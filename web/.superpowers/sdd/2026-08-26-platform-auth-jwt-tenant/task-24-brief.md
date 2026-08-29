## Task 24: Frontend deps + axios setup

**Files:**
- Modify: `smart-agent-frontend/package.json` (add pinia)
- Modify: `smart-agent-frontend/package-lock.json` (auto-updated by npm)
- Create: `smart-agent-frontend/src/api/http.js`

---

## Step 1: Add pinia

```bash
cd smart-agent-frontend
npm install pinia@^2.2.0
```

This updates `package.json` (adds `pinia` to `dependencies`) and `package-lock.json`.

## Step 2: Create axios instance

Create `src/api/http.js`:

```js
import axios from 'axios'

export const http = axios.create({
  baseURL: '/api',
  withCredentials: true,
  timeout: 30000,
})

let auth = null  // injected by stores/auth.js on bootstrap

export function bindAuth(store) { auth = store }
```

Note: `withCredentials: true` is essential for refresh-cookie cross-origin behavior (cookie set by backend must be sent back).

## Step 3: Commit

```bash
git add package.json package-lock.json src/api/http.js
git commit -m "feat(fe): add pinia + axios instance skeleton"
```

---

## Implementation guidance

- Use Bash `cd` then `npm install` — sandbox has Node.js v26.7.0 + npm 11.19.0 available (verify with `node --version`).
- If `npm install` fails (e.g., offline / no registry), document and continue with manual `package.json` edit only.
- The `src/api/` directory does not yet exist — `Write` tool creates parent dirs.

## Sandbox caveat

npm install requires network access. May or may not work in sandbox. If it fails, edit `package.json` manually to add the `pinia` line, skip `package-lock.json` (it would be regenerated on a real host).

## Self-review

- `package.json` includes `"pinia": "^2.2.0"` in `dependencies`
- `package-lock.json` updated (if npm install succeeded)
- `src/api/http.js` exists with the exact content above
- Commit message matches plan

## Report Format

Write to `/Users/Admin/code/rag_agent/smart-agent-frontend/.superpowers/sdd/2026-08-26-platform-auth-jwt-tenant/task-24-report.md`:
- What you implemented
- Files changed
- npm install result (success / failure / fallback)
- Self-review findings

Report back under 10 lines: status, commit SHA, summary, npm install outcome.