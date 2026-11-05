package com.meridian.realestate.repository;

import com.meridian.realestate.domain.Office;
import com.meridian.realestate.domain.OfficeStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * The office register.
 *
 * <p>No method here carries a literal {@code order by}: paging and sorting arrive as a
 * {@link Pageable} that {@code PageRequestFactory} has already validated against a whitelist.</p>
 *
 * <p>Every read filters on {@code deleted_at is null}. A branch is deactivated rather than removed,
 * because the agents, leads and properties that were attributed to it must keep reporting under the
 * office they actually belonged to.</p>
 */
public interface OfficeRepository extends JpaRepository<Office, UUID> {

  Optional<Office> findByIdAndDeletedAtIsNull(UUID id);

  Optional<Office> findByOfficeCodeIgnoreCaseAndDeletedAtIsNull(String officeCode);

  boolean existsByOfficeCodeIgnoreCaseAndDeletedAtIsNull(String officeCode);

  List<Office> findAllByDeletedAtIsNull();

  Page<Office> findAllByDeletedAtIsNull(Pageable pageable);

  long countByDeletedAtIsNull();

  long countByStatusAndDeletedAtIsNull(OfficeStatus status);

  List<Office> findAllByStatusAndDeletedAtIsNull(OfficeStatus status);

  List<Office> findAllByCityAndDeletedAtIsNull(String city);

  /**
   * The registered office, if one has been designated.
   *
   * <p>Returns an {@link Optional} rather than a list because the schema allows at most one: the
   * partial unique index {@code uq_offices_single_head} permits a single live row with
   * {@code is_head_office = true}. Two such rows would silently double-count in office-level
   * reporting, so the model refuses to be able to represent the situation at all.</p>
   */
  Optional<Office> findByIsHeadOfficeTrueAndDeletedAtIsNull();
}
