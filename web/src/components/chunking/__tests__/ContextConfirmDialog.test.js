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

  it('describes mixed overlap units without assuming every limit is tokens', async () => {
    const wrapper = mountDialog({ unitSummary: '字符 2 块 / Token 1 块' })
    await flushPromises()
    expect(document.body.querySelector('[data-testid="confirm-unit-summary"]').textContent).toContain('字符 2 块 / Token 1 块')
    wrapper.unmount()
  })
})
