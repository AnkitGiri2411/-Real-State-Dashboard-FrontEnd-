import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { ListFilter, Plus, UserPlus } from 'lucide-react'
import { useMemo, useState } from 'react'
import { Link, useNavigate, useSearchParams } from 'react-router-dom'
import { PageAccessGuard, PageHeader } from '../components/common'
import {
  Badge, Button, DataTable, Dialog, EmptyState, ErrorState, Field, Input, KpiTile, Panel,
  Pagination, Select, Skeleton, StatusBadge, TONE_INK, TONE_SURFACE, Toolbar, useToast,
  type Column,
} from '../components/ui'
import { Funnel } from '../components/ui/charts'
import { count as fmtCount, date, humanize, moneyCompact, relative } from '../components/ui/formatters'
import { agentApi, leadApi, referenceApi } from '../services'
import { messageOf } from '../lib/errors'
import type { AgentSummary, LeadSummary, PipelineResponse } from '../types'

/**
 * The lead register: one book of enquiry, read three ways.
 *
 * The same filtered read is rendered as a **pipeline board** (a kanban, so an agent can see what is
 * where), a **register table** (so the desk can scan and sort it) and a **funnel** drawn from the
 * pipeline read (so the shape of conversion is visible). Which of the two register layouts is on
 * screen lives in the URL (`?view=pipeline|table`) rather than in component state: a refresh, a
 * bookmark or a link pasted to a colleague must land on the view the reader was looking at.
 *
 * This page writes two things: a new lead, and the agent who holds one. It never writes a stage — a
 * lead moves only through `leadApi.changeStage`, validated against the map in `LeadService.ALLOWED`,
 * and that action belongs to the lead's own screen where the record's current stage is known.
 */

/** The pipeline order, mirroring `LeadStage`. */
const STAGES = ['NEW', 'CONTACTED', 'QUALIFIED', 'VIEWING', 'NEGOTIATION', 'WON', 'LOST'] as const
type Stage = (typeof STAGES)[number]

/** The stages a lead is still being worked in — the ones the pipeline value counts. */
const OPEN_STAGES: Stage[] = ['NEW', 'CONTACTED', 'QUALIFIED', 'VIEWING', 'NEGOTIATION']

const LEAD_TYPES = ['RENTAL', 'SALE', 'BOTH'] as const
const LEAD_PRIORITIES = ['LOW', 'MEDIUM', 'HIGH', 'URGENT'] as const

/** The stage order the funnel ranks by, exactly as `LeadService.funnel()` states it. */
const FUNNEL_ORDER: Stage[] = ['NEW', 'CONTACTED', 'QUALIFIED', 'VIEWING', 'NEGOTIATION', 'WON']

/**
 * The register row, as the endpoint answers it.
 *
 * `LeadDtos.LeadSummary` is the whole of it: the source as a code *and* a name, the next action and
 * whether it has come due, and the office. There is nothing to add here and nothing to take away - the
 * shared `LeadSummary` in `src/types` is declared from that record, so this page reads it directly.
 */

const SORT_OPTIONS = [
  { value: 'createdAt,desc', label: 'Newest first' },
  { value: 'createdAt,asc', label: 'Oldest first' },
  { value: 'displayName,asc', label: 'Name A–Z' },
  { value: 'priority,asc', label: 'Priority' },
  { value: 'expectedValue,desc', label: 'Expected value' },
  { value: 'nextActionAt,asc', label: 'Next action due' },
]

/** A readable sentence for a sort the column headers set, which the picker does not list. */
function describeSort(sort: string): string {
  const [field, direction] = sort.split(',')
  return `Sorted by ${humanize(field).toLowerCase()} · ${direction === 'desc' ? 'descending' : 'ascending'}`
}

export default function LeadsPage() {
  return (
    <PageAccessGuard permission="LEAD_VIEW">
      <LeadRegister />
    </PageAccessGuard>
  )
}

function LeadRegister() {
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const toast = useToast()
  const [params, setParams] = useSearchParams()

  // The view is read from the URL, so the address bar is the state and a refresh preserves it.
  const view: 'pipeline' | 'table' = params.get('view') === 'table' ? 'table' : 'pipeline'
  const setView = (next: 'pipeline' | 'table') => {
    const copy = new URLSearchParams(params)
    copy.set('view', next)
    setParams(copy, { replace: true })
  }

  const [page, setPage] = useState(0)
  const [size, setSize] = useState(20)
  const [sort, setSort] = useState('createdAt,desc')
  const [search, setSearch] = useState('')
  const [stage, setStage] = useState('')
  const [priority, setPriority] = useState('')
  const [type, setType] = useState('')
  const [source, setSource] = useState('')
  const [ownerAgentId, setOwnerAgentId] = useState('')

  const [creating, setCreating] = useState(false)
  const [assigning, setAssigning] = useState<LeadSummary | null>(null)

  /*
   * The board is a kanban, not a page of a register: it needs enough cards to be worth reading as a
   * board. It therefore asks for a wider page than the table does, and says so on screen rather than
   * pretending the board is the whole book.
   */
  const boardSize = 100
  const effectiveSize = view === 'pipeline' ? boardSize : size

  const leadsQuery = useQuery({
    queryKey: [
      'leads', 'register', { page, effectiveSize, sort, search, stage, priority, type, source, ownerAgentId },
    ],
    queryFn: () => leadApi.list({
      page,
      size: effectiveSize,
      sort,
      search: search.trim() || undefined,
      stage: stage || undefined,
      priority: priority || undefined,
      type: type || undefined,
      source: source.trim() || undefined,
      ownerAgentId: ownerAgentId || undefined,
    }),
  })

  const pipelineQuery = useQuery({
    queryKey: ['leads', 'pipeline'],
    queryFn: () => leadApi.pipeline(),
  })

  // The agent list is needed twice: as an owner filter, and to assign work. Only an ACTIVE agent may
  // hold a lead (`LeadService.requireActiveAgent`), so an inactive one is never offered.
  const agentsQuery = useQuery({
    queryKey: ['agents', 'register'],
    queryFn: () => agentApi.list({ size: 200 }),
    enabled: assigning !== null || ownerAgentId !== '' || creating,
  })

  const activeAgents: AgentSummary[] = useMemo(
    () => (agentsQuery.data?.items ?? []).filter((agent) => agent.status === 'ACTIVE'),
    [agentsQuery.data],
  )

  const rows = leadsQuery.data?.items ?? []
  // `leadApi.pipeline` declares `LeadDtos.PipelineResponse`, so the stage buckets are read by name.
  const pipeline = pipelineQuery.data

  /** The board's columns, in pipeline order, from the leads the current page returned. */
  const board = useMemo(
    () => STAGES.map((each) => ({ stage: each, cards: rows.filter((row) => row.stage === each) })),
    [rows],
  )

  /**
   * The funnel.
   *
   * `leadApi.pipeline()` is the only pipeline read the service map exposes, and it answers counts per
   * stage. The rungs below are therefore made **cumulative** by the rule `LeadService.funnel()` uses:
   * a rung counts the leads that reached that stage or went past it, so the book can only narrow, and
   * a LOST lead is counted at the top of the funnel only because the stage it fell out from is not
   * recorded. Nothing here is a per-stage count dressed up as a funnel.
   */
  const funnelSteps = useMemo(() => {
    if (!pipeline || pipeline.stages.length === 0) return []
    const rankOf = (value: string): number => {
      const index = FUNNEL_ORDER.indexOf(value as Stage)
      return index < 0 ? 0 : index
    }
    return FUNNEL_ORDER.map((each) => ({
      label: humanize(each),
      value: pipeline.stages.reduce(
        (sum, bucket) => (rankOf(bucket.stage) >= rankOf(each) ? sum + bucket.count : sum),
        0,
      ),
    }))
  }, [pipeline])

  const stageCount = (value: Stage): number =>
    pipeline?.stages.find((bucket) => bucket.stage === value)?.count ?? 0

  const columns: Column<LeadSummary>[] = [
    {
      key: 'lead', header: 'Lead', width: '15%', sortKey: 'displayName',
      render: (row) => (
        <span className="block min-w-0">
          <span className="block truncate font-medium" style={{ color: 'var(--text-primary)' }}>{row.displayName}</span>
          <span className="block truncate text-[11px] tabular-nums" style={{ color: 'var(--text-secondary)' }}>
            {row.leadCode}
          </span>
        </span>
      ),
    },
    {
      key: 'source', header: 'Source', width: '10%', secondary: true, sortKey: 'source',
      render: (row) => row.sourceName ?? row.sourceCode ?? '—',
    },
    {
      key: 'stage', header: 'Stage', width: '10%', sortKey: 'stage',
      render: (row) => <StatusBadge status={row.stage} />,
    },
    {
      key: 'priority', header: 'Priority', width: '9%', sortKey: 'priority',
      render: (row) => <StatusBadge status={row.priority} />,
    },
    /*
     * This column used to be "City", read from a `city` the register row does not carry: the lead's
     * enquiry names a *preferred* city, which is on `LeadDetail`, not on `LeadSummary`. The slot now
     * holds the company, which the register row does send.
     */
    {
      key: 'company', header: 'Company', width: '8%', secondary: true,
      render: (row) => <span className="truncate">{row.companyName ?? '—'}</span>,
    },
    /*
     * And this one used to be "Budget", read from `minBudget`/`maxBudget` - neither of which is on the
     * register row either; both are on `LeadDetail`, because the budget is the enquiry's requirement
     * rather than a column of the pipeline. What the register does promise is the expected value, which
     * is what the pipeline figure sums, so that is what the column shows.
     */
    {
      key: 'expected', header: 'Expected value', width: '14%', align: 'right', sortKey: 'expectedValue',
      render: (row) => (
        <span className="tabular-nums">{row.expectedValue === null ? '—' : moneyCompact(row.expectedValue)}</span>
      ),
    },
    {
      key: 'agent', header: 'Agent', width: '11%', sortKey: 'owner',
      render: (row) => row.ownerAgentName ?? 'Unassigned',
    },
    {
      key: 'next', header: 'Next action', width: '13%', sortKey: 'nextActionAt',
      render: (row) => (
        <span className="block min-w-0">
          <span className="block truncate text-xs" style={{ color: 'var(--text-primary)' }}>
            {row.nextAction ?? '—'}
          </span>
          {row.nextActionAt && (
            <span className="mt-0.5 flex items-center gap-1.5">
              <span className="text-[11px]" style={{ color: 'var(--text-secondary)' }}>
                {relative(row.nextActionAt)}
              </span>
              {/* Overdue is said in words as well as in colour, never by the hue alone. */}
              {row.overdue && <Badge tone="terracotta">Overdue</Badge>}
            </span>
          )}
        </span>
      ),
    },
    {
      key: 'created', header: 'Created', width: '10%', align: 'right', sortKey: 'createdAt', secondary: true,
      render: (row) => date(row.createdAt),
    },
    {
      key: 'actions', header: 'Actions', width: '10%',
      render: (row) => (
        <Button
          size="sm"
          variant="ghost"
          onClick={(event) => {
            // The row opens the lead; this cell does something else, so the row's click must not fire.
            event.stopPropagation()
            setAssigning(row)
          }}
        >
          Assign
        </Button>
      ),
    },
  ]

  const sortIsListed = SORT_OPTIONS.some((option) => option.value === sort)

  return (
    <>
      <PageHeader
        eyebrow="Enquiry pipeline"
        title="Leads"
        description={`Every enquiry the firm is working, from first contact to conversion. ${
          pipeline ? `${fmtCount(pipeline.openLeads)} of ${fmtCount(pipeline.totalLeads)} are still open.` : ''
        }`}
        actions={
          <>
            <div
              role="group"
              aria-label="Register view"
              className="flex items-center gap-1 rounded-[var(--radius-control)] p-1"
              style={{ background: 'var(--surface-sunken)' }}
            >
              {([
                { key: 'pipeline', label: 'Pipeline board' },
                { key: 'table', label: 'Register table' },
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
            <Button variant="primary" onClick={() => setCreating(true)}>
              <Plus size={14} aria-hidden="true" /> Log a lead
            </Button>
          </>
        }
      />

      {pipelineQuery.isLoading ? (
        <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
          {Array.from({ length: 4 }).map((_, index) => <Skeleton key={index} rows={3} />)}
        </div>
      ) : pipelineQuery.isError ? (
        <ErrorState
          message={messageOf(pipelineQuery.error)}
          onRetry={() => void pipelineQuery.refetch()}
        />
      ) : pipeline ? (
        <div className="mb-5 grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
          <KpiTile label="Leads on the books" value={fmtCount(pipeline.totalLeads)} tone="forest"
            caption="Every stage, converted and lost included" />
          <KpiTile label="Still being worked" value={fmtCount(pipeline.openLeads)} tone="sapphire"
            caption="Not yet won or lost" />
          <KpiTile label="Open pipeline value" value={moneyCompact(pipeline.openPipelineValue)} tone="copper"
            caption="Expected value of the leads still open" />
          <KpiTile label="Converted" value={fmtCount(stageCount('WON'))} tone="emerald"
            caption="Leads that became a tenancy or a purchase" />
        </div>
      ) : (
        <EmptyState
          title="The pipeline could not be summarised"
          description="The pipeline read returned nothing to count. Log a lead and the counts, the funnel and the board will fill in."
        />
      )}

      {/* ── The funnel: the same book, described as conversion ─────────────── */}
      <div className="mb-5 grid gap-4 xl:grid-cols-[1.6fr_1fr]">
        <Panel
          title="Pipeline"
          subtitle="Leads at each stage, read live from the pipeline endpoint"
          tint="forest"
        >
          {pipelineQuery.isLoading ? (
            <Skeleton rows={6} />
          ) : funnelSteps.length === 0 ? (
            <EmptyState
              title="No leads to funnel yet"
              description="The funnel appears as soon as a lead is logged — every rung counts the leads that reached that stage or went past it."
            />
          ) : (
            <Funnel steps={funnelSteps} title="Leads that reached each stage" />
          )}
          <ul className="mt-4 grid grid-cols-2 gap-2 border-t pt-3 sm:grid-cols-4" style={{ borderColor: 'var(--border)' }}>
            {OPEN_STAGES.map((each) => (
              <li key={each} className="flex items-baseline justify-between gap-2">
                <span className="text-[11px]" style={{ color: 'var(--text-secondary)' }}>{humanize(each)}</span>
                <span className="text-xs font-semibold tabular-nums" style={{ color: 'var(--text-primary)' }}>
                  {fmtCount(stageCount(each))}
                </span>
              </li>
            ))}
          </ul>
        </Panel>

        <Panel
          title="Where the book stands"
          subtitle={pipeline ? `Read at ${relative(pipeline.generatedAt)}` : 'Read live'}
          tint="champagne"
        >
          {pipelineQuery.isLoading ? (
            <Skeleton rows={6} />
          ) : (
            <ul className="space-y-2">
              {STAGES.map((each) => {
                const total = opensTotal(pipeline)
                const value = stageCount(each)
                const share = total > 0 ? Math.round((value / total) * 100) : 0
                const tone = stageTone(each)
                return (
                  <li key={each}>
                    <div className="flex items-baseline justify-between gap-3">
                      <span className="text-xs font-medium" style={{ color: 'var(--text-primary)' }}>{humanize(each)}</span>
                      <span className="text-xs tabular-nums" style={{ color: 'var(--text-secondary)' }}>
                        {fmtCount(value)} · {share}%
                      </span>
                    </div>
                    <div className="mt-1 h-2 w-full overflow-hidden rounded-[2px]" style={{ background: TONE_SURFACE[tone] }}>
                      <div className="h-full" style={{ width: `${Math.max(share, value > 0 ? 3 : 0)}%`, background: TONE_INK[tone] }} />
                    </div>
                  </li>
                )
              })}
            </ul>
          )}
          <p className="mt-3 border-t pt-3 text-[11px]" style={{ borderColor: 'var(--border)', color: 'var(--text-secondary)' }}>
            Counts cover the whole book, not the filters below — the board and the table are what the
            filters narrow.
          </p>
        </Panel>
      </div>

      {/* ── Filters ───────────────────────────────────────────────────────── */}
      <Panel className="mb-4" bodyClassName="p-0">
        <Toolbar>
          <div className="w-full sm:w-52">
            <Field label="Search" htmlFor="lead-search" hint="Name, code, company, email, phone or city">
              <Input
                id="lead-search"
                type="search"
                value={search}
                placeholder="Search the book"
                onChange={(event) => { setSearch(event.target.value); setPage(0) }}
              />
            </Field>
          </div>
          <div className="w-full sm:w-40">
            <Field label="Stage" htmlFor="lead-stage">
              <Select id="lead-stage" value={stage} onChange={(event) => { setStage(event.target.value); setPage(0) }}>
                <option value="">Every stage</option>
                {STAGES.map((each) => <option key={each} value={each}>{humanize(each)}</option>)}
              </Select>
            </Field>
          </div>
          <div className="w-full sm:w-36">
            <Field label="Priority" htmlFor="lead-priority">
              <Select id="lead-priority" value={priority} onChange={(event) => { setPriority(event.target.value); setPage(0) }}>
                <option value="">Every priority</option>
                {LEAD_PRIORITIES.map((each) => <option key={each} value={each}>{humanize(each)}</option>)}
              </Select>
            </Field>
          </div>
          <div className="w-full sm:w-32">
            <Field label="Type" htmlFor="lead-type">
              <Select id="lead-type" value={type} onChange={(event) => { setType(event.target.value); setPage(0) }}>
                <option value="">Every type</option>
                {LEAD_TYPES.map((each) => <option key={each} value={each}>{humanize(each)}</option>)}
              </Select>
            </Field>
          </div>
          <div className="w-full sm:w-40">
            <Field label="Source code" htmlFor="lead-source" hint="A lead source's code">
              <Input
                id="lead-source"
                value={source}
                placeholder="e.g. WEBSITE"
                onChange={(event) => { setSource(event.target.value); setPage(0) }}
              />
            </Field>
          </div>
          <div className="w-full sm:w-52">
            <Field label="Owner agent" htmlFor="lead-owner" hint={agentsQuery.isError ? 'Agent list unavailable' : undefined}>
              <Select
                id="lead-owner"
                value={ownerAgentId}
                disabled={agentsQuery.isLoading || agentsQuery.isError}
                onChange={(event) => { setOwnerAgentId(event.target.value); setPage(0) }}
              >
                <option value="">Every agent</option>
                {activeAgents.map((agent) => (
                  <option key={agent.id} value={agent.id}>{agent.fullName ?? agent.agentCode}</option>
                ))}
              </Select>
            </Field>
          </div>
          <div className="w-full sm:w-44">
            <Field label="Sort" htmlFor="lead-sort">
              <Select id="lead-sort" value={sort} onChange={(event) => setSort(event.target.value)}>
                {/* A sort set by a column header is not one of the listed ones; it is shown rather
                    than silently reset, so the picker never disagrees with the table. */}
                {!sortIsListed && <option value={sort}>{describeSort(sort)}</option>}
                {SORT_OPTIONS.map((option) => (
                  <option key={option.value} value={option.value}>{option.label}</option>
                ))}
              </Select>
            </Field>
          </div>
        </Toolbar>
        <p className="px-4 py-2 text-xs" aria-live="polite" style={{ color: 'var(--text-secondary)' }}>
          {leadsQuery.isLoading
            ? 'Reading the register…'
            : `${fmtCount(leadsQuery.data?.totalElements ?? 0)} lead(s) match these filters.`}
        </p>
      </Panel>

      {/* ── The register, in the view the URL asks for ─────────────────────── */}
      {leadsQuery.isLoading ? (
        <Panel><Skeleton rows={6} /></Panel>
      ) : leadsQuery.isError ? (
        <ErrorState message={messageOf(leadsQuery.error)} onRetry={() => void leadsQuery.refetch()} />
      ) : rows.length === 0 ? (
        <Panel>
          <EmptyState
            icon={<ListFilter size={22} />}
            title="No leads match these filters"
            description={
              stage || priority || type || source || search || ownerAgentId
                ? 'Every filter is narrowing the book to nothing. Clear the stage, priority, source or search above and the register will fill in.'
                : 'Not one lead has been logged yet. Log the first enquiry and it will appear here at the NEW stage.'
            }
            action={<Button variant="primary" onClick={() => setCreating(true)}>Log a lead</Button>}
          />
        </Panel>
      ) : view === 'table' ? (
        <Panel bodyClassName="p-0">
          <DataTable
            columns={columns}
            rows={rows}
            keyOf={(row) => row.id}
            sort={sort}
            onSortChange={setSort}
            onRowClick={(row) => navigate(`/leads/${row.id}`)}
            caption="Lead register"
            empty={<EmptyState title="No leads on this page" description="Page past the end of the register." />}
          />
          <Pagination
            page={leadsQuery.data?.page ?? 0}
            size={leadsQuery.data?.size ?? size}
            totalElements={leadsQuery.data?.totalElements ?? 0}
            totalPages={leadsQuery.data?.totalPages ?? 0}
            hasNext={leadsQuery.data?.hasNext ?? false}
            onPageChange={setPage}
            onSizeChange={(next) => { setSize(next); setPage(0) }}
          />
        </Panel>
      ) : (
        <>
          {/* A board is read left to right, so it scrolls sideways rather than wrapping into a wall. */}
          <section
            className="flex items-stretch gap-3 overflow-x-auto pb-2"
            aria-label="Lead pipeline board"
          >
            {board.map((column) => (
              <section
                key={column.stage}
                role="group"
                tabIndex={0}
                aria-label={`${humanize(column.stage)}: ${column.cards.length} lead(s) on this board`}
                className="focus-meridian flex w-64 shrink-0 flex-col rounded-[var(--radius-panel)] border p-3"
                style={{ borderColor: 'var(--border)', background: 'var(--surface-muted)' }}
              >
                <header className="mb-2 flex items-baseline justify-between gap-2">
                  <h3 className="text-xs font-bold uppercase tracking-wide" style={{ color: TONE_INK[stageTone(column.stage)] }}>
                    {humanize(column.stage)}
                  </h3>
                  <span className="text-xs tabular-nums" style={{ color: 'var(--text-secondary)' }}>
                    {column.cards.length}
                  </span>
                </header>
                {column.cards.length === 0 ? (
                  <p className="rounded-[var(--radius-card)] border px-2 py-3 text-[11px]"
                    style={{ borderColor: 'var(--border-forest)', borderStyle: 'dashed', color: 'var(--text-secondary)' }}>
                    Nothing at this stage on the board.
                  </p>
                ) : (
                  <ul className="space-y-2">
                    {column.cards.map((lead) => (
                      <li
                        key={lead.id}
                        className="rounded-[var(--radius-card)] border p-2.5"
                        style={{ borderColor: 'var(--border)', background: 'var(--surface-elevated)' }}
                      >
                        <Link to={`/leads/${lead.id}`} className="focus-meridian block min-w-0">
                          <span className="block truncate text-sm font-semibold" style={{ color: 'var(--text-primary)' }}>
                            {lead.displayName}
                          </span>
                          <span className="block text-[11px] tabular-nums" style={{ color: 'var(--text-secondary)' }}>
                            {lead.leadCode}
                          </span>
                        </Link>
                        <div className="mt-1.5 flex flex-wrap items-center gap-1.5">
                          <StatusBadge status={lead.priority} />
                          {lead.expectedValue !== null && (
                            <span className="text-[11px] tabular-nums" style={{ color: 'var(--text-primary)' }}>
                              {moneyCompact(lead.expectedValue)}
                            </span>
                          )}
                        </div>
                        <div className="mt-1.5 flex items-center justify-between gap-2">
                          <span className="min-w-0 truncate text-[11px]" style={{ color: 'var(--text-secondary)' }}>
                            {lead.ownerAgentName ?? 'Unassigned'}
                          </span>
                          <button
                            type="button"
                            aria-label={`Assign ${lead.displayName}`}
                            onClick={() => setAssigning(lead)}
                            className="focus-meridian rounded-[var(--radius-control)] p-1"
                            style={{ color: 'var(--ink-copper)' }}
                          >
                            <UserPlus size={13} aria-hidden="true" />
                          </button>
                        </div>
                      </li>
                    ))}
                  </ul>
                )}
              </section>
            ))}
          </section>
          <p className="mt-2 text-[11px]" style={{ color: 'var(--text-secondary)' }}>
            A board, not the whole book: the first {boardSize} leads matching the filters are laid out above
            ({fmtCount(leadsQuery.data?.totalElements ?? 0)} match in total). Narrow the filters, or switch to the
            register table to page through all of them.
          </p>
        </>
      )}

      <CreateLeadDialog
        open={creating}
        onClose={() => setCreating(false)}
        agents={activeAgents}
        agentsUnavailable={agentsQuery.isError}
        onCreated={(name) => {
          void queryClient.invalidateQueries({ queryKey: ['leads'] })
          toast.push(`${name} was logged at the NEW stage.`)
          setCreating(false)
        }}
      />

      <AssignLeadDialog
        lead={assigning}
        agents={activeAgents}
        loadingAgents={agentsQuery.isLoading}
        agentsUnavailable={agentsQuery.isError}
        onClose={() => setAssigning(null)}
        onAssigned={(name, agent) => {
          void queryClient.invalidateQueries({ queryKey: ['leads'] })
          toast.push(`${name} is now held by ${agent}.`)
          setAssigning(null)
        }}
      />
    </>
  )
}

/** The colour a stage carries — the same tone `StatusBadge` uses, so a stage is never two colours. */
function stageTone(stage: string) {
  if (stage === 'WON') return 'emerald' as const
  if (stage === 'LOST') return 'terracotta' as const
  if (stage === 'NEW' || stage === 'CONTACTED') return 'sapphire' as const
  return 'amber' as const
}

function opensTotal(pipeline: PipelineResponse | undefined): number {
  return pipeline?.stages.reduce((sum, bucket) => sum + bucket.count, 0) ?? 0
}

/**
 * Log a lead.
 *
 * Every field the form collects exists on `LeadDtos.CreateLeadRequest`, and nothing the form does not
 * collect is sent: a stage is never supplied because every lead starts at NEW server-side. The budget
 * range is checked here as well as on the server, because the server's refusal is a sentence rather
 * than a hint beside the field.
 */
function CreateLeadDialog({
  open, onClose, agents, agentsUnavailable, onCreated,
}: {
  open: boolean
  onClose: () => void
  agents: AgentSummary[]
  agentsUnavailable: boolean
  onCreated: (displayName: string) => void
}) {
  const [form, setForm] = useState({
    displayName: '', leadType: 'RENTAL', priority: 'MEDIUM', email: '', phone: '',
    preferredCity: '', minBudget: '', maxBudget: '', expectedValue: '',
    nextAction: '', nextActionAt: '', ownerAgentId: '',
  })
  const [failure, setFailure] = useState<string | null>(null)

  // The property types are a real reference read, so the requirement is picked from the firm's own list
  // rather than typed as a code that the API would have to refuse.
  const typesQuery = useQuery({
    queryKey: ['reference', 'property-types'],
    queryFn: () => referenceApi.propertyTypes(),
    enabled: open,
  })
  const [propertyTypeCode, setPropertyTypeCode] = useState('')

  const budgetInvalid =
    form.minBudget !== '' && form.maxBudget !== '' && Number(form.maxBudget) < Number(form.minBudget)

  const create = useMutation({
    mutationFn: async () => leadApi.create({
      displayName: form.displayName.trim(),
      leadType: form.leadType,
      priority: form.priority,
      email: form.email.trim() || null,
      phone: form.phone.trim() || null,
      preferredCity: form.preferredCity.trim() || null,
      propertyTypeCode: propertyTypeCode || null,
      minBudget: form.minBudget === '' ? null : Number(form.minBudget),
      maxBudget: form.maxBudget === '' ? null : Number(form.maxBudget),
      expectedValue: form.expectedValue === '' ? null : Number(form.expectedValue),
      nextAction: form.nextAction.trim() || null,
      // The field is a date; the API wants a moment, and nine in the morning is the working day's start.
      nextActionAt: form.nextActionAt ? `${form.nextActionAt}T09:00:00` : null,
      ownerAgentId: form.ownerAgentId || null,
    }),
    onSuccess: (lead) => onCreated(lead.displayName),
    onError: (error) => setFailure(messageOf(error)),
  })

  const submit = () => {
    setFailure(null)
    if (form.displayName.trim() === '') {
      setFailure('A lead needs a name — it is what the desk reads the record back as.')
      return
    }
    if (budgetInvalid) {
      setFailure('The maximum budget cannot be less than the minimum budget.')
      return
    }
    create.mutate()
  }

  return (
    <Dialog
      open={open}
      onClose={onClose}
      title="Log a lead"
      description="A new enquiry. It starts at the NEW stage; moving it on is a separate, recorded act."
      width="lg"
      footer={
        <>
          <Button variant="ghost" onClick={onClose} disabled={create.isPending}>Cancel</Button>
          <Button variant="primary" onClick={submit} loading={create.isPending}>Log the lead</Button>
        </>
      }
    >
      <div className="grid gap-3 sm:grid-cols-2">
        <Field label="Name" htmlFor="lead-new-name" required>
          <Input
            id="lead-new-name"
            value={form.displayName}
            onChange={(event) => setForm({ ...form, displayName: event.target.value })}
            autoFocus
          />
        </Field>
        <Field label="Looking to" htmlFor="lead-new-type">
          <Select id="lead-new-type" value={form.leadType} onChange={(event) => setForm({ ...form, leadType: event.target.value })}>
            {LEAD_TYPES.map((each) => <option key={each} value={each}>{each === 'RENTAL' ? 'Rent' : each === 'SALE' ? 'Buy' : 'Rent or buy'}</option>)}
          </Select>
        </Field>
        <Field label="Priority" htmlFor="lead-new-priority">
          <Select id="lead-new-priority" value={form.priority} onChange={(event) => setForm({ ...form, priority: event.target.value })}>
            {LEAD_PRIORITIES.map((each) => <option key={each} value={each}>{humanize(each)}</option>)}
          </Select>
        </Field>
        <Field label="Property type" htmlFor="lead-new-property-type" hint={typesQuery.isError ? 'Reference list unavailable' : undefined}>
          <Select
            id="lead-new-property-type"
            value={propertyTypeCode}
            disabled={typesQuery.isLoading || typesQuery.isError}
            onChange={(event) => setPropertyTypeCode(event.target.value)}
          >
            <option value="">Not specified</option>
            {(typesQuery.data ?? []).map((row) => <option key={row.code} value={row.code}>{row.name}</option>)}
          </Select>
        </Field>
        <Field label="Email" htmlFor="lead-new-email">
          <Input id="lead-new-email" type="email" value={form.email} onChange={(event) => setForm({ ...form, email: event.target.value })} />
        </Field>
        <Field label="Phone" htmlFor="lead-new-phone">
          <Input id="lead-new-phone" value={form.phone} onChange={(event) => setForm({ ...form, phone: event.target.value })} />
        </Field>
        <Field label="Preferred city" htmlFor="lead-new-city">
          <Input id="lead-new-city" value={form.preferredCity} onChange={(event) => setForm({ ...form, preferredCity: event.target.value })} />
        </Field>
        <Field label="Owner agent" htmlFor="lead-new-owner" hint={agentsUnavailable ? 'Agent list unavailable' : 'Only an active agent may hold work'}>
          <Select
            id="lead-new-owner"
            value={form.ownerAgentId}
            disabled={agentsUnavailable}
            onChange={(event) => setForm({ ...form, ownerAgentId: event.target.value })}
          >
            <option value="">Leave for the desk</option>
            {agents.map((agent) => <option key={agent.id} value={agent.id}>{agent.fullName ?? agent.agentCode}</option>)}
          </Select>
        </Field>
        <Field label="Minimum budget" htmlFor="lead-new-min">
          <Input id="lead-new-min" inputMode="decimal" value={form.minBudget} onChange={(event) => setForm({ ...form, minBudget: event.target.value })} />
        </Field>
        <Field
          label="Maximum budget"
          htmlFor="lead-new-max"
          error={budgetInvalid ? 'The maximum cannot be less than the minimum' : null}
        >
          <Input id="lead-new-max" inputMode="decimal" value={form.maxBudget} onChange={(event) => setForm({ ...form, maxBudget: event.target.value })} />
        </Field>
        <Field label="Expected value" htmlFor="lead-new-value" hint="What the lead is realistically worth, not the budget ceiling">
          <Input id="lead-new-value" inputMode="decimal" value={form.expectedValue} onChange={(event) => setForm({ ...form, expectedValue: event.target.value })} />
        </Field>
        <Field label="Next action" htmlFor="lead-new-next">
          <Input id="lead-new-next" value={form.nextAction} onChange={(event) => setForm({ ...form, nextAction: event.target.value })} />
        </Field>
        <Field label="Next action due" htmlFor="lead-new-next-at">
          <Input id="lead-new-next-at" type="date" value={form.nextActionAt} onChange={(event) => setForm({ ...form, nextActionAt: event.target.value })} />
        </Field>
      </div>

      {/* Async outcome, announced rather than only coloured. */}
      <div aria-live="polite" className="mt-3">
        {failure && <p role="alert" className="text-sm" style={{ color: 'var(--danger)' }}>{failure}</p>}
      </div>
    </Dialog>
  )
}

/**
 * Assign or reassign the agent who holds a lead.
 *
 * Only an ACTIVE agent is offered: `LeadService.requireActiveAgent` refuses work for anyone else with
 * `LEAD_ASSIGN_INACTIVE_AGENT`, and a control that can only fail is not a control.
 */
function AssignLeadDialog({
  lead, agents, loadingAgents, agentsUnavailable, onClose, onAssigned,
}: {
  lead: LeadSummary | null
  agents: AgentSummary[]
  loadingAgents: boolean
  agentsUnavailable: boolean
  onClose: () => void
  onAssigned: (displayName: string, agentName: string) => void
}) {
  const [agentId, setAgentId] = useState('')
  const [reason, setReason] = useState('')
  const [failure, setFailure] = useState<string | null>(null)

  const assign = useMutation({
    mutationFn: async (input: { id: string; agentId: string; reason: string }) =>
      leadApi.assign(input.id, { agentId: input.agentId, reason: input.reason.trim() || null }),
    onSuccess: (updated) => {
      const name = agents.find((agent) => agent.id === agentId)?.fullName ?? 'the agent'
      onAssigned(updated.displayName, name)
      setAgentId('')
      setReason('')
    },
    onError: (error) => setFailure(messageOf(error)),
  })

  const submit = () => {
    setFailure(null)
    if (!lead) return
    if (!agentId) {
      setFailure('Choose the agent who should hold this lead.')
      return
    }
    assign.mutate({ id: lead.id, agentId, reason })
  }

  return (
    <Dialog
      open={lead !== null}
      onClose={onClose}
      title={lead ? `Assign ${lead.displayName}` : 'Assign a lead'}
      description="Reassigning closes the current assignment and opens a new one, so the history of who held the lead is kept."
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
          htmlFor="lead-assign-agent"
          required
          hint={agentsUnavailable
            ? 'The agent list could not be read, so no one can be chosen here.'
            : 'Active agents only — an inactive agent cannot be given work.'}
        >
          <Select
            id="lead-assign-agent"
            value={agentId}
            disabled={loadingAgents || agentsUnavailable}
            onChange={(event) => setAgentId(event.target.value)}
          >
            <option value="">{loadingAgents ? 'Reading the agent list…' : 'Choose an agent'}</option>
            {agents.map((agent) => (
              <option key={agent.id} value={agent.id}>
                {/* The agent's live workload is not on the register row; it is `agentApi.workload(id)`,
                    read per agent on the directory. Nothing is guessed in its place here. */}
                {agent.fullName ?? agent.agentCode}
              </option>
            ))}
          </Select>
        </Field>
        <Field label="Reason" htmlFor="lead-assign-reason" hint="Recorded against the assignment history">
          <Input id="lead-assign-reason" value={reason} onChange={(event) => setReason(event.target.value)} />
        </Field>
        <div aria-live="polite">
          {failure && <p role="alert" className="text-sm" style={{ color: 'var(--danger)' }}>{failure}</p>}
        </div>
      </div>
    </Dialog>
  )
}
