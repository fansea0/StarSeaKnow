import { beforeEach, describe, expect, it, vi } from 'vitest'
import { http } from '../http'
import { createRun, getDataset, listModels, exportRun } from '../embeddingEvaluation'
vi.mock('../http', () => ({ http: { get: vi.fn(), post: vi.fn() } }))
beforeEach(() => vi.clearAllMocks())
describe('embedding evaluation API contract', () => {
  it('unwraps AjaxResult and sends immutable dataset revision using the shared authenticated client', async () => {
    http.post.mockResolvedValue({ data: { code: 200, data: { id: 'run' } } })
    const command = {
      datasetId: 'd',
      datasetRevision: 4,
      phase: 'ACCEPTANCE',
      modelIds: ['current'],
      thresholds: { current: 0 },
    }
    expect(await createRun('11', command)).toEqual({ id: 'run' })
    expect(http.post).toHaveBeenCalledWith('/knowledge/11/embedding-evaluation/runs', command)
    http.get.mockResolvedValue({ data: { code: 200, data: { revision: 4 } } })
    await getDataset('11', 'd', 4)
    expect(http.get).toHaveBeenCalledWith('/knowledge/11/embedding-evaluation/datasets/d', {
      params: { revision: 4 },
    })
  })
  it('rejects HTTP-success business errors instead of showing empty success', async () => {
    http.get.mockResolvedValue({ data: { code: 500, msg: '模型服务不可达' } })
    await expect(listModels()).rejects.toThrow('模型服务不可达')
  })
  it('requests authenticated report files and surfaces error envelopes returned as blobs', async () => {
    http.get.mockResolvedValue({
      data: new Blob([JSON.stringify({ code: 403, msg: '无权导出' })], { type: 'application/json' }),
    })
    await expect(exportRun('11', 'r', 'json')).rejects.toThrow('无权导出')
    expect(http.get).toHaveBeenCalledWith('/knowledge/11/embedding-evaluation/runs/r/export', {
      params: { format: 'json' },
      responseType: 'blob',
    })
  })
})
