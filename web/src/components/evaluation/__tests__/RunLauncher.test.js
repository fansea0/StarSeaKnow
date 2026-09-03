import { mount, flushPromises } from '@vue/test-utils'
import { beforeEach, expect, it, vi } from 'vitest'
import RunLauncher from '../RunLauncher.vue'
import * as api from '../../../api/embeddingEvaluation'
vi.mock('../../../api/embeddingEvaluation', () => ({
  getDataset: vi.fn(),
  getRun: vi.fn(),
  createRun: vi.fn(),
}))
const models = [{ id: 'current', revision: 2, digest: 'sha:1' }]
const dataset = { id: 'd', revision: 4, frozen: true, questions: [{ split: 'ACCEPTANCE' }] }
const calibration = {
  id: 'cal',
  phase: 'CALIBRATION',
  status: 'COMPLETED',
  models,
  modelResults: [
    {
      modelId: 'current',
      status: 'COMPLETED',
      calibration: [{ threshold: 0, evidenceRetentionRate: 1, noAnswerFalsePositiveRate: 0 }],
    },
  ],
}
beforeEach(() => {
  vi.clearAllMocks()
  api.getDataset.mockResolvedValue(dataset)
  api.getRun.mockResolvedValue(calibration)
  api.createRun.mockResolvedValue({ id: 'accept', status: 'QUEUED' })
})
it('requires an explicit calibrated threshold selection and binds the source run plus frozen revision', async () => {
  const wrapper = mount(RunLauncher, {
    props: {
      knowledgeId: 'kb',
      models,
      datasets: [{ ...dataset, name: '验收集' }],
      initialDataset: dataset,
      runs: [calibration],
    },
  })
  await flushPromises()
  await wrapper.get('[data-testid="run-phase"]').setValue('ACCEPTANCE')
  await wrapper.get('[data-testid="calibration-run"]').setValue('cal')
  await flushPromises()
  await wrapper.get('[data-testid="launch-run"]').trigger('click')
  expect(api.createRun).not.toHaveBeenCalled()
  expect(wrapper.text()).toContain('逐个选择')
  await wrapper.get('input[type="radio"]').setValue(true)
  for (const [key, value] of Object.entries({
    minQuestions: 10,
    minUnanswerable: 2,
    hit5Min: 0.9,
    evidenceRetentionMin: 0.8,
    noAnswerFalsePositiveMax: 0,
    p95MaxMs: 1000,
  })) {
    await wrapper.get(`[name="${key}"]`).setValue(value)
  }
  await wrapper.get('[data-testid="launch-run"]').trigger('click')
  await flushPromises()
  expect(api.createRun.mock.calls[0][1]).toMatchObject({
    calibrationRunId: 'cal',
    datasetId: 'd',
    datasetRevision: 4,
    phase: 'ACCEPTANCE',
    thresholds: { current: 0 },
    requirements: { noAnswerFalsePositiveMax: 0 },
  })
  expect(wrapper.emitted('created')[0][0].id).toBe('accept')
  wrapper.unmount()
})
it('refuses a calibration from a different model revision', async () => {
  const wrapper = mount(RunLauncher, {
    props: {
      knowledgeId: 'kb',
      models: [{ ...models[0], revision: 3 }],
      datasets: [dataset],
      initialDataset: dataset,
      runs: [calibration],
    },
  })
  await flushPromises()
  await wrapper.get('[data-testid="run-phase"]').setValue('ACCEPTANCE')
  await wrapper.get('[data-testid="calibration-run"]').setValue('cal')
  await flushPromises()
  await wrapper.get('[data-testid="launch-run"]').trigger('click')
  expect(wrapper.text()).toContain('模型版本已变化')
  expect(api.createRun).not.toHaveBeenCalled()
  wrapper.unmount()
})
