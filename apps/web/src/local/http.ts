import { ApiError } from '../lib/errors'

/**
 * Failures, raised in the backend's own shape.
 *
 * The application already decides what to say and what to offer based on an error's `code` and
 * `status` - `LoginPage` keeps the typed password on a retryable failure and clears it on a
 * rejection, `messageOf` reads the message, `isRetryable` reads the status. So the local data source
 * has to fail the same way the API does rather than throwing something of its own; otherwise every
 * error path in the product would behave differently in this build than in a deployed one.
 */

export function notFound(what: string): ApiError {
  return new ApiError('NOT_FOUND', `We could not find that ${what}.`, 404, [])
}

export function badRequest(message: string, details: string[] = []): ApiError {
  return new ApiError('VALIDATION_FAILED', message, 400, details)
}

export function unauthorized(message = 'Your session has expired. Please sign in again.'): ApiError {
  return new ApiError('UNAUTHORIZED', message, 401, [])
}

export function invalidCredentials(): ApiError {
  return new ApiError('INVALID_CREDENTIALS', 'That e-mail address and password do not match an account.', 401, [])
}

export function notImplemented(path: string): ApiError {
  // A named failure rather than a silent empty answer: an endpoint this build does not carry should
  // say so, because a screen rendering "no rows" would look like an empty portfolio instead of a gap.
  return new ApiError(
    'NOT_AVAILABLE_LOCALLY',
    `This demonstration build does not serve ${path}.`,
    501,
    ['The bundled dataset is generated from the API - see scripts/generate-demo-data.mjs.'],
  )
}

/** The CSV export returns a real `Response`, because the caller downloads a blob from it. */
export function csvResponse(body: string, filename: string): Response {
  return new Response(body, {
    status: 200,
    headers: {
      'Content-Type': 'text/csv;charset=utf-8',
      'Content-Disposition': `attachment; filename="${filename}"`,
    },
  })
}
