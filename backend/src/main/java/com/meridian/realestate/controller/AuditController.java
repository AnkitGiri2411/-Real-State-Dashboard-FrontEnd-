package com.meridian.realestate.controller;

import com.meridian.realestate.dto.ApiResponse;
import com.meridian.realestate.dto.AuditDtos;
import com.meridian.realestate.dto.PageResponse;
import com.meridian.realestate.service.AuditService;
import com.meridian.realestate.web.PageRequestFactory;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The audit trail.
 *
 * <p>Read-only, by design: there is no endpoint that writes, edits or deletes an entry, because
 * every entry is written by the operation it describes, inside that operation's own transaction. A
 * trail that could be touched by hand would stop being evidence.</p>
 *
 * <p>Guarded by {@code AUDIT_VIEW}, which the seeded {@code V2} matrix gives to accounting and the
 * administrative roles - the people who would be asked to explain a discrepancy.</p>
 */
@RestController
@RequestMapping("/api/v1/audit")
public class AuditController {

  private static final PageRequestFactory PAGES =
      PageRequestFactory.sortedBy("createdAt,desc")
          .allow("createdAt", "createdAt")
          .allow("module", "module")
          .allow("action", "action")
          .allow("severity", "severity")
          .allow("actor", "actorName")
          .allow("entityType", "entityType")
          .build();

  private final AuditService auditService;

  public AuditController(AuditService auditService) {
    this.auditService = auditService;
  }

  /** The trail, filtered by whatever the reviewer supplied and paged by the caller's own sort. */
  @GetMapping
  @PreAuthorize("hasAuthority('AUDIT_VIEW')")
  public ApiResponse<PageResponse<AuditDtos.AuditEntry>> list(
      @RequestParam(required = false) String module,
      @RequestParam(required = false) String action,
      @RequestParam(required = false) String severity,
      @RequestParam(required = false) UUID actorId,
      @RequestParam(required = false) String actor,
      @RequestParam(required = false) String entityType,
      @RequestParam(required = false) UUID entityId,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size,
      @RequestParam(required = false) String sort) {
    return ApiResponse.ok(
        PageResponse.from(
            auditService.searchEntries(
                module,
                action,
                severity,
                actorId,
                actor,
                entityType,
                entityId,
                from,
                to,
                PAGES.toPageable(page, size, sort))));
  }

  /** The filter vocabularies, so a control offers values that exist rather than a free-text box. */
  @GetMapping("/options")
  @PreAuthorize("hasAuthority('AUDIT_VIEW')")
  public ApiResponse<AuditDtos.FilterOptions> options() {
    return ApiResponse.ok(auditService.options());
  }

  /** The per-module tally, for the filter chips. */
  @GetMapping("/modules")
  @PreAuthorize("hasAuthority('AUDIT_VIEW')")
  public ApiResponse<List<AuditDtos.ModuleCount>> modules() {
    return ApiResponse.ok(auditService.moduleCounts());
  }

  /** How many critical entries are on record, for the security card. */
  @GetMapping("/critical-count")
  @PreAuthorize("hasAuthority('AUDIT_VIEW')")
  public ApiResponse<Long> criticalCount() {
    return ApiResponse.ok(auditService.criticalCount());
  }

  /** Everything recorded about one entity - the story of a lease, a receipt or a property. */
  @GetMapping("/entity/{entityType}/{entityId}")
  @PreAuthorize("hasAuthority('AUDIT_VIEW')")
  public ApiResponse<List<AuditDtos.AuditEntry>> forEntity(
      @PathVariable String entityType, @PathVariable UUID entityId) {
    return ApiResponse.ok(auditService.entriesForEntity(entityType.toUpperCase(java.util.Locale.ROOT), entityId));
  }

  /** Everything one account did, newest first - the question asked after a discrepancy. */
  @GetMapping("/actor/{actorId}")
  @PreAuthorize("hasAuthority('AUDIT_VIEW')")
  public ApiResponse<List<AuditDtos.AuditEntry>> forActor(@PathVariable UUID actorId) {
    return ApiResponse.ok(auditService.entriesForActor(actorId));
  }

  @GetMapping("/{auditId}")
  @PreAuthorize("hasAuthority('AUDIT_VIEW')")
  public ApiResponse<AuditDtos.AuditEntry> detail(@PathVariable UUID auditId) {
    return ApiResponse.ok(auditService.requireEntry(auditId));
  }
}
