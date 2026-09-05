import { mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { nextTick } from 'vue'
import ParentChildStrategyConfig from '../ParentChildStrategyConfig.vue'

const defaults = {
  parentMode: 'PARAGRAPH',
  parentMaxTokens: 1024,
  childMaxTokens: 256,
  childOverlapTokens: 32,
}

function mountConfig(props = {}) {
  return mount(ParentChildStrategyConfig, {
    props: { initialValues: defaults, ...props },
    global: { plugins: [ElementPlus] },
  })
}

describe('ParentChildStrategyConfig', () => {
  it('emits a complete valid config and warns for full-document mode', async () => {
    const wrapper = mountConfig()

    await wrapper.get('[data-testid="parent-mode-full-document"]').trigger('click')
    await nextTick()

    expect(wrapper.text()).toContain('整篇文档作为回答上下文')
    expect(wrapper.find('[data-testid="parent-max-tokens"]').exists()).toBe(false)
    expect(wrapper.emitted('config-change')?.at(-1)?.[0]).toEqual({ ...defaults, parentMode: 'FULL_DOCUMENT' })
    expect(wrapper.emitted('validity-change')?.at(-1)?.[0]).toBe(true)
  })

  it('marks overlap equal to child size invalid while publishing the complete current config', async () => {
    const wrapper = mountConfig()
    const child = wrapper.get('[data-testid="child-max-tokens"] input')
    await child.setValue('128')
    await child.trigger('change')
    await nextTick()
    const initialConfigEmissions = wrapper.emitted('config-change').length
    const overlap = wrapper.get('[data-testid="child-overlap-tokens"] input')

    await overlap.setValue('128')
    await overlap.trigger('change')
    await nextTick()

    expect(wrapper.get('[role="alert"]').text()).toContain('小于子块最大 Token')
    expect(wrapper.emitted('validity-change')?.at(-1)?.[0]).toBe(false)
    expect(wrapper.emitted('config-change')).toHaveLength(initialConfigEmissions + 1)
    expect(wrapper.emitted('config-change')?.at(-1)?.[0]).toEqual({ ...defaults, childMaxTokens: 128, childOverlapTokens: 128 })
  })
})
