package com.meridian.realestate.domain;

/**
 * What a single staged row amounts to, exactly as {@code ck_import_job_rows_status} enumerates it.
 *
 * <p>A row is judged on its own: one bad cell rejects that row and nothing else, and the reason is
 * recorded against it. {@code INSERTED} carries the identifier of the record it produced, which is
 * what makes committing an already-committed row impossible rather than merely unlikely.</p>
 */
public enum ImportRowStatus {
  /** Parsed but not yet checked. */
  PENDING,
  /** Checked and acceptable. */
  VALID,
  /** Rejected, with the per-field reasons recorded on the row. */
  INVALID,
  /** The record was written; the row names it. */
  INSERTED,
  /** Deliberately not written - a duplicate of something already present. */
  SKIPPED,
  /** The insert itself failed. */
  FAILED;

  /** True when the row explains why it will not be written. */
  public boolean isProblem() {
    return this == INVALID || this == FAILED;
  }
}
