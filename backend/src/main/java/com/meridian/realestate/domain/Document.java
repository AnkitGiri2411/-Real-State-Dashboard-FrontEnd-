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
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * A register entry: what the firm holds, and against what.
 *
 * <p>Titles, agreements, NOCs, policies, valuation reports and compliance certificates are all
 * documents the business runs on, and knowing that a document exists - and when it expires - is a
 * genuine operational need. The document itself lives outside this database, so {@code fileReference}
 * is a reference rather than bytes; this product has no object storage and inventing one would be
 * inventing infrastructure.</p>
 *
 * <p><strong>Filing targets are explicit foreign keys, not a polymorphic pair.</strong> A
 * {@code (entityType, entityId)} shortcut cannot be a foreign key, so a document could be filed
 * against a lease that does not exist and "everything filed against this property" would have to
 * trust a string. With explicit nullable columns the database enforces every reference. The
 * consequence is a wide table, and the wideness is honest: the number of things a document can be
 * filed against is genuinely finite and known. The schema's {@code ck_documents_scope} requires at
 * least one of them - an unfiled document is not a register entry.</p>
 *
 * <p><strong>A document is versioned rather than overwritten.</strong> Superseding points at the
 * newer document through {@link #supersededBy}, and the older row survives so "which version was in
 * force at the time" stays answerable. A document may not supersede itself - the schema carries
 * {@code ck_documents_supersede_not_self} for that - and only a document whose status is
 * {@link DocumentStatus#SUPERSEDED} may name a replacement, which the schema also enforces.</p>
 *
 * <p>{@code saleId} and {@code maintenanceRequestId} are plain identifiers: the Flyway chain does
 * carry foreign keys to {@code sales_opportunities} and {@code maintenance_requests}, but no entity
 * for either table exists in this model, and a {@code @ManyToOne} to a class that does not exist
 * would not compile. They stay UUID columns, so the reference is still an indexed lookup rather
 * than text.</p>
 */
@Entity
@Table(name = "documents")
public class Document extends Auditable {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "document_code", nullable = false, length = 30, unique = true)
  private String documentCode;

  @Column(name = "title", nullable = false, length = 255)
  private String title;

  @Enumerated(EnumType.STRING)
  @Column(name = "document_type", nullable = false, length = 40)
  private DocumentType documentType;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 20)
  private DocumentStatus status = DocumentStatus.ACTIVE;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "property_id")
  private Property property;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "unit_id")
  private Unit unit;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "lease_id")
  private Lease lease;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "tenant_id")
  private Tenant tenant;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "owner_id")
  private Owner owner;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "buyer_id")
  private Buyer buyer;

  /** The opportunity this document belongs to; no {@code SalesOpportunity} entity exists. */
  @Column(name = "sale_id")
  private UUID saleId;

  /** The maintenance request this document belongs to; no entity exists for that table either. */
  @Column(name = "maintenance_request_id")
  private UUID maintenanceRequestId;

  @Column(name = "file_reference", length = 1000)
  private String fileReference;

  @Column(name = "mime_type", length = 120)
  private String mimeType;

  @Column(name = "byte_size")
  private Long byteSize;

  @Column(name = "page_count")
  private Integer pageCount;

  @Column(name = "version", nullable = false)
  private int version = 1;

  /** The newer document that replaced this one. See the class note on versioning. */
  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "superseded_by_id")
  private Document supersededBy;

  /** Maps {@code is_confidential}: withheld from anyone who is not cleared to see it. */
  @Column(name = "is_confidential", nullable = false)
  private boolean confidential = false;

  @Column(name = "issued_date")
  private LocalDate issuedDate;

  /** The date the expiry watchlist is built from. */
  @Column(name = "expiry_date")
  private LocalDate expiryDate;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "verified_by")
  private User verifiedBy;

  @Column(name = "verified_at")
  private LocalDateTime verifiedAt;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "uploaded_by")
  private User uploadedBy;

  @Column(name = "notes", length = 1000)
  private String notes;

  /** True when this document has been replaced by a newer version. */
  public boolean isSuperseded() {
    return status == DocumentStatus.SUPERSEDED;
  }

  public UUID getId() {
    return id;
  }

  public void setId(UUID id) {
    this.id = id;
  }

  public String getDocumentCode() {
    return documentCode;
  }

  public void setDocumentCode(String documentCode) {
    this.documentCode = documentCode;
  }

  public String getTitle() {
    return title;
  }

  public void setTitle(String title) {
    this.title = title;
  }

  public DocumentType getDocumentType() {
    return documentType;
  }

  public void setDocumentType(DocumentType documentType) {
    this.documentType = documentType;
  }

  public DocumentStatus getStatus() {
    return status;
  }

  public void setStatus(DocumentStatus status) {
    this.status = status;
  }

  public Property getProperty() {
    return property;
  }

  public void setProperty(Property property) {
    this.property = property;
  }

  public Unit getUnit() {
    return unit;
  }

  public void setUnit(Unit unit) {
    this.unit = unit;
  }

  public Lease getLease() {
    return lease;
  }

  public void setLease(Lease lease) {
    this.lease = lease;
  }

  public Tenant getTenant() {
    return tenant;
  }

  public void setTenant(Tenant tenant) {
    this.tenant = tenant;
  }

  public Owner getOwner() {
    return owner;
  }

  public void setOwner(Owner owner) {
    this.owner = owner;
  }

  public Buyer getBuyer() {
    return buyer;
  }

  public void setBuyer(Buyer buyer) {
    this.buyer = buyer;
  }

  public UUID getSaleId() {
    return saleId;
  }

  public void setSaleId(UUID saleId) {
    this.saleId = saleId;
  }

  public UUID getMaintenanceRequestId() {
    return maintenanceRequestId;
  }

  public void setMaintenanceRequestId(UUID maintenanceRequestId) {
    this.maintenanceRequestId = maintenanceRequestId;
  }

  public String getFileReference() {
    return fileReference;
  }

  public void setFileReference(String fileReference) {
    this.fileReference = fileReference;
  }

  public String getMimeType() {
    return mimeType;
  }

  public void setMimeType(String mimeType) {
    this.mimeType = mimeType;
  }

  public Long getByteSize() {
    return byteSize;
  }

  public void setByteSize(Long byteSize) {
    this.byteSize = byteSize;
  }

  public Integer getPageCount() {
    return pageCount;
  }

  public void setPageCount(Integer pageCount) {
    this.pageCount = pageCount;
  }

  public int getVersion() {
    return version;
  }

  public void setVersion(int version) {
    this.version = version;
  }

  public Document getSupersededBy() {
    return supersededBy;
  }

  public void setSupersededBy(Document supersededBy) {
    this.supersededBy = supersededBy;
  }

  public boolean isConfidential() {
    return confidential;
  }

  public void setConfidential(boolean confidential) {
    this.confidential = confidential;
  }

  public LocalDate getIssuedDate() {
    return issuedDate;
  }

  public void setIssuedDate(LocalDate issuedDate) {
    this.issuedDate = issuedDate;
  }

  public LocalDate getExpiryDate() {
    return expiryDate;
  }

  public void setExpiryDate(LocalDate expiryDate) {
    this.expiryDate = expiryDate;
  }

  public User getVerifiedBy() {
    return verifiedBy;
  }

  public void setVerifiedBy(User verifiedBy) {
    this.verifiedBy = verifiedBy;
  }

  public LocalDateTime getVerifiedAt() {
    return verifiedAt;
  }

  public void setVerifiedAt(LocalDateTime verifiedAt) {
    this.verifiedAt = verifiedAt;
  }

  public User getUploadedBy() {
    return uploadedBy;
  }

  public void setUploadedBy(User uploadedBy) {
    this.uploadedBy = uploadedBy;
  }

  public String getNotes() {
    return notes;
  }

  public void setNotes(String notes) {
    this.notes = notes;
  }
}
