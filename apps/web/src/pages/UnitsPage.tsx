import { useEffect, useState, type FormEvent } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { PageAccessGuard, PageHeader } from '../components/common'
import {
  Button, DataTable, DetailItem, Dialog, EmptyState, ErrorState, Field, Input, Pagination, Panel,
  Select, Skeleton, StatusBadge, Textarea, Toolbar, useToast, type Column,
} from '../components/ui'
import { count as fmtCount, decimal, humanize, money } from '../components/ui/formatters'
import { propertyApi, referenceApi, unitApi, type PageParams } from '../services'
import { detailsOf, messageOf } from '../lib/errors'
import { useAuthStore } from '../auth/authStore'
import type { UnitSummary } from '../types'

/**
 * The unit register.
 *
 * Units are the busiest read in the product and the population every occupancy figure is computed
 * over, so the register is a dense schedule with a filter bar that mirrors the filters the API
 * actually applies - property, building, type, status, leasability, floor, bedrooms and a text term.
 * Everything is filtered, sorted and paged server-side; the browser never narrows a page it was
 * handed, which is what keeps the total under the table equal to the total in the register.
 *
 * There is no unit route, so the record opens in a dialog: the unit's own summary, read fresh, plus
 * the one write this screen owns - a status move, which the API records against the unit's history
 * and refuses when the move contradicts the unit's leasability or its running lease. Those refusals
 * are the backend's own sentences and are shown as they arrive rather than second-guessed here.
 */
export default function UnitsPage() {
  return (
    <PageAccessGuard permission="UNIT_VIEW">
      <UnitRegister />
    </PageAccessGuard>
  )
}

interface UnitFilters {
  propertyId: string
  buildingId: string
  unitTypeId: string
  status: string
  leasable: string
  floor: string
  bedrooms: string
  search: string
}

const NO_FILTERS: UnitFilters = {
  propertyId: '',
  buildingId: '',
  unitTypeId: '',
  status: '',
  leasable: '',
  floor: '',
  bedrooms: '',
  search: '',
}

function UnitRegister() {
  const can = useAuthStore((state) => state.can)
  // The building list hangs off a property and carries its own capability, so a caller without it is
  // offered the filter as unavailable rather than being given a control that answers 403.
  const canBuildings = can('BUILDING_VIEW')

  const [page, setPage] = useState(0)
  const [size, setSize] = useState(20)
  const [sort, setSort] = useState('createdAt,desc')
  const [criteria, setCriteria] = useState<UnitFilters>(NO_FILTERS)
  // The selects apply as they are chosen; the numeric and text filters wait for the form, because the
  // register is filtered in memory once read and a request per keystroke would re-read it each time.
  const [draft, setDraft] = useState({ floor: '', bedrooms: '', search: '' })
  const [openUnitId, setOpenUnitId] = useState<string | null>(null)

  function applyCriteria(patch: Partial<UnitFilters>) {
    setCriteria((current) => ({ ...current, ...patch }))
    setPage(0)
  }

  function applyForm(event: FormEvent) {
    event.preventDefault()
    applyCriteria({
      floor: draft.floor.trim(),
      bedrooms: draft.bedrooms.trim(),
      search: draft.search.trim(),
    })
  }

  function clear() {
    setCriteria(NO_FILTERS)
    setDraft({ floor: '', bedrooms: '', search: '' })
    setPage(0)
  }

  const params: PageParams = {
    page,
    size,
    sort,
    propertyId: criteria.propertyId || undefined,
    buildingId: criteria.buildingId || undefined,
    unitTypeId: criteria.unitTypeId || undefined,
    status: criteria.status || undefined,
    leasable: criteria.leasable === '' ? undefined : criteria.leasable === 'true',
    floor: intOrUndefined(criteria.floor),
    bedrooms: intOrUndefined(criteria.bedrooms),
    search: criteria.search || undefined,
  }

  const query = useQuery({
    queryKey: ['units', params],
    queryFn: () => unitApi.list(params),
  })

  // The property filter's own options. Read once at the register's page size limit; a property beyond
  // the first hundred is reachable by the free-text filter instead.
  const propertiesQuery = useQuery({
    queryKey: ['properties', 'filter-options'],
    queryFn: () => propertyApi.list({ size: 100, sort: 'name,asc' }),
  })

  const unitTypesQuery = useQuery({
    queryKey: ['reference', 'unit-types'],
    queryFn: () => referenceApi.unitTypes(),
  })

  const buildingsQuery = useQuery({
    queryKey: ['property', criteria.propertyId, 'buildings'],
    queryFn: () => propertyApi.buildings(criteria.propertyId),
    enabled: Boolean(criteria.propertyId) && canBuildings,
  })

  const rows = query.data?.items ?? []
  const totalElements = query.data?.totalElements ?? 0
  const filtered = Object.values(criteria).some((value) => value !== '')

  // Which of the filter-only reads failed, so the notice in the filter bar can name them precisely.
  const unavailableFilters: string[] = []
  if (propertiesQuery.isError) unavailableFilters.push('the property list')
  if (unitTypesQuery.isError) unavailableFilters.push('the unit types')
  if (buildingsQuery.isError) unavailableFilters.push('the blocks of the chosen property')

  const columns: Column<UnitSummary>[] = [
    {
      key: 'unit', header: 'Unit', width: '18%', sortKey: 'unitNumber',
      render: (row) => (
        <span className="block min-w-0">
          <span className="block truncate font-medium" style={{ color: 'var(--text-primary)' }}>{row.unitNumber}</span>
          <span className="block truncate text-[11px]" style={{ color: 'var(--text-secondary)' }}>{row.unitCode}</span>
        </span>
      ),
    },
    { key: 'property', header: 'Property', width: '14%', secondary: true, render: (row) => row.propertyName ?? '—' },
    { key: 'building', header: 'Building', width: '10%', secondary: true, render: (row) => row.buildingName ?? '—' },
    { key: 'floor', header: 'Floor', width: '6%', align: 'right', sortKey: 'floorNumber', render: (row) => fmtCount(row.floorNumber) },
    { key: 'type', header: 'Type', width: '10%', secondary: true, render: (row) => humanize(row.unitTypeName) },
    /*
     * "Carpet area" was read from `carpetAreaSqft`, which the unit *register* projection does not
     * send - `UnitDtos.UnitSummary` carries no areas at all; only `UnitDetail` does. The column drew a
     * dash on every row, so it now holds the bedrooms, which the register row does promise.
     */
    { key: 'bedrooms', header: 'Bedrooms', width: '12%', align: 'right', secondary: true, render: (row) => fmtCount(row.bedrooms) },
    { key: 'rent', header: 'Asking rent', width: '12%', align: 'right', sortKey: 'askingRent', render: (row) => money(row.askingRent) },
    { key: 'status', header: 'Status', width: '9%', sortKey: 'status', render: (row) => <StatusBadge status={row.status} /> },
    /*
     * "Tenant" was read from `tenantName`, which the register row does not send either: who is in a
     * unit is the *lease* in force on it, and that is `UnitDtos.UnitLeaseSummary`, read one unit at a
     * time from `/units/{id}/lease`. The column now shows the furnishing, which the row does carry.
     * The unit's own dialog still names its tenant, from the lease the detail projection carries.
     */
    { key: 'furnishing', header: 'Furnishing', width: '9%', secondary: true, render: (row) => humanize(row.furnishing) },
  ]

  return (
    <>
      <PageHeader
        eyebrow="Stock"
        title="Units"
        description="Every unit in the portfolio — its block, floor, type and asking rent — with the status that decides whether it counts towards occupancy. Open a unit to read its record and move its status."
      />

      <Panel bodyClassName="!p-0">
        <p className="sr-only" role="status" aria-live="polite">
          {query.isLoading
            ? 'Loading the unit register.'
            : `${fmtCount(totalElements)} units match the current filters.`}
        </p>

        <Toolbar>
          <div className="w-52">
            <Field label="Property" htmlFor="unit-property-filter">
              <Select
                id="unit-property-filter"
                value={criteria.propertyId}
                onChange={(event) => {
                  // A block belongs to one property, so changing the property clears the block rather
                  // than leaving a filter behind that cannot match anything.
                  setCriteria((current) => ({ ...current, propertyId: event.target.value, buildingId: '' }))
                  setPage(0)
                }}
                disabled={propertiesQuery.isLoading}
              >
                <option value="">{propertiesQuery.isLoading ? 'Loading properties…' : 'Any property'}</option>
                {(propertiesQuery.data?.items ?? []).map((property) => (
                  <option key={property.id} value={property.id}>{property.name}</option>
                ))}
              </Select>
            </Field>
          </div>

          <div className="w-44">
            <Field label="Building" htmlFor="unit-building-filter">
              <Select
                id="unit-building-filter"
                value={criteria.buildingId}
                onChange={(event) => applyCriteria({ buildingId: event.target.value })}
                disabled={!criteria.propertyId || !canBuildings || buildingsQuery.isLoading}
              >
                <option value="">
                  {!canBuildings
                    ? 'Not available to your role'
                    : !criteria.propertyId
                      ? 'Choose a property first'
                      : buildingsQuery.isLoading
                        ? 'Loading blocks…'
                        : 'Any building'}
                </option>
                {(buildingsQuery.data ?? []).map((building) => (
                  <option key={building.id} value={building.id}>{building.name}</option>
                ))}
              </Select>
            </Field>
          </div>

          <div className="w-40">
            <Field label="Unit type" htmlFor="unit-type-filter">
              <Select
                id="unit-type-filter"
                value={criteria.unitTypeId}
                onChange={(event) => applyCriteria({ unitTypeId: event.target.value })}
                disabled={unitTypesQuery.isLoading || unitTypesQuery.isError}
              >
                <option value="">
                  {unitTypesQuery.isLoading ? 'Loading types…' : 'Any type'}
                </option>
                {(unitTypesQuery.data ?? []).map((type) => (
                  <option key={type.id} value={type.id}>{type.name}</option>
                ))}
              </Select>
            </Field>
          </div>

          <div className="w-40">
            <Field label="Status" htmlFor="unit-status-filter">
              <Select
                id="unit-status-filter"
                value={criteria.status}
                onChange={(event) => applyCriteria({ status: event.target.value })}
              >
                <option value="">Any status</option>
                {UNIT_STATUSES.map((option) => (
                  <option key={option} value={option}>{humanize(option)}</option>
                ))}
              </Select>
            </Field>
          </div>

          <div className="w-32">
            <Field label="Leasable" htmlFor="unit-leasable-filter">
              <Select
                id="unit-leasable-filter"
                value={criteria.leasable}
                onChange={(event) => applyCriteria({ leasable: event.target.value })}
              >
                <option value="">Any</option>
                <option value="true">Leasable</option>
                <option value="false">Not leasable</option>
              </Select>
            </Field>
          </div>

          <form className="flex flex-wrap items-end gap-2" onSubmit={applyForm}>
            <div className="w-24">
              <Field label="Floor" htmlFor="unit-floor-filter">
                <Input
                  id="unit-floor-filter"
                  inputMode="numeric"
                  value={draft.floor}
                  onChange={(event) => setDraft((current) => ({ ...current, floor: event.target.value }))}
                  placeholder="Any"
                />
              </Field>
            </div>
            <div className="w-28">
              <Field label="Bedrooms" htmlFor="unit-bedrooms-filter">
                <Input
                  id="unit-bedrooms-filter"
                  inputMode="numeric"
                  value={draft.bedrooms}
                  onChange={(event) => setDraft((current) => ({ ...current, bedrooms: event.target.value }))}
                  placeholder="Any"
                />
              </Field>
            </div>
            <div className="w-48">
              <Field label="Search" htmlFor="unit-search-filter">
                <Input
                  id="unit-search-filter"
                  type="search"
                  value={draft.search}
                  onChange={(event) => setDraft((current) => ({ ...current, search: event.target.value }))}
                  placeholder="Unit number or code"
                />
              </Field>
            </div>
            <Button type="submit" size="sm">Apply filters</Button>
            <Button type="button" size="sm" variant="ghost" onClick={clear}>Clear</Button>
          </form>

          <div className="w-44 sm:ml-auto">
            <Field label="Sort by" htmlFor="unit-sort-filter">
              <Select id="unit-sort-filter" value={sort} onChange={(event) => { setSort(event.target.value); setPage(0) }}>
                {UNIT_SORTS.map((option) => (
                  <option key={option.value} value={option.value}>{option.label}</option>
                ))}
              </Select>
            </Field>
          </div>
        </Toolbar>

        {/*
         * A filter whose options could not be read is said out loud rather than left as a select with
         * nothing in it. The register itself is unaffected by any of these: they narrow it, they do
         * not feed it, so the table below still answers.
         */}
        {unavailableFilters.length > 0 && (
          <p className="px-4 py-2 text-xs" role="alert" style={{ color: 'var(--danger)' }}>
            {`Could not load ${unavailableFilters.join(' or ')}; the register itself is unaffected. `}
            <button
              type="button"
              className="focus-meridian underline"
              onClick={() => {
                void propertiesQuery.refetch()
                void unitTypesQuery.refetch()
                void buildingsQuery.refetch()
              }}
            >
              Try again
            </button>
          </p>
        )}

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
              onRowClick={(row) => setOpenUnitId(row.id)}
              sort={sort}
              onSortChange={(next) => { setSort(next); setPage(0) }}
              caption="Unit register"
              empty={filtered ? (
                <EmptyState
                  title="No units match these filters"
                  description="Nothing in the register answers to the property, block, type, status, leasability, floor or bedroom count applied here. Clear a filter, or search for the unit's number instead of its code."
                  action={<Button size="sm" onClick={clear}>Clear the filters</Button>}
                />
              ) : (
                <EmptyState
                  title="The unit register is empty"
                  description="Units are the stock a property is let and sold by, and every occupancy figure is computed over them. Record units against a property and they will appear here."
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

      <UnitDialog unitId={openUnitId} onClose={() => setOpenUnitId(null)} />
    </>
  )
}

/* ============================================================
 * One unit, and the one write this screen owns
 * ============================================================ */

function UnitDialog({ unitId, onClose }: { unitId: string | null; onClose: () => void }) {
  const queryClient = useQueryClient()
  const toast = useToast()
  const [status, setStatus] = useState('')
  const [reason, setReason] = useState('')

  // The record is read fresh when the dialog opens rather than reusing the row: the row is the list
  // projection, and a status move made a moment ago belongs in what the reader sees next.
  const detailQuery = useQuery({
    queryKey: ['unit', unitId],
    queryFn: () => unitApi.detail(unitId as string),
    enabled: Boolean(unitId),
  })

  const detail = detailQuery.data

  // The select follows the unit's stored status whenever a fresh record arrives.
  useEffect(() => {
    if (detail) setStatus(detail.status)
  }, [detail])

  const change = useMutation({
    mutationFn: (body: Record<string, unknown>) => unitApi.changeStatus(unitId as string, body),
    onSuccess: (updated) => {
      void queryClient.invalidateQueries({ queryKey: ['unit', unitId] })
      void queryClient.invalidateQueries({ queryKey: ['units'] })
      setReason('')
      change.reset()
      toast.push(`${updated.unitNumber} is now ${humanize(updated.status)}`, 'amber')
    },
  })

  function close() {
    setReason('')
    change.reset()
    onClose()
  }

  function onSubmit(event: FormEvent) {
    event.preventDefault()
    change.mutate({ status, reason: reason.trim() === '' ? undefined : reason.trim() })
  }

  return (
    <Dialog
      open={unitId !== null}
      title={detail ? `Unit ${detail.unitNumber}` : 'Unit'}
      description={detail
        ? [detail.unitCode, detail.propertyName, detail.buildingName].filter(Boolean).join(' · ')
        : 'Reading the unit record…'}
      width="lg"
      onClose={close}
      footer={
        <>
          <Button variant="ghost" onClick={close}>Close</Button>
          {detail && (
            <Button
              type="submit"
              form="unit-status-form"
              variant="primary"
              loading={change.isPending}
              disabled={status === detail.status}
            >
              {change.isPending ? 'Moving…' : 'Move the unit'}
            </Button>
          )}
        </>
      }
    >
      {detailQuery.isLoading ? (
        <Skeleton rows={6} />
      ) : detailQuery.isError ? (
        <ErrorState message={messageOf(detailQuery.error)} onRetry={() => void detailQuery.refetch()} />
      ) : detail ? (
        <div className="space-y-5">
          <dl className="grid gap-x-5 gap-y-3 sm:grid-cols-3">
            <DetailItem label="Unit number" value={detail.unitNumber} />
            <DetailItem label="Unit code" value={detail.unitCode} />
            <DetailItem label="Status" value={<StatusBadge status={detail.status} />} />
            <DetailItem label="Property" value={detail.propertyName ?? '—'} />
            <DetailItem label="Building" value={detail.buildingName ?? '—'} />
            <DetailItem label="Floor" value={fmtCount(detail.floorNumber)} />
            <DetailItem label="Type" value={humanize(detail.unitTypeName)} />
            <DetailItem
              label="Carpet area"
              value={detail.carpetAreaSqft === null ? '—' : `${decimal(detail.carpetAreaSqft, 0)} sq ft`}
            />
            <DetailItem
              label="Leasable"
              value={detail.leasable ? 'Yes — counts towards occupancy' : 'No — never part of occupancy'}
              tone={detail.leasable ? 'emerald' : 'slate'}
            />
            <DetailItem label="Asking rent" value={money(detail.askingRent)} tone="copper" />
            <DetailItem label="Asking price" value={money(detail.askingPrice)} />
            {/*
              Who is in the unit is the lease in force on it, not a column on the unit: the detail
              carries that lease as `UnitLeaseSummary`, so the name comes from there. A unit with no
              lease reads vacant; a lease that names no tenant says so rather than being blanked.
            */}
            <DetailItem
              label="Tenant"
              value={detail.currentLease === null
                ? 'Vacant'
                : detail.currentLease.tenantName ?? 'Let — tenant not named'}
            />
          </dl>

          <form
            id="unit-status-form"
            className="space-y-4 border-t pt-4"
            style={{ borderColor: 'var(--border)' }}
            onSubmit={onSubmit}
            noValidate
          >
            {change.isError && <ErrorState message={messageOf(change.error)} details={detailsOf(change.error)} />}

            <div className="grid gap-4 sm:grid-cols-2">
              <Field
                label="New status"
                htmlFor="unit-status"
                required
                hint="Available, reserved and occupied are the letting states; a sold unit has reached the end of its lifecycle."
              >
                <Select id="unit-status" value={status} onChange={(event) => setStatus(event.target.value)}>
                  {UNIT_STATUSES.map((option) => (
                    <option key={option} value={option}>{humanize(option)}</option>
                  ))}
                </Select>
              </Field>

              <Field
                label="Reason"
                htmlFor="unit-status-reason"
                hint="Optional, and kept with the transition in the unit's history."
              >
                <Textarea
                  id="unit-status-reason"
                  value={reason}
                  onChange={(event) => setReason(event.target.value)}
                />
              </Field>
            </div>

            <p className="text-xs" style={{ color: 'var(--text-secondary)' }}>
              A unit that is not available to let can only be marked not lettable or under maintenance,
              and a unit that already carries a running lease cannot be marked as let again — the API
              refuses both, and says so here.
            </p>
          </form>
        </div>
      ) : (
        <EmptyState
          title="That unit is no longer on the register"
          description="It may have been archived, which withdraws a unit without erasing it, or the register may have moved on since this page was read. Refresh the register to see what it holds now."
        />
      )}
    </Dialog>
  )
}

/* ============================================================
 * The closed sets, and the orders this register accepts
 * ============================================================ */

/** The statuses the schema allows for a unit (`ck_units_status`). */
const UNIT_STATUSES = [
  'AVAILABLE', 'RESERVED', 'OCCUPIED', 'UNDER_MAINTENANCE', 'LISTED', 'UNDER_CONTRACT', 'SOLD', 'NOT_LETTABLE',
] as const

/**
 * The orders the unit register accepts, and nothing else.
 *
 * `UnitController` whitelists these fields and answers 400 to any other, so the control offers only
 * what the API can honour. Both directions of every field a column sorts by are present, so the
 * select and the column headers cannot disagree about what is in force.
 */
const UNIT_SORTS: { value: string; label: string }[] = [
  { value: 'createdAt,desc', label: 'Newest first' },
  { value: 'unitNumber,asc', label: 'Unit number 1–9' },
  { value: 'unitNumber,desc', label: 'Unit number 9–1' },
  { value: 'floorNumber,asc', label: 'Lowest floor first' },
  { value: 'floorNumber,desc', label: 'Highest floor first' },
  { value: 'askingRent,desc', label: 'Highest asking rent' },
  { value: 'askingRent,asc', label: 'Lowest asking rent' },
  { value: 'status,asc', label: 'Status A–Z' },
  { value: 'status,desc', label: 'Status Z–A' },
]

/**
 * A whole number from a text field, or `undefined` for "no filter".
 *
 * A value the API would refuse (a fraction, a word) is treated as no filter rather than sent: the
 * register answers an unparseable number with a 400, and a filter bar that can 400 the whole screen
 * is worse than one that ignores a stray character.
 */
function intOrUndefined(value: string): number | undefined {
  const trimmed = value.trim()
  if (trimmed === '') return undefined
  const parsed = Number(trimmed)
  return Number.isInteger(parsed) ? parsed : undefined
}
