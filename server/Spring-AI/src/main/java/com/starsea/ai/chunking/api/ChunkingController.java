package com.starsea.ai.chunking.api;

import com.starsea.ai.auth.RequireLogin;
import com.starsea.ai.auth.RequireRole;
import com.starsea.ai.chunking.preview.ChunkCommandService;
import com.starsea.ai.chunking.preview.ChunkPreviewService;
import com.starsea.ai.chunking.indexing.ChunkVectorService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static com.starsea.ai.chunking.api.ChunkingApiModels.PreviewRequest;
import static com.starsea.ai.chunking.api.ChunkingApiModels.EditChunkRequest;
import static com.starsea.ai.chunking.api.ChunkingApiModels.ChunkResponse;
import static com.starsea.ai.chunking.api.ChunkingApiModels.ConfirmRequest;
import static com.starsea.ai.chunking.api.ChunkingApiModels.ProcessingResponse;
import static com.starsea.ai.chunking.api.ChunkingApiModels.StrategyResponse;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/knowledge/{knowledgeId}/files/{fileId}")
@RequireLogin
public class ChunkingController {

    private final ChunkPreviewService service;
    private final ChunkCommandService commandService;
    private final ChunkVectorService vectorService;

    @Autowired
    public ChunkingController(ChunkPreviewService service, ChunkCommandService commandService,
                              ChunkVectorService vectorService) {
        this.service = service;
        this.commandService = commandService;
        this.vectorService = vectorService;
    }

    /** Retained for narrow standalone controller tests from Tasks 6 and 8. */
    public ChunkingController(ChunkPreviewService service, ChunkCommandService commandService) {
        this(service, commandService, null);
    }

    @GetMapping("/chunk-strategies")
    public StrategyResponse strategies(@PathVariable long knowledgeId, @PathVariable long fileId) {
        return service.strategies(knowledgeId, fileId);
    }

    @RequireLogin
    @RequireRole("tenant_admin")
    @PostMapping("/chunk-preview")
    public ResponseEntity<Void> preview(@PathVariable long knowledgeId, @PathVariable long fileId,
                                        @RequestBody PreviewRequest request) {
        service.startPreview(knowledgeId, fileId, request);
        return ResponseEntity.accepted().build();
    }

    @GetMapping("/processing")
    public ProcessingResponse processing(@PathVariable long knowledgeId, @PathVariable long fileId) {
        return service.processing(knowledgeId, fileId);
    }

    @GetMapping("/chunks")
    public List<ChunkResponse> chunks(@PathVariable long knowledgeId, @PathVariable long fileId) {
        return commandService.list(knowledgeId, fileId);
    }

    @RequireRole("tenant_admin")
    @PatchMapping("/chunks/{chunkPublicId}")
    public ChunkResponse editChunk(@PathVariable long knowledgeId, @PathVariable long fileId,
                                   @PathVariable UUID chunkPublicId,
                                   @RequestBody EditChunkRequest request) {
        return commandService.edit(knowledgeId, fileId, chunkPublicId, request);
    }

    @RequireRole("tenant_admin")
    @DeleteMapping("/chunks/{chunkPublicId}")
    public ResponseEntity<Void> deleteChunk(@PathVariable long knowledgeId, @PathVariable long fileId,
                                            @PathVariable UUID chunkPublicId,
                                            @RequestParam int lockVersion) {
        commandService.delete(knowledgeId, fileId, chunkPublicId, lockVersion);
        return ResponseEntity.noContent().build();
    }

    @RequireRole("tenant_admin")
    @PostMapping("/confirm")
    public ResponseEntity<Void> confirm(@PathVariable long knowledgeId, @PathVariable long fileId,
                                        @RequestBody ConfirmRequest request) {
        vectorService.confirm(knowledgeId, fileId, request);
        return ResponseEntity.accepted().build();
    }

    @RequireRole("tenant_admin")
    @PostMapping("/chunks/{chunkPublicId}/reindex")
    public ResponseEntity<Void> reindex(@PathVariable long knowledgeId, @PathVariable long fileId,
                                        @PathVariable UUID chunkPublicId) {
        vectorService.reindex(knowledgeId, fileId, chunkPublicId);
        return ResponseEntity.accepted().build();
    }
}
