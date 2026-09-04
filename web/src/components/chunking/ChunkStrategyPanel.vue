<template>
  <aside class="strategy-panel" aria-labelledby="strategy-title">
    <header class="strategy-panel__header">
      <span class="panel-index mono">01 / 设置</span>
      <h2 id="strategy-title">选择分块方式</h2>
      <p>先按文档结构生成原始分块，再逐块人工检查。</p>
    </header>

    <div v-if="loading" class="strategy-loading" aria-live="polite">正在读取可用策略…</div>
    <div v-else class="strategy-list" role="group" aria-label="分块策略">
      <button
        v-for="strategy in strategies"
        :key="strategy.code"
        type="button"
        class="strategy-card"
        :class="{ 'is-selected': strategy.code === selectedCode, 'is-disabled': strategy.disabled }"
        :data-strategy="strategy.code"
        :disabled="strategy.disabled"
        :aria-disabled="strategy.disabled ? 'true' : 'false'"
        :aria-pressed="strategy.code === selectedCode ? 'true' : 'false'"
        @click="$emit('select', strategy.code)"
      >
        <span class="strategy-card__copy">
          <strong>{{ strategy.title }}</strong>
          <small>{{ strategy.description }}</small>
        </span>
        <span v-if="strategy.disabled" class="strategy-card__state">{{ strategy.reason || '暂未开放' }}</span>
        <span v-else-if="strategy.code === selectedCode" class="strategy-card__state strategy-card__state--selected">已选择</span>
      </button>
    </div>

    <MarkdownStrategyConfig
      v-if="selectedCode === 'MARKDOWN_OPTIMIZED'"
      :initial-values="strategyConfig"
      :disabled="configDisabled"
      @config-change="$emit('config-change', $event)"
      @validity-change="$emit('validity-change', $event)"
    />

    <ParentChildStrategyConfig
      v-else-if="selectedCode === 'PARENT_CHILD'"
      :initial-values="strategyConfig"
      :disabled="configDisabled"
      @config-change="$emit('config-change', $event)"
      @validity-change="$emit('validity-change', $event)"
    />

    <p v-if="error" class="strategy-error" role="alert">{{ error }}</p>

    <footer v-if="showPreviewAction" class="strategy-panel__footer">
      <el-button
        type="primary"
        data-testid="create-preview"
        :loading="submitting"
        :disabled="!selectedCode || !configValid || processing || actionsBlocked"
        @click="$emit('preview')"
      >生成分块预览</el-button>
      <small v-if="processing">文件处理中，完成后可再次提交。</small>
    </footer>
  </aside>
</template>

<script setup>
import MarkdownStrategyConfig from './MarkdownStrategyConfig.vue'
import ParentChildStrategyConfig from './ParentChildStrategyConfig.vue'

defineProps({
  strategies: { type: Array, default: () => [] },
  selectedCode: { type: String, default: '' },
  strategyConfig: { type: Object, default: () => ({ minTokens: 100, targetTokens: 400, maxTokens: 512 }) },
  configDisabled: { type: Boolean, default: false },
  configValid: { type: Boolean, default: true },
  loading: { type: Boolean, default: false },
  submitting: { type: Boolean, default: false },
  processing: { type: Boolean, default: false },
  actionsBlocked: { type: Boolean, default: false },
  showPreviewAction: { type: Boolean, default: true },
  error: { type: String, default: '' },
})

defineEmits(['select', 'config-change', 'validity-change', 'preview'])
</script>

<style scoped>
.strategy-panel {
  min-width: 0;
  padding: 26px;
  border-right: 1px solid color-mix(in srgb, var(--sea-muted) 20%, var(--sea-paper));
  background: color-mix(in srgb, var(--sea-mist) 50%, var(--sea-paper));
}

.panel-index {
  color: var(--sea-signal);
  font-size: 11px;
  font-weight: 600;
  letter-spacing: .08em;
}

.strategy-panel__header h2 {
  margin: 7px 0 4px;
  color: var(--sea-deep);
  font-size: 20px;
}

.strategy-panel__header p,
.strategy-panel__footer small {
  margin: 0;
  color: var(--sea-muted);
  font-size: 13px;
  line-height: 1.65;
}

.strategy-list { display: grid; gap: 10px; margin-top: 20px; }

.strategy-card {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 14px;
  width: 100%;
  min-height: 72px;
  padding: 14px 15px;
  border: 1px solid color-mix(in srgb, var(--sea-muted) 22%, var(--sea-paper));
  border-radius: 9px;
  background: var(--sea-paper);
  color: var(--sea-ink);
  text-align: left;
  transition: border-color 150ms ease, background 150ms ease;
}

.strategy-card:not(:disabled):hover,
.strategy-card.is-selected {
  border-color: color-mix(in srgb, var(--sea-signal) 52%, var(--sea-paper));
  background: color-mix(in srgb, var(--sea-signal) 6%, var(--sea-paper));
}

.strategy-card.is-disabled { cursor: not-allowed; opacity: .58; }
.strategy-card__copy { display: grid; gap: 4px; min-width: 0; }
.strategy-card__copy strong { color: var(--sea-deep); font-size: 14px; }
.strategy-card__copy small { color: var(--sea-muted); font-size: 12px; line-height: 1.45; }
.strategy-card__state { flex: 0 0 auto; color: var(--sea-muted); font-size: 11px; }
.strategy-card__state--selected { color: var(--sea-signal); font-weight: 700; }
.strategy-loading { margin-top: 20px; color: var(--sea-muted); font-size: 13px; }
.strategy-error { margin: 14px 0 0; color: var(--sea-danger); font-size: 13px; line-height: 1.5; }

.strategy-panel__footer {
  display: grid;
  gap: 8px;
  margin-top: 22px;
}

.strategy-panel__footer :deep(.el-button) { width: 100%; }

@media (max-width: 850px) {
  .strategy-panel { border-right: 0; border-bottom: 1px solid color-mix(in srgb, var(--sea-muted) 20%, var(--sea-paper)); }
}

@media (max-width: 520px) { .strategy-panel { padding: 20px 16px; } }

@media (prefers-reduced-motion: reduce) { .strategy-card { transition: none; } }
</style>
