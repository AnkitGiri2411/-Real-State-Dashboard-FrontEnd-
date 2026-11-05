package com.meridian.realestate.controller;

import com.meridian.realestate.dto.ApiResponse;
import com.meridian.realestate.dto.ApplicationDtos;
import com.meridian.realestate.dto.ListingDtos;
import com.meridian.realestate.dto.PageResponse;
import com.meridian.realestate.dto.ViewingDtos;
import com.meridian.realestate.service.ListingService;
import com.meridian.realestate.web.PageRequestFactory;
import jakarta.validation.Valid;
import java.util.List;
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
 * The listing board.
 *
 * <p>There is no endpoint that writes a publication state from a request field alone. A listing
 * becomes PUBLISHED, WITHDRAWN, EXPIRED, UNDER_OFFER and so on only through the operation that means
 * it, and each validates the move against the service's listing lifecycle. Publishing something
 * already live on the board is refused.</p>
 */
@RestController
@RequestMapping("/api/v1/listings")
public class ListingController {

  private static final PageRequestFactory PAGES =
      PageRequestFactory.sortedBy("createdAt,desc")
          .allow("createdAt", "createdAt")
          .allow("updatedAt", "updatedAt")
          .allow("listingCode", "listingCode")
          .allow("headline", "headline")
          .allow("status", "status")
          .allow("listingType", "listingType")
          .allow("listingStart", "listingStart")
          .allow("askingRent", "askingRent")
          .allow("askingPrice", "askingPrice")
          .allow("property", "property.name")
          .allow("unit", "unit.unitNumber")
          .allow("agent", "listedBy.agentCode")
          .allow("channel", "channel.name")
          .build();

  private final ListingService listingService;

  public ListingController(ListingService listingService) {
    this.listingService = listingService;
  }

  /** The board. Every filter is optional; sort is restricted to the fields offered. */
  @GetMapping
  @PreAuthorize("hasAuthority('LISTING_VIEW')")
  public ApiResponse<PageResponse<ListingDtos.ListingSummary>> list(
      @RequestParam(required = false) UUID propertyId,
      @RequestParam(required = false) UUID unitId,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String type,
      @RequestParam(required = false) String channel,
      @RequestParam(required = false) UUID agentId,
      @RequestParam(required = false) Boolean staleOnly,
      @RequestParam(required = false) String search,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size,
      @RequestParam(required = false) String sort) {
    return ApiResponse.ok(
        listingService.list(
            propertyId, unitId, status, type, channel, agentId, staleOnly, search,
            PAGES.toPageable(page, size, sort)));
  }

  /** One listing, with its status trail, viewings and applications. */
  @GetMapping("/{listingId}")
  @PreAuthorize("hasAuthority('LISTING_VIEW')")
  public ApiResponse<ListingDtos.ListingDetail> detail(@PathVariable UUID listingId) {
    return ApiResponse.ok(listingService.detail(listingId));
  }

  /** The publication-state trail of one listing. */
  @GetMapping("/{listingId}/history")
  @PreAuthorize("hasAuthority('LISTING_VIEW')")
  public ApiResponse<List<ListingDtos.ListingStatusHistoryEntry>> history(@PathVariable UUID listingId) {
    return ApiResponse.ok(listingService.statusHistory(listingId));
  }

  /** The viewings booked on the stock this listing offers. */
  @GetMapping("/{listingId}/viewings")
  @PreAuthorize("hasAuthority('LISTING_VIEW')")
  public ApiResponse<List<ViewingDtos.ViewingSummary>> viewings(@PathVariable UUID listingId) {
    return ApiResponse.ok(listingService.viewingsFor(listingId));
  }

  /** The applications taken on the stock this listing offers. */
  @GetMapping("/{listingId}/applications")
  @PreAuthorize("hasAuthority('LISTING_VIEW')")
  public ApiResponse<List<ApplicationDtos.ApplicationSummary>> applications(@PathVariable UUID listingId) {
    return ApiResponse.ok(listingService.applicationsFor(listingId));
  }

  /** Create a listing. It starts as a DRAFT. */
  @PostMapping
  @PreAuthorize("hasAuthority('LISTING_CREATE')")
  public ApiResponse<ListingDtos.ListingDetail> create(
      @Valid @RequestBody ListingDtos.CreateListingRequest request) {
    return ApiResponse.ok(listingService.create(request), "Listing drafted");
  }

  /** Change a listing's details. */
  @PutMapping("/{listingId}")
  @PreAuthorize("hasAuthority('LISTING_UPDATE')")
  public ApiResponse<ListingDtos.ListingDetail> update(
      @PathVariable UUID listingId, @Valid @RequestBody ListingDtos.UpdateListingRequest request) {
    return ApiResponse.ok(listingService.update(listingId, request));
  }

  /** Publish a listing onto the market. */
  @PostMapping("/{listingId}/publish")
  @PreAuthorize("hasAuthority('LISTING_PUBLISH')")
  public ApiResponse<ListingDtos.ListingDetail> publish(
      @PathVariable UUID listingId,
      @RequestBody(required = false) ListingDtos.ListingActionRequest request) {
    return ApiResponse.ok(listingService.publish(listingId, request), "Listing published");
  }

  /** Withdraw a live listing from the market. */
  @PostMapping("/{listingId}/unpublish")
  @PreAuthorize("hasAuthority('LISTING_PUBLISH')")
  public ApiResponse<ListingDtos.ListingDetail> unpublish(
      @PathVariable UUID listingId,
      @RequestBody(required = false) ListingDtos.ListingActionRequest request) {
    return ApiResponse.ok(listingService.unpublish(listingId, request), "Listing withdrawn");
  }

  /** Close a live listing as expired. */
  @PostMapping("/{listingId}/expire")
  @PreAuthorize("hasAuthority('LISTING_PUBLISH')")
  public ApiResponse<ListingDtos.ListingDetail> expire(
      @PathVariable UUID listingId,
      @RequestBody(required = false) ListingDtos.ListingActionRequest request) {
    return ApiResponse.ok(listingService.expire(listingId, request), "Listing expired");
  }

  /** Move a listing to any state the lifecycle permits - UNDER_OFFER, LET_AGREED, SOLD and so on. */
  @PutMapping("/{listingId}/status")
  @PreAuthorize("hasAuthority('LISTING_PUBLISH')")
  public ApiResponse<ListingDtos.ListingDetail> changeStatus(
      @PathVariable UUID listingId, @Valid @RequestBody ListingDtos.ListingStatusChangeRequest request) {
    return ApiResponse.ok(listingService.changeStatus(listingId, request), "Listing status changed");
  }
}
