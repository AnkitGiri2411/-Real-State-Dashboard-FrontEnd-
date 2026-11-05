import type { Row } from './query'
import { respond, respondRaw, isRawRoute, type LocalRequest } from './router'

/**
 * The local data source: the switch, and the way in.
 *
 * When `VITE_DATA_SOURCE` is `local` - which is the default - `apiFetch` and `apiFetchRaw` answer
 * from here instead of making a request. Everything above that line is untouched: `services/index.ts`
 * still declares the same paths, the pages still call the same functions, and the errors are still
 * `ApiError`s. That is what allows a static build with no server to run the whole product.
 *
 * `VITE_DATA_SOURCE=api` restores the real calls, so a checkout with a backend keeps working exactly
 * as it did - there is one build, and this decides which half of it is live.
 *
 * **The default lives in code, not in `.env`.** Vercel builds with no environment variables set, so
 * a value that only existed in `.env` would deploy a bundle that tried to reach `localhost:8083`.
 */

export type DataSource = 'local' | 'api'

export function dataSource(): DataSource {
  return import.meta.env.VITE_DATA_SOURCE === 'api' ? 'api' : 'local'
}

export function isLocalDataSource(): boolean {
  return dataSource() === 'local'
}

/** The options `apiFetch` passes through, declared here to keep this module free of a cycle. */
export interface LocalOptions {
  method?: 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE'
  body?: unknown
  formData?: FormData
  query?: Record<string, string | number | boolean | null | undefined>
}

/**
 * Collect the parameters a request carries.
 *
 * Two sources have to be merged, because `services/index.ts` does both: most callers pass `query`
 * (which `buildUrl` would serialise), but a few build the string into the path itself
 * (`unitApi.board` sends `` `/units/board?propertyId=${propertyId}` ``). An explicit `query` wins,
 * matching what `buildUrl` would have produced.
 *
 * Blank values are dropped rather than passed on, which is the rule the whole filter layer depends
 * on: `buildUrl` omits `null`, `undefined` and `''`, so "the reader cleared this filter" arrives as
 * "the parameter is absent" and must be understood that way.
 */
export function collectParams(path: string, query?: LocalOptions['query']): {
  path: string
  params: Record<string, string>
} {
  const [cleanPath, search] = path.split('?')
  const params: Record<string, string> = {}

  for (const [name, value] of new URLSearchParams(search ?? '')) {
    params[name] = value
  }

  for (const [name, value] of Object.entries(query ?? {})) {
    if (value === null || value === undefined || value === '') continue
    params[name] = String(value)
  }

  return { path: cleanPath, params }
}

function request(path: string, options: LocalOptions): LocalRequest {
  const { path: cleanPath, params } = collectParams(path, options.query)
  return {
    path: cleanPath,
    method: options.method ?? 'GET',
    query: params,
    body: options.body as Row | undefined,
    formData: options.formData,
  }
}

/** Answer a request from the bundled dataset. Throws `ApiError`, exactly as `apiFetch` expects. */
export async function localRequest<T>(path: string, options: LocalOptions = {}): Promise<T> {
  return (await respond(request(path, options))) as T
}

/** Answer a request whose response is a file rather than JSON - the report CSV download. */
export async function localRawRequest(path: string, options: LocalOptions = {}): Promise<Response> {
  if (!isRawRoute(path)) {
    throw new Error(`${path} is not a download this build serves`)
  }
  return respondRaw(request(path, options))
}
