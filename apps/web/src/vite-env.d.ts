/// <reference types="vite/client" />

/**
 * A file imported as text.
 *
 * Vite's `?raw` suffix hands back the file's contents as a string. It is declared here because the
 * token contrast test reads `styles.css` this way rather than through `node:fs` - the test runs in
 * the jsdom environment, where the node builtins are externalised and unavailable.
 */
declare module '*?raw' {
  const content: string
  export default content
}

interface ImportMetaEnv {
  readonly VITE_API_BASE_URL?: string
  /**
   * Where the data comes from.
   *
   * `local` (the default when unset) answers every request from the dataset bundled in `src/local`,
   * which is what a static deployment such as Vercel uses. `api` restores the real calls to
   * `VITE_API_BASE_URL` for a checkout that has a backend running.
   */
  readonly VITE_DATA_SOURCE?: string
  readonly VITE_PRODUCT_CODE?: string
  readonly VITE_PRODUCT_NAME?: string
  readonly VITE_INDUSTRY_CODE?: string
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}
