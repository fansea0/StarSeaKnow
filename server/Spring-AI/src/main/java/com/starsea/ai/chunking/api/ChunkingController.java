package com.starsea.ai.chunking.api;

import com.starsea.ai.auth.RequireLogin;
import com.starsea.ai.auth.RequireRole;
import com.starsea.ai.chunking.preview.ChunkCommandService;
import com.starsea.ai.chunking.preview.ChunkPreviewService;
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

    public ChunkingController(ChunkPreviewService service, ChunkCommandService commandService) {
        this.service = service;
        this.commandService = commandService;
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
}
