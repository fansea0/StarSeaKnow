import { mount, flushPromises } from '@vue/test-utils'
import ElementPlus, { ElMessageBox } from 'element-plus'
import {
  confirmVectorization,
  createPreview,
  getChunks,
  getProcessing,
  getStrategies,
  reindexChunk,
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

const strategyResponse = {
  data: {
    fileType: 'md',
    strategies: [{ code: 'MARKDOWN_OPTIMIZED', supportedFileTypes: ['md', 'markdown'], plannerVersion: 'v1' }],
  },
}

const processing = (state, overrides = {}) => ({
  data: {
    state,
    failedFromState: null,
    progress: state === 6 ? 100 : 0,
    lastError: null,
    lockVersion: 3,
    strategyCode: 'MARKDOWN_OPTIMIZED',
    policySnapshot: { minTokens: 100, targetTokens: 400, maxTokens: 512 },
    contextPolicy: { overlapEnabled: false, overlapTokens: 40 },
    ...overrides,
  },
})

const draftChunk = {
  publicId: 'chunk-1',
  position: 0,
  content: '人工修改后的正文',
  sectionPath: ['用户手册', '部署'],
  sourceLocator: { startLine: 3, endLine: 9 },
  tokenCount: 22,
  status: 0,
  isModified: true,
  lockVersion: 5,
}

function mountWorkspace() {
  return mount(ChunkingWorkspace, {
    attachTo: document.body,
    global: {
      plugins: [ElementPlus],
      mocks: {
        $route: { params: { knowledgeId: '11', fileId: '22' } },
      },
    },
  })
}

function deferred() {
  let resolve
  const promise = new Promise(res => { resolve = res })
  return { promise, resolve }
}

describe('ChunkingWorkspace', () => {
  beforeEach(() => {
    vi.useFakeTimers()
    vi.clearAllMocks()
    getStrategies.mockResolvedValue(strategyResponse)
    getProcessing.mockResolvedValue(processing(0))
    getChunks.mockResolvedValue({ data: [] })
    createPreview.mockResolvedValue({ status: 202 })
    confirmVectorization.mockResolvedValue({ status: 202 })
    reindexChunk.mockResolvedValue({ status: 202 })
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  it('loads backend capabilities and selects MD optimized while placeholders stay disabled', async () => {
    const wrapper = mountWorkspace()
    await flushPromises()

    expect(getStrategies).toHaveBeenCalledWith('11', '22')
    expect(getProcessing).toHaveBeenCalledWith('11', '22')
    expect(wrapper.get('[data-strategy="GENERAL"]').attributes('aria-disabled')).toBe('true')
    expect(wrapper.get('[data-strategy="PARENT_CHILD"]').attributes('aria-disabled')).toBe('true')
    expect(wrapper.get('[data-strategy="MARKDOWN_OPTIMIZED"]').classes()).toContain('is-selected')
  })

  it('keeps file submission disabled until the initial processing lockVersion is loaded', async () => {
    const initialProcessing = deferred()
    getProcessing.mockReturnValue(initialProcessing.promise)
    const wrapper = mountWorkspace()
    await flushPromises()

    expect(wrapper.get('[data-testid="create-preview"]').attributes('disabled')).toBeDefined()
    initialProcessing.resolve(processing(0, { lockVersion: 7 }))
    await flushPromises()
    expect(wrapper.get('[data-testid="create-preview"]').attributes('disabled')).toBeUndefined()
    wrapper.unmount()
  })

  it('disables preview submission while the Markdown token budget is invalid', async () => {
    const wrapper = mountWorkspace()
    await flushPromises()

    const maxInput = wrapper.get('[data-testid="max-tokens"] input')
    await maxInput.setValue('513')
    await maxInput.trigger('change')
    await flushPromises()

    expect(wrapper.get('[data-testid="create-preview"]').attributes('disabled')).toBeDefined()
    await wrapper.get('[data-testid="create-preview"]').trigger('click')
    expect(createPreview).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('polls CHUNKING exactly every 1000ms, stops at CHUNKED, and loads chunks once', async () => {
    getProcessing
      .mockResolvedValueOnce(processing(1, { progress: 20 }))
      .mockResolvedValueOnce(processing(1, { progress: 70 }))
      .mockResolvedValueOnce(processing(2, { progress: 100 }))
    const wrapper = mountWorkspace()
    await flushPromises()

    expect(getProcessing).toHaveBeenCalledTimes(1)
    await vi.advanceTimersByTimeAsync(999)
    expect(getProcessing).toHaveBeenCalledTimes(1)
    await vi.advanceTimersByTimeAsync(1)
    expect(getProcessing).toHaveBeenCalledTimes(2)
    await vi.advanceTimersByTimeAsync(1000)
    expect(getProcessing).toHaveBeenCalledTimes(3)
    expect(getChunks).toHaveBeenCalledTimes(1)
    await vi.advanceTimersByTimeAsync(3000)
    expect(getProcessing).toHaveBeenCalledTimes(3)
    wrapper.unmount()
  })

  it('polls VECTORIZING and clears the pending timer when the route component unmounts', async () => {
    getProcessing.mockResolvedValue(processing(5, { progress: 45 }))
    const wrapper = mountWorkspace()
    await flushPromises()
    wrapper.unmount()

    await vi.advanceTimersByTimeAsync(5000)
    expect(getProcessing).toHaveBeenCalledTimes(1)
  })

  it('restores saved DRAFT chunks each time a terminal workspace is revisited', async () => {
    getProcessing.mockResolvedValue(processing(6))
    getChunks.mockResolvedValue({ data: [draftChunk] })
    const first = mountWorkspace()
    await flushPromises()
    expect(first.text()).toContain('人工修改后的正文')
    first.unmount()

    const second = mountWorkspace()
    await flushPromises()
    expect(second.text()).toContain('人工修改后的正文')
    expect(getChunks).toHaveBeenCalledTimes(2)
    second.unmount()
  })

  it('warns before regeneration replaces edited drafts and resends with replaceEditedDrafts=true', async () => {
    getProcessing.mockResolvedValue(processing(3))
    getChunks.mockResolvedValue({ data: [draftChunk] })
    const confirm = vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm')
    const wrapper = mountWorkspace()
    await flushPromises()

    await wrapper.get('[data-testid="create-preview"]').trigger('click')
    await flushPromises()

    expect(confirm).toHaveBeenCalledWith(
      expect.stringContaining('人工修改'),
      expect.any(String),
      expect.objectContaining({ confirmButtonText: expect.stringContaining('重新生成') }),
    )
    expect(createPreview).toHaveBeenCalledWith('11', '22', {
      strategyCode: 'MARKDOWN_OPTIMIZED',
      strategyConfig: { minTokens: 100, targetTokens: 400, maxTokens: 512 },
      replaceEditedDrafts: true,
      lockVersion: 3,
    })
    wrapper.unmount()
  })

  it('offers an explicit reload on a preview 409 and preserves config on a 422', async () => {
    createPreview.mockRejectedValueOnce({ response: { status: 409, data: { msg: '文件版本已经变化' } } })
    const wrapper = mountWorkspace()
    await flushPromises()

    await wrapper.get('[data-testid="create-preview"]').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('文件版本已经变化')
    const reload = wrapper.findAll('button').find(button => button.text() === '重新加载')
    expect(reload).toBeDefined()
    await reload.trigger('click')
    await flushPromises()
    expect(getStrategies).toHaveBeenCalledTimes(2)

    createPreview.mockRejectedValueOnce({ response: { status: 422, data: { msg: 'Token 范围无效' } } })
    await wrapper.get('[data-testid="create-preview"]').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('Token 范围无效')
    expect(wrapper.get('[data-testid="target-tokens"] input').element.value).toBe('400')
    wrapper.unmount()
  })

  it('owns overlap in the final dialog: disabled by default, enabling reveals 40 Token, and current file lock is submitted', async () => {
    getProcessing.mockResolvedValue(processing(3, { lockVersion: 9 }))
    getChunks.mockResolvedValue({ data: [{ ...draftChunk, isModified: false }] })
    const wrapper = mountWorkspace()
    await flushPromises()

    await wrapper.get('[data-testid="open-confirm"]').trigger('click')
    await flushPromises()
    expect(document.body.textContent).toContain('上下文补充')
    expect(document.body.querySelector('[data-testid="overlap-tokens"]')).toBeNull()

    const overlapSwitch = document.body.querySelector('[data-testid="overlap-switch"]')
    overlapSwitch.click()
    await flushPromises()
    const tokenInput = document.body.querySelector('[data-testid="overlap-tokens"] input')
    expect(tokenInput.value).toBe('40')

    document.body.querySelector('[data-testid="confirm-vectorization"]').click()
    await flushPromises()
    expect(confirmVectorization).toHaveBeenCalledWith('11', '22', {
      overlapEnabled: true,
      overlapTokens: 40,
      lockVersion: 9,
    })
    wrapper.unmount()
  })

  it('shows a per-chunk reindex action for a completed file with an edited DRAFT', async () => {
    getProcessing.mockResolvedValueOnce(processing(6)).mockResolvedValueOnce(processing(5))
    getChunks.mockResolvedValue({ data: [draftChunk] })
    const wrapper = mountWorkspace()
    await flushPromises()

    await wrapper.get('[data-testid="reindex-chunk"]').trigger('click')
    await flushPromises()

    expect(reindexChunk).toHaveBeenCalledWith('11', '22', 'chunk-1')
    expect(getProcessing).toHaveBeenCalledTimes(2)
    wrapper.unmount()
  })

  it('shows the failure reason and only the retry matching failedFromState', async () => {
    getProcessing.mockResolvedValue(processing(7, {
      failedFromState: 1,
      lastError: 'Markdown 解析失败',
    }))
    const wrapper = mountWorkspace()
    await flushPromises()

    expect(wrapper.get('[role="alert"]').text()).toContain('Markdown 解析失败')
    expect(wrapper.find('[data-testid="retry-chunking"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="retry-vectorizing"]').exists()).toBe(false)
    wrapper.unmount()
  })
})
