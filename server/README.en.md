

# StarSeaKnow · Backend Service

> 星海知源 / StarSea Server

This is the backend service of the **StarSeaKnow (星海知源)** project, a Spring Boot based service that integrates AI, RAG, file and knowledge base management to provide intelligent conversation and data processing capabilities.

> 📦 For the top-level documentation and unified startup commands, see the repository root [`README.md`](../README.md). This file only covers backend-specific details.

## 🧩 Features

- **AI Chat Functionality**: Supports streaming conversations via `/ai/chat`, using `Flux<String>` to achieve reactive output.
- **RAG Search**: Implements context-aware semantic search by combining files and the knowledge base.
- **File Management**: Supports file upload, deletion, status updates, and embedding processing.
- **Knowledge Base Management**: Provides APIs for adding, updating, deleting, and querying knowledge bases, along with caching support.
- **Smart Agent Management**: Supports CRUD operations for intelligent agents and knowledge base association.
- **Chat History Records**: Supports conversation history recording and retrieval based on `chatId`.
- **Unified Response Wrapper**: Standardizes the API response format using the `AjaxResult` class.

## 📁 Project Structure Overview

- `SpringAiApplication.java`: Main Spring Boot startup class (package `com.starsea.ai.SpringAiApplication`, Maven artifactId `star-sea-server`).
- `config/`: Contains global configurations, exception handling, cache, and CORS settings.
- `controller/`: REST API controllers for various functionalities, including AI chat, file, knowledge base, and agent management.
- `domain/`: Data model definitions, such as `Agent`, `File`, `Knowledge`, etc.
- `domain/dto/`: Data Transfer Objects (DTOs), such as `AjaxResult`.
- `domain/vo/`: View Objects (VOs), such as `KnowledgeVo`, `MessageVo`.
- `mapper/`: MyBatis Plus Mapper interfaces.
- `service/`: Business logic interfaces and implementations for each module.
- `tool/`: Contains AI utility classes, such as `ServerTool`.
- `util/`: General utility classes, such as `FileUtil`.
- `history/`: Chat history management module.

## ⚙️ Configuration Files

- `application.yml`: Spring Boot configuration file, including database connection, file paths, AI model settings, etc.
- `rag.sql`: Database initialization script.

## 🚀 Quick Start

1. **Install Dependencies**: Ensure you have Java 17+, Maven installed, and PostgreSQL or another database configured.
2. **Import Database**: Execute `rag.sql` to initialize the database.
3. **Modify Configuration**: Configure the database, AI model, and file paths in `application.yml`.
4. **Start the Project**: Run `SpringAiApplication` to launch the service.

## 📦 API Documentation (Brief)

| Module | Endpoint | Functionality |
|-------|---------|--------------|
| `/ai/chat` | `POST` | AI Streaming Chat |
| `/ai/history/{chatId}` | `GET` | Retrieve Chat History |
| `/file/upload` | `POST` | File Upload |
| `/knowledge/add` | `POST` | Add Knowledge Base |
| `/agent/add` | `POST` | Add Intelligent Agent |
| `/rag/file` | `GET` | RAG File Search |
| `/tool/convmd` | `POST` | Create Markdown File |

## 📝 Dependencies

- Spring Boot
- Spring WebFlux (for reactive streams)
- MyBatis Plus
- Lombok
- Spring Cache
- PostgreSQL JDBC
- SkyWalking (for distributed tracing)
- Spring AI (for AI conversations)

## 📎 Open Source License

This project uses the Apache-2.0 license. Please check the [LICENSE](LICENSE) file for more information.