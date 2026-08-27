import axios from 'axios'

export const http = axios.create({
  baseURL: '/api',
  withCredentials: true,
  timeout: 30000,
})

let auth = null

export function bindAuth(store) { auth = store }
