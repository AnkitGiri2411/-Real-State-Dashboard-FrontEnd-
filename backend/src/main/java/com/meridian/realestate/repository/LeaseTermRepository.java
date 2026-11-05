package com.meridian.realestate.repository;

import com.meridian.realestate.domain.LeaseTerm;
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
 * The rent schedule.
 *
 * <p>This is the repository that answers "what was the rent on date X" without trusting the lease's
 * headline figure - which is the whole reason {@code lease_terms} exists.</p>
 */
public interface LeaseTermRepository extends JpaRepository<LeaseTerm, UUID> {

  Optional<LeaseTerm> findByIdAndDeletedAtIsNull(UUID id);

  Page<LeaseTerm> findAllByDeletedAtIsNull(Pageable pageable);

  /**
   * The open-ended term of a lease - the rent currently in force.
   *
   * <p>{@code uq_lease_terms_open} allows at most one {@code effective_to IS NULL} term per lease,
   * so this is single-valued.</p>
   */
  Optional<LeaseTerm> findByLease_IdAndEffectiveToIsNullAndDeletedAtIsNull(UUID leaseId);

  /** Every term of a lease, in schedule order. Ordered here because a schedule is a sequence. */
  List<LeaseTerm> findAllByLease_IdAndDeletedAtIsNullOrderByTermNumberAsc(UUID leaseId);

  /**
   * The term in force on a given date - "the rent in force on 1 March".
   *
   * <p>{@code uq_lease_terms_effective} (one term per {@code (lease_id, effective_from)}) and
   * {@code uq_lease_terms_open} together make this single-valued, so the answer is a rent rather
   * than a set.</p>
   */
  @Query(
      "select t from LeaseTerm t "
          + "where t.deletedAt is null and t.lease.id = :leaseId "
          + "and t.effectiveFrom <= :onDate "
          + "and (t.effectiveTo is null or t.effectiveTo >= :onDate)")
  Optional<LeaseTerm> findTermInForce(
      @Param("leaseId") UUID leaseId, @Param("onDate") LocalDate onDate);
}
