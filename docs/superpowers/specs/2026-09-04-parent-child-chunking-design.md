# 父子分块设计

## 目标

在现有 Markdown 分块工作区中新增 `PARENT_CHILD` 策略：以较小子块执行向量召回，以较大父块作为最终上下文，并提供可配置、可预览、可编辑子块、可确认建索引和可单块重建索引的完整前后端闭环。

该设计参考 Dify 的核心语义，但遵循 StarSeaKnow 现有的文件级异步状态机、源文件哈希校验、乐观锁、租户隔离和数据库权威校验。

## 范围

### 包含

- Markdown 文件新增父子分块策略。
- 段落父块与全文父块两种父块模式。
- 父块大小、子块大小与子块重叠 Token 配置。
- 父/子关系持久化及历史单层分块兼容迁移。
- 分层预览：父块展示上下文，子块展示检索单元。
- 子块编辑、删除与单块重建索引；空父块自动清理。
- 仅对子块建向量，命中子块后返回父块上下文。
- 同一父块的多个子块同时命中时按最高分命中去重。
- 后端与前端自动化测试、完整构建验证。

### 不包含

- 非 Markdown 文件解析器。
- 已完成文档在单层与父子模式间的在线无损转换；沿用现有限制，完成索引后不可直接全量重生成预览。
- 父块手工编辑。父块由源文件与策略生成，首版保持只读，以避免父文本与子检索单元失配。
- 单独的子块管理 API；继续使用当前文件级 `/chunks` API，并通过响应中的层级字段表达关系。
- 修改知识库级 `doc_form`。StarSeaKnow 的策略仍按文件选择，避免引入 Dify 数据集级约束。

## Dify 参考与本地取舍

Dify 的官方实现使用层级处理规则，父块支持 paragraph/full-doc 模式，子块持有父段引用；高质量索引仅写入子块向量。检索命中子块后，最终内容映射回父段。参考：

- [父子索引处理器](https://github.com/langgenius/dify/blob/main/api/core/rag/index_processor/processor/parent_child_index_processor.py)
- [处理规则实体](https://github.com/langgenius/dify/blob/main/api/core/rag/entities/processing_entities.py)
- [检索编排](https://github.com/langgenius/dify/blob/main/api/core/rag/retrieval/dataset_retrieval.py)
- [数据模型](https://github.com/langgenius/dify/blob/main/api/models/dataset.py)

StarSeaKnow 保留“子块命中、父块返回”这一核心语义。不同点是：继续使用现有 `document_chunk` 单表和文件级策略；父块首版只读；子块 overlap 复用现有服务端上下文增强器，不复制正文。

## 用户体验

### 策略设置

后端 `/chunk-strategies` 同时返回 `MARKDOWN_OPTIMIZED` 与 `PARENT_CHILD`。前端不再维护 `PARENT_CHILD` 禁用占位，而是完全由后端能力决定是否开放。

父子分块设置包含：

- `parentMode`：`PARAGRAPH` 或 `FULL_DOCUMENT`。
- `parentMaxTokens`：段落父块的最大 Token，范围 128–4096，默认 1024；全文模式保留该值但不用于切分。
- `childMaxTokens`：子块最大 Token，范围 32–512，默认 256。
- `childOverlapTokens`：相邻子块的补充上文 Token，范围 0–128 且必须小于 `childMaxTokens`，默认 32。

子块内部的目标大小与最小大小由 `childMaxTokens` 稳定推导，不额外暴露参数，减少配置噪音。段落模式要求 `parentMaxTokens >= childMaxTokens`。

全文模式显示上下文开销提示，因为一次命中可能返回整篇文档。

### 分层预览

预览区域在父子策略下显示：

- 汇总：父块数、子块数和实际检索单元数。
- 父块卡：父块序号、标题路径、Token 数、只读正文、子块数量、展开/收起状态。
- 子块卡：父块内序号、检索单元标签、正文编辑、删除和适用状态下的单块重建索引。

父子模式不显示子块的逐块“补充上文”开关；重叠值由策略统一控制，避免策略配置与逐块配置产生两套相互矛盾的来源。普通单层分块的现有逐块 overlap 交互保持不变。

### 确认与完成

确认对话框显示实际写入向量的单层块/子块数量，并在父子模式下说明父块只作为回答上下文。完成横幅使用“检索单元”计数，不把父块误报为向量条目。

## 数据模型与迁移

新增 Flyway `V16` 迁移，在 `document_chunk` 增加：

- `chunk_type SMALLINT NOT NULL DEFAULT 0`：`0=SINGLE`、`1=PARENT`、`2=CHILD`。
- `parent_chunk_id BIGINT NULL REFERENCES document_chunk(id) ON DELETE CASCADE`。
- `sibling_position INTEGER NOT NULL DEFAULT 0`：父块在文档内或子块在父块内的零基序号。

历史行迁移为 `SINGLE`，并令 `sibling_position=position`，因此历史向量和检索无需重建。保留现有 `(file_id, position)` 全局唯一约束；新预览按“父块、其子块、下一父块”的顺序分配全局 position。新增部分唯一索引保证同一父块内子块序号唯一，并新增父引用索引。

约束保证：只有 `CHILD` 必须有父引用，`SINGLE/PARENT` 不得有父引用。应用服务在持久化时进一步验证引用的是同租户、同知识库、同文件且类型为 `PARENT` 的当前预览父块。

`DocumentChunk` 增加层级字段与只读父上下文字段。列表查询通过 self join 返回 `parentPublicId`；召回权威查询通过 self join 一次取得父块内容、路径、定位与状态。

## 规划模型

引入与持久化无关的计划模型：

- `ChunkPlan`：有序 planned chunks、向量文本最大 Token。
- `PlannedChunk`：计划内 key、可选 parent key、`ChunkType`、同级序号、`ChunkDraft`、默认 overlap 设置。

`ChunkPlanningStrategy` 增加通用配置标准化与 `planConfigured` 能力。普通 Markdown 策略把原有 `ChunkPolicy` 转为单层 `ChunkPlan`，保持现有算法与公共单元测试 API。父子策略返回父块和子块的有序计划。

### 子块生成

父子策略先使用现有 Markdown 结构感知规划器生成子块。由 `childMaxTokens` 推导：

- `targetTokens = max(1, floor(childMaxTokens * 0.8))`
- `minTokens = max(1, floor(childMaxTokens * 0.25))`

这样继续复用标题边界、语义单元、列表/表格/代码块递归切分与 512 Token 硬上限。`childOverlapTokens > 0` 时，子块标记统一 overlap 策略；实际补充内容在索引阶段由现有 `DefaultChunkContextEnricher` 生成。

### 段落父块

按子块源顺序聚合父块，并遵循以下边界：

- 不跨标题路径。
- 不跨强结构起点（标题、主题分隔、同级标签）。
- 加入下一个子块会超过 `parentMaxTokens` 时结束当前父块。

父块正文为其子块正文按源顺序连接，定位范围由首尾子块合并。由于父边界与子边界对齐，每个子块只属于一个父块，删除或检索时关系确定。

### 全文父块

从解析后的 Markdown block 按源顺序重建全文父块，保留标题与结构标记；全部子块指向该父块。全文不参与 embedding Token 上限校验。前端必须显示上下文开销提示。

## 预览持久化

`ChunkPreviewService` 在异步提交前同步完成策略存在性与配置标准化，非法配置直接返回 422，不进入 `CHUNKING`。

Worker 在不可变源文件快照上解析与规划，并验证：

- 计划至少包含一个可向量化的 `SINGLE` 或 `CHILD`。
- key 唯一；子块 parent key 存在且指向更早的 `PARENT`。
- 所有正文非空。
- `SINGLE/CHILD` 的最终索引预览不超过计划的向量 Token 上限和全局 512 限制。
- `PARENT` 不接受 embedding 上限约束，但 Token 计数必须非负。

持久化事务先插入父块并记录数据库 ID/public ID，再插入子块。任一插入、快照比较或状态转换失败则整体回滚。

## 编辑与删除

- `SINGLE/CHILD` 可沿用现有正文编辑、乐观锁、向量失效和单块重建索引。
- `PARENT` 的编辑、删除与重建索引请求返回 422。
- `CHILD` 的人工编辑只调整召回文本；父块仍保存源文件生成的回答上下文。界面需要明确这一点，避免用户误以为编辑子块会改写父块。
- `CHILD` 编辑使用 `childMaxTokens` 校验，`SINGLE` 使用 `maxTokens`。
- 父子模式的 overlap 来源固定为策略快照；前端不暴露逐块开关。后端拒绝把 `CHILD` overlap 设置改成与策略不一致的值。
- 删除最后一个子块后，同一事务删除已经为空的父块。
- 确认前必须至少存在一个 `SINGLE/CHILD`，防止只剩父块的空索引。

## 向量化与状态机

确认时锁定全部 chunks 并将其置为 `INDEXING`，但只将 `SINGLE/CHILD` 放入 vector target 集合：

1. 上下文增强器只接收可向量化块，父块不会成为首个子块的“上文”。全局 position 中插入父块形成的间隔会自然阻止 overlap 跨父块传播。
2. Gateway 只删除/新增 target 的向量；metadata 增加 `chunkType`，子块增加 `parentChunkId`。
3. 外部向量写成功后，同一完成事务激活 target，并把本批次父块标为 `ACTIVE`（父块 `index_content` 保持空）。
4. 任一步失败时只清理 target 向量，但将本批次全部 `INDEXING` rows（包含父块）恢复为 `DRAFT`，随后把文件标为失败。

单块重建索引只允许 `SINGLE/CHILD`。文件完成判断仍要求全部 rows 为 `ACTIVE`，因此不会在父块尚未完成状态协调时提前完成。

## 检索语义

向量候选 ID 始终对应 `SINGLE/CHILD`。数据库权威查询继续校验租户、知识库、启用文件、chunk ACTIVE 状态和非空 `index_content`，并对 CHILD 额外要求父块存在且 ACTIVE。

映射规则：

- `SINGLE`：保持当前行为，返回自身 `index_content`、路径、定位、position 和 public ID。
- `CHILD`：相似度分数与 citation `chunkId` 保留命中子块；返回内容、路径、定位与 position 改用父块，并通过 `ChunkIndexContentBuilder` 把父标题路径加入父正文。

候选按向量结果顺序处理，以 `parentPublicId`（父子）或自身 `publicId`（单层）作为上下文去重键。同父多个子块命中时保留最高排序的命中，最终 `topK` 表示不同上下文数。

## API 兼容

`PreviewRequest.strategyConfig` 从固定 `ChunkPolicy` 改为 JSON map，以支持不同策略配置；普通策略字段与响应快照保持原样。

`ChunkResponse` 追加：

- `chunkType`: `SINGLE | PARENT | CHILD`
- `parentPublicId`: CHILD 的父块 UUID，其余为空
- `siblingPosition`: 同级零基序号

原字段不删除、不改名。历史/普通块返回 `SINGLE`，现有前端或外部调用方忽略新增字段时行为不变。

## 前端状态与错误处理

- 策略配置按 strategy code 独立缓存，切换策略不会污染另一策略的配置。
- `policySnapshot` 按 `processing.strategyCode` 恢复；非法或旧快照回退到该策略 descriptor 默认值。
- 父子列表由扁平响应正规化并分组；孤立 CHILD 不静默展示为正常数据，而显示数据异常提示并保留刷新入口。
- 409 继续触发当前乐观锁冲突恢复流程；422 展示具体策略或块约束错误；异步规划失败继续显示 retained chunks/retry 状态。
- 路由复用、轮询单例、保存阻塞和陈旧请求丢弃机制保持不变。

## 测试策略

### 后端

- 策略配置：默认值、范围、父/子大小关系、模式枚举。
- Planner：段落聚合、强边界、全文父块、父子覆盖与顺序、子块上限、overlap 标记。
- 迁移：历史行成为 SINGLE、FK/约束/索引有效。
- 预览：父先子后原子持久化、响应父 public ID、非法层级拒绝。
- 命令：父块只读、子块预算与 overlap 约束、最后子块删除清空父块。
- 向量：父块永不发送 gateway；成功激活父子；失败恢复父子；单块父重建被拒绝。
- 检索：子命中返回父内容、同父去重、单层兼容、租户/启用/ACTIVE 校验不回退。
- API：新策略 descriptor、Map 配置 422、层级响应字段。

### 前端

- 策略目录开放后端 `PARENT_CHILD`，API 允许提交。
- 配置组件默认值、校验、模式提示与事件。
- 工作区按策略恢复配置、切换隔离、提交正确 payload。
- 分层预览父子计数、分组、只读父块、可编辑子块与孤儿错误。
- 普通 ChunkCard 与完整 Markdown 工作流回归。

### 完成验证

- `cd server/Spring-AI && mvn -q test`
- `cd server/Spring-AI && mvn -q -DskipTests package`
- `cd web && npm test`
- `cd web && npm run build`
- `git diff --check`

## 安全与兼容性

- 所有新增查询继续显式包含 tenant、knowledge、file 范围。
- 父 self join 必须同时匹配 tenant、knowledge、file，不能只按 ID 连接。
- 向量 metadata 仅用于筛选与诊断；数据库仍是权限和内容权威来源。
- 不记录正文、请求体、token、API key 或其他敏感数据。
- migration 使用历史安全默认值，不删除现有 chunk/vector 数据。
