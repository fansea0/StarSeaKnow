# 通用分块设计

日期：2026-09-04
状态：对话设计已确认，待书面规格评审

## 1. 背景

StarSeaKnow 已实现 Markdown 自适应分块、分块预览、人工编辑、逐块补充上文、异步向量化和失败恢复。前端策略列表同时展示 `GENERAL`，但目前它只是禁用占位；后端只有 Markdown 解析器和 `MARKDOWN_OPTIMIZED` 规划策略。

通用分块提供一个与文件结构无关的基础策略。文件先提取为有顺序的文本，再按统一的分隔符、最大字符数、重叠字符数和清洗规则生成独立 Chunk。算法不调用大模型，不做语义相似度计算，也不产生父子层级。检索命中后直接返回该 Chunk 的索引文本。

本设计借鉴 Dify General mode 的产品心智，但参数、数据模型和限制服从 StarSeaKnow 已有的 512-token 索引硬约束及可编辑预览流程。

## 2. 目标与非目标

### 2.1 目标

- 为当前已注册文本抽取器能够识别、且能力探测通过的上传文件提供 `GENERAL` 策略。
- 使用一套统一、可预测、低成本的切分逻辑，不依赖文件结构解析和大模型。
- 支持普通字符串分隔符以及受限正则分隔符。
- 以字符数配置最大长度和 overlap，默认最大长度 500 字符、overlap 40 字符。
- 在界面提示 overlap 推荐值为最大长度的 15%，但不自动覆盖用户输入。
- 使用轻量边界工具减少句子被硬切的概率，并在必须硬切时明确标记原因。
- 支持空白归一化、URL 删除和邮箱删除，且清洗结果可预览、可追踪。
- 复用现有预览持久化、人工编辑、删除、逐块 overlap 调整、确认向量化、锁和失败恢复。
- 保证最终索引文本同时满足配置的字符上限和当前 512-token 硬约束。
- 同一文件重新调整分块参数时复用已完成的文本抽取结果。

### 2.2 非目标

- 不识别标题树、FAQ 问答对、表格结构、列表层级或代码语法。
- 不使用 embedding、rerank 或 LLM 判断语义边界。
- 不新增父子分块或返回更大父级上下文。
- 不保证超长单句、长标识符或无自然边界内容绝不被切分。
- 不在通用分块内部实现 OCR、语音转写或视频理解；这些能力通过文本抽取适配器接入。
- 不将无法产生文本的任意二进制文件伪装成可分块文件。

## 3. 产品定位与使用场景

策略卡片使用以下文案：

> **通用分块**
> 按分隔符和长度快速切分文档，适合格式规范、内容相对独立的资料。检索时直接返回命中的分块。

适用场景包括：

- 词条表、FAQ、新闻、公告和简短产品说明；
- 使用空行、换行、分号、`|||` 等固定标记组织的规范文本；
- 日志、导出文本和大规模资料的快速入库；
- 原型验证或只需要基础召回质量的知识库。

包含复杂标题层级、嵌套列表、表格或代码结构的 Markdown 文件仍可选择 `MARKDOWN_OPTIMIZED`。同一个 Markdown 文件必须同时显示通用与 Markdown 优化两种可用策略。

首发公开支持的文本抽取格式为 TXT、MD/Markdown、CSV、JSON、LOG、HTML、PDF、DOC/DOCX、XLS/XLSX、PPT/PPTX、RTF 和 EPUB。该列表描述抽取能力，不进入通用 planner 的条件分支；以后注册新抽取器即可扩大能力范围。

## 4. 用户流程与界面

继续使用现有 `ChunkingWorkspace` 的左侧配置、右侧预览布局。

知识库文件列表不再只按 Markdown 扩展名显示“分块管理”。页面先读取服务端文件能力：至少一个策略 `available=true` 时按钮可用；没有已注册抽取器时按钮保持禁用并显示“暂时无法从该文件提取文本”。上传成功只表示文件已保存，进入工作台后仍可能因加密、损坏、扫描件无 OCR 或内容为空得到具体失败信息。

### 4.1 左侧配置

用户选择“通用分块”后展示：

| 字段 | 默认值 | 行为 |
| --- | --- | --- |
| 分段标识符 | 换行（U+000A） | 普通字符串模式；输入框以 <code>&#92;n</code>、<code>&#92;n&#92;n</code>、<code>&#92;t</code> 表示控制字符 |
| 分隔符模式 | 普通字符串 | 高级设置可切换为受限正则 |
| 分段最大长度 | 500 characters | 最终索引文本的字符上限，包含 overlap 与固定格式符 |
| 分段重叠长度 | 40 characters | 设为 0 时关闭；必须小于最大长度 |
| 替换连续空白 | 开启 | 三个及以上换行压为两个；连续普通空格压为一个；制表符等转为空格 |
| 删除 URL | 关闭 | 删除完整 URL，避免留下协议或残片 |
| 删除邮箱 | 关闭 | 删除完整邮箱地址 |

最大长度变化时，界面计算 `round(maxCharacters * 0.15)` 并显示“建议约 N 字符”。“使用建议值”是显式操作；系统不会暗中修改当前 overlap。500 字符对应的建议值为 75，初始值仍为 40。

即时校验规则：

```text
64 <= maxCharacters <= 4000
0 <= overlapCharacters <= 1000
overlapCharacters + overlapFormatCharacters < maxCharacters
delimiter 不能为空
分隔符原始输入长度不超过 256 个字符
正则必须可编译且不能产生零宽匹配
```

上述范围由后端 descriptor 返回，前端不复制一套硬编码限制。重置恢复后端 descriptor 提供的默认值。

分隔符使用一种规范表示：浏览器输入框使用转义记法，<code>&#92;n</code>、<code>&#92;t</code>、<code>&#92;r</code> 和 <code>&#92;&#92;</code> 分别表示换行、制表、回车和一个反斜杠；提交前只解码一次。API 始终接收实际字符，由标准 JSON serializer 完成 wire 转义；后端配置和快照也保存实际字符。若要匹配字面量“反斜杠+n”，用户输入 <code>&#92;&#92;n</code>。加载快照时前端执行逆向转义，保证反复打开和保存不会重复解码。API 示例使用 <code>"delimiter": "&#92;u000A"</code> 明确表示实际换行。

### 4.2 右侧预览

右侧继续展示完整 Chunk 列表、正文、正文长度、实际 overlap、编辑、删除和保存状态，并增加：

- 长度单位随策略显示为“字符”或“Token”；
- 被强制字符切分的 Chunk 显示“因长度限制拆分”；
- token 预算使正文提前结束时显示“受模型长度限制提前拆分”；
- 清洗摘要显示删除的 URL、邮箱及归一化空白数量；
- 文件无指定分隔符时提示已使用兜底边界，不将其视为失败。

左侧参数变化后标记配置已修改，并要求重新生成预览。重新生成会覆盖人工修改时，继续沿用现有确认机制。用户可以在右侧逐块开关 overlap 或修改其上限；左侧 overlap 只负责新预览的初始值。

三个层次的取值来源固定如下：

1. 后端 descriptor 默认值只用于从未生成预览的文件以及“重置”；
2. `file_processing.context_policy` 是最近一次成功生成预览时的文件级默认值，负责恢复左侧表单；
3. `document_chunk.overlap_*` 是每个现有 Chunk 的实际设置，逐块修改只更新它，不回写左侧默认值。

重新生成预览时，左侧值写入 `context_policy`，并复制到所有新 Chunk。该顺序消除 descriptor、文件快照和逐块设置之间的覆盖歧义。

## 5. 总体架构

```text
原始文件快照
    │
    ▼
ChunkInputProviderRegistry（先按 strategyCode 路由）
    ├── GENERAL（通配文件类型）→ GeneralTextInputProvider
    │                 │
    │                 ▼
    │          DocumentTextExtractorRegistry
    │                 │
    │                 ▼
    │          ExtractedText + SourceMap
    │                 │
    │                 ▼
    │          TextNormalizer
    │                 │
    │                 ▼
    │          GeneralBoundaryScanner（用户分隔符）
    │                 │
    │                 ▼
    │          GeneralTextCleaner
    │                 │
    │                 ▼
    │          GeneralChunkPlanningStrategy
    │
    └── MARKDOWN_OPTIMIZED → 现有 MarkdownStructureParser 与 Markdown planner
                              │
                              ▼
                    ChunkDraft（正文所有权）
                              │
                              ▼
            document_chunk 预览、编辑、删除
                              │
                              ▼
        策略对应的 ChunkContextEnricher（生成 overlap）
                              │
                              ▼
             ChunkIndexContentBuilder → 向量库
```

现有 `DocumentStructureParserRegistry` 只按文件类型选择解析器并禁止同扩展名重复注册，无法支持同一 Markdown 文件同时选择通用和 Markdown 优化。新增的输入提供器先按 `strategyCode` 路由：`GENERAL` 注册一个文件类型通配 provider，专属策略再按文件类型匹配。新文件格式只需注册抽取器，不需要给 GENERAL 增加扩展名分支。

## 6. 组件边界

### 6.1 `ChunkInputProvider`

职责：根据策略把同一个源文件快照转换成统一的 `ParsedStructure`。调用者提供 `FileResource`、源哈希、策略代码和已经转换为强类型的配置。

- `GeneralTextInputProvider` 调用文本抽取、归一化、用户分隔符扫描和清洗，为每个清洗后片段生成一个 `PLAIN_TEXT` 类型、无标题路径的 `StructuredBlock`；终止边界和来源映射保存在 block attributes 中。
- `MarkdownStructureInputProvider` 包装现有 Markdown parser，保持当前行为。
- 输入提供器不决定最终 Chunk 长度，也不生成 overlap。

### 6.2 `DocumentTextExtractorRegistry`

职责：使用内容类型探测与文件特征选择抽取器，不能只相信扩展名。

- 纯文本抽取器处理 TXT、MD、CSV、JSON、日志等文本文件，并识别 BOM 与字符编码；
- 专用 PDF reader 处理 PDF 并尽量保留页码；
- Tika 处理 DOC/DOCX、XLS/XLSX、PPT/PPTX、HTML、RTF、EPUB 等能够提取文本的格式；
- OCR、语音转写等未来适配器仍输出相同的 `ExtractedText`。

抽取结果模型为：

```java
record ExtractedText(
    String text,
    String mediaType,
    String extractorId,
    String extractorVersion,
    List<SourceSpan> sourceSpans,
    Map<String, Object> metadata
) {}
```

`sourceSpans` 表示抽取文本偏移到页码、工作表、幻灯片或其他可用来源的映射。抽取器无法提供精确坐标时只保存真实可用的粒度，不伪造原文件字符偏移。

抽取选择是确定性的：先由 Tika Detector 探测实际 media type，再从 registry 选择唯一最高优先级适配器。纯文本及 GENERAL 下的 Markdown 使用纯文本抽取器，PDF 使用专用 PDF reader，其余受支持的 Office、HTML、RTF、EPUB 等使用 Tika。registry 在启动时拒绝同一 media type、同一优先级的重复注册。选定抽取器后，解析失败直接返回该抽取器的错误，不静默换另一个抽取器产生不同文本。

抽取缓存键为 `tenantId + fileId + sourceHash + extractorId + extractorVersion`。源哈希或抽取器版本改变时缓存失效；文件删除时同步删除。缓存不能跨租户共享。

### 6.3 `GeneralTextCleaner`

职责：应用用户选中的确定性清洗规则。输入是已经由 scanner 分开的 `DelimitedSegment`，输出是保持终止边界的 `CleanedSegment` 列表、来源映射和统计信息。

```java
record CleanedSegment(
    String text,
    int normalizedStart,
    int normalizedEnd,
    SourceLocator sourceLocator,
    BoundaryKind boundaryAfter
) {}

record CleaningResult(
    List<CleanedSegment> segments,
    CleaningStats stats
) {}
```

处理顺序：

1. `TextNormalizer` 无条件统一 `CRLF/CR` 为 `LF`，移除 NUL 和无法索引的控制字符；
2. `GeneralBoundaryScanner` 在归一化文本上定位并消费用户分隔符，输出 `DelimitedSegment`；
3. cleaner 在每个 segment 内部应用空白、URL 和邮箱规则；
4. cleaner 删除清洗后为空的 segment，保留前一有效 segment 的终止边界并记录删除数量。

先定位分隔符可以避免空白清洗破坏 `\t` 或多换行分隔规则。显式分隔符本身不进入正文；合并相邻短片段时以一个 `\n` 连接。用于超长兜底的句末标点属于正文并保留。

URL 和邮箱删除使用单个空格替换匹配内容，再交给空白规则收敛，避免删除后把两侧单词拼接成新词。清洗统计记录匹配数量和被替换字符数。

清洗组件不读取文件类型，不主动删除标点、编号、列表符、数学符号或代码运算符。清洗开关改变后重新使用抽取缓存，不重新解析原文件。

### 6.4 `GeneralBoundaryScanner`

这是通用策略复用的边界工具，不承担文档语义识别。配置阶段的一次扫描输出：

```java
record DelimitedSegment(
    String text,
    int normalizedStart,
    int normalizedEnd,
    SourceLocator sourceLocator,
    BoundaryKind boundaryAfter
) {}
```

cleaner 把它转换为字段对应的 `CleanedSegment`，随后 `GeneralTextInputProvider` 为每个有效 segment 生成 `StructuredBlock`。当某个 block 超长时，planner 再调用 scanner 的兜底边界扫描，输出轻量单元：

```java
record BoundaryUnit(
    String text,
    int cleanedStart,
    int cleanedEnd,
    SourceLocator sourceLocator,
    BoundaryKind boundaryAfter
) {}
```

`BoundaryKind` 只包含：

- `USER_DELIMITER`
- `LINE_BREAK`
- `SENTENCE_END`
- `WHITESPACE`
- `FORCED_CHARACTER`

普通字符串分隔符按字面量匹配。高级正则采用线性时间正则能力，拒绝回溯特性；编译后或规划扫描中发现任何零宽匹配时返回 422。每次请求只编译一次。通用策略可以复用现有中英文句界工具，但不复用 Markdown 的标题、容器和边界评分规则。

### 6.5 `GeneralChunkPlanningStrategy`

职责：按原始顺序组合 `BoundaryUnit`，生成正文归属唯一的 `ChunkDraft`。算法只依赖字符预算、token 计数器和公共模型。

`BoundaryUnit` 转换为现有 `SemanticUnit` 后再进入统一规划接口，从而复用 `ChunkDraft`、`SourceLocator` 和边界原因持久化。通用策略不向 `SemanticUnit.attributes` 写结构评分，也不引入第二套 Chunk 生命周期。

`ChunkPlanningStrategy.plan` 改为接收 `ChunkPlanningRequest(ParsedStructure, ChunkStrategyConfig, ContextConfig, maxIndexTokens)`。现有 `ChunkPolicy` 实现 `ChunkStrategyConfig`，新增 `GeneralChunkConfig` 实现相同接口；registry 根据策略代码完成 JSON 到具体配置类型的转换，planner 不接收原始 Map。异步预览 Job 保存已校验的强类型策略配置、上下文配置、策略代码和 token 上限，不能在 worker 中重新解释可变请求 JSON。

## 7. 确定性分块算法

字符数定义为 Unicode code point 数量，不使用 Java UTF-16 `String.length()` 直接截断，从而避免生成无效代理项。

### 7.1 初始片段

1. `GeneralTextInputProvider` 按第 6.3 节的固定顺序完成归一化、分隔符扫描和清洗，分隔符被消耗。
2. 每个 `CleanedSegment` 变成一个有 `USER_DELIMITER` 终止边界的有序 `StructuredBlock`，planner 不再重复匹配分隔符。
3. 若全文没有匹配分隔符，provider 输出包含全文的一个 block，并记录 `delimiterMatched=false`。

### 7.2 组合与递归拆分

第一块正文预算为 `maxCharacters`。后续块在生成边界时按以下公式为用户配置的 overlap 预留固定窗口：

```text
bodyCharacterBudget = maxCharacters
    - configuredOverlapCharacters
    - overlapFormatCharacters
```

`configuredOverlapCharacters = contextConfig.enabled ? contextConfig.limit : 0`。`overlapFormatCharacters` 是 `ChunkIndexContentBuilder` 加入的“上文：”标签和换行所占的 Unicode code point 数；overlap 关闭时为 0。所有长度计算都针对最终实际格式文本，不能只计算正文与 overlap 的裸文本。

这个预留只决定自动生成时稳定的窗口步长，不会删除正文。前一块过短或 token 预算使实际 overlap 小于配置值时，本次规划不回填未使用空间，也不重新移动已经确定的正文边界。这样相同输入、配置和版本始终产生相同边界。第 8 节的“正文优先”适用于实际 overlap 生成、人工编辑和 token 二次校验：系统可以缩短复制文本，但不能为了凑足 overlap 截断正文。

按顺序把短片段装入当前块；片段之间使用一个 `\n`。加入下一片段会超出正文预算时结束当前块。单个片段自身超出预算时按以下顺序递归拆分：

1. 换行；
2. 中英文句末标点；
3. 空白；
4. Unicode code point 安全边界。

前三种边界都无法满足限制时才产生 `FORCED_CHARACTER`。算法不得丢弃、重排或重复正文；除显式分隔符与启用的清洗规则外，所有有效输入字符必须且只属于一个正文 Chunk。

### 7.3 token 二次约束

字符规划完成后，使用当前 embedding 模型对应的 tokenizer 检查正文：

```text
token(body) <= maxIndexTokens <= 512
```

不满足时只对当前正文继续执行上述递归拆分；不能截断或丢弃内容。由 token 约束触发的边界标记为 `MODEL_TOKEN_LIMIT`。通用分块没有标题路径，因此不会生成 Markdown 的“标题：”前缀。

planner 此时只保证正文自身合法，并按第 7.2 节为配置的 overlap 预留字符窗口；它不生成或读取实际 overlap。持久化后的 enricher 使用相同的最终 formatter 加入 overlap，并缩短它直至完整索引文本同时满足字符和 token 上限。enricher 不能回头修改正文边界。

规划器保存实际使用的 tokenizer ID 和硬上限。以后 embedding 模型限制变化时，已有预览继续使用自己的配置快照；确认向量化前仍需针对当前模型复核，无法满足时要求重新生成预览。

## 8. Overlap 规则

通用 overlap 使用字符单位，表示“最多从上一块独立正文末尾复制多少字符”。它不要求完整句，也不检查标题或 Markdown 容器。

生成规则：

1. 第一块没有 overlap。
2. 后续块从前一块 `content` 末尾取最多 `overlapCharacters` 个 Unicode code point。
3. overlap 只从独立正文读取，不能包含前一块自己的 overlap，防止重复链式扩散。
4. 最终 `overlap + body + 固定格式符` 必须同时满足 `maxCharacters` 和 512-token 上限。
5. 预算不足时正文拥有优先权：保留正文，缩短 overlap，并在响应中返回实际字符数和缩短原因；空间连“上文：”及换行都容不下时不生成 overlap。正文自身超限时不靠缩短 overlap 掩盖错误。
6. 前一块被编辑后，重新生成当前块的 overlap；前一块被删除后，不跨越原位置自动连接更早的块。

默认 `overlapCharacters=40` 且开启。设置为 0 或逐块关闭时不生成。15% 仅用于 UI 建议：

```text
recommendedOverlap = round(maxCharacters * 0.15)
```

现有 Markdown overlap 保持完整句、token 单位和结构边界规则。`ChunkContextEnricher` 根据分块策略选择 `CHARACTER_TAIL` 或 `COMPLETE_SENTENCE`，同一 Chunk 只执行一种 overlap 策略。

生成 GENERAL 预览时，每个新 Chunk 都持久化本次已校验的 `contextConfig.enabled/limit`，单位固定为 `CHARACTERS`；40 只是 descriptor 初始默认值。第一块保留该用户设置，但实际 overlap 为空并返回 `FIRST_CHUNK`。文件级 limit 为 0 时，所有新 Chunk 写为 `enabled=false, limit=0`。切换策略或重新生成会删除旧草稿并按新策略重新初始化；已有预览的确认、重试和 reindex 只读取逐块字段，不再读取文件级默认值决定实际 overlap。

持久化正文不包含 overlap。`overlap_content` 保存服务端实际生成的补充文本，`index_content` 只拼接一次 overlap 和正文。预览展示、embedding 输入和检索返回使用同一份 `index_content`。

## 9. API 与策略能力契约

当前 `PreviewRequest.strategyConfig` 固定为 `ChunkPolicy(minTokens,targetTokens,maxTokens)`，无法表达通用策略。改为策略定义负责解析和校验配置：

```json
{
  "strategyCode": "GENERAL",
  "strategyConfig": {
    "delimiter": "\u000A",
    "delimiterMode": "LITERAL",
    "maxCharacters": 500,
    "collapseWhitespace": true,
    "removeUrls": false,
    "removeEmails": false
  },
  "contextConfig": {
    "enabled": true,
    "limit": 40
  },
  "replaceEditedDrafts": false,
  "lockVersion": 3
}
```

后端 registry 中每个策略提供：

- 策略代码、显示范围和适用能力；
- 配置字段 descriptor、默认值与校验器；
- 配置 JSON 到强类型配置的转换；
- 输入提供器、规划器和 overlap 策略。

`contextConfig` 是预览请求中的独立公共对象。服务端根据策略固定它的单位和模式：GENERAL 使用 `CHARACTERS + CHARACTER_TAIL`，Markdown 使用 `TOKENS + COMPLETE_SENTENCE`；客户端不能提交模式或单位覆盖值。

`GENERAL` 的适用性由文本抽取能力决定，descriptor 的 scope 为 `GLOBAL`。能力接口为当前文件返回 `available`、`reason` 和配置 descriptor。若抽取器只在执行时才发现加密或无文本，生成预览返回具体失败原因。

前端删除 `GENERAL` 的本地强制禁用和 API 拦截，以后不再把本地目录中的 `implemented` 作为执行能力来源。策略标题和说明可以由前端维护，是否可用以服务端响应为准。

首版新增显式的 `GeneralStrategyConfig.vue`，与现有 `MarkdownStrategyConfig.vue` 一样由策略代码选择组件，不实现通用动态表单。后端 descriptor 继续使用扁平 `ConfigField`，GENERAL 返回 `delimiter`、`delimiterMode`、`maxCharacters`、`collapseWhitespace`、`removeUrls` 和 `removeEmails` 的默认值与单字段范围；`defaultContextConfig` 单独返回默认 40。跨字段约束由显式前端组件即时提示，后端策略校验器作最终裁决。

受限正则属于高级区，但仍包含在首版。语法以项目锁定版本的 RE2/J 为准：使用 Java String 上的 Unicode 文本，不提供独立 flags 字段，允许引擎支持的内联 flags，不支持反向引用、lookaround 等非线性或引擎未实现的结构。模式最长 256 个 Unicode code point，任何零宽匹配均被拒绝。配置错误统一返回：

```json
{
  "code": "INVALID_STRATEGY_CONFIG",
  "fieldErrors": {
    "delimiter": "分隔符正则不能产生零宽匹配"
  }
}
```

编辑 Chunk 的 overlap 请求改为通用字段：

```json
{
  "content": "...",
  "overlapEnabled": true,
  "overlapLimit": 40,
  "overlapUnit": "CHARACTERS",
  "lockVersion": 8
}
```

服务端校验 `overlapUnit` 必须与该 Chunk 的策略快照一致，客户端不能把 Markdown 的 token 单位切换为字符单位。

`ChunkResponse` 增加以下可选字段，Markdown 响应也用同一结构：

```json
{
  "lengthUnit": "CHARACTERS",
  "bodyLength": 436,
  "indexLength": 500,
  "overlapLimit": 40,
  "overlapActualLength": 36,
  "overlapTokenCount": 29,
  "overlapCharacterCount": 36,
  "overlapReductionReason": "MODEL_TOKEN_LIMIT",
  "boundaryReason": {
    "start": "USER_DELIMITER",
    "end": "MODEL_TOKEN_LIMIT",
    "forcedSplit": false
  }
}
```

现有 `overlapContent` 继续返回实际补充文本。`ProcessingResponse` 增加预览级 `preprocessingSummary`、`delimiterMatched`、`forcedSplitCount` 和 `tokenLimitedSplitCount`，供右侧顶部状态和清洗摘要使用。新字段必须来自已持久化预览，前端不自行重算。

## 10. 数据与兼容性

`file_processing.policy_snapshot` 只保存可编辑的通用策略配置：

```json
{
  "delimiter": "\u000A",
  "delimiterMode": "LITERAL",
  "maxCharacters": 500,
  "collapseWhitespace": true,
  "removeUrls": false,
  "removeEmails": false
}
```

`strategy_code` 和 `planner_version` 继续使用 `file_processing` 的独立字段。`policy_snapshot` 有意不重复保存 overlap；`context_policy` 是文件级 overlap 默认值的唯一来源：

```json
{
  "enabled": true,
  "mode": "CHARACTER_TAIL",
  "limit": 40,
  "unit": "CHARACTERS"
}
```

前端恢复表单时合并 `policy_snapshot` 与 `context_policy`。策略参数、抽取器和 tokenizer 版本都参与结果复现；每个 `document_chunk` 保存逐块最终 overlap 设置与实际内容。

抽取器和 tokenizer 属于只读执行元数据，新增 `file_processing.execution_metadata` 保存：

```json
{
  "extractorId": "tika",
  "extractorVersion": "...",
  "tokenizerId": "BAAI/bge-base-zh-v1.5@7dfbf196",
  "tokenHardLimit": 512
}
```

旧 Markdown 快照中的 `tokenizer` 读取时回退兼容，迁移后新预览统一写入 `execution_metadata`。

新增 `ChunkRuntimePolicyResolver`，根据 `strategy_code + policy_snapshot + context_policy + execution_metadata` 生成编辑、确认、reindex 和异步 worker 共用的强类型运行策略。GENERAL 使用 `maxCharacters + maxIndexTokens`，Markdown 使用 `maxTokens`；旧 Markdown 没有 `execution_metadata` 时将 `policy_snapshot.maxTokens` 同时作为 token 上限。业务服务不能继续直接从 JSON 读取单个 `maxTokens` 字段。

向量化 Job 固化 `strategyCode`、`plannerVersion`、`policySnapshot`、`executionMetadata`、文件 lockVersion 和待处理 Chunk 版本。worker 不读取可能已被后续预览覆盖的新默认配置；任一版本不匹配时按现有冲突恢复流程停止。

数据库将 `overlap_token_limit` 迁移为通用 `overlap_limit`，并新增 `overlap_unit`、两个实际计数字段和缩短原因：

```text
overlap_limit integer not null
overlap_unit varchar not null  // TOKENS | CHARACTERS
overlap_token_count integer not null
overlap_character_count integer not null
overlap_reduction_reason varchar null
```

迁移时删除旧的 `1..512` 单位无关检查，并建立单位相关约束：TOKENS 为 `0..512`，CHARACTERS 为 `0..1000`；`overlap_enabled=true` 时 limit 必须大于 0。所有现有记录设置为 `TOKENS`，数值与 `overlap_token_count` 保持不变，并从现有 `overlap_content` 计算字符数。GENERAL 的 limit 为 0 时服务端规范化为 `enabled=false`。

API 请求在迁移版本中接受旧字段 `overlapTokenLimit` 作为 `overlapLimit + TOKENS` 的别名；若新旧字段同时出现且值冲突则返回 422。响应只返回新字段，前后端在同一版本完成升级。Markdown 已有数据、策略快照和检索文本不得改变。现有预览持久化中固定写入 `false/40` 的逻辑改为读取策略的 `ContextConfig`。

`file_processing.preview_summary` 持久化 `preprocessingSummary`、`delimiterMatched`、forced split 数和 token-limited split 数；processing API 只返回这份已保存的生成结果。

抽取缓存采用 `file_text_extraction` 一对一记录：保存 tenant、file、source hash、extractor ID/version、media type、受管文本路径、来源映射路径、字符数和更新时间。抽取文本与来源映射写入文件存储中的租户隔离目录，先写临时文件再原子替换；文件记录删除时同步删除缓存记录和受管文件。它不是 Chunk 历史版本。预览重新生成仍替换当前草稿，不新增版本系统。

## 11. 错误处理与恢复

| 场景 | 行为 |
| --- | --- |
| 原文件为零字节 | 进入 FAILED，提示“文件为空” |
| 抽取结果为空 | 提示“未提取到可分块文字”；扫描件提示先启用 OCR |
| 文件加密 | 提示文件已加密，不能解析 |
| 文件损坏或格式不支持 | 返回抽取器提供的安全摘要，不暴露堆栈 |
| 文本编码不确定 | 使用探测结果；无法可靠解码时失败，不使用平台默认编码 |
| 分隔符未命中 | 正常生成，提示使用兜底边界 |
| 正则非法或可匹配空串 | 请求返回 422，并定位到分隔符字段 |
| 清洗后为空 | 请求失败并展示各清洗规则删除量 |
| 单元超过字符或 token 上限 | 递归拆分并标记原因，不截断 |
| 人工编辑后正文超过字符或 token 上限 | PATCH 返回 422，包含正文字符数、token 数及各自上限；不保存、不截断 |
| 配置或源文件在处理期间变化 | 沿用 sourceHash 与 lockVersion 冲突处理 |
| 抽取缓存失效 | 重新抽取，不使用旧文本继续分块 |

当前预览 worker 在读取任何文件时都会尝试用 UTF-8 把原始二进制解码后判断空白。通用输入上线时应改为：源阶段只检查字节数，文本阶段检查抽取结果，避免把二进制文件当文本验证。

编辑服务使用 `ChunkRuntimePolicyResolver.validateEditedBody` 检查正文。正文合法时，在同一数据库事务中保存正文并基于相同版本重算本块与下一块的实际 overlap；任何计算失败都回滚。下一块因此改变时同时置为待重新索引。确认向量化在切换状态前同步构建并验证所有最终索引文本；异步 worker 在调用向量服务前重新执行同一校验。单块 reindex 必须先成功生成新索引文本，再删除旧向量，避免配置或格式错误先使旧向量丢失。

失败恢复沿用 `file_processing.failed_from_state` 和异步任务重试。失败摘要不记录完整原文、URL、邮箱或其他知识库内容。

## 12. 性能与安全

通用 planner 对清洗后文本执行顺序扫描，复杂度目标为 O(n)，不对每个候选边界重新扫描全文。分隔规则每次请求只编译一次；批量持久化 Chunk，避免逐块往返数据库。

记录以下阶段耗时与数量，不记录正文：

- `extract_duration_ms`
- `clean_duration_ms`
- `chunk_duration_ms`
- `persist_duration_ms`
- 输入字符数、Chunk 数、强制切分数、token 二次切分数
- 抽取缓存命中与否

“分块速度”只计算已抽取文本的清洗和规划，不将 OCR、Office/PDF 解析或 embedding 耗时混入。发布前使用 1 MB 和 10 MB 的 TXT、HTML、PDF 抽取文本建立基准，并确认输入扩大时耗时和内存没有非线性增长；基准结果随实现计划记录，不在设计阶段承诺与运行环境无关的吞吐数字。

文本抽取必须限制源文件大小、压缩嵌套深度、解析时间和最大输出字符数。临时快照始终在 finally 中删除。用户正则使用受限线性时间语法，避免回溯型拒绝服务。所有缓存、能力查询和 Chunk 操作继续校验 tenant、knowledge 和 file 范围。

## 13. 测试与验收

### 13.1 分块与清洗单元测试

- `\n`、`\n\n`、中文标点和自定义 `|||` 分隔；
- 受限正则的正常、多候选、非法和空匹配情况；
- 分隔符不存在时的递归兜底；
- 短片段组合、单片段超长和 forced character；
- 中英文、emoji、代理项及混合换行；
- 空白、URL 和邮箱三种清洗规则单独及组合启用；
- 清洗前后 SourceLocator 映射和统计；
- 除分隔符及配置清洗内容外，正文连接后与有效输入完全一致。

### 13.2 overlap 单元测试

- 第一块无 overlap，后续块默认最多 40 字符；
- overlap 不包含上一块 overlap；
- 设置为 0、逐块关闭、前块为空或删除；
- 最大字符数不足和 512-token 不足时缩短 overlap；
- 编辑前块后当前块重算；
- Markdown 继续按完整句和 token 工作，不受字符策略影响。

### 13.3 后端集成测试

- TXT、MD、PDF、DOCX、HTML 使用 GENERAL 生成预览；
- CSV、JSON、LOG、DOC、XLS/XLSX、PPT/PPTX、RTF、EPUB 均有参数化抽取契约测试；
- MD 同时提供 GENERAL 和 MARKDOWN_OPTIMIZED，选择不同策略得到对应行为；
- 加密 PDF、扫描 PDF、损坏文件和无文本文件返回明确错误；
- 配置快照恢复、抽取缓存命中及源哈希失效；
- 人工编辑、删除、重新预览、确认向量化和失败重试；
- 最终 `index_content` 同时符合字符与 token 上限；
- 现有 Markdown 数据迁移后 overlap 仍以 token 解释；
- 租户隔离和 lockVersion 冲突。

### 13.4 前端测试

- 后端返回 GENERAL 能力后卡片可选，API 不再本地拒绝；
- 根据 descriptor 渲染默认参数、校验和重置；
- 15% 建议随最大长度更新，但不覆盖已输入值；
- 参数修改后的预览失效提示；
- 字符与 Token 单位正确切换；
- 展示实际 overlap、缩短原因、清洗摘要和强制拆分原因；
- 非 Markdown 文件能从知识库详情进入分块工作台；
- Markdown 双策略选择和原有工作流回归。

### 13.5 完成标准

- 通用策略不调用 LLM、embedding 或 rerank 参与边界规划；
- 所有由当前 registry 注册、能力探测通过且成功抽取文本的文件都进入同一通用规划器；每个对外宣称支持的格式都有参数化抽取契约测试；
- 分块结果确定：相同源哈希、配置和版本产生相同正文与来源定位；
- 正文无非配置性遗漏、乱序和重复；
- overlap 默认 40 字符，界面提供 15% 建议且不自动覆盖；
- 预览、向量化和检索使用同一份最终索引文本；
- 已有 Markdown 分块、逐块编辑、向量化和检索行为保持兼容；
- 后端打包、前端构建及相关测试全部通过。

## 14. 实施拆分建议

实施计划按以下顺序展开，任一步都保持已有 Markdown 流程可运行：

1. 配置契约与 overlap 单位的数据迁移；
2. 文本抽取接口、Tika/PDF/纯文本适配器和缓存；
3. 通用清洗、边界扫描、规划和字符 overlap；
4. 策略感知的输入路由及 API 能力返回；
5. 通用策略前端配置和预览集成；
6. 知识库文件入口开放、兼容验证和性能基准。
