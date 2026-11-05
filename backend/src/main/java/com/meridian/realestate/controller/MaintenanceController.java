package com.meridian.realestate.controller;

import com.meridian.realestate.dto.ApiResponse;
import com.meridian.realestate.dto.MaintenanceDtos;
import com.meridian.realestate.dto.PageResponse;
import com.meridian.realestate.service.MaintenanceService;
import com.meridian.realestate.web.PageRequestFactory;
import jakarta.validation.Valid;
import java.time.LocalDate;
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
 * The fault board: raising, assigning, working and resolving maintenance on the stock.
 *
 * <p><b>Why this controller exists.</b> A fault on a property or a unit is the unit of maintenance
 * work, and every operation on it is reachable from one address so the desk works one board rather
 * than five screens that each write a field. This class only routes: the transition map, the
 * empanelment rule, the resolution rules and the late threshold are all decided in
 * {@link MaintenanceService} and raised as an {@link com.meridian.realestate.exception.ApiException},
 * which {@code GlobalExceptionHandler} turns into the product's one error envelope. No entity and no
 * repository appears here.</p>
 *
 * <p><b>Note the shape of this controller: there is no endpoint that writes a status from a request
 * field.</b> A request moves only through {@code POST /maintenance-requests/{id}/status}, which
 * validates the move against the service's transition map and refuses an illegal one with 409.
 * Resolution is its own endpoint ({@code POST /{id}/resolve}) because a resolved request carries the
 * moment it was fixed, what was done and what it cost, and a status endpoint that could write the
 * first two while dropping the third is how a property's maintenance spend becomes untraceable.
 * Assignment is its own endpoint with its own permission, since handing work over is a different
 * decision from working on it.</p>
 *
 * <p><b>Every endpoint carries a permission code that exists in the {@code V2} seed</b>:
 * {@code MAINTENANCE_VIEW}, {@code MAINTENANCE_CREATE}, {@code MAINTENANCE_UPDATE},
 * {@code MAINTENANCE_ASSIGN} and {@code MAINTENANCE_RESOLVE}. No role name appears here, so changing
 * what a role may do is a data change and never a code change.</p>
 *
 * <p>The two read endpoints that are reports rather than rows - {@code /summary} and
 * {@code /cost-trend} - are declared under the same view permission as the board, because they are
 * readings of the same requests; {@code /summary} is declared before {@code /{requestId}} so the path
 * resolves to it rather than being read as an id.</p>
 */
@RestController
@RequestMapping("/api/v1/maintenance-requests")
public class MaintenanceController {

  private static final PageRequestFactory PAGES =
      PageRequestFactory.sortedBy("createdAt,desc")
          .allow("createdAt", "createdAt")
          .allow("updatedAt", "updatedAt")
          .allow("requestCode", "requestCode")
          .allow("reportedAt", "reportedAt")
          .allow("targetDate", "targetDate")
          .allow("title", "title")
          .allow("priority", "priority")
          .allow("status", "status")
          .allow("category", "category")
          .allow("property", "property.name")
          .allow("vendor", "assignedVendor.name")
          .build();

  private final MaintenanceService maintenanceService;

  public MaintenanceController(MaintenanceService maintenanceService) {
    this.maintenanceService = maintenanceService;
  }

  /**
   * The board. Every filter is optional; {@code overdueOnly} narrows it to the work that needs
   * attention, and sort is restricted to the fields the board offers.
   */
  @GetMapping
  @PreAuthorize("hasAuthority('MAINTENANCE_VIEW')")
  public ApiResponse<PageResponse<MaintenanceDtos.MaintenanceSummary>> list(
      @RequestParam(required = false) UUID propertyId,
      @RequestParam(required = false) UUID unitId,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String priority,
      @RequestParam(required = false) String category,
      @RequestParam(required = false) UUID assignedVendorId,
      @RequestParam(required = false) UUID assignedToId,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate reportedFrom,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate reportedTo,
      @RequestParam(required = false) Boolean overdueOnly,
      @RequestParam(required = false) String search,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size,
      @RequestParam(required = false) String sort) {
    return ApiResponse.ok(
        maintenanceService.list(
            propertyId,
            unitId,
            status,
            priority,
            category,
            assignedVendorId,
            assignedToId,
            reportedFrom,
            reportedTo,
            overdueOnly,
            search,
            PAGES.toPageable(page, size, sort)));
  }

  /** The operations card: outstanding work, the urgent queue and what maintenance has cost. */
  @GetMapping("/summary")
  @PreAuthorize("hasAuthority('MAINTENANCE_VIEW')")
  public ApiResponse<MaintenanceDtos.MaintenanceSummaryResponse> summary() {
    return ApiResponse.ok(maintenanceService.summary());
  }

  /** What maintenance has cost, month by month. Defaults to the configured trend window. */
  @GetMapping("/cost-trend")
  @PreAuthorize("hasAuthority('MAINTENANCE_VIEW')")
  public ApiResponse<MaintenanceDtos.CostTrendResponse> costTrend(
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
    return ApiResponse.ok(maintenanceService.costTrend(from, to));
  }

  /** One request, with its status trail. */
  @GetMapping("/{requestId}")
  @PreAuthorize("hasAuthority('MAINTENANCE_VIEW')")
  public ApiResponse<MaintenanceDtos.MaintenanceDetail> detail(@PathVariable UUID requestId) {
    return ApiResponse.ok(maintenanceService.detail(requestId));
  }

  /** Raise a fault. Every request starts at OPEN. */
  @PostMapping
  @PreAuthorize("hasAuthority('MAINTENANCE_CREATE')")
  public ApiResponse<MaintenanceDtos.MaintenanceDetail> create(
      @Valid @RequestBody MaintenanceDtos.CreateMaintenanceRequest request) {
    return ApiResponse.ok(maintenanceService.create(request), "Maintenance request raised");
  }

  /** Change a request's details. The status has its own endpoint. */
  @PutMapping("/{requestId}")
  @PreAuthorize("hasAuthority('MAINTENANCE_UPDATE')")
  public ApiResponse<MaintenanceDtos.MaintenanceDetail> update(
      @PathVariable UUID requestId,
      @Valid @RequestBody MaintenanceDtos.UpdateMaintenanceRequest request) {
    return ApiResponse.ok(maintenanceService.update(requestId, request));
  }

  /** Hand the work to an empanelled vendor or a colleague. */
  @PostMapping("/{requestId}/assign")
  @PreAuthorize("hasAuthority('MAINTENANCE_ASSIGN')")
  public ApiResponse<MaintenanceDtos.MaintenanceDetail> assign(
      @PathVariable UUID requestId,
      @Valid @RequestBody MaintenanceDtos.AssignMaintenanceRequest request) {
    return ApiResponse.ok(maintenanceService.assign(requestId, request), "Maintenance assigned");
  }

  /** Move a request between statuses. A move to RESOLVED is refused here; use the resolve endpoint. */
  @PostMapping("/{requestId}/status")
  @PreAuthorize("hasAuthority('MAINTENANCE_UPDATE')")
  public ApiResponse<MaintenanceDtos.MaintenanceDetail> transition(
      @PathVariable UUID requestId,
      @Valid @RequestBody MaintenanceDtos.StatusTransitionRequest request) {
    return ApiResponse.ok(
        maintenanceService.transition(requestId, request), "Maintenance status changed");
  }

  /** Record what was done about a fault and what it cost. */
  @PostMapping("/{requestId}/resolve")
  @PreAuthorize("hasAuthority('MAINTENANCE_RESOLVE')")
  public ApiResponse<MaintenanceDtos.MaintenanceDetail> resolve(
      @PathVariable UUID requestId,
      @Valid @RequestBody MaintenanceDtos.ResolveMaintenanceRequest request) {
    return ApiResponse.ok(maintenanceService.resolve(requestId, request), "Maintenance resolved");
  }
}
