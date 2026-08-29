// @vitest-environment happy-dom

import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { reactive } from 'vue'
import TenantProfile from './TenantProfile.vue'

const { patch, success } = vi.hoisted(() => ({ patch: vi.fn(), success: vi.fn() }))
const auth = reactive({ tenant: { name: '旧工作区' } })

vi.mock('../api/http', () => ({ http: { patch } }))
vi.mock('../stores/auth', () => ({ useAuthStore: () => auth }))
vi.mock('element-plus', () => ({ ElMessage: { error: vi.fn(), success } }))

const stubs = {
  'el-form': { template: '<form @submit.prevent="$emit(\'submit\')"><slot /></form>' },
  'el-form-item': { template: '<div><slot /></div>' },
  'el-input': { template: '<input :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />', props: ['modelValue'] },
  'el-button': { template: '<button @click="$emit(\'click\')"><slot /></button>' },
}

describe('tenant profile', () => {
  beforeEach(() => { patch.mockReset(); success.mockReset(); auth.tenant = { name: '旧工作区' } })

  it('saves the tenant name and renders the returned current-tenant name', async () => {
    patch.mockResolvedValueOnce({ data: { data: { id: 22, name: '海洋智能体' } } })
    const wrapper = mount(TenantProfile, { global: { stubs } })
    await flushPromises()
    wrapper.vm.form.name = '海洋智能体'

    await wrapper.vm.save()

    expect(patch).toHaveBeenCalledWith('/tenant/profile', { name: '海洋智能体' })
    expect(auth.tenant.name).toBe('海洋智能体')
    expect(wrapper.text()).toContain('海洋智能体')
  })
})
