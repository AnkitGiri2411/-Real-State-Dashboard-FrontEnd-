import { useMemo, useState, type ReactNode } from 'react'
import { useSearchParams } from 'react-router-dom'
import { useMutation, useQuery } from '@tanstack/react-query'
import { CalendarRange, Download, FileSpreadsheet, Info } from 'lucide-react'
import { PageAccessGuard, PageHeader } from '../components/common'
import {
  Button, DataTable, DetailItem, EmptyState, ErrorState, Field, Input, KpiTile, Pagination, Panel,
  SectionHeader, Select, Skeleton, useToast, type Column,
} from '../components/ui'
import {
  count as formatCount, date, dateTime, decimal, humanize, money, month as monthLabel,
} from '../components/ui/formatters'
import { reportApi } from '../services'
import { messageOf } from '../lib/errors'

/* ============================================================
 * The reports this installation actually serves
 * ============================================================ */

/**
 * The report keys, in the order the Reports screen offers them.
 *
 * <p>This list is `ReportService.KEYS` — the twelve reports the backend implements — and its labels
 * are the titles those reports print. Nothing is offered that the endpoint would refuse: a menu entry
 * that answers `'x' is not a report this installation serves` teaches the reader that the product
 * does not know its own repertoire.</p>
 *
 * <p>`window` records whether the report is scoped by a date range, which is a fact about the service
 * rather than a preference: `propertyPerformance`, `rentalIncome`, `leaseExpiry`, `maintenanceCost`
 * and `expense` query their window; `occupancy` and `outstandingRent` are read *as at* an end date;
 * and the remaining five are the current state of a register, where the service echoes the window but
 * deliberately does not scope the figures by it.</p>
 */
type ReportWindow = 'range' | 'as-of' | 'none'

interface ReportDefinition {
  key: string
  label: string
  window: ReportWindow
}

const REPORTS: ReportDefinition[] = [
  { key: 'property-performance', label: 'Property Performance', window: 'range' },
  { key: 'occupancy', label: 'Occupancy', window: 'as-of' },
  { key: 'rental-income', label: 'Rental Income', window: 'range' },
  { key: 'outstanding-rent', label: 'Outstanding Rent', window: 'as-of' },
  { key: 'lease-expiry', label: 'Lease Expiry', window: 'range' },
  { key: 'tenant', label: 'Tenants', window: 'none' },
  { key: 'maintenance-cost', label: 'Maintenance Cost', window: 'range' },
  { key: 'expense', label: 'Expenses', window: 'range' },
  { key: 'sales', label: 'Sales Pipeline', window: 'none' },
  { key: 'lead-conversion', label: 'Lead Conversion', window: 'none' },
  { key: 'portfolio-performance', label: 'Portfolio Performance', window: 'none' },
  { key: 'agent-performance', label: 'Agent Performance', window: 'none' },
]

/* ============================================================
 * Reading the response
 * ============================================================ */

/**
 * One report, as `ReportDtos.ReportTable` sends it.
 *
 * <p>Every report shares this shape — a key, a title, the window it covered, when it was produced,
 * its columns, its rows and a totals block — which is what lets one screen render all twelve without
 * a table per report. **The columns are read from the response's own `columns`**, so a report that
 * gains a column gains it here, and no column is ever invented by the interface.</p>
 */
interface ReportColumn {
  key: string
  label: string
  type: string
}

interface ReportTable {
  key: string
  title: string
  from: string | null
  to: string | null
  generatedAt: string
  columns: ReportColumn[]
  rows: Record<string, unknown>[]
  totals: Record<string, unknown>
}

/**
 * The response read as a report table, or `null` when it is not one.
 *
 * <p>`reportApi.run` is typed against the shared register shape (`PageResponse`), because that is the
 * one shape the API map declares for a paged read; the endpoint itself answers with a `ReportTable`.
 * Rather than assert a type the client cannot know, the payload is *checked* here — columns and rows
 * have to be arrays — and a payload that is not a table is reported as such instead of rendering an
 * empty grid that would read as "this report has no rows".</p>
 */
function asReportTable(payload: unknown): ReportTable | null {
  if (!payload || typeof payload !== 'object') return null
  const candidate = payload as Partial<ReportTable>
  if (!Array.isArray(candidate.columns) || !Array.isArray(candidate.rows)) return null
  return {
    key: typeof candidate.key === 'string' ? candidate.key : '',
    title: typeof candidate.title === 'string' ? candidate.title : 'Report',
    from: typeof candidate.from === 'string' ? candidate.from : null,
    to: typeof candidate.to === 'string' ? candidate.to : null,
    generatedAt: typeof candidate.generatedAt === 'string' ? candidate.generatedAt : '',
    columns: candidate.columns as ReportColumn[],
    rows: candidate.rows as Record<string, unknown>[],
    totals: (candidate.totals && typeof candidate.totals === 'object' ? candidate.totals : {}) as Record<string, unknown>,
  }
}

/** A number the API sent, whether it arrived as a JSON number or as a numeric string. */
function toNumber(value: unknown): number | null {
  if (typeof value === 'number' && Number.isFinite(value)) return value
  if (typeof value === 'string' && value.trim() !== '') {
    const parsed = Number(value)
    return Number.isFinite(parsed) ? parsed : null
  }
  return null
}

/**
 * One cell, formatted by the column's own declared type.
 *
 * <p>`ReportDtos.ColumnType` is what tells money from a count from a date, so the alignment and the
 * formatting follow the report's own declaration rather than a guess made from the values. A value
 * the type cannot format is printed as it arrived — never silently blanked.</p>
 */
function renderCell(value: unknown, type: string, key: string): ReactNode {
  if (value === null || value === undefined || value === '') return '—'

  switch (type) {
    case 'MONEY': {
      const number = toNumber(value)
      return number === null ? String(value) : money(number)
    }
    case 'DATE':
      return date(String(value))
    case 'DATETIME':
      return dateTime(String(value))
    case 'INTEGER': {
      const number = toNumber(value)
      return number === null ? String(value) : formatCount(number)
    }
    case 'DECIMAL': {
      const number = toNumber(value)
      // No percent sign is appended: the column's own heading already says what the figure is, and a
      // unit the report did not state is exactly the kind of detail a report must not invent.
      return number === null ? String(value) : decimal(number, 2)
    }
    default:
      // The three windowed month reports carry a `2026-03` key in a TEXT column; the dashboard's own
      // trend axis renders the same key as `Mar 2026`, so the reader sees one spelling of a month.
      return key === 'month' ? monthLabel(String(value)) : String(value)
  }
}

/** Money, counts and decimals are right-aligned so a column of figures lines up under its total. */
function isNumericType(type: string): boolean {
  return type === 'MONEY' || type === 'INTEGER' || type === 'DECIMAL'
}

/**
 * The register's sort convention translated into the report's.
 *
 * <p>`DataTable` reports a sort as `field,asc|desc`; `ReportService.sortRows` reads `field` for
 * ascending and `-field` for descending. The two are translated here rather than one of them being
 * changed, because both conventions are already in use elsewhere in the product.</p>
 */
function toReportSort(sort: string): string | undefined {
  if (!sort) return undefined
  const [field, direction] = sort.split(',')
  if (!field) return undefined
  return direction === 'desc' ? `-${field}` : field
}

/** Today, as `yyyy-MM-dd` in the reader's own calendar. */
function today(): string {
  const now = new Date()
  return `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}-${String(now.getDate()).padStart(2, '0')}`
}

/**
 * The default window: the first of the month eleven months ago to today.
 *
 * <p>This mirrors `ReportService.run`'s own default, so a reader who opens the screen without a
 * window sees the same figures a bare API call would return rather than a window the interface made
 * up.</p>
 */
function defaultFrom(): string {
  const now = new Date()
  const start = new Date(now.getFullYear(), now.getMonth() - 11, 1)
  return `${start.getFullYear()}-${String(start.getMonth() + 1).padStart(2, '0')}-01`
}

/**
 * Reports.
 *
 * <p><b>One screen, twelve reports, and the choice lives in the address.</b> The selected report and
 * its window are kept in the query string (`?report=…&from=…&to=…`), so a refresh reopens the same
 * report and a link pasted into a message opens it for a colleague — rather than the twelve routes
 * that a report-per-page design would need, each of which would need its own guard, its own states
 * and its own columns.</p>
 *
 * <p>Nothing on this page is hardcoded: the picker holds the twelve keys the service implements, the
 * columns come from the response's own `columns`, the totals come from the response's own `totals`
 * block, and a report whose figures the backend could not compute simply returns no rows — the empty
 * state below names the register that has to fill before it will.</p>
 */
export default function ReportsPage() {
  const [searchParams, setSearchParams] = useSearchParams()
  const toast = useToast()

  const requestedReport = searchParams.get('report') ?? REPORTS[0].key
  const served = REPORTS.some((definition) => definition.key === requestedReport)
  const definition = REPORTS.find((candidate) => candidate.key === requestedReport) ?? REPORTS[0]

  const from = searchParams.get('from') ?? defaultFrom()
  const to = searchParams.get('to') ?? today()

  const [sort, setSort] = useState('')
  const [page, setPage] = useState(0)
  const [size, setSize] = useState(20)

  /** Write the choice back into the address so a refresh and a shared link both land here. */
  function updateWindow(next: Partial<{ report: string; from: string; to: string }>): void {
    const params = new URLSearchParams(searchParams)
    for (const [key, value] of Object.entries(next)) {
      if (value === undefined || value === '') params.delete(key)
      else params.set(key, value)
    }
    setSearchParams(params, { replace: true })
    setPage(0)
    setSort('')
  }

  const usesWindow = definition.window !== 'none'
  const requestParams = {
    ...(usesWindow ? { from, to } : {}),
    sort: toReportSort(sort),
  }

  const reportQuery = useQuery({
    queryKey: ['reports', definition.key, { from: usesWindow ? from : null, to: usesWindow ? to : null, sort }],
    queryFn: () => reportApi.run(definition.key, requestParams),
    enabled: served,
  })

  const exportCsv = useMutation({
    mutationFn: () => reportApi.export(definition.key, requestParams, `${definition.key}-${from}-to-${to}.csv`),
    onSuccess: () => toast.push('The report was written to a CSV file.', 'emerald'),
    onError: (error) => toast.push(messageOf(error), 'terracotta'),
  })

  const table = useMemo(
    () => (reportQuery.data === undefined ? null : asReportTable(reportQuery.data)),
    [reportQuery.data],
  )

  const columns = useMemo<Column<Record<string, unknown>>[]>(() => {
    if (!table) return []
    return table.columns.map((column): Column<Record<string, unknown>> => ({
      key: column.key,
      header: column.label,
      // Sorting is offered because `ReportService` sorts by any of the report's own columns; the key
      // it accepts is the column key, which is what the header button sends.
      sortKey: column.key,
      align: isNumericType(column.type) ? 'right' : 'left',
      render: (row: Record<string, unknown>) => renderCell(row[column.key], column.type, column.key),
    }))
  }, [table])

  const rows = table?.rows ?? []
  const pageCount = size > 0 ? Math.ceil(rows.length / size) : 1
  const safePage = Math.min(page, Math.max(0, pageCount - 1))
  const pageRows = rows.slice(safePage * size, safePage * size + size)

  const totalEntries = table ? Object.entries(table.totals) : []
  const columnTypeByKey = new Map((table?.columns ?? []).map((column) => [column.key, column.type]))

  return (
    <PageAccessGuard permission="REPORT_VIEW">
      <PageHeader
        eyebrow="Insight"
        title="Reports"
        description="Every report this installation produces, from the same operational tables the registers read. The report and its window live in the address, so a refresh or a shared link opens the same figures."
        actions={
          <Button
            variant="primary"
            onClick={() => exportCsv.mutate()}
            loading={exportCsv.isPending}
            disabled={!table || rows.length === 0}
          >
            <Download size={15} aria-hidden="true" />
            Export CSV
          </Button>
        }
      />

      {/* ── Choosing a report ────────────────────────────────────────────── */}

      <Panel className="mb-5" tint="copper">
        <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
          <Field label="Report" htmlFor="report-picker" hint="The reports this installation serves">
            <Select
              id="report-picker"
              value={definition.key}
              onChange={(event) => updateWindow({ report: event.target.value })}
            >
              {REPORTS.map((option) => (
                <option key={option.key} value={option.key}>{option.label}</option>
              ))}
            </Select>
          </Field>

          {usesWindow && (
            <Field
              label={definition.window === 'as-of' ? 'Read as at' : 'Window starts'}
              htmlFor="report-from"
              hint={definition.window === 'as-of'
                ? 'This report is read as at one date; the start of the window is not used.'
                : 'Inclusive, in the reader\u2019s calendar'}
            >
              <Input
                id="report-from"
                type="date"
                value={from}
                max={to}
                onChange={(event) => updateWindow({ from: event.target.value })}
              />
            </Field>
          )}

          {usesWindow && (
            <Field label="Window ends" htmlFor="report-to" hint="Inclusive">
              <Input
                id="report-to"
                type="date"
                value={to}
                min={from}
                onChange={(event) => updateWindow({ to: event.target.value })}
              />
            </Field>
          )}

          {!usesWindow && (
            <div className="sm:col-span-2">
              <p className="eyebrow" style={{ color: 'var(--ink-copper)' }}>Not windowed</p>
              <p className="mt-1 text-xs leading-relaxed" style={{ color: 'var(--text-secondary)' }}>
                {definition.label} reports the current state of a register rather than a period, so the
                report carries no date range: its figures are the position as at the moment it was
                produced.
              </p>
            </div>
          )}
        </div>

        <div className="mt-3 flex flex-wrap items-center gap-3 border-t pt-3" style={{ borderColor: 'var(--border)' }}>
          <Button size="sm" variant="ghost" onClick={() => updateWindow({ from: defaultFrom(), to: today() })}>
            <CalendarRange size={14} aria-hidden="true" />
            Last twelve months
          </Button>
          <p className="text-[11px]" style={{ color: 'var(--text-secondary)' }}>
            Address: <span className="tabular-nums">/reports?report={definition.key}</span>
            {usesWindow ? <span className="tabular-nums">&from={from}&to={to}</span> : null}
          </p>
        </div>
      </Panel>

      {/* ── The report ───────────────────────────────────────────────────── */}

      {!served && (
        <EmptyState
          title={`'${requestedReport}' is not a report this installation serves`}
          description="The address asked for a report the backend does not implement. Choose one of the reports in the picker above — the twelve listed there are the ones the service produces."
          action={<Button size="sm" onClick={() => updateWindow({ report: REPORTS[0].key })}>Show {REPORTS[0].label}</Button>}
        />
      )}

      {served && reportQuery.isLoading && <Skeleton rows={6} />}

      {served && reportQuery.isError && (
        <ErrorState
          message={messageOf(reportQuery.error)}
          onRetry={() => void reportQuery.refetch()}
        />
      )}

      {served && reportQuery.isSuccess && !table && (
        <EmptyState
          title="The report did not come back as a table"
          description="A report is returned as headed columns with rows and a totals block. This response was not shaped that way, so nothing is shown rather than a grid that would read as an empty report."
        />
      )}

      {served && table && (
        <>
          <div className="mb-5 grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
            <KpiTile
              label="Rows returned"
              value={formatCount(rows.length)}
              tone="forest"
              icon={<FileSpreadsheet size={15} />}
              caption="The whole report, as the endpoint returned it"
            />
            <KpiTile
              label="Columns"
              value={formatCount(table.columns.length)}
              tone="sandstone"
              caption="Declared by the report itself"
            />
            <KpiTile
              label="Figures totalled"
              value={formatCount(totalEntries.length)}
              tone="copper"
              caption={totalEntries.length > 0 ? 'A totals row is supplied' : 'This report supplies no totals'}
            />
            <KpiTile
              label="Produced"
              value={table.generatedAt ? dateTime(table.generatedAt) : '—'}
              tone="slate"
              caption={usesWindow && table.from && table.to
                ? `${date(table.from)} to ${date(table.to)}`
                : 'Not windowed'}
            />
          </div>

          <SectionHeader
            eyebrow="Report"
            title={table.title}
            rule="forest"
            description={
              usesWindow && table.from && table.to
                ? `Figures cover ${date(table.from)} to ${date(table.to)}, inclusive.`
                : 'Figures are the current position of the register this report reads.'
            }
            actions={
              <Button
                size="sm"
                onClick={() => exportCsv.mutate()}
                loading={exportCsv.isPending}
                disabled={rows.length === 0}
              >
                <Download size={14} aria-hidden="true" />
                Export CSV
              </Button>
            }
          />

          <Panel className="mb-5" notch>
            {columns.length === 0
              ? (
                <EmptyState
                  title="This report declares no columns"
                  description="A report is returned with the headings it prints. This response carried none, so there is nothing to lay out — an empty table would read as a report with no figures."
                />
              )
              : (
                <DataTableForReport
                  columns={columns}
                  rows={pageRows}
                  sort={sort}
                  onSortChange={(next) => { setSort(next); setPage(0) }}
                  caption={`${table.title} report`}
                  empty={
                    <EmptyState
                      title={`${table.title} has nothing to report for this window`}
                      description="The report is built by querying the operational tables for the window you asked for. A register with no records in the window produces no rows — widen the window, or add the records the report reads."
                    />
                  }
                />
              )}

            {rows.length > 0 && (
              <div className="border-t" style={{ borderColor: 'var(--border)' }}>
                <Pagination
                  page={safePage}
                  size={size}
                  totalElements={rows.length}
                  totalPages={pageCount}
                  hasNext={safePage + 1 < pageCount}
                  onPageChange={setPage}
                  onSizeChange={(next) => { setSize(next); setPage(0) }}
                />
              </div>
            )}
          </Panel>

          <p className="mb-6 text-[11px]" style={{ color: 'var(--text-secondary)' }}>
            A report arrives whole — the endpoint applies its own row limit — so the pager above is a
            window over the rows the API sent, not a second request. Sorting, by contrast, is the
            report&rsquo;s own: it is sent to the backend, which orders the real values its rows hold.
          </p>

          {/* ── The totals the report supplies ─────────────────────────────── */}

          <SectionHeader
            eyebrow="Totals"
            title="As the report totals itself"
            rule="emerald"
            description="Read from the report's own totals block, so the figure on screen and the figure in the CSV cannot disagree."
          />
          <Panel className="mb-6" tint="emerald">
            {totalEntries.length === 0
              ? (
                <EmptyState
                  title="This report supplies no totals"
                  description="Some reports total meaningfully and some do not — an occupancy percentage has no sum. Where a total would be meaningless the backend sends none, and none is invented here."
                />
              )
              : (
                <dl className="grid gap-x-6 gap-y-3 sm:grid-cols-2 lg:grid-cols-4">
                  {totalEntries.map(([key, value]) => {
                    const type = columnTypeByKey.get(key)
                    const label = table.columns.find((column) => column.key === key)?.label ?? humanize(key)
                    return (
                      <DetailItem
                        key={key}
                        label={label}
                        value={
                          type
                            ? renderCell(value, type, key)
                            : typeof value === 'number'
                              ? formatCount(value)
                              : String(value ?? '—')
                        }
                      />
                    )
                  })}
                </dl>
              )}
            {totalEntries.length > 0 && (
              <p className="mt-3 border-t pt-3 text-[11px] leading-relaxed" style={{ color: 'var(--text-secondary)' }}>
                <Info size={12} aria-hidden="true" className="mr-1 inline" />
                A total that is one of the report&rsquo;s columns is formatted as that column is. A figure the
                report states that is <em>not</em> one of its columns is labelled with the report&rsquo;s own key
                for it, exactly as the API sent it — the report does not say which of those are money, and
                this screen does not guess.
              </p>
            )}
          </Panel>
        </>
      )}
    </PageAccessGuard>
  )
}

/**
 * The register, typed for a report row.
 *
 * <p>A report row is a map from column key to value — {@code Map<String, Object>} on the wire — so
 * this is the one place the table is given a row type, and it is a map of unknowns rather than a
 * shape someone hoped for.</p>
 */
function DataTableForReport({
  columns, rows, sort, onSortChange, caption, empty,
}: {
  columns: Column<Record<string, unknown>>[]
  rows: Record<string, unknown>[]
  sort: string
  onSortChange: (sort: string) => void
  caption: string
  empty: ReactNode
}) {
  return (
    <DataTable
      columns={columns}
      rows={rows}
      keyOf={reportRowKey}
      sort={sort}
      onSortChange={onSortChange}
      caption={caption}
      empty={empty}
    />
  )
}

/**
 * A stable key for a report row.
 *
 * <p>A report row carries no identifier of its own — it is a map of figures — so the first column
 * that is a code, a month or a stage names the row, and failing that the row's own values are used.
 * Deriving the key from the data that exists is what keeps React from re-mounting every row on each
 * re-sort.</p>
 */
function reportRowKey(row: Record<string, unknown>): string {
  const preferred = ['propertyCode', 'leaseCode', 'invoiceNumber', 'tenantCode', 'saleCode', 'agentCode',
    'portfolioCode', 'month', 'stage']
  for (const key of preferred) {
    const value = row[key]
    if (typeof value === 'string' && value !== '') return `${key}:${value}`
  }
  return JSON.stringify(row)
}
