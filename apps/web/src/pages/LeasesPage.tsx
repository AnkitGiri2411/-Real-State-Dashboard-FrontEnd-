import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { FileWarning, Plus, RefreshCw, Search } from 'lucide-react'
import { PageAccessGuard, PageHeader } from '../components/common'
import {
  Badge, Button, DataTable, Dialog, EmptyState, ErrorState, Field, Input, Panel, Pagination,
  SectionHeader, Select, Skeleton, StatusBadge, Textarea, Toolbar, useToast, type Column,
} from '../components/ui'
import { count as fmtCount, date, daysUntil, humanize, money } from '../components/ui/formatters'
import { leaseApi, propertyApi, tenantApi, unitApi } from '../services'
import { usePlatformStore } from '../platform'
import { ApiError, messageOf } from '../lib/errors'
import type { LeaseSummary } from '../types'

/**
 * The lease register.
 *
 * The letting desk's working list: every tenancy, what it is worth, when it ends and what state it is
 * in. Two facts on this screen are the backend's and are never re-derived here:
 *
 *  - the **rent and deposit** are the stored headline amounts on the lease;
 *  - the **status** is the stored lifecycle state, and the only moves offered are those
 *    `LeaseService.isAllowed(from, to)` permits.
 *
 * `daysRemaining` is a calendar countdown, not money: where the projection does not carry it, it is
 * read from the lease's own end date with the shared `daysUntil` formatter. Nothing on this page
 * computes a lease's money position - that lives on the detail screen, and the figures come from the
 * API there too.
 *
 * A second running lease on one unit is refused by the service with the named `UNIT_ALREADY_LET`
 * rather than the database's opaque unique-index text, so that refusal is surfaced beside the unit
 * field it belongs to.
 */

/**
 * The status moves a lease may make, mirrored from `LeaseService.ALLOWED_TRANSITIONS`.
 *
 * Declared here rather than fetched because it is the service's own map that is authoritative: the
 * UI offers only these so a desk never presses a control the backend will refuse. A terminal state
 * (`RENEWED`, `TERMINATED`, `CANCELLED`) has no onward move, which is why its list is empty.
 */
const LEASE_TRANSITIONS: Record<string, string[]> = {
  DRAFT: ['PENDING', 'ACTIVE', 'CANCELLED'],
  PENDING: ['DRAFT', 'ACTIVE', 'CANCELLED'],
  ACTIVE: ['EXPIRING', 'RENEWED', 'TERMINATED'],
  EXPIRING: ['ACTIVE', 'RENEWED', 'TERMINATED'],
  RENEWED: [],
  TERMINATED: [],
  CANCELLED: [],
}

/** Already-ended states, so a countdown is not shown against a tenancy that is over. */
const SETTLED_STATUSES = new Set(['RENEWED', 'TERMINATED', 'CANCELLED'])

/** The frequencies `RentFrequency` allows. Billing cadence is the backend's vocabulary, mirrored. */
const RENT_FREQUENCIES = ['MONTHLY', 'QUARTERLY', 'HALF_YEARLY', 'ANNUAL']

const EXPIRY_WINDOWS = [30, 60, 90, 180]

export default function LeasesPage() {
  return (
    <PageAccessGuard permission="LEASE_VIEW">
      <LeaseRegister />
    </PageAccessGuard>
  )
}

interface CreateLeaseForm {
  unitId: string
  tenantId: string
  startDate: string
  endDate: string
  rentAmount: string
  depositAmount: string
  rentFrequency: string
}

const EMPTY_FORM: CreateLeaseForm = {
  unitId: '', tenantId: '', startDate: '', endDate: '', rentAmount: '', depositAmount: '',
  rentFrequency: 'MONTHLY',
}

function LeaseRegister() {
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const toast = useToast()
  const warningDays = usePlatformStore((state) => state.profile?.policies?.leaseExpiryWarningDays ?? 60)

  // Filters. Held in component state; only the register's own position has to survive nothing, so
  // there is no reason to round-trip every keystroke through the address bar.
  const [propertyId, setPropertyId] = useState('')
  const [unitId, setUnitId] = useState('')
  const [tenantId, setTenantId] = useState('')
  const [status, setStatus] = useState('')
  const [from, setFrom] = useState('')
  const [to, setTo] = useState('')
  const [expiring, setExpiring] = useState('')
  const [search, setSearch] = useState('')
  const [sort, setSort] = useState('createdAt,desc')
  const [page, setPage] = useState(0)
  const [size, setSize] = useState(20)

  const [createOpen, setCreateOpen] = useState(false)
  const [form, setForm] = useState<CreateLeaseForm>(EMPTY_FORM)
  const [formError, setFormError] = useState<string | null>(null)

  const [statusFor, setStatusFor] = useState<LeaseSummary | null>(null)
  const [targetStatus, setTargetStatus] = useState('')
  const [statusReason, setStatusReason] = useState('')

  const leasesQuery = useQuery({
    queryKey: ['leases', { propertyId, unitId, tenantId, status, from, to, expiring, search, sort, page, size }],
    queryFn: () => leaseApi.list({
      propertyId: propertyId || undefined,
      unitId: unitId || undefined,
      tenantId: tenantId || undefined,
      status: status || undefined,
      from: from || undefined,
      to: to || undefined,
      expiringWithinDays: expiring ? Number(expiring) : undefined,
      search: search.trim() || undefined,
      sort,
      page,
      size,
    }),
  })

  // The pickers' option lists. Read once each; every one is a real endpoint from the shared map.
  const propertiesQuery = useQuery({
    queryKey: ['properties', 'options'],
    queryFn: () => propertyApi.list({ size: 200 }),
  })
  const unitsQuery = useQuery({
    queryKey: ['units', 'options'],
    queryFn: () => unitApi.list({ size: 200 }),
  })
  const tenantsQuery = useQuery({
    queryKey: ['tenants', 'options'],
    queryFn: () => tenantApi.list({ size: 200 }),
  })

  const createMutation = useMutation({
    mutationFn: (body: Record<string, unknown>) => leaseApi.create(body),
    onSuccess: (lease) => {
      toast.push(`Lease ${lease.leaseCode} drafted`)
      setCreateOpen(false)
      setForm(EMPTY_FORM)
      void queryClient.invalidateQueries({ queryKey: ['leases'] })
    },
  })

  const statusMutation = useMutation({
    mutationFn: (vars: { id: string; status: string; reason: string }) => leaseApi.changeStatus(vars.id, {
      status: vars.status,
      reason: vars.reason.trim() || undefined,
    }),
    onSuccess: () => {
      toast.push('Lease status changed')
      setStatusFor(null)
      setTargetStatus('')
      setStatusReason('')
      void queryClient.invalidateQueries({ queryKey: ['leases'] })
    },
  })

  const properties = propertiesQuery.data?.items ?? []
  const units = unitsQuery.data?.items ?? []
  const tenants = tenantsQuery.data?.items ?? []
  // The unit picker narrows to the chosen property, so a long register stays usable.
  const unitsForProperty = propertyId ? units.filter((unit) => unit.propertyId === propertyId) : units

  const filtersActive = Boolean(propertyId || unitId || tenantId || status || from || to || expiring || search.trim())

  function openStatus(lease: LeaseSummary) {
    statusMutation.reset()
    setStatusFor(lease)
    setTargetStatus('')
    setStatusReason('')
  }

  function submitCreate() {
    setFormError(null)
    if (!form.unitId) return setFormError('Choose the unit this lease is for')
    if (!form.tenantId) return setFormError('Choose the tenant taking the lease')
    if (!form.startDate) return setFormError('A start date is required')
    if (!form.endDate) return setFormError('An end date is required')
    // The schema refuses an end before the start (`ck_leases_lifecycle` via LEASE_END_BEFORE_START);
    // catching it here saves the round-trip without pretending to be the authority on it.
    if (form.endDate < form.startDate) return setFormError('The lease end date cannot fall before its start date')
    if (!form.rentAmount || Number(form.rentAmount) <= 0) return setFormError('A rent amount greater than zero is required')

    createMutation.mutate({
      unitId: form.unitId,
      tenantId: form.tenantId,
      startDate: form.startDate,
      endDate: form.endDate,
      rentAmount: Number(form.rentAmount),
      depositAmount: form.depositAmount ? Number(form.depositAmount) : 0,
      rentFrequency: form.rentFrequency,
    })
  }

  function submitStatus() {
    if (!statusFor) return
    if (!targetStatus) return
    if (targetStatus === 'TERMINATED' && !statusReason.trim()) return
    statusMutation.mutate({ id: statusFor.id, status: targetStatus, reason: statusReason })
  }

  return (
    <>
      <PageHeader
        title="Leases"
        description="Every tenancy the portfolio holds, its rent and deposit, when it ends, and the moves its state allows."
        actions={<Button variant="primary" onClick={() => { createMutation.reset(); setForm(EMPTY_FORM); setFormError(null); setCreateOpen(true) }}>
          <Plus size={15} aria-hidden="true" /> New lease
        </Button>}
      />

      <SectionHeader
        eyebrow="Letting"
        title="Lease register"
        rule="copper"
        description={`${leasesQuery.data ? fmtCount(leasesQuery.data.totalElements) : '—'} leases on the register. Filter, sort and page through them; open one for its money position.`}
      />

      <Panel notch>
        <>
          <Toolbar>
            <Field label="Property" htmlFor="lease-filter-property">
              <Select id="lease-filter-property" value={propertyId} onChange={(event) => { setPropertyId(event.target.value); setUnitId(''); setPage(0) }}>
                <option value="">All properties</option>
                {properties.map((property) => <option key={property.id} value={property.id}>{property.name}</option>)}
              </Select>
            </Field>
            <Field label="Unit" htmlFor="lease-filter-unit">
              <Select id="lease-filter-unit" value={unitId} onChange={(event) => { setUnitId(event.target.value); setPage(0) }}>
                <option value="">All units</option>
                {unitsForProperty.map((unit) => <option key={unit.id} value={unit.id}>{unit.unitCode}{unit.propertyName ? ` · ${unit.propertyName}` : ''}</option>)}
              </Select>
            </Field>
            <Field label="Tenant" htmlFor="lease-filter-tenant">
              <Select id="lease-filter-tenant" value={tenantId} onChange={(event) => { setTenantId(event.target.value); setPage(0) }}>
                <option value="">All tenants</option>
                {tenants.map((tenant) => <option key={tenant.id} value={tenant.id}>{tenant.displayName}</option>)}
              </Select>
            </Field>
            <Field label="Status" htmlFor="lease-filter-status">
              <Select id="lease-filter-status" value={status} onChange={(event) => { setStatus(event.target.value); setPage(0) }}>
                <option value="">Any status</option>
                {Object.keys(LEASE_TRANSITIONS).map((value) => <option key={value} value={value}>{humanize(value)}</option>)}
              </Select>
            </Field>
            <Field label="Running from" htmlFor="lease-filter-from">
              <Input id="lease-filter-from" type="date" value={from} onChange={(event) => { setFrom(event.target.value); setPage(0) }} />
            </Field>
            <Field label="Up to" htmlFor="lease-filter-to">
              <Input id="lease-filter-to" type="date" value={to} onChange={(event) => { setTo(event.target.value); setPage(0) }} />
            </Field>
            <Field label="Expiring within" htmlFor="lease-filter-expiring" hint={`Default window ${warningDays} days`}>
              <Select id="lease-filter-expiring" value={expiring} onChange={(event) => { setExpiring(event.target.value); setPage(0) }}>
                <option value="">Any time</option>
                {EXPIRY_WINDOWS.map((days) => <option key={days} value={days}>Next {days} days</option>)}
              </Select>
            </Field>
            <Field label="Search" htmlFor="lease-filter-search">
              <Input id="lease-filter-search" value={search} placeholder="Lease code or agreement" onChange={(event) => { setSearch(event.target.value); setPage(0) }} />
            </Field>
            <div className="flex items-end gap-2">
              <Button onClick={() => void leasesQuery.refetch()} aria-label="Reload the lease register">
                <RefreshCw size={14} aria-hidden="true" /> Reload
              </Button>
            </div>
          </Toolbar>

          {leasesQuery.isLoading && <div className="p-4"><Skeleton rows={6} /></div>}

          {leasesQuery.isError && (
            <div className="p-4">
              <ErrorState message={messageOf(leasesQuery.error)} onRetry={() => void leasesQuery.refetch()} />
            </div>
          )}

          {leasesQuery.data && (
            <>
              <DataTable
                caption="Lease register"
                columns={leaseColumns({ onOpen: (row) => void navigate(`/leases/${row.id}`), onStatus: openStatus })}
                rows={leasesQuery.data.items}
                keyOf={(row) => row.id}
                onRowClick={(row) => void navigate(`/leases/${row.id}`)}
                sort={sort}
                onSortChange={(next) => { setSort(next); setPage(0) }}
                empty={filtersActive ? (
                  <EmptyState
                    icon={<Search size={20} />}
                    title="No leases match these filters"
                    description="Loosen the property, unit, tenant, status or date filters — or clear the search — and the tenancies that do exist will reappear here."
                  />
                ) : (
                  <EmptyState
                    icon={<FileWarning size={20} />}
                    title="No leases have been drafted yet"
                    description="A lease is created by drafting one against a unit and a tenant. Draft the first one and this register fills in."
                  />
                )}
              />

              <Pagination
                page={leasesQuery.data.page}
                size={leasesQuery.data.size}
                totalElements={leasesQuery.data.totalElements}
                totalPages={leasesQuery.data.totalPages}
                hasNext={leasesQuery.data.hasNext}
                onPageChange={setPage}
                onSizeChange={(next) => { setSize(next); setPage(0) }}
              />
            </>
          )}

          <p className="sr-only" role="status" aria-live="polite">
            {leasesQuery.isFetching ? 'Updating the lease register' : 'Lease register up to date'}
          </p>
        </>
      </Panel>

      {/* ── Draft a lease ─────────────────────────────────────────────────── */}
      <Dialog
        open={createOpen}
        onClose={() => setCreateOpen(false)}
        title="Draft a lease"
        description="The lease number is minted by the backend and a new lease always starts as a draft."
        width="lg"
        footer={
          <>
            <Button variant="ghost" onClick={() => setCreateOpen(false)}>Cancel</Button>
            <Button variant="primary" loading={createMutation.isPending} onClick={submitCreate}>Draft lease</Button>
          </>
        }
      >
        <div className="grid gap-4 sm:grid-cols-2">
          <Field
            label="Unit"
            htmlFor="lease-new-unit"
            required
            error={unitAlreadyLet(createMutation.error) ? messageOf(createMutation.error) : formError?.startsWith('Choose the unit') ? formError : null}
          >
            <Select id="lease-new-unit" value={form.unitId} onChange={(event) => setForm({ ...form, unitId: event.target.value })}>
              <option value="">Choose a unit…</option>
              {units.map((unit) => <option key={unit.id} value={unit.id}>{unit.unitCode}{unit.propertyName ? ` · ${unit.propertyName}` : ''}</option>)}
            </Select>
          </Field>

          <Field label="Tenant" htmlFor="lease-new-tenant" required error={formError?.startsWith('Choose the tenant') ? formError : null}>
            <Select id="lease-new-tenant" value={form.tenantId} onChange={(event) => setForm({ ...form, tenantId: event.target.value })}>
              <option value="">Choose a tenant…</option>
              {tenants.map((tenant) => <option key={tenant.id} value={tenant.id}>{tenant.displayName}</option>)}
            </Select>
          </Field>

          <Field label="Start date" htmlFor="lease-new-start" required>
            <Input id="lease-new-start" type="date" value={form.startDate} onChange={(event) => setForm({ ...form, startDate: event.target.value })} />
          </Field>

          <Field label="End date" htmlFor="lease-new-end" required hint="Must not fall before the start date">
            <Input id="lease-new-end" type="date" value={form.endDate} onChange={(event) => setForm({ ...form, endDate: event.target.value })} />
          </Field>

          <Field label="Rent" htmlFor="lease-new-rent" required hint="The headline amount, at the frequency below">
            <Input id="lease-new-rent" type="number" min="0" step="0.01" value={form.rentAmount} onChange={(event) => setForm({ ...form, rentAmount: event.target.value })} />
          </Field>

          <Field label="Rent frequency" htmlFor="lease-new-frequency">
            <Select id="lease-new-frequency" value={form.rentFrequency} onChange={(event) => setForm({ ...form, rentFrequency: event.target.value })}>
              {RENT_FREQUENCIES.map((frequency) => <option key={frequency} value={frequency}>{humanize(frequency)}</option>)}
            </Select>
          </Field>

          <Field label="Deposit" htmlFor="lease-new-deposit" hint="Held, not income — recorded against the tenancy">
            <Input id="lease-new-deposit" type="number" min="0" step="0.01" value={form.depositAmount} onChange={(event) => setForm({ ...form, depositAmount: event.target.value })} />
          </Field>
        </div>

        {createMutation.isError && !unitAlreadyLet(createMutation.error) && (
          <div className="mt-4"><ErrorState message={messageOf(createMutation.error)} /></div>
        )}
        {formError && !unitAlreadyLet(createMutation.error) && (
          <p className="mt-4 text-sm" role="alert" style={{ color: 'var(--danger)' }}>{formError}</p>
        )}
      </Dialog>

      {/* ── Change a lease's status ───────────────────────────────────────── */}
      <Dialog
        open={statusFor !== null}
        onClose={() => setStatusFor(null)}
        title="Change lease status"
        description={statusFor ? `${statusFor.leaseCode} · ${statusFor.tenantName ?? 'No tenant'}` : undefined}
        footer={
          <>
            <Button variant="ghost" onClick={() => setStatusFor(null)}>Cancel</Button>
            <Button
              variant="primary"
              loading={statusMutation.isPending}
              disabled={!targetStatus || (targetStatus === 'TERMINATED' && !statusReason.trim())}
              onClick={submitStatus}
            >
              Apply move
            </Button>
          </>
        }
      >
        {statusFor && (
          <div className="space-y-4">
            <p className="text-sm" style={{ color: 'var(--text-secondary)' }}>
              Currently <StatusBadge status={statusFor.status} />. Only the moves the lease's own lifecycle
              permits are offered — a settled lease has reached a terminal state and cannot move again.
            </p>

            {(LEASE_TRANSITIONS[statusFor.status] ?? []).length === 0 ? (
              <EmptyState
                title="This lease cannot move again"
                description={`A lease that is ${humanize(statusFor.status)} is terminal. Draft a new tenancy rather than reopening a settled one.`}
              />
            ) : (
              <>
                <Field label="Move to" htmlFor="lease-status-target" required>
                  <Select id="lease-status-target" value={targetStatus} onChange={(event) => setTargetStatus(event.target.value)}>
                    <option value="">Choose a status…</option>
                    {(LEASE_TRANSITIONS[statusFor.status] ?? []).map((value) => <option key={value} value={value}>{humanize(value)}</option>)}
                  </Select>
                </Field>

                {targetStatus === 'TERMINATED' && (
                  <Field label="Reason" htmlFor="lease-status-reason" required hint="The schema requires a reason to terminate">
                    <Textarea id="lease-status-reason" value={statusReason} onChange={(event) => setStatusReason(event.target.value)} />
                  </Field>
                )}
              </>
            )}

            {statusMutation.isError && <ErrorState message={messageOf(statusMutation.error)} />}
          </div>
        )}
      </Dialog>
    </>
  )
}

/** True when the API refused a draft because the unit is already let. */
function unitAlreadyLet(error: unknown): boolean {
  return error instanceof ApiError && error.code === 'UNIT_ALREADY_LET'
}

/** The register's columns. The action cell is `secondary` so the stacked mobile view never nests a button. */
function leaseColumns(handlers: { onOpen: (row: LeaseSummary) => void; onStatus: (row: LeaseSummary) => void }): Column<LeaseSummary>[] {
  return [
    { key: 'code', header: 'Lease', width: '10%', sortKey: 'leaseCode', render: (row) => (
      <span className="truncate font-semibold" style={{ color: 'var(--text-primary)' }}>{row.leaseCode}</span>
    ) },
    { key: 'tenant', header: 'Tenant', width: '14%', render: (row) => (
      <span className="truncate">{row.tenantName ?? '—'}</span>
    ) },
    { key: 'place', header: 'Property', width: '14%', render: (row) => (
      /*
       * This cell used to append the unit's code, which `LeaseDtos.LeaseSummary` does not send: a lease
       * row names its unit by id alone, so the code was always absent and the cell always ended in a
       * dangling separator. The row's property name is what it does promise.
       */
      <span className="truncate">{row.propertyName ?? '—'}</span>
    ) },
    { key: 'start', header: 'Start', width: '8%', sortKey: 'startDate', render: (row) => date(row.startDate) },
    { key: 'end', header: 'End', width: '8%', sortKey: 'endDate', render: (row) => date(row.endDate) },
    { key: 'rent', header: 'Rent', width: '11%', align: 'right', sortKey: 'rentAmount', render: (row) => money(row.rentAmount) },
    { key: 'deposit', header: 'Deposit', width: '10%', align: 'right', secondary: true, render: (row) => money(row.depositAmount) },
    { key: 'status', header: 'Status', width: '11%', sortKey: 'status', render: (row) => <StatusBadge status={row.status} /> },
    { key: 'days', header: 'Time left', width: '8%', align: 'right', render: remainingLabel },
    { key: 'actions', header: 'Actions', width: '6%', secondary: true, render: (row) => (
      // The row itself opens the lease (that is how the stacked mobile view navigates), so an action
      // button must swallow the click rather than let it fall through to the row.
      <span className="flex gap-1">
        <Button size="sm" variant="ghost" aria-label={`Open lease ${row.leaseCode}`}
          onClick={(event) => { event.stopPropagation(); handlers.onOpen(row) }}>Open</Button>
        <Button size="sm" variant="outline" aria-label={`Change the status of lease ${row.leaseCode}`}
          onClick={(event) => { event.stopPropagation(); handlers.onStatus(row) }}>Status</Button>
      </span>
    ) },
  ]
}

/**
 * A countdown read from the lease's end date, never shown against a tenancy that has already ended.
 *
 * `daysRemaining` is not on the lease register row - `LeaseDtos.LeaseSummary` carries the end date and
 * nothing derived from it, and `daysUntilEnd` belongs to the separate `ExpiringLease` projection - so
 * the count is taken from the end date, which the row does send. It is a calendar reading of a real
 * field, not a figure the screen invented.
 */
function remainingLabel(row: LeaseSummary) {
  if (SETTLED_STATUSES.has(row.status.toUpperCase())) return '—'
  const days = daysUntil(row.endDate)
  if (days === null) return '—'
  if (days < 0) return <Badge tone="terracotta">{`${Math.abs(days)} days over`}</Badge>
  if (days === 0) return 'Ends today'
  return `${days} days`
}
