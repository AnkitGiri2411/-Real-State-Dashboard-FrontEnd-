package com.meridian.realestate.repository;

import com.meridian.realestate.domain.AgentPropertyAssignment;
import com.meridian.realestate.domain.AssignmentRole;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Who carries which property.
 *
 * <p>Assignments are dated rather than deleted: an assignment that has ended carries an
 * {@code assigned_to} date and stays readable, so "who was responsible for this property last
 * quarter" is answerable. "Current" therefore means {@code assigned_to is null} and
 * {@code deleted_at is null} - a closed assignment is history, not a current one.</p>
 *
 * <p>The schema allows at most one <em>open</em> {@code PRIMARY} assignment per property, enforced
 * by the partial unique index {@code uq_agent_property_primary_open} over
 * {@code (property_id) WHERE role = 'PRIMARY' AND assigned_to IS NULL AND deleted_at IS NULL}. A
 * second open primary row would make "the property's agent" ambiguous, and every report that
 * attributes a property to a person would have to guess between them. That is why
 * {@link #findOpenPrimaryForProperty(UUID, AssignmentRole)} can return a single {@link Optional}
 * while the current-assignment reads return lists.</p>
 *
 * <p>No method here carries a literal {@code order by}: paging and sorting arrive as a
 * {@link Pageable} that {@code PageRequestFactory} has already validated against a whitelist.</p>
 */
public interface AgentPropertyAssignmentRepository
    extends JpaRepository<AgentPropertyAssignment, UUID> {

  Optional<AgentPropertyAssignment> findByIdAndDeletedAtIsNull(UUID id);

  Page<AgentPropertyAssignment> findAllByDeletedAtIsNull(Pageable pageable);

  List<AgentPropertyAssignment> findAllByDeletedAtIsNull();

  long countByDeletedAtIsNull();

  long countByAgentIdAndDeletedAtIsNull(UUID agentId);

  long countByAgentIdAndAssignedToIsNullAndDeletedAtIsNull(UUID agentId);

  long countByPropertyIdAndAssignedToIsNullAndDeletedAtIsNull(UUID propertyId);

  /**
   * The properties an agent carries right now, with the property fetched.
   *
   * <p>The dashboard opens on "my portfolio", so this is the query the agent's first screen runs;
   * fetching the property avoids a query per row. Primary holdings are listed first, and a fixed
   * order is safe here because the query is not paged.</p>
   */
  @Query(
      "select a from AgentPropertyAssignment a "
          + "join fetch a.property "
          + "where a.deletedAt is null and a.agent.id = :agentId and a.assignedTo is null "
          + "order by a.role asc, a.assignedFrom desc, a.id asc")
  List<AgentPropertyAssignment> findCurrentForAgent(@Param("agentId") UUID agentId);

  /**
   * Who carries a property right now, with the agent and their office fetched.
   *
   * <p>The property page shows the responsible person and the branch they sit in, so both come back
   * in one statement rather than one per row.</p>
   */
  @Query(
      "select a from AgentPropertyAssignment a "
          + "join fetch a.agent "
          + "left join fetch a.agent.office "
          + "where a.deletedAt is null and a.property.id = :propertyId and a.assignedTo is null "
          + "order by a.role asc, a.assignedFrom desc, a.id asc")
  List<AgentPropertyAssignment> findCurrentForProperty(@Param("propertyId") UUID propertyId);

  /**
   * The single agent answerable for a property at the moment.
   *
   * <p>Single because the partial unique index allows one open primary row per property; the
   * {@link Optional} reflects a constraint the database actually enforces rather than an optimism
   * about the data.</p>
   */
  @Query(
      "select a from AgentPropertyAssignment a "
          + "join fetch a.agent "
          + "left join fetch a.agent.office "
          + "where a.deletedAt is null and a.property.id = :propertyId "
          + "  and a.role = :role and a.assignedTo is null")
  Optional<AgentPropertyAssignment> findOpenPrimaryForProperty(
      @Param("propertyId") UUID propertyId, @Param("role") AssignmentRole role);

  /**
   * The assignments in force on a property on a given date, with the agent fetched.
   *
   * <p>A past question - "who was looking after this when the viewing happened" - answered from the
   * dates rather than from a snapshot, because the assignment rows are never overwritten.</p>
   */
  @Query(
      "select a from AgentPropertyAssignment a "
          + "join fetch a.agent "
          + "where a.deletedAt is null and a.property.id = :propertyId "
          + "  and a.assignedFrom <= :asOf "
          + "  and (a.assignedTo is null or a.assignedTo >= :asOf) "
          + "order by a.role asc, a.assignedFrom desc, a.id asc")
  List<AgentPropertyAssignment> findEffectiveForPropertyOn(
      @Param("propertyId") UUID propertyId, @Param("asOf") LocalDate asOf);

  /**
   * Everything an agent has ever carried, open and closed, with the property fetched.
   *
   * <p>The performance report reads this: what an agent was responsible for over a period, not only
   * what they hold today.</p>
   */
  @Query(
      "select a from AgentPropertyAssignment a "
          + "join fetch a.property "
          + "where a.deletedAt is null and a.agent.id = :agentId "
          + "order by a.assignedFrom desc, a.id asc")
  List<AgentPropertyAssignment> findAllForAgent(@Param("agentId") UUID agentId);

  boolean existsByPropertyIdAndRoleAndAssignedToIsNullAndDeletedAtIsNull(
      UUID propertyId, AssignmentRole role);
}
