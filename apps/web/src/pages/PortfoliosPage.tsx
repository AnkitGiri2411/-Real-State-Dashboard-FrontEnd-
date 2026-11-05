import { useQueries, useQuery } from '@tanstack/react-query'
import { PageAccessGuard, PageHeader } from '../components/common'
import {
  DetailItem, EmptyState, ErrorState, Panel, SectionHeader, Skeleton, StatusBadge, TONE_INK,
  type Tone,
} from '../components/ui'
import { ComparisonBars, OccupancyRing, type SeriesPoint } from '../components/ui/charts'
import { count as fmtCount, humanize, money, moneyCompact, percent } from '../components/ui/formatters'
import { portfolioApi, type PageParams } from '../services'
import { messageOf } from '../lib/errors'
import type { PortfolioPerformance, PortfolioSummary } from '../types'

/**
 * The portfolio register, as an investment view.
 *
 * A book of assets is not a row in a table - it is a position, read for what it is worth and what it
 * returns - so this screen is a grid of plot cards, each carrying its own performance projection and
 * each measured the same way. `portfolioApi.performance(id)` is called once per book: one call per
 * position is what keeps a book's figures internally consistent, which a single flattened aggregate
 * over every book cannot promise.
 *
 * **The single most misread number on a page like this is a yield.** A book is managed against a
 * *target* yield and it *achieves* one; the two sit side by side and are never interchangeable. So on
 * every card and in the headline block the achieved figure is the large one, the target is labelled
 * "Target yield" and set in the quiet ink, and the basis of the achieved figure is written underneath
 * it. A reader scanning the page cannot come away thinking the target was earned.
 */
export default function PortfoliosPage() {
  return (
    <PageAccessGuard permission="PORTFOLIO_VIEW">
      <PortfolioRegister />
    </PageAccessGuard>
  )
}

function PortfolioRegister() {
  const listParams: PageParams = { size: 100, sort: 'name,asc' }
  const listQuery = useQuery({
    queryKey: ['portfolios', listParams],
    queryFn: () => portfolioApi.list(listParams),
  })

  const rows = listQuery.data?.items ?? []

  /*
   * One performance call per book, keyed by the book's own id.
   *
   * `useQueries` rather than a query per card because the comparison chart and the headline block read
   * the same figures the cards do - so one cache entry per book serves all three, and opening the page
   * costs one request per book rather than three.
   */
  const performanceQueries = useQueries({
    queries: rows.map((row) => ({
      queryKey: ['portfolio', row.id, 'performance'],
      queryFn: () => portfolioApi.performance(row.id),
    })),
  })

  // Each card is drawn from its own query's state, so one book that failed to report does not take the
  // rest of the page down with it - and the retry belongs to the book it failed for.
  const cards: BookCard[] = rows.map((row, index) => {
    const result = performanceQueries[index]
    return {
      row,
      performance: result?.data ?? null,
      loading: result?.isLoading ?? false,
      hasError: result?.isError ?? false,
      error: result?.error ?? null,
      retry: () => { void result?.refetch() },
    }
  })

  /*
   * The headline book.
   *
   * Defined as the book with the largest valuation - a rule stated in the copy rather than left to the
   * reader to infer. If no book carries a valuation yet (a register of development land is a real
   * state), the first book is used and the occupancy panel says so in its own words.
   */
  let headline: BookCard | null = cards.length > 0 ? cards[0] : null
  let headlineValuation: number | null = headline?.performance?.totalValuation ?? null
  for (const card of cards) {
    const valuation = card.performance?.totalValuation
    if (typeof valuation !== 'number') continue
    if (headlineValuation === null || valuation > headlineValuation) {
      headline = card
      headlineValuation = valuation
    }
  }

  // Only books whose valuation the API actually returned can be compared. A missing figure is absent
  // from the comparison rather than drawn as a zero-length bar pretending to be a measurement.
  const valuationPoints: SeriesPoint[] = []
  for (const card of cards) {
    const valuation = card.performance?.totalValuation
    if (typeof valuation !== 'number' || valuation <= 0) continue
    valuationPoints.push({
      label: card.row.name,
      value: valuation,
      tone: toneForStrategy(card.row.strategy),
    })
  }

  const totalBooks = listQuery.data?.totalElements ?? 0

  if (listQuery.isLoading) {
    return (
      <>
        <PageHeader title="Portfolios" description="Reading the books of assets…" />
        <Skeleton rows={6} />
      </>
    )
  }

  if (listQuery.isError) {
    return (
      <>
        <PageHeader title="Portfolios" />
        <ErrorState message={messageOf(listQuery.error)} onRetry={() => void listQuery.refetch()} />
      </>
    )
  }

  if (cards.length === 0) {
    return (
      <>
        <PageHeader title="Portfolios" />
        <EmptyState
          title="No portfolios are recorded"
          description="A portfolio groups properties into one book so its value, rent roll, net operating income and yield can be reported together. None have been set up yet, so there is no position to read here."
        />
      </>
    )
  }

  return (
    <>
      <PageHeader
        eyebrow="Investment"
        title="Portfolios"
        description={`Each book of assets, with what it holds and what it returns. Every figure is the book's own performance projection, read one call per book. ${fmtCount(totalBooks)} books are recorded.`}
      />

      <p className="sr-only" role="status" aria-live="polite">
        {performanceQueries.some((query) => query.isLoading)
          ? 'Loading each book\u2019s performance.'
          : `${fmtCount(totalBooks)} books loaded.`}
      </p>

      {/* ── The headline book ─────────────────────────────────────────────── */}
      {headline && <HeadlineBook card={headline} />}

      {/* ── Every book, as a position ─────────────────────────────────────── */}
      <SectionHeader
        eyebrow="The books"
        title="Every portfolio"
        rule="forest"
        description="One card per book: what it holds, what it is worth, what it earns, and the return it achieved against the return it targets."
      />

      <div className="mb-6 grid gap-4 md:grid-cols-2 xl:grid-cols-3">
        {cards.map((card) => (
          <Panel
            key={card.row.id}
            notch
            tint={toneForStrategy(card.row.strategy)}
            title={card.row.name}
            subtitle={`${card.row.portfolioCode} · ${humanize(card.row.strategy)}`}
            actions={<StatusBadge status={card.row.status} />}
          >
            {card.loading ? (
              <Skeleton rows={6} />
            ) : card.hasError ? (
              <ErrorState message={messageOf(card.error)} onRetry={card.retry} />
            ) : card.performance ? (
              <>
                <dl className="grid grid-cols-2 gap-x-4 gap-y-3">
                  <DetailItem label="Properties" value={fmtCount(card.performance.propertyCount)} />
                  <DetailItem
                    label="Occupancy"
                    value={percent(card.performance.occupancyPercent, 1)}
                    tone="emerald"
                  />
                  <DetailItem label="Valuation" value={moneyCompact(card.performance.totalValuation)} tone="copper" />
                  <DetailItem label="Monthly rent roll" value={money(card.performance.monthlyRentRoll)} tone="copper" />
                  <DetailItem label="Net operating income" value={money(card.performance.netOperatingIncome)} />
                  <DetailItem
                    label="Acquisition cost"
                    value={moneyCompact(card.performance.totalAcquisitionValue)}
                  />
                </dl>
                <div className="mt-4 border-t pt-3" style={{ borderColor: 'var(--border)' }}>
                  <YieldReading performance={card.performance} />
                </div>
              </>
            ) : (
              <EmptyState
                title="This book reports no performance"
                description="A book's performance is computed from the properties currently allocated to it. With none allocated there is nothing to report, so no figure is shown rather than an estimate."
              />
            )}
          </Panel>
        ))}
      </div>

      {/* ── How the books compare ─────────────────────────────────────────── */}
      <SectionHeader
        eyebrow="Comparison"
        title="Valuation across the books"
        rule="sandstone"
        description="Each book's valuation against the others, so a position can be read at a glance. A book with no valuation recorded is left out rather than drawn at zero."
      />
      <Panel tint="sandstone">
        {valuationPoints.length === 0 ? (
          <EmptyState
            title="No books carry a valuation yet"
            description="These bars compare what the books are worth. A book appears here once the properties allocated to it carry a current valuation."
          />
        ) : (
          <ComparisonBars points={valuationPoints} title="Portfolio valuation" unit="currency" />
        )}
      </Panel>

      {listQuery.data && totalBooks > rows.length && (
        // The register is read one page at its maximum size. Said plainly, so a reader who counts the
        // cards does not conclude that the rest of the books do not exist.
        <p className="mt-4 text-[11px]" style={{ color: 'var(--text-secondary)' }}>
          {`Showing ${fmtCount(rows.length)} of ${fmtCount(totalBooks)} books. The register above lists the remainder.`}
        </p>
      )}
    </>
  )
}

/* ============================================================
 * The headline book
 * ============================================================ */

/**
 * The book the page leads with.
 *
 * A component rather than an inline block so the card it is handed is a fact of its props rather than
 * a value the renderer has to keep proving - and so the ring and the yield reading, which answer the
 * two questions a book is held to, sit together above the register.
 */
function HeadlineBook({ card }: { card: BookCard }) {
  const occupancy = card.performance?.occupancyPercent ?? null
  const occupancyTarget = card.performance?.occupancyTargetPercent ?? null

  return (
    <>
      <SectionHeader
        eyebrow="Headline"
        title={card.row.name}
        rule="copper"
        description="The book with the largest valuation on this register. Its occupancy is measured against the firm's own occupancy target."
      />
      <div className="mb-6 grid gap-4 lg:grid-cols-[1fr_1.4fr]">
        <Panel
          title="Occupancy"
          subtitle={`${card.row.portfolioCode} · ${humanize(card.row.strategy)}`}
          tint="emerald"
        >
          {card.loading ? (
            <Skeleton rows={6} />
          ) : card.hasError ? (
            <ErrorState message={messageOf(card.error)} onRetry={card.retry} />
          ) : occupancy === null ? (
            <EmptyState
              title="This book reports no occupancy"
              description="Occupancy is measured over the leasable units in the book's properties. With none recorded there is nothing to measure, so no figure is shown rather than a zero."
            />
          ) : (
            <OccupancyRing
              value={occupancy}
              display={percent(occupancy, 1)}
              caption="Occupancy"
              tone="emerald"
              target={occupancyTarget ?? undefined}
            />
          )}
        </Panel>

        <Panel
          title="What this book returns"
          subtitle="The achieved figure is derived; the target is what the book is managed against"
          tint="copper"
        >
          {card.loading ? (
            <Skeleton rows={6} />
          ) : card.hasError ? (
            <ErrorState message={messageOf(card.error)} onRetry={card.retry} />
          ) : card.performance ? (
            <YieldReading performance={card.performance} large />
          ) : (
            <EmptyState
              title="This book reports no performance yet"
              description="A book's performance is computed from the properties allocated to it. Allocate an asset and its valuation, rent and yield will be reported here."
            />
          )}
        </Panel>
      </div>
    </>
  )
}

/* ============================================================
 * The yield reading
 * ============================================================ */

/**
 * The return a book achieved, and the return it targets.
 *
 * Deliberately two labelled figures rather than one: the achieved yield is the large copper figure -
 * copper is this product's colour for money - and the target sits beside it in the quiet ink, named as
 * a target. The basis of the achieved figure is written underneath it, so the number is never left to
 * be interpreted.
 */
function YieldReading({ performance, large = false }: { performance: PortfolioPerformance; large?: boolean }) {
  const achieved = performance.achievedYieldPercent
  const target = performance.targetYieldPercent

  return (
    <div>
      <div className="flex flex-wrap items-end justify-between gap-3">
        <div className="min-w-0">
          <p className="eyebrow" style={{ color: 'var(--text-secondary)' }}>Achieved yield</p>
          <p
            className={large ? 'kpi-value text-3xl' : 'kpi-value text-xl'}
            style={{ color: TONE_INK.copper }}
          >
            {percent(achieved, 2)}
          </p>
        </div>
        <div className="min-w-0 text-right">
          <p className="eyebrow" style={{ color: 'var(--text-secondary)' }}>Target yield</p>
          <p className={`kpi-value ${large ? 'text-xl' : 'text-base'}`} style={{ color: 'var(--text-secondary)' }}>
            {target === null ? 'Not set' : percent(target, 2)}
          </p>
        </div>
      </div>
      <p className="mt-2 text-[11px] leading-relaxed" style={{ color: 'var(--text-secondary)' }}>
        {performance.yieldBasis ?? 'Annualised rent roll over current valuation'} — the achieved figure
        above. The target is the return this book is managed against, not the return it earned.
      </p>
      {large && (
        <dl className="mt-4 grid grid-cols-2 gap-x-4 gap-y-3 border-t pt-3" style={{ borderColor: 'var(--border)' }}>
          <DetailItem label="Valuation" value={moneyCompact(performance.totalValuation)} tone="copper" />
          <DetailItem label="Monthly rent roll" value={money(performance.monthlyRentRoll)} tone="copper" />
          <DetailItem label="Net operating income" value={money(performance.netOperatingIncome)} />
          <DetailItem label="Properties" value={fmtCount(performance.propertyCount)} />
        </dl>
      )}
    </div>
  )
}

/* ============================================================
 * The card, and the tone a book's strategy carries
 * ============================================================ */

/**
 * One book, and its own performance read.
 *
 * Both records come from the API map as the endpoints declare them: `PortfolioDtos.PortfolioSummary` for
 * the register row and `PortfolioDtos.PortfolioPerformance` for the projection. Nothing here is probed
 * for by name out of an untyped map, and `performance` is `null` only while its own request is in
 * flight or has failed - which is what the card's own loading and error states report.
 */
interface BookCard {
  row: PortfolioSummary
  performance: PortfolioPerformance | null
  /** The book's own query state, so a card reports its own loading and its own failure. */
  loading: boolean
  hasError: boolean
  error: unknown
  retry: () => void
}

/**
 * The tone a book's strategy carries.
 *
 * Colour here is information, not decoration: the strategy is what a book *is* - core income, value
 * add, development - and giving each one a fixed identity means a reader learns the register's shape
 * without reading a label. The tones are the design system's own, never a literal.
 */
const STRATEGY_TONES: Record<string, Tone> = {
  CORE: 'forest',
  CORE_PLUS: 'architectural',
  VALUE_ADD: 'copper',
  OPPORTUNISTIC: 'amber',
  DEVELOPMENT: 'sandstone',
  INCOME: 'teal',
  TRADING: 'plum',
}

function toneForStrategy(strategy: string | null): Tone {
  return (strategy !== null ? STRATEGY_TONES[strategy] : undefined) || 'slate'
}
