package com.meridian.realestate.controller;

import com.meridian.realestate.dto.ApiResponse;
import com.meridian.realestate.dto.BuyerDtos;
import com.meridian.realestate.dto.PageResponse;
import com.meridian.realestate.service.BuyerService;
import com.meridian.realestate.web.PageRequestFactory;
import jakarta.validation.Valid;
import java.util.List;
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
 * The buyer register and the briefs (requirements) that state what each buyer is looking for.
 *
 * <p><b>Why this controller exists.</b> A buyer is a client whose brief changes over time, so the
 * brief is its own resource beneath the buyer and is managed on its own endpoints, while the buyer
 * detail carries the read-only projections of the viewings and offers the buyer is party to.</p>
 *
 * <p><b>The rules it protects.</b> No business rule lives here. A brief whose range runs backwards
 * ({@code minBudget > maxBudget} or {@code minBedrooms > maxBedrooms}) is refused as a named 400,
 * and a duplicate buyer code as a 409, both in {@link BuyerService}. This class only routes,
 * validates the body and wraps the answer.</p>
 */
@RestController
@RequestMapping("/api/v1/buyers")
public class BuyerController {

  private static final PageRequestFactory PAGES =
      PageRequestFactory.sortedBy("createdAt,desc")
          .allow("createdAt", "createdAt")
          .allow("displayName", "displayName")
          .allow("buyerCode", "buyerCode")
          .allow("status", "status")
          .allow("city", "city")
          .build();

  private final BuyerService buyerService;

  public BuyerController(BuyerService buyerService) {
    this.buyerService = buyerService;
  }

  /** The buyer register, filtered by status, type and city, with free-text search. */
  @GetMapping
  @PreAuthorize("hasAuthority('BUYER_VIEW')")
  public ApiResponse<PageResponse<BuyerDtos.BuyerSummary>> list(
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String type,
      @RequestParam(required = false) String city,
      @RequestParam(required = false) String search,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size,
      @RequestParam(required = false) String sort) {
    return ApiResponse.ok(
        PageResponse.from(
            buyerService.list(status, type, city, search, PAGES.toPageable(page, size, sort))));
  }

  /** One buyer's full record, with their briefs, viewings and offers. */
  @GetMapping("/{buyerId}")
  @PreAuthorize("hasAuthority('BUYER_VIEW')")
  public ApiResponse<BuyerDtos.BuyerDetail> detail(@PathVariable UUID buyerId) {
    return ApiResponse.ok(buyerService.detail(buyerId));
  }

  /** A buyer's briefs, active first and then the superseded ones as history. */
  @GetMapping("/{buyerId}/requirements")
  @PreAuthorize("hasAuthority('BUYER_VIEW')")
  public ApiResponse<List<BuyerDtos.RequirementRecord>> requirements(@PathVariable UUID buyerId) {
    return ApiResponse.ok(buyerService.requirements(buyerId));
  }

  @PostMapping
  @PreAuthorize("hasAuthority('BUYER_CREATE')")
  public ApiResponse<BuyerDtos.BuyerDetail> create(
      @Valid @RequestBody BuyerDtos.CreateBuyerRequest request) {
    return ApiResponse.ok(buyerService.create(request), "Buyer added");
  }

  @PutMapping("/{buyerId}")
  @PreAuthorize("hasAuthority('BUYER_UPDATE')")
  public ApiResponse<BuyerDtos.BuyerDetail> update(
      @PathVariable UUID buyerId, @Valid @RequestBody BuyerDtos.UpdateBuyerRequest request) {
    return ApiResponse.ok(buyerService.update(buyerId, request));
  }

  /** Archive a buyer: a soft delete. */
  @PostMapping("/{buyerId}/archive")
  @PreAuthorize("hasAuthority('BUYER_DELETE')")
  public ApiResponse<BuyerDtos.BuyerDetail> archive(@PathVariable UUID buyerId) {
    return ApiResponse.ok(buyerService.archive(buyerId), "Buyer archived");
  }

  /** State a brief for a buyer. */
  @PostMapping("/{buyerId}/requirements")
  @PreAuthorize("hasAuthority('BUYER_UPDATE')")
  public ApiResponse<BuyerDtos.RequirementRecord> addRequirement(
      @PathVariable UUID buyerId,
      @Valid @RequestBody BuyerDtos.CreateRequirementRequest request) {
    return ApiResponse.ok(buyerService.createRequirement(buyerId, request), "Requirement added");
  }

  /** Change a brief. Deactivating it is an ordinary change to {@code active}. */
  @PutMapping("/{buyerId}/requirements/{requirementId}")
  @PreAuthorize("hasAuthority('BUYER_UPDATE')")
  public ApiResponse<BuyerDtos.RequirementRecord> updateRequirement(
      @PathVariable UUID buyerId,
      @PathVariable UUID requirementId,
      @Valid @RequestBody BuyerDtos.UpdateRequirementRequest request) {
    return ApiResponse.ok(buyerService.updateRequirement(buyerId, requirementId, request));
  }

  /** Withdraw a brief: a soft delete, which also clears its active flag. */
  @DeleteMapping("/{buyerId}/requirements/{requirementId}")
  @PreAuthorize("hasAuthority('BUYER_UPDATE')")
  public ApiResponse<BuyerDtos.RequirementRecord> archiveRequirement(
      @PathVariable UUID buyerId, @PathVariable UUID requirementId) {
    return ApiResponse.ok(
        buyerService.archiveRequirement(buyerId, requirementId), "Requirement withdrawn");
  }
}
