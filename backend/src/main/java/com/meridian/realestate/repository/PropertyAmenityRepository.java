package com.meridian.realestate.repository;

import com.meridian.realestate.domain.PropertyAmenity;
import com.meridian.realestate.domain.PropertyAmenity.PropertyAmenityId;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * The facilities each property offers.
 *
 * <p>The identity is the {@code (property_id, amenity_id)} pair, so the repository is keyed on
 * {@link PropertyAmenityId} rather than a surrogate id. Links are hard-deleted when a facility is
 * removed from a property - the facility is either offered or it is not - so there is no
 * soft-delete filter here.</p>
 */
public interface PropertyAmenityRepository extends JpaRepository<PropertyAmenity, PropertyAmenityId> {

  /** The facilities one property offers, as linked with their per-property notes. */
  List<PropertyAmenity> findAllByProperty_Id(UUID propertyId);

  /** Which properties offer a given facility - the reverse of the property page. */
  List<PropertyAmenity> findAllByAmenity_Id(UUID amenityId);

  boolean existsByProperty_IdAndAmenity_Id(UUID propertyId, UUID amenityId);

  long countByProperty_Id(UUID propertyId);

  long countByAmenity_Id(UUID amenityId);
}
