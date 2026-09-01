import { mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { nextTick } from 'vue'
import MarkdownStrategyConfig from '../MarkdownStrategyConfig.vue'

function mountConfig() {
  return mount(MarkdownStrategyConfig, {
    global: { plugins: [ElementPlus] },
  })
}

async function setNumber(wrapper, testId, value) {
  const input = wrapper.get(`[data-testid="${testId}"] input`)
  await input.setValue(String(value))
  await input.trigger('change')
  await nextTick()
}

describe('MarkdownStrategyConfig', () => {
  it('starts with the 100/400/512 token budget and publishes that valid config', () => {
    const wrapper = mountConfig()

    expect(wrapper.get('[data-testid="min-tokens"] input').element.value).toBe('100')
    expect(wrapper.get('[data-testid="target-tokens"] input').element.value).toBe('400')
    expect(wrapper.get('[data-testid="max-tokens"] input').element.value).toBe('512')
    expect(wrapper.text()).toContain('最小 ≤ 推荐 ≤ 最大 ≤ 512')
    expect(wrapper.emitted('config-change')?.at(-1)?.[0]).toEqual({
      minTokens: 100,
      targetTokens: 400,
      maxTokens: 512,
    })
  })

  it('reports an inline error for 400/100/512 and does not publish the invalid config', async () => {
    const wrapper = mountConfig()

    await setNumber(wrapper, 'min-tokens', 400)
    const validEmissionCount = wrapper.emitted('config-change').length
    await setNumber(wrapper, 'target-tokens', 100)

    expect(wrapper.get('[role="alert"]').text()).toContain('最小 Token 不能大于推荐 Token')
    expect(wrapper.emitted('validity-change')?.at(-1)?.[0]).toBe(false)
    expect(wrapper.emitted('config-change')).toHaveLength(validEmissionCount)
  })

  it('reports an inline error for a maximum above 512 and disables preview through validity', async () => {
    const wrapper = mountConfig()
    const validEmissionCount = wrapper.emitted('config-change').length

    await setNumber(wrapper, 'max-tokens', 513)

    expect(wrapper.get('[role="alert"]').text()).toContain('最大 Token 不能超过 512')
    expect(wrapper.emitted('validity-change')?.at(-1)?.[0]).toBe(false)
    expect(wrapper.emitted('config-change')).toHaveLength(validEmissionCount)
  })

  it('does not place the text Overlap inside the Markdown strategy config', () => {
    expect(mountConfig().text()).not.toContain('Overlap')
  })
})
