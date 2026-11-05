import { useState } from 'react'
import { useQueries, useQuery } from '@tanstack/react-query'
import { PageAccessGuard, PageHeader } from '../components/common'
import {
  Badge, Button, Dialog, EmptyState, ErrorState, KpiTile, Panel, Pagination, Skeleton, StatusBadge,
  SectionHeader, TONE_INK, TONE_SURFACE,
} from '../components/ui'
import { ComparisonBars } from '../components/ui/charts'
import { count as fmtCount, humanize, initials, percent } from '../components/ui/formatters'
import { agentApi } from '../services'
import { messageOf } from '../lib/errors'
import type { AgentSummary, WorkloadResponse } from '../types'

/**
 * The agent directory.
 *
 * This is a people screen, not a register: an agent is a staff account that carries a portfolio, and
 * what a manager wants from this page is a glanceable read of who is carrying what and how heavily.
 * So it is a card grid rather than a schedule, and each card carries the agent's **workload** — the
 * open leads they hold, the listings they have live, the viewings ahead of them in the next seven
 * days and the maintenance on their book that is still open — read from `agentApi.workload(id)`.
 *
 * The workload is deliberately *not* a field on the register row: `AgentDtos.AgentSummary` carries no
 * lead or listing counts, because each of those four figures is a query over another module and
 * feeding four of them into every row of the register would cost four queries per row. The register is
 * therefore read for who the agents are, and each card reads its own workload once.
 *
 * The conversion rate on the detail is **rendered, never computed here**. The backend guards the
 * division so an agent with no leads reads `0`, not `NaN`; doing the arithmetic in the page would be
 * the one way to undo that guard, so the rate is passed straight through `percent(...)`.
 */

export default function AgentsPage() {
  return (
    <PageAccessGuard permission="AGENT_VIEW">
      <AgentDirectory />
    </PageAccessGuard>
  )
}

function AgentDirectory() {
  const [page, setPage] = useState(0)
  const [size, setSize] = useState(12)
  const [selected, setSelected] = useState<AgentSummary | null>(null)

  const query = useQuery({
    queryKey: ['agents', { page, size }],
    queryFn: () => agentApi.list({ page, size, sort: 'displayName,asc' }),
  })

  const agents = query.data?.items ?? []
  const totalElements = query.data?.totalElements ?? 0

  /*
   * One workload read per agent on the page, fired in parallel.
   *
   * React Query keys each on the agent id, so the card and the detail dialog share the same cached
   * result rather than requesting it twice, and a page change does not refetch the agents that are
   * still on screen.
   */
  const workloadQueries = useQueries({
    queries: agents.map((agent) => ({
      queryKey: ['agents', agent.id, 'workload'],
      queryFn: () => agentApi.workload(agent.id),
    })),
  })

  const cards = agents.map((agent, index) => ({
    agent,
    // `agentApi.workload` declares `AgentDtos.WorkloadResponse`, so every figure read below is a
    // number the endpoint promised rather than a value probed out of an untyped map.
    workload: workloadQueries[index]?.data,
    workloadLoading: Boolean(workloadQueries[index]?.isLoading),
    workloadFailed: Boolean(workloadQueries[index]?.isError),
    reload: () => void workloadQueries[index]?.refetch(),
  }))

  // Only the agents whose workload has actually arrived are compared; a bar for an agent still
  // loading would be a number the page made up.
  const comparison = cards.flatMap((card) =>
    card.workload
      ? [{ label: card.agent.fullName ?? card.agent.agentCode, value: card.workload.openLeads }]
      : [],
  )

  if (query.isLoading) {
    return (
      <>
        <PageHeader title="Agents" description="Reading the agent directory…" />
        <Skeleton rows={6} />
      </>
    )
  }

  if (query.isError) {
    return (
      <>
        <PageHeader title="Agents" />
        <ErrorState message={messageOf(query.error)} onRetry={() => void query.refetch()} />
      </>
    )
  }

  return (
    <>
      <PageHeader
        eyebrow="People"
        title="Agents"
        description="The staff accounts that carry the firm's stock. Each card reads that agent's current workload; open one for their performance over their book."
      />

      <SectionHeader
        eyebrow="Workload"
        title="Open leads by agent"
        rule="sapphire"
        description="How many open leads each agent is holding, so the book can be levelled before it is reassigned."
      />
      <Panel tint="sapphire" className="mb-6">
        <ComparisonBars points={comparison} title="Open leads by agent" unit="count" />
      </Panel>

      <SectionHeader
        eyebrow="Directory"
        title="The agent register"
        rule="forest"
        description={`${fmtCount(totalElements)} agent${totalElements === 1 ? '' : 's'} on the register.`}
      />

      <p className="sr-only" aria-live="polite">
        {query.isFetching ? 'Reading the agent directory.' : `${fmtCount(agents.length)} agent cards shown.`}
      </p>

      {agents.length === 0 ? (
        <Panel>
          <EmptyState
            title="No agent profiles on the register"
            description="No staff account carries an agent profile yet. An agent appears here once a profile is created for a staff account, and their workload fills in behind it."
          />
        </Panel>
      ) : (
        <div className="grid gap-3 sm:grid-cols-2 xl:grid-cols-3">
          {cards.map((card) => (
            <AgentCard
              key={card.agent.id}
              agent={card.agent}
              workload={card.workload}
              loading={card.workloadLoading}
              failed={card.workloadFailed}
              onReload={card.reload}
              onOpen={() => setSelected(card.agent)}
            />
          ))}
        </div>
      )}

      <div className="mt-3">
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

      {selected && <AgentDetailDialog agent={selected} onClose={() => setSelected(null)} />}
    </>
  )
}

/* ============================================================
 * One agent, as a plot card
 * ============================================================ */
function AgentCard({
  agent, workload, loading, failed, onReload, onOpen,
}: {
  agent: AgentSummary
  workload: WorkloadResponse | undefined
  loading: boolean
  failed: boolean
  onReload: () => void
  onOpen: () => void
}) {
  const name = agent.fullName ?? agent.agentCode

  return (
    <Panel notch>
      <div className="flex items-start gap-3">
        {/* An avatar carries identity without inventing a photograph: the agent's own initials. */}
        <span
          aria-hidden="true"
          className="grid h-11 w-11 shrink-0 place-items-center rounded-[var(--radius-chip)] text-sm font-bold"
          style={{ background: TONE_SURFACE.forest, color: TONE_INK.forest }}
        >
          {initials(name)}
        </span>
        <div className="min-w-0 flex-1">
          <h3 className="truncate text-sm font-semibold" style={{ color: 'var(--text-primary)' }}>{name}</h3>
          <p className="truncate text-xs" style={{ color: 'var(--text-secondary)' }}>
            {agent.agentCode} · {agent.officeName ?? 'No office assigned'}
          </p>
          <div className="mt-1.5 flex flex-wrap items-center gap-2">
            <StatusBadge status={agent.status} />
            <Badge>{humanize(agent.specialisation)}</Badge>
          </div>
        </div>
      </div>

      <div className="mt-3 border-t pt-3" style={{ borderColor: 'var(--border)' }}>
        {workload ? (
          <dl className="grid grid-cols-2 gap-x-3 gap-y-2">
            <WorkloadFigure label="Open leads" value={workload.openLeads} />
            <WorkloadFigure label="Live listings" value={workload.activeListings} />
            <WorkloadFigure label="Viewings · 7 days" value={workload.viewingsNext7Days} />
            <WorkloadFigure label="Open maintenance" value={workload.openMaintenance} />
          </dl>
        ) : loading ? (
          <Skeleton rows={3} />
        ) : failed ? (
          <div className="flex flex-wrap items-center justify-between gap-2">
            <p className="text-xs" style={{ color: 'var(--text-secondary)' }}>This agent's workload could not be read.</p>
            <Button size="sm" onClick={onReload}>Try again</Button>
          </div>
        ) : (
          <p className="text-xs" style={{ color: 'var(--text-secondary)' }}>No workload is reported for this agent.</p>
        )}
      </div>

      <div className="mt-3">
        <Button size="sm" onClick={onOpen}>View workload and performance</Button>
      </div>
    </Panel>
  )
}

function WorkloadFigure({ label, value }: { label: string; value: number }) {
  return (
    <div className="min-w-0">
      <dt className="eyebrow" style={{ color: 'var(--text-secondary)' }}>{label}</dt>
      <dd className="kpi-value text-lg" style={{ color: 'var(--text-primary)' }}>{fmtCount(value)}</dd>
    </div>
  )
}

/* ============================================================
 * The detail dialog - workload and performance
 * ============================================================ */
function AgentDetailDialog({ agent, onClose }: { agent: AgentSummary; onClose: () => void }) {
  const name = agent.fullName ?? agent.agentCode

  // Keyed exactly as the card keys its workload, so opening the dialog reuses the figure already on
  // screen rather than flashing a skeleton over a number the reader has just seen.
  const workload = useQuery({
    queryKey: ['agents', agent.id, 'workload'],
    queryFn: () => agentApi.workload(agent.id),
  })

  const performance = useQuery({
    queryKey: ['agents', agent.id, 'performance'],
    queryFn: () => agentApi.performance(agent.id),
  })

  return (
    <Dialog
      open
      title={name}
      description={`Agent ${agent.agentCode} — current workload and performance over their book.`}
      onClose={onClose}
      width="lg"
      footer={<Button variant="ghost" onClick={onClose}>Close</Button>}
    >
      <div className="space-y-4">
        <Panel title="Workload" subtitle="What this agent is carrying right now" tint="sapphire" bodyClassName="space-y-3">
          {workload.isLoading ? (
            <Skeleton rows={4} />
          ) : workload.isError ? (
            <ErrorState message={messageOf(workload.error)} onRetry={() => void workload.refetch()} />
          ) : workload.data ? (
            <div className="grid gap-3 sm:grid-cols-2">
              <KpiTile label="Open leads" value={fmtCount(workload.data.openLeads)} tone="sapphire" dense />
              <KpiTile label="Live listings" value={fmtCount(workload.data.activeListings)} tone="teal" dense />
              <KpiTile label="Viewings · 7 days" value={fmtCount(workload.data.viewingsNext7Days)} tone="forest" dense />
              <KpiTile label="Open maintenance" value={fmtCount(workload.data.openMaintenance)} tone="terracotta" dense />
            </div>
          ) : (
            <EmptyState
              title="No workload is reported"
              description="This agent holds no open work — no leads, no live listings, no viewings ahead and no open maintenance."
            />
          )}
        </Panel>

        <Panel title="Performance" subtitle="How this agent's book has performed" tint="emerald" bodyClassName="space-y-3">
          {performance.isLoading ? (
            <Skeleton rows={4} />
          ) : performance.isError ? (
            <ErrorState message={messageOf(performance.error)} onRetry={() => void performance.refetch()} />
          ) : performance.data ? (
            <div className="grid gap-3 sm:grid-cols-2">
              <KpiTile
                label="Leads converted"
                value={fmtCount(performance.data.leadsConverted)}
                tone="emerald"
                dense
                caption={`of ${fmtCount(performance.data.totalLeads)} held`}
              />
              <KpiTile label="Viewings completed" value={fmtCount(performance.data.viewingsCompleted)} tone="teal" dense />
              {/*
                The rate is the backend's own figure. `percent` renders the value it is given and
                nothing else, so an agent with no leads reads 0% — which is exactly the guard the
                service applies, left intact.
              */}
              <KpiTile
                label="Conversion rate"
                value={percent(performance.data.conversionRatePercent, 0)}
                tone="copper"
                dense
                caption={performance.data.totalLeads === 0 ? 'No leads held yet' : 'Converted over leads held'}
              />
              <KpiTile label="Listings let" value={fmtCount(performance.data.listingsLet)} tone="forest" dense />
            </div>
          ) : (
            <EmptyState
              title="No performance is reported"
              description="This agent's book has nothing to measure yet — the figures appear once they hold leads or listings."
            />
          )}
        </Panel>
      </div>
    </Dialog>
  )
}
