package com.meridian.realestate.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * A fault raised against a property or a unit, and the work done about it.
 *
 * <p>A request is the unit of maintenance work. It is raised against a property and optionally
 * against the unit inside it, and it moves through {@link MaintenanceStatus}. The history of those
 * moves is kept in {@link MaintenanceStatusHistory} rather than inferred from the row.</p>
 *
 * <p><b>Cost is carried in two places on purpose.</b> {@code estimatedCost} is what the fault was
 * expected to cost when it was raised; {@code actualCost} is what it cost. The two are not a
 * duplication and must not be collapsed: the estimate is what the approval conversation was about,
 * and the variance between them is itself a fact the operations report reads. A request is also
 * often raised before anybody has priced it, so both are nullable.</p>
 *
 * <p>The actual cost is what the expense ledger records against the property in {@code
 * property_expenses.maintenance_request_id}. That link is what lets a property's maintenance spend
 * be traced back to the faults that caused it, rather than appearing as an unexplained lump.</p>
 *
 * <p><b>Who is doing the work.</b> The request can be held by an empanelled vendor
 * ({@code assignedVendor}) or by a member of the firm's own facilities team ({@code assignedTo}) -
 * or, briefly, by nobody. All three are real states, which is why both assignment columns are
 * nullable. What the schema does require is that a request in {@link MaintenanceStatus#ASSIGNED}
 * names somebody and records when: a request that claims to be assigned to no one is how work
 * silently stops. Nor can work be marked {@link MaintenanceStatus#IN_PROGRESS} without a start
 * moment, nor resolved or closed without a resolution moment and notes, nor closed before it was
 * resolved.</p>
 *
 * <p><b>The urgent queue.</b> The schema carries the partial index {@code
 * ix_maintenance_requests_open} over ({@code reported_at}) where {@code status in ('OPEN',
 * 'ASSIGNED', 'IN_PROGRESS')} - the open set of {@link MaintenanceStatus#isOpen()}. The dashboard's
 * open-work count and its urgent count read exactly that set, intersected for the urgent figure
 * with {@code priority in ('HIGH', 'URGENT')} - {@link MaintenancePriority#isUrgent()}. Those two
 * predicates are the definition of the KPI; a query that widens or narrows either one reports a
 * number nobody can reconcile with the board.</p>
 *
 * <p>{@code chargeableToTenant} marks a cost the lease allows the firm to recharge. It is a flag
 * about entitlement; what was actually recovered is recorded on the expense row, because the two are
 * routinely different and conflating them would show phantom recovery.</p>
 */
@Entity
@Table(name = "maintenance_requests")
public class MaintenanceRequest extends Auditable {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "request_code", nullable = false, length = 30, unique = true)
  private String requestCode;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "property_id", nullable = false)
  private Property property;

  /** Set when the fault is in a specific unit rather than in the property at large. */
  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "unit_id")
  private Unit unit;

  /** Set when the fault is in a specific block of a multi-block property. */
  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "building_id")
  private Building building;

  @Enumerated(EnumType.STRING)
  @Column(name = "category", nullable = false, length = 30)
  private MaintenanceCategory category;

  @Enumerated(EnumType.STRING)
  @Column(name = "priority", nullable = false, length = 20)
  private MaintenancePriority priority = MaintenancePriority.MEDIUM;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 20)
  private MaintenanceStatus status = MaintenanceStatus.OPEN;

  @Column(name = "title", nullable = false, length = 200)
  private String title;

  @Column(name = "description", columnDefinition = "text")
  private String description;

  /** The staff account that reported it, when a member of staff did. */
  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "reported_by")
  private User reportedBy;

  /** The tenant that reported it, when it came through a tenant. */
  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "reported_by_tenant_id")
  private Tenant reportedByTenant;

  /** A plain name for the rest - a walk-in, or a fault found on inspection by nobody in particular. */
  @Column(name = "reporter_name", length = 200)
  private String reporterName;

  /** The empanelled trade holding the work, when a vendor is. */
  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "assigned_vendor_id")
  private Vendor assignedVendor;

  /** The internal colleague holding the work, when the facilities team is. */
  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "assigned_to")
  private User assignedTo;

  @Column(name = "reported_at", nullable = false)
  private LocalDateTime reportedAt;

  @Column(name = "target_date")
  private LocalDate targetDate;

  @Column(name = "assigned_at")
  private LocalDateTime assignedAt;

  @Column(name = "started_at")
  private LocalDateTime startedAt;

  @Column(name = "resolved_at")
  private LocalDateTime resolvedAt;

  @Column(name = "closed_at")
  private LocalDateTime closedAt;

  /** What the fault was expected to cost. Null until somebody has priced it. */
  @Column(name = "estimated_cost", precision = 12, scale = 2)
  private BigDecimal estimatedCost;

  /** What it actually cost - the figure the expense ledger records against the property. */
  @Column(name = "actual_cost", precision = 12, scale = 2)
  private BigDecimal actualCost;

  @Column(name = "resolution_notes", length = 2000)
  private String resolutionNotes;

  /** True when the lease allows the cost to be recovered from the tenant. */
  @Column(name = "chargeable_to_tenant", nullable = false)
  private boolean chargeableToTenant = false;

  /** True when the request is still outstanding - the open set of the schema's partial index. */
  public boolean isOpen() {
    return status != null && status.isOpen();
  }

  /** True when this request is part of the dashboard's urgent queue. */
  public boolean isUrgentOpen() {
    return isOpen() && priority != null && priority.isUrgent();
  }

  public UUID getId() {
    return id;
  }

  public void setId(UUID id) {
    this.id = id;
  }

  public String getRequestCode() {
    return requestCode;
  }

  public void setRequestCode(String requestCode) {
    this.requestCode = requestCode;
  }

  public Property getProperty() {
    return property;
  }

  public void setProperty(Property property) {
    this.property = property;
  }

  public Unit getUnit() {
    return unit;
  }

  public void setUnit(Unit unit) {
    this.unit = unit;
  }

  public Building getBuilding() {
    return building;
  }

  public void setBuilding(Building building) {
    this.building = building;
  }

  public MaintenanceCategory getCategory() {
    return category;
  }

  public void setCategory(MaintenanceCategory category) {
    this.category = category;
  }

  public MaintenancePriority getPriority() {
    return priority;
  }

  public void setPriority(MaintenancePriority priority) {
    this.priority = priority;
  }

  public MaintenanceStatus getStatus() {
    return status;
  }

  public void setStatus(MaintenanceStatus status) {
    this.status = status;
  }

  public String getTitle() {
    return title;
  }

  public void setTitle(String title) {
    this.title = title;
  }

  public String getDescription() {
    return description;
  }

  public void setDescription(String description) {
    this.description = description;
  }

  public User getReportedBy() {
    return reportedBy;
  }

  public void setReportedBy(User reportedBy) {
    this.reportedBy = reportedBy;
  }

  public Tenant getReportedByTenant() {
    return reportedByTenant;
  }

  public void setReportedByTenant(Tenant reportedByTenant) {
    this.reportedByTenant = reportedByTenant;
  }

  public String getReporterName() {
    return reporterName;
  }

  public void setReporterName(String reporterName) {
    this.reporterName = reporterName;
  }

  public Vendor getAssignedVendor() {
    return assignedVendor;
  }

  public void setAssignedVendor(Vendor assignedVendor) {
    this.assignedVendor = assignedVendor;
  }

  public User getAssignedTo() {
    return assignedTo;
  }

  public void setAssignedTo(User assignedTo) {
    this.assignedTo = assignedTo;
  }

  public LocalDateTime getReportedAt() {
    return reportedAt;
  }

  public void setReportedAt(LocalDateTime reportedAt) {
    this.reportedAt = reportedAt;
  }

  public LocalDate getTargetDate() {
    return targetDate;
  }

  public void setTargetDate(LocalDate targetDate) {
    this.targetDate = targetDate;
  }

  public LocalDateTime getAssignedAt() {
    return assignedAt;
  }

  public void setAssignedAt(LocalDateTime assignedAt) {
    this.assignedAt = assignedAt;
  }

  public LocalDateTime getStartedAt() {
    return startedAt;
  }

  public void setStartedAt(LocalDateTime startedAt) {
    this.startedAt = startedAt;
  }

  public LocalDateTime getResolvedAt() {
    return resolvedAt;
  }

  public void setResolvedAt(LocalDateTime resolvedAt) {
    this.resolvedAt = resolvedAt;
  }

  public LocalDateTime getClosedAt() {
    return closedAt;
  }

  public void setClosedAt(LocalDateTime closedAt) {
    this.closedAt = closedAt;
  }

  public BigDecimal getEstimatedCost() {
    return estimatedCost;
  }

  public void setEstimatedCost(BigDecimal estimatedCost) {
    this.estimatedCost = estimatedCost;
  }

  public BigDecimal getActualCost() {
    return actualCost;
  }

  public void setActualCost(BigDecimal actualCost) {
    this.actualCost = actualCost;
  }

  public String getResolutionNotes() {
    return resolutionNotes;
  }

  public void setResolutionNotes(String resolutionNotes) {
    this.resolutionNotes = resolutionNotes;
  }

  public boolean isChargeableToTenant() {
    return chargeableToTenant;
  }

  public void setChargeableToTenant(boolean chargeableToTenant) {
    this.chargeableToTenant = chargeableToTenant;
  }
}
