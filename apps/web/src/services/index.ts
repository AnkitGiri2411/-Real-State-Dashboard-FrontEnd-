import { apiFetch, apiFetchRaw } from './apiClient'
import type {
  AgentDetail, AgentSummary, AmenityView, ApplicationDetail, ApplicationSummary, AuditRow,
  BuildingSummary, BuyerDetail, BuyerSummary, CollectionsPosition, CollectionsSummary,
  ConversationDetail, ConversationSummaryRow, ConversationUnreadCount, DashboardSummary,
  DocumentDetail, DocumentExpiryEntry, DocumentSummaryRow, ExpenseCategoryView, ExpenseDetail,
  ExpenseSummaryResponse, ExpenseSummaryRow, ExpiringLease, InvoiceSummary, LeadDetail, LeadSummary,
  LeaseDetail, LeaseSummary, ListingDetail, ListingSummary,
  MaintenanceCostTrendResponse, MaintenanceDetail, MaintenanceSummaryResponse, MaintenanceSummaryRow,
  MapMarker, MessageSummary, NotificationRow, NotificationUnreadCount, OfficeDetail, OfficeSummary,
  OfferEntry, OwnerDetail, OwnerSummary, PageResponse, PaymentDetail, PaymentSummary,
  PerformanceResponse, PipelineResponse, PlatformProfile, PortfolioDetail, PortfolioPerformance,
  PortfolioSummary, PropertyDetail, PropertyMetrics, PropertyOwnershipShare, PropertySummary,
  PropertyTypeView, RentInvoiceDetail, RequirementRecord, SaleDetail, SalesPipelineResponse,
  SalesSummary, SaleSummaryRow, TenantDetail, TenantSummary, UnitBoard, UnitDetail,
  UnitLeaseSummary, UnitOccupancy, UnitSummary, UnitTypeView, ViewingDetail, ViewingSummary,
  WorkloadResponse,
} from '../types'

/**
 * The frontend's API map: one place that knows every endpoint's path and shape.
 *
 * Pages never build a URL. That is what keeps a route rename to a single edit, stops a screen from
 * inventing `?limit=` when the backend reads `?size=`, and makes it obvious which endpoints this
 * product actually calls - so a screen cannot quietly depend on something that does not exist.
 *
 * **Every wrapper declares the record the controller actually returns.** Read against
 * `com.meridian.realestate.controller.*`: a `detail()` returns the `…Detail` record, not the register's
 * `…Summary`, and a projection endpoint (`metrics`, `workload`, `performance`, `collections`,
 * `summary`) returns its own record rather than a loose map. A wrapper that named a smaller or a
 * vaguer type than the endpoint sends is how a screen comes to read a field the API never wrote.
 *
 * Read against the real controllers: `/api/v1/properties`, `/units`, `/owners`, `/tenants`,
 * `/buyers`, `/offices`, `/agents`, `/leads`, `/listings`, `/viewings`, `/applications`, `/leases`,
 * `/rent-invoices`, `/payments`, `/maintenance-requests`, `/expenses`, `/expense-categories`,
 * `/sales`, `/offers`, `/documents`, `/portfolios`, `/communications`, `/notifications`, `/reports`,
 * `/audit`, `/imports`, `/dashboard`, `/platform`, `/auth`.
 */

/** Page/size/sort, as every paged endpoint accepts them. */
export interface PageParams {
  page?: number
  size?: number
  sort?: string
  [filter: string]: string | number | boolean | undefined
}

/** The one shape a register is read in. */
export function listResource<T>(path: string, params: PageParams = {}): Promise<PageResponse<T>> {
  return apiFetch<PageResponse<T>>(path, { query: params })
}

export function getResource<T>(path: string): Promise<T> {
  return apiFetch<T>(path)
}

export function createResource<T>(path: string, body: unknown): Promise<T> {
  return apiFetch<T>(path, { method: 'POST', body })
}

/**
 * Update a record.
 *
 * **PUT, not PATCH.** Every register in this backend declares `@PutMapping` for its update route
 * (`PropertyController`, `UnitController`, `LeaseController`, `PortfolioController`, …). Sending
 * PATCH produces a 405 `METHOD_NOT_ALLOWED` — which is what happened the first time these forms
 * were exercised against the live API, and why nothing short of a real request could have found it.
 * `patchResource` exists for the one place the API genuinely patches (an offer decision).
 */
export function updateResource<T>(path: string, body: unknown): Promise<T> {
  return apiFetch<T>(path, { method: 'PUT', body })
}

/** For the endpoints the API genuinely declares as PATCH. */
export function patchResource<T>(path: string, body: unknown): Promise<T> {
  return apiFetch<T>(path, { method: 'PATCH', body })
}

export function replaceResource<T>(path: string, body: unknown): Promise<T> {
  return apiFetch<T>(path, { method: 'PUT', body })
}

export function deleteResource<T = void>(path: string): Promise<T> {
  return apiFetch<T>(path, { method: 'DELETE' })
}

/** Download a CSV export to the reader's machine. */
export async function downloadCsv(path: string, params: PageParams, filename: string): Promise<void> {
  const response = await apiFetchRaw(path, { query: params })
  const blob = await response.blob()
  const url = URL.createObjectURL(blob)
  const anchor = document.createElement('a')
  anchor.href = url
  anchor.download = filename
  document.body.appendChild(anchor)
  anchor.click()
  anchor.remove()
  URL.revokeObjectURL(url)
}

/** The platform profile the whole shell boots from - identity, branding, navigation and policy. */
export const platformApi = {
  profile: () => getResource<PlatformProfile>('/platform/profile'),
}

/** The executive dashboard. One call, so the figures on screen cannot disagree with each other. */
export const dashboardApi = {
  summary: () => getResource<DashboardSummary>('/dashboard/summary'),
}

export const propertyApi = {
  list: (params: PageParams = {}) => listResource<PropertySummary>('/properties', params),
  /** The full record for one property. The register row is `list`, and does not carry this. */
  detail: (id: string) => getResource<PropertyDetail>(`/properties/${id}`),
  /**
   * The property's occupancy, rent roll, valuation and areas.
   *
   * This - not the register row - is where a property's unit counts and occupancy come from. It is one
   * request per property read, which is why only a screen that reads one property may use it.
   */
  metrics: (id: string) => getResource<PropertyMetrics>(`/properties/${id}/metrics`),
  /** Only properties with real coordinates. The map never plots a guessed position. */
  map: () => getResource<MapMarker[]>('/properties/map'),
  buildings: (id: string) => getResource<BuildingSummary[]>(`/properties/${id}/buildings`),
  units: (id: string, params: PageParams = {}) =>
    listResource<UnitSummary>(`/properties/${id}/units`, params),
  ownership: (id: string) => getResource<PropertyOwnershipShare[]>(`/properties/${id}/ownership`),
  create: (body: unknown) => createResource<PropertyDetail>('/properties', body),
  update: (id: string, body: unknown) => updateResource<PropertyDetail>(`/properties/${id}`, body),
  changeStatus: (id: string, body: unknown) =>
    createResource<PropertyDetail>(`/properties/${id}/status`, body),
  archive: (id: string) => createResource<PropertyDetail>(`/properties/${id}/archive`, {}),
}

export const unitApi = {
  list: (params: PageParams = {}) => listResource<UnitSummary>('/units', params),
  detail: (id: string) => getResource<UnitDetail>(`/units/${id}`),
  board: (propertyId: string) =>
    getResource<UnitBoard>(`/units/board?propertyId=${propertyId}`),
  /** The occupancy counts for one property, over its leasable stock. */
  occupancy: (propertyId: string) =>
    getResource<UnitOccupancy>(`/units/occupancy?propertyId=${propertyId}`),
  /** The lease currently in force on a unit, read-only from the unit module. */
  lease: (id: string) => getResource<UnitLeaseSummary>(`/units/${id}/lease`),
  create: (body: unknown) => createResource<UnitDetail>('/units', body),
  update: (id: string, body: unknown) => updateResource<UnitDetail>(`/units/${id}`, body),
  changeStatus: (id: string, body: unknown) => createResource<UnitDetail>(`/units/${id}/status`, body),
}

export const ownerApi = {
  list: (params: PageParams = {}) => listResource<OwnerSummary>('/owners', params),
  detail: (id: string) => getResource<OwnerDetail>(`/owners/${id}`),
  create: (body: unknown) => createResource<OwnerDetail>('/owners', body),
  update: (id: string, body: unknown) => updateResource<OwnerDetail>(`/owners/${id}`, body),
}

export const tenantApi = {
  list: (params: PageParams = {}) => listResource<TenantSummary>('/tenants', params),
  detail: (id: string) => getResource<TenantDetail>(`/tenants/${id}`),
  create: (body: unknown) => createResource<TenantDetail>('/tenants', body),
  update: (id: string, body: unknown) => updateResource<TenantDetail>(`/tenants/${id}`, body),
}

export const buyerApi = {
  list: (params: PageParams = {}) => listResource<BuyerSummary>('/buyers', params),
  detail: (id: string) => getResource<BuyerDetail>(`/buyers/${id}`),
  /** A buyer's briefs, active first and then the superseded ones as history. */
  requirements: (id: string) => getResource<RequirementRecord[]>(`/buyers/${id}/requirements`),
  create: (body: unknown) => createResource<BuyerDetail>('/buyers', body),
  update: (id: string, body: unknown) => updateResource<BuyerDetail>(`/buyers/${id}`, body),
}

export const officeApi = {
  list: (params: PageParams = {}) => listResource<OfficeSummary>('/offices', params),
  detail: (id: string) => getResource<OfficeDetail>(`/offices/${id}`),
}

export const agentApi = {
  list: (params: PageParams = {}) => listResource<AgentSummary>('/agents', params),
  detail: (id: string) => getResource<AgentDetail>(`/agents/${id}`),
  workload: (id: string) => getResource<WorkloadResponse>(`/agents/${id}/workload`),
  performance: (id: string) => getResource<PerformanceResponse>(`/agents/${id}/performance`),
}

export const leadApi = {
  list: (params: PageParams = {}) => listResource<LeadSummary>('/leads', params),
  detail: (id: string) => getResource<LeadDetail>(`/leads/${id}`),
  pipeline: () => getResource<PipelineResponse>('/leads/pipeline'),
  create: (body: unknown) => createResource<LeadDetail>('/leads', body),
  update: (id: string, body: unknown) => updateResource<LeadDetail>(`/leads/${id}`, body),
  changeStage: (id: string, body: unknown) => createResource<LeadDetail>(`/leads/${id}/stage`, body),
  assign: (id: string, body: unknown) => createResource<LeadDetail>(`/leads/${id}/assign`, body),
}

export const listingApi = {
  list: (params: PageParams = {}) => listResource<ListingSummary>('/listings', params),
  detail: (id: string) => getResource<ListingDetail>(`/listings/${id}`),
  create: (body: unknown) => createResource<ListingDetail>('/listings', body),
  update: (id: string, body: unknown) => updateResource<ListingDetail>(`/listings/${id}`, body),
  changeStatus: (id: string, body: unknown) =>
    createResource<ListingDetail>(`/listings/${id}/status`, body),
}

export const viewingApi = {
  list: (params: PageParams = {}) => listResource<ViewingSummary>('/viewings', params),
  detail: (id: string) => getResource<ViewingDetail>(`/viewings/${id}`),
  diary: () => getResource<ViewingSummary[]>('/viewings/diary'),
  create: (body: unknown) => createResource<ViewingDetail>('/viewings', body),
  update: (id: string, body: unknown) => updateResource<ViewingDetail>(`/viewings/${id}`, body),
  complete: (id: string, body: unknown) =>
    createResource<ViewingDetail>(`/viewings/${id}/complete`, body),
  cancel: (id: string, body: unknown) =>
    createResource<ViewingDetail>(`/viewings/${id}/cancel`, body),
}

export const applicationApi = {
  list: (params: PageParams = {}) => listResource<ApplicationSummary>('/applications', params),
  detail: (id: string) => getResource<ApplicationDetail>(`/applications/${id}`),
  create: (body: unknown) => createResource<ApplicationDetail>('/applications', body),
  /** Record one screening result. A decision is refused while any check is still PENDING. */
  screen: (id: string, body: unknown) =>
    createResource<ApplicationDetail>(`/applications/${id}/screenings`, body),
  approve: (id: string, body: unknown) =>
    createResource<ApplicationDetail>(`/applications/${id}/approve`, body),
  reject: (id: string, body: unknown) =>
    createResource<ApplicationDetail>(`/applications/${id}/reject`, body),
  withdraw: (id: string, body: unknown) =>
    createResource<ApplicationDetail>(`/applications/${id}/withdraw`, body),
  /** Route a decision to the endpoint that means it - the API has one path per outcome. */
  decide: (id: string, body: { decision: string } & Record<string, unknown>) => {
    const { decision, ...rest } = body
    const path = decision.toUpperCase() === 'APPROVED' ? 'approve'
      : decision.toUpperCase() === 'REJECTED' ? 'reject'
        : 'withdraw'
    return createResource<ApplicationDetail>(`/applications/${id}/${path}`, rest)
  },
}

export const leaseApi = {
  list: (params: PageParams = {}) => listResource<LeaseSummary>('/leases', params),
  detail: (id: string) => getResource<LeaseDetail>(`/leases/${id}`),
  /** The leases still running that end inside the configured expiry window, with the days counted. */
  expiring: (withinDays?: number) =>
    getResource<ExpiringLease[]>(
      `/leases/expiring${withinDays ? `?withinDays=${withinDays}` : ''}`,
    ),
  create: (body: unknown) => createResource<LeaseSummary>('/leases', body),
  update: (id: string, body: unknown) => updateResource<LeaseSummary>(`/leases/${id}`, body),
  changeStatus: (id: string, body: unknown) =>
    createResource<LeaseSummary>(`/leases/${id}/status`, body),
}

export const invoiceApi = {
  list: (params: PageParams = {}) => listResource<InvoiceSummary>('/rent-invoices', params),
  detail: (id: string) => getResource<RentInvoiceDetail>(`/rent-invoices/${id}`),
  /** The derived settlement of one invoice. It is not on the register row, and cannot be: it is a fact about the allocations, not a stored column. */
  settlement: (id: string) =>
    getResource<RentInvoiceDetail>(`/rent-invoices/${id}`).then((invoice) => invoice.settlement),
  issue: (body: unknown) => createResource<RentInvoiceDetail>('/rent-invoices', body),
  void: (id: string, body: unknown) =>
    createResource<RentInvoiceDetail>(`/rent-invoices/${id}/void`, body),
  collections: (from?: string, to?: string) =>
    getResource<CollectionsPosition>(
      `/rent-invoices/collections${from || to ? `?from=${from ?? ''}&to=${to ?? ''}` : ''}`,
    ),
}

export const paymentApi = {
  list: (params: PageParams = {}) => listResource<PaymentSummary>('/payments', params),
  detail: (id: string) => getResource<PaymentDetail>(`/payments/${id}`),
  record: (body: unknown) => createResource<PaymentSummary>('/payments', body),
  void: (id: string, body: unknown) => createResource<PaymentSummary>(`/payments/${id}/void`, body),
  collections: (from?: string, to?: string) =>
    getResource<CollectionsSummary>(
      `/payments/collections${from || to ? `?from=${from ?? ''}&to=${to ?? ''}` : ''}`,
    ),
}

export const maintenanceApi = {
  list: (params: PageParams = {}) =>
    listResource<MaintenanceSummaryRow>('/maintenance-requests', params),
  detail: (id: string) => getResource<MaintenanceDetail>(`/maintenance-requests/${id}`),
  summary: () => getResource<MaintenanceSummaryResponse>('/maintenance-requests/summary'),
  costTrend: (from?: string, to?: string) =>
    getResource<MaintenanceCostTrendResponse>(
      `/maintenance-requests/cost-trend${from || to ? `?from=${from ?? ''}&to=${to ?? ''}` : ''}`,
    ),
  create: (body: unknown) => createResource<MaintenanceDetail>('/maintenance-requests', body),
  update: (id: string, body: unknown) =>
    updateResource<MaintenanceDetail>(`/maintenance-requests/${id}`, body),
  assign: (id: string, body: unknown) =>
    createResource<MaintenanceDetail>(`/maintenance-requests/${id}/assign`, body),
  changeStatus: (id: string, body: unknown) =>
    createResource<MaintenanceDetail>(`/maintenance-requests/${id}/status`, body),
  resolve: (id: string, body: unknown) =>
    createResource<MaintenanceDetail>(`/maintenance-requests/${id}/resolve`, body),
}

export const expenseApi = {
  list: (params: PageParams = {}) => listResource<ExpenseSummaryRow>('/expenses', params),
  detail: (id: string) => getResource<ExpenseDetail>(`/expenses/${id}`),
  summary: (from?: string, to?: string) =>
    getResource<ExpenseSummaryResponse>(
      `/expenses/summary${from || to ? `?from=${from ?? ''}&to=${to ?? ''}` : ''}`,
    ),
  create: (body: unknown) => createResource<ExpenseDetail>('/expenses', body),
  update: (id: string, body: unknown) => updateResource<ExpenseDetail>(`/expenses/${id}`, body),
}

export const expenseCategoryApi = {
  /** The expense headings, in the order costs are thought about rather than alphabetically. */
  list: () => getResource<ExpenseCategoryView[]>('/expense-categories'),
}

export const saleApi = {
  list: (params: PageParams = {}) => listResource<SaleSummaryRow>('/sales', params),
  detail: (id: string) => getResource<SaleDetail>(`/sales/${id}`),
  pipeline: () => getResource<SalesPipelineResponse>('/sales/pipeline'),
  summary: () => getResource<SalesSummary>('/sales/summary'),
  create: (body: unknown) => createResource<SaleDetail>('/sales', body),
  update: (id: string, body: unknown) => updateResource<SaleDetail>(`/sales/${id}`, body),
  changeStage: (id: string, body: unknown) => createResource<SaleDetail>(`/sales/${id}/stage`, body),
}

export const offerApi = {
  list: (params: PageParams = {}) => listResource<OfferEntry>('/offers', params),
  /**
   * Decide an offer. The endpoint answers with the **sale** it belongs to: accepting an offer moves the
   * sale's stage in the same write, so the sale - not the offer - is what the caller must re-read.
   */
  decide: (id: string, body: unknown) => patchResource<SaleDetail>(`/offers/${id}`, body),
  /** Record an offer on a sale. The first offer moves the sale out of LEAD, so this answers the sale too. */
  record: (saleId: string, body: unknown) =>
    createResource<SaleDetail>(`/sales/${saleId}/offers`, body),
}

export const portfolioApi = {
  list: (params: PageParams = {}) => listResource<PortfolioSummary>('/portfolios', params),
  detail: (id: string) => getResource<PortfolioDetail>(`/portfolios/${id}`),
  performance: (id: string) =>
    getResource<PortfolioPerformance>(`/portfolios/${id}/performance`),
}

export const documentApi = {
  list: (params: PageParams = {}) => listResource<DocumentSummaryRow>('/documents', params),
  detail: (id: string) => getResource<DocumentDetail>(`/documents/${id}`),
  create: (body: unknown) => createResource<DocumentDetail>('/documents', body),
  /**
   * The expiry watchlist: live documents that lapse inside the window, with the days left counted
   * server-side. It is its own projection - `DocumentDtos.ExpiryEntry` - not a filtered register row.
   */
  expiring: (days?: number) =>
    getResource<DocumentExpiryEntry[]>(`/documents/expiring${days ? `?days=${days}` : ''}`),
}

export const communicationApi = {
  list: (params: PageParams = {}) => listResource<ConversationSummaryRow>('/communications', params),
  detail: (id: string) => getResource<ConversationDetail>(`/communications/${id}`),
  /**
   * The client-facing thread.
   *
   * There is no separate `GET /messages` on the backend: the conversation detail carries its own
   * messages, and internal notes are served from a different path entirely so they can never be
   * returned to a client-facing read by accident.
   */
  messages: async (id: string): Promise<MessageSummary[]> => {
    const detail = await getResource<ConversationDetail>(`/communications/${id}`)
    return detail.messages
  },
  /** Internal notes, kept on their own endpoint precisely so this read is never accidental. */
  internalMessages: (id: string) =>
    getResource<MessageSummary[]>(`/communications/${id}/messages/internal`),
  postMessage: (id: string, body: unknown) =>
    createResource<MessageSummary>(`/communications/${id}/messages`, body),
  markRead: (id: string) =>
    createResource<ConversationSummaryRow>(`/communications/${id}/read`, {}),
  /** The badge. `CommunicationDtos.UnreadCount` names the figure `unreadMessages`. */
  unreadCount: () => getResource<ConversationUnreadCount>('/communications/unread-count'),
}

export const notificationApi = {
  list: (params: PageParams = {}) => listResource<NotificationRow>('/notifications', params),
  /** The badge. `NotificationDtos.UnreadCount` names the figure `unread`. */
  unreadCount: () => getResource<NotificationUnreadCount>('/notifications/unread-count'),
  markRead: (id: string) => createResource<NotificationRow>(`/notifications/${id}/read`, {}),
  markAllRead: () => createResource<NotificationUnreadCount>('/notifications/read', {}),
}

export const reportApi = {
  /**
   * Reports are a paged read per report, so one caller can render any of them.
   *
   * A report's rows are `Map<String, Object>` on the wire and its columns are declared by the report
   * itself, so there is no single record to name here - the screen reads the response's own `columns`
   * and `rows`, and checks the shape before it draws anything.
   */
  run: (report: string, params: PageParams = {}) =>
    listResource<Record<string, unknown>>(`/reports/${report}`, params),
  export: (report: string, params: PageParams, filename: string) =>
    downloadCsv(`/reports/${report}/export`, params, filename),
}

export const importApi = {
  preview: (formData: FormData) =>
    apiFetch<Record<string, unknown>>('/imports/preview', { method: 'POST', formData }),
  confirm: (jobCode: string, body: unknown) =>
    createResource<Record<string, unknown>>(`/imports/${jobCode}/confirm`, body),
  list: (params: PageParams = {}) => listResource<Record<string, unknown>>('/imports', params),
  detail: (jobCode: string) => getResource<Record<string, unknown>>(`/imports/${jobCode}`),
}

export const auditApi = {
  list: (params: PageParams = {}) => listResource<AuditRow>('/audit', params),
}

export const referenceApi = {
  propertyTypes: () => getResource<PropertyTypeView[]>('/property-types'),
  unitTypes: () => getResource<UnitTypeView[]>('/unit-types'),
  amenities: () => getResource<AmenityView[]>('/amenities'),
}
