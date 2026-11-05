package com.meridian.realestate.dto;

import com.meridian.realestate.domain.AgentProfile;
import com.meridian.realestate.domain.AgentPropertyAssignment;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * The agent register's API contract: profiles, their assignments to properties, and the two reports
 * that read their book of work.
 *
 * <p><b>Why these records exist.</b> An agent is a staff account that carries a portfolio. The
 * register row must therefore show a person's name and their branch rather than two raw foreign
 * keys, so {@link AgentSummary} flattens the account and the office to the values a screen prints.
 * An assignment is dated rather than deleted, so {@link AssignmentRecord} carries both ends of its
 * period and an {@code open} flag - the history is the point.</p>
 *
 * <p><b>The rules these records protect.</b> Two partial unique indexes on the schema make a wrong
 * shape unrepresentable, and these records exist to let the service refuse the shape clearly first.</p>
 * <ul>
 *   <li>One profile per staff account ({@code agent_profiles.user_id} UNIQUE), refused as
 *       {@code AGENT_USER_ALREADY_LINKED}.</li>
 *   <li>One open {@code PRIMARY} assignment per property ({@code uq_agent_property_primary_open}),
 *       refused as {@code AGENT_PRIMARY_ALREADY_SET}.</li>
 * </ul>
 *
 * <p><b>Every reported figure is guarded.</b> {@link PerformanceResponse#conversionRatePercent()} is
 * zero - never {@code NaN} - when an agent holds no leads, and the raw counts travel beside it so
 * "none converted" can be told apart from "none to convert".</p>
 *
 * <p>Money is {@link BigDecimal} only: no monthly target here is a {@code double}.</p>
 */
public final class AgentDtos {

  private AgentDtos() {}

  /** The closed set of specialisations the schema's {@code ck_agent_profiles_specialisation} allows. */
  private static final String SPECIALISATION_SET =
      "LETTING|SALES|BOTH|PROPERTY_MANAGEMENT|VALUATION";

  /** The closed set of statuses the schema's {@code ck_agent_profiles_status} allows. */
  private static final String AGENT_STATUS_SET = "ACTIVE|INACTIVE";

  /** The closed set of roles the schema's {@code ck_agent_property_assignments_role} allows. */
  private static final String ROLE_SET = "PRIMARY|SECONDARY|CO_AGENT";

  // ---------------------------------------------------------------------------
  // Reads
  // ---------------------------------------------------------------------------

  /**
   * One row of the agent register.
   *
   * <p>The name is the staff account's full name and the office is its branch, resolved here so the
   * register reads as a list of people rather than of identifiers.</p>
   */
  public record AgentSummary(
      UUID id,
      String agentCode,
      UUID userId,
      String fullName,
      String email,
      String phone,
      UUID officeId,
      String officeName,
      UUID managerId,
      String managerName,
      String specialisation,
      String licenceNumber,
      LocalDate joinedOn,
      BigDecimal monthlyTarget,
      String status,
      LocalDateTime createdAt,
      LocalDateTime updatedAt) {

    public static AgentSummary from(AgentProfile agent) {
      var user = agent.getUser();
      var office = agent.getOffice();
      var manager = agent.getManager();
      return new AgentSummary(
          agent.getId(),
          agent.getAgentCode(),
          user == null ? null : user.getId(),
          user == null ? null : user.getFullName(),
          user == null ? null : user.getEmail(),
          user == null ? null : user.getPhone(),
          office == null ? null : office.getId(),
          office == null ? null : office.getName(),
          manager == null ? null : manager.getId(),
          manager == null ? null : managerFullName(manager),
          name(agent.getSpecialisation()),
          agent.getLicenceNumber(),
          agent.getJoinedOn(),
          agent.getMonthlyTarget(),
          name(agent.getStatus()),
          agent.getCreatedAt(),
          agent.getUpdatedAt());
    }
  }

  /** One agent's full record, with every assignment they hold, open and closed. */
  public record AgentDetail(
      UUID id,
      String agentCode,
      UUID userId,
      String fullName,
      String email,
      String phone,
      UUID officeId,
      String officeName,
      UUID managerId,
      String managerName,
      String specialisation,
      String licenceNumber,
      LocalDate joinedOn,
      BigDecimal monthlyTarget,
      String bio,
      String status,
      long assignmentCount,
      long openAssignmentCount,
      List<AssignmentRecord> assignments,
      LocalDateTime createdAt,
      LocalDateTime updatedAt) {}

  /** Who carries which property right now, and in what capacity. */
  public record AssignmentRecord(
      UUID id,
      UUID agentId,
      String agentName,
      UUID propertyId,
      String propertyCode,
      String propertyName,
      String role,
      LocalDate assignedFrom,
      LocalDate assignedTo,
      boolean open) {

    public static AssignmentRecord from(AgentPropertyAssignment assignment) {
      var agent = assignment.getAgent();
      var property = assignment.getProperty();
      return new AssignmentRecord(
          assignment.getId(),
          agent == null ? null : agent.getId(),
          agent == null ? null : agentFullName(agent),
          property == null ? null : property.getId(),
          property == null ? null : property.getPropertyCode(),
          property == null ? null : property.getName(),
          name(assignment.getRole()),
          assignment.getAssignedFrom(),
          assignment.getAssignedTo(),
          assignment.isOpen());
    }
  }

  /**
   * An agent's current workload.
   *
   * <p>Four figures, each read from its own module over the agent's own work: the open leads they
   * hold, the listings they have live, the viewings scheduled for them in the next seven days, and
   * the maintenance on their book that is still open. Every one is a count of real rows.</p>
   */
  public record WorkloadResponse(
      UUID agentId, String agentCode, long openLeads, long activeListings, long viewingsNext7Days,
      long openMaintenance) {}

  /**
   * How an agent's book has performed.
   *
   * <p>The conversion rate is a percentage over the leads the agent has held, and it is guarded:
   * an agent with no leads reads {@code 0}, never {@code NaN}. The raw counts are returned beside
   * the rate so the two can be told apart.</p>
   */
  public record PerformanceResponse(
      UUID agentId,
      String agentCode,
      long totalLeads,
      long leadsConverted,
      long viewingsCompleted,
      int conversionRatePercent,
      long listingsLet) {}

  // ---------------------------------------------------------------------------
  // Writes
  // ---------------------------------------------------------------------------

  /**
   * Create an agent profile for a staff account.
   *
   * <p>The account is named by id and the profile is refused if that account already has one, which
   * is the schema's {@code user_id} uniqueness stated as a named {@code 409}. The agent code is
   * supplied by the caller, because there is no seeded series for it.</p>
   */
  public record CreateAgentRequest(
      @NotNull UUID userId,
      @NotBlank @Size(max = 30) String agentCode,
      UUID officeId,
      UUID managerId,
      @Pattern(regexp = SPECIALISATION_SET, message = "must be an agent specialisation")
          String specialisation,
      @Size(max = 60) String licenceNumber,
      LocalDate joinedOn,
      @DecimalMin(value = "0", message = "cannot be negative") BigDecimal monthlyTarget,
      @Size(max = 1000) String bio,
      @Pattern(regexp = AGENT_STATUS_SET, message = "must be an agent status") String status) {}

  /** Change an agent profile. Every field is optional; only the ones supplied are applied. */
  public record UpdateAgentRequest(
      UUID officeId,
      UUID managerId,
      @Pattern(regexp = SPECIALISATION_SET, message = "must be an agent specialisation")
          String specialisation,
      @Size(max = 60) String licenceNumber,
      LocalDate joinedOn,
      @DecimalMin(value = "0", message = "cannot be negative") BigDecimal monthlyTarget,
      @Size(max = 1000) String bio,
      @Pattern(regexp = AGENT_STATUS_SET, message = "must be an agent status") String status) {}

  /**
   * Assign an agent to a property.
   *
   * <p>{@code role} defaults to {@code PRIMARY} in the schema; the service refuses a second open
   * primary assignment on the same property, because "the property's agent" has to have one
   * answer.</p>
   */
  public record CreateAssignmentRequest(
      @NotNull UUID propertyId,
      @Pattern(regexp = ROLE_SET, message = "must be an assignment role") String role,
      LocalDate assignedFrom) {}

  private static String name(Enum<?> value) {
    return value == null ? null : value.name();
  }

  private static String agentFullName(AgentProfile agent) {
    var user = agent.getUser();
    return user == null ? null : user.getFullName();
  }

  private static String managerFullName(AgentProfile manager) {
    return manager == null ? null : agentFullName(manager);
  }
}
