// @vitest-environment happy-dom

import { shallowMount } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
import App from './App.vue'

vi.mock('vue-router', () => ({ useRoute: () => ({ path: '/agent', matched: [] }) }))
vi.mock('./stores/auth', () => ({
  useAuthStore: () => ({
    user: { role: 'tenant_admin', username: 'fansea' },
    bootstrap: vi.fn(),
    logout: vi.fn(),
  }),
}))

const stubs = {
  'router-link': { template: '<a><slot /></a>' },
  'router-view': true,
  'el-menu': { template: '<nav><slot /></nav>' },
  'el-menu-item': { template: '<span><slot /></span>' },
  'el-button': { template: '<button><slot /></button>' },
}

describe('workspace brand', () => {
  it('uses the otter image as the sidebar brand mark', () => {
    const wrapper = shallowMount(App, { global: { stubs } })

    expect(wrapper.get('.brand__mark').attributes('src')).toContain('brand-otter.png')
  })
})
