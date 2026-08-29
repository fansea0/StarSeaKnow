import * as httpModule from './http'
import { describe, expect, it } from 'vitest'

describe('API host configuration', () => {
  it('uses the configured API base for legacy 8080 URLs', () => {
    expect(httpModule.apiBaseUrl).toBe('/api')
    expect(httpModule.normalizeApiRequestUrl('http://localhost:8080/knowledge/list/vo'))
      .toBe('/api/knowledge/list/vo')
  })

  it('builds non-Axios URLs through the same API base', () => {
    expect(httpModule.apiUrl('/file/uploadToKnow/9')).toBe('/api/file/uploadToKnow/9')
    expect(httpModule.normalizeApiRequestUrl('https://example.test/other')).toBe('https://example.test/other')
  })
})
