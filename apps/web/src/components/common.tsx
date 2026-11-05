import { Navigate, Outlet, useLocation } from 'react-router-dom'
import type { ReactNode } from 'react'
import { useAuthStore } from '../auth/authStore'
import { Button, Dialog, EmptyState, Skeleton, Spinner } from './ui'

/**
 * The two gates a route passes through, plus the page header every screen shares.
 *
 * Both gates are a **courtesy, not security**. Hiding a control or refusing a route explains the
 * refusal to the person holding the keyboard; every endpoint behind it re-checks the same
 * permission server-side, so a route typed directly into the address bar is refused by the API
 * whether or not this file ever ran.
 */

/** Signed in, or sent to sign-in remembering where they were going. */
export function ProtectedRoute() {
  const user = useAuthStore((state) => state.user)
  const hydrating = useAuthStore((state) => state.hydrating)
  const location = useLocation()

  if (hydrating) {
    return (
      <div className="grid min-h-screen place-items-center" style={{ background: 'var(--background)' }}>
        <div className="flex flex-col items-center gap-3">
          <Spinner className="h-6 w-6" style={{ color: 'var(--primary)' }} />
          <p className="text-sm" style={{ color: 'var(--text-secondary)' }}>Restoring your session…</p>
        </div>
      </div>
    )
  }

  if (!user) {
    return <Navigate to="/login" replace state={{ from: location.pathname + location.search }} />
  }

  return <Outlet />
}

/**
 * A screen the caller may not open.
 *
 * Says which permission is missing rather than showing an empty frame, because "you cannot do this
 * and here is why" is a far better answer than a blank page.
 */
export function PageAccessGuard({ permission, children }: { permission: string | null; children: ReactNode }) {
  const can = useAuthStore((state) => state.can)
  const hydrating = useAuthStore((state) => state.hydrating)

  if (hydrating) return <Skeleton rows={6} className="p-6" />

  if (!can(permission)) {
    return (
      <EmptyState
        title="Your account does not have permission to view this"
        description={
          permission
            ? `This screen needs the ${permission} permission. Ask an owner or administrator to add it to your role.`
            : 'This screen is not available to your role.'
        }
      />
    )
  }

  return <>{children}</>
}

/**
 * A page's heading.
 *
 * One `h1` per screen, which is what gives the document a readable outline for a screen reader and
 * for the browser's own navigation.
 */
export function PageHeader({
  title, description, eyebrow, actions,
}: {
  title: string
  description?: string
  eyebrow?: string
  actions?: ReactNode
}) {
  return (
    <header className="mb-5">
      <div className="flex flex-wrap items-end justify-between gap-3">
        <div className="min-w-0">
          {eyebrow && <div className="eyebrow" style={{ color: 'var(--ink-copper)' }}>{eyebrow}</div>}
          <h1 className="font-display text-2xl font-semibold leading-tight" style={{ color: 'var(--text-primary)' }}>
            {title}
          </h1>
          {description && (
            <p className="mt-1 max-w-3xl text-sm" style={{ color: 'var(--text-secondary)' }}>{description}</p>
          )}
        </div>
        {actions && <div className="flex flex-wrap items-center gap-2">{actions}</div>}
      </div>
      <div className="survey-rule" aria-hidden="true" />
    </header>
  )
}

/** A confirmation step for anything destructive or hard to undo. */
export function ConfirmDialog({
  open, title, description, confirmLabel = 'Confirm', onConfirm, onCancel, busy = false, dangerous = false,
}: {
  open: boolean
  title: string
  description: string
  confirmLabel?: string
  onConfirm: () => void
  onCancel: () => void
  busy?: boolean
  dangerous?: boolean
}) {
  // The Dialog primitive owns the focus trap, Escape handling and focus return.
  return (
    <Dialog
      open={open}
      title={title}
      description={description}
      onClose={onCancel}
      width="sm"
      footer={
        <>
          <Button variant="ghost" onClick={onCancel} disabled={busy}>Cancel</Button>
          <Button variant={dangerous ? 'danger' : 'primary'} onClick={onConfirm} loading={busy}>
            {confirmLabel}
          </Button>
        </>
      }
    >
      <p className="text-sm" style={{ color: 'var(--text-secondary)' }}>{description}</p>
    </Dialog>
  )
}
