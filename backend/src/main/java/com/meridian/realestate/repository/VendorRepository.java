package com.meridian.realestate.repository;

import com.meridian.realestate.domain.Vendor;
import com.meridian.realestate.domain.VendorCategory;
import com.meridian.realestate.domain.VendorStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * The vendor register.
 *
 * <p>No method here carries a literal {@code order by}: paging and sorting arrive as a
 * {@link Pageable} the caller has already validated.</p>
 *
 * <p>Every read filters on {@code deleted_at is null}. A vendor is archived rather than removed,
 * because the work orders, expenses and status history that name it must stay coherent.</p>
 */
public interface VendorRepository extends JpaRepository<Vendor, UUID> {

  Optional<Vendor> findByIdAndDeletedAtIsNull(UUID id);

  Optional<Vendor> findByVendorCodeIgnoreCaseAndDeletedAtIsNull(String vendorCode);

  boolean existsByVendorCodeIgnoreCaseAndDeletedAtIsNull(String vendorCode);

  List<Vendor> findAllByDeletedAtIsNull();

  Page<Vendor> findAllByDeletedAtIsNull(Pageable pageable);

  long countByDeletedAtIsNull();

  /** The trades available for a given kind of work. */
  List<Vendor> findAllByCategoryAndDeletedAtIsNull(VendorCategory category);

  /**
   * The approved-list vendors, or the ones that are not on it.
   *
   * <p>{@code is_empanelled} is a preference rather than a gate - an emergency may still use a vendor
   * that is not empanelled - so the caller decides whether it wants the approved set or everything
   * outside it.</p>
   */
  List<Vendor> findAllByEmpanelledAndDeletedAtIsNull(boolean empanelled);

  /** The approved vendors of one trade - the set a request of that category is assigned from. */
  List<Vendor> findAllByEmpanelledTrueAndCategoryAndDeletedAtIsNull(VendorCategory category);

  long countByStatusAndDeletedAtIsNull(VendorStatus status);
}
