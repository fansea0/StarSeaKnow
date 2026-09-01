package com.starsea.ai.chunking.processing;

import com.starsea.ai.auth.AuthContext;
import com.starsea.ai.chunking.model.PipelineState;
import com.starsea.ai.domain.FileProcessing;
import com.starsea.ai.mapper.FileProcessingMapper;
import org.springframework.stereotype.Service;

import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

/** Owns every legal, tenant-scoped file pipeline transition. */
@Service
public class FileProcessingService {

    private static final Map<PipelineState, Set<PipelineState>> LEGAL_TRANSITIONS = legalTransitions();

    private final FileProcessingMapper mapper;

    public FileProcessingService(FileProcessingMapper mapper) {
        this.mapper = mapper;
    }

    public Transition transition(long knowledgeId, long fileId, PipelineState expected,
                                 PipelineState target, int lockVersion) {
        if (target == PipelineState.FAILED) {
            throw new IllegalArgumentException("Use fail() when entering FAILED state");
        }
        return transition(knowledgeId, fileId, expected, target, lockVersion,
                defaultProgress(target), null, null);
    }

    public Transition fail(long knowledgeId, long fileId, PipelineState expected,
                           int lockVersion, int progress, String lastError) {
        if (lastError == null || lastError.isBlank()) {
            throw new IllegalArgumentException("A failed transition requires an error summary");
        }
        return transition(knowledgeId, fileId, expected, PipelineState.FAILED, lockVersion,
                progress, expected.code(), lastError);
    }

    public void restoreAfterRejectedDispatch(long knowledgeId, long fileId,
                                             PipelineState asynchronousState,
                                             PipelineState previousState,
                                             int lockVersion, int previousProgress,
                                             Integer previousFailedFromState,
                                             String previousError) {
        long tenantId = requireTenantId();
        if (asynchronousState != PipelineState.CHUNKING
                && asynchronousState != PipelineState.VECTORIZING) {
            throw new IllegalArgumentException("Only an asynchronous state can be restored");
        }
        int updated = mapper.transition(fileId, tenantId, knowledgeId,
                asynchronousState.code(), previousState.code(), previousProgress,
                lockVersion, previousFailedFromState, previousError);
        if (updated != 1) {
            throw new StateConflictException("Pipeline state changed before dispatch could be restored");
        }
    }

    private Transition transition(long knowledgeId, long fileId, PipelineState expected,
                                  PipelineState target, int lockVersion, int progress,
                                  Integer failedFromState, String lastError) {
        long tenantId = requireTenantId();
        FileProcessing processing = mapper.selectById(fileId);
        if (processing == null
                || !Long.valueOf(tenantId).equals(processing.getTenantId())
                || !Long.valueOf(knowledgeId).equals(processing.getKnowledgeId())
                || !Long.valueOf(fileId).equals(processing.getFileId())) {
            throw new OwnershipException("File does not belong to the current tenant and knowledge base");
        }
        if (!LEGAL_TRANSITIONS.getOrDefault(expected, Set.of()).contains(target)) {
            throw new StateConflictException("Illegal pipeline transition: " + expected + " -> " + target);
        }
        if (!Integer.valueOf(expected.code()).equals(processing.getPipelineState())
                || !Integer.valueOf(lockVersion).equals(processing.getLockVersion())) {
            throw new StateConflictException("Pipeline state or lock version is stale");
        }
        int updated = mapper.transition(fileId, tenantId, knowledgeId, expected.code(), target.code(),
                progress, lockVersion, failedFromState, lastError);
        if (updated != 1) {
            throw new StateConflictException("Pipeline state or lock version changed concurrently");
        }
        return new Transition(knowledgeId, fileId, expected, target, lockVersion + 1,
                processing.getProgress(), processing.getFailedFromState(), processing.getLastError());
    }

    private long requireTenantId() {
        AuthContext context = AuthContext.current();
        if (context == null || context.getTenantId() == null) {
            throw new OwnershipException("A tenant context is required");
        }
        return context.getTenantId();
    }

    private static int defaultProgress(PipelineState target) {
        return switch (target) {
            case CHUNKED, ADJUSTING, COMPLETED -> 100;
            default -> 0;
        };
    }

    private static Map<PipelineState, Set<PipelineState>> legalTransitions() {
        Map<PipelineState, Set<PipelineState>> transitions = new EnumMap<>(PipelineState.class);
        transitions.put(PipelineState.UPLOADED, Set.of(PipelineState.CHUNKING));
        transitions.put(PipelineState.CHUNKING, Set.of(PipelineState.CHUNKED, PipelineState.FAILED));
        transitions.put(PipelineState.CHUNKED,
                Set.of(PipelineState.CHUNKING, PipelineState.ADJUSTING, PipelineState.CONFIRMED));
        transitions.put(PipelineState.ADJUSTING,
                Set.of(PipelineState.CHUNKING, PipelineState.CONFIRMED,
                        PipelineState.COMPLETED, PipelineState.FAILED));
        transitions.put(PipelineState.CONFIRMED,
                Set.of(PipelineState.VECTORIZING, PipelineState.FAILED));
        transitions.put(PipelineState.VECTORIZING,
                Set.of(PipelineState.COMPLETED, PipelineState.FAILED));
        transitions.put(PipelineState.COMPLETED, Set.of(PipelineState.ADJUSTING));
        transitions.put(PipelineState.FAILED,
                Set.of(PipelineState.CHUNKING, PipelineState.ADJUSTING, PipelineState.CONFIRMED));
        return Map.copyOf(transitions);
    }

    public record Transition(long knowledgeId, long fileId, PipelineState previous,
                             PipelineState current, int lockVersion, int previousProgress,
                             Integer previousFailedFromState, String previousError) { }

    public static class StateConflictException extends RuntimeException {
        public StateConflictException(String message) {
            super(message);
        }
    }

    public static class OwnershipException extends RuntimeException {
        public OwnershipException(String message) {
            super(message);
        }
    }
}
