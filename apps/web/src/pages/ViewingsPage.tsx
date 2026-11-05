import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { CalendarCheck, CalendarX, ListFilter, MapPin, User } from 'lucide-react'
import { useMemo, useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import { PageAccessGuard, PageHeader } from '../components/common'
import {
  Badge, Button, DataTable, Dialog, EmptyState, ErrorState, Field, Input, KpiTile, Panel,
  Pagination, Select, Skeleton, StatusBadge, TONE_COLOUR, TONE_INK, TONE_SURFACE, Textarea,
  Toolbar, useToast, type Column,
} from '../components/ui'
import { count as fmtCount, date, dateTime, humanize } from '../components/ui/formatters'
import { agentApi, viewingApi } from '../services'
import { messageOf } from '../lib/errors'
import type { AgentSummary, ViewingSummary } from '../types'

/**
 * The viewing diary.
 *
 * The same appointments, read two ways: a **diary** — an ordered list of what is still ahead of the
 * reader, grouped by day, because a diary is read by date and not by column — and a **register** for
 * everything that has happened, filterable and pageable. Which one is on screen lives in the URL
 * (`?view=diary|register`) so a refresh keeps the reader where they were.
 *
 * Two outcome operations are offered, and both follow the same rule: **a viewing's status and its
 * outcome columns are written together.** `ck_viewings_outcome` refuses a COMPLETED viewing without
 * feedback and a CANCELLED one without a reason, so the forms below cannot be submitted without the
 * half of the state they are there to collect.
 *
 * There is deliberately no control for *confirming* a viewing or for recording a no-show: the service
 * map exposes `complete` and `cancel` for a viewing's outcome and no method for those two operations,
 * and a control that hand-builds a request path it was told not to build is not a control worth having.
 */

const VIEWING_STATUSES = ['SCHEDULED', 'CONFIRMED', 'COMPLETED', 'CANCELLED', 'NO_SHOW']

/** The statuses a viewing can still be worked in, mirroring `ViewingStatus.isOpen()`. */
const OPEN_STATUSES = ['SCHEDULED', 'CONFIRMED']

const SORT_OPTIONS = [
  { value: 'scheduledAt,desc', label: 'Most recent first' },
  { value: 'scheduledAt,asc', label: 'Soonest first' },
  { value: 'status,asc', label: 'Status' },
  { value: 'viewingCode,asc', label: 'Viewing code' },
]

/**
 * The viewing row, as the endpoint answers it.
 *
 * `ViewingDtos.ViewingSummary` names the unit `unitNumber` and carries the prospect's phone, the rating
 * and the completion moment. The shared `ViewingSummary` in `src/types` is declared from that record, so
 * the cells below read its own names and no second spelling is kept here.
 */

/** The hour a viewing starts, for a diary whose day is already in the group heading. */
function timeOf(value: string | null): string {
  if (!value) return '—'
  const parsed = new Date(value)
  if (Number.isNaN(parsed.getTime())) return '—'
  return parsed.toLocaleTimeString('en-GB', { hour: '2-digit', minute: '2-digit' })
}

/**
 * A `YYYY-MM-DD` key for a date, in the reader's own time zone.
 *
 * The stored moment is a wall-clock time with no offset, so "which day is this" must be answered
 * locally — an ISO string built from `toISOString()` would put late-evening appointments on tomorrow's
 * page east of the meridian and yesterday's page west of it.
 */
function localDayKey(value: Date): string {
  const month = String(value.getMonth() + 1).padStart(2, '0')
  const day = String(value.getDate()).padStart(2, '0')
  return `${value.getFullYear()}-${month}-${day}`
}

/** `Today`, `Tomorrow`, or the date, for a diary heading. */
function dayHeading(key: string): string {
  if (key === 'unknown') return 'Date not recorded'
  const today = new Date()
  const tomorrow = new Date(today.getTime() + 86_400_000)
  // Noon, so the label cannot slip a day in a time zone behind UTC.
  const label = date(`${key}T12:00:00`)
  if (key === localDayKey(today)) return `Today · ${label}`
  if (key === localDayKey(tomorrow)) return `Tomorrow · ${label}`
  return label
}

/** The prospect a viewing was booked for, wherever the booking put the name. */
function prospectOf(row: ViewingSummary): string {
  return row.prospectName
    || row.leadName
    || row.buyerName
    || row.tenantName
    || 'Prospect not named'
}

function describeSort(sort: string): string {
  const [field, direction] = sort.split(',')
  return `Sorted by ${humanize(field).toLowerCase()} · ${direction === 'desc' ? 'descending' : 'ascending'}`
}

export default function ViewingsPage() {
  return (
    <PageAccessGuard permission="VIEWING_VIEW">
      <ViewingDiary />
    </PageAccessGuard>
  )
}

function ViewingDiary() {
  const queryClient = useQueryClient()
  const toast = useToast()
  const [params, setParams] = useSearchParams()

  // The view lives in the address bar, so a bookmark of the register opens the register.
  const view: 'diary' | 'register' = params.get('view') === 'register' ? 'register' : 'diary'
  const setView = (next: 'diary' | 'register') => {
    const copy = new URLSearchParams(params)
    copy.set('view', next)
    setParams(copy, { replace: true })
  }

  const [status, setStatus] = useState('')
  const [agentId, setAgentId] = useState('')
  const [from, setFrom] = useState('')
  const [to, setTo] = useState('')
  const [sort, setSort] = useState('scheduledAt,desc')
  const [page, setPage] = useState(0)
  const [size, setSize] = useState(20)

  const [completing, setCompleting] = useState<ViewingSummary | null>(null)
  const [cancelling, setCancelling] = useState<ViewingSummary | null>(null)

  const diaryQuery = useQuery({
    queryKey: ['viewings', 'diary'],
    queryFn: () => viewingApi.diary(),
  })

  const registerQuery = useQuery({
    queryKey: ['viewings', 'register', { status, agentId, from, to, sort, page, size }],
    queryFn: () => viewingApi.list({
      page,
      size,
      sort,
      status: status || undefined,
      agentId: agentId || undefined,
      // The register reads a moment, so a day filter is sent as the start and end of that day.
      from: from ? `${from}T00:00:00` : undefined,
      to: to ? `${to}T23:59:59` : undefined,
    }),
    enabled: view === 'register',
  })

  const agentsQuery = useQuery({
    queryKey: ['agents', 'register'],
    queryFn: () => agentApi.list({ size: 200 }),
    enabled: view === 'register',
  })
  const activeAgents: AgentSummary[] = (agentsQuery.data?.items ?? [])
    .filter((agent) => agent.status === 'ACTIVE')

  const diary = diaryQuery.data ?? []

  /**
   * The diary, ordered by date.
   *
   * The endpoint returns what is ahead of the reader; a diary is *read* by date, so the ordering is
   * imposed here and the appointments are grouped by the day they fall on. A diary sorted by anything
   * else is a register with a different heading.
   */
  const diaryDays = useMemo(() => {
    const sorted = [...diary].sort((left, right) =>
      (left.scheduledAt ?? '').localeCompare(right.scheduledAt ?? ''))
    const days = new Map<string, ViewingSummary[]>()
    for (const viewing of sorted) {
      const key = viewing.scheduledAt ? viewing.scheduledAt.slice(0, 10) : 'unknown'
      const bucket = days.get(key)
      if (bucket) bucket.push(viewing)
      else days.set(key, [viewing])
    }
    return [...days.entries()]
  }, [diary])

  const todayKey = localDayKey(new Date())
  const todayCount = diary.filter((viewing) => (viewing.scheduledAt ?? '').startsWith(todayKey)).length
  const next = diary[0] ?? null

  const columns: Column<ViewingSummary>[] = [
    {
      key: 'when', header: 'When', width: '15%', sortKey: 'scheduledAt',
      render: (row) => (
        <span className="block min-w-0">
          <span className="block tabular-nums" style={{ color: 'var(--text-primary)' }}>{dateTime(row.scheduledAt)}</span>
          <span className="block text-[11px] tabular-nums" style={{ color: 'var(--text-secondary)' }}>
            {row.viewingCode}{row.durationMinutes ? ` · ${fmtCount(row.durationMinutes)} min` : ''}
          </span>
        </span>
      ),
    },
    {
      key: 'stock', header: 'Property / unit', width: '20%', sortKey: 'property',
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
      key: 'prospect', header: 'Prospect', width: '18%',
      render: (row) => (
        <span className="block min-w-0">
          <span className="block truncate" style={{ color: 'var(--text-primary)' }}>{prospectOf(row)}</span>
          <span className="block truncate text-[11px]" style={{ color: 'var(--text-secondary)' }}>
            {row.prospectPhone ?? row.leadName ?? row.buyerName ?? row.tenantName ? 'Booked against a record' : 'No contact recorded'}
          </span>
        </span>
      ),
    },
    {
      key: 'agent', header: 'Agent', width: '13%', sortKey: 'agent',
      render: (row) => row.agentName ?? 'Unassigned',
    },
    {
      key: 'status', header: 'Status', width: '12%', sortKey: 'status',
      render: (row) => <StatusBadge status={row.status} />,
    },
    {
      key: 'outcome', header: 'Outcome', width: '16%', secondary: true,
      render: (row) => (
        <span className="flex flex-wrap items-center gap-1.5">
          {row.interested === true && <Badge tone="emerald">Interested</Badge>}
          {row.interested === false && <Badge tone="terracotta">Not interested</Badge>}
          {row.rating !== null && (
            <span className="text-[11px] tabular-nums" style={{ color: 'var(--text-secondary)' }}>
              rated {row.rating}/5
            </span>
          )}
          {row.interested === null && row.rating === null && (
            <span style={{ color: 'var(--text-secondary)' }}>
              {OPEN_STATUSES.includes(row.status) ? 'Still to be held' : 'No reaction recorded'}
            </span>
          )}
        </span>
      ),
    },
    {
      key: 'actions', header: 'Actions', width: '16%',
      render: (row) => (
        <span className="flex flex-wrap gap-1">
          {OPEN_STATUSES.includes(row.status) ? (
            <>
              <Button size="sm" variant="ghost" onClick={() => setCompleting(row)}>Complete</Button>
              <Button size="sm" variant="ghost" onClick={() => setCancelling(row)}>Cancel</Button>
            </>
          ) : (
            <span className="text-[11px]" style={{ color: 'var(--text-secondary)' }}>
              An outcome has been recorded and is not rewritten.
            </span>
          )}
        </span>
      ),
    },
  ]

  return (
    <>
      <PageHeader
        eyebrow="Diary"
        title="Viewings"
        description="Every appointment the firm has booked, and what came of it. A viewing is completed with its feedback, or cancelled with a reason — the two are written together."
        actions={
          <div
            role="group"
            aria-label="Diary view"
            className="flex items-center gap-1 rounded-[var(--radius-control)] p-1"
            style={{ background: 'var(--surface-sunken)' }}
          >
            {([
              { key: 'diary', label: 'Diary' },
              { key: 'register', label: 'Register' },
            ] as const).map((option) => (
              <button
                key={option.key}
                type="button"
                aria-pressed={view === option.key}
                onClick={() => setView(option.key)}
                className="focus-meridian rounded-[var(--radius-control)] px-3 py-1.5 text-xs font-semibold"
                style={view === option.key
                  ? { background: TONE_SURFACE.forest, color: TONE_INK.forest }
                  : { color: 'var(--text-secondary)' }}
              >
                {option.label}
              </button>
            ))}
          </div>
        }
      />

      {view === 'diary' ? (
        <>
          <div className="mb-5 grid gap-3 sm:grid-cols-3">
            <KpiTile label="Still ahead of you" value={fmtCount(diary.length)} tone="forest"
              caption="Scheduled and confirmed appointments" />
            <KpiTile label="Today" value={fmtCount(todayCount)} tone="copper"
              caption={`Appointments falling on ${date(`${todayKey}T12:00:00`)}`} />
            <KpiTile
              label="Next"
              value={next ? timeOf(next.scheduledAt) : '—'}
              tone="sapphire"
              icon={<CalendarCheck size={15} />}
              caption={next ? `${prospectOf(next)} · ${next.propertyName ?? 'property not recorded'}` : 'Nothing is booked ahead'}
            />
          </div>

          <Panel
            title="What is ahead"
            subtitle="Grouped by day, soonest first"
            tint="teal"
          >
            {diaryQuery.isLoading ? (
              <Skeleton rows={6} />
            ) : diaryQuery.isError ? (
              <ErrorState message={messageOf(diaryQuery.error)} onRetry={() => void diaryQuery.refetch()} />
            ) : diaryDays.length === 0 ? (
              <EmptyState
                icon={<CalendarCheck size={22} />}
                title="Nothing is booked ahead of you"
                description="The diary lists the viewings that are still scheduled or confirmed from now on. Booking an appointment against a property — or against a lead's chosen unit — puts it at the top of this page."
              />
            ) : (
              <ol className="space-y-5">
                {diaryDays.map(([key, appointments]) => (
                  <li key={key}>
                    <div className="mb-2 flex items-center gap-3">
                      <h3 className="text-xs font-bold uppercase tracking-wide" style={{ color: TONE_INK.sandstone }}>
                        {dayHeading(key)}
                      </h3>
                      <span className="text-[11px] tabular-nums" style={{ color: 'var(--text-secondary)' }}>
                        {fmtCount(appointments.length)} appointment(s)
                      </span>
                    </div>
                    <ul className="space-y-2">
                      {appointments.map((viewing) => (
                        <li
                          key={viewing.id}
                          className="flex flex-wrap items-center justify-between gap-3 rounded-[var(--radius-card)] border px-3 py-2.5"
                          style={{ borderColor: 'var(--border)', background: 'var(--surface-elevated)' }}
                        >
                          <span className="flex min-w-0 items-start gap-3">
                            {/* The time is the diary's index, so it leads the entry. */}
                            <span
                              className="shrink-0 rounded-[var(--radius-control)] px-2 py-1 text-xs font-semibold tabular-nums"
                              style={{ background: TONE_SURFACE.copper, color: TONE_INK.copper }}
                            >
                              {timeOf(viewing.scheduledAt)}
                            </span>
                            <span className="min-w-0">
                              <span className="block truncate text-sm font-medium" style={{ color: 'var(--text-primary)' }}>
                                {prospectOf(viewing)}
                              </span>
                              <span className="mt-0.5 flex flex-wrap items-center gap-x-2 gap-y-0.5 text-[11px]" style={{ color: 'var(--text-secondary)' }}>
                                <span className="inline-flex items-center gap-1">
                                  <MapPin size={11} aria-hidden="true" />
                                  {viewing.propertyName ?? 'Property not recorded'}
                                  {viewing.unitNumber ? ` · ${viewing.unitNumber}` : ''}
                                </span>
                                <span className="inline-flex items-center gap-1">
                                  <User size={11} aria-hidden="true" />
                                  {viewing.agentName ?? 'Unassigned'}
                                </span>
                                <span className="tabular-nums">{viewing.viewingCode}</span>
                              </span>
                            </span>
                          </span>
                          <span className="flex items-center gap-2">
                            <StatusBadge status={viewing.status} />
                            <Button size="sm" onClick={() => setCompleting(viewing)}>Complete</Button>
                            <Button size="sm" variant="ghost" onClick={() => setCancelling(viewing)}>Cancel</Button>
                          </span>
                        </li>
                      ))}
                    </ul>
                  </li>
                ))}
              </ol>
            )}
          </Panel>
        </>
      ) : (
        <>
          <Panel className="mb-4" bodyClassName="p-0">
            <Toolbar>
              <div className="w-full sm:w-40">
                <Field label="Status" htmlFor="viewing-status">
                  <Select id="viewing-status" value={status} onChange={(event) => { setStatus(event.target.value); setPage(0) }}>
                    <option value="">Every status</option>
                    {VIEWING_STATUSES.map((each) => <option key={each} value={each}>{humanize(each)}</option>)}
                  </Select>
                </Field>
              </div>
              <div className="w-full sm:w-52">
                <Field label="Agent" htmlFor="viewing-agent" hint={agentsQuery.isError ? 'Agent list unavailable' : undefined}>
                  <Select
                    id="viewing-agent"
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
              <div className="w-full sm:w-40">
                <Field label="From" htmlFor="viewing-from">
                  <Input id="viewing-from" type="date" value={from} onChange={(event) => { setFrom(event.target.value); setPage(0) }} />
                </Field>
              </div>
              <div className="w-full sm:w-40">
                <Field label="To" htmlFor="viewing-to">
                  <Input id="viewing-to" type="date" value={to} onChange={(event) => { setTo(event.target.value); setPage(0) }} />
                </Field>
              </div>
              <div className="w-full sm:w-44">
                <Field label="Sort" htmlFor="viewing-sort">
                  <Select id="viewing-sort" value={sort} onChange={(event) => setSort(event.target.value)}>
                    {!SORT_OPTIONS.some((option) => option.value === sort) && (
                      <option value={sort}>{describeSort(sort)}</option>
                    )}
                    {SORT_OPTIONS.map((option) => (
                      <option key={option.value} value={option.value}>{option.label}</option>
                    ))}
                  </Select>
                </Field>
              </div>
            </Toolbar>
            <p className="px-4 py-2 text-xs" aria-live="polite" style={{ color: 'var(--text-secondary)' }}>
              {registerQuery.isLoading
                ? 'Reading the register…'
                : `${fmtCount(registerQuery.data?.totalElements ?? 0)} viewing(s) match these filters.`}
            </p>
          </Panel>

          {registerQuery.isLoading ? (
            <Panel><Skeleton rows={6} /></Panel>
          ) : registerQuery.isError ? (
            <ErrorState message={messageOf(registerQuery.error)} onRetry={() => void registerQuery.refetch()} />
          ) : (
            <Panel bodyClassName="p-0">
              <DataTable
                columns={columns}
                rows={registerQuery.data?.items ?? []}
                keyOf={(row) => row.id}
                sort={sort}
                onSortChange={setSort}
                caption="Viewing register"
                empty={
                  <EmptyState
                    icon={<ListFilter size={22} />}
                    title={status || agentId || from || to ? 'No viewings match these filters' : 'No viewing has ever been booked'}
                    description={
                      status || agentId || from || to
                        ? 'The date range, status or agent is narrowing the register to nothing. Widen the dates or clear the status and the viewings will come back.'
                        : 'A viewing is booked against a property, or a unit within it, for a lead, a buyer or a tenant. None has been booked yet.'
                    }
                  />
                }
              />
              {(registerQuery.data?.items.length ?? 0) > 0 && (
                <Pagination
                  page={registerQuery.data?.page ?? 0}
                  size={registerQuery.data?.size ?? size}
                  totalElements={registerQuery.data?.totalElements ?? 0}
                  totalPages={registerQuery.data?.totalPages ?? 0}
                  hasNext={registerQuery.data?.hasNext ?? false}
                  onPageChange={setPage}
                  onSizeChange={(next) => { setSize(next); setPage(0) }}
                />
              )}
            </Panel>
          )}
        </>
      )}

      <CompleteViewingDialog
        key={completing?.id ?? 'no-complete'}
        viewing={completing}
        onClose={() => setCompleting(null)}
        onCompleted={(code, interested) => {
          void queryClient.invalidateQueries({ queryKey: ['viewings'] })
          toast.push(`${code} was completed${interested === true ? ' — the prospect is interested' : interested === false ? ' — no interest recorded' : ''}.`)
          setCompleting(null)
        }}
      />

      <CancelViewingDialog
        key={cancelling?.id ?? 'no-cancel'}
        viewing={cancelling}
        onClose={() => setCancelling(null)}
        onCancelled={(code) => {
          void queryClient.invalidateQueries({ queryKey: ['viewings'] })
          toast.push(`${code} was cancelled.`, 'terracotta')
          setCancelling(null)
        }}
      />
    </>
  )
}

/**
 * Complete a viewing.
 *
 * Feedback is required, not optional: `ViewingDtos.CompleteViewingRequest.feedback` is annotated
 * `@NotBlank` because `ck_viewings_outcome` refuses a COMPLETED row without it, and
 * `ViewingService.complete` refuses one too. The form collects the whole outcome — feedback, rating
 * and whether the prospect was interested — because the status and those columns are one write.
 */
function CompleteViewingDialog({
  viewing, onClose, onCompleted,
}: {
  viewing: ViewingSummary | null
  onClose: () => void
  onCompleted: (viewingCode: string, interested: boolean | null) => void
}) {
  const [feedback, setFeedback] = useState('')
  const [rating, setRating] = useState('')
  const [interested, setInterested] = useState('')
  const [failure, setFailure] = useState<string | null>(null)

  const complete = useMutation({
    mutationFn: async () => viewingApi.complete(viewing?.id ?? '', {
      feedback: feedback.trim(),
      rating: rating === '' ? null : Number(rating),
      interested: interested === '' ? null : interested === 'true',
    }),
    onSuccess: (updated) => onCompleted(
      updated.viewingCode ?? viewing?.viewingCode ?? 'The viewing',
      interested === '' ? null : interested === 'true',
    ),
    onError: (error) => setFailure(messageOf(error)),
  })

  const submit = () => {
    setFailure(null)
    if (feedback.trim() === '') {
      setFailure('A completed viewing must carry feedback — the database refuses the row without it.')
      return
    }
    complete.mutate()
  }

  return (
    <Dialog
      open={viewing !== null}
      onClose={onClose}
      title={viewing ? `Complete ${viewing.viewingCode}` : 'Complete a viewing'}
      description="The status and its outcome are written together, so the feedback is what makes this a record rather than a gap."
      width="md"
      footer={
        <>
          <Button variant="ghost" onClick={onClose} disabled={complete.isPending}>Cancel</Button>
          <Button variant="primary" onClick={submit} loading={complete.isPending}>Record the outcome</Button>
        </>
      }
    >
      <div className="space-y-3">
        <p className="text-xs" style={{ color: 'var(--text-secondary)' }}>
          {viewing ? `${prospectOf(viewing)} at ${viewing.propertyName ?? 'the property'}${viewing.unitNumber ? ` · ${viewing.unitNumber}` : ''} — ${dateTime(viewing.scheduledAt)}` : ''}
        </p>
        <Field
          label="Feedback"
          htmlFor="viewing-feedback"
          required
          hint="What the prospect made of it, in the desk's own words"
        >
          <Textarea
            id="viewing-feedback"
            value={feedback}
            onChange={(event) => setFeedback(event.target.value)}
          />
        </Field>
        <div className="grid gap-3 sm:grid-cols-2">
          <Field label="Rating" htmlFor="viewing-rating" hint="One to five, if the desk scored it">
            <Select id="viewing-rating" value={rating} onChange={(event) => setRating(event.target.value)}>
              <option value="">Not scored</option>
              {[1, 2, 3, 4, 5].map((value) => <option key={value} value={value}>{value}</option>)}
            </Select>
          </Field>
          <Field label="Interested" htmlFor="viewing-interested">
            <Select id="viewing-interested" value={interested} onChange={(event) => setInterested(event.target.value)}>
              <option value="">Not recorded</option>
              <option value="true">Interested</option>
              <option value="false">Not interested</option>
            </Select>
          </Field>
        </div>
        <div aria-live="polite">
          {failure && <p role="alert" className="text-sm" style={{ color: 'var(--danger)' }}>{failure}</p>}
        </div>
      </div>
    </Dialog>
  )
}

/**
 * Cancel a viewing.
 *
 * A reason is required for the same reason the feedback is: `ck_viewings_outcome` allows a CANCELLED
 * row only with `cancelled_reason` set and no completion time, and the service writes both columns in
 * the one statement.
 */
function CancelViewingDialog({
  viewing, onClose, onCancelled,
}: {
  viewing: ViewingSummary | null
  onClose: () => void
  onCancelled: (viewingCode: string) => void
}) {
  const [reason, setReason] = useState('')
  const [failure, setFailure] = useState<string | null>(null)

  const cancel = useMutation({
    mutationFn: async () => viewingApi.cancel(viewing?.id ?? '', { reason: reason.trim() }),
    onSuccess: (updated) => onCancelled(
      updated.viewingCode ?? viewing?.viewingCode ?? 'The viewing',
    ),
    onError: (error) => setFailure(messageOf(error)),
  })

  const submit = () => {
    setFailure(null)
    if (reason.trim() === '') {
      setFailure('A reason is required to cancel a viewing — the database refuses the row without it.')
      return
    }
    cancel.mutate()
  }

  return (
    <Dialog
      open={viewing !== null}
      onClose={onClose}
      title={viewing ? `Cancel ${viewing.viewingCode}` : 'Cancel a viewing'}
      description="Cancelling records why. It does not delete the appointment: the diary keeps the trail."
      width="sm"
      footer={
        <>
          <Button variant="ghost" onClick={onClose} disabled={cancel.isPending}>Keep the appointment</Button>
          <Button variant="danger" onClick={submit} loading={cancel.isPending}>
            <CalendarX size={14} aria-hidden="true" /> Cancel the viewing
          </Button>
        </>
      }
    >
      <div className="space-y-3">
        <Field
          label="Reason"
          htmlFor="viewing-cancel-reason"
          required
          hint="Recorded on the viewing and on its status trail"
        >
          <Input id="viewing-cancel-reason" value={reason} onChange={(event) => setReason(event.target.value)} />
        </Field>
        <div aria-live="polite">
          {failure && <p role="alert" className="text-sm" style={{ color: 'var(--danger)' }}>{failure}</p>}
        </div>
        <p className="flex items-start gap-2 text-[11px]" style={{ color: 'var(--text-secondary)' }}>
          <span aria-hidden="true" className="mt-1 inline-block h-1.5 w-1.5 rounded-full" style={{ background: TONE_COLOUR.terracotta }} />
          A no-show is a distinct outcome from a cancellation, and neither this build's service map nor
          this dialog treats one as the other.
        </p>
      </div>
    </Dialog>
  )
}
