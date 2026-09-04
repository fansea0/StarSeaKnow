# 父子分块 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为 Markdown 文档提供“子块向量召回、父块回答上下文”的可配置父子分块前后端闭环。

**Architecture:** 在现有 `document_chunk` 表增加 `SINGLE/PARENT/CHILD` 层级与父引用，通过通用 `ChunkPlan` 让普通 Markdown 和父子策略共用预览流水线。向量化仅选择 SINGLE/CHILD，数据库权威召回查询 self join 父块并按父上下文去重；前端继续使用当前工作区状态机，只新增按策略配置和父子分组展示。

**Tech Stack:** Java 17、Spring Boot 3.3、MyBatis-Plus、Flyway/PostgreSQL、Spring AI VectorStore、Vue 3、Element Plus、Vitest。

**Spec:** `docs/superpowers/specs/2026-09-04-parent-child-chunking-design.md`

## Global Constraints

- 策略码固定为 `PARENT_CHILD`，块类型固定为 `SINGLE/PARENT/CHILD`。
- 历史 `document_chunk` 必须迁移为 SINGLE，现有向量无需重建。
- 仅 SINGLE/CHILD 允许建向量、编辑、删除和单块重建；PARENT 首版只读。
- CHILD 命中后返回 PARENT 内容，但 citation `chunkId` 保留命中的 CHILD public ID。
- 所有 self join 和写操作必须同时约束 tenant、knowledge、file。
- 普通 `MARKDOWN_OPTIMIZED` 的请求、响应、编辑与检索行为不得回退。
- 后端生产代码完成后必须通过 `mvn -q -DskipTests package`，前端生产代码完成后必须通过 `npm run build`。

---

### Task 1: 通用计划模型与 Markdown 父子规划器

**Files:**
- Create: `server/Spring-AI/src/main/java/com/starsea/ai/chunking/model/ChunkType.java`
- Create: `server/Spring-AI/src/main/java/com/starsea/ai/chunking/model/PlannedChunk.java`
- Create: `server/Spring-AI/src/main/java/com/starsea/ai/chunking/model/ChunkPlan.java`
- Create: `server/Spring-AI/src/main/java/com/starsea/ai/chunking/model/ParentChildPolicy.java`
- Create: `server/Spring-AI/src/main/java/com/starsea/ai/chunking/markdown/MarkdownParentChildPlanningStrategy.java`
- Create: `server/Spring-AI/src/test/java/com/starsea/ai/chunking/markdown/MarkdownParentChildPlanningStrategyTest.java`
- Modify: `server/Spring-AI/src/main/java/com/starsea/ai/chunking/spi/ChunkPlanningStrategy.java`
- Modify: `server/Spring-AI/src/main/java/com/starsea/ai/chunking/markdown/MarkdownChunkPlanningStrategy.java`
- Modify: `server/Spring-AI/src/main/java/com/starsea/ai/chunking/registry/ChunkStrategyRegistry.java`
- Modify: `server/Spring-AI/src/main/java/com/starsea/ai/chunking/preview/ChunkPreviewService.java`
- Modify: `server/Spring-AI/src/test/java/com/starsea/ai/chunking/registry/ChunkStrategyRegistryTest.java`

**Interfaces:**
- Produces: `ChunkType { SINGLE(0), PARENT(1), CHILD(2) }` with `code()` and `fromCode(int)`.
- Produces: `PlannedChunk(String key, String parentKey, ChunkType type, int siblingPosition, ChunkDraft draft, boolean overlapEnabled, int overlapTokenLimit)`.
- Produces: `ChunkPlan(List<PlannedChunk> chunks, int indexMaxTokens)` and `ChunkPlan.flat(List<ChunkDraft>, int)`.
- Produces: `ChunkPlanningStrategy.normalizeConfig(Map<String,Object>)` and `planConfigured(ParsedStructure, Map<String,Object>)`.
- Produces: `ChunkStrategyRegistry.descriptors(String fileType)` returning every matching registered descriptor in registration order.

- [ ] **Step 1: Write failing planner and registry tests**

Add tests that express the public behavior before production classes exist:

```java
@Test
void paragraph_mode_groups_children_under_bounded_parents_without_crossing_sections() {
    Map<String, Object> config = Map.of(
            "parentMode", "PARAGRAPH",
            "parentMaxTokens", 300,
            "childMaxTokens", 120,
            "childOverlapTokens", 24);

    ChunkPlan plan = strategy.planConfigured(parsedMarkdownWithTwoSections(), config);

    assertTrue(plan.chunks().stream().anyMatch(c -> c.type() == ChunkType.PARENT));
    assertTrue(plan.chunks().stream().anyMatch(c -> c.type() == ChunkType.CHILD));
    assertEquals(120, plan.indexMaxTokens());
    assertEveryChildReferencesEarlierParent(plan);
    assertParentsDoNotMixSectionPaths(plan);
}

@Test
void full_document_mode_has_one_parent_and_all_children_reference_it() {
    ChunkPlan plan = strategy.planConfigured(parsedMarkdownWithTwoSections(), Map.of(
            "parentMode", "FULL_DOCUMENT",
            "parentMaxTokens", 1024,
            "childMaxTokens", 128,
            "childOverlapTokens", 0));

    List<PlannedChunk> parents = plan.chunks().stream()
            .filter(c -> c.type() == ChunkType.PARENT).toList();
    assertEquals(1, parents.size());
    assertTrue(plan.chunks().stream().filter(c -> c.type() == ChunkType.CHILD)
            .allMatch(c -> parents.get(0).key().equals(c.parentKey())));
}
```

Cover invalid mode, parent 127/4097, child 31/513, overlap -1/129 or `>= childMaxTokens`, paragraph parent smaller than child, stable sibling positions, full-document heading preservation, and descriptor fields/defaults. Add a registry assertion that both Markdown descriptors are returned for `md`.

- [ ] **Step 2: Run tests and verify RED**

Run:

```bash
cd server/Spring-AI
mvn -q -Dtest=MarkdownParentChildPlanningStrategyTest,ChunkStrategyRegistryTest test
```

Expected: compilation fails because `ChunkPlan`, `ParentChildPolicy`, `ChunkType` and `MarkdownParentChildPlanningStrategy` do not exist.

- [ ] **Step 3: Implement config normalization and plan types**

Implement strict numeric conversion that accepts Jackson `Number` values only when integral. `ParentChildPolicy.defaults()` must yield paragraph/1024/256/32. Make plan collections immutable and reject blank/duplicate keys, negative sibling positions, CHILD without parent key, and non-CHILD with parent key.

Keep the existing overload `MarkdownChunkPlanningStrategy.plan(ParsedStructure, ChunkPolicy)` for its focused tests. Its configured path must normalize `minTokens/targetTokens/maxTokens`, call the existing overload, and return `ChunkPlan.flat(drafts, maxTokens)`.

- [ ] **Step 4: Implement `PARENT_CHILD` planning**

Use `MarkdownChunkPlanningStrategy` with a derived child policy:

```java
int childTarget = Math.max(1, (int) Math.floor(policy.childMaxTokens() * 0.8));
int childMin = Math.max(1, (int) Math.floor(policy.childMaxTokens() * 0.25));
ChunkPolicy childPolicy = new ChunkPolicy(childMin, childTarget, policy.childMaxTokens());
List<ChunkDraft> children = childPlanner.plan(structure, childPolicy);
```

For paragraph mode, group consecutive children while path and strong-start rules allow and the rendered parent stays within `parentMaxTokens`. For full-document mode, join nonblank parsed block raw text in source order so headings remain visible. Emit each parent before its children; parent overlap is false/40, child overlap uses `childOverlapTokens > 0` and `max(childOverlapTokens, 1)`.

- [ ] **Step 5: Expose all registered descriptors and synchronously normalize requests**

Replace the single hard-coded descriptor lookup in `ChunkPreviewService.strategies` with `strategyRegistry.descriptors(fileType)`. In `startPreview`, resolve the planner and call `normalizeConfig` before state transition; store the normalized map in the worker job so malformed settings return HTTP 422 synchronously.

- [ ] **Step 6: Run focused and existing planner tests**

Run:

```bash
cd server/Spring-AI
mvn -q -Dtest=MarkdownParentChildPlanningStrategyTest,MarkdownChunkPlanningStrategyTest,ChunkStrategyRegistryTest,ChunkingControllerTest test
```

Expected: all selected tests pass.

- [ ] **Step 7: Commit**

```bash
git add server/Spring-AI/src/main/java/com/starsea/ai/chunking server/Spring-AI/src/test/java/com/starsea/ai/chunking
git commit -m "feat: 添加父子分块规划策略"
```

---

### Task 2: 层级迁移、预览持久化与 API 合约

**Files:**
- Create: `server/Spring-AI/src/main/resources/db/V16__add_parent_child_chunks.sql`
- Create: `server/Spring-AI/src/test/java/com/starsea/ai/chunking/api/V16ParentChildChunkMigrationPostgresIT.java`
- Modify: `server/Spring-AI/src/main/java/com/starsea/ai/domain/DocumentChunk.java`
- Modify: `server/Spring-AI/src/main/java/com/starsea/ai/mapper/DocumentChunkMapper.java`
- Modify: `server/Spring-AI/src/main/resources/mapper/DocumentChunkMapper.xml`
- Modify: `server/Spring-AI/src/main/java/com/starsea/ai/chunking/api/ChunkingApiModels.java`
- Modify: `server/Spring-AI/src/main/java/com/starsea/ai/chunking/preview/ChunkPreviewWorker.java`
- Modify: `server/Spring-AI/src/main/java/com/starsea/ai/chunking/preview/ChunkPreviewPersistenceService.java`
- Modify: `server/Spring-AI/src/main/java/com/starsea/ai/chunking/preview/ChunkCommandService.java`
- Modify: `server/Spring-AI/src/test/java/com/starsea/ai/chunking/preview/ChunkPreviewWorkerTest.java`
- Modify: `server/Spring-AI/src/test/java/com/starsea/ai/chunking/api/ChunkingControllerTest.java`
- Modify: `server/Spring-AI/src/test/java/com/starsea/ai/chunking/api/ChunkVisibilityTest.java`

**Interfaces:**
- Consumes: `ChunkPlan` and normalized config from Task 1.
- Produces: `DocumentChunk.chunkType`, `parentChunkId`, `siblingPosition`, transient `parentPublicId` and parent retrieval fields.
- Produces: `ChunkResponse(..., String chunkType, UUID parentPublicId, int siblingPosition)` while retaining the old convenience constructor.

- [ ] **Step 1: Write failing migration and persistence tests**

Add a migration test that applies V7, V8 and V16 to a temporary PostgreSQL schema, inserts a historical row before V16, and asserts:

```java
assertEquals(0, row.getInt("chunk_type"));
assertEquals(row.getInt("position"), row.getInt("sibling_position"));
assertNull(row.getObject("parent_chunk_id"));
```

Assert CHILD without parent fails, SINGLE with parent fails, deleting a parent cascades children, and duplicate `(parent_chunk_id, sibling_position)` CHILD rows fail.

Extend preview tests with one parent/two children and assert insert order, populated child `parentChunkId`, global positions `0,1,2`, sibling positions `0,0,1`, and transaction rollback when the second child insert fails.

- [ ] **Step 2: Run tests and verify RED**

Run:

```bash
cd server/Spring-AI
mvn -q -Dtest=V16ParentChildChunkMigrationPostgresIT,ChunkPreviewWorkerTest,ChunkingControllerTest test
```

Expected: tests fail because V16 and hierarchy fields are absent and the worker only accepts `ChunkPolicy`/flat drafts.

- [ ] **Step 3: Add the backward-compatible migration and mapper fields**

Create V16 with `chunk_type`, self-referencing `parent_chunk_id`, `sibling_position`, a hierarchy check constraint, `idx_document_chunk_parent`, and a partial unique child-sibling index. Update historical sibling positions before adding final constraints.

Update result maps and list SQL. `findByFile` must use:

```sql
SELECT dc.*, parent.public_id AS parent_public_id
FROM document_chunk dc
LEFT JOIN document_chunk parent
  ON parent.id = dc.parent_chunk_id
 AND parent.tenant_id = dc.tenant_id
 AND parent.knowledge_id = dc.knowledge_id
 AND parent.file_id = dc.file_id
WHERE dc.file_id = #{fileId}
  AND dc.tenant_id = #{tenantId}
  AND dc.knowledge_id = #{knowledgeId}
ORDER BY dc.position
```

- [ ] **Step 4: Persist plans atomically**

Change worker jobs to hold immutable normalized config maps. Validate plan keys and vectorizable token limits, rebuild normalized drafts with server-counted body tokens, add `tokenizer` to the persisted policy snapshot, then pass the `ChunkPlan` to persistence.

In persistence, maintain maps from plan key to inserted parent database/public IDs. Reject a child whose parent key is unknown or not PARENT. Assign one global position counter while preserving planned sibling positions and default overlap values.

- [ ] **Step 5: Return hierarchy through the existing API**

Change `PreviewRequest.strategyConfig` to `Map<String,Object>`. Extend `ChunkResponse` and `ChunkCommandService.toResponse` with the enum name, parent public ID and sibling position. Keep all previous JSON fields and constructors used by existing tests.

- [ ] **Step 6: Run focused tests**

Run:

```bash
cd server/Spring-AI
mvn -q -Dtest=V16ParentChildChunkMigrationPostgresIT,ChunkPreviewWorkerTest,ChunkingControllerTest,ChunkVisibilityTest,MarkdownChunkingWorkflowTest test
```

Expected: all selected tests pass; if Docker/PostgreSQL is unavailable, the migration IT may skip using its existing assumption pattern, while unit/contract tests must pass.

- [ ] **Step 7: Commit**

```bash
git add server/Spring-AI/src/main server/Spring-AI/src/test/java/com/starsea/ai/chunking
git commit -m "feat: 持久化父子分块层级"
```

---

### Task 3: 子块编辑约束与父子感知向量生命周期

**Files:**
- Modify: `server/Spring-AI/src/main/java/com/starsea/ai/chunking/preview/ChunkCommandService.java`
- Modify: `server/Spring-AI/src/main/java/com/starsea/ai/mapper/DocumentChunkMapper.java`
- Modify: `server/Spring-AI/src/main/resources/mapper/DocumentChunkMapper.xml`
- Modify: `server/Spring-AI/src/main/java/com/starsea/ai/chunking/indexing/ChunkVectorGateway.java`
- Modify: `server/Spring-AI/src/main/java/com/starsea/ai/chunking/indexing/SpringAiChunkVectorGateway.java`
- Modify: `server/Spring-AI/src/main/java/com/starsea/ai/chunking/indexing/ChunkVectorService.java`
- Modify: `server/Spring-AI/src/main/java/com/starsea/ai/chunking/indexing/ChunkVectorWorker.java`
- Modify: `server/Spring-AI/src/test/java/com/starsea/ai/chunking/preview/ChunkCommandServiceTest.java`
- Modify: `server/Spring-AI/src/test/java/com/starsea/ai/chunking/indexing/ChunkVectorServiceTest.java`
- Modify: `server/Spring-AI/src/test/java/com/starsea/ai/chunking/indexing/SpringAiChunkVectorGatewayTest.java`

**Interfaces:**
- Consumes: persisted `ChunkType` and parent relation.
- Produces: `deleteEmptyParent(parentId, tenantId, knowledgeId, fileId)` mapper command.
- Produces: vector metadata `chunkType` and optional `parentChunkId`.

- [ ] **Step 1: Write failing command and vector tests**

Cover these behaviors:

```java
@Test
void parent_cannot_be_edited_deleted_or_reindexed() {
    assertEquals(422, statusOf(() -> command.edit(KNOWLEDGE, FILE, PARENT_ID, editRequest())));
    assertEquals(422, statusOf(() -> command.delete(KNOWLEDGE, FILE, PARENT_ID, 0)));
    assertEquals(422, statusOf(() -> vectorService.reindex(KNOWLEDGE, FILE, PARENT_ID)));
}

@Test
void batch_vectors_only_children_then_activates_parent_and_children() {
    vectorService.confirm(KNOWLEDGE, FILE, new ConfirmRequest(FILE_LOCK));
    assertEquals(List.of(CHILD_ONE_ID, CHILD_TWO_ID), gateway.addedPublicIds());
    assertAllRowsHaveStatus(ChunkStatus.ACTIVE);
}
```

Also assert parent is excluded from gateway cleanup, batch failure restores parent and children to DRAFT, the context enricher never receives PARENT, single child reindex completes with active parent, CHILD uses `childMaxTokens`, CHILD overlap mutation inconsistent with the strategy snapshot is rejected, last-child deletion removes the empty parent, and confirmation rejects a parent-only file.

- [ ] **Step 2: Run tests and verify RED**

Run:

```bash
cd server/Spring-AI
mvn -q -Dtest=ChunkCommandServiceTest,ChunkVectorServiceTest,SpringAiChunkVectorGatewayTest test
```

Expected: hierarchy cases fail because every row is currently mutable and vectorized.

- [ ] **Step 3: Enforce edit/delete rules**

Resolve `ChunkType` from each locked row. Reject PARENT before calculating budgets or dependents. Read maximum from `childMaxTokens` for CHILD and `maxTokens` for SINGLE. For CHILD, require request overlap settings to equal `childOverlapTokens > 0` and `max(childOverlapTokens, 1)` in the file policy snapshot.

After deleting a CHILD, call fully scoped `deleteEmptyParent`; retain existing after-commit vector cleanup only for the deleted child and changed vectorizable dependent.

- [ ] **Step 4: Split lifecycle rows from vector targets**

During batch preparation, mark all rows INDEXING and create two immutable lists:

```java
List<ChunkSnapshot> allSnapshots = chunks.stream().map(this::markIndexing).toList();
List<ChunkSnapshot> vectorTargets = allSnapshots.stream()
        .filter(ChunkSnapshot::vectorizable)
        .toList();
```

Pass only vectorizable detached chunks to the context enricher. On success, activate vector targets with derived index fields and activate parents with status/lock update only. On failure, delete only vector target IDs but restore every INDEXING snapshot. Single reindex must reject non-vectorizable snapshots before state transitions.

- [ ] **Step 5: Add diagnostic vector metadata**

Extend `VectorDocument` with `chunkType` and nullable `parentChunkPublicId`, retaining an overload for the prior constructor. Write `chunkType` always and `parentChunkId` only for CHILD in `SpringAiChunkVectorGateway`.

- [ ] **Step 6: Run focused and recovery tests**

Run:

```bash
cd server/Spring-AI
mvn -q -Dtest=ChunkCommandServiceTest,ChunkVectorServiceTest,SpringAiChunkVectorGatewayTest,ChunkPipelineRecoveryTest test
```

Expected: all selected tests pass, including compensation paths.

- [ ] **Step 7: Commit**

```bash
git add server/Spring-AI/src/main server/Spring-AI/src/test/java/com/starsea/ai/chunking
git commit -m "feat: 仅对子块建立向量索引"
```

---

### Task 4: 子命中父返回与父上下文去重

**Files:**
- Modify: `server/Spring-AI/src/main/java/com/starsea/ai/domain/DocumentChunk.java`
- Modify: `server/Spring-AI/src/main/resources/mapper/DocumentChunkMapper.xml`
- Modify: `server/Spring-AI/src/main/java/com/starsea/ai/service/impl/PgVectorRagServiceImpl.java`
- Modify: `server/Spring-AI/src/test/java/com/starsea/ai/service/impl/PgVectorRagServiceImplTest.java`
- Modify: `server/Spring-AI/src/test/java/com/starsea/ai/openapi/retrieval/ExternalRetrievalControllerTest.java`
- Modify: `server/Spring-AI/src/test/java/com/starsea/ai/agent/execution/AgentPromptAssemblerTest.java`

**Interfaces:**
- Consumes: CHILD `parent_chunk_id` and active parent rows.
- Produces: transient parent content/path/locator/position/public ID on `DocumentChunk` retrieval results.
- Preserves: `RetrievedChunk.chunkId` remains the matched child ID.

- [ ] **Step 1: Write failing retrieval tests**

Add an authoritative CHILD row with parent fields and assert:

```java
List<RetrievedChunk> result = service.retrieve(new RetrievalQuery("query", Set.of(KNOWLEDGE_ID), 3, 0.2));

assertEquals(parentIndexText, result.get(0).content());
assertEquals(childPublicId, result.get(0).chunkId());
assertEquals(parentPosition, result.get(0).chunkIndex());
assertEquals(parentPath, result.get(0).sectionPath());
```

Return two child candidates for the same parent followed by a child from another parent; assert two contexts are emitted in first-hit score order. Assert missing/inactive/cross-scope parent causes the child row to be dropped. Retain a SINGLE candidate assertion unchanged.

- [ ] **Step 2: Run tests and verify RED**

Run:

```bash
cd server/Spring-AI
mvn -q -Dtest=PgVectorRagServiceImplTest,ExternalRetrievalControllerTest,AgentPromptAssemblerTest test
```

Expected: CHILD results still return child index content and duplicate the same parent.

- [ ] **Step 3: Join authoritative parent fields**

Extend `findActiveByPublicIds` with a fully scoped parent self join and select aliases for parent public ID, content, section path, source locator, position and status. Require active parent in SQL for CHILD while leaving SINGLE behavior unchanged.

- [ ] **Step 4: Map and deduplicate effective contexts**

Build parent content with `ChunkIndexContentBuilder.preview(parentSectionPath, parentContent)`. Use `parentPublicId` as the emitted key for CHILD and child public ID for SINGLE. Preserve candidate score and child citation ID, but use parent position/path/locator in the returned record.

- [ ] **Step 5: Run retrieval and public-contract tests**

Run:

```bash
cd server/Spring-AI
mvn -q -Dtest=PgVectorRagServiceImplTest,ExternalRetrievalControllerTest,AgentPromptAssemblerTest test
```

Expected: all selected tests pass and existing external response fields remain compatible.

- [ ] **Step 6: Commit**

```bash
git add server/Spring-AI/src/main server/Spring-AI/src/test/java/com/starsea/ai/service server/Spring-AI/src/test/java/com/starsea/ai/openapi server/Spring-AI/src/test/java/com/starsea/ai/agent
git commit -m "feat: 子块召回返回父块上下文"
```

---

### Task 5: 前端策略开放与父子配置

**Files:**
- Create: `web/src/features/chunking/strategyConfig.js`
- Create: `web/src/features/chunking/__tests__/strategyConfig.test.js`
- Create: `web/src/components/chunking/ParentChildStrategyConfig.vue`
- Create: `web/src/components/chunking/__tests__/ParentChildStrategyConfig.test.js`
- Modify: `web/src/api/chunking.js`
- Modify: `web/src/api/__tests__/chunking.test.js`
- Modify: `web/src/features/chunking/strategyCatalog.js`
- Modify: `web/src/features/chunking/__tests__/strategyCatalog.test.js`
- Modify: `web/src/components/chunking/ChunkStrategyPanel.vue`
- Modify: `web/src/views/ChunkingWorkspace.vue`
- Modify: `web/src/views/__tests__/ChunkingWorkspace.test.js`

**Interfaces:**
- Produces: `defaultConfigFor(strategy)` and `normalizePolicySnapshot(strategyCode, snapshot, descriptor)`.
- Produces: parent config component emits complete config and boolean validity.

- [ ] **Step 1: Write failing catalog/API/config tests**

Assert a backend `PARENT_CHILD` descriptor is enabled, de-duplicated and presented as “父子分块”; `createPreview` sends it without throwing. Test configuration defaults and these invalid states: unknown mode, parent below 128/above 4096, child below 32/above 512, overlap below 0/above 128/not less than child, and paragraph parent below child.

Component test example:

```js
it('emits a valid full config and warns for full-document mode', async () => {
  const wrapper = mount(ParentChildStrategyConfig, { props: { initialValues: defaults } })
  await wrapper.get('[data-testid="parent-mode-full-document"]').trigger('click')
  expect(wrapper.text()).toContain('整篇文档作为回答上下文')
  expect(wrapper.emitted('config-change').at(-1)[0]).toMatchObject({ parentMode: 'FULL_DOCUMENT' })
  expect(wrapper.emitted('validity-change').at(-1)[0]).toBe(true)
})
```

- [ ] **Step 2: Run tests and verify RED**

Run:

```bash
cd web
npm test -- src/api/__tests__/chunking.test.js src/features/chunking/__tests__/strategyCatalog.test.js src/features/chunking/__tests__/strategyConfig.test.js src/components/chunking/__tests__/ParentChildStrategyConfig.test.js
```

Expected: failures show PARENT_CHILD is filtered/blocked and the config module/component is absent.

- [ ] **Step 3: Make backend capability authoritative**

Keep only `GENERAL` as a disabled local placeholder. Remove PARENT_CHILD from API and catalog reserved sets. Add presentation copy: “子块精准召回，父块提供完整回答上下文。”

- [ ] **Step 4: Implement configuration component and normalization**

Build the component with Element Plus radio buttons and input numbers, `--sea-*` variables, accessible labels and deterministic test IDs. Emit a complete immutable-shaped object after each change. Hide `parentMaxTokens` in FULL_DOCUMENT while retaining its value and show the cost warning.

- [ ] **Step 5: Isolate workspace config by strategy**

Maintain a plain-object `strategyConfigs` cache keyed by code. On strategy switch, save the current value, load the target cached/default value and reset validity until the target component emits. Restore processing snapshots according to `processing.strategyCode`, not the currently selected fallback. Submit exactly the selected strategy config.

- [ ] **Step 6: Run focused workspace tests**

Run:

```bash
cd web
npm test -- src/api/__tests__/chunking.test.js src/features/chunking/__tests__/strategyCatalog.test.js src/features/chunking/__tests__/strategyConfig.test.js src/components/chunking/__tests__/ParentChildStrategyConfig.test.js src/views/__tests__/ChunkingWorkspace.test.js
```

Expected: selected tests pass, including old Markdown hydration and route-race tests.

- [ ] **Step 7: Commit**

```bash
git add web/src/api web/src/features/chunking web/src/components/chunking web/src/views/ChunkingWorkspace.vue web/src/views/__tests__/ChunkingWorkspace.test.js
git commit -m "feat: 开放父子分块策略配置"
```

---

### Task 6: 父子分层预览与检索单元统计

**Files:**
- Create: `web/src/features/chunking/chunkHierarchy.js`
- Create: `web/src/features/chunking/__tests__/chunkHierarchy.test.js`
- Create: `web/src/components/chunking/ParentChunkGroup.vue`
- Create: `web/src/components/chunking/__tests__/ParentChunkGroup.test.js`
- Modify: `web/src/components/chunking/ChunkCard.vue`
- Modify: `web/src/components/chunking/__tests__/ChunkCard.test.js`
- Modify: `web/src/components/chunking/ChunkPreviewPanel.vue`
- Modify: `web/src/components/chunking/ContextConfirmDialog.vue`
- Modify: `web/src/components/chunking/__tests__/ContextConfirmDialog.test.js`
- Modify: `web/src/views/ChunkingWorkspace.vue`
- Modify: `web/src/views/__tests__/ChunkingWorkspace.test.js`
- Modify: `web/src/views/__tests__/ChunkingWorkflow.test.js`

**Interfaces:**
- Produces: `groupChunks(chunks)` returning `{ hierarchical, parents, singles, orphanChildren, parentCount, childCount, vectorCount }`.
- Produces: `ChunkCard.label` and `ChunkCard.showOverlapControls` props with backward-compatible defaults.

- [ ] **Step 1: Write failing hierarchy and component tests**

Test stable grouping from a flat API response whose parents/children are interleaved by global position. Assert child order uses siblingPosition, orphan children are reported, input is not mutated, SINGLE-only input remains flat, and vectorCount excludes parents.

Mount a parent group with two children and assert:

```js
expect(wrapper.get('[data-testid="parent-chunk-P1"]').text()).toContain('父块 01')
expect(wrapper.findAll('[data-testid="child-chunk"]')).toHaveLength(2)
expect(wrapper.find('[data-testid="overlap-switch"]').exists()).toBe(false)
expect(wrapper.find('[data-testid="edit-parent"]').exists()).toBe(false)
```

Assert child `updated/deleted/reload/reindex/save-state` events are forwarded unchanged.

- [ ] **Step 2: Run tests and verify RED**

Run:

```bash
cd web
npm test -- src/features/chunking/__tests__/chunkHierarchy.test.js src/components/chunking/__tests__/ParentChunkGroup.test.js src/components/chunking/__tests__/ChunkCard.test.js src/components/chunking/__tests__/ContextConfirmDialog.test.js src/views/__tests__/ChunkingWorkspace.test.js
```

Expected: hierarchy module and parent component are missing and preview remains flat.

- [ ] **Step 3: Implement pure grouping and parent group UI**

Normalize unknown chunk types to SINGLE only when no parent relation exists. Treat CHILD with missing parent as orphan and render a visible data-integrity banner with reload guidance. Parent cards show path, token count, readonly pre-wrapped content, child count and accessible expand/collapse control. Child cards use label `检索子块 NN` and hide overlap controls while retaining server overlap settings in save requests.

- [ ] **Step 4: Make preview and confirmation hierarchy-aware**

Render ParentChunkGroup when hierarchy is present and ordinary ChunkCard for SINGLE. Show `N 父块 · M 子块` and use `vectorCount` for confirmation/complete copy. Extend ContextConfirmDialog with optional parent/child counts and the message “仅对子块建立向量，命中后使用父块回答”。 Preserve the old overlap summary for SINGLE mode.

- [ ] **Step 5: Keep workspace events and barriers unchanged**

Derive hierarchy summary as a computed value in the workspace. Continue storing the server's flat chunk array so edit replacement, save barriers, reload epochs and reindex IDs remain keyed by child public ID. A deleted child triggers a server refresh, allowing an auto-deleted empty parent to disappear.

- [ ] **Step 6: Run all frontend tests and build**

Run:

```bash
cd web
npm test
npm run build
```

Expected: all tests pass and Vite produces a successful production build without correctness or security warnings introduced by this change.

- [ ] **Step 7: Commit**

```bash
git add web/src
git commit -m "feat: 展示父子分块层级预览"
```

---

### Task 7: 全链路回归、文档与最终验证

**Files:**
- Create: `server/Spring-AI/src/test/java/com/starsea/ai/chunking/ParentChildChunkingWorkflowTest.java`
- Modify: `web/src/views/__tests__/ChunkingWorkflow.test.js`

**Interfaces:**
- Consumes: Tasks 1–6.
- Produces: one verified branch with no uncommitted feature files.

- [ ] **Step 1: Add backend and frontend parent-child workflow tests**

The backend workflow must execute registered PARENT_CHILD planning through persistence, list parent/children, confirm, record only child vector IDs, and verify all rows reach ACTIVE. Use the existing in-memory mapper/test service harness; do not require a live embedding service.

The frontend workflow must select PARENT_CHILD, submit paragraph/1024/256/32, poll through CHUNKING to CHUNKED, render one parent with children, edit one child, confirm with the refreshed lock, poll to COMPLETED, and assert completion counts only child retrieval units.

- [ ] **Step 2: Run backend full suite**

Run:

```bash
cd server/Spring-AI
mvn -q test
```

Expected: exit code 0. Logged ERROR stack traces are acceptable only when emitted by tests that intentionally exercise compensation; Surefire must report zero failures/errors.

- [ ] **Step 3: Run backend required package build**

Run:

```bash
cd server/Spring-AI
mvn -q -DskipTests package
```

Expected: exit code 0.

- [ ] **Step 4: Run frontend full suite and required build**

Run:

```bash
cd web
npm test
npm run build
```

Expected: every Vitest file passes and Vite build exits 0.

- [ ] **Step 5: Review the complete change**

Run:

```bash
git diff main...HEAD --check
git status --short
git log --oneline main..HEAD
```

Verify no logs, build output, dependency directories, secrets, local databases or unrelated files are tracked. Confirm API fields and migration names match the spec.

- [ ] **Step 6: Commit the workflow tests**

```bash
git add server/Spring-AI/src/test/java/com/starsea/ai/chunking/ParentChildChunkingWorkflowTest.java web/src/views/__tests__/ChunkingWorkflow.test.js
git commit -m "test: 补充父子分块全链路回归"
```
