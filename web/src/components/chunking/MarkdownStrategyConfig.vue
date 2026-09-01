<template>
  <section class="markdown-config" aria-labelledby="markdown-config-title">
    <div class="markdown-config__heading">
      <h3 id="markdown-config-title">正文 Token 范围</h3>
      <span>按语义边界靠近推荐值</span>
    </div>

    <div class="token-fields">
      <label data-testid="min-tokens">
        <span>最小 Token</span>
        <el-input-number v-model="values.minTokens" :min="1" :max="9999" :disabled="disabled" controls-position="right" />
      </label>
      <label data-testid="target-tokens">
        <span>推荐 Token</span>
        <el-input-number v-model="values.targetTokens" :min="1" :max="9999" :disabled="disabled" controls-position="right" />
      </label>
      <label data-testid="max-tokens">
        <span>最大 Token</span>
        <el-input-number v-model="values.maxTokens" :min="1" :max="9999" :disabled="disabled" controls-position="right" />
      </label>
    </div>

    <p class="token-rule">最小 ≤ 推荐 ≤ 最大 ≤ 512</p>
    <p v-if="validationMessage" class="config-error" role="alert">{{ validationMessage }}</p>
  </section>
</template>

<script setup>
import { computed, reactive, watch } from 'vue'

const props = defineProps({
  initialValues: { type: Object, default: () => ({ minTokens: 100, targetTokens: 400, maxTokens: 512 }) },
  disabled: { type: Boolean, default: false },
})

const emit = defineEmits(['config-change', 'validity-change'])

const values = reactive({
  minTokens: 100,
  targetTokens: 400,
  maxTokens: 512,
})

const validationMessage = computed(() => {
  const { minTokens, targetTokens, maxTokens } = values
  if (![minTokens, targetTokens, maxTokens].every(value => Number.isInteger(value) && value > 0)) {
    return 'Token 数量必须是大于 0 的整数'
  }
  if (maxTokens > 512) return '最大 Token 不能超过 512'
  if (minTokens > targetTokens) return '最小 Token 不能大于推荐 Token'
  if (targetTokens > maxTokens) return '推荐 Token 不能大于最大 Token'
  return ''
})

watch(
  () => props.initialValues,
  initialValues => {
    Object.assign(values, initialValues)
  },
  { immediate: true, deep: true },
)

watch(
  values,
  () => {
    const valid = !validationMessage.value
    emit('validity-change', valid)
    if (valid) emit('config-change', { ...values })
  },
  { immediate: true },
)
</script>

<style scoped>
.markdown-config {
  margin-top: 14px;
  padding-top: 16px;
  border-top: 1px solid color-mix(in srgb, var(--sea-muted) 18%, var(--sea-paper));
}

.markdown-config__heading {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 12px;
}

.markdown-config__heading h3 {
  margin: 0;
  color: var(--sea-deep);
  font-size: 14px;
}

.markdown-config__heading span,
.token-rule {
  color: var(--sea-muted);
  font-size: 12px;
}

.token-fields {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 10px;
}

.token-fields label {
  display: grid;
  gap: 6px;
  color: var(--sea-muted);
  font-size: 12px;
  font-weight: 600;
}

.token-fields :deep(.el-input-number) { width: 100%; }
.token-rule { margin: 10px 0 0; }

.config-error {
  margin: 8px 0 0;
  color: var(--sea-danger);
  font-size: 12px;
  line-height: 1.5;
}

@media (max-width: 520px) {
  .token-fields { grid-template-columns: 1fr; }
  .markdown-config__heading { align-items: flex-start; flex-direction: column; }
}
</style>
