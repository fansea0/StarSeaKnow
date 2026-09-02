<template>
  <el-card class="auth-card">
    <h2>{{ auth.user?.role === 'platform_admin' ? '修改初始密码' : '重置登录密码' }}</h2>
    <p class="subtitle">{{ auth.user?.role === 'platform_admin' ? '为保障平台安全，请先修改系统初始化密码。' : '管理员已要求你修改密码，请完成设置后继续使用。' }}</p>
    <el-form :model="form" label-width="100px" @submit.prevent="onSubmit">
      <el-form-item label="当前密码"><el-input v-model="form.currentPassword" type="password" show-password autocomplete="current-password" /></el-form-item>
      <el-form-item label="新密码"><el-input v-model="form.newPassword" type="password" show-password autocomplete="new-password" /></el-form-item>
      <el-form-item label="确认新密码"><el-input v-model="form.confirmPassword" type="password" show-password autocomplete="new-password" /></el-form-item>
      <el-button type="primary" native-type="submit" :loading="loading" style="width: 100%">保存并继续</el-button>
    </el-form>
  </el-card>
</template>

<script setup>
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '../stores/auth'
import { ElMessage } from 'element-plus'

const router = useRouter()
const route = useRoute()
const auth = useAuthStore()
const form = reactive({ currentPassword: '', newPassword: '', confirmPassword: '' })
const loading = ref(false)

async function onSubmit() {
  if (form.newPassword !== form.confirmPassword) {
    ElMessage.error('两次输入的新密码不一致')
    return
  }
  loading.value = true
  try {
    await auth.changeInitialPassword({ ...form })
    ElMessage.success('初始密码已修改')
    router.push(route.query.redirect || (auth.user?.role === 'platform_admin' ? '/system' : '/knowledge'))
  } catch (e) {
    ElMessage.error(e.response?.data?.msg || '修改失败，请检查当前密码')
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.auth-card { max-width: 440px; margin: 80px auto; }
h2 { margin: 0 0 8px; color: #172554; }
.subtitle { margin: 0 0 24px; color: #64748b; font-size: 14px; }
</style>
