package com.meridian.realestate.dto;

import com.meridian.realestate.domain.Document;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.UUID;

/**
 * The document register's API contract: what the register lists, what one register entry reads as,
 * and what it takes to file, change, archive and supersede one.
 *
 * <p><b>Why these records exist.</b> A {@code Document} entity is never returned from an endpoint. The
 * entity carries a dozen lazy filing associations and the housekeeping columns, and a client that
 * received it could not tell which fields are a promise and which are an implementation detail. Each
 * record here is the deliberate subset the register actually promises, with every enum flattened to
 * its name so a client never has to know the ordinal or the class.</p>
 *
 * <p><b>The rule these records protect: there is no file, only a record that one exists.</b> This
 * product has no object storage and the migration that creates {@code documents} says so. The
 * {@code fileReference}, {@code mimeType}, {@code byteSize} and {@code pageCount} fields here are
 * therefore <em>recorded metadata the caller supplies</em> - the location a document lives at and
 * the shape of it - and never bytes. Nothing here accepts an upload, mints a URL, or generates a
 * path; doing any of those would be inventing infrastructure the schema deliberately does not have.
 * Where the register renders a document it renders the reference the caller recorded, or nothing.</p>
 *
 * <p><b>Filing targets are explicit, not a polymorphic pair.</b> The schema makes every one of
 * {@code propertyId}, {@code unitId}, {@code leaseId}, {@code tenantId}, {@code ownerId},
 * {@code buyerId}, {@code saleId} and {@code maintenanceRequestId} a real foreign key, and requires
 * at least one. The service refuses a create that names none of them as a 400 before the database's
 * {@code ck_documents_scope} would reject it as an opaque integrity error - an unfiled document is
 * not a register entry, and saying so in the product's own error envelope is how the caller learns
 * it.</p>
 */
public final class DocumentDtos {

  private DocumentDtos() {}

  /** The closed set of types {@code ck_documents_type} allows, as a regex for the request records. */
  private static final String TYPE_SET =
      "LEASE_AGREEMENT|SALE_DEED|TITLE_DEED|AGREEMENT_TO_SELL|NOC|TAX_INVOICE|INSURANCE_POLICY"
          + "|COMPLIANCE_CERTIFICATE|ID_PROOF|FLOOR_PLAN|SITE_PLAN|VALUATION_REPORT|HANDOVER_NOTE"
          + "|POSSESSION_LETTER|UTILITY_BILL|OTHER";

  /** The closed set of statuses {@code ck_documents_status} allows. */
  private static final String STATUS_SET = "ACTIVE|SUPERSEDED|EXPIRED|ARCHIVED";

  // ---------------------------------------------------------------------------
  // Reads
  // ---------------------------------------------------------------------------

  /**
   * One row of the register: enough to identify a document, what it is filed against, and where it
   * lives.
   *
   * <p>The filing targets are flattened to an id, a code and, where the entity carries one, a name,
   * so a list can render "filed against ME-P-00007" without a request per row.</p>
   */
  public record DocumentSummary(
      UUID id,
      String documentCode,
      String title,
      String documentType,
      String status,
      UUID propertyId,
      String propertyCode,
      String propertyName,
      UUID unitId,
      String unitCode,
      UUID leaseId,
      String leaseCode,
      UUID tenantId,
      String tenantCode,
      UUID ownerId,
      String ownerCode,
      UUID buyerId,
      String buyerCode,
      UUID saleId,
      UUID maintenanceRequestId,
      boolean confidential,
      boolean superseded,
      UUID supersededById,
      String supersededByCode,
      int version,
      String fileReference,
      String mimeType,
      Long byteSize,
      Integer pageCount,
      LocalDate issuedDate,
      LocalDate expiryDate,
      boolean verified,
      java.time.LocalDateTime createdAt,
      java.time.LocalDateTime updatedAt) {

    public static DocumentSummary from(Document document) {
      return new DocumentSummary(
          document.getId(),
          document.getDocumentCode(),
          document.getTitle(),
          document.getDocumentType() == null ? null : document.getDocumentType().name(),
          document.getStatus() == null ? null : document.getStatus().name(),
          document.getProperty() == null ? null : document.getProperty().getId(),
          document.getProperty() == null ? null : document.getProperty().getPropertyCode(),
          document.getProperty() == null ? null : document.getProperty().getName(),
          document.getUnit() == null ? null : document.getUnit().getId(),
          document.getUnit() == null ? null : document.getUnit().getUnitCode(),
          document.getLease() == null ? null : document.getLease().getId(),
          document.getLease() == null ? null : document.getLease().getLeaseCode(),
          document.getTenant() == null ? null : document.getTenant().getId(),
          document.getTenant() == null ? null : document.getTenant().getTenantCode(),
          document.getOwner() == null ? null : document.getOwner().getId(),
          document.getOwner() == null ? null : document.getOwner().getOwnerCode(),
          document.getBuyer() == null ? null : document.getBuyer().getId(),
          document.getBuyer() == null ? null : document.getBuyer().getBuyerCode(),
          document.getSaleId(),
          document.getMaintenanceRequestId(),
          document.isConfidential(),
          document.isSuperseded(),
          document.getSupersededBy() == null ? null : document.getSupersededBy().getId(),
          document.getSupersededBy() == null ? null : document.getSupersededBy().getDocumentCode(),
          document.getVersion(),
          document.getFileReference(),
          document.getMimeType(),
          document.getByteSize(),
          document.getPageCount(),
          document.getIssuedDate(),
          document.getExpiryDate(),
          document.getVerifiedAt() != null,
          document.getCreatedAt(),
          document.getUpdatedAt());
    }
  }

  /**
   * One register entry's full record: the summary plus the notes and the verification it carries.
   *
   * <p>Verification is read out in full because the schema makes it all-or-nothing - a verified
   * document records both who checked it and when - so the two travel together here as they do in
   * the table.</p>
   */
  public record DocumentDetail(
      UUID id,
      String documentCode,
      String title,
      String documentType,
      String status,
      UUID propertyId,
      String propertyCode,
      String propertyName,
      UUID unitId,
      String unitCode,
      UUID leaseId,
      String leaseCode,
      UUID tenantId,
      String tenantCode,
      UUID ownerId,
      String ownerCode,
      UUID buyerId,
      String buyerCode,
      UUID saleId,
      UUID maintenanceRequestId,
      boolean confidential,
      boolean superseded,
      UUID supersededById,
      String supersededByCode,
      int version,
      String fileReference,
      String mimeType,
      Long byteSize,
      Integer pageCount,
      LocalDate issuedDate,
      LocalDate expiryDate,
      UUID verifiedById,
      String verifiedByName,
      java.time.LocalDateTime verifiedAt,
      UUID uploadedById,
      String uploadedByName,
      String notes,
      java.time.LocalDateTime createdAt,
      java.time.LocalDateTime updatedAt) {

    public static DocumentDetail from(Document document) {
      return new DocumentDetail(
          document.getId(),
          document.getDocumentCode(),
          document.getTitle(),
          document.getDocumentType() == null ? null : document.getDocumentType().name(),
          document.getStatus() == null ? null : document.getStatus().name(),
          document.getProperty() == null ? null : document.getProperty().getId(),
          document.getProperty() == null ? null : document.getProperty().getPropertyCode(),
          document.getProperty() == null ? null : document.getProperty().getName(),
          document.getUnit() == null ? null : document.getUnit().getId(),
          document.getUnit() == null ? null : document.getUnit().getUnitCode(),
          document.getLease() == null ? null : document.getLease().getId(),
          document.getLease() == null ? null : document.getLease().getLeaseCode(),
          document.getTenant() == null ? null : document.getTenant().getId(),
          document.getTenant() == null ? null : document.getTenant().getTenantCode(),
          document.getOwner() == null ? null : document.getOwner().getId(),
          document.getOwner() == null ? null : document.getOwner().getOwnerCode(),
          document.getBuyer() == null ? null : document.getBuyer().getId(),
          document.getBuyer() == null ? null : document.getBuyer().getBuyerCode(),
          document.getSaleId(),
          document.getMaintenanceRequestId(),
          document.isConfidential(),
          document.isSuperseded(),
          document.getSupersededBy() == null ? null : document.getSupersededBy().getId(),
          document.getSupersededBy() == null ? null : document.getSupersededBy().getDocumentCode(),
          document.getVersion(),
          document.getFileReference(),
          document.getMimeType(),
          document.getByteSize(),
          document.getPageCount(),
          document.getIssuedDate(),
          document.getExpiryDate(),
          document.getVerifiedBy() == null ? null : document.getVerifiedBy().getId(),
          document.getVerifiedBy() == null ? null : document.getVerifiedBy().getFullName(),
          document.getVerifiedAt(),
          document.getUploadedBy() == null ? null : document.getUploadedBy().getId(),
          document.getUploadedBy() == null ? null : document.getUploadedBy().getFullName(),
          document.getNotes(),
          document.getCreatedAt(),
          document.getUpdatedAt());
    }
  }

  /**
   * One line of the expiry watchlist.
   *
   * <p>The watchlist exists because knowing a policy lapses is a real operational need, so the entry
   * carries the date and how many days are left - negative when the deadline has already passed -
   * rather than only the document. It is built from live, current-version documents with an expiry
   * date; an archived or superseded document is not a renewal anybody has to act on.</p>
   */
  public record ExpiryEntry(
      UUID id,
      String documentCode,
      String title,
      String documentType,
      UUID propertyId,
      String propertyCode,
      String propertyName,
      UUID leaseId,
      String leaseCode,
      LocalDate expiryDate,
      long daysUntilExpiry,
      boolean confidential) {

    public static ExpiryEntry from(Document document, LocalDate asOf) {
      return new ExpiryEntry(
          document.getId(),
          document.getDocumentCode(),
          document.getTitle(),
          document.getDocumentType() == null ? null : document.getDocumentType().name(),
          document.getProperty() == null ? null : document.getProperty().getId(),
          document.getProperty() == null ? null : document.getProperty().getPropertyCode(),
          document.getProperty() == null ? null : document.getProperty().getName(),
          document.getLease() == null ? null : document.getLease().getId(),
          document.getLease() == null ? null : document.getLease().getLeaseCode(),
          document.getExpiryDate(),
          document.getExpiryDate() == null
              ? 0L
              : java.time.temporal.ChronoUnit.DAYS.between(asOf, document.getExpiryDate()),
          document.isConfidential());
    }
  }

  // ---------------------------------------------------------------------------
  // Writes
  // ---------------------------------------------------------------------------

  /**
   * File a document against something.
   *
   * <p>The metadata fields are recorded, not uploaded: {@code fileReference} is where the document
   * lives, {@code mimeType} is what it is, and {@code byteSize} and {@code pageCount} describe it.
   * See the class note - there is no file storage, and no field here carries bytes.</p>
   */
  public record CreateDocumentRequest(
      @NotBlank @Size(max = 255) String title,
      @NotBlank
          @Pattern(regexp = TYPE_SET, message = "must be a document type")
          String documentType,
      UUID propertyId,
      UUID unitId,
      UUID leaseId,
      UUID tenantId,
      UUID ownerId,
      UUID buyerId,
      UUID saleId,
      UUID maintenanceRequestId,
      @Size(max = 1000) String fileReference,
      @Size(max = 120) String mimeType,
      @Min(value = 0, message = "cannot be negative") Long byteSize,
      @Min(value = 1, message = "must be at least one page") Integer pageCount,
      Boolean confidential,
      LocalDate issuedDate,
      LocalDate expiryDate,
      @Size(max = 1000) String notes) {}

  /** Change a register entry's details. Every field is optional; only the ones supplied are applied. */
  public record UpdateDocumentRequest(
      @Size(max = 255) String title,
      @Pattern(regexp = TYPE_SET, message = "must be a document type") String documentType,
      UUID propertyId,
      UUID unitId,
      UUID leaseId,
      UUID tenantId,
      UUID ownerId,
      UUID buyerId,
      UUID saleId,
      UUID maintenanceRequestId,
      @Size(max = 1000) String fileReference,
      @Size(max = 120) String mimeType,
      @Min(value = 0, message = "cannot be negative") Long byteSize,
      @Min(value = 1, message = "must be at least one page") Integer pageCount,
      Boolean confidential,
      LocalDate issuedDate,
      LocalDate expiryDate,
      @Size(max = 1000) String notes) {}

  /**
   * Supersede a document with a later version.
   *
   * <p>The request names the replacement. The document in the path is the one being replaced: it is
   * moved to {@code SUPERSEDED} and its {@code supersededBy} is pointed at the newer record, exactly
   * as the schema's {@code ck_documents_superseded} requires. A document may not name itself, and a
   * document that has already been superseded may not be superseded again.</p>
   */
  public record SupersedeDocumentRequest(
      @NotNull UUID supersededById, @Size(max = 1000) String notes) {}

  /** The status filter set, exposed so a caller can be told the accepted values. */
  public static java.util.List<String> statusValues() {
    return java.util.List.of(STATUS_SET.split("\\|"));
  }

  /** The type filter set, exposed so a caller can be told the accepted values. */
  public static java.util.List<String> typeValues() {
    return java.util.List.of(TYPE_SET.split("\\|"));
  }
}
