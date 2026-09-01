import { mount, flushPromises } from '@vue/test-utils'
import ElementPlus, { ElMessageBox } from 'element-plus'
import { nextTick, reactive } from 'vue'
import {
  confirmVectorization,
  createPreview,
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

const mountedWrappers = []

function routeFor(fileId = '22') {
  return reactive({ params: { knowledgeId: '11', fileId } })
}

function mountWorkspace(route = routeFor()) {
  const wrapper = mount(ChunkingWorkspace, {
    attachTo: document.body,
    global: {
      plugins: [ElementPlus],
      mocks: { $route: route },
    },
  })
  mountedWrappers.push(wrapper)
  return wrapper
}

function deferred() {
  let resolve
  let reject
  const promise = new Promise((res, rej) => {
    resolve = res
    reject = rej
  })
  return { promise, resolve, reject }
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
    updateChunk.mockResolvedValue({ data: { ...draftChunk, lockVersion: 6 } })
    deleteChunk.mockResolvedValue({ status: 204 })
  })

  afterEach(() => {
    mountedWrappers.splice(0).forEach(wrapper => wrapper.unmount())
    vi.restoreAllMocks()
    vi.useRealTimers()
  })

  it('discards stale processing responses on route reuse, keeps one poll, and routes actions to the new file', async () => {
    const route = routeFor('22')
    const oldProcessing = deferred()
    const newChunk = { ...draftChunk, publicId: 'chunk-new', content: '新文件正文', lockVersion: 8 }
    getProcessing.mockImplementation((knowledgeId, fileId) => {
      if (fileId === '22') return oldProcessing.promise
      return getProcessing.mock.calls.filter(call => call[1] === '33').length === 1
        ? Promise.resolve(processing(1, { progress: 20, lockVersion: 7 }))
        : Promise.resolve(processing(2, { progress: 100, lockVersion: 8 }))
    })
    getChunks.mockResolvedValue({ data: [newChunk] })
    updateChunk.mockResolvedValue({ data: { ...newChunk, content: '新文件已编辑', lockVersion: 9 } })
    const wrapper = mountWorkspace(route)
    await flushPromises()

    route.params.fileId = '33'
    await nextTick()
    await flushPromises()
    oldProcessing.resolve(processing(1, { progress: 99, lockVersion: 99 }))
    await flushPromises()
    await vi.advanceTimersByTimeAsync(1000)
    await flushPromises()

    expect(wrapper.text()).toContain('新文件正文')
    expect(getProcessing.mock.calls.filter(call => call[1] === '22')).toHaveLength(1)
    expect(getProcessing.mock.calls.filter(call => call[1] === '33')).toHaveLength(2)
    await vi.advanceTimersByTimeAsync(3000)
    expect(getProcessing.mock.calls.filter(call => call[1] === '33')).toHaveLength(2)

    await wrapper.get('[data-testid="edit-chunk"]').trigger('click')
    await wrapper.get('textarea').setValue('新文件已编辑')
    await vi.advanceTimersByTimeAsync(650)
    expect(updateChunk).toHaveBeenCalledWith('11', '33', 'chunk-new', {
      content: '新文件已编辑',
      lockVersion: 8,
    })
  })

  it('discards a stale chunk response after the route changes', async () => {
    const route = routeFor('22')
    const oldChunks = deferred()
    getProcessing.mockImplementation((knowledgeId, fileId) => Promise.resolve(processing(2, {
      lockVersion: fileId === '22' ? 3 : 7,
    })))
    getChunks.mockImplementation((knowledgeId, fileId) => (
      fileId === '22'
        ? oldChunks.promise
        : Promise.resolve({ data: [{ ...draftChunk, publicId: 'new-only', content: '仅属于新文件' }] })
    ))
    const wrapper = mountWorkspace(route)
    await flushPromises()

    route.params.fileId = '33'
    await nextTick()
    await flushPromises()
    expect(wrapper.text()).toContain('仅属于新文件')

    oldChunks.resolve({ data: [{ ...draftChunk, publicId: 'old-only', content: '旧文件迟到正文' }] })
    await flushPromises()
    expect(wrapper.text()).toContain('仅属于新文件')
    expect(wrapper.text()).not.toContain('旧文件迟到正文')
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

    expect(wrapper.find('[data-testid="create-preview"]').exists()).toBe(false)
    expect(wrapper.text()).toContain('正在读取文件处理状态')
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

  it('loads an edited DRAFT when CHUNKING failed and confirms replacement before retrying', async () => {
    getProcessing.mockResolvedValue(processing(7, {
      failedFromState: 1,
      lastError: 'Markdown 解析失败',
    }))
    getChunks.mockResolvedValue({ data: [draftChunk] })
    const confirm = vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm')
    const wrapper = mountWorkspace()
    await flushPromises()

    expect(wrapper.text()).toContain('人工修改后的正文')
    expect(wrapper.get('.chunk-card').attributes('aria-disabled')).toBe('true')
    expect(wrapper.get('[data-testid="edit-chunk"]').attributes('disabled')).toBeDefined()
    expect(wrapper.get('[data-testid="delete-chunk"]').attributes('disabled')).toBeDefined()
    expect(wrapper.find('[data-testid="reindex-chunk"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="create-preview"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="open-confirm"]').exists()).toBe(false)
    await wrapper.get('[data-testid="retry-chunking"]').trigger('click')
    await flushPromises()

    expect(confirm).toHaveBeenCalledWith(
      expect.stringContaining('人工修改'),
      expect.any(String),
      expect.objectContaining({ confirmButtonText: expect.stringContaining('重新生成') }),
    )
    expect(createPreview).toHaveBeenCalledWith('11', '22', expect.objectContaining({
      replaceEditedDrafts: true,
      lockVersion: 3,
    }))
  })

  it('restores a valid non-default Markdown policy snapshot into the inputs and retry payload', async () => {
    getProcessing.mockResolvedValue(processing(2, {
      policySnapshot: { minTokens: 64, targetTokens: 256, maxTokens: 480 },
    }))
    getChunks.mockResolvedValue({ data: [{ ...draftChunk, isModified: false }] })
    const wrapper = mountWorkspace()
    await flushPromises()

    expect(wrapper.get('[data-testid="min-tokens"] input').element.value).toBe('64')
    expect(wrapper.get('[data-testid="target-tokens"] input').element.value).toBe('256')
    expect(wrapper.get('[data-testid="max-tokens"] input').element.value).toBe('480')

    await wrapper.get('[data-testid="create-preview"]').trigger('click')
    await flushPromises()
    expect(createPreview).toHaveBeenCalledWith('11', '22', expect.objectContaining({
      strategyConfig: { minTokens: 64, targetTokens: 256, maxTokens: 480 },
    }))
  })

  it('keeps local token edits through chunk-save refresh and hydrates the next route snapshot', async () => {
    const route = routeFor('22')
    const oldSnapshot = { minTokens: 64, targetTokens: 256, maxTokens: 480 }
    const nextSnapshot = { minTokens: 90, targetTokens: 300, maxTokens: 500 }
    getProcessing.mockImplementation((knowledgeId, fileId) => processing(3, {
      lockVersion: fileId === '22' ? 3 : 8,
      policySnapshot: fileId === '22' ? oldSnapshot : nextSnapshot,
    }))
    getChunks.mockImplementation((knowledgeId, fileId) => Promise.resolve({
      data: [{
        ...draftChunk,
        publicId: fileId === '22' ? 'chunk-1' : 'chunk-next',
        content: fileId === '22' ? '人工修改后的正文' : '下一文件正文',
      }],
    }))
    updateChunk.mockResolvedValue({ data: { ...draftChunk, content: '触发刷新后的正文', lockVersion: 6 } })
    vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm')
    const wrapper = mountWorkspace(route)
    await flushPromises()

    const setToken = async (testId, value) => {
      const input = wrapper.get(`[data-testid="${testId}"] input`)
      await input.setValue(String(value))
      await input.trigger('change')
      await nextTick()
    }
    await setToken('min-tokens', 80)
    await setToken('target-tokens', 300)
    await setToken('max-tokens', 500)
    await wrapper.get('[data-testid="edit-chunk"]').trigger('click')
    await wrapper.get('textarea').setValue('触发刷新后的正文')
    await vi.advanceTimersByTimeAsync(650)
    await flushPromises()

    expect(wrapper.get('[data-testid="min-tokens"] input').element.value).toBe('80')
    expect(wrapper.get('[data-testid="target-tokens"] input').element.value).toBe('300')
    expect(wrapper.get('[data-testid="max-tokens"] input').element.value).toBe('500')
    await wrapper.get('[data-testid="create-preview"]').trigger('click')
    await flushPromises()
    expect(createPreview).toHaveBeenLastCalledWith('11', '22', expect.objectContaining({
      strategyConfig: { minTokens: 80, targetTokens: 300, maxTokens: 500 },
    }))

    route.params.fileId = '33'
    await nextTick()
    await flushPromises()
    expect(wrapper.get('[data-testid="min-tokens"] input').element.value).toBe('90')
    expect(wrapper.get('[data-testid="target-tokens"] input').element.value).toBe('300')
    expect(wrapper.get('[data-testid="max-tokens"] input').element.value).toBe('500')
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

  it('force-reloads a conflicted card and resets its editor, version, and conflict state', async () => {
    const freshChunk = { ...draftChunk, content: '服务端最新正文', lockVersion: 9 }
    getProcessing.mockResolvedValue(processing(3, { lockVersion: 7 }))
    getChunks
      .mockResolvedValueOnce({ data: [draftChunk] })
      .mockResolvedValueOnce({ data: [freshChunk] })
    updateChunk.mockRejectedValue({ response: { status: 409, data: { msg: '块版本冲突' } } })
    const wrapper = mountWorkspace()
    await flushPromises()

    await wrapper.get('[data-testid="edit-chunk"]').trigger('click')
    await wrapper.get('textarea').setValue('本地冲突正文')
    await vi.advanceTimersByTimeAsync(650)
    await flushPromises()
    expect(wrapper.text()).toContain('块版本冲突')

    await wrapper.get('[data-testid="reload-chunk"]').trigger('click')
    await flushPromises()
    expect(getChunks).toHaveBeenCalledTimes(2)
    expect(wrapper.get('textarea').element.value).toBe('服务端最新正文')
    expect(wrapper.find('[data-testid="reload-chunk"]').exists()).toBe(false)

    updateChunk.mockResolvedValue({ data: { ...freshChunk, content: '基于新版本编辑', lockVersion: 10 } })
    await wrapper.get('textarea').setValue('基于新版本编辑')
    await vi.advanceTimersByTimeAsync(650)
    expect(updateChunk).toHaveBeenLastCalledWith('11', '22', 'chunk-1', {
      content: '基于新版本编辑',
      lockVersion: 9,
    })
  })

  it('keeps the conflict and local body when the forced chunk reload fails', async () => {
    getProcessing.mockResolvedValue(processing(3))
    getChunks
      .mockResolvedValueOnce({ data: [draftChunk] })
      .mockRejectedValueOnce({ response: { status: 503, data: { msg: '分块刷新失败' } } })
    updateChunk.mockRejectedValue({ response: { status: 409, data: { msg: '块版本冲突' } } })
    const wrapper = mountWorkspace()
    await flushPromises()

    await wrapper.get('[data-testid="edit-chunk"]').trigger('click')
    await wrapper.get('textarea').setValue('仍需保留的本地正文')
    await vi.advanceTimersByTimeAsync(650)
    await flushPromises()
    await wrapper.get('[data-testid="reload-chunk"]').trigger('click')
    await flushPromises()

    expect(wrapper.get('textarea').element.value).toBe('仍需保留的本地正文')
    expect(wrapper.get('[data-testid="reload-chunk"]').exists()).toBe(true)
    expect(wrapper.text()).toContain('分块刷新失败')
  })

  it('force-refreshes all chunks after edit so a dependent chunk receives its new status and version', async () => {
    const nextChunk = { ...draftChunk, publicId: 'chunk-2', position: 1, content: '相邻旧正文', lockVersion: 2 }
    const refreshedNext = { ...nextChunk, content: '相邻块已失效', status: 1, lockVersion: 3 }
    getProcessing.mockResolvedValue(processing(3))
    getChunks
      .mockResolvedValueOnce({ data: [draftChunk, nextChunk] })
      .mockResolvedValueOnce({ data: [{ ...draftChunk, content: '第一块新正文', lockVersion: 6 }, refreshedNext] })
    updateChunk.mockResolvedValue({ data: { ...draftChunk, content: '第一块新正文', lockVersion: 6 } })
    const wrapper = mountWorkspace()
    await flushPromises()

    await wrapper.findAll('[data-testid="edit-chunk"]')[0].trigger('click')
    await wrapper.get('textarea').setValue('第一块新正文')
    await vi.advanceTimersByTimeAsync(650)
    await flushPromises()

    expect(getChunks).toHaveBeenCalledTimes(2)
    expect(wrapper.text()).toContain('相邻块已失效')
    expect(wrapper.findAll('.chunk-card')[1].attributes('aria-disabled')).toBe('true')
  })

  it.each([
    { failedFromState: 1, visible: 'retry-chunking', hidden: 'retry-vectorizing' },
    { failedFromState: 5, visible: 'retry-vectorizing', hidden: 'retry-chunking' },
  ])('shows only the matching FAILED retry for failedFromState=$failedFromState', async ({ failedFromState, visible, hidden }) => {
    getProcessing.mockResolvedValue(processing(7, { failedFromState, lastError: '阶段失败' }))
    const wrapper = mountWorkspace()
    await flushPromises()

    expect(wrapper.find(`[data-testid="${visible}"]`).exists()).toBe(true)
    expect(wrapper.find(`[data-testid="${hidden}"]`).exists()).toBe(false)
    expect(wrapper.find('[data-testid="create-preview"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="open-confirm"]').exists()).toBe(false)
  })

  it.each([1, 4, 5])('does not expose normal file actions in processing state %s', async (state) => {
    getProcessing.mockResolvedValue(processing(state))
    const wrapper = mountWorkspace()
    await flushPromises()

    expect(wrapper.find('[data-testid="create-preview"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="open-confirm"]').exists()).toBe(false)
  })

  it.each([0, 1, 4, 5, 7])('disables every retained chunk action in file state %s', async (state) => {
    getProcessing
      .mockResolvedValueOnce(processing(6))
      .mockResolvedValueOnce(processing(state))
    getChunks.mockResolvedValue({ data: [draftChunk] })
    const confirm = vi.spyOn(ElMessageBox, 'confirm')
    const wrapper = mountWorkspace()
    await flushPromises()

    await wrapper.vm.refreshProcessing(false, wrapper.vm.currentContext())
    await flushPromises()

    expect(wrapper.get('.chunk-card').attributes('aria-disabled')).toBe('true')
    expect(wrapper.get('[data-testid="edit-chunk"]').attributes('disabled')).toBeDefined()
    expect(wrapper.get('[data-testid="delete-chunk"]').attributes('disabled')).toBeDefined()
    expect(wrapper.find('[data-testid="reindex-chunk"]').exists()).toBe(false)
    await wrapper.get('[data-testid="edit-chunk"]').trigger('click')
    await wrapper.get('[data-testid="delete-chunk"]').trigger('click')
    await vi.advanceTimersByTimeAsync(650)
    expect(wrapper.find('textarea').exists()).toBe(false)
    expect(confirm).not.toHaveBeenCalled()
    expect(updateChunk).not.toHaveBeenCalled()
    expect(deleteChunk).not.toHaveBeenCalled()
    expect(reindexChunk).not.toHaveBeenCalled()
  })

  it('COMPLETED hides full preview and confirm actions but keeps DRAFT reindex', async () => {
    getProcessing.mockResolvedValue(processing(6))
    getChunks.mockResolvedValue({ data: [draftChunk] })
    const wrapper = mountWorkspace()
    await flushPromises()

    expect(wrapper.find('[data-testid="create-preview"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="open-confirm"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="reindex-chunk"]').exists()).toBe(true)
  })

  it('ADJUSTING keeps full confirmation but does not expose per-chunk reindex', async () => {
    getProcessing.mockResolvedValue(processing(3))
    getChunks.mockResolvedValue({ data: [draftChunk] })
    const wrapper = mountWorkspace()
    await flushPromises()

    expect(wrapper.find('[data-testid="open-confirm"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="reindex-chunk"]').exists()).toBe(false)
  })

  it('keeps every file action disabled after processing load failure until retry succeeds', async () => {
    getProcessing.mockRejectedValueOnce({ response: { status: 503, data: { msg: '状态服务不可用' } } })
    const wrapper = mountWorkspace()
    await flushPromises()

    expect(wrapper.text()).toContain('状态服务不可用')
    expect(wrapper.find('[data-testid="create-preview"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="open-confirm"]').exists()).toBe(false)

    getProcessing.mockResolvedValueOnce(processing(0, { lockVersion: 12 }))
    await wrapper.get('[data-testid="retry-processing-load"]').trigger('click')
    await flushPromises()
    expect(wrapper.get('[data-testid="create-preview"]').attributes('disabled')).toBeUndefined()
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

  it('keeps the confirm dialog open and renders a 422 server error inside it', async () => {
    getProcessing.mockResolvedValue(processing(3, { lockVersion: 9 }))
    getChunks.mockResolvedValue({ data: [{ ...draftChunk, isModified: false }] })
    confirmVectorization.mockRejectedValue({ response: { status: 422, data: { msg: '补充 Token 不合法' } } })
    const wrapper = mountWorkspace()
    await flushPromises()

    await wrapper.get('[data-testid="open-confirm"]').trigger('click')
    await flushPromises()
    document.body.querySelector('[data-testid="confirm-vectorization"]').click()
    await flushPromises()

    const dialog = document.body.querySelector('[role="dialog"]')
    expect(dialog.textContent).toContain('补充 Token 不合法')
    expect(dialog.querySelector('[role="alert"]')).not.toBeNull()
    expect(document.body.querySelector('[data-testid="confirm-vectorization"]')).not.toBeNull()
  })

  it('reloads processing and chunks from a confirm 409, then submits the refreshed file lock', async () => {
    getProcessing
      .mockResolvedValueOnce(processing(3, { lockVersion: 3 }))
      .mockResolvedValueOnce(processing(3, { lockVersion: 9 }))
    getChunks.mockResolvedValue({ data: [{ ...draftChunk, isModified: false }] })
    confirmVectorization
      .mockRejectedValueOnce({ response: { status: 409, data: { msg: '确认版本冲突' } } })
      .mockResolvedValueOnce({ status: 202 })
    const wrapper = mountWorkspace()
    await flushPromises()

    await wrapper.get('[data-testid="open-confirm"]').trigger('click')
    await flushPromises()
    document.body.querySelector('[data-testid="confirm-vectorization"]').click()
    await flushPromises()
    expect(document.body.querySelector('[role="dialog"]').textContent).toContain('确认版本冲突')

    document.body.querySelector('[data-testid="reload-confirm"]').click()
    await flushPromises()
    expect(getChunks).toHaveBeenCalledTimes(2)
    document.body.querySelector('[data-testid="confirm-vectorization"]').click()
    await flushPromises()
    expect(confirmVectorization).toHaveBeenLastCalledWith('11', '22', {
      overlapEnabled: false,
      overlapTokens: 40,
      lockVersion: 9,
    })
  })

  it('keeps a confirm 409 blocked and retryable until both processing and chunks reload', async () => {
    getProcessing
      .mockResolvedValueOnce(processing(3, { lockVersion: 3 }))
      .mockResolvedValueOnce(processing(3, { lockVersion: 9 }))
      .mockResolvedValueOnce(processing(3, { lockVersion: 10 }))
    getChunks
      .mockResolvedValueOnce({ data: [{ ...draftChunk, isModified: false }] })
      .mockRejectedValueOnce({ response: { status: 503, data: { msg: '分块重载失败' } } })
      .mockResolvedValueOnce({ data: [{ ...draftChunk, isModified: false, lockVersion: 7 }] })
    confirmVectorization
      .mockRejectedValueOnce({ response: { status: 409, data: { msg: '确认版本冲突' } } })
      .mockResolvedValueOnce({ status: 202 })
    const wrapper = mountWorkspace()
    await flushPromises()

    await wrapper.get('[data-testid="open-confirm"]').trigger('click')
    await flushPromises()
    document.body.querySelector('[data-testid="confirm-vectorization"]').click()
    await flushPromises()
    document.body.querySelector('[data-testid="reload-confirm"]').click()
    await flushPromises()

    let dialog = document.body.querySelector('[role="dialog"]')
    expect(dialog.textContent).toContain('分块重载失败')
    expect(dialog.querySelector('[data-testid="reload-confirm"]')).not.toBeNull()
    expect(dialog.querySelector('[data-testid="confirm-vectorization"]').disabled).toBe(true)
    dialog.querySelector('[data-testid="confirm-vectorization"]').click()
    expect(confirmVectorization).toHaveBeenCalledTimes(1)

    dialog.querySelector('[data-testid="reload-confirm"]').click()
    await flushPromises()
    dialog = document.body.querySelector('[role="dialog"]')
    expect(dialog.querySelector('[role="alert"]')).toBeNull()
    expect(dialog.querySelector('[data-testid="confirm-vectorization"]').disabled).toBe(false)
    dialog.querySelector('[data-testid="confirm-vectorization"]').click()
    await flushPromises()
    expect(confirmVectorization).toHaveBeenLastCalledWith('11', '22', {
      overlapEnabled: false,
      overlapTokens: 40,
      lockVersion: 10,
    })
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

  it('blocks duplicate reindex activation and disables that card until refresh completes', async () => {
    const pending = deferred()
    getProcessing.mockResolvedValueOnce(processing(6)).mockResolvedValueOnce(processing(5))
    getChunks
      .mockResolvedValueOnce({ data: [draftChunk] })
      .mockResolvedValueOnce({ data: [{ ...draftChunk, status: 1, lockVersion: 6 }] })
    reindexChunk.mockReturnValue(pending.promise)
    const wrapper = mountWorkspace()
    await flushPromises()

    const action = wrapper.get('[data-testid="reindex-chunk"]')
    await action.trigger('click')
    await action.trigger('click')
    expect(reindexChunk).toHaveBeenCalledTimes(1)
    expect(wrapper.get('.chunk-card').attributes('aria-disabled')).toBe('true')

    pending.resolve({ status: 202 })
    await flushPromises()
    expect(getChunks).toHaveBeenCalledTimes(2)
    expect(wrapper.get('.chunk-card').attributes('aria-disabled')).toBe('true')
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
