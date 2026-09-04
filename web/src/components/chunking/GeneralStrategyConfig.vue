<template>
  <section class="general-config" aria-labelledby="general-config-title">
    <div class="general-config__heading">
      <div>
        <h3 id="general-config-title">通用分块规则</h3>
        <span>分隔符与字符预算由服务端按当前文件执行</span>
      </div>
    </div>

    <div class="general-fields">
      <label class="general-field general-field--wide" data-testid="general-delimiter">
        <span>分隔符</span>
        <el-input
          v-model="delimiterDisplay"
          class="delimiter-input mono"
          :disabled="disabled"
          placeholder="例如：\\n"
          aria-describedby="delimiter-help delimiter-error"
          @input="touch('delimiter')"
        />
        <small id="delimiter-help">使用转义记法输入换行 <b class="mono">\n</b>、制表符 <b class="mono">\t</b> 或反斜杠 <b class="mono">\\</b></small>
        <small v-if="localErrors.delimiter" id="delimiter-error" class="config-error" role="alert">{{ localErrors.delimiter }}</small>
        <small v-if="serverFieldErrors.delimiter" data-testid="field-error-delimiter" class="config-error" role="alert">{{ serverFieldErrors.delimiter }}</small>
      </label>

      <fieldset class="delimiter-mode">
        <legend>匹配方式</legend>
        <button
          type="button"
          data-testid="delimiter-mode-literal"
          :class="{ 'is-active': values.delimiterMode === 'LITERAL' }"
          :aria-pressed="values.delimiterMode === 'LITERAL'"
          :disabled="disabled"
          @click="setDelimiterMode('LITERAL')"
        >普通文本</button>
        <button
          type="button"
          data-testid="delimiter-mode-regex"
          :class="{ 'is-active': values.delimiterMode === 'REGEX' }"
          :aria-pressed="values.delimiterMode === 'REGEX'"
          :disabled="disabled"
          @click="setDelimiterMode('REGEX')"
        >正则表达式</button>
      </fieldset>
      <p v-if="regexAdvisory" data-testid="regex-advisory" class="config-advisory">该表达式可能产生零宽匹配，提交后以服务端 RE2/J 校验结果为准。</p>

      <label class="general-field" data-testid="general-max">
        <span>每块最大字符数</span>
        <el-input-number
          v-model="values.maxCharacters"
          :min="limits.maxCharacters.min"
          :max="limits.maxCharacters.max"
          controls-position="right"
          :disabled="disabled"
          @change="touch('maxCharacters')"
        />
        <small>{{ limits.maxCharacters.min }}–{{ limits.maxCharacters.max }} 字符</small>
        <small v-if="localErrors.maxCharacters" class="config-error" role="alert">{{ localErrors.maxCharacters }}</small>
        <small v-if="serverFieldErrors.maxCharacters" class="config-error" role="alert">{{ serverFieldErrors.maxCharacters }}</small>
      </label>

      <div class="general-field overlap-field">
        <span>补充上文</span>
        <div class="switch-line">
          <el-switch v-model="context.enabled" aria-label="启用通用分块补充上文" :disabled="disabled" @change="touch('enabled')" />
          <span>{{ context.enabled ? '已启用' : '已停用' }}</span>
        </div>
        <label data-testid="general-overlap">
          <span class="sr-only">补充上文字符上限</span>
          <el-input-number
            v-model="context.limit"
            :min="0"
            :max="1000"
            controls-position="right"
            :disabled="disabled || !context.enabled"
            @change="touch('limit')"
          />
        </label>
        <small>0–1000 字符；0 表示停用</small>
        <small v-if="localErrors.limit" class="config-error" role="alert">{{ localErrors.limit }}</small>
        <small v-if="serverLimitError" class="config-error" role="alert">{{ serverLimitError }}</small>
      </div>
    </div>

    <section class="budget" aria-labelledby="budget-title">
      <div class="budget__heading">
        <div>
          <strong id="budget-title">长度预算</strong>
          <span>建议补充量为最大字符数的 15%</span>
        </div>
        <el-button link type="primary" data-testid="apply-overlap-suggestion" :disabled="disabled" @click="applyRecommendation">应用建议值</el-button>
      </div>
      <div
        class="budget-rail"
        data-testid="length-budget-rail"
        role="img"
        :aria-label="budgetAriaLabel"
      >
        <span class="budget-rail__track" aria-hidden="true">
          <i class="budget-rail__configured" :style="{ width: `${configuredPercent}%` }" />
          <i class="budget-rail__recommended" :style="{ left: `${recommendedPercent}%` }" />
        </span>
        <span class="budget-rail__labels mono">
          <b>补充 {{ normalizedContext.limit }} 字符</b>
          <b data-testid="recommended-overlap">建议 {{ recommendedOverlap }} 字符</b>
          <b>最大 {{ normalizedMax }} 字符</b>
        </span>
      </div>
    </section>

    <fieldset class="preprocessing">
      <legend>预处理</legend>
      <el-checkbox v-model="values.collapseWhitespace" :disabled="disabled" @change="touch('collapseWhitespace')">合并连续空白</el-checkbox>
      <el-checkbox v-model="values.removeUrls" :disabled="disabled" @change="touch('removeUrls')">移除 URL</el-checkbox>
      <el-checkbox v-model="values.removeEmails" :disabled="disabled" @change="touch('removeEmails')">移除邮箱地址</el-checkbox>
    </fieldset>
  </section>
</template>

<script setup>
import { computed, reactive, ref, watch } from 'vue'
import { countUnicodeCodePoints, decodeDelimiter, encodeDelimiter } from '../../features/chunking/delimiterCodec'

const props = defineProps({
  configFields: { type: Array, default: () => [] },
  initialValues: { type: Object, default: () => ({}) },
  initialContextConfig: { type: Object, default: () => ({}) },
  serverFieldErrors: { type: Object, default: () => ({}) },
  disabled: { type: Boolean, default: false },
})

const emit = defineEmits(['config-change', 'context-change', 'validity-change', 'field-change'])
const values = reactive({ delimiterMode: 'LITERAL', maxCharacters: 500, collapseWhitespace: true, removeUrls: false, removeEmails: false })
const context = reactive({ enabled: true, limit: 40 })
const delimiterDisplay = ref('\\n')
let hydrating = false
let hydrationSignature = ''

const field = key => props.configFields.find(item => item?.key === key)
const fieldDefault = (key, fallback) => field(key)?.defaultValue ?? fallback
const limits = computed(() => ({
  maxCharacters: { min: Number(field('maxCharacters')?.min) || 64, max: Number(field('maxCharacters')?.max) || 4000 },
}))
const actualDelimiter = computed(() => decodeDelimiter(delimiterDisplay.value))
const normalizedMax = computed(() => Number.isInteger(values.maxCharacters) ? values.maxCharacters : 0)
const recommendedOverlap = computed(() => Math.round(normalizedMax.value * 0.15))
const normalizedContext = computed(() => ({
  enabled: Boolean(context.enabled) && Number(context.limit) > 0,
  limit: Number.isInteger(context.limit) ? context.limit : 0,
}))
const configuredPercent = computed(() => Math.min(100, Math.max(0, normalizedContext.value.limit / Math.max(1, normalizedMax.value) * 100)))
const recommendedPercent = computed(() => Math.min(100, Math.max(0, recommendedOverlap.value / Math.max(1, normalizedMax.value) * 100)))
const budgetAriaLabel = computed(() => `最大 ${normalizedMax.value} 字符；已配置补充 ${normalizedContext.value.limit} 字符；建议 ${recommendedOverlap.value} 字符`)
const regexAdvisory = computed(() => values.delimiterMode === 'REGEX' && /(^\^$|^\$$|\\b|\(\?[=!<]|[?*])/.test(actualDelimiter.value))
const localErrors = computed(() => {
  const errors = {}
  const length = countUnicodeCodePoints(actualDelimiter.value)
  if (!length) errors.delimiter = '分隔符不能为空'
  else if (length > delimiterMaximum.value) errors.delimiter = `分隔符不能超过 ${delimiterMaximum.value} 个 Unicode 字符`
  if (!Number.isInteger(values.maxCharacters) || values.maxCharacters < limits.value.maxCharacters.min || values.maxCharacters > limits.value.maxCharacters.max) {
    errors.maxCharacters = `最大字符数必须是 ${limits.value.maxCharacters.min}–${limits.value.maxCharacters.max} 的整数`
  }
  if (!Number.isInteger(context.limit) || context.limit < 0 || context.limit > 1000) errors.limit = '补充上限必须是 0–1000 的整数'
  else if (context.enabled && context.limit <= 0) errors.limit = '启用补充上文时，上限必须大于 0'
  else if (context.enabled && context.limit + 5 >= values.maxCharacters) errors.limit = '补充上限加 5 必须小于最大字符数'
  return errors
})
const serverLimitError = computed(() => props.serverFieldErrors.limit || props.serverFieldErrors['contextConfig.limit'] || '')
const delimiterMaximum = computed(() => {
  const maximum = Number(field('delimiter')?.max)
  return Number.isInteger(maximum) && maximum > 0 ? maximum : 256
})
const valid = computed(() => Object.keys(localErrors.value).length === 0 && Object.keys(props.serverFieldErrors).length === 0)

function hydrate() {
  const signature = JSON.stringify([props.configFields, props.initialValues, props.initialContextConfig])
  if (signature === hydrationSignature) return
  hydrationSignature = signature
  hydrating = true
  const incoming = props.initialValues || {}
  const delimiter = incoming.delimiter ?? fieldDefault('delimiter', '\n')
  delimiterDisplay.value = encodeDelimiter(delimiter)
  values.delimiterMode = incoming.delimiterMode ?? fieldDefault('delimiterMode', 'LITERAL')
  values.maxCharacters = incoming.maxCharacters ?? fieldDefault('maxCharacters', 500)
  values.collapseWhitespace = incoming.collapseWhitespace ?? fieldDefault('collapseWhitespace', true)
  values.removeUrls = incoming.removeUrls ?? fieldDefault('removeUrls', false)
  values.removeEmails = incoming.removeEmails ?? fieldDefault('removeEmails', false)
  const initialContext = props.initialContextConfig || {}
  context.limit = Number.isInteger(initialContext.limit) ? initialContext.limit : 40
  context.enabled = Boolean(initialContext.enabled ?? true) && context.limit > 0
  hydrating = false
  publish()
}

function publish() {
  if (hydrating) return
  emit('validity-change', valid.value)
  emit('config-change', {
    delimiter: actualDelimiter.value,
    delimiterMode: values.delimiterMode,
    maxCharacters: values.maxCharacters,
    collapseWhitespace: Boolean(values.collapseWhitespace),
    removeUrls: Boolean(values.removeUrls),
    removeEmails: Boolean(values.removeEmails),
  })
  emit('context-change', normalizedContext.value)
}

function touch(name) {
  if (name === 'limit' && context.limit === 0) context.enabled = false
  emit('field-change', name)
}

function setDelimiterMode(mode) {
  values.delimiterMode = mode
  touch('delimiterMode')
}

function applyRecommendation() {
  context.limit = Math.min(1000, Math.max(0, recommendedOverlap.value))
  context.enabled = context.limit > 0
  touch('limit')
}

watch([() => props.configFields, () => props.initialValues, () => props.initialContextConfig], hydrate, { immediate: true, deep: true })
watch([delimiterDisplay, values, context], publish, { deep: true })
watch(() => props.serverFieldErrors, publish, { deep: true })
</script>

<style scoped>
.general-config { margin-top: 14px; padding-top: 16px; border-top: 1px solid color-mix(in srgb, var(--sea-muted) 18%, var(--sea-paper)); }
.general-config__heading { margin-bottom: 14px; }
.general-config__heading h3 { margin: 0 0 3px; color: var(--sea-deep); font-size: 14px; }
.general-config__heading span, .general-field small { color: var(--sea-muted); font-size: 11px; line-height: 1.5; }
.general-fields { display: grid; grid-template-columns: 1fr 1fr; gap: 14px 12px; }
.general-field { display: grid; align-content: start; gap: 6px; color: var(--sea-muted); font-size: 12px; font-weight: 600; }
.general-field--wide { grid-column: 1 / -1; }
.general-field :deep(.el-input-number) { width: 100%; }
.delimiter-input :deep(input) { font-family: 'JetBrains Mono', monospace; }
.delimiter-mode, .preprocessing { margin: 0; padding: 0; border: 0; }
.delimiter-mode legend, .preprocessing legend { margin-bottom: 6px; color: var(--sea-muted); font-size: 12px; font-weight: 600; }
.delimiter-mode { display: grid; grid-template-columns: 1fr 1fr; align-content: start; }
.delimiter-mode legend { grid-column: 1 / -1; }
.delimiter-mode button { min-height: 40px; border: 1px solid #b9ccd5; background: var(--sea-paper); color: var(--sea-muted); font: inherit; font-size: 12px; }
.delimiter-mode button:first-of-type { border-radius: 7px 0 0 7px; }
.delimiter-mode button:last-of-type { margin-left: -1px; border-radius: 0 7px 7px 0; }
.delimiter-mode button.is-active { position: relative; border-color: var(--sea-signal); background: color-mix(in srgb, var(--sea-signal) 7%, var(--sea-paper)); color: var(--sea-deep); font-weight: 600; }
.config-error { color: var(--sea-danger) !important; font-weight: 500; }
.config-advisory { grid-column: 1 / -1; margin: -6px 0 0; color: var(--sea-muted); font-size: 11px; line-height: 1.5; }
.switch-line { display: flex; align-items: center; gap: 8px; min-height: 40px; font-weight: 500; }
.budget { margin-top: 16px; padding: 13px; border: 1px solid color-mix(in srgb, var(--sea-signal) 18%, var(--sea-paper)); border-radius: 8px; background: color-mix(in srgb, var(--sea-mist) 62%, var(--sea-paper)); }
.budget__heading { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
.budget__heading div { display: grid; gap: 2px; }
.budget__heading strong { color: var(--sea-deep); font-size: 12px; }
.budget__heading span { color: var(--sea-muted); font-size: 10.5px; }
.budget-rail { display: grid; gap: 7px; margin-top: 10px; }
.budget-rail__track { position: relative; height: 7px; overflow: visible; border-radius: 999px; background: color-mix(in srgb, var(--sea-muted) 18%, var(--sea-paper)); }
.budget-rail__configured { display: block; height: 100%; border-radius: inherit; background: var(--sea-signal); }
.budget-rail__recommended { position: absolute; top: -3px; width: 2px; height: 13px; background: var(--sea-deep); }
.budget-rail__labels { display: flex; justify-content: space-between; gap: 8px; color: var(--sea-muted); font-size: 9.5px; font-weight: 500; }
.preprocessing { display: flex; flex-wrap: wrap; gap: 4px 14px; margin-top: 16px; }
.preprocessing legend { width: 100%; }
.sr-only { position: absolute; width: 1px; height: 1px; overflow: hidden; clip: rect(0, 0, 0, 0); white-space: nowrap; }
@media (max-width: 520px) { .general-fields { grid-template-columns: 1fr; } .general-field--wide, .config-advisory { grid-column: 1; } .budget__heading { align-items: flex-start; flex-direction: column; } .budget-rail__labels { align-items: flex-start; flex-direction: column; } }
@media (prefers-reduced-motion: reduce) { .budget-rail__configured, .budget-rail__recommended { transition: none; } }
</style>
