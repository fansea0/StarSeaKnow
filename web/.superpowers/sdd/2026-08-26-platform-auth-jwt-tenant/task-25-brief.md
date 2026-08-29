## Task 25: Pinia auth store + bootstrap + silent refresh

**Files:**
- Create: `smart-agent-frontend/src/stores/auth.js`
- Modify: `smart-agent-frontend/src/main.js` (PRESERVE existing ElementPlus setup, ADD Pinia + setupInterceptors)

---

## Step 1: Create the store

Create `src/stores/auth.js`:

```js
import { defineStore } from 'pinia'
import { http, bindAuth } from '../api/http'
import router from '../router'

export const useAuthStore = defineStore('auth', {
  state: () => ({
    accessToken: null,
    expiresAt: 0,
    user: null,
    tenant: null,   // 来自 /auth/me 响应 data.tenant
    ready: false,
  }),
  actions: {
    async bootstrap() {
      bindAuth(this)
      try {
        const r = await http.post('/auth/refresh')
        this.accessToken = r.data.data.accessToken
        this.expiresAt = r.data.data.expiresAt
        await this.fetchMe()
      } catch (e) {
        // 没登录是预期情况
      } finally {
        this.ready = true
      }
    },
    async login(tenantCode, username, password) {
      const r = await http.post('/auth/login', { tenantCode, username, password })
      this.accessToken = r.data.data.accessToken
      this.expiresAt = r.data.data.expiresAt
      this.user = r.data.data.user
      this.ready = true
    },
    async logout() {
      try { await http.post('/auth/logout') } catch (e) {}
      this.clear()
      router.push('/login')
    },
    async fetchMe() {
      const r = await http.get('/auth/me')
      this.user = r.data.data.user
      this.tenant = r.data.data.tenant
    },
    async silentRefresh() {
      const r = await http.post('/auth/refresh')
      this.accessToken = r.data.data.accessToken
      this.expiresAt = r.data.data.expiresAt
    },
    clear() {
      this.accessToken = null
      this.expiresAt = 0
      this.user = null
      this.tenant = null
    },
  },
})
```

**Brief bug correction #1**: Brief stores `this._tenant = ...` — Pinia state requires explicit declaration in `state()` for reactivity. Use `tenant: null` and `this.tenant = ...`.

## Step 2: Update main.js (PRESERVE ElementPlus)

Current main.js:
```js
import { createApp } from 'vue'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import './style.css'
import App from './App.vue'
import router from './router'

const app = createApp(App)
app.use(ElementPlus)
app.use(router)
app.mount('#app')
```

Updated main.js (PRESERVE ElementPlus, ADD Pinia + setupInterceptors):
```js
import { createApp } from 'vue'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import 'element-plus/theme-chalk/dark/css-vars.css'
import './style.css'
import App from './App.vue'
import router from './router'
import { createPinia } from 'pinia'
import { setupInterceptors } from './api/interceptors'

const app = createApp(App)
app.use(createPinia())
app.use(ElementPlus)
app.use(router)
setupInterceptors()
app.mount('#app')
```

**Brief bug correction #2**: Brief's main.js DROPS the entire ElementPlus setup. ElementPlus is the UI library used throughout the existing app — must be preserved. Brief's "main.js" snippet is incomplete.

**Brief bug correction #3**: Brief references `setupInterceptors` from `./api/interceptors` — but that file is created in Task 26, NOT Task 25. Two options:
- (a) Create a stub `src/api/interceptors.js` at Task 25 with `export function setupInterceptors() {}` (empty function), then Task 26 will overwrite it. This keeps Task 25's commit runnable.
- (b) Don't import `setupInterceptors` at Task 25 — defer to Task 26. Document as forward-reference.

**Recommendation**: option (a) — create a stub `interceptors.js` so each commit is independently runnable. Task 26 will replace it with the full implementation.

Stub:
```js
// Stub: full implementation lands in Task 26.
export function setupInterceptors() {}
```

## Step 3: Commit

```bash
git add src/stores/auth.js src/main.js src/api/interceptors.js
git commit -m "feat(fe): Pinia auth store with bootstrap + silent refresh"
```

## Sandbox caveat

Frontend cannot be runtime-tested (no browser). Static review only. Vite build via `npm run build` may work in sandbox if dependencies are clean.

## Self-review

- `src/stores/auth.js` exists with the corrected code (state.tenant, no `_tenant`)
- `src/main.js` preserves ElementPlus, adds Pinia + setupInterceptors
- `src/api/interceptors.js` stub created (Task 26 will overwrite)
- Commit message matches plan

## Report Format

Write to `/Users/Admin/code/rag_agent/smart-agent-frontend/.superpowers/sdd/2026-08-26-platform-auth-jwt-tenant/task-25-report.md`:
- What you implemented
- **Brief bugs corrected** (3 bugs: `_tenant` → `tenant`; ElementPlus preservation; interceptors stub)
- Files changed
- Self-review findings

Report back under 10 lines: status, commit SHA, summary.