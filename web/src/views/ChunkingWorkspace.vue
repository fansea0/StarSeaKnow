<template>
  <main class="chunking-workspace" aria-label="分块工作区">
    <header class="workspace-heading">
      <div>
        <span class="workspace-heading__eyebrow mono">MARKDOWN / 语义分块</span>
        <h1>分块设置与预览</h1>
        <p>生成、检查并确认这份文档的语义分块。</p>
      </div>
      <el-button @click="reloadWorkspace">重新加载</el-button>
    </header>

    <section class="chunking-layout workspace-panel">
      <ChunkStrategyPanel
        :strategies="strategies"
        :selected-code="selectedStrategy"
        :config-valid="configValid"
        :loading="capabilityLoading"
        :submitting="previewSubmitting"
        :processing="isProcessing || processingLoading"
        :error="capabilityError || submissionError"
        @select="selectStrategy"
        @config-change="strategyConfig = $event"
        @validity-change="configValid = $event"
        @preview="submitPreview"
      />

      <div class="preview-column">
        <div v-if="processing.state === 7" class="failure-banner" role="alert">
          <div>
            <strong>处理未完成</strong>
            <span>{{ processing.lastError || '文件处理失败，请按失败阶段重试。' }}</span>
          </div>
          <el-button
            v-if="processing.failedFromState === 1"
            data-testid="retry-chunking"
            :disabled="!configValid || previewSubmitting"
            @click="submitPreview"
          >重试分块</el-button>
          <el-button
            v-if="processing.failedFromState === 5"
            data-testid="retry-vectorizing"
            :loading="confirmSubmitting"
            @click="retryVectorization"
          >重试建立索引</el-button>
        </div>

        <p v-if="processingError" class="processing-error" role="alert">
          {{ processingError }}
          <el-button link @click="reloadWorkspace">重新加载</el-button>
        </p>

        <ChunkPreviewPanel
          :knowledge-id="knowledgeId"
          :file-id="fileId"
          :chunks="chunks"
          :loading="chunksLoading"
          :file-state="processing.state"
          :progress="processing.progress"
          :processing="isProcessing"
          :processing-label="processingLabel"
          @updated="handleChunkUpdated"
          @deleted="handleChunkDeleted"
          @reload="reloadChunks"
          @reindex="handleReindex"
          @confirm="confirmDialogVisible = true"
        />
      </div>
    </section>

    <ContextConfirmDialog
      v-model="confirmDialogVisible"
      :submitting="confirmSubmitting"
      @confirm="submitVectorization"
    />
  </main>
</template>

<script>
import { ElMessageBox } from 'element-plus'
import {
  confirmVectorization,
  createPreview,
  getChunks,
  getProcessing,
  getStrategies,
  reindexChunk,
} from '../api/chunking'
import { mergeStrategies } from '../features/chunking/strategyCatalog'
import ChunkPreviewPanel from '../components/chunking/ChunkPreviewPanel.vue'
import ChunkStrategyPanel from '../components/chunking/ChunkStrategyPanel.vue'
import ContextConfirmDialog from '../components/chunking/ContextConfirmDialog.vue'

const terminalChunkStates = new Set([2, 3, 6])
const processingStates = new Set([1, 5])

function errorMessage(cause, fallback) {
  return cause?.response?.data?.msg || fallback
}

export default {
  name: 'ChunkingWorkspace',
  components: { ChunkPreviewPanel, ChunkStrategyPanel, ContextConfirmDialog },
  data() {
    return {
      strategies: [],
      selectedStrategy: '',
      strategyConfig: { minTokens: 100, targetTokens: 400, maxTokens: 512 },
      configValid: true,
      chunks: [],
      processing: {
        state: 0,
        failedFromState: null,
        progress: 0,
        lastError: null,
        lockVersion: 0,
        strategyCode: '',
        policySnapshot: {},
        contextPolicy: {},
      },
      capabilityLoading: true,
      processingLoading: true,
      chunksLoading: false,
      previewSubmitting: false,
      confirmSubmitting: false,
      confirmDialogVisible: false,
      capabilityError: '',
      processingError: '',
      submissionError: '',
      pollTimer: null,
      processingRequest: null,
      chunksRequest: null,
      chunksLoadedKey: '',
      destroyed: false,
    }
  },
  computed: {
    knowledgeId() { return this.$route.params.knowledgeId },
    fileId() { return this.$route.params.fileId },
    isProcessing() { return processingStates.has(Number(this.processing.state)) },
    processingLabel() {
      return Number(this.processing.state) === 1 ? '正在生成分块' : '正在建立索引'
    },
  },
  mounted() {
    this.initialize()
  },
  beforeUnmount() {
    this.destroyed = true
    this.stopPolling()
  },
  methods: {
    async initialize() {
      await Promise.all([this.loadCapabilities(), this.refreshProcessing()])
    },
    async loadCapabilities() {
      this.capabilityLoading = true
      this.capabilityError = ''
      try {
        const response = await getStrategies(this.knowledgeId, this.fileId)
        if (this.destroyed) return
        const catalog = mergeStrategies(response?.data?.fileType, response?.data?.strategies)
        this.strategies = catalog
        const backendSelected = catalog.find(strategy => (
          !strategy.disabled && strategy.code === this.processing.strategyCode
        ))
        const markdownDefault = catalog.find(strategy => (
          !strategy.disabled && strategy.code === 'MARKDOWN_OPTIMIZED'
        ))
        this.selectedStrategy = backendSelected?.code || markdownDefault?.code || ''
      } catch (cause) {
        if (!this.destroyed) this.capabilityError = errorMessage(cause, '可用分块策略暂时无法加载。')
      } finally {
        if (!this.destroyed) this.capabilityLoading = false
      }
    },
    selectStrategy(code) {
      const strategy = this.strategies.find(item => item.code === code)
      if (!strategy || strategy.disabled) return
      this.selectedStrategy = code
      this.submissionError = ''
    },
    async refreshProcessing(forceChunkLoad = false) {
      if (this.processingRequest) return this.processingRequest
      this.processingLoading = true
      this.processingError = ''
      this.processingRequest = (async () => {
        try {
          const response = await getProcessing(this.knowledgeId, this.fileId)
          if (this.destroyed) return
          this.processing = { ...this.processing, ...(response?.data || {}) }
          if (forceChunkLoad) this.chunksLoadedKey = ''
          if (terminalChunkStates.has(Number(this.processing.state))) {
            this.stopPolling()
            await this.loadChunksOnce()
          } else if (this.isProcessing) {
            this.schedulePoll()
          } else {
            this.stopPolling()
          }
        } catch (cause) {
          if (!this.destroyed) {
            this.processingError = errorMessage(cause, '文件处理状态暂时无法加载，请重新加载。')
            this.stopPolling()
          }
        } finally {
          if (!this.destroyed) this.processingLoading = false
          this.processingRequest = null
        }
      })()
      return this.processingRequest
    },
    schedulePoll() {
      if (this.destroyed || this.pollTimer || !this.isProcessing) return
      this.pollTimer = setTimeout(async () => {
        this.pollTimer = null
        await this.refreshProcessing()
      }, 1000)
    },
    stopPolling() {
      if (!this.pollTimer) return
      clearTimeout(this.pollTimer)
      this.pollTimer = null
    },
    async loadChunksOnce() {
      const key = `${this.processing.state}:${this.processing.lockVersion}`
      if (this.chunksLoadedKey === key || this.chunksRequest) return this.chunksRequest
      this.chunksLoading = true
      this.chunksRequest = (async () => {
        try {
          const response = await getChunks(this.knowledgeId, this.fileId)
          if (this.destroyed) return
          this.chunks = Array.isArray(response?.data) ? response.data : []
          this.chunksLoadedKey = key
        } catch (cause) {
          if (!this.destroyed) this.processingError = errorMessage(cause, '分块预览暂时无法加载，请重新加载。')
        } finally {
          if (!this.destroyed) this.chunksLoading = false
          this.chunksRequest = null
        }
      })()
      return this.chunksRequest
    },
    async reloadChunks() {
      this.chunksLoadedKey = ''
      await this.loadChunksOnce()
    },
    async reloadWorkspace() {
      this.stopPolling()
      this.chunksLoadedKey = ''
      this.processingError = ''
      this.submissionError = ''
      await Promise.all([this.loadCapabilities(), this.refreshProcessing(true)])
    },
    async submitPreview() {
      if (!this.configValid || this.isProcessing || this.processingLoading) return
      const selected = this.strategies.find(strategy => strategy.code === this.selectedStrategy)
      if (!selected || selected.disabled) return

      let replaceEditedDrafts = false
      if (this.chunks.some(chunk => Number(chunk.status) === 0 && chunk.isModified)) {
        try {
          await ElMessageBox.confirm(
            '当前预览包含人工修改的草稿。重新生成会替换这些人工修改，是否继续？',
            '确认重新生成分块',
            { confirmButtonText: '替换并重新生成', cancelButtonText: '保留当前分块', type: 'warning' },
          )
          replaceEditedDrafts = true
        } catch {
          return
        }
      }

      this.previewSubmitting = true
      this.submissionError = ''
      try {
        await createPreview(this.knowledgeId, this.fileId, {
          strategyCode: this.selectedStrategy,
          strategyConfig: { ...this.strategyConfig },
          replaceEditedDrafts,
          lockVersion: this.processing.lockVersion,
        })
        this.chunksLoadedKey = ''
        await this.refreshProcessing()
      } catch (cause) {
        const status = cause?.response?.status
        this.submissionError = status === 409
          ? errorMessage(cause, '文件状态已变化，请重新加载后再生成分块。')
          : errorMessage(cause, status === 422 ? '分块设置不符合要求，请调整后重试。' : '分块预览提交失败，请稍后重试。')
      } finally {
        this.previewSubmitting = false
      }
    },
    async submitVectorization(contextPolicy) {
      if (this.isProcessing || this.processingLoading) return
      this.confirmSubmitting = true
      this.submissionError = ''
      try {
        await confirmVectorization(this.knowledgeId, this.fileId, {
          ...contextPolicy,
          lockVersion: this.processing.lockVersion,
        })
        this.confirmDialogVisible = false
        await this.refreshProcessing()
      } catch (cause) {
        const status = cause?.response?.status
        this.submissionError = status === 409
          ? errorMessage(cause, '文件状态已变化，请重新加载后再确认。')
          : errorMessage(cause, status === 422 ? '上下文设置不符合要求，请调整后重试。' : '建立索引提交失败，请稍后重试。')
      } finally {
        this.confirmSubmitting = false
      }
    },
    retryVectorization() {
      const policy = this.processing.contextPolicy || {}
      return this.submitVectorization({
        overlapEnabled: Boolean(policy.overlapEnabled),
        overlapTokens: Number.isInteger(policy.overlapTokens) ? policy.overlapTokens : 40,
      })
    },
    async handleChunkUpdated(updated) {
      const index = this.chunks.findIndex(chunk => chunk.publicId === updated.publicId)
      if (index >= 0) this.chunks.splice(index, 1, updated)
      await this.refreshProcessing()
    },
    handleChunkDeleted(publicId) {
      this.chunks = this.chunks.filter(chunk => chunk.publicId !== publicId)
      this.chunksLoadedKey = ''
      return this.refreshProcessing()
    },
    async handleReindex(chunk) {
      this.submissionError = ''
      try {
        await reindexChunk(this.knowledgeId, this.fileId, chunk.publicId)
        this.chunksLoadedKey = ''
        await this.refreshProcessing()
      } catch (cause) {
        const status = cause?.response?.status
        this.submissionError = status === 409
          ? errorMessage(cause, '文件状态已变化，请重新加载后再建立索引。')
          : errorMessage(cause, status === 422 ? '该分块当前不能建立索引。' : '单块索引提交失败，请稍后重试。')
      }
    },
  },
}
</script>

<style scoped>
.chunking-workspace { width: min(100%, 1440px); margin: 0 auto; padding-bottom: 18px; }

.workspace-heading {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 20px;
  margin: 4px 0 20px;
}

.workspace-heading__eyebrow { color: var(--sea-signal); font-size: 10px; font-weight: 600; letter-spacing: .12em; }
.workspace-heading h1 { margin: 5px 0 3px; color: var(--sea-deep); font-family: 'Noto Serif SC', serif; font-size: 27px; }
.workspace-heading p { margin: 0; color: var(--sea-muted); font-size: 13px; }

.chunking-layout {
  display: grid;
  grid-template-columns: minmax(320px, 2fr) minmax(0, 3fr);
  min-height: 560px;
}

.preview-column { min-width: 0; background: var(--sea-paper); }

.failure-banner {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 18px;
  margin: 18px 28px 0;
  padding: 13px 14px;
  border: 1px solid color-mix(in srgb, var(--sea-danger) 32%, var(--sea-paper));
  border-radius: 8px;
  background: color-mix(in srgb, var(--sea-danger) 7%, var(--sea-paper));
  color: var(--sea-danger);
}

.failure-banner div { display: grid; gap: 3px; }
.failure-banner strong { color: var(--sea-deep); font-size: 13px; }
.failure-banner span { font-size: 12px; line-height: 1.5; }

.processing-error {
  margin: 18px 28px 0;
  color: var(--sea-danger);
  font-size: 12px;
}

@media (max-width: 850px) {
  .chunking-layout { grid-template-columns: 1fr; }
}

@media (max-width: 520px) {
  .workspace-heading { align-items: flex-start; flex-direction: column; }
  .workspace-heading h1 { font-size: 23px; }
  .failure-banner { align-items: stretch; flex-direction: column; margin: 14px 16px 0; }
  .processing-error { margin: 14px 16px 0; }
}
</style>
