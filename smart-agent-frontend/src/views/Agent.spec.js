// @vitest-environment happy-dom

import { flushPromises, shallowMount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import Agent from './Agent.vue'

const { get, post } = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn() }))

vi.mock('../api/http', () => ({ http: { get, post } }))

const stubs = {
  'el-card': { template: '<article><slot /></article>' },
  'el-button': { template: '<button @click="$emit(\'click\')"><slot /></button>' },
  'el-dialog': { template: '<section><slot /><slot name="footer" /></section>' },
  'el-form': {
    template: '<form><slot /></form>',
    methods: { validate(callback) { callback(true) } },
  },
  'el-form-item': { props: ['label', 'prop'], template: '<div class="form-field" :data-prop="prop"><label>{{ label }}</label><slot /></div>' },
  'el-input': { template: '<input />' },
  'el-icon': { template: '<span><slot /></span>' },
}

describe('agent list', () => {
  beforeEach(() => {
    get.mockReset()
    post.mockReset()
    get.mockResolvedValue({
      data: {
        code: 200,
        data: [{ id: 2, name: '产品顾问', description: '回答产品问题', prologue: '你好', roleDescription: '产品专家' }],
      },
    })
  })

  it('renders active collaborators with their open-and-debug action', async () => {
    const wrapper = shallowMount(Agent, {
      global: {
        stubs,
        config: { globalProperties: { $message: { error: vi.fn() } } },
      },
    })

    await flushPromises()

    expect(wrapper.get('.agent-page__eyebrow').text()).toBe('配置协作者')
    expect(wrapper.get('[data-testid="create-agent"]').text()).toBe('新增智能体')
    expect(wrapper.get('.agent-profile__title').text()).toBe('产品顾问')
    expect(wrapper.get('.agent-profile__open').text()).toContain('打开并调试')
  })

  it('renders a contrasting trash-can icon in every delete control', async () => {
    const wrapper = shallowMount(Agent, {
      global: {
        stubs,
        config: { globalProperties: { $message: { error: vi.fn() } } },
      },
    })

    await flushPromises()

    expect(wrapper.get('[aria-label="删除智能体 产品顾问"] .agent-profile__delete-icon').exists()).toBe(true)
  })

  it('creates an agent from only its name and description', async () => {
    const wrapper = shallowMount(Agent, {
      global: {
        stubs,
        config: { globalProperties: { $message: { error: vi.fn(), success: vi.fn() } } },
      },
    })
    post.mockResolvedValue({ data: { code: 200 } })

    await flushPromises()
    wrapper.vm.createForm = { name: '产品顾问', description: '回答产品问题' }
    await wrapper.vm.handleCreate()
    await flushPromises()

    expect(wrapper.findAll('.form-field').map((field) => field.attributes('data-prop'))).toEqual(['name', 'description'])
    expect(post).toHaveBeenCalledWith('/agent/add', { name: '产品顾问', description: '回答产品问题' })
  })
})
