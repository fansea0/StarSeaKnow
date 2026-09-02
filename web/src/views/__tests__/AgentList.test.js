import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, expect, it, vi } from 'vitest'
import Agent from '../Agent.vue'
import { http } from '../../api/http'
vi.mock('../../api/http', () => ({ http: { get: vi.fn(), post: vi.fn(), delete: vi.fn() } }))
vi.mock('../../api/authenticatedFetch', () => ({ authenticatedFetch: vi.fn() }))
vi.mock('../../stores/auth', () => ({ useAuthStore: () => ({ user: { role: 'tenant_admin' } }) }))
const push = vi.hoisted(() => vi.fn())
vi.mock('vue-router', () => ({ useRouter: () => ({ push }) }))
beforeEach(() => {
  vi.clearAllMocks()
  http.get.mockImplementation(path => Promise.resolve({ data: { code: 200, data: path === '/agents/metrics' ? { all: 1, published: 0, draftChanged: 0, debuggedThisWeek: 1 } : { items: [{ id: 3, name: '客服', description: '帮助团队', tags: ['RAG'], status: 'UNPUBLISHED', knowledgeCount: 2, modelConfigured: false }], page: 1, pageSize: 12, total: 1 } } }))
})
it('loads real status and knowledge count from the new aggregate API', async () => {
  const wrapper = mount(Agent, { global: { stubs: { RouterLink: { template: '<a><slot /></a>' } } } }); await flushPromises()
  expect(http.get).toHaveBeenCalledWith('/agents', expect.anything())
  expect(wrapper.text()).toContain('2 知识库')
  expect(wrapper.text()).toContain('未发布')
  expect(wrapper.text()).not.toContain('运行中')
  wrapper.unmount()
})
it('creates a valid initial draft then opens its model settings', async () => {
  http.post.mockResolvedValue({ data: { code: 200, data: { id: 17 } } })
  const wrapper = mount(Agent, { global: { stubs: { RouterLink: { template: '<a><slot /></a>' } } } }); await flushPromises()
  await wrapper.get('[data-testid="create-agent"]').trigger('click')
  await wrapper.get('[role="dialog"] input').setValue('客服')
  await wrapper.get('form[role="dialog"]').trigger('submit'); await flushPromises()
  expect(http.post.mock.calls[0][1].systemPrompt.trim().length).toBeGreaterThan(0)
  expect(push).toHaveBeenCalledWith('/agent/17')
  wrapper.unmount()
})
