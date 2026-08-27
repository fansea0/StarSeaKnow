// @vitest-environment happy-dom

import { beforeEach, describe, expect, it, vi } from 'vitest'
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

describe('platform management route guard', () => {
  beforeEach(() => {
    Object.assign(auth, {
      ready: true,
      accessToken: null,
      user: null,
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
})
