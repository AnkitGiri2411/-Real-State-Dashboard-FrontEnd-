package com.meridian.realestate.controller;

import com.meridian.realestate.dto.ApiResponse;
import com.meridian.realestate.dto.ApplicationDtos;
import com.meridian.realestate.dto.PageResponse;
import com.meridian.realestate.service.ApplicationService;
import com.meridian.realestate.web.PageRequestFactory;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Rental applications.
 *
 * <p>An application is decided whole - the decider and the decision time are taken server-side, and a
 * rejection carries its reason - and it may only be approved or rejected once every screening check is
 * finished. Each operation writes an application status-history row and an audit entry.</p>
 */
@RestController
@RequestMapping("/api/v1/applications")
public class ApplicationController {

  private static final PageRequestFactory PAGES =
      PageRequestFactory.sortedBy("submittedAt,desc")
          .allow("submittedAt", "submittedAt")
          .allow("createdAt", "createdAt")
          .allow("decidedAt", "decidedAt")
          .allow("status", "status")
          .allow("applicationCode", "applicationCode")
          .allow("applicant", "applicantName")
          .allow("proposedRent", "proposedRent")
          .build();

  private final ApplicationService applicationService;

  public ApplicationController(ApplicationService applicationService) {
    this.applicationService = applicationService;
  }

  /** The register. Every filter is optional; sort is restricted to the fields offered. */
  @GetMapping
  @PreAuthorize("hasAuthority('APPLICATION_VIEW')")
  public ApiResponse<PageResponse<ApplicationDtos.ApplicationSummary>> list(
      @RequestParam(required = false) UUID propertyId,
      @RequestParam(required = false) UUID unitId,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String applicant,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size,
      @RequestParam(required = false) String sort) {
    return ApiResponse.ok(
        applicationService.list(
            propertyId, unitId, status, applicant, PAGES.toPageable(page, size, sort)));
  }

  /** One application, with its screenings and history. */
  @GetMapping("/{applicationId}")
  @PreAuthorize("hasAuthority('APPLICATION_VIEW')")
  public ApiResponse<ApplicationDtos.ApplicationDetail> detail(@PathVariable UUID applicationId) {
    return ApiResponse.ok(applicationService.detail(applicationId));
  }

  /** Take a rental application for a unit. */
  @PostMapping
  @PreAuthorize("hasAuthority('APPLICATION_CREATE')")
  public ApiResponse<ApplicationDtos.ApplicationDetail> create(
      @Valid @RequestBody ApplicationDtos.CreateApplicationRequest request) {
    return ApiResponse.ok(applicationService.create(request), "Application taken");
  }

  /** Move an application into screening. */
  @PostMapping("/{applicationId}/screen")
  @PreAuthorize("hasAuthority('APPLICATION_SCREEN')")
  public ApiResponse<ApplicationDtos.ApplicationDetail> submitForScreening(
      @PathVariable UUID applicationId,
      @RequestBody(required = false) ApplicationDtos.SubmitForScreeningRequest request) {
    return ApiResponse.ok(applicationService.submitForScreening(applicationId, request), "Application in screening");
  }

  /** Record the result of one screening check. */
  @PostMapping("/{applicationId}/screenings")
  @PreAuthorize("hasAuthority('APPLICATION_SCREEN')")
  public ApiResponse<ApplicationDtos.ApplicationDetail> recordScreening(
      @PathVariable UUID applicationId,
      @Valid @RequestBody ApplicationDtos.RecordScreeningRequest request) {
    return ApiResponse.ok(applicationService.recordScreening(applicationId, request), "Screening recorded");
  }

  /** Approve an application, once every screening check is finished. */
  @PostMapping("/{applicationId}/approve")
  @PreAuthorize("hasAuthority('APPLICATION_DECIDE')")
  public ApiResponse<ApplicationDtos.ApplicationDetail> approve(
      @PathVariable UUID applicationId,
      @RequestBody(required = false) ApplicationDtos.ApproveRequest request) {
    return ApiResponse.ok(applicationService.approve(applicationId, request), "Application approved");
  }

  /** Reject an application, once every screening check is finished. */
  @PostMapping("/{applicationId}/reject")
  @PreAuthorize("hasAuthority('APPLICATION_DECIDE')")
  public ApiResponse<ApplicationDtos.ApplicationDetail> reject(
      @PathVariable UUID applicationId, @Valid @RequestBody ApplicationDtos.RejectRequest request) {
    return ApiResponse.ok(applicationService.reject(applicationId, request), "Application rejected");
  }

  /** Withdraw an application before a decision is taken. */
  @PostMapping("/{applicationId}/withdraw")
  @PreAuthorize("hasAuthority('APPLICATION_DECIDE')")
  public ApiResponse<ApplicationDtos.ApplicationDetail> withdraw(
      @PathVariable UUID applicationId,
      @RequestBody(required = false) ApplicationDtos.WithdrawRequest request) {
    return ApiResponse.ok(applicationService.withdraw(applicationId, request), "Application withdrawn");
  }
}
