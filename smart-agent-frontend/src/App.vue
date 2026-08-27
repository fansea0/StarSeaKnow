<template>
  <div class="app-root">
    <header class="main-header">
      <div class="logo-area">
        <img src="./assets/logo.png" alt="logo" class="logo-img" />
        <span class="brand-name">小达智能体</span>
      </div>
      <el-menu :default-active="activeMenu" mode="horizontal" router class="main-menu">
        <template v-if="auth.user?.role !== 'platform_admin'">
          <el-menu-item index="/agent">智能体</el-menu-item>
          <el-menu-item index="/knowledge">知识库</el-menu-item>
          <el-menu-item index="/tools">工具</el-menu-item>
        </template>
        <el-menu-item v-if="auth.user?.role === 'tenant_admin'" index="/tenant/members">成员</el-menu-item>
        <el-menu-item v-if="auth.user?.role === 'tenant_admin'" index="/tenant/profile">租户设置</el-menu-item>
        <el-menu-item v-if="auth.user?.role === 'platform_admin'" index="/system">系统管理</el-menu-item>
      </el-menu>
      <div v-if="auth.user" class="user-area">
        <span class="user-name">{{ auth.user.displayName || auth.user.username }}</span>
        <el-button size="small" type="text" @click="auth.logout()">退出</el-button>
      </div>
    </header>
    <div class="main-content">
      <div class="content-wrapper">
        <router-view></router-view>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, watch, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { useAuthStore } from './stores/auth'

const auth = useAuthStore()
const route = useRoute()
const activeMenu = ref(route.path)

watch(
  () => route.path,
  (to) => {
    activeMenu.value = to
  }
)

onMounted(() => {
  auth.bootstrap()
})
</script>

<style>
.app-root {
  min-height: 100vh;
  width: 100%;
  background: #f5f6fa;
}
.main-header {
  position: fixed;
  top: 0;
  left: 0;
  width: 100%;
  z-index: 100;
  background: #fff;
  box-shadow: 0 2px 8px rgba(0,0,0,0.06);
  display: flex;
  align-items: center;
  padding: 0 32px;
  height: 64px;
}
.logo-area {
  display: flex;
  align-items: center;
  margin-right: 32px;
}
.logo-img {
  width: 36px;
  height: 36px;
  margin-right: 10px;
}
.brand-name {
  font-size: 20px;
  font-weight: bold;
  color: #409eff;
  letter-spacing: 2px;
}
.main-menu {
  flex: 1;
  background: transparent;
  border-bottom: none;
  box-shadow: none;
  min-width: 0;
}
.main-menu .el-menu-item {
  font-size: 16px;
  padding: 0 32px;
  height: 64px;
  line-height: 64px;
  color: #333;
  background: transparent;
  transition: color 0.2s, background 0.2s;
}
.main-menu .el-menu-item.is-active {
  color: #409eff;
  background: #eaf6ff;
  border-bottom: 2.5px solid #409eff;
  font-weight: 600;
}
.main-menu .el-menu-item:hover {
  color: #409eff;
  background: #f4faff;
}
.user-area {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-left: 16px;
}
.user-name {
  font-size: 14px;
  color: #666;
}
.main-content {
  padding-top: 64px;
  min-height: calc(100vh - 64px);
  background: #f5f6fa;
  width: 100vw;
  box-sizing: border-box;
}
#app { display: block; min-height: 100vh; width: 100%; }
.content-wrapper {
  width: 100%;
  max-width: none;
  margin: 0;
  padding: 0;
  box-sizing: border-box;
}
</style>
