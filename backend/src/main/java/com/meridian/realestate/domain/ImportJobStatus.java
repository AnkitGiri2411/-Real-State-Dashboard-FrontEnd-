package com.meridian.realestate.domain;

/**
 * Where an import job stands, exactly as {@code ck_import_jobs_status} enumerates it.
 *
 * <p>An import stages rows, validates them, shows them to a person, and only then commits.
 * The states are what make "nothing has touched live data yet" answerable: only {@code COMMITTED}
 * means records were written, and the schema requires a committed job to carry a time, an actor
 * and a validation timestamp, so a commit cannot be anonymous or unvalidated.</p>
 */
public enum ImportJobStatus {
  /** Rows are staged, nothing has been validated yet. */
  STAGED,
  /** Validation has run and the preview is available. */
  VALIDATED,
  /** Records were written. */
  COMMITTED,
  /** The file could not be processed; the reason is recorded on the job. */
  FAILED,
  /** Abandoned before it was committed. */
  CANCELLED;

  /** True while the job can still be changed or committed, because nothing was written yet. */
  public boolean isPending() {
    return this == STAGED || this == VALIDATED;
  }
}
