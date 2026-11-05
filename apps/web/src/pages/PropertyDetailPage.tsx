import { useEffect, useState, type FormEvent } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { PageAccessGuard, ConfirmDialog, PageHeader } from '../components/common'
import {
  Button, DataTable, DetailItem, Dialog, EmptyState, ErrorState, Field, KpiTile, Pagination,
  Panel, Select, Skeleton, StatusBadge, Textarea, useToast, type Column,
} from '../components/ui'
import {
  count as fmtCount, date, decimal, humanize, money, moneyCompact, percent,
} from '../components/ui/formatters'
import { propertyApi, type PageParams } from '../services'
import { ApiError, detailsOf, messageOf } from '../lib/errors'
import { useAuthStore } from '../auth/authStore'
import type { BuildingSummary, PropertyOwnershipShare, UnitSummary } from '../types'

/**
 * One property's record.
 *
 * The asset page is built from the endpoints that actually exist under a property, and from nothing
 * else. `PropertyService` exposes a detail, a metrics projection, the buildings within it, a paged
 * view of its units and its ownership shares - so those are the panels here. Amenities, documents and
 * the status timeline exist on the backend but have no method on the frontend's API map, so no panel
 * is offered for them: a panel for an endpoint this build cannot call would be a promise the screen
 * cannot keep.
 *
 * **Each sub-resource carries its own permission server-side** (`BUILDING_VIEW`, `UNIT_VIEW`,
 * `OWNER_VIEW`), so a panel is rendered only when the caller holds the capability that would let the
 * API answer it - and the query behind it is not even fired otherwise. The guard is a courtesy: every
 * endpoint re-checks the same permission, and a route typed into the address bar is refused there.
 */
export default function PropertyDetailPage() {
  return (
    <PageAccessGuard permission="PROPERTY_VIEW">
      <PropertyRecord />
    </PageAccessGuard>
  )
}

function PropertyRecord() {
  const { propertyId } = useParams<{ propertyId: string }>()
  const navigate = useNavigate()
  const can = useAuthStore((state) => state.can)

  // The sub-resources each have their own capability, so they are read separately rather than being
  // bundled into one call that would fail whole for a caller who holds only part of the picture.
  const canBuildings = can('BUILDING_VIEW')
  const canUnits = can('UNIT_VIEW')
  const canOwnership = can('OWNER_VIEW')

  const [unitsPage, setUnitsPage] = useState(0)
  const [unitsSize, setUnitsSize] = useState(20)
  const [changingStatus, setChangingStatus] = useState(false)
  const [archiving, setArchiving] = useState(false)

  const detailQuery = useQuery({
    queryKey: ['property', propertyId],
    queryFn: () => propertyApi.detail(propertyId as string),
    enabled: Boolean(propertyId),
  })

  const metricsQuery = useQuery({
    queryKey: ['property', propertyId, 'metrics'],
    queryFn: () => propertyApi.metrics(propertyId as string),
    enabled: Boolean(propertyId),
  })

  const buildingsQuery = useQuery({
    queryKey: ['property', propertyId, 'buildings'],
    queryFn: () => propertyApi.buildings(propertyId as string),
    enabled: Boolean(propertyId) && canBuildings,
  })

  const unitsParams: PageParams = { page: unitsPage, size: unitsSize, sort: 'unitNumber,asc' }
  const unitsQuery = useQuery({
    queryKey: ['property', propertyId, 'units', unitsParams],
    queryFn: () => propertyApi.units(propertyId as string, unitsParams),
    enabled: Boolean(propertyId) && canUnits,
  })

  const ownershipQuery = useQuery({
    queryKey: ['property', propertyId, 'ownership'],
    queryFn: () => propertyApi.ownership(propertyId as string),
    enabled: Boolean(propertyId) && canOwnership,
  })

  if (!propertyId) {
    return (
      <>
        <PageHeader title="Property" />
        <EmptyState
          title="No property was named in the address"
          description="This screen reads one asset from the register. Open it from the property register instead."
          action={<Link to="/properties" className="focus-meridian btn btn-outline">Back to the register</Link>}
        />
      </>
    )
  }

  if (detailQuery.isLoading) {
    return (
      <>
        <PageHeader title="Property" description="Reading the asset record…" />
        <div className="space-y-4">
          <Skeleton rows={6} />
        </div>
      </>
    )
  }

  /*
   * A property that is not there.
   *
   * Archived properties are not addressable - `PropertyService.require` refuses them - so a 404 is a
   * real answer about a real possibility (a bookmarked link to an asset that has since left the
   * register), and it deserves a route back rather than a retry button that will fail again.
   */
  if (detailQuery.isError && detailQuery.error instanceof ApiError && detailQuery.error.status === 404) {
    return (
      <>
        <PageHeader title="Property not found" description="The register holds no live property at this address." />
        <EmptyState
          title="That property is not on the register"
          description="It may have been archived, which takes it out of the register without erasing it, or the address may be wrong. The property register lists everything currently under management."
          action={<Link to="/properties" className="focus-meridian btn btn-outline">Back to the register</Link>}
        />
      </>
    )
  }

  const property = detailQuery.data

  /*
   * A record that could not be read.
   *
   * A failure and an empty answer are the same thing to the reader - the asset could not be read - so
   * they share one answer, with the API's own sentence where there was one and the client's own
   * sentence when there was not. The 404 case above is handled separately, because "that property is
   * not on the register" is a fact rather than a failure and retrying it would only fail again.
   */
  if (!property) {
    return (
      <>
        <PageHeader title="Property" />
        <ErrorState message={messageOf(detailQuery.error)} onRetry={() => void detailQuery.refetch()} />
      </>
    )
  }

  const positioned = property.positioned

  const unitColumns: Column<UnitSummary>[] = [
    {
      key: 'unit', header: 'Unit', width: '18%',
      render: (row) => (
        <span className="block min-w-0">
          <span className="block truncate font-medium" style={{ color: 'var(--text-primary)' }}>{row.unitNumber}</span>
          <span className="block truncate text-[11px]" style={{ color: 'var(--text-secondary)' }}>{row.unitCode}</span>
        </span>
      ),
    },
    { key: 'building', header: 'Building', width: '18%', secondary: true, render: (row) => row.buildingName ?? '—' },
    { key: 'floor', header: 'Floor', width: '10%', align: 'right', render: (row) => fmtCount(row.floorNumber) },
    { key: 'type', header: 'Type', width: '16%', secondary: true, render: (row) => humanize(row.unitTypeName) },
    /*
     * This column was "Carpet area", read from `carpetAreaSqft`. That figure is not on the unit
     * register's projection - `UnitDtos.UnitSummary` carries no areas; only `UnitDetail` does - so the
     * column rendered a dash on every row. The bedrooms are sent on the register row, so the slot now
     * holds a figure the endpoint actually promises.
     */
    { key: 'bedrooms', header: 'Bedrooms', width: '14%', align: 'right', secondary: true, render: (row) => fmtCount(row.bedrooms) },
    { key: 'rent', header: 'Asking rent', width: '14%', align: 'right', render: (row) => money(row.askingRent) },
    { key: 'status', header: 'Status', width: '10%', render: (row) => <StatusBadge status={row.status} /> },
  ]

  const buildingColumns: Column<BuildingSummary>[] = [
    { key: 'name', header: 'Building', width: '50%', render: (row) => (
      <span className="truncate font-medium" style={{ color: 'var(--text-primary)' }}>{row.name}</span>
    ) },
    // `BuildingDtos.BuildingSummary` names the code `buildingCode`; a block code is unique within its
    // property rather than globally, which is why the property's name travels beside it on the row.
    { key: 'code', header: 'Code', width: '25%', secondary: true, render: (row) => row.buildingCode || '—' },
    { key: 'units', header: 'Units', width: '25%', align: 'right', render: (row) => fmtCount(row.unitCount) },
  ]

  const ownershipRows = ownershipQuery.data ?? []
  const ownershipColumns: Column<PropertyOwnershipShare>[] = [
    { key: 'owner', header: 'Owner', width: '34%', render: (row) => (
      <span className="block min-w-0">
        <span className="block truncate font-medium" style={{ color: 'var(--text-primary)' }}>{row.ownerName ?? '—'}</span>
        <span className="block truncate text-[11px]" style={{ color: 'var(--text-secondary)' }}>{row.ownerCode ?? '—'}</span>
      </span>
    ) },
    { key: 'share', header: 'Share', width: '16%', align: 'right', render: (row) => percent(row.percentage, 2) },
    { key: 'relationship', header: 'Relationship', width: '22%', secondary: true, render: (row) => humanize(row.relationship) },
    { key: 'from', header: 'From', width: '14%', secondary: true, render: (row) => date(row.effectiveFrom) },
    {
      key: 'to', header: 'To', width: '14%', align: 'right',
      render: (row) => (row.open ? 'In force' : date(row.effectiveTo)),
    },
  ]

  const metrics = metricsQuery.data
  const metricItems: { label: string; value: string; tone?: 'copper' }[] = []
  if (metrics) {
    metricItems.push({ label: 'Units', value: fmtCount(metrics.unitCount) })
    metricItems.push({ label: 'Leasable', value: fmtCount(metrics.leasableUnits) })
    metricItems.push({ label: 'Let', value: fmtCount(metrics.occupiedUnits) })
    metricItems.push({ label: 'Occupancy', value: percent(metrics.occupancyPercent, 0) })
    metricItems.push({ label: 'Monthly rent roll', value: money(metrics.monthlyRentRoll), tone: 'copper' })
    // The two areas and the two values are nullable on the projection: a figure the API did not answer
    // is left out of the list rather than drawn as a zero the screen invented.
    if (metrics.currentValuation !== null) {
      metricItems.push({ label: 'Current valuation', value: money(metrics.currentValuation), tone: 'copper' })
    }
    if (metrics.acquisitionValue !== null) {
      metricItems.push({ label: 'Acquisition cost', value: money(metrics.acquisitionValue) })
    }
    if (metrics.landAreaSqft !== null) {
      metricItems.push({ label: 'Land area', value: `${decimal(metrics.landAreaSqft, 0)} sq ft` })
    }
    if (metrics.builtAreaSqft !== null) {
      metricItems.push({ label: 'Built area', value: `${decimal(metrics.builtAreaSqft, 0)} sq ft` })
    }
  }

  return (
    <>
      <PageHeader
        eyebrow={property.propertyCode}
        title={property.name}
        description={[property.addressLine1, property.locality, property.city].filter(Boolean).join(', ')}
        actions={
          <>
            <StatusBadge status={property.status} />
            <Button onClick={() => setChangingStatus(true)}>Change status</Button>
            <Button variant="danger" onClick={() => setArchiving(true)}>Archive</Button>
          </>
        }
      />

      {/*
       * The four figures the desk leads with.
       *
       * Occupancy, the unit counts and the rent roll are read from the metrics projection, because the
       * property's own detail does not carry them - `PropertyDtos.PropertyDetail` promises counts of
       * buildings, units, documents and media, not occupancy. That projection is one request for the
       * property on screen, which is why this is the screen that reads it; a register could not.
       *
       * Until it answers, each tile reads a dash rather than a zero.
       */}
      <div className="mb-5 grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
        <KpiTile
          label="Occupancy"
          value={metrics ? percent(metrics.occupancyPercent, 1) : '—'}
          tone="emerald"
          caption={metrics ? `${fmtCount(metrics.occupiedUnits)} of ${fmtCount(metrics.leasableUnits)} leasable units let` : 'Reading the metrics'}
        />
        <KpiTile
          label="Units"
          value={metrics ? fmtCount(metrics.unitCount) : '—'}
          tone="teal"
          caption={metrics ? `${fmtCount(metrics.leasableUnits)} leasable` : 'Reading the metrics'}
        />
        <KpiTile
          label="Monthly rent"
          value={metrics ? money(metrics.monthlyRentRoll) : '—'}
          tone="copper"
          caption="Across the lettable stock"
        />
        <KpiTile
          label="Valuation"
          value={moneyCompact(property.currentValuation)}
          tone="forest"
          caption={property.acquisitionValue === null ? 'No acquisition cost recorded' : `Against ${moneyCompact(property.acquisitionValue)} of cost`}
        />
      </div>

      {/* ── The plot card: this asset's own record ────────────────────────── */}
      <Panel
        notch
        className="mb-5"
        title="Asset record"
        subtitle="How this property is classified and where it stands"
      >
        <dl className="grid gap-x-5 gap-y-3 sm:grid-cols-2 lg:grid-cols-4">
          <DetailItem label="Property code" value={property.propertyCode} />
          <DetailItem label="Type" value={humanize(property.propertyTypeName)} />
          <DetailItem label="City" value={property.city || '—'} />
          <DetailItem label="Locality" value={property.locality ?? '—'} />
          <DetailItem label="Address" value={property.addressLine1} />
          <DetailItem
            label="Position"
            value={positioned
              ? `${property.latitude}, ${property.longitude}`
              : 'Not surveyed'}
            tone={positioned ? 'emerald' : undefined}
          />
        </dl>
        {!positioned && (
          <p className="mt-3 text-xs" style={{ color: 'var(--text-secondary)' }}>
            This property carries no coordinate pair, so it is left off the portfolio map rather than
            plotted at a guessed position.
          </p>
        )}
      </Panel>

      <div className="mb-5 grid gap-4 xl:grid-cols-[1.3fr_1fr]">
        {/* ── The metrics projection ─────────────────────────────────────── */}
        <Panel title="Metrics" subtitle="Occupancy, rent roll and value as the register computes them" tint="forest">
          {metricsQuery.isLoading ? (
            <Skeleton rows={6} />
          ) : metricsQuery.isError ? (
            <ErrorState message={messageOf(metricsQuery.error)} onRetry={() => void metricsQuery.refetch()} />
          ) : metricItems.length === 0 ? (
            <EmptyState
              title="This property reports no metrics yet"
              description="Occupancy, the rent roll and the valuation are computed from the units and the value recorded against the asset. Add its stock and its valuation and this projection fills in."
            />
          ) : (
            <dl className="grid gap-x-5 gap-y-3 sm:grid-cols-2">
              {metricItems.map((item) => (
                <DetailItem key={item.label} label={item.label} value={item.value} tone={item.tone} />
              ))}
            </dl>
          )}
        </Panel>

        {/* ── The blocks within the property ─────────────────────────────── */}
        {canBuildings && (
          <Panel title="Buildings" subtitle="The blocks this property is made of" tint="sandstone">
            {buildingsQuery.isLoading ? (
              <Skeleton rows={6} />
            ) : buildingsQuery.isError ? (
              <ErrorState message={messageOf(buildingsQuery.error)} onRetry={() => void buildingsQuery.refetch()} />
            ) : (
              <DataTable
                columns={buildingColumns}
                rows={buildingsQuery.data ?? []}
                keyOf={(row) => row.id}
                caption="Buildings within this property"
                empty={
                  <EmptyState
                    title="No buildings are recorded"
                    description="A property given no blocks holds its units directly. Record a block against it and its units can be grouped floor by floor."
                  />
                }
              />
            )}
          </Panel>
        )}
      </div>

      {/* ── Ownership ─────────────────────────────────────────────────────── */}
      {canOwnership && (
        <Panel
          className="mb-5"
          title="Ownership"
          subtitle="Who holds this property, and for how long"
          tint="copper"
        >
          {ownershipQuery.isLoading ? (
            <div className="p-4"><Skeleton rows={6} /></div>
          ) : ownershipQuery.isError ? (
            <div className="p-4">
              <ErrorState message={messageOf(ownershipQuery.error)} onRetry={() => void ownershipQuery.refetch()} />
            </div>
          ) : (
            <DataTable
              columns={ownershipColumns}
              rows={ownershipRows}
              keyOf={(row) => row.ownershipId}
              caption="Ownership shares in this property"
              empty={
                <EmptyState
                  title="No ownership shares are recorded"
                  description="A share states who owns what, from when and for how long. None are recorded against this property, so no one is attributed to it yet."
                />
              }
            />
          )}
        </Panel>
      )}

      {/* ── The stock ─────────────────────────────────────────────────────── */}
      {canUnits ? (
        <Panel title="Units" subtitle="Every unit in this property, by unit number" bodyClassName="!p-0">
          {unitsQuery.isLoading ? (
            <div className="p-4"><Skeleton rows={6} /></div>
          ) : unitsQuery.isError ? (
            <div className="p-4">
              <ErrorState message={messageOf(unitsQuery.error)} onRetry={() => void unitsQuery.refetch()} />
            </div>
          ) : (
            <>
              <DataTable
                columns={unitColumns}
                rows={unitsQuery.data?.items ?? []}
                keyOf={(row) => row.id}
                caption="Units in this property"
                empty={
                  <EmptyState
                    title="This property holds no units yet"
                    description="Units are the stock a property is let and sold by, and occupancy is measured over them. Add its units and the rent roll and occupancy above will follow."
                  />
                }
              />
              {(unitsQuery.data?.items.length ?? 0) > 0 && (
                <div className="border-t" style={{ borderColor: 'var(--border)' }}>
                  <Pagination
                    page={unitsPage}
                    size={unitsSize}
                    totalElements={unitsQuery.data?.totalElements ?? 0}
                    totalPages={unitsQuery.data?.totalPages ?? 0}
                    hasNext={unitsQuery.data?.hasNext ?? false}
                    onPageChange={setUnitsPage}
                    onSizeChange={(next) => { setUnitsSize(next); setUnitsPage(0) }}
                  />
                </div>
              )}
            </>
          )}
        </Panel>
      ) : (
        <Panel title="Units" subtitle="Not available to your role">
          <EmptyState
            title="Your account cannot read the unit register"
            description="The unit register needs the UNIT_VIEW permission. Ask an owner or administrator to add it to your role to see this property's stock."
          />
        </Panel>
      )}

      <ChangeStatusDialog
        open={changingStatus}
        propertyId={propertyId}
        current={property.status}
        onClose={() => setChangingStatus(false)}
      />

      <ArchiveDialog
        open={archiving}
        propertyId={propertyId}
        propertyName={property.name}
        onClose={() => setArchiving(false)}
        onArchived={() => navigate('/properties')}
      />
    </>
  )
}

/* ============================================================
 * Writes
 * ============================================================ */

/** Move a property between lifecycle states, with the reason the history keeps. */
function ChangeStatusDialog({
  open, propertyId, current, onClose,
}: {
  open: boolean
  propertyId: string
  current: string
  onClose: () => void
}) {
  const queryClient = useQueryClient()
  const toast = useToast()
  const [status, setStatus] = useState(current)
  const [reason, setReason] = useState('')

  // The dialog stays mounted between openings, so the form is re-seeded from the property's stored
  // status each time it is opened. Without this it would offer the state the asset was in when the
  // screen first loaded - which, after a move, is a status the property no longer holds.
  useEffect(() => {
    if (!open) return
    setStatus(current)
    setReason('')
  }, [open, current])

  const change = useMutation({
    mutationFn: (body: Record<string, unknown>) => propertyApi.changeStatus(propertyId, body),
    onSuccess: (updated) => {
      void queryClient.invalidateQueries({ queryKey: ['property', propertyId] })
      // The register shows each row's status, so it is refetched too rather than only the record.
      void queryClient.invalidateQueries({ queryKey: ['properties'] })
      toast.push(`${updated.propertyCode} is now ${humanize(updated.status)}`, 'amber')
      change.reset()
      onClose()
    },
  })

  function close() {
    change.reset()
    onClose()
  }

  function onSubmit(event: FormEvent) {
    event.preventDefault()
    change.mutate({ status, reason: reason.trim() === '' ? undefined : reason.trim() })
  }

  return (
    <Dialog
      open={open}
      title="Change property status"
      description="A status move is written to the property's own history with the reason you give."
      onClose={close}
      footer={
        <>
          <Button variant="ghost" onClick={close} disabled={change.isPending}>Cancel</Button>
          <Button type="submit" form="property-status-form" variant="primary" loading={change.isPending}>
            {change.isPending ? 'Moving…' : 'Move the property'}
          </Button>
        </>
      }
    >
      <form id="property-status-form" className="space-y-4" onSubmit={onSubmit} noValidate>
        {change.isError && <ErrorState message={messageOf(change.error)} details={detailsOf(change.error)} />}

        <Field
          label="New status"
          htmlFor="property-status"
          required
          hint="A property that has been sold has reached the end of its lifecycle and cannot move again."
        >
          <Select id="property-status" value={status} onChange={(event) => setStatus(event.target.value)}>
            {PROPERTY_STATUSES.map((option) => (
              <option key={option} value={option}>{humanize(option)}</option>
            ))}
          </Select>
        </Field>

        <Field
          label="Reason"
          htmlFor="property-status-reason"
          hint="Optional, and kept with the transition — “listed on the owner's instruction” outlives the status itself."
        >
          <Textarea
            id="property-status-reason"
            value={reason}
            onChange={(event) => setReason(event.target.value)}
          />
        </Field>
      </form>
    </Dialog>
  )
}

/**
 * Archive the property.
 *
 * Archiving is a soft delete, and the API refuses it while a running lease remains (409
 * `PROPERTY_HAS_ACTIVE_LEASES`) - so the refusal is reported as the sentence the backend wrote rather
 * than being pre-empted here by a guess about the leases this screen cannot see.
 */
function ArchiveDialog({
  open, propertyId, propertyName, onClose, onArchived,
}: {
  open: boolean
  propertyId: string
  propertyName: string
  onClose: () => void
  onArchived: () => void
}) {
  const queryClient = useQueryClient()
  const toast = useToast()

  const archive = useMutation({
    mutationFn: () => propertyApi.archive(propertyId),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['properties'] })
      toast.push(`${propertyName} has been archived`, 'slate')
      onArchived()
    },
    onError: (error) => {
      toast.push(messageOf(error), 'terracotta')
      onClose()
    },
  })

  return (
    <ConfirmDialog
      open={open}
      title={`Archive ${propertyName}?`}
      description="An archived property is withdrawn from the register without erasing it — its leases, invoices and audit trail stay coherent. A property with a running lease cannot be archived until that tenancy ends."
      confirmLabel="Archive property"
      dangerous
      busy={archive.isPending}
      onConfirm={() => archive.mutate()}
      onCancel={onClose}
    />
  )
}

/* ============================================================
 * The closed set the status control offers
 * ============================================================ */

/** The statuses the schema allows for a property (`ck_properties_status`). */
const PROPERTY_STATUSES = [
  'DRAFT', 'LISTED', 'AVAILABLE_ACTIVE', 'UNDER_CONTRACT', 'LEASED', 'SOLD', 'INACTIVE',
] as const
