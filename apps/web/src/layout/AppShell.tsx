import { useEffect, useMemo, useRef, useState } from 'react'
import { Link, NavLink, Outlet, useNavigate } from 'react-router-dom'
import { Bell, ChevronRight, LogOut, Menu, Search, UserCircle, X } from 'lucide-react'
import clsx from 'clsx'
import { useAuthStore } from '../auth/authStore'
import { iconFor, usePlatformStore } from '../platform'
import { notificationApi } from '../services'
import { initials } from '../components/ui/formatters'
import { Button, Spinner } from '../components/ui'
import { IMPLEMENTED_PATHS } from '../routes'
import type { NavigationEntryView } from '../types'

/**
 * The application shell - one frame for every role.
 *
 * There is deliberately no second shell and no per-role layout. Everyone works in the same building;
 * what changes is which rooms their key opens. The sidebar is served by the backend (`platform.profile`)
 * and is filtered twice over:
 *
 *  - by `IMPLEMENTED_PATHS`, so a menu entry whose screen does not exist is **hidden** rather than
 *    offered as a dead link;
 *  - by the caller's permissions, so an entry they cannot use is not dangled in front of them.
 *
 * Neither filter is security. Each screen and every endpoint re-checks the same permission.
 */
export default function AppShell() {
  const profile = usePlatformStore((state) => state.profile)
  const loadProfile = usePlatformStore((state) => state.load)
  const user = useAuthStore((state) => state.user)
  const can = useAuthStore((state) => state.can)
  const signOut = useAuthStore((state) => state.signOut)
  const navigate = useNavigate()

  const [drawerOpen, setDrawerOpen] = useState(false)
  const [menuOpen, setMenuOpen] = useState(false)
  const [unread, setUnread] = useState(0)

  /*
   * The controls that open the two panels, and the panels themselves.
   *
   * Held so focus can go into a panel when it opens and back to the control that opened it when it
   * closes - without them a panel could be opened from the keyboard and never left.
   */
  const drawerOpenerRef = useRef<HTMLButtonElement | null>(null)
  const drawerCloseRef = useRef<HTMLButtonElement | null>(null)
  const drawerPanelRef = useRef<HTMLDivElement | null>(null)
  const menuTriggerRef = useRef<HTMLButtonElement | null>(null)

  useEffect(() => {
    void loadProfile()
  }, [loadProfile])

  useEffect(() => {
    // The unread badge is a real count from the API. A failed call leaves it at zero rather than
    // inventing a number, and the header stays usable either way. The field is `unread`:
    // `NotificationDtos.UnreadCount` carries that name and no other.
    notificationApi.unreadCount()
      .then((result) => setUnread(result.unread))
      .catch(() => setUnread(0))
  }, [])

  // Close the drawer whenever the route changes, so a tap on a link does not leave it open.
  useEffect(() => { setDrawerOpen(false); setMenuOpen(false) }, [navigate])

  /*
   * A panel opened from the keyboard has to be usable, and leavable, from the keyboard.
   *
   * Both panels are rendered in the shell rather than beside their trigger, so without this a reader
   * who pressed Enter on "Open navigation" kept focus on a control that the drawer's scrim had just
   * covered, then tabbed into the page underneath it - a screen they could no longer see - with no
   * way to dismiss it but a mouse. Focus therefore moves into the drawer as it opens, Escape closes
   * it and hands focus back to the control that opened it, and while the drawer is up Tab is kept
   * inside it, because the scrim has made everything behind it unusable.
   */
  useEffect(() => {
    if (drawerOpen) drawerCloseRef.current?.focus()
  }, [drawerOpen])

  useEffect(() => {
    if (!drawerOpen && !menuOpen) return

    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        if (drawerOpen) { setDrawerOpen(false); drawerOpenerRef.current?.focus() }
        if (menuOpen) { setMenuOpen(false); menuTriggerRef.current?.focus() }
        return
      }

      if (event.key !== 'Tab' || !drawerOpen) return
      const panel = drawerPanelRef.current
      if (!panel) return

      const stops = Array.from(
        panel.querySelectorAll<HTMLElement>('a[href], button:not([disabled]), [tabindex]:not([tabindex="-1"])'),
      ).filter((element) => element.offsetParent !== null)
      if (stops.length === 0) return

      const first = stops[0]
      const last = stops[stops.length - 1]
      const active = document.activeElement

      if (!panel.contains(active)) { event.preventDefault(); first.focus(); return }
      if (event.shiftKey && active === first) { event.preventDefault(); last.focus() }
      else if (!event.shiftKey && active === last) { event.preventDefault(); first.focus() }
    }

    document.addEventListener('keydown', onKeyDown)
    return () => document.removeEventListener('keydown', onKeyDown)
  }, [drawerOpen, menuOpen])

  const navigation = useMemo(() => {
    const entries = profile?.navigation ?? []
    return entries
      .filter((entry) => IMPLEMENTED_PATHS.includes(entry.to as never))
      .filter((entry) => can(entry.permission))
      .sort((left, right) => left.displayOrder - right.displayOrder)
  }, [profile, can])

  const groups = useMemo(() => {
    const byGroup = new Map<string, NavigationEntryView[]>()
    for (const entry of navigation) {
      const key = entry.group ?? 'Portfolio'
      const bucket = byGroup.get(key)
      if (bucket) bucket.push(entry)
      else byGroup.set(key, [entry])
    }
    return Array.from(byGroup.entries())
  }, [navigation])

  const firmName = profile?.firm?.name ?? 'Meridian Estates'
  const brandLabel = profile?.theme?.sidebarBrandLabel || firmName

  const sidebar = (
    <nav aria-label="Main" className="flex h-full flex-col">
      <div className="px-4 py-4">
        <Link to="/dashboard" className="focus-meridian block rounded-[var(--radius-control)]">
          <span className="eyebrow block" style={{ color: 'var(--hero-copper)' }}>Portfolio</span>
          <span className="font-display text-base font-semibold" style={{ color: 'var(--hero-text)' }}>{brandLabel}</span>
        </Link>
      </div>

      <div className="min-h-0 flex-1 overflow-y-auto px-2 pb-4">
        {groups.map(([group, entries]) => (
          <div key={group} className="mb-3">
            <p className="nav-group-label" style={{ color: 'var(--hero-muted)' }}>{group}</p>
            <ul>
              {entries.map((entry) => (
                <li key={entry.key}>
                  <NavLink
                    to={entry.to}
                    className={({ isActive }) => clsx('nav-item focus-hero', isActive && 'nav-item-active')}
                    style={({ isActive }) => (isActive ? undefined : { color: 'var(--hero-muted)' })}
                  >
                    <span aria-hidden="true">{iconFor(entry.iconKey, 16)}</span>
                    <span className="truncate">{entry.label}</span>
                  </NavLink>
                </li>
              ))}
            </ul>
          </div>
        ))}
        {navigation.length === 0 && (
          <p className="px-3 py-2 text-xs" style={{ color: 'var(--hero-muted)' }}>
            No screens are available to your role.
          </p>
        )}
      </div>

      <div className="border-t px-4 py-3" style={{ borderColor: 'rgba(255,255,255,0.12)' }}>
        <p className="text-[11px]" style={{ color: 'var(--hero-muted)' }}>
          {profile?.firm?.licence ?? ''}
        </p>
        <p className="mt-0.5 text-[11px]" style={{ color: 'var(--hero-muted)' }}>
          {profile?.theme?.footerText || profile?.industry?.name || 'Real Estate'}
        </p>
      </div>
    </nav>
  )

  return (
    <div className="min-h-screen" style={{ background: 'var(--background)' }}>
      <a
        href="#content"
        className="focus-meridian sr-only focus:not-sr-only focus:absolute focus:left-3 focus:top-3 focus:z-[70] focus:rounded focus:bg-[var(--surface-elevated)] focus:px-3 focus:py-2 focus:text-sm"
      >
        Skip to content
      </a>

      {/* The rail is its own material - a deep forest plane, the one dark surface in a light product. */}
      <aside className="fixed inset-y-0 left-0 z-30 hidden w-64 lg:block" style={{ background: 'var(--hero-bg)' }}>
        {sidebar}
      </aside>

      {drawerOpen && (
        <div className="fixed inset-0 z-40 lg:hidden">
          <button
            type="button"
            aria-label="Close navigation"
            className="absolute inset-0"
            style={{ background: 'var(--overlay)' }}
            onClick={() => setDrawerOpen(false)}
          />
          <div ref={drawerPanelRef} className="absolute inset-y-0 left-0 w-72 max-w-[85vw] overflow-y-auto" style={{ background: 'var(--hero-bg)' }}>
            <button
              ref={drawerCloseRef}
              type="button"
              onClick={() => setDrawerOpen(false)}
              aria-label="Close navigation"
              className="focus-hero absolute right-2 top-3 rounded p-2"
              style={{ color: 'var(--hero-text)' }}
            >
              <X size={18} aria-hidden="true" />
            </button>
            {sidebar}
          </div>
        </div>
      )}

      <div className="lg:pl-64">
        <header
          className="sticky top-0 z-20 flex items-center gap-3 border-b px-3 py-2.5 sm:px-4"
          style={{ background: 'var(--surface-elevated)', borderColor: 'var(--border)' }}
        >
          <button
            ref={drawerOpenerRef}
            type="button"
            className="focus-meridian rounded-[var(--radius-control)] p-2 lg:hidden"
            aria-label="Open navigation"
            aria-expanded={drawerOpen}
            onClick={() => setDrawerOpen(true)}
          >
            <Menu size={18} aria-hidden="true" />
          </button>

          <div className="hidden items-center gap-2 sm:flex" aria-hidden="true">
            <Search size={15} style={{ color: 'var(--text-secondary)' }} />
            <span className="text-xs" style={{ color: 'var(--text-secondary)' }}>
              {firmName} · {profile?.firm?.code ?? ''}
            </span>
          </div>

          <div className="ml-auto flex items-center gap-2">
            <Link
              to="/notifications"
              className="focus-meridian relative rounded-[var(--radius-control)] p-2"
              aria-label={unread > 0 ? `Notifications, ${unread} unread` : 'Notifications'}
            >
              <Bell size={18} aria-hidden="true" />
              {unread > 0 && (
                <span
                  className="absolute -right-0.5 -top-0.5 grid h-4 min-w-4 place-items-center rounded-full px-1 text-[10px] font-bold"
                  style={{ background: 'var(--secondary)', color: '#24160a' }}
                >
                  {unread > 99 ? '99+' : unread}
                </span>
              )}
            </Link>

            <div className="relative">
              <button
                ref={menuTriggerRef}
                type="button"
                className="focus-meridian flex items-center gap-2 rounded-[var(--radius-control)] px-2 py-1.5"
                aria-haspopup="menu"
                aria-expanded={menuOpen}
                /*
                 * The account's name is carried on the control itself, not only in the label beside
                 * it. Below `sm` that label is not rendered, the avatar is decorative and the chevron
                 * is hidden from assistive technology, so the control had no accessible name at all
                 * on a phone - a critical `button-name` failure. The name is included here rather
                 * than replaced by a generic word, so the accessible name still contains what is
                 * visibly written for a reader using speech input.
                 */
                aria-label={user ? `Account menu, ${user.firstName} ${user.lastName}` : 'Account menu'}
                onClick={() => setMenuOpen((open) => !open)}
              >
                <span
                  className="grid h-7 w-7 place-items-center rounded-[var(--radius-chip)] text-[11px] font-bold"
                  style={{ background: 'var(--primary-soft)', color: 'var(--ink-forest)' }}
                  aria-hidden="true"
                >
                  {initials(`${user?.firstName ?? ''} ${user?.lastName ?? ''}`)}
                </span>
                <span className="hidden text-left sm:block">
                  <span className="block text-xs font-semibold leading-tight" style={{ color: 'var(--text-primary)' }}>
                    {user?.firstName} {user?.lastName}
                  </span>
                  <span className="block text-[10px] leading-tight" style={{ color: 'var(--text-secondary)' }}>
                    {user?.roles?.join(', ')}
                  </span>
                </span>
                <ChevronRight size={14} aria-hidden="true" style={{ transform: menuOpen ? 'rotate(90deg)' : 'none' }} />
              </button>

              {menuOpen && (
                <div
                  role="menu"
                  className="absolute right-0 top-full z-30 mt-1 w-56 rounded-[var(--radius-card)] border p-1 shadow-[var(--shadow-soft)]"
                  style={{ background: 'var(--surface-elevated)', borderColor: 'var(--border)' }}
                >
                  <div className="px-3 py-2">
                    <p className="truncate text-xs font-semibold" style={{ color: 'var(--text-primary)' }}>{user?.email}</p>
                    <p className="text-[10px]" style={{ color: 'var(--text-secondary)' }}>
                      {user?.staffCode} · {user?.designation ?? user?.department ?? ''}
                    </p>
                  </div>
                  <hr style={{ borderColor: 'var(--border)' }} />
                  <Link
                    to="/profile"
                    role="menuitem"
                    className="focus-meridian flex items-center gap-2 rounded-[var(--radius-control)] px-3 py-2 text-sm"
                    style={{ color: 'var(--text-primary)' }}
                  >
                    <UserCircle size={15} aria-hidden="true" /> Your profile
                  </Link>
                  <button
                    type="button"
                    role="menuitem"
                    className="focus-meridian flex w-full items-center gap-2 rounded-[var(--radius-control)] px-3 py-2 text-left text-sm"
                    style={{ color: 'var(--danger)' }}
                    onClick={() => { void signOut().then(() => navigate('/login', { replace: true })) }}
                  >
                    <LogOut size={15} aria-hidden="true" /> Sign out
                  </button>
                </div>
              )}
            </div>
          </div>
        </header>

        <main id="content" className="px-3 py-5 sm:px-5 lg:px-6">
          {profile === null && usePlatformStore.getState().loading
            ? <div className="flex items-center gap-3 py-16 justify-center"><Spinner /> <span className="text-sm" style={{ color: 'var(--text-secondary)' }}>Loading the portfolio profile…</span></div>
            : <Outlet />}
        </main>
      </div>
    </div>
  )
}

/** A small helper so a screen can offer a consistent primary action in the shell's own idiom. */
export function ShellAction({ label, onClick }: { label: string; onClick: () => void }) {
  return <Button variant="primary" size="sm" onClick={onClick}>{label}</Button>
}
