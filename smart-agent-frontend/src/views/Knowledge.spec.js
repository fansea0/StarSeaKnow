// @vitest-environment happy-dom

import { flushPromises, shallowMount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import Knowledge from './Knowledge.vue'

const { get } = vi.hoisted(() => ({ get: vi.fn() }))

vi.mock('../api/http', () => ({ http: { get } }))

const stubs = {
  'el-button': { template: '<button @click="$emit(\'click\')"><slot /></button>' },
  'el-dialog': { template: '<section><slot /><slot name="footer" /></section>' },
  'el-form': { template: '<form><slot /></form>' },
  'el-form-item': { template: '<div><slot /></div>' },
  'el-input': { template: '<input />' },
  'el-select': { template: '<select><slot /></select>' },
  'el-option': { template: '<option><slot /></option>' },
  'el-icon': { template: '<span><slot /></span>' },
}

describe('knowledge list', () => {
  beforeEach(() => {
    get.mockReset()
    get.mockResolvedValue({
      data: {
        code: 200,
        data: [{ id: 1, name: '产品资料', description: '产品说明与常见问题', fileCount: 4, agentCount: 2 }],
      },
    })
  })

  it('shows the knowledge management workspace without import or type controls', async () => {
    const wrapper = shallowMount(Knowledge, {
      global: {
        stubs,
        config: { globalProperties: { $message: { error: vi.fn() } } },
      },
    })

    await flushPromises()

    expect(wrapper.get('.knowledge-page__subtitle').text()).toContain('管理团队知识资产')
    expect(wrapper.get('[data-testid="create-knowledge"]').text()).toBe('创建知识库')
    expect(wrapper.get('[data-testid="knowledge-search"]').exists()).toBe(true)
    expect(wrapper.get('[data-testid="status-filter"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="import-knowledge"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="type-filter"]').exists()).toBe(false)
    expect(wrapper.get('.knowledge-table__header').text()).not.toContain('类型')
    expect(wrapper.get('.knowledge-table__title').text()).toBe('产品资料')
    expect(wrapper.get('.knowledge-table__document-count').text()).toBe('4')
    expect(wrapper.get('.knowledge-table__agent-count').text()).toBe('2')
  })

  it('renders a contrasting trash-can icon in every delete control', async () => {
    const wrapper = shallowMount(Knowledge, {
      global: {
        stubs,
        config: { globalProperties: { $message: { error: vi.fn() } } },
      },
    })

    await flushPromises()

    expect(wrapper.get('[aria-label="删除知识库 产品资料"] .knowledge-table__delete-icon').exists()).toBe(true)
  })
})
