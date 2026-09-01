<template>
  <main class="kb-detail">
    <!-- ============ Hero 区 ============ -->
    <header class="kb-hero">
      <div class="kb-hero__intro">
        <div class="kb-hero__crumbs">
          <a
            href="/knowledge"
            class="kb-hero__crumb-link"
            @click.prevent="goBackToList"
          >知识库</a>
          <span class="kb-hero__crumb-sep">/</span>
          <span class="kb-hero__crumb-current">{{ kbInfo.name || '未命名知识库' }}</span>
        </div>
        <div class="kb-hero__eyebrow">
          <span class="kb-hero__id">KB-{{ String(knowledgeId || 0).padStart(4, '0') }}</span>
          <span class="kb-hero__divider"></span>
          <span class="kb-hero__eyebrow-label">知识库</span>
        </div>
        <h1 class="kb-hero__title">{{ kbInfo.name || '未命名知识库' }}</h1>
        <p class="kb-hero__desc">{{ kbInfo.desc || '尚未添加描述。' }}</p>
      </div>
      <dl class="kb-hero__stats" aria-label="知识库统计">
        <div class="kb-hero__stat">
          <dt>文档</dt>
          <dd>{{ String(docList.length).padStart(2, '0') }}</dd>
        </div>
        <div class="kb-hero__stat">
          <dt>总大小</dt>
          <dd>{{ formatTotalSize }}<span class="kb-hero__unit">KB</span></dd>
        </div>
        <div class="kb-hero__stat">
          <dt>嵌入完成</dt>
          <dd>{{ embedProgress }}<span class="kb-hero__unit">%</span></dd>
        </div>
      </dl>
    </header>

    <!-- ============ Workbench：侧栏 + 内容 ============ -->
    <div class="kb-workbench">
      <aside class="kb-side">
        <nav class="kb-side__nav" aria-label="知识库导航">
          <button
            type="button"
            class="kb-side__item"
            :class="{ 'is-active': activeTab === 'docs' }"
            @click="activeTab = 'docs'"
          >
            <el-icon class="kb-side__icon"><Document /></el-icon>
            <span class="kb-side__label">文档</span>
            <span class="kb-side__count mono">{{ String(docList.length).padStart(2, '0') }}</span>
          </button>
          <button
            type="button"
            class="kb-side__item"
            :class="{ 'is-active': activeTab === 'settings' }"
            @click="activeTab = 'settings'"
          >
            <el-icon class="kb-side__icon"><Setting /></el-icon>
            <span class="kb-side__label">设置</span>
          </button>
        </nav>
      </aside>

      <section class="kb-content">
        <!-- 文档 Tab -->
        <div v-show="activeTab === 'docs'" class="kb-docs">
          <!-- Toolbar -->
          <div class="kb-toolbar">
            <div class="kb-toolbar__tabs" role="tablist">
              <button
                v-for="opt in statusFilterOptions"
                :key="opt.value"
                type="button"
                class="kb-tab"
                :class="{ 'is-active': statusFilter === opt.value }"
                :aria-selected="statusFilter === opt.value"
                @click="statusFilter = opt.value"
              >{{ opt.label }}</button>
            </div>
            <div class="kb-toolbar__right">
              <div class="kb-filter-chip">
                <el-icon><Filter /></el-icon>
                <span>类型：</span>
                <select v-model="typeFilter" class="kb-filter-chip__select" aria-label="按文件类型筛选">
                  <option value="all">全部</option>
                  <option value="pdf">PDF</option>
                  <option value="docx">DOCX</option>
                  <option value="md">MD</option>
                  <option value="txt">TXT</option>
                </select>
              </div>
              <el-upload
                class="kb-upload"
                :action="uploadUrl"
                :show-file-list="false"
                :before-upload="beforeUpload"
                :data="{ knowledgeId }"
                :headers="{ }"
                :on-success="onUploadSuccess"
                :on-error="onUploadError"
              >
                <el-button
                  data-testid="upload-document"
                  type="primary"
                  class="kb-upload__btn"
                >
                  <el-icon><Upload /></el-icon>
                  <span>上传文档</span>
                </el-button>
              </el-upload>
            </div>
          </div>

          <!-- 文档列表 -->
          <div class="kb-table-wrap">
            <table class="kb-table">
              <thead>
                <tr>
                  <th scope="col" class="kb-col-id">编号</th>
                  <th scope="col" class="kb-col-name">名称 / 上传于</th>
                  <th scope="col" class="kb-col-size">大小</th>
                  <th scope="col" class="kb-col-type">类型</th>
                  <th scope="col" class="kb-col-status">状态</th>
                  <th scope="col" class="kb-col-embed">分块状态</th>
                  <th scope="col" class="kb-col-actions" style="text-align:right">操作</th>
                </tr>
              </thead>
              <tbody>
                <tr
                  v-for="row in filteredDocList"
                  :key="row.id"
                  class="kb-row"
                >
                  <td class="kb-col-id mono">DOC-{{ String(row.id).padStart(3, '0') }}</td>
                  <td class="kb-col-name">
                    <div class="kb-row__name">
                      <span class="kb-row__badge mono">{{ (row.type || '?').toUpperCase() }}</span>
                      <div class="kb-row__meta">
                        <span class="kb-row__title">{{ row.fileName }}</span>
                        <span class="kb-row__sub mono">上传于 {{ formatDate(row.createTime) }}</span>
                      </div>
                    </div>
                  </td>
                  <td class="kb-col-size mono">{{ formatSize(row.size) }}</td>
                  <td class="kb-col-type">
                    <span class="kb-type-pill mono">{{ (row.type || '?').toLowerCase() }}</span>
                  </td>
                  <td class="kb-col-status">
                    <el-switch
                      v-model="row.status"
                      :active-value="1"
                      :inactive-value="0"
                      :loading="statusLoadingId === row.id"
                      class="kb-switch"
                      @change="changeFileStatus(row)"
                    />
                  </td>
                  <td class="kb-col-embed">
                    <span class="kb-embed" :class="`kb-embed--${pipelineClass(row.pipelineState)}`">
                      <span class="kb-embed__dot"></span>
                      <el-icon v-if="row.pipelineState === 6" class="kb-embed__icon"><Check /></el-icon>
                      <el-icon v-else-if="row.pipelineState === 7" class="kb-embed__icon"><Close /></el-icon>
                      <el-icon v-else class="kb-embed__icon"><Minus /></el-icon>
                      <span>{{ pipelineLabel(row.pipelineState) }}</span>
                    </span>
                  </td>
                  <td class="kb-col-actions">
                    <div class="kb-row__actions">
                      <el-button
                        type="primary"
                        size="small"
                        class="kb-action kb-action--primary"
                        :data-testid="`file-primary-action-${row.id}`"
                        :disabled="!isMarkdown(row)"
                        @click="handleFileAction(row)"
                      >
                        <el-icon><MagicStick /></el-icon>
                        <span>{{ isMarkdown(row) ? '分块管理' : '暂不支持' }}</span>
                      </el-button>
                      <el-button
                        text
                        type="primary"
                        class="kb-action kb-action--icon"
                        :aria-label="`预览 ${row.fileName}`"
                        title="预览"
                        @click="previewFile(row)"
                      >
                        <el-icon><View /></el-icon>
                      </el-button>
                      <el-button
                        text
                        type="primary"
                        class="kb-action kb-action--icon"
                        :aria-label="`下载 ${row.fileName}`"
                        title="下载"
                        @click="downloadFile(row)"
                      >
                        <el-icon><Download /></el-icon>
                      </el-button>
                      <el-button
                        text
                        type="danger"
                        class="kb-action kb-action--icon kb-action--danger"
                        :aria-label="`删除 ${row.fileName}`"
                        title="删除"
                        :loading="deleteLoadingId === row.id"
                        @click="deleteFile(row)"
                      >
                        <el-icon><Delete /></el-icon>
                      </el-button>
                    </div>
                  </td>
                </tr>
                <tr v-if="!docList.length">
                  <td colspan="7" class="kb-empty">
                    <el-icon class="kb-empty__icon"><FolderOpened /></el-icon>
                    <p class="kb-empty__title">这个知识库还没有文档</p>
                    <p class="kb-empty__desc">上传第一份文档，智能体就可以开始引用这里的内容回答问题。</p>
                  </td>
                </tr>
                <tr v-else-if="!filteredDocList.length">
                  <td colspan="7" class="kb-empty kb-empty--quiet">
                    <p>没有匹配当前筛选的文档</p>
                  </td>
                </tr>
              </tbody>
            </table>
          </div>

          <!-- 底部拖拽提示 -->
          <div class="kb-dropzone" aria-label="拖拽上传">
            <el-icon class="kb-dropzone__icon"><UploadFilled /></el-icon>
            <span>拖拽文件到这里上传 · 支持 PDF / DOCX / MD / TXT</span>
          </div>
        </div>

        <!-- 设置 Tab -->
        <div v-show="activeTab === 'settings'" class="kb-settings">
          <header class="kb-settings__header">
            <h2 class="kb-settings__title">知识库设置</h2>
            <p class="kb-settings__desc">修改知识库的名称与描述，会同步到所有引用它的智能体。</p>
          </header>
          <el-form
            label-width="80px"
            class="kb-settings__form"
            @submit.prevent="saveKnowledge"
          >
            <el-form-item label="名称">
              <el-input v-model="kbInfo.name" maxlength="32" show-word-limit />
            </el-form-item>
            <el-form-item label="描述">
              <el-input v-model="kbInfo.desc" type="textarea" :rows="4" maxlength="200" show-word-limit />
            </el-form-item>
            <el-form-item>
              <el-button
                data-testid="save-knowledge"
                type="primary"
                :loading="saveLoading"
                @click="saveKnowledge"
              >保存设置</el-button>
            </el-form-item>
          </el-form>
        </div>
      </section>
    </div>
  </main>
</template>

<script>
import axios from 'axios'
import { apiUrl } from '../api/http'
import { normalizeFileType } from '../features/chunking/normalization'
import {
  Check,
  Close,
  Delete,
  Document,
  Download,
  Filter,
  FolderOpened,
  MagicStick,
  Minus,
  Setting,
  Upload,
  UploadFilled,
  View,
} from '@element-plus/icons-vue'

export default {
  name: 'KnowledgeDetail',
  components: {
    Check,
    Close,
    Delete,
    Document,
    Download,
    Filter,
    FolderOpened,
    MagicStick,
    Minus,
    Setting,
    Upload,
    UploadFilled,
    View,
  },
  data() {
    return {
      activeTab: 'docs',
      statusFilter: 'all',
      typeFilter: 'all',
      statusFilterOptions: [
        { label: '全部', value: 'all' },
        { label: '已启用', value: 'on' },
        { label: '已禁用', value: 'off' },
      ],
      kbInfo: {
        name: 'MaxKB 用户手册',
        desc: 'MaxKB 用户手册说明',
      },
      docList: [],
      knowledgeId: null,
      uploadUrl: '',
      deleteLoadingId: null,
      statusLoadingId: null,
      saveLoading: false,
      requestGeneration: 0,
    }
  },
  computed: {
    filteredDocList() {
      return this.docList.filter((row) => {
        if (this.statusFilter === 'on' && row.status !== 1) return false
        if (this.statusFilter === 'off' && row.status !== 0) return false
        if (this.typeFilter !== 'all') {
          const t = (row.type || '').toLowerCase()
          if (t !== this.typeFilter) return false
        }
        return true
      })
    },
    formatTotalSize() {
      const total = this.docList.reduce((sum, r) => sum + (Number(r.size) || 0), 0)
      return (total / 1024).toFixed(2)
    },
    embedProgress() {
      if (!this.docList.length) return 0
      const done = this.docList.filter((r) => r.pipelineState === 6).length
      return Math.round((done / this.docList.length) * 100)
    },
  },
  watch: {
    '$route.params.id': {
      immediate: true,
      handler(id) {
        this.startKnowledgeRoute(id)
      },
    },
  },
  methods: {
    startKnowledgeRoute(routeId) {
      this.requestGeneration += 1
      this.setKnowledgeId(routeId)
      const context = this.currentRequestContext()
      this.fetchKnowledgeInfo(context)
      this.fetchDocList(context)
    },
    currentRequestContext() {
      return { generation: this.requestGeneration, knowledgeId: this.knowledgeId }
    },
    isCurrentRequest(context) {
      return context?.generation === this.requestGeneration
        && context?.knowledgeId === this.knowledgeId
    },
    setKnowledgeId(routeId = this.$route.params.id) {
      this.knowledgeId = parseInt(routeId)
      this.uploadUrl = apiUrl(`/file/uploadToKnow/${this.knowledgeId}`)
    },
    async fetchKnowledgeInfo(context = this.currentRequestContext()) {
      try {
        const res = await axios.get(apiUrl(`/knowledge/${context.knowledgeId}`))
        if (!this.isCurrentRequest(context)) return
        if (res.data && res.data.code === 200 && res.data.data) {
          this.kbInfo.name = res.data.data.name
          this.kbInfo.desc = res.data.data.description
        }
      } catch (e) {
        if (this.isCurrentRequest(context)) this.$message.error('获取知识库信息失败')
      }
    },
    async fetchDocList(context = this.currentRequestContext()) {
      try {
        const res = await axios.get(apiUrl('/knowledge/file/list'), {
          params: { knowledgeId: parseInt(context.knowledgeId) },
        })
        if (!this.isCurrentRequest(context)) return
        if (res.data && res.data.code === 200) {
          this.docList = Array.isArray(res.data.data) ? res.data.data : []
        } else {
          this.$message.error(res.data.msg || '获取文件列表失败')
        }
      } catch (e) {
        if (this.isCurrentRequest(context)) this.$message.error('网络错误，获取文件列表失败')
      }
    },
    beforeUpload(file) {
      return true
    },
    async onUploadSuccess(response, uploadFile) {
      const fileId = this.normalizeUploadFileId(response?.data)
      if (!this.isUploadSuccessCode(response?.code) || fileId === null) {
        this.$message.error(response?.msg || '上传失败')
        return
      }

      this.$message.success('上传成功')
      await this.fetchDocList()
      if (this.isMarkdown(uploadFile)) {
        this.openChunkingWorkspace(fileId)
      }
    },
    onUploadError() {
      this.$message.error('上传失败')
    },
    handleFileAction(row) {
      if (this.isMarkdown(row)) {
        this.openChunkingWorkspace(row.id)
        return
      }
      this.$message.info('当前仅支持 Markdown 智能分块，其他文件类型暂不支持。')
    },
    openChunkingWorkspace(fileId) {
      return this.$router.push({
        name: 'ChunkingWorkspace',
        params: { knowledgeId: String(this.knowledgeId), fileId: String(fileId) },
      })
    },
    isMarkdown(file) {
      const type = normalizeFileType(file?.type)
      if (type) return type === 'md' || type === 'markdown'
      const name = String(file?.name || file?.fileName || '')
      return /\.(md|markdown)$/i.test(name)
    },
    isUploadSuccessCode(code) {
      return code === 200 || (typeof code === 'string' && code.trim() === '200')
    },
    normalizeUploadFileId(fileId) {
      if (typeof fileId === 'number') {
        return Number.isSafeInteger(fileId) && fileId > 0 ? fileId : null
      }
      if (typeof fileId !== 'string') return null

      const normalized = fileId.trim()
      if (!/^\d+$/.test(normalized)) return null

      const numericId = Number(normalized)
      return Number.isSafeInteger(numericId) && numericId > 0 ? numericId : null
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
          params: { status: row.status },
        })
        if (res.data && res.data.code === 200) {
          this.$message.success('状态已切换')
          this.fetchDocList()
        } else {
          this.$message.error(res.data.msg || '切换失败')
          row.status = row.status === 1 ? 0 : 1
        }
      } catch (e) {
        this.$message.error('切换失败')
        row.status = row.status === 1 ? 0 : 1
      } finally {
        this.statusLoadingId = null
      }
    },
    async saveKnowledge() {
      this.saveLoading = true
      try {
        const res = await axios.put(apiUrl(`/knowledge/update/${this.knowledgeId}`), {
          name: this.kbInfo.name,
          description: this.kbInfo.desc,
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
    },
    previewFile(row) {
      this.$message.info('预览功能开发中')
    },
    downloadFile(row) {
      this.$message.info('下载功能开发中')
    },
    formatSize(bytes) {
      const n = Number(bytes) || 0
      if (n < 1024) return `${n} B`
      if (n < 1024 * 1024) return `${(n / 1024).toFixed(2)} KB`
      return `${(n / 1024 / 1024).toFixed(2)} MB`
    },
    formatDate(value) {
      if (!value) return '未知'
      const d = new Date(value)
      if (Number.isNaN(d.getTime())) return String(value)
      const y = d.getFullYear()
      const m = String(d.getMonth() + 1).padStart(2, '0')
      const day = String(d.getDate()).padStart(2, '0')
      return `${y}-${m}-${day}`
    },
    goBackToList() {
      this.$router.push('/knowledge')
    },
    pipelineLabel(state) {
      const labels = {
        0: '待分块',
        1: '分块中',
        2: '待调整',
        3: '待调整',
        4: '待调整',
        5: '向量化中',
        6: '已完成',
        7: '失败',
      }
      return labels[state] ?? '未知'
    },
    pipelineClass(state) {
      if (state === 6) return 'ok'
      if (state === 7) return 'fail'
      return 'pending'
    },
  },
}
</script>

<style scoped>
.kb-detail { width: 100%; padding: 12px 0 40px; }

/* ============ Hero ============ */
.kb-hero {
  display: grid;
  grid-template-columns: 1fr auto;
  align-items: end;
  gap: 32px;
  padding: 4px 4px 24px;
  border-bottom: 1px solid #dfe9ee;
  margin-bottom: 24px;
}
.kb-hero__crumbs { display: flex; align-items: center; gap: 8px; margin-bottom: 14px; font-size: 12.5px; }
.kb-hero__crumb-link { color: var(--sea-muted); text-decoration: none; transition: color 160ms ease; }
.kb-hero__crumb-link:hover { color: var(--sea-signal); }
.kb-hero__crumb-sep { color: #b9ccd5; }
.kb-hero__crumb-current { color: var(--sea-ink); font-weight: 500; }

.kb-hero__eyebrow {
  display: inline-flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 10px;
  font-family: 'JetBrains Mono', monospace;
  font-size: 11px;
  letter-spacing: 0.08em;
  text-transform: uppercase;
}
.kb-hero__id { color: var(--sea-signal); font-weight: 500; }
.kb-hero__divider { width: 18px; height: 1px; background: #c9d6dd; }
.kb-hero__eyebrow-label { color: var(--sea-muted); }

.kb-hero__title {
  margin: 0;
  color: var(--sea-deep);
  font-family: 'Noto Serif SC', serif;
  font-size: clamp(28px, 3.4vw, 36px);
  font-weight: 700;
  line-height: 1.1;
  letter-spacing: -0.01em;
}
.kb-hero__desc {
  max-width: 60ch;
  margin: 10px 0 0;
  color: var(--sea-muted);
  font-size: 14px;
  line-height: 1.6;
}

.kb-hero__stats {
  display: flex;
  gap: 36px;
  margin: 0;
  padding: 0;
}
.kb-hero__stat { display: flex; flex-direction: column; gap: 4px; text-align: right; }
.kb-hero__stat dt {
  font-family: 'JetBrains Mono', monospace;
  font-size: 10.5px;
  letter-spacing: 0.1em;
  text-transform: uppercase;
  color: var(--sea-muted);
}
.kb-hero__stat dd {
  margin: 0;
  color: var(--sea-deep);
  font-family: 'Noto Serif SC', serif;
  font-size: 26px;
  font-weight: 700;
  line-height: 1;
}
.kb-hero__unit {
  margin-left: 4px;
  font-family: 'JetBrains Mono', monospace;
  font-size: 11px;
  font-weight: 400;
  color: var(--sea-muted);
  letter-spacing: 0.04em;
}

/* ============ Workbench ============ */
.kb-workbench {
  display: grid;
  grid-template-columns: 200px 1fr;
  min-height: 520px;
  border: 1px solid #d5e1e6;
  border-radius: 12px;
  background: var(--sea-paper);
  box-shadow: 0 12px 32px rgb(17 36 59 / 8%);
  overflow: hidden;
}

/* ============ Side ============ */
.kb-side {
  padding: 20px 12px;
  background: color-mix(in srgb, var(--sea-mist) 52%, var(--sea-paper));
  border-right: 1px solid #dfe9ee;
}
.kb-side__nav { display: flex; flex-direction: column; gap: 2px; }
.kb-side__item {
  display: flex;
  align-items: center;
  gap: 10px;
  width: 100%;
  padding: 9px 12px;
  border: 0;
  border-radius: 7px;
  background: transparent;
  color: var(--sea-muted);
  font: inherit;
  font-size: 13.5px;
  font-weight: 600;
  text-align: left;
  cursor: pointer;
  transition: background 160ms ease, color 160ms ease;
}
.kb-side__item:hover { background: rgba(0, 166, 166, 0.06); color: var(--sea-ink); }
.kb-side__item.is-active {
  background: var(--sea-paper);
  color: var(--sea-ink);
  box-shadow: 0 1px 0 #dfe9ee, 0 4px 12px rgb(17 36 59 / 4%);
}
.kb-side__icon { font-size: 15px; color: inherit; opacity: 0.85; }
.kb-side__item.is-active .kb-side__icon { color: var(--sea-signal); opacity: 1; }
.kb-side__label { flex: 1; }
.kb-side__count {
  font-size: 11px;
  color: var(--sea-muted);
  background: color-mix(in srgb, var(--sea-mist) 80%, transparent);
  padding: 2px 6px;
  border-radius: 4px;
}
.kb-side__item.is-active .kb-side__count {
  background: color-mix(in srgb, var(--sea-signal) 14%, transparent);
  color: var(--sea-signal);
}

/* ============ Content ============ */
.kb-content { flex: 1; min-width: 0; padding: 28px 32px 32px; }

/* ============ Toolbar ============ */
.kb-toolbar {
  display: flex;
  align-items: center;
  gap: 16px;
  margin-bottom: 16px;
}
.kb-toolbar__tabs { display: flex; gap: 0; }
.kb-tab {
  padding: 6px 14px;
  border: 0;
  border-radius: 6px;
  background: transparent;
  color: var(--sea-muted);
  font: inherit;
  font-size: 13px;
  font-weight: 500;
  cursor: pointer;
  transition: background 160ms ease, color 160ms ease;
}
.kb-tab:hover { color: var(--sea-ink); background: color-mix(in srgb, var(--sea-mist) 60%, transparent); }
.kb-tab.is-active { background: var(--sea-deep); color: var(--sea-paper); }
.kb-toolbar__right { margin-left: auto; display: flex; align-items: center; gap: 10px; }
.kb-filter-chip {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 0 12px;
  height: 36px;
  border: 1px solid #cedbe2;
  border-radius: 6px;
  background: var(--sea-paper);
  color: var(--sea-muted);
  font-size: 12.5px;
}
.kb-filter-chip .el-icon { font-size: 13px; }
.kb-filter-chip__select {
  border: 0;
  background: transparent;
  color: var(--sea-ink);
  font: inherit;
  font-size: 12.5px;
  font-weight: 500;
  cursor: pointer;
  outline: none;
}
.kb-upload__btn {
  min-height: 36px;
  padding-inline: 16px;
  font-weight: 600;
  box-shadow: 0 6px 16px rgb(0 166 166 / 22%);
}

/* ============ Table ============ */
.kb-table-wrap { overflow-x: auto; -webkit-overflow-scrolling: touch; }
.kb-table { width: 100%; min-width: 880px; border-collapse: collapse; }

.kb-table thead th {
  padding: 12px 16px;
  background: #f3f7f9;
  border-bottom: 1px solid #e2ebf0;
  color: var(--sea-muted);
  font-family: 'JetBrains Mono', monospace;
  font-size: 10.5px;
  font-weight: 500;
  letter-spacing: 0.08em;
  text-transform: uppercase;
  text-align: left;
}
.kb-table tbody td {
  padding: 14px 16px;
  border-bottom: 1px solid #edf2f5;
  color: #3e5269;
  font-size: 13px;
  vertical-align: middle;
}
.kb-col-id { width: 90px; color: var(--sea-muted); }
.kb-col-size { width: 90px; color: var(--sea-muted); }
.kb-col-type { width: 70px; }
.kb-col-status { width: 70px; }
.kb-col-embed { width: 110px; }
.kb-col-actions { width: 280px; }

.kb-row { transition: background 160ms ease, box-shadow 200ms ease; }
.kb-row:hover {
  background: linear-gradient(180deg, rgba(0, 166, 166, 0.04), transparent);
}

/* 名称区 */
.kb-row__name { display: flex; align-items: center; gap: 12px; min-width: 240px; }
.kb-row__badge {
  flex-shrink: 0;
  display: grid;
  place-items: center;
  width: 36px;
  height: 44px;
  border: 1px solid #d5e1e6;
  border-radius: 4px;
  background: #f3f7f9;
  color: var(--sea-ink);
  font-size: 9px;
  font-weight: 600;
  letter-spacing: 0.04em;
}
.kb-row__meta { display: flex; flex-direction: column; gap: 3px; min-width: 0; }
.kb-row__title {
  display: block;
  overflow: hidden;
  color: var(--sea-deep);
  font-size: 14px;
  font-weight: 600;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.kb-row__sub { font-size: 11px; color: var(--sea-muted); }

/* 类型 pill */
.kb-type-pill {
  display: inline-block;
  padding: 2px 8px;
  border-radius: 3px;
  background: color-mix(in srgb, var(--sea-mist) 70%, transparent);
  color: var(--sea-ink);
  font-size: 10.5px;
  letter-spacing: 0.04em;
}

/* 状态 */
.kb-switch { --el-switch-on-color: var(--sea-signal); }

/* 嵌入状态 */
.kb-embed {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  font-weight: 500;
}
.kb-embed__dot {
  width: 6px; height: 6px;
  border-radius: 50%;
  background: currentColor;
  box-shadow: 0 0 0 3px color-mix(in srgb, currentColor 18%, transparent);
}
.kb-embed__icon { font-size: 12px; }
.kb-embed--ok { color: #10ae77; }
.kb-embed--fail { color: var(--sea-danger); }
.kb-embed--pending { color: var(--sea-muted); }

/* 操作 */
.kb-row__actions { display: flex; align-items: center; gap: 4px; justify-content: flex-end; }
.kb-action { min-width: 0; min-height: 32px; }
.kb-action--primary {
  padding-inline: 12px;
  font-size: 12px;
  box-shadow: 0 4px 10px rgb(0 166 166 / 18%);
}
.kb-action--icon { padding: 4px 6px; font-size: 14px; }
.kb-action--icon .el-icon { font-size: 15px; }
.kb-action--danger:hover { color: var(--sea-danger); }

/* 空状态 */
.kb-empty { padding: 48px 24px !important; text-align: center; }
.kb-empty__icon { color: var(--sea-signal); font-size: 32px; }
.kb-empty__title { margin: 12px 0 4px; color: var(--sea-deep); font-size: 16px; font-weight: 600; }
.kb-empty__desc { margin: 0 auto; max-width: 36ch; color: var(--sea-muted); font-size: 13px; }
.kb-empty--quiet { color: var(--sea-muted); font-size: 13px; padding: 32px 24px !important; }

/* 底部拖拽 */
.kb-dropzone {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  margin-top: 16px;
  padding: 14px;
  border: 1px dashed #c9d6dd;
  border-radius: 8px;
  background: transparent;
  color: var(--sea-muted);
  font-size: 12.5px;
  transition: border-color 160ms ease, background 160ms ease;
}
.kb-dropzone:hover { border-color: var(--sea-signal); background: color-mix(in srgb, var(--sea-signal) 4%, transparent); }
.kb-dropzone__icon { font-size: 14px; }

/* ============ Settings tab ============ */
.kb-settings__header { margin-bottom: 18px; }
.kb-settings__title { margin: 0; color: var(--sea-deep); font-family: 'Noto Serif SC', serif; font-size: 20px; }
.kb-settings__desc { margin: 6px 0 0; color: var(--sea-muted); font-size: 13px; }
.kb-settings__form { max-width: 480px; }

/* ============ Responsive ============ */
@media (max-width: 960px) {
  .kb-hero { grid-template-columns: 1fr; align-items: start; gap: 20px; }
  .kb-hero__stats { gap: 24px; }
  .kb-hero__stat { text-align: left; }
}
@media (max-width: 720px) {
  .kb-workbench { grid-template-columns: 1fr; }
  .kb-side { border-right: 0; border-bottom: 1px solid #dfe9ee; }
  .kb-side__nav { flex-direction: row; }
  .kb-side__item { min-width: 0; flex: 1; }
  .kb-content { padding: 20px 16px 24px; }
  .kb-toolbar { flex-direction: column; align-items: stretch; }
  .kb-toolbar__right { margin-left: 0; flex-wrap: wrap; }
}
</style>
