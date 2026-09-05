package com.starsea.ai.evaluation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.starsea.ai.auth.AuthContext;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

@Service
public class EvaluationService {
    private final EvaluationRepository repository;
    private final EvaluationCorpusService corpus;
    private final EmbeddingModelRegistry models;
    private final EvaluationWorker worker;
    private final ObjectMapper json;
    private final ThreadPoolExecutor executor;
    private final ConcurrentHashMap<String,Future<?>> tasks=new ConcurrentHashMap<>();
    private final String instance=UUID.randomUUID().toString();
    private final ScheduledExecutorService heartbeat=Executors.newSingleThreadScheduledExecutor(r->{
        Thread thread=new Thread(r,"embedding-evaluation-heartbeat"); thread.setDaemon(true); return thread;
    });

    public EvaluationService(EvaluationRepository repository, EvaluationCorpusService corpus, EmbeddingModelRegistry models,
                              EvaluationWorker worker,ObjectMapper json,@Value("${embedding-evaluation.queue-capacity:16}") int capacity) {
        this.repository=repository; this.corpus=corpus; this.models=models; this.worker=worker; this.json=json;
        executor=new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(capacity),r->{
            Thread thread=new Thread(r,"embedding-evaluation"); thread.setDaemon(true); return thread;
        },new ThreadPoolExecutor.AbortPolicy());
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recover() {
        repository.recoverInterruptedRuns();
        heartbeat.scheduleWithFixedDelay(()->{
            try { repository.heartbeat(instance); repository.recoverInterruptedRuns(); }
            catch(RuntimeException ex) { org.slf4j.LoggerFactory.getLogger(EvaluationService.class).error("event=evaluation_heartbeat_failed",ex); }
        },0,30,TimeUnit.SECONDS);
    }

    @PreDestroy public void shutdown() {
        heartbeat.shutdownNow(); executor.shutdownNow(); repository.stopOwnedRuns(instance);
    }

    public ObjectNode start(long tenant,long knowledge,ObjectNode command) {
        corpus.authorize(tenant,knowledge);
        String phase=command.path("phase").asText("QUICK");
        if(!Set.of("QUICK","CALIBRATION","ACCEPTANCE","DELIVERY").contains(phase)) throw EvaluationJson.bad("评测阶段无效");
        String mode=command.path("retrievalMode").asText("EXACT");
        if(!Set.of("EXACT","PRODUCTION").contains(mode)) throw EvaluationJson.bad("检索模式无效");
        if(phase.equals("DELIVERY") && !mode.equals("PRODUCTION")) throw EvaluationJson.bad("交付复测请选择生产检索模式");
        if(phase.equals("CALIBRATION") && !mode.equals("EXACT")) throw EvaluationJson.bad("阈值校准使用精确检索基线");
        int topK=command.path("topK").asInt(5);
        if(topK<1||topK>20) throw EvaluationJson.bad("Top K 必须为 1～20");
        ObjectNode run=json.createObjectNode().put("phase",phase).put("retrievalMode",mode).put("topK",topK)
                .put("status","QUEUED").put("verdict","INSUFFICIENT");
        run.putArray("verdictReasons").add("评测尚未完成");
        ObjectNode snapshot;
        ArrayNode questions;
        ObjectNode calibration = null;
        if(phase.equals("QUICK")) {
            snapshot=corpus.snapshot(tenant,knowledge,EvaluationJson.required(command,"snapshotId",64));
            Set<String> chunkIds=new HashSet<>(); snapshot.path("chunks").forEach(c->chunkIds.add(c.path("id").asText()));
            questions=EvaluationCorpusService.validateQuestions(command.path("questions"),chunkIds,json);
            if(questions.size()>20) throw EvaluationJson.bad("快速对比最多 20 条问题，批量评测请使用问题集");
        } else {
            ObjectNode dataset=corpus.dataset(tenant,knowledge,EvaluationJson.required(command,"datasetId",64),
                    command.path("datasetRevision").isIntegralNumber()?command.path("datasetRevision").asInt():null);
            snapshot=corpus.snapshot(tenant,knowledge,dataset.path("snapshotId").asText());
            run.put("datasetId",dataset.path("id").asText()).put("datasetRevision",dataset.path("revision").asInt())
                    .put("datasetFrozen",dataset.path("frozen").asBoolean()).put("datasetName",dataset.path("name").asText())
                    .put("datasetHash",dataset.path("hash").asText());
            String split=phase.equals("CALIBRATION")?"CALIBRATION":"ACCEPTANCE";
            questions=json.createArrayNode();
            ArrayNode calibrationQuestions=json.createArrayNode();
            for(JsonNode question:dataset.path("questions")) {
                if(question.path("split").asText().equals(split)) questions.add(question.deepCopy());
                if(question.path("split").asText().equals("CALIBRATION")) calibrationQuestions.add(question);
            }
            if(questions.isEmpty()) throw EvaluationJson.bad("问题集中没有 " + (split.equals("CALIBRATION")?"校准":"验收") + " 问题");
            run.put("calibrationHash",EvaluationJson.hash(snapshot.path("hash").asText()+calibrationQuestions));
            if(command.hasNonNull("calibrationRunId")) {
                calibration=get(tenant,knowledge,command.path("calibrationRunId").asText());
                if(!calibration.path("phase").asText().equals("CALIBRATION")) throw EvaluationJson.bad("请选择阈值校准运行");
                run.put("calibrationRunId",calibration.path("id").asText());
            }
        }
        run.put("snapshotId",snapshot.path("id").asText()).put("snapshotHash",snapshot.path("hash").asText())
                .put("scope",snapshot.path("scope").asText()).put("chunkCount",snapshot.path("chunks").size());
        run.set("questions",questions);
        JsonNode ids=command.path("modelIds");
        if(!ids.isArray()||ids.isEmpty()||ids.size()>3) throw EvaluationJson.bad("请选择 1～3 个向量模型");
        Set<String> selected=new LinkedHashSet<>();
        ArrayNode configs=run.putArray("models");
        ArrayNode results=run.putArray("modelResults");
        for(JsonNode id:ids) {
            if(!id.isTextual()||!selected.add(id.asText())) throw EvaluationJson.bad("模型选择不能重复");
            ObjectNode config=models.get(tenant,id.asText());
            configs.add(config);
            ObjectNode result=results.addObject().put("modelId",id.asText()).put("status","PENDING")
                    .put("preparedChunks",0).put("totalChunks",snapshot.path("chunks").size());
            result.putArray("queries"); result.putObject("metrics"); result.putArray("calibration");
        }
        String baseline=command.path("baselineModelId").asText(selected.iterator().next());
        if(!selected.contains(baseline)) throw EvaluationJson.bad("基线必须是本次参与评测的模型");
        run.put("baselineModelId",baseline);
        ObjectNode thresholds=run.putObject("thresholds");
        if(command.has("thresholds")&&!command.path("thresholds").isObject()) throw EvaluationJson.bad("阈值配置格式无效");
        command.path("thresholds").fields().forEachRemaining(e->{
            if(!selected.contains(e.getKey())||!e.getValue().isNumber()||!Double.isFinite(e.getValue().asDouble())||Math.abs(e.getValue().asDouble())>1)
                throw EvaluationJson.bad("阈值必须对应本次模型，且在 -1～1 之间");
            thresholds.set(e.getKey(),e.getValue());
        });
        if (calibration != null && !phase.equals("CALIBRATION")) {
            ObjectNode calibrationRun = calibration;
            thresholds.fields().forEachRemaining(entry -> {
                JsonNode calibrated = EvaluationRunPolicy.find(calibrationRun.path("modelResults"), "modelId", entry.getKey());
                boolean supported = calibrated != null;
                if (supported) {
                    supported = false;
                    for (JsonNode point : calibrated.path("calibration")) {
                        if (point.path("threshold").isNumber()
                                && Math.abs(point.path("threshold").asDouble() - entry.getValue().asDouble()) <= 1e-6) {
                            supported = true;
                            break;
                        }
                    }
                }
                if (!supported) throw EvaluationJson.bad("阈值必须来自所引用校准运行的校准曲线");
            });
        }
        ObjectNode requirements=command.path("requirements").isObject()?((ObjectNode)command.get("requirements")).deepCopy():json.createObjectNode();
        validateRequirements(requirements);
        run.set("requirements",requirements);
        run.putObject("modelFingerprints");
        run.putObject("progress").put("completed",0).put("total",configs.size()*(questions.size()+snapshot.path("chunks").size())).put("message","排队等待评测");
        run.putObject("retrievalSettings").put("distance","COSINE").put("indexType",mode.equals("EXACT")?"NONE":"HNSW")
                .put("topK",topK).put("candidateLimit",Math.min(topK*3,100))
                .put("thresholdComparison",">").put("scoringHorizon",mode.equals("EXACT")?Math.max(10,topK):topK)
                .put("pipelineNote",mode.equals("PRODUCTION")?"按实际 Top K 和三倍候选预算检索；补充分数仅供诊断，不计入返回排名":"无阈值的全范围精确排序");
        run.putObject("environment").put("os",System.getProperty("os.name")).put("architecture",System.getProperty("os.arch"))
                .put("processors",Runtime.getRuntime().availableProcessors()).put("java",System.getProperty("java.version"))
                .put("springAi","1.0.0-M6").put("timing","预热后问题编码与检索；模型探测、建库和精确参照检索分别计时");
        return dispatch(tenant,knowledge,run);
    }

    private static void validateRequirements(ObjectNode requirements) {
        for(String field:List.of("minQuestions","minUnanswerable","minHardNegativeQuestions","minVariantGroups")) if(requirements.has(field)) {
            JsonNode value=requirements.get(field);
            if(!value.isIntegralNumber()||value.asInt()<1||value.asInt()>1000) throw EvaluationJson.bad(field+" 必须为 1～1000");
        }
        for(String field:List.of("hit5Min","evidenceRetentionMin","noAnswerFalsePositiveMax","hardNegativeWinMin","variantGroupHitMin")) if(requirements.has(field)) {
            JsonNode value=requirements.get(field);
            if(!value.isNumber()||!Double.isFinite(value.asDouble())||value.asDouble()<0||value.asDouble()>1) throw EvaluationJson.bad(field+" 必须为 0～1");
        }
        if(requirements.has("p95MaxMs")&&(!requirements.get("p95MaxMs").isNumber()||!Double.isFinite(requirements.path("p95MaxMs").asDouble())||requirements.path("p95MaxMs").asDouble()<=0)) throw EvaluationJson.bad("p95 延迟上限必须大于 0");
    }

    private ObjectNode dispatch(long tenant,long knowledge,ObjectNode run) {
        AuthContext authorization=AuthContext.current();
        if(authorization==null||authorization.getTenantId()==null||authorization.getTenantId()!=tenant) throw EvaluationRepository.missing();
        run.put("ownerInstance",instance);
        ObjectNode saved=repository.createRun(tenant,knowledge,run);
        String id=saved.path("id").asText();
        try {
            FutureTask<Void> task=new FutureTask<>(()-> { try { worker.execute(authorization,saved.deepCopy()); }
                finally { tasks.remove(id); } return null; });
            tasks.put(id,task); executor.execute(task);
        } catch(RejectedExecutionException ex) {
            tasks.remove(id);
            saved.put("status","FAILED").put("error","评测队列已满，请稍后重试").put("finishedAt",Instant.now().toString());
            repository.updateRun(tenant,knowledge,id,saved.path("revision").asInt(),saved);
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,"评测队列已满，请稍后重试");
        }
        return saved;
    }

    public List<ObjectNode> list(long tenant,long knowledge) {
        corpus.authorize(tenant,knowledge);
        return repository.listRuns(tenant,knowledge);
    }

    public ObjectNode get(long tenant,long knowledge,String id) {
        corpus.authorize(tenant,knowledge);
        ObjectNode run=repository.getRun(tenant,knowledge,id);
        corpus.snapshot(tenant,knowledge,run.path("snapshotId").asText());
        return run;
    }

    public ObjectNode export(long tenant,long knowledge,String id) {
        ObjectNode run=get(tenant,knowledge,id);
        run.set("snapshot",corpus.snapshot(tenant,knowledge,run.path("snapshotId").asText()));
        return run;
    }

    public ObjectNode cancel(long tenant,long knowledge,String id) {
        for(int attempt=0;attempt<5;attempt++) {
            ObjectNode run=get(tenant,knowledge,id);
            if(!Set.of("QUEUED","RUNNING").contains(run.path("status").asText())) return run;
            run.put("status","CANCELLED").put("verdict","INSUFFICIENT").put("finishedAt",Instant.now().toString());
            run.putArray("verdictReasons").add("用户取消了评测");
            try {
                ObjectNode saved=repository.updateRun(tenant,knowledge,id,run.path("revision").asInt(),run);
                Future<?> task=tasks.remove(id);
                if(task!=null) {
                    task.cancel(true);
                    if(task instanceof Runnable queued) executor.remove(queued);
                }
                return saved;
            } catch(ResponseStatusException ex) { if(ex.getStatusCode().value()!=409) throw ex; }
        }
        throw EvaluationRepository.conflict();
    }

    public ObjectNode retry(long tenant,long knowledge,String id) {
        ObjectNode previous=get(tenant,knowledge,id);
        if(Set.of("RUNNING","QUEUED").contains(previous.path("status").asText())) throw EvaluationJson.bad("当前评测仍在运行");
        ObjectNode run=previous.deepCopy();
        run.remove(List.of("id","revision","createdAt","finishedAt","error","modelFingerprints"));
        run.put("retryOf",id).put("status","QUEUED").put("verdict","INSUFFICIENT");
        run.putArray("verdictReasons").add("正在重试相同输入版本");
        ArrayNode results=run.putArray("modelResults");
        for(JsonNode model:run.path("models")) {
            ObjectNode result=results.addObject().put("modelId",model.path("id").asText()).put("status","PENDING").put("preparedChunks",0).put("totalChunks",run.path("chunkCount").asInt());
            result.putArray("queries"); result.putObject("metrics"); result.putArray("calibration");
        }
        run.putObject("modelFingerprints");
        run.with("progress").put("completed",0).put("message","排队重试相同快照");
        return dispatch(tenant,knowledge,run);
    }
}
