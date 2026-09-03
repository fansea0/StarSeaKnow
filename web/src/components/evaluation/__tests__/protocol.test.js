import { mount } from '@vue/test-utils'
import { expect, it } from 'vitest'
import AcceptanceProtocol from '../AcceptanceProtocol.vue'
it('records critical questions and optional hard-negative gates without manufacturing defaults', async () => {
  let wrapper
  wrapper = mount(AcceptanceProtocol, {
    props: {
      modelValue: {},
      questions: [{ id: 'q', query: '关键业务问题', split: 'ACCEPTANCE', answerable: true }],
      'onUpdate:modelValue': (value) => wrapper.setProps({ modelValue: value }),
    },
  })
  expect(wrapper.props('modelValue')).toEqual({})
  await wrapper.get('[name="minHardNegativeQuestions"]').setValue(5)
  await wrapper.get('[data-testid="critical-q"]').setValue(true)
  expect(wrapper.props('modelValue')).toEqual({ minHardNegativeQuestions: 5, criticalQuestionIds: ['q'] })
  wrapper.unmount()
})
