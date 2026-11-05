package com.meridian.realestate.repository;

import com.meridian.realestate.domain.ImportEntityType;
import com.meridian.realestate.domain.ImportJob;
import com.meridian.realestate.domain.ImportJobStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * The staging ledger for data import.
 *
 * <p>{@link #findByIdempotencyKey(String)} is the important one. The key is unique in the schema,
 * and that uniqueness is what makes a re-submitted import return the original job instead of
 * importing twice: a retry after a dropped connection, or a second press of a button, presents the
 * same key and is answered with what already exists. It deliberately does <em>not</em> filter on
 * {@code deletedAt}, because the constraint does not either - a soft-deleted job still holds its
 * key, and pretending otherwise would let the same request be imported again.</p>
 *
 * <p>Import jobs are administrative records rather than business rows, so they are archived rather
 * than deleted and the ordinary readers filter on {@code deletedAt is null}. No paged finder here
 * carries a literal order; the recent-jobs reader does, because "most recent first" is what it
 * means.</p>
 */
public interface ImportJobRepository extends JpaRepository<ImportJob, UUID> {

  Optional<ImportJob> findByIdAndDeletedAtIsNull(UUID id);

  Optional<ImportJob> findByJobCodeIgnoreCaseAndDeletedAtIsNull(String jobCode);

  boolean existsByJobCodeIgnoreCaseAndDeletedAtIsNull(String jobCode);

  /**
   * The job a re-submitted import belongs to.
   *
   * <p>Unique by constraint, so this is a single lookup rather than a search.</p>
   */
  Optional<ImportJob> findByIdempotencyKey(String idempotencyKey);

  boolean existsByIdempotencyKey(String idempotencyKey);

  /**
   * Jobs staged from a file with this content.
   *
   * <p>A list rather than a single value: the hash lets the interface warn that the same file has
   * been imported before, and "before" may be more than once. The file itself is never kept, so this
   * digest is the only way to recognise it.</p>
   */
  List<ImportJob> findAllByContentHashAndDeletedAtIsNull(String contentHash);

  Page<ImportJob> findAllByDeletedAtIsNull(Pageable pageable);

  List<ImportJob> findAllByDeletedAtIsNull();

  long countByDeletedAtIsNull();

  /** The import history screen: the most recent stagings, newest first. */
  List<ImportJob> findTop20ByDeletedAtIsNullOrderByCreatedAtDesc();

  /** One state's count, which is how the staged / committed / failed tiles are read. */
  long countByStatusAndDeletedAtIsNull(ImportJobStatus status);

  /**
   * The same counts in one round trip.
   *
   * <p>Each row is {@code [ImportJobStatus, Long]}.</p>
   */
  @Query(
      "select j.status, count(j) from ImportJob j "
          + "where j.deletedAt is null group by j.status order by j.status")
  List<Object[]> countGroupedByStatus();

  /** The jobs that deliberately re-ran an earlier one, for the replay trail. */
  List<ImportJob> findAllByReplayOfIdAndDeletedAtIsNull(UUID replayOfJobId);

  /** The jobs that staged one entity type, for the import history's entity filter. */
  List<ImportJob> findAllByEntityTypeAndDeletedAtIsNull(ImportEntityType entityType);
}
