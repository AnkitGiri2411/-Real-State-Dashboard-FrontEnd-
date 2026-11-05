import { useEffect, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Plus } from 'lucide-react'
import { PageAccessGuard, PageHeader } from '../components/common'
import {
  Badge, Button, DataTable, DetailItem, Dialog, EmptyState, ErrorState, Field, Input, Panel,
  Pagination, Select, Skeleton, StatusBadge, Toolbar, TONE_INK, useToast, type Column,
} from '../components/ui'
import { count as fmtCount, date, humanize, money } from '../components/ui/formatters'
import { buyerApi } from '../services'
import { detailsOf, messageOf } from '../lib/errors'
import { useAuthStore } from '../auth/authStore'
import type { BuyerSummary } from '../types'

/**
 * The buyer register.
 *
 * A buyer is a client the firm is trying to place, and what they are looking for is not a column on
 * the buyer row — it is a brief that changes over time. Opening a buyer therefore shows three things
 * the detail endpoint projects for us in one request: the briefs (active first, then the superseded
 * ones as history), the viewings they have attended, and the offers they have made.
 *
 * **The budget range is a range, and it runs forwards.** `minBudget <= maxBudget` is checked here
 * before anything is sent, and the API's own refusal — which it reports as a named 400 rather than an
 * opaque integrity error — is shown as a sentence beside the field it concerns.
 */

const BUYER_STATUS_OPTIONS = [
  { value: 'ACTIVE', label: 'Active' },
  { value: 'INACTIVE', label: 'Inactive' },
  { value: 'CONVERTED', label: 'Converted' },
]

const BUYER_TYPE_OPTIONS = [
  { value: 'INDIVIDUAL', label: 'Individual' },
  { value: 'COMPANY', label: 'Company' },
  { value: 'NRI', label: 'NRI' },
  { value: 'TRUST', label: 'Trust' },
  { value: 'FUND', label: 'Fund' },
  { value: 'JOINT_VENTURE', label: 'Joint venture' },
  { value: 'OTHER', label: 'Other' },
]

/** The human label each input's field failure arrives under, mirroring `GlobalExceptionHandler`. */
const BUYER_FIELD_LABELS: Record<string, string> = {
  buyerCode: 'Buyer code',
  buyerType: 'Buyer type',
  displayName: 'Display name',
  email: 'Email',
  phone: 'Phone',
  city: 'City',
}

/*
 * `buyerApi.detail` answers `BuyerDtos.BuyerDetail` - the register summary plus the briefs, viewings
 * and offers the service projects for the buyer page. That record is declared in `src/types` from the
 * DTO itself, so the three lists are drawn from the one request without a boundary cast and without a
 * local second copy of the contract.
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

/** A budget range reads as a range; an absent bound is a dash, never a zero. */
function budgetRange(min: number | null, max: number | null): string {
  if (min === null && max === null) return 'No budget stated'
  return `${money(min)} – ${money(max)}`
}

/** A bedroom count reads as a bound, not as a single number, because the brief is a minimum. */
function bedroomsText(min: number | null, max: number | null): string {
  if (min === null && max === null) return 'Any'
  if (min !== null && max === null) return `${min}+`
  if (min === null) return `Up to ${max}`
  return `${min}–${max}`
}

export default function BuyersPage() {
  return (
    <PageAccessGuard permission="BUYER_VIEW">
      <BuyerRegister />
    </PageAccessGuard>
  )
}

function BuyerRegister() {
  const canCreate = useAuthStore((state) => state.can('BUYER_CREATE'))
  const canUpdate = useAuthStore((state) => state.can('BUYER_UPDATE'))

  const [status, setStatus] = useState('')
  const [type, setType] = useState('')
  const [city, setCity] = useState('')
  const [searchInput, setSearchInput] = useState('')
  const search = useDebounced(searchInput)
  const [page, setPage] = useState(0)
  const [size, setSize] = useState(20)

  const [selected, setSelected] = useState<BuyerSummary | null>(null)
  const [editing, setEditing] = useState<BuyerSummary | null>(null)
  const [creating, setCreating] = useState(false)

  const query = useQuery({
    queryKey: ['buyers', { status, type, city, search, page, size }],
    queryFn: () => buyerApi.list({ status, type, city, search, page, size }),
  })

  const hasFilters = Boolean(status || type || city || search)

  const columns: Column<BuyerSummary>[] = [
    {
      key: 'buyer',
      header: 'Buyer',
      width: '26%',
      render: (row) => (
        <span className="block min-w-0">
          <span className="block truncate font-medium" style={{ color: 'var(--text-primary)' }}>{row.displayName}</span>
          <span className="block truncate text-[11px] tabular-nums" style={{ color: 'var(--text-secondary)' }}>{row.buyerCode}</span>
        </span>
      ),
    },
    {
      key: 'type',
      header: 'Type',
      width: '13%',
      secondary: true,
      render: (row) => humanize(row.buyerType),
    },
    {
      key: 'city',
      header: 'City',
      width: '15%',
      secondary: true,
      render: (row) => row.city ?? '—',
    },
    /*
     * This column used to be "Budget range", read from `minBudget`/`maxBudget` on the buyer row.
     * `BuyerDtos.BuyerSummary` sends neither: a budget is not a column on a buyer, it is a **brief** -
     * `RequirementRecord`, with its own min and max and its own history - and it is read from the
     * buyer's detail. So the register's slot now holds the buyer's state, which the row does promise,
     * and each brief's range is shown in the buyer's own file below.
     */
    {
      key: 'state',
      header: 'State',
      width: '30%',
      secondary: true,
      render: (row) => row.state ?? '—',
    },
    {
      key: 'status',
      header: 'Status',
      width: '16%',
      render: (row) => <StatusBadge status={row.status} />,
    },
  ]

  if (query.isLoading) {
    return (
      <>
        <PageHeader title="Buyers" description="Reading the buyer register…" />
        <Skeleton rows={6} />
      </>
    )
  }

  if (query.isError) {
    return (
      <>
        <PageHeader title="Buyers" />
        <ErrorState message={messageOf(query.error)} onRetry={() => void query.refetch()} />
      </>
    )
  }

  const items = query.data?.items ?? []
  const totalElements = query.data?.totalElements ?? 0

  return (
    <>
      <PageHeader
        eyebrow="Demand"
        title="Buyers"
        description="The clients the firm is placing. Open a row for the brief they are buying to, the viewings they have attended and the offers they have made."
        actions={
          canCreate ? (
            <Button variant="primary" onClick={() => setCreating(true)}>
              <Plus size={16} aria-hidden="true" />
              New buyer
            </Button>
          ) : undefined
        }
      />

      <Panel notch className="mb-3">
        <Toolbar>
          <div className="w-full sm:w-44">
            <Field label="Status" htmlFor="buyers-status">
              <Select id="buyers-status" value={status} onChange={(event) => { setStatus(event.target.value); setPage(0) }}>
                <option value="">All statuses</option>
                {BUYER_STATUS_OPTIONS.map((option) => <option key={option.value} value={option.value}>{option.label}</option>)}
              </Select>
            </Field>
          </div>
          <div className="w-full sm:w-44">
            <Field label="Type" htmlFor="buyers-type">
              <Select id="buyers-type" value={type} onChange={(event) => { setType(event.target.value); setPage(0) }}>
                <option value="">All types</option>
                {BUYER_TYPE_OPTIONS.map((option) => <option key={option.value} value={option.value}>{option.label}</option>)}
              </Select>
            </Field>
          </div>
          <div className="w-full sm:w-40">
            <Field label="City" htmlFor="buyers-city">
              <Input id="buyers-city" value={city} placeholder="Any city" onChange={(event) => { setCity(event.target.value); setPage(0) }} />
            </Field>
          </div>
          <div className="w-full sm:w-64">
            <Field label="Search" htmlFor="buyers-search" hint="Name, code, email or phone.">
              <Input
                id="buyers-search"
                type="search"
                value={searchInput}
                placeholder="Search the register"
                onChange={(event) => { setSearchInput(event.target.value); setPage(0) }}
              />
            </Field>
          </div>
        </Toolbar>

        <p className="px-4 pt-3 text-xs" aria-live="polite" style={{ color: 'var(--text-secondary)' }}>
          {query.isFetching ? 'Reading the register…' : `${fmtCount(totalElements)} buyer${totalElements === 1 ? '' : 's'} in this view`}
        </p>

        <DataTable
          columns={columns}
          rows={items}
          keyOf={(row) => row.id}
          onRowClick={(row) => setSelected(row)}
          caption="Buyer register"
          empty={
            hasFilters ? (
              <EmptyState
                title="No buyers match these filters"
                description="Nothing in the register matches the status, type, city or search you chose. Clear a filter to widen the view."
              />
            ) : (
              <EmptyState
                title="The buyer register is empty"
                description="No buyers have been added yet. A buyer appears here once one is created, and their brief is what fills the budget column."
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

      {selected && (
        <BuyerDetailDialog
          buyer={selected}
          canEdit={canUpdate}
          onEdit={() => { setEditing(selected); setSelected(null) }}
          onClose={() => setSelected(null)}
        />
      )}

      {editing && <BuyerFormDialog buyer={editing} onClose={() => setEditing(null)} />}
      {creating && <BuyerFormDialog buyer={null} onClose={() => setCreating(false)} />}
    </>
  )
}

/* ============================================================
 * The detail dialog - profile, briefs, viewings and offers
 * ============================================================ */
function BuyerDetailDialog({
  buyer, canEdit, onEdit, onClose,
}: {
  buyer: BuyerSummary
  canEdit: boolean
  onEdit: () => void
  onClose: () => void
}) {
  const detail = useQuery({
    queryKey: ['buyers', buyer.id, 'detail'],
    queryFn: () => buyerApi.detail(buyer.id),
  })

  const footer = (
    <>
      {canEdit && <Button onClick={onEdit}>Edit buyer</Button>}
      <Button variant="ghost" onClick={onClose}>Close</Button>
    </>
  )

  if (detail.isLoading) {
    return (
      <Dialog open title={buyer.displayName} description="Reading the buyer's record…" onClose={onClose} width="lg" footer={footer}>
        <Skeleton rows={6} />
      </Dialog>
    )
  }

  if (detail.isError) {
    return (
      <Dialog open title={buyer.displayName} onClose={onClose} width="lg" footer={footer}>
        <ErrorState message={messageOf(detail.error)} onRetry={() => void detail.refetch()} />
      </Dialog>
    )
  }

  const record = detail.data
  if (!record) {
    return (
      <Dialog open title={buyer.displayName} onClose={onClose} width="lg" footer={footer}>
        <EmptyState
          title="This buyer's record could not be read"
          description="The buyer was found in the register but their detail did not come back. Try again, or reopen them from the register."
        />
      </Dialog>
    )
  }

  const requirements = record.requirements ?? []
  const viewings = record.viewings ?? []
  const offers = record.offers ?? []

  return (
    <Dialog
      open
      title={record.displayName}
      description={`Buyer ${record.buyerCode} — brief, viewings and offers.`}
      onClose={onClose}
      width="lg"
      footer={footer}
    >
      <div className="space-y-4">
        <dl className="grid grid-cols-2 gap-3 sm:grid-cols-3">
          <DetailItem label="Type" value={humanize(record.buyerType)} />
          <DetailItem label="Status" value={<StatusBadge status={record.status} />} />
          <DetailItem label="City" value={record.city ?? '—'} />
          <DetailItem label="Email" value={record.email ?? '—'} />
          <DetailItem label="Phone" value={record.phone ?? '—'} />
          {/*
            The budget is not on the buyer: it is on the brief, and the briefs are listed below with
            their own ranges. What the buyer *does* carry is how many briefs are in force, which is the
            figure the header reads beside the name.
          */}
          <DetailItem label="Briefs in force" value={fmtCount(record.activeRequirementCount)} tone="copper" />
        </dl>

        {/* The brief is what the buyer is actually looking for; the active one leads, and the
            superseded ones follow as history so "why was this property shown" stays answerable. */}
        <Panel title="Requirement brief" subtitle="What this buyer is looking for" tint="sapphire">
          {requirements.length === 0 ? (
            <p className="text-sm" style={{ color: 'var(--text-secondary)' }}>
              No brief has been stated for this buyer yet. One appears here once it is recorded against them.
            </p>
          ) : (
            <ul className="space-y-2">
              {requirements.map((requirement) => (
                <li
                  key={requirement.requirementId}
                  className="rounded-[var(--radius-card)] border px-3 py-2"
                  style={{ borderColor: 'var(--border)', background: 'var(--surface-elevated)' }}
                >
                  <div className="flex flex-wrap items-center justify-between gap-2">
                    <span className="text-xs font-semibold" style={{ color: 'var(--text-primary)' }}>
                      {requirement.purpose ? humanize(requirement.purpose) : 'Purpose not stated'}
                    </span>
                    <Badge tone={requirement.active ? 'emerald' : 'slate'}>{requirement.active ? 'Active' : 'Superseded'}</Badge>
                  </div>
                  <dl className="mt-2 grid grid-cols-2 gap-x-3 gap-y-1 sm:grid-cols-4">
                    <DetailItem label="City" value={requirement.preferredCity ?? 'Any'} />
                    <DetailItem label="Property type" value={requirement.propertyTypeName ?? requirement.propertyTypeCode ?? 'Any'} />
                    <DetailItem label="Budget" value={budgetRange(requirement.minBudget, requirement.maxBudget)} tone="copper" />
                    <DetailItem label="Bedrooms" value={bedroomsText(requirement.minBedrooms, requirement.maxBedrooms)} />
                  </dl>
                </li>
              ))}
            </ul>
          )}
        </Panel>

        <Panel title="Viewings" subtitle="Properties this buyer has been shown" tint="teal">
          {viewings.length === 0 ? (
            <p className="text-sm" style={{ color: 'var(--text-secondary)' }}>
              This buyer has not been booked into a viewing yet. One appears here once a viewing is scheduled for them.
            </p>
          ) : (
            <ul className="divide-y" style={{ borderColor: 'var(--border)' }}>
              {viewings.map((viewing) => (
                <li key={viewing.viewingId} className="flex items-center justify-between gap-3 py-2 first:pt-0 last:pb-0">
                  <span className="min-w-0">
                    <span className="block truncate text-xs font-semibold" style={{ color: 'var(--text-primary)' }}>
                      {viewing.propertyName ?? viewing.viewingCode}
                    </span>
                    <span className="block truncate text-[11px] tabular-nums" style={{ color: 'var(--text-secondary)' }}>
                      {viewing.viewingCode} · {date(viewing.scheduledAt)}
                    </span>
                  </span>
                  <span className="flex shrink-0 items-center gap-2">
                    {viewing.interested !== null && (
                      <Badge tone={viewing.interested ? 'emerald' : 'slate'}>{viewing.interested ? 'Interested' : 'Not interested'}</Badge>
                    )}
                    <StatusBadge status={viewing.status} />
                  </span>
                </li>
              ))}
            </ul>
          )}
        </Panel>

        {/* Money reads copper, so the offer amounts sit in the copper family rather than the
            default ink. */}
        <Panel title="Offers" subtitle="Offers made on this buyer's sales" tint="copper">
          {offers.length === 0 ? (
            <p className="text-sm" style={{ color: 'var(--text-secondary)' }}>
              No offers have been recorded for this buyer. One appears here once an offer is made on one of their sales.
            </p>
          ) : (
            <ul className="divide-y" style={{ borderColor: 'var(--border)' }}>
              {offers.map((offer) => (
                <li key={offer.offerId} className="flex items-center justify-between gap-3 py-2 first:pt-0 last:pb-0">
                  <span className="min-w-0">
                    <span className="block truncate text-xs font-semibold" style={{ color: 'var(--text-primary)' }}>
                      {offer.offerNumber}{offer.saleCode ? ` · ${offer.saleCode}` : ''}
                    </span>
                    <span className="block truncate text-[11px]" style={{ color: 'var(--text-secondary)' }}>
                      {humanize(offer.direction)} · {date(offer.offeredAt)}
                    </span>
                  </span>
                  <span className="flex shrink-0 items-center gap-2">
                    <span className="text-xs font-semibold tabular-nums" style={{ color: TONE_INK.copper }}>{money(offer.amount)}</span>
                    <StatusBadge status={offer.status} />
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
/*
 * The buyer's own fields - and nothing else.
 *
 * A budget used to be collected here and posted to `/buyers`, where `CreateBuyerRequest` and
 * `UpdateBuyerRequest` have no such fields: the request would have been accepted and the figures
 * dropped. A buyer's budget is a **brief** - `BuyerDtos.RequirementRecord`, with its own min and max,
 * its own bedrooms and its own active flag, written through `/buyers/{id}/requirements` - so this form
 * no longer offers a control the buyer endpoint cannot honour. The briefs are read and shown in full on
 * the buyer's file.
 */
interface BuyerForm {
  buyerCode: string
  buyerType: string
  displayName: string
  email: string
  phone: string
  city: string
}

function emptyBuyerForm(): BuyerForm {
  return { buyerCode: '', buyerType: 'INDIVIDUAL', displayName: '', email: '', phone: '', city: '' }
}

function formFromBuyer(buyer: BuyerSummary): BuyerForm {
  return {
    buyerCode: buyer.buyerCode,
    buyerType: buyer.buyerType,
    displayName: buyer.displayName,
    email: buyer.email ?? '',
    phone: buyer.phone ?? '',
    city: buyer.city ?? '',
  }
}

function blankToNull(value: string): string | null {
  const trimmed = value.trim()
  return trimmed === '' ? null : trimmed
}

function validateBuyer(form: BuyerForm, editing: boolean): Record<string, string> {
  const errors: Record<string, string> = {}
  if (!editing && !form.buyerCode.trim()) errors.buyerCode = 'A buyer code is required.'
  if (!form.buyerType) errors.buyerType = 'A buyer type is required.'
  if (!form.displayName.trim()) errors.displayName = 'A name is required.'
  if (form.email.trim() && !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(form.email.trim())) {
    errors.email = 'That does not look like an email address.'
  }
  return errors
}

/** Return the API's field failures keyed by input, with a whole-record refusal left as a sentence. */
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

function BuyerFormDialog({ buyer, onClose }: { buyer: BuyerSummary | null; onClose: () => void }) {
  const queryClient = useQueryClient()
  const toast = useToast()
  const editing = buyer !== null

  const [form, setForm] = useState<BuyerForm>(() => (buyer ? formFromBuyer(buyer) : emptyBuyerForm()))
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({})
  const [generalError, setGeneralError] = useState<string | null>(null)

  const mutation = useMutation({
    mutationFn: (body: Record<string, unknown>) =>
      editing && buyer ? buyerApi.update(buyer.id, body) : buyerApi.create(body),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['buyers'] })
      toast.push(editing ? 'Buyer updated' : 'Buyer added')
      onClose()
    },
    onError: (error) => {
      const mapped = mapFieldDetails(detailsOf(error), BUYER_FIELD_LABELS)
      setFieldErrors(mapped.fields)
      setGeneralError(mapped.other ?? messageOf(error))
      toast.push(messageOf(error), 'terracotta')
    },
  })

  // Written as an explicit copy-and-assign rather than a computed key in the literal, so the
  // narrowing is obvious to the compiler and to the next reader.
  const update = (key: keyof BuyerForm, value: string) =>
    setForm((current) => {
      const next = { ...current }
      next[key] = value
      return next
    })

  const submit = () => {
    const errors = validateBuyer(form, editing)
    setFieldErrors(errors)
    setGeneralError(null)
    if (Object.keys(errors).length > 0) return
    const body: Record<string, unknown> = {
      buyerType: form.buyerType,
      displayName: form.displayName.trim(),
      email: blankToNull(form.email),
      phone: blankToNull(form.phone),
      city: blankToNull(form.city),
    }
    // The code is supplied by the caller on create only; a change to it is not offered on edit.
    if (!editing) body.buyerCode = form.buyerCode.trim()
    mutation.mutate(body)
  }

  return (
    <Dialog
      open
      title={editing ? `Edit ${buyer?.displayName ?? 'buyer'}` : 'New buyer'}
      description={editing
        ? 'The buyer code is fixed once a buyer is on the register.'
        : 'The buyer code is supplied here — the firm has no seeded series for buyers.'}
      onClose={onClose}
      footer={
        <>
          <Button variant="ghost" onClick={onClose} disabled={mutation.isPending}>Cancel</Button>
          <Button variant="primary" onClick={submit} loading={mutation.isPending}>
            {editing ? 'Save changes' : 'Add buyer'}
          </Button>
        </>
      }
    >
      <div className="space-y-3">
        {generalError && <ErrorState message={generalError} />}

        {!editing && (
          <Field
            label="Buyer code"
            htmlFor="buyer-code"
            required
            error={fieldErrors.buyerCode}
            hint="A short reference unique to this buyer, up to 30 characters."
          >
            <Input id="buyer-code" value={form.buyerCode} autoComplete="off" onChange={(event) => update('buyerCode', event.target.value)} />
          </Field>
        )}

        <Field label="Display name" htmlFor="buyer-display-name" required error={fieldErrors.displayName}>
          <Input id="buyer-display-name" value={form.displayName} autoComplete="off" onChange={(event) => update('displayName', event.target.value)} />
        </Field>

        <Field label="Buyer type" htmlFor="buyer-type-field" required error={fieldErrors.buyerType}>
          <Select id="buyer-type-field" value={form.buyerType} onChange={(event) => update('buyerType', event.target.value)}>
            {BUYER_TYPE_OPTIONS.map((option) => <option key={option.value} value={option.value}>{option.label}</option>)}
          </Select>
        </Field>

        <div className="grid gap-3 sm:grid-cols-2">
          <Field label="Email" htmlFor="buyer-email" error={fieldErrors.email}>
            <Input id="buyer-email" type="email" value={form.email} autoComplete="off" onChange={(event) => update('email', event.target.value)} />
          </Field>
          <Field label="Phone" htmlFor="buyer-phone" error={fieldErrors.phone}>
            <Input id="buyer-phone" value={form.phone} autoComplete="off" onChange={(event) => update('phone', event.target.value)} />
          </Field>
        </div>

        <Field label="City" htmlFor="buyer-city-field" error={fieldErrors.city}>
          <Input id="buyer-city-field" value={form.city} autoComplete="off" onChange={(event) => update('city', event.target.value)} />
        </Field>
      </div>
    </Dialog>
  )
}
