# 小达智能体前端

Vue 3、Vite 和 Element Plus 实现的租户业务端与平台管理端。

## 启动开发环境

在仓库根目录运行：

```bash
./scripts/start.sh all
```

也可以只运行其中一个服务：

```bash
./scripts/start.sh backend
./scripts/start.sh frontend
```

默认地址：前端为 `http://127.0.0.1:5174`，后端为 `http://localhost:8090`。脚本会在启动前释放对应端口；若不希望结束已运行服务，请加 `--keep-existing`。

## 平台管理员与首次登录

首次部署且数据库中没有 `platform_admin` 记录时，后端会创建平台管理员。默认用户名是 `su`，默认初始密码是 `ChangeMe!123`；这两个值都可在部署时通过环境变量 `PLATFORM_BOOTSTRAP_USERNAME` 和 `PLATFORM_BOOTSTRAP_PASSWORD` 覆盖。生产部署必须覆盖默认密码。

初始密码只用于创建首个管理员，且不会写入日志或任何接口响应。首次登录后，系统会强制跳转至“修改初始密码”；完成前不能访问平台管理功能。

1. 打开 `/login`，选择“平台管理员”，使用部署方配置的凭据登录。
2. 完成首次改密后，在“邀请码管理”创建设置了生效时间和到期时间的邀请码。
3. 邀请码可立即禁用，成功注册后只能使用一次。
4. 成功注册的租户显示在独立的“租户管理”页面；平台管理员可禁用租户并维护内部备注。

> 若数据库已经存在平台管理员，启动时不会重置其凭据。请使用既有凭据或按部署方的恢复流程处理。

## 租户注册与登录

外部租户管理员打开 `/register`，输入且仅输入：邀请码、用户名、密码和确认密码。注册成功后系统会创建独立租户与该租户的初始管理员，并直接登录。

之后租户用户在 `/login` 选择“租户用户”，仅使用用户名和密码登录，不需要填写租户代码。租户管理员可在租户资料页修改默认的租户名称；不同租户的数据互相隔离。

## 验证

```bash
cd smart-agent-frontend
npm run test
npm run build
```

完整后端/前端启动与邀请码开户烟雾流程见仓库根目录的 `xiaoda-backend-intelligence/scripts/smoke-auth.sh`。为避免泄露凭据，脚本要求通过环境变量提供平台管理员密码，并且不会输出密码或访问令牌：

```bash
BASE=http://localhost:8090 SU_PWD='已知平台密码' \\
  ../xiaoda-backend-intelligence/scripts/smoke-auth.sh
```

如果该账号仍处于首次改密状态，还需要一次性提供新的平台管理员密码：

```bash
BASE=http://localhost:8090 SU_PWD='初始密码' SU_NEW_PWD='新的强密码' \\
  ../xiaoda-backend-intelligence/scripts/smoke-auth.sh
```
