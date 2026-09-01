import { mergeStrategies } from '../strategyCatalog'

const backendMarkdown = [
  {
    code: 'MARKDOWN_OPTIMIZED',
    title: 'Markdown 优化分块',
    supportedFileTypes: ['md', 'markdown'],
  },
]

describe('mergeStrategies', () => {
  it('keeps local placeholders first and appends compatible backend strategies', () => {
    expect(mergeStrategies('md', backendMarkdown)).toEqual([
      expect.objectContaining({ code: 'GENERAL', disabled: true }),
      expect.objectContaining({ code: 'PARENT_CHILD', disabled: true }),
      expect.objectContaining({ code: 'MARKDOWN_OPTIMIZED', disabled: false }),
    ])
  })

  it('keeps only disabled local placeholders when a file type has no backend strategy', () => {
    expect(mergeStrategies('pdf', [])).toHaveLength(2)
  })
})
