<script setup>
import { computed, ref, watch } from 'vue'
import * as api from '../../api/embeddingEvaluation'
import {
  clone,
  errorText,
  isActiveRun,
  newQuestion,
  modelSignature,
  snapshotDescription,
  validateQuestions,
} from './evaluationState'
import { useRunPolling } from './useRunPolling'
import SnapshotPicker from './SnapshotPicker.vue'
import ModelPicker from './ModelPicker.vue'
import QuestionEditor from './QuestionEditor.vue'
import RunReport from './RunReport.vue'
const props = defineProps({
  knowledgeId: { type: [String, Number], required: true },
  chunks: { type: Array, default: () => [] },
  models: { type: Array, default: () => [] },
  datasets: { type: Array, default: () => [] },
  initialChunkId: String,
})
const emit = defineEmits(['saved'])
const snapshot = ref(null),
  question = ref(newQuestion()),
  modelIds = ref(props.models.slice(0, 2).map((m) => m.id)),
  baseline = ref(modelIds.value[0] || '')
const datasetId = ref(''),
  datasetName = ref(''),
  loadedDataset = ref(null),
  busy = ref(false),
  error = ref(''),
  savedInput = ref(''),
  savedMessage = ref(''),
  savedQuestion = ref('')
const {
  run,
  error: pollingError,
  busy: pollingBusy,
  setRun,
  refresh,
  cancel,
  retry,
} = useRunPolling(() => props.knowledgeId)
const fingerprint = computed(() =>
  JSON.stringify({
    snapshot: snapshot.value?.id,
    question: question.value,
    models: props.models
      .filter((m) => modelIds.value.includes(m.id))
      .map((m) => [modelSignature(m), m.digest]),
    baseline: baseline.value,
  }),
)
const sourceChanged = computed(() =>
  (snapshot.value?.chunks || []).some((chunk) => {
    const live = props.chunks.find((item) => item.id === chunk.id)
    return (
      !live ||
      live.content !== chunk.content ||
      (live.lockVersion != null && chunk.lockVersion != null && live.lockVersion !== chunk.lockVersion)
    )
  }),
)
const stale = computed(() => !!run.value && (savedInput.value !== fingerprint.value || sourceChanged.value))
const questionFingerprint = computed(() => JSON.stringify([snapshot.value?.id, question.value]))
watch(
  () => snapshot.value?.id,
  (id, previous) => {
    if (id !== previous)
      question.value = { ...question.value, reviewed: false, labels: {}, hardNegativeIds: [] }
  },
)
async function chooseDataset() {
  error.value = ''
  snapshot.value = null
  loadedDataset.value = null
  if (!datasetId.value) return
  busy.value = true
  try {
    loadedDataset.value = await api.getDataset(props.knowledgeId, datasetId.value)
    snapshot.value = await api.getSnapshot(props.knowledgeId, loadedDataset.value.snapshotId)
  } catch (cause) {
    error.value = errorText(cause)
  } finally {
    busy.value = false
  }
}
async function start() {
  error.value = validateQuestions([question.value])
  if (error.value || !snapshot.value || !modelIds.value.length) return
  if (modelIds.value.some((id) => !props.models.some((model) => model.id === id))) {
    error.value = '所选模型已删除，请重新选择模型。'
    return
  }
  busy.value = true
  const input = fingerprint.value
  const command = {
    snapshotId: snapshot.value.id,
    questions: [clone(question.value)],
    modelIds: [...modelIds.value],
    baselineModelId: baseline.value,
    phase: 'QUICK',
    topK: 5,
    thresholds: {},
    requirements: {},
    retrievalMode: 'EXACT',
  }
  try {
    const result = await api.createRun(props.knowledgeId, command)
    savedInput.value = input
    setRun(result)
  } catch (cause) {
    error.value = errorText(cause)
  } finally {
    busy.value = false
  }
}
async function saveQuestion() {
  error.value = validateQuestions([question.value])
  if (error.value || !snapshot.value) return
  if (!loadedDataset.value && !datasetName.value.trim()) {
    error.value = '请填写新问题集名称。'
    return
  }
  if (loadedDataset.value?.frozen) {
    error.value = '冻结问题集不可直接追加，请到问题集页基于此版本创建新修订。'
    return
  }
  const questions = [...clone(loadedDataset.value?.questions || []), clone(question.value)]
  error.value = validateQuestions(questions)
  if (error.value) return
  busy.value = true
  try {
    const command = {
      name: loadedDataset.value?.name || datasetName.value.trim(),
      snapshotId: snapshot.value.id,
      questions,
      frozen: false,
      ...(loadedDataset.value ? { revision: loadedDataset.value.revision } : {}),
    }
    const saved = loadedDataset.value
      ? await api.updateDataset(props.knowledgeId, loadedDataset.value.id, command)
      : await api.createDataset(props.knowledgeId, command)
    savedMessage.value = `已保存到 ${saved.name} · v${saved.revision}`
    savedQuestion.value = questionFingerprint.value
    emit('saved', saved)
  } catch (cause) {
    error.value = errorText(cause)
  } finally {
    busy.value = false
  }
}
</script>
<template>
  <div class="ev-stack">
    <section class="ev-panel ev-stack">
      <h3>固定语料，复现一个真实问题</h3>
      <label
        >语料来源<select v-model="datasetId" :disabled="busy" @change="chooseDataset">
          <option value="">新建语料快照</option>
          <option v-for="item in datasets" :key="item.id" :value="item.id">
            复用 {{ item.name }} · v{{ item.revision }} 的快照
          </option>
        </select></label
      >
      <SnapshotPicker
        v-if="!datasetId"
        v-model="snapshot"
        :knowledge-id="knowledgeId"
        :chunks="chunks"
        :initial-chunk-id="initialChunkId"
        :disabled="busy"
      />
      <p v-else class="ev-notice">{{ snapshotDescription(snapshot) }}</p>
      <p v-if="sourceChanged" class="ev-notice ev-warning">
        当前语料已变化，此快照仍保留原始文本。测试最新文本请重建快照；旧问题集仍使用其冻结文本。
      </p>
      <ModelPicker v-model="modelIds" v-model:baseline="baseline" :models="models" :disabled="busy" />
      <QuestionEditor v-model="question" :chunks="snapshot?.chunks || []" :disabled="busy" compact />
      <p v-if="error" class="ev-notice ev-error" role="alert">{{ error }}</p>
      <div class="ev-actions">
        <button
          class="ev-button ev-primary"
          data-testid="start-quick"
          :disabled="busy || !snapshot || !modelIds.length || isActiveRun(run)"
          @click="start"
        >
          {{ busy ? '提交中…' : '开始对比' }}</button
        ><span class="ev-muted">精确检索 · 不过滤分数 · 相似度不是正确率</span>
      </div>
    </section>
    <p v-if="stale" class="ev-notice ev-warning" data-testid="stale-results" role="status">
      待重新计算：问题、模型或快照已变化。下方仍为原始运行结果。
    </p>
    <p v-if="pollingError" class="ev-notice ev-error" role="alert">
      {{ pollingError }} <button class="ev-button" @click="refresh">重新获取进度</button>
    </p>
    <RunReport
      v-if="run"
      :run="run"
      :knowledge-id="knowledgeId"
      :busy="pollingBusy"
      @cancel="cancel"
      @retry="retry"
    />
    <section class="ev-panel ev-stack">
      <h3>把这次问题留作评测样本</h3>
      <label v-if="!loadedDataset"
        >新问题集名称<input
          v-model="datasetName"
          data-testid="quick-dataset-name"
          placeholder="例如：安装与配置"
      /></label>
      <p v-else class="ev-muted">
        追加到 {{ loadedDataset.name }} · v{{ loadedDataset.revision }}，使用相同快照。
      </p>
      <div class="ev-actions">
        <button
          class="ev-button"
          data-testid="save-quick-question"
          :disabled="busy || !snapshot || loadedDataset?.frozen || savedQuestion === questionFingerprint"
          @click="saveQuestion"
        >
          加入问题集</button
        ><span class="ev-muted">保存后可添加同义问法、继续标注与冻结验收。</span>
      </div>
      <p v-if="savedMessage" class="ev-notice" role="status">{{ savedMessage }}</p>
    </section>
  </div>
</template>
