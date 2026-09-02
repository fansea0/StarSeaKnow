# 智能体工作台剩余模块 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 完成智能体工作台模块 2—7 的后端能力及与最终 HTML 原型一致的 Vue 工作台页面。

**Architecture:** 按“租户厂商连接 → Agent 草稿与独占模型 → 发布快照 → 统一执行 → Caffeine 调试 → 正式调用”的依赖顺序演进数据库和服务。前端使用独立 API 层消费这些聚合接口，模型管理、Agent 列表和三栏工作台直接复刻最终原型的信息架构与 sea 主题，不继续调用旧 `/agent/*` 和 `/ai/agent/chat` 接口。

**Tech Stack:** Java 17、Spring Boot 3.3、MyBatis-Plus、PostgreSQL/pgvector、Caffeine、Spring AI、Vue 3、Vue Router、Element Plus、Vitest

**Spec:** `docs/superpowers/specs/2026-09-02-agent-workbench-backend-design.md`、`docs/prd/2026-09-01-智能体工作台-prd.md`、`docs/prototype/2026-09-01-智能体工作台/2026-09-01-智能体工作台.html`

## Global Constraints

- 调试会话和消息不入库，只存在单机 Caffeine；前端后续请求只携带 `debugContextId` 和新消息。
- 公共厂商与租户连接分表；API Key 只以 AES-256-GCM 密文存储，任何响应、日志、快照不得包含明文、密文或 nonce。
- 一个 Agent 独占一个 `agent_model`，由 `agent.agent_model_id` 反向引用，`agent_model` 不保存 `agent_id`。
- 不实现真正混合检索；前端保留原型按钮，后端仍只使用 pgvector。
- Agent、独占模型和快照使用同一时间戳软删除；知识库关系、厂商连接和快照历史规则按设计文档执行。
- `tenant_admin` 管理厂商、草稿、调试、发布、回滚和删除；`tenant_member` 只查看和调用已发布 Agent。
- 页面颜色、字体、圆角、阴影、三栏结构和文案以最终 HTML 原型及其 `style-guide.md` 为准。
- 每个后端模块完成后运行 `mvn -q test` 和 `mvn -q -DskipTests package`；前端模块运行 `npm test` 和 `npm run build`。

---

### Task 1: 模块 2—租户厂商连接数据库与聚合查询

**Files:**
- Create: `server/Spring-AI/src/main/resources/db/V11__add_tenant_model_provider.sql`
- Create: `server/Spring-AI/src/main/java/com/starsea/ai/model/provider/ModelSuggestion.java`
- Create: `server/Spring-AI/src/main/java/com/starsea/ai/model/provider/ModelProviderCatalog.java`
- Create: `server/Spring-AI/src/main/java/com/starsea/ai/model/provider/TenantModelProvider.java`
- Create: `server/Spring-AI/src/main/java/com/starsea/ai/mapper/ModelProviderCatalogMapper.java`
- Create: `server/Spring-AI/src/main/java/com/starsea/ai/mapper/TenantModelProviderMapper.java`
- Test: `server/Spring-AI/src/test/java/com/starsea/ai/model/provider/V11TenantModelProviderMigrationPostgresIT.java`

**Interfaces:**
- Produces `tenant_model_provider` with composite tenant identity, built-in uniqueness, case-insensitive custom-name uniqueness, JSON model validation, authentication-field consistency, timestamps and encrypted secret columns.
- Produces mapper operations `nextId()`, catalog listing, current-tenant connection listing and row locking by ID.

- [ ] Write PostgreSQL tests that reject cross-tenant references, duplicate built-in/custom providers, malformed model JSON and invalid API-key field combinations.
- [ ] Run `mvn -q -Dtest=V11TenantModelProviderMigrationPostgresIT test` and observe RED because V11 is absent.
- [ ] Implement V11 and mappings; add `auth_type` to the tenant row so custom providers and `NONE` authentication can enforce their own database constraint.
- [ ] Re-run the migration test and observe GREEN.

### Task 2: 模块 2—连接验证、加密事务与管理 API

**Files:**
- Create: `server/Spring-AI/src/main/java/com/starsea/ai/model/provider/ModelProviderConnectionVerifier.java`
- Create: `server/Spring-AI/src/main/java/com/starsea/ai/model/provider/OpenAiCompatibleConnectionVerifier.java`
- Create: `server/Spring-AI/src/main/java/com/starsea/ai/model/provider/ModelProviderException.java`
- Create: `server/Spring-AI/src/main/java/com/starsea/ai/model/provider/ModelProviderService.java`
- Create: `server/Spring-AI/src/main/java/com/starsea/ai/model/provider/ModelProviderController.java`
- Modify: `server/Spring-AI/src/main/java/com/starsea/ai/config/GlobalExceptionHandler.java`
- Test: `server/Spring-AI/src/test/java/com/starsea/ai/model/provider/ModelProviderServiceTest.java`
- Test: `server/Spring-AI/src/test/java/com/starsea/ai/model/provider/ModelProviderControllerTest.java`
- Test: `server/Spring-AI/src/test/java/com/starsea/ai/model/provider/OpenAiCompatibleConnectionVerifierTest.java`

**Interfaces:**
- `GET /model-providers` returns built-in catalog left-joined with current-tenant connections plus tenant custom providers.
- `POST /model-providers/connections/test|connections`, `PUT|DELETE /model-providers/connections/{id}`, `POST|PUT .../{id}/models` implement the confirmed API boundary.
- `ModelProviderConnectionVerifier.verify(baseUrl, authType, apiKey)` returns discovered models or a sanitized 401/429/timeout/upstream error.

- [ ] Write service/controller/gateway RED tests for tenant isolation, verify-before-write, failed-update preservation, encryption fields, no secret response, discovered-model merge and candidate replacement.
- [ ] Implement the verifier with bounded timeout and `/models`; never log headers or upstream bodies.
- [ ] Implement transactional service and admin-only controller; create rows with preallocated sequence IDs before AAD-bound encryption.
- [ ] Run module tests, full backend tests and package; commit `feat: 完成租户模型厂商配置`.

### Task 3: 模块 2 前端—模型管理页面

**Files:**
- Create: `web/src/api/modelProviders.js`
- Create: `web/src/views/ModelProviders.vue`
- Create: `web/src/views/__tests__/ModelProviders.test.js`
- Modify: `web/src/router/index.js`
- Modify: `web/src/App.vue`

**Interfaces:**
- Route `/models` is tenant-admin only and consumes Task 2 APIs.
- Page reproduces prototype provider rail, provider header, model table, connection state and add/configure modal; no enabled-state control is shown.

- [ ] Write Vitest RED tests for route visibility, provider selection, API-key non-echo, connection test, save and model add/remove.
- [ ] Implement the page using exact sea tokens, Noto fonts, 280px provider rail, model-table density, modal hierarchy and responsive fallback from the prototype.
- [ ] Run `npm test` and `npm run build`; capture desktop and mobile screenshots for comparison; commit `feat: 添加租户模型管理页面`.

### Task 4: 模块 3—Agent 草稿、独占模型和软删除

**Files:**
- Create: `server/Spring-AI/src/main/resources/db/V12__add_agent_draft_and_model.sql`
- Create: `server/Spring-AI/src/main/java/com/starsea/ai/agent/AgentModel.java`
- Create: `server/Spring-AI/src/main/java/com/starsea/ai/agent/AgentModelMapper.java`
- Create: `server/Spring-AI/src/main/java/com/starsea/ai/agent/AgentWorkbenchApiModels.java`
- Create: `server/Spring-AI/src/main/java/com/starsea/ai/agent/AgentAggregateService.java`
- Create: `server/Spring-AI/src/main/java/com/starsea/ai/agent/AgentWorkbenchController.java`
- Test: `server/Spring-AI/src/test/java/com/starsea/ai/agent/V12AgentDraftMigrationPostgresIT.java`
- Test: `server/Spring-AI/src/test/java/com/starsea/ai/agent/AgentAggregateServiceTest.java`
- Test: `server/Spring-AI/src/test/java/com/starsea/ai/agent/AgentWorkbenchControllerTest.java`

**Interfaces:**
- `GET /agents`, `GET /agents/metrics`, `GET /agents/{id}`, `POST /agents`, `PUT /agents/{id}/draft`, `DELETE /agents/{id}`.
- Draft command includes basic fields, tags, variables, knowledge IDs, retrieval parameters and optional exclusive model configuration.

- [ ] Write migration and service RED tests covering every invariant above.
- [ ] Implement V12 and aggregate service with one transaction boundary and revision increment.
- [ ] Implement admin/member projection rules and stable 404/409 errors.
- [ ] Verify and commit `feat: 建立智能体草稿与独占模型`.

### Task 5: 模块 4—不可变快照、发布和回滚

**Files:**
- Create: `server/Spring-AI/src/main/resources/db/V13__add_agent_snapshot.sql`
- Create: `server/Spring-AI/src/main/java/com/starsea/ai/agent/snapshot/AgentSnapshot.java`
- Create: `server/Spring-AI/src/main/java/com/starsea/ai/agent/snapshot/AgentSnapshotMapper.java`
- Create: `server/Spring-AI/src/main/java/com/starsea/ai/agent/snapshot/AgentSnapshotAssembler.java`
- Create: `server/Spring-AI/src/main/java/com/starsea/ai/agent/snapshot/AgentSnapshotService.java`
- Create: `server/Spring-AI/src/main/java/com/starsea/ai/agent/snapshot/AgentSnapshotController.java`
- Test: `server/Spring-AI/src/test/java/com/starsea/ai/agent/snapshot/V13AgentSnapshotMigrationPostgresIT.java`
- Test: `server/Spring-AI/src/test/java/com/starsea/ai/agent/snapshot/AgentSnapshotServiceTest.java`

**Interfaces:**
- `GET /agents/{id}/snapshots`, `GET /agents/{id}/snapshots/{version}`, `POST /agents/{id}/publish`, `POST /agents/{id}/snapshots/{version}/rollback`.

- [ ] Write RED tests for atomic publish, immutable JSON, concurrent serialization and rollback provenance.
- [ ] Implement V13 and snapshot assembler that stores provider connection ID but no secret material.
- [ ] Implement APIs and revision-derived state transitions.
- [ ] Verify and commit `feat: 添加智能体发布快照与回滚`.

### Task 6: 模块 5—统一执行内核和结构化引用

**Files:**
- Create: `server/Spring-AI/src/main/java/com/starsea/ai/agent/execution/ExecutionSource.java`
- Create: `server/Spring-AI/src/main/java/com/starsea/ai/agent/execution/ExecutionRequest.java`
- Create: `server/Spring-AI/src/main/java/com/starsea/ai/agent/execution/ExecutionEvent.java`
- Create: `server/Spring-AI/src/main/java/com/starsea/ai/agent/execution/AgentExecutionService.java`
- Create: `server/Spring-AI/src/main/java/com/starsea/ai/agent/execution/DraftExecutionSourceLoader.java`
- Create: `server/Spring-AI/src/main/java/com/starsea/ai/agent/execution/SnapshotExecutionSourceLoader.java`
- Create: `server/Spring-AI/src/main/java/com/starsea/ai/agent/execution/AgentPromptAssembler.java`
- Create: `server/Spring-AI/src/main/java/com/starsea/ai/agent/execution/AgentModelClientFactory.java`
- Modify: `server/Spring-AI/src/main/java/com/starsea/ai/service/impl/PgVectorRagServiceImpl.java`
- Modify: `server/Spring-AI/src/main/java/com/starsea/ai/service/RagService.java`
- Test: `server/Spring-AI/src/test/java/com/starsea/ai/agent/execution/AgentExecutionServiceTest.java`
- Test: `server/Spring-AI/src/test/java/com/starsea/ai/agent/execution/AgentPromptAssemblerTest.java`

**Interfaces:**
- `AgentExecutionService.execute(ExecutionSource source, ExecutionRequest request)` emits `retrieval/delta/usage/complete/error` events.
- Draft loader and published snapshot loader are explicit, never selected implicitly by controller state.

- [ ] Write RED tests for prompt variables, pgvector Top-K/threshold, C1/C2 order, no-result behavior, current-key decrypt and sanitized upstream errors.
- [ ] Implement loaders, prompt/context assembler, citations and model adapter.
- [ ] Verify existing RAG tenant/file-state tests plus new execution tests.
- [ ] Commit `feat: 建立统一智能体执行内核`.

### Task 7: 模块 6—Caffeine 临时调试上下文与 SSE

**Files:**
- Create: `server/Spring-AI/src/main/java/com/starsea/ai/agent/debug/DebugContextProperties.java`
- Create: `server/Spring-AI/src/main/java/com/starsea/ai/agent/debug/DebugContext.java`
- Create: `server/Spring-AI/src/main/java/com/starsea/ai/agent/debug/DebugContextStore.java`
- Create: `server/Spring-AI/src/main/java/com/starsea/ai/agent/debug/DebugExecutionCoordinator.java`
- Create: `server/Spring-AI/src/main/java/com/starsea/ai/agent/debug/AgentDebugController.java`
- Modify: `server/Spring-AI/src/main/resources/application.yml`
- Test: `server/Spring-AI/src/test/java/com/starsea/ai/agent/debug/DebugContextStoreTest.java`
- Test: `server/Spring-AI/src/test/java/com/starsea/ai/agent/debug/AgentDebugControllerTest.java`

**Interfaces:**
- `POST /agents/{id}/debug/stream` accepts `{debugContextId?, message, variables}`.
- `DELETE /agents/{id}/debug-contexts/{debugContextId}` removes only the caller-owned context.

- [ ] Write RED tests for all fixed cache limits and no persistence.
- [ ] Implement Caffeine store and execution coordination.
- [ ] Implement SSE lifecycle and client-cancel cleanup.
- [ ] Verify and commit `feat: 添加临时调试上下文与流式调试`.

### Task 8: 模块 7—正式调用、权限和旧入口退役

**Files:**
- Create: `server/Spring-AI/src/main/java/com/starsea/ai/agent/execution/PublishedAgentChatController.java`
- Create: `server/Spring-AI/src/main/resources/db/V14__drop_legacy_agent_model_columns.sql`
- Modify: `server/Spring-AI/src/main/java/com/starsea/ai/controller/AgentController.java`
- Modify: `server/Spring-AI/src/main/java/com/starsea/ai/controller/AiChatController.java`
- Delete: `server/Spring-AI/src/main/java/com/starsea/ai/model/AgentChatClientFactory.java`
- Delete: `server/Spring-AI/src/main/java/com/starsea/ai/model/OpenAiCompatibleAgentChatClientFactory.java`
- Test: `server/Spring-AI/src/test/java/com/starsea/ai/agent/execution/PublishedAgentChatControllerTest.java`
- Test: `server/Spring-AI/src/test/java/com/starsea/ai/agent/execution/V14LegacyModelColumnsMigrationPostgresIT.java`

**Interfaces:**
- `POST /agents/{id}/chat/stream` always loads `current_snapshot_id`; unpublished/deleted Agent is rejected.
- Old `/ai/agent/chat` and plaintext model fields are unused before columns are dropped.

- [ ] Write RED permission and source-selection tests for tenant admin/member and unpublished/deleted/cross-tenant cases.
- [ ] Switch runtime and retire old endpoints without compatibility fallback to plaintext keys.
- [ ] Add migration dropping `model_url/model_api_key/model_id` only after repository search proves no production reads.
- [ ] Verify and commit `refactor: 切换智能体正式调用到发布快照`.

### Task 9: 前端—原型一致的 Agent 列表与三栏工作台

**Files:**
- Create: `web/src/api/agents.js`
- Rewrite: `web/src/views/Agent.vue`
- Rewrite: `web/src/views/AgentDetail.vue`
- Create: `web/src/components/agent/AgentEditorTabs.vue`
- Create: `web/src/components/agent/AgentModelSelector.vue`
- Create: `web/src/components/agent/AgentDebugPanel.vue`
- Create: `web/src/components/agent/AgentReferences.vue`
- Create: `web/src/components/agent/AgentSnapshotHistory.vue`
- Test: `web/src/views/__tests__/AgentList.test.js`
- Test: `web/src/views/__tests__/AgentWorkbench.test.js`
- Test: `web/src/components/agent/__tests__/AgentDebugPanel.test.js`

**Interfaces:**
- List consumes `/agents` and `/agents/metrics`.
- Detail consumes aggregate draft, configured provider models, snapshots and SSE endpoints from Tasks 2—8.

- [ ] Write component/API RED tests from PRD acceptance scenarios.
- [ ] Reproduce prototype desktop structure: 60px action bar, left configuration rail/editor, center prompt workspace, right dark-headed debug instrument and snapshot side panel.
- [ ] Keep “混合检索” as visual placeholder only; never send a retrieval mode field.
- [ ] Implement keyboard focus, reduced-motion behavior and mobile stacked layout.
- [ ] Run all frontend tests/build and compare screenshots at prototype desktop width and 390px mobile.
- [ ] Commit `feat: 完成智能体工作台前端`.

### Task 10: 全链路验收与文档收口

**Files:**
- Modify: `docs/superpowers/specs/2026-09-02-agent-workbench-backend-design.md`
- Modify: `docs/prd/2026-09-01-智能体工作台-prd.md`
- Create: `docs/api/agent-workbench.md`

- [ ] Run fresh-database Flyway migration V1 through final version and inspect constraints.
- [ ] Run full backend tests/build and full frontend tests/build.
- [ ] Exercise model configuration → Agent draft → debug → publish → member chat → rollback → soft-delete flow against local backend.
- [ ] Inspect `error.log` and confirm the acceptance flow produces no new ERROR entries or secret leakage.
- [ ] Compare final desktop/mobile screenshots with the supplied prototype and correct material visual differences.
- [ ] Run `git diff --check`, secret scan and `git status --short`; commit final documentation and mark modules 2—7 complete only when evidence covers every requirement.
