# 智能体工作台后端设计

> 日期：2026-09-02
>
> 状态：已确认，待实施计划
>
> 产品依据：[智能体工作台 PRD](../../prd/2026-09-01-智能体工作台-prd.md)

## 1. 目标

在保留现有多租户、知识库和 pgvector 检索能力的基础上，为智能体工作台补齐以下后端能力：

- 系统内置厂商目录与租户独立厂商连接。
- 数据库加密保存模型厂商 API Key。
- 内置厂商与 OpenAI-compatible 自定义厂商。
- Agent 草稿、显式发布、不可变快照和可审计回滚。
- 单机 Caffeine 临时调试上下文，不持久化调试会话或消息。
- 草稿调试与已发布正式调用彻底分离。
- 结构化 RAG 引用、列表统计、软删除和现有角色权限。

本设计只覆盖后端和数据层。视觉与交互继续以 PRD 和最终 HTML 原型为准。

## 2. 明确不做

- 不建立 `debug_session`、`debug_message` 或其他调试历史表。
- 不支持调试历史恢复、团队共享或跨实例迁移。
- 不引入 Redis、粘性会话、多实例缓存同步。
- 不实现关键词召回、RRF、OpenSearch 或 Elasticsearch；首版仍为 pgvector 向量检索。
- 不新增 `agent_editor` 等角色。
- 不建立模板表；客服、运营、研发模板由前端静态预填。
- 不迁移现有 `agent.model_api_key` 明文。
- 不支持同一租户为同一个内置厂商维护多套连接。
- 不实现模型或厂商的启用/禁用状态。

## 3. 现状与结构性缺口

当前后端存在以下约束：

- `agent` 直接保存 `model_url`、`model_api_key`、`model_id`，API Key 为可逆使用所需的明文字段。
- `/ai/agent/chat` 直接读取可变 Agent，草稿变更会立即影响调用。
- 聊天历史由全局 `InMemoryChatMemory` 和客户端 `chatId` 维护，无租户、用户和 Agent 作用域。
- Agent RAG 使用固定 Top-K 和阈值，并将检索结果拼接为纯文本，丢失引用元数据。
- 现有 `RetrievedChunk` 已有文档、Chunk、章节、页码和分数，但缺少知识库标识与名称。
- `AgentController` 直接暴露 MyBatis-Plus CRUD，缺少草稿修订、发布状态、并发保护和聚合列表。
- `TenantLineHandlerImpl` 会为非全局表自动附加当前 `tenant_id`；公共数据与租户数据混表会破坏这一安全边界。

因此，本设计采用公共目录和租户配置分表，不在同一张表中混合 `tenant_id IS NULL` 与租户记录。

## 4. 总体结构

```text
model_provider_catalog（公共厂商目录）
                 │
                 └── tenant_model_provider（租户厂商连接、密钥、候选模型）
                                │
                                └── agent_model（单个 Agent 独占的模型配置）
                                             ▲
                                             │ agent.agent_model_id
agent（可变草稿） ── agent_knowledge ── knowledge
  │
  ├── current_snapshot_id ── agent_snapshot（不可变发布快照）
  │
  ├── debug execution ── Caffeine debug context
  │
  └── published execution ── current agent_snapshot
```

新增四张表：

- `model_provider_catalog`
- `tenant_model_provider`
- `agent_model`
- `agent_snapshot`

扩展现有 `agent` 和 `agent_knowledge`。不新增调试持久化表。

## 5. 数据设计

### 5.1 `model_provider_catalog`

系统公共厂商目录，由 Flyway 数据迁移初始化和升级。该表加入 MyBatis 租户插件的全局表白名单。

| 字段 | 约束 | 含义 |
|---|---|---|
| `id` | PK | 内部 ID |
| `code` | UNIQUE, NOT NULL | 稳定厂商编码，如 `OPENAI` |
| `name` | NOT NULL | 厂商名称 |
| `icon` | NOT NULL | 图标资源键或路径 |
| `default_base_url` | NOT NULL | 默认 API Base URL |
| `protocol_type` | NOT NULL | 首版固定支持 `OPENAI_COMPATIBLE` |
| `auth_type` | NOT NULL | `API_KEY` 或 `NONE`；用于 Ollama 等无需密钥的连接 |
| `suggested_models` | JSONB, NOT NULL, default `[]` | 系统建议模型列表 |
| `create_time` | NOT NULL | 创建时间 |
| `update_time` | NOT NULL | 更新时间 |

`suggested_models` 每项固定包含：

```json
{
  "modelId": "gpt-4o-mini",
  "displayName": "GPT-4o Mini",
  "contextWindow": 128000
}
```

列表内 `modelId` 必须唯一。该列表只是创建租户连接时的初始候选，不直接供 Agent 选择。

### 5.2 `tenant_model_provider`

租户实际可调用的厂商连接。内置厂商和自定义厂商都在此表，但每行都必须属于一个租户，因此继续使用现有自动租户隔离。

| 字段 | 约束 | 含义 |
|---|---|---|
| `id` | PK | 内部 ID |
| `tenant_id` | NOT NULL | 租户 ID |
| `catalog_provider_id` | nullable FK | 内置厂商目录 ID；为空表示自定义厂商 |
| `custom_name` | nullable | 自定义厂商名称 |
| `custom_icon` | nullable | 自定义厂商图标 |
| `base_url` | NOT NULL | 租户实际使用的 Base URL |
| `selectable_models` | JSONB, NOT NULL, default `[]` | 当前连接可供 Agent 选择的模型 |
| `api_key_ciphertext` | 条件必填 | AES-GCM 密文；`auth_type=API_KEY` 时必填 |
| `api_key_nonce` | 条件必填 | 每次加密独立生成的随机 nonce |
| `api_key_version` | 条件必填 | 主密钥版本 |
| `api_key_last_four` | 条件必填 | 仅供界面辨识的末四位 |
| `last_verified_at` | NOT NULL | 最近一次成功连接验证时间 |
| `created_by` | NOT NULL | 创建用户 |
| `create_time` | NOT NULL | 创建时间 |
| `update_time` | NOT NULL | 更新时间 |

约束：

- `UNIQUE (id, tenant_id)`，供下游复合外键使用。
- 当 `catalog_provider_id` 非空时，`UNIQUE (tenant_id, catalog_provider_id)`，保证同租户同内置厂商只有一套连接。
- 当 `catalog_provider_id` 为空时，`custom_name`、`custom_icon` 和 `base_url` 必填。
- 自定义厂商名称在同一租户内不区分大小写唯一。
- `selectable_models` 结构与公共目录一致，`modelId` 在单个连接内唯一。
- `auth_type=API_KEY` 时四个密钥字段必填；`auth_type=NONE` 时四个字段必须为空。
- 不设置 `status` 或 `enabled`；记录存在即表示厂商已配置，模型存在于 JSON 列表即表示可选择。

内置厂商首次配置时，以公共 `suggested_models` 初始化租户候选列表。连接测试或用户刷新可以合并厂商 `/models` 返回结果；用户也可以手动补充模型 ID。

租户厂商配置仅在连接测试成功后创建或替换。更新密钥时先验证新连接，验证失败则保留旧密文和旧配置。

### 5.3 API Key 加密

API Key 必须使用 AES-256-GCM 加密后写入 `tenant_model_provider`：

- 主密钥由环境变量或部署 Secret 注入，不进入数据库、配置仓库或日志。
- 每次加密生成独立随机 nonce。
- `tenant_id + tenant_model_provider.id + api_key_version` 作为附加认证数据，防止密文被复制到其他租户或连接。创建连接时先从数据库序列分配 ID，再完成加密和单次插入。
- `api_key_version` 支持未来轮换主密钥。
- 生产环境缺少有效主密钥时应用启动失败。
- 接口只返回 `apiKeyConfigured=true` 和 `apiKeyLastFour`，永不返回明文、密文或 nonce。
- 连接测试和模型调用时按需解密，不缓存解密结果，不记录请求头或完整请求体。
- Agent、快照、SSE、异常和审计日志中不得包含 API Key。

现有 `ApiKeyCodec` 是面向入站调用凭证的不可逆 HMAC 摘要，不能复用于需要还原明文的模型厂商密钥。新能力使用独立的模型密钥加密组件。

### 5.4 `agent_model`

每个 Agent 独占一条模型配置。该表不保存 `agent_id`，由 `agent.agent_model_id` 反向引用。

| 字段 | 约束 | 含义 |
|---|---|---|
| `id` | PK | 模型配置 ID |
| `tenant_id` | NOT NULL | 租户 ID |
| `tenant_model_provider_id` | NOT NULL | 当前租户厂商连接 ID |
| `model_id` | NOT NULL | 上游模型实际 ID |
| `temperature` | NOT NULL | Agent 独立 Temperature |
| `top_p` | NOT NULL | Agent 独立 Top P |
| `max_tokens` | NOT NULL | Agent 独立最大输出 Token |
| `timeout_seconds` | NOT NULL | Agent 独立调用超时 |
| `deleted_at` | nullable | 随 Agent 软删除 |
| `deleted_by` | nullable | 随 Agent 记录删除用户 |
| `create_time` | NOT NULL | 创建时间 |
| `update_time` | NOT NULL | 更新时间 |

约束：

- `UNIQUE (id, tenant_id)`。
- `(tenant_model_provider_id, tenant_id)` 复合外键指向当前租户厂商连接。
- 创建或更新时，`model_id` 必须存在于厂商连接的 `selectable_models`；由于候选列表为 JSONB，此规则由事务服务校验。
- `agent.agent_model_id` 使用唯一约束，防止两个 Agent 指向同一条 `agent_model`。
- 已被 Agent 使用的候选模型禁止从 `selectable_models` 移除；被 Agent 使用的厂商连接禁止删除。

新建 Agent 可以暂时没有模型配置，此时 `agent.agent_model_id` 为空，列表显示“未配置模型”，且调试和发布均被拒绝。

### 5.5 `agent`

`agent` 是租户内可变的工作草稿。除现有字段外，增加或调整：

| 字段 | 含义 |
|---|---|
| `system_prompt` | 完整系统提示词，替代旧 `role_description` 语义 |
| `tags` | JSONB 字符串数组 |
| `variables` | JSONB 变量定义 |
| `agent_model_id` | 唯一引用该 Agent 独占模型配置 |
| `retrieval_top_k` | 向量召回数量，范围 1—20 |
| `retrieval_score_threshold` | 向量相似度阈值，范围 0—1 |
| `draft_revision` | 每次完整保存草稿后递增 |
| `published_revision` | 当前发布快照对应的草稿修订号 |
| `current_snapshot_id` | 当前发布快照；为空表示从未发布 |
| `lock_version` | 乐观锁版本 |
| `last_debugged_at` | 最后成功调试时间 |
| `last_debugged_by` | 最后成功调试用户 |
| `last_edited_by` | 最后编辑用户 |
| `deleted_at` | 软删除时间 |
| `deleted_by` | 删除用户 |

不增加 `retrieval_mode`。前端“混合检索”开关为首版占位，不入库、不进入快照，也不改变后端行为。

状态由数据计算，不保存额外状态字符串：

```text
current_snapshot_id IS NULL                 -> UNPUBLISHED
draft_revision > published_revision         -> DRAFT_CHANGED
draft_revision = published_revision         -> PUBLISHED
```

所有影响执行结果的草稿变更，包括基础资料、提示词、变量、知识库、模型和检索参数，必须通过统一事务服务完成并递增 `draft_revision`。

### 5.6 `agent_knowledge`

继续使用现有表，不新增 Agent 知识库表。补强数据库约束：

- `(agent_id, tenant_id)` 复合外键指向 `agent`。
- `(knowledge_id, tenant_id)` 复合外键指向 `knowledge`。
- 主键保持 `(agent_id, knowledge_id)`。

Agent 软删除时保留关联行，便于未来恢复；普通查询通过未删除 Agent 过滤，不会暴露已删除配置。

### 5.7 `agent_snapshot`

发布快照采用索引字段加完整 JSONB，不为快照内部各段配置新增子表。

| 字段 | 约束 | 含义 |
|---|---|---|
| `id` | PK | 快照 ID |
| `tenant_id` | NOT NULL | 租户 ID |
| `agent_id` | NOT NULL | Agent ID |
| `version_number` | NOT NULL | Agent 内递增版本 |
| `publish_note` | NOT NULL | 发布简介 |
| `snapshot_data` | JSONB, NOT NULL | 完整不可变配置 |
| `source_revision` | NOT NULL | 发布来源草稿修订号 |
| `rollback_from_snapshot_id` | nullable | 回滚来源快照 |
| `created_by` | NOT NULL | 发布用户 |
| `create_time` | NOT NULL | 发布时间 |
| `deleted_at` | nullable | 随 Agent 软删除 |
| `deleted_by` | nullable | 删除用户 |

约束：

- `UNIQUE (tenant_id, agent_id, version_number)`。
- Agent、回滚来源和当前快照引用均使用包含 `tenant_id` 的复合外键。
- 快照创建后禁止更新 `snapshot_data`、版本号、发布简介和作者。
- 快照永久保留，除非所属 Agent 被软删除。

`snapshot_data` 固定包含：

- Agent 名称、描述、开场白和标签。
- 系统提示词和变量定义。
- 知识库 ID 列表。
- 厂商连接 ID、厂商展示信息、模型 ID和模型参数。
- Top-K 和相似度阈值。

快照不得包含 API Key、密文、nonce 或调试消息。正式运行时根据快照中的厂商连接 ID 读取当前密钥，因此密钥轮换不要求重新发布。

## 6. 核心事务

### 6.1 保存草稿

1. 读取未删除 Agent，并校验当前租户和 `lock_version`。
2. 校验知识库、厂商连接和模型均属于当前租户。
3. 更新 Agent 基础资料、提示词、变量和检索参数。
4. 创建或更新该 Agent 独占的 `agent_model`。
5. 替换 `agent_knowledge` 关联。
6. 递增 `draft_revision`、`lock_version`，记录最后编辑人。
7. 在一个数据库事务中提交。

并发版本不匹配返回 HTTP 409，不静默覆盖其他管理员的草稿。

### 6.2 发布

1. 锁定 Agent 行，拒绝已删除或无模型配置的 Agent。
2. 校验版本号、发布简介、知识库、模型连接、提示词和检索参数。
3. 读取完整草稿并组装 `snapshot_data`。
4. 插入不可变 `agent_snapshot`。
5. 更新 `current_snapshot_id` 和 `published_revision=draft_revision`。
6. 提交后清除相关运行配置缓存。

正式调用只能读取 `current_snapshot_id` 对应快照，不能读取草稿。

### 6.3 回滚

1. 锁定 Agent 并读取同租户、同 Agent 的历史目标快照。
2. 将目标快照内容恢复为当前 Agent 草稿、知识库关联和独占 `agent_model`。
3. 递增草稿修订号。
4. 创建下一个版本的新快照，记录 `rollback_from_snapshot_id`。
5. 将新快照设为当前发布快照，并令发布修订等于新草稿修订。

例如从 v18 回滚到 v12，结果是新建“v19 · 回滚自 v12”；v12 和 v18 均不修改。

### 6.4 级联软删除

删除 Agent 时，在同一事务中使用同一个时间戳：

- 标记 `agent.deleted_at/deleted_by`。
- 标记其独占 `agent_model.deleted_at`。
- 标记全部 `agent_snapshot.deleted_at/deleted_by`。
- 保留 `agent_knowledge`、厂商连接和知识库。
- 主动清理该 Agent 的全部 Caffeine 调试上下文。

列表、详情、调试和正式调用默认排除已删除记录。首版不提供恢复入口。

## 7. 厂商与模型配置流程

### 7.1 内置厂商

1. 查询公共 `model_provider_catalog`。
2. 左连接当前租户的 `tenant_model_provider`，由服务层返回统一视图。
3. 未配置厂商通常只要求用户填写 API Key，Base URL 可使用公共默认值并允许覆盖。
4. 测试连接成功后创建租户连接，以公共建议模型初始化 `selectable_models`。
5. 用户可以刷新上游模型、从候选中选择或手动补充模型 ID。

### 7.2 自定义厂商

1. 用户填写名称、图标、Base URL、API Key 和至少一个模型 ID。
2. 首版只支持 OpenAI-compatible 协议。
3. 测试连接成功后创建 `catalog_provider_id IS NULL` 的租户连接。
4. 自定义厂商仅在当前租户可见。

### 7.3 Agent 选择模型

Agent 详情只查询当前租户已存在的厂商连接及其 `selectable_models`。选择后：

- Agent 尚无模型配置时创建一条 `agent_model`，再设置 `agent.agent_model_id`。
- Agent 已有模型配置时更新该独占记录。
- 快捷新增厂商或模型复用相同服务，不在 Agent 控制器复制连接和加密逻辑。

## 8. 执行模型

### 8.1 统一执行内核

新增统一 Agent 执行服务，负责：

1. 加载草稿或发布快照。
2. 校验租户、权限、软删除状态和模型连接。
3. 渲染系统提示词变量。
4. 使用最后一条用户消息执行向量检索。
5. 组装结构化引用和模型上下文。
6. 解密当前厂商 API Key。
7. 应用 Agent 独立模型参数并流式调用。
8. 输出统一 SSE 事件。

调试入口显式选择草稿加载器；正式入口显式选择发布快照加载器，避免 Controller 根据状态隐式切换。

### 8.2 RAG 与引用

首版继续复用 `PgVectorRagServiceImpl`，不实现真正混合检索。Top-K 范围为 1—20，相似度阈值范围为 0—1。

结构化引用补充：

- 知识库公开 ID和名称。
- 文档公开 ID、标题和文件类型。
- Chunk 公开 ID与位置。
- 章节路径、页码和来源定位。
- 归一化分数和展示摘要。

执行服务为本轮结果分配 `C1`、`C2` 等稳定顺序编号，并使用相同编号组装模型上下文和 SSE `retrieval` 事件。无召回结果是正常业务结果，按系统提示词兜底，不作为服务器异常。

### 8.3 SSE 协议

统一事件：

| 事件 | 内容 |
|---|---|
| `context` | 首次调试返回 `debugContextId` |
| `retrieval` | 本轮结构化引用列表 |
| `delta` | 模型文本增量 |
| `usage` | 可获得时返回耗时和 Token 用量 |
| `complete` | 正常结束和完成原因 |
| `error` | 流建立后的结构化错误 |

流建立前的认证、权限、参数和配置错误使用普通 HTTP 4xx。流建立后的上游错误通过 `error` 事件返回。客户端断开连接必须取消上游请求。

## 9. Caffeine 临时调试上下文

每个前端调试 Tab 使用一个不可猜测的 `debugContextId`。缓存值绑定：

- `tenantId`
- `userId`
- `agentId`
- 有序用户/助手消息
- 创建时间和最后访问时间
- 上下文修订号

首版固定边界：

- 单机 Caffeine，访问后 30 分钟过期。
- 每个用户最多 4 个上下文。
- 全局默认最多 1000 个上下文，支持配置覆盖。
- 单条消息最多 16,000 字符。
- 每个上下文最多 40 条消息和 64,000 字符；超限时按完整用户/助手轮次从最早处裁剪。
- 同一上下文同一时间只允许一个生成请求；并发请求返回 409。
- 关闭 Tab 时前端调用删除接口；过期、刷新、离开页面或应用重启后不可恢复。
- 缓存内容不得写入数据库、日志或异常消息。

后续调试请求仅传 `debugContextId`、新消息和本轮变量，不重复传完整历史。缓存只减少浏览器到 StarSeaKnow 的重复传输；OpenAI-compatible 上游通常仍需接收完整模型上下文，因此不会自动减少模型 Token 消耗。

## 10. API 边界

### 10.1 厂商连接

- `GET /model-providers`：公共目录与当前租户配置的统一视图。
- `POST /model-providers/connections/test`：测试内置或自定义连接，不保存失败配置。
- `POST /model-providers/connections`：创建当前租户连接。
- `PUT /model-providers/connections/{id}`：验证成功后更新 URL 或密钥。
- `POST /model-providers/connections/{id}/models`：增加候选模型。
- `PUT /model-providers/connections/{id}/models`：验证并替换候选模型列表；禁止移除被 Agent 或当前发布快照使用的模型。
- `DELETE /model-providers/connections/{id}`：删除未被 Agent 使用的租户连接。

### 10.2 Agent 与列表

- `GET /agents`：分页、状态、标签和关键词过滤。
- `GET /agents/metrics`：全部、已发布、本周调试、有暂存。
- `GET /agents/{id}`：聚合草稿、知识库和模型配置。
- `POST /agents`：创建无模型或带完整模型配置的草稿。
- `PUT /agents/{id}/draft`：原子保存完整草稿并进行乐观锁校验。
- `DELETE /agents/{id}`：级联软删除。

“本周调试”定义为 `last_debugged_at` 位于当前自然周的未删除 Agent 数量，不是消息数或会话数。

### 10.3 快照

- `GET /agents/{id}/snapshots`：倒序分页返回快照元数据。
- `GET /agents/{id}/snapshots/{version}`：快照预览。
- `POST /agents/{id}/publish`：发布当前草稿。
- `POST /agents/{id}/snapshots/{version}/rollback`：基于历史快照创建新发布版本。

### 10.4 调试与正式调用

- `POST /agents/{id}/debug/stream`：使用草稿和可选 `debugContextId` 流式调试。
- `DELETE /agents/{id}/debug-contexts/{debugContextId}`：主动清理当前用户上下文。
- `POST /agents/{id}/chat/stream`：仅使用当前已发布快照。

## 11. 权限

沿用现有角色：

| 能力 | `tenant_admin` | `tenant_member` |
|---|---:|---:|
| 查看已发布 Agent | 是 | 是 |
| 正式调用已发布 Agent | 是 | 是 |
| 查看草稿和历史快照 | 是 | 否 |
| 配置厂商和密钥 | 是 | 否 |
| 创建、编辑和调试 Agent | 是 | 否 |
| 发布、回滚和删除 Agent | 是 | 否 |

平台管理员不通过平台身份跨租户调用工作台业务接口。所有业务读写继续要求有效租户上下文。

## 12. 错误处理与并发

- 跨租户资源统一表现为不存在，避免泄漏 ID 是否有效。
- 草稿乐观锁冲突返回 409。
- 同一 Agent 发布或回滚使用数据库行锁串行化。
- 模型未配置、厂商不存在、候选模型已移除或 API Key 解密失败时拒绝调试和发布。
- 已发布快照引用的厂商连接被使用时禁止删除。
- 厂商认证失败不自动清除旧密钥；更新失败时保留原连接。
- 上游 401/403、429、超时和 5xx 映射为稳定业务错误码，不向前端透传敏感响应体。
- 已软删除 Agent、未发布 Agent 的正式调用、过期调试上下文均返回明确错误。
- 调试上下文过期后前端可自动创建新上下文，但不恢复历史消息。

## 13. 列表、搜索与统计

Agent 标签使用 `agent.tags JSONB`，不新增标签表。列表查询支持：

- 精确标签包含过滤。
- 名称和描述关键词过滤。
- 未发布、已发布、有暂存状态过滤。
- 分页和最近更新时间排序。

卡片聚合字段包括知识库数量、当前发布版本、是否配置模型、最后调试人和时间。常用标签通过当前租户未删除 Agent 的标签聚合，不建立标签目录表。

## 14. 旧数据迁移

采用两阶段迁移：

### 第一阶段

- 新增厂商、模型、快照和 Agent 扩展结构。
- 保留现有 Agent 名称、描述、开场白、角色提示词和知识库关联。
- 不读取、不复制、不重新加密旧 `model_api_key`。
- 新版运行逻辑忽略旧 `model_url`、`model_api_key`、`model_id`。
- 旧 Agent 显示“未配置模型”，由租户管理员重新配置厂商和模型。

### 第二阶段

在确认所有租户完成重新配置后，通过独立 Flyway 迁移删除旧三个模型字段。不得在第一阶段自动删除，以便部署方有明确过渡窗口。

## 15. 验证策略

每个实施模块均需运行后端基础构建，并增加与风险相符的自动化测试。

重点测试：

- AES-GCM 加解密、随机 nonce、错误密钥和密文篡改失败。
- API DTO、日志、异常和快照均不包含密钥。
- 公共厂商与当前租户连接合并，不返回其他租户连接。
- 复合外键和服务校验阻止跨租户厂商、模型、知识库与快照引用。
- 一个 `agent_model` 不能被两个 Agent 引用。
- 保存草稿修订、状态计算、乐观锁冲突。
- 发布原子性、不可变快照、并发版本冲突和回滚新建版本。
- Agent、模型配置和快照使用同一时间戳软删除。
- pgvector 检索继续遵守租户、知识库、文件状态、Top-K 和阈值。
- 结构化引用的知识库、文档、Chunk 和顺序编号正确。
- Caffeine TTL、每用户上限、全局上限、上下文裁剪、作用域校验和并发保护。
- 调试读取草稿，正式调用只读取当前发布快照。
- `tenant_member` 无法读取草稿、配置厂商、调试、发布或删除。
- SSE 正常完成、客户端取消、上游超时和流中错误协议。

后端生产代码变更后的最低验证命令为：

```bash
cd server/Spring-AI
mvn -q -DskipTests package
```

相关模块测试存在时还必须运行对应测试套件。

## 16. 分模块实施顺序

### 模块 1：厂商目录与密钥基础设施

建立公共厂商目录、模型密钥加密组件、配置校验和安全测试。

### 模块 2：租户厂商连接

建立租户连接、公共与租户合并查询、自定义厂商、连接测试和候选模型维护。

### 模块 3：Agent 草稿与模型映射

建立 Agent 独占 `agent_model`、完整草稿事务、知识库复合约束、列表搜索、统计和软删除基础。

### 模块 4：发布快照与回滚

建立不可变快照、草稿状态计算、发布、预览、永久保留和新版本回滚。

### 模块 5：统一执行内核

拆分草稿/快照加载，统一提示词、变量、向量 RAG、结构化引用、模型适配与错误映射。

### 模块 6：Caffeine 临时调试上下文

建立有界临时上下文、SSE 调试、取消、过期和最后调试元数据。

### 模块 7：正式调用与旧结构清理

切换正式调用到发布快照，落实角色权限，废弃旧聊天入口，并在迁移窗口结束后删除旧明文模型字段。

依赖顺序：

```text
厂商与加密
→ 租户连接
→ Agent 草稿
→ 发布快照
→ 执行内核
→ 临时调试
→ 正式调用与旧结构清理
```

每个模块作为独立主题完成测试、构建和 Conventional Commit，不把后续模块提前混入。
