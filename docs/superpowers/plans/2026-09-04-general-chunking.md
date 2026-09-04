# 通用分块 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在保持现有 Markdown 优化分块兼容的前提下，为所有已注册、能力探测通过且可成功抽取文本的文件提供快速、确定性的 `GENERAL` 通用分块，并完成配置、预览、编辑、字符 overlap、向量化和知识库入口的前后端闭环。

**Architecture:** 预览入口先把策略 JSON 解析成强类型配置，再由 `ChunkInputProviderRegistry` 按策略选择输入提供器。`GENERAL` 通过抽取器 registry 和持久化抽取缓存得到统一文本，经归一化、分隔符扫描、清洗和字符/token 双重边界规划生成正文唯一归属的草稿；Markdown 继续复用现有 parser/planner。运行时策略解析器统一驱动编辑校验、逐块 overlap、确认和 reindex，最终索引文本在任何状态或向量副作用之前完成校验。

**Tech Stack:** Java 17、Spring Boot 3.3.10、Spring AI 1.0.0-M6、Apache Tika/PDF reader、MyBatis-Plus 3.5.6、PostgreSQL/Flyway、DJL HuggingFace Tokenizers 0.36.0、Vue 3、Element Plus、Vitest。

**Spec:** `docs/superpowers/specs/2026-09-04-general-chunking-design.md`

## Global Constraints

- 当前仓库禁止创建 Git worktree；所有实现继续使用现有 `codex/general-chunking-design` 分支。
- 所有生产代码遵循 TDD：先添加一个能证明缺失行为的测试并观察失败，再写实现并观察通过。
- `GENERAL` 只做确定性文本抽取、清洗和边界规划，不调用 LLM、embedding 或 reranker 决定边界。
- 对外支持格式固定为 TXT、MD/Markdown、CSV、JSON、LOG、HTML、PDF、DOC/DOCX、XLS/XLSX、PPT/PPTX、RTF、EPUB；实际可用性仍以注册抽取器能力探测和本次抽取成功为准。
- 正文长度使用 Unicode code point；最大字符数默认 500、范围 64–4000；最大索引 token 固定不超过 512。
- 分隔符 API 和快照保存实际字符。前端只在编辑框显示 `\n`、`\t`、`\\` 等可读转义，提交前恰好解码一次，恢复时恰好编码一次。
- 通用 overlap 默认开启且为 40 字符，范围 0–1000；0 规范化为关闭。界面显示 `round(maxCharacters * 0.15)` 建议值，但只能由用户点击应用。
- 通用正文边界为 overlap 预留已配置窗口及固定格式字符；实际 overlap 较短时不回填正文，保证相同输入和配置始终得到相同边界。
- planner 只保证正文满足字符限制和 token 限制；持久化后的 enricher 只能缩短 overlap，不能改变或截断正文。
- 正文字符属于唯一一个 Chunk；允许移除的只有显式分隔符、无条件控制字符和用户开启的清洗目标。不得丢失、重排或重复其他正文。
- `policy_snapshot` 只保存可编辑策略配置，`context_policy` 只保存最近一次预览的文件级 overlap 默认值，`execution_metadata` 只保存抽取器/tokenizer 等只读执行信息，逐块 overlap 字段是实际执行真源。
- Markdown 的 token overlap、完整句选择、旧快照、旧请求别名和检索结果必须保持兼容。
- 所有缓存、文件、流程、Chunk 查询和删除都必须校验 tenant、knowledge 和 file 作用域；日志不得输出原文、URL、邮箱或缓存文本。
- 确认与 reindex 必须先构建并校验所有目标 `index_content`，成功后才能切换状态、删除旧向量或写新向量。
- 修改后端生产代码至少运行 `cd server/Spring-AI && mvn -q -DskipTests package`；修改前端生产代码至少运行 `cd web && npm run build`。
- 每个任务验证通过后独立提交，提交信息使用 Conventional Commits 中文格式；提交前运行 `git diff --check` 和 `git status --short`，只暂存任务列出的文件。

---

### Task 1: 强类型策略契约、运行时策略和数据迁移

**Files:**
- Modify: `server/Spring-AI/pom.xml`
- Create: `server/Spring-AI/src/main/resources/db/V16__add_general_chunking_runtime.sql`
- Create: `server/Spring-AI/src/main/java/com/starsea/ai/chunking/model/ChunkStrategyConfig.java`
- Create: `server/Spring-AI/src/main/java/com/starsea/ai/chunking/model/GeneralChunkConfig.java`
- Create: `server/Spring-AI/src/main/java/com/starsea/ai/chunking/model/DelimiterMode.java`
- Create: `server/Spring-AI/src/main/java/com/starsea/ai/chunking/model/ContextConfig.java`
- Create: `server/Spring-AI/src/main/java/com/starsea/ai/chunking/model/OverlapUnit.java`
- Create: `server/Spring-AI/src/main/java/com/starsea/ai/chunking/model/ContextMode.java`
- Create: `server/Spring-AI/src/main/java/com/starsea/ai/chunking/model/ChunkPlanningRequest.java`
- Create: `server/Spring-AI/src/main/java/com/starsea/ai/chunking/model/ChunkPlanningResult.java`
- Create: `server/Spring-AI/src/main/java/com/starsea/ai/chunking/model/ValidatedPreviewConfig.java`
- Create: `server/Spring-AI/src/main/java/com/starsea/ai/chunking/runtime/ChunkRuntimePolicy.java`
- Create: `server/Spring-AI/src/main/java/com/starsea/ai/chunking/runtime/ChunkRuntimePolicyResolver.java`
- Modify: `server/Spring-AI/src/main/java/com/starsea/ai/chunking/model/ChunkPolicy.java`
- Modify: `server/Spring-AI/src/main/java/com/starsea/ai/chunking/model/ContextPolicy.java`
- Modify: `server/Spring-AI/src/main/java/com/starsea/ai/chunking/spi/ChunkPlanningStrategy.java`
- Modify: `server/Spring-AI/src/main/java/com/starsea/ai/chunking/registry/ChunkStrategyDescriptor.java`
- Modify: `server/Spring-AI/src/main/java/com/starsea/ai/chunking/registry/ChunkStrategyRegistry.java`
- Modify: `server/Spring-AI/src/main/java/com/starsea/ai/chunking/markdown/MarkdownChunkPlanningStrategy.java`
- Modify: `server/Spring-AI/src/main/java/com/starsea/ai/domain/FileProcessing.java`
- Modify: `server/Spring-AI/src/main/java/com/starsea/ai/domain/DocumentChunk.java`
- Modify: `server/Spring-AI/src/main/java/com/starsea/ai/mapper/DocumentChunkMapper.java`
- Modify: `server/Spring-AI/src/main/resources/mapper/DocumentChunkMapper.xml`
- Modify: `server/Spring-AI/src/main/resources/mapper/FileProcessingMapper.xml`
- Test: `server/Spring-AI/src/test/java/com/starsea/ai/chunking/model/GeneralChunkConfigTest.java`
- Test: `server/Spring-AI/src/test/java/com/starsea/ai/chunking/registry/ChunkStrategyRegistryTest.java`
- Test: `server/Spring-AI/src/test/java/com/starsea/ai/chunking/runtime/ChunkRuntimePolicyResolverTest.java`
- Test: `server/Spring-AI/src/test/java/com/starsea/ai/chunking/api/V16GeneralChunkingMigrationPostgresIT.java`
- Modify test: `server/Spring-AI/src/test/java/com/starsea/ai/chunking/markdown/MarkdownChunkPlanningStrategyTest.java`

**Interfaces:**
- `ChunkStrategyConfig` is the marker implemented by both `ChunkPolicy` and `GeneralChunkConfig`.
- `ChunkPlanningRequest` contains `ParsedStructure structure`、`ChunkStrategyConfig strategyConfig`、`ContextConfig contextConfig` and `int maxIndexTokens`.
- `ChunkStrategyRegistry.validatePreviewConfig(code, fileType, rawStrategyConfig, rawContextConfig)` synchronously returns `ValidatedPreviewConfig` containing the concrete strategy config, server-derived `ContextConfig` unit/mode and token hard limit. This is the single cross-config validation boundary and must reject invalid delimiter, ranges and `overlap + format >= maxCharacters` before dispatch so the controller returns 422 instead of an asynchronous FAILED state.
- `ChunkPlanningStrategy.plan(ChunkPlanningRequest)` returns `ChunkPlanningResult(drafts, forcedSplitCount, tokenLimitedSplitCount)`; Markdown returns zero for both counters unless its existing boundary metadata proves otherwise.
- `ChunkRuntimePolicyResolver.resolve(strategyCode, policySnapshot, contextPolicy, executionMetadata)` is the only production parser for persisted policy.

- [ ] **Step 1: Add failing configuration, registry, runtime compatibility, and migration tests**

Cover constructor validation, delimiter length/no zero-width regex, defaults `500/40`, Markdown old `maxTokens/tokenizer` fallback, GENERAL character policy, new descriptor context defaults, migration of existing overlap values to `TOKENS`, old character-count backfill, and unit-specific constraints.

- [ ] **Step 2: Run focused tests and confirm they fail for the missing types/schema**

Run: `cd server/Spring-AI && mvn -q -Dtest=GeneralChunkConfigTest,ChunkStrategyRegistryTest,ChunkRuntimePolicyResolverTest,V16GeneralChunkingMigrationPostgresIT,MarkdownChunkPlanningStrategyTest test`

- [ ] **Step 3: Add V16 and update persistence models**

The migration must:

1. Rename `document_chunk.overlap_token_limit` to `overlap_limit`.
2. Add `overlap_unit` defaulting existing rows to `TOKENS`, `overlap_character_count`, and `overlap_reduction_reason`.
3. Replace the old unit-blind constraint with `TOKENS 0..512` / `CHARACTERS 0..1000`, plus “enabled implies limit > 0”.
4. Preserve existing `overlap_token_count` and compute existing `overlap_character_count` from `overlap_content` with PostgreSQL character length.
5. Add JSONB `execution_metadata` and `preview_summary` to `file_processing`.

- [ ] **Step 4: Implement contracts and adapt Markdown without changing its behavior**

Use Jackson conversion only in the registry boundary. The registry must turn conversion, constructor and cross-field failures into a structured invalid-config result; planners never receive raw maps. Keep `ContextPolicy` as a compatibility facade where current Markdown callers still require it, but map it to `ContextConfig(TOKENS, COMPLETE_SENTENCE)` centrally.

This task must keep the branch compiling while introducing the new contract: retain a deprecated two-argument planner bridge until Task 4 moves the preview worker, retain overloaded descriptor constructors used by existing tests, and expose temporary deprecated `get/setOverlapTokenLimit` entity accessors delegating to `overlapLimit` until Task 5 migrates all runtime callers. Declare the locked RE2/J dependency here because `GeneralChunkConfig` performs server-side REGEX validation.

- [ ] **Step 5: Run focused tests and backend package**

Run: `cd server/Spring-AI && mvn -q -Dtest=GeneralChunkConfigTest,ChunkStrategyRegistryTest,ChunkRuntimePolicyResolverTest,V16GeneralChunkingMigrationPostgresIT,MarkdownChunkPlanningStrategyTest test && mvn -q -DskipTests package`

- [ ] **Step 6: Commit**

Commit: `feat: 建立通用分块策略契约`

---

### Task 2: 文本抽取 registry、能力探测和持久化缓存

**Files:**
- Modify: `server/Spring-AI/pom.xml`
- Modify: `server/Spring-AI/src/main/resources/application.yml`
- Create: `server/Spring-AI/src/main/resources/db/V17__add_file_text_extraction_cache.sql`
- Create: `server/Spring-AI/src/main/java/com/starsea/ai/domain/FileTextExtraction.java`
- Create: `server/Spring-AI/src/main/java/com/starsea/ai/mapper/FileTextExtractionMapper.java`
- Create: `server/Spring-AI/src/main/resources/mapper/FileTextExtractionMapper.xml`
- Create: `server/Spring-AI/src/main/java/com/starsea/ai/chunking/extraction/ExtractedText.java`
- Create: `server/Spring-AI/src/main/java/com/starsea/ai/chunking/extraction/SourceSpan.java`
- Create: `server/Spring-AI/src/main/java/com/starsea/ai/chunking/extraction/ExtractionCapability.java`
- Create: `server/Spring-AI/src/main/java/com/starsea/ai/chunking/extraction/DocumentTextExtractor.java`
- Create: `server/Spring-AI/src/main/java/com/starsea/ai/chunking/extraction/DocumentTextExtractorRegistry.java`
- Create: `server/Spring-AI/src/main/java/com/starsea/ai/chunking/extraction/PlainTextExtractor.java`
- Create: `server/Spring-AI/src/main/java/com/starsea/ai/chunking/extraction/PdfTextExtractor.java`
- Create: `server/Spring-AI/src/main/java/com/starsea/ai/chunking/extraction/TikaDocumentTextExtractor.java`
- Create: `server/Spring-AI/src/main/java/com/starsea/ai/chunking/extraction/ManagedExtractionCache.java`
- Modify: `server/Spring-AI/src/main/java/com/starsea/ai/service/FileService.java`
- Modify: `server/Spring-AI/src/main/java/com/starsea/ai/service/impl/FileServiceImpl.java`
- Modify: `server/Spring-AI/src/main/java/com/starsea/ai/controller/FileController.java`
- Modify: `server/Spring-AI/src/main/java/com/starsea/ai/chunking/preview/ChunkPreviewService.java`
- Modify: `server/Spring-AI/src/main/java/com/starsea/ai/chunking/preview/ChunkPreviewWorker.java`
- Create: `server/Spring-AI/src/test/java/com/starsea/ai/chunking/extraction/DocumentTextExtractorRegistryTest.java`
- Create: `server/Spring-AI/src/test/java/com/starsea/ai/chunking/extraction/DocumentTextExtractorContractTest.java`
- Create: `server/Spring-AI/src/test/java/com/starsea/ai/chunking/extraction/ManagedExtractionCacheTest.java`
- Create: `server/Spring-AI/src/test/java/com/starsea/ai/chunking/api/V17FileTextExtractionMigrationPostgresIT.java`
- Modify test: `server/Spring-AI/src/test/java/com/starsea/ai/chunking/preview/ChunkPreviewWorkerTest.java`
- Modify test: `server/Spring-AI/src/test/java/com/starsea/ai/service/impl/FileServiceImplTest.java`

**Interfaces:**
- `DocumentTextExtractor.probe(path, suppliedType)` returns deterministic capability and detected media type; `extract` returns `ExtractedText` or a typed extraction failure.
- Registry selection order is explicit priority, rejects duplicate media-type/priority registrations at construction, and never silently falls through after the selected extractor fails.
- Cache key includes tenant, file, source hash, extractor ID and extractor version; managed files live below the configured cache root in a normalized tenant/file directory.

- [ ] **Step 1: Add failing extractor matrix, registry conflict, cache hit/stale and empty-binary tests**

Generate minimal test fixtures in a temporary directory for every public format. Assert plain Markdown stays plain text for GENERAL, PDF uses the PDF adapter, office/HTML/RTF/EPUB use Tika, MIME/content detection outranks a misleading extension, encrypted/corrupt/no-text inputs return typed failures, and a non-empty binary source is not rejected by UTF-8 blank heuristics.

- [ ] **Step 2: Run focused tests and confirm failure**

Run: `cd server/Spring-AI && mvn -q -Dtest=DocumentTextExtractorRegistryTest,DocumentTextExtractorContractTest,ManagedExtractionCacheTest,V17FileTextExtractionMigrationPostgresIT,ChunkPreviewWorkerTest,FileServiceImplTest test`

- [ ] **Step 3: Implement extraction and cache**

Apply parser limits for source bytes, decompression/nesting, elapsed time and extracted output. Write cache text/source maps to temporary siblings and atomically replace the final files; validate paths remain under the configured root. A metadata row is valid only when source hash, extractor identity/version and both managed files agree. Delete stale managed files without logging content.

`V17` creates tenant-scoped `file_text_extraction` metadata with a unique `(tenant_id,file_id)` key, source hash, extractor ID/version, media type, managed text/source-map paths, character count and cascade delete. It must use the same composite tenant/file foreign-key pattern as `file_processing`.

- [ ] **Step 4: Integrate cache cleanup with file deletion**

Update the existing file deletion service/controller path so database cascade and managed extraction files are removed together as far as local transactional boundaries permit; a managed-file cleanup failure must be surfaced or logged with identifiers only.

- [ ] **Step 5: Run focused tests and backend package**

Run: `cd server/Spring-AI && mvn -q -Dtest=DocumentTextExtractorRegistryTest,DocumentTextExtractorContractTest,ManagedExtractionCacheTest,V17FileTextExtractionMigrationPostgresIT,ChunkPreviewWorkerTest,FileServiceImplTest test && mvn -q -DskipTests package`

- [ ] **Step 6: Commit**

Commit: `feat: 增加通用文本抽取与缓存`

---

### Task 3: 通用清洗、边界扫描和确定性规划器

**Files:**
- Create: `server/Spring-AI/src/main/java/com/starsea/ai/chunking/general/NormalizedText.java`
- Create: `server/Spring-AI/src/main/java/com/starsea/ai/chunking/general/UnicodeText.java`
- Create: `server/Spring-AI/src/main/java/com/starsea/ai/chunking/general/TextNormalizer.java`
- Create: `server/Spring-AI/src/main/java/com/starsea/ai/chunking/general/GeneralBoundaryScanner.java`
- Create: `server/Spring-AI/src/main/java/com/starsea/ai/chunking/general/BoundaryUnit.java`
- Create: `server/Spring-AI/src/main/java/com/starsea/ai/chunking/general/BoundaryKind.java`
- Create: `server/Spring-AI/src/main/java/com/starsea/ai/chunking/general/DelimitedSegment.java`
- Create: `server/Spring-AI/src/main/java/com/starsea/ai/chunking/general/CleanedSegment.java`
- Create: `server/Spring-AI/src/main/java/com/starsea/ai/chunking/general/CleaningResult.java`
- Create: `server/Spring-AI/src/main/java/com/starsea/ai/chunking/general/CleaningStats.java`
- Create: `server/Spring-AI/src/main/java/com/starsea/ai/chunking/general/GeneralTextCleaner.java`
- Create: `server/Spring-AI/src/main/java/com/starsea/ai/chunking/general/GeneralChunkPlanningStrategy.java`
- Create: `server/Spring-AI/src/test/java/com/starsea/ai/chunking/general/TextNormalizerTest.java`
- Create: `server/Spring-AI/src/test/java/com/starsea/ai/chunking/general/GeneralBoundaryScannerTest.java`
- Create: `server/Spring-AI/src/test/java/com/starsea/ai/chunking/general/GeneralTextCleanerTest.java`
- Create: `server/Spring-AI/src/test/java/com/starsea/ai/chunking/general/GeneralChunkPlanningStrategyTest.java`
- Create: `server/Spring-AI/src/test/java/com/starsea/ai/chunking/general/GeneralChunkingPropertyTest.java`
- Modify: `server/Spring-AI/src/main/java/com/starsea/ai/chunking/model/BoundaryReason.java`

**Interfaces:**
- Scanner owns locating and consuming delimiters; cleaner receives `DelimitedSegment`, never re-splits raw text.
- `CleaningResult` exposes cleaned segments plus URL/email/whitespace/control counts and replaced character counts.
- Planner emits `ChunkDraft` with empty section path and `boundaryReason` containing start/end, forced flag and `MODEL_TOKEN_LIMIT` where applicable.

- [ ] **Step 1: Add failing normalization, delimiter, cleanup, Unicode and no-loss tests**

Cover LF normalization, NUL/control removal, literal `\n` versus LF, multi-character delimiter, bounded REGEX compile, regex zero-width rejection, URL/email replacement with one space, whitespace collapse, empty-after-cleaning, Chinese/English sentence fallback, whitespace fallback, emoji/surrogate safety and deterministic output.

- [ ] **Step 2: Add a property-style invariant test**

For generated Unicode inputs and parameter ranges, reconstruct normalized/cleaned text from chunk bodies plus known consumed delimiters. Assert every retained code point appears in order exactly once, each body is `<= maxCharacters`, each index preview is `<= 512` tokens, and repeated runs are byte-identical.

- [ ] **Step 3: Run tests and confirm failure**

Run: `cd server/Spring-AI && mvn -q -Dtest='com.starsea.ai.chunking.general.*Test' test`

- [ ] **Step 4: Implement the O(n) pipeline and planner**

Compile a REGEX delimiter once, enforce max 256 code points and no zero-width matches. Split oversized units using line break → sentence end → whitespace → Unicode code point hard boundary. Join packed short segments with one LF. First-body budget is `maxCharacters`; later-body budget reserves configured overlap and `ChunkIndexContentBuilder`’s `上文：` plus two LF characters. If that leaves no useful body budget, fail config validation instead of looping.

- [ ] **Step 5: Run tests and backend package**

Run: `cd server/Spring-AI && mvn -q -Dtest='com.starsea.ai.chunking.general.*Test' test && mvn -q -DskipTests package`

- [ ] **Step 6: Commit**

Commit: `feat: 实现确定性通用分块算法`

---

### Task 4: 策略感知输入路由、预览 API 和持久化集成

**Files:**
- Create: `server/Spring-AI/src/main/java/com/starsea/ai/chunking/spi/ChunkInputProvider.java`
- Create: `server/Spring-AI/src/main/java/com/starsea/ai/chunking/model/ChunkInputResult.java`
- Create: `server/Spring-AI/src/main/java/com/starsea/ai/chunking/registry/ChunkInputProviderRegistry.java`
- Create: `server/Spring-AI/src/main/java/com/starsea/ai/chunking/general/GeneralTextInputProvider.java`
- Create: `server/Spring-AI/src/main/java/com/starsea/ai/chunking/markdown/MarkdownStructureInputProvider.java`
- Create: `server/Spring-AI/src/main/java/com/starsea/ai/chunking/model/PreviewSummary.java`
- Create: `server/Spring-AI/src/main/java/com/starsea/ai/chunking/model/PreprocessingSummary.java`
- Create: `server/Spring-AI/src/main/java/com/starsea/ai/chunking/api/StrategyCapabilityResponse.java`
- Modify: `server/Spring-AI/src/main/java/com/starsea/ai/chunking/api/ChunkingApiModels.java`
- Modify: `server/Spring-AI/src/main/java/com/starsea/ai/chunking/api/ChunkingException.java`
- Modify: `server/Spring-AI/src/main/java/com/starsea/ai/chunking/api/ChunkingController.java`
- Modify: `server/Spring-AI/src/main/java/com/starsea/ai/chunking/preview/ChunkPreviewService.java`
- Modify: `server/Spring-AI/src/main/java/com/starsea/ai/chunking/preview/ChunkPreviewWorker.java`
- Modify: `server/Spring-AI/src/main/java/com/starsea/ai/chunking/preview/ChunkPreviewPersistenceService.java`
- Modify: `server/Spring-AI/src/main/java/com/starsea/ai/chunking/config/ChunkingConfiguration.java`
- Modify: `server/Spring-AI/src/main/java/com/starsea/ai/domain/vo/FileVo.java`
- Modify: `server/Spring-AI/src/main/java/com/starsea/ai/service/FileService.java`
- Modify: `server/Spring-AI/src/main/java/com/starsea/ai/service/impl/FileServiceImpl.java`
- Modify: `server/Spring-AI/src/main/resources/mapper/FileMapper.xml`
- Test: `server/Spring-AI/src/test/java/com/starsea/ai/chunking/registry/ChunkInputProviderRegistryTest.java`
- Modify test: `server/Spring-AI/src/test/java/com/starsea/ai/chunking/api/ChunkingControllerTest.java`
- Modify test: `server/Spring-AI/src/test/java/com/starsea/ai/chunking/preview/ChunkPreviewWorkerTest.java`
- Modify test: `server/Spring-AI/src/test/java/com/starsea/ai/chunking/processing/FileProcessingServiceTest.java`
- Modify test: `server/Spring-AI/src/test/java/com/starsea/ai/chunking/MarkdownChunkingWorkflowTest.java`
- Modify test: `server/Spring-AI/src/test/java/com/starsea/ai/service/impl/FileServiceImplTest.java`

**Interfaces:**
- Preview request accepts raw `strategyConfig`, separate `contextConfig`, replacement flag and lock version; response errors include stable `code` and `fieldErrors`.
- Strategy response exposes both available and unavailable descriptors with capability reason; Markdown receives GENERAL and MARKDOWN_OPTIMIZED, other extractable formats receive GENERAL.
- Processing response returns only persisted `previewSummary` values.
- Knowledge file-list rows expose `chunkingCapability: { available, reason }`, computed from registered extractors, so the UI never infers support from a filename extension.
- `ChunkInputResult` carries only provider-owned data: `ParsedStructure`, extractor metadata, `PreprocessingSummary` and `delimiterMatched`. `ChunkPlanningResult` carries planner-owned drafts and split counters. The worker merges those with its immutable tokenizer ID/hard limit into final `executionMetadata` and `PreviewSummary`; no layer may invent another layer's measurements.

- [ ] **Step 1: Add failing route, capability, API-validation and persistence tests**

Cover wildcard GENERAL provider versus Markdown-specific provider, exact invalid field errors, Markdown dual strategy, all public non-Markdown formats, actual LF round-trip, first chunk’s persisted enabled/40/CHARACTERS setting with empty actual overlap, limit 0 normalization, source hash/config conflicts, stale worker job and summary counters.

- [ ] **Step 2: Run focused tests and confirm failure**

Run: `cd server/Spring-AI && mvn -q -Dtest=ChunkInputProviderRegistryTest,ChunkingControllerTest,ChunkPreviewWorkerTest,FileProcessingServiceTest,MarkdownChunkingWorkflowTest,FileServiceImplTest test`

- [ ] **Step 3: Implement provider routing and API contracts**

Remove parser/file-extension assumptions from GENERAL. Validate only zero bytes before extraction; determine “no usable text” after extraction and cleaning. Snapshot jobs must carry already validated config/context, strategy code, planner version and token cap. Persist editable config, file context default, execution metadata, summary and per-chunk unit/limit in the same successful preview transaction.

- [ ] **Step 4: Preserve Markdown compatibility**

Wrap the existing Markdown parser in `MarkdownStructureInputProvider`; keep its output and token planning unchanged. Continue accepting the legacy Markdown request shape where `contextConfig` is absent and keep its default overlap disabled/40 TOKENS.

- [ ] **Step 5: Run focused tests and backend package**

Run: `cd server/Spring-AI && mvn -q -Dtest=ChunkInputProviderRegistryTest,ChunkingControllerTest,ChunkPreviewWorkerTest,FileProcessingServiceTest,MarkdownChunkingWorkflowTest,FileServiceImplTest test && mvn -q -DskipTests package`

- [ ] **Step 6: Commit**

Commit: `feat: 接入通用分块预览流程`

---

### Task 5: 字符 overlap、编辑联动和安全向量化

**Files:**
- Create: `server/Spring-AI/src/main/java/com/starsea/ai/chunking/context/CharacterTailContextEnricher.java`
- Create: `server/Spring-AI/src/main/java/com/starsea/ai/chunking/context/StrategyAwareChunkContextEnricher.java`
- Create: `server/Spring-AI/src/main/java/com/starsea/ai/chunking/context/OverlapReductionReason.java`
- Modify: `server/Spring-AI/src/main/java/com/starsea/ai/chunking/spi/ChunkContextEnricher.java`
- Modify: `server/Spring-AI/src/main/java/com/starsea/ai/chunking/context/DefaultChunkContextEnricher.java`
- Modify: `server/Spring-AI/src/main/java/com/starsea/ai/chunking/context/ChunkIndexContentBuilder.java`
- Modify: `server/Spring-AI/src/main/java/com/starsea/ai/chunking/model/EnrichedChunk.java`
- Modify: `server/Spring-AI/src/main/java/com/starsea/ai/chunking/preview/ChunkCommandService.java`
- Modify: `server/Spring-AI/src/main/java/com/starsea/ai/chunking/preview/ChunkPreviewWorker.java`
- Modify: `server/Spring-AI/src/main/java/com/starsea/ai/chunking/preview/ChunkPreviewPersistenceService.java`
- Modify: `server/Spring-AI/src/main/java/com/starsea/ai/chunking/indexing/ChunkVectorService.java`
- Modify: `server/Spring-AI/src/main/java/com/starsea/ai/chunking/indexing/ChunkVectorWorker.java`
- Modify: `server/Spring-AI/src/main/java/com/starsea/ai/chunking/api/ChunkingApiModels.java`
- Modify: `server/Spring-AI/src/main/java/com/starsea/ai/chunking/processing/ChunkPipelineRecovery.java`
- Modify: `server/Spring-AI/src/main/resources/mapper/DocumentChunkMapper.xml`
- Test: `server/Spring-AI/src/test/java/com/starsea/ai/chunking/context/CharacterTailContextEnricherTest.java`
- Modify test: `server/Spring-AI/src/test/java/com/starsea/ai/chunking/context/ChunkContextEnricherTest.java`
- Modify test: `server/Spring-AI/src/test/java/com/starsea/ai/chunking/preview/ChunkCommandServiceTest.java`
- Modify test: `server/Spring-AI/src/test/java/com/starsea/ai/chunking/indexing/ChunkVectorServiceTest.java`
- Create test: `server/Spring-AI/src/test/java/com/starsea/ai/chunking/indexing/ChunkVectorWorkerOrderingTest.java`
- Modify test: `server/Spring-AI/src/test/java/com/starsea/ai/chunking/api/PerChunkOverlapContractTest.java`
- Modify test: `server/Spring-AI/src/test/java/com/starsea/ai/chunking/api/ChunkVisibilityTest.java`
- Modify test: `server/Spring-AI/src/test/java/com/starsea/ai/chunking/preview/ChunkPreviewWorkerTest.java`
- Modify test: `server/Spring-AI/src/test/java/com/starsea/ai/chunking/processing/ChunkPipelineRecoveryTest.java`

**Interfaces:**
- `StrategyAwareChunkContextEnricher` selects exactly one mode from persisted runtime policy: GENERAL `CHARACTER_TAIL`, Markdown `COMPLETE_SENTENCE`.
- `ChunkResponse` exposes length unit/body/index lengths, configured limit, actual token/character counts, reduction reason and boundary reason; overlap text remains read-only.
- Edit request accepts new `overlapLimit` plus `overlapUnit`; legacy `overlapTokenLimit` is a one-release alias only and conflicts are rejected.

- [ ] **Step 1: Add failing character-tail and final-budget tests**

Cover first chunk `FIRST_CHUNK`, independent previous-body tail, emoji-safe count, configured cap, character-budget reduction, token-budget reduction, no room for label, deleted gap, no chained overlap and Markdown complete-sentence compatibility.

- [ ] **Step 2: Add failing edit/confirm/reindex ordering tests**

Assert GENERAL edits over character or token limit return 422 without saving; a valid edit recomputes current and next overlap in one transaction and marks both for reindex when needed; confirm validates every final index before state transition; single reindex builds/validates before deleting the old vector; async jobs reject file/chunk/policy version drift.

Also assert successful GENERAL preview persistence enriches all newly inserted chunks in position order and writes the same actual `overlap_content`, counts, reduction reason and `index_content` later used by confirmation. Recovery SQL must clear `overlap_character_count`, `overlap_reduction_reason` and `index_content` together with existing overlap token fields.

- [ ] **Step 3: Run focused tests and confirm failure**

Run: `cd server/Spring-AI && mvn -q -Dtest=CharacterTailContextEnricherTest,ChunkContextEnricherTest,ChunkCommandServiceTest,ChunkVectorServiceTest,ChunkVectorWorkerOrderingTest,PerChunkOverlapContractTest,ChunkVisibilityTest,ChunkPreviewWorkerTest,ChunkPipelineRecoveryTest test`

- [ ] **Step 4: Implement strategy-aware enrichment and operation ordering**

Copy at most N code points from the immediately previous independent body. Build final text through the shared formatter, then shrink overlap until both `maxCharacters` and `maxIndexTokens` pass; never modify body. Precompute all final texts before any state/vector mutation and reuse that immutable prepared batch in the worker.

- [ ] **Step 5: Run focused tests, Markdown workflow regression and backend package**

Run: `cd server/Spring-AI && mvn -q -Dtest=CharacterTailContextEnricherTest,ChunkContextEnricherTest,ChunkCommandServiceTest,ChunkVectorServiceTest,ChunkVectorWorkerOrderingTest,PerChunkOverlapContractTest,ChunkVisibilityTest,ChunkPreviewWorkerTest,ChunkPipelineRecoveryTest,MarkdownChunkingWorkflowTest test && mvn -q -DskipTests package`

- [ ] **Step 6: Commit**

Commit: `feat: 支持通用分块字符上下文`

---

### Task 6: 通用分块前端配置、预览信息和文件入口

**Files:**
- Create: `web/src/components/chunking/GeneralStrategyConfig.vue`
- Create: `web/src/components/chunking/__tests__/GeneralStrategyConfig.test.js`
- Create: `web/src/components/chunking/ChunkPreviewSummary.vue`
- Create: `web/src/components/chunking/__tests__/ChunkPreviewSummary.test.js`
- Create: `web/src/features/chunking/delimiterCodec.js`
- Create: `web/src/features/chunking/__tests__/delimiterCodec.test.js`
- Modify: `web/src/api/chunking.js`
- Modify: `web/src/api/__tests__/chunking.test.js`
- Modify: `web/src/features/chunking/strategyCatalog.js`
- Modify: `web/src/features/chunking/normalization.js`
- Modify: `web/src/features/chunking/__tests__/strategyCatalog.test.js`
- Modify: `web/src/components/chunking/ChunkStrategyPanel.vue`
- Modify: `web/src/components/chunking/ChunkPreviewPanel.vue`
- Modify: `web/src/components/chunking/ChunkCard.vue`
- Modify: `web/src/components/chunking/ContextConfirmDialog.vue`
- Modify: `web/src/components/chunking/__tests__/ChunkCard.test.js`
- Modify: `web/src/components/chunking/__tests__/ContextConfirmDialog.test.js`
- Modify: `web/src/views/ChunkingWorkspace.vue`
- Modify: `web/src/views/KnowledgeDetail.vue`
- Modify: `web/src/views/__tests__/ChunkingWorkspace.test.js`
- Modify: `web/src/views/__tests__/ChunkingWorkflow.test.js`
- Modify: `web/src/views/__tests__/KnowledgeDetail.test.js`

- [ ] **Step 1: Add failing form and strategy tests**

Assert descriptor defaults hydrate GENERAL, delimiter escape round-trip is exact for LF/tab/backslash and literal `\\n`, regex validation is displayed, ranges/cross-field budget are enforced, 15% suggestion updates when max changes but does not overwrite 40, and “应用建议” is the only automatic setter。浏览器端只做基础长度、空值和零宽风险提示；RE2/J 语法兼容性以服务端 `fieldErrors` 为最终结果，不能用原生 `RegExp` 冒充 RE2/J。

- [ ] **Step 2: Add failing workflow and file-list tests**

Assert GENERAL is selectable when backend says available; Markdown shows both strategies; switching restores each strategy’s own config; processing snapshot wins over descriptor defaults; stale preview generation is ignored; preview summary and boundary/reduction reasons render; character and token units render correctly; any capability-enabled non-Markdown file opens “分块管理”; unavailable files show the server reason.

- [ ] **Step 3: Run focused frontend tests and confirm failure**

Run: `cd web && npm test -- --run src/api/__tests__/chunking.test.js src/components/chunking/__tests__ src/features/chunking/__tests__/strategyCatalog.test.js src/features/chunking/__tests__/delimiterCodec.test.js src/views/__tests__/ChunkingWorkspace.test.js src/views/__tests__/ChunkingWorkflow.test.js src/views/__tests__/KnowledgeDetail.test.js`

- [ ] **Step 4: Implement the explicit GENERAL UI and request normalization**

Do not add a generic schema form. Keep separate per-strategy form state, merge `policySnapshot + contextPolicy` on restore, derive availability solely from the backend capability response, and send actual delimiter characters. Chunk cards allow editing only the body and configured overlap; actual overlap and summary fields remain read-only.

- [ ] **Step 5: Run chunking frontend tests and build**

Run: `cd web && npm test -- --run src/api/__tests__/chunking.test.js src/components/chunking/__tests__ src/features/chunking/__tests__/strategyCatalog.test.js src/features/chunking/__tests__/delimiterCodec.test.js src/views/__tests__/ChunkingWorkspace.test.js src/views/__tests__/ChunkingWorkflow.test.js src/views/__tests__/KnowledgeDetail.test.js && npm run build`

- [ ] **Step 6: Commit**

Commit: `feat: 完成通用分块交互`

---

### Task 7: 全格式工作流、兼容性和完成验证

**Files:**
- Create: `server/Spring-AI/src/test/java/com/starsea/ai/chunking/GeneralChunkingWorkflowTest.java`
- Create: `server/Spring-AI/src/test/java/com/starsea/ai/chunking/GeneralChunkingFormatMatrixTest.java`
- Create: `server/Spring-AI/src/test/java/com/starsea/ai/chunking/GeneralChunkingPerformanceTest.java`
- Modify: `server/Spring-AI/src/test/java/com/starsea/ai/chunking/MarkdownChunkingWorkflowTest.java`
- Modify: `web/src/views/__tests__/ChunkingWorkflow.test.js`
- Modify: `docs/superpowers/specs/2026-09-04-general-chunking-design.md` only if implementation reveals a verified naming correction; do not weaken behavior.

- [ ] **Step 1: Add the full workflow acceptance tests**

Exercise upload metadata → capability → GENERAL preview → summary → edit → current/next overlap recompute → confirm → vector write → retrieval → re-edit/reindex for representative plain text, PDF and office inputs. Parameterize extraction over every public format and assert deterministic bodies/source locators. Keep an unchanged Markdown end-to-end assertion in the same verification set.

- [ ] **Step 2: Add bounded benchmark coverage**

Generate 1 MB and 10 MB fixtures for each of TXT, HTML and PDF, assert completion without runaway memory/time using generous CI-safe thresholds, and record stage metrics without asserting a marketing throughput number.

- [ ] **Step 3: Run all relevant backend and frontend tests**

Run: `cd server/Spring-AI && mvn -q test`

Run: `cd server/Spring-AI && mvn -q -Dtest=V16GeneralChunkingMigrationPostgresIT,V17FileTextExtractionMigrationPostgresIT test`

Run: `cd web && npm test -- --run`

- [ ] **Step 4: Run mandatory builds and repository hygiene checks**

Run: `cd server/Spring-AI && mvn -q -DskipTests package`

Run: `cd web && npm run build`

Run: `git diff --check && git status --short`

- [ ] **Step 5: Commit acceptance coverage**

Commit: `test: 验证通用分块完整工作流`

- [ ] **Step 6: Request final whole-branch review**

Review the complete diff from `29ffa35` to HEAD for spec compliance, tenant isolation, data migration safety, deterministic boundaries, async snapshot safety, vector side-effect ordering, Markdown regressions and frontend stale-request behavior. Fix all load-bearing findings, repeat the affected tests, then rerun both mandatory builds.
