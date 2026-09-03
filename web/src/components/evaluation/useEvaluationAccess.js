import { computed, getCurrentInstance } from 'vue'
import { useAuthStore } from '../../stores/auth'

export function useEvaluationAccess() {
  const pinia = getCurrentInstance()?.appContext.config.globalProperties.$pinia
  const auth = pinia ? useAuthStore(pinia) : null
  return computed(() => auth?.user?.role === 'tenant_admin')
}
