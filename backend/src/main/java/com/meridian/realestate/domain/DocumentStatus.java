package com.meridian.realestate.domain;

/**
 * Where a document sits in its own life, exactly as {@code ck_documents_status} enumerates it.
 *
 * <p>{@code SUPERSEDED} is the one that matters: a replaced document is not deleted, because
 * "which version was in force at the time" is a question that gets asked. The status is therefore
 * a statement about currency, not about existence.</p>
 */
public enum DocumentStatus {
  /** The current version, and the one the expiry watchlist reads. */
  ACTIVE,
  /** A newer version exists; this one is kept as the historical record. */
  SUPERSEDED,
  /** Past its expiry date and not renewed. */
  EXPIRED,
  /** Withdrawn from the register's live view without being removed. */
  ARCHIVED;

  /** True when the document is the current version rather than a replaced or withdrawn one. */
  public boolean isLive() {
    return this == ACTIVE;
  }
}
