import { defineStore } from 'pinia'
import { http, bindAuth } from '../api/http'
import router from '../router'

export const useAuthStore = defineStore('auth', {
  state: () => ({
    accessToken: null,
    expiresAt: 0,
    user: null,
    tenant: null,
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
    async login(tenantCode, username, password) {
      const r = await http.post('/auth/login', { tenantCode, username, password })
      this.accessToken = r.data.data.accessToken
      this.expiresAt = r.data.data.expiresAt
      this.user = r.data.data.user
      this.ready = true
    },
    async logout() {
      try { await http.post('/auth/logout') } catch (e) {}
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
    },
  },
})
