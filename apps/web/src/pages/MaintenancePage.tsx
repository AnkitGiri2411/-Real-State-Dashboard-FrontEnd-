import { useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { AlertTriangle, CalendarClock, CheckCircle2, ClipboardList, Hammer, Plus, RefreshCw, Search, Wrench } from 'lucide-react'
import { PageAccessGuard, PageHeader } from '../components/common'
import {
  Badge, Button, DataTable, Dialog, EmptyState, ErrorState, Field, Input, KpiTile, Panel,
  Pagination, SectionHeader, Select, Skeleton, StatusBadge, Textarea, Toolbar, useToast, type Column,
} from '../components/ui'
import { count as fmtCount, date, humanize, money, moneyCompact } from '../components/ui/formatters'
import { maintenanceApi, propertyApi, unitApi } from '../services'
import { messageOf } from '../lib/errors'
import type { MaintenanceSummaryRow } from '../types'

/**
 * The fault board: every maintenance request, as a status board or as a register.
 *
 * The two views are the same data seen by the same eye — one by where the work stands, one by what it
 * is — and the switch between them is kept in the address bar. The KPI header is
 * `GET /maintenance-requests/summary`, whose open, urgent-open, late and cost figures are computed in
 * the service over the schema's own open set, so the number on the card is the number the partial
 * index was built to produce. `costToDate` is actual spend, never the estimate.
 *
 * **Status moves are policy, not edits.** A request moves only along
 * `MaintenanceService.ALLOWED`, and only the moves this build can complete end-to-end are offered:
 * `RESOLVED` is reached through the resolve operation (so the moment, the notes and the cost are
 * written together) and `ASSIGNED` needs a named holder chosen through the assign operation. Offering
 * a move the desk cannot finish would be a button that always fails, so the reachable subset of the
 * map is what the control shows.
 *
 * Assignment itself is not offered here: handing work to a trade or a colleague names a vendor or a
 * staff member by identifier, and this build's shared API map exposes no vendor or staff directory to
 * choose one from. Rather than render a picker with nothing in it, the action is left out; the vendor
 * refusals (`VENDOR_NOT_EMPANELLED`, `VENDOR_NOT_ASSIGNABLE`) are therefore not reachable from this
 * screen, while every other refusal the service raises is surfaced beside the form that caused it.
 */

/**
 * The reachable status moves, mirrored from `MaintenanceService.ALLOWED`. Two targets are omitted on
 * purpose — see the screen note — so this is the subset the desk can complete.
 */
const MAINTENANCE_TRANSITIONS: Record<string, string[]> = {
  OPEN: ['IN_PROGRESS', 'CANCELLED'],
  ASSIGNED: ['OPEN', 'IN_PROGRESS', 'CANCELLED'],
  IN_PROGRESS: ['CANCELLED'],
  RESOLVED: ['CLOSED'],
  CLOSED: [],
  CANCELLED: [],
}

/** The board's column order, mirrored from `MaintenanceService.STATUS_ORDER`. */
const STATUS_ORDER = ['OPEN', 'ASSIGNED', 'IN_PROGRESS', 'RESOLVED', 'CLOSED', 'CANCELLED']

/** Mirrored from `MaintenanceCategory` — the vocabulary a request and a vendor are matched on. */
const MAINTENANCE_CATEGORIES = [
  'PLUMBING', 'ELECTRICAL', 'HVAC', 'CIVIL', 'PAINTING', 'CARPENTRY', 'PEST_CONTROL',
  'HOUSEKEEPING', 'SECURITY', 'LIFT', 'FIRE_SAFETY', 'LANDSCAPING', 'GENERAL', 'OTHER',
]

/** Mirrored from `MaintenancePriority`. `HIGH` and `URGENT` are the urgent set the KPI counts. */
const MAINTENANCE_PRIORITIES = ['LOW', 'MEDIUM', 'HIGH', 'URGENT']

/** The board reads a whole working set at once; a register pages. */
const BOARD_SIZE = 100

export default function MaintenancePage() {
  return (
    <PageAccessGuard permission="MAINTENANCE_VIEW">
      <MaintenanceBoard />
    </PageAccessGuard>
  )
}

interface CreateRequestForm {
  propertyId: string
  unitId: string
  category: string
  priority: string
  title: string
  description: string
  reporterName: string
  targetDate: string
  estimatedCost: string
}

const EMPTY_REQUEST: CreateRequestForm = {
  propertyId: '', unitId: '', category: 'PLUMBING', priority: 'MEDIUM', title: '',
  description: '', reporterName: '', targetDate: '', estimatedCost: '',
}

function MaintenanceBoard() {
  const queryClient = useQueryClient()
  const toast = useToast()
  const [searchParams, setSearchParams] = useSearchParams()

  // The view lives in the URL so a bookmarked board opens as a board.
  const view: 'board' | 'register' = searchParams.get('view') === 'register' ? 'register' : 'board'

  const [propertyId, setPropertyId] = useState('')
  const [unitId, setUnitId] = useState('')
  const [status, setStatus] = useState('')
  const [priority, setPriority] = useState('')
  const [category, setCategory] = useState('')
  const [reportedFrom, setReportedFrom] = useState('')
  const [reportedTo, setReportedTo] = useState('')
  const [overdueOnly, setOverdueOnly] = useState(false)
  const [search, setSearch] = useState('')
  const [sort, setSort] = useState('createdAt,desc')
  const [page, setPage] = useState(0)
  const [size, setSize] = useState(20)

  const [createOpen, setCreateOpen] = useState(false)
  const [form, setForm] = useState<CreateRequestForm>(EMPTY_REQUEST)
  const [formError, setFormError] = useState<string | null>(null)

  const [statusFor, setStatusFor] = useState<MaintenanceSummaryRow | null>(null)
  const [targetStatus, setTargetStatus] = useState('')
  const [statusReason, setStatusReason] = useState('')

  const [resolveFor, setResolveFor] = useState<MaintenanceSummaryRow | null>(null)
  const [resolutionNotes, setResolutionNotes] = useState('')
  const [actualCost, setActualCost] = useState('')

  function switchView(next: 'board' | 'register') {
    const params = new URLSearchParams(searchParams)
    params.set('view', next)
    setSearchParams(params, { replace: true })
    setPage(0)
  }

  const detailParams = {
    propertyId: propertyId || undefined,
    unitId: unitId || undefined,
    status: status || undefined,
    priority: priority || undefined,
    category: category || undefined,
    reportedFrom: reportedFrom || undefined,
    reportedTo: reportedTo || undefined,
    overdueOnly: overdueOnly || undefined,
    search: search.trim() || undefined,
  }

  const summaryQuery = useQuery({
    queryKey: ['maintenance', 'summary'],
    queryFn: () => maintenanceApi.summary(),
  })
  const listQuery = useQuery({
    queryKey: ['maintenance', 'list', { ...detailParams, view, sort, page, size }],
    queryFn: () => maintenanceApi.list({
      ...detailParams,
      sort: view === 'board' ? 'createdAt,desc' : sort,
      page: view === 'board' ? 0 : page,
      size: view === 'board' ? BOARD_SIZE : size,
    }),
  })
  const propertiesQuery = useQuery({
    queryKey: ['properties', 'options'],
    queryFn: () => propertyApi.list({ size: 200 }),
  })
  const unitsQuery = useQuery({
    queryKey: ['units', 'options'],
    queryFn: () => unitApi.list({ size: 200 }),
  })

  const createMutation = useMutation({
    mutationFn: (body: Record<string, unknown>) => maintenanceApi.create(body),
    onSuccess: (request) => {
      toast.push(`Request ${request.requestCode} raised`)
      setCreateOpen(false)
      setForm(EMPTY_REQUEST)
      void queryClient.invalidateQueries({ queryKey: ['maintenance'] })
    },
  })

  const statusMutation = useMutation({
    mutationFn: (vars: { id: string; status: string; reason: string }) =>
      maintenanceApi.changeStatus(vars.id, { status: vars.status, reason: vars.reason.trim() || undefined }),
    onSuccess: () => {
      toast.push('Request status changed')
      setStatusFor(null)
      setTargetStatus('')
      setStatusReason('')
      void queryClient.invalidateQueries({ queryKey: ['maintenance'] })
    },
  })

  const resolveMutation = useMutation({
    mutationFn: (vars: { id: string; notes: string; cost: string }) =>
      maintenanceApi.resolve(vars.id, {
        resolutionNotes: vars.notes,
        actualCost: vars.cost ? Number(vars.cost) : undefined,
      }),
    onSuccess: () => {
      toast.push('Request resolved')
      setResolveFor(null)
      setResolutionNotes('')
      setActualCost('')
      void queryClient.invalidateQueries({ queryKey: ['maintenance'] })
    },
  })

  // `maintenanceApi.summary` declares `MaintenanceDtos.MaintenanceSummaryResponse`, so the operations
  // card below is read off the record the endpoint promised rather than probed out of a map.
  const summary = summaryQuery.data
  const rows = listQuery.data?.items ?? []
  const properties = propertiesQuery.data?.items ?? []
  const units = unitsQuery.data?.items ?? []
  const unitsForProperty = propertyId ? units.filter((unit) => unit.propertyId === propertyId) : units
  const filtersActive = Boolean(propertyId || unitId || status || priority || category || reportedFrom || reportedTo || overdueOnly || search.trim())
  // The estimate the API reported, if any — used to frame the actual-cost field rather than a zero.
  const resolveEstimate = resolveFor?.estimatedCost

  function openStatus(row: MaintenanceSummaryRow) {
    statusMutation.reset()
    setTargetStatus('')
    setStatusReason('')
    setStatusFor(row)
  }

  function openResolve(row: MaintenanceSummaryRow) {
    resolveMutation.reset()
    setResolutionNotes('')
    setActualCost('')
    setResolveFor(row)
  }

  function submitCreate() {
    setFormError(null)
    if (!form.propertyId) return setFormError('Choose the property the fault is on')
    if (!form.title.trim()) return setFormError('A short title is required')
    createMutation.mutate({
      propertyId: form.propertyId,
      unitId: form.unitId || undefined,
      category: form.category,
      priority: form.priority,
      title: form.title.trim(),
      description: form.description.trim() || undefined,
      reporterName: form.reporterName.trim() || undefined,
      targetDate: form.targetDate || undefined,
      estimatedCost: form.estimatedCost ? Number(form.estimatedCost) : undefined,
    })
  }

  function submitStatus() {
    if (!statusFor || !targetStatus) return
    statusMutation.mutate({ id: statusFor.id, status: targetStatus, reason: statusReason })
  }

  function submitResolve() {
    if (!resolveFor || !resolutionNotes.trim()) return
    resolveMutation.mutate({ id: resolveFor.id, notes: resolutionNotes.trim(), cost: actualCost })
  }

  return (
    <>
      <PageHeader
        title="Maintenance"
        description="Every fault on the stock, where it stands, who holds it and what it has cost."
        actions={
          <Button variant="primary" onClick={() => { createMutation.reset(); setForm(EMPTY_REQUEST); setFormError(null); setCreateOpen(true) }}>
            <Plus size={15} aria-hidden="true" /> Raise request
          </Button>
        }
      />

      {/* ── The operations card, from the API ────────────────────────────── */}
      {summaryQuery.isLoading && (
        <div className="mb-5 grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
          {Array.from({ length: 4 }).map((_, index) => <Skeleton key={index} rows={3} />)}
        </div>
      )}
      {summaryQuery.isError && (
        <div className="mb-5"><ErrorState message={messageOf(summaryQuery.error)} onRetry={() => void summaryQuery.refetch()} /></div>
      )}
      {summary && (
        <div className="mb-5 grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
          <KpiTile label="Open work" value={fmtCount(summary.openCount)} tone="sapphire" icon={<Wrench size={15} />}
            caption="Open, assigned and in progress" />
          <KpiTile label="Urgent open" value={fmtCount(summary.urgentOpenCount)} tone="terracotta" icon={<AlertTriangle size={15} />}
            caption="High or urgent priority, still outstanding" />
          <KpiTile label="Unanswered" value={fmtCount(summary.lateOpenCount)} tone="amber" icon={<CalendarClock size={15} />}
            caption="Open past the configured response window" />
          <KpiTile label="Cost to date" value={moneyCompact(summary.costToDate)} tone="copper" icon={<Hammer size={15} />}
            caption={`${moneyCompact(summary.estimatedOpenCost)} more estimated on open work`} />
        </div>
      )}

      <SectionHeader
        eyebrow="Works"
        title={view === 'board' ? 'Status board' : 'Request register'}
        rule="terracotta"
        description={view === 'board'
          ? 'Every request placed by the state it is in. Move the cursor through the columns with the keyboard.'
          : 'The same requests as a ruled register, with what they were estimated and what they actually cost.'}
        actions={
          <div role="group" aria-label="Choose a view" className="flex gap-1 rounded-[var(--radius-control)] p-1"
            style={{ background: 'var(--surface-sunken)' }}>
            <Button size="sm" variant={view === 'board' ? 'primary' : 'ghost'} aria-pressed={view === 'board'} onClick={() => switchView('board')}>
              Board
            </Button>
            <Button size="sm" variant={view === 'register' ? 'primary' : 'ghost'} aria-pressed={view === 'register'} onClick={() => switchView('register')}>
              Register
            </Button>
          </div>
        }
      />

      <Panel notch>
        <>
          <Toolbar>
            <Field label="Property" htmlFor="maint-filter-property">
              <Select id="maint-filter-property" value={propertyId} onChange={(event) => { setPropertyId(event.target.value); setUnitId(''); setPage(0) }}>
                <option value="">All properties</option>
                {properties.map((property) => <option key={property.id} value={property.id}>{property.name}</option>)}
              </Select>
            </Field>
            <Field label="Unit" htmlFor="maint-filter-unit">
              <Select id="maint-filter-unit" value={unitId} onChange={(event) => { setUnitId(event.target.value); setPage(0) }}>
                <option value="">All units</option>
                {unitsForProperty.map((unit) => <option key={unit.id} value={unit.id}>{unit.unitCode}{unit.propertyName ? ` · ${unit.propertyName}` : ''}</option>)}
              </Select>
            </Field>
            <Field label="Status" htmlFor="maint-filter-status">
              <Select id="maint-filter-status" value={status} onChange={(event) => { setStatus(event.target.value); setPage(0) }}>
                <option value="">Any status</option>
                {STATUS_ORDER.map((value) => <option key={value} value={value}>{humanize(value)}</option>)}
              </Select>
            </Field>
            <Field label="Priority" htmlFor="maint-filter-priority">
              <Select id="maint-filter-priority" value={priority} onChange={(event) => { setPriority(event.target.value); setPage(0) }}>
                <option value="">Any priority</option>
                {MAINTENANCE_PRIORITIES.map((value) => <option key={value} value={value}>{humanize(value)}</option>)}
              </Select>
            </Field>
            <Field label="Category" htmlFor="maint-filter-category">
              <Select id="maint-filter-category" value={category} onChange={(event) => { setCategory(event.target.value); setPage(0) }}>
                <option value="">Any category</option>
                {MAINTENANCE_CATEGORIES.map((value) => <option key={value} value={value}>{humanize(value)}</option>)}
              </Select>
            </Field>
            <Field label="Reported from" htmlFor="maint-filter-from">
              <Input id="maint-filter-from" type="date" value={reportedFrom} onChange={(event) => { setReportedFrom(event.target.value); setPage(0) }} />
            </Field>
            <Field label="Reported to" htmlFor="maint-filter-to">
              <Input id="maint-filter-to" type="date" value={reportedTo} onChange={(event) => { setReportedTo(event.target.value); setPage(0) }} />
            </Field>
            <label className="flex items-center gap-2 pb-2.5 text-xs" style={{ color: 'var(--text-secondary)' }}>
              <input type="checkbox" className="checkbox" checked={overdueOnly}
                onChange={(event) => { setOverdueOnly(event.target.checked); setPage(0) }} />
              Overdue only
            </label>
            <Field label="Search" htmlFor="maint-filter-search">
              <Input id="maint-filter-search" value={search} placeholder="Code, title, reporter" onChange={(event) => { setSearch(event.target.value); setPage(0) }} />
            </Field>
            <div className="flex items-end gap-2">
              <Button onClick={() => void listQuery.refetch()} aria-label="Reload the board">
                <RefreshCw size={14} aria-hidden="true" /> Reload
              </Button>
            </div>
          </Toolbar>

          {listQuery.isLoading && <div className="p-4"><Skeleton rows={6} /></div>}
          {listQuery.isError && (
            <div className="p-4"><ErrorState message={messageOf(listQuery.error)} onRetry={() => void listQuery.refetch()} /></div>
          )}

          {listQuery.data && view === 'board' && (
            <div className="p-4">
              <ol className="grid gap-3 md:grid-cols-3 xl:grid-cols-6">
                {STATUS_ORDER.map((column) => {
                  const cards = rows.filter((row) => row.status === column)
                  return (
                    <li key={column} className="min-w-0">
                      <div
                        tabIndex={0}
                        role="group"
                        aria-label={`${humanize(column)} — ${fmtCount(cards.length)} requests`}
                        className="focus-meridian h-full min-w-0 rounded-[var(--radius-card)] border p-3"
                        style={{ borderColor: 'var(--border)', background: 'var(--surface)' }}
                      >
                      <header className="mb-2 flex items-center justify-between gap-2">
                        <StatusBadge status={column} />
                        <span className="text-xs tabular-nums" style={{ color: 'var(--text-secondary)' }}>{fmtCount(cards.length)}</span>
                      </header>
                      {cards.length === 0 ? (
                        <p className="text-[11px]" style={{ color: 'var(--text-secondary)' }}>Nothing here</p>
                      ) : (
                        <ul className="space-y-2">
                          {cards.map((card) => (
                            <li key={card.id} className="rounded-[var(--radius-field)] border p-2"
                              style={{ borderColor: 'var(--border)', background: 'var(--surface-elevated)' }}>
                              <p className="truncate text-xs font-semibold" style={{ color: 'var(--text-primary)' }}>{card.requestCode}</p>
                              <p className="truncate text-[11px]" style={{ color: 'var(--text-secondary)' }}>
                                {card.propertyName ?? '—'}{card.unitCode ? ` · ${card.unitCode}` : ''}
                              </p>
                              <p className="mt-1 flex flex-wrap items-center gap-1">
                                <StatusBadge status={card.priority} />
                                <span className="text-[11px]" style={{ color: 'var(--text-secondary)' }}>{humanize(card.category)}</span>
                              </p>
                              <p className="mt-1 truncate text-[11px]" style={{ color: 'var(--text-secondary)' }}>
                                {card.assignedVendorName ?? card.assignedToName ?? 'Unassigned'}
                              </p>
                              {card.late && <p className="mt-1"><Badge tone="terracotta">Late</Badge></p>}
                              <div className="mt-2 flex flex-wrap gap-1">
                                <Button size="sm" variant="outline" disabled={(MAINTENANCE_TRANSITIONS[card.status] ?? []).length === 0}
                                  onClick={() => openStatus(card)} aria-label={`Change the status of request ${card.requestCode}`}>
                                  Move
                                </Button>
                                {card.status === 'IN_PROGRESS' && (
                                  <Button size="sm" variant="primary" onClick={() => openResolve(card)} aria-label={`Resolve request ${card.requestCode}`}>
                                    <CheckCircle2 size={13} aria-hidden="true" /> Resolve
                                  </Button>
                                )}
                              </div>
                            </li>
                          ))}
                        </ul>
                      )}
                      </div>
                    </li>
                  )
                })}
              </ol>
              {listQuery.data.totalElements > BOARD_SIZE && (
                <p className="mt-3 text-[11px]" style={{ color: 'var(--text-secondary)' }}>
                  Showing the first {BOARD_SIZE} of {fmtCount(listQuery.data.totalElements)} requests. Narrow the filters to see the rest.
                </p>
              )}
            </div>
          )}

          {listQuery.data && view === 'register' && (
            <>
              <DataTable
                caption="Maintenance register"
                columns={registerColumns({ onStatus: openStatus, onResolve: openResolve })}
                rows={rows}
                keyOf={(row) => row.id}
                sort={sort}
                onSortChange={(next) => { setSort(next); setPage(0) }}
                empty={filtersActive ? (
                  <EmptyState icon={<Search size={20} />} title="No requests match these filters"
                    description="Change the property, unit, status, priority, category or dates — the requests that do exist will reappear." />
                ) : (
                  <EmptyState icon={<ClipboardList size={20} />} title="No maintenance has been reported yet"
                    description="A request is raised against a property or a unit. Raise the first fault and it appears on the board and in this register." />
                )}
              />
              <Pagination
                page={listQuery.data.page} size={listQuery.data.size}
                totalElements={listQuery.data.totalElements} totalPages={listQuery.data.totalPages}
                hasNext={listQuery.data.hasNext}
                onPageChange={setPage} onSizeChange={(next) => { setSize(next); setPage(0) }}
              />
            </>
          )}

          <p className="sr-only" role="status" aria-live="polite">
            {listQuery.isFetching ? 'Updating the maintenance board' : 'Maintenance board up to date'}
          </p>
        </>
      </Panel>

      {/* ── Raise a request ──────────────────────────────────────────────── */}
      <Dialog
        open={createOpen}
        onClose={() => setCreateOpen(false)}
        title="Raise a maintenance request"
        description="Every request starts as open, whether or not a priority was chosen."
        width="lg"
        footer={
          <>
            <Button variant="ghost" onClick={() => setCreateOpen(false)}>Cancel</Button>
            <Button variant="primary" loading={createMutation.isPending} onClick={submitCreate}>Raise request</Button>
          </>
        }
      >
        <div className="grid gap-4 sm:grid-cols-2">
          <Field label="Property" htmlFor="maint-new-property" required>
            <Select id="maint-new-property" value={form.propertyId} onChange={(event) => setForm({ ...form, propertyId: event.target.value, unitId: '' })}>
              <option value="">Choose a property…</option>
              {properties.map((property) => <option key={property.id} value={property.id}>{property.name}</option>)}
            </Select>
          </Field>
          <Field label="Unit" htmlFor="maint-new-unit" hint="Leave blank for a fault on the property itself">
            <Select id="maint-new-unit" value={form.unitId} onChange={(event) => setForm({ ...form, unitId: event.target.value })}>
              <option value="">The whole property</option>
              {(form.propertyId ? units.filter((unit) => unit.propertyId === form.propertyId) : units).map((unit) => (
                <option key={unit.id} value={unit.id}>{unit.unitCode}</option>
              ))}
            </Select>
          </Field>
          <Field label="Category" htmlFor="maint-new-category" required hint="Decides which trades are eligible to hold the work">
            <Select id="maint-new-category" value={form.category} onChange={(event) => setForm({ ...form, category: event.target.value })}>
              {MAINTENANCE_CATEGORIES.map((value) => <option key={value} value={value}>{humanize(value)}</option>)}
            </Select>
          </Field>
          <Field label="Priority" htmlFor="maint-new-priority">
            <Select id="maint-new-priority" value={form.priority} onChange={(event) => setForm({ ...form, priority: event.target.value })}>
              {MAINTENANCE_PRIORITIES.map((value) => <option key={value} value={value}>{humanize(value)}</option>)}
            </Select>
          </Field>
          <Field label="Title" htmlFor="maint-new-title" required>
            <Input id="maint-new-title" value={form.title} onChange={(event) => setForm({ ...form, title: event.target.value })} />
          </Field>
          <Field label="Reported by" htmlFor="maint-new-reporter" hint="A name, when the reporter is not a tenant on the system">
            <Input id="maint-new-reporter" value={form.reporterName} onChange={(event) => setForm({ ...form, reporterName: event.target.value })} />
          </Field>
          <Field label="Target date" htmlFor="maint-new-target">
            <Input id="maint-new-target" type="date" value={form.targetDate} onChange={(event) => setForm({ ...form, targetDate: event.target.value })} />
          </Field>
          <Field label="Estimated cost" htmlFor="maint-new-estimate" hint="What the work is expected to cost">
            <Input id="maint-new-estimate" type="number" min="0" step="0.01" value={form.estimatedCost} onChange={(event) => setForm({ ...form, estimatedCost: event.target.value })} />
          </Field>
          <div className="sm:col-span-2">
            <Field label="Description" htmlFor="maint-new-description">
              <Textarea id="maint-new-description" value={form.description} onChange={(event) => setForm({ ...form, description: event.target.value })} />
            </Field>
          </div>
        </div>

        {createMutation.isError && <div className="mt-4"><ErrorState message={messageOf(createMutation.error)} /></div>}
        {formError && <p className="mt-4 text-sm" role="alert" style={{ color: 'var(--danger)' }}>{formError}</p>}
      </Dialog>

      {/* ── Move a request ───────────────────────────────────────────────── */}
      <Dialog
        open={statusFor !== null}
        onClose={() => setStatusFor(null)}
        title="Change request status"
        description={statusFor ? `${statusFor.requestCode} · currently ${humanize(statusFor.status)}` : undefined}
        footer={
          <>
            <Button variant="ghost" onClick={() => setStatusFor(null)}>Cancel</Button>
            <Button variant="primary" loading={statusMutation.isPending} disabled={!targetStatus} onClick={submitStatus}>Apply move</Button>
          </>
        }
      >
        {statusFor && (
          <div className="space-y-4">
            <p className="text-sm" style={{ color: 'var(--text-secondary)' }}>
              Only the moves this state permits are listed. Resolving is a separate act — it records when the
              fault was fixed, what was done and what it cost, together.
            </p>
            {(MAINTENANCE_TRANSITIONS[statusFor.status] ?? []).length === 0 ? (
              <EmptyState
                title="This request cannot move again"
                description={`A request that is ${humanize(statusFor.status)} is terminal. A closed or dropped fault has a trail worth more than an edit back.`}
              />
            ) : (
              <Field label="Move to" htmlFor="maint-status-target" required>
                <Select id="maint-status-target" value={targetStatus} onChange={(event) => setTargetStatus(event.target.value)}>
                  <option value="">Choose a status…</option>
                  {(MAINTENANCE_TRANSITIONS[statusFor.status] ?? []).map((value) => <option key={value} value={value}>{humanize(value)}</option>)}
                </Select>
              </Field>
            )}
            {(targetStatus === 'CANCELLED' || targetStatus === 'OPEN') && (
              <Field label="Reason" htmlFor="maint-status-reason" hint="Recorded in the status trail">
                <Textarea id="maint-status-reason" value={statusReason} onChange={(event) => setStatusReason(event.target.value)} />
              </Field>
            )}
            {statusMutation.isError && <ErrorState message={messageOf(statusMutation.error)} />}
          </div>
        )}
      </Dialog>

      {/* ── Resolve a request ────────────────────────────────────────────── */}
      <Dialog
        open={resolveFor !== null}
        onClose={() => setResolveFor(null)}
        title="Resolve this request"
        description={resolveFor ? `${resolveFor.requestCode} — what was done, and what it cost.` : undefined}
        footer={
          <>
            <Button variant="ghost" onClick={() => setResolveFor(null)}>Cancel</Button>
            <Button variant="primary" loading={resolveMutation.isPending} disabled={!resolutionNotes.trim()} onClick={submitResolve}>
              Record resolution
            </Button>
          </>
        }
      >
        <div className="space-y-4">
          <Field label="What was done" htmlFor="maint-resolve-notes" required hint="A resolution with nothing said about it is indistinguishable from a status somebody clicked">
            <Textarea id="maint-resolve-notes" value={resolutionNotes} onChange={(event) => setResolutionNotes(event.target.value)} />
          </Field>
          <Field
            label="Actual cost"
            htmlFor="maint-resolve-cost"
            hint={resolveEstimate === null || resolveEstimate === undefined
              ? 'Leave blank when no cost was recorded'
              : `Estimated at ${money(resolveEstimate)} — the actual is what it really cost`}
          >
            <Input id="maint-resolve-cost" type="number" min="0" step="0.01" value={actualCost} onChange={(event) => setActualCost(event.target.value)} />
          </Field>
          {resolveMutation.isError && <ErrorState message={messageOf(resolveMutation.error)} />}
        </div>
      </Dialog>
    </>
  )
}

/** The register's columns. The action cell is `secondary` so the stacked mobile view nests no button. */
function registerColumns(handlers: {
  onStatus: (row: MaintenanceSummaryRow) => void
  onResolve: (row: MaintenanceSummaryRow) => void
}): Column<MaintenanceSummaryRow>[] {
  return [
    { key: 'code', header: 'Request', width: '9%', sortKey: 'requestCode', render: (row) => (
      <span className="truncate font-semibold" style={{ color: 'var(--text-primary)' }}>{row.requestCode}</span>
    ) },
    { key: 'place', header: 'Property · unit', width: '12%', render: (row) => (
      <span className="truncate">{row.propertyName ?? '—'}{row.unitCode ? ` · ${row.unitCode}` : ''}</span>
    ) },
    { key: 'category', header: 'Category', width: '9%', sortKey: 'category', render: (row) => humanize(row.category) },
    { key: 'priority', header: 'Priority', width: '8%', sortKey: 'priority', render: (row) => <StatusBadge status={row.priority} /> },
    { key: 'status', header: 'Status', width: '9%', sortKey: 'status', render: (row) => <StatusBadge status={row.status} /> },
    { key: 'reporter', header: 'Reported by', width: '9%', secondary: true, render: (row) => <span className="truncate">{row.reporterName ?? '—'}</span> },
    { key: 'holder', header: 'Held by', width: '11%', render: (row) => (
      <span className="truncate">{row.assignedVendorName ?? row.assignedToName ?? 'Unassigned'}</span>
    ) },
    { key: 'cost', header: 'Est. / actual', width: '12%', align: 'right', render: (row) => (
      <span className="text-xs tabular-nums">{money(row.estimatedCost)} / {money(row.actualCost)}</span>
    ) },
    { key: 'reported', header: 'Reported', width: '9%', sortKey: 'reportedAt', render: (row) => date(row.reportedAt) },
    { key: 'late', header: 'Attention', width: '6%', render: (row) => (
      row.late ? <Badge tone="terracotta">Late</Badge> : row.overdue ? <Badge tone="amber">Overdue</Badge> : <span style={{ color: 'var(--text-secondary)' }}>—</span>
    ) },
    { key: 'actions', header: 'Actions', width: '6%', secondary: true, render: (row) => (
      <span className="flex flex-wrap gap-1">
        <Button size="sm" variant="outline" disabled={(MAINTENANCE_TRANSITIONS[row.status] ?? []).length === 0}
          onClick={() => handlers.onStatus(row)} aria-label={`Change the status of request ${row.requestCode}`}>
          Move
        </Button>
        {row.status === 'IN_PROGRESS' && (
          <Button size="sm" variant="primary" onClick={() => handlers.onResolve(row)} aria-label={`Resolve request ${row.requestCode}`}>
            Resolve
          </Button>
        )}
      </span>
    ) },
  ]
}
