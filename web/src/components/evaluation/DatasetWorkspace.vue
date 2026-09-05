<script setup>
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import * as api from '../../api/embeddingEvaluation'
import { clone, errorText, newQuestion, snapshotDescription, validateQuestions } from './evaluationState'
import QuestionEditor from './QuestionEditor.vue'
import SnapshotPicker from './SnapshotPicker.vue'
const props = defineProps({
  knowledgeId: { type: [String, Number], required: true },
  chunks: { type: Array, default: () => [] },
  datasets: { type: Array, default: () => [] },
  selectedId: String,
})
const emit = defineEmits(['saved', 'run'])
const selected = ref(props.selectedId || ''),
  draft = ref(null),
  snapshot = ref(null),
  questionIndex = ref(0),
  busy = ref(false),
  error = ref(''),
  message = ref(''),
  revision = ref(null),
  savedFingerprint = ref(''),
  removeConfirm = ref(false)
let generation = 0
const fingerprint = computed(() => JSON.stringify(draft.value))
const dirty = computed(() => !!draft.value && savedFingerprint.value !== fingerprint.value)
const current = computed({
  get: () => draft.value?.questions?.[questionIndex.value],
  set: (value) => {
    draft.value.questions[questionIndex.value] = value
  },
})
function newDataset() {
  generation++
  selected.value = ''
  snapshot.value = null
  error.value = ''
  message.value = ''
  questionIndex.value = 0
  draft.value = { name: '', questions: [newQuestion()], frozen: false, snapshotId: null }
  savedFingerprint.value = ''
}
async function load(id = selected.value, version) {
  if (!id) {
    newDataset()
    return
  }
  const token = ++generation
  busy.value = true
  error.value = ''
  message.value = ''
  draft.value = null
  snapshot.value = null
  try {
    const value = await api.getDataset(props.knowledgeId, id, version)
    const corpus = await api.getSnapshot(props.knowledgeId, value.snapshotId)
    if (token !== generation) return
    draft.value = clone(value)
    snapshot.value = corpus
    revision.value = value.revision
    questionIndex.value = 0
    savedFingerprint.value = JSON.stringify(value)
  } catch (cause) {
    if (token === generation) error.value = errorText(cause)
  } finally {
    if (token === generation) busy.value = false
  }
}
watch(
  () => props.selectedId,
  (value) => {
    if (value) {
      selected.value = value
      load(value)
    }
  },
  { immediate: true },
)
function addQuestion(variant = false) {
  const source = current.value
  const q = newQuestion(
    variant && source
      ? {
          intentGroup: source.intentGroup,
          category: source.category,
          split: source.split,
          answerable: source.answerable,
        }
      : {},
  )
  draft.value.questions.push(q)
  questionIndex.value = draft.value.questions.length - 1
}
function removeQuestion() {
  draft.value.questions.splice(questionIndex.value, 1)
  questionIndex.value = Math.max(0, questionIndex.value - 1)
  removeConfirm.value = false
}
function discardNewDraft() {
  draft.value = null
  snapshot.value = null
}
function selectQuestion(index) {
  questionIndex.value = index
  removeConfirm.value = false
}
function copyDraft() {
  draft.value = { ...clone(draft.value), frozen: false }
  message.value = '将基于此版本保存新修订，已有冻结版本和报告保持原样。'
}
async function save(freeze = false) {
  error.value = validateQuestions(draft.value.questions)
  if (!draft.value.name.trim()) error.value = '请填写问题集名称。'
  if (!snapshot.value) error.value = '请先冻结语料快照。'
  if (error.value) return
  if (freeze && draft.value.questions.some((q) => !q.reviewed)) {
    error.value = '请先审核全部问题，再冻结验收版本。'
    return
  }
  busy.value = true
  try {
    const command = {
      name: draft.value.name.trim(),
      snapshotId: snapshot.value.id,
      questions: clone(draft.value.questions),
      frozen: freeze,
      ...(draft.value.id ? { revision: draft.value.revision } : {}),
    }
    const saved = draft.value.id
      ? await api.updateDataset(props.knowledgeId, draft.value.id, command)
      : await api.createDataset(props.knowledgeId, command)
    draft.value = clone(saved)
    selected.value = saved.id
    revision.value = saved.revision
    savedFingerprint.value = JSON.stringify(saved)
    message.value = `已保存 v${saved.revision}${saved.frozen ? ' · 冻结版本' : ''}`
    emit('saved', saved)
  } catch (cause) {
    error.value = errorText(cause)
  } finally {
    busy.value = false
  }
}
onBeforeUnmount(() => generation++)
</script>
<template>
  <div class="ev-stack">
    <div class="ev-head">
      <label
        >问题集<select v-model="selected" :disabled="busy || dirty" @change="load()">
          <option value="">选择问题集</option>
          <option v-for="item in datasets" :key="item.id" :value="item.id">
            {{ item.name }} · v{{ item.revision }}{{ item.frozen ? ' · 已冻结' : '' }}
          </option>
        </select></label
      ><button class="ev-button" :disabled="busy || dirty" @click="newDataset">新建问题集</button>
    </div>
    <p v-if="error" class="ev-notice ev-error" role="alert">
      {{ error }} <button v-if="!draft && selected" class="ev-button" @click="load()">重新加载</button>
    </p>
    <p v-if="busy" role="status">正在读取 / 保存版本…</p>
    <p v-if="!draft && !busy" class="ev-empty">选择已有问题集，或新建问题集开始积累真实样本。</p>
    <section v-if="draft" class="ev-panel ev-stack">
      <div class="ev-head">
        <h3>
          {{ draft.name || '新问题集' }}
          <span v-if="draft.revision" class="ev-pill"
            >v{{ draft.revision }} · {{ draft.frozen ? '冻结只读' : '草稿' }}</span
          >
        </h3>
        <div v-if="draft.id" class="ev-actions">
          <label
            >历史版本<input
              v-model.number="revision"
              type="number"
              min="1"
              aria-label="数据集历史版本" /></label
          ><button class="ev-button" :disabled="busy || dirty" @click="load(selected, revision)">
            读取版本
          </button>
        </div>
      </div>
      <p v-if="draft.frozen" class="ev-notice">
        此版本用于冻结验收。后续编辑将创建新修订；继续调参后请准备新的保留样本。<button
          class="ev-button"
          :disabled="busy"
          @click="copyDraft"
        >
          基于此版本编辑
        </button>
      </p>
      <p v-if="dirty && !draft.id" class="ev-notice">
        新草稿尚未保存。<button class="ev-button" :disabled="busy" @click="discardNewDraft">
          放弃新草稿
        </button>
      </p>
      <p v-if="dirty && draft.id" class="ev-notice ev-warning">
        有未保存修改，旧运行仍引用原始版本。<button
          class="ev-button"
          :disabled="busy"
          @click="load(draft.id, draft.revision)"
        >
          放弃修改，恢复版本
        </button>
      </p>
      <label>名称<input v-model="draft.name" :disabled="busy || draft.frozen" /></label>
      <p v-if="snapshot" class="ev-notice" :class="{ 'ev-warning': snapshot.scope === 'SELECTED' }">
        {{ snapshotDescription(snapshot) }}<br /><code>{{ snapshot.hash }}</code>
      </p>
      <SnapshotPicker
        v-else
        v-model="snapshot"
        :knowledge-id="knowledgeId"
        :chunks="chunks"
        :disabled="busy || !!draft.id"
      />
      <div class="ev-split">
        <aside class="ev-list">
          <button
            v-for="(item, index) in draft.questions"
            :key="item.id"
            class="ev-button"
            :aria-current="questionIndex === index"
            @click="selectQuestion(index)"
          >
            {{ index + 1 }}. {{ item.query || '未填写问题' }}<br /><span class="ev-muted"
              >{{ item.reviewed ? '已审核' : '待审核' }} ·
              {{ item.split === 'ACCEPTANCE' ? '验收' : '校准' }}</span
            >
          </button>
        </aside>
        <QuestionEditor
          v-if="current"
          v-model="current"
          :chunks="snapshot?.chunks || []"
          :disabled="busy || draft.frozen"
        />
        <p v-else class="ev-empty">添加第一个问题。</p>
      </div>
      <div v-if="!draft.frozen" class="ev-actions">
        <button class="ev-button" :disabled="busy" @click="addQuestion()">添加问题</button
        ><button
          class="ev-button"
          data-testid="add-variant"
          :disabled="busy || !current"
          @click="addQuestion(true)"
        >
          添加同义问法</button
        ><button class="ev-button ev-danger" :disabled="busy || !current" @click="removeConfirm = true">
          删除当前问题
        </button>
      </div>
      <div v-if="removeConfirm" class="ev-notice ev-warning">
        删除当前问题及其草稿标签？<button class="ev-button ev-danger" @click="removeQuestion">
          确认删除问题</button
        ><button class="ev-button" @click="removeConfirm = false">取消</button>
      </div>
      <div class="ev-actions">
        <button
          v-if="!draft.frozen"
          class="ev-button ev-primary"
          data-testid="save-dataset"
          :disabled="busy"
          @click="save()"
        >
          保存新版本</button
        ><button v-if="!draft.frozen" class="ev-button" :disabled="busy" @click="save(true)">
          保存并冻结验收版本</button
        ><button class="ev-button" :disabled="busy || dirty || !draft.id" @click="emit('run', draft)">
          运行此版本
        </button>
      </div>
      <p v-if="message" class="ev-notice" role="status">{{ message }}</p>
    </section>
  </div>
</template>
