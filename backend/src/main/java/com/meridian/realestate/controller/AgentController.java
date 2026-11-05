package com.meridian.realestate.controller;

import com.meridian.realestate.dto.AgentDtos;
import com.meridian.realestate.dto.ApiResponse;
import com.meridian.realestate.dto.PageResponse;
import com.meridian.realestate.service.AgentService;
import com.meridian.realestate.web.PageRequestFactory;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The agent register, the properties assigned to each agent, and the two reports that read an
 * agent's book of work.
 *
 * <p><b>Why this controller exists.</b> An agent is the staff account that carries a portfolio, so
 * the register shows people; the assignments say which stock each carries and in what capacity; and
 * the workload and performance reports are read by agent because they are about a person's book. All
 * three answer "who is responsible for this, and how are they doing".</p>
 *
 * <p><b>The rules it protects.</b> No business rule lives here. A second profile for one staff
 * account is a 409 named {@code AGENT_USER_ALREADY_LINKED}, and a second open primary assignment on
 * one property is a 409 named {@code AGENT_PRIMARY_ALREADY_SET} - both decided in
 * {@link AgentService}, mirroring the schema's own partial unique indexes rather than letting them
 * report as opaque integrity errors. The two report figures are computed from real queries and are
 * guarded against a zero denominator in the service, so a rate that cannot be computed reads
 * {@code 0} rather than {@code NaN}. Nothing here builds an error response.</p>
 *
 * <p>Sorting is honoured through {@link PageRequestFactory}; an unsupported field is a 400, never a
 * 500.</p>
 */
@RestController
@RequestMapping("/api/v1/agents")
public class AgentController {

  private static final PageRequestFactory PAGES =
      PageRequestFactory.sortedBy("createdAt,desc")
          .allow("createdAt", "createdAt")
          .allow("agentCode", "agentCode")
          .allow("displayName", "displayName")
          .allow("specialisation", "specialisation")
          .allow("status", "status")
          .build();

  private final AgentService agentService;

  public AgentController(AgentService agentService) {
    this.agentService = agentService;
  }

  /** The agent register, filtered by office, specialisation and status, with free-text search. */
  @GetMapping
  @PreAuthorize("hasAuthority('AGENT_VIEW')")
  public ApiResponse<PageResponse<AgentDtos.AgentSummary>> list(
      @RequestParam(required = false) UUID officeId,
      @RequestParam(required = false) String specialisation,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String search,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size,
      @RequestParam(required = false) String sort) {
    return ApiResponse.ok(
        PageResponse.from(
            agentService.list(
                officeId, specialisation, status, search, PAGES.toPageable(page, size, sort))));
  }

  /** One agent's full record, with every assignment they hold, open and closed. */
  @GetMapping("/{agentId}")
  @PreAuthorize("hasAuthority('AGENT_VIEW')")
  public ApiResponse<AgentDtos.AgentDetail> detail(@PathVariable UUID agentId) {
    return ApiResponse.ok(agentService.detail(agentId));
  }

  /** An agent's current workload: open leads, live listings, viewings ahead and open maintenance. */
  @GetMapping("/{agentId}/workload")
  @PreAuthorize("hasAuthority('AGENT_VIEW')")
  public ApiResponse<AgentDtos.WorkloadResponse> workload(@PathVariable UUID agentId) {
    return ApiResponse.ok(agentService.workload(agentId));
  }

  /** How an agent's book has performed, with a guarded conversion rate. */
  @GetMapping("/{agentId}/performance")
  @PreAuthorize("hasAuthority('AGENT_VIEW')")
  public ApiResponse<AgentDtos.PerformanceResponse> performance(@PathVariable UUID agentId) {
    return ApiResponse.ok(agentService.performance(agentId));
  }

  @PostMapping
  @PreAuthorize("hasAuthority('AGENT_CREATE')")
  public ApiResponse<AgentDtos.AgentDetail> create(
      @Valid @RequestBody AgentDtos.CreateAgentRequest request) {
    return ApiResponse.ok(agentService.create(request), "Agent profile added");
  }

  @PutMapping("/{agentId}")
  @PreAuthorize("hasAuthority('AGENT_UPDATE')")
  public ApiResponse<AgentDtos.AgentDetail> update(
      @PathVariable UUID agentId, @Valid @RequestBody AgentDtos.UpdateAgentRequest request) {
    return ApiResponse.ok(agentService.update(agentId, request));
  }

  /** Archive an agent profile: a soft delete, which stops the profile carrying stock. */
  @PostMapping("/{agentId}/archive")
  @PreAuthorize("hasAuthority('AGENT_DEACTIVATE')")
  public ApiResponse<AgentDtos.AgentDetail> archive(@PathVariable UUID agentId) {
    return ApiResponse.ok(agentService.archive(agentId), "Agent profile archived");
  }

  /** Assign an agent to a property, refusing a second open primary assignment on that property. */
  @PostMapping("/{agentId}/assignments")
  @PreAuthorize("hasAuthority('AGENT_UPDATE')")
  public ApiResponse<AgentDtos.AssignmentRecord> assign(
      @PathVariable UUID agentId, @Valid @RequestBody AgentDtos.CreateAssignmentRequest request) {
    return ApiResponse.ok(agentService.assign(agentId, request), "Property assigned");
  }

  /** Close an assignment: it is dated, not deleted, so the history stays answerable. */
  @DeleteMapping("/{agentId}/assignments/{assignmentId}")
  @PreAuthorize("hasAuthority('AGENT_UPDATE')")
  public ApiResponse<AgentDtos.AssignmentRecord> closeAssignment(
      @PathVariable UUID agentId, @PathVariable UUID assignmentId) {
    return ApiResponse.ok(
        agentService.closeAssignment(agentId, assignmentId), "Assignment closed");
  }
}
