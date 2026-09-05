<script setup>
import { formatPercent, formatScore, isNumber, modelName } from './evaluationState'
defineProps({
  run: { type: Object, required: true },
  modelValue: { type: Object, default: () => ({}) },
  selectable: Boolean,
  modelIds: { type: Array, default: () => [] },
})
const emit = defineEmits(['update:modelValue'])
</script>
<template>
  <section class="ev-stack">
    <h3>阈值权衡 · 每个模型独立选择</h3>
    <p class="ev-muted">
      边界规则：score &gt; threshold。0
      是有效阈值；关闭过滤表示不传阈值。这里只评估检索证据，不代表最终回答质量。
    </p>
    <section
      v-for="result in (run.modelResults || []).filter(
        (r) => !modelIds.length || modelIds.includes(r.modelId),
      )"
      :key="result.modelId"
      class="ev-stack"
    >
      <strong>{{ modelName(run.models?.find((m) => m.id === result.modelId)) }}</strong>
      <div class="ev-table-wrap ev-scroll">
        <table class="ev-table">
          <thead>
            <tr>
              <th v-if="selectable">选择</th>
              <th>阈值</th>
              <th>正确证据保留率</th>
              <th>无答案误召回率</th>
              <th>有答案但返回空</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="row in result.calibration || []" :key="row.threshold">
              <td v-if="selectable">
                <label class="ev-check"
                  ><input
                    type="radio"
                    :name="`threshold-${result.modelId}`"
                    :checked="modelValue[result.modelId] === row.threshold"
                    :disabled="!isNumber(row.threshold)"
                    :aria-label="`${result.modelId} 选择阈值 ${row.threshold}`"
                    @change="emit('update:modelValue', { ...modelValue, [result.modelId]: row.threshold })"
                  />使用</label
                >
              </td>
              <td class="ev-score">{{ row.threshold === null ? '关闭过滤（参考）' : formatScore(row.threshold) }}</td>
              <td>{{ formatPercent(row.evidenceRetentionRate) }}</td>
              <td>{{ formatPercent(row.noAnswerFalsePositiveRate) }}</td>
              <td>{{ formatPercent(row.answerableEmptyRate) }}</td>
            </tr>
            <tr v-if="!result.calibration?.length">
              <td :colspan="selectable ? 5 : 4" class="ev-empty">
                该模型没有可用校准结果，不能据此选择验收阈值。
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </section>
  </section>
</template>
