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
