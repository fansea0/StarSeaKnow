import { useAuthStore } from '../stores/auth'

export async function authenticatedFetch(url, options = {}) {
  const auth = useAuthStore()
  if (!auth.accessToken) {
    await auth.silentRefresh()
  }

  let response = await request(url, options, auth.accessToken)
  if (await isExpiredAccessToken(response)) {
    await auth.silentRefresh()
    response = await request(url, options, auth.accessToken)
  }
  return response
}

function request(url, options, accessToken) {
  return fetch(url, {
    ...options,
    credentials: 'include',
    headers: {
      ...options.headers,
      Authorization: `Bearer ${accessToken}`,
    },
  })
}

async function isExpiredAccessToken(response) {
  if (response.status !== 401 || typeof response.clone !== 'function') return false
  try {
    return (await response.clone().json()).code === 40101
  } catch {
    return false
  }
}
