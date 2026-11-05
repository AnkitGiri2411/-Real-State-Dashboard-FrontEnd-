import { useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { AlertTriangle, FileWarning, Receipt, RefreshCw, Search, TrendingUp, Wallet } from 'lucide-react'
import { PageAccessGuard, PageHeader } from '../components/common'
import {
  Badge, Button, DataTable, Dialog, EmptyState, ErrorState, Field, Input, KpiTile, Panel,
  Pagination, SectionHeader, Select, Skeleton, StatusBadge, Textarea, Toolbar, useToast,
  type Column, type Tone,
} from '../components/ui'
import { TrendArea, ComparisonBars } from '../components/ui/charts'
import { count as fmtCount, date, humanize, money } from '../components/ui/formatters'
import { dashboardApi, invoiceApi, paymentApi } from '../services'
import { messageOf } from '../lib/errors'
import type { CollectionsPosition, InvoiceSummary, PaymentSummary } from '../types'

/**
 * The money desk: the rent invoices raised and the receipts banked against them.
 *
 * Two registers share one screen because they are two views of one ledger, and the switch between
 * them is kept in the address bar so a bookmark or a shared link opens the same view.
 *
 * **Nothing on this screen computes a settlement figure.** The KPI band is
 * `GET /rent-invoices/collections`, whose invoiced/collected/outstanding/overdue are derived in the
 * service over voided-invoice-excluded rows with the grace period applied. That endpoint answers with
 * `RentInvoiceDtos.CollectionsPosition`, and every figure on this page is read straight off its own
 * record - nothing here adds up allocations or subtracts receipts, because a second ledger is exactly
 * how a UI comes to disagree with the database.
 *
 * **Per-invoice settlement is not on this screen, and cannot be.** `RentInvoiceDtos.RentInvoiceSummary`
 * stores no settlement: whether an invoice has been paid is derived from the allocations posted against
 * it, and that block travels on the invoice *detail*. So the register's columns are the amounts the
 * invoice stores; the settlement of one invoice is read from its own record.
 *
 * The monthly invoiced-versus-collected series is read from the dashboard summary, which is the one
 * endpoint that publishes it; recomputing it by bucketing this page's invoices would be precisely the
 * client-side derivation the ledger rules forbid.
 *
 * Recorded receipts are *not* created from this screen: `POST /payments` requires a payment-method
 * identifier, and this build's shared API map exposes no payment-method directory to choose one from,
 * nor an allocation endpoint. Rather than render a form that cannot be submitted, the action is
 * omitted; invoices and receipts can still be voided, which is the reversible act this screen owns.
 */

/** Invoice statuses, mirrored from `InvoiceStatus`. There is deliberately no `PAID` — settlement is derived. */
const INVOICE_STATUSES = ['DRAFT', 'ISSUED', 'VOID', 'WRITTEN_OFF']

/** Receipt statuses, mirrored from `PaymentStatus`. Only `PAID` counts as collected. */
const PAYMENT_STATUSES = ['PENDING', 'PAID', 'FAILED', 'VOIDED']

/** Directions, mirrored from `PaymentDirection`. An `OUTBOUND` row is a refund. */
const PAYMENT_DIRECTIONS = ['INBOUND', 'OUTBOUND']

export default function PaymentsPage() {
  return (
    <PageAccessGuard permission="PAYMENT_VIEW">
      <MoneyDesk />
    </PageAccessGuard>
  )
}

function MoneyDesk() {
  const queryClient = useQueryClient()
  const toast = useToast()
  const [searchParams, setSearchParams] = useSearchParams()

  // The register lives in the URL so the view survives a reload and can be linked.
  const register: 'invoices' | 'receipts' = searchParams.get('register') === 'receipts' ? 'receipts' : 'invoices'

  const [search, setSearch] = useState('')
  const [from, setFrom] = useState('')
  const [to, setTo] = useState('')
  const [status, setStatus] = useState('')
  const [direction, setDirection] = useState('')
  const [overdueOnly, setOverdueOnly] = useState(false)
  const [sort, setSort] = useState('dueDate,desc')
  const [page, setPage] = useState(0)
  const [size, setSize] = useState(20)

  const [voidTarget, setVoidTarget] = useState<{ kind: 'invoice' | 'receipt'; id: string; label: string } | null>(null)
  const [voidReason, setVoidReason] = useState('')
  const [voidError, setVoidError] = useState<string | null>(null)

  function switchRegister(next: 'invoices' | 'receipts') {
    const params = new URLSearchParams(searchParams)
    params.set('register', next)
    setSearchParams(params, { replace: true })
    setStatus('')
    setDirection('')
    setSort(next === 'invoices' ? 'dueDate,desc' : 'receivedAt,desc')
    setPage(0)
  }

  const collectionsQuery = useQuery({
    queryKey: ['collections'],
    queryFn: () => invoiceApi.collections(),
  })
  const trendQuery = useQuery({
    queryKey: ['dashboard', 'summary', 'trend'],
    queryFn: () => dashboardApi.summary(),
  })

  const invoicesQuery = useQuery({
    queryKey: ['invoices', { search, from, to, status, overdueOnly, sort, page, size }],
    queryFn: () => invoiceApi.list({
      search: search.trim() || undefined,
      from: from || undefined,
      to: to || undefined,
      status: status || undefined,
      overdueOnly: overdueOnly || undefined,
      sort,
      page,
      size,
    }),
    enabled: register === 'invoices',
  })

  const receiptsQuery = useQuery({
    queryKey: ['receipts', { search, from, to, status, direction, sort, page, size }],
    queryFn: () => paymentApi.list({
      search: search.trim() || undefined,
      from: from || undefined,
      to: to || undefined,
      status: status || undefined,
      direction: direction || undefined,
      sort,
      page,
      size,
    }),
    enabled: register === 'receipts',
  })

  const voidMutation = useMutation({
    // One mutation for both registers so the reason-collecting dialog is written once. It deliberately
    // returns nothing: the two endpoints answer with different projections, and this screen only needs
    // to know that the void went through.
    mutationFn: async (vars: { kind: 'invoice' | 'receipt'; id: string; reason: string }) => {
      if (vars.kind === 'invoice') {
        await invoiceApi.void(vars.id, { reason: vars.reason })
      } else {
        await paymentApi.void(vars.id, { reason: vars.reason })
      }
    },
    onSuccess: (_result, vars) => {
      toast.push(vars.kind === 'invoice' ? 'Invoice voided' : 'Receipt voided')
      setVoidTarget(null)
      setVoidReason('')
      void queryClient.invalidateQueries({ queryKey: [vars.kind === 'invoice' ? 'invoices' : 'receipts'] })
      void queryClient.invalidateQueries({ queryKey: ['collections'] })
    },
  })

  // `invoiceApi.collections` declares `RentInvoiceDtos.CollectionsPosition`: every field below is a
  // figure the endpoint promised, not a value probed out of an untyped map.
  const collections = collectionsQuery.data
  const trend = trendQuery.data?.trend ?? []
  const filtersActive = Boolean(search.trim() || from || to || status || direction || overdueOnly)

  function submitVoid() {
    setVoidError(null)
    if (!voidTarget) return
    // Voiding is reversible in the books' history, but it needs the reason the schema requires.
    if (!voidReason.trim()) return setVoidError('A reason is required to void')
    voidMutation.mutate({ kind: voidTarget.kind, id: voidTarget.id, reason: voidReason })
  }

  function openVoid(target: { kind: 'invoice' | 'receipt'; id: string; label: string }) {
    voidMutation.reset()
    setVoidError(null)
    setVoidReason('')
    setVoidTarget(target)
  }

  const collectionsFailed = collectionsQuery.isError

  return (
    <>
      <PageHeader
        title="Payments"
        description="Rent invoices and the receipts banked against them — one ledger, read two ways."
      />

      {/* ── The collections position, from the API ───────────────────────── */}
      {collectionsQuery.isLoading && (
        <div className="mb-5 grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
          {Array.from({ length: 4 }).map((_, index) => <Skeleton key={index} rows={3} />)}
        </div>
      )}
      {collectionsFailed && (
        <div className="mb-5">
          <ErrorState message={messageOf(collectionsQuery.error)} onRetry={() => void collectionsQuery.refetch()} />
        </div>
      )}
      {collections && (
        <div className="mb-5 grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
          <KpiTile label="Invoiced" value={money(collections.invoiced)} tone="copper" icon={<Wallet size={15} />}
            caption={collections.from && collections.to ? `${date(collections.from)} – ${date(collections.to)}` : 'Period not reported'} />
          <KpiTile label="Collected" value={money(collections.collected)} tone="emerald" icon={<TrendingUp size={15} />}
            caption="Cleared receipts only — a refund never increases this" />
          <KpiTile label="Outstanding" value={money(collections.outstanding)} tone="amber" icon={<FileWarning size={15} />}
            caption="Owed on collectable invoices" />
          <KpiTile label="Overdue" value={money(collections.overdue)} tone="terracotta" icon={<AlertTriangle size={15} />}
            caption={`${fmtCount(collections.overdueCount)} invoices past the grace period`} />
        </div>
      )}

      {/* ── Collections analytics ───────────────────────────────────────── */}
      <SectionHeader eyebrow="Analytics" title="Collections position" rule="emerald"
        description="Rent raised against rent collected, month by month, and the position it leaves today." />
      <div className="mb-6 grid gap-4 xl:grid-cols-[2fr_1fr]">
        <Panel title="Rent invoiced against collected" subtitle="Monthly, from the rent ledger" tint="emerald">
          <TrendArea
            points={trend.map((point) => ({ label: point.month, value: point.invoiced }))}
            title="Invoiced"
            unit="currency"
            primaryTone="copper"
          />
          <div className="mt-4 border-t pt-4" style={{ borderColor: 'var(--border)' }}>
            <TrendArea
              points={trend.map((point) => ({ label: point.month, value: point.collected }))}
              title="Collected"
              unit="currency"
              primaryTone="emerald"
            />
          </div>
          {trendQuery.isError && (
            <p className="mt-3 text-xs" role="status" style={{ color: 'var(--text-secondary)' }}>
              The monthly series could not be read: {messageOf(trendQuery.error)}
            </p>
          )}
        </Panel>
        <Panel title="Position" subtitle="Invoiced against what has settled" tint="copper">
          {collections && comparisonPoints(collections).length >= 2 ? (
            <ComparisonBars title="Collections position" points={comparisonPoints(collections)} />
          ) : (
            <EmptyState
              title="The position is not available"
              description="The collections summary did not report enough figures to compare, so nothing is charted rather than a set of zeros."
            />
          )}
        </Panel>
      </div>

      {/* ── The register ────────────────────────────────────────────────── */}
      <SectionHeader
        eyebrow="Ledger"
        title={register === 'invoices' ? 'Rent invoices' : 'Receipts'}
        rule="copper"
        description={register === 'invoices'
          ? 'Every demand for rent, with the settlement the API derives for it.'
          : 'Money in, and the outbound refund rows that reverse a receipt.'}
        actions={
          <div role="group" aria-label="Choose which register to read" className="flex gap-1 rounded-[var(--radius-control)] p-1"
            style={{ background: 'var(--surface-sunken)' }}>
            <Button
              size="sm"
              variant={register === 'invoices' ? 'primary' : 'ghost'}
              aria-pressed={register === 'invoices'}
              onClick={() => switchRegister('invoices')}
            >
              Invoices
            </Button>
            <Button
              size="sm"
              variant={register === 'receipts' ? 'primary' : 'ghost'}
              aria-pressed={register === 'receipts'}
              onClick={() => switchRegister('receipts')}
            >
              Receipts
            </Button>
          </div>
        }
      />

      <Panel notch>
        <>
          <Toolbar>
            <Field label="From" htmlFor="payment-filter-from">
              <Input id="payment-filter-from" type="date" value={from} onChange={(event) => { setFrom(event.target.value); setPage(0) }} />
            </Field>
            <Field label="To" htmlFor="payment-filter-to">
              <Input id="payment-filter-to" type="date" value={to} onChange={(event) => { setTo(event.target.value); setPage(0) }} />
            </Field>
            <Field label="Status" htmlFor="payment-filter-status">
              <Select id="payment-filter-status" value={status} onChange={(event) => { setStatus(event.target.value); setPage(0) }}>
                <option value="">Any status</option>
                {(register === 'invoices' ? INVOICE_STATUSES : PAYMENT_STATUSES).map((value) => (
                  <option key={value} value={value}>{humanize(value)}</option>
                ))}
              </Select>
            </Field>
            {register === 'receipts' && (
              <Field label="Direction" htmlFor="payment-filter-direction">
                <Select id="payment-filter-direction" value={direction} onChange={(event) => { setDirection(event.target.value); setPage(0) }}>
                  <option value="">Both directions</option>
                  {PAYMENT_DIRECTIONS.map((value) => (
                    <option key={value} value={value}>{value === 'OUTBOUND' ? 'Refund (outbound)' : 'Receipt (inbound)'}</option>
                  ))}
                </Select>
              </Field>
            )}
            {register === 'invoices' && (
              <label className="flex items-center gap-2 pb-2.5 text-xs" style={{ color: 'var(--text-secondary)' }}>
                <input
                  type="checkbox"
                  className="checkbox"
                  checked={overdueOnly}
                  onChange={(event) => { setOverdueOnly(event.target.checked); setPage(0) }}
                />
                Overdue only
              </label>
            )}
            <Field label="Search" htmlFor="payment-filter-search">
              <Input id="payment-filter-search" value={search} placeholder="Number, reference" onChange={(event) => { setSearch(event.target.value); setPage(0) }} />
            </Field>
            <div className="flex items-end gap-2">
              <Button
                onClick={() => void (register === 'invoices' ? invoicesQuery.refetch() : receiptsQuery.refetch())}
                aria-label="Reload the register"
              >
                <RefreshCw size={14} aria-hidden="true" /> Reload
              </Button>
            </div>
          </Toolbar>

          {register === 'invoices' && (
            <>
              {invoicesQuery.isLoading && <div className="p-4"><Skeleton rows={6} /></div>}
              {invoicesQuery.isError && (
                <div className="p-4"><ErrorState message={messageOf(invoicesQuery.error)} onRetry={() => void invoicesQuery.refetch()} /></div>
              )}
              {invoicesQuery.data && (
                <>
                  <DataTable
                    caption="Rent invoices"
                    columns={invoiceColumns((row) => openVoid({ kind: 'invoice', id: row.id, label: row.invoiceNumber }))}
                    rows={invoicesQuery.data.items}
                    keyOf={(row) => row.id}
                    sort={sort}
                    onSortChange={(next) => { setSort(next); setPage(0) }}
                    empty={filtersActive ? (
                      <EmptyState icon={<Search size={20} />} title="No invoices match these filters"
                        description="Change the period, status or search — the invoices that do exist will reappear." />
                    ) : (
                      <EmptyState icon={<FileWarning size={20} />} title="No rent has been invoiced yet"
                        description="Invoices are raised against a lease for a period. Raise one and its settlement appears here." />
                    )}
                  />
                  <Pagination
                    page={invoicesQuery.data.page} size={invoicesQuery.data.size}
                    totalElements={invoicesQuery.data.totalElements} totalPages={invoicesQuery.data.totalPages}
                    hasNext={invoicesQuery.data.hasNext}
                    onPageChange={setPage} onSizeChange={(next) => { setSize(next); setPage(0) }}
                  />
                </>
              )}
            </>
          )}

          {register === 'receipts' && (
            <>
              {receiptsQuery.isLoading && <div className="p-4"><Skeleton rows={6} /></div>}
              {receiptsQuery.isError && (
                <div className="p-4"><ErrorState message={messageOf(receiptsQuery.error)} onRetry={() => void receiptsQuery.refetch()} /></div>
              )}
              {receiptsQuery.data && (
                <>
                  <DataTable
                    caption="Receipts"
                    columns={receiptColumns((row) => openVoid({ kind: 'receipt', id: row.id, label: row.receiptNumber }))}
                    rows={receiptsQuery.data.items}
                    keyOf={(row) => row.id}
                    sort={sort}
                    onSortChange={(next) => { setSort(next); setPage(0) }}
                    empty={filtersActive ? (
                      <EmptyState icon={<Search size={20} />} title="No receipts match these filters"
                        description="Change the period, status, direction or search — the receipts that do exist will reappear." />
                    ) : (
                      <EmptyState icon={<Receipt size={20} />} title="No receipts have been recorded yet"
                        description="A receipt is banked against a lease or a sale. Record one and it appears here, with any refund shown as its own outbound row." />
                    )}
                  />
                  <Pagination
                    page={receiptsQuery.data.page} size={receiptsQuery.data.size}
                    totalElements={receiptsQuery.data.totalElements} totalPages={receiptsQuery.data.totalPages}
                    hasNext={receiptsQuery.data.hasNext}
                    onPageChange={setPage} onSizeChange={(next) => { setSize(next); setPage(0) }}
                  />
                </>
              )}
            </>
          )}

          <p className="sr-only" role="status" aria-live="polite">
            {register === 'invoices'
              ? (invoicesQuery.isFetching ? 'Updating the invoice register' : 'Invoice register up to date')
              : (receiptsQuery.isFetching ? 'Updating the receipt register' : 'Receipt register up to date')}
          </p>
        </>
      </Panel>

      {/* ── Void a document ──────────────────────────────────────────────── */}
      <Dialog
        open={voidTarget !== null}
        onClose={() => setVoidTarget(null)}
        title={voidTarget?.kind === 'invoice' ? 'Void this invoice' : 'Void this receipt'}
        description={voidTarget ? `${voidTarget.label} — the row is kept and stops counting toward settlement.` : undefined}
        width="sm"
        footer={
          <>
            <Button variant="ghost" onClick={() => setVoidTarget(null)}>Cancel</Button>
            <Button variant="danger" loading={voidMutation.isPending} disabled={!voidReason.trim()} onClick={submitVoid}>
              Void
            </Button>
          </>
        }
      >
        <div className="space-y-4">
          <Field label="Reason" htmlFor="payment-void-reason" required hint="Recorded against the void, as the schema requires">
            <Textarea id="payment-void-reason" value={voidReason} onChange={(event) => setVoidReason(event.target.value)} />
          </Field>
          {voidMutation.isError && <ErrorState message={messageOf(voidMutation.error)} />}
          {voidError && <p className="text-sm" role="alert" style={{ color: 'var(--danger)' }}>{voidError}</p>}
        </div>
      </Dialog>
    </>
  )
}

/**
 * The comparison bars, built only from the figures the API actually sent.
 *
 * `CollectionsPosition` carries all four, so each bar is drawn from a real figure; the guard is kept
 * so a position that somehow arrived without one charts nothing rather than a zero.
 */
function comparisonPoints(position: CollectionsPosition): { label: string; value: number; tone: Tone }[] {
  const points: { label: string; value: number; tone: Tone }[] = []
  points.push({ label: 'Invoiced', value: position.invoiced, tone: 'copper' })
  points.push({ label: 'Collected', value: position.collected, tone: 'emerald' })
  points.push({ label: 'Outstanding', value: position.outstanding, tone: 'amber' })
  points.push({ label: 'Overdue', value: position.overdue, tone: 'terracotta' })
  return points.filter((point) => Number.isFinite(point.value))
}

const invoiceColumns = (onVoid: (row: InvoiceSummary) => void): Column<InvoiceSummary>[] => [
  { key: 'number', header: 'Invoice', width: '14%', sortKey: 'invoiceNumber', render: (row) => (
    <span className="truncate font-medium" style={{ color: 'var(--text-primary)' }}>{row.invoiceNumber}</span>
  ) },
  /*
   * This column used to name the tenant and the property, reading `tenantName` and `propertyName`.
   * Neither is on `RentInvoiceDtos.RentInvoiceSummary`: an invoice row names its lease, tenant, property
   * and unit by id, and resolves only the lease's *code*. So the "Tenant" slot now holds the lease code,
   * which the row does send, and the "Property" slot is gone with the tenant's own name and property's
   * own name - both of which are read from the lease the invoice points at.
   */
  { key: 'lease', header: 'Lease', width: '14%', render: (row) => <span className="truncate tabular-nums">{row.leaseCode ?? '—'}</span> },
  { key: 'period', header: 'Period', width: '18%', render: (row) => (
    <span className="truncate text-xs">{date(row.periodStart)} – {date(row.periodEnd)}</span>
  ) },
  { key: 'due', header: 'Due', width: '10%', sortKey: 'dueDate', render: (row) => date(row.dueDate) },
  /*
   * "Settled", "Outstanding" and "Overdue" have been removed rather than shown as dashes: the register
   * does not store settlement, and deriving it per row would mean one invoice-detail request per row -
   * the one thing this screen's own rules forbid. The four amounts the invoice does store are shown
   * instead, and the settlement of one invoice is read from its own detail record.
   */
  { key: 'subtotal', header: 'Subtotal', width: '12%', align: 'right', secondary: true, sortKey: 'totalAmount', render: (row) => money(row.subtotal) },
  { key: 'tax', header: 'Tax', width: '10%', align: 'right', secondary: true, render: (row) => money(row.taxAmount) },
  { key: 'total', header: 'Total', width: '11%', align: 'right', sortKey: 'totalAmount', render: (row) => money(row.totalAmount) },
  { key: 'credited', header: 'Credited', width: '11%', align: 'right', secondary: true, render: (row) => money(row.creditedAmount) },
  { key: 'status', header: 'Status', width: '9%', sortKey: 'status', render: (row) => <StatusBadge status={row.status} /> },
  { key: 'actions', header: 'Actions', width: '5%', secondary: true, render: (row) => (
    <Button
      size="sm"
      variant="outline"
      disabled={row.status === 'VOID'}
      onClick={() => onVoid(row)}
      aria-label={`Void invoice ${row.invoiceNumber}`}
    >
      Void
    </Button>
  ) },
]

const receiptColumns = (onVoid: (row: PaymentSummary) => void): Column<PaymentSummary>[] => [
  { key: 'number', header: 'Receipt', width: '14%', sortKey: 'receiptNumber', render: (row) => (
    <span className="truncate font-medium" style={{ color: 'var(--text-primary)' }}>{row.receiptNumber}</span>
  ) },
  { key: 'direction', header: 'Direction', width: '11%', render: (row) => (
    row.direction === 'OUTBOUND'
      ? <Badge tone="burgundy">Refund</Badge>
      : <Badge tone="emerald">Receipt</Badge>
  ) },
  /*
   * This cell used to name the tenant or the buyer, reading `tenantName`/`buyerName`. Neither is on
   * `PaymentDtos.PaymentSummary`: a receipt names the party it settles by id, and carries the free-text
   * `reference` the desk recorded instead. The reference takes the slot.
   */
  { key: 'reference', header: 'Reference', width: '17%', secondary: true, render: (row) => <span className="truncate">{row.reference ?? '—'}</span> },
  { key: 'amount', header: 'Amount', width: '12%', align: 'right', sortKey: 'amount', render: (row) => money(row.amount) },
  { key: 'currency', header: 'Currency', width: '8%', secondary: true, render: (row) => row.currency },
  // The DTO resolves the method to a name (`paymentMethodName`); the row has no bare `method`.
  { key: 'method', header: 'Method', width: '11%', secondary: true, render: (row) => row.paymentMethodName ?? row.paymentMethodCode ?? '—' },
  { key: 'date', header: 'Received', width: '11%', sortKey: 'receivedAt', render: (row) => date(row.receivedAt) },
  { key: 'status', header: 'Status', width: '9%', sortKey: 'status', render: (row) => <StatusBadge status={row.status} /> },
  { key: 'actions', header: 'Actions', width: '7%', secondary: true, render: (row) => (
    <Button
      size="sm"
      variant="outline"
      disabled={row.status === 'VOIDED'}
      onClick={() => onVoid(row)}
      aria-label={`Void receipt ${row.receiptNumber}`}
    >
      Void
    </Button>
  ) },
]
