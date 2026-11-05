package com.meridian.realestate.repository;

import com.meridian.realestate.domain.Notification;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * The bell: the notifications one signed-in user has.
 *
 * <p>There is no {@code deletedAt} filter anywhere here, because {@code notifications} has no
 * {@code deleted_at} column - and deliberately so. A notification is not a business record that
 * gets archived: it is written once, read once, and removed with its user by the schema's
 * {@code ON DELETE CASCADE}. Inventing a soft delete in the model would ask Hibernate for a column
 * the Flyway chain does not have.</p>
 *
 * <p>Newest first is intrinsic to a feed of notifications, so it appears on the non-paged readers;
 * the paged ones take their sort from the {@link Pageable}.</p>
 */
public interface NotificationRepository extends JpaRepository<Notification, UUID> {

  Optional<Notification> findByIdAndUserId(UUID id, UUID userId);

  List<Notification> findAllByUserId(UUID userId);

  Page<Notification> findAllByUserId(UUID userId, Pageable pageable);

  /** The unread rows the badge counts, newest first. */
  List<Notification> findAllByUserIdAndReadFalse(UUID userId);

  /** The badge's number. */
  long countByUserIdAndReadFalse(UUID userId);

  /** How many rows one user has in total, read and unread. */
  long countByUserId(UUID userId);

  /**
   * Mark every unread notification of one user as read.
   *
   * <p>Read state is all-or-nothing with the timestamp - the schema enforces that - so both columns
   * are set here in one statement. Bulk rather than row-by-row because "mark all read" is a single
   * click on a list that may be long, and a hundred updates would be a hundred chances to fail
   * half-way.</p>
   *
   * @return the number of notifications that changed state
   */
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(
      "update Notification n set n.read = true, n.readAt = :readAt "
          + "where n.user.id = :userId and n.read = false")
  int markAllReadForUser(@Param("userId") UUID userId, @Param("readAt") LocalDateTime readAt);
}
