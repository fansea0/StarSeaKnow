<script setup>
import { computed } from 'vue'
import { formatScore, isNumber, modelName } from './evaluationState'
import { formatMetric, metricName } from './reportFields'
const props = defineProps({ run: { type: Object, required: true } })
const comparisons = computed(() => (props.run.modelResults || []).filter((result) => result.comparison))
function evidence(row) {
  if (!isNumber(row.lower95) || !isNumber(row.upper95)) return '可配对意图组不足，无法判断稳定提升'
  if (row.lower95 <= 0 && row.upper95 >= 0) return '区间跨过零，提升证据不足'
  return '区间未跨零；请结合指标方向和逐题退化判断'
}
</script>
<template>
  <details>
    <summary>相对基线差异与意图组汇总</summary>
    <section
      v-for="result in run.modelResults || []"
      :key="result.modelId"
      class="ev-stack"
      style="margin-top: 14px"
    >
      <strong>{{ modelName(run.models?.find((m) => m.id === result.modelId)) }}</strong>
      <p>
        p50 {{ formatMetric('p50Ms', result.metrics?.p50Ms) }} · 失败率
        {{ formatMetric('failureRate', result.metrics?.failureRate) }}
      </p>
      <div v-if="result.metrics?.intentGroupMetrics" class="ev-table-wrap">
        <table class="ev-table">
          <thead>
            <tr>
              <th>指标</th>
              <th>按意图组平均</th>
              <th>适用意图组数</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="(value, key) in result.metrics.intentGroupMetrics" :key="key">
              <td>{{ metricName(key) }}</td>
              <td>{{ formatMetric(key, value) }}</td>
              <td>{{ result.metrics.intentGroupDenominators?.[key] ?? '未提供' }}</td>
            </tr>
          </tbody>
        </table>
      </div>
    </section>
    <section v-for="result in comparisons" :key="result.modelId" class="ev-stack" style="margin-top: 18px">
      <h3>{{ modelName(run.models?.find((m) => m.id === result.modelId)) }} · 相对基线</h3>
      <p class="ev-muted">
        配对 {{ result.comparison.pairedQuestionCount ?? '未知' }} 个问题 /
        {{ result.comparison.intentGroupCount ?? '未知' }}
        个意图组。变化值为候选减基线，区间按整个意图组重采样。
      </p>
      <div class="ev-table-wrap">
        <table class="ev-table">
          <thead>
            <tr>
              <th>指标</th>
              <th>基线</th>
              <th>候选</th>
              <th>变化</th>
              <th>95% 区间</th>
              <th>解释</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="(row, key) in result.comparison.metrics || {}" :key="key">
              <td>{{ metricName(key) }}</td>
              <td>{{ formatMetric(key, row.baseline) }}</td>
              <td>{{ formatMetric(key, row.candidate) }}</td>
              <td>{{ formatScore(row.delta) }}</td>
              <td>{{ formatScore(row.lower95) }} ～ {{ formatScore(row.upper95) }}</td>
              <td>{{ evidence(row) }}</td>
            </tr>
          </tbody>
        </table>
      </div>
    </section>
    <p v-if="!comparisons.length" class="ev-muted">
      没有可用的配对差异结果。至少需要基线与候选模型的匹配样本。
    </p>
  </details>
</template>
