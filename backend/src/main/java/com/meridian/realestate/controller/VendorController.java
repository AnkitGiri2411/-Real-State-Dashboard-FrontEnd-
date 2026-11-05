package com.meridian.realestate.controller;

import com.meridian.realestate.dto.ApiResponse;
import com.meridian.realestate.dto.PageResponse;
import com.meridian.realestate.dto.VendorDtos;
import com.meridian.realestate.service.VendorService;
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
 * The empanelled trades the firm calls out to do work.
 *
 * <p><b>Why this controller exists.</b> A maintenance request is handed to a vendor, so the register
 * of who may be handed what has to be reachable from the same module as the fault board rather than
 * buried in settings. This class only routes: every rule - that approval is per trade, that a stopped
 * vendor may not be given new work, that archiving is soft and once - is decided in
 * {@link VendorService} and raised as an {@link com.meridian.realestate.exception.ApiException}, which
 * {@code GlobalExceptionHandler} turns into the product's one error envelope. No entity and no
 * repository appears here.</p>
 *
 * <p><b>The permission codes.</b> The vendor register belongs to the maintenance module - {@code V14}
 * creates the two tables together, and the {@code V2} seed has no {@code VENDOR_*} codes at all,
 * because the firm's approved trades are part of the stock's condition rather than a separate area of
 * the product. The codes used here are therefore the maintenance ones: {@code MAINTENANCE_VIEW} to
 * read the register, {@code MAINTENANCE_CREATE} to add a trade, and {@code MAINTENANCE_UPDATE} to
 * change or archive one. That last pairing is deliberate rather than accidental - archiving a vendor
 * takes them off the list of trades new work may be handed to, which is an edit to the register, and
 * the {@code MAINTENANCE} role holds all three. No code is invented: every one exists in the
 * {@code V2} seed, and no role name appears anywhere in this class, so changing what a role may do is
 * a data change and never a code change.</p>
 *
 * <p>Sorting is honoured through {@link PageRequestFactory}: a caller may sort by the fields the
 * register offers and by nothing else, and an unsupported field is answered with a 400 that names the
 * accepted ones rather than a 500.</p>
 */
@RestController
@RequestMapping("/api/v1/vendors")
public class VendorController {

  private static final PageRequestFactory PAGES =
      PageRequestFactory.sortedBy("createdAt,desc")
          .allow("createdAt", "createdAt")
          .allow("updatedAt", "updatedAt")
          .allow("vendorCode", "vendorCode")
          .allow("name", "name")
          .allow("category", "category")
          .allow("status", "status")
          .allow("city", "city")
          .allow("rating", "rating")
          .build();

  private final VendorService vendorService;

  public VendorController(VendorService vendorService) {
    this.vendorService = vendorService;
  }

  /** The register, filtered by trade, approval and status, with free-text search. */
  @GetMapping
  @PreAuthorize("hasAuthority('MAINTENANCE_VIEW')")
  public ApiResponse<PageResponse<VendorDtos.VendorSummary>> list(
      @RequestParam(required = false) String category,
      @RequestParam(required = false) Boolean empanelled,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String search,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size,
      @RequestParam(required = false) String sort) {
    return ApiResponse.ok(
        vendorService.list(category, empanelled, status, search, PAGES.toPageable(page, size, sort)));
  }

  /** One trade's full record. */
  @GetMapping("/{vendorId}")
  @PreAuthorize("hasAuthority('MAINTENANCE_VIEW')")
  public ApiResponse<VendorDtos.VendorDetail> detail(@PathVariable UUID vendorId) {
    return ApiResponse.ok(vendorService.detail(vendorId));
  }

  /** Add a trade to the register. The vendor code is minted, never supplied. */
  @PostMapping
  @PreAuthorize("hasAuthority('MAINTENANCE_CREATE')")
  public ApiResponse<VendorDtos.VendorDetail> create(
      @Valid @RequestBody VendorDtos.CreateVendorRequest request) {
    return ApiResponse.ok(vendorService.create(request), "Vendor added");
  }

  /** Change a trade's details, including whether it is on the approved list. */
  @PutMapping("/{vendorId}")
  @PreAuthorize("hasAuthority('MAINTENANCE_UPDATE')")
  public ApiResponse<VendorDtos.VendorDetail> update(
      @PathVariable UUID vendorId, @Valid @RequestBody VendorDtos.UpdateVendorRequest request) {
    return ApiResponse.ok(vendorService.update(vendorId, request));
  }

  /** Archive a trade: a soft delete, so the work it already did stays readable. */
  @PostMapping("/{vendorId}/archive")
  @PreAuthorize("hasAuthority('MAINTENANCE_UPDATE')")
  public ApiResponse<VendorDtos.VendorDetail> archive(@PathVariable UUID vendorId) {
    return ApiResponse.ok(vendorService.archive(vendorId), "Vendor archived");
  }
}
