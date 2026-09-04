import { normalizeFileType, normalizeStrategyCode } from './normalization'

const localOnlyStrategyCodes = new Set(['GENERAL'])

export const placeholderStrategies = Object.freeze([
  Object.freeze({ code: 'GENERAL', title: '通用', scope: 'GLOBAL', disabled: true, reason: '暂未开放' }),
])

const presentation = {
  MARKDOWN_OPTIMIZED: {
    title: 'MD 自适应分块',
    description: '按 Markdown 标题结构生成可调整的语义分块。',
  },
  PARENT_CHILD: {
    title: '父子分块',
    description: '子块精准召回，父块提供完整回答上下文。',
  },
}

function enrichDescriptor(strategy, code) {
  const fallbackTitle = code.replace(/_/g, ' ').trim() || '未命名策略'
  const metadata = presentation[code]

  return {
    ...strategy,
    code,
    title: metadata?.title || String(strategy.title || '').trim() || fallbackTitle,
    description: metadata?.description || String(strategy.description || '').trim() || `策略：${fallbackTitle}`,
    disabled: false,
  }
}

export function mergeStrategies(fileType, backendStrategies) {
  const normalized = normalizeFileType(fileType)
  const strategies = Array.isArray(backendStrategies) ? backendStrategies : []
  const seenCodes = new Set()

  const availableStrategies = strategies
    .filter((strategy) => {
      const code = normalizeStrategyCode(strategy?.code)
      const supportedFileTypes = Array.isArray(strategy?.supportedFileTypes)
        ? strategy.supportedFileTypes.map(normalizeFileType)
        : []

      if (!code || localOnlyStrategyCodes.has(code) || seenCodes.has(code) || !supportedFileTypes.includes(normalized)) {
        return false
      }

      seenCodes.add(code)
      return true
    })
    .map(strategy => enrichDescriptor(strategy, normalizeStrategyCode(strategy.code)))

  return [
    ...placeholderStrategies.map(strategy => ({ ...strategy })),
    ...availableStrategies,
  ]
}
