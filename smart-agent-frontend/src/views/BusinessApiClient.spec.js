// @vitest-environment happy-dom

import { flushPromises, mount } from '@vue/test-utils'
import { createPinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import Knowledge from './Knowledge.vue'
import Agent from './Agent.vue'
import AgentDetail from './AgentDetail.vue'
import KnowledgeDetail from './KnowledgeDetail.vue'
import MdConverter from './MdConverter.vue'

const { get, post, put, legacyGet } = vi.hoisted(() => ({
  get: vi.fn(),
  post: vi.fn(),
  put: vi.fn(),
  legacyGet: vi.fn(),
}))

vi.mock('../api/http', () => ({
  apiUrl: path => `/api${path}`,
  http: { get },
}))
vi.mock('axios', () => ({ default: { get: legacyGet, post, put } }))
vi.mock('../stores/auth', () => ({ useAuthStore: () => ({ accessToken: 'stream-token' }) }))

const stubs = {
  'el-button': { template: '<button v-bind="$attrs" @click="$emit(\'click\')"><slot /></button>' },
  'el-card': { template: '<section><slot /></section>' },
  'el-dialog': { template: '<section><slot /><slot name="footer" /></section>' },
  'el-form': { template: '<form><slot /></form>' },
  'el-form-item': { template: '<div><slot /></div>' },
  'el-input': { template: '<input v-bind="$attrs" />' },
  'el-icon': { template: '<i><slot /></i>' },
  'el-upload': { template: '<div><slot /></div>' },
  'el-menu': { template: '<nav><slot /></nav>' },
  'el-menu-item': { template: '<div><slot /></div>' },
  'el-table': { template: '<div><slot /></div>' },
  'el-table-column': true,
  'el-switch': true,
  'el-tooltip': { template: '<span><slot /></span>' },
  'el-select': { template: '<select><slot /></select>' },
  'el-option': { template: '<option><slot /></option>' },
}

const message = { success: vi.fn(), error: vi.fn(), warning: vi.fn() }

describe('business list API client', () => {
  beforeEach(() => {
    get.mockReset()
    post.mockReset()
    put.mockReset()
    legacyGet.mockReset()
    message.success.mockReset()
    message.error.mockReset()
    message.warning.mockReset()
    get.mockResolvedValue({ data: { code: 200, data: [] } })
    legacyGet.mockResolvedValue({ data: { code: 200, data: [] } })
    post.mockResolvedValue({ data: { code: 200, data: '/tmp/output.md' } })
    put.mockResolvedValue({ data: { code: 200 } })
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ body: null }))
  })

  it('loads knowledge through the configured API client', async () => {
    mount(Knowledge, { global: { stubs, mocks: { $message: { error: vi.fn() } } } })
    await flushPromises()

    expect(get).toHaveBeenCalledWith('/knowledge/list/vo')
    expect(legacyGet).not.toHaveBeenCalled()
  })

  it('loads agents through the configured API client', async () => {
    mount(Agent, { global: { stubs, mocks: { $message: { error: vi.fn() } } } })
    await flushPromises()

    expect(get).toHaveBeenCalledWith('/agent/list')
    expect(legacyGet).not.toHaveBeenCalled()
  })

  it('keeps the agent save request URL and payload when saving from the workbench', async () => {
    const wrapper = mount(AgentDetail, {
      global: {
        stubs,
        mocks: { $route: { params: { id: '7' } }, $message: message },
      },
    })
    await flushPromises()
    wrapper.vm.agentInfo = { name: '海图助手', description: '检索资料', prologue: '你好', roleDescription: '研究员' }

    await wrapper.get('[data-testid="save-agent"]').trigger('click')

    expect(put).toHaveBeenCalledWith('/api/agent/update/7', {
      name: '海图助手', description: '检索资料', prologue: '你好', roleDescription: '研究员'
    })
  })

  it('keeps the agent chat request URL and prompt when sending from the preview', async () => {
    const wrapper = mount(AgentDetail, {
      global: {
        stubs,
        mocks: { $route: { params: { id: '7' } }, $message: message },
      },
    })
    await flushPromises()
    wrapper.vm.chatId = 'chat-42'
    wrapper.vm.inputMsg = '请总结上传文档'

    await wrapper.get('[data-testid="send-message"]').trigger('click')

    expect(fetch).toHaveBeenCalledWith('/api/ai/agent/chat?chatId=chat-42&agentId=7', expect.objectContaining({
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Authorization: 'Bearer stream-token',
      },
      body: JSON.stringify({ prompt: '请总结上传文档' }),
    }))
  })

  it('does not start a second agent request while the first reply is still streaming', async () => {
    let resolveFirstChunk
    let readCount = 0
    const firstResponse = {
      ok: true,
      body: {
        getReader: () => ({
          read: () => {
            if (readCount++ === 0) {
              return new Promise(resolve => { resolveFirstChunk = resolve })
            }
            return Promise.resolve({ done: true })
          },
        }),
      },
    }
    fetch.mockResolvedValueOnce(firstResponse).mockResolvedValueOnce({ ok: true, body: { getReader: () => ({ read: async () => ({ done: true }) }) } })
    const wrapper = mount(AgentDetail, {
      global: {
        stubs,
        mocks: { $route: { params: { id: '7' } }, $message: message },
      },
    })
    await flushPromises()
    wrapper.vm.chatId = 'chat-42'
    wrapper.vm.inputMsg = '第一条问题'

    const firstRequest = wrapper.vm.sendMsg()
    await flushPromises()
    wrapper.vm.inputMsg = '第二条问题'
    const secondRequest = wrapper.vm.sendMsg()
    await flushPromises()

    expect(fetch).toHaveBeenCalledTimes(1)

    resolveFirstChunk({ value: new TextEncoder().encode('第一条回复'), done: false })
    await firstRequest
    await secondRequest
  })

  it('renders the first streamed assistant chunk before the response completes', async () => {
    let resolveChunk
    let resolveDone
    let readCount = 0
    fetch.mockResolvedValue({
      ok: true,
      body: {
        getReader: () => ({
          read: () => {
            if (readCount++ === 0) return new Promise(resolve => { resolveChunk = resolve })
            return new Promise(resolve => { resolveDone = resolve })
          },
        }),
      },
    })
    const wrapper = mount(AgentDetail, {
      global: {
        stubs,
        mocks: { $route: { params: { id: '7' } }, $message: message },
      },
    })
    await flushPromises()
    wrapper.vm.chatId = 'chat-42'
    wrapper.vm.inputMsg = '第一条问题'

    const request = wrapper.vm.sendMsg()
    await flushPromises()
    resolveChunk({ value: new TextEncoder().encode('正在显示的回复'), done: false })
    await flushPromises()

    expect(wrapper.text()).toContain('正在显示的回复')

    resolveDone({ done: true })
    await request
  })

  it('keeps the knowledge save request URL and payload when saving settings', async () => {
    const wrapper = mount(KnowledgeDetail, {
      global: {
        plugins: [createPinia()],
        stubs,
        mocks: { $route: { params: { id: '3' } }, $message: message },
      },
    })
    await flushPromises()
    await wrapper.setData({ activeTab: 'settings', kbInfo: { name: '航海资料', desc: '已整理的航线资料' } })

    await wrapper.get('[data-testid="save-knowledge"]').trigger('click')

    expect(put).toHaveBeenCalledWith('/api/knowledge/update/3', {
      name: '航海资料', description: '已整理的航线资料',
    })
  })

  it('keeps the markdown conversion URL, text body, and filename when generating a file', async () => {
    const wrapper = mount(MdConverter, { global: { stubs } })
    wrapper.vm.fileName = '航海日志'
    wrapper.vm.mdContent = '内容'

    await wrapper.get('[data-testid="convert-markdown"]').trigger('click')

    expect(post).toHaveBeenCalledWith('/api/tool/convmd', '内容', {
      params: { fileName: '航海日志' },
      headers: { 'Content-Type': 'text/plain;charset=UTF-8' },
    })
  })
})
