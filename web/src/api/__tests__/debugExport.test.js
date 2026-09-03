import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { exportDebugContext } from '../agents'

const auth = vi.hoisted(() => ({ accessToken: 'old-token', silentRefresh: vi.fn() }))
vi.mock('../http', () => ({ http: { get: vi.fn() }, apiUrl: p => `/api${p}` }))
vi.mock('../../stores/auth', () => ({ useAuthStore: () => auth }))
const json = (body, status = 200) => new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
beforeEach(() => {
  vi.resetAllMocks()
  auth.accessToken = 'old-token'
  vi.stubGlobal('fetch', vi.fn())
})
afterEach(() => vi.unstubAllGlobals())

it('downloads a JSON attachment with authentication', async () => {
  fetch.mockResolvedValue(json({ schemaVersion: 1 }))
  expect(await (await exportDebugContext(3, 'context-id')).text()).toBe('{"schemaVersion":1}')
  expect(fetch).toHaveBeenCalledWith('/api/agents/3/debug-contexts/context-id/export', expect.objectContaining({
    credentials: 'include', headers: expect.objectContaining({ Authorization: 'Bearer old-token' }),
  }))
})
it('preserves backend JSON errors for the session error message', async () => {
  fetch.mockResolvedValue(json({ msg: '调试上下文已失效' }, 410))
  await expect(exportDebugContext(3, 'context-id')).rejects.toMatchObject({ response: { status: 410, data: { msg: '调试上下文已失效' } } })
})
it('refreshes an expired access token before retrying the download', async () => {
  fetch.mockResolvedValueOnce(json({ code: 40101 }, 401)).mockResolvedValueOnce(json({ schemaVersion: 1 }))
  auth.silentRefresh.mockImplementation(async () => { auth.accessToken = 'new-token' })
  expect(await (await exportDebugContext(3, 'context-id')).text()).toBe('{"schemaVersion":1}')
  expect(auth.silentRefresh).toHaveBeenCalledTimes(1)
  expect(fetch).toHaveBeenCalledTimes(2)
  expect(fetch.mock.calls[1][1].headers.Authorization).toBe('Bearer new-token')
})
