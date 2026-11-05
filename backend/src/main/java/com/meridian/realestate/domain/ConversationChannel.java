package com.meridian.realestate.domain;

/**
 * How the correspondence happened, exactly as {@code ck_conversations_channel} enumerates it.
 *
 * <p>The product records correspondence rather than sending it, so the channel says what actually
 * took place - a phone call, a letter, a conversation at the desk - rather than which integration
 * was used. A channel this product cannot deliver on would be a claim it cannot keep.</p>
 */
public enum ConversationChannel {
  /** Email. */
  EMAIL,
  /** A telephone call. */
  PHONE,
  /** A messaging channel such as SMS or a chat platform. */
  MESSAGE,
  /** The tenant or owner portal. */
  PORTAL,
  /** A face-to-face conversation. */
  IN_PERSON,
  /** Physical post. */
  LETTER,
  /** Internal correspondence, with no counterparty outside the firm. */
  INTERNAL
}
