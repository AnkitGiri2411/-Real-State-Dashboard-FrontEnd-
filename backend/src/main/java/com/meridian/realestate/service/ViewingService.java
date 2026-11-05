package com.meridian.realestate.service;

import com.meridian.realestate.domain.AgentProfile;
import com.meridian.realestate.domain.Buyer;
import com.meridian.realestate.domain.Lead;
import com.meridian.realestate.domain.Property;
import com.meridian.realestate.domain.Tenant;
import com.meridian.realestate.domain.Unit;
import com.meridian.realestate.domain.User;
import com.meridian.realestate.domain.Viewing;
import com.meridian.realestate.domain.ViewingStatus;
import com.meridian.realestate.domain.ViewingStatusHistory;
import com.meridian.realestate.dto.PageResponse;
import com.meridian.realestate.dto.ViewingDtos;
import com.meridian.realestate.exception.ApiException;
import com.meridian.realestate.repository.AgentProfileRepository;
import com.meridian.realestate.repository.BuyerRepository;
import com.meridian.realestate.repository.LeadRepository;
import com.meridian.realestate.repository.PropertyRepository;
import com.meridian.realestate.repository.TenantRepository;
import com.meridian.realestate.repository.UnitRepository;
import com.meridian.realestate.repository.ViewingRepository;
import com.meridian.realestate.repository.ViewingStatusHistoryRepository;
import com.meridian.realestate.security.AccessScope;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
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
 * The viewing diary, and everything that moves an appointment.
 *
 * <h2>Status changes are operations, not edits</h2>
 * There is no method here that writes a status on its own. A viewing moves only through the named
 * operations - confirm, complete, cancel, no-show, reschedule - and each validates the move against
 * the lifecycle stated in {@link #ALLOWED}. A generic "set status" endpoint would be an invitation to
 * skip the rules.
 *
 * <h2>The outcome is written whole</h2>
 * {@code ck_viewings_outcome} refuses a half-applied outcome: a {@code COMPLETED} viewing must carry
 * both a completion time and feedback, a {@code CANCELLED} or {@code NO_SHOW} viewing must carry a
 * reason and no completion time, and a {@code SCHEDULED} or {@code CONFIRMED} viewing must not be
 * completed. Every operation below therefore sets the status and its outcome columns in one write
 * against the loaded entity, and refuses an invalid combination with a named code
 * ({@code VIEWING_NOT_COMPLETABLE}, {@code VIEWING_ALREADY_CANCELLED}) rather than letting the
 * constraint fail opaquely.
 *
 * <h2>Scope</h2>
 * A scoped caller - a letting or sales agent - sees only their own diary; a firm-wide role sees every
 * appointment. The rule is enforced here, in the service, not merely at the endpoint.
 */
@Service
public class ViewingService {

  /** The moves the lifecycle permits, from each status. */
  private static final Map<ViewingStatus, Set<ViewingStatus>> ALLOWED =
      new EnumMap<>(ViewingStatus.class);

  static {
    ALLOWED.put(
        ViewingStatus.SCHEDULED,
        EnumSet.of(
            ViewingStatus.CONFIRMED,
            ViewingStatus.COMPLETED,
            ViewingStatus.CANCELLED,
            ViewingStatus.NO_SHOW));
    ALLOWED.put(
        ViewingStatus.CONFIRMED,
        EnumSet.of(ViewingStatus.COMPLETED, ViewingStatus.CANCELLED, ViewingStatus.NO_SHOW));
    // Terminal: an outcome has been recorded and it is not rewritten.
    ALLOWED.put(ViewingStatus.COMPLETED, EnumSet.noneOf(ViewingStatus.class));
    ALLOWED.put(ViewingStatus.CANCELLED, EnumSet.noneOf(ViewingStatus.class));
    ALLOWED.put(ViewingStatus.NO_SHOW, EnumSet.noneOf(ViewingStatus.class));
  }

  /** The statuses a diary still counts as ahead of the reader. */
  public static final Set<ViewingStatus> OPEN_STATUSES =
      EnumSet.of(ViewingStatus.SCHEDULED, ViewingStatus.CONFIRMED);

  private static final Map<String, Comparator<Viewing>> SORTS =
      Map.of(
          "scheduledAt",
              Comparator.comparing(Viewing::getScheduledAt, nullSafe()),
          "createdAt",
              Comparator.comparing(Viewing::getCreatedAt, nullSafe()),
          "updatedAt",
              Comparator.comparing(Viewing::getUpdatedAt, nullSafe()),
          "status",
              Comparator.comparing((Viewing v) -> enumName(v.getStatus()), nullSafe()),
          "viewingCode",
              Comparator.comparing(Viewing::getViewingCode, nullSafe()),
          "agent.agentCode",
              Comparator.comparing(
                  (Viewing v) -> v.getAgent() == null ? null : v.getAgent().getAgentCode(),
                  nullSafe()),
          "property.name",
              Comparator.comparing(
                  (Viewing v) -> v.getProperty() == null ? null : v.getProperty().getName(),
                  nullSafe()));

  private final ViewingRepository viewings;
  private final ViewingStatusHistoryRepository history;
  private final PropertyRepository properties;
  private final UnitRepository units;
  private final LeadRepository leads;
  private final BuyerRepository buyers;
  private final TenantRepository tenants;
  private final AgentProfileRepository agents;
  private final BusinessNumberService numbers;
  private final AuditRecordingService audit;
  private final AccessScope accessScope;

  public ViewingService(
      ViewingRepository viewings,
      ViewingStatusHistoryRepository history,
      PropertyRepository properties,
      UnitRepository units,
      LeadRepository leads,
      BuyerRepository buyers,
      TenantRepository tenants,
      AgentProfileRepository agents,
      BusinessNumberService numbers,
      AuditRecordingService audit,
      AccessScope accessScope) {
    this.viewings = viewings;
    this.history = history;
    this.properties = properties;
    this.units = units;
    this.leads = leads;
    this.buyers = buyers;
    this.tenants = tenants;
    this.agents = agents;
    this.numbers = numbers;
    this.audit = audit;
    this.accessScope = accessScope;
  }

  // ---------------------------------------------------------------------------
  // The lifecycle, exposed so a screen can offer only the moves that will succeed
  // ---------------------------------------------------------------------------

  public static boolean canTransition(ViewingStatus from, ViewingStatus to) {
    if (from == null || to == null || from == to) return false;
    return ALLOWED.getOrDefault(from, Set.of()).contains(to);
  }

  public static Set<ViewingStatus> nextStates(ViewingStatus from) {
    return ALLOWED.getOrDefault(from, Set.of());
  }

  private static Set<String> transitionsFor(Viewing viewing) {
    var names = new java.util.LinkedHashSet<String>();
    for (ViewingStatus status : nextStates(viewing.getStatus())) names.add(status.name());
    return names;
  }

  // ---------------------------------------------------------------------------
  // Reads
  // ---------------------------------------------------------------------------

  @Transactional(readOnly = true)
  public PageResponse<ViewingDtos.ViewingSummary> list(
      UUID agentId,
      UUID propertyId,
      UUID leadId,
      String status,
      LocalDateTime from,
      LocalDateTime to,
      Pageable pageable) {

    ScopeFilter scope = scopeFor(agentId);
    ViewingStatus statusFilter = status == null || status.isBlank() ? null : parseStatus(status);

    List<ViewingDtos.ViewingSummary> rows =
        viewings.findAllForDiary().stream()
            .filter(viewing -> scope.matches(agentIdOf(viewing)))
            .filter(
                viewing ->
                    propertyId == null
                        || (viewing.getProperty() != null
                            && propertyId.equals(viewing.getProperty().getId())))
            .filter(
                viewing ->
                    leadId == null
                        || (viewing.getLead() != null && leadId.equals(viewing.getLead().getId())))
            .filter(viewing -> statusFilter == null || statusFilter == viewing.getStatus())
            .filter(viewing -> within(viewing.getScheduledAt(), from, to))
            .sorted(comparator(pageable))
            .map(ViewingDtos.ViewingSummary::from)
            .toList();

    return paginate(rows, pageable);
  }

  /**
   * The agent diary: what is still ahead of the reader, soonest first.
   *
   * <p>A scoped caller sees their own diary whatever they asked for; a firm-wide caller may name an
   * agent, or none for the whole desk.</p>
   */
  @Transactional(readOnly = true)
  public List<ViewingDtos.ViewingSummary> diary(UUID agentId) {
    ScopeFilter scope = scopeFor(agentId);
    return viewings.findUpcomingAfter(LocalDateTime.now(), OPEN_STATUSES).stream()
        .filter(viewing -> scope.matches(agentIdOf(viewing)))
        .map(ViewingDtos.ViewingSummary::from)
        .toList();
  }

  /** The viewings held for one lead - its viewing history. */
  @Transactional(readOnly = true)
  public List<ViewingDtos.ViewingSummary> forLead(UUID leadId) {
    Lead lead = requireLead(leadId);
    if (!accessScope.isCompanyWide()) {
      accessScope.requireAgentOrCompanyWide(
          lead.getOwnerAgent() == null ? null : lead.getOwnerAgent().getId());
    }
    // ViewingRepository has no by-lead read, so the diary is filtered here rather than adding one.
    return viewings.findAllForDiary().stream()
        .filter(viewing -> viewing.getLead() != null && leadId.equals(viewing.getLead().getId()))
        .sorted(Comparator.comparing((Viewing v) -> v.getScheduledAt(), nullSafe()).reversed())
        .map(ViewingDtos.ViewingSummary::from)
        .toList();
  }

  /** One viewing, with the moves that are legal from where it stands. */
  @Transactional(readOnly = true)
  public ViewingDtos.ViewingDetail detail(UUID viewingId) {
    Viewing viewing = require(viewingId);
    requireVisible(viewing);

    List<ViewingDtos.ViewingStatusHistoryEntry> entries =
        history.findAllByViewingIdOrderByChangedAtDesc(viewingId).stream()
            .map(ViewingDtos.ViewingStatusHistoryEntry::from)
            .toList();

    return new ViewingDtos.ViewingDetail(
        viewing.getId(),
        viewing.getViewingCode(),
        idOf(viewing.getProperty()),
        viewing.getProperty() == null ? null : viewing.getProperty().getPropertyCode(),
        viewing.getProperty() == null ? null : viewing.getProperty().getName(),
        idOf(viewing.getUnit()),
        viewing.getUnit() == null ? null : viewing.getUnit().getUnitNumber(),
        idOf(viewing.getLead()),
        viewing.getLead() == null ? null : viewing.getLead().getDisplayName(),
        idOf(viewing.getBuyer()),
        viewing.getBuyer() == null ? null : viewing.getBuyer().getDisplayName(),
        idOf(viewing.getTenant()),
        viewing.getTenant() == null ? null : viewing.getTenant().getDisplayName(),
        viewing.getProspectName(),
        viewing.getProspectPhone(),
        viewing.getProspectEmail(),
        idOf(viewing.getAgent()),
        agentName(viewing.getAgent()),
        viewing.getScheduledAt(),
        viewing.getDurationMinutes(),
        enumName(viewing.getStatus()),
        viewing.getFeedback(),
        viewing.getRating(),
        viewing.getInterested(),
        viewing.getCancelledReason(),
        viewing.getCompletedAt(),
        viewing.getCreatedAt(),
        viewing.getUpdatedAt(),
        transitionsFor(viewing),
        entries);
  }

  // ---------------------------------------------------------------------------
  // The operations
  // ---------------------------------------------------------------------------

  /** Book a viewing. */
  @Transactional
  public ViewingDtos.ViewingDetail schedule(ViewingDtos.ScheduleViewingRequest request) {
    User actor = accessScope.requireUser();

    Property property = requireProperty(request.propertyId());
    Unit unit = request.unitId() == null ? null : requireUnit(request.unitId());
    if (unit != null && !unitBelongsTo(unit, property)) {
      throw ApiException.badRequest("The unit does not belong to the chosen property");
    }

    Lead lead = request.leadId() == null ? null : requireLead(request.leadId());
    Buyer buyer = request.buyerId() == null ? null : requireBuyer(request.buyerId());
    Tenant tenant = request.tenantId() == null ? null : requireTenant(request.tenantId());

    if (lead == null && buyer == null && tenant == null && isBlank(request.prospectName())) {
      throw ApiException.badRequest(
          "A viewing needs a prospect: link a lead, a buyer or a tenant, or give a name");
    }

    Viewing viewing = new Viewing();
    viewing.setViewingCode(numbers.next(BusinessNumberService.VIEWING));
    viewing.setProperty(property);
    viewing.setUnit(unit);
    viewing.setLead(lead);
    viewing.setBuyer(buyer);
    viewing.setTenant(tenant);
    viewing.setProspectName(trim(request.prospectName()));
    viewing.setProspectPhone(trim(request.prospectPhone()));
    viewing.setProspectEmail(lower(request.prospectEmail()));
    viewing.setAgent(resolveAgent(request.agentId(), actor));
    viewing.setScheduledAt(request.scheduledAt());
    viewing.setDurationMinutes(request.durationMinutes() == null ? 30 : request.durationMinutes());
    // A brand-new viewing is SCHEDULED with no outcome columns: the constraint's third arm.
    viewing.setStatus(ViewingStatus.SCHEDULED);
    viewing.setCompletedAt(null);
    viewing.setFeedback(null);
    viewing.setCancelledReason(null);
    viewing.setCreatedBy(actor.getId());
    viewing.setUpdatedBy(actor.getId());

    Viewing saved = viewings.save(viewing);
    recordHistory(saved, null, ViewingStatus.SCHEDULED, "Viewing scheduled", actor);
    recordAudit(actor, AuditActions.CREATE, "Scheduled viewing " + saved.getViewingCode(), saved);

    return detail(saved.getId());
  }

  /** Move a booked viewing to a new time, or change who runs it. Only while it is still open. */
  @Transactional
  public ViewingDtos.ViewingDetail reschedule(
      UUID viewingId, ViewingDtos.RescheduleViewingRequest request) {
    User actor = accessScope.requireUser();
    Viewing viewing = require(viewingId);
    requireVisible(viewing);

    if (viewing.getStatus() == null || !viewing.getStatus().isOpen()) {
      throw ApiException.rule(
          "VIEWING_NOT_COMPLETABLE",
          "Only a scheduled or confirmed viewing can be rescheduled; this one is "
              + viewing.getStatus()
              + ".");
    }

    viewing.setScheduledAt(request.scheduledAt());
    if (request.durationMinutes() != null) {
      viewing.setDurationMinutes(request.durationMinutes());
    }
    if (request.agentId() != null) {
      viewing.setAgent(requireAgent(request.agentId()));
    }
    if (request.prospectName() != null) {
      viewing.setProspectName(trim(request.prospectName()));
    }
    if (request.prospectPhone() != null) {
      viewing.setProspectPhone(trim(request.prospectPhone()));
    }
    viewing.setUpdatedBy(actor.getId());

    Viewing saved = viewings.save(viewing);
    recordAudit(actor, AuditActions.UPDATE, "Rescheduled viewing " + saved.getViewingCode(), saved);

    return detail(viewingId);
  }

  /** Confirm the appointment with the prospect. */
  @Transactional
  public ViewingDtos.ViewingDetail confirm(UUID viewingId) {
    User actor = accessScope.requireUser();
    Viewing viewing = require(viewingId);
    requireVisible(viewing);

    if (viewing.getStatus() != ViewingStatus.SCHEDULED) {
      throw ApiException.rule(
          "VIEWING_NOT_CONFIRMABLE",
          "Only a scheduled viewing can be confirmed; this one is " + viewing.getStatus() + ".");
    }

    viewing.setStatus(ViewingStatus.CONFIRMED);
    viewing.setUpdatedBy(actor.getId());
    Viewing saved = viewings.save(viewing);
    recordHistory(saved, ViewingStatus.SCHEDULED, ViewingStatus.CONFIRMED, "Confirmed", actor);
    recordAudit(actor, AuditActions.STATUS_CHANGE, "Confirmed viewing " + saved.getViewingCode(), saved);

    return detail(viewingId);
  }

  /**
   * Complete a viewing with its outcome.
   *
   * <p>The status, the completion time and the feedback are written together: any intermediate state
   * is refused by {@code ck_viewings_outcome}, so a half-applied completion must be impossible rather
   * than merely unlikely.</p>
   */
  @Transactional
  public ViewingDtos.ViewingDetail complete(
      UUID viewingId, ViewingDtos.CompleteViewingRequest request) {
    User actor = accessScope.requireUser();
    Viewing viewing = require(viewingId);
    requireVisible(viewing);

    String feedback = request == null ? null : request.feedback();
    if (isBlank(feedback)) {
      throw ApiException.rule(
          "VIEWING_NOT_COMPLETABLE",
          "A completed viewing must carry feedback: a viewing with no reaction recorded is a lost "
              + "opportunity rather than a result.");
    }
    if (viewing.getStatus() == null || !viewing.getStatus().isOpen()) {
      throw ApiException.rule(
          "VIEWING_NOT_COMPLETABLE",
          "Only a scheduled or confirmed viewing can be completed; this one is "
              + viewing.getStatus()
              + ".");
    }

    ViewingStatus from = viewing.getStatus();
    LocalDateTime completedAt =
        request.completedAt() == null ? LocalDateTime.now() : request.completedAt();

    // One write: status and every outcome column together.
    viewing.setStatus(ViewingStatus.COMPLETED);
    viewing.setCompletedAt(completedAt);
    viewing.setFeedback(feedback);
    viewing.setRating(request.rating());
    viewing.setInterested(request.interested());
    viewing.setCancelledReason(null);
    viewing.setUpdatedBy(actor.getId());

    Viewing saved = viewings.save(viewing);
    recordHistory(saved, from, ViewingStatus.COMPLETED, "Completed", actor);
    recordAudit(
        actor, AuditActions.VIEWING_COMPLETED, "Completed viewing " + saved.getViewingCode(), saved);

    return detail(viewingId);
  }

  /** Cancel the appointment, with a reason. */
  @Transactional
  public ViewingDtos.ViewingDetail cancel(UUID viewingId, ViewingDtos.CancelViewingRequest request) {
    User actor = accessScope.requireUser();
    Viewing viewing = require(viewingId);
    requireVisible(viewing);

    if (viewing.getStatus() == ViewingStatus.CANCELLED) {
      throw ApiException.rule("VIEWING_ALREADY_CANCELLED", "This viewing has already been cancelled.");
    }
    if (viewing.getStatus() == ViewingStatus.COMPLETED || viewing.getStatus() == ViewingStatus.NO_SHOW) {
      throw ApiException.rule(
          "VIEWING_NOT_COMPLETABLE",
          "A viewing that has already reached an outcome cannot be cancelled; this one is "
              + viewing.getStatus()
              + ".");
    }

    String reason = request == null ? null : request.reason();
    if (isBlank(reason)) {
      throw ApiException.badRequest("A reason is required to cancel a viewing");
    }

    ViewingStatus from = viewing.getStatus();
    // One write: status, reason and no completion time - the constraint's second arm.
    viewing.setStatus(ViewingStatus.CANCELLED);
    viewing.setCancelledReason(reason);
    viewing.setCompletedAt(null);
    viewing.setUpdatedBy(actor.getId());

    Viewing saved = viewings.save(viewing);
    recordHistory(saved, from, ViewingStatus.CANCELLED, reason, actor);
    recordAudit(actor, AuditActions.STATUS_CHANGE, "Cancelled viewing " + saved.getViewingCode(), saved);

    return detail(viewingId);
  }

  /** Close the appointment as a no-show, with a reason. */
  @Transactional
  public ViewingDtos.ViewingDetail noShow(UUID viewingId, ViewingDtos.NoShowRequest request) {
    User actor = accessScope.requireUser();
    Viewing viewing = require(viewingId);
    requireVisible(viewing);

    if (viewing.getStatus() == null || !viewing.getStatus().isOpen()) {
      throw ApiException.rule(
          "VIEWING_NOT_COMPLETABLE",
          "Only a scheduled or confirmed viewing can be closed as a no-show; this one is "
              + viewing.getStatus()
              + ".");
    }

    String reason = request == null ? null : request.reason();
    if (isBlank(reason)) {
      throw ApiException.badRequest("A reason is required to record a no-show");
    }

    ViewingStatus from = viewing.getStatus();
    // One write: status, reason and no completion time - the constraint's second arm.
    viewing.setStatus(ViewingStatus.NO_SHOW);
    viewing.setCancelledReason(reason);
    viewing.setCompletedAt(null);
    viewing.setUpdatedBy(actor.getId());

    Viewing saved = viewings.save(viewing);
    recordHistory(saved, from, ViewingStatus.NO_SHOW, reason, actor);
    recordAudit(
        actor,
        AuditActions.STATUS_CHANGE,
        "Recorded a no-show for viewing " + saved.getViewingCode(),
        saved);

    return detail(viewingId);
  }

  // ---------------------------------------------------------------------------
  // Internals
  // ---------------------------------------------------------------------------

  @Transactional(readOnly = true)
  public Viewing require(UUID viewingId) {
    return viewings
        .findByIdAndDeletedAtIsNull(viewingId)
        .orElseThrow(() -> ApiException.notFound("We could not find that viewing"));
  }

  private void requireVisible(Viewing viewing) {
    if (accessScope.isCompanyWide()) {
      return;
    }
    accessScope.requireAgentOrCompanyWide(
        viewing.getAgent() == null ? null : viewing.getAgent().getId());
  }

  private void recordHistory(
      Viewing viewing, ViewingStatus from, ViewingStatus to, String reason, User actor) {
    ViewingStatusHistory entry = new ViewingStatusHistory();
    entry.setViewing(viewing);
    entry.setFromStatus(from);
    entry.setToStatus(to);
    entry.setReason(reason);
    entry.setChangedBy(actor);
    entry.setChangedAt(LocalDateTime.now());
    history.save(entry);
  }

  private void recordAudit(User actor, String action, String description, Viewing viewing) {
    audit.record(
        actor.getId(),
        actor.getFullName(),
        AuditActions.VIEWING,
        action,
        description,
        "VIEWING",
        viewing.getId(),
        viewing.getViewingCode());
  }

  private AgentProfile resolveAgent(UUID requestedAgentId, User actor) {
    if (requestedAgentId != null) {
      return requireAgent(requestedAgentId);
    }
    // A caller with a profile runs their own viewings unless told otherwise.
    return agents.findByUserIdAndDeletedAtIsNull(actor.getId()).orElse(null);
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

  private Lead requireLead(UUID id) {
    return leads
        .findByIdAndDeletedAtIsNull(id)
        .orElseThrow(() -> ApiException.notFound("We could not find that lead"));
  }

  private Buyer requireBuyer(UUID id) {
    return buyers
        .findByIdAndDeletedAtIsNull(id)
        .orElseThrow(() -> ApiException.notFound("We could not find that buyer"));
  }

  private Tenant requireTenant(UUID id) {
    return tenants
        .findByIdAndDeletedAtIsNull(id)
        .orElseThrow(() -> ApiException.notFound("We could not find that tenant"));
  }

  private AgentProfile requireAgent(UUID id) {
    return agents
        .findByIdAndDeletedAtIsNull(id)
        .orElseThrow(() -> ApiException.notFound("We could not find that agent"));
  }

  private static boolean unitBelongsTo(Unit unit, Property property) {
    return unit.getProperty() != null
        && property != null
        && property.getId() != null
        && property.getId().equals(unit.getProperty().getId());
  }

  private static UUID idOf(Property property) {
    return property == null ? null : property.getId();
  }

  private static UUID idOf(Unit unit) {
    return unit == null ? null : unit.getId();
  }

  private static UUID idOf(Lead lead) {
    return lead == null ? null : lead.getId();
  }

  private static UUID idOf(Buyer buyer) {
    return buyer == null ? null : buyer.getId();
  }

  private static UUID idOf(Tenant tenant) {
    return tenant == null ? null : tenant.getId();
  }

  private static UUID idOf(AgentProfile agent) {
    return agent == null ? null : agent.getId();
  }

  private static UUID agentIdOf(Viewing viewing) {
    return viewing.getAgent() == null ? null : viewing.getAgent().getId();
  }

  private static String agentName(AgentProfile agent) {
    if (agent == null || agent.getUser() == null) return null;
    return agent.getUser().getFullName();
  }

  private static String enumName(Enum<?> value) {
    return value == null ? null : value.name();
  }

  private static ViewingStatus parseStatus(String status) {
    try {
      return ViewingStatus.valueOf(status.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException ex) {
      throw ApiException.badRequest("'" + status + "' is not a viewing status");
    }
  }

  private static boolean within(LocalDateTime value, LocalDateTime from, LocalDateTime to) {
    if (value == null) return from == null && to == null;
    if (from != null && value.isBefore(from)) return false;
    if (to != null && value.isAfter(to)) return false;
    return true;
  }

  private static boolean isBlank(String value) {
    return value == null || value.isBlank();
  }

  private static String trim(String value) {
    return value == null ? null : value.trim();
  }

  private static String lower(String value) {
    return value == null || value.isBlank() ? null : value.trim().toLowerCase(Locale.ROOT);
  }

  private static <T extends Comparable<? super T>> Comparator<T> nullSafe() {
    return Comparator.nullsLast(Comparator.naturalOrder());
  }

  private static Comparator<Viewing> comparator(Pageable pageable) {
    Comparator<Viewing> tieBreak =
        Comparator.comparing(Viewing::getCreatedAt, nullSafe())
            .thenComparing(Viewing::getId, nullSafe());
    Comparator<Viewing> chosen = null;
    Sort sort = pageable == null ? Sort.unsorted() : pageable.getSort();
    for (Sort.Order order : sort) {
      Comparator<Viewing> by = SORTS.get(order.getProperty());
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

  /** A record-level filter: either un-restricted, or narrowed to one agent profile. */
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
