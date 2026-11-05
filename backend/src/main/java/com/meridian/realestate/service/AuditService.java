package com.meridian.realestate.service;

import com.meridian.realestate.domain.AuditLog;
import com.meridian.realestate.domain.AuditSeverity;
import com.meridian.realestate.dto.AuditDtos;
import com.meridian.realestate.exception.ApiException;
import com.meridian.realestate.repository.AuditLogRepository;
import jakarta.persistence.criteria.Predicate;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reading the audit trail.
 *
 * <p>The trail is written by every operation that changes business state - {@link
 * AuditRecordingService} is the single door in - and read only through {@code AUDIT_VIEW}, which the
 * seeded {@code V2} matrix gives to accounting and the administrative roles: the people who would be
 * asked to explain a discrepancy.</p>
 *
 * <p><b>Read-only, always.</b> There is no method here that writes, edits or deletes an entry. The
 * table is append-only by design, so "archived entries" is not a state it has, and offering one
 * would make the trail a claim about the past rather than a record of it.</p>
 *
 * <p><b>Filters compose.</b> {@link AuditLogRepository} carries {@code JpaSpecificationExecutor} for
 * exactly this screen: the reviewer combines a module and an action and a severity and an actor and
 * a date window freely, and enumerating those permutations as derived queries would produce a method
 * per combination, most never called. The specification below therefore builds one predicate per
 * supplied filter and hands the whole set to the repository, and no order is ever written into the
 * query - the caller's {@code Pageable} carries it, validated by {@code PageRequestFactory}, with
 * recency as the default.</p>
 */
@Service
public class AuditService {

  private final AuditLogRepository auditLogs;

  public AuditService(AuditLogRepository auditLogs) {
    this.auditLogs = auditLogs;
  }

  /**
   * The audit screen's page, filtered by whatever the caller supplied.
   *
   * <p>Every filter is optional; a null or blank one is simply not applied, so the same endpoint
   * serves "everything, newest first" and "critical security entries by this actor last week".</p>
   */
  @Transactional(readOnly = true)
  public Page<AuditLog> search(
      String module,
      String action,
      String severity,
      UUID actorId,
      String actor,
      String entityType,
      UUID entityId,
      LocalDate from,
      LocalDate to,
      Pageable pageable) {

    AuditSeverity parsedSeverity = parseSeverity(severity);

    Specification<AuditLog> specification =
        (root, query, cb) -> {
          List<Predicate> predicates = new ArrayList<>();
          String moduleValue = trimToNull(module);
          if (moduleValue != null) predicates.add(cb.equal(root.get("module"), moduleValue));
          String actionValue = trimToNull(action);
          if (actionValue != null) predicates.add(cb.equal(root.get("action"), actionValue));
          String entityTypeValue = trimToNull(entityType);
          if (entityTypeValue != null) {
            predicates.add(cb.equal(root.get("entityType"), entityTypeValue));
          }
          if (parsedSeverity != null) {
            predicates.add(cb.equal(root.get("severity"), parsedSeverity));
          }
          if (actorId != null) {
            predicates.add(cb.equal(root.get("actor").get("id"), actorId));
          }
          String actorValue = trimToNull(actor);
          if (actorValue != null) {
            predicates.add(
                cb.like(
                    cb.lower(cb.coalesce(root.<String>get("actorName"), "")),
                    "%" + actorValue.toLowerCase(Locale.ROOT) + "%"));
          }
          if (entityId != null) {
            predicates.add(cb.equal(root.get("entityId"), entityId));
          }
          if (from != null) {
            predicates.add(
                cb.greaterThanOrEqualTo(root.<LocalDateTime>get("createdAt"), from.atStartOfDay()));
          }
          if (to != null) {
            predicates.add(
                cb.lessThanOrEqualTo(root.<LocalDateTime>get("createdAt"), to.atTime(LocalTime.MAX)));
          }
          return predicates.isEmpty() ? cb.conjunction() : cb.and(predicates.toArray(new Predicate[0]));
        };

    return auditLogs.findAll(specification, pageable);
  }

  /** The same page, mapped to the API shape; mapped inside the transaction as every list is. */
  @Transactional(readOnly = true)
  public Page<AuditDtos.AuditEntry> searchEntries(
      String module,
      String action,
      String severity,
      UUID actorId,
      String actor,
      String entityType,
      UUID entityId,
      LocalDate from,
      LocalDate to,
      Pageable pageable) {
    return search(module, action, severity, actorId, actor, entityType, entityId, from, to, pageable)
        .map(AuditDtos.AuditEntry::from);
  }

  @Transactional(readOnly = true)
  public AuditDtos.AuditEntry requireEntry(UUID id) {
    return AuditDtos.AuditEntry.from(require(id));
  }

  @Transactional(readOnly = true)
  public AuditLog require(UUID id) {
    return auditLogs
        .findById(id)
        .orElseThrow(() -> ApiException.notFound("That audit entry does not exist"));
  }

  /** Everything recorded about one entity - the story of a lease, a receipt or a property, in order. */
  @Transactional(readOnly = true)
  public List<AuditDtos.AuditEntry> entriesForEntity(String entityType, UUID entityId) {
    Sort order = Sort.by(Sort.Direction.DESC, "createdAt");
    Specification<AuditLog> specification =
        (root, query, cb) ->
            cb.and(cb.equal(root.get("entityType"), entityType), cb.equal(root.get("entityId"), entityId));
    return auditLogs.findAll(specification, order).stream().map(AuditDtos.AuditEntry::from).toList();
  }

  /** Everything one account did, newest first - the question asked after a discrepancy. */
  @Transactional(readOnly = true)
  public List<AuditDtos.AuditEntry> entriesForActor(UUID actorId) {
    Sort order = Sort.by(Sort.Direction.DESC, "createdAt");
    Specification<AuditLog> specification =
        (root, query, cb) -> cb.equal(root.get("actor").get("id"), actorId);
    return auditLogs.findAll(specification, order).stream().map(AuditDtos.AuditEntry::from).toList();
  }

  /** The per-module tally, for the filter chips. */
  @Transactional(readOnly = true)
  public List<AuditDtos.ModuleCount> moduleCounts() {
    return auditLogs.countGroupedByModule().stream()
        .map(row -> new AuditDtos.ModuleCount(row.getModule(), row.getTotal()))
        .toList();
  }

  /**
   * The filter vocabularies that exist.
   *
   * <p>The modules come from the trail's own values rather than a hand-listed set, so a module that
   * has never been written with simply does not appear; the severities are the closed enum, which is
   * what the column's own constraint allows.</p>
   */
  @Transactional(readOnly = true)
  public AuditDtos.FilterOptions options() {
    List<String> modules =
        auditLogs.countGroupedByModule().stream().map(AuditLogRepository.ModuleCount::getModule).toList();
    List<String> severities = Arrays.stream(AuditSeverity.values()).map(Enum::name).toList();
    return new AuditDtos.FilterOptions(modules, severities);
  }

  /** How many critical entries are on record, for the security card. */
  @Transactional(readOnly = true)
  public long criticalCount() {
    return auditLogs.countBySeverity(AuditSeverity.CRITICAL);
  }

  private static AuditSeverity parseSeverity(String value) {
    String trimmed = trimToNull(value);
    if (trimmed == null) return null;
    try {
      return AuditSeverity.valueOf(trimmed.toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException ex) {
      throw ApiException.badRequest(
          "'" + value + "' is not an audit severity",
          Arrays.stream(AuditSeverity.values()).map(s -> "severity: " + s.name()).toList());
    }
  }

  private static String trimToNull(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }
}
