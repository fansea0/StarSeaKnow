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
  chunkingCapability: { available: true, reason: null },
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
        'el-upload': {
          name: 'ElUpload',
          props: ['httpRequest'],
          template: '<div><slot /></div>',
        },
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
    axios.get.mockImplementation((url) => {
      if (url.endsWith('/knowledge/11')) {
        return Promise.resolve({
          data: { code: 200, data: { id: 11, name: '测试知识库', description: '测试描述' } },
        })
      }
      return Promise.resolve({ data: { code: 200, data: [] } })
    })
  })

  it('does not render the MaxKB fixture while knowledge details are loading', async () => {
    const pendingInfo = deferred()
    axios.get.mockImplementation((url) => {
      if (url.endsWith('/knowledge/11')) return pendingInfo.promise
      return Promise.resolve({ data: { code: 200, data: [] } })
    })

    const wrapper = mountDetail()
    await nextTick()

    expect(wrapper.text()).not.toContain('MaxKB 用户手册')
    expect(wrapper.get('[data-testid="knowledge-loading"]').text()).toContain('正在加载知识库')
  })

  it('shows an unavailable state and does not load files when knowledge details are missing', async () => {
    axios.get.mockImplementation((url) => {
      if (url.endsWith('/knowledge/11')) {
        return Promise.resolve({ data: { code: 200, data: null } })
      }
      return Promise.resolve({ data: { code: 200, data: [] } })
    })

    const wrapper = mountDetail()
    await flushPromises()

    expect(wrapper.get('[data-testid="knowledge-unavailable"]').text()).toContain('知识库不存在或无权访问')
    expect(wrapper.text()).not.toContain('MaxKB 用户手册')
    expect(axios.get.mock.calls.filter(([url]) => url.endsWith('/knowledge/file/list'))).toHaveLength(0)
  })

  it('shows the backend unavailable message when the tenant-scoped detail request returns 404', async () => {
    axios.get.mockImplementation((url) => {
      if (url.endsWith('/knowledge/11')) {
        return Promise.reject({
          response: { data: { msg: '知识库不存在或无权访问' } },
        })
      }
      return Promise.resolve({ data: { code: 200, data: [] } })
    })

    const wrapper = mountDetail()
    await flushPromises()

    expect(wrapper.get('[data-testid="knowledge-unavailable"]').text()).toContain('知识库不存在或无权访问')
    expect(wrapper.vm.$message.error).toHaveBeenCalledWith('知识库不存在或无权访问')
    expect(axios.get.mock.calls.filter(([url]) => url.endsWith('/knowledge/file/list'))).toHaveLength(0)
  })

  it('loads each route exactly once instead of duplicating watcher and mounted initialization', async () => {
    mountDetail()
    await flushPromises()

    expect(axios.get.mock.calls.filter(([url]) => url.endsWith('/knowledge/11'))).toHaveLength(1)
    expect(axios.get.mock.calls.filter(([url]) => url.endsWith('/knowledge/file/list'))).toHaveLength(1)
  })

  it('uploads documents through axios so auth interceptors can attach and refresh the access token', async () => {
    const wrapper = mountDetail()
    await flushPromises()
    const file = new File(['# Guide'], 'guide.md', { type: 'text/markdown' })
    const response = { code: 200, data: 23 }
    axios.post.mockResolvedValue({ data: response })

    const upload = wrapper.findComponent({ name: 'ElUpload' })
    const result = await upload.props('httpRequest')({ file })

    expect(axios.post).toHaveBeenCalledWith(
      expect.stringMatching(/\/file\/uploadToKnow\/11$/),
      expect.any(FormData),
    )
    expect(axios.post.mock.calls[0][1].get('file')).toBe(file)
    expect(result).toBe(response)
  })

  it('discards a late knowledge response from the previous reused route', async () => {
    const route = reactive({ params: { id: '11' } })
    const oldInfo = deferred()
    axios.get.mockImplementation((url, options) => {
      if (url.endsWith('/knowledge/11')) return oldInfo.promise
      if (url.endsWith('/knowledge/12')) {
        return Promise.resolve({ data: { code: 200, data: { name: '知识库 B', description: 'B' } } })
      }
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
    await flushPromises()

    expect(wrapper.vm.kbInfo.name).toBe('知识库 B')
    expect(wrapper.vm.docList.map(row => row.fileName)).toEqual(['b.md'])
  })

  it('discards a late file response from the previous reused route', async () => {
    const route = reactive({ params: { id: '11' } })
    const oldFiles = deferred()
    axios.get.mockImplementation((url, options) => {
      if (url.endsWith('/knowledge/11')) {
        return Promise.resolve({ data: { code: 200, data: { name: '知识库 A', description: 'A' } } })
      }
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
    expect(axios.get.mock.calls.some(([, options]) => options?.params?.knowledgeId === 11)).toBe(true)

    route.params.id = '12'
    await nextTick()
    await flushPromises()
    oldFiles.resolve({ data: { code: 200, data: [{ ...markdownFile, id: 110, fileName: 'a.md' }] } })
    await flushPromises()

    expect(wrapper.vm.kbInfo.name).toBe('知识库 B')
    expect(wrapper.vm.docList.map(row => row.fileName)).toEqual(['b.md'])
  })

  it('renders backend-unavailable files with the exact capability reason', async () => {
    const wrapper = mountDetail()
    await flushPromises()
    await wrapper.setData({ docList: [{ ...markdownFile, id: 31, type: 'pdf', fileName: 'guide.pdf', chunkingCapability: { available: false, reason: 'PDF 文本提取失败' } }] })

    const action = wrapper.get('[data-testid="file-primary-action-31"]')
    expect(action.text()).toContain('暂不可用')
    expect(wrapper.text()).toContain('PDF 文本提取失败')
    expect(action.attributes('disabled')).toBeDefined()
    await action.trigger('click')
    expect(axios.post).not.toHaveBeenCalled()
  })

  it('uses a neutral reason when capability is missing', async () => {
    const wrapper = mountDetail()
    await flushPromises()

    wrapper.vm.handleFileAction({ id: 31, type: 'pdf', fileName: 'guide.pdf' })

    expect(wrapper.vm.$message.info).toHaveBeenCalledWith('尚未获得该文件的分块能力信息')
  })
  it('opens any capability-enabled row in the chunking workspace without extension inference', async () => {
    const wrapper = mountDetail()
    await flushPromises()
    await wrapper.setData({ docList: [{ ...markdownFile, type: 'pdf', fileName: 'guide.pdf' }] })

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
    await flushPromises()
    const refresh = vi.spyOn(wrapper.vm, 'fetchDocList').mockImplementation(async () => {
      wrapper.vm.docList = [{ ...markdownFile, id: 23, type: 'txt', fileName: 'guide.txt' }]
    })

    await completeUpload(wrapper, { code: 200, data: 23 })
    await flushPromises()

    expect(refresh).toHaveBeenCalledTimes(1)
    expect(wrapper.vm.$router.push).toHaveBeenCalledWith({
      name: 'ChunkingWorkspace',
      params: { knowledgeId: '11', fileId: '23' },
    })
  })

  it('does not auto-open an uploaded file when refreshed capability is unavailable', async () => {
    const wrapper = mountDetail()
    await flushPromises()
    vi.spyOn(wrapper.vm, 'fetchDocList').mockImplementation(async () => {
      wrapper.vm.docList = [{ ...markdownFile, id: 23, chunkingCapability: { available: false, reason: '内容不可提取' } }]
    })

    await completeUpload(wrapper, { code: 200, data: 23 }, 'guide.pdf')

    expect(wrapper.vm.$router.push).not.toHaveBeenCalled()
  })

  it('ignores an upload from knowledge A when its success arrives after navigating to knowledge B', async () => {
    const route = reactive({ params: { id: '11' } })
    const wrapper = mountDetail(route)
    await flushPromises()
    const rawFile = { name: 'late-guide.md' }
    wrapper.vm.beforeUpload(rawFile)

    route.params.id = '12'
    await nextTick()
    await flushPromises()
    const refresh = vi.spyOn(wrapper.vm, 'fetchDocList').mockImplementation(async () => {
      if (succeeds) wrapper.vm.docList = [{ ...markdownFile, id: 42, type: 'pdf', fileName: 'new.pdf' }]
    })
    wrapper.vm.$router.push.mockClear()
    wrapper.vm.$message.success.mockClear()

    await wrapper.vm.onUploadSuccess(
      { code: 200, data: 23 },
      { name: 'late-guide.md', raw: rawFile },
    )
    await flushPromises()

    expect(refresh).not.toHaveBeenCalled()
    expect(wrapper.vm.$router.push).not.toHaveBeenCalled()
    expect(wrapper.vm.$message.success).not.toHaveBeenCalled()
    expect(wrapper.vm.knowledgeId).toBe(12)
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
    await flushPromises()
    const refresh = vi.spyOn(wrapper.vm, 'fetchDocList').mockImplementation(async () => {
      if (succeeds) wrapper.vm.docList = [{ ...markdownFile, id: 42, type: 'pdf', fileName: 'new.pdf' }]
    })

    await completeUpload(wrapper, response)

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
    await flushPromises()
    let resolveRefresh
    const refresh = vi.spyOn(wrapper.vm, 'fetchDocList').mockImplementation(() => new Promise((resolve) => {
      resolveRefresh = () => {
        wrapper.vm.docList = [{ ...markdownFile, id: 42 }]
        resolve()
      }
    }))

    const upload = completeUpload(wrapper, { code: 200, data: 42 })

    expect(refresh).toHaveBeenCalledTimes(1)
    expect(wrapper.vm.$router.push).not.toHaveBeenCalled()
    resolveRefresh()
    await upload

    expect(wrapper.vm.$router.push).toHaveBeenCalledTimes(1)
    expect(refresh.mock.invocationCallOrder[0]).toBeLessThan(wrapper.vm.$router.push.mock.invocationCallOrder[0])
  })

  it('does not refresh or navigate when an HTTP-success upload envelope has a business error', async () => {
    const wrapper = mountDetail()
    await flushPromises()
    const refresh = vi.spyOn(wrapper.vm, 'fetchDocList').mockResolvedValue()

    await completeUpload(wrapper, { code: 500, msg: '文件解析失败', data: 23 })

    expect(wrapper.vm.$message.error).toHaveBeenCalledWith('文件解析失败')
    expect(wrapper.vm.$message.success).not.toHaveBeenCalled()
    expect(refresh).not.toHaveBeenCalled()
    expect(wrapper.vm.$router.push).not.toHaveBeenCalled()
  })

  it('does not treat a successful envelope without a valid file id as uploaded', async () => {
    const wrapper = mountDetail()
    await flushPromises()
    const refresh = vi.spyOn(wrapper.vm, 'fetchDocList').mockResolvedValue()

    await completeUpload(wrapper, { code: 200, data: null })

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

function completeUpload(wrapper, response, name = 'new-guide.md') {
  const raw = { name }
  wrapper.vm.beforeUpload(raw)
  return wrapper.vm.onUploadSuccess(response, { name, raw })
}
