<template>
  <div class="tool-workbench">
    <aside class="settings-pane secondary-nav">
      <el-menu
        :default-active="activeTool"
        class="side-menu"
        @select="handleToolSelect"
      >
        <el-menu-item index="md-converter">
          <el-icon><Document /></el-icon>
          <span>MD文件转化</span>
        </el-menu-item>
      </el-menu>
    </aside>
    <main class="tool-content-pane">
      <router-view></router-view>
    </main>
  </div>
</template>

<script>
import { Document } from '@element-plus/icons-vue'

export default {
  name: 'ToolsView',
  components: {
    Document
  },
  data() {
    return {
      activeTool: 'md-converter'
    }
  },
  methods: {
    handleToolSelect(index) {
      this.$router.push(`/tools/${index}`)
    }
  }
}
</script>

<style scoped>
.tool-workbench {
  display: flex;
  min-height: calc(100vh - 112px);
  margin: 24px 0;
  overflow: hidden;
  border: 1px solid color-mix(in srgb, var(--sea-mist) 72%, var(--sea-muted));
  border-radius: 12px;
  background: var(--sea-paper);
  box-shadow: 0 12px 32px rgb(17 36 59 / 8%);
}

.settings-pane {
  width: 196px;
  flex: 0 0 196px;
  padding: 20px 12px;
  border-right: 1px solid color-mix(in srgb, var(--sea-mist) 72%, var(--sea-muted));
  background: color-mix(in srgb, var(--sea-mist) 52%, var(--sea-paper));
}

.side-menu {
  border: none;
  background: transparent;
}

.side-menu :deep(.el-menu-item) {
  min-width: 156px;
  height: 44px;
  margin: 4px 0;
  border-radius: 7px;
  color: var(--sea-muted);
  font-weight: 600;
}

.side-menu :deep(.el-menu-item.is-active) {
  background: color-mix(in srgb, var(--sea-signal) 12%, var(--sea-paper));
  color: var(--sea-ink);
}

.tool-content-pane {
  flex: 1;
  min-width: 0;
  padding: 32px 36px;
  overflow-y: auto;
  background: var(--sea-paper);
}

@media (max-width: 720px) {
  .tool-workbench {
    min-height: 0;
    flex-direction: column;
    margin: 16px 0;
  }

  .settings-pane {
    width: 100%;
    flex-basis: auto;
    overflow-x: auto;
    border-right: 0;
    border-bottom: 1px solid color-mix(in srgb, var(--sea-mist) 72%, var(--sea-muted));
    padding: 10px 12px;
  }

  .side-menu {
    display: flex;
    width: max-content;
    min-width: 100%;
  }

  .side-menu :deep(.el-menu-item) { min-width: 164px; }
  .tool-content-pane { min-height: 50vh; padding: 24px 18px; }
}
</style>
