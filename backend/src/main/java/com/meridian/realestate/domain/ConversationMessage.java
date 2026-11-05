package com.meridian.realestate.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * One entry in a thread.
 *
 * <p>The interface styles a message from stored fields only - {@code senderType}, {@code messageType}
 * and {@code internal} are real columns, set when the message is written, and nothing about the
 * appearance of a message is inferred from its text. A category guessed from free text produces a
 * screen that is confidently wrong in a way nobody can debug, which is why anything the interface
 * distinguishes is a column or is not distinguished.</p>
 *
 * <p><strong>{@code internal} (column {@code is_internal}) separates an internal note from a message
 * the client can see, and an internal note must never be returned to a client-facing surface.</strong>
 * It is a note written by the firm about the client, often candid, and leaking it through a portal,
 * an export or a shared print view is a disclosure rather than a cosmetic bug - so any query that
 * feeds a client-facing view has to exclude internal rows at the query, not in the rendering. The
 * schema also requires that only a firm-side message may be internal: a client cannot write a note
 * to the firm that the firm cannot see.</p>
 *
 * <p>{@code senderName} is stored alongside {@code senderUser} because a message can arrive from
 * somebody who has no account - a tenant replying to a listing - and a null sender would otherwise
 * render as nothing. {@code systemEvent} names the business event a {@link MessageSenderType#SYSTEM}
 * entry records, so the thread carries "lease activated" or "receipt posted" without pretending a
 * person wrote it.</p>
 */
@Entity
@Table(name = "conversation_messages")
public class ConversationMessage extends Auditable {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "conversation_id", nullable = false)
  private Conversation conversation;

  @Enumerated(EnumType.STRING)
  @Column(name = "sender_type", nullable = false, length = 20)
  private MessageSenderType senderType;

  @Enumerated(EnumType.STRING)
  @Column(name = "message_type", nullable = false, length = 20)
  private MessageType messageType = MessageType.TEXT;

  /** Maps {@code is_internal}: true means the note must never reach a client-facing surface. */
  @Column(name = "is_internal", nullable = false)
  private boolean internal = false;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "sender_user_id")
  private User senderUser;

  @Column(name = "sender_name", length = 200)
  private String senderName;

  @Column(name = "body", columnDefinition = "text", nullable = false)
  private String body;

  @Column(name = "sent_at", nullable = false)
  private LocalDateTime sentAt;

  @Column(name = "read_at")
  private LocalDateTime readAt;

  @Column(name = "system_event", length = 80)
  private String systemEvent;

  /** True when the note is for the firm's eyes only. See the class note on internal notes. */
  public boolean isInternalNote() {
    return internal;
  }

  /** True when the counterparty has seen this message. */
  public boolean isRead() {
    return readAt != null;
  }

  public UUID getId() {
    return id;
  }

  public void setId(UUID id) {
    this.id = id;
  }

  public Conversation getConversation() {
    return conversation;
  }

  public void setConversation(Conversation conversation) {
    this.conversation = conversation;
  }

  public MessageSenderType getSenderType() {
    return senderType;
  }

  public void setSenderType(MessageSenderType senderType) {
    this.senderType = senderType;
  }

  public MessageType getMessageType() {
    return messageType;
  }

  public void setMessageType(MessageType messageType) {
    this.messageType = messageType;
  }

  public boolean isInternal() {
    return internal;
  }

  public void setInternal(boolean internal) {
    this.internal = internal;
  }

  public User getSenderUser() {
    return senderUser;
  }

  public void setSenderUser(User senderUser) {
    this.senderUser = senderUser;
  }

  public String getSenderName() {
    return senderName;
  }

  public void setSenderName(String senderName) {
    this.senderName = senderName;
  }

  public String getBody() {
    return body;
  }

  public void setBody(String body) {
    this.body = body;
  }

  public LocalDateTime getSentAt() {
    return sentAt;
  }

  public void setSentAt(LocalDateTime sentAt) {
    this.sentAt = sentAt;
  }

  public LocalDateTime getReadAt() {
    return readAt;
  }

  public void setReadAt(LocalDateTime readAt) {
    this.readAt = readAt;
  }

  public String getSystemEvent() {
    return systemEvent;
  }

  public void setSystemEvent(String systemEvent) {
    this.systemEvent = systemEvent;
  }
}
