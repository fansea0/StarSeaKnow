import { beforeEach, describe, expect, it, vi } from 'vitest'

const { request } = vi.hoisted(() => ({
  request: {
    get: vi.fn(),
    post: vi.fn(),
    put: vi.fn(),
    delete: vi.fn(),
  },
}))

vi.mock('../http', () => ({ http: request }))

import {
  addProviderModel,
  createProviderConnection,
  deleteProviderConnection,
  listModelProviders,
  replaceProviderModels,
  testProviderConnection,
  updateProviderConnection,
} from '../modelProviders'

describe('model provider api', () => {
  beforeEach(() => vi.clearAllMocks())

  it('uses the tenant model provider endpoints', () => {
    const command = { catalogProviderId: 1, apiKey: 'secret' }
    listModelProviders()
    testProviderConnection(command)
    createProviderConnection(command)
    updateProviderConnection(9, command)
    addProviderModel(9, { modelId: 'gpt-4o', displayName: 'GPT-4o', contextWindow: 128000 })
    replaceProviderModels(9, [])
    deleteProviderConnection(9)

    expect(request.get).toHaveBeenCalledWith('/model-providers')
    expect(request.post).toHaveBeenCalledWith('/model-providers/connections/test', command)
    expect(request.post).toHaveBeenCalledWith('/model-providers/connections', command)
    expect(request.put).toHaveBeenCalledWith('/model-providers/connections/9', command)
    expect(request.post).toHaveBeenCalledWith('/model-providers/connections/9/models', expect.any(Object))
    expect(request.put).toHaveBeenCalledWith('/model-providers/connections/9/models', { models: [] })
    expect(request.delete).toHaveBeenCalledWith('/model-providers/connections/9')
  })
})
