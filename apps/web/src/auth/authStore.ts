import { create } from 'zustand'
import { authService } from './authService'
import {
  clearTokens,
  readAccessToken,
  readRefreshToken,
  writeTokens,
} from './session'
import type { CurrentUser } from '../types'

/**
 * The signed-in session, and the only authority the UI consults about who is acting.
 *
 * Two rules this store exists to enforce:
 *
 *  1. **Nothing is assumed.** The user, their roles and their permissions all come from
 *     `GET /auth/me`. No screen may claim a role, and the interface must never render as OWNER
 *     because that is what the demo data happened to be.
 *  2. **Frontend permission checks are a courtesy, not security.** `can(...)` decides whether a
 *     control is worth showing. Every endpoint re-checks the same permission server-side, and the
 *     backend remains the authority - hiding a button has never stopped anyone.
 */
interface AuthState {
  accessToken: string | null
  refreshToken: string | null
  user: CurrentUser | null
  /** True while the initial `/auth/me` for an existing token is in flight. */
  hydrating: boolean
  /** True while a sign-in is in flight, so the form can disable its own button. */
  signingIn: boolean

  isAuthenticated: () => boolean
  can: (permission: string | null | undefined) => boolean
  canAny: (permissions: string[]) => boolean
  hasRole: (role: string) => boolean

  signIn: (email: string, password: string) => Promise<CurrentUser>
  signOut: () => Promise<void>
  /** Load the identity for a token already in storage. Called once on boot. */
  hydrate: () => Promise<void>
  clear: () => void
}

export const useAuthStore = create<AuthState>((set, get) => ({
  accessToken: readAccessToken(),
  refreshToken: readRefreshToken(),
  user: null,
  hydrating: Boolean(readAccessToken()),
  signingIn: false,

  isAuthenticated: () => Boolean(get().accessToken) && get().user !== null,

  can: (permission) => {
    if (!permission) return true
    const user = get().user
    return user !== null && user.permissions.includes(permission)
  },

  canAny: (permissions) => {
    const user = get().user
    if (user === null) return false
    return permissions.some((permission) => user.permissions.includes(permission))
  },

  hasRole: (role) => {
    const user = get().user
    return user !== null && user.roles.includes(role)
  },

  async signIn(email, password) {
    set({ signingIn: true })
    try {
      const tokens = await authService.login(email, password)
      writeTokens(tokens.accessToken, tokens.refreshToken, tokens.expiresInSeconds)
      set({ accessToken: tokens.accessToken, refreshToken: tokens.refreshToken })
      const user = await authService.me()
      set({ user, hydrating: false })
      return user
    } finally {
      set({ signingIn: false })
    }
  },

  async signOut() {
    const token = get().refreshToken
    // The session is ended locally whatever the server says: a failed revoke must not trap someone
    // in a session they have asked to leave. The refresh token is short-lived and rotates, so the
    // exposure of a failed revoke is small and bounded.
    clearTokens()
    set({ accessToken: null, refreshToken: null, user: null, hydrating: false })
    if (token) {
      try {
        await authService.logout(token)
      } catch {
        // Deliberately ignored - see above.
      }
    }
  },

  async hydrate() {
    if (!readAccessToken()) {
      set({ hydrating: false, user: null })
      return
    }
    set({ hydrating: true })
    try {
      const user = await authService.me()
      set({ user, accessToken: readAccessToken(), refreshToken: readRefreshToken(), hydrating: false })
    } catch {
      // A token that cannot resolve an identity is not a session. Clearing here is what stops the
      // shell from rendering an empty frame around six failing requests.
      clearTokens()
      set({ accessToken: null, refreshToken: null, user: null, hydrating: false })
    }
  },

  clear() {
    clearTokens()
    set({ accessToken: null, refreshToken: null, user: null, hydrating: false })
  },
}))

/*
 * When the API client finds the refresh token can no longer be renewed, it reports the session as
 * over. The store is told here, once, rather than every screen guessing from a 401.
 */
notifySessionExpiredHandler()

function notifySessionExpiredHandler(): void {
  // Registered lazily so importing this module never runs a state change at import time.
  queueMicrotask(() => {
    import('./session').then(({ setSessionExpiredHandler }) => {
      setSessionExpiredHandler(() => {
        useAuthStore.setState({ accessToken: null, refreshToken: null, user: null, hydrating: false })
      })
    })
  })
}
