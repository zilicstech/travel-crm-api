package com.voyra.crm.service;

import com.voyra.crm.enums.SupplierEnvironment;
import com.voyra.crm.enums.SupplierProvider;
import com.voyra.crm.models.ResolvedCredential;
import com.voyra.crm.support.LogCapture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class SupplierFanOutTest {

    private final SupplierFanOut fanOut = new SupplierFanOut();

    @AfterEach
    void cleanUp() {
        fanOut.shutdown();
        MDC.clear();
    }

    private static ResolvedCredential supplier(String id, String name) {
        return new ResolvedCredential(id, name, SupplierProvider.MOCK, SupplierEnvironment.MOCK, "mock://local", "", null, null);
    }

    @Test
    void collectsEveryAnswerInSupplierOrder() {
        SupplierFanOut.Result<String> result = fanOut.run(List.of(supplier("a", "A"), supplier("b", "B")), s -> {
            if (s.id().equals("a")) {
                sleep(100); // the first supplier finishes last; the order is still the suppliers' order
            }
            return List.of(s.id() + "1", s.id() + "2");
        });

        assertThat(result.answers()).containsExactly("a1", "a2", "b1", "b2");
        assertThat(result.failed()).isEmpty();
    }

    @Test
    void aThrowingSupplierIsFailedByName() {
        SupplierFanOut.Result<String> result = fanOut.run(List.of(supplier("a", "Tripjack"), supplier("b", "Mock")), s -> {
            if (s.id().equals("a")) {
                throw new IllegalStateException("secret-key-123");
            }
            return List.of("ok");
        });

        assertThat(result.failed()).containsExactly("Tripjack");
        assertThat(result.answers()).containsExactly("ok");
    }

    @Test
    void theLogNamesTheErrorClassAndNeverItsMessage() {
        try (LogCapture log = new LogCapture(SupplierFanOut.class)) {
            fanOut.run(List.of(supplier("a", "Tripjack")), s -> {
                throw new IllegalStateException("secret-key-123");
            });

            assertThat(log.lines()).containsExactly("WARN Supplier Tripjack search failed: IllegalStateException");
        }
    }

    @Test
    void aSupplierStillRunningAtTheDeadlineIsFailed() {
        SupplierFanOut quick = new SupplierFanOut(Executors.newFixedThreadPool(2), Duration.ofMillis(50));
        try {
            SupplierFanOut.Result<String> result = quick.run(List.of(supplier("a", "Slow"), supplier("b", "Fast")), s -> {
                if (s.id().equals("a")) {
                    sleep(500);
                }
                return List.of(s.id());
            });

            assertThat(result.failed()).containsExactly("Slow");
            assertThat(result.answers()).containsExactly("b");
        } finally {
            quick.shutdown();
        }
    }

    @Test
    void aRejectedTaskIsFailed() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        SupplierFanOut tiny = new SupplierFanOut(new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new SynchronousQueue<>()),
                Duration.ofSeconds(5));
        try {
            SupplierFanOut.Result<String> result = tiny.run(List.of(supplier("a", "First"), supplier("b", "Second")), s -> {
                if (s.id().equals("a")) {
                    try {
                        release.await(200, TimeUnit.MILLISECONDS);
                    } catch (InterruptedException e) {
                        throw new IllegalStateException(e);
                    }
                }
                return List.of(s.id());
            });

            assertThat(result.failed()).containsExactly("Second");
            assertThat(result.answers()).containsExactly("a");
        } finally {
            release.countDown();
            tiny.shutdown();
        }
    }

    @Test
    void theTaskSeesTheCallersMdc() {
        MDC.put("requestId", "req-7");

        SupplierFanOut.Result<String> result = fanOut.run(List.of(supplier("a", "A")), s -> List.of(String.valueOf(MDC.get("requestId"))));

        assertThat(result.answers()).containsExactly("req-7");
    }

    @Test
    void aCallerWithNoMdcRunsWithNone() {
        SupplierFanOut.Result<String> result = fanOut.run(List.of(supplier("a", "A")), s -> List.of(String.valueOf(MDC.get("requestId"))));

        assertThat(result.answers()).containsExactly("null");
    }

    @Test
    void aHungSupplierNeverStarvesAnotherSupplier() {
        CountDownLatch hang = new CountDownLatch(1);
        AtomicInteger hungCalls = new AtomicInteger();
        SupplierFanOut shared = new SupplierFanOut(new ThreadPoolExecutor(8, 8, 0, TimeUnit.MILLISECONDS, new java.util.concurrent.ArrayBlockingQueue<>(64)),
                Duration.ofMillis(300));
        try {
            for (int search = 0; search < 10; search++) {
                SupplierFanOut.Result<String> result = shared.run(List.of(supplier("h", "Hung"), supplier("f", "Fast")), s -> {
                    if (s.id().equals("h")) {
                        hungCalls.incrementAndGet();
                        await(hang);
                    }
                    return List.of(s.id());
                });

                assertThat(result.failed()).as("search %d", search).containsExactly("Hung");
                assertThat(result.answers()).as("search %d", search).containsExactly("f");
            }
            assertThat(hungCalls).hasValue(4); // at most half the pool is ever held by one supplier
        } finally {
            hang.countDown();
            shared.shutdown();
        }
    }

    @Test
    void aSupplierOverItsCapIsFailedAtOnceAndServedAgainOnceItsCallsReturn() throws Exception {
        CountDownLatch hang = new CountDownLatch(1);
        AtomicInteger hungCalls = new AtomicInteger();
        SupplierFanOut shared = new SupplierFanOut(Executors.newFixedThreadPool(8), Duration.ofMillis(100));
        try {
            for (int search = 0; search < 4; search++) {
                shared.run(List.of(supplier("h", "Hung")), s -> {
                    hungCalls.incrementAndGet();
                    await(hang);
                    return List.of(s.id());
                });
            }
            try (LogCapture log = new LogCapture(SupplierFanOut.class)) {
                SupplierFanOut.Result<String> over = shared.run(List.of(supplier("h", "Hung"), supplier("f", "Fast")), s -> List.of(s.id()));

                assertThat(over.failed()).containsExactly("Hung");
                assertThat(over.answers()).containsExactly("f");
                assertThat(log.lines()).containsExactly("WARN Supplier Hung search failed: SupplierBusyException");
            }
            assertThat(hungCalls).hasValue(4);

            hang.countDown();
            SupplierFanOut.Result<String> recovered = null;
            for (int attempt = 0; attempt < 50 && (recovered == null || !recovered.failed().isEmpty()); attempt++) {
                sleep(20);
                recovered = shared.run(List.of(supplier("h", "Hung")), s -> List.of(s.id()));
            }
            assertThat(recovered.answers()).containsExactly("h");
        } finally {
            hang.countDown();
            shared.shutdown();
        }
    }

    @Test
    void aTaskStillQueuedAtTheDeadlineNeverCallsItsSupplier() throws Exception {
        CountDownLatch hang = new CountDownLatch(1);
        AtomicInteger queuedCalls = new AtomicInteger();
        ThreadPoolExecutor single = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new java.util.concurrent.ArrayBlockingQueue<>(4));
        SupplierFanOut quick = new SupplierFanOut(single, Duration.ofMillis(100));
        try {
            SupplierFanOut.Result<String> result = quick.run(List.of(supplier("a", "Hung"), supplier("b", "Queued")), s -> {
                if (s.id().equals("a")) {
                    await(hang);
                } else {
                    queuedCalls.incrementAndGet();
                }
                return List.of(s.id());
            });
            assertThat(result.failed()).containsExactly("Hung", "Queued");

            hang.countDown();
            single.shutdown();
            assertThat(single.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
            assertThat(queuedCalls).hasValue(0);
        } finally {
            hang.countDown();
            quick.shutdown();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
