import { mergeStrategies } from '../strategyCatalog'

const backendMarkdown = [
  {
    code: 'GENERAL',
    scope: 'GLOBAL',
    available: true,
    configFields: [{ key: 'maxCharacters', defaultValue: 500 }],
    defaultContextConfig: { enabled: true, limit: 40 },
  },
  {
    code: 'MARKDOWN_OPTIMIZED',
    title: 'Markdown 优化分块',
    supportedFileTypes: ['md', 'markdown'],
    available: true,
  },
]

describe('mergeStrategies', () => {
  it('uses backend GENERAL and keeps only PARENT_CHILD as a local placeholder', () => {
    expect(mergeStrategies('md', backendMarkdown)).toEqual([
      expect.objectContaining({ code: 'GENERAL', title: '通用分块', disabled: false, configFields: expect.any(Array) }),
      expect.objectContaining({ code: 'PARENT_CHILD', disabled: true }),
      expect.objectContaining({ code: 'MARKDOWN_OPTIMIZED', disabled: false }),
    ])
  })

  it('keeps only disabled local placeholders when a file type has no backend strategy', () => {
    expect(mergeStrategies('pdf', [])).toEqual([expect.objectContaining({ code: 'PARENT_CHILD', disabled: true })])
  })

  it('uses an available backend parent-child descriptor instead of the local placeholder', () => {
    const strategies = mergeStrategies('md', [
      { code: 'PARENT_CHILD', available: true, configFields: [{ key: 'childMaxTokens', defaultValue: 256 }] },
    ])

    expect(strategies).toEqual([
      expect.objectContaining({ code: 'PARENT_CHILD', disabled: false, title: '父子分块' }),
    ])
  })

  it('normalizes requested and supported file types before matching', () => {
    const strategies = mergeStrategies('.MD', [
      { code: 'MARKDOWN_OPTIMIZED', supportedFileTypes: ['.MD', '.mArKdOwN'], available: true },
    ])

    expect(strategies[1]).toMatchObject({
      code: 'MARKDOWN_OPTIMIZED',
      title: 'MD 自适应分块',
      description: expect.any(String),
      disabled: false,
    })
  })

  it('filters reserved codes and deduplicates normalized backend codes without mutating input', () => {
    const backend = [
      { code: ' general ', supportedFileTypes: [], available: false, reason: '解析器不可用' },
      { code: ' markdown_optimized ', supportedFileTypes: [], available: true, marker: 'first' },
      { code: 'MARKDOWN_OPTIMIZED', supportedFileTypes: ['md'], available: true, marker: 'second' },
      { code: 'FUTURE_MODE', supportedFileTypes: [], available: false, reason: '租户未启用' },
    ]
    const original = structuredClone(backend)

    const strategies = mergeStrategies('.markdown', backend)

    expect(strategies.map(strategy => strategy.code)).toEqual([
      'GENERAL',
      'PARENT_CHILD',
      'MARKDOWN_OPTIMIZED',
      'FUTURE_MODE',
    ])
    expect(strategies[0]).toMatchObject({ reason: '解析器不可用', disabled: true })
    expect(strategies[2]).toMatchObject({ marker: 'first', title: 'MD 自适应分块' })
    expect(strategies[3]).toMatchObject({ title: 'FUTURE MODE', description: expect.any(String), disabled: true, reason: '租户未启用' })
    expect(backend).toEqual(original)
  })

  it('returns placeholder clones so consumer mutation cannot change the global disabled catalog', () => {
    const first = mergeStrategies('pdf', [])
    first[0].disabled = false

    expect(mergeStrategies('pdf', [])[0]).toMatchObject({ code: 'PARENT_CHILD', disabled: true })
  })

  it('does not let a file extension override backend capability', () => {
    expect(mergeStrategies('pdf', [{ code: 'GENERAL', available: true, supportedFileTypes: ['txt'] }])[0])
      .toMatchObject({ code: 'GENERAL', disabled: false })
  })
})
