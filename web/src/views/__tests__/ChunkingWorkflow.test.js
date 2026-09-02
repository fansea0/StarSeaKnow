import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus, { ElMessageBox } from 'element-plus'
import { reactive } from 'vue'
import {
  confirmVectorization,
  deleteChunk,
  getChunks,
  getProcessing,
  getStrategies,
  updateChunk,
} from '../../api/chunking'
import ChunkingWorkspace from '../ChunkingWorkspace.vue'

vi.mock('../../api/chunking', () => ({
  getStrategies: vi.fn(),
  createPreview: vi.fn(),
  getProcessing: vi.fn(),
  getChunks: vi.fn(),
  updateChunk: vi.fn(),
  deleteChunk: vi.fn(),
  confirmVectorization: vi.fn(),
  reindexChunk: vi.fn(),
}))

const route = reactive({ params: { knowledgeId: '10', fileId: '20' } })
const originalChunks = [
  {
    publicId: 'chunk-1', position: 0, content: '第一块原文。',
    sectionPath: ['科大百事通', '招生录取类问题'],
    sourceLocator: { startLine: 1, endLine: 8 }, tokenCount: 18,
    status: 0, isModified: false, lockVersion: 1,
    overlapEnabled: false, overlapTokenLimit: 40,
    overlapContent: null, overlapTokenCount: 0, overlapUnavailableReason: null,
  },
  {
    publicId: 'chunk-2', position: 1, content: '第二块原文。',
    sectionPath: ['科大百事通', '校园生活类问题'],
    sourceLocator: { startLine: 11, endLine: 20 }, tokenCount: 16,
    status: 0, isModified: false, lockVersion: 1,
    overlapEnabled: false, overlapTokenLimit: 40,
    overlapContent: null, overlapTokenCount: 0, overlapUnavailableReason: null,
  },
]

function processing(state, lockVersion, contextPolicy = {}) {
  return {
    data: {
      state,
      failedFromState: null,
      progress: state === 6 ? 100 : 0,
      lastError: null,
      lockVersion,
      strategyCode: 'MARKDOWN_OPTIMIZED',
      policySnapshot: { minTokens: 100, targetTokens: 400, maxTokens: 512 },
      contextPolicy,
    },
  }
}

function mountWorkspace() {
  return mount(ChunkingWorkspace, {
    attachTo: document.body,
    global: { plugins: [ElementPlus], mocks: { $route: route } },
  })
}

describe('Markdown chunking workflow', () => {
  beforeEach(() => {
    vi.useFakeTimers()
    vi.clearAllMocks()
    getStrategies.mockResolvedValue({
      data: {
        fileType: 'md',
        strategies: [{
          code: 'MARKDOWN_OPTIMIZED',
          supportedFileTypes: ['md', 'markdown'],
          plannerVersion: 'markdown-adaptive-v1',
        }],
      },
    })
  })

  afterEach(() => {
    document.body.innerHTML = ''
    vi.restoreAllMocks()
    vi.useRealTimers()
  })

  it('restores ADJUSTING edits, saves per-chunk overlap, and completes after summary confirmation', async () => {
    let chunks = originalChunks.map(chunk => ({ ...chunk }))
    let fileState = 2
    let fileLock = 3
    getProcessing.mockImplementation(() => Promise.resolve(processing(fileState, fileLock)))
    getChunks.mockImplementation(() => Promise.resolve({ data: chunks.map(chunk => ({ ...chunk })) }))
    updateChunk.mockImplementation((knowledgeId, fileId, chunkId, request) => {
      const chunk = chunks.find(candidate => candidate.publicId === chunkId)
      Object.assign(chunk, {
        content: request.content,
        overlapEnabled: request.overlapEnabled,
        overlapTokenLimit: request.overlapTokenLimit,
        overlapContent: request.overlapEnabled ? '仅来自服务端的补充文本' : null,
        overlapTokenCount: request.overlapEnabled ? 7 : 0,
        overlapUnavailableReason: null,
        tokenCount: 24,
        status: 0,
        isModified: true,
        lockVersion: request.lockVersion + 1,
      })
      fileState = 3
      fileLock += 1
      return Promise.resolve({ data: { ...chunk } })
    })
    deleteChunk.mockImplementation((knowledgeId, fileId, chunkId) => {
      chunks = chunks.filter(chunk => chunk.publicId !== chunkId)
      fileState = 3
      fileLock += 1
      return Promise.resolve({ status: 204 })
    })
    confirmVectorization.mockImplementation((knowledgeId, fileId, request) => {
      expect(request).toEqual({ lockVersion: fileLock })
      fileState = 5
      fileLock += 1
      return Promise.resolve({ status: 202 })
    })

    let wrapper = mountWorkspace()
    await flushPromises()
    await wrapper.findAll('[data-testid="edit-chunk"]')[0].trigger('click')
    await wrapper.get('textarea').setValue('人工保存后的第一块正文。')
    await vi.advanceTimersByTimeAsync(650)
    await flushPromises()
    vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm')
    await wrapper.findAll('[data-testid="delete-chunk"]')[1].trigger('click')
    await flushPromises()
    wrapper.unmount()

    expect(fileState).toBe(3)
    wrapper = mountWorkspace()
    await flushPromises()
    expect(wrapper.text()).toContain('人工保存后的第一块正文。')
    expect(wrapper.findAll('.chunk-card')).toHaveLength(1)

    await wrapper.get('[data-testid="overlap-switch"] .el-switch').trigger('click')
    await vi.advanceTimersByTimeAsync(650)
    await flushPromises()
    expect(wrapper.get('[data-testid="overlap-token-limit"] input').element.value).toBe('40')
    expect(wrapper.get('[data-testid="overlap-content"]').text()).toContain('仅来自服务端的补充文本')
    expect(wrapper.text()).not.toMatch(/上文：/)

    await wrapper.get('[data-testid="open-confirm"]').trigger('click')
    await flushPromises()
    const dialog = document.body.querySelector('[role="dialog"]')
    expect(dialog.querySelector('[data-testid="confirm-total-count"]').textContent).toContain('1')
    expect(dialog.querySelector('[data-testid="confirm-enabled-count"]').textContent).toContain('1')
    expect(dialog.querySelector('[data-testid="confirm-generated-count"]').textContent).toContain('1')
    expect(dialog.querySelector('[data-testid="overlap-switch"]')).toBeNull()
    document.body.querySelector('[data-testid="confirm-vectorization"]').click()
    await flushPromises()

    expect(confirmVectorization).toHaveBeenCalledTimes(1)
    fileState = 6
    chunks = chunks.map(chunk => ({
      ...chunk,
      status: 2,
      lockVersion: chunk.lockVersion + 1,
    }))
    getProcessing.mockResolvedValueOnce(processing(6, fileLock))
    await vi.advanceTimersByTimeAsync(1000)
    await flushPromises()
    expect(wrapper.text()).toContain('仅来自服务端的补充文本')
    expect(wrapper.find('[data-testid="open-confirm"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="reindex-chunk"]').exists()).toBe(false)
    expect(wrapper.get('.chunk-card').attributes('aria-disabled')).toBe('false')
    wrapper.unmount()
  })
})
