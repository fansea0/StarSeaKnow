import { mount, flushPromises } from '@vue/test-utils'
import ElementPlus, { ElMessageBox } from 'element-plus'
import { nextTick } from 'vue'
import { deleteChunk, updateChunk } from '../../../api/chunking'
import ChunkCard from '../ChunkCard.vue'

vi.mock('../../../api/chunking', () => ({
  updateChunk: vi.fn(),
  deleteChunk: vi.fn(),
}))

const chunk = {
  publicId: 'chunk-1',
  position: 0,
  content: '原始正文',
  sectionPath: ['产品手册', '安装'],
  sourceLocator: { startLine: 8, endLine: 12 },
  tokenCount: 18,
  status: 0,
  isModified: false,
  lockVersion: 4,
  overlapEnabled: false,
  overlapTokenLimit: 40,
  overlapContent: null,
  overlapTokenCount: 0,
  overlapUnavailableReason: null,
}

function mountCard(overrides = {}) {
  return mount(ChunkCard, {
    props: {
      knowledgeId: '11',
      fileId: '22',
      chunk: { ...chunk, ...overrides },
    },
    global: { plugins: [ElementPlus] },
  })
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

describe('ChunkCard', () => {
  beforeEach(() => {
    vi.useFakeTimers()
    vi.clearAllMocks()
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  it('renders a readonly semantic path and only reveals an editor after 编辑', async () => {
    const wrapper = mountCard()

    expect(wrapper.get('[data-testid="section-path"]').text()).toContain('产品手册')
    expect(wrapper.get('[data-testid="section-path"]').text()).toContain('安装')
    expect(wrapper.get('[data-testid="section-path"]').attributes('title')).toBe('产品手册 / 安装')
    expect(wrapper.get('[data-testid="section-path"]').attributes('aria-label')).toBe('语义标题路径：产品手册 / 安装')
    expect(wrapper.find('[data-testid="section-path"] input').exists()).toBe(false)
    expect(wrapper.find('textarea').exists()).toBe(false)
    expect(wrapper.get('[data-testid="chunk-body"]').text()).toBe('原始正文')
    expect(wrapper.text()).toContain('正文 18 Token')

    await wrapper.get('[data-testid="edit-chunk"]').trigger('click')

    expect(wrapper.get('textarea').element.value).toBe('原始正文')
  })

  it('uses a supplied child label and hides overlap controls without dropping server overlap payload', async () => {
    updateChunk.mockResolvedValue({ data: { ...chunk, content: '子块已编辑', overlapEnabled: true, overlapTokenLimit: 32, lockVersion: 5 } })
    const wrapper = mount(ChunkCard, {
      props: {
        knowledgeId: '11', fileId: '22', chunk: { ...chunk, overlapEnabled: true, overlapTokenLimit: 32 },
        label: '检索子块 01', showOverlapControls: false,
      },
      global: { plugins: [ElementPlus] },
    })

    expect(wrapper.text()).toContain('检索子块 01')
    expect(wrapper.find('[data-testid="overlap-switch"]').exists()).toBe(false)
    await wrapper.get('[data-testid="edit-chunk"]').trigger('click')
    await wrapper.get('textarea').setValue('子块已编辑')
    await vi.advanceTimersByTimeAsync(650)

    expect(updateChunk).toHaveBeenCalledWith('11', '22', 'chunk-1', {
      content: '子块已编辑', overlapEnabled: true, overlapTokenLimit: 32, lockVersion: 4,
    })
  })

  it('debounces edits for 650ms, sends the complete editable contract, then reports saved', async () => {
    const pending = deferred()
    updateChunk.mockReturnValue(pending.promise)
    const wrapper = mountCard()
    await wrapper.get('[data-testid="edit-chunk"]').trigger('click')
    await wrapper.get('textarea').setValue('更新后的正文')

    await vi.advanceTimersByTimeAsync(649)
    expect(updateChunk).not.toHaveBeenCalled()

    await vi.advanceTimersByTimeAsync(1)
    expect(updateChunk).toHaveBeenCalledWith('11', '22', 'chunk-1', {
      content: '更新后的正文',
      overlapEnabled: false,
      overlapTokenLimit: 40,
      lockVersion: 4,
    })
    expect(wrapper.get('[data-testid="save-status"]').text()).toBe('保存中')

    pending.resolve({ data: { ...chunk, content: '更新后的正文', lockVersion: 5, isModified: true } })
    await flushPromises()

    expect(wrapper.get('[data-testid="save-status"]').text()).toBe('已保存')
    expect(wrapper.emitted('updated')?.[0]?.[0]).toMatchObject({ content: '更新后的正文', lockVersion: 5 })
  })

  it('shows a 409 conflict with an explicit reload action', async () => {
    updateChunk.mockRejectedValue({ response: { status: 409, data: { msg: '版本已经变化' } } })
    const wrapper = mountCard()
    await wrapper.get('[data-testid="edit-chunk"]').trigger('click')
    await wrapper.get('textarea').setValue('冲突正文')
    await vi.advanceTimersByTimeAsync(650)
    await flushPromises()

    expect(wrapper.get('[role="alert"]').text()).toContain('版本已经变化')
    expect(wrapper.get('[data-testid="reload-chunk"]').text()).toContain('重新加载')
    await wrapper.get('[data-testid="reload-chunk"]').trigger('click')
    expect(wrapper.emitted('reload')).toHaveLength(1)
  })

  it('serializes edits made during a save and sends the latest body with the returned lockVersion', async () => {
    const firstSave = deferred()
    updateChunk
      .mockReturnValueOnce(firstSave.promise)
      .mockResolvedValueOnce({ data: { ...chunk, content: '第二次正文', lockVersion: 6, isModified: true } })
    const wrapper = mountCard()
    await wrapper.get('[data-testid="edit-chunk"]').trigger('click')

    await wrapper.get('textarea').setValue('第一次正文')
    await vi.advanceTimersByTimeAsync(650)
    await wrapper.get('textarea').setValue('第二次正文')
    await vi.advanceTimersByTimeAsync(650)
    expect(updateChunk).toHaveBeenCalledTimes(1)

    firstSave.resolve({ data: { ...chunk, content: '第一次正文', lockVersion: 5, isModified: true } })
    await flushPromises()

    expect(updateChunk).toHaveBeenNthCalledWith(2, '11', '22', 'chunk-1', {
      content: '第二次正文',
      overlapEnabled: false,
      overlapTokenLimit: 40,
      lockVersion: 5,
    })
    expect(wrapper.get('textarea').element.value).toBe('第二次正文')
    expect(wrapper.get('[data-testid="save-status"]').text()).toBe('已保存')
  })

  it('keeps a rejected body visible and explains blank and 422 validation failures', async () => {
    const wrapper = mountCard()
    await wrapper.get('[data-testid="edit-chunk"]').trigger('click')
    await wrapper.get('textarea').setValue('   ')
    await vi.advanceTimersByTimeAsync(650)

    expect(updateChunk).not.toHaveBeenCalled()
    expect(wrapper.get('[role="alert"]').text()).toContain('正文不能为空')

    updateChunk.mockRejectedValue({ response: { status: 422, data: { msg: '正文超过 Token 上限' } } })
    await wrapper.get('textarea').setValue('仍需人工调整的正文')
    await vi.advanceTimersByTimeAsync(650)
    await flushPromises()

    expect(wrapper.get('textarea').element.value).toBe('仍需人工调整的正文')
    expect(wrapper.get('[role="alert"]').text()).toContain('正文超过 Token 上限')
  })

  it('deletes only after the real Element Plus confirmation resolves', async () => {
    const confirm = vi.spyOn(ElMessageBox, 'confirm')
    confirm.mockRejectedValueOnce(new Error('cancelled'))
    const cancelled = mountCard()
    await cancelled.get('[data-testid="delete-chunk"]').trigger('click')
    await flushPromises()
    expect(deleteChunk).not.toHaveBeenCalled()

    confirm.mockResolvedValueOnce('confirm')
    deleteChunk.mockResolvedValue({ status: 204 })
    const confirmed = mountCard()
    await confirmed.get('[data-testid="delete-chunk"]').trigger('click')
    await flushPromises()

    expect(deleteChunk).toHaveBeenCalledWith('11', '22', 'chunk-1', 4)
    expect(confirmed.emitted('deleted')).toHaveLength(1)
  })

  it('shows the per-chunk overlap setting and renders only backend-provided readonly context', async () => {
    const wrapper = mountCard({
      overlapEnabled: true,
      overlapTokenLimit: 64,
      overlapContent: '后端生成的完整补充上文',
      overlapTokenCount: 17,
      indexContent: '机密索引内容',
    })

    expect(wrapper.get('[data-testid="overlap-switch"]').text()).toContain('补充上文')
    expect(wrapper.get('[data-testid="overlap-token-limit"] input').element.value).toBe('64')
    expect(wrapper.get('[data-testid="overlap-content"]').text()).toContain('后端生成的完整补充上文')
    expect(wrapper.get('[data-testid="overlap-token-count"]').text()).toContain('17 Token')
    expect(wrapper.text()).not.toContain('机密索引内容')
  })

  it('uses 40 as the fallback limit and maps stable backend reason codes to Chinese', async () => {
    const backendReason = mountCard({
      overlapEnabled: true,
      overlapTokenLimit: 0,
      overlapUnavailableReason: 'NO_AVAILABLE_OVERLAP',
    })

    expect(backendReason.get('[data-testid="overlap-token-limit"] input').element.value).toBe('40')
    expect(backendReason.get('[data-testid="overlap-unavailable"]').text()).toContain('当前分块没有可补充的完整上文')
    expect(backendReason.text()).not.toContain('NO_AVAILABLE_OVERLAP')
    expect(backendReason.text()).not.toContain('产品手册 / 安装原始正文')

    const unknownReason = mountCard({
      overlapEnabled: true,
      overlapContent: null,
      overlapUnavailableReason: 'FUTURE_REASON',
    })
    expect(unknownReason.get('[data-testid="overlap-unavailable"]').text()).toContain('暂时无法生成补充上文')
    expect(unknownReason.text()).not.toContain('FUTURE_REASON')
    expect(unknownReason.get('[data-testid="overlap-token-count"]').text()).toContain('0 Token')
  })

  it('serializes overlap changes behind an in-flight body save and reuses the returned lockVersion', async () => {
    const firstSave = deferred()
    updateChunk
      .mockReturnValueOnce(firstSave.promise)
      .mockResolvedValueOnce({ data: { ...chunk, content: '第一次正文', overlapEnabled: true, lockVersion: 6, isModified: true } })
    const wrapper = mountCard()
    await wrapper.get('[data-testid="edit-chunk"]').trigger('click')
    await wrapper.get('textarea').setValue('第一次正文')
    await vi.advanceTimersByTimeAsync(650)

    await wrapper.get('[data-testid="overlap-switch"] .el-switch').trigger('click')
    await vi.advanceTimersByTimeAsync(650)
    expect(updateChunk).toHaveBeenCalledTimes(1)

    firstSave.resolve({ data: { ...chunk, content: '第一次正文', lockVersion: 5, isModified: true } })
    await flushPromises()

    expect(updateChunk).toHaveBeenNthCalledWith(2, '11', '22', 'chunk-1', {
      content: '第一次正文',
      overlapEnabled: true,
      overlapTokenLimit: 40,
      lockVersion: 5,
    })
  })

  it('saves a 1-512 overlap limit through the same queue and clears pending saves on unmount', async () => {
    updateChunk.mockResolvedValue({ data: { ...chunk, overlapEnabled: true, overlapTokenLimit: 512, lockVersion: 5 } })
    const wrapper = mountCard({ overlapEnabled: true })
    const limitInput = wrapper.get('[data-testid="overlap-token-limit"] input')
    expect(limitInput.attributes('min')).toBe('1')
    expect(limitInput.attributes('max')).toBe('512')
    await limitInput.setValue('512')
    await limitInput.trigger('change')
    await vi.advanceTimersByTimeAsync(650)

    expect(updateChunk).toHaveBeenCalledWith('11', '22', 'chunk-1', {
      content: '原始正文',
      overlapEnabled: true,
      overlapTokenLimit: 512,
      lockVersion: 4,
    })

    await wrapper.get('[data-testid="edit-chunk"]').trigger('click')
    await wrapper.get('textarea').setValue('不会保存')
    vi.clearAllMocks()
    wrapper.unmount()
    await vi.advanceTimersByTimeAsync(650)
    expect(updateChunk).not.toHaveBeenCalled()
  })
})
