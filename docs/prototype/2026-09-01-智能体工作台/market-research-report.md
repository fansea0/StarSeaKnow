# Market Research：StarSeaKnow 智能体（Agent）模块

## Scope
- Product type：企业级 / 团队级 LLM 智能体工作台（self-hosted Web 应用）
- Core question：在已有「基础资料 + 知识库关联 + 流式对话调试」之上，要把 Agent 工作台重做成更专业、可承载多会话调试与提示词模板的形态。参考 Dify / MaxKB 等成熟产品，提取可复用的信息架构与交互模式，同时保留 StarSeaKnow 现有 sea 视觉主题。

## Similar Products Surveyed

| Product | URL | Page depth fetched | Notes |
|---------|-----|--------------------|-------|
| Dify | https://dify.ai / docs.dify.ai / agentlist.top 评测 | 首页 + 教程页 + 工作流构建教程 | 画布式工作流是核心，左侧窄导航 + 顶部「发布/运行/保存」+ 中央画布/配置 + 右侧调试/日志 |
| MaxKB | https://maxkb.cn / developer.qiniu.com 配置 | 首页 + 文档 + 配置说明 | 应用列表是卡片网格；进应用是「左配置 + 右调试预览」两栏，调试支持多会话与历史 |
| LangGraph Cloud / OpenAI AgentKit 等 | deepwiki langgraph-example-monorepo | UI 介绍页 | 同样「调试 + 状态可视化」双栏，强化 trace/graph 视图 |

排除（需要登录才能看核心 UI）：Open WebUI、Botpress、Yellow.ai Widget Builder、Flowise 主工作台。它们的视觉信息通过评测文章间接获得，但核心编辑页被登录墙挡住。

## Information Architecture Patterns
- **左侧窄导航 + 顶部项目切换**：Dify 把「工作区 / 应用 / 知识库 / 工具 / 日志 / 设置」放进左侧窄导航，顶部保留「+ 新建」+ 工作区切换器。MaxKB 类似但顶导 + 侧栏混合。
- **应用列表的「卡片网格 + 顶部筛选 + 创建入口」**：所有 agent 平台都是「搜索 + 状态/标签筛选 + 卡片」，新建通常是右上角突出的「+」或「从模板创建」。
- **进入应用编辑的「分栏 + Tab」布局**：Dify 用左侧 30% 配置 + 中央 50% 画布/调试 + 右侧 20% 调试/工具；MaxKB 用 50/50 左右两栏，调试在右；Dify 的工作流模式下还有顶部 Tab（编排 / 知识库 / 发布 / 监测）。
- **顶部「动作条」**：Dify 把发布/运行/保存/批量测试/导出 DSL 全部堆在画布顶部右侧；MaxKB 类似的发布/概览/嵌入放在右侧调试头部。
- **多会话与历史**：MaxKB、PC 端 Chat 模式固定 280px 左侧历史栏，调试侧边可折叠。

## Interaction Patterns
- **核心任务 ≤2 击**：列表 → 进入应用 → 直接看到调试面板，无中间页。StarSeaKnow 现在的「列表 → 详情工作台」就属于这个模式，保持。
- **渐进式披露**：基础资料一眼看完，模型配置折叠/抽屉（当前已经是这样）。Dify / MaxKB 进一步把高级能力（工具、变量、监测）放在 Tab 或抽屉里，不污染主面板。
- **保存即生效 / 强提示未保存**：Dify 在未保存时按钮高亮+提示，发布需显式「发布」才在生产生效。
- **变量与提示词模板**：Dify 用 Jinja-like 语法 + 变量下拉提示，提示词编辑器提供「变量插入」「历史版本」。这是我们这次重点要补的能力。
- **调试与生产一致**：MaxKB 直接在编辑器右侧给一个实时调试面板，问答不计入生产日志。

## Visual Style Tendencies
- **Dify**：偏轻、偏画布；主色蓝紫（#155EEF 之类），大留白，分栏用细 1px 灰线，节点用圆角矩形+小标题+图标，调试面板用浅灰底区分。排版以无衬线为主，强调信息密度低、留白足。
- **MaxKB**：偏中后台、偏表格；主色 #3370ff（Element Plus 蓝），Form 主导，分栏明确，状态/标签/按钮多。排版密度比 Dify 高 30-50%，更接近 admin dashboard。
- **共性**：都是浅色 + 单色品牌；卡片/面板用 8-12px 圆角；icon 一律用 outline 风格；hover/focus 用浅色填充而非明显投影。
- **不属于它们的**：深色玻璃拟态、霓虹渐变、动画复杂的过渡——这些是企业级 agent 工作台普遍回避的。

## Common Differentiators
- **Dify** 卖的是「可视化工作流 + DSL 导出/版本控制 + 插件市场 + MCP 双向」。
- **MaxKB** 卖的是「开箱即用 + 嵌入第三方 + 简洁的 RAG 应用 + 高级编排（函数库）」。
- **Open WebUI** 卖的是「更好的 ChatGPT 体验 + 个人/团队轻量 RAG」。
- **Dify / MaxKB 都强调**：模型中立（多供应商）、可自托管、可观测（log/monitoring）。

## Differentiation Opportunities (gaps the new product can fill)
- **1. Dify 太重、MaxKB 太工具化**：Dify 的工作流画布对纯 RAG 问答是 over-spec；MaxKB 没有「画布式编排」，遇到复杂场景只能靠函数。StarSeaKnow 处在中间，可以做「**表单为主 + 可选简易节点画布**」的混合工作台，第一版先强化表单 + 提示词模板 + 多会话调试，把画布留到第二版。
- **2. 多会话调试 + 历史几乎是 Dify / MaxKB 共识，但 StarSeaKnow 没有**：这是最直观的「对标 Dify/MaxKB 风格」就能补的能力。右侧调试支持多个会话 Tab，历史消息可命名、可对比、可一键回放 prompt。
- **3. 提示词模板与变量提示**：Dify 强、MaxKB 弱（仅一处文本框）。StarSeaKnow 现在是「开篇白 + 角色描述」两段大文本框，没有「插入变量」按钮、没有模板片段、没有「最近一次 / 历史版本」。这是这次改造里杠杆最大的体验升级。
- **4. 团队/租户视角下的安全与归属**：Dify / MaxKB 都有多工作区、成员管理；StarSeaKnow 已经有租户成员，但 agent 编辑器没体现「这个 agent 属于哪个租户、谁在编辑」。可以加一个右上角"归属/最近编辑"小卡。
- **5. 极简 ≠ 简陋**：Dify 留白多但功能密；StarSeaKnow 当前编辑器视觉信息密度也低，但功能少，导致留白显得空——这次改造要把"留白"和"功能深度"对齐。

## Implications for Prototype Design
- **Follow（行业共识，沿用即可）**：
  - 列表页 = 顶部「标题 + 创建按钮 + 搜索/筛选」+ 卡片网格；卡片显示头像、名称、描述、标签、状态、关联知识库数、最后调试时间。
  - 编辑器 = 左「导航/属性」+ 中「主工作区」+ 右「调试预览」三栏；Dify 风格的 Tab（编排/知识库/监测/发布）放在顶部。
  - 调试面板 = 多会话 Tab + 历史消息 + 流式渲染 + 一键「用此 prompt 重跑」。
  - 顶部动作条 = 保存 / 发布 / 运行 / 导出 / 历史版本。
- **Avoid（行业过度饱和区，StarSeaKnow 别卷）**：
  - 不要做完整的工作流画布（节点拖拽），至少第一版不做——会大幅增加成本，但用户场景里 RAG 问答是 90%。
  - 不要做插件市场 / MCP Server——和 StarSeaKnow 私有部署定位不匹配。
  - 不要做批量测试 / 灰度发布——超出当前用户体量。
- **Signature opportunity（差异化亮点）**：
  - **「提示词工作台」**：变量 `{{kb.snippet}}` 风格提示词编辑器 + 模板片段 + 变量自动联想（来自已绑定的知识库、用户输入、系统变量），右侧调试面板同步显示"本次 prompt 的实际渲染结果"。这是 Dify 强、MaxKB 弱、而 StarSeaKnow 现在空白的精确切入。
  - **「会话快照」**：调试时一键「冻结当前会话 + 命名 + 保存到 agent 名下」，后续可以从「历史会话」回放、对比、复制 prompt 改动。
