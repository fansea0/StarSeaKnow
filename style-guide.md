# Style Guide: StarSeaKnow

> Extracted from the current front-end source for Task 6. Every value below is traceable to the cited file and line. This document describes the existing sea-themed workspace language used by the knowledge and chunking flows; it does not prescribe a redesign.

## Stack

- Framework: Vue 3.5.13 with Vue Router 4.5.1 and Pinia 2.3.1.
  - Source: `web/package.json:12-18`
- Build tool: Vite 6.3.1.
  - Source: `web/package.json:20-25`
- Component library: Element Plus 2.9.9, with its CSS variables overridden globally.
  - Source: `web/package.json:12-18`; `web/src/style.css:12-53`
- Styling: global CSS in `web/src/style.css`, plus scoped `<style>` blocks in Vue SFCs; no Tailwind/theme/token configuration file was found in `web/`.
  - Source: `web/src/style.css:1-81`; `web/src/views/ChunkingWorkspace.vue:657-748`
- Design tokens: `web/src/style.css:3-53`.

## Color System

### Brand colors

- `--sea-signal` — `#00A6A6`; primary action, selected state, active indicators, and success-completion mark.
  - Source: `web/src/style.css:7`; `web/src/views/ChunkingWorkspace.vue:722-727`; `web/src/components/chunking/ChunkStrategyPanel.vue:122-126`
- `--sea-deep` — `#11243B`; main heading and emphatic text, plus the dark active tab background.
  - Source: `web/src/style.css:4`; `web/src/views/ChunkingWorkspace.vue:669`; `web/src/views/KnowledgeDetail.vue:779`
- `--sea-sand` — `#E9C98B`; warm accent used in the application sidebar border/mark treatment.
  - Source: `web/src/style.css:8`; `web/src/App.vue:162,177-180`

### Neutral palette

- `--sea-paper` — `#FCFDFC`; card, panel, dialog, and input surface.
  - Source: `web/src/style.css:6,33,35,41`; `web/src/style.css:74`; `web/src/components/chunking/ChunkCard.vue:390-395`
- `--sea-mist` — `#EAF1F5`; page background and soft summary surface.
  - Source: `web/src/style.css:5,36,60`; `web/src/components/chunking/ContextConfirmDialog.vue:76-79`
- `--sea-ink` — `#19304A`; default body/interactive text.
  - Source: `web/src/style.css:9,26,45,60`; `web/src/components/chunking/ChunkCard.vue:433-434`
- `--sea-muted` — `#64748B`; captions, secondary text, metadata, and subdued controls.
  - Source: `web/src/style.css:10,28`; `web/src/components/chunking/ChunkPreviewPanel.vue:111-127`
- Element Plus regular text — `#3e5269`; table cell text.
  - Source: `web/src/style.css:27`; `web/src/views/KnowledgeDetail.vue:827-833`
- Element Plus borders — `#cedbe2` / `#dfe8ec` / `#e8eff2`; controls and separating rules.
  - Source: `web/src/style.css:30-32`
- Workspace-panel border — `#d5e1e6`; shared outer workbench boundary.
  - Source: `web/src/style.css:74`; `web/src/views/KnowledgeDetail.vue:699-707`

### Semantic colors

- error: `--sea-danger` — `#C8524E`; errors, destructive feedback, and error buttons.
  - Source: `web/src/style.css:11,19-25`; `web/src/views/ChunkingWorkspace.vue:680-701`; `web/src/components/chunking/ChunkCard.vue:469-478`
- success/status: `#10ae77`; successful embedding status in the knowledge workbench.
  - Source: `web/src/views/KnowledgeDetail.vue:889-905`
- positive Element Plus tint scale: `#5cc4c4`, `#8ad6d6`, `#b8e7e7`, `#d0efef`, `#e8f8f8`, and dark `#008585`.
  - Source: `web/src/style.css:13-18`
- danger Element Plus tint scale: `#d98582`, `#e4a8a5`, `#efcbc9`, `#f4d9d7`, `#faeceb`, and dark `#a6423f`.
  - Source: `web/src/style.css:20-25`

### Derived surface treatments

- Selected/hovered strategy: `color-mix(in srgb, var(--sea-signal) 6%, var(--sea-paper))` with a 52% signal border mix.
  - Source: `web/src/components/chunking/ChunkStrategyPanel.vue:122-126`
- Informational processing strip: 6% signal surface and 22% signal border mix.
  - Source: `web/src/components/chunking/ChunkPreviewPanel.vue:129-143`
- Error notice: 7% danger surface and 32% danger border mix.
  - Source: `web/src/views/ChunkingWorkspace.vue:680-695`
- Completion notice: 10% signal-to-paper gradient with a 34% signal border mix.
  - Source: `web/src/views/ChunkingWorkspace.vue:703-714`
- Non-tokenized table and empty-state lines include `#dfe9ee`, `#edf2f5`, `#bdd0d8`, and `#c9d6dd` in the knowledge pages. They are local structural colors rather than global variables.
  - Source: `web/src/views/KnowledgeDetail.vue:615,627,829,934`; `web/src/views/Knowledge.vue:48-50`

## Typography

- Display font: `Noto Serif SC`, serif; used for page titles, workbench titles, and prominent metrics.
  - Source: `web/src/style.css:1,72`; `web/src/views/KnowledgeDetail.vue:650-657,681-687`
- Body font: `Noto Sans SC`, sans-serif; applied to `body`, buttons, inputs, textareas, and selects.
  - Source: `web/src/style.css:1,59`
- Mono font: `JetBrains Mono`, monospace; used for compact English labels, identifiers, numeric summaries, and code.
  - Source: `web/src/style.css:1,68`; `web/src/views/KnowledgeDetail.vue:636-645`; `web/src/components/chunking/ContextConfirmDialog.vue:76-79`

Type scale from the active knowledge/chunking workspace:

| Pattern | Size | Line-height | Weight | Usage | Source |
|---|---:|---:|---:|---|---|
| Hero title | `clamp(28px, 3.4vw, 36px)` | `1.1` | `700` | Knowledge detail title | `web/src/views/KnowledgeDetail.vue:650-657` |
| Workspace title | `27px` | not declared | default | Chunking workspace title | `web/src/views/ChunkingWorkspace.vue:668-670` |
| Section title | `20px` | not declared | default | Strategy/preview headings | `web/src/components/chunking/ChunkStrategyPanel.vue:90-94`; `web/src/components/chunking/ChunkPreviewPanel.vue:109-112` |
| Card title/body emphasis | `14px` | body `1.78` | title default | Chunk content and strategy names | `web/src/components/chunking/ChunkCard.vue:433-434`; `web/src/components/chunking/ChunkStrategyPanel.vue:129-131` |
| Standard support copy | `13px` | `1.65` where declared | default | Descriptions and panel helper text | `web/src/components/chunking/ChunkStrategyPanel.vue:96-102`; `web/src/views/ChunkingWorkspace.vue:669-670` |
| Compact support copy | `12px` | `1.45`–`1.65` | default | Options, error text, overlap details | `web/src/components/chunking/ChunkCard.vue:447-477`; `web/src/components/chunking/MarkdownStrategyConfig.vue:95-123` |
| Metadata | `11px` | `1.45` where declared | default | Chunk IDs, state, and supplementary copy | `web/src/components/chunking/ChunkCard.vue:411-425,446-448,490` |
| Eyebrow/index | `9px`–`11px` | not declared | `600`/`700` | Uppercase mono labels and counts | `web/src/views/ChunkingWorkspace.vue:668,731`; `web/src/components/chunking/ChunkPreviewPanel.vue:109-110` |

## Spacing Scale

The code uses an informal, repeated 2px-based scale rather than global spacing variables: `2`, `3`, `4`, `5`, `6`, `7`, `8`, `10`, `12`, `13`, `14`, `15`, `16`, `18`, `20`, `22`, `24`, `26`, `28`, `32`, `36`, `40`, and `48px` all appear in the representative workspace styles.

- Outer desktop workspace main padding: `0 32px 36px`; tablet reduces horizontal padding to `24px`, mobile to `16px`.
  - Source: `web/src/App.vue:252-256,336-340,387-391`
- Shared full workbench: `12px` radius with internal panels at `26px` left padding and `28px` preview padding.
  - Source: `web/src/style.css:74`; `web/src/components/chunking/ChunkStrategyPanel.vue:76-81`; `web/src/components/chunking/ChunkPreviewPanel.vue:92-99`
- Strategy cards: `10px` list gap, `20px` gap above the list, `14px 15px` card padding.
  - Source: `web/src/components/chunking/ChunkStrategyPanel.vue:104-115`
- Preview cards: `14px` stack gap; body `17px 18px 13px`; footer `7px 14px 7px 18px`.
  - Source: `web/src/components/chunking/ChunkPreviewPanel.vue:114`; `web/src/components/chunking/ChunkCard.vue:433,480-488`
- Form fields: a `10px` grid gap and `6px` label-to-control gap in the markdown configuration.
  - Source: `web/src/components/chunking/MarkdownStrategyConfig.vue:101-113`
- Summary statistics: `10px` gap with `13px 10px` cell padding.
  - Source: `web/src/components/chunking/ContextConfirmDialog.vue:76-79`

## Border Radius

| Value | Current use | Source |
|---:|---|---|
| `999px` | Element Plus round controls | `web/src/style.css:38-40` |
| `50%` | Completion mark and status dots | `web/src/views/ChunkingWorkspace.vue:716-727`; `web/src/views/KnowledgeDetail.vue:896-901` |
| `12px` | Main workbenches, panels, dialogs | `web/src/style.css:74`; `web/src/views/KnowledgeDetail.vue:699-707`; `web/src/views/ModelProviders.vue:490-494` |
| `10px` | Chunk cards, completion banner, overview cards | `web/src/components/chunking/ChunkCard.vue:390-396`; `web/src/views/ChunkingWorkspace.vue:703-714`; `web/src/views/Knowledge.vue:47` |
| `9px` | Strategy cards, compact framed cards | `web/src/components/chunking/ChunkStrategyPanel.vue:106-120`; `web/src/views/ModelProviders.vue:502` |
| `8px` | Inputs, information/error strips, summary cells | `web/src/style.css:38-39`; `web/src/components/chunking/ChunkPreviewPanel.vue:129-143`; `web/src/components/chunking/ContextConfirmDialog.vue:76-79` |
| `6px`–`7px` | Small tabs, filter chips, and compact input controls | `web/src/style.css:39`; `web/src/views/KnowledgeDetail.vue:766-803`; `web/src/views/ModelProviders.vue:483,498` |

## Shadows

| Value | Current use | Source |
|---|---|---|
| `0 8px 28px rgb(17 36 59 / 7%)` | Standard elevated workspace panel | `web/src/style.css:74`; `web/src/views/ModelProviders.vue:444` |
| `0 12px 32px rgb(17 36 59 / 8%)` | Knowledge-detail workbench | `web/src/views/KnowledgeDetail.vue:699-707` |
| `0 2px 8px color-mix(in srgb, var(--sea-deep) 5%, transparent)` | Chunk card | `web/src/components/chunking/ChunkCard.vue:390-396` |
| `0 6px 16px rgb(0 166 166 / 22%)` | Primary upload CTA | `web/src/views/KnowledgeDetail.vue:804-809` |
| `0 24px 70px rgb(17 36 59 / 24%)` | Modal | `web/src/views/ModelProviders.vue:490-494` |

## Z-Index Layers

| Layer | Value | Purpose | Source |
|---|---:|---|---|
| Modal mask | `100` | Agent workbench modal overlay | `web/src/components/agent/workbench.css:84` |
| Toast/feedback | `110` | Agent workbench feedback notice | `web/src/components/agent/workbench.css:81` |
| Model-provider modal | `2000` | Model-provider modal overlay | `web/src/views/ModelProviders.vue:490` |

## Component Patterns (from real usage)

### Generic two-pane workbench

- Main application layout reserves a `248px` sidebar and a flexible main column; the main content uses `32px` horizontal padding on desktop.
  - Source: `web/src/App.vue:137-153,252-256`
- A knowledge workbench uses a `200px 1fr` grid, `520px` minimum height, `#d5e1e6` border, `12px` radius, paper surface, and `0 12px 32px rgb(17 36 59 / 8%)` shadow.
  - Source: `web/src/views/KnowledgeDetail.vue:699-707`
- The chunking workbench follows the same outer `workspace-panel` shell but uses `minmax(320px, 2fr) minmax(0, 3fr)` and a `560px` minimum height.
  - Source: `web/src/style.css:74`; `web/src/views/ChunkingWorkspace.vue:672-678`

### Buttons

- Global interactive target minimum is `40px × 40px`; selects, inputs, radio controls, and switches are also given a `40px` minimum height.
  - Source: `web/src/style.css:62-67`
- Default Element Plus hover is `#e8f8f8` background, `#007d7d` text, and `--sea-signal` border.
  - Source: `web/src/style.css:47-49`
- Primary custom button is `--sea-signal` fill with white text; hover uses `#008f8f`. Common dimensions are `40px` min-height, `0 15px` padding, `8px` radius, and `600` weight.
  - Source: `web/src/views/ModelProviders.vue:483-487`
- Existing primary knowledge actions use a slightly denser `36px` min-height with `16px` horizontal padding and a teal shadow.
  - Source: `web/src/views/KnowledgeDetail.vue:804-809`

### Selectable strategy cards

- Full-width, `72px` minimum height cards have `14px 15px` padding, `9px` radius, paper surface, muted 22% border mix, and 150ms background/border transition.
  - Source: `web/src/components/chunking/ChunkStrategyPanel.vue:106-120`
- Selected and hover states use the 6% signal surface and a 52% signal border; disabled cards are `0.58` opacity.
  - Source: `web/src/components/chunking/ChunkStrategyPanel.vue:122-128`

### Chunk preview cards

- Cards use a `10px` radius, paper surface, light deep-blue shadow, and an 8% signal ribbon with 24% signal divider.
  - Source: `web/src/components/chunking/ChunkCard.vue:390-409`
- The ribbon puts an `11px`, `600`, `.08em` chunk ID next to a `12px` muted breadcrumb. The body uses `14px` ink text at `1.78` line-height.
  - Source: `web/src/components/chunking/ChunkCard.vue:411-434`
- Footer metadata uses `11px` muted text; adjacent Element Plus actions have a `5px` gap.
  - Source: `web/src/components/chunking/ChunkCard.vue:480-492`

### Forms and compact configuration

- Element Plus inputs inherit paper background, `#b9ccd5` border, signal hover/focus border, ink text, and `#91a1af` placeholder.
  - Source: `web/src/style.css:41-46`
- Markdown numeric fields are a three-column grid with `10px` gap, full-width controls, and `12px`/`600` muted labels.
  - Source: `web/src/components/chunking/MarkdownStrategyConfig.vue:101-115`
- Knowledge filters demonstrate compact controls: `36px` height, `0 12px` padding, `6px` radius, `#cedbe2` border, paper background, and `12.5px` text.
  - Source: `web/src/views/KnowledgeDetail.vue:781-803`

### Tables, feedback, and empty states

- Wide tables retain a horizontal scroll wrapper; the knowledge-detail table sets an `880px` minimum width, `12px 16px` headers, and `14px 16px` cells.
  - Source: `web/src/views/KnowledgeDetail.vue:811-833`
- Table headers use `#f3f7f9`, mono `10.5px` text, `.08em` tracking, and uppercase labels.
  - Source: `web/src/views/KnowledgeDetail.vue:815-826`
- Empty states are centered grids with 32–34px signal icons, deep headings, muted explanations, and roughly `280px`–`360px` minimum height depending on page scope.
  - Source: `web/src/components/chunking/ChunkPreviewPanel.vue:116-127`; `web/src/views/Knowledge.vue:50`; `web/src/views/KnowledgeDetail.vue:615-618`
- Completion feedback uses an explicit 38px round signal mark, mono 20px check, and a teal shadow.
  - Source: `web/src/views/ChunkingWorkspace.vue:703-733`

### Focus and motion

- The shared keyboard focus ring is `3px solid color-mix(in srgb, var(--sea-signal) 60%, white)` with `2px` offset.
  - Source: `web/src/style.css:57`
- Common interactive transitions are `150ms` or `160ms` ease; chunk strategy selection uses `150ms`, and knowledge navigation/table controls use `160ms`.
  - Source: `web/src/components/chunking/ChunkStrategyPanel.vue:119`; `web/src/views/KnowledgeDetail.vue:631,732,776,841,939`
- Reduced-motion preference disables scroll/transition/animation duration globally; the chunk strategy card also disables its transition explicitly.
  - Source: `web/src/style.css:80-81`; `web/src/components/chunking/ChunkStrategyPanel.vue:151`

## Responsive Breakpoints

| Maximum width | Existing behavior | Source |
|---:|---|---|
| `1024px` (and at least `721px`) | App shell sidebar changes to `196px`; main horizontal padding changes to `24px`. | `web/src/App.vue:336-340` |
| `960px` | Knowledge detail hero becomes one column; stat alignment changes left. | `web/src/views/KnowledgeDetail.vue:951-955` |
| `850px` | Chunking grid becomes one column; preview loses internal viewport scroll/sticky footer; strategy separator moves from right to bottom. | `web/src/views/ChunkingWorkspace.vue:736-738`; `web/src/components/chunking/ChunkPreviewPanel.vue:163-166`; `web/src/components/chunking/ChunkStrategyPanel.vue:145-147` |
| `720px` | Main app shell becomes a stacked layout with horizontal navigation; knowledge-detail workbench becomes one column and uses `20px 16px 24px` content padding. | `web/src/App.vue:342-391`; `web/src/views/KnowledgeDetail.vue:956-963` |
| `640px` | General page heading stacks; knowledge list toolbar wraps. | `web/src/style.css:79`; `web/src/views/Knowledge.vue:51` |
| `520px` | Chunking heading stacks; left/right panel padding becomes `20px 16px`; chunk card footer stacks; markdown token fields become one column. | `web/src/views/ChunkingWorkspace.vue:740-746`; `web/src/components/chunking/ChunkStrategyPanel.vue:149`; `web/src/components/chunking/ChunkPreviewPanel.vue:168-170`; `web/src/components/chunking/ChunkCard.vue:494-497`; `web/src/components/chunking/MarkdownStrategyConfig.vue:125-127` |

## Notes for Task 6 prototyping

- Use the `--sea-*` variables and the Element Plus overrides from `web/src/style.css:3-53`; they are the shared workspace tokens used by the existing knowledge and chunking experiences.
- Match the existing information hierarchy: small uppercase/mono teal index, deep serif title, muted support text, then a paper workbench with fine blue-grey boundaries.
  - Source: `web/src/views/ChunkingWorkspace.vue:660-678`; `web/src/components/chunking/ChunkPreviewPanel.vue:101-114`
- Preserve the current chunking interaction geometry: desktop strategy/preview ratio `2fr:3fr`, then stack at `850px`; use the `520px` rules for dense phone layouts.
  - Source: `web/src/views/ChunkingWorkspace.vue:672-676,736-746`
- Reuse the established card and feedback states instead of adding a second visual language: white/paper card, 8px–12px radius, teal-tinted selected/processing/success surface, and danger-tinted failure surface.
  - Source: `web/src/components/chunking/ChunkCard.vue:390-409`; `web/src/components/chunking/ChunkStrategyPanel.vue:122-128`; `web/src/views/ChunkingWorkspace.vue:680-714`
- The system/tenant pages also contain independent blue palettes such as `#526aaf` and `#182541`; these do not derive from the global sea tokens and are not the reference for a general chunking workspace.
  - Source: `web/src/views/system/Overview.vue:21`; `web/src/views/TenantProfile.vue:28`
