import clsx from 'clsx'
import { X } from 'lucide-react'
import {
  createContext, useCallback, useContext, useEffect, useId, useRef, useState,
  type ButtonHTMLAttributes, type InputHTMLAttributes, type ReactNode, type SelectHTMLAttributes,
  type TextareaHTMLAttributes,
} from 'react'

/**
 * The interface primitives.
 *
 * These exist so a screen describes *what a thing is* - a KPI, a panel, a status, a register - and
 * the design system decides what it looks like. No page picks a colour or a radius: it names a
 * `Tone`, and the tone resolves to the three tokens the stylesheet expects.
 *
 * Several classes in `styles.css` read custom properties from their call site. That contract is
 * honoured here, once per primitive, instead of being repeated as an inline style on every page -
 * which is also what stops a card from silently falling back to a default surface because a page
 * forgot to pass `--tint`.
 */

// ---------------------------------------------------------------- tones

/** The product's thirteen colour identities. Each is a role, not a decoration. */
export type Tone =
  | 'forest' | 'architectural' | 'copper' | 'champagne' | 'sandstone' | 'sapphire'
  | 'teal' | 'plum' | 'emerald' | 'amber' | 'terracotta' | 'burgundy' | 'slate'
  | 'neutral' | 'primary' | 'secondary'

/** The mark half of each tone - a line, a bar, a dot. Clears 3:1 as a graphic. */
export const TONE_COLOUR: Record<Tone, string> = {
  forest: 'var(--accent-forest)',
  architectural: 'var(--accent-architectural)',
  copper: 'var(--accent-copper)',
  champagne: 'var(--accent-champagne)',
  sandstone: 'var(--accent-sandstone)',
  sapphire: 'var(--accent-sapphire)',
  teal: 'var(--accent-teal)',
  plum: 'var(--accent-plum)',
  emerald: 'var(--accent-emerald)',
  amber: 'var(--accent-amber)',
  terracotta: 'var(--accent-terracotta)',
  burgundy: 'var(--accent-burgundy)',
  slate: 'var(--accent-slate)',
  neutral: 'var(--text-secondary)',
  primary: 'var(--primary)',
  secondary: 'var(--secondary)',
}

/** The wash half - a card, a tile, a pane. */
export const TONE_SURFACE: Record<Tone, string> = {
  forest: 'var(--surface-forest)',
  architectural: 'var(--surface-architectural)',
  copper: 'var(--surface-copper)',
  champagne: 'var(--surface-champagne)',
  sandstone: 'var(--surface-sandstone)',
  sapphire: 'var(--surface-sapphire)',
  teal: 'var(--surface-teal)',
  plum: 'var(--surface-plum)',
  emerald: 'var(--surface-emerald)',
  amber: 'var(--surface-amber)',
  terracotta: 'var(--surface-terracotta)',
  burgundy: 'var(--surface-burgundy)',
  slate: 'var(--surface-slate)',
  neutral: 'var(--surface-muted)',
  primary: 'var(--primary-soft)',
  secondary: 'var(--secondary-soft)',
}

/** The readable half - a label, a figure, a badge. Clears 4.5:1 on its own wash. */
export const TONE_INK: Record<Tone, string> = {
  forest: 'var(--ink-forest)',
  architectural: 'var(--ink-architectural)',
  copper: 'var(--ink-copper)',
  champagne: 'var(--ink-champagne)',
  sandstone: 'var(--ink-sandstone)',
  sapphire: 'var(--ink-sapphire)',
  teal: 'var(--ink-teal)',
  plum: 'var(--ink-plum)',
  emerald: 'var(--ink-emerald)',
  amber: 'var(--ink-amber)',
  terracotta: 'var(--ink-terracotta)',
  burgundy: 'var(--ink-burgundy)',
  slate: 'var(--ink-slate)',
  neutral: 'var(--text-secondary)',
  primary: 'var(--ink-forest)',
  secondary: 'var(--ink-copper)',
}

/** The tone a status carries, so one state is never two colours on two screens. */
export function toneForStatus(status: string | null | undefined): Tone {
  switch ((status ?? '').toUpperCase()) {
    case 'ACTIVE': case 'OCCUPIED': case 'AVAILABLE': case 'PAID': case 'COMPLETED':
    case 'APPROVED': case 'WON': case 'SOLD': case 'CLOSED': case 'RESOLVED': case 'PASSED':
    case 'LET_AGREED':
      return 'emerald'
    case 'RESERVED': case 'UNDER_CONTRACT': case 'PENDING': case 'SUBMITTED': case 'SCREENING':
    case 'IN_PROGRESS': case 'OFFERED': case 'NEGOTIATION': case 'QUALIFIED': case 'EXPIRING':
    case 'UNDER_OFFER': case 'ASSIGNED': case 'WAIVED': case 'PUBLISHED': case 'LISTED':
      return 'amber'
    case 'OVERDUE': case 'FAILED': case 'HIGH': case 'URGENT': case 'LOST': case 'REJECTED':
    case 'CANCELLED': case 'NO_SHOW': case 'TERMINATED': case 'EXPIRED': case 'WITHDRAWN':
    case 'BLACKLISTED': case 'VOID': case 'VOIDED': case 'WRITTEN_OFF': case 'SUSPENDED':
      return 'terracotta'
    case 'UNDER_MAINTENANCE': case 'ARCHIVED': case 'SUPERSEDED': case 'INACTIVE': case 'DRAFT':
      return 'slate'
    case 'NEW': case 'OPEN': case 'CONTACTED': case 'SCHEDULED': case 'CONFIRMED': case 'VIEWING':
    case 'LEAD':
      return 'sapphire'
    case 'RENEWED': case 'RENEWAL': case 'CONVERTED': case 'ISSUED': case 'COUNTERED':
      return 'teal'
    default:
      return 'neutral'
  }
}

// ---------------------------------------------------------------- primitives

export function Spinner({ className, style }: { className?: string; style?: React.CSSProperties }) {
  return (
    <span
      role="status"
      aria-label="Loading"
      style={style}
      className={clsx(
        'inline-block h-4 w-4 animate-spin rounded-full border-2 border-current border-t-transparent',
        className,
      )}
    />
  )
}

type ButtonProps = ButtonHTMLAttributes<HTMLButtonElement> & {
  variant?: 'primary' | 'secondary' | 'outline' | 'ghost' | 'danger'
  size?: 'sm' | 'md'
  loading?: boolean
}

export function Button({
  variant = 'outline', size = 'md', loading = false, className, children, disabled, ...rest
}: ButtonProps) {
  return (
    <button
      type="button"
      className={clsx(
        'focus-meridian btn',
        variant === 'primary' && 'btn-primary',
        variant === 'secondary' && 'btn-secondary',
        variant === 'outline' && 'btn-outline',
        variant === 'ghost' && 'btn-ghost',
        variant === 'danger' && 'btn-danger',
        size === 'sm' && 'px-3 py-1.5 text-xs',
        className,
      )}
      disabled={disabled || loading}
      aria-busy={loading || undefined}
      {...rest}
    >
      {loading && <Spinner className="h-3.5 w-3.5" />}
      {children}
    </button>
  )
}

export function Badge({ tone = 'neutral', children, className }: {
  tone?: Tone; children: ReactNode; className?: string
}) {
  return (
    <span
      className={clsx('badge', className)}
      style={{ background: TONE_SURFACE[tone], color: TONE_INK[tone] }}
    >
      {children}
    </span>
  )
}

/**
 * A status, said twice: as a colour and as a word.
 *
 * Colour alone must never be the signal - a reader who cannot distinguish the hues still needs to
 * know the state, so the stored value is always spelled out beside its mark.
 */
export function StatusBadge({ status, className }: { status: string | null | undefined; className?: string }) {
  const tone = toneForStatus(status)
  return (
    <Badge tone={tone} className={className}>
      <span
        aria-hidden="true"
        className="inline-block h-1.5 w-1.5 rounded-full"
        style={{ background: TONE_COLOUR[tone] }}
      />
      {(status ?? 'Unknown').replace(/_/g, ' ')}
    </Badge>
  )
}

/**
 * A KPI tile.
 *
 * Supplies the three custom properties `.tile` / `.tile-interactive` read, so the lift on hover and
 * the leading plot-boundary edge both work without the caller knowing the class names.
 */
export function KpiTile({
  label, value, caption, tone = 'neutral', icon, onClick, dense = false,
}: {
  label: string
  value: ReactNode
  caption?: ReactNode
  tone?: Tone
  icon?: ReactNode
  onClick?: () => void
  dense?: boolean
}) {
  const interactive = Boolean(onClick)
  const content = (
    <>
      <div className="flex items-start justify-between gap-3">
        <span className="eyebrow" style={{ color: TONE_INK[tone] }}>{label}</span>
        {icon && <span className="tile-icon grid h-7 w-7 place-items-center rounded-[3px]" aria-hidden="true">{icon}</span>}
      </div>
      <div className={clsx('kpi-value mt-2', dense ? 'text-xl' : 'text-2xl')} style={{ color: 'var(--text-primary)' }}>
        {value}
      </div>
      {caption && <div className="mt-1 text-xs" style={{ color: 'var(--text-secondary)' }}>{caption}</div>}
    </>
  )

  const style = {
    '--tile-surface': TONE_SURFACE[tone],
    '--tile-surface-hover': `var(--surface-${tone}-strong, ${TONE_SURFACE[tone]})`,
    '--tile-accent': TONE_COLOUR[tone],
  } as React.CSSProperties

  if (interactive) {
    return (
      <button
        type="button"
        onClick={onClick}
        style={style}
        className="tile tile-interactive focus-meridian block w-full p-4 text-left"
      >
        {content}
      </button>
    )
  }
  return <div style={style} className="tile p-4">{content}</div>
}

/** A titled surface. `tint` supplies the `--tint` `surface-tinted` reads. */
export function Panel({
  title, subtitle, actions, children, tint, notch, className, bodyClassName, icon,
}: {
  title?: string
  subtitle?: string
  actions?: ReactNode
  children: ReactNode
  tint?: Tone
  notch?: boolean
  className?: string
  bodyClassName?: string
  icon?: ReactNode
}) {
  const style = {
    ...(tint ? { '--tint': TONE_SURFACE[tint] } : {}),
    ...(notch ? { '--notch': 'var(--background)' } : {}),
  } as React.CSSProperties

  return (
    <section
      style={style}
      className={clsx(
        /*
         * `min-w-0` because a Panel is always a layout child, and a grid or flex child carries
         * `min-width: auto` - which means it refuses to shrink below its own content's min-content
         * width and pushes the whole page wider than the viewport. A wide `<dd>` or a long
         * unbroken string inside a card was enough to give Settings a horizontal scrollbar. This is
         * fixed on the primitive rather than on the page so no future screen can reintroduce it.
         */
        'min-w-0',
        notch ? 'plot-card' : 'rounded-[var(--radius-panel)] border',
        tint ? 'surface-tinted' : 'panel-plain',
        !notch && 'border-[var(--border)]',
        'shadow-[var(--shadow-card)]',
        className,
      )}
    >
      {(title || actions) && (
        <header className={clsx('flex flex-wrap items-start justify-between gap-3', notch ? 'mb-3' : 'border-b border-[var(--border)] px-4 py-3')}>
          <div className="min-w-0">
            {title && (
              <h2 className="flex items-center gap-2 text-sm font-semibold" style={{ color: 'var(--text-primary)' }}>
                {icon && <span aria-hidden="true" style={{ color: tint ? TONE_INK[tint] : 'var(--secondary)' }}>{icon}</span>}
                {title}
              </h2>
            )}
            {subtitle && <p className="mt-0.5 text-xs" style={{ color: 'var(--text-secondary)' }}>{subtitle}</p>}
          </div>
          {actions && <div className="flex flex-wrap items-center gap-2">{actions}</div>}
        </header>
      )}
      <div className={clsx(notch ? '' : 'p-4', bodyClassName)}>{children}</div>
    </section>
  )
}

/** A section heading with the drawing's own scale-bar rule under it. */
export function SectionHeader({ eyebrow, title, description, rule = 'secondary', actions }: {
  eyebrow?: string
  title: string
  description?: string
  rule?: Tone
  actions?: ReactNode
}) {
  return (
    <div className="mb-4" style={{ '--rule': TONE_COLOUR[rule] } as React.CSSProperties}>
      <div className="flex flex-wrap items-end justify-between gap-3">
        <div className="min-w-0">
          {eyebrow && <div className="eyebrow" style={{ color: TONE_INK[rule] }}>{eyebrow}</div>}
          <h2 className="font-display text-lg font-semibold" style={{ color: 'var(--text-primary)' }}>{title}</h2>
          {description && <p className="mt-0.5 text-sm" style={{ color: 'var(--text-secondary)' }}>{description}</p>}
        </div>
        {actions}
      </div>
      <div className="survey-rule" aria-hidden="true" />
    </div>
  )
}

export function Field({
  label, htmlFor, error, hint, required, children,
}: {
  label: string
  htmlFor?: string
  error?: string | null
  hint?: string
  required?: boolean
  children: ReactNode
}) {
  return (
    <div className="min-w-0">
      <label htmlFor={htmlFor} className="mb-1 block text-xs font-semibold" style={{ color: 'var(--text-secondary)' }}>
        {label}{required && <span aria-hidden="true" style={{ color: 'var(--danger)' }}> *</span>}
      </label>
      {children}
      {error
        ? <p className="mt-1 text-xs" role="alert" style={{ color: 'var(--danger)' }}>{error}</p>
        : hint && <p className="mt-1 text-xs" style={{ color: 'var(--text-secondary)' }}>{hint}</p>}
    </div>
  )
}

export function Input({ className, ...rest }: InputHTMLAttributes<HTMLInputElement>) {
  return <input className={clsx('input focus-meridian', className)} {...rest} />
}

export function Select({ className, children, ...rest }: SelectHTMLAttributes<HTMLSelectElement>) {
  return <select className={clsx('select focus-meridian', className)} {...rest}>{children}</select>
}

export function Textarea({ className, ...rest }: TextareaHTMLAttributes<HTMLTextAreaElement>) {
  return <textarea className={clsx('textarea focus-meridian', className)} rows={3} {...rest} />
}

// ---------------------------------------------------------------- async states

/** A skeleton in the shape of what is coming, so the layout does not shift when data lands. */
export function Skeleton({ rows = 3, className }: { rows?: number; className?: string }) {
  return (
    <div className={clsx('space-y-2', className)} aria-hidden="true">
      {Array.from({ length: rows }).map((_, index) => (
        <div
          key={index}
          className="animate-pulse rounded-[var(--radius-field)]"
          style={{ background: 'var(--surface-sunken)', height: 14, width: `${92 - index * 9}%` }}
        />
      ))}
    </div>
  )
}

/**
 * Nothing here, and why.
 *
 * The wording names the actual thing that is missing and what would make it appear - a single
 * generic "No data" repeated on every screen tells the reader nothing about whether the portfolio
 * is empty or their filter was too narrow.
 */
export function EmptyState({ title, description, action, icon }: {
  title: string; description: string; action?: ReactNode; icon?: ReactNode
}) {
  return (
    <div className="flex flex-col items-center justify-center px-6 py-12 text-center">
      {icon && <span className="mb-3" aria-hidden="true" style={{ color: 'var(--text-secondary)' }}>{icon}</span>}
      <p className="font-display text-base font-semibold" style={{ color: 'var(--text-primary)' }}>{title}</p>
      <p className="mt-1 max-w-md text-sm" style={{ color: 'var(--text-secondary)' }}>{description}</p>
      {action && <div className="mt-4">{action}</div>}
    </div>
  )
}

/** A failure with a way out. Never a bare error string, and never a silent blank. */
export function ErrorState({ message, details, onRetry }: {
  message: string; details?: string[]; onRetry?: () => void
}) {
  return (
    <div role="alert" className="rounded-[var(--radius-card)] border p-4"
      style={{ borderColor: 'var(--border)', background: 'var(--danger-soft)' }}>
      <p className="text-sm font-semibold" style={{ color: 'var(--danger)' }}>{message}</p>
      {details && details.length > 0 && (
        <ul className="mt-2 list-disc pl-5 text-xs" style={{ color: 'var(--ink-terracotta)' }}>
          {details.map((detail) => <li key={detail}>{detail}</li>)}
        </ul>
      )}
      {onRetry && <div className="mt-3"><Button size="sm" onClick={onRetry}>Try again</Button></div>}
    </div>
  )
}

// ---------------------------------------------------------------- dialog

/**
 * A modal with the behaviour a keyboard reader expects: focus moves in and is trapped, Escape
 * closes, and focus returns to whatever opened it. A dialog that traps focus and never gives it back
 * is worse than no dialog.
 */
export function Dialog({
  open, title, description, onClose, children, footer, width = 'md',
}: {
  open: boolean
  title: string
  description?: string
  onClose: () => void
  children: ReactNode
  footer?: ReactNode
  width?: 'sm' | 'md' | 'lg'
}) {
  const panelRef = useRef<HTMLDivElement>(null)
  const restoreTo = useRef<HTMLElement | null>(null)
  const titleId = useId()
  const descriptionId = useId()

  useEffect(() => {
    if (!open) return undefined
    restoreTo.current = document.activeElement as HTMLElement | null

    const focusable = () =>
      panelRef.current?.querySelectorAll<HTMLElement>(
        'a[href], button:not([disabled]), textarea:not([disabled]), input:not([disabled]), select:not([disabled]), [tabindex]:not([tabindex="-1"])',
      ) ?? []

    const first = focusable()[0]
    ;(first ?? panelRef.current)?.focus()

    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        event.stopPropagation()
        onClose()
        return
      }
      if (event.key !== 'Tab') return
      const items = Array.from(focusable())
      if (items.length === 0) return
      const firstItem = items[0]
      const lastItem = items[items.length - 1]
      if (event.shiftKey && document.activeElement === firstItem) {
        event.preventDefault()
        lastItem.focus()
      } else if (!event.shiftKey && document.activeElement === lastItem) {
        event.preventDefault()
        firstItem.focus()
      }
    }

    document.addEventListener('keydown', onKeyDown)
    const previousOverflow = document.body.style.overflow
    document.body.style.overflow = 'hidden'

    return () => {
      document.removeEventListener('keydown', onKeyDown)
      document.body.style.overflow = previousOverflow
      restoreTo.current?.focus?.()
    }
  }, [open, onClose])

  if (!open) return null

  return (
    <div
      className="fixed inset-0 z-50 flex items-end justify-center overflow-y-auto p-0 sm:items-center sm:p-4"
      style={{ background: 'var(--overlay)' }}
      onClick={(event) => { if (event.target === event.currentTarget) onClose() }}
    >
      <div
        ref={panelRef}
        role="dialog"
        aria-modal="true"
        aria-labelledby={titleId}
        aria-describedby={description ? descriptionId : undefined}
        tabIndex={-1}
        className={clsx(
          'w-full max-h-[92vh] overflow-y-auto rounded-t-[var(--radius-panel)] sm:rounded-[var(--radius-panel)]',
          width === 'sm' && 'sm:max-w-sm', width === 'md' && 'sm:max-w-lg', width === 'lg' && 'sm:max-w-3xl',
        )}
        style={{ background: 'var(--surface-elevated)', boxShadow: 'var(--shadow-soft)' }}
      >
        <header className="flex items-start justify-between gap-4 border-b px-4 py-3" style={{ borderColor: 'var(--border)' }}>
          <div className="min-w-0">
            <h2 id={titleId} className="font-display text-base font-semibold" style={{ color: 'var(--text-primary)' }}>{title}</h2>
            {description && <p id={descriptionId} className="mt-0.5 text-xs" style={{ color: 'var(--text-secondary)' }}>{description}</p>}
          </div>
          <button
            type="button"
            onClick={onClose}
            aria-label="Close dialog"
            className="focus-meridian rounded-[var(--radius-control)] p-1.5"
            style={{ color: 'var(--text-secondary)' }}
          >
            <X size={16} aria-hidden="true" />
          </button>
        </header>
        <div className="px-4 py-4">{children}</div>
        {footer && <footer className="flex flex-wrap justify-end gap-2 border-t px-4 py-3" style={{ borderColor: 'var(--border)' }}>{footer}</footer>}
      </div>
    </div>
  )
}

// ---------------------------------------------------------------- table

export interface Column<T> {
  key: string
  header: string
  /** The cell. Returning a node lets a column render a badge or a link. */
  render: (row: T) => ReactNode
  /** Right-align a numeric column so a total lines up under its figures. */
  align?: 'left' | 'right'
  /** Percentage width for the `colgroup`, which is what keeps the register stable. */
  width?: string
  /** Hide below `sm` and show in the stacked mobile view instead. */
  secondary?: boolean
  sortKey?: string
}

/**
 * The register.
 *
 * A desktop table with `table-fixed` and percentage column widths, so a fetch never resizes the
 * page; the wrapper scrolls rather than the page. Below `sm` the same data is rendered as stacked
 * cards, because a nine-column table squeezed into 375px is not a mobile view - it is a desktop view
 * nobody can read.
 */
export function DataTable<T>({
  columns, rows, keyOf, loading, empty, onRowClick, sort, onSortChange, caption,
}: {
  columns: Column<T>[]
  rows: T[]
  keyOf: (row: T) => string
  loading?: boolean
  empty: ReactNode
  onRowClick?: (row: T) => void
  sort?: string
  onSortChange?: (sort: string) => void
  caption?: string
}) {
  if (loading) return <div className="p-4"><Skeleton rows={6} /></div>
  if (rows.length === 0) return <>{empty}</>

  const activeSortKey = sort?.split(',')[0]
  const activeDirection = sort?.split(',')[1] === 'desc' ? 'desc' : 'asc'

  const toggleSort = (column: Column<T>) => {
    if (!column.sortKey || !onSortChange) return
    const next = activeSortKey === column.sortKey && activeDirection === 'asc' ? 'desc' : 'asc'
    onSortChange(`${column.sortKey},${next}`)
  }

  return (
    <>
      {/* Desktop */}
      {/*
       * A register wider than the viewport scrolls sideways, and a keyboard user has to be able to
       * reach that scroll. The container holds no focusable element of its own - a sorted column's
       * button exists only when the register offers sorting - so the container itself is made
       * focusable, and named after the table it holds when a caption was given.
       */}
      <div
        className="hidden w-full overflow-x-auto sm:block"
        tabIndex={0}
        role={caption ? 'region' : undefined}
        aria-label={caption}
      >
        <table className="table-ledger table-fixed">
          {caption && <caption className="sr-only">{caption}</caption>}
          <colgroup>
            {columns.map((column) => (
              <col key={column.key} style={{ width: column.width ?? `${Math.floor(100 / columns.length)}%` }} />
            ))}
          </colgroup>
          <thead>
            <tr>
              {columns.map((column) => {
                const sorted = activeSortKey === column.sortKey
                return (
                  <th
                    key={column.key}
                    scope="col"
                    className={column.align === 'right' ? 'text-right' : undefined}
                    aria-sort={sorted ? (activeDirection === 'asc' ? 'ascending' : 'descending') : undefined}
                  >
                    {column.sortKey && onSortChange ? (
                      <button
                        type="button"
                        onClick={() => toggleSort(column)}
                        className="focus-meridian inline-flex items-center gap-1"
                      >
                        {column.header}
                        <span aria-hidden="true" style={{ opacity: sorted ? 1 : 0.35 }}>
                          {sorted && activeDirection === 'desc' ? '▾' : '▴'}
                        </span>
                        <span className="sr-only">
                          {sorted ? `sorted ${activeDirection === 'asc' ? 'ascending' : 'descending'}` : 'sort by this column'}
                        </span>
                      </button>
                    ) : column.header}
                  </th>
                )
              })}
            </tr>
          </thead>
          <tbody>
            {rows.map((row) => (
              /*
               * A row that opens a record is a keyboard control, not only a mouse target.
               *
               * The stacked mobile view below already renders each row as a real button, so the same
               * action was reachable by keyboard on a phone and unreachable on a desktop - a register
               * whose columns render no link or button of their own left a keyboard user with no way
               * into the record at all. The row is therefore focusable and answers Enter and Space,
               * exactly as the mobile button does.
               *
               * `role` is deliberately left as the implicit `row`: giving it `button` would tear the
               * row out of the table it belongs to, and the cells under their headers are what name
               * it. The key is acted on only when the row itself holds focus - a register that
               * renders an action button in a cell must not have that button's Enter open the record
               * a second time as the event bubbles.
               */
              <tr
                key={keyOf(row)}
                onClick={onRowClick ? () => onRowClick(row) : undefined}
                tabIndex={onRowClick ? 0 : undefined}
                onKeyDown={onRowClick ? (event) => {
                  if (event.target !== event.currentTarget) return
                  if (event.key === 'Enter' || event.key === ' ' || event.key === 'Spacebar') {
                    event.preventDefault()
                    onRowClick(row)
                  }
                } : undefined}
                className={onRowClick ? 'focus-meridian cursor-pointer' : undefined}
              >
                {columns.map((column) => (
                  <td key={column.key} className={column.align === 'right' ? 'text-right tabular-nums' : 'truncate'}>
                    {column.render(row)}
                  </td>
                ))}
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      {/* Mobile: the same record, stacked. */}
      <ul className="divide-y sm:hidden" style={{ borderColor: 'var(--border)' }}>
        {rows.map((row) => {
          /*
           * The stacked row is a button only when the row does something.
           *
           * It used to be a button unconditionally, rendered `disabled` when the register has no row
           * action - and a disabled button is still a button. Any control a cell renders inside it
           * was then a focusable element nested in another control: the listings register put its
           * Withdraw, Expire, Move and Edit buttons inside one, which axe reports as
           * `nested-interactive` and which tells a screen reader the opposite of the truth about what
           * can be activated. A row with no action of its own is a plain container, and the controls
           * in its cells stand on their own.
           */
          const content = (
            <>
              <div className="flex flex-wrap items-baseline justify-between gap-2">
                {columns.filter((column) => !column.secondary).slice(0, 2).map((column) => (
                  <span key={column.key} className="text-sm font-semibold" style={{ color: 'var(--text-primary)' }}>
                    {column.render(row)}
                  </span>
                ))}
              </div>
              <dl className="mt-2 grid grid-cols-2 gap-x-3 gap-y-1">
                {columns.filter((column) => !column.secondary).slice(2).map((column) => (
                  <div key={column.key} className="min-w-0">
                    <dt className="text-[10px] font-bold uppercase tracking-wide" style={{ color: 'var(--text-secondary)' }}>
                      {column.header}
                    </dt>
                    <dd className="truncate text-xs" style={{ color: 'var(--text-primary)' }}>{column.render(row)}</dd>
                  </div>
                ))}
              </dl>
            </>
          )

          return (
            <li key={keyOf(row)}>
              {onRowClick ? (
                <button
                  type="button"
                  onClick={() => onRowClick(row)}
                  className="focus-meridian block w-full px-4 py-3 text-left"
                >
                  {content}
                </button>
              ) : (
                <div className="block w-full px-4 py-3 text-left">{content}</div>
              )}
            </li>
          )
        })}
      </ul>
    </>
  )
}

/** The pager under a register. */
export function Pagination({ page, size, totalElements, totalPages, hasNext, onPageChange, onSizeChange }: {
  page: number; size: number; totalElements: number; totalPages: number; hasNext: boolean
  onPageChange: (page: number) => void
  onSizeChange?: (size: number) => void
}) {
  const first = totalElements === 0 ? 0 : page * size + 1
  const last = Math.min((page + 1) * size, totalElements)
  return (
    <nav className="flex flex-wrap items-center justify-between gap-3 px-4 py-3" aria-label="Pagination">
      <p className="text-xs" style={{ color: 'var(--text-secondary)' }}>
        Showing <strong>{first}</strong>–<strong>{last}</strong> of <strong>{totalElements}</strong>
      </p>
      <div className="flex items-center gap-2">
        {onSizeChange && (
          <label className="flex items-center gap-1 text-xs" style={{ color: 'var(--text-secondary)' }}>
            Rows
            <Select
              className="w-auto py-1 text-xs"
              value={String(size)}
              onChange={(event) => onSizeChange(Number(event.target.value))}
            >
              {[10, 20, 50, 100].map((option) => <option key={option} value={option}>{option}</option>)}
            </Select>
          </label>
        )}
        <Button size="sm" onClick={() => onPageChange(page - 1)} disabled={page <= 0}>Previous</Button>
        <span className="text-xs tabular-nums" style={{ color: 'var(--text-secondary)' }}>
          {totalPages === 0 ? 0 : page + 1} / {Math.max(totalPages, 1)}
        </span>
        <Button size="sm" onClick={() => onPageChange(page + 1)} disabled={!hasNext}>Next</Button>
      </div>
    </nav>
  )
}

/** A filter bar. Children are the controls; this only supplies the layout that keeps them usable. */
export function Toolbar({ children, className }: { children: ReactNode; className?: string }) {
  return (
    <div className={clsx('flex flex-wrap items-end gap-2 border-b px-4 py-3', className)} style={{ borderColor: 'var(--border)' }}>
      {children}
    </div>
  )
}

/** A label/value pair, for a detail header. */
export function DetailItem({ label, value, tone }: { label: string; value: ReactNode; tone?: Tone }) {
  return (
    <div className="min-w-0">
      <dt className="text-[10px] font-bold uppercase tracking-wide" style={{ color: 'var(--text-secondary)' }}>{label}</dt>
      <dd className="mt-0.5 truncate text-sm" style={{ color: tone ? TONE_INK[tone] : 'var(--text-primary)' }}>{value}</dd>
    </div>
  )
}

// ---------------------------------------------------------------- toasts

interface Toast { id: number; tone: Tone; message: string }

const ToastContext = createContext<{ push: (message: string, tone?: Tone) => void }>({ push: () => {} })

/** Show a confirmation or a failure. Mutations report their outcome here rather than silently. */
export function useToast() {
  return useContext(ToastContext)
}

export function ToastProvider({ children }: { children: ReactNode }) {
  const [toasts, setToasts] = useState<Toast[]>([])

  const push = useCallback((message: string, tone: Tone = 'emerald') => {
    const id = Date.now() + Math.random()
    setToasts((current) => [...current, { id, tone, message }])
    window.setTimeout(() => setToasts((current) => current.filter((toast) => toast.id !== id)), 5000)
  }, [])

  return (
    <ToastContext.Provider value={{ push }}>
      {children}
      <div aria-live="polite" aria-atomic="false" className="pointer-events-none fixed bottom-4 right-4 z-[60] flex w-80 flex-col gap-2">
        {toasts.map((toast) => (
          <div
            key={toast.id}
            className="pointer-events-auto rounded-[var(--radius-card)] border px-3 py-2 text-sm shadow-[var(--shadow-soft)]"
            style={{ background: TONE_SURFACE[toast.tone], borderColor: TONE_COLOUR[toast.tone], color: TONE_INK[toast.tone] }}
          >
            {toast.message}
          </div>
        ))}
      </div>
    </ToastContext.Provider>
  )
}
