// @vitest-environment happy-dom

import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import Invitations from './Invitations.vue'

const { get, post, confirm } = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn(), confirm: vi.fn() }))

vi.mock('../../api/http', () => ({ http: { get, post } }))
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
  'el-date-picker': { template: '<input />', props: ['modelValue'] },
  'el-pagination': { template: '<nav />' },
  'el-tag': { template: '<span><slot /></span>' },
}

function mountInvitations() { return mount(Invitations, { global: { stubs } }) }

describe('platform invitation workspace', () => {
  beforeEach(() => {
    get.mockReset(); post.mockReset(); confirm.mockReset()
    get.mockResolvedValue({ data: { data: { items: [], page: 1, pageSize: 20, total: 0 } } })
  })

  it('renders invitation lifecycle data separately from tenant data', async () => {
    get.mockResolvedValueOnce({ data: { data: { items: [{ id: 8, code: 'YQ-7A5K', status: 'ACTIVE' }], page: 1, pageSize: 20, total: 1 } } })
    const wrapper = mountInvitations()
    await flushPromises()
    expect(wrapper.text()).toContain('YQ-7A5K')
    expect(get).toHaveBeenCalledWith('/platform/invitations', expect.anything())
    expect(get).not.toHaveBeenCalledWith('/platform/tenants', expect.anything())
  })

  it('does not call disable endpoint until invitation disable is confirmed', async () => {
    const wrapper = mountInvitations()
    await flushPromises()
    confirm.mockRejectedValueOnce(new Error('cancelled'))
    await wrapper.vm.disableInvitation({ id: 8, code: 'YQ-7A5K' })
    expect(post).not.toHaveBeenCalled()
  })
})
