# Task 27 Report

## Status
COMPLETE

## Commit
- SHA: `261ac4b20dc4698628aa3880541679ca9d542e45`
- Message: `feat(fe): router guards + install on existing routes`
- Branch: `master`

## Summary
Implemented Task 27 per the brief with two corrections.

### Files Changed
1. **Created** `src/router/guards.js` — exports `installGuards(router)` which registers a `beforeEach` hook that bootstraps the auth store, allows public routes, redirects unauthenticated users to `/login` (with redirect query), and enforces `requiresAdmin` meta by redirecting to `/403` when the user role is not `tenant_admin`.
2. **Modified** `src/router/index.js`:
   - Added `import { installGuards } from './guards';`
   - Added `installGuards(router)` immediately before `export default router`.
   - Preserved all 7 existing routes (Agent, Knowledge, System, Tools + MdConverter child, KnowledgeDetail, AgentDetail) and the `/` redirect to `/knowledge`.

### Brief Bugs Corrected
1. **Deferred new routes**: Brief suggested adding `/login`, `/accept-invite/:code`, `/403`, and `/tenant/members`. None of the corresponding view files (`Login.vue`, `AcceptInvite.vue`, `Forbidden.vue`, `TenantMembers.vue`) exist yet (Tasks 28/29 create them). Importing/routing to these would break the Vite build. **Not added**.
2. **Removed non-existent view imports**: Same root cause — the brief's proposed `import Login/AcceptInvite/Forbidden/TenantMembers` at top-level was removed. The guards' `installGuards` function references `/login` and `/403` as redirect destinations, but those are plain path strings, not file imports, so the build is unaffected.

### Self-Review
- `src/router/guards.js` exists; exports `installGuards`. Confirmed.
- `router/index.js` preserves all 7 existing routes; no new routes added. Confirmed.
- No references to non-existent view files. Confirmed (no imports of `Login.vue`/`AcceptInvite.vue`/`Forbidden.vue`/`TenantMembers.vue`).
- `installGuards(router)` is called before `export default router`. Confirmed.
- Commit message matches the brief exactly: `feat(fe): router guards + install on existing routes`. Confirmed.

### Notes
- Guards redirect to `/login` and `/403` even though those routes aren't yet registered. vue-router will treat unknown redirect targets as a no-op navigation failure at runtime, but this is harmless until Tasks 28/29 add the routes; static build succeeds.
- Could not runtime-test (sandbox caveat in brief). Static review only.
