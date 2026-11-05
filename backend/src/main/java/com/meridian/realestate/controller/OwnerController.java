package com.meridian.realestate.controller;

import com.meridian.realestate.dto.ApiResponse;
import com.meridian.realestate.dto.OwnerDtos;
import com.meridian.realestate.dto.PageResponse;
import com.meridian.realestate.service.OwnerService;
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
 * The owner register.
 *
 * <p><b>Why this controller exists.</b> Owners are the parties whose assets are being managed, and
 * they are read as a register and managed one at a time. The property-ownership sub-resource lives
 * under {@code /api/v1/properties/{propertyId}/ownerships} in its own controller, because a holding
 * is read by the property it is a share of rather than by the owner - and the 100% rule is a rule
 * about a property's holdings, not about one owner.</p>
 *
 * <p><b>The rules it protects.</b> No business rule lives here. The 100% total, the duplicate-owner
 * and duplicate-period refusals, and the soft archive are all decided in {@link OwnerService} and
 * raised as {@link com.meridian.realestate.exception.ApiException}, which the global handler turns
 * into the product's one error envelope. This class only routes, validates the shape of the request
 * body and wraps the answer. No entity and no repository ever appears here.</p>
 *
 * <p>Sorting is honoured through {@link PageRequestFactory}: a caller may sort by the fields the
 * register offers and by nothing else, and an unsupported field is answered with a 400 that names
 * the accepted ones rather than a 500.</p>
 */
@RestController
@RequestMapping("/api/v1/owners")
public class OwnerController {

  private static final PageRequestFactory PAGES =
      PageRequestFactory.sortedBy("createdAt,desc")
          .allow("createdAt", "createdAt")
          .allow("displayName", "displayName")
          .allow("ownerCode", "ownerCode")
          .allow("status", "status")
          .allow("city", "city")
          .build();

  private final OwnerService ownerService;

  public OwnerController(OwnerService ownerService) {
    this.ownerService = ownerService;
  }

  /** The owner register, filtered by status, type and city, with free-text search. */
  @GetMapping
  @PreAuthorize("hasAuthority('OWNER_VIEW')")
  public ApiResponse<PageResponse<OwnerDtos.OwnerSummary>> list(
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String type,
      @RequestParam(required = false) String city,
      @RequestParam(required = false) String search,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size,
      @RequestParam(required = false) String sort) {
    return ApiResponse.ok(
        PageResponse.from(
            ownerService.list(status, type, city, search, PAGES.toPageable(page, size, sort))));
  }

  @GetMapping("/{ownerId}")
  @PreAuthorize("hasAuthority('OWNER_VIEW')")
  public ApiResponse<OwnerDtos.OwnerDetail> detail(@PathVariable UUID ownerId) {
    return ApiResponse.ok(ownerService.detail(ownerId));
  }

  @PostMapping
  @PreAuthorize("hasAuthority('OWNER_CREATE')")
  public ApiResponse<OwnerDtos.OwnerDetail> create(
      @Valid @RequestBody OwnerDtos.CreateOwnerRequest request) {
    return ApiResponse.ok(ownerService.create(request), "Owner added");
  }

  @PutMapping("/{ownerId}")
  @PreAuthorize("hasAuthority('OWNER_UPDATE')")
  public ApiResponse<OwnerDtos.OwnerDetail> update(
      @PathVariable UUID ownerId, @Valid @RequestBody OwnerDtos.UpdateOwnerRequest request) {
    return ApiResponse.ok(ownerService.update(ownerId, request));
  }

  /** Archive an owner: a soft delete. */
  @PostMapping("/{ownerId}/archive")
  @PreAuthorize("hasAuthority('OWNER_DELETE')")
  public ApiResponse<OwnerDtos.OwnerDetail> archive(@PathVariable UUID ownerId) {
    return ApiResponse.ok(ownerService.archive(ownerId), "Owner archived");
  }
}
