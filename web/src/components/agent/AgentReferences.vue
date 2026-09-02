<template>
  <details v-if="citations.length" class="aw-citations">
    <summary><span class="aw-chip">本轮召回 · {{ citations.length }} 段</span><span>引用来源</span></summary>
    <article v-for="source in citations" :key="source.id">
      <strong>[{{ source.id }}] {{ source.knowledgeName }} · {{ source.title }}</strong>
      <small>{{ source.sectionPath?.join(' / ') }}<template v-if="source.pageNumber"> · 第 {{ source.pageNumber }} 页</template> · 相似度 {{ Number(source.score).toFixed(2) }}</small>
      <p>{{ source.summary }}</p>
    </article>
  </details>
</template>
<script setup>
defineProps({ citations: { type: Array, default: () => [] } })
</script>
