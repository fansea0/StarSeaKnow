import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, expect, it, vi } from 'vitest'
import EmbeddingModels from '../EmbeddingModels.vue'
import * as api from '../../../api/embeddingEvaluation'
vi.mock('../../../api/embeddingEvaluation', () => ({
  listModels: vi.fn(),
  createModel: vi.fn(),
  updateModel: vi.fn(),
  testModel: vi.fn(),
  deleteModel: vi.fn(),
}))
beforeEach(() => {
  vi.clearAllMocks()
  api.listModels.mockResolvedValue([
    { id: 'current', displayName: 'current', readOnly: true, revision: 1, dimensions: 768 },
  ])
})
it('keeps the current baseline read-only and validates then saves an explicit candidate configuration', async () => {
  const wrapper = mount(EmbeddingModels)
  await flushPromises()
  expect(wrapper.find('[data-testid="edit-embedding-current"]').exists()).toBe(false)
  await wrapper.get('[data-testid="add-embedding"]').trigger('click')
  await wrapper.get('[name="displayName"]').setValue('候选')
  await wrapper.get('[name="baseUrl"]').setValue('http://ollama:11434')
  await wrapper.get('[name="modelName"]').setValue('bge:latest')
  api.testModel.mockResolvedValue({ dimensions: 1024, digest: 'sha256:abc' })
  await wrapper.get('[data-testid="test-embedding"]').trigger('click')
  await flushPromises()
  expect(wrapper.text()).toContain('1024')
  api.createModel.mockResolvedValue({ id: 'b', displayName: '候选' })
  await wrapper.get('[data-testid="embedding-form"]').trigger('submit')
  await flushPromises()
  expect(api.createModel).toHaveBeenCalledWith({
    displayName: '候选',
    baseUrl: 'http://ollama:11434',
    modelName: 'bge:latest',
    queryPrefix: '',
    documentPrefix: '',
  })
  wrapper.unmount()
})

it('retains explicit runtime options when editing a candidate', async () => {
  api.listModels.mockResolvedValue([{ id: 'b', displayName: '候选', baseUrl: 'http://localhost:11434',
    modelName: 'bge:latest', revision: 2, options: { num_ctx: 1024 }, keepAlive: '10m' }])
  const wrapper = mount(EmbeddingModels)
  await flushPromises()
  await wrapper.get('[data-testid="edit-embedding-b"]').trigger('click')
  await wrapper.get('[name="displayName"]').setValue('候选改名')
  await wrapper.get('[data-testid="embedding-form"]').trigger('submit')
  await flushPromises()
  expect(api.updateModel).toHaveBeenCalledWith('b', expect.objectContaining({
    revision: 2, options: { num_ctx: 1024 }, keepAlive: '10m', displayName: '候选改名',
  }))
  wrapper.unmount()
})
