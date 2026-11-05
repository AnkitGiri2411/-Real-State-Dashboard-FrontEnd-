package com.meridian.realestate.service;

import com.meridian.realestate.domain.Notification;
import com.meridian.realestate.domain.NotificationSeverity;
import com.meridian.realestate.domain.User;
import com.meridian.realestate.dto.NotificationDtos;
import com.meridian.realestate.exception.ApiException;
import com.meridian.realestate.repository.NotificationRepository;
import com.meridian.realestate.security.AccessScope;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The bell: the notifications one signed-in user reads, and the state of having read them.
 *
 * <p><b>Why this class exists.</b> A notification is in-app only. This product does not send email or
 * messages, so a notification is a row a signed-in user reads and nothing more; there is no sender,
 * no delivery channel and no "create" method here, because every notification is raised by the
 * operation that caused it, inside that operation's transaction. A notification centre that could be
 * filled by hand would stop meaning anything the moment somebody used it.</p>
 *
 * <p><b>The rules it protects.</b></p>
 * <ul>
 *   <li><b>The caller sees only their own rows.</b> Every read and every write is scoped to the
 *       signed-in user by the query itself - {@code user_id} is bound from {@link AccessScope}, never
 *       taken from the request - so marking "someone else's" notification read is not an operation the
 *       API can express.</li>
 *   <li><b>There is no soft delete.</b> The {@code notifications} table carries no {@code deleted_at}
 *       column - a notification is written once, read once and removed with its user - so no read here
 *       filters on {@code deletedAt}. Inventing one would ask Hibernate for a column the migration
 *       chain does not have.</li>
 *   <li><b>Read state is all-or-nothing.</b> The schema's {@code ck_notifications_read} requires
 *       {@code is_read} and {@code read_at} to move together, so marking a notification read sets
 *       <em>both</em> columns, and "mark all read" sets both on every unread row. A row left with a
 *       read timestamp but an unread flag would be hidden from the badge while never having been seen,
 *       which is exactly the state the constraint forbids.</li>
 * </ul>
 *
 * <p><b>The entity reference is exposed as stored.</b> {@code entity_type}/{@code entity_id} are a
 * generic pair, not a foreign key; they are returned as they are so the interface can navigate to the
 * record by identifier, and never resolved to an entity here.</p>
 *
 * <p><b>A note on paging.</b> The caller's notifications are read in one go and filtered, sorted and
 * sliced here, so the unread-only filter and the requested sort agree with the page that is returned.
 * One user's notification feed is small, and there is no soft-delete column to filter on in SQL.</p>
 */
@Service
public class NotificationService {

  private final NotificationRepository notifications;
  private final AuditRecordingService audit;
  private final AccessScope accessScope;

  public NotificationService(
      NotificationRepository notifications, AuditRecordingService audit, AccessScope accessScope) {
    this.notifications = notifications;
    this.audit = audit;
    this.accessScope = accessScope;
  }

  /* ====================================================================== */
  /* Reads                                                                  */
  /* ====================================================================== */

  /** The caller's own notifications, newest first, with an unread-only filter and a severity filter. */
  @Transactional(readOnly = true)
  public Page<NotificationDtos.NotificationSummary> list(
      Boolean unreadOnly, String severity, Pageable pageable) {
    UUID userId = accessScope.requireUserId();
    NotificationSeverity severityFilter = parseSeverity(severity, null);
    boolean onlyUnread = Boolean.TRUE.equals(unreadOnly);

    List<NotificationDtos.NotificationSummary> matches =
        sortNotifications(notifications.findAllByUserId(userId), pageable.getSort()).stream()
            .filter(notification -> !onlyUnread || notification.isUnread())
            .filter(
                notification ->
                    severityFilter == null || notification.getSeverity() == severityFilter)
            .map(NotificationDtos.NotificationSummary::from)
            .toList();

    return pageOf(matches, pageable);
  }

  /**
   * The bell's badge: the caller's unread notifications.
   *
   * <p>Counted in the database against the partial index over unread rows, so the figure is the one
   * the index is built for rather than a filter over a fetched list.</p>
   */
  @Transactional(readOnly = true)
  public long unreadCount() {
    return notifications.countByUserIdAndReadFalse(accessScope.requireUserId());
  }

  /* ====================================================================== */
  /* Writes                                                                 */
  /* ====================================================================== */

  /**
   * Mark one of the caller's notifications read.
   *
   * <p>Both columns move together, as the schema requires, and the row is resolved against the
   * signed-in user so another account's notification cannot be touched.</p>
   */
  @Transactional
  public NotificationDtos.NotificationSummary markRead(UUID notificationId) {
    User actor = accessScope.requireUser();
    Notification notification =
        notifications
            .findByIdAndUserId(notificationId, actor.getId())
            .orElseThrow(() -> ApiException.notFound("We could not find that notification"));

    if (notification.isUnread()) {
      LocalDateTime now = LocalDateTime.now();
      // Read state is all-or-nothing: the flag and the timestamp are set together.
      notification.setRead(true);
      notification.setReadAt(now);
      notifications.save(notification);
      recordRead(actor, 1);
    }
    return NotificationDtos.NotificationSummary.from(notification);
  }

  /**
   * Mark every unread notification of the caller read.
   *
   * <p>Each row's {@code is_read} and {@code read_at} are set together in the same write, so no row is
   * left carrying one without the other - the state {@code ck_notifications_read} exists to forbid.</p>
   *
   * @return how many of the caller's notifications remain unread, which is zero here
   */
  @Transactional
  public long markAllRead() {
    User actor = accessScope.requireUser();
    LocalDateTime now = LocalDateTime.now();

    List<Notification> unread = notifications.findAllByUserIdAndReadFalse(actor.getId());
    for (Notification notification : unread) {
      notification.setRead(true);
      notification.setReadAt(now);
    }
    if (!unread.isEmpty()) {
      notifications.saveAll(unread);
      recordRead(actor, unread.size());
    }
    return notifications.countByUserIdAndReadFalse(actor.getId());
  }

  /* ====================================================================== */
  /* Internals                                                              */
  /* ====================================================================== */

  private void recordRead(User actor, int count) {
    audit.record(
        actor.getId(),
        actor.getFullName(),
        AuditActions.NOTIFICATIONS,
        AuditActions.UPDATE,
        count == 1 ? "Read a notification" : "Read " + count + " notifications",
        "NOTIFICATION",
        null,
        null);
  }

  private static NotificationSeverity parseSeverity(String value, NotificationSeverity fallback) {
    if (value == null || value.isBlank()) return fallback;
    try {
      return NotificationSeverity.valueOf(value.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException ex) {
      throw ApiException.badRequest(
          "'" + value + "' is not a notification severity",
          List.of("severity: INFO", "severity: WARNING", "severity: CRITICAL"));
    }
  }

  private static List<Notification> sortNotifications(List<Notification> items, Sort sort) {
    Comparator<Notification> comparator =
        sort == null || sort.isUnsorted() ? createdAtDescending() : buildComparator(sort);
    List<Notification> sorted = new ArrayList<>(items);
    sorted.sort(
        comparator.thenComparing(
            Notification::getId, Comparator.nullsLast(Comparator.naturalOrder())));
    return sorted;
  }

  private static Comparator<Notification> buildComparator(Sort sort) {
    Comparator<Notification> comparator = null;
    for (Sort.Order order : sort) {
      Comparator<Notification> next = notificationComparator(order.getProperty(), order.isDescending());
      comparator = comparator == null ? next : comparator.thenComparing(next);
    }
    return comparator == null ? createdAtDescending() : comparator;
  }

  private static Comparator<Notification> notificationComparator(String property, boolean descending) {
    return switch (property == null ? "" : property) {
      case "type" -> comparingNullsLast(Notification::getType, descending);
      case "severity" ->
          comparingNullsLast(
              notification -> notification.getSeverity() == null ? null : notification.getSeverity().name(),
              descending);
      case "read" -> comparingNullsLast(Notification::isRead, descending);
      default -> comparingNullsLast(Notification::getCreatedAt, descending);
    };
  }

  private static Comparator<Notification> createdAtDescending() {
    return Comparator.comparing(
        Notification::getCreatedAt, Comparator.nullsLast(Comparator.reverseOrder()));
  }

  private static <T extends Comparable<? super T>> Comparator<Notification> comparingNullsLast(
      Function<Notification, T> key, boolean descending) {
    Comparator<T> order =
        descending
            ? Comparator.nullsLast(Comparator.<T>reverseOrder())
            : Comparator.nullsLast(Comparator.<T>naturalOrder());
    return Comparator.comparing(key, order);
  }

  private static <T> Page<T> pageOf(List<T> all, Pageable pageable) {
    int total = all.size();
    int size = pageable.isPaged() ? pageable.getPageSize() : total;
    int start = pageable.isPaged() ? (int) Math.min(pageable.getOffset(), (long) total) : 0;
    int end = Math.min(start + size, total);
    return new PageImpl<>(new ArrayList<>(all.subList(start, end)), pageable, total);
  }
}
