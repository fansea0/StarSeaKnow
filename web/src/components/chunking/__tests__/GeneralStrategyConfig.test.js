import { mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { nextTick } from 'vue'
import GeneralStrategyConfig from '../GeneralStrategyConfig.vue'

const fields = [
  { key: 'delimiter', defaultValue: '\n' },
  { key: 'delimiterMode', defaultValue: 'LITERAL' },
  { key: 'maxCharacters', defaultValue: 500, min: 64, max: 4000 },
  { key: 'collapseWhitespace', defaultValue: true },
  { key: 'removeUrls', defaultValue: false },
  { key: 'removeEmails', defaultValue: false },
]

function mountConfig(props = {}) {
  return mount(GeneralStrategyConfig, {
    props: {
      configFields: fields,
      initialValues: {},
      initialContextConfig: { enabled: true, limit: 40, unit: 'CHARACTERS', mode: 'CHARACTER_TAIL' },
      defaultContextConfig: { enabled: true, limit: 40, unit: 'CHARACTERS', mode: 'CHARACTER_TAIL' },
      contextConfigFields: [
        { key: 'enabled', type: 'boolean', defaultValue: true },
        { key: 'limit', type: 'number', defaultValue: 40, min: 0, max: 1000 },
      ],
      ...props,
    },
    global: { plugins: [ElementPlus] },
  })
}

describe('GeneralStrategyConfig', () => {
  it('hydrates descriptor defaults, displays LF as an escape, and emits actual LF without server-owned fields', () => {
    const wrapper = mountConfig()
    expect(wrapper.get('[data-testid="general-delimiter"] input').element.value).toBe('\\n')
    expect(wrapper.emitted('config-change').at(-1)[0]).toEqual({
      delimiter: '\n', delimiterMode: 'LITERAL', maxCharacters: 500,
      collapseWhitespace: true, removeUrls: false, removeEmails: false,
    })
    expect(wrapper.emitted('context-change').at(-1)[0]).toEqual({ enabled: true, limit: 40 })
  })

  it('validates ranges, Unicode delimiter length, and the overlap budget', async () => {
    const wrapper = mountConfig()
    await wrapper.get('[data-testid="general-delimiter"] input').setValue('😀'.repeat(257))
    expect(wrapper.text()).toContain('256')
    expect(wrapper.emitted('validity-change').at(-1)[0]).toBe(false)

    await wrapper.get('[data-testid="general-delimiter"] input').setValue('\\n')
    await wrapper.get('[data-testid="general-max"] input').setValue('64')
    await wrapper.get('[data-testid="general-max"] input').trigger('change')
    await wrapper.get('[data-testid="general-overlap"] input').setValue('60')
    await wrapper.get('[data-testid="general-overlap"] input').trigger('change')
    expect(wrapper.text()).toContain('小于最大字符数')
  })

  it('normalizes a zero overlap to disabled and keeps server-owned unit and mode out of emitted context', () => {
    const wrapper = mountConfig({ initialContextConfig: { enabled: true, limit: 0, unit: 'CHARACTERS', mode: 'CHARACTER_TAIL' } })
    expect(wrapper.emitted('context-change').at(-1)[0]).toEqual({ enabled: false, limit: 0 })
  })

  it('shows a regex advisory separately from an authoritative server field error', async () => {
    const wrapper = mountConfig({ serverFieldErrors: { delimiter: '服务端：分隔符正则无效' } })
    await wrapper.get('[data-testid="delimiter-mode-regex"]').trigger('click')
    await wrapper.get('[data-testid="general-delimiter"] input').setValue('^')
    expect(wrapper.get('[data-testid="regex-advisory"]').text()).toContain('零宽')
    expect(wrapper.get('[data-testid="field-error-delimiter"]').text()).toContain('服务端：分隔符正则无效')
  })

  it('updates the 15% suggestion without mutating overlap until explicitly applied', async () => {
    const wrapper = mountConfig()
    await wrapper.get('[data-testid="general-max"] input').setValue('800')
    await wrapper.get('[data-testid="general-max"] input').trigger('change')
    await nextTick()
    expect(wrapper.get('[data-testid="recommended-overlap"]').text()).toContain('120')
    expect(wrapper.get('[data-testid="general-overlap"] input').element.value).toBe('40')
    await wrapper.get('[data-testid="apply-overlap-suggestion"]').trigger('click')
    expect(wrapper.get('[data-testid="general-overlap"] input').element.value).toBe('120')
  })

  it('gives the length budget rail a complete accessible text label', () => {
    const wrapper = mountConfig()
    expect(wrapper.get('[data-testid="length-budget-rail"]').attributes('aria-label')).toContain('最大 500 字符')
    expect(wrapper.get('[data-testid="length-budget-rail"]').attributes('aria-label')).toContain('建议 75 字符')
  })

  it('uses descriptor delimiter max while counting decoded Unicode code points', async () => {
    const customFields = fields.map(field => field.key === 'delimiter' ? { ...field, max: 1 } : field)
    const wrapper = mountConfig({ configFields: customFields })

    await wrapper.get('[data-testid="general-delimiter"] input').setValue('\\n')
    expect(wrapper.text()).not.toContain('分隔符不能超过 1 个')
    expect(wrapper.emitted('config-change').at(-1)[0].delimiter).toBe('\n')

    await wrapper.get('[data-testid="general-delimiter"] input').setValue('\\\\n')
    expect(wrapper.text()).toContain('分隔符不能超过 1 个 Unicode 字符')
    expect(wrapper.emitted('validity-change').at(-1)[0]).toBe(false)
  })

  it('keeps a trailing backslash stable through parent prop echo while typing an LF escape', async () => {
    const wrapper = mountConfig()
    const input = wrapper.get('[data-testid="general-delimiter"] input')

    await input.setValue('\\')
    const firstEmission = wrapper.emitted('config-change').at(-1)[0]
    expect(firstEmission.delimiter).toBe('\\')
    await wrapper.setProps({ initialValues: firstEmission })
    expect(input.element.value).toBe('\\')

    await input.setValue('\\n')
    expect(wrapper.emitted('config-change').at(-1)[0].delimiter).toBe('\n')

    await wrapper.setProps({ initialValues: { ...firstEmission, delimiter: '\t' } })
    expect(input.element.value).toBe('\\t')
  })

  it('uses descriptor context bounds and can restore the descriptor default', async () => {
    const wrapper = mountConfig({
      initialContextConfig: { enabled: true, limit: 33 },
      defaultContextConfig: { enabled: true, limit: 24 },
      contextConfigFields: [
        { key: 'enabled', type: 'boolean', defaultValue: true },
        { key: 'limit', type: 'number', defaultValue: 24, min: 0, max: 240 },
      ],
    })
    const input = wrapper.get('[data-testid="general-overlap"] input')

    expect(input.attributes('min')).toBe('0')
    expect(input.attributes('max')).toBe('240')
    expect(wrapper.text()).toContain('0–240 字符')
    await wrapper.get('[data-testid="reset-overlap-default"]').trigger('click')
    expect(input.element.value).toBe('24')
    expect(wrapper.emitted('context-change').at(-1)[0]).toEqual({ enabled: true, limit: 24 })
  })
})
