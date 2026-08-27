<template>
  <div class="app-root">
    <template v-if="isPublicPage">
      <router-view />
    </template>

    <div v-else class="workspace-shell">
      <aside class="workspace-sidebar">
        <router-link class="brand" to="/knowledge">
          <img src="./assets/logo.png" alt="" class="brand__mark" />
          <span>小达智能体</span>
        </router-link>

        <nav class="workspace-navigation" aria-label="工作区导航">
          <el-menu :default-active="activeMenu" router class="workspace-menu">
            <template v-if="auth.user?.role !== 'platform_admin'">
              <el-menu-item index="/agent">智能体</el-menu-item>
              <el-menu-item index="/knowledge">知识库</el-menu-item>
              <el-menu-item index="/tools">工具</el-menu-item>
            </template>
            <el-menu-item v-if="auth.user?.role === 'tenant_admin'" index="/tenant/members">成员</el-menu-item>
            <el-menu-item v-if="auth.user?.role === 'tenant_admin'" index="/tenant/profile">租户设置</el-menu-item>
            <el-menu-item v-if="auth.user?.role === 'platform_admin'" index="/system">系统管理</el-menu-item>
          </el-menu>
        </nav>

        <div v-if="auth.user" class="account-actions">
          <span class="account-actions__name">{{ auth.user.displayName || auth.user.username }}</span>
          <el-button type="text" @click="auth.logout()">退出</el-button>
        </div>
      </aside>

      <main class="workspace-main">
        <div class="tide-line" :data-section="activeSection" :aria-label="`当前工作区：${activeSectionLabel}`">
          <span aria-hidden="true"></span>
        </div>
        <router-view />
      </main>
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { useAuthStore } from './stores/auth'

const auth = useAuthStore()
const route = useRoute()
const activeMenu = ref(route.path)
const publicPaths = new Set(['/login', '/register', '/403', '/change-initial-password'])

const isPublicPage = computed(() => (
  route.matched.some((record) => record.meta.public) ||
  publicPaths.has(route.path) ||
  route.path.startsWith('/accept-invite/')
))

const activeSection = computed(() => {
  if (route.path.startsWith('/agent')) return 'agent'
  if (route.path.startsWith('/knowledge')) return 'knowledge'
  if (route.path.startsWith('/tools')) return 'tools'
  if (route.path.startsWith('/tenant')) return 'tenant'
  if (route.path.startsWith('/system')) return 'system'
  return 'workspace'
})

const sectionLabels = {
  agent: '智能体',
  knowledge: '知识库',
  tools: '工具',
  tenant: '租户管理',
  system: '系统管理',
  workspace: '工作区',
}

const activeSectionLabel = computed(() => sectionLabels[activeSection.value])

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
  background: var(--sea-mist);
}

.workspace-shell {
  display: grid;
  grid-template-columns: 248px minmax(0, 1fr);
  min-height: 100vh;
  width: 100%;
}

.workspace-sidebar {
  position: sticky;
  top: 0;
  display: flex;
  flex-direction: column;
  min-width: 0;
  height: 100vh;
  padding: 24px 16px 18px;
  background: var(--sea-deep);
  color: color-mix(in srgb, var(--sea-paper) 88%, var(--sea-mist));
}

.brand {
  display: inline-flex;
  align-items: center;
  gap: 10px;
  min-height: 48px;
  padding: 0 8px 22px;
  border-bottom: 1px solid color-mix(in srgb, var(--sea-mist) 18%, transparent);
  color: var(--sea-paper);
  font-family: 'Noto Serif SC', serif;
  font-size: 19px;
  font-weight: 700;
  letter-spacing: 0.04em;
  text-decoration: none;
}

.brand__mark {
  width: 32px;
  height: 32px;
  object-fit: contain;
}

.workspace-navigation {
  flex: 1;
  min-height: 0;
  padding-top: 18px;
}

.workspace-menu {
  border-right: 0;
  background: transparent;
}

.workspace-menu .el-menu-item {
  height: 44px;
  margin: 3px 0;
  padding: 0 12px !important;
  border-radius: 8px;
  color: color-mix(in srgb, var(--sea-mist) 82%, var(--sea-muted));
  font-size: 15px;
  line-height: 44px;
}

.workspace-menu .el-menu-item:hover {
  background: color-mix(in srgb, var(--sea-mist) 10%, transparent);
  color: var(--sea-paper);
}

.workspace-menu .el-menu-item.is-active {
  border-right: 0;
  background: color-mix(in srgb, var(--sea-signal) 22%, transparent);
  color: color-mix(in srgb, var(--sea-paper) 80%, var(--sea-signal));
  font-weight: 600;
}

.account-actions {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  min-height: 52px;
  padding: 12px 8px 0;
  border-top: 1px solid color-mix(in srgb, var(--sea-mist) 18%, transparent);
}

.account-actions__name {
  min-width: 0;
  overflow: hidden;
  color: color-mix(in srgb, var(--sea-paper) 88%, var(--sea-mist));
  font-size: 14px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.account-actions .el-button {
  min-width: 40px;
  color: color-mix(in srgb, var(--sea-mist) 75%, var(--sea-signal));
}

.account-actions .el-button:hover {
  color: color-mix(in srgb, var(--sea-paper) 92%, var(--sea-signal));
}

.workspace-main {
  min-width: 0;
  max-width: 100%;
  padding: 0 32px 36px;
}

.tide-line {
  display: flex;
  align-items: center;
  height: 44px;
  margin-bottom: 12px;
  border-bottom: 1px solid color-mix(in srgb, var(--sea-mist) 85%, var(--sea-muted));
}

.tide-line span {
  position: relative;
  display: block;
  width: 64px;
  height: 2px;
  background: var(--sea-signal);
  transition: width 180ms ease;
}

.tide-line span::before {
  position: absolute;
  top: 50%;
  left: 0;
  width: 8px;
  height: 8px;
  border: 2px solid var(--sea-mist);
  border-radius: 50%;
  background: var(--sea-signal);
  content: '';
  transform: translateY(-50%);
}

.tide-line[data-section='agent'] span { width: 92px; }
.tide-line[data-section='knowledge'] span { width: 120px; }
.tide-line[data-section='tools'] span { width: 76px; }
.tide-line[data-section='tenant'] span { width: 104px; }
.tide-line[data-section='system'] span { width: 112px; }

@media (max-width: 1024px) and (min-width: 721px) {
  .workspace-shell { grid-template-columns: 196px minmax(0, 1fr); }
  .workspace-sidebar { padding-right: 12px; padding-left: 12px; }
  .workspace-main { padding-right: 24px; padding-left: 24px; }
}

@media (max-width: 720px) {
  .workspace-shell {
    display: flex;
    flex-direction: column;
    min-width: 0;
  }

  .workspace-sidebar {
    position: static;
    width: 100%;
    height: auto;
    min-height: 0;
    padding: 0;
  }

  .brand {
    min-height: 52px;
    padding: 10px 16px;
    border-bottom: 1px solid color-mix(in srgb, var(--sea-mist) 18%, transparent);
  }

  .workspace-navigation {
    width: 100%;
    overflow-x: auto;
    padding: 0;
    -webkit-overflow-scrolling: touch;
  }

  .workspace-menu {
    display: flex;
    width: max-content;
    min-width: 100%;
    padding: 8px 12px;
  }

  .workspace-menu .el-menu-item {
    flex: 0 0 auto;
    margin: 0 2px;
  }

  .account-actions {
    min-height: 48px;
    padding: 8px 16px;
  }

  .workspace-main {
    width: 100%;
    min-width: 0;
    padding: 0 16px 24px;
  }

  .tide-line {
    height: 38px;
    margin-bottom: 8px;
  }
}
</style>
