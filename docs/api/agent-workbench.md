# 智能体工作台接口与升级说明

更新：2026-09-03。浏览器路由仍为 `/agent`、`/agent/:id`；模型管理为 `/models`。浏览器请求统一加 `/api` 前缀，后端接口本身不含该前缀。

## 使用入口

1. 租户管理员进入「模型」，选择内置厂商或新增自定义厂商，填写 Base URL、API Key 并保存。连接验证成功后才落库，密钥只返回已配置标记和末四位。
2. 进入「智能体」创建/打开 Agent，在左侧「模型」分区选择该租户已配置的模型。可通过「配置模型」新页签快捷新增，返回后刷新候选列表。
3. 编辑基础信息、提示词、变量、知识库和检索参数，自动或手动保存草稿。模型参数属于这个 Agent 的独占映射，不共享给其他 Agent。
4. 在右侧调试，确认回答与引用后填写发布说明发布。成员只能使用发布版本；后续草稿改动不影响正式调用。

## 权限与数据约定

- 管理、调试、快照接口：`tenant_admin`；正式调用及已发布列表/详情：同租户 `tenant_member` 或管理员。
- 租户由认证上下文取得，客户端不能通过请求参数指定其他租户。
- 普通接口返回 `{code: 200, data: ...}`；失败使用相应 HTTP 状态及 JSON 错误。流式响应开始前的失败也是 JSON。
- 无启用/禁用字段。厂商连接未删除且配置成功即表示可选择；模型候选保存在 `tenant_model_provider.selectable_models`。
- `agent.agent_model_id` 指向独占 `agent_model`，后者只有厂商连接 ID、模型 ID 和参数，不含 `agent_id`、API Key。

## 管理接口

| 方法 | 后端路径 | 用途 |
|---|---|---|
| GET | `/model-providers` | 合并公共厂商目录与当前租户配置 |
| POST | `/model-providers/connections/test` | 连接测试，不持久化 |
| POST | `/model-providers/connections` | 验证后创建连接 |
| PUT | `/model-providers/connections/{id}` | 更新连接；空密钥保留已加密密钥 |
| POST | `/model-providers/connections/{id}/models` | 添加模型候选 |
| PUT | `/model-providers/connections/{id}/models` | 替换模型候选；禁止移除正在使用的模型 |
| DELETE | `/model-providers/connections/{id}` | 无有效引用时软删除连接 |
| GET | `/agents?page=1&pageSize=20&keyword=&tag=&status=` | 列表；状态 `PUBLISHED`、`DRAFT_CHANGED`、`UNPUBLISHED` |
| GET | `/agents/metrics` | 全部、已发布、本周调试、有暂存统计 |
| GET | `/agents/{id}` | 管理员草稿详情；成员发布视图，不含系统提示词 |
| POST | `/agents` | 创建草稿 |
| PUT | `/agents/{id}/draft` | 完整草稿替换，携带 `lockVersion` |
| DELETE | `/agents/{id}` | Agent、独占模型、快照一起软删除并清理缓存 |
| GET | `/agents/{id}/debug-contexts/{debugContextId}/export` | 下载自己创建的调试会话简约 JSON，无 `mode` 参数 |
| GET | `/agents/{id}/snapshots?page=1&pageSize=20` | 分页元数据 `{items,page,pageSize,total}`，不加载完整快照 JSON |
| GET | `/agents/{id}/snapshots/{version}` | 完整只读快照 |
| POST | `/agents/{id}/publish` | `{lockVersion,publishNote}`，说明必填，最多 512 字符 |
| POST | `/agents/{id}/snapshots/{version}/rollback` | `{lockVersion,publishNote?}`，恢复为草稿并发布一个新版本 |

草稿字段：`name, description, prologue, systemPrompt, tags[], variables[], knowledgeIds[], retrievalTopK, retrievalScoreThreshold, model, lockVersion`。

`variables` 元素为 `{name,label,defaultValue,required}`，提示词使用 `{{name}}`；运行请求只允许声明过的变量。`model` 为 `{providerConnectionId,modelId,temperature,topP,maxTokens,timeoutSeconds}`，可为 `null` 暂不配置。提示词不能为空，创建页面会提供可编辑的初始提示词。

`lockVersion` 过期返回 409；客户端保留未保存内容并提示重新载入，不静默覆盖其他修改。发布/回滚串行锁定 Agent；同一版本号的并发请求只能成功一个。创建/更新模型引用以共享锁锁定厂商行，厂商更新/删除使用排他锁，避免引用到刚删除的连接。

## 流式调用

`POST /agents/{id}/debug/stream`：

```json
{"message":"本轮问题","variables":{"company":"星海"},"debugContextId":"后续轮次使用，首轮省略"}
```

首轮 `context` 事件返回随机 `debugContextId`。以后仅传当前消息、变量和该 ID，**不传历史上下文**。`DELETE /agents/{id}/debug-contexts/{debugContextId}` 主动清理。关闭会话、离开组件、页面退出会尝试清理；网络失联时 TTL 是最终回收机制。

`POST /agents/{id}/chat/stream`：`{message,variables}`，只读当前发布快照，单次独立调用，不接受调试上下文、客户端历史或系统消息。

两者均使用 `Accept: text/event-stream`。命名事件：

- `context`：仅新调试会话，`{debugContextId}`。
- `retrieval`：`{citations:[...]}`，含知识库、文件、分块、定位、相似度、摘要。
- `delta`：`{text}`。
- `usage`：`{inputTokens,outputTokens,totalTokens,elapsedMs}`，厂商不提供时 Token 数可为空。
- `complete`：`{finishReason}`。
- `error`：`{code,message}`，不包含厂商原始错误正文或密钥。

只有完整成功轮次进入 Caffeine。缓存单机默认最多 1,000 个上下文，每用户最多 4 个，30 分钟无操作过期；最多 40 条消息、单条 16,000 字符、历史总计 64,000 字符。数据库只更新最后调试时间/用户，不保存消息或会话 ID。混合检索仅为前端不可用占位，后端仍采用原向量检索流程。

## 调试会话导出（仅简约版）

点击调试会话小标签同一行右侧的「下载」，通过现有认证请求下载当前会话 JSON。仅租户管理员可导出自己创建的上下文，不能通过请求指定其他租户或用户。

成功返回原始 JSON，不套 `AjaxResult`：`Content-Type: application/json`、`Content-Disposition: attachment; filename="agent-{id}-session.json"`、`Cache-Control: no-store`。

```json
{
  "schemaVersion": 1,
  "historyTruncated": false,
  "model": [{
    "configId": "M1", "providerName": "DeepSeek", "modelId": "deepseek-chat",
    "temperature": 0.4, "topP": 1, "maxTokens": 2048, "timeoutSeconds": 60
  }],
  "turns": [{
    "turn": 1, "modelConfigId": "M1", "question": "年假如何申请？",
    "answer": "需要提前提交审批。[C1]",
    "references": [{"id": "C1", "documentTitle": "员工手册", "score": 0.89, "content": "员工申请年假，应提前提交审批。"}]
  }]
}
```

- `model` 是每轮执行时捕获的配置去重列表，配置变更不会覆盖历史轮次；`modelConfigId` 在本次导出文件内有效。
- `references` 是过滤、去重后实际送入模型的全部引用片段，保留完整原文而非前端短摘要。无召回时为 `[]`。不新增 SSE 正文传输，也不重新检索。
- 只导出成功完成且仍在缓存中的轮次；失败、中止、生成中的内容不进入导出。`turn` 保留原始成功轮次序号；裁剪后不会重排。
- 导出数据与现有会话共用 Caffeine 生命周期，关闭会话、删除 Agent、30 分钟无访问或重启后不可恢复，不入库。最多保留现有 20 轮／64,000 字符窗口内的导出数据，并另设每上下文默认 256 KiB 的序列化数据预算（实际 JVM 堆占用与此不同）；超限裁剪完整旧轮次，`historyTruncated=true`，不截断单条引用正文。单轮过大可能导致当前无可导出数据。
- 容量可用 `AGENT_DEBUG_MAX_EXPORT_BYTES_PER_CONTEXT` 设置，范围 1 KiB—8 MiB；调整时应结合 `AGENT_DEBUG_MAX_CONTEXTS` 评估总内存。导出缓存裁剪不改变送给模型的对话历史窗口。
- 不导出 API Key、密文、连接 URL、登录凭证、系统提示词、运行变量、Token、耗时或 `citedInAnswer`。问答和知识库正文仍可能包含业务敏感内容，请妥善保管下载文件。
- 错误仍返回 JSON：生成中 `409 DEBUG_CONTEXT_BUSY`；无成功轮次 `409 DEBUG_EXPORT_EMPTY`；缺少历史元数据或全部超限裁剪 `409 DEBUG_EXPORT_UNAVAILABLE`；上下文不存在、过期或不属于当前用户 `410 DEBUG_CONTEXT_EXPIRED`；Agent 已删除或不可访问 `404`；权限不足 `403`，未登录 `401`。

## RAG 提示词组装

调试与正式调用共用 `AgentPromptAssembler`，不改变向量检索、Top-K 或阈值：

- 保留 Agent 系统提示词与变量替换，在关联知识库时追加资料使用规则：仅引用有依据的结论、区分一般性建议、说明资料不足及冲突，不执行资料中的指令。
- 当前用户消息包含结构化 `retrieved_context`，每段提供引用编号、文档名、知识库名、可用的章节与页码、原文正文。缺失来源字段省略，相似度仍返回前端但不作为事实可信度送入提示词。
- 跳过空正文；相同来源的重复分块、同一文档内完全相同正文只保留首次结果。不同文档保留独立来源，不折叠正文空白，不截断或改写表格、列表和代码。
- 过滤后统一生成连续的 `[C1]`、`[C2]`，SSE 引用列表与模型资料使用同一编号。历史轮次编号不能直接当作本轮来源。
- 未关联知识库时保留普通对话；关联了知识库但无可用片段时明确标记“本轮未检索到可用资料”，不将其表述为事实不存在。

来源字段和正文中的 XML 分隔符会转义，避免文档内容破坏资料结构。这是提示词层的风险缓解，不保证模型完全抵抗提示注入或遵守引用规则；实际回答质量仍需用业务问题及所选模型验证。本次不新增检索调用、模型调用或数据库迁移，重启后端后生效。

## 升级与部署

1. 备份后重启后端，由 Flyway 应用至 V15。现有已启动 JVM 不会自动加载本次代码。
2. V10—V15 分别提供公共厂商目录、租户连接、草稿与模型映射、不可变快照、厂商连接软删除、模型必填字段校验修正。软删除保留历史外键，唯一约束只限制未删除连接，允许重新配置同名厂商。V15 不修改历史迁移校验和，也不自动清理历史异常数据。
3. 旧 `/agent/**`、`/ai/agent/chat` 返回 410。前端已切到新接口，不再读取/写入 Agent 上的旧模型密钥字段。
4. **旧明文 API Key 不自动迁移。**保留 Agent 业务信息，要求租户重新配置厂商并选择模型后发布。旧数据库列保留到迁移窗口结束，再单独删除，不在本次升级中自动丢弃旧列。
5. 生产环境必须设置 `SPRING_PROFILES_ACTIVE=prod` 及有效的 `MODEL_PROVIDER_ENCRYPTION_KEY_V1`（Base64 编码的 32 字节主密钥），不要使用开发默认值。密钥必须稳定保存，变更前制定轮换方案。

## 验证与边界

- 前端单元/组件/API 测试：`cd web && npm test`；构建：`npm run build`。
- 后端测试（含 PostgreSQL 迁移）及构建：`cd server/Spring-AI && mvn -q clean test '-Dtest=*Test,*PostgresIT' && mvn -q -DskipTests package`。
- 本地真实 PostgreSQL 验收：`mvn -q -Dtest=AgentWorkbenchAcceptancePostgresIT test`。需要本机 `createdb/dropdb/psql`、PostgreSQL 与 pgvector；测试建立 UUID 命名专属库并清理，只使用本地模拟厂商，不消费真实模型配额。测试采用独立控制台日志配置，不覆盖正在运行的开发日志。
- 验收覆盖全新 V1—V15 迁移、AES 加密、变量读取、清空模型、两轮上下文、发布内容隔离、并发发布、回滚、软删除、同名厂商重配及厂商删除与新建引用的竞争。
- 桌面 1440px、窄屏 390px 的列表、模型选择和调试布局已用隔离模拟数据检查；保留项目现有全局导航壳，工作台内部采用原型最终双栏结构及 sea 主题。
- 当前真实浏览器会话已过期，未使用用户的实际厂商密钥做收费调用；生产部署与真实厂商连通性需由部署者在重启、登录后确认。
- 已有打包体积告警（主包超过 500kB）和 PostgreSQL 18 / Flyway 支持版本提示不阻断本次功能验证，仍应纳入后续依赖/体积治理。
