import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { ArrowLeft, Coins, FileText, Phone, UserPlus } from 'lucide-react'
import { useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { PageHeader } from '../components/common'
import {
  Badge, Button, DetailItem, Dialog, EmptyState, ErrorState, Field, Input, KpiTile, Panel,
  SectionHeader, Select, Skeleton, StatusBadge, TONE_COLOUR, TONE_INK, TONE_SURFACE, useToast,
} from '../components/ui'
import {
  count as fmtCount, date, dateTime, humanize, money, moneyCompact, relative,
} from '../components/ui/formatters'
import { agentApi, leadApi } from '../services'
import { ApiError, messageOf } from '../lib/errors'
import type { AgentSummary, LeadDetail } from '../types'

/**
 * One lead, whole.
 *
 * The screen is built from exactly what `leadApi.detail` returns — `LeadDtos.LeadDetail` carries the
 * lead, its **assignments, activities, follow-ups, viewings and applications**, and its
 * `allowedTransitions`. There is deliberately no panel for offers or for correspondence: that
 * projection returns no such collection, and an empty panel for a collection the endpoint never sends
 * would read as "there have been none" when the truth is "this is not where they are read from".
 *
 * The stage is the one thing here that is a *sequence* rather than a field, so it is treated as one:
 * the change-stage dialog offers the intersection of the record's own `allowedTransitions` and the
 * rule transcribed from `LeadService.ALLOWED` below. The interface therefore cannot put a move in
 * front of the reader that the pipeline will refuse — and if it refuses one anyway (because the
 * record moved under the reader), the API's own sentence is shown rather than a generic failure.
 */

/** The permitted stage moves, transcribed from `LeadService.ALLOWED`. WON and LOST are terminal. */
const LEAD_ALLOWED: Record<string, string[]> = {
  NEW: ['CONTACTED', 'LOST'],
  CONTACTED: ['QUALIFIED', 'LOST'],
  QUALIFIED: ['VIEWING', 'NEGOTIATION', 'LOST'],
  VIEWING: ['NEGOTIATION', 'WON', 'LOST'],
  NEGOTIATION: ['WON', 'LOST'],
  WON: [],
  LOST: [],
}

/* --------------------------------------------------------------------------
 * `leadApi.detail` answers `LeadDtos.LeadDetail`.
 *
 * That record - the lead, its assignments, activities, follow-ups, viewings and applications, and its
 * `allowedTransitions` - is declared in full in `src/types` from the DTO itself, so this screen reads
 * it by name rather than asserting a shape the client cannot know.
 * -------------------------------------------------------------------------- */

export default function LeadDetailPage() {
  const { leadId } = useParams<{ leadId: string }>()
  const queryClient = useQueryClient()
  const toast = useToast()

  const [changingStage, setChangingStage] = useState(false)
  const [assigning, setAssigning] = useState(false)

  const detailQuery = useQuery({
    queryKey: ['leads', 'detail', leadId],
    queryFn: () => leadApi.detail(leadId as string),
    enabled: Boolean(leadId),
  })

  const agentsQuery = useQuery({
    queryKey: ['agents', 'register'],
    queryFn: () => agentApi.list({ size: 200 }),
    enabled: assigning,
  })

  const activeAgents: AgentSummary[] = (agentsQuery.data?.items ?? [])
    .filter((agent) => agent.status === 'ACTIVE')

  if (detailQuery.isLoading) {
    return (
      <>
        <PageHeader title="Lead" description="Reading the lead's record…" />
        <Skeleton rows={6} />
      </>
    )
  }

  if (detailQuery.isError) {
    // A lead that is not there is not a failure — it is an answer. It gets the empty state, with the
    // record's own address named, rather than an error the reader can only retry.
    const missing = detailQuery.error instanceof ApiError && detailQuery.error.status === 404
    if (missing) {
      return (
        <>
          <PageHeader title="Lead not found" eyebrow={leadId} />
          <Panel>
            <EmptyState
              icon={<FileText size={22} />}
              title="This lead is not on the books"
              description="No lead carries that reference. It may have been removed, or the link may be mistyped — the register lists every lead the firm holds."
              action={
                <Link to="/leads" className="focus-meridian btn btn-outline">
                  Back to the lead register
                </Link>
              }
            />
          </Panel>
        </>
      )
    }
    return (
      <>
        <PageHeader title="Lead" />
        <ErrorState message={messageOf(detailQuery.error)} onRetry={() => void detailQuery.refetch()} />
      </>
    )
  }

  const lead = detailQuery.data
  if (!lead) {
    return (
      <>
        <PageHeader title="Lead" />
        <EmptyState
          title="The lead's record came back empty"
          description="The detail read returned no payload, so nothing is rendered rather than a blank dossier."
        />
      </>
    )
  }

  // The intersection of the record's own legal moves and the rule transcribed from LeadService: the
  // UI cannot offer a move either source forbids.
  const mirrored = LEAD_ALLOWED[lead.stage] ?? []
  const allowedMoves = lead.allowedTransitions && lead.allowedTransitions.length > 0
    ? mirrored.filter((move) => lead.allowedTransitions.includes(move))
    : mirrored

  const budget = lead.minBudget === null && lead.maxBudget === null
    ? '—'
    : `${moneyCompact(lead.minBudget)} – ${moneyCompact(lead.maxBudget)}`

  const terminal = allowedMoves.length === 0
  const lockedReason = terminal
    ? `A lead at ${humanize(lead.stage)} has reached the end of the pipeline and cannot move again.`
    : 'Every move the pipeline permits from here is listed.'

  return (
    <>
      <PageHeader
        eyebrow={lead.leadCode}
        title={lead.displayName}
        description={[
          lead.companyName,
          `${humanize(lead.leadType)} enquiry`,
          lead.preferredCity ? `looking in ${lead.preferredCity}` : null,
          lead.ownerAgentName ? `held by ${lead.ownerAgentName}` : 'unassigned',
        ].filter(Boolean).join(' · ')}
        actions={
          <>
            <Link
              to="/leads"
              className="focus-meridian btn btn-ghost"
              style={{ color: 'var(--text-secondary)' }}
            >
              <ArrowLeft size={14} aria-hidden="true" /> Register
            </Link>
            <Button onClick={() => setAssigning(true)}>
              <UserPlus size={14} aria-hidden="true" /> {lead.ownerAgentName ? 'Reassign' : 'Assign agent'}
            </Button>
            <Button
              variant="primary"
              onClick={() => setChangingStage(true)}
              disabled={terminal}
              title={terminal ? lockedReason : undefined}
            >
              Change stage
            </Button>
          </>
        }
      />

      {/* ── The four figures a desk reads first ────────────────────────────── */}
      <div className="mb-5 grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
        <KpiTile
          label="Stage"
          value={<StatusBadge status={lead.stage} />}
          tone={lead.stage === 'WON' ? 'emerald' : lead.stage === 'LOST' ? 'terracotta' : 'sapphire'}
          caption={lead.wonAt
            ? `Won ${date(lead.wonAt)}`
            : lead.lostAt
              ? `Lost ${date(lead.lostAt)}${lead.lostReason ? ` — ${lead.lostReason}` : ''}`
              : `${fmtCount(allowedMoves.length)} possible next move(s)`}
        />
        <KpiTile label="Priority" value={<StatusBadge status={lead.priority} />} tone="copper"
          caption={lead.nextActionAt ? `Next action ${relative(lead.nextActionAt)}` : 'No next action is set'} />
        <KpiTile label="Budget" value={budget} tone="forest"
          caption={lead.minBedrooms === null ? 'Bedrooms not specified' : `${lead.minBedrooms}+ bedrooms`} />
        <KpiTile label="Expected value" value={moneyCompact(lead.expectedValue)} tone="champagne"
          icon={<Coins size={15} />}
          caption="What the lead is realistically worth — the figure the pipeline sums" />
      </div>

      {/* ── Requirements and contact ──────────────────────────────────────── */}
      <SectionHeader
        eyebrow="The brief"
        title="What this lead is looking for"
        rule="copper"
        description="The requirement, the contact details and the outcome columns the stage writes."
      />
      <div className="mb-6 grid gap-4 lg:grid-cols-[1.4fr_1fr]">
        <Panel title="Requirement" tint="sandstone" notch>
          <dl className="grid gap-3 sm:grid-cols-2">
            <DetailItem label="Type" value={humanize(lead.leadType)} />
            <DetailItem label="Source" value={lead.sourceName ?? lead.sourceCode ?? '—'} />
            <DetailItem label="Property type" value={lead.propertyTypeCode ?? '—'} />
            <DetailItem label="Preferred city" value={lead.preferredCity ?? '—'} />
            <DetailItem label="Preferred locality" value={lead.preferredLocality ?? '—'} />
            <DetailItem label="Minimum bedrooms" value={lead.minBedrooms === null ? '—' : fmtCount(lead.minBedrooms)} />
            <DetailItem label="Budget" value={`${money(lead.minBudget)} to ${money(lead.maxBudget)}`} />
            <DetailItem label="Expected value" value={money(lead.expectedValue)} />
            <DetailItem label="Office" value={lead.officeName ?? '—'} />
            <DetailItem label="Next action" value={lead.nextAction ?? '—'} />
            <DetailItem label="Next action due" value={dateTime(lead.nextActionAt)} />
            <DetailItem label="Recorded" value={dateTime(lead.createdAt)} />
          </dl>
          {lead.requirementNotes && (
            <div className="mt-4 border-t pt-3" style={{ borderColor: 'var(--border)' }}>
              <p className="eyebrow" style={{ color: 'var(--text-secondary)' }}>Notes</p>
              <p className="mt-1 text-sm" style={{ color: 'var(--text-primary)' }}>{lead.requirementNotes}</p>
            </div>
          )}
          {(lead.wonAt || lead.lostAt || lead.convertedAt) && (
            <div className="mt-4 border-t pt-3" style={{ borderColor: 'var(--border)' }}>
              <p className="eyebrow" style={{ color: TONE_INK.copper }}>Outcome</p>
              <ul className="mt-1 space-y-1 text-sm" style={{ color: 'var(--text-primary)' }}>
                {lead.wonAt && <li>Won on {dateTime(lead.wonAt)}</li>}
                {lead.lostAt && <li>Lost on {dateTime(lead.lostAt)}{lead.lostReason ? `: ${lead.lostReason}` : ''}</li>}
                {lead.convertedAt && (
                  <li>
                    Converted on {dateTime(lead.convertedAt)}
                    {lead.convertedTenantId ? ' into a tenancy' : lead.convertedBuyerId ? ' into a buyer' : ''}
                  </li>
                )}
              </ul>
            </div>
          )}
        </Panel>

        <Panel title="Contact" tint="forest">
          <dl className="grid gap-3">
            <DetailItem label="Email" value={lead.email ?? '—'} />
            <DetailItem label="Phone" value={lead.phone ?? '—'} />
            <DetailItem label="Alternate phone" value={lead.alternatePhone ?? '—'} />
            <DetailItem label="Held by" value={lead.ownerAgentName ?? 'Unassigned'} />
            <DetailItem label="Last changed" value={relative(lead.updatedAt)} />
          </dl>
          <p className="mt-4 flex items-start gap-2 border-t pt-3 text-[11px]" style={{ borderColor: 'var(--border)', color: 'var(--text-secondary)' }}>
            <Phone size={12} aria-hidden="true" className="mt-0.5" />
            Correspondence is read from the communication register, not from the lead's own projection, so
            no thread is shown here.
          </p>
        </Panel>
      </div>

      {/* ── Activities and follow-ups ─────────────────────────────────────── */}
      <SectionHeader
        eyebrow="The work"
        title="Activity and follow-ups"
        rule="teal"
        description="What has been done against the lead, and what is planned next."
      />
      <div className="mb-6 grid gap-4 lg:grid-cols-2">
        <Panel
          title="Activities"
          subtitle={`${fmtCount(lead.activities.length)} recorded`}
          tint="teal"
        >
          {lead.activities.length === 0 ? (
            <EmptyState
              title="Nothing has been logged against this lead yet"
              description="Moving the lead's stage writes an entry here automatically, and a call or an email logged from the desk is recorded alongside it."
            />
          ) : (
            <ol className="space-y-3">
              {lead.activities.map((activity) => (
                <li
                  key={activity.id}
                  className="border-l-2 pl-3"
                  style={{ borderColor: TONE_COLOUR[activity.activityType === 'STAGE_CHANGE' ? 'copper' : 'teal'] }}
                >
                  <div className="flex flex-wrap items-baseline justify-between gap-2">
                    <span className="text-sm font-medium" style={{ color: 'var(--text-primary)' }}>
                      {activity.subject ?? humanize(activity.activityType)}
                    </span>
                    <span className="text-[11px]" style={{ color: 'var(--text-secondary)' }}>
                      {relative(activity.occurredAt)}
                    </span>
                  </div>
                  <div className="mt-1 flex flex-wrap items-center gap-2">
                    <Badge tone="teal">{humanize(activity.activityType)}</Badge>
                    {activity.fromStage && activity.toStage && (
                      <span className="text-[11px]" style={{ color: 'var(--text-secondary)' }}>
                        {humanize(activity.fromStage)} → {humanize(activity.toStage)}
                      </span>
                    )}
                    {activity.durationMinutes !== null && (
                      <span className="text-[11px]" style={{ color: 'var(--text-secondary)' }}>
                        {fmtCount(activity.durationMinutes)} minutes
                      </span>
                    )}
                  </div>
                  {activity.notes && (
                    <p className="mt-1 text-xs" style={{ color: 'var(--text-secondary)' }}>{activity.notes}</p>
                  )}
                </li>
              ))}
            </ol>
          )}
        </Panel>

        <Panel
          title="Follow-ups"
          subtitle={`${fmtCount(lead.followups.length)} planned, soonest first`}
          tint="amber"
        >
          {lead.followups.length === 0 ? (
            <EmptyState
              title="No follow-up is planned against this lead"
              description="A planned follow-up carries the date the next contact is due and the channel it will be made on, and it moves the lead's next action forward."
            />
          ) : (
            <ul className="space-y-2">
              {lead.followups.map((followup) => (
                <li
                  key={followup.id}
                  className="rounded-[var(--radius-card)] border px-3 py-2"
                  style={{ borderColor: 'var(--border)', background: 'var(--surface-elevated)' }}
                >
                  <div className="flex flex-wrap items-baseline justify-between gap-2">
                    <span className="text-sm font-medium" style={{ color: 'var(--text-primary)' }}>
                      {dateTime(followup.dueAt)}
                    </span>
                    <span className="flex items-center gap-1.5">
                      <StatusBadge status={followup.status} />
                      {/* Overdue in words as well as in tone. */}
                      {followup.overdue && <Badge tone="terracotta">Overdue</Badge>}
                    </span>
                  </div>
                  <div className="mt-1 flex flex-wrap items-center gap-2 text-[11px]" style={{ color: 'var(--text-secondary)' }}>
                    <span>{humanize(followup.channel)}</span>
                    {followup.assignedToName && <span>· {followup.assignedToName}</span>}
                    {followup.completedAt && <span>· closed {relative(followup.completedAt)}</span>}
                  </div>
                  {followup.notes && (
                    <p className="mt-1 text-xs" style={{ color: 'var(--text-secondary)' }}>{followup.notes}</p>
                  )}
                  {followup.outcome && (
                    <p className="mt-1 text-xs" style={{ color: 'var(--text-primary)' }}>{followup.outcome}</p>
                  )}
                </li>
              ))}
            </ul>
          )}
        </Panel>
      </div>

      {/* ── Viewings and applications ─────────────────────────────────────── */}
      <SectionHeader
        eyebrow="Progress"
        title="Viewings and applications"
        rule="sapphire"
        description="The appointments held for this lead and the applications that grew out of it."
      />
      <div className="mb-6 grid gap-4 lg:grid-cols-2">
        <Panel title="Viewings" subtitle={`${fmtCount(lead.viewings.length)} booked`} tint="sapphire">
          {lead.viewings.length === 0 ? (
            <EmptyState
              title="No viewing has been booked for this lead"
              description="A viewing booked against this lead — at a property or a unit — is listed here with who showed it and how it went."
            />
          ) : (
            <ul className="space-y-2">
              {lead.viewings.map((viewing) => (
                <li
                  key={viewing.id}
                  className="flex flex-wrap items-center justify-between gap-3 rounded-[var(--radius-card)] border px-3 py-2"
                  style={{ borderColor: 'var(--border)', background: 'var(--surface-elevated)' }}
                >
                  <span className="min-w-0">
                    <span className="block truncate text-sm font-medium" style={{ color: 'var(--text-primary)' }}>
                      {viewing.propertyName ?? 'Property not recorded'}
                      {viewing.unitNumber ? ` · ${viewing.unitNumber}` : ''}
                    </span>
                    <span className="block text-[11px]" style={{ color: 'var(--text-secondary)' }}>
                      {dateTime(viewing.scheduledAt)} · {viewing.agentName ?? 'unassigned'}
                      {viewing.interested === true ? ' · interested' : viewing.interested === false ? ' · not interested' : ''}
                    </span>
                  </span>
                  <StatusBadge status={viewing.status} />
                </li>
              ))}
            </ul>
          )}
        </Panel>

        <Panel title="Applications" subtitle={`${fmtCount(lead.applications.length)} taken`} tint="plum">
          {lead.applications.length === 0 ? (
            <EmptyState
              title="No application has grown out of this lead"
              description="An application taken for the unit this lead settles on is listed here with its screening position."
            />
          ) : (
            <ul className="space-y-2">
              {lead.applications.map((application) => (
                <li
                  key={application.id}
                  className="flex flex-wrap items-center justify-between gap-3 rounded-[var(--radius-card)] border px-3 py-2"
                  style={{ borderColor: 'var(--border)', background: 'var(--surface-elevated)' }}
                >
                  <span className="min-w-0">
                    <span className="block truncate text-sm font-medium" style={{ color: 'var(--text-primary)' }}>
                      {application.applicantName}
                    </span>
                    <span className="block text-[11px] tabular-nums" style={{ color: 'var(--text-secondary)' }}>
                      {application.applicationCode}
                      {' · '}{humanize(application.applicantType)}
                      {' · '}{money(application.proposedRent)}
                      {' · '}{date(application.submittedAt)}
                    </span>
                  </span>
                  <StatusBadge status={application.status} />
                </li>
              ))}
            </ul>
          )}
        </Panel>
      </div>

      {/* ── Who held the lead, and when ───────────────────────────────────── */}
      <SectionHeader
        eyebrow="Ownership"
        title="Assignment history"
        rule="slate"
        description="Every hand the lead has passed through, as the assignment log records it."
      />
      <Panel>
        {lead.assignments.length === 0 ? (
          <EmptyState
            title="The lead has never been assigned"
            description="Assigning the lead to an agent opens the first chapter of this history; reassigning closes that one and opens the next."
          />
        ) : (
          <ol className="space-y-2">
            {lead.assignments.map((assignment) => (
              <li
                key={assignment.id}
                className="flex flex-wrap items-center justify-between gap-3 rounded-[var(--radius-card)] border px-3 py-2"
                style={{
                  borderColor: 'var(--border)',
                  background: assignment.unassignedAt === null ? TONE_SURFACE.forest : 'var(--surface-elevated)',
                }}
              >
                <span className="min-w-0">
                  <span className="block text-sm font-medium" style={{ color: 'var(--text-primary)' }}>
                    {assignment.agentName ?? 'Agent not recorded'}
                  </span>
                  <span className="block text-[11px]" style={{ color: 'var(--text-secondary)' }}>
                    from {dateTime(assignment.assignedAt)}
                    {assignment.unassignedAt ? ` to ${dateTime(assignment.unassignedAt)}` : ' · currently held'}
                    {assignment.reason ? ` · ${assignment.reason}` : ''}
                  </span>
                </span>
                {/* The current holder is said in words, not only by the tinted surface. */}
                <Badge tone={assignment.unassignedAt === null ? 'emerald' : 'slate'}>
                  {assignment.unassignedAt === null ? 'Current holder' : 'Past holder'}
                </Badge>
              </li>
            ))}
          </ol>
        )}
      </Panel>

      <ChangeStageDialog
        open={changingStage}
        lead={lead}
        moves={allowedMoves}
        onClose={() => setChangingStage(false)}
        onChanged={(stage) => {
          void queryClient.invalidateQueries({ queryKey: ['leads'] })
          toast.push(`${lead.displayName} moved to ${humanize(stage)}.`)
          setChangingStage(false)
        }}
      />

      <AssignDialog
        open={assigning}
        lead={lead}
        agents={activeAgents}
        loadingAgents={agentsQuery.isLoading}
        agentsUnavailable={agentsQuery.isError}
        onClose={() => setAssigning(false)}
        onAssigned={(agentName) => {
          void queryClient.invalidateQueries({ queryKey: ['leads'] })
          toast.push(`${lead.displayName} is now held by ${agentName}.`)
          setAssigning(false)
        }}
      />
    </>
  )
}

/**
 * Move the lead between pipeline stages.
 *
 * The target list is the intersection of the record's `allowedTransitions` and `LeadService.ALLOWED`,
 * so an illegal move cannot be selected. Two outcome fields travel with the stage, exactly as the
 * backend writes them: a reason (recorded on the stage-change activity) and, **when the target is
 * LOST, a lost reason** — the pipeline refuses a lost lead without one, and `ck_leads_outcome` would
 * refuse the row anyway.
 */
function ChangeStageDialog({
  open, lead, moves, onClose, onChanged,
}: {
  open: boolean
  lead: LeadDetail
  moves: string[]
  onClose: () => void
  onChanged: (stage: string) => void
}) {
  const [target, setTarget] = useState('')
  const [reason, setReason] = useState('')
  const [lostReason, setLostReason] = useState('')
  const [failure, setFailure] = useState<string | null>(null)

  const chosen = target || moves[0] || ''
  const needsLostReason = chosen === 'LOST'

  const changeStage = useMutation({
    mutationFn: async () => leadApi.changeStage(lead.id, {
      stage: chosen,
      reason: reason.trim() || null,
      // Only sent for a lost lead: the backend refuses a lost reason on any other move.
      lostReason: needsLostReason ? lostReason.trim() : null,
    }),
    onSuccess: (updated) => {
      setTarget('')
      setReason('')
      setLostReason('')
      // `leadApi.changeStage` declares the detail record the endpoint answers, so the stage that came
      // back is read directly.
      onChanged(updated.stage)
    },
    // The pipeline's own refusal — which names the moves that *are* allowed from here — is shown as
    // it stands. Replacing it with "something went wrong" would throw away the only useful sentence.
    onError: (error) => setFailure(messageOf(error)),
  })

  const submit = () => {
    setFailure(null)
    if (!chosen) {
      setFailure('Choose the stage this lead should move to.')
      return
    }
    if (needsLostReason && lostReason.trim() === '') {
      setFailure('A lost lead must record why it was lost.')
      return
    }
    changeStage.mutate()
  }

  return (
    <Dialog
      open={open}
      onClose={onClose}
      title={`Change stage — ${lead.displayName}`}
      description={`Currently ${humanize(lead.stage)}. Only the moves the pipeline permits are listed.`}
      width="sm"
      footer={
        <>
          <Button variant="ghost" onClick={onClose} disabled={changeStage.isPending}>Cancel</Button>
          <Button variant="primary" onClick={submit} loading={changeStage.isPending}>Move the lead</Button>
        </>
      }
    >
      <div className="space-y-3">
        <Field
          label="Move to"
          htmlFor="lead-stage-target"
          required
          hint={moves.length === 0
            ? 'The pipeline permits no move from here.'
            : `Permitted from ${humanize(lead.stage)}: ${moves.map((move) => humanize(move)).join(', ')}.`}
        >
          <Select
            id="lead-stage-target"
            value={chosen}
            disabled={moves.length === 0}
            onChange={(event) => setTarget(event.target.value)}
          >
            {moves.length === 0 && <option value="">No move is permitted</option>}
            {moves.map((move) => <option key={move} value={move}>{humanize(move)}</option>)}
          </Select>
        </Field>

        {needsLostReason && (
          <Field
            label="Why it was lost"
            htmlFor="lead-stage-lost-reason"
            required
            hint="The pipeline refuses a lost lead without a reason."
          >
            <Input
              id="lead-stage-lost-reason"
              value={lostReason}
              onChange={(event) => setLostReason(event.target.value)}
            />
          </Field>
        )}

        <Field label="Reason" htmlFor="lead-stage-reason" hint="Recorded on the activity this move writes">
          <Input id="lead-stage-reason" value={reason} onChange={(event) => setReason(event.target.value)} />
        </Field>

        <div aria-live="polite">
          {failure && <p role="alert" className="text-sm" style={{ color: 'var(--danger)' }}>{failure}</p>}
        </div>
      </div>
    </Dialog>
  )
}

/** Assign or reassign the agent who holds the lead. Only an ACTIVE agent may be given the work. */
function AssignDialog({
  open, lead, agents, loadingAgents, agentsUnavailable, onClose, onAssigned,
}: {
  open: boolean
  lead: LeadDetail
  agents: AgentSummary[]
  loadingAgents: boolean
  agentsUnavailable: boolean
  onClose: () => void
  onAssigned: (agentName: string) => void
}) {
  const [agentId, setAgentId] = useState('')
  const [reason, setReason] = useState('')
  const [failure, setFailure] = useState<string | null>(null)

  const assign = useMutation({
    mutationFn: async () => leadApi.assign(lead.id, {
      agentId,
      reason: reason.trim() || null,
    }),
    onSuccess: () => {
      const name = agents.find((agent) => agent.id === agentId)?.fullName ?? 'the agent'
      setAgentId('')
      setReason('')
      onAssigned(name)
    },
    onError: (error) => setFailure(messageOf(error)),
  })

  const submit = () => {
    setFailure(null)
    if (!agentId) {
      setFailure('Choose the agent who should hold this lead.')
      return
    }
    assign.mutate()
  }

  return (
    <Dialog
      open={open}
      onClose={onClose}
      title={`Assign ${lead.displayName}`}
      description="The current assignment is closed and a new one opened, so the history stays whole."
      width="sm"
      footer={
        <>
          <Button variant="ghost" onClick={onClose} disabled={assign.isPending}>Cancel</Button>
          <Button variant="primary" onClick={submit} loading={assign.isPending}>Assign</Button>
        </>
      }
    >
      <div className="space-y-3">
        <Field
          label="Agent"
          htmlFor="lead-detail-agent"
          required
          hint={agentsUnavailable
            ? 'The agent list could not be read, so no one can be chosen here.'
            : 'Active agents only — work is refused for anyone else.'}
        >
          <Select
            id="lead-detail-agent"
            value={agentId}
            disabled={loadingAgents || agentsUnavailable}
            onChange={(event) => setAgentId(event.target.value)}
          >
            <option value="">{loadingAgents ? 'Reading the agent list…' : 'Choose an agent'}</option>
            {agents.map((agent) => (
              <option key={agent.id} value={agent.id}>
                {/* An agent's open-lead count is `AgentDtos.WorkloadResponse`, read from
                    `agentApi.workload(id)` - it is not on the agent register row, and nothing is
                    invented for it here. */}
                {agent.fullName ?? agent.agentCode}
              </option>
            ))}
          </Select>
        </Field>
        <Field label="Reason" htmlFor="lead-detail-assign-reason" hint="Recorded against the assignment history">
          <Input id="lead-detail-assign-reason" value={reason} onChange={(event) => setReason(event.target.value)} />
        </Field>
        <div aria-live="polite">
          {failure && <p role="alert" className="text-sm" style={{ color: 'var(--danger)' }}>{failure}</p>}
        </div>
      </div>
    </Dialog>
  )
}
