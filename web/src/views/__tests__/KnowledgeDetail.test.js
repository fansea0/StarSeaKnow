import { flushPromises, mount } from '@vue/test-utils'
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

function mountDetail() {
  axios.get.mockResolvedValue({ data: { code: 200, data: [] } })
  return mount(KnowledgeDetail, {
    global: {
      mocks: {
        $route: { params: { id: '11' } },
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
