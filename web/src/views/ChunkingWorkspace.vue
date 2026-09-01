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
        :strategy-config="strategyConfig"
        :config-disabled="!strategyConfigHydrated"
        :config-valid="configValid"
        :loading="capabilityLoading"
        :submitting="previewSubmitting"
        :processing="isProcessing || processingLoading"
        :show-preview-action="canPreview"
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
            v-if="showRetryPreview"
            data-testid="retry-chunking"
            :disabled="!canRetryPreview || !configValid || previewSubmitting"
            @click="submitPreview(true)"
          >重试分块</el-button>
          <el-button
            v-if="canRetryVector"
            data-testid="retry-vectorizing"
            :loading="confirmSubmitting"
            @click="retryVectorization"
          >重试建立索引</el-button>
        </div>

        <p v-if="processingError" class="processing-error" role="alert">
          {{ processingError }}
          <el-button link data-testid="retry-processing-load" @click="retryProcessingLoad">重新加载文件状态</el-button>
        </p>

        <ChunkPreviewPanel
          :knowledge-id="knowledgeId"
          :file-id="fileId"
          :chunks="chunks"
          :loading="chunksLoading || (processingLoading && !processingLoaded)"
          :loading-label="processingLoading && !processingLoaded ? '正在读取文件处理状态…' : '正在读取分块…'"
          :file-state="processing.state"
          :progress="processing.progress"
          :processing="isProcessing"
          :processing-label="processingLabel"
          :actions-disabled="chunkActionsDisabled"
          :show-confirm="canConfirm"
          :reindexing-ids="reindexingChunkIds"
          :reload-epochs="chunkReloadEpochs"
          @updated="handleChunkUpdated"
          @deleted="handleChunkDeleted"
          @reload="reloadChunks"
          @reindex="handleReindex"
          @confirm="openConfirmDialog"
        />
      </div>
    </section>

    <ContextConfirmDialog
      v-model="confirmDialogVisible"
      :submitting="confirmSubmitting"
      :server-error="confirmError"
      :server-conflict="confirmConflict"
      :reloading="confirmReloading"
      :blocked="!canConfirm || confirmConflict"
      @confirm="submitVectorization"
      @reload="reloadConfirmState"
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
const previewStates = new Set([0, 2, 3])
const confirmStates = new Set([2, 3])
const mutableChunkStates = new Set([2, 3, 6])
const defaultStrategyConfig = Object.freeze({ minTokens: 100, targetTokens: 400, maxTokens: 512 })

function initialProcessing() {
  return {
    state: 0,
    failedFromState: null,
    progress: 0,
    lastError: null,
    lockVersion: 0,
    strategyCode: '',
    policySnapshot: {},
    contextPolicy: {},
  }
}

function errorMessage(cause, fallback) {
  if (cause?.response?.data?.data?.errorCode === 'SOURCE_CHANGED') {
    return '源文件已发生变化，请重新生成分块预览'
  }
  return cause?.response?.data?.msg || fallback
}

function validPolicySnapshot(snapshot) {
  const minTokens = snapshot?.minTokens
  const targetTokens = snapshot?.targetTokens
  const maxTokens = snapshot?.maxTokens
  if (![minTokens, targetTokens, maxTokens].every(value => Number.isInteger(value) && value > 0)) return null
  if (minTokens > targetTokens || targetTokens > maxTokens || maxTokens > 512) return null
  return { minTokens, targetTokens, maxTokens }
}

function shouldLoadChunks(processing) {
  return terminalChunkStates.has(Number(processing.state))
    || isRetainedChunksFailure(processing)
    || isMutableDraftFailure(processing)
}

function isRetainedChunksFailure(processing) {
  return Number(processing.state) === 7 && Number(processing.failedFromState) === 1
}

function isMutableDraftFailure(processing) {
  return Number(processing.state) === 7 && [3, 5].includes(Number(processing.failedFromState))
}

export default {
  name: 'ChunkingWorkspace',
  components: { ChunkPreviewPanel, ChunkStrategyPanel, ContextConfirmDialog },
  data() {
    return {
      strategies: [],
      selectedStrategy: '',
      strategyConfig: { ...defaultStrategyConfig },
      strategyConfigHydrated: false,
      configValid: true,
      chunks: [],
      processing: initialProcessing(),
      processingLoaded: false,
      capabilityLoading: true,
      processingLoading: true,
      chunksLoading: false,
      previewSubmitting: false,
      confirmSubmitting: false,
      confirmDialogVisible: false,
      capabilityError: '',
      processingError: '',
      submissionError: '',
      confirmError: '',
      confirmConflict: false,
      confirmReloading: false,
      pollTimer: null,
      processingRequest: null,
      chunksRequest: null,
      chunksLoadedKey: '',
      retainedChunksLoaded: false,
      chunkReloadEpochs: {},
      reindexingChunkIds: new Set(),
      requestGeneration: 0,
      activeRouteKey: '',
      destroyed: false,
    }
  },
  computed: {
    knowledgeId() { return this.$route.params.knowledgeId },
    fileId() { return this.$route.params.fileId },
    routeKey() { return `${String(this.knowledgeId)}:${String(this.fileId)}` },
    isProcessing() { return processingStates.has(Number(this.processing.state)) },
    canPreview() {
      return this.processingLoaded && !this.processingLoading && previewStates.has(Number(this.processing.state))
    },
    canConfirm() {
      return this.processingLoaded && !this.processingLoading && confirmStates.has(Number(this.processing.state)) && this.chunks.length > 0
    },
    canRetryPreview() {
      return this.processingLoaded && !this.processingLoading && this.showRetryPreview && this.retainedChunksLoaded
    },
    showRetryPreview() {
      return isRetainedChunksFailure(this.processing)
    },
    canRetryVector() {
      return this.processingLoaded && !this.processingLoading && Number(this.processing.state) === 7 && Number(this.processing.failedFromState) === 5
    },
    chunkActionsDisabled() {
      return !this.processingLoaded || this.processingLoading
        || !(mutableChunkStates.has(Number(this.processing.state)) || isMutableDraftFailure(this.processing))
    },
    processingLabel() {
      return Number(this.processing.state) === 1 ? '正在生成分块' : '正在建立索引'
    },
  },
  watch: {
    routeKey: {
      immediate: true,
      handler(key) { this.startRoute(key) },
    },
  },
  beforeUnmount() {
    this.destroyed = true
    this.requestGeneration += 1
    this.stopPolling()
  },
  methods: {
    currentContext() {
      return {
        generation: this.requestGeneration,
        routeKey: this.activeRouteKey,
        knowledgeId: String(this.knowledgeId),
        fileId: String(this.fileId),
      }
    },
    isCurrent(context) {
      return !this.destroyed && context.generation === this.requestGeneration && context.routeKey === this.activeRouteKey
    },
    startRoute(key) {
      this.requestGeneration += 1
      this.activeRouteKey = key
      this.stopPolling()
      this.strategies = []
      this.selectedStrategy = ''
      this.strategyConfig = { ...defaultStrategyConfig }
      this.strategyConfigHydrated = false
      this.configValid = true
      this.chunks = []
      this.processing = initialProcessing()
      this.processingLoaded = false
      this.capabilityLoading = true
      this.processingLoading = true
      this.chunksLoading = false
      this.previewSubmitting = false
      this.confirmSubmitting = false
      this.confirmDialogVisible = false
      this.confirmReloading = false
      this.capabilityError = ''
      this.processingError = ''
      this.submissionError = ''
      this.confirmError = ''
      this.confirmConflict = false
      this.processingRequest = null
      this.chunksRequest = null
      this.chunksLoadedKey = ''
      this.retainedChunksLoaded = false
      this.chunkReloadEpochs = {}
      this.reindexingChunkIds = new Set()
      const context = this.currentContext()
      this.initialize(context)
    },
    async initialize(context) {
      await Promise.all([this.loadCapabilities(context), this.refreshProcessing(false, context)])
    },
    syncSelectedStrategy() {
      const backendSelected = this.strategies.find(strategy => (
        !strategy.disabled && strategy.code === this.processing.strategyCode
      ))
      const markdownDefault = this.strategies.find(strategy => (
        !strategy.disabled && strategy.code === 'MARKDOWN_OPTIMIZED'
      ))
      this.selectedStrategy = backendSelected?.code || markdownDefault?.code || ''
    },
    async loadCapabilities(context = this.currentContext()) {
      this.capabilityLoading = true
      this.capabilityError = ''
      try {
        const response = await getStrategies(context.knowledgeId, context.fileId)
        if (!this.isCurrent(context)) return
        const catalog = mergeStrategies(response?.data?.fileType, response?.data?.strategies)
        this.strategies = catalog
        this.syncSelectedStrategy()
      } catch (cause) {
        if (this.isCurrent(context)) this.capabilityError = errorMessage(cause, '可用分块策略暂时无法加载。')
      } finally {
        if (this.isCurrent(context)) this.capabilityLoading = false
      }
    },
    selectStrategy(code) {
      const strategy = this.strategies.find(item => item.code === code)
      if (!strategy || strategy.disabled) return
      this.selectedStrategy = code
      this.submissionError = ''
    },
    async refreshProcessing(forceChunkLoad = false, context = this.currentContext()) {
      if (!this.isCurrent(context)) return false
      if (this.processingRequest) return this.processingRequest
      this.processingLoading = true
      this.processingLoaded = false
      this.processingError = ''
      const request = (async () => {
        try {
          const response = await getProcessing(context.knowledgeId, context.fileId)
          if (!this.isCurrent(context)) return false
          this.processing = { ...this.processing, ...(response?.data || {}) }
          this.processingLoaded = true
          const retainedChunksFailure = isRetainedChunksFailure(this.processing)
          this.retainedChunksLoaded = false
          this.syncSelectedStrategy()
          if (!this.strategyConfigHydrated) {
            const restoredConfig = validPolicySnapshot(this.processing.policySnapshot)
            if (restoredConfig) this.strategyConfig = restoredConfig
            this.strategyConfigHydrated = true
          }
          if (forceChunkLoad) this.chunksLoadedKey = ''
          if (shouldLoadChunks(this.processing)) {
            this.stopPolling()
            const chunksLoaded = await this.loadChunks({ force: forceChunkLoad }, context)
            if (this.isCurrent(context) && retainedChunksFailure) {
              this.retainedChunksLoaded = Boolean(chunksLoaded)
            }
            return Boolean(chunksLoaded && this.isCurrent(context))
          } else if (this.isProcessing) {
            this.schedulePoll(context)
          } else {
            this.stopPolling()
          }
          return true
        } catch (cause) {
          if (this.isCurrent(context)) {
            this.processingLoaded = false
            this.processingError = errorMessage(cause, '文件处理状态暂时无法加载，请重新加载。')
            this.stopPolling()
          }
          return false
        } finally {
          if (this.isCurrent(context) && this.processingRequest === request) {
            this.processingLoading = false
            this.processingRequest = null
          }
        }
      })()
      this.processingRequest = request
      return request
    },
    schedulePoll(context = this.currentContext()) {
      if (!this.isCurrent(context) || this.pollTimer || !this.isProcessing) return
      this.pollTimer = setTimeout(async () => {
        this.pollTimer = null
        if (!this.isCurrent(context)) return
        await this.refreshProcessing(false, context)
      }, 1000)
    },
    stopPolling() {
      if (!this.pollTimer) return
      clearTimeout(this.pollTimer)
      this.pollTimer = null
    },
    async loadChunks({ force = false } = {}, context = this.currentContext()) {
      if (!this.isCurrent(context)) return false
      const key = `${context.routeKey}:${this.processing.state}:${this.processing.lockVersion}`
      if (!force && this.chunksLoadedKey === key) return true
      if (this.chunksRequest) return this.chunksRequest
      this.chunksLoading = this.chunks.length === 0
      const request = (async () => {
        try {
          const response = await getChunks(context.knowledgeId, context.fileId)
          if (!this.isCurrent(context)) return false
          this.chunks = Array.isArray(response?.data) ? response.data : []
          this.chunksLoadedKey = key
          return true
        } catch (cause) {
          if (this.isCurrent(context)) this.processingError = errorMessage(cause, '分块预览暂时无法加载，请重新加载。')
          return false
        } finally {
          if (this.isCurrent(context) && this.chunksRequest === request) {
            this.chunksLoading = false
            this.chunksRequest = null
          }
        }
      })()
      this.chunksRequest = request
      return request
    },
    async reloadChunks(chunkPublicId) {
      const context = this.currentContext()
      const loaded = await this.loadChunks({ force: true }, context)
      if (!loaded || !this.isCurrent(context) || !chunkPublicId || !this.chunks.some(chunk => chunk.publicId === chunkPublicId)) return
      this.chunkReloadEpochs = {
        ...this.chunkReloadEpochs,
        [chunkPublicId]: (this.chunkReloadEpochs[chunkPublicId] || 0) + 1,
      }
    },
    reloadWorkspace() {
      this.startRoute(this.routeKey)
    },
    retryProcessingLoad() {
      return this.refreshProcessing(true, this.currentContext())
    },
    async submitPreview(isRetry = false) {
      if (!this.configValid || (isRetry ? !this.canRetryPreview : !this.canPreview)) return
      const selected = this.strategies.find(strategy => strategy.code === this.selectedStrategy)
      if (!selected || selected.disabled) return
      const context = this.currentContext()

      let replaceEditedDrafts = false
      if (this.chunks.some(chunk => Number(chunk.status) === 0 && chunk.isModified)) {
        try {
          await ElMessageBox.confirm(
            '当前预览包含人工修改的草稿。重新生成会替换这些人工修改，是否继续？',
            '确认重新生成分块',
            { confirmButtonText: '替换并重新生成', cancelButtonText: '保留当前分块', type: 'warning' },
          )
          if (!this.isCurrent(context)) return
          replaceEditedDrafts = true
        } catch {
          return
        }
      }

      this.previewSubmitting = true
      this.submissionError = ''
      try {
        await createPreview(context.knowledgeId, context.fileId, {
          strategyCode: this.selectedStrategy,
          strategyConfig: { ...this.strategyConfig },
          replaceEditedDrafts,
          lockVersion: this.processing.lockVersion,
        })
        if (!this.isCurrent(context)) return
        this.chunksLoadedKey = ''
        await this.refreshProcessing(false, context)
      } catch (cause) {
        if (!this.isCurrent(context)) return
        const status = cause?.response?.status
        this.submissionError = status === 409
          ? errorMessage(cause, '文件状态已变化，请重新加载后再生成分块。')
          : errorMessage(cause, status === 422 ? '分块设置不符合要求，请调整后重试。' : '分块预览提交失败，请稍后重试。')
      } finally {
        if (this.isCurrent(context)) this.previewSubmitting = false
      }
    },
    openConfirmDialog() {
      if (!this.canConfirm) return
      this.confirmError = ''
      this.confirmConflict = false
      this.confirmDialogVisible = true
    },
    async submitVectorization(contextPolicy, isRetry = false) {
      if (isRetry ? !this.canRetryVector : (!this.canConfirm || this.confirmConflict || this.confirmReloading)) return
      const context = this.currentContext()
      this.confirmSubmitting = true
      this.submissionError = ''
      this.confirmError = ''
      this.confirmConflict = false
      try {
        await confirmVectorization(context.knowledgeId, context.fileId, {
          ...contextPolicy,
          lockVersion: this.processing.lockVersion,
        })
        if (!this.isCurrent(context)) return
        this.confirmDialogVisible = false
        await this.refreshProcessing(false, context)
      } catch (cause) {
        if (!this.isCurrent(context)) return
        const status = cause?.response?.status
        const message = status === 409
          ? errorMessage(cause, '文件状态已变化，请重新加载后再确认。')
          : errorMessage(cause, status === 422 ? '上下文设置不符合要求，请调整后重试。' : '建立索引提交失败，请稍后重试。')
        if (this.confirmDialogVisible) {
          this.confirmError = message
          this.confirmConflict = status === 409
        } else {
          this.submissionError = message
        }
      } finally {
        if (this.isCurrent(context)) this.confirmSubmitting = false
      }
    },
    retryVectorization() {
      if (!this.canRetryVector) return
      const policy = this.processing.contextPolicy || {}
      return this.submitVectorization({
        overlapEnabled: Boolean(policy.overlapEnabled),
        overlapTokens: Number.isInteger(policy.overlapTokens) ? policy.overlapTokens : 40,
      }, true)
    },
    async reloadConfirmState() {
      if (this.confirmReloading) return
      const context = this.currentContext()
      this.confirmReloading = true
      const reloaded = await this.refreshProcessing(true, context)
      if (this.isCurrent(context)) {
        this.confirmReloading = false
        if (reloaded) {
          this.confirmError = ''
          this.confirmConflict = false
        } else {
          this.confirmError = this.processingError || '文件状态或分块仍无法加载，请重试。'
          this.confirmConflict = true
        }
      }
    },
    async handleChunkUpdated(updated) {
      const context = this.currentContext()
      const index = this.chunks.findIndex(chunk => chunk.publicId === updated.publicId)
      if (index >= 0) this.chunks.splice(index, 1, updated)
      await this.refreshProcessing(false, context)
      await this.loadChunks({ force: true }, context)
    },
    async handleChunkDeleted(publicId) {
      const context = this.currentContext()
      this.chunks = this.chunks.filter(chunk => chunk.publicId !== publicId)
      await this.refreshProcessing(false, context)
      await this.loadChunks({ force: true }, context)
    },
    async handleReindex(chunk) {
      if (
        this.chunkActionsDisabled
        || ![3, 6].includes(Number(this.processing.state))
        || Number(chunk.status) !== 0
        || !chunk.isModified
        || this.reindexingChunkIds.has(chunk.publicId)
      ) return
      const context = this.currentContext()
      const pendingIds = this.reindexingChunkIds
      pendingIds.add(chunk.publicId)
      this.submissionError = ''
      try {
        await reindexChunk(context.knowledgeId, context.fileId, chunk.publicId)
        if (!this.isCurrent(context)) return
        await this.refreshProcessing(false, context)
        await this.loadChunks({ force: true }, context)
      } catch (cause) {
        if (!this.isCurrent(context)) return
        const status = cause?.response?.status
        this.submissionError = status === 409
          ? errorMessage(cause, '文件状态已变化，请重新加载后再建立索引。')
          : errorMessage(cause, status === 422 ? '该分块当前不能建立索引。' : '单块索引提交失败，请稍后重试。')
      } finally {
        pendingIds.delete(chunk.publicId)
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
