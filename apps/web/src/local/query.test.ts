import { describe, expect, it } from 'vitest'
import { applyFilters, applySort, paginate, readRegister, type Row } from './query'
import { registerSpecs } from './registers'

/**
 * The register engine.
 *
 * These pin the rules the whole filter bar rests on, and the first is the one that would be least
 * visible if it broke: `buildUrl` drops empty parameters, so "the reader cleared this filter" reaches
 * the engine as "the parameter is absent". An engine that read a missing value as `''` would match
 * nothing and every register would open empty.
 */
describe('the register engine', () => {
  const rows: Row[] = [
    { id: 'a', name: 'Harbour Court', city: 'Mumbai', status: 'ACTIVE', units: 32, leasable: true },
    { id: 'b', name: 'Meridian One', city: 'Mumbai', status: 'ACTIVE', units: 14, leasable: true },
    { id: 'c', name: 'Orion Tech Square', city: 'Bengaluru', status: 'ARCHIVED', units: 12, leasable: false },
  ]
  const spec = registerSpecs(() => new Set<string>())

  describe('filters', () => {
    it('matches everything when a parameter is absent, empty or null', () => {
      expect(readRegister(rows, {}, spec.properties).totalElements).toBe(3)
      expect(readRegister(rows, { status: '' }, spec.properties).totalElements).toBe(3)
    })

    it('filters on an exact value, ignoring case', () => {
      expect(readRegister(rows, { city: 'mumbai' }, spec.properties).totalElements).toBe(2)
      expect(readRegister(rows, { status: 'archived' }, spec.properties).totalElements).toBe(1)
    })

    it('filters on a boolean, which arrives as the string "false"', () => {
      const filtered = readRegister(rows, { leasable: 'false' }, spec.units).items
      expect(filtered.map((row) => row.id)).toEqual(['c'])
    })

    it('filters on a number written as text', () => {
      expect(readRegister(rows, { units: '14' }, { filters: { units: 'units' } }).totalElements).toBe(1)
    })

    it('ignores a parameter the register does not declare, rather than matching nothing', () => {
      // `properties` has no `units` filter, so the parameter is not one this register understands.
      // It is dropped - which is what stops a stale filter in a URL, or one sent to the wrong
      // endpoint, from emptying a register that has rows.
      expect(readRegister(rows, { units: '14' }, spec.properties).totalElements).toBe(3)
    })

    it('searches only the fields the register declares', () => {
      // `properties` searches name, code, city and locality - so a city matches...
      expect(readRegister(rows, { search: 'bengaluru' }, spec.properties).totalElements).toBe(1)
      // ...and a status does not, even though the row carries one.
      expect(readRegister(rows, { search: 'archived' }, spec.properties).totalElements).toBe(0)
    })

    it('combines a filter and a search, as a screen with both does', () => {
      expect(readRegister(rows, { city: 'Mumbai', search: 'harbour' }, spec.properties).totalElements).toBe(1)
    })
  })

  describe('sorting', () => {
    it('keeps the captured order when nothing was asked for', () => {
      expect(applySort(rows, undefined, {}).map((row) => row.id)).toEqual(['a', 'b', 'c'])
    })

    it('sorts numbers numerically rather than as text', () => {
      // As text '14' < '32' < '12' would be wrong; numerically 12 < 14 < 32.
      const sorted = applySort(rows, 'units,asc', {}).map((row) => row.id)
      expect(sorted).toEqual(['c', 'b', 'a'])
    })

    it('honours the direction', () => {
      expect(applySort(rows, 'name,desc', {}).map((row) => row.id)).toEqual(['c', 'b', 'a'])
    })

    it('follows an alias, so the field a screen sorts by need not be the field the row carries', () => {
      // `listings` offers `property`, and the row holds `propertyName`.
      const listings: Row[] = [
        { id: '1', propertyName: 'Zephyr' },
        { id: '2', propertyName: 'Ashwood' },
      ]
      expect(applySort(listings, 'property,asc', spec.listings).map((row) => row.id)).toEqual(['2', '1'])
    })

    it('sinks blanks in both directions rather than letting them lead a descending sort', () => {
      const withBlank: Row[] = [{ id: '1', units: 5 }, { id: '2', units: null }, { id: '3', units: 9 }]
      expect(applySort(withBlank, 'units,desc', {}).map((row) => row.id)).toEqual(['3', '1', '2'])
      expect(applySort(withBlank, 'units,asc', {}).map((row) => row.id)).toEqual(['1', '3', '2'])
    })
  })

  describe('paging', () => {
    const many: Row[] = Array.from({ length: 222 }, (_, index) => ({ id: String(index) }))

    it('reports the envelope a pager needs, not just the rows', () => {
      const page = paginate(many, { page: '0', size: '100' })
      expect(page.items).toHaveLength(100)
      expect(page).toMatchObject({ page: 0, size: 100, totalElements: 222, totalPages: 3, hasNext: true })
    })

    it('reports the last page as having nothing after it', () => {
      expect(paginate(many, { page: '2', size: '100' }).hasNext).toBe(false)
    })

    it('clamps the size to the maximum the service accepts', () => {
      expect(paginate(many, { size: '5000' }).size).toBe(100)
    })

    it('falls back to the default page size', () => {
      expect(paginate(many, {}).size).toBe(20)
    })

    it('reads a negative or unparseable page as the first one', () => {
      expect(paginate(many, { page: '-3' }).page).toBe(0)
      expect(paginate(many, { page: 'abc' }).page).toBe(0)
    })

    it('describes an empty register without dividing by zero', () => {
      expect(paginate([], {})).toMatchObject({ items: [], totalElements: 0, totalPages: 0, hasNext: false })
    })
  })

  describe('the composite rule', () => {
    it('filters, sorts and pages in that order', () => {
      const page = readRegister(rows, { sort: 'name,asc', page: '0', size: '2' }, {})
      expect(page.items.map((row) => row.name)).toEqual(['Harbour Court', 'Meridian One'])
      expect(page.totalElements).toBe(3)
      expect(page.hasNext).toBe(true)
    })

    it('counts the filtered set, not the whole register', () => {
      expect(readRegister(rows, { city: 'Mumbai' }, { filters: { city: 'city' } }).totalElements).toBe(2)
    })

    it('never matches when a filter names a field no row has', () => {
      expect(applyFilters(rows, { nothing: 'x' }, { filters: { nothing: 'ghost' } })).toHaveLength(0)
    })
  })
})
