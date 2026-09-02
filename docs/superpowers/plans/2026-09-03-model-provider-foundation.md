# 模型厂商目录与密钥基础设施 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 建立可供后续租户厂商配置复用的公共厂商目录，以及绑定租户与厂商连接身份的 API Key 加密基础设施。

**Architecture:** Flyway V10 创建只读公共目录并初始化六个内置厂商，MyBatis 租户拦截器将该表视为全局表。独立的 `ModelProviderSecretCipher` 使用 AES-256-GCM、随机 96-bit nonce 和 `tenantId + providerId + keyVersion` AAD；版本化 Base64 主密钥由配置注入，生产环境拒绝开发默认值。

**Tech Stack:** Java 17、Spring Boot 3.3、MyBatis-Plus、Flyway、PostgreSQL JSONB、JCE AES/GCM、JUnit 5、AssertJ

**Spec:** `docs/superpowers/specs/2026-09-02-agent-workbench-backend-design.md`

## Global Constraints

- 本计划只实现模块 1，不创建 `tenant_model_provider`，不提供厂商配置 API，也不修改 Agent。
- 公共目录和租户配置必须分表；`model_provider_catalog` 不含 `tenant_id`、`status` 或 `enabled`。
- API Key 使用 AES-256-GCM；每次加密生成独立 nonce；AAD 固定绑定 `tenant_id + tenant_model_provider.id + api_key_version`。
- 主密钥不进入数据库和日志；仓库配置只允许不可用于生产的显式开发默认值；`prod` profile 使用默认值必须启动失败。
- 不复用面向入站凭证的 `ApiKeyCodec`。
- 在当前 `codex/agent-workbench-backend-design` 分支执行，不创建 Git worktree。

---

### Task 1: 公共厂商目录迁移与租户隔离

**Files:**
- Create: `server/Spring-AI/src/main/resources/db/V10__add_model_provider_catalog.sql`
- Create: `server/Spring-AI/src/test/java/com/starsea/ai/model/provider/V10ModelProviderCatalogMigrationPostgresIT.java`
- Create: `server/Spring-AI/src/test/java/com/starsea/ai/config/TenantLineHandlerImplTest.java`
- Modify: `server/Spring-AI/src/main/java/com/starsea/ai/config/TenantLineHandlerImpl.java`

**Interfaces:**
- Consumes: PostgreSQL 中 V1 已定义的 `update_timestamp()` trigger function。
- Produces: 公共表 `model_provider_catalog(id, code, name, icon, default_base_url, protocol_type, auth_type, suggested_models, create_time, update_time)`；`TenantLineHandlerImpl.ignoreTable("model_provider_catalog") == true`。

- [x] **Step 1: 写迁移和租户拦截的失败测试**

  PostgreSQL 集成测试在临时 schema 中先创建 `update_timestamp()`，执行 V10 SQL，再断言：恰有 `OPENAI/ANTHROPIC/DEEPSEEK/QWEN/ZHIPU/OLLAMA` 六个编码、所有 `suggested_models` 都是 JSON 数组、OpenAI 建议列表内 `modelId` 唯一、非法协议和对象类型 JSON 被数据库约束拒绝。租户拦截测试断言目录表被忽略，`tenant_model_provider` 不被忽略。

- [x] **Step 2: 运行测试并确认 RED**

  Run: `cd server/Spring-AI && mvn -q -Dtest=V10ModelProviderCatalogMigrationPostgresIT,TenantLineHandlerImplTest test`

  Expected: FAIL，因为 V10 资源不存在，且目录表尚未加入全局表白名单。

- [x] **Step 3: 实现最小迁移和白名单变更**

  SQL 创建 `code` 唯一、协议/认证枚举 CHECK、`jsonb_typeof(suggested_models) = 'array'` CHECK、更新时间 trigger，并用 `INSERT ... ON CONFLICT (code) DO UPDATE` 初始化六个厂商。建议模型只录入原型已明确的 OpenAI `gpt-4o-mini` 和 `gpt-4o`，其余厂商保持 `[]`，避免把会过期的模型清单固化为运行时真相。Ollama 使用 `auth_type='NONE'`，其余使用 `API_KEY`。

- [x] **Step 4: 运行测试并确认 GREEN**

  Run: `cd server/Spring-AI && mvn -q -Dtest=V10ModelProviderCatalogMigrationPostgresIT,TenantLineHandlerImplTest test`

  Expected: PASS。

### Task 2: 版本化密钥配置校验

**Files:**
- Create: `server/Spring-AI/src/main/java/com/starsea/ai/model/provider/ModelProviderEncryptionProperties.java`
- Create: `server/Spring-AI/src/test/java/com/starsea/ai/model/provider/ModelProviderEncryptionPropertiesTest.java`
- Modify: `server/Spring-AI/src/main/resources/application.yml`

**Interfaces:**
- Consumes: `model-provider.encryption.active-key-version` 和 `model-provider.encryption.keys.<version>`；每个值为 Base64 编码的 32-byte AES key。
- Produces: `String getActiveKeyVersion()`、`SecretKey requiredKey(String version)`、`boolean usesDevelopmentDefault(String version)`；Bean 初始化时完成配置校验。

- [x] **Step 1: 写配置校验失败测试**

  覆盖 active version 缺失、active version 找不到 key、非法 Base64、解码后不是 32 bytes、`prod` profile 使用开发默认值，以及合法 key/version 返回 `AES` `SecretKey`。

- [x] **Step 2: 运行测试并确认 RED**

  Run: `cd server/Spring-AI && mvn -q -Dtest=ModelProviderEncryptionPropertiesTest test`

  Expected: FAIL，因为属性类不存在。

- [x] **Step 3: 实现属性类和开发配置**

  `ModelProviderEncryptionProperties` 使用 `@Component`、`@ConfigurationProperties(prefix = "model-provider.encryption")`、`EnvironmentAware` 和 `@PostConstruct`。校验时使用严格 Base64 decoder，所有 key 必须恰好 32 bytes；active version 必须存在；`environment.acceptsProfiles(Profiles.of("prod"))` 且 active key 等于 `DEVELOPMENT_DEFAULT_KEY` 时抛出不含密钥内容的 `IllegalStateException`。`application.yml` 添加：

  ```yaml
  model-provider:
    encryption:
      active-key-version: v1
      keys:
        v1: ${MODEL_PROVIDER_ENCRYPTION_KEY_V1:<32-byte开发默认值的Base64>}
  ```

- [x] **Step 4: 运行测试并确认 GREEN**

  Run: `cd server/Spring-AI && mvn -q -Dtest=ModelProviderEncryptionPropertiesTest test`

  Expected: PASS。

### Task 3: AES-256-GCM 密钥加解密

**Files:**
- Create: `server/Spring-AI/src/main/java/com/starsea/ai/model/provider/EncryptedProviderSecret.java`
- Create: `server/Spring-AI/src/main/java/com/starsea/ai/model/provider/ModelProviderSecretCipher.java`
- Create: `server/Spring-AI/src/main/java/com/starsea/ai/model/provider/AesGcmModelProviderSecretCipher.java`
- Create: `server/Spring-AI/src/test/java/com/starsea/ai/model/provider/AesGcmModelProviderSecretCipherTest.java`

**Interfaces:**
- Consumes: `ModelProviderEncryptionProperties.requiredKey(version)`。
- Produces: `EncryptedProviderSecret encrypt(long tenantId, long providerId, String plaintext)` 和 `String decrypt(long tenantId, long providerId, EncryptedProviderSecret secret)`；存储对象仅含 `ciphertext`、`nonce`、`keyVersion`、`lastFour`。

- [x] **Step 1: 写加解密失败测试**

  使用固定 32-byte 测试 key 构造真实属性对象，覆盖：往返还原；相同明文连续加密得到不同 nonce/密文；换 tenantId 或 providerId 无法解密；篡改密文无法解密；未知 key version 无法解密；空白密钥、非正 ID、格式错误 Base64 被拒绝；末四位正确且异常信息不含测试明文。

- [x] **Step 2: 运行测试并确认 RED**

  Run: `cd server/Spring-AI && mvn -q -Dtest=AesGcmModelProviderSecretCipherTest test`

  Expected: FAIL，因为 cipher 接口和实现不存在。

- [x] **Step 3: 实现最小安全组件**

  使用 `AES/GCM/NoPadding`、128-bit authentication tag、`SecureRandom` 生成 12-byte nonce、标准 Base64 存储。AAD 使用 UTF-8 文本 `tenantId=<id>;providerId=<id>;keyVersion=<version>`。解密认证失败统一抛出不携带底层明文或密钥的 `IllegalArgumentException("Unable to decrypt model provider secret")`；组件不缓存明文。

- [x] **Step 4: 运行测试并确认 GREEN**

  Run: `cd server/Spring-AI && mvn -q -Dtest=AesGcmModelProviderSecretCipherTest test`

  Expected: PASS。

### Task 4: 模块级验证、文档状态与提交

**Files:**
- Modify: `docs/superpowers/specs/2026-09-02-agent-workbench-backend-design.md`
- Modify: `docs/superpowers/plans/2026-09-03-model-provider-foundation.md`

**Interfaces:**
- Consumes: Tasks 1—3 的数据库和 Java 契约。
- Produces: 已验证、可供模块 2 直接引用的模块 1 基线。

- [x] **Step 1: 运行模块测试和全量后端测试**

  Run: `cd server/Spring-AI && mvn -q -Dtest='com.starsea.ai.model.provider.*,com.starsea.ai.config.TenantLineHandlerImplTest' test`

  Run: `cd server/Spring-AI && mvn -q test`

  Expected: 全部 PASS。

- [x] **Step 2: 运行仓库要求的生产构建**

  Run: `cd server/Spring-AI && mvn -q -DskipTests package`

  Expected: exit code 0。

- [x] **Step 3: 更新实施状态并检查差异**

  将设计文档模块 1 标记为已完成，将本计划全部 checkbox 标记为完成。运行 `git diff --check` 和 `git status --short`，确认没有日志、密钥、构建产物或无关文件。

- [x] **Step 4: 提交一个连贯变更**

  ```bash
  git add docs/superpowers/plans/2026-09-03-model-provider-foundation.md \
    docs/superpowers/specs/2026-09-02-agent-workbench-backend-design.md \
    server/Spring-AI/src/main/java/com/starsea/ai/config/TenantLineHandlerImpl.java \
    server/Spring-AI/src/main/java/com/starsea/ai/model/provider \
    server/Spring-AI/src/main/resources/application.yml \
    server/Spring-AI/src/main/resources/db/V10__add_model_provider_catalog.sql \
    server/Spring-AI/src/test/java/com/starsea/ai/config/TenantLineHandlerImplTest.java \
    server/Spring-AI/src/test/java/com/starsea/ai/model/provider
  git commit -m "feat: 完成模型厂商目录与密钥基础设施"
  ```

  提交后运行 `git status --short`，预期为空。
