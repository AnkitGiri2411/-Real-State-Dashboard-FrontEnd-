package com.meridian.realestate.dto;

import com.meridian.realestate.domain.AuditLog;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/** Audit-trail API contracts. */
public final class AuditDtos {

  private AuditDtos() {}

  /**
   * One entry in the trail.
   *
   * <p>The named entity-reference columns come back as plain identifiers, which is what lets the
   * interface turn "LEASE_ACTIVATED" into a link to the lease without this DTO carrying a nested
   * object the audit table does not actually hold. They are plain identifiers precisely because an
   * audit entry must survive the record it describes being archived.</p>
   *
   * <p>{@code metadata} is returned as-is. It is written by the operation it describes and never
   * holds a credential - a sign-in records <em>that</em> somebody signed in, never how.</p>
   */
  public record AuditEntry(
      UUID id,
      String module,
      String action,
      String severity,
      String description,
      UUID actorId,
      String actorName,
      String entityType,
      UUID entityId,
      String entityLabel,
      UUID propertyId,
      UUID unitId,
      UUID ownerId,
      UUID tenantId,
      UUID leadId,
      UUID listingId,
      UUID leaseId,
      UUID paymentId,
      UUID maintenanceRequestId,
      String previousValue,
      String newValue,
      String metadata,
      String ipAddress,
      String userAgent,
      LocalDateTime createdAt) {

    public static AuditEntry from(AuditLog entry) {
      return new AuditEntry(
          entry.getId(),
          entry.getModule(),
          entry.getAction(),
          entry.getSeverity() == null ? null : entry.getSeverity().name(),
          entry.getDescription(),
          entry.getActor() == null ? null : entry.getActor().getId(),
          entry.getActorName(),
          entry.getEntityType(),
          entry.getEntityId(),
          entry.getEntityLabel(),
          entry.getPropertyId(),
          entry.getUnitId(),
          entry.getOwnerId(),
          entry.getTenantId(),
          entry.getLeadId(),
          entry.getListingId(),
          entry.getLeaseId(),
          entry.getPaymentId(),
          entry.getMaintenanceRequestId(),
          entry.getPreviousValue(),
          entry.getNewValue(),
          entry.getMetadata(),
          entry.getIpAddress(),
          entry.getUserAgent(),
          entry.getCreatedAt());
    }
  }

  /** A per-module tally, for the audit screen's filter chips. */
  public record ModuleCount(String module, long count) {}

  /** The filter vocabularies, so a control offers values that exist rather than a free-text box. */
  public record FilterOptions(List<String> modules, List<String> severities) {}
}
