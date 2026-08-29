// @vitest-environment happy-dom

import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createMemoryHistory, createRouter } from 'vue-router'
import { installGuards } from './guards'

const { auth } = vi.hoisted(() => ({
  auth: {
    ready: false,
    accessToken: null,
    user: null,
    bootstrap: vi.fn(),
  },
}))

vi.mock('../stores/auth', () => ({
  useAuthStore: () => auth,
}))

function installTestGuard() {
  let guard
  installGuards({
    beforeEach(callback) {
      guard = callback
    },
  })
  return guard
}

function createTestRouter() {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/knowledge', component: { template: '<div />' } },
      {
        path: '/system',
        component: { template: '<router-view />' },
        children: [
          {
            path: 'overview',
            component: { template: '<div />' },
            meta: { requiresPlatformAdmin: true },
          },
        ],
      },
    ],
  })

  installGuards(router)
  return router
}

describe('platform management route guard', () => {
  beforeEach(() => {
    Object.assign(auth, {
      ready: true,
      accessToken: null,
      user: null,
      mustChangePassword: false,
    })
  })

  it('redirects tenant_admin from /system/overview to /403', async () => {
    auth.accessToken = 'tenant-token'
    auth.user = { role: 'tenant_admin' }

    const guard = installTestGuard()

    await expect(guard({ meta: { requiresPlatformAdmin: true } })).resolves.toBe('/403')
  })

  it('allows platform_admin to enter /system/overview', async () => {
    auth.accessToken = 'platform-token'
    auth.user = { role: 'platform_admin' }

    const guard = installTestGuard()

    await expect(guard({ meta: { requiresPlatformAdmin: true } })).resolves.toBe(true)
  })

  it('allows the public API guide without bootstrapping a login session', async () => {
    auth.ready = false
    auth.accessToken = null
    auth.bootstrap.mockReset()
    const guard = installTestGuard()

    await expect(guard({ path: '/tenant/api-docs', fullPath: '/tenant/api-docs', meta: { public: true } }))
      .resolves.toBe(true)
    expect(auth.bootstrap).not.toHaveBeenCalled()
  })

  it('allows platform_admin to navigate to /system/overview', async () => {
    auth.accessToken = 'platform-token'
    auth.user = { role: 'platform_admin' }
    const router = createTestRouter()

    await router.push('/system/overview')

    expect(router.currentRoute.value.path).toBe('/system/overview')
  })

  it('allows a tenant user to navigate to /knowledge', async () => {
    auth.accessToken = 'tenant-token'
    auth.user = { role: 'tenant_user' }
    const router = createTestRouter()

    await router.push('/knowledge')

    expect(router.currentRoute.value.path).toBe('/knowledge')
  })

  it('redirects a platform administrator who must change password to the change-password page', async () => {
    auth.accessToken = 'platform-token'
    auth.user = { role: 'platform_admin' }
    auth.mustChangePassword = true

    const guard = installTestGuard()

    await expect(guard({ path: '/system/invitations', fullPath: '/system/invitations', meta: { requiresPlatformAdmin: true } }))
      .resolves.toBe('/change-initial-password')
    await expect(guard({ path: '/change-initial-password', fullPath: '/change-initial-password', meta: { requiresPlatformAdmin: true } }))
      .resolves.toBe(true)
  })
})
