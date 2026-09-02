import { describe, expect, it, vi } from 'vitest'
import { streamAgent, draftCommand, configuredModels } from '../agents'
import { authenticatedFetch } from '../authenticatedFetch'
vi.mock('../authenticatedFetch', () => ({ authenticatedFetch: vi.fn() }))

describe('agent workbench transport', () => {
  it('selects models only from configured tenant connections', () => {
    expect(configuredModels([
      { configured: false, name: 'Builtin', selectableModels: [{ modelId: 'a' }] },
      { configured: true, connectionId: 7, name: 'Custom', selectableModels: [{ modelId: 'b', displayName: 'B' }] },
    ])).toEqual([{ providerConnectionId: 7, modelId: 'b', label: 'Custom · B' }])
  })
  it('saves the exclusive model mapping, never legacy credentials or UI fields', () => {
    const command = draftCommand({ name: '客服', model: { id: 9, providerConnectionId: 7, modelId: 'b', temperature: .4, topP: 1, maxTokens: 2048, timeoutSeconds: 60 }, lockVersion: 2, modelApiKey: 'secret', editable: true })
    expect(command.model).toEqual({ providerConnectionId: 7, modelId: 'b', temperature: .4, topP: 1, maxTokens: 2048, timeoutSeconds: 60 })
    expect(command).not.toHaveProperty('modelApiKey')
    expect(command).not.toHaveProperty('editable')
  })
  it('parses fragmented SSE and sends only context id, current message and variables', async () => {
    const encoder = new TextEncoder()
    authenticatedFetch.mockResolvedValue(new Response(new ReadableStream({ start(c) {
      for (const text of ['event: context\r\ndata: {"debugContextId":"abc"}\r\n\r', '\nevent: delta\ndata: {"text":"你好"}\n\nevent: complete\ndata: {}\n\n']) c.enqueue(encoder.encode(text))
      c.close()
    } }), { headers: { 'Content-Type': 'text/event-stream' } }))
    const events = []
    await streamAgent(3, { message: '问', variables: {}, debugContextId: 'abc', history: ['never'] }, { onEvent: (type, data) => events.push([type, data]) })
    expect(events).toEqual([['context', { debugContextId: 'abc' }], ['delta', { text: '你好' }], ['complete', {}]])
    expect(JSON.parse(authenticatedFetch.mock.calls[0][1].body)).toEqual({ message: '问', variables: {}, debugContextId: 'abc' })
  })
  it('reports an incomplete stream instead of presenting a partial answer as complete', async () => {
    authenticatedFetch.mockResolvedValue(new Response('event: delta\ndata: {"text":"部分"}\n\n', { headers: { 'Content-Type': 'text/event-stream' } }))
    await expect(streamAgent(3, { message: '问' }, { onEvent: vi.fn() })).rejects.toThrow('中断')
  })
})
