/**
 * The payload types this frontend consumes.
 *
 * Every shape here mirrors a real backend record - read from
 * `com.meridian.realestate.dto.*`. Nothing is invented: a field that is not in a DTO is not in these
 * types, because a plausible-looking field the API never sends is how a screen ends up rendering
 * blanks that look like data.
 *
 * **How a field is spelled.** The name is the DTO's own component name, character for character:
 * `propertyTypeName`, not `propertyType`; `currentValuation`, not `valuation`; `unitNumber`, not
 * `unitCode`. A rename in a Java record is a rename here.
 *
 * **How a field is made nullable.** A Java record cannot state nullability, so the rule is taken from
 * the database underneath it: a column the schema declares `NOT NULL` is a `string`/`number` here, and
 * a column that may be absent - or a value the service leaves out because there is nothing honest to
 * say - is `| null`. Nothing is optional: a field the DTO sends is always present on the wire, so the
 * type says so.
 *
 * **What a register row is, and what it is not.** A list endpoint answers with its `…Summary`
 * projection, which is deliberately narrow: it carries what a reader scans, not everything the record
 * holds. The fuller `…Detail` projection - with its counts, sub-lists and history - is a second
 * record, read from the detail endpoint for one row at a time. A figure that only exists on the
 * detail therefore cannot be a column on the register, and is not modelled as one.
 */

/** The envelope every success is wrapped in. */
export interface ApiEnvelope<T> {
  success: boolean
  data: T
  message: string | null
}

/** The envelope every failure is wrapped in. */
export interface ApiFailure {
  success: false
  error: { code: string; message: string; details: string[] }
}

/** The shape every paged endpoint returns. */
export interface PageResponse<T> {
  items: T[]
  page: number
  size: number
  totalElements: number
  totalPages: number
  hasNext: boolean
}

// ---------------------------------------------------------------- identity

export interface AuthTokens {
  accessToken: string
  refreshToken: string
  tokenType: string
  expiresInSeconds: number
}

export interface CurrentUser {
  id: string
  staffCode: string
  firstName: string
  lastName: string
  email: string
  phone: string | null
  department: string | null
  designation: string | null
  status: string
  forcePasswordReset: boolean
  roles: string[]
  permissions: string[]
}

// ---------------------------------------------------------------- platform

export interface ProductView {
  code: string
  name: string
  version: string
}

export interface IndustryView {
  code: string
  name: string
}

export interface NavigationEntryView {
  key: string
  label: string
  to: string
  iconKey: string | null
  group: string | null
  permission: string | null
  displayOrder: number
}

export interface BrandThemeView {
  productName: string
  brandName: string
  companyName: string
  logoUrl: string | null
  faviconUrl: string | null
  loginHeadline: string
  loginSubheadline: string
  sidebarBrandLabel: string
  footerText: string
  primaryColor: string | null
  primarySoftColor: string | null
  secondaryColor: string | null
  colorScheme: string
}

export interface FirmProfileView {
  name: string
  code: string
  email: string
  phone: string
  address: string
  licence: string
}

export interface PolicyThresholdsView {
  leaseExpiryWarningDays: number
  leaseExpiryUrgentDays: number
  rentDueDay: number
  rentOverdueGraceDays: number
  occupancyTargetPercent: number
  listingStaleDays: number
  viewingReminderHours: number
  maintenanceUrgentHours: number
  trendMonths: number
  importMaxFileSizeMb: number
  importMaxRows: number
  currency: string
  currencySymbol: string
}

export interface PlatformProfile {
  product: ProductView
  industry: IndustryView
  terminology: Record<string, string>
  terminologyKeys: string[]
  enabledModules: string[]
  navigation: NavigationEntryView[]
  iconKeys: string[]
  theme: BrandThemeView
  firm: FirmProfileView
  policies: PolicyThresholdsView
}

// ---------------------------------------------------------------- dashboard

/** `DashboardDtos.PortfolioSummary` - the portfolio header, every ratio with its parts. */
export interface DashboardPortfolio {
  propertyCount: number
  unitCount: number
  leasableUnits: number
  occupiedUnits: number
  occupancyPercent: number
  occupancyTargetPercent: number
  totalValuation: number
  totalAcquisitionValue: number
  monthlyRentRoll: number
  outstandingRent: number
  outstandingInvoiceCount: number
  overdueRent: number
  overdueInvoiceCount: number
  invoicedThisPeriod: number
  collectedThisPeriod: number
  operatingExpensesThisPeriod: number
  capitalExpensesThisPeriod: number
  netOperatingIncome: number
  annualisedYieldPercent: number
}

/** `DashboardDtos.UnitStateCount` - one bar of the unit-state distribution. */
export interface UnitStateCount {
  status: string
  count: number
}

/** `DashboardDtos.TrendPoint` - one month of the portfolio trend. */
export interface TrendPoint {
  month: string
  invoiced: number
  collected: number
  outstanding: number
  operatingExpenses: number
  netOperatingIncome: number
}

/**
 * `DashboardDtos.PropertyPerformance` - one property's standing, for the ranking.
 *
 * The DTO carries no type, no status and no coordinate: the ranking list is a comparison of stock,
 * occupancy, rent and value, and the map draws from `propertyApi.map()` instead, whose markers are
 * the only rows the backend will plot.
 */
export interface PropertyPerformanceRow {
  propertyId: string
  propertyCode: string
  name: string
  city: string | null
  valuation: number | null
  monthlyRent: number | null
  leasableUnits: number
  occupiedUnits: number
  occupancyPercent: number
}

/** `DashboardDtos.StageCount` - one rung of a pipeline: a stage and how many records sit in it. */
export interface StageCountRow {
  stage: string
  count: number
}

/** `DashboardDtos.StatusCount` - one bar of a status chart. */
export interface StatusCountRow {
  status: string
  count: number
}

/** `DashboardDtos.PriorityCount` - one bar of the maintenance priority chart. */
export interface PriorityCountRow {
  priority: string
  count: number
}

/** `DashboardDtos.MonthlyAmount` - one month of a simple amount series. */
export interface MonthlyAmountRow {
  month: string
  amount: number
}

/** `DashboardDtos.LeadKpi` - the lead funnel and the value still being worked. */
export interface DashboardLeadKpi {
  total: number
  openCount: number
  openPipelineValue: number
  funnel: StageCountRow[]
}

/** `DashboardDtos.LeaseAttention` - the leases that need a decision. Counts only; the rows are `leaseApi.expiring()`. */
export interface DashboardLeaseAttention {
  runningLeases: number
  expiringWithinWarningDays: number
  expiringWithinUrgentDays: number
  renewalCandidates: number
  alreadyExpired: number
}

/** `DashboardDtos.MaintenanceKpi` - the state of the stock's condition. */
export interface DashboardMaintenanceKpi {
  openCount: number
  urgentOpenCount: number
  costThisPeriod: number
  byPriority: PriorityCountRow[]
  costTrend: MonthlyAmountRow[]
}

/** `DashboardDtos.SalesKpi` - the sales pipeline. */
export interface DashboardSalesKpi {
  total: number
  openPipelineValue: number
  byStage: StageCountRow[]
}

/** `DashboardDtos.ActivityEntry` - one row of the recent-activity rail. */
export interface ActivityRow {
  id: string
  module: string
  action: string
  severity: string
  description: string | null
  actorName: string | null
  entityType: string | null
  entityLabel: string | null
  createdAt: string
}

/** `DashboardDtos.Summary` - the whole dashboard, for one as-of date. */
export interface DashboardSummary {
  asOf: string
  generatedAt: string
  currency: string
  currencySymbol: string
  portfolio: DashboardPortfolio
  unitStates: UnitStateCount[]
  trend: TrendPoint[]
  topPropertiesByValuation: PropertyPerformanceRow[]
  topPropertiesByRent: PropertyPerformanceRow[]
  leads: DashboardLeadKpi
  listings: {
    total: number
    live: number
    stale: number
    byStatus: StatusCountRow[]
  }
  viewings: {
    total: number
    scheduled: number
    confirmed: number
    completed: number
    cancelled: number
    noShow: number
    conversionPercent: number
  }
  applications: {
    total: number
    openCount: number
    byStatus: StatusCountRow[]
  }
  sales: DashboardSalesKpi
  leases: DashboardLeaseAttention
  maintenance: DashboardMaintenanceKpi
  recentActivity: ActivityRow[]
}

// ---------------------------------------------------------------- properties

/** `PropertyDtos.PropertySummary` - one row of the register. */
export interface PropertySummary {
  id: string
  propertyCode: string
  name: string
  propertyTypeId: string | null
  propertyTypeCode: string | null
  propertyTypeName: string | null
  status: string
  city: string
  locality: string | null
  state: string | null
  country: string | null
  currentValuation: number | null
  acquisitionValue: number | null
  /** True when the property carries a complete coordinate pair. `latitude`/`longitude` are not sent. */
  positioned: boolean
  createdAt: string
  updatedAt: string
}

/** `PropertyDtos.PropertyDetail` - one property's full record, with the counts the property page shows. */
export interface PropertyDetail {
  id: string
  propertyCode: string
  name: string
  propertyTypeId: string | null
  propertyTypeCode: string | null
  propertyTypeName: string | null
  propertyTypeLeasable: boolean
  status: string
  addressLine1: string
  addressLine2: string | null
  locality: string | null
  city: string
  state: string | null
  postalCode: string | null
  country: string | null
  latitude: number | null
  longitude: number | null
  positioned: boolean
  description: string | null
  yearBuilt: number | null
  landAreaSqft: number | null
  builtAreaSqft: number | null
  acquisitionValue: number | null
  currentValuation: number | null
  valuationDate: string | null
  buildingCount: number
  unitCount: number
  documentCount: number
  mediaCount: number
  createdAt: string
  updatedAt: string
}

/**
 * `PropertyDtos.PropertyMetrics` - the property's operational metrics, from `/properties/{id}/metrics`.
 *
 * This is where a property's unit counts, occupancy and rent roll actually live. The register row
 * (`PropertySummary`) carries none of them, so a screen that wants them reads this projection once for
 * the property on screen rather than guessing at a column the list endpoint cannot populate.
 */
export interface PropertyMetrics {
  propertyId: string
  propertyCode: string
  unitCount: number
  leasableUnits: number
  occupiedUnits: number
  occupancyPercent: number
  monthlyRentRoll: number
  currentValuation: number | null
  acquisitionValue: number | null
  landAreaSqft: number | null
  builtAreaSqft: number | null
}

/** `PropertyDtos.MapMarker` - one marker on the portfolio map. Only positioned properties appear. */
export interface MapMarker {
  id: string
  propertyCode: string
  name: string
  city: string | null
  status: string
  latitude: number
  longitude: number
  currentValuation: number | null
  occupancyPercent: number
}

/** `PropertyDtos.AmenityLink` - a facility offered by a property, with its per-property note. */
export interface AmenityLink {
  amenityId: string
  code: string
  name: string
  category: string | null
  notes: string | null
}

/** `PropertyDtos.OwnershipShare` - one owner's holding in a property, as read from the property. */
export interface PropertyOwnershipShare {
  ownershipId: string
  ownerId: string | null
  ownerCode: string | null
  ownerName: string | null
  percentage: number
  relationship: string
  effectiveFrom: string
  effectiveTo: string | null
  open: boolean
}

/** `PropertyDtos.StatusHistoryEntry` - one status transition the property has made. */
export interface PropertyStatusHistoryEntry {
  id: string
  fromStatus: string | null
  toStatus: string | null
  reason: string | null
  changedBy: string | null
  changedAt: string | null
}

// ---------------------------------------------------------------- units

/** `UnitDtos.UnitSummary` - one row of the unit register. */
export interface UnitSummary {
  id: string
  unitCode: string
  unitNumber: string
  propertyId: string
  propertyName: string
  buildingId: string | null
  buildingCode: string | null
  buildingName: string | null
  unitTypeId: string
  unitTypeCode: string | null
  unitTypeName: string | null
  floorNumber: number
  floorLabel: string | null
  bedrooms: number
  bathrooms: number
  balconies: number
  parkingSlots: number
  furnishing: string | null
  facing: string | null
  status: string
  leasable: boolean
  askingRent: number | null
  askingPrice: number | null
}

/** `UnitDtos.UnitDetail` - one unit's full record, including the lease currently in force on it. */
export interface UnitDetail extends UnitSummary {
  carpetAreaSqft: number | null
  builtUpAreaSqft: number | null
  currentLease: UnitLeaseSummary | null
  createdAt: string
  updatedAt: string
}

/** `UnitDtos.UnitLeaseSummary` - the lease in force on a unit, as far as the unit module may see it. */
export interface UnitLeaseSummary {
  unitId: string | null
  let: boolean
  leaseId: string | null
  leaseCode: string | null
  status: string | null
  startDate: string | null
  endDate: string | null
  rentAmount: number | null
  rentFrequency: string | null
  tenantId: string | null
  tenantName: string | null
}

/** `UnitDtos.UnitBoard` - a property's units grouped by building and then by floor. */
export interface UnitBoard {
  propertyId: string
  propertyCode: string
  buildings: BuildingGroup[]
}

/** `UnitDtos.BuildingGroup` - the blocks of a property. A unit in no block groups under a null id. */
export interface BuildingGroup {
  buildingId: string | null
  buildingCode: string | null
  buildingName: string | null
  floors: FloorGroup[]
}

/** `UnitDtos.FloorGroup` - one floor of one block, ordered as a person walks it. */
export interface FloorGroup {
  floorNumber: number
  floorLabel: string | null
  units: UnitCard[]
}

/** `UnitDtos.UnitCard` - one tile on the unit board. */
export interface UnitCard {
  id: string
  unitCode: string
  unitNumber: string
  buildingId: string | null
  unitTypeCode: string | null
  unitTypeName: string | null
  floorNumber: number
  floorLabel: string | null
  bedrooms: number
  status: string
  leasable: boolean
  occupied: boolean
  askingRent: number | null
}

/** `UnitDtos.UnitOccupancy` - the occupancy figures for one property. */
export interface UnitOccupancy {
  propertyId: string
  totalUnits: number
  leasableUnits: number
  occupiedUnits: number
  availableUnits: number
  reservedUnits: number
  underMaintenanceUnits: number
  notLettableUnits: number
  occupancyPercent: number
}

// ---------------------------------------------------------------- owners

/** `OwnerDtos.OwnerSummary` - one row of the owner register. */
export interface OwnerSummary {
  id: string
  ownerCode: string
  ownerType: string
  displayName: string
  primaryContactName: string | null
  email: string | null
  phone: string | null
  city: string | null
  state: string | null
  status: string
  createdAt: string
  updatedAt: string
}

/** `OwnerDtos.OwnerDetail` - one owner's full record, with every holding they carry. */
export interface OwnerDetail {
  id: string
  ownerCode: string
  ownerType: string
  displayName: string
  primaryContactName: string | null
  email: string | null
  phone: string | null
  alternatePhone: string | null
  idReference: string | null
  taxReference: string | null
  addressLine1: string | null
  addressLine2: string | null
  locality: string | null
  city: string | null
  state: string | null
  postalCode: string | null
  country: string | null
  status: string
  notes: string | null
  holdingCount: number
  openHoldingCount: number
  holdings: OwnershipShare[]
  createdAt: string
  updatedAt: string
}

/** `OwnerDtos.OwnershipShare` - one owner's holding in one property, for a period, with a share. */
export interface OwnershipShare {
  ownershipId: string
  propertyId: string | null
  propertyCode: string | null
  propertyName: string | null
  ownerId: string | null
  ownerCode: string | null
  ownerName: string | null
  percentage: number
  relationship: string
  effectiveFrom: string
  effectiveTo: string | null
  open: boolean
  notes: string | null
}

// ---------------------------------------------------------------- tenants

/** `TenantDtos.TenantSummary` - one row of the tenant register. */
export interface TenantSummary {
  id: string
  tenantCode: string
  tenantType: string
  displayName: string
  primaryContactName: string | null
  email: string | null
  phone: string | null
  city: string | null
  state: string | null
  status: string
  createdAt: string
  updatedAt: string
}

/** `TenantDtos.TenantDetail` - one tenant's full record, with the leases, receipts and faults behind it. */
export interface TenantDetail {
  id: string
  tenantCode: string
  tenantType: string
  displayName: string
  primaryContactName: string | null
  email: string | null
  phone: string | null
  alternatePhone: string | null
  idReference: string | null
  taxReference: string | null
  registrationNumber: string | null
  occupation: string | null
  employer: string | null
  annualIncome: number | null
  addressLine1: string | null
  addressLine2: string | null
  locality: string | null
  city: string | null
  state: string | null
  postalCode: string | null
  country: string | null
  status: string
  notes: string | null
  runningLease: boolean
  leases: TenantLeaseProjection[]
  payments: TenantPaymentProjection[]
  maintenance: TenantMaintenanceProjection[]
  createdAt: string
  updatedAt: string
}

/** `TenantDtos.LeaseProjection` - a lease this tenant is party to. */
export interface TenantLeaseProjection {
  leaseId: string
  leaseCode: string
  status: string
  startDate: string
  endDate: string
  rentAmount: number
  rentFrequency: string | null
  unitId: string | null
  propertyId: string | null
  propertyName: string | null
  running: boolean
}

/** `TenantDtos.PaymentProjection` - a receipt from this tenant. */
export interface TenantPaymentProjection {
  paymentId: string
  receiptNumber: string
  amount: number
  currency: string
  status: string
  direction: string
  receivedAt: string
}

/** `TenantDtos.MaintenanceProjection` - a fault reported by this tenant. */
export interface TenantMaintenanceProjection {
  requestId: string
  requestCode: string
  title: string | null
  category: string
  priority: string
  status: string
  reportedAt: string
  open: boolean
}

// ---------------------------------------------------------------- buyers

/**
 * `BuyerDtos.BuyerSummary` - one row of the buyer register.
 *
 * The budget is deliberately absent: what a buyer will pay is a *brief*, not a column on the buyer,
 * so it lives on `RequirementRecord` and is read from the buyer's detail.
 */
export interface BuyerSummary {
  id: string
  buyerCode: string
  buyerType: string
  displayName: string
  primaryContactName: string | null
  email: string | null
  phone: string | null
  city: string | null
  state: string | null
  status: string
  createdAt: string
  updatedAt: string
}

/** `BuyerDtos.BuyerDetail` - one buyer's full record, with their briefs, viewings and offers. */
export interface BuyerDetail {
  id: string
  buyerCode: string
  buyerType: string
  displayName: string
  primaryContactName: string | null
  email: string | null
  phone: string | null
  alternatePhone: string | null
  idReference: string | null
  taxReference: string | null
  addressLine1: string | null
  addressLine2: string | null
  locality: string | null
  city: string | null
  state: string | null
  postalCode: string | null
  country: string | null
  status: string
  notes: string | null
  requirementCount: number
  activeRequirementCount: number
  requirements: RequirementRecord[]
  viewings: BuyerViewingRecord[]
  offers: BuyerOfferRecord[]
  createdAt: string
  updatedAt: string
}

/** `BuyerDtos.RequirementRecord` - one brief: what the buyer wants, where, and for how much. */
export interface RequirementRecord {
  requirementId: string
  propertyTypeId: string | null
  propertyTypeCode: string | null
  propertyTypeName: string | null
  preferredCity: string | null
  preferredLocality: string | null
  minBudget: number | null
  maxBudget: number | null
  minBedrooms: number | null
  maxBedrooms: number | null
  minAreaSqft: number | null
  purpose: string | null
  active: boolean
  notes: string | null
  createdAt: string
  updatedAt: string
}

/** `BuyerDtos.ViewingRecord` - a viewing this buyer attended or is booked for. */
export interface BuyerViewingRecord {
  viewingId: string
  viewingCode: string
  status: string
  scheduledAt: string
  propertyId: string | null
  propertyName: string | null
  unitId: string | null
  interested: boolean | null
}

/** `BuyerDtos.OfferRecord` - an offer made on one of this buyer's sales. */
export interface BuyerOfferRecord {
  offerId: string
  saleId: string | null
  saleCode: string | null
  offerNumber: string
  amount: number
  status: string
  direction: string
  offeredAt: string
  respondedAt: string | null
}

// ---------------------------------------------------------------- offices

/** `OfficeDtos.OfficeSummary` - one row of the office register. */
export interface OfficeSummary {
  id: string
  officeCode: string
  name: string
  city: string | null
  state: string | null
  phone: string | null
  email: string | null
  headOffice: boolean
  status: string
  createdAt: string
  updatedAt: string
}

/** `OfficeDtos.OfficeDetail` - one office's full record, with the number of agents who sit in it. */
export interface OfficeDetail {
  id: string
  officeCode: string
  name: string
  addressLine1: string | null
  addressLine2: string | null
  locality: string | null
  city: string | null
  state: string | null
  postalCode: string | null
  country: string | null
  phone: string | null
  email: string | null
  headOffice: boolean
  status: string
  agentCount: number
  createdAt: string
  updatedAt: string
}

// ---------------------------------------------------------------- agents

/** `AgentDtos.AgentSummary` - one row of the agent register. */
export interface AgentSummary {
  id: string
  agentCode: string
  userId: string | null
  fullName: string | null
  email: string | null
  phone: string | null
  officeId: string | null
  officeName: string | null
  managerId: string | null
  managerName: string | null
  specialisation: string
  licenceNumber: string | null
  joinedOn: string | null
  monthlyTarget: number | null
  status: string
  createdAt: string
  updatedAt: string
}

/** `AgentDtos.AgentDetail` - one agent's full record, with every assignment they hold. */
export interface AgentDetail {
  id: string
  agentCode: string
  userId: string | null
  fullName: string | null
  email: string | null
  phone: string | null
  officeId: string | null
  officeName: string | null
  managerId: string | null
  managerName: string | null
  specialisation: string
  licenceNumber: string | null
  joinedOn: string | null
  monthlyTarget: number | null
  bio: string | null
  status: string
  assignmentCount: number
  openAssignmentCount: number
  assignments: AssignmentRecord[]
  createdAt: string
  updatedAt: string
}

/** `AgentDtos.AssignmentRecord` - who carries which property right now, and in what capacity. */
export interface AssignmentRecord {
  id: string
  agentId: string | null
  agentName: string | null
  propertyId: string | null
  propertyCode: string | null
  propertyName: string | null
  role: string
  assignedFrom: string
  assignedTo: string | null
  open: boolean
}

/**
 * `AgentDtos.WorkloadResponse` - an agent's current workload, from `/agents/{id}/workload`.
 *
 * The workload is not on the register row: it is four counts read over four other modules, and asking
 * for them beside every row would cost four queries per row. A screen that wants them reads them once
 * per agent on screen.
 */
export interface WorkloadResponse {
  agentId: string
  agentCode: string
  openLeads: number
  activeListings: number
  viewingsNext7Days: number
  openMaintenance: number
}

/** `AgentDtos.PerformanceResponse` - how an agent's book has performed. */
export interface PerformanceResponse {
  agentId: string
  agentCode: string
  totalLeads: number
  leadsConverted: number
  viewingsCompleted: number
  conversionRatePercent: number
  listingsLet: number
}

// ---------------------------------------------------------------- leads

/** `LeadDtos.LeadSummary` - a row in the lead register. */
export interface LeadSummary {
  id: string
  leadCode: string
  displayName: string
  companyName: string | null
  leadType: string
  stage: string
  priority: string
  sourceCode: string | null
  sourceName: string | null
  ownerAgentId: string | null
  ownerAgentName: string | null
  officeId: string | null
  officeName: string | null
  email: string | null
  phone: string | null
  expectedValue: number | null
  nextAction: string | null
  nextActionAt: string | null
  overdue: boolean
  createdAt: string
  updatedAt: string
}

/** `LeadDtos.LeadDetail` - everything the lead screen shows for one lead. */
export interface LeadDetail {
  id: string
  leadCode: string
  displayName: string
  companyName: string | null
  leadType: string
  stage: string
  priority: string
  sourceCode: string | null
  sourceName: string | null
  ownerAgentId: string | null
  ownerAgentName: string | null
  officeId: string | null
  officeName: string | null
  email: string | null
  phone: string | null
  alternatePhone: string | null
  propertyTypeCode: string | null
  preferredCity: string | null
  preferredLocality: string | null
  minBudget: number | null
  maxBudget: number | null
  minBedrooms: number | null
  requirementNotes: string | null
  propertyId: string | null
  unitId: string | null
  nextAction: string | null
  nextActionAt: string | null
  expectedValue: number | null
  wonAt: string | null
  lostAt: string | null
  lostReason: string | null
  convertedAt: string | null
  convertedTenantId: string | null
  convertedBuyerId: string | null
  createdAt: string
  updatedAt: string
  allowedTransitions: string[]
  assignments: LeadAssignmentEntry[]
  activities: LeadActivityEntry[]
  followups: LeadFollowupEntry[]
  viewings: ViewingSummary[]
  applications: ApplicationSummary[]
}

/** `LeadDtos.LeadAssignmentEntry` - one chapter in the history of who held a lead. */
export interface LeadAssignmentEntry {
  id: string
  agentId: string | null
  agentName: string | null
  assignedAt: string | null
  assignedById: string | null
  unassignedAt: string | null
  reason: string | null
}

/** `LeadDtos.LeadActivityEntry` - a piece of work recorded against a lead. */
export interface LeadActivityEntry {
  id: string
  activityType: string
  subject: string | null
  notes: string | null
  occurredAt: string | null
  durationMinutes: number | null
  performedById: string | null
  fromStage: string | null
  toStage: string | null
}

/** `LeadDtos.LeadFollowupEntry` - a planned piece of work against a lead. */
export interface LeadFollowupEntry {
  id: string
  dueAt: string | null
  channel: string | null
  notes: string | null
  status: string
  assignedToId: string | null
  assignedToName: string | null
  completedAt: string | null
  outcome: string | null
  overdue: boolean
}

/** `LeadDtos.StageBucket` - one rung of the pipeline board. */
export interface StageBucket {
  stage: string
  count: number
  value: number | null
  open: boolean
}

/** `LeadDtos.PipelineResponse` - the pipeline: how many leads carry each stage, and what it is worth. */
export interface PipelineResponse {
  totalLeads: number
  openLeads: number
  openPipelineValue: number
  generatedAt: string
  stages: StageBucket[]
}

// ---------------------------------------------------------------- listings

/** `ListingDtos.ListingSummary` - a row in the listing board. */
export interface ListingSummary {
  id: string
  listingCode: string
  propertyId: string | null
  propertyCode: string | null
  propertyName: string | null
  unitId: string | null
  unitNumber: string | null
  listingType: string
  channelCode: string | null
  channelName: string | null
  status: string
  live: boolean
  headline: string | null
  askingRent: number | null
  askingPrice: number | null
  exclusive: boolean
  negotiable: boolean
  listingStart: string | null
  listingEnd: string | null
  listedById: string | null
  listedByName: string | null
  officeId: string | null
  publishedAt: string | null
  closedAt: string | null
  stale: boolean
  createdAt: string
  updatedAt: string
}

/** `ListingDtos.ListingStatusHistoryEntry` - one transition in a listing's publication state. */
export interface ListingStatusHistoryEntry {
  id: string
  fromStatus: string | null
  toStatus: string | null
  reason: string | null
  changedById: string | null
  changedAt: string | null
}

/** `ListingDtos.ListingDetail` - everything the listing screen shows for one listing. */
export interface ListingDetail {
  id: string
  listingCode: string
  propertyId: string | null
  propertyCode: string | null
  propertyName: string | null
  unitId: string | null
  unitNumber: string | null
  listingType: string
  channelCode: string | null
  channelName: string | null
  status: string
  headline: string | null
  description: string | null
  askingRent: number | null
  askingPrice: number | null
  exclusive: boolean
  negotiable: boolean
  listingStart: string | null
  listingEnd: string | null
  publishedAt: string | null
  closedAt: string | null
  closedReason: string | null
  listedById: string | null
  listedByName: string | null
  officeId: string | null
  stale: boolean
  createdAt: string
  updatedAt: string
  allowedTransitions: string[]
  statusHistory: ListingStatusHistoryEntry[]
  viewings: ViewingSummary[]
  applications: ApplicationSummary[]
}

// ---------------------------------------------------------------- viewings

/** `ViewingDtos.ViewingSummary` - a row in the viewing diary. The unit is `unitNumber`. */
export interface ViewingSummary {
  id: string
  viewingCode: string
  propertyId: string | null
  propertyCode: string | null
  propertyName: string | null
  unitId: string | null
  unitNumber: string | null
  leadId: string | null
  leadName: string | null
  buyerId: string | null
  buyerName: string | null
  tenantId: string | null
  tenantName: string | null
  prospectName: string | null
  prospectPhone: string | null
  agentId: string | null
  agentName: string | null
  scheduledAt: string
  durationMinutes: number
  status: string
  rating: number | null
  interested: boolean | null
  completedAt: string | null
  createdAt: string
}

/** `ViewingDtos.ViewingStatusHistoryEntry` - one transition in a viewing's state. */
export interface ViewingStatusHistoryEntry {
  id: string
  fromStatus: string | null
  toStatus: string | null
  reason: string | null
  changedById: string | null
  changedAt: string | null
}

/** `ViewingDtos.ViewingDetail` - everything the viewing screen shows for one appointment. */
export interface ViewingDetail {
  id: string
  viewingCode: string
  propertyId: string | null
  propertyCode: string | null
  propertyName: string | null
  unitId: string | null
  unitNumber: string | null
  leadId: string | null
  leadName: string | null
  buyerId: string | null
  buyerName: string | null
  tenantId: string | null
  tenantName: string | null
  prospectName: string | null
  prospectPhone: string | null
  prospectEmail: string | null
  agentId: string | null
  agentName: string | null
  scheduledAt: string
  durationMinutes: number
  status: string
  feedback: string | null
  rating: number | null
  interested: boolean | null
  cancelledReason: string | null
  completedAt: string | null
  createdAt: string
  updatedAt: string
  allowedTransitions: string[]
  statusHistory: ViewingStatusHistoryEntry[]
}

// ---------------------------------------------------------------- applications

/** `ApplicationDtos.ApplicationSummary` - a row in the applications register. The unit is `unitNumber`. */
export interface ApplicationSummary {
  id: string
  applicationCode: string
  propertyId: string | null
  propertyCode: string | null
  propertyName: string | null
  unitId: string | null
  unitNumber: string | null
  leadId: string | null
  leadName: string | null
  tenantId: string | null
  applicantType: string | null
  applicantName: string
  applicantEmail: string | null
  applicantPhone: string | null
  status: string
  proposedStartDate: string | null
  proposedEndDate: string | null
  proposedRent: number | null
  proposedDeposit: number | null
  outstandingChecks: number
  submittedAt: string | null
  decidedAt: string | null
  createdAt: string | null
}

/** `ApplicationDtos.ScreeningEntry` - one screening check run on an application. */
export interface ScreeningEntry {
  id: string
  checkType: string | null
  status: string | null
  score: number | null
  notes: string | null
  evidenceReference: string | null
  performedById: string | null
  performedAt: string | null
}

/** `ApplicationDtos.ApplicationStatusHistoryEntry` - one transition in an application's state. */
export interface ApplicationStatusHistoryEntry {
  id: string
  fromStatus: string | null
  toStatus: string | null
  reason: string | null
  changedById: string | null
  changedAt: string | null
}

/** `ApplicationDtos.ApplicationDetail` - everything the application screen shows for one application. */
export interface ApplicationDetail {
  id: string
  applicationCode: string
  propertyId: string | null
  propertyCode: string | null
  propertyName: string | null
  unitId: string | null
  unitNumber: string | null
  leadId: string | null
  leadName: string | null
  tenantId: string | null
  applicantType: string | null
  applicantName: string
  applicantEmail: string | null
  applicantPhone: string | null
  status: string
  proposedStartDate: string | null
  proposedEndDate: string | null
  proposedRent: number | null
  proposedDeposit: number | null
  occupancyCount: number | null
  hasPets: boolean
  employmentType: string | null
  annualIncome: number | null
  notes: string | null
  submittedAt: string | null
  decidedAt: string | null
  decidedById: string | null
  decisionNotes: string | null
  rejectionReason: string | null
  createdAt: string | null
  updatedAt: string | null
  outstandingChecks: number
  allowedTransitions: string[]
  screenings: ScreeningEntry[]
  statusHistory: ApplicationStatusHistoryEntry[]
}

// ---------------------------------------------------------------- leases

/**
 * `LeaseDtos.LeaseSummary` - a row in the lease register.
 *
 * The unit's *number* is not sent: the row names the unit by id only, so a register cannot render a
 * unit code, and the days remaining are not stored either - they are a countdown the reader's clock
 * answers from `endDate`. `LeaseDtos.ExpiringLease` is the read that carries `daysUntilEnd`.
 */
export interface LeaseSummary {
  id: string
  leaseCode: string
  status: string
  propertyId: string | null
  propertyName: string | null
  unitId: string | null
  tenantId: string | null
  tenantName: string | null
  startDate: string | null
  endDate: string | null
  rentAmount: number | null
  rentFrequency: string | null
  dueDay: number
  depositAmount: number | null
  createdAt: string | null
}

/** `LeaseDtos.LeaseTermView` - one step in a lease's rent schedule. */
export interface LeaseTermView {
  id: string
  termNumber: number
  effectiveFrom: string | null
  effectiveTo: string | null
  rentAmount: number | null
  escalationPercent: number | null
  notes: string | null
}

/** `LeaseDtos.StatusChangeView` - one recorded status transition. */
export interface LeaseStatusChangeView {
  id: string
  fromStatus: string | null
  toStatus: string | null
  reason: string | null
  changedBy: string | null
  changedAt: string | null
}

/** `LeaseDtos.RenewalView` - a renewal conversation and its outcome. */
export interface RenewalView {
  id: string
  leaseId: string | null
  renewedLeaseId: string | null
  status: string | null
  proposedAt: string | null
  proposedRent: number | null
  proposedStartDate: string | null
  proposedEndDate: string | null
  decidedAt: string | null
  decidedBy: string | null
  decisionNotes: string | null
}

/** `LeaseDtos.LeaseFinance` - the lease's money position, every figure derived from the ledger. */
export interface LeaseFinance {
  invoiced: number
  settled: number
  outstanding: number
  overdue: number
  invoiceCount: number
  overdueCount: number
  receiptCount: number
}

/** `LeaseDtos.LeaseDetail` - everything the lease detail screen reads. */
export interface LeaseDetail {
  id: string
  leaseCode: string
  status: string
  propertyId: string | null
  propertyName: string | null
  unitId: string | null
  tenantId: string | null
  tenantName: string | null
  primaryOwnerId: string | null
  startDate: string | null
  endDate: string | null
  rentAmount: number | null
  rentFrequency: string | null
  dueDay: number
  escalationPercent: number | null
  depositAmount: number | null
  depositReceivedAt: string | null
  depositRefundedAmount: number | null
  depositRefundedAt: string | null
  noticePeriodDays: number
  agreementReference: string | null
  signedAt: string | null
  activatedAt: string | null
  terminatedAt: string | null
  terminationReason: string | null
  renewalOfLeaseId: string | null
  createdAt: string | null
  updatedAt: string | null
  terms: LeaseTermView[]
  statusHistory: LeaseStatusChangeView[]
  renewals: RenewalView[]
  finance: LeaseFinance | null
}

/** `LeaseDtos.ExpiringLease` - one lease due to end inside the configured expiry window. */
export interface ExpiringLease {
  id: string
  leaseCode: string
  status: string
  propertyId: string | null
  propertyName: string | null
  unitId: string | null
  tenantId: string | null
  tenantName: string | null
  endDate: string
  daysUntilEnd: number
  urgency: string
  rentAmount: number | null
}

// ---------------------------------------------------------------- rent invoices

/**
 * `RentInvoiceDtos.RentInvoiceSummary` - a row in the invoice register.
 *
 * Settlement is deliberately absent: nothing about whether an invoice has been paid is stored, so
 * `settled`, `outstanding` and `overdue` travel on `Settlement` on the invoice *detail*, derived from
 * the allocations posted against it. The register row cannot carry them, and per-row they would cost a
 * request each.
 */
export interface InvoiceSummary {
  id: string
  invoiceNumber: string
  leaseId: string | null
  leaseCode: string | null
  tenantId: string | null
  propertyId: string | null
  unitId: string | null
  periodStart: string
  periodEnd: string
  dueDate: string
  subtotal: number | null
  taxAmount: number | null
  totalAmount: number | null
  creditedAmount: number | null
  status: string
  issuedAt: string | null
  createdAt: string | null
}

/** `RentInvoiceDtos.InvoiceLineView` - one line of an invoice. A line may be negative. */
export interface InvoiceLineView {
  id: string
  lineType: string
  description: string
  quantity: number | null
  unitAmount: number
  lineTotal: number
  taxRatePercent: number | null
  taxAmount: number | null
  displayOrder: number
}

/** `RentInvoiceDtos.AllocationView` - one allocation posted against an invoice. */
export interface AllocationView {
  id: string
  paymentId: string | null
  receiptNumber: string | null
  amount: number
  allocatedAt: string | null
  allocatedBy: string | null
}

/** `RentInvoiceDtos.Settlement` - the derived settlement of an invoice. */
export interface Settlement {
  totalAmount: number
  creditedAmount: number
  settled: number
  outstanding: number
  overdue: boolean
  collectable: boolean
}

/** `RentInvoiceDtos.RentInvoiceDetail` - everything the invoice detail screen reads. */
export interface RentInvoiceDetail {
  id: string
  invoiceNumber: string
  leaseId: string | null
  leaseCode: string | null
  tenantId: string | null
  propertyId: string | null
  unitId: string | null
  periodStart: string
  periodEnd: string
  dueDate: string
  subtotal: number | null
  taxAmount: number | null
  totalAmount: number | null
  creditedAmount: number | null
  status: string
  issuedAt: string | null
  voidedAt: string | null
  voidedBy: string | null
  voidReason: string | null
  writtenOffAt: string | null
  writtenOffBy: string | null
  writeOffReason: string | null
  notes: string | null
  settlement: Settlement | null
  lines: InvoiceLineView[]
  allocations: AllocationView[]
}

/** `RentInvoiceDtos.CollectionsPosition` - the collections position over a period. */
export interface CollectionsPosition {
  from: string | null
  to: string | null
  invoiced: number
  collected: number
  outstanding: number
  overdue: number
  overdueCount: number
}

// ---------------------------------------------------------------- payments

/**
 * `PaymentDtos.PaymentSummary` - a row in the payment ledger.
 *
 * The party a receipt settles is named by id, not by name: `tenantName`, `buyerName` and `leaseCode`
 * are not on the row. What the row does carry is the method, resolved to its code and name.
 */
export interface PaymentSummary {
  id: string
  receiptNumber: string
  direction: string
  refundOfPaymentId: string | null
  status: string
  amount: number
  currency: string
  paymentMethodId: string | null
  paymentMethodCode: string | null
  paymentMethodName: string | null
  tenantId: string | null
  buyerId: string | null
  leaseId: string | null
  saleId: string | null
  propertyId: string | null
  receivedAt: string
  reference: string | null
  notes: string | null
  recordedBy: string | null
  voidedAt: string | null
  voidedBy: string | null
  voidReason: string | null
  createdAt: string
}

/** `PaymentDtos.AllocationEntry` - one invoice a receipt has been applied to. */
export interface AllocationEntry {
  id: string
  invoiceId: string | null
  invoiceNumber: string | null
  amount: number
  allocatedAt: string | null
  allocatedBy: string | null
}

/** `PaymentDtos.PaymentDetail` - a receipt with its allocations and what is still unapplied. */
export interface PaymentDetail {
  payment: PaymentSummary
  allocations: AllocationEntry[]
  allocated: number
  unallocated: number
}

/** `PaymentDtos.CollectionsSummary` - the firm's collections over a period. */
export interface CollectionsSummary {
  from: string | null
  to: string | null
  collected: number
  refunded: number
  netCollected: number
  receiptCount: number
  refundCount: number
}

// ---------------------------------------------------------------- maintenance

/**
 * `MaintenanceDtos.MaintenanceSummary` - a row on the maintenance board.
 *
 * The reporter is `reporterName`: the schema records a name, not a tenant link, because a fault is
 * routinely reported by a watchman or a neighbour rather than by the tenant on the lease.
 */
export interface MaintenanceSummaryRow {
  id: string
  requestCode: string
  propertyId: string | null
  propertyCode: string | null
  propertyName: string | null
  unitId: string | null
  unitCode: string | null
  buildingId: string | null
  category: string
  priority: string
  status: string
  title: string
  reporterName: string | null
  assignedVendorId: string | null
  assignedVendorName: string | null
  assignedToId: string | null
  assignedToName: string | null
  reportedAt: string
  targetDate: string | null
  assignedAt: string | null
  startedAt: string | null
  resolvedAt: string | null
  closedAt: string | null
  estimatedCost: number | null
  actualCost: number | null
  chargeableToTenant: boolean
  late: boolean
  overdue: boolean
  open: boolean
  createdAt: string
  updatedAt: string
}

/** `MaintenanceDtos.StatusHistoryEntry` - one recorded transition in a request's status. */
export interface MaintenanceStatusHistoryEntry {
  id: string
  fromStatus: string | null
  toStatus: string | null
  reason: string | null
  changedById: string | null
  changedAt: string | null
}

/** `MaintenanceDtos.MaintenanceDetail` - everything the fault screen shows for one request. */
export interface MaintenanceDetail {
  id: string
  requestCode: string
  propertyId: string | null
  propertyCode: string | null
  propertyName: string | null
  unitId: string | null
  unitCode: string | null
  buildingId: string | null
  category: string
  priority: string
  status: string
  title: string
  description: string | null
  reportedById: string | null
  reportedByName: string | null
  reportedByTenantId: string | null
  reporterName: string | null
  assignedVendorId: string | null
  assignedVendorName: string | null
  assignedToId: string | null
  assignedToName: string | null
  reportedAt: string
  targetDate: string | null
  assignedAt: string | null
  startedAt: string | null
  resolvedAt: string | null
  closedAt: string | null
  estimatedCost: number | null
  actualCost: number | null
  resolutionNotes: string | null
  chargeableToTenant: boolean
  late: boolean
  overdue: boolean
  open: boolean
  createdAt: string
  updatedAt: string
  allowedTransitions: string[]
  statusHistory: MaintenanceStatusHistoryEntry[]
}

/** `MaintenanceDtos.StatusBucket` - one column of the board. */
export interface MaintenanceStatusBucket {
  status: string
  label: string
  count: number
  open: boolean
}

/** `MaintenanceDtos.PriorityBucket` - one bar of the priority chart. */
export interface MaintenancePriorityBucket {
  priority: string
  count: number
  urgent: boolean
}

/** `MaintenanceDtos.MaintenanceSummaryResponse` - the operations card. */
export interface MaintenanceSummaryResponse {
  openCount: number
  urgentOpenCount: number
  lateOpenCount: number
  costToDate: number
  estimatedOpenCost: number
  generatedAt: string
  byStatus: MaintenanceStatusBucket[]
  byPriority: MaintenancePriorityBucket[]
}

/** `MaintenanceDtos.CostTrendPoint` - one month of the maintenance cost trend. */
export interface MaintenanceCostTrendPoint {
  year: number
  month: number
  label: string
  actualCost: number
  resolvedCount: number
}

/** `MaintenanceDtos.CostTrendResponse` - what maintenance has cost, month by month. */
export interface MaintenanceCostTrendResponse {
  from: string | null
  to: string | null
  totalCost: number
  resolvedCount: number
  months: MaintenanceCostTrendPoint[]
  generatedAt: string
}

// ---------------------------------------------------------------- expenses

/** `ExpenseDtos.ExpenseSummary` - a row in the expense ledger. */
export interface ExpenseSummaryRow {
  id: string
  expenseCode: string
  propertyId: string | null
  propertyCode: string | null
  propertyName: string | null
  unitId: string | null
  unitCode: string | null
  categoryId: string | null
  categoryCode: string | null
  categoryName: string | null
  categoryGroup: string | null
  capital: boolean
  maintenanceRequestId: string | null
  vendorId: string | null
  vendorName: string | null
  expenseDate: string
  periodStart: string | null
  periodEnd: string | null
  amount: number
  taxAmount: number
  totalAmount: number
  description: string | null
  invoiceReference: string | null
  recoverable: boolean
  recoveredAmount: number
  unrecoveredAmount: number
  recordedById: string | null
  createdAt: string
  updatedAt: string
}

/** `ExpenseDtos.ExpenseDetail` - everything the expense screen shows for one row. */
export interface ExpenseDetail {
  id: string
  expenseCode: string
  propertyId: string | null
  propertyCode: string | null
  propertyName: string | null
  unitId: string | null
  unitCode: string | null
  categoryId: string | null
  categoryCode: string | null
  categoryName: string | null
  categoryGroup: string | null
  capital: boolean
  typicallyRecoverable: boolean
  maintenanceRequestId: string | null
  maintenanceRequestCode: string | null
  vendorId: string | null
  vendorName: string | null
  expenseDate: string
  periodStart: string | null
  periodEnd: string | null
  amount: number
  taxAmount: number
  totalAmount: number
  description: string | null
  invoiceReference: string | null
  recoverable: boolean
  recoveredAmount: number
  unrecoveredAmount: number
  recordedById: string | null
  createdAt: string
  updatedAt: string
}

/** `ExpenseDtos.MonthlyExpenseRow` - one month of the expense trend, operating and capital apart. */
export interface MonthlyExpenseRow {
  year: number
  month: number
  label: string
  operatingTotal: number
  capitalTotal: number
  recoveredTotal: number
}

/** `ExpenseDtos.ExpenseSummaryResponse` - the ledger's headline. */
export interface ExpenseSummaryResponse {
  from: string | null
  to: string | null
  operatingTotal: number
  capitalTotal: number
  recoveredTotal: number
  unrecoveredTotal: number
  totalIncurred: number
  operatingCount: number
  capitalCount: number
  recoverableCount: number
  monthlyTrend: MonthlyExpenseRow[]
  generatedAt: string
}

// ---------------------------------------------------------------- sales and offers

/** `SaleDtos.SaleSummary` - a row on the pipeline board. */
export interface SaleSummaryRow {
  id: string
  saleCode: string
  propertyId: string | null
  propertyCode: string | null
  propertyName: string | null
  unitId: string | null
  unitCode: string | null
  buyerId: string | null
  buyerName: string | null
  leadId: string | null
  stage: string
  askingPrice: number | null
  expectedPrice: number | null
  agreedPrice: number | null
  expectedCloseDate: string | null
  closedAt: string | null
  lostAt: string | null
  lostReason: string | null
  ownerAgentId: string | null
  ownerAgentName: string | null
  officeId: string | null
  officeName: string | null
  open: boolean
  createdAt: string
  updatedAt: string
}

/** `SaleDtos.OfferEntry` - one offer in a negotiation, as it was made. */
export interface OfferEntry {
  id: string
  offerNumber: string
  saleId: string | null
  amount: number
  offeredAt: string
  direction: string
  status: string
  expiresAt: string | null
  respondedAt: string | null
  respondedById: string | null
  respondedByName: string | null
  responseNotes: string | null
  conditions: string | null
}

/** `SaleDtos.StageHistoryEntry` - one recorded transition in a sale's stage. */
export interface SaleStageHistoryEntry {
  id: string
  fromStage: string | null
  toStage: string | null
  reason: string | null
  changedById: string | null
  changedAt: string | null
}

/** `SaleDtos.SaleDetail` - everything the deal screen shows for one sale. */
export interface SaleDetail {
  id: string
  saleCode: string
  propertyId: string | null
  propertyCode: string | null
  propertyName: string | null
  unitId: string | null
  unitCode: string | null
  buyerId: string | null
  buyerName: string | null
  leadId: string | null
  stage: string
  askingPrice: number | null
  expectedPrice: number | null
  agreedPrice: number | null
  expectedCloseDate: string | null
  closedAt: string | null
  lostAt: string | null
  lostReason: string | null
  ownerAgentId: string | null
  ownerAgentName: string | null
  officeId: string | null
  officeName: string | null
  notes: string | null
  open: boolean
  acceptedOfferId: string | null
  totalOffered: number | null
  createdAt: string
  updatedAt: string
  allowedTransitions: string[]
  offers: OfferEntry[]
  stageHistory: SaleStageHistoryEntry[]
}

/** `SaleDtos.StageBucket` - one column of the board. */
export interface SaleStageBucket {
  stage: string
  count: number
  value: number | null
  open: boolean
}

/** `SaleDtos.PipelineResponse` - how many deals sit in each stage and what the open pipeline is worth. */
export interface SalesPipelineResponse {
  totalSales: number
  openSales: number
  openPipelineValue: number
  generatedAt: string
  stages: SaleStageBucket[]
}

/** `SaleDtos.SalesSummary` - the sales card: the pipeline, and what has actually completed. */
export interface SalesSummary {
  totalSales: number
  openSales: number
  closedSales: number
  lostSales: number
  openPipelineValue: number
  closedAgreedValue: number
  generatedAt: string
}

// ---------------------------------------------------------------- documents

/** `DocumentDtos.DocumentSummary` - one row of the register. */
export interface DocumentSummaryRow {
  id: string
  documentCode: string
  title: string
  documentType: string
  status: string
  propertyId: string | null
  propertyCode: string | null
  propertyName: string | null
  unitId: string | null
  unitCode: string | null
  leaseId: string | null
  leaseCode: string | null
  tenantId: string | null
  tenantCode: string | null
  ownerId: string | null
  ownerCode: string | null
  buyerId: string | null
  buyerCode: string | null
  saleId: string | null
  maintenanceRequestId: string | null
  confidential: boolean
  superseded: boolean
  supersededById: string | null
  supersededByCode: string | null
  version: number
  fileReference: string | null
  mimeType: string | null
  byteSize: number | null
  pageCount: number | null
  issuedDate: string | null
  expiryDate: string | null
  verified: boolean
  createdAt: string
  updatedAt: string
}

/** `DocumentDtos.DocumentDetail` - one register entry's full record. */
export interface DocumentDetail {
  id: string
  documentCode: string
  title: string
  documentType: string
  status: string
  propertyId: string | null
  propertyCode: string | null
  propertyName: string | null
  unitId: string | null
  unitCode: string | null
  leaseId: string | null
  leaseCode: string | null
  tenantId: string | null
  tenantCode: string | null
  ownerId: string | null
  ownerCode: string | null
  buyerId: string | null
  buyerCode: string | null
  saleId: string | null
  maintenanceRequestId: string | null
  confidential: boolean
  superseded: boolean
  supersededById: string | null
  supersededByCode: string | null
  version: number
  fileReference: string | null
  mimeType: string | null
  byteSize: number | null
  pageCount: number | null
  issuedDate: string | null
  expiryDate: string | null
  verifiedById: string | null
  verifiedByName: string | null
  verifiedAt: string | null
  uploadedById: string | null
  uploadedByName: string | null
  notes: string | null
  createdAt: string
  updatedAt: string
}

/** `DocumentDtos.ExpiryEntry` - one line of the expiry watchlist, with the days left counted server-side. */
export interface DocumentExpiryEntry {
  id: string
  documentCode: string
  title: string
  documentType: string
  propertyId: string | null
  propertyCode: string | null
  propertyName: string | null
  leaseId: string | null
  leaseCode: string | null
  expiryDate: string | null
  daysUntilExpiry: number
  confidential: boolean
}

// ---------------------------------------------------------------- communication

/** `CommunicationDtos.ConversationSummary` - one row of the thread list. */
export interface ConversationSummaryRow {
  id: string
  conversationCode: string
  subject: string
  channel: string
  status: string
  priority: string
  propertyId: string | null
  propertyCode: string | null
  propertyName: string | null
  unitId: string | null
  leaseId: string | null
  listingId: string | null
  leadId: string | null
  tenantId: string | null
  tenantCode: string | null
  ownerId: string | null
  buyerId: string | null
  counterpartyName: string | null
  counterpartyEmail: string | null
  counterpartyPhone: string | null
  assignedToId: string | null
  assignedToName: string | null
  lastMessageAt: string | null
  lastMessagePreview: string | null
  openedAt: string | null
  closedAt: string | null
  closeReason: string | null
  messageCount: number
  unreadCount: number
  createdAt: string
  updatedAt: string
}

/** `CommunicationDtos.MessageSummary` - one message. Styled from stored fields, never from its body. */
export interface MessageSummary {
  id: string
  senderType: string
  messageType: string
  internal: boolean
  senderUserId: string | null
  senderName: string | null
  body: string
  sentAt: string | null
  readAt: string | null
  systemEvent: string | null
}

/** `CommunicationDtos.ConversationDetail` - one thread and the messages a client could see. */
export interface ConversationDetail {
  conversation: ConversationSummaryRow
  messages: MessageSummary[]
  includesInternalNotes: boolean
}

/** `CommunicationDtos.UnreadCount` - the console badge. The field is `unreadMessages`. */
export interface ConversationUnreadCount {
  unreadMessages: number
}

// ---------------------------------------------------------------- portfolios

/** `PortfolioDtos.PortfolioSummary` - one row of the portfolio register. */
export interface PortfolioSummary {
  id: string
  portfolioCode: string
  name: string
  description: string | null
  strategy: string | null
  status: string
  ownerId: string | null
  ownerName: string | null
  managerId: string | null
  managerName: string | null
  targetYieldPercent: number | null
  inceptionDate: string | null
  propertyCount: number
  createdAt: string
  updatedAt: string
}

/** `PortfolioDtos.AllocationEntry` - one asset's membership of a book, held as a period. */
export interface PortfolioAllocationEntry {
  id: string
  propertyId: string | null
  propertyCode: string | null
  propertyName: string | null
  allocatedAt: string
  deallocatedAt: string | null
  open: boolean
  notes: string | null
}

/** `PortfolioDtos.PortfolioDetail` - one book's full record, with the assets allocated to it. */
export interface PortfolioDetail {
  portfolio: PortfolioSummary
  allocations: PortfolioAllocationEntry[]
}

/** `PortfolioDtos.PortfolioPerformance` - one book's performance, every figure read from its own query. */
export interface PortfolioPerformance {
  portfolioId: string
  portfolioCode: string
  name: string
  strategy: string | null
  status: string
  asOf: string | null
  propertyCount: number
  totalValuation: number
  totalAcquisitionValue: number
  monthlyRentRoll: number
  operatingExpenses: number
  capitalExpenses: number
  netOperatingIncome: number
  annualisedRent: number
  achievedYieldPercent: number
  targetYieldPercent: number | null
  occupancyPercent: number
  occupancyTargetPercent: number
  yieldBasis: string | null
}

// ---------------------------------------------------------------- notifications

/** `NotificationDtos.NotificationSummary` - one notification in the caller's own feed. */
export interface NotificationRow {
  id: string
  type: string
  severity: string
  title: string
  body: string | null
  entityType: string | null
  entityId: string | null
  link: string | null
  read: boolean
  readAt: string | null
  createdAt: string
}

/** `NotificationDtos.UnreadCount` - the bell's badge. The field is `unread`. */
export interface NotificationUnreadCount {
  unread: number
}

// ---------------------------------------------------------------- audit

/** `AuditDtos.AuditEntry` - one entry in the trail. */
export interface AuditRow {
  id: string
  module: string
  action: string
  severity: string
  description: string | null
  actorId: string | null
  actorName: string | null
  entityType: string | null
  entityId: string | null
  entityLabel: string | null
  propertyId: string | null
  unitId: string | null
  ownerId: string | null
  tenantId: string | null
  leadId: string | null
  listingId: string | null
  leaseId: string | null
  paymentId: string | null
  maintenanceRequestId: string | null
  previousValue: string | null
  newValue: string | null
  metadata: string | null
  ipAddress: string | null
  userAgent: string | null
  createdAt: string
}

// ---------------------------------------------------------------- reference data

/** `ReferenceDtos.PropertyTypeView` - one property category. */
export interface PropertyTypeView {
  id: string
  code: string
  name: string
  description: string | null
  leasable: boolean
  displayOrder: number
}

/** `ReferenceDtos.UnitTypeView` - one unit category. */
export interface UnitTypeView {
  id: string
  code: string
  name: string
  description: string | null
  displayOrder: number
}

/** `AmenityDtos.AmenityView` - one facility a property can offer. */
export interface AmenityView {
  id: string
  code: string
  name: string
  category: string
  displayOrder: number
}

/** `ExpenseCategoryDtos.CategoryView` - one heading an expense may be recorded under. */
export interface ExpenseCategoryView {
  id: string
  code: string
  name: string
  group: string | null
  capital: boolean
  typicallyRecoverable: boolean
  description: string | null
  displayOrder: number
}

/**
 * `BuildingDtos.BuildingSummary` - one row of the building list.
 *
 * A block's code is `buildingCode`, unique within its property rather than globally, and the row
 * carries its property's name so a code is never read out of context.
 */
export interface BuildingSummary {
  id: string
  propertyId: string | null
  propertyName: string | null
  buildingCode: string
  name: string
  floors: number | null
  yearBuilt: number | null
  unitCount: number
  createdAt: string
  updatedAt: string
}
