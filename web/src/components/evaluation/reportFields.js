import { formatMs, formatPercent, formatScore } from './evaluationState'

export const metricFields = [
  { key: 'hit1', label: 'Hit@1', filter: 'miss1' },
  { key: 'hit5', label: 'Hit@5', filter: 'miss5' },
  { key: 'mrr10', label: 'MRR@10', filter: 'all' },
  { key: 'ndcg5', label: 'nDCG@5', filter: 'all' },
  { key: 'hardNegativeWinRate', label: '困难负例胜出率', filter: 'negative' },
  { key: 'variantGroupHitRate', label: '变体组全部命中率', filter: 'variant' },
  { key: 'noAnswerFalsePositiveRate', label: '无答案误召回率', filter: 'noanswer' },
  { key: 'evidenceRetentionRate', label: '正确证据保留率', filter: 'all' },
  { key: 'p95Ms', label: 'p95', filter: 'all' },
]
export function metricName(key) {
  return (
    metricFields.find((field) => field.key === key)?.label ||
    {
      hitK: 'Hit@K',
      answerableEmptyRate: '有答案但返回空',
      failureRate: '失败率',
      p50Ms: 'p50',
      latencyMs: '平均延迟',
    }[key] ||
    key
  )
}
export function formatMetric(key, value) {
  if (['p50Ms', 'p95Ms', 'latencyMs'].includes(key)) return formatMs(value)
  if (['mrr10', 'ndcg5'].includes(key)) return value === null ? '无适用样本' : formatScore(value)
  return formatPercent(value)
}
export const requirementFields = [
  { key: 'minQuestions', label: '最少问题数', min: 1, step: 1 },
  { key: 'minUnanswerable', label: '最少无答案问题数', min: 1, step: 1 },
  { key: 'hit5Min', label: 'Hit@5 下限', min: 0, max: 1, step: 0.01 },
  { key: 'evidenceRetentionMin', label: '正确证据保留率下限', min: 0, max: 1, step: 0.01 },
  { key: 'noAnswerFalsePositiveMax', label: '无答案误召回率上限', min: 0, max: 1, step: 0.01 },
  { key: 'p95MaxMs', label: 'p95 预算（毫秒）', min: 1, step: 1 },
]

export const optionalRequirementFields = [
  { key: 'minHardNegativeQuestions', label: '最少困难负例问题数', min: 1, step: 1 },
  { key: 'minVariantGroups', label: '最少同义问法意图组数', min: 1, step: 1 },
  { key: 'hardNegativeWinMin', label: '困难负例胜出率下限', min: 0, max: 1, step: 0.01 },
  { key: 'variantGroupHitMin', label: '变体组全部命中率下限', min: 0, max: 1, step: 0.01 },
]
