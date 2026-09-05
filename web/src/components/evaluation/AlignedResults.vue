<script setup>
import { computed, ref } from 'vue'
import {
  alignChunks,
  formatScore,
  isNumber,
  labelText,
  modelName,
  questionGap,
  statusText,
} from './evaluationState'
const props = defineProps({
  run: { type: Object, required: true },
  question: { type: Object, required: true },
})
const mode = ref('aligned'),
  visibleCount = ref(5)
const results = computed(() =>
  (props.run.models || []).map((model) => ({
    model,
    result: props.run.modelResults?.find((r) => r.modelId === model.id),
  })),
)
const rows = computed(() => alignChunks(props.run.modelResults, props.question.id))
const visibleRows = computed(() =>
  rows.value.filter((row) =>
    Object.values(row.scores).some((h) => h.judgedOnly || h.rank == null || h.rank <= visibleCount.value),
  ),
)
function queryFor(result) {
  return result?.queries?.find((q) => q.questionId === props.question.id)
}
function labelFor(hit) {
  return props.question.labels?.[hit.chunkId] ?? hit.label
}
function missingText(result) {
  const query = queryFor(result)
  if (result?.status === 'FAILED' || query?.status === 'FAILED') return '计算失败'
  if (result?.status === 'CANCELLED' || query?.status === 'CANCELLED') return '已取消 · 无结果'
  if (query?.status !== 'COMPLETED') return '等待计算'
  return '未进入保存的 Top K'
}
function ties(query, hit) {
  return (
    isNumber(hit.score) &&
    (query?.hits || []).some((other) => other.chunkId !== hit.chunkId && other.score === hit.score)
  )
}
function retained(result, hit) {
  const query = queryFor(result)
  if (Array.isArray(query?.acceptedHits)) return query.acceptedHits.some((item) => item.chunkId === hit.chunkId)
  return hit.rank <= props.run.topK && hit.score > props.run.thresholds?.[result?.modelId]
}
const referenceFields = [
  ['hit1', 'Hit@1'], ['hit5', 'Hit@5'], ['mrr10', 'MRR@10'],
  ['evidenceRetention', '正确证据保留'], ['noAnswerFalsePositive', '无答案误召回'],
]
</script>
<template>
  <section class="ev-stack">
    <div class="ev-head">
      <h3>逐题证据</h3>
      <div class="ev-tabs" role="tablist" aria-label="结果布局">
        <button class="ev-button" role="tab" :aria-selected="mode === 'aligned'" @click="mode = 'aligned'">
          按相同 chunk 对齐</button
        ><button class="ev-button" role="tab" :aria-selected="mode === 'rank'" @click="mode = 'rank'">
          按排名查看
        </button>
      </div>
    </div>
    <p class="ev-notice">
      {{ question.query }}<br /><span class="ev-muted"
        >意图组 {{ question.intentGroup }} · {{ question.answerable ? '可回答' : '已确认无答案' }} ·
        {{ question.reviewed ? '已审核' : '未审核' }}。余弦分数保留负值，不能跨模型直接比较分数大小。</span
      >
    </p>
    <p v-if="run.retrievalMode === 'PRODUCTION'" class="ev-muted">
      默认名次来自本次无阈值检索。阈值后是否实际返回单独标注；补充证据的精确名次不计为检索命中。
    </p>
    <label
      >显示范围<select v-model.number="visibleCount">
        <option :value="5">前 5 名与额外已标注证据</option>
        <option :value="Number.MAX_SAFE_INTEGER">全部保存的候选</option></select
      ><span class="ev-muted">显示条数不改变后台指标计分范围。</span></label
    >
    <div class="ev-fields">
      <div v-for="{ model, result } in results" :key="model.id" class="ev-notice">
        <strong>{{ modelName(model) }}</strong>
        <p>
          最佳可回答块 {{ formatScore(queryFor(result)?.metrics?.bestAnswerScore) }} · 最高已审核无关块
          {{ formatScore(queryFor(result)?.metrics?.bestIrrelevantScore) }}
        </p>
        <p>
          正例领先无关块：<span class="ev-score">{{
            questionGap(queryFor(result), question.reviewed) == null
              ? '缺少标注'
              : formatScore(questionGap(queryFor(result), question.reviewed))
          }}</span>
        </p>
        <span class="ev-muted">仅已审核标签 2 与 0 的候选范围</span>
        <p v-if="queryFor(result)?.error" class="ev-danger">{{ queryFor(result).error }}</p>
      </div>
    </div>
    <div v-if="mode === 'aligned'" class="ev-table-wrap">
      <table class="ev-table">
        <thead>
          <tr>
            <th>Chunk / 人工标签 / 文本</th>
            <th v-for="{ model } in results" :key="model.id">{{ modelName(model) }}</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="row in visibleRows" :key="row.chunkId" :data-testid="`aligned-${row.chunkId}`">
            <td class="ev-evidence ev-evidence-sticky">
              <span class="ev-pill" :class="`ev-label-${labelFor(row)}`">{{ labelText(labelFor(row)) }}</span>
              <span v-if="question.hardNegativeIds?.includes(row.chunkId)" class="ev-pill">困难负例</span>
              <details>
                <summary>
                  {{ row.fileName || '来源未知' }} · {{ row.content?.slice(0, 100) || row.chunkId }}
                </summary>
                <p>{{ row.content }}</p>
                <p>{{ Array.isArray(row.sectionPath) ? row.sectionPath.join(' / ') : row.sectionPath }}</p>
                <strong>完整索引正文（下方可查看各模型的完整编码输入）</strong>
                <pre>{{ row.indexContent ?? '入模文本未提供' }}</pre>
                <code>{{ row.chunkId }}</code>
                <details v-for="{ model } in results" :key="model.id">
                  <summary>{{ modelName(model) }} · 完整 document 编码输入</summary>
                  <pre>{{
                    row.indexContent == null
                      ? '入模正文未提供'
                      : (model.documentPrefix || '') + row.indexContent
                  }}</pre>
                </details>
              </details>
            </td>
            <td v-for="{ model, result } in results" :key="model.id">
              <template v-if="row.scores[model.id]"
                ><span class="ev-score"
                  >{{
                    row.scores[model.id].acceptedOnly
                      ? `阈值后 #${row.scores[model.id].rank ?? '—'}`
                      : row.scores[model.id].judgedOnly
                      ? `补充证据 · 精确 #${row.scores[model.id].rank ?? '—'}`
                      : `#${row.scores[model.id].rank ?? '—'}`
                  }}
                  · {{ formatScore(row.scores[model.id].score) }}</span
                >
                <p v-if="isNumber(run.thresholds?.[model.id])" class="ev-muted">
                  {{ retained(result, row.scores[model.id]) ? '阈值后返回' : '阈值后未返回' }}
                </p>
                <p v-if="ties(queryFor(result), row.scores[model.id])" class="ev-pill">同分候选</p></template
              ><span v-else class="ev-muted">{{ missingText(result) }}</span>
            </td>
          </tr>
          <tr v-if="!visibleRows.length">
            <td :colspan="results.length + 1" class="ev-empty">尚无计算结果。请查看模型进度或错误原因。</td>
          </tr>
        </tbody>
      </table>
    </div>
    <div v-else class="ev-fields">
      <section v-for="{ model, result } in results" :key="model.id" class="ev-panel ev-stack">
        <h3>{{ modelName(model) }}</h3>
        <p v-if="!queryFor(result)?.hits?.length" class="ev-muted">{{ missingText(result) }}</p>
        <article
          v-for="hit in (queryFor(result)?.hits || []).filter((h) => h.rank <= visibleCount)"
          :key="hit.chunkId"
        >
          <span class="ev-score">#{{ hit.rank }} · {{ formatScore(hit.score) }}</span>
          <span class="ev-pill">{{ labelText(labelFor(hit)) }}</span>
          <details>
            <summary>{{ hit.fileName }} · {{ hit.content?.slice(0, 100) || hit.chunkId }}</summary>
            <p>{{ hit.content }}</p>
            <pre>{{ hit.indexContent ?? '入模文本未提供' }}</pre>
            <code>{{ hit.chunkId }}</code>
          </details>
        </article>
      </section>
    </div>
    <details v-if="run.retrievalMode === 'PRODUCTION'">
      <summary>精确检索与交付链路 · 本题对照</summary>
      <section v-for="{ model, result } in results" :key="model.id" class="ev-stack" style="margin-top: 14px">
        <strong>{{ modelName(model) }}</strong>
        <div class="ev-table-wrap">
          <table class="ev-table">
            <thead><tr><th>指标</th><th>精确参考</th><th>实际链路</th></tr></thead>
            <tbody><tr v-for="[key, title] in referenceFields" :key="key">
              <td>{{ title }}</td>
              <td>{{ formatScore(queryFor(result)?.exactReferenceMetrics?.[key]) }}</td>
              <td>{{ formatScore(queryFor(result)?.metrics?.[key]) }}</td>
            </tr></tbody>
          </table>
        </div>
      </section>
    </details>
    <details>
      <summary>本题的编码输入与配置</summary>
      <div v-for="{ model, result } in results" :key="model.id">
        <strong>{{ modelName(model) }} · {{ statusText(queryFor(result)?.status) }}</strong>
        <pre>{{ (model.queryPrefix || '') + question.query }}</pre>
        <p>Document 前缀（与各块入模正文拼接）：</p>
        <pre>{{ model.documentPrefix || '（空）' }}</pre>
      </div>
    </details>
  </section>
</template>
