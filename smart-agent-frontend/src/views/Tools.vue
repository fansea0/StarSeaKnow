<template>
  <div class="tools-container">
    <aside class="sidebar">
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
    <main class="main-content">
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
.tools-container {
  display: flex;
  height: calc(100vh - 88px);
  background: #fff;
  border-radius: 8px;
  box-shadow: 0 2px 12px rgba(0, 0, 0, 0.1);
  margin: 24px;
}

.sidebar {
  width: 200px;
  border-right: 1px solid #e6e6e6;
  background: #fff;
  border-radius: 8px 0 0 8px;
}

.side-menu {
  border-right: none;
  padding-top: 16px;
}

.side-menu :deep(.el-menu-item) {
  height: 40px;
  line-height: 40px;
  padding-left: 20px;
}

.main-content {
  flex: 1;
  padding: 24px;
  overflow-y: auto;
  background: #fff;
  border-radius: 0 8px 8px 0;
}
</style> 