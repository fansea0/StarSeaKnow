export const clone = (value) => JSON.parse(JSON.stringify(value))
export const isNumber = (value) => typeof value === 'number' && Number.isFinite(value)
export const formatScore = (value) => (isNumber(value) ? value.toFixed(4) : '未计算')
export const formatPercent = (value) =>
  isNumber(value) ? `${(value * 100).toFixed(1)}%` : value === null ? '无适用样本' : '未计算'
export const formatMs = (value) => (isNumber(value) ? `${value.toFixed(1)} ms` : '未计算')
export const errorText = (error) => error?.response?.data?.msg || error?.message || '请求失败，请重试。'
export const isActiveRun = (run) => ['QUEUED', 'RUNNING'].includes(run?.status)
export const statusText = (status) =>
  ({
    QUEUED: '排队中',
    PENDING: '待运行',
    RUNNING: '运行中',
    COMPLETED: '已完成',
    PARTIAL: '部分完成',
    FAILED: '失败',
    CANCELLED: '已取消',
  })[status] ||
  status ||
  '未运行'
export const phaseText = (phase) =>
  ({ QUICK: '快速诊断', CALIBRATION: '阈值校准', ACCEPTANCE: '冻结验收', DELIVERY: '交付链路复测' })[phase] ||
  phase
export const labelText = (value) =>
  ({ 0: '0 · 无关', 1: '1 · 部分相关', 2: '2 · 支持答案' })[value] || '未标注 · 未知'
export const modelName = (model) =>
  model?.id === 'current' ? '当前配置重算基线' : model?.displayName || model?.modelId || '未知模型'
export const snapshotDescription = (snapshot) =>
  snapshot
    ? `${snapshot.scope === 'SELECTED' ? '候选集内对比' : '全部有效分块'} · ${new Set((snapshot.chunks || []).map((c) => c.fileId)).size} 份文档 · ${snapshot.chunks?.length ?? '未知'} 块`
    : '尚未冻结语料'
export function newQuestion(base = {}) {
  const id = crypto.randomUUID()
  return {
    id,
    query: '',
    intentGroup: id,
    category: '',
    split: 'CALIBRATION',
    answerable: true,
    reviewed: false,
    labels: {},
    hardNegativeIds: [],
    ...base,
  }
}
export function validateQuestions(questions) {
  if (!questions.length) return '请至少添加一个问题。'
  const groups = new Map(),
    ids = new Set()
  for (const question of questions) {
    if (!question.query?.trim() || !question.intentGroup?.trim()) return '请填写问题和意图组。'
    if (ids.has(question.id)) return '问题 ID 重复，请重新添加问题。'
    ids.add(question.id)
    if (groups.has(question.intentGroup) && groups.get(question.intentGroup) !== question.split)
      return '同一意图组不能跨校准集和验收集。'
    groups.set(question.intentGroup, question.split)
    if ((question.hardNegativeIds || []).some((id) => question.labels?.[id] !== 0))
      return '困难负例必须明确标为 0。'
    if (question.reviewed && question.answerable && !Object.values(question.labels || {}).includes(2))
      return '已审核的可回答问题至少需要一个支持答案的标签 2。'
    if (!question.answerable && Object.values(question.labels || {}).includes(2))
      return '无答案问题不能同时标记支持答案的分块。'
  }
  return ''
}
export function alignChunks(modelResults, questionId) {
  const rows = new Map()
  for (const result of modelResults || []) {
    const query = result.queries?.find((q) => q.questionId === questionId)
    for (const hit of [
      ...(query?.judgedScores || []).map((h) => ({ ...h, judgedOnly: true })),
      ...(query?.acceptedHits || []).map((h) => ({ ...h, acceptedOnly: true, judgedOnly: false })),
      ...(query?.hits || []).map((h) => ({ ...h, judgedOnly: false })),
    ]) {
      const row = rows.get(hit.chunkId) || { ...hit, scores: {} }
      row.scores[result.modelId] = hit
      rows.set(hit.chunkId, row)
    }
  }
  return [...rows.values()]
}
export function questionGap(query, reviewed) {
  if (!reviewed) return null
  if (query?.metrics && Object.hasOwn(query.metrics, 'gap'))
    return isNumber(query.metrics.gap) ? query.metrics.gap : null
  const hits = [...(query?.judgedScores || []), ...(query?.hits || [])]
  const positives = hits.filter((h) => h.label === 2 && isNumber(h.score)).map((h) => h.score)
  const negatives = hits.filter((h) => h.label === 0 && isNumber(h.score)).map((h) => h.score)
  return positives.length && negatives.length ? Math.max(...positives) - Math.max(...negatives) : null
}

export function modelSignature(model) {
  return JSON.stringify(
    ['id', 'revision', 'baseUrl', 'modelName', 'queryPrefix', 'documentPrefix', 'options', 'keepAlive'].map(
      (key) => model?.[key] ?? null,
    ),
  )
}
