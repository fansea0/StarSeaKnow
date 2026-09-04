<template>
  <section class="parent-child-config" aria-labelledby="parent-child-config-title">
    <div class="parent-child-config__heading">
      <h3 id="parent-child-config-title">父子分块设置</h3>
      <span>仅对子块建立向量，命中后使用父块回答。</span>
    </div>

    <fieldset class="parent-mode" :disabled="disabled">
      <legend>父块模式</legend>
      <el-radio-group v-model="values.parentMode" aria-label="父块模式">
        <el-radio data-testid="parent-mode-paragraph" value="PARAGRAPH" @click="values.parentMode = 'PARAGRAPH'">按段落组织父块</el-radio>
        <el-radio data-testid="parent-mode-full-document" value="FULL_DOCUMENT" @click="values.parentMode = 'FULL_DOCUMENT'">整篇文档</el-radio>
      </el-radio-group>
    </fieldset>

    <p v-if="values.parentMode === 'FULL_DOCUMENT'" class="config-warning" role="status">
      整篇文档作为回答上下文，单次命中可能带来更高的上下文成本。
    </p>

    <div class="token-fields">
      <label v-if="values.parentMode === 'PARAGRAPH'" data-testid="parent-max-tokens">
        <span>父块最大 Token</span>
        <el-input-number v-model="values.parentMaxTokens" :min="128" :max="4096" :disabled="disabled" controls-position="right" />
      </label>
      <label data-testid="child-max-tokens">
        <span>子块最大 Token</span>
        <el-input-number v-model="values.childMaxTokens" :min="32" :max="512" :disabled="disabled" controls-position="right" />
      </label>
      <label data-testid="child-overlap-tokens">
        <span>子块重叠 Token</span>
        <el-input-number v-model="values.childOverlapTokens" :min="0" :max="128" :disabled="disabled" controls-position="right" />
      </label>
    </div>

    <p class="token-rule">父块 128–4096；子块 32–512；重叠 0–128 且小于子块。</p>
    <p v-if="validationMessage" class="config-error" role="alert">{{ validationMessage }}</p>
  </section>
</template>

<script setup>
import { computed, reactive, watch } from 'vue'

const props = defineProps({
  initialValues: {
    type: Object,
    default: () => ({ parentMode: 'PARAGRAPH', parentMaxTokens: 1024, childMaxTokens: 256, childOverlapTokens: 32 }),
  },
  disabled: { type: Boolean, default: false },
})

const emit = defineEmits(['config-change', 'validity-change'])

const values = reactive({ parentMode: 'PARAGRAPH', parentMaxTokens: 1024, childMaxTokens: 256, childOverlapTokens: 32 })

const validationMessage = computed(() => {
  const { parentMode, parentMaxTokens, childMaxTokens, childOverlapTokens } = values
  if (!['PARAGRAPH', 'FULL_DOCUMENT'].includes(parentMode)) return '父块模式必须为段落或整篇文档'
  if (!Number.isInteger(parentMaxTokens) || parentMaxTokens < 128 || parentMaxTokens > 4096) return '父块最大 Token 必须在 128 到 4096 之间'
  if (!Number.isInteger(childMaxTokens) || childMaxTokens < 32 || childMaxTokens > 512) return '子块最大 Token 必须在 32 到 512 之间'
  if (!Number.isInteger(childOverlapTokens) || childOverlapTokens < 0 || childOverlapTokens > 128) return '子块重叠 Token 必须在 0 到 128 之间'
  if (childOverlapTokens >= childMaxTokens) return '子块重叠 Token 必须小于子块最大 Token'
  if (parentMode === 'PARAGRAPH' && parentMaxTokens < childMaxTokens) return '段落父块最大 Token 不能小于子块最大 Token'
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
    emit('config-change', { ...values })
    emit('validity-change', !validationMessage.value)
  },
  { immediate: true },
)
</script>

<style scoped>
.parent-child-config {
  margin-top: 14px;
  padding-top: 16px;
  border-top: 1px solid color-mix(in srgb, var(--sea-muted) 18%, var(--sea-paper));
}

.parent-child-config__heading { display: grid; gap: 4px; margin-bottom: 12px; }
.parent-child-config__heading h3 { margin: 0; color: var(--sea-deep); font-size: 14px; }
.parent-child-config__heading span,
.token-rule { color: var(--sea-muted); font-size: 12px; line-height: 1.55; }

.parent-mode { display: grid; gap: 7px; min-width: 0; margin: 0 0 12px; padding: 0; border: 0; }
.parent-mode legend,
.token-fields label { color: var(--sea-muted); font-size: 12px; font-weight: 600; }
.parent-mode :deep(.el-radio-group) { display: grid; gap: 7px; }
.parent-mode :deep(.el-radio) { margin-right: 0; }

.token-fields { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 10px; }
.token-fields label { display: grid; gap: 6px; }
.token-fields :deep(.el-input-number) { width: 100%; }
.token-rule { margin: 10px 0 0; }

.config-warning {
  margin: 0 0 12px;
  padding: 10px 12px;
  border: 1px solid color-mix(in srgb, var(--sea-sand) 52%, var(--sea-paper));
  border-radius: 8px;
  background: color-mix(in srgb, var(--sea-sand) 10%, var(--sea-paper));
  color: var(--sea-ink);
  font-size: 12px;
  line-height: 1.55;
}

.config-error { margin: 8px 0 0; color: var(--sea-danger); font-size: 12px; line-height: 1.5; }

@media (max-width: 520px) { .token-fields { grid-template-columns: 1fr; } }
</style>
