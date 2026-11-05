import type { Row } from './query'
import { loadNotifications, loadReference, loadRegister, newCode, newId } from './data'
import { currentAccountEmail } from './auth'
import { notFound } from './http'

/**
 * Writes, applied in memory.
 *
 * There is no server, so a write cannot be persisted. It is not ignored either: applying it to the
 * array the register reads from is what makes the demonstration feel like a working product - a new
 * property appears in the list, in the search results and in pagination, and a status change is
 * visible on the row and on the record behind it. Everything resets on reload.
 *
 * ## What is deliberately not enforced
 *
 * The service's business rules are absent. It will not refuse to approve an application while a
 * screening is still pending, it will not stop a payment exceeding its invoice, and it will not
 * cascade a lease termination through the ledger. Reproducing those faithfully would mean
 * reimplementing the domain, and a half-reimplementation would be worse than none: a rule that fires
 * wrongly teaches the reader something false about the product. The demonstration is honest about
 * being a demonstration instead.
 */

/** The status an action moves a record to. Read off the lifecycle each service enforces. */
const ACTION_STATUS: Record<string, string> = {
  publish: 'PUBLISHED',
  unpublish: 'WITHDRAWN',
  expire: 'EXPIRED',
  archive: 'ARCHIVED',
  complete: 'COMPLETED',
  cancel: 'CANCELLED',
  approve: 'APPROVED',
  reject: 'REJECTED',
  withdraw: 'WITHDRAWN',
  resolve: 'RESOLVED',
  close: 'CLOSED',
}

/** Where a module keeps its business code, and the prefix the seed's number series uses. */
const CODES: Record<string, [field: string, prefix: string]> = {
  properties: ['propertyCode', 'ME-P-'],
  units: ['unitCode', 'ME-U-'],
  owners: ['ownerCode', 'ME-O-'],
  tenants: ['tenantCode', 'ME-T-'],
  buyers: ['buyerCode', 'ME-B-'],
  leads: ['leadCode', 'ME-LD-'],
  listings: ['listingCode', 'ME-LS-'],
  viewings: ['viewingCode', 'ME-V-'],
  applications: ['applicationCode', 'ME-A-'],
  leases: ['leaseCode', 'ME-L-'],
  invoices: ['invoiceNumber', 'ME-R-'],
  payments: ['receiptNumber', 'ME-PAY-'],
  maintenance: ['requestCode', 'ME-M-'],
  expenses: ['expenseCode', 'ME-EX-'],
  sales: ['saleCode', 'ME-S-'],
  documents: ['documentCode', 'ME-D-'],
}

/** The state a newly created record starts in, matching each service's own default. */
const CREATED_STATUS: Record<string, string> = {
  properties: 'AVAILABLE_ACTIVE',
  units: 'AVAILABLE',
  leads: 'NEW',
  listings: 'DRAFT',
  viewings: 'SCHEDULED',
  applications: 'SUBMITTED',
  leases: 'DRAFT',
  invoices: 'DRAFT',
  maintenance: 'REPORTED',
  sales: 'LEAD',
  owners: 'ACTIVE',
  tenants: 'ACTIVE',
  buyers: 'ACTIVE',
  documents: 'DRAFT',
}

/** Resolve a display name the register column reads, from the reference data the row only has an id for. */
async function resolveNames(module: string, record: Row): Promise<void> {
  if (module !== 'properties' && module !== 'units') return

  const reference = await loadReference()
  const propertyTypes = (reference.propertyTypes ?? []) as Row[]

  if (module === 'properties' && record.propertyTypeId) {
    const type = propertyTypes.find((entry) => entry.id === record.propertyTypeId)
    if (type) {
      record.propertyTypeCode = type.code
      record.propertyTypeName = type.name
    }
  }

  if (module === 'units' && record.unitTypeId) {
    const unitTypes = (reference.unitTypes ?? []) as Row[]
    const type = unitTypes.find((entry) => entry.id === record.unitTypeId)
    if (type) {
      record.unitTypeCode = type.code
      record.unitTypeName = type.name
    }
  }
}

/** `POST /<register>` */
export async function createRecord(module: string, body: Row): Promise<Row> {
  const register = await loadRegister(module)
  const timestamp = new Date().toISOString()

  const record: Row = {
    id: newId(),
    ...body,
    status: body.status ?? CREATED_STATUS[module] ?? 'ACTIVE',
    createdAt: timestamp,
    updatedAt: timestamp,
  }

  const code = CODES[module]
  if (code && !record[code[0]]) record[code[0]] = newCode(code[1])

  await resolveNames(module, record)

  // Unshift, so the new record is the first row a register sorted by createdAt desc shows - which is
  // where the reader expects to find the thing they just created.
  register.items.unshift(record)
  register.details[record.id] = record

  return record
}

/** `PUT /<register>/{id}` */
export async function updateRecord(module: string, id: string, body: Row): Promise<Row> {
  const record = await require_(module, id)

  Object.assign(record, body, { updatedAt: new Date().toISOString() })
  await resolveNames(module, record)

  const detail = (await loadRegister(module)).details[id]
  if (detail && detail !== record) Object.assign(detail, body, { updatedAt: record.updatedAt })

  return record
}

/**
 * An action on one record - `/status`, `/archive`, `/assign`, `/complete`, `/publish`, `/void`, …
 *
 * The body's own fields are merged first and the action's implied state applied second, so
 * `{status, reason}` chooses the state explicitly while `complete` without a status still lands in
 * COMPLETED. A `reason` becomes whichever reason field the record actually carries, because the
 * screens send one name and the records hold several (`terminationReason`, `voidReason`,
 * `cancelledReason`, `lostReason`).
 */
export async function performAction(
  module: string,
  id: string,
  action: string,
  body: Row = {},
): Promise<Row> {
  const record = await require_(module, id)
  const timestamp = new Date().toISOString()

  const implied = ACTION_STATUS[action]
  Object.assign(record, body, { updatedAt: timestamp })

  if (implied && !body.status) record.status = implied
  if (action === 'archive') record.archivedAt = timestamp
  if (action === 'resolve') record.resolvedAt = timestamp
  if (action === 'complete') record.completedAt = timestamp
  if (action === 'expire') record.closedAt = timestamp

  if (body.reason !== undefined) {
    const reasonField = Object.keys(record).find(
      (key) => key.toLowerCase() === `${action}reason` || key.endsWith('Reason'),
    )
    if (reasonField && action !== 'assign') record[reasonField] = body.reason
  }

  // Assignments land on the field the register column reads.
  if (action === 'assign' && body.agentId !== undefined) {
    record.ownerAgentId = body.agentId
    record.assignedToId = body.agentId
  }

  const detail = (await loadRegister(module)).details[id]
  if (detail && detail !== record) Object.assign(detail, record)

  return record
}

/** `POST /notifications/{id}/read` and the mark-all variant. */
export async function markNotificationRead(id: string | null): Promise<Row | null> {
  const { items } = await notificationsForCurrentAccount()
  const timestamp = new Date().toISOString()

  for (const row of items) {
    if (id === null || row.id === id) {
      row.read = true
      row.readAt = row.readAt ?? timestamp
    }
  }

  return id === null ? null : (items.find((row) => row.id === id) ?? null)
}

async function notificationsForCurrentAccount(): Promise<{ items: Row[]; unread: Row }> {
  const all = await loadNotifications()
  const email = currentAccountEmail()
  return (email && all[email]) || { items: [], unread: { unread: 0 } }
}

/** Live unread count for the signed-in account, after any reads applied this session. */
export async function notificationUnread(): Promise<Row> {
  const { items } = await notificationsForCurrentAccount()
  return { unread: items.filter((row) => row.read !== true).length }
}

/** `POST /communications/{id}/messages` and `/{id}/read`. */
export async function conversationAction(id: string, action: string, body: Row = {}): Promise<Row> {
  const register = await loadRegister('communications')
  const conversation = register.items.find((row) => row.id === id)
  if (!conversation) throw notFound('conversation')

  if (action === 'read') {
    conversation.unreadCount = 0
    return conversation
  }

  const detail = register.details[id] as Row | undefined
  const timestamp = new Date().toISOString()
  const message: Row = {
    id: newId(),
    senderType: 'FIRM',
    messageType: 'NOTE',
    isInternal: body.isInternal === true,
    senderName: 'Demonstration session',
    body: body.body ?? '',
    sentAt: timestamp,
  }

  if (detail && Array.isArray(detail.messages)) {
    ;(detail.messages as Row[]).push(message)
    detail.includesInternalNotes = true
  }

  conversation.messageCount = Number(conversation.messageCount ?? 0) + 1
  conversation.lastMessageAt = timestamp
  conversation.lastMessagePreview = String(body.body ?? '').slice(0, 120)

  return message
}

/**
 * An import, staged and committed in the browser.
 *
 * The preview parses the uploaded file for its columns and row count and checks them against the
 * columns the screen declares are required, so the answer describes the file the reader chose rather
 * than a canned one. Committing records the job in the register.
 */
export async function previewImport(formData: FormData): Promise<Row> {
  const entityType = String(formData.get('entityType') ?? 'PROPERTY')
  const file = formData.get('file')
  const text = file instanceof File ? await file.text() : ''

  const lines = text.split(/\r?\n/).filter((line) => line.trim().length > 0)
  const header = lines.length > 0 ? splitCsvLine(lines[0]) : []
  const body = lines.slice(1)

  const rows = body.map((line, index) => {
    const cells = splitCsvLine(line)
    const values: Row = {}
    header.forEach((name, position) => {
      values[name] = cells[position] ?? ''
    })
    const missing = header.filter((name) => values[name] === '')
    return {
      rowNumber: index + 2,
      values,
      errors: missing.map((name) => `${name} is required`),
      warnings: [] as string[],
    }
  })

  const problems = rows.flatMap((row) =>
    row.errors.map((message) => ({ rowNumber: row.rowNumber, field: header[0] ?? '', message })),
  )

  return {
    job: {
      jobCode: newCode('ME-IMP-'),
      entityType,
      sourceType: 'CSV',
      originalFilename: file instanceof File ? file.name : 'unnamed.csv',
      fileSizeBytes: file instanceof File ? file.size : 0,
      status: problems.length > 0 ? 'INVALID' : 'VALIDATED',
      rowCount: rows.length,
      validRowCount: rows.length - problems.length,
      invalidRowCount: problems.length,
      insertedRowCount: 0,
      skippedRowCount: 0,
      stagedAt: new Date().toISOString(),
      createdAt: new Date().toISOString(),
    },
    canCommit: rows.length > 0 && problems.length === 0,
    columnNames: header,
    rows,
    problems,
  }
}

/** A single CSV line, quoted fields included. Enough for the shape an import file actually has. */
function splitCsvLine(line: string): string[] {
  const cells: string[] = []
  let value = ''
  let quoted = false

  for (let index = 0; index < line.length; index += 1) {
    const character = line[index]
    if (quoted) {
      if (character === '"' && line[index + 1] === '"') {
        value += '"'
        index += 1
      } else if (character === '"') {
        quoted = false
      } else {
        value += character
      }
    } else if (character === '"') {
      quoted = true
    } else if (character === ',') {
      cells.push(value.trim())
      value = ''
    } else {
      value += character
    }
  }

  cells.push(value.trim())
  return cells
}

/** Find a record in a register, or fail the way the API fails. */
async function require_(module: string, id: string): Promise<Row> {
  const register = await loadRegister(module)
  const record = register.items.find((row) => row.id === id)
  if (!record) throw notFound(module.replace(/s$/, ''))
  return record
}
