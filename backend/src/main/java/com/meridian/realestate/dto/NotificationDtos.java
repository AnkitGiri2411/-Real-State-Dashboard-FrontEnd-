package com.meridian.realestate.dto;

import com.meridian.realestate.domain.Notification;
import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Notification API contracts.
 *
 * <p><b>Why these records exist.</b> A {@code Notification} carries a lazy user association and the
 * housekeeping columns, and never leaves the service. The records here are the subset the bell reads.</p>
 *
 * <p><b>The rule these records protect: {@code entityType} and {@code entityId} are a generic reference
 * pair, not a foreign key.</b> They let a notification point at "whatever this is about" - a lease, a
 * receipt, a maintenance request that has no entity of its own - without the schema knowing every
 * subject that might raise one. They are exposed as they are stored, so the interface can navigate to
 * the record by identifier, and their lack of a constraint is what keeps the notification table from
 * having to change every time the product grows a new subject. Nothing here resolves them to an entity;
 * that is the UI's job and it does it by the pair alone.</p>
 *
 * <p>There is deliberately no "create notification" request. Notifications are raised by the operations
 * that cause them, so the bell in the header can only ever contain something a member of staff's work
 * produced - a notification centre that could be filled by hand would stop meaning anything the moment
 * somebody used it.</p>
 */
public final class NotificationDtos {

  private NotificationDtos() {}

  /**
   * One notification.
   *
   * <p>{@code type} is a plain string, because the {@code notifications} table carries no CHECK
   * constraint on it - the vocabulary belongs to whoever raises the notification, and inventing a
   * closed set here would claim a constraint the database does not enforce. {@code severity} is a real
   * enum, because the table does constrain it.</p>
   */
  public record NotificationSummary(
      UUID id,
      String type,
      String severity,
      String title,
      String body,
      String entityType,
      UUID entityId,
      String link,
      boolean read,
      LocalDateTime readAt,
      LocalDateTime createdAt) {

    public static NotificationSummary from(Notification notification) {
      return new NotificationSummary(
          notification.getId(),
          notification.getType(),
          notification.getSeverity() == null ? null : notification.getSeverity().name(),
          notification.getTitle(),
          notification.getBody(),
          notification.getEntityType(),
          notification.getEntityId(),
          notification.getLink(),
          notification.isRead(),
          notification.getReadAt(),
          notification.getCreatedAt());
    }
  }

  /** The bell's badge: how many of the caller's notifications are still unread. */
  public record UnreadCount(long unread) {}

  /**
   * Mark some notifications read, or all of them when no ids are given.
   *
   * <p>An absent list means "mark all read", which is the one-click the badge offers; a list marks
   * exactly those. Either way only the caller's own rows are touched, because the service resolves
   * them against the signed-in user.</p>
   */
  public record MarkReadRequest(@Size(max = 200) List<UUID> ids) {}
}
