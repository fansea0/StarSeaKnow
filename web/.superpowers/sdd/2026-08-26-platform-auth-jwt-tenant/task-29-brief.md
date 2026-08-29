## Task 29: TenantMembers view + nav + add deferred routes

**Files:**
- Create: `smart-agent-frontend/src/views/TenantMembers.vue`
- Modify: `smart-agent-frontend/src/router/index.js` (add the 4 routes deferred from Task 27)

---

## Step 1: TenantMembers.vue

Per brief verbatim (with brief bug correction noted below):

```vue
<template>
  <div style="padding:24px">
    <h2>成员管理</h2>
    <el-button type="primary" @click="dlg = true">邀请新成员</el-button>
    <el-table :data="list" style="margin-top:16px">
      <el-table-column prop="username" label="用户名" />
      <el-table-column prop="displayName" label="显示名" />
      <el-table-column prop="role" label="角色" />
      <el-table-column prop="status" label="状态">
        <template #default="{ row }">{{ row.status === 1 ? '正常' : '停用' }}</template>
      </el-table-column>
      <el-table-column label="操作">
        <template #default="{ row }">
          <el-button size="small" @click="reset(row.id)">重置密码</el-button>
          <el-button size="small" type="danger" @click="disable(row.id)" :disabled="row.status === 0">停用</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="dlg" title="邀请新成员">
      <el-form>
        <el-form-item label="用户名"><el-input v-model="form.username" /></el-form-item>
        <el-form-item label="显示名"><el-input v-model="form.displayName" /></el-form-item>
        <el-form-item label="角色">
          <el-select v-model="form.role">
            <el-option value="tenant_member" label="成员" />
            <el-option value="tenant_admin" label="管理员" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dlg = false">取消</el-button>
        <el-button type="primary" @click="create">创建</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="pwdDlg" title="重置后的临时密码">
      <p>请把以下密码转交给成员,要求其首次使用后立即修改(本期后端暂不强制):</p>
      <el-input v-model="tempPwd" readonly />
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import { http } from '../api/http'
import { ElMessage } from 'element-plus'

const list = ref([])
const dlg = ref(false)
const pwdDlg = ref(false)
const tempPwd = ref('')
const form = reactive({ username: '', displayName: '', role: 'tenant_member' })

async function reload() {
  const r = await http.get('/tenant/members')
  list.value = r.data.data
}

async function create() {
  try {
    const r = await http.post('/tenant/members', form)
    tempPwd.value = r.data.data.tempPassword
    pwdDlg.value = true
    dlg.value = false
    await reload()
  } catch (e) { ElMessage.error(e.response?.data?.msg || '创建失败') }
}

async function reset(id) {
  try {
    const r = await http.post(`/tenant/members/${id}/reset-password`)
    tempPwd.value = r.data.data.tempPassword
    pwdDlg.value = true
  } catch (e) { ElMessage.error(e.response?.data?.msg || '重置失败') }
}

async function disable(id) {
  try {
    await http.post(`/tenant/members/${id}/disable`)
    await reload()
  } catch (e) { ElMessage.error(e.response?.data?.msg || '停用失败') }
}

onMounted(reload)
</script>
```

**Brief bug correction #1**: Brief's script ends at `create()`. Missing `reset(id)` and `disable(id)` action handlers — these are referenced in the template but never defined, which would cause runtime errors when admin clicks reset/disable. **ADD both handlers** (above). Also missing `onMounted(reload)` — added.

## Step 2: Add the 4 deferred routes to router/index.js

Routes deferred from Task 27 (Login/AcceptInvite/Forbidden views now exist; TenantMembers view created in Step 1):

```js
import Login from '../views/Login.vue'
import AcceptInvite from '../views/AcceptInvite.vue'
import Forbidden from '../views/Forbidden.vue'
import TenantMembers from '../views/TenantMembers.vue'

const routes = [
  // ... existing routes ...
  { path: '/login', name: 'Login', component: Login, meta: { public: true } },
  { path: '/accept-invite/:code', name: 'AcceptInvite', component: AcceptInvite, meta: { public: true } },
  { path: '/403', name: 'Forbidden', component: Forbidden, meta: { public: true } },
  { path: '/tenant/members', name: 'TenantMembers', component: TenantMembers, meta: { requiresAdmin: true } },
]
```

(Add the 4 routes to the existing array — they're complementary to the 7 existing routes; don't drop anything.)

## Step 3: Commit

```bash
git add src/views/TenantMembers.vue src/router/index.js
git commit -m "feat(fe): TenantMembers view + deferred public/admin routes"
```

## Sandbox caveat

Frontend can't be runtime-tested. Static review only.

## Self-review

- TenantMembers.vue exists with `reload()`, `create()`, `reset(id)`, `disable(id)` handlers
- `onMounted(reload)` triggers initial load
- router/index.js has all 4 new routes (`/login`, `/accept-invite/:code`, `/403`, `/tenant/members`)
- imports at top of router/index.js include Login, AcceptInvite, Forbidden, TenantMembers
- Existing 7 routes preserved
- Commit message matches

## Report Format

Write to `/Users/Admin/code/rag_agent/smart-agent-frontend/.superpowers/sdd/2026-08-26-platform-auth-jwt-tenant/task-29-report.md`:
- What you implemented
- **Brief bugs corrected** (1 bug: missing reset/disable handlers in TenantMembers)
- Files changed
- Self-review findings

Report back under 10 lines: status, commit SHA, summary.