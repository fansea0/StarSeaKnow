<template>
  <el-dialog
    :model-value="modelValue"
    title="确认建立索引"
    width="min(92vw, 480px)"
    :close-on-click-modal="false"
    @update:model-value="$emit('update:modelValue', $event)"
  >
    <p class="dialog-intro">为本次索引选择是否补充相邻正文。原始分块预览不会因此改变。</p>

    <div class="context-setting">
      <div>
        <strong>上下文补充</strong>
        <span>把上一块结尾附加到索引内容中</span>
      </div>
      <el-switch v-model="overlapEnabled" data-testid="overlap-switch" aria-label="启用上下文补充" />
    </div>

    <label v-if="overlapEnabled" class="token-setting" data-testid="overlap-tokens">
      <span>补充 Token</span>
      <el-input-number v-model="overlapTokens" :min="0" :max="512" controls-position="right" />
      <small>最多 512 Token</small>
    </label>
    <div v-if="displayError" class="dialog-error" role="alert">
      <span>{{ displayError }}</span>
      <el-button
        v-if="serverConflict"
        link
        data-testid="reload-confirm"
        :loading="reloading"
        @click="$emit('reload')"
      >重新加载文件状态</el-button>
    </div>

    <template #footer>
      <el-button :disabled="submitting" @click="$emit('update:modelValue', false)">取消</el-button>
      <el-button
        type="primary"
        data-testid="confirm-vectorization"
        :loading="submitting"
        :disabled="submitting || reloading || blocked"
        @click="confirm"
      >确认并建立索引</el-button>
    </template>
  </el-dialog>
</template>

<script setup>
import { computed, ref, watch } from 'vue'

const props = defineProps({
  modelValue: { type: Boolean, default: false },
  submitting: { type: Boolean, default: false },
  serverError: { type: String, default: '' },
  serverConflict: { type: Boolean, default: false },
  reloading: { type: Boolean, default: false },
  blocked: { type: Boolean, default: false },
})

const emit = defineEmits(['update:modelValue', 'confirm', 'reload'])
const overlapEnabled = ref(false)
const overlapTokens = ref(40)
const errorMessage = ref('')
const displayError = computed(() => props.serverError || errorMessage.value)

watch(
  () => props.modelValue,
  visible => {
    if (!visible) return
    overlapEnabled.value = false
    overlapTokens.value = 40
    errorMessage.value = ''
  },
)

function confirm() {
  if (props.submitting || props.reloading || props.blocked) return
  if (overlapEnabled.value && (!Number.isInteger(overlapTokens.value) || overlapTokens.value < 0 || overlapTokens.value > 512)) {
    errorMessage.value = '补充 Token 必须在 0 到 512 之间。'
    return
  }
  emit('confirm', {
    overlapEnabled: overlapEnabled.value,
    overlapTokens: overlapTokens.value,
  })
}
</script>

<style scoped>
.dialog-intro { margin: 0 0 18px; color: var(--sea-muted); font-size: 13px; line-height: 1.65; }

.context-setting {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 18px;
  padding: 14px;
  border: 1px solid color-mix(in srgb, var(--sea-muted) 20%, var(--sea-paper));
  border-radius: 9px;
  background: color-mix(in srgb, var(--sea-mist) 42%, var(--sea-paper));
}

.context-setting div { display: grid; gap: 3px; }
.context-setting strong { color: var(--sea-deep); font-size: 14px; }
.context-setting span { color: var(--sea-muted); font-size: 12px; }

.token-setting {
  display: grid;
  grid-template-columns: 1fr auto;
  align-items: center;
  gap: 7px 14px;
  margin-top: 14px;
  color: var(--sea-deep);
  font-size: 13px;
  font-weight: 600;
}

.token-setting small { grid-column: 1 / -1; color: var(--sea-muted); font-size: 11px; font-weight: 400; }
.dialog-error { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin: 10px 0 0; color: var(--sea-danger); font-size: 12px; }
</style>
