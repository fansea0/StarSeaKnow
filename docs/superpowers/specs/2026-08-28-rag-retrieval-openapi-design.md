# RAG Retrieval OpenAPI 对外开放设计

**日期**：2026-08-28

**状态**：已确认

**范围**：面向外部服务端 Agent 开放知识库语义检索能力；不包含 LLM 生成、会话、文件写入和知识库管理能力

---

## 1. 背景

当前平台已经具备 Spring AI、pgvector、知识库/文件管理、JWT 登录和租户隔离，但现有 `/ai/knowledge/chat` 是内部用户接口：它依赖用户 JWT，直接返回流式文本，检索参数固定，缺少稳定的外部契约、机器凭证、知识库授权、限流和密钥生命周期管理。

本设计新增独立的 Retrieval OpenAPI。外部 Agent 使用服务端 API Key 提问，平台只返回相关知识片段、相似度和来源信息。API 不调用 LLM，也不维护对话状态。

## 2. 已确认的设计决策

| 决策点 | 选择 |
|---|---|
| 首期能力 | 只开放 Retrieval API |
| 调用方 | 服务端到服务端，不允许浏览器保存 API Key |
| 认证 | `Authorization: Bearer <api-key>` |
| API Key 权限 | 绑定租户，并关联一组允许检索的知识库 |
| 知识库选择 | 请求不传 `knowledge_id`；始终检索当前 Key 的完整授权范围 |
| 请求参数 | 只支持 `query` 和 `retrieval_setting` |
| Metadata Filter | 首期不支持 |
| 检索方式 | 向量语义检索；不做混合检索和 rerank |
| 密钥存储 | 公开 Key ID + 高熵 Secret；数据库只存 HMAC 摘要 |
| Pepper | `application.yml` 引用环境变量，允许开发默认值，支持多版本 |
| 缓存 | 通过抽象接口使用，首期 Caffeine，未来可扩展 Redis/多级缓存 |
| 数据模型 | 保留 `api_credential` 与 `api_credential_knowledge` 两张核心表 |
| 请求审计表 | 不建设 `external_api_request_audit`；使用结构化安全日志和 Metrics |
| 外部文档 | 单独提供人读调用文档和机器可读 OpenAPI 3.1 契约 |
| 当前传输 | 可信内网 HTTP 仅用于测试 Key；生产 Key 预留 HTTPS 强制开关 |

## 3. 目标与非目标

### 3.1 目标

1. 为外部 Agent 提供版本稳定、可生成 SDK 的检索接口。
2. API Key 全生命周期由租户管理员管理：创建、授权、轮换、吊销、禁用和过期。
3. 即使调用方构造恶意请求，也不能扩大租户或知识库访问范围。
4. 数据库不保存可直接使用的 API Key，日志不泄露 Key、Query 或知识片段。
5. 热缓存认证只执行一次 Key 解析、一次缓存读取和一次 HMAC 校验。
6. 缓存、限流和传输安全可以在不改变外部 API 的前提下逐步升级。

### 3.2 非目标

- LLM 答案生成、对话历史、Agent 工具执行。
- OAuth2、HMAC 请求签名和 mTLS。
- 外部文档上传、分段管理或知识库写操作。
- 客户端指定某个知识库或临时缩小知识库范围。
- `metadata_condition` 或任意 pgvector filter expression。
- 首期严格兼容 Dify External Knowledge API。Dify 强制传递 `knowledge_id`，本接口不接受该字段；未来如有需要，可在核心检索服务之前增加独立 Dify Adapter，不能把 `knowledge_id` 引入核心权限模型。

## 4. 总体架构

```text
External Agent
    │ Authorization: Bearer rag_test_...
    ▼
ExternalApiKeyFilter
    ├─ Header/格式/长度校验
    ├─ 解析一次 key_id 与 secret
    ├─ ApiCredentialResolver
    │    ├─ ApiCredentialCache
    │    └─ ApiCredentialRepository
    ├─ HMAC-SHA-256 + 常量时间比较
    ├─ 状态/过期/IP/环境检查
    └─ 创建 EXTERNAL_API AuthContext
    ▼
CredentialRateLimiter
    ▼
POST /openapi/v1/retrieval
    ├─ 校验 query/retrieval_setting
    ├─ 从 AuthContext 取得 tenantId 与 allowedKnowledgeIds
    ├─ 构造服务端固定过滤条件
    └─ RetrievalService
         ▼
Embedding Model → pgvector → 来源信息补全 → threshold → global Top K
```

### 4.1 与现有认证体系的关系

现有 `AuthContext.Kind` 增加 `EXTERNAL_API`。外部上下文至少包含：

```text
principalId       = credentialId
tenantId
credentialId
environment
allowedKnowledgeIds
allowedIpCidrs
rateLimitPolicy
authorizationVersion
```

路由隔离规则：

- `JwtAuthFilter` 跳过 `/openapi/v1/**`。
- `ExternalApiKeyFilter` 只处理 `/openapi/v1/**`。
- `@RequireLogin` 和内部管理 Controller 明确拒绝 `EXTERNAL_API`。
- Retrieval Controller 明确只接受 `EXTERNAL_API`。
- `TenantLineHandlerImpl` 继续从认证上下文获取 `tenantId`。
- Filter 必须在 `finally` 中清理 ThreadLocal。

## 5. API Key 设计

### 5.1 格式

```text
rag_test_k_7F3K9Q2M.xQ9v...43字符随机Secret
rag_live_k_8P2H6ABC.aB7d...43字符随机Secret
```

- `rag_test` / `rag_live` 表示环境。
- `k_7F3K9Q2M` 是公开且唯一的 Key ID，用于索引和缓存定位。
- Secret 使用密码学安全随机数生成 32 字节，再做 Base64URL 无填充编码。
- 完整 Key 只在创建或轮换成功时返回一次。
- 管理页面和后续查询只显示前缀与末四位。
- 不依赖固定总长度；认证 Header 设置合理最大长度。

### 5.2 摘要与 Pepper

```text
secret_digest = HMAC-SHA-256(
  pepper[pepper_version],
  key_id + "." + secret
)
```

数据库不保存原始 Secret 或可逆密文。校验摘要时使用常量时间比较。

配置结构：

```yaml
external-api:
  api-key:
    active-pepper-version: v1
    peppers:
      v1: ${RAG_API_KEY_PEPPER_V1:dev-only-rag-pepper-v1-change-me-32bytes}
```

轮换示例：

```yaml
external-api:
  api-key:
    active-pepper-version: v2
    peppers:
      v1: ${RAG_API_KEY_PEPPER_V1:dev-only-rag-pepper-v1-change-me-32bytes}
      v2: ${RAG_API_KEY_PEPPER_V2:dev-only-rag-pepper-v2-change-me-32bytes}
```

规则：

1. 新 Key 始终使用 active 版本。
2. 校验旧 Key 时按数据库中的 `pepper_version` 选择 Pepper。
3. 应用启动时校验 active 版本存在且 Pepper 不少于 32 字节。
4. 使用源码默认 Pepper 时只能创建 `rag_test_*`，创建 `rag_live_*` 必须失败。
5. 数据库仍存在某版本的有效 Key 时，不得删除该版本 Pepper。
6. Pepper 加载为不可变内存配置，请求中不重复解析 YAML。
7. Pepper 缺失属于服务端配置错误；内部记录原因，对外不暴露版本信息。

## 6. 数据模型

### 6.1 `api_credential`

| 字段 | 说明 |
|---|---|
| `id` | 内部主键 |
| `public_id` | UUID，管理 API 使用 |
| `tenant_id` | 所属租户 |
| `name` | 调用方名称 |
| `description` | 用途说明 |
| `key_id` | 唯一索引，可公开 |
| `secret_digest` | HMAC-SHA-256 摘要 |
| `pepper_version` | `v1`、`v2` 等 |
| `environment` | `test` / `live` |
| `status` | `active` / `disabled` / `revoked` / `expired` |
| `expires_at` | 可空；Key 过期时间 |
| `allowed_ip_cidrs` | 可空；允许来源网段 |
| `requests_per_minute` | 每分钟请求上限 |
| `burst_capacity` | 突发容量 |
| `max_concurrency` | 最大并发 |
| `authorization_version` | 授权变更版本，用于缓存失效 |
| `display_prefix` | 安全展示前缀 |
| `display_last_four` | 安全展示末四位 |
| `rotated_from_id` | 可空；轮换来源 Key |
| `created_by` | 创建的租户管理员 |
| `created_at` | 创建时间 |
| `revoked_at` | 吊销时间 |
| `last_used_at` | 聚合更新的最近使用时间 |
| `last_used_ip` | 聚合更新的最近来源 IP |

### 6.2 `api_credential_knowledge`

```text
tenant_id
credential_id
knowledge_id
created_at
PRIMARY KEY (credential_id, knowledge_id)
```

必须使用包含 `tenant_id` 的约束或等效数据库约束，确保无法创建跨租户授权关系。保留独立关系表而不使用 JSON/数组，原因是需要外键完整性、删除清理、反向查询、并发安全更新和精确缓存失效。

### 6.3 不建设请求审计表

首期不创建 `external_api_request_audit`。替代方案：

- 创建、轮换、禁用和吊销写结构化安全日志。
- 请求量、延迟、401、403、429、5xx 使用 Metrics。
- `last_used_at` 与 `last_used_ip` 异步聚合更新，不能每请求写库。
- 不记录 Authorization、Secret、digest、Query 原文和返回知识片段。

## 7. 缓存抽象

业务认证逻辑不得直接依赖 Caffeine 或 Redis。

```text
ApiCredentialAuthenticator
        ▼
ApiCredentialResolver
   ├─ ApiCredentialCache
   └─ ApiCredentialRepository
```

抽象能力：

```java
interface ApiCredentialCache {
    CachedCredentialResult get(String keyId);
    void putValid(String keyId, CachedCredential credential);
    void putMissing(String keyId);
    void evict(String keyId);
    void evictTenant(Long tenantId);
}
```

`CachedCredentialResult` 必须区分未缓存、有效记录和负缓存命中。`CachedCredential` 包含认证与授权所需的不可变快照，但不包含原始 Secret。

实现演进：

```text
CaffeineApiCredentialCache     首期
RedisApiCredentialCache        后续共享缓存
CompositeApiCredentialCache    后续 L1 Caffeine + L2 Redis
```

首期默认：

- 有效记录 TTL 60 秒。
- 不存在的 Key ID 负缓存 10 秒。
- 创建、授权变更、禁用、轮换和吊销时主动失效。
- `authorization_version` 防止旧授权快照继续使用。
- 热缓存认证成本为一次 Header 解析、一次缓存读取、一次 HMAC 和一次常量时间比较。

## 8. Retrieval API 契约

### 8.1 Endpoint

```http
POST /openapi/v1/retrieval
Authorization: Bearer <rag-api-key>
Content-Type: application/json
X-Request-ID: optional-client-request-id
```

### 8.2 请求

```json
{
  "query": "退款需要提供哪些材料？",
  "retrieval_setting": {
    "top_k": 5,
    "score_threshold": 0.5
  }
}
```

Schema 必须设置 `additionalProperties: false`。`knowledge_id`、`metadata_condition` 和其他未知字段均不属于 v1 契约，并返回 `400 invalid_request`。

| 字段 | 规则 |
|---|---|
| `query` | 必填；trim 后 1～250 字符 |
| `retrieval_setting` | 可选；缺省时使用服务端默认值 |
| `top_k` | 可选；默认 5；范围 1～20 |
| `score_threshold` | 可选；默认 0；范围 0～1 |
| 请求体 | 最大 32 KB，UTF-8 JSON |

空查询或非法参数在调用 Embedding 前返回 400。

### 8.3 授权范围与全局 Top K

请求不能选择知识库。服务端从已认证 Credential 的授权快照取得完整 `allowedKnowledgeIds`：

```text
tenantId = authenticatedTenant
AND knowledgeId IN allowedKnowledgeIds
AND fileStatus = enabled
```

- 空授权集合返回 `403 credential_has_no_knowledge_scope`，绝不能解释为租户全部知识库。
- 单 Credential 可关联的知识库数量设置可配置上限，默认 50。
- pgvector 在全部授权知识库范围内执行一次查询，返回全局 Top K。
- `score_threshold` 在分数归一化后应用。
- 调用方不能通过 Query 或请求字段扩大、缩小或覆盖授权范围。

### 8.4 成功响应

```json
{
  "records": [
    {
      "content": "退款申请需要提交订单号、付款凭证以及退款原因。",
      "score": 0.92,
      "title": "售后服务说明.pdf",
      "metadata": {
        "document_id": "65c28997-ad56-4812-8a50-e00e804b45cc",
        "chunk_id": "a3c05b1e-c40b-4eb6-8a81-26f9874f6f7b",
        "file_type": "pdf",
        "page_number": 3,
        "chunk_index": 12
      }
    }
  ]
}
```

规则：

- 无命中返回 `200 {"records":[]}`。
- `content`、`score`、`title` 始终存在。
- `metadata` 始终为对象，不能为 `null`。
- `score` 归一化为 `[0,1]` 并降序返回。
- 不返回租户 ID、内部 Long ID、文件系统路径或模型配置。
- 单 Chunk 最多返回 8 KB，整体响应最多 128 KB；超限时从低分结果开始裁减。
- 响应包含 `Cache-Control: no-store`。

响应 Header：

```http
X-Request-ID: req_01...
X-RateLimit-Limit: 60
X-RateLimit-Remaining: 42
X-RateLimit-Reset: 1787890000
Cache-Control: no-store
```

## 9. 错误契约

```json
{
  "request_id": "req_01...",
  "error": {
    "code": "invalid_request",
    "message": "retrieval_setting.top_k must be between 1 and 20",
    "param": "retrieval_setting.top_k"
  }
}
```

| HTTP | Code | 场景 |
|---:|---|---|
| 400 | `invalid_authorization_header` | Bearer Header 格式错误 |
| 400 | `invalid_request` | JSON、字段或参数非法 |
| 401 | `authentication_failed` | Key 无效、过期或已吊销 |
| 403 | `credential_disabled` | Credential 被禁用 |
| 403 | `ip_not_allowed` | 来源 IP 不在白名单 |
| 403 | `credential_has_no_knowledge_scope` | Key 未关联知识库 |
| 413 | `request_too_large` | 请求体超过上限 |
| 429 | `rate_limit_exceeded` | 频率、突发或并发超限 |
| 503 | `retrieval_unavailable` | Embedding 或 pgvector 暂时不可用 |
| 504 | `retrieval_timeout` | 检索超时 |
| 500 | `internal_error` | 未知服务端错误 |

安全规则：

- 无效、过期、吊销 Key 统一返回 `authentication_failed`，不泄露具体状态。
- 5xx 不返回类名、SQL、路径和堆栈。
- 429 返回 `Retry-After`。
- 所有响应都返回 `X-Request-ID`。

## 10. 限流与资源保护

首期默认测试策略：

```text
60 requests/minute
burst capacity 10
max concurrency 5
top_k max 20
request timeout 10 seconds
```

规则：

- 已认证请求按 `credentialId` 限流，不按原始 Key。
- 未认证请求按来源 IP 使用更低阈值。
- 限制连续无效 Key ID，负缓存避免反复查询数据库。
- Credential 限制与租户/平台全局上限取最严格值。
- 授权、限流和参数校验必须在 Embedding 调用之前完成。
- Embedding 和 pgvector 分别设置超时。

## 11. 租户管理员接口

管理接口继续使用现有业务 JWT，并要求 `tenant_admin`。

| 方法 | 路径 | 用途 |
|---|---|---|
| POST | `/tenant/api-credentials` | 创建 Credential、关联知识库并返回一次完整 Key |
| GET | `/tenant/api-credentials` | 分页查询 Credential |
| GET | `/tenant/api-credentials/{credentialId}` | 查看状态、策略和知识库授权 |
| PATCH | `/tenant/api-credentials/{credentialId}` | 修改名称、状态、过期、IP 和限流策略 |
| PUT | `/tenant/api-credentials/{credentialId}/knowledge-bases` | 原子替换知识库授权集合 |
| POST | `/tenant/api-credentials/{credentialId}/rotate` | 创建新 Credential/Key，并复制策略和授权 |
| POST | `/tenant/api-credentials/{credentialId}/revoke` | 吊销 Key |
| GET | `/tenant/api-credentials/{credentialId}/usage` | 返回 Metrics 聚合的基础使用情况 |

创建和轮换响应必须设置 `Cache-Control: no-store`。不存在恢复明文 Key 的接口；丢失后只能轮换。

轮换创建一条新的 `api_credential` 记录，通过 `rotated_from_id` 关联旧记录，并复制 `api_credential_knowledge`。允许设置短暂重叠期，旧 Key 到期后自动吊销。

## 12. HTTP 与 HTTPS 策略

当前无域名阶段：

- 仅允许 `rag_test_*`。
- 默认只监听 localhost、可信局域网或 VPN。
- 不接入敏感知识库。
- 测试 Key 使用较短有效期和较低配额。
- 不允许公网 HTTP。

未来生产配置预留：

```yaml
external-api:
  require-https: true
  trusted-proxies:
    - 10.0.0.10/32
```

- 只有来自可信代理的请求才读取 `Forwarded` / `X-Forwarded-Proto`。
- `rag_live_*` 请求在 `require-https=true` 时必须通过 HTTPS。
- Caddy/Nginx 负责 TLS，Spring Boot 不直接暴露公网。
- 数据库摘要只解决静态存储泄露，不能保护 HTTP 传输中的 Bearer Key。

## 13. 独立外部调用文档

实现阶段新增：

```text
docs/openapi/retrieval-api.md
docs/openapi/retrieval-api.yaml
```

`retrieval-api.md` 面向接入开发者，包含：

- 接口地址、版本和认证方法。
- Key 只展示一次、只能保存在服务端的说明。
- 请求、响应、错误码和 Rate Limit Header。
- cURL、Java、Python、JavaScript 示例。
- HTTP 测试模式与 HTTPS 生产要求。
- Key 轮换和常见错误排查。
- 与 Dify 的差异：本接口不接收 `knowledge_id`。

`retrieval-api.yaml` 使用 OpenAPI 3.1，至少包含：

```yaml
components:
  securitySchemes:
    RagApiKey:
      type: http
      scheme: bearer
      bearerFormat: RAG-API-Key
security:
  - RagApiKey: []
```

服务端可只读暴露：

```text
GET /openapi/specs/retrieval-v1.yaml
GET /openapi/docs/retrieval
```

文档不得包含真实 API Key、内部管理 API 或敏感部署信息。

## 14. 现有 RAG 代码改造要求

1. 将现有固定 `topK(1)` 改为受边界约束的动态 `top_k`。
2. RetrievalService 返回 score、chunk ID 和文档来源，而不是仅返回 Document 文本。
3. 向量化 metadata 至少包含：

```text
tenantId
knowledgeId
fileId
documentPublicId
chunkId
chunkIndex
pageNumber
fileType
```

4. 文件名等可变信息在检索后按 `fileId` 批量补全，避免依赖陈旧向量 metadata。
5. pgvector 最终过滤必须同时包含认证租户和 Credential 授权知识库集合。
6. 外部错误处理使用独立异常映射，不能复用内部 `AjaxResult` 契约。
7. 当前 `application.yml` 中的模型 API Key 也应迁移到环境变量；若为真实 Key，应在对外开放前轮换。

## 15. 测试与验收

### 15.1 API Key 与配置

- 创建后数据库、缓存和日志不存在原始 Key。
- v1/v2 Pepper Key 可同时验证。
- 默认 Pepper 不能创建 live Key。
- 缺失 active Pepper、短 Pepper 和未知版本启动/认证行为符合设计。
- Header 格式、超长 Header、重复 Authorization Header和常量时间比较测试。

### 15.2 权限与租户隔离

- 请求不能提交 `knowledge_id` 或 `metadata_condition`。
- 空知识库授权返回 403，而不是查询租户全部知识库。
- 一次检索覆盖当前 Credential 的完整授权集合并返回全局 Top K。
- 跨租户关联在服务层和数据库约束层均失败。
- API Key 不能调用内部 JWT API，JWT 也不能调用 Retrieval OpenAPI。
- 未授权或非法请求不会调用 Embedding。

### 15.3 缓存与生命周期

- Caffeine 实现只通过 `ApiCredentialCache` 被使用。
- 正缓存、负缓存、主动失效和授权版本测试。
- 授权修改、禁用、轮换、吊销后旧缓存不能继续授权。
- Cache 接口可由测试 Redis Fake 替换，业务代码无需修改。

### 15.4 接口与资源保护

- Query、top_k、threshold、请求体和响应体边界测试。
- 未知字段被拒绝。
- 无结果返回 200 空数组。
- score 范围、排序、threshold 和来源信息正确。
- 限流、并发限制、`Retry-After` 和 Rate Limit Header 正确。
- Embedding/pgvector 超时映射为 503/504。
- 日志不包含 Authorization、Query 和知识片段。
- OpenAPI Schema 与真实请求/响应进行契约测试。

## 16. 实施顺序

1. 数据库迁移：`api_credential`、`api_credential_knowledge` 和必要的资源 UUID。
2. Pepper 多版本配置、Key 生成、摘要校验和认证上下文。
3. `ApiCredentialCache` 抽象及 Caffeine 实现。
4. 租户管理员 Credential 创建、授权、轮换和吊销接口。
5. RetrievalService 重构：动态参数、全授权范围、global Top K、score 和来源。
6. `/openapi/v1/retrieval`、独立错误契约、限流和 Metrics。
7. `retrieval-api.md` 与 OpenAPI 3.1 YAML。
8. 安全、隔离、缓存、性能和契约测试。
9. 可信内网 HTTP 测试发布。
10. 域名、自动证书和 HTTPS 生产发布。

## 17. 完成标准

以下条件全部满足才视为 Retrieval OpenAPI 可交付：

- 外部 Agent 仅凭测试 API Key 可在可信内网完成检索。
- 请求只包含 `query` 和 `retrieval_setting`，不能选择知识库。
- 查询范围严格等于当前 Credential 的知识库授权集合。
- 数据库不保存可用 Key，默认 Pepper 无法签发 live Key。
- 缓存实现可替换，业务认证逻辑不依赖 Caffeine。
- 轮换、吊销、禁用、过期、IP 和限流生效。
- 租户隔离、空授权和跨租户测试通过。
- 独立调用文档和 OpenAPI 3.1 契约可供外部团队直接接入。
