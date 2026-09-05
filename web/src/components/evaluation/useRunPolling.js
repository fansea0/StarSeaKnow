import { onBeforeUnmount, ref } from 'vue'
import * as api from '../../api/embeddingEvaluation'
import { errorText, isActiveRun } from './evaluationState'

export function useRunPolling(knowledgeId) {
  const run = ref(null),
    error = ref(''),
    busy = ref(false)
  let timer,
    generation = 0,
    disposed = false,
    selectedId = null
  function stop() {
    clearTimeout(timer)
    timer = undefined
  }
  function schedule() {
    stop()
    if (!disposed && isActiveRun(run.value)) timer = setTimeout(refresh, 1500)
  }
  function setRun(value) {
    generation++
    stop()
    selectedId = value?.id
    run.value = value
    error.value = ''
    busy.value = false
    schedule()
  }
  async function perform(operation) {
    stop()
    const token = ++generation
    busy.value = true
    error.value = ''
    try {
      const value = await operation()
      if (disposed || token !== generation) return
      run.value = value
      selectedId = value.id
      schedule()
    } catch (cause) {
      if (!disposed && token === generation) error.value = errorText(cause)
    } finally {
      if (!disposed && token === generation) busy.value = false
    }
  }
  async function open(id) {
    run.value = null
    selectedId = id
    return perform(() => api.getRun(knowledgeId(), id))
  }
  async function refresh() {
    if (selectedId) return perform(() => api.getRun(knowledgeId(), selectedId))
  }
  async function cancel() {
    if (isActiveRun(run.value)) return perform(() => api.cancelRun(knowledgeId(), selectedId))
  }
  async function retry() {
    if (run.value && !isActiveRun(run.value)) return perform(() => api.retryRun(knowledgeId(), selectedId))
  }
  onBeforeUnmount(() => {
    disposed = true
    generation++
    stop()
  })
  return { run, error, busy, setRun, open, refresh, cancel, retry }
}
