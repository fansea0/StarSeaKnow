<script setup>
import { formatScore, isNumber, modelName } from './evaluationState'
import { requirementFields, optionalRequirementFields } from './reportFields'
const props = defineProps({ run: { type: Object, required: true } })
function retained(modelId, questionId) {
  const answerable = props.run.questions?.find((q) => q.id === questionId)?.answerable
  const metrics = props.run.modelResults
    ?.find((result) => result.modelId === modelId)
    ?.queries?.find((query) => query.questionId === questionId)?.metrics
  if (answerable === false) {
    const value = metrics?.noAnswerFalsePositive
    return value === 0 ? '未返回无关候选' : value === 1 ? '仍返回无关候选' : '证据未确认'
  }
  const value = metrics?.evidenceRetention
  return value === 1 ? '已保留正确证据' : value === 0 ? '未保留正确证据' : '证据未确认'
}
</script>
<template>
  <details>
    <summary>验收协议与阈值来源</summary>
    <p>Top K：{{ run.topK }} · 阈值边界：score &gt; threshold</p>
    <p v-if="run.retrievalSettings?.pipelineNote">{{ run.retrievalSettings.pipelineNote }}</p>
    <p v-if="run.retrievalSettings?.efSearch">HNSW ef_search：{{ run.retrievalSettings.efSearch }}</p>
    <p>
      校准来源：<code>{{ run.calibrationRunId || '未绑定校准运行' }}</code>
    </p>
    <div v-for="model in run.models || []" :key="model.id">
      {{ modelName(model) }}：{{
        isNumber(run.thresholds?.[model.id]) ? formatScore(run.thresholds[model.id]) : '关闭分数过滤'
      }}
    </div>
    <div class="ev-table-wrap" style="margin-top: 12px">
      <table class="ev-table">
        <thead>
          <tr>
            <th>要求</th>
            <th>此运行固定值</th>
          </tr>
        </thead>
        <tbody>
          <tr
            v-for="field in [
              ...requirementFields,
              ...optionalRequirementFields.filter((f) => run.requirements?.[f.key] != null),
            ]"
            :key="field.key"
          >
            <td>{{ field.label }}</td>
            <td>{{ run.requirements?.[field.key] ?? '未填写 · 证据不足' }}</td>
          </tr>
        </tbody>
      </table>
    </div>
    <section v-if="run.requirements?.criticalQuestionIds?.length" class="ev-stack" style="margin-top: 14px">
      <h3>关键问题逐题检查</h3>
      <div class="ev-table-wrap">
        <table class="ev-table">
          <thead>
            <tr>
              <th>问题</th>
              <th v-for="model in run.models || []" :key="model.id">{{ modelName(model) }}</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="id in run.requirements.criticalQuestionIds" :key="id">
              <td>{{ run.questions?.find((q) => q.id === id)?.query || id }}</td>
              <td v-for="model in run.models || []" :key="model.id">{{ retained(model.id, id) }}</td>
            </tr>
          </tbody>
        </table>
      </div>
    </section>
  </details>
</template>
