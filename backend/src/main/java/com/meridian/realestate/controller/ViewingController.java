package com.meridian.realestate.controller;

import com.meridian.realestate.dto.ApiResponse;
import com.meridian.realestate.dto.PageResponse;
import com.meridian.realestate.dto.ViewingDtos;
import com.meridian.realestate.service.ViewingService;
import com.meridian.realestate.web.PageRequestFactory;
import jakarta.validation.Valid;
import java.time.LocalDateTime;
import java.util.List;
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
 * The viewing diary.
 *
 * <p>There is no endpoint that writes a status from a request field. A viewing becomes CONFIRMED,
 * COMPLETED, CANCELLED or a NO_SHOW only through the operation that means it, and each writes the
 * status and its outcome columns together - which is what the schema's outcome constraint requires.
 * Completing without feedback is refused, and cancelling an already-cancelled viewing is refused.</p>
 */
@RestController
@RequestMapping("/api/v1/viewings")
public class ViewingController {

  private static final PageRequestFactory PAGES =
      PageRequestFactory.sortedBy("scheduledAt,desc")
          .allow("scheduledAt", "scheduledAt")
          .allow("createdAt", "createdAt")
          .allow("updatedAt", "updatedAt")
          .allow("status", "status")
          .allow("viewingCode", "viewingCode")
          .allow("agent", "agent.agentCode")
          .allow("property", "property.name")
          .build();

  private final ViewingService viewingService;

  public ViewingController(ViewingService viewingService) {
    this.viewingService = viewingService;
  }

  /** The diary list. Every filter is optional; sort is restricted to the fields offered. */
  @GetMapping
  @PreAuthorize("hasAuthority('VIEWING_VIEW')")
  public ApiResponse<PageResponse<ViewingDtos.ViewingSummary>> list(
      @RequestParam(required = false) UUID agentId,
      @RequestParam(required = false) UUID propertyId,
      @RequestParam(required = false) UUID leadId,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size,
      @RequestParam(required = false) String sort) {
    return ApiResponse.ok(
        viewingService.list(agentId, propertyId, leadId, status, from, to, PAGES.toPageable(page, size, sort)));
  }

  /** What is still ahead of the reader, soonest first. */
  @GetMapping("/diary")
  @PreAuthorize("hasAuthority('VIEWING_VIEW')")
  public ApiResponse<List<ViewingDtos.ViewingSummary>> diary(
      @RequestParam(required = false) UUID agentId) {
    return ApiResponse.ok(viewingService.diary(agentId));
  }

  /** The viewings held for one lead - its viewing history. */
  @GetMapping("/by-lead/{leadId}")
  @PreAuthorize("hasAuthority('VIEWING_VIEW')")
  public ApiResponse<List<ViewingDtos.ViewingSummary>> forLead(@PathVariable UUID leadId) {
    return ApiResponse.ok(viewingService.forLead(leadId));
  }

  /** One viewing, with the moves that are legal from where it stands. */
  @GetMapping("/{viewingId}")
  @PreAuthorize("hasAuthority('VIEWING_VIEW')")
  public ApiResponse<ViewingDtos.ViewingDetail> detail(@PathVariable UUID viewingId) {
    return ApiResponse.ok(viewingService.detail(viewingId));
  }

  /** Book a viewing. */
  @PostMapping
  @PreAuthorize("hasAuthority('VIEWING_CREATE')")
  public ApiResponse<ViewingDtos.ViewingDetail> schedule(
      @Valid @RequestBody ViewingDtos.ScheduleViewingRequest request) {
    return ApiResponse.ok(viewingService.schedule(request), "Viewing scheduled");
  }

  /** Move a booked viewing to a new time, or change who runs it. */
  @PutMapping("/{viewingId}")
  @PreAuthorize("hasAuthority('VIEWING_UPDATE')")
  public ApiResponse<ViewingDtos.ViewingDetail> reschedule(
      @PathVariable UUID viewingId, @Valid @RequestBody ViewingDtos.RescheduleViewingRequest request) {
    return ApiResponse.ok(viewingService.reschedule(viewingId, request));
  }

  /** Confirm the appointment with the prospect. */
  @PostMapping("/{viewingId}/confirm")
  @PreAuthorize("hasAuthority('VIEWING_UPDATE')")
  public ApiResponse<ViewingDtos.ViewingDetail> confirm(@PathVariable UUID viewingId) {
    return ApiResponse.ok(viewingService.confirm(viewingId), "Viewing confirmed");
  }

  /** Complete a viewing with its outcome and feedback. */
  @PostMapping("/{viewingId}/complete")
  @PreAuthorize("hasAuthority('VIEWING_UPDATE')")
  public ApiResponse<ViewingDtos.ViewingDetail> complete(
      @PathVariable UUID viewingId, @Valid @RequestBody ViewingDtos.CompleteViewingRequest request) {
    return ApiResponse.ok(viewingService.complete(viewingId, request), "Viewing completed");
  }

  /** Cancel the appointment, with a reason. */
  @PostMapping("/{viewingId}/cancel")
  @PreAuthorize("hasAuthority('VIEWING_CANCEL')")
  public ApiResponse<ViewingDtos.ViewingDetail> cancel(
      @PathVariable UUID viewingId, @Valid @RequestBody ViewingDtos.CancelViewingRequest request) {
    return ApiResponse.ok(viewingService.cancel(viewingId, request), "Viewing cancelled");
  }

  /** Close the appointment as a no-show, with a reason. */
  @PostMapping("/{viewingId}/no-show")
  @PreAuthorize("hasAuthority('VIEWING_UPDATE')")
  public ApiResponse<ViewingDtos.ViewingDetail> noShow(
      @PathVariable UUID viewingId, @Valid @RequestBody ViewingDtos.NoShowRequest request) {
    return ApiResponse.ok(viewingService.noShow(viewingId, request), "Viewing closed as a no-show");
  }
}
