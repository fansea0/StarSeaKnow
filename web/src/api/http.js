import axios from 'axios'

export const apiBaseUrl = (import.meta.env.VITE_API_BASE_URL || '/api').replace(/\/$/, '')
const legacyApiOrigin = (import.meta.env.VITE_LEGACY_API_ORIGIN || 'http://localhost:8080').replace(/\/$/, '')

export function apiUrl(path) {
  return `${apiBaseUrl}/${String(path).replace(/^\//, '')}`
}

export function normalizeApiRequestUrl(url) {
  if (typeof url !== 'string' || !url.startsWith(legacyApiOrigin)) return url
  return apiUrl(url.slice(legacyApiOrigin.length))
}

axios.interceptors.request.use(config => {
  config.url = normalizeApiRequestUrl(config.url)
  return config
})

export const http = axios.create({
  baseURL: apiBaseUrl,
  withCredentials: true,
  timeout: 30000,
})

let auth = null

export function bindAuth(store) { auth = store }
