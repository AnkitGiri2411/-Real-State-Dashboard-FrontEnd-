import type { Row } from './query'

/**
 * Reports, and the one thing about them that has to be recomputed.
 *
 * A register is a list of records; a report is a table of figures over a period. The bundled
 * snapshot holds the report exactly as the service produced it, which means its rows are the rows for
 * **the window it was generated over** - recorded in `src/data/manifest.json`. That is fine until a
 * reader narrows the dates, at which point the captured totals would describe a period the screen is
 * no longer claiming to show.
 *
 * So when a window is passed, rows whose period falls outside it are dropped and the totals are
 * rebuilt from what is left. Which column is the period is read off the report's own `columns`: a
 * `month` column compares by year and month, a `DATE`/`DATETIME` column by day. A report with neither
 * has no period in its rows - `property-performance` aggregates per property, not per period - and is
 * left as captured, because there is nothing to filter it by.
 */

interface ReportColumn {
  key: string
  label: string
  type: string
}

export interface ReportTable {
  key: string
  title: string
  from: string | null
  to: string | null
  generatedAt: string
  columns: ReportColumn[]
  rows: Row[]
  totals: Row
}

/** How a report's rows record the period they belong to. */
function periodColumn(table: ReportTable): { key: string; kind: 'date' | 'month' } | null {
  const month = table.columns.find((column) => column.key === 'month')
  if (month) return { key: month.key, kind: 'month' }

  const date = table.columns.find(
    (column) => column.type === 'DATE' || column.type === 'DATETIME',
  )
  return date ? { key: date.key, kind: 'date' } : null
}

function withinWindow(row: Row, column: { key: string; kind: 'date' | 'month' }, from?: string, to?: string): boolean {
  const raw = row[column.key]
  if (raw === null || raw === undefined || raw === '') return true // nothing to judge it by: keep it

  const width = column.kind === 'month' ? 7 : 10
  const value = String(raw).slice(0, width)

  if (from && value < from.slice(0, width)) return false
  if (to && value > to.slice(0, width)) return false
  return true
}

/**
 * Rebuild the totals block from the rows that survived.
 *
 * A key that is a numeric field on the rows is summed. A key that is not a field is a count of the
 * rows themselves - `property-performance` reports `properties` - and is recognised by the captured
 * value having been the row count. Anything else keeps the figure the service sent, rather than
 * being replaced by a guess.
 */
function rebuildTotals(table: ReportTable, rows: Row[]): Row {
  const totals: Row = { ...table.totals }

  for (const key of Object.keys(table.totals)) {
    const onRows = rows.filter((row) => typeof row[key] === 'number')
    if (onRows.length === rows.length && rows.length > 0) {
      totals[key] = onRows.reduce((sum: number, row: Row) => sum + (row[key] as number), 0)
    } else if (table.totals[key] === table.rows.length) {
      totals[key] = rows.length
    }
  }

  return totals
}

/** Apply the report's own sort dialect: `field` ascending, `-field` descending. */
function sortRows(rows: Row[], sort?: string): Row[] {
  if (!sort) return rows
  const descending = sort.startsWith('-')
  const field = descending ? sort.slice(1) : sort

  return [...rows].sort((left, right) => {
    const a = left[field]
    const b = right[field]
    if (a === null || a === undefined) return b === null || b === undefined ? 0 : 1
    if (b === null || b === undefined) return -1

    const result =
      typeof a === 'number' && typeof b === 'number'
        ? a - b
        : String(a).localeCompare(String(b), undefined, { numeric: true, sensitivity: 'base' })

    return descending ? -result : result
  })
}

/** One report, as the screen asked for it. */
export function readReport(
  captured: ReportTable,
  params: Record<string, string>,
): ReportTable {
  const column = periodColumn(captured)
  const from = params.from || undefined
  const to = params.to || undefined

  const rows = column
    ? captured.rows.filter((row) => withinWindow(row, column, from, to))
    : captured.rows

  return {
    ...captured,
    // The window is echoed as asked for, so the screen's own caption and its pickers agree.
    from: params.from ?? captured.from,
    to: params.to ?? captured.to,
    rows: sortRows(rows, params.sort),
    totals: column ? rebuildTotals(captured, rows) : captured.totals,
  }
}

/** Report rows and columns, as a CSV download. */
export function reportCsv(table: ReportTable): string {
  const escape = (value: unknown): string => {
    if (value === null || value === undefined) return ''
    const text = String(value)
    return /[",\n]/.test(text) ? `"${text.replace(/"/g, '""')}"` : text
  }

  const header = table.columns.map((column) => escape(column.label)).join(',')
  const body = table.rows.map((row) =>
    table.columns.map((column) => escape(row[column.key])).join(','),
  )

  return [header, ...body].join('\r\n')
}
