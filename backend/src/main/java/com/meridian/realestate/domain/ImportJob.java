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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One staging of one uploaded file: parse, validate, preview, commit.
 *
 * <p>Data import is staged rather than direct. Nothing reaches an application table until a person
 * has seen what will happen, which is why the job carries row counts that must add up and a
 * validation summary that is shown before the commit.</p>
 *
 * <p><strong>Uploaded files are parsed in memory and never written to disk.</strong> There is no
 * column for uploaded bytes here and no path on disk anywhere in the product, so there is no
 * temporary-file surface, no directory that has to be cleaned and secured, and no path-traversal
 * surface - a filename is a label, never something the application opens. The staged rows are a
 * superset of what the file contained, so the upload can be discarded when the request ends at no
 * cost to the record.</p>
 *
 * <p><strong>{@code idempotencyKey} is unique, and that is what makes a re-submitted import return
 * the original result instead of importing twice.</strong> A client that retries an upload - because
 * the connection dropped, or because a person pressed the button twice - presents the same key and
 * is answered with the job that already exists rather than inserting a second copy of every row.
 * {@code replayOf} records an <em>intentional</em> re-run of a previously committed job, so a
 * genuine repeat stays distinguishable from a retry; the schema keeps a job from naming itself as
 * its own replay.</p>
 *
 * <p><strong>The import surface is INSERT-ONLY.</strong> It offers no edit, update or delete of an
 * imported record: a correction is another import, which is why the commit writes new records and
 * records the identifier each staged row produced rather than mutating what is already there.</p>
 *
 * <p>{@code columnMapping} and {@code validationSummary} are jsonb because both are documents whose
 * shape follows the entity type being imported - which columns were mapped, what validation found -
 * and neither can be a fixed set of columns.</p>
 */
@Entity
@Table(name = "import_jobs")
public class ImportJob extends Auditable {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "job_code", nullable = false, length = 30, unique = true)
  private String jobCode;

  /**
   * What the file imports. The column carries the CHECK {@code ck_import_jobs_entity}, so this is a
   * closed set and adding an entity type is a migration rather than a new string.
   */
  @Enumerated(EnumType.STRING)
  @Column(name = "entity_type", nullable = false, length = 30)
  private ImportEntityType entityType;

  @Enumerated(EnumType.STRING)
  @Column(name = "source_type", nullable = false, length = 20)
  private ImportSourceType sourceType = ImportSourceType.CSV;

  /** A label for the person who uploaded it. Never a path: nothing is opened from it. */
  @Column(name = "original_filename", length = 255)
  private String originalFilename;

  @Column(name = "file_size_bytes")
  private Long fileSizeBytes;

  /** SHA-256 of the uploaded content, so the same file can be recognised without keeping it. */
  @Column(name = "content_hash", length = 64)
  private String contentHash;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 20)
  private ImportJobStatus status = ImportJobStatus.STAGED;

  @Column(name = "row_count", nullable = false)
  private int rowCount = 0;

  @Column(name = "valid_row_count", nullable = false)
  private int validRowCount = 0;

  @Column(name = "invalid_row_count", nullable = false)
  private int invalidRowCount = 0;

  @Column(name = "inserted_row_count", nullable = false)
  private int insertedRowCount = 0;

  @Column(name = "skipped_row_count", nullable = false)
  private int skippedRowCount = 0;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "column_mapping", columnDefinition = "jsonb")
  private String columnMapping;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "validation_summary", columnDefinition = "jsonb")
  private String validationSummary;

  /** Unique per import request. See the class note on idempotent replay. */
  @Column(name = "idempotency_key", nullable = false, length = 64, unique = true)
  private String idempotencyKey;

  /** The earlier committed job this one deliberately re-runs. */
  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "replay_of_job_id")
  private ImportJob replayOf;

  @Column(name = "staged_at", nullable = false)
  private LocalDateTime stagedAt;

  @Column(name = "validated_at")
  private LocalDateTime validatedAt;

  @Column(name = "committed_at")
  private LocalDateTime committedAt;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "committed_by")
  private User committedBy;

  @Column(name = "cancelled_at")
  private LocalDateTime cancelledAt;

  @Column(name = "error_message", length = 2000)
  private String errorMessage;

  /** True while nothing has been written to an application table yet. */
  public boolean isPending() {
    return status.isPending();
  }

  /** The count of rows that will not be written, which the preview leads with. */
  public int getProblemRowCount() {
    return invalidRowCount;
  }

  public UUID getId() {
    return id;
  }

  public void setId(UUID id) {
    this.id = id;
  }

  public String getJobCode() {
    return jobCode;
  }

  public void setJobCode(String jobCode) {
    this.jobCode = jobCode;
  }

  public ImportEntityType getEntityType() {
    return entityType;
  }

  public void setEntityType(ImportEntityType entityType) {
    this.entityType = entityType;
  }

  public ImportSourceType getSourceType() {
    return sourceType;
  }

  public void setSourceType(ImportSourceType sourceType) {
    this.sourceType = sourceType;
  }

  public String getOriginalFilename() {
    return originalFilename;
  }

  public void setOriginalFilename(String originalFilename) {
    this.originalFilename = originalFilename;
  }

  public Long getFileSizeBytes() {
    return fileSizeBytes;
  }

  public void setFileSizeBytes(Long fileSizeBytes) {
    this.fileSizeBytes = fileSizeBytes;
  }

  public String getContentHash() {
    return contentHash;
  }

  public void setContentHash(String contentHash) {
    this.contentHash = contentHash;
  }

  public ImportJobStatus getStatus() {
    return status;
  }

  public void setStatus(ImportJobStatus status) {
    this.status = status;
  }

  public int getRowCount() {
    return rowCount;
  }

  public void setRowCount(int rowCount) {
    this.rowCount = rowCount;
  }

  public int getValidRowCount() {
    return validRowCount;
  }

  public void setValidRowCount(int validRowCount) {
    this.validRowCount = validRowCount;
  }

  public int getInvalidRowCount() {
    return invalidRowCount;
  }

  public void setInvalidRowCount(int invalidRowCount) {
    this.invalidRowCount = invalidRowCount;
  }

  public int getInsertedRowCount() {
    return insertedRowCount;
  }

  public void setInsertedRowCount(int insertedRowCount) {
    this.insertedRowCount = insertedRowCount;
  }

  public int getSkippedRowCount() {
    return skippedRowCount;
  }

  public void setSkippedRowCount(int skippedRowCount) {
    this.skippedRowCount = skippedRowCount;
  }

  public String getColumnMapping() {
    return columnMapping;
  }

  public void setColumnMapping(String columnMapping) {
    this.columnMapping = columnMapping;
  }

  public String getValidationSummary() {
    return validationSummary;
  }

  public void setValidationSummary(String validationSummary) {
    this.validationSummary = validationSummary;
  }

  public String getIdempotencyKey() {
    return idempotencyKey;
  }

  public void setIdempotencyKey(String idempotencyKey) {
    this.idempotencyKey = idempotencyKey;
  }

  public ImportJob getReplayOf() {
    return replayOf;
  }

  public void setReplayOf(ImportJob replayOf) {
    this.replayOf = replayOf;
  }

  public LocalDateTime getStagedAt() {
    return stagedAt;
  }

  public void setStagedAt(LocalDateTime stagedAt) {
    this.stagedAt = stagedAt;
  }

  public LocalDateTime getValidatedAt() {
    return validatedAt;
  }

  public void setValidatedAt(LocalDateTime validatedAt) {
    this.validatedAt = validatedAt;
  }

  public LocalDateTime getCommittedAt() {
    return committedAt;
  }

  public void setCommittedAt(LocalDateTime committedAt) {
    this.committedAt = committedAt;
  }

  public User getCommittedBy() {
    return committedBy;
  }

  public void setCommittedBy(User committedBy) {
    this.committedBy = committedBy;
  }

  public LocalDateTime getCancelledAt() {
    return cancelledAt;
  }

  public void setCancelledAt(LocalDateTime cancelledAt) {
    this.cancelledAt = cancelledAt;
  }

  public String getErrorMessage() {
    return errorMessage;
  }

  public void setErrorMessage(String errorMessage) {
    this.errorMessage = errorMessage;
  }
}
