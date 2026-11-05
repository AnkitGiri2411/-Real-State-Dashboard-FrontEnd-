import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { BrowserRouter } from 'react-router-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import App from './App'
import './styles.css'

/**
 * The application entry point.
 *
 * The query client's defaults are chosen for an operational desk rather than a marketing site: a
 * screen the reader returns to within a minute is served from cache instead of re-fetching, one
 * retry is attempted for a transient failure, and the retry is **not** attempted for a refusal the
 * server already explained (4xx) - retrying a 403 just delays telling the reader.
 */
const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      staleTime: 30_000,
      refetchOnWindowFocus: false,
      retry: (failureCount, error) => {
        const status = (error as { status?: number })?.status
        if (status !== undefined && status >= 400 && status < 500) return false
        return failureCount < 1
      },
    },
    mutations: { retry: false },
  },
})

const container = document.getElementById('root')
if (!container) throw new Error('The application root element is missing from index.html')

createRoot(container).render(
  <StrictMode>
    <QueryClientProvider client={queryClient}>
      <BrowserRouter>
        <App />
      </BrowserRouter>
    </QueryClientProvider>
  </StrictMode>,
)
