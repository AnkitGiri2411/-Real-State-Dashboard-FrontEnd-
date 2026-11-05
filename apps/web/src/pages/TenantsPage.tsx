import { useEffect, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Plus } from 'lucide-react'
import { PageAccessGuard, PageHeader } from '../components/common'
import {
  Button, DataTable, DetailItem, Dialog, EmptyState, ErrorState, Field, Input, Panel, Pagination,
  Select, Skeleton, StatusBadge, Toolbar, TONE_INK, useToast, type Column,
} from '../components/ui'
import { count as fmtCount, date, humanize, money } from '../components/ui/formatters'
import { tenantApi } from '../services'
import { detailsOf, messageOf } from '../lib/errors'
import { useAuthStore } from '../auth/authStore'
import type { TenantSummary } from '../types'

/**
 * The tenant register.
 *
 * A tenant is the party that occupies a unit under a lease, so this screen is the hinge between the
 * demand side and the rent side of the firm: the register reads as a schedule a manager scans, and
 * opening a row shows the profile together with the three things that sit behind it — the leases
 * they hold, the receipts they have paid and the faults they have reported.
 *
 * **Everything on this page is read or written through `tenantApi`.** No URL is assembled here, so
 * a route rename is one edit in the API map, and the page cannot quietly depend on an endpoint that
 * does not exist. The detail's three sub-lists come back from the detail endpoint itself, so opening
 * a tenant is one request rather than four.
 *
 * The create and edit forms validate on the client first and then, whatever the API refuses, put the
 * API's own field sentences *beside the inputs they name* — the backend reports a field failure as
 * `"<Label>: <sentence>"` (see `GlobalExceptionHandler.describe`), and a whole-record refusal such as
 * a duplicate contact stays as a sentence at the top of the form.
 */

/** The closed sets the request records accept, so a filter never sends a value the API refuses. */
const TENANT_STATUS_OPTIONS = [
  { value: 'ACTIVE', label: 'Active' },
  { value: 'INACTIVE', label: 'Inactive' },
  { value: 'BLACKLISTED', label: 'Blacklisted' },
]

const TENANT_TYPE_OPTIONS = [
  { value: 'INDIVIDUAL', label: 'Individual' },
  { value: 'COMPANY', label: 'Company' },
  { value: 'TRUST', label: 'Trust' },
  { value: 'GOVERNMENT', label: 'Government' },
  { value: 'OTHER', label: 'Other' },
]

/**
 * The human label each input's field failure arrives under.
 *
 * The backend humanises a request field (`annualIncome` -> `"Annual income"`) before it is put in
 * `details`, so the mapping back to an input has to be done here against the same wording. Keeping
 * it as one table means a new field is a one-line addition rather than a new branch.
 */
const TENANT_FIELD_LABELS: Record<string, string> = {
  displayName: 'Display name',
  tenantType: 'Tenant type',
  email: 'Email',
  phone: 'Phone',
  city: 'City',
  state: 'State',
  annualIncome: 'Annual income',
}

/*
 * `tenantApi.detail` answers the fuller `TenantDtos.TenantDetail` projection: the register summary
 * plus the tenant's leases, receipts and reported faults, read-only from the lease, payment and
 * maintenance modules. That record is declared in `src/types` from the DTO itself, so the three lists
 * are drawn from the one request without a boundary cast and without a local second copy of it.
 */

/** Hold a value still for `delay` ms, so a register is not refetched on every keystroke. */
function useDebounced<T>(value: T, delay = 350): T {
  const [settled, setSettled] = useState(value)
  useEffect(() => {
    const timer = window.setTimeout(() => setSettled(value), delay)
    return () => window.clearTimeout(timer)
  }, [value, delay])
  return settled
}

export default function TenantsPage() {
  return (
    <PageAccessGuard permission="TENANT_VIEW">
      <TenantRegister />
    </PageAccessGuard>
  )
}

function TenantRegister() {
  const canCreate = useAuthStore((state) => state.can('TENANT_CREATE'))
  const canUpdate = useAuthStore((state) => state.can('TENANT_UPDATE'))

  const [status, setStatus] = useState('')
  const [type, setType] = useState('')
  const [city, setCity] = useState('')
  const [searchInput, setSearchInput] = useState('')
  const search = useDebounced(searchInput)
  const [sort, setSort] = useState('displayName,asc')
  const [page, setPage] = useState(0)
  const [size, setSize] = useState(20)

  const [selected, setSelected] = useState<TenantSummary | null>(null)
  const [editing, setEditing] = useState<TenantSummary | null>(null)
  const [creating, setCreating] = useState(false)

  const query = useQuery({
    queryKey: ['tenants', { status, type, city, search, sort, page, size }],
    queryFn: () => tenantApi.list({ status, type, city, search, sort, page, size }),
  })

  const hasFilters = Boolean(status || type || city || search)

  const columns: Column<TenantSummary>[] = [
    {
      key: 'tenant',
      header: 'Tenant',
      width: '26%',
      sortKey: 'displayName',
      render: (row) => (
        <span className="block min-w-0">
          <span className="block truncate font-medium" style={{ color: 'var(--text-primary)' }}>{row.displayName}</span>
          <span className="block truncate text-[11px] tabular-nums" style={{ color: 'var(--text-secondary)' }}>{row.tenantCode}</span>
        </span>
      ),
    },
    {
      key: 'type',
      header: 'Type',
      width: '12%',
      secondary: true,
      render: (row) => humanize(row.tenantType),
    },
    {
      key: 'city',
      header: 'City',
      width: '14%',
      sortKey: 'city',
      secondary: true,
      render: (row) => row.city ?? '—',
    },
    {
      key: 'contact',
      header: 'Contact',
      width: '22%',
      secondary: true,
      render: (row) => (
        <span className="block min-w-0">
          <span className="block truncate text-xs" style={{ color: 'var(--text-primary)' }}>{row.email ?? 'No email on record'}</span>
          <span className="block truncate text-[11px]" style={{ color: 'var(--text-secondary)' }}>{row.phone ?? 'No phone on record'}</span>
        </span>
      ),
    },
    /*
     * This column used to be "Leases held", read from a `leaseCount` the register row does not send.
     * How many tenancies a tenant holds is a count over the lease register, and it is not on
     * `TenantDtos.TenantSummary` - so the column drew a dash on every row. It now holds the tenant's
     * state, which the row does promise; the leases themselves are listed in the tenant's own file.
     */
    {
      key: 'state',
      header: 'State',
      width: '12%',
      secondary: true,
      render: (row) => row.state ?? '—',
    },
    {
      key: 'status',
      header: 'Status',
      width: '14%',
      sortKey: 'status',
      render: (row) => <StatusBadge status={row.status} />,
    },
  ]

  if (query.isLoading) {
    return (
      <>
        <PageHeader title="Tenants" description="Reading the tenant register…" />
        <Skeleton rows={6} />
      </>
    )
  }

  if (query.isError) {
    return (
      <>
        <PageHeader title="Tenants" />
        <ErrorState message={messageOf(query.error)} onRetry={() => void query.refetch()} />
      </>
    )
  }

  const items = query.data?.items ?? []
  const totalElements = query.data?.totalElements ?? 0

  return (
    <>
      <PageHeader
        eyebrow="Occupancy"
        title="Tenants"
        description="The parties who occupy the firm's units under a lease. Open a row for the profile, its leases, its receipts and the faults the tenant has reported."
        actions={
          canCreate ? (
            <Button variant="primary" onClick={() => setCreating(true)}>
              <Plus size={16} aria-hidden="true" />
              New tenant
            </Button>
          ) : undefined
        }
      />

      <Panel notch className="mb-3">
        <Toolbar>
          <div className="w-full sm:w-44">
            <Field label="Status" htmlFor="tenants-status">
              <Select id="tenants-status" value={status} onChange={(event) => { setStatus(event.target.value); setPage(0) }}>
                <option value="">All statuses</option>
                {TENANT_STATUS_OPTIONS.map((option) => <option key={option.value} value={option.value}>{option.label}</option>)}
              </Select>
            </Field>
          </div>
          <div className="w-full sm:w-44">
            <Field label="Type" htmlFor="tenants-type">
              <Select id="tenants-type" value={type} onChange={(event) => { setType(event.target.value); setPage(0) }}>
                <option value="">All types</option>
                {TENANT_TYPE_OPTIONS.map((option) => <option key={option.value} value={option.value}>{option.label}</option>)}
              </Select>
            </Field>
          </div>
          <div className="w-full sm:w-40">
            <Field label="City" htmlFor="tenants-city">
              <Input id="tenants-city" value={city} placeholder="Any city" onChange={(event) => { setCity(event.target.value); setPage(0) }} />
            </Field>
          </div>
          <div className="w-full sm:w-64">
            <Field label="Search" htmlFor="tenants-search" hint="Name, code, email or phone.">
              <Input
                id="tenants-search"
                type="search"
                value={searchInput}
                placeholder="Search the register"
                onChange={(event) => { setSearchInput(event.target.value); setPage(0) }}
              />
            </Field>
          </div>
        </Toolbar>

        {/* The count is the async status of the register; announcing it is what tells a screen-reader
            user that a filter change has landed. */}
        <p className="px-4 pt-3 text-xs" aria-live="polite" style={{ color: 'var(--text-secondary)' }}>
          {query.isFetching ? 'Reading the register…' : `${fmtCount(totalElements)} tenant${totalElements === 1 ? '' : 's'} in this view`}
        </p>

        <DataTable
          columns={columns}
          rows={items}
          keyOf={(row) => row.id}
          sort={sort}
          onSortChange={(next) => { setSort(next); setPage(0) }}
          onRowClick={(row) => setSelected(row)}
          caption="Tenant register"
          empty={
            hasFilters ? (
              <EmptyState
                title="No tenants match these filters"
                description="Nothing in the register matches the status, type, city or search you chose. Clear a filter to widen the view."
              />
            ) : (
              <EmptyState
                title="The tenant register is empty"
                description="No tenant profiles have been created yet. A tenant appears here once one is added, and the leases they sign are counted against their row."
              />
            )
          }
        />

        <Pagination
          page={page}
          size={size}
          totalElements={totalElements}
          totalPages={query.data?.totalPages ?? 0}
          hasNext={query.data?.hasNext ?? false}
          onPageChange={setPage}
          onSizeChange={(next) => { setSize(next); setPage(0) }}
        />
      </Panel>

      {/* Edit is offered from the detail dialog, not the row, so a row click is always "show me
          this tenant" rather than a guess about intent. */}
      {selected && (
        <TenantDetailDialog
          tenant={selected}
          canEdit={canUpdate}
          onEdit={() => { setEditing(selected); setSelected(null) }}
          onClose={() => setSelected(null)}
        />
      )}

      {editing && <TenantFormDialog tenant={editing} onClose={() => setEditing(null)} />}
      {creating && <TenantFormDialog tenant={null} onClose={() => setCreating(false)} />}
    </>
  )
}

/* ============================================================
 * The detail dialog - the profile and the three books behind it
 * ============================================================ */
function TenantDetailDialog({
  tenant, canEdit, onEdit, onClose,
}: {
  tenant: TenantSummary
  canEdit: boolean
  onEdit: () => void
  onClose: () => void
}) {
  const detail = useQuery({
    queryKey: ['tenants', tenant.id, 'detail'],
    queryFn: () => tenantApi.detail(tenant.id),
  })

  const footer = (
    <>
      {canEdit && <Button onClick={onEdit}>Edit tenant</Button>}
      <Button variant="ghost" onClick={onClose}>Close</Button>
    </>
  )

  if (detail.isLoading) {
    return (
      <Dialog open title={tenant.displayName} description="Reading the tenant's record…" onClose={onClose} width="lg" footer={footer}>
        <Skeleton rows={6} />
      </Dialog>
    )
  }

  if (detail.isError) {
    return (
      <Dialog open title={tenant.displayName} onClose={onClose} width="lg" footer={footer}>
        <ErrorState message={messageOf(detail.error)} onRetry={() => void detail.refetch()} />
      </Dialog>
    )
  }

  const record = detail.data
  if (!record) {
    return (
      <Dialog open title={tenant.displayName} onClose={onClose} width="lg" footer={footer}>
        <EmptyState
          title="This tenant's record could not be read"
          description="The profile was found in the register but its detail did not come back. Try again, or reopen it from the register."
        />
      </Dialog>
    )
  }

  const leases = record.leases ?? []
  const payments = record.payments ?? []
  const maintenance = record.maintenance ?? []

  return (
    <Dialog
      open
      title={record.displayName}
      description={`Tenant ${record.tenantCode} — profile, leases, receipts and reported faults.`}
      onClose={onClose}
      width="lg"
      footer={footer}
    >
      <div className="space-y-4">
        <dl className="grid grid-cols-2 gap-3 sm:grid-cols-3">
          <DetailItem label="Type" value={humanize(record.tenantType)} />
          <DetailItem label="Status" value={<StatusBadge status={record.status} />} />
          <DetailItem label="City" value={record.city ?? '—'} />
          <DetailItem label="Email" value={record.email ?? '—'} />
          <DetailItem label="Phone" value={record.phone ?? '—'} />
          <DetailItem label="Annual income" value={money(record.annualIncome)} tone="copper" />
        </dl>

        {/* The money section reads copper; the fault section reads terracotta. Colour is carrying the
            module's meaning, not decorating the panel. */}
        <Panel title="Leases" subtitle={record.runningLease ? 'A lease is running' : 'Leases on record'} tint="forest">
          {leases.length === 0 ? (
            <p className="text-sm" style={{ color: 'var(--text-secondary)' }}>
              No leases are recorded against this tenant. One appears here once a tenancy is drafted for them.
            </p>
          ) : (
            <ul className="divide-y" style={{ borderColor: 'var(--border)' }}>
              {leases.map((lease) => (
                <li key={lease.leaseId} className="flex items-center justify-between gap-3 py-2 first:pt-0 last:pb-0">
                  <span className="min-w-0">
                    <span className="block truncate text-xs font-semibold" style={{ color: 'var(--text-primary)' }}>
                      {lease.leaseCode}{lease.propertyName ? ` · ${lease.propertyName}` : ''}
                    </span>
                    <span className="block truncate text-[11px]" style={{ color: 'var(--text-secondary)' }}>
                      {date(lease.startDate)} – {date(lease.endDate)}{lease.rentFrequency ? ` · ${humanize(lease.rentFrequency)}` : ''}
                    </span>
                  </span>
                  <span className="flex shrink-0 items-center gap-2 text-right">
                    <span className="text-xs tabular-nums" style={{ color: 'var(--text-primary)' }}>{money(lease.rentAmount)}</span>
                    <StatusBadge status={lease.status} />
                  </span>
                </li>
              ))}
            </ul>
          )}
        </Panel>

        <Panel title="Receipts" subtitle="Money received from this tenant" tint="copper">
          {payments.length === 0 ? (
            <p className="text-sm" style={{ color: 'var(--text-secondary)' }}>
              No receipts are recorded against this tenant yet. They appear here as payments are posted against their leases.
            </p>
          ) : (
            <ul className="divide-y" style={{ borderColor: 'var(--border)' }}>
              {payments.map((payment) => (
                <li key={payment.paymentId} className="flex items-center justify-between gap-3 py-2 first:pt-0 last:pb-0">
                  <span className="min-w-0">
                    <span className="block truncate text-xs font-semibold" style={{ color: 'var(--text-primary)' }}>{payment.receiptNumber}</span>
                    <span className="block truncate text-[11px]" style={{ color: 'var(--text-secondary)' }}>
                      {humanize(payment.direction)} · {date(payment.receivedAt)}
                    </span>
                  </span>
                  <span className="flex shrink-0 items-center gap-2 text-right">
                    <span className="text-xs font-semibold tabular-nums" style={{ color: TONE_INK.copper }}>{money(payment.amount)}</span>
                    <StatusBadge status={payment.status} />
                  </span>
                </li>
              ))}
            </ul>
          )}
        </Panel>

        <Panel title="Reported faults" subtitle="Maintenance this tenant raised" tint="terracotta">
          {maintenance.length === 0 ? (
            <p className="text-sm" style={{ color: 'var(--text-secondary)' }}>
              This tenant has not reported any faults. A request appears here once it is raised against their unit.
            </p>
          ) : (
            <ul className="divide-y" style={{ borderColor: 'var(--border)' }}>
              {maintenance.map((request) => (
                <li key={request.requestId} className="flex items-center justify-between gap-3 py-2 first:pt-0 last:pb-0">
                  <span className="min-w-0">
                    <span className="block truncate text-xs font-semibold" style={{ color: 'var(--text-primary)' }}>
                      {request.title ?? humanize(request.category)}
                    </span>
                    <span className="block truncate text-[11px] tabular-nums" style={{ color: 'var(--text-secondary)' }}>
                      {request.requestCode} · {date(request.reportedAt)}
                    </span>
                  </span>
                  <span className="flex shrink-0 items-center gap-2">
                    <StatusBadge status={request.priority} />
                    <StatusBadge status={request.status} />
                  </span>
                </li>
              ))}
            </ul>
          )}
        </Panel>
      </div>
    </Dialog>
  )
}

/* ============================================================
 * The create / edit form
 * ============================================================ */
interface TenantForm {
  displayName: string
  tenantType: string
  email: string
  phone: string
  city: string
  state: string
  annualIncome: string
}

function emptyTenantForm(): TenantForm {
  return { displayName: '', tenantType: 'INDIVIDUAL', email: '', phone: '', city: '', state: '', annualIncome: '' }
}

function formFromTenant(tenant: TenantSummary): TenantForm {
  // The register summary carries the contact fields but not the address or income, which the update
  // request treats as "leave unchanged" when they are absent — so those two start blank on purpose.
  return {
    displayName: tenant.displayName,
    tenantType: tenant.tenantType,
    email: tenant.email ?? '',
    phone: tenant.phone ?? '',
    city: tenant.city ?? '',
    state: '',
    annualIncome: '',
  }
}

function blankToNull(value: string): string | null {
  const trimmed = value.trim()
  return trimmed === '' ? null : trimmed
}

function numberOrNull(value: string): number | null {
  const trimmed = value.trim()
  return trimmed === '' ? null : Number(trimmed)
}

function validateTenant(form: TenantForm): Record<string, string> {
  const errors: Record<string, string> = {}
  if (!form.displayName.trim()) errors.displayName = 'A name is required.'
  if (!form.tenantType) errors.tenantType = 'A tenant type is required.'
  if (form.email.trim() && !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(form.email.trim())) {
    errors.email = 'That does not look like an email address.'
  }
  if (form.annualIncome.trim() !== '') {
    const income = Number(form.annualIncome)
    if (!Number.isFinite(income) || income < 0) errors.annualIncome = 'Enter a number of zero or more.'
  }
  return errors
}

/**
 * Return the API's field failures keyed by the input they name, with whatever it refused as a whole
 * left as a single sentence.
 */
function mapFieldDetails(details: string[], labels: Record<string, string>): { fields: Record<string, string>; other: string | null } {
  const fields: Record<string, string> = {}
  const other: string[] = []
  for (const detail of details) {
    const separator = detail.indexOf(':')
    if (separator === -1) {
      other.push(detail)
      continue
    }
    const label = detail.slice(0, separator).trim().toLowerCase()
    const sentence = detail.slice(separator + 1).trim()
    const field = Object.keys(labels).find((key) => labels[key].toLowerCase() === label)
    if (field && sentence) fields[field] = sentence
    else other.push(detail)
  }
  return { fields, other: other.length > 0 ? other.join(' ') : null }
}

function TenantFormDialog({ tenant, onClose }: { tenant: TenantSummary | null; onClose: () => void }) {
  const queryClient = useQueryClient()
  const toast = useToast()
  const editing = tenant !== null

  const [form, setForm] = useState<TenantForm>(() => (tenant ? formFromTenant(tenant) : emptyTenantForm()))
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({})
  const [generalError, setGeneralError] = useState<string | null>(null)

  const mutation = useMutation({
    mutationFn: (body: Record<string, unknown>) =>
      editing && tenant ? tenantApi.update(tenant.id, body) : tenantApi.create(body),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['tenants'] })
      toast.push(editing ? 'Tenant profile updated' : 'Tenant profile created')
      onClose()
    },
    onError: (error) => {
      const mapped = mapFieldDetails(detailsOf(error), TENANT_FIELD_LABELS)
      setFieldErrors(mapped.fields)
      setGeneralError(mapped.other ?? messageOf(error))
      toast.push(messageOf(error), 'terracotta')
    },
  })

  // Written as an explicit copy-and-assign rather than a computed key in the literal, so the
  // narrowing is obvious to the compiler and to the next reader.
  const update = (key: keyof TenantForm, value: string) =>
    setForm((current) => {
      const next = { ...current }
      next[key] = value
      return next
    })

  const submit = () => {
    const errors = validateTenant(form)
    setFieldErrors(errors)
    setGeneralError(null)
    if (Object.keys(errors).length > 0) return
    mutation.mutate({
      displayName: form.displayName.trim(),
      tenantType: form.tenantType,
      email: blankToNull(form.email),
      phone: blankToNull(form.phone),
      city: blankToNull(form.city),
      state: blankToNull(form.state),
      annualIncome: numberOrNull(form.annualIncome),
    })
  }

  return (
    <Dialog
      open
      title={editing ? `Edit ${tenant?.displayName ?? 'tenant'}` : 'New tenant'}
      description={editing
        ? 'Leave an address or income blank to keep what is already on record.'
        : 'The tenant code is minted by the firm’s own series and appears once the profile is created.'}
      onClose={onClose}
      footer={
        <>
          <Button variant="ghost" onClick={onClose} disabled={mutation.isPending}>Cancel</Button>
          <Button variant="primary" onClick={submit} loading={mutation.isPending}>
            {editing ? 'Save changes' : 'Create tenant'}
          </Button>
        </>
      }
    >
      <div className="space-y-3">
        {generalError && <ErrorState message={generalError} />}

        <Field label="Display name" htmlFor="tenant-display-name" required error={fieldErrors.displayName}>
          <Input
            id="tenant-display-name"
            value={form.displayName}
            autoComplete="off"
            onChange={(event) => update('displayName', event.target.value)}
          />
        </Field>

        <Field label="Tenant type" htmlFor="tenant-type" required error={fieldErrors.tenantType}>
          <Select id="tenant-type" value={form.tenantType} onChange={(event) => update('tenantType', event.target.value)}>
            {TENANT_TYPE_OPTIONS.map((option) => <option key={option.value} value={option.value}>{option.label}</option>)}
          </Select>
        </Field>

        <div className="grid gap-3 sm:grid-cols-2">
          <Field label="Email" htmlFor="tenant-email" error={fieldErrors.email}>
            <Input id="tenant-email" type="email" value={form.email} autoComplete="off" onChange={(event) => update('email', event.target.value)} />
          </Field>
          <Field label="Phone" htmlFor="tenant-phone" error={fieldErrors.phone}>
            <Input id="tenant-phone" value={form.phone} autoComplete="off" onChange={(event) => update('phone', event.target.value)} />
          </Field>
        </div>

        <div className="grid gap-3 sm:grid-cols-2">
          <Field label="City" htmlFor="tenant-city-field" error={fieldErrors.city}>
            <Input id="tenant-city-field" value={form.city} autoComplete="off" onChange={(event) => update('city', event.target.value)} />
          </Field>
          <Field
            label="State"
            htmlFor="tenant-state"
            error={fieldErrors.state}
            hint={editing ? 'Leave blank to keep the value on record.' : undefined}
          >
            <Input id="tenant-state" value={form.state} autoComplete="off" onChange={(event) => update('state', event.target.value)} />
          </Field>
        </div>

        <Field
          label="Annual income"
          htmlFor="tenant-income"
          error={fieldErrors.annualIncome}
          hint={editing ? 'Leave blank to keep the value on record.' : 'Used for screening. Leave blank if the tenant has not disclosed it.'}
        >
          <Input
            id="tenant-income"
            type="number"
            min={0}
            inputMode="decimal"
            value={form.annualIncome}
            onChange={(event) => update('annualIncome', event.target.value)}
          />
        </Field>
      </div>
    </Dialog>
  )
}
