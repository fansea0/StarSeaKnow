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
  overlapEnabled: false,
  overlapTokenLimit: 40,
  overlapContent: null,
  overlapTokenCount: 0,
  overlapUnavailableReason: null,
}

const mountedWrappers = []

function routeFor(fileId = '22') {
  return reactive({ params: { knowledgeId: '11', fileId } })
}

function mountWorkspace(route = routeFor(), router = { push: vi.fn() }) {
  const wrapper = mount(ChunkingWorkspace, {
    attachTo: document.body,
    global: {
      plugins: [ElementPlus],
      mocks: { $route: route, $router: router },
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
      overlapEnabled: false,
      overlapTokenLimit: 40,
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

  it('uses backend PARENT_CHILD capability while retaining only the general disabled placeholder', async () => {
    getStrategies.mockResolvedValue({
      data: {
        fileType: 'md',
        strategies: [
          { code: 'MARKDOWN_OPTIMIZED', supportedFileTypes: ['md', 'markdown'] },
          { code: 'PARENT_CHILD', supportedFileTypes: ['md', 'markdown'] },
        ],
      },
    })
    getProcessing.mockResolvedValue(processing(0, { strategyCode: 'PARENT_CHILD' }))
    const wrapper = mountWorkspace()
    await flushPromises()

    expect(getStrategies).toHaveBeenCalledWith('11', '22')
    expect(getProcessing).toHaveBeenCalledWith('11', '22')
    expect(wrapper.get('[data-strategy="GENERAL"]').attributes('aria-disabled')).toBe('true')
    expect(wrapper.get('[data-strategy="PARENT_CHILD"]').attributes('aria-disabled')).toBe('false')
    expect(wrapper.get('[data-strategy="PARENT_CHILD"]').classes()).toContain('is-selected')
  })

  it('groups a flat parent-child response, confirms only retrieval children, and refreshes after deleting the last child', async () => {
    const parent = {
      ...draftChunk, publicId: 'parent-1', position: 0, siblingPosition: 0, chunkType: 'PARENT',
      content: '只读父块上下文', tokenCount: 80, overlapEnabled: false,
    }
    const child = {
      ...draftChunk, publicId: 'child-1', position: 1, siblingPosition: 0, chunkType: 'CHILD',
      parentPublicId: 'parent-1', content: '可编辑检索子块', overlapEnabled: true, overlapTokenLimit: 32,
    }
    getProcessing.mockResolvedValue(processing(3, { strategyCode: 'PARENT_CHILD' }))
    getChunks
      .mockResolvedValueOnce({ data: [child, parent] })
      .mockResolvedValueOnce({ data: [] })
    vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm')
    const wrapper = mountWorkspace()
    await flushPromises()

    expect(wrapper.get('[data-testid="parent-chunk-parent-1"]').text()).toContain('父块 01')
    expect(wrapper.findAll('[data-testid="child-chunk"]')).toHaveLength(1)
    expect(wrapper.find('[data-testid="overlap-switch"]').exists()).toBe(false)
    await wrapper.get('[data-testid="open-confirm"]').trigger('click')
    await flushPromises()
    const dialog = document.body.querySelector('[role="dialog"]')
    expect(dialog.querySelector('[data-testid="confirm-total-count"]').textContent).toContain('1')
    expect(dialog.textContent).toContain('1 父块 · 1 子块')

    await wrapper.get('[data-testid="delete-chunk"]').trigger('click')
    await flushPromises()
    expect(getChunks).toHaveBeenCalledTimes(2)
    expect(wrapper.find('[data-testid="parent-chunk-parent-1"]').exists()).toBe(false)
  })

  it('isolates strategy configs, restores the processing strategy snapshot, and submits the selected parent-child config', async () => {
    getStrategies.mockResolvedValue({
      data: {
        fileType: 'md',
        strategies: [
          { code: 'MARKDOWN_OPTIMIZED', supportedFileTypes: ['md', 'markdown'] },
          { code: 'PARENT_CHILD', supportedFileTypes: ['md', 'markdown'] },
        ],
      },
    })
    getProcessing.mockResolvedValue(processing(0, {
      strategyCode: 'PARENT_CHILD',
      policySnapshot: {
        parentMode: 'PARAGRAPH',
        parentMaxTokens: 2048,
        childMaxTokens: 384,
        childOverlapTokens: 64,
      },
    }))
    const wrapper = mountWorkspace()
    await flushPromises()

    expect(wrapper.get('[data-testid="parent-max-tokens"] input').element.value).toBe('2048')
    await wrapper.get('[data-strategy="MARKDOWN_OPTIMIZED"]').trigger('click')
    await nextTick()
    expect(wrapper.get('[data-testid="min-tokens"] input').element.value).toBe('100')

    await wrapper.get('[data-strategy="PARENT_CHILD"]').trigger('click')
    await nextTick()
    expect(wrapper.get('[data-testid="parent-max-tokens"] input').element.value).toBe('2048')
    await wrapper.get('[data-testid="create-preview"]').trigger('click')
    await flushPromises()

    expect(createPreview).toHaveBeenCalledWith('11', '22', expect.objectContaining({
      strategyCode: 'PARENT_CHILD',
      strategyConfig: {
        parentMode: 'PARAGRAPH',
        parentMaxTokens: 2048,
        childMaxTokens: 384,
        childOverlapTokens: 64,
      },
    }))
  })

  it('keeps file submission disabled until the initial processing lockVersion is loaded', async () => {
    const initialProcessing = deferred()
    getProcessing.mockReturnValue(initialProcessing.promise)
    const wrapper = mountWorkspace()
    await flushPromises()

    expect(wrapper.find('[data-testid="create-preview"]').exists()).toBe(false)
    expect(wrapper.text()).toContain('正在读取文件处理状态')
    expect(wrapper.get('[data-testid="min-tokens"] input').attributes('disabled')).toBeDefined()
    initialProcessing.resolve(processing(0, {
      lockVersion: 7,
      policySnapshot: { minTokens: 64, targetTokens: 256, maxTokens: 480 },
    }))
    await flushPromises()
    expect(wrapper.get('[data-testid="min-tokens"] input').attributes('disabled')).toBeUndefined()
    expect(wrapper.get('[data-testid="min-tokens"] input').element.value).toBe('64')
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

  it('keeps FAILED CHUNKING retry disabled after retained chunks fail, then unlocks after reload', async () => {
    getProcessing.mockResolvedValue(processing(7, {
      failedFromState: 1,
      lastError: 'Markdown 解析失败',
    }))
    getChunks
      .mockRejectedValueOnce({ response: { status: 503, data: { msg: '保留草稿读取失败' } } })
      .mockResolvedValueOnce({ data: [draftChunk] })
    const confirm = vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm')
    const wrapper = mountWorkspace()
    await flushPromises()

    const retry = wrapper.get('[data-testid="retry-chunking"]')
    expect(retry.attributes('disabled')).toBeDefined()
    await retry.trigger('click')
    expect(createPreview).not.toHaveBeenCalled()

    await wrapper.get('[data-testid="retry-processing-load"]').trigger('click')
    await flushPromises()
    expect(wrapper.get('[data-testid="retry-chunking"]').attributes('disabled')).toBeUndefined()
    await wrapper.get('[data-testid="retry-chunking"]').trigger('click')
    await flushPromises()
    expect(confirm).toHaveBeenCalledTimes(1)
    expect(createPreview).toHaveBeenCalledWith('11', '22', expect.objectContaining({
      replaceEditedDrafts: true,
    }))
  })

  it('does not let an old retained-chunks response unlock the new route retry', async () => {
    const route = routeFor('22')
    const oldChunks = deferred()
    const newChunks = deferred()
    getProcessing.mockResolvedValue(processing(7, {
      failedFromState: 1,
      lastError: 'Markdown 解析失败',
    }))
    getChunks.mockImplementation((knowledgeId, fileId) => (
      fileId === '22' ? oldChunks.promise : newChunks.promise
    ))
    const wrapper = mountWorkspace(route)
    await flushPromises()

    expect(wrapper.find('[data-testid="retry-chunking"]').exists()).toBe(true)
    expect(wrapper.get('[data-testid="retry-chunking"]').attributes('disabled')).toBeDefined()
    route.params.fileId = '33'
    await nextTick()
    await flushPromises()

    oldChunks.resolve({ data: [draftChunk] })
    await flushPromises()
    expect(wrapper.get('[data-testid="retry-chunking"]').attributes('disabled')).toBeDefined()

    newChunks.resolve({ data: [{ ...draftChunk, publicId: 'chunk-new' }] })
    await flushPromises()
    expect(wrapper.get('[data-testid="retry-chunking"]').attributes('disabled')).toBeUndefined()
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
      overlapEnabled: false,
      overlapTokenLimit: 40,
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

  it('waits for the latest queued save before refreshing processing and dependent chunks', async () => {
    const firstSave = deferred()
    const secondSave = deferred()
    getProcessing.mockResolvedValue(processing(3))
    getChunks.mockResolvedValue({ data: [draftChunk] })
    updateChunk
      .mockReturnValueOnce(firstSave.promise)
      .mockReturnValueOnce(secondSave.promise)
    const wrapper = mountWorkspace()
    await flushPromises()

    await wrapper.get('[data-testid="edit-chunk"]').trigger('click')
    await wrapper.get('textarea').setValue('第一次正文')
    await vi.advanceTimersByTimeAsync(650)
    await wrapper.get('textarea').setValue('第二次正文')
    await vi.advanceTimersByTimeAsync(650)
    expect(updateChunk).toHaveBeenCalledTimes(1)

    firstSave.resolve({ data: { ...draftChunk, content: '第一次正文', lockVersion: 6 } })
    await flushPromises()

    expect(updateChunk).toHaveBeenCalledTimes(2)
    expect(getProcessing).toHaveBeenCalledTimes(1)
    expect(getChunks).toHaveBeenCalledTimes(1)

    secondSave.resolve({ data: { ...draftChunk, content: '第二次正文', lockVersion: 7 } })
    await flushPromises()

    expect(getProcessing).toHaveBeenCalledTimes(2)
    expect(getChunks).toHaveBeenCalledTimes(2)
  })

  it('refreshes once only after two cards have both finished saving', async () => {
    const firstSave = deferred()
    const secondSave = deferred()
    const secondChunk = {
      ...draftChunk,
      publicId: 'chunk-2',
      position: 1,
      content: '第二块正文',
      lockVersion: 8,
    }
    getProcessing.mockResolvedValue(processing(3))
    getChunks.mockResolvedValue({ data: [draftChunk, secondChunk] })
    updateChunk.mockImplementation((knowledgeId, fileId, publicId) => (
      publicId === 'chunk-1' ? firstSave.promise : secondSave.promise
    ))
    const wrapper = mountWorkspace()
    await flushPromises()

    const editButtons = wrapper.findAll('[data-testid="edit-chunk"]')
    await editButtons[0].trigger('click')
    await editButtons[1].trigger('click')
    const editors = wrapper.findAll('textarea')
    await editors[0].setValue('第一块新正文')
    await editors[1].setValue('第二块新正文')
    await vi.advanceTimersByTimeAsync(650)
    expect(updateChunk).toHaveBeenCalledTimes(2)

    firstSave.resolve({ data: { ...draftChunk, content: '第一块新正文', lockVersion: 6 } })
    await flushPromises()
    expect(getProcessing).toHaveBeenCalledTimes(1)
    expect(getChunks).toHaveBeenCalledTimes(1)

    secondSave.resolve({ data: { ...secondChunk, content: '第二块新正文', lockVersion: 9 } })
    await flushPromises()
    expect(getProcessing).toHaveBeenCalledTimes(2)
    expect(getChunks).toHaveBeenCalledTimes(2)
  })

  it('blocks regeneration confirmation and every single-chunk rebuild immediately after an edit', async () => {
    getProcessing.mockResolvedValue(processing(3))
    getChunks.mockResolvedValue({ data: [draftChunk] })
    const wrapper = mountWorkspace()
    await flushPromises()

    await wrapper.get('[data-testid="overlap-switch"] .el-switch').trigger('click')
    await nextTick()

    expect(wrapper.get('[data-testid="create-preview"]').attributes('disabled')).toBeDefined()
    expect(wrapper.get('[data-testid="open-confirm"]').attributes('disabled')).toBeDefined()
    expect(wrapper.get('[data-testid="reindex-chunk"]').attributes('disabled')).toBeDefined()
    await wrapper.get('[data-testid="create-preview"]').trigger('click')
    await wrapper.get('[data-testid="open-confirm"]').trigger('click')
    await wrapper.get('[data-testid="reindex-chunk"]').trigger('click')
    expect(createPreview).not.toHaveBeenCalled()
    expect(confirmVectorization).not.toHaveBeenCalled()
    expect(reindexChunk).not.toHaveBeenCalled()
  })

  it('disables every chunk while preview regeneration is pending and leaves no save blocker after refresh', async () => {
    const previewRequest = deferred()
    const secondChunk = {
      ...draftChunk,
      publicId: 'chunk-2',
      position: 1,
      content: '第二块正文',
      isModified: false,
      lockVersion: 8,
    }
    getProcessing.mockResolvedValue(processing(3))
    getChunks.mockResolvedValue({ data: [
      { ...draftChunk, isModified: false },
      secondChunk,
    ] })
    createPreview.mockReturnValue(previewRequest.promise)
    const wrapper = mountWorkspace()
    await flushPromises()

    await wrapper.get('[data-testid="create-preview"]').trigger('click')
    await nextTick()

    expect(createPreview).toHaveBeenCalledTimes(1)
    expect(wrapper.findAll('.chunk-card').every(card => card.attributes('aria-disabled') === 'true')).toBe(true)
    expect(wrapper.get('[data-testid="open-confirm"]').attributes('disabled')).toBeDefined()
    await wrapper.findAll('[data-testid="edit-chunk"]')[1].trigger('click')
    await vi.advanceTimersByTimeAsync(650)
    expect(wrapper.find('textarea').exists()).toBe(false)
    expect(updateChunk).not.toHaveBeenCalled()
    expect(wrapper.vm.hasBlockingChunkSaves).toBe(false)

    previewRequest.resolve({ status: 202 })
    await flushPromises()

    expect(getProcessing).toHaveBeenCalledTimes(2)
    expect(getChunks).toHaveBeenCalledTimes(2)
    expect(wrapper.vm.fileMutationInProgress).toBe(false)
    expect(wrapper.vm.hasBlockingChunkSaves).toBe(false)
    expect(wrapper.findAll('.chunk-card').every(card => card.attributes('aria-disabled') === 'false')).toBe(true)
  })

  it('owns the file mutation barrier while edited-draft regeneration confirmation is pending', async () => {
    const confirmation = deferred()
    const secondChunk = {
      ...draftChunk,
      publicId: 'chunk-2',
      position: 1,
      content: '第二块人工修改正文',
      lockVersion: 8,
    }
    getProcessing.mockResolvedValue(processing(3))
    getChunks.mockResolvedValue({ data: [draftChunk, secondChunk] })
    const confirm = vi.spyOn(ElMessageBox, 'confirm').mockReturnValue(confirmation.promise)
    const wrapper = mountWorkspace()
    await flushPromises()

    await wrapper.get('[data-testid="create-preview"]').trigger('click')
    await nextTick()

    expect(confirm).toHaveBeenCalledTimes(1)
    expect(createPreview).not.toHaveBeenCalled()
    expect(wrapper.findAll('.chunk-card').every(card => card.attributes('aria-disabled') === 'true')).toBe(true)
    expect(wrapper.get('[data-testid="create-preview"]').attributes('disabled')).toBeDefined()
    expect(wrapper.get('[data-testid="open-confirm"]').attributes('disabled')).toBeDefined()

    const duplicatePreview = wrapper.vm.submitPreview()
    const parallelReindex = wrapper.vm.handleReindex(secondChunk)
    const parallelConfirm = wrapper.vm.submitVectorization()
    wrapper.vm.openConfirmDialog()
    await nextTick()

    expect(confirm).toHaveBeenCalledTimes(1)
    expect(createPreview).not.toHaveBeenCalled()
    expect(reindexChunk).not.toHaveBeenCalled()
    expect(confirmVectorization).not.toHaveBeenCalled()
    expect(wrapper.vm.confirmDialogVisible).toBe(false)

    confirmation.resolve('confirm')
    await Promise.all([duplicatePreview, parallelReindex, parallelConfirm])
    await flushPromises()

    expect(createPreview).toHaveBeenCalledTimes(1)
    expect(wrapper.vm.fileMutationInProgress).toBe(false)
    expect(wrapper.vm.hasBlockingChunkSaves).toBe(false)
    expect(wrapper.findAll('.chunk-card').every(card => card.attributes('aria-disabled') === 'false')).toBe(true)
  })

  it('releases the preview mutation barrier when edited-draft regeneration is cancelled', async () => {
    const confirmation = deferred()
    getProcessing.mockResolvedValue(processing(3))
    getChunks.mockResolvedValue({ data: [draftChunk] })
    vi.spyOn(ElMessageBox, 'confirm').mockReturnValue(confirmation.promise)
    const wrapper = mountWorkspace()
    await flushPromises()

    await wrapper.get('[data-testid="create-preview"]').trigger('click')
    await nextTick()

    expect(wrapper.get('.chunk-card').attributes('aria-disabled')).toBe('true')
    expect(wrapper.vm.fileMutationInProgress).toBe(true)

    confirmation.reject(new Error('cancelled'))
    await flushPromises()

    expect(createPreview).not.toHaveBeenCalled()
    expect(wrapper.vm.fileMutationInProgress).toBe(false)
    expect(wrapper.vm.hasBlockingChunkSaves).toBe(false)
    expect(wrapper.get('.chunk-card').attributes('aria-disabled')).toBe('false')
    expect(wrapper.get('[data-testid="create-preview"]').attributes('disabled')).toBeUndefined()
  })

  it('blocks failed-vectorization retry while a recovered draft has an unsaved change', async () => {
    getProcessing.mockResolvedValue(processing(7, {
      failedFromState: 5,
      lastError: '向量服务不可用',
    }))
    getChunks.mockResolvedValue({ data: [draftChunk] })
    const wrapper = mountWorkspace()
    await flushPromises()

    await wrapper.get('[data-testid="overlap-switch"] .el-switch').trigger('click')
    await nextTick()

    const retry = wrapper.get('[data-testid="retry-vectorizing"]')
    expect(retry.attributes('disabled')).toBeDefined()
    await retry.trigger('click')
    expect(confirmVectorization).not.toHaveBeenCalled()
  })

  it('keeps file actions and rebuild blocked after a chunk save error', async () => {
    getProcessing.mockResolvedValue(processing(3))
    getChunks.mockResolvedValue({ data: [draftChunk] })
    updateChunk.mockRejectedValue({ response: { status: 503, data: { msg: '保存服务不可用' } } })
    const wrapper = mountWorkspace()
    await flushPromises()

    await wrapper.get('[data-testid="edit-chunk"]').trigger('click')
    await wrapper.get('textarea').setValue('尚未保存的正文')
    await nextTick()
    expect(wrapper.get('[data-testid="open-confirm"]').attributes('disabled')).toBeDefined()

    await vi.advanceTimersByTimeAsync(650)
    await flushPromises()

    expect(wrapper.get('[role="alert"]').text()).toContain('保存服务不可用')
    expect(wrapper.get('[data-testid="create-preview"]').attributes('disabled')).toBeDefined()
    expect(wrapper.get('[data-testid="open-confirm"]').attributes('disabled')).toBeDefined()
    expect(wrapper.get('[data-testid="reindex-chunk"]').attributes('disabled')).toBeDefined()
    expect(getProcessing).toHaveBeenCalledTimes(1)
    expect(getChunks).toHaveBeenCalledTimes(1)
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

  it('retries failed vectorization with only the current file lock', async () => {
    getProcessing.mockResolvedValue(processing(7, {
      failedFromState: 5,
      lockVersion: 12,
      contextPolicy: { overlapEnabled: true, overlapTokens: 128 },
    }))
    getChunks.mockResolvedValue({ data: [draftChunk] })
    const wrapper = mountWorkspace()
    await flushPromises()

    await wrapper.get('[data-testid="retry-vectorizing"]').trigger('click')
    await flushPromises()

    expect(confirmVectorization).toHaveBeenCalledWith('11', '22', { lockVersion: 12 })
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

  it('COMPLETED shows an indexed result with a navigation action', async () => {
    getProcessing.mockResolvedValue(processing(6))
    getChunks.mockResolvedValue({ data: [draftChunk] })
    const router = { push: vi.fn() }
    const wrapper = mountWorkspace(routeFor(), router)
    await flushPromises()

    expect(wrapper.find('[data-testid="create-preview"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="open-confirm"]').exists()).toBe(false)
    expect(wrapper.get('[data-testid="vectorization-complete"]').text()).toContain('索引建立完成')
    expect(wrapper.get('[data-testid="vectorization-complete"]').text()).toContain('1 个检索单元')
    expect(wrapper.find('[data-testid="reindex-chunk"]').exists()).toBe(true)

    await wrapper.get('[data-testid="back-to-knowledge"]').trigger('click')
    expect(router.push).toHaveBeenCalledWith({ name: 'KnowledgeDetail', params: { id: '11' } })
  })

  it('does not claim completion or expose actions when completed chunks fail to load', async () => {
    getProcessing.mockResolvedValue(processing(6))
    getChunks.mockRejectedValue({ response: { status: 503, data: { msg: '分块读取失败' } } })
    const wrapper = mountWorkspace()
    await flushPromises()

    expect(wrapper.text()).toContain('分块读取失败')
    expect(wrapper.find('[data-testid="vectorization-complete"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="back-to-knowledge"]').exists()).toBe(false)
  })

  it('does not reuse cached adjusting chunks when the completed snapshot fails to load', async () => {
    getProcessing
      .mockResolvedValueOnce(processing(3, { lockVersion: 3 }))
      .mockResolvedValueOnce(processing(6, { lockVersion: 4 }))
    getChunks
      .mockResolvedValueOnce({ data: [draftChunk] })
      .mockRejectedValueOnce({ response: { status: 503, data: { msg: '完成态分块读取失败' } } })
    const wrapper = mountWorkspace()
    await flushPromises()

    await wrapper.vm.refreshProcessing(false, wrapper.vm.currentContext())
    await flushPromises()

    expect(wrapper.text()).toContain('完成态分块读取失败')
    expect(wrapper.find('[data-testid="vectorization-complete"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="back-to-knowledge"]').exists()).toBe(false)
  })

  it('ADJUSTING exposes per-chunk reindex for an edited DRAFT', async () => {
    getProcessing.mockResolvedValue(processing(3))
    getChunks.mockResolvedValue({ data: [draftChunk] })
    const wrapper = mountWorkspace()
    await flushPromises()

    expect(wrapper.find('[data-testid="open-confirm"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="reindex-chunk"]').exists()).toBe(true)
  })

  it('keeps the existing reindex entry after an overlap setting changes a completed chunk back to modified DRAFT', async () => {
    const cleanChunk = { ...draftChunk, isModified: false, overlapEnabled: false }
    const changedChunk = { ...cleanChunk, isModified: true, overlapEnabled: true, lockVersion: 6 }
    getProcessing.mockResolvedValue(processing(6))
    getChunks
      .mockResolvedValueOnce({ data: [cleanChunk] })
      .mockResolvedValueOnce({ data: [changedChunk] })
    updateChunk.mockResolvedValue({ data: changedChunk })
    const wrapper = mountWorkspace()
    await flushPromises()

    expect(wrapper.find('[data-testid="reindex-chunk"]').exists()).toBe(false)
    await wrapper.get('[data-testid="overlap-switch"] .el-switch').trigger('click')
    await vi.advanceTimersByTimeAsync(650)
    await flushPromises()

    expect(wrapper.find('[data-testid="reindex-chunk"]').exists()).toBe(true)
  })

  it('FAILED vectorization keeps DRAFT chunks editable and reindexable after recovery edit', async () => {
    getProcessing.mockResolvedValue(processing(7, { failedFromState: 5, lastError: '向量服务不可用' }))
    getChunks.mockResolvedValue({ data: [draftChunk] })
    const wrapper = mountWorkspace()
    await flushPromises()

    expect(wrapper.get('[data-testid="edit-chunk"]').attributes('disabled')).toBeUndefined()
    expect(wrapper.get('[data-testid="delete-chunk"]').attributes('disabled')).toBeUndefined()
    expect(wrapper.find('[data-testid="reindex-chunk"]').exists()).toBe(false)
  })

  it('keeps every file action disabled after processing load failure until retry succeeds', async () => {
    getProcessing.mockRejectedValueOnce({ response: { status: 503, data: { msg: '状态服务不可用' } } })
    const wrapper = mountWorkspace()
    await flushPromises()

    expect(wrapper.text()).toContain('状态服务不可用')
    expect(wrapper.get('[data-testid="min-tokens"] input').attributes('disabled')).toBeDefined()
    expect(wrapper.find('[data-testid="create-preview"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="open-confirm"]').exists()).toBe(false)

    getProcessing.mockResolvedValueOnce(processing(0, {
      lockVersion: 12,
      policySnapshot: { minTokens: 72, targetTokens: 288, maxTokens: 504 },
    }))
    await wrapper.get('[data-testid="retry-processing-load"]').trigger('click')
    await flushPromises()
    expect(wrapper.get('[data-testid="min-tokens"] input').attributes('disabled')).toBeUndefined()
    expect(wrapper.get('[data-testid="min-tokens"] input').element.value).toBe('72')
    expect(wrapper.get('[data-testid="create-preview"]').attributes('disabled')).toBeUndefined()
  })

  it('summarizes per-chunk overlap in the final dialog and submits only the current file lock', async () => {
    getProcessing.mockResolvedValue(processing(3, { lockVersion: 9 }))
    getChunks.mockResolvedValue({ data: [
      { ...draftChunk, publicId: 'chunk-1', isModified: false, overlapEnabled: true, overlapContent: '已生成上文', overlapTokenCount: 8 },
      { ...draftChunk, publicId: 'chunk-2', isModified: false, overlapEnabled: true, overlapContent: null, overlapTokenCount: 0 },
      { ...draftChunk, publicId: 'chunk-3', isModified: false, overlapEnabled: false, overlapContent: '历史残留上文', overlapTokenCount: 6 },
    ] })
    const wrapper = mountWorkspace()
    await flushPromises()

    await wrapper.get('[data-testid="open-confirm"]').trigger('click')
    await flushPromises()
    const dialog = document.body.querySelector('[role="dialog"]')
    expect(dialog.querySelector('[data-testid="confirm-total-count"]').textContent).toContain('3')
    expect(dialog.querySelector('[data-testid="confirm-enabled-count"]').textContent).toContain('2')
    expect(dialog.querySelector('[data-testid="confirm-generated-count"]').textContent).toContain('1')
    expect(dialog.querySelector('[data-testid="overlap-switch"]')).toBeNull()
    expect(dialog.querySelector('[data-testid="overlap-tokens"]')).toBeNull()

    document.body.querySelector('[data-testid="confirm-vectorization"]').click()
    await flushPromises()
    expect(confirmVectorization).toHaveBeenCalledWith('11', '22', {
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

  it('keeps the source-change 422 visible so the user can regenerate the preview', async () => {
    getProcessing.mockResolvedValue(processing(3, { lockVersion: 9 }))
    getChunks.mockResolvedValue({ data: [{ ...draftChunk, isModified: false }] })
    confirmVectorization.mockRejectedValue({
      response: {
        status: 422,
        data: {
          code: 500,
          msg: 'The physical source changed after the chunk preview was generated',
          data: { errorCode: 'SOURCE_CHANGED' },
        },
      },
    })
    const wrapper = mountWorkspace()
    await flushPromises()

    await wrapper.get('[data-testid="open-confirm"]').trigger('click')
    await flushPromises()
    document.body.querySelector('[data-testid="confirm-vectorization"]').click()
    await flushPromises()

    const dialog = document.body.querySelector('[role="dialog"]')
    expect(dialog.textContent).toContain('源文件已发生变化，请重新生成分块预览')
    expect(dialog.querySelector('[role="alert"]')).not.toBeNull()
    expect(document.body.querySelector('[data-testid="confirm-vectorization"]')).not.toBeNull()
    wrapper.unmount()
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

  it('disables every chunk and file action while one reindex request is pending without creating a save blocker', async () => {
    const reindexRequest = deferred()
    const secondChunk = {
      ...draftChunk,
      publicId: 'chunk-2',
      position: 1,
      content: '第二块正文',
      lockVersion: 8,
    }
    getProcessing.mockResolvedValue(processing(3))
    getChunks.mockResolvedValue({ data: [draftChunk, secondChunk] })
    reindexChunk.mockReturnValue(reindexRequest.promise)
    const wrapper = mountWorkspace()
    await flushPromises()

    await wrapper.findAll('[data-testid="reindex-chunk"]')[0].trigger('click')
    await nextTick()

    expect(reindexChunk).toHaveBeenCalledTimes(1)
    expect(wrapper.findAll('.chunk-card').every(card => card.attributes('aria-disabled') === 'true')).toBe(true)
    expect(wrapper.get('[data-testid="create-preview"]').attributes('disabled')).toBeDefined()
    expect(wrapper.get('[data-testid="open-confirm"]').attributes('disabled')).toBeDefined()
    await wrapper.findAll('[data-testid="reindex-chunk"]')[1].trigger('click')
    await wrapper.findAll('[data-testid="edit-chunk"]')[1].trigger('click')
    await vi.advanceTimersByTimeAsync(650)
    expect(reindexChunk).toHaveBeenCalledTimes(1)
    expect(wrapper.find('textarea').exists()).toBe(false)
    expect(updateChunk).not.toHaveBeenCalled()
    expect(wrapper.vm.hasBlockingChunkSaves).toBe(false)

    reindexRequest.resolve({ status: 202 })
    await flushPromises()

    expect(getProcessing).toHaveBeenCalledTimes(2)
    expect(getChunks).toHaveBeenCalledTimes(2)
    expect(wrapper.vm.fileMutationInProgress).toBe(false)
    expect(wrapper.vm.hasBlockingChunkSaves).toBe(false)
    expect(wrapper.findAll('.chunk-card').every(card => card.attributes('aria-disabled') === 'false')).toBe(true)
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
