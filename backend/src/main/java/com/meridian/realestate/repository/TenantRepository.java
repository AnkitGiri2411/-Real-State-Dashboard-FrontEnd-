package com.meridian.realestate.repository;

import com.meridian.realestate.domain.Tenant;
import com.meridian.realestate.domain.TenantStatus;
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
 * The tenant register.
 *
 * <p>No method here carries a literal {@code order by}: paging and sorting arrive as a
 * {@link Pageable} that {@code PageRequestFactory} has already validated against a whitelist.</p>
 *
 * <p>Every read filters on {@code deleted_at is null}. A tenant is archived rather than removed,
 * because the leases, invoices and audit rows that reference them must stay coherent.</p>
 */
public interface TenantRepository extends JpaRepository<Tenant, UUID> {

  Optional<Tenant> findByIdAndDeletedAtIsNull(UUID id);

  Optional<Tenant> findByTenantCodeIgnoreCaseAndDeletedAtIsNull(String tenantCode);

  boolean existsByTenantCodeIgnoreCaseAndDeletedAtIsNull(String tenantCode);

  /**
   * A tenant found by email.
   *
   * <p>Emails are stored lower-cased by the database ({@code ck_tenants_email_lower}), so this is
   * compared case-insensitively rather than relying on the caller normalising first: an enquiry
   * that arrives with a capitalised address still has to find its tenant.</p>
   */
  Optional<Tenant> findByEmailIgnoreCaseAndDeletedAtIsNull(String email);

  boolean existsByEmailIgnoreCaseAndDeletedAtIsNull(String email);

  /**
   * Whether this phone number is already on the register.
   *
   * <p>Exact rather than normalised: the product records the number as given, and it is the
   * duplicate check on intake rather than a search.</p>
   */
  boolean existsByPhoneAndDeletedAtIsNull(String phone);

  Page<Tenant> findAllByDeletedAtIsNull(Pageable pageable);

  List<Tenant> findAllByDeletedAtIsNull();

  long countByDeletedAtIsNull();

  long countByStatusAndDeletedAtIsNull(TenantStatus status);

  List<Tenant> findAllByStatusInAndDeletedAtIsNull(Collection<TenantStatus> statuses);

  /** Tenants matching a free-text term across the fields the register is searched by. */
  @Query(
      "select t from Tenant t "
          + "where t.deletedAt is null and ("
          + "  lower(t.displayName) like lower(concat('%', :term, '%')) "
          + "  or lower(t.tenantCode) like lower(concat('%', :term, '%')) "
          + "  or lower(coalesce(t.primaryContactName, '')) like lower(concat('%', :term, '%')) "
          + "  or lower(coalesce(t.email, '')) like lower(concat('%', :term, '%')) "
          + "  or coalesce(t.phone, '') like concat('%', :term, '%') "
          + "  or lower(coalesce(t.city, '')) like lower(concat('%', :term, '%'))"
          + ")")
  Page<Tenant> search(@Param("term") String term, Pageable pageable);
}
