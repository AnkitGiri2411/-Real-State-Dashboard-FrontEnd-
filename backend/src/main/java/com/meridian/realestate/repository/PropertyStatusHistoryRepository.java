package com.meridian.realestate.repository;

import com.meridian.realestate.domain.PropertyStatusHistory;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * The status trail behind each property.
 *
 * <p>History is append-only: a transition is a fact that happened at a moment, so nothing here
 * filters or marks a row as removed and there is no soft-delete column to filter on.</p>
 */
public interface PropertyStatusHistoryRepository
    extends JpaRepository<PropertyStatusHistory, UUID> {

  /** A property's transitions, most recent first - the order the register presents them. */
  List<PropertyStatusHistory> findAllByPropertyIdOrderByChangedAtDesc(UUID propertyId);

  /** The most recent transition, for the "last changed" line on the property header. */
  Optional<PropertyStatusHistory> findFirstByPropertyIdOrderByChangedAtDesc(UUID propertyId);

  Page<PropertyStatusHistory> findAllByPropertyId(UUID propertyId, Pageable pageable);

  long countByPropertyId(UUID propertyId);
}
