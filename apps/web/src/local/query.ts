import type { PageResponse } from '../types'

/**
 * The register engine: filter, sort, then slice.
 *
 * Every register in the product is read as a page - `{items, page, size, totalElements, totalPages,
 * hasNext}` - and the screens depend on all four of those numbers, not just the rows: `Pagination`
 * renders "showing 21-40 of 222" from them and disables its own buttons from `hasNext`. So this
 * reproduces the whole envelope rather than handing back an array.
 *
 * ## The rules that have to match the backend
 *
 * - **An absent parameter matches everything.** `buildUrl` drops every `null`, `undefined` and `''`
 *   value before the request is sent, so "the reader cleared this filter" arrives as "the parameter
 *   is not there at all". A filter that treated a missing value as `''` would match nothing and the
 *   register would look empty the moment a screen first loaded.
 * - **Paging is zero-based** and `page`/`size` are clamped the way `PageRequestFactory` clamps them
 *   (`DEFAULT_PAGE_SIZE` 20, `MAX_PAGE_SIZE` 100).
 * - **Sorting only happens when asked for.** With no `sort` the captured order is kept, which is the
 *   order the backend returned - so a register nobody has sorted looks exactly as it did online.
 * - **A sort field no row carries leaves the order alone.** The service answers 400 for a field that
 *   is not on its whitelist; here it is a harmless no-op, because every screen only ever offers the
 *   fields its register declares and a wrong alias would show up as a column that will not sort
 *   rather than as an error the reader cannot act on.
 */

export type Row = Record<string, any>

/** What a parameter does to a row. */
export type Filter =
  /** Case-insensitive equality on one field: an id, an enum, a boolean or a number. */
  | string
  /** Equality on whichever of these fields matches. */
  | { any: string[] }
  /** Case-insensitive "contains" over any of these fields. */
  | { contains: string[] }
  /** That field is on or after this date/datetime. */
  | { from: string }
  /** That field is on or before this date/datetime. */
  | { to: string }
  /** Anything the declarative rules cannot express. */
  | ((row: Row, value: string) => boolean)

export interface RegisterSpec {
  /** Response parameter name -> what it does. */
  filters?: Record<string, Filter>
  /** Row fields the free-text `search` parameter matches, mirroring the backend's own list. */
  search?: string[]
  /** API sort field -> row field, where the two names differ (`property` -> `propertyName`). */
  sortAliases?: Record<string, string>
  /** The order the register is returned in when nobody has chosen one. */
  defaultSort?: string
}

export const DEFAULT_PAGE_SIZE = 20
export const MAX_PAGE_SIZE = 100

function text(value: unknown): string {
  return value === null || value === undefined ? '' : String(value)
}

function equals(row: Row, field: string, value: string): boolean {
  const actual = row[field]
  if (actual === null || actual === undefined) return false
  return text(actual).toLowerCase() === value.toLowerCase()
}

function contains(row: Row, fields: string[], needle: string): boolean {
  const lower = needle.toLowerCase()
  return fields.some((field) => text(row[field]).toLowerCase().includes(lower))
}

/** Is this row's field on the requested side of the boundary? Compared as text, which is right for
 * ISO dates and datetimes (`2026-11-05` and `2026-11-05T09:00:00Z` both sort and compare correctly). */
function onOrAfter(row: Row, field: string, boundary: string): boolean {
  const value = text(row[field])
  if (!value) return false
  return value.slice(0, boundary.length) >= boundary
}

function onOrBefore(row: Row, field: string, boundary: string): boolean {
  const value = text(row[field])
  if (!value) return false
  return value.slice(0, boundary.length) <= boundary
}

function matches(row: Row, filter: Filter, value: string): boolean {
  if (typeof filter === 'string') return equals(row, filter, value)
  if (typeof filter === 'function') return filter(row, value)
  if ('any' in filter) return filter.any.some((field) => equals(row, field, value))
  if ('contains' in filter) return contains(row, filter.contains, value)
  if ('from' in filter) return onOrAfter(row, filter.from, value)
  return onOrBefore(row, filter.to, value)
}

/** Apply every present filter parameter. */
export function applyFilters(rows: Row[], params: Record<string, string>, spec: RegisterSpec): Row[] {
  const active = Object.entries(spec.filters ?? {}).filter(([name]) => {
    const value = params[name]
    return value !== undefined && value !== null && value !== ''
  })

  const searchTerm = params.search?.trim()
  const searchFields = spec.search ?? []

  if (active.length === 0 && !searchTerm) return rows

  return rows.filter((row) => {
    for (const [name, filter] of active) {
      if (!matches(row, filter, params[name])) return false
    }
    if (searchTerm && searchFields.length > 0) return contains(row, searchFields, searchTerm)
    return true
  })
}

/**
 * Order a register.
 *
 * The comparison is by value: numbers numerically, everything else as text. Nulls sink to the bottom
 * in both directions - the database puts them at the end of an ascending sort and the beginning of a
 * descending one, and a register whose blanks lead a descending sort reads as though the column were
 * broken.
 */
export function applySort(rows: Row[], sort: string | undefined, spec: RegisterSpec): Row[] {
  const chosen = sort?.trim() || spec.defaultSort
  if (!chosen) return rows

  const [apiField, direction] = chosen.split(',')
  const field = spec.sortAliases?.[apiField] ?? apiField
  const descending = direction?.trim().toLowerCase() === 'desc'

  return [...rows].sort((left, right) => {
    const a = left[field]
    const b = right[field]

    if (a === null || a === undefined) return b === null || b === undefined ? 0 : 1
    if (b === null || b === undefined) return -1

    let result: number
    if (typeof a === 'number' && typeof b === 'number') result = a - b
    else result = text(a).localeCompare(text(b), undefined, { numeric: true, sensitivity: 'base' })

    return descending ? -result : result
  })
}

/** Slice a filtered, sorted register into the envelope every screen reads. */
export function paginate(rows: Row[], params: Record<string, string>): PageResponse<Row> {
  const page = Math.max(0, Number(params.page ?? '0') || 0)
  const size = Math.min(MAX_PAGE_SIZE, Math.max(1, Number(params.size ?? '') || DEFAULT_PAGE_SIZE))

  const totalElements = rows.length
  const totalPages = totalElements === 0 ? 0 : Math.ceil(totalElements / size)
  const start = page * size

  return {
    items: rows.slice(start, start + size),
    page,
    size,
    totalElements,
    totalPages,
    hasNext: start + size < totalElements,
  }
}

/** Filter, sort and page in one call - what every register resolver returns. */
export function readRegister(
  rows: Row[],
  params: Record<string, string>,
  spec: RegisterSpec = {},
): PageResponse<Row> {
  return paginate(applySort(applyFilters(rows, params, spec), params.sort, spec), params)
}
