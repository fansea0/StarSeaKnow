// @vitest-environment happy-dom

import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import Tenants from './Tenants.vue'

const { get, post, confirm } = vi.hoisted(() => ({
  get: vi.fn(),
  post: vi.fn(),
  confirm: vi.fn(),
}))

vi.mock('../../api/http', () => ({
  http: { get, post },
}))

vi.mock('element-plus', () => ({
  ElMessage: { error: vi.fn(), success: vi.fn() },
  ElMessageBox: { confirm },
}))

const stubs = {
  'el-button': { template: '<button @click="$emit(\'click\')"><slot /></button>' },
  'el-dialog': { template: '<section v-if="modelValue"><slot /><slot name="footer" /></section>', props: ['modelValue'] },
  'el-form': { template: '<form><slot /></form>' },
  'el-form-item': { template: '<div><slot /></div>' },
  'el-input': { template: '<input :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />', props: ['modelValue'] },
  'el-select': { template: '<select><slot /></select>' },
  'el-option': true,
  'el-table': { template: '<div><slot /></div>' },
  'el-table-column': true,
  'el-pagination': { template: '<nav />' },
  'el-tag': { template: '<span><slot /></span>' },
}

function mountTenants() {
  return mount(Tenants, { global: { stubs } })
}

describe('platform tenant workspace', () => {
  beforeEach(() => {
    get.mockReset()
    post.mockReset()
    confirm.mockReset()
    get.mockResolvedValue({ data: { data: { items: [], page: 1, pageSize: 20, total: 0 } } })
  })

  it('renders tenants and pagination returned by the API', async () => {
    get.mockResolvedValueOnce({
      data: { data: { items: [{ id: 9, code: 'acme', name: 'Acme', status: 1 }], page: 2, pageSize: 10, total: 11 } },
    })
    const wrapper = mountTenants()
    await flushPromises()

    expect(get).toHaveBeenCalledWith('/platform/tenants', { params: { page: 1, pageSize: 20, keyword: undefined, status: undefined } })
    expect(wrapper.text()).toContain('Acme')
    expect(wrapper.vm.total).toBe(11)
    expect(wrapper.vm.page).toBe(2)
  })

  it('displays inviteCode after a successful create request', async () => {
    const wrapper = mountTenants()
    await flushPromises()
    wrapper.vm.createForm.code = 'acme'
    wrapper.vm.createForm.name = 'Acme'
    post.mockResolvedValueOnce({ data: { data: { inviteCode: 'invite-123', inviteExpiresAt: '2026-09-03T00:00:00Z' } } })

    await wrapper.vm.createTenant()

    expect(post).toHaveBeenCalledWith('/platform/tenants', { code: 'acme', name: 'Acme' })
    expect(wrapper.text()).toContain('invite-123')
  })

  it('does not disable before confirmation', async () => {
    const wrapper = mountTenants()
    await flushPromises()
    confirm.mockRejectedValueOnce(new Error('cancelled'))

    await wrapper.vm.disableTenant({ id: 9, name: 'Acme' })

    expect(post).not.toHaveBeenCalled()
  })
})
