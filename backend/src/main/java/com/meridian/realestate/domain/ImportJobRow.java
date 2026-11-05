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
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One row of an uploaded file, staged.
 *
 * <p>A row is judged on its own: a single bad cell rejects that row and nothing else, and the
 * reasons are recorded against the row so the preview can point at a specific field rather than
 * refusing the whole file. The row is therefore evidence of what the file said, which is why it
 * keeps the parsed form and the mapped form side by side instead of overwriting one with the
 * other.</p>
 *
 * <p>{@code rawData} is the row as it was parsed, keyed by the source column heading, and
 * {@code mappedData} is the row as it will be written after mapping and coercion - null until the
 * mapping is applied. Both are jsonb because their shape follows the entity type being imported
 * and cannot be a fixed set of columns; {@code validationErrors} is jsonb for the same reason, one
 * entry per problem naming the field and what was wrong with it.</p>
 *
 * <p>{@code entityId} records the record this row produced at commit time. That is what makes a
 * re-commit unable to insert a second copy: a row that already names its record is not written
 * again.</p>
 *
 * <p>There is no base class: the table carries {@code created_at} and {@code updated_at} but no
 * who-changed-it columns and no soft delete, because a staged row belongs to its job and is
 * discarded with it rather than archived. {@link Auditable} would map three columns this table does
 * not have.</p>
 */
@Entity
@Table(name = "import_job_rows")
public class ImportJobRow {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "job_id", nullable = false)
  private ImportJob job;

  /** The source line number, 1-based, so a problem can be pointed at in the original file. */
  @Column(name = "row_number", nullable = false)
  private int rowNumber;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "raw_data", columnDefinition = "jsonb", nullable = false)
  private String rawData;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "mapped_data", columnDefinition = "jsonb")
  private String mappedData;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 20)
  private ImportRowStatus status = ImportRowStatus.PENDING;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "validation_errors", columnDefinition = "jsonb")
  private String validationErrors;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "validation_warnings", columnDefinition = "jsonb")
  private String validationWarnings;

  /** The record this row produced, set when it is committed. */
  @Column(name = "entity_id")
  private UUID entityId;

  @Column(name = "entity_label", length = 200)
  private String entityLabel;

  @Column(name = "created_at", nullable = false, updatable = false)
  private LocalDateTime createdAt;

  @Column(name = "updated_at", nullable = false)
  private LocalDateTime updatedAt;

  @PrePersist
  void onCreate() {
    LocalDateTime now = LocalDateTime.now();
    if (createdAt == null) createdAt = now;
    updatedAt = now;
  }

  @PreUpdate
  void onUpdate() {
    updatedAt = LocalDateTime.now();
  }

  /** True when the row will not be written, and says why. */
  public boolean isProblem() {
    return status.isProblem();
  }

  /** True when the row has already produced a record, so committing it again would duplicate. */
  public boolean isCommitted() {
    return entityId != null;
  }

  public UUID getId() {
    return id;
  }

  public void setId(UUID id) {
    this.id = id;
  }

  public ImportJob getJob() {
    return job;
  }

  public void setJob(ImportJob job) {
    this.job = job;
  }

  public int getRowNumber() {
    return rowNumber;
  }

  public void setRowNumber(int rowNumber) {
    this.rowNumber = rowNumber;
  }

  public String getRawData() {
    return rawData;
  }

  public void setRawData(String rawData) {
    this.rawData = rawData;
  }

  public String getMappedData() {
    return mappedData;
  }

  public void setMappedData(String mappedData) {
    this.mappedData = mappedData;
  }

  public ImportRowStatus getStatus() {
    return status;
  }

  public void setStatus(ImportRowStatus status) {
    this.status = status;
  }

  public String getValidationErrors() {
    return validationErrors;
  }

  public void setValidationErrors(String validationErrors) {
    this.validationErrors = validationErrors;
  }

  public String getValidationWarnings() {
    return validationWarnings;
  }

  public void setValidationWarnings(String validationWarnings) {
    this.validationWarnings = validationWarnings;
  }

  public UUID getEntityId() {
    return entityId;
  }

  public void setEntityId(UUID entityId) {
    this.entityId = entityId;
  }

  public String getEntityLabel() {
    return entityLabel;
  }

  public void setEntityLabel(String entityLabel) {
    this.entityLabel = entityLabel;
  }

  public LocalDateTime getCreatedAt() {
    return createdAt;
  }

  public void setCreatedAt(LocalDateTime createdAt) {
    this.createdAt = createdAt;
  }

  public LocalDateTime getUpdatedAt() {
    return updatedAt;
  }

  public void setUpdatedAt(LocalDateTime updatedAt) {
    this.updatedAt = updatedAt;
  }
}
