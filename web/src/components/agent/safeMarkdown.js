import { Marked } from 'marked'

const escape = value => String(value ?? '').replace(/[&<>"']/g, char => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[char]))
const markdown = new Marked({ breaks: true, renderer: {
  html({ text }) { return escape(text) },
  image({ text }) { return escape(text) },
  link({ href, tokens }) {
    const text = this.parser.parseInline(tokens)
    // No script/data URLs, protocol-relative URLs, raw HTML or remote image requests from model output.
    if (!/^(https?:\/\/|mailto:|\/(?!\/)|#)/i.test(href) || /[\u0000-\u0020]/.test(href)) return text
    return `<a href="${escape(href)}" target="_blank" rel="noopener noreferrer">${text}</a>`
  },
} })
export function safeMarkdown(content) { return markdown.parse(String(content || '')) }
