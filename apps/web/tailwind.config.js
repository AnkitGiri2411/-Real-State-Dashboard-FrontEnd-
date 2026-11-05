/**
 * ============================================================================
 * Meridian Estates - Real Estate Management Dashboard
 * Tailwind theme layer.
 * ============================================================================
 * Every value here is backed by a CSS custom property declared in
 * `src/styles.css`, which is the single source of truth for the theme (and the
 * thing `applyBrandTheme` rewrites for a rebranded installation).
 *
 * There is deliberately no second, literal palette in this file: a duplicated
 * colour scale is exactly the thing that drifts away from the real theme.
 *
 * The colour identities (forest, architectural, copper, champagne, sandstone,
 * sapphire, teal, plum, emerald, amber, terracotta, burgundy, slate) are
 * exposed as `<name>`, `<name>-soft` and `<name>-ink`, so a component can say
 * `bg-emerald-soft text-emerald-ink` rather than reaching for a hex, and the
 * three roles stay tied together.
 * ============================================================================
 */
/** @type {import('tailwindcss').Config} */

/* The identities the interface reasons in. Order matters only for readability. */
const IDENTITIES = [
  'forest',
  'architectural',
  'copper',
  'champagne',
  'sandstone',
  'sapphire',
  'teal',
  'plum',
  'emerald',
  'amber',
  'terracotta',
  'burgundy',
  'slate',
]

const identityColors = Object.fromEntries(
  IDENTITIES.flatMap((name) => [
    [name, `var(--accent-${name})`],
    [`${name}-soft`, `var(--surface-${name})`],
    [`${name}-ink`, `var(--ink-${name})`],
  ]),
)

export default {
  content: ['./index.html', './src/**/*.{ts,tsx}'],
  theme: {
    extend: {
      colors: {
        /* surfaces */
        background: 'var(--background)',
        surface: 'var(--surface)',
        'surface-elevated': 'var(--surface-elevated)',
        'surface-muted': 'var(--surface-muted)',
        'surface-sunken': 'var(--surface-sunken)',

        /* brand */
        primary: 'var(--primary)',
        'primary-hover': 'var(--primary-hover)',
        'primary-active': 'var(--primary-active)',
        'primary-soft': 'var(--primary-soft)',
        secondary: 'var(--secondary)',
        'secondary-hover': 'var(--secondary-hover)',
        'secondary-soft': 'var(--secondary-soft)',
        accent: 'var(--accent)',
        'accent-soft': 'var(--accent-soft)',

        /* the property and investment identities */
        ...identityColors,

        /* text */
        'text-primary': 'var(--text-primary)',
        'text-secondary': 'var(--text-secondary)',

        /* lines */
        border: 'var(--border)',
        'border-strong': 'var(--border-strong)',

        /* semantic */
        success: 'var(--success)',
        warning: 'var(--warning)',
        danger: 'var(--danger)',
        info: 'var(--info)',
        premium: 'var(--status-premium)',

        /* the unit-board status vocabulary */
        'status-available': 'var(--status-available)',
        'status-occupied': 'var(--status-occupied)',
        'status-reserved': 'var(--status-reserved)',
        'status-listed': 'var(--status-listed)',
        'status-under-contract': 'var(--status-under-contract)',
        'status-maintenance': 'var(--status-maintenance)',
        'status-sold': 'var(--status-sold)',

        /* the hero band, read in parchment */
        'hero-text': 'var(--hero-text)',
        'hero-muted': 'var(--hero-muted)'
      },

      /*
       * Meridian's own component geometry.
       *
       * Deliberately NOT the hotel's soft set (18/24/999/12). This product is
       * rectilinear and drafting-like: a 4px control and a 3px chip read as an
       * architectural drawing, where a 999px pill reads as a hospitality
       * brochure. The curves are the difference between the two products that a
       * reader registers before they read a single word.
       */
      borderRadius: {
        card: 'var(--radius-card)',
        panel: 'var(--radius-panel)',
        control: 'var(--radius-control)',
        field: 'var(--radius-field)',
        chip: 'var(--radius-chip)'
      },

      boxShadow: {
        card: 'var(--shadow-card)',
        soft: 'var(--shadow-soft)',
        lift: 'var(--shadow-lift)',
        hero: 'var(--shadow-hero)'
      },

      fontFamily: {
        body: 'var(--font-body)',
        display: 'var(--font-display)'
      }
    }
  },
  plugins: []
}
