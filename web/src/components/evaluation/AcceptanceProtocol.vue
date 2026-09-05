<script setup>
import { requirementFields, optionalRequirementFields } from './reportFields'
const props = defineProps({
  modelValue: { type: Object, required: true },
  questions: { type: Array, default: () => [] },
  disabled: Boolean,
})
const emit = defineEmits(['update:modelValue'])
function setNumber(key, text) {
  const value = { ...props.modelValue }
  if (text === '') delete value[key]
  else value[key] = Number(text)
  emit('update:modelValue', value)
}
function setCritical(id, checked) {
  const ids = new Set(props.modelValue.criticalQuestionIds || [])
  checked ? ids.add(id) : ids.delete(id)
  emit('update:modelValue', { ...props.modelValue, criticalQuestionIds: [...ids] })
}
</script>
<template>
  <fieldset class="ev-stack" :disabled="disabled">
    <legend>验收协议</legend>
    <p class="ev-muted">
      在查看验收结果前确定业务要求。以下门槛随本次运行保存，不预填通用合格线。比例字段为 0–1。
    </p>
    <div class="ev-fields">
      <label v-for="field in requirementFields" :key="field.key"
        >{{ field.label
        }}<input
          type="number"
          :name="field.key"
          :value="modelValue[field.key] ?? ''"
          :min="field.min"
          :max="field.max"
          :step="field.step"
          placeholder="待业务确认"
          @input="setNumber(field.key, $event.target.value)"
      /></label>
    </div>
    <details>
      <summary>关键问题与分类覆盖要求</summary>
      <p class="ev-muted">正式验收至少需覆盖困难负例和同义问法意图组。可设置更高样本要求或额外质量门槛。</p>
      <div class="ev-fields">
        <label v-for="field in optionalRequirementFields" :key="field.key"
          >{{ field.label
          }}<input
            type="number"
            :name="field.key"
            :value="modelValue[field.key] ?? ''"
            :min="field.min"
            :max="field.max"
            :step="field.step"
            placeholder="使用服务端基础覆盖要求"
            @input="setNumber(field.key, $event.target.value)"
        /></label>
      </div>
      <p>关键业务问题 · 有答案必须保留正确证据，无答案必须不返回候选</p>
      <div class="ev-list ev-scroll">
        <label
          v-for="question in questions.filter((q) => q.split === 'ACCEPTANCE')"
          :key="question.id"
          class="ev-check"
          ><input
            type="checkbox"
            :data-testid="`critical-${question.id}`"
            :checked="modelValue.criticalQuestionIds?.includes(question.id)"
            @change="setCritical(question.id, $event.target.checked)"
          />{{ question.query }}{{ question.answerable ? '' : ' · 无答案' }}</label
        >
      </div>
    </details>
  </fieldset>
</template>
