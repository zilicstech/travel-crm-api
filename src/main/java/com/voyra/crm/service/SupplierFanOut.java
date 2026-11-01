package com.voyra.crm.service;

import com.voyra.crm.models.ResolvedCredential;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

/**
 * Asks every supplier at once and waits up to an overall deadline. A supplier that throws, is rejected by a full
 * queue, already has {@link #PER_SUPPLIER} calls in flight or is still running at the deadline is reported by name
 * instead of failing the others. A task that only starts after its search's deadline never calls its supplier.
 */
@Component
@Slf4j
public class SupplierFanOut {

    /** What the suppliers that answered returned (in supplier order) and the names of those that did not. */
    public record Result<T>(List<T> answers, List<String> failed) { }

    /** At most half the pool may wait on one supplier, so a hung supplier cannot starve the others. */
    static final int PER_SUPPLIER = 4;

    /** Refused because the supplier already has {@link #PER_SUPPLIER} calls in flight. */
    static final class SupplierBusyException extends RuntimeException { }

    private final ExecutorService executor;
    private final Map<String, Semaphore> inFlight = new ConcurrentHashMap<>();
    private final Duration deadline;

    public SupplierFanOut() {
        this(new ThreadPoolExecutor(8, 8, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(64), runnable -> {
            Thread t = new Thread(runnable, "supplier-fan-out");
            t.setDaemon(true);
            return t;
        }), Duration.ofSeconds(40));
    }

    SupplierFanOut(ExecutorService executor, Duration deadline) {
        this.executor = executor;
        this.deadline = deadline;
    }

    public <T> Result<T> run(List<ResolvedCredential> suppliers, Function<ResolvedCredential, List<T>> call) {
        long deadlineAt = System.nanoTime() + deadline.toNanos();
        Map<String, String> mdc = MDC.getCopyOfContextMap();
        List<CompletableFuture<List<T>>> futures = new ArrayList<>();
        for (ResolvedCredential supplier : suppliers) {
            Semaphore permits = inFlight.computeIfAbsent(supplier.id(), id -> new Semaphore(PER_SUPPLIER));
            if (!permits.tryAcquire()) {
                futures.add(CompletableFuture.failedFuture(new SupplierBusyException()));
                continue;
            }
            CompletableFuture<List<T>> future = new CompletableFuture<>();
            try {
                executor.execute(() -> {
                    try {
                        if (System.nanoTime() - deadlineAt > 0) {
                            // queued past the deadline: the search has already reported this supplier as failed
                            future.completeExceptionally(new TimeoutException());
                            return;
                        }
                        if (mdc != null) {
                            MDC.setContextMap(mdc);
                        }
                        future.complete(call.apply(supplier));
                    } catch (Throwable e) {
                        future.completeExceptionally(e);
                    } finally {
                        MDC.clear();
                        permits.release();
                    }
                });
            } catch (RejectedExecutionException e) {
                permits.release();
                future.completeExceptionally(e);
            }
            futures.add(future);
        }
        try {
            CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new))
                    .orTimeout(Math.max(0, deadlineAt - System.nanoTime()), TimeUnit.NANOSECONDS).join();
        } catch (CompletionException e) {
            // a late or failed supplier is judged one by one below
        }
        List<T> answers = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        for (int i = 0; i < suppliers.size(); i++) {
            CompletableFuture<List<T>> future = futures.get(i);
            String error = null;
            if (!future.isDone()) {
                future.cancel(true);
                error = "no answer by the deadline";
            } else {
                try {
                    answers.addAll(future.join());
                } catch (CompletionException e) {
                    error = e.getCause().getClass().getSimpleName();
                }
            }
            if (error != null) {
                failed.add(suppliers.get(i).name());
                log.warn("Supplier {} search failed: {}", suppliers.get(i).name(), error);
            }
        }
        return new Result<>(answers, failed);
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }
}
