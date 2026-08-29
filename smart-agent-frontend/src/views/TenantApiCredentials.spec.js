// @vitest-environment happy-dom

import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import TenantApiCredentials from './TenantApiCredentials.vue'
import TenantApiDocs from './TenantApiDocs.vue'

const { get, post, error, success, routeLeaveHandlers } = vi.hoisted(() => ({
  get: vi.fn(),
  post: vi.fn(),
  error: vi.fn(),
  success: vi.fn(),
  routeLeaveHandlers: [],
}))

vi.mock('../api/http', () => ({ http: { get, post } }))
vi.mock('vue-router', () => ({ onBeforeRouteLeave: (handler) => routeLeaveHandlers.push(handler) }))
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

function deferred() {
  let resolve
  const promise = new Promise((next) => { resolve = next })
  return { promise, resolve }
}

describe('tenant API credential list and creation', () => {
  beforeEach(() => {
    get.mockReset()
    post.mockReset()
    error.mockReset()
    success.mockReset()
    routeLeaveHandlers.length = 0
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

  it('keeps the credential list usable when knowledge loading fails', async () => {
    get.mockImplementation((url) => {
      if (url === '/tenant/api-credentials') return Promise.resolve({ data: { code: 200, data: [credential] } })
      if (url === '/knowledge/list') return Promise.reject({ response: { data: { msg: '知识库暂不可用' } } })
      return Promise.reject(new Error(`Unexpected GET ${url}`))
    })
    const wrapper = mount(TenantApiCredentials, { global: { stubs: { 'router-link': routerLinkStub } } })
    await flushPromises()

    expect(wrapper.text()).toContain('客服问答 Agent')
    expect(wrapper.get('[data-testid="knowledge-load-warning"]').text()).toContain('知识库暂不可用')
    expect(wrapper.get('[data-testid="retry-knowledge-load"]').exists()).toBe(true)
    await wrapper.get('[data-testid="open-create-credential"]').trigger('click')
    expect(wrapper.get('[data-testid="knowledge-scope-unavailable"]').exists()).toBe(true)
  })

  it('moves focus into the create dialog, traps tab navigation, and restores its opener on Escape', async () => {
    const wrapper = mount(TenantApiCredentials, { attachTo: document.body, global: { stubs: { 'router-link': routerLinkStub } } })
    await flushPromises()
    const opener = wrapper.get('[data-testid="open-create-credential"]')
    opener.element.focus()
    await opener.trigger('click')
    await flushPromises()
    const dialog = wrapper.get('[role="dialog"][aria-labelledby="create-credential-title"]')

    expect(document.activeElement).toBe(wrapper.get('[name="credentialName"]').element)
    wrapper.get('[data-testid="submit-create-credential"]').element.focus()
    await dialog.trigger('keydown', { key: 'Tab' })
    expect(document.activeElement).toBe(wrapper.get('[aria-label="关闭创建凭证"]').element)
    await dialog.trigger('keydown', { key: 'Escape' })
    expect(wrapper.find('[role="dialog"][aria-labelledby="create-credential-title"]').exists()).toBe(false)
    expect(document.activeElement).toBe(opener.element)
    wrapper.unmount()
  })

  it('keeps the active create dialog outside the hidden background and restores background semantics on close', async () => {
    const wrapper = mount(TenantApiCredentials, { attachTo: document.body, global: { stubs: { 'router-link': routerLinkStub } } })
    await flushPromises()
    await wrapper.get('[data-testid="open-create-credential"]').trigger('click')
    await flushPromises()
    const dialog = wrapper.get('[role="dialog"][aria-labelledby="create-credential-title"]')
    const background = wrapper.get('[data-testid="credential-page-background"]')

    expect(dialog.element.closest('[aria-hidden="true"]')).toBeNull()
    expect(background.attributes('aria-hidden')).toBe('true')
    expect(background.attributes()).toHaveProperty('inert')
    await dialog.trigger('keydown', { key: 'Escape' })
    expect(background.attributes('aria-hidden')).toBeUndefined()
    expect(background.attributes()).not.toHaveProperty('inert')
    wrapper.unmount()
  })

  it('submits the exact create contract and keeps the returned Key in a non-dismissible result until saved', async () => {
    const rawKey = 'rag_test_k_7F3K9Q2M.xQ9vP3L2sK8mW5nR4tY7uA6bC1dE0fG'
    post.mockResolvedValueOnce({ data: { code: 200, data: { credential, apiKey: rawKey } } })
    const storageSpy = vi.spyOn(Storage.prototype, 'setItem')
    const wrapper = mount(TenantApiCredentials, { global: { stubs: { 'router-link': routerLinkStub } } })
    await flushPromises()

    await wrapper.get('[data-testid="open-create-credential"]').trigger('click')
    await flushPromises()
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

  it('drops a late create response after route leave so its raw Key is never disclosed', async () => {
    const rawKey = 'rag_test_k_LATECREATE.raw-secret-value'
    const createResponse = deferred()
    post.mockReturnValueOnce(createResponse.promise)
    const wrapper = mount(TenantApiCredentials, { global: { stubs: { 'router-link': routerLinkStub } } })
    await flushPromises()
    await wrapper.get('[data-testid="open-create-credential"]').trigger('click')
    await wrapper.get('[name="credentialName"]').setValue('迟到响应')
    await wrapper.get('form').trigger('submit')

    routeLeaveHandlers.forEach((handler) => handler())
    createResponse.resolve({ data: { code: 201, data: { credential, apiKey: rawKey } } })
    await flushPromises()

    expect(wrapper.text()).not.toContain(rawKey)
    expect(wrapper.find('[role="dialog"][aria-labelledby="one-time-key-title"]').exists()).toBe(false)
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
    expect(wrapper.text()).toContain('/openapi/v1/retrieval')
  })

  it('shows successful, empty, and error response formats with stable fields', () => {
    const wrapper = mount(TenantApiDocs)

    expect(wrapper.get('[data-testid="retrieval-success-response"]').text()).toContain('"records"')
    expect(wrapper.get('[data-testid="retrieval-success-response"]').text()).toContain('"document_id"')
    expect(wrapper.get('[data-testid="retrieval-success-response"]').text()).toContain('"chunk_id"')
    expect(wrapper.get('[data-testid="retrieval-empty-response"]').text()).toContain('"records": []')
    expect(wrapper.get('[data-testid="retrieval-error-response"]').text()).toContain('"request_id"')
    expect(wrapper.get('[data-testid="retrieval-error-response"]').text()).toContain('"error"')
    expect(wrapper.text()).toContain('X-Request-ID')
  })
})
