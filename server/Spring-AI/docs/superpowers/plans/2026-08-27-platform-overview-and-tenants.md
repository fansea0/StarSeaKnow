# 平台概览与租户管理 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为平台管理员提供角色隔离的概览和租户管理工作台。

**Architecture:** 后端扩展既有 `/platform` 控制器，提供概览和分页租户查询；前端在 `/system` 下建立平台专属子路由，复用已有 `/api` Axios 客户端和 `loginPlatform` 存储的令牌。

**Tech Stack:** Java 17, Spring Boot 3.3, MyBatis-Plus 3.5.6, JUnit 5, Vue 3, Pinia, Vue Router, Element Plus, Axios, Vite.

**Spec:** `docs/superpowers/specs/2026-08-27-platform-system-management-design.md`

## Global Constraints

- `/platform/**` 管理 API 必须使用 `@RequireRole("platform_admin")`。
- 平台路由必须拒绝租户用户；平台导航不得展示给租户用户。
- 不得从管理 API 返回密码、令牌、密码哈希或 API 密钥。
- 停用租户只把 `tenant.status` 更新为 `0`，不得删除租户数据。
- 前端必须使用 `/api` 代理，禁止硬编码后端端口。

---

## File Structure

- Modify: `src/main/java/com/fansea/platform/PlatformTenantController.java` — 分页查询、筛选、邀请码到期时间和停用校验。
- Create: `src/main/java/com/fansea/platform/PlatformOverviewController.java` — 聚合租户和用户计数。
- Create: `src/test/java/com/fansea/platform/PlatformManagementControllerTest.java` — 平台授权与响应契约测试。
- Modify: `src/test/java/com/fansea/ai/it/AuthIntegrationTest.java` — 平台端到端验证。
- Modify: `smart-agent-frontend/src/router/index.js`, `guards.js`, `App.vue` — 平台路由、权限和导航。
- Replace: `smart-agent-frontend/src/views/System.vue` — 系统管理壳。
- Create: `smart-agent-frontend/src/views/system/Overview.vue`, `Tenants.vue` 和对应 Vitest 测试。

## API Contracts

`GET /platform/overview`:

```json
{ "code": 200, "data": { "tenantTotal": 12, "tenantActive": 10, "tenantDisabled": 2, "userTotal": 48 } }
```

`GET /platform/tenants?page=1&pageSize=20&keyword=acme&status=1`:

```json
{ "code": 200, "data": { "items": [], "page": 1, "pageSize": 20, "total": 0 } }
```

`POST /platform/tenants` 保持 `{code,name}` 请求，响应增加 `inviteExpiresAt`。

### Task 1: 平台概览 API

**Files:**
- Create: `src/main/java/com/fansea/platform/PlatformOverviewController.java`
- Test: `src/test/java/com/fansea/platform/PlatformManagementControllerTest.java`

**Consumes:** `TenantMapper`, `AppUserMapper`, `AjaxResult`, `@RequireRole`.

**Produces:** `GET /platform/overview`，返回四项计数。

- [ ] **Step 1: Write the failing test**

```java
@Test
void overview_returnsCountsForPlatformAdmin() throws Exception {
    // Seed 3 tenants (2 active) and 5 users.
    // Assert code=200, tenantTotal=3, tenantActive=2,
    // tenantDisabled=1 and userTotal=5.
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -Dtest=PlatformManagementControllerTest test`

Expected: FAIL because there is no `/platform/overview` handler.

- [ ] **Step 3: Write minimal implementation**

```java
@GetMapping("/overview")
@RequireRole("platform_admin")
AjaxResult overview() {
  long total = tenants.selectCount(null);
  long active = tenants.selectCount(new QueryWrapper<Tenant>().eq("status", 1));
  return AjaxResult.success(Map.of("tenantTotal", total, "tenantActive", active,
      "tenantDisabled", total - active, "userTotal", users.selectCount(null)));
}
```

- [ ] **Step 4: Run focused test**

Run: `mvn -Dtest=PlatformManagementControllerTest test`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/fansea/platform/PlatformOverviewController.java src/test/java/com/fansea/platform/PlatformManagementControllerTest.java
git commit -m "feat: add platform overview endpoint"
```

### Task 2: 分页租户管理 API

**Files:**
- Modify: `src/main/java/com/fansea/platform/PlatformTenantController.java:34-70`
- Modify: `src/test/java/com/fansea/platform/PlatformManagementControllerTest.java`
- Modify: `src/test/java/com/fansea/ai/it/AuthIntegrationTest.java`

**Consumes:** Task 1 的平台授权方式。

**Produces:** `items/page/pageSize/total` 分页响应、`inviteExpiresAt` 和安全的停用校验。

- [ ] **Step 1: Write failing API tests**

```java
@Test
void listTenants_filtersAndPaginates() throws Exception {
    // Seed an active ACME tenant and a disabled unrelated tenant; request keyword=acme.
    // Assert data.items has ACME and data.page/data.pageSize/data.total are present.
}
@Test
void createTenant_returnsInviteExpiration() throws Exception {
    // POST a unique code and name; assert data.tenantId, data.inviteCode and data.inviteExpiresAt.
}
@Test
void disableUnknownTenant_returnsDomainError() throws Exception {
    // POST /platform/tenants/999999/disable as platform_admin; assert the controlled non-200 response.
}
```

- [ ] **Step 2: Run them**

Run: `mvn -Dtest=PlatformManagementControllerTest test`

Expected: FAIL because list returns an array and create omits `inviteExpiresAt`.

- [ ] **Step 3: Implement minimum API behavior**

Use `new Page<Tenant>(page, pageSize)` and a `QueryWrapper<Tenant>`; apply `like` to `code` and `name` only for nonblank keyword and `eq("status", status)` only for a non-null status. Return `items/page/pageSize/total`. Include `inv.getExpiresAt()` after creation. Load the tenant before disabling; throw a domain error when it is absent.

- [ ] **Step 4: Run controller and integration tests**

Run: `mvn -Dtest=PlatformManagementControllerTest,AuthIntegrationTest test`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/fansea/platform/PlatformTenantController.java src/test/java/com/fansea/platform/PlatformManagementControllerTest.java src/test/java/com/fansea/ai/it/AuthIntegrationTest.java
git commit -m "feat: add paginated platform tenant management"
```

### Task 3: 平台路由和导航隔离

**Files:**
- Modify: `smart-agent-frontend/src/router/index.js`
- Modify: `smart-agent-frontend/src/router/guards.js`
- Modify: `smart-agent-frontend/src/App.vue`
- Test: `smart-agent-frontend/src/router/guards.spec.js`

**Consumes:** `auth.user.role` 的 `platform_admin` 值。

**Produces:** `/system/overview` 和 `/system/tenants`，均含 `meta.requiresPlatformAdmin`。

- [ ] **Step 1: Write failing guard tests**

```js
it('redirects tenant_admin from /system/overview to /403', async () => {})
it('allows platform_admin to enter /system/overview', async () => {})
```

- [ ] **Step 2: Run tests**

Run: `npm run test -- guards.spec.js`

Expected: FAIL because `requiresPlatformAdmin` does not exist.

- [ ] **Step 3: Implement the guard and navigation**

Add:

```js
if (to.meta.requiresPlatformAdmin && auth.user?.role !== 'platform_admin') return '/403'
```

Add nested system routes and redirect `/system` to `/system/overview`. Only platform administrators see System Management; tenant users retain agent, knowledge, tools and member navigation.

- [ ] **Step 4: Run tests and build**

Run: `npm run test -- guards.spec.js && npm run build`

Expected: PASS and production build succeeds.

- [ ] **Step 5: Commit**

```bash
git add smart-agent-frontend/src/router smart-agent-frontend/src/App.vue smart-agent-frontend/package.json smart-agent-frontend/package-lock.json
git commit -m "feat: isolate platform management routes"
```

### Task 4: 概览和租户管理页面

**Files:**
- Replace: `smart-agent-frontend/src/views/System.vue`
- Create: `smart-agent-frontend/src/views/system/Overview.vue`
- Create: `smart-agent-frontend/src/views/system/Tenants.vue`
- Test: `smart-agent-frontend/src/views/system/Tenants.spec.js`

**Consumes:** Tasks 1–3 的 API、路由和 `http` 客户端。

**Produces:** 统计卡片、筛选表格、创建租户表单、复制邀请码、停用确认。

- [ ] **Step 1: Write failing component tests**

```js
it('renders tenants and pagination returned by the API', async () => {})
it('displays inviteCode after a successful create request', async () => {})
it('does not disable before confirmation', async () => {})
```

- [ ] **Step 2: Run tests**

Run: `npm run test -- Tenants.spec.js`

Expected: FAIL because the workspace components do not exist.

- [ ] **Step 3: Implement pages**

`System.vue` renders vertical menu plus nested `router-view`. `Overview.vue` calls `http.get('/platform/overview')` on mount and renders four statistics. `Tenants.vue` owns filters, pagination, create dialog, invite result and loading state; it calls only `/platform/tenants`, uses `navigator.clipboard.writeText(inviteCode)`, and calls `ElMessageBox.confirm` before disable.

- [ ] **Step 4: Verify**

Run: `npm run test -- Tenants.spec.js && npm run build`

Expected: PASS. Manually verify platform login, overview, tenant creation, invite copying and disabled status badge.

- [ ] **Step 5: Commit**

```bash
git add smart-agent-frontend/src/views/System.vue smart-agent-frontend/src/views/system smart-agent-frontend/src/router smart-agent-frontend/src/App.vue
git commit -m "feat: add platform overview and tenant workspace"
```

## Final Verification

- [ ] Run `mvn test` in `xiaoda-backend-intelligence/Spring-AI`.
- [ ] Run `npm run test` and `npm run build` in `smart-agent-frontend`.
- [ ] Verify a platform administrator receives 200 from `/api/platform/overview` and `/api/platform/tenants`.
- [ ] Verify a tenant administrator is redirected from `/system/overview` to `/403`.
- [ ] Verify disabling preserves the tenant row and changes only status.
