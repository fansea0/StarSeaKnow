package com.starsea.ai.chunking.processing;

import com.starsea.ai.auth.AuthContext;
import com.starsea.ai.chunking.model.PipelineState;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/** Claims a pipeline state before handing tenant-aware work to the dedicated chunking pool. */
@Component
public class ChunkTaskDispatcher {

    private final FileProcessingService processingService;
    private final Executor executor;

    public ChunkTaskDispatcher(FileProcessingService processingService,
                               @Qualifier("chunkingTaskExecutor") Executor executor) {
        this.processingService = processingService;
        this.executor = executor;
    }

    public void dispatch(long knowledgeId, long fileId, PipelineState expected,
                         PipelineState asynchronousState, int lockVersion, Runnable task) {
        AuthContext context = AuthContext.current();
        if (context == null) {
            throw new FileProcessingService.OwnershipException("An authentication context is required");
        }
        Runnable work = Objects.requireNonNull(task, "task");
        FileProcessingService.Transition transition = processingService.transition(
                knowledgeId, fileId, expected, asynchronousState, lockVersion);
        try {
            executor.execute(() -> {
                AuthContext.set(new AuthContext(context.getKind(), context.getUserId(),
                        context.getTenantId(), context.getRole(), context.getJti()));
                try {
                    work.run();
                } finally {
                    AuthContext.clear();
                }
            });
        } catch (RejectedExecutionException rejected) {
            try {
                processingService.restoreAfterRejectedDispatch(knowledgeId, fileId,
                        asynchronousState, expected, transition.lockVersion(),
                        transition.previousProgress(), transition.previousFailedFromState(),
                        transition.previousError());
            } catch (RuntimeException restoreFailure) {
                rejected.addSuppressed(restoreFailure);
            }
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Chunking service is temporarily unavailable", rejected);
        }
    }
}
