import { describe, expect, it } from 'vitest'
import { readReport, reportCsv, type ReportTable } from './aggregates'

/**
 * The two things a report does that are not a straight read.
 *
 * A report's rows cover a period, and the screen lets the reader narrow it. The captured table
 * describes the window it was generated over, so a narrower request has to drop the rows outside it
 * **and rebuild the totals from what is left** - otherwise the figures would describe a period the
 * screen is no longer claiming to show, which is worse than not filtering at all.
 */
function table(overrides: Partial<ReportTable> = {}): ReportTable {
  return {
    key: 'expense',
    title: 'Expenses',
    from: '2025-01-01',
    to: '2026-12-31',
    generatedAt: '2026-11-05T00:00:00Z',
    columns: [
      { key: 'month', label: 'Month', type: 'TEXT' },
      { key: 'total', label: 'Total', type: 'MONEY' },
    ],
    rows: [
      { month: '2026-01', total: 100 },
      { month: '2026-06', total: 200 },
      { month: '2026-11', total: 400 },
    ],
    totals: { total: 700, months: 3 },
    ...overrides,
  }
}

describe('report windows', () => {
  it('keeps every row when no window is asked for', () => {
    expect(readReport(table(), {}).rows).toHaveLength(3)
  })

  it('drops the rows outside the window', () => {
    const narrowed = readReport(table(), { from: '2026-05-01', to: '2026-11-30' })
    expect(narrowed.rows.map((row) => row.month)).toEqual(['2026-06', '2026-11'])
  })

  it('rebuilds the totals from the rows that survived', () => {
    // The captured total of 700 belongs to the whole window and would be a lie about this one.
    expect(readReport(table(), { from: '2026-05-01', to: '2026-11-30' }).totals.total).toBe(600)
  })

  it('treats a total that matches the row count as a count, and recounts it', () => {
    expect(readReport(table(), { from: '2026-05-01', to: '2026-11-30' }).totals.months).toBe(2)
  })

  it('echoes the window that was asked for, so the caption and the pickers agree', () => {
    const narrowed = readReport(table(), { from: '2026-05-01', to: '2026-11-30' })
    expect(narrowed.from).toBe('2026-05-01')
    expect(narrowed.to).toBe('2026-11-30')
  })

  it('filters on a date column when that is what the report has', () => {
    const dated = table({
      columns: [
        { key: 'endDate', label: 'Ends', type: 'DATE' },
        { key: 'rent', label: 'Rent', type: 'MONEY' },
      ],
      rows: [
        { endDate: '2026-02-14', rent: 10 },
        { endDate: '2026-09-30', rent: 20 },
      ],
      totals: { rent: 30 },
    })
    expect(readReport(dated, { from: '2026-06-01' }).rows).toHaveLength(1)
  })

  it('leaves a report whose rows carry no period alone', () => {
    // `property-performance` aggregates per property, not per period: there is nothing to filter by.
    const perProperty = table({
      columns: [
        { key: 'name', label: 'Property', type: 'TEXT' },
        { key: 'valuation', label: 'Valuation', type: 'MONEY' },
      ],
      rows: [{ name: 'Harbour Court', valuation: 100 }],
      totals: { valuation: 100, properties: 1 },
    })
    const narrowed = readReport(perProperty, { from: '2026-06-01', to: '2026-06-30' })
    expect(narrowed.rows).toHaveLength(1)
    expect(narrowed.totals.valuation).toBe(100)
  })

  it('sorts by the report dialect, where a leading minus means descending', () => {
    expect(readReport(table(), { sort: '-total' }).rows.map((row) => row.total)).toEqual([400, 200, 100])
    expect(readReport(table(), { sort: 'total' }).rows.map((row) => row.total)).toEqual([100, 200, 400])
  })
})

describe('report CSV', () => {
  it('writes the header from the column labels, not their keys', () => {
    expect(reportCsv(table()).split('\r\n')[0]).toBe('Month,Total')
  })

  it('writes one line per row and quotes anything that would break the format', () => {
    const quoted = table({ rows: [{ month: 'June, 2026', total: 200 }] })
    expect(reportCsv(quoted).split('\r\n')).toEqual(['Month,Total', '"June, 2026",200'])
  })

  it('writes a blank for a missing value rather than the word undefined', () => {
    const sparse = table({ rows: [{ month: '2026-01' }] })
    expect(reportCsv(sparse).split('\r\n')[1]).toBe('2026-01,')
  })
})
