import { mount, flushPromises } from '@vue/test-utils'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { useRunPolling } from '../useRunPolling'
import * as api from '../../../api/embeddingEvaluation'
vi.mock('../../../api/embeddingEvaluation', () => ({
  getRun: vi.fn(),
  cancelRun: vi.fn(),
  retryRun: vi.fn(),
}))
let wrapper, state
beforeEach(() => {
  vi.useFakeTimers()
  vi.clearAllMocks()
  wrapper = mount({
    setup() {
      state = useRunPolling(() => 'kb')
      return () => null
    },
  })
})
afterEach(() => {
  wrapper.unmount()
  vi.useRealTimers()
})
it('polls until complete, retains partial results, and cancels without zeroing evidence', async () => {
  api.getRun.mockResolvedValue({ id: 'r', status: 'RUNNING', modelResults: [{ modelId: 'a', queries: [] }] })
  state.setRun({ id: 'r', status: 'QUEUED' })
  await vi.advanceTimersByTimeAsync(1500)
  expect(state.run.value.modelResults).toHaveLength(1)
  api.cancelRun.mockResolvedValue({ ...state.run.value, status: 'CANCELLED' })
  await state.cancel()
  expect(state.run.value.status).toBe('CANCELLED')
  expect(state.run.value.modelResults).toHaveLength(1)
  const count = api.getRun.mock.calls.length
  await vi.advanceTimersByTimeAsync(4000)
  expect(api.getRun).toHaveBeenCalledTimes(count)
})
it('ignores an older response after selecting a different run and stops on unmount', async () => {
  let resolve
  api.getRun.mockReturnValueOnce(
    new Promise((r) => {
      resolve = r
    }),
  )
  const old = state.open('old')
  state.setRun({ id: 'new', status: 'COMPLETED' })
  resolve({ id: 'old', status: 'RUNNING' })
  await old
  expect(state.run.value.id).toBe('new')
  wrapper.unmount()
  await vi.advanceTimersByTimeAsync(4000)
  expect(api.getRun).toHaveBeenCalledTimes(1)
})
it('surfaces polling failure with a manual resume and retry creates a new run', async () => {
  api.getRun.mockRejectedValueOnce(new Error('连接中断'))
  await state.open('r')
  expect(state.error.value).toContain('连接中断')
  api.getRun.mockResolvedValue({ id: 'r', status: 'FAILED' })
  await state.refresh()
  expect(state.run.value.status).toBe('FAILED')
  api.retryRun.mockResolvedValue({ id: 'retry', status: 'QUEUED' })
  await state.retry()
  expect(state.run.value.id).toBe('retry')
  await flushPromises()
})
