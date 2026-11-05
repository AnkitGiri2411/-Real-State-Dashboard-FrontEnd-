import { useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { ArrowLeft, CalendarClock, Receipt, Wallet } from 'lucide-react'
import { PageAccessGuard, PageHeader } from '../components/common'
import {
  Button, DataTable, DetailItem, Dialog, EmptyState, ErrorState, Field, KpiTile, Panel,
  SectionHeader, Select, Skeleton, StatusBadge, Textarea, useToast, type Column,
} from '../components/ui'
import { count as fmtCount, date, dateTime, daysUntil, humanize, money, moneyCompact, relative } from '../components/ui/formatters'
import { invoiceApi, leaseApi } from '../services'
import { ApiError, messageOf } from '../lib/errors'
import type { InvoiceSummary } from '../types'

/**
 * One lease, in full.
 *
 * The screen is built around a single rule: **the backend is the only source of financial truth.**
 * The money panel is the `finance` block on `GET /leases/{id}`, whose `invoiced`, `settled`,
 * `outstanding` and `overdue` are the service's own roll-up (allocations on paid receipts, voided
 * invoices excluded, the grace period applied). The per-invoice settlement columns are read straight
 * off the invoice list. Nothing on this page adds up allocations, subtracts payments or derives a
 * balance — a second implementation of the ledger is exactly how a UI comes to disagree with the
 * database.
 *
 * The lifecycle is the same story: the only moves offered are the ones `LeaseService.isAllowed`
 * permits, and a termination carries the reason the schema requires.
 */

/**
 * The status moves a lease may make, mirrored from `LeaseService.ALLOWED_TRANSITIONS`. `TERMINATED`
 * is deliberately left out of the inline control: ending a tenancy is a distinct, destructive act
 * with its own confirmation and its own required reason, so it has its own button rather than
 * sitting in a dropdown beside `EXPIRING`.
 */
const LEASE_TRANSITIONS: Record<string, string[]> = {
  DRAFT: ['PENDING', 'ACTIVE', 'CANCELLED'],
  PENDING: ['DRAFT', 'ACTIVE', 'CANCELLED'],
  ACTIVE: ['EXPIRING', 'RENEWED'],
  EXPIRING: ['ACTIVE', 'RENEWED'],
  RENEWED: [],
  TERMINATED: [],
  CANCELLED: [],
}

/** A settled tenancy shows no countdown. */
const SETTLED_STATUSES = new Set(['RENEWED', 'TERMINATED', 'CANCELLED'])

/**
 * `leaseApi.detail` answers the fuller `LeaseDtos.LeaseDetail` projection - the register row plus the
 * rent schedule, the status trail, the renewals and the money position. That record is declared in
 * `src/types` from the DTO itself, so this screen reads it by name; there is no second copy of it here.
 */

export default function LeaseDetailPage() {
  return (
    <PageAccessGuard permission="LEASE_VIEW">
      <LeaseRecord />
    </PageAccessGuard>
  )
}

function LeaseRecord() {
  const { leaseId } = useParams<{ leaseId: string }>()
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const toast = useToast()

  const [statusOpen, setStatusOpen] = useState(false)
  const [targetStatus, setTargetStatus] = useState('')
  const [statusReason, setStatusReason] = useState('')
  const [terminateOpen, setTerminateOpen] = useState(false)
  const [terminationReason, setTerminationReason] = useState('')
  const [dialogError, setDialogError] = useState<string | null>(null)

  const detailQuery = useQuery({
    queryKey: ['lease', leaseId],
    queryFn: () => leaseApi.detail(leaseId as string),
    enabled: Boolean(leaseId),
  })
  /*
   * The money position travels on the detail read, not on a call of its own.
   *
   * `LeaseDtos.LeaseDetail` already carries `finance` - the ledger's own roll-up of invoiced,
   * settled, outstanding and overdue - so the panel is filled from the record the page has already
   * fetched. There is no `/leases/{id}/finance` endpoint: asking for one answered 404 on every
   * lease and left the panel in an error state beside figures the detail had already sent.
   */
  const invoicesQuery = useQuery({
    queryKey: ['lease', leaseId, 'invoices'],
    queryFn: () => invoiceApi.list({ leaseId: leaseId as string, size: 50, sort: 'dueDate,desc' }),
    enabled: Boolean(leaseId),
  })

  const statusMutation = useMutation({
    mutationFn: (vars: { status: string; reason: string }) => leaseApi.changeStatus(leaseId as string, {
      status: vars.status,
      reason: vars.reason.trim() || undefined,
    }),
    onSuccess: () => {
      toast.push('Lease status changed')
      setStatusOpen(false)
      setTerminateOpen(false)
      setTargetStatus('')
      setStatusReason('')
      setTerminationReason('')
      void queryClient.invalidateQueries({ queryKey: ['lease', leaseId] })
      void queryClient.invalidateQueries({ queryKey: ['leases'] })
    },
  })

  if (!leaseId) {
    return (
      <>
        <PageHeader title="Lease" />
        <EmptyState title="No lease was named in the address" description="Open a lease from the register and this screen will show its terms, invoices and money position." />
      </>
    )
  }

  if (detailQuery.isLoading) {
    return (
      <>
        <PageHeader title="Lease" description="Loading the tenancy…" />
        <div className="space-y-4">
          <Skeleton rows={4} />
          <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
            {Array.from({ length: 4 }).map((_, index) => <Skeleton key={index} rows={3} />)}
          </div>
          <Skeleton rows={6} />
        </div>
      </>
    )
  }

  if (detailQuery.isError) {
    // A missing lease is not a failure of the screen — it is an answer. Everything else is a failure.
    if (isNotFound(detailQuery.error)) {
      return (
        <>
          <PageHeader title="Lease not found" />
          <EmptyState
            title="We could not find that lease"
            description="It may have been removed, or the address may be mistyped. The register lists every tenancy the portfolio holds."
            action={<Button onClick={() => void navigate('/leases')}>Back to the lease register</Button>}
          />
        </>
      )
    }
    return (
      <>
        <PageHeader title="Lease" />
        <ErrorState message={messageOf(detailQuery.error)} onRetry={() => void detailQuery.refetch()} />
      </>
    )
  }

  const lease = detailQuery.data
  if (!lease) {
    return (
      <>
        <PageHeader title="Lease" />
        <EmptyState
          title="The lease's record came back empty"
          description="The detail read returned no payload, so nothing is rendered rather than a blank tenancy."
        />
      </>
    )
  }

  const terms = lease.terms
  const history = lease.statusHistory
  const finance = lease.finance
  const allowed = LEASE_TRANSITIONS[lease.status] ?? []
  const canTerminate = lease.status === 'ACTIVE' || lease.status === 'EXPIRING'
  // `daysRemaining` is not on the lease record; `LeaseDtos.ExpiringLease` is where a server-counted
  // days-until-end lives. The countdown here is read from the end date, which the lease does send.
  const days = daysUntil(lease.endDate)

  function submitStatus() {
    setDialogError(null)
    if (!targetStatus) return setDialogError('Choose the status to move to')
    statusMutation.mutate({ status: targetStatus, reason: statusReason })
  }

  function submitTerminate() {
    setDialogError(null)
    // The schema (and `LEASE_TERMINATE`) insists a terminated lease carries a reason.
    if (!terminationReason.trim()) return setDialogError('A termination reason is required')
    statusMutation.mutate({ status: 'TERMINATED', reason: terminationReason })
  }

  return (
    <>
      <PageHeader
        eyebrow="Lease"
        title={lease.leaseCode}
        description={[lease.tenantName, lease.propertyName].filter(Boolean).join(' · ')}
        actions={
          <>
            <Button variant="ghost" onClick={() => void navigate('/leases')}>
              <ArrowLeft size={15} aria-hidden="true" /> All leases
            </Button>
            <Button
              variant="outline"
              disabled={allowed.length === 0}
              onClick={() => { statusMutation.reset(); setDialogError(null); setTargetStatus(''); setStatusReason(''); setStatusOpen(true) }}
            >
              Change status
            </Button>
            <Button
              variant="danger"
              disabled={!canTerminate}
              onClick={() => { statusMutation.reset(); setDialogError(null); setTerminationReason(''); setTerminateOpen(true) }}
            >
              Terminate
            </Button>
          </>
        }
      />

      {/* ── The four figures a tenancy is read by ─────────────────────────── */}
      <div className="mb-5 grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
        <KpiTile label="Rent" value={money(lease.rentAmount)} tone="copper" icon={<Wallet size={15} />}
          caption={lease.rentFrequency ? `${humanize(lease.rentFrequency)}${lease.dueDay ? ` · due on day ${lease.dueDay}` : ''}` : 'Billing cadence not reported'} />
        <KpiTile label="Deposit" value={money(lease.depositAmount)} tone="champagne"
          caption="Held against the tenancy, not income" />
        <KpiTile label="Status" value={<StatusBadge status={lease.status} />} tone="forest"
          caption={lease.activatedAt ? `Activated ${date(lease.activatedAt)}` : 'Not yet activated'} />
        <KpiTile
          label="Days remaining"
          value={SETTLED_STATUSES.has(lease.status) ? '—' : (days === null ? '—' : fmtCount(days))}
          tone={days !== null && days < 0 && !SETTLED_STATUSES.has(lease.status) ? 'terracotta' : 'teal'}
          icon={<CalendarClock size={15} />}
          caption={SETTLED_STATUSES.has(lease.status) ? 'The tenancy has ended' : `Ends ${date(lease.endDate)}`}
        />
      </div>

      {/* ── The money position, exactly as the service derived it ─────────── */}
      <SectionHeader eyebrow="Money" title="Finance position" rule="copper"
        description="Invoiced, settled, outstanding and overdue as the ledger reports them — never a figure recomputed in the browser." />
      <div className="mb-6">
        <Panel notch tint="copper">
          {finance && (
            <>
              <dl className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
                <DetailItem label="Invoiced" value={<span className="kpi-value text-lg">{money(finance.invoiced)}</span>} />
                <DetailItem label="Settled" value={<span className="kpi-value text-lg">{money(finance.settled)}</span>} />
                <DetailItem label="Outstanding" value={<span className="kpi-value text-lg">{money(finance.outstanding)}</span>} />
                <DetailItem label="Overdue" value={<span className="kpi-value text-lg" style={{ color: 'var(--ink-terracotta)' }}>{money(finance.overdue)}</span>} />
              </dl>
              <p className="mt-4 border-t pt-3 text-xs" style={{ borderColor: 'var(--border)', color: 'var(--text-secondary)' }}>
                {`${fmtCount(finance.invoiceCount)} invoices raised`}
                {` · ${fmtCount(finance.overdueCount)} past the grace period`}
                {` · ${fmtCount(finance.receiptCount)} receipts against this lease`}
              </p>
            </>
          )}
        </Panel>
      </div>

      <div className="mb-6 grid gap-4 xl:grid-cols-2">
        {/* ── Rent schedule ──────────────────────────────────────────────── */}
        <Panel title="Rent schedule" subtitle="The revisions recorded against the lease" tint="sandstone">
          {terms.length === 0 ? (
            <EmptyState
              title="No rent revisions have been recorded"
              description="The headline rent above is the only figure in force. Add a revision on the lease and the schedule appears here."
            />
          ) : (
            <ol className="space-y-2">
              {terms.map((term) => (
                <li key={term.id} className="rounded-[var(--radius-card)] border px-3 py-2"
                  style={{ borderColor: 'var(--border)', background: 'var(--surface-elevated)' }}>
                  <div className="flex flex-wrap items-baseline justify-between gap-2">
                    <span className="text-xs font-semibold" style={{ color: 'var(--text-primary)' }}>
                      Revision {term.termNumber}
                    </span>
                    <span className="kpi-value text-sm" style={{ color: 'var(--ink-copper)' }}>{money(term.rentAmount)}</span>
                  </div>
                  <p className="mt-0.5 text-[11px]" style={{ color: 'var(--text-secondary)' }}>
                    {date(term.effectiveFrom)} – {term.effectiveTo ? date(term.effectiveTo) : 'open-ended'}
                    {term.escalationPercent !== null ? ` · escalation ${term.escalationPercent}%` : ''}
                  </p>
                  {term.notes && <p className="mt-1 text-[11px]" style={{ color: 'var(--text-secondary)' }}>{term.notes}</p>}
                </li>
              ))}
            </ol>
          )}
          <p className="mt-3 border-t pt-3 text-[11px]" style={{ borderColor: 'var(--border)', color: 'var(--text-secondary)' }}>
            Agreement {lease.agreementReference ?? '—'}
            {lease.noticePeriodDays ? ` · ${lease.noticePeriodDays}-day notice period` : ''}
          </p>
        </Panel>

        {/* ── Status history ─────────────────────────────────────────────── */}
        <Panel title="Status history" subtitle="Every recorded move, newest first" tint="forest">
          {history.length === 0 ? (
            <EmptyState
              title="No status moves have been recorded"
              description="The lease has not changed state since it was drafted, so there is no trail to show."
            />
          ) : (
            <ol className="space-y-2">
              {history.map((change) => (
                <li key={change.id} className="flex flex-wrap items-center justify-between gap-2 border-b pb-2 last:border-b-0"
                  style={{ borderColor: 'var(--border)' }}>
                  <span className="flex flex-wrap items-center gap-1.5 text-xs" style={{ color: 'var(--text-primary)' }}>
                    {change.fromStatus ? <StatusBadge status={change.fromStatus} /> : <span style={{ color: 'var(--text-secondary)' }}>new</span>}
                    <span aria-hidden="true" style={{ color: 'var(--text-secondary)' }}>→</span>
                    <StatusBadge status={change.toStatus} />
                  </span>
                  <span className="text-right">
                    {change.reason && <span className="block text-[11px]" style={{ color: 'var(--text-secondary)' }}>{change.reason}</span>}
                    <span className="block text-[11px] tabular-nums" style={{ color: 'var(--text-secondary)' }}>{relative(change.changedAt)}</span>
                  </span>
                </li>
              ))}
            </ol>
          )}
          {lease.terminationReason && (
            <p className="mt-3 rounded-[var(--radius-card)] px-3 py-2 text-xs"
              style={{ background: 'var(--surface-terracotta)', color: 'var(--ink-terracotta)' }}>
              Terminated {dateTime(lease.terminatedAt)} — {lease.terminationReason}
            </p>
          )}
        </Panel>
      </div>

      {/* ── Invoices raised against this lease ───────────────────────────── */}
      <SectionHeader eyebrow="Rent" title="Invoices" rule="emerald"
        description="What this lease has been billed. Subtotal, tax, total and credited are the amounts the register stores; the settlement those amounts produce is the lease's finance panel above, which is the ledger's own roll-up." />
      <div className="mb-6">
        <Panel notch>
          {invoicesQuery.isLoading && <div className="p-4"><Skeleton rows={6} /></div>}
          {invoicesQuery.isError && (
            <div className="p-4"><ErrorState message={messageOf(invoicesQuery.error)} onRetry={() => void invoicesQuery.refetch()} /></div>
          )}
          {invoicesQuery.data && (
            <DataTable
              caption="Invoices for this lease"
              columns={invoiceColumns}
              rows={invoicesQuery.data.items}
              keyOf={(row) => row.id}
              empty={
                <EmptyState
                  icon={<Receipt size={20} />}
                  title="No rent has been invoiced for this lease"
                  description="An invoice is raised against the lease for a period. Raise the first one and its settlement appears here."
                />
              }
            />
          )}
        </Panel>
      </div>

      {/* ── Inline lifecycle actions ─────────────────────────────────────── */}
      <Dialog
        open={statusOpen}
        onClose={() => setStatusOpen(false)}
        title="Change lease status"
        description={`${lease.leaseCode} · currently ${humanize(lease.status)}`}
        footer={
          <>
            <Button variant="ghost" onClick={() => setStatusOpen(false)}>Cancel</Button>
            <Button variant="primary" loading={statusMutation.isPending} disabled={!targetStatus} onClick={submitStatus}>Apply move</Button>
          </>
        }
      >
        <div className="space-y-4">
          <Field label="Move to" htmlFor="lease-detail-target" required hint="Only the moves this state permits are listed">
            <Select id="lease-detail-target" value={targetStatus} onChange={(event) => setTargetStatus(event.target.value)}>
              <option value="">Choose a status…</option>
              {allowed.map((value) => <option key={value} value={value}>{humanize(value)}</option>)}
            </Select>
          </Field>
          {statusMutation.isError && <ErrorState message={messageOf(statusMutation.error)} />}
          {dialogError && <p className="text-sm" role="alert" style={{ color: 'var(--danger)' }}>{dialogError}</p>}
        </div>
      </Dialog>

      <Dialog
        open={terminateOpen}
        onClose={() => setTerminateOpen(false)}
        title="Terminate this lease"
        description="Ending a tenancy is recorded with the reason the schema requires, and cannot be undone."
        width="sm"
        footer={
          <>
            <Button variant="ghost" onClick={() => setTerminateOpen(false)}>Cancel</Button>
            <Button variant="danger" loading={statusMutation.isPending} onClick={submitTerminate}>Terminate lease</Button>
          </>
        }
      >
        <div className="space-y-4">
          <Field label="Termination reason" htmlFor="lease-terminate-reason" required>
            <Textarea id="lease-terminate-reason" value={terminationReason} onChange={(event) => setTerminationReason(event.target.value)} />
          </Field>
          {statusMutation.isError && <ErrorState message={messageOf(statusMutation.error)} />}
          {dialogError && <p className="text-sm" role="alert" style={{ color: 'var(--danger)' }}>{dialogError}</p>}
        </div>
      </Dialog>

      <p className="mt-4 text-[11px]" style={{ color: 'var(--text-secondary)' }}>
        Opened {dateTime(lease.startDate)} · figure of record {moneyCompact(lease.rentAmount)} per period
      </p>
    </>
  )
}

/**
 * A missing lease (404 or the named `LEASE_NOT_FOUND`) is an answer, not an error.
 */
function isNotFound(error: unknown): boolean {
  return error instanceof ApiError && (error.code === 'LEASE_NOT_FOUND' || error.status === 404)
}

/*
 * The invoice columns.
 *
 * The register read is `RentInvoiceDtos.RentInvoiceSummary`, and it deliberately carries **no**
 * settlement: whether an invoice has been paid is derived from the allocations posted against it, and
 * that derived block - `Settlement` - travels on the invoice *detail*. So a per-invoice "Settled",
 * "Outstanding" and "Overdue" column cannot be filled from anything this list returns, and filling
 * them would mean one detail request per row. They are gone; the four figures that are stored
 * (subtotal, tax, total, credited) take their place.
 *
 * The lease's own money position is not lost by this: it is the finance panel at the top of the screen,
 * which comes from the `finance` block of `GET /leases/{id}` and is the ledger's own roll-up.
 */
const invoiceColumns: Column<InvoiceSummary>[] = [
  { key: 'number', header: 'Invoice', width: '15%', render: (row) => (
    <span className="truncate font-medium" style={{ color: 'var(--text-primary)' }}>{row.invoiceNumber}</span>
  ) },
  { key: 'period', header: 'Period', width: '19%', render: (row) => (
    <span className="truncate text-xs">{date(row.periodStart)} – {date(row.periodEnd)}</span>
  ) },
  { key: 'due', header: 'Due', width: '11%', render: (row) => date(row.dueDate) },
  { key: 'subtotal', header: 'Subtotal', width: '12%', align: 'right', secondary: true, render: (row) => money(row.subtotal) },
  { key: 'tax', header: 'Tax', width: '12%', align: 'right', secondary: true, render: (row) => money(row.taxAmount) },
  { key: 'total', header: 'Total', width: '12%', align: 'right', render: (row) => money(row.totalAmount) },
  { key: 'credited', header: 'Credited', width: '12%', align: 'right', secondary: true, render: (row) => money(row.creditedAmount) },
  { key: 'status', header: 'Status', width: '12%', render: (row) => <StatusBadge status={row.status} /> },
]
