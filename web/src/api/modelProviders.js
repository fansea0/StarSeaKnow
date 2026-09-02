import { http } from './http'

const root = '/model-providers'

export function listModelProviders() {
  return http.get(root)
}

export function testProviderConnection(command) {
  return http.post(`${root}/connections/test`, command)
}

export function createProviderConnection(command) {
  return http.post(`${root}/connections`, command)
}

export function updateProviderConnection(connectionId, command) {
  return http.put(`${root}/connections/${connectionId}`, command)
}

export function addProviderModel(connectionId, model) {
  return http.post(`${root}/connections/${connectionId}/models`, model)
}

export function replaceProviderModels(connectionId, models) {
  return http.put(`${root}/connections/${connectionId}/models`, { models })
}

export function deleteProviderConnection(connectionId) {
  return http.delete(`${root}/connections/${connectionId}`)
}
