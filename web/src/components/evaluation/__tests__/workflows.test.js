import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, expect, it, vi } from 'vitest'
import QuickComparison from '../QuickComparison.vue'
import DatasetWorkspace from '../DatasetWorkspace.vue'
import * as api from '../../../api/embeddingEvaluation'
vi.mock('../../../api/embeddingEvaluation', () => ({
  createSnapshot: vi.fn(),
  getSnapshot: vi.fn(),
  createRun: vi.fn(),
  getRun: vi.fn(),
  cancelRun: vi.fn(),
  retryRun: vi.fn(),
  listDatasets: vi.fn(),
  createDataset: vi.fn(),
  updateDataset: vi.fn(),
  getDataset: vi.fn(),
  exportRun: vi.fn(),
  downloadReport: vi.fn(),
}))
const chunks = [{ id: 'c', content: '正文', indexContent: '标题\n正文', fileId: 'f', fileName: '手册.md' }]
const snapshot = { id: 's', scope: 'ALL', hash: 'hash', chunks }
const question = {
  id: 'q',
  query: '原问题',
  intentGroup: 'g',
  category: '安装',
  split: 'CALIBRATION',
  answerable: true,
  reviewed: false,
  labels: {},
  hardNegativeIds: [],
}
const dataset = {
  id: 'd',
  revision: 3,
  snapshotId: 's',
  name: '安装问题',
  questions: [question],
  frozen: false,
}
beforeEach(() => {
  vi.clearAllMocks()
  api.createSnapshot.mockResolvedValue(snapshot)
  api.getSnapshot.mockResolvedValue(snapshot)
  api.listDatasets.mockResolvedValue([dataset])
  api.getDataset.mockResolvedValue(structuredClone(dataset))
})
it('freezes real chunks, compares a query, marks changed input stale, and saves the question with its snapshot', async () => {
  const wrapper = mount(QuickComparison, {
    props: { knowledgeId: '11', chunks, models: [{ id: 'current', revision: 1 }], datasets: [] },
  })
  await wrapper.get('[data-testid="freeze-snapshot"]').trigger('click')
  await flushPromises()
  await wrapper.get('[data-testid="question-query"]').setValue('如何安装？')
  api.createRun.mockResolvedValue({
    id: 'r',
    phase: 'QUICK',
    scope: 'ALL',
    status: 'COMPLETED',
    questions: [{ ...question, query: '如何安装？' }],
    models: [{ id: 'current' }],
    modelResults: [],
    progress: { completed: 1, total: 1 },
  })
  await wrapper.get('[data-testid="start-quick"]').trigger('click')
  await flushPromises()
  expect(api.createRun.mock.calls[0][1]).toMatchObject({
    snapshotId: 's',
    phase: 'QUICK',
    modelIds: ['current'],
    thresholds: {},
    retrievalMode: 'EXACT',
  })
  expect(api.createRun.mock.calls[0][1].questions[0].query).toBe('如何安装？')
  await wrapper.get('[data-testid="question-query"]').setValue('怎样安装？')
  expect(wrapper.text()).toContain('待重新计算')
  await wrapper.get('[data-testid="quick-dataset-name"]').setValue('真实问题')
  api.createDataset.mockResolvedValue({ ...dataset, name: '真实问题' })
  await wrapper.get('[data-testid="save-quick-question"]').trigger('click')
  await flushPromises()
  expect(api.createDataset.mock.calls[0][1]).toMatchObject({
    name: '真实问题',
    snapshotId: 's',
    frozen: false,
    questions: [expect.objectContaining({ query: '怎样安装？', labels: {} })],
  })
  wrapper.unmount()
})
it('automatically freezes the current chunk when opened from the chunk comparison entry', async () => {
  api.createSnapshot.mockResolvedValue({ ...snapshot, scope: 'SELECTED' })
  const wrapper = mount(QuickComparison, {
    props: {
      knowledgeId: '11',
      chunks,
      models: [{ id: 'current', revision: 1 }],
      datasets: [],
      initialChunkId: 'c',
    },
  })

  await flushPromises()

  expect(api.createSnapshot).toHaveBeenCalledWith('11', {
    scope: 'SELECTED',
    chunkIds: ['c'],
  })
  expect(wrapper.get('[data-testid="start-quick"]').attributes('disabled')).toBeUndefined()
  expect(wrapper.text()).toContain('候选集内对比 · 1 份文档 · 1 块')
  wrapper.unmount()
})
it('explains why comparison is unavailable when the current chunk cannot be prepared', async () => {
  api.createSnapshot.mockRejectedValue(new Error('当前分块尚未保存'))
  const wrapper = mount(QuickComparison, {
    props: {
      knowledgeId: '11',
      chunks,
      models: [{ id: 'current', revision: 1 }],
      datasets: [],
      initialChunkId: 'c',
    },
  })

  await flushPromises()

  expect(wrapper.get('[role="alert"]').text()).toContain('无法准备当前分块：当前分块尚未保存')
  expect(wrapper.get('[data-testid="start-disabled-reason"]').text()).toBe('请先在上方冻结语料快照。')
  expect(wrapper.get('[data-testid="start-quick"]').attributes('disabled')).toBeDefined()
  wrapper.unmount()
})
it('edits labels with revision checks and adds an unreviewed variant in the same intent group', async () => {
  const wrapper = mount(DatasetWorkspace, {
    props: { knowledgeId: '11', chunks, datasets: [dataset], selectedId: 'd' },
  })
  await flushPromises()
  await wrapper.get('[data-testid="label-c"]').setValue('2')
  await wrapper.get('[data-testid="question-reviewed"]').setValue(true)
  api.updateDataset.mockResolvedValue({
    ...dataset,
    revision: 4,
    questions: [{ ...question, reviewed: true, labels: { c: 2 } }],
  })
  await wrapper.get('[data-testid="save-dataset"]').trigger('click')
  await flushPromises()
  expect(api.updateDataset.mock.calls[0][2]).toMatchObject({
    revision: 3,
    questions: [expect.objectContaining({ labels: { c: 2 }, reviewed: true })],
  })
  await wrapper.get('[data-testid="add-variant"]').trigger('click')
  expect(wrapper.get('[data-testid="intent-group"]').element.value).toBe('g')
  expect(wrapper.get('[data-testid="question-reviewed"]').element.checked).toBe(false)
  expect(wrapper.get('[data-testid="label-c"]').element.value).toBe('')
  wrapper.unmount()
})

it('keeps a frozen revision intact while edits use PUT to create a new revision', async () => {
  api.getDataset.mockResolvedValue({ ...structuredClone(dataset), frozen: true })
  const wrapper = mount(DatasetWorkspace, {
    props: { knowledgeId: '11', chunks, datasets: [dataset], selectedId: 'd' },
  })
  await flushPromises()
  expect(wrapper.get('[data-testid="question-query"]').element.closest('fieldset').disabled).toBe(true)
  const editButton = wrapper.findAll('button').find((button) => button.text() === '基于此版本编辑')
  await editButton.trigger('click')
  await wrapper.get('[data-testid="question-query"]').setValue('新的问法')
  api.updateDataset.mockResolvedValue({ ...dataset, revision: 4 })
  await wrapper.get('[data-testid="save-dataset"]').trigger('click')
  await flushPromises()
  expect(api.updateDataset).toHaveBeenCalledWith(
    '11',
    'd',
    expect.objectContaining({
      revision: 3,
      frozen: false,
      questions: [expect.objectContaining({ query: '新的问法' })],
    }),
  )
  wrapper.unmount()
})
