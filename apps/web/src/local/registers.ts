import type { RegisterSpec } from './query'

/**
 * One specification per register: what its filter parameters mean and which fields it can sort by.
 *
 * ## Where these come from
 *
 * Not from guesswork. Every entry was read off the endpoint it stands in for:
 *
 * - the **filter parameters** are the `@RequestParam` names on the controller's `list` method;
 * - the **search fields** are the repository's `like` clauses, or the service's own `matchesSearch`
 *   where the filtering happens in memory (`ListingService`, `LeadService`, `ExpenseService`,
 *   `MaintenanceService`, `SaleService`, `DocumentService`, `CommunicationService`, `UnitService`,
 *   and the four repositories that do it in SQL);
 * - the **sort aliases** are the `PageRequestFactory.allow(apiField, propertyPath)` mappings, which
 *   exist because the name a screen sorts by is not the name the row carries. A listing is sorted by
 *   `property` but the row holds `propertyName`; a lead by `owner` but the row holds `ownerAgentName`.
 *   Getting those wrong would make the header click do nothing visible, which reads as a broken
 *   table rather than a wrong alias.
 *
 * ## What is deliberately absent
 *
 * Parameters no screen sends are omitted rather than guessed at. `page`, `size` and `sort` are
 * handled by the engine itself and never appear here.
 */

/** Owners are reached through `property_ownerships`, so the holdings index is supplied by the caller. */
export type OwnershipIndex = (ownerId: string) => Set<string>

const today = () => new Date().toISOString().slice(0, 10)

/** "on or after" and "on or before" for a parameter that names a boundary, tolerant of a missing one. */
const atOrAfter = (field: string) => (row: Record<string, any>, value: string) =>
  String(row[field] ?? '') >= value

const atOrBefore = (field: string) => (row: Record<string, any>, value: string) => {
  // A boundary of `2026-11-05T23:59:59` has to include everything recorded during that day, so the
  // comparison is on the date part only when the value carries a time.
  const day = value.slice(0, 10)
  return String(row[field] ?? '').slice(0, 10) <= day
}

/** Whole days between today and a date, negative once it is past. */
function daysUntil(date: string | null | undefined): number | null {
  if (!date) return null
  const target = new Date(`${String(date).slice(0, 10)}T00:00:00Z`).getTime()
  const now = new Date(`${today()}T00:00:00Z`).getTime()
  return Math.round((target - now) / 86_400_000)
}

export function registerSpecs(ownedBy: OwnershipIndex): Record<string, RegisterSpec> {
  const specs: Record<string, RegisterSpec> = {
    /* ------------------------------------------------ asset register */
    properties: {
      filters: {
        status: 'status',
        propertyTypeId: 'propertyTypeId',
        city: 'city',
        // Reached through the ownership table, which is why it needs the index rather than a field.
        ownerId: (row, value) => ownedBy(value).has(row.id),
      },
      search: ['name', 'propertyCode', 'city', 'locality'],
      sortAliases: { propertyType: 'propertyTypeName' },
      defaultSort: 'createdAt,desc',
    },

    units: {
      filters: {
        propertyId: 'propertyId',
        buildingId: 'buildingId',
        unitTypeId: 'unitTypeId',
        status: 'status',
        leasable: 'leasable',
        floor: 'floorNumber',
        bedrooms: 'bedrooms',
      },
      // `UnitService.matchesTerm` searches the unit's own identifiers only.
      search: ['unitNumber', 'unitCode'],
      sortAliases: { unitType: 'unitTypeName', property: 'propertyName', building: 'buildingName' },
      defaultSort: 'unitNumber,asc',
    },

    /* ------------------------------------------------ parties */
    owners: {
      filters: { status: 'status', type: 'ownerType', city: 'city' },
      search: ['displayName', 'ownerCode', 'primaryContactName', 'email', 'phone', 'city'],
      sortAliases: { ownerType: 'ownerType' },
      defaultSort: 'createdAt,desc',
    },

    tenants: {
      filters: { status: 'status', type: 'tenantType', city: 'city' },
      search: ['displayName', 'tenantCode', 'primaryContactName', 'email', 'phone', 'city'],
      sortAliases: { tenantType: 'tenantType' },
      defaultSort: 'createdAt,desc',
    },

    buyers: {
      filters: { status: 'status', type: 'buyerType', city: 'city' },
      search: ['displayName', 'buyerCode', 'primaryContactName', 'email', 'phone', 'city'],
      sortAliases: { buyerType: 'buyerType' },
      defaultSort: 'createdAt,desc',
    },

    /* ------------------------------------------------ agency */
    offices: {
      filters: { status: 'status', city: 'city' },
      search: ['name', 'officeCode', 'city'],
      sortAliases: { headOffice: 'headOffice' },
      defaultSort: 'name,asc',
    },

    agents: {
      filters: { officeId: 'officeId', status: 'status' },
      search: ['fullName', 'agentCode', 'email', 'specialisation'],
      // The endpoint offers `displayName`; the register row calls the same thing `fullName`.
      sortAliases: { displayName: 'fullName' },
      defaultSort: 'createdAt,desc',
    },

    /* ------------------------------------------------ letting */
    leads: {
      filters: {
        stage: 'stage',
        priority: 'priority',
        type: 'leadType',
        source: 'sourceCode',
        ownerAgentId: 'ownerAgentId',
        officeId: 'officeId',
      },
      search: ['displayName', 'companyName', 'email', 'phone', 'leadCode', 'preferredCity', 'preferredLocality'],
      sortAliases: { owner: 'ownerAgentName', source: 'sourceName' },
      defaultSort: 'createdAt,desc',
    },

    listings: {
      filters: {
        propertyId: 'propertyId',
        unitId: 'unitId',
        status: 'status',
        type: 'listingType',
        channel: 'channelCode',
        agentId: 'listedById',
        // A listing is stale when it has stood beyond `LISTING_STALE_DAYS`; the endpoint computes it,
        // and the row carries the answer.
        staleOnly: 'stale',
      },
      search: ['headline', 'listingCode', 'propertyName', 'unitNumber'],
      sortAliases: { property: 'propertyName', unit: 'unitNumber', agent: 'listedByName', channel: 'channelName' },
      defaultSort: 'createdAt,desc',
    },

    viewings: {
      filters: {
        status: 'status',
        agentId: 'agentId',
        propertyId: 'propertyId',
        from: atOrAfter('scheduledAt'),
        to: atOrBefore('scheduledAt'),
      },
      search: ['viewingCode', 'prospectName', 'propertyName', 'agentName'],
      sortAliases: { agent: 'agentName', property: 'propertyName' },
      defaultSort: 'scheduledAt,desc',
    },

    applications: {
      filters: {
        propertyId: 'propertyId',
        unitId: 'unitId',
        status: 'status',
        applicant: { contains: ['applicantName'] },
      },
      search: ['applicationCode', 'applicantName', 'applicantEmail'],
      sortAliases: { applicant: 'applicantName' },
      defaultSort: 'submittedAt,desc',
    },

    leases: {
      filters: {
        propertyId: 'propertyId',
        unitId: 'unitId',
        tenantId: 'tenantId',
        status: 'status',
        from: atOrAfter('startDate'),
        to: atOrBefore('endDate'),
        // A lease still running whose end falls inside the window.
        expiringWithinDays: (row, value) => {
          const days = daysUntil(row.endDate)
          if (days === null) return false
          return days >= 0 && days <= Number(value)
        },
      },
      search: ['leaseCode', 'tenantName', 'propertyName'],
      defaultSort: 'createdAt,desc',
    },

    /* ------------------------------------------------ ledger */
    invoices: {
      filters: {
        leaseId: 'leaseId',
        tenantId: 'tenantId',
        propertyId: 'propertyId',
        status: 'status',
        from: atOrAfter('periodStart'),
        to: atOrBefore('periodStart'),
        // Unsettled and past its due date - the two facts together, which is what the screen means.
        overdueOnly: (row) =>
          String(row.status).toUpperCase() !== 'VOID' &&
          String(row.dueDate ?? '').slice(0, 10) < today(),
      },
      search: ['invoiceNumber', 'leaseCode'],
      defaultSort: 'dueDate,desc',
    },

    payments: {
      filters: {
        leaseId: 'leaseId',
        tenantId: 'tenantId',
        propertyId: 'propertyId',
        status: 'status',
        direction: 'direction',
        from: atOrAfter('receivedAt'),
        to: atOrBefore('receivedAt'),
      },
      search: ['receiptNumber', 'reference'],
      defaultSort: 'receivedAt,desc',
    },

    /* ------------------------------------------------ operations */
    maintenance: {
      filters: {
        propertyId: 'propertyId',
        unitId: 'unitId',
        status: 'status',
        priority: 'priority',
        category: 'category',
        reportedFrom: atOrAfter('reportedAt'),
        reportedTo: atOrBefore('reportedAt'),
        overdueOnly: 'overdue',
      },
      search: ['requestCode', 'title', 'reporterName', 'propertyName', 'unitCode'],
      sortAliases: { property: 'propertyName', vendor: 'assignedVendorName' },
      defaultSort: 'createdAt,desc',
    },

    expenses: {
      filters: {
        propertyId: 'propertyId',
        unitId: 'unitId',
        categoryId: 'categoryId',
        capital: 'capital',
        from: atOrAfter('expenseDate'),
        to: atOrBefore('expenseDate'),
      },
      search: ['expenseCode', 'description', 'invoiceReference', 'propertyName'],
      sortAliases: { category: 'categoryName', property: 'propertyName', vendor: 'vendorName' },
      defaultSort: 'createdAt,desc',
    },

    /* ------------------------------------------------ capital markets */
    sales: {
      filters: {
        stage: 'stage',
        ownerAgentId: 'ownerAgentId',
        openOnly: 'open',
      },
      search: ['saleCode', 'propertyName', 'propertyCode', 'unitCode', 'buyerName'],
      sortAliases: { property: 'propertyName', buyer: 'buyerName' },
      defaultSort: 'createdAt,desc',
    },

    offers: {
      filters: { saleId: 'saleId', status: 'status', direction: 'direction' },
      search: ['offerNumber'],
      defaultSort: 'offeredAt,desc',
    },

    /* ------------------------------------------------ records */
    portfolios: {
      filters: { status: 'status', strategy: 'strategy' },
      search: ['name', 'portfolioCode', 'description'],
      defaultSort: 'createdAt,desc',
    },

    documents: {
      filters: {
        propertyId: 'propertyId',
        unitId: 'unitId',
        leaseId: 'leaseId',
        tenantId: 'tenantId',
        ownerId: 'ownerId',
        buyerId: 'buyerId',
        saleId: 'saleId',
        maintenanceRequestId: 'maintenanceRequestId',
        type: 'documentType',
        status: 'status',
        expiringWithinDays: (row, value) => {
          const days = daysUntil(row.expiryDate)
          if (days === null) return false
          return days >= 0 && days <= Number(value)
        },
      },
      search: ['title', 'documentCode'],
      defaultSort: 'createdAt,desc',
    },

    /* ------------------------------------------------ interaction */
    communications: {
      filters: {
        status: 'status',
        channel: 'channel',
        priority: 'priority',
        unreadOnly: (row) => Number(row.unreadCount ?? 0) > 0,
      },
      search: ['subject', 'conversationCode', 'counterpartyName', 'counterpartyEmail'],
      sortAliases: { assignedTo: 'assignedToName' },
      defaultSort: 'lastMessageAt,desc',
    },

    /* ------------------------------------------------ audit and support */
    audit: {
      filters: {
        module: 'module',
        action: 'action',
        severity: 'severity',
        actor: { contains: ['actorName'] },
        entityType: 'entityType',
        entityId: 'entityId',
        from: atOrAfter('createdAt'),
        to: atOrBefore('createdAt'),
      },
      sortAliases: { actor: 'actorName' },
      defaultSort: 'createdAt,desc',
    },

    imports: {
      filters: { entityType: 'entityType', status: 'status' },
      defaultSort: 'createdAt,desc',
    },
  }

  return specs
}

/**
 * The notification feed is per account, so it is filtered here rather than in `registerSpecs`.
 * `unreadOnly` arrives as the string `'true'`, because `buildUrl` stringifies every parameter.
 */
export const notificationSpec: RegisterSpec = {
  filters: {
    severity: 'severity',
    unreadOnly: (row) => row.read !== true,
  },
  defaultSort: 'createdAt,desc',
}
