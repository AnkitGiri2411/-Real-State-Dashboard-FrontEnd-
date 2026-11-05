package com.meridian.realestate.dto;

import com.meridian.realestate.domain.Conversation;
import com.meridian.realestate.domain.ConversationMessage;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * The communication register's API contract: the threads, their messages, and the reads and writes a
 * console performs on them.
 *
 * <p><b>Why these records exist.</b> A {@code Conversation} entity carries eight lazy subject links
 * and a thread's messages are a separate table; nothing here returns an entity. Each record is the
 * deliberate subset the console actually reads, with every enum flattened to its name.</p>
 *
 * <p><b>The rule these records protect: a message is styled from stored fields, never from its
 * text.</b> {@code senderType}, {@code messageType} and {@code internal} are real columns, set when a
 * message is written, and nothing about how a message appears is inferred from its body. Inferring a
 * category from free text produces a screen that is confidently wrong in a way nobody can debug - a
 * tenant writing "I will not pay for the lift again" styled as a payment message, while a genuinely
 * overdue account whose email says "please see attached" is not. So there is deliberately no
 * free-text classifier anywhere in this contract, and no field a caller could use to imply one.</p>
 *
 * <p><b>The rule these records protect, part two: an internal note is never client-facing.</b> {@link
 * MessageSummary#internal()} is real. The default {@link ConversationDetail} carries only the messages
 * a client could see, and the internal-only read is a separate call. See {@code CommunicationService}.</p>
 */
public final class CommunicationDtos {

  private CommunicationDtos() {}

  private static final String CHANNEL_SET = "EMAIL|PHONE|MESSAGE|PORTAL|IN_PERSON|LETTER|INTERNAL";
  private static final String STATUS_SET = "OPEN|WAITING|RESOLVED|CLOSED";
  private static final String PRIORITY_SET = "LOW|NORMAL|HIGH|URGENT";
  private static final String SENDER_SET = "CLIENT|FIRM|SYSTEM";
  private static final String MESSAGE_TYPE_SET = "TEXT|NOTE|SYSTEM_EVENT|ATTACHMENT_REFERENCE";

  // ---------------------------------------------------------------------------
  // Reads
  // ---------------------------------------------------------------------------

  /**
   * One row of the thread list.
   *
   * <p>{@code lastMessageAt} may be null, and a thread with no messages sorts last when the list is
   * read by recency: it has no activity to place in time, and inventing a timestamp for it would put a
   * thread nobody has written in above one that has been. That is the schema's own ordering, kept
   * here as the list's default.</p>
   */
  public record ConversationSummary(
      UUID id,
      String conversationCode,
      String subject,
      String channel,
      String status,
      String priority,
      UUID propertyId,
      String propertyCode,
      String propertyName,
      UUID unitId,
      UUID leaseId,
      UUID listingId,
      UUID leadId,
      UUID tenantId,
      String tenantCode,
      UUID ownerId,
      UUID buyerId,
      String counterpartyName,
      String counterpartyEmail,
      String counterpartyPhone,
      UUID assignedToId,
      String assignedToName,
      LocalDateTime lastMessageAt,
      String lastMessagePreview,
      LocalDateTime openedAt,
      LocalDateTime closedAt,
      String closeReason,
      long messageCount,
      long unreadCount,
      LocalDateTime createdAt,
      LocalDateTime updatedAt) {}

  /**
   * One thread and its messages.
   *
   * <p>The default read: {@code messages} are the ones a client could see, and {@code
   * includesInternalNotes} is false. An internal note is not returned here at all - see {@code
   * CommunicationService} for the separate internal-only read.</p>
   */
  public record ConversationDetail(
      ConversationSummary conversation, java.util.List<MessageSummary> messages, boolean includesInternalNotes) {}

  /**
   * One message.
   *
   * <p>{@code internal}, {@code senderType}, {@code messageType} and {@code systemEvent} are the
   * stored fields the interface styles from; the body is displayed and never parsed.</p>
   */
  public record MessageSummary(
      UUID id,
      String senderType,
      String messageType,
      boolean internal,
      UUID senderUserId,
      String senderName,
      String body,
      LocalDateTime sentAt,
      LocalDateTime readAt,
      String systemEvent) {

    public static MessageSummary from(ConversationMessage message) {
      return new MessageSummary(
          message.getId(),
          message.getSenderType() == null ? null : message.getSenderType().name(),
          message.getMessageType() == null ? null : message.getMessageType().name(),
          message.isInternalNote(),
          message.getSenderUser() == null ? null : message.getSenderUser().getId(),
          message.getSenderName(),
          message.getBody(),
          message.getSentAt(),
          message.getReadAt(),
          message.getSystemEvent());
    }
  }

  /** The header badge's number for the communication console. */
  public record UnreadCount(long unreadMessages) {}

  // ---------------------------------------------------------------------------
  // Writes
  // ---------------------------------------------------------------------------

  /**
   * Open a thread.
   *
   * <p>No opening message is required: a thread opened before anybody has written is a real state -
   * a logged call with nothing said yet - and it sorts last until a message is posted, exactly as the
   * schema's ordering intends.</p>
   */
  public record CreateConversationRequest(
      @NotBlank @Size(max = 255) String subject,
      @Pattern(regexp = CHANNEL_SET, message = "must be a conversation channel") String channel,
      @Pattern(regexp = STATUS_SET, message = "must be a conversation status") String status,
      @Pattern(regexp = PRIORITY_SET, message = "must be a conversation priority") String priority,
      UUID propertyId,
      UUID unitId,
      UUID leaseId,
      UUID listingId,
      UUID leadId,
      UUID tenantId,
      UUID ownerId,
      UUID buyerId,
      @Size(max = 200) String counterpartyName,
      @Size(max = 255) String counterpartyEmail,
      @Size(max = 30) String counterpartyPhone,
      UUID assignedToId) {}

  /**
   * Post a message.
   *
   * <p>{@code senderType} defaults to {@code FIRM} - a colleague logging correspondence. A {@code
   * CLIENT} message carries a name but no account; a {@code SYSTEM} message carries the event that
   * produced it. {@code internal} may only be set on a firm-side message, which the schema also
   * requires: a client cannot write a note to the firm that the firm cannot see.</p>
   */
  public record PostMessageRequest(
      @NotBlank @Size(max = 10000) String body,
      @Pattern(regexp = SENDER_SET, message = "must be a message sender type") String senderType,
      @Pattern(regexp = MESSAGE_TYPE_SET, message = "must be a message type") String messageType,
      Boolean internal,
      @Size(max = 200) String senderName,
      @Size(max = 80) String systemEvent) {}

  /** Assign or reassign the colleague who owns a thread. A null id unassigns it. */
  public record AssignConversationRequest(UUID assignedToId) {}

  /** Close a thread. A reason is optional but travels into the record. */
  public record CloseConversationRequest(@Size(max = 500) String reason) {}

  /** The channel filter set, exposed so a caller can be told the accepted values. */
  public static java.util.List<String> channelValues() {
    return java.util.List.of(CHANNEL_SET.split("\\|"));
  }

  /** The status filter set, exposed so a caller can be told the accepted values. */
  public static java.util.List<String> statusValues() {
    return java.util.List.of(STATUS_SET.split("\\|"));
  }

  /** True when a conversation carries the given code - a helper for the summary's own readers. */
  public static boolean concerns(Conversation conversation, UUID id) {
    return conversation != null && id != null && id.equals(conversation.getId());
  }
}
