import { motion, useReducedMotion } from 'framer-motion'
import { useId } from 'react'
import { TONE_COLOUR, TONE_INK, TONE_SURFACE, type Tone } from './index'
import { moneyAxis, month as monthLabel } from './formatters'

/**
 * The dashboard's data shapes, drawn as SVG.
 *
 * There is no charting library here on purpose. A chart is a claim about the business, and the
 * shapes below are chosen because the data has that shape - a proportion of a fixed whole is a ring,
 * a book that narrows is a funnel, a series over time is an area. Every colour is a design token,
 * every chart names its units, and every one renders a real empty state rather than an axis with
 * nothing on it.
 *
 * Animation is skipped entirely when the reader has asked for reduced motion. A CSS media query
 * cannot reach an inline SVG transform, so each component gates on `useReducedMotion` itself.
 */

const SERIES: Tone[] = ['forest', 'copper', 'teal', 'sapphire', 'emerald', 'amber', 'plum', 'sandstone']

/**
 * A number an SVG attribute can actually carry.
 *
 * A series point whose field the API did not send arrives as `undefined`, and one `undefined` is
 * enough to make `Math.max` return `NaN` for the whole series - which renders `y="NaN"` on every
 * element and floods the console with invalid-attribute errors while leaving an empty chart on
 * screen. Coercing here means a missing figure draws as zero, which is visibly a gap the reader can
 * question, rather than as a broken graphic nobody can diagnose.
 */
function finite(value: number | null | undefined): number {
  return typeof value === 'number' && Number.isFinite(value) ? value : 0
}

export interface SeriesPoint {
  label: string
  value: number
  /** Pins a point to an identity instead of taking one from the rotation. */
  tone?: Tone
}

function EmptyChart({ title, description }: { title: string; description: string }) {
  return (
    <div className="flex h-40 flex-col items-center justify-center text-center">
      <p className="text-sm font-semibold" style={{ color: 'var(--text-primary)' }}>{title}</p>
      <p className="mt-1 max-w-xs text-xs" style={{ color: 'var(--text-secondary)' }}>{description}</p>
    </div>
  )
}

/* ============================================================
 * OccupancyRing - a proportion of a fixed whole
 * ============================================================ */
export function OccupancyRing({
  value, display, caption, tone = 'primary', size = 168, target,
}: {
  value: number
  display: string
  caption: string
  tone?: Tone
  size?: number
  target?: number
}) {
  const reduce = useReducedMotion()
  const clamp = Math.max(0, Math.min(100, value))
  const radius = (size - 22) / 2
  const circumference = 2 * Math.PI * radius
  const dash = (clamp / 100) * circumference
  const targetAngle = target === undefined ? null : (Math.max(0, Math.min(100, target)) / 100) * 360

  return (
    <figure className="flex flex-col items-center">
      <svg width={size} height={size} role="img" aria-label={`${caption}: ${display}`}>
        <g transform={`rotate(-90 ${size / 2} ${size / 2})`}>
          <circle cx={size / 2} cy={size / 2} r={radius} fill="none" strokeWidth={12} stroke={TONE_SURFACE[tone]} />
          <motion.circle
            cx={size / 2}
            cy={size / 2}
            r={radius}
            fill="none"
            strokeWidth={12}
            stroke={TONE_COLOUR[tone]}
            strokeLinecap="butt"
            strokeDasharray={`${dash} ${circumference}`}
            initial={reduce ? false : { strokeDasharray: `0 ${circumference}` }}
            animate={{ strokeDasharray: `${dash} ${circumference}` }}
            transition={{ duration: 0.7, ease: 'easeOut' }}
          />
          {targetAngle !== null && (
            <line
              x1={size / 2}
              y1={size / 2 - radius - 8}
              x2={size / 2}
              y2={size / 2 - radius + 8}
              stroke={TONE_COLOUR.copper}
              strokeWidth={3}
              transform={`rotate(${targetAngle} ${size / 2} ${size / 2})`}
            />
          )}
        </g>
        <text
          x="50%" y="47%" textAnchor="middle" dominantBaseline="middle"
          style={{ fontFamily: 'var(--font-display)', fontSize: size / 4.4, fill: 'var(--text-primary)' }}
        >
          {display}
        </text>
        <text
          x="50%" y="63%" textAnchor="middle"
          style={{ fontSize: 10, letterSpacing: '0.12em', textTransform: 'uppercase', fill: 'var(--text-secondary)' }}
        >
          {caption}
        </text>
      </svg>
      {target !== undefined && (
        <figcaption className="mt-1 text-xs" style={{ color: 'var(--text-secondary)' }}>
          Managed against a {target}% target
        </figcaption>
      )}
    </figure>
  )
}

/* ============================================================
 * TrendArea - a series over time, with a collected overlay
 * ============================================================ */
export function TrendArea({
  points, title, unit = 'currency', primaryTone = 'forest', secondaryTone = 'copper', secondaryLabel,
}: {
  points: SeriesPoint[]
  title: string
  unit?: 'currency' | 'count'
  primaryTone?: Tone
  secondaryTone?: Tone
  secondaryLabel?: string
}) {
  const gradientId = useId()
  if (points.length === 0) {
    return <EmptyChart title={title} description="No months to chart yet — the series appears once invoices are raised." />
  }

  const width = 640
  const height = 200
  const padding = { top: 12, right: 12, bottom: 26, left: 46 }
  const values = points.map((point) => finite(point.value))
  const max = Math.max(...values, 1)
  const scaleX = (index: number) =>
    padding.left + (index * (width - padding.left - padding.right)) / Math.max(points.length - 1, 1)
  // `value / max` with a non-finite value is what produced `y="NaN"`; both operands are now numbers.
  const scaleY = (value: number) =>
    height - padding.bottom - (finite(value) / max) * (height - padding.top - padding.bottom)

  const line = points.map((point, index) => `${scaleX(index)},${scaleY(point.value)}`).join(' ')
  const area = `${padding.left},${height - padding.bottom} ${line} ${scaleX(points.length - 1)},${height - padding.bottom}`
  const format = unit === 'currency' ? moneyAxis : (value: number) => String(value)

  return (
    <figure>
      <svg viewBox={`0 0 ${width} ${height}`} className="h-48 w-full" role="img"
        aria-label={`${title}. ${points.length} months, peaking at ${format(max)}.`}>
        <defs>
          <linearGradient id={gradientId} x1="0" y1="0" x2="0" y2="1">
            <stop offset="0%" stopColor={TONE_COLOUR[primaryTone]} stopOpacity="0.32" />
            <stop offset="100%" stopColor={TONE_COLOUR[primaryTone]} stopOpacity="0.02" />
          </linearGradient>
        </defs>
        {[0, 0.5, 1].map((fraction) => (
          <g key={fraction}>
            <line
              x1={padding.left} x2={width - padding.right}
              y1={scaleY(max * fraction)} y2={scaleY(max * fraction)}
              stroke="var(--border)" strokeWidth={1}
            />
            <text x={padding.left - 6} y={scaleY(max * fraction) + 3} textAnchor="end"
              style={{ fontSize: 9, fill: 'var(--text-secondary)' }}>
              {format(max * fraction)}
            </text>
          </g>
        ))}
        <polygon points={area} fill={`url(#${gradientId})`} />
        <polyline points={line} fill="none" stroke={TONE_COLOUR[primaryTone]} strokeWidth={2} />
        {points.map((point, index) => (
          <circle key={point.label} cx={scaleX(index)} cy={scaleY(point.value)} r={2.5}
            fill={TONE_COLOUR[primaryTone]}>
            <title>{`${monthLabel(point.label)}: ${format(point.value)}`}</title>
          </circle>
        ))}
        {points.map((point, index) => (
          index % 2 === 0 ? (
            <text key={`label-${point.label}`} x={scaleX(index)} y={height - 8} textAnchor="middle"
              style={{ fontSize: 9, fill: 'var(--text-secondary)' }}>
              {monthLabel(point.label)}
            </text>
          ) : null
        ))}
      </svg>
      <figcaption className="mt-1 flex flex-wrap items-center gap-3 text-xs" style={{ color: 'var(--text-secondary)' }}>
        <span className="inline-flex items-center gap-1">
          <span aria-hidden="true" className="inline-block h-2 w-4" style={{ background: TONE_COLOUR[primaryTone] }} />
          {title}
        </span>
        {secondaryLabel && (
          <span className="inline-flex items-center gap-1">
            <span aria-hidden="true" className="inline-block h-2 w-4" style={{ background: TONE_COLOUR[secondaryTone] }} />
            {secondaryLabel}
          </span>
        )}
      </figcaption>
    </figure>
  )
}

/* ============================================================
 * ComparisonBars - a comparison across categories
 * ============================================================ */
export function ComparisonBars({
  points, title, unit = 'currency', onSelect,
}: {
  points: SeriesPoint[]
  title: string
  unit?: 'currency' | 'count'
  onSelect?: (label: string) => void
}) {
  if (points.length === 0) {
    return <EmptyChart title={title} description="Nothing to compare yet." />
  }
  const max = Math.max(...points.map((point) => finite(point.value)), 1)
  const format = unit === 'currency' ? moneyAxis : (value: number) => String(value)

  return (
    <figure>
      <ul className="space-y-2">
        {points.map((point, index) => {
          const tone = point.tone ?? SERIES[index % SERIES.length]
          const width = Math.max(2, (finite(point.value) / max) * 100)
          const content = (
            <>
              <div className="flex items-baseline justify-between gap-3">
                <span className="truncate text-xs font-medium" style={{ color: 'var(--text-primary)' }}>{point.label}</span>
                <span className="shrink-0 text-xs tabular-nums" style={{ color: TONE_INK[tone] }}>{format(point.value)}</span>
              </div>
              <div className="mt-1 h-2 w-full overflow-hidden rounded-[2px]" style={{ background: TONE_SURFACE[tone] }}>
                <div className="h-full" style={{ width: `${width}%`, background: TONE_COLOUR[tone] }} />
              </div>
            </>
          )
          return (
            <li key={point.label}>
              {onSelect
                ? <button type="button" onClick={() => onSelect(point.label)} className="focus-meridian block w-full text-left">{content}</button>
                : content}
            </li>
          )
        })}
      </ul>
      <figcaption className="sr-only">{title}</figcaption>
    </figure>
  )
}

/* ============================================================
 * Donut - a distribution across a fixed set of states
 * ============================================================ */
export function DistributionDonut({
  points, title, centerLabel, size = 180,
  toneFor,
}: {
  points: SeriesPoint[]
  title: string
  centerLabel?: string
  size?: number
  toneFor?: (label: string) => Tone
}) {
  const total = points.reduce((sum, point) => sum + finite(point.value), 0)
  if (total === 0) {
    return <EmptyChart title={title} description="No units are recorded against these states yet." />
  }

  const radius = size / 2 - 10
  const circumference = 2 * Math.PI * radius
  let offset = 0

  return (
    <figure className="flex flex-col items-center gap-3 sm:flex-row sm:items-center sm:justify-center">
      <svg width={size} height={size} role="img" aria-label={`${title}. ${points.map((p) => `${p.label}: ${p.value}`).join(', ')}`}>
        <g transform={`rotate(-90 ${size / 2} ${size / 2})`}>
          {points.map((point, index) => {
            const tone = point.tone ?? toneFor?.(point.label) ?? SERIES[index % SERIES.length]
            const length = (finite(point.value) / total) * circumference
            const circle = (
              <circle
                key={point.label}
                cx={size / 2} cy={size / 2} r={radius}
                fill="none" strokeWidth={16} stroke={TONE_COLOUR[tone]}
                strokeDasharray={`${length} ${circumference - length}`}
                strokeDashoffset={-offset}
              />
            )
            offset += length
            return circle
          })}
        </g>
        {centerLabel && (
          <text x="50%" y="50%" textAnchor="middle" dominantBaseline="middle"
            style={{ fontFamily: 'var(--font-display)', fontSize: 18, fill: 'var(--text-primary)' }}>
            {centerLabel}
          </text>
        )}
      </svg>
      {/* The legend is the accessible reading of the chart, not decoration. */}
      <ul className="w-full space-y-1 sm:w-auto">
        {points.map((point, index) => {
          const tone = point.tone ?? toneFor?.(point.label) ?? SERIES[index % SERIES.length]
          const share = Math.round((finite(point.value) / total) * 100)
          return (
            <li key={point.label} className="flex items-center justify-between gap-3 text-xs">
              <span className="inline-flex items-center gap-2" style={{ color: 'var(--text-primary)' }}>
                <span aria-hidden="true" className="inline-block h-3 w-3 rounded-[2px]" style={{ background: TONE_COLOUR[tone] }} />
                {point.label.replace(/_/g, ' ')}
              </span>
              <span className="tabular-nums" style={{ color: 'var(--text-secondary)' }}>
                {point.value} · {share}%
              </span>
            </li>
          )
        })}
      </ul>
      <figcaption className="sr-only">{title}</figcaption>
    </figure>
  )
}

/* ============================================================
 * Funnel - a book that narrows
 * ============================================================ */
export function Funnel({ steps, title }: { steps: SeriesPoint[]; title: string }) {
  const top = finite(steps[0]?.value)
  if (steps.length === 0 || top === 0) {
    return <EmptyChart title={title} description="No leads have been recorded yet, so there is no pipeline to show." />
  }
  return (
    <figure>
      <ol className="space-y-1.5">
        {steps.map((step, index) => {
          const tone = SERIES[index % SERIES.length]
          const width = Math.max(8, (finite(step.value) / top) * 100)
          return (
            <li key={step.label} className="flex items-center gap-3">
              <span className="w-24 shrink-0 text-xs" style={{ color: 'var(--text-secondary)' }}>{step.label}</span>
              <span className="relative h-6 min-w-0 flex-1 rounded-[2px]" style={{ background: TONE_SURFACE[tone] }}>
                <span className="absolute inset-y-0 left-0 rounded-[2px]" style={{ width: `${width}%`, background: TONE_COLOUR[tone] }} />
                {/*
                  The figure rides on a chip of the tone's own WASH rather than directly on the bar.
                  The bar behind it is the ACCENT colour, and ink-on-accent is not a pair the palette
                  guarantees - axe measured one funnel label below 4.5:1 for exactly that reason. On
                  the wash it is the pair `theme.test.tsx` asserts, so the label is legible whatever
                  fraction of the track the bar happens to fill.
                */}
                <span className="absolute inset-y-0 left-1.5 flex items-center">
                  <span
                    className="rounded-[2px] px-1 text-[11px] font-semibold tabular-nums"
                    style={{ background: TONE_SURFACE[tone], color: TONE_INK[tone] }}
                  >
                    {step.value}
                    {step.value > 0 && index > 0 && (
                      <span className="ml-1 font-normal">· {Math.round((step.value / top) * 100)}%</span>
                    )}
                  </span>
                </span>
              </span>
            </li>
          )
        })}
      </ol>
      <figcaption className="sr-only">{title}</figcaption>
    </figure>
  )
}

/* ============================================================
 * PropertyMap - a real projection over real coordinates
 * ============================================================ */
export interface MapMarker {
  id: string
  code: string
  name: string
  city: string | null
  status: string
  latitude: number
  longitude: number
  occupiedUnits?: number
  unitCount?: number
}

/**
 * The portfolio, plotted.
 *
 * Only properties whose coordinates the database actually holds appear here. A property with no
 * position is **omitted**, never placed at a guessed point - a map is a factual claim about where
 * the firm's assets are, and a fabricated marker on it is worse than an absent one. The projection
 * is an equirectangular fit to the data's own bounding box, which is honest about being a schematic
 * rather than a survey.
 */
export function PropertyMap({
  markers, onSelect, height = 320,
}: {
  markers: MapMarker[]
  onSelect?: (marker: MapMarker) => void
  height?: number
}) {
  if (markers.length === 0) {
    return (
      <EmptyChart
        title="No properties can be plotted"
        description="None of the portfolio's properties carry a latitude and longitude yet, so there is nothing to place on a map."
      />
    )
  }

  const width = 640
  const padding = 34
  const lats = markers.map((marker) => marker.latitude)
  const lons = markers.map((marker) => marker.longitude)
  const minLat = Math.min(...lats)
  const maxLat = Math.max(...lats)
  const minLon = Math.min(...lons)
  const maxLon = Math.max(...lons)
  // A single property, or a set on one line, would divide by zero - a tenth of a degree of padding
  // keeps a lone marker in the middle of the panel instead of at its edge.
  const latSpan = maxLat - minLat || 0.1
  const lonSpan = maxLon - minLon || 0.1

  const project = (marker: MapMarker) => ({
    x: padding + ((marker.longitude - minLon) / lonSpan) * (width - padding * 2),
    y: padding + ((maxLat - marker.latitude) / latSpan) * (height - padding * 2),
  })

  return (
    <figure>
      {/*
       * An image when it is only drawn, a group when its markers are links.
       *
       * `role="img"` describes a graphic with no interactive content, so an image role must not
       * contain focusable descendants. When `onSelect` is supplied each marker is a real anchor, and
       * a map of links cannot claim to be an image - the container becomes a labelled group instead,
       * and every marker keeps its own accessible name. The non-interactive map, where the markers
       * are dots with titles, stays an image, which is the correct description of it.
       */}
      <svg viewBox={`0 0 ${width} ${height}`} className="w-full" style={{ height }}
        role={onSelect ? 'group' : 'img'}
        aria-label={`Portfolio map. ${markers.length} properties plotted: ${markers.map((m) => `${m.name} in ${m.city ?? 'an unnamed city'}`).join('; ')}.`}>
        <rect x="0" y="0" width={width} height={height} fill="var(--surface-sunken)" rx="6" />
        {/* The plot grid, at a tenth of a degree, so the projection reads as a drawing. */}
        {Array.from({ length: 9 }).map((_, index) => (
          <line key={`v${index}`} x1={padding + (index * (width - padding * 2)) / 8} x2={padding + (index * (width - padding * 2)) / 8}
            y1={padding} y2={height - padding} stroke="var(--border-forest)" strokeWidth={1} />
        ))}
        {Array.from({ length: 7 }).map((_, index) => (
          <line key={`h${index}`} y1={padding + (index * (height - padding * 2)) / 6} y2={padding + (index * (height - padding * 2)) / 6}
            x1={padding} x2={width - padding} stroke="var(--border-forest)" strokeWidth={1} />
        ))}
        {markers.map((marker) => {
          const { x, y } = project(marker)
          return (
            <g key={marker.id} transform={`translate(${x} ${y})`}>
              {onSelect ? (
                <a
                  href={`/properties/${marker.id}`}
                  onClick={(event) => { event.preventDefault(); onSelect(marker) }}
                  aria-label={`${marker.name}, ${marker.city ?? 'location unknown'}, ${marker.status.replace(/_/g, ' ')}`}
                >
                  <circle r={11} fill="transparent" />
                  <rect x={-5} y={-5} width={10} height={10} fill={TONE_COLOUR.copper} stroke="var(--surface-elevated)" strokeWidth={1.5} />
                  <text x={8} y={4} style={{ fontSize: 9, fill: 'var(--text-primary)' }}>{marker.code}</text>
                </a>
              ) : (
                <>
                  <rect x={-5} y={-5} width={10} height={10} fill={TONE_COLOUR.copper} />
                  <title>{marker.name}</title>
                </>
              )}
            </g>
          )
        })}
      </svg>
      <figcaption className="mt-1 text-xs" style={{ color: 'var(--text-secondary)' }}>
        Plotted from the coordinates held against each property — {markers.length} of the portfolio.
        A property without a position is left off rather than placed at a guess.
      </figcaption>
    </figure>
  )
}
