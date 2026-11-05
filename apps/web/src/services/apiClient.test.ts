import { afterEach, describe, expect, it, vi } from 'vitest'

/**
 * How a request URL is composed, and the reason the second argument to `URL` matters.
 *
 * The application talks to the API through one base URL. In development that base is an absolute
 * address on its own port; in a deployment that serves the bundle and the API from one origin it is
 * a relative `/api/v1`. The relative form is the one that breaks quietly: `new URL('/api/v1/x')` on
 * its own throws `Invalid URL`, so every request in the product would fail before leaving the
 * browser - sign-in included - while the build itself reported success and the page rendered
 * perfectly. These tests pin the resolution rule so that failure cannot come back.
 */
describe('buildUrl', () => {
  afterEach(() => {
    vi.unstubAllEnvs()
    vi.resetModules()
  })

  /**
   * Load the module fresh under a declared base.
   *
   * The base is read once, when the module is evaluated, so it cannot be changed on an already
   * imported module - which is also why the application reads it at the top level rather than per
   * request.
   */
  async function withBase(base: string) {
    vi.resetModules()
    vi.stubEnv('VITE_API_BASE_URL', base)
    return await import('./apiClient')
  }

  it('resolves a relative base against the document origin', async () => {
    // The single-origin deployment: the API is a sibling of the page, on whatever host serves it.
    const { buildUrl } = await withBase('/api/v1')

    expect(buildUrl('/properties')).toBe('http://localhost:3000/api/v1/properties')
  })

  it('resolves a relative base for a parameterised detail route', async () => {
    const { buildUrl } = await withBase('/api/v1')

    expect(buildUrl('/properties/3f1c8a2e-9b44-4c71-8d10-2a5f6e7b8c90')).toBe(
      'http://localhost:3000/api/v1/properties/3f1c8a2e-9b44-4c71-8d10-2a5f6e7b8c90',
    )
  })

  it('honours an absolute base, which is how local development runs', async () => {
    // The development arrangement: the API is on its own port, and CORS covers the difference.
    const { buildUrl } = await withBase('http://localhost:8083/api/v1')

    expect(buildUrl('/leases')).toBe('http://localhost:8083/api/v1/leases')
  })

  it('omits empty, null and undefined query values rather than sending blanks', async () => {
    const { buildUrl } = await withBase('/api/v1')

    const url = buildUrl('/properties', {
      page: 0,
      size: 20,
      status: 'LISTED',
      city: '',
      locality: null,
      search: undefined,
    })

    expect(url).toBe('http://localhost:3000/api/v1/properties?page=0&size=20&status=LISTED')
  })

  it('appends no bare question mark when there is nothing to filter by', async () => {
    const { buildUrl } = await withBase('/api/v1')

    expect(buildUrl('/dashboard/summary', {})).toBe('http://localhost:3000/api/v1/dashboard/summary')
    // A boolean false is a value, not an absence, so it is kept.
    expect(buildUrl('/units', { unlet: false })).toBe(
      'http://localhost:3000/api/v1/units?unlet=false',
    )
  })
})
