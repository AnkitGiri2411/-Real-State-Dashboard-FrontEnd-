import { afterEach, describe, expect, it, vi } from 'vitest'
import { collectParams, dataSource, isLocalDataSource } from './index'

/**
 * The switch, and the parameter handling behind it.
 *
 * The default matters more than it looks: `local` is what lets a Vercel build work with no
 * environment variables at all. If the default ever moved into `.env`, the deployment would silently
 * become a bundle reaching for `localhost:8083` and every screen would fail at once.
 */
describe('the data source switch', () => {
  afterEach(() => {
    vi.unstubAllEnvs()
  })

  it('defaults to the bundled dataset when nothing is configured', () => {
    vi.stubEnv('VITE_DATA_SOURCE', undefined)
    expect(dataSource()).toBe('local')
    expect(isLocalDataSource()).toBe(true)
  })

  it('treats an unrecognised value as the bundled dataset, not as a backend', () => {
    vi.stubEnv('VITE_DATA_SOURCE', 'something-else')
    expect(isLocalDataSource()).toBe(true)
  })

  it('only leaves for a server when it is explicitly asked to', () => {
    vi.stubEnv('VITE_DATA_SOURCE', 'api')
    expect(dataSource()).toBe('api')
    expect(isLocalDataSource()).toBe(false)
  })
})

describe('parameter collection', () => {
  it('takes the query string a service built into the path', () => {
    // `unitApi.board` sends the parameter inside the path rather than through `query`.
    const { path, params } = collectParams('/units/board?propertyId=abc')
    expect(path).toBe('/units/board')
    expect(params).toEqual({ propertyId: 'abc' })
  })

  it('merges the query object on top of the path, as buildUrl would produce', () => {
    const { params } = collectParams('/units?propertyId=abc', { page: 2, status: 'OCCUPIED' })
    expect(params).toEqual({ propertyId: 'abc', page: '2', status: 'OCCUPIED' })
  })

  it('drops blank values, because an absent filter means "match anything"', () => {
    const { params } = collectParams('/units', { page: 0, status: '', city: null, floor: undefined })
    // `page: 0` is kept on purpose - the first page is a real page, not a blank.
    expect(params).toEqual({ page: '0' })
  })

  it('stringifies a boolean rather than dropping it', () => {
    // A `false` filter is meaningful ("not leasable"), and `buildUrl` keeps it too.
    expect(collectParams('/units', { leasable: false }).params).toEqual({ leasable: 'false' })
  })
})
