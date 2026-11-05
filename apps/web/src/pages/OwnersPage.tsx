import { useState, type FormEvent } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Plus } from 'lucide-react'
import { PageAccessGuard, PageHeader } from '../components/common'
import {
  Button, DataTable, DetailItem, Dialog, EmptyState, ErrorState, Field, Input, Pagination, Panel,
  Select, Skeleton, StatusBadge, Textarea, Toolbar, useToast, type Column,
} from '../components/ui'
import { count as fmtCount, date, humanize, percent } from '../components/ui/formatters'
import { ownerApi, propertyApi, type PageParams } from '../services'
import { detailsOf, messageOf } from '../lib/errors'
import { useAuthStore } from '../auth/authStore'
import type { OwnerSummary } from '../types'

/**
 * The owner register.
 *
 * Owners are the parties whose assets are being managed, so the register is read two ways: the list a
 * person scans, and one owner's file - who they are, and what they hold. Holdings are not a field on
 * an owner: a share belongs to a property, with a percentage and a period, and the register is the
 * one place that arithmetic lives. So the file reads the owner's assets from the property register and
 * each asset's shares from the ownership register, and shows the share that is **this owner's** - never
 * a property's whole ownership.
 *
 * The share a person holds is the figure most easily misread on a screen like this, so it is written
 * as a percentage with the owner's name attached and the period it was in force, never as a colour.
 */
export default function OwnersPage() {
  return (
    <PageAccessGuard permission="OWNER_VIEW">
      <OwnerRegister />
    </PageAccessGuard>
  )
}

interface OwnerFilters {
  status: string
  type: string
  city: string
  search: string
}

const NO_FILTERS: OwnerFilters = { status: '', type: '', city: '', search: '' }

function OwnerRegister() {
  const toast = useToast()
  const can = useAuthStore((state) => state.can)
  const canCreate = can('OWNER_CREATE')

  const [page, setPage] = useState(0)
  const [size, setSize] = useState(20)
  const [sort, setSort] = useState('createdAt,desc')
  const [criteria, setCriteria] = useState<OwnerFilters>(NO_FILTERS)
  // The selects apply as they are chosen; the free-text filters wait for the form, because the
  // register is filtered in memory once read and a request per keystroke would re-read the whole book.
  const [draft, setDraft] = useState({ city: '', search: '' })
  const [creating, setCreating] = useState(false)
  const [openOwnerId, setOpenOwnerId] = useState<string | null>(null)

  const params: PageParams = {
    page,
    size,
    sort,
    status: criteria.status || undefined,
    type: criteria.type || undefined,
    city: criteria.city || undefined,
    search: criteria.search || undefined,
  }

  const query = useQuery({
    queryKey: ['owners', params],
    queryFn: () => ownerApi.list(params),
  })

  const rows = query.data?.items ?? []
  const totalElements = query.data?.totalElements ?? 0
  const filtered = Boolean(criteria.status || criteria.type || criteria.city || criteria.search)

  function applyForm(event: FormEvent) {
    event.preventDefault()
    setCriteria((current) => ({ ...current, city: draft.city.trim(), search: draft.search.trim() }))
    setPage(0)
  }

  function clear() {
    setCriteria(NO_FILTERS)
    setDraft({ city: '', search: '' })
    setPage(0)
  }

  const columns: Column<OwnerSummary>[] = [
    {
      key: 'owner', header: 'Owner', width: '22%', sortKey: 'displayName',
      render: (row) => (
        <span className="block min-w-0">
          <span className="block truncate font-medium" style={{ color: 'var(--text-primary)' }}>{row.displayName}</span>
          <span className="block truncate text-[11px]" style={{ color: 'var(--text-secondary)' }}>{row.ownerCode}</span>
        </span>
      ),
    },
    { key: 'type', header: 'Type', width: '13%', render: (row) => humanize(row.ownerType) },
    { key: 'city', header: 'City', width: '14%', sortKey: 'city', secondary: true, render: (row) => row.city ?? '—' },
    {
      key: 'contact', header: 'Contact', width: '25%', secondary: true,
      render: (row) => (
        <span className="block min-w-0">
          <span className="block truncate" style={{ color: row.email ? 'var(--text-primary)' : undefined }}>{row.email ?? '—'}</span>
          {row.phone && <span className="block truncate text-[11px]" style={{ color: 'var(--text-secondary)' }}>{row.phone}</span>}
        </span>
      ),
    },
    /*
     * This column used to be "Properties held", read from a `propertyCount` the register row does not
     * carry: how many properties an owner holds is a count over the property register, computed for one
     * owner by `OwnerDetail.openHoldingCount` and not sent on any owner row. A column of dashes is not a
     * column, so the slot now holds the owner's state, which `OwnerDtos.OwnerSummary` does promise.
     * The owner's file below reports their open holdings from the detail.
     */
    { key: 'state', header: 'State', width: '14%', secondary: true, render: (row) => row.state ?? '—' },
    { key: 'status', header: 'Status', width: '12%', sortKey: 'status', render: (row) => <StatusBadge status={row.status} /> },
  ]

  return (
    <>
      <PageHeader
        eyebrow="Parties"
        title="Owners"
        description="The people and vehicles whose assets the firm manages, and the share of each property they hold. Open an owner to read their record and their holdings."
        actions={canCreate ? (
          <Button variant="primary" onClick={() => setCreating(true)}>
            <Plus size={15} aria-hidden="true" />
            New owner
          </Button>
        ) : undefined}
      />

      <Panel bodyClassName="!p-0">
        <p className="sr-only" role="status" aria-live="polite">
          {query.isLoading
            ? 'Loading the owner register.'
            : `${fmtCount(totalElements)} owners match the current filters.`}
        </p>

        <Toolbar>
          <div className="w-40">
            <Field label="Status" htmlFor="owner-status-filter">
              <Select
                id="owner-status-filter"
                value={criteria.status}
                onChange={(event) => { setCriteria((current) => ({ ...current, status: event.target.value })); setPage(0) }}
              >
                <option value="">Any status</option>
                {OWNER_STATUSES.map((option) => (
                  <option key={option} value={option}>{humanize(option)}</option>
                ))}
              </Select>
            </Field>
          </div>

          <div className="w-44">
            <Field label="Type" htmlFor="owner-type-filter">
              <Select
                id="owner-type-filter"
                value={criteria.type}
                onChange={(event) => { setCriteria((current) => ({ ...current, type: event.target.value })); setPage(0) }}
              >
                <option value="">Any type</option>
                {OWNER_TYPES.map((option) => (
                  <option key={option} value={option}>{humanize(option)}</option>
                ))}
              </Select>
            </Field>
          </div>

          <form className="flex flex-wrap items-end gap-2" onSubmit={applyForm}>
            <div className="w-40">
              <Field label="City" htmlFor="owner-city-filter">
                <Input
                  id="owner-city-filter"
                  value={draft.city}
                  onChange={(event) => setDraft((current) => ({ ...current, city: event.target.value }))}
                  placeholder="Any city"
                />
              </Field>
            </div>
            <div className="w-56">
              <Field label="Search" htmlFor="owner-search-filter">
                <Input
                  id="owner-search-filter"
                  type="search"
                  value={draft.search}
                  onChange={(event) => setDraft((current) => ({ ...current, search: event.target.value }))}
                  placeholder="Name, owner code or contact"
                />
              </Field>
            </div>
            <Button type="submit" size="sm">Apply filters</Button>
            <Button type="button" size="sm" variant="ghost" onClick={clear}>Clear</Button>
          </form>

          <div className="w-48 sm:ml-auto">
            <Field label="Sort by" htmlFor="owner-sort-filter">
              <Select
                id="owner-sort-filter"
                value={sort}
                onChange={(event) => { setSort(event.target.value); setPage(0) }}
              >
                {OWNER_SORTS.map((option) => (
                  <option key={option.value} value={option.value}>{option.label}</option>
                ))}
              </Select>
            </Field>
          </div>
        </Toolbar>

        {query.isLoading ? (
          <div className="p-4"><Skeleton rows={6} /></div>
        ) : query.isError ? (
          <div className="p-4">
            <ErrorState message={messageOf(query.error)} onRetry={() => void query.refetch()} />
          </div>
        ) : (
          <>
            <DataTable
              columns={columns}
              rows={rows}
              keyOf={(row) => row.id}
              onRowClick={(row) => setOpenOwnerId(row.id)}
              sort={sort}
              onSortChange={(next) => { setSort(next); setPage(0) }}
              caption="Owner register"
              empty={filtered ? (
                <EmptyState
                  title="No owners match these filters"
                  description="Nothing in the register answers to the status, type, city or search term applied here. Clear a filter, or search for the owner's code instead of their name."
                  action={<Button size="sm" onClick={clear}>Clear the filters</Button>}
                />
              ) : (
                <EmptyState
                  title="The owner register holds no owners yet"
                  description="An owner is the party a property is held for, and the properties held column counts the register's holdings. Add the first owner and their holdings will appear here."
                  action={canCreate
                    ? <Button variant="primary" size="sm" onClick={() => setCreating(true)}>New owner</Button>
                    : undefined}
                />
              )}
            />
            {rows.length > 0 && (
              <div className="border-t" style={{ borderColor: 'var(--border)' }}>
                <Pagination
                  page={page}
                  size={size}
                  totalElements={totalElements}
                  totalPages={query.data?.totalPages ?? 0}
                  hasNext={query.data?.hasNext ?? false}
                  onPageChange={setPage}
                  onSizeChange={(next) => { setSize(next); setPage(0) }}
                />
              </div>
            )}
          </>
        )}
      </Panel>

      <CreateOwnerDialog
        open={creating}
        onClose={() => setCreating(false)}
        onCreated={(owner) => {
          setPage(0)
          toast.push(`${owner.displayName} added to the register`, 'emerald')
        }}
      />

      <OwnerFileDialog ownerId={openOwnerId} onClose={() => setOpenOwnerId(null)} />
    </>
  )
}

/* ============================================================
 * One owner's file
 * ============================================================ */

function OwnerFileDialog({ ownerId, onClose }: { ownerId: string | null; onClose: () => void }) {
  const can = useAuthStore((state) => state.can)
  // The register and the ownership shares are both property-side reads. An account that can see
  // owners but not the property register is told why its holdings are missing rather than being shown
  // a failed request it could do nothing about.
  const canProperties = can('PROPERTY_VIEW')

  const detailQuery = useQuery({
    queryKey: ['owner', ownerId],
    queryFn: () => ownerApi.detail(ownerId as string),
    enabled: Boolean(ownerId),
  })

  const holdingsQuery = useQuery({
    queryKey: ['owner-holdings', ownerId],
    queryFn: () => loadHoldings(ownerId as string),
    enabled: Boolean(ownerId) && canProperties,
  })

  const owner = detailQuery.data
  const holdings = holdingsQuery.data
  const holdingsRows = holdings?.rows ?? []

  const holdingColumns: Column<HoldingRow>[] = [
    {
      key: 'property', header: 'Property', width: '30%',
      render: (row) => (
        <span className="block min-w-0">
          <span className="block truncate font-medium" style={{ color: 'var(--text-primary)' }}>{row.name}</span>
          <span className="block truncate text-[11px]" style={{ color: 'var(--text-secondary)' }}>{row.code}</span>
        </span>
      ),
    },
    { key: 'city', header: 'City', width: '16%', secondary: true, render: (row) => row.city || '—' },
    {
      key: 'share', header: 'Share held', width: '14%', align: 'right',
      render: (row) => (row.percentage === null ? 'Not recorded' : percent(row.percentage, 2)),
    },
    { key: 'relationship', header: 'As', width: '18%', secondary: true, render: (row) => humanize(row.relationship) },
    {
      key: 'period', header: 'In force', width: '22%',
      render: (row) => (row.open === true
        ? `Since ${date(row.effectiveFrom)}`
        : row.effectiveTo === null
          ? '—'
          : `${date(row.effectiveFrom)} – ${date(row.effectiveTo)}`),
    },
  ]

  return (
    <Dialog
      open={ownerId !== null}
      title={owner ? owner.displayName : 'Owner'}
      description={owner ? `${owner.ownerCode} · ${humanize(owner.ownerType)}` : 'Reading the owner record…'}
      width="lg"
      onClose={onClose}
      footer={<Button variant="ghost" onClick={onClose}>Close</Button>}
    >
      {detailQuery.isLoading ? (
        <Skeleton rows={6} />
      ) : detailQuery.isError ? (
        <ErrorState message={messageOf(detailQuery.error)} onRetry={() => void detailQuery.refetch()} />
      ) : owner ? (
        <div className="space-y-5">
          <dl className="grid gap-x-5 gap-y-3 sm:grid-cols-3">
            <DetailItem label="Owner code" value={owner.ownerCode} />
            <DetailItem label="Type" value={humanize(owner.ownerType)} />
            <DetailItem label="Status" value={<StatusBadge status={owner.status} />} />
            <DetailItem label="E-mail" value={owner.email ?? '—'} />
            <DetailItem label="Phone" value={owner.phone ?? '—'} />
            <DetailItem label="City" value={owner.city ?? '—'} />
            <DetailItem label="Properties held" value={fmtCount(owner.openHoldingCount)} tone="copper" />
          </dl>

          <section className="border-t pt-4" style={{ borderColor: 'var(--border)' }}>
            <h3 className="font-display text-sm font-semibold" style={{ color: 'var(--text-primary)' }}>
              Property holdings
            </h3>
            <p className="mb-3 mt-0.5 text-xs" style={{ color: 'var(--text-secondary)' }}>
              The share shown is this owner&rsquo;s holding in each property, not the property&rsquo;s total
              ownership. Shares are dated rather than deleted, so a closed holding stays readable as history.
            </p>

            {!canProperties ? (
              <EmptyState
                title="Your account cannot read the property register"
                description="An owner's holdings are read from the property register and the ownership register, both of which need the PROPERTY_VIEW permission. Ask an owner or administrator to add it to your role."
              />
            ) : holdingsQuery.isLoading ? (
              <Skeleton rows={6} />
            ) : holdingsQuery.isError ? (
              <ErrorState message={messageOf(holdingsQuery.error)} onRetry={() => void holdingsQuery.refetch()} />
            ) : (
              <>
                <DataTable
                  columns={holdingColumns}
                  rows={holdingsRows}
                  keyOf={(row) => row.id}
                  caption={`Properties held by ${owner.displayName}`}
                  empty={
                    <EmptyState
                      title="This owner holds no properties in the register"
                      description="A holding is recorded against a property, with a share and a period. Record one and this owner's assets and their percentages appear here."
                    />
                  }
                />
                {holdings && holdings.total > holdingsRows.length && (
                  // Said plainly rather than hidden: the list is capped, and a reader who counts the
                  // rows should not conclude that the rest do not exist.
                  <p className="mt-3 text-xs" style={{ color: 'var(--text-secondary)' }}>
                    {`Showing the first ${holdingsRows.length} of ${fmtCount(holdings.total)} properties this owner holds. The register lists the rest against the owner's name.`}
                  </p>
                )}
              </>
            )}
          </section>
        </div>
      ) : (
        <EmptyState
          title="That owner is no longer on the register"
          description="They may have been archived, which withdraws an owner without erasing their holdings, or the register may have moved on since this page was read."
        />
      )}
    </Dialog>
  )
}

/* ============================================================
 * The create form
 * ============================================================ */

interface OwnerForm {
  ownerType: string
  displayName: string
  primaryContactName: string
  email: string
  phone: string
  city: string
  state: string
  notes: string
}

const EMPTY_OWNER_FORM: OwnerForm = {
  ownerType: 'INDIVIDUAL',
  displayName: '',
  primaryContactName: '',
  email: '',
  phone: '',
  city: '',
  state: '',
  notes: '',
}

/**
 * The label the API puts in front of a field error, mapped back to the input it belongs beside.
 *
 * The backend reports bean-validation failures as `"<Humanised field>: <message>"`; the mapping is
 * stated rather than guessed, so a detail the form cannot place is listed above the form instead of
 * being silently dropped.
 */
const OWNER_FIELD_LABELS: Record<string, string> = {
  ownerType: 'Owner type',
  displayName: 'Display name',
  primaryContactName: 'Primary contact name',
  email: 'Email',
  phone: 'Phone',
  city: 'City',
  state: 'State',
  notes: 'Notes',
}

function fieldErrorsFrom(details: string[]): { fields: Record<string, string>; rest: string[] } {
  const fields: Record<string, string> = {}
  const rest: string[] = []
  for (const detail of details) {
    const separator = detail.indexOf(': ')
    const label = separator === -1 ? '' : detail.slice(0, separator)
    const message = separator === -1 ? detail : detail.slice(separator + 2)
    const field = Object.keys(OWNER_FIELD_LABELS).find((key) => OWNER_FIELD_LABELS[key] === label)
    if (field && !fields[field]) fields[field] = message
    else rest.push(detail)
  }
  return { fields, rest }
}

function CreateOwnerDialog({
  open, onClose, onCreated,
}: {
  open: boolean
  onClose: () => void
  onCreated: (owner: OwnerSummary) => void
}) {
  const queryClient = useQueryClient()
  const [form, setForm] = useState<OwnerForm>(EMPTY_OWNER_FORM)
  const [errors, setErrors] = useState<Record<string, string>>({})

  const create = useMutation({
    mutationFn: (body: Record<string, unknown>) => ownerApi.create(body),
    onSuccess: (owner) => {
      void queryClient.invalidateQueries({ queryKey: ['owners'] })
      onCreated(owner)
      close()
    },
  })

  function close() {
    setForm(EMPTY_OWNER_FORM)
    setErrors({})
    create.reset()
    onClose()
  }

  const serverErrors = fieldErrorsFrom(detailsOf(create.error))
  const errorFor = (field: string): string | null => errors[field] ?? serverErrors.fields[field] ?? null

  function update<K extends keyof OwnerForm>(key: K, value: OwnerForm[K]) {
    setForm((current) => ({ ...current, [key]: value }))
  }

  function onSubmit(event: FormEvent) {
    event.preventDefault()
    const found = validateOwner(form)
    setErrors(found)
    if (Object.keys(found).length > 0) return

    create.mutate({
      ownerType: form.ownerType,
      displayName: form.displayName.trim(),
      primaryContactName: blankToUndefined(form.primaryContactName),
      email: blankToUndefined(form.email),
      phone: blankToUndefined(form.phone),
      city: blankToUndefined(form.city),
      state: blankToUndefined(form.state),
      notes: blankToUndefined(form.notes),
    })
  }

  return (
    <Dialog
      open={open}
      title="New owner"
      description="The owner is added to the register with an owner code minted for it. Their holdings are recorded against each property afterwards."
      width="lg"
      onClose={close}
      footer={
        <>
          <Button variant="ghost" onClick={close} disabled={create.isPending}>Cancel</Button>
          <Button type="submit" form="create-owner-form" variant="primary" loading={create.isPending}>
            {create.isPending ? 'Adding…' : 'Add owner'}
          </Button>
        </>
      }
    >
      <form id="create-owner-form" className="space-y-4" onSubmit={onSubmit} noValidate>
        {create.isError && (
          <ErrorState
            message={messageOf(create.error)}
            details={serverErrors.rest.length > 0 ? serverErrors.rest : undefined}
          />
        )}

        <div className="grid gap-4 sm:grid-cols-2">
          <Field label="Owner type" htmlFor="owner-type" required error={errorFor('ownerType')}>
            <Select id="owner-type" value={form.ownerType} onChange={(event) => update('ownerType', event.target.value)}>
              {OWNER_TYPES.map((option) => (
                <option key={option} value={option}>{humanize(option)}</option>
              ))}
            </Select>
          </Field>
          <Field label="Name" htmlFor="owner-name" required error={errorFor('displayName')}>
            <Input
              id="owner-name"
              value={form.displayName}
              onChange={(event) => update('displayName', event.target.value)}
              aria-invalid={Boolean(errorFor('displayName')) || undefined}
            />
          </Field>
        </div>

        <div className="grid gap-4 sm:grid-cols-2">
          <Field
            label="Primary contact"
            htmlFor="owner-contact"
            hint="For a trust, the trustee the firm deals with"
            error={errorFor('primaryContactName')}
          >
            <Input
              id="owner-contact"
              value={form.primaryContactName}
              onChange={(event) => update('primaryContactName', event.target.value)}
            />
          </Field>
          <Field label="Phone" htmlFor="owner-phone" error={errorFor('phone')}>
            <Input
              id="owner-phone"
              type="tel"
              value={form.phone}
              onChange={(event) => update('phone', event.target.value)}
            />
          </Field>
        </div>

        <Field label="E-mail" htmlFor="owner-email" error={errorFor('email')}>
          <Input
            id="owner-email"
            type="email"
            value={form.email}
            onChange={(event) => update('email', event.target.value)}
            aria-invalid={Boolean(errorFor('email')) || undefined}
          />
        </Field>

        <div className="grid gap-4 sm:grid-cols-2">
          <Field label="City" htmlFor="owner-city" error={errorFor('city')}>
            <Input id="owner-city" value={form.city} onChange={(event) => update('city', event.target.value)} />
          </Field>
          <Field label="State" htmlFor="owner-state" error={errorFor('state')}>
            <Input id="owner-state" value={form.state} onChange={(event) => update('state', event.target.value)} />
          </Field>
        </div>

        <Field label="Notes" htmlFor="owner-notes" error={errorFor('notes')}>
          <Textarea id="owner-notes" value={form.notes} onChange={(event) => update('notes', event.target.value)} />
        </Field>
      </form>
    </Dialog>
  )
}

function validateOwner(form: OwnerForm): Record<string, string> {
  const errors: Record<string, string> = {}

  if (form.ownerType === '') errors.ownerType = 'Choose an owner type'

  const name = form.displayName.trim()
  if (name === '') errors.displayName = 'Give the owner a name'
  else if (name.length > 200) errors.displayName = 'A name is at most 200 characters'

  const email = form.email.trim()
  if (email !== '' && !/^[^@\s]+@[^@\s]+\.[^@\s]+$/.test(email)) {
    errors.email = 'That does not look like an e-mail address'
  } else if (email.length > 255) {
    errors.email = 'An e-mail address is at most 255 characters'
  }

  if (form.phone.trim().length > 30) errors.phone = 'A phone number is at most 30 characters'

  return errors
}

/** A trimmed string, or `undefined` so an empty field is omitted from the body rather than sent blank. */
function blankToUndefined(value: string): string | undefined {
  const trimmed = value.trim()
  return trimmed === '' ? undefined : trimmed
}

/* ============================================================
 * Reading an owner's holdings
 * ============================================================ */

interface HoldingRow {
  id: string
  code: string
  name: string
  city: string | null
  percentage: number | null
  relationship: string | null
  effectiveFrom: string | null
  effectiveTo: string | null
  open: boolean
}

/**
 * An owner's assets, and this owner's share of each.
 *
 * A holding is not an attribute of an owner: it is a row on a property, with a percentage and a
 * period, and the property register is the only thing that can say which properties an owner holds
 * (`propertyApi.list({ ownerId })`). So one page of the owner's assets is read, and each asset's
 * shares are read from the ownership register - the register that owns the 100% arithmetic - and the
 * share belonging to **this** owner is the one shown.
 *
 * The read is capped at one page and fanned out inside a single query: one loading state, one failure,
 * and a bound on how many requests opening a file can cost. The dialog says plainly when the list has
 * been capped, so a reader never mistakes a partial list for the whole holding.
 *
 * Both reads are typed by the API map - `PropertyDtos.OwnershipShare` is a real record, not a loose
 * map - so every figure below is read by name rather than probed for.
 */
async function loadHoldings(ownerId: string): Promise<{ total: number; rows: HoldingRow[] }> {
  const assets = await propertyApi.list({ ownerId, size: HOLDINGS_PAGE_SIZE, sort: 'name,asc' })

  const rows = await Promise.all(assets.items.map(async (property): Promise<HoldingRow> => {
    const shares = await propertyApi.ownership(property.id)
    const share = shares.find((record) => record.ownerId === ownerId)
    return {
      id: share ? share.ownershipId : property.id,
      code: property.propertyCode,
      name: property.name,
      city: property.city || null,
      // A property the owner is named against but for which the ownership register records no share
      // reads an absent percentage rather than a zero: "the firm did not record a share" and "this
      // owner holds none" are different facts, and only one of them is on the wire.
      percentage: share ? share.percentage : null,
      relationship: share ? share.relationship : null,
      effectiveFrom: share ? share.effectiveFrom : null,
      effectiveTo: share ? share.effectiveTo : null,
      open: share ? share.open : false,
    }
  }))

  return { total: assets.totalElements, rows }
}

/** How many of an owner's assets a file opens. One page - see the note on `loadHoldings`. */
const HOLDINGS_PAGE_SIZE = 25

/* ============================================================
 * The closed sets, and the orders this register accepts
 * ============================================================ */

/** The owner types the schema allows (`ck_owners_type`). */
const OWNER_TYPES = [
  'INDIVIDUAL', 'COMPANY', 'PARTNERSHIP', 'TRUST', 'JOINT_VENTURE', 'GOVERNMENT', 'OTHER',
] as const

/** The owner statuses the schema allows (`ck_owners_status`). There is deliberately no blacklisting state. */
const OWNER_STATUSES = ['ACTIVE', 'INACTIVE'] as const

/**
 * The orders the owner register accepts, and nothing else.
 *
 * `OwnerController` whitelists these fields and answers 400 to any other, so the control offers only
 * what the API can honour. Both directions of every field a column sorts by are present, so the select
 * and the column headers cannot disagree about what is in force.
 */
const OWNER_SORTS: { value: string; label: string }[] = [
  { value: 'createdAt,desc', label: 'Newest first' },
  { value: 'displayName,asc', label: 'Name A–Z' },
  { value: 'displayName,desc', label: 'Name Z–A' },
  { value: 'ownerCode,asc', label: 'Owner code A–Z' },
  { value: 'city,asc', label: 'City A–Z' },
  { value: 'city,desc', label: 'City Z–A' },
  { value: 'status,asc', label: 'Status A–Z' },
  { value: 'status,desc', label: 'Status Z–A' },
]
