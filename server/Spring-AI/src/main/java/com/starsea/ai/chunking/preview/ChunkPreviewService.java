package com.starsea.ai.chunking.preview;

import com.starsea.ai.auth.AuthContext;
import com.starsea.ai.chunking.api.ChunkingApiModels.PreviewRequest;
import com.starsea.ai.chunking.api.ChunkingApiModels.ProcessingResponse;
import com.starsea.ai.chunking.api.ChunkingApiModels.StrategyResponse;
import com.starsea.ai.chunking.api.ChunkingException;
import com.starsea.ai.chunking.model.ChunkStatus;
import com.starsea.ai.chunking.model.PipelineState;
import com.starsea.ai.chunking.processing.ChunkTaskDispatcher;
import com.starsea.ai.chunking.registry.ChunkStrategyNotFoundException;
import com.starsea.ai.chunking.registry.ChunkStrategyRegistry;
import com.starsea.ai.chunking.registry.DocumentStructureParserRegistry;
import com.starsea.ai.domain.DocumentChunk;
import com.starsea.ai.domain.File;
import com.starsea.ai.domain.FileProcessing;
import com.starsea.ai.mapper.DocumentChunkMapper;
import com.starsea.ai.mapper.FileMapper;
import com.starsea.ai.mapper.FileProcessingMapper;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

@Service
public class ChunkPreviewService {

    private static final String MARKDOWN_STRATEGY = "MARKDOWN_OPTIMIZED";

    private final FileProcessingMapper processingMapper;
    private final FileMapper fileMapper;
    private final DocumentChunkMapper chunkMapper;
    private final ChunkStrategyRegistry strategyRegistry;
    private final DocumentStructureParserRegistry parserRegistry;
    private final ChunkTaskDispatcher dispatcher;
    private final ChunkPreviewWorker worker;

    public ChunkPreviewService(FileProcessingMapper processingMapper,
                               FileMapper fileMapper,
                               DocumentChunkMapper chunkMapper,
                               ChunkStrategyRegistry strategyRegistry,
                               DocumentStructureParserRegistry parserRegistry,
                               ChunkTaskDispatcher dispatcher,
                               ChunkPreviewWorker worker) {
        this.processingMapper = processingMapper;
        this.fileMapper = fileMapper;
        this.chunkMapper = chunkMapper;
        this.strategyRegistry = strategyRegistry;
        this.parserRegistry = parserRegistry;
        this.dispatcher = dispatcher;
        this.worker = worker;
    }

    public StrategyResponse strategies(long knowledgeId, long fileId) {
        ScopedFile scoped = requireScopedFile(knowledgeId, fileId);
        List<com.starsea.ai.chunking.registry.ChunkStrategyDescriptor> strategies;
        try {
            strategies = List.of(strategyRegistry.require(MARKDOWN_STRATEGY, scoped.fileType()).descriptor());
        } catch (ChunkStrategyNotFoundException exception) {
            strategies = List.of();
        }
        return new StrategyResponse(scoped.fileType(), strategies);
    }

    public ProcessingResponse processing(long knowledgeId, long fileId) {
        ScopedFile scoped = requireScopedFile(knowledgeId, fileId);
        FileProcessing processing = scoped.processing();
        return new ProcessingResponse(
                processing.getPipelineState(),
                processing.getFailedFromState(),
                processing.getProgress(),
                processing.getLastError(),
                processing.getLockVersion(),
                processing.getStrategyCode(),
                processing.getPolicySnapshot(),
                processing.getContextPolicy());
    }

    public void startPreview(long knowledgeId, long fileId, PreviewRequest request) {
        if (request == null || request.strategyConfig() == null) {
            throw ChunkingException.unprocessable("A chunk strategy and token budget are required");
        }
        ScopedFile scoped = requireScopedFile(knowledgeId, fileId);
        requireRegisteredStrategy(request.strategyCode(), scoped.fileType());
        requireRegisteredParser(scoped.fileType());
        requireUsableSource(scoped.file());
        List<ChunkPreviewWorker.ExistingChunkSnapshot> existingChunks =
                requireReplaceableDrafts(scoped, request.replaceEditedDrafts());

        PipelineState current = currentState(scoped.processing());
        requireEligibleState(scoped.processing(), current);
        if (request.lockVersion() != scoped.processing().getLockVersion()) {
            throw ChunkingException.conflict("Pipeline state or lock version is stale");
        }

        ChunkPreviewWorker.Job job = new ChunkPreviewWorker.Job(
                knowledgeId,
                fileId,
                request.strategyCode().trim().toUpperCase(Locale.ROOT),
                request.strategyConfig(),
                request.replaceEditedDrafts(),
                request.lockVersion() + 1,
                existingChunks);
        dispatcher.dispatch(knowledgeId, fileId, current, PipelineState.CHUNKING,
                request.lockVersion(), () -> worker.generate(job));
    }

    private void requireRegisteredStrategy(String code, String fileType) {
        try {
            strategyRegistry.require(code, fileType);
        } catch (ChunkStrategyNotFoundException | NullPointerException exception) {
            throw ChunkingException.unprocessable("The requested chunk strategy is not available for this file");
        }
    }

    private void requireRegisteredParser(String fileType) {
        try {
            parserRegistry.require(fileType);
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw ChunkingException.unprocessable("The file type is not supported for chunk preview");
        }
    }

    private void requireUsableSource(File file) {
        try {
            byte[] bytes = Files.readAllBytes(Path.of(file.getPath()));
            if (bytes.length == 0) {
                throw ChunkingException.unprocessable("The source document is empty");
            }
        } catch (IOException | InvalidPathException | NullPointerException exception) {
            throw ChunkingException.unprocessable("The source document cannot be read");
        }
    }

    private List<ChunkPreviewWorker.ExistingChunkSnapshot> requireReplaceableDrafts(
            ScopedFile scoped, boolean replaceEditedDrafts) {
        List<DocumentChunk> chunks = chunkMapper.findByFile(
                scoped.processing().getFileId(), scoped.tenantId(), scoped.processing().getKnowledgeId());
        for (DocumentChunk chunk : chunks) {
            ChunkStatus status;
            try {
                status = ChunkStatus.fromCode(chunk.getStatus());
            } catch (IllegalArgumentException | NullPointerException exception) {
                throw ChunkingException.conflict("Existing chunks are in an unknown state");
            }
            if (status == ChunkStatus.ACTIVE) {
                throw ChunkingException.conflict("ACTIVE chunks cannot be regenerated as a full preview");
            }
            if (status != ChunkStatus.DRAFT) {
                throw ChunkingException.conflict("Chunks currently being indexed cannot be regenerated");
            }
            if (Boolean.TRUE.equals(chunk.getIsModified()) && !replaceEditedDrafts) {
                throw ChunkingException.conflict("Edited DRAFT chunks require explicit replacement confirmation");
            }
        }
        return chunks.stream().map(ChunkPreviewWorker.ExistingChunkSnapshot::from).toList();
    }

    private void requireEligibleState(FileProcessing processing, PipelineState current) {
        if (current == PipelineState.UPLOADED || current == PipelineState.CHUNKED
                || current == PipelineState.ADJUSTING) {
            return;
        }
        if (current == PipelineState.FAILED
                && Integer.valueOf(PipelineState.CHUNKING.code()).equals(processing.getFailedFromState())) {
            return;
        }
        throw ChunkingException.conflict("The current processing state cannot start chunk preview");
    }

    private PipelineState currentState(FileProcessing processing) {
        try {
            return PipelineState.fromCode(processing.getPipelineState());
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw ChunkingException.conflict("The current processing state is invalid");
        }
    }

    private ScopedFile requireScopedFile(long knowledgeId, long fileId) {
        AuthContext context = AuthContext.current();
        Long tenantId = context == null ? null : context.getTenantId();
        FileProcessing processing = processingMapper.selectById(fileId);
        if (tenantId == null || processing == null
                || !Long.valueOf(fileId).equals(processing.getFileId())
                || !tenantId.equals(processing.getTenantId())
                || !Long.valueOf(knowledgeId).equals(processing.getKnowledgeId())) {
            throw ChunkingException.notFound("File was not found in the current tenant and knowledge base");
        }
        File file = fileMapper.selectById(fileId);
        if (file == null || !Long.valueOf(fileId).equals(file.getId())) {
            throw ChunkingException.notFound("File was not found in the current tenant and knowledge base");
        }
        return new ScopedFile(tenantId, processing, file, normalizeFileType(file.getType()));
    }

    private String normalizeFileType(String type) {
        if (type == null || type.isBlank()) {
            throw ChunkingException.unprocessable("The file type is missing");
        }
        String normalized = type.trim().toLowerCase(Locale.ROOT);
        return normalized.startsWith(".") ? normalized.substring(1) : normalized;
    }

    private record ScopedFile(long tenantId, FileProcessing processing, File file, String fileType) {
    }
}
