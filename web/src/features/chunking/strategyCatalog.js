export const placeholderStrategies = Object.freeze([
  { code: 'GENERAL', title: '通用', scope: 'GLOBAL', disabled: true, reason: '暂未开放' },
  { code: 'PARENT_CHILD', title: '父子分块', scope: 'GLOBAL', disabled: true, reason: '暂未开放' },
])

export function mergeStrategies(fileType, backendStrategies) {
  const normalized = String(fileType || '').toLowerCase()
  const strategies = Array.isArray(backendStrategies) ? backendStrategies : []

  return [
    ...placeholderStrategies,
    ...strategies
      .filter(item => item.supportedFileTypes?.includes(normalized))
      .map(item => ({ ...item, disabled: false })),
  ]
}
