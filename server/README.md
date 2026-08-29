

# 星海知源 · 后端服务

> StarSeaKnow / StarSea Server

这是「星海知源」（StarSeaKnow）项目的后端服务，基于 Spring Boot + Spring AI 构建，结合 RAG、文件和知识库管理，提供智能对话和数据处理能力。

> 📦 顶层文档与启动方式见仓库根目录的 [`README.md`](../README.md)。本文件只覆盖后端特有的内容。

## 🧩 功能特性

- **AI 聊天功能**：支持通过 `/ai/chat` 进行流式对话，结合 `Flux<String>` 实现响应式输出。
- **RAG 搜索**：通过文件与知识库的结合，实现基于上下文的语义搜索。
- **文件管理**：支持文件上传、删除、状态更新，以及文件嵌入处理。
- **知识库管理**：提供知识库的添加、更新、删除和查询接口，并支持缓存。
- **智能代理管理**：提供智能代理的增删改查、关联知识库等操作。
- **对话历史记录**：支持基于 `chatId` 的对话历史记录和检索。
- **统一响应封装**：通过 `AjaxResult` 类封装统一的 API 响应格式。

## 📁 项目结构简述

- `SpringAiApplication.java`: Spring Boot 主启动类（包路径 `com.starsea.ai.SpringAiApplication`，Maven artifactId `star-sea-server`）。
- `config/`: 包含全局配置、异常处理、缓存和跨域配置。
- `controller/`: 各功能的 REST API 控制器，包括 AI 聊天、文件、知识库、代理等。
- `domain/`: 数据模型定义，如 `Agent`, `File`, `Knowledge` 等。
- `domain/dto/`: 数据传输对象，如 `AjaxResult`。
- `domain/vo/`: 视图对象，如 `KnowledgeVo`, `MessageVo`。
- `mapper/`: MyBatis Plus 的 Mapper 接口。
- `service/`: 各模块的业务逻辑接口和实现。
- `tool/`: 包含 AI 工具类，如 `ServerTool`。
- `util/`: 通用工具类，如 `FileUtil`。
- `history/`: 聊天记录管理模块。

## ⚙️ 配置文件

- `application.yml`: Spring Boot 配置文件，包含数据库连接、文件路径、AI 模型等配置。
- `rag.sql`: 数据库初始化脚本。

## 🚀 快速启动

1. **安装依赖**：确保你已经安装 Java 17+、Maven，并配置好 PostgreSQL 或其他数据库。
2. **导入数据库**：执行 `rag.sql` 初始化数据库。
3. **修改配置**：在 `application.yml` 中配置数据库、AI 模型和文件路径。
4. **启动项目**：运行 `SpringAiApplication` 启动服务。

## 📦 接口文档（简要）

| 模块 | 接口 | 功能 |
|------|------|------|
| `/ai/chat` | `POST` | AI 流式对话 |
| `/ai/history/{chatId}` | `GET` | 获取对话历史 |
| `/file/upload` | `POST` | 文件上传 |
| `/knowledge/add` | `POST` | 添加知识库 |
| `/agent/add` | `POST` | 添加智能代理 |
| `/rag/file` | `GET` | RAG 文件搜索 |
| `/tool/convmd` | `POST` | 创建 Markdown 文件 |

## 📝 依赖库

- Spring Boot
- Spring WebFlux（支持响应式流）
- MyBatis Plus
- Lombok
- Spring Cache
- PostgreSQL JDBC
- SkyWalking（用于分布式追踪）
- Spring AI（用于 AI 对话）

## 📎 开源协议

本项目使用 Apache-2.0 协议。请查看 [LICENSE](LICENSE) 文件了解更多。