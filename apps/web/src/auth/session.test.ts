import { beforeEach, describe, expect, it, vi } from 'vitest'
import {
  clearTokens,
  isAccessTokenExpiringSoon,
  readAccessToken,
  readAccessTokenExpiresAt,
  readRefreshToken,
  writeTokens,
} from './session'

/**
 * The session's expiry, which is what stops a page load producing a refused request.
 *
 * The behaviour these tests pin is the whole reason the expiry is stored at all. Before it, a reader
 * returning to an open tab sent a request the server had to refuse, the client refreshed and
 * recovered - but the browser had already logged a 401, and a console full of errors the reader
 * cannot act on is a defect however well the application recovers.
 */
describe('session token storage', () => {
  beforeEach(() => {
    clearTokens()
  })

  it('stores both tokens and the moment the access token lapses', () => {
    writeTokens('access-1', 'refresh-1', 900)
    expect(readAccessToken()).toBe('access-1')
    expect(readRefreshToken()).toBe('refresh-1')

    const expiresAt = readAccessTokenExpiresAt()
    expect(expiresAt).not.toBeNull()
    // 900 seconds from now, within a second of tolerance for the test's own runtime.
    expect(expiresAt as number).toBeGreaterThan(Date.now() + 890_000)
    expect(expiresAt as number).toBeLessThanOrEqual(Date.now() + 900_000)
  })

  it('clears everything, so no stale token survives a sign-out', () => {
    writeTokens('access-1', 'refresh-1', 900)
    clearTokens()
    expect(readAccessToken()).toBeNull()
    expect(readRefreshToken()).toBeNull()
    expect(readAccessTokenExpiresAt()).toBeNull()
  })

  it('stores no expiry when the backend did not supply a lifetime', () => {
    // Degrades to refreshing on a 401 rather than assuming a token lives forever.
    writeTokens('access-1', 'refresh-1')
    expect(readAccessTokenExpiresAt()).toBeNull()
  })

  it('ignores a non-positive or non-finite lifetime', () => {
    writeTokens('access-1', 'refresh-1', 0)
    expect(readAccessTokenExpiresAt()).toBeNull()
    writeTokens('access-2', 'refresh-2', Number.NaN)
    expect(readAccessTokenExpiresAt()).toBeNull()
  })
})

describe('isAccessTokenExpiringSoon', () => {
  beforeEach(() => {
    clearTokens()
  })

  it('is false when no token is held', () => {
    expect(isAccessTokenExpiringSoon()).toBe(false)
  })

  it('is false for a token with plenty of life left', () => {
    writeTokens('access-1', 'refresh-1', 900)
    expect(isAccessTokenExpiringSoon()).toBe(false)
  })

  it('is true once the token has lapsed', () => {
    writeTokens('access-1', 'refresh-1', 900)
    // Wind the clock past the expiry rather than waiting fifteen minutes for it.
    vi.spyOn(Date, 'now').mockReturnValue(Date.now() + 1_000_000)
    expect(isAccessTokenExpiringSoon()).toBe(true)
    vi.restoreAllMocks()
  })

  it('is true inside the renewal window, before the token has actually lapsed', () => {
    writeTokens('access-1', 'refresh-1', 900)
    // 30 seconds short of expiry: still technically valid, but a request sent now could arrive late.
    vi.spyOn(Date, 'now').mockReturnValue(Date.now() + 900_000 - 30_000)
    expect(isAccessTokenExpiringSoon()).toBe(true)
    vi.restoreAllMocks()
  })

  it('is false just outside the renewal window', () => {
    writeTokens('access-1', 'refresh-1', 900)
    vi.spyOn(Date, 'now').mockReturnValue(Date.now() + 900_000 - 120_000)
    expect(isAccessTokenExpiringSoon()).toBe(false)
    vi.restoreAllMocks()
  })

  it('is false when the expiry is unknown, so an older session still works', () => {
    // A session written before the expiry was stored must not be treated as stale; the client falls
    // back to refreshing on a 401, which is correct, just noisier.
    writeTokens('access-1', 'refresh-1')
    expect(isAccessTokenExpiringSoon()).toBe(false)
  })
})
