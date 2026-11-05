package com.meridian.realestate.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.meridian.realestate.domain.Notification;
import com.meridian.realestate.domain.User;
import com.meridian.realestate.repository.NotificationRepository;
import com.meridian.realestate.security.AccessScope;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * The bell's rules, asserted without a database.
 *
 * <p>Two promises are pinned here. Marking all read sets <em>both</em> {@code is_read} and
 * {@code read_at} on every unread row - the state {@code ck_notifications_read} exists to require, and
 * the state a row left with only one of the two would violate while still hiding it from the badge. And
 * the unread count is the caller's own unread rows: it is resolved from the signed-in account, not from
 * the request, so it can never count another user's feed.</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("NotificationService: read state and the unread count")
class NotificationServiceTest {

  @Mock private NotificationRepository notifications;
  @Mock private AuditRecordingService audit;
  @Mock private AccessScope accessScope;

  private NotificationService service;

  @BeforeEach
  void setUp() {
    service = new NotificationService(notifications, audit, accessScope);
  }

  private static User actor(UUID id) {
    User user = new User();
    user.setId(id);
    user.setFirstName("Deepa");
    user.setLastName("Venkatesan");
    return user;
  }

  private static Notification notification() {
    Notification notification = new Notification();
    notification.setId(UUID.randomUUID());
    notification.setType("LEASE_EXPIRING");
    notification.setTitle("A lease is expiring");
    notification.setRead(false);
    notification.setReadAt(null);
    return notification;
  }

  @Test
  @DisplayName("mark-all-read sets both is_read and read_at on every unread row")
  void markAllReadSetsBothColumns() {
    UUID userId = UUID.randomUUID();
    Notification first = notification();
    Notification second = notification();

    when(accessScope.requireUser()).thenReturn(actor(userId));
    when(notifications.findAllByUserIdAndReadFalse(userId)).thenReturn(List.of(first, second));
    when(notifications.countByUserIdAndReadFalse(userId)).thenReturn(0L);

    service.markAllRead();

    assertThat(first.isRead()).isTrue();
    assertThat(first.getReadAt()).isNotNull();
    assertThat(second.isRead()).isTrue();
    assertThat(second.getReadAt()).isNotNull();
    verify(notifications).saveAll(anyList());
  }

  @Test
  @DisplayName("the unread count counts only unread rows for the caller")
  void theUnreadCountCountsOnlyTheCallersUnreadRows() {
    UUID caller = UUID.randomUUID();
    UUID someoneElse = UUID.randomUUID();

    when(accessScope.requireUserId()).thenReturn(caller);
    when(notifications.countByUserIdAndReadFalse(caller)).thenReturn(3L);

    assertThat(service.unreadCount()).isEqualTo(3L);

    // The count is scoped to the caller by the query itself: no other account's rows are read.
    verify(notifications).countByUserIdAndReadFalse(caller);
    verify(notifications, never()).countByUserIdAndReadFalse(someoneElse);
  }
}
