package com.starsea.ai.evaluation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Tenant-scoped immutable model/dataset/snapshot versions and CAS-controlled run progress. */
@Repository
public class EvaluationRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final TransactionTemplate transaction;

    public EvaluationRepository(JdbcTemplate jdbc, ObjectMapper json, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.json = json;
        this.transaction = new TransactionTemplate(manager);
    }

    public List<ObjectNode> list(String kind, long tenantId, Long knowledgeId) {
        return jdbc.query("""
                SELECT payload::text FROM (
                  SELECT DISTINCT ON (public_id) payload, created_at
                  FROM embedding_eval_entity
                  WHERE tenant_id=? AND kind=? AND knowledge_id IS NOT DISTINCT FROM ?::bigint
                  ORDER BY public_id, revision DESC
                ) versions WHERE COALESCE((payload->>'deleted')::boolean,false)=false
                ORDER BY created_at DESC
                """, (rs, row) -> parse(rs.getString(1)), tenantId, kind, knowledgeId);
    }

    public ObjectNode get(String kind, String id, long tenantId, Long knowledgeId, Integer revision) {
        String versionSql = revision == null ? " ORDER BY revision DESC LIMIT 1" : " AND revision=?";
        Object[] params = revision == null ? new Object[]{tenantId, kind, uuid(id), knowledgeId}
                : new Object[]{tenantId, kind, uuid(id), knowledgeId, revision};
        List<ObjectNode> values = jdbc.query("""
                SELECT payload::text FROM embedding_eval_entity
                WHERE tenant_id=? AND kind=? AND public_id=? AND knowledge_id IS NOT DISTINCT FROM ?::bigint
                """ + versionSql, (rs, row) -> parse(rs.getString(1)), params);
        if (values.isEmpty() || values.get(0).path("deleted").asBoolean()) throw missing();
        return values.get(0);
    }

    public ObjectNode save(String kind, String id, long tenantId, Long knowledgeId,
                           int expectedRevision, ObjectNode value) {
        UUID publicId = uuid(id);
        return transaction.execute(status -> {
            jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended(?,0))::text", String.class,
                    tenantId + ":" + kind + ":" + publicId);
            Integer current = jdbc.queryForObject("""
                    SELECT COALESCE(max(revision),0) FROM embedding_eval_entity
                    WHERE tenant_id=? AND kind=? AND public_id=?
                    """, Integer.class, tenantId, kind, publicId);
            if (current == null || current != expectedRevision) throw conflict();
            if (current > 0) get(kind, id, tenantId, knowledgeId, null);
            ObjectNode saved = value.deepCopy().put("id", id).put("revision", current + 1);
            if (!saved.hasNonNull("createdAt")) saved.put("createdAt", Instant.now().toString());
            saved.put("updatedAt", Instant.now().toString());
            if (knowledgeId != null) saved.put("knowledgeId", knowledgeId);
            jdbc.update("""
                    INSERT INTO embedding_eval_entity(tenant_id,kind,public_id,revision,knowledge_id,payload)
                    VALUES(?,?,?,?,?,?::jsonb)
                    """, tenantId, kind, publicId, current + 1, knowledgeId, saved.toString());
            return saved;
        });
    }

    public void delete(String kind, String id, long tenantId, Long knowledgeId, int expectedRevision) {
        ObjectNode value = get(kind, id, tenantId, knowledgeId, null);
        save(kind, id, tenantId, knowledgeId, expectedRevision, value.put("deleted", true));
    }

    public ObjectNode createRun(long tenantId, long knowledgeId, ObjectNode value) {
        String id = UUID.randomUUID().toString();
        ObjectNode run = value.deepCopy().put("id", id).put("revision", 1)
                .put("knowledgeId", knowledgeId).put("createdAt", Instant.now().toString());
        jdbc.update("INSERT INTO embedding_eval_run(public_id,tenant_id,knowledge_id,payload) VALUES(?,?,?,?::jsonb)",
                UUID.fromString(id), tenantId, knowledgeId, run.toString());
        return run;
    }

    public ObjectNode getRun(long tenantId, long knowledgeId, String id) {
        List<ObjectNode> rows = jdbc.query("SELECT payload::text FROM embedding_eval_run WHERE public_id=? AND tenant_id=? AND knowledge_id=?",
                (rs, row) -> parse(rs.getString(1)), uuid(id), tenantId, knowledgeId);
        if (rows.isEmpty()) throw missing();
        return rows.get(0);
    }

    public List<ObjectNode> listRuns(long tenantId, long knowledgeId) {
        return jdbc.query("SELECT (payload - 'snapshot' - 'models' - 'modelResults' - 'questions')::text FROM embedding_eval_run WHERE tenant_id=? AND knowledge_id=? ORDER BY created_at DESC LIMIT 100",
                (rs, row) -> parse(rs.getString(1)), tenantId, knowledgeId);
    }

    public ObjectNode updateRun(long tenantId, long knowledgeId, String id, int expectedRevision, ObjectNode value) {
        ObjectNode updated = value.deepCopy().put("id", id).put("revision", expectedRevision + 1);
        int changed = jdbc.update("""
                UPDATE embedding_eval_run SET payload=?::jsonb,revision=revision+1,updated_at=CURRENT_TIMESTAMP
                WHERE public_id=? AND tenant_id=? AND knowledge_id=? AND revision=?
                """, updated.toString(), uuid(id), tenantId, knowledgeId, expectedRevision);
        if (changed != 1) throw conflict();
        return updated;
    }

    public int recoverInterruptedRuns() {
        return jdbc.update("""
                UPDATE embedding_eval_run SET
                  payload=jsonb_set(jsonb_set(jsonb_set(payload,'{status}','"FAILED"'),'{error}',
                    '"服务重启中断了评测，请重试；原始输入已保留。"'),'{revision}',to_jsonb(revision+1)),
                  revision=revision+1,updated_at=CURRENT_TIMESTAMP
                WHERE payload->>'status' IN ('QUEUED','RUNNING') AND updated_at < CURRENT_TIMESTAMP - INTERVAL '5 minutes'
                """);
    }

    public void heartbeat(String instance) {
        jdbc.update("UPDATE embedding_eval_run SET updated_at=CURRENT_TIMESTAMP WHERE payload->>'ownerInstance'=? AND payload->>'status' IN ('QUEUED','RUNNING')",instance);
    }

    public void stopOwnedRuns(String instance) {
        jdbc.update("""
                UPDATE embedding_eval_run SET payload=jsonb_set(jsonb_set(jsonb_set(payload,'{status}','"FAILED"'),
                  '{error}','"服务停止中断了评测，可重试相同输入版本。"'),'{revision}',to_jsonb(revision+1)),revision=revision+1
                WHERE payload->>'ownerInstance'=? AND payload->>'status' IN ('QUEUED','RUNNING')
                """,instance);
    }

    private ObjectNode parse(String payload) {
        try { return (ObjectNode) json.readTree(payload); }
        catch (JsonProcessingException ex) { throw new IllegalStateException("Stored evaluation record is invalid", ex); }
    }

    public static UUID uuid(String value) {
        try { return UUID.fromString(value); }
        catch (IllegalArgumentException | NullPointerException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "评测资源 ID 无效");
        }
    }

    public static ResponseStatusException missing() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "评测资源不存在或无权访问");
    }

    public static ResponseStatusException conflict() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "配置或评测已更新，请重新加载后重试");
    }
}
