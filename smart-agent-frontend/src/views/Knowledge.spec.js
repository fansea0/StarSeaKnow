// @vitest-environment happy-dom

import { flushPromises, shallowMount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import Knowledge from './Knowledge.vue'

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
  'el-form-item': { template: '<div><slot /></div>' },
  'el-input': { template: '<input />' },
  'el-icon': { template: '<span><slot /></span>' },
}

describe('knowledge list', () => {
  beforeEach(() => {
    get.mockReset()
    post.mockReset()
    get.mockResolvedValue({
      data: {
        code: 200,
        data: [
          { id: 1, name: '产品资料', description: '产品说明与常见问题', fileCount: 4, agentCount: 2 },
          { id: 2, name: '操作手册', description: '团队工作流程说明', fileCount: 8, agentCount: 1 },
        ],
      },
    })
  })

  it('renders knowledge bases as lightweight management rows', async () => {
    const wrapper = shallowMount(Knowledge, {
      global: {
        stubs,
        config: { globalProperties: { $message: { error: vi.fn() } } },
      },
    })

    await flushPromises()

    expect(wrapper.get('.knowledge-page__eyebrow').text()).toBe('整理资料')
    expect(wrapper.get('[data-testid="create-knowledge"]').text()).toBe('创建知识库')
    expect(wrapper.get('.knowledge-list__header').text()).toContain('文档数')
    expect(wrapper.get('.knowledge-list__header').text()).toContain('描述')
    expect(wrapper.get('.knowledge-row__title').text()).toBe('产品资料')
    expect(wrapper.get('.knowledge-row__document-count').text()).toBe('4')
    expect(wrapper.get('.knowledge-row__agent-count').text()).toBe('2')
    expect(wrapper.get('.knowledge-row__enter').text()).toBe('进入知识库')
  })

  it('renders a contrasting trash-can icon in every delete control', async () => {
    const wrapper = shallowMount(Knowledge, {
      global: {
        stubs,
        config: { globalProperties: { $message: { error: vi.fn() } } },
      },
    })

    await flushPromises()

    expect(wrapper.get('[aria-label="删除知识库 产品资料"] .knowledge-row__delete-icon').exists()).toBe(true)
  })

  it('filters the existing knowledge list by name or description and shows a no-result state', async () => {
    const wrapper = shallowMount(Knowledge, {
      global: {
        stubs,
        config: { globalProperties: { $message: { error: vi.fn() } } },
      },
    })

    await flushPromises()
    await wrapper.setData({ searchQuery: '操作' })
    expect(wrapper.findAll('.knowledge-row')).toHaveLength(1)
    expect(wrapper.get('.knowledge-row__title').text()).toBe('操作手册')

    await wrapper.setData({ searchQuery: '不存在' })
    expect(wrapper.get('[data-testid="knowledge-search-empty"]').text()).toContain('没有找到匹配的知识库')
  })

  it('creates a knowledge base with its name and description', async () => {
    const wrapper = shallowMount(Knowledge, {
      global: {
        stubs,
        config: { globalProperties: { $message: { error: vi.fn(), success: vi.fn() } } },
      },
    })
    post.mockResolvedValue({ data: { code: 200 } })

    await flushPromises()
    wrapper.vm.createForm = { name: '入职资料', desc: '员工入职指南' }
    await wrapper.vm.handleCreate()
    await flushPromises()

    expect(post).toHaveBeenCalledWith('/knowledge/add', { name: '入职资料', description: '员工入职指南' })
  })
})
