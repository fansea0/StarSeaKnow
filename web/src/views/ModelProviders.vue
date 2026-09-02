<template>
  <section class="model-page" aria-label="模型管理">
    <header class="model-page__head">
      <div>
        <span class="model-page__eyebrow">MODELS · 模型</span>
        <h1>集中管理模型预设</h1>
        <p>按厂商维护一次 API Key 与 Base URL，智能体里直接选用，省去重复配置。</p>
      </div>
      <button class="sea-button sea-button--primary" type="button" data-testid="add-custom-provider" @click="openCustomProvider">
        + 自定义厂商
      </button>
    </header>

    <div class="model-shell">
      <aside class="provider-rail" aria-label="厂商">
        <label class="provider-search">
          <span class="sr-only">搜索厂商</span>
          <input v-model.trim="search" placeholder="搜索厂商" />
        </label>
        <nav class="provider-list">
          <button
            v-for="provider in visibleProviders"
            :key="providerKey(provider)"
            :data-testid="`provider-${provider.code}`"
            class="provider-item"
            :class="{ 'provider-item--active': providerKey(provider) === selectedKey }"
            type="button"
            @click="selectedKey = providerKey(provider)"
          >
            <span class="provider-logo" :data-code="provider.code">{{ providerInitial(provider) }}</span>
            <span class="provider-item__name">{{ provider.name }}</span>
            <span class="provider-item__count">{{ provider.selectableModels.length }}</span>
          </button>
        </nav>
      </aside>

      <main class="model-detail">
        <div v-if="loading" class="model-state">正在载入厂商配置…</div>
        <div v-else-if="loadError" class="model-state model-state--error">
          <strong>模型配置暂时无法载入</strong>
          <span>{{ loadError }}</span>
          <button class="sea-button" type="button" @click="loadProviders">重新加载</button>
        </div>
        <template v-else-if="selectedProvider">
          <header class="model-detail__head">
            <div class="provider-identity">
              <span class="provider-logo provider-logo--large" :data-code="selectedProvider.code">
                {{ providerInitial(selectedProvider) }}
              </span>
              <div>
                <div class="provider-title-line">
                  <h2>{{ selectedProvider.name }}</h2>
                  <span class="connection-chip" :class="{ 'connection-chip--muted': !selectedProvider.configured }">
                    <i aria-hidden="true"></i>{{ selectedProvider.configured ? '已连接' : '未配置' }}
                  </span>
                </div>
                <p>Base URL: <code>{{ selectedProvider.baseUrl || '尚未设置' }}</code> · {{ selectedProvider.selectableModels.length }} 个模型</p>
              </div>
            </div>
            <div class="model-detail__actions">
              <button
                v-if="selectedProvider.configured"
                class="sea-button"
                data-testid="add-model"
                type="button"
                @click="openModelDialog"
              >+ 添加模型</button>
              <button
                class="sea-button"
                :data-testid="`configure-${selectedProvider.code}`"
                type="button"
                @click="openConnectionDialog(selectedProvider)"
              >{{ selectedProvider.configured ? '连接设置' : '配置连接' }}</button>
            </div>
          </header>

          <div class="model-table" data-testid="model-table">
            <div class="model-row model-row--head">
              <span>名称</span><span>模型 ID</span><span>上下文</span><span class="align-right">操作</span>
            </div>
            <div v-for="model in selectedProvider.selectableModels" :key="model.modelId" class="model-row">
              <strong>{{ model.displayName }}</strong>
              <code>{{ model.modelId }}</code>
              <span>{{ formatContext(model.contextWindow) }}</span>
              <span class="row-actions">
                <button class="text-button text-button--danger" type="button" @click="removeModel(model)">删除</button>
              </span>
            </div>
            <div v-if="!selectedProvider.selectableModels.length" class="model-empty">
              <strong>{{ selectedProvider.configured ? '还没有可选模型' : '配置连接后可维护模型列表' }}</strong>
              <span>{{ selectedProvider.configured ? '添加厂商支持的模型 ID，之后即可在智能体中选择。' : '填写连接信息后，系统会自动发现厂商可用模型。' }}</span>
            </div>
          </div>

          <footer v-if="selectedProvider.configured" class="connection-footer">
            <span v-if="selectedProvider.apiKeyConfigured">API Key 已加密保存 · 末四位 {{ selectedProvider.apiKeyLastFour }}</span>
            <span v-else>该厂商连接不需要 API Key</span>
            <button class="text-button text-button--danger" type="button" @click="removeConnection">删除连接</button>
          </footer>
        </template>
        <div v-else class="model-state">没有匹配的厂商</div>
      </main>
    </div>

    <div v-if="connectionDialogOpen" class="modal-mask" role="presentation" @click.self="closeConnectionDialog">
      <form class="sea-modal" data-testid="connection-form" role="dialog" aria-modal="true" aria-labelledby="connection-title" @submit.prevent="saveConnection">
        <header>
          <span class="model-page__eyebrow">PROVIDER CONNECTION</span>
          <h3 id="connection-title">{{ connectionForm.connectionId ? '编辑厂商连接' : '配置厂商连接' }}</h3>
          <p>保存前会验证连接。API Key 只写入后端加密存储，不会再次明文返回。</p>
        </header>
        <div class="sea-modal__body">
          <div v-if="connectionForm.custom" class="field-grid field-grid--two">
            <label>厂商名称<input v-model.trim="connectionForm.customName" required placeholder="如：企业模型网关" /></label>
            <label>图标文字<input v-model.trim="connectionForm.customIcon" required maxlength="8" placeholder="如：AC" /></label>
          </div>
          <label>Base URL<input v-model.trim="connectionForm.baseUrl" required class="mono" placeholder="https://example.com/v1" /></label>
          <label v-if="connectionForm.authType === 'API_KEY'">
            API Key
            <input
              v-model="connectionForm.apiKey"
              data-testid="connection-api-key"
              class="mono"
              type="password"
              autocomplete="new-password"
              :required="!connectionForm.connectionId"
              :placeholder="connectionForm.connectionId ? '留空则继续使用已保存的密钥' : '请输入 API Key'"
            />
          </label>
          <div v-if="connectionForm.custom" class="custom-model-box">
            <strong>初始模型</strong>
            <div class="field-grid field-grid--two">
              <label>显示名<input v-model.trim="connectionForm.initialModel.displayName" required placeholder="模型显示名" /></label>
              <label>模型 ID<input v-model.trim="connectionForm.initialModel.modelId" required class="mono" placeholder="model-id" /></label>
            </div>
            <label>上下文长度<input v-model.number="connectionForm.initialModel.contextWindow" required type="number" min="1" /></label>
          </div>
          <p v-if="connectionFeedback" class="connection-feedback" :class="{ 'connection-feedback--error': connectionFeedbackError }">
            {{ connectionFeedback }}
          </p>
        </div>
        <footer>
          <button class="sea-button" type="button" @click="closeConnectionDialog">取消</button>
          <button
            class="sea-button"
            data-testid="test-connection"
            type="button"
            :disabled="testing || (connectionForm.authType === 'API_KEY' && !connectionForm.apiKey)"
            @click="testConnection"
          >{{ testing ? '测试中…' : '测试连接' }}</button>
          <button class="sea-button sea-button--primary" data-testid="save-connection" type="submit" :disabled="saving">
            {{ saving ? '保存中…' : '保存连接' }}
          </button>
        </footer>
      </form>
    </div>

    <div v-if="modelDialogOpen" class="modal-mask" role="presentation" @click.self="modelDialogOpen = false">
      <form class="sea-modal sea-modal--compact" data-testid="model-form" role="dialog" aria-modal="true" aria-labelledby="model-title" @submit.prevent="saveModel">
        <header>
          <span class="model-page__eyebrow">MODEL PRESET</span>
          <h3 id="model-title">添加模型</h3>
          <p>模型会加入当前厂商的候选列表，供智能体配置时选择。</p>
        </header>
        <div class="sea-modal__body">
          <label>显示名<input v-model.trim="modelForm.displayName" data-testid="model-display-name" required placeholder="如：主力对话" /></label>
          <label>模型 ID<input v-model.trim="modelForm.modelId" data-testid="model-id" required class="mono" placeholder="如：gpt-4o-mini" /></label>
          <label>上下文长度<input v-model.number="modelForm.contextWindow" data-testid="model-context-window" required type="number" min="1" /></label>
        </div>
        <footer>
          <button class="sea-button" type="button" @click="modelDialogOpen = false">取消</button>
          <button class="sea-button sea-button--primary" data-testid="save-model" type="submit" :disabled="saving">保存模型</button>
        </footer>
      </form>
    </div>
  </section>
</template>

<script setup>
import { computed, getCurrentInstance, onMounted, reactive, ref } from 'vue'
import {
  addProviderModel,
  createProviderConnection,
  deleteProviderConnection,
  listModelProviders,
  replaceProviderModels,
  testProviderConnection,
  updateProviderConnection,
} from '../api/modelProviders'

const instance = getCurrentInstance()
const providers = ref([])
const loading = ref(true)
const loadError = ref('')
const search = ref('')
const selectedKey = ref('')
const connectionDialogOpen = ref(false)
const modelDialogOpen = ref(false)
const testing = ref(false)
const saving = ref(false)
const connectionFeedback = ref('')
const connectionFeedbackError = ref(false)

const connectionForm = reactive(emptyConnectionForm())
const modelForm = reactive({ displayName: '', modelId: '', contextWindow: 128000 })

const visibleProviders = computed(() => {
  const keyword = search.value.toLowerCase()
  if (!keyword) return providers.value
  return providers.value.filter((provider) => (
    provider.name.toLowerCase().includes(keyword) || provider.code.toLowerCase().includes(keyword)
  ))
})

const selectedProvider = computed(() => (
  providers.value.find((provider) => providerKey(provider) === selectedKey.value)
    || visibleProviders.value[0]
    || null
))

onMounted(loadProviders)

async function loadProviders() {
  loading.value = true
  loadError.value = ''
  try {
    const response = await listModelProviders()
    providers.value = response?.data?.data || []
    if (!providers.value.some((item) => providerKey(item) === selectedKey.value)) {
      selectedKey.value = providers.value.length ? providerKey(providers.value[0]) : ''
    }
  } catch (error) {
    loadError.value = errorMessage(error, '请稍后重新加载。')
  } finally {
    loading.value = false
  }
}

function providerKey(provider) {
  return provider.connectionId ? `connection-${provider.connectionId}` : `catalog-${provider.catalogProviderId}`
}

function providerInitial(provider) {
  if (provider.custom && provider.icon) return provider.icon.slice(0, 2).toUpperCase()
  return provider.name?.slice(0, 1).toUpperCase() || '?'
}

function formatContext(size) {
  if (size >= 1000 && size % 1000 === 0) return `${size / 1000}K`
  return Number(size || 0).toLocaleString('zh-CN')
}

function emptyConnectionForm() {
  return {
    connectionId: null,
    catalogProviderId: null,
    custom: false,
    customName: '',
    customIcon: '',
    baseUrl: '',
    authType: 'API_KEY',
    apiKey: '',
    selectableModels: [],
    initialModel: { displayName: '', modelId: '', contextWindow: 128000 },
  }
}

function assignConnectionForm(values) {
  Object.assign(connectionForm, emptyConnectionForm(), values)
}

function openConnectionDialog(provider) {
  assignConnectionForm({
    connectionId: provider.connectionId,
    catalogProviderId: provider.catalogProviderId,
    custom: provider.custom,
    customName: provider.custom ? provider.name : '',
    customIcon: provider.custom ? provider.icon : '',
    baseUrl: provider.baseUrl,
    authType: provider.authType,
    selectableModels: provider.selectableModels.map((model) => ({ ...model })),
  })
  connectionFeedback.value = ''
  connectionDialogOpen.value = true
}

function openCustomProvider() {
  assignConnectionForm({ custom: true })
  connectionFeedback.value = ''
  connectionDialogOpen.value = true
}

function closeConnectionDialog() {
  if (!saving.value && !testing.value) connectionDialogOpen.value = false
}

function connectionCommand() {
  const selectableModels = connectionForm.custom
    ? [{
        displayName: connectionForm.initialModel.displayName,
        modelId: connectionForm.initialModel.modelId,
        contextWindow: Number(connectionForm.initialModel.contextWindow),
      }]
    : connectionForm.selectableModels
  return {
    catalogProviderId: connectionForm.catalogProviderId,
    customName: connectionForm.custom ? connectionForm.customName : null,
    customIcon: connectionForm.custom ? connectionForm.customIcon : null,
    baseUrl: connectionForm.baseUrl,
    apiKey: connectionForm.apiKey || null,
    selectableModels,
  }
}

async function testConnection() {
  testing.value = true
  connectionFeedback.value = ''
  connectionFeedbackError.value = false
  try {
    const response = await testProviderConnection(connectionCommand())
    const count = response?.data?.data?.discoveredModels?.length || 0
    connectionFeedback.value = `连接成功${count ? `，发现 ${count} 个模型` : ''}`
  } catch (error) {
    connectionFeedbackError.value = true
    connectionFeedback.value = errorMessage(error, '连接测试失败')
  } finally {
    testing.value = false
  }
}

async function saveConnection() {
  saving.value = true
  connectionFeedback.value = ''
  try {
    const command = connectionCommand()
    const response = connectionForm.connectionId
      ? await updateProviderConnection(connectionForm.connectionId, command)
      : await createProviderConnection(command)
    applyProvider(response?.data?.data)
    connectionDialogOpen.value = false
    notify('success', '模型厂商连接已保存')
  } catch (error) {
    connectionFeedbackError.value = true
    connectionFeedback.value = errorMessage(error, '保存连接失败')
  } finally {
    saving.value = false
  }
}

function openModelDialog() {
  Object.assign(modelForm, { displayName: '', modelId: '', contextWindow: 128000 })
  modelDialogOpen.value = true
}

async function saveModel() {
  if (!selectedProvider.value?.connectionId) return
  saving.value = true
  try {
    const model = {
      displayName: modelForm.displayName,
      modelId: modelForm.modelId,
      contextWindow: Number(modelForm.contextWindow),
    }
    const response = await addProviderModel(selectedProvider.value.connectionId, model)
    applyProvider(response?.data?.data)
    modelDialogOpen.value = false
    notify('success', '模型已添加')
  } catch (error) {
    notify('error', errorMessage(error, '添加模型失败'))
  } finally {
    saving.value = false
  }
}

async function removeModel(model) {
  if (!window.confirm(`确认删除模型“${model.displayName}”吗？`)) return
  const remaining = selectedProvider.value.selectableModels.filter((item) => item.modelId !== model.modelId)
  if (!remaining.length) {
    notify('error', '厂商至少需要保留一个可选模型')
    return
  }
  try {
    const response = await replaceProviderModels(selectedProvider.value.connectionId, remaining)
    applyProvider(response?.data?.data)
    notify('success', '模型已删除')
  } catch (error) {
    notify('error', errorMessage(error, '删除模型失败'))
  }
}

async function removeConnection() {
  const provider = selectedProvider.value
  if (!provider?.connectionId || !window.confirm(`确认删除“${provider.name}”连接吗？`)) return
  try {
    await deleteProviderConnection(provider.connectionId)
    await loadProviders()
    notify('success', '厂商连接已删除')
  } catch (error) {
    notify('error', errorMessage(error, '删除连接失败'))
  }
}

function applyProvider(updated) {
  if (!updated) return
  const catalogIndex = providers.value.findIndex((item) => (
    updated.catalogProviderId && item.catalogProviderId === updated.catalogProviderId
  ))
  const connectionIndex = providers.value.findIndex((item) => (
    updated.connectionId && item.connectionId === updated.connectionId
  ))
  const index = connectionIndex >= 0 ? connectionIndex : catalogIndex
  if (index >= 0) providers.value.splice(index, 1, updated)
  else providers.value.push(updated)
  selectedKey.value = providerKey(updated)
}

function notify(type, message) {
  instance?.proxy?.$message?.[type]?.(message)
}

function errorMessage(error, fallback) {
  return error?.response?.data?.msg || error?.message || fallback
}
</script>

<style scoped>
.model-page { max-width: 1440px; margin: 0 auto; }
.model-page__head { display: flex; align-items: flex-end; justify-content: space-between; gap: 28px; margin-bottom: 24px; }
.model-page__eyebrow { display: block; margin-bottom: 8px; color: var(--sea-signal); font: 600 12px/1.2 'JetBrains Mono', monospace; letter-spacing: .12em; }
.model-page__head h1 { margin: 0; color: var(--sea-deep); font: 700 clamp(26px, 3vw, 36px)/1.2 'Noto Serif SC', serif; }
.model-page__head p { max-width: 660px; margin: 8px 0 0; color: var(--sea-muted); line-height: 1.7; }
.model-shell { display: grid; grid-template-columns: 240px minmax(0, 1fr); gap: 14px; min-height: 540px; }
.provider-rail { padding: 8px; border: 1px solid #d5e1e6; border-radius: 12px; background: var(--sea-paper); box-shadow: 0 8px 28px rgb(17 36 59 / 7%); }
.provider-search input { width: 100%; min-height: 40px; padding: 0 12px; border: 1px solid #c7d7df; border-radius: 8px; background: var(--sea-paper); color: var(--sea-ink); }
.provider-list { display: grid; gap: 2px; margin-top: 8px; }
.provider-item { display: grid; grid-template-columns: 34px 1fr auto; align-items: center; gap: 10px; width: 100%; min-height: 48px; padding: 7px 9px; border: 1px solid transparent; border-radius: 8px; background: transparent; color: var(--sea-ink); text-align: left; cursor: pointer; }
.provider-item:hover { border-color: #c8dcdf; background: var(--sea-paper); }
.provider-item--active { border-color: color-mix(in srgb, var(--sea-signal) 35%, #d5e1e6); background: color-mix(in srgb, var(--sea-signal) 8%, var(--sea-paper)); color: var(--sea-deep); font-weight: 650; }
.provider-logo { display: inline-grid; width: 34px; height: 34px; place-items: center; border-radius: 9px; background: #475569; color: white; font: 700 13px/1 'JetBrains Mono', monospace; }
.provider-logo[data-code='OPENAI'] { background: #16836b; }
.provider-logo[data-code='ANTHROPIC'] { background: #b65b36; }
.provider-logo[data-code='DEEPSEEK'], .provider-logo[data-code='ZHIPU'] { background: var(--sea-deep); }
.provider-logo[data-code='QWEN'] { background: #b96b21; }
.provider-logo[data-code='OLLAMA'] { background: #263442; }
.provider-logo[data-code='CUSTOM'] { background: var(--sea-muted); }
.provider-logo--large { width: 48px; height: 48px; border-radius: 12px; font-size: 17px; }
.provider-item__name { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.provider-item__count { min-width: 24px; color: var(--sea-muted); font: 500 12px/1 'JetBrains Mono', monospace; text-align: right; }
.model-detail { min-width: 0; }
.model-detail__head { display: flex; align-items: center; justify-content: space-between; gap: 20px; min-height: 84px; padding: 16px 20px; border: 1px solid #d5e1e6; border-bottom: 0; border-radius: 12px 12px 0 0; background: var(--sea-paper); }
.provider-identity { display: flex; align-items: center; gap: 14px; min-width: 0; }
.provider-title-line { display: flex; align-items: center; gap: 10px; }
.provider-title-line h2 { margin: 0; color: var(--sea-deep); font: 700 22px/1.3 'Noto Serif SC', serif; }
.provider-identity p { margin: 5px 0 0; overflow-wrap: anywhere; color: var(--sea-muted); font-size: 13px; }
.provider-identity code { color: var(--sea-ink); }
.connection-chip { display: inline-flex; align-items: center; gap: 6px; padding: 4px 8px; border: 1px solid color-mix(in srgb, var(--sea-signal) 32%, #d7e2e6); border-radius: 999px; background: color-mix(in srgb, var(--sea-signal) 8%, white); color: #087878; font-size: 12px; }
.connection-chip i { width: 6px; height: 6px; border-radius: 50%; background: var(--sea-signal); }
.connection-chip--muted { border-color: #d5e1e6; background: #f3f6f7; color: var(--sea-muted); }
.connection-chip--muted i { background: #9aa8b2; }
.model-detail__actions { display: flex; gap: 8px; }
.model-table { width: 100%; overflow-x: auto; border: 1px solid #d5e1e6; border-radius: 0 0 12px 12px; background: var(--sea-paper); box-shadow: 0 8px 28px rgb(17 36 59 / 7%); }
.model-row { display: grid; grid-template-columns: minmax(150px, 1.2fr) minmax(180px, 1.3fr) 100px minmax(80px, .7fr); align-items: center; min-width: 650px; min-height: 62px; padding: 10px 24px; border-bottom: 1px solid #e5ecef; color: #3e5269; font-size: 14px; }
.model-row--head { min-height: 44px; background: #f1f6f7; color: var(--sea-muted); font-size: 12px; font-weight: 650; letter-spacing: .04em; }
.model-row strong { color: var(--sea-ink); font-weight: 650; }
.model-row code { color: var(--sea-deep); font-size: 13px; }
.align-right, .row-actions { text-align: right; }
.model-empty, .model-state { display: grid; min-height: 280px; place-content: center; gap: 8px; padding: 32px; color: var(--sea-muted); text-align: center; }
.model-empty strong, .model-state strong { color: var(--sea-ink); font-family: 'Noto Serif SC', serif; }
.model-state--error span { max-width: 480px; }
.model-state .sea-button { justify-self: center; margin-top: 8px; }
.connection-footer { display: flex; align-items: center; justify-content: space-between; gap: 16px; margin-top: 10px; padding: 12px 16px; border: 1px solid #d5e1e6; border-radius: 9px; background: color-mix(in srgb, var(--sea-paper) 82%, var(--sea-mist)); color: var(--sea-muted); font-size: 13px; }
.sea-button { min-height: 40px; padding: 0 15px; border: 1px solid #bfcfd7; border-radius: 8px; background: var(--sea-paper); color: var(--sea-ink); font-weight: 600; cursor: pointer; }
.sea-button:hover { border-color: var(--sea-signal); color: #087878; }
.sea-button--primary { border-color: var(--sea-signal); background: var(--sea-signal); color: white; }
.sea-button--primary:hover { background: #008f8f; color: white; }
.sea-button:disabled { cursor: not-allowed; opacity: .55; }
.text-button { min-height: 40px; border: 0; background: transparent; color: #087878; cursor: pointer; }
.text-button--danger { color: var(--sea-danger); }
.modal-mask { position: fixed; z-index: 2000; inset: 0; display: grid; place-items: center; padding: 24px; background: rgb(17 36 59 / 48%); }
.sea-modal { width: min(100%, 620px); max-height: calc(100vh - 48px); overflow-y: auto; border: 1px solid #cddbe1; border-radius: 12px; background: var(--sea-paper); box-shadow: 0 24px 70px rgb(17 36 59 / 24%); }
.sea-modal--compact { width: min(100%, 520px); }
.sea-modal > header { padding: 24px 26px 18px; border-bottom: 1px solid #dfe8ec; }
.sea-modal h3 { margin: 0; color: var(--sea-deep); font: 700 23px/1.3 'Noto Serif SC', serif; }
.sea-modal header p { margin: 7px 0 0; color: var(--sea-muted); font-size: 13px; line-height: 1.65; }
.sea-modal__body { display: grid; gap: 17px; padding: 22px 26px; }
.sea-modal label { display: grid; gap: 7px; color: var(--sea-ink); font-size: 13px; font-weight: 650; }
.sea-modal input { width: 100%; min-height: 42px; padding: 0 12px; border: 1px solid #b9ccd5; border-radius: 7px; background: white; color: var(--sea-ink); font-weight: 400; }
.sea-modal input:focus { border-color: var(--sea-signal); outline: 3px solid color-mix(in srgb, var(--sea-signal) 14%, white); }
.field-grid { display: grid; gap: 14px; }
.field-grid--two { grid-template-columns: 1fr 1fr; }
.custom-model-box { display: grid; gap: 14px; padding: 16px; border: 1px solid #dce6ea; border-radius: 9px; background: #f5f8f9; }
.connection-feedback { margin: 0; padding: 10px 12px; border-left: 3px solid var(--sea-signal); background: #ecf8f7; color: #087878; font-size: 13px; }
.connection-feedback--error { border-left-color: var(--sea-danger); background: #fbefee; color: var(--sea-danger); }
.sea-modal > footer { display: flex; justify-content: flex-end; gap: 8px; padding: 16px 26px 22px; border-top: 1px solid #dfe8ec; }
.sr-only { position: absolute; width: 1px; height: 1px; overflow: hidden; clip: rect(0, 0, 0, 0); white-space: nowrap; }
@media (max-width: 860px) {
  .model-shell { grid-template-columns: 1fr; }
  .provider-rail { border: 1px solid #d5e1e6; }
  .provider-list { grid-template-columns: repeat(auto-fit, minmax(150px, 1fr)); }
  .model-detail__head { align-items: flex-start; flex-direction: column; }
}
@media (max-width: 640px) {
  .model-page__head { align-items: flex-start; flex-direction: column; }
  .model-detail__actions { width: 100%; }
  .model-detail__actions .sea-button { flex: 1; }
  .field-grid--two { grid-template-columns: 1fr; }
  .connection-footer { align-items: flex-start; flex-direction: column; }
}
</style>
