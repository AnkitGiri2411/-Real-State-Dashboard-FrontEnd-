package com.meridian.realestate.domain;

/**
 * Where a thread stands, exactly as {@code ck_conversations_status} enumerates it.
 *
 * <p>{@code OPEN} and {@code WAITING} are the live states the "needs a reply" count reads, and the
 * schema indexes precisely that pair. {@code CLOSED} requires a closure time, a closer and a
 * reason, so a thread cannot be closed in a way nobody can explain later.</p>
 */
public enum ConversationStatus {
  /** Live and being worked. */
  OPEN,
  /** Live but awaiting somebody else, typically the counterparty. */
  WAITING,
  /** Dealt with, but not formally closed. */
  RESOLVED,
  /** Closed, with a recorded time, actor and reason. */
  CLOSED;

  /** True when the thread still needs attention rather than being finished. */
  public boolean isLive() {
    return this == OPEN || this == WAITING;
  }
}
