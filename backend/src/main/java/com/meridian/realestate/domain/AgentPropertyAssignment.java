package com.meridian.realestate.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Who carries which property, and in what capacity.
 *
 * <p>Dated rather than deleted, so "who was responsible for this property last quarter" is
 * answerable - which is exactly what an agent-performance report has to ask. A closed assignment
 * carries an {@code assignedTo} date; the history is the point, so it is never removed.</p>
 *
 * <p>The schema allows at most one <em>open</em> {@code PRIMARY} row per property, enforced by the
 * partial unique index {@code uq_agent_property_primary_open} over
 * {@code (property_id) WHERE role = 'PRIMARY' AND assigned_to IS NULL AND deleted_at IS NULL}. A
 * second open primary assignment would make "the property's agent" ambiguous and force every report
 * that attributes a property to a person to guess between them. {@code SECONDARY} and
 * {@code CO_AGENT} rows are unlimited, because sharing an instruction is a real and common
 * arrangement.</p>
 */
@Entity
@Table(name = "agent_property_assignments")
public class AgentPropertyAssignment extends Auditable {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "agent_id", nullable = false)
  private AgentProfile agent;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "property_id", nullable = false)
  private Property property;

  @Enumerated(EnumType.STRING)
  @Column(name = "role", nullable = false, length = 20)
  private AssignmentRole role = AssignmentRole.PRIMARY;

  @Column(name = "assigned_from", nullable = false)
  private LocalDate assignedFrom;

  /** Null means the agent still carries the property. Closed assignments are dated, never deleted. */
  @Column(name = "assigned_to")
  private LocalDate assignedTo;

  /** True while this assignment is still in force, which is what the dashboard reads. */
  public boolean isOpen() {
    return assignedTo == null;
  }

  public UUID getId() {
    return id;
  }

  public void setId(UUID id) {
    this.id = id;
  }

  public AgentProfile getAgent() {
    return agent;
  }

  public void setAgent(AgentProfile agent) {
    this.agent = agent;
  }

  public Property getProperty() {
    return property;
  }

  public void setProperty(Property property) {
    this.property = property;
  }

  public AssignmentRole getRole() {
    return role;
  }

  public void setRole(AssignmentRole role) {
    this.role = role;
  }

  public LocalDate getAssignedFrom() {
    return assignedFrom;
  }

  public void setAssignedFrom(LocalDate assignedFrom) {
    this.assignedFrom = assignedFrom;
  }

  public LocalDate getAssignedTo() {
    return assignedTo;
  }

  public void setAssignedTo(LocalDate assignedTo) {
    this.assignedTo = assignedTo;
  }
}
