import { mount } from '@vue/test-utils'
import ChunkPreviewSummary from '../ChunkPreviewSummary.vue'

describe('ChunkPreviewSummary', () => {
  it('renders persisted zero counters and delimiter fallback state', () => {
    const wrapper = mount(ChunkPreviewSummary, {
      props: {
        summary: {
          preprocessingSummary: {
            whitespaceMatches: 0, whitespaceCharactersRemoved: 0,
            urlMatches: 0, urlCharactersReplaced: 0,
            emailMatches: 0, emailCharactersReplaced: 0,
            controlCharactersRemoved: 0, emptySegmentsRemoved: 0,
          },
          delimiterMatched: false,
          forcedSplitCount: 0,
          tokenLimitedSplitCount: 0,
        },
      },
    })
    expect(wrapper.text()).toContain('未匹配，已按长度回退')
    expect(wrapper.text()).toContain('强制切分 0')
    expect(wrapper.text()).toContain('Token 限制切分 0')
    expect(wrapper.text()).toContain('URL')
    expect(wrapper.text()).toContain('0 处 / 替换 0 字符')
  })

  it('renders nothing for a legacy response without persisted summary fields', () => {
    expect(mount(ChunkPreviewSummary, { props: { summary: null } }).html()).toBe('<!--v-if-->')
  })
})
