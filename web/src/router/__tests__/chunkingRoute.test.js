const auth = {
  ready: true,
  accessToken: 'access-token',
  mustChangePassword: false,
  user: { role: 'tenant_user' },
  bootstrap: vi.fn(),
}

vi.mock('../../stores/auth', () => ({ useAuthStore: () => auth }))

import { installGuards } from '../guards'
import { routes } from '../index'

function installedGuard() {
  let guard
  installGuards({ beforeEach: candidate => { guard = candidate } })
  return guard
}

describe('chunking workspace route access', () => {
  it('uses the tenant-admin guard for the write-capable workspace', () => {
    const route = routes.find(item => item.name === 'ChunkingWorkspace')

    expect(route.meta).toEqual({ requiresAdmin: true })
  })

  it('allows authenticated users to the read-only knowledge route but rejects workspace writes', async () => {
    const guard = installedGuard()
    const detail = routes.find(item => item.name === 'KnowledgeDetail')
    const workspace = routes.find(item => item.name === 'ChunkingWorkspace')

    expect(await guard({ ...detail, meta: detail.meta || {}, fullPath: '/knowledge/11' })).toBe(true)
    expect(await guard({ ...workspace, fullPath: '/knowledge/11/files/22/chunks' })).toBe('/403')

    auth.user = { role: 'tenant_admin' }
    expect(await guard({ ...workspace, fullPath: '/knowledge/11/files/22/chunks' })).toBe(true)
    auth.user = { role: 'tenant_user' }
  })

  it('forces users marked for password change to the reset page', async () => {
    const guard = installedGuard()
    auth.mustChangePassword = true

    expect(await guard({ path: '/knowledge', meta: {}, fullPath: '/knowledge' }))
      .toBe('/change-initial-password')
    expect(await guard({ path: '/change-initial-password', meta: {}, fullPath: '/change-initial-password' }))
      .toBe(true)

    auth.mustChangePassword = false
  })
})
