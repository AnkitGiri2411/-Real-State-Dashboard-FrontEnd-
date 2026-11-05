#!/usr/bin/env node
/*
 * Generates the bundled demonstration dataset from a running backend.
 *
 * ============================================================================
 * Why this is a script rather than hand-written fixtures
 * ============================================================================
 *
 * The static build has no server, so the data it shows has to ship inside it. Writing that data out
 * by hand would guarantee it drifts: the shapes come from the DTO records, the row values come from
 * the Flyway seed, and both change independently of anything in `apps/web`.
 *
 * So instead this walks the real API and writes down what it answers. The bundled data is therefore
 * the migrated data - 14 properties, 222 units, 166 leases and the whole rent ledger - because it is
 * the same responses the application serves.
 *
 * ============================================================================
 * How to run it
 * ============================================================================
 *
 * The backend has to be up first. Either the SQL-file mode, which needs no database to be prepared:
 *
 *   cd backend && mvn spring-boot:run                 # APP_DATA_SOURCE=sqlfiles in backend/.env
 *
 * or a backend pointed at a real database, if you want the bundle built from that instead:
 *
 *   cd backend && APP_DATA_SOURCE=postgres mvn spring-boot:run
 *
 * Then, from apps/web:
 *
 *   npm run generate:demo-data
 *
 * Output lands in `src/data/` and is committed. Re-run it whenever the migrations or the DTOs change -
 * `src/data/manifest.json` records what was captured and when, so a stale bundle is identifiable.
 */

import { mkdir, writeFile } from 'node:fs/promises'
import { fileURLToPath } from 'node:url'

const API_BASE = process.env.DEMO_API_BASE ?? 'http://localhost:8083/api/v1'
const DEMO_PASSWORD = process.env.DEMO_PASSWORD ?? 'testing'

/** Where the bundle is written. Relative to this file, so it runs from anywhere. */
const OUTPUT_DIRECTORY = fileURLToPath(new URL('../src/data/', import.meta.url))

/** Asked for in every register read. The backend caps a page at `PageRequestFactory.MAX_PAGE_SIZE`. */
const PAGE_SIZE = 100

/** A stop so a backend that never reports `hasNext: false` cannot spin forever. */
const MAX_PAGES = 500

/**
 * The ten seeded accounts, from `V2__seed_roles_permissions_and_users.sql`.
 *
 * Signing in as each one is how the bundle learns their roles and permissions: `GET /auth/me`
 * answers with the real set, so the static build's role model is the database's own rather than a
 * transcription of the seed that could quietly disagree with it.
 */
const DEMO_ACCOUNTS = [
  'owner@meridianestates.example',
  'admin@meridianestates.example',
  'manager@meridianestates.example',
  'leasing@meridianestates.example',
  'leasing2@meridianestates.example',
  'sales@meridianestates.example',
  'sales2@meridianestates.example',
  'accounts@meridianestates.example',
  'maintenance@meridianestates.example',
  'viewer@meridianestates.example',
]

/**
 * Every register, with a detail route **only where a screen can actually open one**.
 *
 * Which details are worth capturing was decided by looking at what the pages call, not by what the
 * API offers. `invoiceApi.detail`, for instance, exists and is never reached - no screen opens a
 * single rent invoice - so capturing 1,921 of them would put several megabytes into the bundle that
 * no click could ever show. The registers left without a detail route are `offices`, `agents`,
 * `listings`, `viewings`, `invoices`, `payments`, `maintenance`, `expenses`, `sales`, `portfolios`
 * and `documents`.
 *
 * Their actions still work: a write is answered by the local layer, not by a captured record.
 *
 * `idField` is what the detail route is keyed on - `jobCode` rather than `id` for imports, which is
 * the only register that names its rows differently.
 */
const REGISTERS = [
  // Detail reachable from a screen.
  { module: 'properties', path: '/properties', detail: '/properties/{id}' }, // PropertyDetailPage
  { module: 'units', path: '/units', detail: '/units/{id}' }, // UnitsPage dialog
  { module: 'owners', path: '/owners', detail: '/owners/{id}' }, // OwnersPage dialog
  { module: 'tenants', path: '/tenants', detail: '/tenants/{id}' }, // TenantsPage dialog
  { module: 'buyers', path: '/buyers', detail: '/buyers/{id}' }, // BuyersPage dialog
  { module: 'leads', path: '/leads', detail: '/leads/{id}' }, // LeadDetailPage
  { module: 'applications', path: '/applications', detail: '/applications/{id}' }, // ApplicationsPage
  { module: 'leases', path: '/leases', detail: '/leases/{id}' }, // LeaseDetailPage
  { module: 'communications', path: '/communications', detail: '/communications/{id}' }, // messages
  { module: 'imports', path: '/imports', detail: '/imports/{id}', idField: 'jobCode' }, // ImportsPage

  // Register only: the row is the whole record as far as the product is concerned.
  { module: 'offices', path: '/offices', detail: null },
  { module: 'agents', path: '/agents', detail: null },
  { module: 'listings', path: '/listings', detail: null },
  { module: 'viewings', path: '/viewings', detail: null },
  { module: 'invoices', path: '/rent-invoices', detail: null },
  { module: 'payments', path: '/payments', detail: null },
  { module: 'maintenance', path: '/maintenance-requests', detail: null },
  { module: 'expenses', path: '/expenses', detail: null },
  { module: 'sales', path: '/sales', detail: null },
  { module: 'portfolios', path: '/portfolios', detail: null },
  { module: 'documents', path: '/documents', detail: null },
  { module: 'offers', path: '/offers', detail: null },
  { module: 'audit', path: '/audit', detail: null },
]

/**
 * The projections, which are computed per record rather than per register.
 *
 * A property's unit counts, occupancy and rent roll exist only on `/{id}/metrics`; an agent's
 * workload only on `/{id}/workload`. Pages read them and are written never to recompute a figure the
 * API sent, so they have to be captured rather than derived.
 */
const PER_RECORD_PROJECTIONS = [
  { parent: 'properties', suffix: 'metrics' },
  { parent: 'properties', suffix: 'buildings' },
  { parent: 'properties', suffix: 'units', paged: true },
  { parent: 'properties', suffix: 'ownership' },
  { parent: 'units', suffix: 'lease' },
  { parent: 'buyers', suffix: 'requirements' },
  { parent: 'agents', suffix: 'workload' },
  { parent: 'agents', suffix: 'performance' },
  { parent: 'portfolios', suffix: 'performance' },
  { parent: 'communications', suffix: 'messages/internal' },
]

/** Projections addressed by a query parameter rather than by a path segment. */
const QUERY_PROJECTIONS = [
  { parent: 'properties', module: 'unit-boards', path: '/units/board', parameter: 'propertyId' },
  { parent: 'properties', module: 'unit-occupancy', path: '/units/occupancy', parameter: 'propertyId' },
]

/** The reporting endpoints, which need no arguments beyond the window. */
const REPORT_KEYS = [
  'property-performance',
  'occupancy',
  'rental-income',
  'outstanding-rent',
  'lease-expiry',
  'tenant',
  'maintenance-cost',
  'expense',
  'sales',
  'lead-conversion',
  'portfolio-performance',
  'agent-performance',
]

/** Simple reads with no parameters: the whole response is stored under its own key. */
const PLAIN_READS = {
  profile: '/platform/profile',
  'dashboard-summary': '/dashboard/summary',
  'lead-pipeline': '/leads/pipeline',
  'sale-pipeline': '/sales/pipeline',
  'sale-summary': '/sales/summary',
  'maintenance-summary': '/maintenance-requests/summary',
  'maintenance-cost-trend': '/maintenance-requests/cost-trend',
  'expense-summary': '/expenses/summary',
  'invoice-collections': '/rent-invoices/collections',
  'payment-collections': '/payments/collections',
  'document-expiring': '/documents/expiring',
  'lease-expiring': '/leases/expiring',
  'viewing-diary': '/viewings/diary',
  'conversation-unread': '/communications/unread-count',
  'property-map': '/properties/map',
}

/** The unpaged reference lists. */
const REFERENCE_READS = {
  propertyTypes: '/property-types',
  unitTypes: '/unit-types',
  amenities: '/amenities',
  expenseCategories: '/expense-categories',
}

let accessToken = null

/** One API call, unwrapping the envelope and failing loudly. */
async function api(path, { token = accessToken, method = 'GET', body } = {}) {
  const response = await fetch(`${API_BASE}${path}`, {
    method,
    headers: {
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
      ...(body ? { 'Content-Type': 'application/json' } : {}),
    },
    body: body ? JSON.stringify(body) : undefined,
  })

  const payload = await response.json().catch(() => null)
  if (!response.ok || payload?.success === false) {
    throw new Error(
      `${method} ${path} failed: ${response.status} ${payload?.error?.code ?? ''} ${
        payload?.error?.message ?? ''
      }`,
    )
  }
  return payload?.data
}

/**
 * A paged read, walked to the end and returned as plain rows.
 *
 * <b>This must page.</b> The backend caps a page at 100 (`PageRequestFactory.MAX_PAGE_SIZE`), so
 * asking for `size=5000` does not fail - it quietly returns 100 rows, and a bundle built from that
 * would show 100 of 222 units and 100 of 1,921 invoices while looking perfectly successful. The loop
 * follows the response's own `hasNext`, which is the authority on whether there is more.
 */
async function readAll(path, query = '', token = accessToken) {
  const joiner = path.includes('?') ? '&' : '?'
  const items = []

  for (let page = 0; page < MAX_PAGES; page += 1) {
    const response = await api(`${path}${query}${joiner}page=${page}&size=${PAGE_SIZE}`, { token })
    if (Array.isArray(response)) return response

    const batch = response?.items ?? []
    items.push(...batch)

    if (!response?.hasNext || batch.length === 0) return items
  }

  throw new Error(`Paging ${path} did not finish within ${MAX_PAGES} pages`)
}

const written = {}

/** Write one module, returning its size in bytes so the manifest can report the real cost. */
async function writeModule(name, value) {
  const json = JSON.stringify(value)
  await writeFile(`${OUTPUT_DIRECTORY}${name}.json`, json, 'utf8')
  written[name] = { bytes: Buffer.byteLength(json, 'utf8'), entries: Array.isArray(value) ? value.length : Object.keys(value).length }
  return json
}

async function signIn(email) {
  const tokens = await api('/auth/login', { method: 'POST', token: null, body: { email, password: DEMO_PASSWORD } })
  return tokens.accessToken
}

/* ------------------------------------------------------------------ capture */

/**
 * One account's identity, and its notifications.
 *
 * The notification feed is **per caller** - the service answers "what is waiting for you" - so it has
 * to be captured once per account. A single capture would leave the bell empty for every account but
 * the one used, and the Notifications screen showing nothing at all. Four of the ten accounts have
 * six each; the rest genuinely have none, and that difference is part of what the data says.
 */
async function captureAccounts() {
  const users = []
  const notifications = {}

  for (const email of DEMO_ACCOUNTS) {
    const token = await signIn(email)
    // Read the identity through the API, so roles and permissions are the database's own.
    const me = await api('/auth/me', { token })
    users.push({ email, user: me })

    const items = await readAll('/notifications', '', token)
    const unread = await api('/notifications/unread-count', { token })
    notifications[email] = { items, unread }

    process.stdout.write(
      `  ${email.padEnd(38)} ${me.roles.join(',').padEnd(18)} ${items.length} notifications\n`,
    )
  }

  return { users, notifications }
}

async function captureRegisters() {
  const modules = {}

  for (const register of REGISTERS) {
    const items = await readAll(register.path)
    const idField = register.idField ?? 'id'

    const details = {}
    if (register.detail) {
      for (const item of items) {
        const id = item[idField]
        if (!id) continue
        details[id] = await api(register.detail.replace('{id}', encodeURIComponent(id)))
      }
    }

    modules[register.module] = { items, details }
    process.stdout.write(
      `  ${register.module.padEnd(16)} ${String(items.length).padStart(5)} rows` +
        (register.detail ? `, ${Object.keys(details).length} details\n` : `\n`),
    )
  }

  return modules
}

async function captureProjections(registers) {
  const projections = {}

  for (const spec of PER_RECORD_PROJECTIONS) {
    const records = registers[spec.parent]?.items ?? []
    for (const record of records) {
      // Keyed the way the path is built, so the local layer can look one up without re-deriving it.
      projections[`${spec.parent}/${record.id}/${spec.suffix}`] = spec.paged
        ? await readAll(`/${spec.parent}/${record.id}/${spec.suffix}`)
        : await api(`/${spec.parent}/${record.id}/${spec.suffix}`)
    }
    process.stdout.write(
      `  ${(spec.parent + '/' + spec.suffix).padEnd(34)} ${records.length} records\n`,
    )
  }

  for (const spec of QUERY_PROJECTIONS) {
    const records = registers[spec.parent]?.items ?? []
    for (const record of records) {
      projections[`${spec.module}/${record.id}`] = await api(
        `${spec.path}?${spec.parameter}=${encodeURIComponent(record.id)}`,
      )
    }
  }

  return projections
}

async function captureReports() {
  const reports = {}
  for (const key of REPORT_KEYS) {
    // No window is passed, which is what the service treats as "its own default window" - the same
    // answer the Reports screen gets on first open before anyone narrows the dates.
    reports[key] = await api(`/reports/${key}`)
    process.stdout.write(`  ${key.padEnd(24)} ${reports[key]?.rows?.length ?? 0} rows\n`)
  }
  return reports
}

async function capturePlainReads() {
  const out = {}
  for (const [key, path] of Object.entries(PLAIN_READS)) {
    out[key] = await api(path)
  }
  return out
}

async function captureReference() {
  const out = {}
  for (const [key, path] of Object.entries(REFERENCE_READS)) {
    out[key] = await api(path)
  }
  return out
}

/* --------------------------------------------------------------------- main */

async function main() {
  process.stdout.write(`Generating the demonstration dataset from ${API_BASE}\n\n`)

  // Fail immediately and clearly if nothing is listening, rather than at the first capture.
  try {
    await fetch(`${API_BASE}/health/readiness`)
  } catch {
    throw new Error(
      `No backend answering at ${API_BASE}. Start it first - see the header of this file.`,
    )
  }

  await mkdir(OUTPUT_DIRECTORY, { recursive: true })

  process.stdout.write('Signing in as the seeded accounts:\n')
  const { users: accounts, notifications } = await captureAccounts()
  accessToken = await signIn(DEMO_ACCOUNTS[0])

  process.stdout.write('\nRegisters:\n')
  const registers = await captureRegisters()

  process.stdout.write('\nProjections:\n')
  const projections = await captureProjections(registers)
  process.stdout.write(`  ${Object.keys(projections).length} captured\n`)

  process.stdout.write('\nReports:\n')
  const reports = await captureReports()

  const plain = await capturePlainReads()
  const reference = await captureReference()

  process.stdout.write('\nWriting modules:\n')
  await writeModule('accounts', accounts)
  await writeModule('notifications', notifications)
  await writeModule('platform', plain.profile)
  await writeModule('reference', reference)
  await writeModule(
    'aggregates',
    Object.fromEntries(Object.entries(plain).filter(([key]) => key !== 'profile')),
  )
  await writeModule('reports', reports)
  await writeModule('projections', projections)
  for (const [module, value] of Object.entries(registers)) {
    await writeModule(module, value)
  }

  const totalBytes = Object.values(written).reduce((sum, entry) => sum + entry.bytes, 0)
  await writeModule('manifest', {
    generatedAt: new Date().toISOString(),
    apiBase: API_BASE,
    pageSize: PAGE_SIZE,
    accounts: accounts.map((entry) => entry.email),
    reportKeys: REPORT_KEYS,
    modules: written,
    totalBytes,
  })

  process.stdout.write('\nModule sizes:\n')
  for (const [name, entry] of Object.entries(written).sort((a, b) => b[1].bytes - a[1].bytes)) {
    process.stdout.write(`  ${name.padEnd(18)} ${(entry.bytes / 1024 / 1024).toFixed(2).padStart(7)} MB\n`)
  }
  process.stdout.write(`  ${'TOTAL'.padEnd(18)} ${(totalBytes / 1024 / 1024).toFixed(2).padStart(7)} MB\n`)
}

main().catch((error) => {
  process.stderr.write(`\n${error.message}\n`)
  process.exitCode = 1
})
