import { countUnicodeCodePoints, decodeDelimiter, encodeDelimiter } from '../delimiterCodec'

describe('delimiterCodec', () => {
  it.each([
    ['\n', '\\n'],
    ['\r', '\\r'],
    ['\t', '\\t'],
    ['\\', '\\\\'],
    ['\\n', '\\\\n'],
  ])('round trips %j through visible escape notation', (actual, visible) => {
    expect(encodeDelimiter(actual)).toBe(visible)
    expect(decodeDelimiter(visible)).toBe(actual)
  })

  it('preserves unknown and trailing escapes literally', () => {
    expect(decodeDelimiter('\\q\\')).toBe('\\q\\')
  })

  it('counts Unicode code points instead of UTF-16 code units', () => {
    expect(countUnicodeCodePoints('中😀a')).toBe(3)
  })
})
