import { useAuthStore } from '../stores/auth'

export function installGuards(router) {
  router.beforeEach(async (to) => {
    const auth = useAuthStore()
    if (!auth.ready) await auth.bootstrap()
    if (!auth.accessToken) {
      if (to.meta.public) return true
      return { path: '/login', query: { redirect: to.fullPath } }
    }
    if (auth.mustChangePassword && to.path !== '/change-initial-password') {
      return '/change-initial-password'
    }
    if (to.meta.public) return true
    if (to.meta.requiresAdmin && auth.user?.role !== 'tenant_admin') {
      return '/403'
    }
    if (to.meta.requiresPlatformAdmin && auth.user?.role !== 'platform_admin') {
      return '/403'
    }
    return true
  })
}
