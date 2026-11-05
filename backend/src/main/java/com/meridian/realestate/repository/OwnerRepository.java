package com.meridian.realestate.repository;

import com.meridian.realestate.domain.Owner;
import com.meridian.realestate.domain.OwnerStatus;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * The owner register.
 *
 * <p>No method here carries a literal {@code order by}: paging and sorting arrive as a
 * {@link Pageable} that {@code PageRequestFactory} has already validated against a whitelist, so
 * the caller's requested order is the one that is actually applied.</p>
 *
 * <p>Every read filters on {@code deleted_at is null}. An owner is archived rather than removed,
 * because the ownership rows, statements and audit trail that reference them must stay coherent.
 * What an owner actually holds lives in {@link PropertyOwnershipRepository}.</p>
 */
public interface OwnerRepository extends JpaRepository<Owner, UUID> {

  Optional<Owner> findByIdAndDeletedAtIsNull(UUID id);

  /** Owners are looked up by code, case-insensitively, so a code typed in either case finds them. */
  Optional<Owner> findByOwnerCodeIgnoreCaseAndDeletedAtIsNull(String ownerCode);

  boolean existsByOwnerCodeIgnoreCaseAndDeletedAtIsNull(String ownerCode);

  Page<Owner> findAllByDeletedAtIsNull(Pageable pageable);

  List<Owner> findAllByDeletedAtIsNull();

  long countByDeletedAtIsNull();

  long countByStatusAndDeletedAtIsNull(OwnerStatus status);

  List<Owner> findAllByStatusInAndDeletedAtIsNull(Collection<OwnerStatus> statuses);

  /**
   * Owners matching a free-text term across the fields the register is searched by.
   *
   * <p>The email comparison is case-insensitive rather than relying on the stored value: the
   * database keeps addresses lower-cased ({@code ck_owners_email_lower}), but a term typed by hand
   * will not be, and a search that only worked for one casing would look broken.</p>
   */
  @Query(
      "select o from Owner o "
          + "where o.deletedAt is null and ("
          + "  lower(o.displayName) like lower(concat('%', :term, '%')) "
          + "  or lower(o.ownerCode) like lower(concat('%', :term, '%')) "
          + "  or lower(coalesce(o.primaryContactName, '')) like lower(concat('%', :term, '%')) "
          + "  or lower(coalesce(o.email, '')) like lower(concat('%', :term, '%')) "
          + "  or coalesce(o.phone, '') like concat('%', :term, '%') "
          + "  or lower(coalesce(o.city, '')) like lower(concat('%', :term, '%'))"
          + ")")
  Page<Owner> search(@Param("term") String term, Pageable pageable);
}
