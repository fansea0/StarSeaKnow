package com.starsea.ai.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.starsea.ai.auth.AuthContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class EvaluationExecutionLifecycleTest {
    private static final long TENANT=7;
    private static final long KNOWLEDGE=11;
    private static final String CHUNK="00000000-0000-0000-0000-000000000001";
    private final ObjectMapper json=new ObjectMapper();

    @AfterEach void clearAuthorization() { AuthContext.clear(); }

    @Test void marksFailedBuildWhileItsLeaseIsStillHeld() throws Exception {
        WorkerFixture fixture=workerFixture();
        AtomicBoolean leaseHeld=new AtomicBoolean();
        when(fixture.vectors.acquireBuildLease(anyString())).thenAnswer(invocation -> {
            leaseHeld.set(true);
            return (AutoCloseable)()->leaseHeld.set(false);
        });
        when(fixture.vectors.ready(eq(TENANT),anyString(),eq(1),eq(2))).thenReturn(false);
        when(fixture.gateway.embed(any(ObjectNode.class),anyList(),eq(false)))
                .thenThrow(new IllegalArgumentException("controlled build failure"));
        doAnswer(invocation -> {
            assertTrue(leaseHeld.get(),"failed build was mutated after its lease was released");
            return null;
        }).when(fixture.vectors).fail(eq(TENANT),anyString());

        fixture.worker.execute(authorization(),fixture.run.deepCopy());

        assertFalse(leaseHeld.get());
        verify(fixture.vectors).fail(eq(TENANT),anyString());
        assertEquals("FAILED",fixture.stored.get().path("status").asText());
    }

    @Test void doesNotFailCompletedBuildWhenLaterIdentityCheckFails() throws Exception {
        WorkerFixture fixture=workerFixture();
        AtomicBoolean leaseHeld=new AtomicBoolean();
        when(fixture.vectors.acquireBuildLease(anyString())).thenAnswer(invocation -> {
            leaseHeld.set(true);
            return (AutoCloseable)()->leaseHeld.set(false);
        });
        when(fixture.vectors.ready(eq(TENANT),anyString(),eq(1),eq(2))).thenReturn(false);
        AtomicInteger inspections=new AtomicInteger();
        when(fixture.gateway.inspect(any(ObjectNode.class))).thenAnswer(invocation -> {
            if(inspections.incrementAndGet()==3) throw new IllegalStateException("controlled post-build identity failure");
            return ((ObjectNode)invocation.getArgument(0)).deepCopy();
        });
        when(fixture.gateway.embed(any(ObjectNode.class),anyList(),anyBoolean()))
                .thenReturn(List.of(new double[]{1,0}));
        when(fixture.vectors.exact(eq(TENANT),anyString(),any(double[].class)))
                .thenReturn(List.of(new EvaluationVectorStore.Score(CHUNK,1)));
        doAnswer(invocation -> {
            assertTrue(leaseHeld.get(),"build completed outside its lease");
            return null;
        }).when(fixture.vectors).complete(eq(TENANT),anyString());

        fixture.worker.execute(authorization(),fixture.run.deepCopy());

        assertFalse(leaseHeld.get());
        verify(fixture.vectors).complete(eq(TENANT),anyString());
        verify(fixture.vectors,never()).fail(anyLong(),anyString());
        assertEquals("FAILED",fixture.stored.get().path("status").asText());
    }

    @Test void productionRunPersistsEfSearchAndReproducibleHitFields() throws Exception {
        WorkerFixture fixture=workerFixture();
        fixture.run.put("retrievalMode","PRODUCTION");
        fixture.stored.set(fixture.run.deepCopy());
        when(fixture.vectors.acquireBuildLease(anyString())).thenReturn(()->{});
        when(fixture.vectors.ready(eq(TENANT),anyString(),eq(1),eq(2))).thenReturn(false);
        when(fixture.gateway.embed(any(ObjectNode.class),anyList(),anyBoolean()))
                .thenReturn(List.of(new double[]{1,0}));
        when(fixture.vectors.exact(eq(TENANT),anyString(),any(double[].class)))
                .thenReturn(List.of(new EvaluationVectorStore.Score(CHUNK,.25)));
        when(fixture.vectors.production(eq(TENANT),eq(KNOWLEDGE),anyString(),any(double[].class),eq(1),
                org.mockito.ArgumentMatchers.<Double>isNull(),eq(40)))
                .thenReturn(List.of(new EvaluationVectorStore.Score(CHUNK,.25)));

        fixture.worker.execute(authorization(),fixture.run.deepCopy());

        ObjectNode saved=fixture.stored.get();
        assertEquals(40,saved.path("retrievalSettings").path("efSearch").asInt());
        ObjectNode hit=(ObjectNode)saved.path("modelResults").get(0).path("queries").get(0).path("hits").get(0);
        assertEquals(.75,hit.path("distance").asDouble(),1e-12);
        assertEquals("d".repeat(64),hit.path("contentHash").asText());
    }

    @Test void cancellingQueuedRunImmediatelyFreesBoundedQueueSlot() throws Exception {
        EvaluationRepository repository=mock(EvaluationRepository.class);
        EvaluationCorpusService corpus=mock(EvaluationCorpusService.class);
        EmbeddingModelRegistry models=mock(EmbeddingModelRegistry.class);
        EvaluationWorker worker=mock(EvaluationWorker.class);
        ObjectNode snapshot=snapshot();
        when(corpus.snapshot(eq(TENANT),eq(KNOWLEDGE),anyString())).thenReturn(snapshot);
        when(models.get(eq(TENANT),eq("current"))).thenReturn(model());
        Map<String,ObjectNode> runs=new ConcurrentHashMap<>();
        when(repository.createRun(eq(TENANT),eq(KNOWLEDGE),any(ObjectNode.class))).thenAnswer(invocation -> {
            ObjectNode saved=((ObjectNode)invocation.getArgument(2)).deepCopy()
                    .put("id",UUID.randomUUID().toString()).put("revision",1).put("knowledgeId",KNOWLEDGE);
            runs.put(saved.path("id").asText(),saved.deepCopy());
            return saved;
        });
        when(repository.getRun(eq(TENANT),eq(KNOWLEDGE),anyString())).thenAnswer(invocation ->
                runs.get(invocation.getArgument(2,String.class)).deepCopy());
        when(repository.updateRun(eq(TENANT),eq(KNOWLEDGE),anyString(),anyInt(),any(ObjectNode.class))).thenAnswer(invocation -> {
            String id=invocation.getArgument(2,String.class);
            int revision=invocation.getArgument(3,Integer.class);
            ObjectNode saved=((ObjectNode)invocation.getArgument(4)).deepCopy().put("id",id).put("revision",revision+1);
            runs.put(id,saved.deepCopy());
            return saved;
        });
        CountDownLatch firstStarted=new CountDownLatch(1);
        CountDownLatch releaseFirst=new CountDownLatch(1);
        doAnswer(invocation -> {
            firstStarted.countDown();
            assertTrue(releaseFirst.await(5,TimeUnit.SECONDS),"controlled worker was not released");
            return null;
        }).doNothing().when(worker).execute(any(AuthContext.class),any(ObjectNode.class));
        EvaluationService service=new EvaluationService(repository,corpus,models,worker,json,1);
        AuthContext.set(authorization());
        ObjectNode replacement=null;
        try {
            service.start(TENANT,KNOWLEDGE,quickCommand("first"));
            assertTrue(firstStarted.await(5,TimeUnit.SECONDS),"first worker did not start");
            ObjectNode queued=service.start(TENANT,KNOWLEDGE,quickCommand("queued"));
            service.cancel(TENANT,KNOWLEDGE,queued.path("id").asText());

            replacement=assertDoesNotThrow(()->service.start(TENANT,KNOWLEDGE,quickCommand("replacement")));
            assertEquals("QUEUED",replacement.path("status").asText());
        } finally {
            if(replacement!=null) service.cancel(TENANT,KNOWLEDGE,replacement.path("id").asText());
            releaseFirst.countDown();
            service.shutdown();
        }
    }

    private WorkerFixture workerFixture() throws Exception {
        EvaluationRepository repository=mock(EvaluationRepository.class);
        EvaluationCorpusService corpus=mock(EvaluationCorpusService.class);
        EvaluationVectorStore vectors=mock(EvaluationVectorStore.class);
        OllamaEmbeddingGateway gateway=mock(OllamaEmbeddingGateway.class);
        JdbcTemplate jdbc=mock(JdbcTemplate.class);
        ObjectNode snapshot=snapshot();
        ObjectNode run=run(snapshot);
        AtomicReference<ObjectNode> stored=new AtomicReference<>(run.deepCopy());
        when(repository.getRun(eq(TENANT),eq(KNOWLEDGE),eq(run.path("id").asText())))
                .thenAnswer(invocation -> stored.get().deepCopy());
        when(repository.updateRun(eq(TENANT),eq(KNOWLEDGE),eq(run.path("id").asText()),anyInt(),any(ObjectNode.class)))
                .thenAnswer(invocation -> {
                    int expected=invocation.getArgument(3,Integer.class);
                    assertEquals(stored.get().path("revision").asInt(),expected);
                    ObjectNode saved=((ObjectNode)invocation.getArgument(4)).deepCopy().put("revision",expected+1);
                    stored.set(saved.deepCopy());
                    return saved;
                });
        when(corpus.snapshot(TENANT,KNOWLEDGE,snapshot.path("id").asText())).thenReturn(snapshot.deepCopy());
        when(jdbc.queryForObject(anyString(),eq(Integer.class),any(Object[].class))).thenReturn(1);
        when(gateway.inspect(any(ObjectNode.class))).thenAnswer(invocation ->
                ((ObjectNode)invocation.getArgument(0)).deepCopy());
        when(gateway.selfSimilarity(any(ObjectNode.class),anyString())).thenReturn(1.0);
        EvaluationWorker worker=new EvaluationWorker(repository,corpus,vectors,gateway,json,jdbc,40);
        return new WorkerFixture(worker,repository,vectors,gateway,run,stored);
    }

    private ObjectNode run(ObjectNode snapshot) {
        ObjectNode run=json.createObjectNode().put("id",UUID.randomUUID().toString()).put("revision",1)
                .put("knowledgeId",KNOWLEDGE).put("snapshotId",snapshot.path("id").asText())
                .put("snapshotHash",snapshot.path("hash").asText()).put("scope","ALL")
                .put("phase","QUICK").put("retrievalMode","EXACT").put("topK",1)
                .put("status","QUEUED").put("verdict","INSUFFICIENT").put("baselineModelId","current")
                .put("chunkCount",1);
        run.putArray("models").add(model());
        ObjectNode result=run.putArray("modelResults").addObject().put("modelId","current").put("status","PENDING")
                .put("preparedChunks",0).put("totalChunks",1);
        result.putArray("queries"); result.putObject("metrics"); result.putArray("calibration");
        ObjectNode question=run.putArray("questions").addObject().put("id","question").put("query","query")
                .put("intentGroup","question").put("category","test").put("split","CALIBRATION")
                .put("answerable",true).put("reviewed",true);
        question.putObject("labels").put(CHUNK,2); question.putArray("hardNegativeIds");
        run.putObject("thresholds"); run.putObject("requirements"); run.putObject("modelFingerprints");
        run.putObject("progress").put("completed",0).put("total",2).put("message","queued");
        return run;
    }

    private ObjectNode snapshot() {
        ObjectNode snapshot=json.createObjectNode().put("id",UUID.randomUUID().toString())
                .put("hash","c".repeat(64)).put("scope","ALL");
        snapshot.putArray("chunks").addObject().put("id",CHUNK).put("fileId",19)
                .put("fileName","fixture.txt").put("content","document").put("indexContent","document")
                .put("contentHash","d".repeat(64)).put("sectionPath","");
        return snapshot;
    }

    private ObjectNode model() {
        ObjectNode model=json.createObjectNode().put("id","current").put("displayName","fixture")
                .put("baseUrl","http://127.0.0.1:1").put("modelName","fixture:fixed")
                .put("digest","sha256:"+"e".repeat(64)).put("dimensions",2).put("quantization","F32")
                .put("ollamaVersion","fixture-1").put("queryPrefix","").put("documentPrefix","");
        model.putObject("options");
        return model;
    }

    private ObjectNode quickCommand(String id) {
        ObjectNode command=json.createObjectNode().put("phase","QUICK").put("retrievalMode","EXACT")
                .put("topK",1).put("snapshotId",UUID.randomUUID().toString());
        command.putArray("modelIds").add("current");
        ObjectNode question=command.putArray("questions").addObject().put("id",id).put("query",id)
                .put("intentGroup",id).put("split","CALIBRATION").put("answerable",true).put("reviewed",true);
        question.putObject("labels").put(CHUNK,2); question.putArray("hardNegativeIds");
        return command;
    }

    private static AuthContext authorization() {
        return new AuthContext(AuthContext.Kind.BUSINESS,3,TENANT,"tenant_admin","fixture");
    }

    private record WorkerFixture(EvaluationWorker worker,EvaluationRepository repository,
                                 EvaluationVectorStore vectors,OllamaEmbeddingGateway gateway,
                                 ObjectNode run,AtomicReference<ObjectNode> stored) { }
}
