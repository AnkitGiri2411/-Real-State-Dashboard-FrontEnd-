package com.meridian.realestate.controller;

import com.meridian.realestate.dto.ApiResponse;
import com.meridian.realestate.dto.PageResponse;
import com.meridian.realestate.dto.UnitDtos;
import com.meridian.realestate.service.UnitService;
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
 * The unit register, the unit board and the occupancy figures.
 *
 * <p><b>Why this controller exists.</b> Units are the busiest read in the product and they answer
 * three different questions the desk asks in three different shapes: a filtered list of units, the
 * board (a property's units grouped by building and floor), and the occupancy count for a property.
 * {@code /board} and {@code /occupancy} are their own endpoints rather than filtered {@code /units}
 * calls because each carries a shape the list does not - the grouped stack, and the counts beside
 * them.</p>
 *
 * <p><b>The rules it protects.</b> Marking a non-lettable unit as let, or a unit that already
 * carries a running lease, is refused in {@link UnitService} as
 * {@code UNIT_NOT_LEASABLE} / {@code UNIT_ALREADY_LET}; a status move is recorded in the unit's
 * history; a no-op move is not. This class routes, validates the body and wraps the answer, and
 * never touches a repository or an entity.</p>
 */
@RestController
@RequestMapping("/api/v1/units")
public class UnitController {

  private static final PageRequestFactory PAGES =
      PageRequestFactory.sortedBy("unitNumber,asc")
          .allow("unitNumber", "unitNumber")
          .allow("unitCode", "unitCode")
          .allow("floorNumber", "floorNumber")
          .allow("bedrooms", "bedrooms")
          .allow("status", "status")
          .allow("askingRent", "askingRent")
          .allow("createdAt", "createdAt")
          .build();

  private final UnitService unitService;

  public UnitController(UnitService unitService) {
    this.unitService = unitService;
  }

  /** The unit register, filtered by property, building, type, status, leasable, floor and bedrooms. */
  @GetMapping
  @PreAuthorize("hasAuthority('UNIT_VIEW')")
  public ApiResponse<PageResponse<UnitDtos.UnitSummary>> list(
      @RequestParam(required = false) UUID propertyId,
      @RequestParam(required = false) UUID buildingId,
      @RequestParam(required = false) UUID unitTypeId,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) Boolean leasable,
      @RequestParam(required = false) Integer floor,
      @RequestParam(required = false) Integer bedrooms,
      @RequestParam(required = false) String search,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size,
      @RequestParam(required = false) String sort) {
    return ApiResponse.ok(
        PageResponse.from(
            unitService.list(
                propertyId,
                buildingId,
                unitTypeId,
                status,
                leasable,
                floor,
                bedrooms,
                search,
                PAGES.toPageable(page, size, sort))));
  }

  /** The unit board: a property's units grouped by building and then by floor. */
  @GetMapping("/board")
  @PreAuthorize("hasAuthority('UNIT_VIEW')")
  public ApiResponse<UnitDtos.UnitBoard> board(@RequestParam UUID propertyId) {
    return ApiResponse.ok(unitService.board(propertyId));
  }

  /** The occupancy figures for a property. */
  @GetMapping("/occupancy")
  @PreAuthorize("hasAuthority('UNIT_VIEW')")
  public ApiResponse<UnitDtos.UnitOccupancy> occupancy(@RequestParam UUID propertyId) {
    return ApiResponse.ok(unitService.occupancy(propertyId));
  }

  @GetMapping("/{unitId}")
  @PreAuthorize("hasAuthority('UNIT_VIEW')")
  public ApiResponse<UnitDtos.UnitDetail> detail(@PathVariable UUID unitId) {
    return ApiResponse.ok(unitService.detail(unitId));
  }

  /** The lease currently in force on a unit, as a read-only summary. */
  @GetMapping("/{unitId}/lease")
  @PreAuthorize("hasAuthority('LEASE_VIEW')")
  public ApiResponse<UnitDtos.UnitLeaseSummary> lease(@PathVariable UUID unitId) {
    return ApiResponse.ok(unitService.leaseSummary(unitId));
  }

  @PostMapping
  @PreAuthorize("hasAuthority('UNIT_CREATE')")
  public ApiResponse<UnitDtos.UnitDetail> create(
      @Valid @RequestBody UnitDtos.CreateUnitRequest request) {
    return ApiResponse.ok(unitService.create(request), "Unit added");
  }

  @PutMapping("/{unitId}")
  @PreAuthorize("hasAuthority('UNIT_UPDATE')")
  public ApiResponse<UnitDtos.UnitDetail> update(
      @PathVariable UUID unitId, @Valid @RequestBody UnitDtos.UpdateUnitRequest request) {
    return ApiResponse.ok(unitService.update(unitId, request));
  }

  /** Archive a unit: a soft delete, refused while a running lease remains. */
  @PostMapping("/{unitId}/archive")
  @PreAuthorize("hasAuthority('UNIT_DELETE')")
  public ApiResponse<UnitDtos.UnitDetail> archive(@PathVariable UUID unitId) {
    return ApiResponse.ok(unitService.archive(unitId), "Unit archived");
  }

  /** Move a unit between lifecycle states, recording the transition. */
  @PostMapping("/{unitId}/status")
  @PreAuthorize("hasAuthority('UNIT_STATUS_CHANGE')")
  public ApiResponse<UnitDtos.UnitDetail> changeStatus(
      @PathVariable UUID unitId, @Valid @RequestBody UnitDtos.ChangeStatusRequest request) {
    return ApiResponse.ok(unitService.changeStatus(unitId, request), "Unit status changed");
  }
}
