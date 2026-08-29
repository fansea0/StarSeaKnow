<template>
  <main class="api-docs" aria-labelledby="api-docs-title">
    <header class="api-docs__heading">
      <span class="eyebrow">INTEGRATION GUIDE</span>
      <h1 id="api-docs-title">RAG Retrieval API 调用文档</h1>
      <p>外部服务可使用 API Key，从凭证授权的全部知识库中检索上下文。调用方无需登录平台。</p>
    </header>

    <section class="guide-card">
      <h2>1. 环境与安全</h2>
      <div class="environment-notes">
        <p><strong>测试凭证：</strong>HTTP 仅限可信网络测试，例如受控内网或本机集成环境。</p>
        <p><strong>生产凭证：</strong>生产环境必须使用 HTTPS；Key 只能保存在服务端密钥管理工具中。</p>
      </div>
    </section>

    <section class="guide-card">
      <h2>2. 请求格式</h2>
      <p>请求地址为 <code>POST /openapi/v1/retrieval</code>，Key 放入 <code>Authorization: Bearer</code> 请求头。</p>
      <pre data-testid="retrieval-request-example"><code>{
  "query": "如何办理退款？",
  "retrieval_setting": {
    "top_k": 5,
    "score_threshold": 0.2
  }
}</code></pre>
      <p class="guide-callout"><strong>范围由凭证决定。</strong>不支持 <code>knowledge_id</code> 或 <code>metadata_condition</code>；未知字段会返回 <code>400 invalid_request</code>。</p>
      <pre><code>curl -X POST "$BASE_URL/openapi/v1/retrieval" \
  -H "Authorization: Bearer $RAG_API_KEY" \
  -H "Content-Type: application/json" \
  --data @request.json</code></pre>
    </section>

    <section class="guide-card">
      <h2>3. 成功响应</h2>
      <p><code>records</code> 按 <code>score</code> 从高到低排列；<code>metadata</code> 给出公开文档与分块标识，不包含内部数据库 ID 或文件路径。</p>
      <pre data-testid="retrieval-success-response"><code>{
  "records": [
    {
      "content": "退款申请将在审核通过后原路退回。",
      "score": 0.92,
      "title": "售后服务政策",
      "metadata": {
        "document_id": "65c28997-ad56-4812-8a50-e00e804b45cc",
        "chunk_id": "d4d86a30-cf3e-45df-a33c-595e31fc8766",
        "file_type": "pdf",
        "page_number": 3,
        "chunk_index": 7
      }
    }
  ]
}</code></pre>
      <p>没有命中结果时仍返回 <code>200</code>：</p>
      <pre data-testid="retrieval-empty-response"><code>{
  "records": []
}</code></pre>
    </section>

    <section class="guide-card">
      <h2>4. 错误响应</h2>
      <p>所有错误都使用统一结构。响应头 <code>X-Request-ID</code> 与响应体中的 <code>request_id</code> 一致，可用于排查请求。</p>
      <pre data-testid="retrieval-error-response"><code>{
  "request_id": "91e250a4-5409-440f-91b8-fedea479b537",
  "error": {
    "code": "ip_not_allowed",
    "message": "Client IP is not allowed.",
    "param": null
  }
}</code></pre>
      <div class="status-grid">
        <span><strong>400</strong> 请求字段或 JSON 非法</span>
        <span><strong>401</strong> Key 无效、过期或已吊销</span>
        <span><strong>403</strong> 凭证停用、IP 或授权范围不允许</span>
        <span><strong>429</strong> 请求频率或并发超过限制</span>
        <span><strong>503</strong> 检索依赖暂不可用</span>
        <span><strong>504</strong> 检索超过服务端时限</span>
      </div>
    </section>
  </main>
</template>

<style scoped>
.api-docs { width: min(100%, 920px); box-sizing: border-box; padding: 36px 20px 52px; margin: 0 auto; color: var(--sea-deep); }
.api-docs__heading { margin-bottom: 22px; }
.eyebrow { color: var(--sea-signal); font-size: 10px; font-weight: 800; letter-spacing: .16em; }
.api-docs h1, .api-docs h2 { font-family: 'Noto Serif SC', serif; }
.api-docs h1 { margin: 6px 0; font-size: clamp(27px, 3vw, 34px); }
.api-docs h2 { margin: 0 0 9px; font-size: 20px; }
.api-docs__heading p, .guide-card p { margin: 0; color: var(--sea-muted); font-size: 14px; line-height: 1.7; }
.guide-card { padding: 20px; margin-top: 14px; border: 1px solid color-mix(in srgb, var(--sea-muted) 20%, var(--sea-mist)); border-radius: 11px; background: var(--sea-paper); }
.guide-card p + pre, .guide-card pre + p { margin-top: 14px; }
.environment-notes, .status-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 12px; }
.environment-notes p, .status-grid span { padding: 12px; border-radius: 8px; background: color-mix(in srgb, var(--sea-signal) 6%, var(--sea-paper)); }
.status-grid { margin-top: 14px; color: var(--sea-muted); font-size: 13px; }
.status-grid strong, .environment-notes strong, .guide-callout strong { color: var(--sea-deep); }
pre { overflow-x: auto; padding: 15px; margin: 14px 0 0; border-radius: 8px; background: color-mix(in srgb, var(--sea-deep) 92%, #000); color: #e6f5ef; font-size: 12px; line-height: 1.65; }
code { font-family: 'SFMono-Regular', Consolas, monospace; }
.guide-card > p code { color: var(--sea-deep); }
.guide-callout { padding: 12px; border-left: 3px solid var(--sea-signal); background: color-mix(in srgb, var(--sea-signal) 7%, var(--sea-paper)); }
@media (max-width: 620px) { .environment-notes, .status-grid { grid-template-columns: 1fr; } .guide-card { padding: 17px; } }
@media (prefers-reduced-motion: reduce) { * { transition: none !important; } }
</style>
