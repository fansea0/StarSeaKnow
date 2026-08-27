<template>
  <main class="system-shell" aria-label="平台系统管理">
    <aside class="system-sidebar">
      <div class="system-sidebar__heading">
        <span class="system-sidebar__eyebrow">PLATFORM CONTROL</span>
        <h1>系统管理</h1>
        <p>租户运营与平台治理</p>
      </div>
      <el-menu :default-active="route.path" router class="system-menu">
        <el-menu-item index="/system/overview">运营概览</el-menu-item>
        <el-menu-item index="/system/tenants">租户管理</el-menu-item>
      </el-menu>
    </aside>
    <section class="system-workspace"><router-view /></section>
  </main>
</template>

<script setup>
import { useRoute } from 'vue-router'
const route = useRoute()
</script>

<style scoped>
.system-shell { display: grid; grid-template-columns: 220px minmax(0, 1fr); min-height: calc(100vh - 88px); background: #f7f8fc; color: #1d2742; }
.system-sidebar { padding: 28px 16px; border-right: 1px solid #e4e8f2; background: #fff; }
.system-sidebar__heading { padding: 0 12px 24px; }.system-sidebar__eyebrow { color: #6675a6; font-size: 10px; font-weight: 700; letter-spacing: .14em; }h1 { margin: 8px 0 4px; color: #16213d; font-size: 22px; }.system-sidebar p { margin: 0; color: #74809a; font-size: 13px; }.system-menu { border: 0; }.system-menu :deep(.el-menu-item) { border-radius: 8px; margin-bottom: 6px; color: #59657d; font-weight: 600; }.system-menu :deep(.el-menu-item.is-active) { background: #eaf0ff; color: #315fc7; }.system-workspace { min-width: 0; padding: 28px 32px 48px; }@media (max-width: 720px) { .system-shell { grid-template-columns: 1fr; }.system-sidebar { padding: 18px; border-right: 0; border-bottom: 1px solid #e4e8f2; }.system-sidebar__heading { padding-bottom: 12px; }.system-menu { display: flex; }.system-menu :deep(.el-menu-item) { flex: 1; justify-content: center; }.system-workspace { padding: 20px 16px 32px; } }@media (prefers-reduced-motion: reduce) { * { transition: none !important; } }
</style>
