import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import ModelProviders from '../ModelProviders.vue'
import {
  addProviderModel,
  createProviderConnection,
  listModelProviders,
  testProviderConnection,
  updateProviderConnection,
  replaceProviderModels,
} from '../../api/modelProviders'

vi.mock('../../api/modelProviders', () => ({
  listModelProviders: vi.fn(),
  testProviderConnection: vi.fn(),
  createProviderConnection: vi.fn(),
  updateProviderConnection: vi.fn(),
  addProviderModel: vi.fn(),
  replaceProviderModels: vi.fn(),
  deleteProviderConnection: vi.fn(),
}))

const providers = [
  {
    catalogProviderId: 1,
    connectionId: null,
    code: 'OPENAI',
    name: 'OpenAI',
    icon: 'provider/openai',
    baseUrl: 'https://api.openai.com/v1',
    authType: 'API_KEY',
    selectableModels: [{ modelId: 'gpt-4o-mini', displayName: 'GPT-4o Mini', contextWindow: 128000 }],
    configured: false,
    custom: false,
    apiKeyConfigured: false,
  },
  {
    catalogProviderId: 6,
    connectionId: 26,
    code: 'OLLAMA',
    name: 'Ollama',
    icon: 'provider/ollama',
    baseUrl: 'http://localhost:11434/v1',
    authType: 'NONE',
    selectableModels: [{ modelId: 'qwen3:8b', displayName: 'qwen3:8b', contextWindow: 32768 }],
    configured: true,
    custom: false,
    apiKeyConfigured: false,
  },
]

function mountPage() {
  return mount(ModelProviders, {
    global: {
      mocks: {
        $message: { success: vi.fn(), error: vi.fn() },
        $confirm: vi.fn().mockResolvedValue(true),
      },
    },
  })
}

describe('ModelProviders', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    listModelProviders.mockResolvedValue({ data: { code: 200, data: structuredClone(providers) } })
  })

  it('renders the prototype provider rail and omits an enabled-state column', async () => {
    const wrapper = mountPage()
    await flushPromises()

    expect(wrapper.text()).toContain('集中管理模型预设')
    expect(wrapper.text()).toContain('OpenAI')
    expect(wrapper.text()).toContain('Ollama')
    expect(wrapper.text()).toContain('未配置')
    expect(wrapper.find('[data-testid="model-table"]').text()).not.toContain('状态')
  })

  it('tests and saves a built-in provider connection without echoing the key', async () => {
    testProviderConnection.mockResolvedValue({ data: { code: 200, data: { connected: true, discoveredModels: [] } } })
    createProviderConnection.mockResolvedValue({ data: { code: 200, data: { ...providers[0], connectionId: 31, configured: true } } })
    const wrapper = mountPage()
    await flushPromises()

    await wrapper.get('[data-testid="configure-OPENAI"]').trigger('click')
    await wrapper.get('[data-testid="connection-api-key"]').setValue('sk-private')
    await wrapper.get('[data-testid="test-connection"]').trigger('click')
    await flushPromises()
    expect(testProviderConnection).toHaveBeenCalledWith(expect.objectContaining({
      catalogProviderId: 1,
      apiKey: 'sk-private',
    }))

    await wrapper.get('[data-testid="connection-form"]').trigger('submit')
    await flushPromises()
    expect(createProviderConnection).toHaveBeenCalledWith(expect.objectContaining({ apiKey: 'sk-private' }))
    expect(wrapper.text()).not.toContain('sk-private')
  })

  it('adds a model only under an already configured connection', async () => {
    addProviderModel.mockResolvedValue({ data: { code: 200, data: providers[1] } })
    const wrapper = mountPage()
    await flushPromises()

    await wrapper.get('[data-testid="provider-OLLAMA"]').trigger('click')
    await wrapper.get('[data-testid="add-model"]').trigger('click')
    await wrapper.get('[data-testid="model-display-name"]').setValue('本地问答')
    await wrapper.get('[data-testid="model-id"]').setValue('qwen3:14b')
    await wrapper.get('[data-testid="model-context-window"]').setValue('65536')
    await wrapper.get('[data-testid="model-form"]').trigger('submit')
    await flushPromises()

    expect(addProviderModel).toHaveBeenCalledWith(26, {
      displayName: '本地问答',
      modelId: 'qwen3:14b',
      contextWindow: 65536,
    })
  })
  it('does not offer deletion on an unconfigured built-in catalog model', async () => {
    const wrapper = mountPage(); await flushPromises()
    expect(wrapper.get('[data-testid="model-table"]').findAll('button').filter(b => b.text() === '删除')).toHaveLength(0)
  })
  it('preserves custom model candidates while editing its connection', async () => {
    const custom = { ...providers[1], catalogProviderId: null, custom: true, code: 'CUSTOM', name: '企业模型', icon: '企' }
    listModelProviders.mockResolvedValue({ data: { code: 200, data: [custom] } })
    updateProviderConnection.mockResolvedValue({ data: { code: 200, data: custom } })
    const wrapper = mountPage(); await flushPromises()
    await wrapper.get('[data-testid="configure-CUSTOM"]').trigger('click')
    await wrapper.get('[data-testid="connection-form"]').trigger('submit'); await flushPromises()
    expect(updateProviderConnection).toHaveBeenCalledWith(26, expect.objectContaining({ selectableModels: custom.selectableModels }))
  })
  it('edits a configured model without removing other candidates', async () => {
    replaceProviderModels.mockResolvedValue({ data: { code: 200, data: providers[1] } })
    const wrapper = mountPage(); await flushPromises()
    await wrapper.get('[data-testid="provider-OLLAMA"]').trigger('click')
    await wrapper.get('[data-testid="edit-model-qwen3:8b"]').trigger('click')
    await wrapper.get('[data-testid="model-display-name"]').setValue('本地助手')
    await wrapper.get('[data-testid="model-form"]').trigger('submit'); await flushPromises()
    expect(replaceProviderModels).toHaveBeenCalledWith(26, [{ displayName: '本地助手', modelId: 'qwen3:8b', contextWindow: 32768 }])
  })
})
