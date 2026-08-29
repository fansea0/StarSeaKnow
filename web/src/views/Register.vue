<template>
  <el-card class="auth-card">
    <h2>使用邀请码注册</h2>
    <p class="subtitle">创建租户管理员账号，完成后即可进入工作区。</p>
    <el-form :model="form" label-width="92px" @submit.prevent="onSubmit">
      <el-form-item label="邀请码"><el-input v-model="form.inviteCode" autocomplete="off" placeholder="请输入邀请码" /></el-form-item>
      <el-form-item label="用户名"><el-input v-model="form.username" autocomplete="username" placeholder="设置登录用户名" /></el-form-item>
      <el-form-item label="密码"><el-input v-model="form.password" type="password" show-password autocomplete="new-password" placeholder="设置登录密码" /></el-form-item>
      <el-form-item label="确认密码"><el-input v-model="form.confirmPassword" type="password" show-password autocomplete="new-password" placeholder="再次输入密码" /></el-form-item>
      <el-button type="primary" native-type="submit" :loading="loading" style="width: 100%">注册并进入工作区</el-button>
    </el-form>
    <p class="login-link">已有账号？<router-link to="/login">返回登录</router-link></p>
  </el-card>
</template>

<script setup>
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '../stores/auth'
import { ElMessage } from 'element-plus'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const form = reactive({ inviteCode: typeof route.query.inviteCode === 'string' ? route.query.inviteCode : '', username: '', password: '', confirmPassword: '' })
const loading = ref(false)

async function onSubmit() {
  if (form.password !== form.confirmPassword) {
    ElMessage.error('两次输入的密码不一致')
    return
  }
  loading.value = true
  try {
    await auth.register({ ...form })
    ElMessage.success('注册成功，欢迎进入工作区')
    router.push('/knowledge')
  } catch (e) {
    ElMessage.error(e.response?.data?.msg || '注册失败，请检查邀请码和输入信息')
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.auth-card { max-width: 440px; margin: 80px auto; }
h2 { margin: 0 0 8px; color: #172554; }
.subtitle, .login-link { color: #64748b; font-size: 14px; }
.subtitle { margin: 0 0 24px; }
.login-link { margin: 16px 0 0; text-align: center; }
.login-link a { color: #2563eb; font-weight: 600; text-decoration: none; }
</style>
