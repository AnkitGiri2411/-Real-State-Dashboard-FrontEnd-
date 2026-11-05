// @vitest-environment node
import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'

/**
 * The design system's own contrast guarantee, measured rather than remembered.
 *
 * `src/styles.css` states that "Contrast for every text-bearing pair in this file is asserted in
 * `src/components/theme.test.tsx`, measured against these actual values rather than against
 * remembered ones". This is that assertion.
 *
 * It parses the real stylesheet, resolves the `var()` indirections the token layer uses, and
 * computes the WCAG 2.1 relative-luminance contrast ratio for each ink-on-its-own-wash pair. That
 * is the pair the interface actually renders - a badge's label on the badge's tint - so a token
 * edit that quietly drops a status label below legibility fails here rather than in review.
 *
 * The rule the palette is built on: every hue is drawn three times - a saturated HUE for large
 * fills, an ACCENT darkened to clear 3:1 as a *mark*, and an INK darkened to clear 4.5:1 as *text*.
 * This test holds the third of those to its promise.
 */

const STYLESHEET = readFileSync(resolve(__dirname, '../styles.css'), 'utf8')

/** The `:root { ... }` block, as a name -> raw value map. */
function readTokens(): Map<string, string> {
  // The :root block is a flat list of declarations - no nested braces - so the first `}` after it
  // closes it. Matching to the first brace rather than the last avoids swallowing the whole file.
  const rootBlock = /:root\s*\{([\s\S]*?)\}/.exec(STYLESHEET)
  if (!rootBlock) throw new Error('styles.css no longer declares a :root token block')

  const tokens = new Map<string, string>()
  const declaration = /(--[a-z0-9-]+)\s*:\s*([^;]+);/gi
  let match = declaration.exec(rootBlock[1])
  while (match) {
    tokens.set(match[1], match[2].trim())
    match = declaration.exec(rootBlock[1])
  }
  if (tokens.size === 0) throw new Error('styles.css declares no custom properties')
  return tokens
}

const TOKENS = readTokens()

/** Follow one level of `var(--x)` indirection, which the token layer uses liberally. */
function resolveToken(name: string): string {
  const raw = TOKENS.get(name)
  if (!raw) throw new Error(`Token ${name} is not declared in styles.css`)
  const indirect = /^var\(\s*(--[a-z0-9-]+)\s*\)$/i.exec(raw)
  if (indirect) return resolveToken(indirect[1])
  return raw
}

/** A hex colour as [r, g, b] in 0-255. */
function toRgb(value: string): [number, number, number] {
  const hex = /^#([0-9a-f]{6})$/i.exec(value.trim())
  if (!hex) throw new Error(`Expected a six-digit hex colour, received "${value}"`)
  const numeric = parseInt(hex[1], 16)
  return [(numeric >> 16) & 0xff, (numeric >> 8) & 0xff, numeric & 0xff]
}

/** WCAG relative luminance. */
function luminance([r, g, b]: [number, number, number]): number {
  const channel = (value: number) => {
    const scaled = value / 255
    return scaled <= 0.04045 ? scaled / 12.92 : ((scaled + 0.055) / 1.055) ** 2.4
  }
  return 0.2126 * channel(r) + 0.7152 * channel(g) + 0.0722 * channel(b)
}

/** The WCAG 2.1 contrast ratio between two colours, always >= 1. */
function contrast(foreground: string, background: string): number {
  const first = luminance(toRgb(foreground))
  const second = luminance(toRgb(background))
  const lighter = Math.max(first, second)
  const darker = Math.min(first, second)
  return (lighter + 0.05) / (darker + 0.05)
}

/** The thirteen colour identities the interface reasons in. */
const IDENTITIES = [
  'forest', 'architectural', 'copper', 'champagne', 'sandstone', 'sapphire',
  'teal', 'plum', 'emerald', 'amber', 'terracotta', 'burgundy',
] as const

describe('the design tokens are declared', () => {
  it('declares a surface, an accent and an ink for every identity', () => {
    for (const identity of IDENTITIES) {
      expect(TOKENS.has(`--surface-${identity}`), `--surface-${identity}`).toBe(true)
      expect(TOKENS.has(`--accent-${identity}`), `--accent-${identity}`).toBe(true)
      expect(TOKENS.has(`--ink-${identity}`), `--ink-${identity}`).toBe(true)
    }
  })

  it('declares the geometry, typography and surface scales the components consume', () => {
    for (const token of [
      '--radius-card', '--radius-panel', '--radius-control', '--radius-field', '--radius-chip',
      '--font-body', '--font-display',
      '--background', '--surface', '--surface-elevated', '--surface-muted', '--surface-sunken',
      '--text-primary', '--text-secondary', '--border', '--border-strong',
      '--success', '--warning', '--danger', '--info',
    ]) {
      expect(TOKENS.has(token), token).toBe(true)
    }
  })
})

describe('text is legible on the wash it sits on (WCAG 2.1 AA, 4.5:1)', () => {
  /*
   * An INK on its own SURFACE is the pair a badge, a KPI caption and a tinted panel actually
   * render. If any of these drop below 4.5:1 the interface has become unreadable for a low-vision
   * reader, however good it looks.
   */
  it.each(IDENTITIES)('%s ink clears 4.5:1 on its own wash', (identity) => {
    const ink = resolveToken(`--ink-${identity}`)
    const wash = resolveToken(`--surface-${identity}`)
    const ratio = contrast(ink, wash)
    expect(ratio, `${identity}: ${ink} on ${wash}`).toBeGreaterThanOrEqual(4.5)
  })

  it.each(IDENTITIES)('%s accent clears 3:1 as a mark on its own wash', (identity) => {
    // A 2px chart line, a status dot, a bar: a non-text graphic that carries meaning needs 3:1.
    const accent = resolveToken(`--accent-${identity}`)
    const wash = resolveToken(`--surface-${identity}`)
    const ratio = contrast(accent, wash)
    expect(ratio, `${identity}: ${accent} on ${wash}`).toBeGreaterThanOrEqual(3)
  })
})

describe('body text on the page canvas', () => {
  it('primary text clears 4.5:1 on every base surface', () => {
    const text = resolveToken('--text-primary')
    for (const surface of ['--background', '--surface', '--surface-elevated', '--surface-muted']) {
      const ratio = contrast(text, resolveToken(surface))
      expect(ratio, `--text-primary on ${surface}`).toBeGreaterThanOrEqual(4.5)
    }
  })

  it('secondary text clears 4.5:1 on the page canvas', () => {
    // Secondary is the quietest text the product renders at 11-12px, which is where a shortfall
    // is least forgivable.
    const ratio = contrast(resolveToken('--text-secondary'), resolveToken('--surface'))
    expect(ratio).toBeGreaterThanOrEqual(4.5)
  })
})

describe('semantic status colours are readable as text', () => {
  it.each([
    ['--success-ink', '--success-soft'],
    ['--warning-ink', '--warning-soft'],
    ['--danger', '--danger-soft'],
    ['--info', '--info-soft'],
  ])('%s clears 4.5:1 on %s', (inkToken, washToken) => {
    const ratio = contrast(resolveToken(inkToken), resolveToken(washToken))
    expect(ratio, `${inkToken} on ${washToken}`).toBeGreaterThanOrEqual(4.5)
  })
})

describe('the hero band, which inverts the rule', () => {
  /*
   * On deep forest it is the *pale* half of each hue that carries meaning, so the hero's own text
   * and accent tokens are what must be legible against the hero background - not the parchment ones.
   */
  it('hero text and muted text clear their thresholds on the hero ground', () => {
    const heroGround = toRgb(resolveToken('--primary'))
    const asHex = ([r, g, b]: [number, number, number]) =>
      `#${((r << 16) | (g << 8) | b).toString(16).padStart(6, '0')}`
    const ground = asHex(heroGround)

    expect(contrast(resolveToken('--hero-text'), ground)).toBeGreaterThanOrEqual(4.5)
    // Muted hero copy is small supporting text, so it is held to the same 4.5:1.
    expect(contrast(resolveToken('--hero-muted'), ground)).toBeGreaterThanOrEqual(4.5)
  })
})
