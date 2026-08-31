# Markdown 自适应分块与人工调整设计

日期：2026-08-31
状态：已按第三轮评审修订，待书面规格确认

## 1. 背景

当前 Markdown 文件在 `PgVectorRagServiceImpl` 中经过 `MarkdownDocumentReader` 和默认 `TokenTextSplitter` 后直接写入向量库。现状有三个主要问题：

1. 分块策略固定，主要依据长度，不能稳定保留 Markdown 的标题层级、列表、表格、代码块等结构。
2. 分块结果不可见，用户无法在向量化前发现标题丢失、上下文错位、内容归属错误等问题。
3. 分块和向量化是一次性过程，用户不能保存调整结果并在下次继续。

本次只实现 Markdown 自适应分块，但流程、数据表和公共接口按文件类型无关的方式设计。后续接入 PDF 时只新增 PDF 结构解析与规划策略，不改流程状态机、Chunk 生命周期和向量化主链路。

## 2. 目标与非目标

### 2.1 目标

- 自动感知 Markdown 结构并生成质量更高的 Chunk。
- 标题不单独成为 Chunk，而是作为正文 Chunk 的结构上下文。
- 所有 Markdown 文件必须先生成分块预览，再由用户确认向量化。
- 预览页只提供智能分段，用户可以编辑正文和删除 Chunk。
- 编辑结果实时保存，用户离开后可以继续处理。
- 预览态只展示只读标题结构和可编辑原文 Chunk，不展示 overlap，用户只关注原文分块质量。
- 最终向量化前允许开启或关闭 overlap；开启时可设置 `overlapTokens`，默认 40。
- Embedding 输入与 LLM 上下文使用同一文本：关闭 overlap 时为“完整标题路径 + 编辑后的正文”，开启时为“完整标题路径 + overlap + 编辑后的正文”。
- `maxTokens` 可以在智能分段设置中修改；最终上下文不得超过 Embedding 模型的 512 token 硬限制。
- 已向量化的单个 Chunk 支持编辑、删除和重新向量化。
- 分块、编辑、向量化和失败重试具有明确、可恢复的状态。
- 检索结果能通过 Chunk 定位到文件、标题路径和源文件位置。

### 2.2 非目标

- 本期不实现 PDF 自适应分块。
- 不提供高级分段或手工设置分隔符；智能分段阶段只开放 `maxTokens`，`overlapEnabled/overlapTokens` 在最终向量化前设置。
- 不提供合并、拆分、拖动边界等交互；用户通过直接编辑相邻 Chunk 完成内容归位。
- 不提供“分段标题作为关联问题”开关；标题默认参与向量计算。
- 不提供 Chunk 启用/停用。
- 不保存 Chunk 修改历史、历史版本或回滚记录。
- 不新增用于跨知识库复用物理文件的 `document` 表。同一个物理文件上传到不同知识库时按不同文件处理。
- 不针对问答文档编写专用解析器。

## 3. 总体架构

新增四个职责清晰的层次：

```text
原文件（本期 Markdown，后续 PDF）
    │
    ▼
结构解析策略 DocumentStructureParser
    │  StructuredBlock + 标题/章节结构
    ▼
自适应规划策略 AdaptiveChunkPlanner
    │  ChunkDraft
    ▼
持久化预览层 document_chunk（DRAFT）
    │  用户编辑 / 删除 / 下次继续
    ▼
索引构建层 ChunkIndexContentBuilder
    │  标题路径 + [可选 overlap] + 编辑后正文
    ▼
向量库 + document_chunk（ACTIVE）
```

各组件职责如下：

- `DocumentStructureParser`：文件结构解析策略接口，只负责输出通用结构块，不决定最终 Chunk 边界。本期实现 `MarkdownStructureParser`，后续增加 `PdfStructureParser`。
- `SemanticUnitBuilder`：将必须保持在一起的相邻块组合成语义单元，例如 Markdown 的“引导段 + 列表”；不同文件类型可以提供自己的组合规则。
- `AdaptiveChunkPlanner`：规划策略接口，根据结构边界、完整索引 token 预算和不可拆约束规划 Chunk。本期注册 Markdown 策略，后续注册 PDF 策略。
- `ChunkPreviewService`：生成预览并将当前 Chunk 写入数据库。
- `FileProcessingService`：集中维护文件处理状态，禁止 Controller 或异步任务随意写状态值。
- `ChunkCommandService`：处理 Chunk 编辑、删除、确认和单 Chunk 重新向量化。
- `ChunkIndexContentBuilder`：每次向量化前，根据数据库中的最新正文重新生成实际索引文本。
- `ChunkVectorService`：封装向量写入、删除、补偿和状态更新。

策略通过 `file.type` 路由，公共主流程只依赖统一的 `StructuredBlock` 和 `ChunkDraft`。本期不再引入 `file_chunk`。解析产生的结构块、语义单元和 `ChunkDraft` 都是内存中的中间模型；一旦预览生成，直接持久化为 `document_chunk`。

## 4. Markdown 结构感知

### 4.1 解析方式

使用 commonmark-java AST 解析 Markdown，解析器明确开启 `IncludeSourceSpans.BLOCKS_AND_INLINES`，并加载 GFM table 扩展。对应实现依赖为 `org.commonmark:commonmark` 和 `org.commonmark:commonmark-ext-gfm-tables`，两个依赖使用同一版本。不能通过正则逐行切分 Markdown，因为正则难以正确处理嵌套列表、围栏代码块、引用、HTML 块和软换行。

解析结果统一转换为内部模型：

```java
record StructuredBlock(
    String blockId,
    BlockType type,
    String rawText,
    String plainText,
    Integer headingLevel,
    List<String> sectionPath,
    int tokenCount,
    SourceLocator sourceLocator,
    Map<String, Object> attributes
) {}
```

Markdown 实现的 `sourceLocator` 填充行号和字符偏移；未来 PDF 实现填充页码和版面区域，并可在 `attributes` 中提供字体、坐标和目录层级特征。两者通过同一个来源定位接口交给后续规划器。

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

### 5.2 Token 预算

智能分段阶段只开放 `maxTokens`，其余规划参数由服务端策略控制；Overlap 在最终向量化前单独设置：

| 参数 | 默认值 | 含义 |
| --- | ---: | --- |
| `minTokens` | 100 | 低于该值时优先与同章节相邻内容合并 |
| `targetTokens` | 400 | 预览阶段“标题路径 + 正文”的内部目标上限；实际取 `min(400, floor(maxTokens × 0.8))` |
| `maxTokens` | 512 | 用户可调；完整索引输入的硬上限，后端禁止设置为大于 512 |
| `overlapEnabled` | false | 最终向量化前设置；是否为 Embedding 与 LLM 上下文加入 overlap |
| `overlapTokens` | 40 | 仅开启 overlap 时有效；完整句 overlap 的最大 token 预算 |

`maxTokens` 不是正文长度，而是一次真正送入 Embedding 模型的全部文本长度：

```text
token(完整标题路径 + [可选 overlap] + 编辑后的正文 + 固定格式符)
    <= maxTokens <= 512
```

分块预览阶段尚未确定是否开启 overlap，因此规划器只用标题路径和正文控制原文 Chunk：

```text
previewBodyBudget = maxTokens - titlePathTokens - formatTokens
```

最终开启 overlap 时不能回头修改用户确认的原文分块。系统从 512 总预算的剩余空间中抽取 overlap：

```text
overlapBudget = min(
    overlapTokens,
    maxTokens - titlePathTokens - bodyTokens - formatTokens
)
```

如果当前标题路径和正文已经用满预算，则该 Chunk 的实际 overlap 为 0。系统不能为了加入 overlap 截断正文或改变预览边界。规划和向量化必须使用与当前 Embedding 模型一致的 tokenizer；不能用字符数或另一个模型的 token 估算替代最终校验。

自动生成内容超过预算时，规划器按结构递归拆分。用户编辑后“标题路径 + 正文”超过预算时不允许静默截断，保存接口返回 422，并给出标题和正文各自的 token 数。完整标题路径本身已经达到 512 时，文件无法安全向量化，返回明确错误并要求用户缩短源标题。

生成预览时在 `file_processing.policy_snapshot` 中保存实际生效参数；参数含义和版本作用见 7.2。

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
4. 预计“标题路径 + 正文”即将超过 `maxTokens` 时，回退到当前 Chunk 内最后一个合法边界。
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

Overlap 在预览态完全不可见，也不可编辑。用户确认原文分块后，在最终向量化前设置：

- `overlapEnabled=false`：Embedding 输入和 LLM 上下文均为“完整标题路径 + 正文”。
- `overlapEnabled=true`：Embedding 输入和 LLM 上下文均为“完整标题路径 + overlap + 正文”。
- 开启时 `overlapTokens` 默认 40，可修改；它表示完整句 overlap 的最大 token 预算。

从前一个 Chunk 的编辑后正文末尾向前选取完整句子，实际 overlap 必须同时满足：

- 不超过用户设置的 `overlapTokens`；
- 加入标题路径和当前正文后，总长度不超过 `maxTokens`；
- 不截断句子。

抽取算法使用支持中英文标点的句子边界识别，从最后一个完整句子开始向前累积，加入下一句会超出任一预算时停止。因此配置 40 表示“最多 40 tokens”，实际可能是 36、18 或 0；如果最后一个完整句子自身超过预算，则不添加 overlap，不能截取半句凑满 40。

仅在以下条件全部成立时生成 overlap：

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

Overlap 在确认向量化时根据最终开关、token 预算和编辑后的正文统一生成并保存。它虽然不展示给用户，但必须持久化，确保 Embedding 输入和后续 LLM 上下文使用完全相同的文本。

用户保存后的 `content` 直接替换自动分块正文，不再使用原始 Markdown 正文。文件完成向量化后：

- overlap 关闭时，编辑一个 Chunk 只使该 Chunk 失效。
- overlap 开启时，如果被编辑 Chunk 是下一 Chunk 的 overlap 来源，系统从编辑后的正文重新提取完整句，更新下一 Chunk 的隐藏 `overlap_content`，并同时将下一 Chunk 置为 `DRAFT`。

Overlap 文本没有独立编辑入口。用户只控制是否开启和最大 token 数，具体内容始终由相邻正文、结构边界和完整句规则确定。

## 7. 数据模型

### 7.1 为什么使用独立的 `file_processing`

`file` 表表示上传后的物理文件和文件级可用性；分块流程还包含异步进度、失败阶段、分块参数、策略版本、错误信息和并发锁。将这些字段全部加入 `file` 会混合两类不同生命周期的数据，并使未来 PDF 接入时继续膨胀。

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
| `planner_version` | varchar | 生成当前预览的规划算法版本，如 `markdown-adaptive-v1`、未来的 `pdf-layout-v1` |
| `policy_snapshot` | jsonb | 当前流程实际生效的分块与上下文参数、tokenizer 信息 |
| `last_error` | text nullable | 最近一次失败的可读摘要，不存堆栈和敏感信息 |
| `lock_version` | integer | 乐观锁，仅用于并发控制，不是业务历史版本 |
| `create_time` / `update_time` | timestamp | 时间字段 |

约束与索引：

- `file_id` 主键保证一对一。
- `(tenant_id, knowledge_id, pipeline_state)` 建普通索引，支持知识库文件列表展示状态。
- 所有查询同时校验 `tenant_id`、`knowledge_id` 和 `file_id`，不能只凭 Chunk ID 操作。

`policy_snapshot` 不是历史记录。它保存当前这套预览真正使用的参数，例如：

```json
{
  "maxTokens": 512,
  "overlapEnabled": false,
  "overlapTokens": 40,
  "minTokens": 100,
  "targetTokens": 400,
  "tokenizer": "当前 Embedding 模型 tokenizer"
}
```

预览生成时写入分块参数；用户最终确认时再写入 `overlapEnabled/overlapTokens`。它有三个作用：用户下次打开时恢复本次设置；单 Chunk 重新向量化时继续使用相同的上下文规则；系统默认值变化后，已经发布的向量和 LLM 上下文不会悄悄改变。参数更新直接覆盖该字段，不产生历史版本。

`planner_version` 标识“是哪套代码规则生成了当前边界”，用于问题定位、测试样例和未来策略升级。它与 Chunk 修改版本无关，也不保存历史。PDF 接入后写入自己的规划器版本，公共流程不需要新增字段。

### 7.3 `document_chunk`

`document_chunk` 表只保存当前可编辑/可检索的 Chunk，不保存历史版本：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `id` | bigint PK | 数据库主键 |
| `public_id` | uuid UNIQUE | Chunk 对外稳定 ID，同时作为向量记录 ID |
| `tenant_id` | bigint | 租户隔离 |
| `knowledge_id` | bigint | 所属知识库 |
| `file_id` | bigint FK | 来源文件，文件删除时级联删除 |
| `position` | integer | 当前展示顺序 |
| `content` | text | 用户可编辑的当前正文，不包含标题路径和 overlap |
| `overlap_content` | text nullable | 隐藏的上文完整句；开启 overlap 后参与向量化和 LLM 上下文 |
| `overlap_source_chunk_id` | bigint nullable | overlap 来源 Chunk；删除来源后用于清空和重新计算 |
| `overlap_token_count` | integer | 当前 overlap 的实际 token 数 |
| `index_content` | text nullable | 最近一次成功向量化使用的完整文本；DRAFT 时置空 |
| `section_path` | jsonb | 有序标题数组 |
| `source_locator` | jsonb | 文件类型无关的来源定位信息 |
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

`source_locator` 使用按文件类型区分的 JSON 结构，避免 PDF 接入时修改表结构：

```json
// Markdown
{
  "type": "markdown",
  "blockIds": ["b12", "b13"],
  "startOffset": 320,
  "endOffset": 680,
  "startLine": 18,
  "endLine": 31
}

// 后续 PDF
{
  "type": "pdf",
  "startPage": 3,
  "endPage": 4,
  "regions": []
}
```

`file.public_id` 和 `document_chunk.public_id` 的映射范围不同：

- `file.public_id` 表示一次上传形成的整份文件，是 API、检索来源中的文档公开 ID，对应向量 metadata 的 `documentPublicId`。
- `document_chunk.public_id` 表示这份文件中的一个 Chunk，对应向量 metadata 的 `documentChunkId`，并直接作为向量记录 ID。Chunk 编辑后该 UUID 不变，因此可以精确覆盖或删除同一条向量。
- 两者都使用公开 UUID，是为了不把数据库递增主键暴露到 API 和向量 metadata；Chunk 与文件的真实归属仍通过 `document_chunk.file_id → file.id` 外键维护，它们的 UUID 不互相映射。

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

1. API 校验文件属于当前租户和知识库，且扩展名为 `.md` 或 `.markdown`；校验 `maxTokens <= 512`，并确保标题路径和正文具有有效预算。
2. 原子转换 `UPLOADED/FAILED → CHUNKING`，提交异步任务。
3. 异步任务计算源文件哈希，解析 AST、构建标题树、语义单元和 ChunkDraft，并使用 Embedding tokenizer 校验完整索引预算。
4. 在单个数据库事务中删除该文件尚未发布的旧 DRAFT Chunk，并批量插入新 `document_chunk`，状态均为 `DRAFT`；预览阶段 `overlap_content` 保持为空。
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
2. 后端按“标题路径 + 正文”重新计算 token，拒绝空白内容、超过当前 `maxTokens` 的内容和过期 `lockVersion`；Overlap 将来只能使用剩余预算，不能挤占正文。
3. 更新正文、token 数、hash、`is_modified=true`、`status=DRAFT`、`index_content=null`。
4. 如果原状态是 ACTIVE，先使数据库状态失效，再尽快删除旧向量。数据库状态先提交，因此即使向量删除失败，检索后置校验也不会返回旧内容。
5. 文件已经发布且 overlap 开启时，先按编辑后正文的剩余预算重新计算当前 Chunk 自己的隐藏 overlap；如果右侧相邻 Chunk 的 overlap 依赖当前 Chunk，再从编辑后的正文重新提取完整句并更新右侧 `overlap_content`。右侧上下文发生变化时，将右侧 Chunk 标为 DRAFT、清空 `index_content` 并删除其旧向量。Overlap 关闭时不级联右侧 Chunk。
6. 文件状态变为 `ADJUSTING`。

标题路径在本期为只读结构信息，用户只编辑正文。这样避免正文与源标题树产生无法解释的映射；若源标题本身错误，用户可以把需要检索的说明补入正文，或重新上传修正后的 Markdown。

### 9.5 删除 Chunk

- 删除 DRAFT Chunk：数据库物理删除，并压紧后续 `position`。
- 删除 ACTIVE Chunk：先将其状态改为 DRAFT，使检索立即失效；删除向量成功后物理删除数据库记录并压紧顺序。
- 文件已经发布且 overlap 开启时，如果删除导致右侧 Chunk 不再满足 overlap 连续条件，则右侧 Chunk 也转为 DRAFT 并清除旧向量；overlap 关闭时不级联。
- 向量删除失败时不物理删除记录，文件进入 `FAILED`，用户可重试；该 Chunk 因为不是 ACTIVE，不会参与检索。
- 删除成功后，如果剩余 Chunk 全部为 ACTIVE，文件恢复/保持 `COMPLETED`；只要还有 DRAFT，文件就是 `ADJUSTING`。
- 删除最后一个 Chunk 时禁止确认向量化，并提示文件没有可导入内容。

不提供撤销或回收站。

### 9.6 确认并整体向量化

1. `CHUNKED/ADJUSTING` 可以首次确认；`FAILED` 且 `failed_from_state=VECTORIZING` 时可以重试确认。必须至少存在一个 Chunk，且所有 Chunk 当前为 DRAFT 或 ACTIVE。
2. 用户提交最终 `overlapEnabled`；开启时同时提交 `overlapTokens`，默认 40。服务保存到 `policy_snapshot`，条件更新文件状态为 `CONFIRMED`，立即禁止编辑。
3. 异步任务抢占为 `VECTORIZING`，将需要索引的 DRAFT Chunk 批量置为 `INDEXING`。
4. 对每个 Chunk 从数据库读取最新正文。Overlap 开启时按结构、完整句和剩余 token 预算生成隐藏 `overlap_content`；关闭时清空该字段。随后构建 `index_content`。
5. 向量 Document ID 固定使用 `document_chunk.public_id`；metadata 写入租户、知识库、文件、Chunk ID、位置和标题路径。
6. 全部向量写入成功后，在事务中将 Chunk 置为 `ACTIVE`、保存实际 `index_content`，再将文件置为 `COMPLETED`。
7. 任一写入失败时，补偿删除本批次已写入向量，相关 Chunk 恢复 `DRAFT`，文件进入 `FAILED`。用户编辑正文不会丢失。

由于数据库和向量库无法组成同一个事务，采用“数据库状态先失效 + 向量写入补偿 + 检索后置校验”保证最终一致性。

### 9.7 单 Chunk 重新向量化

单 Chunk 重新向量化适用于用户修改已发布 Chunk 后的快速恢复，也允许对 ACTIVE Chunk 主动重算：

1. `DRAFT/ACTIVE → INDEXING`，进入 INDEXING 后禁止并发编辑或删除。
2. 根据最新正文、隐藏的 `overlap_content` 和当前文件策略构建 `index_content`，并再次执行 512 token 硬校验。
3. 使用稳定 `public_id` 删除/覆盖旧向量并写入新向量。
4. 成功后置为 `ACTIVE`；失败后置为 `DRAFT`，文件进入 `FAILED` 并记录错误。
5. 若文件内还存在 DRAFT Chunk，文件保持 `ADJUSTING`；全部 Chunk 均为 ACTIVE 时文件变为 `COMPLETED`。

如果编辑引起右侧 Chunk 的 overlap 失效，两个 Chunk 都会显示待重新向量化。用户可以分别重算，也可以使用文件级确认一次处理全部 DRAFT Chunk。

## 10. 向量输入和 metadata

### 10.1 索引文本

Overlap 关闭时，Embedding 输入和 LLM 上下文均为：

```text
标题：科大百事通 > 校园网 > 如何重置密码

……用户编辑后的正文……
```

Overlap 开启时，两者均为：

```text
标题：科大百事通 > 校园网 > 如何重置密码
上文：……最多 overlapTokens 的合法完整句……

……用户编辑后的正文……
```

规则：

- 文档名不进入 Embedding 输入和 LLM 上下文；完整标题路径始终进入。
- 用户保存后的 `content` 直接替换自动生成正文，绝不继续使用修改前的原始正文。
- `overlap_content` 在预览中不可见；开启后同时进入 Embedding 和 LLM，关闭后两边都不进入。
- 没有标题路径时省略“标题”行。
- 没有合法 overlap 时省略“上文”行。
- 标题、可选 overlap、编辑正文及固定格式符的 token 合计必须 `<= maxTokens <= 512`。
- `index_content` 保存成功索引时的完整文本，并直接作为检索命中后的 LLM 上下文，保证“向量检索的内容”和“交给 LLM 的内容”一致。
- 预览和人工编辑接口只返回 `section_path + content`，不返回 `overlap_content/index_content`；检索接口返回 `index_content` 作为上下文，并另带来源 metadata。

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
3. 使用数据库中的当前 `index_content` 作为 LLM 上下文，使用标题路径和来源位置构造 metadata。`index_content` 必须与该向量成功生成时的输入完全一致。
4. 按原相似度顺序截取最终 topK。

为避免少量待删除旧向量占满候选集，向量库查询先取 `topK × 3`，并设置安全上限 100，再经过数据库过滤后截取 topK。向量删除仍需重试，过量拉取只是短时一致性保护，不替代清理。

来源定位信息包括：

- 文件公开 ID 和文件名；
- Chunk 公开 ID；
- 完整标题路径；
- `source_locator` 中当前文件类型可提供的位置；Markdown 为起止行和字符范围，未来 PDF 为页码和版面区域；
- 当前 Chunk 顺序。

人工编辑后，源行号仍表示“该 Chunk 最初由哪里自动生成”，不声称编辑后的每个字符都能映射回原文；`is_modified` 用于让调用方区分这一点。

## 12. API 设计

沿用现有登录和 `tenant_admin` 权限要求，所有写接口做租户与知识库归属校验。建议新增：

| 方法 | 路径 | 作用 |
| --- | --- | --- |
| `POST` | `/knowledge/{knowledgeId}/files/{fileId}/chunk-preview` | 携带 `maxTokens` 启动智能分块，或重试从 CHUNKING 失败的任务，返回 202 |
| `GET` | `/knowledge/{knowledgeId}/files/{fileId}/processing` | 查询文件流程状态、进度和错误 |
| `GET` | `/knowledge/{knowledgeId}/files/{fileId}/chunks` | 分页或全量读取预览 Chunk |
| `PATCH` | `/knowledge/{knowledgeId}/files/{fileId}/chunks/{chunkId}` | 保存 Chunk 正文 |
| `DELETE` | `/knowledge/{knowledgeId}/files/{fileId}/chunks/{chunkId}` | 删除 Chunk |
| `POST` | `/knowledge/{knowledgeId}/files/{fileId}/confirm` | 携带 `overlapEnabled/overlapTokens` 确认并启动文件级向量化，或重试失败任务 |
| `POST` | `/knowledge/{knowledgeId}/files/{fileId}/chunks/{chunkId}/reindex` | 单 Chunk 重新向量化或失败重试 |

关键返回码：

- `202 Accepted`：异步分块或向量化已经成功入队。
- `409 Conflict`：状态不允许当前操作，或 `lockVersion` 已过期。
- `422 Unprocessable Entity`：正文为空、文件无 Chunk、源文件 hash 已变化等业务校验失败。
- `404 Not Found`：资源不存在或不属于当前租户/知识库，避免泄露跨租户信息。

## 13. 前端交互约束

页面参考已确认的双栏布局：

- 左侧只展示“智能分段”及说明，不展示高级分段和标题关联问题选项。
- 智能分段阶段只提供 `maxTokens`，默认且最高为 512。
- 点击“生成预览”后轮询处理状态；CHUNKING 时禁用编辑。
- 右侧按顺序展示 Chunk 卡片、不可编辑的完整标题路径、可编辑正文、正文 token 数和当前状态。
- 预览接口和页面不展示 overlap、`overlap_content` 或完整 `index_content`。
- 卡片只提供编辑和删除；ACTIVE Chunk 修改后显示“待重新向量化”。
- 正文采用失焦保存或 500～800ms 防抖保存，并展示保存中/已保存/冲突状态。
- 删除需要二次确认，因为没有历史版本和恢复能力。
- 点击“开始导入/确认向量化”时设置 overlap 开关；开启后显示 `overlapTokens`，默认 40。该设置作用于整个文件。
- 进入 `CONFIRMED` 后锁定 overlap 设置；本期不支持在 COMPLETED 后单独切换开关，因为切换会改变全部 Embedding 输入和 LLM 上下文，必须走未来的文件级重新索引能力。
- 完成后的 Chunk 卡片可提供“重新向量化”，沿用文件最终确认时保存的 overlap 设置。
- VECTORIZING 和 INDEXING 时禁止相关编辑、删除和重复提交。

修改 `maxTokens` 后需要重新生成预览，因为它会改变原文边界。若已经存在人工编辑的 DRAFT Chunk，前端必须提示重新生成会覆盖当前编辑；存在 ACTIVE Chunk 时本期不允许重新生成整套预览，只能编辑/删除/重新向量化现有 Chunk。Overlap 设置不改变预览边界，只在最终确认时生成隐藏上下文。

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

## 15. 全新项目基线与 PDF 扩展点

本项目按全新流程实现，不兼容旧的 `file.embedding_status`，也不迁移或识别旧向量：

- `file_processing.pipeline_state` 是唯一文件处理状态来源；新 schema、实体和接口中移除 `file.embedding_status`。
- `file` 只保留物理文件属性和文件级业务可用状态，不重复保存分块/向量化状态。
- 不设置 `legacy_vector_present`，不做双读、状态映射或旧向量兜底。
- 数据库初始化和新代码直接按新模型创建数据；旧链路不作为实现约束。

为了让 PDF 以最小改动接入，公共层固定以下接口和数据：

```java
interface DocumentStructureParser {
    boolean supports(String fileType);
    ParsedStructure parse(FileResource file);
}

interface ChunkPlanningStrategy {
    boolean supports(String fileType);
    List<ChunkDraft> plan(ParsedStructure structure, ChunkPolicy policy);
}
```

后续接入 PDF 时只需要：

1. 新增 `PdfStructureParser`，输出页、段落、标题、表格和版面区域等通用结构块。
2. 新增或注册 `PdfChunkPlanningStrategy`，处理跨页、页眉页脚和版面边界。
3. 将 PDF 页码/区域写入现有 `source_locator`。
4. 在 `planner_version` 写入 PDF 策略版本，在 `policy_snapshot` 写入 PDF 实际参数。
5. 复用现有预览、编辑、删除、状态机、512 token 校验、向量化、重新向量化和检索后置校验。

因此 PDF 接入不新增流程表、不新增 PDF Chunk 表、不改变 Chunk 状态，也不改变前端主要交互；只扩展解析/规划策略和来源定位展示。

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
- overlap 只包含完整中英文句子，实际 token 数不超过配置值。
- 每个预览 Chunk 的标题、正文和格式符总计不超过 512 tokens。
- 最终开启 overlap 后，标题、实际 overlap、正文和格式符总计仍不超过 512 tokens，预算不足时只缩短/取消 overlap，不改变正文。
- `科大百事通.md` 作为黄金样例保存期望 Chunk 路径、数量范围和关键正文归属，不绑定脆弱的精确 token 数。

### 16.3 状态机和服务测试

- 非法状态转换返回 409。
- 预览失败不留下半套 Chunk。
- 编辑能持久化并在重新查询后恢复。
- 乐观锁冲突不会覆盖新内容。
- 编辑 ACTIVE Chunk 后旧向量立即失效。
- overlap 开启时编辑前一 Chunk 会按依赖使右侧 Chunk 失效；关闭时不会级联。
- 重新向量化后，右侧隐藏 overlap 来自编辑后的正文且保持完整句。
- 正文编辑造成完整索引输入超过 maxTokens 时返回 422，不截断用户内容。
- 删除 DRAFT/ACTIVE Chunk 的数据库与向量补偿行为正确。
- 整体向量化部分失败后正文仍保留、Chunk 回到 DRAFT。
- 单 Chunk 重新向量化成功后恢复 ACTIVE。

### 16.4 检索集成测试

- 搜索只能返回 ACTIVE Chunk。
- stale 向量存在时会被数据库后置校验过滤。
- overlap 关闭时，检索返回的 LLM 上下文等于“标题路径 + 正文”。
- overlap 开启时，检索返回的 LLM 上下文等于“标题路径 + overlap + 正文”，并与 Embedding 输入一致。
- 返回来源包含文件、Chunk、完整标题路径和源行号。
- 租户、知识库和禁用文件过滤保持有效。

### 16.5 前端测试

- CHUNKING/VECTORIZING/INDEXING 时按钮状态正确。
- 预览态展示只读标题路径和可编辑正文，不泄露 `overlap_content/index_content`。
- 最终确认提供 overlap 开关，开启时显示 `overlapTokens`，默认 40。
- `maxTokens > 512` 时前端阻止提交，后端仍独立拒绝绕过校验的请求。
- 编辑保存、保存失败、并发冲突和刷新恢复正确。
- 删除确认和删除最后一个 Chunk 后的提示正确。
- DRAFT/ACTIVE 状态和待重新向量化提示正确。

## 17. 实施边界与验收标准

本设计可以作为一个实现计划完成，但应按以下顺序拆分任务：通用数据库和状态模型、文件类型策略接口、Markdown 解析器、Markdown 规划器、预览服务、编辑删除、向量化与检索一致性、前端交互。

最终验收标准：

1. Markdown 上传后不能绕过预览直接向量化。
2. `科大百事通.md` 中标题不会形成空 Chunk，正文能继承正确的完整标题路径。
3. 列表、表格、代码和同级条目的关键边界符合规划规则。
4. 用户编辑/删除后刷新或下次进入仍保持结果。
5. 预览只展示不可编辑的标题路径和可编辑正文，不展示 overlap。
6. 最终向量化前可以开启/关闭 overlap；开启时默认 40 tokens，且不截断句子。
7. Embedding 输入与 LLM 上下文完全一致：关闭时为“标题路径 + 正文”，开启时为“标题路径 + overlap + 正文”。
8. 任一最终上下文不超过 512 tokens；预算不足时只能减少 overlap，不能截断或改变用户确认的正文。
9. 检索只返回 ACTIVE Chunk，并返回保存的 `index_content` 作为 LLM 上下文和独立来源信息。
10. 单 Chunk 支持编辑、删除、重新向量化，不存在启用/停用和历史版本功能。
11. 分块或向量化失败可以安全重试，不产生可检索的过期 Chunk。
12. 后续 PDF 接入只需增加解析和规划策略，不修改流程表、Chunk 表和状态机。
