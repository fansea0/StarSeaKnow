import { mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import ChunkPreviewPanel from '../ChunkPreviewPanel.vue'

const chunk = (publicId, position) => ({
  publicId,
  position,
  siblingPosition: position,
  chunkType: 'SINGLE',
  content: `正文 ${publicId}`,
  sectionPath: ['手册'],
  tokenCount: 12,
  status: 0,
  lockVersion: 1,
  overlapEnabled: false,
  overlapTokenLimit: 40,
})

function mountPreview(chunks = [chunk('single-1', 0)]) {
  return mount(ChunkPreviewPanel, {
    props: { knowledgeId: '11', fileId: '22', chunks, showConfirm: true },
    global: { plugins: [ElementPlus] },
  })
}

describe('ChunkPreviewPanel', () => {
  it('derives and renders a single-only fallback hierarchy when callers provide only flat chunks', () => {
    const wrapper = mountPreview()

    expect(wrapper.findAll('.chunk-card')).toHaveLength(1)
    expect(wrapper.get('.chunk-count').text()).toBe('1 块')
    expect(wrapper.get('.preview-panel__footer').text()).toContain('1 个检索单元')
  })

  it('recomputes the fallback hierarchy when flat chunks change', async () => {
    const wrapper = mountPreview()

    await wrapper.setProps({ chunks: [chunk('single-1', 0), chunk('single-2', 1)] })

    expect(wrapper.findAll('.chunk-card')).toHaveLength(2)
    expect(wrapper.get('.chunk-count').text()).toBe('2 块')
    expect(wrapper.get('.preview-panel__footer').text()).toContain('2 个检索单元')
  })
})
