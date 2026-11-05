import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Banknote, ListFilter, TrendingUp } from 'lucide-react'
import { useMemo, useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import { PageAccessGuard, PageHeader } from '../components/common'
import {
  Badge, Button, DataTable, Dialog, EmptyState, ErrorState, Field, Input, KpiTile, Panel, Pagination,
  Select, Skeleton, StatusBadge, TONE_INK, TONE_SURFACE, Toolbar, useToast, type Column,
} from '../components/ui'
import { Funnel } from '../components/ui/charts'
import {
  count as fmtCount, date, dateTime, humanize, money, moneyCompact, relative,
} from '../components/ui/formatters'
import { agentApi, saleApi } from '../services'
import { useAuthStore } from '../auth/authStore'
import { ApiError, messageOf } from '../lib/errors'
import type { AgentSummary, SaleSummaryRow } from '../types'

/**
 * The sales pipeline: the capital side of the book.
 *
 * Read as a **board** by stage (a deal is a thing that moves, and a board is how a desk sees what is
 * moving), as a **register** (a table is how it is scanned and sorted), and as a **funnel** drawn from
 * the pipeline read. Which of the two layouts is on screen lives in the URL (`?view=pipeline|table`).
 *
 * Three rules from `SaleService` are mirrored here, and each one is stated on screen rather than left
 * to be discovered:
 *
 *  1. **A stage is a move, not a field.** `SALE_ALLOWED` transcribes the service's own map, so the
 *     change-stage dialog offers only the moves that will succeed, and a concluded deal — CLOSED or
 *     LOST — offers none at all.
 *  2. **Closing needs a price.** `ck_sales_opportunities_outcome` refuses a CLOSED row without an agreed
 *     price and the service refuses it as `SALE_AMOUNT_REQUIRED`, so the price is collected in the very
 *     form that moves the stage.
 *  3. **Concluding needs the capability.** `SaleService.transition` requires `SALE_CLOSE` once the
 *     target is concluded, so an account without it is not offered a move it would be refused.
 */

/** The permitted stage moves, transcribed from `SaleService.ALLOWED`. */
const SALE_ALLOWED: Record<string, string[]> = {
  LEAD: ['OFFER', 'LOST'],
  OFFER: ['NEGOTIATION', 'UNDER_CONTRACT', 'LOST'],
  NEGOTIATION: ['UNDER_CONTRACT', 'LOST'],
  UNDER_CONTRACT: ['CLOSED', 'LOST'],
  // Terminal, and frozen: a concluded deal is a fact and is not reopened.
  CLOSED: [],
  LOST: [],
}

/** The order the board and the funnel present the stages in, mirroring `SaleService.STAGE_ORDER`. */
const STAGE_ORDER = ['LEAD', 'OFFER', 'NEGOTIATION', 'UNDER_CONTRACT', 'CLOSED', 'LOST'] as const

/** The stages a deal has concluded in, either way, mirroring `SaleStage.isConcluded()`. */
const CONCLUDED_STAGES = ['CLOSED', 'LOST']

/**
 * The rank a stage occupies in the funnel.
 *
 * This mirrors the rule `LeadService.funnel()` states for the enquiry funnel: a rung counts the records
 * that **reached** that stage or went past it, so the rungs can only narrow, and a deal that was LOST is
 * counted at the top of the funnel only — the stage it fell out from is not recorded, so it is not
 * attributed to a stage it may never have reached.
 */
const FUNNEL_RANKS = ['LEAD', 'OFFER', 'NEGOTIATION', 'UNDER_CONTRACT', 'CLOSED']

const SORT_OPTIONS = [
  { value: 'createdAt,desc', label: 'Newest first' },
  { value: 'expectedPrice,desc', label: 'Expected price, high to low' },
  { value: 'expectedCloseDate,asc', label: 'Expected close date' },
  { value: 'stage,asc', label: 'Stage' },
  { value: 'saleCode,asc', label: 'Sale code' },
]

/**
 * The board row, as the endpoint answers it.
 *
 * `SaleDtos.SaleSummary` carries the stock (property and unit), the buyer, the owner, the office, the
 * three commercial figures and whether the deal is still live. The shared `SaleSummaryRow` in
 * `src/types` is declared from that record, so the cells below read its own names.
 */

function describeSort(sort: string): string {
  const [field, direction] = sort.split(',')
  return `Sorted by ${humanize(field).toLowerCase()} · ${direction === 'desc' ? 'descending' : 'ascending'}`
}

/**
 * The sentence a refused stage move deserves.
 *
 * The two that a reader can act on are stated in the desk's terms: a deal cannot be completed without a
 * price, and a completion needs an accepted offer. Anything else is passed through as the API worded it,
 * because the API's own sentence names the moves that *are* allowed from where the deal stands.
 */
function transitionFailure(error: unknown): string {
  if (error instanceof ApiError) {
    if (error.code === 'SALE_AMOUNT_REQUIRED') {
      return 'A sale cannot complete without an agreed price. Give the price the deal closed at.'
    }
    if (error.code === 'SALE_COMPLETION_REQUIRES_ACCEPTED_OFFER') {
      return 'A sale cannot be completed without an accepted offer — completion is what an acceptance means'
    }
    if (error.code === 'SALE_ALREADY_CONCLUDED') {
      return 'This deal has concluded. A concluded deal is a fact and is not edited back.'
    }
  }
  return messageOf(error)
}

/** The colour a stage carries — the same tone `StatusBadge` resolves, so a stage is never two colours. */
function stageTone(stage: string) {
  if (stage === 'CLOSED') return 'emerald' as const
  if (stage === 'LOST') return 'terracotta' as const
  if (stage === 'LEAD') return 'sapphire' as const
  return 'amber' as const
}

export default function SalesPage() {
  return (
    <PageAccessGuard permission="SALE_VIEW">
      <SalesPipeline />
    </PageAccessGuard>
  )
}

function SalesPipeline() {
  const queryClient = useQueryClient()
  const toast = useToast()
  const canClose = useAuthStore((state) => state.can('SALE_CLOSE'))
  const [params, setParams] = useSearchParams()

  const view: 'pipeline' | 'table' = params.get('view') === 'table' ? 'table' : 'pipeline'
  const setView = (next: 'pipeline' | 'table') => {
    const copy = new URLSearchParams(params)
    copy.set('view', next)
    setParams(copy, { replace: true })
  }

  const [stage, setStage] = useState('')
  const [agentId, setAgentId] = useState('')
  const [search, setSearch] = useState('')
  const [openOnly, setOpenOnly] = useState(false)
  const [sort, setSort] = useState('createdAt,desc')
  const [page, setPage] = useState(0)
  const [size, setSize] = useState(20)
  const [changing, setChanging] = useState<SaleSummaryRow | null>(null)

  // The board asks for a wider page than the register, and says so rather than pretending otherwise.
  const boardSize = 100
  const effectiveSize = view === 'pipeline' ? boardSize : size

  const salesQuery = useQuery({
    queryKey: ['sales', 'register', {
      page, effectiveSize, sort, stage, agentId, search, openOnly,
    }],
    queryFn: () => saleApi.list({
      page,
      size: effectiveSize,
      sort,
      stage: stage || undefined,
      ownerAgentId: agentId || undefined,
      search: search.trim() || undefined,
      openOnly: openOnly ? true : undefined,
    }),
  })

  const pipelineQuery = useQuery({
    queryKey: ['sales', 'pipeline'],
    queryFn: () => saleApi.pipeline(),
  })

  const agentsQuery = useQuery({
    queryKey: ['agents', 'register'],
    queryFn: () => agentApi.list({ size: 200 }),
  })

  const rows = salesQuery.data?.items ?? []
  // `saleApi.pipeline` declares `SaleDtos.PipelineResponse`, so the stage buckets are read by name.
  const pipeline = pipelineQuery.data
  const activeAgents: AgentSummary[] = (agentsQuery.data?.items ?? [])
    .filter((agent) => agent.status === 'ACTIVE')

  const board = useMemo(
    () => STAGE_ORDER.map((each) => ({ stage: each, cards: rows.filter((row) => row.stage === each) })),
    [rows],
  )

  const stageCount = (value: string): number =>
    pipeline?.stages.find((bucket) => bucket.stage === value)?.count ?? 0

  /**
   * The funnel.
   *
   * There is no funnel read in the service map for sales, so the rungs are derived from the pipeline's
   * own per-stage counts using the cumulative rule the enquiry funnel states: a rung counts the deals
   * that reached that stage or went beyond it. A LOST deal is counted at the top only, because where it
   * fell out is not recorded.
   */
  const funnelSteps = useMemo(() => {
    if (!pipeline || pipeline.stages.length === 0) return []
    const rankOf = (value: string): number => {
      const index = FUNNEL_RANKS.indexOf(value)
      return index < 0 ? 0 : index
    }
    return FUNNEL_RANKS.map((each) => ({
      label: humanize(each),
      value: pipeline.stages.reduce(
        (sum, bucket) => (rankOf(bucket.stage) >= rankOf(each) ? sum + bucket.count : sum),
        0,
      ),
    }))
  }, [pipeline])

  const sortIsListed = SORT_OPTIONS.some((option) => option.value === sort)
  const narrowed = Boolean(stage || agentId || search.trim() || openOnly)

  const columns: Column<SaleSummaryRow>[] = [
    {
      key: 'code', header: 'Sale', width: '12%', sortKey: 'saleCode',
      render: (row) => (
        <span className="block min-w-0">
          <span className="block truncate font-medium tabular-nums" style={{ color: 'var(--text-primary)' }}>
            {row.saleCode}
          </span>
          <span className="block truncate text-[11px]" style={{ color: 'var(--text-secondary)' }}>
            opened {date(row.createdAt)}
          </span>
        </span>
      ),
    },
    {
      key: 'stock', header: 'Property / unit', width: '19%', sortKey: 'property',
      render: (row) => (
        <span className="block min-w-0">
          <span className="block truncate" style={{ color: 'var(--text-primary)' }}>
            {row.propertyName ?? 'Property not recorded'}
          </span>
          <span className="block truncate text-[11px]" style={{ color: 'var(--text-secondary)' }}>
            {row.unitCode ?? 'Whole property'}{row.propertyCode ? ` · ${row.propertyCode}` : ''}
          </span>
        </span>
      ),
    },
    {
      key: 'buyer', header: 'Buyer', width: '15%', sortKey: 'buyer',
      render: (row) => row.buyerName ?? 'Buyer not recorded',
    },
    {
      key: 'stage', header: 'Stage', width: '11%', sortKey: 'stage',
      render: (row) => <StatusBadge status={row.stage} />,
    },
    {
      key: 'prices', header: 'Asking / expected', width: '14%', align: 'right', sortKey: 'expectedPrice',
      render: (row) => (
        <span className="block tabular-nums">
          <span className="block text-[11px]" style={{ color: 'var(--text-secondary)' }}>
            asking {moneyCompact(row.askingPrice)}
          </span>
          <span className="block" style={{ color: 'var(--text-primary)' }}>
            expected {moneyCompact(row.expectedPrice)}
          </span>
          {row.agreedPrice !== null && (
            <span className="block text-[11px] font-semibold" style={{ color: 'var(--ink-emerald)' }}>
              agreed {moneyCompact(row.agreedPrice)}
            </span>
          )}
        </span>
      ),
    },
    {
      key: 'agent', header: 'Agent', width: '11%',
      render: (row) => row.ownerAgentName ?? 'Unassigned',
    },
    {
      key: 'expected-close', header: 'Expected close', width: '10%', align: 'right', sortKey: 'expectedCloseDate', secondary: true,
      render: (row) => date(row.expectedCloseDate),
    },
    {
      key: 'actions', header: 'Actions', width: '8%',
      render: (row) => {
        const moves = SALE_ALLOWED[row.stage] ?? []
        const permitted = canClose ? moves : moves.filter((move) => !CONCLUDED_STAGES.includes(move))
        return permitted.length > 0 ? (
          <Button size="sm" variant="ghost" onClick={() => setChanging(row)}>Change…</Button>
        ) : (
          <span className="text-[11px]" style={{ color: 'var(--text-secondary)' }}>
            {moves.length === 0 ? 'Concluded' : 'Needs SALE_CLOSE'}
          </span>
        )
      },
    },
  ]

  return (
    <>
      <PageHeader
        eyebrow="Capital pipeline"
        title="Sales"
        description="Every live opportunity the firm is negotiating, from first enquiry to completion or loss. A deal concludes once — completed at an agreed price, or lost for a reason."
        actions={
          <div
            role="group"
            aria-label="Sales view"
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
        }
      />

      {pipelineQuery.isLoading ? (
        <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
          {Array.from({ length: 4 }).map((_, index) => <Skeleton key={index} rows={3} />)}
        </div>
      ) : pipelineQuery.isError ? (
        <ErrorState message={messageOf(pipelineQuery.error)} onRetry={() => void pipelineQuery.refetch()} />
      ) : pipeline ? (
        <div className="mb-5 grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
          <KpiTile label="Opportunities" value={fmtCount(pipeline.totalSales)} tone="forest"
            caption="Every deal recorded, concluded ones included" />
          <KpiTile label="Live pipeline" value={fmtCount(pipeline.openSales)} tone="sapphire"
            icon={<TrendingUp size={15} />} caption="Not yet completed and not lost" />
          <KpiTile label="Expected pipeline value" value={moneyCompact(pipeline.openPipelineValue)} tone="copper"
            icon={<Banknote size={15} />}
            caption="Sums the expected price of the live deals — never the asking price" />
          <KpiTile label="Completed" value={fmtCount(stageCount('CLOSED'))} tone="emerald"
            caption={`${fmtCount(stageCount('LOST'))} deal(s) lost`} />
        </div>
      ) : (
        <EmptyState
          title="The pipeline could not be summarised"
          description="The pipeline read returned nothing to count. Open a sale against a property and the board, the funnel and these figures will fill in."
        />
      )}

      {/* ── The funnel and the stage breakdown ─────────────────────────────── */}
      <div className="mb-5 grid gap-4 xl:grid-cols-[1.6fr_1fr]">
        <Panel title="Conversion" subtitle="Deals that reached each stage" tint="forest">
          {pipelineQuery.isLoading ? (
            <Skeleton rows={6} />
          ) : funnelSteps.length === 0 ? (
            <EmptyState
              title="No deals to funnel yet"
              description="Each rung counts the deals that reached that stage or went past it, so the shape appears as soon as a sale is opened at the LEAD stage."
            />
          ) : (
            <Funnel steps={funnelSteps} title="Deals that reached each stage" />
          )}
        </Panel>
        <Panel
          title="Where the deals stand"
          subtitle={pipeline ? `Read at ${relative(pipeline.generatedAt)}` : 'Read live'}
          tint="champagne"
        >
          {pipelineQuery.isLoading ? (
            <Skeleton rows={6} />
          ) : (
            <ul className="space-y-2">
              {STAGE_ORDER.map((each) => {
                const value = stageCount(each)
                const total = pipeline?.stages.reduce((sum, bucket) => sum + bucket.count, 0) ?? 0
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
            Counts cover every deal, not the filters below — the board and the register are what the filters
            narrow.
          </p>
        </Panel>
      </div>

      {/* ── Filters ───────────────────────────────────────────────────────── */}
      <Panel className="mb-4" bodyClassName="p-0">
        <Toolbar>
          <div className="w-full sm:w-52">
            <Field label="Search" htmlFor="sale-search" hint="Code, property, unit, buyer or notes">
              <Input
                id="sale-search"
                type="search"
                value={search}
                onChange={(event) => { setSearch(event.target.value); setPage(0) }}
              />
            </Field>
          </div>
          <div className="w-full sm:w-44">
            <Field label="Stage" htmlFor="sale-stage">
              <Select id="sale-stage" value={stage} onChange={(event) => { setStage(event.target.value); setPage(0) }}>
                <option value="">Every stage</option>
                {STAGE_ORDER.map((each) => <option key={each} value={each}>{humanize(each)}</option>)}
              </Select>
            </Field>
          </div>
          <div className="w-full sm:w-52">
            <Field label="Owner agent" htmlFor="sale-agent" hint={agentsQuery.isError ? 'Agent list unavailable' : undefined}>
              <Select
                id="sale-agent"
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
          <div className="w-full sm:w-48">
            <Field label="Sort" htmlFor="sale-sort">
              <Select id="sale-sort" value={sort} onChange={(event) => setSort(event.target.value)}>
                {!sortIsListed && <option value={sort}>{describeSort(sort)}</option>}
                {SORT_OPTIONS.map((option) => (
                  <option key={option.value} value={option.value}>{option.label}</option>
                ))}
              </Select>
            </Field>
          </div>
          <label
            className="flex items-center gap-2 rounded-[var(--radius-control)] border px-3 py-2 text-xs"
            style={{ borderColor: 'var(--border)', color: 'var(--text-primary)' }}
            htmlFor="sale-open-only"
          >
            <input
              id="sale-open-only"
              type="checkbox"
              className="checkbox focus-meridian"
              checked={openOnly}
              onChange={(event) => { setOpenOnly(event.target.checked); setPage(0) }}
            />
            Live deals only
          </label>
        </Toolbar>
        <p className="px-4 py-2 text-xs" aria-live="polite" style={{ color: 'var(--text-secondary)' }}>
          {salesQuery.isLoading
            ? 'Reading the pipeline…'
            : `${fmtCount(salesQuery.data?.totalElements ?? 0)} deal(s) match these filters.`}
        </p>
      </Panel>

      {salesQuery.isLoading ? (
        <Panel><Skeleton rows={6} /></Panel>
      ) : salesQuery.isError ? (
        <ErrorState message={messageOf(salesQuery.error)} onRetry={() => void salesQuery.refetch()} />
      ) : rows.length === 0 ? (
        <Panel>
          <EmptyState
            icon={<ListFilter size={22} />}
            title={narrowed ? 'No deals match these filters' : 'No opportunity has been opened yet'}
            description={narrowed
              ? 'Every filter is narrowing the pipeline to nothing. Clear the stage, agent or live-only filter and the deals will come back.'
              : 'A sale is opened against a property — and optionally a unit — for a buyer. It starts at the LEAD stage and is moved on by the offers it attracts.'}
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
            caption="Sales pipeline register"
            empty={<EmptyState title="No deals on this page" description="Page back through the register." />}
          />
          <Pagination
            page={salesQuery.data?.page ?? 0}
            size={salesQuery.data?.size ?? size}
            totalElements={salesQuery.data?.totalElements ?? 0}
            totalPages={salesQuery.data?.totalPages ?? 0}
            hasNext={salesQuery.data?.hasNext ?? false}
            onPageChange={setPage}
            onSizeChange={(next) => { setSize(next); setPage(0) }}
          />
        </Panel>
      ) : (
        <>
          <section className="flex items-stretch gap-3 overflow-x-auto pb-2" aria-label="Sales pipeline board">
            {board.map((column) => (
              <section
                key={column.stage}
                role="group"
                tabIndex={0}
                aria-label={`${humanize(column.stage)}: ${column.cards.length} deal(s) on this board`}
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
                  <p
                    className="rounded-[var(--radius-card)] border px-2 py-3 text-[11px]"
                    style={{ borderColor: 'var(--border-forest)', borderStyle: 'dashed', color: 'var(--text-secondary)' }}
                  >
                    Nothing at this stage on the board.
                  </p>
                ) : (
                  <ul className="space-y-2">
                    {column.cards.map((sale) => {
                      const moves = SALE_ALLOWED[sale.stage] ?? []
                      const permitted = canClose ? moves : moves.filter((move) => !CONCLUDED_STAGES.includes(move))
                      return (
                        <li
                          key={sale.id}
                          className="rounded-[var(--radius-card)] border p-2.5"
                          style={{ borderColor: 'var(--border)', background: 'var(--surface-elevated)' }}
                        >
                          <span className="block truncate text-sm font-semibold" style={{ color: 'var(--text-primary)' }}>
                            {sale.propertyName ?? 'Property not recorded'}
                          </span>
                          <span className="block truncate text-[11px]" style={{ color: 'var(--text-secondary)' }}>
                            {sale.saleCode}{sale.unitCode ? ` · ${sale.unitCode}` : ''}
                          </span>
                          <span className="mt-1 block truncate text-xs" style={{ color: 'var(--text-primary)' }}>
                            {sale.buyerName ?? 'Buyer not recorded'}
                          </span>
                          <div className="mt-1.5 flex flex-wrap items-center gap-1.5">
                            <span className="text-xs font-semibold tabular-nums" style={{ color: 'var(--ink-copper)' }}>
                              {moneyCompact(sale.expectedPrice)}
                            </span>
                            {sale.agreedPrice !== null && (
                              <Badge tone="emerald">agreed {moneyCompact(sale.agreedPrice)}</Badge>
                            )}
                          </div>
                          <div className="mt-1.5 flex items-center justify-between gap-2">
                            <span className="min-w-0 truncate text-[11px]" style={{ color: 'var(--text-secondary)' }}>
                              {sale.ownerAgentName ?? 'Unassigned'}
                            </span>
                            {permitted.length > 0 ? (
                              <Button size="sm" variant="ghost" onClick={() => setChanging(sale)}>
                                Change stage
                              </Button>
                            ) : (
                              <span className="text-[11px]" style={{ color: 'var(--text-secondary)' }}>
                                {moves.length === 0 ? 'Concluded' : 'Needs SALE_CLOSE'}
                              </span>
                            )}
                          </div>
                        </li>
                      )
                    })}
                  </ul>
                )}
              </section>
            ))}
          </section>
          <p className="mt-2 text-[11px]" style={{ color: 'var(--text-secondary)' }}>
            A board, not the whole pipeline: the first {boardSize} deals matching the filters are laid out
            above ({fmtCount(salesQuery.data?.totalElements ?? 0)} match in total). Narrow the filters, or
            switch to the register table to page through them all.
          </p>
        </>
      )}

      <ChangeStageDialog
        key={changing?.id ?? 'no-sale'}
        sale={changing}
        canClose={canClose}
        onClose={() => setChanging(null)}
        onChanged={(code, to) => {
          void queryClient.invalidateQueries({ queryKey: ['sales'] })
          toast.push(
            `${code} moved to ${humanize(to)}.`,
            to === 'LOST' ? 'terracotta' : to === 'CLOSED' ? 'emerald' : 'teal',
          )
          setChanging(null)
        }}
      />
    </>
  )
}

/**
 * Move a deal between stages.
 *
 * The target list is `SaleService.ALLOWED[current]`, minus the concluded targets for an account without
 * `SALE_CLOSE` — the capability the service requires once the target is known. The price a completion
 * needs is collected in this same form, because the schema writes the stage and its outcome columns
 * together: a CLOSED row without an agreed price is refused as `SALE_AMOUNT_REQUIRED`, and a LOST row
 * without a reason is refused before that.
 */
function ChangeStageDialog({
  sale, canClose, onClose, onChanged,
}: {
  sale: SaleSummaryRow | null
  canClose: boolean
  onClose: () => void
  onChanged: (saleCode: string, to: string) => void
}) {
  const [target, setTarget] = useState('')
  const [reason, setReason] = useState('')
  const [agreedPrice, setAgreedPrice] = useState('')
  const [lostReason, setLostReason] = useState('')
  const [failure, setFailure] = useState<string | null>(null)

  const moves = sale ? SALE_ALLOWED[sale.stage] ?? [] : []
  const permitted = canClose ? moves : moves.filter((move) => !CONCLUDED_STAGES.includes(move))
  const chosen = target || permitted[0] || ''
  const needsPrice = chosen === 'CLOSED'
  const needsLostReason = chosen === 'LOST'

  const changeStage = useMutation({
    mutationFn: async () => saleApi.changeStage(sale?.id ?? '', {
      stage: chosen,
      reason: reason.trim() || null,
      // Only a completion carries an agreed price; only a loss carries a reason. Neither is accepted
      // on any other stage, because the schema requires every other stage to carry neither.
      agreedPrice: needsPrice && agreedPrice !== '' ? Number(agreedPrice) : null,
      lostReason: needsLostReason ? lostReason.trim() : null,
    }),
    onSuccess: (updated) => onChanged(
      updated.saleCode ?? sale?.saleCode ?? 'The deal',
      updated.stage ?? chosen,
    ),
    onError: (error) => setFailure(transitionFailure(error)),
  })

  const submit = () => {
    setFailure(null)
    if (!sale || !chosen) {
      setFailure('Choose the stage this deal should move to.')
      return
    }
    if (needsPrice && (agreedPrice === '' || Number(agreedPrice) <= 0)) {
      setFailure('A sale cannot complete without an agreed price. Give the price the deal closed at.')
      return
    }
    if (needsLostReason && lostReason.trim() === '') {
      setFailure('A lost sale must record why it was lost.')
      return
    }
    changeStage.mutate()
  }

  return (
    <Dialog
      open={sale !== null}
      onClose={onClose}
      title={sale ? `Change stage — ${sale.saleCode}` : 'Change a deal’s stage'}
      description={sale
        ? `Currently ${humanize(sale.stage)}. ${permitted.length} move(s) are permitted from here.`
        : undefined}
      width="sm"
      footer={
        <>
          <Button variant="ghost" onClick={onClose} disabled={changeStage.isPending}>Cancel</Button>
          <Button variant="primary" onClick={submit} loading={changeStage.isPending}>Move the deal</Button>
        </>
      }
    >
      <div className="space-y-3">
        {sale && (
          <dl className="grid grid-cols-2 gap-2 text-xs">
            <div>
              <dt className="eyebrow" style={{ color: 'var(--text-secondary)' }}>Property</dt>
              <dd className="truncate" style={{ color: 'var(--text-primary)' }}>{sale.propertyName ?? '—'}</dd>
            </div>
            <div>
              <dt className="eyebrow" style={{ color: 'var(--text-secondary)' }}>Buyer</dt>
              <dd className="truncate" style={{ color: 'var(--text-primary)' }}>{sale.buyerName ?? '—'}</dd>
            </div>
            <div>
              <dt className="eyebrow" style={{ color: 'var(--text-secondary)' }}>Expected price</dt>
              <dd className="tabular-nums" style={{ color: 'var(--text-primary)' }}>{money(sale.expectedPrice)}</dd>
            </div>
            <div>
              <dt className="eyebrow" style={{ color: 'var(--text-secondary)' }}>Opened</dt>
              <dd style={{ color: 'var(--text-primary)' }}>{dateTime(sale.createdAt)}</dd>
            </div>
          </dl>
        )}

        <Field
          label="Move to"
          htmlFor="sale-stage-target"
          required
          hint={permitted.length === 0
            ? moves.length === 0
              ? 'A concluded deal is not reopened.'
              : 'Concluding a deal needs the SALE_CLOSE permission, which your account does not hold.'
            : `Permitted from ${humanize(sale?.stage ?? '')}: ${permitted.map((move) => humanize(move)).join(', ')}.`}
        >
          <Select
            id="sale-stage-target"
            value={chosen}
            disabled={permitted.length === 0}
            onChange={(event) => { setTarget(event.target.value); setFailure(null) }}
          >
            {permitted.length === 0 && <option value="">No move is permitted</option>}
            {permitted.map((each) => <option key={each} value={each}>{humanize(each)}</option>)}
          </Select>
        </Field>

        {needsPrice && (
          <Field
            label="Agreed price"
            htmlFor="sale-agreed-price"
            required
            hint="Completion is a price agreed with a buyer — the schema refuses a completed deal without one."
          >
            <Input
              id="sale-agreed-price"
              inputMode="decimal"
              value={agreedPrice}
              onChange={(event) => setAgreedPrice(event.target.value)}
            />
          </Field>
        )}

        {needsLostReason && (
          <Field
            label="Why it was lost"
            htmlFor="sale-lost-reason"
            required
            hint="A lost deal records a moment and a reason, and carries no agreed price."
          >
            <Input
              id="sale-lost-reason"
              value={lostReason}
              onChange={(event) => setLostReason(event.target.value)}
            />
          </Field>
        )}

        <Field label="Reason" htmlFor="sale-stage-reason" hint="Recorded on the stage trail">
          <Input id="sale-stage-reason" value={reason} onChange={(event) => setReason(event.target.value)} />
        </Field>

        <div aria-live="polite">
          {failure && <p role="alert" className="text-sm" style={{ color: 'var(--danger)' }}>{failure}</p>}
        </div>
      </div>
    </Dialog>
  )
}
