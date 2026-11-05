package com.meridian.realestate.repository;

import com.meridian.realestate.domain.LeadActivity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/** The work recorded against a lead - its timeline. */
public interface LeadActivityRepository extends JpaRepository<LeadActivity, UUID> {

  /** The full timeline for one lead, newest first. */
  List<LeadActivity> findAllByLeadIdAndDeletedAtIsNullOrderByOccurredAtDesc(UUID leadId);

  /** The same timeline, paged; the caller supplies the validated sort. */
  Page<LeadActivity> findAllByLeadIdAndDeletedAtIsNull(UUID leadId, Pageable pageable);
}
