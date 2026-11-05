import type { RegisterSpec, Row } from './query'
import { readRegister } from './query'
import type { ReportTable } from './aggregates'
import { readReport, reportCsv } from './aggregates'
import { registerSpecs, notificationSpec } from './registers'
import * as store from './data'
import * as writes from './mutations'
import * as auth from './auth'
import { csvResponse, notFound, notImplemented } from './http'

/**
 * The API, implemented in the browser.
 *
 * `apiFetch` hands every request here when the local data source is on, so this is the only place
 * that knows a URL means something. It answers from the generated dataset in `src/data` and from the
 * in-memory write layer, and it raises the same `ApiError`s the service does - which is what lets the
 * pages, the shell, the route guards and the auth store all work without knowing the difference.
 *
 * ## Matching
 *
 * Two rules make the matching unambiguous rather than order-dependent:
 *
 * - `:id` only accepts a UUID. The API's identifiers are UUIDs and its literal segments are words, so
 *   `/properties/map` and `/properties/{id}` can never be confused, in either order.
 * - Literal routes are matched before any `:id` route, so `/{key}/export` never reads as a record.
 *
 * ## Method
 *
 * Read routes require GET. Write routes **deliberately do not check the method**, because the product
 * is not consistent about it: `ListingController` declares `@PutMapping("/{id}/status")` while the
 * frontend's `listingApi.changeStatus` sends `POST`, which against the real service is a 405. The
 * intent is unambiguous from the path, so it is honoured either way - a demonstration should not
 * reproduce a defect whose only effect is that a button does nothing.
 */

export interface LocalRequest {
  /** The path below the API base, e.g. `/properties/<id>/metrics`. */
  path: string
  method: string
  query: Record<string, string>
  body?: Row
  formData?: FormData
}

interface Context {
  query: Record<string, string>
  body: Row
}

type Handler = (context: Context) => unknown | Promise<unknown>

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i

/** API path segment -> the name of the captured register behind it. */
const REGISTERS: Record<string, string> = {
  properties: 'properties',
  units: 'units',
  owners: 'owners',
  tenants: 'tenants',
  buyers: 'buyers',
  offices: 'offices',
  agents: 'agents',
  leads: 'leads',
  listings: 'listings',
  viewings: 'viewings',
  applications: 'applications',
  leases: 'leases',
  'rent-invoices': 'invoices',
  payments: 'payments',
  'maintenance-requests': 'maintenance',
  expenses: 'expenses',
  sales: 'sales',
  offers: 'offers',
  portfolios: 'portfolios',
  documents: 'documents',
  communications: 'communications',
  audit: 'audit',
  imports: 'imports',
}

/** Captured aggregate by the path that serves it. */
const AGGREGATES: Record<string, string> = {
  'dashboard/summary': 'dashboard-summary',
  'leads/pipeline': 'lead-pipeline',
  'sales/pipeline': 'sale-pipeline',
  'sales/summary': 'sale-summary',
  'maintenance-requests/summary': 'maintenance-summary',
  'maintenance-requests/cost-trend': 'maintenance-cost-trend',
  'expenses/summary': 'expense-summary',
  'rent-invoices/collections': 'invoice-collections',
  'payments/collections': 'payment-collections',
  'documents/expiring': 'document-expiring',
  'leases/expiring': 'lease-expiring',
  'viewings/diary': 'viewing-diary',
  'communications/unread-count': 'conversation-unread',
  'properties/map': 'property-map',
}

/** Reference lists: unpaged, and captured whole. */
const REFERENCE: Record<string, string> = {
  'expense-categories': 'expenseCategories',
  'property-types': 'propertyTypes',
  'unit-types': 'unitTypes',
  amenities: 'amenities',
}

/** The per-record projections, by the suffix the controller exposes them under. */
const PROJECTIONS = new Set([
  'metrics',
  'buildings',
  'units',
  'ownership',
  'lease',
  'requirements',
  'workload',
  'performance',
])

/** Actions that write to one record. */
const ACTIONS = new Set([
  'status',
  'archive',
  'assign',
  'stage',
  'publish',
  'unpublish',
  'expire',
  'complete',
  'cancel',
  'resolve',
  'void',
  'approve',
  'reject',
  'withdraw',
  'screenings',
  'read',
])

/* ------------------------------------------------------------------ helpers */

let specsPromise: Promise<Record<string, RegisterSpec>> | null = null

/** The register specifications, built once from the ownership records. */
function specs(): Promise<Record<string, RegisterSpec>> {
  specsPromise ??= store.ownershipIndex().then(registerSpecs)
  return specsPromise
}

function singular(module: string): string {
  return module.replace(/ies$/, 'y').replace(/s$/, '')
}

async function list(module: string, query: Record<string, string>): Promise<unknown> {
  const { items } = await store.loadRegister(module)
  const spec = (await specs())[module] ?? {}
  return readRegister(items, query, spec)
}

/**
 * One record.
 *
 * Falls back to the register row when no separate detail was captured - the ledger registers have
 * none, because no screen opens one. A forgetful 404 for an endpoint the API does serve would be
 * worse than a row carrying everything the register already showed.
 */
async function detail(module: string, id: string): Promise<unknown> {
  const register = await store.loadRegister(module)
  const found = register.details[id] ?? register.items.find((row) => row.id === id)
  if (!found) throw notFound(singular(module))
  return found
}

async function aggregate(key: string): Promise<unknown> {
  const aggregates = await store.loadAggregates()
  const value = aggregates[key]
  if (value === undefined) throw notImplemented(key)
  return value
}

async function projectionOf(key: string): Promise<unknown> {
  const value = await store.projection(key)
  if (value === undefined) throw notImplemented(key)
  return value
}

/** The notification feed for whoever is signed in. */
async function notificationItems(): Promise<Row[]> {
  const all = await store.loadNotifications()
  const email = auth.currentAccountEmail()
  return (email ? all[email]?.items : undefined) ?? []
}

/** A report, windowed to whatever the screen asked for. */
export async function reportTable(key: string, query: Record<string, string>): Promise<ReportTable> {
  const reports = await store.loadReports()
  const captured = reports[key] as ReportTable | undefined
  if (!captured) throw notFound('report')
  return readReport(captured, query)
}

/** The routes that answer a plain GET, keyed by their path below the API base. */
const GET_ROUTES: Record<string, Handler> = {
  'platform/profile': () => store.loadPlatform(),
  'auth/me': () => auth.me(),
  'notifications/unread-count': () => writes.notificationUnread(),
}

/* --------------------------------------------------------------------- run */

/** Resolve a request to its answer, or raise the failure the service would have raised. */
export async function respond(request: LocalRequest): Promise<unknown> {
  const path = request.path.replace(/\/+$/, '') || '/'
  const segments = path.split('/').filter(Boolean)
  const query = request.query
  const body = request.body ?? {}
  const context: Context = { query, body }
  const key = segments.join('/')

  /* ---------------------------------------------------------------- reads */
  if (request.method === 'GET') {
    // The notification feed is per account, so it is resolved here rather than through the table.
    if (key === 'notifications') {
      return readRegister(await notificationItems(), query, notificationSpec)
    }
    if (key in GET_ROUTES) return GET_ROUTES[key](context)
    if (key in AGGREGATES) return aggregate(AGGREGATES[key])
    if (key in REFERENCE) return (await store.loadReference())[REFERENCE[key]]

    if (segments[0] === 'reports' && segments.length === 2) {
      return reportTable(segments[1], query)
    }
    if (segments.length === 1 && key in REGISTERS) return list(REGISTERS[key], query)

    // Query-addressed projections: `/units/board?propertyId=<id>`.
    if (key === 'units/board' || key === 'units/occupancy') {
      const propertyId = query.propertyId
      if (!propertyId) throw notFound('property')
      return projectionOf(`${key === 'units/board' ? 'unit-boards' : 'unit-occupancy'}/${propertyId}`)
    }

    // `/communications/{id}/messages/internal` - three deep, so it is matched before the rest.
    if (
      segments.length === 4 &&
      UUID.test(segments[1]) &&
      segments[2] === 'messages' &&
      segments[3] === 'internal'
    ) {
      return projectionOf(`communications/${segments[1]}/messages/internal`)
    }

    // A per-record projection: `/{base}/{id}/{suffix}`.
    if (segments.length === 3 && UUID.test(segments[1]) && PROJECTIONS.has(segments[2])) {
      const module = REGISTERS[segments[0]]
      if (module) return projectionOf(`${module}/${segments[1]}/${segments[2]}`)
    }

    // One record.
    if (segments.length === 2 && UUID.test(segments[1]) && REGISTERS[segments[0]]) {
      return detail(REGISTERS[segments[0]], segments[1])
    }
  }

  /* --------------------------------------------------------------- writes */
  if (segments[0] === 'auth') {
    const route = segments.slice(1).join('/')
    if (route === 'login') return auth.login(String(body.email ?? ''), String(body.password ?? ''))
    if (route === 'logout' || route === 'logout-all') {
      auth.forgetAccount()
      return null
    }
    if (route === 'change-password') {
      return auth.changePassword(
        String(body.currentPassword ?? ''),
        String(body.newPassword ?? ''),
        String(body.confirmPassword ?? ''),
      )
    }
  }

  if (segments[0] === 'imports' && segments[1] === 'preview') {
    if (!request.formData) throw notFound('file')
    return writes.previewImport(request.formData)
  }

  // `POST /notifications/read` marks the whole feed; `POST /notifications/{id}/read` marks one.
  if (segments[0] === 'notifications') {
    if (segments.length === 2 && segments[1] === 'read') {
      await writes.markNotificationRead(null)
      return writes.notificationUnread()
    }
    if (segments.length === 3 && segments[2] === 'read') {
      return writes.markNotificationRead(segments[1])
    }
  }

  const module = REGISTERS[segments[0]]

  if (module && segments.length === 1) {
    return writes.createRecord(module, body)
  }

  if (module && segments.length === 2 && UUID.test(segments[1])) {
    // `PATCH /offers/{id}` is the decision, not an edit of the offer's own fields.
    if (segments[0] === 'offers' && request.method === 'PATCH') {
      return writes.performAction('offers', segments[1], 'decide', body)
    }
    return writes.updateRecord(module, segments[1], body)
  }

  if (module && segments.length === 3 && UUID.test(segments[1])) {
    const [, id, action] = segments

    if (module === 'communications' && action === 'messages') {
      return writes.conversationAction(id, 'messages', body)
    }
    if (module === 'sales' && action === 'offers') {
      return writes.createRecord('offers', { ...body, saleId: id })
    }
    if (module === 'imports' && action === 'confirm') {
      return detail('imports', id)
    }
    if (ACTIONS.has(action)) {
      return writes.performAction(module, id, action, body)
    }
  }

  throw notImplemented(path)
}

/** True for the paths whose answer is not JSON: currently only the report CSV download. */
export function isRawRoute(path: string): boolean {
  const segments = path.replace(/\/+$/, '').split('/').filter(Boolean)
  return segments[0] === 'reports' && segments[2] === 'export'
}

/** The CSV download for a report, built from the same rows the screen is showing. */
export async function respondRaw(request: LocalRequest): Promise<Response> {
  const segments = request.path.replace(/\/+$/, '').split('/').filter(Boolean)
  const table = await reportTable(segments[1], request.query)
  return csvResponse(reportCsv(table), `${segments[1]}.csv`)
}
