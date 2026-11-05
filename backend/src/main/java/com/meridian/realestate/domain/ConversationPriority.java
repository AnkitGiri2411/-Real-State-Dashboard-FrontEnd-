package com.meridian.realestate.domain;

/**
 * How urgent a thread is, exactly as {@code ck_conversations_priority} enumerates it.
 *
 * <p>Priority is stored rather than inferred from the message body. Reading free text to decide
 * that a message is urgent produces a screen that is confidently wrong in a way nobody can debug,
 * so anything the interface distinguishes is a column.</p>
 */
public enum ConversationPriority {
  /** Informational; no deadline is attached. */
  LOW,
  /** The default. */
  NORMAL,
  /** Time-sensitive, but not yet on fire. */
  HIGH,
  /** Needs attention now. */
  URGENT
}
