package com.meridian.realestate.repository;

import com.meridian.realestate.domain.UnitStatus;
import com.meridian.realestate.domain.UnitStatusHistory;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * The status trail behind each unit.
 *
 * <p>History is append-only: a transition is a fact that happened at a moment, so nothing here
 * filters or marks a row as removed and there is no soft-delete column to filter on.</p>
 */
public interface UnitStatusHistoryRepository extends JpaRepository<UnitStatusHistory, UUID> {

  /** A unit's transitions, most recent first - the letting history behind the board. */
  List<UnitStatusHistory> findAllByUnitIdOrderByChangedAtDesc(UUID unitId);

  /** The most recent transition, for the "last changed" line on the unit card. */
  Optional<UnitStatusHistory> findFirstByUnitIdOrderByChangedAtDesc(UUID unitId);

  Page<UnitStatusHistory> findAllByUnitId(UUID unitId, Pageable pageable);

  long countByUnitId(UUID unitId);

  /** How many units have ever reached a given state - the shape of the letting pipeline. */
  long countByToStatus(UnitStatus toStatus);
}
