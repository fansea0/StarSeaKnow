import { mount, flushPromises } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import ContextConfirmDialog from '../ContextConfirmDialog.vue'

function mountDialog(props = {}) {
  return mount(ContextConfirmDialog, {
    attachTo: document.body,
    props: {
      modelValue: true,
      totalCount: 3,
      enabledCount: 2,
      generatedCount: 1,
      ...props,
    },
    global: { plugins: [ElementPlus] },
  })
}

describe('ContextConfirmDialog', () => {
  it('summarizes per-chunk overlap coverage without rendering global overlap controls', async () => {
    const wrapper = mountDialog()
    await flushPromises()

    const dialog = document.body.querySelector('[role="dialog"]')
    expect(dialog.querySelector('[data-testid="confirm-total-count"]').textContent).toContain('3')
    expect(dialog.querySelector('[data-testid="confirm-enabled-count"]').textContent).toContain('2')
    expect(dialog.querySelector('[data-testid="confirm-generated-count"]').textContent).toContain('1')
    expect(dialog.querySelector('[data-testid="overlap-switch"]')).toBeNull()
    expect(dialog.querySelector('[data-testid="overlap-tokens"]')).toBeNull()

    dialog.querySelector('[data-testid="confirm-vectorization"]').click()
    await flushPromises()
    expect(wrapper.emitted('confirm')?.[0]).toEqual([])
    wrapper.unmount()
  })

  it('explains parent-child vectorization using retrieval child counts instead of overlap summary', async () => {
    const wrapper = mountDialog({ totalCount: 3, parentCount: 2, childCount: 3, hierarchical: true })
    await flushPromises()
    const dialog = document.body.querySelector('[role="dialog"]')

    expect(dialog.textContent).toContain('2 父块 · 3 子块')
    expect(dialog.textContent).toContain('仅对子块建立向量，命中后使用父块回答')
    expect(dialog.querySelector('[data-testid="confirm-enabled-count"]')).toBeNull()
    wrapper.unmount()
  })
})
