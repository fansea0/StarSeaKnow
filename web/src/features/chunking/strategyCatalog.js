import { normalizeStrategyCode } from './normalization'

export const placeholderStrategies = Object.freeze([
  Object.freeze({ code: 'PARENT_CHILD', title: '父子分块', scope: 'GLOBAL', disabled: true, reason: '暂未开放' }),
])

const presentation = {
  GENERAL: {
    title: '通用分块',
    description: '按分隔符和字符上限快速、确定地拆分可提取文档。',
  },
  PARENT_CHILD: {
    title: '父子分块',
    description: '对子块建立向量，命中后返回完整父块作为回答上下文。',
  },
  MARKDOWN_OPTIMIZED: {
    title: 'MD 自适应分块',
    description: '按 Markdown 标题结构生成可调整的语义分块。',
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
    disabled: strategy.available !== true,
    reason: strategy.available === true ? '' : String(strategy.reason || '').trim() || '当前文件不可用',
  }
}

export function mergeStrategies(fileType, backendStrategies) {
  const strategies = Array.isArray(backendStrategies) ? backendStrategies : []
  const seenCodes = new Set()

  const backendCatalog = strategies
    .filter((strategy) => {
      const code = normalizeStrategyCode(strategy?.code)
      if (!code || seenCodes.has(code)) {
        return false
      }

      seenCodes.add(code)
      return true
    })
    .map(strategy => enrichDescriptor(strategy, normalizeStrategyCode(strategy.code)))

  const rank = code => ({ GENERAL: 0, PARENT_CHILD: 1, MARKDOWN_OPTIMIZED: 2 }[code] ?? 3)
  const placeholders = placeholderStrategies
    .filter(strategy => !seenCodes.has(strategy.code))
    .map(strategy => ({ ...strategy }))
  return [...backendCatalog, ...placeholders]
    .sort((left, right) => rank(left.code) - rank(right.code))
}
