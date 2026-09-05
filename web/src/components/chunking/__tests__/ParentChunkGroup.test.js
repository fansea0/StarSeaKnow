import { mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import ParentChunkGroup from '../ParentChunkGroup.vue'

const parent = {
  publicId: 'P1', position: 4, siblingPosition: 0, chunkType: 'PARENT',
  content: '父块完整上下文\n保留换行', sectionPath: ['手册', '安装'], tokenCount: 300,
}
const child = (publicId, siblingPosition) => ({
  publicId, position: 5 + siblingPosition, siblingPosition, chunkType: 'CHILD', parentPublicId: 'P1',
  content: `子块 ${publicId}`, sectionPath: ['手册', '安装'], tokenCount: 50, status: 0,
  lockVersion: 1, overlapEnabled: true, overlapTokenLimit: 32,
})

function mountGroup(props = {}) {
  return mount(ParentChunkGroup, {
    props: {
      knowledgeId: '11', fileId: '22', parent, children: [child('C1', 0), child('C2', 1)],
      ...props,
    },
    global: { plugins: [ElementPlus] },
  })
}

describe('ParentChunkGroup', () => {
  it('renders read-only parent context and nested retrieval children without overlap controls', () => {
    const wrapper = mountGroup()

    expect(wrapper.get('[data-testid="parent-chunk-P1"]').text()).toContain('父块 01')
    expect(wrapper.text()).toContain('手册 / 安装')
    expect(wrapper.text()).toContain('300 Token')
    expect(wrapper.get('[data-testid="parent-content"]').text()).toContain('父块完整上下文')
    expect(wrapper.findAll('[data-testid="child-chunk"]')).toHaveLength(2)
    expect(wrapper.find('[data-testid="overlap-switch"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="edit-parent"]').exists()).toBe(false)
    expect(wrapper.get('[data-testid="toggle-parent"]').attributes('aria-expanded')).toBe('true')
  })

  it('forwards every child event and payload unchanged while allowing collapse', async () => {
    const wrapper = mountGroup()
    const childCard = wrapper.findComponent({ name: 'ChunkCard' })
    const updated = { ...child('C1', 0), content: '已编辑' }
    const saveState = { publicId: 'C1', blocking: true }

    childCard.vm.$emit('updated', updated)
    childCard.vm.$emit('deleted', 'C1')
    childCard.vm.$emit('reload', 'C1')
    childCard.vm.$emit('reindex', updated)
    childCard.vm.$emit('save-state', saveState)
    await wrapper.vm.$nextTick()

    expect(wrapper.emitted('updated')?.[0]).toEqual([updated])
    expect(wrapper.emitted('deleted')?.[0]).toEqual(['C1'])
    expect(wrapper.emitted('reload')?.[0]).toEqual(['C1'])
    expect(wrapper.emitted('reindex')?.[0]).toEqual([updated])
    expect(wrapper.emitted('save-state')?.[0]).toEqual([saveState])
    await wrapper.get('[data-testid="toggle-parent"]').trigger('click')
    expect(wrapper.get('[data-testid="toggle-parent"]').attributes('aria-expanded')).toBe('false')
    expect(wrapper.findAll('[data-testid="child-chunk"]')).toHaveLength(2)
    expect(wrapper.get('.parent-chunk-group__children').attributes('style')).toContain('display: none')
  })
})
