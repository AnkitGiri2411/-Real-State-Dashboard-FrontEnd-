package com.meridian.realestate.repository;

import com.meridian.realestate.domain.Building;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * The blocks within the properties.
 *
 * <p>No method here carries a literal {@code order by}: paging and sorting arrive as a
 * {@link Pageable} that {@code PageRequestFactory} has already validated against a whitelist.</p>
 *
 * <p>Every read filters on {@code deleted_at is null}. A building is archived rather than removed,
 * because the units that sit in it must stay coherent.</p>
 */
public interface BuildingRepository extends JpaRepository<Building, UUID> {

  Optional<Building> findByIdAndDeletedAtIsNull(UUID id);

  Optional<Building> findByPropertyIdAndBuildingCodeIgnoreCaseAndDeletedAtIsNull(
      UUID propertyId, String buildingCode);

  boolean existsByPropertyIdAndBuildingCodeIgnoreCaseAndDeletedAtIsNull(
      UUID propertyId, String buildingCode);

  Page<Building> findAllByDeletedAtIsNull(Pageable pageable);

  List<Building> findAllByDeletedAtIsNull();

  List<Building> findAllByPropertyIdAndDeletedAtIsNull(UUID propertyId);

  /** The blocks of one property in the order the unit board presents them. */
  List<Building> findAllByPropertyIdAndDeletedAtIsNullOrderByNameAsc(UUID propertyId);

  long countByDeletedAtIsNull();

  long countByPropertyIdAndDeletedAtIsNull(UUID propertyId);
}
