package com.meridian.realestate.repository;

import com.meridian.realestate.domain.ImportJobRow;
import com.meridian.realestate.domain.ImportRowStatus;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * The staged rows of an import.
 *
 * <p>A row is read in file order, so the readers order by {@code row_number} ascending: the preview
 * has to line up with what the spreadsheet showed, and a preview that reordered the rows would be
 * describing a different file from the one that was uploaded. The paged reader leaves the sort to
 * the {@link Pageable}.</p>
 *
 * <p>Rows are self-contained - they reference only their job - so there is nothing here to fetch
 * join, and no fetches are written.</p>
 */
public interface ImportJobRowRepository extends JpaRepository<ImportJobRow, UUID> {

  /** The whole staged file, in source order. */
  List<ImportJobRow> findAllByJobIdOrderByRowNumberAsc(UUID jobId);

  /** One page of it; the sort is the caller's. */
  Page<ImportJobRow> findAllByJobId(UUID jobId, Pageable pageable);

  /** How many rows one job staged. */
  long countByJobId(UUID jobId);

  /** The number next to a status filter in the preview - how many rows are valid, invalid, written. */
  long countByJobIdAndStatus(UUID jobId, ImportRowStatus status);

  /**
   * The rows in the given states, in source order.
   *
   * <p>This is the preview's "show me only the problems" reader, so it takes the states it wants
   * rather than hard-coding invalid rows: a caller can look at what is invalid, what was skipped,
   * or what failed without three separate finders.</p>
   */
  @Query(
      "select r from ImportJobRow r "
          + "where r.job.id = :jobId and r.status in (:statuses) "
          + "order by r.rowNumber asc")
  List<ImportJobRow> findRowsWithStatus(
      @Param("jobId") UUID jobId, @Param("statuses") Collection<ImportRowStatus> statuses);

  /**
   * The row-count breakdown for one job in a single round trip.
   *
   * <p>Each row is {@code [ImportRowStatus, Long]}.</p>
   */
  @Query(
      "select r.status, count(r) from ImportJobRow r "
          + "where r.job.id = :jobId group by r.status order by r.status")
  List<Object[]> countGroupedByStatus(@Param("jobId") UUID jobId);
}
