import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Handshake, ListFilter } from 'lucide-react'
import { useMemo, useState } from 'react'
import { Link } from 'react-router-dom'
import { PageAccessGuard, PageHeader } from '../components/common'
import {
  Badge, Button, DataTable, Dialog, EmptyState, ErrorState, Field, Input, KpiTile, Panel,
  Pagination, Select, Skeleton, StatusBadge, Textarea, Toolbar, useToast, type Column,
} from '../components/ui'
import { count as fmtCount, date, dateTime, humanize, money, moneyCompact } from '../components/ui/formatters'
import { offerApi, saleApi } from '../services'
import { ApiError, messageOf } from '../lib/errors'
import type { OfferEntry, SaleSummaryRow } from '../types'

/**
 * The offer register: every step of every negotiation, across the pipeline.
 *
 * **The rule this screen exists to make visible** is the schema's partial unique index
 * `uq_offers_accepted_per_sale`: at most one live accepted offer per sale. The index can only see rows
 * that are already accepted, so the service reads the sale for an existing acceptance first and refuses
 * the second with `OFFER_ALREADY_ACCEPTED` (409). Two things follow, and both are done here:
 *
 *  - the refusal is surfaced **as its own sentence**, not as a generic conflict;
 *  - where the loaded rows already show a sale with an accepted offer, the accept action on that sale's
 *    other offers is disabled and the reason is written beside it, so the reader does not discover the
 *    rule by failing.
 *
 * The page can only see the rows it has loaded, so the disabled state is a courtesy and the API's own
 * refusal remains the last word.
 *
 * Only an unanswered offer can be decided (`SaleService.canDecideOffer`): a counter is a *new* offer in
 * the other direction rather than an amendment, which is why `COUNTER` asks for the amount the firm is
 * putting forward and why a decided offer offers no further action at all.
 */

const OFFER_STATUSES = ['SUBMITTED', 'COUNTERED', 'ACCEPTED', 'REJECTED', 'WITHDRAWN', 'EXPIRED']
const OFFER_DIRECTIONS = ['BUYER', 'FIRM']
const OFFER_ACTIONS = ['ACCEPT', 'COUNTER', 'REJECT', 'WITHDRAW'] as const
type OfferAction = (typeof OFFER_ACTIONS)[number]

const SORT_OPTIONS = [
  { value: 'offeredAt,desc', label: 'Most recent first' },
  { value: 'offeredAt,asc', label: 'Oldest first' },
  { value: 'amount,desc', label: 'Amount, high to low' },
  { value: 'status,asc', label: 'Status' },
  { value: 'direction,asc', label: 'Direction' },
  { value: 'offerNumber,asc', label: 'Offer number' },
]

/*
 * `SaleDtos.OfferEntry` is the offer read - the offer's own number, amount, direction, status and
 * response, and the **sale's id** with nothing about the asset itself. It is declared in `src/types`
 * from the DTO, so the table below reads it by name; an offer that names a sale not on the loaded page
 * of the sale register is named as such rather than left blank.
 */
function describeSort(sort: string): string {
  const [field, direction] = sort.split(',')
  return `Sorted by ${humanize(field).toLowerCase()} · ${direction === 'desc' ? 'descending' : 'ascending'}`
}

/**
 * The sentence a refused decision deserves.
 *
 * `OFFER_ALREADY_ACCEPTED` is the 409 the whole screen is arranged around; `OFFER_ALREADY_DECIDED` is
 * the other refusal a reader can act on. Anything else is passed through as the API worded it.
 */
function decisionFailure(error: unknown): string {
  if (error instanceof ApiError) {
    if (error.code === 'OFFER_ALREADY_ACCEPTED') {
      return 'This sale already has an accepted offer. Accepting an offer ends the negotiation, so no second offer on the same sale can be accepted'
    }
    if (error.code === 'OFFER_ALREADY_DECIDED') {
      return 'That offer has already been decided. A counter is a new offer rather than an amendment, so the original is left as it stands'
    }
    if (error.isConflict) return messageOf(error)
  }
  return messageOf(error)
}

export default function OffersPage() {
  return (
    <PageAccessGuard permission="SALE_VIEW">
      <OfferRegister />
    </PageAccessGuard>
  )
}

function OfferRegister() {
  const queryClient = useQueryClient()
  const toast = useToast()

  const [page, setPage] = useState(0)
  const [size, setSize] = useState(20)
  const [sort, setSort] = useState('offeredAt,desc')
  const [saleId, setSaleId] = useState('')
  const [status, setStatus] = useState('')
  const [direction, setDirection] = useState('')
  const [deciding, setDeciding] = useState<OfferEntry | null>(null)

  const offersQuery = useQuery({
    queryKey: ['offers', 'register', { page, size, sort, saleId, status, direction }],
    queryFn: () => offerApi.list({
      page,
      size,
      sort,
      saleId: saleId || undefined,
      status: status || undefined,
      direction: direction || undefined,
    }),
  })

  /*
   * An offer carries the sale's id and nothing else about the asset, so the sale register is read
   * alongside it: the sale code, the property and the buyer are the deal's, and reading them from the
   * sale is the only honest way to show them.
   */
  const salesQuery = useQuery({
    queryKey: ['sales', 'picker'],
    queryFn: () => saleApi.list({ size: 200 }),
  })

  const salesById = useMemo(() => {
    const map = new Map<string, SaleSummaryRow>()
    for (const sale of salesQuery.data?.items ?? []) map.set(sale.id, sale)
    return map
  }, [salesQuery.data])

  const rows = offersQuery.data?.items ?? []

  /**
   * The sales that already show an accepted offer among the rows loaded.
   *
   * This is the one rule of the offer table that is a *set* rather than a field, so it is computed from
   * the offers themselves — `ACCEPTED` is what the partial unique index hangs on.
   */
  const acceptedSaleIds = useMemo(() => {
    const set = new Set<string>()
    for (const row of rows) {
      if (row.status === 'ACCEPTED' && row.saleId) set.add(row.saleId)
    }
    return set
  }, [rows])

  const submittedCount = rows.filter((row) => row.status === 'SUBMITTED').length
  const acceptedCount = rows.filter((row) => row.status === 'ACCEPTED').length
  const pageValue = rows.reduce((sum, row) => sum + (row.amount ?? 0), 0)
  const narrowed = Boolean(saleId || status || direction)
  const sortIsListed = SORT_OPTIONS.some((option) => option.value === sort)

  const columns: Column<OfferEntry>[] = [
    {
      key: 'number', header: 'Offer', width: '12%', sortKey: 'offerNumber',
      render: (row) => (
        <span className="block min-w-0">
          <span className="block truncate font-medium tabular-nums" style={{ color: 'var(--text-primary)' }}>
            {row.offerNumber}
          </span>
          {row.expiresAt && (
            <span className="block truncate text-[11px]" style={{ color: 'var(--text-secondary)' }}>
              expires {date(row.expiresAt)}
            </span>
          )}
        </span>
      ),
    },
    {
      key: 'sale', header: 'Sale', width: '13%',
      render: (row) => {
        const sale = row.saleId ? salesById.get(row.saleId) : undefined
        // A sale not on the loaded page of the register is named honestly rather than left blank.
        return sale
          ? <Link to="/sales" className="focus-meridian tabular-nums" style={{ color: 'var(--ink-forest)' }}>{sale.saleCode}</Link>
          : <span className="tabular-nums" style={{ color: 'var(--text-secondary)' }}>Not on this page</span>
      },
    },
    {
      key: 'property', header: 'Property / unit', width: '19%',
      render: (row) => {
        const sale = row.saleId ? salesById.get(row.saleId) : undefined
        return (
          <span className="block min-w-0">
            <span className="block truncate" style={{ color: 'var(--text-primary)' }}>
              {sale?.propertyName ?? '—'}
            </span>
            <span className="block truncate text-[11px]" style={{ color: 'var(--text-secondary)' }}>
              {sale?.unitCode ?? (sale ? 'Whole property' : 'Read from the sale register')}
            </span>
          </span>
        )
      },
    },
    {
      key: 'buyer', header: 'Buyer', width: '14%',
      render: (row) => (row.saleId ? salesById.get(row.saleId)?.buyerName ?? '—' : '—'),
    },
    {
      key: 'amount', header: 'Amount', width: '13%', align: 'right', sortKey: 'amount',
      render: (row) => <span className="tabular-nums">{money(row.amount)}</span>,
    },
    {
      key: 'direction', header: 'Direction', width: '10%', sortKey: 'direction',
      render: (row) => <Badge tone={row.direction === 'FIRM' ? 'copper' : 'sapphire'}>{humanize(row.direction)}</Badge>,
    },
    {
      key: 'status', header: 'Status', width: '12%', sortKey: 'status',
      render: (row) => (
        <span className="flex flex-wrap items-center gap-1.5">
          <StatusBadge status={row.status} />
          {/* The rule, said in words beside the offer it constrains. */}
          {row.status === 'SUBMITTED' && row.saleId && acceptedSaleIds.has(row.saleId) && (
            <Badge tone="amber">Sale already accepted</Badge>
          )}
        </span>
      ),
    },
    {
      key: 'offered', header: 'Offered', width: '12%', align: 'right', sortKey: 'offeredAt', secondary: true,
      render: (row) => (
        <span className="block tabular-nums">
          <span className="block" style={{ color: 'var(--text-primary)' }}>{date(row.offeredAt)}</span>
          {row.respondedByName && (
            <span className="block text-[11px]" style={{ color: 'var(--text-secondary)' }}>
              answered by {row.respondedByName}
            </span>
          )}
        </span>
      ),
    },
    {
      key: 'actions', header: 'Actions', width: '11%',
      render: (row) => (
        row.status === 'SUBMITTED'
          ? <Button size="sm" onClick={() => setDeciding(row)}>Decide…</Button>
          : <span className="text-[11px]" style={{ color: 'var(--text-secondary)' }}>Decided</span>
      ),
    },
  ]

  return (
    <>
      <PageHeader
        eyebrow="Capital"
        title="Offers"
        description="Every offer made across the pipeline, in the order it was made. One acceptance ends a negotiation, so a sale can carry only one accepted offer."
      />

      <div className="mb-5 grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
        <KpiTile label="Offers in the register" value={fmtCount(offersQuery.data?.totalElements ?? 0)} tone="copper"
          icon={<Handshake size={15} />} caption="Match the filters above" />
        <KpiTile label="Awaiting a decision" value={fmtCount(submittedCount)} tone="sapphire"
          caption="On this page, still unanswered" />
        <KpiTile label="Accepted" value={fmtCount(acceptedCount)} tone="emerald"
          caption="On this page — one per sale at most" />
        <KpiTile label="Value on this page" value={moneyCompact(pageValue)} tone="forest"
          caption="Summed across the loaded page" />
      </div>

      <Panel className="mb-4" bodyClassName="p-0">
        <Toolbar>
          <div className="w-full sm:w-72">
            <Field
              label="Sale"
              htmlFor="offer-sale"
              hint={salesQuery.isError ? 'Sale register unavailable' : 'The deal the offer was made on'}
            >
              <Select
                id="offer-sale"
                value={saleId}
                disabled={salesQuery.isLoading || salesQuery.isError}
                onChange={(event) => { setSaleId(event.target.value); setPage(0) }}
              >
                <option value="">Every sale</option>
                {(salesQuery.data?.items ?? []).map((sale) => (
                  <option key={sale.id} value={sale.id}>
                    {sale.saleCode} · {sale.propertyName ?? 'property not recorded'}
                    {sale.buyerName ? ` · ${sale.buyerName}` : ''}
                  </option>
                ))}
              </Select>
            </Field>
          </div>
          <div className="w-full sm:w-40">
            <Field label="Status" htmlFor="offer-status">
              <Select id="offer-status" value={status} onChange={(event) => { setStatus(event.target.value); setPage(0) }}>
                <option value="">Every status</option>
                {OFFER_STATUSES.map((each) => <option key={each} value={each}>{humanize(each)}</option>)}
              </Select>
            </Field>
          </div>
          <div className="w-full sm:w-40">
            <Field label="Direction" htmlFor="offer-direction" hint="Who put the offer forward">
              <Select id="offer-direction" value={direction} onChange={(event) => { setDirection(event.target.value); setPage(0) }}>
                <option value="">Both directions</option>
                {OFFER_DIRECTIONS.map((each) => <option key={each} value={each}>{humanize(each)}</option>)}
              </Select>
            </Field>
          </div>
          <div className="w-full sm:w-48">
            <Field label="Sort" htmlFor="offer-sort">
              <Select id="offer-sort" value={sort} onChange={(event) => setSort(event.target.value)}>
                {!sortIsListed && <option value={sort}>{describeSort(sort)}</option>}
                {SORT_OPTIONS.map((option) => (
                  <option key={option.value} value={option.value}>{option.label}</option>
                ))}
              </Select>
            </Field>
          </div>
        </Toolbar>
        <p className="px-4 py-2 text-xs" aria-live="polite" style={{ color: 'var(--text-secondary)' }}>
          {offersQuery.isLoading
            ? 'Reading the offer register…'
            : `${fmtCount(offersQuery.data?.totalElements ?? 0)} offer(s) match these filters.`}
        </p>
      </Panel>

      {offersQuery.isLoading ? (
        <Panel><Skeleton rows={6} /></Panel>
      ) : offersQuery.isError ? (
        <ErrorState message={messageOf(offersQuery.error)} onRetry={() => void offersQuery.refetch()} />
      ) : (
        <Panel bodyClassName="p-0">
          <DataTable
            columns={columns}
            rows={rows}
            keyOf={(row) => row.id}
            sort={sort}
            onSortChange={setSort}
            caption="Offer register"
            empty={
              <EmptyState
                icon={<ListFilter size={22} />}
                title={narrowed ? 'No offers match these filters' : 'No offer has been made yet'}
                description={narrowed
                  ? 'The sale, status or direction is narrowing the register to nothing. Clear the status and the offers will come back.'
                  : 'An offer is recorded against a sale — by the buyer, or by the firm as a counter. None has been recorded yet.'}
              />
            }
          />
          {rows.length > 0 && (
            <Pagination
              page={offersQuery.data?.page ?? 0}
              size={offersQuery.data?.size ?? size}
              totalElements={offersQuery.data?.totalElements ?? 0}
              totalPages={offersQuery.data?.totalPages ?? 0}
              hasNext={offersQuery.data?.hasNext ?? false}
              onPageChange={setPage}
              onSizeChange={(next) => { setSize(next); setPage(0) }}
            />
          )}
        </Panel>
      )}

      <DecideOfferDialog
        key={deciding?.id ?? 'no-offer'}
        offer={deciding}
        saleAlreadyAccepted={Boolean(deciding?.saleId && acceptedSaleIds.has(deciding.saleId))}
        onClose={() => setDeciding(null)}
        onDecided={(number, action) => {
          void queryClient.invalidateQueries({ queryKey: ['offers'] })
          // An acceptance moves the sale's stage with it, so the sale register is stale too.
          void queryClient.invalidateQueries({ queryKey: ['sales'] })
          toast.push(
            `${number} was ${action === 'ACCEPT' ? 'accepted' : action === 'COUNTER' ? 'countered' : action === 'REJECT' ? 'rejected' : 'withdrawn'}.`,
            action === 'ACCEPT' ? 'emerald' : 'terracotta',
          )
          setDeciding(null)
        }}
      />
    </>
  )
}

/**
 * Decide one offer.
 *
 * The action list is the four the service accepts, and each asks for the thing it needs: a counter
 * needs the amount the firm is putting forward, a rejection or a withdrawal takes a note, and an
 * acceptance is offered only where the sale does not already show an accepted offer among the loaded
 * rows.
 */
function DecideOfferDialog({
  offer, saleAlreadyAccepted, onClose, onDecided,
}: {
  offer: OfferEntry | null
  saleAlreadyAccepted: boolean
  onClose: () => void
  onDecided: (offerNumber: string, action: OfferAction) => void
}) {
  const [action, setAction] = useState<OfferAction>('ACCEPT')
  const [counterAmount, setCounterAmount] = useState('')
  const [notes, setNotes] = useState('')
  const [failure, setFailure] = useState<string | null>(null)

  const decide = useMutation({
    mutationFn: async () => offerApi.decide(offer?.id ?? '', {
      action,
      // Only a counter carries an amount: the original offer is marked COUNTERED and a new offer is
      // recorded in the other direction.
      counterAmount: action === 'COUNTER' && counterAmount !== '' ? Number(counterAmount) : null,
      responseNotes: notes.trim() || null,
    }),
    onSuccess: () => onDecided(offer?.offerNumber ?? 'The offer', action),
    onError: (error) => setFailure(decisionFailure(error)),
  })

  const submit = () => {
    setFailure(null)
    if (!offer) return
    if (action === 'ACCEPT' && saleAlreadyAccepted) {
      setFailure('This sale already has an accepted offer. Accepting an offer ends the negotiation, so no second offer on the same sale can be accepted.')
      return
    }
    if (action === 'COUNTER' && (counterAmount === '' || Number(counterAmount) <= 0)) {
      setFailure('A counter must name the amount the firm is putting forward.')
      return
    }
    decide.mutate()
  }

  return (
    <Dialog
      open={offer !== null}
      onClose={onClose}
      title={offer ? `Decide ${offer.offerNumber}` : 'Decide an offer'}
      description={offer
        ? `${humanize(offer.direction)} offer of ${money(offer.amount)}, made ${dateTime(offer.offeredAt)}.`
        : undefined}
      width="md"
      footer={
        <>
          <Button variant="ghost" onClick={onClose} disabled={decide.isPending}>Cancel</Button>
          <Button variant="primary" onClick={submit} loading={decide.isPending}>Record the decision</Button>
        </>
      }
    >
      <div className="space-y-3">
        <Field
          label="Decision"
          htmlFor="offer-action"
          required
          hint="An offer is decided once. A counter is a new offer in the other direction rather than an amendment of this one."
        >
          <Select
            id="offer-action"
            value={action}
            onChange={(event) => { setAction(event.target.value as OfferAction); setFailure(null) }}
          >
            {OFFER_ACTIONS.map((each) => (
              <option key={each} value={each} disabled={each === 'ACCEPT' && saleAlreadyAccepted}>
                {humanize(each)}{each === 'ACCEPT' && saleAlreadyAccepted ? ' — the sale already has an accepted offer' : ''}
              </option>
            ))}
          </Select>
        </Field>

        {action === 'ACCEPT' && (
          <p
            className="rounded-[var(--radius-card)] border px-3 py-2 text-xs"
            style={{
              borderColor: 'var(--border)',
              background: saleAlreadyAccepted ? 'var(--surface-amber)' : 'var(--surface-emerald)',
              color: 'var(--text-primary)',
            }}
          >
            {saleAlreadyAccepted
              ? 'This sale already shows an accepted offer. A sale carries one accepted offer, so accepting this one will be refused — read the sale before deciding.'
              : 'Accepting this offer marks it answered and moves the sale to under contract in the same transaction.'}
          </p>
        )}

        {action === 'COUNTER' && (
          <Field label="Counter amount" htmlFor="offer-counter" required hint="What the firm is putting forward">
            <Input
              id="offer-counter"
              inputMode="decimal"
              value={counterAmount}
              onChange={(event) => setCounterAmount(event.target.value)}
            />
          </Field>
        )}

        <Field
          label={action === 'REJECT' || action === 'WITHDRAW' ? 'Why' : 'Note'}
          htmlFor="offer-notes"
          hint="Recorded against the offer as the response note"
        >
          <Textarea id="offer-notes" value={notes} onChange={(event) => setNotes(event.target.value)} />
        </Field>

        <div aria-live="polite">
          {failure && <p role="alert" className="text-sm" style={{ color: 'var(--danger)' }}>{failure}</p>}
        </div>
      </div>
    </Dialog>
  )
}
