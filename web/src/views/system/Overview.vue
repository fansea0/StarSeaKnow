<template>
  <section class="overview-page" aria-labelledby="overview-title">
    <header class="page-heading"><div><span class="eyebrow">实时平台态势</span><h2 id="overview-title">运营概览</h2><p>掌握租户规模、可服务状态与账户总量。</p></div><el-button :loading="loading" plain @click="loadOverview">刷新数据</el-button></header>
    <section class="pulse-strip" aria-label="运营脉搏"><div class="pulse-strip__label"><span>运营脉搏</span><small>LIVE COUNTS</small></div><div v-for="item in metrics" :key="item.key" class="pulse-metric"><span>{{ item.label }}</span><strong>{{ item.value }}</strong></div></section>
    <p v-if="error" class="error-message" role="alert">{{ error }}</p>
  </section>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { http } from '../../api/http'
const loading = ref(false)
const error = ref('')
const overview = ref({ tenantTotal: 0, tenantActive: 0, tenantDisabled: 0, userTotal: 0 })
const metrics = computed(() => [{ key: 'tenantTotal', label: '全部租户', value: overview.value.tenantTotal }, { key: 'tenantActive', label: '正常服务', value: overview.value.tenantActive }, { key: 'tenantDisabled', label: '已停用', value: overview.value.tenantDisabled }, { key: 'userTotal', label: '平台用户', value: overview.value.userTotal }])
async function loadOverview() { loading.value = true; error.value = ''; try { const response = await http.get('/platform/overview'); overview.value = { ...overview.value, ...(response.data?.data || {}) } } catch (cause) { error.value = cause.response?.data?.msg || '概览数据暂时无法加载' } finally { loading.value = false } }
onMounted(loadOverview)
</script>

<style scoped>
.page-heading { display: flex; justify-content: space-between; gap: 20px; align-items: flex-end; margin-bottom: 26px; }.eyebrow { color: #526aaf; font-size: 12px; font-weight: 700; letter-spacing: .08em; }h2 { margin: 5px 0; color: #182541; font-size: 28px; letter-spacing: -.02em; }p { margin: 0; color: #75819a; }.pulse-strip { display: grid; grid-template-columns: 1.35fr repeat(4, 1fr); overflow: hidden; border: 1px solid #dfe5f1; border-radius: 12px; background: #fff; box-shadow: 0 8px 28px rgba(42, 58, 103, .06); }.pulse-strip__label, .pulse-metric { padding: 22px 24px; }.pulse-strip__label { display: flex; flex-direction: column; justify-content: center; background: #202e55; color: #fff; font-weight: 700; }.pulse-strip__label small { margin-top: 5px; color: #b6c2e6; font-size: 10px; letter-spacing: .12em; }.pulse-metric { border-left: 1px solid #e7eaf2; }.pulse-metric span { display: block; color: #75819a; font-size: 13px; }.pulse-metric strong { display: block; margin-top: 7px; color: #253b79; font-size: 29px; line-height: 1; font-variant-numeric: tabular-nums; }.error-message { margin-top: 16px; color: #ba3434; }@media (max-width: 900px) { .pulse-strip { grid-template-columns: repeat(2, 1fr); }.pulse-strip__label { grid-column: span 2; }.pulse-metric:nth-child(4) { border-left: 0; } }@media (max-width: 520px) { .page-heading { align-items: flex-start; flex-direction: column; }.pulse-strip { grid-template-columns: 1fr; }.pulse-strip__label { grid-column: auto; }.pulse-metric, .pulse-metric:nth-child(4) { border-left: 0; border-top: 1px solid #e7eaf2; } }
</style>
