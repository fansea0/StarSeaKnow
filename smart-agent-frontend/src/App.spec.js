// @vitest-environment happy-dom

import { mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createMemoryHistory, createRouter } from 'vue-router'
import App from './App.vue'
import { routes } from './router/index'
import { installGuards } from './router/guards'

const { auth } = vi.hoisted(() => ({
  auth: {
    ready: true,
    accessToken: 'tenant-token',
    user: { role: 'tenant_admin', username: 'fansea' },
    bootstrap: vi.fn(),
    logout: vi.fn(),
  },
}))

vi.mock('./stores/auth', () => ({
  useAuthStore: () => auth,
}))

const stubs = {
  'el-menu': { template: '<nav><slot /></nav>' },
  'el-menu-item': { props: ['index'], template: '<span class="menu-item" :data-index="index"><slot /></span>' },
  'el-button': { template: '<button><slot /></button>' },
}

function createShellRouter(initialPath = '/agent') {
  const Placeholder = { template: '<div data-testid="route-view" />' }
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/agent', component: Placeholder },
      { path: '/knowledge', component: Placeholder },
      { path: '/tools', component: Placeholder },
      { path: '/tenant/members', component: Placeholder },
      { path: '/tenant/profile', component: Placeholder },
      { path: '/tenant/api-credentials', component: Placeholder },
      { path: '/tenant/api-credentials/:credentialId', component: Placeholder },
      { path: '/tenant/api-docs', component: Placeholder },
    ],
  })
  return router.push(initialPath).then(() => router)
}

describe('workspace shell', () => {
  beforeEach(() => {
    auth.ready = true
    auth.accessToken = 'tenant-token'
    auth.user = { role: 'tenant_admin', username: 'fansea' }
    auth.bootstrap.mockReset()
  })

  it('uses the otter image as the sidebar brand mark', async () => {
    const router = await createShellRouter('/agent')
    const wrapper = mount(App, { global: { plugins: [router], stubs } })

    expect(wrapper.get('.brand__mark').attributes('src')).toContain('brand-otter.png')
  })

  it('groups tenant administration under one primary entry and shows the four secondary destinations', async () => {
    const router = await createShellRouter('/tenant/api-credentials')
    const wrapper = mount(App, { global: { plugins: [router], stubs } })

    expect(wrapper.findAll('.menu-item').filter((item) => item.text() === '租户管理')).toHaveLength(1)
    expect(wrapper.findAll('.menu-item').some((item) => item.text() === '成员')).toBe(false)
    expect(wrapper.findAll('.menu-item').some((item) => item.text() === '租户设置')).toBe(false)
    expect(wrapper.get('[aria-label="租户管理导航"]').text()).toContain('成员管理')
    expect(wrapper.get('[aria-label="租户管理导航"]').text()).toContain('租户设置')
    expect(wrapper.get('[aria-label="租户管理导航"]').text()).toContain('API 凭证')
    expect(wrapper.get('[aria-label="租户管理导航"]').text()).toContain('调用文档')
  })

  it('keeps API credentials active in the secondary navigation on a credential detail route', async () => {
    const router = await createShellRouter('/tenant/api-credentials/8797a05e-9d6c-4d47-a254-e648c8027ee9')
    const wrapper = mount(App, { global: { plugins: [router], stubs } })

    expect(wrapper.get('[aria-label="租户管理导航"] [aria-current="page"]').text()).toBe('API 凭证')
  })

  it('does not render tenant administration navigation for a tenant member', async () => {
    auth.user = { role: 'tenant_member', username: 'member' }
    const router = await createShellRouter('/knowledge')
    const wrapper = mount(App, { global: { plugins: [router], stubs } })

    expect(wrapper.text()).not.toContain('租户管理')
    expect(wrapper.find('[aria-label="租户管理导航"]').exists()).toBe(false)
  })
})

describe('tenant administration routes', () => {
  beforeEach(() => {
    auth.ready = true
    auth.accessToken = 'member-token'
    auth.user = { role: 'tenant_member', username: 'member' }
  })

  it('redirects a tenant member away from every tenant administration route', async () => {
    const router = createRouter({ history: createMemoryHistory(), routes })
    installGuards(router)

    await router.push('/tenant/api-credentials')
    expect(router.currentRoute.value.path).toBe('/403')

    await router.push('/tenant/api-credentials/8797a05e-9d6c-4d47-a254-e648c8027ee9')
    expect(router.currentRoute.value.path).toBe('/403')

    await router.push('/tenant/api-docs')
    expect(router.currentRoute.value.path).toBe('/403')

    await router.push('/tenant')
    expect(router.currentRoute.value.path).toBe('/403')
  })

  it('redirects a tenant administrator from the tenant root to members', async () => {
    auth.user = { role: 'tenant_admin', username: 'admin' }
    const router = createRouter({ history: createMemoryHistory(), routes })
    installGuards(router)

    await router.push('/tenant')
    expect(router.currentRoute.value.path).toBe('/tenant/members')
  })
})
