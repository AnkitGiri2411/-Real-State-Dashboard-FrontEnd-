package com.meridian.realestate.controller;

import com.meridian.realestate.dto.ApiResponse;
import com.meridian.realestate.dto.BuildingDtos;
import com.meridian.realestate.dto.PageResponse;
import com.meridian.realestate.dto.PropertyDtos;
import com.meridian.realestate.dto.UnitDtos;
import com.meridian.realestate.service.PropertyService;
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
 * The property register and everything that hangs off a property.
 *
 * <p><b>Why this controller exists.</b> The register is one resource with a handful of sub-resources
 * that all read the same asset: its buildings, its units, its amenities (and attaching and
 * detaching them), its ownership shares, its document count, its status history and its metrics
 * projection. They live under one base path because a caller reads them by property, and because the
 * property is the record the whole register hangs off.</p>
 *
 * <p><b>The rules it protects.</b> No business rule lives here. A position that is half a coordinate
 * pair, an asset with running leases that cannot be archived, a terminal asset that cannot move, a
 * duplicate building code - all of those are decided in the services and raised as
 * {@link com.meridian.realestate.exception.ApiException}, which {@code GlobalExceptionHandler}
 * turns into the product's one error envelope. This class only routes, validates the shape of the
 * request body and wraps the answer in {@link ApiResponse}. No entity and no repository ever appears
 * here.</p>
 *
 * <p>Sorting is honoured through {@link PageRequestFactory}: a caller may sort by the fields the
 * register offers and by nothing else, and an unsupported field is answered with a 400 that names
 * the accepted ones rather than a 500.</p>
 */
@RestController
@RequestMapping("/api/v1/properties")
public class PropertyController {

  private static final PageRequestFactory PAGES =
      PageRequestFactory.sortedBy("createdAt,desc")
          .allow("createdAt", "createdAt")
          .allow("name", "name")
          .allow("propertyCode", "propertyCode")
          .allow("city", "city")
          .allow("status", "status")
          .allow("currentValuation", "currentValuation")
          .build();

  /**
   * The sort whitelist for the units under a property.
   *
   * <p>Deliberately separate from {@link #PAGES}: this sub-resource is a paged unit query, so it may
   * only be sorted by fields a {@code Unit} actually has. Offering the register's fields here would
   * let a caller name an order the query cannot apply.</p>
   */
  private static final PageRequestFactory UNIT_PAGES =
      PageRequestFactory.sortedBy("unitNumber,asc")
          .allow("unitNumber", "unitNumber")
          .allow("unitCode", "unitCode")
          .allow("floorNumber", "floorNumber")
          .allow("bedrooms", "bedrooms")
          .allow("status", "status")
          .allow("createdAt", "createdAt")
          .build();

  private final PropertyService propertyService;

  public PropertyController(PropertyService propertyService) {
    this.propertyService = propertyService;
  }

  /** The register, filtered by status, type, city, owner and agent, with free-text search. */
  @GetMapping
  @PreAuthorize("hasAuthority('PROPERTY_VIEW')")
  public ApiResponse<PageResponse<PropertyDtos.PropertySummary>> list(
      @RequestParam(required = false) String status,
      @RequestParam(required = false) UUID propertyTypeId,
      @RequestParam(required = false) String city,
      @RequestParam(required = false) UUID ownerId,
      @RequestParam(required = false) UUID agentId,
      @RequestParam(required = false) String search,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size,
      @RequestParam(required = false) String sort) {
    return ApiResponse.ok(
        PageResponse.from(
            propertyService.list(
                status,
                propertyTypeId,
                city,
                ownerId,
                agentId,
                search,
                PAGES.toPageable(page, size, sort))));
  }

  /**
   * The portfolio map.
   *
   * <p>Only properties that carry a real coordinate pair are plotted; the service never invents a
   * position for one whose position is not known.</p>
   */
  @GetMapping("/map")
  @PreAuthorize("hasAuthority('PROPERTY_VIEW')")
  public ApiResponse<List<PropertyDtos.MapMarker>> map() {
    return ApiResponse.ok(propertyService.map());
  }

  @GetMapping("/{propertyId}")
  @PreAuthorize("hasAuthority('PROPERTY_VIEW')")
  public ApiResponse<PropertyDtos.PropertyDetail> detail(@PathVariable UUID propertyId) {
    return ApiResponse.ok(propertyService.detail(propertyId));
  }

  /** One property's occupancy, rent roll, valuation and areas. */
  @GetMapping("/{propertyId}/metrics")
  @PreAuthorize("hasAuthority('PROPERTY_VIEW')")
  public ApiResponse<PropertyDtos.PropertyMetrics> metrics(@PathVariable UUID propertyId) {
    return ApiResponse.ok(propertyService.metrics(propertyId));
  }

  /** The blocks within a property. */
  @GetMapping("/{propertyId}/buildings")
  @PreAuthorize("hasAuthority('BUILDING_VIEW')")
  public ApiResponse<List<BuildingDtos.BuildingSummary>> buildings(@PathVariable UUID propertyId) {
    return ApiResponse.ok(propertyService.buildings(propertyId));
  }

  /** A paged view of a property's units. */
  @GetMapping("/{propertyId}/units")
  @PreAuthorize("hasAuthority('UNIT_VIEW')")
  public ApiResponse<PageResponse<UnitDtos.UnitSummary>> units(
      @PathVariable UUID propertyId,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size,
      @RequestParam(required = false) String sort) {
    return ApiResponse.ok(
        PageResponse.from(
            propertyService.units(propertyId, UNIT_PAGES.toPageable(page, size, sort))));
  }

  /** The facilities a property offers. */
  @GetMapping("/{propertyId}/amenities")
  @PreAuthorize("hasAuthority('PROPERTY_VIEW')")
  public ApiResponse<List<PropertyDtos.AmenityLink>> amenities(@PathVariable UUID propertyId) {
    return ApiResponse.ok(propertyService.amenities(propertyId));
  }

  /** Attach a facility to a property. */
  @PostMapping("/{propertyId}/amenities")
  @PreAuthorize("hasAuthority('PROPERTY_UPDATE')")
  public ApiResponse<List<PropertyDtos.AmenityLink>> attachAmenity(
      @PathVariable UUID propertyId,
      @Valid @RequestBody PropertyDtos.AttachAmenityRequest request) {
    return ApiResponse.ok(
        propertyService.attachAmenity(propertyId, request), "Amenity attached");
  }

  /** Detach a facility from a property. */
  @DeleteMapping("/{propertyId}/amenities/{amenityId}")
  @PreAuthorize("hasAuthority('PROPERTY_UPDATE')")
  public ApiResponse<Void> detachAmenity(
      @PathVariable UUID propertyId, @PathVariable UUID amenityId) {
    propertyService.detachAmenity(propertyId, amenityId);
    return ApiResponse.ok(null, "Amenity detached");
  }

  /** Who owns this property. Read-only; the ownership slice owns the writes. */
  @GetMapping("/{propertyId}/ownership")
  @PreAuthorize("hasAuthority('OWNER_VIEW')")
  public ApiResponse<List<PropertyDtos.OwnershipShare>> ownership(@PathVariable UUID propertyId) {
    return ApiResponse.ok(propertyService.ownership(propertyId));
  }

  /** How many documents are filed against this property. */
  @GetMapping("/{propertyId}/documents")
  @PreAuthorize("hasAuthority('DOCUMENT_VIEW')")
  public ApiResponse<PropertyDtos.DocumentCount> documents(@PathVariable UUID propertyId) {
    return ApiResponse.ok(propertyService.documents(propertyId));
  }

  /** The property's status timeline. */
  @GetMapping("/{propertyId}/status-history")
  @PreAuthorize("hasAuthority('PROPERTY_VIEW')")
  public ApiResponse<List<PropertyDtos.StatusHistoryEntry>> statusHistory(
      @PathVariable UUID propertyId) {
    return ApiResponse.ok(propertyService.statusHistory(propertyId));
  }

  @PostMapping
  @PreAuthorize("hasAuthority('PROPERTY_CREATE')")
  public ApiResponse<PropertyDtos.PropertyDetail> create(
      @Valid @RequestBody PropertyDtos.CreatePropertyRequest request) {
    return ApiResponse.ok(propertyService.create(request), "Property added");
  }

  @PutMapping("/{propertyId}")
  @PreAuthorize("hasAuthority('PROPERTY_UPDATE')")
  public ApiResponse<PropertyDtos.PropertyDetail> update(
      @PathVariable UUID propertyId,
      @Valid @RequestBody PropertyDtos.UpdatePropertyRequest request) {
    return ApiResponse.ok(propertyService.update(propertyId, request));
  }

  /** Archive a property: a soft delete, refused while running leases remain. */
  @PostMapping("/{propertyId}/archive")
  @PreAuthorize("hasAuthority('PROPERTY_DELETE')")
  public ApiResponse<PropertyDtos.PropertyDetail> archive(@PathVariable UUID propertyId) {
    return ApiResponse.ok(propertyService.archive(propertyId), "Property archived");
  }

  /** Move a property between lifecycle states, recording the transition. */
  @PostMapping("/{propertyId}/status")
  @PreAuthorize("hasAuthority('PROPERTY_STATUS_CHANGE')")
  public ApiResponse<PropertyDtos.PropertyDetail> changeStatus(
      @PathVariable UUID propertyId,
      @Valid @RequestBody PropertyDtos.ChangeStatusRequest request) {
    return ApiResponse.ok(
        propertyService.changeStatus(propertyId, request), "Property status changed");
  }
}
