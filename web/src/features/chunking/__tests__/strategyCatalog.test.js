import { mergeStrategies } from '../strategyCatalog'

const backendMarkdown = [
  {
    code: 'MARKDOWN_OPTIMIZED',
    title: 'Markdown 优化分块',
    supportedFileTypes: ['md', 'markdown'],
  },
]

describe('mergeStrategies', () => {
  it('keeps only the general local placeholder and appends compatible backend strategies', () => {
    expect(mergeStrategies('md', backendMarkdown)).toEqual([
      expect.objectContaining({ code: 'GENERAL', disabled: true }),
      expect.objectContaining({ code: 'MARKDOWN_OPTIMIZED', disabled: false }),
    ])
  })

  it('keeps only disabled local placeholders when a file type has no backend strategy', () => {
    expect(mergeStrategies('pdf', [])).toHaveLength(1)
  })

  it('normalizes requested and supported file types before matching', () => {
    const strategies = mergeStrategies('.MD', [
      { code: 'MARKDOWN_OPTIMIZED', supportedFileTypes: ['.MD', '.mArKdOwN'] },
    ])

    expect(strategies[1]).toMatchObject({
      code: 'MARKDOWN_OPTIMIZED',
      title: 'MD 自适应分块',
      description: expect.any(String),
      disabled: false,
    })
  })

  it('opens, presents, and deduplicates backend PARENT_CHILD descriptors without mutating input', () => {
    const backend = [
      { code: ' general ', supportedFileTypes: ['md'] },
      { code: ' parent_child ', supportedFileTypes: ['md', 'markdown'], marker: 'parent-first' },
      { code: 'PARENT_CHILD', supportedFileTypes: ['md', 'markdown'], marker: 'parent-second' },
      { code: ' markdown_optimized ', supportedFileTypes: ['.MD', '.markdown'], marker: 'first' },
      { code: 'MARKDOWN_OPTIMIZED', supportedFileTypes: ['md'], marker: 'second' },
      { code: 'FUTURE_MODE', supportedFileTypes: ['markdown'] },
    ]
    const original = structuredClone(backend)

    const strategies = mergeStrategies('.markdown', backend)

    expect(strategies.map(strategy => strategy.code)).toEqual([
      'GENERAL',
      'PARENT_CHILD',
      'MARKDOWN_OPTIMIZED',
      'FUTURE_MODE',
    ])
    expect(strategies[1]).toMatchObject({
      marker: 'parent-first',
      title: '父子分块',
      description: '子块精准召回，父块提供完整回答上下文。',
      disabled: false,
    })
    expect(strategies[2]).toMatchObject({ marker: 'first', title: 'MD 自适应分块' })
    expect(strategies[3]).toMatchObject({ title: 'FUTURE MODE', description: expect.any(String), disabled: false })
    expect(backend).toEqual(original)
  })

  it('returns placeholder clones so consumer mutation cannot change the global disabled catalog', () => {
    const first = mergeStrategies('pdf', [])
    first[0].disabled = false

    expect(mergeStrategies('pdf', [])[0]).toMatchObject({ code: 'GENERAL', disabled: true })
  })
})
