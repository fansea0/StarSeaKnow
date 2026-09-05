<script setup>
import { onBeforeUnmount, ref, watch } from 'vue'
import { listRuns } from '../../api/embeddingEvaluation'
import { errorText, isActiveRun, phaseText, statusText } from './evaluationState'
import { useRunPolling } from './useRunPolling'
import RunLauncher from './RunLauncher.vue'
import RunReport from './RunReport.vue'
const props = defineProps({
  knowledgeId: { type: [String, Number], required: true },
  models: { type: Array, default: () => [] },
  datasets: { type: Array, default: () => [] },
  initialDataset: Object,
  active: { type: Boolean, default: true },
})
const runs = ref([]),
  error = ref(''),
  loading = ref(false),
  showLauncher = ref(!!props.initialDataset)
const {
  run,
  error: pollingError,
  busy,
  open,
  setRun,
  refresh,
  cancel,
  retry,
} = useRunPolling(() => props.knowledgeId)
let listTimer,
  listEpoch = 0,
  disposed = false
async function load() {
  clearTimeout(listTimer)
  const token = ++listEpoch
  loading.value = true
  error.value = ''
  try {
    const value = await listRuns(props.knowledgeId)
    if (disposed || token !== listEpoch) return
    runs.value = value
    if (props.active && value.some(isActiveRun)) listTimer = setTimeout(load, 5000)
  } catch (cause) {
    if (!disposed && token === listEpoch) error.value = errorText(cause)
  } finally {
    if (!disposed && token === listEpoch) loading.value = false
  }
}
function created(value) {
  setRun(value)
  showLauncher.value = false
  load()
}
watch(
  () => props.initialDataset,
  (value) => {
    if (value) showLauncher.value = true
  },
)
watch(
  () => run.value?.status,
  (status, old) => {
    if (status && old && !isActiveRun(run.value)) load()
  },
)
watch(
  () => props.active,
  (active) => {
    if (active) load()
    else clearTimeout(listTimer)
  },
  { immediate: true },
)
onBeforeUnmount(() => {
  disposed = true
  listEpoch++
  clearTimeout(listTimer)
})
</script>
<template>
  <div class="ev-stack">
    <div class="ev-head">
      <h3>运行记录</h3>
      <div class="ev-actions">
        <button class="ev-button" :disabled="loading" @click="load">刷新记录</button
        ><button class="ev-button ev-primary" @click="showLauncher = !showLauncher">
          {{ showLauncher ? '收起运行配置' : '新建批量运行' }}
        </button>
      </div>
    </div>
    <p v-if="error" class="ev-notice ev-error" role="alert">{{ error }}</p>
    <RunLauncher
      v-show="showLauncher"
      :knowledge-id="knowledgeId"
      :models="models"
      :datasets="datasets"
      :runs="runs"
      :initial-dataset="initialDataset"
      @created="created"
    />
    <div class="ev-table-wrap ev-scroll">
      <table class="ev-table">
        <thead>
          <tr>
            <th>创建时间 / ID</th>
            <th>用途 / 版本</th>
            <th>进度</th>
            <th>操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="item in runs" :key="item.id">
            <td>
              {{ item.createdAt }}<br /><code>{{ item.id }}</code>
            </td>
            <td>
              {{ phaseText(item.phase) }} ·
              {{ item.retrievalMode === 'PRODUCTION' ? '生产链路' : '精确检索' }}
              <p>问题集 v{{ item.datasetRevision ?? '—' }}</p>
            </td>
            <td>
              {{ statusText(item.status) }}
              <p>{{ item.progress?.completed ?? '未知' }} / {{ item.progress?.total ?? '未知' }}</p>
            </td>
            <td><button class="ev-button" :disabled="busy" @click="open(item.id)">查看报告</button></td>
          </tr>
          <tr v-if="!runs.length">
            <td colspan="4" class="ev-empty">
              {{
                loading ? '正在读取运行记录…' : '还没有运行。保存问题集后开始校准，或在快速对比中采集基线。'
              }}
            </td>
          </tr>
        </tbody>
      </table>
    </div>
    <p v-if="pollingError" class="ev-notice ev-error" role="alert">
      {{ pollingError }} <button class="ev-button" @click="refresh">重新获取进度</button>
    </p>
    <p v-if="busy && !run" role="status">正在加载报告…</p>
    <RunReport
      v-if="run"
      :run="run"
      :knowledge-id="knowledgeId"
      :busy="busy"
      @cancel="cancel"
      @retry="retry"
    />
  </div>
</template>
