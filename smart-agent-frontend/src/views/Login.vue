<template>
  <el-card style="max-width:380px;margin:80px auto">
    <h2>登录</h2>
    <el-form :model="form" label-width="80px" @submit.prevent="onSubmit">
      <el-form-item label="登录类型">
        <el-radio-group v-model="loginType">
          <el-radio value="tenant">租户用户</el-radio>
          <el-radio value="platform">平台管理员</el-radio>
        </el-radio-group>
      </el-form-item>
      <el-form-item label="用户名">
        <el-input v-model="form.username" />
      </el-form-item>
      <el-form-item label="密码">
        <el-input v-model="form.password" type="password" show-password />
      </el-form-item>
      <el-button type="primary" native-type="submit" :loading="loading" style="width:100%">登录</el-button>
    </el-form>
    <p v-if="loginType === 'tenant'" class="register-link">还没有租户账号？<router-link to="/register">使用邀请码注册</router-link></p>
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
const form = reactive({ username: '', password: '' })
const loginType = ref('tenant')
const loading = ref(false)

async function onSubmit() {
  loading.value = true
  try {
    if (loginType.value === 'platform') {
      await auth.loginPlatform(form.username, form.password)
      router.push(auth.mustChangePassword ? '/change-initial-password' : '/system')
    } else {
      await auth.login(form.username, form.password)
      router.push(route.query.redirect || '/knowledge')
    }
  } catch (e) {
    ElMessage.error(e.response?.data?.msg || '登录失败')
  } finally { loading.value = false }
}
</script>

<style scoped>
.register-link { margin: 16px 0 0; text-align: center; color: #64748b; font-size: 14px; }
.register-link a { color: #2563eb; font-weight: 600; text-decoration: none; }
</style>
