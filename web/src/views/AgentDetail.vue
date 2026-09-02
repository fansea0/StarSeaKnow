<template>
  <main class="agent-workbench" data-testid="agent-workbench">
    <div v-if="loading" class="aw-empty">正在载入智能体…</div>
    <div v-else-if="loadError" class="aw-empty" role="alert">{{ loadError }}<button class="aw-button" @click="load">重新加载</button></div>
    <template v-else-if="draft">
      <header class="aw-editor-bar">
        <div class="aw-identity"><RouterLink class="aw-back" to="/agent" aria-label="返回智能体列表">←</RouterLink><span class="aw-avatar">{{ draft.name?.slice(0, 1) || '助' }}</span>
          <div><h1>{{ draft.name }} <span class="aw-chip" :class="{ sand: draft.status !== 'PUBLISHED' }">{{ statusLabel }}</span></h1><small>关联 {{ draft.knowledgeIds.length }} 个知识库 · {{ saveState }}</small></div>
        </div>
        <div v-if="draft.editable" class="aw-actions">
          <button class="aw-button" data-testid="save-agent" :disabled="saving || conflict" @click="save">{{ saving ? '保存中…' : '保存草稿' }}</button>
          <button class="aw-button sand" :disabled="saving || conflict" @click="publishOpen = true">发布</button>
        </div>
      </header>
      <div v-if="feedback" class="aw-feedback" :class="{ 'aw-inline-error': feedbackError }" role="status">{{ feedback }}<button v-if="conflict" class="aw-text-button" @click="reloadConflict">重新载入最新草稿</button></div>
      <div class="aw-editor-body" :class="{ 'aw-editor-body--readonly': !draft.editable }">
        <aside v-if="draft.editable" class="aw-pane aw-config" aria-label="智能体配置">
          <nav class="aw-config-tabs" aria-label="配置分区"><button v-for="tab in configTabs" :key="tab.id" type="button" :class="{ active: configTab === tab.id }" @click="jump(tab.id)">{{ tab.label }}</button></nav>
          <div class="aw-pane-body" ref="configBody">
            <section id="config-basic" class="aw-config-section"><h2>基础资料</h2>
              <label>名称<input v-model="draft.name" maxlength="32" required /></label>
              <label>一句话描述<input v-model="draft.description" maxlength="256" /></label>
              <label>分类标签<input :value="draft.tags.join('，')" placeholder="逗号分隔，如：客服，RAG" @change="draft.tags = $event.target.value.split(/[,，]/).map(t => t.trim()).filter(Boolean)" /></label>
            </section>
            <section id="config-conversation" class="aw-config-section"><h2>对话设定</h2>
              <label>开场白<textarea v-model="draft.prologue" rows="4" maxlength="512" /></label><small class="aw-help">以「- 」开头的每一行显示为快捷问题</small>
              <label>系统提示词<textarea v-model="draft.systemPrompt" rows="4" maxlength="32000" /></label>
            </section>
            <section id="config-knowledge" class="aw-config-section"><h2>知识库</h2><p class="aw-help">为该智能体提供可检索的资料</p>
              <div v-for="id in draft.knowledgeIds" :key="id" class="aw-knowledge-link"><span>▦</span><strong>{{ knowledgeName(id) }}</strong><button class="aw-text-button" :aria-label="`移除知识库 ${knowledgeName(id)}`" @click="draft.knowledgeIds = draft.knowledgeIds.filter(k => k !== id)">×</button></div>
              <button class="aw-button" @click="knowledgeOpen = true">＋ 关联知识库</button>
            </section>
            <section id="config-model" class="aw-config-section"><h2>模型</h2><AgentModelSelector v-model:model="draft.model" :providers="providers" @refresh="loadProviders" /></section>
            <section id="config-retrieval" class="aw-config-section"><h2>检索参数</h2>
              <div class="aw-placeholder"><span>混合检索<small>向量 + 关键词 · 暂未开放</small></span><button role="switch" aria-checked="false" aria-label="混合检索（暂未开放）" disabled class="aw-switch"></button></div>
              <div class="aw-grid-two"><label>Top-K<input v-model.number="draft.retrievalTopK" type="number" min="1" max="20" /></label><label>相似度阈值<input v-model.number="draft.retrievalScoreThreshold" type="number" min="0" max="1" step="0.01" /></label></div>
            </section>
            <section class="aw-config-section"><h2>变量</h2><p class="aw-help">在提示词中使用 <code v-pre>{{变量名}}</code>，运行时填写。</p>
              <div v-for="(variable, index) in draft.variables" :key="index" class="aw-variable-definition">
                <div class="aw-grid-two"><label>变量名<input v-model="variable.name" placeholder="如：department" /></label><label>显示名<input v-model="variable.label" placeholder="如：部门" /></label></div>
                <label>默认值<input v-model="variable.defaultValue" /></label><div class="aw-variable-actions"><label><input v-model="variable.required" type="checkbox" /> 必填</label><button class="aw-text-button danger" @click="draft.variables.splice(index, 1)">删除</button></div>
              </div><button class="aw-button" :disabled="draft.variables.length >= 50" @click="draft.variables.push({ name: '', label: '', defaultValue: '', required: false })">＋ 添加变量</button>
            </section>
          </div>
        </aside>
        <section class="aw-pane aw-stage" aria-label="智能体工作区">
          <header class="aw-stage-head"><span><b>{{ draft.editable ? 'DEBUG' : 'CHAT' }}</b> {{ draft.editable ? '调试预览' : '智能体对话' }}</span><small>{{ draft.model?.modelId || '尚未选择模型' }}<template v-if="draft.model"> · {{ draft.model.temperature }}</template></small></header>
          <nav v-if="draft.editable" class="aw-stage-tabs" role="tablist"><button v-for="tab in stageTabs" :key="tab.id" role="tab" :aria-selected="stage === tab.id" :class="{ active: stage === tab.id }" @click="stage = tab.id">{{ tab.label }}</button></nav>
          <AgentDebugPanel v-show="stage === 'chat'" :key="agentId" :agent-id="agentId" :editable="draft.editable" :prologue="draft.prologue" :variables="draft.variables" :before-send="save" />
          <section v-if="stage === 'prompt' && draft.editable" class="aw-prompt"><div class="aw-prompt-toolbar"><span class="aw-eyebrow">SYSTEM PROMPT</span><button class="aw-text-button" @click="draft.systemPrompt = lastSavedPrompt">还原到上次保存</button></div><textarea v-model="draft.systemPrompt" aria-label="系统提示词编辑器" spellcheck="false" maxlength="32000" placeholder="在这里写你的系统提示词…" /><footer><span>修改自动保存到草稿</span><span>{{ draft.systemPrompt.length }} 字符 · {{ draft.systemPrompt.split('\n').length }} 行</span></footer></section>
          <section v-if="stage === 'snapshots' && draft.editable" class="aw-snapshots"><header><div><span class="aw-eyebrow">SNAPSHOTS · 快照</span><p class="aw-help">每次发布保留一个版本，回滚会生成新版本。</p></div><button class="aw-button sand" @click="publishOpen = true">＋ 发布新版本</button></header>
            <p v-if="snapshotsLoading" class="aw-empty">正在载入快照…</p><p v-else-if="!snapshots.length" class="aw-empty">尚未发布。保存配置后发布第一个版本。</p>
            <article v-for="snapshot in snapshots" :key="snapshot.id" class="aw-snapshot-row" :class="{ current: snapshot.id === draft.currentSnapshotId }"><span class="aw-snapshot-mark"></span><div><h3>v{{ snapshot.versionNumber }} <span v-if="snapshot.id === draft.currentSnapshotId" class="aw-chip">当前已发布</span></h3><small>{{ formatTime(snapshot.createTime) }} · 发布者 {{ snapshot.createdBy }}</small><p>{{ snapshot.publishNote }}</p></div><div class="aw-actions"><button class="aw-text-button" @click="preview(snapshot)">预览</button><button v-if="snapshot.id !== draft.currentSnapshotId" class="aw-text-button" @click="rollbackTarget = snapshot">回滚</button></div></article>
            <footer class="aw-actions"><button class="aw-button" :disabled="snapshotPage <= 1 || snapshotsLoading" @click="snapshotPage--; loadSnapshots()">上一页</button><span>{{ snapshotPage }} / {{ Math.max(1, Math.ceil(snapshotTotal / 20)) }}</span><button class="aw-button" :disabled="snapshotPage * 20 >= snapshotTotal || snapshotsLoading" @click="snapshotPage++; loadSnapshots()">下一页</button></footer>
          </section>
        </section>
      </div>
      <div v-if="knowledgeOpen" class="aw-modal-mask" @click.self="knowledgeOpen = false"><section role="dialog" aria-modal="true" aria-label="关联知识库" class="aw-modal"><h2>关联知识库</h2><p v-if="!knowledge.length">当前租户没有可关联的知识库。</p><label v-for="item in knowledge" :key="item.id" class="aw-checkbox"><input v-model="draft.knowledgeIds" type="checkbox" :value="item.id" /> {{ item.name }}</label><footer><button class="aw-button primary" @click="knowledgeOpen = false">完成</button></footer></section></div>
      <div v-if="publishOpen" class="aw-modal-mask" @click.self="!publishing && (publishOpen = false)"><form class="aw-modal" role="dialog" aria-modal="true" aria-label="发布智能体" @submit.prevent="publish"><h2>发布新版本</h2><p class="aw-help">正式调用将使用这次发布的配置，后续草稿修改不会影响它。</p><label>发布说明<textarea v-model="publishNote" required maxlength="512" rows="4" placeholder="简要说明本次修改…" /></label><p v-if="feedbackError" class="aw-error" role="alert">{{ feedback }}</p><footer><button class="aw-button" type="button" :disabled="publishing" @click="publishOpen = false">取消</button><button class="aw-button sand" :disabled="publishing || !publishNote.trim()">{{ publishing ? '发布中…' : '确认发布' }}</button></footer></form></div>
      <div v-if="snapshotPreview" class="aw-modal-mask" @click.self="snapshotPreview = null"><section class="aw-modal aw-modal-wide" role="dialog" aria-modal="true" aria-label="快照预览"><h2>v{{ snapshotPreview.versionNumber }} · 发布快照</h2><p>{{ snapshotPreview.publishNote }}</p><dl><dt>名称</dt><dd>{{ snapshotPreview.snapshotData.name }}</dd><dt>模型</dt><dd>{{ snapshotPreview.snapshotData.model?.modelId }}</dd><dt>系统提示词</dt><dd class="aw-pre">{{ snapshotPreview.snapshotData.systemPrompt }}</dd><dt>开场白</dt><dd class="aw-pre">{{ snapshotPreview.snapshotData.prologue }}</dd><dt>知识库</dt><dd>{{ snapshotPreview.snapshotData.knowledgeIds?.map(knowledgeName).join('、') || '无' }}</dd><dt>变量</dt><dd>{{ snapshotPreview.snapshotData.variables?.map(v => v.name).join('、') || '无' }}</dd></dl><footer><button class="aw-button" @click="snapshotPreview = null">关闭</button></footer></section></div>
      <div v-if="rollbackTarget" class="aw-modal-mask"><section class="aw-modal" role="dialog" aria-modal="true" aria-label="回滚确认"><h2>回滚到 v{{ rollbackTarget.versionNumber }}？</h2><p>当前草稿将被此版本替换，并立即生成一个新的发布版本。已有发布历史保持不变。</p><footer><button class="aw-button" :disabled="publishing" @click="rollbackTarget = null">取消</button><button class="aw-button sand" :disabled="publishing" @click="rollback">{{ publishing ? '回滚中…' : '确认回滚并发布' }}</button></footer></section></div>
    </template>
  </main>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { onBeforeRouteLeave, onBeforeRouteUpdate, useRoute } from 'vue-router'
import { http } from '../api/http'
import { listModelProviders } from '../api/modelProviders'
import { draftCommand, errorMessage, getAgent, getSnapshot, listSnapshots, publishAgent, rollbackAgent, saveAgent, unwrap } from '../api/agents'
import AgentModelSelector from '../components/agent/AgentModelSelector.vue'
import AgentDebugPanel from '../components/agent/AgentDebugPanel.vue'
import '../components/agent/workbench.css'
const route = useRoute(), agentId = computed(() => route.params.id)
const draft = ref(null), providers = ref([]), knowledge = ref([]), snapshots = ref([])
const loading = ref(true), loadError = ref(''), saving = ref(false), conflict = ref(false), feedback = ref(''), feedbackError = ref(false)
const stage = ref('chat'), configTab = ref('basic'), configBody = ref(null), knowledgeOpen = ref(false)
const publishOpen = ref(false), publishNote = ref(''), publishing = ref(false), snapshotsLoading = ref(false), snapshotPreview = ref(null), rollbackTarget = ref(null)
const lastSaved = ref(''), lastSavedPrompt = ref('')
const snapshotPage = ref(1), snapshotTotal = ref(0)
let timer, savePromise, disposed = false, loadGeneration = 0
const configTabs = [{ id: 'basic', label: '基础' }, { id: 'conversation', label: '对话' }, { id: 'knowledge', label: '知识库' }, { id: 'model', label: '模型' }, { id: 'retrieval', label: '检索' }]
const stageTabs = [{ id: 'chat', label: '对话' }, { id: 'prompt', label: '系统提示词' }, { id: 'snapshots', label: '发布快照' }]
const fingerprint = value => JSON.stringify({ ...draftCommand(value), lockVersion: null })
const dirty = computed(() => draft.value?.editable && fingerprint(draft.value) !== lastSaved.value)
const statusLabel = computed(() => ({ UNPUBLISHED: '未发布', DRAFT_CHANGED: '有暂存', PUBLISHED: '已发布' }[draft.value?.status] || '草稿'))
const saveState = computed(() => saving.value ? '保存中' : dirty.value ? '有未保存修改' : draft.value?.editable ? '草稿已保存' : '已发布版本')
function accept(value) { draft.value = value; lastSaved.value = fingerprint(value); lastSavedPrompt.value = value.systemPrompt || ''; conflict.value = false }
function report(error) { feedback.value = errorMessage(error); feedbackError.value = true; if (error?.response?.status === 409) conflict.value = true }
async function loadProviders() { try { providers.value = unwrap(await listModelProviders()) } catch (error) { report(error) } }
async function load() {
  const generation = ++loadGeneration
  loading.value = true; loadError.value = ''; clearTimeout(timer)
  try {
    const value = await getAgent(agentId.value)
    if (generation !== loadGeneration || disposed) return
    accept(value)
    if (value.editable) await Promise.all([loadProviders(), http.get('/knowledge/list/vo').then(unwrap).then(data => { knowledge.value = data || [] }).catch(report)])
  } catch (error) { if (generation === loadGeneration) loadError.value = errorMessage(error) }
  finally { if (generation === loadGeneration) loading.value = false }
}
async function save() {
  clearTimeout(timer)
  if (!draft.value?.editable) return true
  if (conflict.value) return false
  if (savePromise) { const ok = await savePromise; return ok && dirty.value ? save() : ok }
  if (!dirty.value) return true
  const command = draftCommand(draft.value), sentFingerprint = fingerprint(command), id = agentId.value
  saving.value = true
  savePromise = (async () => {
    try {
      const value = await saveAgent(id, command)
      if (disposed || id !== agentId.value) return false
      // Do not erase edits made while the request was in flight.
      Object.assign(draft.value, { lockVersion: value.lockVersion, draftRevision: value.draftRevision, publishedRevision: value.publishedRevision, status: value.status, updateTime: value.updateTime })
      lastSaved.value = sentFingerprint; lastSavedPrompt.value = command.systemPrompt
      feedback.value = '草稿已保存'; feedbackError.value = false
      return true
    } catch (error) { report(error); return false }
    finally { saving.value = false; savePromise = null }
  })()
  return savePromise
}
watch(() => draft.value && fingerprint(draft.value), () => {
  clearTimeout(timer)
  if (dirty.value && !loading.value && !conflict.value && !publishing.value) timer = setTimeout(() => { void save() }, 1200)
})
async function loadSnapshots() { snapshotsLoading.value = true; try { const result = await listSnapshots(agentId.value, snapshotPage.value); snapshots.value = result.items || []; snapshotTotal.value = result.total || 0 } catch (error) { report(error) } finally { snapshotsLoading.value = false } }
watch(stage, value => { if (value === 'snapshots') void loadSnapshots() })
async function publish() {
  publishing.value = true
  try {
    if (!await save()) return
    await publishAgent(agentId.value, draft.value.lockVersion, publishNote.value.trim())
    accept(await getAgent(agentId.value)); publishOpen.value = false; publishNote.value = ''
    feedback.value = '新版本已发布'; feedbackError.value = false; await loadSnapshots()
  } catch (error) { report(error) } finally { publishing.value = false }
}
async function rollback() {
  clearTimeout(timer); publishing.value = true
  try {
    if (savePromise) await savePromise
    await rollbackAgent(agentId.value, rollbackTarget.value.versionNumber, draft.value.lockVersion)
    accept(await getAgent(agentId.value)); rollbackTarget.value = null; feedback.value = '已回滚并发布新版本'; feedbackError.value = false; await loadSnapshots()
  } catch (error) { report(error) } finally { publishing.value = false }
}
async function preview(snapshot) { try { snapshotPreview.value = await getSnapshot(agentId.value, snapshot.versionNumber) } catch (error) { report(error) } }
function knowledgeName(id) { return knowledge.value.find(k => k.id === id)?.name || `知识库 #${id}` }
function formatTime(value) { return value ? new Date(value).toLocaleString('zh-CN') : '—' }
function jump(id) { configTab.value = id; configBody.value?.querySelector(`#config-${id}`)?.scrollIntoView({ block: 'start' }) }
async function reloadConflict() { if (window.confirm('重新载入会丢弃本页未保存修改，是否继续？')) { feedback.value = ''; await load() } }
async function guardDraft() { if (dirty.value && !await save()) return window.confirm('草稿尚未保存，仍要离开吗？') }
onBeforeRouteLeave(guardDraft)
onBeforeRouteUpdate(guardDraft)
function beforeUnload(event) { if (dirty.value) { event.preventDefault(); event.returnValue = '' } }
onMounted(() => { void load(); window.addEventListener('beforeunload', beforeUnload) })
watch(agentId, () => { stage.value = 'chat'; void load() })
onBeforeUnmount(() => { disposed = true; clearTimeout(timer); window.removeEventListener('beforeunload', beforeUnload) })
</script>
