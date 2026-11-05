package com.meridian.realestate.repository;

import com.meridian.realestate.domain.ConversationMessage;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * The messages inside a thread.
 *
 * <p>A thread reads in the order it was written, so the conversation readers order by
 * {@code sent_at} ascending: ordering a conversation by anything else would make it read as a
 * jumble. The paged reader takes its sort from the {@link Pageable} instead.</p>
 *
 * <p><strong>Internal notes are excluded from anything client-facing at the query.</strong> A note
 * whose {@code internal} flag is set was written by the firm about the counterparty, and the only
 * safe way to keep it off a portal or an export is a finder that cannot return it -
 * {@link #findAllByConversationIdAndInternalFalseAndDeletedAtIsNullOrderBySentAtAsc(UUID)} - rather
 * than a filter applied while rendering.</p>
 */
public interface ConversationMessageRepository extends JpaRepository<ConversationMessage, UUID> {

  Optional<ConversationMessage> findByIdAndDeletedAtIsNull(UUID id);

  /** The whole thread, oldest first. */
  List<ConversationMessage> findAllByConversationIdAndDeletedAtIsNullOrderBySentAtAsc(
      UUID conversationId);

  /** One page of the thread; the sort is the caller's. */
  Page<ConversationMessage> findAllByConversationIdAndDeletedAtIsNull(
      UUID conversationId, Pageable pageable);

  /**
   * The thread as the counterparty may see it: internal notes omitted.
   *
   * <p>This is the reader a portal, a print view or an export must use.</p>
   */
  List<ConversationMessage> findAllByConversationIdAndInternalFalseAndDeletedAtIsNullOrderBySentAtAsc(
      UUID conversationId);

  /**
   * The latest message of a thread, which is what the thread list shows as its preview.
   *
   * <p>The identifier breaks ties on identical timestamps, so "the latest message" is a single
   * answer rather than whichever row the planner returned first.</p>
   */
  Optional<ConversationMessage>
      findFirstByConversationIdAndDeletedAtIsNullOrderBySentAtDescIdDesc(UUID conversationId);

  /** How many messages are still unread on a thread, for the unread marker. */
  long countByConversationIdAndReadAtIsNullAndDeletedAtIsNull(UUID conversationId);

  /** How many messages the thread has in total. */
  long countByConversationIdAndDeletedAtIsNull(UUID conversationId);

  /**
   * How many messages arrived after a moment.
   *
   * <p>Counted in the database rather than by loading the thread and filtering, because this is the
   * "anything new since I last looked" figure and it runs while a thread is open.</p>
   */
  @Query(
      "select count(m) from ConversationMessage m "
          + "where m.deletedAt is null and m.conversation.id = :conversationId "
          + "and m.sentAt > :since")
  long countSentAfter(
      @Param("conversationId") UUID conversationId, @Param("since") LocalDateTime since);
}
