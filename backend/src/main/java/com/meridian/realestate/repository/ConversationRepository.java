package com.meridian.realestate.repository;

import com.meridian.realestate.domain.Conversation;
import com.meridian.realestate.domain.ConversationStatus;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * The correspondence, read as a list of threads.
 *
 * <p>A thread is archived rather than deleted, so reads filter on {@code deletedAt is null}.</p>
 *
 * <p>The ordering {@code last_message_at desc nulls last} is <em>intrinsic</em> to the thread list
 * rather than a preference of one caller: a conversation with no messages has no activity to place
 * in time and sorts last by design, and the schema indexes exactly that order. It therefore lives in
 * the query ({@link #findAllOrderedByRecentActivity()}) and is not something a caller opts out
 * of. The <em>paged</em> readers deliberately carry no literal order: their sort arrives as a
 * {@link Pageable} that has already been validated against a whitelist, so a requested order can
 * never quietly conflict with a hard-coded one.</p>
 */
public interface ConversationRepository extends JpaRepository<Conversation, UUID> {

  Optional<Conversation> findByIdAndDeletedAtIsNull(UUID id);

  Optional<Conversation> findByConversationCodeIgnoreCaseAndDeletedAtIsNull(
      String conversationCode);

  boolean existsByConversationCodeIgnoreCaseAndDeletedAtIsNull(String conversationCode);

  /**
   * The thread list: newest activity first, and threads with no messages last.
   *
   * <p>Not paged, because the list itself is the thing being defined - the console's left rail
   * renders it with a cursor rather than a page number.</p>
   */
  @Query(
      "select c from Conversation c where c.deletedAt is null "
          + "order by c.lastMessageAt desc nulls last")
  List<Conversation> findAllOrderedByRecentActivity();

  /** The same threads, paged, with the sort supplied by the caller's {@link Pageable}. */
  Page<Conversation> findAllByDeletedAtIsNull(Pageable pageable);

  List<Conversation> findAllByDeletedAtIsNull();

  long countByDeletedAtIsNull();

  /** One state's count, which is how the open and closed totals are read. */
  long countByStatusAndDeletedAtIsNull(ConversationStatus status);

  /** The live states in one call, for the "needs a reply" figure. */
  long countByStatusInAndDeletedAtIsNull(Collection<ConversationStatus> statuses);

  /** The threads filed against one property. */
  List<Conversation> findAllByPropertyIdAndDeletedAtIsNull(UUID propertyId);

  /** The threads with one tenant. */
  List<Conversation> findAllByTenantIdAndDeletedAtIsNull(UUID tenantId);

  /** One colleague's threads, paged; the sort is the caller's. */
  Page<Conversation> findAllByAssignedToIdAndDeletedAtIsNull(UUID userId, Pageable pageable);

  /** How much of one colleague's work is still live. */
  long countByAssignedToIdAndStatusInAndDeletedAtIsNull(
      UUID userId, Collection<ConversationStatus> statuses);

  /**
   * Threads with the assigned colleague fetched, so a list can show who owns each one without a
   * query per row.
   */
  @Query("select c from Conversation c left join fetch c.assignedTo where c.deletedAt is null")
  List<Conversation> findAllWithAssignedUser();

  @Query("select c from Conversation c left join fetch c.assignedTo where c.id = :id")
  Optional<Conversation> findByIdWithAssignedUser(@Param("id") UUID id);
}
