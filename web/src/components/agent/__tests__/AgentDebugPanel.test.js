import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, expect, it, vi } from 'vitest'
import AgentDebugPanel from '../AgentDebugPanel.vue'
import { streamAgent, deleteDebugContext, releaseDebugContext, exportDebugContext } from '../../../api/agents'
vi.mock('../../../api/agents', () => ({ streamAgent: vi.fn(), deleteDebugContext: vi.fn(), releaseDebugContext: vi.fn(), exportDebugContext: vi.fn(), errorMessage: e => e.message }))
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

it('downloads only the selected session from the server and disables empty or generating sessions', async () => {
  let finish
  streamAgent.mockImplementation(async (id, command, options) => {
    options.onEvent('context', { debugContextId: command.message === '第一问' ? 'ctx-one' : 'ctx-two' })
    options.onEvent('delta', { text: '完整回答' })
    if (command.message === '第二问') await new Promise(resolve => { finish = resolve })
  })
  const blob = new Blob(['{"schemaVersion":1,"model":[],"turns":[]}'], { type: 'application/json' })
  exportDebugContext.mockResolvedValue(blob)
  const objectUrl = vi.spyOn(URL, 'createObjectURL').mockReturnValue('blob:session-export')
  const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {})
  const wrapper = mount(AgentDebugPanel, { props: { agentId: 3, editable: true } })
  try {
    expect(wrapper.get('.aw-session-tabs [data-testid="export-session"]').text()).toBe('下载')
    expect(wrapper.find('.aw-export-bar').exists()).toBe(false)
    expect(wrapper.get('[data-testid="export-session"]').element.disabled).toBe(true)
    await wrapper.get('textarea').setValue('第一问'); await wrapper.get('form').trigger('submit'); await flushPromises()
    await wrapper.get('[data-testid="export-session"]').trigger('click'); await flushPromises()
    expect(exportDebugContext).toHaveBeenLastCalledWith(3, 'ctx-one')
    expect(objectUrl).toHaveBeenCalledWith(blob)
    expect(click.mock.instances[0].download).toBe('agent-3-session.json')
    await wrapper.findAll('button').find(button => button.text() === '＋ 新会话').trigger('click')
    expect(wrapper.get('[data-testid="export-session"]').element.disabled).toBe(true)
    await wrapper.get('textarea').setValue('第二问'); await wrapper.get('form').trigger('submit'); await flushPromises()
    expect(wrapper.get('[data-testid="export-session"]').element.disabled).toBe(true)
    finish(); await flushPromises()
    await wrapper.get('[data-testid="export-session"]').trigger('click'); await flushPromises()
    expect(exportDebugContext).toHaveBeenLastCalledWith(3, 'ctx-two')
  } finally { wrapper.unmount(); objectUrl.mockRestore(); click.mockRestore() }
})

it('shows export errors without losing the conversation, and hides export for member chat', async () => {
  streamAgent.mockImplementation(async (id, command, options) => {
    options.onEvent('context', { debugContextId: 'ctx' }); options.onEvent('delta', { text: '保留回答' })
  })
  exportDebugContext.mockRejectedValue(new Error('没有可导出的已完成对话'))
  const wrapper = mount(AgentDebugPanel, { props: { agentId: 3, editable: true } })
  try {
    await wrapper.get('textarea').setValue('问题'); await wrapper.get('form').trigger('submit'); await flushPromises()
    await wrapper.get('[data-testid="export-session"]').trigger('click'); await flushPromises()
    expect(wrapper.get('[role="alert"]').text()).toContain('没有可导出')
    expect(wrapper.text()).toContain('保留回答')
    await wrapper.setProps({ editable: false })
    expect(wrapper.find('[data-testid="export-session"]').exists()).toBe(false)
  } finally { wrapper.unmount() }
})
