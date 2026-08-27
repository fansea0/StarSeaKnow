// @vitest-environment happy-dom

import { flushPromises, shallowMount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import Agent from './Agent.vue'

const { get } = vi.hoisted(() => ({ get: vi.fn() }))

vi.mock('../api/http', () => ({ http: { get } }))

const stubs = {
  'el-card': { template: '<article><slot /></article>' },
  'el-button': { template: '<button @click="$emit(\'click\')"><slot /></button>' },
  'el-dialog': { template: '<section><slot /><slot name="footer" /></section>' },
  'el-form': { template: '<form><slot /></form>' },
  'el-form-item': { template: '<div><slot /></div>' },
  'el-input': { template: '<input />' },
  'el-icon': { template: '<span><slot /></span>' },
}

describe('agent list', () => {
  beforeEach(() => {
    get.mockReset()
    get.mockResolvedValue({
      data: {
        code: 200,
        data: [{ id: 2, name: '产品顾问', description: '回答产品问题', prologue: '你好', roleDescription: '产品专家' }],
      },
    })
  })

  it('renders the agent create action and returned card title', async () => {
    const wrapper = shallowMount(Agent, {
      global: {
        stubs,
        config: { globalProperties: { $message: { error: vi.fn() } } },
      },
    })

    await flushPromises()

    expect(wrapper.get('[data-testid="create-agent"]').text()).toBe('创建智能体')
    expect(wrapper.get('.entity-card__title').text()).toBe('产品顾问')
  })
})
