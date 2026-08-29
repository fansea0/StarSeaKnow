<template>
  <div class="detail-workbench knowledge-workbench">
    <aside class="settings-pane secondary-nav">
      <el-menu :default-active="activeTab" @select="activeTab = $event" class="side-menu">
        <el-menu-item index="docs">文档</el-menu-item>
        <el-menu-item index="settings">设置</el-menu-item>
      </el-menu>
    </aside>
    <main class="content-pane">
      <div v-if="activeTab === 'docs'">
        <div class="docs-header-row">
          <h3>文档列表</h3>
          <el-upload
            class="upload-btn"
            :action="uploadUrl"
            :show-file-list="false"
            :before-upload="beforeUpload"
            :data="{ knowledgeId }"
            :headers="{ }"
            :on-success="onUploadSuccess"
            :on-error="onUploadError"
          >
            <el-button data-testid="upload-document" type="primary" icon="el-icon-upload">上传文档</el-button>
          </el-upload>
        </div>
        <div class="document-table-wrap">
          <el-table :data="docList" border style="width: 100%; margin-top: 16px;">
          <el-table-column prop="fileName" label="文件名称" min-width="180" />
          <el-table-column label="文件大小" min-width="100">
            <template #default="scope">
              {{ (scope.row.size / 1024).toFixed(2) }} KB
            </template>
          </el-table-column>
          <el-table-column prop="type" label="类型" min-width="80" />
          <el-table-column label="状态" min-width="90">
            <template #default="scope">
              <el-switch
                v-model="scope.row.status"
                :active-value="1"
                :inactive-value="0"
                :loading="statusLoadingId===scope.row.id"
                @change="changeFileStatus(scope.row)"
              />
            </template>
          </el-table-column>
          <el-table-column label="嵌入状态" min-width="110">
            <template #default="scope">
              <el-tooltip v-if="scope.row.embeddingStatus === 0" content="未嵌入"><el-icon style="color:#909399"><Minus /></el-icon></el-tooltip>
              <el-tooltip v-else-if="scope.row.embeddingStatus === 1" content="失败"><el-icon style="color:#f56c6c"><Close /></el-icon></el-tooltip>
              <el-tooltip v-else-if="scope.row.embeddingStatus === 2" content="成功"><el-icon style="color:#67c23a"><Check /></el-icon></el-tooltip>
            </template>
          </el-table-column>
          <el-table-column label="操作" min-width="160">
            <template #default="scope">
              <el-button size="small" type="primary" @click="embedFile(scope.row)" :loading="embedLoadingId===scope.row.id">文本嵌入</el-button>
              <el-button size="small" type="danger" @click="deleteFile(scope.row)" :loading="deleteLoadingId===scope.row.id">删除文件</el-button>
            </template>
          </el-table-column>
          </el-table>
        </div>
      </div>
      <div v-else-if="activeTab === 'settings'">
        <h3>知识库设置</h3>
        <el-form label-width="80px" style="max-width: 400px; margin-top: 16px;">
          <el-form-item label="名称">
            <el-input v-model="kbInfo.name" />
          </el-form-item>
          <el-form-item label="描述">
            <el-input v-model="kbInfo.desc" type="textarea" rows="3" />
          </el-form-item>
          <el-form-item>
            <el-button data-testid="save-knowledge" type="primary" @click="saveKnowledge" :loading="saveLoading">保存</el-button>
          </el-form-item>
        </el-form>
      </div>
    </main>
  </div>
</template>

<script>
import axios from 'axios'
import { apiUrl } from '../api/http'
import { Check, Close, Minus } from '@element-plus/icons-vue'
export default {
  name: 'KnowledgeDetail',
  components: { Check, Close, Minus },
  data() {
    return {
      activeTab: 'docs',
      kbInfo: {
        name: 'MaxKB 用户手册',
        desc: 'MaxKB 用户手册说明',
      },
      docList: [],
      knowledgeId: null,
      uploadUrl: '',
      embedLoadingId: null,
      deleteLoadingId: null,
      statusLoadingId: null,
      saveLoading: false
    }
  },
  mounted() {
    this.setKnowledgeId()
    this.fetchKnowledgeInfo()
    this.fetchDocList()
  },
  watch: {
    '$route.params.id': {
      immediate: true,
      handler() {
        this.setKnowledgeId()
        this.fetchKnowledgeInfo()
        this.fetchDocList()
      }
    }
  },
  methods: {
    setKnowledgeId() {
      this.knowledgeId = parseInt(this.$route.params.id)
      this.uploadUrl = apiUrl(`/file/uploadToKnow/${this.knowledgeId}`)
    },
    async fetchKnowledgeInfo() {
      try {
        const res = await axios.get(apiUrl(`/knowledge/${this.knowledgeId}`))
        if (res.data && res.data.code === 200 && res.data.data) {
          this.kbInfo.name = res.data.data.name
          this.kbInfo.desc = res.data.data.description
        }
      } catch (e) {
        this.$message.error('获取知识库信息失败')
      }
    },
    async fetchDocList() {
      try {
        const res = await axios.get(apiUrl('/knowledge/file/list'), { params: { knowledgeId: parseInt(this.knowledgeId) } })
        if (res.data && res.data.code === 200) {
          this.docList = res.data.data || []
        } else {
          this.$message.error(res.data.msg || '获取文件列表失败')
        }
      } catch (e) {
        this.$message.error('网络错误，获取文件列表失败')
      }
    },
    beforeUpload(file) {
      // 可加类型/大小校验
      return true
    },
    onUploadSuccess() {
      this.$message.success('上传成功')
      this.fetchDocList()
    },
    onUploadError() {
      this.$message.error('上传失败')
    },
    async embedFile(row) {
      this.embedLoadingId = row.id
      try {
        const res = await axios.post(apiUrl('/knowledge/file'), null, { params: { fileId: row.id, knowledgeId: this.knowledgeId } })
        if (res.data && res.data.code === 200) {
          this.$message.success('文本嵌入成功')
          this.fetchDocList()
        } else {
          this.$message.error(res.data.msg || '嵌入失败')
        }
      } catch (e) {
        this.$message.error('网络错误，嵌入失败')
      } finally {
        this.embedLoadingId = null
      }
    },
    async deleteFile(row) {
      this.deleteLoadingId = row.id
      try {
        const res = await axios.delete(apiUrl(`/file/delete/${row.id}`))
        if (res.data && res.data.code === 200) {
          this.$message.success('删除成功')
          this.fetchDocList()
        } else {
          this.$message.error(res.data.msg || '删除失败')
        }
      } catch (e) {
        this.$message.error('网络错误，删除失败')
      } finally {
        this.deleteLoadingId = null
      }
    },
    async changeFileStatus(row) {
      this.statusLoadingId = row.id
      try {
        const res = await axios.put(apiUrl(`/file/updateStatus/${row.id}`), null, {
          params: { status: row.status }
        })
        if (res.data && res.data.code === 200) {
          this.$message.success('状态已切换')
          this.fetchDocList()
        } else {
          this.$message.error(res.data.msg || '切换失败')
          row.status = row.status === 1 ? 0 : 1 // 回滚
        }
      } catch (e) {
        this.$message.error('切换失败')
        row.status = row.status === 1 ? 0 : 1 // 回滚
      } finally {
        this.statusLoadingId = null
      }
    },
    async saveKnowledge() {
      this.saveLoading = true
      try {
        const res = await axios.put(apiUrl(`/knowledge/update/${this.knowledgeId}`), {
          name: this.kbInfo.name,
          description: this.kbInfo.desc
        })
        if (res.data && res.data.code === 200) {
          this.$message.success('保存成功')
        } else {
          this.$message.error(res.data.msg || '保存失败')
        }
      } catch (e) {
        this.$message.error('网络错误，保存失败')
      } finally {
        this.saveLoading = false
      }
    }
  }
}
</script>

<style scoped>
.detail-workbench {
  display: flex;
  min-height: 600px;
  border: 1px solid color-mix(in srgb, var(--sea-mist) 72%, var(--sea-muted));
  border-radius: 12px;
  background: var(--sea-paper);
  box-shadow: 0 12px 32px rgb(17 36 59 / 8%);
  overflow: hidden;
}
.settings-pane {
  width: 196px;
  flex: 0 0 196px;
  background: color-mix(in srgb, var(--sea-mist) 52%, var(--sea-paper));
  border-right: 1px solid color-mix(in srgb, var(--sea-mist) 72%, var(--sea-muted));
  padding: 20px 12px;
}
.side-menu {
  border: none;
  background: transparent;
}
.side-menu :deep(.el-menu-item) {
  min-width: 156px;
  margin: 4px 0;
  border-radius: 7px;
  color: var(--sea-muted);
  font-weight: 600;
}
.side-menu :deep(.el-menu-item.is-active) {
  background: color-mix(in srgb, var(--sea-signal) 12%, var(--sea-paper));
  color: var(--sea-ink);
}
.content-pane {
  flex: 1;
  min-width: 0;
  padding: 32px 36px;
  background: var(--sea-paper);
}
.docs-header-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 12px;
}
.docs-header-row h3,
.content-pane h3 {
  margin: 0;
  color: var(--sea-deep);
  font-family: 'Noto Serif SC', serif;
}
.upload-btn {
  flex: 0 0 auto;
}
.document-table-wrap {
  width: 100%;
  overflow-x: auto;
}

@media (max-width: 720px) {
  .detail-workbench {
    min-height: 0;
    flex-direction: column;
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

  .side-menu :deep(.el-menu-item) { min-width: 112px; }
  .content-pane { min-height: 50vh; padding: 24px 18px; }
  .docs-header-row { align-items: flex-start; flex-direction: column; }
}
</style>
