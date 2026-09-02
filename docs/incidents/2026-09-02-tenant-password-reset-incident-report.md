# 租户用户后台重置密码故障复盘与预防报告

- 日期：2026-09-02
- 影响模块：平台管理端租户用户管理、租户登录、强制修改密码
- 影响接口：
  - `GET /platform/tenants/{tenantId}/users`
  - `POST /platform/tenants/{tenantId}/users/{userId}/reset-password`
  - `POST /auth/login`
  - `POST /auth/change-password`
- 典型数据：租户 `974`，用户 `863/root`
- 当前状态：已修复并完成接口、数据库、后端测试和前端路由测试验证

## 1. 事故摘要

平台管理员需要对租户用户设置“下次登录必须修改密码”。实现过程中连续出现了以下症状：

1. 租户明明存在用户，但用户列表显示为 0。
2. 查看用户接口报 `NoResourceFoundException`。
3. 点击“要求改密”时报 `user not in tenant`。
4. 修复查询后又报 `user password reset changed concurrently`。
5. 手工把 `must_change_password` 改为 `true` 后，使用 `root` 登录仍未进入改密页。

这些现象不是五个彼此独立的问题，而是同一功能链路在接口部署、跨租户查询、跨租户更新和登录前置条件四个边界上分别暴露了缺陷。前期每次只修复当前失败点，没有用真实数据库完整走通“列表 → 标记 → 登录 → 改密 → 再登录”闭环，因此问题连续出现。

## 2. 用户影响

- 平台管理员无法可靠查看租户成员。
- 平台管理员无法给指定用户设置强制改密标志。
- 页面可能显示空列表，使管理员误判租户没有账号。
- 更新失败时返回了“并发修改”文案，但真实原因并不是并发，而是 SQL 没有命中目标行。
- 用户登录失败时无法进入改密流程，容易被误判为强制跳转功能失效。

本次未修改租户用户的现有密码，也未生成临时密码。

## 3. 事件链与证据

| 阶段 | 现象 | 直接证据 | 根因 |
| --- | --- | --- | --- |
| 接口部署 | `NoResourceFoundException` | 日志显示 `No static resource platform/tenants/974/users` | 当前运行分支/编译产物未包含新 Controller，8090 仍运行旧 class |
| 用户列表 | 租户 974 显示 0 个用户 | 数据库存在 `app_user.id=863, tenant_id=974, username=root` | 平台跨租户查询使用普通 `BaseMapper.selectList`，受到租户拦截器影响 |
| 用户校验 | `user not in tenant` | 平台登录成功后重置接口进入 Service，但按用户 ID 查不到目标 | 重置流程仍使用普通 `selectById`，跨租户读取未走平台专用查询 |
| 标记更新 | `user password reset changed concurrently` | 查询已经找到用户，但 `updateById` 返回 0 | 平台跨租户更新仍使用普通 `updateById`，租户拦截后 SQL 未命中；错误文案误导为并发 |
| 登录跳转 | 手工置 `true` 后未跳转 | 日志连续出现 `AUTH_LOGIN_FAIL ... user=root` 和 `401 bad credentials`，没有 `AUTH_LOGIN user=863` | 认证在读取并返回强制改密会话前已经失败；后台重置按设计不会改变原密码 |

## 4. 根因分析

### 4.1 主要技术根因：平台跨租户操作复用了租户业务 Mapper

`app_user` 是租户表，MyBatis-Plus 配置了租户行级拦截器。平台管理员虽然有跨租户权限，但最初的实现直接复用了以下通用方法：

- `selectList(...)`
- `selectById(...)`
- `updateById(...)`

这些方法适合当前租户上下文中的业务请求，不适合平台管理员跨租户操作。平台上下文没有普通租户 ID，通用 Mapper 经过租户拦截器后可能返回空结果或更新 0 行。

最终修复是为平台操作提供显式 Mapper 方法：

- `selectByTenantIdForPlatform(tenantId)`
- `selectByIdForPlatform(userId)`
- `markMustChangePasswordForPlatform(userId, tenantId)`

这些方法同时满足两个条件：

1. 使用 `@InterceptorIgnore(tenantLine = "true")` 明确绕过自动租户拦截。
2. SQL 自身必须包含显式的 `tenant_id` 条件，不能因为绕过拦截而失去租户边界。

更新操作使用 `user_id + tenant_id` 双条件：

```sql
UPDATE app_user
SET must_change_password = TRUE
WHERE id = :userId
  AND tenant_id = :tenantId;
```

这既支持平台跨租户管理，又防止把其他租户的用户错误标记。

### 4.2 测试根因：Mock 测试没有执行真实 MyBatis 拦截器

原单元测试 Mock 了 `AppUserMapper`，并人为规定 `selectList`、`selectById` 和 `updateById` 成功。因此测试验证了 Service 分支，却没有验证真实 SQL 在租户拦截器下的行为。

这解释了为什么代码单元测试通过，但接口连接 PostgreSQL 后连续失败。

### 4.3 运行环境根因：源码、分支、编译产物和运行进程不一致

排查期间出现过以下状态：

- 前端已经调用新接口，但当前后端分支没有对应 Controller。
- Git 已合并新代码，但 8090 仍由旧的 `target/classes` 启动。
- 多次启动后旧 Java 子进程仍占用 8090，新服务报 `Port 8090 was already in use`。
- 仅重启 Maven 命令，没有先确认实际监听端口的 Java PID 和工作目录。

因此“代码已经修复”不等于“正在提供请求的进程已经加载修复”。

### 4.4 认知根因：把登录失败误判为强制改密跳转失败

强制改密流程的顺序是：

1. 用户使用当前有效密码通过认证。
2. 后端在登录响应中返回 `mustChangePassword=true`。
3. 前端保存该状态并跳转 `/change-initial-password`。
4. 用户输入当前密码和新密码。
5. 修改成功后数据库标志清零，后续登录不再跳转。

如果第 1 步返回 `401 bad credentials`，后端不会签发登录会话，也不会进入强制改密页面。这是安全边界，不应为了显示改密页而绕过密码认证。

本次日志中 `root` 只有 `AUTH_LOGIN_FAIL`，没有 `AUTH_LOGIN user=863`。后台标记不会改变原密码，所以用户必须使用数据库当前密码对应的真实密码。若用户已经忘记原密码，需要单独设计经过身份验证的账号找回流程，不能把“管理员要求改密”当作免认证找回密码。

## 5. 已实施修复

| 提交 | 内容 |
| --- | --- |
| `eda500f` | 将租户用户后台重置功能合并到当前分支 |
| `c371288` | 平台用户列表改用跨租户专用查询 |
| `03c471e` | 平台重置前按用户 ID 使用跨租户专用查询 |
| `849034d` | 平台强制改密改用专用 UPDATE，并增加前端强制跳转回归测试 |

当前已验证：

- `POST /platform/tenants/974/users/863/reset-password` 返回 `code=200`。
- 数据库 `app_user.id=863` 的 `must_change_password=true`。
- 登录结果会携带数据库中的 `mustChangePassword`。
- 前端路由在该值为 `true` 时强制进入 `/change-initial-password`。
- 用户修改密码成功后标志清零，后续不再弹出或跳转。

## 6. 必须执行的预防措施

### 6.1 代码红线

平台模块操作租户表时，禁止直接使用以下通用 BaseMapper 方法：

```text
selectList
selectById
selectCount
updateById
deleteById
```

允许的模式是：

1. 建立语义明确的 `*ForPlatform` Mapper 方法。
2. 方法使用 `@InterceptorIgnore(tenantLine = "true")`。
3. SQL 必须显式包含 `tenant_id` 条件。
4. 更新和删除必须同时使用资源 ID 与租户 ID。
5. 返回给前端的 DTO 禁止包含 `password_hash`、刷新令牌等敏感字段。

代码审查时应搜索：

```bash
rg -n "selectList|selectById|selectCount|updateById|deleteById" \
  server/Spring-AI/src/main/java/com/starsea/platform
```

出现结果时必须逐条确认是否在访问租户表。

### 6.2 增加真实数据库集成测试

Mock 单元测试继续保留，但不能作为跨租户功能的唯一验证。应增加 PostgreSQL/Testcontainers 集成测试，至少覆盖：

1. 创建租户 A、租户 B，各创建一个用户。
2. 平台管理员查询租户 A，只返回 A 的用户。
3. 平台管理员标记 A 的用户，更新 1 行。
4. 使用 A 的用户 ID 和 B 的租户 ID，更新必须失败且不得修改数据。
5. 被标记用户使用正确当前密码登录，响应必须为 `mustChangePassword=true`。
6. 被标记用户访问普通业务接口必须被拦截。
7. 修改密码成功后标志必须为 `false`。
8. 再次登录不得跳转改密页。

建议把该测试作为后端 CI 必跑项。它能直接捕获 MyBatis 租户拦截器、SQL 和真实数据库之间的问题。

### 6.3 增加静态架构检查

建议增加 ArchUnit 测试或 CI 脚本：平台包中不得直接调用租户表 Mapper 的通用 CRUD 方法。检查失败时阻止合并。

仅依赖人工代码审查容易再次漏掉“查询已改、更新未改”这种半链路问题。

### 6.4 强制执行完整业务闭环验收

此功能以后不能只验证单个接口。发布前必须按以下顺序验收：

```text
平台登录
  → 查看目标租户用户
  → 设置要求改密
  → 再次查询并确认标志为 true
  → 租户用户用当前正确密码登录
  → 强制进入改密页
  → 修改密码
  → 普通业务页面可访问
  → 再次登录不再进入改密页
```

任何一步失败，都不能宣布功能完成。

### 6.5 统一服务启动与进程检查

开发环境重启后端前执行：

```bash
lsof -nP -iTCP:8090 -sTCP:LISTEN
```

确认 PID 和工作目录：

```bash
lsof -a -p <PID> -d cwd -Fn
ps -p <PID> -o pid,lstart,command
```

停止精确 PID 后再执行干净启动：

```bash
cd server/Spring-AI
mvn clean spring-boot:run
```

启动成功的最低标准：

- 日志明确出现 `Started SpringAiApplication`。
- 8090 只有一个监听进程。
- 进程工作目录属于当前仓库。
- Flyway 校验成功。
- 新接口未登录访问返回 401，而不是 404/`NoResourceFoundException`。

建议后续增加 `/actuator/info` 或构建信息接口，返回 Git commit、分支、构建时间，页面和日志均可快速确认当前运行版本。

### 6.6 发布前确认分支和工作区

每次构建和启动前检查：

```bash
git status --short --branch
git log -1 --oneline
```

不能只根据编辑器中的源码判断服务版本。必须确认当前分支包含目标提交，并通过干净构建重新生成 `target/classes`。

### 6.7 改进错误码和日志

`update == 0` 不应统一描述为“并发修改”。建议区分：

- `USER_NOT_IN_TENANT`：用户与租户不匹配。
- `PASSWORD_RESET_NOT_APPLIED`：标记未落库。
- `CONCURRENT_MODIFICATION`：只有存在版本号或明确并发证据时使用。

平台重置成功时增加结构化审计日志，但不能记录密码或密码哈希：

```text
PLATFORM_FORCE_PASSWORD_CHANGE adminId=<id> tenantId=<id> userId=<id> result=success
```

登录排查必须区分：

- `AUTH_LOGIN_FAIL`：密码认证失败，尚未进入强制改密流程。
- `AUTH_LOGIN` 且 `mustChangePassword=true`：认证成功，应由前端跳转。

## 7. 现场排查 Runbook

以后再次出现“租户用户查不到或无法重置”时，按顺序检查，不要跳步。

### 第一步：确认数据库事实

```sql
SELECT id, code, name, status
FROM tenant
WHERE id = :tenantId;

SELECT id, tenant_id, username, status, must_change_password
FROM app_user
WHERE tenant_id = :tenantId;
```

### 第二步：确认接口是否注册

未携带 Token 请求目标接口：

- 返回 401：路由已注册。
- 返回 404 或 `NoResourceFoundException`：运行版本没有该 Controller。

### 第三步：确认平台身份

平台请求必须使用 issuer 为平台端的 Token，角色必须是 `platform_admin`。不要混用租户用户 Token、过期 Token 或浏览器中残留的其他租户刷新会话。

### 第四步：确认列表响应

目标租户有数据但列表为空时，优先检查平台 Mapper 是否使用 `*ForPlatform` 方法和显式 `tenant_id` 条件。

### 第五步：确认更新实际落库

重置接口必须返回 200；随后重新查询用户列表或数据库，确认 `must_change_password=true`。不要只相信前端本地把状态改成了“已设置”。

### 第六步：区分登录失败和跳转失败

- 有 `401 bad credentials`：先解决当前密码不匹配问题，强制改密流程尚未开始。
- 登录响应 `mustChangePassword=false`：检查数据库读取和 Mapper 字段映射。
- 登录响应为 `true` 但页面不跳转：检查 Pinia 状态和 Vue Router guard。

### 第七步：确认改密闭环

改密成功后数据库必须变成 `false`。刷新会话和再次登录都不能再次触发改密页。

## 8. 后续行动项

| 优先级 | 行动项 | 验收标准 |
| --- | --- | --- |
| P0 | 增加真实 PostgreSQL 跨租户集成测试 | CI 可复现并阻止普通 BaseMapper 跨租户访问 |
| P0 | 增加平台包通用 CRUD 静态检查 | 平台代码直接调用租户表通用 CRUD 时构建失败 |
| P1 | 增加运行版本信息接口 | 可从 HTTP 响应确认 Git SHA 和构建时间 |
| P1 | 增加重置密码结构化审计日志 | 可按 tenantId/userId 追踪成功与失败原因 |
| P1 | 细分错误码和错误信息 | 不再把 SQL 未命中误报为并发修改 |
| P2 | 评估安全的忘记密码流程 | 不依赖临时密码，且具备可靠身份验证与审计 |

## 9. 结论

本次问题反复出现的核心不是单个 SQL 写错，而是平台跨租户能力缺少统一的数据访问边界，并且测试只覆盖了 Mock Service，没有覆盖真实租户拦截器和完整业务闭环。

以后应把平台跨租户访问视为独立安全边界：使用专用 Mapper、显式租户条件、真实数据库集成测试、静态架构检查和单进程干净部署。只有“列表、标记、登录、改密、清零、再次登录”全链路通过，才能判定该功能完成。
