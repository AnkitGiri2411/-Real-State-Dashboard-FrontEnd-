import { describe, expect, it } from 'vitest'
import { IMPLEMENTED_PATHS, isImplemented } from './routes'

/**
 * The route table, which is what keeps the sidebar honest.
 *
 * The navigation is served by the backend, so the failure this test guards against is a menu entry
 * pointing at a screen that does not exist - a dead link the reader only discovers by clicking it.
 * `AppShell` filters the sidebar through `IMPLEMENTED_PATHS`, so the list being correct and unique
 * is the whole guarantee.
 */
describe('IMPLEMENTED_PATHS', () => {
  it('contains only absolute paths', () => {
    for (const path of IMPLEMENTED_PATHS) {
      expect(path.startsWith('/'), `${path} is not absolute`).toBe(true)
    }
  })

  it('has no duplicates', () => {
    expect(new Set(IMPLEMENTED_PATHS).size).toBe(IMPLEMENTED_PATHS.length)
  })

  it('uses no trailing slash, so two spellings cannot both match', () => {
    for (const path of IMPLEMENTED_PATHS) {
      expect(path.endsWith('/'), `${path} has a trailing slash`).toBe(false)
    }
  })

  it('uses lower-case kebab-case segments, which is what the backend serves', () => {
    for (const path of IMPLEMENTED_PATHS) {
      expect(path, path).toMatch(/^\/[a-z0-9-]+$/)
    }
  })

  it('covers every register the product actually ships', () => {
    for (const required of [
      '/dashboard', '/properties', '/units', '/owners', '/portfolios',
      '/tenants', '/buyers', '/agents', '/offices',
      '/listings', '/viewings', '/applications', '/leases', '/payments',
      '/leads', '/offers', '/sales',
      '/maintenance', '/expenses', '/documents',
      '/communication', '/notifications',
      '/reports', '/audit-logs', '/imports', '/settings', '/profile',
    ]) {
      expect(isImplemented(required), `${required} is missing`).toBe(true)
    }
  })
})

describe('isImplemented', () => {
  it('accepts a served route', () => {
    expect(isImplemented('/dashboard')).toBe(true)
  })

  it('rejects a route with no screen, so a nav entry pointing at it is hidden', () => {
    expect(isImplemented('/reservations')).toBe(false)
    expect(isImplemented('/guests')).toBe(false)
    expect(isImplemented('/')).toBe(false)
  })

  it('does not match a prefix of a served route', () => {
    // '/properties/123' is a detail route, not a nav destination; a prefix match here would make
    // the sidebar treat a record address as a register.
    expect(isImplemented('/properties/123')).toBe(false)
  })
})
