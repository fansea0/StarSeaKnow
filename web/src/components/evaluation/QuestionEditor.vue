<script setup>
import { computed, ref } from 'vue'
import { labelText } from './evaluationState'
const props = defineProps({
  modelValue: { type: Object, required: true },
  chunks: { type: Array, default: () => [] },
  disabled: Boolean,
  compact: Boolean,
})
const emit = defineEmits(['update:modelValue'])
const search = ref(''),
  labeledOnly = ref(false)
const filtered = computed(() =>
  props.chunks.filter(
    (c) =>
      (!labeledOnly.value || props.modelValue.labels?.[c.id] != null) &&
      `${c.id} ${c.fileName} ${c.content}`.toLowerCase().includes(search.value.toLowerCase()),
  ),
)
function update(key, value) {
  emit('update:modelValue', { ...props.modelValue, [key]: value })
}
function setLabel(id, value) {
  const labels = { ...props.modelValue.labels }
  if (value === '') delete labels[id]
  else labels[id] = Number(value)
  emit('update:modelValue', {
    ...props.modelValue,
    reviewed: false,
    labels,
    hardNegativeIds: (props.modelValue.hardNegativeIds || []).filter((key) => labels[key] === 0),
  })
}
function setNegative(id, checked) {
  const ids = new Set(props.modelValue.hardNegativeIds || [])
  checked ? ids.add(id) : ids.delete(id)
  emit('update:modelValue', { ...props.modelValue, reviewed: false, hardNegativeIds: [...ids] })
}
</script>
<template>
  <fieldset class="ev-stack" :disabled="disabled">
    <label
      >问题<textarea
        :value="modelValue.query"
        rows="3"
        data-testid="question-query"
        placeholder="输入真实用户问题"
        @input="emit('update:modelValue', { ...modelValue, query: $event.target.value, reviewed: false })"
      />
    </label>
    <details :open="!compact">
    <summary v-if="compact">样本信息 · 加入问题集时填写</summary>
    <div class="ev-fields">
      <label
        >意图组<input
          :value="modelValue.intentGroup"
          data-testid="intent-group"
          @input="update('intentGroup', $event.target.value)"
        /><span class="ev-muted">同义问法使用同一组，不能跨校准与验收。</span></label
      >
      <label
        >业务类别<input
          :value="modelValue.category"
          placeholder="例如：安装、条件差异、跨块证据"
          @input="update('category', $event.target.value)"
      /></label>
      <label
        >样本分组<select :value="modelValue.split" @change="update('split', $event.target.value)">
          <option value="CALIBRATION">调试 / 校准集</option>
          <option value="ACCEPTANCE">冻结验收集</option>
        </select></label
      >
      <label
        >是否可回答<select
          :value="String(modelValue.answerable)"
          @change="
            emit('update:modelValue', {
              ...modelValue,
              answerable: $event.target.value === 'true',
              reviewed: false,
            })
          "
        >
          <option value="true">知识库有答案</option>
          <option value="false">已确认知识库无答案</option>
        </select></label
      >
    </div>
    </details>
    <details :open="!compact">
      <summary>
        相关性标注 · {{ Object.keys(modelValue.labels || {}).length }} / {{ chunks.length }} 块已标注
      </summary>
      <p class="ev-muted">
        2：足以支持答案；1：部分相关；0：无关或条件不符。未标注是未知。无答案需人工核查整个指定快照。
      </p>
      <div class="ev-fields">
        <label>查找候选<input v-model="search" placeholder="搜索正文、来源或 ID" /></label>
        <label class="ev-check"><input v-model="labeledOnly" type="checkbox" />只看已标注</label>
      </div>
      <div class="ev-table-wrap ev-scroll">
        <table class="ev-table">
          <thead>
            <tr>
              <th>候选内容（隐藏模型名与分数）</th>
              <th>相关性</th>
              <th>困难负例</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="chunk in filtered" :key="chunk.id">
              <td class="ev-evidence">
                <details>
                  <summary>
                    {{ chunk.fileName || '来源未知' }} · {{ chunk.content?.slice(0, 100) || chunk.id }}
                  </summary>
                  <p>{{ chunk.content }}</p>
                  <strong>完整入模文本</strong>
                  <pre>{{ chunk.indexContent ?? '入模文本不可用' }}</pre>
                  <code>{{ chunk.id }} · {{ chunk.contentHash || '哈希未提供' }}</code>
                </details>
              </td>
              <td>
                <select
                  :value="modelValue.labels?.[chunk.id] ?? ''"
                  :data-testid="`label-${chunk.id}`"
                  :aria-label="`${chunk.id} 相关性`"
                  @change="setLabel(chunk.id, $event.target.value)"
                >
                  <option value="">未标注 · 未知</option>
                  <option v-for="label in [2, 1, 0]" :key="label" :value="label">
                    {{ labelText(label) }}
                  </option>
                </select>
              </td>
              <td>
                <label class="ev-check"
                  ><input
                    type="checkbox"
                    :data-testid="`negative-${chunk.id}`"
                    :checked="modelValue.hardNegativeIds?.includes(chunk.id)"
                    :disabled="disabled || modelValue.labels?.[chunk.id] !== 0"
                    @change="setNegative(chunk.id, $event.target.checked)"
                  />困难负例</label
                >
              </td>
            </tr>
            <tr v-if="!filtered.length">
              <td colspan="3" class="ev-empty">
                {{ chunks.length ? '没有匹配内容。' : '先冻结语料快照，再标注候选。' }}
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </details>
    <label class="ev-check"
      ><input
        :checked="modelValue.reviewed"
        type="checkbox"
        data-testid="question-reviewed"
        @change="update('reviewed', $event.target.checked)"
      />我已审核问题、可回答性和当前标签</label
    >
  </fieldset>
</template>
