## Task 30: vite proxy /api → backend

**File:**
- Modify: `smart-agent-frontend/vite.config.js`

---

## Step 1: Add /api proxy

Update vite.config.js:

```js
import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// https://vite.dev/config/
export default defineConfig({
  plugins: [vue()],
  server: {
    proxy: {
      '/api': { target: 'http://localhost:8080', changeOrigin: true },
    },
  },
})
```

This proxies all `/api/*` requests from the dev server (default port 5173) to the Spring Boot backend at `http://localhost:8080`.

## Step 2: Commit

```bash
git add vite.config.js
git commit -m "chore(fe): vite proxy /api → backend"
```

## Sandbox caveat

Frontend can't be runtime-tested. Static review only.

## Self-review

- vite.config.js has `server.proxy['/api']` with target `http://localhost:8080` and `changeOrigin: true`
- Existing `plugins: [vue()]` preserved
- Comment preserved
- Commit message matches

## Report

Write to `/Users/Admin/code/rag_agent/smart-agent-frontend/.superpowers/sdd/2026-08-26-platform-auth-jwt-tenant/task-30-report.md`.

Report back under 10 lines: status, commit SHA, summary.