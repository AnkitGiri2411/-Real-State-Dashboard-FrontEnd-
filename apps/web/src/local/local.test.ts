import { describe, expect, it } from 'vitest'
import { respond, type LocalRequest } from './router'
import { login, me, changePassword, forgetAccount, rememberAccount } from './auth'
import { loadRegister } from './data'
import { ApiError } from '../lib/errors'
import type { PageResponse } from '../types'

/**
 * The local data source, driven through the same entry point `apiFetch` uses.
 *
 * These are the behaviours a static deployment depends on and that a reader would notice breaking:
 * signing in, opening a register, reading a record, and reaching something that is not there.
 */

function get(path: string, query: Record<string, string> = {}): Promise<unknown> {
  return respond({ path, method: 'GET', query } as LocalRequest)
}

function send(path: string, method: string, body: Record<string, unknown> = {}): Promise<unknown> {
  return respond({ path, method, query: {}, body } as LocalRequest)
}

async function firstPropertyId(): Promise<string> {
  const { items } = await loadRegister('properties')
  return items[0].id as string
}

describe('sign-in', () => {
  it('accepts a seeded account with the published demonstration password', async () => {
    const tokens = await login('owner@meridianestates.example', 'testing')
    expect(tokens.accessToken).toBeTruthy()
    expect(tokens.expiresInSeconds).toBeGreaterThan(0)
  })

  it('gives the account its real roles and permissions, not a blanket grant', async () => {
    await login('owner@meridianestates.example', 'testing')
    const user = await me()
    expect(user.roles).toEqual(['OWNER'])
    expect(user.permissions).toContain('PROPERTY_VIEW')
  })

  it('gives a restricted role its restricted set, which is what the sidebar is built from', async () => {
    forgetAccount()
    await login('viewer@meridianestates.example', 'testing')
    const user = await me()
    expect(user.roles).toEqual(['VIEWER'])
    // The viewer holds only `_VIEW` permissions - `V2`'s guard block asserts exactly that.
    expect(user.permissions).not.toContain('PROPERTY_CREATE')
  })

  it('refuses a wrong password with the code the sign-in screen already handles', async () => {
    await expect(login('owner@meridianestates.example', 'wrong')).rejects.toMatchObject({
      code: 'INVALID_CREDENTIALS',
      status: 401,
    })
  })

  it('refuses an address that is not one of the ten accounts', async () => {
    await expect(login('nobody@example.com', 'testing')).rejects.toBeInstanceOf(ApiError)
  })

  it('treats the address case-insensitively', async () => {
    await expect(login('OWNER@MeridianEstates.Example', 'testing')).resolves.toBeTruthy()
  })

  it('has no session when no account was chosen', async () => {
    forgetAccount()
    await expect(me()).rejects.toMatchObject({ status: 401 })
  })

  it('stays signed in across a reload, which is what remembering the account is for', async () => {
    await login('manager@meridianestates.example', 'testing')
    // A reload loses the store but not localStorage, and `hydrate()` asks `me()` on start.
    expect((await me()).email).toBe('manager@meridianestates.example')
  })

  it('validates a password change rather than pretending to store one', async () => {
    await expect(changePassword('testing', 'short', 'short')).rejects.toMatchObject({ status: 400 })
    await expect(changePassword('testing', 'longenough1', 'different')).rejects.toMatchObject({ status: 400 })
    await expect(changePassword('testing', 'longenough1', 'longenough1')).resolves.toBeUndefined()
  })

  it('answers the auth routes through the router, so the store needs no special case', async () => {
    const tokens = (await send('/auth/login', 'POST', {
      email: 'admin@meridianestates.example',
      password: 'testing',
    })) as { accessToken: string }
    expect(tokens.accessToken).toBeTruthy()
    expect((await get('/auth/me'))).toMatchObject({ email: 'admin@meridianestates.example' })
  })
})

describe('registers', () => {
  it('serves a whole register with the envelope a pager reads', async () => {
    const page = (await get('/properties', { size: '100' })) as PageResponse<unknown>
    expect(page.totalElements).toBe(14)
    expect(page.items).toHaveLength(14)
    expect(page.hasNext).toBe(false)
  })

  it('pages a large register', async () => {
    const page = (await get('/units', { page: '0', size: '100' })) as PageResponse<unknown>
    expect(page.totalElements).toBe(222)
    expect(page.items).toHaveLength(100)
    expect(page.totalPages).toBe(3)
    expect(page.hasNext).toBe(true)
  })

  it('sorts by the field the screen names', async () => {
    const page = (await get('/properties', { sort: 'name,asc', size: '100' })) as PageResponse<
      Record<string, string>
    >
    const names = page.items.map((row) => row.name)
    expect(names).toEqual([...names].sort((left, right) => left.localeCompare(right)))
  })

  it('filters on an id, which is how one screen narrows to one property', async () => {
    const propertyId = await firstPropertyId()
    const page = (await get('/units', { propertyId, size: '100' })) as PageResponse<
      Record<string, string>
    >
    expect(page.totalElements).toBeGreaterThan(0)
    expect(page.items.every((row) => row.propertyId === propertyId)).toBe(true)
  })

  it('returns one record with the fields only the detail carries', async () => {
    const propertyId = await firstPropertyId()
    const detail = (await get(`/properties/${propertyId}`)) as Record<string, unknown>
    expect(detail.id).toBe(propertyId)
    // `addressLine1` is on the detail and not on the register row.
    expect(detail).toHaveProperty('addressLine1')
  })

  it('fails a record that does not exist the way the API fails it', async () => {
    await expect(get('/properties/00000000-0000-0000-0000-000000000000')).rejects.toMatchObject({
      status: 404,
    })
  })

  it('serves a per-record projection', async () => {
    const propertyId = await firstPropertyId()
    expect(await get(`/properties/${propertyId}/metrics`)).toBeTruthy()
  })

  it('serves the unpaged reference lists', async () => {
    expect(await get('/property-types')).toHaveLength(9)
    expect(await get('/amenities')).toHaveLength(18)
  })

  it('says so when asked for something this build does not carry', async () => {
    await expect(get('/something-that-does-not-exist')).rejects.toMatchObject({
      code: 'NOT_AVAILABLE_LOCALLY',
    })
  })
})

describe('aggregates', () => {
  it('serves the dashboard summary the shell and the dashboard both read', async () => {
    const summary = (await get('/dashboard/summary')) as Record<string, any>
    expect(summary.portfolio.propertyCount).toBe(14)
    expect(Array.isArray(summary.trend)).toBe(true)
  })

  it('serves the platform profile that the whole shell boots from', async () => {
    const profile = (await get('/platform/profile')) as Record<string, any>
    expect(profile.firm.name).toBeTruthy()
    expect(Array.isArray(profile.navigation)).toBe(true)
  })

  it('serves the projections the detail screens read instead of computing', async () => {
    expect(Array.isArray(await get('/properties/map'))).toBe(true)
    expect(await get('/leases/expiring')).toBeTruthy()
    expect(await get('/viewings/diary')).toBeTruthy()
  })

  it('windows a report to the dates asked for', async () => {
    const all = (await get('/reports/rental-income', { size: '100' })) as Record<string, any>
    const narrowed = (await get('/reports/rental-income', {
      from: '2026-01-01',
      to: '2026-03-31',
    })) as Record<string, any>

    expect(narrowed.rows.length).toBeLessThanOrEqual(all.rows.length)
    expect(narrowed.from).toBe('2026-01-01')
    expect(narrowed.to).toBe('2026-03-31')
  })

  it('fails a report this build does not carry', async () => {
    await expect(get('/reports/no-such-report')).rejects.toMatchObject({ status: 404 })
  })
})

describe('writes, which last until the page is reloaded', () => {
  it('creates a record that the register then contains', async () => {
    rememberAccount('owner@meridianestates.example')
    const before = (await get('/properties', { size: '100' })) as PageResponse<unknown>

    const created = (await send('/properties', 'POST', {
      name: 'Test Quay',
      city: 'Kochi',
      addressLine1: '1 Test Quay',
    })) as Record<string, string>

    expect(created.id).toBeTruthy()
    expect(created.propertyCode).toMatch(/^ME-P-/)

    const after = (await get('/properties', { size: '100' })) as PageResponse<unknown>
    expect(after.totalElements).toBe(before.totalElements + 1)
  })

  it('finds the created record through the search box too, because there is one array', async () => {
    const found = (await get('/properties', { search: 'Test Quay' })) as PageResponse<unknown>
    expect(found.items).toHaveLength(1)
  })

  it('updates a record in place', async () => {
    const propertyId = await firstPropertyId()
    const updated = (await send(`/properties/${propertyId}`, 'PUT', {
      name: 'Renamed In Place',
    })) as Record<string, string>
    expect(updated.name).toBe('Renamed In Place')
    expect((await get(`/properties/${propertyId}`) as Record<string, string>).name).toBe(
      'Renamed In Place',
    )
  })

  it('applies an action to the record it names', async () => {
    const propertyId = await firstPropertyId()
    const archived = (await send(`/properties/${propertyId}/archive`, 'POST', {})) as Record<
      string,
      string
    >
    expect(archived.status).toBe('ARCHIVED')
  })

  it('accepts an action the frontend sends with a method the backend declares differently', async () => {
    // `listingApi.changeStatus` posts to `/listings/{id}/status` while `ListingController` maps PUT.
    const { items } = await loadRegister('listings')
    const listingId = items[0].id as string
    const changed = (await send(`/listings/${listingId}/status`, 'POST', {
      status: 'PUBLISHED',
    })) as Record<string, string>
    expect(changed.status).toBe('PUBLISHED')
  })

  it('marks a notification read and drops the unread badge', async () => {
    rememberAccount('manager@meridianestates.example')
    const before = (await get('/notifications/unread-count')) as { unread: number }
    expect(before.unread).toBeGreaterThan(0)

    await send('/notifications/read', 'POST', {})
    expect((await get('/notifications/unread-count')) as { unread: number }).toMatchObject({
      unread: 0,
    })
  })

  it('shows an account with no notifications an empty feed rather than another account’s', async () => {
    rememberAccount('viewer@meridianestates.example')
    const page = (await get('/notifications', { size: '100' })) as PageResponse<unknown>
    expect(page.totalElements).toBe(0)
  })
})
