<script setup>
import { computed, ref, watch } from 'vue'
import * as api from '../../api/embeddingEvaluation'
import {
  errorText,
  formatMs,
  formatScore,
  isActiveRun,
  isNumber,
  modelName,
  phaseText,
  statusText,
} from './evaluationState'
import AlignedResults from './AlignedResults.vue'
import CalibrationPanel from './CalibrationPanel.vue'
import ComparisonEvidence from './ComparisonEvidence.vue'
import ProtocolReport from './ProtocolReport.vue'
import { metricFields, formatMetric } from './reportFields'
const props = defineProps({
  run: { type: Object, required: true },
  knowledgeId: { type: [String, Number], required: true },
  busy: Boolean,
})
const emit = defineEmits(['cancel', 'retry'])
const questionId = ref(''),
  filter = ref('all'),
  filterModel = ref(''),
  exportBusy = ref(false),
  error = ref('')
const effectiveVerdict = computed(() =>
  props.run.verdict === 'PASS' && props.run.status !== 'COMPLETED' ? 'INSUFFICIENT' : props.run.verdict,
)
const verdictText = computed(
  () =>
    ({
      DIAGNOSTIC: '诊断结果 · 不作为验收结论',
      CALIBRATION: '校准结果 · 待冻结验收',
      PASS: '满足已设门槛',
      FAIL: '不满足已设门槛',
      INSUFFICIENT: '证据不足',
    })[effectiveVerdict.value] || '等待结论',
)
const modelResults = computed(() => props.run.modelResults || [])
function query(result, id) {
  return result?.queries?.find((q) => q.questionId === id)
}
function hit(question, result, k = 5) {
  const value = query(result, question.id)
  const metric = k === 1 ? 'hit1' : 'hit5'
  if (value?.metrics && Object.hasOwn(value.metrics, metric))
    return isNumber(value.metrics[metric]) ? value.metrics[metric] === 1 : null
  if (value?.status !== 'COMPLETED') return null
  return (value.hits || []).some((h) => h.rank <= k && (question.labels?.[h.chunkId] ?? h.label) === 2)
}
function negativeWins(question, result) {
  const q = query(result, question.id),
    scores = [...(q?.hits || []), ...(q?.judgedScores || [])]
  const positive = scores
    .filter((h) => (question.labels?.[h.chunkId] ?? h.label) === 2 && isNumber(h.score))
    .map((h) => h.score)
  const negative = scores
    .filter((h) => question.hardNegativeIds?.includes(h.chunkId) && isNumber(h.score))
    .map((h) => h.score)
  return (
    question.reviewed && positive.length && negative.length && Math.max(...negative) >= Math.max(...positive)
  )
}
const filteredQuestions = computed(() => {
  const chosen = modelResults.value.filter((r) => !filterModel.value || r.modelId === filterModel.value)
  const base = modelResults.value.find((r) => r.modelId === props.run.baselineModelId)
  return (props.run.questions || []).filter((question) => {
    if (filter.value === 'all') return true
    if (filter.value === 'miss1')
      return question.answerable && chosen.some((r) => hit(question, r, 1) === false)
    if (filter.value === 'miss5') return question.answerable && chosen.some((r) => hit(question, r) === false)
    if (filter.value === 'negative') return chosen.some((r) => negativeWins(question, r))
    if (filter.value === 'regression')
      return hit(question, base) === true && chosen.some((r) => r !== base && hit(question, r) === false)
    if (filter.value === 'variant') {
      const group = props.run.questions.filter((q) => q.intentGroup === question.intentGroup && q.reviewed)
      return (
        group.length > 1 &&
        chosen.some((r) => group.some((q) => hit(q, r) === true) && group.some((q) => hit(q, r) === false))
      )
    }
    if (filter.value === 'noanswer')
      return (
        !question.answerable &&
        question.reviewed &&
        chosen.some((r) =>
          (query(r, question.id)?.hits || []).some(
            (h) =>
              h.rank <= props.run.topK &&
              isNumber(h.score) &&
              (!isNumber(props.run.thresholds?.[r.modelId]) || h.score > props.run.thresholds[r.modelId]),
          ),
        )
      )
    if (filter.value === 'unknown')
      return chosen.some((r) =>
        (query(r, question.id)?.hits || []).some((h) => (question.labels?.[h.chunkId] ?? h.label) == null),
      )
    return true
  })
})
const selectedQuestion = computed(
  () => filteredQuestions.value.find((q) => q.id === questionId.value) || filteredQuestions.value[0],
)
watch(
  () => props.run.id,
  () => {
    filter.value = 'all'
    filterModel.value = ''
    questionId.value = ''
    error.value = ''
  },
)
function inspect(modelId, value) {
  filterModel.value = modelId
  filter.value = value
  questionId.value = ''
}
async function download(format) {
  exportBusy.value = true
  error.value = ''
  try {
    api.downloadReport(await api.exportRun(props.knowledgeId, props.run.id, format), props.run.id, format)
  } catch (cause) {
    error.value = errorText(cause)
  } finally {
    exportBusy.value = false
  }
}
</script>
<template>
  <section class="ev-panel ev-stack" aria-label="评测运行报告">
    <header class="ev-head">
      <div>
        <h3>{{ phaseText(run.phase) }} · {{ statusText(run.status) }}</h3>
        <p class="ev-muted">
          {{ new Date(run.createdAt).toLocaleString('zh-CN', { hour12: false }) }} · {{ run.retrievalMode === 'PRODUCTION' ? '独立生产链路复测' : '精确余弦检索' }}
        </p>
      </div>
      <div class="ev-actions">
        <button v-if="isActiveRun(run)" class="ev-button ev-danger" :disabled="busy" @click="emit('cancel')">
          取消运行</button
        ><button
          v-if="['PARTIAL', 'FAILED', 'CANCELLED'].includes(run.status)"
          class="ev-button"
          :disabled="busy"
          @click="emit('retry')"
        >
          重试相同输入（新运行）
        </button>
      </div>
    </header>
    <p class="ev-notice" :class="{ 'ev-warning': effectiveVerdict !== 'PASS' }">
      <strong>{{ verdictText }}</strong
      ><br />{{ run.scope === 'SELECTED' ? '候选集内对比，不能与全库验收混同' : '固定语料快照' }} · 问题集
      {{ run.datasetId || '单题诊断' }}
      <template v-if="run.datasetRevision != null">v{{ run.datasetRevision }}</template
      ><br /><span v-for="reason in run.verdictReasons || []" :key="reason">{{ reason }}<br /></span
      ><code>{{ run.snapshotHash || '快照哈希未提供' }}</code>
    </p>
    <div aria-live="polite">
      <span
        >{{ run.progress?.message || statusText(run.status) }} · {{ run.progress?.completed ?? '未知' }} /
        {{ run.progress?.total ?? '未知' }}</span
      ><progress v-if="run.progress?.total > 0" :value="run.progress.completed" :max="run.progress.total" />
    </div>
    <p v-if="run.error" class="ev-notice ev-error" role="alert">{{ run.error }}</p>
    <div class="ev-fields">
      <div v-for="model in run.models || []" :key="model.id" class="ev-notice">
        <strong>{{ modelName(model) }}</strong
        ><template v-for="result in modelResults.filter((r) => r.modelId === model.id)" :key="result.modelId"
          ><p>
            {{ statusText(result.status) }} · 向量 {{ result.preparedChunks ?? '未知' }} /
            {{ result.totalChunks ?? '未知' }} 块
          </p>
          <p v-if="result.error" role="alert">{{ result.error }}</p>
          <span class="ev-muted"
            >自相似 {{ formatScore(result.selfSimilarity) }} · 建库 {{ formatMs(result.buildMs) }}</span
          ></template
        >
        <details>
          <summary>模型版本与编码</summary>
          <code
            >{{ model.modelName }} · v{{ model.revision }}<br />{{ model.digest || 'digest 未取得' }}</code
          >
          <p>
            {{ model.dimensions ?? '未知' }} 维 · {{ model.quantization || '量化未知' }} · Ollama
            {{ model.ollamaVersion || '未知' }}
          </p>
          <pre>
Query 前缀：{{ model.queryPrefix || '（空）' }}
Document 前缀：{{ model.documentPrefix || '（空）' }}</pre
          >
        </details>
      </div>
    </div>
    <details :open="run.phase !== 'QUICK'">
    <summary v-if="run.phase === 'QUICK'">指标与样本筛选 · 完成标注后可查看</summary>
    <div class="ev-table-wrap">
      <table class="ev-table">
        <thead>
          <tr>
            <th>模型 / 分母</th>
            <th v-for="column in metricFields" :key="column.key">{{ column.label }}</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="result in modelResults" :key="result.modelId">
            <td>
              <strong>{{ modelName(run.models?.find((m) => m.id === result.modelId)) }}</strong>
              <p>
                完成 {{ result.metrics?.completedCount ?? '未知' }} /
                {{ result.metrics?.questionCount ?? '未知' }} · 失败
                {{ result.metrics?.failedCount ?? '未知' }}
              </p>
              <p>
                有答案 {{ result.metrics?.answerableCount ?? '未知' }} · 无答案
                {{ result.metrics?.unanswerableCount ?? '未知' }}
              </p>
              <p>
                已审核 {{ result.metrics?.reviewedCount ?? '未知' }} · Top K 未标注
                {{ result.metrics?.unknownTopKCount ?? '未知' }} · 计分范围未标注
                {{ result.metrics?.unknownScoringCount ?? '未知' }}
              </p>
            </td>
            <td v-for="column in metricFields" :key="column.key">
              <button
                class="ev-button"
                :aria-label="`${column.label} 查看样本`"
                @click="inspect(result.modelId, column.filter)"
              >
                {{ formatMetric(column.key, result.metrics?.[column.key]) }}
              </button>
              <p class="ev-muted">适用数 {{ result.metrics?.denominators?.[column.key] ?? '未提供' }}</p>
            </td>
          </tr>
        </tbody>
      </table>
    </div>
    <div class="ev-fields">
      <label
        >模型筛选<select v-model="filterModel">
          <option value="">全部模型</option>
          <option v-for="model in run.models || []" :key="model.id" :value="model.id">
            {{ modelName(model) }}
          </option>
        </select></label
      ><label
        >问题复核<select v-model="filter">
          <option value="all">全部样本</option>
          <option value="regression">相较基线退化</option>
          <option value="negative">困难负例反超或同分</option>
          <option value="variant">同组问法部分未命中</option>
          <option value="noanswer">无答案仍返回</option>
          <option value="miss1">Hit@1 未命中</option>
          <option value="miss5">Hit@5 未命中</option>
          <option value="unknown">存在未知标签</option>
        </select></label
      ><label
        >问题 · {{ filteredQuestions.length }} 条<select v-model="questionId">
          <option value="">选择问题（默认首条）</option>
          <option v-for="question in filteredQuestions" :key="question.id" :value="question.id">
            {{ question.query }}
          </option>
        </select></label
      >
    </div>
    </details>
    <AlignedResults v-if="selectedQuestion" :run="run" :question="selectedQuestion" />
    <p v-else class="ev-empty">没有符合筛选条件的样本。</p>
    <CalibrationPanel v-if="run.phase === 'CALIBRATION'" :run="run" />
    <ComparisonEvidence :run="run" />
    <ProtocolReport :run="run" />
    <div class="ev-actions">
      <button
        v-for="format in ['html', 'csv', 'json']"
        :key="format"
        class="ev-button"
        :disabled="exportBusy || isActiveRun(run)"
        :data-testid="`export-${format}`"
        @click="download(format)"
      >
        导出 {{ format.toUpperCase() }}</button
      ><span class="ev-muted">导出保存的版本，包含缺失证据与失败原因。</span>
    </div>
    <p v-if="error" class="ev-notice ev-error" role="alert">{{ error }}</p>
  </section>
</template>
