import { mount, flushPromises } from '@vue/test-utils'
import { createPinia } from 'pinia'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import ElementPlus from 'element-plus'
import axios from 'axios'
import { useAuthStore } from '../../../stores/auth'
import EvaluationWorkbench from '../EvaluationWorkbench.vue'
import ModelProviders from '../../../views/ModelProviders.vue'
import KnowledgeDetail from '../../../views/KnowledgeDetail.vue'
import ChunkCard from '../../chunking/ChunkCard.vue'
import * as api from '../../../api/embeddingEvaluation'
import { updateChunk } from '../../../api/chunking'
vi.mock('../../../api/embeddingEvaluation', () => ({
  listModels: vi.fn(),
  listChunks: vi.fn(),
  listDatasets: vi.fn(),
  listRuns: vi.fn(),
}))
vi.mock('../../../api/modelProviders', () => ({
  listModelProviders: vi.fn().mockResolvedValue({ data: { code: 200, data: [] } }),
}))
vi.mock('../../../api/chunking', () => ({ updateChunk: vi.fn(), deleteChunk: vi.fn() }))
function globalOptions(role = 'tenant_admin') {
  const pinia = createPinia()
  useAuthStore(pinia).user = { role }
  return {
    plugins: [pinia, ElementPlus],
    mocks: { $route: { params: { id: '11' } }, $router: { push: vi.fn() } },
  }
}
beforeEach(() => {
  vi.clearAllMocks()
  api.listModels.mockResolvedValue([{ id: 'current', revision: 1 }])
  api.listChunks.mockResolvedValue([])
  api.listDatasets.mockResolvedValue([])
  api.listRuns.mockResolvedValue([])
})
afterEach(() => {
  vi.useRealTimers()
  vi.restoreAllMocks()
})
it('makes no evaluation requests for non-admin users', async () => {
  const wrapper = mount(EvaluationWorkbench, {
    props: { knowledgeId: '11' },
    global: globalOptions('member'),
  })
  await flushPromises()
  expect(wrapper.text()).toContain('仅租户管理员')
  expect(api.listModels).not.toHaveBeenCalled()
  wrapper.unmount()
})
it('opens the admin embedding tab and knowledge evaluation tab through their real parents', async () => {
  const wrapper = mount(ModelProviders, { global: globalOptions() })
  await flushPromises()
  await wrapper.get('[data-testid="embedding-tab"]').trigger('click')
  await flushPromises()
  expect(wrapper.find('[aria-label="向量模型配置"]').exists()).toBe(true)
  wrapper.unmount()
  vi.spyOn(axios, 'get').mockResolvedValue({ data: { code: 200, data: { id: 11, name: '手册' } } })
  const knowledge = mount(KnowledgeDetail, { global: globalOptions() })
  await flushPromises()
  await knowledge.get('[data-testid="knowledge-evaluation-tab"]').trigger('click')
  await flushPromises()
  expect(knowledge.findComponent(EvaluationWorkbench).props('knowledgeId')).toBe(11)
  knowledge.unmount()
})
it('blocks chunk snapshot shortcuts until the actual save finishes', async () => {
  vi.useFakeTimers()
  let finishSave
  const chunk = { publicId: 'c', content: '正文', position: 0, lockVersion: 1, status: 0 }
  updateChunk.mockReturnValue(
    new Promise((resolve) => {
      finishSave = resolve
    }),
  )
  const wrapper = mount(ChunkCard, {
    props: { knowledgeId: '11', fileId: '22', chunk },
    global: globalOptions(),
  })
  await wrapper.get('[data-testid="edit-chunk"]').trigger('click')
  await wrapper.get('textarea').setValue('更新正文')
  expect(wrapper.get('[data-testid="evaluate-chunk"]').attributes('disabled')).toBeDefined()
  await vi.advanceTimersByTimeAsync(650)
  expect(wrapper.get('[data-testid="evaluate-chunk"]').attributes('disabled')).toBeDefined()
  finishSave({ data: { ...chunk, content: '更新正文', lockVersion: 2 } })
  await flushPromises()
  expect(wrapper.get('[data-testid="evaluate-chunk"]').attributes('disabled')).toBeUndefined()
  wrapper.unmount()
})
