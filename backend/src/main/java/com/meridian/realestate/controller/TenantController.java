package com.meridian.realestate.controller;

import com.meridian.realestate.dto.ApiResponse;
import com.meridian.realestate.dto.PageResponse;
import com.meridian.realestate.dto.TenantDtos;
import com.meridian.realestate.service.TenantService;
import com.meridian.realestate.web.PageRequestFactory;
import jakarta.validation.Valid;
import java.util.UUID;
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
 * The tenant register and the profile a manager opens on a tenant.
 *
 * <p><b>Why this controller exists.</b> A tenant is the party that occupies a unit under a lease,
 * and the tenant page is "who is this, what are they leasing, what have they paid and what have they
 * reported" - so the detail carries the read-only projections of the leases, receipts and faults
 * behind it rather than making the caller run three more requests.</p>
 *
 * <p><b>The rules it protects.</b> No business rule lives here. The duplicate contact refusals
 * ({@code DUPLICATE_TENANT_EMAIL} / {@code DUPLICATE_TENANT_PHONE}) and the refusal to archive a
 * tenant with a running lease ({@code TENANT_HAS_RUNNING_LEASE}) are decided in
 * {@link TenantService} and raised as {@link com.meridian.realestate.exception.ApiException}. This
 * class only routes, validates the body and wraps the answer. No entity and no repository ever
 * appears here.</p>
 *
 * <p>Sorting is honoured through {@link PageRequestFactory}; an unsupported field is a 400, never a
 * 500.</p>
 */
@RestController
@RequestMapping("/api/v1/tenants")
public class TenantController {

  private static final PageRequestFactory PAGES =
      PageRequestFactory.sortedBy("createdAt,desc")
          .allow("createdAt", "createdAt")
          .allow("displayName", "displayName")
          .allow("tenantCode", "tenantCode")
          .allow("status", "status")
          .allow("city", "city")
          .build();

  private final TenantService tenantService;

  public TenantController(TenantService tenantService) {
    this.tenantService = tenantService;
  }

  /** The tenant register, filtered by status, type and city, with free-text search. */
  @GetMapping
  @PreAuthorize("hasAuthority('TENANT_VIEW')")
  public ApiResponse<PageResponse<TenantDtos.TenantSummary>> list(
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String type,
      @RequestParam(required = false) String city,
      @RequestParam(required = false) String search,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size,
      @RequestParam(required = false) String sort) {
    return ApiResponse.ok(
        PageResponse.from(
            tenantService.list(status, type, city, search, PAGES.toPageable(page, size, sort))));
  }

  /** One tenant's full record, with their leases, receipts and reported faults. */
  @GetMapping("/{tenantId}")
  @PreAuthorize("hasAuthority('TENANT_VIEW')")
  public ApiResponse<TenantDtos.TenantDetail> detail(@PathVariable UUID tenantId) {
    return ApiResponse.ok(tenantService.detail(tenantId));
  }

  @PostMapping
  @PreAuthorize("hasAuthority('TENANT_CREATE')")
  public ApiResponse<TenantDtos.TenantDetail> create(
      @Valid @RequestBody TenantDtos.CreateTenantRequest request) {
    return ApiResponse.ok(tenantService.create(request), "Tenant profile created");
  }

  @PutMapping("/{tenantId}")
  @PreAuthorize("hasAuthority('TENANT_UPDATE')")
  public ApiResponse<TenantDtos.TenantDetail> update(
      @PathVariable UUID tenantId, @Valid @RequestBody TenantDtos.UpdateTenantRequest request) {
    return ApiResponse.ok(tenantService.update(tenantId, request));
  }

  /** Archive a tenant: a soft delete, refused while a lease is running. */
  @PostMapping("/{tenantId}/archive")
  @PreAuthorize("hasAuthority('TENANT_DELETE')")
  public ApiResponse<TenantDtos.TenantDetail> archive(@PathVariable UUID tenantId) {
    return ApiResponse.ok(tenantService.archive(tenantId), "Tenant profile archived");
  }
}
