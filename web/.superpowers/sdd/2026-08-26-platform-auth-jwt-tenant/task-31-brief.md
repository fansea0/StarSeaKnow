## Task 31: App.vue logout button + mount bootstrap

**File:**
- Modify: `smart-agent-frontend/src/App.vue` (PRESERVE existing layout, ADD bootstrap + logout button)

---

## Brief bug correction (CRITICAL)

Brief REPLACES App.vue entirely with bare `<router-view />`. But existing App.vue contains:
- Header with logo, brand name ("小达智能体")
- Navigation menu (el-menu with routes for /agent, /knowledge, /tools, /system)
- Content wrapper

**Replacing this would BREAK the entire UI layout.** Plan title says "App.vue logout button + mount bootstrap" — the intent is to ADD features to existing App.vue, not REPLACE it.

**FIX**: PRESERVE the existing App.vue (template, script, style). ADD:
1. Bootstrap on mount (`onMounted(() => auth.bootstrap())`)
2. Logout button in the header (after `<el-menu>`, conditionally shown if authenticated)
3. Link to /tenant/members in nav menu (admin only — see below)

The brief's `<router-view />` IS already inside the existing template (`<router-view></router-view>` is in `content-wrapper`).

## Step 1: Updated App.vue

PRESERVE everything from current App.vue. MODIFY script + add logout button to header.

Updated script:
```js
<script setup>
import { useAuthStore } from './stores/auth'
import { onMounted } from 'vue'

const auth = useAuthStore()
onMounted(() => { auth.bootstrap() })
</script>
```

(Convert from Options API `script` to Composition API `script setup`.)

Updated template — add logout button at end of header (after `<el-menu>`):
```vue
<header class="main-header">
  <div class="logo-area">
    <img src="./assets/logo.png" alt="logo" class="logo-img" />
    <span class="brand-name">小达智能体</span>
  </div>
  <el-menu :default-active="activeMenu" mode="horizontal" router class="main-menu">
    <el-menu-item index="/agent">智能体</el-menu-item>
    <el-menu-item index="/knowledge">知识库</el-menu-item>
    <el-menu-item index="/tools">工具</el-menu-item>
    <el-menu-item v-if="auth.user?.role === 'tenant_admin'" index="/tenant/members">成员</el-menu-item>
    <el-menu-item index="/system">系统管理</el-menu-item>
  </el-menu>
  <div v-if="auth.user" class="user-area">
    <span class="user-name">{{ auth.user.displayName || auth.user.username }}</span>
    <el-button size="small" type="text" @click="auth.logout()">退出</el-button>
  </div>
</header>
```

Add styles for `.user-area` and `.user-name`:
```css
.user-area {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-left: 16px;
}
.user-name {
  font-size: 14px;
  color: #666;
}
```

## Step 2: Commit

```bash
git add src/App.vue
git commit -m "feat(fe): App.vue bootstraps auth on mount + logout button"
```

## Sandbox caveat

Frontend can't be runtime-tested. Static review only.

## Self-review

- App.vue preserved (header with logo, brand, menu intact)
- `<router-view>` still inside `content-wrapper`
- Composition API `script setup` used
- `auth.bootstrap()` called in onMounted
- Logout button only shown when `auth.user` is non-null
- "成员" menu item only shown when `auth.user?.role === 'tenant_admin'`
- All existing styles preserved
- Commit message matches

## Report Format

Write to `/Users/Admin/code/rag_agent/smart-agent-frontend/.superpowers/sdd/2026-08-26-platform-auth-jwt-tenant/task-31-report.md`:
- What you implemented
- **Brief bugs corrected** (1 critical bug: brief replaces entire App.vue, would break UI layout)
- Files changed
- Self-review findings

Report back under 10 lines: status, commit SHA, summary.