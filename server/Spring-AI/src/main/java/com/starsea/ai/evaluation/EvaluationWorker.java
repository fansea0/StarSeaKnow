package com.starsea.ai.evaluation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.starsea.ai.auth.AuthContext;
import com.starsea.ai.evaluation.metrics.EvaluationMetrics;
import com.starsea.ai.evaluation.metrics.EvaluationMetrics.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.*;

@Component
public class EvaluationWorker {
    private static final Logger log = LoggerFactory.getLogger(EvaluationWorker.class);
    private final EvaluationRepository repository;
    private final EvaluationCorpusService corpus;
    private final EvaluationVectorStore vectors;
    private final OllamaEmbeddingGateway gateway;
    private final ObjectMapper json;
    private final JdbcTemplate jdbc;
    private final int efSearch;

    public EvaluationWorker(EvaluationRepository repository, EvaluationCorpusService corpus,
            EvaluationVectorStore vectors, OllamaEmbeddingGateway gateway, ObjectMapper json, JdbcTemplate jdbc,
            @Value("${embedding-evaluation.production-ef-search:40}") int efSearch) {
        this.repository=repository; this.corpus=corpus; this.vectors=vectors; this.gateway=gateway;
        this.json=json; this.jdbc=jdbc; this.efSearch=efSearch;
    }

    public void execute(AuthContext authorization, ObjectNode run) {
        long tenant = authorization.getTenantId();
        long knowledge = run.path("knowledgeId").asLong();
        AuthContext.set(authorization);
        try {
            assertAuthorized(authorization, knowledge);
            checkpoint(tenant, knowledge, run);
            run.put("status", "RUNNING");
            boolean production = "PRODUCTION".equals(run.path("retrievalMode").asText());
            if (production) run.with("retrievalSettings").put("efSearch", efSearch);
            ObjectNode snapshot = corpus.snapshot(tenant, knowledge, run.path("snapshotId").asText());
            if(production) corpus.assertProductionSnapshotCurrent(tenant,knowledge,snapshot);
            List<JsonNode> chunks = list(snapshot.path("chunks"));
            Map<String,JsonNode> chunkMap = new LinkedHashMap<>();
            chunks.forEach(c -> chunkMap.put(c.path("id").asText(), c));
            int total = (chunks.size() + run.path("questions").size()) * run.path("models").size();
            run.putObject("progress").put("completed", 0).put("total", total).put("message", "准备模型和语料");
            persist(tenant, knowledge, run);
            Map<String,List<QueryInput>> modelInputs = new LinkedHashMap<>();
            int completedSteps = 0;
            for (JsonNode raw : run.path("models")) {
                ObjectNode config = (ObjectNode)raw;
                String modelId = config.path("id").asText();
                ObjectNode result = (ObjectNode)EvaluationRunPolicy.find(run.path("modelResults"), "modelId", modelId);
                result.put("status", "RUNNING");
                String buildKey = null;
                List<QueryInput> inputs = new ArrayList<>();
                List<QueryInput> acceptedInputs = new ArrayList<>();
                Double threshold = threshold(run, modelId);
                try {
                    assertAuthorized(authorization, knowledge);
                    checkpoint(tenant, knowledge, run);
                    long verificationStart = System.nanoTime();
                    ObjectNode verified = gateway.inspect(config);
                    config.setAll(verified);
                    String fingerprint = fingerprint(config);
                    run.with("modelFingerprints").put(modelId, fingerprint);
                    result.put("dimensions", config.path("dimensions").asInt()).put("digest", config.path("digest").asText())
                            .put("verificationMs", elapsed(verificationStart));
                    result.put("selfSimilarity", gateway.selfSimilarity(config, "同一完整文本的向量一致性检查"));
                    if (result.path("selfSimilarity").asDouble() < .999) throw EvaluationJson.bad("同一输入两次编码的相似度异常，请检查模型运行环境");
                    buildKey = EvaluationJson.hash(tenant + ":" + snapshot.path("hash").asText() + ":" + fingerprint);
                    result.put("buildKey", buildKey).put("totalChunks", chunks.size());
                    long start = System.nanoTime();
                    try (AutoCloseable lease = vectors.acquireBuildLease(buildKey)) {
                        if (!vectors.ready(tenant, buildKey, chunks.size(), config.path("dimensions").asInt())) {
                            boolean buildAttemptOwned = false;
                            try {
                                vectors.begin(tenant, knowledge, buildKey, snapshot, config);
                                buildAttemptOwned = true;
                                for (int offset=0;offset<chunks.size();offset+=32) {
                                    checkpoint(tenant, knowledge, run);
                                    assertAuthorized(authorization, knowledge);
                                    List<JsonNode> batch = chunks.subList(offset, Math.min(offset+32,chunks.size()));
                                    List<double[]> embedded = gateway.embed(config, batch.stream().map(c -> c.path("indexContent").asText()).toList(), false);
                                    vectors.append(tenant, buildKey, batch, embedded, config.path("dimensions").asInt());
                                    result.put("preparedChunks", offset+batch.size());
                                    progress(run, completedSteps+offset+batch.size(), "正在准备 " + config.path("displayName").asText());
                                    persist(tenant, knowledge, run);
                                }
                                assertAuthorized(authorization, knowledge);
                                gateway.inspect(config);
                                vectors.complete(tenant, buildKey);
                                buildAttemptOwned = false;
                                result.put("cacheHit", false);
                            } catch (Exception failure) {
                                if (buildAttemptOwned) {
                                    try { vectors.fail(tenant, buildKey); }
                                    catch (Exception cleanup) { failure.addSuppressed(cleanup); }
                                }
                                throw failure;
                            }
                        } else result.put("cacheHit", true);
                    }
                    result.put("preparedChunks", chunks.size()).put("buildMs", elapsed(start));
                    completedSteps += chunks.size();
                    if (production) {
                        assertAuthorized(authorization, knowledge);
                        vectors.prepareProductionIndex(tenant, buildKey, config.path("dimensions").asInt());
                    }
                    for (JsonNode question : run.path("questions")) {
                        checkpoint(tenant, knowledge, run);
                        ObjectNode queryResult = result.withArray("queries").addObject().put("questionId", question.path("id").asText())
                                .put("query", question.path("query").asText()).put("status", "RUNNING");
                        QueryInput input;
                        try {
                            assertAuthorized(authorization, knowledge);
                            long queryStart=System.nanoTime();
                            double[] embedding=gateway.embed(config, List.of(question.path("query").asText()), true).get(0);
                            long embeddingMs=elapsed(queryStart);
                            long searchStart=System.nanoTime();
                            List<EvaluationVectorStore.Score> exact=vectors.exact(tenant, buildKey, embedding);
                            long exactMs=elapsed(searchStart);
                            List<EvaluationVectorStore.Score> ranking=exact;
                            List<EvaluationVectorStore.Score> accepted=exact;
                            long searchMs=exactMs;
                            if (production) {
                                searchStart=System.nanoTime();
                                ranking=vectors.production(tenant, knowledge, buildKey, embedding, run.path("topK").asInt(), null,efSearch);
                                long rawMs=elapsed(searchStart);
                                searchStart=System.nanoTime();
                                accepted=threshold==null?ranking:vectors.production(tenant, knowledge, buildKey, embedding, run.path("topK").asInt(), threshold,efSearch);
                                searchMs=threshold==null?rawMs:elapsed(searchStart);
                                queryResult.put("exactReferenceSearchMs",exactMs);
                                queryResult.put("unfilteredReferenceSearchMs",rawMs);
                            }
                            input=input(question,ranking,null,embeddingMs,searchMs,exact);
                            queryResult.put("status","COMPLETED").put("embeddingMs",embeddingMs).put("searchMs",searchMs);
                            queryResult.set("metrics",json.valueToTree(EvaluationMetrics.analyze(input,run.path("topK").asInt(),threshold)));
                            if (production) {
                                queryResult.set("exactReferenceMetrics",json.valueToTree(EvaluationMetrics.analyze(input(question,exact,null,embeddingMs,exactMs),run.path("topK").asInt(),threshold)));
                                QueryInput acceptedInput=input(question,accepted,null,embeddingMs,searchMs,exact);
                                acceptedInputs.add(acceptedInput);
                                JsonNode actual=json.valueToTree(EvaluationMetrics.analyze(acceptedInput,run.path("topK").asInt(),null));
                                ObjectNode caseMetrics=(ObjectNode)queryResult.get("metrics");
                                for(String field:List.of("noAnswerFalsePositive","evidenceRetention","answerableEmpty","returnedCount")) caseMetrics.set(field,actual.path(field));
                                caseMetrics.put("unknownScoringCount",Math.max(caseMetrics.path("unknownScoringCount").asInt(),actual.path("unknownScoringCount").asInt()));
                                ArrayNode acceptedHits=queryResult.putArray("acceptedHits");
                                for(int i=0;i<accepted.size();i++) acceptedHits.add(hit(accepted.get(i),i+1,chunkMap,question));
                            }
                            ArrayNode hits=queryResult.putArray("hits");
                            for (int i=0;i<Math.min(ranking.size(),Math.max(10,run.path("topK").asInt()));i++) hits.add(hit(ranking.get(i),i+1,chunkMap,question));
                            ArrayNode judged=queryResult.putArray("judgedScores");
                            for (int i=0;i<exact.size();i++) if(question.path("labels").has(exact.get(i).chunkId())) judged.add(hit(exact.get(i),i+1,chunkMap,question));
                        } catch (Cancelled ignored) { throw ignored; }
                        catch (Exception ex) {
                            String error=safeError(ex);
                            queryResult.put("status","FAILED").put("error",error);
                            input=input(question,List.of(),error,0,0);
                            if(production) acceptedInputs.add(input);
                            log.warn("event=embedding_evaluation_query_failed run={} model={} type={}",run.path("id").asText(),modelId,ex.getClass().getSimpleName());
                        }
                        inputs.add(input);
                        completedSteps++;
                        progress(run,completedSteps,"已完成问题 " + inputs.size() + " / " + run.path("questions").size());
                        persist(tenant,knowledge,run);
                    }
                    assertAuthorized(authorization, knowledge);
                    ObjectNode finalIdentity=gateway.inspect(config);
                    if(!fingerprint(finalIdentity).equals(fingerprint)) throw EvaluationJson.bad("模型身份或运行配置在评测期间发生变化");
                    result.put("identityStable",true);
                    result.put("status",inputs.stream().anyMatch(q -> q.error()!=null) ? "FAILED" : "COMPLETED");
                    if(!result.path("status").asText().equals("COMPLETED")) result.put("error","部分问题编码或检索失败，请检查逐题结果");
                } catch(Cancelled ignored) { throw ignored; }
                catch(Exception ex) {
                    result.put("status","FAILED").put("error",safeError(ex)).put("identityStable",false);
                    Set<String> done=new HashSet<>(); inputs.forEach(q -> done.add(q.questionId()));
                    for(JsonNode question:run.path("questions")) if(!done.contains(question.path("id").asText())) {
                        inputs.add(input(question,List.of(),safeError(ex),0,0));
                        result.withArray("queries").addObject().put("questionId",question.path("id").asText())
                                .put("query",question.path("query").asText()).put("status","FAILED").put("error",safeError(ex));
                    }
                    log.error("event=embedding_evaluation_model_failed run={} model={}",run.path("id").asText(),modelId,ex);
                }
                modelInputs.put(modelId,inputs);
                result.set("metrics",json.valueToTree(EvaluationMetrics.calculate(inputs,run.path("topK").asInt(),threshold)));
                if("PRODUCTION".equals(run.path("retrievalMode").asText()) && acceptedInputs.size()==inputs.size()) {
                    JsonNode actual=json.valueToTree(EvaluationMetrics.calculate(acceptedInputs,run.path("topK").asInt(),null));
                    ObjectNode summary=(ObjectNode)result.get("metrics");
                    for(String field:List.of("noAnswerFalsePositiveRate","evidenceRetentionRate","answerableEmptyRate")) summary.set(field,actual.path(field));
                    summary.put("unknownScoringCount",Math.max(summary.path("unknownScoringCount").asInt(),actual.path("unknownScoringCount").asInt()));
                }
                result.set("calibration",json.valueToTree(EvaluationMetrics.calibrate(inputs,run.path("topK").asInt())));
                persist(tenant,knowledge,run);
            }
            List<QueryInput> baseline=modelInputs.get(run.path("baselineModelId").asText());
            if(baseline!=null) for(JsonNode result:run.path("modelResults")) {
                String id=result.path("modelId").asText();
                if(!id.equals(run.path("baselineModelId").asText())) ((ObjectNode)result).set("comparison",json.valueToTree(EvaluationMetrics.compare(baseline,modelInputs.get(id),run.path("topK").asInt())));
            }
            int successful=0;
            for(JsonNode result:run.path("modelResults")) if(result.path("status").asText().equals("COMPLETED")) successful++;
            run.put("status",successful==run.path("models").size()?"COMPLETED":successful==0?"FAILED":"PARTIAL");
            run.put("finishedAt",Instant.now().toString());
            progress(run,total,"评测结束");
            ObjectNode calibration=null;
            if(run.hasNonNull("calibrationRunId")) calibration=repository.getRun(tenant,knowledge,run.path("calibrationRunId").asText());
            EvaluationRunPolicy.apply(run,calibration);
            corpus.assertReadable(tenant,knowledge,snapshot);
            if(run.path("retrievalMode").asText().equals("PRODUCTION")) corpus.assertProductionSnapshotCurrent(tenant,knowledge,snapshot);
            persist(tenant,knowledge,run);
        } catch(Cancelled ignored) {
            // Cancellation already owns the persisted terminal state; do not overwrite it.
        } catch(Exception ex) {
            log.error("event=embedding_evaluation_run_failed run={}",run.path("id").asText(),ex);
            try {
                run.put("status","FAILED").put("error",safeError(ex)).put("finishedAt",Instant.now().toString()).put("verdict","INSUFFICIENT");
                persist(tenant,knowledge,run);
            } catch(Exception ignored) { /* Deletion/cancellation can remove permission to update the run. */ }
        } finally { AuthContext.clear(); }
    }

    private void assertAuthorized(AuthContext auth,long knowledge) {
        corpus.authorize(auth.getTenantId(),knowledge);
        Integer allowed=jdbc.queryForObject("SELECT count(*) FROM app_user u JOIN tenant t ON t.id=u.tenant_id WHERE u.id=? AND u.tenant_id=? AND u.role='tenant_admin' AND u.status=1 AND t.status=1",Integer.class,auth.getUserId(),auth.getTenantId());
        if(allowed==null||allowed!=1) throw EvaluationRepository.missing();
    }

    private void checkpoint(long tenant,long knowledge,ObjectNode run) {
        if(Thread.currentThread().isInterrupted()) throw new Cancelled();
        ObjectNode current=repository.getRun(tenant,knowledge,run.path("id").asText());
        if(current.path("status").asText().equals("CANCELLED")) throw new Cancelled();
        if(current.path("revision").asInt()!=run.path("revision").asInt()) throw EvaluationRepository.conflict();
    }

    private void persist(long tenant,long knowledge,ObjectNode run) {
        checkpoint(tenant,knowledge,run);
        ObjectNode saved=repository.updateRun(tenant,knowledge,run.path("id").asText(),run.path("revision").asInt(),run);
        run.put("revision",saved.path("revision").asInt());
    }

    private static void progress(ObjectNode run,int completed,String message) {
        run.with("progress").put("completed",completed).put("message",message);
    }

    static String fingerprint(ObjectNode model) {
        ObjectNode stable=model.objectNode();
        for(String field:List.of("baseUrl","modelName","digest","dimensions","quantization","ollamaVersion","queryPrefix","documentPrefix","options","keepAlive")) stable.set(field,model.path(field));
        return EvaluationJson.hash(stable.toString());
    }

    static Double threshold(JsonNode run,String modelId) {
        JsonNode value=run.path("thresholds").path(modelId);
        return value.isNumber()?value.asDouble():null;
    }

    static QueryInput input(JsonNode question,List<EvaluationVectorStore.Score> ranking,String error,long embeddingMs,long searchMs) {
        return input(question,ranking,error,embeddingMs,searchMs,List.of());
    }

    static QueryInput input(JsonNode question,List<EvaluationVectorStore.Score> ranking,String error,long embeddingMs,long searchMs,List<EvaluationVectorStore.Score> supplemental) {
        List<Judgment> judgments=new ArrayList<>();
        Set<String> hard=new HashSet<>(); question.path("hardNegativeIds").forEach(id->hard.add(id.asText()));
        question.path("labels").fields().forEachRemaining(e->judgments.add(new Judgment(e.getKey(),e.getValue().asInt(),hard.contains(e.getKey()))));
        return new QueryInput(question.path("id").asText(),question.path("intentGroup").asText(),question.path("category").asText(),question.path("split").asText(),
                question.path("answerable").asBoolean(),question.path("reviewed").asBoolean(),judgments,
                ranking.stream().map(s->new ScoredChunk(s.chunkId(),s.score())).toList(),error,embeddingMs,searchMs,
                supplemental.stream().filter(s->question.path("labels").has(s.chunkId())).map(s->new ScoredChunk(s.chunkId(),s.score())).toList());
    }

    private ObjectNode hit(EvaluationVectorStore.Score score,int rank,Map<String,JsonNode> chunks,JsonNode question) {
        JsonNode chunk=chunks.get(score.chunkId());
        ObjectNode hit=json.createObjectNode().put("chunkId",score.chunkId()).put("rank",rank)
                .put("score",score.score()).put("distance",1-score.score());
        for(String field:List.of("fileName","content","indexContent","contentHash","sectionPath")) hit.set(field,chunk.path(field));
        if(question.path("labels").has(score.chunkId())) hit.set("label",question.path("labels").get(score.chunkId()));
        boolean hard=false; for(JsonNode id:question.path("hardNegativeIds")) hard|=id.asText().equals(score.chunkId());
        return hit.put("hardNegative",hard);
    }

    static String safeError(Exception ex) {
        if(ex instanceof ResponseStatusException status && status.getReason()!=null) return status.getReason();
        if(ex instanceof IllegalArgumentException) return "向量、输入或计分参数无效，请检查模型与数据配置";
        return "评测执行失败，请检查服务日志后重试";
    }

    private static long elapsed(long start) { return Math.max(0,(System.nanoTime()-start)/1_000_000); }
    private static List<JsonNode> list(JsonNode nodes) { List<JsonNode> values=new ArrayList<>(); nodes.forEach(values::add); return values; }
    private static final class Cancelled extends RuntimeException {}
}
