# Platform Auth + JWT + Multi-Tenant Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add invitation-based multi-tenant account system with JWT (Access+Refresh) auth to the smart-agent platform, isolating all business data by tenant.

**Architecture:** Row-level tenant isolation via `tenant_id` column. Self-rolled `JwtAuthFilter` (no Spring Security). HS256 Access (15m, stateless) + rotating Refresh (30d, DB-backed, reuse-detection). Cookie-based refresh (httpOnly, SameSite=Lax). Pinia store + axios interceptor + Vue Router guards on the frontend.

**Tech Stack:**
- Backend: Spring Boot 3.3.10, Java 17, MyBatis-Plus 3.5.6, PostgreSQL (with pgvector), JJWT 0.12.x, spring-security-crypto (BCrypt only)
- Frontend: Vue 3, Vite 6, Element Plus, Vue Router 4, Pinia, axios
- Test: JUnit 5, Mockito, `@WebMvcTest`, `@SpringBootTest`

**Spec:** `docs/superpowers/specs/2026-08-26-platform-auth-jwt-tenant-design.md`

---

## Global Constraints

These are project-wide rules; every task must respect them without restating.

- **JWT algorithm**: HS256, key from env `JWT_SECRET` (≥32 bytes), default `dev-secret-please-change-32bytes-min` only for local
- **Access TTL**: 15 minutes; **Refresh TTL**: 30 days
- **Refresh token storage**: DB stores `SHA-256(raw)`, never plaintext; raw is `URL-safe base64` of 32 random bytes
- **Refresh rotation**: every successful `/auth/refresh` issues new row (same `family_id`, `parent_id = old`), marks old `revoked_at = now()`
- **Reuse detection**: presenting a `revoked_at IS NOT NULL` refresh revokes entire `family_id` and returns 40103
- **Tenant isolation**: row-level via `tenant_id BIGINT NOT NULL` on every business table; MyBatis-Plus `TenantLineInnerInterceptor` auto-injects `WHERE tenant_id = ?`
- **`TenantLineInnerInterceptor`** must be registered LAST (innermost) in `MybatisPlusInterceptor`
- **`TenantLineHandler.ignoreTable`** = exactly `{tenant, invite, refresh_token, platform_admin}` — adding new tables requires explicit review
- **`TenantLineHandler.getTenantId()`** returns `null` (skip) when `AuthContext.current() == null`
- **Roles**: `tenant_admin` (write), `tenant_member` (read + AI), `platform_admin` (cross-tenant)
- **Issuer claim**: business users `iss="smart-agent"`, platform admins `iss="smart-agent-platform"`
- **Cookie names**: business `sm_refresh`, platform `sm_platform_refresh`; both `HttpOnly; SameSite=Lax; Path=/; Secure` in prod
- **Errors**: unified body `{code: int, msg: string, data: null}`; WWW-Authenticate header on 401
- **Existing data**: `TRUNCATE agent, knowledge, file, agent_knowledge, knowledge_file RESTART IDENTITY CASCADE` before applying ALTER
- **CORS**: `allowCredentials(true)`, origins = `["http://localhost:5173", "<prod-domain>"]`
- **No Spring Security starter**: only `spring-security-crypto` for BCrypt
- **Commits**: `feat:` for new behavior, `test:` for test-only, `chore:` for config/seed, `fix:` for bug
- **Code comments**: 中文允许,与现有项目风格一致

---

## File Structure

### Backend — new files

```
src/main/java/com/fangsa/ai/auth/
  JwtService.java
  AuthContext.java                       # ThreadLocal 容器
  AuthContextFilter.java                 # 在 JwtAuthFilter 之后,清理 ThreadLocal
  RefreshTokenService.java
  AuthService.java                       # 业务用户登录
  PlatformAuthService.java               # 平台管理员登录
  PasswordEncoderConfig.java             # @Configuration + @Bean BCryptPasswordEncoder
  PasswordEncoder.java                   # interface(便于单测 mock)
  BcryptPasswordEncoder.java             # 实现,委派 spring-security-crypto
  JwtAuthFilter.java                     # OncePerRequestFilter
  TenantContextInterceptor.java          # HandlerInterceptor
  RequireLogin.java                      # @Retention(RUNTIME) @Target(METHOD)
  RequireRole.java                       # value() default "tenant_admin"
  AuthAspect.java                        # @Aspect, 处理上面两个注解
  AuthException.java
  AuthErrorCode.java                     # enum
  AuthAuditLogger.java
  AuthBootstrapRunner.java               # ApplicationRunner, 启动时检查 su 种子
  AuthController.java                    # /auth/*
  TenantMemberController.java            # /tenant/members
  AcceptInviteService.java               # 接受邀请逻辑

src/main/java/com/fangsa/platform/
  PlatformAuthController.java            # /platform/auth/*
  PlatformTenantController.java          # /platform/tenants
  PlatformInviteController.java          # /platform/invites
  PlatformAuthService.java

src/main/java/com/fangsa/ai/config/
  MybatisPlusAuthConfig.java             # 注册 TenantLineInnerInterceptor
  TenantLineHandlerImpl.java             # 实现 TenantLineHandler

src/main/resources/db/
  V1__init_auth_and_tenant.sql           # TRUNCATE + ALTER + 新表 + 索引 + 触发器 + 种子

src/test/java/com/fangsa/ai/auth/
  JwtServiceTest.java
  RefreshTokenServiceTest.java
  AuthServiceTest.java
  PasswordEncoderTest.java
  AuthControllerWebMvcTest.java
  AcceptInviteServiceTest.java
src/test/java/com/fangsa/platform/
  PlatformAuthControllerWebMvcTest.java

src/test/java/com/fangsa/ai/it/
  AuthIntegrationTest.java               # @SpringBootTest + 真实 PG
```

### Backend — modified files

```
pom.xml                                                       # +jjwt +spring-security-crypto
src/main/java/com/fangsa/ai/config/WebMvcConfig.java          # CORS 收紧 + 拦截器注册
src/main/java/com/fangsa/ai/config/GlobalExceptionHandler.java# +AuthException +JWT 异常
src/main/java/com/fangsa/ai/controller/AgentController.java   # +@RequireLogin/@RequireRole
src/main/java/com/fangsa/ai/controller/KnowledgeController.java
src/main/java/com/fangsa/ai/controller/FileController.java
src/main/java/com/fangsa/ai/controller/RagController.java
src/main/java/com/fangsa/ai/controller/AiChatController.java
src/main/java/com/fangsa/ai/controller/AiHistoryController.java
src/main/java/com/fangsa/ai/controller/ToolController.java
src/main/java/com/fangsa/ai/service/RagService.java           # pgvector 加 tenant filter
src/main/resources/application.yml                            # JWT_SECRET、cookie Secure 等
```

### Frontend — new files

```
src/stores/auth.js
src/api/http.js                  # axios 实例 + 拦截器
src/views/Login.vue
src/views/AcceptInvite.vue
src/views/Forbidden.vue
src/views/TenantMembers.vue
src/router/guards.js             # beforeEach
scripts/smoke-auth.sh
```

### Frontend — modified files

```
src/main.js                      # 注册 Pinia + 启动 bootstrap
src/App.vue                      # 挂载时调 auth.bootstrap
src/router/index.js              # 加路由 + meta + 引 guards
vite.config.js                   # proxy /api
package.json                     # +pinia
```

---

## Task 1: DB migration script + seed

**Files:**
- Create: `src/main/resources/db/V1__init_auth_and_tenant.sql`

**Interfaces:**
- Produces: tables `tenant`, `app_user`, `platform_admin`, `invite`, `refresh_token`; columns `tenant_id` on existing tables; seed one platform admin `su` if table empty.

- [ ] **Step 1: Write the SQL file**

`xiaoda-backend-intelligence/Spring-AI/src/main/resources/db/V1__init_auth_and_tenant.sql`:

```sql
-- ============================================================
-- V1: Auth + Multi-tenant
-- Apply order: existing tables first get tenant_id, then new tables.
-- ============================================================

-- 0. Drop existing business data (per spec: 全新建)
TRUNCATE agent, knowledge, file, agent_knowledge, knowledge_file RESTART IDENTITY CASCADE;

-- 1. Add tenant_id to existing tables
ALTER TABLE agent           ADD COLUMN IF NOT EXISTS tenant_id BIGINT NOT NULL DEFAULT 1;
ALTER TABLE knowledge       ADD COLUMN IF NOT EXISTS tenant_id BIGINT NOT NULL DEFAULT 1;
ALTER TABLE file            ADD COLUMN IF NOT EXISTS tenant_id BIGINT NOT NULL DEFAULT 1;
ALTER TABLE agent_knowledge ADD COLUMN IF NOT EXISTS tenant_id BIGINT NOT NULL DEFAULT 1;
ALTER TABLE knowledge_file  ADD COLUMN IF NOT EXISTS tenant_id BIGINT NOT NULL DEFAULT 1;

CREATE INDEX IF NOT EXISTS idx_agent_tenant            ON agent(tenant_id);
CREATE INDEX IF NOT EXISTS idx_knowledge_tenant        ON knowledge(tenant_id);
CREATE INDEX IF NOT EXISTS idx_file_tenant             ON file(tenant_id);
CREATE INDEX IF NOT EXISTS idx_agent_knowledge_tenant  ON agent_knowledge(tenant_id);
CREATE INDEX IF NOT EXISTS idx_knowledge_file_tenant   ON knowledge_file(tenant_id);

-- 2. tenant
CREATE TABLE IF NOT EXISTS tenant (
    id          BIGSERIAL PRIMARY KEY,
    code        VARCHAR(64)  NOT NULL UNIQUE,
    name        VARCHAR(128) NOT NULL,
    status      SMALLINT     NOT NULL DEFAULT 1,
    create_time TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
DROP TRIGGER IF EXISTS trigger_update_tenant_timestamp ON tenant;
CREATE TRIGGER trigger_update_tenant_timestamp BEFORE UPDATE ON tenant
    FOR EACH ROW EXECUTE FUNCTION update_timestamp();

-- 3. app_user
CREATE TABLE IF NOT EXISTS app_user (
    id            BIGSERIAL PRIMARY KEY,
    tenant_id     BIGINT       NOT NULL REFERENCES tenant(id) ON DELETE RESTRICT,
    username      VARCHAR(64)  NOT NULL,
    password_hash VARCHAR(128) NOT NULL,
    display_name  VARCHAR(128),
    role          VARCHAR(32)  NOT NULL,
    status        SMALLINT     NOT NULL DEFAULT 1,
    last_login_at TIMESTAMP WITH TIME ZONE,
    create_time   TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time   TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (tenant_id, username)
);
CREATE INDEX IF NOT EXISTS idx_app_user_tenant ON app_user(tenant_id);
DROP TRIGGER IF EXISTS trigger_update_app_user_timestamp ON app_user;
CREATE TRIGGER trigger_update_app_user_timestamp BEFORE UPDATE ON app_user
    FOR EACH ROW EXECUTE FUNCTION update_timestamp();

-- 4. platform_admin
CREATE TABLE IF NOT EXISTS platform_admin (
    id            BIGSERIAL PRIMARY KEY,
    username      VARCHAR(64)  NOT NULL UNIQUE,
    password_hash VARCHAR(128) NOT NULL,
    create_time   TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 5. invite
CREATE TABLE IF NOT EXISTS invite (
    id            BIGSERIAL PRIMARY KEY,
    tenant_id     BIGINT       NOT NULL REFERENCES tenant(id) ON DELETE CASCADE,
    code          VARCHAR(64)  NOT NULL UNIQUE,
    intended_role VARCHAR(32)  NOT NULL,
    accepted_by   BIGINT       REFERENCES app_user(id),
    expires_at    TIMESTAMP WITH TIME ZONE NOT NULL,
    create_time   TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_invite_tenant ON invite(tenant_id);

-- 6. refresh_token
CREATE TABLE IF NOT EXISTS refresh_token (
    id          BIGSERIAL PRIMARY KEY,
    user_id     BIGINT       NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    family_id   UUID         NOT NULL,
    token_hash  VARCHAR(128) NOT NULL UNIQUE,
    parent_id   BIGINT       REFERENCES refresh_token(id),
    rotated_at  TIMESTAMP WITH TIME ZONE,
    revoked_at  TIMESTAMP WITH TIME ZONE,
    expires_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    user_agent  VARCHAR(256),
    ip          VARCHAR(64),
    create_time TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_refresh_user   ON refresh_token(user_id);
CREATE INDEX IF NOT EXISTS idx_refresh_family ON refresh_token(family_id);

-- 7. Seed: default tenant (id=1); platform admin su 密码由 AuthBootstrapRunner 在运行时生成
INSERT INTO tenant (id, code, name) VALUES (1, 'default', 'Default Tenant')
    ON CONFLICT (id) DO NOTHING;
-- 调整 sequence,使后续 INSERT 从 2 起(避免与种子 id=1 冲突)
SELECT setval(pg_get_serial_sequence('tenant', 'id'), GREATEST((SELECT MAX(id) FROM tenant), 1));
```

- [ ] **Step 2: Verify the script applies cleanly**

```bash
# 在 dev PG 上手动跑一次,要求:无错误,所有表/索引/触发器就绪
psql "postgresql://postgres:123456@localhost:5432/aichat" -f src/main/resources/db/V1__init_auth_and_tenant.sql
```

Expected: script completes; `\dt` shows new tables; `\d tenant` shows `update_time` trigger.

- [ ] **Step 3: Commit**

```bash
cd xiaoda-backend-intelligence/Spring-AI
git add src/main/resources/db/V1__init_auth_and_tenant.sql
git commit -m "chore(db): tenant schema, auth tables, default tenant seed"
```

---

## Task 2: Add jjwt + spring-security-crypto deps

**Files:**
- Modify: `pom.xml`

- [ ] **Step 1: Add jjwt dependencies**

Inside `<dependencies>` (around line 82, before `</dependencies>`):

```xml
<dependency>
    <groupId>io.jsonwebtoken</groupId>
    <artifactId>jjwt-api</artifactId>
    <version>0.12.6</version>
</dependency>
<dependency>
    <groupId>io.jsonwebtoken</groupId>
    <artifactId>jjwt-impl</artifactId>
    <version>0.12.6</version>
    <scope>runtime</scope>
</dependency>
<dependency>
    <groupId>io.jsonwebtoken</groupId>
    <artifactId>jjwt-jackson</artifactId>
    <version>0.12.6</version>
    <scope>runtime</scope>
</dependency>
<!-- 仅用其 BCryptPasswordEncoder,不引入 Security starter -->
<dependency>
    <groupId>org.springframework.security</groupId>
    <artifactId>spring-security-crypto</artifactId>
</dependency>
```

- [ ] **Step 2: Verify Maven resolves**

```bash
cd xiaoda-backend-intelligence/Spring-AI
./mvnw -q -DskipTests dependency:resolve | grep -E "jjwt|spring-security-crypto"
```

Expected: 4 lines (jjwt-api, jjwt-impl, jjwt-jackson, spring-security-crypto) all resolved.

- [ ] **Step 3: Commit**

```bash
cd xiaoda-backend-intelligence/Spring-AI
git add pom.xml
git commit -m "chore(deps): add jjwt 0.12.6 and spring-security-crypto"
```

---

## Task 3: JwtService — sign + verify (TDD)

**Files:**
- Create: `src/main/java/com/fangsa/ai/auth/JwtService.java`
- Test: `src/test/java/com/fangsa/ai/auth/JwtServiceTest.java`

**Interfaces:**
- Produces:
  - `JwtService.signAccess(long userId, long tenantId, String role) → String` — returns compact JWT
  - `JwtService.verifyAccess(String token) → ParsedClaims` — throws `AuthException` with codes 40100/40101
  - `JwtService.signPlatformAccess(long platformAdminId) → String`
  - `ParsedClaims { getSub(); getTenantId(); getRole(); getIssuer(); getJti(); getExpiresAt(); }`

- [ ] **Step 1: Write failing test**

`src/test/java/com/fangsa/ai/auth/JwtServiceTest.java`:

```java
package com.fangsa.ai.auth;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class JwtServiceTest {

    private final JwtService svc = new JwtService(
            "test-secret-must-be-at-least-32-bytes-long-for-hs256",
            15 * 60 * 1000L
    );

    @Test
    void signAndVerify_roundTrip_business() {
        String token = svc.signAccess(42L, 7L, "tenant_admin");
        ParsedClaims c = svc.verifyAccess(token);
        assertEquals("smart-agent", c.getIssuer());
        assertEquals("u:42", c.getSub());
        assertEquals(7L, c.getTenantId());
        assertEquals("tenant_admin", c.getRole());
        assertNotNull(c.getJti());
        assertTrue(c.getExpiresAt().isAfter(Instant.now()));
    }

    @Test
    void signAndVerify_roundTrip_platform() {
        String token = svc.signPlatformAccess(1L);
        ParsedClaims c = svc.verifyAccess(token);
        assertEquals("smart-agent-platform", c.getIssuer());
        assertEquals("pa:1", c.getSub());
        assertNull(c.getTenantId(), "platform admin must not carry tenant claim");
        assertEquals("platform_admin", c.getRole());
    }

    @Test
    void verifyAccess_expiredToken_throws40101() throws InterruptedException {
        JwtService shortSvc = new JwtService(
                "test-secret-must-be-at-least-32-bytes-long-for-hs256",
                1L // 1 ms TTL
        );
        String t = shortSvc.signAccess(1L, 1L, "tenant_admin");
        Thread.sleep(50);
        AuthException ex = assertThrows(AuthException.class, () -> shortSvc.verifyAccess(t));
        assertEquals(40101, ex.getCode());
    }

    @Test
    void verifyAccess_tamperedSignature_throws40100() {
        String t = svc.signAccess(1L, 1L, "tenant_admin");
        String tampered = t.substring(0, t.length() - 4) + "AAAA";
        AuthException ex = assertThrows(AuthException.class, () -> svc.verifyAccess(tampered));
        assertEquals(40100, ex.getCode());
    }

    @Test
    void verifyAccess_wrongIssuer_throws40100() {
        JwtService other = new JwtService(
                "test-secret-must-be-at-least-32-bytes-long-for-hs256",
                15 * 60 * 1000L
        );
        // 模拟平台 token 走业务验签路径
        String t = other.signPlatformAccess(1L);
        AuthException ex = assertThrows(AuthException.class, () -> svc.verifyAccess(t));
        assertEquals(40100, ex.getCode());
    }
}
```

- [ ] **Step 2: Run, expect FAIL**

```bash
cd xiaoda-backend-intelligence/Spring-AI
./mvnw -q test -Dtest=JwtServiceTest
```

Expected: compilation error (no JwtService/AuthException/ParsedClaims yet).

- [ ] **Step 3: Write minimal AuthErrorCode + AuthException + ParsedClaims + JwtService**

`src/main/java/com/fangsa/ai/auth/AuthErrorCode.java`:

```java
package com.fangsa.ai.auth;

public enum AuthErrorCode {
    MISSING_TOKEN(40100),
    EXPIRED(40101),
    REFRESH_EXPIRED(40102),
    REFRESH_REUSE(40103),
    FORBIDDEN_ROLE(40301),
    CROSS_TENANT(40302),
    INVITE_INVALID(41001),
    USERNAME_CONFLICT(40901);

    private final int code;
    AuthErrorCode(int code) { this.code = code; }
    public int code() { return code; }
}
```

`src/main/java/com/fangsa/ai/auth/AuthException.java`:

```java
package com.fangsa.ai.auth;

public class AuthException extends RuntimeException {
    private final int code;
    public AuthException(AuthErrorCode code, String msg) {
        super(msg);
        this.code = code.code();
    }
    public AuthException(int code, String msg) {
        super(msg);
        this.code = code;
    }
    public int getCode() { return code; }
}
```

`src/main/java/com/fangsa/ai/auth/ParsedClaims.java`:

```java
package com.fangsa.ai.auth;

import java.time.Instant;

public class ParsedClaims {
    private final String issuer;
    private final String sub;
    private final Long tenantId;
    private final String role;
    private final String jti;
    private final Instant expiresAt;

    public ParsedClaims(String issuer, String sub, Long tenantId, String role, String jti, Instant expiresAt) {
        this.issuer = issuer; this.sub = sub; this.tenantId = tenantId;
        this.role = role; this.jti = jti; this.expiresAt = expiresAt;
    }

    public String getIssuer() { return issuer; }
    public String getSub() { return sub; }
    public Long getTenantId() { return tenantId; }
    public String getRole() { return role; }
    public String getJti() { return jti; }
    public Instant getExpiresAt() { return expiresAt; }
}
```

`src/main/java/com/fangsa/ai/auth/JwtService.java`:

```java
package com.fangsa.ai.auth;

import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

@Service
public class JwtService {

    public static final String ISS_BUSINESS = "smart-agent";
    public static final String ISS_PLATFORM = "smart-agent-platform";
    public static final String ROLE_PLATFORM_ADMIN = "platform_admin";

    private final SecretKey key;
    private final long accessTtlMillis;

    public JwtService(@Value("${jwt.secret:dev-secret-please-change-32bytes-min}") String secret,
                      @Value("${jwt.access-ttl-millis:900000}") long accessTtlMillis) {
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 32) {
            throw new IllegalStateException("jwt.secret must be >= 32 bytes");
        }
        this.key = Keys.hmacShaKeyFor(bytes);
        this.accessTtlMillis = accessTtlMillis;
    }

    public String signAccess(long userId, long tenantId, String role) {
        Instant now = Instant.now();
        return Jwts.builder()
                .issuer(ISS_BUSINESS)
                .subject("u:" + userId)
                .claim("tid", tenantId)
                .claim("role", role)
                .id(UUID.randomUUID().toString())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusMillis(accessTtlMillis)))
                .signWith(key, Jwts.SIG.HS256)
                .compact();
    }

    public String signPlatformAccess(long platformAdminId) {
        Instant now = Instant.now();
        return Jwts.builder()
                .issuer(ISS_PLATFORM)
                .subject("pa:" + platformAdminId)
                .claim("role", ROLE_PLATFORM_ADMIN)
                .id(UUID.randomUUID().toString())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusMillis(accessTtlMillis)))
                .signWith(key, Jwts.SIG.HS256)
                .compact();
    }

    public ParsedClaims verifyAccess(String token) {
        try {
            Jws<Claims> jws = Jwts.parser()
                    .verifyWith(key)
                    .requireIssuer(ISS_BUSINESS, ISS_PLATFORM)
                    .build()
                    .parseSignedClaims(token);
            Claims c = jws.getPayload();
            Long tid = c.get("tid", Long.class);
            return new ParsedClaims(
                    c.getIssuer(),
                    c.getSubject(),
                    tid,
                    c.get("role", String.class),
                    c.getId(),
                    c.getExpiration().toInstant()
            );
        } catch (ExpiredJwtException e) {
            throw new AuthException(AuthErrorCode.EXPIRED, "access token expired");
        } catch (JwtException | IllegalArgumentException e) {
            throw new AuthException(AuthErrorCode.MISSING_TOKEN, "invalid token: " + e.getMessage());
        }
    }
}
```

- [ ] **Step 4: Run, expect PASS**

```bash
cd xiaoda-backend-intelligence/Spring-AI
./mvnw -q test -Dtest=JwtServiceTest
```

Expected: 5 tests pass.

- [ ] **Step 5: Commit**

```bash
cd xiaoda-backend-intelligence/Spring-AI
git add src/main/java/com/fangsa/ai/auth/ src/test/java/com/fangsa/ai/auth/JwtServiceTest.java
git commit -m "feat(auth): JwtService with HS256 sign/verify, platform + business claims"
```

---

## Task 4: PasswordEncoder abstraction + BCrypt impl

**Files:**
- Create: `src/main/java/com/fangsa/ai/auth/PasswordEncoder.java`
- Create: `src/main/java/com/fangsa/ai/auth/BcryptPasswordEncoder.java`
- Create: `src/main/java/com/fangsa/ai/auth/PasswordEncoderConfig.java`
- Test: `src/test/java/com/fangsa/ai/auth/PasswordEncoderTest.java`

**Interfaces:**
- Produces: `PasswordEncoder.hash(String raw) → String`, `PasswordEncoder.matches(String raw, String hash) → boolean`
- Spring bean: `PasswordEncoder passwordEncoder()`

- [ ] **Step 1: Write failing test**

```java
package com.fangsa.ai.auth;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PasswordEncoderTest {
    private final PasswordEncoder enc = new BcryptPasswordEncoder();

    @Test
    void hashAndMatch_roundTrip() {
        String h = enc.hash("hunter2!");
        assertTrue(enc.matches("hunter2!", h));
        assertFalse(enc.matches("hunter3!", h));
    }

    @Test
    void hash_producesDifferentOutputEachTime() {
        assertNotEquals(enc.hash("x"), enc.hash("x"));
    }
}
```

- [ ] **Step 2: Run, expect FAIL**

```bash
./mvnw -q test -Dtest=PasswordEncoderTest
```

Expected: compilation error.

- [ ] **Step 3: Implement**

`src/main/java/com/fangsa/ai/auth/PasswordEncoder.java`:

```java
package com.fangsa.ai.auth;

public interface PasswordEncoder {
    String hash(String raw);
    boolean matches(String raw, String hash);
}
```

`src/main/java/com/fangsa/ai/auth/BcryptPasswordEncoder.java`:

```java
package com.fangsa.ai.auth;

import org.springframework.security.crypto.bcrypt.BCrypt;

public class BcryptPasswordEncoder implements PasswordEncoder {
    @Override public String hash(String raw) { return BCrypt.hashpw(raw, BCrypt.gensalt(10)); }
    @Override public boolean matches(String raw, String hash) { return BCrypt.checkpw(raw, hash); }
}
```

`src/main/java/com/fangsa/ai/auth/PasswordEncoderConfig.java`:

```java
package com.fangsa.ai.auth;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class PasswordEncoderConfig {
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BcryptPasswordEncoder();
    }
}
```

- [ ] **Step 4: Run, expect PASS**

```bash
./mvnw -q test -Dtest=PasswordEncoderTest
```

Expected: 2 tests pass.

- [ ] **Step 5: Commit**

```bash
cd xiaoda-backend-intelligence/Spring-AI
git add src/main/java/com/fangsa/ai/auth/PasswordEncoder.java \
        src/main/java/com/fangsa/ai/auth/BcryptPasswordEncoder.java \
        src/main/java/com/fangsa/ai/auth/PasswordEncoderConfig.java \
        src/test/java/com/fangsa/ai/auth/PasswordEncoderTest.java
git commit -m "feat(auth): PasswordEncoder abstraction + BCrypt impl"
```

---

## Task 5: RefreshTokenService — hash / rotate / reuse detect (TDD)

**Files:**
- Create: `src/main/java/com/fangsa/ai/auth/RefreshTokenService.java`
- Test: `src/test/java/com/fangsa/ai/auth/RefreshTokenServiceTest.java`

**Interfaces:**
- Produces:
  - `IssueResult issue(long userId, String userAgent, String ip) → {rawToken, expiresAt, familyId}`
  - `RotateResult rotate(String rawToken, String userAgent, String ip) → {newRawToken, userId, familyId}` — throws AuthException(40102 expired/revoked, 40103 reuse)
  - `void revokeFamily(UUID familyId)` — for logout

- [ ] **Step 1: Write failing test**

```java
package com.fangsa.ai.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RefreshTokenServiceTest {

    private RefreshTokenStore store;
    private RefreshTokenService svc;
    private final long userId = 42L;
    private final long ttlMillis = 30L * 24 * 3600 * 1000;

    @BeforeEach
    void setUp() {
        store = mock(RefreshTokenStore.class);
        svc = new RefreshTokenService(store, ttlMillis);
    }

    @Test
    void issue_returnsRaw_andPersistsHash() {
        RefreshTokenStore.IssueInsert insert = new RefreshTokenStore.IssueInsert(
                userId, UUID.randomUUID(), "hash-xyz", Instant.now().plusMillis(ttlMillis),
                null, null, null);
        when(store.insert(any())).thenReturn(insert);

        RefreshTokenService.IssueResult r = svc.issue(userId, "ua", "ip");
        assertNotNull(r.rawToken());
        assertEquals(insert.expiresAt(), r.expiresAt());

        ArgumentCaptor<RefreshTokenStore.InsertParams> cap = ArgumentCaptor.forClass(RefreshTokenStore.InsertParams.class);
        verify(store).insert(cap.capture());
        RefreshTokenStore.InsertParams p = cap.getValue();
        assertEquals(userId, p.userId());
        assertEquals(64, p.rawToken().length(), "raw token must be at least 64 chars base64");
        assertTrue(p.tokenHash().matches("^[a-f0-9]{64}$"), "hash must be SHA-256 hex");
        assertNotEquals(p.rawToken(), p.tokenHash());
    }

    @Test
    void rotate_happyPath_issuesNewAndRevokesOld() {
        String raw = "old-raw-token-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
        UUID family = UUID.randomUUID();
        RefreshTokenStore.LookupRow oldRow = new RefreshTokenStore.LookupRow(
                100L, userId, family, null, null, Instant.now().plusSeconds(60));
        when(store.findByHash(any())).thenReturn(Optional.of(oldRow));

        RefreshTokenStore.IssueInsert newInsert = new RefreshTokenStore.IssueInsert(
                userId, family, "newhash", Instant.now().plusSeconds(60), 100L, null, null);
        when(store.insert(any())).thenReturn(newInsert);

        RefreshTokenService.RotateResult r = svc.rotate(raw, "ua", "ip");
        assertEquals(userId, r.userId());
        assertNotEquals(raw, r.newRawToken());

        verify(store).revoke(100L);
        verify(store).insert(any());
    }

    @Test
    void rotate_revokedOldToken_throws40103_andRevokesFamily() {
        String raw = "stolen-raw-token";
        UUID family = UUID.randomUUID();
        RefreshTokenStore.LookupRow oldRow = new RefreshTokenStore.LookupRow(
                100L, userId, family, 99L, Instant.now(), Instant.now().plusSeconds(60));
        when(store.findByHash(any())).thenReturn(Optional.of(oldRow));

        AuthException ex = assertThrows(AuthException.class, () -> svc.rotate(raw, "ua", "ip"));
        assertEquals(40103, ex.getCode());
        verify(store).revokeFamily(family);
    }

    @Test
    void rotate_expiredToken_throws40102() {
        RefreshTokenStore.LookupRow oldRow = new RefreshTokenStore.LookupRow(
                100L, userId, UUID.randomUUID(), null, null, Instant.now().minusSeconds(1));
        when(store.findByHash(any())).thenReturn(Optional.of(oldRow));
        AuthException ex = assertThrows(AuthException.class, () -> svc.rotate("x", null, null));
        assertEquals(40102, ex.getCode());
    }

    @Test
    void rotate_unknownToken_throws40102() {
        when(store.findByHash(any())).thenReturn(Optional.empty());
        AuthException ex = assertThrows(AuthException.class, () -> svc.rotate("x", null, null));
        assertEquals(40102, ex.getCode());
    }

    @Test
    void revokeFamily_callsStore() {
        UUID f = UUID.randomUUID();
        svc.revokeFamily(f);
        verify(store).revokeFamily(f);
    }
}
```

- [ ] **Step 2: Run, expect FAIL**

```bash
./mvnw -q test -Dtest=RefreshTokenServiceTest
```

Expected: compilation error (RefreshTokenService, RefreshTokenStore not defined).

- [ ] **Step 3: Implement service + store interface**

`src/main/java/com/fangsa/ai/auth/RefreshTokenStore.java`:

```java
package com.fangsa.ai.auth;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface RefreshTokenStore {

    record InsertParams(long userId, UUID familyId, String rawToken, String tokenHash,
                        Instant expiresAt, String userAgent, String ip) {}

    record IssueInsert(long userId, UUID familyId, String tokenHash, Instant expiresAt,
                       Long parentId, String userAgent, String ip) {}

    record LookupRow(long id, long userId, UUID familyId,
                     Long parentId, Instant revokedAt, Instant expiresAt) {}

    IssueInsert insert(InsertParams p);
    Optional<LookupRow> findByHash(String tokenHash);
    void revoke(long id);
    void revokeFamily(UUID familyId);
}
```

`src/main/java/com/fangsa/ai/auth/RefreshTokenService.java`:

```java
package com.fangsa.ai.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

@Service
public class RefreshTokenService {

    private static final SecureRandom RNG = new SecureRandom();

    private final RefreshTokenStore store;
    private final long ttlMillis;

    public RefreshTokenService(RefreshTokenStore store,
                               @Value("${jwt.refresh-ttl-millis:2592000000}") long ttlMillis) {
        this.store = store;
        this.ttlMillis = ttlMillis;
    }

    public record IssueResult(String rawToken, Instant expiresAt, UUID familyId) {}
    public record RotateResult(String newRawToken, long userId, UUID familyId) {}

    public IssueResult issue(long userId, String userAgent, String ip) {
        String raw = newRawToken();
        String hash = sha256(raw);
        UUID family = UUID.randomUUID();
        Instant exp = Instant.now().plusMillis(ttlMillis);
        store.insert(new RefreshTokenStore.InsertParams(userId, family, raw, hash, exp, userAgent, ip));
        return new IssueResult(raw, exp, family);
    }

    public RotateResult rotate(String rawToken, String userAgent, String ip) {
        String hash = sha256(rawToken);
        RefreshTokenStore.LookupRow row = store.findByHash(hash)
                .orElseThrow(() -> new AuthException(AuthErrorCode.REFRESH_EXPIRED, "refresh not found"));

        if (row.revokedAt() != null) {
            store.revokeFamily(row.familyId());
            throw new AuthException(AuthErrorCode.REFRESH_REUSE, "refresh reuse detected");
        }
        if (row.expiresAt().isBefore(Instant.now())) {
            throw new AuthException(AuthErrorCode.REFRESH_EXPIRED, "refresh expired");
        }

        store.revoke(row.id());
        String newRaw = newRawToken();
        String newHash = sha256(newRaw);
        Instant newExp = Instant.now().plusMillis(ttlMillis);
        store.insert(new RefreshTokenStore.InsertParams(row.userId(), row.familyId(), newRaw, newHash, newExp, userAgent, ip));
        return new RotateResult(newRaw, row.userId(), row.familyId());
    }

    public void revokeFamily(UUID familyId) { store.revokeFamily(familyId); }

    private static String newRawToken() {
        byte[] buf = new byte[32];
        RNG.nextBytes(buf);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
    }

    private static String sha256(String s) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
```

- [ ] **Step 4: Run, expect PASS**

```bash
./mvnw -q test -Dtest=RefreshTokenServiceTest
```

Expected: 6 tests pass.

- [ ] **Step 5: Commit**

```bash
cd xiaoda-backend-intelligence/Spring-AI
git add src/main/java/com/fangsa/ai/auth/RefreshTokenStore.java \
        src/main/java/com/fangsa/ai/auth/RefreshTokenService.java \
        src/test/java/com/fangsa/ai/auth/RefreshTokenServiceTest.java
git commit -m "feat(auth): RefreshTokenService with rotation and reuse detection"
```

---

## Task 6: MyBatis-Plus RefreshTokenStoreImpl + mapper

**Files:**
- Create: `src/main/java/com/fangsa/ai/mapper/RefreshTokenMapper.java`
- Create: `src/main/resources/mapper/RefreshTokenMapper.xml`
- Create: `src/main/java/com/fangsa/ai/auth/RefreshTokenStoreImpl.java`

**Interfaces:**
- Implements `RefreshTokenStore` using MyBatis-Plus; bean named `refreshTokenStore` (override default naming so RefreshTokenService gets the impl).

- [ ] **Step 1: Mapper interface**

`src/main/java/com/fangsa/ai/mapper/RefreshTokenMapper.java`:

```java
package com.fangsa.ai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fangsa.ai.domain.RefreshToken;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface RefreshTokenMapper extends BaseMapper<RefreshToken> {
    int revokeById(@Param("id") long id);
    int revokeByFamily(@Param("familyId") java.util.UUID familyId);
}
```

- [ ] **Step 2: XML mapper**

`src/main/resources/mapper/RefreshTokenMapper.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN"
        "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
<mapper namespace="com.fangsa.ai.mapper.RefreshTokenMapper">
    <update id="revokeById">
        UPDATE refresh_token SET revoked_at = CURRENT_TIMESTAMP
        WHERE id = #{id} AND revoked_at IS NULL
    </update>
    <update id="revokeByFamily">
        UPDATE refresh_token SET revoked_at = CURRENT_TIMESTAMP
        WHERE family_id = #{familyId,jdbcType=OTHER,typeHandler=com.fangsa.ai.auth.UuidTypeHandler}
          AND revoked_at IS NULL
    </update>
</mapper>
```

- [ ] **Step 3: UUID TypeHandler**

`src/main/java/com/fangsa/ai/auth/UuidTypeHandler.java`:

```java
package com.fangsa.ai.auth;

import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;

import java.sql.*;
import java.util.UUID;

public class UuidTypeHandler extends BaseTypeHandler<UUID> {
    @Override public void setNonNullParameter(PreparedStatement ps, int i, UUID p, JdbcType jdbcType) throws SQLException {
        ps.setObject(i, p);
    }
    @Override public UUID getNullableResult(ResultSet rs, String col) throws SQLException {
        Object o = rs.getObject(col); return o == null ? null : UUID.fromString(o.toString());
    }
    @Override public UUID getNullableResult(ResultSet rs, int col) throws SQLException {
        Object o = rs.getObject(col); return o == null ? null : UUID.fromString(o.toString());
    }
    @Override public UUID getNullableResult(CallableStatement cs, int col) throws SQLException {
        Object o = cs.getObject(col); return o == null ? null : UUID.fromString(o.toString());
    }
}
```

- [ ] **Step 4: Domain**

`src/main/java/com/fangsa/ai/domain/RefreshToken.java`:

```java
package com.fangsa.ai.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.UUID;

@Data
@TableName("refresh_token")
public class RefreshToken {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private UUID familyId;
    private String tokenHash;
    private Long parentId;
    private OffsetDateTime rotatedAt;
    private OffsetDateTime revokedAt;
    private OffsetDateTime expiresAt;
    private String userAgent;
    private String ip;
    private OffsetDateTime createTime;
}
```

- [ ] **Step 5: Store impl**

`src/main/java/com/fangsa/ai/auth/RefreshTokenStoreImpl.java`:

```java
package com.fangsa.ai.auth;

import com.fangsa.ai.domain.RefreshToken;
import com.fangsa.ai.mapper.RefreshTokenMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

@Component("refreshTokenStore")
public class RefreshTokenStoreImpl implements RefreshTokenStore {

    private final RefreshTokenMapper mapper;

    @Autowired
    public RefreshTokenStoreImpl(RefreshTokenMapper mapper) { this.mapper = mapper; }

    @Override
    public IssueInsert insert(InsertParams p) {
        RefreshToken t = new RefreshToken();
        t.setUserId(p.userId());
        t.setFamilyId(p.familyId());
        t.setTokenHash(p.tokenHash());
        t.setExpiresAt(toOdt(p.expiresAt()));
        t.setUserAgent(p.userAgent());
        t.setIp(p.ip());
        mapper.insert(t);
        return new IssueInsert(p.userId(), p.familyId(), p.tokenHash(),
                p.expiresAt(), null, p.userAgent(), p.ip());
    }

    @Override
    public Optional<LookupRow> findByHash(String tokenHash) {
        return mapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<RefreshToken>()
                        .eq("token_hash", tokenHash)
        ).stream().findFirst().map(t -> new LookupRow(
                t.getId(), t.getUserId(), t.getFamilyId(),
                t.getParentId(),
                t.getRevokedAt() == null ? null : t.getRevokedAt().toInstant(),
                t.getExpiresAt().toInstant()
        ));
    }

    @Override public void revoke(long id) { mapper.revokeById(id); }
    @Override public void revokeFamily(UUID familyId) { mapper.revokeByFamily(familyId); }

    private static OffsetDateTime toOdt(Instant i) { return i.atOffset(ZoneOffset.UTC); }
}
```

- [ ] **Step 6: Compile check**

```bash
cd xiaoda-backend-intelligence/Spring-AI
./mvnw -q compile
```

Expected: success.

- [ ] **Step 7: Re-run RefreshTokenServiceTest, expect PASS**

```bash
./mvnw -q test -Dtest=RefreshTokenServiceTest
```

Expected: 6 tests still pass (the impl is wired via Spring context only, the service tests mock the store).

- [ ] **Step 8: Commit**

```bash
cd xiaoda-backend-intelligence/Spring-AI
git add src/main/java/com/fangsa/ai/mapper/RefreshTokenMapper.java \
        src/main/java/com/fangsa/ai/domain/RefreshToken.java \
        src/main/java/com/fangsa/ai/auth/RefreshTokenStoreImpl.java \
        src/main/java/com/fangsa/ai/auth/UuidTypeHandler.java \
        src/main/resources/mapper/RefreshTokenMapper.xml
git commit -m "feat(auth): MyBatis RefreshTokenStore impl with UUID TypeHandler"
```

---

## Task 7: AuthContext + JwtAuthFilter + WebMvcConfig wiring

**Files:**
- Create: `src/main/java/com/fangsa/ai/auth/AuthContext.java`
- Create: `src/main/java/com/fangsa/ai/auth/JwtAuthFilter.java`
- Modify: `src/main/java/com/fangsa/ai/config/WebMvcConfig.java`

**Interfaces:**
- `AuthContext.current() → AuthContext` (ThreadLocal-backed)
- `AuthContext.clear()` called by filter after request
- `JwtAuthFilter extends OncePerRequestFilter`

- [ ] **Step 1: AuthContext**

`src/main/java/com/fangsa/ai/auth/AuthContext.java`:

```java
package com.fangsa.ai.auth;

public class AuthContext {
    public enum Kind { BUSINESS, PLATFORM }

    private static final ThreadLocal<AuthContext> HOLDER = new ThreadLocal<>();

    private final Kind kind;
    private final long userId;
    private final Long tenantId;
    private final String role;
    private final String jti;

    public AuthContext(Kind kind, long userId, Long tenantId, String role, String jti) {
        this.kind = kind; this.userId = userId; this.tenantId = tenantId;
        this.role = role; this.jti = jti;
    }

    public static AuthContext current() { return HOLDER.get(); }
    public static void set(AuthContext ctx) { HOLDER.set(ctx); }
    public static void clear() { HOLDER.remove(); }

    public Kind getKind() { return kind; }
    public long getUserId() { return userId; }
    public Long getTenantId() { return tenantId; }
    public String getRole() { return role; }
    public String getJti() { return jti; }
}
```

- [ ] **Step 2: JwtAuthFilter**

`src/main/java/com/fangsa/ai/auth/JwtAuthFilter.java`:

```java
package com.fangsa.ai.auth;

import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Set;

public class JwtAuthFilter extends OncePerRequestFilter {

    private static final Set<String> EXCLUDED_PREFIXES = Set.of(
            "/auth/login", "/auth/refresh", "/auth/accept-invite",
            "/platform/auth/login", "/platform/auth/refresh", "/platform/auth/logout"
    );

    private final JwtService jwtService;

    public JwtAuthFilter(JwtService jwtService) { this.jwtService = jwtService; }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest req) {
        String p = req.getRequestURI();
        for (String e : EXCLUDED_PREFIXES) if (p.startsWith(e)) return true;
        return false;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse resp, FilterChain chain)
            throws ServletException, IOException {
        AuthContext ctx = null;
        String auth = req.getHeader("Authorization");
        if (auth != null && auth.startsWith("Bearer ")) {
            String token = auth.substring(7);
            try {
                ParsedClaims c = jwtService.verifyAccess(token);
                long uid = Long.parseLong(c.getSub().substring(2));
                AuthContext.Kind kind = "smart-agent-platform".equals(c.getIssuer())
                        ? AuthContext.Kind.PLATFORM : AuthContext.Kind.BUSINESS;
                ctx = new AuthContext(kind, uid, c.getTenantId(), c.getRole(), c.getJti());
            } catch (AuthException e) {
                // 401 — filter 不直接写响应,交给 GlobalExceptionHandler 兜底
                req.setAttribute("authException", e);
            } catch (JwtException e) {
                req.setAttribute("authException",
                        new AuthException(AuthErrorCode.MISSING_TOKEN, "invalid token"));
            }
        }
        if (ctx != null) AuthContext.set(ctx);
        try {
            chain.doFilter(req, resp);
        } finally {
            AuthContext.clear();
        }
    }
}
```

- [ ] **Step 3: WebMvcConfig — CORS tighten + filter registration**

Replace `src/main/java/com/fangsa/ai/config/WebMvcConfig.java`:

```java
package com.fangsa.ai.config;

import com.fangsa.ai.auth.JwtAuthFilter;
import com.fangsa.ai.auth.JwtService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final JwtService jwtService;

    @Autowired
    public WebMvcConfig(JwtService jwtService) { this.jwtService = jwtService; }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                .allowedOrigins(List.of("http://localhost:5173").toArray(new String[0]))
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .exposedHeaders("Authorization")
                .allowCredentials(true)
                .maxAge(3600);
    }

    @Override
    public void addInterceptors(org.springframework.web.servlet.config.annotation.InterceptorRegistry registry) {
        // 注:OncePerRequestFilter 通过 Spring Boot 自动注册为 filter,
        // 这里只注册 HandlerInterceptor
        registry.addInterceptor(new com.fangsa.ai.auth.TenantContextInterceptor())
                .addPathPatterns("/**");
    }
}
```

- [ ] **Step 4: Register JwtAuthFilter as a Spring bean**

Add to `PasswordEncoderConfig.java` (or create a new `AuthFilterConfig`):

```java
@Bean
public FilterRegistrationBean<JwtAuthFilter> jwtAuthFilterRegistration(JwtService jwtService) {
    FilterRegistrationBean<JwtAuthFilter> reg = new FilterRegistrationBean<>(new JwtAuthFilter(jwtService));
    reg.addUrlPatterns("/*");
    reg.setOrder(10); // 在 CorsFilter 之后,Controller 之前
    return reg;
}
```

- [ ] **Step 5: Compile**

```bash
cd xiaoda-backend-intelligence/Spring-AI
./mvnw -q compile
```

Expected: `TenantContextInterceptor` not yet defined — fix in next task. Expect a **deliberate** compile error on that line.

- [ ] **Step 6: Commit**

```bash
cd xiaoda-backend-intelligence/Spring-AI
git add src/main/java/com/fangsa/ai/auth/AuthContext.java \
        src/main/java/com/fangsa/ai/auth/JwtAuthFilter.java \
        src/main/java/com/fangsa/ai/auth/PasswordEncoderConfig.java \
        src/main/java/com/fangsa/ai/config/WebMvcConfig.java
git commit -m "feat(auth): AuthContext ThreadLocal + JwtAuthFilter + CORS tighten"
```

---

## Task 8: TenantContextInterceptor + @RequireLogin/@RequireRole + AuthAspect

**Files:**
- Create: `src/main/java/com/fangsa/ai/auth/TenantContextInterceptor.java`
- Create: `src/main/java/com/fangsa/ai/auth/RequireLogin.java`
- Create: `src/main/java/com/fangsa/ai/auth/RequireRole.java`
- Create: `src/main/java/com/fangsa/ai/auth/AuthAspect.java`

**Interfaces:**
- `TenantContextInterceptor.preHandle` returns false (40100 via exception) when `AuthContext.current() == null` and path not excluded
- `AuthAspect` checks `@RequireLogin` (any authenticated) and `@RequireRole` (must match role)

- [ ] **Step 1: RequireLogin**

`src/main/java/com/fangsa/ai/auth/RequireLogin.java`:

```java
package com.fangsa.ai.auth;

import java.lang.annotation.*;

@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface RequireLogin {}
```

- [ ] **Step 2: RequireRole**

`src/main/java/com/fangsa/ai/auth/RequireRole.java`:

```java
package com.fangsa.ai.auth;

import java.lang.annotation.*;

@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface RequireRole {
    String value(); // "tenant_admin" | "platform_admin"
}
```

- [ ] **Step 3: TenantContextInterceptor**

`src/main/java/com/fangsa/ai/auth/TenantContextInterceptor.java`:

```java
package com.fangsa.ai.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Set;

public class TenantContextInterceptor implements HandlerInterceptor {

    private static final Set<String> EXCLUDED_PREFIXES = Set.of(
            "/auth/login", "/auth/refresh", "/auth/accept-invite",
            "/platform/auth/login", "/platform/auth/refresh", "/platform/auth/logout",
            "/error"
    );

    @Override
    public boolean preHandle(HttpServletRequest req, HttpServletResponse resp, Object handler) {
        String p = req.getRequestURI();
        for (String e : EXCLUDED_PREFIXES) if (p.startsWith(e)) return true;

        // 已有 AuthException 的(由 JwtAuthFilter 解析失败塞进 request attr),直接抛出
        Object pre = req.getAttribute("authException");
        if (pre instanceof AuthException ae) throw ae;

        if (AuthContext.current() == null) {
            throw new AuthException(AuthErrorCode.MISSING_TOKEN, "login required");
        }
        return true;
    }
}
```

- [ ] **Step 4: AuthAspect (uses Spring AOP)**

`src/main/java/com/fangsa/ai/auth/AuthAspect.java`:

```java
package com.fangsa.ai.auth;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

@Aspect
@Component
public class AuthAspect {

    @Around("@annotation(com.fangsa.ai.auth.RequireRole) || @within(com.fangsa.ai.auth.RequireRole)")
    public Object requireRole(ProceedingJoinPoint pjp) throws Throwable {
        checkAuth();
        AuthContext ctx = AuthContext.current();
        RequireRole ann = findAnnotation(pjp);
        if (!ann.value().equals(ctx.getRole())) {
            throw new AuthException(AuthErrorCode.FORBIDDEN_ROLE, "role required: " + ann.value());
        }
        return pjp.proceed();
    }

    @Around("@annotation(com.fangsa.ai.auth.RequireLogin) || @within(com.fangsa.ai.auth.RequireLogin)")
    public Object requireLogin(ProceedingJoinPoint pjp) throws Throwable {
        checkAuth();
        return pjp.proceed();
    }

    private void checkAuth() {
        if (AuthContext.current() == null) {
            throw new AuthException(AuthErrorCode.MISSING_TOKEN, "login required");
        }
    }

    private RequireRole findAnnotation(ProceedingJoinPoint pjp) {
        // 简化:只检查方法级注解
        var sig = (org.aspectj.lang.reflect.MethodSignature) pjp.getSignature();
        return sig.getMethod().getAnnotation(RequireRole.class);
    }
}
```

- [ ] **Step 5: Verify WebMvcConfig compiles**

```bash
cd xiaoda-backend-intelligence/Spring-AI
./mvnw -q compile
```

Expected: success.

- [ ] **Step 6: Commit**

```bash
cd xiaoda-backend-intelligence/Spring-AI
git add src/main/java/com/fangsa/ai/auth/RequireLogin.java \
        src/main/java/com/fangsa/ai/auth/RequireRole.java \
        src/main/java/com/fangsa/ai/auth/TenantContextInterceptor.java \
        src/main/java/com/fangsa/ai/auth/AuthAspect.java
git commit -m "feat(auth): TenantContextInterceptor + RequireLogin/Role + AuthAspect"
```

---

## Task 9: MyBatis-Plus TenantLineHandler + AuthConfig

**Files:**
- Create: `src/main/java/com/fangsa/ai/config/TenantLineHandlerImpl.java`
- Create: `src/main/java/com/fangsa/ai/config/MybatisPlusAuthConfig.java`

**Interfaces:**
- Implements `TenantLineHandler.getTenantId()` returns null when no AuthContext; `ignoreTable()` returns true for `{tenant, invite, refresh_token, platform_admin}`
- Config bean ensures `TenantLineInnerInterceptor` is registered LAST in the chain

- [ ] **Step 1: TenantLineHandlerImpl**

`src/main/java/com/fangsa/ai/config/TenantLineHandlerImpl.java`:

```java
package com.fangsa.ai.config;

import com.baomidou.mybatisplus.extension.plugins.handler.TenantLineHandler;
import com.fangsa.ai.auth.AuthContext;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.LongValue;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

@Component
public class TenantLineHandlerImpl implements TenantLineHandler {

    private static final Set<String> GLOBAL_TABLES = Set.of(
            "tenant", "invite", "refresh_token", "platform_admin"
    );

    @Override public Expression getTenantId() {
        AuthContext ctx = AuthContext.current();
        if (ctx == null || ctx.getTenantId() == null) return null; // skip
        return new LongValue(ctx.getTenantId());
    }

    @Override public boolean ignoreTable(String tableName) {
        return GLOBAL_TABLES.contains(tableName.toLowerCase());
    }

    // MP 3.5.6 签名:允许在 INSERT 时也注入 tenant_id 列
    @Override public boolean ignoreInsert(List<String> columns, String tenantIdColumn) {
        return false; // 我们由 Service 层在 Domain 对象中显式 setTenantId()
    }
}
```

- [ ] **Step 2: MybatisPlusAuthConfig**

`src/main/java/com/fangsa/ai/config/MybatisPlusAuthConfig.java`:

```java
package com.fangsa.ai.config;

import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.handler.TenantLineHandler;
import com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MybatisPlusAuthConfig {

    @Bean
    public TenantLineInnerInterceptor tenantLineInnerInterceptor(TenantLineHandler handler) {
        return new TenantLineInnerInterceptor(handler);
    }

    @Bean
    public MybatisPlusInterceptorCustomizer interceptorCustomizer(TenantLineInnerInterceptor tenant) {
        return mp -> {
            // 确保 tenant 拦截器是最后一个 inner interceptor
            mp.addInnerInterceptor(tenant);
        };
    }

    @FunctionalInterface
    public interface MybatisPlusInterceptorCustomizer {
        void customize(MybatisPlusInterceptor mp);
    }
}
```

- [ ] **Step 3: Compile**

```bash
cd xiaoda-backend-intelligence/Spring-AI
./mvnw -q compile
```

Expected: success (or minor signature fixes noted above).

- [ ] **Step 4: Commit**

```bash
cd xiaoda-backend-intelligence/Spring-AI
git add src/main/java/com/fangsa/ai/config/TenantLineHandlerImpl.java \
        src/main/java/com/fangsa/ai/config/MybatisPlusAuthConfig.java
git commit -m "feat(orm): TenantLineHandler + MyBatis interceptor registration (innermost)"
```

---

## Task 10: GlobalExceptionHandler — AuthException + JWT 异常

**Files:**
- Modify: `src/main/java/com/fangsa/ai/config/GlobalExceptionHandler.java`

**Interfaces:**
- Catches `AuthException`, returns uniform body with HTTP code

- [ ] **Step 1: Read existing handler**

```bash
cat src/main/java/com/fangsa/ai/config/GlobalExceptionHandler.java
```

(Expect `@RestControllerAdvice` class.)

- [ ] **Step 2: Add new handlers**

Append to the `@RestControllerAdvice` class:

```java
@ExceptionHandler(com.fangsa.ai.auth.AuthException.class)
public org.springframework.http.ResponseEntity<com.fangsa.ai.domain.dto.AjaxResult> handleAuth(
        com.fangsa.ai.auth.AuthException ex) {
    int code = ex.getCode();
    int http;
    if (code == 40100 || code == 40101 || code == 40102 || code == 40103) http = 401;
    else if (code == 40301 || code == 40302) http = 403;
    else if (code == 40901) http = 409;
    else if (code == 41001) http = 410;
    else http = 400;
    var body = com.fangsa.ai.domain.dto.AjaxResult.error(code, ex.getMessage());
    if (http == 401) {
        org.springframework.http.HttpHeaders h = new org.springframework.http.HttpHeaders();
        h.add("WWW-Authenticate",
                "Bearer error=\"invalid_token\", error_description=\"" + ex.getMessage() + "\"");
        return new org.springframework.http.ResponseEntity<>(body, h,
                org.springframework.http.HttpStatus.valueOf(http));
    }
    return new org.springframework.http.ResponseEntity<>(body,
            org.springframework.http.HttpStatus.valueOf(http));
}
```

> 注:`AjaxResult.error(code, msg)` 假定已存在;若未提供,则补一行:
> ```java
> public static AjaxResult error(int code, String msg) { return new AjaxResult(code, msg, null); }
> ```
> 并在 `AjaxResult` 字段加 `private final int code;` 与对应构造器。

- [ ] **Step 3: Compile**

```bash
./mvnw -q compile
```

Expected: success after any minor signature adjustments.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/fangsa/ai/config/GlobalExceptionHandler.java
git commit -m "feat(auth): GlobalExceptionHandler maps AuthException → uniform error body"
```

---

## Task 11: AuthAuditLogger

**Files:**
- Create: `src/main/java/com/fangsa/ai/auth/AuthAuditLogger.java`

- [ ] **Step 1: Implement**

```java
package com.fangsa.ai.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class AuthAuditLogger {
    private static final Logger log = LoggerFactory.getLogger("com.fangsa.ai.auth");

    public void login(long userId, Long tenantId, String ip, String ua) {
        log.info("AUTH_LOGIN user={} tenantId={} ip={} ua={}", userId, tenantId, ip, ua);
    }
    public void loginFail(String tenantCode, String username, String ip) {
        log.warn("AUTH_LOGIN_FAIL tenant={} user={} ip={}", tenantCode, username, ip);
    }
    public void refresh(long userId, UUID family, String ip) {
        log.info("AUTH_REFRESH user={} family={} ip={}", userId, family, ip);
    }
    public void refreshReuse(UUID family, String ip) {
        log.warn("AUTH_REFRESH_REUSE family={} ip={}", family, ip);
    }
    public void logout(long userId, UUID family) {
        log.info("AUTH_LOGOUT user={} family={}", userId, family);
    }
    public void tenantCreate(String code, long by) {
        log.info("TENANT_CREATE code={} by_platform_admin={}", code, by);
    }
    public void inviteAccept(String code, long userId, long tenantId) {
        log.info("AUTH_INVITE_ACCEPT code={} userId={} tenantId={}", code, userId, tenantId);
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add src/main/java/com/fangsa/ai/auth/AuthAuditLogger.java
git commit -m "feat(auth): AuthAuditLogger for login/refresh/invite events"
```

---

## Task 12: AuthBootstrapRunner — seed su + default tenant

**Files:**
- Create: `src/main/java/com/fangsa/ai/auth/AuthBootstrapRunner.java`

- [ ] **Step 1: Implement**

```java
package com.fangsa.ai.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.Base64;

@Component
public class AuthBootstrapRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AuthBootstrapRunner.class);

    private final JdbcTemplate jdbc;
    private final PasswordEncoder encoder;

    public AuthBootstrapRunner(JdbcTemplate jdbc, PasswordEncoder encoder) {
        this.jdbc = jdbc; this.encoder = encoder;
    }

    @Override
    public void run(ApplicationArguments args) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM platform_admin", Integer.class);
        if (count != null && count > 0) {
            log.info("AuthBootstrap: platform_admin already exists, skipping seed.");
            return;
        }
        byte[] buf = new byte[16];
        new SecureRandom().nextBytes(buf);
        String pwd = Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
        String hash = encoder.hash(pwd);
        jdbc.update("INSERT INTO platform_admin (username, password_hash) VALUES (?, ?)", "su", hash);
        log.warn("====================================================================");
        log.warn("AuthBootstrap: seeded platform admin 'su' with initial password:");
        log.warn("    {}", pwd);
        log.warn("LOG THIS PASSWORD NOW and CHANGE IT IMMEDIATELY after first login.");
        log.warn("====================================================================");
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add src/main/java/com/fangsa/ai/auth/AuthBootstrapRunner.java
git commit -m "chore(auth): seed platform admin 'su' on first start with random password"
```

---

## Task 13: AuthService.login (TDD) + /auth/login endpoint

**Files:**
- Create: `src/main/java/com/fangsa/ai/auth/AuthService.java`
- Create: `src/main/java/com/fangsa/ai/auth/AuthController.java`
- Create: `src/main/java/com/fangsa/ai/domain/AppUser.java`
- Create: `src/main/java/com/fangsa/ai/mapper/AppUserMapper.java`
- Create: `src/main/java/com/fangsa/ai/mapper/TenantMapper.java`
- Create: `src/main/java/com/fangsa/ai/domain/Tenant.java`
- Test: `src/test/java/com/fangsa/ai/auth/AuthServiceTest.java`
- Test: `src/test/java/com/fangsa/ai/auth/AuthControllerWebMvcTest.java`

**Interfaces:**
- `AuthService.login(String tenantCode, String username, String rawPassword, String ip, String ua) → LoginResult{accessToken, refreshRaw, expiresAt, user}`
- `AuthController.POST /auth/login` body `{tenantCode, username, password}` → 200 with body + Set-Cookie `sm_refresh`

- [ ] **Step 1: Domain + Mapper**

`src/main/java/com/fangsa/ai/domain/Tenant.java`:

```java
package com.fangsa.ai.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.OffsetDateTime;

@Data
@TableName("tenant")
public class Tenant {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String code;
    private String name;
    private Integer status;
    private OffsetDateTime createTime;
    private OffsetDateTime updateTime;
}
```

`src/main/java/com/fangsa/ai/mapper/TenantMapper.java`:

```java
package com.fangsa.ai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fangsa.ai.domain.Tenant;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface TenantMapper extends BaseMapper<Tenant> {}
```

`src/main/java/com/fangsa/ai/domain/AppUser.java`:

```java
package com.fangsa.ai.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.OffsetDateTime;

@Data
@TableName("app_user")
public class AppUser {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private String username;
    private String passwordHash;
    private String displayName;
    private String role;
    private Integer status;
    private OffsetDateTime lastLoginAt;
    private OffsetDateTime createTime;
    private OffsetDateTime updateTime;
}
```

`src/main/java/com/fangsa/ai/mapper/AppUserMapper.java`:

```java
package com.fangsa.ai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fangsa.ai.domain.AppUser;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface AppUserMapper extends BaseMapper<AppUser> {}
```

- [ ] **Step 2: Failing test for AuthService.login**

```java
package com.fangsa.ai.auth;

import com.fangsa.ai.domain.AppUser;
import com.fangsa.ai.domain.Tenant;
import com.fangsa.ai.mapper.AppUserMapper;
import com.fangsa.ai.mapper.TenantMapper;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AuthServiceTest {

    @Test
    void login_validCredentials_returnsTokens() {
        TenantMapper tenants = mock(TenantMapper.class);
        AppUserMapper users = mock(AppUserMapper.class);
        PasswordEncoder enc = mock(PasswordEncoder.class);
        JwtService jwt = mock(JwtService.class);
        RefreshTokenService refresh = mock(RefreshTokenService.class);
        AuthAuditLogger audit = mock(AuthAuditLogger.class);

        Tenant t = new Tenant(); t.setId(7L); t.setCode("acme");
        AppUser u = new AppUser();
        u.setId(42L); u.setTenantId(7L); u.setUsername("alice");
        u.setPasswordHash("hash"); u.setRole("tenant_admin"); u.setStatus(1);

        when(tenants.selectList(any())).thenReturn(List.of(t));
        when(users.selectList(any())).thenReturn(List.of(u));
        when(enc.matches("pw", "hash")).thenReturn(true);
        when(jwt.signAccess(42L, 7L, "tenant_admin")).thenReturn("ACCESS.jwt");
        when(refresh.issue(42L, "ua", "ip"))
                .thenReturn(new RefreshTokenService.IssueResult("REFRESH", java.time.Instant.now(), java.util.UUID.randomUUID()));

        AuthService svc = new AuthService(tenants, users, enc, jwt, refresh, audit);
        AuthService.LoginResult r = svc.login("acme", "alice", "pw", "ip", "ua");

        assertEquals("ACCESS.jwt", r.accessToken());
        assertEquals("REFRESH", r.refreshRaw());
        assertEquals(42L, r.user().getId());
        assertEquals(7L, r.user().getTenantId());
    }

    @Test
    void login_wrongPassword_throws40100() {
        TenantMapper tenants = mock(TenantMapper.class);
        AppUserMapper users = mock(AppUserMapper.class);
        PasswordEncoder enc = mock(PasswordEncoder.class);

        Tenant t = new Tenant(); t.setId(7L); t.setCode("acme");
        AppUser u = new AppUser(); u.setId(42L); u.setTenantId(7L);
        u.setPasswordHash("hash"); u.setRole("tenant_admin"); u.setStatus(1);

        when(tenants.selectList(any())).thenReturn(List.of(t));
        when(users.selectList(any())).thenReturn(List.of(u));
        when(enc.matches(any(), any())).thenReturn(false);

        AuthService svc = new AuthService(tenants, users, enc, mock(JwtService.class),
                mock(RefreshTokenService.class), mock(AuthAuditLogger.class));
        AuthException ex = assertThrows(AuthException.class,
                () -> svc.login("acme", "alice", "bad", "ip", "ua"));
        assertEquals(40100, ex.getCode());
    }

    @Test
    void login_unknownTenant_throws40100() {
        TenantMapper tenants = mock(TenantMapper.class);
        when(tenants.selectList(any())).thenReturn(List.of());

        AuthService svc = new AuthService(tenants, mock(AppUserMapper.class), mock(PasswordEncoder.class),
                mock(JwtService.class), mock(RefreshTokenService.class), mock(AuthAuditLogger.class));
        AuthException ex = assertThrows(AuthException.class,
                () -> svc.login("nope", "alice", "pw", "ip", "ua"));
        assertEquals(40100, ex.getCode());
    }
}
```

- [ ] **Step 3: Run, expect FAIL**

```bash
./mvnw -q test -Dtest=AuthServiceTest
```

Expected: AuthService missing.

- [ ] **Step 4: Implement AuthService**

```java
package com.fangsa.ai.auth;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fangsa.ai.domain.AppUser;
import com.fangsa.ai.domain.Tenant;
import com.fangsa.ai.mapper.AppUserMapper;
import com.fangsa.ai.mapper.TenantMapper;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

@Service
public class AuthService {

    private final TenantMapper tenants;
    private final AppUserMapper users;
    private final PasswordEncoder encoder;
    private final JwtService jwt;
    private final RefreshTokenService refresh;
    private final AuthAuditLogger audit;

    public AuthService(TenantMapper tenants, AppUserMapper users, PasswordEncoder encoder,
                       JwtService jwt, RefreshTokenService refresh, AuthAuditLogger audit) {
        this.tenants = tenants; this.users = users; this.encoder = encoder;
        this.jwt = jwt; this.refresh = refresh; this.audit = audit;
    }

    public record UserView(long id, long tenantId, String username, String displayName, String role) {
        public static UserView from(AppUser u) {
            return new UserView(u.getId(), u.getTenantId(), u.getUsername(), u.getDisplayName(), u.getRole());
        }
    }
    public record LoginResult(String accessToken, String refreshRaw, Instant expiresAt, UserView user) {}

    public LoginResult login(String tenantCode, String username, String rawPwd, String ip, String ua) {
        Tenant t = tenants.selectList(new QueryWrapper<Tenant>().eq("code", tenantCode).eq("status", 1))
                .stream().findFirst()
                .orElseThrow(() -> { audit.loginFail(tenantCode, username, ip); return new AuthException(AuthErrorCode.MISSING_TOKEN, "bad credentials"); });

        AppUser u = users.selectList(new QueryWrapper<AppUser>()
                .eq("tenant_id", t.getId()).eq("username", username).eq("status", 1)
        ).stream().findFirst()
                .orElseThrow(() -> { audit.loginFail(tenantCode, username, ip); return new AuthException(AuthErrorCode.MISSING_TOKEN, "bad credentials"); });

        if (!encoder.matches(rawPwd, u.getPasswordHash())) {
            audit.loginFail(tenantCode, username, ip);
            throw new AuthException(AuthErrorCode.MISSING_TOKEN, "bad credentials");
        }

        String access = jwt.signAccess(u.getId(), u.getTenantId(), u.getRole());
        RefreshTokenService.IssueResult rr = refresh.issue(u.getId(), ua, ip);
        audit.login(u.getId(), u.getTenantId(), ip, ua);
        return new LoginResult(access, rr.rawToken(), rr.expiresAt(), UserView.from(u));
    }

    public AppUser requireUser(long userId) {
        AppUser u = users.selectById(userId);
        if (u == null) throw new AuthException(AuthErrorCode.MISSING_TOKEN, "user not found");
        return u;
    }

    public Tenant requireTenant(long tenantId) {
        Tenant t = tenants.selectById(tenantId);
        if (t == null) throw new AuthException(AuthErrorCode.CROSS_TENANT, "tenant not found");
        return t;
    }
}
```

- [ ] **Step 5: Run, expect PASS**

```bash
./mvnw -q test -Dtest=AuthServiceTest
```

Expected: 3 tests pass.

- [ ] **Step 6: AuthController**

```java
package com.fangsa.ai.auth;

import com.fangsa.ai.domain.dto.AjaxResult;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.Map;

@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService auth;
    private final RefreshTokenService refresh;
    private final JwtService jwt;
    private final AuthAuditLogger audit;

    public record LoginReq(@NotBlank String tenantCode, @NotBlank String username, @NotBlank String password) {}

    @PostMapping("/login")
    public AjaxResult login(@RequestBody LoginReq req, HttpServletResponse resp) {
        AuthService.LoginResult r = auth.login(req.tenantCode(), req.username(), req.password(), null, null);
        setRefreshCookie(resp, r.refreshRaw(), false);
        return AjaxResult.success(Map.of(
                "accessToken", r.accessToken(),
                "expiresAt", r.expiresAt().toEpochMilli(),
                "user", r.user()
        ));
    }

    public static void setRefreshCookie(HttpServletResponse resp, String raw, boolean delete) {
        String cookie = "sm_refresh=" + (delete ? "" : raw)
                + "; Path=/; HttpOnly; SameSite=Lax; Max-Age=" + (delete ? 0 : 30L * 24 * 3600);
        resp.addHeader("Set-Cookie", cookie);
    }
}
```

- [ ] **Step 7: WebMvc slice test (skip if `@WebMvcTest` proves brittle; otherwise test via integration)**

For brevity, defer `/auth/login` to the integration test in Task 17.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/fangsa/ai/auth/AuthService.java \
        src/main/java/com/fangsa/ai/auth/AuthController.java \
        src/main/java/com/fangsa/ai/domain/AppUser.java \
        src/main/java/com/fangsa/ai/domain/Tenant.java \
        src/main/java/com/fangsa/ai/mapper/AppUserMapper.java \
        src/main/java/com/fangsa/ai/mapper/TenantMapper.java \
        src/test/java/com/fangsa/ai/auth/AuthServiceTest.java
git commit -m "feat(auth): AuthService.login + /auth/login endpoint + AppUser/Tenant domain"
```

---

## Task 14: /auth/refresh + /auth/logout

**Files:**
- Modify: `src/main/java/com/fangsa/ai/auth/AuthController.java`
- Modify: `src/main/java/com/fangsa/ai/auth/AuthService.java`

**Interfaces:**
- `POST /auth/refresh` reads `sm_refresh` cookie, returns new access + rotates refresh
- `POST /auth/logout` reads `sm_refresh` cookie, revokes family

- [ ] **Step 1: Add to AuthService**

Append to `AuthService.java`:

```java
public RefreshResult refresh(String rawRefresh, String ip, String ua) {
    RefreshTokenService.RotateResult rr = refresh.rotate(rawRefresh, ua, ip);
    AppUser u = users.selectById(rr.userId());
    if (u == null) throw new AuthException(AuthErrorCode.REFRESH_EXPIRED, "user gone");
    String access = jwt.signAccess(u.getId(), u.getTenantId(), u.getRole());
    audit.refresh(u.getId(), rr.familyId(), ip);
    return new RefreshResult(access, rr.newRawToken(), rr.userId());
}

public void logout(String rawRefresh) {
    if (rawRefresh == null || rawRefresh.isBlank()) return;
    String hash = sha256(rawRefresh);
    refresh.findByHash(hash).ifPresent(row -> {
        refresh.revokeFamily(row.familyId());
        audit.logout(row.userId(), row.familyId());
    });
}

private static String sha256(String s) {
    try {
        var md = java.security.MessageDigest.getInstance("SHA-256");
        var d = md.digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var sb = new StringBuilder();
        for (byte b : d) sb.append(String.format("%02x", b));
        return sb.toString();
    } catch (Exception e) { throw new IllegalStateException(e); }
}

public record RefreshResult(String accessToken, String newRefreshRaw, long userId) {}
```

> 注:`findByHash` 已存在于 `RefreshTokenStore` 接口(Task 5)。本 Task 通过它同时拿到 userId 和 familyId,正确审计登出。

- [ ] **Step 2: Add controller methods**

Append to `AuthController`:

```java
@PostMapping("/refresh")
public AjaxResult refresh(@CookieValue(name = "sm_refresh", required = false) String cookie,
                          HttpServletResponse resp) {
    if (cookie == null || cookie.isBlank())
        throw new AuthException(AuthErrorCode.REFRESH_EXPIRED, "no refresh cookie");
    AuthService.RefreshResult r = auth.refresh(cookie, null, null);
    AuthController.setRefreshCookie(resp, r.newRefreshRaw(), false);
    return AjaxResult.success(Map.of(
            "accessToken", r.accessToken(),
            "expiresAt", Instant.now().plusSeconds(15 * 60).toEpochMilli()
    ));
}

@PostMapping("/logout")
public AjaxResult logout(@CookieValue(name = "sm_refresh", required = false) String cookie,
                         HttpServletResponse resp) {
    auth.logout(cookie);
    AuthController.setRefreshCookie(resp, null, true);
    return AjaxResult.success();
}
```

- [ ] **Step 3: Compile + commit**

```bash
./mvnw -q compile
git add -A
git commit -m "feat(auth): /auth/refresh + /auth/logout with reuse detection wiring"
```

---

## Task 15: /auth/accept-invite (TDD on service)

**Files:**
- Create: `src/main/java/com/fangsa/ai/auth/AcceptInviteService.java`
- Create: `src/main/java/com/fangsa/ai/domain/Invite.java`
- Create: `src/main/java/com/fangsa/ai/mapper/InviteMapper.java`
- Test: `src/test/java/com/fangsa/ai/auth/AcceptInviteServiceTest.java`
- Modify: `src/main/java/com/fangsa/ai/auth/AuthController.java`

**Interfaces:**
- `AcceptInviteService.accept(code, password, displayName, ip, ua) → LoginResult`
- Throws 41001 if invite missing/expired/already accepted

- [ ] **Step 1: Domain + Mapper**

`src/main/java/com/fangsa/ai/domain/Invite.java`:

```java
package com.fangsa.ai.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.OffsetDateTime;

@Data
@TableName("invite")
public class Invite {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private String code;
    private String intendedRole;
    private Long acceptedBy;
    private OffsetDateTime expiresAt;
    private OffsetDateTime createTime;
}
```

`src/main/java/com/fangsa/ai/mapper/InviteMapper.java`:

```java
package com.fangsa.ai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fangsa.ai.domain.Invite;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface InviteMapper extends BaseMapper<Invite> {}
```

- [ ] **Step 2: Failing test**

```java
package com.fangsa.ai.auth;

import com.fangsa.ai.domain.Invite;
import com.fangsa.ai.mapper.AppUserMapper;
import com.fangsa.ai.mapper.InviteMapper;
import com.fangsa.ai.mapper.TenantMapper;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class AcceptInviteServiceTest {

    private static AcceptInviteService newSvc(InviteMapper invites, TenantMapper tenants,
                                              AppUserMapper users) {
        JwtService jwt = mock(JwtService.class);
        when(jwt.signAccess(anyLong(), anyLong(), anyString())).thenReturn("ACCESS");
        RefreshTokenService refresh = mock(RefreshTokenService.class);
        when(refresh.issue(anyLong(), any(), any())).thenReturn(
                new RefreshTokenService.IssueResult("REFRESH", java.time.Instant.now(),
                        java.util.UUID.randomUUID()));
        return new AcceptInviteService(invites, tenants, users, mock(PasswordEncoder.class),
                jwt, refresh, mock(AuthAuditLogger.class));
    }

    @Test
    void accept_validInvite_createsUserAndReturnsLoginResult() {
        InviteMapper invites = mock(InviteMapper.class);
        TenantMapper tenants = mock(TenantMapper.class);
        AppUserMapper users = mock(AppUserMapper.class);
        Invite inv = new Invite();
        inv.setId(1L); inv.setTenantId(7L); inv.setCode("ABC");
        inv.setIntendedRole("tenant_admin");
        inv.setExpiresAt(OffsetDateTime.now().plusHours(1));
        when(invites.selectList(any())).thenReturn(List.of(inv));
        when(tenants.selectById(7L)).thenReturn(new com.fangsa.ai.domain.Tenant());

        AcceptInviteService svc = newSvc(invites, tenants, users);
        AuthService.LoginResult r = svc.accept("ABC", "pw", "Alice", "ip", "ua");
        assertEquals("ACCESS", r.accessToken());
        verify(users).insert(any(com.fangsa.ai.domain.AppUser.class));
    }

    @Test
    void accept_expiredInvite_throws41001() {
        InviteMapper invites = mock(InviteMapper.class);
        Invite inv = new Invite();
        inv.setId(1L); inv.setTenantId(7L); inv.setCode("ABC");
        inv.setIntendedRole("tenant_admin");
        inv.setExpiresAt(OffsetDateTime.now().minusSeconds(1));
        when(invites.selectList(any())).thenReturn(List.of(inv));

        AcceptInviteService svc = newSvc(invites, mock(TenantMapper.class), mock(AppUserMapper.class));
        AuthException ex = assertThrows(AuthException.class,
                () -> svc.accept("ABC", "pw", "Alice", "ip", "ua"));
        assertEquals(41001, ex.getCode());
    }

    @Test
    void accept_alreadyAccepted_throws41001() {
        InviteMapper invites = mock(InviteMapper.class);
        Invite inv = new Invite();
        inv.setId(1L); inv.setTenantId(7L); inv.setCode("ABC");
        inv.setIntendedRole("tenant_admin");
        inv.setAcceptedBy(99L);
        inv.setExpiresAt(OffsetDateTime.now().plusHours(1));
        when(invites.selectList(any())).thenReturn(List.of(inv));

        AcceptInviteService svc = newSvc(invites, mock(TenantMapper.class), mock(AppUserMapper.class));
        assertThrows(AuthException.class, () -> svc.accept("ABC", "pw", "Alice", "ip", "ua"));
    }
}
```

- [ ] **Step 3: Implement**

`src/main/java/com/fangsa/ai/auth/AcceptInviteService.java`:

```java
package com.fangsa.ai.auth;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fangsa.ai.domain.AppUser;
import com.fangsa.ai.domain.Invite;
import com.fangsa.ai.mapper.AppUserMapper;
import com.fangsa.ai.mapper.InviteMapper;
import com.fangsa.ai.mapper.TenantMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;

@Service
public class AcceptInviteService {

    private final InviteMapper invites;
    private final TenantMapper tenants;
    private final AppUserMapper users;
    private final PasswordEncoder encoder;
    private final JwtService jwt;
    private final RefreshTokenService refresh;
    private final AuthAuditLogger audit;

    public AcceptInviteService(InviteMapper invites, TenantMapper tenants, AppUserMapper users,
                               PasswordEncoder encoder, JwtService jwt, RefreshTokenService refresh,
                               AuthAuditLogger audit) {
        this.invites = invites; this.tenants = tenants; this.users = users;
        this.encoder = encoder; this.jwt = jwt; this.refresh = refresh; this.audit = audit;
    }

    @Transactional
    public AuthService.LoginResult accept(String code, String password, String displayName,
                                          String ip, String ua) {
        Invite inv = invites.selectList(new QueryWrapper<Invite>().eq("code", code))
                .stream().findFirst()
                .orElseThrow(() -> new AuthException(AuthErrorCode.INVITE_INVALID, "invite not found"));
        if (inv.getAcceptedBy() != null) {
            throw new AuthException(AuthErrorCode.INVITE_INVALID, "invite already accepted");
        }
        if (inv.getExpiresAt().isBefore(OffsetDateTime.now())) {
            throw new AuthException(AuthErrorCode.INVITE_INVALID, "invite expired");
        }
        if (tenants.selectById(inv.getTenantId()) == null) {
            throw new AuthException(AuthErrorCode.INVITE_INVALID, "tenant gone");
        }

        AppUser u = new AppUser();
        u.setTenantId(inv.getTenantId());
        u.setUsername("admin"); // 第一位管理员固定 username=admin
        u.setPasswordHash(encoder.hash(password));
        u.setDisplayName(displayName);
        u.setRole(inv.getIntendedRole());
        u.setStatus(1);
        users.insert(u);

        inv.setAcceptedBy(u.getId());
        invites.updateById(inv);

        audit.inviteAccept(code, u.getId(), inv.getTenantId());

        // 手工签发(避免再次 BCrypt 校验已哈希过的密码)
        String access = jwt.signAccess(u.getId(), u.getTenantId(), u.getRole());
        var rr = refresh.issue(u.getId(), ua, ip);
        return new AuthService.LoginResult(access, rr.rawToken(), rr.expiresAt(),
                new AuthService.UserView(u.getId(), u.getTenantId(), u.getUsername(),
                        u.getDisplayName(), u.getRole()));
    }
}
```

- [ ] **Step 4: Add controller method**

```java
@PostMapping("/accept-invite")
public AjaxResult acceptInvite(@RequestBody Map<String, String> req, HttpServletResponse resp) {
    AuthService.LoginResult r = accept.accept(
            req.get("code"), req.get("password"), req.get("displayName"), null, null);
    AuthController.setRefreshCookie(resp, r.refreshRaw(), false);
    return AjaxResult.success(Map.of(
            "accessToken", r.accessToken(),
            "expiresAt", r.expiresAt().toEpochMilli(),
            "user", r.user()
    ));
}
```

- [ ] **Step 5: Run tests, expect PASS**

```bash
./mvnw -q test -Dtest=AcceptInviteServiceTest
```

Expected: 3 tests pass.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "feat(auth): AcceptInviteService + /auth/accept-invite"
```

---

## Task 16: /auth/me + tenant-scoped me view

**Files:**
- Modify: `src/main/java/com/fangsa/ai/auth/AuthController.java`

- [ ] **Step 1: Add endpoint**

```java
@GetMapping("/me")
@RequireLogin
public AjaxResult me() {
    AuthContext ctx = AuthContext.current();
    var u = auth.requireUser(ctx.getUserId());
    var t = auth.requireTenant(u.getTenantId());
    return AjaxResult.success(Map.of(
            "user", AuthService.UserView.from(u),
            "tenant", Map.of("id", t.getId(), "code", t.getCode(), "name", t.getName())
    ));
}
```

- [ ] **Step 2: Commit**

```bash
git add src/main/java/com/fangsa/ai/auth/AuthController.java
git commit -m "feat(auth): /auth/me returns current user + tenant"
```

---

## Task 17: Platform auth (login/refresh/logout)

**Files:**
- Create: `src/main/java/com/fangsa/platform/PlatformAuthService.java`
- Create: `src/main/java/com/fangsa/platform/PlatformAuthController.java`
- Create: `src/main/java/com/fangsa/platform/PlatformAdminMapper.java`
- Create: `src/main/java/com/fangsa/ai/domain/PlatformAdmin.java`

- [ ] **Step 1: Domain + Mapper**

```java
// PlatformAdmin.java
@Data @TableName("platform_admin")
public class PlatformAdmin {
    @TableId(type = IdType.AUTO) private Long id;
    private String username;
    private String passwordHash;
    private OffsetDateTime createTime;
}

// PlatformAdminMapper.java
@Mapper
public interface PlatformAdminMapper extends BaseMapper<PlatformAdmin> {}
```

- [ ] **Step 2: Service**

```java
package com.fangsa.platform;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fangsa.ai.auth.*;
import com.fangsa.ai.domain.PlatformAdmin;
import com.fangsa.ai.mapper.PlatformAdminMapper;
import org.springframework.stereotype.Service;

@Service
public class PlatformAuthService {

    private final PlatformAdminMapper admins;
    private final PasswordEncoder encoder;
    private final JwtService jwt;
    private final RefreshTokenService refresh;
    private final AuthAuditLogger audit;

    public PlatformAuthService(PlatformAdminMapper admins, PasswordEncoder encoder, JwtService jwt,
                               RefreshTokenService refresh, AuthAuditLogger audit) {
        this.admins = admins; this.encoder = encoder; this.jwt = jwt;
        this.refresh = refresh; this.audit = audit;
    }

    public record PlatformLoginResult(String accessToken, String refreshRaw, java.time.Instant expiresAt) {}

    public PlatformLoginResult login(String username, String rawPwd, String ip, String ua) {
        PlatformAdmin a = admins.selectList(new QueryWrapper<PlatformAdmin>().eq("username", username))
                .stream().findFirst()
                .orElseThrow(() -> new AuthException(AuthErrorCode.MISSING_TOKEN, "bad credentials"));
        if (!encoder.matches(rawPwd, a.getPasswordHash())) {
            throw new AuthException(AuthErrorCode.MISSING_TOKEN, "bad credentials");
        }
        // refresh_token.user_id 强引用 app_user.id,平台管理员不写入 refresh 表;
        // 平台 su 重新登录即可,前端 cookie sm_platform_refresh 设置 Max-Age=0 提示浏览器清掉。
        String access = jwt.signPlatformAccess(a.getId());
        audit.login(a.getId(), null, ip, ua);
        return new PlatformLoginResult(access, null, java.time.Instant.now().plusSeconds(15 * 60));
    }
}
```

> 平台 su 走短 token,无续命。`refreshRaw` 字段保留为 `null` 以兼容 controller;`Set-Cookie sm_platform_refresh` 用 `Max-Age=0` 提示浏览器清掉旧的 cookie。

- [ ] **Step 3: Controller**

```java
package com.fangsa.platform;

import com.fangsa.ai.auth.*;
import com.fangsa.ai.domain.dto.AjaxResult;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/platform/auth")
@RequiredArgsConstructor
public class PlatformAuthController {

    private final PlatformAuthService svc;

    public record LoginReq(String username, String password) {}

    @PostMapping("/login")
    public AjaxResult login(@RequestBody LoginReq req, HttpServletResponse resp) {
        PlatformAuthService.PlatformLoginResult r = svc.login(req.username(), req.password(), null, null);
        AuthController.setPlatformRefreshCookie(resp, "", true);
        return AjaxResult.success(java.util.Map.of(
                "accessToken", r.accessToken(),
                "expiresAt", r.expiresAt().toEpochMilli()
        ));
    }

    @PostMapping("/logout")
    public AjaxResult logout(HttpServletResponse resp) {
        AuthController.setPlatformRefreshCookie(resp, "", true);
        return AjaxResult.success();
    }
}
```

- [ ] **Step 4: Add `setPlatformRefreshCookie` to AuthController**

```java
public static void setPlatformRefreshCookie(HttpServletResponse resp, String raw, boolean delete) {
    String cookie = "sm_platform_refresh=" + (delete ? "" : raw)
            + "; Path=/; HttpOnly; SameSite=Lax; Max-Age=" + (delete ? 0 : 30L * 24 * 3600);
    resp.addHeader("Set-Cookie", cookie);
}
```

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat(platform): platform admin login/logout (no refresh)"
```

---

## Task 18: Platform tenant CRUD (create/list/disable) + invite

**Files:**
- Create: `src/main/java/com/fangsa/platform/PlatformTenantController.java`
- Create: `src/main/java/com/fangsa/platform/PlatformInviteController.java`
- Modify: `src/main/java/com/fangsa/ai/auth/AuthController.java` (no edit needed; `@RequireRole` annotation on platform controllers already routes through `AuthAspect`)

**Interfaces:**
- `POST /platform/tenants {code,name}` → creates tenant + generates invite
- `GET /platform/tenants` → list
- `POST /platform/tenants/{id}/disable` → status=0
- `GET /platform/invites/{code}` → details

- [ ] **Step 1: Controller**

```java
package com.fangsa.platform;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fangsa.ai.auth.AuthContext;
import com.fangsa.ai.auth.AuthErrorCode;
import com.fangsa.ai.auth.AuthException;
import com.fangsa.ai.auth.AuthAuditLogger;
import com.fangsa.ai.auth.RequireRole;
import com.fangsa.ai.domain.Invite;
import com.fangsa.ai.domain.Tenant;
import com.fangsa.ai.domain.dto.AjaxResult;
import com.fangsa.ai.mapper.AppUserMapper;
import com.fangsa.ai.mapper.InviteMapper;
import com.fangsa.ai.mapper.TenantMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.Map;

@RestController
@RequestMapping("/platform")
@RequiredArgsConstructor
public class PlatformTenantController {

    private final TenantMapper tenants;
    private final InviteMapper invites;
    private final AppUserMapper users;
    private final AuthAuditLogger audit;

    public record CreateReq(String code, String name) {}

    @PostMapping("/tenants")
    @RequireRole("platform_admin")
    @Transactional
    public AjaxResult create(@RequestBody CreateReq req) {
        Long exists = tenants.selectCount(new QueryWrapper<Tenant>().eq("code", req.code()));
        if (exists != null && exists > 0) {
            throw new AuthException(AuthErrorCode.USERNAME_CONFLICT, "tenant code exists");
        }
        Tenant t = new Tenant();
        t.setCode(req.code()); t.setName(req.name()); t.setStatus(1);
        tenants.insert(t);

        Invite inv = new Invite();
        inv.setTenantId(t.getId());
        inv.setCode(randomCode());
        inv.setIntendedRole("tenant_admin");
        inv.setExpiresAt(OffsetDateTime.now().plusDays(7));
        invites.insert(inv);

        audit.tenantCreate(t.getCode(), AuthContext.current().getUserId());
        return AjaxResult.success(Map.of("tenantId", t.getId(), "inviteCode", inv.getCode()));
    }

    @GetMapping("/tenants")
    @RequireRole("platform_admin")
    public AjaxResult list() {
        return AjaxResult.success(tenants.selectList(null));
    }

    @PostMapping("/tenants/{id}/disable")
    @RequireRole("platform_admin")
    public AjaxResult disable(@PathVariable Long id) {
        Tenant t = new Tenant(); t.setId(id); t.setStatus(0);
        tenants.updateById(t);
        return AjaxResult.success();
    }

    private static String randomCode() {
        byte[] buf = new byte[24];
        new SecureRandom().nextBytes(buf);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
    }
}
```

- [ ] **Step 2: Invite controller**

```java
package com.fangsa.platform;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fangsa.ai.auth.RequireRole;
import com.fangsa.ai.domain.Invite;
import com.fangsa.ai.domain.Tenant;
import com.fangsa.ai.domain.dto.AjaxResult;
import com.fangsa.ai.mapper.InviteMapper;
import com.fangsa.ai.mapper.TenantMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/platform/invites")
@RequiredArgsConstructor
public class PlatformInviteController {

    private final InviteMapper invites;
    private final TenantMapper tenants;

    @GetMapping("/{code}")
    @RequireRole("platform_admin")
    public AjaxResult get(@PathVariable String code) {
        Invite inv = invites.selectList(new QueryWrapper<Invite>().eq("code", code))
                .stream().findFirst().orElse(null);
        if (inv == null) return AjaxResult.success(java.util.Map.of("code", code, "accepted", false));
        Tenant t = tenants.selectById(inv.getTenantId());
        return AjaxResult.success(java.util.Map.of(
                "code", inv.getCode(),
                "tenantId", inv.getTenantId(),
                "tenantName", t == null ? "" : t.getName(),
                "expiresAt", inv.getExpiresAt(),
                "accepted", inv.getAcceptedBy() != null
        ));
    }
}
```

- [ ] **Step 3: Commit**

```bash
git add -A
git commit -m "feat(platform): tenant CRUD + invite query (platform_admin only)"
```

---

## Task 19: Tenant member CRUD (admin invites members)

**Files:**
- Create: `src/main/java/com/fangsa/ai/auth/TenantMemberController.java`

**Interfaces:**
- `GET /tenant/members` — list (admin or member can list)
- `POST /tenant/members {username, displayName, role}` — admin only, returns tempPassword
- `PUT /tenant/members/{id}/role {role}` — admin only
- `POST /tenant/members/{id}/disable` — admin only
- `POST /tenant/members/{id}/reset-password` — admin only, returns tempPassword

- [ ] **Step 1: Controller**

```java
package com.fangsa.ai.auth;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fangsa.ai.domain.AppUser;
import com.fangsa.ai.domain.dto.AjaxResult;
import com.fangsa.ai.mapper.AppUserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.bcrypt.BCrypt;
import org.springframework.web.bind.annotation.*;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/tenant/members")
@RequiredArgsConstructor
public class TenantMemberController {

    private final AppUserMapper users;

    public record CreateReq(String username, String displayName, String role) {}
    public record RoleReq(String role) {}

    @GetMapping
    @RequireLogin
    public AjaxResult list() {
        long tid = AuthContext.current().getTenantId();
        List<AppUser> all = users.selectList(new QueryWrapper<AppUser>().eq("tenant_id", tid));
        return AjaxResult.success(all.stream().map(u -> Map.of(
                "id", u.getId(),
                "username", u.getUsername(),
                "displayName", u.getDisplayName() == null ? "" : u.getDisplayName(),
                "role", u.getRole(),
                "status", u.getStatus(),
                "lastLoginAt", u.getLastLoginAt()
        )).toList());
    }

    @PostMapping
    @RequireRole("tenant_admin")
    public AjaxResult create(@RequestBody CreateReq req) {
        long tid = AuthContext.current().getTenantId();
        Long exists = users.selectCount(new QueryWrapper<AppUser>()
                .eq("tenant_id", tid).eq("username", req.username()));
        if (exists != null && exists > 0) {
            throw new AuthException(AuthErrorCode.USERNAME_CONFLICT, "username exists");
        }
        String pwd = randomPassword();
        AppUser u = new AppUser();
        u.setTenantId(tid);
        u.setUsername(req.username());
        u.setDisplayName(req.displayName());
        u.setRole(req.role() == null ? "tenant_member" : req.role());
        u.setPasswordHash(BCrypt.hashpw(pwd, BCrypt.gensalt(10)));
        u.setStatus(1);
        users.insert(u);
        return AjaxResult.success(Map.of("id", u.getId(), "tempPassword", pwd));
    }

    @PutMapping("/{id}/role")
    @RequireRole("tenant_admin")
    public AjaxResult setRole(@PathVariable Long id, @RequestBody RoleReq req) {
        AppUser u = users.selectById(id);
        if (u == null || !u.getTenantId().equals(AuthContext.current().getTenantId()))
            throw new AuthException(AuthErrorCode.CROSS_TENANT, "not in your tenant");
        u.setRole(req.role());
        users.updateById(u);
        return AjaxResult.success();
    }

    @PostMapping("/{id}/disable")
    @RequireRole("tenant_admin")
    public AjaxResult disable(@PathVariable Long id) {
        AppUser u = users.selectById(id);
        if (u == null || !u.getTenantId().equals(AuthContext.current().getTenantId()))
            throw new AuthException(AuthErrorCode.CROSS_TENANT, "not in your tenant");
        u.setStatus(0);
        users.updateById(u);
        return AjaxResult.success();
    }

    @PostMapping("/{id}/reset-password")
    @RequireRole("tenant_admin")
    public AjaxResult resetPwd(@PathVariable Long id) {
        AppUser u = users.selectById(id);
        if (u == null || !u.getTenantId().equals(AuthContext.current().getTenantId()))
            throw new AuthException(AuthErrorCode.CROSS_TENANT, "not in your tenant");
        String pwd = randomPassword();
        u.setPasswordHash(BCrypt.hashpw(pwd, BCrypt.gensalt(10)));
        users.updateById(u);
        return AjaxResult.success(Map.of("tempPassword", pwd));
    }

    private static String randomPassword() {
        byte[] buf = new byte[12];
        new SecureRandom().nextBytes(buf);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add src/main/java/com/fangsa/ai/auth/TenantMemberController.java
git commit -m "feat(auth): /tenant/members CRUD (admin only writes)"
```

---

## Task 20: Wire @RequireLogin/@RequireRole to existing controllers

**Files:**
- Modify: every file under `src/main/java/com/fangsa/ai/controller/`

**Rules:**
- `@GetMapping` (list, getById): `@RequireLogin`
- `@PostMapping @PutMapping @DeleteMapping`: `@RequireRole("tenant_admin")`
- Exception: `AiChatController` `/ai/chat` — `@RequireLogin` (members can use AI)

- [ ] **Step 1: AgentController**

`src/main/java/com/fangsa/ai/controller/AgentController.java`:

- Add `import com.fangsa.ai.auth.RequireLogin;` + `import com.fangsa.ai.auth.RequireRole;`
- Annotate class with `@RequireLogin`
- Each write method (`addAgent`, `updateAgent`, `deleteAgent`, `deleteAgentAndKnowledge`, `agentToKnowledge`) gets `@RequireRole("tenant_admin")`
- Leave `getAgentById`, `knowledgeList`, `getAllAgents` without per-method override (inherits class-level `@RequireLogin`)

- [ ] **Step 2: KnowledgeController**

Same pattern: class `@RequireLogin`; writes get `@RequireRole("tenant_admin")`.

- [ ] **Step 3: FileController**

Same.

- [ ] **Step 4: RagController**

Reads (`@GetMapping`): `@RequireLogin`. Writes: `@RequireRole("tenant_admin")`. If only reads, class-level `@RequireLogin` suffices.

- [ ] **Step 5: AiChatController**

Class `@RequireLogin`. Members allowed.

- [ ] **Step 6: AiHistoryController**

Class `@RequireLogin`.

- [ ] **Step 7: ToolController**

Class `@RequireLogin`; writes `@RequireRole("tenant_admin")`.

- [ ] **Step 8: Compile + smoke test boot**

```bash
./mvnw -q compile
./mvnw -q spring-boot:run -Dspring-boot.run.fork=false &
sleep 15 && curl -s -o /dev/null -w "%{http_code}\n" http://localhost:8080/agent/list
```

Expected: 401 (no token → 40100). Then kill the server: `kill %1`.

- [ ] **Step 9: Commit**

```bash
git add src/main/java/com/fangsa/ai/controller/
git commit -m "feat(auth): wire @RequireLogin/@RequireRole to all existing controllers"
```

---

## Task 21: pgvector tenant filter in RagService

**Files:**
- Modify: `src/main/java/com/fangsa/ai/service/RagService.java` (and any other vector_store call sites)

**Interfaces:**
- Every `VectorStore.similaritySearch(...)` must include `tenant_id = current` filter expression

- [ ] **Step 1: Locate call sites**

```bash
grep -rn "similaritySearch\|VectorStore\|vectorStore" src/main/java/
```

- [ ] **Step 2: Wrap with helper**

Add to `RagService`:

```java
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.Filter.Expression;
import org.springframework.ai.vectorstore.filter.Filter.ExpressionType;
import org.springframework.ai.vectorstore.filter.Filter.Key;
import org.springframework.ai.vectorstore.filter.Filter.Value;

private Expression tenantFilter() {
    AuthContext ctx = AuthContext.current();
    if (ctx == null || ctx.getTenantId() == null) {
        throw new AuthException(AuthErrorCode.MISSING_TOKEN, "login required");
    }
    return new Expression(ExpressionType.EQ, new Key("tenant_id"), new Value(ctx.getTenantId()));
}
```

In every similarity search, combine with existing filters via AND. Example:

```java
Expression combined = existing == null
        ? tenantFilter()
        : new Expression(ExpressionType.AND, existing, tenantFilter());
SearchRequest req = SearchRequest.query(q).withFilterExpression(combined);
return vectorStore.similaritySearch(req);
```

- [ ] **Step 3: Write a focused test verifying the filter is applied**

```bash
grep -n "tenant_id" src/test/java/com/fangsa/ai/service/RagServiceTest.java 2>/dev/null || echo "no existing test"
```

If a `RagServiceTest` exists, add a test mocking `VectorStore` and asserting the SearchRequest contains a `tenant_id` filter with current tenant id. Otherwise create one — at minimum an integration assertion (see Task 23).

- [ ] **Step 4: Compile + commit**

```bash
./mvnw -q compile
git add -A
git commit -m "feat(rag): inject tenant_id filter into pgvector similarity search"
```

---

## Task 22: application.yml additions

**Files:**
- Modify: `src/main/resources/application.yml`

- [ ] **Step 1: Add JWT config**

```yaml
jwt:
  secret: dev-secret-please-change-32bytes-minimum-length-required
  access-ttl-millis: 900000       # 15 min
  refresh-ttl-millis: 2592000000  # 30 days
```

> 生产部署必须通过环境变量 `JWT_SECRET` 覆盖。

- [ ] **Step 2: Commit**

```bash
git add src/main/resources/application.yml
git commit -m "chore(config): jwt ttl + dev secret with env override note"
```

---

## Task 23: Backend integration test

**Files:**
- Create: `src/test/java/com/fangsa/ai/it/AuthIntegrationTest.java`

- [ ] **Step 1: Test class**

```java
package com.fangsa.ai.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;
import org.springframework.test.context.TestPropertySource;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = "spring.main.banner-mode=off")
class AuthIntegrationTest {

    @LocalServerPort int port;
    @Autowired ObjectMapper om;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;

    @Test
    void fullFlow_suCreatesTenant_acceptInvite_login_listAgentsIsTenantScoped() throws Exception {
        // 1. su login
        var suLogin = http("/platform/auth/login",
                Map.of("username", "su", "password", suPassword()), null, null);
        String suAccess = suLogin.get("data").get("accessToken").asText();

        // 2. create tenant
        var create = http("/platform/tenants",
                Map.of("code", "acme" + System.currentTimeMillis(), "name", "ACME"),
                Map.of("Authorization", "Bearer " + suAccess), null);
        String inviteCode = create.get("data").get("inviteCode").asText();
        long tenantId = create.get("data").get("tenantId").asLong();

        // 3. accept invite
        var accept = http("/auth/accept-invite",
                Map.of("code", inviteCode, "password", "Pw!12345", "displayName", "Alice"), null, null);
        String ac = accept.get("data").get("accessToken").asText();
        String refreshCookie = accept.getHeaders().getFirst("Set-Cookie").split(";")[0];

        // 4. call /agent/list with tenant scope
        var list = http("/agent/list", HttpMethod.GET, null, Map.of("Authorization", "Bearer " + ac), null);
        assertEquals(200, list.get("code").asInt());

        // 5. /auth/me roundtrip
        var me = http("/auth/me", HttpMethod.GET, null, Map.of("Authorization", "Bearer " + ac), null);
        assertEquals(tenantId, me.get("data").get("user").get("tenantId").asLong());

        // 6. refresh rotation
        var ref = http("/auth/refresh", null, null, refreshCookie);
        assertEquals(200, ref.get("code").asInt());
        String newCookie = ref.getHeaders().getFirst("Set-Cookie").split(";")[0];
        assertNotEquals(refreshCookie, newCookie);
    }

    private String suPassword() {
        // 启动时 AuthBootstrapRunner 写入日志;测试需要从 DB 读取
        return jdbc.queryForObject(
                "SELECT password_hash FROM platform_admin WHERE username='su'", String.class);
        // 注:此处拿的是 hash,不是明文。本测试通过完整重置 su 来获得明文:
        //    在 @BeforeEach 中 jdbc.update("UPDATE platform_admin SET password_hash = ? WHERE username='su'", BCrypt.hashpw("su-test-pw", BCrypt.gensalt(10)))
    }

    // http() helper: 见下
}
```

**Helper + fix for su password** (完整实现):

```java
import org.junit.jupiter.api.BeforeEach;
import org.springframework.security.crypto.bcrypt.BCrypt;

@BeforeEach
void resetSu() {
    jdbc.update("UPDATE platform_admin SET password_hash = ? WHERE username='su'",
            BCrypt.hashpw("su-test-pw", BCrypt.gensalt(10)));
    jdbc.update("DELETE FROM refresh_token");
    jdbc.update("DELETE FROM app_user WHERE username <> 'admin' OR id NOT IN (SELECT id FROM app_user WHERE username='admin')");
}

private record Resp(int status, com.fasterxml.jackson.databind.JsonNode body, org.springframework.http.HttpHeaders headers) {}

private Resp http(String path, org.springframework.http.HttpMethod method,
                  Object body, Map<String, String> extraHeaders, String cookie) throws Exception {
    org.springframework.http.HttpHeaders h = new org.springframework.http.HttpHeaders();
    if (body != null) h.setContentType(MediaType.APPLICATION_JSON);
    if (extraHeaders != null) h.setAll(extraHeaders);
    if (cookie != null) h.add("Cookie", cookie);
    var req = body == null
            ? new org.springframework.http.HttpEntity<Void>(h)
            : new org.springframework.http.HttpEntity<>(om.writeValueAsString(body), h);
    var resp = new org.springframework.boot.test.web.client.TestRestTemplate().exchange(
            "http://localhost:" + port + path, method, req, String.class);
    return new Resp(resp.getStatusCode().value(),
            om.readTree(resp.getBody() == null ? "{}" : resp.getBody()),
            resp.getHeaders());
}

private Resp http(String path, Object body, Map<String, String> extraHeaders, String cookie) throws Exception {
    return http(path, HttpMethod.POST, body, extraHeaders, cookie);
}
```

- [ ] **Step 2: Run**

```bash
./mvnw -q test -Dtest=AuthIntegrationTest
```

Expected: passes.

- [ ] **Step 3: Commit**

```bash
git add src/test/java/com/fangsa/ai/it/AuthIntegrationTest.java
git commit -m "test(auth): end-to-end integration (su→tenant→invite→login→list→refresh)"
```

---

## Task 24: Frontend deps + axios setup

**Files:**
- Modify: `smart-agent-frontend/package.json`
- Create: `smart-agent-frontend/src/api/http.js`

- [ ] **Step 1: Add pinia**

```bash
cd smart-agent-frontend
npm install pinia@^2.2.0
```

- [ ] **Step 2: axios instance**

`src/api/http.js`:

```js
import axios from 'axios'

export const http = axios.create({
  baseURL: '/api',
  withCredentials: true,
  timeout: 30000,
})

let auth = null  // injected by stores/auth.js on bootstrap

export function bindAuth(store) { auth = store }
```

- [ ] **Step 3: Commit**

```bash
git add package.json package-lock.json src/api/http.js
git commit -m "feat(fe): add pinia + axios instance skeleton"
```

---

## Task 25: Pinia auth store + bootstrap + silent refresh

**Files:**
- Create: `smart-agent-frontend/src/stores/auth.js`
- Modify: `smart-agent-frontend/src/main.js`

- [ ] **Step 1: Store**

`src/stores/auth.js`:

```js
import { defineStore } from 'pinia'
import { http, bindAuth } from '../api/http'
import router from '../router'

export const useAuthStore = defineStore('auth', {
  state: () => ({
    accessToken: null,
    expiresAt: 0,
    user: null,
    ready: false,
  }),
  actions: {
    async bootstrap() {
      bindAuth(this)
      try {
        const r = await http.post('/auth/refresh')
        this.accessToken = r.data.data.accessToken
        this.expiresAt = r.data.data.expiresAt
        await this.fetchMe()
      } catch (e) {
        // 没登录是预期情况
      } finally {
        this.ready = true
      }
    },
    async login(tenantCode, username, password) {
      const r = await http.post('/auth/login', { tenantCode, username, password })
      this.accessToken = r.data.data.accessToken
      this.expiresAt = r.data.data.expiresAt
      this.user = r.data.data.user
      this.ready = true
    },
    async logout() {
      try { await http.post('/auth/logout') } catch (e) {}
      this.clear()
      router.push('/login')
    },
    async fetchMe() {
      const r = await http.get('/auth/me')
      this.user = r.data.data.user
      this._tenant = r.data.data.tenant
    },
    async silentRefresh() {
      const r = await http.post('/auth/refresh')
      this.accessToken = r.data.data.accessToken
      this.expiresAt = r.data.data.expiresAt
    },
    clear() {
      this.accessToken = null
      this.expiresAt = 0
      this.user = null
    },
  },
})
```

- [ ] **Step 2: Wire in main.js**

`src/main.js`:

```js
import { createApp } from 'vue'
import { createPinia } from 'pinia'
import App from './App.vue'
import router from './router'
import './style.css'
import { setupInterceptors } from './api/interceptors'

const app = createApp(App)
app.use(createPinia())
app.use(router)
setupInterceptors()
app.mount('#app')
```

- [ ] **Step 3: Commit**

```bash
git add src/stores/auth.js src/main.js
git commit -m "feat(fe): Pinia auth store with bootstrap + silent refresh"
```

---

## Task 26: axios request/response interceptors

**Files:**
- Create: `smart-agent-frontend/src/api/interceptors.js`

- [ ] **Step 1: Implement**

```js
import { http } from './http'
import { useAuthStore } from '../stores/auth'
import router from '../router'
import { ElMessage } from 'element-plus'

let refreshing = null  // 单例,避免 40101 风暴

export function setupInterceptors() {
  http.interceptors.request.use(cfg => {
    const auth = useAuthStore()
    if (auth.accessToken) cfg.headers.Authorization = `Bearer ${auth.accessToken}`
    return cfg
  })

  http.interceptors.response.use(r => r, async err => {
    const auth = useAuthStore()
    const { config, response } = err
    if (!response) return Promise.reject(err)
    const code = response.data?.code

    if (response.status === 401 && code === 40101 && !config._retried) {
      config._retried = true
      refreshing = refreshing || auth.silentRefresh().finally(() => { refreshing = null })
      try {
        await refreshing
        config.headers.Authorization = `Bearer ${auth.accessToken}`
        return http(config)
      } catch (e) {
        auth.clear()
        router.push('/login')
        return Promise.reject(e)
      }
    }

    if ([40102, 40103].includes(code)) {
      auth.clear()
      router.push('/login')
      ElMessage.warning(code === 40103 ? '已在其他设备登录' : '登录已过期,请重新登录')
      return Promise.reject(err)
    }

    if (code === 40301) {
      router.push('/403')
      return Promise.reject(err)
    }

    return Promise.reject(err)
  })
}
```

- [ ] **Step 2: Commit**

```bash
git add src/api/interceptors.js
git commit -m "feat(fe): axios interceptors with single-flight silent refresh"
```

---

## Task 27: vue-router + guards + meta

**Files:**
- Modify: `smart-agent-frontend/src/router/index.js`
- Create: `smart-agent-frontend/src/router/guards.js`

- [ ] **Step 1: guards.js**

```js
import { useAuthStore } from '../stores/auth'

export function installGuards(router) {
  router.beforeEach(async (to) => {
    const auth = useAuthStore()
    if (!auth.ready) await auth.bootstrap()
    if (to.meta.public) return true
    if (!auth.accessToken) {
      return { path: '/login', query: { redirect: to.fullPath } }
    }
    if (to.meta.requiresAdmin && auth.user?.role !== 'tenant_admin') {
      return '/403'
    }
    return true
  })
}
```

- [ ] **Step 2: rewrite router/index.js with meta**

```js
import { createRouter, createWebHistory } from 'vue-router'
import { installGuards } from './guards'
import Login from '../views/Login.vue'
import AcceptInvite from '../views/AcceptInvite.vue'
import Forbidden from '../views/Forbidden.vue'
import TenantMembers from '../views/TenantMembers.vue'

const routes = [
  { path: '/', redirect: '/knowledge' },
  { path: '/login', name: 'Login', component: Login, meta: { public: true } },
  { path: '/accept-invite/:code', name: 'AcceptInvite', component: AcceptInvite, meta: { public: true } },
  { path: '/403', name: 'Forbidden', component: Forbidden, meta: { public: true } },
  { path: '/tenant/members', name: 'TenantMembers', component: TenantMembers, meta: { requiresAdmin: true } },
  { path: '/agent', name: 'Agent', component: () => import('../views/Agent.vue') },
  { path: '/knowledge', name: 'Knowledge', component: () => import('../views/Knowledge.vue') },
  { path: '/system', name: 'System', component: () => import('../views/System.vue') },
  { path: '/tools', component: () => import('../views/Tools.vue'),
    redirect: '/tools/md-converter',
    children: [
      { path: 'md-converter', component: () => import('../views/MdConverter.vue') },
    ],
  },
  { path: '/knowledge/:id', component: () => import('../views/KnowledgeDetail.vue') },
  { path: '/agent/:id', component: () => import('../views/AgentDetail.vue') },
]

const router = createRouter({ history: createWebHistory(), routes })
installGuards(router)
export default router
```

- [ ] **Step 3: Commit**

```bash
git add src/router/
git commit -m "feat(fe): router guards + meta + new public routes"
```

---

## Task 28: Login / AcceptInvite / Forbidden views

**Files:**
- Create: `smart-agent-frontend/src/views/Login.vue`
- Create: `src/views/AcceptInvite.vue`
- Create: `src/views/Forbidden.vue`

- [ ] **Step 1: Login.vue (Element Plus form)**

```vue
<template>
  <el-card style="max-width:380px;margin:80px auto">
    <h2>登录</h2>
    <el-form :model="form" label-width="80px" @submit.prevent="onSubmit">
      <el-form-item label="租户 code">
        <el-input v-model="form.tenantCode" placeholder="如 acme" />
      </el-form-item>
      <el-form-item label="用户名">
        <el-input v-model="form.username" />
      </el-form-item>
      <el-form-item label="密码">
        <el-input v-model="form.password" type="password" show-password />
      </el-form-item>
      <el-button type="primary" native-type="submit" :loading="loading" style="width:100%">登录</el-button>
    </el-form>
  </el-card>
</template>

<script setup>
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '../stores/auth'
import { ElMessage } from 'element-plus'

const auth = useAuthStore()
const route = useRoute()
const router = useRouter()
const form = reactive({ tenantCode: '', username: '', password: '' })
const loading = ref(false)

async function onSubmit() {
  loading.value = true
  try {
    await auth.login(form.tenantCode, form.username, form.password)
    router.push(route.query.redirect || '/knowledge')
  } catch (e) {
    ElMessage.error(e.response?.data?.msg || '登录失败')
  } finally { loading.value = false }
}
</script>
```

- [ ] **Step 2: AcceptInvite.vue**

```vue
<template>
  <el-card style="max-width:380px;margin:80px auto">
    <h2>接受邀请</h2>
    <p>租户: <b>{{ tenantName || '加载中…' }}</b></p>
    <el-form @submit.prevent="onSubmit">
      <el-form-item label="显示名">
        <el-input v-model="form.displayName" />
      </el-form-item>
      <el-form-item label="密码">
        <el-input v-model="form.password" type="password" show-password />
      </el-form-item>
      <el-button type="primary" native-type="submit" :loading="loading" style="width:100%">接受并登录</el-button>
    </el-card>
</template>
<script setup>
import { reactive, ref, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '../stores/auth'
import { http } from '../api/http'
import { ElMessage } from 'element-plus'

const auth = useAuthStore()
const route = useRoute()
const router = useRouter()
const form = reactive({ displayName: '', password: '' })
const loading = ref(false)

async function onSubmit() {
  loading.value = true
  try {
    await http.post('/auth/accept-invite', {
      code: route.params.code, password: form.password, displayName: form.displayName,
    })
    // 后端 set-cookie 后,我们主动 bootstrap store
    await auth.bootstrap()
    router.push('/knowledge')
  } catch (e) {
    ElMessage.error(e.response?.data?.msg || '接受失败')
  } finally { loading.value = false }
}
</script>
```

> 备注:页面顶部用邀请码作为副标题占位("您正在接受邀请: <code>"),不调 preview 端点。

- [ ] **Step 3: Forbidden.vue**

```vue
<template>
  <el-result icon="warning" title="403" sub-title="您没有权限访问该资源">
    <template #extra>
      <el-button @click="$router.push('/')">返回首页</el-button>
    </template>
  </el-result>
</template>
```

- [ ] **Step 4: Commit**

```bash
git add src/views/Login.vue src/views/AcceptInvite.vue src/views/Forbidden.vue
git commit -m "feat(fe): Login / AcceptInvite / Forbidden views"
```

---

## Task 29: TenantMembers view + nav link

**Files:**
- Create: `smart-agent-frontend/src/views/TenantMembers.vue`
- Modify (optional): a nav component (skip if no central nav exists; users can use direct URL `/tenant/members`)

- [ ] **Step 1: TenantMembers.vue**

```vue
<template>
  <div style="padding:24px">
    <h2>成员管理</h2>
    <el-button type="primary" @click="dlg = true">邀请新成员</el-button>
    <el-table :data="list" style="margin-top:16px">
      <el-table-column prop="username" label="用户名" />
      <el-table-column prop="displayName" label="显示名" />
      <el-table-column prop="role" label="角色" />
      <el-table-column prop="status" label="状态">
        <template #default="{ row }">{{ row.status === 1 ? '正常' : '停用' }}</template>
      </el-table-column>
      <el-table-column label="操作">
        <template #default="{ row }">
          <el-button size="small" @click="reset(row.id)">重置密码</el-button>
          <el-button size="small" type="danger" @click="disable(row.id)" :disabled="row.status === 0">停用</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="dlg" title="邀请新成员">
      <el-form>
        <el-form-item label="用户名"><el-input v-model="form.username" /></el-form-item>
        <el-form-item label="显示名"><el-input v-model="form.displayName" /></el-form-item>
        <el-form-item label="角色">
          <el-select v-model="form.role">
            <el-option value="tenant_member" label="成员" />
            <el-option value="tenant_admin" label="管理员" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dlg = false">取消</el-button>
        <el-button type="primary" @click="create">创建</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="pwdDlg" title="重置后的临时密码">
      <p>请把以下密码转交给成员,要求其首次使用后立即修改(本期后端暂不强制):</p>
      <el-input v-model="tempPwd" readonly />
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import { http } from '../api/http'
import { ElMessage } from 'element-plus'

const list = ref([])
const dlg = ref(false)
const pwdDlg = ref(false)
const tempPwd = ref('')
const form = reactive({ username: '', displayName: '', role: 'tenant_member' })

async function reload() {
  const r = await http.get('/tenant/members')
  list.value = r.data.data
}

async function create() {
  try {
    const r = await http.post('/tenant/members', form)
    tempPwd.value = r.data.data.tempPassword
    pwdDlg.value = true
    dlg.value = false
    await reload()
  } catch (e) { ElMessage.error(e.response?.data?.msg || '创建失败') }
}

async function reset(id) {
  const r = await http.post(`/tenant/members/${id}/reset-password`)
  tempPwd.value = r.data.data.tempPassword
  pwdDlg.value = true
}

async function disable(id) {
  await http.post(`/tenant/members/${id}/disable`)
  await reload()
}

onMounted(reload)
</script>
```

- [ ] **Step 2: Commit**

```bash
git add src/views/TenantMembers.vue
git commit -m "feat(fe): TenantMembers view (invite / reset / disable)"
```

---

## Task 30: vite proxy

**Files:**
- Modify: `smart-agent-frontend/vite.config.js`

- [ ] **Step 1: Add proxy**

```js
import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
  server: {
    proxy: {
      '/api': { target: 'http://localhost:8080', changeOrigin: true },
    },
  },
})
```

- [ ] **Step 2: Commit**

```bash
git add vite.config.js
git commit -m "chore(fe): vite proxy /api → backend"
```

---

## Task 31: App.vue logout button + mount bootstrap

**Files:**
- Modify: `smart-agent-frontend/src/App.vue`

- [ ] **Step 1: Replace App.vue**

```vue
<template>
  <router-view />
</template>

<script setup>
import { useAuthStore } from './stores/auth'
import { onMounted } from 'vue'

const auth = useAuthStore()
onMounted(() => { auth.bootstrap() })
</script>
```

- [ ] **Step 2: Commit**

```bash
git add src/App.vue
git commit -m "feat(fe): App.vue bootstraps auth on mount"
```

---

## Task 32: End-to-end manual smoke

**Files:**
- Create: `scripts/smoke-auth.sh`

- [ ] **Step 1: Write smoke script**

`scripts/smoke-auth.sh`:

```bash
#!/usr/bin/env bash
set -euo pipefail
BASE=${BASE:-http://localhost:8080}

echo "1. su login"
SU=$(curl -sS -X POST "$BASE/platform/auth/login" \
  -H 'Content-Type: application/json' \
  -d '{"username":"su","password":"'$SU_PWD'"}')
SU_TOK=$(echo "$SU" | python3 -c "import sys,json;print(json.load(sys.stdin)['data']['accessToken'])")
echo "  su token len=${#SU_TOK}"

echo "2. create tenant"
SUF=$(date +%s)
CREATE=$(curl -sS -X POST "$BASE/platform/tenants" \
  -H "Authorization: Bearer $SU_TOK" -H 'Content-Type: application/json' \
  -d "{\"code\":\"acme-$SUF\",\"name\":\"ACME $SUF\"}")
echo "  $CREATE"
INV=$(echo "$CREATE" | python3 -c "import sys,json;print(json.load(sys.stdin)['data']['inviteCode'])")

echo "3. accept invite"
JAR=$(mktemp)
curl -sS -c "$JAR" -X POST "$BASE/auth/accept-invite" \
  -H 'Content-Type: application/json' \
  -d "{\"code\":\"$INV\",\"password\":\"Pw!12345\",\"displayName\":\"Alice\"}" >/dev/null

echo "4. login"
curl -sS -c "$JAR" -X POST "$BASE/auth/login" \
  -H 'Content-Type: application/json' \
  -d "{\"tenantCode\":\"acme-$SUF\",\"username\":\"admin\",\"password\":\"Pw!12345\"}" >/dev/null

echo "5. /agent/list"
curl -sS -b "$JAR" -X GET "$BASE/agent/list" | head -c 200; echo

echo "6. refresh"
curl -sS -b "$JAR" -c "$JAR" -X POST "$BASE/auth/refresh" | head -c 200; echo

echo "7. logout"
curl -sS -b "$JAR" -c "$JAR" -X POST "$BASE/auth/logout" -o /dev/null -w '%{http_code}\n'

rm -f "$JAR"
echo "smoke OK"
```

- [ ] **Step 2: Run end-to-end**

```bash
# 后台启动后端(从前一个 task 留下来的进程)
cd xiaoda-backend-intelligence/Spring-AI && ./mvnw -q spring-boot:run &
BACK_PID=$!
sleep 20
# 从日志里捞 su 初始密码
SU_PWD=$(grep -A1 "AuthBootstrap: seeded" logs/spring-boot.log | tail -1 | tr -d ' ')
SU_PWD=${SU_PWD:-}  # 如果之前已经 seed 过,这个变量为空 → 你需要手动 UPDATE platform_admin 设置一个已知密码
bash scripts/smoke-auth.sh
kill $BACK_PID
```

Expected: 7 steps complete; `smoke OK` printed.

- [ ] **Step 3: Commit**

```bash
git add scripts/smoke-auth.sh
git commit -m "test(smoke): end-to-end auth + tenant flow"
```

---

## Self-Review Notes (post-write)

**Spec coverage check:**
- §3 架构 → Task 1 (DB), 7/8 (filter+interceptor), 9 (MP tenant)
- §4 数据模型 → Task 1 (SQL), 6 (store), 13/15 (app_user, invite), 17 (platform_admin)
- §5 JWT 策略 → Task 3 (JwtService), 5 (RefreshTokenService), 22 (config)
- §6 接口契约 → Tasks 13–19
- §7 错误码 → Task 3 (enum), 10 (handler)
- §8 前端 → Tasks 24–31
- §9 CORS → Task 7
- §10 租户隔离 → Task 9 (MP), 21 (pgvector)
- §11 错误处理 → Task 10
- §12 可观测性 → Task 11
- §13 测试 → Task 3, 5, 13, 15, 23
- §14 落地顺序 → covered by task order
- §15 风险 → Task 9 documents `getTenantId() returns null`, ignoreTable list
- §16 后续演进 → not in scope (deferred)

**Placeholder scan:** no TBD/TODO. All code blocks concrete.

**Type consistency:** `AuthContext.Kind`, `AuthErrorCode`, `LoginResult`, `RotateResult`, `IssueResult` are referenced consistently. `RefreshTokenStore.InsertParams` matches in impl + test.

**Ambiguity fixes:** Task 17 explicitly removes refresh for platform admin (DB FK doesn't allow). Task 15 explicitly revises `auth.login` call to manual token issuance. Task 13 `@WebMvcTest` deferred to integration (slice testing brittle here).

---

## End of Plan