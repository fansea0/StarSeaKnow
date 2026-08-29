<template>
  <main class="tenant-profile" aria-labelledby="tenant-profile-title">
    <section class="profile-card">
      <header><span class="eyebrow">WORKSPACE IDENTITY</span><h1 id="tenant-profile-title">租户设置</h1><p>更新成员在产品中看到的租户名称；不会影响登录账号和已有数据。</p></header>
      <div class="identity-band"><span class="identity-band__mark">{{ initial }}</span><div><small>当前工作区</small><strong>{{ tenantName || '我的工作区' }}</strong></div></div>
      <el-form label-position="top" @submit.prevent="save">
        <el-form-item label="租户名称"><el-input v-model.trim="form.name" maxlength="64" show-word-limit placeholder="例如：星海科技" /></el-form-item>
        <el-button type="primary" native-type="submit" :loading="saving">保存名称</el-button>
      </el-form>
    </section>
  </main>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { useAuthStore } from '../stores/auth'
import { http } from '../api/http'
const auth = useAuthStore(), saving = ref(false)
const form = reactive({ name: '' })
const tenantName = computed(() => auth.tenant?.name || form.name)
const initial = computed(() => (tenantName.value || '工').trim().slice(0, 1))
onMounted(() => { form.name = auth.tenant?.name || '' })
async function save() { if (!form.name) { ElMessage.error('请填写租户名称'); return } saving.value = true; try { const response = await http.patch('/tenant/profile', { name: form.name }); auth.tenant = { ...(auth.tenant || {}), ...(response.data?.data || {}), name: form.name }; ElMessage.success('租户名称已保存') } catch (cause) { ElMessage.error(cause.response?.data?.msg || '保存租户名称失败') } finally { saving.value = false } }
</script>

<style scoped>
.tenant-profile { min-height: calc(100vh - 64px); box-sizing: border-box; padding: 52px 24px; background: radial-gradient(circle at 75% 0%, #e8efff 0, transparent 32%), #f7f8fc; }.profile-card { width: min(100%, 560px); margin: 0 auto; padding: 36px; border: 1px solid #e0e6f2; border-radius: 16px; background: #fff; box-shadow: 0 18px 46px rgba(28, 54, 109, .1); }.eyebrow { color: #526aaf; font-size: 11px; font-weight: 800; letter-spacing: .12em; }h1 { margin: 7px 0; color: #17284a; font-size: 30px; letter-spacing: -.03em; }.profile-card header p { margin: 0; color: #74819a; line-height: 1.65; }.identity-band { display: flex; align-items: center; gap: 13px; margin: 28px 0; padding: 14px; border: 1px solid #dce5f8; border-radius: 10px; background: #f4f7ff; }.identity-band__mark { display: grid; width: 38px; height: 38px; place-items: center; border-radius: 10px; background: #294b8d; color: #fff; font-size: 18px; font-weight: 800; }.identity-band small, .identity-band strong { display: block; }.identity-band small { color: #71809a; font-size: 12px; }.identity-band strong { margin-top: 3px; color: #263c6b; }@media (max-width: 540px) { .tenant-profile { padding: 24px 16px; }.profile-card { padding: 24px 20px; } }@media (prefers-reduced-motion: reduce) { * { transition: none !important; } }
</style>
