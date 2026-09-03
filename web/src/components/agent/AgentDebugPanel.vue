<template>
  <section class="aw-debug-panel" aria-label="对话预览">
    <div v-if="editable" class="aw-session-tabs" aria-label="调试会话">
      <div v-for="session in sessions" :key="session.id" class="aw-session-tab" :class="{ active: session.id === activeId }">
        <button type="button" @click="activeId = session.id">{{ session.title }}</button>
        <button type="button" :aria-label="`关闭会话：${session.title}`" @click="closeSession(session)">×</button>
      </div>
      <button class="aw-text-button" :disabled="sessions.length >= 4" @click="newSession">＋ 新会话</button>
      <button type="button" class="aw-text-button aw-session-download" data-testid="export-session" title="下载当前会话 JSON，含知识库内容，请妥善保管" :disabled="exporting || active.busy || !active.contextId || !active.messages.length" @click="exportSession">{{ exporting ? '下载中…' : '下载' }}</button>
    </div>
    <div class="aw-runtime-variables" v-if="variables.length">
      <details open><summary>运行变量 <small>仅本次会话使用</small></summary>
        <div class="aw-grid-two"><label v-for="variable in variables" :key="variable.name">{{ variable.label || variable.name }}{{ variable.required ? ' *' : '' }}<input v-model="active.variables[variable.name]" :disabled="active.busy" :placeholder="variable.defaultValue || variable.name" maxlength="16000" /></label></div>
      </details>
    </div>
    <div class="aw-stream" ref="streamElement" aria-live="polite" aria-relevant="additions text">
      <div v-if="prologue" class="aw-message">
        <span class="aw-avatar">助</span><div><div class="aw-bubble">{{ greeting }}<div class="aw-quick-questions"><button v-for="question in questions" :key="question" :disabled="active.busy" @click="send(question)">{{ question }}</button></div></div><small>开场白</small></div>
      </div>
      <div v-if="!active.messages.length && !prologue" class="aw-empty">发送一条消息，验证智能体的回答与引用来源。</div>
      <div v-for="(message, index) in active.messages" :key="index" class="aw-message" :class="{ user: message.role === 'user' }">
        <span class="aw-avatar">{{ message.role === 'user' ? '你' : '助' }}</span>
        <div><div v-if="message.role === 'assistant' && message.content" class="aw-bubble aw-markdown" v-html="safeMarkdown(message.content)"></div><div v-else class="aw-bubble">{{ message.content || (active.busy ? '正在检索与生成…' : '未收到回答') }}</div>
          <AgentReferences :citations="message.citations || []" />
          <small v-if="message.usage">{{ (message.usage.elapsedMs / 1000).toFixed(1) }}s · {{ message.usage.totalTokens ?? '—' }} tok</small>
          <small v-if="message.failed" class="aw-error">本轮未加入上下文</small>
        </div>
      </div>
    </div>
    <p v-if="active.error" class="aw-inline-error" role="alert">{{ active.error }} <button v-if="active.expired" class="aw-text-button" @click="resetSession">新建上下文</button></p>
    <form class="aw-composer" @submit.prevent="send()">
      <textarea v-model="active.input" aria-label="消息" :disabled="active.busy" maxlength="16000" placeholder="输入消息，测试此智能体…（Shift+Enter 换行，Enter 发送）" @keydown.enter="enter" />
      <div><small>{{ editable ? '会话仅在内存保留，刷新页面后不恢复' : '当前使用已发布版本 · 每轮独立调用' }}</small>
        <button v-if="active.busy" class="aw-button" type="button" @click="stop(active)">停止生成</button>
        <button v-else class="aw-button primary" type="submit" :disabled="!active.input.trim() || active.expired">发送 ↵</button>
      </div>
    </form>
  </section>
</template>
<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { deleteDebugContext, releaseDebugContext, exportDebugContext, errorMessage, streamAgent } from '../../api/agents'
import AgentReferences from './AgentReferences.vue'
import { safeMarkdown } from './safeMarkdown'
const props = defineProps({ agentId: [Number, String], editable: Boolean, prologue: String, variables: { type: Array, default: () => [] }, beforeSend: Function })
let sequence = 0
const sessions = ref([]), activeId = ref(null), streamElement = ref(null)
const exporting = ref(false)
const controllers = new Map()
function newSession() {
  if (sessions.value.length >= 4) return
  const session = { id: ++sequence, title: `会话 ${sequence}`, contextId: null, messages: [], variables: {}, input: '', busy: false, error: '', expired: false }
  sessions.value.push(session); activeId.value = session.id
}
newSession()
const active = computed(() => sessions.value.find(s => s.id === activeId.value) || sessions.value[0])
const questions = computed(() => (props.prologue || '').split('\n').filter(l => /^\s*[-*] /.test(l)).map(l => l.replace(/^\s*[-*] /, '').trim()))
const greeting = computed(() => (props.prologue || '').split('\n').filter(l => !/^\s*[-*] /.test(l)).join('\n'))
function stop(session) { controllers.get(session.id)?.abort() }
async function exportSession() {
  const session = active.value
  if (!props.editable || exporting.value || session.busy || !session.contextId || !session.messages.length) return
  exporting.value = true; session.error = ''
  try {
    const blob = await exportDebugContext(props.agentId, session.contextId)
    const url = URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = url; link.download = `agent-${props.agentId}-session.json`
    try { document.body.append(link); link.click() }
    finally { link.remove(); setTimeout(() => URL.revokeObjectURL(url), 1000) }
  } catch (error) { session.error = errorMessage(error) }
  finally { exporting.value = false }
}
async function dispose(session) {
  stop(session)
  if (session.contextId) {
    try { await deleteDebugContext(props.agentId, session.contextId) } catch { /* Server TTL is the fallback after network loss. */ }
  }
}
async function closeSession(session) {
  // Remove only after a known context has been deleted, so a fifth slot cannot race its release.
  await dispose(session)
  sessions.value = sessions.value.filter(s => s.id !== session.id)
  if (!sessions.value.length) newSession()
  else if (activeId.value === session.id) activeId.value = sessions.value[0].id
}
async function resetSession() { await closeSession(active.value) }
function enter(event) { if (!event.shiftKey && !event.isComposing) { event.preventDefault(); send() } }
async function send(question) {
  const session = active.value
  const message = typeof question === 'string' ? question : session.input.trim()
  if (!message || session.busy || session.expired) return
  session.busy = true; session.error = ''
  const controller = new AbortController(); controllers.set(session.id, controller)
  let answer
  try {
    if (props.editable && props.beforeSend && !await props.beforeSend()) return
    if (controller.signal.aborted) return
    const values = {}
    for (const variable of props.variables) {
      const value = session.variables[variable.name] ?? variable.defaultValue ?? ''
      if (variable.required && !value.trim()) throw new Error(`请填写变量：${variable.label || variable.name}`)
      values[variable.name.trim()] = value
    }
    session.input = ''; if (!session.messages.length) session.title = message.slice(0, 10)
    session.messages.push({ role: 'user', content: message })
    session.messages.push({ role: 'assistant', content: '', citations: [] })
    answer = session.messages[session.messages.length - 1]
    await streamAgent(props.agentId, { message, variables: values, debugContextId: session.contextId }, {
      signal: controller.signal, published: !props.editable,
      onEvent(type, data) {
        if (type === 'context') session.contextId = data.debugContextId
        if (type === 'delta') answer.content += data.text || ''
        if (type === 'retrieval') answer.citations = data.citations || []
        if (type === 'usage') answer.usage = data
        nextTick(() => { if (streamElement.value) streamElement.value.scrollTop = streamElement.value.scrollHeight })
      },
    })
  } catch (error) {
    if (answer) answer.failed = true
    session.error = controller.signal.aborted ? '已停止生成，本轮未加入上下文' : errorMessage(error)
    if (error.status === 410) session.expired = true
  } finally { session.busy = false; controllers.delete(session.id) }
}
function pageHide() {
  for (const session of sessions.value) {
    stop(session)
    if (session.contextId) releaseDebugContext(props.agentId, session.contextId)
    session.contextId = null; session.messages = []; session.expired = false
  }
}
onMounted(() => window.addEventListener('pagehide', pageHide))
onBeforeUnmount(() => { window.removeEventListener('pagehide', pageHide); for (const session of sessions.value) void dispose(session) })
</script>
