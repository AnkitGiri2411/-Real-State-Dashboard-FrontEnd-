import { apiFetch } from '../services/apiClient'
import type { AuthTokens, CurrentUser } from '../types'

/**
 * The authentication calls.
 *
 * Every one of these passes `skipAuthRefresh` where the endpoint is an auth path, so a stale token
 * cannot cause the client to try to renew its way out of a failed sign-in.
 */
export const authService = {
  /** Sign in. On success the caller stores both tokens - this function deliberately does not. */
  login(email: string, password: string): Promise<AuthTokens> {
    return apiFetch<AuthTokens>('/auth/login', {
      method: 'POST',
      body: { email, password },
      skipAuthRefresh: true,
    })
  },

  /** The signed-in identity, with its roles and the full permission set the JWT carries. */
  me(): Promise<CurrentUser> {
    return apiFetch<CurrentUser>('/auth/me')
  },

  /** End this session. The refresh token is revoked server-side, so the token cannot be replayed. */
  logout(refreshToken: string): Promise<void> {
    return apiFetch<void>('/auth/logout', {
      method: 'POST',
      body: { refreshToken },
      skipAuthRefresh: true,
    })
  },

  /** End every session for this account, not only this browser's. */
  logoutEverywhere(): Promise<void> {
    return apiFetch<void>('/auth/logout-all', { method: 'POST' })
  },

  changePassword(currentPassword: string, newPassword: string, confirmPassword: string): Promise<void> {
    return apiFetch<void>('/auth/change-password', {
      method: 'POST',
      body: { currentPassword, newPassword, confirmPassword },
    })
  },
}
