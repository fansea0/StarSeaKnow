<script setup>
import { computed, ref, watch } from 'vue'
import { createSnapshot } from '../../api/embeddingEvaluation'
import { errorText, snapshotDescription } from './evaluationState'
const props = defineProps({
  knowledgeId: { type: [String, Number], required: true },
  chunks: { type: Array, default: () => [] },
  modelValue: Object,
  initialChunkId: String,
  disabled: Boolean,
})
const emit = defineEmits(['update:modelValue'])
const scope = ref(props.initialChunkId ? 'SELECTED' : 'ALL'),
  selected = ref(props.initialChunkId ? [props.initialChunkId] : []),
  search = ref(''),
  busy = ref(false),
  error = ref('')
const filtered = computed(() =>
  props.chunks.filter((c) =>
    `${c.id} ${c.fileName} ${c.content}`.toLowerCase().includes(search.value.toLowerCase()),
  ),
)
const files = computed(() => [
  ...new Map(props.chunks.map((c) => [c.fileId, { id: c.fileId, name: c.fileName }])).values(),
])
watch([scope, selected], () => emit('update:modelValue', null), { deep: true })
function chooseFile(id) {
  const ids = props.chunks.filter((c) => c.fileId === id).map((c) => c.id)
  const allSelected = ids.every((id) => selected.value.includes(id))
  selected.value = allSelected
    ? selected.value.filter((id) => !ids.includes(id))
    : [...new Set([...selected.value, ...ids])]
}
async function freeze() {
  busy.value = true
  error.value = ''
  try {
    emit(
      'update:modelValue',
      await createSnapshot(props.knowledgeId, {
        scope: scope.value,
        chunkIds: scope.value === 'SELECTED' ? selected.value : [],
      }),
    )
  } catch (cause) {
    error.value = errorText(cause)
  } finally {
    busy.value = false
  }
}
</script>
<template>
  <section class="ev-stack">
    <fieldset class="ev-stack" :disabled="disabled || busy">
      <div class="ev-fields">
        <label
          >语料范围<select v-model="scope">
            <option value="ALL">全部有效分块</option>
            <option value="SELECTED">指定文档 / 手选候选</option>
          </select></label
        >
        <div class="ev-actions">
          <button
            type="button"
            class="ev-button"
            data-testid="freeze-snapshot"
            :disabled="!chunks.length || (scope === 'SELECTED' && !selected.length)"
            @click="freeze"
          >
            {{ busy ? '冻结中…' : modelValue ? '从最新文本重建快照' : '冻结语料快照' }}
          </button>
        </div>
      </div>
      <details v-if="scope === 'SELECTED'" open>
        <summary>选择候选 · {{ selected.length }} 块</summary>
        <p class="ev-muted">默认仅当前块；可勾选干扰块，或选择整个文档。指定范围始终按候选集内对比报告。</p>
        <div class="ev-actions">
          <button
            v-for="file in files"
            :key="file.id"
            type="button"
            class="ev-button"
            @click="chooseFile(file.id)"
          >
            {{ file.name }} · 全选 / 取消
          </button>
        </div>
        <label>查找分块<input v-model="search" placeholder="正文、来源或 ID" /></label>
        <div class="ev-scroll ev-list">
          <label v-for="chunk in filtered" :key="chunk.id" class="ev-check"
            ><input v-model="selected" type="checkbox" :value="chunk.id" />{{ chunk.fileName }} ·
            {{ chunk.content?.slice(0, 120) || chunk.id }}</label
          >
        </div>
      </details>
    </fieldset>
    <p v-if="!chunks.length" class="ev-notice">没有可冻结的有效分块。请先在文档中完成分块并保存。</p>
    <div v-if="modelValue" class="ev-notice" :class="{ 'ev-warning': modelValue.scope === 'SELECTED' }">
      <strong>{{ snapshotDescription(modelValue) }}</strong>
      <details>
        <summary>快照版本与文本</summary>
        <code>{{ modelValue.id }} · {{ modelValue.hash }}</code>
        <p>{{ modelValue.createdAt }}</p>
      </details>
    </div>
    <p v-if="error" class="ev-notice ev-error" role="alert">{{ error }}</p>
  </section>
</template>
