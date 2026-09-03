import { http, apiUrl } from './http'
import { authenticatedFetch } from './authenticatedFetch'
import { useAuthStore } from '../stores/auth'

export function unwrap(response) {
  if (response?.data?.code !== 200) throw new Error(response?.data?.msg || '请求失败，请重试')
  return response.data.data
}
export const listAgents = params => http.get('/agents', { params }).then(unwrap)
export const agentMetrics = () => http.get('/agents/metrics').then(unwrap)
export const getAgent = id => http.get(`/agents/${id}`).then(unwrap)
export const createAgent = command => http.post('/agents', draftCommand(command)).then(unwrap)
export const saveAgent = (id, command) => http.put(`/agents/${id}/draft`, draftCommand(command)).then(unwrap)
export const deleteAgent = id => http.delete(`/agents/${id}`).then(unwrap)
export const listSnapshots = (id, page = 1) => http.get(`/agents/${id}/snapshots`, { params: { page, pageSize: 20 } }).then(unwrap)
export const getSnapshot = (id, version) => http.get(`/agents/${id}/snapshots/${version}`).then(unwrap)
export const publishAgent = (id, lockVersion, publishNote) => http.post(`/agents/${id}/publish`, { lockVersion, publishNote }).then(unwrap)
export const rollbackAgent = (id, version, lockVersion, publishNote) => http.post(`/agents/${id}/snapshots/${version}/rollback`, { lockVersion, publishNote }).then(unwrap)
export const deleteDebugContext = (id, context) => http.delete(`/agents/${id}/debug-contexts/${encodeURIComponent(context)}`).then(unwrap)
export async function exportDebugContext(id, context) {
  const response = await authenticatedFetch(apiUrl(`/agents/${id}/debug-contexts/${encodeURIComponent(context)}/export`), {
    method: 'GET', headers: { Accept: 'application/json' },
  })
  if (!response.ok) {
    const data = await response.json().catch(() => ({}))
    const error = new Error(data.msg || data.message || `导出失败（${response.status}）`)
    error.response = { status: response.status, data }
    throw error
  }
  return response.blob()
}
export function releaseDebugContext(id, context) {
  const token = useAuthStore().accessToken
  if (!token) return
  // Unload cannot await a refresh; keepalive lets an already authenticated delete finish after navigation.
  void fetch(apiUrl(`/agents/${id}/debug-contexts/${encodeURIComponent(context)}`), {
    method: 'DELETE', keepalive: true, credentials: 'include', headers: { Authorization: `Bearer ${token}` },
  }).catch(() => {})
}

export function draftCommand(value) {
  const model = value.model
  return {
    name: value.name || '', description: value.description || '', prologue: value.prologue || '',
    systemPrompt: value.systemPrompt || '', tags: value.tags || [], variables: (value.variables || []).map(v => ({ ...v, name: v.name.trim() })),
    knowledgeIds: value.knowledgeIds || [], retrievalTopK: value.retrievalTopK ?? 5,
    retrievalScoreThreshold: value.retrievalScoreThreshold ?? .7, lockVersion: value.lockVersion ?? null,
    model: model ? { providerConnectionId: model.providerConnectionId, modelId: model.modelId,
      temperature: model.temperature, topP: model.topP, maxTokens: model.maxTokens, timeoutSeconds: model.timeoutSeconds } : null,
  }
}

export function configuredModels(providers) {
  return providers.filter(p => p.configured && p.connectionId).flatMap(p =>
    (p.selectableModels || []).map(m => ({ providerConnectionId: p.connectionId, modelId: m.modelId, label: `${p.name} · ${m.displayName || m.modelId}` })))
}

export function errorMessage(error, fallback = '操作失败，请重试') {
  return error?.response?.data?.msg || error?.response?.data?.message || error?.message || fallback
}

// POST streaming is not supported by EventSource. Keep the parser incremental across UTF-8/chunk boundaries.
export async function streamAgent(id, command, { signal, onEvent, published = false } = {}) {
  const body = { message: command.message, variables: command.variables || {} }
  if (!published && command.debugContextId) body.debugContextId = command.debugContextId
  const response = await authenticatedFetch(apiUrl(`/agents/${id}/${published ? 'chat' : 'debug'}/stream`), {
    method: 'POST', signal, headers: { 'Content-Type': 'application/json', Accept: 'text/event-stream' }, body: JSON.stringify(body),
  })
  if (!response.ok) {
    const data = await response.json().catch(() => ({}))
    const error = new Error(data.msg || data.message || `请求失败（${response.status}）`)
    error.status = response.status
    throw error
  }
  if (!response.headers.get('content-type')?.includes('text/event-stream') || !response.body) throw new Error('未收到有效的流式响应')
  const reader = response.body.getReader()
  const decoder = new TextDecoder()
  let buffer = '', complete = false
  const dispatch = block => {
    let type = 'message'; const data = []
    for (const line of block.split(/\r\n|\r|\n/)) {
      if (line.startsWith('event:')) type = line.slice(6).trim()
      if (line.startsWith('data:')) data.push(line.slice(5).replace(/^ /, ''))
    }
    if (!data.length) return
    let payload
    try { payload = JSON.parse(data.join('\n')) } catch { throw new Error('流式响应格式无效') }
    onEvent?.(type, payload)
    if (type === 'error') throw new Error(payload.message || '模型执行失败')
    if (type === 'complete') complete = true
  }
  try {
    while (true) {
      const { value, done } = await reader.read()
      buffer += decoder.decode(value, { stream: !done })
      let separator
      while ((separator = /\r\n\r\n|\n\n|\r\r/.exec(buffer))) {
        dispatch(buffer.slice(0, separator.index))
        buffer = buffer.slice(separator.index + separator[0].length)
      }
      if (buffer.length > 1_048_576) throw new Error('响应数据过大，请重试')
      if (done) break
    }
    if (!complete) throw new Error('响应已中断，请重试；本轮未加入上下文')
  } finally {
    await reader.cancel().catch(() => {})
    reader.releaseLock()
  }
}
