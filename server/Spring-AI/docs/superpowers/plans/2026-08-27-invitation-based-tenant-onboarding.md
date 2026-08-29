# 邀请码驱动租户开户 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace pre-created-tenant invitation acceptance with invitation-based self-service tenant onboarding, forced initial platform-admin password change, and separate invitation/tenant management workspaces.

**Architecture:** A new `platform_invitation` table owns invitation lifecycle independently of tenant accounts. Registration conditionally consumes one invitation in a transaction and creates the tenant and its sole initial tenant-admin user. Existing tenant data remains isolated with `tenant_id`; platform management separates invitation operations from post-registration tenant operations.

**Tech Stack:** Spring Boot 3, MyBatis-Plus, PostgreSQL, JJWT, Vue 3, Pinia, Vue Router, Element Plus, Vitest.

**Spec:** `xiaoda-backend-intelligence/Spring-AI/docs/superpowers/specs/2026-08-27-invitation-based-tenant-onboarding-design.md`

## Global Constraints

- Platform management APIs require `@RequireRole("platform_admin")`.
- `platform_invitation` is independent of `app_user` and does not pre-bind a tenant.
- An invitation is consumable only when ACTIVE, inside `[valid_from, valid_until]`, and not already used.
- Invitation consumption, tenant creation, and tenant-admin creation occur in one database transaction.
- Tenant usernames are globally unique; tenant login takes username and password only.
- The initial platform administrator must change its default password before platform management APIs are permitted.
- Never return password hashes, raw refresh tokens, or platform credentials in API responses.
- Tenant business data remains constrained to the authenticated tenant; a platform-only mapper bypass must be narrow and explicit.
- Registration page fields are exactly invitation code, username, password, and confirm password.
- Tenant management and invitation management are separate platform navigation entries.

---

## File Structure

| File | Responsibility |
| --- | --- |
| `src/main/resources/db/V2__invitation_based_tenant_onboarding.sql` | Add onboarding schema and migrate constraints without deleting existing tenants. |
| `domain/PlatformInvitation.java`, `mapper/PlatformInvitationMapper.java` | Invitation persistence model and conditional-use SQL. |
| `domain/PlatformAdmin.java`, `domain/Tenant.java`, `mapper/AppUserMapper.java` | Password-change flag, tenant remark, global username lookup. |
| `auth/TenantRegistrationService.java` | Public registration transaction and default tenant identity generation. |
| `auth/AuthController.java`, `auth/AuthService.java` | Username/password login and `/auth/register`. |
| `platform/PlatformAuthService.java`, `platform/PlatformAuthController.java` | First-password-change state and change-password API. |
| `platform/PlatformInvitationController.java`, `platform/PlatformTenantController.java` | Invitation CRUD lifecycle and tenant notes/metadata operations. |
| `src/test/java/.../InvitationOnboardingIntegrationTest.java` | Real PostgreSQL end-to-end onboarding/one-time use/first-password-change tests. |
| `smart-agent-frontend/src/router/*`, `stores/auth.js` | Auth state, forced password-change guard, public register route. |
| `views/Login.vue`, `Register.vue`, `ChangeInitialPassword.vue` | Tenant login simplification, registration, forced password change. |
| `views/System.vue`, `views/system/Invitations.vue`, `views/system/Tenants.vue` | Separate polished management Tabs and platform pages. |
| `src/**/*.spec.js` | Frontend guards, registration form, invitation workspace tests. |

### Task 1: Database migration and persistence models

**Files:**
- Create: `xiaoda-backend-intelligence/Spring-AI/src/main/resources/db/V2__invitation_based_tenant_onboarding.sql`
- Create: `xiaoda-backend-intelligence/Spring-AI/src/main/java/com/fansea/ai/domain/PlatformInvitation.java`
- Create: `xiaoda-backend-intelligence/Spring-AI/src/main/java/com/fansea/ai/mapper/PlatformInvitationMapper.java`
- Modify: `xiaoda-backend-intelligence/Spring-AI/src/main/java/com/fansea/ai/domain/PlatformAdmin.java`
- Modify: `xiaoda-backend-intelligence/Spring-AI/src/main/java/com/fansea/ai/domain/Tenant.java`
- Modify: `xiaoda-backend-intelligence/Spring-AI/src/main/java/com/fansea/ai/mapper/AppUserMapper.java`
- Test: `xiaoda-backend-intelligence/Spring-AI/src/test/java/com/fansea/ai/mapper/PlatformInvitationMapperTest.java`

**Interfaces:**
- Consumes: current PostgreSQL `tenant`, `app_user`, and `platform_admin` tables.
- Produces: `PlatformInvitation { id, code, status, validFrom, validUntil, usedAt, usedTenantId, usedUserId, createdBy }`; `AppUserMapper.selectByUsername(String username)`; `PlatformInvitationMapper.consumeIfAvailable(long id, OffsetDateTime usedAt, long tenantId, long userId)`.

- [ ] **Step 1: Write failing mapper tests**

```java
@Test
void consumeIfAvailable_updatesOneActiveUnusedInvitationOnly() {
    PlatformInvitation invitation = insertActiveInvitation();
    int updated = mapper.consumeIfAvailable(invitation.getId(), OffsetDateTime.now(), 21L, 31L);
    assertThat(updated).isEqualTo(1);
    assertThat(mapper.selectById(invitation.getId()).getStatus()).isEqualTo("USED");
}

@Test
void consumeIfAvailable_rejectsDisabledOrExpiredInvitation() {
    assertThat(mapper.consumeIfAvailable(disabledId, OffsetDateTime.now(), 21L, 31L)).isZero();
    assertThat(mapper.consumeIfAvailable(expiredId, OffsetDateTime.now(), 21L, 31L)).isZero();
}
```

- [ ] **Step 2: Run the mapper tests to verify they fail**

Run: `mvn -Dtest=PlatformInvitationMapperTest test`

Expected: FAIL because the invitation model/mapper and migration do not exist.

- [ ] **Step 3: Add the migration and models**

```sql
ALTER TABLE platform_admin ADD COLUMN IF NOT EXISTS must_change_password BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE tenant ADD COLUMN IF NOT EXISTS remark VARCHAR(512);
ALTER TABLE app_user DROP CONSTRAINT IF EXISTS app_user_tenant_id_username_key;
CREATE UNIQUE INDEX IF NOT EXISTS uq_app_user_username ON app_user(username);

CREATE TABLE IF NOT EXISTS platform_invitation (
  id BIGSERIAL PRIMARY KEY,
  code VARCHAR(64) NOT NULL UNIQUE,
  status VARCHAR(16) NOT NULL,
  valid_from TIMESTAMPTZ NOT NULL,
  valid_until TIMESTAMPTZ NOT NULL,
  used_at TIMESTAMPTZ,
  used_tenant_id BIGINT REFERENCES tenant(id),
  used_user_id BIGINT REFERENCES app_user(id),
  created_by BIGINT NOT NULL REFERENCES platform_admin(id),
  create_time TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CHECK (status IN ('ACTIVE', 'DISABLED', 'USED')),
  CHECK (valid_until > valid_from)
);
```

Implement `consumeIfAvailable` as one `UPDATE platform_invitation ... WHERE id = #{id} AND status = 'ACTIVE' AND used_at IS NULL AND valid_from <= CURRENT_TIMESTAMP AND valid_until >= CURRENT_TIMESTAMP` statement. Keep the old `invite` table untouched for backward database compatibility; new onboarding code must not write to it.

- [ ] **Step 4: Run mapper and compilation tests**

Run: `mvn -Dtest=PlatformInvitationMapperTest test && mvn -DskipTests compile`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add xiaoda-backend-intelligence/Spring-AI/src/main/resources/db/V2__invitation_based_tenant_onboarding.sql xiaoda-backend-intelligence/Spring-AI/src/main/java/com/fansea/ai/domain xiaoda-backend-intelligence/Spring-AI/src/main/java/com/fansea/ai/mapper xiaoda-backend-intelligence/Spring-AI/src/test/java/com/fansea/ai/mapper/PlatformInvitationMapperTest.java
git commit -m "feat: add independent platform invitations"
```

### Task 2: Public registration and username-only tenant login

**Files:**
- Create: `xiaoda-backend-intelligence/Spring-AI/src/main/java/com/fansea/ai/auth/TenantRegistrationService.java`
- Modify: `xiaoda-backend-intelligence/Spring-AI/src/main/java/com/fansea/ai/auth/AuthController.java`
- Modify: `xiaoda-backend-intelligence/Spring-AI/src/main/java/com/fansea/ai/auth/AuthService.java`
- Modify: `xiaoda-backend-intelligence/Spring-AI/src/main/java/com/fansea/ai/auth/AuthErrorCode.java`
- Test: `xiaoda-backend-intelligence/Spring-AI/src/test/java/com/fansea/ai/it/InvitationOnboardingIntegrationTest.java`

**Interfaces:**
- Consumes: Task 1 invitation mapper and global `AppUserMapper.selectByUsername`.
- Produces: `POST /auth/register {inviteCode, username, password, confirmPassword}` and `POST /auth/login {username, password}`; successful responses retain `{accessToken, expiresAt, user}`.

- [ ] **Step 1: Write failing integration tests**

```java
@Test
void registration_consumesInvitation_createsTenantAndLogsIn() {
    String code = createActiveInvitationAsPlatformAdmin();
    ResponseEntity<JsonNode> response = post("/auth/register", Map.of(
        "inviteCode", code, "username", "ocean-admin",
        "password", "Strong!123", "confirmPassword", "Strong!123"));
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody().at("/data/user/username").asText()).isEqualTo("ocean-admin");
    assertThat(tenantFor("ocean-admin").getName()).isEqualTo("ocean-admin 的工作区");
    assertThat(invitation(code).getStatus()).isEqualTo("USED");
}

@Test
void registration_rejectsReusedDisabledExpiredAndMismatchedPasswords() { /* assert controlled 4xx codes for all four cases */ }

@Test
void tenantUser_canLogInWithUsernameAndPasswordOnly() { /* register, then POST /auth/login without tenantCode and assert 200 */ }
```

- [ ] **Step 2: Run the integration test to verify it fails**

Run: `mvn -Dtest=InvitationOnboardingIntegrationTest test`

Expected: FAIL because `/auth/register` and username-only login do not exist.

- [ ] **Step 3: Implement the transaction and endpoints**

```java
@Transactional
public AuthService.LoginResult register(RegisterRequest request, String ip, String ua) {
    validatePasswords(request.password(), request.confirmPassword());
    PlatformInvitation invitation = requireAvailableInvitation(request.inviteCode());
    rejectIfUsernameExists(request.username());
    Tenant tenant = insertTenant(defaultTenantCode(), request.username() + " 的工作区");
    AppUser user = insertTenantAdmin(tenant.getId(), request.username(), request.password());
    if (invitations.consumeIfAvailable(invitation.getId(), OffsetDateTime.now(), tenant.getId(), user.getId()) != 1) {
        throw new AuthException(AuthErrorCode.INVITATION_UNAVAILABLE, "invitation unavailable");
    }
    return issueBusinessSession(user, ip, ua);
}
```

Generate tenant codes server-side with a random suffix; never derive an authorization boundary from a client field. Remove `tenantCode` from `LoginReq`, find an enabled user by username, then load and verify its tenant is active before issuing tokens. Remove the obsolete `/auth/accept-invite` route only after the new registration flow is covered.

- [ ] **Step 4: Run focused and existing auth tests**

Run: `mvn -Dtest=InvitationOnboardingIntegrationTest,AuthServiceTest,JwtAuthFilterTest test`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add xiaoda-backend-intelligence/Spring-AI/src/main/java/com/fansea/ai/auth xiaoda-backend-intelligence/Spring-AI/src/test/java/com/fansea/ai/it/InvitationOnboardingIntegrationTest.java
git commit -m "feat: register tenants from invitations"
```

### Task 3: First platform password change and invitation/tenant platform APIs

**Files:**
- Create: `xiaoda-backend-intelligence/Spring-AI/src/main/java/com/fansea/platform/PlatformInvitationController.java`
- Modify: `xiaoda-backend-intelligence/Spring-AI/src/main/java/com/fansea/platform/PlatformAuthController.java`
- Modify: `xiaoda-backend-intelligence/Spring-AI/src/main/java/com/fansea/platform/PlatformAuthService.java`
- Modify: `xiaoda-backend-intelligence/Spring-AI/src/main/java/com/fansea/platform/PlatformTenantController.java`
- Modify: `xiaoda-backend-intelligence/Spring-AI/src/main/java/com/fansea/ai/auth/AuthAspect.java`
- Test: `xiaoda-backend-intelligence/Spring-AI/src/test/java/com/fansea/platform/PlatformInvitationControllerTest.java`

**Interfaces:**
- Consumes: Task 1 invitation model, `PlatformAdmin.mustChangePassword`, existing platform JWT.
- Produces: `POST /platform/auth/change-initial-password`, invitation list/create/disable endpoints, `PATCH /platform/tenants/{id}/remark`, and a platform login response `{accessToken, expiresAt, mustChangePassword}`.

- [ ] **Step 1: Write failing controller tests**

```java
@Test
void invitationCreateListAndDisable_arePlatformOnly() throws Exception { /* assert 200 with ACTIVE record, then DISABLED; tenant_admin gets 40301 */ }

@Test
void platformAdminMustChangePassword_beforeManagementAccess() throws Exception {
    setPlatformContextWithMustChangePassword();
    mockMvc.perform(get("/platform/invitations")).andExpect(status().isForbidden());
    mockMvc.perform(post("/platform/auth/change-initial-password")
        .contentType(APPLICATION_JSON).content("{\"currentPassword\":\"old\",\"newPassword\":\"Strong!123\",\"confirmPassword\":\"Strong!123\"}"))
        .andExpect(status().isOk());
}

@Test
void platformCanUpdateTenantRemark_butTenantCannot() throws Exception { /* platform 200, tenant 40301 */ }
```

- [ ] **Step 2: Run controller tests to verify they fail**

Run: `mvn -Dtest=PlatformInvitationControllerTest test`

Expected: FAIL because invitation management and initial-password APIs are absent.

- [ ] **Step 3: Implement platform policies and APIs**

```java
@PostMapping("/invitations")
@RequireRole("platform_admin")
public AjaxResult create(@RequestBody CreateInvitationReq req) { /* validate validUntil > validFrom; insert ACTIVE random code */ }

@PostMapping("/invitations/{id}/disable")
@RequireRole("platform_admin")
public AjaxResult disable(@PathVariable long id) { /* conditional ACTIVE -> DISABLED; USED returns controlled conflict */ }
```

Do not create tenants from `PlatformTenantController`. Remove its tenant-create endpoint and legacy invitation generation. Enforce `must_change_password` in a dedicated platform authorization check that permits only the change-password route and logout while true; do not use a client-provided flag as authority. The change-password endpoint validates current password, confirmation, and password strength, then atomically sets the BCrypt hash and `must_change_password=false`.

- [ ] **Step 4: Run platform tests**

Run: `mvn -Dtest=PlatformInvitationControllerTest,PlatformManagementControllerTest test`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add xiaoda-backend-intelligence/Spring-AI/src/main/java/com/fansea/platform xiaoda-backend-intelligence/Spring-AI/src/main/java/com/fansea/ai/auth/AuthAspect.java xiaoda-backend-intelligence/Spring-AI/src/test/java/com/fansea/platform
git commit -m "feat: manage invitations and initial admin password"
```

### Task 4: Tenant profile and platform navigation/auth state

**Files:**
- Modify: `smart-agent-frontend/src/stores/auth.js`
- Modify: `smart-agent-frontend/src/router/index.js`
- Modify: `smart-agent-frontend/src/router/guards.js`
- Modify: `smart-agent-frontend/src/App.vue`
- Create: `smart-agent-frontend/src/views/Register.vue`
- Create: `smart-agent-frontend/src/views/ChangeInitialPassword.vue`
- Modify: `smart-agent-frontend/src/views/Login.vue`
- Replace: `smart-agent-frontend/src/views/AcceptInvite.vue`
- Test: `smart-agent-frontend/src/router/guards.spec.js`
- Test: `smart-agent-frontend/src/views/Register.spec.js`

**Interfaces:**
- Consumes: Task 2 `/auth/register`, username-only `/auth/login`; Task 3 `mustChangePassword` and change-password endpoint.
- Produces: public `/register`, platform `/change-initial-password`, tenant login without tenant code, auth guard redirects based on `mustChangePassword`.

- [ ] **Step 1: Write failing frontend tests**

```js
it('redirects a platform administrator who must change password to /change-initial-password', async () => {
  auth.user = { role: 'platform_admin' }
  auth.mustChangePassword = true
  expect(await guardFor('/system/invitations')).toBe('/change-initial-password')
})

it('submits invitation code, username, password and confirmation to /auth/register', async () => {
  // mount Register.vue, fill four labels, submit, assert mocked http.post payload and redirect
})
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `npm run test -- guards.spec.js Register.spec.js`

Expected: FAIL because the route, state, and registration page do not exist.

- [ ] **Step 3: Implement auth UI and route policy**

```js
const register = (payload) => http.post('/auth/register', payload)
const changeInitialPassword = (payload) => http.post('/platform/auth/change-initial-password', payload)

if (auth.mustChangePassword && to.path !== '/change-initial-password') {
  return '/change-initial-password'
}
```

Login must call `/auth/login` with `{ username, password }` for tenant users and show a visible “使用邀请码注册” link. `Register.vue` must validate matching password fields before sending. Replace the old accept-invite page with a redirect to `/register` so stale links do not expose a broken registration path.

- [ ] **Step 4: Run focused frontend tests and build**

Run: `npm run test -- guards.spec.js Register.spec.js && npm run build`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add smart-agent-frontend/src/stores/auth.js smart-agent-frontend/src/router smart-agent-frontend/src/App.vue smart-agent-frontend/src/views/Login.vue smart-agent-frontend/src/views/Register.vue smart-agent-frontend/src/views/ChangeInitialPassword.vue smart-agent-frontend/src/views/AcceptInvite.vue smart-agent-frontend/src/views/*.spec.js
git commit -m "feat: add invitation registration and initial password flow"
```

### Task 5: Separate invitation workspace, tenant profile, and layout repair

**Files:**
- Modify: `smart-agent-frontend/src/App.vue`
- Modify: `smart-agent-frontend/src/views/System.vue`
- Create: `smart-agent-frontend/src/views/system/Invitations.vue`
- Modify: `smart-agent-frontend/src/views/system/Tenants.vue`
- Create: `smart-agent-frontend/src/views/TenantProfile.vue`
- Modify: `smart-agent-frontend/src/router/index.js`
- Test: `smart-agent-frontend/src/views/system/Invitations.spec.js`
- Test: `smart-agent-frontend/src/views/system/Tenants.spec.js`

**Interfaces:**
- Consumes: Task 3 invitation list/create/disable and tenant remark APIs; Task 4 route guards.
- Produces: `/system/invitations`, a tenant-management page containing only registered tenants and remark editing, `/tenant/profile` for tenant-name editing.

- [ ] **Step 1: Write failing component tests**

```js
it('renders invitation lifecycle data separately from tenant data', async () => {
  mockGet('/platform/invitations', { items: [{ code: 'YQ-7A5K', status: 'ACTIVE' }], total: 1 })
  await mountAndFlush(Invitations)
  expect(wrapper.text()).toContain('YQ-7A5K')
  expect(http.get).not.toHaveBeenCalledWith('/platform/tenants', expect.anything())
})

it('does not call disable endpoint until invitation disable is confirmed', async () => { /* cancel ElMessageBox.confirm; assert no POST */ })

it('tenant table saves only the platform remark and not tenant business fields', async () => { /* edit remark; assert PATCH /platform/tenants/10/remark */ })
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `npm run test -- Invitations.spec.js Tenants.spec.js`

Expected: FAIL because the invitations page and remark controls do not exist.

- [ ] **Step 3: Implement the workspace and layout**

```vue
<!-- System.vue menu entries -->
<el-menu-item index="/system/overview">运营概览</el-menu-item>
<el-menu-item index="/system/tenants">租户管理</el-menu-item>
<el-menu-item index="/system/invitations">邀请码管理</el-menu-item>
```

Set global main content `padding-top: 64px` to match the fixed header. Make the system shell height `calc(100vh - 64px)` and keep its sidebar inside that space; do not nest a second fixed header. `Invitations.vue` provides create-validity dialog, stats, filters, status badges and confirmation-before-disable. `Tenants.vue` removes creation/invite controls, retains list/filter/status action, and adds an internal remark editor. `TenantProfile.vue` calls `PATCH /tenant/profile` to rename only the current tenant.

- [ ] **Step 4: Run tests and build**

Run: `npm run test -- Invitations.spec.js Tenants.spec.js && npm run build`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add smart-agent-frontend/src/App.vue smart-agent-frontend/src/router/index.js smart-agent-frontend/src/views/System.vue smart-agent-frontend/src/views/system smart-agent-frontend/src/views/TenantProfile.vue
git commit -m "feat: separate invitation and tenant management"
```

### Task 6: Full workflow verification and documentation

**Files:**
- Modify: `xiaoda-backend-intelligence/scripts/smoke-auth.sh`
- Modify: `smart-agent-frontend/README.md`
- Test: `xiaoda-backend-intelligence/Spring-AI/src/test/java/com/fansea/ai/it/InvitationOnboardingIntegrationTest.java`

**Interfaces:**
- Consumes: all prior completed APIs and frontend routes.
- Produces: an executable onboarding smoke flow and documented startup/login instructions.

- [ ] **Step 1: Write the failing smoke expectation**

```bash
# Expected flow: platform login → first password change if required → create invitation
# → POST /auth/register → tenant username/password login → platform lists the new tenant
# → disable a second invitation → registration receives a 4xx response.
```

- [ ] **Step 2: Run it before adaptation**

Run: `BASE=http://localhost:8090 SU_PWD='<known password>' xiaoda-backend-intelligence/scripts/smoke-auth.sh`

Expected: FAIL because the legacy script calls `/platform/tenants` creation and `/auth/accept-invite`.

- [ ] **Step 3: Update smoke script and README**

```bash
CREATE=$(curl -sS -X POST "$BASE/platform/invitations" -H "Authorization: Bearer $SU_TOK" ...)
INV=$(echo "$CREATE" | jq -r '.data.code')
curl -sS -X POST "$BASE/auth/register" -d "{\"inviteCode\":\"$INV\",\"username\":\"demo-admin\",...}"
```

Document public registration URL, tenant username/password login, platform initial-password change, and script start commands. Do not print access tokens or raw passwords in normal smoke output.

- [ ] **Step 4: Run complete verification**

Run: `mvn test` in `xiaoda-backend-intelligence/Spring-AI`, then `npm run test && npm run build` in `smart-agent-frontend`, then the updated smoke script against a disposable local database.

Expected: all test suites and the smoke workflow pass.

- [ ] **Step 5: Commit**

```bash
git add xiaoda-backend-intelligence/scripts/smoke-auth.sh smart-agent-frontend/README.md xiaoda-backend-intelligence/Spring-AI/src/test/java/com/fansea/ai/it/InvitationOnboardingIntegrationTest.java
git commit -m "docs: verify invitation tenant onboarding"
```

## Plan Self-Review

- Spec coverage: Tasks 1–3 cover invitation lifecycle, first platform password change, username-only auth and tenant notes; Tasks 4–5 cover public registration, separated Tabs, forced redirect and layout; Task 6 covers end-to-end verification and user documentation.
- Placeholder scan: no unfinished work markers remain; every task specifies concrete files, commands and test behaviors.
- Type consistency: API names used by frontend tasks are defined in Tasks 2–3; `PlatformInvitation` is created before all invitation consumers; tenant profile/remark endpoints are named before their UI consumers.
