package com.meridian.realestate.controller;

import com.meridian.realestate.dto.ApiResponse;
import com.meridian.realestate.dto.NotificationDtos;
import com.meridian.realestate.dto.PageResponse;
import com.meridian.realestate.service.NotificationService;
import com.meridian.realestate.web.PageRequestFactory;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Notifications: the caller's own in-app feed, and the state of having read it.
 *
 * <p><b>Why this controller exists.</b> The bell in the header reads one resource three ways: the
 * feed (with an unread-only filter), the badge's count, and the actions that clear the badge. There is
 * no endpoint that creates a notification, because every one is raised by the operation that caused it.</p>
 *
 * <p><b>The rules it protects.</b> No business rule lives here. What a caller sees is their own rows,
 * resolved from the signed-in account and never from the request, so a caller cannot read or clear
 * another account's feed. This class only routes, validates the shape of the request body and wraps the
 * answer in {@link ApiResponse}; the scoping lives in {@link NotificationService}.</p>
 *
 * <p><b>Permissions.</b> The notification module declares one capability, {@code NOTIFICATION_VIEW},
 * and every endpoint here uses it: reading the feed, reading the badge and clearing it are all the same
 * capability in the seed, and there is no separate update code to invent. No role name appears
 * anywhere in this class.</p>
 */
@RestController
@RequestMapping("/api/v1/notifications")
public class NotificationController {

  private static final PageRequestFactory PAGES =
      PageRequestFactory.sortedBy("createdAt,desc")
          .allow("createdAt", "createdAt")
          .allow("type", "type")
          .allow("severity", "severity")
          .allow("read", "read")
          .build();

  private final NotificationService notificationService;

  public NotificationController(NotificationService notificationService) {
    this.notificationService = notificationService;
  }

  /** The caller's own notifications, newest first, with an unread-only filter. */
  @GetMapping
  @PreAuthorize("hasAuthority('NOTIFICATION_VIEW')")
  public ApiResponse<PageResponse<NotificationDtos.NotificationSummary>> list(
      @RequestParam(required = false) Boolean unreadOnly,
      @RequestParam(required = false) String severity,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size,
      @RequestParam(required = false) String sort) {
    return ApiResponse.ok(
        PageResponse.from(
            notificationService.list(unreadOnly, severity, PAGES.toPageable(page, size, sort))));
  }

  /** The badge in the header. */
  @GetMapping("/unread-count")
  @PreAuthorize("hasAuthority('NOTIFICATION_VIEW')")
  public ApiResponse<NotificationDtos.UnreadCount> unreadCount() {
    return ApiResponse.ok(new NotificationDtos.UnreadCount(notificationService.unreadCount()));
  }

  /** Mark one notification read. */
  @PostMapping("/{notificationId}/read")
  @PreAuthorize("hasAuthority('NOTIFICATION_VIEW')")
  public ApiResponse<NotificationDtos.NotificationSummary> markRead(@PathVariable UUID notificationId) {
    return ApiResponse.ok(notificationService.markRead(notificationId), "Notification read");
  }

  /**
   * Mark some, or all, of the caller's notifications read.
   *
   * <p>An absent body, or one with no ids, marks every unread notification read - the one-click the
   * badge offers. A body with ids marks exactly those.</p>
   */
  @PostMapping("/read")
  @PreAuthorize("hasAuthority('NOTIFICATION_VIEW')")
  public ApiResponse<NotificationDtos.UnreadCount> markRead(
      @Valid @RequestBody(required = false) NotificationDtos.MarkReadRequest request) {
    List<UUID> ids = request == null || request.ids() == null ? List.of() : request.ids();
    if (ids.isEmpty()) {
      return ApiResponse.ok(new NotificationDtos.UnreadCount(notificationService.markAllRead()));
    }
    for (UUID id : ids) {
      notificationService.markRead(id);
    }
    return ApiResponse.ok(new NotificationDtos.UnreadCount(notificationService.unreadCount()));
  }
}
