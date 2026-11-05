import { useState, type FormEvent } from 'react'
import { Navigate, useLocation, useNavigate } from 'react-router-dom'
import { Building2, LockKeyhole } from 'lucide-react'
import { useAuthStore } from '../auth/authStore'
import { Button, Field, Input } from '../components/ui'
import { isRetryable, messageOf } from '../lib/errors'

/**
 * The identity shown before anybody has signed in.
 *
 * Deliberately **not** read from `GET /platform/profile`. That endpoint requires a session - it
 * returns the firm's details and the sidebar - so calling it here produced a 401 on every visit to
 * the sign-in page. A screen that is by definition unauthenticated must not depend on an
 * authenticated read, so the pre-session identity comes from build-time configuration instead,
 * which is exactly what `VITE_PRODUCT_NAME` and the `VITE_BRAND_*` variables are for.
 *
 * A rebranded installation sets those at build time; the shell replaces them with the live profile
 * the moment somebody signs in.
 */
const BRAND_NAME: string = import.meta.env.VITE_PRODUCT_NAME ?? 'Meridian Estates'
const LOGIN_HEADLINE = 'The portfolio, its leases and its ledger — on one drawing.'
const LOGIN_SUBHEADLINE =
  'Properties, units, owners and tenants. Rent, collections and arrears. Listings, viewings and the sales pipeline. Everything the desk needs, from the register to the receipt.'

/**
 * Sign-in.
 *
 * The headline and subheadline come from the platform profile, so a rebranded installation shows its
 * own words without a rebuild. The error the reader sees is the backend's own sentence - including
 * the deliberately identical wording for "wrong password" and "no such address", which is what stops
 * this screen being used to discover which e-mail addresses exist.
 */
export default function LoginPage() {
  const user = useAuthStore((state) => state.user)
  const signIn = useAuthStore((state) => state.signIn)
  const signingIn = useAuthStore((state) => state.signingIn)
  const navigate = useNavigate()
  const location = useLocation()

  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [fieldErrors, setFieldErrors] = useState<{ email?: string; password?: string }>({})

  const expired = Boolean((location.state as { expired?: boolean } | null)?.expired)
  const from = (location.state as { from?: string } | null)?.from ?? '/dashboard'

  if (user) return <Navigate to={from} replace />

  async function onSubmit(event: FormEvent) {
    event.preventDefault()
    setError(null)

    const nextFieldErrors: { email?: string; password?: string } = {}
    if (!email.trim()) nextFieldErrors.email = 'Enter your e-mail address'
    else if (!/^[^@\s]+@[^@\s]+\.[^@\s]+$/.test(email.trim())) nextFieldErrors.email = 'That does not look like an e-mail address'
    if (!password) nextFieldErrors.password = 'Enter your password'
    setFieldErrors(nextFieldErrors)
    if (Object.keys(nextFieldErrors).length > 0) return

    try {
      await signIn(email.trim(), password)
      navigate(from, { replace: true })
    } catch (failure) {
      setError(messageOf(failure))
      setPassword('')
      // A rejected sign-in is the server's answer; only a transport failure is worth inviting a retry.
      if (!isRetryable(failure)) setFieldErrors({})
    }
  }

  return (
    <div className="grid min-h-screen lg:grid-cols-[1.15fr_1fr]">
      {/* The architectural plane: deep forest with the drawing's own linework. */}
      <section className="hero-band relative hidden flex-col justify-between p-10 lg:flex">
        <div className="relative z-10">
          <div className="flex items-center gap-3">
            <span className="grid h-10 w-10 place-items-center rounded-[var(--radius-control)]" style={{ background: 'rgba(255,255,255,0.1)' }}>
              <Building2 size={20} aria-hidden="true" style={{ color: 'var(--hero-copper)' }} />
            </span>
            <div>
              <p className="eyebrow" style={{ color: 'var(--hero-copper)' }}>Real Estate</p>
              <p className="font-display text-lg font-semibold" style={{ color: 'var(--hero-text)' }}>
                {BRAND_NAME}
              </p>
            </div>
          </div>

          <h1 className="font-display mt-14 max-w-xl text-4xl font-semibold leading-tight" style={{ color: 'var(--hero-text)' }}>
            {LOGIN_HEADLINE}
          </h1>
          <p className="mt-4 max-w-lg text-sm leading-relaxed" style={{ color: 'var(--hero-muted)' }}>
            {LOGIN_SUBHEADLINE}
          </p>
        </div>

        <dl className="relative z-10 grid max-w-lg grid-cols-3 gap-6">
          {[
            { label: 'Asset register', value: 'Properties · Units · Buildings' },
            { label: 'Income', value: 'Leases · Invoices · Receipts' },
            { label: 'Pipeline', value: 'Leads · Listings · Sales' },
          ].map((item) => (
            <div key={item.label}>
              <dt className="eyebrow" style={{ color: 'var(--hero-copper)' }}>{item.label}</dt>
              <dd className="mt-1 text-xs leading-relaxed" style={{ color: 'var(--hero-muted)' }}>{item.value}</dd>
            </div>
          ))}
        </dl>
      </section>

      <section className="flex items-center justify-center px-5 py-10 sm:px-10">
        <div className="w-full max-w-sm">
          <div className="mb-8 lg:hidden">
            <p className="eyebrow" style={{ color: 'var(--ink-copper)' }}>Real Estate</p>
            <p className="font-display text-xl font-semibold" style={{ color: 'var(--text-primary)' }}>
              {BRAND_NAME}
            </p>
          </div>

          <h2 className="font-display text-2xl font-semibold" style={{ color: 'var(--text-primary)' }}>Sign in</h2>
          <p className="mt-1 text-sm" style={{ color: 'var(--text-secondary)' }}>
            Use the account your firm gave you.
          </p>
          <div className="copper-rule mt-4" aria-hidden="true" />

          {expired && (
            <div role="status" className="mt-4 rounded-[var(--radius-card)] border px-3 py-2 text-sm"
              style={{ background: 'var(--warning-soft)', borderColor: 'var(--border)', color: 'var(--warning-ink)' }}>
              Your session ended. Please sign in again.
            </div>
          )}

          {error && (
            <div role="alert" className="mt-4 rounded-[var(--radius-card)] border px-3 py-2 text-sm"
              style={{ background: 'var(--danger-soft)', borderColor: 'var(--border)', color: 'var(--danger)' }}>
              {error}
            </div>
          )}

          <form className="mt-6 space-y-4" onSubmit={onSubmit} noValidate>
            <Field label="E-mail address" htmlFor="email" required error={fieldErrors.email}>
              <Input
                id="email"
                name="email"
                type="email"
                autoComplete="username"
                autoFocus
                value={email}
                onChange={(event) => setEmail(event.target.value)}
                aria-invalid={Boolean(fieldErrors.email) || undefined}
                aria-describedby={fieldErrors.email ? 'email-error' : undefined}
              />
            </Field>

            <Field label="Password" htmlFor="password" required error={fieldErrors.password}>
              <Input
                id="password"
                name="password"
                type="password"
                autoComplete="current-password"
                value={password}
                onChange={(event) => setPassword(event.target.value)}
                aria-invalid={Boolean(fieldErrors.password) || undefined}
              />
            </Field>

            <Button type="submit" variant="primary" className="w-full" loading={signingIn}>
              <LockKeyhole size={15} aria-hidden="true" />
              {signingIn ? 'Signing in…' : 'Sign in'}
            </Button>
          </form>

          <p className="mt-6 text-xs" style={{ color: 'var(--text-secondary)' }}>
            {BRAND_NAME} · Real Estate Management
          </p>
        </div>
      </section>
    </div>
  )
}
