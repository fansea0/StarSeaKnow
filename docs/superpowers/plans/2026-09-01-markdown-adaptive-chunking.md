# Markdown 自适应分块 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为 Markdown 文件实现结构感知的智能分块、可恢复的人工预览编辑、可选 Overlap、向量化与可验证的检索来源，并落地已确认的双栏交互。

**Architecture:** 后端以 `ChunkPlanningStrategy` SPI 将文件类型解析/规划与通用预览、状态机、上下文增强、向量化解耦；本期 registry 只注册 `MARKDOWN_OPTIMIZED`。原文预览直接持久化到 `document_chunk`，Overlap 仅在确认向量化后由通用增强器生成。前端本地维护“通用/父子分块”禁用占位，与后端返回的真实策略能力合并。

**Tech Stack:** Java 17、Spring Boot 3.3.10、Spring AI 1.0.0-M6、MyBatis-Plus 3.5.6、PostgreSQL/Flyway、commonmark-java 0.22.0、DJL HuggingFace Tokenizers 0.36.0、Vue 3、Element Plus、Vitest。

**Spec:** `docs/superpowers/specs/2026-08-31-markdown-adaptive-chunking-design.md`

## Global Constraints

- 当前仓库禁止创建 Git worktree；在现有 `feat/kb-detail-redesign` 功能分支继续执行。
- 执行每个任务前检查 `git status --short`，保留现有未提交的 `server/Spring-AI/src/main/resources/file/客服话术.txt` 和未跟踪 `server/Spring-AI/src/test/`，不得覆盖或误提交。
- 当前只实现 `.md/.markdown` 的 `MARKDOWN_OPTIMIZED`；`GENERAL/PARENT_CHILD` 只存在于前端目录并保持不可选择。
- Token 配置固定满足 `0 < minTokens <= targetTokens <= maxTokens <= 512`，默认 `100/400/512`。
- Token 数必须由当前 BGE 模型的 HuggingFace tokenizer 计算，不得使用字符数或 CL100K 估算器。
- Markdown parser/planner 不包含 Overlap；预览接口不返回 `overlap_content/index_content`。
- Chunk 只使用数字状态：`0=DRAFT`、`1=INDEXING`、`2=ACTIVE`；不增加启用、历史版本、回滚、合并或拖动边界能力。
- 文件流程只使用数字状态：`0=UPLOADED`、`1=CHUNKING`、`2=CHUNKED`、`3=ADJUSTING`、`4=CONFIRMED`、`5=VECTORIZING`、`6=COMPLETED`、`7=FAILED`。
- 所有 Chunk 写接口同时校验 `tenantId + knowledgeId + fileId + chunkPublicId`；异步任务显式传播并清理 `AuthContext`。
- 修改后端生产代码至少运行 `cd server/Spring-AI && mvn -q -DskipTests package`；修改前端生产代码至少运行 `cd web && npm run build`。
- 每个任务验证通过后按计划单独提交，提交信息采用 Conventional Commits 中文格式。
- 每次提交前运行 `git diff --cached --name-only`，只允许出现该任务 Files 清单中的路径。

---

## Planned File Structure

### Backend production files

- `server/Spring-AI/src/main/resources/db/V7__add_document_chunking_pipeline.sql`：删除旧嵌入状态，创建流程与 Chunk 表、约束和索引。
- `server/Spring-AI/src/main/resources/tokenizer/bge-base-zh-v1.5-tokenizer.json`：与当前 Embedding 模型一致的 tokenizer 固定资产。
- `server/Spring-AI/src/main/java/com/starsea/ai/domain/FileProcessing.java`：文件当前流程状态。
- `server/Spring-AI/src/main/java/com/starsea/ai/domain/DocumentChunk.java`：当前可编辑/可检索 Chunk。
- `server/Spring-AI/src/main/java/com/starsea/ai/mapper/FileProcessingMapper.java`：条件状态转换与流程查询。
- `server/Spring-AI/src/main/java/com/starsea/ai/mapper/DocumentChunkMapper.java`：Chunk 查询、乐观锁更新与 ACTIVE 后置校验。
- `server/Spring-AI/src/main/resources/mapper/FileProcessingMapper.xml`、`DocumentChunkMapper.xml`：JSONB 映射、批量查询及条件更新 SQL。
- `server/Spring-AI/src/main/java/com/starsea/ai/chunking/model/*`：状态、策略参数、结构块、语义单元、草稿、来源和边界原因。
- `server/Spring-AI/src/main/java/com/starsea/ai/chunking/spi/*`：解析、规划、Token、上下文增强接口。
- `server/Spring-AI/src/main/java/com/starsea/ai/chunking/registry/*`：只注册真实实现的策略 registry。
- `server/Spring-AI/src/main/java/com/starsea/ai/chunking/markdown/*`：Markdown AST 解析、语义单元和自适应规划。
- `server/Spring-AI/src/main/java/com/starsea/ai/chunking/processing/*`：状态机、异步调度与流程恢复。
- `server/Spring-AI/src/main/java/com/starsea/ai/chunking/preview/*`：预览生成、读取、编辑和删除。
- `server/Spring-AI/src/main/java/com/starsea/ai/chunking/context/*`：Overlap 与最终索引文本构建。
- `server/Spring-AI/src/main/java/com/starsea/ai/chunking/indexing/*`：向量写入、补偿、单 Chunk 重算。
- `server/Spring-AI/src/main/java/com/starsea/ai/chunking/api/*`：HTTP DTO、Controller 和业务异常。

### Frontend production files

- `web/src/api/chunking.js`：分块能力、状态、Chunk、确认与重算 API。
- `web/src/features/chunking/strategyCatalog.js`：前端占位目录与后端能力合并。
- `web/src/views/ChunkingWorkspace.vue`：页面状态编排与轮询。
- `web/src/components/chunking/ChunkStrategyPanel.vue`：左侧策略列表。
- `web/src/components/chunking/MarkdownStrategyConfig.vue`：三档 Token 配置。
- `web/src/components/chunking/ChunkPreviewPanel.vue`：右侧空态和 Chunk 列表。
- `web/src/components/chunking/ChunkCard.vue`：只读标题路径、正文编辑、删除与重新向量化。
- `web/src/components/chunking/ContextConfirmDialog.vue`：最终 Overlap 开关与确认。

---

### Task 1: 数据库与持久化状态基线

**Files:**
- Create: `server/Spring-AI/src/main/resources/db/V7__add_document_chunking_pipeline.sql`
- Create: `server/Spring-AI/src/main/java/com/starsea/ai/domain/FileProcessing.java`
- Create: `server/Spring-AI/src/main/java/com/starsea/ai/domain/DocumentChunk.java`
- Create: `server/Spring-AI/src/main/java/com/starsea/ai/mapper/FileProcessingMapper.java`
- Create: `server/Spring-AI/src/main/java/com/starsea/ai/mapper/DocumentChunkMapper.java`
- Create: `server/Spring-AI/src/main/resources/mapper/FileProcessingMapper.xml`
- Create: `server/Spring-AI/src/main/resources/mapper/DocumentChunkMapper.xml`
- Create: `server/Spring-AI/src/main/java/com/starsea/ai/chunking/model/PipelineState.java`
- Create: `server/Spring-AI/src/main/java/com/starsea/ai/chunking/model/ChunkStatus.java`
- Modify: `server/Spring-AI/src/main/java/com/starsea/ai/domain/File.java`
- Modify: `server/Spring-AI/src/main/java/com/starsea/ai/domain/vo/FileVo.java`
- Modify: `server/Spring-AI/src/main/java/com/starsea/ai/service/KnowledgeService.java`
- Modify: `server/Spring-AI/src/main/java/com/starsea/ai/service/impl/KnowledgeServiceImpl.java`
- Modify: `server/Spring-AI/src/main/java/com/starsea/ai/controller/KnowledgeController.java`
- Modify: `server/Spring-AI/src/main/resources/mapper/FileMapper.xml`
- Test: `server/Spring-AI/src/test/java/com/starsea/ai/chunking/model/ChunkStateTest.java`

**Interfaces:**
- Produces: `PipelineState.fromCode(int)`、`ChunkStatus.fromCode(int)`、`FileProcessingMapper.transition(long,long,long,int,int,int,int,Integer,String)`、`DocumentChunkMapper.findByFile(long,long,long)`。
- Consumes: existing `PostgresJsonbTypeHandler` and `UuidTypeHandler` mapping patterns.

- [ ] **Step 1: Write the failing numeric state test**

```java
class ChunkStateTest {
    @Test void pipeline_codes_are_stable() {
        assertEquals(PipelineState.UPLOADED, PipelineState.fromCode(0));
        assertEquals(PipelineState.FAILED, PipelineState.fromCode(7));
        assertThrows(IllegalArgumentException.class, () -> PipelineState.fromCode(8));
    }

    @Test void chunk_codes_are_stable() {
        assertEquals(ChunkStatus.DRAFT, ChunkStatus.fromCode(0));
        assertEquals(ChunkStatus.ACTIVE, ChunkStatus.fromCode(2));
        assertThrows(IllegalArgumentException.class, () -> ChunkStatus.fromCode(-1));
    }
}
```

- [ ] **Step 2: Run the focused test and confirm it fails because the enums do not exist**

Run: `cd server/Spring-AI && mvn -q -Dtest=ChunkStateTest test`

Expected: compilation failure naming `PipelineState` and `ChunkStatus`.

- [ ] **Step 3: Add the migration with exact lifecycle constraints**

The migration must execute these operations in order:

```sql
ALTER TABLE file DROP COLUMN IF EXISTS embedding_status;
ALTER TABLE file ADD CONSTRAINT uk_file_id_tenant UNIQUE (id, tenant_id);

CREATE TABLE file_processing (
    file_id BIGINT PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    knowledge_id BIGINT NOT NULL,
    pipeline_state SMALLINT NOT NULL DEFAULT 0 CHECK (pipeline_state BETWEEN 0 AND 7),
    failed_from_state SMALLINT CHECK (failed_from_state BETWEEN 0 AND 6),
    progress SMALLINT NOT NULL DEFAULT 0 CHECK (progress BETWEEN 0 AND 100),
    source_hash VARCHAR(64),
    strategy_code VARCHAR(64),
    planner_version VARCHAR(64),
    policy_snapshot JSONB NOT NULL DEFAULT '{}'::jsonb,
    context_policy JSONB NOT NULL DEFAULT '{}'::jsonb,
    last_error TEXT,
    lock_version INTEGER NOT NULL DEFAULT 0,
    create_time TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (file_id, tenant_id) REFERENCES file(id, tenant_id) ON DELETE CASCADE,
    FOREIGN KEY (knowledge_id, tenant_id) REFERENCES knowledge(id, tenant_id) ON DELETE CASCADE
);

CREATE TABLE document_chunk (
    id BIGSERIAL PRIMARY KEY,
    public_id UUID NOT NULL DEFAULT gen_random_uuid() UNIQUE,
    tenant_id BIGINT NOT NULL,
    knowledge_id BIGINT NOT NULL,
    file_id BIGINT NOT NULL,
    position INTEGER NOT NULL CHECK (position >= 0),
    content TEXT NOT NULL CHECK (length(btrim(content)) > 0),
    overlap_content TEXT,
    overlap_source_chunk_id BIGINT REFERENCES document_chunk(id) ON DELETE SET NULL,
    overlap_token_count INTEGER NOT NULL DEFAULT 0 CHECK (overlap_token_count >= 0),
    index_content TEXT,
    section_path JSONB NOT NULL DEFAULT '[]'::jsonb,
    source_locator JSONB NOT NULL DEFAULT '{}'::jsonb,
    token_count INTEGER NOT NULL CHECK (token_count >= 0),
    content_hash VARCHAR(64) NOT NULL,
    boundary_reason JSONB NOT NULL DEFAULT '{}'::jsonb,
    status SMALLINT NOT NULL DEFAULT 0 CHECK (status BETWEEN 0 AND 2),
    is_modified BOOLEAN NOT NULL DEFAULT FALSE,
    last_error TEXT,
    lock_version INTEGER NOT NULL DEFAULT 0,
    create_time TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (file_id, tenant_id) REFERENCES file(id, tenant_id) ON DELETE CASCADE,
    FOREIGN KEY (knowledge_id, tenant_id) REFERENCES knowledge(id, tenant_id) ON DELETE CASCADE,
    UNIQUE (file_id, position)
);
```

Also create `idx_file_processing_scope_state` on `(tenant_id, knowledge_id, pipeline_state)`, `idx_document_chunk_scope_status` on `(tenant_id, knowledge_id, file_id, status)`, and update-time triggers using the existing `update_timestamp()` function.

- [ ] **Step 4: Implement entities, JSONB fields, mappers, and stable numeric enums**

Use `@TableName(autoResultMap = true)`. Map UUID with `UuidTypeHandler`, and map `Map<String,Object>` / `List<String>` JSON fields with `PostgresJsonbTypeHandler`. Implement state lookup without ordinal persistence:

```java
public enum PipelineState {
    UPLOADED(0), CHUNKING(1), CHUNKED(2), ADJUSTING(3),
    CONFIRMED(4), VECTORIZING(5), COMPLETED(6), FAILED(7);
    private final int code;
    PipelineState(int code) { this.code = code; }
    public int code() { return code; }
    public static PipelineState fromCode(int code) {
        return Arrays.stream(values()).filter(v -> v.code == code).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unknown pipeline state: " + code));
    }
}
```

Remove `embeddingStatus` from `File`, `FileVo`, and `FileMapper.xml`. Remove the direct `POST /knowledge/file` endpoint plus `KnowledgeService.loadEmbedding` and its implementation so a migrated database cannot call code that still expects the dropped column. Do not add parent/child or history fields.

- [ ] **Step 5: Run tests and compile the backend**

Run: `cd server/Spring-AI && mvn -q -Dtest=ChunkStateTest test && mvn -q -DskipTests package`

Expected: state tests pass and package exits 0.

- [ ] **Step 6: Commit only the schema/state foundation**

```bash
git add server/Spring-AI/src/main/resources/db/V7__add_document_chunking_pipeline.sql \
  server/Spring-AI/src/main/java/com/starsea/ai/domain/FileProcessing.java \
  server/Spring-AI/src/main/java/com/starsea/ai/domain/DocumentChunk.java \
  server/Spring-AI/src/main/java/com/starsea/ai/mapper/FileProcessingMapper.java \
  server/Spring-AI/src/main/java/com/starsea/ai/mapper/DocumentChunkMapper.java \
  server/Spring-AI/src/main/resources/mapper/FileProcessingMapper.xml \
  server/Spring-AI/src/main/resources/mapper/DocumentChunkMapper.xml \
  server/Spring-AI/src/main/java/com/starsea/ai/chunking/model \
  server/Spring-AI/src/main/java/com/starsea/ai/domain/File.java \
  server/Spring-AI/src/main/java/com/starsea/ai/domain/vo/FileVo.java \
  server/Spring-AI/src/main/java/com/starsea/ai/service/KnowledgeService.java \
  server/Spring-AI/src/main/java/com/starsea/ai/service/impl/KnowledgeServiceImpl.java \
  server/Spring-AI/src/main/java/com/starsea/ai/controller/KnowledgeController.java \
  server/Spring-AI/src/main/resources/mapper/FileMapper.xml \
  server/Spring-AI/src/test/java/com/starsea/ai/chunking/model/ChunkStateTest.java
git commit -m "feat: 建立文档分块状态模型"
```

### Task 2: 精确 Tokenizer、策略参数与扩展 SPI

**Files:**
- Modify: `server/Spring-AI/pom.xml`
- Modify: `server/Spring-AI/src/main/resources/application.yml`
- Create: `server/Spring-AI/src/main/resources/tokenizer/bge-base-zh-v1.5-tokenizer.json`
- Create: `server/Spring-AI/src/main/java/com/starsea/ai/chunking/model/ChunkPolicy.java`
- Create: `server/Spring-AI/src/main/java/com/starsea/ai/chunking/model/ContextPolicy.java`
- Create: `server/Spring-AI/src/main/java/com/starsea/ai/chunking/model/SourceLocator.java`
- Create: `server/Spring-AI/src/main/java/com/starsea/ai/chunking/model/FileResource.java`
- Create: `server/Spring-AI/src/main/java/com/starsea/ai/chunking/model/StructuredBlock.java`
- Create: `server/Spring-AI/src/main/java/com/starsea/ai/chunking/model/ParsedStructure.java`
- Create: `server/Spring-AI/src/main/java/com/starsea/ai/chunking/model/SemanticUnit.java`
- Create: `server/Spring-AI/src/main/java/com/starsea/ai/chunking/model/ChunkDraft.java`
- Create: `server/Spring-AI/src/main/java/com/starsea/ai/chunking/spi/TokenCounter.java`
- Create: `server/Spring-AI/src/main/java/com/starsea/ai/chunking/token/HuggingFaceTokenCounter.java`
- Create: `server/Spring-AI/src/main/java/com/starsea/ai/chunking/spi/DocumentStructureParser.java`
- Create: `server/Spring-AI/src/main/java/com/starsea/ai/chunking/spi/ChunkPlanningStrategy.java`
- Create: `server/Spring-AI/src/main/java/com/starsea/ai/chunking/registry/ChunkStrategyDescriptor.java`
- Create: `server/Spring-AI/src/main/java/com/starsea/ai/chunking/registry/ChunkStrategyRegistry.java`
- Create: `server/Spring-AI/src/main/java/com/starsea/ai/chunking/registry/DocumentStructureParserRegistry.java`
- Create: `server/Spring-AI/src/main/java/com/starsea/ai/chunking/registry/ChunkStrategyNotFoundException.java`
- Create: `server/Spring-AI/src/main/java/com/starsea/ai/chunking/config/ChunkingConfiguration.java`
- Test: `server/Spring-AI/src/test/java/com/starsea/ai/chunking/registry/ChunkStrategyRegistryTest.java`
- Test: `server/Spring-AI/src/test/java/com/starsea/ai/chunking/token/HuggingFaceTokenCounterTest.java`

**Interfaces:**
- Produces: `TokenCounter.count(String)`、`TokenCounter.id()`、`new ChunkPolicy(int minTokens,int targetTokens,int maxTokens)`、`ChunkStrategyRegistry.require(String code,String fileType)`。
- Consumes: DJL `HuggingFaceTokenizer.newInstance(Path)`.

- [ ] **Step 1: Add failing tests for policy validation, exact Chinese token count, and registry filtering**

```java
@Test void rejects_invalid_token_order() {
    assertThrows(IllegalArgumentException.class, () -> new ChunkPolicy(400, 100, 512));
    assertThrows(IllegalArgumentException.class, () -> new ChunkPolicy(100, 400, 513));
}

@Test void bge_tokenizer_counts_special_tokens() {
    assertEquals(8, counter.count("湖南科技大学"));
    assertTrue(counter.id().startsWith("BAAI/bge-base-zh-v1.5@"));
}

@Test void registry_returns_only_real_matching_implementations() {
    assertEquals("MARKDOWN_OPTIMIZED", registry.require("MARKDOWN_OPTIMIZED", "md").code());
    assertThrows(ChunkStrategyNotFoundException.class,
            () -> registry.require("PARENT_CHILD", "md"));
}
```

Construct the test registry with a nested fake `ChunkPlanningStrategy` whose only supported file type is `md`; do not create production `GENERAL` or `PARENT_CHILD` implementations.

- [ ] **Step 2: Run the focused tests and verify missing types/assets cause failure**

Run: `cd server/Spring-AI && mvn -q -Dtest=ChunkStrategyRegistryTest,HuggingFaceTokenCounterTest test`

- [ ] **Step 3: Add deterministic tokenizer dependency and asset**

Add `ai.djl.huggingface:tokenizers:0.36.0` and explicit `org.commonmark:commonmark:0.22.0` plus `commonmark-ext-gfm-tables:0.22.0` to `pom.xml`. Add `**/*.json` to the Maven resource includes so the tokenizer is present in the packaged JAR.

Download the tokenizer only from:

```text
https://huggingface.co/BAAI/bge-base-zh-v1.5/resolve/main/tokenizer.json?download=true
```

Store it at the declared resource path and verify:

```bash
shasum -a 256 server/Spring-AI/src/main/resources/tokenizer/bge-base-zh-v1.5-tokenizer.json
```

Expected SHA-256: `7dfbf1966ebf99d471c3796e9b457329d2b2182b817e144f1e904b957745c839`.

- [ ] **Step 4: Implement the interfaces and startup configuration**

`TokenCounter` must count the entire final string including BERT special tokens:

```java
public interface TokenCounter {
    int count(String text);
    String id();
}

public final class HuggingFaceTokenCounter implements TokenCounter, AutoCloseable {
    private final HuggingFaceTokenizer tokenizer;
    private final String id;
    public int count(String text) { return tokenizer.encode(text == null ? "" : text).getIds().length; }
    public String id() { return id; }
    public void close() { tokenizer.close(); }
}
```

`ChunkPolicy` validates its three constructor values and exposes `minTokens()`, `targetTokens()`, and `maxTokens()`. `ContextPolicy` validates `0 <= overlapTokens <= 512`; its default factory returns `new ContextPolicy(false, 40)`.

Expose one bean whose ID is `BAAI/bge-base-zh-v1.5@7dfbf196`. Load the classpath resource through `ClassPathResource.getInputStream()` and `HuggingFaceTokenizer.newInstance(inputStream, Map.of())`, so it also works from a packaged JAR. Fail application startup when the tokenizer file is absent or its checksum differs; never fall back to another estimator.

Define the strategy contract exactly as:

```java
public interface ChunkPlanningStrategy {
    String code();
    Set<String> supportedFileTypes();
    String plannerVersion();
    ChunkStrategyDescriptor descriptor();
    List<ChunkDraft> plan(ParsedStructure structure, ChunkPolicy policy);
}
```

Define the file-type-neutral input as `record FileResource(long tenantId, long knowledgeId, long fileId, UUID filePublicId, String fileName, String fileType, Path path)`. PDF can later reuse the same record without changing the preview service.

Define `SourceLocator` with nullable cross-format coordinates: `type`, `blockIds`, `startOffset`, `endOffset`, `startLine`, `endLine`, `startPage`, `endPage`, and `regions`. Markdown fills offsets/lines and leaves page fields null; future PDF does the inverse. Persist it as JSONB without adding format-specific database columns.

Define the parser contract and lookup exactly as:

```java
public interface DocumentStructureParser {
    Set<String> supportedFileTypes();
    ParsedStructure parse(FileResource resource);
}

public final class DocumentStructureParserRegistry {
    public DocumentStructureParser require(String fileType);
}
```

`ChunkStrategyRegistry` receives `List<ChunkPlanningStrategy>` from Spring and returns only registered implementations supporting the normalized extension. `DocumentStructureParserRegistry` independently receives `List<DocumentStructureParser>`; the preview worker selects both by normalized file type, so adding PDF does not alter Markdown code.

- [ ] **Step 5: Run tests and package**

Run: `cd server/Spring-AI && mvn -q -Dtest=ChunkStrategyRegistryTest,HuggingFaceTokenCounterTest test && mvn -q -DskipTests package`

- [ ] **Step 6: Commit tokenizer and SPI together**

```bash
git add server/Spring-AI/pom.xml server/Spring-AI/src/main/resources/application.yml \
  server/Spring-AI/src/main/resources/tokenizer \
  server/Spring-AI/src/main/java/com/starsea/ai/chunking \
  server/Spring-AI/src/test/java/com/starsea/ai/chunking/registry/ChunkStrategyRegistryTest.java \
  server/Spring-AI/src/test/java/com/starsea/ai/chunking/token/HuggingFaceTokenCounterTest.java
git commit -m "feat: 增加分块策略扩展接口与精确分词器"
```

### Task 3: Markdown AST 结构解析

**Files:**
- Create: `server/Spring-AI/src/main/java/com/starsea/ai/chunking/markdown/MarkdownStructureParser.java`
- Create: `server/Spring-AI/src/main/java/com/starsea/ai/chunking/markdown/MarkdownBlockRenderer.java`
- Create: `server/Spring-AI/src/main/java/com/starsea/ai/chunking/model/BlockType.java`
- Test: `server/Spring-AI/src/test/java/com/starsea/ai/chunking/markdown/MarkdownStructureParserTest.java`
- Test resource: `server/Spring-AI/src/test/resources/chunking/markdown/structure-sample.md`

**Interfaces:**
- Consumes: `DocumentStructureParser.parse(FileResource)` and `TokenCounter`.
- Produces: ordered `ParsedStructure.blocks()` with section paths and Markdown `SourceLocator`.

- [ ] **Step 1: Write parser tests for heading stack, rich blocks, source lines, and empty headings**

The fixture must contain H1→H3 jump, H2 rollback, nested list, GFM table, fenced code, quote, HTML block, thematic break, and a final empty heading. Assert:

```java
assertEquals(List.of("指南", "网络", "重置密码"), passwordBlock.sectionPath());
assertEquals(BlockType.TABLE, tableBlock.type());
assertEquals(7, tableBlock.sourceLocator().startLine());
assertTrue(parsed.blocks().stream().anyMatch(block ->
        block.type() == BlockType.HEADING && block.plainText().equals("空章节")));
```

- [ ] **Step 2: Run the test and verify it fails because the parser is absent**

Run: `cd server/Spring-AI && mvn -q -Dtest=MarkdownStructureParserTest test`

- [ ] **Step 3: Implement commonmark parsing with source spans and GFM tables**

Build the parser with:

```java
Parser.builder()
    .includeSourceSpans(IncludeSourceSpans.BLOCKS_AND_INLINES)
    .extensions(List.of(TablesExtension.create()))
    .build();
```

Traverse top-level block nodes in source order. Maintain a heading stack by removing entries with level `>= currentLevel`, then add the current heading. Emit heading blocks so the planner can score explicit section boundaries, but never turn them into standalone Chunk drafts. Render `plainText` with `TextContentRenderer`; derive `rawText` from the original source using the first and last `SourceSpan` plus precomputed line offsets, so list markers, table markup, code fences, quotes and HTML remain byte-for-byte intact. Convert zero-based `SourceSpan` rows to one-based API lines.

- [ ] **Step 4: Prove parser has no context/Overlap dependency**

Run:

```bash
rg -n 'overlap|ContextPolicy|ChunkContextEnricher' \
  server/Spring-AI/src/main/java/com/starsea/ai/chunking/markdown/MarkdownStructureParser.java
```

Expected: no output.

- [ ] **Step 5: Run parser tests and package**

Run: `cd server/Spring-AI && mvn -q -Dtest=MarkdownStructureParserTest test && mvn -q -DskipTests package`

- [ ] **Step 6: Commit**

```bash
git add server/Spring-AI/src/main/java/com/starsea/ai/chunking/markdown \
  server/Spring-AI/src/main/java/com/starsea/ai/chunking/model/BlockType.java \
  server/Spring-AI/src/test/java/com/starsea/ai/chunking/markdown/MarkdownStructureParserTest.java \
  server/Spring-AI/src/test/resources/chunking/markdown/structure-sample.md
git commit -m "feat: 实现 Markdown 结构感知解析"
```

### Task 4: 语义单元与自适应 Chunk 规划

**Files:**
- Create: `server/Spring-AI/src/main/java/com/starsea/ai/chunking/markdown/MarkdownSemanticUnitBuilder.java`
- Create: `server/Spring-AI/src/main/java/com/starsea/ai/chunking/markdown/MarkdownChunkPlanningStrategy.java`
- Create: `server/Spring-AI/src/main/java/com/starsea/ai/chunking/markdown/MarkdownRecursiveSplitter.java`
- Create: `server/Spring-AI/src/main/java/com/starsea/ai/chunking/model/BoundaryReason.java`
- Test: `server/Spring-AI/src/test/java/com/starsea/ai/chunking/markdown/MarkdownChunkPlanningStrategyTest.java`
- Test: `server/Spring-AI/src/test/java/com/starsea/ai/chunking/markdown/MarkdownGoldenSampleTest.java`

**Interfaces:**
- Consumes: `ParsedStructure`, `ChunkPolicy`, `TokenCounter`.
- Produces: `MARKDOWN_OPTIMIZED`, planner version `markdown-adaptive-v1`, and ordered `ChunkDraft` records.

- [ ] **Step 1: Add failing boundary-quality tests**

Cover these exact cases: title-only sections produce no Chunk; guide paragraph remains with its following list/table/code; peer labels (`Q1：`, `1.`, `（一）`, `第一条`, `步骤 1`) start candidate units; long prose splits on a complete sentence; long table repeats its header; short tails merge only under identical `sectionPath`.

The golden test for `科大百事通.md` must assert a range rather than an exact count:

```java
assertTrue(chunks.size() >= 5 && chunks.size() <= 15);
assertTrue(chunks.stream().allMatch(c -> !c.content().isBlank()));
assertTrue(chunks.stream().allMatch(c -> counter.count(indexText(c)) <= 512));
assertTrue(chunks.stream().anyMatch(c -> c.sectionPath().contains("招生录取类问题")
        && c.content().contains("Q1") && c.content().contains("一本批次")));
```

- [ ] **Step 2: Run planner tests and confirm they fail**

Run: `cd server/Spring-AI && mvn -q -Dtest=MarkdownChunkPlanningStrategyTest,MarkdownGoldenSampleTest test`

- [ ] **Step 3: Implement semantic grouping and scored boundary selection**

Use stable score constants:

```java
static final int H1_H2 = 100;
static final int H3_H4 = 90;
static final int THEMATIC_BREAK = 90;
static final int PEER_LABEL = 85;
static final int CONTAINER_END = 70;
static final int PARAGRAPH_END = 50;
static final int SENTENCE_END = 25;
```

Accumulate units until `minTokens`; near `targetTokens`, pick the legal boundary with the highest score and then the smallest absolute distance to target. Before exceeding `maxTokens`, roll back to the last legal boundary. A forced split may occur only inside an oversized unit, in order: sentence → list item/table row/code line → token-safe substring.

Use the formatter for budget checks:

```java
String previewIndexText(List<String> path, String content) {
    return path.isEmpty() ? content : "标题：" + String.join(" > ", path) + "\n\n" + content;
}
```

Do not include document name or Overlap.

- [ ] **Step 4: Run planner and golden tests**

Run: `cd server/Spring-AI && mvn -q -Dtest=MarkdownChunkPlanningStrategyTest,MarkdownGoldenSampleTest test`

Expected: all tests pass, no Chunk exceeds the configured hard maximum.

- [ ] **Step 5: Commit**

```bash
git add server/Spring-AI/src/main/java/com/starsea/ai/chunking/markdown \
  server/Spring-AI/src/main/java/com/starsea/ai/chunking/model/BoundaryReason.java \
  server/Spring-AI/src/test/java/com/starsea/ai/chunking/markdown/MarkdownChunkPlanningStrategyTest.java \
  server/Spring-AI/src/test/java/com/starsea/ai/chunking/markdown/MarkdownGoldenSampleTest.java
git commit -m "feat: 实现 Markdown 自适应分块规划"
```

### Task 5: 上传流程、租户归属与集中状态机

**Files:**
- Create: `server/Spring-AI/src/main/java/com/starsea/ai/chunking/processing/FileProcessingService.java`
- Create: `server/Spring-AI/src/main/java/com/starsea/ai/chunking/processing/ChunkTaskDispatcher.java`
- Modify: `server/Spring-AI/src/main/java/com/starsea/ai/chunking/config/ChunkingConfiguration.java`
- Modify: `server/Spring-AI/src/main/resources/application.yml`
- Modify: `server/Spring-AI/src/main/java/com/starsea/ai/service/FileService.java`
- Modify: `server/Spring-AI/src/main/java/com/starsea/ai/service/impl/FileServiceImpl.java`
- Modify: `server/Spring-AI/src/main/java/com/starsea/ai/controller/FileController.java`
- Modify: `server/Spring-AI/src/main/java/com/starsea/ai/controller/KnowledgeController.java`
- Delete: `server/Spring-AI/src/main/java/com/starsea/ai/controller/RagController.java`
- Modify: `server/Spring-AI/src/main/java/com/starsea/ai/domain/vo/FileVo.java`
- Test: `server/Spring-AI/src/test/java/com/starsea/ai/chunking/processing/FileProcessingServiceTest.java`
- Test: `server/Spring-AI/src/test/java/com/starsea/ai/service/impl/FileServiceImplTest.java`

**Interfaces:**
- Produces: `FileProcessingService.transition(fileId, expected, target)` and one `UPLOADED` row per successful knowledge upload.
- Consumes: Task 1 mappers and the current request `AuthContext`.

- [ ] **Step 1: Write failing state transition and upload-creation tests**

Assert legal transitions and reject `UPLOADED → COMPLETED`, stale `lockVersion`, cross-tenant knowledge/file pairs, and upload without a `file_processing` insert.

- [ ] **Step 2: Run focused tests and confirm failures**

Run: `cd server/Spring-AI && mvn -q -Dtest=FileProcessingServiceTest,FileServiceImplTest test`

- [ ] **Step 3: Implement conditional state updates and tenant-aware async dispatch**

The mapper transition SQL must update only when all expected values match:

```sql
UPDATE file_processing
SET pipeline_state = #{target}, progress = #{progress}, last_error = #{lastError},
    failed_from_state = #{failedFromState}, lock_version = lock_version + 1
WHERE tenant_id = #{tenantId} AND knowledge_id = #{knowledgeId} AND file_id = #{fileId}
  AND pipeline_state = #{expected} AND lock_version = #{lockVersion}
```

`ChunkTaskDispatcher` captures `AuthContext.current()` before submitting. Inside the worker thread it calls `AuthContext.set(new AuthContext(context.getKind(), context.getUserId(), context.getTenantId(), context.getRole(), context.getJti()))`, and always calls `AuthContext.clear()` in `finally`. Transition to the asynchronous state immediately before `executor.execute`; if submission throws `RejectedExecutionException`, conditionally restore the previous state and return HTTP 503. Return 202 only after the executor accepts the task.

Configure a dedicated `ThreadPoolTaskExecutor` with core size 2, max size 4, queue capacity 64 and prefix `chunking-`; expose the four numbers through `chunking.executor.*` properties. Do not use the shared retrieval executor.

- [ ] **Step 4: Make upload atomic at the database boundary**

After the physical file is written, a transaction inserts `file`, `knowledge_file`, and `file_processing(UPLOADED)`. On database failure, delete only the just-created explicit file path. Return `fileId`; do not start vectorization. Remove standalone `POST /file/upload` and `FileService.uploadDocument` so every accepted file is born with a knowledge relation and processing row.

Delete the unused `/rag/file` controller. Add `pipelineState`, `progress`, and `processingError` to `FileVo`, and make the knowledge file list join the single `file_processing` row instead of issuing one query per file.

- [ ] **Step 5: Run tests and package**

Run: `cd server/Spring-AI && mvn -q -Dtest=FileProcessingServiceTest,FileServiceImplTest test && mvn -q -DskipTests package`

- [ ] **Step 6: Commit**

```bash
git add server/Spring-AI/src/main/java/com/starsea/ai/chunking/processing \
  server/Spring-AI/src/main/java/com/starsea/ai/chunking/config/ChunkingConfiguration.java \
  server/Spring-AI/src/main/resources/application.yml \
  server/Spring-AI/src/main/java/com/starsea/ai/service/FileService.java \
  server/Spring-AI/src/main/java/com/starsea/ai/service/impl/FileServiceImpl.java \
  server/Spring-AI/src/main/java/com/starsea/ai/controller/FileController.java \
  server/Spring-AI/src/main/java/com/starsea/ai/controller/KnowledgeController.java \
  server/Spring-AI/src/main/java/com/starsea/ai/controller/RagController.java \
  server/Spring-AI/src/main/java/com/starsea/ai/domain/vo/FileVo.java \
  server/Spring-AI/src/test/java/com/starsea/ai/chunking/processing/FileProcessingServiceTest.java \
  server/Spring-AI/src/test/java/com/starsea/ai/service/impl/FileServiceImplTest.java
git commit -m "feat: 接入文件分块流程状态机"
```

### Task 6: 策略能力、异步预览生成与恢复 API

**Files:**
- Create: `server/Spring-AI/src/main/java/com/starsea/ai/chunking/api/ChunkingApiModels.java`
- Create: `server/Spring-AI/src/main/java/com/starsea/ai/chunking/api/ChunkingController.java`
- Create: `server/Spring-AI/src/main/java/com/starsea/ai/chunking/api/ChunkingException.java`
- Create: `server/Spring-AI/src/main/java/com/starsea/ai/chunking/preview/ChunkPreviewService.java`
- Create: `server/Spring-AI/src/main/java/com/starsea/ai/chunking/preview/ChunkPreviewWorker.java`
- Create: `server/Spring-AI/src/main/java/com/starsea/ai/chunking/preview/ChunkPreviewPersistenceService.java`
- Modify: `server/Spring-AI/src/main/java/com/starsea/ai/config/GlobalExceptionHandler.java`
- Test: `server/Spring-AI/src/test/java/com/starsea/ai/chunking/api/ChunkingControllerTest.java`
- Test: `server/Spring-AI/src/test/java/com/starsea/ai/chunking/preview/ChunkPreviewWorkerTest.java`

**Interfaces:**
- Produces: capability GET, preview POST returning 202, processing GET, and persisted DRAFT chunks.
- Consumes: Tasks 3–5 parser, planner, registry, state service, and mappers.

- [ ] **Step 1: Write failing controller contract tests**

Use MockMvc to assert:

```json
{
  "strategyCode": "MARKDOWN_OPTIMIZED",
  "strategyConfig": {"minTokens": 100, "targetTokens": 400, "maxTokens": 512},
  "replaceEditedDrafts": false,
  "lockVersion": 0
}
```

returns HTTP 202; capability GET for an MD file returns only `MARKDOWN_OPTIMIZED`; forged `GENERAL` returns 422; a file outside the tenant/knowledge scope returns 404.

- [ ] **Step 2: Write failing worker transaction tests**

Assert successful generation saves all drafts in one transaction and writes `source_hash`, `strategy_code`, `planner_version`, `policy_snapshot` including tokenizer ID, then changes to `CHUNKED`. Parser/planner or persistence failure must leave no partial Chunk and set `FAILED` with `failed_from_state=CHUNKING`.

- [ ] **Step 3: Run tests and confirm failure**

Run: `cd server/Spring-AI && mvn -q -Dtest=ChunkingControllerTest,ChunkPreviewWorkerTest test`

- [ ] **Step 4: Implement API records and response filtering**

Define nested records in `ChunkingApiModels`:

```java
public record PreviewRequest(String strategyCode, ChunkPolicy strategyConfig,
                             boolean replaceEditedDrafts, int lockVersion) {}
public record StrategyResponse(String fileType, List<ChunkStrategyDescriptor> strategies) {}
public record ProcessingResponse(int state, Integer failedFromState, int progress,
                                 String lastError, int lockVersion, String strategyCode,
                                 Map<String,Object> policySnapshot,
                                 Map<String,Object> contextPolicy) {}
```

The controller uses `@RequireLogin` and `@RequireRole("tenant_admin")` for writes. Map state/lock conflicts to 409, invalid strategy/content/budget to 422, and ownership failures to 404.

- [ ] **Step 5: Implement asynchronous preview flow**

The request validates ownership and policy, then transitions `UPLOADED`, `CHUNKED`, `ADJUSTING`, or a FAILED row with `failed_from_state=CHUNKING` to `CHUNKING`, and dispatches IDs plus immutable policy. The worker hashes the source with SHA-256, selects both parser and planner from their registries, parses, plans, and delegates a short `@Transactional` replacement to `ChunkPreviewPersistenceService`. Existing ACTIVE chunks reject full regeneration. Edited DRAFT chunks return 409 while `replaceEditedDrafts=false`; after the UI confirmation, the same request with `replaceEditedDrafts=true` replaces the current draft set.

Use controller base path `/knowledge/{knowledgeId}/files/{fileId}` with `GET /chunk-strategies`, `POST /chunk-preview`, and `GET /processing`.

- [ ] **Step 6: Run tests and package**

Run: `cd server/Spring-AI && mvn -q -Dtest=ChunkingControllerTest,ChunkPreviewWorkerTest test && mvn -q -DskipTests package`

- [ ] **Step 7: Commit**

```bash
git add server/Spring-AI/src/main/java/com/starsea/ai/chunking/api \
  server/Spring-AI/src/main/java/com/starsea/ai/chunking/preview \
  server/Spring-AI/src/main/java/com/starsea/ai/config/GlobalExceptionHandler.java \
  server/Spring-AI/src/test/java/com/starsea/ai/chunking/api/ChunkingControllerTest.java \
  server/Spring-AI/src/test/java/com/starsea/ai/chunking/preview/ChunkPreviewWorkerTest.java
git commit -m "feat: 增加 Markdown 分块预览接口"
```

### Task 7: 通用索引文本与 Overlap 增强器

**Files:**
- Create: `server/Spring-AI/src/main/java/com/starsea/ai/chunking/context/ChunkIndexContentBuilder.java`
- Create: `server/Spring-AI/src/main/java/com/starsea/ai/chunking/context/SentenceBoundaryDetector.java`
- Create: `server/Spring-AI/src/main/java/com/starsea/ai/chunking/context/DefaultChunkContextEnricher.java`
- Create: `server/Spring-AI/src/main/java/com/starsea/ai/chunking/spi/ChunkContextEnricher.java`
- Create: `server/Spring-AI/src/main/java/com/starsea/ai/chunking/model/EnrichedChunk.java`
- Test: `server/Spring-AI/src/test/java/com/starsea/ai/chunking/context/ChunkContextEnricherTest.java`
- Test: `server/Spring-AI/src/test/java/com/starsea/ai/chunking/context/ChunkIndexContentBuilderTest.java`

**Interfaces:**
- Produces: `ChunkIndexContentBuilder.build(List<String> sectionPath,String overlap,String body)` and `ChunkContextEnricher.enrich(List<DocumentChunk> chunks,ContextPolicy policy,int maxTokens)`.
- Consumes: current edited `DocumentChunk` rows and BGE `TokenCounter`.

- [ ] **Step 1: Write failing formatter and complete-sentence tests**

Assert exact strings:

```text
标题：招生录取类问题

编辑后的正文
```

and, when enabled:

```text
标题：招生录取类问题
上文：这是前一个 Chunk 的完整句。

编辑后的正文
```

Test Chinese `。！？` and English `.?!`; never cut a sentence to fill 40 tokens.

- [ ] **Step 2: Add failing structural boundary tests**

No Overlap across a different title path, thematic/peer-label boundary, table/code ending, deleted position gap, or when the current body consumes all of `maxTokens`. Verify same-path continuous prose receives at most 40 tokens by default.

- [ ] **Step 3: Run focused tests and confirm failure**

Run: `cd server/Spring-AI && mvn -q -Dtest=ChunkContextEnricherTest,ChunkIndexContentBuilderTest test`

- [ ] **Step 4: Implement the generic enhancer after persistence**

Walk chunks by position. For each eligible pair, take complete sentences from the previous edited body in reverse order. Measure Overlap cost as `token(finalTextWithCandidate) - token(finalTextWithoutOverlap)`, so BERT special tokens are not counted twice. Stop before adding the next sentence would exceed either `overlapTokens` or the final configured `maxTokens`. Return `overlapSourceChunkId`, actual content/count, and final index text. Disabled context policy always returns null Overlap.

- [ ] **Step 5: Prove context code does not call Markdown parser/planner**

Run:

```bash
rg -n 'MarkdownStructureParser|MarkdownChunkPlanningStrategy' \
  server/Spring-AI/src/main/java/com/starsea/ai/chunking/context
```

Expected: no output.

- [ ] **Step 6: Run tests and commit**

Run: `cd server/Spring-AI && mvn -q -Dtest=ChunkContextEnricherTest,ChunkIndexContentBuilderTest test`

```bash
git add server/Spring-AI/src/main/java/com/starsea/ai/chunking/context \
  server/Spring-AI/src/main/java/com/starsea/ai/chunking/spi/ChunkContextEnricher.java \
  server/Spring-AI/src/main/java/com/starsea/ai/chunking/model/EnrichedChunk.java \
  server/Spring-AI/src/test/java/com/starsea/ai/chunking/context/ChunkContextEnricherTest.java \
  server/Spring-AI/src/test/java/com/starsea/ai/chunking/context/ChunkIndexContentBuilderTest.java
git commit -m "feat: 增加通用 Overlap 上下文增强"
```

### Task 8: Chunk 查询、编辑、删除与乐观锁

**Files:**
- Create: `server/Spring-AI/src/main/java/com/starsea/ai/chunking/preview/ChunkCommandService.java`
- Create: `server/Spring-AI/src/main/java/com/starsea/ai/chunking/indexing/ChunkVectorGateway.java`
- Modify: `server/Spring-AI/src/main/java/com/starsea/ai/chunking/api/ChunkingApiModels.java`
- Modify: `server/Spring-AI/src/main/java/com/starsea/ai/chunking/api/ChunkingController.java`
- Modify: `server/Spring-AI/src/main/java/com/starsea/ai/mapper/DocumentChunkMapper.java`
- Modify: `server/Spring-AI/src/main/resources/mapper/DocumentChunkMapper.xml`
- Test: `server/Spring-AI/src/test/java/com/starsea/ai/chunking/preview/ChunkCommandServiceTest.java`
- Test: `server/Spring-AI/src/test/java/com/starsea/ai/chunking/api/ChunkVisibilityTest.java`

**Interfaces:**
- Produces: chunks GET, content PATCH, DELETE; no Overlap/index leakage.
- Consumes: `ChunkIndexContentBuilder`, state service, and a mockable `ChunkVectorGateway.delete(UUID publicId)`.

- [ ] **Step 1: Write failing API visibility and command tests**

Assert GET returns `publicId, position, content, sectionPath, sourceLocator, tokenCount, status, isModified, lockVersion` and does not serialize `overlapContent` or `indexContent`. PATCH requires nonblank `content` and matching `lockVersion`; stale version returns 409. Editing above the full title/body budget returns 422 with separate counts.

Expose `GET /chunks`, `PATCH /chunks/{chunkPublicId}`, and `DELETE /chunks/{chunkPublicId}` under the Task 6 controller base path.

- [ ] **Step 2: Test ACTIVE invalidation and delete compensation**

Editing ACTIVE must first conditionally set it to DRAFT and clear `index_content`; retrieval therefore stops seeing it before vector deletion. When Overlap is enabled, editing/deleting a source Chunk also clears the next dependent Chunk and sets it DRAFT. Deleting the final Chunk makes confirm return 422.

After a successful edit or delete, move a CHUNKED or COMPLETED file to ADJUSTING. A file remains ADJUSTING while any Chunk is DRAFT; it returns to COMPLETED only after every remaining Chunk is ACTIVE.

- [ ] **Step 3: Run focused tests and confirm failure**

Run: `cd server/Spring-AI && mvn -q -Dtest=ChunkCommandServiceTest,ChunkVisibilityTest test`

- [ ] **Step 4: Implement exact optimistic update**

```sql
UPDATE document_chunk
SET content = #{content}, token_count = #{tokenCount}, content_hash = #{contentHash},
    status = 0, is_modified = TRUE, index_content = NULL, last_error = NULL,
    lock_version = lock_version + 1
WHERE tenant_id = #{tenantId} AND knowledge_id = #{knowledgeId}
  AND file_id = #{fileId} AND public_id = #{chunkPublicId}
  AND status <> 1 AND lock_version = #{lockVersion}
```

After database invalidation, call `ChunkVectorGateway.delete(publicId)` outside the transaction with three bounded attempts at 100ms, 300ms and 900ms. If all attempts fail, return success for the database edit/delete, emit a structured error containing tenant/file/chunk IDs, and rely on ACTIVE post-filtering to suppress the stale candidate; the next reindex begins by deleting the same stable vector ID again. DELETE is physical and requires an explicit confirmation in the UI, not a soft-delete column.

- [ ] **Step 5: Run tests, package, and commit**

Run: `cd server/Spring-AI && mvn -q -Dtest=ChunkCommandServiceTest,ChunkVisibilityTest test && mvn -q -DskipTests package`

```bash
git add server/Spring-AI/src/main/java/com/starsea/ai/chunking \
  server/Spring-AI/src/main/resources/mapper/DocumentChunkMapper.xml \
  server/Spring-AI/src/test/java/com/starsea/ai/chunking/preview/ChunkCommandServiceTest.java \
  server/Spring-AI/src/test/java/com/starsea/ai/chunking/api/ChunkVisibilityTest.java
git commit -m "feat: 支持分块编辑删除与恢复"
```

### Task 9: 文件级向量化与单 Chunk 重新向量化

**Files:**
- Create: `server/Spring-AI/src/main/java/com/starsea/ai/chunking/indexing/SpringAiChunkVectorGateway.java`
- Create: `server/Spring-AI/src/main/java/com/starsea/ai/chunking/indexing/ChunkVectorService.java`
- Create: `server/Spring-AI/src/main/java/com/starsea/ai/chunking/indexing/ChunkVectorWorker.java`
- Modify: `server/Spring-AI/src/main/java/com/starsea/ai/chunking/api/ChunkingApiModels.java`
- Modify: `server/Spring-AI/src/main/java/com/starsea/ai/chunking/api/ChunkingController.java`
- Modify: `server/Spring-AI/src/main/java/com/starsea/ai/service/RagService.java`
- Modify: `server/Spring-AI/src/main/java/com/starsea/ai/service/impl/PgVectorRagServiceImpl.java`
- Test: `server/Spring-AI/src/test/java/com/starsea/ai/chunking/indexing/ChunkVectorServiceTest.java`
- Test: `server/Spring-AI/src/test/java/com/starsea/ai/chunking/indexing/SpringAiChunkVectorGatewayTest.java`

**Interfaces:**
- Produces: confirm POST and single reindex POST; stable vector ID equals `document_chunk.public_id`.
- Consumes: context enhancer, index builder, VectorStore, state and Chunk mappers.

- [ ] **Step 1: Write failing vector document tests**

Assert Spring AI `Document` uses `new Document(publicId.toString(), indexContent, metadata)` and metadata contains only simple values: `tenantId`, `knowledgeId`, `fileId`, `documentPublicId`, `documentChunkId`, `chunkIndex`, `fileType`, and JSON-stringified `sectionPath`.

- [ ] **Step 2: Write failing file/single indexing state tests**

Confirm request is:

```json
{"overlapEnabled": true, "overlapTokens": 40, "lockVersion": 3}
```

Expose it as `POST /confirm`; expose single Chunk recomputation as `POST /chunks/{chunkPublicId}/reindex`.

Assert `CHUNKED/ADJUSTING → CONFIRMED → VECTORIZING`, all target chunks `DRAFT → INDEXING → ACTIVE`, and final file `COMPLETED`. On batch failure, delete every intended ID in the current batch, restore affected chunks to DRAFT, retain edited content, and set file FAILED. Single reindex follows `DRAFT/ACTIVE → INDEXING → ACTIVE`; INDEXING rejects edit/delete/reindex with 409.

- [ ] **Step 3: Run focused tests and confirm failure**

Run: `cd server/Spring-AI && mvn -q -Dtest=ChunkVectorServiceTest,SpringAiChunkVectorGatewayTest test`

- [ ] **Step 4: Implement three-phase external write**

Use separate short transactions:

1. Save `context_policy`, mark DB rows INDEXING and file VECTORIZING.
2. Outside any transaction, enrich current rows and call `vectorStore.delete(ids)` then `vectorStore.add(documents)`.
3. On success, save exact `overlap_content`, `overlap_source_chunk_id`, `overlap_token_count`, `index_content`, ACTIVE status and COMPLETED file state.

Before step 1, recompute the physical source SHA-256 and reject confirmation when it differs from `file_processing.source_hash`. Never call Markdown parser/planner during confirm or reindex. Recheck final BGE token count immediately before `vectorStore.add`. A FAILED file may retry preview only when `failed_from_state=CHUNKING`, or retry vectorization only when `failed_from_state=VECTORIZING`; other retry combinations return 409.

- [ ] **Step 5: Remove the direct file vectorization path**

Delete `RagService.vectorize(File,Long)` and the old `PgVectorRagServiceImpl.handle(String,Long,Long,UUID,String)` reader/splitter pipeline. Keep retrieval methods until Task 10. This prevents Markdown from bypassing preview.

- [ ] **Step 6: Run tests, package, and commit**

Run: `cd server/Spring-AI && mvn -q -Dtest=ChunkVectorServiceTest,SpringAiChunkVectorGatewayTest test && mvn -q -DskipTests package`

```bash
git add server/Spring-AI/src/main/java/com/starsea/ai/chunking/indexing \
  server/Spring-AI/src/main/java/com/starsea/ai/chunking/api \
  server/Spring-AI/src/main/java/com/starsea/ai/service/RagService.java \
  server/Spring-AI/src/main/java/com/starsea/ai/service/impl/PgVectorRagServiceImpl.java \
  server/Spring-AI/src/test/java/com/starsea/ai/chunking/indexing/ChunkVectorServiceTest.java \
  server/Spring-AI/src/test/java/com/starsea/ai/chunking/indexing/SpringAiChunkVectorGatewayTest.java
git commit -m "feat: 接入分块向量化与单块重算"
```

### Task 10: ACTIVE 后置校验、检索上下文与来源信息

**Files:**
- Modify: `server/Spring-AI/src/main/java/com/starsea/ai/service/RagService.java`
- Modify: `server/Spring-AI/src/main/java/com/starsea/ai/service/impl/PgVectorRagServiceImpl.java`
- Modify: `server/Spring-AI/src/main/java/com/starsea/ai/openapi/retrieval/RetrievedChunk.java`
- Modify: `server/Spring-AI/src/main/java/com/starsea/ai/openapi/retrieval/ExternalRetrievalController.java`
- Modify: `server/Spring-AI/src/main/java/com/starsea/ai/controller/AiChatController.java`
- Test: `server/Spring-AI/src/test/java/com/starsea/ai/service/impl/PgVectorRagServiceImplTest.java`
- Test: `server/Spring-AI/src/test/java/com/starsea/ai/openapi/retrieval/ExternalRetrievalControllerTest.java`

**Interfaces:**
- Produces: retrieval returns database `index_content` only for ACTIVE rows plus title path and source locator.
- Consumes: vector candidates and `DocumentChunkMapper.findActiveByPublicIds(long tenantId,Set<Long> knowledgeIds,List<UUID> publicIds)`.

- [ ] **Step 1: Write failing stale-vector and ordering tests**

VectorStore search must request `min(topK * 3, 100)`. Given candidates A/B/C where B is DRAFT and C is missing, only ACTIVE A remains. The returned content must be A's database `index_content`, not candidate text. Preserve vector similarity order after filtering and then truncate to requested topK.

- [ ] **Step 2: Write failing source response tests**

Extend `RetrievedChunk` with `List<String> sectionPath` and `Map<String,Object> sourceLocator`. Assert external metadata includes `document_id`, `chunk_id`, `file_type`, `chunk_index`, `section_path`, `start_line`, and `end_line`, without exposing internal numeric database IDs.

- [ ] **Step 3: Run focused tests and confirm failure**

Run: `cd server/Spring-AI && mvn -q -Dtest=PgVectorRagServiceImplTest,ExternalRetrievalControllerTest test`

- [ ] **Step 4: Implement DB post-filtering and remove legacy search methods**

Keep one public `RagService.retrieve(RetrievalQuery)` path. Update `AiChatController` to consume retrieved content rather than `searchByFile`. Remove `search` and `searchByFile` after all callers compile. Continue filtering enabled files and tenant/knowledge scopes before vector search, then perform the ACTIVE database check after search.

- [ ] **Step 5: Run retrieval tests, full backend tests, and commit**

Run: `cd server/Spring-AI && mvn -q -Dtest=PgVectorRagServiceImplTest,ExternalRetrievalControllerTest test && mvn -q test && mvn -q -DskipTests package`

```bash
git add server/Spring-AI/src/main/java/com/starsea/ai/service \
  server/Spring-AI/src/main/java/com/starsea/ai/openapi/retrieval \
  server/Spring-AI/src/main/java/com/starsea/ai/controller/AiChatController.java \
  server/Spring-AI/src/test/java/com/starsea/ai/service/impl/PgVectorRagServiceImplTest.java \
  server/Spring-AI/src/test/java/com/starsea/ai/openapi/retrieval/ExternalRetrievalControllerTest.java
git commit -m "feat: 保证分块检索与来源一致性"
```

### Task 11: 前端策略目录、API 客户端与工作区路由

**Files:**
- Create: `web/src/api/chunking.js`
- Create: `web/src/features/chunking/strategyCatalog.js`
- Create: `web/src/features/chunking/__tests__/strategyCatalog.test.js`
- Create: `web/src/test/setup.js`
- Modify: `web/vite.config.js`
- Modify: `web/src/router/index.js`
- Modify: `web/src/views/KnowledgeDetail.vue`
- Test: `web/src/views/__tests__/KnowledgeDetail.test.js`

**Interfaces:**
- Produces: `/knowledge/:knowledgeId/files/:fileId/chunks` route and normalized API methods.
- Consumes: backend API from Tasks 6, 8 and 9.

- [ ] **Step 1: Write failing catalog merge tests**

```js
expect(mergeStrategies('md', backendMarkdown)).toEqual([
  expect.objectContaining({ code: 'GENERAL', disabled: true }),
  expect.objectContaining({ code: 'PARENT_CHILD', disabled: true }),
  expect.objectContaining({ code: 'MARKDOWN_OPTIMIZED', disabled: false }),
])
expect(mergeStrategies('pdf', [])).toHaveLength(2)
```

- [ ] **Step 2: Write failing KnowledgeDetail navigation tests**

For MD files, the row action navigates to the workbench instead of POSTing `/knowledge/file`. Label states as `待分块/分块中/待调整/向量化中/已完成/失败`; upload success receives the returned `fileId`, refreshes the list, and opens the workbench for MD.

- [ ] **Step 3: Run tests and confirm failure**

Run: `cd web && npm test -- strategyCatalog.test.js KnowledgeDetail.test.js`

Before adding component tests, configure Vitest in `vite.config.js` with `environment: 'happy-dom'`, `globals: true`, and `setupFiles: ['./src/test/setup.js']`. The setup file stubs `ResizeObserver`, imports `afterEach` from Vitest, and resets `document.body.innerHTML = ''` after every test.

- [ ] **Step 4: Implement the local-only placeholder catalog**

```js
export const placeholderStrategies = Object.freeze([
  { code: 'GENERAL', title: '通用', scope: 'GLOBAL', disabled: true, reason: '暂未开放' },
  { code: 'PARENT_CHILD', title: '父子分块', scope: 'GLOBAL', disabled: true, reason: '暂未开放' },
])

export function mergeStrategies(fileType, backendStrategies) {
  const normalized = String(fileType || '').toLowerCase()
  return [...placeholderStrategies, ...backendStrategies
    .filter(item => item.supportedFileTypes?.includes(normalized))
    .map(item => ({ ...item, disabled: false }))]
}
```

The frontend never sends `GENERAL/PARENT_CHILD` to the backend.

- [ ] **Step 5: Implement API functions and route**

Export `getStrategies`, `createPreview`, `getProcessing`, `getChunks`, `updateChunk`, `deleteChunk`, `confirmVectorization`, and `reindexChunk` from `chunking.js`. All functions use `http`/`apiUrl`, preserve HTTP 409/422 status for page-level handling, and do not manufacture Overlap fields.

- [ ] **Step 6: Run tests, build, and commit**

Run: `cd web && npm test -- strategyCatalog.test.js KnowledgeDetail.test.js && npm run build`

```bash
git add web/src/api/chunking.js web/src/features/chunking \
  web/src/test/setup.js web/vite.config.js web/src/router/index.js web/src/views/KnowledgeDetail.vue \
  web/src/views/__tests__/KnowledgeDetail.test.js
git commit -m "feat: 接入分块工作区路由与策略目录"
```

### Task 12: 双栏分块设置与人工预览交互

**Files:**
- Create: `web/src/views/ChunkingWorkspace.vue`
- Create: `web/src/components/chunking/ChunkStrategyPanel.vue`
- Create: `web/src/components/chunking/MarkdownStrategyConfig.vue`
- Create: `web/src/components/chunking/ChunkPreviewPanel.vue`
- Create: `web/src/components/chunking/ChunkCard.vue`
- Create: `web/src/components/chunking/ContextConfirmDialog.vue`
- Test: `web/src/views/__tests__/ChunkingWorkspace.test.js`
- Test: `web/src/components/chunking/__tests__/ChunkCard.test.js`
- Test: `web/src/components/chunking/__tests__/MarkdownStrategyConfig.test.js`

**Interfaces:**
- Produces: approved prototype behavior with responsive two-column layout.
- Consumes: Task 11 API and catalog.

- [ ] **Step 1: Write failing Token configuration tests**

Assert defaults `100/400/512`, inline invalid message for `400/100/512` and `100/400/513`, disabled preview submission while invalid, and emitted config only when valid. Verify no component text contains `Overlap` inside the MD strategy card.

- [ ] **Step 2: Write failing Chunk card tests**

Assert title path is rendered as readonly text, body becomes editable only after the edit action, a 650ms debounce sends `content + lockVersion`, save status moves `保存中 → 已保存`, HTTP 409 shows conflict and reload action, and delete emits only after Element Plus confirmation. Ensure rendered props cannot reveal `overlapContent/indexContent`.

- [ ] **Step 3: Write failing workspace flow tests**

Cover initial capability load, CHUNKING/VECTORIZING polling, DRAFT restoration after route revisit, preview regeneration warning when edited chunks exist, final confirmation dialog with disabled Overlap default and 40 Token input when enabled, and a DRAFT-after-edit reindex action for completed files.

- [ ] **Step 4: Run component tests and confirm failure**

Run: `cd web && npm test -- MarkdownStrategyConfig.test.js ChunkCard.test.js ChunkingWorkspace.test.js`

- [ ] **Step 5: Implement the approved visual hierarchy**

Use the prototype proportions: desktop settings `40%`, preview `60%`; under 850px stack vertically. Keep “通用/父子分块” disabled with `aria-disabled=true`; select MD optimized by default; show three number inputs with the persistent hint `最小 ≤ 推荐 ≤ 最大 ≤ 512`.

Preview cards show `CHUNK nn`, readonly title path, editable original body, body Token count, save state, edit and delete. Do not render Overlap in preview. Use the separate confirmation dialog for the file-wide Overlap setting.

- [ ] **Step 6: Implement polling and error behavior**

Poll `getProcessing` every 1000ms only while state is CHUNKING or VECTORIZING; stop the timer on route leave. On CHUNKED/ADJUSTING load chunks once. On FAILED show `lastError` and enable only the retry that matches `failedFromState`. Disable edit/delete during INDEXING and file submission during CHUNKING/VECTORIZING.

- [ ] **Step 7: Run frontend tests and build**

Run: `cd web && npm test && npm run build`

- [ ] **Step 8: Commit**

```bash
git add web/src/views/ChunkingWorkspace.vue web/src/components/chunking \
  web/src/views/__tests__/ChunkingWorkspace.test.js \
  web/src/components/chunking/__tests__/ChunkCard.test.js \
  web/src/components/chunking/__tests__/MarkdownStrategyConfig.test.js
git commit -m "feat: 实现 Markdown 分块预览工作区"
```

### Task 13: 端到端回归、边界清理与交付验证

**Files:**
- Modify: `docs/superpowers/specs/2026-08-31-markdown-adaptive-chunking-design.md` only if implementation changed an already-approved class or field name.
- Create: `server/Spring-AI/src/test/java/com/starsea/ai/chunking/MarkdownChunkingWorkflowTest.java`
- Create: `web/src/views/__tests__/ChunkingWorkflow.test.js`

**Interfaces:**
- Produces: one executable regression path from upload to ACTIVE retrieval.
- Consumes: all prior tasks.

- [ ] **Step 1: Add a backend workflow test with fake vector storage**

The test must run this sequence in one tenant: upload `科大百事通.md` → create preview → edit one body → delete one Chunk → confirm with Overlap enabled → verify all remaining chunks ACTIVE → retrieve a matching candidate → assert returned content equals saved `index_content` and source lines point to the original Markdown range.

- [ ] **Step 2: Add a frontend workflow test**

Mock backend responses for the same state sequence and assert the user can leave/re-enter ADJUSTING, see edited content, confirm Overlap without seeing it in preview, and reach the completed state.

- [ ] **Step 3: Run the complete verification matrix**

```bash
cd server/Spring-AI
mvn -q test
mvn -q -DskipTests package
cd ../../web
npm test
npm run build
cd ..
git diff --check
git status --short
```

Expected: all commands exit 0; status contains no generated build output and still preserves unrelated user-owned changes without staging them.

- [ ] **Step 4: Perform the requirement scan**

```bash
rg -n 'TokenTextSplitter|MarkdownDocumentReader|embedding_status' server/Spring-AI/src/main
rg -n 'GENERAL|PARENT_CHILD' server/Spring-AI/src/main/java/com/starsea/ai/chunking
rg -ni 'overlap' server/Spring-AI/src/main/java/com/starsea/ai/chunking/markdown
```

Expected: no old direct Markdown splitter or embedding status; no backend fake strategies; no Overlap in Markdown parser/planner. Legitimate PDF/Tika dependencies may remain in `pom.xml` for future work but must not be reachable from the current chunking workflow.

- [ ] **Step 5: Commit workflow tests or final name alignment**

```bash
git add server/Spring-AI/src/test/java/com/starsea/ai/chunking/MarkdownChunkingWorkflowTest.java \
  web/src/views/__tests__/ChunkingWorkflow.test.js
git commit -m "test: 覆盖 Markdown 分块完整流程"
```

- [ ] **Step 6: Inspect final commit scope**

Run: `git status --short && git log --oneline --max-count=15`

Expected: the feature is split into coherent commits; no local sample edits, Agent files, caches, build output, secrets, or unrelated untracked tests are included.
