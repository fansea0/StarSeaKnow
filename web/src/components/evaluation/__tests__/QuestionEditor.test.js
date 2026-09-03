import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import QuestionEditor from '../QuestionEditor.vue'

const question = {
  id: 'q',
  query: '如何重置？',
  intentGroup: 'reset',
  category: '',
  split: 'CALIBRATION',
  answerable: true,
  reviewed: false,
  labels: {},
  hardNegativeIds: [],
}
function editor() {
  return mount(QuestionEditor, {
    props: {
      modelValue: structuredClone(question),
      chunks: [
        {
          id: 'c',
          content: '需要联系管理员',
          indexContent: '手册\n需要联系管理员',
          contentHash: 'hash',
          fileName: 'manual.md',
        },
      ],
      'onUpdate:modelValue': (value) => wrapper.setProps({ modelValue: value }),
    },
  })
}
let wrapper
it('lets reviewers explicitly distinguish unknown, zero, and a hard negative then clear a label', async () => {
  wrapper = editor()
  expect(wrapper.get('[data-testid="label-c"]').element.value).toBe('')
  await wrapper.get('[data-testid="label-c"]').setValue('0')
  await wrapper.get('[data-testid="negative-c"]').setValue(true)
  expect(wrapper.props('modelValue').labels).toEqual({ c: 0 })
  expect(wrapper.props('modelValue').hardNegativeIds).toEqual(['c'])
  await wrapper.get('[data-testid="label-c"]').setValue('')
  expect(wrapper.props('modelValue').labels).toEqual({})
  expect(wrapper.props('modelValue').hardNegativeIds).toEqual([])
  expect(wrapper.text()).toContain('未标注')
  wrapper.unmount()
})
