<template>
  <main class="api-docs" aria-labelledby="api-docs-title">
    <header class="api-docs__heading"><span class="eyebrow">INTEGRATION GUIDE</span><h1 id="api-docs-title">调用文档</h1><p>使用 RAG 检索凭证从授权知识库获得上下文。凭证与知识范围都由服务端管理。</p></header>
    <section class="guide-card"><h2>1. 选择正确的环境</h2><div class="environment-notes"><p><strong>测试凭证：</strong>HTTP 仅限可信网络测试，例如受控内网或本机集成环境。</p><p><strong>生产凭证：</strong>生产环境必须使用 HTTPS；请将 Key 保存到服务端密钥管理工具，绝不放入浏览器或客户端应用。</p></div></section>
    <section class="guide-card"><h2>2. 发起检索请求</h2><p>将 Key 放入 <code>Authorization: Bearer</code> 请求头。请求体只接受以下两个字段：</p><pre data-testid="retrieval-request-example"><code>{
  "query": "如何办理退款？",
  "retrieval_setting": {
    "top_k": 5,
    "score_threshold": 0.2
  }
}</code></pre><p class="guide-callout"><strong>范围由凭证决定。</strong>不支持 <code>knowledge_id</code> 或 <code>metadata_condition</code>；服务会拒绝包含这些字段的外部请求。</p></section>
    <section class="guide-card"><h2>3. 接收结果</h2><p>响应仅包含检索到的片段、得分与来源。RAG 检索凭证不会调用模型，也不会接受任意知识库标识。</p><pre><code>curl -X POST "$BASE_URL/openapi/v1/retrieval" \
  -H "Authorization: Bearer $RAG_API_KEY" \
  -H "Content-Type: application/json" \
  --data @request.json</code></pre></section>
  </main>
</template>

<script setup>
</script>

<style scoped>
.api-docs { width: min(100%, 880px); padding: 4px 0 36px; color: var(--sea-deep); }.api-docs__heading { margin-bottom: 22px; }.eyebrow { color: var(--sea-signal); font-size: 10px; font-weight: 800; letter-spacing: .16em; }.api-docs h1, .api-docs h2 { font-family: 'Noto Serif SC', serif; }.api-docs h1 { margin: 6px 0; font-size: clamp(27px, 3vw, 34px); }.api-docs h2 { margin: 0 0 9px; font-size: 20px; }.api-docs__heading p, .guide-card p { margin: 0; color: var(--sea-muted); font-size: 14px; line-height: 1.7; }.guide-card { padding: 20px; margin-top: 14px; border: 1px solid color-mix(in srgb, var(--sea-muted) 20%, var(--sea-mist)); border-radius: 11px; background: var(--sea-paper); }.guide-card p + pre, .guide-card pre + p { margin-top: 14px; }.environment-notes { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 12px; }.environment-notes p { padding: 12px; border-radius: 8px; background: color-mix(in srgb, var(--sea-signal) 6%, var(--sea-paper)); }.environment-notes strong, .guide-callout strong { color: var(--sea-deep); }pre { overflow-x: auto; padding: 15px; margin: 14px 0 0; border-radius: 8px; background: color-mix(in srgb, var(--sea-deep) 92%, #000); color: #e6f5ef; font-size: 12px; line-height: 1.65; }code { font-family: 'SFMono-Regular', Consolas, monospace; }.guide-card > p code { color: var(--sea-deep); }.guide-callout { padding: 12px; border-left: 3px solid var(--sea-signal); background: color-mix(in srgb, var(--sea-signal) 7%, var(--sea-paper)); }@media (max-width: 620px) { .environment-notes { grid-template-columns: 1fr; }.guide-card { padding: 17px; } }@media (prefers-reduced-motion: reduce) { * { transition: none !important; } }
</style>
