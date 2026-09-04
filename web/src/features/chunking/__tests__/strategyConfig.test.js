import { defaultConfigFor, normalizePolicySnapshot } from '../strategyConfig'

const parentDescriptor = {
  code: 'PARENT_CHILD',
  configFields: [
    { key: 'parentMode', defaultValue: 'PARAGRAPH' },
    { key: 'parentMaxTokens', defaultValue: 1024, min: 128, max: 4096 },
    { key: 'childMaxTokens', defaultValue: 256, min: 32, max: 512 },
    { key: 'childOverlapTokens', defaultValue: 32, min: 0, max: 128 },
  ],
}

const markdownDescriptor = {
  code: 'MARKDOWN_OPTIMIZED',
  configFields: [
    { key: 'minTokens', defaultValue: 100 },
    { key: 'targetTokens', defaultValue: 400 },
    { key: 'maxTokens', defaultValue: 512 },
  ],
}

describe('strategy config', () => {
  it('returns independent backend-aligned parent-child defaults', () => {
    const first = defaultConfigFor(parentDescriptor)
    first.parentMaxTokens = 2048

    expect(defaultConfigFor(parentDescriptor)).toEqual({
      parentMode: 'PARAGRAPH',
      parentMaxTokens: 1024,
      childMaxTokens: 256,
      childOverlapTokens: 32,
    })
  })

  it.each([
    [{ ...defaultConfigFor(parentDescriptor), parentMode: 'SECTION' }, '未知模式'],
    [{ ...defaultConfigFor(parentDescriptor), parentMaxTokens: 127 }, '父块下限'],
    [{ ...defaultConfigFor(parentDescriptor), parentMaxTokens: 4097 }, '父块上限'],
    [{ ...defaultConfigFor(parentDescriptor), childMaxTokens: 31 }, '子块下限'],
    [{ ...defaultConfigFor(parentDescriptor), childMaxTokens: 513 }, '子块上限'],
    [{ ...defaultConfigFor(parentDescriptor), childOverlapTokens: -1 }, '重叠下限'],
    [{ ...defaultConfigFor(parentDescriptor), childOverlapTokens: 129 }, '重叠上限'],
    [{ ...defaultConfigFor(parentDescriptor), childOverlapTokens: 256 }, '重叠必须小于子块'],
    [{ ...defaultConfigFor(parentDescriptor), parentMaxTokens: 128, childMaxTokens: 256 }, '段落父块不得小于子块'],
  ])('falls back to descriptor defaults for invalid %s snapshots', (snapshot) => {
    expect(normalizePolicySnapshot('PARENT_CHILD', snapshot, parentDescriptor)).toEqual(defaultConfigFor(parentDescriptor))
  })

  it('keeps a valid matching parent-child snapshot and rejects it for another strategy', () => {
    const snapshot = { parentMode: 'FULL_DOCUMENT', parentMaxTokens: 2048, childMaxTokens: 384, childOverlapTokens: 64 }

    expect(normalizePolicySnapshot('PARENT_CHILD', snapshot, parentDescriptor)).toEqual(snapshot)
    expect(normalizePolicySnapshot('MARKDOWN_OPTIMIZED', snapshot, markdownDescriptor)).toEqual(defaultConfigFor(markdownDescriptor))
  })
})
