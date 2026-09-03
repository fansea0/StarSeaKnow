# 向量模型评测工作台实施计划

**Goal:** 实现多模型配置、单题与批量检索、困难负例和同义问法评测、无答案校准、冻结验收与可导出报告。

**Architecture:** 新增 `com.starsea.ai.evaluation` 模块，使用租户隔离的 PostgreSQL JSONB 版本记录和独立向量集合。后台作业按模型执行，Vue 工作台展示不可变快照和逐题证据；模型指标与报告计算独立于 HTTP 与持久化。

**Tech Stack:** Java 17 / Spring Boot / Spring AI 1.0.0-M6 / PostgreSQL pgvector / Vue 3 / Element Plus / Vitest。

**Spec:** [设计目标](../specs/2026-09-04-embedding-evaluation-design.md)

## 全局约束

- 当前普通分支开发，不创建 worktree，不询问用户；完整实施后再审计目标是否完成。
- 保留现有业务模型与索引；当前配置作为只读评测基线，新模型按租户保存。
- 完整入模文本、模型身份、配置与问题标签冻结；原始余弦支持负值，未标注不当作无关。
- 每个模型对同一完整快照编码，失败不能悄悄删样本；生产验收必须具备完整证据。
- 实现后执行后端构建与测试、前端构建与测试、真实 Ollama 与 PostgreSQL 验证，以及浏览器检查。

## 实施任务

- [x] 1. 固定 API 合约；实现模型注册、Ollama 身份/能力验证、租户隔离存储和迁移。
  - 文件：`evaluation/EmbeddingModelRegistry.java`, `evaluation/OllamaEmbeddingGateway.java`, `evaluation/EvaluationRepository.java`, `db/V16__add_embedding_evaluation.sql`。
  - 验证：本地 HTTP fixture 检查 no-truncate、向量合法性、模型 digest；数据库检查版本冲突与跨租户访问。
- [x] 2. 实现纯指标引擎，包括余弦、未知标签、困难负例、意图组稳定性、阈值曲线、配对差异区间。
  - 文件：`evaluation/metrics/*` 与对应测试。
  - 从人工可计算的向量与标签样本先写失败测试，验证分母、边界、负值、同分和错误处理。
- [x] 3. 实现不可变语料、问题集版本、后台运行、取消/重试、向量缓存和生产链路独立复测。
  - 文件：`evaluation/EvaluationService.java`, `evaluation/EvaluationWorker.java`, `evaluation/EvaluationVectorStore.java`, `evaluation/EvaluationController.java`。
  - 验证：快照一致性、模型版本变化、全量编码、异步权限与失败恢复；真实双模型端到端。
- [x] 4. 实现向量模型 UI、知识库评测工作台、chunk 快捷入口、问题/标签编辑和批量报告。
  - 文件：`web/src/api/embeddingEvaluation.js`, `web/src/components/evaluation/*`, `ModelProviders.vue`, `KnowledgeDetail.vue`, `ChunkCard.vue`。
  - 验证：空/忙/失败/过期状态、数值 0 与缺失区别、模型选择、标注保存、校准/验收与导出交互。
- [x] 5. 实现验收门槛、报告导出和差异证据，整合前后端并完成审查。
  - 文件：`evaluation/EvaluationReports.java` 与对应测试，使用说明与真实验证记录。
  - 验证：HTML 转义、CSV 公式安全、不可变 JSON 清单、样本不足/未标注/失败无法通过、部署参数与基准比较。
- [x] 6. 运行全部必需验证，修复发现的问题，逐项审计设计目标并提交。

## 前后端合约（基础路径无 `/api` 前缀，复用现有 http）

所有普通响应沿用 `AjaxResult.success(data)`。导出直接返回文件正文。模型/数据集/运行 ID 使用字符串 UUID；当前配置模型 ID 为 `current`。权限为 `tenant_admin`。

### 模型

- `GET /embedding-evaluation/models` → `Model[]`。
- `POST /embedding-evaluation/models/test` → `Model`（未保存的探测结果）。
- `POST /embedding-evaluation/models`、`PUT /embedding-evaluation/models/{id}` → `Model`。
- `DELETE /embedding-evaluation/models/{id}`。
- 命令：`{displayName,baseUrl,modelName,queryPrefix,documentPrefix,options?,keepAlive?,revision?}`。
- Model：`{id,revision,displayName,baseUrl,modelName,queryPrefix,documentPrefix,dimensions,digest,quantization,ollamaVersion,source,readOnly,verifiedAt}`。

### 知识库内资源

根路径 `K = /knowledge/{knowledgeId}/embedding-evaluation`。

- `GET K/chunks` → `Chunk[]`：`{id,fileId,fileName,position,sectionPath,content,indexContent,contentHash,lockVersion}`。
- `POST K/snapshots`，`{scope:'ALL'|'SELECTED',chunkIds:[]}` → Snapshot：`{id,knowledgeId,scope,hash,createdAt,chunks:Chunk[]}`。
- `GET K/snapshots/{id}` → Snapshot。
- `GET K/datasets` → Dataset[]；`GET K/datasets/{id}?revision=N` → Dataset。
- `POST K/datasets`、`PUT K/datasets/{id}`，`{name,snapshotId,questions,frozen,revision?}` → Dataset。
- Dataset：`{id,name,revision,snapshotId,questions,frozen,createdAt}`。
- Question：`{id,query,intentGroup,category,split:'CALIBRATION'|'ACCEPTANCE',answerable,reviewed,labels:{[chunkId]:0|1|2},hardNegativeIds:[]}`。无标签为未知。
- `POST K/runs` → Run；`GET K/runs` → RunSummary[]；`GET K/runs/{id}` → Run。
- `POST K/runs/{id}/cancel` → Run；`POST K/runs/{id}/retry` → 新 Run。
- Run 命令：`{datasetId?,datasetRevision?,snapshotId?,questions?,modelIds,baselineModelId,phase:'QUICK'|'CALIBRATION'|'ACCEPTANCE'|'DELIVERY',topK:5,thresholds:{[modelId]:number},requirements:{minQuestions,minUnanswerable,hit5Min,evidenceRetentionMin,noAnswerFalsePositiveMax,p95MaxMs},retrievalMode:'EXACT'|'PRODUCTION'}`。
- QUICK 使用 snapshotId+questions；其他运行使用 datasetId+datasetRevision。验收使用 frozen 数据集的 ACCEPTANCE 分组，校准使用 CALIBRATION 分组。阈值缺失表示关闭过滤；验收需明确模型阈值。
- Run：`{id,knowledgeId,datasetId,datasetRevision,snapshotId,snapshotHash,scope,phase,retrievalMode,status,createdAt,finishedAt,questions,models:Model[],topK,thresholds,requirements,baselineModelId,modelResults:ModelResult[],progress:{completed,total,message},error?,verdict,verdictReasons:[],calibrationRunId?}`。
- ModelResult：`{modelId,status,dimensions,digest,selfSimilarity,preparedChunks,totalChunks,buildMs,queries:QueryResult[],metrics,calibration:[],comparison?,error?}`。
- QueryResult：`{questionId,query,status,error?,embeddingMs,searchMs,hits:Hit[],judgedScores:Hit[],metrics}`。
- Hit：`{chunkId,rank,score,fileName,content,indexContent,sectionPath,label?,hardNegative}`。精确 hits 至少保存 max(10,topK)；交付 hits 保存实际 topK，judgedScores 补充未返回的已标注块，仅供诊断。
- `GET K/runs/{id}/export?format=json|csv|html`，提供下载。

### 指标结果字段

`metrics`：`{questionCount,completedCount,failedCount,answerableCount,unanswerableCount,reviewedCount,unknownTopKCount,hit1,hit5,mrr10,ndcg5,hardNegativeWinRate,variantGroupHitRate,noAnswerFalsePositiveRate,evidenceRetentionRate,answerableEmptyRate,p50Ms,p95Ms}`。比例使用 0～1，无适用样本为 null，不显示成 0%。校准行包含 `threshold,evidenceRetentionRate,noAnswerFalsePositiveRate,answerableEmptyRate`。

运行状态 `QUEUED|RUNNING|COMPLETED|PARTIAL|FAILED|CANCELLED`；模型/题目状态 `PENDING|RUNNING|COMPLETED|FAILED|CANCELLED`。verdict 为 `DIAGNOSTIC|CALIBRATION|PASS|FAIL|INSUFFICIENT`。只有满足正式门槛的完整验收运行才能 PASS。

## 执行记录

- 2026-09-04：基于已批准设计开始实施；当前普通分支 `codex/embedding-evaluation-design`，初始工作区干净。
- 决策：模型基准默认精确检索，生产复测使用独立集合及实际候选处理参数；两者明确标注，避免误归因。

- 实现与审查完成。使用说明见 [本地向量模型评测](../../guides/embedding-evaluation.md)，实际观察与限制见 [验证记录](../verification/2026-09-04-embedding-evaluation.md)。
