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
    </el-form>
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
