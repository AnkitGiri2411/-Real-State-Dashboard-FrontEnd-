import type { AuthTokens, CurrentUser } from '../types'
import { loadAccounts } from './data'
import { badRequest, invalidCredentials, unauthorized } from './http'

/**
 * Sign-in, resolved in the browser.
 *
 * The ten accounts are the ones the seed creates in
 * `V2__seed_roles_permissions_and_users.sql`, captured from `GET /auth/me` as each of them - so the
 * roles and the full 99-permission set are the database's own rather than a transcription of it that
 * could drift. That matters beyond sign-in: every guarded route, every gated panel and the entire
 * sidebar read `user.permissions`, so a wrong set here would silently change what the product looks
 * like for a role.
 *
 * ## What this is not
 *
 * It is not authentication. The password is compared against a value compiled into the bundle, and
 * anybody can read it - which is exactly why the account list is the published demonstration one and
 * the password is the published demonstration password. A static site cannot hold a secret, and this
 * does not pretend otherwise.
 */

/** The published demonstration credential, as `docs/SETUP.md` documents it. */
const DEMO_PASSWORD = 'testing'

/**
 * Where the chosen account is remembered.
 *
 * Its own key, deliberately: `session.ts` owns the token keys, and a reload has to survive with the
 * token machinery none the wiser. `authStore.hydrate()` already asks `GET /auth/me` on start, so
 * remembering the account here is all that is needed for a reload to stay signed in.
 */
const ACCOUNT_KEY = 'meridian.demoAccount'

/** Long enough that the session never tries to refresh - there is no refresh endpoint to call. */
const SESSION_SECONDS = 60 * 60 * 24 * 30

function storage(): Storage | null {
  try {
    return window.localStorage
  } catch {
    // Private modes that throw on access: the session then lasts as long as the tab.
    return null
  }
}

export function rememberAccount(email: string): void {
  storage()?.setItem(ACCOUNT_KEY, email)
}

export function forgetAccount(): void {
  storage()?.removeItem(ACCOUNT_KEY)
}

/** The account this browser last signed in as, if any. */
export function currentAccountEmail(): string | null {
  return storage()?.getItem(ACCOUNT_KEY) ?? null
}

async function findAccount(email: string): Promise<CurrentUser | null> {
  const accounts = await loadAccounts()
  const wanted = email.trim().toLowerCase()
  return (accounts.find((entry) => entry.email.toLowerCase() === wanted)?.user as CurrentUser) ?? null
}

/**
 * `POST /auth/login`.
 *
 * A refusal is deliberately the same `ApiError` the service raises, so `LoginPage`'s own handling -
 * keep what was typed, offer a retry - works here without a special case.
 */
export async function login(email: string, password: string): Promise<AuthTokens> {
  if (!email?.trim() || !password) {
    throw invalidCredentials()
  }

  const account = await findAccount(email)
  if (!account || password !== DEMO_PASSWORD) {
    throw invalidCredentials()
  }
  if (String(account.status).toUpperCase() !== 'ACTIVE') {
    throw unauthorized('That account is not active.')
  }

  rememberAccount(account.email)
  return {
    accessToken: `demo-access-token.${account.id}`,
    refreshToken: `demo-refresh-token.${account.id}`,
    tokenType: 'Bearer',
    expiresInSeconds: SESSION_SECONDS,
  }
}

/** `GET /auth/me`. No account remembered means no session, which is a 401 like any expired one. */
export async function me(): Promise<CurrentUser> {
  const email = currentAccountEmail()
  if (!email) throw unauthorized()

  const account = await findAccount(email)
  if (!account) {
    forgetAccount()
    throw unauthorized()
  }
  return account
}

/**
 * `POST /auth/change-password`.
 *
 * Validated the way the service validates it - a confirmation that disagrees and a new password
 * equal to the current one are both refused with the field names the form already renders - and then
 * accepted without changing anything, because there is nothing to change. `ProfilePage` signs out
 * afterwards either way, so the reader is not left believing a password was stored.
 */
export async function changePassword(
  currentPassword: string,
  newPassword: string,
  confirmPassword: string,
): Promise<void> {
  const details: string[] = []
  if (!currentPassword) details.push('currentPassword: required')
  if (!newPassword) details.push('newPassword: required')
  if (newPassword && newPassword.length < 8) {
    details.push('newPassword: must be at least 8 characters')
  }
  if (newPassword !== confirmPassword) details.push('confirmPassword: the passwords do not match')

  if (details.length > 0) throw badRequest('Some of the details need correcting.', details)
  if (currentPassword !== DEMO_PASSWORD) {
    throw badRequest('Your current password is not correct.', ['currentPassword: incorrect'])
  }
}
