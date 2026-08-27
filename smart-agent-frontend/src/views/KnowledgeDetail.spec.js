// @vitest-environment happy-dom

import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import KnowledgeDetail from './KnowledgeDetail.vue'
import { useAuthStore } from '../stores/auth'

const { get } = vi.hoisted(() => ({ get: vi.fn() }))

vi.mock('axios', () => ({ default: { get } }))
vi.mock('../api/http', () => ({ apiUrl: path => `/api${path}` }))

const stubs = {
  'el-upload': { name: 'ElUpload', template: '<div class="el-upload"><slot /></div>', props: ['headers', 'action'] },
  'el-button': { template: '<button><slot /></button>' },
  'el-menu': { template: '<nav><slot /></nav>' },
  'el-menu-item': { template: '<div><slot /></div>' },
  'el-table': { template: '<div><slot /></div>', props: ['data'] },
  'el-table-column': true,
  'el-switch': true,
  'el-tooltip': { template: '<span><slot /></span>' },
  'el-icon': { template: '<span><slot /></span>' },
  'el-form': { template: '<form><slot /></form>' },
  'el-form-item': { template: '<div><slot /></div>' },
  'el-input': true,
}

describe('knowledge document upload', () => {
  let pinia

  beforeEach(() => {
    pinia = createPinia()
    setActivePinia(pinia)
    get.mockReset()
    get.mockResolvedValue({ data: { code: 200, data: {} } })
  })

  it('keeps the upload headers empty when a tenant token is available', () => {
    useAuthStore().accessToken = 'tenant-access-token'
    const wrapper = mount(KnowledgeDetail, {
      global: {
        plugins: [pinia],
        stubs,
        mocks: { $route: { params: { id: '1' } }, $message: { error: vi.fn() } },
      },
    })

    expect(wrapper.getComponent({ name: 'ElUpload' }).props('headers')).toEqual({})
  })
})
