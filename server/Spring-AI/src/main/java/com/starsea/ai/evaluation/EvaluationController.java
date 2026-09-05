package com.starsea.ai.evaluation;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.starsea.ai.auth.AuthContext;
import com.starsea.ai.auth.RequireLogin;
import com.starsea.ai.auth.RequireRole;
import com.starsea.ai.domain.dto.AjaxResult;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;

@RestController
@RequireLogin
@RequireRole("tenant_admin")
public class EvaluationController {
    private static final String ROOT="/knowledge/{knowledgeId}/embedding-evaluation";
    private final EmbeddingModelRegistry models;
    private final EvaluationCorpusService corpus;
    private final EvaluationService service;
    private final EvaluationReports reports;

    public EvaluationController(EmbeddingModelRegistry models,EvaluationCorpusService corpus,EvaluationService service,EvaluationReports reports) {
        this.models=models;this.corpus=corpus;this.service=service;this.reports=reports;
    }

    private static long tenant() {
        AuthContext current=AuthContext.current();
        if(current==null||current.getTenantId()==null) throw EvaluationRepository.missing();
        return current.getTenantId();
    }

    @GetMapping("/embedding-evaluation/models") public AjaxResult models() { return AjaxResult.success(models.list(tenant())); }
    @PostMapping("/embedding-evaluation/models/test") public AjaxResult test(@RequestBody ObjectNode command) { return AjaxResult.success(models.test(command)); }
    @PostMapping("/embedding-evaluation/models") public AjaxResult createModel(@RequestBody ObjectNode command) { return AjaxResult.success(models.save(tenant(),null,command)); }
    @PutMapping("/embedding-evaluation/models/{id}") public AjaxResult updateModel(@PathVariable String id,@RequestBody ObjectNode command) { return AjaxResult.success(models.save(tenant(),id,command)); }
    @DeleteMapping("/embedding-evaluation/models/{id}") public AjaxResult deleteModel(@PathVariable String id) { models.delete(tenant(),id); return AjaxResult.success(); }

    @GetMapping(ROOT+"/chunks") public AjaxResult chunks(@PathVariable long knowledgeId) { return AjaxResult.success(corpus.chunks(tenant(),knowledgeId)); }
    @PostMapping(ROOT+"/snapshots") public AjaxResult capture(@PathVariable long knowledgeId,@RequestBody ObjectNode command) { return AjaxResult.success(corpus.capture(tenant(),knowledgeId,command)); }
    @GetMapping(ROOT+"/snapshots/{id}") public AjaxResult snapshot(@PathVariable long knowledgeId,@PathVariable String id) { return AjaxResult.success(corpus.snapshot(tenant(),knowledgeId,id)); }
    @GetMapping(ROOT+"/datasets") public AjaxResult datasets(@PathVariable long knowledgeId) { return AjaxResult.success(corpus.datasets(tenant(),knowledgeId)); }
    @GetMapping(ROOT+"/datasets/{id}") public AjaxResult dataset(@PathVariable long knowledgeId,@PathVariable String id,@RequestParam(required=false) Integer revision) { return AjaxResult.success(corpus.dataset(tenant(),knowledgeId,id,revision)); }
    @PostMapping(ROOT+"/datasets") public AjaxResult createDataset(@PathVariable long knowledgeId,@RequestBody ObjectNode command) { return AjaxResult.success(corpus.saveDataset(tenant(),knowledgeId,null,command)); }
    @PutMapping(ROOT+"/datasets/{id}") public AjaxResult updateDataset(@PathVariable long knowledgeId,@PathVariable String id,@RequestBody ObjectNode command) { return AjaxResult.success(corpus.saveDataset(tenant(),knowledgeId,id,command)); }
    @PostMapping(ROOT+"/runs") public AjaxResult start(@PathVariable long knowledgeId,@RequestBody ObjectNode command) { return AjaxResult.success(service.start(tenant(),knowledgeId,command)); }
    @GetMapping(ROOT+"/runs") public AjaxResult runs(@PathVariable long knowledgeId) { return AjaxResult.success(service.list(tenant(),knowledgeId)); }
    @GetMapping(ROOT+"/runs/{id}") public AjaxResult run(@PathVariable long knowledgeId,@PathVariable String id) { return AjaxResult.success(service.get(tenant(),knowledgeId,id)); }
    @PostMapping(ROOT+"/runs/{id}/cancel") public AjaxResult cancel(@PathVariable long knowledgeId,@PathVariable String id) { return AjaxResult.success(service.cancel(tenant(),knowledgeId,id)); }
    @PostMapping(ROOT+"/runs/{id}/retry") public AjaxResult retry(@PathVariable long knowledgeId,@PathVariable String id) { return AjaxResult.success(service.retry(tenant(),knowledgeId,id)); }

    @GetMapping(ROOT+"/runs/{id}/export")
    public ResponseEntity<byte[]> export(@PathVariable long knowledgeId,@PathVariable String id,@RequestParam(defaultValue="json") String format) {
        ObjectNode run=service.export(tenant(),knowledgeId,id);
        String body; String type;
        switch(format) {
            case "html" -> { body=reports.html(run);type="text/html;charset=UTF-8"; }
            case "csv" -> { body=reports.csv(run);type="text/csv;charset=UTF-8"; }
            case "json" -> { body=reports.manifest(run);type="application/json;charset=UTF-8"; }
            default -> throw EvaluationJson.bad("导出格式必须为 json、csv 或 html");
        }
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(type))
                .header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=embedding-evaluation-"+EvaluationRepository.uuid(id)+"."+format)
                .header("X-Content-Type-Options","nosniff").body(body.getBytes(StandardCharsets.UTF_8));
    }
}
