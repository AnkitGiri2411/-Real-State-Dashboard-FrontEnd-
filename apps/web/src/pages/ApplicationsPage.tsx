import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { ClipboardCheck, ListFilter, ShieldCheck } from 'lucide-react'
import { useState } from 'react'
import { ConfirmDialog, PageAccessGuard, PageHeader } from '../components/common'
import {
  Badge, Button, DataTable, DetailItem, Dialog, EmptyState, ErrorState, Field, Input, KpiTile, Panel,
  Pagination, Select, Skeleton, StatusBadge, Textarea, Toolbar, useToast, type Column,
} from '../components/ui'
import { count as fmtCount, date, dateTime, humanize, money, moneyCompact } from '../components/ui/formatters'
import { applicationApi, propertyApi } from '../services'
import { ApiError, messageOf } from '../lib/errors'
import type { ApplicationSummary, PropertySummary } from '../types'

/**
 * Rental applications, and the screening that gates a decision.
 *
 * **Two backend rules decide the shape of this screen.**
 *
 *  1. *Screening gates the decision.* An application may only be approved or rejected once every
 *     screening check has finished, and only a `PENDING` check blocks it — `WAIVED` is a finished
 *     check, an attributed decision to run none. So the register counts the checks still pending, the
 *     detail panel shows each check with its result, and the refusal is surfaced as its own sentence
 *     ("Every screening check must be finished before a decision can be taken") rather than as a
 *     generic conflict.
 *  2. *A decision is written whole.* The decider and the decision moment are taken server-side, and a
 *     rejection carries the reason the schema requires — which is why a rejection is not submitted
 *     until a reason has been given.
 *
 * Recording a screening *result* is not offered here: the service map carries no method for it (it
 * exposes `list`, `detail`, `create` and `decide`), and a screen that hand-wrote the request path would
 * be inventing the one thing it was told not to invent. The checks are read and shown in full.
 */

/** The states an application is still undecided in, mirroring `ApplicationStatus.isOpen()`. */
const OPEN_STATUSES = ['SUBMITTED', 'SCREENING']

const APPLICATION_STATUSES = ['SUBMITTED', 'SCREENING', 'APPROVED', 'REJECTED', 'WITHDRAWN']

/* --------------------------------------------------------------------------
 * The payloads, as `ApplicationDtos` sends them.
 *
 * `applicationApi.list` answers the register row and `applicationApi.detail` the detail projection with
 * its screenings and status trail. Both records are declared in `src/types` from the DTOs themselves,
 * so this screen reads them by name rather than asserting a shape the client cannot know.
 * -------------------------------------------------------------------------- */

/**
 * The sentence a refused decision deserves.
 *
 * `SCREENING_INCOMPLETE` is the one refusal a reader here can act on, so it is stated in the terms
 * the desk uses rather than as a 409.
 */
function decisionFailure(error: unknown): string {
  if (error instanceof ApiError) {
    if (error.code === 'SCREENING_INCOMPLETE') {
      return 'Every screening check must be finished before a decision can be taken'
    }
    if (error.code === 'APPLICATION_NOT_DECIDABLE') {
      return 'This application has already been decided, so it cannot be decided again'
    }
  }
  return messageOf(error)
}

export default function ApplicationsPage() {
  return (
    <PageAccessGuard permission="APPLICATION_VIEW">
      <ApplicationRegister />
    </PageAccessGuard>
  )
}

function ApplicationRegister() {
  const queryClient = useQueryClient()
  const toast = useToast()

  const [page, setPage] = useState(0)
  const [size, setSize] = useState(20)
  const [propertyId, setPropertyId] = useState('')
  const [unitId, setUnitId] = useState('')
  const [status, setStatus] = useState('')
  const [applicant, setApplicant] = useState('')

  const [openId, setOpenId] = useState<string | null>(null)

  const applicationsQuery = useQuery({
    queryKey: ['applications', 'register', { page, size, propertyId, unitId, status, applicant }],
    queryFn: () => applicationApi.list({
      page,
      size,
      propertyId: propertyId || undefined,
      unitId: unitId || undefined,
      status: status || undefined,
      // The API searches the applicant by name, email, phone or code through one term.
      applicant: applicant.trim() || undefined,
    }),
  })

  const propertiesQuery = useQuery({
    queryKey: ['properties', 'picker'],
    queryFn: () => propertyApi.list({ size: 200 }),
  })

  const unitsQuery = useQuery({
    queryKey: ['properties', propertyId, 'units', 'picker'],
    queryFn: () => propertyApi.units(propertyId, { size: 200 }),
    enabled: propertyId !== '',
  })

  const rows = applicationsQuery.data?.items ?? []
  const properties: PropertySummary[] = propertiesQuery.data?.items ?? []
  const narrowed = Boolean(propertyId || unitId || status || applicant.trim())
  const pendingRows = rows.filter((row) => row.outstandingChecks > 0).length

  const columns: Column<ApplicationSummary>[] = [
    {
      key: 'code', header: 'Application', width: '14%', sortKey: 'applicationCode',
      render: (row) => (
        <span className="block min-w-0">
          <span className="block truncate font-medium tabular-nums" style={{ color: 'var(--text-primary)' }}>
            {row.applicationCode}
          </span>
          <span className="block truncate text-[11px]" style={{ color: 'var(--text-secondary)' }}>
            {row.leadName ? `from ${row.leadName}` : 'taken at the desk'}
          </span>
        </span>
      ),
    },
    {
      key: 'applicant', header: 'Applicant', width: '18%', sortKey: 'applicant',
      render: (row) => (
        <span className="block min-w-0">
          <span className="block truncate" style={{ color: 'var(--text-primary)' }}>{row.applicantName}</span>
          <span className="block truncate text-[11px]" style={{ color: 'var(--text-secondary)' }}>
            {row.applicantEmail ?? row.applicantPhone ?? 'No contact recorded'}
          </span>
        </span>
      ),
    },
    {
      key: 'type', header: 'Type', width: '11%',
      render: (row) => humanize(row.applicantType),
    },
    {
      key: 'stock', header: 'Property / unit', width: '20%',
      render: (row) => (
        <span className="block min-w-0">
          <span className="block truncate" style={{ color: 'var(--text-primary)' }}>
            {row.propertyName ?? 'Property not recorded'}
          </span>
          <span className="block truncate text-[11px]" style={{ color: 'var(--text-secondary)' }}>
            {row.unitNumber ?? 'Whole property'}
          </span>
        </span>
      ),
    },
    {
      key: 'rent', header: 'Proposed rent', width: '13%', align: 'right', sortKey: 'proposedRent',
      render: (row) => money(row.proposedRent),
    },
    {
      key: 'status', header: 'Status', width: '14%', sortKey: 'status',
      render: (row) => (
        <span className="flex flex-wrap items-center gap-1.5">
          <StatusBadge status={row.status} />
          {/* The thing that blocks a decision is said in words, next to the state it blocks. */}
          {row.outstandingChecks > 0 && (
            <Badge tone="amber">{fmtCount(row.outstandingChecks)} check(s) pending</Badge>
          )}
        </span>
      ),
    },
    {
      key: 'submitted', header: 'Submitted', width: '10%', align: 'right', sortKey: 'submittedAt', secondary: true,
      render: (row) => date(row.submittedAt),
    },
  ]

  return (
    <>
      <PageHeader
        eyebrow="Letting"
        title="Applications"
        description="Rental applications taken against the firm's stock. An application is approved or rejected only once every screening check has finished."
      />

      <div className="mb-5 grid gap-3 sm:grid-cols-3">
        <KpiTile
          label="Applications"
          value={fmtCount(applicationsQuery.data?.totalElements ?? 0)}
          tone="plum"
          icon={<ClipboardCheck size={15} />}
          caption="Match the filters above"
        />
        <KpiTile
          label="Awaiting a decision"
          value={fmtCount(pendingRows)}
          tone="amber"
          caption="On this page, with at least one screening check still pending"
        />
        <KpiTile
          label="Proposed rent on this page"
          value={moneyCompact(rows.reduce((sum, row) => sum + (row.proposedRent ?? 0), 0))}
          tone="forest"
          caption="Summed across the loaded page, monthly"
        />
      </div>

      <Panel className="mb-4" bodyClassName="p-0">
        <Toolbar>
          <div className="w-full sm:w-52">
            <Field label="Search applicant" htmlFor="application-applicant" hint="Name, email, phone or code">
              <Input
                id="application-applicant"
                type="search"
                value={applicant}
                onChange={(event) => { setApplicant(event.target.value); setPage(0) }}
              />
            </Field>
          </div>
          <div className="w-full sm:w-52">
            <Field label="Property" htmlFor="application-property" hint={propertiesQuery.isError ? 'Property list unavailable' : undefined}>
              <Select
                id="application-property"
                value={propertyId}
                disabled={propertiesQuery.isLoading || propertiesQuery.isError}
                onChange={(event) => { setPropertyId(event.target.value); setUnitId(''); setPage(0) }}
              >
                <option value="">Every property</option>
                {properties.map((property) => (
                  <option key={property.id} value={property.id}>{property.name}</option>
                ))}
              </Select>
            </Field>
          </div>
          <div className="w-full sm:w-44">
            <Field label="Unit" htmlFor="application-unit" hint={propertyId === '' ? 'Choose a property first' : undefined}>
              <Select
                id="application-unit"
                value={unitId}
                disabled={propertyId === '' || unitsQuery.isLoading || unitsQuery.isError}
                onChange={(event) => { setUnitId(event.target.value); setPage(0) }}
              >
                <option value="">Every unit</option>
                {(unitsQuery.data?.items ?? []).map((unit) => (
                  <option key={unit.id} value={unit.id}>{unit.unitNumber}</option>
                ))}
              </Select>
            </Field>
          </div>
          <div className="w-full sm:w-40">
            <Field label="Status" htmlFor="application-status">
              <Select id="application-status" value={status} onChange={(event) => { setStatus(event.target.value); setPage(0) }}>
                <option value="">Every status</option>
                {APPLICATION_STATUSES.map((each) => <option key={each} value={each}>{humanize(each)}</option>)}
              </Select>
            </Field>
          </div>
        </Toolbar>
        <p className="px-4 py-2 text-xs" aria-live="polite" style={{ color: 'var(--text-secondary)' }}>
          {applicationsQuery.isLoading
            ? 'Reading the register…'
            : `${fmtCount(applicationsQuery.data?.totalElements ?? 0)} application(s) match these filters.`}
        </p>
      </Panel>

      {applicationsQuery.isLoading ? (
        <Panel><Skeleton rows={6} /></Panel>
      ) : applicationsQuery.isError ? (
        <ErrorState
          message={messageOf(applicationsQuery.error)}
          onRetry={() => void applicationsQuery.refetch()}
        />
      ) : (
        <Panel bodyClassName="p-0">
          <DataTable
            columns={columns}
            rows={rows}
            keyOf={(row) => row.id}
            onRowClick={(row) => setOpenId(row.id)}
            caption="Application register"
            empty={
              <EmptyState
                icon={<ListFilter size={22} />}
                title={narrowed ? 'No applications match these filters' : 'No application has been taken yet'}
                description={narrowed
                  ? 'The property, unit, status or applicant search is narrowing the register to nothing. Clear the applicant search and the applications will come back.'
                  : 'An application is taken against a unit, for a lead, a tenant or someone new. None has been taken yet.'}
              />
            }
          />
          {rows.length > 0 && (
            <Pagination
              page={applicationsQuery.data?.page ?? 0}
              size={applicationsQuery.data?.size ?? size}
              totalElements={applicationsQuery.data?.totalElements ?? 0}
              totalPages={applicationsQuery.data?.totalPages ?? 0}
              hasNext={applicationsQuery.data?.hasNext ?? false}
              onPageChange={setPage}
              onSizeChange={(next) => { setSize(next); setPage(0) }}
            />
          )}
        </Panel>
      )}

      <ApplicationDialog
        key={openId ?? 'no-application'}
        applicationId={openId}
        onClose={() => setOpenId(null)}
        onDecided={(code, decision) => {
          void queryClient.invalidateQueries({ queryKey: ['applications'] })
          toast.push(
            decision === 'APPROVED' ? `${code} was approved.` : `${code} was rejected.`,
            decision === 'APPROVED' ? 'emerald' : 'terracotta',
          )
          setOpenId(null)
        }}
      />
    </>
  )
}

/**
 * One application, with its checks and its decision.
 *
 * The decisions live here rather than on a row, because a decision is the end of a review: the reader
 * has read the screening results, and only then approves or rejects. Both decisions pass through a
 * confirmation step, and a rejection cannot reach it without a reason.
 */
function ApplicationDialog({
  applicationId, onClose, onDecided,
}: {
  applicationId: string | null
  onClose: () => void
  onDecided: (applicationCode: string, decision: 'APPROVED' | 'REJECTED') => void
}) {
  const [rejectMode, setRejectMode] = useState(false)
  const [reason, setReason] = useState('')
  const [intent, setIntent] = useState<'APPROVED' | 'REJECTED' | null>(null)
  const [failure, setFailure] = useState<string | null>(null)

  const detailQuery = useQuery({
    queryKey: ['applications', 'detail', applicationId],
    queryFn: () => applicationApi.detail(applicationId as string),
    enabled: Boolean(applicationId),
  })

  const decide = useMutation({
    /*
     * The service map's decision operation takes the decision in its body. The backend's two named
     * operations — approve and reject — are what this call stands for, so the decision travels as
     * APPROVED or REJECTED, with the reason a rejection requires.
     */
    mutationFn: async (decision: 'APPROVED' | 'REJECTED') => applicationApi.decide(applicationId as string, {
      decision,
      reason: decision === 'REJECTED' ? reason.trim() : null,
    }),
    onSuccess: (updated, decision) => onDecided(updated.applicationCode, decision),
    onError: (error) => {
      // The dialog stays open on a refusal: the reader needs to read the sentence, and the pending
      // checks are usually what it is about.
      setIntent(null)
      setFailure(decisionFailure(error))
    },
  })

  const application = detailQuery.data ?? null
  const open = Boolean(applicationId)
  const decidable = application !== null && OPEN_STATUSES.includes(application.status)
  const blocked = application !== null && application.outstandingChecks > 0

  const beginRejection = () => {
    setFailure(null)
    if (reason.trim() === '') {
      setRejectMode(true)
      setFailure('A rejection records why. Give the reason before confirming it.')
      return
    }
    setIntent('REJECTED')
  }

  return (
    <>
      <Dialog
        open={open}
        onClose={onClose}
        title={application ? `Application ${application.applicationCode}` : 'Application'}
        description={application
          ? `${application.applicantName} · ${application.propertyName ?? 'property not recorded'}${application.unitNumber ? ` · ${application.unitNumber}` : ''}`
          : 'Reading the application…'}
        width="lg"
        footer={
          <>
            <Button variant="ghost" onClick={onClose} disabled={decide.isPending}>Close</Button>
            {decidable && (
              <>
                {/* A rejection is gated on its reason before it can be confirmed. */}
                {rejectMode && (
                  <Button
                    variant="outline"
                    onClick={beginRejection}
                    disabled={decide.isPending}
                  >
                    Continue the rejection
                  </Button>
                )}
                {!rejectMode && (
                  <Button variant="ghost" onClick={() => { setRejectMode(true); setFailure(null) }}>
                    Reject…
                  </Button>
                )}
                <Button
                  variant="primary"
                  onClick={() => { setFailure(null); setIntent('APPROVED') }}
                  disabled={decide.isPending}
                >
                  Approve…
                </Button>
              </>
            )}
          </>
        }
      >
        {detailQuery.isLoading ? (
          <Skeleton rows={6} />
        ) : detailQuery.isError ? (
          <ErrorState
            message={messageOf(detailQuery.error)}
            onRetry={() => void detailQuery.refetch()}
          />
        ) : application === null ? (
          <EmptyState
            title="The application's record came back empty"
            description="The detail read returned no payload, so nothing is shown rather than a blank dossier."
          />
        ) : (
          <>
            <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
              <DetailItem label="Status" value={<StatusBadge status={application.status} />} />
              <DetailItem label="Applicant type" value={humanize(application.applicantType)} />
              <DetailItem label="Submitting lead" value={application.leadName ?? 'Taken at the desk'} />
              <DetailItem label="Email" value={application.applicantEmail ?? '—'} />
              <DetailItem label="Phone" value={application.applicantPhone ?? '—'} />
              <DetailItem label="Employment" value={humanize(application.employmentType)} />
              <DetailItem label="Annual income" value={money(application.annualIncome)} />
              <DetailItem label="Occupancy" value={application.occupancyCount === null ? '—' : `${fmtCount(application.occupancyCount)} people`} />
              <DetailItem label="Pets" value={application.hasPets ? 'Yes' : 'No'} />
              <DetailItem label="Proposed rent" value={money(application.proposedRent)} />
              <DetailItem label="Proposed deposit" value={money(application.proposedDeposit)} />
              <DetailItem
                label="Proposed term"
                value={`${date(application.proposedStartDate)} → ${date(application.proposedEndDate)}`}
              />
              <DetailItem label="Submitted" value={dateTime(application.submittedAt)} />
              <DetailItem label="Decided" value={dateTime(application.decidedAt)} />
              <DetailItem label="Last changed" value={dateTime(application.updatedAt)} />
            </div>

            {application.notes && (
              <div className="mt-4 border-t pt-3" style={{ borderColor: 'var(--border)' }}>
                <p className="eyebrow" style={{ color: 'var(--text-secondary)' }}>Notes</p>
                <p className="mt-1 text-sm" style={{ color: 'var(--text-primary)' }}>{application.notes}</p>
              </div>
            )}

            {(application.rejectionReason || application.decisionNotes) && (
              <div className="mt-4 border-t pt-3" style={{ borderColor: 'var(--border)' }}>
                <p className="eyebrow" style={{ color: 'var(--text-secondary)' }}>Decision</p>
                {application.rejectionReason && (
                  <p className="mt-1 text-sm" style={{ color: 'var(--text-primary)' }}>
                    Rejected: {application.rejectionReason}
                  </p>
                )}
                {application.decisionNotes && (
                  <p className="mt-1 text-sm" style={{ color: 'var(--text-secondary)' }}>{application.decisionNotes}</p>
                )}
              </div>
            )}

            {/* ── The screening checks: the gate on the decision ─────────── */}
            <div className="mt-5">
              <div className="mb-2 flex flex-wrap items-center justify-between gap-2">
                <h3 className="flex items-center gap-2 text-sm font-semibold" style={{ color: 'var(--text-primary)' }}>
                  <ShieldCheck size={15} aria-hidden="true" style={{ color: 'var(--ink-teal)' }} />
                  Screening checks
                </h3>
                {blocked ? (
                  <Badge tone="amber">{fmtCount(application.outstandingChecks)} still pending</Badge>
                ) : application.screenings.length > 0 ? (
                  <Badge tone="emerald">Every check is finished</Badge>
                ) : (
                  <Badge tone="neutral">No check recorded</Badge>
                )}
              </div>
              {application.screenings.length === 0 ? (
                <EmptyState
                  title="No screening check has been recorded on this application"
                  description="A decision is refused while any check is pending — and with no checks recorded there is nothing pending, so this application can be decided. Checks and their results appear here as they are run."
                />
              ) : (
                <ul className="space-y-2">
                  {application.screenings.map((check) => (
                    <li
                      key={check.id}
                      className="flex flex-wrap items-center justify-between gap-2 rounded-[var(--radius-card)] border px-3 py-2"
                      style={{ borderColor: 'var(--border)', background: 'var(--surface-elevated)' }}
                    >
                      <span className="min-w-0">
                        <span className="block text-sm font-medium" style={{ color: 'var(--text-primary)' }}>
                          {humanize(check.checkType)}
                        </span>
                        <span className="block text-[11px]" style={{ color: 'var(--text-secondary)' }}>
                          {check.performedAt ? `run ${dateTime(check.performedAt)}` : 'not run yet'}
                          {check.score !== null ? ` · scored ${check.score}` : ''}
                          {check.evidenceReference ? ` · ${check.evidenceReference}` : ''}
                        </span>
                        {check.notes && (
                          <span className="mt-0.5 block text-xs" style={{ color: 'var(--text-secondary)' }}>{check.notes}</span>
                        )}
                      </span>
                      <StatusBadge status={check.status} />
                    </li>
                  ))}
                </ul>
              )}
            </div>

            {/* ── The decision ───────────────────────────────────────────── */}
            {decidable && (
              <div className="mt-5 border-t pt-3" style={{ borderColor: 'var(--border)' }}>
                <p className="flex items-start gap-2 text-xs" style={{ color: blocked ? 'var(--warning-ink)' : 'var(--text-secondary)' }}>
                  <ClipboardCheck size={14} aria-hidden="true" className="mt-0.5" />
                  {blocked
                    ? 'Every screening check must be finished before a decision can be taken. The decision will be refused until then.'
                    : 'Every screening check is finished, so this application can be decided. Approving or rejecting writes the decision and the decider together.'}
                </p>
                {rejectMode && (
                  <div className="mt-3">
                    <Field
                      label="Rejection reason"
                      htmlFor="application-reject-reason"
                      required
                      hint="The schema requires a reason on a rejection; it is recorded with the decision."
                    >
                      <Textarea
                        id="application-reject-reason"
                        value={reason}
                        onChange={(event) => setReason(event.target.value)}
                      />
                    </Field>
                  </div>
                )}
                {application.rejectionReason && (
                  <p className="mt-2 text-xs" style={{ color: 'var(--text-secondary)' }}>
                    Previously rejected for: {application.rejectionReason}
                  </p>
                )}
              </div>
            )}

            {!decidable && (
              <p className="mt-5 border-t pt-3 text-xs" style={{ borderColor: 'var(--border)', color: 'var(--text-secondary)' }}>
                This application is {humanize(application.status).toLowerCase()}. A decision is taken once
                and is not revised, so no approval or rejection is offered here.
              </p>
            )}

            {/* ── The trail ──────────────────────────────────────────────── */}
            {application.statusHistory.length > 0 && (
              <div className="mt-5 border-t pt-3" style={{ borderColor: 'var(--border)' }}>
                <p className="eyebrow" style={{ color: 'var(--text-secondary)' }}>Status trail</p>
                <ol className="mt-2 space-y-1.5">
                  {application.statusHistory.map((entry) => (
                    <li key={entry.id} className="flex flex-wrap items-baseline justify-between gap-2 text-xs">
                      <span style={{ color: 'var(--text-primary)' }}>
                        {entry.fromStatus ? `${humanize(entry.fromStatus)} → ` : ''}{humanize(entry.toStatus)}
                        {entry.reason ? ` · ${entry.reason}` : ''}
                      </span>
                      <span style={{ color: 'var(--text-secondary)' }}>{dateTime(entry.changedAt)}</span>
                    </li>
                  ))}
                </ol>
              </div>
            )}

            {/* Async outcome, announced rather than only coloured. */}
            <div aria-live="polite" className="mt-3">
              {failure && <p role="alert" className="text-sm" style={{ color: 'var(--danger)' }}>{failure}</p>}
            </div>
          </>
        )}
      </Dialog>

      {application && (
        <ConfirmDialog
          open={intent !== null}
          title={intent === 'APPROVED' ? 'Approve this application?' : 'Reject this application?'}
          description={intent === 'APPROVED'
            ? `Approving ${application.applicationCode} records the decision, the decider and the moment it was taken. It is not revised afterwards.`
            : `Rejecting ${application.applicationCode} records the reason you gave${reason.trim() ? ` — "${reason.trim()}"` : ''}. It is not revised afterwards.`}
          confirmLabel={intent === 'APPROVED' ? 'Approve the application' : 'Reject the application'}
          dangerous={intent === 'REJECTED'}
          busy={decide.isPending}
          onCancel={() => setIntent(null)}
          onConfirm={() => { if (intent) decide.mutate(intent) }}
        />
      )}
    </>
  )
}
