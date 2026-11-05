package com.meridian.realestate.controller;

import com.meridian.realestate.dto.ApiResponse;
import com.meridian.realestate.dto.LeadDtos;
import com.meridian.realestate.dto.PageResponse;
import com.meridian.realestate.service.LeadService;
import com.meridian.realestate.web.PageRequestFactory;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The enquiry pipeline.
 *
 * <p>Note the shape of this controller: there is no endpoint that writes a stage from a request
 * field. A lead moves only through {@code POST /leads/{id}/stage}, which validates the move against
 * the service's transition map and refuses an illegal one. Assignment, activities, follow-ups and
 * conversion each have their own endpoint and their own permission.</p>
 *
 * <p>Every endpoint declares a permission code that exists in the {@code V2} seed. No role name
 * appears anywhere in this class, so changing what a role may do is a data change and never a code
 * change.</p>
 */
@RestController
@RequestMapping("/api/v1/leads")
public class LeadController {

  private static final PageRequestFactory PAGES =
      PageRequestFactory.sortedBy("createdAt,desc")
          .allow("createdAt", "createdAt")
          .allow("updatedAt", "updatedAt")
          .allow("leadCode", "leadCode")
          .allow("displayName", "displayName")
          .allow("stage", "stage")
          .allow("priority", "priority")
          .allow("expectedValue", "expectedValue")
          .allow("nextActionAt", "nextActionAt")
          .allow("owner", "ownerAgent.displayName")
          .allow("source", "source.name")
          .build();

  private final LeadService leadService;

  public LeadController(LeadService leadService) {
    this.leadService = leadService;
  }

  /** The pipeline register. Every filter is optional; sort is restricted to the fields offered. */
  @GetMapping
  @PreAuthorize("hasAuthority('LEAD_VIEW')")
  public ApiResponse<PageResponse<LeadDtos.LeadSummary>> list(
      @RequestParam(required = false) String stage,
      @RequestParam(required = false) String priority,
      @RequestParam(required = false) String type,
      @RequestParam(required = false) String source,
      @RequestParam(required = false) UUID ownerAgentId,
      @RequestParam(required = false) UUID officeId,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate createdFrom,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate createdTo,
      @RequestParam(required = false) String search,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size,
      @RequestParam(required = false) String sort) {
    return ApiResponse.ok(
        leadService.list(
            stage,
            priority,
            type,
            source,
            ownerAgentId,
            officeId,
            createdFrom,
            createdTo,
            search,
            PAGES.toPageable(page, size, sort)));
  }

  /** Counts per stage and the open pipeline value. */
  @GetMapping("/pipeline")
  @PreAuthorize("hasAuthority('LEAD_VIEW')")
  public ApiResponse<LeadDtos.PipelineResponse> pipeline() {
    return ApiResponse.ok(leadService.pipeline());
  }

  /** The conversion funnel: cumulative counts, so it can only narrow. */
  @GetMapping("/funnel")
  @PreAuthorize("hasAuthority('LEAD_VIEW')")
  public ApiResponse<LeadDtos.FunnelResponse> funnel() {
    return ApiResponse.ok(leadService.funnel());
  }

  /** Leads whose next action has come due, and the follow-ups that are overdue. */
  @GetMapping("/needs-attention")
  @PreAuthorize("hasAuthority('LEAD_VIEW')")
  public ApiResponse<LeadDtos.NeedsAttentionResponse> needsAttention() {
    return ApiResponse.ok(leadService.needsAttention());
  }

  /** One lead, with its assignments, activities, follow-ups, viewings and applications. */
  @GetMapping("/{leadId}")
  @PreAuthorize("hasAuthority('LEAD_VIEW')")
  public ApiResponse<LeadDtos.LeadDetail> detail(@PathVariable UUID leadId) {
    return ApiResponse.ok(leadService.detail(leadId));
  }

  /** Log a lead. Every lead starts at NEW. */
  @PostMapping
  @PreAuthorize("hasAuthority('LEAD_CREATE')")
  public ApiResponse<LeadDtos.LeadDetail> create(@Valid @RequestBody LeadDtos.CreateLeadRequest request) {
    return ApiResponse.ok(leadService.create(request), "Lead logged");
  }

  /** Change a lead's details. */
  @PutMapping("/{leadId}")
  @PreAuthorize("hasAuthority('LEAD_UPDATE')")
  public ApiResponse<LeadDtos.LeadDetail> update(
      @PathVariable UUID leadId, @Valid @RequestBody LeadDtos.UpdateLeadRequest request) {
    return ApiResponse.ok(leadService.update(leadId, request));
  }

  /** Move a lead between pipeline stages. */
  @PostMapping("/{leadId}/stage")
  @PreAuthorize("hasAuthority('LEAD_UPDATE')")
  public ApiResponse<LeadDtos.LeadDetail> transition(
      @PathVariable UUID leadId, @Valid @RequestBody LeadDtos.StageTransitionRequest request) {
    return ApiResponse.ok(leadService.transition(leadId, request), "Lead stage changed");
  }

  /** Assign or reassign the agent who holds a lead. */
  @PostMapping("/{leadId}/assign")
  @PreAuthorize("hasAuthority('LEAD_ASSIGN')")
  public ApiResponse<LeadDtos.LeadDetail> assign(
      @PathVariable UUID leadId, @Valid @RequestBody LeadDtos.AssignLeadRequest request) {
    return ApiResponse.ok(leadService.assign(leadId, request), "Lead assigned");
  }

  /** Convert a won lead into the tenant or the buyer it produced. */
  @PostMapping("/{leadId}/convert")
  @PreAuthorize("hasAuthority('LEAD_CONVERT')")
  public ApiResponse<LeadDtos.LeadDetail> convert(
      @PathVariable UUID leadId, @Valid @RequestBody LeadDtos.ConvertLeadRequest request) {
    return ApiResponse.ok(leadService.convert(leadId, request), "Lead converted");
  }

  /** Log work done against a lead. */
  @PostMapping("/{leadId}/activities")
  @PreAuthorize("hasAuthority('LEAD_UPDATE')")
  public ApiResponse<LeadDtos.LeadActivityEntry> logActivity(
      @PathVariable UUID leadId, @Valid @RequestBody LeadDtos.LogActivityRequest request) {
    return ApiResponse.ok(leadService.logActivity(leadId, request), "Activity logged");
  }

  /** Plan a follow-up against a lead. */
  @PostMapping("/{leadId}/followups")
  @PreAuthorize("hasAuthority('LEAD_UPDATE')")
  public ApiResponse<LeadDtos.LeadFollowupEntry> logFollowup(
      @PathVariable UUID leadId, @Valid @RequestBody LeadDtos.LogFollowupRequest request) {
    return ApiResponse.ok(leadService.logFollowup(leadId, request), "Follow-up planned");
  }

  /** Close a follow-up as done, cancelled or missed. */
  @PostMapping("/followups/{followupId}/complete")
  @PreAuthorize("hasAuthority('LEAD_UPDATE')")
  public ApiResponse<LeadDtos.LeadFollowupEntry> completeFollowup(
      @PathVariable UUID followupId, @Valid @RequestBody LeadDtos.CompleteFollowupRequest request) {
    return ApiResponse.ok(leadService.completeFollowup(followupId, request), "Follow-up closed");
  }
}
