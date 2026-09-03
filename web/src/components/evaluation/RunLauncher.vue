<script setup>
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import * as api from '../../api/embeddingEvaluation'
import { errorText, isNumber, modelName, modelSignature, phaseText } from './evaluationState'
import ModelPicker from './ModelPicker.vue'
import CalibrationPanel from './CalibrationPanel.vue'
import AcceptanceProtocol from './AcceptanceProtocol.vue'
import { optionalRequirementFields } from './reportFields'
const props = defineProps({
  knowledgeId: { type: [String, Number], required: true },
  models: { type: Array, default: () => [] },
  datasets: { type: Array, default: () => [] },
  runs: { type: Array, default: () => [] },
  initialDataset: Object,
})
const emit = defineEmits(['created'])
const datasetId = ref(''),
  dataset = ref(null),
  revision = ref(null),
  phase = ref('CALIBRATION'),
  modelIds = ref(props.models.slice(0, 2).map((m) => m.id)),
  baseline = ref(modelIds.value[0] || ''),
  calibrationId = ref(''),
  calibration = ref(null),
  thresholds = ref({}),
  requirements = ref({}),
  busy = ref(false),
  loadingDataset = ref(false),
  loadingCalibration = ref(false),
  error = ref('')
let datasetEpoch = 0,
  calibrationEpoch = 0
const formal = computed(() => ['ACCEPTANCE', 'DELIVERY'].includes(phase.value))
const calibrationRuns = computed(() =>
  props.runs.filter((r) => r.phase === 'CALIBRATION' && r.status === 'COMPLETED'),
)
watch(
  () => props.initialDataset,
  (value) => {
    if (value) {
      dataset.value = value
      datasetId.value = value.id
      revision.value = value.revision
    }
  },
  { immediate: true },
)
async function chooseDataset(version) {
  const token = ++datasetEpoch
  dataset.value = null
  error.value = ''
  if (!datasetId.value) return
  loadingDataset.value = true
  try {
    const value = await api.getDataset(props.knowledgeId, datasetId.value, version)
    if (token === datasetEpoch) {
      dataset.value = value
      revision.value = value.revision
    }
  } catch (cause) {
    if (token === datasetEpoch) error.value = errorText(cause)
  } finally {
    if (token === datasetEpoch) loadingDataset.value = false
  }
}
async function chooseCalibration() {
  const token = ++calibrationEpoch
  calibration.value = null
  thresholds.value = {}
  error.value = ''
  if (!calibrationId.value) return
  loadingCalibration.value = true
  try {
    const value = await api.getRun(props.knowledgeId, calibrationId.value)
    if (token === calibrationEpoch) calibration.value = value
  } catch (cause) {
    if (token === calibrationEpoch) error.value = errorText(cause)
  } finally {
    if (token === calibrationEpoch) loadingCalibration.value = false
  }
}
function validate() {
  if (!dataset.value) return '请选择一个已保存的问题集版本。'
  if (!modelIds.value.length || modelIds.value.some((id) => !props.models.some((m) => m.id === id)))
    return '请选择仍可用的模型配置。'
  const split = formal.value ? 'ACCEPTANCE' : 'CALIBRATION'
  if (!dataset.value.questions?.some((q) => q.split === split))
    return `此版本没有${formal.value ? '验收' : '校准'}分组的问题。`
  if (!formal.value) return ''
  if (!dataset.value.frozen) return '验收与交付复测需要已冻结的问题集版本。'
  if (calibration.value?.status !== 'COMPLETED' || calibration.value?.phase !== 'CALIBRATION')
    return '请选择一个已完成的校准运行。'
  for (const id of modelIds.value) {
    const current = props.models.find((m) => m.id === id),
      calibrated = calibration.value.models?.find((m) => m.id === id)
    if (
      !calibrated ||
      modelSignature(calibrated) !== modelSignature(current) ||
      (calibrated.digest && current.digest && calibrated.digest !== current.digest)
    )
      return '模型版本已变化或未参加所选校准，请重新校准后验收。'
    const result = calibration.value.modelResults?.find((r) => r.modelId === id)
    if (
      result?.status !== 'COMPLETED' ||
      !isNumber(thresholds.value[id]) ||
      !result.calibration?.some((row) => row.threshold === thresholds.value[id])
    )
      return '请为入选模型逐个选择校准曲线中的阈值，不会自动选择最佳值。'
  }
  for (const key of [
    'minQuestions',
    'minUnanswerable',
    'hit5Min',
    'evidenceRetentionMin',
    'noAnswerFalsePositiveMax',
    'p95MaxMs',
  ]) {
    if (!isNumber(requirements.value[key])) return '请完整填写验收协议，缺失的要求不能当作 0。'
  }
  if (
    ['minQuestions', 'minUnanswerable'].some(
      (key) => !Number.isInteger(requirements.value[key]) || requirements.value[key] < 1,
    ) ||
    requirements.value.p95MaxMs <= 0
  )
    return '样本数需为正整数，延迟预算需大于 0。'
  if (
    ['hit5Min', 'evidenceRetentionMin', 'noAnswerFalsePositiveMax'].some(
      (key) => requirements.value[key] < 0 || requirements.value[key] > 1,
    )
  )
    return '比例门槛需在 0–1 之间。'
  for (const field of optionalRequirementFields) {
    const value = requirements.value[field.key]
    if (value == null) continue
    if (
      !isNumber(value) ||
      value < field.min ||
      (field.max != null && value > field.max) ||
      (field.step === 1 && !Number.isInteger(value))
    )
      return `请检查${field.label}。`
  }
  if (
    (requirements.value.criticalQuestionIds || []).some(
      (id) => !dataset.value.questions.some((q) => q.id === id && q.split === 'ACCEPTANCE'),
    )
  )
    return '关键问题不属于当前验收版本，请重新选择。'
  return ''
}
async function launch() {
  error.value = validate()
  if (error.value) return
  busy.value = true
  const command = {
    datasetId: dataset.value.id,
    datasetRevision: dataset.value.revision,
    modelIds: [...modelIds.value],
    baselineModelId: baseline.value,
    phase: phase.value,
    topK: 5,
    retrievalMode: phase.value === 'DELIVERY' ? 'PRODUCTION' : 'EXACT',
    thresholds: formal.value
      ? Object.fromEntries(modelIds.value.map((id) => [id, thresholds.value[id]]))
      : {},
    requirements: formal.value ? { ...requirements.value } : {},
    ...(formal.value ? { calibrationRunId: calibration.value.id } : {}),
  }
  try {
    emit('created', await api.createRun(props.knowledgeId, command))
  } catch (cause) {
    error.value = errorText(cause)
  } finally {
    busy.value = false
  }
}
onBeforeUnmount(() => {
  datasetEpoch++
  calibrationEpoch++
})
</script>
<template>
  <section class="ev-panel ev-stack" aria-label="创建评测运行">
    <h3>运行已保存的问题集版本</h3>
    <fieldset class="ev-stack" :disabled="busy">
      <div class="ev-fields">
        <label
          >问题集<select v-model="datasetId" :disabled="loadingDataset" @change="chooseDataset()">
            <option value="">请选择问题集</option>
            <option v-for="item in datasets" :key="item.id" :value="item.id">
              {{ item.name || item.id }} · v{{ item.revision }}
            </option>
          </select></label
        ><label
          >引用版本<input v-model.number="revision" type="number" min="1" :disabled="loadingDataset" /><button
            type="button"
            class="ev-button"
            :disabled="!datasetId || loadingDataset"
            @click="chooseDataset(revision)"
          >
            读取指定版本
          </button></label
        ><label
          >运行目的<select v-model="phase" data-testid="run-phase">
            <option value="CALIBRATION">调试 / 阈值校准</option>
            <option value="ACCEPTANCE">冻结验收 · 精确检索</option>
            <option value="DELIVERY">交付链路复测 · 独立索引</option>
          </select></label
        >
      </div>
      <p v-if="dataset" class="ev-notice">
        实际引用 v{{ dataset.revision }} · {{ dataset.frozen ? '已冻结' : '未冻结' }} ·
        {{ formal ? '仅使用 ACCEPTANCE 验收分组' : '仅使用 CALIBRATION 校准分组' }} · Top K = 5
      </p>
      <ModelPicker v-model="modelIds" v-model:baseline="baseline" :models="models" />
      <template v-if="formal">
        <label
          >校准来源<select
            v-model="calibrationId"
            data-testid="calibration-run"
            :disabled="loadingCalibration"
            @change="chooseCalibration"
          >
            <option value="">选择已完成的校准运行</option>
            <option v-for="item in calibrationRuns" :key="item.id" :value="item.id">
              {{ item.createdAt || item.id }} · 问题集 v{{ item.datasetRevision ?? '未知' }} · {{ item.id }}
            </option>
          </select></label
        >
        <p v-if="!calibrationRuns.length" class="ev-notice ev-warning">
          先完成一个校准运行，再刷新运行记录以选择来源。
        </p>
        <p v-if="loadingCalibration" role="status">正在读取校准曲线…</p>
        <CalibrationPanel
          v-if="calibration"
          v-model="thresholds"
          :run="calibration"
          :model-ids="modelIds"
          selectable
        />
        <div v-if="calibration" class="ev-notice">
          阈值来源：<code>{{ calibration.id }}</code>
          <p v-for="id in modelIds" :key="id">
            {{ modelName(models.find((m) => m.id === id)) }}：{{
              isNumber(thresholds[id]) ? thresholds[id] : '待手动选择'
            }}
          </p>
        </div>
        <AcceptanceProtocol v-model="requirements" :questions="dataset?.questions || []" />
      </template>
      <p v-else class="ev-notice">
        校准先运行无阈值的精确检索，再计算每个模型的阈值曲线。选择阈值后，使用冻结验收集验证。
      </p>
      <p v-if="phase === 'DELIVERY'" class="ev-notice ev-warning">
        使用候选模型的独立索引和生产检索链路。交付链路差异需要与精确检索结果分别审查。
      </p>
    </fieldset>
    <p v-if="error" class="ev-notice ev-error" role="alert">{{ error }}</p>
    <button
      class="ev-button ev-primary"
      data-testid="launch-run"
      :disabled="busy || loadingDataset || loadingCalibration"
      @click="launch"
    >
      {{ busy ? '提交中…' : `开始${phaseText(phase)}` }}
    </button>
  </section>
</template>
