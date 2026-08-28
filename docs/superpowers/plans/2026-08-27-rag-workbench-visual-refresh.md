# RAG 工作台视觉重塑 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将 Vue RAG 工作台升级为统一、可访问、响应式的「深海工作台」视觉系统，且不改变任何业务接口、权限或路由行为。

**Architecture:** 在 `style.css` 中建立全局令牌和 Element Plus 基础覆盖；`App.vue` 将认证页和工作区分流，并提供按角色显示的侧栏导航及潮汐线。各视图只承担本页的布局和内容，复用全局的页首、卡片、面板、表格与表单样式，保留既有业务逻辑与测试契约。

**Tech Stack:** Vue 3、Vue Router 4、Pinia、Element Plus、Vite、Vitest、Vue Test Utils。

**Spec:** `docs/superpowers/specs/2026-08-27-rag-workbench-visual-refresh-design.md`

## Global Constraints

- 不修改后端接口、HTTP 请求、路由地址、鉴权规则、数据字段或业务操作含义。
- 色彩固定为深水 `#11243B`、海雾 `#EAF1F5`、纸面 `#FCFDFC`、信号青 `#00A6A6`、航标砂 `#E9C98B`、岩墨 `#19304A`、潮灰 `#64748B`、珊瑚 `#C8524E`。
- 标题使用 `Noto Serif SC`，正文使用 `Noto Sans SC`，代码与邀请码使用 `JetBrains Mono`，均有系统回退。
- 所有可交互元素保持可见键盘焦点和至少 40px 的触控目标；`prefers-reduced-motion: reduce` 下禁用视觉过渡。
- 720px 以下将工作区导航转为顶部横向导航，卡片单列，详情纵向堆叠，表格可水平滚动。
- 每次提交仅包含本任务触及的文件，提交前运行相关 Vitest 和构建。

---

### Task 1: 建立全局设计令牌与可访问性基线

**Files:**
- Modify: `smart-agent-frontend/src/style.css`
- Test: `smart-agent-frontend/src/views/Register.spec.js`

**Interfaces:**
- Consumes: Vue 应用根节点 `#app` 与 Element Plus 的 CSS 变量。
- Produces: `--sea-*` CSS 自定义属性、全局 `.page-heading`、`.workspace-panel`、`.table-wrap`、`.empty-state` 和可复用的 Element Plus 覆盖。

- [ ] **Step 1: 写出验证全局样式已加载的失败测试**

在 `Register.spec.js` 中加入对注册页根卡片类名和登录链接可见性的断言，以确保后续认证页依然使用 `auth-card` 与可访问的链接：

```js
expect(wrapper.find('.auth-card').exists()).toBe(true)
expect(wrapper.get('.login-link a').attributes('href')).toBe('/login')
```

- [ ] **Step 2: 运行测试确认基线**

Run: `npm test -- Register.spec.js`

Expected: PASS；这是回归基线，现有注册流程没有因样式任务被修改。

- [ ] **Step 3: 在 `style.css` 实现令牌与基础规则**

移除现有把 `#app` 强制居中的规则，写入以下骨架，并补齐输入、按钮、弹窗、表格与焦点态的 Element Plus 变量覆盖：

```css
@import url('https://fonts.googleapis.com/css2?family=JetBrains+Mono:wght@400;500&family=Noto+Sans+SC:wght@400;500;600;700&family=Noto+Serif+SC:wght@600;700&display=swap');

:root {
  --sea-deep: #11243B;
  --sea-mist: #EAF1F5;
  --sea-paper: #FCFDFC;
  --sea-signal: #00A6A6;
  --sea-sand: #E9C98B;
  --sea-ink: #19304A;
  --sea-muted: #64748B;
  --sea-danger: #C8524E;
  --el-color-primary: var(--sea-signal);
  --el-color-danger: var(--sea-danger);
}

*:focus-visible { outline: 3px solid color-mix(in srgb, var(--sea-signal) 60%, white); outline-offset: 2px; }
@media (prefers-reduced-motion: reduce) { *, *::before, *::after { scroll-behavior: auto !important; transition-duration: 0s !important; animation-duration: 0s !important; } }
```

再以 `body`、`button`、`input` 继承 `Noto Sans SC`，以 `code`、`.mono` 使用 `JetBrains Mono`。为 `.workspace-panel`、`.page-heading`、`.empty-state` 和 `.table-wrap` 定义可供业务页直接复用的纸面面板与响应式表格规则。

- [ ] **Step 4: 运行测试与构建**

Run: `npm test -- Register.spec.js && npm run build`

Expected: 两个命令成功。

- [ ] **Step 5: 提交全局样式基线**

```bash
git add smart-agent-frontend/src/style.css smart-agent-frontend/src/views/Register.spec.js
git commit -m "feat: add workbench design tokens"
```

### Task 2: 重构应用壳层与角色导航

**Files:**
- Modify: `smart-agent-frontend/src/App.vue`
- Modify: `smart-agent-frontend/src/router/guards.spec.js`
- Test: `smart-agent-frontend/src/router/guards.spec.js`

**Interfaces:**
- Consumes: `useAuthStore()` 的 `user`、`logout()` 和 Vue Router 的当前 `route.path`。
- Produces: `.workspace-shell`、`.workspace-sidebar`、`.workspace-main` 与 `.tide-line[data-section]`；认证路径不渲染工作区导航。

- [ ] **Step 1: 为已有权限导航写回归断言**

在 `guards.spec.js` 中保留现有角色守卫断言，并新增平台管理员访问 `/system/overview`、普通租户访问 `/knowledge` 的断言；不要把样式选择器引入路由守卫测试。

```js
await router.push('/system/overview')
expect(router.currentRoute.value.path).toBe('/system/overview')
```

- [ ] **Step 2: 运行路由测试**

Run: `npm test -- guards.spec.js`

Expected: PASS，明确 UI 壳层没有修改角色访问控制。

- [ ] **Step 3: 改造 `App.vue` 模板与样式**

根据 `route.meta.public` 或路径集合为登录、注册、邀请、403 和初始密码修改页计算 `isPublicPage`。公开页只包裹 `router-view`；工作区页使用下列语义结构，保留现有按角色显示的菜单项及其 `index`：

```vue
<div v-if="!isPublicPage" class="workspace-shell">
  <aside class="workspace-sidebar">
    <router-link class="brand" to="/knowledge">…</router-link>
    <nav aria-label="工作区导航"><el-menu …>…</el-menu></nav>
    <div class="account-actions">…退出…</div>
  </aside>
  <main class="workspace-main">
    <div class="tide-line" :data-section="activeSection"><span></span></div>
    <router-view />
  </main>
</div>
<router-view v-else />
```

用路由路径派生 `activeSection`，仅在其变化时为潮汐线改变 `data-section`。CSS 桌面端采用 248px 左栏；`max-width: 720px` 下改为顶栏横向滚动，禁止 100vw 造成横向溢出。

- [ ] **Step 4: 运行所有前端单测与构建**

Run: `npm test && npm run build`

Expected: 成功，守卫测试与既有页面测试均通过。

- [ ] **Step 5: 提交应用壳层**

```bash
git add smart-agent-frontend/src/App.vue smart-agent-frontend/src/router/guards.spec.js
git commit -m "feat: add responsive workbench shell"
```

### Task 3: 统一知识库与智能体列表页

**Files:**
- Modify: `smart-agent-frontend/src/views/Knowledge.vue`
- Modify: `smart-agent-frontend/src/views/Agent.vue`
- Test: `smart-agent-frontend/src/views/Register.spec.js`

**Interfaces:**
- Consumes: 现有列表数据、创建/删除方法与详情路由。
- Produces: 每页 `.entity-page`、`.entity-page__header`、`.entity-grid`、`.entity-card` 和明确的空状态。

- [ ] **Step 1: 扩充现有列表页测试或创建同目录测试**

若 `Knowledge.spec.js` 和 `Agent.spec.js` 不存在，创建它们，用浅挂载和 API mock 验证每个页面显示一个“创建”按钮，且卡片标题由响应数据渲染：

```js
expect(wrapper.get('[data-testid="create-knowledge"]')).toHaveText('创建知识库')
expect(wrapper.get('.entity-card__title').text()).toBe('产品资料')
```

- [ ] **Step 2: 运行新增测试确认当前行为缺口**

Run: `npm test -- Knowledge.spec.js Agent.spec.js`

Expected: 如果测试使用新的 `data-testid` 或结构类，初次应失败；记录失败来自尚未加入语义标记，而非请求 mock。

- [ ] **Step 3: 更新两个列表模板和 scoped CSS**

保留创建、删除、点击卡片的全部事件处理。为页首增加中文标题和简短说明；为创建按钮添加稳定的 `data-testid`。卡片内部使用类别小标签、`.entity-card__title`、最多两行的描述、知识库统计以及永远可通过 Tab 访问的删除按钮。没有数据时显示 `.empty-state` 与现有创建动作。删除旧的蓝色渐变、`scale(1.02)` 和逐页 Element Plus 弹窗覆盖，改用全局令牌。

- [ ] **Step 4: 运行列表测试与构建**

Run: `npm test -- Knowledge.spec.js Agent.spec.js && npm run build`

Expected: PASS；卡片操作、对话框创建流程仍由既有事件方法处理。

- [ ] **Step 5: 提交实体列表页**

```bash
git add smart-agent-frontend/src/views/Knowledge.vue smart-agent-frontend/src/views/Agent.vue smart-agent-frontend/src/views/Knowledge.spec.js smart-agent-frontend/src/views/Agent.spec.js
git commit -m "feat: refresh knowledge and agent lists"
```

### Task 4: 重塑知识库、智能体与工具工作台

**Files:**
- Modify: `smart-agent-frontend/src/views/AgentDetail.vue`
- Modify: `smart-agent-frontend/src/views/KnowledgeDetail.vue`
- Modify: `smart-agent-frontend/src/views/Tools.vue`
- Modify: `smart-agent-frontend/src/views/MdConverter.vue`
- Test: `smart-agent-frontend/src/views/BusinessApiClient.spec.js`

**Interfaces:**
- Consumes: 已有详情表单字段、上传、嵌入、保存、消息发送与 Markdown 生成方法。
- Produces: `.detail-workbench`、`.settings-pane`、`.preview-pane`、`.tool-workbench`；所有事件方法、API 调用参数不变。

- [ ] **Step 1: 写出业务行为回归测试**

在 `BusinessApiClient.spec.js` 中补充详情页触发保存/发送/生成 Markdown 时仍调用原来的 API URL 和请求体的断言。示例：

```js
await wrapper.get('[data-testid="convert-markdown"]').trigger('click')
expect(post).toHaveBeenCalledWith(expect.stringContaining('/tool/convmd'), '内容', expect.any(Object))
```

- [ ] **Step 2: 运行测试确认当前实现保持可测**

Run: `npm test -- BusinessApiClient.spec.js`

Expected: 先通过现有断言；若新增 `data-testid` 引发失败，失败应只来自缺少目标元素。

- [ ] **Step 3: 实现详情与工具布局**

将智能体详情的双栏容器改为 `.detail-workbench`：配置为纸面栏，聊天预览为深水栏，用户和助手消息用不同明度而非渐变区分；推荐问题保持按钮语义。知识库详情和工具页改用同一二级导航面板外观，上传、保存与生成按钮文本及处理函数不变。为保存、上传、发送、转换按钮分别添加语义化 `data-testid`，例如 `save-agent`、`upload-document`、`send-message`、`convert-markdown`。

- [ ] **Step 4: 添加响应式与减少动态效果规则**

在四个页面的 scoped CSS 中，于 `max-width: 720px` 让配置和预览纵向排列、移除固定 640px 高度、让编辑器至少占 50vh，并令二级导航可以横向滚动。不要用 `!important` 覆盖全局减少动态效果规则。

- [ ] **Step 5: 运行测试与构建**

Run: `npm test -- BusinessApiClient.spec.js && npm run build`

Expected: 成功；构建不报告 SFC 模板错误。

- [ ] **Step 6: 提交工作台页面**

```bash
git add smart-agent-frontend/src/views/AgentDetail.vue smart-agent-frontend/src/views/KnowledgeDetail.vue smart-agent-frontend/src/views/Tools.vue smart-agent-frontend/src/views/MdConverter.vue smart-agent-frontend/src/views/BusinessApiClient.spec.js
git commit -m "feat: redesign entity detail workbenches"
```

### Task 5: 统一运营与租户管理界面

**Files:**
- Modify: `smart-agent-frontend/src/views/System.vue`
- Modify: `smart-agent-frontend/src/views/system/Overview.vue`
- Modify: `smart-agent-frontend/src/views/system/Tenants.vue`
- Modify: `smart-agent-frontend/src/views/system/Invitations.vue`
- Modify: `smart-agent-frontend/src/views/TenantMembers.vue`
- Modify: `smart-agent-frontend/src/views/TenantProfile.vue`
- Test: `smart-agent-frontend/src/views/system/Tenants.spec.js`
- Test: `smart-agent-frontend/src/views/system/Invitations.spec.js`

**Interfaces:**
- Consumes: 平台管理 API、现有筛选和弹窗状态、成员操作。
- Produces: 一致的运营页首、统计带、筛选面板、表格容器、状态标记和设置卡片；请求与数据格式不变。

- [ ] **Step 1: 写状态与筛选行为回归测试**

在 `Tenants.spec.js` 和 `Invitations.spec.js` 中确认点击筛选仍以当前字段请求，且状态标签仍使用既有标签文字：

```js
await wrapper.get('form.filters').trigger('submit')
expect(listTenants).toHaveBeenCalledWith(expect.objectContaining({ keyword: '星海' }))
expect(wrapper.text()).toContain('正常')
```

- [ ] **Step 2: 运行运营测试**

Run: `npm test -- Tenants.spec.js Invitations.spec.js`

Expected: PASS，作为重构前行为基线。

- [ ] **Step 3: 移除页面私有的蓝紫体系，接入全局面板令牌**

保留 Overview 的真实“运营脉搏”和 Invitations 的生命周期信息，改为海雾画布上的纸面面板；表头、筛选栏、分页和弹窗使用相同间距与色彩。将 `TenantMembers.vue` 从无容器表格更新为有标题、说明和主操作的 `.workspace-panel`，而不改 `reset`、`disable`、`create` 方法。将 `TenantProfile.vue` 现有身份带改为信号青航标样式并使用全局文本令牌。

- [ ] **Step 4: 检查窄屏表格与键盘焦点**

Run: `npm run build`

Expected: PASS。使用浏览器在 390px 视口检查筛选控件单列，表格容器可横向滚动，行操作和弹窗按钮可用 Tab 聚焦。

- [ ] **Step 5: 运行运营回归测试**

Run: `npm test -- Tenants.spec.js Invitations.spec.js`

Expected: PASS。

- [ ] **Step 6: 提交运营页面**

```bash
git add smart-agent-frontend/src/views/System.vue smart-agent-frontend/src/views/system/Overview.vue smart-agent-frontend/src/views/system/Tenants.vue smart-agent-frontend/src/views/system/Invitations.vue smart-agent-frontend/src/views/TenantMembers.vue smart-agent-frontend/src/views/TenantProfile.vue smart-agent-frontend/src/views/system/Tenants.spec.js smart-agent-frontend/src/views/system/Invitations.spec.js
git commit -m "feat: unify platform management surfaces"
```

### Task 6: 重做认证、受限与首登页面并进行视觉 QA

**Files:**
- Modify: `smart-agent-frontend/src/views/Login.vue`
- Modify: `smart-agent-frontend/src/views/Register.vue`
- Modify: `smart-agent-frontend/src/views/ChangeInitialPassword.vue`
- Modify: `smart-agent-frontend/src/views/Forbidden.vue`
- Test: `smart-agent-frontend/src/views/Login.spec.js` (create)
- Test: `smart-agent-frontend/src/views/Register.spec.js`

**Interfaces:**
- Consumes: 已有 `auth.login`、`auth.loginPlatform`、`auth.register`、路由跳转和 Element Plus 消息。
- Produces: `.auth-page`、`.auth-card`、`.auth-card__eyebrow`；提交数据和跳转目标保持不变。

- [ ] **Step 1: 写认证流失败测试**

创建 `Login.spec.js`，mock `useAuthStore` 和 router，验证租户登录和平台登录分别调用正确方法；同时断言登录表单使用 `.auth-card`。

```js
await wrapper.get('form').trigger('submit')
expect(auth.login).toHaveBeenCalledWith('alice', 'secret')
expect(wrapper.find('.auth-card').exists()).toBe(true)
```

- [ ] **Step 2: 运行认证测试确认失败**

Run: `npm test -- Login.spec.js Register.spec.js`

Expected: `Login.spec.js` 初次失败（文件尚不存在），`Register.spec.js` 通过。

- [ ] **Step 3: 实现认证环境与统一卡片**

每个认证类页面添加 `.auth-page` 根容器、简短产品标识与现有表单卡片；深水背景使用纯色和极淡的径向纹理，避免动画。登录的“租户用户/平台管理员”单选保留原值；注册、首次改密和 403 的按钮、错误反馈、链接与跳转全部保持原行为。禁止使用内联 `style` 来承载布局。

- [ ] **Step 4: 运行认证测试与全量测试**

Run: `npm test -- Login.spec.js Register.spec.js && npm test && npm run build`

Expected: 全部成功。

- [ ] **Step 5: 执行桌面与移动视觉 QA**

启动 `npm run dev -- --host 127.0.0.1`，使用浏览器逐页检查 `/login`、`/register`、`/knowledge`、`/agent`、一个详情页、`/tools/md-converter`、`/system/overview`、`/system/tenants` 与 `/system/invitations` 的 1440px 和 390px 截图。修复可见的遮挡、水平溢出、低对比文本或失焦问题后，重复 `npm test && npm run build`。

- [ ] **Step 6: 提交认证与 QA 修正**

```bash
git add smart-agent-frontend/src/views/Login.vue smart-agent-frontend/src/views/Register.vue smart-agent-frontend/src/views/ChangeInitialPassword.vue smart-agent-frontend/src/views/Forbidden.vue smart-agent-frontend/src/views/Login.spec.js smart-agent-frontend/src/views/Register.spec.js
git commit -m "feat: refresh authentication experiences"
```

### Task 7: 完整回归与交付检查

**Files:**
- Modify: 仅限 Task 6 视觉 QA 所发现、且与本规格直接相关的前端文件。
- Test: 全部 `smart-agent-frontend/src/**/*.spec.js`

**Interfaces:**
- Consumes: Task 1–6 的已提交界面与现有前端脚本。
- Produces: 可构建、可测试、已人工视觉检查的工作区界面。

- [ ] **Step 1: 运行完整自动验证**

Run: `npm test && npm run build`

Expected: 零失败、Vite 产物生成成功。

- [ ] **Step 2: 检查改动范围与令牌一致性**

Run: `rg -n "#409eff|#67c23a|Arial|scale\\(1\\.02" smart-agent-frontend/src --glob '*.vue' --glob '*.css'`

Expected: 不再有旧的通用蓝、绿、Arial 或卡片缩放悬停定义；任何业务必需色值必须在全局令牌中解释。

- [ ] **Step 3: 检查 git 差异**

Run: `git status --short && git diff --check`

Expected: 无空白错误；只包含此计划预期的前端与测试文件。

- [ ] **Step 4: 提交最终的 QA 修正（如有）**

若 Step 1–3 产生本规格范围内的修正，执行：

```bash
git add smart-agent-frontend
git commit -m "fix: polish responsive workbench UI"
```

若没有修正，不创建空提交。
