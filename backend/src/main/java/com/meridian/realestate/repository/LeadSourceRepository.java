package com.meridian.realestate.repository;

import com.meridian.realestate.domain.LeadSource;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Lookup access to the origins an enquiry can come from. Reference data: read-only in the app. */
public interface LeadSourceRepository extends JpaRepository<LeadSource, UUID> {

  Optional<LeadSource> findByCode(String code);

  Optional<LeadSource> findByCodeIgnoreCase(String code);

  /** The sources in the order the picker offers them. */
  List<LeadSource> findAllByOrderByDisplayOrderAsc();

  /** Only the live sources, in offer order - what a new lead may be attributed to. */
  List<LeadSource> findAllByActiveTrueOrderByDisplayOrderAsc();
}
