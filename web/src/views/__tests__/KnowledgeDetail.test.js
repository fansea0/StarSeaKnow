import { flushPromises, mount } from '@vue/test-utils'
import { nextTick, reactive } from 'vue'
import axios from 'axios'
import KnowledgeDetail from '../KnowledgeDetail.vue'

vi.mock('axios', () => ({
  default: {
    interceptors: {
      request: { use: vi.fn() },
    },
    get: vi.fn(),
    post: vi.fn(),
    put: vi.fn(),
    delete: vi.fn(),
    create: vi.fn(() => ({})),
  },
}))

const markdownFile = {
  id: 22,
  fileName: 'guide.md',
  size: 1024,
  status: 1,
  type: 'md',
  pipelineState: 0,
  createTime: '2026-09-01T00:00:00Z',
}

function mountDetail(route = { params: { id: '11' } }) {
  return mount(KnowledgeDetail, {
    global: {
      mocks: {
        $route: route,
        $router: { push: vi.fn() },
        $message: { success: vi.fn(), error: vi.fn(), info: vi.fn() },
      },
      stubs: {
        'el-icon': true,
        'el-button': { template: '<button @click="$emit(\'click\')"><slot /></button>' },
        'el-upload': { template: '<div><slot /></div>' },
        'el-switch': true,
        'el-form': true,
        'el-form-item': true,
        'el-input': true,
      },
    },
  })
}

describe('KnowledgeDetail', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    axios.get.mockResolvedValue({ data: { code: 200, data: [] } })
  })
  it('loads each route exactly once instead of duplicating watcher and mounted initialization', async () => {
    mountDetail()
    await flushPromises()

    expect(axios.get.mock.calls.filter(([url]) => url.endsWith('/knowledge/11'))).toHaveLength(1)
    expect(axios.get.mock.calls.filter(([url]) => url.endsWith('/knowledge/file/list'))).toHaveLength(1)
  })

  it('discards late knowledge and file responses from the previous reused route', async () => {
    const route = reactive({ params: { id: '11' } })
    const oldInfo = deferred()
    const oldFiles = deferred()
    axios.get.mockImplementation((url, options) => {
      if (url.endsWith('/knowledge/11')) return oldInfo.promise
      if (url.endsWith('/knowledge/12')) {
        return Promise.resolve({ data: { code: 200, data: { name: '知识库 B', description: 'B' } } })
      }
      if (url.endsWith('/knowledge/file/list') && options?.params?.knowledgeId === 11) return oldFiles.promise
      if (url.endsWith('/knowledge/file/list') && options?.params?.knowledgeId === 12) {
        return Promise.resolve({ data: { code: 200, data: [{ ...markdownFile, id: 120, fileName: 'b.md' }] } })
      }
      return Promise.resolve({ data: { code: 200, data: [] } })
    })
    const wrapper = mountDetail(route)
    await flushPromises()

    route.params.id = '12'
    await nextTick()
    await flushPromises()
    oldInfo.resolve({ data: { code: 200, data: { name: '知识库 A', description: 'A' } } })
    oldFiles.resolve({ data: { code: 200, data: [{ ...markdownFile, id: 110, fileName: 'a.md' }] } })
    await flushPromises()

    expect(wrapper.vm.kbInfo.name).toBe('知识库 B')
    expect(wrapper.vm.docList.map(row => row.fileName)).toEqual(['b.md'])
  })

  it('renders non-Markdown embedding as unavailable without calling the removed endpoint', async () => {
    const wrapper = mountDetail()
    await flushPromises()
    await wrapper.setData({ docList: [{ ...markdownFile, id: 31, type: 'pdf', fileName: 'guide.pdf' }] })

    const action = wrapper.get('[data-testid="file-primary-action-31"]')
    expect(action.text()).toContain('暂不支持')
    expect(action.attributes('disabled')).toBeDefined()
    await action.trigger('click')
    expect(axios.post).not.toHaveBeenCalled()
  })
  it('opens a Markdown row in the chunking workspace without the legacy embedding POST', async () => {
    const wrapper = mountDetail()
    await flushPromises()
    await wrapper.setData({ docList: [markdownFile] })

    await wrapper.get('[data-testid="file-primary-action-22"]').trigger('click')

    expect(wrapper.vm.$router.push).toHaveBeenCalledWith({
      name: 'ChunkingWorkspace',
      params: { knowledgeId: '11', fileId: '22' },
    })
    expect(axios.post).not.toHaveBeenCalledWith(expect.stringContaining('/knowledge/file'), expect.anything(), expect.anything())
  })

  it.each([
    [0, '待分块'],
    [1, '分块中'],
    [2, '待调整'],
    [3, '待调整'],
    [4, '待调整'],
    [5, '向量化中'],
    [6, '已完成'],
    [7, '失败'],
    [null, '未知'],
    [99, '未知'],
  ])('renders pipeline state %i as %s', async (pipelineState, label) => {
    const wrapper = mountDetail()
    await flushPromises()
    await wrapper.setData({ docList: [{ ...markdownFile, pipelineState }] })

    expect(wrapper.text()).toContain(label)
  })

  it('refreshes and opens the workspace after an uploaded Markdown file returns its id', async () => {
    const wrapper = mountDetail()
    const refresh = vi.spyOn(wrapper.vm, 'fetchDocList').mockResolvedValue()

    await wrapper.vm.onUploadSuccess({ code: 200, data: 23 }, { name: 'new-guide.md' })
    await flushPromises()

    expect(refresh).toHaveBeenCalledTimes(1)
    expect(wrapper.vm.$router.push).toHaveBeenCalledWith({
      name: 'ChunkingWorkspace',
      params: { knowledgeId: '11', fileId: '23' },
    })
  })

  it.each([
    [200, true],
    ['200', true],
    [' 200 ', true],
    [true, false],
    [null, false],
    ['', false],
    ['0200', false],
    ['200.0', false],
    ['ok', false],
  ])('accepts upload business code %p: %s', (code, expected) => {
    const wrapper = mountDetail()

    expect(wrapper.vm.isUploadSuccessCode(code)).toBe(expected)
  })

  it.each([
    [42, 42],
    ['42', 42],
    [' 00042 ', 42],
    [true, null],
    [0, null],
    [-1, null],
    ['0', null],
    ['-1', null],
    ['1.5', null],
    ['1e2', null],
    ['   ', null],
    [{ id: 42 }, null],
    [NaN, null],
    [Infinity, null],
    [Number.MAX_SAFE_INTEGER + 1, null],
  ])('normalizes upload file id %p to %p', (fileId, expected) => {
    const wrapper = mountDetail()

    expect(wrapper.vm.normalizeUploadFileId(fileId)).toBe(expected)
  })

  it.each([
    [{ code: '200', data: '42' }, true],
    [{ code: 200, data: true }, false],
  ])('handles upload envelope %p as success: %s', async (response, succeeds) => {
    const wrapper = mountDetail()
    const refresh = vi.spyOn(wrapper.vm, 'fetchDocList').mockResolvedValue()

    await wrapper.vm.onUploadSuccess(response, { name: 'new-guide.md' })

    expect(refresh).toHaveBeenCalledTimes(succeeds ? 1 : 0)
    expect(wrapper.vm.$router.push).toHaveBeenCalledTimes(succeeds ? 1 : 0)
    if (succeeds) {
      expect(wrapper.vm.$router.push).toHaveBeenCalledWith({
        name: 'ChunkingWorkspace',
        params: { knowledgeId: '11', fileId: '42' },
      })
    } else {
      expect(wrapper.vm.$message.error).toHaveBeenCalledWith('上传失败')
    }
  })

  it('awaits the document refresh before opening the Markdown workspace', async () => {
    const wrapper = mountDetail()
    let resolveRefresh
    const refresh = vi.spyOn(wrapper.vm, 'fetchDocList').mockImplementation(() => new Promise((resolve) => {
      resolveRefresh = resolve
    }))

    const upload = wrapper.vm.onUploadSuccess({ code: 200, data: 42 }, { name: 'new-guide.md' })

    expect(refresh).toHaveBeenCalledTimes(1)
    expect(wrapper.vm.$router.push).not.toHaveBeenCalled()
    resolveRefresh()
    await upload

    expect(wrapper.vm.$router.push).toHaveBeenCalledTimes(1)
    expect(refresh.mock.invocationCallOrder[0]).toBeLessThan(wrapper.vm.$router.push.mock.invocationCallOrder[0])
  })

  it('does not refresh or navigate when an HTTP-success upload envelope has a business error', async () => {
    const wrapper = mountDetail()
    const refresh = vi.spyOn(wrapper.vm, 'fetchDocList').mockResolvedValue()

    await wrapper.vm.onUploadSuccess({ code: 500, msg: '文件解析失败', data: 23 }, { name: 'new-guide.md' })

    expect(wrapper.vm.$message.error).toHaveBeenCalledWith('文件解析失败')
    expect(wrapper.vm.$message.success).not.toHaveBeenCalled()
    expect(refresh).not.toHaveBeenCalled()
    expect(wrapper.vm.$router.push).not.toHaveBeenCalled()
  })

  it('does not treat a successful envelope without a valid file id as uploaded', async () => {
    const wrapper = mountDetail()
    const refresh = vi.spyOn(wrapper.vm, 'fetchDocList').mockResolvedValue()

    await wrapper.vm.onUploadSuccess({ code: 200, data: null }, { name: 'new-guide.md' })

    expect(wrapper.vm.$message.error).toHaveBeenCalledWith('上传失败')
    expect(wrapper.vm.$message.success).not.toHaveBeenCalled()
    expect(refresh).not.toHaveBeenCalled()
    expect(wrapper.vm.$router.push).not.toHaveBeenCalled()
  })
})

function deferred() {
  let resolve
  const promise = new Promise(res => { resolve = res })
  return { promise, resolve }
}
