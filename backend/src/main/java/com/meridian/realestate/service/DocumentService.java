package com.meridian.realestate.service;

import com.meridian.realestate.config.AppProperties;
import com.meridian.realestate.domain.Buyer;
import com.meridian.realestate.domain.Document;
import com.meridian.realestate.domain.DocumentStatus;
import com.meridian.realestate.domain.DocumentType;
import com.meridian.realestate.domain.Lease;
import com.meridian.realestate.domain.Owner;
import com.meridian.realestate.domain.Property;
import com.meridian.realestate.domain.Tenant;
import com.meridian.realestate.domain.Unit;
import com.meridian.realestate.domain.User;
import com.meridian.realestate.dto.DocumentDtos;
import com.meridian.realestate.dto.ErrorMessages;
import com.meridian.realestate.exception.ApiException;
import com.meridian.realestate.repository.BuyerRepository;
import com.meridian.realestate.repository.DocumentRepository;
import com.meridian.realestate.repository.LeaseRepository;
import com.meridian.realestate.repository.OwnerRepository;
import com.meridian.realestate.repository.PropertyRepository;
import com.meridian.realestate.repository.TenantRepository;
import com.meridian.realestate.repository.UnitRepository;
import com.meridian.realestate.security.AccessScope;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The document register: what the firm holds, against what, and when it lapses.
 *
 * <p><b>Why this class exists.</b> A document is a register entry, not a file. The operations that
 * file one, change it, replace it and withdraw it belong in a single place where the register's shape
 * is visible, because the rules that keep it coherent - it must be filed against something, a
 * superseded entry names its replacement, the watchlist reads only current versions - are stated once
 * here rather than re-derived at each endpoint.</p>
 *
 * <p><b>There is no file.</b> The schema that creates {@code documents} states in its own header that
 * the document itself lives outside this database, and this product has no object storage. The
 * {@code fileReference}, {@code mimeType}, {@code byteSize} and {@code pageCount} columns are
 * therefore <em>recorded metadata</em> the caller supplies: where the document lives and what shape
 * it is. This service never accepts an upload, never mints a URL and never invents a path - doing any
 * of those would be inventing infrastructure the product deliberately does not have. The
 * metadata-based implementation is the intended one.</p>
 *
 * <p><b>The rules it protects.</b></p>
 * <ul>
 *   <li><b>A register entry is filed against something.</b> The schema's {@code ck_documents_scope}
 *       requires at least one of the eight filing columns. A create or an update that would leave the
 *       entry filed against nothing is refused as a 400 <em>before</em> the database would reject it as
 *       an opaque integrity error, so the caller gets a sentence rather than a stack trace.</li>
 *   <li><b>A document cannot supersede itself.</b> Refused as a 409 named
 *       {@code DOCUMENT_SUPERSEDES_ITSELF}, mirroring the schema's {@code ck_documents_supersede_not_self}
 *       but with a reason a person can read.</li>
 *   <li><b>A superseded document cannot be superseded again.</b> Refused as a 409 named
 *       {@code DOCUMENT_ALREADY_SUPERSEDED}, and - crucially - nothing is written on that path: no
 *       status change is stamped and no replacement is touched. A document is replaced once; a second
 *       replacement would make "which version was in force then" unanswerable, which is the one
 *       question the versioning columns exist to answer.</li>
 *   <li><b>The watchlist is a renewal queue, not a history.</b> It reads only live, current-version
 *       documents with an expiry date. An archived entry is not a renewal anybody has to act on, so it
 *       is excluded at the query and again here, so a row that slipped the query cannot reach the desk
 *       as a false alarm.</li>
 *   <li><b>Archiving is soft, and once.</b> A document is withdrawn by stamping {@code deletedAt}; the
 *       register's reads filter on it, so the entry disappears from the live view while the record that
 *       it existed - and when it expired - survives. A second archive is refused rather than silently
 *       re-stamped.</li>
 * </ul>
 *
 * <p><b>A note on numbering.</b> The {@code V2} seed declares thirteen business-number series and the
 * document register is not one of them; the code this service mints is therefore derived from the
 * record's own identity rather than from a counter, and is unique without a series row to contend
 * with. It is reported rather than added, because the migration chain and the repository layer are
 * not this task's to change.</p>
 *
 * <p><b>A note on paging.</b> {@code DocumentRepository} offers the filing filters one at a time but
 * not one combined query for the register's nine filters, and a literal {@code order by} inside a
 * paged query is forbidden by the product. The register is therefore filtered, sorted and sliced
 * here, so the page the caller asked for is the page they get, with correct totals.</p>
 */
@Service
public class DocumentService {

  private final DocumentRepository documents;
  private final PropertyRepository properties;
  private final UnitRepository units;
  private final LeaseRepository leases;
  private final TenantRepository tenants;
  private final OwnerRepository owners;
  private final BuyerRepository buyers;
  private final AuditRecordingService audit;
  private final AccessScope accessScope;
  private final AppProperties config;

  public DocumentService(
      DocumentRepository documents,
      PropertyRepository properties,
      UnitRepository units,
      LeaseRepository leases,
      TenantRepository tenants,
      OwnerRepository owners,
      BuyerRepository buyers,
      AuditRecordingService audit,
      AccessScope accessScope,
      AppProperties config) {
    this.documents = documents;
    this.properties = properties;
    this.units = units;
    this.leases = leases;
    this.tenants = tenants;
    this.owners = owners;
    this.buyers = buyers;
    this.audit = audit;
    this.accessScope = accessScope;
    this.config = config;
  }

  /* ====================================================================== */
  /* Reads                                                                  */
  /* ====================================================================== */

  /** The live register entry behind an id, or a 404. An archived entry is not addressable. */
  @Transactional(readOnly = true)
  public Document require(UUID documentId) {
    return documents
        .findByIdAndDeletedAtIsNull(documentId)
        .orElseThrow(() -> ApiException.notFound(ErrorMessages.friendly("DOCUMENT_NOT_FOUND")));
  }

  /**
   * The register, filtered, searched, sorted and paged.
   *
   * <p>See the class note on paging: the combined repository query does not exist, so the filters and
   * the sort are applied here over the live rows, and the slice that comes back is the page the
   * caller asked for with a correct total.</p>
   */
  @Transactional(readOnly = true)
  public Page<DocumentDtos.DocumentSummary> list(
      UUID propertyId,
      UUID unitId,
      UUID leaseId,
      UUID tenantId,
      UUID ownerId,
      UUID buyerId,
      UUID saleId,
      UUID maintenanceRequestId,
      String type,
      String status,
      Integer expiringWithinDays,
      String search,
      Pageable pageable) {

    DocumentType typeFilter = parseType(type, null);
    DocumentStatus statusFilter = parseStatus(status, null);
    String term = blankToNull(search);
    LocalDate asOf = LocalDate.now();
    LocalDate expiryCeiling = expiringWithinDays == null ? null : asOf.plusDays(expiringWithinDays);

    List<Document> matches =
        documents.findAllByDeletedAtIsNull().stream()
            .filter(document -> propertyId == null || propertyId.equals(idOf(document.getProperty())))
            .filter(document -> unitId == null || unitId.equals(idOf(document.getUnit())))
            .filter(document -> leaseId == null || leaseId.equals(idOf(document.getLease())))
            .filter(document -> tenantId == null || tenantId.equals(idOf(document.getTenant())))
            .filter(document -> ownerId == null || ownerId.equals(idOf(document.getOwner())))
            .filter(document -> buyerId == null || buyerId.equals(idOf(document.getBuyer())))
            .filter(document -> saleId == null || saleId.equals(document.getSaleId()))
            .filter(
                document ->
                    maintenanceRequestId == null
                        || maintenanceRequestId.equals(document.getMaintenanceRequestId()))
            .filter(document -> typeFilter == null || document.getDocumentType() == typeFilter)
            .filter(document -> statusFilter == null || document.getStatus() == statusFilter)
            .filter(
                document ->
                    expiryCeiling == null
                        || (document.getExpiryDate() != null
                            && !document.getExpiryDate().isAfter(expiryCeiling)))
            .filter(document -> term == null || matchesSearch(document, term))
            .toList();

    return pageOf(sortDocuments(matches, pageable.getSort()), pageable, DocumentDtos.DocumentSummary::from);
  }

  /** One register entry's full record. */
  @Transactional(readOnly = true)
  public DocumentDtos.DocumentDetail detail(UUID documentId) {
    return DocumentDtos.DocumentDetail.from(require(documentId));
  }

  /**
   * The expiry watchlist: live documents that lapse on or before the window, soonest first.
   *
   * <p>The window defaults to the firm's configured warning period
   * ({@code app.portfolio.lease-expiry-warning-days}) when the caller gives none, so the register's
   * watchlist and the dashboard's "expiring" rail describe the same horizon. Only current-version
   * documents are returned - a superseded or archived entry is not a renewal the desk has to act on -
   * and the rows are filtered again here as well as in the query, so a stale row cannot reach the
   * desk as a false alarm.</p>
   */
  @Transactional(readOnly = true)
  public List<DocumentDtos.ExpiryEntry> expiring(Integer withinDays) {
    int days =
        withinDays == null || withinDays < 0
            ? config.getPortfolio().getLeaseExpiryWarningDays()
            : withinDays;
    LocalDate asOf = LocalDate.now();
    LocalDate until = asOf.plusDays(days);

    return documents.findExpiryWatchlist(DocumentStatus.ACTIVE, until).stream()
        .filter(document -> !document.isDeleted())
        .filter(document -> document.getStatus() == DocumentStatus.ACTIVE)
        .filter(document -> document.getExpiryDate() != null)
        .map(document -> DocumentDtos.ExpiryEntry.from(document, asOf))
        .toList();
  }

  /* ====================================================================== */
  /* Writes                                                                 */
  /* ====================================================================== */

  /** File a document against something. Its code is minted; its metadata is recorded as supplied. */
  @Transactional
  public DocumentDtos.DocumentDetail create(DocumentDtos.CreateDocumentRequest request) {
    User actor = accessScope.requireUser();

    DocumentType type = parseType(request.documentType(), null);
    if (type == null) {
      throw ApiException.badRequest("A document type is required");
    }

    Document document = new Document();
    document.setDocumentCode(mintCode());
    document.setTitle(request.title().trim());
    document.setDocumentType(type);
    // A new entry is the current version; SUPERSEDED is reached only through supersede().
    document.setStatus(DocumentStatus.ACTIVE);
    document.setVersion(1);
    applyFiling(
        document,
        request.propertyId(),
        request.unitId(),
        request.leaseId(),
        request.tenantId(),
        request.ownerId(),
        request.buyerId(),
        request.saleId(),
        request.maintenanceRequestId());
    assertFiled(document);
    assertPeriod(request.issuedDate(), request.expiryDate());
    document.setFileReference(request.fileReference());
    document.setMimeType(request.mimeType());
    document.setByteSize(request.byteSize());
    document.setPageCount(request.pageCount());
    document.setConfidential(Boolean.TRUE.equals(request.confidential()));
    document.setIssuedDate(request.issuedDate());
    document.setExpiryDate(request.expiryDate());
    document.setNotes(request.notes());
    document.setUploadedBy(actor);
    document.setCreatedBy(actor.getId());
    document.setUpdatedBy(actor.getId());

    Document saved = documents.save(document);

    audit.record(
        actor.getId(),
        actor.getFullName(),
        AuditActions.DOCUMENT,
        AuditActions.CREATE,
        "Registered document " + saved.getDocumentCode() + " (" + saved.getTitle() + ")",
        "DOCUMENT",
        saved.getId(),
        saved.getDocumentCode());

    return DocumentDtos.DocumentDetail.from(saved);
  }

  /** Change a register entry. Only the fields supplied are applied; the code and version are fixed. */
  @Transactional
  public DocumentDtos.DocumentDetail update(
      UUID documentId, DocumentDtos.UpdateDocumentRequest request) {
    User actor = accessScope.requireUser();
    Document document = require(documentId);

    if (request.title() != null && !request.title().isBlank()) {
      document.setTitle(request.title().trim());
    }
    if (request.documentType() != null && !request.documentType().isBlank()) {
      document.setDocumentType(parseType(request.documentType(), document.getDocumentType()));
    }
    if (request.propertyId() != null
        || request.unitId() != null
        || request.leaseId() != null
        || request.tenantId() != null
        || request.ownerId() != null
        || request.buyerId() != null
        || request.saleId() != null
        || request.maintenanceRequestId() != null) {
      // Filing is edited as a set: an omitted column falls back to what is stored, and the result is
      // refused if that would leave the entry filed against nothing.
      applyFiling(
          document,
          orElse(request.propertyId(), idOf(document.getProperty())),
          orElse(request.unitId(), idOf(document.getUnit())),
          orElse(request.leaseId(), idOf(document.getLease())),
          orElse(request.tenantId(), idOf(document.getTenant())),
          orElse(request.ownerId(), idOf(document.getOwner())),
          orElse(request.buyerId(), idOf(document.getBuyer())),
          orElse(request.saleId(), document.getSaleId()),
          orElse(request.maintenanceRequestId(), document.getMaintenanceRequestId()));
    }
    assertFiled(document);
    LocalDate issued = request.issuedDate() == null ? document.getIssuedDate() : request.issuedDate();
    LocalDate expiry = request.expiryDate() == null ? document.getExpiryDate() : request.expiryDate();
    assertPeriod(issued, expiry);
    document.setIssuedDate(issued);
    document.setExpiryDate(expiry);
    if (request.fileReference() != null) document.setFileReference(request.fileReference());
    if (request.mimeType() != null) document.setMimeType(request.mimeType());
    if (request.byteSize() != null) document.setByteSize(request.byteSize());
    if (request.pageCount() != null) document.setPageCount(request.pageCount());
    if (request.confidential() != null) document.setConfidential(request.confidential());
    if (request.notes() != null) document.setNotes(request.notes());
    document.setUpdatedBy(actor.getId());

    Document saved = documents.save(document);

    audit.record(
        actor.getId(),
        actor.getFullName(),
        AuditActions.DOCUMENT,
        AuditActions.UPDATE,
        "Changed document " + saved.getDocumentCode(),
        "DOCUMENT",
        saved.getId(),
        saved.getDocumentCode());

    return DocumentDtos.DocumentDetail.from(saved);
  }

  /**
   * Supersede a document with a later version.
   *
   * <p>The document named by the path is the one being replaced: its {@code supersededBy} is pointed
   * at the newer record and it is moved to {@code SUPERSEDED}. A document cannot name itself
   * ({@code DOCUMENT_SUPERSEDES_ITSELF}) and a document that has already been superseded cannot be
   * superseded again ({@code DOCUMENT_ALREADY_SUPERSEDED}); both are refused as a 409, and on both
   * paths nothing at all is written.</p>
   */
  @Transactional
  public DocumentDtos.DocumentDetail supersede(UUID documentId, UUID supersededById) {
    User actor = accessScope.requireUser();
    Document document = require(documentId);

    if (supersededById == null) {
      throw ApiException.badRequest("The replacement document must be named");
    }
    // Checked before anything is loaded or written, so the refused paths leave no trace.
    if (documentId.equals(supersededById)) {
      throw ApiException.rule(
          "DOCUMENT_SUPERSEDES_ITSELF",
          ErrorMessages.friendly("DOCUMENT_SUPERSEDES_ITSELF"));
    }
    if (document.isSuperseded()) {
      throw ApiException.rule(
          "DOCUMENT_ALREADY_SUPERSEDED",
          ErrorMessages.friendly("DOCUMENT_ALREADY_SUPERSEDED"));
    }

    Document replacement =
        documents
            .findByIdAndDeletedAtIsNull(supersededById)
            .orElseThrow(() -> ApiException.notFound(ErrorMessages.friendly("DOCUMENT_NOT_FOUND")));

    // The replacement carries the next version, so "which version was in force then" stays answerable
    // from the number alone. Written only when it would actually advance the counter.
    if (replacement.getVersion() <= document.getVersion()) {
      replacement.setVersion(document.getVersion() + 1);
      replacement.setUpdatedBy(actor.getId());
      documents.save(replacement);
    }

    document.setSupersededBy(replacement);
    document.setStatus(DocumentStatus.SUPERSEDED);
    document.setUpdatedBy(actor.getId());
    Document saved = documents.save(document);

    audit.record(
        actor.getId(),
        actor.getFullName(),
        AuditActions.DOCUMENT,
        AuditActions.DOCUMENT_SUPERSEDED,
        "Superseded document "
            + saved.getDocumentCode()
            + " with "
            + replacement.getDocumentCode(),
        "DOCUMENT",
        saved.getId(),
        saved.getDocumentCode());

    return DocumentDtos.DocumentDetail.from(saved);
  }

  /**
   * Archive a register entry - a soft delete, once.
   *
   * <p>The entry disappears from the register's live view because every read filters {@code
   * deletedAt}, while the record that it existed survives for the renewals and reports that reference
   * it. A second archive is refused rather than silently re-stamped.</p>
   */
  @Transactional
  public DocumentDtos.DocumentDetail archive(UUID documentId) {
    User actor = accessScope.requireUser();

    Document document =
        documents
            .findById(documentId)
            .orElseThrow(() -> ApiException.notFound(ErrorMessages.friendly("DOCUMENT_NOT_FOUND")));
    if (document.isDeleted()) {
      throw ApiException.rule(
          "DOCUMENT_ALREADY_ARCHIVED", "This document has already been archived");
    }

    document.setDeletedAt(LocalDateTime.now());
    document.setUpdatedBy(actor.getId());
    Document saved = documents.save(document);

    audit.record(
        actor.getId(),
        actor.getFullName(),
        AuditActions.DOCUMENT,
        AuditActions.DELETE,
        "Archived document " + saved.getDocumentCode() + " (" + saved.getTitle() + ")",
        "DOCUMENT",
        saved.getId(),
        saved.getDocumentCode());

    return DocumentDtos.DocumentDetail.from(saved);
  }

  /* ====================================================================== */
  /* Internals                                                              */
  /* ====================================================================== */

  /**
   * Mints a register code from the record's own identity.
   *
   * <p>See the class note on numbering: {@code V2} seeds no document series, so the code is a
   * prefix plus a short, random, upper-case suffix. It is unique without a counter to contend with,
   * and honest - it does not pretend to be a sequence.</p>
   */
  private static String mintCode() {
    return "ME-D-"
        + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase(Locale.ROOT);
  }

  private void applyFiling(
      Document document,
      UUID propertyId,
      UUID unitId,
      UUID leaseId,
      UUID tenantId,
      UUID ownerId,
      UUID buyerId,
      UUID saleId,
      UUID maintenanceRequestId) {
    document.setProperty(propertyId == null ? null : requireProperty(propertyId));
    document.setUnit(unitId == null ? null : requireUnit(unitId));
    document.setLease(leaseId == null ? null : requireLease(leaseId));
    document.setTenant(tenantId == null ? null : requireTenant(tenantId));
    document.setOwner(ownerId == null ? null : requireOwner(ownerId));
    document.setBuyer(buyerId == null ? null : requireBuyer(buyerId));
    document.setSaleId(saleId);
    document.setMaintenanceRequestId(maintenanceRequestId);
  }

  /** Refuses an entry filed against nothing - the schema's own rule, said in the product's words. */
  private static void assertFiled(Document document) {
    boolean filed =
        document.getProperty() != null
            || document.getUnit() != null
            || document.getLease() != null
            || document.getTenant() != null
            || document.getOwner() != null
            || document.getBuyer() != null
            || document.getSaleId() != null
            || document.getMaintenanceRequestId() != null;
    if (!filed) {
      throw ApiException.badRequest(
          "A document must be filed against a property, unit, lease, tenant, owner, buyer, sale or "
              + "maintenance request; an unfiled document is not a register entry");
    }
  }

  /** Refuses a period that runs backwards before the schema's paired constraint has to. */
  private static void assertPeriod(LocalDate issued, LocalDate expiry) {
    if (issued != null && expiry != null && expiry.isBefore(issued)) {
      throw ApiException.badRequest("A document's expiry date cannot fall before its issue date");
    }
  }

  private Property requireProperty(UUID id) {
    return properties
        .findByIdAndDeletedAtIsNull(id)
        .orElseThrow(() -> ApiException.notFound(ErrorMessages.friendly("PROPERTY_NOT_FOUND")));
  }

  private Unit requireUnit(UUID id) {
    return units
        .findByIdAndDeletedAtIsNull(id)
        .orElseThrow(() -> ApiException.notFound(ErrorMessages.friendly("UNIT_NOT_FOUND")));
  }

  private Lease requireLease(UUID id) {
    return leases
        .findByIdAndDeletedAtIsNull(id)
        .orElseThrow(() -> ApiException.notFound(ErrorMessages.friendly("LEASE_NOT_FOUND")));
  }

  private Tenant requireTenant(UUID id) {
    return tenants
        .findByIdAndDeletedAtIsNull(id)
        .orElseThrow(() -> ApiException.notFound(ErrorMessages.friendly("TENANT_NOT_FOUND")));
  }

  private Owner requireOwner(UUID id) {
    return owners
        .findByIdAndDeletedAtIsNull(id)
        .orElseThrow(() -> ApiException.notFound(ErrorMessages.friendly("OWNER_NOT_FOUND")));
  }

  private Buyer requireBuyer(UUID id) {
    return buyers
        .findByIdAndDeletedAtIsNull(id)
        .orElseThrow(() -> ApiException.notFound(ErrorMessages.friendly("BUYER_NOT_FOUND")));
  }

  private static UUID idOf(Property value) {
    return value == null ? null : value.getId();
  }

  private static UUID idOf(Unit value) {
    return value == null ? null : value.getId();
  }

  private static UUID idOf(Lease value) {
    return value == null ? null : value.getId();
  }

  private static UUID idOf(Tenant value) {
    return value == null ? null : value.getId();
  }

  private static UUID idOf(Owner value) {
    return value == null ? null : value.getId();
  }

  private static UUID idOf(Buyer value) {
    return value == null ? null : value.getId();
  }

  private static <T> T orElse(T supplied, T stored) {
    return supplied == null ? stored : supplied;
  }

  private static boolean matchesSearch(Document document, String term) {
    return contains(document.getTitle(), term)
        || contains(document.getDocumentCode(), term)
        || contains(document.getNotes(), term);
  }

  private static boolean contains(String value, String term) {
    return value != null && value.toLowerCase(Locale.ROOT).contains(term);
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.trim().toLowerCase(Locale.ROOT);
  }

  private static DocumentType parseType(String value, DocumentType fallback) {
    if (value == null || value.isBlank()) {
      return fallback;
    }
    try {
      return DocumentType.valueOf(value.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException ex) {
      throw ApiException.badRequest(
          "'" + value + "' is not a document type", DocumentDtos.typeValues());
    }
  }

  private static DocumentStatus parseStatus(String value, DocumentStatus fallback) {
    if (value == null || value.isBlank()) {
      return fallback;
    }
    try {
      return DocumentStatus.valueOf(value.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException ex) {
      throw ApiException.badRequest(
          "'" + value + "' is not a document status", DocumentDtos.statusValues());
    }
  }

  // --------------------------------------------------------------------------
  // Paging and sorting, applied here because the combined repository query does
  // not exist (see the class note on paging).
  // --------------------------------------------------------------------------

  private static List<Document> sortDocuments(List<Document> items, Sort sort) {
    if (sort == null || sort.isUnsorted()) {
      return items;
    }
    Comparator<Document> comparator = null;
    for (Sort.Order order : sort) {
      Comparator<Document> next = documentComparator(order.getProperty(), order.isDescending());
      comparator = comparator == null ? next : comparator.thenComparing(next);
    }
    List<Document> sorted = new ArrayList<>(items);
    if (comparator != null) {
      sorted.sort(comparator);
    }
    return sorted;
  }

  /**
   * The comparator for one field, with nulls always last in either direction.
   *
   * <p>Nulls last when descending matters here in a way it does not for most registers: a document
   * with no expiry date is not a stale one, so a newest-expiring-first view must not push the
   * undated entries to the top as if they were the most urgent.</p>
   */
  private static Comparator<Document> documentComparator(String property, boolean descending) {
    return switch (property == null ? "" : property) {
      case "title" -> comparingNullsLast(document -> lower(document.getTitle()), descending);
      case "documentCode" ->
          comparingNullsLast(document -> lower(document.getDocumentCode()), descending);
      case "documentType" ->
          comparingNullsLast(
              document -> document.getDocumentType() == null ? null : document.getDocumentType().name(),
              descending);
      case "status" ->
          comparingNullsLast(
              document -> document.getStatus() == null ? null : document.getStatus().name(),
              descending);
      case "expiryDate" -> comparingNullsLast(Document::getExpiryDate, descending);
      case "issuedDate" -> comparingNullsLast(Document::getIssuedDate, descending);
      case "updatedAt" -> comparingNullsLast(Document::getUpdatedAt, descending);
      default -> comparingNullsLast(Document::getCreatedAt, true);
    };
  }

  private static <T extends Comparable<? super T>> Comparator<Document> comparingNullsLast(
      Function<Document, T> key, boolean descending) {
    Comparator<T> order =
        descending
            ? Comparator.nullsLast(Comparator.<T>reverseOrder())
            : Comparator.nullsLast(Comparator.<T>naturalOrder());
    return Comparator.comparing(key, order);
  }

  private static String lower(String value) {
    return value == null ? null : value.toLowerCase(Locale.ROOT);
  }

  private static <T, R> Page<R> pageOf(List<T> all, Pageable pageable, Function<T, R> mapper) {
    int total = all.size();
    int size = pageable.isPaged() ? pageable.getPageSize() : total;
    int start = pageable.isPaged() ? (int) Math.min(pageable.getOffset(), (long) total) : 0;
    int end = Math.min(start + size, total);
    List<R> content = all.subList(start, end).stream().map(mapper).toList();
    return new PageImpl<>(content, pageable, total);
  }
}
