package com.meridian.realestate.repository;

import com.meridian.realestate.domain.Amenity;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Lookup access to the facilities a property can offer.
 *
 * <p>Reference data: read-only in the application. It has no soft delete, so no finder filters on
 * {@code deleted_at} - every seeded amenity is live.</p>
 */
public interface AmenityRepository extends JpaRepository<Amenity, UUID> {

  Optional<Amenity> findByCode(String code);

  Optional<Amenity> findByCodeIgnoreCase(String code);

  /** The facilities in the order the property page offers them. */
  List<Amenity> findAllByOrderByDisplayOrderAsc();

  /** One group of the property page - utility, access, security, parking, logistics, lifestyle. */
  List<Amenity> findAllByCategoryOrderByDisplayOrderAsc(String category);

  List<Amenity> findByCodeIn(Collection<String> codes);
}
