import { flushPromises, shallowMount } from '@vue/test-utils'
import Tenants from '../Tenants.vue'
import { http } from '../../../api/http'

vi.mock('../../../api/http', () => ({
  http: {
    get: vi.fn(),
    post: vi.fn(),
    patch: vi.fn(),
  },
}))

describe('platform tenants page', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    http.get.mockResolvedValue({ data: { data: { items: [{ id: 7, name: '星海科技', code: 'tenant-x', status: 1, createTime: '2026-09-02T00:00:00Z' }], total: 1 } } })
  })

  it('shows a visible user-management action for every tenant', async () => {
    const wrapper = shallowMount(Tenants, {
      global: {
        directives: { loading: () => {} },
        stubs: {
          'el-button': true,
          'el-input': true,
          'el-option': true,
          'el-select': true,
          'el-tag': true,
          'el-pagination': true,
          'el-drawer': true,
          'el-form': true,
          'el-form-item': true,
          'el-dialog': true,
          'el-table-column': { template: '<div><slot :row="{ username: \'user\', mustChangePassword: false }" /></div>' },
        },
      },
    })
    await flushPromises()

    expect(wrapper.text()).toContain('查看用户')
  })
})
