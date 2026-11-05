import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Building2, ListFilter, Pencil, Plus } from 'lucide-react'
import { useMemo, useState } from 'react'
import { PageAccessGuard, PageHeader } from '../components/common'
import {
  Badge, Button, DataTable, Dialog, EmptyState, ErrorState, Field, Input, KpiTile, Panel,
  Pagination, Select, Skeleton, StatusBadge, Textarea, Toolbar, useToast, type Column,
} from '../components/ui'
import {
  count as fmtCount, date, daysUntil, humanize, money, moneyCompact,
} from '../components/ui/formatters'
import { agentApi, listingApi, propertyApi } from '../services'
import { usePlatformStore } from '../platform'
import { messageOf } from '../lib/errors'
import type { AgentSummary, ListingSummary, PropertySummary, UnitSummary } from '../types'

/**
 * The listing board.
 *
 * Two rules from `ListingService` shape this screen, and both are stated here rather than discovered
 * by failing:
 *
 *  1. **A publication state is an operation, not a field.** `LISTING_ALLOWED` below transcribes the
 *     service's own map, and the state control offers nothing outside it, so a closed listing cannot
 *     be re-published and a live one cannot be "published" again.
 *  2. **Stale is a policy flag, not a status.** The badge is rendered from the API's own `stale` field,
 *     and the window it is measured against is read from the installation's profile rather than
 *     assumed.
 *
 * The service map exposes one state operation — `listingApi.changeStatus`, which is the service's
 * guarded `move` — rather than the three named endpoints the controller also serves. Publish,
 * withdraw and expire are therefore the same call with the state that means them, and each button is
 * rendered only where the map permits that target.
 */

/** The permitted publication-state moves, transcribed from `ListingService.ALLOWED`. */
const LISTING_ALLOWED: Record<string, string[]> = {
  DRAFT: ['PUBLISHED', 'ACTIVE', 'WITHDRAWN'],
  PUBLISHED: ['ACTIVE', 'UNDER_OFFER', 'WITHDRAWN', 'EXPIRED'],
  ACTIVE: ['UNDER_OFFER', 'LET_AGREED', 'SOLD', 'WITHDRAWN', 'EXPIRED'],
  UNDER_OFFER: ['ACTIVE', 'LET_AGREED', 'SOLD', 'WITHDRAWN', 'EXPIRED'],
  // Terminal: a closed listing is not re-opened.
  LET_AGREED: [],
  SOLD: [],
  WITHDRAWN: [],
  EXPIRED: [],
}

/** The states a listing is live in, mirroring `ListingStatus.isLive()`. */
const LIVE_STATUSES = ['PUBLISHED', 'ACTIVE', 'UNDER_OFFER']

const LISTING_STATUSES = ['DRAFT', 'PUBLISHED', 'ACTIVE', 'UNDER_OFFER', 'LET_AGREED', 'SOLD', 'WITHDRAWN', 'EXPIRED']
const LISTING_TYPES = ['RENT', 'SALE', 'RENT_AND_SALE']

const SORT_OPTIONS = [
  { value: 'createdAt,desc', label: 'Newest first' },
  { value: 'listingStart,desc', label: 'Most recently listed' },
  { value: 'askingRent,desc', label: 'Asking rent, high to low' },
  { value: 'askingPrice,desc', label: 'Asking price, high to low' },
  { value: 'listingCode,asc', label: 'Listing code' },
  { value: 'status,asc', label: 'Status' },
]

/**
 * The board row, as the endpoint answers it.
 *
 * `ListingDtos.ListingSummary` names the unit `unitNumber`, the channel by a code *and* a name, and the
 * agent `listedByName`. The shared `ListingSummary` in `src/types` is declared from that record, so the
 * cells below read the DTO's own names and this page carries no second spelling of them.
 */

/** A sentence for a sort the column headers set, which the picker does not list. */
function describeSort(sort: string): string {
  const [field, direction] = sort.split(',')
  return `Sorted by ${humanize(field).toLowerCase()} · ${direction === 'desc' ? 'descending' : 'ascending'}`
}

export default function ListingsPage() {
  return (
    <PageAccessGuard permission="LISTING_VIEW">
      <ListingBoard />
    </PageAccessGuard>
  )
}

function ListingBoard() {
  const queryClient = useQueryClient()
  const toast = useToast()
  const profile = usePlatformStore((state) => state.profile)
  const staleDays = profile?.policies?.listingStaleDays ?? 45

  const [page, setPage] = useState(0)
  const [size, setSize] = useState(20)
  const [sort, setSort] = useState('createdAt,desc')
  const [search, setSearch] = useState('')
  const [propertyId, setPropertyId] = useState('')
  const [unitId, setUnitId] = useState('')
  const [status, setStatus] = useState('')
  const [type, setType] = useState('')
  const [channel, setChannel] = useState('')
  const [agentId, setAgentId] = useState('')
  const [staleOnly, setStaleOnly] = useState(false)

  const [stateTarget, setStateTarget] = useState<{ listing: ListingSummary; target: string } | null>(null)
  const [editing, setEditing] = useState<ListingSummary | null>(null)
  const [creating, setCreating] = useState(false)

  const listingsQuery = useQuery({
    queryKey: ['listings', 'register', {
      page, size, sort, search, propertyId, unitId, status, type, channel, agentId, staleOnly,
    }],
    queryFn: () => listingApi.list({
      page,
      size,
      sort,
      search: search.trim() || undefined,
      propertyId: propertyId || undefined,
      unitId: unitId || undefined,
      status: status || undefined,
      type: type || undefined,
      channel: channel.trim() || undefined,
      agentId: agentId || undefined,
      staleOnly: staleOnly ? true : undefined,
    }),
  })

  const propertiesQuery = useQuery({
    queryKey: ['properties', 'picker'],
    queryFn: () => propertyApi.list({ size: 200 }),
  })

  // The unit picker is scoped to the property it belongs to, because a unit is only meaningful
  // inside its property — and the API reads a property's units from the property itself.
  const unitsQuery = useQuery({
    queryKey: ['properties', propertyId, 'units', 'picker'],
    queryFn: () => propertyApi.units(propertyId, { size: 200 }),
    enabled: propertyId !== '',
  })

  const agentsQuery = useQuery({
    queryKey: ['agents', 'register'],
    queryFn: () => agentApi.list({ size: 200 }),
  })

  const rows = listingsQuery.data?.items ?? []
  const properties: PropertySummary[] = propertiesQuery.data?.items ?? []
  const units: UnitSummary[] = unitsQuery.data?.items ?? []
  const activeAgents: AgentSummary[] = useMemo(
    () => (agentsQuery.data?.items ?? []).filter((agent) => agent.status === 'ACTIVE'),
    [agentsQuery.data],
  )

  const staleCount = rows.filter((row) => row.stale).length
  const liveCount = rows.filter((row) => LIVE_STATUSES.includes(row.status)).length
  const sortIsListed = SORT_OPTIONS.some((option) => option.value === sort)
  // An empty register means one of two different things, and the empty state says which.
  const narrowed = Boolean(status || type || channel.trim() || search.trim() || propertyId || staleOnly)

  const columns: Column<ListingSummary>[] = [
    {
      key: 'code', header: 'Listing', width: '11%', sortKey: 'listingCode',
      render: (row) => (
        <span className="block min-w-0">
          <span className="block truncate font-medium tabular-nums" style={{ color: 'var(--text-primary)' }}>
            {row.listingCode}
          </span>
          {row.headline && (
            <span className="block truncate text-[11px]" style={{ color: 'var(--text-secondary)' }}>{row.headline}</span>
          )}
        </span>
      ),
    },
    {
      key: 'stock', header: 'Property / unit', width: '16%', sortKey: 'property',
      render: (row) => (
        <span className="block min-w-0">
          <span className="block truncate" style={{ color: 'var(--text-primary)' }}>
            {row.propertyName ?? 'Property not recorded'}
          </span>
          <span className="block truncate text-[11px]" style={{ color: 'var(--text-secondary)' }}>
            {row.unitNumber ?? 'Whole property'}
            {row.propertyCode ? ` · ${row.propertyCode}` : ''}
          </span>
        </span>
      ),
    },
    {
      key: 'type', header: 'Type', width: '8%', sortKey: 'listingType',
      render: (row) => humanize(row.listingType),
    },
    {
      key: 'channel', header: 'Channel', width: '9%', secondary: true, sortKey: 'channel',
      render: (row) => row.channelName ?? row.channelCode ?? '—',
    },
    {
      key: 'terms', header: 'Asking terms', width: '12%', align: 'right', sortKey: 'askingRent',
      render: (row) => (
        <span className="block tabular-nums">
          {row.askingRent !== null && (
            <span className="block" style={{ color: 'var(--text-primary)' }}>{money(row.askingRent)}</span>
          )}
          {row.askingPrice !== null && (
            <span className="block text-[11px]" style={{ color: 'var(--text-secondary)' }}>
              sale {moneyCompact(row.askingPrice)}
            </span>
          )}
          {row.askingRent === null && row.askingPrice === null && '—'}
        </span>
      ),
    },
    {
      key: 'status', header: 'Status', width: '10%', sortKey: 'status',
      render: (row) => (
        <span className="flex flex-wrap items-center gap-1.5">
          <StatusBadge status={row.status} />
          {/* Stale is a policy flag, said in words as well as in tone. */}
          {row.stale && <Badge tone="amber">Stale</Badge>}
        </span>
      ),
    },
    {
      key: 'agent', header: 'Agent', width: '10%', sortKey: 'agent',
      render: (row) => row.listedByName ?? 'Unassigned',
    },
    {
      key: 'period', header: 'Listed / expiry', width: '9%', sortKey: 'listingStart',
      render: (row) => {
        const remaining = daysUntil(row.listingEnd)
        return (
          <span className="block tabular-nums text-[11px]">
            <span className="block" style={{ color: 'var(--text-primary)' }}>{date(row.listingStart)}</span>
            <span className="block" style={{ color: remaining !== null && remaining < 0 ? 'var(--danger)' : 'var(--text-secondary)' }}>
              {date(row.listingEnd)}{remaining !== null && remaining < 0 ? ' · passed' : ''}
            </span>
          </span>
        )
      },
    },
    {
      key: 'actions', header: 'Actions', width: '15%',
      render: (row) => {
        const permitted = LISTING_ALLOWED[row.status] ?? []
        const others = permitted.filter((each) => !['PUBLISHED', 'WITHDRAWN', 'EXPIRED'].includes(each))
        return (
          <span className="flex flex-wrap gap-1">
            {permitted.includes('PUBLISHED') && (
              <Button size="sm" variant="ghost" onClick={() => setStateTarget({ listing: row, target: 'PUBLISHED' })}>
                Publish
              </Button>
            )}
            {permitted.includes('WITHDRAWN') && (
              <Button size="sm" variant="ghost" onClick={() => setStateTarget({ listing: row, target: 'WITHDRAWN' })}>
                Withdraw
              </Button>
            )}
            {permitted.includes('EXPIRED') && (
              <Button size="sm" variant="ghost" onClick={() => setStateTarget({ listing: row, target: 'EXPIRED' })}>
                Expire
              </Button>
            )}
            {others.length > 0 && (
              <Button size="sm" variant="ghost" onClick={() => setStateTarget({ listing: row, target: others[0] })}>
                Move…
              </Button>
            )}
            <Button size="sm" variant="ghost" onClick={() => setEditing(row)}>
              <Pencil size={12} aria-hidden="true" />
              <span className="sr-only">Edit {row.listingCode}</span>
            </Button>
          </span>
        )
      },
    },
  ]

  return (
    <>
      <PageHeader
        eyebrow="Letting and sales"
        title="Listings"
        description={`The stock the firm is offering, and where each listing stands on the board. A listing is stale when it has been on the market longer than the firm's ${staleDays}-day window.`}
        actions={
          <Button variant="primary" onClick={() => setCreating(true)}>
            <Plus size={14} aria-hidden="true" /> Draft a listing
          </Button>
        }
      />

      <div className="mb-5 grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
        <KpiTile label="Listings on this page" value={fmtCount(rows.length)} tone="forest"
          caption={`${fmtCount(listingsQuery.data?.totalElements ?? 0)} match these filters`} />
        <KpiTile label="Live" value={fmtCount(liveCount)} tone="emerald"
          caption="Published, active or under offer, within the page shown" />
        <KpiTile label="Stale" value={fmtCount(staleCount)} tone="amber"
          caption={`Live and past the firm's ${staleDays}-day window, within the page shown`} />
        <KpiTile label="Page coverage" value={`${fmtCount(rows.length)} of ${fmtCount(size)}`} tone="slate"
          caption="The live and stale counts describe the loaded page, not the whole board" />
      </div>

      <Panel className="mb-4" bodyClassName="p-0">
        <Toolbar>
          <div className="w-full sm:w-52">
            <Field label="Search" htmlFor="listing-search" hint="Code, headline, description or stock">
              <Input
                id="listing-search"
                type="search"
                value={search}
                onChange={(event) => { setSearch(event.target.value); setPage(0) }}
              />
            </Field>
          </div>
          <div className="w-full sm:w-52">
            <Field label="Property" htmlFor="listing-property" hint={propertiesQuery.isError ? 'Property list unavailable' : undefined}>
              <Select
                id="listing-property"
                value={propertyId}
                disabled={propertiesQuery.isLoading || propertiesQuery.isError}
                onChange={(event) => {
                  setPropertyId(event.target.value)
                  // A unit belongs to one property, so changing the property clears the unit filter.
                  setUnitId('')
                  setPage(0)
                }}
              >
                <option value="">Every property</option>
                {properties.map((property) => (
                  <option key={property.id} value={property.id}>{property.name}</option>
                ))}
              </Select>
            </Field>
          </div>
          <div className="w-full sm:w-44">
            <Field
              label="Unit"
              htmlFor="listing-unit"
              hint={propertyId === '' ? 'Choose a property first' : undefined}
            >
              <Select
                id="listing-unit"
                value={unitId}
                disabled={propertyId === '' || unitsQuery.isLoading || unitsQuery.isError}
                onChange={(event) => { setUnitId(event.target.value); setPage(0) }}
              >
                <option value="">Every unit</option>
                {units.map((unit) => (
                  <option key={unit.id} value={unit.id}>{unit.unitNumber}</option>
                ))}
              </Select>
            </Field>
          </div>
          <div className="w-full sm:w-40">
            <Field label="Status" htmlFor="listing-status">
              <Select id="listing-status" value={status} onChange={(event) => { setStatus(event.target.value); setPage(0) }}>
                <option value="">Every status</option>
                {LISTING_STATUSES.map((each) => <option key={each} value={each}>{humanize(each)}</option>)}
              </Select>
            </Field>
          </div>
          <div className="w-full sm:w-36">
            <Field label="Type" htmlFor="listing-type">
              <Select id="listing-type" value={type} onChange={(event) => { setType(event.target.value); setPage(0) }}>
                <option value="">Every type</option>
                {LISTING_TYPES.map((each) => <option key={each} value={each}>{humanize(each)}</option>)}
              </Select>
            </Field>
          </div>
          <div className="w-full sm:w-40">
            <Field label="Channel code" htmlFor="listing-channel" hint="A listing channel's code">
              <Input
                id="listing-channel"
                value={channel}
                placeholder="e.g. PORTAL"
                onChange={(event) => { setChannel(event.target.value); setPage(0) }}
              />
            </Field>
          </div>
          <div className="w-full sm:w-48">
            <Field label="Agent" htmlFor="listing-agent" hint={agentsQuery.isError ? 'Agent list unavailable' : undefined}>
              <Select
                id="listing-agent"
                value={agentId}
                disabled={agentsQuery.isLoading || agentsQuery.isError}
                onChange={(event) => { setAgentId(event.target.value); setPage(0) }}
              >
                <option value="">Every agent</option>
                {activeAgents.map((agent) => (
                  <option key={agent.id} value={agent.id}>{agent.fullName ?? agent.agentCode}</option>
                ))}
              </Select>
            </Field>
          </div>
          <div className="w-full sm:w-44">
            <Field label="Sort" htmlFor="listing-sort">
              <Select id="listing-sort" value={sort} onChange={(event) => setSort(event.target.value)}>
                {!sortIsListed && <option value={sort}>{describeSort(sort)}</option>}
                {SORT_OPTIONS.map((option) => (
                  <option key={option.value} value={option.value}>{option.label}</option>
                ))}
              </Select>
            </Field>
          </div>
          {/* The stale filter is the one control the API takes as a boolean, so it is a checkbox. */}
          <label
            className="flex items-center gap-2 rounded-[var(--radius-control)] border px-3 py-2 text-xs"
            style={{ borderColor: 'var(--border)', color: 'var(--text-primary)' }}
            htmlFor="listing-stale"
          >
            <input
              id="listing-stale"
              type="checkbox"
              className="checkbox focus-meridian"
              checked={staleOnly}
              onChange={(event) => { setStaleOnly(event.target.checked); setPage(0) }}
            />
            Stale only
          </label>
        </Toolbar>
        <p className="px-4 py-2 text-xs" aria-live="polite" style={{ color: 'var(--text-secondary)' }}>
          {listingsQuery.isLoading
            ? 'Reading the board…'
            : `${fmtCount(listingsQuery.data?.totalElements ?? 0)} listing(s) match these filters.`}
        </p>
      </Panel>

      {listingsQuery.isLoading ? (
        <Panel><Skeleton rows={6} /></Panel>
      ) : listingsQuery.isError ? (
        <ErrorState message={messageOf(listingsQuery.error)} onRetry={() => void listingsQuery.refetch()} />
      ) : (
        <Panel bodyClassName="p-0">
          <DataTable
            columns={columns}
            rows={rows}
            keyOf={(row) => row.id}
            sort={sort}
            onSortChange={setSort}
            caption="Listing board"
            empty={
              <EmptyState
                icon={<ListFilter size={22} />}
                title={narrowed ? 'No listings match these filters' : 'Nothing is on the board yet'}
                description={narrowed
                  ? 'The filters are narrowing the board to nothing. Clear the status, channel or stale-only filter and the listings will come back.'
                  : 'A listing is drafted against a property or a unit and starts as a draft; publishing it puts it on the market. Nothing has been drafted yet.'}
                action={<Button variant="primary" onClick={() => setCreating(true)}>Draft a listing</Button>}
              />
            }
          />
          {rows.length > 0 && (
            <Pagination
              page={listingsQuery.data?.page ?? 0}
              size={listingsQuery.data?.size ?? size}
              totalElements={listingsQuery.data?.totalElements ?? 0}
              totalPages={listingsQuery.data?.totalPages ?? 0}
              hasNext={listingsQuery.data?.hasNext ?? false}
              onPageChange={setPage}
              onSizeChange={(next) => { setSize(next); setPage(0) }}
            />
          )}
        </Panel>
      )}

      <Panel className="mt-4" tint="sandstone">
        <div className="flex flex-wrap items-start gap-3">
          <Building2 size={18} aria-hidden="true" style={{ color: 'var(--ink-sandstone)' }} />
          <p className="max-w-3xl text-xs" style={{ color: 'var(--text-secondary)' }}>
            A listing is published onto the market, moved under offer, and closed as let agreed, sold,
            withdrawn or expired. Every one of those moves is validated by the service against its own
            lifecycle, so the buttons above appear only where a move is permitted — a closed listing has
            no further moves at all.
          </p>
        </div>
      </Panel>

      {/* Keyed on the row it was opened for, so a second listing's dialog never inherits the first's
          chosen state. */}
      <StateDialog
        key={stateTarget ? `${stateTarget.listing.id}-${stateTarget.target}` : 'no-state-target'}
        selection={stateTarget}
        onClose={() => setStateTarget(null)}
        onChanged={(code, to) => {
          void queryClient.invalidateQueries({ queryKey: ['listings'] })
          toast.push(`${code} moved to ${humanize(to)}.`)
          setStateTarget(null)
        }}
      />

      <ListingFormDialog
        key={creating ? 'new-listing' : `edit-${editing?.id ?? 'none'}`}
        open={creating || editing !== null}
        listing={editing}
        properties={properties}
        propertiesUnavailable={propertiesQuery.isError}
        agents={activeAgents}
        agentsUnavailable={agentsQuery.isError}
        onClose={() => { setCreating(false); setEditing(null) }}
        onSaved={(code, mode) => {
          void queryClient.invalidateQueries({ queryKey: ['listings'] })
          toast.push(mode === 'create' ? `${code} was drafted. Publish it when it is ready.` : `${code} was updated.`)
          setCreating(false)
          setEditing(null)
        }}
      />
    </>
  )
}

/**
 * Move a listing between publication states.
 *
 * The select holds exactly `ListingService.ALLOWED[current]`, so the dialog cannot ask for a move the
 * service refuses. The reason travels with the state because `closed_reason` is written in the same
 * transaction: a listing closed without a reason would leave the trail unexplained.
 */
function StateDialog({
  selection, onClose, onChanged,
}: {
  selection: { listing: ListingSummary; target: string } | null
  onClose: () => void
  onChanged: (listingCode: string, to: string) => void
}) {
  const listing = selection?.listing ?? null
  const permitted = listing ? LISTING_ALLOWED[listing.status] ?? [] : []
  const [target, setTarget] = useState('')
  const [reason, setReason] = useState('')
  const [failure, setFailure] = useState<string | null>(null)

  const chosen = target || selection?.target || permitted[0] || ''

  const changeStatus = useMutation({
    mutationFn: async () => listingApi.changeStatus(listing?.id ?? '', {
      status: chosen,
      reason: reason.trim() || null,
    }),
    onSuccess: (updated) => onChanged(listing?.listingCode ?? 'Listing', updated.status ?? chosen),
    onError: (error) => setFailure(messageOf(error)),
  })

  const submit = () => {
    setFailure(null)
    if (!listing || !chosen) {
      setFailure('Choose the state this listing should move to.')
      return
    }
    changeStatus.mutate()
  }

  const meaning: Record<string, string> = {
    PUBLISHED: 'offered to the market, but not yet marked live on the board',
    ACTIVE: 'live on the board',
    UNDER_OFFER: 'an offer is in hand',
    LET_AGREED: 'closed — a tenancy has been agreed',
    SOLD: 'closed — the asset has sold',
    WITHDRAWN: 'closed — withdrawn from the market',
    EXPIRED: 'closed — run past its end date',
  }

  return (
    <Dialog
      open={selection !== null}
      onClose={onClose}
      title={listing ? `Move ${listing.listingCode}` : 'Move a listing'}
      description={listing ? `Currently ${humanize(listing.status)}. ${permitted.length} move(s) are permitted from here.` : undefined}
      width="sm"
      footer={
        <>
          <Button variant="ghost" onClick={onClose} disabled={changeStatus.isPending}>Cancel</Button>
          <Button variant="primary" onClick={submit} loading={changeStatus.isPending}>Move the listing</Button>
        </>
      }
    >
      <div className="space-y-3">
        <Field
          label="Move to"
          htmlFor="listing-state-target"
          required
          hint={permitted.length === 0
            ? 'A closed listing has no further moves.'
            : `Permitted from ${humanize(listing?.status ?? '')}: ${permitted.map((each) => humanize(each)).join(', ')}.`}
        >
          <Select
            id="listing-state-target"
            value={chosen}
            disabled={permitted.length === 0}
            onChange={(event) => setTarget(event.target.value)}
          >
            {permitted.length === 0 && <option value="">No move is permitted</option>}
            {permitted.map((each) => (
              <option key={each} value={each}>{humanize(each)} — {meaning[each] ?? 'a permitted state'}</option>
            ))}
          </Select>
        </Field>
        <Field label="Reason" htmlFor="listing-state-reason" hint="Recorded on the status trail and on the listing itself when it closes">
          <Input id="listing-state-reason" value={reason} onChange={(event) => setReason(event.target.value)} />
        </Field>
        <div aria-live="polite">
          {failure && <p role="alert" className="text-sm" style={{ color: 'var(--danger)' }}>{failure}</p>}
        </div>
      </div>
    </Dialog>
  )
}

/**
 * Draft or change a listing.
 *
 * Creating and editing are deliberately different shapes, because the API's two requests are: a
 * listing is created against a property (and optionally a unit), and the update request cannot move it
 * — so on edit the stock is shown as a fixed fact rather than as a control that would silently do
 * nothing.
 *
 * The asking terms are checked here as `ListingService.assertTermsPresent` checks them: a RENT listing
 * needs a rent, a SALE listing needs a price, and a listing cannot end before it starts.
 */
function ListingFormDialog({
  open, listing, properties, propertiesUnavailable, agents, agentsUnavailable, onClose, onSaved,
}: {
  open: boolean
  listing: ListingSummary | null
  properties: PropertySummary[]
  propertiesUnavailable: boolean
  agents: AgentSummary[]
  agentsUnavailable: boolean
  onClose: () => void
  onSaved: (listingCode: string, mode: 'create' | 'edit') => void
}) {
  const editing = listing !== null
  /*
   * The dialog is mounted with a key derived from the row it was opened for, so these initial values
   * are read once per listing and never inherited from the previous one.
   */
  const [form, setForm] = useState(() => listing ? {
    propertyId: listing.propertyId ?? '',
    unitId: listing.unitId ?? '',
    listingType: listing.listingType,
    channelCode: listing.channelCode ?? '',
    headline: listing.headline ?? '',
    description: '',
    askingRent: listing.askingRent === null ? '' : String(listing.askingRent),
    askingPrice: listing.askingPrice === null ? '' : String(listing.askingPrice),
    listingStart: listing.listingStart ?? '',
    listingEnd: listing.listingEnd ?? '',
    listedById: listing.listedById ?? '',
    exclusive: listing.exclusive,
    negotiable: listing.negotiable,
  } : {
    propertyId: '', unitId: '', listingType: 'RENT', channelCode: '', headline: '', description: '',
    askingRent: '', askingPrice: '', listingStart: '', listingEnd: '', listedById: '',
    exclusive: false, negotiable: true,
  })
  const [failure, setFailure] = useState<string | null>(null)
  const [touched, setTouched] = useState(false)

  const unitsQuery = useQuery({
    queryKey: ['properties', form.propertyId, 'units', 'picker'],
    queryFn: () => propertyApi.units(form.propertyId, { size: 200 }),
    enabled: open && !editing && form.propertyId !== '',
  })

  const termsMissing =
    (form.listingType === 'RENT' && form.askingRent === '')
    || (form.listingType === 'SALE' && form.askingPrice === '')
    || (form.listingType === 'RENT_AND_SALE' && form.askingRent === '' && form.askingPrice === '')
  const periodInvalid =
    form.listingStart !== '' && form.listingEnd !== '' && form.listingEnd < form.listingStart

  const save = useMutation({
    mutationFn: async () => {
      const body = {
        listingType: form.listingType,
        channelCode: form.channelCode.trim() || null,
        headline: form.headline.trim(),
        description: form.description.trim() || null,
        askingRent: form.askingRent === '' ? null : Number(form.askingRent),
        askingPrice: form.askingPrice === '' ? null : Number(form.askingPrice),
        exclusive: form.exclusive,
        negotiable: form.negotiable,
        listingStart: form.listingStart || null,
        listingEnd: form.listingEnd || null,
        listedById: form.listedById || null,
      }
      if (listing) return listingApi.update(listing.id, body)
      return listingApi.create({
        ...body,
        // The stock is only settable on create: the update request cannot move a listing.
        propertyId: form.propertyId,
        unitId: form.unitId || null,
      })
    },
    onSuccess: (saved) => onSaved(saved.listingCode, editing ? 'edit' : 'create'),
    onError: (error) => setFailure(messageOf(error)),
  })

  const submit = () => {
    setTouched(true)
    setFailure(null)
    if (form.headline.trim() === '') {
      setFailure('A listing needs a headline — it is what the board reads the listing back as.')
      return
    }
    if (!editing && form.propertyId === '') {
      setFailure('Choose the property this listing offers.')
      return
    }
    if (termsMissing) {
      setFailure(`A ${humanize(form.listingType).toLowerCase()} listing needs its asking terms.`)
      return
    }
    if (periodInvalid) {
      setFailure('A listing cannot end before it starts.')
      return
    }
    save.mutate()
  }

  return (
    <Dialog
      open={open}
      onClose={onClose}
      title={editing ? `Change ${listing?.listingCode}` : 'Draft a listing'}
      description={editing
        ? 'The stock a listing offers is fixed once it is created; the terms, the channel and the agent can be changed.'
        : 'A new listing starts as a draft. Publishing it onto the market is a separate, guarded move.'}
      width="lg"
      footer={
        <>
          <Button variant="ghost" onClick={onClose} disabled={save.isPending}>Cancel</Button>
          <Button variant="primary" onClick={submit} loading={save.isPending}>
            {editing ? 'Save the listing' : 'Draft it'}
          </Button>
        </>
      }
    >
      <div className="grid gap-3 sm:grid-cols-2">
        {editing ? (
          <>
            <Field label="Property" htmlFor="listing-edit-property">
              <Input id="listing-edit-property" value={listing?.propertyName ?? '—'} readOnly disabled />
            </Field>
            <Field label="Unit" htmlFor="listing-edit-unit">
              <Input
                id="listing-edit-unit"
                value={listing?.unitNumber ?? 'Whole property'}
                readOnly
                disabled
              />
            </Field>
          </>
        ) : (
          <>
            <Field label="Property" htmlFor="listing-new-property" required hint={propertiesUnavailable ? 'Property list unavailable' : undefined}>
              <Select
                id="listing-new-property"
                value={form.propertyId}
                disabled={propertiesUnavailable}
                onChange={(event) => setForm({ ...form, propertyId: event.target.value, unitId: '' })}
              >
                <option value="">Choose a property</option>
                {properties.map((property) => (
                  <option key={property.id} value={property.id}>{property.name}</option>
                ))}
              </Select>
            </Field>
            <Field
              label="Unit"
              htmlFor="listing-new-unit"
              hint={form.propertyId === '' ? 'Choose a property first' : 'Leave blank to list the whole property'}
            >
              <Select
                id="listing-new-unit"
                value={form.unitId}
                disabled={form.propertyId === '' || unitsQuery.isLoading || unitsQuery.isError}
                onChange={(event) => setForm({ ...form, unitId: event.target.value })}
              >
                <option value="">Whole property</option>
                {(unitsQuery.data?.items ?? []).map((unit) => (
                  <option key={unit.id} value={unit.id}>{unit.unitNumber}</option>
                ))}
              </Select>
            </Field>
          </>
        )}

        <Field label="Offered to" htmlFor="listing-type-field">
          <Select
            id="listing-type-field"
            value={form.listingType}
            onChange={(event) => setForm({ ...form, listingType: event.target.value })}
          >
            {LISTING_TYPES.map((each) => (
              <option key={each} value={each}>
                {each === 'RENT' ? 'Let' : each === 'SALE' ? 'Sale' : 'Let or sale'}
              </option>
            ))}
          </Select>
        </Field>
        <Field label="Channel code" htmlFor="listing-channel-field" hint="A listing channel's code, or blank">
          <Input
            id="listing-channel-field"
            value={form.channelCode}
            onChange={(event) => setForm({ ...form, channelCode: event.target.value })}
          />
        </Field>
        <Field label="Listed by" htmlFor="listing-agent-field" hint={agentsUnavailable ? 'Agent list unavailable' : undefined}>
          <Select
            id="listing-agent-field"
            value={form.listedById}
            disabled={agentsUnavailable}
            onChange={(event) => setForm({ ...form, listedById: event.target.value })}
          >
            <option value="">Leave to the desk</option>
            {agents.map((agent) => (
              <option key={agent.id} value={agent.id}>{agent.fullName ?? agent.agentCode}</option>
            ))}
          </Select>
        </Field>
        <Field
          label="Headline"
          htmlFor="listing-headline"
          required
          error={touched && form.headline.trim() === '' ? 'A listing needs a headline' : null}
        >
          <Input
            id="listing-headline"
            value={form.headline}
            onChange={(event) => setForm({ ...form, headline: event.target.value })}
          />
        </Field>
        <div className="sm:col-span-2">
          <Field label="Description" htmlFor="listing-description">
            <Textarea
              id="listing-description"
              value={form.description}
              onChange={(event) => setForm({ ...form, description: event.target.value })}
            />
          </Field>
        </div>
        <Field
          label="Asking rent"
          htmlFor="listing-rent"
          error={touched && form.listingType === 'RENT' && form.askingRent === '' ? 'A letting needs an asking rent' : null}
          hint={form.listingType === 'SALE' ? 'Not used for a sale listing' : undefined}
        >
          <Input
            id="listing-rent"
            inputMode="decimal"
            value={form.askingRent}
            onChange={(event) => setForm({ ...form, askingRent: event.target.value })}
          />
        </Field>
        <Field
          label="Asking price"
          htmlFor="listing-price"
          error={touched && form.listingType === 'SALE' && form.askingPrice === '' ? 'A sale needs an asking price' : null}
          hint={form.listingType === 'RENT' ? 'Not used for a letting' : undefined}
        >
          <Input
            id="listing-price"
            inputMode="decimal"
            value={form.askingPrice}
            onChange={(event) => setForm({ ...form, askingPrice: event.target.value })}
          />
        </Field>
        <Field label="Listing starts" htmlFor="listing-start">
          <Input
            id="listing-start"
            type="date"
            value={form.listingStart}
            onChange={(event) => setForm({ ...form, listingStart: event.target.value })}
          />
        </Field>
        <Field
          label="Listing ends"
          htmlFor="listing-end"
          error={periodInvalid ? 'The end cannot be before the start' : null}
          hint="The window the stale flag is measured against"
        >
          <Input
            id="listing-end"
            type="date"
            value={form.listingEnd}
            onChange={(event) => setForm({ ...form, listingEnd: event.target.value })}
          />
        </Field>
        <label className="flex items-center gap-2 text-sm" style={{ color: 'var(--text-primary)' }} htmlFor="listing-exclusive">
          <input
            id="listing-exclusive"
            type="checkbox"
            className="checkbox focus-meridian"
            checked={form.exclusive}
            onChange={(event) => setForm({ ...form, exclusive: event.target.checked })}
          />
          Exclusive instruction
        </label>
        <label className="flex items-center gap-2 text-sm" style={{ color: 'var(--text-primary)' }} htmlFor="listing-negotiable">
          <input
            id="listing-negotiable"
            type="checkbox"
            className="checkbox focus-meridian"
            checked={form.negotiable}
            onChange={(event) => setForm({ ...form, negotiable: event.target.checked })}
          />
          Terms are negotiable
        </label>
      </div>

      <div aria-live="polite" className="mt-3">
        {failure && <p role="alert" className="text-sm" style={{ color: 'var(--danger)' }}>{failure}</p>}
      </div>
    </Dialog>
  )
}
