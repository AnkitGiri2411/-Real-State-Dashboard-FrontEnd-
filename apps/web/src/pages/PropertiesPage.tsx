import { useState, type FormEvent } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useNavigate } from 'react-router-dom'
import { Plus } from 'lucide-react'
import { PageAccessGuard, PageHeader } from '../components/common'
import {
  Button, DataTable, Dialog, EmptyState, ErrorState, Field, Input, Pagination, Panel, Select,
  Skeleton, StatusBadge, Textarea, Toolbar, useToast, type Column,
} from '../components/ui'
import { count as fmtCount, humanize, moneyCompact } from '../components/ui/formatters'
import { propertyApi, referenceApi, type PageParams } from '../services'
import { detailsOf, messageOf } from '../lib/errors'
import { useAuthStore } from '../auth/authStore'
import type { PropertySummary } from '../types'

/**
 * The property register.
 *
 * The asset register is the spine of the product - units, leases, invoices and documents all hang
 * off a property - so this screen is a schedule rather than a gallery: a filtered, sorted, paged
 * ledger whose columns are the facts a person scans the book for, and a create form for the one
 * record the desk adds most often.
 *
 * **Every filter here is server-side.** The register's filters, sort and slice are applied by the
 * API (see `PropertyService.list`), so the page the reader asks for is the page they get and the
 * total under the table is the total the register holds. Nothing on this screen is filtered in the
 * browser, which is what stops the count and the admin filters drifting apart.
 *
 * The create form is the only write. It carries the one rule the interface can catch before the
 * request leaves: **a position is both coordinates or neither**, because the schema stores the pair
 * under a paired constraint and the service refuses half a position as a 400 - a reader who typed a
 * latitude deserves to be told beside the field, not by a failed request.
 */
export default function PropertiesPage() {
  return (
    <PageAccessGuard permission="PROPERTY_VIEW">
      <PropertyRegister />
    </PageAccessGuard>
  )
}

/**
 * The register itself.
 *
 * Deliberately a second component: the guard above refuses a caller without `PROPERTY_VIEW` before
 * this one ever renders, so the register's query is never fired on the strength of a route typed
 * into the address bar. The guard explains the refusal; the API enforces it.
 */
function PropertyRegister() {
  const navigate = useNavigate()
  const toast = useToast()
  const can = useAuthStore((state) => state.can)
  const canCreate = can('PROPERTY_CREATE')

  const [page, setPage] = useState(0)
  const [size, setSize] = useState(20)
  const [sort, setSort] = useState('createdAt,desc')
  const [criteria, setCriteria] = useState({ status: '', city: '', search: '' })
  // The selects apply as they are chosen; the free-text filters wait for the form. The register is
  // filtered in memory once it has been read, so a request per keystroke would re-read the whole
  // book to answer a question the reader has not finished typing.
  const [draft, setDraft] = useState({ city: '', search: '' })
  const [creating, setCreating] = useState(false)

  const params: PageParams = {
    page,
    size,
    sort,
    status: criteria.status || undefined,
    city: criteria.city || undefined,
    search: criteria.search || undefined,
  }

  const query = useQuery({
    queryKey: ['properties', params],
    queryFn: () => propertyApi.list(params),
  })

  const rows = query.data?.items ?? []
  const totalElements = query.data?.totalElements ?? 0
  const filtered = Boolean(criteria.status || criteria.city || criteria.search)

  function apply(event: FormEvent) {
    event.preventDefault()
    setCriteria((current) => ({ ...current, city: draft.city.trim(), search: draft.search.trim() }))
    setPage(0)
  }

  function clear() {
    setCriteria({ status: '', city: '', search: '' })
    setDraft({ city: '', search: '' })
    setPage(0)
  }

  function changeSort(next: string) {
    setSort(next)
    setPage(0)
  }

  const columns: Column<PropertySummary>[] = [
    {
      key: 'property',
      header: 'Property',
      width: '32%',
      sortKey: 'name',
      render: (row) => (
        <span className="block min-w-0">
          <span className="block truncate font-medium" style={{ color: 'var(--text-primary)' }}>{row.name}</span>
          <span className="block truncate text-[11px]" style={{ color: 'var(--text-secondary)' }}>{row.propertyCode}</span>
        </span>
      ),
    },
    {
      key: 'city', header: 'City', width: '18%', sortKey: 'city', secondary: true,
      render: (row) => row.city || '—',
    },
    {
      key: 'type', header: 'Type', width: '18%', secondary: true,
      render: (row) => humanize(row.propertyTypeName),
    },
    {
      key: 'valuation', header: 'Valuation', width: '18%', align: 'right', secondary: true,
      sortKey: 'currentValuation',
      render: (row) => moneyCompact(row.currentValuation),
    },
    {
      key: 'status', header: 'Status', width: '14%', sortKey: 'status',
      render: (row) => <StatusBadge status={row.status} />,
    },
  ]

  /*
   * Three columns used to sit between Type and Valuation - Units, Occupancy and Monthly rent - and
   * they have been taken out rather than left to render a column of dashes.
   *
   * `GET /properties` answers with `PropertyDtos.PropertySummary`, and that record carries no unit
   * count, no occupancy share and no rent roll: those live on `PropertyMetrics`, which is read one
   * property at a time from `/properties/{id}/metrics`. There is no endpoint that returns them beside
   * a whole page of rows, so the only honest ways to fill the columns would have been to invent a
   * figure or to fire one request per row - both of which are worse than the column not being there.
   *
   * The register still reports the two figures it does promise: the type and the current valuation.
   * A reader who wants a property's stock, occupancy and rent opens the property, whose page reads the
   * metrics projection once and shows all three.
   */

  return (
    <>
      <PageHeader
        eyebrow="Asset register"
        title="Properties"
        description="Every asset under management, with its stock, occupancy, rent roll and valuation. Open a property for its buildings, units and ownership."
        actions={canCreate ? (
          <Button variant="primary" onClick={() => setCreating(true)}>
            <Plus size={15} aria-hidden="true" />
            New property
          </Button>
        ) : undefined}
      />

      <Panel bodyClassName="!p-0">
        {/* The register's own outcome, announced rather than only drawn. */}
        <p className="sr-only" role="status" aria-live="polite">
          {query.isLoading
            ? 'Loading the property register.'
            : `${fmtCount(totalElements)} properties match the current filters.`}
        </p>

        <Toolbar>
          <div className="w-44">
            <Field label="Status" htmlFor="property-status-filter">
              <Select
                id="property-status-filter"
                value={criteria.status}
                onChange={(event) => {
                  setCriteria((current) => ({ ...current, status: event.target.value }))
                  setPage(0)
                }}
              >
                <option value="">Any status</option>
                {PROPERTY_STATUSES.map((status) => (
                  <option key={status} value={status}>{humanize(status)}</option>
                ))}
              </Select>
            </Field>
          </div>

          <form className="flex flex-wrap items-end gap-2" onSubmit={apply}>
            <div className="w-40">
              <Field label="City" htmlFor="property-city-filter">
                <Input
                  id="property-city-filter"
                  value={draft.city}
                  onChange={(event) => setDraft((current) => ({ ...current, city: event.target.value }))}
                  placeholder="Any city"
                />
              </Field>
            </div>
            <div className="w-56">
              <Field label="Search" htmlFor="property-search-filter">
                <Input
                  id="property-search-filter"
                  type="search"
                  value={draft.search}
                  onChange={(event) => setDraft((current) => ({ ...current, search: event.target.value }))}
                  placeholder="Name or property code"
                />
              </Field>
            </div>
            <Button type="submit" size="sm">Apply filters</Button>
            <Button type="button" size="sm" variant="ghost" onClick={clear}>Clear</Button>
          </form>

          <div className="w-48 sm:ml-auto">
            <Field label="Sort by" htmlFor="property-sort-filter">
              <Select
                id="property-sort-filter"
                value={sort}
                onChange={(event) => changeSort(event.target.value)}
              >
                {PROPERTY_SORTS.map((option) => (
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
              onRowClick={(row) => navigate(`/properties/${row.id}`)}
              sort={sort}
              onSortChange={changeSort}
              caption="Property register"
              empty={filtered ? (
                <EmptyState
                  title="No properties match these filters"
                  description="Nothing in the register answers to the status, city or search term applied here. Clear a filter, or search for the property code instead of the property's name."
                  action={<Button size="sm" onClick={clear}>Clear the filters</Button>}
                />
              ) : (
                <EmptyState
                  title="The register holds no properties yet"
                  description="A property is the asset everything else hangs off — its units, leases, invoices and documents. Add the first one and this register fills in with its occupancy and rent roll."
                  action={canCreate
                    ? <Button variant="primary" size="sm" onClick={() => setCreating(true)}>New property</Button>
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

      <CreatePropertyDialog
        open={creating}
        onClose={() => setCreating(false)}
        onCreated={() => {
          // The register is read newest first by default, so returning to the first page is where the
          // property just added will be.
          setPage(0)
          toast.push('Property added to the register', 'emerald')
        }}
      />
    </>
  )
}

/* ============================================================
 * The create form
 * ============================================================ */

interface PropertyForm {
  name: string
  propertyTypeId: string
  addressLine1: string
  city: string
  locality: string
  description: string
  latitude: string
  longitude: string
}

const EMPTY_FORM: PropertyForm = {
  name: '',
  propertyTypeId: '',
  addressLine1: '',
  city: '',
  locality: '',
  description: '',
  latitude: '',
  longitude: '',
}

/**
 * The label the API puts in front of a field error, mapped back to the input it belongs beside.
 *
 * `GlobalExceptionHandler` reports bean-validation failures as `"<Humanised field>: <message>"`, so
 * the two halves of that sentence are exactly what a form needs - and because the mapping is stated
 * rather than guessed, a detail the form cannot place is listed above the form instead of being
 * silently dropped.
 */
const FIELD_LABELS: Record<string, string> = {
  name: 'Name',
  propertyTypeId: 'Property type id',
  addressLine1: 'Address line1',
  city: 'City',
  locality: 'Locality',
  description: 'Description',
  latitude: 'Latitude',
  longitude: 'Longitude',
}

function fieldErrorsFrom(details: string[]): { fields: Record<string, string>; rest: string[] } {
  const fields: Record<string, string> = {}
  const rest: string[] = []
  for (const detail of details) {
    const separator = detail.indexOf(': ')
    const label = separator === -1 ? '' : detail.slice(0, separator)
    const message = separator === -1 ? detail : detail.slice(separator + 2)
    const field = Object.keys(FIELD_LABELS).find((key) => FIELD_LABELS[key] === label)
    if (field && !fields[field]) fields[field] = message
    else rest.push(detail)
  }
  return { fields, rest }
}

/** A trimmed string, or `undefined` so an empty field is omitted from the body rather than sent blank. */
function blankToUndefined(value: string): string | undefined {
  const trimmed = value.trim()
  return trimmed === '' ? undefined : trimmed
}

function numberOrNull(value: string): number | null {
  const trimmed = value.trim()
  if (trimmed === '') return null
  const parsed = Number(trimmed)
  return Number.isFinite(parsed) ? parsed : null
}

function CreatePropertyDialog({
  open, onClose, onCreated,
}: {
  open: boolean
  onClose: () => void
  onCreated: () => void
}) {
  const queryClient = useQueryClient()
  const [form, setForm] = useState<PropertyForm>(EMPTY_FORM)
  const [errors, setErrors] = useState<Record<string, string>>({})

  // The property types are a reference list the backend owns, so the form reads them rather than
  // spelling them out here - a type the schema gains must not need a frontend release.
  const typesQuery = useQuery({
    queryKey: ['reference', 'property-types'],
    queryFn: () => referenceApi.propertyTypes(),
  })

  const create = useMutation({
    mutationFn: (body: Record<string, unknown>) => propertyApi.create(body),
    onSuccess: () => {
      // The register is the record of truth for what exists; refetch it rather than editing the
      // cached page by hand, so the row's computed occupancy and rent come from the same query the
      // rest of the book does.
      void queryClient.invalidateQueries({ queryKey: ['properties'] })
      onCreated()
      close()
    },
  })

  function close() {
    setForm(EMPTY_FORM)
    setErrors({})
    create.reset()
    onClose()
  }

  const serverErrors = fieldErrorsFrom(detailsOf(create.error))
  const errorFor = (field: string): string | null => errors[field] ?? serverErrors.fields[field] ?? null

  function update<K extends keyof PropertyForm>(key: K, value: PropertyForm[K]) {
    setForm((current) => ({ ...current, [key]: value }))
  }

  function onSubmit(event: FormEvent) {
    event.preventDefault()
    const found = validate(form)
    setErrors(found)
    if (Object.keys(found).length > 0) return

    const latitude = numberOrNull(form.latitude)
    const longitude = numberOrNull(form.longitude)

    create.mutate({
      name: form.name.trim(),
      propertyTypeId: form.propertyTypeId,
      addressLine1: form.addressLine1.trim(),
      city: form.city.trim(),
      locality: blankToUndefined(form.locality),
      description: blankToUndefined(form.description),
      latitude: latitude ?? undefined,
      longitude: longitude ?? undefined,
    })
  }

  return (
    <Dialog
      open={open}
      title="New property"
      description="The asset is created in draft. Its buildings, units and ownership are recorded against it afterwards."
      width="lg"
      onClose={close}
      footer={
        <>
          <Button variant="ghost" onClick={close} disabled={create.isPending}>Cancel</Button>
          {/* The submit lives in the dialog's footer, which sits outside the form element - hence the
              explicit `form` association rather than wrapping the footer in the form. */}
          <Button type="submit" form="create-property-form" variant="primary" loading={create.isPending}>
            {create.isPending ? 'Adding…' : 'Add property'}
          </Button>
        </>
      }
    >
      <form id="create-property-form" className="space-y-4" onSubmit={onSubmit} noValidate>
        {create.isError && (
          <ErrorState
            message={messageOf(create.error)}
            details={serverErrors.rest.length > 0 ? serverErrors.rest : undefined}
          />
        )}

        <div className="grid gap-4 sm:grid-cols-2">
          <Field label="Property name" htmlFor="property-name" required error={errorFor('name')}>
            <Input
              id="property-name"
              value={form.name}
              onChange={(event) => update('name', event.target.value)}
              aria-invalid={Boolean(errorFor('name')) || undefined}
            />
          </Field>

          <Field
            label="Property type"
            htmlFor="property-type"
            required
            error={errorFor('propertyTypeId')}
            hint={typesQuery.isError ? undefined : 'Sets whether the asset is lettable stock'}
          >
            <Select
              id="property-type"
              value={form.propertyTypeId}
              onChange={(event) => update('propertyTypeId', event.target.value)}
              disabled={typesQuery.isLoading || typesQuery.isError}
              aria-invalid={Boolean(errorFor('propertyTypeId')) || undefined}
            >
              <option value="">{typesQuery.isLoading ? 'Loading property types…' : 'Choose a type'}</option>
              {(typesQuery.data ?? []).map((type) => (
                <option key={type.id} value={type.id}>{type.name}</option>
              ))}
            </Select>
          </Field>
        </div>

        {typesQuery.isError && (
          <p className="text-xs" role="alert" style={{ color: 'var(--danger)' }}>
            The property types could not be loaded, so a property cannot be added yet.{' '}
            <button type="button" className="focus-meridian underline" onClick={() => void typesQuery.refetch()}>
              Try again
            </button>
          </p>
        )}

        <Field label="Address line 1" htmlFor="property-address" required error={errorFor('addressLine1')}>
          <Input
            id="property-address"
            value={form.addressLine1}
            onChange={(event) => update('addressLine1', event.target.value)}
            aria-invalid={Boolean(errorFor('addressLine1')) || undefined}
          />
        </Field>

        <div className="grid gap-4 sm:grid-cols-2">
          <Field label="City" htmlFor="property-city" required error={errorFor('city')}>
            <Input
              id="property-city"
              value={form.city}
              onChange={(event) => update('city', event.target.value)}
              aria-invalid={Boolean(errorFor('city')) || undefined}
            />
          </Field>
          <Field label="Locality" htmlFor="property-locality" error={errorFor('locality')}>
            <Input
              id="property-locality"
              value={form.locality}
              onChange={(event) => update('locality', event.target.value)}
            />
          </Field>
        </div>

        <Field
          label="Description"
          htmlFor="property-description"
          error={errorFor('description')}
          hint="What the asset is, in the words the desk uses for it"
        >
          <Textarea
            id="property-description"
            value={form.description}
            onChange={(event) => update('description', event.target.value)}
          />
        </Field>

        {/*
         * Position is entered as a pair.
         *
         * The schema holds latitude and longitude under a paired constraint and the service refuses
         * half a position as a named 400 - so the rule is stated here, beside the fields, rather than
         * left to a failed request. A property with no position is perfectly valid: it is simply not
         * plotted on the map, which is the honest answer rather than a marker at a guessed point.
         */}
        <fieldset className="rounded-[var(--radius-card)] border p-3" style={{ borderColor: 'var(--border)' }}>
          <legend className="px-1 text-xs font-semibold" style={{ color: 'var(--text-secondary)' }}>
            Position (optional, both or neither)
          </legend>
          <div className="grid gap-4 sm:grid-cols-2">
            <Field label="Latitude" htmlFor="property-latitude" error={errorFor('latitude')}>
              <Input
                id="property-latitude"
                inputMode="decimal"
                value={form.latitude}
                onChange={(event) => update('latitude', event.target.value)}
                aria-invalid={Boolean(errorFor('latitude')) || undefined}
                placeholder="e.g. 12.9716"
              />
            </Field>
            <Field label="Longitude" htmlFor="property-longitude" error={errorFor('longitude')}>
              <Input
                id="property-longitude"
                inputMode="decimal"
                value={form.longitude}
                onChange={(event) => update('longitude', event.target.value)}
                aria-invalid={Boolean(errorFor('longitude')) || undefined}
                placeholder="e.g. 77.5946"
              />
            </Field>
          </div>
        </fieldset>
      </form>
    </Dialog>
  )
}

/** The client-side check. Only the rules the request would be refused for; nothing else is invented. */
function validate(form: PropertyForm): Record<string, string> {
  const errors: Record<string, string> = {}

  const name = form.name.trim()
  if (name === '') errors.name = 'Give the property a name'
  else if (name.length > 180) errors.name = 'A name is at most 180 characters'

  if (form.propertyTypeId === '') errors.propertyTypeId = 'Choose a property type'

  const address = form.addressLine1.trim()
  if (address === '') errors.addressLine1 = 'The address is required'
  else if (address.length > 200) errors.addressLine1 = 'An address line is at most 200 characters'

  const city = form.city.trim()
  if (city === '') errors.city = 'The city is required'
  else if (city.length > 120) errors.city = 'A city is at most 120 characters'

  if (form.locality.trim().length > 120) errors.locality = 'A locality is at most 120 characters'

  const hasLatitude = form.latitude.trim() !== ''
  const hasLongitude = form.longitude.trim() !== ''

  if (hasLatitude !== hasLongitude) {
    // The rule the database's paired constraint exists to enforce - stated here so it is answered
    // beside the field instead of coming back as a refusal.
    const message = 'A position is both coordinates or neither'
    if (!hasLatitude) errors.latitude = message
    if (!hasLongitude) errors.longitude = message
  } else {
    const latitude = numberOrNull(form.latitude)
    const longitude = numberOrNull(form.longitude)
    if (hasLatitude && (latitude === null || latitude < -90 || latitude > 90)) {
      errors.latitude = 'A latitude is a number between -90 and 90'
    }
    if (hasLongitude && (longitude === null || longitude < -180 || longitude > 180)) {
      errors.longitude = 'A longitude is a number between -180 and 180'
    }
  }

  return errors
}

/**
 * The statuses the schema allows (`ck_properties_status`).
 *
 * Listed from the same closed set the request record validates against, so the filter can never offer
 * a value the API would answer "that is not a property status" to.
 */
const PROPERTY_STATUSES = [
  'DRAFT', 'LISTED', 'AVAILABLE_ACTIVE', 'UNDER_CONTRACT', 'LEASED', 'SOLD', 'INACTIVE',
] as const

/**
 * The orders this register accepts, and nothing else.
 *
 * `PageRequestFactory` whitelists the sortable fields and answers 400 to any other, so the control
 * offers only the accepted four. Both directions of every field a column can sort by are listed, so
 * the select and the column headers can never disagree about what is in force.
 */
const PROPERTY_SORTS: { value: string; label: string }[] = [
  { value: 'createdAt,desc', label: 'Newest first' },
  { value: 'name,asc', label: 'Name A–Z' },
  { value: 'name,desc', label: 'Name Z–A' },
  { value: 'city,asc', label: 'City A–Z' },
  { value: 'city,desc', label: 'City Z–A' },
  { value: 'currentValuation,desc', label: 'Highest valuation' },
  { value: 'currentValuation,asc', label: 'Lowest valuation' },
  { value: 'status,asc', label: 'Status A–Z' },
  { value: 'status,desc', label: 'Status Z–A' },
]
