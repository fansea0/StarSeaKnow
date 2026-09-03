import { mount, flushPromises } from '@vue/test-utils'
import { expect, it, vi } from 'vitest'
import RunReport from '../RunReport.vue'
import * as api from '../../../api/embeddingEvaluation'
vi.mock('../../../api/embeddingEvaluation', () => ({ exportRun: vi.fn(), downloadReport: vi.fn() }))
const run = {
  id: 'r',
  status: 'PARTIAL',
  phase: 'ACCEPTANCE',
  verdict: 'INSUFFICIENT',
  topK: 5,
  baselineModelId: 'a',
  questions: [
    {
      id: 'q',
      query: '原问题',
      reviewed: true,
      answerable: true,
      labels: { c: 2, outside: 0 },
      hardNegativeIds: ['outside'],
    },
  ],
  models: [
    { id: 'a', displayName: 'A' },
    { id: 'b', displayName: 'B' },
  ],
  modelResults: [
    {
      modelId: 'a',
      status: 'COMPLETED',
      metrics: { hit1: 0, p95Ms: 0 },
      queries: [
        {
          questionId: 'q',
          status: 'COMPLETED',
          hits: [{ chunkId: 'c', score: 0, rank: 1, label: 2 }],
          judgedScores: [{ chunkId: 'outside', rank: 20, score: -0.2, label: 0 }],
        },
      ],
    },
    { modelId: 'b', status: 'FAILED', error: '模型不存在', queries: [] },
  ],
}
it('renders zero distinctly from missing and retains explicitly scored labels outside saved top K', () => {
  const wrapper = mount(RunReport, { props: { run, knowledgeId: '11' } })
  expect(wrapper.text()).toContain('0.0%')
  expect(wrapper.text()).toContain('证据不足')
  expect(wrapper.get('[data-testid="aligned-c"]').text()).toContain('0.0000')
  expect(wrapper.get('[data-testid="aligned-c"]').text()).toContain('计算失败')
  expect(wrapper.get('[data-testid="aligned-outside"]').text()).toContain('-0.2000')
  wrapper.unmount()
})
it('downloads the stored report and displays export errors', async () => {
  api.exportRun.mockResolvedValueOnce(new Blob(['report']))
  const wrapper = mount(RunReport, { props: { run, knowledgeId: '11' } })
  await wrapper.get('[data-testid="export-html"]').trigger('click')
  await flushPromises()
  expect(api.exportRun).toHaveBeenCalledWith('11', 'r', 'html')
  expect(api.downloadReport).toHaveBeenCalledWith(expect.any(Blob), 'r', 'html')
  api.exportRun.mockRejectedValueOnce(new Error('报告已无权访问'))
  await wrapper.get('[data-testid="export-csv"]').trigger('click')
  await flushPromises()
  expect(
    wrapper
      .findAll('[role="alert"]')
      .map((node) => node.text())
      .join(' '),
  ).toContain('报告已无权访问')
  wrapper.unmount()
})
