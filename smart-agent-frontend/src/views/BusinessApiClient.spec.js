// @vitest-environment happy-dom

import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import Knowledge from './Knowledge.vue'
import Agent from './Agent.vue'

const { get, legacyGet } = vi.hoisted(() => ({
  get: vi.fn(),
  legacyGet: vi.fn(),
}))

vi.mock('../api/http', () => ({ http: { get } }))
vi.mock('axios', () => ({ default: { get: legacyGet } }))

const stubs = {
  'el-button': { template: '<button><slot /></button>' },
  'el-card': { template: '<section><slot /></section>' },
  'el-dialog': { template: '<section><slot /><slot name="footer" /></section>' },
  'el-form': { template: '<form><slot /></form>' },
  'el-form-item': { template: '<div><slot /></div>' },
  'el-input': { template: '<input />' },
  'el-icon': { template: '<i><slot /></i>' },
}

describe('business list API client', () => {
  beforeEach(() => {
    get.mockReset()
    legacyGet.mockReset()
    get.mockResolvedValue({ data: { code: 200, data: [] } })
    legacyGet.mockResolvedValue({ data: { code: 200, data: [] } })
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
})
