# RAG Retrieval OpenAPI Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为外部服务端 Agent 提供安全、租户隔离、按 API Key 全知识库授权范围检索的 RAG Retrieval OpenAPI，并交付独立接入文档和 OpenAPI 3.1 契约。

**Architecture:** 使用通用 `api_credential` 保存机器凭证身份与生命周期，使用 RAG 专用 `api_credential_knowledge` 保存知识库 Scope。外部 Bearer Key 只在 Filter 中解析一次，经可替换缓存、HMAC 和类型校验生成 `EXTERNAL_API` 上下文；Retrieval 端点从该上下文取得完整知识库范围，执行一次跨库全局 Top K pgvector 检索。

**Tech Stack:** Java 17、Spring Boot 3.3.10、Spring AI 1.0.0-M6、MyBatis-Plus 3.5.6、PostgreSQL/Flyway、pgvector、Caffeine、JUnit 5、Mockito、MockMvc、OpenAPI 3.1

**Spec:** `docs/superpowers/specs/2026-08-28-rag-retrieval-openapi-design.md`

## Global Constraints

- 外部请求只允许 `query` 和 `retrieval_setting`；拒绝 `knowledge_id`、`metadata_condition` 和所有未知字段。
- 请求不能选择知识库；检索范围严格等于当前 `RAG_RETRIEVAL` Credential 的全部知识库授权，空授权返回 403。
- `api_credential` 是通用凭证表，`credential_type` 使用 `VARCHAR(32)` 且创建后不可修改。
- `api_credential_knowledge` 只能关联 `credential_type='RAG_RETRIEVAL'`，并使用组合外键阻止跨类型、跨租户关联。
- 完整 API Key 只在创建或轮换时返回一次；数据库、缓存和日志不得保存原始 Key。
- Pepper 从 `application.yml` 引用环境变量，允许开发默认值，支持多版本；默认 Pepper 禁止签发 live Key。
- 缓存业务只依赖 `ApiCredentialCache`；首期 Caffeine，不能让认证服务直接引用 Caffeine API。
- 当前 HTTP 只允许可信内网测试 Key；公网生产与 live Key 必须留出 HTTPS 强制开关。
- 不建设请求审计表；生命周期事件写结构化日志，请求数据只进 Metrics，禁止记录 Query 和知识片段。
- 每个任务使用 TDD：先看到目标失败，再做最小实现，再运行相关回归测试，最后独立提交。

---

## File Structure

### Database and persistence

- Create: `xiaoda-backend-intelligence/Spring-AI/src/main/resources/db/V4__add_external_api_credentials.sql`
- Create: `xiaoda-backend-intelligence/Spring-AI/src/main/java/com/fansea/ai/openapi/credential/ApiCredential.java`
- Create: `xiaoda-backend-intelligence/Spring-AI/src/main/java/com/fansea/ai/openapi/credential/ApiCredentialKnowledge.java`
- Create: `xiaoda-backend-intelligence/Spring-AI/src/main/java/com/fansea/ai/openapi/credential/ApiCredentialMapper.java`
- Create: `xiaoda-backend-intelligence/Spring-AI/src/main/java/com/fansea/ai/openapi/credential/ApiCredentialKnowledgeMapper.java`
- Modify: `xiaoda-backend-intelligence/Spring-AI/src/main/java/com/fansea/ai/domain/Knowledge.java`
- Modify: `xiaoda-backend-intelligence/Spring-AI/src/main/java/com/fansea/ai/domain/File.java`

### Credential configuration, cryptography, cache, and authentication

- Create: `xiaoda-backend-intelligence/Spring-AI/src/main/java/com/fansea/ai/openapi/credential/ApiKeyProperties.java`
- Create: `xiaoda-backend-intelligence/Spring-AI/src/main/java/com/fansea/ai/openapi/credential/CredentialType.java`
- Create: `xiaoda-backend-intelligence/Spring-AI/src/main/java/com/fansea/ai/openapi/credential/ApiKeyCodec.java`
- Create: `xiaoda-backend-intelligence/Spring-AI/src/main/java/com/fansea/ai/openapi/credential/ApiCredentialCache.java`
- Create: `xiaoda-backend-intelligence/Spring-AI/src/main/java/com/fansea/ai/openapi/credential/CaffeineApiCredentialCache.java`
- Create: `xiaoda-backend-intelligence/Spring-AI/src/main/java/com/fansea/ai/openapi/credential/CredentialScopeSnapshot.java`
- Create: `xiaoda-backend-intelligence/Spring-AI/src/main/java/com/fansea/ai/openapi/credential/RagKnowledgeScopeSnapshot.java`
- Create: `xiaoda-backend-intelligence/Spring-AI/src/main/java/com/fansea/ai/openapi/credential/CredentialAuthenticationException.java`
- Create: `xiaoda-backend-intelligence/Spring-AI/src/main/java/com/fansea/ai/openapi/credential/ApiCredentialResolver.java`
- Create: `xiaoda-backend-intelligence/Spring-AI/src/main/java/com/fansea/ai/openapi/auth/ExternalApiKeyFilter.java`
- Create: `xiaoda-backend-intelligence/Spring-AI/src/main/java/com/fansea/ai/openapi/auth/ExternalApiFilterConfig.java`
- Create: `xiaoda-backend-intelligence/Spring-AI/src/main/java/com/fansea/ai/openapi/auth/ExternalApiTransportProperties.java`
- Create: `xiaoda-backend-intelligence/Spring-AI/src/main/java/com/fansea/ai/openapi/auth/ClientIpResolver.java`
- Create: `xiaoda-backend-intelligence/Spring-AI/src/main/java/com/fansea/ai/openapi/auth/IpCidrMatcher.java`
- Create: `xiaoda-backend-intelligence/Spring-AI/src/main/java/com/fansea/ai/openapi/error/ExternalApiException.java`
- Create: `xiaoda-backend-intelligence/Spring-AI/src/main/java/com/fansea/ai/openapi/error/ExternalApiExceptionHandler.java`
- Modify: `xiaoda-backend-intelligence/Spring-AI/src/main/java/com/fansea/ai/auth/AuthContext.java`
- Modify: `xiaoda-backend-intelligence/Spring-AI/src/main/java/com/fansea/ai/auth/AuthAspect.java`
- Modify: `xiaoda-backend-intelligence/Spring-AI/src/main/java/com/fansea/ai/auth/JwtAuthFilter.java`

### Tenant management and public retrieval

- Create: `xiaoda-backend-intelligence/Spring-AI/src/main/java/com/fansea/ai/openapi/credential/ApiCredentialService.java`
- Create: `xiaoda-backend-intelligence/Spring-AI/src/main/java/com/fansea/ai/openapi/credential/TenantApiCredentialController.java`
- Create: `xiaoda-backend-intelligence/Spring-AI/src/main/java/com/fansea/ai/openapi/retrieval/RetrievalQuery.java`
- Create: `xiaoda-backend-intelligence/Spring-AI/src/main/java/com/fansea/ai/openapi/retrieval/RetrievedChunk.java`
- Create: `xiaoda-backend-intelligence/Spring-AI/src/main/java/com/fansea/ai/openapi/retrieval/RetrievalRequestParser.java`
- Create: `xiaoda-backend-intelligence/Spring-AI/src/main/java/com/fansea/ai/openapi/retrieval/ExternalRetrievalController.java`
- Create: `xiaoda-backend-intelligence/Spring-AI/src/main/java/com/fansea/ai/openapi/retrieval/CredentialRateLimiter.java`
- Create: `xiaoda-backend-intelligence/Spring-AI/src/main/java/com/fansea/ai/openapi/retrieval/InMemoryCredentialRateLimiter.java`
- Modify: `xiaoda-backend-intelligence/Spring-AI/src/main/java/com/fansea/ai/service/RagService.java`
- Modify: `xiaoda-backend-intelligence/Spring-AI/src/main/java/com/fansea/ai/service/impl/PgVectorRagServiceImpl.java`

### Documentation and verification

- Create: `docs/openapi/retrieval-api.md`
- Create: `docs/openapi/retrieval-api.yaml`
- Create focused tests beside the packages above under `xiaoda-backend-intelligence/Spring-AI/src/test/java/com/fansea/ai/openapi/**`
- Modify: `xiaoda-backend-intelligence/Spring-AI/src/test/java/com/fansea/ai/config/FlywayMigrationIntegrationTest.java`
- Modify: `xiaoda-backend-intelligence/Spring-AI/pom.xml`
- Modify: `xiaoda-backend-intelligence/Spring-AI/src/main/resources/application.yml`

---

### Task 1: Add extensible credential schema and persistence models

**Files:**
- Create: `xiaoda-backend-intelligence/Spring-AI/src/main/resources/db/V4__add_external_api_credentials.sql`
- Create: `xiaoda-backend-intelligence/Spring-AI/src/main/java/com/fansea/ai/openapi/credential/ApiCredential.java`
- Create: `xiaoda-backend-intelligence/Spring-AI/src/main/java/com/fansea/ai/openapi/credential/ApiCredentialKnowledge.java`
- Create: `xiaoda-backend-intelligence/Spring-AI/src/main/java/com/fansea/ai/openapi/credential/ApiCredentialMapper.java`
- Create: `xiaoda-backend-intelligence/Spring-AI/src/main/java/com/fansea/ai/openapi/credential/ApiCredentialKnowledgeMapper.java`
- Modify: `xiaoda-backend-intelligence/Spring-AI/src/main/java/com/fansea/ai/domain/Knowledge.java`
- Modify: `xiaoda-backend-intelligence/Spring-AI/src/main/java/com/fansea/ai/domain/File.java`
- Modify: `xiaoda-backend-intelligence/Spring-AI/src/test/java/com/fansea/ai/config/FlywayMigrationIntegrationTest.java`

**Interfaces:**
- Produces: `ApiCredentialMapper extends BaseMapper<ApiCredential>` and `ApiCredentialKnowledgeMapper extends BaseMapper<ApiCredentialKnowledge>`.
- Produces: `Knowledge.publicId` and `File.publicId` as UUID source identifiers.
- Produces: database constraint that only RAG credentials can be inserted into `api_credential_knowledge`.

- [ ] **Step 1: Extend the migration integration test with schema and constraint assertions**

Update the expected migration count from 3 to 4 and add assertions equivalent to:

```java
assertThat(flyway.migrate().migrationsExecuted).isEqualTo(4);
assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM " + schema
        + ".api_credential", Integer.class)).isZero();
assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns "
        + "WHERE table_schema=? AND table_name='api_credential' "
        + "AND column_name IN ('credential_type','secret_digest','pepper_version')",
        Integer.class, schema)).isEqualTo(3);
assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns "
        + "WHERE table_schema=? AND table_name IN ('knowledge','file') "
        + "AND column_name='public_id'", Integer.class, schema)).isEqualTo(2);
```

In the same test, insert a tenant, a `credential_type='AGENT_INVOKE'` credential and a Knowledge row, then assert that inserting that credential into `api_credential_knowledge` throws `DataIntegrityViolationException`.

- [ ] **Step 2: Run the migration test and verify the expected failure**

Run:

```bash
cd xiaoda-backend-intelligence/Spring-AI
mvn -Dtest=FlywayMigrationIntegrationTest test
```

Expected: FAIL because migration count is still 3 and `api_credential` does not exist.

- [ ] **Step 3: Add V4 migration with concrete relational constraints**

Create SQL with these essential definitions:

```sql
ALTER TABLE knowledge ADD COLUMN IF NOT EXISTS public_id UUID;
UPDATE knowledge SET public_id = gen_random_uuid() WHERE public_id IS NULL;
ALTER TABLE knowledge ALTER COLUMN public_id SET DEFAULT gen_random_uuid();
ALTER TABLE knowledge ALTER COLUMN public_id SET NOT NULL;
CREATE UNIQUE INDEX IF NOT EXISTS uk_knowledge_public_id ON knowledge(public_id);
ALTER TABLE knowledge ADD CONSTRAINT uk_knowledge_id_tenant UNIQUE (id, tenant_id);

ALTER TABLE file ADD COLUMN IF NOT EXISTS public_id UUID;
UPDATE file SET public_id = gen_random_uuid() WHERE public_id IS NULL;
ALTER TABLE file ALTER COLUMN public_id SET DEFAULT gen_random_uuid();
ALTER TABLE file ALTER COLUMN public_id SET NOT NULL;
CREATE UNIQUE INDEX IF NOT EXISTS uk_file_public_id ON file(public_id);

CREATE TABLE api_credential (
    id BIGSERIAL PRIMARY KEY,
    public_id UUID NOT NULL DEFAULT gen_random_uuid() UNIQUE,
    tenant_id BIGINT NOT NULL REFERENCES tenant(id) ON DELETE CASCADE,
    credential_type VARCHAR(32) NOT NULL,
    name VARCHAR(128) NOT NULL,
    description VARCHAR(512),
    key_id VARCHAR(32) NOT NULL UNIQUE,
    secret_digest CHAR(64) NOT NULL,
    pepper_version VARCHAR(16) NOT NULL,
    environment VARCHAR(16) NOT NULL CHECK (environment IN ('test','live')),
    status VARCHAR(16) NOT NULL CHECK (status IN ('active','disabled','revoked')),
    expires_at TIMESTAMP WITH TIME ZONE,
    allowed_ip_cidrs JSONB NOT NULL DEFAULT '[]'::jsonb,
    requests_per_minute INTEGER NOT NULL CHECK (requests_per_minute BETWEEN 1 AND 100000),
    burst_capacity INTEGER NOT NULL CHECK (burst_capacity BETWEEN 1 AND 100000),
    max_concurrency INTEGER NOT NULL CHECK (max_concurrency BETWEEN 1 AND 10000),
    authorization_version BIGINT NOT NULL DEFAULT 1,
    display_prefix VARCHAR(32) NOT NULL,
    display_last_four CHAR(4) NOT NULL,
    rotated_from_id BIGINT REFERENCES api_credential(id),
    created_by BIGINT NOT NULL REFERENCES app_user(id),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    revoked_at TIMESTAMP WITH TIME ZONE,
    last_used_at TIMESTAMP WITH TIME ZONE,
    last_used_ip VARCHAR(64),
    UNIQUE (id, tenant_id, credential_type)
);
CREATE INDEX idx_api_credential_tenant_type_status
    ON api_credential(tenant_id, credential_type, status);

CREATE TABLE api_credential_knowledge (
    tenant_id BIGINT NOT NULL,
    credential_id BIGINT NOT NULL,
    credential_type VARCHAR(32) NOT NULL DEFAULT 'RAG_RETRIEVAL'
        CHECK (credential_type = 'RAG_RETRIEVAL'),
    knowledge_id BIGINT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (credential_id, knowledge_id),
    FOREIGN KEY (credential_id, tenant_id, credential_type)
        REFERENCES api_credential(id, tenant_id, credential_type) ON DELETE CASCADE,
    FOREIGN KEY (knowledge_id, tenant_id)
        REFERENCES knowledge(id, tenant_id) ON DELETE CASCADE
);
```

Make constraint creation idempotent by relying on Flyway's once-only V4 execution rather than wrapping new constraints in procedural exception suppression.

- [ ] **Step 4: Add MyBatis entities and mappers**

Define `ApiCredential` with `@TableName(value="api_credential", autoResultMap=true)`. Map `allowedIpCidrs` as `List<String>` using `JacksonTypeHandler`. Define `ApiCredentialKnowledge` with `tenantId`, `credentialId`, `credentialType`, `knowledgeId`, and `createdAt`. Add `UUID publicId` to both existing resource entities. The mapper interfaces contain no custom SQL yet:

```java
@Mapper
public interface ApiCredentialMapper extends BaseMapper<ApiCredential> {}

@Mapper
public interface ApiCredentialKnowledgeMapper extends BaseMapper<ApiCredentialKnowledge> {}
```

- [ ] **Step 5: Run migration and mapper compilation tests**

Run:

```bash
mvn -Dtest=FlywayMigrationIntegrationTest test
mvn -DskipTests compile
```

Expected: both commands succeed; V4 applies once and the Agent-type relation insert is rejected by the database.

- [ ] **Step 6: Commit the schema slice**

```bash
git add xiaoda-backend-intelligence/Spring-AI/src/main/resources/db/V4__add_external_api_credentials.sql \
  xiaoda-backend-intelligence/Spring-AI/src/main/java/com/fansea/ai/domain/Knowledge.java \
  xiaoda-backend-intelligence/Spring-AI/src/main/java/com/fansea/ai/domain/File.java \
  xiaoda-backend-intelligence/Spring-AI/src/main/java/com/fansea/ai/openapi/credential \
  xiaoda-backend-intelligence/Spring-AI/src/test/java/com/fansea/ai/config/FlywayMigrationIntegrationTest.java
git commit -m "feat: add extensible api credential schema"
```

---

### Task 2: Implement versioned Pepper configuration and opaque API Key codec

**Files:**
- Create: `xiaoda-backend-intelligence/Spring-AI/src/main/java/com/fansea/ai/openapi/credential/ApiKeyProperties.java`
- Create: `xiaoda-backend-intelligence/Spring-AI/src/main/java/com/fansea/ai/openapi/credential/CredentialType.java`
- Create: `xiaoda-backend-intelligence/Spring-AI/src/main/java/com/fansea/ai/openapi/credential/ApiKeyCodec.java`
- Create: `xiaoda-backend-intelligence/Spring-AI/src/test/java/com/fansea/ai/openapi/credential/ApiKeyCodecTest.java`
- Modify: `xiaoda-backend-intelligence/Spring-AI/src/main/resources/application.yml`

**Interfaces:**
- Produces: `CredentialType.RAG_RETRIEVAL` with prefix `rag` and reserved `CredentialType.AGENT_INVOKE` with prefix `agt`; v1 management/API authorization still permits only RAG.
- Produces: `ApiKeyCodec.IssuedKey issue(CredentialType type, String environment)`.
- Produces: `ApiKeyCodec.ParsedKey parse(String rawKey)` and `boolean verify(ParsedKey key, String digest, String pepperVersion)`.

- [ ] **Step 1: Write codec tests for format, digest, version coexistence, and live guard**

Create tests with explicit expectations:

```java
@Test
void issuesRagTestKeyAndNeverRequiresStoredPlaintextForVerification() {
    ApiKeyCodec codec = codec(Map.of("v1", "12345678901234567890123456789012"), "v1", true);
    ApiKeyCodec.IssuedKey issued = codec.issue(CredentialType.RAG_RETRIEVAL, "test");
    assertThat(issued.rawKey()).startsWith("rag_test_");
    assertThat(issued.digest()).hasSize(64);
    assertThat(codec.verify(codec.parse(issued.rawKey()), issued.digest(), "v1")).isTrue();
    assertThat(issued.digest()).doesNotContain(issued.secret());
}

@Test
void verifiesOldV1AfterV2BecomesActive() {
    ApiKeyCodec v1 = codec(Map.of("v1", PEPPER_V1), "v1", false);
    ApiKeyCodec.IssuedKey old = v1.issue(CredentialType.RAG_RETRIEVAL, "test");
    ApiKeyCodec v2 = codec(Map.of("v1", PEPPER_V1, "v2", PEPPER_V2), "v2", false);
    assertThat(v2.verify(v2.parse(old.rawKey()), old.digest(), "v1")).isTrue();
}

@Test
void defaultPepperCannotIssueLiveKey() {
    assertThatThrownBy(() -> defaultCodec.issue(CredentialType.RAG_RETRIEVAL, "live"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("default pepper");
}
```

Also test malformed prefix, more than one separator, non-Base64URL secret, key longer than 256 characters, and prefix/type/environment mismatch.

- [ ] **Step 2: Run the codec test and verify it fails to compile**

Run:

```bash
mvn -Dtest=ApiKeyCodecTest test
```

Expected: FAIL because `ApiKeyCodec` and `ApiKeyProperties` do not exist.

- [ ] **Step 3: Add typed configuration and application defaults**

Add configuration:

```yaml
external-api:
  require-https: false
  api-key:
    active-pepper-version: v1
    peppers:
      v1: ${RAG_API_KEY_PEPPER_V1:dev-only-rag-pepper-v1-change-me-32bytes}
    positive-cache-ttl: 60s
    negative-cache-ttl: 10s
```

In the same edit, replace the existing committed model credential with an environment reference:

```yaml
spring:
  ai:
    openai:
      api-key: ${OPENAI_API_KEY:dev-only-openai-key-not-configured}
```

If the previous value was a real credential, rotate it outside the repository before deploying this feature.

`ApiKeyProperties` is a `@ConfigurationProperties(prefix="external-api.api-key")` bean. On initialization it rejects a missing active version and any Pepper shorter than 32 UTF-8 bytes. It exposes `boolean usesDevelopmentDefault(String version)` without exposing Pepper values in `toString()`.

- [ ] **Step 4: Implement the codec with one parse and HMAC-SHA-256**

Use `SecureRandom`, `Base64.getUrlEncoder().withoutPadding()`, `Mac.getInstance("HmacSHA256")`, `HexFormat`, and `MessageDigest.isEqual`. The records are:

```java
public record IssuedKey(
    String rawKey, String keyId, String secret, String digest,
    String pepperVersion, CredentialType credentialType, String environment,
    String displayPrefix, String displayLastFour) {}

public record ParsedKey(
    String rawPrefix, String keyId, String secret,
    CredentialType credentialType, String environment) {}
```

Do not log or include `rawKey`/`secret` in exception messages.

- [ ] **Step 5: Run focused and configuration regression tests**

```bash
mvn -Dtest=ApiKeyCodecTest test
mvn -Dtest=SpringAiApplicationTests test
```

Expected: PASS; application starts with the development default, while live issuance is rejected.

- [ ] **Step 6: Commit key cryptography**

```bash
git add xiaoda-backend-intelligence/Spring-AI/src/main/java/com/fansea/ai/openapi/credential \
  xiaoda-backend-intelligence/Spring-AI/src/test/java/com/fansea/ai/openapi/credential/ApiKeyCodecTest.java \
  xiaoda-backend-intelligence/Spring-AI/src/main/resources/application.yml
git commit -m "feat: add versioned external api key codec"
```

---

### Task 3: Add replaceable credential cache and RAG Scope resolver

**Files:**
- Modify: `xiaoda-backend-intelligence/Spring-AI/pom.xml`
- Create: `xiaoda-backend-intelligence/Spring-AI/src/main/java/com/fansea/ai/openapi/credential/ApiCredentialCache.java`
- Create: `xiaoda-backend-intelligence/Spring-AI/src/main/java/com/fansea/ai/openapi/credential/CaffeineApiCredentialCache.java`
- Create: `xiaoda-backend-intelligence/Spring-AI/src/main/java/com/fansea/ai/openapi/credential/CredentialScopeSnapshot.java`
- Create: `xiaoda-backend-intelligence/Spring-AI/src/main/java/com/fansea/ai/openapi/credential/RagKnowledgeScopeSnapshot.java`
- Create: `xiaoda-backend-intelligence/Spring-AI/src/main/java/com/fansea/ai/openapi/credential/CredentialAuthenticationException.java`
- Create: `xiaoda-backend-intelligence/Spring-AI/src/main/java/com/fansea/ai/openapi/credential/ApiCredentialResolver.java`
- Create: `xiaoda-backend-intelligence/Spring-AI/src/test/java/com/fansea/ai/openapi/credential/ApiCredentialResolverTest.java`
- Create: `xiaoda-backend-intelligence/Spring-AI/src/test/java/com/fansea/ai/openapi/credential/CaffeineApiCredentialCacheTest.java`

**Interfaces:**
- Consumes: Task 1 mappers and Task 2 `ApiKeyCodec`/`CredentialType`.
- Produces: `ApiCredentialResolver.ResolvedCredential resolve(ApiKeyCodec.ParsedKey parsedKey)`; `CachedCredential` and `ResolvedCredential` are separate public nested records of `ApiCredentialResolver`.
- Produces: generic `CredentialScopeSnapshot` and typed `RagKnowledgeScopeSnapshot(Set<Long> knowledgeIds)`.
- Produces: `CachedCredentialResult get(String keyId)`, `putValid(String keyId, ApiCredentialResolver.CachedCredential value)`, `putMissing(String keyId)`, `evict(String keyId)`, and `evictTenant(Long tenantId)`.

- [ ] **Step 1: Add Caffeine dependency and write resolver tests against a fake cache**

Add:

```xml
<dependency>
  <groupId>com.github.ben-manes.caffeine</groupId>
  <artifactId>caffeine</artifactId>
</dependency>
```

The resolver test must use an in-memory fake implementing only `ApiCredentialCache`, not Caffeine. Cover:

```java
@Test
void cacheMissLoadsCredentialAndRagScopeOnce() {
    when(credentials.selectOne(any())).thenReturn(activeRagCredential());
    when(credentialKnowledge.selectList(any())).thenReturn(List.of(link(11L), link(12L)));
    ResolvedCredential first = resolver.resolve(parsedKey);
    ResolvedCredential second = resolver.resolve(parsedKey);
    assertThat(((RagKnowledgeScopeSnapshot) first.scope()).knowledgeIds())
        .containsExactlyInAnyOrder(11L, 12L);
    assertThat(second).isEqualTo(first);
    verify(credentials, times(1)).selectOne(any());
}

@Test
void cacheHitStillVerifiesSecret() {
    resolver.resolve(parsedKey);
    ApiKeyCodec.ParsedKey forged = new ApiKeyCodec.ParsedKey(
        parsedKey.rawPrefix(), parsedKey.keyId(), "different-secret",
        parsedKey.credentialType(), parsedKey.environment());
    assertThatThrownBy(() -> resolver.resolve(forged))
        .isInstanceOf(CredentialAuthenticationException.class);
    verify(credentials, times(1)).selectOne(any());
}

@Test
void negativeCacheAvoidsRepeatedDatabaseLookup() {
    when(credentials.selectOne(any())).thenReturn(null);
    assertThatThrownBy(() -> resolver.resolve(parsedKey)).isInstanceOf(CredentialAuthenticationException.class);
    assertThatThrownBy(() -> resolver.resolve(parsedKey)).isInstanceOf(CredentialAuthenticationException.class);
    verify(credentials, times(1)).selectOne(any());
}
```

Also cover revoked, expired, prefix/type mismatch, disabled tenant, authorization version preservation, and an `AGENT_INVOKE` credential not loading the RAG table. In `CaffeineApiCredentialCacheTest`, inject a controllable Caffeine `Ticker` and prove positive entries expire after 60 seconds, negative entries expire after 10 seconds, `evict(keyId)` removes both forms, and `evictTenant(tenantId)` removes only that tenant's positive entries.

- [ ] **Step 2: Run the resolver tests and verify they fail**

```bash
mvn -Dtest=ApiCredentialResolverTest test
```

Expected: FAIL because cache, resolver, snapshots, and auth exception do not exist.

- [ ] **Step 3: Implement generic cache records and Caffeine adapter**

Define:

```java
public sealed interface CachedCredentialResult {
    record Hit(ApiCredentialResolver.CachedCredential credential) implements CachedCredentialResult {}
    record Missing() implements CachedCredentialResult {}
    record NotCached() implements CachedCredentialResult {}
}

public sealed interface CredentialScopeSnapshot permits RagKnowledgeScopeSnapshot {}

public record RagKnowledgeScopeSnapshot(Set<Long> knowledgeIds)
        implements CredentialScopeSnapshot {
    public RagKnowledgeScopeSnapshot {
        knowledgeIds = Set.copyOf(knowledgeIds);
    }
}
```

Use separate Caffeine caches for positive and negative entries so their TTLs differ. `CachedCredential` contains `secretDigest` and `pepperVersion` plus status/policy/Scope; `ResolvedCredential` omits both verification fields. On every request, including a positive-cache hit, `ApiCredentialResolver` calls `ApiKeyCodec.verify(parsedKey, cached.secretDigest(), cached.pepperVersion())` before producing `ResolvedCredential`. `CaffeineApiCredentialCache` knows no Knowledge semantics and never stores the raw Key or Secret.

Define `CredentialAuthenticationException` with a non-sensitive machine code such as `authentication_failed`, `credential_disabled`, or `credential_type_not_supported`. Its messages must not include Key ID, digest, Pepper version, or raw Key.

- [ ] **Step 4: Implement type-based Scope loading**

For `RAG_RETRIEVAL`, load all `api_credential_knowledge` rows by `credential_id` and build the immutable set. Define a registry map from `CredentialType` to loader function so future `AGENT_INVOKE` adds another loader without changing the cache contract. If a type has no registered loader, return `credential_type_not_supported` before caching a usable principal.

- [ ] **Step 5: Run resolver and full credential tests**

```bash
mvn -Dtest='com.fansea.ai.openapi.credential.*Test' test
```

Expected: PASS; the fake-cache tests prove the resolver is not coupled to Caffeine.

- [ ] **Step 6: Commit cache and resolver**

```bash
git add xiaoda-backend-intelligence/Spring-AI/pom.xml \
  xiaoda-backend-intelligence/Spring-AI/src/main/java/com/fansea/ai/openapi/credential \
  xiaoda-backend-intelligence/Spring-AI/src/test/java/com/fansea/ai/openapi/credential/ApiCredentialResolverTest.java
git commit -m "feat: add replaceable credential cache and scope resolver"
```

---

### Task 4: Integrate external credentials into request authentication without weakening JWT routes

**Files:**
- Create: `xiaoda-backend-intelligence/Spring-AI/src/main/java/com/fansea/ai/openapi/auth/ExternalApiKeyFilter.java`
- Create: `xiaoda-backend-intelligence/Spring-AI/src/main/java/com/fansea/ai/openapi/auth/ExternalApiFilterConfig.java`
- Create: `xiaoda-backend-intelligence/Spring-AI/src/main/java/com/fansea/ai/openapi/auth/ExternalApiTransportProperties.java`
- Create: `xiaoda-backend-intelligence/Spring-AI/src/main/java/com/fansea/ai/openapi/auth/ClientIpResolver.java`
- Create: `xiaoda-backend-intelligence/Spring-AI/src/main/java/com/fansea/ai/openapi/auth/IpCidrMatcher.java`
- Create: `xiaoda-backend-intelligence/Spring-AI/src/main/java/com/fansea/ai/openapi/error/ExternalApiException.java`
- Create: `xiaoda-backend-intelligence/Spring-AI/src/main/java/com/fansea/ai/openapi/error/ExternalApiExceptionHandler.java`
- Create: `xiaoda-backend-intelligence/Spring-AI/src/test/java/com/fansea/ai/openapi/auth/ExternalApiKeyFilterTest.java`
- Modify: `xiaoda-backend-intelligence/Spring-AI/src/main/java/com/fansea/ai/auth/AuthContext.java`
- Modify: `xiaoda-backend-intelligence/Spring-AI/src/main/java/com/fansea/ai/auth/AuthAspect.java`
- Modify: `xiaoda-backend-intelligence/Spring-AI/src/main/java/com/fansea/ai/auth/JwtAuthFilter.java`
- Modify: `xiaoda-backend-intelligence/Spring-AI/src/main/java/com/fansea/ai/auth/PasswordEncoderConfig.java`

**Interfaces:**
- Consumes: Task 3 `ApiCredentialResolver.ResolvedCredential`.
- Produces: `AuthContext.external(ResolvedCredential credential)` with `Kind.EXTERNAL_API`.
- Produces: external filter order 9; existing JWT filter remains order 10 and skips `/openapi/v1/**`.
- Produces: a shared external error envelope/handler used by both authentication and the later Retrieval Controller.

- [ ] **Step 1: Write filter isolation tests**

Cover these exact cases using `MockHttpServletRequest`, `MockHttpServletResponse`, and a recording `FilterChain`:

```java
@Test
void validExternalKeySetsContextForChainAndAlwaysClearsIt() throws Exception {
    request.setRequestURI("/openapi/v1/retrieval");
    request.addHeader("Authorization", "Bearer " + RAW_KEY);
    filter.doFilter(request, response, (req, res) -> {
        assertThat(AuthContext.current().getKind()).isEqualTo(AuthContext.Kind.EXTERNAL_API);
        assertThat(AuthContext.current().getCredentialType()).isEqualTo("RAG_RETRIEVAL");
    });
    assertThat(AuthContext.current()).isNull();
}
```

Also assert: missing Header returns 401, duplicate Authorization headers return 400, oversized Header returns 400, `/auth/me` bypasses the external filter, external Key cannot pass `@RequireLogin`, and JWT filter's `shouldNotFilter` returns true for `/openapi/v1/retrieval`. Add focused `ClientIpResolver`/`IpCidrMatcher` tests proving IPv4 and IPv6 CIDRs work, untrusted callers cannot spoof `X-Forwarded-For`/`X-Forwarded-Proto`, a trusted proxy can supply them, IP allowlist rejection occurs before the chain, and a live Key is rejected on insecure transport when `requireHttps=true`.

- [ ] **Step 2: Run the filter tests and verify failure**

```bash
mvn -Dtest=ExternalApiKeyFilterTest test
```

Expected: FAIL because `EXTERNAL_API` context and filter do not exist.

- [ ] **Step 3: Extend AuthContext without breaking existing constructors**

Keep the current five-argument constructor for all existing tests. Add `Kind.EXTERNAL_API`, nullable `credentialId`, `credentialType`, `credentialEnvironment`, `CredentialScopeSnapshot`, `requestsPerMinute`, `burstCapacity`, and `maxConcurrency`, plus a static factory:

```java
public static AuthContext external(ResolvedCredential c) {
    return new AuthContext(Kind.EXTERNAL_API, c.id(), c.tenantId(),
        "external_api", c.keyId(), c.id(), c.credentialType().name(), c.environment(),
        c.scope(), c.requestsPerMinute(), c.burstCapacity(), c.maxConcurrency());
}
```

Change `AuthAspect.requireLogin` to reject both `PLATFORM` and `EXTERNAL_API`; this prevents machine keys from accessing tenant UI endpoints.

- [ ] **Step 4: Implement and register the external filter**

The filter must:

1. Apply only to `/openapi/v1/**`.
2. Require exactly one `Authorization` Header.
3. Require case-sensitive `Bearer ` followed by a nonblank Key of at most 256 characters.
4. Parse and resolve once.
5. Resolve client IP from the socket unless the socket peer matches a configured trusted-proxy CIDR.
6. Enforce Credential `allowedIpCidrs` and live-Key HTTPS policy before setting AuthContext.
7. Set AuthContext, call the chain, and clear in `finally`.
8. Delegate failures to a named `HandlerExceptionResolver` so the shared external exception handler controls the body.

Register order 9. Update `JwtAuthFilter.shouldNotFilter` to skip `/openapi/v1/`. Do not add the external prefix to the JWT filter's login exclusions in a way that skips the external filter. Bind transport settings as:

```yaml
external-api:
  require-https: false
  trusted-proxies: []
```

`ClientIpResolver` only honors the first forwarded client address and forwarded scheme when `request.getRemoteAddr()` matches `trusted-proxies`. `IpCidrMatcher` compares normalized `InetAddress` bytes and never performs DNS lookup for request-supplied hostnames.

Create `ExternalApiException` with `HttpStatus status`, `String code`, `String message`, and optional `String param`. `ExternalApiExceptionHandler` is limited to `com.fansea.ai.openapi` via `@RestControllerAdvice(basePackages="com.fansea.ai.openapi")`; it returns `{request_id,error:{code,message,param}}`, adds `X-Request-ID`, and maps `CredentialAuthenticationException` to the non-sensitive external codes. Inject `@Qualifier("handlerExceptionResolver") HandlerExceptionResolver` into the Filter so filter failures use this same envelope.

- [ ] **Step 5: Run authentication regressions**

```bash
mvn -Dtest=ExternalApiKeyFilterTest test
mvn -Dtest='JwtAuthFilterTest,AuthServiceTest,TenantProfileControllerTest' test
```

Expected: PASS; existing JWT business and platform behavior remains unchanged.

- [ ] **Step 6: Commit authentication routing**

```bash
git add xiaoda-backend-intelligence/Spring-AI/src/main/java/com/fansea/ai/auth \
  xiaoda-backend-intelligence/Spring-AI/src/main/java/com/fansea/ai/openapi/auth \
  xiaoda-backend-intelligence/Spring-AI/src/main/java/com/fansea/ai/openapi/error \
  xiaoda-backend-intelligence/Spring-AI/src/test/java/com/fansea/ai/openapi/auth/ExternalApiKeyFilterTest.java
git commit -m "feat: authenticate external api credentials"
```

---

### Task 5: Add tenant-admin credential lifecycle and RAG Scope management APIs

**Files:**
- Create: `xiaoda-backend-intelligence/Spring-AI/src/main/java/com/fansea/ai/openapi/credential/ApiCredentialService.java`
- Create: `xiaoda-backend-intelligence/Spring-AI/src/main/java/com/fansea/ai/openapi/credential/TenantApiCredentialController.java`
- Create: `xiaoda-backend-intelligence/Spring-AI/src/test/java/com/fansea/ai/openapi/credential/ApiCredentialServiceTest.java`
- Create: `xiaoda-backend-intelligence/Spring-AI/src/test/java/com/fansea/ai/openapi/credential/TenantApiCredentialControllerTest.java`

**Interfaces:**
- Consumes: Task 1 mappers, Task 2 codec, Task 3 cache.
- Produces: create/list/get/patch/replace-scope/rotate/revoke tenant JWT endpoints under `/tenant/api-credentials`.
- Produces: full Key only in `CreateCredentialResponse.apiKey()` and `RotateCredentialResponse.apiKey()`.

- [ ] **Step 1: Write service tests for atomic creation, scope replacement, rotation, and revocation**

Key cases:

```java
@Test
void createsRagCredentialAndRelationsInAuthenticatedTenant() {
    CreateCredentialCommand cmd = new CreateCredentialCommand(
        "客服检索", "RAG_RETRIEVAL", "test", Set.of(KNOWLEDGE_PUBLIC_ID),
        List.of("10.0.0.0/24"), 60, 10, 5, expiresAt);
    CreatedCredential result = service.create(cmd, tenantAdminContext());
    assertThat(result.apiKey()).startsWith("rag_test_");
    verify(credentials).insert(argThat(row -> row.getTenantId().equals(22L)
        && row.getSecretDigest() != null && row.getSecretDigest().length() == 64));
    verify(credentialKnowledge).insert(argThat(link -> link.getCredentialType().equals("RAG_RETRIEVAL")));
}
```

Also assert: knowledge UUID from another tenant is rejected; malformed IPv4/IPv6 CIDRs are rejected before persistence; empty scope is allowed at creation but later retrieval returns 403; `credential_type` other than `RAG_RETRIEVAL` is rejected in v1; live creation with default Pepper fails; replacement is transactional and increments `authorization_version`; rotation copies type, policy, and scope; revoke is idempotent and evicts cache.

- [ ] **Step 2: Write controller tests for role and secret response behavior**

Use `@WebMvcTest(TenantApiCredentialController.class)` with `AuthAspect` enabled. Assert tenant member gets 403, tenant admin gets 201, create/rotate response has `Cache-Control: no-store`, list/detail never include `secretDigest`, `pepperVersion`, or full Key, and PATCH cannot change `credentialType`.

- [ ] **Step 3: Run the new tests and verify failure**

```bash
mvn -Dtest='ApiCredentialServiceTest,TenantApiCredentialControllerTest' test
```

Expected: FAIL because service, commands, response views, and controller do not exist.

- [ ] **Step 4: Implement transactional lifecycle service**

Use public nested records inside `ApiCredentialService` so later callers import `ApiCredentialService.CreateCredentialCommand` and `ApiCredentialService.CreatedCredential` without introducing unspecified files:

```java
public record CreateCredentialCommand(
    String name, String credentialType, String environment,
    Set<UUID> knowledgeIds, List<String> allowedIpCidrs,
    int requestsPerMinute, int burstCapacity, int maxConcurrency,
    Instant expiresAt) {}

public record CreatedCredential(ApiCredentialView credential, String apiKey) {}
```

Annotate create, replace scope, rotate, and revoke with `@Transactional`. Resolve Knowledge UUIDs under the authenticated tenant before inserting relations. Use structured logs containing `credentialPublicId`, tenant ID and action only; never log Key, digest, Pepper, Query, or content.

- [ ] **Step 5: Implement controller routes with explicit DTO validation**

Implement:

```text
POST   /tenant/api-credentials
GET    /tenant/api-credentials
GET    /tenant/api-credentials/{credentialId}
PATCH  /tenant/api-credentials/{credentialId}
PUT    /tenant/api-credentials/{credentialId}/knowledge-bases
POST   /tenant/api-credentials/{credentialId}/rotate
POST   /tenant/api-credentials/{credentialId}/revoke
```

Use `@RequireLogin` and `@RequireRole("tenant_admin")`. Do not implement usage history storage; the optional usage view returns current `lastUsedAt` and `lastUsedIp` from `api_credential` only until Metrics export is introduced.

- [ ] **Step 6: Run service/controller and tenant regressions**

```bash
mvn -Dtest='ApiCredentialServiceTest,TenantApiCredentialControllerTest,TenantProfileControllerTest' test
```

Expected: PASS.

- [ ] **Step 7: Commit lifecycle APIs**

```bash
git add xiaoda-backend-intelligence/Spring-AI/src/main/java/com/fansea/ai/openapi/credential \
  xiaoda-backend-intelligence/Spring-AI/src/test/java/com/fansea/ai/openapi/credential
git commit -m "feat: manage tenant api credentials"
```

---

### Task 6: Refactor RAG search for authorized multi-knowledge global Top K and stable sources

**Files:**
- Create: `xiaoda-backend-intelligence/Spring-AI/src/main/java/com/fansea/ai/openapi/retrieval/RetrievalQuery.java`
- Create: `xiaoda-backend-intelligence/Spring-AI/src/main/java/com/fansea/ai/openapi/retrieval/RetrievedChunk.java`
- Create: `xiaoda-backend-intelligence/Spring-AI/src/test/java/com/fansea/ai/openapi/retrieval/PgVectorRagServiceImplTest.java`
- Modify: `xiaoda-backend-intelligence/Spring-AI/src/main/java/com/fansea/ai/service/RagService.java`
- Modify: `xiaoda-backend-intelligence/Spring-AI/src/main/java/com/fansea/ai/service/impl/PgVectorRagServiceImpl.java`

**Interfaces:**
- Produces: `List<RetrievedChunk> retrieve(RetrievalQuery query)`.
- `RetrievalQuery`: query text, immutable knowledge ID set, topK, scoreThreshold.
- `RetrievedChunk`: content, score, title, document UUID, chunk UUID, file type, page number, chunk index.

- [ ] **Step 1: Write unit tests around a mocked VectorStore**

Capture the `SearchRequest` passed to `VectorStore.similaritySearch` and assert:

```java
@Test
void searchesAllAuthorizedKnowledgeBasesOnceAndReturnsGlobalTopK() {
    when(vectorStore.similaritySearch(any())).thenReturn(List.of(
        document("chunk-a", 0.91, 31L), document("chunk-b", 0.77, 32L)));
    List<RetrievedChunk> result = service.retrieve(
        new RetrievalQuery("退款材料", Set.of(11L, 12L), 5, 0.5));
    ArgumentCaptor<SearchRequest> request = ArgumentCaptor.forClass(SearchRequest.class);
    verify(vectorStore, times(1)).similaritySearch(request.capture());
    assertThat(request.getValue().getTopK()).isEqualTo(5);
    assertThat(request.getValue().getSimilarityThreshold()).isEqualTo(0.5);
    assertThat(request.getValue().getFilterExpression().toString())
        .contains("knowledgeId", "11", "12", "tenantId");
    assertThat(result).extracting(RetrievedChunk::score).containsExactly(0.91, 0.77);
}
```

Also assert empty knowledge scope throws before VectorStore, disabled files are excluded, `topK=20` works, scores remain `[0,1]`, null score becomes 0 and is removed by threshold, and source title/public UUID is batch-enriched from current File rows.

- [ ] **Step 2: Run the service test and verify failure**

```bash
mvn -Dtest=PgVectorRagServiceImplTest test
```

Expected: FAIL because the new query/result types and `retrieve` method do not exist.

- [ ] **Step 3: Add retrieval types and preserve existing RagService methods**

Add the new method without deleting `search`, `searchByFile`, or `vectorize`, so current chat endpoints keep compiling:

```java
List<RetrievedChunk> retrieve(RetrievalQuery query);
```

The records defensively copy collections and reject invalid topK/threshold at construction.

- [ ] **Step 4: Implement safe numeric filter construction and result enrichment**

All IDs come from authenticated DB state, not request JSON. Sort and join numeric IDs before building the Spring AI filter string. Build one `SearchRequest`:

```java
SearchRequest request = SearchRequest.builder()
    .query(query.query())
    .topK(query.topK())
    .similarityThreshold(query.scoreThreshold())
    .filterExpression(combinedTenantKnowledgeAndEnabledFileFilter)
    .build();
```

Batch-load current File rows with `fileService.listByIds`, discard disabled files defensively, and map source metadata without returning internal IDs or paths.

- [ ] **Step 5: Add stable chunk metadata during vectorization**

After splitting, ensure every Document contains:

```java
doc.getMetadata().put("tenantId", tenantId);
doc.getMetadata().put("knowledgeId", knowledgeId);
doc.getMetadata().put("fileId", fileId);
doc.getMetadata().put("documentPublicId", file.getPublicId().toString());
doc.getMetadata().put("chunkId", doc.getId());
doc.getMetadata().put("chunkIndex", index);
doc.getMetadata().put("fileType", file.getType());
```

Preserve reader-provided page metadata under normalized `pageNumber` when present. Do not put the filesystem path into the external response.

- [ ] **Step 6: Run RAG and chat regressions**

```bash
mvn -Dtest=PgVectorRagServiceImplTest test
mvn -DskipTests compile
```

Expected: PASS and current `AiChatController` still compiles.

- [ ] **Step 7: Commit retrieval core**

```bash
git add xiaoda-backend-intelligence/Spring-AI/src/main/java/com/fansea/ai/service \
  xiaoda-backend-intelligence/Spring-AI/src/main/java/com/fansea/ai/openapi/retrieval/RetrievalQuery.java \
  xiaoda-backend-intelligence/Spring-AI/src/main/java/com/fansea/ai/openapi/retrieval/RetrievedChunk.java \
  xiaoda-backend-intelligence/Spring-AI/src/test/java/com/fansea/ai/openapi/retrieval/PgVectorRagServiceImplTest.java
git commit -m "feat: retrieve globally across authorized knowledge bases"
```

---

### Task 7: Expose strict Retrieval OpenAPI with rate limits and external error contract

**Files:**
- Create: `xiaoda-backend-intelligence/Spring-AI/src/main/java/com/fansea/ai/openapi/retrieval/RetrievalRequestParser.java`
- Create: `xiaoda-backend-intelligence/Spring-AI/src/main/java/com/fansea/ai/openapi/retrieval/ExternalRetrievalController.java`
- Create: `xiaoda-backend-intelligence/Spring-AI/src/main/java/com/fansea/ai/openapi/retrieval/CredentialRateLimiter.java`
- Create: `xiaoda-backend-intelligence/Spring-AI/src/main/java/com/fansea/ai/openapi/retrieval/InMemoryCredentialRateLimiter.java`
- Create: `xiaoda-backend-intelligence/Spring-AI/src/test/java/com/fansea/ai/openapi/retrieval/ExternalRetrievalControllerTest.java`
- Create: `xiaoda-backend-intelligence/Spring-AI/src/test/java/com/fansea/ai/openapi/retrieval/InMemoryCredentialRateLimiterTest.java`

**Interfaces:**
- Consumes: Task 4 `EXTERNAL_API` context/shared external error handler and Task 6 `RagService.retrieve`.
- Produces: `POST /openapi/v1/retrieval` with only `query` and `retrieval_setting`.
- Produces: `CredentialRateLimiter.RateLimitLease acquire(long credentialId, int requestsPerMinute, int burstCapacity, int maxConcurrency)`; the lease releases its concurrency permit from `close()`.

- [ ] **Step 1: Write strict parser and controller tests**

Use `@WebMvcTest(ExternalRetrievalController.class)` and set an external RAG AuthContext in `@BeforeEach`. Assert:

```java
mockMvc.perform(post("/openapi/v1/retrieval")
        .contentType(APPLICATION_JSON)
        .content("""
            {"query":"退款材料","retrieval_setting":{"top_k":5,"score_threshold":0.5}}
            """))
    .andExpect(status().isOk())
    .andExpect(jsonPath("$.records[0].score").value(0.92))
    .andExpect(header().string("Cache-Control", containsString("no-store")))
    .andExpect(header().exists("X-Request-ID"));
```

Add separate tests proving `knowledge_id`, `metadata_condition`, and arbitrary unknown fields each return 400; a body larger than 32 KB returns 413; blank or over-250 query returns 400; topK outside 1..20 and threshold outside 0..1 return 400; empty Scope returns 403; Agent type returns `credential_type_not_allowed`; no results return exactly `{"records":[]}`; and the service receives the entire Scope set from AuthContext.

- [ ] **Step 2: Write deterministic token-bucket tests**

Inject a fake `Clock` into `InMemoryCredentialRateLimiter`. Assert the first 10 burst requests succeed, the 11th returns retry-after, capacity refills after one minute according to `requestsPerMinute`, and concurrency permits are released in `finally` after success or exception.

Define `RateLimitLease extends AutoCloseable` with `limit`, `remaining`, `resetEpochSecond`, and a no-throw `close()`. The controller obtains policy fields from the external AuthContext and invokes Retrieval inside try-with-resources so concurrency is released on every exit path.

Back limiter state with a bounded Caffeine cache expiring entries 10 minutes after last access; never use an unbounded `ConcurrentHashMap` keyed by attacker-controlled Credential IDs.

- [ ] **Step 3: Run endpoint and limiter tests and verify failure**

```bash
mvn -Dtest='ExternalRetrievalControllerTest,InMemoryCredentialRateLimiterTest' test
```

Expected: FAIL because parser, controller, handler, and limiter do not exist.

- [ ] **Step 4: Implement strict JsonNode parser**

Accept `@RequestBody byte[]` in the controller, reject `body.length > 32 * 1024` before JSON parsing, then call `ObjectMapper.readTree(body)` and parse the `JsonNode` with an allowlist:

```java
private static final Set<String> ROOT_FIELDS = Set.of("query", "retrieval_setting");
private static final Set<String> SETTING_FIELDS = Set.of("top_k", "score_threshold");
```

Reject any field outside those sets. Defaults are topK 5 and threshold 0.0. This avoids changing Jackson unknown-field behavior for existing internal endpoints.

- [ ] **Step 5: Implement response, error mapping, and rate-limit headers**

Response records are:

```java
public record RetrievalRecord(
    String content, double score, String title, Map<String, Object> metadata) {}
public record RetrievalResponse(List<RetrievalRecord> records) {}
```

Map errors to the exact codes from the spec. All responses include `X-Request-ID`; 429 includes `Retry-After`; success includes `X-RateLimit-Limit`, `X-RateLimit-Remaining`, `X-RateLimit-Reset`, and `Cache-Control: no-store`. Truncate per chunk at 8 KB and drop lowest-score records until serialized response content is within 128 KB.

- [ ] **Step 6: Add structured Metrics without a request-audit table**

Use Micrometer counters/timers available through Spring Boot observation support. Record tags only for outcome, status, and credential type. Do not tag credential ID, tenant ID, query, title, or chunk content. If the application has no `MeterRegistry` bean in tests, inject `ObjectProvider<MeterRegistry>` and treat absence as no-op rather than adding a database audit path.

- [ ] **Step 7: Run endpoint, auth, and RAG tests**

```bash
mvn -Dtest='ExternalRetrievalControllerTest,InMemoryCredentialRateLimiterTest,ExternalApiKeyFilterTest,PgVectorRagServiceImplTest' test
```

Expected: PASS.

- [ ] **Step 8: Commit public endpoint**

```bash
git add xiaoda-backend-intelligence/Spring-AI/src/main/java/com/fansea/ai/openapi/retrieval \
  xiaoda-backend-intelligence/Spring-AI/src/test/java/com/fansea/ai/openapi/retrieval
git commit -m "feat: expose external rag retrieval endpoint"
```

---

### Task 8: Publish standalone integration guide and OpenAPI 3.1 contract

**Files:**
- Create: `docs/openapi/retrieval-api.md`
- Create: `docs/openapi/retrieval-api.yaml`
- Create: `xiaoda-backend-intelligence/Spring-AI/src/test/java/com/fansea/ai/openapi/retrieval/RetrievalOpenApiContractTest.java`

**Interfaces:**
- Consumes: Task 7 route, request, response, headers, and error codes.
- Produces: human-readable external guide and machine-readable OpenAPI 3.1 contract.

- [ ] **Step 1: Write a contract test before adding the documents**

Parse `../../docs/openapi/retrieval-api.yaml` with SnakeYAML and assert:

```java
assertThat(root.get("openapi")).isEqualTo("3.1.0");
assertThat(path(root, "paths", "/openapi/v1/retrieval", "post")).isNotNull();
assertThat(path(root, "components", "securitySchemes", "RagApiKey", "scheme"))
    .isEqualTo("bearer");
assertThat(schemaProperties(root, "RetrievalRequest"))
    .containsExactlyInAnyOrder("query", "retrieval_setting");
assertThat(path(root, "components", "schemas", "RetrievalRequest", "additionalProperties"))
    .isEqualTo(false);
```

Also read Markdown and assert it contains examples for cURL, Java, Python, JavaScript, Key rotation, HTTP testing warning, HTTPS production requirement, 400/401/403/429/503/504, and an explicit statement that `knowledge_id` is unsupported.

- [ ] **Step 2: Run the contract test and verify missing-file failure**

```bash
mvn -Dtest=RetrievalOpenApiContractTest test
```

Expected: FAIL because both external documents are absent.

- [ ] **Step 3: Write the OpenAPI 3.1 YAML**

Define `RagApiKey` exactly as:

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

Include schemas for RetrievalRequest, RetrievalSetting, RetrievalResponse, RetrievalRecord, RetrievalMetadata, and ErrorResponse. Set `additionalProperties: false` on request objects, `query.maxLength: 250`, `top_k.minimum: 1`, `top_k.maximum: 20`, and score threshold bounds 0/1. Document all headers and status codes from Task 7.

- [ ] **Step 4: Write the external integration guide**

Use a test-only example Key such as `rag_test_k_example.example-secret-not-valid`. Include:

```bash
curl -X POST http://127.0.0.1:8090/openapi/v1/retrieval \
  -H 'Authorization: Bearer rag_test_k_example.example-secret-not-valid' \
  -H 'Content-Type: application/json' \
  -d '{"query":"退款需要哪些材料？","retrieval_setting":{"top_k":5,"score_threshold":0.5}}'
```

State that API Key Scope is configured by the tenant administrator and the caller neither sends nor controls a knowledge-base ID. State that HTTP is only for trusted-network test credentials.

- [ ] **Step 5: Run contract test and inspect examples**

```bash
mvn -Dtest=RetrievalOpenApiContractTest test
rg -n "knowledge_id|Authorization|rag_test|HTTPS|429" ../../docs/openapi
```

Expected: test passes; `knowledge_id` appears only in the statement that it is unsupported, never in the request schema/example.

- [ ] **Step 6: Commit documentation**

```bash
git add docs/openapi/retrieval-api.md docs/openapi/retrieval-api.yaml \
  xiaoda-backend-intelligence/Spring-AI/src/test/java/com/fansea/ai/openapi/retrieval/RetrievalOpenApiContractTest.java
git commit -m "docs: publish rag retrieval openapi guide"
```

---

### Task 9: Add end-to-end security, isolation, revocation, and regression verification

**Files:**
- Create: `xiaoda-backend-intelligence/Spring-AI/src/test/java/com/fansea/ai/openapi/it/ExternalRetrievalIntegrationTest.java`
- Create: `xiaoda-backend-intelligence/Spring-AI/scripts/smoke-retrieval-openapi.sh`
- Modify only if a verified test exposes a defect: files introduced in Tasks 1–8.

**Interfaces:**
- Consumes: the complete credential management and retrieval flow.
- Produces: automated proof that auth, tenant scope, Key lifecycle, strict request shape, rate limits, and docs agree.

- [ ] **Step 1: Write an end-to-end integration test with a mocked VectorStore bean**

Use `@SpringBootTest(webEnvironment=RANDOM_PORT)` and `@MockBean VectorStore`. Arrange two tenants, two Knowledge rows per tenant, a tenant-admin JWT, and an API Credential created through the real management endpoint. Verify this sequence:

1. Create a `RAG_RETRIEVAL` test Credential scoped to two same-tenant Knowledge rows.
2. Capture the one-time Key from the create response.
3. Call `/openapi/v1/retrieval` with only Query/settings and receive records.
4. Capture the VectorStore SearchRequest and confirm both authorized internal Knowledge IDs and the authenticated tenant ID occur in one filter.
5. Confirm another tenant's Knowledge ID is absent.
6. Revoke the Key through the real admin endpoint.
7. Call retrieval again and receive 401 immediately, proving active cache invalidation.

- [ ] **Step 2: Add negative security cases**

In the same integration class, assert:

- Raw Key is absent from `api_credential.secret_digest`, application log capture, and list/detail responses.
- A Key with no relations returns 403 and VectorStore is never called.
- `knowledge_id` and `metadata_condition` return 400 and VectorStore is never called.
- A business JWT cannot call Retrieval OpenAPI.
- An external API Key cannot call `/knowledge/list` or `/tenant/api-credentials`.
- Disabled Credential, expired Credential, wrong IP, wrong prefix/type, and Agent type all fail before VectorStore.
- Repeated requests cross the configured burst limit and receive 429 with `Retry-After`.

- [ ] **Step 3: Run the integration test and fix only evidenced failures**

```bash
mvn -Dtest=ExternalRetrievalIntegrationTest test
```

Expected: PASS. If it fails, make the smallest change in the owning Task 1–8 file and add a focused regression assertion before rerunning.

- [ ] **Step 4: Add a safe smoke script**

Create a script that requires explicit environment variables and never echoes the Key:

```bash
#!/usr/bin/env bash
set -euo pipefail
: "${RAG_API_BASE_URL:?set RAG_API_BASE_URL}"
: "${RAG_API_KEY:?set RAG_API_KEY}"
curl --fail-with-body --silent --show-error \
  -X POST "${RAG_API_BASE_URL}/openapi/v1/retrieval" \
  -H "Authorization: Bearer ${RAG_API_KEY}" \
  -H 'Content-Type: application/json' \
  -d '{"query":"测试检索","retrieval_setting":{"top_k":3,"score_threshold":0.0}}'
```

Make it executable and add a shell syntax check.

- [ ] **Step 5: Run complete verification**

```bash
bash -n scripts/smoke-retrieval-openapi.sh
mvn test
```

From the repository root, also run:

```bash
git diff --check
git status --short
```

Expected: all Maven tests pass, script syntax passes, no whitespace errors, and only intentional task files are changed.

- [ ] **Step 6: Commit end-to-end verification**

```bash
git add xiaoda-backend-intelligence/Spring-AI/src/test/java/com/fansea/ai/openapi/it/ExternalRetrievalIntegrationTest.java \
  xiaoda-backend-intelligence/Spring-AI/scripts/smoke-retrieval-openapi.sh
git commit -m "test: verify external rag retrieval flow"
```

---

## Final Verification Gate

Before reporting completion, run from `xiaoda-backend-intelligence/Spring-AI`:

```bash
mvn clean test
```

Then run from the repository root:

```bash
git diff --check
git status --short
git log --oneline -10
```

Confirm all of the following from test output and code inspection:

- V4 migration applies on a fresh schema and rejects non-RAG/cross-tenant Knowledge relations.
- API Key plaintext appears only in the one-time create/rotate response object.
- Both old and active Pepper versions validate while the configured Pepper remains present.
- Default Pepper cannot issue live credentials.
- Authentication logic accesses Caffeine only through `ApiCredentialCache`.
- Retrieval rejects unknown fields, never accepts `knowledge_id`, and uses the complete authenticated RAG Scope.
- Empty Scope, wrong credential type, revoked/expired/disabled Key, wrong IP, and rate limit fail before Embedding/vector search.
- A single VectorStore request covers the authorized Knowledge set and produces global Top K.
- External errors and Rate Limit headers match `docs/openapi/retrieval-api.yaml`.
- Existing JWT, tenant, platform, knowledge, Agent chat, and frontend tests have no regression.
- No request-audit table was introduced and no secret/query/content logging was added.
