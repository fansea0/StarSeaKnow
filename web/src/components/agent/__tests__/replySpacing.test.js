import { afterEach, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import { safeMarkdown } from '../safeMarkdown'
const workbenchStyles = readFileSync('src/components/agent/workbench.css', 'utf8')

let style, bubble
afterEach(() => { style?.remove(); bubble?.remove() })

it('collapses generated HTML whitespace in replies without losing code or plain-message line breaks', () => {
  style = document.createElement('style')
  style.textContent = workbenchStyles
  document.head.append(style)
  bubble = document.createElement('div')
  bubble.className = 'aw-bubble aw-markdown'
  bubble.innerHTML = safeMarkdown('第一段\n第二行\n\n第二段\n\n- 条目一\n- 条目二\n\n```text\n  保留缩进\n下一行\n```')
  document.body.append(bubble)

  // Later bubble rules must not re-enable whitespace from generated HTML.
  expect(getComputedStyle(bubble).whiteSpace).toBe('normal')
  expect(Number(getComputedStyle(bubble).lineHeight)).toBeLessThanOrEqual(1.65)
  expect(bubble.querySelector('br')).not.toBeNull()
  expect(bubble.querySelector('code').textContent).toBe('  保留缩进\n下一行\n')
  expect(getComputedStyle(bubble.querySelector('pre')).whiteSpace).toBe('pre')
  expect(parseFloat(getComputedStyle(bubble.querySelectorAll('p')[1]).marginTop)).toBeLessThanOrEqual(8)
  expect(parseFloat(getComputedStyle(bubble.querySelector('ul')).marginTop)).toBeLessThanOrEqual(8)

  bubble.className = 'aw-bubble'
  expect(getComputedStyle(bubble).whiteSpace).toBe('pre-wrap')
})
