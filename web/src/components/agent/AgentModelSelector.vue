<template>
  <div class="model-selector">
    <label>选择已配置模型
      <select data-testid="agent-model-select" :value="selected" @change="select($event.target.value)">
        <option value="">请选择模型</option>
        <option v-for="item in options" :key="key(item)" :value="key(item)">{{ item.label }}</option>
        <option v-if="missing" :value="selected" disabled>{{ model.modelId }}（连接已不可用，请重新选择）</option>
      </select>
    </label>
    <div class="model-shortcuts"><a href="/models" target="_blank" rel="noopener">＋ 配置模型 ↗</a><button type="button" @click="$emit('refresh')">刷新列表</button></div>
    <p v-if="!options.length" class="aw-help">尚未配置厂商，请先填写 API Key 并保存连接。</p>
    <details v-if="model" class="model-advanced">
      <summary>高级参数</summary>
      <div class="aw-grid-two">
        <label>Temperature<input type="number" min="0" max="2" step="0.1" :value="model.temperature" @input="parameter('temperature', $event)" /></label>
        <label>Max Tokens<input type="number" min="1" max="200000" :value="model.maxTokens" @input="parameter('maxTokens', $event)" /></label>
        <label>Top P<input type="number" min="0" max="1" step="0.05" :value="model.topP" @input="parameter('topP', $event)" /></label>
        <label>超时（秒）<input type="number" min="1" max="600" :value="model.timeoutSeconds" @input="parameter('timeoutSeconds', $event)" /></label>
      </div>
    </details>
  </div>
</template>
<script setup>
import { computed } from 'vue'
import { configuredModels } from '../../api/agents'
const props = defineProps({ model: Object, providers: { type: Array, default: () => [] } })
const emit = defineEmits(['update:model', 'refresh'])
const key = item => `${item.providerConnectionId}:${item.modelId}`
const options = computed(() => configuredModels(props.providers))
const selected = computed(() => props.model ? key(props.model) : '')
const missing = computed(() => props.model && !options.value.some(o => key(o) === selected.value))
function select(value) {
  const option = options.value.find(o => key(o) === value)
  emit('update:model', option ? { temperature: .4, topP: 1, maxTokens: 2048, timeoutSeconds: 60,
    ...props.model, providerConnectionId: option.providerConnectionId, modelId: option.modelId } : null)
}
function parameter(name, event) { emit('update:model', { ...props.model, [name]: event.target.value === '' ? null : Number(event.target.value) }) }
</script>
