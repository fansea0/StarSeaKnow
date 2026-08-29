import axios from 'axios'
import { http } from './http'
import { useAuthStore } from '../stores/auth'
import router from '../router'
import { ElMessage } from 'element-plus'

let refreshing = null  // 单例,避免 40101 风暴

export function setupInterceptors() {
  setupClientInterceptors(http)
  setupClientInterceptors(axios)
}

function setupClientInterceptors(client) {
  client.interceptors.request.use(cfg => {
    const auth = useAuthStore()
    if (auth.accessToken) cfg.headers.Authorization = `Bearer ${auth.accessToken}`
    return cfg
  })

  client.interceptors.response.use(r => r, async err => {
    const auth = useAuthStore()
    const { config, response } = err
    if (!response) return Promise.reject(err)
    const code = response.data?.code

    if (response.status === 401 && code === 40101 && !config._retried) {
      config._retried = true
      refreshing = refreshing || auth.silentRefresh().finally(() => { refreshing = null })
      try {
        await refreshing
        config.headers.Authorization = `Bearer ${auth.accessToken}`
        return client(config)
      } catch (e) {
        auth.clear()
        router.push('/login')
        return Promise.reject(e)
      }
    }

    if ([40102, 40103].includes(code)) {
      auth.clear()
      router.push('/login')
      ElMessage.warning(code === 40103 ? '已在其他设备登录' : '登录已过期,请重新登录')
      return Promise.reject(err)
    }

    if (code === 40301) {
      router.push('/403')
      return Promise.reject(err)
    }

    return Promise.reject(err)
  })
}
