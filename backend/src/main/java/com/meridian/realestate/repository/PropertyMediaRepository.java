package com.meridian.realestate.repository;

import com.meridian.realestate.domain.PropertyMedia;
import com.meridian.realestate.domain.PropertyMediaType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Metadata for the drawings, brochures and photographs a property carries.
 *
 * <p>No binary is stored here - this product has no blob-storage service - so every finder returns
 * a description of an asset that lives elsewhere.</p>
 *
 * <p>Reads filter on {@code deleted_at is null}: a media row is detached rather than removed.</p>
 */
public interface PropertyMediaRepository extends JpaRepository<PropertyMedia, UUID> {

  Optional<PropertyMedia> findByIdAndDeletedAtIsNull(UUID id);

  Page<PropertyMedia> findAllByDeletedAtIsNull(Pageable pageable);

  List<PropertyMedia> findAllByDeletedAtIsNull();

  /** Everything attached to one property, in the order the gallery presents it. */
  List<PropertyMedia> findAllByPropertyIdAndDeletedAtIsNullOrderByDisplayOrderAsc(UUID propertyId);

  List<PropertyMedia> findAllByPropertyIdAndMediaTypeAndDeletedAtIsNullOrderByDisplayOrderAsc(
      UUID propertyId, PropertyMediaType mediaType);

  /** The single asset that represents the property in a list. */
  List<PropertyMedia> findAllByPropertyIdAndIsPrimaryTrueAndDeletedAtIsNull(UUID propertyId);

  long countByPropertyIdAndDeletedAtIsNull(UUID propertyId);

  long countByDeletedAtIsNull();
}
