/**
 * The session's tokens, their expiry, and the hook a screen registers to be told the session ended.
 *
 * Kept deliberately framework-free and dependency-free. The API client needs to read a token and, on
 * a failed refresh, end the session - but the client must not import the React store, or the store's
 * import of the client's own service would close a cycle. Everything meets here instead:
 *
 *   session  <-  apiClient  <-  authService  <-  authStore  <-  screens
 *
 * Nothing in this file imports anything from the application, which is what keeps that acyclic.
 *
 * The **expiry** is stored alongside the tokens, and that is what makes the difference between a
 * clean page load and a logged 401. Without it the client cannot know a token is stale until the
 * server refuses it, so every reload with an aged token produced a refused request and a console
 * error the reader could do nothing about.
 */

const ACCESS_TOKEN_KEY = 'meridian.accessToken'
const REFRESH_TOKEN_KEY = 'meridian.refreshToken'
const EXPIRES_AT_KEY = 'meridian.accessTokenExpiresAt'

/**
 * How early to renew.
 *
 * A request that leaves just before the token lapsing arrives after it. Renewing 60 seconds early
 * means a navigation or a screen's first query never races the clock, and the cost is one refresh a
 * minute rather than one refused request per screen.
 */
const RENEWAL_SKEW_MS = 60_000

/** Called when the refresh token can no longer be renewed, so the shell can send the user to sign-in. */
type SessionExpiredHandler = () => void

let expiredHandler: SessionExpiredHandler | null = null

export function setSessionExpiredHandler(handler: SessionExpiredHandler | null): void {
  expiredHandler = handler
}

/** Tell the shell the session is over. Safe to call when no handler is registered yet. */
export function notifySessionExpired(): void {
  expiredHandler?.()
}

/*
 * localStorage is used rather than memory so a refresh keeps the reader signed in, which is what a
 * desk expects from a browser tab. The access token is short-lived and the refresh token rotates on
 * every use, so a leaked copy is detectable server-side.
 */
export function readAccessToken(): string | null {
  return safeStorage()?.getItem(ACCESS_TOKEN_KEY) ?? null
}

export function readRefreshToken(): string | null {
  return safeStorage()?.getItem(REFRESH_TOKEN_KEY) ?? null
}

/** When the access token lapses, or null when that is unknown. */
export function readAccessTokenExpiresAt(): number | null {
  const raw = safeStorage()?.getItem(EXPIRES_AT_KEY)
  if (!raw) return null
  const parsed = Number(raw)
  return Number.isFinite(parsed) ? parsed : null
}

/**
 * Store a freshly issued pair, with the moment the access token lapses.
 *
 * `expiresInSeconds` comes from the backend's own token response, so the client never guesses a
 * lifetime. A missing or non-positive value stores no expiry, which degrades to the old
 * refresh-on-401 behaviour rather than to a token that is assumed to live forever.
 */
export function writeTokens(accessToken: string, refreshToken: string, expiresInSeconds?: number): void {
  const storage = safeStorage()
  if (!storage) return
  storage.setItem(ACCESS_TOKEN_KEY, accessToken)
  storage.setItem(REFRESH_TOKEN_KEY, refreshToken)
  if (typeof expiresInSeconds === 'number' && Number.isFinite(expiresInSeconds) && expiresInSeconds > 0) {
    storage.setItem(EXPIRES_AT_KEY, String(Date.now() + expiresInSeconds * 1000))
  } else {
    storage.removeItem(EXPIRES_AT_KEY)
  }
}

export function clearTokens(): void {
  const storage = safeStorage()
  if (!storage) return
  storage.removeItem(ACCESS_TOKEN_KEY)
  storage.removeItem(REFRESH_TOKEN_KEY)
  storage.removeItem(EXPIRES_AT_KEY)
}

/** True when a refresh token is held, which is the condition for attempting a renewal. */
export function hasRefreshToken(): boolean {
  return readRefreshToken() !== null
}

/**
 * True when the access token is absent, already lapsed, or about to.
 *
 * An unknown expiry reads as "not expiring soon" so that a session written by an older build still
 * works: the client falls back to refreshing on a 401, which is a correct answer, just a noisier one.
 */
export function isAccessTokenExpiringSoon(skewMs: number = RENEWAL_SKEW_MS): boolean {
  if (!readAccessToken()) return false
  const expiresAt = readAccessTokenExpiresAt()
  if (expiresAt === null) return false
  return Date.now() >= expiresAt - skewMs
}

/**
 * localStorage, or null when it is unavailable.
 *
 * A browser in strict private mode throws on access rather than returning null, and losing the
 * session on refresh is a far better outcome than a blank screen, so the failure is contained here.
 */
function safeStorage(): Storage | null {
  try {
    return window.localStorage
  } catch {
    return null
  }
}
