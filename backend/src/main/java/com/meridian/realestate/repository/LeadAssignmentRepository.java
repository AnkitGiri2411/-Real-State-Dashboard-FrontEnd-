package com.meridian.realestate.repository;

import com.meridian.realestate.domain.LeadAssignment;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * The history of who held a lead.
 *
 * <p>The schema allows at most one open assignment per lead, so {@link
 * #findByLeadIdAndUnassignedAtIsNullAndDeletedAtIsNull} returns at most one row - the current
 * holder.</p>
 */
public interface LeadAssignmentRepository extends JpaRepository<LeadAssignment, UUID> {

  /** The assignment history for one lead, newest first. */
  List<LeadAssignment> findAllByLeadIdAndDeletedAtIsNullOrderByAssignedAtDesc(UUID leadId);

  /** The open assignment for a lead - who holds it now. */
  Optional<LeadAssignment> findByLeadIdAndUnassignedAtIsNullAndDeletedAtIsNull(UUID leadId);

  /** The leads a given agent currently holds, by open assignment. */
  List<LeadAssignment> findAllByAgentIdAndUnassignedAtIsNullAndDeletedAtIsNull(UUID agentId);
}
