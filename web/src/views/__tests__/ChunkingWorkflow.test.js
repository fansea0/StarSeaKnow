import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus, { ElMessageBox } from 'element-plus'
import { reactive } from 'vue'
import {
  confirmVectorization,
  deleteChunk,
  getChunks,
  getProcessing,
  getStrategies,
  reindexChunk,
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
    overlapEnabled: false, overlapLimit: 40,
    overlapContent: null, overlapTokenCount: 0, overlapUnavailableReason: null,
  },
  {
    publicId: 'chunk-2', position: 1, content: '第二块原文。',
    sectionPath: ['科大百事通', '校园生活类问题'],
    sourceLocator: { startLine: 11, endLine: 20 }, tokenCount: 16,
    status: 0, isModified: false, lockVersion: 1,
    overlapEnabled: false, overlapLimit: 40,
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
          available: true,
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
        overlapLimit: request.overlapLimit,
        overlapUnit: 'TOKENS',
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

  it('completes the GENERAL character workflow through edit, confirmation, and reindex', async () => {
    getStrategies.mockResolvedValue({
      data: {
        fileType: 'txt',
        strategies: [{
          code: 'GENERAL', scope: 'GLOBAL', available: true,
          supportedFileTypes: ['*'], plannerVersion: 'general-deterministic-v1',
          configFields: [
            { key: 'delimiter', defaultValue: '\n' },
            { key: 'delimiterMode', defaultValue: 'LITERAL' },
            { key: 'maxCharacters', defaultValue: 96, min: 64, max: 4000 },
            { key: 'collapseWhitespace', defaultValue: false },
            { key: 'removeUrls', defaultValue: true },
            { key: 'removeEmails', defaultValue: false },
          ],
          defaultContextConfig: { enabled: true, limit: 16, unit: 'CHARACTERS', mode: 'CHARACTER_TAIL' },
        }],
      },
    })
    let fileState = 2
    let fileLock = 2
    let chunks = [
      {
        publicId: 'general-1', position: 0, content: '第一块通用正文。', sectionPath: [],
        sourceLocator: { type: 'TEXT', startOffset: 0, endOffset: 8 }, tokenCount: 8,
        status: 0, isModified: false, lockVersion: 0, overlapEnabled: true,
        overlapLimit: 16, overlapUnit: 'CHARACTERS', overlapContent: null,
        overlapTokenCount: 0, overlapCharacterCount: 0, overlapActualLength: 0,
        overlapReductionReason: 'FIRST_CHUNK', overlapUnavailableReason: 'NO_AVAILABLE_OVERLAP',
        lengthUnit: 'CHARACTERS', bodyLength: 8, indexLength: 8,
        boundaryReason: { start: 'DOCUMENT_START', end: 'USER_DELIMITER', forcedSplit: false },
      },
      {
        publicId: 'general-2', position: 1, content: '第二块通用正文。', sectionPath: [],
        sourceLocator: { type: 'TEXT', startOffset: 9, endOffset: 17 }, tokenCount: 8,
        status: 0, isModified: false, lockVersion: 0, overlapEnabled: true,
        overlapLimit: 16, overlapUnit: 'CHARACTERS', overlapContent: '第一块通用正文。',
        overlapTokenCount: 8, overlapCharacterCount: 8, overlapActualLength: 8,
        overlapReductionReason: 'CONFIGURED_LIMIT', overlapUnavailableReason: null,
        lengthUnit: 'CHARACTERS', bodyLength: 8, indexLength: 21,
        boundaryReason: { start: 'USER_DELIMITER', end: 'DOCUMENT_END', forcedSplit: false },
      },
    ]
    const generalProcessing = () => ({
      data: {
        state: fileState, failedFromState: null, progress: fileState === 6 ? 100 : 0,
        lastError: null, lockVersion: fileLock, strategyCode: 'GENERAL',
        policySnapshot: { delimiter: '\n', delimiterMode: 'LITERAL', maxCharacters: 96, collapseWhitespace: false, removeUrls: true, removeEmails: false },
        contextPolicy: { enabled: true, limit: 16, unit: 'CHARACTERS', mode: 'CHARACTER_TAIL' },
        preprocessingSummary: { whitespaceMatches: 2, whitespaceCharactersRemoved: 3, urlMatches: 1, urlCharactersReplaced: 18, emailMatches: 0, emailCharactersReplaced: 0, controlCharactersRemoved: 1, emptySegmentsRemoved: 0 },
        delimiterMatched: true, forcedSplitCount: 0, tokenLimitedSplitCount: 0,
      },
    })
    getProcessing.mockImplementation(() => Promise.resolve(generalProcessing()))
    getChunks.mockImplementation(() => Promise.resolve({ data: chunks.map(chunk => ({ ...chunk })) }))
    updateChunk.mockImplementation((knowledgeId, fileId, chunkId, request) => {
      const index = chunks.findIndex(chunk => chunk.publicId === chunkId)
      chunks[index] = {
        ...chunks[index], content: request.content, status: 0, isModified: true,
        lockVersion: chunks[index].lockVersion + 1,
      }
      if (index + 1 < chunks.length) {
        chunks[index + 1] = {
          ...chunks[index + 1], status: 0, isModified: true,
          lockVersion: chunks[index + 1].lockVersion + 1,
          overlapContent: request.content.slice(-16), overlapCharacterCount: 16,
          overlapActualLength: 16, overlapReductionReason: 'CHARACTER_LIMIT',
        }
      }
      fileState = 3
      fileLock += 1
      return Promise.resolve({ data: { ...chunks[index] } })
    })
    confirmVectorization.mockImplementation(() => {
      fileState = 5
      fileLock += 1
      return Promise.resolve({ status: 202 })
    })
    reindexChunk.mockImplementation((knowledgeId, fileId, chunkId) => {
      chunks = chunks.map(chunk => chunk.publicId === chunkId
        ? { ...chunk, status: 2, isModified: true, lockVersion: chunk.lockVersion + 2 }
        : chunk)
      fileState = 6
      fileLock += 2
      return Promise.resolve({ status: 202 })
    })

    const wrapper = mountWorkspace()
    await flushPromises()
    expect(wrapper.text()).toContain('正文 8 字符')
    expect(wrapper.text()).toContain('分隔符已匹配')
    expect(wrapper.text()).toContain('URL1 处 / 替换 18 字符')
    expect(wrapper.text()).toContain('已按配置上限缩减')

    await wrapper.findAll('[data-testid="edit-chunk"]')[0].trigger('click')
    await wrapper.findAll('textarea')[0].setValue('第一块正文已经人工更新，直接影响后继。')
    await vi.advanceTimersByTimeAsync(650)
    await flushPromises()
    expect(updateChunk).toHaveBeenCalledWith('10', '20', 'general-1', expect.objectContaining({
      overlapUnit: 'CHARACTERS', overlapLimit: 16,
    }))
    expect(wrapper.text()).toContain('已按字符上限缩减')
    expect(wrapper.get('[data-testid="overlap-content"]').text()).toContain('接影响后继')

    await wrapper.get('[data-testid="open-confirm"]').trigger('click')
    await flushPromises()
    const dialog = document.body.querySelector('[role="dialog"]')
    expect(dialog.querySelector('[data-testid="confirm-enabled-count"]').textContent).toContain('2')
    expect(dialog.textContent).toContain('字符 2 块')
    dialog.querySelector('[data-testid="confirm-vectorization"]').click()
    await flushPromises()
    expect(confirmVectorization).toHaveBeenCalledWith('10', '20', { lockVersion: 3 })

    fileState = 6
    chunks = chunks.map(chunk => ({ ...chunk, status: 2 }))
    await vi.advanceTimersByTimeAsync(1000)
    await flushPromises()
    expect(wrapper.get('[data-testid="vectorization-complete"]').text()).toContain('2 个分块')

    await wrapper.findAll('[data-testid="edit-chunk"]')[1].trigger('click')
    await wrapper.findAll('textarea')[0].setValue('第二块完成后再次编辑。')
    await vi.advanceTimersByTimeAsync(650)
    await flushPromises()
    const reindex = wrapper.get('[data-testid="reindex-chunk"]')
    await reindex.trigger('click')
    await flushPromises()
    expect(reindexChunk).toHaveBeenCalledWith('10', '20', 'general-2')
    expect(wrapper.get('[data-testid="vectorization-complete"]').text()).toContain('索引建立完成')
    wrapper.unmount()
  })
})
