package com.fansea.ai.openapi.retrieval;

import com.fansea.ai.auth.AuthContext;
import com.fansea.ai.openapi.auth.ExternalApiTransportProperties;
import com.fansea.ai.service.RagService;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public final class RetrievalDeadlineExecutor implements AutoCloseable {
    private final RagService ragService;
    private final Duration timeout;
    private final ThreadPoolExecutor executor;

    @Autowired
    public RetrievalDeadlineExecutor(RagService ragService, ExternalApiTransportProperties properties) {
        this(ragService, properties.getRetrievalTimeout(), properties.getRetrievalExecutorThreads(),
                properties.getRetrievalExecutorQueueCapacity());
    }

    RetrievalDeadlineExecutor(RagService ragService, Duration timeout, int threads, int queueCapacity) {
        this.ragService = Objects.requireNonNull(ragService, "ragService");
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        int poolSize = Math.max(1, threads);
        this.executor = new ThreadPoolExecutor(poolSize, poolSize, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(Math.max(1, queueCapacity)), daemonThreadFactory(),
                new ThreadPoolExecutor.AbortPolicy());
    }

    public List<RetrievedChunk> retrieve(RetrievalQuery query, AuthContext context) throws TimeoutException {
        Future<List<RetrievedChunk>> future = executor.submit(() -> withContext(context, () -> ragService.retrieve(query)));
        try {
            return future.get(timeout.toNanos(), TimeUnit.NANOSECONDS);
        } catch (TimeoutException exception) {
            future.cancel(true);
            executor.purge();
            throw exception;
        } catch (InterruptedException exception) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new IllegalStateException("retrieval interrupted", exception);
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof RuntimeException runtime) throw runtime;
            if (cause instanceof Error error) throw error;
            throw new IllegalStateException("retrieval failed", cause);
        }
    }

    void executeProbe(Runnable probe) throws Exception {
        executor.submit(() -> withContext(null, () -> { probe.run(); return null; })).get(1, TimeUnit.SECONDS);
    }

    private <T> T withContext(AuthContext context, java.util.concurrent.Callable<T> operation) throws Exception {
        AuthContext.clear();
        try {
            if (context != null) AuthContext.set(context);
            return operation.call();
        } finally {
            AuthContext.clear();
        }
    }

    private static ThreadFactory daemonThreadFactory() {
        AtomicInteger sequence = new AtomicInteger();
        return task -> {
            Thread thread = new Thread(task, "rag-retrieval-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    @Override
    @PreDestroy
    public void close() {
        executor.shutdownNow();
    }
}
