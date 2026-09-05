<script setup>
import { onBeforeUnmount, ref, watch } from 'vue'
import * as api from '../../api/embeddingEvaluation'
import { errorText } from './evaluationState'
import { useEvaluationAccess } from './useEvaluationAccess'
import QuickComparison from './QuickComparison.vue'
import DatasetWorkspace from './DatasetWorkspace.vue'
import RunsWorkspace from './RunsWorkspace.vue'
import './evaluation.css'
const props = defineProps({ knowledgeId: { type: [String, Number], required: true }, initialChunkId: String })
const admin = useEvaluationAccess()
const tab = ref('quick'),
  models = ref([]),
  chunks = ref([]),
  datasets = ref([]),
  selectedDataset = ref(''),
  launchDataset = ref(null),
  loading = ref(false),
  initialized = ref(false),
  error = ref('')
let epoch = 0
async function load() {
  const token = ++epoch
  if (!admin.value) return
  loading.value = true
  error.value = ''
  const outcomes = await Promise.allSettled([
    api.listModels(),
    api.listChunks(props.knowledgeId),
    api.listDatasets(props.knowledgeId),
  ])
  if (token !== epoch || !admin.value) return
  const targets = [models, chunks, datasets],
    labels = ['模型', '语料', '问题集']
  outcomes.forEach((outcome, index) => {
    if (outcome.status === 'fulfilled') targets[index].value = outcome.value || []
    else error.value += `${labels[index]}加载失败：${errorText(outcome.reason)} `
  })
  loading.value = false
  initialized.value = true
}
function saved(value, open = false) {
  datasets.value = [value, ...datasets.value.filter((item) => item.id !== value.id)]
  if (open) {
    selectedDataset.value = value.id
    tab.value = 'datasets'
  }
}
function launch(value) {
  launchDataset.value = value
  tab.value = 'runs'
}
watch(
  [() => props.knowledgeId, admin],
  () => {
    epoch++
    initialized.value = false
    models.value = []
    chunks.value = []
    datasets.value = []
    selectedDataset.value = ''
    launchDataset.value = null
    if (admin.value) load()
  },
  { immediate: true },
)
onBeforeUnmount(() => epoch++)
</script>
<template>
  <section class="evaluation ev-stack" aria-label="检索评测工作台">
    <p v-if="!admin" class="ev-notice ev-warning">仅租户管理员可使用检索评测。</p>
    <template v-else>
      <header class="ev-head">
        <div>
          <h2>检索评测</h2>
          <p class="ev-muted">冻结语料与人工真值，比较模型，再验证交付要求。</p>
        </div>
        <button class="ev-button" :disabled="loading" @click="load">刷新配置与语料</button>
      </header>
      <nav class="ev-tabs" role="tablist" aria-label="评测导航">
        <button
          v-for="item in [
            { id: 'quick', label: '快速对比' },
            { id: 'datasets', label: '问题集' },
            { id: 'runs', label: '运行记录' },
          ]"
          :key="item.id"
          class="ev-button"
          role="tab"
          :data-testid="`evaluation-tab-${item.id}`"
          :aria-selected="tab === item.id"
          @click="tab = item.id"
        >
          {{ item.label }}
        </button>
      </nav>
      <p v-if="error" class="ev-notice ev-error" role="alert">
        {{ error }} <button class="ev-button" :disabled="loading" @click="load">重试加载</button>
      </p>
      <p v-if="loading" role="status">正在读取评测配置…</p>
      <template v-if="initialized">
        <QuickComparison
          v-show="tab === 'quick'"
          :key="`quick-${knowledgeId}`"
          :knowledge-id="knowledgeId"
          :models="models"
          :chunks="chunks"
          :datasets="datasets"
          :initial-chunk-id="initialChunkId"
          @saved="saved($event, true)"
        />
        <DatasetWorkspace
          v-show="tab === 'datasets'"
          :key="`dataset-${knowledgeId}`"
          :knowledge-id="knowledgeId"
          :chunks="chunks"
          :datasets="datasets"
          :selected-id="selectedDataset"
          @saved="saved($event)"
          @run="launch"
        />
        <RunsWorkspace
          :active="tab === 'runs'"
          v-show="tab === 'runs'"
          :key="`runs-${knowledgeId}`"
          :knowledge-id="knowledgeId"
          :models="models"
          :datasets="datasets"
          :initial-dataset="launchDataset"
        />
      </template>
    </template>
  </section>
</template>
