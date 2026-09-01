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
    </div>

    <footer class="chunk-card__footer">
      <div class="chunk-metadata">
        <span>{{ localChunk.tokenCount }} Token</span>
        <span v-if="saveStatus" data-testid="save-status" aria-live="polite">{{ saveStatus }}</span>
      </div>
      <div class="chunk-actions">
        <el-button
          v-if="showReindex"
          link
          type="primary"
          data-testid="reindex-chunk"
          :disabled="actionsDisabled"
          @click="$emit('reindex', localChunk)"
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
</template>

<script setup>
import { computed, onBeforeUnmount, reactive, ref, watch } from 'vue'
import { ElMessageBox } from 'element-plus'
import { deleteChunk, updateChunk } from '../../api/chunking'

const props = defineProps({
  knowledgeId: { type: [String, Number], required: true },
  fileId: { type: [String, Number], required: true },
  chunk: { type: Object, required: true },
  disabled: { type: Boolean, default: false },
  showReindex: { type: Boolean, default: false },
  reloadEpoch: { type: Number, default: 0 },
})

const emit = defineEmits(['updated', 'deleted', 'reload', 'reindex'])
const localChunk = reactive({ ...props.chunk })
const editing = ref(false)
const editorValue = ref(props.chunk.content || '')
const saveStatus = ref('')
const errorMessage = ref('')
const conflict = ref(false)
let saveTimer = null
let requestGeneration = 0
let saveInFlight = false
let queuedSave = false

const chunkNumber = computed(() => String((Number(localChunk.position) || 0) + 1).padStart(2, '0'))
const actionsDisabled = computed(() => props.disabled || Number(localChunk.status) === 1)
const sectionPathText = computed(() => localChunk.sectionPath?.length ? localChunk.sectionPath.join(' / ') : '文档正文')

watch(
  () => props.chunk,
  value => {
    Object.assign(localChunk, value)
    if (!editing.value) editorValue.value = value.content || ''
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

function resetFromServer() {
  requestGeneration += 1
  if (saveTimer) clearTimeout(saveTimer)
  saveTimer = null
  saveInFlight = false
  queuedSave = false
  Object.assign(localChunk, props.chunk)
  editorValue.value = props.chunk.content || ''
  saveStatus.value = ''
  errorMessage.value = ''
  conflict.value = false
}

function queueSave() {
  errorMessage.value = ''
  conflict.value = false
  saveStatus.value = ''
  if (saveTimer) clearTimeout(saveTimer)
  saveTimer = setTimeout(saveBody, 650)
}

async function saveBody() {
  saveTimer = null
  if (saveInFlight) {
    queuedSave = true
    return
  }
  const body = editorValue.value
  if (!body.trim()) {
    errorMessage.value = '正文不能为空，请输入内容后再保存。'
    return
  }

  const generation = ++requestGeneration
  let saveSucceeded = false
  let retryQueuedSave = false
  saveInFlight = true
  errorMessage.value = ''
  conflict.value = false
  saveStatus.value = '保存中'
  try {
    const response = await updateChunk(props.knowledgeId, props.fileId, localChunk.publicId, {
      content: body,
      lockVersion: localChunk.lockVersion,
    })
    if (generation !== requestGeneration) return
    const updated = response?.data || {
      ...localChunk,
      content: body,
      lockVersion: Number(localChunk.lockVersion) + 1,
      status: 0,
      isModified: true,
    }
    Object.assign(localChunk, updated)
    if (editorValue.value === body) editorValue.value = updated.content
    saveSucceeded = true
    emit('updated', { ...localChunk })
  } catch (cause) {
    if (generation !== requestGeneration) return
    saveStatus.value = ''
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
    if (generation !== requestGeneration) return
    if (queuedSave && (saveSucceeded || retryQueuedSave)) {
      queuedSave = false
      await saveBody()
    } else {
      queuedSave = false
      if (saveSucceeded) saveStatus.value = saveTimer ? '' : '已保存'
    }
  }
}

async function requestDelete() {
  try {
    await ElMessageBox.confirm(
      '删除后该原始分块将不再参与索引，此操作不能撤销。',
      '确认删除分块',
      { confirmButtonText: '删除分块', cancelButtonText: '取消', type: 'warning' },
    )
  } catch {
    return
  }

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
