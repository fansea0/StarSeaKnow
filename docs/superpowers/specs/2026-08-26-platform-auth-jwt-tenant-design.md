# 平台接入登录系统：多租户 + JWT 鉴权 设计

**日期**：2026-08-26
**状态**：待用户审阅
**作者**：brainstorming 会话
**范围**：当前 `smart-agent` 平台（Spring Boot 3.3.10 + Vue 3）从零接入鉴权与多租户隔离

---

## 1. 背景与目标

`smart-agent` 是一个 RAG/Agent 平台，后端 Spring Boot + PG/pgvector，前端 Vue 3 + Element Plus。当前**完全开放**：

- 所有 Controller 公开可调
- 业务表 `agent / knowledge / file / agent_knowledge / knowledge_file` 均无租户字段
- CORS 全开
- 前端无路由守卫、无 token 存储

**目标**：

1. 引入平台超级管理员（su）与租户管理员（tenant_admin）/成员（tenant_member）三层账号
2. 引入 JWT 鉴权（Access + Refresh 双 token）
3. 所有业务数据按租户隔离（共享库 + `tenant_id` 列级隔离）
4. 现有数据**不迁移**——按全新系统设计
5. 前端接入登录、续签、登出、403 流程

**非目标**（本期不做）：

- 公开自助注册、邮箱验证、找回密码
- SSO / OAuth2 / Keycloak 集成
- 完整 RBAC（自定义角色与细粒度权限）
- Member 写权限（本期写操作统一要求 tenant_admin）
- 审计日志建表（仅 `INFO` 日志）

---

## 2. 关键决策汇总

| 决策点 | 选择 | 备选 |
|---|---|---|
| 租户隔离策略 | 共享库 + `tenant_id` 列 | schema-per-tenant / db-per-tenant |
| 租户开通方式 | 邀请制 + su 开租 | 公开注册 / 纯脚本初始化 |
| JWT 形态 | Access(15m, 无状态) + Refresh(30d, 有状态) | 单 token / Access+黑名单 |
| 鉴权实现 | 自写 `JwtAuthFilter` + JJWT | Spring Security / Keycloak |
| 权限模型 | 二角色（tenant_admin / tenant_member） | 完整 RBAC / 无角色 |
| 前端 token 存储 | Access → Pinia 内存；Refresh → httpOnly cookie | localStorage / 双 cookie |
| Refresh 轮换 | 每次刷新轮换 + reuse detection | 不轮换 / 不检测 |
| Member 写权限 | 暂不允许（只读 + 调 AI） | 按 owner 字段 |
| 现有数据 | **不迁移**，按全新建 | 塞入 default 租户 / truncate |

---

## 3. 架构

```
┌─────────────────────────────────────────────────────────────────┐
│                       Vue 3 SPA (Pinia + axios)                 │
│   Pinia authStore (内存里的 AccessToken)                        │
│   axios request interceptor ──  Authorization: Bearer … │
│   axios response interceptor ── 40101 → /auth/refresh         │
│   vue-router beforeEach ── 未登录跳 /login,缺 admin 跳 /403   │
└────────────────────┬──────────────────────────────┬─────────────┘
                     │  Bearer …(Access)            │ cookie ▼                              ▼
┌─────────────────────────────────────────────────────────────────┐
│       Spring Boot 3.3.10  / Tomcat │
│  ┌────────────────────────────────────────────────────────┐     │
│  │ JwtAuthFilter (OncePerRequestFilter, 高优先级)         │     │
│  │   1. 读 Authorization → 验签 → 解析 tenantId/userId/    │     │
│  │      role → 塞进 AuthContext (ThreadLocal)             │     │
│  │   2. /auth/* 与 /platform/auth/* 直接放行 │     │
│  └────────────────────────────────────────────────────────┘     │
│  ┌────────────────────────┐  ┌──────────────────────────────┐   │
│  │ TenantInterceptor │  │ @RequireRole / @RequireLogin │   │
│  │ (Controller 之前) │  │  (AOP 拦截,注解式)          │   │
│  │ 校验 AuthContext 非空, │  │                              │   │
│  │ 把 tenantId 推到 MP拦截器│  │                              │   │
│  └────────────────────────┘  └──────────────────────────────┘   │
│  ┌────────────────────────────────────────────────────────┐     │
│  │ MyBatis-Plus TenantLineInnerInterceptor │     │
│  │   自动给 SQL 拼 WHERE tenant_id = ?                    │     │
│  │   pgvector 的 vector_store SQL 也要手动加同条件 │     │
│  └────────────────────────────────────────────────────────┘     │
│                            │ │
│                            ▼                                    │
│ Controllers → Service → Mapper → PG (tenant-scoped)            │
└─────────────────────────────────────────────────────────────────┘
```

### 3.1 核心组件清单

后端新增包：`com.fansea.ai.auth`、`com.fansea.ai.platform`、`com.fansea.ai.tenant`

| 类 | 职责 |
|---|---|
| `JwtService` | 签发 / 验签 Access；生成 raw refresh |
| `RefreshTokenService` | 入库 / 轮换 / 撤销 / reuse detection |
| `AuthContext` | ThreadLocal 容器：`userId/tenantId/role/jti` |
| `JwtAuthFilter` | `OncePerRequestFilter`，解析 Bearer |
| `TenantInterceptor` | HandlerInterceptor，校验登录态并注入 tenantId |
| `AuthContextInterceptor` | 配合 AOP 注解 |
| `@RequireLogin` / `@RequireRole("tenant_admin")` | 注解 |
| `AuthController` | `/auth/login` `/auth/refresh` `/auth/logout` `/auth/accept-invite` `/auth/me` |
| `PlatformAuthController` | `/platform/auth/login` `/platform/auth/logout` |
| `PlatformTenantController` | `/platform/tenants`（创建租户并生成 invite） |
| `PlatformInviteController` | `/platform/invites/{code}` 查询 |
| `TenantMemberController` | `/tenant/members` 邀请 / 列表 / 改角色 / 停用 |
| `PasswordEncoder`（封装 `BCryptPasswordEncoder`） | 密码哈希 |
| `AuthAuditLogger` | 登录 / 刷新 / reuse / 邀请接受 等关键事件 INFO 日志 |

依赖增量：

- `io.jsonwebtoken:jjwt-api:0.12.x`
- `io.jsonwebtoken:jjwt-impl:0.12.x`（runtime）
- `io.jsonwebtoken:jjwt-jackson:0.12.x`（runtime）
- `org.springframework.security:spring-security-crypto`（**只**为了 `BCryptPasswordEncoder`，不引入 Security starter）

---

## 4. 数据模型

### 4.1 新增表

```sql
-- 租户
CREATE TABLE tenant (
    id          BIGSERIAL PRIMARY KEY,
    code        VARCHAR(64)  NOT NULL UNIQUE,
    name        VARCHAR(128) NOT NULL,
    status      SMALLINT     NOT NULL DEFAULT 1,   -- 1=active,0=disabled
    create_time TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 业务用户
CREATE TABLE app_user (
    id            BIGSERIAL PRIMARY KEY,
    tenant_id     BIGINT       NOT NULL REFERENCES tenant(id) ON DELETE RESTRICT,
    username      VARCHAR(64)  NOT NULL,
    password_hash VARCHAR(128) NOT NULL,
    display_name  VARCHAR(128),
    role          VARCHAR(32)  NOT NULL,  -- 'tenant_admin' | 'tenant_member'
    status        SMALLINT     NOT NULL DEFAULT 1,
    last_login_at TIMESTAMP WITH TIME ZONE,
    create_time   TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time   TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (tenant_id, username)
);
CREATE INDEX idx_app_user_tenant ON app_user(tenant_id);

-- 平台超级管理员（不属于任何租户）
CREATE TABLE platform_admin (
    id            BIGSERIAL PRIMARY KEY,
    username      VARCHAR(64)  NOT NULL UNIQUE,
    password_hash VARCHAR(128) NOT NULL,
    create_time   TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 邀请码（平台管理员创建租户时生成,首位管理员用它设置密码）
CREATE TABLE invite (
    id            BIGSERIAL PRIMARY KEY,
    tenant_id     BIGINT       NOT NULL REFERENCES tenant(id) ON DELETE CASCADE,
    code          VARCHAR(64)  NOT NULL UNIQUE,
    intended_role VARCHAR(32)  NOT NULL,
    accepted_by   BIGINT       REFERENCES app_user(id),
    expires_at    TIMESTAMP WITH TIME ZONE NOT NULL,
    create_time   TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Refresh token（服务端有状态）
CREATE TABLE refresh_token (
    id          BIGSERIAL PRIMARY KEY,
    user_id     BIGINT       NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    family_id   UUID         NOT NULL,                       -- 同一登录会话共享一个 family
    token_hash  VARCHAR(128) NOT NULL UNIQUE,                  -- SHA-256(raw)
    parent_id   BIGINT       REFERENCES refresh_token(id),     -- 上一代,reuse detection 用
    rotated_at  TIMESTAMP WITH TIME ZONE,
    revoked_at  TIMESTAMP WITH TIME ZONE,
    expires_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    user_agent  VARCHAR(256),
    ip          VARCHAR(64),
    create_time TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_refresh_user   ON refresh_token(user_id);
CREATE INDEX idx_refresh_family ON refresh_token(family_id);
```

### 4.2 现有表加列

> 按"完全当作新的去做"——初始化脚本里 `rag.sql` 之前先 `TRUNCATE agent, knowledge, file, agent_knowledge, knowledge_file RESTART IDENTITY CASCADE`，再执行下列 DDL。`DEFAULT 1` 仅用于兜底，使脚本在已有数据的开发库上也不报错。

```sql
TRUNCATE agent, knowledge, file, agent_knowledge, knowledge_file RESTART IDENTITY CASCADE;

ALTER TABLE agent           ADD COLUMN tenant_id BIGINT NOT NULL DEFAULT 1;
ALTER TABLE knowledge       ADD COLUMN tenant_id BIGINT NOT NULL DEFAULT 1;
ALTER TABLE file            ADD COLUMN tenant_id BIGINT NOT NULL DEFAULT 1;
ALTER TABLE agent_knowledge ADD COLUMN tenant_id BIGINT NOT NULL DEFAULT 1;
ALTER TABLE knowledge_file  ADD COLUMN tenant_id BIGINT NOT NULL DEFAULT 1;

CREATE INDEX idx_agent_tenant            ON agent(tenant_id);
CREATE INDEX idx_knowledge_tenant        ON knowledge(tenant_id);
CREATE INDEX idx_file_tenant             ON file(tenant_id);
CREATE INDEX idx_agent_knowledge_tenant  ON agent_knowledge(tenant_id);
CREATE INDEX idx_knowledge_file_tenant   ON knowledge_file(tenant_id);
```

### 4.3 种子数据

启动时执行一次（`ApplicationRunner`，**只**当 `platform_admin` 表为空时插入）：

```sql
-- 仅在 platform_admin 表为空时执行,后续启动跳过
INSERT INTO platform_admin (username, password_hash)
VALUES ('su', '<bcrypt of 启动时随机生成的 16 字节 base32 密码>');

INSERT INTO tenant (id, code, name)
VALUES (1, 'default', 'Default Tenant')
ON CONFLICT (id) DO NOTHING;
```

随机初始密码生成后写入 `logs/seed-su-credentials.log`（首次部署/空数据库场景）。后续启动不会覆盖。要求 su 首次登录后强制改密码（v1 不实现，提示在 README）。

### 4.4 触发器

沿用现有 `update_timestamp()` 函数与触发器，给新表 `tenant` `app_user` `refresh_token` 同样加上 `BEFORE UPDATE` 触发器。

---

## 5. JWT 策略

### 5.1 Access Token

| 项 | 值 |
|---|---|
| 算法 | HS256 |
| 密钥 | 环境变量 `JWT_SECRET`（≥32 字节随机），开发默认值仅本地用 |
| 寿命 | 15 分钟 |
| 载体 | 前端内存（Pinia） |
| 服务端状态 | 无 |
| Claims | `iss`、`sub`、`tid`、`role`、`iat`、`exp`、`jti` |
| `iss` 取值 | 业务用户 `"smart-agent"`；平台用户 `"smart-agent-platform"` |

示例 payload（业务用户）：

```json
{
  "iss": "smart-agent",
  "sub": "u:12345",
  "tid": 123,
  "role": "tenant_admin",
  "iat": 1756000000,
  "exp": 1756000900,
  "jti": "5f9a…"
}
```

平台管理员示例：

```json
{
  "iss": "smart-agent-platform",
  "sub": "pa:1",
  "role": "platform_admin",
  "iat": 1756000000,
  "exp": 1756000900,
  "jti": "6c2b…"
}
```

### 5.2 Refresh Token

- 形态：43~64 字符随机串（URL-safe base64）
- 存 DB：`SHA-256(raw)`，不存明文
- 寿命：30 天，滑动
- 载体：`httpOnly; Secure(生产); SameSite=Lax` cookie，名为 `sm_refresh`
- 轮换：每次 `/auth/refresh` 成功 → 插新行（`family_id` 同、`parent_id = 旧行 id`），旧行 `revoked_at = now()`
- Reuse detection：若旧行 `revoked_at IS NOT NULL` 又被拿来 refresh → 整个 `family_id` 所有行 `revoked_at = now()`，返回 40103
- 登出：将当前 `family_id` 全族撤销

### 5.3 拦截路径

| 前缀 | 是否要求 Bearer | 是否要求角色 |
|---|---|---|
| `/auth/login` `/auth/refresh` `/auth/accept-invite` | 否（放行） | — |
| `/platform/auth/login` `/platform/auth/refresh` `/platform/auth/logout` | 否（放行） | — |
| `/platform/**`（其它） | 是，`iss=smart-agent-platform` | `role=platform_admin` |
| `/tenant/**` | 是，`iss=smart-agent` | 写：`tenant_admin`；读：`tenant_admin` 或 `tenant_member` |
| `/agent/**` `/file/**` `/knowledge/**` `/rag/**` `/ai/**` `/tool/**` | 是，`iss=smart-agent` | 写：`tenant_admin`；读：两者 |

---

## 6. 接口契约

### 6.1 平台

| Method | Path | Body / Params | 响应 |
|---|---|---|---|
| POST | `/platform/auth/login` | `{username, password}` | 200 `{accessToken, expiresAt}` + `Set-Cookie: sm_platform_refresh=…`（Path=/, SameSite=Lax, HttpOnly, Secure in prod） |
| POST | `/platform/auth/refresh` | cookie `sm_platform_refresh` | 200 同上 + 新 cookie |
| POST | `/platform/auth/logout` | cookie `sm_platform_refresh` | 204，撤销 family |
| POST | `/platform/tenants` | `{code, name, adminDisplayName?}` | 200 `{tenantId, inviteCode}` |
| GET | `/platform/tenants` | — | 200 `[{id, code, name, status, memberCount}]` |
| POST | `/platform/tenants/{id}/disable` | — | 204 |
| GET | `/platform/invites/{code}` | — | 200 `{code, tenantId, tenantName, expiresAt, accepted: boolean}` |

> 业务 cookie 名 `sm_refresh`，平台 cookie 名 `sm_platform_refresh`——并存不冲突；后端按路径前缀（`/auth/*` vs `/platform/auth/*`）区分。

### 6.2 业务

| Method | Path | Body / Params | 响应 |
|---|---|---|---|
| POST | `/auth/login` | `{tenantCode, username, password}` | 200 `{accessToken, expiresAt, user}` + `Set-Cookie: sm_refresh=…` |
| POST | `/auth/refresh` | cookie | 200 同上 + 新 cookie |
| POST | `/auth/logout` | cookie | 204 |
| POST | `/auth/accept-invite` | `{code, password, displayName}` | 200 + 自动登录 + cookie |
| GET | `/auth/me` | Bearer | 200 `{user, tenant}` |

### 6.3 租户成员管理（仅 admin）

| Method | Path | Body | 响应 |
|---|---|---|---|
| GET | `/tenant/members` | — | `[{id, username, displayName, role, status, lastLoginAt}]` |
| POST | `/tenant/members` | `{username, displayName, role}` | 200 `{id, tempPassword}`（管理员把临时密码转交给成员，强制首次登录改密码可后续做） |
| PUT | `/tenant/members/{id}/role` | `{role}` | 204 |
| POST | `/tenant/members/{id}/disable` | — | 204 |
| POST | `/tenant/members/{id}/reset-password` | — | 200 `{tempPassword}` |

### 6.4 现有业务接口改造

每个 Controller 方法加注解：

- 列表 / 详情：`@RequireLogin`
- 新增 / 更新 / 删除：`@RequireRole("tenant_admin")`
- AI 对话：`@RequireLogin`（member 也可调）

`/ai/chat` 写入历史属于"自己使用"，允许 member。

---

## 7. 错误码

| code | 含义 | HTTP | 前端动作 |
|---|---|---|---|
| 40001 | 参数校验失败 | 400 | 表单内联提示 |
| 40100 | 缺 token 或格式错 | 401 | 跳 `/login` |
| 40101 | access 过期 | 401 | 静默 `/auth/refresh` |
| 40102 | refresh 过期或已撤销 | 401 | 清状态 + 跳 `/login` |
| 40103 | refresh reuse（family 全废） | 401 | 同上 + toast |
| 40301 | 缺 tenant_admin 角色 | 403 | 跳 `/403` |
| 40302 | 跨租户访问拒绝 | 403 | toast |
| 40401 | 资源不存在或不属于本租户 | 404 | — |
| 40901 | 用户名冲突 | 409 | 表单内联提示 |
| 41001 | 邀请码失效 | 410 | toast |

统一响应体：

```json
{
  "code": 40101,
  "msg":  "access token expired",
  "data": null
}
```

`WWW-Authenticate` 头保留 `Bearer error="invalid_token", error_description="expired"`。

---

## 8. 前端集成

### 8.1 Pinia authStore

```js
// stores/auth.js
state: () => ({
  accessToken: null,
  expiresAt: 0,
  user: null,        // { id, username, displayName, role, tenantId, tenantCode, tenantName }
  ready: false,
}),
login(tenantCode, username, password) → POST /auth/login
logout() → POST /auth/logout, 清空 + 跳 /login
bootstrap() {
  // 应用启动时调一次: 试 /auth/refresh; 成功则 store 里有 user; 失败则 ready=true 但保持未登录
}
```

### 8.2 axios 拦截器

```js
const api = axios.create({ baseURL: '/api', withCredentials: true })

api.interceptors.request.use(cfg => {
  if (auth.accessToken) cfg.headers.Authorization = `Bearer ${auth.accessToken}`
  return cfg
})

api.interceptors.response.use(
  r => r,
  async err => {
    const { config, response } = err
    if (response?.status === 401 && response.data?.code === 40101 && !config._retried) {
      config._retried = true
      try {
        await auth.silentRefresh()
        config.headers.Authorization = `Bearer ${auth.accessToken}`
        return api(config)
      } catch (e) { auth.clear(); router.push('/login') }
    }
    if ([40102, 40103].includes(response?.data?.code)) {
      auth.clear(); router.push('/login')
      toast('已在其他地方登录')
    }
    return Promise.reject(err)
  }
)
```

### 8.3 路由守卫

```js
router.beforeEach(async (to) => {
  if (!auth.ready) await auth.bootstrap()
  if (to.meta.public) return true
  if (!auth.accessToken) return { path: '/login', query: { redirect: to.fullPath } }
  if (to.meta.requiresAdmin && auth.user.role !== 'tenant_admin') return '/403'
  return true
})
```

`meta.public`：`/login`、`/accept-invite/:code`、`/403`、`/404`。
`meta.requiresAdmin`：`/tenant/members` 等。

### 8.4 新增 / 修改的视图

| 文件 | 说明 |
|---|---|
| `views/Login.vue` | 新增。表单 `tenantCode + username + password` |
| `views/AcceptInvite.vue` | 新增。`/accept-invite/:code`，输密码 → 提交 |
| `views/Forbidden.vue` | 新增。`/403` |
| `views/TenantMembers.vue` | 新增。成员列表 + 邀请 + 改角色 + 停用 |
| `App.vue` | 启动时 `auth.bootstrap()` |
| `router/index.js` | 加 routes + meta |

### 8.5 Vite 代理

```js
// vite.config.js
server: {
  proxy: {
    '/api': {
      target: 'http://localhost:8080',
      changeOrigin: true,
    }
  }
}
```

---

## 9. CORS 与安全头

`WebMvcConfig` 改造（不引入 Security，用 MVC 配置）：

```java
@Override
public void addCorsMappings(CorsRegistry registry) {
    registry.addMapping("/**")
        .allowedOrigins("http://localhost:5173", "<生产前端域名>")
        .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
        .allowedHeaders("*")
        .exposedHeaders("Authorization")
        .allowCredentials(true)
        .maxAge(3600);
}
```

`WebMvcConfigurer.addInterceptors` 注册：

```java
registry.addInterceptor(new JwtAuthFilter(jwtService, objectMapper))
    .addPathPatterns("/**")
    .excludePathPatterns(
        "/auth/login", "/auth/refresh", "/auth/accept-invite",
        "/platform/auth/login", "/platform/auth/refresh", "/platform/auth/logout"
    );
registry.addInterceptor(tenantInterceptor)
    .addPathPatterns("/**")
    .excludePathPatterns(/* 同上 */);
```

顺序：`CorsFilter`（在 `JwtAuthFilter` 之前）→ `JwtAuthFilter` → `TenantInterceptor` → Controller。

---

## 10. 租户隔离实现细节

### 10.1 MyBatis-Plus 自动拼租户

`MybatisPlusInterceptor` 注册 `TenantLineInnerInterceptor`，tenantId 从 `AuthContext` 取：

```java
interceptor.addInnerInterceptor(new TenantLineInnerInterceptor(new TenantLineHandler() {
    @Override public Expression getTenantId() {
        Long tid = AuthContext.current() == null ? null : AuthContext.current().getTenantId();
        return tid == null ? null : new LongValue(tid);   // null → 拦截器跳过本条 SQL 的租户拼接
    }
    @Override public boolean ignoreTable(String table) {
        // 这些表是全局性的(不按当前用户所在租户过滤)
        return Set.of("tenant", "invite", "refresh_token", "platform_admin")
                  .contains(table);
        // 注:app_user 不在此列——成员列表查询需要 WHERE tenant_id = 当前租户
        //   但 /auth/login、/auth/refresh、/auth/accept-invite 不走 TenantInterceptor,
        //   MP 拦截器从 ThreadLocal 读到 null → 不拼 tenant 条件,登录路径用 raw SQL 自取。
    }
}));
```

**注册顺序（MP 拦截器链是栈式,后注册先执行,这里给出最终顺序）**：

```java
MybatisPlusInterceptor mp = new MybatisPlusInterceptor();
mp.addInnerInterceptor(new PaginationInnerInterceptor());                 // 1
mp.addInnerInterceptor(new OptimisticLockerInnerInterceptor());          // 2
mp.addInnerInterceptor(new TenantLineInnerInterceptor(tenantHandler));   // 3 最后
```

TenantLine 必须最后注册（最内层），保证分页 count SQL 也带 tenant 条件。

### 10.2 pgvector 检索加租户过滤

`RagService` / `KnowledgeService` 中走 `vectorStore.similaritySearch(...)` 时，手动构造 `Filter.Expression`：

```java
Filter.Expression tenantFilter = new Filter.Expression(
    Filter.ExpressionType.EQ,
    new Filter.Key("tenant_id"),
    new Filter.Value(AuthContext.current().getTenantId())
);
```

或在自定义 `BatchingStrategy` 中把 `tenant_id` 当成 metadata 一起索引。

### 10.3 跨租户请求

不允许通过 URL/Body 手动注入 `tenant_id`：所有写入一律由 `AuthContext` 注入。任何显式 `tenant_id` 字段在 Controller 入口被剥离（DTO 不暴露该字段；Domain 类 `tenant_id` 由 MP 拦截器自动写入）。

---

## 11. 错误处理

新增 `AuthException(code, msg)`，由 `GlobalExceptionHandler` 集中映射：

| 异常 | 映射 |
|---|---|
| `AuthException(40100)` | 401 + code 40100 |
| `AuthException(40101)` | 401 + code 40101 |
| `ExpiredJwtException` | 401 + code 40101 |
| `SignatureException` / `MalformedJwtException` | 401 + code 40100 |
| `AuthException(40102/40103)` | 401 |
| `AuthException(40301)` | 403 |
| `AuthException(40901)` | 409 |
| 其它 `RuntimeException` | 500（不暴露堆栈） |

`GlobalExceptionHandler` 已存在，新增 `@ExceptionHandler(AuthException.class)` 与 JWT 异常分支即可。

---

## 12. 可观测性

`logback-spring.xml` 增加 logger：

```xml
<logger name="com.fansea.ai.auth" level="INFO"/>
<logger name="com.fangsa.platform" level="INFO"/>
```

关键事件：

```
INFO  AUTH_LOGIN         tenant=acme user=alice ip=… ua=…
INFO  AUTH_REFRESH       user=alice family=… ip=…
WARN  AUTH_REFRESH_REUSE family=… ip=…   ← 触发整 family 撤销
INFO  AUTH_LOGOUT        user=alice family=…
INFO  TENANT_CREATE      code=acme by_su=1
INFO  AUTH_INVITE_ACCEPT code=… user_id=… tenant=…
WARN  AUTH_LOGIN_FAIL    tenant=acme user=ghost ip=…    ← 限速触发后再 WARN
```

不建审计表；将来要建只是 appender 切换。

---

## 13. 测试

### 13.1 单测

| 类 | 用例 |
|---|---|
| `JwtServiceTest` | 签发 / 验签 / 过期 / 签名错 / iss 错 / 篡改 |
| `RefreshTokenServiceTest` | 哈希落库 / 轮换 / reuse detection / 整 family 撤销 |
| `AuthServiceTest` | 密码 BCrypt 校验 / 登录失败锁定（v1 仅日志） |
| `PasswordEncoderTest` | BCrypt 往返 |

### 13.2 切片测试（`@WebMvcTest`）

| 用例 |
|---|
| `/auth/login` 200 + cookie 设置 + access 返回 |
| 缺 `Authorization` 调 `/agent/list` → 40100 |
| 过期 access → 40101 |
| tenant_member 调 `PUT /agent/update/1` → 40301 |
| `POST /auth/accept-invite` 接受后能用新 cookie 调 `/auth/me` |

### 13.3 集成测试（`@SpringBootTest` + 真实 PG schema）

| 用例 |
|---|
| 完整链路：su 建租户 → 接受邀请 → 登录 → 创建 agent → 列 agent 只看到本租户 |
| 越权：把 SQL 强制 `tenant_id=999` 也不会被查询返回（验证 MP 拦截器对 SQL 强制覆盖） |
| 跨租户：租户 A 登录后 URL 用 B 的资源 id → 40401 |

### 13.4 Smoke（手测 curl 脚本）

存为 `scripts/smoke-auth.sh`，包含 Section 6 的关键步骤。CI 暂不接入。

---

## 14. 落地顺序

1. 依赖增量 + `JwtService` / `AuthContext` / `BCryptPasswordEncoder` 包装 + 单测
2. PG 初始化脚本（新表 + ALTER + 索引 + 触发器 + 种子）
3. `RefreshTokenService` + 单测
4. `JwtAuthFilter` + `TenantInterceptor` + `WebMvcConfig` 改造
5. `@RequireLogin` / `@RequireRole` 注解 + AOP
6. `MybatisPlusInterceptor` 注册 `TenantLineInnerInterceptor`
7. `/auth/*` 与 `/platform/*` 端点
8. 现有 6 个 Controller 加注解
9. pgvector 检索加 `tenant_id` 过滤
10. `GlobalExceptionHandler` 新增分支
11. 前端：Pinia store + axios 拦截 + router 守卫 + Login/AcceptInvite/Forbidden/Members 四页面
12. CORS 收紧 + Vite proxy
13. 集成测试 + smoke 脚本

---

## 15. 风险与权衡

- **MyBatis-Plus `TenantLineHandler.getTenantId()` 返回 null 时必须跳过拼租户**：否则 `/auth/login`、`/auth/refresh`、`/auth/accept-invite` 会 NPE。已在 10.1 显式约定。
- **`ignoreTable` 黑名单漏一张表 = 安全洞**：落到代码评审 checklist；只允许 `tenant / invite / refresh_token / platform_admin` 四张全局表。
- **`TenantLineInnerInterceptor` 注册顺序**：必须**最后**注册（最内层），保证分页 count SQL 也带 tenant 条件；否则分页查询可能跨租户泄漏。
- **pgvector 不走 MP**：必须人工加 filter；列入 `RagService` 评审点。
- **轮换 + reuse detection**：实现稍复杂，但避免 refresh 永久有效带来的安全风险。
- **httpOnly cookie 需 HTTPS**：生产部署文档里写明；本地开发走 `http://localhost` 浏览器允许 non-Secure cookie。
- **业务 cookie 与平台 cookie 同名风险**：已分别命名为 `sm_refresh` 与 `sm_platform_refresh`，并按路径前缀区分。
- **Member 写权限暂全关**：业务上 member 能否改自己创建的资源先不做，避免一上来就引入 owner 字段；后续 PR 单独演进。
- **平台 su 强改密码流程**：本期不做，留 README 警示。

---

## 16. 后续演进（不在本期范围）

- Member owner 列 → 放开"自己创建的资源可改"
- Refresh 全部撤销可选化：单设备登出而非整 family
- Redis 缓存 refresh_token 校验
- 平台 admin 强制改密码 + 90 天轮换
- 邮箱绑定与找回密码
- SSO / OAuth2 / Keycloak
- 完整 RBAC

---

## 17. 开放问题

无。所有 Section 已与用户逐段确认。