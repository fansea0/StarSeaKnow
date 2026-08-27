## Task 28: Login / AcceptInvite / Forbidden views

**Files:**
- Create: `smart-agent-frontend/src/views/Login.vue`
- Create: `smart-agent-frontend/src/views/AcceptInvite.vue`
- Create: `smart-agent-frontend/src/views/Forbidden.vue`

---

## Step 1: Login.vue

```vue
<template>
  <el-card style="max-width:380px;margin:80px auto">
    <h2>登录</h2>
    <el-form :model="form" label-width="80px" @submit.prevent="onSubmit">
      <el-form-item label="租户 code">
        <el-input v-model="form.tenantCode" placeholder="如 acme" />
      </el-form-item>
      <el-form-item label="用户名">
        <el-input v-model="form.username" />
      </el-form-item>
      <el-form-item label="密码">
        <el-input v-model="form.password" type="password" show-password />
      </el-form-item>
      <el-button type="primary" native-type="submit" :loading="loading" style="width:100%">登录</el-button>
    </el-form>
  </el-card>
</template>

<script setup>
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '../stores/auth'
import { ElMessage } from 'element-plus'

const auth = useAuthStore()
const route = useRoute()
const router = useRouter()
const form = reactive({ tenantCode: '', username: '', password: '' })
const loading = ref(false)

async function onSubmit() {
  loading.value = true
  try {
    await auth.login(form.tenantCode, form.username, form.password)
    router.push(route.query.redirect || '/knowledge')
  } catch (e) {
    ElMessage.error(e.response?.data?.msg || '登录失败')
  } finally { loading.value = false }
}
</script>
```

## Step 2: AcceptInvite.vue

```vue
<template>
  <el-card style="max-width:380px;margin:80px auto">
    <h2>接受邀请</h2>
    <p>租户: <b>{{ tenantName || '加载中…' }}</b></p>
    <el-form @submit.prevent="onSubmit">
      <el-form-item label="显示名">
        <el-input v-model="form.displayName" />
      </el-form-item>
      <el-form-item label="密码">
        <el-input v-model="form.password" type="password" show-password />
      </el-form-item>
      <el-button type="primary" native-type="submit" :loading="loading" style="width:100%">接受并登录</el-button>
    </el-card>
</template>
<script setup>
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '../stores/auth'
import { http } from '../api/http'
import { ElMessage } from 'element-plus'

const auth = useAuthStore()
const route = useRoute()
const router = useRouter()
const tenantName = ref('')  // 不调 preview 端点;留空
const form = reactive({ displayName: '', password: '' })
const loading = ref(false)

async function onSubmit() {
  loading.value = true
  try {
    await http.post('/auth/accept-invite', {
      code: route.params.code, password: form.password, displayName: form.displayName,
    })
    await auth.bootstrap()
    router.push('/knowledge')
  } catch (e) {
    ElMessage.error(e.response?.data?.msg || '接受失败')
  } finally { loading.value = false }
}
</script>
```

> 备注:页面顶部用邀请码作为副标题占位, 不调 preview 端点。`tenantName` 留空 → 显示 "加载中…"。

## Step 3: Forbidden.vue

```vue
<template>
  <el-result icon="warning" title="403" sub-title="您没有权限访问该资源">
    <template #extra>
      <el-button @click="$router.push('/')">返回首页</el-button>
    </template>
  </el-result>
</template>
```

## Step 4: Commit

```bash
git add src/views/Login.vue src/views/AcceptInvite.vue src/views/Forbidden.vue
git commit -m "feat(fe): Login / AcceptInvite / Forbidden views"
```

## Sandbox caveat

Frontend can't be runtime-tested. Static review only.

## Self-review

- All 3 files at `src/views/{Login,AcceptInvite,Forbidden}.vue`
- Login uses `auth.login(...)` (Pinia store from Task 25)
- AcceptInvite uses `http.post('/auth/accept-invite', ...)` then `auth.bootstrap()`
- Forbidden has $router.push('/') for "back to home"
- All use Element Plus components (`el-card`, `el-form`, `el-input`, `el-button`, `el-result`)
- Commit message matches

## Report Format

Write to `/Users/Admin/code/rag_agent/smart-agent-frontend/.superpowers/sdd/2026-08-26-platform-auth-jwt-tenant/task-28-report.md`:
- What you implemented
- Files changed
- Self-review findings

Report back under 10 lines: status, commit SHA, summary.