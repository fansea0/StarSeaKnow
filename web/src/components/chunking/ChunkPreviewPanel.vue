<template>
  <section class="preview-panel" aria-labelledby="preview-title">
    <header class="preview-panel__header">
      <div>
        <span class="panel-index mono">02 / 预览</span>
        <h2 id="preview-title">人工检查原始分块</h2>
        <p>标题路径只读；正文修改会自动保存。</p>
      </div>
      <span v-if="chunks.length" class="chunk-count mono">{{ countLabel }}</span>
    </header>

    <div v-if="processing" class="processing-strip" aria-live="polite">
      <span>{{ processingLabel }}</span>
      <el-progress :percentage="progress" :stroke-width="5" :show-text="false" />
      <strong>{{ progress }}%</strong>
    </div>

    <div v-if="loading" class="preview-empty" aria-live="polite">{{ loadingLabel }}</div>
    <div v-else-if="!chunks.length" class="preview-empty">
      <strong>尚无可预览分块</strong>
      <span>选择可用策略并生成预览后，在这里逐块检查。</span>
    </div>
    <div v-else class="chunk-stack">
      <div v-if="hierarchy.orphanChildren.length" class="hierarchy-integrity" role="alert">
        <span>分块层级数据异常：发现无法关联父块的检索子块。请刷新预览后重试。</span>
        <el-button link type="danger" @click="$emit('reload')">刷新预览</el-button>
      </div>
      <template v-if="hierarchy.hierarchical">
        <ParentChunkGroup
          v-for="group in hierarchy.parents"
          :key="group.parent.publicId"
          :knowledge-id="knowledgeId"
          :file-id="fileId"
          :parent="group.parent"
          :children="group.children"
          :disabled="actionsDisabled"
          :show-reindex="canReindex"
          :is-reindexing="isReindexing"
          :reindex-disabled="reindexDisabled"
          :reload-epochs="reloadEpochs"
          @updated="$emit('updated', $event)"
          @deleted="$emit('deleted', $event)"
          @reload="$emit('reload', $event)"
          @reindex="$emit('reindex', $event)"
          @save-state="$emit('save-state', $event)"
        />
      </template>
      <ChunkCard
        v-for="chunk in hierarchy.singles"
        :key="chunk.publicId"
        :knowledge-id="knowledgeId"
        :file-id="fileId"
        :chunk="chunk"
        :disabled="actionsDisabled || isReindexing(chunk)"
        :show-reindex="canReindex(chunk)"
        :reindex-disabled="reindexDisabled"
        :reload-epoch="reloadEpochs[chunk.publicId] || 0"
        @updated="$emit('updated', $event)"
        @deleted="$emit('deleted', $event)"
        @reload="$emit('reload', $event)"
        @reindex="$emit('reindex', $event)"
        @save-state="$emit('save-state', $event)"
      />
    </div>

    <footer v-if="chunks.length && showConfirm" class="preview-panel__footer">
      <div>
        <strong>检查完成</strong>
        <span>确认后开始为当前文件建立 {{ hierarchy.vectorCount }} 个检索单元的向量索引。</span>
      </div>
      <el-button
        type="primary"
        data-testid="open-confirm"
        :disabled="processing || confirmDisabled"
        @click="$emit('confirm')"
      >确认并建立索引</el-button>
    </footer>
  </section>
</template>

<script setup>
import { computed } from 'vue'
import ChunkCard from './ChunkCard.vue'
import ParentChunkGroup from './ParentChunkGroup.vue'

const props = defineProps({
  knowledgeId: { type: [String, Number], required: true },
  fileId: { type: [String, Number], required: true },
  chunks: { type: Array, default: () => [] },
  hierarchy: {
    type: Object,
    default: () => ({ hierarchical: false, parents: [], singles: [], orphanChildren: [], parentCount: 0, childCount: 0, vectorCount: 0 }),
  },
  loading: { type: Boolean, default: false },
  fileState: { type: Number, default: 0 },
  progress: { type: Number, default: 0 },
  processing: { type: Boolean, default: false },
  processingLabel: { type: String, default: '' },
  loadingLabel: { type: String, default: '正在读取分块…' },
  actionsDisabled: { type: Boolean, default: false },
  confirmDisabled: { type: Boolean, default: false },
  reindexDisabled: { type: Boolean, default: false },
  showConfirm: { type: Boolean, default: true },
  reindexingIds: { type: Set, default: () => new Set() },
  reloadEpochs: { type: Object, default: () => ({}) },
})

defineEmits(['updated', 'deleted', 'reload', 'reindex', 'confirm', 'save-state'])

const countLabel = computed(() => props.hierarchy.hierarchical
  ? `${props.hierarchy.parentCount} 父块 · ${props.hierarchy.childCount} 子块`
  : `${props.hierarchy.vectorCount} 块`)

function canReindex(chunk) {
  return [3, 6].includes(Number(props.fileState))
    && Number(chunk.status) === 0
    && Boolean(chunk.isModified)
}

function isReindexing(chunk) {
  return props.reindexingIds.has(chunk.publicId)
}
</script>

<style scoped>
.preview-panel {
  min-width: 0;
  max-height: calc(100vh - 154px);
  overflow-y: auto;
  padding: 26px 28px 0;
  background: var(--sea-paper);
  scrollbar-width: thin;
}

.preview-panel__header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 18px;
  margin-bottom: 18px;
}

.panel-index,
.chunk-count { color: var(--sea-signal); font-size: 11px; font-weight: 600; letter-spacing: .08em; }
.preview-panel__header h2 { margin: 7px 0 4px; color: var(--sea-deep); font-size: 20px; }
.preview-panel__header p { margin: 0; color: var(--sea-muted); font-size: 13px; }
.chunk-count { padding-top: 4px; white-space: nowrap; }
.chunk-stack { display: grid; gap: 14px; padding-bottom: 22px; }
.hierarchy-integrity { display: flex; align-items: center; justify-content: space-between; gap: 12px; padding: 11px 13px; border: 1px solid color-mix(in srgb, var(--sea-danger) 32%, var(--sea-paper)); border-radius: 8px; background: color-mix(in srgb, var(--sea-danger) 7%, var(--sea-paper)); color: var(--sea-danger); font-size: 12px; line-height: 1.5; }

.preview-empty {
  display: grid;
  min-height: 280px;
  place-content: center;
  gap: 7px;
  padding: 40px 24px;
  color: var(--sea-muted);
  text-align: center;
}

.preview-empty strong { color: var(--sea-deep); font-size: 15px; }
.preview-empty span { font-size: 13px; }

.processing-strip {
  display: grid;
  grid-template-columns: auto minmax(100px, 1fr) auto;
  align-items: center;
  gap: 12px;
  margin-bottom: 16px;
  padding: 11px 13px;
  border: 1px solid color-mix(in srgb, var(--sea-signal) 22%, var(--sea-paper));
  border-radius: 8px;
  background: color-mix(in srgb, var(--sea-signal) 6%, var(--sea-paper));
  color: var(--sea-muted);
  font-size: 12px;
}

.processing-strip strong { color: var(--sea-deep); font-family: 'JetBrains Mono', monospace; }

.preview-panel__footer {
  position: sticky;
  bottom: 0;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 18px;
  margin: 0 -28px;
  padding: 15px 28px;
  border-top: 1px solid color-mix(in srgb, var(--sea-muted) 18%, var(--sea-paper));
  background: color-mix(in srgb, var(--sea-paper) 95%, transparent);
  backdrop-filter: blur(8px);
}

.preview-panel__footer div { display: grid; gap: 3px; }
.preview-panel__footer strong { color: var(--sea-deep); font-size: 13px; }
.preview-panel__footer span { color: var(--sea-muted); font-size: 11px; }

@media (max-width: 850px) {
  .preview-panel { max-height: none; overflow: visible; }
  .preview-panel__footer { position: static; }
}

@media (max-width: 520px) {
  .preview-panel { padding: 20px 16px 0; }
  .preview-panel__footer { align-items: stretch; flex-direction: column; margin: 0 -16px; padding: 15px 16px; }
  .hierarchy-integrity { align-items: flex-start; flex-direction: column; }
}
</style>
