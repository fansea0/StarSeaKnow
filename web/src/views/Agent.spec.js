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
  'el-select': { template: '<select><slot /></select>' },
  'el-option': { template: '<option><slot /></option>' },
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

  it('renders the agent workspace with a banner, metrics, filters, and agent cards', async () => {
    const wrapper = shallowMount(Agent, {
      global: {
        stubs,
        config: { globalProperties: { $message: { error: vi.fn() } } },
      },
    })

    await flushPromises()

    expect(wrapper.get('.agent-hero__title').text()).toBe('配置你的专属智能体')
    expect(wrapper.get('[data-testid="create-agent"]').text()).toBe('创建智能体')
    expect(wrapper.get('[data-testid="agent-search"]').exists()).toBe(true)
    expect(wrapper.get('[data-testid="agent-status-filter"]').exists()).toBe(true)
    expect(wrapper.get('.agent-card__title').text()).toBe('产品顾问')
    expect(wrapper.get('.agent-card__status').text()).toContain('运行中')
  })

  it('renders a contrasting trash-can icon in every delete control', async () => {
    const wrapper = shallowMount(Agent, {
      global: {
        stubs,
        config: { globalProperties: { $message: { error: vi.fn() } } },
      },
    })

    await flushPromises()

    expect(wrapper.get('[aria-label="删除智能体 产品顾问"] .agent-card__delete-icon').exists()).toBe(true)
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
