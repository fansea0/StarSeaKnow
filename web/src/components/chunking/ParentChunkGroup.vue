<template>
  <section class="parent-chunk-group" :data-testid="`parent-chunk-${parent.publicId}`">
    <article class="parent-chunk" aria-label="只读父块上下文">
      <header class="parent-chunk__ribbon">
        <span class="parent-chunk__code mono">父块 {{ parentNumber }}</span>
        <span class="parent-chunk__path" :title="sectionPathText">{{ sectionPathText }}</span>
      </header>
      <div class="parent-chunk__body">
        <p data-testid="parent-content">{{ parent.content }}</p>
        <div class="parent-chunk__meta">
          <span>正文 {{ parent.tokenCount }} Token</span>
          <span>{{ children.length }} 个检索子块</span>
        </div>
      </div>
      <footer class="parent-chunk__footer">
        <span>命中子块后使用此父块回答</span>
        <el-button
          data-testid="toggle-parent"
          link
          type="primary"
          :aria-expanded="String(expanded)"
          @click="expanded = !expanded"
        >{{ expanded ? '收起子块' : '展开子块' }}</el-button>
      </footer>
    </article>

    <div v-show="expanded" class="parent-chunk-group__children">
      <ChunkCard
        v-for="child in children"
        :key="child.publicId"
        data-testid="child-chunk"
        :knowledge-id="knowledgeId"
        :file-id="fileId"
        :chunk="child"
        :label="`检索子块 ${childNumber(child)}`"
        :show-overlap-controls="false"
        :disabled="disabled || isReindexing(child)"
        :show-reindex="canReindex(child)"
        :reindex-disabled="reindexDisabled"
        :reload-epoch="reloadEpochs[child.publicId] || 0"
        @updated="$emit('updated', $event)"
        @deleted="$emit('deleted', $event)"
        @reload="$emit('reload', $event)"
        @reindex="$emit('reindex', $event)"
        @save-state="$emit('save-state', $event)"
        @delete-state="$emit('delete-state', $event)"
      />
    </div>
  </section>
</template>

<script setup>
import { computed, ref } from 'vue'
import ChunkCard from './ChunkCard.vue'

const props = defineProps({
  knowledgeId: { type: [String, Number], required: true },
  fileId: { type: [String, Number], required: true },
  parent: { type: Object, required: true },
  children: { type: Array, default: () => [] },
  disabled: { type: Boolean, default: false },
  showReindex: { type: Function, default: () => false },
  isReindexing: { type: Function, default: () => false },
  reindexDisabled: { type: Boolean, default: false },
  reloadEpochs: { type: Object, default: () => ({}) },
})

defineEmits(['updated', 'deleted', 'reload', 'reindex', 'save-state', 'delete-state'])

const expanded = ref(true)
const parentNumber = computed(() => String((Number(props.parent.siblingPosition) || 0) + 1).padStart(2, '0'))
const sectionPathText = computed(() => props.parent.sectionPath?.length ? props.parent.sectionPath.join(' / ') : '文档正文')
function childNumber(child) {
  return String((Number(child.siblingPosition) || 0) + 1).padStart(2, '0')
}
function canReindex(child) { return props.showReindex(child) }
function isReindexing(child) { return props.isReindexing(child) }
</script>

<style scoped>
.parent-chunk-group { position: relative; display: grid; gap: 12px; padding-left: 18px; }
.parent-chunk-group::before { position: absolute; top: 22px; bottom: 22px; left: 5px; width: 2px; background: color-mix(in srgb, var(--sea-signal) 52%, var(--sea-paper)); content: ''; }
.parent-chunk { position: relative; overflow: hidden; border: 1px solid color-mix(in srgb, var(--sea-muted) 22%, var(--sea-paper)); border-radius: 10px; background: var(--sea-paper); }
.parent-chunk__ribbon { display: grid; grid-template-columns: auto minmax(0, 1fr); gap: 14px; padding: 8px 14px; border-bottom: 1px solid color-mix(in srgb, var(--sea-signal) 20%, var(--sea-paper)); background: color-mix(in srgb, var(--sea-signal) 6%, var(--sea-paper)); }
.parent-chunk__code { color: var(--sea-deep); font-size: 11px; font-weight: 600; letter-spacing: .08em; }
.parent-chunk__path { overflow: hidden; color: var(--sea-muted); font-size: 12px; text-overflow: ellipsis; white-space: nowrap; }
.parent-chunk__body { padding: 16px 18px 12px; }
.parent-chunk__body p { margin: 0; color: var(--sea-ink); font-size: 14px; line-height: 1.78; white-space: pre-wrap; }
.parent-chunk__meta { display: flex; gap: 12px; margin-top: 12px; color: var(--sea-muted); font-size: 11px; }
.parent-chunk__footer { display: flex; align-items: center; justify-content: space-between; gap: 12px; min-height: 48px; padding: 4px 14px 4px 18px; border-top: 1px solid color-mix(in srgb, var(--sea-muted) 14%, var(--sea-paper)); color: var(--sea-muted); font-size: 11px; }
.parent-chunk-group__children { display: grid; gap: 12px; }
@media (max-width: 520px) { .parent-chunk-group { padding-left: 12px; } .parent-chunk__ribbon { grid-template-columns: 1fr; gap: 3px; } .parent-chunk__footer { align-items: flex-start; flex-direction: column; padding: 9px 14px 9px 18px; } }
</style>
