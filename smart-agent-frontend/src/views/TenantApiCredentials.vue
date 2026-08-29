<template>
  <main class="credential-page" aria-labelledby="credential-list-title">
    <header class="credential-page__heading">
      <div>
        <span class="credential-page__eyebrow">EXTERNAL ACCESS</span>
        <h1 id="credential-list-title">API 凭证</h1>
        <p>为外部 Agent 创建独立机器凭证，并限制其可检索的知识库范围。</p>
      </div>
      <button class="sea-button sea-button--primary" data-testid="open-create-credential" type="button" @click="openCreate">
        创建凭证
      </button>
    </header>

    <section class="credential-workspace" aria-label="API 凭证列表">
      <div class="credential-toolbar">
        <label>
          <span class="sr-only">搜索 API 凭证</span>
          <input v-model.trim="searchQuery" type="search" placeholder="搜索名称或 Key 前缀" />
        </label>
        <span>{{ filteredCredentials.length }} 个凭证</span>
      </div>

      <div v-if="loading" class="credential-state" aria-live="polite">正在载入凭证…</div>
      <div v-else-if="loadError" class="credential-state credential-state--error" role="alert">
        <strong>凭证列表未载入</strong>
        <span>{{ loadError }}</span>
        <button class="sea-button" type="button" @click="loadPage">重试</button>
      </div>
      <div v-else-if="!credentials.length" class="credential-state">
        <strong>还没有 API 凭证</strong>
        <span>创建第一把仅用于服务端调用的 RAG 检索凭证。</span>
        <button class="sea-button" type="button" @click="openCreate">创建凭证</button>
      </div>
      <div v-else class="credential-table-wrap">
        <table class="credential-table">
          <thead>
            <tr>
              <th scope="col">名称与 Key 标识</th>
              <th scope="col">业务类型</th>
              <th scope="col">知识库范围</th>
              <th scope="col">状态</th>
              <th scope="col">最近使用</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="item in filteredCredentials" :key="item.id">
              <td>
                <router-link class="credential-table__link" :to="`/tenant/api-credentials/${item.id}`">
                  {{ item.name }}
                </router-link>
                <code>{{ displayKey(item) }}</code>
              </td>
              <td><span class="credential-tag">{{ displayType(item.credentialType) }}</span></td>
              <td>{{ item.knowledgeIds.length }} 个知识库</td>
              <td><span class="credential-status" :data-status="item.status">{{ displayStatus(item.status) }}</span></td>
              <td>{{ formatDate(item.lastUsedAt) }}</td>
            </tr>
          </tbody>
        </table>
        <p v-if="!filteredCredentials.length" class="credential-table__empty">没有匹配的凭证。</p>
      </div>
    </section>

    <div v-if="showCreate" class="modal-layer" role="presentation">
      <section class="sea-modal sea-modal--wide" role="dialog" aria-modal="true" aria-labelledby="create-credential-title">
        <header class="sea-modal__heading">
          <div>
            <h2 id="create-credential-title">创建 API 凭证</h2>
            <p>当前版本仅创建 RAG 检索凭证。完整 Key 只会显示一次。</p>
          </div>
          <button class="icon-button" type="button" aria-label="关闭创建凭证" @click="showCreate = false">×</button>
        </header>

        <form class="credential-form" @submit.prevent="createCredential">
          <label class="form-field">
            <span>名称</span>
            <input v-model.trim="createForm.name" name="credentialName" maxlength="128" required placeholder="例如：客服问答 Agent" />
          </label>

          <div class="form-field">
            <span>业务类型</span>
            <div class="capability-card">
              <strong>RAG 检索</strong>
              <small>RAG_RETRIEVAL · 返回知识片段、分数与来源，不调用 LLM</small>
            </div>
          </div>

          <label class="form-field">
            <span>环境</span>
            <select v-model="createForm.environment">
              <option value="test">测试环境</option>
              <option value="live">生产环境</option>
            </select>
          </label>

          <fieldset class="form-field knowledge-options">
            <legend>授权知识库</legend>
            <label v-for="item in knowledgeBases" :key="item.publicId">
              <input v-model="createForm.knowledgeIds" type="checkbox" :value="item.publicId" />
              <span><strong>{{ item.name }}</strong><small>{{ item.description || '暂无描述' }}</small></span>
            </label>
            <p v-if="!knowledgeBases.length">当前租户没有可授权的知识库。</p>
          </fieldset>

          <div class="form-grid">
            <label class="form-field">
              <span>每分钟请求数</span>
              <input v-model.number="createForm.requestsPerMinute" type="number" min="1" max="100000" required />
            </label>
            <label class="form-field">
              <span>突发容量</span>
              <input v-model.number="createForm.burstCapacity" type="number" min="1" max="100000" required />
            </label>
            <label class="form-field">
              <span>最大并发</span>
              <input v-model.number="createForm.maxConcurrency" type="number" min="1" max="10000" required />
            </label>
          </div>

          <label class="form-field">
            <span>允许的 IP 网段</span>
            <textarea v-model="createForm.allowedIpCidrs" rows="2" placeholder="每行一个 CIDR，例如 10.0.0.0/24"></textarea>
          </label>

          <label class="form-field">
            <span>过期时间（可选，ISO 8601）</span>
            <input v-model.trim="createForm.expiresAt" placeholder="2026-12-31T00:00:00Z" />
          </label>

          <footer class="sea-modal__actions">
            <button class="sea-button" type="button" @click="showCreate = false">取消</button>
            <button class="sea-button sea-button--primary" data-testid="submit-create-credential" type="submit" :disabled="creating">
              {{ creating ? '正在创建…' : '创建并显示 Key' }}
            </button>
          </footer>
        </form>
      </section>
    </div>

    <div v-if="oneTimeKey" class="modal-layer modal-layer--secret" role="presentation">
      <section class="sea-modal secret-disclosure" role="dialog" aria-modal="true" aria-labelledby="one-time-key-title">
        <span class="secret-disclosure__signal" aria-hidden="true">ONE TIME</span>
        <h2 id="one-time-key-title">API Key 已创建</h2>
        <p>请立即复制并保存到服务端密钥管理工具。离开此页面后无法再次查看。</p>
        <div class="secret-box">
          <span>{{ disclosureCredential?.environment === 'live' ? '生产凭证' : '测试凭证' }}</span>
          <code data-testid="one-time-api-key">{{ oneTimeKey }}</code>
          <button class="sea-button" type="button" @click="copyKey">{{ copied ? '已复制' : '复制 Key' }}</button>
        </div>
        <label class="save-confirmation">
          <input v-model="saveConfirmed" type="checkbox" />
          <span>我已将 Key 保存到安全位置</span>
        </label>
        <button
          ref="confirmKeyButton"
          class="sea-button sea-button--primary sea-button--full"
          data-testid="confirm-key-saved"
          type="button"
          :disabled="!saveConfirmed"
          @click="acknowledgeKey"
        >确认已保存并关闭</button>
      </section>
    </div>
  </main>
</template>

<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { http } from '../api/http'

const SAFE_CREDENTIAL_FIELDS = [
  'id', 'name', 'credentialType', 'description', 'environment', 'status', 'expiresAt',
  'allowedIpCidrs', 'requestsPerMinute', 'burstCapacity', 'maxConcurrency',
  'authorizationVersion', 'displayPrefix', 'displayLastFour', 'createdAt', 'revokedAt',
  'lastUsedAt', 'lastUsedIp', 'knowledgeIds',
]

const credentials = ref([])
const knowledgeBases = ref([])
const loading = ref(true)
const loadError = ref('')
const searchQuery = ref('')
const showCreate = ref(false)
const creating = ref(false)
const oneTimeKey = ref('')
const disclosureCredential = ref(null)
const copied = ref(false)
const saveConfirmed = ref(false)
const confirmKeyButton = ref(null)
const createForm = reactive(defaultCreateForm())

const filteredCredentials = computed(() => {
  const query = searchQuery.value.toLocaleLowerCase()
  if (!query) return credentials.value
  return credentials.value.filter((item) => (
    `${item.name} ${item.displayPrefix} ${item.displayLastFour}`.toLocaleLowerCase().includes(query)
  ))
})

function defaultCreateForm() {
  return {
    name: '',
    environment: 'test',
    knowledgeIds: [],
    allowedIpCidrs: '10.0.0.0/24',
    requestsPerMinute: 60,
    burstCapacity: 10,
    maxConcurrency: 5,
    expiresAt: '',
  }
}

function safeCredential(source = {}) {
  const target = {}
  for (const field of SAFE_CREDENTIAL_FIELDS) target[field] = source[field] ?? null
  target.allowedIpCidrs = Array.isArray(source.allowedIpCidrs) ? [...source.allowedIpCidrs] : []
  target.knowledgeIds = Array.isArray(source.knowledgeIds) ? [...source.knowledgeIds] : []
  return target
}

async function loadPage() {
  loading.value = true
  loadError.value = ''
  try {
    const [credentialResponse, knowledgeResponse] = await Promise.all([
      http.get('/tenant/api-credentials'),
      http.get('/knowledge/list'),
    ])
    credentials.value = Array.isArray(credentialResponse.data?.data)
      ? credentialResponse.data.data.map(safeCredential)
      : []
    knowledgeBases.value = (knowledgeResponse.data?.data || [])
      .filter((item) => typeof item.publicId === 'string')
      .map((item) => ({ publicId: item.publicId, name: item.name, description: item.description }))
  } catch (cause) {
    loadError.value = cause.response?.data?.msg || '请检查网络后重试。'
  } finally {
    loading.value = false
  }
}

function openCreate() {
  Object.assign(createForm, defaultCreateForm())
  showCreate.value = true
}

function parseCidrs(value) {
  return String(value || '')
    .split(/[\n,]/)
    .map((item) => item.trim())
    .filter(Boolean)
}

async function createCredential() {
  if (!createForm.name) {
    ElMessage.error('请填写凭证名称')
    return
  }
  creating.value = true
  try {
    const response = await http.post('/tenant/api-credentials', {
      name: createForm.name,
      credentialType: 'RAG_RETRIEVAL',
      environment: createForm.environment,
      knowledgeIds: [...createForm.knowledgeIds],
      allowedIpCidrs: parseCidrs(createForm.allowedIpCidrs),
      requestsPerMinute: Number(createForm.requestsPerMinute),
      burstCapacity: Number(createForm.burstCapacity),
      maxConcurrency: Number(createForm.maxConcurrency),
      expiresAt: createForm.expiresAt || null,
    })
    const result = response.data?.data || {}
    const created = safeCredential(result.credential)
    credentials.value = [created, ...credentials.value.filter((item) => item.id !== created.id)]
    showCreate.value = false
    discloseKey(result.apiKey, created)
  } catch (cause) {
    ElMessage.error(cause.response?.data?.msg || '创建凭证失败')
  } finally {
    creating.value = false
  }
}

function discloseKey(apiKey, item) {
  oneTimeKey.value = typeof apiKey === 'string' ? apiKey : ''
  disclosureCredential.value = item
  copied.value = false
  saveConfirmed.value = false
  nextTick(() => confirmKeyButton.value?.focus())
}

async function copyKey() {
  if (!oneTimeKey.value || !navigator.clipboard?.writeText) return
  await navigator.clipboard.writeText(oneTimeKey.value)
  copied.value = true
}

function acknowledgeKey() {
  if (!saveConfirmed.value) return
  clearDisclosure()
}

function clearDisclosure() {
  oneTimeKey.value = ''
  disclosureCredential.value = null
  copied.value = false
  saveConfirmed.value = false
}

function displayKey(item) {
  return `${item.displayPrefix || '—'}••••${item.displayLastFour || '—'}`
}

function displayType(type) {
  return type === 'RAG_RETRIEVAL' ? 'RAG 检索' : type || '未知'
}

function displayStatus(status) {
  return { active: '启用', revoked: '已吊销', disabled: '已停用' }[status] || status || '未知'
}

function formatDate(value) {
  if (!value) return '尚未使用'
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? String(value) : date.toLocaleString('zh-CN', { hour12: false })
}

onMounted(loadPage)
onBeforeUnmount(clearDisclosure)
</script>

<style scoped>
.credential-page { width: 100%; padding: 4px 0 32px; color: var(--sea-deep); }
.credential-page__heading { display: flex; align-items: flex-end; justify-content: space-between; gap: 24px; margin-bottom: 22px; }
.credential-page__heading h1 { margin: 5px 0 6px; font-family: 'Noto Serif SC', serif; font-size: clamp(27px, 3vw, 34px); line-height: 1.2; }
.credential-page__heading p, .sea-modal__heading p { margin: 0; color: var(--sea-muted); font-size: 14px; line-height: 1.65; }
.credential-page__eyebrow { color: var(--sea-signal); font-size: 10px; font-weight: 800; letter-spacing: .16em; }
.credential-workspace { overflow: hidden; border: 1px solid color-mix(in srgb, var(--sea-muted) 22%, var(--sea-mist)); border-radius: 11px; background: var(--sea-paper); box-shadow: 0 10px 30px color-mix(in srgb, var(--sea-deep) 6%, transparent); }
.credential-toolbar { display: flex; align-items: center; justify-content: space-between; gap: 16px; padding: 15px 18px; border-bottom: 1px solid color-mix(in srgb, var(--sea-muted) 14%, var(--sea-mist)); color: var(--sea-muted); font-size: 12px; }
.credential-toolbar label { width: min(340px, 75%); }
.credential-toolbar input, .form-field input, .form-field select, .form-field textarea { width: 100%; box-sizing: border-box; border: 1px solid color-mix(in srgb, var(--sea-muted) 32%, var(--sea-mist)); border-radius: 7px; background: var(--sea-paper); color: var(--sea-deep); font: inherit; }
.credential-toolbar input { min-height: 38px; padding: 8px 11px; }
.credential-table-wrap { overflow-x: auto; }
.credential-table { width: 100%; min-width: 760px; border-collapse: collapse; }
.credential-table th { padding: 12px 18px; background: color-mix(in srgb, var(--sea-mist) 55%, var(--sea-paper)); color: var(--sea-muted); font-size: 11px; font-weight: 700; letter-spacing: .03em; text-align: left; }
.credential-table td { padding: 15px 18px; border-top: 1px solid color-mix(in srgb, var(--sea-muted) 13%, var(--sea-mist)); color: color-mix(in srgb, var(--sea-deep) 74%, var(--sea-muted)); font-size: 13px; }
.credential-table tbody tr:hover { background: color-mix(in srgb, var(--sea-signal) 5%, var(--sea-paper)); }
.credential-table__link { display: block; margin-bottom: 5px; color: var(--sea-deep); font-size: 14px; font-weight: 750; text-decoration: none; }
.credential-table__link:hover { color: var(--sea-signal); }
.credential-table code { color: var(--sea-muted); font-size: 11px; }
.credential-tag, .credential-status { display: inline-flex; align-items: center; min-height: 23px; padding: 0 8px; border-radius: 999px; background: color-mix(in srgb, var(--sea-signal) 10%, var(--sea-paper)); color: color-mix(in srgb, var(--sea-deep) 68%, var(--sea-signal)); font-size: 11px; font-weight: 700; }
.credential-status::before { width: 6px; height: 6px; margin-right: 6px; border-radius: 50%; background: currentColor; content: ''; }
.credential-status[data-status='revoked'], .credential-status[data-status='disabled'] { background: color-mix(in srgb, var(--sea-sand) 25%, var(--sea-paper)); color: color-mix(in srgb, var(--sea-deep) 55%, var(--sea-muted)); }
.credential-table__empty { margin: 0; padding: 36px; color: var(--sea-muted); text-align: center; }
.credential-state { display: grid; justify-items: center; gap: 8px; min-height: 220px; box-sizing: border-box; align-content: center; padding: 28px; color: var(--sea-muted); text-align: center; }
.credential-state strong { color: var(--sea-deep); font-size: 18px; }
.credential-state--error strong { color: color-mix(in srgb, var(--sea-deep) 68%, var(--sea-sand)); }
.sea-button { min-height: 38px; padding: 8px 14px; border: 1px solid color-mix(in srgb, var(--sea-muted) 30%, var(--sea-mist)); border-radius: 7px; background: var(--sea-paper); color: var(--sea-deep); font: inherit; font-size: 13px; font-weight: 700; cursor: pointer; }
.sea-button:hover { border-color: color-mix(in srgb, var(--sea-signal) 54%, var(--sea-mist)); }
.sea-button:focus-visible, input:focus-visible, select:focus-visible, textarea:focus-visible, a:focus-visible { outline: 3px solid color-mix(in srgb, var(--sea-signal) 30%, transparent); outline-offset: 1px; }
.sea-button:disabled { cursor: not-allowed; opacity: .48; }
.sea-button--primary { border-color: var(--sea-signal); background: var(--sea-signal); color: var(--sea-paper); box-shadow: 0 8px 20px color-mix(in srgb, var(--sea-signal) 18%, transparent); }
.sea-button--full { width: 100%; margin-top: 12px; }
.modal-layer { position: fixed; z-index: 100; inset: 0; display: grid; overflow-y: auto; place-items: center; padding: 24px; background: color-mix(in srgb, var(--sea-deep) 48%, transparent); }
.modal-layer--secret { z-index: 110; background: color-mix(in srgb, var(--sea-deep) 68%, transparent); }
.sea-modal { width: min(100%, 520px); box-sizing: border-box; padding: 24px; border: 1px solid color-mix(in srgb, var(--sea-signal) 20%, var(--sea-mist)); border-radius: 12px; background: var(--sea-paper); box-shadow: 0 24px 70px color-mix(in srgb, var(--sea-deep) 30%, transparent); }
.sea-modal--wide { width: min(100%, 680px); margin-block: auto; }
.sea-modal__heading { display: flex; align-items: flex-start; justify-content: space-between; gap: 18px; margin-bottom: 20px; }
.sea-modal h2 { margin: 0 0 5px; font-family: 'Noto Serif SC', serif; font-size: 23px; }
.icon-button { flex: 0 0 auto; width: 34px; height: 34px; border: 1px solid color-mix(in srgb, var(--sea-muted) 25%, var(--sea-mist)); border-radius: 7px; background: transparent; color: var(--sea-muted); font-size: 21px; cursor: pointer; }
.credential-form { display: grid; gap: 15px; }
.form-field { display: grid; gap: 7px; margin: 0; color: var(--sea-deep); font-size: 12px; font-weight: 700; }
.form-field input, .form-field select, .form-field textarea { min-height: 39px; padding: 8px 10px; font-size: 13px; font-weight: 400; resize: vertical; }
.form-grid { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 11px; }
.capability-card { display: grid; gap: 3px; padding: 12px; border: 1px solid color-mix(in srgb, var(--sea-signal) 24%, var(--sea-mist)); border-radius: 8px; background: color-mix(in srgb, var(--sea-signal) 6%, var(--sea-paper)); }
.capability-card small, .knowledge-options small { color: var(--sea-muted); font-weight: 400; }
.knowledge-options { max-height: 210px; overflow-y: auto; padding: 0; border: 0; }
.knowledge-options legend { margin-bottom: 7px; }
.knowledge-options > label { display: flex; align-items: flex-start; gap: 9px; padding: 10px; border: 1px solid color-mix(in srgb, var(--sea-muted) 20%, var(--sea-mist)); border-radius: 7px; cursor: pointer; }
.knowledge-options > label + label { margin-top: 7px; }
.knowledge-options input { width: auto; min-height: 0; margin-top: 2px; accent-color: var(--sea-signal); }
.knowledge-options span { display: grid; gap: 2px; }
.knowledge-options p { margin: 0; padding: 10px; color: var(--sea-muted); font-weight: 400; }
.sea-modal__actions { display: flex; justify-content: flex-end; gap: 9px; margin-top: 3px; }
.secret-disclosure { border-top: 5px solid var(--sea-signal); }
.secret-disclosure__signal { color: var(--sea-signal); font-size: 10px; font-weight: 900; letter-spacing: .18em; }
.secret-disclosure > p { margin: 7px 0 16px; color: var(--sea-muted); font-size: 13px; line-height: 1.6; }
.secret-box { display: grid; gap: 10px; padding: 15px; border: 1px solid color-mix(in srgb, var(--sea-signal) 26%, var(--sea-mist)); border-radius: 9px; background: color-mix(in srgb, var(--sea-signal) 8%, var(--sea-paper)); }
.secret-box > span { color: var(--sea-muted); font-size: 11px; font-weight: 700; }
.secret-box code { overflow-wrap: anywhere; color: var(--sea-deep); font-size: 13px; line-height: 1.55; user-select: all; }
.secret-box .sea-button { justify-self: start; }
.save-confirmation { display: flex; align-items: center; gap: 9px; margin-top: 15px; color: var(--sea-deep); font-size: 13px; cursor: pointer; }
.save-confirmation input { accent-color: var(--sea-signal); }
.sr-only { position: absolute; width: 1px; height: 1px; overflow: hidden; clip: rect(0, 0, 0, 0); white-space: nowrap; }
@media (max-width: 680px) {
  .credential-page__heading { align-items: stretch; flex-direction: column; }
  .credential-page__heading .sea-button { width: 100%; }
  .credential-toolbar { align-items: stretch; flex-direction: column; }
  .credential-toolbar label { width: 100%; }
  .form-grid { grid-template-columns: 1fr; }
  .modal-layer { align-items: start; padding: 12px; }
  .sea-modal { margin-block: 12px; padding: 19px; }
}
@media (prefers-reduced-motion: reduce) { *, *::before, *::after { scroll-behavior: auto !important; transition: none !important; } }
</style>
