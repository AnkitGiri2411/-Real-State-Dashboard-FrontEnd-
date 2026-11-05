package com.meridian.realestate.repository;

import com.meridian.realestate.domain.ApplicationStatus;
import com.meridian.realestate.domain.RentalApplication;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/**
 * Rental applications.
 *
 * <p>No method here carries a literal {@code order by}: paging and sorting arrive as a
 * {@link Pageable} the caller has already validated.</p>
 *
 * <p>Every read filters on {@code deleted_at is null}. The open applications on a unit are read
 * through {@link #findAllByUnitIdAndStatusInAndDeletedAtIsNull}, which mirrors the schema's partial
 * unique index - the service uses it to refuse a second open application with an HTTP {@code 409}
 * before the index can fail opaquely.</p>
 */
public interface RentalApplicationRepository extends JpaRepository<RentalApplication, UUID> {

  Optional<RentalApplication> findByIdAndDeletedAtIsNull(UUID id);

  Optional<RentalApplication> findByApplicationCodeIgnoreCaseAndDeletedAtIsNull(String applicationCode);

  boolean existsByApplicationCodeIgnoreCaseAndDeletedAtIsNull(String applicationCode);

  Page<RentalApplication> findAllByDeletedAtIsNull(Pageable pageable);

  List<RentalApplication> findAllByDeletedAtIsNull();

  long countByDeletedAtIsNull();

  /**
   * The applications on a unit in the given statuses. Passing the open statuses ({@code SUBMITTED},
   * {@code SCREENING}) answers "who is in the running for this unit", which is the check the service
   * makes before accepting a new application.
   */
  List<RentalApplication> findAllByUnitIdAndStatusInAndDeletedAtIsNull(
      UUID unitId, Collection<ApplicationStatus> statuses);

  List<RentalApplication> findAllByStatusAndDeletedAtIsNull(ApplicationStatus status);

  Page<RentalApplication> findAllByStatusAndDeletedAtIsNull(ApplicationStatus status, Pageable pageable);

  long countByStatusAndDeletedAtIsNull(ApplicationStatus status);

  /**
   * The register, with the associations a row reads eager-fetched so it does not incur a query per
   * row: the property, the unit and the lead.
   */
  @Query(
      "select a from RentalApplication a "
          + "left join fetch a.property "
          + "left join fetch a.unit "
          + "left join fetch a.lead "
          + "where a.deletedAt is null")
  List<RentalApplication> findAllForRegister();
}
