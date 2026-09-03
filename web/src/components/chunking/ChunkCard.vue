<template>
  <article class="chunk-card" :aria-disabled="actionsDisabled ? 'true' : 'false'">
    <header class="chunk-ribbon">
      <span class="chunk-code mono">CHUNK {{ chunkNumber }}</span>
      <nav
        data-testid="section-path"
        :title="sectionPathText"
        :aria-label="`语义标题路径：${sectionPathText}`"
      >
        <template v-if="localChunk.sectionPath?.length">
          <template v-for="(section, index) in localChunk.sectionPath" :key="`${section}-${index}`">
            <span v-if="index" aria-hidden="true">/</span>
            <span
              class="path-segment"
              :class="{ 'path-segment--last': index === localChunk.sectionPath.length - 1 }"
            >{{ section }}</span>
          </template>
        </template>
        <span v-else class="path-segment path-segment--last">文档正文</span>
      </nav>
    </header>

    <div class="chunk-card__body">
      <el-input
        v-if="editing"
        v-model="editorValue"
        type="textarea"
        :rows="6"
        aria-label="分块正文"
        :disabled="actionsDisabled"
        @input="queueSave"
      />
      <p v-else data-testid="chunk-body" class="chunk-content">{{ localChunk.content }}</p>

      <div v-if="errorMessage" class="chunk-error" role="alert">
        <span>{{ errorMessage }}</span>
        <el-button v-if="conflict" link data-testid="reload-chunk" @click="$emit('reload', localChunk.publicId)">重新加载</el-button>
      </div>

      <section class="overlap-setting" data-testid="overlap-switch">
        <div class="overlap-setting__heading">
          <strong>补充上文</strong>
          <span>使用服务端生成的相邻正文补充当前块语境</span>
        </div>
        <el-switch
          v-model="overlapEnabled"
          aria-label="补充上文"
          :disabled="actionsDisabled"
          @change="queueSave"
        />
      </section>

      <div v-if="overlapEnabled" class="overlap-details">
        <label class="overlap-limit" data-testid="overlap-token-limit">
          <span>补充上限</span>
          <el-input-number
            v-model="overlapTokenLimit"
            :min="1"
            :max="512"
            controls-position="right"
            :disabled="actionsDisabled"
            @change="changeOverlapTokenLimit"
          />
          <small>1–512 Token</small>
        </label>
        <div class="overlap-preview">
          <div class="overlap-preview__heading">
            <strong>补充内容</strong>
            <span data-testid="overlap-token-count">{{ localChunk.overlapTokenCount }} Token</span>
          </div>
          <p v-if="localChunk.overlapContent" class="overlap-content" data-testid="overlap-content">
            {{ localChunk.overlapContent }}
          </p>
          <p v-else class="overlap-unavailable" data-testid="overlap-unavailable">
            {{ overlapUnavailableText }}
          </p>
        </div>
      </div>
    </div>

    <footer class="chunk-card__footer">
      <div class="chunk-metadata">
        <span>正文 {{ localChunk.tokenCount }} Token</span>
        <span v-if="saveStatus" data-testid="save-status" aria-live="polite">{{ saveStatus }}</span>
      </div>
      <div class="chunk-actions">
        <el-button
          v-if="evaluationAllowed"
          link
          type="primary"
          data-testid="evaluate-chunk"
          :disabled="actionsDisabled || evaluationBlocked"
          :title="evaluationBlocked ? '请先完成保存，再冻结实际文本' : '相似度测试 / 加入评测'"
          @click="openEvaluation"
        >相似度测试 / 加入评测</el-button>
        <el-button
          v-if="showReindex"
          link
          type="primary"
          data-testid="reindex-chunk"
          :disabled="actionsDisabled || reindexDisabled"
          @click="requestReindex"
        >重新建立索引</el-button>
        <el-button
          link
          data-testid="edit-chunk"
          :disabled="actionsDisabled"
          @click="editing = !editing"
        >{{ editing ? '收起编辑' : '编辑' }}</el-button>
        <el-button
          link
          type="danger"
          data-testid="delete-chunk"
          :disabled="actionsDisabled"
          @click="requestDelete"
        >删除</el-button>
      </div>
    </footer>
  </article>
  <el-drawer v-if="evaluationOpen && evaluationAllowed" v-model="evaluationOpen" title="相似度测试 / 加入评测" size="min(1120px, 96vw)" append-to-body destroy-on-close :close-on-click-modal="false">
    <EvaluationWorkbench :knowledge-id="knowledgeId" :initial-chunk-id="localChunk.publicId" />
  </el-drawer>
</template>

<script setup>
import { computed, defineAsyncComponent, onBeforeUnmount, reactive, ref, watch } from 'vue'
import { ElMessageBox } from 'element-plus'
import { deleteChunk, updateChunk } from '../../api/chunking'
import { useEvaluationAccess } from '../evaluation/useEvaluationAccess'

const EvaluationWorkbench = defineAsyncComponent(() => import('../evaluation/EvaluationWorkbench.vue'))
const evaluationAllowed = useEvaluationAccess()
const evaluationOpen = ref(false)
const evaluationBlocked = ref(false)

const props = defineProps({
  knowledgeId: { type: [String, Number], required: true },
  fileId: { type: [String, Number], required: true },
  chunk: { type: Object, required: true },
  disabled: { type: Boolean, default: false },
  showReindex: { type: Boolean, default: false },
  reindexDisabled: { type: Boolean, default: false },
  reloadEpoch: { type: Number, default: 0 },
})

const emit = defineEmits(['updated', 'deleted', 'reload', 'reindex', 'save-state'])
const localChunk = reactive({ ...props.chunk })
const editing = ref(false)
const editorValue = ref(props.chunk.content || '')
const overlapEnabled = ref(Boolean(props.chunk.overlapEnabled))
const overlapTokenLimit = ref(normalizeOverlapTokenLimit(props.chunk.overlapTokenLimit))
const saveStatus = ref('')
const errorMessage = ref('')
const conflict = ref(false)
let saveTimer = null
let requestGeneration = 0
let saveInFlight = false
let queuedSave = false
let saveError = false
let destroyed = false

const overlapUnavailableMessages = Object.freeze({
  NO_AVAILABLE_OVERLAP: '当前分块没有可补充的完整上文。',
})

const chunkNumber = computed(() => String((Number(localChunk.position) || 0) + 1).padStart(2, '0'))
const actionsDisabled = computed(() => props.disabled || Number(localChunk.status) === 1)
const sectionPathText = computed(() => localChunk.sectionPath?.length ? localChunk.sectionPath.join(' / ') : '文档正文')
const overlapUnavailableText = computed(() => {
  const code = String(localChunk.overlapUnavailableReason || '').trim()
  if (!code) return '暂无可补充的上文。'
  return overlapUnavailableMessages[code] || '暂时无法生成补充上文，请稍后重试。'
})

function normalizeOverlapTokenLimit(value) {
  return Number.isInteger(value) && value >= 1 && value <= 512 ? value : 40
}

function currentSnapshot() {
  return {
    content: editorValue.value || '',
    overlapEnabled: overlapEnabled.value,
    overlapTokenLimit: normalizeOverlapTokenLimit(overlapTokenLimit.value),
  }
}

function matchesServer(snapshot = currentSnapshot()) {
  return snapshot.content === (localChunk.content || '')
    && snapshot.overlapEnabled === Boolean(localChunk.overlapEnabled)
    && snapshot.overlapTokenLimit === normalizeOverlapTokenLimit(localChunk.overlapTokenLimit)
}

function reportSaveState() {
  if (destroyed) return
  const dirty = !matchesServer()
  const pending = Boolean(saveTimer || saveInFlight || queuedSave)
  evaluationBlocked.value = dirty || pending || saveError
  emit('save-state', {
    publicId: localChunk.publicId,
    dirty,
    pending,
    error: saveError,
    blocking: dirty || pending || saveError,
  })
}

watch(
  () => props.chunk,
  value => {
    Object.assign(localChunk, value)
    if (!editing.value) editorValue.value = value.content || ''
    if (!saveInFlight && !queuedSave && !saveTimer) {
      overlapEnabled.value = Boolean(value.overlapEnabled)
      overlapTokenLimit.value = normalizeOverlapTokenLimit(value.overlapTokenLimit)
    }
  },
  { deep: true },
)

watch(
  () => props.reloadEpoch,
  (value, previous) => {
    if (value === previous) return
    resetFromServer()
  },
)

watch(
  () => props.disabled,
  disabled => {
    if (!disabled) return
    requestGeneration += 1
    if (saveTimer) clearTimeout(saveTimer)
    saveTimer = null
    queuedSave = false
    saveStatus.value = ''
    reportSaveState()
  },
)

function resetFromServer() {
  requestGeneration += 1
  if (saveTimer) clearTimeout(saveTimer)
  saveTimer = null
  saveInFlight = false
  queuedSave = false
  saveError = false
  Object.assign(localChunk, props.chunk)
  editorValue.value = props.chunk.content || ''
  overlapEnabled.value = Boolean(props.chunk.overlapEnabled)
  overlapTokenLimit.value = normalizeOverlapTokenLimit(props.chunk.overlapTokenLimit)
  saveStatus.value = ''
  errorMessage.value = ''
  conflict.value = false
  reportSaveState()
}

function queueSave() {
  if (actionsDisabled.value) return
  errorMessage.value = ''
  conflict.value = false
  saveError = false
  saveStatus.value = ''
  if (saveTimer) clearTimeout(saveTimer)
  saveTimer = setTimeout(saveBody, 650)
  reportSaveState()
}

function changeOverlapTokenLimit(value) {
  overlapTokenLimit.value = normalizeOverlapTokenLimit(value)
  queueSave()
}

async function saveBody() {
  saveTimer = null
  if (actionsDisabled.value) {
    reportSaveState()
    return
  }
  if (saveInFlight) {
    queuedSave = true
    reportSaveState()
    return
  }
  const snapshot = {
    content: editorValue.value,
    overlapEnabled: overlapEnabled.value,
    overlapTokenLimit: normalizeOverlapTokenLimit(overlapTokenLimit.value),
  }
  if (!snapshot.content.trim()) {
    errorMessage.value = '正文不能为空，请输入内容后再保存。'
    saveError = true
    reportSaveState()
    return
  }

  const generation = ++requestGeneration
  let saveSucceeded = false
  let retryQueuedSave = false
  saveInFlight = true
  saveError = false
  errorMessage.value = ''
  conflict.value = false
  saveStatus.value = '保存中'
  try {
    const response = await updateChunk(props.knowledgeId, props.fileId, localChunk.publicId, {
      ...snapshot,
      lockVersion: localChunk.lockVersion,
    })
    if (generation !== requestGeneration) return
    const updated = response?.data || {
      ...localChunk,
      ...snapshot,
      lockVersion: Number(localChunk.lockVersion) + 1,
      status: 0,
      isModified: true,
    }
    Object.assign(localChunk, updated)
    if (editorValue.value === snapshot.content) editorValue.value = updated.content
    if (overlapEnabled.value === snapshot.overlapEnabled) overlapEnabled.value = Boolean(updated.overlapEnabled)
    if (overlapTokenLimit.value === snapshot.overlapTokenLimit) {
      overlapTokenLimit.value = normalizeOverlapTokenLimit(updated.overlapTokenLimit)
    }
    saveSucceeded = true
    emit('updated', { ...localChunk })
  } catch (cause) {
    if (generation !== requestGeneration) return
    saveStatus.value = ''
    saveError = true
    const status = cause?.response?.status
    retryQueuedSave = status === 422
    conflict.value = status === 409
    if (status === 409) {
      errorMessage.value = cause?.response?.data?.msg || '分块版本已变化，请重新加载后继续编辑。'
    } else if (status === 422) {
      errorMessage.value = cause?.response?.data?.msg || '正文不符合分块要求，请调整后重试。'
    } else {
      errorMessage.value = cause?.response?.data?.msg || '正文保存失败，请稍后重试。'
    }
  } finally {
    saveInFlight = false
    if (generation !== requestGeneration) {
      reportSaveState()
      return
    }
    if (queuedSave && (saveSucceeded || retryQueuedSave)) {
      queuedSave = false
      reportSaveState()
      await saveBody()
    } else {
      queuedSave = false
      if (saveSucceeded) {
        saveError = false
        saveStatus.value = saveTimer || !matchesServer() ? '' : '已保存'
      }
      reportSaveState()
    }
  }
}

function requestReindex() {
  if (actionsDisabled.value || props.reindexDisabled) return
  emit('reindex', localChunk)
}

function openEvaluation() {
  if (actionsDisabled.value || evaluationBlocked.value || !evaluationAllowed.value) return
  evaluationOpen.value = true
}

async function requestDelete() {
  if (actionsDisabled.value) return
  try {
    await ElMessageBox.confirm(
      '删除后该原始分块将不再参与索引，此操作不能撤销。',
      '确认删除分块',
      { confirmButtonText: '删除分块', cancelButtonText: '取消', type: 'warning' },
    )
  } catch {
    return
  }

  if (actionsDisabled.value) return

  errorMessage.value = ''
  conflict.value = false
  const generation = requestGeneration
  try {
    await deleteChunk(props.knowledgeId, props.fileId, localChunk.publicId, localChunk.lockVersion)
    if (generation !== requestGeneration) return
    emit('deleted', localChunk.publicId)
  } catch (cause) {
    if (generation !== requestGeneration) return
    const status = cause?.response?.status
    conflict.value = status === 409
    if (status === 409) {
      errorMessage.value = cause?.response?.data?.msg || '分块版本已变化，请重新加载后再删除。'
    } else if (status === 422) {
      errorMessage.value = cause?.response?.data?.msg || '当前分块不能删除。'
    } else {
      errorMessage.value = cause?.response?.data?.msg || '删除失败，请稍后重试。'
    }
  }
}

onBeforeUnmount(() => {
  destroyed = true
  requestGeneration += 1
  if (saveTimer) clearTimeout(saveTimer)
})
</script>

<style scoped>
.chunk-card {
  overflow: hidden;
  border: 1px solid color-mix(in srgb, var(--sea-muted) 22%, var(--sea-paper));
  border-radius: 10px;
  background: var(--sea-paper);
  box-shadow: 0 2px 8px color-mix(in srgb, var(--sea-deep) 5%, transparent);
}

.chunk-card[aria-disabled='true'] { opacity: .68; }

.chunk-ribbon {
  display: grid;
  grid-template-columns: auto minmax(0, 1fr);
  align-items: center;
  gap: 14px;
  min-height: 38px;
  padding: 7px 14px;
  border-bottom: 1px solid color-mix(in srgb, var(--sea-signal) 24%, var(--sea-paper));
  background: color-mix(in srgb, var(--sea-signal) 8%, var(--sea-paper));
}

.chunk-code {
  color: var(--sea-signal);
  font-size: 11px;
  font-weight: 600;
  letter-spacing: .08em;
  white-space: nowrap;
}

.chunk-ribbon nav {
  display: flex;
  min-width: 0;
  gap: 7px;
  overflow: hidden;
  color: var(--sea-muted);
  font-size: 12px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.path-segment { flex: 0 0 auto; }
.path-segment--last { flex: 1 1 auto; min-width: 0; overflow: hidden; text-overflow: ellipsis; }

.chunk-card__body { padding: 17px 18px 13px; }
.chunk-content { margin: 0; color: var(--sea-ink); font-size: 14px; line-height: 1.78; white-space: pre-wrap; }

.overlap-setting {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 18px;
  margin-top: 16px;
  padding-top: 14px;
  border-top: 1px solid color-mix(in srgb, var(--sea-muted) 14%, var(--sea-paper));
}

.overlap-setting__heading { display: grid; gap: 3px; }
.overlap-setting__heading strong { color: var(--sea-deep); font-size: 13px; }
.overlap-setting__heading span { color: var(--sea-muted); font-size: 11px; line-height: 1.45; }

.overlap-details {
  display: grid;
  gap: 12px;
  margin-top: 12px;
  padding: 13px;
  border: 1px solid color-mix(in srgb, var(--sea-signal) 18%, var(--sea-paper));
  border-radius: 8px;
  background: color-mix(in srgb, var(--sea-signal) 4%, var(--sea-paper));
}

.overlap-limit { display: flex; align-items: center; gap: 10px; color: var(--sea-deep); font-size: 12px; font-weight: 600; }
.overlap-limit small { color: var(--sea-muted); font-size: 11px; font-weight: 400; }
.overlap-preview { display: grid; gap: 7px; }
.overlap-preview__heading { display: flex; align-items: center; justify-content: space-between; color: var(--sea-deep); font-size: 12px; }
.overlap-preview__heading span { color: var(--sea-muted); font-family: 'JetBrains Mono', monospace; font-size: 10px; }
.overlap-content,
.overlap-unavailable { margin: 0; color: var(--sea-muted); font-size: 12px; line-height: 1.65; white-space: pre-wrap; }
.overlap-unavailable { font-style: italic; }

.chunk-error {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  margin-top: 10px;
  color: var(--sea-danger);
  font-size: 12px;
  line-height: 1.5;
}

.chunk-card__footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 14px;
  min-height: 48px;
  padding: 7px 14px 7px 18px;
  border-top: 1px solid color-mix(in srgb, var(--sea-muted) 14%, var(--sea-paper));
}

.chunk-metadata { display: flex; gap: 12px; color: var(--sea-muted); font-size: 11px; }
.chunk-actions { display: flex; align-items: center; flex-wrap: wrap; justify-content: flex-end; }
.chunk-actions :deep(.el-button + .el-button) { margin-left: 5px; }

@media (max-width: 520px) {
  .chunk-ribbon { grid-template-columns: 1fr; gap: 3px; }
  .chunk-card__footer { align-items: flex-start; flex-direction: column; }
  .chunk-actions { justify-content: flex-start; }
}
</style>
