import { expect, it } from 'vitest'
import { safeMarkdown } from '../safeMarkdown'
it('renders markdown formatting without executing raw HTML or dangerous links', () => {
  const div = document.createElement('div')
  div.innerHTML = safeMarkdown('**答案**\n\n<img src=x onerror=alert(1)>\n\n[恶意](javascript:alert)\n\n[资料](https://example.com/a)')
  expect(div.querySelector('strong')?.textContent).toBe('答案')
  expect(div.querySelector('img')).toBeNull()
  expect(div.querySelectorAll('a')).toHaveLength(1)
  expect(div.querySelector('a').getAttribute('href')).toBe('https://example.com/a')
})
