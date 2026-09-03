package com.starsea.ai.evaluation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.starsea.ai.chunking.context.DefaultChunkContextEnricher;
import com.starsea.ai.domain.DocumentChunk;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class EvaluationCorpusService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final EvaluationRepository repository;
    private final DefaultChunkContextEnricher enricher;
    private final TransactionTemplate snapshotTransaction;
    private final int maxChunks;

    public EvaluationCorpusService(JdbcTemplate jdbc, ObjectMapper json, EvaluationRepository repository,
            DefaultChunkContextEnricher enricher, PlatformTransactionManager transactions,
            @Value("${embedding-evaluation.max-chunks:10000}") int maxChunks) {
        this.jdbc = jdbc; this.json = json; this.repository = repository; this.enricher = enricher;
        this.maxChunks = maxChunks;
        snapshotTransaction = new TransactionTemplate(transactions);
        snapshotTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    public void authorize(long tenantId, long knowledgeId) {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM knowledge WHERE id=? AND tenant_id=?", Integer.class, knowledgeId, tenantId);
        if (count == null || count == 0) throw EvaluationRepository.missing();
    }

    public List<ObjectNode> chunks(long tenantId, long knowledgeId) {
        authorize(tenantId, knowledgeId);
        return loadSources(tenantId, knowledgeId).stream().map(this::view).toList();
    }

    public ObjectNode capture(long tenantId, long knowledgeId, ObjectNode command) {
        return snapshotTransaction.execute(status -> {
            authorize(tenantId, knowledgeId);
            String scope = command.path("scope").asText("ALL");
            if (!Set.of("ALL", "SELECTED").contains(scope)) throw EvaluationJson.bad("请选择全部语料或指定分块");
            Set<String> selected = new LinkedHashSet<>();
            command.path("chunkIds").forEach(id -> selected.add(EvaluationRepository.uuid(id.asText()).toString()));
            if (scope.equals("SELECTED") && selected.isEmpty()) throw EvaluationJson.bad("请至少选择一个分块");
            if (scope.equals("ALL")) {
                Integer building = jdbc.queryForObject("SELECT count(*) FROM document_chunk c JOIN file f ON f.id=c.file_id AND f.tenant_id=c.tenant_id WHERE c.tenant_id=? AND c.knowledge_id=? AND c.status=1 AND f.status=1", Integer.class, tenantId, knowledgeId);
                if (building != null && building > 0) throw EvaluationJson.bad("知识库正在更新索引，请完成后建立完整快照");
            }
            List<Source> sources = loadSources(tenantId, knowledgeId);
            List<Source> included = sources.stream().filter(s -> scope.equals("ALL")
                    ? s.enabled() && s.chunk().getStatus() == 2 : selected.contains(s.chunk().getPublicId().toString())).toList();
            if (included.isEmpty()) throw EvaluationJson.bad("没有可评测的分块，请先启用文档并完成分块索引，或选择具体分块测试");
            if (included.size() > maxChunks) throw EvaluationJson.bad("当前语料超过作业上限 " + maxChunks + " 块，请缩小范围或调整作业配置");
            if (scope.equals("SELECTED") && included.size() != selected.size()) throw EvaluationRepository.missing();
            if (scope.equals("ALL") && included.stream().anyMatch(s -> Boolean.TRUE.equals(s.chunk().getIsModified())
                    || s.chunk().getIndexContent() == null)) throw EvaluationJson.bad("部分分块修改尚未建立索引，请先完成索引以冻结一致的语料");
            Map<UUID,String> freshTexts = new HashMap<>();
            if (scope.equals("SELECTED")) {
                Set<Long> fileIds = included.stream().filter(s -> s.chunk().getIndexContent() == null || Boolean.TRUE.equals(s.chunk().getIsModified()))
                        .map(s -> s.chunk().getFileId()).collect(Collectors.toSet());
                Map<Long,List<DocumentChunk>> files = sources.stream().filter(s -> fileIds.contains(s.chunk().getFileId()))
                        .map(Source::chunk).collect(Collectors.groupingBy(DocumentChunk::getFileId));
                for (var entry : files.entrySet()) {
                    List<Integer> limits = jdbc.query("SELECT (policy_snapshot->>'maxTokens')::integer FROM file_processing WHERE file_id=? AND tenant_id=? AND knowledge_id=?",
                            (rs,row) -> rs.getObject(1,Integer.class), entry.getKey(), tenantId, knowledgeId);
                    if (limits.size()!=1 || limits.get(0)==null || limits.get(0)<1 || limits.get(0)>512) throw EvaluationJson.bad("缺少有效的分块策略快照，请重新生成分块后评测");
                    enricher.enrich(entry.getValue(), limits.get(0)).forEach(c -> freshTexts.put(c.chunk().getPublicId(), c.indexContent()));
                }
            }
            ArrayNode chunks = json.createArrayNode();
            for (Source source : included) {
                ObjectNode chunk = view(source);
                String text = source.chunk().getIndexContent();
                if (text == null || Boolean.TRUE.equals(source.chunk().getIsModified())) text = freshTexts.get(source.chunk().getPublicId());
                if (text == null || text.isBlank()) throw EvaluationJson.bad("分块入模文本为空，无法建立评测快照");
                chunk.put("indexContent", text).put("contentHash", EvaluationJson.hash(text));
                chunks.add(chunk);
            }
            ObjectNode snapshot = json.createObjectNode().put("scope", scope).put("createdAt", Instant.now().toString())
                    .put("hash", EvaluationJson.hash(chunks.toString()));
            snapshot.set("chunks", chunks);
            return repository.save("snapshot", UUID.randomUUID().toString(), tenantId, knowledgeId, 0, snapshot);
        });
    }

    public ObjectNode snapshot(long tenantId, long knowledgeId, String id) {
        authorize(tenantId, knowledgeId);
        ObjectNode snapshot = repository.get("snapshot", id, tenantId, knowledgeId, null);
        assertReadable(tenantId, knowledgeId, snapshot);
        return snapshot;
    }

    public void assertReadable(long tenantId, long knowledgeId, ObjectNode snapshot) {
        authorize(tenantId, knowledgeId);
        Set<Long> required = new HashSet<>();
        snapshot.path("chunks").forEach(c -> required.add(c.path("fileId").asLong()));
        Set<Long> current = new HashSet<>(jdbc.queryForList("SELECT file_id FROM knowledge_file WHERE knowledge_id=? AND tenant_id=?",
                Long.class, knowledgeId, tenantId));
        if (!current.containsAll(required)) throw new ResponseStatusException(HttpStatus.GONE, "评测来源文件已删除或移出知识库，请重新建立快照");
    }

    public void assertProductionSnapshotCurrent(long tenantId,long knowledgeId,ObjectNode snapshot) {
        if(!snapshot.path("scope").asText().equals("ALL")) return;
        ArrayNode current=json.createArrayNode();
        for(Source source:loadSources(tenantId,knowledgeId)) {
            if(source.enabled() && source.chunk().getStatus()==2) current.add(view(source));
        }
        // JSONB does not preserve Java's Integer/Long node distinction for identical IDs.
        boolean unchanged = current.equals((left, right) ->
                left.isIntegralNumber() && right.isIntegralNumber()
                        ? left.bigIntegerValue().compareTo(right.bigIntegerValue())
                        : (left.equals(right) ? 0 : 1), snapshot.path("chunks"));
        if(!unchanged) throw EvaluationJson.bad("生产语料已发生变化，请重新冻结快照后进行交付复测");
    }

    public List<ObjectNode> datasets(long tenantId, long knowledgeId) {
        authorize(tenantId, knowledgeId);
        return repository.list("dataset", tenantId, knowledgeId);
    }

    public ObjectNode dataset(long tenantId, long knowledgeId, String id, Integer revision) {
        authorize(tenantId, knowledgeId);
        ObjectNode dataset = repository.get("dataset", id, tenantId, knowledgeId, revision);
        snapshot(tenantId, knowledgeId, dataset.path("snapshotId").asText());
        return dataset;
    }

    public ObjectNode saveDataset(long tenantId, long knowledgeId, String id, ObjectNode command) {
        ObjectNode snapshot = snapshot(tenantId, knowledgeId, EvaluationJson.required(command, "snapshotId", 64));
        ObjectNode dataset = json.createObjectNode().put("name", EvaluationJson.required(command, "name", 160))
                .put("snapshotId", snapshot.path("id").asText()).put("frozen", command.path("frozen").asBoolean());
        Set<String> ids = new HashSet<>();
        snapshot.path("chunks").forEach(c -> ids.add(c.path("id").asText()));
        ArrayNode questions = validateQuestions(command.path("questions"), ids, json);
        dataset.set("questions", questions);
        dataset.put("hash", EvaluationJson.hash(snapshot.path("hash").asText() + questions));
        int revision = id == null ? 0 : command.path("revision").asInt(-1);
        if (id != null) dataset(tenantId, knowledgeId, id, null);
        return repository.save("dataset", id == null ? UUID.randomUUID().toString() : id,
                tenantId, knowledgeId, revision, dataset);
    }

    static ArrayNode validateQuestions(JsonNode inputs, Set<String> chunkIds, ObjectMapper json) {
        if (!inputs.isArray() || inputs.isEmpty() || inputs.size() > 1000) throw EvaluationJson.bad("问题集需要 1～1000 条问题");
        ArrayNode result = json.createArrayNode();
        Set<String> questionIds = new HashSet<>();
        Map<String,String> groupSplits = new HashMap<>();
        Map<String,String> querySplits = new HashMap<>();
        for (JsonNode input : inputs) {
            String query = EvaluationJson.required(input, "query", 4000);
            String id = input.path("id").asText(UUID.randomUUID().toString());
            if (id.isBlank() || id.length() > 100 || !questionIds.add(id)) throw EvaluationJson.bad("问题 ID 必须唯一");
            String group = input.path("intentGroup").asText(id).strip();
            if (group.isEmpty() || group.length() > 160) throw EvaluationJson.bad("意图组名称需要 1～160 字符");
            String split = input.path("split").asText("CALIBRATION");
            if (!Set.of("CALIBRATION", "ACCEPTANCE").contains(split)) throw EvaluationJson.bad("问题分组必须为校准或验收");
            if (groupSplits.containsKey(group) && !groupSplits.get(group).equals(split)) throw EvaluationJson.bad("同一意图组的问法不能同时用于校准和验收");
            String normalizedQuery = query.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
            if (querySplits.containsKey(normalizedQuery)) throw EvaluationJson.bad("问题文本重复，重复执行不能当作不同问法");
            groupSplits.put(group, split); querySplits.put(normalizedQuery, split);
            ObjectNode question = json.createObjectNode().put("id", id).put("query", query).put("intentGroup", group)
                    .put("category", input.path("category").asText("一般问题")).put("split", split)
                    .put("answerable", input.path("answerable").asBoolean(true)).put("reviewed", input.path("reviewed").asBoolean(false));
            ObjectNode labels = question.putObject("labels");
            JsonNode labelInput = input.path("labels");
            if (!labelInput.isMissingNode() && !labelInput.isObject()) throw EvaluationJson.bad("相关性标注格式无效");
            labelInput.fields().forEachRemaining(entry -> {
                JsonNode label = entry.getValue();
                if (!chunkIds.contains(entry.getKey()) || !label.isIntegralNumber() || label.asInt() < 0 || label.asInt() > 2)
                    throw EvaluationJson.bad("标注只能引用当前快照的分块，相关性为 0、1 或 2");
                labels.put(entry.getKey(), label.asInt());
            });
            boolean positive = false;
            for (JsonNode label : labels) positive |= label.asInt() == 2;
            if (question.path("reviewed").asBoolean() && question.path("answerable").asBoolean() && !positive)
                throw EvaluationJson.bad("已审核的有答案问题至少需要一个能支持答案的分块");
            if (!question.path("answerable").asBoolean() && positive) throw EvaluationJson.bad("无答案问题不能标注能支持答案的分块");
            ArrayNode negatives = question.putArray("hardNegativeIds");
            if (input.has("hardNegativeIds") && !input.path("hardNegativeIds").isArray()) throw EvaluationJson.bad("困难负例格式无效");
            Set<String> negativeIds = new LinkedHashSet<>();
            input.path("hardNegativeIds").forEach(negative -> {
                String chunkId = negative.asText();
                if (!labels.has(chunkId) || labels.path(chunkId).asInt() != 0) throw EvaluationJson.bad("困难负例必须是已确认无关的分块");
                negativeIds.add(chunkId);
            });
            negativeIds.forEach(negatives::add);
            result.add(question);
        }
        return result;
    }

    private List<Source> loadSources(long tenantId, long knowledgeId) {
        return jdbc.query("""
                SELECT c.*, f.file_name, f.status AS file_enabled FROM document_chunk c
                JOIN file f ON f.id=c.file_id AND f.tenant_id=c.tenant_id
                JOIN knowledge_file kf ON kf.file_id=f.id AND kf.tenant_id=c.tenant_id AND kf.knowledge_id=c.knowledge_id
                WHERE c.tenant_id=? AND c.knowledge_id=? AND c.status<>1 ORDER BY c.file_id,c.position
                """, (rs, row) -> {
            DocumentChunk c = new DocumentChunk();
            c.setId(rs.getLong("id")); c.setPublicId(rs.getObject("public_id", UUID.class));
            c.setTenantId(tenantId); c.setKnowledgeId(knowledgeId); c.setFileId(rs.getLong("file_id"));
            c.setPosition(rs.getInt("position")); c.setContent(rs.getString("content"));
            c.setIndexContent(rs.getString("index_content")); c.setStatus(rs.getInt("status"));
            c.setIsModified(rs.getBoolean("is_modified")); c.setLockVersion(rs.getInt("lock_version"));
            c.setOverlapEnabled(rs.getBoolean("overlap_enabled")); c.setOverlapTokenLimit(rs.getInt("overlap_token_limit"));
            c.setOverlapContent(rs.getString("overlap_content"));
            try {
                c.setSectionPath(json.readValue(rs.getString("section_path"), json.getTypeFactory().constructCollectionType(List.class,String.class)));
                c.setBoundaryReason(json.readValue(rs.getString("boundary_reason"), Map.class));
                c.setSourceLocator(json.readValue(rs.getString("source_locator"), Map.class));
            } catch (Exception ex) { throw new IllegalStateException("Invalid chunk metadata", ex); }
            return new Source(c, rs.getString("file_name"), rs.getInt("file_enabled") == 1);
        }, tenantId, knowledgeId);
    }

    private ObjectNode view(Source source) {
        DocumentChunk c = source.chunk();
        ObjectNode view = json.createObjectNode().put("id", c.getPublicId().toString()).put("fileId", c.getFileId())
                .put("fileName", source.fileName()).put("position", c.getPosition()).put("content", c.getContent())
                .put("indexContent", c.getIndexContent()).put("lockVersion", c.getLockVersion())
                .put("status", c.getStatus()).put("fileEnabled", source.enabled());
        view.set("sectionPath", json.valueToTree(c.getSectionPath()));
        view.put("contentHash", EvaluationJson.hash(Objects.toString(c.getIndexContent(), c.getContent())));
        return view;
    }

    private record Source(DocumentChunk chunk, String fileName, boolean enabled) {}
}
