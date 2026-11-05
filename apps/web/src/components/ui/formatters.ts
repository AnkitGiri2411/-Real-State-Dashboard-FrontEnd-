/**
 * Number, money and date formatting.
 *
 * Money goes through `Intl.NumberFormat` with the currency the **platform profile** supplies, never a
 * hardcoded symbol - the same reason the currency lives in configuration on the backend. Figures are
 * formatted with tabular alignment so a column of amounts lines up, and a missing value renders as a
 * dash rather than `0`, because "we do not know" and "it is zero" are different facts.
 */

let currencyCode = 'INR'
let currencySymbol = 'Rs.'

/** Called once from the platform profile so every figure in the product agrees. */
export function configureCurrency(code: string, symbol: string): void {
  currencyCode = code || currencyCode
  currencySymbol = symbol || currencySymbol
}

export function getCurrencySymbol(): string {
  return currencySymbol
}

/** Money, to the paise. Null and undefined read as a dash, not as zero. */
export function money(value: number | null | undefined): string {
  if (value === null || value === undefined) return '—'
  return `${currencySymbol} ${new Intl.NumberFormat('en-IN', {
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  }).format(value)}`
}

/** Money without the paise, for a KPI where the lakhs are the story. */
export function moneyCompact(value: number | null | undefined): string {
  if (value === null || value === undefined) return '—'
  const absolute = Math.abs(value)
  if (absolute >= 1_00_00_000) return `${currencySymbol} ${(value / 1_00_00_000).toFixed(2)} Cr`
  if (absolute >= 1_00_000) return `${currencySymbol} ${(value / 1_00_000).toFixed(2)} L`
  if (absolute >= 1_000) return `${currencySymbol} ${(value / 1_000).toFixed(1)} K`
  return money(value)
}

/** The bare number, for a chart axis that already names its unit. */
export function moneyAxis(value: number): string {
  const absolute = Math.abs(value)
  if (absolute >= 1_00_00_000) return `${(value / 1_00_00_000).toFixed(1)}Cr`
  if (absolute >= 1_00_000) return `${(value / 1_00_000).toFixed(0)}L`
  if (absolute >= 1_000) return `${(value / 1_000).toFixed(0)}K`
  return String(Math.round(value))
}

export function percent(value: number | null | undefined, digits = 1): string {
  if (value === null || value === undefined) return '—'
  return `${value.toFixed(digits)}%`
}

export function count(value: number | null | undefined): string {
  if (value === null || value === undefined) return '—'
  return new Intl.NumberFormat('en-IN').format(value)
}

export function decimal(value: number | null | undefined, digits = 2): string {
  if (value === null || value === undefined) return '—'
  return new Intl.NumberFormat('en-IN', {
    minimumFractionDigits: digits,
    maximumFractionDigits: digits,
  }).format(value)
}

/**
 * A date as a person reads it.
 *
 * The backend sends ISO dates and timestamps; only the date part is shown where the time of day
 * carries no meaning for the reader.
 */
export function date(value: string | null | undefined): string {
  if (!value) return '—'
  const parsed = new Date(value)
  if (Number.isNaN(parsed.getTime())) return '—'
  return parsed.toLocaleDateString('en-IN', { day: '2-digit', month: 'short', year: 'numeric' })
}

export function dateTime(value: string | null | undefined): string {
  if (!value) return '—'
  const parsed = new Date(value)
  if (Number.isNaN(parsed.getTime())) return '—'
  return parsed.toLocaleString('en-IN', {
    day: '2-digit',
    month: 'short',
    year: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
  })
}

/** A month key (`2026-03`) as `Mar 2026`, for a trend axis. */
export function month(value: string | null | undefined): string {
  if (!value) return '—'
  const [year, monthPart] = value.split('-')
  if (!year || !monthPart) return value
  const parsed = new Date(Number(year), Number(monthPart) - 1, 1)
  if (Number.isNaN(parsed.getTime())) return value
  return parsed.toLocaleDateString('en-IN', { month: 'short', year: 'numeric' })
}

/** Relative time for an activity feed, where "3 days ago" reads better than a timestamp. */
export function relative(value: string | null | undefined): string {
  if (!value) return '—'
  const parsed = new Date(value)
  if (Number.isNaN(parsed.getTime())) return '—'
  const seconds = Math.round((Date.now() - parsed.getTime()) / 1000)
  if (seconds < 60) return 'just now'
  if (seconds < 3600) return `${Math.floor(seconds / 60)}m ago`
  if (seconds < 86_400) return `${Math.floor(seconds / 3600)}h ago`
  if (seconds < 2_592_000) return `${Math.floor(seconds / 86_400)}d ago`
  return date(value)
}

/**
 * A human label for a stored enum value.
 *
 * The database stores `UNDER_MAINTENANCE`; a person reads "Under maintenance". The stored value is
 * never renamed anywhere else - only its label is derived, so the same value can never be spelled
 * two ways on two screens.
 */
export function humanize(value: string | null | undefined): string {
  if (!value) return '—'
  const spaced = value.replace(/_/g, ' ').toLowerCase()
  return spaced.charAt(0).toUpperCase() + spaced.slice(1)
}

/** Initials, for an avatar that carries identity without an invented photograph. */
export function initials(value: string | null | undefined): string {
  if (!value) return '—'
  const parts = value.trim().split(/\s+/).filter(Boolean)
  if (parts.length === 0) return '—'
  if (parts.length === 1) return parts[0].slice(0, 2).toUpperCase()
  return (parts[0][0] + parts[parts.length - 1][0]).toUpperCase()
}

/** True when a date is inside the next `days` days - used for the expiry watchlists. */
export function withinDays(value: string | null | undefined, days: number): boolean {
  if (!value) return false
  const parsed = new Date(value)
  if (Number.isNaN(parsed.getTime())) return false
  const remaining = parsed.getTime() - Date.now()
  return remaining >= 0 && remaining <= days * 86_400_000
}

/** Whole days from today to a date. Negative when the date has passed. */
export function daysUntil(value: string | null | undefined): number | null {
  if (!value) return null
  const parsed = new Date(value)
  if (Number.isNaN(parsed.getTime())) return null
  return Math.ceil((parsed.getTime() - Date.now()) / 86_400_000)
}
