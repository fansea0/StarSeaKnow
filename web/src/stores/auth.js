import { defineStore } from 'pinia'
import { http, bindAuth } from '../api/http'
import router from '../router'

export const useAuthStore = defineStore('auth', {
  state: () => ({
    accessToken: null,
    expiresAt: 0,
    user: null,
    tenant: null,
    mustChangePassword: false,
    ready: false,
  }),
  actions: {
    async bootstrap() {
      bindAuth(this)
      try {
        const r = await http.post('/auth/refresh')
        this.accessToken = r.data.data.accessToken
        this.expiresAt = r.data.data.expiresAt
        await this.fetchMe()
      } catch (e) {
        // 没登录是预期情况
      } finally {
        this.ready = true
      }
    },
    applyBusinessSession(session) {
      this.accessToken = session.accessToken
      this.expiresAt = session.expiresAt
      this.user = session.user
      this.tenant = null
      this.mustChangePassword = false
      this.ready = true
    },
    async login(username, password) {
      const r = await http.post('/auth/login', { username, password })
      this.applyBusinessSession(r.data.data)
    },
    async register(payload) {
      const r = await http.post('/auth/register', payload)
      this.applyBusinessSession(r.data.data)
    },
    async loginPlatform(username, password) {
      const r = await http.post('/platform/auth/login', { username, password })
      this.accessToken = r.data.data.accessToken
      this.expiresAt = r.data.data.expiresAt
      this.user = { username, role: 'platform_admin' }
      this.tenant = null
      this.mustChangePassword = r.data.data.mustChangePassword === true
      this.ready = true
    },
    async changeInitialPassword(payload) {
      const r = await http.post('/platform/auth/change-initial-password', payload)
      this.mustChangePassword = r.data.data.mustChangePassword === true
    },
    async logout() {
      try { await http.post(this.user?.role === 'platform_admin' ? '/platform/auth/logout' : '/auth/logout') } catch (e) {}
      this.clear()
      router.push('/login')
    },
    async fetchMe() {
      const r = await http.get('/auth/me')
      this.user = r.data.data.user
      this.tenant = r.data.data.tenant
    },
    async silentRefresh() {
      const r = await http.post('/auth/refresh')
      this.accessToken = r.data.data.accessToken
      this.expiresAt = r.data.data.expiresAt
    },
    clear() {
      this.accessToken = null
      this.expiresAt = 0
      this.user = null
      this.tenant = null
      this.mustChangePassword = false
    },
  },
})
