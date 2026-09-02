# Style Guide: StarSeaKnow

> Auto-generated from project code. Every value links to its source file.
> 主要作用：让 Agent 工作台新原型继续使用项目现成的「海」主题（sea 主题），不引入 Dify 蓝紫品牌色，保留 Noto Serif 标题 + Noto Sans 主体 + JetBrains Mono mono 的字体组合。

## Stack
- Framework: Vue 3.5.13 + Vue Router 4.5.1
- Styling: 原生 CSS（Element Plus 主题通过 CSS 变量重绑） + scoped style
- Detected design tokens at: `web/src/style.css`（全站 `:root`）
- 关键混合工具：`color-mix(in srgb, var(--sea-X) <N>%, var(--sea-Y))`

## Color System

### Brand / 主色（sea 主题）
| Token | Hex | 用途 | Source |
|-------|-----|------|--------|
| `--sea-deep` | `#11243B` | 主标题/深底背景（debug 头部） | `web/src/style.css:4` |
| `--sea-ink` | `#19304A` | 正文/段落/按钮字 | `web/src/style.css:9` |
| `--sea-signal` | `#00A6A6` | 主品牌色 / Element Plus primary / 链接 hover / focus | `web/src/style.css:7` |
| `--sea-sand` | `#E9C98B` | 强调色 / 「运行/保存」按钮底 / 警示边框 | `web/src/style.css:8` |
| `--sea-mist` | `#EAF1F5` | 页面底色 / 浅分区 | `web/src/style.css:5` |
| `--sea-paper` | `#FCFDFC` | 卡片/面板底色 | `web/src/style.css:6` |
| `--sea-muted` | `#64748B` | 次要文字 / 描边 / 注释 | `web/src/style.css:10` |
| `--sea-danger` | `#C8524E` | 错误 / 删除 | `web/src/style.css:11` |

> Dify 用蓝紫、MaxKB 用 #3370ff。**新原型必须用 sea-signal（青绿），不要引入蓝色品牌色**。`--el-color-primary` 已绑到 `--sea-signal`，Element Plus 组件也会自动跟随。

### 中性 / 灰阶（散落）
| Hex | 用途 | Source |
|-----|------|--------|
| `#d5e1e6` | `.workspace-panel` 描边 | `web/src/style.css:74` |
| `#cedbe2` | `--el-border-color` | `web/src/style.css:30` |
| `#dfe8ec` | 表格 / 浅描边 | `web/src/style.css:31,51` |
| `#e8eff2` | 极浅描边 | `web/src/style.css:32` |
| `#91a1af` | placeholder | `web/src/style.css:29,46` |
| `#3e5269` | `--el-text-color-regular` | `web/src/style.css:27` |

### 状态色
- success: 借用 sea-signal 系（`#12b982` 出现在 `Agent.vue:21` 指标图标里，但非常少见；建议新原型仍用 sea-signal）
- warning: 借用 sea-sand + 字色 `--el-color-warning` 默认
- error: `--sea-danger` `#C8524E`

## Typography

| 角色 | 字体 | Weight | Source |
|------|------|--------|--------|
| Display / 标题（h1/h2、modal 标题、card 标题） | `Noto Serif SC` | 600/700 | `web/src/style.css:1,72,73`；`Agent.vue:21` |
| Body / UI（正文、按钮、表格、表单） | `Noto Sans SC` | 400/500/600/700 | `web/src/style.css:1,59,62,63,64,65,66,67,68` |
| Mono（code、变量、token、prompt 调试预览） | `JetBrains Mono` | 400/500 | `web/src/style.css:1,68`；`AgentDetail.vue:524` |

### Type scale（出现频次倒序）
| 用途 | Size | Weight | Source |
|------|------|--------|--------|
| h1 / 页面标题 | `clamp(27px, 3vw, 34px)` | 700 (Serif) | `TenantApiCredentialDetail.vue:293` |
| h2 / 卡片标题 | 20px | 700 (Serif) | `TenantApiCredentialDetail.vue:293` |
| modal h2 | 23px | Serif | `TenantApiCredentials.vue:494` |
| 小节标题 | 16px / 14px | 700 | `AgentDetail.vue:343-348` |
| 正文 | 13px / 14px | 400/500 | 多处 |
| 辅助文字 / 注释 | 12px / 11px | 400 | 多处 |
| Eyebrow（页眉小字） | 10px letter-spacing .12-.18em weight 800 | `--sea-signal` | `TenantApiCredentials.vue:458`、`AgentDetail.vue:524` |

> **不要发明新的字号**。沿用 27/23/20/16/14/13/12/11/10 这一档。

## Spacing Scale

项目内间距粒度集中在 4 7 9 10 12 14 16 18 20 24 28 30 34。**没有统一 token**（如 `mt-4`），但视觉上服从 4 的倍数 + 7/9 这种小调整。
- 卡片/面板 padding: 16 / 18 / 20 / 24
- 字段间 gap: 7 / 10 / 12 / 14
- 行内 gap: 4 / 6 / 8
- Section 间分隔: 30 / 34（用 `border-top + padding-top` 而不是更大 margin）

## Border Radius
| 出现位置 | 值 | Source |
|---------|-----|--------|
| Element Plus 全局 | 6 / 8 / 999（pill） | `web/src/style.css:38-40` |
| `.workspace-panel` | 12px | `web/src/style.css:74` |
| `.agent-card` | 10px | `Agent.vue:21` |
| `.agent-hero` | 12px | `Agent.vue:21` |
| `.detail-workbench` | 10px | `AgentDetail.vue:312` |
| `.credential-modal` | 12px | `TenantApiCredentials.vue:491` |
| `.capability-card` | 8px | `TenantApiCredentials.vue:500` |
| `.icon-button` | 7px | `TenantApiCredentials.vue:495` |

> 三档最常用：**6/8 (小) — 10/12 (面板) — 999 (pill/tag)**。新组件沿用即可。

## Shadows
| Token | Value | 用途 | Source |
|-------|-------|------|--------|
| Element Plus 轻 | `0 8px 24px rgb(17 36 59 / 8%)` | dialog / popover | `web/src/style.css:37` |
| 标志性 panel 阴影 | `0 8px 28px rgb(17 36 59 / 7%)` | `.workspace-panel` | `web/src/style.css:74` |
| 卡片 hover | `0 8px 20px #00a6a614` | `.agent-card:hover` | `Agent.vue:21` |
| 主按钮 / 强调 | `0 8px 20px color-mix(in srgb, var(--sea-signal) 18%, transparent)` | `.sea-button--primary` | `TenantApiCredentials.vue:485` |
| 模态 | `0 24px 70px color-mix(in srgb, var(--sea-deep) 30%, transparent)` | `.sea-modal` | `TenantApiCredentials.vue:491` |

## Z-Index
| 层 | 值 | Source |
|----|----|--------|
| 默认 modal | 100 | `TenantApiCredentials.vue:489` |
| 嵌套 modal | 110 | `TenantApiCredentials.vue:490` |
| Element Plus dialog（自动） | 2000+ | 由 Element Plus 管理 |

## Component Patterns（项目内现成的"招牌组件"）

### `.workspace-panel`（**最重要，沿用**）
```
border: 1px solid #d5e1e6;
border-radius: 12px;
background: var(--sea-paper);
box-shadow: 0 8px 28px rgb(17 36 59 / 7%);
```
Source: `web/src/style.css:74`

### `.sea-button` / `.sea-button--primary` / `.sea-button--danger`
- min-height 38px, padding 8 14, border-radius 7px
- 字体 Noto Sans SC, size 13, weight 700
- primary 用 `--sea-signal` 边 + 底 + 字色 `--sea-paper`
- danger 用 `--sea-sand` 混合

### `.credential-card` / `.detail-card` / `.knowledge-link`
- 1px `color-mix(... var(--sea-muted) 20%, var(--sea-mist))` 描边
- border-radius 8-11px
- 背景 `var(--sea-paper)`

### `.eyebrow`（页眉小字）
- color `--sea-signal`, size 10px, weight 800, letter-spacing .16em, 全大写或全中文短词
- 用在"AGENTS" / "API 凭据" / "智能体" 之类的页眉标识

### `.page-heading`
- display flex, align-items flex-end, justify-content space-between
- h1 用 Serif 27-34px; 副标用 muted 14px

### Status pill
- 5-9px padding, 999px border-radius, sea-signal 浅底 + sea-deep 字
- 状态可选值：运行中 / 草稿 / 已发布 / 错误

### Variable 提示 / code
- 字体 `JetBrains Mono`
- 浅底：`color-mix(in srgb, var(--sea-mist) 56%, var(--sea-paper))`
- 1px 边 `color-mix(... muted 30%, mist)`

## Responsive Breakpoints

项目主要使用 `min-width` 桌面布局，仅在 `style.css:79` 有 `@media (max-width: 640px)` 把 `.page-heading` 改为 column。
- 列表页用 `repeat(3, minmax(0, 1fr))` grid（`Agent.vue:21`），中小屏缩到 2 列
- 编辑器三栏布局（`AgentDetail.vue:325-493`）在窄屏可考虑上下堆叠

## Notes for Prototyping

- **沿用 `:root` 9 个 sea token + Element Plus 已重绑主题**，不要在原型里硬编码新的 hex 色。
- **招牌组件** `.workspace-panel` (12px 圆角 + 大阴影 + sea-paper 底) 是 Dify 工作台"白底卡"的本地化版本，新原型的「编辑器主面板」「列表页 workspace」「侧栏」都建议直接用这个 class。
- **品牌色保持 sea-signal 青绿**。Dify 蓝紫、MaxKB 蓝色品牌色都**不要**引入。状态色用 sea-sand 提示、sea-danger 报错。
- **字体组合保留**：Serif 给标题/卡片标题，Sans 给 UI/正文，Mono 给 prompt/变量/代码。
- **Eyebrow 模式**：每个分区顶部用 10px 字 + letter-spacing + sea-signal 颜色写明分区名（"提示词编排 / 知识库 / 调试预览 / 历史快照"）。
- **Tab 顶部栏**：Dify 的顶部 Tab 模式用「下划线 + 字色变化 + active sea-signal + 字重 700」即可；不要做成 chip/pill 风格。
- **多会话/多版本列表**：左 280px 固定栏（参考 MaxKB PC 模式）+ 顶 60px 操作条，遵循 `AgentDetail.vue` 已有 workbench 模式。
- **阴影** 全部用 `color-mix(in srgb, var(--sea-deep) X%, transparent)` 而不要用固定 rgba。保持和现有面板一致。
- **不要在原型里堆彩色卡片**。sea 主题偏冷+轻，多用 `--sea-paper` 浅底 + 1px 浅描边 + 大留白。
- **反模式**：紫色/蓝紫渐变、玻璃拟态、霓虹色 box-shadow、emoji 作为状态图标（项目里所有图标都是文字/element-plus icon，不引入 emoji 图标）。
