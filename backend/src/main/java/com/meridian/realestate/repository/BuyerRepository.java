package com.meridian.realestate.repository;

import com.meridian.realestate.domain.Buyer;
import com.meridian.realestate.domain.BuyerStatus;
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
 * The buyer register.
 *
 * <p>No method here carries a literal {@code order by}: paging and sorting arrive as a
 * {@link Pageable} that {@code PageRequestFactory} has already validated against a whitelist.</p>
 *
 * <p>Every read filters on {@code deleted_at is null}. A buyer is archived rather than removed,
 * because the offers, viewings and sales that reference them must stay coherent. What a buyer is
 * looking for lives in {@link BuyerRequirementRepository}, not here.</p>
 */
public interface BuyerRepository extends JpaRepository<Buyer, UUID> {

  Optional<Buyer> findByIdAndDeletedAtIsNull(UUID id);

  /** Buyers are looked up by code, case-insensitively, so a code typed in either case finds them. */
  Optional<Buyer> findByBuyerCodeIgnoreCaseAndDeletedAtIsNull(String buyerCode);

  boolean existsByBuyerCodeIgnoreCaseAndDeletedAtIsNull(String buyerCode);

  Optional<Buyer> findByEmailIgnoreCaseAndDeletedAtIsNull(String email);

  boolean existsByEmailIgnoreCaseAndDeletedAtIsNull(String email);

  Page<Buyer> findAllByDeletedAtIsNull(Pageable pageable);

  List<Buyer> findAllByDeletedAtIsNull();

  long countByDeletedAtIsNull();

  long countByStatusAndDeletedAtIsNull(BuyerStatus status);

  List<Buyer> findAllByStatusInAndDeletedAtIsNull(Collection<BuyerStatus> statuses);

  /** Buyers matching a free-text term across the fields the register is searched by. */
  @Query(
      "select b from Buyer b "
          + "where b.deletedAt is null and ("
          + "  lower(b.displayName) like lower(concat('%', :term, '%')) "
          + "  or lower(b.buyerCode) like lower(concat('%', :term, '%')) "
          + "  or lower(coalesce(b.primaryContactName, '')) like lower(concat('%', :term, '%')) "
          + "  or lower(coalesce(b.email, '')) like lower(concat('%', :term, '%')) "
          + "  or coalesce(b.phone, '') like concat('%', :term, '%') "
          + "  or lower(coalesce(b.city, '')) like lower(concat('%', :term, '%'))"
          + ")")
  Page<Buyer> search(@Param("term") String term, Pageable pageable);
}
