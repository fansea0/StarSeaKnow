// @vitest-environment happy-dom

import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import TenantApiCredentialDetail from './TenantApiCredentialDetail.vue'

const { get, patch, put, post, replace, error, success } = vi.hoisted(() => ({
  get: vi.fn(),
  patch: vi.fn(),
  put: vi.fn(),
  post: vi.fn(),
  replace: vi.fn(),
  error: vi.fn(),
  success: vi.fn(),
}))

vi.mock('../api/http', () => ({ http: { get, patch, put, post } }))
vi.mock('vue-router', () => ({
  useRoute: () => ({ params: { credentialId: '8797a05e-9d6c-4d47-a254-e648c8027ee9' } }),
  useRouter: () => ({ replace }),
  onBeforeRouteLeave: () => {},
}))
vi.mock('element-plus', () => ({ ElMessage: { error, success } }))

const credential = {
  id: '8797a05e-9d6c-4d47-a254-e648c8027ee9',
  name: '客服问答 Agent',
  credentialType: 'RAG_RETRIEVAL',
  description: '面向客服系统',
  environment: 'test',
  status: 'active',
  expiresAt: '2026-12-31T00:00:00Z',
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
  { id: 9, publicId: 'd3b4fe35-cdd9-4a4d-914f-984a80bfeb9b', name: '产品与服务知识库' },
  { id: 10, publicId: 'a3e5987d-4125-469c-83be-d11f0f62a74d', name: '售后政策知识库' },
]

const stubs = { 'router-link': { template: '<a><slot /></a>' } }

function mockLoads() {
  get.mockImplementation((url) => {
    if (url === `/tenant/api-credentials/${credential.id}`) {
      return Promise.resolve({ data: { code: 200, data: credential } })
    }
    if (url === '/knowledge/list') {
      return Promise.resolve({ data: { code: 200, data: knowledge } })
    }
    return Promise.reject(new Error(`Unexpected GET ${url}`))
  })
}

describe('tenant API credential detail', () => {
  beforeEach(() => {
    get.mockReset()
    patch.mockReset()
    put.mockReset()
    post.mockReset()
    replace.mockReset()
    error.mockReset()
    success.mockReset()
    mockLoads()
  })

  it('updates mutable metadata and completely replaces the public-UUID knowledge scope', async () => {
    patch.mockResolvedValueOnce({ data: { code: 200, data: { ...credential, name: '售后问答 Agent' } } })
    put.mockResolvedValueOnce({
      data: {
        code: 200,
        data: { ...credential, knowledgeIds: knowledge.map((item) => item.publicId), authorizationVersion: 2 },
      },
    })
    const wrapper = mount(TenantApiCredentialDetail, { global: { stubs } })
    await flushPromises()

    expect(wrapper.text()).toContain('RAG 检索')
    expect(wrapper.text()).toContain('rag_test_k_7F3K••••9vQ2')
    await wrapper.get('[name="credentialName"]').setValue('售后问答 Agent')
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    expect(patch).toHaveBeenCalledWith(`/tenant/api-credentials/${credential.id}`, {
      name: '售后问答 Agent',
      description: '面向客服系统',
      allowedIpCidrs: ['10.0.0.0/24'],
      requestsPerMinute: 60,
      burstCapacity: 10,
      maxConcurrency: 5,
      expiresAt: '2026-12-31T00:00:00Z',
    })

    await wrapper.get('[data-testid="open-scope-editor"]').trigger('click')
    await wrapper.get(`[value="${knowledge[1].publicId}"]`).setValue(true)
    await wrapper.get('[data-testid="save-credential-scope"]').trigger('click')
    await flushPromises()

    expect(put).toHaveBeenCalledWith(`/tenant/api-credentials/${credential.id}/knowledge-bases`, {
      knowledgeIds: [
        'd3b4fe35-cdd9-4a4d-914f-984a80bfeb9b',
        'a3e5987d-4125-469c-83be-d11f0f62a74d',
      ],
    })
  })

  it('rotates into the replacement credential, discloses the new Key once, and requires revoke confirmation', async () => {
    const rawKey = 'rag_test_k_NEWKEY.new-raw-secret-value'
    const rotated = {
      ...credential,
      id: '02e896a9-33b5-4f9e-b501-a843e6180a37',
      displayPrefix: 'rag_test_k_NEWA',
      displayLastFour: 'NEW4',
    }
    post
      .mockResolvedValueOnce({ data: { code: 200, data: { credential: rotated, apiKey: rawKey } } })
      .mockResolvedValueOnce({ data: { code: 200, data: { ...rotated, status: 'revoked' } } })
    const wrapper = mount(TenantApiCredentialDetail, { global: { stubs } })
    await flushPromises()

    await wrapper.get('[data-testid="open-rotate-confirmation"]').trigger('click')
    await wrapper.get('[data-testid="confirm-credential-rotation"]').trigger('click')
    await flushPromises()

    expect(post).toHaveBeenNthCalledWith(1, `/tenant/api-credentials/${credential.id}/rotate`)
    expect(replace).toHaveBeenCalledWith(`/tenant/api-credentials/${rotated.id}`)
    expect(wrapper.get('[role="dialog"][aria-labelledby="one-time-key-title"]').text()).toContain(rawKey)
    expect(wrapper.find('[aria-label="关闭一次性 API Key"]').exists()).toBe(false)

    await wrapper.get('.save-confirmation input').setValue(true)
    await wrapper.get('[data-testid="confirm-key-saved"]').trigger('click')
    expect(wrapper.text()).not.toContain(rawKey)

    await wrapper.get('[data-testid="open-revoke-confirmation"]').trigger('click')
    await wrapper.get('[data-testid="confirm-credential-revoke"]').trigger('click')
    await flushPromises()

    expect(post).toHaveBeenNthCalledWith(2, `/tenant/api-credentials/${rotated.id}/revoke`)
    expect(wrapper.text()).toContain('已吊销')
  })
})
