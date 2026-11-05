package com.meridian.realestate.repository;

import com.meridian.realestate.domain.Document;
import com.meridian.realestate.domain.DocumentStatus;
import com.meridian.realestate.domain.DocumentType;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * The document register.
 *
 * <p>The filing columns are queried one at a time rather than through a generic
 * "type plus identifier" pair, because each of them is a real foreign key: "everything filed
 * against this lease" is an indexed lookup the database can prove, not a filter over a string that
 * might have been spelled differently.</p>
 *
 * <p>No paged method here carries a literal {@code order by}: sorting arrives as a {@link Pageable}
 * that has already been validated against a whitelist, so the caller's requested order is the one
 * that is applied. The non-paged expiry watchlist does order, because earliest-expiring-first is
 * intrinsic to what a watchlist is.</p>
 *
 * <p>Every read filters on {@code deletedAt is null}. A document is archived rather than removed,
 * because the record that it existed - and when it expired - outlives the file.</p>
 */
public interface DocumentRepository extends JpaRepository<Document, UUID> {

  Optional<Document> findByIdAndDeletedAtIsNull(UUID id);

  Optional<Document> findByDocumentCodeIgnoreCaseAndDeletedAtIsNull(String documentCode);

  boolean existsByDocumentCodeIgnoreCaseAndDeletedAtIsNull(String documentCode);

  Page<Document> findAllByDeletedAtIsNull(Pageable pageable);

  List<Document> findAllByDeletedAtIsNull();

  long countByDeletedAtIsNull();

  /** How many documents of one type the register holds, for the type filter's counts. */
  long countByDocumentTypeAndDeletedAtIsNull(DocumentType documentType);

  long countByStatusAndDeletedAtIsNull(DocumentStatus status);

  /**
   * The same counts in one round trip, for a filter bar that has to render every type at once.
   *
   * <p>Each row is {@code [DocumentType, Long]}.</p>
   */
  @Query(
      "select d.documentType, count(d) from Document d "
          + "where d.deletedAt is null group by d.documentType order by d.documentType")
  List<Object[]> countGroupedByType();

  /** Everything filed against one property. */
  List<Document> findAllByPropertyIdAndDeletedAtIsNull(UUID propertyId);

  /** Everything filed against one unit. */
  List<Document> findAllByUnitIdAndDeletedAtIsNull(UUID unitId);

  /** Everything filed against one lease. */
  List<Document> findAllByLeaseIdAndDeletedAtIsNull(UUID leaseId);

  /** Everything filed against one tenant. */
  List<Document> findAllByTenantIdAndDeletedAtIsNull(UUID tenantId);

  /** Everything filed against one owner. */
  List<Document> findAllByOwnerIdAndDeletedAtIsNull(UUID ownerId);

  /** Everything filed against one buyer. */
  List<Document> findAllByBuyerIdAndDeletedAtIsNull(UUID buyerId);

  /**
   * Everything filed against one sales opportunity.
   *
   * <p>A plain identifier rather than an association: the {@code sales_opportunities} table exists
   * in the Flyway chain, but this model has no entity for it.</p>
   */
  List<Document> findAllBySaleIdAndDeletedAtIsNull(UUID saleId);

  /** Everything filed against one maintenance request, for the same reason as the sale above. */
  List<Document> findAllByMaintenanceRequestIdAndDeletedAtIsNull(UUID maintenanceRequestId);

  /** Documents that expire strictly before a date, whatever their status. */
  List<Document> findByExpiryDateBeforeAndDeletedAtIsNull(LocalDate date);

  /**
   * The expiry watchlist: live documents that expire on or before a date, earliest first.
   *
   * <p>The status is a parameter rather than a literal so that only the current versions are
   * reported - a superseded or archived document is not a renewal the desk has to act on - and the
   * ordering is part of the query because a watchlist that is not in date order is not a
   * watchlist.</p>
   */
  @Query(
      "select d from Document d "
          + "where d.deletedAt is null and d.expiryDate is not null "
          + "and d.status = :status and d.expiryDate <= :date "
          + "order by d.expiryDate asc, d.id asc")
  List<Document> findExpiryWatchlist(
      @Param("status") DocumentStatus status, @Param("date") LocalDate date);

  /**
   * The register with the property fetched, so a list of documents can render its filing target
   * without one query per row.
   *
   * <p>The sort still arrives as a {@link Pageable} - this query pins no order of its own.</p>
   */
  @Query(
      value = "select d from Document d left join fetch d.property where d.deletedAt is null",
      countQuery = "select count(d) from Document d where d.deletedAt is null")
  Page<Document> findAllWithProperty(Pageable pageable);

  @Query("select d from Document d left join fetch d.property where d.id = :id")
  Optional<Document> findByIdWithProperty(@Param("id") UUID id);
}
