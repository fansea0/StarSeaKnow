// @vitest-environment happy-dom

import { shallowMount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import AgentDetail from './AgentDetail.vue'

const { get } = vi.hoisted(() => ({ get: vi.fn() }))

vi.mock('axios', () => ({ default: { get } }))
vi.mock('../api/http', () => ({ apiUrl: path => `/api${path}` }))

const stubs = {
  'el-form': { template: '<form><slot /></form>' },
  'el-form-item': { template: '<div><slot /></div>' },
  'el-input': true,
  'el-button': { template: '<button><slot /></button>' },
  'el-card': { template: '<article><slot /></article>' },
  'el-icon': { template: '<span><slot /></span>' },
  'el-dialog': { template: '<section><slot /><slot name="footer" /></section>' },
  'el-select': { template: '<select><slot /></select>' },
  'el-option': true,
}

describe('agent detail debug workbench', () => {
  beforeEach(() => {
    get.mockReset()
    get.mockResolvedValue({ data: { code: 200, data: [] } })
  })

  it('keeps the preview and composer inside the adaptive viewport workbench', () => {
    const wrapper = shallowMount(AgentDetail, {
      global: {
        stubs,
        mocks: { $route: { params: { id: '1' } }, $message: { error: vi.fn() } },
      },
    })

    expect(wrapper.get('[data-testid="agent-workbench"]').classes()).toContain('detail-workbench--viewport')
    expect(wrapper.get('[data-testid="debug-preview"]').classes()).toContain('preview-pane--adaptive')
    expect(wrapper.get('[data-testid="chat-composer"]').exists()).toBe(true)
  })
})
