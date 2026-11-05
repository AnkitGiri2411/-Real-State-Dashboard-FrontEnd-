package com.meridian.realestate.domain;

/**
 * Who wrote a message, exactly as {@code ck_conversation_messages_sender} enumerates it.
 *
 * <p>{@code FIRM} and {@code CLIENT} decide which side of the thread a message appears on, and
 * {@code SYSTEM} marks the record of something the system did rather than something a person
 * wrote. The schema requires a firm-side message to name the colleague who wrote it and a system
 * message to name the event that produced it, so neither can be an anonymous assertion.</p>
 */
public enum MessageSenderType {
  /** The counterparty: owner, tenant, buyer or prospect. */
  CLIENT,
  /** A colleague at the firm. */
  FIRM,
  /** A business event recorded into the thread rather than typed by a person. */
  SYSTEM
}
