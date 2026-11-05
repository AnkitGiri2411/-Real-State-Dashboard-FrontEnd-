import { create } from 'zustand'
import * as Icons from 'lucide-react'
import type { ReactNode } from 'react'
import type { PlatformProfile } from '../types'
import { configureCurrency } from '../components/ui/formatters'
import { platformApi } from '../services'

/**
 * The platform profile: what this installation *is*.
 *
 * One installation serves one firm, so its identity, vocabulary, branding and policy thresholds are
 * configuration - and the backend is the source of truth for all of it. The shell boots from
 * `GET /platform/profile` rather than from anything compiled into the bundle, which is what lets a
 * purchased installation be rebranded without a rebuild.
 *
 * A build-time fallback is used only until the profile arrives, so a rebranded installation never
 * flashes Meridian's own name on the way in.
 *
 * (This file is `.tsx` because `iconFor` returns an element: JSX is not valid in a `.ts` module.)
 */
interface PlatformState {
  profile: PlatformProfile | null
  loading: boolean
  error: string | null
  load: () => Promise<void>
}

export const usePlatformStore = create<PlatformState>((set, get) => ({
  profile: null,
  loading: false,
  error: null,

  async load() {
    if (get().profile || get().loading) return
    set({ loading: true, error: null })
    try {
      const profile = await platformApi.profile()
      applyBrandTheme(profile)
      configureCurrency(profile.policies.currency, profile.policies.currencySymbol)
      set({ profile, loading: false })
    } catch (error) {
      set({
        loading: false,
        error: error instanceof Error ? error.message : 'The product profile could not be loaded.',
      })
    }
  },
}))

/**
 * Apply the installation's branding to the document's tokens.
 *
 * Only the three brand properties are rewritten. Everything else in the stylesheet derives from
 * them, so a rebrand is a token change rather than a restyle - and an installation that supplies no
 * colours keeps Meridian's own forest and copper rather than inheriting an empty string.
 */
export function applyBrandTheme(profile: PlatformProfile): void {
  const root = document.documentElement
  const { primaryColor, primarySoftColor, secondaryColor, colorScheme } = profile.theme

  if (primaryColor) {
    root.style.setProperty('--primary', primaryColor)
    root.style.setProperty('--primary-hover', shift(primaryColor, -8))
    root.style.setProperty('--primary-active', shift(primaryColor, -16))
  }
  if (primarySoftColor) root.style.setProperty('--primary-soft', primarySoftColor)
  if (secondaryColor) {
    root.style.setProperty('--secondary', secondaryColor)
    root.style.setProperty('--secondary-hover', shift(secondaryColor, -8))
    root.style.setProperty('--secondary-active', shift(secondaryColor, -16))
  }
  if (colorScheme) root.style.colorScheme = colorScheme
}

/** Darken (or lighten, for a negative amount) a hex colour, for a derived hover state. */
function shift(hex: string, amount: number): string {
  const match = /^#?([0-9a-f]{6})$/i.exec(hex.trim())
  if (!match) return hex
  const value = parseInt(match[1], 16)
  const clamp = (channel: number) => Math.max(0, Math.min(255, channel))
  const red = clamp(((value >> 16) & 0xff) + amount)
  const green = clamp(((value >> 8) & 0xff) + amount)
  const blue = clamp((value & 0xff) + amount)
  return `#${((red << 16) | (green << 8) | blue).toString(16).padStart(6, '0')}`
}

/**
 * Resolve a navigation entry's icon.
 *
 * The backend names an icon key from a documented set. An unknown key renders **nothing** rather
 * than a broken glyph - a missing icon is a small loss, a wrong one is misleading.
 */
export function iconFor(key: string | null | undefined, size = 16): ReactNode {
  if (!key) return null
  const registry = Icons as unknown as Record<
    string,
    React.ComponentType<{ size?: number; 'aria-hidden'?: boolean }> | undefined
  >
  const Component = registry[key]
  if (!Component) return null
  return <Component size={size} aria-hidden />
}

/** The label a term resolves to, falling back to the key so a partial vocabulary never blanks a label. */
export function term(profile: PlatformProfile | null, key: string, fallback: string): string {
  return profile?.terminology?.[key] ?? fallback
}
