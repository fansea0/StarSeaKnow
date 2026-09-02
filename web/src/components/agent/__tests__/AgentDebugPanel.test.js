import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, expect, it, vi } from 'vitest'
import AgentDebugPanel from '../AgentDebugPanel.vue'
import { streamAgent, deleteDebugContext, releaseDebugContext } from '../../../api/agents'
vi.mock('../../../api/agents', () => ({ streamAgent: vi.fn(), deleteDebugContext: vi.fn(), releaseDebugContext: vi.fn(), errorMessage: e => e.message }))
beforeEach(() => { vi.clearAllMocks(); deleteDebugContext.mockResolvedValue() })
it('reuses server context without resending history and deletes it on close', async () => {
  streamAgent.mockImplementation(async (id, cmd, options) => { options.onEvent('context', { debugContextId: 'ctx' }); options.onEvent('delta', { text: '<img src=x onerror=alert(1)>' }); options.onEvent('complete', {}) })
  const wrapper = mount(AgentDebugPanel, { props: { agentId: 3, editable: true, beforeSend: async () => true } })
  await wrapper.get('textarea').setValue('第一问'); await wrapper.get('form').trigger('submit'); await flushPromises()
  expect(wrapper.find('.aw-bubble img').exists()).toBe(false)
  await wrapper.get('textarea').setValue('第二问'); await wrapper.get('form').trigger('submit'); await flushPromises()
  expect(streamAgent.mock.calls[1][1]).toEqual({ message: '第二问', variables: {}, debugContextId: 'ctx' })
  await wrapper.get('[aria-label="关闭会话：第一问"]').trigger('click'); await flushPromises()
  expect(deleteDebugContext).toHaveBeenCalledWith(3, 'ctx')
  wrapper.unmount()
})
it('clears the visible conversation when explicitly resetting an expired context', async () => {
  streamAgent.mockRejectedValue(Object.assign(new Error('已过期'), { status: 410 }))
  const wrapper = mount(AgentDebugPanel, { props: { agentId: 3, editable: true } })
  await wrapper.get('textarea').setValue('失败问题'); await wrapper.get('form').trigger('submit'); await flushPromises()
  const reset = wrapper.findAll('button').find(b => b.text() === '新建上下文')
  await reset.trigger('click'); await flushPromises()
  expect(wrapper.findAll('.aw-message')).toHaveLength(0)
  wrapper.unmount()
})
it('normalizes existing variable names and releases contexts when the page is hidden', async () => {
  streamAgent.mockImplementation(async (id, cmd, options) => { options.onEvent('context', { debugContextId: 'ctx' }); options.onEvent('complete', {}) })
  const wrapper = mount(AgentDebugPanel, { props: { agentId: 3, editable: true, variables: [{ name: ' company ', label: '公司', defaultValue: '星海', required: true }] } })
  await wrapper.get('textarea').setValue('问题'); await wrapper.get('form').trigger('submit'); await flushPromises()
  expect(streamAgent.mock.calls[0][1].variables).toEqual({ company: '星海' })
  window.dispatchEvent(new Event('pagehide'))
  expect(releaseDebugContext).toHaveBeenCalledWith(3, 'ctx')
  wrapper.unmount()
})
