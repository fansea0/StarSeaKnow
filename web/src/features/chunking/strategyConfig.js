import { normalizeStrategyCode } from './normalization'

const markdownDefaults = Object.freeze({ minTokens: 100, targetTokens: 400, maxTokens: 512 })
const parentChildDefaults = Object.freeze({
  parentMode: 'PARAGRAPH',
  parentMaxTokens: 1024,
  childMaxTokens: 256,
  childOverlapTokens: 32,
})

function strategyCode(strategy) {
  return normalizeStrategyCode(typeof strategy === 'string' ? strategy : strategy?.code)
}

function descriptorDefaults(descriptor, defaults) {
  const fields = Array.isArray(descriptor?.configFields) ? descriptor.configFields : []
  return Object.entries(defaults).reduce((config, [key, fallback]) => {
    const value = fields.find(field => field?.key === key)?.defaultValue
    config[key] = typeof fallback === 'number'
      ? (Number.isInteger(value) ? value : fallback)
      : (typeof value === 'string' ? value : fallback)
    return config
  }, {})
}

function isMarkdownConfig(snapshot) {
  const { minTokens, targetTokens, maxTokens } = snapshot || {}
  return [minTokens, targetTokens, maxTokens].every(value => Number.isInteger(value) && value > 0)
    && minTokens <= targetTokens
    && targetTokens <= maxTokens
    && maxTokens <= 512
}

function isParentChildConfig(snapshot) {
  const { parentMode, parentMaxTokens, childMaxTokens, childOverlapTokens } = snapshot || {}
  return ['PARAGRAPH', 'FULL_DOCUMENT'].includes(parentMode)
    && Number.isInteger(parentMaxTokens) && parentMaxTokens >= 128 && parentMaxTokens <= 4096
    && Number.isInteger(childMaxTokens) && childMaxTokens >= 32 && childMaxTokens <= 512
    && Number.isInteger(childOverlapTokens) && childOverlapTokens >= 0 && childOverlapTokens <= 128
    && childOverlapTokens < childMaxTokens
    && (parentMode !== 'PARAGRAPH' || parentMaxTokens >= childMaxTokens)
}

export function defaultConfigFor(strategy) {
  const code = strategyCode(strategy)
  if (code === 'PARENT_CHILD') return descriptorDefaults(strategy, parentChildDefaults)
  if (code === 'MARKDOWN_OPTIMIZED') return descriptorDefaults(strategy, markdownDefaults)
  return {}
}

export function normalizePolicySnapshot(code, snapshot, descriptor) {
  const normalizedCode = normalizeStrategyCode(code)
  if (normalizedCode !== strategyCode(descriptor)) return defaultConfigFor(descriptor)

  if (normalizedCode === 'PARENT_CHILD') {
    return isParentChildConfig(snapshot)
      ? {
          parentMode: snapshot.parentMode,
          parentMaxTokens: snapshot.parentMaxTokens,
          childMaxTokens: snapshot.childMaxTokens,
          childOverlapTokens: snapshot.childOverlapTokens,
        }
      : defaultConfigFor(descriptor)
  }

  if (normalizedCode === 'MARKDOWN_OPTIMIZED') {
    return isMarkdownConfig(snapshot)
      ? { minTokens: snapshot.minTokens, targetTokens: snapshot.targetTokens, maxTokens: snapshot.maxTokens }
      : defaultConfigFor(descriptor)
  }

  return defaultConfigFor(descriptor)
}
