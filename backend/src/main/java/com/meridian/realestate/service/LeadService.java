package com.meridian.realestate.service;

import com.meridian.realestate.domain.AgentProfile;
import com.meridian.realestate.domain.AgentStatus;
import com.meridian.realestate.domain.Buyer;
import com.meridian.realestate.domain.Lead;
import com.meridian.realestate.domain.LeadActivity;
import com.meridian.realestate.domain.LeadActivityType;
import com.meridian.realestate.domain.LeadAssignment;
import com.meridian.realestate.domain.LeadFollowup;
import com.meridian.realestate.domain.LeadFollowupChannel;
import com.meridian.realestate.domain.LeadFollowupStatus;
import com.meridian.realestate.domain.LeadPriority;
import com.meridian.realestate.domain.LeadSource;
import com.meridian.realestate.domain.LeadStage;
import com.meridian.realestate.domain.LeadType;
import com.meridian.realestate.domain.Office;
import com.meridian.realestate.domain.Property;
import com.meridian.realestate.domain.PropertyType;
import com.meridian.realestate.domain.RentalApplication;
import com.meridian.realestate.domain.Tenant;
import com.meridian.realestate.domain.Unit;
import com.meridian.realestate.domain.User;
import com.meridian.realestate.dto.ApplicationDtos;
import com.meridian.realestate.dto.LeadDtos;
import com.meridian.realestate.dto.PageResponse;
import com.meridian.realestate.dto.ViewingDtos;
import com.meridian.realestate.exception.ApiException;
import com.meridian.realestate.repository.AgentProfileRepository;
import com.meridian.realestate.repository.BuyerRepository;
import com.meridian.realestate.repository.LeadActivityRepository;
import com.meridian.realestate.repository.LeadAssignmentRepository;
import com.meridian.realestate.repository.LeadFollowupRepository;
import com.meridian.realestate.repository.LeadRepository;
import com.meridian.realestate.repository.LeadSourceRepository;
import com.meridian.realestate.repository.OfficeRepository;
import com.meridian.realestate.repository.PropertyRepository;
import com.meridian.realestate.repository.PropertyTypeRepository;
import com.meridian.realestate.repository.RentalApplicationRepository;
import com.meridian.realestate.repository.TenantRepository;
import com.meridian.realestate.repository.UnitRepository;
import com.meridian.realestate.repository.ViewingRepository;
import com.meridian.realestate.security.AccessScope;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The enquiry pipeline: the leads, the work done on them and the stages they move through.
 *
 * <h2>Status changes are operations, not edits</h2>
 * There is no method here that writes a stage from a request field. A lead moves only through
 * {@link #transition}, which validates the move against the map in {@link #ALLOWED} and refuses an
 * illegal move with {@code LEAD_TRANSITION_NOT_ALLOWED} (409). A converted lead cannot be converted
 * again ({@code LEAD_ALREADY_CONVERTED}), and work cannot be parked with an inactive agent
 * ({@code LEAD_ASSIGN_INACTIVE_AGENT}).
 *
 * <h2>The pipeline value is the expected value</h2>
 * The open pipeline sums {@code expected_value} over the leads still being worked - never the budget
 * maximum, which is a wish rather than a forecast. The funnel is cumulative, so it can only narrow.
 *
 * <h2>Scope</h2>
 * A scoped caller - a letting or sales agent - sees only the leads they hold; a firm-wide role sees
 * every lead. The rule is enforced here, in the service, not merely at the endpoint.
 */
@Service
public class LeadService {

  /** The permitted stage moves. See the class note: the map is the rule, not a suggestion. */
  private static final Map<LeadStage, Set<LeadStage>> ALLOWED = new EnumMap<>(LeadStage.class);

  static {
    ALLOWED.put(LeadStage.NEW, EnumSet.of(LeadStage.CONTACTED, LeadStage.LOST));
    ALLOWED.put(LeadStage.CONTACTED, EnumSet.of(LeadStage.QUALIFIED, LeadStage.LOST));
    ALLOWED.put(
        LeadStage.QUALIFIED,
        EnumSet.of(LeadStage.VIEWING, LeadStage.NEGOTIATION, LeadStage.LOST));
    ALLOWED.put(
        LeadStage.VIEWING, EnumSet.of(LeadStage.NEGOTIATION, LeadStage.WON, LeadStage.LOST));
    ALLOWED.put(LeadStage.NEGOTIATION, EnumSet.of(LeadStage.WON, LeadStage.LOST));
    // Terminal: a won lead is a conversion and cannot move again.
    ALLOWED.put(LeadStage.WON, EnumSet.noneOf(LeadStage.class));
    ALLOWED.put(LeadStage.LOST, EnumSet.noneOf(LeadStage.class));
  }

  /** The order the funnel and pipeline report stages in. */
  private static final List<LeadStage> FUNNEL_ORDER =
      List.of(
          LeadStage.NEW,
          LeadStage.CONTACTED,
          LeadStage.QUALIFIED,
          LeadStage.VIEWING,
          LeadStage.NEGOTIATION,
          LeadStage.WON);

  /** The stages a lead is no longer being worked in. These are excluded from pipeline figures. */
  private static final Set<LeadStage> CLOSED_STAGES = EnumSet.of(LeadStage.WON, LeadStage.LOST);

  private static final Map<String, Comparator<Lead>> SORTS =
      Map.of(
          "createdAt", Comparator.comparing(Lead::getCreatedAt, nullSafe()),
          "updatedAt", Comparator.comparing(Lead::getUpdatedAt, nullSafe()),
          "leadCode", Comparator.comparing(Lead::getLeadCode, nullSafe()),
          "displayName", Comparator.comparing(Lead::getDisplayName, nullSafe()),
          "stage", Comparator.comparing((Lead l) -> enumName(l.getStage()), nullSafe()),
          "priority", Comparator.comparing((Lead l) -> enumName(l.getPriority()), nullSafe()),
          "expectedValue", Comparator.comparing(Lead::getExpectedValue, nullSafe()),
          "nextActionAt", Comparator.comparing(Lead::getNextActionAt, nullSafe()),
          "ownerAgent.displayName",
              Comparator.comparing(
                  (Lead l) ->
                      l.getOwnerAgent() == null || l.getOwnerAgent().getUser() == null
                          ? null
                          : l.getOwnerAgent().getUser().getFullName(),
                  nullSafe()),
          "source.name",
              Comparator.comparing(
                  (Lead l) -> l.getSource() == null ? null : l.getSource().getName(), nullSafe()));

  private final LeadRepository leads;
  private final LeadActivityRepository activities;
  private final LeadAssignmentRepository assignments;
  private final LeadFollowupRepository followups;
  private final LeadSourceRepository sources;
  private final PropertyTypeRepository propertyTypes;
  private final PropertyRepository properties;
  private final UnitRepository units;
  private final OfficeRepository offices;
  private final AgentProfileRepository agents;
  private final ViewingRepository viewings;
  private final RentalApplicationRepository applications;
  private final TenantRepository tenants;
  private final BuyerRepository buyers;
  private final BusinessNumberService numbers;
  private final AuditRecordingService audit;
  private final AccessScope accessScope;

  public LeadService(
      LeadRepository leads,
      LeadActivityRepository activities,
      LeadAssignmentRepository assignments,
      LeadFollowupRepository followups,
      LeadSourceRepository sources,
      PropertyTypeRepository propertyTypes,
      PropertyRepository properties,
      UnitRepository units,
      OfficeRepository offices,
      AgentProfileRepository agents,
      ViewingRepository viewings,
      RentalApplicationRepository applications,
      TenantRepository tenants,
      BuyerRepository buyers,
      BusinessNumberService numbers,
      AuditRecordingService audit,
      AccessScope accessScope) {
    this.leads = leads;
    this.activities = activities;
    this.assignments = assignments;
    this.followups = followups;
    this.sources = sources;
    this.propertyTypes = propertyTypes;
    this.properties = properties;
    this.units = units;
    this.offices = offices;
    this.agents = agents;
    this.viewings = viewings;
    this.applications = applications;
    this.tenants = tenants;
    this.buyers = buyers;
    this.numbers = numbers;
    this.audit = audit;
    this.accessScope = accessScope;
  }

  // ---------------------------------------------------------------------------
  // The lifecycle, exposed so a screen can offer only the moves that will succeed
  // ---------------------------------------------------------------------------

  public static boolean canTransition(LeadStage from, LeadStage to) {
    if (from == null || to == null || from == to) return false;
    return ALLOWED.getOrDefault(from, Set.of()).contains(to);
  }

  public static Set<LeadStage> nextStages(LeadStage from) {
    return ALLOWED.getOrDefault(from, Set.of());
  }

  private static Set<String> transitionsFor(LeadStage stage) {
    Set<String> names = new LinkedHashSet<>();
    for (LeadStage next : nextStages(stage)) names.add(next.name());
    return names;
  }

  // ---------------------------------------------------------------------------
  // Reads
  // ---------------------------------------------------------------------------

  @Transactional(readOnly = true)
  public PageResponse<LeadDtos.LeadSummary> list(
      String stage,
      String priority,
      String type,
      String source,
      UUID ownerAgentId,
      UUID officeId,
      LocalDate createdFrom,
      LocalDate createdTo,
      String search,
      Pageable pageable) {

    ScopeFilter scope = scopeFor(ownerAgentId);
    LeadStage stageFilter = stage == null || stage.isBlank() ? null : parseStage(stage);
    LeadPriority priorityFilter = priority == null || priority.isBlank() ? null : parsePriority(priority);
    LeadType typeFilter = type == null || type.isBlank() ? null : parseLeadType(type);
    String sourceFilter = source == null || source.isBlank() ? null : source.trim();
    String term = search == null || search.isBlank() ? null : search.trim().toLowerCase(Locale.ROOT);
    LocalDateTime now = LocalDateTime.now();

    List<LeadDtos.LeadSummary> rows =
        leads.findAllForRegister().stream()
            .filter(lead -> scope.matches(ownerAgentIdOf(lead)))
            .filter(lead -> stageFilter == null || stageFilter == lead.getStage())
            .filter(lead -> priorityFilter == null || priorityFilter == lead.getPriority())
            .filter(lead -> typeFilter == null || typeFilter == lead.getLeadType())
            .filter(
                lead ->
                    sourceFilter == null
                        || (lead.getSource() != null
                            && lead.getSource().getCode() != null
                            && lead.getSource().getCode().equalsIgnoreCase(sourceFilter)))
            .filter(
                lead ->
                    officeId == null
                        || (lead.getOffice() != null && officeId.equals(lead.getOffice().getId())))
            .filter(lead -> withinCreated(lead, createdFrom, createdTo))
            .filter(lead -> term == null || matchesSearch(lead, term))
            .sorted(comparator(pageable))
            .map(lead -> LeadDtos.LeadSummary.from(lead, now))
            .toList();

    return paginate(rows, pageable);
  }

  /** One lead, with its assignments, activities, follow-ups, viewings and applications. */
  @Transactional(readOnly = true)
  public LeadDtos.LeadDetail detail(UUID leadId) {
    return detailOf(require(leadId));
  }

  private LeadDtos.LeadDetail detailOf(Lead lead) {
    requireVisible(lead);
    LocalDateTime now = LocalDateTime.now();
    UUID leadId = lead.getId();

    List<LeadDtos.LeadAssignmentEntry> assignmentRows =
        assignments.findAllByLeadIdAndDeletedAtIsNullOrderByAssignedAtDesc(leadId).stream()
            .map(LeadDtos.LeadAssignmentEntry::from)
            .toList();

    List<LeadDtos.LeadActivityEntry> activityRows =
        activities.findAllByLeadIdAndDeletedAtIsNullOrderByOccurredAtDesc(leadId).stream()
            .map(LeadDtos.LeadActivityEntry::from)
            .toList();

    List<LeadDtos.LeadFollowupEntry> followupRows =
        followups.findAllByLeadIdAndDeletedAtIsNullOrderByDueAtAsc(leadId).stream()
            .map(followup -> LeadDtos.LeadFollowupEntry.from(followup, now))
            .toList();

    List<ViewingDtos.ViewingSummary> viewingRows =
        viewings.findAllForDiary().stream()
            .filter(v -> v.getLead() != null && leadId != null && leadId.equals(v.getLead().getId()))
            .map(ViewingDtos.ViewingSummary::from)
            .toList();

    List<ApplicationDtos.ApplicationSummary> applicationRows =
        applications.findAllForRegister().stream()
            .filter(a -> a.getLead() != null && leadId != null && leadId.equals(a.getLead().getId()))
            .map(a -> ApplicationDtos.ApplicationSummary.from(a, 0))
            .toList();

    return new LeadDtos.LeadDetail(
        lead.getId(),
        lead.getLeadCode(),
        lead.getDisplayName(),
        lead.getCompanyName(),
        enumName(lead.getLeadType()),
        enumName(lead.getStage()),
        enumName(lead.getPriority()),
        lead.getSource() == null ? null : lead.getSource().getCode(),
        lead.getSource() == null ? null : lead.getSource().getName(),
        lead.getOwnerAgent() == null ? null : lead.getOwnerAgent().getId(),
        agentName(lead.getOwnerAgent()),
        lead.getOffice() == null ? null : lead.getOffice().getId(),
        lead.getOffice() == null ? null : lead.getOffice().getName(),
        lead.getEmail(),
        lead.getPhone(),
        lead.getAlternatePhone(),
        lead.getPropertyType() == null ? null : lead.getPropertyType().getCode(),
        lead.getPreferredCity(),
        lead.getPreferredLocality(),
        lead.getMinBudget(),
        lead.getMaxBudget(),
        lead.getMinBedrooms(),
        lead.getRequirementNotes(),
        lead.getProperty() == null ? null : lead.getProperty().getId(),
        lead.getUnit() == null ? null : lead.getUnit().getId(),
        lead.getNextAction(),
        lead.getNextActionAt(),
        lead.getExpectedValue(),
        lead.getWonAt(),
        lead.getLostAt(),
        lead.getLostReason(),
        lead.getConvertedAt(),
        lead.getConvertedTenant() == null ? null : lead.getConvertedTenant().getId(),
        lead.getConvertedBuyer() == null ? null : lead.getConvertedBuyer().getId(),
        lead.getCreatedAt(),
        lead.getUpdatedAt(),
        transitionsFor(lead.getStage()),
        assignmentRows,
        activityRows,
        followupRows,
        viewingRows,
        applicationRows);
  }

  /** How many leads sit in each stage and what the open pipeline is worth. */
  @Transactional(readOnly = true)
  public LeadDtos.PipelineResponse pipeline() {
    Map<LeadStage, Long> counts = countsByStage();
    long total = counts.values().stream().mapToLong(Long::longValue).sum();
    long open = counts.entrySet().stream().filter(e -> e.getKey().isOpen()).mapToLong(Map.Entry::getValue).sum();
    BigDecimal openValue = leads.sumExpectedValueInOpenStages(CLOSED_STAGES);

    List<LeadDtos.StageBucket> buckets = new ArrayList<>();
    for (LeadStage stage : LeadStage.values()) {
      long count = counts.getOrDefault(stage, 0L);
      buckets.add(new LeadDtos.StageBucket(stage.name(), count, null, stage.isOpen()));
    }

    return new LeadDtos.PipelineResponse(total, open, safe(openValue), LocalDateTime.now(), buckets);
  }

  /**
   * The conversion funnel.
   *
   * <p>Cumulative by construction: each rung counts the leads that reached that stage or went past
   * it, so the counts can only fall. A lost lead is counted at the top of the funnel only - where it
   * fell out is not recorded, so it is not attributed to a stage it may never have reached.</p>
   */
  @Transactional(readOnly = true)
  public LeadDtos.FunnelResponse funnel() {
    Map<LeadStage, Long> counts = countsByStage();
    long total = counts.values().stream().mapToLong(Long::longValue).sum();

    List<LeadDtos.FunnelRung> rungs = new ArrayList<>();
    for (int i = 0; i < FUNNEL_ORDER.size(); i++) {
      LeadStage rungStage = FUNNEL_ORDER.get(i);
      int rank = i;
      long reached =
          counts.entrySet().stream()
              .filter(entry -> rankOf(entry.getKey()) >= rank)
              .mapToLong(Map.Entry::getValue)
              .sum();
      rungs.add(new LeadDtos.FunnelRung(rungStage.name(), labelOf(rungStage), reached));
    }

    return new LeadDtos.FunnelResponse(total, rungs, LocalDateTime.now());
  }

  /** The leads whose next action has come due, and the follow-ups that are overdue. */
  @Transactional(readOnly = true)
  public LeadDtos.NeedsAttentionResponse needsAttention() {
    LocalDateTime now = LocalDateTime.now();
    ScopeFilter scope = scopeFor(null);

    List<LeadDtos.LeadSummary> due =
        leads.findAllByNextActionAtBeforeAndDeletedAtIsNull(now).stream()
            .filter(lead -> lead.getStage() != null && lead.getStage().isOpen())
            .filter(lead -> scope.matches(ownerAgentIdOf(lead)))
            .map(lead -> LeadDtos.LeadSummary.from(lead, now))
            .toList();

    List<LeadDtos.LeadFollowupEntry> overdue =
        followups.findAllByStatusAndDueAtBeforeAndDeletedAtIsNull(LeadFollowupStatus.PENDING, now).stream()
            .filter(followup -> scope.matches(ownerAgentIdOf(followup.getLead())))
            .map(followup -> LeadDtos.LeadFollowupEntry.from(followup, now))
            .toList();

    return new LeadDtos.NeedsAttentionResponse(due.size(), overdue.size(), due, overdue);
  }

  // ---------------------------------------------------------------------------
  // The operations
  // ---------------------------------------------------------------------------

  /** Log a lead. Every lead starts at NEW, whatever a caller may have hoped. */
  @Transactional
  public LeadDtos.LeadDetail create(LeadDtos.CreateLeadRequest request) {
    User actor = accessScope.requireUser();
    assertBudgetRange(request.minBudget(), request.maxBudget());

    Lead lead = new Lead();
    lead.setLeadCode(numbers.next(BusinessNumberService.LEAD));
    lead.setLeadType(parseLeadType(request.leadType()));
    lead.setStage(LeadStage.NEW);
    lead.setPriority(parsePriority(request.priority()));
    lead.setDisplayName(request.displayName().trim());
    lead.setCompanyName(trim(request.companyName()));
    lead.setEmail(lower(request.email()));
    lead.setPhone(trim(request.phone()));
    lead.setAlternatePhone(trim(request.alternatePhone()));
    lead.setSource(request.sourceCode() == null || request.sourceCode().isBlank() ? null : requireSource(request.sourceCode()));
    lead.setPropertyType(
        request.propertyTypeCode() == null || request.propertyTypeCode().isBlank()
            ? null
            : requirePropertyType(request.propertyTypeCode()));
    lead.setPreferredCity(trim(request.preferredCity()));
    lead.setPreferredLocality(trim(request.preferredLocality()));
    lead.setMinBudget(request.minBudget());
    lead.setMaxBudget(request.maxBudget());
    lead.setMinBedrooms(request.minBedrooms());
    lead.setRequirementNotes(request.requirementNotes());
    applyTarget(lead, request.propertyId(), request.unitId());
    lead.setOffice(request.officeId() == null ? null : requireOffice(request.officeId()));
    lead.setNextAction(trim(request.nextAction()));
    lead.setNextActionAt(request.nextActionAt());
    lead.setExpectedValue(request.expectedValue());
    lead.setCreatedBy(actor.getId());
    lead.setUpdatedBy(actor.getId());

    if (request.ownerAgentId() != null) {
      AgentProfile agent = requireActiveAgent(request.ownerAgentId());
      lead.setOwnerAgent(agent);
    }

    Lead saved = leads.save(lead);

    if (saved.getOwnerAgent() != null) {
      openAssignment(saved, saved.getOwnerAgent(), "Assigned on intake", actor);
    }

    recordAudit(actor, AuditActions.CREATE, "Logged lead " + saved.getLeadCode(), saved);
    return detailOf(saved);
  }

  /** Change a lead's details. A null field is left unchanged; the stage has its own endpoint. */
  @Transactional
  public LeadDtos.LeadDetail update(UUID leadId, LeadDtos.UpdateLeadRequest request) {
    User actor = accessScope.requireUser();
    Lead lead = require(leadId);
    requireVisible(lead);

    if (request.leadType() != null) lead.setLeadType(parseLeadType(request.leadType()));
    if (request.priority() != null) lead.setPriority(parsePriority(request.priority()));
    if (request.displayName() != null && !request.displayName().isBlank()) {
      lead.setDisplayName(request.displayName().trim());
    }
    if (request.companyName() != null) lead.setCompanyName(trim(request.companyName()));
    if (request.email() != null) lead.setEmail(lower(request.email()));
    if (request.phone() != null) lead.setPhone(trim(request.phone()));
    if (request.alternatePhone() != null) lead.setAlternatePhone(trim(request.alternatePhone()));
    if (request.sourceCode() != null) {
      lead.setSource(request.sourceCode().isBlank() ? null : requireSource(request.sourceCode()));
    }
    if (request.propertyTypeCode() != null) {
      lead.setPropertyType(
          request.propertyTypeCode().isBlank() ? null : requirePropertyType(request.propertyTypeCode()));
    }
    if (request.preferredCity() != null) lead.setPreferredCity(trim(request.preferredCity()));
    if (request.preferredLocality() != null) lead.setPreferredLocality(trim(request.preferredLocality()));

    BigDecimal min = request.minBudget() == null ? lead.getMinBudget() : request.minBudget();
    BigDecimal max = request.maxBudget() == null ? lead.getMaxBudget() : request.maxBudget();
    assertBudgetRange(min, max);
    if (request.minBudget() != null) lead.setMinBudget(request.minBudget());
    if (request.maxBudget() != null) lead.setMaxBudget(request.maxBudget());

    if (request.minBedrooms() != null) lead.setMinBedrooms(request.minBedrooms());
    if (request.requirementNotes() != null) lead.setRequirementNotes(request.requirementNotes());
    if (request.propertyId() != null || request.unitId() != null) {
      UUID propertyId = request.propertyId() == null
          ? (lead.getProperty() == null ? null : lead.getProperty().getId())
          : request.propertyId();
      UUID unitId = request.unitId() == null
          ? (lead.getUnit() == null ? null : lead.getUnit().getId())
          : request.unitId();
      applyTarget(lead, propertyId, unitId);
    }
    if (request.officeId() != null) lead.setOffice(requireOffice(request.officeId()));
    if (request.nextAction() != null) lead.setNextAction(trim(request.nextAction()));
    if (request.nextActionAt() != null) lead.setNextActionAt(request.nextActionAt());
    if (request.expectedValue() != null) lead.setExpectedValue(request.expectedValue());
    if (request.lostReason() != null) lead.setLostReason(trim(request.lostReason()));
    lead.setUpdatedBy(actor.getId());

    Lead saved = leads.save(lead);
    recordAudit(actor, AuditActions.UPDATE, "Changed lead " + saved.getLeadCode(), saved);
    return detailOf(saved);
  }

  /** Move a lead between pipeline stages, writing the outcome columns with the stage. */
  @Transactional
  public LeadDtos.LeadDetail transition(UUID leadId, LeadDtos.StageTransitionRequest request) {
    User actor = accessScope.requireUser();
    Lead lead = require(leadId);
    requireVisible(lead);

    LeadStage from = lead.getStage();
    LeadStage target = parseStage(request.stage());

    if (from == target) {
      throw ApiException.rule("LEAD_TRANSITION_NOT_ALLOWED", "This lead is already at " + target + ".");
    }
    if (!canTransition(from, target)) {
      throw ApiException.rule(
          "LEAD_TRANSITION_NOT_ALLOWED",
          "A lead cannot move from " + from + " to " + target + ". From " + from + " the possible moves are "
              + nextStages(from) + ".");
    }

    LocalDateTime now = LocalDateTime.now();
    // The stage and its outcome columns are written together: the schema refuses a half-applied outcome.
    if (target == LeadStage.WON) {
      lead.setWonAt(now);
      lead.setLostAt(null);
      lead.setLostReason(null);
    } else if (target == LeadStage.LOST) {
      if (isBlank(request.lostReason())) {
        throw ApiException.badRequest("A lost lead must record a reason");
      }
      lead.setLostAt(now);
      lead.setWonAt(null);
      lead.setLostReason(request.lostReason().trim());
    } else {
      lead.setWonAt(null);
      lead.setLostAt(null);
    }
    lead.setStage(target);
    lead.setUpdatedBy(actor.getId());

    Lead saved = leads.save(lead);
    logStageActivity(saved, from, target, request.reason(), actor);
    recordAudit(
        actor,
        AuditActions.STATUS_CHANGE,
        "Moved lead " + saved.getLeadCode() + " from " + from + " to " + target,
        saved);

    return detailOf(saved);
  }

  /** Assign or reassign the agent who holds a lead, keeping the assignment history. */
  @Transactional
  public LeadDtos.LeadDetail assign(UUID leadId, LeadDtos.AssignLeadRequest request) {
    User actor = accessScope.requireUser();
    Lead lead = require(leadId);
    requireVisible(lead);

    // Validated before anything is written: an inactive agent must not receive the work at all.
    AgentProfile agent = requireActiveAgent(request.agentId());
    LocalDateTime now = LocalDateTime.now();

    assignments
        .findByLeadIdAndUnassignedAtIsNullAndDeletedAtIsNull(leadId)
        .ifPresent(
            current -> {
              current.setUnassignedAt(now);
              assignments.save(current);
            });

    lead.setOwnerAgent(agent);
    lead.setUpdatedBy(actor.getId());
    Lead saved = leads.save(lead);

    LeadAssignment assignment = new LeadAssignment();
    assignment.setLead(saved);
    assignment.setAgent(agent);
    assignment.setAssignedAt(now);
    assignment.setAssignedBy(actor);
    assignment.setReason(trim(request.reason()));
    assignment.setCreatedBy(actor.getId());
    assignment.setUpdatedBy(actor.getId());
    assignments.save(assignment);

    recordAudit(
        actor,
        AuditActions.ASSIGNMENT,
        "Assigned lead " + saved.getLeadCode() + " to " + agentName(agent),
        saved);

    return detailOf(saved);
  }

  /** Convert a won lead into a tenant or a buyer. Refuses a lead that has already been converted. */
  @Transactional
  public LeadDtos.LeadDetail convert(UUID leadId, LeadDtos.ConvertLeadRequest request) {
    User actor = accessScope.requireUser();
    Lead lead = require(leadId);
    requireVisible(lead);

    if (lead.getConvertedAt() != null) {
      throw ApiException.rule("LEAD_ALREADY_CONVERTED", "This lead has already been converted.");
    }

    Tenant tenant = request.tenantId() == null ? null : requireTenant(request.tenantId());
    Buyer buyer = request.buyerId() == null ? null : requireBuyer(request.buyerId());
    if (tenant == null && buyer == null) {
      throw ApiException.badRequest("A conversion must name the tenant or the buyer it produced");
    }

    LeadStage from = lead.getStage();
    LocalDateTime now = LocalDateTime.now();
    if (from != LeadStage.WON) {
      if (!canTransition(from, LeadStage.WON)) {
        throw ApiException.rule(
            "LEAD_TRANSITION_NOT_ALLOWED",
            "A lead at " + from + " cannot be converted. From " + from + " the possible moves are "
                + nextStages(from) + ".");
      }
      lead.setStage(LeadStage.WON);
      lead.setWonAt(now);
      lead.setLostAt(null);
      lead.setLostReason(null);
    }

    lead.setConvertedTenant(tenant);
    lead.setConvertedBuyer(buyer);
    lead.setConvertedAt(now);
    lead.setUpdatedBy(actor.getId());

    Lead saved = leads.save(lead);
    logStageActivity(saved, from, saved.getStage(), "Converted", actor);
    recordAudit(actor, AuditActions.UPDATE, "Converted lead " + saved.getLeadCode(), saved);

    return detailOf(saved);
  }

  /** Log work done against a lead. */
  @Transactional
  public LeadDtos.LeadActivityEntry logActivity(UUID leadId, LeadDtos.LogActivityRequest request) {
    User actor = accessScope.requireUser();
    Lead lead = require(leadId);
    requireVisible(lead);

    LeadActivity activity = new LeadActivity();
    activity.setLead(lead);
    activity.setActivityType(parseActivityType(request.activityType()));
    activity.setSubject(request.subject().trim());
    activity.setNotes(request.notes());
    activity.setOccurredAt(request.occurredAt() == null ? LocalDateTime.now() : request.occurredAt());
    activity.setDurationMinutes(request.durationMinutes());
    activity.setPerformedBy(actor);
    activity.setCreatedBy(actor.getId());
    activity.setUpdatedBy(actor.getId());

    LeadActivity saved = activities.save(activity);
    recordAudit(actor, AuditActions.CREATE, "Logged " + saved.getActivityType() + " on lead " + lead.getLeadCode(), lead);
    return LeadDtos.LeadActivityEntry.from(saved);
  }

  /** Plan a follow-up against a lead, bringing the lead's next action forward when it is sooner. */
  @Transactional
  public LeadDtos.LeadFollowupEntry logFollowup(UUID leadId, LeadDtos.LogFollowupRequest request) {
    User actor = accessScope.requireUser();
    Lead lead = require(leadId);
    requireVisible(lead);

    LeadFollowup followup = new LeadFollowup();
    followup.setLead(lead);
    followup.setDueAt(request.dueAt());
    followup.setChannel(parseFollowupChannel(request.channel()));
    followup.setNotes(request.notes());
    // PENDING: a follow-up that is not done must carry no completion time.
    followup.setStatus(LeadFollowupStatus.PENDING);
    followup.setCompletedAt(null);
    followup.setAssignedTo(
        request.assignedToId() == null ? lead.getOwnerAgent() : requireAgent(request.assignedToId()));
    followup.setCreatedBy(actor.getId());
    followup.setUpdatedBy(actor.getId());

    // Due-date handling: the lead's next action tracks the soonest planned follow-up.
    if (lead.getNextActionAt() == null || request.dueAt().isBefore(lead.getNextActionAt())) {
      lead.setNextActionAt(request.dueAt());
      if (isBlank(lead.getNextAction())) {
        lead.setNextAction("Follow up (" + followup.getChannel() + ")");
      }
      lead.setUpdatedBy(actor.getId());
      leads.save(lead);
    }

    LeadFollowup saved = followups.save(followup);
    recordAudit(actor, AuditActions.CREATE, "Planned a follow-up on lead " + lead.getLeadCode(), lead);
    return LeadDtos.LeadFollowupEntry.from(saved, LocalDateTime.now());
  }

  /** Close a follow-up as done, cancelled or missed. */
  @Transactional
  public LeadDtos.LeadFollowupEntry completeFollowup(UUID followupId, LeadDtos.CompleteFollowupRequest request) {
    User actor = accessScope.requireUser();
    LeadFollowup followup =
        followups
            .findById(followupId)
            .filter(row -> row.getDeletedAt() == null)
            .orElseThrow(() -> ApiException.notFound("We could not find that follow-up"));
    requireVisible(followup.getLead());

    LeadFollowupStatus status = parseFollowupStatus(request.status());
    // One write: the status and its completion time together, as ck_lead_followups_completion requires.
    if (status == LeadFollowupStatus.DONE) {
      followup.setCompletedAt(LocalDateTime.now());
    } else {
      followup.setCompletedAt(null);
    }
    followup.setStatus(status);
    followup.setOutcome(trim(request.outcome()));
    followup.setUpdatedBy(actor.getId());

    LeadFollowup saved = followups.save(followup);
    recordAudit(
        actor,
        AuditActions.UPDATE,
        "Closed a follow-up on lead " + (saved.getLead() == null ? null : saved.getLead().getLeadCode()),
        saved.getLead());
    return LeadDtos.LeadFollowupEntry.from(saved, LocalDateTime.now());
  }

  // ---------------------------------------------------------------------------
  // Internals
  // ---------------------------------------------------------------------------

  @Transactional(readOnly = true)
  public Lead require(UUID leadId) {
    return leads
        .findByIdAndDeletedAtIsNull(leadId)
        .orElseThrow(() -> ApiException.notFound("We could not find that lead"));
  }

  private void requireVisible(Lead lead) {
    if (lead == null) {
      return;
    }
    if (accessScope.isCompanyWide()) {
      return;
    }
    accessScope.requireAgentOrCompanyWide(
        lead.getOwnerAgent() == null ? null : lead.getOwnerAgent().getId());
  }

  private Map<LeadStage, Long> countsByStage() {
    Map<LeadStage, Long> counts = new EnumMap<>(LeadStage.class);
    for (Object[] row : leads.countGroupedByStage()) {
      if (row == null || row.length < 2 || row[0] == null) continue;
      LeadStage stage = (LeadStage) row[0];
      long total = ((Number) row[1]).longValue();
      counts.merge(stage, total, Long::sum);
    }
    return counts;
  }

  /** A lost lead is counted at the top of the funnel only; see {@link #funnel()}. */
  private static int rankOf(LeadStage stage) {
    if (stage == null || stage == LeadStage.LOST) return 0;
    return switch (stage) {
      case NEW -> 0;
      case CONTACTED -> 1;
      case QUALIFIED -> 2;
      case VIEWING -> 3;
      case NEGOTIATION -> 4;
      case WON -> 5;
      default -> 0;
    };
  }

  private static String labelOf(LeadStage stage) {
    String name = stage.name();
    return name.charAt(0) + name.substring(1).toLowerCase(Locale.ROOT).replace('_', ' ');
  }

  private void openAssignment(Lead lead, AgentProfile agent, String reason, User actor) {
    LocalDateTime now = LocalDateTime.now();
    LeadAssignment assignment = new LeadAssignment();
    assignment.setLead(lead);
    assignment.setAgent(agent);
    assignment.setAssignedAt(now);
    assignment.setAssignedBy(actor);
    assignment.setReason(reason);
    assignment.setCreatedBy(actor.getId());
    assignment.setUpdatedBy(actor.getId());
    assignments.save(assignment);
  }

  private void logStageActivity(Lead lead, LeadStage from, LeadStage to, String reason, User actor) {
    LeadActivity activity = new LeadActivity();
    activity.setLead(lead);
    activity.setActivityType(LeadActivityType.STAGE_CHANGE);
    activity.setSubject("Stage " + from + " to " + to);
    activity.setNotes(reason);
    activity.setOccurredAt(LocalDateTime.now());
    activity.setPerformedBy(actor);
    activity.setFromStage(from);
    activity.setToStage(to);
    activity.setCreatedBy(actor.getId());
    activity.setUpdatedBy(actor.getId());
    activities.save(activity);
  }

  private void recordAudit(User actor, String action, String description, Lead lead) {
    if (lead == null) return;
    audit.record(
        actor.getId(),
        actor.getFullName(),
        AuditActions.LEAD,
        action,
        description,
        "LEAD",
        lead.getId(),
        lead.getLeadCode());
  }

  private void applyTarget(Lead lead, UUID propertyId, UUID unitId) {
    Property property = propertyId == null ? null : requireProperty(propertyId);
    Unit unit = unitId == null ? null : requireUnit(unitId);
    if (unit != null && property == null) {
      property = unit.getProperty();
    }
    if (unit != null && property != null && property.getId() != null
        && (unit.getProperty() == null || !property.getId().equals(unit.getProperty().getId()))) {
      throw ApiException.badRequest("The unit does not belong to the chosen property");
    }
    lead.setProperty(property);
    lead.setUnit(unit);
  }

  private AgentProfile requireActiveAgent(UUID agentId) {
    AgentProfile agent = requireAgent(agentId);
    if (agent.getStatus() != AgentStatus.ACTIVE) {
      throw ApiException.rule(
          "LEAD_ASSIGN_INACTIVE_AGENT",
          agentName(agent) + " is " + agent.getStatus() + " and cannot be assigned work.");
    }
    return agent;
  }

  private AgentProfile requireAgent(UUID agentId) {
    return agents
        .findByIdAndDeletedAtIsNull(agentId)
        .orElseThrow(() -> ApiException.notFound("We could not find that agent"));
  }

  private Property requireProperty(UUID id) {
    return properties
        .findByIdAndDeletedAtIsNull(id)
        .orElseThrow(() -> ApiException.notFound("We could not find that property"));
  }

  private Unit requireUnit(UUID id) {
    return units
        .findByIdAndDeletedAtIsNull(id)
        .orElseThrow(() -> ApiException.notFound("We could not find that unit"));
  }

  private Office requireOffice(UUID id) {
    return offices
        .findByIdAndDeletedAtIsNull(id)
        .orElseThrow(() -> ApiException.notFound("We could not find that office"));
  }

  private LeadSource requireSource(String code) {
    return sources
        .findByCodeIgnoreCase(code.trim())
        .orElseThrow(() -> ApiException.notFound("'" + code + "' is not a known lead source"));
  }

  private PropertyType requirePropertyType(String code) {
    return propertyTypes
        .findByCode(code.trim())
        .orElseThrow(() -> ApiException.notFound("'" + code + "' is not a known property type"));
  }

  private Tenant requireTenant(UUID id) {
    return tenants
        .findByIdAndDeletedAtIsNull(id)
        .orElseThrow(() -> ApiException.notFound("We could not find that tenant"));
  }

  private Buyer requireBuyer(UUID id) {
    return buyers
        .findByIdAndDeletedAtIsNull(id)
        .orElseThrow(() -> ApiException.notFound("We could not find that buyer"));
  }

  private static void assertBudgetRange(BigDecimal min, BigDecimal max) {
    if (min != null && max != null && max.compareTo(min) < 0) {
      throw ApiException.badRequest("The maximum budget cannot be less than the minimum budget");
    }
  }

  private static boolean matchesSearch(Lead lead, String term) {
    return contains(lead.getDisplayName(), term)
        || contains(lead.getCompanyName(), term)
        || contains(lead.getEmail(), term)
        || contains(lead.getPhone(), term)
        || contains(lead.getLeadCode(), term)
        || contains(lead.getPreferredCity(), term)
        || contains(lead.getPreferredLocality(), term);
  }

  private static boolean contains(String value, String term) {
    return value != null && value.toLowerCase(Locale.ROOT).contains(term);
  }

  private static boolean withinCreated(Lead lead, LocalDate from, LocalDate to) {
    LocalDateTime createdAt = lead.getCreatedAt();
    if (createdAt == null) return from == null && to == null;
    LocalDate date = createdAt.toLocalDate();
    if (from != null && date.isBefore(from)) return false;
    if (to != null && date.isAfter(to)) return false;
    return true;
  }

  private static BigDecimal safe(BigDecimal value) {
    return value == null ? BigDecimal.ZERO : value;
  }

  private static UUID ownerAgentIdOf(Lead lead) {
    return lead == null || lead.getOwnerAgent() == null ? null : lead.getOwnerAgent().getId();
  }

  private static String agentName(AgentProfile agent) {
    if (agent == null || agent.getUser() == null) return null;
    return agent.getUser().getFullName();
  }

  private static String enumName(Enum<?> value) {
    return value == null ? null : value.name();
  }

  private static String trim(String value) {
    return value == null ? null : value.trim();
  }

  private static String lower(String value) {
    return value == null || value.isBlank() ? null : value.trim().toLowerCase(Locale.ROOT);
  }

  private static boolean isBlank(String value) {
    return value == null || value.isBlank();
  }

  private static LeadType parseLeadType(String value) {
    if (isBlank(value)) return LeadType.RENTAL;
    try {
      return LeadType.valueOf(value.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException ex) {
      throw ApiException.badRequest("'" + value + "' is not a lead type");
    }
  }

  private static LeadPriority parsePriority(String value) {
    if (isBlank(value)) return LeadPriority.MEDIUM;
    try {
      return LeadPriority.valueOf(value.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException ex) {
      throw ApiException.badRequest("'" + value + "' is not a lead priority");
    }
  }

  private static LeadStage parseStage(String value) {
    try {
      return LeadStage.valueOf(value.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException ex) {
      throw ApiException.badRequest("'" + value + "' is not a lead stage");
    }
  }

  private static LeadActivityType parseActivityType(String value) {
    try {
      return LeadActivityType.valueOf(value.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException ex) {
      throw ApiException.badRequest("'" + value + "' is not a lead activity type");
    }
  }

  private static LeadFollowupChannel parseFollowupChannel(String value) {
    if (isBlank(value)) return LeadFollowupChannel.CALL;
    try {
      return LeadFollowupChannel.valueOf(value.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException ex) {
      throw ApiException.badRequest("'" + value + "' is not a follow-up channel");
    }
  }

  private static LeadFollowupStatus parseFollowupStatus(String value) {
    try {
      return LeadFollowupStatus.valueOf(value.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException ex) {
      throw ApiException.badRequest("'" + value + "' is not a follow-up status");
    }
  }

  private static <T extends Comparable<? super T>> Comparator<T> nullSafe() {
    return Comparator.nullsLast(Comparator.naturalOrder());
  }

  private static Comparator<Lead> comparator(Pageable pageable) {
    Comparator<Lead> tieBreak =
        Comparator.comparing(Lead::getCreatedAt, nullSafe()).thenComparing(Lead::getId, nullSafe());
    Comparator<Lead> chosen = null;
    Sort sort = pageable == null ? Sort.unsorted() : pageable.getSort();
    for (Sort.Order order : sort) {
      Comparator<Lead> by = SORTS.get(order.getProperty());
      if (by == null) continue;
      if (order.isDescending()) by = by.reversed();
      chosen = chosen == null ? by : chosen.thenComparing(by);
    }
    return chosen == null ? tieBreak.reversed() : chosen.thenComparing(tieBreak);
  }

  private static <T> PageResponse<T> paginate(List<T> items, Pageable pageable) {
    int size = pageable == null || pageable.getPageSize() < 1 ? 20 : pageable.getPageSize();
    int page = pageable == null ? 0 : pageable.getPageNumber();
    int from = Math.min(page * size, items.size());
    int to = Math.min(from + size, items.size());
    return PageResponse.of(items.subList(from, to), page, size, items.size());
  }

  // ---------------------------------------------------------------------------
  // Scope
  // ---------------------------------------------------------------------------

  private record ScopeFilter(boolean restricted, UUID agentProfileId) {
    boolean matches(UUID ownerAgentId) {
      if (!restricted) return true;
      return ownerAgentId != null && ownerAgentId.equals(agentProfileId);
    }
  }

  private ScopeFilter scopeFor(UUID requestedAgentId) {
    if (accessScope.isCompanyWide()) {
      return new ScopeFilter(false, requestedAgentId);
    }
    return new ScopeFilter(true, ownAgentProfileId());
  }

  private UUID ownAgentProfileId() {
    return agents
        .findByUserIdAndDeletedAtIsNull(accessScope.requireUserId())
        .map(AgentProfile::getId)
        .orElse(null);
  }
}
