import { ApiError, NetworkError } from '../lib/errors'
import { isLocalDataSource, localRawRequest, localRequest } from '../local'
import {
  clearTokens,
  isAccessTokenExpiringSoon,
  notifySessionExpired,
  readAccessToken,
  readRefreshToken,
  writeTokens,
} from '../auth/session'

/**
 * The one place this application talks to the API.
 *
 * Every screen goes through `apiFetch`, so three cross-cutting concerns are solved once rather than
 * per page:
 *
 *  1. **The bearer token** is attached from the session, and never read by a component.
 *  2. **A 401 renews once.** The refresh is single-flight - only one renewal request is in the air
 *     at a time and every waiting caller shares its promise - so a screen that fires six parallel
 *     queries does not trigger six rotations, which would invalidate its own tokens. The original
 *     request is retried **exactly once**; a second 401 ends the session instead of looping.
 *  3. **The envelope is unwrapped here.** The backend answers `{ success, data, message }`, so callers
 *     receive `data` directly and never handle the wrapper. A failure becomes an `ApiError` carrying
 *     the backend's own `code`, human `message` and per-field `details`.
 */

const API_BASE_URL: string = import.meta.env.VITE_API_BASE_URL ?? 'http://localhost:8083/api/v1'

export function apiBaseUrl(): string {
  return API_BASE_URL
}

/** Auth paths must not trigger the refresh flow - a 401 from them is the answer, not a stale token. */
function isAuthPath(path: string): boolean {
  return path.startsWith('/auth/login') || path.startsWith('/auth/refresh')
}

let refreshInFlight: Promise<string | null> | null = null

interface Envelope<T> {
  success?: boolean
  data?: T
  error?: { code?: string; message?: string; details?: string[] }
}

/**
 * Renew the access token, once for all callers.
 *
 * The refresh token **rotates**, so two concurrent renewals would race: the first would rotate the
 * stored token and the second would present an already-rotated one and be refused, ending a session
 * that was never actually over. Sharing one promise is what prevents that.
 */
function refreshAccessToken(): Promise<string | null> {
  if (!refreshInFlight) {
    refreshInFlight = performRefresh().finally(() => {
      refreshInFlight = null
    })
  }
  return refreshInFlight
}

async function performRefresh(): Promise<string | null> {
  const refreshToken = readRefreshToken()
  if (!refreshToken) return null
  try {
    const response = await fetch(`${API_BASE_URL}/auth/refresh`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ refreshToken }),
    })
    if (!response.ok) return null
    const payload = (await response.json().catch(() => ({}))) as Envelope<{
      accessToken?: string
      refreshToken?: string
      expiresInSeconds?: number
    }>
    const accessToken = payload.data?.accessToken
    const rotated = payload.data?.refreshToken
    if (!accessToken || !rotated) return null
    writeTokens(accessToken, rotated, payload.data?.expiresInSeconds)
    return accessToken
  } catch {
    return null
  }
}

/** End the session and let the shell react, rather than leaving a screen of failing requests. */
function endSession(): void {
  clearTokens()
  notifySessionExpired()
}

export interface RequestOptions {
  method?: 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE'
  /** A JSON body. Serialised here so no caller hand-writes `JSON.stringify`. */
  body?: unknown
  /** A multipart body, sent as-is so the browser sets its own boundary. */
  formData?: FormData
  /** Query parameters. `null`/`undefined`/empty values are omitted rather than sent as blanks. */
  query?: Record<string, string | number | boolean | null | undefined>
  /** Skip the 401 refresh flow. Used by the auth calls themselves. */
  skipAuthRefresh?: boolean
  signal?: AbortSignal
}

/**
 * Compose a request URL from the configured base, the path and any query parameters.
 *
 * Exported because it is a pure function with a subtle rule worth pinning by test: the base may be
 * **relative**. A deployment that serves the dashboard and the API from one origin sets
 * `VITE_API_BASE_URL=/api/v1`, and the second argument to `URL` is what lets that resolve - without
 * it, `new URL('/api/v1/properties')` throws `Invalid URL` and every request in the application
 * fails before it is sent. Resolving against the document's own origin keeps one build working on
 * localhost, on a platform subdomain and behind a custom domain alike.
 */
export function buildUrl(path: string, query?: RequestOptions['query']): string {
  const url = new URL(`${API_BASE_URL}${path}`, window.location.origin)
  if (query) {
    for (const [key, value] of Object.entries(query)) {
      if (value === null || value === undefined || value === '') continue
      url.searchParams.set(key, String(value))
    }
  }
  // The backend composes its own URLs; a trailing '?' would be noise in logs.
  return url.toString()
}

/** Perform the request, translating the envelope and the failure shape. */
export async function apiFetch<T>(path: string, options: RequestOptions = {}): Promise<T> {
  /*
   * The one branch that decides whether this build talks to a server at all.
   *
   * `local` - the default - answers from the bundled dataset in `src/local`, which is what lets the
   * product be deployed as a static site with no backend. `api` falls through to the fetch below,
   * unchanged. Everything above this line is identical either way: the same paths, the same
   * envelopes, the same `ApiError`s.
   */
  if (isLocalDataSource()) return localRequest<T>(path, options)

  const { method = 'GET', body, formData, query, skipAuthRefresh, signal } = options

  const headers = new Headers()
  if (body !== undefined && !formData) headers.set('Content-Type', 'application/json')

  const send = (token: string | null): Promise<Response> => {
    const outgoing = new Headers(headers)
    if (token) outgoing.set('Authorization', `Bearer ${token}`)
    return fetch(buildUrl(path, query), {
      method,
      headers: outgoing,
      body: formData ?? (body !== undefined ? JSON.stringify(body) : undefined),
      signal,
    })
  }

  /*
   * Renew *before* sending when the token is already stale or about to lapse.
   *
   * This is the whole point of storing the expiry: without it, a page load with an aged token sent a
   * request the server had to refuse, and although the client then refreshed and succeeded, the
   * browser had already logged a 401 the reader could do nothing about. A `skipAuthRefresh` caller
   * (sign-in, refresh) is left alone, and a path whose 401 is the answer rather than a stale token
   * is filtered by `isAuthPath`.
   */
  let token = readAccessToken()
  if (token && !skipAuthRefresh && !isAuthPath(path) && isAccessTokenExpiringSoon()) {
    const renewed = await refreshAccessToken()
    if (renewed) token = renewed
  }

  let response: Response
  try {
    response = await send(token)
  } catch (cause) {
    throw new NetworkError('Could not reach the server. Check your connection and try again.', cause)
  }

  if (response.status === 401 && !skipAuthRefresh && !isAuthPath(path)) {
    const renewed = await refreshAccessToken()
    if (!renewed) {
      endSession()
      throw new ApiError('UNAUTHORIZED', 'Your session has expired. Please sign in again.', 401)
    }
    try {
      response = await send(renewed)
    } catch (cause) {
      throw new NetworkError('Could not reach the server. Check your connection and try again.', cause)
    }
  }

  return unwrap<T>(response)
}

/**
 * A raw response, for a consumer that is not JSON - the CSV exports.
 *
 * Still goes through the refresh flow, so an export does not fail merely because the access token
 * happened to expire while the reader was choosing filters.
 */
export async function apiFetchRaw(path: string, options: RequestOptions = {}): Promise<Response> {
  // The local build synthesises the download from the same rows the screen is showing.
  if (isLocalDataSource()) return localRawRequest(path, options)

  const { method = 'GET', body, query, skipAuthRefresh, signal } = options

  const headers = new Headers()
  if (body !== undefined) headers.set('Content-Type', 'application/json')

  const send = (token: string | null): Promise<Response> => {
    const outgoing = new Headers(headers)
    if (token) outgoing.set('Authorization', `Bearer ${token}`)
    return fetch(buildUrl(path, query), {
      method,
      headers: outgoing,
      body: body !== undefined ? JSON.stringify(body) : undefined,
      signal,
    })
  }

  let response: Response
  try {
    response = await send(readAccessToken())
  } catch (cause) {
    throw new NetworkError('Could not reach the server. Check your connection and try again.', cause)
  }

  if (response.status === 401 && !skipAuthRefresh && !isAuthPath(path)) {
    const renewed = await refreshAccessToken()
    if (!renewed) {
      endSession()
      throw new ApiError('UNAUTHORIZED', 'Your session has expired. Please sign in again.', 401)
    }
    response = await send(renewed)
  }

  if (!response.ok) throw await toApiError(response)
  return response
}

/** Read one response, unwrapping the success envelope or raising the failure. */
async function unwrap<T>(response: Response): Promise<T> {
  if (response.status === 204) return undefined as T

  const payload = (await response.json().catch(() => null)) as Envelope<T> | null

  if (!response.ok) throw toApiErrorFrom(response, payload)

  if (payload && typeof payload === 'object' && 'data' in payload) return payload.data as T
  // A flat body (the readiness probe) is returned as-is rather than rejected.
  return payload as unknown as T
}

async function toApiError(response: Response): Promise<ApiError> {
  const payload = (await response.json().catch(() => null)) as Envelope<unknown> | null
  return toApiErrorFrom(response, payload)
}

function toApiErrorFrom(response: Response, payload: Envelope<unknown> | null): ApiError {
  const error = payload?.error
  const code = error?.code ?? `HTTP_${response.status}`
  const message =
    error?.message ?? defaultMessageFor(response.status, response.statusText)
  return new ApiError(code, message, response.status, error?.details ?? [])
}

/**
 * A sentence for a failure the backend did not describe.
 *
 * Reaching here means the response had no envelope at all - a proxy error page, a gateway timeout -
 * so the status is the only information available and it is translated rather than shown as a number.
 */
function defaultMessageFor(status: number, statusText: string): string {
  if (status === 401) return 'Your session has expired. Please sign in again.'
  if (status === 403) return 'Your account does not have permission to do that.'
  if (status === 404) return 'We could not find that record.'
  if (status === 409) return 'That change conflicts with other records in the portfolio.'
  if (status === 413) return 'That file is larger than this installation accepts.'
  if (status === 422) return 'Some of the details need correcting.'
  if (status >= 500) return 'Something went wrong at our end. Please try again.'
  return statusText || 'The request could not be completed.'
}
