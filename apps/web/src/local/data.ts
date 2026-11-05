import type { Row } from './query'

/**
 * The bundled demonstration dataset, loaded on demand.
 *
 * Everything under `src/data` is generated from the running API by
 * `scripts/generate-demo-data.mjs`, so the shapes here are the API's own - this file does not
 * describe or transform them, it only fetches them.
 *
 * ## Why every module is a dynamic import
 *
 * The dataset is a few megabytes, and the ledger is most of it: a reader opening the dashboard has
 * no use for 1,921 rent invoices. A literal `import` of every module would inline all of it into the
 * entry chunk and make the first paint wait for data nobody asked for. A dynamic `import()` lets the
 * bundler emit one chunk per module, so a screen downloads exactly the data it renders.
 *
 * ## Mutation
 *
 * The modules are plain objects, and the in-memory write layer edits their `items` arrays directly.
 * That is what makes a created record appear in the register, in search and in pagination at once:
 * there is one array, and both the writer and the reader are looking at it. The modules are imported
 * once per page load, so the edits last until the reader reloads.
 */

export interface RegisterModule {
  items: Row[]
  details: Record<string, Row>
}

type Loader = () => Promise<{ default: unknown }>

const registerLoaders: Record<string, Loader> = {
  properties: () => import('../data/properties.json'),
  units: () => import('../data/units.json'),
  owners: () => import('../data/owners.json'),
  tenants: () => import('../data/tenants.json'),
  buyers: () => import('../data/buyers.json'),
  offices: () => import('../data/offices.json'),
  agents: () => import('../data/agents.json'),
  leads: () => import('../data/leads.json'),
  listings: () => import('../data/listings.json'),
  viewings: () => import('../data/viewings.json'),
  applications: () => import('../data/applications.json'),
  leases: () => import('../data/leases.json'),
  invoices: () => import('../data/invoices.json'),
  payments: () => import('../data/payments.json'),
  maintenance: () => import('../data/maintenance.json'),
  expenses: () => import('../data/expenses.json'),
  sales: () => import('../data/sales.json'),
  offers: () => import('../data/offers.json'),
  portfolios: () => import('../data/portfolios.json'),
  documents: () => import('../data/documents.json'),
  communications: () => import('../data/communications.json'),
  audit: () => import('../data/audit.json'),
  imports: () => import('../data/imports.json'),
}

const singletons: Record<string, Loader> = {
  accounts: () => import('../data/accounts.json'),
  notifications: () => import('../data/notifications.json'),
  platform: () => import('../data/platform.json'),
  reference: () => import('../data/reference.json'),
  aggregates: () => import('../data/aggregates.json'),
  reports: () => import('../data/reports.json'),
  projections: () => import('../data/projections.json'),
  manifest: () => import('../data/manifest.json'),
}

const cache = new Map<string, unknown>()

async function load<T>(name: string, loader: Loader): Promise<T> {
  if (!cache.has(name)) cache.set(name, (await loader()).default)
  return cache.get(name) as T
}

/** One register: its rows, and the details of the ones a screen can open. */
export function loadRegister(name: string): Promise<RegisterModule> {
  const loader = registerLoaders[name]
  if (!loader) throw new Error(`No bundled register called '${name}'`)
  return load<RegisterModule>(name, loader)
}

/** True when the bundled dataset carries this register - used to decide a 404 rather than a crash. */
export function hasRegister(name: string): boolean {
  return name in registerLoaders
}

export const loadAccounts = () => load<{ email: string; user: Row }[]>('accounts', singletons.accounts)
export const loadNotifications = () =>
  load<Record<string, { items: Row[]; unread: { unread: number } }>>('notifications', singletons.notifications)
export const loadPlatform = () => load<Row>('platform', singletons.platform)
export const loadReference = () => load<Row>('reference', singletons.reference)
export const loadAggregates = () => load<Row>('aggregates', singletons.aggregates)
export const loadReports = () => load<Record<string, Row>>('reports', singletons.reports)
export const loadProjections = () => load<Record<string, unknown>>('projections', singletons.projections)

/** One captured projection, e.g. `properties/<id>/metrics`, or undefined if it was never captured. */
export async function projection(key: string): Promise<unknown> {
  return (await loadProjections())[key]
}

/** The same, but for a projection a screen cannot work without - a miss is a 404, not a blank panel. */
export async function requireProjection(key: string): Promise<unknown> {
  const value = await projection(key)
  if (value === undefined) throw new Error(`No bundled projection at '${key}'`)
  return value
}

/**
 * Which properties each owner currently holds.
 *
 * `properties?ownerId=` is served from the ownership records rather than from a column on the
 * property, because a holding is a dated period with a share and a property can change hands. Only
 * open holdings count: a closed period is history, and listing the former owner's asset under their
 * name today would be wrong.
 */
export async function ownershipIndex(): Promise<(ownerId: string) => Set<string>> {
  const projections = await loadProjections()
  const byOwner = new Map<string, Set<string>>()

  for (const [key, value] of Object.entries(projections)) {
    const match = /^properties\/([^/]+)\/ownership$/.exec(key)
    if (!match || !Array.isArray(value)) continue

    for (const share of value as Row[]) {
      if (!share.ownerId || share.open === false) continue
      const held = byOwner.get(share.ownerId) ?? new Set<string>()
      held.add(match[1])
      byOwner.set(share.ownerId, held)
    }
  }

  return (ownerId: string) => byOwner.get(ownerId) ?? new Set<string>()
}

/** A fresh id for a record created in this session. Shaped like the UUIDs the API mints. */
export function newId(): string {
  return crypto.randomUUID()
}

/** A business code for a record created in this session, e.g. `ME-P-90001`. */
export function newCode(prefix: string): string {
  return `${prefix}${String(90000 + Math.floor(Math.random() * 9999))}`
}
