import { afterEach } from 'vitest'

class ResizeObserver {
  observe() {}
  unobserve() {}
  disconnect() {}
}

globalThis.ResizeObserver = ResizeObserver

afterEach(() => {
  document.body.innerHTML = ''
})
