package com.meridian.realestate.repository;

import com.meridian.realestate.domain.PropertyType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Lookup access to the property categories. Reference data: read-only in the application. */
public interface PropertyTypeRepository extends JpaRepository<PropertyType, UUID> {

  Optional<PropertyType> findByCode(String code);

  /** The categories in the order the register offers them. */
  List<PropertyType> findAllByOrderByDisplayOrderAsc();
}
