# 快速启动指南 (Quick Start)

本仓库包含两个子项目：

| 子项目 | 路径 | 技术栈 | 端口 |
| --- | --- | --- | --- |
| 后端服务 | `xiaoda-backend-intelligence/Spring-AI` | Spring Boot 3.3.10 + Java 17 + Spring AI 1.0.0-M6 + MyBatis Plus + PostgreSQL/pgvector | `8080` |
| 前端应用 | `smart-agent-frontend` | Vue 3 + Vite 6 + Element Plus + Axios | `5173` (Vite 默认) |

---

## 0. 前置环境

| 工具 | 推荐版本 | 说明 |
| --- | --- | --- |
| JDK | 17+ | `java -version` 验证 |
| Maven | 3.8+ | `mvn -v` 验证 |
| Node.js | 18+ (推荐 20 LTS) | `node -v` 验证 |
| pnpm / npm | 任一 | 文档使用 `npm` |
| PostgreSQL | 14+ | 需启用 `pgvector` 扩展 |
| Ollama（可选） | 最新 | 仅在使用本地 Embedding 时需要 |

> **重要**：向量维度必须保持一致。当前默认 `dimensions: 768`，对应 `mofanke/dmeta-embedding-zh` 或 `nomic-embed-text`。若切换 Embedding 模型，必须同步修改 `application.yml` 中的 `dimensions`。

---

## 1. 准备 PostgreSQL + pgvector

### 1.1 安装扩展（以 macOS/Homebrew 为例）

```bash
brew install postgresql@16 pgvector
brew services start postgresql@16
```

### 1.2 创建数据库与扩展

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

## 2. 启动后端

### 2.1 初始化表结构

> ⚠️ **执行顺序很重要**：先跑 `rag.sql`（业务表），再让 Flyway 跑 `V1__init_auth_and_tenant.sql`（租户/认证）。

```bash
cd xiaoda-backend-intelligence/Spring-AI
psql -U postgres -d aichat -f src/main/resources/sql/rag.sql
```

Flyway 会在 Spring Boot 启动时自动执行 `src/main/resources/db/V1__init_auth_and_tenant.sql`，无需手动跑。

### 2.2 修改 `application.yml`

打开 `src/main/resources/application.yml`，**至少修改以下 5 处**：

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
  uploadPath: /Users/yourname/projects/rag_agent/xiaoda-backend-intelligence/Spring-AI/src/main/resources/file/
  outputPath: /Users/yourname/projects/rag_agent/xiaoda-backend-intelligence/Spring-AI/src/main/resources/output/
```

**推荐配置**（避免硬编码个人路径，更适合团队协作）：用 `${user.dir}` 动态指向工程根目录：
```yaml
file:
  uploadPath: ${user.dir}/src/main/resources/file/
  outputPath: ${user.dir}/src/main/resources/output/
```

### 2.3 配置 Embedding 来源（可选二选一）

| 方案 | application.yml 设置 | 是否需要本地 Ollama | 适用 |
| --- | --- | --- | --- |
| **A. Ollama 本地**（默认） | `spring.ai.openai.embedding.enabled: false` + 启动 Ollama 拉取 `mofanke/dmeta-embedding-zh` | ✅ 是 | 离线 / 数据不出本机 |
| **B. 阿里 DashScope 远程 Embedding** | 改用 OpenAI 兼容的 `text-embedding-v3`（需把 `dimensions: 1024`，并启用 OpenAI embedding） | ❌ 否 | 网络可达、避免本地 GPU |

切换到方案 B 时务必同步 `spring.ai.vectorstore.pgvector.dimensions` 为 Embedding 模型实际输出维度。

### 2.4 启动 Ollama（仅方案 A）

```bash
# 安装：https://ollama.com/download
ollama pull mofanke/dmeta-embedding-zh
ollama serve   # 默认监听 11434
```

### 2.5 启动 Spring Boot

```bash
cd xiaoda-backend-intelligence/Spring-AI
mvn spring-boot:run
# 或 IDE 中直接运行 SpringAiApplication
```

启动成功标志：日志出现 `Started SpringAiApplication in X.XXX seconds`，监听 `http://localhost:8080`。

---

## 3. 启动前端

```bash
cd smart-agent-frontend
npm install
npm run dev
```

打开浏览器访问 Vite 给出的本地地址（默认 `http://localhost:5173`）。

> 前端代码中已硬编码 `http://localhost:8080` 作为后端地址（见 `src/views/*.vue`）。如需修改：
> ```bash
> grep -rln "localhost:8080" src/
> ```
> 替换为你自己的后端地址即可。

---

## 4. 配置清单速查（启动前必须自检）

- [ ] PostgreSQL 已启动，库 `aichat` 已创建
- [ ] `CREATE EXTENSION vector;` 成功
- [ ] 已执行 `rag.sql` 初始化业务表
- [ ] `application.yml` 中数据库账号密码已改
- [ ] `application.yml` 中 OpenAI `api-key` 已改为自己的 Key
- [ ] `file.uploadPath` / `file.outputPath` 已改为**当前机器可写**的绝对路径
- [ ] Embedding 模型与 `vectorstore.pgvector.dimensions` **匹配**
- [ ] （方案 A）Ollama 已启动且模型已 `pull`

---

## 5. 验证后端 API

```bash
# 健康检查
curl http://localhost:8080/knowledge/list/vo

# 进入对话（流式）
curl -N -X POST http://localhost:8080/ai/chat \
  -H "Content-Type: application/json" \
  -d '{"chatId":"demo","message":"你好"}'
```

完整接口列表见 `xiaoda-backend-intelligence/README.md`。

---

## 6. 常见问题

**Q1 启动报 `extension "vector" is not available`**
A：未安装 pgvector。请按 §1.1 重装数据库或使用带 pgvector 的 Docker 镜像。

**Q2 启动报 `dimensions mismatch` / 向量维度错误**
A：数据库中的 `vector(768)` 与当前 Embedding 输出维度不一致。删除 `vector_store` 表后重启，或修改 `application.yml` 的 `dimensions` 与之对齐。

**Q3 上传文件后报 `Permission denied` / 找不到路径**
A：`file.uploadPath` 仍是 Windows 路径或不存在。请按 §2.2 改为绝对路径并 `mkdir -p`。

**Q4 前端请求后端跨域/连接失败**
A：确认后端已启动在 `8080`；`WebMvcConfig` 已放行 `*` 跨域；若改了端口，全局替换前端 `localhost:8080`。

**Q5 Flyway 报 `V1 migration failed`**
A：`V1__init_auth_and_tenant.sql` 会 `TRUNCATE` 并 `ALTER` 已存在的业务表，请确保先执行 `rag.sql` 创建这些表。

**Q6 `AuthBootstrapRunner` 不存在导致无法登录**
A：当前仓库尚未实现 `AuthBootstrapRunner`（V1 注释中提及），需要后续补全或在 `platform_admin` / `app_user` 表里手动插入管理员账号后重启。

---

## 7. 一键启动脚本（参考）

把以下内容保存为 `start.sh`（macOS/Linux）：

```bash
#!/usr/bin/env bash
set -e
cd "$(dirname "$0")"

# 后端
cd xiaoda-backend-intelligence/Spring-AI
mvn -q spring-boot:run &
BACKEND_PID=$!

# 前端
cd ../../smart-agent-frontend
npm install
npm run dev &
FRONTEND_PID=$!

trap "kill $BACKEND_PID $FRONTEND_PID" EXIT
wait
```

```bash
chmod +x start.sh && ./start.sh
```

---

## 8. 目录约定

- 后端业务代码：`xiaoda-backend-intelligence/Spring-AI/src/main/java/com/fansea/ai`
- 资源文件：`xiaoda-backend-intelligence/Spring-AI/src/main/resources/{file, mapper, db, sql}`
- 前端页面：`smart-agent-frontend/src/views/`
- 路由：`smart-agent-frontend/src/router/index.js`

启动后访问：

- 前端 UI：`http://localhost:5173`
- 后端 API：`http://localhost:8080`
