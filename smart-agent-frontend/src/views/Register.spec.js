// @vitest-environment happy-dom

import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import Register from './Register.vue'

const { post, push } = vi.hoisted(() => ({ post: vi.fn(), push: vi.fn() }))

vi.mock('../api/http', () => ({ http: { post } }))
vi.mock('vue-router', async (importOriginal) => ({
  ...(await importOriginal()),
  useRoute: () => ({ query: {} }),
  useRouter: () => ({ push }),
}))
vi.mock('element-plus', () => ({ ElMessage: { error: vi.fn(), success: vi.fn() } }))

const stubs = {
  'el-card': { template: '<section><slot /></section>' },
  'el-form': { template: '<form @submit.prevent="$emit(\'submit\')"><slot /></form>' },
  'el-form-item': { template: '<label><slot /></label>' },
  'el-input': { template: '<input :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />', props: ['modelValue'] },
  'el-button': { template: '<button :type="nativeType" @click="$emit(\'click\')"><slot /></button>', props: ['nativeType'] },
  'router-link': { template: '<a><slot /></a>' },
}

describe('invitation registration', () => {
  beforeEach(() => {
    post.mockReset()
    push.mockReset()
    setActivePinia(createPinia())
    post.mockResolvedValue({ data: { data: { accessToken: 'token', expiresAt: 99, user: { username: 'ocean-admin' } } } })
  })

  it('submits invitation code, username, password and confirmation to /auth/register', async () => {
    const wrapper = mount(Register, { global: { plugins: [createPinia()], stubs } })
    wrapper.vm.form.inviteCode = 'YQ-7A5K'
    wrapper.vm.form.username = 'ocean-admin'
    wrapper.vm.form.password = 'Strong!123'
    wrapper.vm.form.confirmPassword = 'Strong!123'

    await wrapper.vm.onSubmit()
    await flushPromises()

    expect(post).toHaveBeenCalledWith('/auth/register', {
      inviteCode: 'YQ-7A5K', username: 'ocean-admin', password: 'Strong!123', confirmPassword: 'Strong!123',
    })
    expect(push).toHaveBeenCalledWith('/knowledge')
  })

  it('does not submit when the two passwords differ', async () => {
    const wrapper = mount(Register, { global: { plugins: [createPinia()], stubs } })
    wrapper.vm.form.password = 'Strong!123'
    wrapper.vm.form.confirmPassword = 'Different!123'

    await wrapper.vm.onSubmit()

    expect(post).not.toHaveBeenCalled()
  })
})
