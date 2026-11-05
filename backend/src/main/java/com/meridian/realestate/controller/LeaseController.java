package com.meridian.realestate.controller;

import com.meridian.realestate.dto.ApiResponse;
import com.meridian.realestate.dto.LeaseDtos;
import com.meridian.realestate.dto.PageResponse;
import com.meridian.realestate.service.LeaseService;
import com.meridian.realestate.web.PageRequestFactory;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.List;
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
 * Leases: the contract that makes a unit occupied, its rent schedule and its renewals.
 *
 * <p>Every write declares the permission for <em>that</em> act, not a blanket "manage leases" right:
 * drafting needs {@code LEASE_CREATE}, changing the terms needs {@code LEASE_UPDATE}, activating needs
 * {@code LEASE_ACTIVATE}, terminating needs {@code LEASE_TERMINATE} and a renewal needs
 * {@code LEASE_RENEW}. The permission codes are the ones seeded in {@code V2}. No business rule lives
 * here - the controller resolves the payload and delegates; every derivation, cap and state machine
 * is in {@link LeaseService}.</p>
 */
@RestController
@RequestMapping("/api/v1/leases")
public class LeaseController {

  private static final PageRequestFactory PAGES =
      PageRequestFactory.sortedBy("createdAt,desc")
          .allow("leaseCode", "leaseCode")
          .allow("startDate", "startDate")
          .allow("endDate", "endDate")
          .allow("rentAmount", "rentAmount")
          .allow("status", "status")
          .allow("createdAt", "createdAt")
          .build();

  private final LeaseService leaseService;

  public LeaseController(LeaseService leaseService) {
    this.leaseService = leaseService;
  }

  @GetMapping
  @PreAuthorize("hasAuthority('LEASE_VIEW')")
  public ApiResponse<PageResponse<LeaseDtos.LeaseSummary>> list(
      @RequestParam(required = false) UUID propertyId,
      @RequestParam(required = false) UUID unitId,
      @RequestParam(required = false) UUID tenantId,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
      @RequestParam(required = false) Integer expiringWithinDays,
      @RequestParam(required = false) String search,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size,
      @RequestParam(required = false) String sort) {
    return ApiResponse.ok(
        PageResponse.from(
            leaseService.search(
                propertyId,
                unitId,
                tenantId,
                status,
                from,
                to,
                expiringWithinDays,
                search,
                PAGES.toPageable(page, size, sort))));
  }

  /** The leases that are still running and end inside the configured expiry window. */
  @GetMapping("/expiring")
  @PreAuthorize("hasAuthority('LEASE_VIEW')")
  public ApiResponse<List<LeaseDtos.ExpiringLease>> expiring(
      @RequestParam(required = false) Integer withinDays) {
    return ApiResponse.ok(leaseService.expiringLeases(withinDays));
  }

  @GetMapping("/{leaseId}")
  @PreAuthorize("hasAuthority('LEASE_VIEW')")
  public ApiResponse<LeaseDtos.LeaseDetail> detail(@PathVariable UUID leaseId) {
    return ApiResponse.ok(leaseService.detail(leaseId));
  }

  @PostMapping
  @PreAuthorize("hasAuthority('LEASE_CREATE')")
  public ApiResponse<LeaseDtos.LeaseSummary> create(
      @Valid @RequestBody LeaseDtos.CreateLeaseRequest request) {
    return ApiResponse.ok(leaseService.createLease(request), "Lease drafted");
  }

  @PutMapping("/{leaseId}")
  @PreAuthorize("hasAuthority('LEASE_UPDATE')")
  public ApiResponse<LeaseDtos.LeaseSummary> update(
      @PathVariable UUID leaseId, @Valid @RequestBody LeaseDtos.UpdateLeaseRequest request) {
    return ApiResponse.ok(leaseService.updateLease(leaseId, request), "Lease updated");
  }

  /** Move a lease between lifecycle states. An illegal move is refused by the service. */
  @PostMapping("/{leaseId}/status")
  @PreAuthorize("hasAuthority('LEASE_UPDATE')")
  public ApiResponse<LeaseDtos.LeaseSummary> changeStatus(
      @PathVariable UUID leaseId, @Valid @RequestBody LeaseDtos.ChangeStatusRequest request) {
    return ApiResponse.ok(leaseService.changeStatus(leaseId, request), "Lease status changed");
  }

  /** Activate a draft or pending lease. Refused when the unit already runs a different lease. */
  @PostMapping("/{leaseId}/activate")
  @PreAuthorize("hasAuthority('LEASE_ACTIVATE')")
  public ApiResponse<LeaseDtos.LeaseSummary> activate(
      @PathVariable UUID leaseId, @RequestBody(required = false) LeaseDtos.ChangeStatusRequest request) {
    return ApiResponse.ok(leaseService.activate(leaseId, request), "Lease activated");
  }

  /** End a lease, recording the reason. */
  @PostMapping("/{leaseId}/terminate")
  @PreAuthorize("hasAuthority('LEASE_TERMINATE')")
  public ApiResponse<LeaseDtos.LeaseSummary> terminate(
      @PathVariable UUID leaseId, @Valid @RequestBody LeaseDtos.TerminateRequest request) {
    return ApiResponse.ok(leaseService.terminateLease(leaseId, request), "Lease terminated");
  }

  /** Add a rent revision to the lease's schedule. Refused when it overlaps an existing term. */
  @PostMapping("/{leaseId}/terms")
  @PreAuthorize("hasAuthority('LEASE_UPDATE')")
  public ApiResponse<LeaseDtos.LeaseTermView> createTerm(
      @PathVariable UUID leaseId, @Valid @RequestBody LeaseDtos.CreateTermRequest request) {
    return ApiResponse.ok(leaseService.createTerm(leaseId, request), "Rent term added");
  }

  /** Open a renewal conversation for a lease. */
  @PostMapping("/{leaseId}/renewals")
  @PreAuthorize("hasAuthority('LEASE_RENEW')")
  public ApiResponse<LeaseDtos.RenewalView> proposeRenewal(
      @PathVariable UUID leaseId, @Valid @RequestBody LeaseDtos.ProposeRenewalRequest request) {
    return ApiResponse.ok(leaseService.proposeRenewal(leaseId, request), "Renewal proposed");
  }

  /** Put a renewal offer to the tenant. Only a running or expiring lease can be offered one. */
  @PostMapping("/renewals/{renewalId}/offer")
  @PreAuthorize("hasAuthority('LEASE_RENEW')")
  public ApiResponse<LeaseDtos.RenewalView> offerRenewal(
      @PathVariable UUID renewalId, @Valid @RequestBody LeaseDtos.OfferRenewalRequest request) {
    return ApiResponse.ok(leaseService.offerRenewal(renewalId, request), "Renewal offered");
  }

  /** Accept a renewal: this drafts the new lease and moves the old one to {@code RENEWED}. */
  @PostMapping("/renewals/{renewalId}/accept")
  @PreAuthorize("hasAuthority('LEASE_RENEW')")
  public ApiResponse<LeaseDtos.RenewalView> acceptRenewal(
      @PathVariable UUID renewalId, @Valid @RequestBody LeaseDtos.DecideRenewalRequest request) {
    return ApiResponse.ok(leaseService.acceptRenewal(renewalId, request), "Renewal accepted");
  }

  /** Decline a renewal offer. */
  @PostMapping("/renewals/{renewalId}/decline")
  @PreAuthorize("hasAuthority('LEASE_RENEW')")
  public ApiResponse<LeaseDtos.RenewalView> declineRenewal(
      @PathVariable UUID renewalId, @Valid @RequestBody LeaseDtos.DeclineRenewalRequest request) {
    return ApiResponse.ok(leaseService.declineRenewal(renewalId, request), "Renewal declined");
  }
}
