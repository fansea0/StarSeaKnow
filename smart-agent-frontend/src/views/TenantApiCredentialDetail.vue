<template>
  <main class="credential-detail" aria-labelledby="credential-detail-title">
    <header class="credential-detail__heading">
      <div>
        <router-link class="back-link" to="/tenant/api-credentials">← API 凭证</router-link>
        <span class="eyebrow">CREDENTIAL CONTROL</span>
        <h1 id="credential-detail-title">{{ credential.name || 'API 凭证' }}</h1>
        <p>管理此 RAG 检索凭证的机器访问边界。完整 Key 不会再次显示。</p>
      </div>
      <span class="credential-status" :data-status="credential.status">{{ displayStatus(credential.status) }}</span>
    </header>

    <div v-if="loading" class="page-state" aria-live="polite">正在载入凭证…</div>
    <div v-else-if="loadError" class="page-state page-state--error" role="alert">
      <strong>凭证未载入</strong><span>{{ loadError }}</span>
      <button class="sea-button" type="button" @click="loadPage">重试</button>
    </div>
    <template v-else>
      <section class="credential-summary" aria-label="凭证身份">
        <div><small>业务能力</small><strong>RAG 检索</strong><span>RAG_RETRIEVAL · 不可修改</span></div>
        <div><small>Key 标识</small><code>{{ displayKey(credential) }}</code><span>完整 Key 仅在创建或轮换时显示一次</span></div>
        <div><small>环境</small><strong>{{ credential.environment === 'live' ? '生产环境' : '测试环境' }}</strong><span>{{ credential.environment === 'live' ? '生产调用必须使用 HTTPS' : 'HTTP 仅限可信网络测试' }}</span></div>
      </section>

      <section class="detail-card" aria-labelledby="metadata-title">
        <header><div><span class="eyebrow">SETTINGS</span><h2 id="metadata-title">凭证设置</h2></div><span>只提交可修改的管理字段</span></header>
        <form class="credential-form" @submit.prevent="saveMetadata">
          <label class="form-field"><span>名称</span><input v-model.trim="form.name" name="credentialName" maxlength="128" required /></label>
          <label class="form-field"><span>说明</span><textarea v-model.trim="form.description" rows="3" maxlength="512" placeholder="说明这个凭证由哪个服务使用"></textarea></label>
          <label class="form-field"><span>允许的 IP 网段</span><textarea v-model="form.allowedIpCidrs" rows="2" placeholder="每行一个 CIDR，例如 10.0.0.0/24"></textarea></label>
          <div class="form-grid">
            <label class="form-field"><span>每分钟请求数</span><input v-model.number="form.requestsPerMinute" type="number" min="1" max="100000" required /></label>
            <label class="form-field"><span>突发容量</span><input v-model.number="form.burstCapacity" type="number" min="1" max="100000" required /></label>
            <label class="form-field"><span>最大并发</span><input v-model.number="form.maxConcurrency" type="number" min="1" max="10000" required /></label>
          </div>
          <label class="form-field"><span>过期时间（可选，ISO 8601）</span><input v-model.trim="form.expiresAt" placeholder="2026-12-31T00:00:00Z" /></label>
          <footer><button class="sea-button sea-button--primary" data-testid="save-credential-metadata" type="submit" :disabled="saving">{{ saving ? '正在保存…' : '保存设置' }}</button></footer>
        </form>
      </section>

      <section class="detail-card" aria-labelledby="scope-title">
        <header><div><span class="eyebrow">AUTHORIZATION</span><h2 id="scope-title">知识库范围</h2></div><button class="sea-button" data-testid="open-scope-editor" type="button" @click="openScopeEditor">编辑范围</button></header>
        <p class="card-copy">外部检索使用此完整授权集合。调用方不能通过请求体选择知识库。</p>
        <ul class="scope-list">
          <li v-for="id in credential.knowledgeIds" :key="id">{{ knowledgeName(id) }}<code>{{ id }}</code></li>
          <li v-if="!credential.knowledgeIds.length" class="scope-list__empty">尚未授权知识库；调用会返回空范围。</li>
        </ul>
      </section>

      <section class="detail-card detail-card--danger" aria-labelledby="lifecycle-title">
        <header><div><span class="eyebrow">LIFECYCLE</span><h2 id="lifecycle-title">轮换与吊销</h2></div></header>
        <p class="card-copy">轮换会立即吊销当前 Key，并创建一把继承当前设置与范围的新凭证。</p>
        <div class="lifecycle-actions">
          <button class="sea-button" data-testid="open-rotate-confirmation" type="button" :disabled="credential.status === 'revoked'" @click="showRotateConfirmation = true">轮换 Key</button>
          <button class="sea-button sea-button--danger" data-testid="open-revoke-confirmation" type="button" :disabled="credential.status === 'revoked'" @click="showRevokeConfirmation = true">吊销凭证</button>
        </div>
      </section>
    </template>

    <div v-if="showScopeEditor" class="modal-layer" role="presentation">
      <section class="sea-modal" role="dialog" aria-modal="true" aria-labelledby="scope-editor-title">
        <header class="modal-heading"><h2 id="scope-editor-title">替换知识库范围</h2><button class="icon-button" type="button" aria-label="关闭范围编辑" @click="showScopeEditor = false">×</button></header>
        <p class="card-copy">保存会以当前选择完整替换已有范围。</p>
        <fieldset class="knowledge-options"><legend>授权知识库</legend><label v-for="item in knowledgeBases" :key="item.publicId"><input v-model="scopeSelection" type="checkbox" :value="item.publicId" /><span><strong>{{ item.name }}</strong><small>{{ item.publicId }}</small></span></label><p v-if="!knowledgeBases.length">当前租户没有可授权的知识库。</p></fieldset>
        <footer class="modal-actions"><button class="sea-button" type="button" @click="showScopeEditor = false">取消</button><button class="sea-button sea-button--primary" data-testid="save-credential-scope" type="button" :disabled="savingScope" @click="saveScope">{{ savingScope ? '正在保存…' : '替换范围' }}</button></footer>
      </section>
    </div>

    <div v-if="showRotateConfirmation" class="modal-layer" role="presentation">
      <section class="sea-modal" role="dialog" aria-modal="true" aria-labelledby="rotate-title"><header class="modal-heading"><h2 id="rotate-title">轮换这把 Key？</h2></header><p class="card-copy">当前 Key 将立即失效，新 Key 仅显示一次。</p><footer class="modal-actions"><button class="sea-button" type="button" @click="showRotateConfirmation = false">取消</button><button class="sea-button sea-button--primary" data-testid="confirm-credential-rotation" type="button" :disabled="rotating" @click="rotateCredential">{{ rotating ? '正在轮换…' : '轮换并显示新 Key' }}</button></footer></section>
    </div>

    <div v-if="showRevokeConfirmation" class="modal-layer" role="presentation">
      <section class="sea-modal" role="dialog" aria-modal="true" aria-labelledby="revoke-title"><header class="modal-heading"><h2 id="revoke-title">吊销这把凭证？</h2></header><p class="card-copy">吊销后无法恢复；如仍需要访问，请创建或轮换新的凭证。</p><footer class="modal-actions"><button class="sea-button" type="button" @click="showRevokeConfirmation = false">取消</button><button class="sea-button sea-button--danger" data-testid="confirm-credential-revoke" type="button" :disabled="revoking" @click="revokeCredential">{{ revoking ? '正在吊销…' : '确认吊销' }}</button></footer></section>
    </div>

    <div v-if="oneTimeKey" class="modal-layer modal-layer--secret" role="presentation">
      <section class="sea-modal secret-disclosure" role="dialog" aria-modal="true" aria-labelledby="one-time-key-title">
        <span class="eyebrow">ONE TIME</span><h2 id="one-time-key-title">新的 API Key 已生成</h2><p>请立即复制并保存到服务端密钥管理工具。离开此页面后无法再次查看。</p>
        <div class="secret-box"><code data-testid="one-time-api-key">{{ oneTimeKey }}</code><button class="sea-button" type="button" @click="copyKey">{{ copied ? '已复制' : '复制 Key' }}</button></div>
        <label class="save-confirmation"><input v-model="saveConfirmed" type="checkbox" /><span>我已将 Key 保存到安全位置</span></label>
        <button class="sea-button sea-button--primary sea-button--full" data-testid="confirm-key-saved" type="button" :disabled="!saveConfirmed" @click="clearDisclosure">确认已保存并关闭</button>
      </section>
    </div>
  </main>
</template>

<script setup>
import { onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { onBeforeRouteLeave, useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { http } from '../api/http'

const route = useRoute()
const router = useRouter()
const credential = reactive({ knowledgeIds: [], allowedIpCidrs: [] })
const form = reactive({ name: '', description: '', allowedIpCidrs: '', requestsPerMinute: 60, burstCapacity: 10, maxConcurrency: 5, expiresAt: '' })
const knowledgeBases = ref([])
const scopeSelection = ref([])
const loading = ref(true)
const loadError = ref('')
const saving = ref(false)
const savingScope = ref(false)
const rotating = ref(false)
const revoking = ref(false)
const showScopeEditor = ref(false)
const showRotateConfirmation = ref(false)
const showRevokeConfirmation = ref(false)
const oneTimeKey = ref('')
const copied = ref(false)
const saveConfirmed = ref(false)

function safeCredential(source = {}) {
  return {
    id: source.id || '', name: source.name || '', credentialType: source.credentialType || '', description: source.description || '', environment: source.environment || 'test', status: source.status || '', expiresAt: source.expiresAt || null,
    allowedIpCidrs: Array.isArray(source.allowedIpCidrs) ? [...source.allowedIpCidrs] : [], requestsPerMinute: source.requestsPerMinute || 0, burstCapacity: source.burstCapacity || 0, maxConcurrency: source.maxConcurrency || 0,
    displayPrefix: source.displayPrefix || '', displayLastFour: source.displayLastFour || '', knowledgeIds: Array.isArray(source.knowledgeIds) ? [...source.knowledgeIds] : [],
  }
}
function applyCredential(source) {
  Object.assign(credential, safeCredential(source))
  Object.assign(form, { name: credential.name, description: credential.description, allowedIpCidrs: credential.allowedIpCidrs.join('\n'), requestsPerMinute: credential.requestsPerMinute, burstCapacity: credential.burstCapacity, maxConcurrency: credential.maxConcurrency, expiresAt: credential.expiresAt || '' })
}
function parseCidrs(value) { return String(value || '').split(/[\n,]/).map((item) => item.trim()).filter(Boolean) }
function displayKey(item) { return `${item.displayPrefix || '—'}••••${item.displayLastFour || '—'}` }
function displayStatus(status) { return { active: '启用', revoked: '已吊销', disabled: '已停用' }[status] || status || '未知' }
function knowledgeName(id) { return knowledgeBases.value.find((item) => item.publicId === id)?.name || '已授权知识库' }
function errorMessage(cause, fallback) { return cause.response?.data?.msg || fallback }

async function loadPage() {
  loading.value = true; loadError.value = ''
  try {
    const [credentialResponse, knowledgeResponse] = await Promise.all([http.get(`/tenant/api-credentials/${route.params.credentialId}`), http.get('/knowledge/list')])
    applyCredential(credentialResponse.data?.data)
    knowledgeBases.value = (knowledgeResponse.data?.data || []).filter((item) => typeof item.publicId === 'string').map((item) => ({ publicId: item.publicId, name: item.name || '未命名知识库' }))
  } catch (cause) { loadError.value = errorMessage(cause, '请检查网络后重试。') } finally { loading.value = false }
}
async function saveMetadata() {
  saving.value = true
  try {
    const response = await http.patch(`/tenant/api-credentials/${credential.id}`, { name: form.name, description: form.description || null, allowedIpCidrs: parseCidrs(form.allowedIpCidrs), requestsPerMinute: Number(form.requestsPerMinute), burstCapacity: Number(form.burstCapacity), maxConcurrency: Number(form.maxConcurrency), expiresAt: form.expiresAt || null })
    applyCredential(response.data?.data); ElMessage.success('凭证设置已保存')
  } catch (cause) { ElMessage.error(errorMessage(cause, '保存凭证设置失败')) } finally { saving.value = false }
}
function openScopeEditor() { scopeSelection.value = [...credential.knowledgeIds]; showScopeEditor.value = true }
async function saveScope() {
  savingScope.value = true
  try { const response = await http.put(`/tenant/api-credentials/${credential.id}/knowledge-bases`, { knowledgeIds: [...scopeSelection.value] }); applyCredential(response.data?.data); showScopeEditor.value = false; ElMessage.success('知识库范围已替换') } catch (cause) { ElMessage.error(errorMessage(cause, '替换知识库范围失败')) } finally { savingScope.value = false }
}
async function rotateCredential() {
  rotating.value = true
  try {
    const response = await http.post(`/tenant/api-credentials/${credential.id}/rotate`)
    const result = response.data?.data || {}; applyCredential(result.credential); showRotateConfirmation.value = false
    oneTimeKey.value = typeof result.apiKey === 'string' ? result.apiKey : ''; copied.value = false; saveConfirmed.value = false
    router.replace(`/tenant/api-credentials/${credential.id}`)
  } catch (cause) { ElMessage.error(errorMessage(cause, '轮换凭证失败')) } finally { rotating.value = false }
}
async function revokeCredential() {
  revoking.value = true
  try { const response = await http.post(`/tenant/api-credentials/${credential.id}/revoke`); applyCredential(response.data?.data); showRevokeConfirmation.value = false; ElMessage.success('凭证已吊销') } catch (cause) { ElMessage.error(errorMessage(cause, '吊销凭证失败')) } finally { revoking.value = false }
}
async function copyKey() { if (oneTimeKey.value && navigator.clipboard?.writeText) { await navigator.clipboard.writeText(oneTimeKey.value); copied.value = true } }
function clearDisclosure() { oneTimeKey.value = ''; copied.value = false; saveConfirmed.value = false }

onMounted(loadPage)
onBeforeRouteLeave(clearDisclosure)
onBeforeUnmount(clearDisclosure)
</script>

<style scoped>
.credential-detail { width: 100%; padding: 4px 0 32px; color: var(--sea-deep); }.credential-detail__heading, .detail-card header, .modal-heading, .lifecycle-actions, .modal-actions { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; }.credential-detail__heading { margin-bottom: 22px; }.credential-detail h1, .detail-card h2, .sea-modal h2 { margin: 5px 0; font-family: 'Noto Serif SC', serif; }.credential-detail h1 { font-size: clamp(27px, 3vw, 34px); }.detail-card h2, .sea-modal h2 { font-size: 20px; }.credential-detail__heading p, .card-copy, .detail-card header > span, .credential-summary span { margin: 0; color: var(--sea-muted); font-size: 13px; line-height: 1.6; }.eyebrow { display: block; color: var(--sea-signal); font-size: 10px; font-weight: 800; letter-spacing: .16em; }.back-link { display: inline-block; margin-bottom: 12px; color: var(--sea-muted); font-size: 13px; text-decoration: none; }.back-link:hover { color: var(--sea-signal); }.credential-summary { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 1px; overflow: hidden; margin-bottom: 16px; border: 1px solid color-mix(in srgb, var(--sea-muted) 20%, var(--sea-mist)); border-radius: 11px; background: color-mix(in srgb, var(--sea-muted) 16%, var(--sea-mist)); }.credential-summary > div { display: grid; gap: 5px; padding: 16px; background: var(--sea-paper); }.credential-summary small { color: var(--sea-muted); font-size: 11px; }.credential-summary strong, .credential-summary code { color: var(--sea-deep); font-size: 14px; }.detail-card { margin-top: 16px; padding: 20px; border: 1px solid color-mix(in srgb, var(--sea-muted) 20%, var(--sea-mist)); border-radius: 11px; background: var(--sea-paper); }.detail-card--danger { border-left: 4px solid color-mix(in srgb, var(--sea-sand) 65%, var(--sea-signal)); }.credential-status { flex: 0 0 auto; padding: 5px 9px; border-radius: 999px; background: color-mix(in srgb, var(--sea-signal) 11%, var(--sea-paper)); color: var(--sea-deep); font-size: 12px; font-weight: 700; }.credential-status[data-status='revoked'] { background: color-mix(in srgb, var(--sea-sand) 22%, var(--sea-paper)); }.credential-form { display: grid; gap: 14px; margin-top: 18px; }.form-field { display: grid; gap: 6px; color: var(--sea-deep); font-size: 12px; font-weight: 700; }.form-field input, .form-field textarea { width: 100%; box-sizing: border-box; min-height: 39px; padding: 8px 10px; border: 1px solid color-mix(in srgb, var(--sea-muted) 32%, var(--sea-mist)); border-radius: 7px; background: var(--sea-paper); color: var(--sea-deep); font: inherit; font-weight: 400; resize: vertical; }.form-grid { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 11px; }.scope-list { display: grid; gap: 7px; padding: 0; margin: 14px 0 0; list-style: none; }.scope-list li { display: flex; align-items: center; justify-content: space-between; gap: 12px; padding: 10px; border-radius: 7px; background: color-mix(in srgb, var(--sea-signal) 6%, var(--sea-paper)); font-size: 13px; }.scope-list code { color: var(--sea-muted); font-size: 11px; overflow-wrap: anywhere; }.scope-list__empty { color: var(--sea-muted); }.lifecycle-actions { justify-content: flex-start; margin-top: 16px; }.sea-button { min-height: 38px; padding: 8px 14px; border: 1px solid color-mix(in srgb, var(--sea-muted) 30%, var(--sea-mist)); border-radius: 7px; background: var(--sea-paper); color: var(--sea-deep); font: inherit; font-size: 13px; font-weight: 700; cursor: pointer; }.sea-button:hover { border-color: var(--sea-signal); }.sea-button:disabled { cursor: not-allowed; opacity: .5; }.sea-button--primary { border-color: var(--sea-signal); background: var(--sea-signal); color: var(--sea-paper); }.sea-button--danger { border-color: color-mix(in srgb, var(--sea-sand) 55%, var(--sea-mist)); background: color-mix(in srgb, var(--sea-sand) 17%, var(--sea-paper)); }.sea-button--full { width: 100%; margin-top: 12px; }.page-state { display: grid; justify-items: center; gap: 8px; min-height: 220px; align-content: center; color: var(--sea-muted); }.page-state--error strong { color: var(--sea-deep); }.modal-layer { position: fixed; z-index: 100; inset: 0; display: grid; overflow-y: auto; place-items: center; padding: 20px; background: color-mix(in srgb, var(--sea-deep) 52%, transparent); }.modal-layer--secret { z-index: 110; background: color-mix(in srgb, var(--sea-deep) 68%, transparent); }.sea-modal { width: min(100%, 540px); box-sizing: border-box; padding: 24px; border: 1px solid color-mix(in srgb, var(--sea-signal) 22%, var(--sea-mist)); border-radius: 12px; background: var(--sea-paper); box-shadow: 0 24px 70px color-mix(in srgb, var(--sea-deep) 30%, transparent); }.icon-button { width: 32px; height: 32px; border: 1px solid color-mix(in srgb, var(--sea-muted) 28%, var(--sea-mist)); border-radius: 7px; background: transparent; color: var(--sea-muted); font-size: 20px; cursor: pointer; }.modal-actions { justify-content: flex-end; margin-top: 18px; }.knowledge-options { display: grid; gap: 7px; max-height: 260px; overflow-y: auto; padding: 0; margin-top: 16px; border: 0; }.knowledge-options legend { margin-bottom: 4px; color: var(--sea-deep); font-size: 12px; font-weight: 700; }.knowledge-options label { display: flex; gap: 9px; padding: 10px; border: 1px solid color-mix(in srgb, var(--sea-muted) 20%, var(--sea-mist)); border-radius: 7px; cursor: pointer; }.knowledge-options label span { display: grid; gap: 2px; font-size: 13px; }.knowledge-options small { color: var(--sea-muted); font-size: 10px; }.secret-disclosure { border-top: 5px solid var(--sea-signal); }.secret-disclosure > p { color: var(--sea-muted); font-size: 13px; line-height: 1.6; }.secret-box { display: grid; gap: 10px; padding: 14px; margin-top: 14px; border: 1px solid color-mix(in srgb, var(--sea-signal) 25%, var(--sea-mist)); border-radius: 9px; background: color-mix(in srgb, var(--sea-signal) 8%, var(--sea-paper)); }.secret-box code { overflow-wrap: anywhere; color: var(--sea-deep); font-size: 13px; line-height: 1.55; user-select: all; }.secret-box .sea-button { justify-self: start; }.save-confirmation { display: flex; gap: 9px; margin-top: 15px; color: var(--sea-deep); font-size: 13px; }.sea-button:focus-visible, input:focus-visible, textarea:focus-visible, a:focus-visible { outline: 3px solid color-mix(in srgb, var(--sea-signal) 32%, transparent); outline-offset: 1px; }@media (max-width: 680px) { .credential-detail__heading, .detail-card header { flex-direction: column; }.credential-summary, .form-grid { grid-template-columns: 1fr; }.modal-layer { align-items: start; padding: 12px; }.sea-modal { margin-block: 12px; padding: 19px; } }@media (prefers-reduced-motion: reduce) { *, *::before, *::after { scroll-behavior: auto !important; transition: none !important; } }
</style>
