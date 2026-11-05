package com.meridian.realestate.controller;

import com.meridian.realestate.dto.ApiResponse;
import com.meridian.realestate.dto.DashboardDtos;
import com.meridian.realestate.service.DashboardService;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The dashboard.
 *
 * <p>One endpoint, deliberately: the whole summary is assembled in a single call rather than dribbled
 * out over a dozen requests that each re-derive the same period, so the screen renders once and
 * every tile agrees with every other because they were computed from the same read.</p>
 *
 * <p>Guarded by {@code REPORT_VIEW}, which is the permission the seeded {@code V2} matrix already
 * gives every role that is meant to see the portfolio's figures. That was a decision taken in the
 * permission seed, not one invented here: changing who may see the numbers is a change to a role,
 * not an edit to this class.</p>
 */
@RestController
@RequestMapping("/api/v1/dashboard")
public class DashboardController {

  private final DashboardService dashboardService;

  public DashboardController(DashboardService dashboardService) {
    this.dashboardService = dashboardService;
  }

  /**
   * The portfolio, the desks, the attention lists and the trend.
   *
   * @param asOf the as-of date; null means today. A past date is legitimate and reports what the
   *     portfolio looked like then for the point-in-time figures, with the period figures covering
   *     that date's month.
   */
  @GetMapping("/summary")
  @PreAuthorize("hasAuthority('REPORT_VIEW')")
  public ApiResponse<DashboardDtos.Summary> summary(
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {
    return ApiResponse.ok(dashboardService.summary(asOf));
  }
}
