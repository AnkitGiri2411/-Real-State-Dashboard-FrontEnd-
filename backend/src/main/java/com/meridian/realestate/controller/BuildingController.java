package com.meridian.realestate.controller;

import com.meridian.realestate.dto.ApiResponse;
import com.meridian.realestate.dto.BuildingDtos;
import com.meridian.realestate.dto.PageResponse;
import com.meridian.realestate.service.BuildingService;
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
 * The buildings (blocks) within the properties.
 *
 * <p><b>Why this controller exists.</b> A block is addressable in its own right - the desk renames
 * one, checks how many units sit in it, archives one that has been demolished - so it has its own
 * base path rather than living only under a property. It can still be filtered by property, which is
 * how the unit board's grouping is populated.</p>
 *
 * <p>The rules are the service's: a code unique within its property, and no archiving a block that
 * still has units. This class routes, validates the body and wraps the answer; it holds no business
 * logic and never touches a repository. An unsupported sort field is a 400 from
 * {@link com.meridian.realestate.web.PageRequestFactory}, not a 500.</p>
 */
@RestController
@RequestMapping("/api/v1/buildings")
public class BuildingController {

  private static final PageRequestFactory PAGES =
      PageRequestFactory.sortedBy("name,asc")
          .allow("name", "name")
          .allow("buildingCode", "buildingCode")
          .allow("floors", "floors")
          .allow("createdAt", "createdAt")
          .build();

  private final BuildingService buildingService;

  public BuildingController(BuildingService buildingService) {
    this.buildingService = buildingService;
  }

  /** The buildings, optionally narrowed to one property. */
  @GetMapping
  @PreAuthorize("hasAuthority('BUILDING_VIEW')")
  public ApiResponse<PageResponse<BuildingDtos.BuildingSummary>> list(
      @RequestParam(required = false) UUID propertyId,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size,
      @RequestParam(required = false) String sort) {
    return ApiResponse.ok(
        PageResponse.from(
            buildingService.list(propertyId, PAGES.toPageable(page, size, sort))));
  }

  @GetMapping("/{buildingId}")
  @PreAuthorize("hasAuthority('BUILDING_VIEW')")
  public ApiResponse<BuildingDtos.BuildingDetail> detail(@PathVariable UUID buildingId) {
    return ApiResponse.ok(buildingService.detail(buildingId));
  }

  @PostMapping
  @PreAuthorize("hasAuthority('BUILDING_CREATE')")
  public ApiResponse<BuildingDtos.BuildingDetail> create(
      @Valid @RequestBody BuildingDtos.CreateBuildingRequest request) {
    return ApiResponse.ok(buildingService.create(request), "Building added");
  }

  @PutMapping("/{buildingId}")
  @PreAuthorize("hasAuthority('BUILDING_UPDATE')")
  public ApiResponse<BuildingDtos.BuildingDetail> update(
      @PathVariable UUID buildingId,
      @Valid @RequestBody BuildingDtos.UpdateBuildingRequest request) {
    return ApiResponse.ok(buildingService.update(buildingId, request));
  }

  /** Archive a building: a soft delete, refused while units still sit in it. */
  @PostMapping("/{buildingId}/archive")
  @PreAuthorize("hasAuthority('BUILDING_DELETE')")
  public ApiResponse<BuildingDtos.BuildingDetail> archive(@PathVariable UUID buildingId) {
    return ApiResponse.ok(buildingService.archive(buildingId), "Building archived");
  }
}
