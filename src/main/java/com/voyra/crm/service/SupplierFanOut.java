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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * Asks every supplier at once and waits up to an overall deadline. A supplier that throws, is rejected by a full
 * queue or is still running at the deadline is reported by name instead of failing the others.
 */
@Component
@Slf4j
public class SupplierFanOut {

    /** What the suppliers that answered returned (in supplier order) and the names of those that did not. */
    public record Result<T>(List<T> answers, List<String> failed) { }

    private final ExecutorService executor;
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
        Map<String, String> mdc = MDC.getCopyOfContextMap();
        List<CompletableFuture<List<T>>> futures = new ArrayList<>();
        for (ResolvedCredential supplier : suppliers) {
            try {
                futures.add(CompletableFuture.supplyAsync(() -> {
                    if (mdc != null) {
                        MDC.setContextMap(mdc);
                    }
                    try {
                        return call.apply(supplier);
                    } finally {
                        MDC.clear();
                    }
                }, executor));
            } catch (RejectedExecutionException e) {
                futures.add(CompletableFuture.failedFuture(e));
            }
        }
        try {
            CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).orTimeout(deadline.toMillis(), TimeUnit.MILLISECONDS).join();
        } catch (CompletionException e) {
            // a late or failed supplier is judged one by one below
        }
        List<T> answers = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        for (int i = 0; i < suppliers.size(); i++) {
            CompletableFuture<List<T>> future = futures.get(i);
            String error = null;
            if (!future.isDone()) {
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
