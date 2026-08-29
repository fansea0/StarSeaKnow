import { beforeEach, describe, expect, it, vi } from 'vitest'
import { authenticatedFetch } from './authenticatedFetch'

const { auth } = vi.hoisted(() => ({
  auth: { accessToken: 'access-1', silentRefresh: vi.fn() },
}))

vi.mock('../stores/auth', () => ({ useAuthStore: () => auth }))

describe('authenticatedFetch', () => {
  beforeEach(() => {
    auth.accessToken = 'access-1'
    auth.silentRefresh.mockReset()
    vi.stubGlobal('fetch', vi.fn())
  })

  it('adds the active access token to a streaming request', async () => {
    fetch.mockResolvedValue({ status: 200 })

    await authenticatedFetch('/api/ai/agent/chat', { method: 'POST' })

    expect(fetch).toHaveBeenCalledWith('/api/ai/agent/chat', expect.objectContaining({
      headers: { Authorization: 'Bearer access-1' },
      credentials: 'include',
    }))
  })

  it('refreshes from the session cookie before requesting when no access token is in memory', async () => {
    auth.accessToken = null
    auth.silentRefresh.mockImplementation(async () => { auth.accessToken = 'access-from-refresh' })
    fetch.mockResolvedValue({ status: 200 })

    await authenticatedFetch('/api/ai/agent/chat', { method: 'POST' })

    expect(auth.silentRefresh).toHaveBeenCalledOnce()
    expect(fetch).toHaveBeenCalledWith('/api/ai/agent/chat', expect.objectContaining({
      headers: { Authorization: 'Bearer access-from-refresh' },
    }))
  })

  it('refreshes an expired access token and retries the request once', async () => {
    const expired = { status: 401, clone: () => ({ json: async () => ({ code: 40101 }) }) }
    fetch.mockResolvedValueOnce(expired).mockResolvedValueOnce({ status: 200 })
    auth.silentRefresh.mockImplementation(async () => { auth.accessToken = 'access-2' })

    await authenticatedFetch('/api/ai/agent/chat', { method: 'POST' })

    expect(auth.silentRefresh).toHaveBeenCalledOnce()
    expect(fetch).toHaveBeenLastCalledWith('/api/ai/agent/chat', expect.objectContaining({
      headers: { Authorization: 'Bearer access-2' },
    }))
  })
})
