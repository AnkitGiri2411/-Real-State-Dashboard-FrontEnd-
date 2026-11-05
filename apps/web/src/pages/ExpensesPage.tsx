import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { CircleDollarSign, ClipboardList, Landmark, Plus, RefreshCw, Search, Wallet } from 'lucide-react'
import { PageAccessGuard, PageHeader } from '../components/common'
import {
  Badge, Button, DataTable, Dialog, EmptyState, ErrorState, Field, Input, KpiTile, Panel,
  Pagination, SectionHeader, Select, Skeleton, Toolbar, useToast, type Column,
} from '../components/ui'
import { ComparisonBars, DistributionDonut } from '../components/ui/charts'
import { count as fmtCount, date, money } from '../components/ui/formatters'
import { expenseApi, expenseCategoryApi, propertyApi, unitApi } from '../services'
import { ApiError, messageOf } from '../lib/errors'
import type { ExpenseSummaryResponse, ExpenseSummaryRow } from '../types'

/**
 * The expense ledger.
 *
 * **Operating and capital are never added together here.** The two figures are read out of
 * `GET /expenses/summary` as the service reports them — `operatingTotal` is the cost of the period's
 * letting, the figure net operating income subtracts, and `capitalTotal` is investment in the asset
 * that net income deliberately excludes. Folding them into one "expenses" number would misstate NOI
 * while every part of the number remained real money really spent, which is what makes that mistake
 * so easy to make and so hard to see. They are shown as two separate tiles and are never summed on
 * this screen.
 *
 * **The total is the backend's.** `total = amount + tax` is computed in the service; a client-supplied
 * total is ignored rather than trusted. So the create form sends the two parts and no total, exactly
 * as the endpoint expects.
 *
 * The category mix is drawn from the ledger rows the API returned, grouped by the heading it reports
 * on each row — an aggregation of the API's own rows, not a re-derivation of any ledger rule.
 */

/** The operating/capital filter, spelled as the register's three states rather than a boolean toggle. */
const CAPITAL_FILTERS = [
  { value: '', label: 'Operating and capital' },
  { value: 'operating', label: 'Operating only' },
  { value: 'capital', label: 'Capital only' },
]

const MIX_SIZE = 200

export default function ExpensesPage() {
  return (
    <PageAccessGuard permission="EXPENSE_VIEW">
      <ExpenseLedger />
    </PageAccessGuard>
  )
}

interface CreateExpenseForm {
  propertyId: string
  unitId: string
  categoryId: string
  amount: string
  taxAmount: string
  expenseDate: string
  recoverable: boolean
  recoveredAmount: string
  description: string
}

const EMPTY_EXPENSE: CreateExpenseForm = {
  propertyId: '', unitId: '', categoryId: '', amount: '', taxAmount: '', expenseDate: '',
  recoverable: false, recoveredAmount: '', description: '',
}

function ExpenseLedger() {
  const queryClient = useQueryClient()
  const toast = useToast()

  const [propertyId, setPropertyId] = useState('')
  const [unitId, setUnitId] = useState('')
  const [categoryId, setCategoryId] = useState('')
  const [capitalFilter, setCapitalFilter] = useState('')
  const [from, setFrom] = useState('')
  const [to, setTo] = useState('')
  const [search, setSearch] = useState('')
  const [sort, setSort] = useState('createdAt,desc')
  const [page, setPage] = useState(0)
  const [size, setSize] = useState(20)

  const [createOpen, setCreateOpen] = useState(false)
  const [form, setForm] = useState<CreateExpenseForm>(EMPTY_EXPENSE)
  const [formError, setFormError] = useState<string | null>(null)

  // The one filter set every read below shares, so the register, the mix and the figures agree.
  const filters = {
    propertyId: propertyId || undefined,
    unitId: unitId || undefined,
    categoryId: categoryId || undefined,
    // The split is read from the category server-side; the filter only chooses a side of it.
    capital: capitalFilter === '' ? undefined : capitalFilter === 'capital',
    from: from || undefined,
    to: to || undefined,
    search: search.trim() || undefined,
  }

  const summaryQuery = useQuery({
    queryKey: ['expenses', 'summary'],
    queryFn: () => expenseApi.summary(),
  })
  const registerQuery = useQuery({
    queryKey: ['expenses', 'register', { ...filters, sort, page, size }],
    queryFn: () => expenseApi.list({ ...filters, sort, page, size }),
  })
  // A wide read of the same filters, used only to draw the category mix.
  const mixQuery = useQuery({
    queryKey: ['expenses', 'mix', filters],
    queryFn: () => expenseApi.list({ ...filters, size: MIX_SIZE }),
  })
  const categoriesQuery = useQuery({
    queryKey: ['expense-categories'],
    queryFn: () => expenseCategoryApi.list(),
  })
  const propertiesQuery = useQuery({
    queryKey: ['properties', 'options'],
    queryFn: () => propertyApi.list({ size: 200 }),
  })
  const unitsQuery = useQuery({
    queryKey: ['units', 'options'],
    queryFn: () => unitApi.list({ size: 200 }),
  })

  const createMutation = useMutation({
    mutationFn: (body: Record<string, unknown>) => expenseApi.create(body),
    onSuccess: () => {
      toast.push('Expense recorded')
      setCreateOpen(false)
      setForm(EMPTY_EXPENSE)
      void queryClient.invalidateQueries({ queryKey: ['expenses'] })
    },
  })

  // `expenseApi.summary` declares `ExpenseDtos.ExpenseSummaryResponse`: `operatingTotal` and
  // `capitalTotal` below are the two distinct facts the endpoint reports, read by name.
  const summary = summaryQuery.data
  const categories = categoriesQuery.data ?? []
  const properties = propertiesQuery.data?.items ?? []
  const units = unitsQuery.data?.items ?? []
  const unitsForProperty = propertyId ? units.filter((unit) => unit.propertyId === propertyId) : units
  const filtersActive = Boolean(propertyId || unitId || categoryId || capitalFilter || from || to || search.trim())

  const categoryPoints = categoryMix(mixQuery.data?.items ?? [])
  const recoveredOverTotal = createMutation.error instanceof ApiError
    && createMutation.error.code === 'EXPENSE_RECOVERY_EXCEEDS_TOTAL'

  function submitCreate() {
    setFormError(null)
    if (!form.propertyId) return setFormError('Choose the property the cost was incurred on')
    if (!form.categoryId) return setFormError('Choose the heading the cost falls under')
    if (!form.expenseDate) return setFormError('A date is required')
    if (!form.description.trim()) return setFormError('A description is required')
    if (form.amount === '' || Number(form.amount) < 0) return setFormError('An amount is required')

    createMutation.mutate({
      propertyId: form.propertyId,
      unitId: form.unitId || undefined,
      categoryId: form.categoryId,
      expenseDate: form.expenseDate,
      amount: Number(form.amount),
      taxAmount: form.taxAmount ? Number(form.taxAmount) : 0,
      recoverable: form.recoverable,
      // Sent only when given; the service restricts it to the cost and to a recoverable heading.
      recoveredAmount: form.recoveredAmount ? Number(form.recoveredAmount) : undefined,
      description: form.description.trim(),
      // Deliberately no `totalAmount`: the backend computes amount + tax and ignores any total sent.
    })
  }

  return (
    <>
      <PageHeader
        title="Expenses"
        description="What the portfolio costs to hold, split between the cost of letting and investment in the asset."
        actions={<Button variant="primary" onClick={() => { createMutation.reset(); setForm(EMPTY_EXPENSE); setFormError(null); setCreateOpen(true) }}>
          <Plus size={15} aria-hidden="true" /> Record expense
        </Button>}
      />

      {/* ── Operating and capital, kept apart ────────────────────────────── */}
      {summaryQuery.isLoading && (
        <div className="mb-5 grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
          {Array.from({ length: 4 }).map((_, index) => <Skeleton key={index} rows={3} />)}
        </div>
      )}
      {summaryQuery.isError && (
        <div className="mb-5"><ErrorState message={messageOf(summaryQuery.error)} onRetry={() => void summaryQuery.refetch()} /></div>
      )}
      {summary && (
        <div className="mb-5 grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
          <KpiTile label="Operating total" value={money(summary.operatingTotal)} tone="copper" icon={<Wallet size={15} />}
            caption="The cost of the period's letting — what net operating income subtracts" />
          <KpiTile label="Capital total" value={money(summary.capitalTotal)} tone="slate" icon={<Landmark size={15} />}
            caption="Investment in the asset — reported separately, never added to operating" />
          <KpiTile label="Recovered" value={money(summary.recoveredTotal)} tone="emerald" icon={<CircleDollarSign size={15} />}
            caption={`${fmtCount(summary.recoverableCount)} costs recoverable under a lease`} />
          <KpiTile label="Unrecovered" value={money(summary.unrecoveredTotal)} tone="amber"
            caption="Borne by the portfolio after any recovery" />
        </div>
      )}

      {/* ── Analytics ───────────────────────────────────────────────────── */}
      <SectionHeader eyebrow="Analysis" title="Cost mix and the operating/capital split" rule="copper"
        description="The headings the cost fell under, and the two figures kept apart — operating is not capital, and the two are never shown as one." />
      <div className="mb-6 grid gap-4 xl:grid-cols-[1fr_1fr]">
        <Panel title="Expense by category" subtitle={`Across ${mixQuery.data ? fmtCount(mixQuery.data.totalElements) : '—'} recorded costs`} tint="sandstone">
          {mixQuery.isLoading && <Skeleton rows={4} />}
          {mixQuery.isError && <ErrorState message={messageOf(mixQuery.error)} onRetry={() => void mixQuery.refetch()} />}
          {mixQuery.data && (
            categoryPoints.length > 0 ? (
              <>
                <DistributionDonut points={categoryPoints} title="Expense by category" centerLabel={fmtCount(categoryPoints.length)} />
                <p className="mt-3 border-t pt-3 text-[11px]" style={{ borderColor: 'var(--border)', color: 'var(--text-secondary)' }}>
                  Shares of the cost recorded, by heading. Operating and capital appear together here because a
                  heading mix is a mix of headings; the split that matters to net income is reported above, as two figures.
                </p>
              </>
            ) : (
              <EmptyState
                title="No cost has been recorded under these filters"
                description="Record an expense — or clear the filters — and the mix of headings appears here."
              />
            )
          )}
        </Panel>
        <Panel title="Operating against capital" subtitle="The split net operating income is drawn from" tint="copper">
          {summary ? (
            <ComparisonBars
              title="Operating against capital"
              points={splitPoints(summary)}
            />
          ) : (
            <EmptyState title="The split is not available" description="The spending summary could not be read, so nothing is charted rather than a set of zeros." />
          )}
          <p className="mt-3 border-t pt-3 text-[11px]" style={{ borderColor: 'var(--border)', color: 'var(--text-secondary)' }}>
            Capital expenditure is excluded from net operating income on purpose. Adding the two bars together
            would overstate cost and understate the portfolio's running result.
          </p>
        </Panel>
      </div>

      {/* ── The ledger ──────────────────────────────────────────────────── */}
      <SectionHeader eyebrow="Ledger" title="Expense register" rule="slate"
        description="Every cost, the heading it fell under, and whether it is a cost of letting or an investment." />

      <Panel notch>
        <>
          <Toolbar>
            <Field label="Property" htmlFor="expense-filter-property">
              <Select id="expense-filter-property" value={propertyId} onChange={(event) => { setPropertyId(event.target.value); setUnitId(''); setPage(0) }}>
                <option value="">All properties</option>
                {properties.map((property) => <option key={property.id} value={property.id}>{property.name}</option>)}
              </Select>
            </Field>
            <Field label="Unit" htmlFor="expense-filter-unit">
              <Select id="expense-filter-unit" value={unitId} onChange={(event) => { setUnitId(event.target.value); setPage(0) }}>
                <option value="">All units</option>
                {unitsForProperty.map((unit) => <option key={unit.id} value={unit.id}>{unit.unitCode}{unit.propertyName ? ` · ${unit.propertyName}` : ''}</option>)}
              </Select>
            </Field>
            <Field label="Category" htmlFor="expense-filter-category">
              <Select id="expense-filter-category" value={categoryId} onChange={(event) => { setCategoryId(event.target.value); setPage(0) }}>
                <option value="">Any category</option>
                {categories.map((category) => <option key={category.id} value={category.id}>{category.name}</option>)}
              </Select>
            </Field>
            <Field label="Kind" htmlFor="expense-filter-kind">
              <Select id="expense-filter-kind" value={capitalFilter} onChange={(event) => { setCapitalFilter(event.target.value); setPage(0) }}>
                {CAPITAL_FILTERS.map((option) => <option key={option.value} value={option.value}>{option.label}</option>)}
              </Select>
            </Field>
            <Field label="From" htmlFor="expense-filter-from">
              <Input id="expense-filter-from" type="date" value={from} onChange={(event) => { setFrom(event.target.value); setPage(0) }} />
            </Field>
            <Field label="To" htmlFor="expense-filter-to">
              <Input id="expense-filter-to" type="date" value={to} onChange={(event) => { setTo(event.target.value); setPage(0) }} />
            </Field>
            <Field label="Search" htmlFor="expense-filter-search">
              <Input id="expense-filter-search" value={search} placeholder="Code, description, reference" onChange={(event) => { setSearch(event.target.value); setPage(0) }} />
            </Field>
            <div className="flex items-end gap-2">
              <Button onClick={() => void registerQuery.refetch()} aria-label="Reload the expense ledger">
                <RefreshCw size={14} aria-hidden="true" /> Reload
              </Button>
            </div>
          </Toolbar>

          {registerQuery.isLoading && <div className="p-4"><Skeleton rows={6} /></div>}
          {registerQuery.isError && (
            <div className="p-4"><ErrorState message={messageOf(registerQuery.error)} onRetry={() => void registerQuery.refetch()} /></div>
          )}
          {registerQuery.data && (
            <>
              <DataTable
                caption="Expense register"
                columns={expenseColumns}
                rows={registerQuery.data.items}
                keyOf={(row) => row.id}
                sort={sort}
                onSortChange={(next) => { setSort(next); setPage(0) }}
                empty={filtersActive ? (
                  <EmptyState icon={<Search size={20} />} title="No costs match these filters"
                    description="Change the property, unit, heading, kind or dates — the costs that do exist will reappear." />
                ) : (
                  <EmptyState icon={<ClipboardList size={20} />} title="No expenses have been recorded yet"
                    description="A cost is recorded against a property under a heading, which decides whether it is operating or capital. Record the first one and this register fills in." />
                )}
              />
              <Pagination
                page={registerQuery.data.page} size={registerQuery.data.size}
                totalElements={registerQuery.data.totalElements} totalPages={registerQuery.data.totalPages}
                hasNext={registerQuery.data.hasNext}
                onPageChange={setPage} onSizeChange={(next) => { setSize(next); setPage(0) }}
              />
            </>
          )}

          <p className="sr-only" role="status" aria-live="polite">
            {registerQuery.isFetching ? 'Updating the expense ledger' : 'Expense ledger up to date'}
          </p>
        </>
      </Panel>

      {/* ── Record a cost ────────────────────────────────────────────────── */}
      <Dialog
        open={createOpen}
        onClose={() => setCreateOpen(false)}
        title="Record an expense"
        description="The total is computed by the backend as amount plus tax, and the heading decides whether the cost is operating or capital."
        width="lg"
        footer={
          <>
            <Button variant="ghost" onClick={() => setCreateOpen(false)}>Cancel</Button>
            <Button variant="primary" loading={createMutation.isPending} onClick={submitCreate}>Record expense</Button>
          </>
        }
      >
        <div className="grid gap-4 sm:grid-cols-2">
          <Field label="Property" htmlFor="expense-new-property" required>
            <Select id="expense-new-property" value={form.propertyId} onChange={(event) => setForm({ ...form, propertyId: event.target.value, unitId: '' })}>
              <option value="">Choose a property…</option>
              {properties.map((property) => <option key={property.id} value={property.id}>{property.name}</option>)}
            </Select>
          </Field>
          <Field label="Unit" htmlFor="expense-new-unit" hint="Leave blank for a cost on the property itself">
            <Select id="expense-new-unit" value={form.unitId} onChange={(event) => setForm({ ...form, unitId: event.target.value })}>
              <option value="">The whole property</option>
              {(form.propertyId ? units.filter((unit) => unit.propertyId === form.propertyId) : units).map((unit) => (
                <option key={unit.id} value={unit.id}>{unit.unitCode}</option>
              ))}
            </Select>
          </Field>
          <Field label="Category" htmlFor="expense-new-category" required hint="Decides the operating/capital split">
            <Select id="expense-new-category" value={form.categoryId} onChange={(event) => setForm({ ...form, categoryId: event.target.value })}>
              <option value="">Choose a heading…</option>
              {categories.map((category) => <option key={category.id} value={category.id}>{category.name}</option>)}
            </Select>
          </Field>
          <Field label="Date" htmlFor="expense-new-date" required>
            <Input id="expense-new-date" type="date" value={form.expenseDate} onChange={(event) => setForm({ ...form, expenseDate: event.target.value })} />
          </Field>
          <Field label="Amount" htmlFor="expense-new-amount" required hint="Before tax">
            <Input id="expense-new-amount" type="number" min="0" step="0.01" value={form.amount} onChange={(event) => setForm({ ...form, amount: event.target.value })} />
          </Field>
          <Field label="Tax" htmlFor="expense-new-tax" hint="The total is amount plus tax, computed by the API">
            <Input id="expense-new-tax" type="number" min="0" step="0.01" value={form.taxAmount} onChange={(event) => setForm({ ...form, taxAmount: event.target.value })} />
          </Field>
          <div className="sm:col-span-2">
            <Field label="Description" htmlFor="expense-new-description" required>
              <Input id="expense-new-description" value={form.description} onChange={(event) => setForm({ ...form, description: event.target.value })} />
            </Field>
          </div>
          <div className="sm:col-span-2 space-y-3">
            <label className="flex items-center gap-2 text-sm" style={{ color: 'var(--text-secondary)' }}>
              <input type="checkbox" className="checkbox" checked={form.recoverable}
                onChange={(event) => setForm({ ...form, recoverable: event.target.checked })} />
              Recoverable under the lease
            </label>
            <Field
              label="Recovered so far"
              htmlFor="expense-new-recovered"
              hint="Only a recoverable cost may carry a recovery, and never more than the cost came to"
              error={recoveredOverTotal ? messageOf(createMutation.error) : formError}
            >
              <Input id="expense-new-recovered" type="number" min="0" step="0.01" value={form.recoveredAmount} onChange={(event) => setForm({ ...form, recoveredAmount: event.target.value })} />
            </Field>
          </div>
        </div>

        {createMutation.isError && !recoveredOverTotal && (
          <div className="mt-4"><ErrorState message={messageOf(createMutation.error)} /></div>
        )}
        {formError && !recoveredOverTotal && (
          <p className="mt-4 text-sm" role="alert" style={{ color: 'var(--danger)' }}>{formError}</p>
        )}
      </Dialog>
    </>
  )
}

/**
 * The two bars, drawn from the two figures the API reports. Operating is the cost of the period's
 * letting and capital is investment in the asset; the two are never added together, on the chart or
 * anywhere else on this screen.
 */
function splitPoints(summary: ExpenseSummaryResponse): { label: string; value: number; tone: 'copper' | 'slate' }[] {
  return [
    { label: 'Operating', value: summary.operatingTotal, tone: 'copper' },
    { label: 'Capital', value: summary.capitalTotal, tone: 'slate' },
  ]
}

/**
 * The category mix: the ledger rows grouped by the heading the API reports on each one. Amounts are
 * rounded for the donut's legend, which prints whole numbers; this is a presentation rounding of an
 * aggregate of the API's rows, not a ledger figure being recomputed.
 */
function categoryMix(rows: ExpenseSummaryRow[]): { label: string; value: number }[] {
  const totals = new Map<string, number>()
  for (const row of rows) {
    const label = row.categoryName ?? 'Uncategorised'
    totals.set(label, (totals.get(label) ?? 0) + row.totalAmount)
  }
  return [...totals.entries()]
    .map(([label, value]) => ({ label, value: Math.round(value) }))
    .sort((a, b) => b.value - a.value)
}

const expenseColumns: Column<ExpenseSummaryRow>[] = [
  { key: 'date', header: 'Date', width: '9%', sortKey: 'expenseDate', render: (row) => date(row.expenseDate) },
  { key: 'place', header: 'Property · unit', width: '13%', render: (row) => (
    <span className="truncate">{row.propertyName ?? '—'}{row.unitCode ? ` · ${row.unitCode}` : ''}</span>
  ) },
  { key: 'category', header: 'Category', width: '11%', sortKey: 'category', render: (row) => <span className="truncate">{row.categoryName ?? '—'}</span> },
  { key: 'vendor', header: 'Vendor', width: '11%', secondary: true, render: (row) => <span className="truncate">{row.vendorName ?? '—'}</span> },
  { key: 'amount', header: 'Amount', width: '11%', align: 'right', sortKey: 'amount', render: (row) => money(row.amount) },
  { key: 'tax', header: 'Tax', width: '10%', align: 'right', secondary: true, render: (row) => money(row.taxAmount) },
  { key: 'total', header: 'Total', width: '11%', align: 'right', sortKey: 'totalAmount', render: (row) => money(row.totalAmount) },
  { key: 'recovered', header: 'Recovered', width: '11%', align: 'right', render: (row) => money(row.recoveredAmount) },
  { key: 'kind', header: 'Kind', width: '9%', render: (row) => (
    <Badge tone={row.capital ? 'slate' : 'copper'}>{row.capital ? 'Capital' : 'Operating'}</Badge>
  ) },
]
