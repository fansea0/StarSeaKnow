# 租户用户后台重置密码 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为平台管理台增加租户用户重置密码权限，并让被重置用户首次登录后强制修改密码且只触发一次。

**Architecture:** 在 `app_user` 增加服务端控制的 `must_change_password` 状态；平台管理员通过 `/platform/tenants/{tenantId}/users` 管理用户并设置该状态。租户登录返回状态，后端认证切面和前端路由同时限制强制修改期间的访问，密码修改成功后原子清除状态。

**Tech Stack:** Spring Boot、MyBatis-Plus、Flyway、BCrypt、Vue 3、Pinia、Vue Router、Vitest。

**Spec:** `docs/superpowers/specs/2026-09-02-tenant-user-password-reset-design.md`

## Global Constraints

- 不生成、不返回、不展示一次性临时密码。
- 只有平台管理员可通过平台接口重置租户用户密码状态。
- 不返回密码明文或密码哈希。
- 必须保留平台管理员现有初始密码修改流程。
- 后端生产代码变更后运行 `cd server/Spring-AI && mvn -q -DskipTests package`。
- 前端生产代码变更后运行 `cd web && npm run build`。
- 完成验证后使用 Conventional Commits 中文提交信息。

### Task 1: 添加租户用户强制修改密码字段

**Files:**
- Create: `server/Spring-AI/src/main/resources/db/V9__tenant_user_password_reset.sql`
- Modify: `server/Spring-AI/src/main/java/com/starsea/ai/domain/AppUser.java`

- [ ] 写数据库/实体回归测试或最小验证，确认已有用户默认 `must_change_password=false`，字段可被 MyBatis 映射。
- [ ] 运行验证并确认在实现前失败或暴露字段不存在。
- [ ] 添加 Flyway migration：`app_user.must_change_password BOOLEAN NOT NULL DEFAULT FALSE`，使用当前迁移序列下一个版本 `V9`。
- [ ] 在 `AppUser` 增加 `Boolean mustChangePassword`。
- [ ] 运行后端编译验证。

### Task 2: 实现平台管理员查询和重置租户用户

**Files:**
- Create: `server/Spring-AI/src/main/java/com/starsea/platform/PlatformTenantUserController.java`
- Create: `server/Spring-AI/src/main/java/com/starsea/platform/PlatformTenantUserService.java`
- Modify: `server/Spring-AI/src/main/java/com/starsea/ai/mapper/AppUserMapper.java`
- Test: `server/Spring-AI/src/test/java/com/starsea/platform/PlatformTenantUserServiceTest.java`

- [ ] 先写失败测试：平台管理员可按租户查询安全用户视图；跨租户用户不能被重置；重置不生成或返回密码并设置标志。
- [ ] 运行测试确认失败原因是接口/服务尚未实现。
- [ ] 增加服务层的租户存在性、用户归属和并发更新校验。
- [ ] 增加 `GET /platform/tenants/{tenantId}/users` 与 `POST /platform/tenants/{tenantId}/users/{userId}/reset-password`，统一使用 `@RequireRole("platform_admin")`。
- [ ] 使用专用安全 DTO/Map，明确排除 `passwordHash`。
- [ ] 运行相关测试与后端构建。

### Task 3: 增加租户用户密码修改接口和强制访问控制

**Files:**
- Modify: `server/Spring-AI/src/main/java/com/starsea/ai/auth/AuthService.java`
- Modify: `server/Spring-AI/src/main/java/com/starsea/ai/auth/AuthController.java`
- Modify: `server/Spring-AI/src/main/java/com/starsea/ai/auth/AuthAspect.java`
- Modify: `server/Spring-AI/src/main/java/com/starsea/ai/auth/TenantContextInterceptor.java`
- Test: `server/Spring-AI/src/test/java/com/starsea/ai/auth/AuthServicePasswordResetTest.java`

- [ ] 先写失败测试：标记用户登录响应包含 `mustChangePassword=true`；当前密码/新密码/确认密码校验；成功后标志为 false；重复登录不再强制。
- [ ] 运行测试确认失败。
- [ ] 在 `AuthService` 增加租户用户密码修改方法，校验当前用户、当前密码、至少 8 位新密码和确认密码，并事务性更新哈希与标志。
- [ ] 在登录响应增加 `mustChangePassword`，新增 `/auth/change-password`。
- [ ] 在认证切面增加租户强制改密检查：允许密码修改方法和登出，拒绝其它租户接口；平台管理员逻辑保持不变。
- [ ] 确保登录、刷新、登出等公开/必要路径不会被强制检查错误拦截。
- [ ] 运行相关测试与后端构建。

### Task 4: 前端 store、路由和改密页面

**Files:**
- Modify: `web/src/stores/auth.js`
- Modify: `web/src/router/guards.js`
- Modify: `web/src/router/index.js`
- Modify: `web/src/views/ChangeInitialPassword.vue`
- Create: `web/src/views/__tests__/ChangePasswordFlow.test.js`

- [ ] 先写失败测试：登录响应标志为真时 store 保存状态；租户用户访问业务路由被重定向；改密成功后状态清除且不重复重定向。
- [ ] 运行 Vitest 确认失败。
- [ ] 增加租户会话的 `mustChangePassword` 保存和恢复逻辑，区分平台管理员/租户用户改密接口。
- [ ] 将改密页面根据用户类型提交 `/auth/change-password` 或 `/platform/auth/change-initial-password`，成功后按原 redirect 跳转。
- [ ] 更新路由元信息，使租户用户可访问改密页，平台管理员仍受平台权限保护。
- [ ] 运行相关 Vitest 测试。

### Task 5: 平台管理台用户列表与重置按钮

**Files:**
- Modify: `web/src/views/system/Tenants.vue`
- Create: `web/src/views/system/__tests__/TenantUsersReset.test.js`

- [ ] 先写失败测试：打开某租户用户列表请求正确 URL；点击重置发送平台接口；界面不展示临时密码并显示已标记提示。
- [ ] 运行 Vitest 确认失败。
- [ ] 在租户管理台增加用户查看区域/对话框、用户列表和重置操作。
- [ ] 删除现有平台流程中“重置后的临时密码”文案和展示逻辑；租户管理员旧页面如继续保留，需明确其与平台管理员流程的边界并禁止误称为强制流程。
- [ ] 运行前端完整测试和 `npm run build`。

### Task 6: 端到端验证、文档与提交

**Files:**
- Modify: `docs/QUICKSTART.md` 或相关认证文档（仅在现有说明过时才修改）

- [ ] 使用 disposable 本地数据库验证：普通用户登录不跳转；平台重置后原密码登录被强制改密；改密成功后再次登录不跳转。
- [ ] 验证非平台管理员调用平台接口返回 403，跨租户用户重置被拒绝，密码哈希未出现在响应。
- [ ] 运行 `git diff --check` 和 `git status --short`，确认没有生成物、密钥或无关改动。
- [ ] 运行后端 Maven package、前端 npm build 及相关测试，记录退出码和失败数。
- [ ] 提交单一连贯变更：`feat: 增加租户用户后台重置密码流程`。
- [ ] 提交后再次运行 `git status --short`，确认工作区只剩用户原有未提交内容或为空。
