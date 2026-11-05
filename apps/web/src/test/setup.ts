import '@testing-library/jest-dom/vitest'
import { afterEach, vi } from 'vitest'
import { cleanup } from '@testing-library/react'

/**
 * The test environment.
 *
 * `vitest.config.ts` points here, and it is shared by every suite - including ones that do not want
 * a DOM. The token contrast test parses `styles.css` and declares `@vitest-environment node`, so
 * everything below is guarded on the globals actually being present. A setup file that assumes a
 * `window` turns a legitimate node-environment suite into a confusing failure.
 *
 * Two things the DOM suites need:
 *
 *  - the tree is unmounted and storage cleared between tests, so one suite's dialogs cannot trap the
 *    next one's focus and a stale session cannot leak across tests;
 *  - `matchMedia` and `ResizeObserver` exist, because jsdom implements neither and several
 *    components ask about reduced motion and layout. Without these a component that honours
 *    `prefers-reduced-motion` throws instead of rendering, which would be a false failure.
 */
const hasDom = typeof window !== 'undefined' && typeof document !== 'undefined'

afterEach(() => {
  if (hasDom) {
    cleanup()
    window.localStorage.clear()
  }
})

if (hasDom && !window.matchMedia) {
  window.matchMedia = ((query: string) => ({
    matches: false,
    media: query,
    onchange: null,
    addListener: vi.fn(),
    removeListener: vi.fn(),
    addEventListener: vi.fn(),
    removeEventListener: vi.fn(),
    dispatchEvent: vi.fn(),
  })) as unknown as typeof window.matchMedia
}

if (hasDom && !('ResizeObserver' in window)) {
  class ResizeObserverStub {
    observe() {}
    unobserve() {}
    disconnect() {}
  }
  ;(window as unknown as { ResizeObserver: unknown }).ResizeObserver = ResizeObserverStub
}
