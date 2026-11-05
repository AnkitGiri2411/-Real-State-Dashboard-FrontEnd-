package com.meridian.realestate.service;

import com.meridian.realestate.domain.AgentProfile;
import com.meridian.realestate.domain.AgentPropertyAssignment;
import com.meridian.realestate.domain.AgentSpecialisation;
import com.meridian.realestate.domain.AgentStatus;
import com.meridian.realestate.domain.AssignmentRole;
import com.meridian.realestate.domain.Lead;
import com.meridian.realestate.domain.LeadStage;
import com.meridian.realestate.domain.Listing;
import com.meridian.realestate.domain.ListingStatus;
import com.meridian.realestate.domain.MaintenanceRequest;
import com.meridian.realestate.domain.Office;
import com.meridian.realestate.domain.Property;
import com.meridian.realestate.domain.User;
import com.meridian.realestate.domain.Viewing;
import com.meridian.realestate.domain.ViewingStatus;
import com.meridian.realestate.dto.AgentDtos;
import com.meridian.realestate.dto.ErrorMessages;
import com.meridian.realestate.exception.ApiException;
import com.meridian.realestate.repository.AgentProfileRepository;
import com.meridian.realestate.repository.AgentPropertyAssignmentRepository;
import com.meridian.realestate.repository.LeadRepository;
import com.meridian.realestate.repository.ListingRepository;
import com.meridian.realestate.repository.MaintenanceRequestRepository;
import com.meridian.realestate.repository.OfficeRepository;
import com.meridian.realestate.repository.PropertyRepository;
import com.meridian.realestate.repository.UserRepository;
import com.meridian.realestate.repository.ViewingRepository;
import com.meridian.realestate.security.AccessScope;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The agent register: the profiles that carry a portfolio, the properties assigned to them, and the
 * two figures - workload and performance - that read their book of work.
 *
 * <p><b>Why this class exists.</b> An agent is a staff account that carries stock, and the profile is
 * a 1:1 extension of the account that says which stock, which branch and which licence. The register,
 * the assignments and the reports all answer one question - "who is responsible for this, and how
 * are they doing" - so they belong together, where the assignments and the figures that read them
 * cannot disagree.</p>
 *
 * <p><b>The rules it protects.</b></p>
 * <ul>
 *   <li><b>One profile per staff account.</b> The schema carries a UNIQUE foreign key from
 *       {@code agent_profiles.user_id} to {@code users}. A second profile for the same account is
 *       refused first, as a named 409 {@code AGENT_USER_ALREADY_LINKED}, rather than left to the
 *       index to report as an opaque integrity error.</li>
 *   <li><b>One open primary agent per property.</b> The partial unique index
 *       {@code uq_agent_property_primary_open} allows a single open {@code PRIMARY} row per
 *       property, because "the property's agent" has to have one answer. A second open primary
 *       assignment is refused as a 409 named {@code AGENT_PRIMARY_ALREADY_SET}.</li>
 *   <li><b>An assignment is dated, never deleted.</b> Closing one stamps {@code assignedTo}, so
 *       "who was responsible for this property last quarter" stays answerable - which is exactly
 *       what the performance report asks.</li>
 *   <li><b>Every reported figure is guarded.</b> The conversion rate divides by the leads the agent
 *       has held, and reads {@code 0} - never {@code NaN} - when there are none. Every count is a
 *       count of real rows read through its own module's repository.</li>
 * </ul>
 *
 * <p><b>On the reports and their reach.</b> There is no repository method that reads listings or
 * maintenance by their agent, and the repository layer is not this task's to change, so those two
 * figures are computed from the register/board queries and filtered here rather than through a
 * dedicated query. See the notes on {@link #workload(UUID)} and {@link #performance(UUID)}.</p>
 *
 * <p><b>A note on paging.</b> As everywhere in this module, the filters, the sort and the slice are
 * applied here because the combined filtered query does not exist and a literal {@code order by} in
 * a paged query is forbidden.</p>
 */
@Service
public class AgentService {

  /** How far ahead the workload report counts viewings. */
  private static final int VIEWING_HORIZON_DAYS = 7;

  private final AgentProfileRepository agentRepository;
  private final OfficeRepository officeRepository;
  private final UserRepository userRepository;
  private final PropertyRepository propertyRepository;
  private final AgentPropertyAssignmentRepository assignmentRepository;
  private final LeadRepository leadRepository;
  private final ListingRepository listingRepository;
  private final ViewingRepository viewingRepository;
  private final MaintenanceRequestRepository maintenanceRepository;
  private final AuditRecordingService audit;
  private final AccessScope accessScope;

  public AgentService(
      AgentProfileRepository agentRepository,
      OfficeRepository officeRepository,
      UserRepository userRepository,
      PropertyRepository propertyRepository,
      AgentPropertyAssignmentRepository assignmentRepository,
      LeadRepository leadRepository,
      ListingRepository listingRepository,
      ViewingRepository viewingRepository,
      MaintenanceRequestRepository maintenanceRepository,
      AuditRecordingService audit,
      AccessScope accessScope) {
    this.agentRepository = agentRepository;
    this.officeRepository = officeRepository;
    this.userRepository = userRepository;
    this.propertyRepository = propertyRepository;
    this.assignmentRepository = assignmentRepository;
    this.leadRepository = leadRepository;
    this.listingRepository = listingRepository;
    this.viewingRepository = viewingRepository;
    this.maintenanceRepository = maintenanceRepository;
    this.audit = audit;
    this.accessScope = accessScope;
  }

  /* ====================================================================== */
  /* Reads                                                                  */
  /* ====================================================================== */

  /** The live agent behind an id, or a 404. Archived profiles are not addressable. */
  @Transactional(readOnly = true)
  public AgentProfile require(UUID agentId) {
    return agentRepository
        .findByIdAndDeletedAtIsNull(agentId)
        .orElseThrow(() -> ApiException.notFound(ErrorMessages.friendly("AGENT_NOT_FOUND")));
  }

  /** The agent register, filtered by office, specialisation and status, with free-text search. */
  @Transactional(readOnly = true)
  public Page<AgentDtos.AgentSummary> list(
      UUID officeId, String specialisation, String status, String search, Pageable pageable) {

    AgentSpecialisation specialisationFilter = parseSpecialisation(specialisation);
    AgentStatus statusFilter = parseStatus(status);
    String term = blankToNull(search);
    String needle = term == null ? null : term.toLowerCase(Locale.ROOT);

    List<AgentProfile> filtered =
        agentRepository.findAllWithUserAndOffice().stream()
            .filter(
                agent ->
                    officeId == null
                        || (agent.getOffice() != null && officeId.equals(agent.getOffice().getId())))
            .filter(
                agent ->
                    specialisationFilter == null
                        || agent.getSpecialisation() == specialisationFilter)
            .filter(agent -> statusFilter == null || agent.getStatus() == statusFilter)
            .filter(agent -> needle == null || matches(agent, needle))
            .toList();

    return pageOf(sortAgents(filtered, pageable.getSort()), pageable, AgentDtos.AgentSummary::from);
  }

  /** One agent's full record, with every assignment they hold, open and closed. */
  @Transactional(readOnly = true)
  public AgentDtos.AgentDetail detail(UUID agentId) {
    return detailOf(require(agentId));
  }

  /**
   * An agent's current workload: the open leads they hold, the listings they have live, the viewings
   * ahead of them in the next seven days, and the open maintenance on their book.
   *
   * <p>The listings and the maintenance are filtered from the register/board queries because there is
   * no repository method that reads either by its agent; the leads and the viewings have a
   * by-agent method and use it. Every figure is a count of real rows.</p>
   */
  @Transactional(readOnly = true)
  public AgentDtos.WorkloadResponse workload(UUID agentId) {
    AgentProfile agent = require(agentId);

    long openLeads =
        leadRepository.findAllByOwnerAgentIdAndDeletedAtIsNull(agentId).stream()
            .filter(lead -> lead.getStage() != null && lead.getStage().isOpen())
            .count();

    long activeListings =
        listingRepository.findAllForRegister().stream()
            .filter(listing -> isListedBy(listing, agentId))
            .filter(listing -> listing.getStatus() != null && listing.getStatus().isLive())
            .count();

    LocalDateTime now = LocalDateTime.now();
    LocalDateTime horizon = now.plusDays(VIEWING_HORIZON_DAYS);
    long viewingsNext7Days =
        viewingRepository.findAllByAgentIdAndDeletedAtIsNull(agentId).stream()
            .filter(viewing -> withinWindow(viewing, now, horizon))
            .count();

    UUID userId = agent.getUser() == null ? null : agent.getUser().getId();
    long openMaintenance =
        maintenanceRepository.findAllForBoard().stream()
            .filter(
                request ->
                    userId != null
                        && request.getAssignedTo() != null
                        && userId.equals(request.getAssignedTo().getId())
                        && request.isOpen())
            .count();

    return new AgentDtos.WorkloadResponse(
        agent.getId(), agent.getAgentCode(), openLeads, activeListings, viewingsNext7Days, openMaintenance);
  }

  /**
   * An agent's performance over their book: how many leads they converted, how many viewings they
   * completed, the conversion rate, and how many of their listings were let.
   *
   * <p>The conversion rate is over the leads the agent has held and is guarded: with no leads it is
   * {@code 0}, never {@code NaN}. The raw counts travel beside it so "none converted" can be told
   * apart from "none to convert".</p>
   */
  @Transactional(readOnly = true)
  public AgentDtos.PerformanceResponse performance(UUID agentId) {
    AgentProfile agent = require(agentId);

    List<Lead> leads = leadRepository.findAllByOwnerAgentIdAndDeletedAtIsNull(agentId);
    long totalLeads = leads.size();
    long leadsConverted =
        leads.stream().filter(lead -> lead.getStage() == LeadStage.WON).count();

    long viewingsCompleted =
        viewingRepository.findAllByAgentIdAndDeletedAtIsNull(agentId).stream()
            .filter(viewing -> viewing.getStatus() == ViewingStatus.COMPLETED)
            .count();

    long listingsLet =
        listingRepository.findAllForRegister().stream()
            .filter(listing -> isListedBy(listing, agentId))
            .filter(listing -> listing.getStatus() == ListingStatus.LET_AGREED)
            .count();

    return new AgentDtos.PerformanceResponse(
        agent.getId(),
        agent.getAgentCode(),
        totalLeads,
        leadsConverted,
        viewingsCompleted,
        percent(leadsConverted, totalLeads),
        listingsLet);
  }

  /* ====================================================================== */
  /* Writes                                                                 */
  /* ====================================================================== */

  /** Create an agent profile for a staff account, refusing a second profile for the same account. */
  @Transactional
  public AgentDtos.AgentDetail create(AgentDtos.CreateAgentRequest request) {
    User actor = accessScope.requireUser();
    UUID userId = request.userId();

    if (agentRepository.existsByUserIdAndDeletedAtIsNull(userId)) {
      throw ApiException.rule(
          "AGENT_USER_ALREADY_LINKED", ErrorMessages.friendly("AGENT_USER_ALREADY_LINKED"));
    }

    User user =
        userRepository
            .findByIdAndDeletedAtIsNull(userId)
            .orElseThrow(() -> ApiException.notFound("We could not find that staff account"));

    String code = request.agentCode().trim();
    if (agentRepository.existsByAgentCodeIgnoreCaseAndDeletedAtIsNull(code)) {
      throw ApiException.duplicate(
          "DUPLICATE_AGENT_CODE", "An agent with this code already exists");
    }

    AgentProfile agent = new AgentProfile();
    agent.setUser(user);
    agent.setAgentCode(code);
    agent.setOffice(resolveOffice(request.officeId()));
    agent.setManager(resolveManager(request.managerId(), null));
    agent.setSpecialisation(
        parseSpecialisationOrDefault(request.specialisation(), AgentSpecialisation.BOTH));
    agent.setLicenceNumber(trim(request.licenceNumber()));
    agent.setJoinedOn(request.joinedOn());
    agent.setMonthlyTarget(MoneyPolicy.money(request.monthlyTarget()));
    agent.setBio(request.bio());
    agent.setStatus(parseStatusOrDefault(request.status(), AgentStatus.ACTIVE));
    agent.setCreatedBy(actor.getId());
    agent.setUpdatedBy(actor.getId());

    AgentProfile saved = agentRepository.save(agent);
    recordAudit(actor, AuditActions.CREATE, "Added agent " + saved.getAgentCode(), saved);
    return detailOf(saved);
  }

  /** Change an agent profile. Only the fields supplied are applied. */
  @Transactional
  public AgentDtos.AgentDetail update(UUID agentId, AgentDtos.UpdateAgentRequest request) {
    User actor = accessScope.requireUser();
    AgentProfile agent = require(agentId);

    if (request.officeId() != null) agent.setOffice(resolveOffice(request.officeId()));
    if (request.managerId() != null) {
      agent.setManager(resolveManager(request.managerId(), agent.getId()));
    }
    if (request.specialisation() != null) {
      agent.setSpecialisation(
          parseSpecialisationOrDefault(request.specialisation(), agent.getSpecialisation()));
    }
    if (request.licenceNumber() != null) agent.setLicenceNumber(trim(request.licenceNumber()));
    if (request.joinedOn() != null) agent.setJoinedOn(request.joinedOn());
    if (request.monthlyTarget() != null) {
      agent.setMonthlyTarget(MoneyPolicy.money(request.monthlyTarget()));
    }
    if (request.bio() != null) agent.setBio(request.bio());
    if (request.status() != null) agent.setStatus(parseStatusOrDefault(request.status(), null));
    agent.setUpdatedBy(actor.getId());

    AgentProfile saved = agentRepository.save(agent);
    recordAudit(actor, AuditActions.UPDATE, "Changed agent " + saved.getAgentCode(), saved);
    return detailOf(saved);
  }

  /** Archive an agent profile - a soft delete, once. */
  @Transactional
  public AgentDtos.AgentDetail archive(UUID agentId) {
    User actor = accessScope.requireUser();
    AgentProfile agent =
        agentRepository
            .findById(agentId)
            .orElseThrow(() -> ApiException.notFound(ErrorMessages.friendly("AGENT_NOT_FOUND")));

    if (agent.isDeleted()) {
      throw ApiException.rule(
          "AGENT_ALREADY_ARCHIVED", "This agent profile has already been archived");
    }

    agent.setDeletedAt(LocalDateTime.now());
    agent.setUpdatedBy(actor.getId());
    AgentProfile saved = agentRepository.save(agent);
    recordAudit(actor, AuditActions.DELETE, "Archived agent " + saved.getAgentCode(), saved);
    return detailOf(saved);
  }

  /**
   * Assign an agent to a property, refusing a second open primary assignment on that property.
   *
   * <p>The refusal is the schema's {@code uq_agent_property_primary_open} index stated clearly: a
   * second open primary row would make "the property's agent" ambiguous, so it is answered as a 409
   * named {@code AGENT_PRIMARY_ALREADY_SET} rather than left to the index.</p>
   */
  @Transactional
  public AgentDtos.AssignmentRecord assign(UUID agentId, AgentDtos.CreateAssignmentRequest request) {
    User actor = accessScope.requireUser();
    AgentProfile agent = require(agentId);
    Property property =
        propertyRepository
            .findByIdAndDeletedAtIsNull(request.propertyId())
            .orElseThrow(() -> ApiException.notFound(ErrorMessages.friendly("PROPERTY_NOT_FOUND")));

    AssignmentRole role = parseRole(request.role(), AssignmentRole.PRIMARY);
    if (role == AssignmentRole.PRIMARY
        && assignmentRepository.existsByPropertyIdAndRoleAndAssignedToIsNullAndDeletedAtIsNull(
            property.getId(), AssignmentRole.PRIMARY)) {
      throw ApiException.rule(
          "AGENT_PRIMARY_ALREADY_SET",
          "This property already has a primary agent. Close that assignment before naming another.");
    }

    LocalDate from = request.assignedFrom() == null ? LocalDate.now() : request.assignedFrom();

    AgentPropertyAssignment assignment = new AgentPropertyAssignment();
    assignment.setAgent(agent);
    assignment.setProperty(property);
    assignment.setRole(role);
    assignment.setAssignedFrom(from);
    assignment.setCreatedBy(actor.getId());
    assignment.setUpdatedBy(actor.getId());

    AgentPropertyAssignment saved = assignmentRepository.save(assignment);
    recordAssignmentAudit(
        actor,
        "Assigned " + agentName(agent) + " to " + property.getPropertyCode() + " as " + role,
        saved);
    return AgentDtos.AssignmentRecord.from(saved);
  }

  /**
   * Close an assignment - the dated equivalent of a delete. The row survives with an
   * {@code assignedTo} date, so the history stays answerable.
   */
  @Transactional
  public AgentDtos.AssignmentRecord closeAssignment(UUID agentId, UUID assignmentId) {
    User actor = accessScope.requireUser();
    AgentProfile agent = require(agentId);

    AgentPropertyAssignment assignment =
        assignmentRepository
            .findByIdAndDeletedAtIsNull(assignmentId)
            .orElseThrow(() -> ApiException.notFound("We could not find that assignment"));
    if (assignment.getAgent() == null || !agent.getId().equals(assignment.getAgent().getId())) {
      throw ApiException.notFound("We could not find that assignment for this agent");
    }
    if (!assignment.isOpen()) {
      throw ApiException.rule(
          "AGENT_ASSIGNMENT_ALREADY_CLOSED", "That assignment has already ended");
    }

    LocalDate today = LocalDate.now();
    assignment.setAssignedTo(
        assignment.getAssignedFrom() != null && today.isBefore(assignment.getAssignedFrom())
            ? assignment.getAssignedFrom()
            : today);
    assignment.setUpdatedBy(actor.getId());

    AgentPropertyAssignment saved = assignmentRepository.save(assignment);
    recordAssignmentAudit(actor, "Closed an assignment for " + agentName(agent), saved);
    return AgentDtos.AssignmentRecord.from(saved);
  }

  /* ====================================================================== */
  /* Internals                                                              */
  /* ====================================================================== */

  private AgentDtos.AgentDetail detailOf(AgentProfile agent) {
    List<AgentPropertyAssignment> assignments =
        assignmentRepository.findAllForAgent(agent.getId());
    List<AgentDtos.AssignmentRecord> rows =
        assignments.stream().map(AgentDtos.AssignmentRecord::from).toList();
    long open = assignments.stream().filter(a -> a.isOpen()).count();

    Office office = agent.getOffice();
    AgentProfile manager = agent.getManager();

    return new AgentDtos.AgentDetail(
        agent.getId(),
        agent.getAgentCode(),
        agent.getUser() == null ? null : agent.getUser().getId(),
        agent.getUser() == null ? null : agent.getUser().getFullName(),
        agent.getUser() == null ? null : agent.getUser().getEmail(),
        agent.getUser() == null ? null : agent.getUser().getPhone(),
        office == null ? null : office.getId(),
        office == null ? null : office.getName(),
        manager == null ? null : manager.getId(),
        manager == null ? null : agentName(manager),
        name(agent.getSpecialisation()),
        agent.getLicenceNumber(),
        agent.getJoinedOn(),
        agent.getMonthlyTarget(),
        agent.getBio(),
        name(agent.getStatus()),
        assignments.size(),
        open,
        rows,
        agent.getCreatedAt(),
        agent.getUpdatedAt());
  }

  private Office resolveOffice(UUID officeId) {
    if (officeId == null) {
      return null;
    }
    return officeRepository
        .findByIdAndDeletedAtIsNull(officeId)
        .orElseThrow(() -> ApiException.notFound(ErrorMessages.friendly("OFFICE_NOT_FOUND")));
  }

  private AgentProfile resolveManager(UUID managerId, UUID selfId) {
    if (managerId == null) {
      return null;
    }
    if (selfId != null && selfId.equals(managerId)) {
      throw ApiException.badRequest(ErrorMessages.friendly("INVALID_MANAGER_ID"));
    }
    return agentRepository
        .findByIdAndDeletedAtIsNull(managerId)
        .orElseThrow(
            () -> ApiException.badRequest(ErrorMessages.friendly("INVALID_MANAGER_ID")));
  }

  private static boolean isListedBy(Listing listing, UUID agentId) {
    return listing.getListedBy() != null && agentId.equals(listing.getListedBy().getId());
  }

  private static boolean withinWindow(Viewing viewing, LocalDateTime from, LocalDateTime to) {
    LocalDateTime scheduledAt = viewing.getScheduledAt();
    if (scheduledAt == null || scheduledAt.isBefore(from) || scheduledAt.isAfter(to)) {
      return false;
    }
    return viewing.getStatus() == null || viewing.getStatus().isOpen();
  }

  /** A percentage over a denominator, or zero when there is nothing to measure. Never {@code NaN}. */
  static int percent(long part, long whole) {
    if (whole <= 0) return 0;
    return (int) Math.round((part * 100.0) / whole);
  }

  private static boolean matches(AgentProfile agent, String needle) {
    return contains(agent.getAgentCode(), needle)
        || contains(agentName(agent), needle)
        || contains(agent.getUser() == null ? null : agent.getUser().getEmail(), needle);
  }

  private static boolean contains(String value, String needle) {
    return value != null && value.toLowerCase(Locale.ROOT).contains(needle);
  }

  private void recordAudit(User actor, String action, String description, AgentProfile agent) {
    audit.record(
        actor.getId(),
        actor.getFullName(),
        AuditActions.AGENT,
        action,
        description,
        "AGENT",
        agent.getId(),
        agent.getAgentCode());
  }

  private void recordAssignmentAudit(
      User actor, String description, AgentPropertyAssignment assignment) {
    audit.record(
        actor.getId(),
        actor.getFullName(),
        AuditActions.AGENT,
        AuditActions.ASSIGNMENT,
        description,
        "AGENT_PROPERTY_ASSIGNMENT",
        assignment.getId(),
        assignment.getProperty() == null ? null : assignment.getProperty().getPropertyCode());
  }

  private static String agentName(AgentProfile agent) {
    return agent == null || agent.getUser() == null ? null : agent.getUser().getFullName();
  }

  private static AgentStatus parseStatus(String value) {
    if (value == null || value.isBlank()) return null;
    return parseStatusOrDefault(value, null);
  }

  private static AgentStatus parseStatusOrDefault(String value, AgentStatus fallback) {
    if (value == null || value.isBlank()) {
      if (fallback == null) {
        throw ApiException.badRequest("An agent status is required");
      }
      return fallback;
    }
    try {
      return AgentStatus.valueOf(value.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException ex) {
      throw ApiException.badRequest("'" + value + "' is not an agent status");
    }
  }

  private static AgentSpecialisation parseSpecialisation(String value) {
    if (value == null || value.isBlank()) return null;
    return parseSpecialisationOrDefault(value, null);
  }

  private static AgentSpecialisation parseSpecialisationOrDefault(
      String value, AgentSpecialisation fallback) {
    if (value == null || value.isBlank()) {
      if (fallback == null) {
        throw ApiException.badRequest("An agent specialisation is required");
      }
      return fallback;
    }
    try {
      return AgentSpecialisation.valueOf(value.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException ex) {
      throw ApiException.badRequest("'" + value + "' is not an agent specialisation");
    }
  }

  private static AssignmentRole parseRole(String value, AssignmentRole fallback) {
    if (value == null || value.isBlank()) {
      return fallback == null ? AssignmentRole.PRIMARY : fallback;
    }
    try {
      return AssignmentRole.valueOf(value.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException ex) {
      throw ApiException.badRequest("'" + value + "' is not an assignment role");
    }
  }

  private static String name(Enum<?> value) {
    return value == null ? null : value.name();
  }

  private static String trim(String value) {
    return value == null ? null : value.trim();
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }

  // --------------------------------------------------------------------------
  // Paging and sorting, applied here because the combined repository query does
  // not exist. No repository query carries a literal order by.
  // --------------------------------------------------------------------------

  private static List<AgentProfile> sortAgents(List<AgentProfile> items, Sort sort) {
    if (sort == null || sort.isUnsorted()) {
      return items;
    }
    Comparator<AgentProfile> comparator = null;
    for (Sort.Order order : sort) {
      Comparator<AgentProfile> next = agentComparator(order.getProperty());
      if (order.isDescending()) {
        next = next.reversed();
      }
      comparator = comparator == null ? next : comparator.thenComparing(next);
    }
    List<AgentProfile> sorted = new ArrayList<>(items);
    if (comparator != null) {
      sorted.sort(comparator);
    }
    return sorted;
  }

  private static Comparator<AgentProfile> agentComparator(String property) {
    Comparator<AgentProfile> byCreatedAt =
        Comparator.comparing(
            AgentProfile::getCreatedAt,
            Comparator.nullsLast(Comparator.<LocalDateTime>naturalOrder()));
    return switch (property == null ? "" : property) {
      case "agentCode" ->
          Comparator.comparing(
              AgentProfile::getAgentCode, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER));
      case "displayName" ->
          Comparator.comparing(
              AgentService::agentName, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER));
      case "specialisation" ->
          Comparator.comparing(
              current ->
                  current.getSpecialisation() == null ? "" : current.getSpecialisation().name());
      case "status" ->
          Comparator.comparing(
              current -> current.getStatus() == null ? "" : current.getStatus().name());
      default -> byCreatedAt;
    };
  }

  private static <T, R> Page<R> pageOf(List<T> all, Pageable pageable, Function<T, R> mapper) {
    int total = all.size();
    int size = pageable.isPaged() ? pageable.getPageSize() : total;
    int start = pageable.isPaged() ? (int) Math.min(pageable.getOffset(), (long) total) : 0;
    int end = Math.min(start + size, total);
    List<R> content = all.subList(start, end).stream().map(mapper).toList();
    return new PageImpl<>(content, pageable, total);
  }
}
