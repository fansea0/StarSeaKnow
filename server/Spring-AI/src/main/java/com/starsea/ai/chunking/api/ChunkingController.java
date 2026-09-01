package com.starsea.ai.chunking.api;

import com.starsea.ai.auth.RequireLogin;
import com.starsea.ai.auth.RequireRole;
import com.starsea.ai.chunking.preview.ChunkPreviewService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static com.starsea.ai.chunking.api.ChunkingApiModels.PreviewRequest;
import static com.starsea.ai.chunking.api.ChunkingApiModels.ProcessingResponse;
import static com.starsea.ai.chunking.api.ChunkingApiModels.StrategyResponse;

@RestController
@RequestMapping("/knowledge/{knowledgeId}/files/{fileId}")
@RequireLogin
public class ChunkingController {

    private final ChunkPreviewService service;

    public ChunkingController(ChunkPreviewService service) {
        this.service = service;
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
}
