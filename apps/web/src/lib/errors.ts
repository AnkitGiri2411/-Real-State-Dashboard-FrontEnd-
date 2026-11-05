/**
 * The error shape the API answers in, and the one place a failure becomes a message.
 *
 * The backend has a single failure envelope - `{ success: false, error: { code, message, details } }`
 * - so the client has exactly one place to read a refusal. `code` is what code branches on;
 * `message` is what a person reads, and the backend has already translated it (see `ErrorMessages`),
 * so the UI must show `message` and never the raw `code`.
 */

/** A failure the API described. */
export class ApiError extends Error {
  readonly code: string
  readonly status: number
  readonly details: string[]

  constructor(code: string, message: string, status: number, details: string[] = []) {
    super(message)
    this.name = 'ApiError'
    this.code = code
    this.status = status
    this.details = details
  }

  /** True when the caller's session is the problem rather than their request. */
  get isAuthFailure(): boolean {
    return this.status === 401
  }

  /** True when the caller is signed in but not permitted. */
  get isForbidden(): boolean {
    return this.status === 403
  }

  /** True when the request was well formed but the portfolio's state refused it. */
  get isConflict(): boolean {
    return this.status === 409
  }
}

/**
 * A network or transport failure.
 *
 * Separate from {@link ApiError} because the answer differs: the API did not refuse, it was never
 * reached. That is a "try again", not a "you cannot do that".
 */
export class NetworkError extends Error {
  readonly cause?: unknown

  constructor(message: string, cause?: unknown) {
    super(message)
    this.name = 'NetworkError'
    this.cause = cause
  }
}

/** The sentence to show for any thrown value. */
export function messageOf(error: unknown): string {
  if (error instanceof ApiError || error instanceof NetworkError) return error.message
  if (error instanceof Error) return error.message
  return 'Something went wrong. Please try again.'
}

/** The field-level sentences to show beside inputs, when the API supplied any. */
export function detailsOf(error: unknown): string[] {
  return error instanceof ApiError ? error.details : []
}

/** True when retrying the same request could plausibly succeed. */
export function isRetryable(error: unknown): boolean {
  if (error instanceof NetworkError) return true
  if (error instanceof ApiError) return error.status >= 500
  return false
}
