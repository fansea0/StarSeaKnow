// @vitest-environment happy-dom

import { flushPromises, shallowMount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import Knowledge from './Knowledge.vue'

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

  it('renders the knowledge create action and returned card title', async () => {
    const wrapper = shallowMount(Knowledge, {
      global: {
        stubs,
        config: { globalProperties: { $message: { error: vi.fn() } } },
      },
    })

    await flushPromises()

    expect(wrapper.get('[data-testid="create-knowledge"]').text()).toBe('创建知识库')
    expect(wrapper.get('.entity-card__title').text()).toBe('产品资料')
  })

  it('renders a contrasting trash-can icon in every delete control', async () => {
    const wrapper = shallowMount(Knowledge, {
      global: {
        stubs,
        config: { globalProperties: { $message: { error: vi.fn() } } },
      },
    })

    await flushPromises()

    expect(wrapper.get('[aria-label="删除知识库 产品资料"] .entity-card__delete-icon').exists()).toBe(true)
  })
})
