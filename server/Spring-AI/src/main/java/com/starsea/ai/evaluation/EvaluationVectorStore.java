package com.starsea.ai.evaluation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.sql.Connection;
import java.sql.SQLException;

/** Evaluation vectors never share a table or embedding client with the production VectorStore. */
@Repository
public class EvaluationVectorStore {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    public EvaluationVectorStore(JdbcTemplate jdbc, PlatformTransactionManager manager) {
        this.jdbc = jdbc; this.transactions = new TransactionTemplate(manager);
    }

    public AutoCloseable acquireBuildLease(String buildKey) {
        safeKey(buildKey);
        long lock=Long.parseUnsignedLong(buildKey.substring(0,16),16);
        Connection connection=null;
        try {
            connection=jdbc.getDataSource().getConnection();
            long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(120);
            while(true) {
                try(var statement=connection.prepareStatement("SELECT pg_try_advisory_lock(?)")) {
                    statement.setLong(1,lock);
                    try(var rows=statement.executeQuery()) {
                        rows.next(); if(rows.getBoolean(1)) break;
                    }
                }
                if(System.nanoTime()>deadline) throw EvaluationJson.bad("同一模型语料正在准备，请稍后重试");
                Thread.sleep(250);
            }
            Connection leased=connection;
            return ()->{
                try(var statement=leased.prepareStatement("SELECT pg_advisory_unlock(?)")) {
                    statement.setLong(1,lock); statement.execute();
                } finally { leased.close(); }
            };
        } catch(Exception ex) {
            if(connection!=null) try { connection.close(); } catch(SQLException ignored) { }
            if(ex instanceof InterruptedException) Thread.currentThread().interrupt();
            if(ex instanceof RuntimeException runtime) throw runtime;
            throw new IllegalStateException("Unable to acquire evaluation build lease",ex);
        }
    }

    public boolean ready(long tenantId, String buildKey, int count, int dimensions) {
        Integer found = jdbc.queryForObject("""
                SELECT count(*) FROM embedding_eval_build b
                WHERE b.tenant_id=? AND b.build_key=? AND b.status='READY'
                  AND b.expected_count=? AND b.dimensions=?
                  AND (SELECT count(*) FROM embedding_eval_vector v WHERE v.tenant_id=b.tenant_id AND v.build_key=b.build_key)=b.expected_count
                """, Integer.class, tenantId, buildKey, count, dimensions);
        return found != null && found == 1;
    }

    public void begin(long tenantId, long knowledgeId, String buildKey, ObjectNode snapshot, ObjectNode model) {
        transactions.executeWithoutResult(status -> {
            jdbc.update("DELETE FROM embedding_eval_build WHERE tenant_id=? AND build_key=?", tenantId, buildKey);
            jdbc.update("""
                    INSERT INTO embedding_eval_build(tenant_id,knowledge_id,build_key,snapshot_id,dimensions,expected_count,status,model)
                    VALUES(?,?,?,?,?,?,'BUILDING',?::jsonb)
                    """, tenantId, knowledgeId, buildKey, EvaluationRepository.uuid(snapshot.path("id").asText()),
                    model.path("dimensions").asInt(), snapshot.path("chunks").size(), model.toString());
        });
    }

    public void append(long tenantId, String buildKey, List<JsonNode> chunks, List<double[]> embeddings, int dimensions) {
        if (chunks.size() != embeddings.size()) throw new IllegalArgumentException("Vector response count differs from inputs");
        List<Object[]> batch = new ArrayList<>();
        for (int index = 0; index < chunks.size(); index++) {
            JsonNode chunk = chunks.get(index);
            double[] vector = embeddings.get(index);
            if (vector.length != dimensions) throw new IllegalArgumentException("Embedding dimensions changed during build");
            batch.add(new Object[]{tenantId, buildKey, EvaluationRepository.uuid(chunk.path("id").asText()),
                    chunk.path("fileId").asLong(), literal(vector), chunk.path("contentHash").asText()});
        }
        jdbc.batchUpdate("INSERT INTO embedding_eval_vector(tenant_id,build_key,chunk_id,file_id,embedding,content_hash) VALUES(?,?,?,?,?::vector,?)", batch);
    }

    public void complete(long tenantId, String buildKey) {
        int updated = jdbc.update("""
                UPDATE embedding_eval_build b SET status='READY' WHERE tenant_id=? AND build_key=?
                  AND (SELECT count(*) FROM embedding_eval_vector v WHERE v.tenant_id=b.tenant_id AND v.build_key=b.build_key)=b.expected_count
                  AND NOT EXISTS(SELECT 1 FROM embedding_eval_vector v WHERE v.tenant_id=b.tenant_id AND v.build_key=b.build_key AND vector_dims(v.embedding)<>b.dimensions)
                """, tenantId, buildKey);
        if (updated != 1) throw new IllegalStateException("向量集合不完整，不能参加评测");
    }

    public void fail(long tenantId, String buildKey) {
        jdbc.update("UPDATE embedding_eval_build SET status='FAILED' WHERE tenant_id=? AND build_key=? AND status='BUILDING'", tenantId, buildKey);
    }

    public List<Score> exact(long tenantId, String buildKey, double[] query) {
        return jdbc.query("""
                SELECT chunk_id::text, 1-(embedding <=> ?::vector) AS score
                FROM embedding_eval_vector WHERE tenant_id=? AND build_key=? ORDER BY score DESC,chunk_id
                """, (rs,row) -> new Score(rs.getString(1), rs.getDouble(2)), literal(query), tenantId, buildKey);
    }

    public void prepareProductionIndex(long tenantId, String buildKey, int dimensions) {
        safeKey(buildKey);
        if (dimensions < 1 || dimensions > 2000) throw EvaluationJson.bad("当前 HNSW 交付复测支持至多 2000 维，当前模型请使用精确评测或调整部署索引方案");
        String name = "eval_hnsw_" + EvaluationJson.hash(tenantId + ":" + buildKey).substring(0, 32);
        jdbc.execute("CREATE INDEX IF NOT EXISTS " + name + " ON embedding_eval_vector USING hnsw ((embedding::vector("
                + dimensions + ")) vector_cosine_ops) WHERE tenant_id=" + tenantId + " AND build_key='" + buildKey + "'");
        jdbc.execute("ANALYZE embedding_eval_vector");
    }

    public List<Score> production(long tenantId, long knowledgeId, String buildKey, double[] query,
                                   int topK, Double threshold, int efSearch) {
        safeKey(buildKey);
        if (topK < 1 || topK > 20 || efSearch < 1 || efSearch > 1000) throw EvaluationJson.bad("检索参数超出范围");
        int dimension = query.length;
        String expression = "embedding::vector(" + dimension + ") <=> ?::vector(" + dimension + ")";
        String scoreFilter = threshold == null ? "" : " AND " + expression + " < ?";
        List<Object> args = new ArrayList<>(List.of(literal(query), tenantId, buildKey));
        if (threshold != null) { args.add(literal(query)); args.add(1 - threshold); }
        args.add(tenantId); args.add(knowledgeId); args.add(Math.min(topK * 3, 100));
        return transactions.execute(status -> {
            jdbc.queryForObject("SELECT set_config('hnsw.ef_search',?,true)", String.class, String.valueOf(efSearch));
            List<Score> candidates = jdbc.query("SELECT chunk_id::text, " + expression + " AS distance FROM embedding_eval_vector "
                    + "WHERE tenant_id=? AND build_key=?" + scoreFilter
                    + " AND file_id IN (SELECT f.id FROM file f JOIN knowledge_file kf ON kf.file_id=f.id AND kf.tenant_id=f.tenant_id WHERE f.status=1 AND f.tenant_id=? AND kf.knowledge_id=?)"
                    + " ORDER BY distance LIMIT ?", (rs,row) -> new Score(rs.getString(1), 1-rs.getDouble(2)), args.toArray());
            List<Score> permitted = new ArrayList<>();
            for (Score candidate : candidates) {
                Integer active = jdbc.queryForObject("SELECT count(*) FROM document_chunk WHERE public_id=? AND tenant_id=? AND knowledge_id=? AND status=2",
                        Integer.class, UUID.fromString(candidate.chunkId()), tenantId, knowledgeId);
                if (active != null && active == 1) permitted.add(candidate);
            }
            permitted.sort(Comparator.comparingDouble(Score::score).reversed().thenComparing(Score::chunkId));
            return List.copyOf(permitted.subList(0,Math.min(topK,permitted.size())));
        });
    }

    private static void safeKey(String key) {
        if (key == null || !key.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("Invalid build fingerprint");
    }

    static String literal(double[] vector) {
        if (vector == null || vector.length == 0) throw new IllegalArgumentException("Empty embedding");
        double scale = 0;
        for (double value : vector) {
            if (!Double.isFinite(value)) throw new IllegalArgumentException("Embedding must contain finite values");
            scale = Math.max(scale, Math.abs(value));
        }
        if (scale == 0) throw new IllegalArgumentException("Zero embedding has no cosine similarity");
        double squares = 0;
        for (double value : vector) squares += (value / scale) * (value / scale);
        double norm = Math.sqrt(squares);
        StringBuilder result = new StringBuilder("[");
        boolean nonzero = false;
        for (int i=0;i<vector.length;i++) {
            float value = (float)((vector[i] / scale) / norm);
            if (!Double.isFinite(vector[i]) || !Float.isFinite(value)) throw new IllegalArgumentException("Embedding must contain finite float32 values");
            nonzero |= value != 0;
            if (i>0) result.append(',');
            result.append(value);
        }
        if (!nonzero) throw new IllegalArgumentException("Zero embedding has no cosine similarity");
        return result.append(']').toString();
    }

    public record Score(String chunkId,double score) {
        public Score {
            if(!Double.isFinite(score)) throw new IllegalArgumentException("PostgreSQL returned a nonfinite cosine score");
        }
    }
}
