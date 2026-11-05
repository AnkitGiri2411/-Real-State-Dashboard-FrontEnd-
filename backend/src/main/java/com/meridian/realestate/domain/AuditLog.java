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
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * The trail of what actually happened, in the order it happened.
 *
 * <p>{@code actor} is a real foreign key to the account that acted, because "everything this account
 * did" has to be a single indexed lookup rather than a scan over a name that may have been typed
 * differently. It is nullable on purpose: a scheduled job, a failed sign-in attempt or a system
 * migration has no account behind it, and inventing one would be a lie in the record.</p>
 *
 * <p>The entity-reference columns ({@code entityId}, {@code propertyId}, {@code unitId},
 * {@code ownerId}, {@code tenantId}, {@code leadId}, {@code listingId}, {@code leaseId},
 * {@code paymentId}, {@code maintenanceRequestId}) are plain identifiers with <em>no</em> foreign
 * key, deliberately. An audit entry must survive the record it describes being archived, and it
 * must never be deleted or rewritten by a cascade; a constraint here would quietly turn the trail
 * into a description of whatever still exists. They are UUID columns rather than text so the desk
 * can ask "show me everything that happened to this property" as an index lookup, without parsing
 * a description.</p>
 *
 * <p>{@code entityType} / {@code entityId} carry the generic case; the named columns carry the
 * cases the product actually filters on, which is why both exist.</p>
 *
 * <p>{@code metadata} is jsonb so an operational summary - a state transition, the field that
 * changed - stays queryable instead of being prose nobody can aggregate. It must never hold a
 * credential: a sign-in entry records <em>that</em> somebody signed in, never how.</p>
 *
 * <p>There is no base class and no {@code updated_at}, because an entry is written once and never
 * edited. That is the property that makes the trail worth keeping: a row that could be rewritten
 * would only be a claim about the past rather than a record of it, which is why nothing in the
 * product updates this table.</p>
 */
@Entity
@Table(name = "audit_logs")
public class AuditLog {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "module", nullable = false, length = 60)
  private String module;

  @Column(name = "action", nullable = false, length = 80)
  private String action;

  @Enumerated(EnumType.STRING)
  @Column(name = "severity", nullable = false, length = 20)
  private AuditSeverity severity = AuditSeverity.INFO;

  @Column(name = "description", length = 500)
  private String description;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "actor_id")
  private User actor;

  @Column(name = "actor_name", length = 200)
  private String actorName;

  @Column(name = "entity_type", length = 60)
  private String entityType;

  @Column(name = "entity_id")
  private UUID entityId;

  @Column(name = "entity_label", length = 200)
  private String entityLabel;

  @Column(name = "property_id")
  private UUID propertyId;

  @Column(name = "unit_id")
  private UUID unitId;

  @Column(name = "owner_id")
  private UUID ownerId;

  @Column(name = "tenant_id")
  private UUID tenantId;

  @Column(name = "lead_id")
  private UUID leadId;

  @Column(name = "listing_id")
  private UUID listingId;

  @Column(name = "lease_id")
  private UUID leaseId;

  @Column(name = "payment_id")
  private UUID paymentId;

  @Column(name = "maintenance_request_id")
  private UUID maintenanceRequestId;

  @Column(name = "previous_value", length = 500)
  private String previousValue;

  @Column(name = "new_value", length = 500)
  private String newValue;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "metadata", columnDefinition = "jsonb")
  private String metadata;

  @Column(name = "ip_address", length = 60)
  private String ipAddress;

  @Column(name = "user_agent", length = 255)
  private String userAgent;

  @Column(name = "created_at", nullable = false, updatable = false)
  private LocalDateTime createdAt;

  @PrePersist
  void onCreate() {
    if (createdAt == null) createdAt = LocalDateTime.now();
  }

  public UUID getId() {
    return id;
  }

  public void setId(UUID id) {
    this.id = id;
  }

  public String getModule() {
    return module;
  }

  public void setModule(String module) {
    this.module = module;
  }

  public String getAction() {
    return action;
  }

  public void setAction(String action) {
    this.action = action;
  }

  public AuditSeverity getSeverity() {
    return severity;
  }

  public void setSeverity(AuditSeverity severity) {
    this.severity = severity;
  }

  public String getDescription() {
    return description;
  }

  public void setDescription(String description) {
    this.description = description;
  }

  public User getActor() {
    return actor;
  }

  public void setActor(User actor) {
    this.actor = actor;
  }

  public String getActorName() {
    return actorName;
  }

  public void setActorName(String actorName) {
    this.actorName = actorName;
  }

  public String getEntityType() {
    return entityType;
  }

  public void setEntityType(String entityType) {
    this.entityType = entityType;
  }

  public UUID getEntityId() {
    return entityId;
  }

  public void setEntityId(UUID entityId) {
    this.entityId = entityId;
  }

  public String getEntityLabel() {
    return entityLabel;
  }

  public void setEntityLabel(String entityLabel) {
    this.entityLabel = entityLabel;
  }

  public UUID getPropertyId() {
    return propertyId;
  }

  public void setPropertyId(UUID propertyId) {
    this.propertyId = propertyId;
  }

  public UUID getUnitId() {
    return unitId;
  }

  public void setUnitId(UUID unitId) {
    this.unitId = unitId;
  }

  public UUID getOwnerId() {
    return ownerId;
  }

  public void setOwnerId(UUID ownerId) {
    this.ownerId = ownerId;
  }

  public UUID getTenantId() {
    return tenantId;
  }

  public void setTenantId(UUID tenantId) {
    this.tenantId = tenantId;
  }

  public UUID getLeadId() {
    return leadId;
  }

  public void setLeadId(UUID leadId) {
    this.leadId = leadId;
  }

  public UUID getListingId() {
    return listingId;
  }

  public void setListingId(UUID listingId) {
    this.listingId = listingId;
  }

  public UUID getLeaseId() {
    return leaseId;
  }

  public void setLeaseId(UUID leaseId) {
    this.leaseId = leaseId;
  }

  public UUID getPaymentId() {
    return paymentId;
  }

  public void setPaymentId(UUID paymentId) {
    this.paymentId = paymentId;
  }

  public UUID getMaintenanceRequestId() {
    return maintenanceRequestId;
  }

  public void setMaintenanceRequestId(UUID maintenanceRequestId) {
    this.maintenanceRequestId = maintenanceRequestId;
  }

  public String getPreviousValue() {
    return previousValue;
  }

  public void setPreviousValue(String previousValue) {
    this.previousValue = previousValue;
  }

  public String getNewValue() {
    return newValue;
  }

  public void setNewValue(String newValue) {
    this.newValue = newValue;
  }

  public String getMetadata() {
    return metadata;
  }

  public void setMetadata(String metadata) {
    this.metadata = metadata;
  }

  public String getIpAddress() {
    return ipAddress;
  }

  public void setIpAddress(String ipAddress) {
    this.ipAddress = ipAddress;
  }

  public String getUserAgent() {
    return userAgent;
  }

  public void setUserAgent(String userAgent) {
    this.userAgent = userAgent;
  }

  public LocalDateTime getCreatedAt() {
    return createdAt;
  }

  public void setCreatedAt(LocalDateTime createdAt) {
    this.createdAt = createdAt;
  }
}
