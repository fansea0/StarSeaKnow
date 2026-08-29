# 星海知源 · StarSeaKnow

> 在海量信息中追溯答案的源头 —— 基于检索增强生成（RAG）的智能问答平台。

## 项目结构

本仓库是一个 monorepo，统一管理前后端代码：

```
.
├── server/          # 后端：Spring Boot + Spring AI（Java 17）
│   └── Spring-AI/   # Maven 项目（artifactId: star-sea-server）
├── web/             # 前端：Vue 3 + Vite + Element Plus
├── docs/            # 跨端设计与方案文档
├── scripts/         # 仓库级脚本
└── AGENTS.md        # 协作规范（命名、commit、CI）
```

| 端 | 目录 | 技术栈 | 端口 |
|----|------|--------|------|
| 后端 | `server/Spring-AI` | Spring Boot 3.3 + Spring AI 1.0 + MyBatis-Plus + PostgreSQL/pgvector | `8090` |
| 前端 | `web` | Vue 3 + Vite 6 + Element Plus + Pinia + Vue Router | `5173` |

## 快速开始

### 1. 启动后端

```bash
cd server/Spring-AI

# 准备 PostgreSQL + pgvector，参考 application.yml 中的 datasource 配置
# 启动应用
mvn spring-boot:run
```

应用启动后访问：`http://localhost:8090`

### 2. 启动前端

```bash
cd web
npm install
npm run dev
```

前端默认代理 `/api` 到 `http://localhost:8090`（见 `web/vite.config.js`）。

## 常用命令

| 任务 | 命令 |
|------|------|
| 后端编译 | `cd server/Spring-AI && mvn compile` |
| 后端测试 | `cd server/Spring-AI && mvn test` |
| 后端启动 | `cd server/Spring-AI && mvn spring-boot:run` |
| 前端依赖安装 | `cd web && npm install` |
| 前端开发 | `cd web && npm run dev` |
| 前端构建 | `cd web && npm run build` |
| 前端测试 | `cd web && npm test` |

## 文档

- 设计与方案：`docs/`
- 后端内部文档：`server/Spring-AI/docs/`
- 协作规范：`AGENTS.md`
