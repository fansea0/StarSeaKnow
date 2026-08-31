# Markdown 自适应分块与人工调整设计

日期：2026-08-31
状态：已完成方案对齐，待实现计划评审

## 1. 背景

当前 Markdown 文件在 `PgVectorRagServiceImpl` 中经过 `MarkdownDocumentReader` 和默认 `TokenTextSplitter` 后直接写入向量库。现状有三个主要问题：

1. 分块策略固定，主要依据长度，不能稳定保留 Markdown 的标题层级、列表、表格、代码块等结构。
2. 分块结果不可见，用户无法在向量化前发现标题丢失、上下文错位、内容归属错误等问题。
3. 分块和向量化是一次性过程，用户不能保存调整结果并在下次继续。

本次只改造 Markdown，PDF 继续使用现有链路，后续可复用本设计中的流程管理和 Chunk 管理能力。

## 2. 目标与非目标

### 2.1 目标

- 自动感知 Markdown 结构并生成质量更高的 Chunk。
- 标题不单独成为 Chunk，而是作为正文 Chunk 的结构上下文。
- 所有 Markdown 文件必须先生成分块预览，再由用户确认向量化。
- 预览页只提供智能分段，用户可以编辑正文和删除 Chunk。
- 编辑结果实时保存，用户离开后可以继续处理。
- 用户确认后，向量输入固定由“文档名 + 完整标题路径 + 合法 overlap + 编辑后的正文”组成。
- 已向量化的单个 Chunk 支持编辑、删除和重新向量化。
- 分块、编辑、向量化和失败重试具有明确、可恢复的状态。
- 检索结果能通过 Chunk 定位到文件、标题路径和源文件位置。

### 2.2 非目标

- 本期不实现 PDF 自适应分块。
- 不提供高级分段、手工设置分隔符或公开分块参数。
- 不提供合并、拆分、拖动边界等交互；用户通过直接编辑相邻 Chunk 完成内容归位。
- 不提供“分段标题作为关联问题”开关；标题默认参与向量计算。
- 不提供 Chunk 启用/停用。
- 不保存 Chunk 修改历史、历史版本或回滚记录。
- 不新增用于跨知识库复用物理文件的 `document` 表。同一个物理文件上传到不同知识库时按不同文件处理。
- 不针对问答文档编写专用解析器。

## 3. 总体架构

新增四个职责清晰的层次：

```text
Markdown 原文件
    │
    ▼
结构解析层 MarkdownStructureParser
    │  MarkdownBlock + 标题树
    ▼
自适应规划层 AdaptiveChunkPlanner
    │  ChunkDraft
    ▼
持久化预览层 document_chunk（DRAFT）
    │  用户编辑 / 删除 / 下次继续
    ▼
索引构建层 ChunkIndexContentBuilder
    │  文档名 + 标题路径 + overlap + 编辑后正文
    ▼
向量库 + document_chunk（ACTIVE）
```

各组件职责如下：

- `MarkdownStructureParser`：只负责将 Markdown 解析成带源位置的结构化块，不决定最终 Chunk 边界。
- `MarkdownSemanticUnitBuilder`：将必须保持在一起的相邻块组合成语义单元，例如“引导段 + 列表”。
- `AdaptiveChunkPlanner`：根据结构边界、长度和不可拆约束规划 Chunk。
- `ChunkPreviewService`：生成预览并将当前 Chunk 写入数据库。
- `FileProcessingService`：集中维护文件处理状态，禁止 Controller 或异步任务随意写状态值。
- `ChunkCommandService`：处理 Chunk 编辑、删除、确认和单 Chunk 重新向量化。
- `ChunkIndexContentBuilder`：每次向量化前，根据数据库中的最新正文重新生成实际索引文本。
- `ChunkVectorService`：封装向量写入、删除、补偿和状态更新。

本期不再引入 `file_chunk`。解析产生的 `MarkdownBlock`、语义单元和 `ChunkDraft` 都是内存中的中间模型；一旦预览生成，直接持久化为 `document_chunk`。

## 4. Markdown 结构感知

### 4.1 解析方式

使用 commonmark-java AST 解析 Markdown，解析器明确开启 `IncludeSourceSpans.BLOCKS_AND_INLINES`，并加载 GFM table 扩展。对应实现依赖为 `org.commonmark:commonmark` 和 `org.commonmark:commonmark-ext-gfm-tables`，两个依赖使用同一版本。不能通过正则逐行切分 Markdown，因为正则难以正确处理嵌套列表、围栏代码块、引用、HTML 块和软换行。

解析结果统一转换为内部模型：

```java
record MarkdownBlock(
    String blockId,
    BlockType type,
    String rawText,
    String plainText,
    int startOffset,
    int endOffset,
    int startLine,
    int endLine,
    Integer headingLevel,
    List<String> sectionPath,
    int tokenCount,
    Map<String, Object> attributes
) {}
```

支持的块类型：

- 标题 `HEADING`
- 普通段落 `PARAGRAPH`
- 有序/无序列表 `ORDERED_LIST`、`UNORDERED_LIST`
- 引用 `BLOCK_QUOTE`
- 围栏/缩进代码块 `FENCED_CODE`、`INDENTED_CODE`
- 表格 `TABLE`
- 分隔线 `THEMATIC_BREAK`
- HTML 块 `HTML_BLOCK`

`rawText` 用于保存 Markdown 原貌和源位置；`plainText` 用于长度估算、边界特征和索引正文清洗。最终预览正文以保留可读结构为原则，不将列表、代码或表格压平成连续文本。

### 4.2 标题树与完整标题路径

解析器维护一个标题栈：

1. 遇到新标题时，弹出所有层级大于或等于当前标题层级的标题。
2. 将当前标题压栈。
3. 后续非标题块继承当前标题栈，形成 `sectionPath`。

示例：

```markdown
# 科大百事通
## 校园网
### 如何重置密码
正文……
```

正文块的完整标题路径为：

```text
科大百事通 > 校园网 > 如何重置密码
```

标题本身默认不生成独立 Chunk。标题只定义后续内容的上下文；只有标题而没有正文的空章节不会产生 Chunk。这样可避免向量库中出现只有“校园网”“常见问题”等无法回答问题的碎片。

### 4.3 通用结构特征，而非问答专用解析

系统不识别“问题/答案”这一业务类型，也不依赖固定的 `Q:`、`A:` 格式。它只识别可复用的结构特征：

- 标题层级变化；
- 同级编号或标签段落，例如 `Q1`、`1.`、`（一）`、`第一条`、`步骤 1`；
- 列表、表格、代码、引用等完整容器；
- 段落结束和句子结束；
- 引导语与后续结构块之间的关系。

因此，格式不同的 FAQ、制度条款、操作手册和普通说明文档可以复用同一套解析和规划过程。

## 5. 自适应 Chunk 规划

### 5.1 先生成语义单元

解析块不能直接按长度装箱。系统先将紧密相关的相邻块组合成不可随意拆开的 `SemanticUnit`：

- 标题与标题后的首个正文块建立强关联，但标题仅进入路径，不进入正文所有权。
- “如下/包括/步骤如下”等引导段与紧随其后的列表、表格或代码块组合。
- 表格说明或表名与表格组合。
- 代码说明与紧随其后的代码块组合。
- 一个列表或一个引用块优先保持完整。
- 同级编号/标签段落各自成为候选语义单元，避免不同条目互相污染。

如果某个语义单元自身超过最大长度，再按其类型递归拆分，而不是在任意字符位置截断。

### 5.2 内部默认长度

本期 UI 不展示参数，使用服务端版本化策略：

| 参数 | 默认值 | 含义 |
| --- | ---: | --- |
| `minTokens` | 100 | 低于该值时优先与同章节相邻内容合并 |
| `targetTokens` | 400 | 规划器期望的 Chunk 长度 |
| `maxTokens` | 650 | 常规 Chunk 上限 |
| `indexOverlapTokens` | 40 | 允许加入索引文本的最大 overlap |

这些值属于 `plannerVersion` 对应的系统策略，不是用户配置。生成预览时在 `file_processing.policy_snapshot` 中保存策略快照，便于定位质量问题和后续灰度调整。

### 5.3 边界评分

规划器将结构边界转换为分数，分数越高越适合作为 Chunk 边界：

| 边界 | 基础分 |
| --- | ---: |
| H1/H2 章节边界 | 100 |
| H3/H4 小节边界 | 90 |
| Markdown 分隔线 | 90 |
| 同级编号/标签条目边界 | 85 |
| 列表、表格、代码块结束 | 70 |
| 普通段落结束 | 50 |
| 句子结束 | 25 |

不可拆关系使用负分约束：

| 关系 | 调整分 |
| --- | ---: |
| 标题与首个正文 | -100 |
| 引导段与后续列表/表格 | -100 |
| 表名与表格 | -100 |
| 代码说明与代码块 | -80 |
| 列表、表格、代码块内部 | -80 |

具体分数封装在策略版本中，不作为数据库业务数据。分数的目的不是追求数学最优，而是让以下优先级稳定成立：结构完整性优先，其次是接近目标长度，最后才是减少 Chunk 数量。

### 5.4 规划步骤

1. 从当前章节开始累积语义单元。
2. 达到 `minTokens` 后开始评估候选边界。
3. 接近 `targetTokens` 时优先选择高分边界结束当前 Chunk。
4. 即将超过 `maxTokens` 时，回退到当前 Chunk 内最后一个合法边界。
5. 如果没有合法块边界，则对超长语义单元递归拆分。
6. 处理短尾：只允许在同一标题路径内与前一个 Chunk 合并，不能跨 H1/H2、分隔线或同级标签边界。
7. 输出 Chunk 的正文所有权、完整标题路径、源块集合和边界原因。

### 5.5 超长结构的递归拆分

- 超长段落：按句子结束符拆分；单句仍过长时才按 token 安全截断。
- 超长列表：按列表项分组，每个 Chunk 保持合法列表结构。
- 超长表格：重复表头后按数据行分组；表头只用于可读性和索引上下文，不改变源内容所有权。
- 超长代码块：优先按空行或明显函数边界拆分，并在每段保留围栏和语言标识；不能识别时按行分组。
- 超长引用：按内部段落或列表项拆分，并保留引用标记。

### 5.6 边界原因和质量指标

每个自动 Chunk 保存只读的 `boundary_reason`，例如：

```json
{
  "start": "H2_SECTION",
  "end": "PEER_LABEL",
  "forcedSplit": false
}
```

生成预览后记录质量统计：

- Chunk 数量、平均/最小/最大 token 数；
- 低于 `minTokens` 的 Chunk 比例；
- 被迫在句内截断的次数；
- 跨强边界合并次数，正常应为 0；
- 标题后无正文和空 Chunk 数，正常应为 0。

质量指标用于日志和测试，不在第一期 UI 中展示。

## 6. Overlap 规则

Overlap 只进入向量输入，不写入 Chunk 的 `content`，也不在检索结果正文中重复展示。这样相邻 Chunk 对原文的内容所有权保持唯一，用户编辑时不会看到重复正文。

仅在以下条件全部成立时，从前一个 Chunk 的编辑后正文尾部抽取最多 40 tokens 的完整句子：

- 两个 Chunk 在源顺序上相邻；
- 标题路径相同；
- 边界是因为连续正文过长而产生；
- 边界没有跨标题、分隔线或同级编号/标签；
- 前一个 Chunk 末尾不是表格或代码块；
- 中间没有被用户删除的 Chunk。

下列情况不添加 overlap：

- 新章节或新标题开始；
- FAQ/条款等同级条目之间；
- 表格或代码块边界；
- 用户删除内容后形成的非连续边界；
- 添加 overlap 会明显重复一个完整独立语义单元。

Overlap 在向量化前实时计算，来源是前一个 Chunk 当前保存的 `content`，不是原始 Markdown。编辑前一个 Chunk 后，如果右侧相邻 Chunk 实际使用了它的 overlap，则右侧 Chunk 的索引内容也已经失效，必须一起变为 `DRAFT`。这是内部一致性处理，不新增合并或边界调整操作。

## 7. 数据模型

### 7.1 为什么使用独立的 `file_processing`

`file` 表表示上传后的物理文件和文件级可用性；分块流程还包含异步进度、失败阶段、策略快照、错误信息和并发锁。将这些字段全部加入 `file` 会混合两类不同生命周期的数据，并使未来 PDF 接入时继续膨胀。

因此新增 `file_processing`，但它不是流程历史表，也不是版本表：

- 每个 `file` 只有一条 `file_processing`；
- 只保存当前状态，状态变化直接覆盖；
- 不保存历史状态轨迹；
- 文件删除时级联删除处理记录。

`document` 表也不需要新增。当前 `file` 已经能承担文档身份，同一个物理文件在不同知识库上传时生成不同 `file` 记录。

### 7.2 `file_processing`

建议字段：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `file_id` | bigint PK/FK | 与 `file` 一对一 |
| `tenant_id` | bigint | 租户隔离 |
| `knowledge_id` | bigint | 所属知识库；本期一个文件只属于一次上传关系 |
| `pipeline_state` | smallint | 当前文件处理状态 |
| `failed_from_state` | smallint nullable | 失败前所在阶段，用于决定重试入口 |
| `progress` | smallint | 0～100，仅供异步任务展示 |
| `source_hash` | varchar | 分块时的源文件哈希，防止源文件被替换后继续使用旧预览 |
| `planner_version` | varchar | 自适应规划器版本 |
| `policy_snapshot` | jsonb | 生成本次预览时使用的系统策略 |
| `legacy_vector_present` | boolean | 兼容上线前已存在的 Markdown 向量 |
| `last_error` | text nullable | 最近一次失败的可读摘要，不存堆栈和敏感信息 |
| `lock_version` | integer | 乐观锁，仅用于并发控制，不是业务历史版本 |
| `create_time` / `update_time` | timestamp | 时间字段 |

约束与索引：

- `file_id` 主键保证一对一。
- `(tenant_id, knowledge_id, pipeline_state)` 建普通索引，支持知识库文件列表展示状态。
- 所有查询同时校验 `tenant_id`、`knowledge_id` 和 `file_id`，不能只凭 Chunk ID 操作。

### 7.3 `document_chunk`

`document_chunk` 表只保存当前可编辑/可检索的 Chunk，不保存历史版本：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `id` | bigint PK | 数据库主键 |
| `public_id` | uuid UNIQUE | 稳定 Chunk ID，同时作为向量记录 ID |
| `tenant_id` | bigint | 租户隔离 |
| `knowledge_id` | bigint | 所属知识库 |
| `file_id` | bigint FK | 来源文件，文件删除时级联删除 |
| `position` | integer | 当前展示顺序 |
| `content` | text | 用户可编辑的当前正文，不包含标题路径和 overlap |
| `index_content` | text nullable | 最近一次成功向量化使用的完整文本；DRAFT 时置空 |
| `section_path` | jsonb | 有序标题数组 |
| `source_block_ids` | jsonb | 自动分块时包含的源块 ID |
| `source_start_offset` / `source_end_offset` | integer | 原 Markdown 字符范围 |
| `source_start_line` / `source_end_line` | integer | 原 Markdown 行范围 |
| `token_count` | integer | 当前正文 token 数 |
| `content_hash` | varchar | 当前正文摘要 |
| `boundary_reason` | jsonb | 自动规划边界原因，只读 |
| `status` | smallint | Chunk 状态 |
| `is_modified` | boolean | 是否偏离自动生成的正文；只用于提示，不保存原文副本 |
| `last_error` | text nullable | 最近一次该 Chunk 索引失败摘要 |
| `lock_version` | integer | 乐观锁；不代表历史版本 |
| `create_time` / `update_time` | timestamp | 时间字段 |

约束与索引：

- `(file_id, position)` 唯一，保证显示顺序稳定。
- `(tenant_id, knowledge_id, file_id, status)` 建索引。
- `content` 不能为空白；编辑为空等同于删除，但 API 要求用户明确调用删除，避免误操作。
- 不保存 `original_content`、`chunk_version`、`enabled`、`deleted` 或 revision 表。
- 删除是物理删除；数据库不会提供恢复能力。

## 8. 状态机

### 8.1 文件处理状态

文件处理状态使用数字存储，Java 枚举负责数字与语义转换，禁止使用 ordinal：

| 数值 | 状态 | 含义 |
| ---: | --- | --- |
| 0 | `UPLOADED` | 文件已上传，尚未分块 |
| 1 | `CHUNKING` | 正在异步解析和分块，不可编辑 |
| 2 | `CHUNKED` | 预览已生成，尚未人工修改 |
| 3 | `ADJUSTING` | 用户正在调整，或存在待重新向量化 Chunk |
| 4 | `CONFIRMED` | 已确认，等待向量化任务开始 |
| 5 | `VECTORIZING` | 正在生成向量，不可编辑 |
| 6 | `COMPLETED` | 所有保留 Chunk 均为 ACTIVE |
| 7 | `FAILED` | 最近一次分块、删除向量或向量化失败 |

主流程：

```text
UPLOADED → CHUNKING → CHUNKED
                         │
                         ├─ 编辑/删除 → ADJUSTING
                         │                │
                         └──── 确认 ──────┤
                                          ▼
                                    CONFIRMED
                                          ▼
                                    VECTORIZING
                                          ▼
                                     COMPLETED
                                          │
                                          └─ 编辑已发布 Chunk → ADJUSTING
```

`CHUNKING`、`CONFIRMED`、`VECTORIZING` 中发生异常均进入 `FAILED`，并记录 `failed_from_state`。重试规则：

- 从 `CHUNKING` 失败：重新解析源文件并原子替换预览。
- 从 `VECTORIZING` 失败：保留用户正文，将失败或未成功发布的 Chunk 恢复为 `DRAFT` 后重试。
- 编辑或删除 ACTIVE Chunk 时，向量清理失败：状态进入 `FAILED`，失效 Chunk 保持非 ACTIVE，保证不会返回过期正文。

`FAILED` 不是不可恢复终态。用户再次编辑保留下来的 DRAFT Chunk 时可转回 `ADJUSTING`；重新提交预览、确认、删除或单 Chunk 索引时，服务根据 `failed_from_state` 和 Chunk 当前状态选择对应重试路径。

异步任务启动前先通过数据库条件更新抢占状态，例如只允许 `UPLOADED/FAILED → CHUNKING`。应用重启时，启动检查将长时间停留在 `CHUNKING/VECTORIZING` 的记录转为 `FAILED`，防止永久卡死。本期不额外建设任务历史表或消息队列表。

### 8.2 Chunk 状态

| 数值 | 状态 | 含义 |
| ---: | --- | --- |
| 0 | `DRAFT` | 可编辑，尚无与当前正文匹配的有效向量 |
| 1 | `INDEXING` | 正在写入向量，暂时不可编辑 |
| 2 | `ACTIVE` | 当前正文与向量一致，可以参与检索 |

允许的核心转换：

```text
DRAFT → INDEXING → ACTIVE
ACTIVE ──编辑──→ DRAFT
ACTIVE ──重新向量化──→ INDEXING → ACTIVE
INDEXING ──失败──→ DRAFT
```

Chunk 不存在启用/停用状态。是否参与检索只由 `status == ACTIVE` 决定。

## 9. 完整后端流程

### 9.1 上传

1. 保存物理文件并创建 `file`。
2. 创建 `knowledge_file` 关联。
3. 创建一条 `file_processing`，状态为 `UPLOADED`。
4. 不自动向量化 Markdown。

同一个物理文件再次上传到其他知识库时重新创建 `file`、`knowledge_file` 和 `file_processing`，不共享预览或 Chunk。

### 9.2 生成预览

1. API 校验文件属于当前租户和知识库，且扩展名为 `.md` 或 `.markdown`。
2. 原子转换 `UPLOADED/FAILED → CHUNKING`，提交异步任务。
3. 异步任务计算源文件哈希，解析 AST、构建标题树、语义单元和 ChunkDraft。
4. 在单个数据库事务中删除该文件尚未发布的旧 DRAFT Chunk，并批量插入新 `document_chunk`，状态均为 `DRAFT`。
5. 更新策略快照、质量统计日志和状态 `CHUNKED`。
6. 失败时事务回滚，状态转为 `FAILED`，不会留下半套预览。

已经存在 ACTIVE Chunk 的文件不允许无提示地重新生成预览，因为本期没有版本回滚。若以后增加“重新智能分段”，必须明确提示这会覆盖当前编辑结果并重新索引，不纳入本期。

### 9.3 加载和继续编辑

预览查询直接按 `file_id, position` 读取 `document_chunk`。`CHUNKED` 和 `ADJUSTING` 均允许用户离开页面后再次进入：

- `CHUNKED` 表示自动预览尚未修改；
- 第一次有效编辑或删除后变为 `ADJUSTING`；
- 已保存的 `content` 就是下次打开时展示的正文。

不需要 `file_chunk` 或“临时分块表”。`document_chunk` 同时是可持久化草稿和最终检索 Chunk，状态决定它当前处于哪一阶段。

### 9.4 编辑 Chunk

1. 前端发送 `content + lockVersion`，采用失焦保存或短防抖自动保存。
2. 后端拒绝空白内容、超出安全长度的内容和过期 `lockVersion`。
3. 更新正文、token 数、hash、`is_modified=true`、`status=DRAFT`、`index_content=null`。
4. 如果原状态是 ACTIVE，先使数据库状态失效，再尽快删除旧向量。数据库状态先提交，因此即使向量删除失败，检索后置校验也不会返回旧内容。
5. 如果右侧相邻 Chunk 的 overlap 依赖当前 Chunk，则同样将右侧 Chunk 标为 DRAFT、清空 `index_content` 并删除其旧向量。
6. 文件状态变为 `ADJUSTING`。

标题路径在本期为只读结构信息，用户只编辑正文。这样避免正文与源标题树产生无法解释的映射；若源标题本身错误，用户可以把需要检索的说明补入正文，或重新上传修正后的 Markdown。

### 9.5 删除 Chunk

- 删除 DRAFT Chunk：数据库物理删除，并压紧后续 `position`。
- 删除 ACTIVE Chunk：先将其状态改为 DRAFT，使检索立即失效；删除向量成功后物理删除数据库记录并压紧顺序。
- 如果删除导致右侧 Chunk 不再满足 overlap 连续条件，则右侧 Chunk 也转为 DRAFT 并清除旧向量。
- 向量删除失败时不物理删除记录，文件进入 `FAILED`，用户可重试；该 Chunk 因为不是 ACTIVE，不会参与检索。
- 删除成功后，如果剩余 Chunk 全部为 ACTIVE，文件恢复/保持 `COMPLETED`；只要还有 DRAFT，文件就是 `ADJUSTING`。
- 删除最后一个 Chunk 时禁止确认向量化，并提示文件没有可导入内容。

不提供撤销或回收站。

### 9.6 确认并整体向量化

1. `CHUNKED/ADJUSTING` 可以首次确认；`FAILED` 且 `failed_from_state=VECTORIZING` 时可以重试确认。必须至少存在一个 Chunk，且所有 Chunk 当前为 DRAFT 或 ACTIVE。
2. 条件更新文件状态为 `CONFIRMED`，立即禁止编辑。
3. 异步任务抢占为 `VECTORIZING`，将需要索引的 DRAFT Chunk 批量置为 `INDEXING`。
4. 对每个 Chunk 从数据库读取最新正文，并构建 `index_content`。
5. 向量 Document ID 固定使用 `document_chunk.public_id`；metadata 写入租户、知识库、文件、Chunk ID、位置和标题路径。
6. 全部向量写入成功后，在事务中将 Chunk 置为 `ACTIVE`、保存实际 `index_content`，再将文件置为 `COMPLETED`。
7. 任一写入失败时，补偿删除本批次已写入向量，相关 Chunk 恢复 `DRAFT`，文件进入 `FAILED`。用户编辑正文不会丢失。

由于数据库和向量库无法组成同一个事务，采用“数据库状态先失效 + 向量写入补偿 + 检索后置校验”保证最终一致性。

### 9.7 单 Chunk 重新向量化

单 Chunk 重新向量化适用于用户修改已发布 Chunk 后的快速恢复，也允许对 ACTIVE Chunk 主动重算：

1. `DRAFT/ACTIVE → INDEXING`，进入 INDEXING 后禁止并发编辑或删除。
2. 根据最新正文和当前相邻关系重新构建 `index_content`。
3. 使用稳定 `public_id` 删除/覆盖旧向量并写入新向量。
4. 成功后置为 `ACTIVE`；失败后置为 `DRAFT`，文件进入 `FAILED` 并记录错误。
5. 若文件内还存在 DRAFT Chunk，文件保持 `ADJUSTING`；全部 Chunk 均为 ACTIVE 时文件变为 `COMPLETED`。

如果编辑引起右侧 Chunk 的 overlap 失效，两个 Chunk 都会显示待重新向量化。用户可以分别重算，也可以使用文件级确认一次处理全部 DRAFT Chunk。

## 10. 向量输入和 metadata

### 10.1 索引文本

实际送入 Embedding 模型的文本格式固定为：

```text
文档：科大百事通.md
标题：科大百事通 > 校园网 > 如何重置密码
上文：……最多 40 tokens 的合法 overlap……

……用户编辑后的正文……
```

规则：

- 文档名和完整标题路径始终参与向量计算。
- 没有标题路径时省略“标题”行。
- 没有合法 overlap 时省略“上文”行。
- `index_content` 保存成功索引时的完整文本，便于排查“数据库正文与向量输入不一致”。
- 返回给用户或大模型的正文是 `content`，不是 `index_content`，避免把技术前缀和 overlap 当成正式答案重复展示。

### 10.2 向量 metadata

至少包含：

```json
{
  "tenantId": 1,
  "knowledgeId": 10,
  "fileId": 20,
  "documentPublicId": "文件 UUID",
  "documentChunkId": "Chunk UUID",
  "chunkIndex": 3,
  "fileType": "md",
  "sectionPath": ["科大百事通", "校园网", "如何重置密码"]
}
```

向量 ID 与 `documentChunkId` 相同，便于精确覆盖和删除。

## 11. 检索一致性与来源定位

向量 metadata 不是 Chunk 有效性的最终依据。相似度搜索得到候选结果后，系统按 `documentChunkId` 批量读取 `document_chunk`：

1. 只保留数据库中仍存在且 `status=ACTIVE` 的 Chunk。
2. 再次校验租户、知识库、文件关系和文件级 `file.status`。
3. 使用数据库中的当前 `content`、标题路径和来源位置构造返回值。
4. 按原相似度顺序截取最终 topK。

为避免少量待删除旧向量占满候选集，向量库查询先取 `topK × 3`，并设置安全上限 100，再经过数据库过滤后截取 topK。向量删除仍需重试，过量拉取只是短时一致性保护，不替代清理。

来源定位信息包括：

- 文件公开 ID 和文件名；
- Chunk 公开 ID；
- 完整标题路径；
- 原 Markdown 起止行和字符范围；
- 当前 Chunk 顺序。

人工编辑后，源行号仍表示“该 Chunk 最初由哪里自动生成”，不声称编辑后的每个字符都能映射回原文；`is_modified` 用于让调用方区分这一点。

## 12. API 设计

沿用现有登录和 `tenant_admin` 权限要求，所有写接口做租户与知识库归属校验。建议新增：

| 方法 | 路径 | 作用 |
| --- | --- | --- |
| `POST` | `/knowledge/{knowledgeId}/files/{fileId}/chunk-preview` | 启动智能分块，或重试从 CHUNKING 失败的任务，返回 202 |
| `GET` | `/knowledge/{knowledgeId}/files/{fileId}/processing` | 查询文件流程状态、进度和错误 |
| `GET` | `/knowledge/{knowledgeId}/files/{fileId}/chunks` | 分页或全量读取预览 Chunk |
| `PATCH` | `/knowledge/{knowledgeId}/files/{fileId}/chunks/{chunkId}` | 保存 Chunk 正文 |
| `DELETE` | `/knowledge/{knowledgeId}/files/{fileId}/chunks/{chunkId}` | 删除 Chunk |
| `POST` | `/knowledge/{knowledgeId}/files/{fileId}/confirm` | 确认并启动文件级向量化，或重试向量化失败任务 |
| `POST` | `/knowledge/{knowledgeId}/files/{fileId}/chunks/{chunkId}/reindex` | 单 Chunk 重新向量化或失败重试 |

关键返回码：

- `202 Accepted`：异步分块或向量化已经成功入队。
- `409 Conflict`：状态不允许当前操作，或 `lockVersion` 已过期。
- `422 Unprocessable Entity`：正文为空、文件无 Chunk、源文件 hash 已变化等业务校验失败。
- `404 Not Found`：资源不存在或不属于当前租户/知识库，避免泄露跨租户信息。

旧的 `POST /knowledge/file` 对 Markdown 不再直接向量化。过渡期可将其改为启动预览并返回新流程状态；前端完成切换后再移除兼容入口。

## 13. 前端交互约束

页面参考已确认的双栏布局：

- 左侧只展示“智能分段”及说明，不展示高级分段和标题关联问题选项。
- 点击“生成预览”后轮询处理状态；CHUNKING 时禁用编辑。
- 右侧按顺序展示 Chunk 卡片、标题路径、正文、字符/token 数和当前索引状态。
- 卡片只提供编辑和删除；ACTIVE Chunk 修改后显示“待重新向量化”。
- 正文采用失焦保存或 500～800ms 防抖保存，并展示保存中/已保存/冲突状态。
- 删除需要二次确认，因为没有历史版本和恢复能力。
- 页面底部提供“开始导入/确认向量化”；完成后的 Chunk 卡片可提供“重新向量化”。
- VECTORIZING 和 INDEXING 时禁止相关编辑、删除和重复提交。

系统内部仍执行完整边界规划，UI 简化不等于退化为固定长度切分。

## 14. 并发、事务和错误处理

- `file_processing.lock_version` 防止重复生成预览、重复确认和异步任务重复执行。
- `document_chunk.lock_version` 防止两个浏览器标签互相覆盖编辑；它不是 Chunk 历史版本。
- 预览替换、Chunk 批量状态变化和文件状态变化分别使用短数据库事务。
- 不在数据库事务中等待 Embedding 或向量库网络调用。
- 向量化采用“声明 INDEXING → 外部调用 → 提交 ACTIVE”的三段式处理。
- 日志带 `tenantId/knowledgeId/fileId/chunkId/pipelineState`，不打印完整用户正文。
- `last_error` 只保存脱敏后的短消息；完整堆栈仅进服务日志。
- 状态转换全部经过集中服务和白名单，不允许直接写任意数字。

## 15. 与现有字段和旧数据的兼容

### 15.1 `file` 表

- `file.status` 继续表示文件级检索可用/禁用，不承担流程状态。
- `file.embedding_status` 不再作为真实流程来源。过渡期只做兼容映射：
  - `COMPLETED → 2`
  - `FAILED → 1`
  - 其他状态 `→ 0`
- 新前端改读 `file_processing.pipeline_state` 后，再单独安排迁移移除 `embedding_status`。

### 15.2 上线前已经向量化的 Markdown

采用非破坏式渐进迁移：

1. 为已有 Markdown 文件补 `file_processing`，状态为 `UPLOADED`。
2. 若原 `embedding_status=2`，设置 `legacy_vector_present=true`，旧向量在用户生成预览和编辑期间继续服务。
3. 用户确认新预览后，文件进入 VECTORIZING；此时暂时从检索中排除该文件，按租户/知识库/文件范围删除旧向量。
4. 旧向量删除成功后立即设置 `legacy_vector_present=false`，再写入新 Chunk 向量。若后续新向量写入失败，该文件保持不可检索并等待重试，不能错误回退到已经不存在的旧向量。
5. 新向量全部成功后设置 `COMPLETED`。
6. 新上传文件默认 `legacy_vector_present=false`。

这样发布数据库变更不会立即清除已有知识库内容，也不会伪造缺少正文和来源信息的 `document_chunk`。

PDF、TXT、Office 文件继续走旧链路，检索后置校验仅对携带 `documentChunkId` 的新 Markdown 向量强制查询 `document_chunk`；旧格式的向量按原逻辑返回。

## 16. 测试设计

### 16.1 解析器单元测试

- 标题栈正确处理跳级、回退和重复标题。
- 列表、嵌套列表、代码块、引用、表格和 HTML 块源范围正确。
- 软换行段落不会被误拆成固定问答格式。
- 空章节不生成独立 Chunk。

### 16.2 规划器单元测试

- 标题不单独成块，完整路径进入正文 Chunk。
- 引导段不与其列表/表格分离。
- 同级编号条目优先分开。
- 普通长段在目标长度附近按句切分。
- 超长列表、表格和代码块按类型递归拆分。
- 短尾只在同标题路径内合并。
- 不跨强边界生成 overlap。
- `科大百事通.md` 作为黄金样例保存期望 Chunk 路径、数量范围和关键正文归属，不绑定脆弱的精确 token 数。

### 16.3 状态机和服务测试

- 非法状态转换返回 409。
- 预览失败不留下半套 Chunk。
- 编辑能持久化并在重新查询后恢复。
- 乐观锁冲突不会覆盖新内容。
- 编辑 ACTIVE Chunk 后旧向量立即失效。
- 编辑前一 Chunk 会按 overlap 依赖使右侧 Chunk 失效。
- 删除 DRAFT/ACTIVE Chunk 的数据库与向量补偿行为正确。
- 整体向量化部分失败后正文仍保留、Chunk 回到 DRAFT。
- 单 Chunk 重新向量化成功后恢复 ACTIVE。

### 16.4 检索集成测试

- 搜索只能返回 ACTIVE Chunk。
- stale 向量存在时会被数据库后置校验过滤。
- 返回正文不包含文档名、标题前缀和 overlap。
- 返回来源包含文件、Chunk、完整标题路径和源行号。
- 租户、知识库和禁用文件过滤保持有效。
- 旧 Markdown 和非 Markdown 向量在迁移期仍能检索。

### 16.5 前端测试

- CHUNKING/VECTORIZING/INDEXING 时按钮状态正确。
- 编辑保存、保存失败、并发冲突和刷新恢复正确。
- 删除确认和删除最后一个 Chunk 后的提示正确。
- DRAFT/ACTIVE 状态和待重新向量化提示正确。

## 17. 实施边界与验收标准

本设计可以作为一个实现计划完成，但应按以下顺序拆分任务：数据库和状态模型、Markdown 解析器、规划器、预览服务、编辑删除、向量化与检索一致性、前端交互、旧数据兼容。

最终验收标准：

1. Markdown 上传后不能绕过预览直接向量化。
2. `科大百事通.md` 中标题不会形成空 Chunk，正文能继承正确的完整标题路径。
3. 列表、表格、代码和同级条目的关键边界符合规划规则。
4. 用户编辑/删除后刷新或下次进入仍保持结果。
5. 向量输入精确包含文档名、标题路径、合法 overlap 和编辑后的正文。
6. 检索只返回 ACTIVE Chunk，并返回不含 overlap 的当前正文和来源信息。
7. 单 Chunk 支持编辑、删除、重新向量化，不存在启用/停用和历史版本功能。
8. 分块或向量化失败可以安全重试，不产生可检索的过期 Chunk。
