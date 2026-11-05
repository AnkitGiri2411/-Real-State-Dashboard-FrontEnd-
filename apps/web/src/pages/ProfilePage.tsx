import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { useMutation } from '@tanstack/react-query'
import { BadgeCheck, KeyRound, ShieldCheck, UserCircle } from 'lucide-react'
import { PageHeader } from '../components/common'
import {
  Badge, Button, DetailItem, Dialog, EmptyState, Field, Input, Panel, SectionHeader, Skeleton,
  StatusBadge, useToast,
} from '../components/ui'
import { initials } from '../components/ui/formatters'
import { useAuthStore } from '../auth/authStore'
import { authService } from '../auth/authService'
import { ApiError, messageOf } from '../lib/errors'

/** The shortest password the backend accepts (`AuthService.MIN_PASSWORD_LENGTH`). */
const MIN_PASSWORD_LENGTH = 8

/**
 * The product's own wording for the password rules the backend refuses.
 *
 * `AuthService` answers a change-password refusal with the *code* as the message
 * (`ApiException.badRequest("WEAK_PASSWORD")` sends `message: "WEAK_PASSWORD"`), and the backend's
 * `ErrorMessages` is where those codes already have sentences. A person must never be shown a raw
 * code, so each is translated here — and each keeps its **own** message, so "too short" and "do not
 * match" are never collapsed into one vague failure.
 */
const PASSWORD_FAILURES: Record<string, string> = {
  WEAK_PASSWORD: 'Password must be at least 8 characters',
  PASSWORD_MISMATCH: 'The passwords do not match',
  INVALID_CREDENTIALS: 'Your current password is not correct',
}

/** The named failure behind an error, if it has one - either the code or the bare-code message. */
function namedFailureOf(error: unknown): string | null {
  const raw = messageOf(error).trim()
  if (/^[A-Z][A-Z_]{3,}$/.test(raw)) return raw
  if (error instanceof ApiError && error.code && error.code !== 'VALIDATION_FAILED') return error.code
  return null
}

/**
 * Your profile.
 *
 * Everyone has one, so this screen carries no permission guard — and it also refuses to *claim*
 * anything about the caller. The identity, the roles and the full permission list all come from
 * `GET /auth/me`, held by the session store, so the interface can never render as OWNER because that
 * is what a demo happened to be: what is shown is what the token actually carries.
 *
 * A password change is the one action here. It ends every session for the account, including this
 * one, because a password change is exactly the moment somebody is entitled to believe that nobody
 * else still holds a session — so on success the reader is signed out and told why, rather than left
 * in a session the backend has already revoked.
 */
export default function ProfilePage() {
  const user = useAuthStore((state) => state.user)
  const hydrating = useAuthStore((state) => state.hydrating)
  const signOut = useAuthStore((state) => state.signOut)
  const navigate = useNavigate()
  const toast = useToast()

  const [dialogOpen, setDialogOpen] = useState(false)
  const [currentPassword, setCurrentPassword] = useState('')
  const [newPassword, setNewPassword] = useState('')
  const [confirmPassword, setConfirmPassword] = useState('')
  const [fieldErrors, setFieldErrors] = useState<{
    current?: string
    next?: string
    confirm?: string
  }>({})
  const [formError, setFormError] = useState<string | null>(null)

  const changePassword = useMutation({
    mutationFn: () => authService.changePassword(currentPassword, newPassword, confirmPassword),
    onSuccess: async () => {
      setDialogOpen(false)
      setCurrentPassword('')
      setNewPassword('')
      setConfirmPassword('')
      setFieldErrors({})
      setFormError(null)
      toast.push('Password changed. Signing you out, because every session for this account was revoked.', 'emerald')
      await signOut()
      navigate('/login', { replace: true, state: { expired: true } })
    },
    onError: (error) => {
      const named = namedFailureOf(error)
      const sentence = named ? PASSWORD_FAILURES[named] ?? messageOf(error) : messageOf(error)
      if (named === 'WEAK_PASSWORD') setFieldErrors({ next: sentence })
      else if (named === 'PASSWORD_MISMATCH') setFieldErrors({ confirm: sentence })
      else if (named === 'INVALID_CREDENTIALS') setFieldErrors({ current: sentence })
      else setFormError(sentence)
    },
  })

  /**
   * Validate locally, then ask the API.
   *
   * Kept separate from the form's own submit handler so the dialog's footer button and the keyboard's
   * Enter key run exactly the same path rather than two that could drift apart.
   */
  function submitPassword() {
    if (changePassword.isPending) return

    // The client-side checks exist so the reader is told before a request is made; the backend
    // repeats both of them, and its answer wins whenever the two could disagree.
    const next: { current?: string; next?: string; confirm?: string } = {}
    if (!currentPassword) next.current = 'Enter your current password'
    if (!newPassword) next.next = 'Enter a new password'
    else if (newPassword.length < MIN_PASSWORD_LENGTH) {
      next.next = `A password must be at least ${MIN_PASSWORD_LENGTH} characters`
    }
    if (!confirmPassword) next.confirm = 'Repeat the new password'
    else if (confirmPassword !== newPassword) next.confirm = 'The passwords do not match'

    setFieldErrors(next)
    setFormError(null)
    if (Object.keys(next).length > 0) return
    changePassword.mutate()
  }

  if (hydrating || !user) {
    return (
      <>
        <PageHeader title="Your profile" />
        {hydrating
          ? <Skeleton rows={6} />
          : (
            <EmptyState
              title="Your identity has not been loaded"
              description="Your name, roles and permissions are read from /auth/me. Until that read answers, nothing is shown rather than a guessed identity."
            />
          )}
      </>
    )
  }

  const fullName = `${user.firstName} ${user.lastName}`.trim()
  const permissions = [...user.permissions].sort((left, right) => left.localeCompare(right))

  return (
    <>
      <PageHeader
        eyebrow="Account"
        title="Your profile"
        description="Who you are signed in as, and exactly what your account may do — read from the session the backend issued."
        actions={
          <Button variant="primary" onClick={() => setDialogOpen(true)}>
            <KeyRound size={15} aria-hidden="true" />
            Change password
          </Button>
        }
      />

      {/* ── Identity ─────────────────────────────────────────────────────── */}

      <div className="mb-6 grid gap-4 lg:grid-cols-[1.4fr_1fr]">
        <Panel notch title="Identity" icon={<UserCircle size={15} />} subtitle="As the token carries it">
          <div className="flex items-start gap-3">
            <span
              className="grid h-12 w-12 shrink-0 place-items-center rounded-[var(--radius-chip)] text-sm font-bold"
              style={{ background: 'var(--primary-soft)', color: 'var(--ink-forest)' }}
              aria-hidden="true"
            >
              {initials(fullName)}
            </span>
            <div className="min-w-0">
              <p className="font-display text-lg font-semibold" style={{ color: 'var(--text-primary)' }}>
                {fullName}
              </p>
              <p className="text-xs" style={{ color: 'var(--text-secondary)' }}>
                {user.designation ?? 'No designation on record'}
                {user.department ? ` · ${user.department}` : ''}
              </p>
            </div>
          </div>

          <dl className="mt-4 grid gap-3 border-t pt-4 sm:grid-cols-2" style={{ borderColor: 'var(--border)' }}>
            <DetailItem label="Staff code" value={user.staffCode} />
            <DetailItem label="Status" value={<StatusBadge status={user.status} />} />
            <DetailItem label="E-mail" value={user.email} />
            <DetailItem label="Telephone" value={user.phone ?? '—'} />
            <DetailItem label="Department" value={user.department ?? '—'} />
            <DetailItem label="Designation" value={user.designation ?? '—'} />
          </dl>

          {user.forcePasswordReset && (
            <p
              role="status"
              className="mt-4 rounded-[var(--radius-card)] border px-3 py-2 text-xs"
              style={{ background: 'var(--warning-soft)', borderColor: 'var(--border)', color: 'var(--warning-ink)' }}
            >
              This account is flagged for a password reset. Changing your password clears the flag.
            </p>
          )}
        </Panel>

        <Panel tint="copper" title="What your account may do" icon={<ShieldCheck size={15} />}
          subtitle={`${user.roles.length} role${user.roles.length === 1 ? '' : 's'} · ${permissions.length} permission${permissions.length === 1 ? '' : 's'}`}>
          <div>
            <p className="eyebrow" style={{ color: 'var(--ink-copper)' }}>Roles</p>
            <ul className="mt-1.5 flex flex-wrap gap-1.5">
              {user.roles.length > 0
                ? user.roles.map((role) => (
                  <li key={role}><Badge tone="copper">{role.replace(/_/g, ' ')}</Badge></li>
                ))
                : <li className="text-sm" style={{ color: 'var(--text-secondary)' }}>No role is assigned to this account.</li>}
            </ul>
          </div>
          <p className="mt-3 border-t pt-3 text-xs leading-relaxed" style={{ borderColor: 'var(--border)', color: 'var(--text-secondary)' }}>
            Nothing on this screen is inferred from a role name. Every control in the product asks the
            permission list below, and every endpoint re-checks the same permission on the server — so a
            screen hidden here is also refused there.
          </p>
        </Panel>
      </div>

      {/* ── Permissions ──────────────────────────────────────────────────── */}

      <SectionHeader
        eyebrow="Permissions"
        title="Every permission this account carries"
        rule="forest"
        description="The full set the token carries, not a summary. A screen you cannot open is one whose permission is not in this list."
      />
      <Panel className="mb-6">
        {permissions.length > 0
          ? (
            <ul className="flex flex-wrap gap-1.5">
              {permissions.map((permission) => (
                <li key={permission}>
                  <span
                    className="badge"
                    style={{ background: 'var(--surface-sunken)', color: 'var(--text-secondary)' }}
                  >
                    {permission}
                  </span>
                </li>
              ))}
            </ul>
          )
          : (
            <EmptyState
              title="This account carries no permissions"
              description="Permissions are attached to roles, and roles are granted to staff. Ask an owner or administrator to attach a role to this account and its permissions will appear here."
            />
          )}
      </Panel>

      {/* ── Password ─────────────────────────────────────────────────────── */}

      <Panel tint="slate" title="Changing your password" icon={<BadgeCheck size={15} />}>
        <p className="text-sm leading-relaxed" style={{ color: 'var(--text-secondary)' }}>
          A password must be at least {MIN_PASSWORD_LENGTH} characters. The backend revokes{' '}
          <strong>every</strong> live session for this account when you change it — including this one —
          so you will be signed out and asked to sign in again with the new password. That is deliberate:
          it is the one moment you are entitled to believe nobody else is still holding a session.
        </p>
        <div className="mt-3">
          <Button variant="outline" onClick={() => setDialogOpen(true)}>
            <KeyRound size={15} aria-hidden="true" />
            Change password
          </Button>
        </div>
      </Panel>

      <p className="mt-4 text-[11px]" style={{ color: 'var(--text-secondary)' }}>
        Identity read from /auth/me · session shown as the backend issued it
        {user.forcePasswordReset ? ' · a password reset is required for this account' : ''}
      </p>

      {/* ── The dialog ───────────────────────────────────────────────────── */}

      <Dialog
        open={dialogOpen}
        title="Change password"
        description="Your current password is re-checked even though you are already signed in."
        onClose={() => { setDialogOpen(false); setFormError(null) }}
        footer={
          <>
            <Button variant="ghost" onClick={() => { setDialogOpen(false); setFormError(null) }} disabled={changePassword.isPending}>
              Cancel
            </Button>
            <Button variant="primary" onClick={submitPassword} loading={changePassword.isPending}>
              Change password
            </Button>
          </>
        }
      >
        <form
          className="space-y-4"
          onSubmit={(event) => { event.preventDefault(); submitPassword() }}
          noValidate
        >
          {formError && (
            <div
              role="alert"
              className="rounded-[var(--radius-card)] border px-3 py-2 text-sm"
              style={{ background: 'var(--danger-soft)', borderColor: 'var(--border)', color: 'var(--danger)' }}
            >
              {formError}
            </div>
          )}

          <Field label="Current password" htmlFor="current-password" required error={fieldErrors.current}>
            <Input
              id="current-password"
              name="currentPassword"
              type="password"
              autoComplete="current-password"
              value={currentPassword}
              onChange={(event) => setCurrentPassword(event.target.value)}
              aria-invalid={Boolean(fieldErrors.current) || undefined}
            />
          </Field>

          <Field
            label="New password"
            htmlFor="new-password"
            required
            error={fieldErrors.next}
            hint={`At least ${MIN_PASSWORD_LENGTH} characters`}
          >
            <Input
              id="new-password"
              name="newPassword"
              type="password"
              autoComplete="new-password"
              value={newPassword}
              onChange={(event) => setNewPassword(event.target.value)}
              aria-invalid={Boolean(fieldErrors.next) || undefined}
            />
          </Field>

          <Field label="Repeat the new password" htmlFor="confirm-password" required error={fieldErrors.confirm}>
            <Input
              id="confirm-password"
              name="confirmPassword"
              type="password"
              autoComplete="new-password"
              value={confirmPassword}
              onChange={(event) => setConfirmPassword(event.target.value)}
              aria-invalid={Boolean(fieldErrors.confirm) || undefined}
            />
          </Field>

          <p className="text-xs leading-relaxed" style={{ color: 'var(--text-secondary)' }}>
            On success you will be signed out of this browser and every other session for this account
            will be rejected, so sign in again with the new password.
          </p>

          {/* A form inside a dialog still needs its own submit path for the keyboard: Enter in a text
              field submits the form, which runs the same validation and mutation as the footer button. */}
          <button type="submit" className="sr-only">Change password</button>
        </form>
      </Dialog>
    </>
  )
}
