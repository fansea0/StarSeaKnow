# 快速启动指南 (Quick Start)

> 本文档给出**从零启动整套项目**的完整步骤。如需快速命令速查，参考仓库根目录的 [`README.md`](../README.md)。

## 0. 项目结构

| 子项目 | 路径 | 技术栈 | 端口 |
| --- | --- | --- | --- |
| 后端服务 | `server/Spring-AI` | Spring Boot 3.3 + Java 17 + Spring AI 1.0 + MyBatis-Plus + PostgreSQL/pgvector | `8090` |
| 前端应用 | `web` | Vue 3 + Vite 6 + Element Plus + Axios | `5174`（`vite.config.js` 中显式设置） |

> 项目名：**星海知源 / StarSeaKnow**

---

## 1. 前置环境

| 工具 | 推荐版本 | 说明 |
| --- | --- | --- |
| JDK | 17+ | `java -version` 验证 |
| Maven | 3.8+ | `mvn -v` 验证 |
| Node.js | 18+ (推荐 20 LTS) | `node -v` 验证 |
| npm | 9+ | 文档使用 `npm` |
| PostgreSQL | 14+ | 需启用 `pgvector` 扩展 |
| Ollama（可选） | 最新 | 仅在使用本地 Embedding 时需要 |

> **重要**：向量维度必须保持一致。当前默认 `dimensions: 768`，对应 `mofanke/dmeta-embedding-zh` 或 `nomic-embed-text`。若切换 Embedding 模型，必须同步修改 `application.yml` 中的 `dimensions`。

---

## 2. 准备 PostgreSQL + pgvector

### 2.1 安装扩展（以 macOS/Homebrew 为例）

```bash
brew install postgresql@16 pgvector
brew services start postgresql@16
```

### 2.2 创建数据库与扩展

```bash
psql -U postgres <<'SQL'
CREATE DATABASE aichat;
\c aichat
CREATE EXTENSION IF NOT EXISTS vector;
SQL
```

> 如果使用 Docker，可一行启动：
> ```bash
> docker run -d --name pgvector -p 5432:5432 \
>   -e POSTGRES_PASSWORD=123456 -e POSTGRES_DB=aichat \
>   ankane/pgvector
> ```

---

## 3. 启动后端

### 3.1 初始化表结构

> ⚠️ **执行顺序很重要**：先跑 `rag.sql`（业务表），再让 Flyway 跑 `V1__init_auth_and_tenant.sql`（租户/认证）。

```bash
cd server/Spring-AI
psql -U postgres -d aichat -f src/main/resources/sql/rag.sql
```

Flyway 会在 Spring Boot 启动时自动执行 `src/main/resources/db/V1__init_auth_and_tenant.sql`，无需手动跑。

### 3.2 修改 `application.yml`

打开 `server/Spring-AI/src/main/resources/application.yml`，**至少修改以下 5 处**：

```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/aichat        # ← 数据库连接
    username: postgres                                  # ← DB 用户名
    password: 123456                                    # ← DB 密码
  ai:
    openai:
      api-key: sk-9fba15d576ec47529925b14e526478f0     # ← 你的 LLM Key（当前指向阿里 DashScope 百炼 OpenAI 兼容模式）
      base-url: https://dashscope.aliyuncs.com/compatible-mode
    ollama:
      embedding:
        model: mofanke/dmeta-embedding-zh               # ← Embedding 模型（与 dimensions 对应）

file:
  uploadPath: D:\桌面\Code\Spring-AI\...\src\main\resources\file\   # ← macOS/Linux 改成绝对路径
  outputPath: D:\桌面\Code\Spring-AI\...\src\main\resources\output\
```

**路径替换示例（macOS / Linux）**：
```yaml
file:
  uploadPath: /Users/yourname/projects/rag_agent/server/Spring-AI/src/main/resources/file/
  outputPath: /Users/yourname/projects/rag_agent/server/Spring-AI/src/main/resources/output/
```

**推荐配置**（避免硬编码个人路径，更适合团队协作）：用 `${user.dir}` 动态指向工程根目录：
```yaml
file:
  uploadPath: ${user.dir}/src/main/resources/file/
  outputPath: ${user.dir}/src/main/resources/output/
```

### 3.3 配置 Embedding 来源（可选二选一）

| 方案 | application.yml 设置 | 是否需要本地 Ollama | 适用 |
| --- | --- | --- | --- |
| **A. Ollama 本地**（默认） | `spring.ai.openai.embedding.enabled: false` + 启动 Ollama 拉取 `mofanke/dmeta-embedding-zh` | ✅ 是 | 离线 / 数据不出本机 |
| **B. 阿里 DashScope 远程 Embedding** | 改用 OpenAI 兼容的 `text-embedding-v3`（需把 `dimensions: 1024`，并启用 OpenAI embedding） | ❌ 否 | 网络可达、避免本地 GPU |

切换到方案 B 时务必同步 `spring.ai.vectorstore.pgvector.dimensions` 为 Embedding 模型实际输出维度。

### 3.4 启动 Ollama（仅方案 A）

```bash
# 安装：https://ollama.com/download
ollama pull mofanke/dmeta-embedding-zh
ollama serve   # 默认监听 11434
```

### 3.5 启动 Spring Boot

```bash
cd server/Spring-AI
mvn spring-boot:run
# 或 IDE 中直接运行 com.starsea.ai.SpringAiApplication
```

启动成功标志：日志出现 `Started SpringAiApplication in X.XXX seconds`，监听 `http://localhost:8090`。

---

## 4. 启动前端

```bash
cd web
npm install
npm run dev
```

打开浏览器访问 `http://127.0.0.1:5174`。

> 前端通过 `vite.config.js` 的 `proxy` 把 `/api` 转发到 `http://localhost:8090`，无需配置跨域。

---

## 5. 一键启动（推荐）

仓库根目录提供了统一启动脚本，会自动处理端口冲突：

```bash
# 同时启动后端 + 前端
./scripts/start.sh all

# 单独启动
./scripts/start.sh backend
./scripts/start.sh frontend
```

加上 `--keep-existing` 可以让脚本在端口被占用时**报错**而不是杀掉已有进程。完整脚本测试见 `scripts/test-start.sh`。

---

## 6. 启动前自检清单

- [ ] PostgreSQL 已启动，库 `aichat` 已创建
- [ ] `CREATE EXTENSION vector;` 成功
- [ ] 已执行 `rag.sql` 初始化业务表
- [ ] `application.yml` 中数据库账号密码已改
- [ ] `application.yml` 中 OpenAI `api-key` 已改为自己的 Key
- [ ] `file.uploadPath` / `file.outputPath` 已改为**当前机器可写**的绝对路径
- [ ] Embedding 模型与 `vectorstore.pgvector.dimensions` **匹配**
- [ ] （方案 A）Ollama 已启动且模型已 `pull`

---

## 7. 验证后端 API

```bash
# 登录（默认平台管理员 su，首次密码见后端启动日志 / 环境变量 PLATFORM_BOOTSTRAP_PASSWORD）
curl -X POST http://localhost:8090/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"su","password":"<your-password>"}'

# 健康检查（需要登录后的 token）
curl http://localhost:8090/actuator/health
```

完整接口列表见 [`server/README.md`](../server/README.md)。

---

## 8. 常见问题

**Q1 启动报 `extension "vector" is not available`**
A：未安装 pgvector。请按 §2.1 重装数据库或使用带 pgvector 的 Docker 镜像。

**Q2 启动报 `dimensions mismatch` / 向量维度错误**
A：数据库中的 `vector(768)` 与当前 Embedding 输出维度不一致。删除 `vector_store` 表后重启，或修改 `application.yml` 的 `dimensions` 与之对齐。

**Q3 上传文件后报 `Permission denied` / 找不到路径**
A：`file.uploadPath` 仍是 Windows 路径或不存在。请按 §3.2 改为绝对路径并 `mkdir -p`。

**Q4 前端请求后端跨域/连接失败**
A：后端必须启动在 `8090`；前端通过 vite proxy 自动转发 `/api`，不要在后端再开 CORS；若改了端口，**同时**修改 `vite.config.js` 的 `proxy.target` 和 `application.yml` 的 `server.port`。

**Q5 Flyway 报 `V1 migration failed`**
A：`V1__init_auth_and_tenant.sql` 会 `TRUNCATE` 并 `ALTER` 已存在的业务表，请确保先执行 `rag.sql` 创建这些表。

**Q6 找不到平台管理员账号**
A：首次启动时 `AuthBootstrapRunner` 会自动创建平台管理员（默认用户名 `su`，密码来自环境变量 `PLATFORM_BOOTSTRAP_PASSWORD`，默认为 `ChangeMe!123`，**生产环境必须覆盖**）。若已存在则不会重置，凭据沿用数据库中已有的。

---

## 9. 目录约定

- 后端业务代码：`server/Spring-AI/src/main/java/com/starsea/ai`
- 资源文件：`server/Spring-AI/src/main/resources/{file, mapper, db, sql}`
- 前端页面：`web/src/views/`
- 路由：`web/src/router/index.js`
- 跨端设计文档：`docs/`

启动后访问：

- 前端 UI：`http://127.0.0.1:5174`
- 后端 API：`http://localhost:8090`
