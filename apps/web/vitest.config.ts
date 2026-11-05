import { defineConfig } from 'vitest/config'
import react from '@vitejs/plugin-react'

/**
 * The frontend test configuration.
 *
 * Deliberately separate from `vite.config.ts`: the build config carries the production chunk
 * strategy and the dev-server ports, which have nothing to do with running tests, and a
 * `vitest.config.ts` takes precedence when one exists. Keeping them apart means a change to
 * the bundle split cannot accidentally change how tests resolve modules.
 */
export default defineConfig({
  plugins: [react()],
  resolve: {
    alias: { '@': '/src' },
  },
  test: {
    environment: 'jsdom',
    globals: true,
    setupFiles: ['./src/test/setup.ts'],
    css: false,
    include: ['src/**/*.{test,spec}.{ts,tsx}'],
    // The screens talk to the API through `services/apiClient`, which reads the session out
    // of localStorage. jsdom provides it, so no shim is needed; what is restored between
    // tests is the storage contents themselves.
    restoreMocks: true,
  },
})
