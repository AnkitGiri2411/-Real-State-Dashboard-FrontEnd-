package com.meridian.realestate.domain;

/**
 * What kind of entry a message is, exactly as {@code ck_conversation_messages_type} enumerates it.
 *
 * <p>The interface styles a message from its stored fields and from nothing else. This enum is one
 * of those fields: it is set when the message is written, never guessed from the body text, so a
 * tenant writing "I will not pay for the lift again" is never silently styled as a payment
 * message.</p>
 */
public enum MessageType {
  /** Ordinary correspondence. */
  TEXT,
  /** An internal note; never returned to a client-facing surface. */
  NOTE,
  /** The record of a business event - a lease activated, a receipt posted. */
  SYSTEM_EVENT,
  /** A reference to a document held in the register rather than the file itself. */
  ATTACHMENT_REFERENCE
}
