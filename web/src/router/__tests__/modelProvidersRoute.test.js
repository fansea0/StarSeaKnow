import { describe, expect, it } from 'vitest'
import { routes } from '../index'

describe('model provider route', () => {
  it('is available only to tenant administrators', () => {
    const route = routes.find((item) => item.path === '/models')

    expect(route).toBeDefined()
    expect(route.name).toBe('ModelProviders')
    expect(route.meta).toEqual({ requiresAdmin: true })
  })
})
