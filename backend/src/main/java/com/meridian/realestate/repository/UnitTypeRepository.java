package com.meridian.realestate.repository;

import com.meridian.realestate.domain.UnitType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Lookup access to the unit categories. Reference data: read-only in the application. */
public interface UnitTypeRepository extends JpaRepository<UnitType, UUID> {

  Optional<UnitType> findByCode(String code);

  /** The categories in the order the unit board offers them. */
  List<UnitType> findAllByOrderByDisplayOrderAsc();
}
