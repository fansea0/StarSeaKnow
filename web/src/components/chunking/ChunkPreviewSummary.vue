<template>
  <section v-if="summary" class="preview-summary" aria-labelledby="preview-summary-title">
    <div class="preview-summary__heading">
      <strong id="preview-summary-title">本次分块摘要</strong>
      <span v-if="isGeneral">{{ summary.delimiterMatched === false ? '分隔符未匹配，已按长度回退' : '分隔符已匹配' }}</span>
    </div>
    <dl>
      <div v-if="isGeneral"><dt>空白处理</dt><dd>{{ preprocessing.whitespaceMatches }} 处 / 减少 {{ preprocessing.whitespaceCharactersRemoved }} 字符</dd></div>
      <div v-if="isGeneral"><dt>URL</dt><dd>{{ preprocessing.urlMatches }} 处 / 替换 {{ preprocessing.urlCharactersReplaced }} 字符</dd></div>
      <div v-if="isGeneral"><dt>邮箱</dt><dd>{{ preprocessing.emailMatches }} 处 / 替换 {{ preprocessing.emailCharactersReplaced }} 字符</dd></div>
      <div v-if="isGeneral"><dt>控制字符</dt><dd>移除 {{ preprocessing.controlCharactersRemoved }} 字符</dd></div>
      <div v-if="isGeneral"><dt>空片段</dt><dd>移除 {{ preprocessing.emptySegmentsRemoved }} 个</dd></div>
      <div><dt>边界回退</dt><dd>强制切分 {{ number(summary.forcedSplitCount) }} / Token 限制切分 {{ number(summary.tokenLimitedSplitCount) }}</dd></div>
    </dl>
  </section>
</template>

<script setup>
import { computed } from 'vue'
const props = defineProps({ summary: { type: Object, default: null } })
const number = value => Number.isFinite(Number(value)) ? Number(value) : 0
const isGeneral = computed(() => String(props.summary?.strategyCode || '').toUpperCase() === 'GENERAL')
const preprocessing = computed(() => ({
  whitespaceMatches: number(props.summary?.preprocessingSummary?.whitespaceMatches),
  whitespaceCharactersRemoved: number(props.summary?.preprocessingSummary?.whitespaceCharactersRemoved),
  urlMatches: number(props.summary?.preprocessingSummary?.urlMatches),
  urlCharactersReplaced: number(props.summary?.preprocessingSummary?.urlCharactersReplaced),
  emailMatches: number(props.summary?.preprocessingSummary?.emailMatches),
  emailCharactersReplaced: number(props.summary?.preprocessingSummary?.emailCharactersReplaced),
  controlCharactersRemoved: number(props.summary?.preprocessingSummary?.controlCharactersRemoved),
  emptySegmentsRemoved: number(props.summary?.preprocessingSummary?.emptySegmentsRemoved),
}))
</script>

<style scoped>
.preview-summary { margin: 0 0 16px; padding: 13px 14px; border: 1px solid color-mix(in srgb, var(--sea-signal) 20%, var(--sea-paper)); border-radius: 8px; background: color-mix(in srgb, var(--sea-signal) 5%, var(--sea-paper)); }
.preview-summary__heading { display: flex; justify-content: space-between; gap: 12px; margin-bottom: 10px; }
.preview-summary__heading strong { color: var(--sea-deep); font-size: 12px; }
.preview-summary__heading span { color: var(--sea-muted); font-size: 11px; }
.preview-summary dl { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 8px; margin: 0; }
.preview-summary dl div { display: grid; gap: 2px; }
.preview-summary dt { color: var(--sea-muted); font-size: 10px; }
.preview-summary dd { margin: 0; color: var(--sea-ink); font-family: 'JetBrains Mono', monospace; font-size: 10.5px; line-height: 1.45; }
@media (max-width: 520px) { .preview-summary__heading { flex-direction: column; } .preview-summary dl { grid-template-columns: 1fr 1fr; } }
</style>
