<script setup>
import { modelName } from './evaluationState'
const props = defineProps({
  models: { type: Array, default: () => [] },
  modelValue: { type: Array, default: () => [] },
  baseline: String,
  disabled: Boolean,
})
const emit = defineEmits(['update:modelValue', 'update:baseline'])
function select(id, checked) {
  const ids = checked ? [...props.modelValue, id] : props.modelValue.filter((value) => value !== id)
  emit('update:modelValue', ids)
  if (!ids.includes(props.baseline)) emit('update:baseline', ids[0] || '')
}
</script>
<template>
  <fieldset class="ev-stack" :disabled="disabled">
    <legend>模型配置 · 选择 1–3 个</legend>
    <div class="ev-actions">
      <label v-for="model in models" :key="model.id" class="ev-check"
        ><input
          type="checkbox"
          :checked="modelValue.includes(model.id)"
          :disabled="disabled || (!modelValue.includes(model.id) && modelValue.length >= 3)"
          :data-testid="`select-model-${model.id}`"
          @change="select(model.id, $event.target.checked)"
        />{{ modelName(model) }}
        <span class="ev-pill">v{{ model.revision }} · {{ model.dimensions ?? '未知' }} 维</span></label
      >
    </div>
    <label v-if="modelValue.length"
      >比较基线<select :value="baseline" @change="emit('update:baseline', $event.target.value)">
        <option
          v-for="model in models.filter((m) => modelValue.includes(m.id))"
          :key="model.id"
          :value="model.id"
        >
          {{ modelName(model) }}
        </option>
      </select></label
    >
    <p v-if="models.length < 2" class="ev-notice">
      {{ models.length ? '可先采集单模型基线；' : '暂无模型；' }}在“模型 → 向量模型”中添加候选后进行比较。
    </p>
  </fieldset>
</template>
