// @vitest-environment happy-dom

import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import TenantApiCredentials from './TenantApiCredentials.vue'
import TenantApiDocs from './TenantApiDocs.vue'

const { get, post, error, success } = vi.hoisted(() => ({
  get: vi.fn(),
  post: vi.fn(),
  error: vi.fn(),
  success: vi.fn(),
}))

vi.mock('../api/http', () => ({ http: { get, post } }))
vi.mock('element-plus', () => ({ ElMessage: { error, success } }))

const credential = {
  id: '8797a05e-9d6c-4d47-a254-e648c8027ee9',
  name: '客服问答 Agent',
  credentialType: 'RAG_RETRIEVAL',
  description: null,
  environment: 'test',
  status: 'active',
  expiresAt: null,
  allowedIpCidrs: ['10.0.0.0/24'],
  requestsPerMinute: 60,
  burstCapacity: 10,
  maxConcurrency: 5,
  authorizationVersion: 1,
  displayPrefix: 'rag_test_k_7F3K',
  displayLastFour: '9vQ2',
  createdAt: '2026-08-28T06:32:00Z',
  revokedAt: null,
  lastUsedAt: null,
  lastUsedIp: null,
  knowledgeIds: ['d3b4fe35-cdd9-4a4d-914f-984a80bfeb9b'],
}

const knowledge = [
  { id: 9, publicId: 'd3b4fe35-cdd9-4a4d-914f-984a80bfeb9b', name: '产品与服务知识库', description: '产品说明' },
  { id: 10, publicId: 'a3e5987d-4125-469c-83be-d11f0f62a74d', name: '售后政策知识库', description: '售后政策' },
]

const routerLinkStub = {
  props: ['to'],
  template: '<a :href="typeof to === \'string\' ? to : to.path"><slot /></a>',
}

function mockLoads(list = [credential]) {
  get.mockImplementation((url) => {
    if (url === '/tenant/api-credentials') {
      return Promise.resolve({ data: { code: 200, data: list } })
    }
    if (url === '/knowledge/list') {
      return Promise.resolve({ data: { code: 200, data: knowledge } })
    }
    return Promise.reject(new Error(`Unexpected GET ${url}`))
  })
}

describe('tenant API credential list and creation', () => {
  beforeEach(() => {
    get.mockReset()
    post.mockReset()
    error.mockReset()
    success.mockReset()
    mockLoads([{ ...credential, apiKey: 'must-never-render-from-a-list-response' }])
  })

  it('loads the management list and renders only the safe Key identity fields', async () => {
    const wrapper = mount(TenantApiCredentials, { global: { stubs: { 'router-link': routerLinkStub } } })
    await flushPromises()

    expect(get).toHaveBeenCalledWith('/tenant/api-credentials')
    expect(wrapper.text()).toContain('客服问答 Agent')
    expect(wrapper.text()).toContain('RAG 检索')
    expect(wrapper.text()).toContain('rag_test_k_7F3K••••9vQ2')
    expect(wrapper.text()).toContain('启用')
    expect(wrapper.text()).not.toContain('must-never-render-from-a-list-response')
  })

  it('submits the exact create contract and keeps the returned Key in a non-dismissible result until saved', async () => {
    const rawKey = 'rag_test_k_7F3K9Q2M.xQ9vP3L2sK8mW5nR4tY7uA6bC1dE0fG'
    post.mockResolvedValueOnce({ data: { code: 200, data: { credential, apiKey: rawKey } } })
    const storageSpy = vi.spyOn(Storage.prototype, 'setItem')
    const wrapper = mount(TenantApiCredentials, { global: { stubs: { 'router-link': routerLinkStub } } })
    await flushPromises()

    await wrapper.get('[data-testid="open-create-credential"]').trigger('click')
    await wrapper.get('[name="credentialName"]').setValue('客服问答 Agent')
    await wrapper.get(`[value="${knowledge[0].publicId}"]`).setValue(true)
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    expect(post).toHaveBeenCalledWith('/tenant/api-credentials', {
      name: '客服问答 Agent',
      credentialType: 'RAG_RETRIEVAL',
      environment: 'test',
      knowledgeIds: ['d3b4fe35-cdd9-4a4d-914f-984a80bfeb9b'],
      allowedIpCidrs: ['10.0.0.0/24'],
      requestsPerMinute: 60,
      burstCapacity: 10,
      maxConcurrency: 5,
      expiresAt: null,
    })
    expect(wrapper.get('[role="dialog"][aria-labelledby="one-time-key-title"]').text()).toContain(rawKey)
    expect(wrapper.find('[aria-label="关闭一次性 API Key"]').exists()).toBe(false)
    expect(storageSpy).not.toHaveBeenCalled()

    await wrapper.get('.save-confirmation input').setValue(true)
    await wrapper.get('[data-testid="confirm-key-saved"]').trigger('click')
    expect(wrapper.text()).not.toContain(rawKey)
    storageSpy.mockRestore()
  })
})

describe('tenant API calling guide', () => {
  it('documents the strict request body and separates trusted-network HTTP testing from HTTPS production', () => {
    const wrapper = mount(TenantApiDocs)
    const requestExample = wrapper.get('[data-testid="retrieval-request-example"]').text()

    expect(requestExample).toContain('"query"')
    expect(requestExample).toContain('"retrieval_setting"')
    expect(requestExample).not.toContain('knowledge_id')
    expect(requestExample).not.toContain('metadata_condition')
    expect(wrapper.text()).toContain('knowledge_id')
    expect(wrapper.text()).toContain('metadata_condition')
    expect(wrapper.text()).toContain('HTTP 仅限可信网络测试')
    expect(wrapper.text()).toContain('生产环境必须使用 HTTPS')
  })
})
