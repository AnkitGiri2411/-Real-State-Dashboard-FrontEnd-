package com.meridian.realestate.repository;

import com.meridian.realestate.domain.BusinessNumberSequence;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * The human-readable number series.
 *
 * <p>This repository exists mostly for one method. Minting a document number means reading the
 * counter, incrementing it and writing it back, and two people working the same portfolio at the
 * same moment must not be handed the same number - so the read has to take a row lock rather than
 * trust the default isolation the caller happens to be running at.</p>
 *
 * <p>The series are seeded by the migration chain and re-anchored by later migrations, so nothing
 * here creates or deletes rows except the {@code findByCodeForUpdate} path's caller when a series a
 * later feature needs is genuinely absent; the unique constraint on {@code code} is what makes that
 * safe, and a plain {@code existsByCode} is offered so the common case can avoid it.</p>
 */
public interface BusinessNumberSequenceRepository extends JpaRepository<BusinessNumberSequence, UUID> {

  /** The current value of a series, read without taking a lock - for display, never for minting. */
  Optional<BusinessNumberSequence> findByCode(String code);

  boolean existsByCode(String code);

  /**
   * The series row, locked for update, so a number can be minted without collision.
   *
   * <p>The pessimistic write lock is held until the caller's transaction commits. That is what makes
   * the mint atomic: a second caller waits, then reads the value the first one wrote, and is handed
   * the following number. It is deliberately a row lock on one series rather than a lock over the
   * table, because two people minting a lease and an invoice are not in contention at all.</p>
   *
   * <p>The lock cannot be taken on a row that does not exist, so a caller for a series that has not
   * been seeded has to insert it and rely on the unique constraint on {@code code} to resolve a
   * simultaneous first use.</p>
   */
  @Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
  @Query("select s from BusinessNumberSequence s where s.code = :code")
  java.util.Optional<BusinessNumberSequence> findByCodeForUpdate(@Param("code") String code);
}
