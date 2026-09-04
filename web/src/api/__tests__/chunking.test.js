import { http } from '../http'
import {
  confirmVectorization,
  createPreview,
  deleteChunk,
  getChunks,
  getProcessing,
  getStrategies,
  reindexChunk,
  updateChunk,
} from '../chunking'

vi.mock('../http', () => ({
  http: {
    get: vi.fn(),
    post: vi.fn(),
    patch: vi.fn(),
    delete: vi.fn(),
  },
}))

describe('chunking API client', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('uses the chunking endpoint paths with the configured http client', () => {
    const preview = { strategyCode: 'MARKDOWN_OPTIMIZED', strategyConfig: { minTokens: 100 } }
    const chunkUpdate = {
      content: 'updated',
      overlapEnabled: true,
      overlapLimit: 64,
      overlapUnit: 'CHARACTERS',
      overlapTokenLimit: 99,
      lockVersion: 4,
      ignoredInternalField: 'must-not-leak',
    }
    const confirm = { lockVersion: 4, overlapEnabled: true, overlapTokens: 64 }

    getStrategies(11, 22)
    createPreview(11, 22, preview)
    getProcessing(11, 22)
    getChunks(11, 22)
    updateChunk(11, 22, 'chunk-1', chunkUpdate)
    deleteChunk(11, 22, 'chunk-1', 4)
    confirmVectorization(11, 22, confirm)
    reindexChunk(11, 22, 'chunk-1')

    expect(http.get).toHaveBeenNthCalledWith(1, '/knowledge/11/files/22/chunk-strategies')
    expect(http.post).toHaveBeenNthCalledWith(1, '/knowledge/11/files/22/chunk-preview', preview)
    expect(http.get).toHaveBeenNthCalledWith(2, '/knowledge/11/files/22/processing')
    expect(http.get).toHaveBeenNthCalledWith(3, '/knowledge/11/files/22/chunks')
    expect(http.patch).toHaveBeenCalledWith('/knowledge/11/files/22/chunks/chunk-1', {
      content: 'updated',
      overlapEnabled: true,
      overlapLimit: 64,
      overlapUnit: 'CHARACTERS',
      lockVersion: 4,
    })
    expect(http.delete).toHaveBeenCalledWith('/knowledge/11/files/22/chunks/chunk-1', { params: { lockVersion: 4 } })
    expect(http.post).toHaveBeenNthCalledWith(2, '/knowledge/11/files/22/confirm', { lockVersion: 4 })
    expect(http.post).toHaveBeenNthCalledWith(3, '/knowledge/11/files/22/chunks/chunk-1/reindex')
  })

  it('submits GENERAL like any backend strategy', () => {
    const request = { strategyCode: 'GENERAL', strategyConfig: { delimiter: '\n' }, contextConfig: { enabled: true, limit: 40 } }
    createPreview(11, 22, request)
    expect(http.post).toHaveBeenCalledWith('/knowledge/11/files/22/chunk-preview', request)
  })
})
