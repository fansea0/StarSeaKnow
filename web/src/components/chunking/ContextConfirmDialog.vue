<template>
  <el-dialog
    :model-value="modelValue"
    title="确认建立索引"
    width="min(92vw, 480px)"
    :close-on-click-modal="false"
    @update:model-value="$emit('update:modelValue', $event)"
  >
    <p class="dialog-intro">确认当前逐块审核结果后，将开始为这份文件建立向量索引。</p>

    <dl class="confirm-summary" aria-label="分块补充上文统计">
      <div data-testid="confirm-total-count">
        <dt>总块数</dt>
        <dd>{{ totalCount }}</dd>
      </div>
      <div data-testid="confirm-enabled-count">
        <dt>已开启补充上文</dt>
        <dd>{{ enabledCount }}</dd>
      </div>
      <div data-testid="confirm-generated-count">
        <dt>已生成补充内容</dt>
        <dd>{{ generatedCount }}</dd>
      </div>
    </dl>
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
import { computed } from 'vue'

const props = defineProps({
  modelValue: { type: Boolean, default: false },
  submitting: { type: Boolean, default: false },
  serverError: { type: String, default: '' },
  serverConflict: { type: Boolean, default: false },
  reloading: { type: Boolean, default: false },
  blocked: { type: Boolean, default: false },
  totalCount: { type: Number, default: 0 },
  enabledCount: { type: Number, default: 0 },
  generatedCount: { type: Number, default: 0 },
})

const emit = defineEmits(['update:modelValue', 'confirm', 'reload'])
const displayError = computed(() => props.serverError)

function confirm() {
  if (props.submitting || props.reloading || props.blocked) return
  emit('confirm')
}
</script>

<style scoped>
.dialog-intro { margin: 0 0 18px; color: var(--sea-muted); font-size: 13px; line-height: 1.65; }

.confirm-summary { display: grid; grid-template-columns: repeat(3, 1fr); gap: 10px; margin: 0; }
.confirm-summary div { display: grid; gap: 5px; padding: 13px 10px; border-radius: 8px; background: var(--sea-mist); text-align: center; }
.confirm-summary dt { color: var(--sea-muted); font-size: 11px; }
.confirm-summary dd { margin: 0; color: var(--sea-deep); font-family: 'JetBrains Mono', monospace; font-size: 20px; font-weight: 600; }
.dialog-error { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin: 10px 0 0; color: var(--sea-danger); font-size: 12px; }
</style>
