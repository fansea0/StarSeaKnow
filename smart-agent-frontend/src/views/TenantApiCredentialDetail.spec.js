// @vitest-environment happy-dom

import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import TenantApiCredentialDetail from './TenantApiCredentialDetail.vue'

const { get, patch, put, post, replace, error, success, routeLeaveHandlers, routeUpdateHandlers } = vi.hoisted(() => ({
  get: vi.fn(),
  patch: vi.fn(),
  put: vi.fn(),
  post: vi.fn(),
  replace: vi.fn(),
  error: vi.fn(),
  success: vi.fn(),
  routeLeaveHandlers: [],
  routeUpdateHandlers: [],
}))

vi.mock('../api/http', () => ({ http: { get, patch, put, post } }))
vi.mock('vue-router', () => ({
  useRoute: () => ({ params: { credentialId: '8797a05e-9d6c-4d47-a254-e648c8027ee9' } }),
  useRouter: () => ({ replace }),
  onBeforeRouteLeave: (handler) => routeLeaveHandlers.push(handler),
  onBeforeRouteUpdate: (handler) => routeUpdateHandlers.push(handler),
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

const credentialB = {
  ...credential,
  id: '11111111-2222-4333-8444-555555555555',
  name: 'B 租户检索 Agent',
  displayPrefix: 'rag_test_k_BBBB',
  displayLastFour: 'BBBB',
}

const knowledge = [
  { id: 9, publicId: 'd3b4fe35-cdd9-4a4d-914f-984a80bfeb9b', name: '产品与服务知识库' },
  { id: 10, publicId: 'a3e5987d-4125-469c-83be-d11f0f62a74d', name: '售后政策知识库' },
]

const stubs = { 'router-link': { template: '<a><slot /></a>' } }

function deferred() {
  let resolve
  const promise = new Promise((next) => { resolve = next })
  return { promise, resolve }
}

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
    routeLeaveHandlers.length = 0
    routeUpdateHandlers.length = 0
    mockLoads()
  })

  it('keeps metadata and lifecycle controls available when only knowledge loading fails', async () => {
    get.mockImplementation((url) => {
      if (url === `/tenant/api-credentials/${credential.id}`) return Promise.resolve({ data: { code: 200, data: credential } })
      if (url === '/knowledge/list') return Promise.reject({ response: { data: { msg: '知识库暂不可用' } } })
      return Promise.reject(new Error(`Unexpected GET ${url}`))
    })
    const wrapper = mount(TenantApiCredentialDetail, { attachTo: document.body, global: { stubs } })
    await flushPromises()

    expect(wrapper.get('[name="credentialName"]').exists()).toBe(true)
    expect(wrapper.get('[data-testid="open-rotate-confirmation"]').exists()).toBe(true)
    expect(wrapper.get('[data-testid="open-revoke-confirmation"]').exists()).toBe(true)
    expect(wrapper.get('[data-testid="knowledge-load-warning"]').text()).toContain('知识库暂不可用')
    expect(wrapper.get('[data-testid="open-scope-editor"]').attributes('disabled')).toBeDefined()
  })

  it('focuses the scope editor and restores its opener when Escape closes it', async () => {
    const wrapper = mount(TenantApiCredentialDetail, { attachTo: document.body, global: { stubs } })
    await flushPromises()
    const opener = wrapper.get('[data-testid="open-scope-editor"]')
    opener.element.focus()
    await opener.trigger('click')
    await flushPromises()
    const dialog = wrapper.get('[role="dialog"][aria-labelledby="scope-editor-title"]')

    expect(document.activeElement).toBe(wrapper.get('[role="dialog"][aria-labelledby="scope-editor-title"] input[type="checkbox"]').element)
    await dialog.trigger('keydown', { key: 'Escape' })
    expect(wrapper.find('[role="dialog"][aria-labelledby="scope-editor-title"]').exists()).toBe(false)
    expect(document.activeElement).toBe(opener.element)
    wrapper.unmount()
  })

  it('focuses a usable close control when the scope editor has no knowledge checkboxes', async () => {
    get.mockImplementation((url) => {
      if (url === `/tenant/api-credentials/${credential.id}`) return Promise.resolve({ data: { code: 200, data: credential } })
      if (url === '/knowledge/list') return Promise.resolve({ data: { code: 200, data: [] } })
      return Promise.reject(new Error(`Unexpected GET ${url}`))
    })
    const wrapper = mount(TenantApiCredentialDetail, { attachTo: document.body, global: { stubs } })
    await flushPromises()
    await wrapper.get('[data-testid="open-scope-editor"]').trigger('click')
    await flushPromises()

    expect(document.activeElement).toBe(wrapper.get('[aria-label="关闭范围编辑"]').element)
    wrapper.unmount()
  })

  it('keeps management controls active while knowledge loads and enables scope editing only after resolution', async () => {
    const initialKnowledge = deferred()
    get.mockImplementation((url) => {
      if (url === `/tenant/api-credentials/${credential.id}`) return Promise.resolve({ data: { code: 200, data: credential } })
      if (url === '/knowledge/list') return initialKnowledge.promise
      return Promise.reject(new Error(`Unexpected GET ${url}`))
    })
    const wrapper = mount(TenantApiCredentialDetail, { global: { stubs } })
    await flushPromises()

    expect(wrapper.get('[name="credentialName"]').exists()).toBe(true)
    expect(wrapper.get('[data-testid="open-rotate-confirmation"]').exists()).toBe(true)
    expect(wrapper.get('[data-testid="open-revoke-confirmation"]').exists()).toBe(true)
    expect(wrapper.get('[data-testid="open-scope-editor"]').attributes('disabled')).toBeDefined()
    expect(wrapper.get('[data-testid="knowledge-loading-state"]').exists()).toBe(true)
    initialKnowledge.resolve({ data: { code: 200, data: knowledge } })
    await flushPromises()
    expect(wrapper.get('[data-testid="open-scope-editor"]').attributes('disabled')).toBeUndefined()
  })

  it('keeps scope editing disabled while a failed knowledge request is retried', async () => {
    const retryKnowledge = deferred()
    let knowledgeAttempts = 0
    get.mockImplementation((url) => {
      if (url === `/tenant/api-credentials/${credential.id}`) return Promise.resolve({ data: { code: 200, data: credential } })
      if (url === '/knowledge/list') return ++knowledgeAttempts === 1
        ? Promise.reject({ response: { data: { msg: '稍后重试' } } })
        : retryKnowledge.promise
      return Promise.reject(new Error(`Unexpected GET ${url}`))
    })
    const wrapper = mount(TenantApiCredentialDetail, { global: { stubs } })
    await flushPromises()
    await wrapper.get('[data-testid="retry-knowledge-load"]').trigger('click')
    await wrapper.vm.$nextTick()
    expect(wrapper.get('[data-testid="open-scope-editor"]').attributes('disabled')).toBeDefined()
    expect(knowledgeAttempts).toBeGreaterThan(1)
    expect(wrapper.get('[data-testid="knowledge-loading-state"]').exists()).toBe(true)
    retryKnowledge.resolve({ data: { code: 200, data: knowledge } })
    await flushPromises()
    expect(wrapper.get('[data-testid="open-scope-editor"]').attributes('disabled')).toBeUndefined()
  })

  it('keeps the active dialog outside the hidden background and restores background semantics when it closes', async () => {
    const wrapper = mount(TenantApiCredentialDetail, { attachTo: document.body, global: { stubs } })
    await flushPromises()
    await wrapper.get('[data-testid="open-scope-editor"]').trigger('click')
    await flushPromises()
    const dialog = wrapper.get('[role="dialog"][aria-labelledby="scope-editor-title"]')
    const background = wrapper.get('[data-testid="credential-detail-background"]')

    expect(dialog.element.closest('[aria-hidden="true"]')).toBeNull()
    expect(background.attributes('aria-hidden')).toBe('true')
    expect(background.attributes()).toHaveProperty('inert')
    await dialog.trigger('keydown', { key: 'Escape' })
    expect(background.attributes('aria-hidden')).toBeUndefined()
    expect(background.attributes()).not.toHaveProperty('inert')
    wrapper.unmount()
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

  it('explicitly disables and re-enables a non-revoked credential', async () => {
    patch
      .mockResolvedValueOnce({ data: { code: 200, data: { ...credential, status: 'disabled' } } })
      .mockResolvedValueOnce({ data: { code: 200, data: { ...credential, status: 'active' } } })
    const wrapper = mount(TenantApiCredentialDetail, { global: { stubs } })
    await flushPromises()

    await wrapper.get('[data-testid="toggle-credential-status"]').trigger('click')
    await flushPromises()
    expect(patch).toHaveBeenNthCalledWith(1, `/tenant/api-credentials/${credential.id}`, { status: 'disabled' })
    expect(wrapper.get('[data-testid="toggle-credential-status"]').text()).toContain('启用')

    await wrapper.get('[data-testid="toggle-credential-status"]').trigger('click')
    await flushPromises()
    expect(patch).toHaveBeenNthCalledWith(2, `/tenant/api-credentials/${credential.id}`, { status: 'active' })
  })

  it('omits unchanged expiry but sends explicit null when the user clears it', async () => {
    patch.mockResolvedValue({ data: { code: 200, data: credential } })
    const wrapper = mount(TenantApiCredentialDetail, { global: { stubs } })
    await flushPromises()

    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(patch.mock.calls[0][1]).not.toHaveProperty('expiresAt')

    await wrapper.get('input[placeholder="2026-12-31T00:00:00Z"]').setValue('')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(patch.mock.calls[1][1]).toHaveProperty('expiresAt', null)
  })

  it('rotates into the replacement credential and discloses the new Key until acknowledgement', async () => {
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
    const wrapper = mount(TenantApiCredentialDetail, { attachTo: document.body, global: { stubs } })
    await flushPromises()

    await wrapper.get('[data-testid="open-rotate-confirmation"]').trigger('click')
    await wrapper.get('[data-testid="confirm-credential-rotation"]').trigger('click')
    await flushPromises()

    expect(post).toHaveBeenNthCalledWith(1, `/tenant/api-credentials/${credential.id}/rotate`)
    expect(replace).not.toHaveBeenCalled()
    expect(wrapper.get('[role="dialog"][aria-labelledby="one-time-key-title"]').text()).toContain(rawKey)
    expect(wrapper.find('[aria-label="关闭一次性 API Key"]').exists()).toBe(false)
    expect(document.activeElement).toBe(wrapper.get('[data-testid="copy-one-time-key"]').element)
    await wrapper.get('[role="dialog"][aria-labelledby="one-time-key-title"]').trigger('keydown', { key: 'Escape' })
    expect(wrapper.text()).toContain(rawKey)

    await wrapper.get('.save-confirmation input').setValue(true)
    await wrapper.get('[data-testid="confirm-key-saved"]').trigger('click')
    expect(wrapper.text()).not.toContain(rawKey)
    expect(replace).toHaveBeenCalledWith(`/tenant/api-credentials/${rotated.id}`)
  })

  it('drops a late rotation response after route leave without navigating or disclosing the Key', async () => {
    const rawKey = 'rag_test_k_LATEROTATE.raw-secret-value'
    const rotateResponse = deferred()
    post.mockReturnValueOnce(rotateResponse.promise)
    const wrapper = mount(TenantApiCredentialDetail, { global: { stubs } })
    await flushPromises()
    await wrapper.get('[data-testid="open-rotate-confirmation"]').trigger('click')
    await wrapper.get('[data-testid="confirm-credential-rotation"]').trigger('click')

    routeLeaveHandlers.forEach((handler) => handler())
    rotateResponse.resolve({ data: { code: 200, data: { credential: { ...credential, id: '02e896a9-33b5-4f9e-b501-a843e6180a37' }, apiKey: rawKey } } })
    await flushPromises()

    expect(replace).not.toHaveBeenCalled()
    expect(wrapper.text()).not.toContain(rawKey)
    expect(wrapper.find('[role="dialog"][aria-labelledby="one-time-key-title"]').exists()).toBe(false)
  })

  it('also invalidates a late rotation response when the detail component unmounts', async () => {
    const rotateResponse = deferred()
    post.mockReturnValueOnce(rotateResponse.promise)
    const wrapper = mount(TenantApiCredentialDetail, { global: { stubs } })
    await flushPromises()
    await wrapper.get('[data-testid="open-rotate-confirmation"]').trigger('click')
    await wrapper.get('[data-testid="confirm-credential-rotation"]').trigger('click')

    wrapper.unmount()
    rotateResponse.resolve({ data: { code: 200, data: { credential: { ...credential, id: '02e896a9-33b5-4f9e-b501-a843e6180a37' }, apiKey: 'rag_test_k_UNMOUNTED.raw-secret-value' } } })
    await flushPromises()

    expect(replace).not.toHaveBeenCalled()
  })

  it('prevents overlapping dialogs while rotation is pending or a secret is disclosed', async () => {
    const rotateResponse = deferred()
    post.mockReturnValueOnce(rotateResponse.promise)
    const wrapper = mount(TenantApiCredentialDetail, { global: { stubs } })
    await flushPromises()
    await wrapper.get('[data-testid="open-rotate-confirmation"]').trigger('click')
    await wrapper.get('[data-testid="confirm-credential-rotation"]').trigger('click')
    await wrapper.get('[data-testid="open-revoke-confirmation"]').trigger('click')
    await wrapper.get('[data-testid="open-scope-editor"]').trigger('click')
    expect(wrapper.find('[role="dialog"][aria-labelledby="revoke-title"]').exists()).toBe(false)
    expect(wrapper.find('[role="dialog"][aria-labelledby="scope-editor-title"]').exists()).toBe(false)

    rotateResponse.resolve({ data: { code: 200, data: { credential: { ...credential, id: '02e896a9-33b5-4f9e-b501-a843e6180a37' }, apiKey: 'rag_test_k_EXCLUSIVE.raw-secret-value' } } })
    await flushPromises()
    expect(wrapper.find('[role="dialog"][aria-labelledby="rotate-title"]').exists()).toBe(false)
    expect(wrapper.find('[role="dialog"][aria-labelledby="revoke-title"]').exists()).toBe(false)
    expect(wrapper.get('[role="dialog"][aria-labelledby="one-time-key-title"]').exists()).toBe(true)
    await wrapper.get('[data-testid="open-revoke-confirmation"]').trigger('click')
    expect(wrapper.find('[role="dialog"][aria-labelledby="revoke-title"]').exists()).toBe(false)
  })

  it('clears a secret and reloads the detail when the credential route parameter updates', async () => {
    const rawKey = 'rag_test_k_ROUTEUPDATE.raw-secret-value'
    post.mockResolvedValueOnce({ data: { code: 200, data: { credential: { ...credential, id: '02e896a9-33b5-4f9e-b501-a843e6180a37' }, apiKey: rawKey } } })
    const wrapper = mount(TenantApiCredentialDetail, { global: { stubs } })
    await flushPromises()
    await wrapper.get('[data-testid="open-rotate-confirmation"]').trigger('click')
    await wrapper.get('[data-testid="confirm-credential-rotation"]').trigger('click')
    await flushPromises()
    routeUpdateHandlers.forEach((handler) => handler({ params: { credentialId: '02e896a9-33b5-4f9e-b501-a843e6180a37' } }))
    await flushPromises()

    expect(wrapper.text()).not.toContain(rawKey)
    expect(get).toHaveBeenCalledWith('/tenant/api-credentials/02e896a9-33b5-4f9e-b501-a843e6180a37')
  })

  it('does not let a late A detail load overwrite the B route', async () => {
    const loadA = deferred()
    const loadB = deferred()
    get.mockImplementation((url) => {
      if (url === `/tenant/api-credentials/${credential.id}`) return loadA.promise
      if (url === `/tenant/api-credentials/${credentialB.id}`) return loadB.promise
      if (url === '/knowledge/list') return Promise.resolve({ data: { code: 200, data: knowledge } })
      return Promise.reject(new Error(`Unexpected GET ${url}`))
    })
    const wrapper = mount(TenantApiCredentialDetail, { global: { stubs } })
    await flushPromises()
    routeUpdateHandlers.forEach((handler) => handler({ params: { credentialId: credentialB.id } }))
    loadB.resolve({ data: { code: 200, data: credentialB } })
    await flushPromises()
    loadA.resolve({ data: { code: 200, data: credential } })
    await flushPromises()

    expect(wrapper.text()).toContain('B 租户检索 Agent')
    expect(wrapper.text()).not.toContain('客服问答 Agent')
  })

  it('does not let a late A knowledge result replace B scope options', async () => {
    const knowledgeA = deferred()
    const knowledgeB = deferred()
    let knowledgeCalls = 0
    const bKnowledge = [{ id: 11, publicId: 'b3b4fe35-cdd9-4a4d-914f-984a80bfeb9b', name: 'B 知识库' }]
    get.mockImplementation((url) => {
      if (url === `/tenant/api-credentials/${credential.id}`) return Promise.resolve({ data: { code: 200, data: credential } })
      if (url === `/tenant/api-credentials/${credentialB.id}`) return Promise.resolve({ data: { code: 200, data: credentialB } })
      if (url === '/knowledge/list') return ++knowledgeCalls === 1 ? knowledgeA.promise : knowledgeB.promise
      return Promise.reject(new Error(`Unexpected GET ${url}`))
    })
    const wrapper = mount(TenantApiCredentialDetail, { global: { stubs } })
    await flushPromises()
    routeUpdateHandlers.forEach((handler) => handler({ params: { credentialId: credentialB.id } }))
    knowledgeB.resolve({ data: { code: 200, data: bKnowledge } })
    await flushPromises()
    knowledgeA.resolve({ data: { code: 200, data: knowledge } })
    await flushPromises()
    await wrapper.get('[data-testid="open-scope-editor"]').trigger('click')

    expect(wrapper.find(`[value="${knowledge[0].publicId}"]`).exists()).toBe(false)
    expect(wrapper.get(`[value="${bKnowledge[0].publicId}"]`).exists()).toBe(true)
  })

  it('closes A dialogs and ignores an A scope mutation after routing to B', async () => {
    const scopeResponse = deferred()
    put.mockReturnValueOnce(scopeResponse.promise)
    get.mockImplementation((url) => {
      if (url === `/tenant/api-credentials/${credential.id}`) return Promise.resolve({ data: { code: 200, data: credential } })
      if (url === `/tenant/api-credentials/${credentialB.id}`) return Promise.resolve({ data: { code: 200, data: credentialB } })
      if (url === '/knowledge/list') return Promise.resolve({ data: { code: 200, data: knowledge } })
      return Promise.reject(new Error(`Unexpected GET ${url}`))
    })
    const wrapper = mount(TenantApiCredentialDetail, { attachTo: document.body, global: { stubs } })
    await flushPromises()
    const opener = wrapper.get('[data-testid="open-scope-editor"]')
    opener.element.focus()
    await opener.trigger('click')
    await wrapper.get('[data-testid="save-credential-scope"]').trigger('click')
    routeUpdateHandlers.forEach((handler) => handler({ params: { credentialId: credentialB.id } }))
    await flushPromises()
    scopeResponse.resolve({ data: { code: 200, data: { ...credential, name: 'A 的迟到范围' } } })
    await flushPromises()

    expect(wrapper.find('[role="dialog"][aria-labelledby="scope-editor-title"]').exists()).toBe(false)
    expect(put).toHaveBeenCalledTimes(1)
    expect(wrapper.text()).toContain('B 租户检索 Agent')
    expect(wrapper.text()).not.toContain('A 的迟到范围')
    expect(document.activeElement).not.toBe(opener.element)
  })

  it('drops a late A rotation after routing to B without opening a secret or navigating', async () => {
    const rotateResponse = deferred()
    post.mockReturnValueOnce(rotateResponse.promise)
    get.mockImplementation((url) => {
      if (url === `/tenant/api-credentials/${credential.id}`) return Promise.resolve({ data: { code: 200, data: credential } })
      if (url === `/tenant/api-credentials/${credentialB.id}`) return Promise.resolve({ data: { code: 200, data: credentialB } })
      if (url === '/knowledge/list') return Promise.resolve({ data: { code: 200, data: knowledge } })
      return Promise.reject(new Error(`Unexpected GET ${url}`))
    })
    const wrapper = mount(TenantApiCredentialDetail, { global: { stubs } })
    await flushPromises()
    await wrapper.get('[data-testid="open-rotate-confirmation"]').trigger('click')
    await wrapper.get('[data-testid="confirm-credential-rotation"]').trigger('click')
    routeUpdateHandlers.forEach((handler) => handler({ params: { credentialId: credentialB.id } }))
    await flushPromises()

    rotateResponse.resolve({ data: { code: 200, data: { credential: { ...credential, id: '02e896a9-33b5-4f9e-b501-a843e6180a37' }, apiKey: 'rag_test_k_STALE-ROTATION.raw-secret' } } })
    await flushPromises()

    expect(wrapper.text()).toContain('B 租户检索 Agent')
    expect(wrapper.text()).not.toContain('rag_test_k_STALE-ROTATION.raw-secret')
    expect(wrapper.find('[role="dialog"][aria-labelledby="one-time-key-title"]').exists()).toBe(false)
    expect(replace).not.toHaveBeenCalled()
  })
})
