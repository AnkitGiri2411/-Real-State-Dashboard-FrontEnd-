package com.meridian.realestate.dto;

import com.meridian.realestate.domain.ImportJob;
import com.meridian.realestate.domain.ImportJobRow;
import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/** Data-import API contracts. */
public final class ImportDtos {

  private ImportDtos() {}

  /**
   * One of the entity types this installation can import, with the columns it accepts.
   *
   * <p>Driven by {@code ImportService}'s own list rather than by the whole enum, so a picker never
   * offers an option that does nothing.</p>
   */
  public record SupportedImport(
      String entityType, String label, List<String> columns, List<String> requiredColumns) {}

  /** A staged job, as the history screen lists it. */
  public record JobSummary(
      UUID id,
      String jobCode,
      String entityType,
      String sourceType,
      String originalFilename,
      Long fileSizeBytes,
      String contentHash,
      String status,
      int rowCount,
      int validRowCount,
      int invalidRowCount,
      int insertedRowCount,
      int skippedRowCount,
      String errorMessage,
      LocalDateTime stagedAt,
      LocalDateTime validatedAt,
      LocalDateTime committedAt,
      LocalDateTime cancelledAt,
      LocalDateTime createdAt) {

    public static JobSummary from(ImportJob job) {
      return new JobSummary(
          job.getId(),
          job.getJobCode(),
          job.getEntityType() == null ? null : job.getEntityType().name(),
          job.getSourceType() == null ? null : job.getSourceType().name(),
          job.getOriginalFilename(),
          job.getFileSizeBytes(),
          job.getContentHash(),
          job.getStatus() == null ? null : job.getStatus().name(),
          job.getRowCount(),
          job.getValidRowCount(),
          job.getInvalidRowCount(),
          job.getInsertedRowCount(),
          job.getSkippedRowCount(),
          job.getErrorMessage(),
          job.getStagedAt(),
          job.getValidatedAt(),
          job.getCommittedAt(),
          job.getCancelledAt(),
          job.getCreatedAt());
    }
  }

  /** A staged row, as the review screen shows it, with the per-field reasons it was refused. */
  public record RowSummary(
      UUID id,
      int rowNumber,
      String rawData,
      String mappedData,
      String status,
      List<String> errors,
      List<String> warnings,
      UUID entityId,
      String entityLabel) {

    public static RowSummary from(ImportJobRow row, List<String> errors, List<String> warnings) {
      return new RowSummary(
          row.getId(),
          row.getRowNumber(),
          row.getRawData(),
          row.getMappedData(),
          row.getStatus() == null ? null : row.getStatus().name(),
          errors,
          warnings,
          row.getEntityId(),
          row.getEntityLabel());
    }
  }

  /**
   * What a staged file turned out to contain, before anything is written.
   *
   * <p>{@code canCommit} is computed, not guessed by the screen: a job with no valid rows cannot be
   * committed, and the interface should not have to work that out for itself.</p>
   */
  public record PreviewResult(
      JobSummary job,
      boolean canCommit,
      List<String> columnNames,
      List<RowSummary> rows,
      List<RowSummary> problems) {}

  /**
   * Confirm a staged import.
   *
   * <p>{@code idempotencyKey} is the deduplication key. A desk that double-clicks Confirm, or a
   * retried request, presents the same key and receives the first run's result instead of importing
   * the file twice. A different key against an already-committed job is refused rather than
   * silently ignored.</p>
   */
  public record ConfirmRequest(@Size(max = 64) String idempotencyKey) {}

  /**
   * The commit outcome.
   *
   * @param idempotentReplay true when this call changed nothing because the key had already been
   *     committed - the first run's result, returned verbatim
   */
  public record ConfirmResult(
      JobSummary job,
      int inserted,
      int skipped,
      int failed,
      boolean idempotentReplay,
      List<RowSummary> outcomes) {}
}
