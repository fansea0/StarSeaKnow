import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import AgentDetail from '../AgentDetail.vue'
import { http } from '../../api/http'
vi.mock('../../api/http', () => ({ apiUrl: p => `/api${p}`, http: { get: vi.fn(), put: vi.fn(), post: vi.fn(), delete: vi.fn() } }))
vi.mock('../../stores/auth', () => ({ useAuthStore: () => ({ user: { role: 'tenant_admin' } }) }))
const routeGuards = vi.hoisted(() => ({ update: vi.fn(), leave: vi.fn() }))
vi.mock('vue-router', () => ({ useRoute: () => ({ params: { id: '3' } }), useRouter: () => ({ push: vi.fn() }), onBeforeRouteLeave: routeGuards.leave, onBeforeRouteUpdate: routeGuards.update }))
const detail = { id: 3, name: '客服', description: '', prologue: '欢迎', systemPrompt: '你是助手', tags: [], variables: [], knowledgeIds: [], retrievalTopK: 5, retrievalScoreThreshold: .7, model: null, status: 'UNPUBLISHED', lockVersion: 2, editable: true }
function page() { return mount(AgentDetail, { global: { stubs: { RouterLink: { template: '<a><slot /></a>' } }, mocks: { $route: { params: { id: '3' } }, $message: { success: vi.fn(), error: vi.fn() } } } }) }
beforeEach(() => {
  vi.clearAllMocks()
  http.get.mockImplementation(path => Promise.resolve({ data: { code: 200, data: path === '/agents/3' ? structuredClone(detail) : path === '/model-providers' ? [
    { configured: false, name: '不可选', selectableModels: [{ modelId: 'hidden' }] },
    { configured: true, connectionId: 7, name: '厂商', selectableModels: [{ modelId: 'model-b', displayName: '模型 B' }] },
  ] : [] } }))
  http.put.mockImplementation((path, command) => Promise.resolve({ data: { code: 200, data: { ...detail, ...command, lockVersion: 3 } } }))
})
describe('Agent workbench', () => {
  it('saves unsaved changes before a parameter-only route switch', async () => {
    const wrapper = page(); await flushPromises()
    await wrapper.get('#config-basic input').setValue('修改后的客服')
    expect(routeGuards.update).toHaveBeenCalled()
    await routeGuards.update.mock.calls[0][0]()
    expect(http.put).toHaveBeenCalledWith('/agents/3/draft', expect.objectContaining({ name: '修改后的客服' }))
    wrapper.unmount()
  })
  it('shows configured model selection and a quick configuration entry', async () => {
    const wrapper = page(); await flushPromises()
    expect(wrapper.find('[data-testid="agent-model-select"]').exists()).toBe(true)
    expect(wrapper.get('[data-testid="agent-model-select"]').text()).toContain('模型 B')
    expect(wrapper.get('[data-testid="agent-model-select"]').text()).not.toContain('不可选')
    expect(wrapper.text()).toContain('配置模型')
    await wrapper.get('[data-testid="agent-model-select"]').setValue('7:model-b')
    await wrapper.get('[data-testid="save-agent"]').trigger('click'); await flushPromises()
    expect(http.put).toHaveBeenCalledWith('/agents/3/draft', expect.objectContaining({ model: expect.objectContaining({ providerConnectionId: 7, modelId: 'model-b' }), lockVersion: 2 }))
    wrapper.unmount()
  })
  it('does not offer draft editing or tenant credentials to members', async () => {
    http.get.mockResolvedValue({ data: { code: 200, data: { ...detail, editable: false } } })
    const wrapper = page(); await flushPromises()
    expect(wrapper.find('[data-testid="save-agent"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="agent-model-select"]').exists()).toBe(false)
    expect(http.get.mock.calls.map(c => c[0])).not.toContain('/model-providers')
    wrapper.unmount()
  })
})
