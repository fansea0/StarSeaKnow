## Task 27: vue-router + guards + meta

**Files:**
- Create: `smart-agent-frontend/src/router/guards.js`
- Modify: `smart-agent-frontend/src/router/index.js` (call installGuards; add meta to existing routes)

**DEFERRED to later tasks** (NOT in Task 27):
- `/login`, `/accept-invite/:code`, `/403`, `/tenant/members` routes — added when Tasks 28/29 create their views.

---

## Step 1: guards.js

Create `src/router/guards.js`:

```js
import { useAuthStore } from '../stores/auth'

export function installGuards(router) {
  router.beforeEach(async (to) => {
    const auth = useAuthStore()
    if (!auth.ready) await auth.bootstrap()
    if (to.meta.public) return true
    if (!auth.accessToken) {
      return { path: '/login', query: { redirect: to.fullPath } }
    }
    if (to.meta.requiresAdmin && auth.user?.role !== 'tenant_admin') {
      return '/403'
    }
    return true
  })
}
```

## Step 2: Modify router/index.js

PRESERVE all existing routes (Agent, Knowledge, System, Tools, KnowledgeDetail, AgentDetail). ADD `installGuards(router)` call. DO NOT add new public routes yet (their views don't exist until Tasks 28/29).

Updated router/index.js:

```js
import { createRouter, createWebHistory } from 'vue-router'
import { installGuards } from './guards'
import Agent from '../views/Agent.vue'
import Knowledge from '../views/Knowledge.vue'
import System from '../views/System.vue'
import Tools from '../views/Tools.vue'

const routes = [
  { path: '/', redirect: '/knowledge' },
  { path: '/agent', name: 'Agent', component: Agent },
  { path: '/knowledge', name: 'Knowledge', component: Knowledge },
  { path: '/system', name: 'System', component: System },
  { path: '/tools', name: 'Tools', component: Tools,
    redirect: '/tools/md-converter',
    children: [
      { path: 'md-converter', name: 'MdConverter', component: () => import('../views/MdConverter.vue') },
    ],
  },
  { path: '/knowledge/:id', name: 'KnowledgeDetail', component: () => import('../views/KnowledgeDetail.vue') },
  { path: '/agent/:id', name: 'AgentDetail', component: () => import('../views/AgentDetail.vue') },
]

const router = createRouter({ history: createWebHistory(), routes })
installGuards(router)
export default router
```

**Brief bug correction #1**: Brief adds routes `/login`, `/accept-invite/:code`, `/403`, `/tenant/members` — but the views (`Login.vue`, `AcceptInvite.vue`, `Forbidden.vue`, `TenantMembers.vue`) don't exist yet. They're created in Tasks 28/29. Adding routes referencing non-existent views will break the build. **Defer these routes to Tasks 28/29.**

**Brief bug correction #2**: Brief imports Login/AcceptInvite/Forbidden/TenantMembers at top-level — same issue. Removed.

## Step 3: Commit

```bash
git add src/router/guards.js src/router/index.js
git commit -m "feat(fe): router guards + install on existing routes"
```

## Sandbox caveat

Frontend can't be runtime-tested. Static review only. `vite build` may work if deps are clean.

## Self-review

- `src/router/guards.js` exists with the installGuards function
- `src/router/index.js` preserves all 7 existing routes
- `installGuards(router)` called BEFORE export default
- NO new public routes added (deferred to Tasks 28/29)
- Commit message matches

## Report Format

Write to `/Users/Admin/code/rag_agent/smart-agent-frontend/.superpowers/sdd/2026-08-26-platform-auth-jwt-tenant/task-27-report.md`:
- What you implemented
- **Brief bugs corrected** (2 bugs: deferred new routes, removed non-existent view imports)
- Files changed
- Self-review findings

Report back under 10 lines: status, commit SHA, summary.