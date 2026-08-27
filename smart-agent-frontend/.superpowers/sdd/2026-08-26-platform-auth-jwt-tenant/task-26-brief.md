## Task 26: axios request/response interceptors

**File:**
- Overwrite: `smart-agent-frontend/src/api/interceptors.js` (currently a Task 25 stub)

---

## Step 1: Replace stub with full implementation

```js
import { http } from './http'
import { useAuthStore } from '../stores/auth'
import router from '../router'
import { ElMessage } from 'element-plus'

let refreshing = null  // 单例,避免 40101 风暴

export function setupInterceptors() {
  http.interceptors.request.use(cfg => {
    const auth = useAuthStore()
    if (auth.accessToken) cfg.headers.Authorization = `Bearer ${auth.accessToken}`
    return cfg
  })

  http.interceptors.response.use(r => r, async err => {
    const auth = useAuthStore()
    const { config, response } = err
    if (!response) return Promise.reject(err)
    const code = response.data?.code

    if (response.status === 401 && code === 40101 && !config._retried) {
      config._retried = true
      refreshing = refreshing || auth.silentRefresh().finally(() => { refreshing = null })
      try {
        await refreshing
        config.headers.Authorization = `Bearer ${auth.accessToken}`
        return http(config)
      } catch (e) {
        auth.clear()
        router.push('/login')
        return Promise.reject(e)
      }
    }

    if ([40102, 40103].includes(code)) {
      auth.clear()
      router.push('/login')
      ElMessage.warning(code === 40103 ? '已在其他设备登录' : '登录已过期,请重新登录')
      return Promise.reject(err)
    }

    if (code === 40301) {
      router.push('/403')
      return Promise.reject(err)
    }

    return Promise.reject(err)
  })
}
```

Notes:
- **40101**: token expired (silent refresh path with `_retried` guard, single-flight via `refreshing` Promise)
- **40102/40103**: token revoked / refresh reuse detected (clear + redirect to /login with toast)
- **40301**: FORBIDDEN_ROLE (redirect to /403 — view created in Task 28)

## Step 2: Commit

```bash
git add src/api/interceptors.js
git commit -m "feat(fe): axios interceptors with single-flight silent refresh"
```

## Sandbox caveat

Frontend can't be runtime-tested in sandbox. Static review only.

## Self-review

- `src/api/interceptors.js` exists with full code (not the Task 25 stub)
- `setupInterceptors()` exports correctly
- `refreshing` module-level Promise for single-flight
- All error codes (40101/40102/40103/40301) handled
- Commit message matches plan

## Report Format

Write to `/Users/Admin/code/rag_agent/smart-agent-frontend/.superpowers/sdd/2026-08-26-platform-auth-jwt-tenant/task-26-report.md`:
- What you implemented
- Files changed
- Self-review findings

Report back under 10 lines: status, commit SHA, summary.