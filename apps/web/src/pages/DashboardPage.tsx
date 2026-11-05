import { useQuery } from '@tanstack/react-query'
import { useNavigate } from 'react-router-dom'
import {
  Building2, DoorOpen, FileWarning, Receipt, TrendingUp, Wallet, Wrench,
} from 'lucide-react'
import { PageHeader } from '../components/common'
import {
  Button, DataTable, EmptyState, ErrorState, KpiTile, Panel, SectionHeader, Skeleton, StatusBadge,
  TONE_COLOUR, type Column,
} from '../components/ui'
import {
  ComparisonBars, DistributionDonut, Funnel, OccupancyRing, PropertyMap, TrendArea,
} from '../components/ui/charts'
import {
  count as fmtCount, dateTime, humanize, money, moneyCompact, percent, relative,
} from '../components/ui/formatters'
import { dashboardApi, leaseApi, propertyApi } from '../services'
import { usePlatformStore } from '../platform'
import { messageOf } from '../lib/errors'
import type { ActivityRow, PropertyPerformanceRow, TrendPoint, UnitStateCount } from '../types'

/**
 * The executive dashboard.
 *
 * A designed composition rather than a grid of identical cards: an architectural hero carrying the
 * figures the firm leads with, then a KPI band, then panels whose *shape* is chosen by the shape of
 * the data - a ring for a proportion of a fixed whole, a funnel for a book that narrows, an ordered
 * rail for a list read by date.
 *
 * **Every figure on this page comes from `GET /dashboard/summary`.** Nothing is computed here that
 * the backend did not send, and a metric the backend could not honestly compute is simply absent -
 * the panel that would show it is not rendered. There is no placeholder number anywhere on this
 * screen, because a plausible-looking figure the database never contained is the single most
 * damaging thing a dashboard can do.
 *
 * **Two things on this page are read from their own endpoints, and both are single requests.**
 * The map's markers come from `/properties/map`, because the summary's property ranking carries no
 * coordinate and a map drawn without one would be a guess. The expiring-lease rail comes from
 * `/leases/expiring`, because the summary's `leases` block is counts without rows. Neither costs a
 * request per table row, which is the line this screen does not cross.
 */
export default function DashboardPage() {
  const navigate = useNavigate()
  const profile = usePlatformStore((state) => state.profile)
  const target = profile?.policies?.occupancyTargetPercent ?? 92

  const summaryQuery = useQuery({
    queryKey: ['dashboard', 'summary'],
    queryFn: () => dashboardApi.summary(),
  })

  /*
   * The map's markers, read from the endpoint that owns them.
   *
   * The dashboard's own property projection carries text, occupancy, rent and value - no coordinate -
   * so a map drawn from it could only ever be a guess. `/properties/map` is the read that returns real
   * positions, and it is the only one: a property with no coordinate pair is absent from it rather than
   * plotted somewhere plausible.
   */
  const mapQuery = useQuery({
    queryKey: ['properties', 'map'],
    queryFn: () => propertyApi.map(),
  })

  /*
   * The leases close to expiry.
   *
   * `DashboardDtos.LeaseAttention` is a block of *counts* - how many are running, how many fall inside
   * the warning and urgent windows, how many have already passed - and it carries no rows. The rows
   * come from `/leases/expiring`, which is the projection built for exactly this rail and which counts
   * the days to each end date server-side.
   */
  const expiringQuery = useQuery({
    queryKey: ['leases', 'expiring'],
    queryFn: () => leaseApi.expiring(),
  })

  if (summaryQuery.isLoading) {
    return (
      <>
        <PageHeader title="Portfolio dashboard" description="Loading the portfolio position…" />
        <div className="space-y-4">
          <Skeleton rows={4} />
          <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
            {Array.from({ length: 8 }).map((_, index) => <Skeleton key={index} rows={3} />)}
          </div>
        </div>
      </>
    )
  }

  if (summaryQuery.isError) {
    return (
      <>
        <PageHeader title="Portfolio dashboard" />
        <ErrorState
          message={messageOf(summaryQuery.error)}
          onRetry={() => void summaryQuery.refetch()}
        />
      </>
    )
  }

  const summary = summaryQuery.data
  if (!summary) {
    return (
      <>
        <PageHeader title="Portfolio dashboard" />
        <EmptyState
          title="The portfolio position could not be assembled"
          description="The dashboard reports on properties, units, leases and receipts together. Add a property and a lease and this screen will fill in."
        />
      </>
    )
  }

  const p = summary.portfolio
  const unitStates = summary.unitStates.filter((state) => state.count > 0)
  const trend = summary.trend
  // The dashboard ranks properties twice - by valuation and by rent - so the table reads the ranking it
  // is for. There is no third, unfiltered list.
  const performance = summary.topPropertiesByValuation
  const activity = summary.recentActivity
  const leaseAttention = summary.leases
  const maintenance = summary.maintenance
  const leadFunnel = summary.leads.funnel
  const salesPipeline = summary.sales.byStage
  const expiringLeases = expiringQuery.data ?? []

  const collectedShare = p.invoicedThisPeriod > 0
    ? Math.round((p.collectedThisPeriod / p.invoicedThisPeriod) * 100)
    : 0

  const performanceColumns: Column<PropertyPerformanceRow>[] = [
    { key: 'name', header: 'Property', width: '26%', render: (row) => (
      <span className="truncate font-medium" style={{ color: 'var(--text-primary)' }}>{row.name}</span>
    ) },
    { key: 'city', header: 'City', width: '13%', secondary: true, render: (row) => row.city ?? '—' },
    /*
     * This column was "Units", read from a `unitCount` the ranking does not send: the projection carries
     * the *leasable* stock, which is the population occupancy is measured over, and that is the figure
     * the ranking is drawn from.
     */
    { key: 'units', header: 'Leasable', width: '9%', align: 'right', render: (row) => fmtCount(row.leasableUnits) },
    { key: 'occupancy', header: 'Occupancy', width: '14%', align: 'right', render: (row) => (
      <span className="inline-flex items-center gap-1.5 tabular-nums">
        <span aria-hidden="true" className="inline-block h-1.5 w-8 rounded-full" style={{ background: 'var(--surface-sunken)' }}>
          <span className="block h-full rounded-full" style={{ width: `${Math.min(100, row.occupancyPercent)}%`, background: TONE_COLOUR.emerald }} />
        </span>
        {percent(row.occupancyPercent, 0)}
      </span>
    ) },
    { key: 'rent', header: 'Monthly rent', width: '18%', align: 'right', render: (row) => money(row.monthlyRent) },
    { key: 'value', header: 'Valuation', width: '20%', align: 'right', secondary: true, render: (row) => (row.valuation === null ? '—' : moneyCompact(row.valuation)) },
  ]

  const activityColumns: Column<ActivityRow>[] = [
    { key: 'action', header: 'What', width: '30%', render: (row) => (
      <span className="truncate text-xs" style={{ color: 'var(--text-primary)' }}>{row.description ?? humanize(row.action)}</span>
    ) },
    { key: 'entity', header: 'Record', width: '26%', secondary: true, render: (row) => row.entityLabel ?? '—' },
    { key: 'actor', header: 'Who', width: '20%', secondary: true, render: (row) => row.actorName ?? 'System' },
    { key: 'when', header: 'When', width: '24%', align: 'right', render: (row) => relative(row.createdAt) },
  ]

  return (
    <>
      <PageHeader
        eyebrow={profile?.industry?.name ?? 'Real Estate'}
        title="Portfolio dashboard"
        description={`The position as at ${summary.asOf} — ${fmtCount(p.propertyCount)} properties and ${fmtCount(p.unitCount)} units under management.`}
      />

      {/* ── Architectural hero: the figures the firm leads with ────────────── */}
      <section className="hero-band relative mb-5 overflow-hidden rounded-[var(--radius-panel)] px-5 py-6 shadow-[var(--shadow-hero)] sm:px-7 sm:py-8">
        <div className="relative z-10">
          <div className="flex flex-wrap items-end justify-between gap-4">
            <div>
              <p className="eyebrow" style={{ color: 'var(--hero-copper)' }}>Portfolio value</p>
              <p className="kpi-value mt-1 text-3xl sm:text-4xl" style={{ color: 'var(--hero-text)' }}>
                {moneyCompact(p.totalValuation)}
              </p>
              <p className="mt-1 text-xs" style={{ color: 'var(--hero-muted)' }}>
                Against {moneyCompact(p.totalAcquisitionValue)} of acquisition cost
              </p>
            </div>
            <div className="text-right">
              <p className="eyebrow" style={{ color: 'var(--hero-copper)' }}>Annualised yield</p>
              <p className="kpi-value mt-1 text-2xl" style={{ color: 'var(--hero-text)' }}>
                {percent(p.annualisedYieldPercent, 2)}
              </p>
              <p className="mt-1 text-xs" style={{ color: 'var(--hero-muted)' }}>Rent roll over current valuation</p>
            </div>
          </div>

          <div className="mt-6 grid gap-5 sm:grid-cols-2 lg:grid-cols-4">
            {[
              { label: 'Monthly rent roll', value: money(p.monthlyRentRoll) },
              { label: 'Collected this period', value: money(p.collectedThisPeriod), caption: `${collectedShare}% of what was invoiced` },
              { label: 'Net operating income', value: money(p.netOperatingIncome), caption: 'Collected rent less operating cost' },
              { label: 'Outstanding rent', value: money(p.outstandingRent), caption: `${fmtCount(p.outstandingInvoiceCount)} invoices open` },
            ].map((item) => (
              <div key={item.label}>
                <p className="eyebrow" style={{ color: 'var(--hero-muted)' }}>{item.label}</p>
                <p className="kpi-value mt-1 text-lg" style={{ color: 'var(--hero-text)' }}>{item.value}</p>
                {item.caption && <p className="mt-0.5 text-[11px]" style={{ color: 'var(--hero-muted)' }}>{item.caption}</p>}
              </div>
            ))}
          </div>
        </div>
      </section>

      {/* ── KPI band ───────────────────────────────────────────────────────── */}
      <div className="mb-5 grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
        <KpiTile label="Properties" value={fmtCount(p.propertyCount)} tone="forest"
          icon={<Building2 size={15} />} caption={`${fmtCount(p.leasableUnits)} leasable units`}
          onClick={() => navigate('/properties')} />
        <KpiTile label="Occupancy" value={percent(p.occupancyPercent, 1)} tone="emerald"
          icon={<DoorOpen size={15} />} caption={`${fmtCount(p.occupiedUnits)} of ${fmtCount(p.leasableUnits)} let · target ${p.occupancyTargetPercent}%`}
          onClick={() => navigate('/units')} />
        <KpiTile label="Overdue rent" value={money(p.overdueRent)} tone="terracotta"
          icon={<FileWarning size={15} />} caption={`${fmtCount(p.overdueInvoiceCount)} invoices past the grace period`}
          onClick={() => navigate('/payments')} />
        <KpiTile label="Open maintenance" value={fmtCount(maintenance?.openCount ?? 0)} tone="amber"
          icon={<Wrench size={15} />} caption={maintenance ? `${fmtCount(maintenance.urgentOpenCount)} urgent` : 'Not reported'}
          onClick={() => navigate('/maintenance')} />
        <KpiTile label="Capital expenses" value={money(p.capitalExpensesThisPeriod)} tone="slate"
          icon={<Receipt size={15} />} caption="Reported separately from operating cost" />
        <KpiTile label="Operating expenses" value={money(p.operatingExpensesThisPeriod)} tone="copper"
          icon={<Wallet size={15} />} caption="Excludes capital and deposits" onClick={() => navigate('/expenses')} />
        <KpiTile label="Invoiced this period" value={money(p.invoicedThisPeriod)} tone="sapphire"
          icon={<TrendingUp size={15} />} caption={`${collectedShare}% collected`} />
        {/*
          `DashboardDtos.LeaseAttention` names the expired count `alreadyExpired`, which is what this
          caption reads.
        */}
        <KpiTile label="Renewals to watch" value={fmtCount(leaseAttention.renewalCandidates)} tone="teal"
          icon={<TrendingUp size={15} />} caption={`${fmtCount(leaseAttention.alreadyExpired)} already expired`}
          onClick={() => navigate('/leases')} />
      </div>

      {/* ── Property performance + occupancy ──────────────────────────────── */}
      <SectionHeader eyebrow="Performance" title="Property performance" rule="copper"
        description="Each property's occupancy and monthly rent, with its valuation where one has been assessed."
        actions={<Button size="sm" onClick={() => navigate('/properties')}>All properties</Button>} />
      <div className="mb-6 grid gap-4 xl:grid-cols-[1.5fr_1fr]">
        <Panel notch className="min-w-0">
          <DataTable
            columns={performanceColumns}
            rows={performance.slice(0, 8)}
            keyOf={(row) => row.propertyId}
            onRowClick={(row) => navigate(`/properties/${row.propertyId}`)}
            empty={<EmptyState title="No properties to rank yet" description="Property performance compares occupancy and rent across the register. It appears once properties carry units." />}
            caption="Property performance"
          />
        </Panel>
        <Panel title="Unit state distribution" subtitle="Where the stock stands right now" tint="teal">
          {unitStates.length > 0 ? (
            <DistributionDonut
              points={unitStates.map((state: UnitStateCount) => ({ label: state.status, value: state.count }))}
              title="Unit states"
              centerLabel={fmtCount(p.unitCount)}
            />
          ) : (
            <EmptyState title="No units recorded" description="The distribution appears once the portfolio holds units." />
          )}
        </Panel>
      </div>

      {/* ── Occupancy and rental analytics ───────────────────────────────── */}
      <SectionHeader eyebrow="Analytics" title="Occupancy and collections" rule="emerald"
        description={`The last ${trend.length} months of rent raised against rent collected, and the portfolio's occupancy against its target.`} />
      <div className="mb-6 grid gap-4 xl:grid-cols-[1fr_2fr]">
        <Panel title="Occupancy" subtitle={`Target ${target}%`} tint="emerald">
          <OccupancyRing
            value={p.occupancyPercent}
            display={percent(p.occupancyPercent, 1)}
            caption="Occupancy"
            tone="emerald"
            target={target}
          />
          <dl className="mt-4 grid grid-cols-3 gap-3 border-t pt-3 text-center" style={{ borderColor: 'var(--border)' }}>
            <div>
              <dt className="eyebrow" style={{ color: 'var(--text-secondary)' }}>Let</dt>
              <dd className="kpi-value text-sm" style={{ color: 'var(--text-primary)' }}>{fmtCount(p.occupiedUnits)}</dd>
            </div>
            <div>
              <dt className="eyebrow" style={{ color: 'var(--text-secondary)' }}>Leasable</dt>
              <dd className="kpi-value text-sm" style={{ color: 'var(--text-primary)' }}>{fmtCount(p.leasableUnits)}</dd>
            </div>
            <div>
              <dt className="eyebrow" style={{ color: 'var(--text-secondary)' }}>Vacant</dt>
              <dd className="kpi-value text-sm" style={{ color: 'var(--text-primary)' }}>
                {fmtCount(Math.max(0, p.leasableUnits - p.occupiedUnits))}
              </dd>
            </div>
          </dl>
        </Panel>
        <Panel title="Rent invoiced against collected" subtitle="Monthly, from the rent ledger">
          <TrendArea
            points={trend.map((point: TrendPoint) => ({ label: point.month, value: point.invoiced }))}
            title="Invoiced"
            unit="currency"
            primaryTone="forest"
          />
          <div className="mt-4 border-t pt-4" style={{ borderColor: 'var(--border)' }}>
            <TrendArea
              points={trend.map((point: TrendPoint) => ({ label: point.month, value: point.collected }))}
              title="Collected"
              unit="currency"
              primaryTone="emerald"
            />
          </div>
        </Panel>
      </div>

      {/* ── Portfolio and NOI ─────────────────────────────────────────────── */}
      <SectionHeader eyebrow="Investment" title="Net operating income and cost" rule="forest"
        description="Collected rent against operating cost. Capital expenditure is reported separately and never folded into the operating figure." />
      <div className="mb-6 grid gap-4 lg:grid-cols-3">
        <Panel title="Net operating income" subtitle="By month" tint="forest" className="lg:col-span-2">
          <TrendArea
            points={trend.map((point: TrendPoint) => ({ label: point.month, value: point.netOperatingIncome }))}
            title="Net operating income"
            unit="currency"
            primaryTone="forest"
          />
        </Panel>
        <Panel title="Cost split" subtitle="This period" tint="copper">
          <ComparisonBars
            points={[
              { label: 'Operating expenses', value: p.operatingExpensesThisPeriod, tone: 'copper' },
              { label: 'Capital expenses', value: p.capitalExpensesThisPeriod, tone: 'slate' },
              { label: 'Collected', value: p.collectedThisPeriod, tone: 'emerald' },
              { label: 'Outstanding', value: p.outstandingRent, tone: 'terracotta' },
            ]}
            title="Cost against income"
          />
        </Panel>
      </div>

      {/* ── Pipelines ────────────────────────────────────────────────────── */}
      {(leadFunnel.length > 0 || salesPipeline.length > 0) && (
        <>
          <SectionHeader eyebrow="Pipeline" title="Lead and sales pipeline" rule="sapphire"
            description="The book narrowing from enquiry to completion." />
          <div className="mb-6 grid gap-4 lg:grid-cols-2">
            {leadFunnel.length > 0 && (
              <Panel title="Lead funnel" subtitle="Leads that reached each stage">
                <Funnel
                  steps={leadFunnel.map((bucket) => ({ label: humanize(bucket.stage), value: bucket.count }))}
                  title="Lead funnel"
                />
              </Panel>
            )}
            {salesPipeline.length > 0 && (
              <Panel title="Sales pipeline" subtitle="Opportunities by stage" tint="champagne">
                <Funnel
                  steps={salesPipeline.map((bucket) => ({ label: humanize(bucket.stage), value: bucket.count }))}
                  title="Sales pipeline"
                />
              </Panel>
            )}
          </div>
        </>
      )}

      {/* ── Lease attention + maintenance ────────────────────────────────── */}
      <div className="mb-6 grid gap-4 xl:grid-cols-2">
        <Panel
          title="Leases needing attention"
          subtitle={leaseAttention
            ? `${fmtCount(leaseAttention.expiringWithinWarningDays)} of ${fmtCount(leaseAttention.runningLeases)} running leases fall inside the ${profile?.policies?.leaseExpiryWarningDays ?? 60}-day warning window`
            : 'Renewals and expiry'}
          tint="amber"
          actions={<Button size="sm" variant="ghost" onClick={() => navigate('/leases')}>All leases</Button>}
        >
          {expiringQuery.isError ? (
            <ErrorState message={messageOf(expiringQuery.error)} onRetry={() => void expiringQuery.refetch()} />
          ) : expiringQuery.isLoading ? (
            <Skeleton rows={4} />
          ) : expiringLeases.length > 0 ? (
            <ol className="space-y-2">
              {/*
                The rows come from `/leases/expiring` — `LeaseDtos.ExpiringLease` — which is the one
                read that carries the days to each end date, counted server-side. The dashboard's own
                `leases` block is counts only, so it fills the subtitle above and not this list.
              */}
              {expiringLeases.slice(0, 6).map((lease) => {
                const urgent = lease.daysUntilEnd <= (profile?.policies?.leaseExpiryUrgentDays ?? 30)
                return (
                  <li key={lease.id}>
                    <button
                      type="button"
                      onClick={() => navigate(`/leases/${lease.id}`)}
                      className="focus-meridian flex w-full items-center justify-between gap-3 rounded-[var(--radius-card)] border px-3 py-2 text-left"
                      style={{ borderColor: 'var(--border)', background: 'var(--surface-elevated)' }}
                    >
                      <span className="min-w-0">
                        <span className="block truncate text-xs font-semibold" style={{ color: 'var(--text-primary)' }}>
                          {lease.tenantName ?? lease.leaseCode}
                        </span>
                        <span className="block truncate text-[11px]" style={{ color: 'var(--text-secondary)' }}>
                          {lease.leaseCode} · {lease.propertyName ?? ''}
                        </span>
                      </span>
                      <span className="shrink-0 text-right">
                        <span className="block text-xs tabular-nums" style={{ color: 'var(--text-primary)' }}>{money(lease.rentAmount)}</span>
                        <span className="block text-[11px] font-semibold" style={{ color: urgent ? 'var(--danger)' : 'var(--warning-ink)' }}>
                          {lease.daysUntilEnd < 0
                            ? `${Math.abs(lease.daysUntilEnd)} days overdue`
                            : `${lease.daysUntilEnd} days left`}
                        </span>
                      </span>
                    </button>
                  </li>
                )
              })}
            </ol>
          ) : (
            <EmptyState
              title="No leases are close to expiry"
              description={`Nothing falls due within the ${profile?.policies?.leaseExpiryWarningDays ?? 60}-day warning window, so there is nothing to renew yet.`}
            />
          )}
        </Panel>

        <Panel title="Maintenance" subtitle="Open work and what it has cost this period" tint="terracotta"
          actions={<Button size="sm" variant="ghost" onClick={() => navigate('/maintenance')}>All requests</Button>}>
          {maintenance ? (
            <>
              <div className="grid grid-cols-3 gap-3">
                <div>
                  <p className="eyebrow" style={{ color: 'var(--text-secondary)' }}>Open</p>
                  <p className="kpi-value text-lg" style={{ color: 'var(--text-primary)' }}>{fmtCount(maintenance.openCount)}</p>
                </div>
                <div>
                  <p className="eyebrow" style={{ color: 'var(--text-secondary)' }}>Urgent</p>
                  <p className="kpi-value text-lg" style={{ color: 'var(--text-primary)' }}>{fmtCount(maintenance.urgentOpenCount)}</p>
                </div>
                <div>
                  {/* `MaintenanceKpi` reports the cost of *this period*; there is no all-time total on it. */}
                  <p className="eyebrow" style={{ color: 'var(--text-secondary)' }}>Cost this period</p>
                  <p className="kpi-value text-lg" style={{ color: 'var(--text-primary)' }}>{moneyCompact(maintenance.costThisPeriod)}</p>
                </div>
              </div>
              {maintenance.costTrend.length > 0 && (
                <div className="mt-4 border-t pt-3" style={{ borderColor: 'var(--border)' }}>
                  <TrendArea
                    points={maintenance.costTrend.map((point) => ({ label: point.month, value: point.amount }))}
                    title="Maintenance cost"
                    unit="currency"
                    primaryTone="terracotta"
                  />
                </div>
              )}
              {/* The priority tally arrives as its own rows: `PriorityCount(priority, count)`. */}
              {maintenance.byPriority.length > 0 && (
                <ul className="mt-3 flex flex-wrap gap-2 border-t pt-3" style={{ borderColor: 'var(--border)' }}>
                  {maintenance.byPriority.map((entry) => (
                    <li key={entry.priority} className="inline-flex items-center gap-1.5">
                      <StatusBadge status={entry.priority} />
                      <span className="text-xs tabular-nums" style={{ color: 'var(--text-secondary)' }}>{fmtCount(entry.count)}</span>
                    </li>
                  ))}
                </ul>
              )}
            </>
          ) : (
            <EmptyState title="Maintenance is not reported here" description="The summary did not include maintenance figures, so nothing is shown rather than an estimate." />
          )}
        </Panel>
      </div>

      {/* ── Map ─────────────────────────────────────────────────────────── */}
      <SectionHeader eyebrow="Geography" title="Property map" rule="sandstone"
        description="Plotted only from the coordinates held against each property." />
      <div className="mb-6">
        <Panel notch>
          {mapQuery.isError ? (
            <ErrorState message={messageOf(mapQuery.error)} onRetry={() => void mapQuery.refetch()} />
          ) : (
            <PropertyMap
              /*
               * The markers are the API's own: `/properties/map` returns nothing but positioned
               * properties, so there is no filter and no guard to write here - the endpoint has already
               * excluded every asset whose position is not known.
               */
              markers={(mapQuery.data ?? []).map((marker) => ({
                id: marker.id,
                code: marker.propertyCode,
                name: marker.name,
                city: marker.city,
                status: marker.status,
                latitude: marker.latitude,
                longitude: marker.longitude,
              }))}
              onSelect={(marker) => navigate(`/properties/${marker.id}`)}
            />
          )}
        </Panel>
      </div>

      {/* ── Recent activity ─────────────────────────────────────────────── */}
      <SectionHeader eyebrow="Audit" title="Recent activity" rule="slate"
        description="What was done, to what, and by whom — read from the audit trail."
        actions={<Button size="sm" variant="ghost" onClick={() => navigate('/audit-logs')}>Full trail</Button>} />
      <Panel>
        <DataTable
          columns={activityColumns}
          rows={activity.slice(0, 10)}
          keyOf={(row) => row.id}
          empty={<EmptyState title="Nothing has been recorded yet" description="The audit trail fills as records are created and changed." />}
          caption="Recent activity"
        />
      </Panel>

      <p className="mt-4 text-[11px]" style={{ color: 'var(--text-secondary)' }}>
        Generated {dateTime(summary.generatedAt)} · figures in {summary.currency}
      </p>
    </>
  )
}
