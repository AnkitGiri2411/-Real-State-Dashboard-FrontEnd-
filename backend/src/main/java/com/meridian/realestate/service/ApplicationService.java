package com.meridian.realestate.service;

import com.meridian.realestate.domain.ApplicationScreening;
import com.meridian.realestate.domain.ApplicationStatus;
import com.meridian.realestate.domain.ApplicationStatusHistory;
import com.meridian.realestate.domain.Lead;
import com.meridian.realestate.domain.Property;
import com.meridian.realestate.domain.RentalApplication;
import com.meridian.realestate.domain.ScreeningCheckType;
import com.meridian.realestate.domain.ScreeningStatus;
import com.meridian.realestate.domain.Tenant;
import com.meridian.realestate.domain.Unit;
import com.meridian.realestate.domain.User;
import com.meridian.realestate.dto.ApplicationDtos;
import com.meridian.realestate.dto.PageResponse;
import com.meridian.realestate.exception.ApiException;
import com.meridian.realestate.repository.AgentProfileRepository;
import com.meridian.realestate.repository.ApplicationScreeningRepository;
import com.meridian.realestate.repository.ApplicationStatusHistoryRepository;
import com.meridian.realestate.repository.LeadRepository;
import com.meridian.realestate.repository.PropertyRepository;
import com.meridian.realestate.repository.RentalApplicationRepository;
import com.meridian.realestate.repository.TenantRepository;
import com.meridian.realestate.repository.UnitRepository;
import com.meridian.realestate.security.AccessScope;
import java.time.LocalDateTime;
import java.util.Comparator;
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
 * Rental applications: taking one, running the checks on it, and deciding it.
 *
 * <h2>One open application per unit</h2>
 * The schema carries a partial unique index over {@code (unit_id, applicant_email)} for the open
 * statuses. The service is deliberately <em>stricter</em>: it refuses a second open application on
 * the same unit whatever the applicant, because "who is in the running for this unit" has to have one
 * answer. The caller gets a named {@code APPLICATION_ALREADY_OPEN_ON_UNIT} (409) rather than a raw
 * constraint violation.
 *
 * <h2>Screening gates the decision</h2>
 * An application may only be approved or rejected once every screening check is finished. A check is
 * finished when it is not {@code PENDING}, and <b>{@code WAIVED} is a finished check</b>: it is an
 * explicit, attributed decision to run no check, not an absence of one. So only a {@code PENDING}
 * check blocks a decision; {@code PASSED}, {@code FAILED} and {@code WAIVED} all clear the gate. A
 * decision is refused with {@code SCREENING_INCOMPLETE} (409) while any check is still pending.
 *
 * <h2>Decisions are written whole</h2>
 * {@code ck_rental_applications_decision} refuses an approval or rejection without both a decider and
 * a decision time, and refuses a rejection without a reason. Every decision below sets those columns
 * together in one write against the loaded entity.
 */
@Service
public class ApplicationService {

  /** The statuses in which an application is undecided - the "open" set for the unit rule. */
  private static final Set<ApplicationStatus> OPEN_STATUSES =
      EnumSet.of(ApplicationStatus.SUBMITTED, ApplicationStatus.SCREENING);

  private static final Map<ApplicationStatus, Set<ApplicationStatus>> ALLOWED_STATUS_MOVES =
      Map.of(
          ApplicationStatus.SUBMITTED,
          EnumSet.of(
              ApplicationStatus.SCREENING,
              ApplicationStatus.APPROVED,
              ApplicationStatus.REJECTED,
              ApplicationStatus.WITHDRAWN),
          ApplicationStatus.SCREENING,
          EnumSet.of(
              ApplicationStatus.APPROVED, ApplicationStatus.REJECTED, ApplicationStatus.WITHDRAWN),
          ApplicationStatus.APPROVED,
          EnumSet.noneOf(ApplicationStatus.class),
          ApplicationStatus.REJECTED,
          EnumSet.noneOf(ApplicationStatus.class),
          ApplicationStatus.WITHDRAWN,
          EnumSet.noneOf(ApplicationStatus.class));

  private static final Map<String, Comparator<RentalApplication>> SORTS =
      Map.of(
          "submittedAt",
              Comparator.comparing(RentalApplication::getSubmittedAt, nullSafe()),
          "createdAt",
              Comparator.comparing(RentalApplication::getCreatedAt, nullSafe()),
          "decidedAt",
              Comparator.comparing(RentalApplication::getDecidedAt, nullSafe()),
          "status",
              Comparator.comparing((RentalApplication a) -> enumName(a.getStatus()), nullSafe()),
          "applicationCode",
              Comparator.comparing(RentalApplication::getApplicationCode, nullSafe()),
          "applicantName",
              Comparator.comparing(RentalApplication::getApplicantName, nullSafe()),
          "proposedRent",
              Comparator.comparing(RentalApplication::getProposedRent, nullSafe()));

  private final RentalApplicationRepository applications;
  private final ApplicationScreeningRepository screenings;
  private final ApplicationStatusHistoryRepository history;
  private final PropertyRepository properties;
  private final UnitRepository units;
  private final LeadRepository leads;
  private final TenantRepository tenants;
  private final AgentProfileRepository agents;
  private final BusinessNumberService numbers;
  private final AuditRecordingService audit;
  private final AccessScope accessScope;

  public ApplicationService(
      RentalApplicationRepository applications,
      ApplicationScreeningRepository screenings,
      ApplicationStatusHistoryRepository history,
      PropertyRepository properties,
      UnitRepository units,
      LeadRepository leads,
      TenantRepository tenants,
      AgentProfileRepository agents,
      BusinessNumberService numbers,
      AuditRecordingService audit,
      AccessScope accessScope) {
    this.applications = applications;
    this.screenings = screenings;
    this.history = history;
    this.properties = properties;
    this.units = units;
    this.leads = leads;
    this.tenants = tenants;
    this.agents = agents;
    this.numbers = numbers;
    this.audit = audit;
    this.accessScope = accessScope;
  }

  // ---------------------------------------------------------------------------
  // Reads
  // ---------------------------------------------------------------------------

  @Transactional(readOnly = true)
  public PageResponse<ApplicationDtos.ApplicationSummary> list(
      UUID propertyId, UUID unitId, String status, String applicant, Pageable pageable) {

    ScopeFilter scope = scopeFor();
    ApplicationStatus statusFilter = status == null || status.isBlank() ? null : parseStatus(status);
    String term = applicant == null || applicant.isBlank() ? null : applicant.trim().toLowerCase(Locale.ROOT);

    List<ApplicationDtos.ApplicationSummary> rows =
        applications.findAllForRegister().stream()
            .filter(scope::matches)
            .filter(
                application ->
                    propertyId == null
                        || (application.getProperty() != null
                            && propertyId.equals(application.getProperty().getId())))
            .filter(
                application ->
                    unitId == null
                        || (application.getUnit() != null
                            && unitId.equals(application.getUnit().getId())))
            .filter(application -> statusFilter == null || statusFilter == application.getStatus())
            .filter(application -> term == null || matchesApplicant(application, term))
            .sorted(comparator(pageable))
            .map(application -> ApplicationDtos.ApplicationSummary.from(application, outstandingChecks(application)))
            .toList();

    return paginate(rows, pageable);
  }

  /** One application, with its screenings, history and the moves that are legal from where it stands. */
  @Transactional(readOnly = true)
  public ApplicationDtos.ApplicationDetail detail(UUID applicationId) {
    RentalApplication application = require(applicationId);
    requireVisible(application);
    return detailOf(application);
  }

  private ApplicationDtos.ApplicationDetail detailOf(RentalApplication application) {
    List<ApplicationDtos.ScreeningEntry> screeningRows =
        screenings.findAllByApplicationIdAndDeletedAtIsNull(application.getId()).stream()
            .map(ApplicationDtos.ScreeningEntry::from)
            .toList();

    List<ApplicationDtos.ApplicationStatusHistoryEntry> historyRows =
        history.findAllByApplicationIdOrderByChangedAtDesc(application.getId()).stream()
            .map(ApplicationDtos.ApplicationStatusHistoryEntry::from)
            .toList();

    return new ApplicationDtos.ApplicationDetail(
        application.getId(),
        application.getApplicationCode(),
        idOf(application.getProperty()),
        application.getProperty() == null ? null : application.getProperty().getPropertyCode(),
        application.getProperty() == null ? null : application.getProperty().getName(),
        idOf(application.getUnit()),
        application.getUnit() == null ? null : application.getUnit().getUnitNumber(),
        idOf(application.getLead()),
        application.getLead() == null ? null : application.getLead().getDisplayName(),
        idOf(application.getTenant()),
        enumName(application.getApplicantType()),
        application.getApplicantName(),
        application.getApplicantEmail(),
        application.getApplicantPhone(),
        enumName(application.getStatus()),
        application.getProposedStartDate(),
        application.getProposedEndDate(),
        application.getProposedRent(),
        application.getProposedDeposit(),
        application.getOccupancyCount(),
        application.isHasPets(),
        enumName(application.getEmploymentType()),
        application.getAnnualIncome(),
        application.getNotes(),
        application.getSubmittedAt(),
        application.getDecidedAt(),
        idOf(application.getDecidedBy()),
        application.getDecisionNotes(),
        application.getRejectionReason(),
        application.getCreatedAt(),
        application.getUpdatedAt(),
        outstandingChecks(application),
        transitionsFor(application.getStatus()),
        screeningRows,
        historyRows);
  }

  // ---------------------------------------------------------------------------
  // The operations
  // ---------------------------------------------------------------------------

  /** Take an application for a unit. Refuses a second open application on the same unit. */
  @Transactional
  public ApplicationDtos.ApplicationDetail create(ApplicationDtos.CreateApplicationRequest request) {
    User actor = accessScope.requireUser();

    Property property = requireProperty(request.propertyId());
    Unit unit = requireUnit(request.unitId());
    if (!unitBelongsTo(unit, property)) {
      throw ApiException.badRequest("The unit does not belong to the chosen property");
    }

    // One open application per unit; see the class note on why this is stricter than the index.
    List<RentalApplication> open =
        applications.findAllByUnitIdAndStatusInAndDeletedAtIsNull(unit.getId(), OPEN_STATUSES);
    if (!open.isEmpty()) {
      throw ApiException.rule(
          "APPLICATION_ALREADY_OPEN_ON_UNIT",
          "Unit "
              + unit.getUnitNumber()
              + " already has an open application ("
              + open.get(0).getApplicationCode()
              + "). Decide or withdraw that one before taking another.");
    }

    Lead lead = request.leadId() == null ? null : requireLead(request.leadId());
    Tenant tenant = request.tenantId() == null ? null : requireTenant(request.tenantId());

    RentalApplication application = new RentalApplication();
    application.setApplicationCode(numbers.next(BusinessNumberService.APPLICATION));
    application.setProperty(property);
    application.setUnit(unit);
    application.setLead(lead);
    application.setTenant(tenant);
    application.setApplicantType(parseApplicantType(request.applicantType()));
    application.setApplicantName(request.applicantName().trim());
    application.setApplicantEmail(lower(request.applicantEmail()));
    application.setApplicantPhone(trim(request.applicantPhone()));
    application.setStatus(ApplicationStatus.SUBMITTED);
    application.setProposedStartDate(request.proposedStartDate());
    application.setProposedEndDate(request.proposedEndDate());
    application.setProposedRent(request.proposedRent());
    application.setProposedDeposit(request.proposedDeposit());
    application.setOccupancyCount(request.occupancyCount());
    application.setHasPets(Boolean.TRUE.equals(request.hasPets()));
    application.setEmploymentType(parseEmploymentType(request.employmentType()));
    application.setAnnualIncome(request.annualIncome());
    application.setNotes(request.notes());
    application.setSubmittedAt(LocalDateTime.now());
    // The decision columns stay null: an undecided application must carry no decision time.
    application.setDecidedAt(null);
    application.setDecidedBy(null);
    application.setRejectionReason(null);
    application.setCreatedBy(actor.getId());
    application.setUpdatedBy(actor.getId());

    RentalApplication saved = applications.save(application);
    recordHistory(saved, null, ApplicationStatus.SUBMITTED, "Application taken", actor);
    recordAudit(actor, AuditActions.CREATE, "Took application " + saved.getApplicationCode(), saved);

    return detailOf(saved);
  }

  /** Move an application into screening. */
  @Transactional
  public ApplicationDtos.ApplicationDetail submitForScreening(
      UUID applicationId, ApplicationDtos.SubmitForScreeningRequest request) {
    User actor = accessScope.requireUser();
    RentalApplication application = require(applicationId);
    requireVisible(application);

    if (application.getStatus() != ApplicationStatus.SUBMITTED) {
      throw ApiException.rule(
          "APPLICATION_NOT_SCREENABLE",
          "Only a submitted application can move into screening; this one is "
              + application.getStatus()
              + ".");
    }

    ApplicationStatus from = application.getStatus();
    application.setStatus(ApplicationStatus.SCREENING);
    application.setUpdatedBy(actor.getId());
    RentalApplication saved = applications.save(application);
    recordHistory(
        saved,
        from,
        ApplicationStatus.SCREENING,
        request == null ? null : request.reason(),
        actor);
    recordAudit(
        actor,
        AuditActions.STATUS_CHANGE,
        "Moved application " + saved.getApplicationCode() + " into screening",
        saved);

    return detailOf(saved);
  }

  /**
   * Record the result of one screening check.
   *
   * <p>One row per check type per application: a second result for the same check is a correction and
   * updates the row rather than adding one, mirroring {@code uq_application_screenings_check}.</p>
   */
  @Transactional
  public ApplicationDtos.ApplicationDetail recordScreening(
      UUID applicationId, ApplicationDtos.RecordScreeningRequest request) {
    User actor = accessScope.requireUser();
    RentalApplication application = require(applicationId);
    requireVisible(application);

    if (!application.getStatus().isOpen()) {
      throw ApiException.rule(
          "APPLICATION_NOT_DECIDABLE",
          "This application has already been decided and its screening cannot be changed.");
    }

    ScreeningCheckType checkType = parseCheckType(request.checkType());
    ScreeningStatus status = parseScreeningStatus(request.status());

    ApplicationScreening screening =
        screenings.findAllByApplicationIdAndDeletedAtIsNull(applicationId).stream()
            .filter(row -> row.getCheckType() == checkType)
            .findFirst()
            .orElseGet(ApplicationScreening::new);

    screening.setApplication(application);
    screening.setCheckType(checkType);
    screening.setStatus(status);
    screening.setScore(request.score());
    screening.setNotes(request.notes());
    screening.setEvidenceReference(request.evidenceReference());
    // PENDING carries neither performer nor time; anything else carries both, WAIVED included.
    if (status == ScreeningStatus.PENDING) {
      screening.setPerformedAt(null);
      screening.setPerformedBy(null);
    } else {
      screening.setPerformedAt(LocalDateTime.now());
      screening.setPerformedBy(actor);
    }
    if (screening.getId() == null) {
      screening.setCreatedBy(actor.getId());
    }
    screening.setUpdatedBy(actor.getId());
    screenings.save(screening);

    // Recording the first check moves a merely-submitted application into screening.
    if (application.getStatus() == ApplicationStatus.SUBMITTED) {
      application.setStatus(ApplicationStatus.SCREENING);
      application.setUpdatedBy(actor.getId());
      RentalApplication saved = applications.save(application);
      recordHistory(saved, ApplicationStatus.SUBMITTED, ApplicationStatus.SCREENING, "Screening began", actor);
    }

    recordAudit(
        actor,
        AuditActions.UPDATE,
        "Recorded " + checkType + " screening (" + status + ") on " + application.getApplicationCode(),
        application);

    return detailOf(application);
  }

  /** Approve an application, once every screening check is finished. */
  @Transactional
  public ApplicationDtos.ApplicationDetail approve(UUID applicationId, ApplicationDtos.ApproveRequest request) {
    User actor = accessScope.requireUser();
    RentalApplication application = require(applicationId);
    requireVisible(application);

    assertDecidable(application);
    assertScreeningComplete(application);

    ApplicationStatus from = application.getStatus();
    // The decision is written whole: status, decider and decision time together.
    application.setStatus(ApplicationStatus.APPROVED);
    application.setDecidedAt(LocalDateTime.now());
    application.setDecidedBy(actor);
    application.setDecisionNotes(request == null ? null : request.decisionNotes());
    application.setRejectionReason(null);
    application.setUpdatedBy(actor.getId());
    RentalApplication saved = applications.save(application);

    recordHistory(saved, from, ApplicationStatus.APPROVED, "Approved", actor);
    recordAudit(actor, AuditActions.APPLICATION_DECIDED, "Approved application " + saved.getApplicationCode(), saved);

    return detailOf(saved);
  }

  /** Reject an application, once every screening check is finished. */
  @Transactional
  public ApplicationDtos.ApplicationDetail reject(UUID applicationId, ApplicationDtos.RejectRequest request) {
    User actor = accessScope.requireUser();
    RentalApplication application = require(applicationId);
    requireVisible(application);

    String reason = request == null ? null : request.reason();
    if (isBlank(reason)) {
      throw ApiException.badRequest("A reason is required to reject an application");
    }

    assertDecidable(application);
    assertScreeningComplete(application);

    ApplicationStatus from = application.getStatus();
    // The decision is written whole: a rejection carries a reason as well as a decider and a time.
    application.setStatus(ApplicationStatus.REJECTED);
    application.setDecidedAt(LocalDateTime.now());
    application.setDecidedBy(actor);
    application.setRejectionReason(reason);
    application.setDecisionNotes(request.decisionNotes());
    application.setUpdatedBy(actor.getId());
    RentalApplication saved = applications.save(application);

    recordHistory(saved, from, ApplicationStatus.REJECTED, reason, actor);
    recordAudit(actor, AuditActions.APPLICATION_DECIDED, "Rejected application " + saved.getApplicationCode(), saved);

    return detailOf(saved);
  }

  /** Withdraw an application before a decision is taken. */
  @Transactional
  public ApplicationDtos.ApplicationDetail withdraw(
      UUID applicationId, ApplicationDtos.WithdrawRequest request) {
    User actor = accessScope.requireUser();
    RentalApplication application = require(applicationId);
    requireVisible(application);

    assertDecidable(application);

    ApplicationStatus from = application.getStatus();
    // Withdrawn is undecided: the decision columns must stay null for the constraint's third arm.
    application.setStatus(ApplicationStatus.WITHDRAWN);
    application.setUpdatedBy(actor.getId());
    RentalApplication saved = applications.save(application);

    recordHistory(saved, from, ApplicationStatus.WITHDRAWN, request == null ? null : request.reason(), actor);
    recordAudit(actor, AuditActions.STATUS_CHANGE, "Withdrew application " + saved.getApplicationCode(), saved);

    return detailOf(saved);
  }

  // ---------------------------------------------------------------------------
  // Internals
  // ---------------------------------------------------------------------------

  @Transactional(readOnly = true)
  public RentalApplication require(UUID applicationId) {
    return applications
        .findByIdAndDeletedAtIsNull(applicationId)
        .orElseThrow(() -> ApiException.notFound("We could not find that application"));
  }

  /**
   * The count of screening checks still to be resolved.
   *
   * <p>Only {@code PENDING} counts. A {@code WAIVED} check is a finished check - an explicit
   * decision - so it does not block a decision.</p>
   */
  private long outstandingChecks(RentalApplication application) {
    return screenings.findAllByApplicationIdAndDeletedAtIsNull(application.getId()).stream()
        .filter(row -> row.getStatus() == ScreeningStatus.PENDING)
        .count();
  }

  private void assertDecidable(RentalApplication application) {
    if (application.getStatus() == null || !application.getStatus().isOpen()) {
      throw ApiException.rule(
          "APPLICATION_NOT_DECIDABLE",
          "This application has already been decided; it is " + application.getStatus() + ".");
    }
  }

  private void assertScreeningComplete(RentalApplication application) {
    long outstanding = outstandingChecks(application);
    if (outstanding > 0) {
      throw ApiException.rule(
          "SCREENING_INCOMPLETE",
          outstanding
              + " screening check(s) are still pending. Every check must be finished before an "
              + "application can be decided.");
    }
  }

  private void requireVisible(RentalApplication application) {
    if (accessScope.isCompanyWide()) {
      return;
    }
    Lead lead = application.getLead();
    if (lead == null) {
      return;
    }
    accessScope.requireAgentOrCompanyWide(
        lead.getOwnerAgent() == null ? null : lead.getOwnerAgent().getId());
  }

  private void recordHistory(
      RentalApplication application,
      ApplicationStatus from,
      ApplicationStatus to,
      String reason,
      User actor) {
    ApplicationStatusHistory entry = new ApplicationStatusHistory();
    entry.setApplication(application);
    entry.setFromStatus(from);
    entry.setToStatus(to);
    entry.setReason(reason);
    entry.setChangedBy(actor);
    entry.setChangedAt(LocalDateTime.now());
    history.save(entry);
  }

  private void recordAudit(User actor, String action, String description, RentalApplication application) {
    audit.record(
        actor.getId(),
        actor.getFullName(),
        AuditActions.APPLICATION,
        action,
        description,
        "APPLICATION",
        application.getId(),
        application.getApplicationCode());
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

  private Tenant requireTenant(UUID id) {
    return tenants
        .findByIdAndDeletedAtIsNull(id)
        .orElseThrow(() -> ApiException.notFound("We could not find that tenant"));
  }

  private static boolean matchesApplicant(RentalApplication application, String term) {
    return contains(application.getApplicantName(), term)
        || contains(application.getApplicantEmail(), term)
        || contains(application.getApplicantPhone(), term)
        || contains(application.getApplicationCode(), term);
  }

  private static boolean contains(String value, String term) {
    return value != null && value.toLowerCase(Locale.ROOT).contains(term);
  }

  private static boolean unitBelongsTo(Unit unit, Property property) {
    return unit.getProperty() != null
        && property != null
        && property.getId() != null
        && property.getId().equals(unit.getProperty().getId());
  }

  private static Set<String> transitionsFor(ApplicationStatus status) {
    Set<String> names = new LinkedHashSet<>();
    for (ApplicationStatus next : ALLOWED_STATUS_MOVES.getOrDefault(status, Set.of())) {
      names.add(next.name());
    }
    return names;
  }

  private static ApplicationStatus parseStatus(String status) {
    try {
      return ApplicationStatus.valueOf(status.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException ex) {
      throw ApiException.badRequest("'" + status + "' is not an application status");
    }
  }

  private static ScreeningStatus parseScreeningStatus(String status) {
    try {
      return ScreeningStatus.valueOf(status.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException ex) {
      throw ApiException.badRequest("'" + status + "' is not a screening status");
    }
  }

  private static ScreeningCheckType parseCheckType(String checkType) {
    try {
      return ScreeningCheckType.valueOf(checkType.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException ex) {
      throw ApiException.badRequest("'" + checkType + "' is not a screening check type");
    }
  }

  private static com.meridian.realestate.domain.ApplicantType parseApplicantType(String applicantType) {
    if (isBlank(applicantType)) return com.meridian.realestate.domain.ApplicantType.INDIVIDUAL;
    try {
      return com.meridian.realestate.domain.ApplicantType.valueOf(applicantType.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException ex) {
      throw ApiException.badRequest("'" + applicantType + "' is not an applicant type");
    }
  }

  private static com.meridian.realestate.domain.EmploymentType parseEmploymentType(String employmentType) {
    if (isBlank(employmentType)) return null;
    try {
      return com.meridian.realestate.domain.EmploymentType.valueOf(employmentType.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException ex) {
      throw ApiException.badRequest("'" + employmentType + "' is not an employment type");
    }
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

  private static UUID idOf(Tenant tenant) {
    return tenant == null ? null : tenant.getId();
  }

  private static UUID idOf(User user) {
    return user == null ? null : user.getId();
  }

  private static String enumName(Enum<?> value) {
    return value == null ? null : value.name();
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

  private static Comparator<RentalApplication> comparator(Pageable pageable) {
    Comparator<RentalApplication> tieBreak =
        Comparator.comparing(RentalApplication::getCreatedAt, nullSafe())
            .thenComparing(RentalApplication::getId, nullSafe());
    Comparator<RentalApplication> chosen = null;
    Sort sort = pageable == null ? Sort.unsorted() : pageable.getSort();
    for (Sort.Order order : sort) {
      Comparator<RentalApplication> by = SORTS.get(order.getProperty());
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

  /**
   * A record-level filter for applications.
   *
   * <p>An application is owned through the lead it grew out of. A scoped agent sees an application
   * whose lead they hold, and one that has no lead or an unassigned lead - an application taken at
   * the desk belongs to the desk until it is assigned.</p>
   */
  private record ScopeFilter(boolean restricted, UUID agentProfileId) {
    boolean matches(RentalApplication application) {
      if (!restricted) return true;
      Lead lead = application.getLead();
      if (lead == null) return true;
      if (lead.getOwnerAgent() == null) return true;
      return lead.getOwnerAgent().getId().equals(agentProfileId);
    }
  }

  private ScopeFilter scopeFor() {
    if (accessScope.isCompanyWide()) {
      return new ScopeFilter(false, null);
    }
    return new ScopeFilter(true, ownAgentProfileId());
  }

  private UUID ownAgentProfileId() {
    // A scoped caller with no agent profile holds no book of work, so the filter matches nothing.
    return agents
        .findByUserIdAndDeletedAtIsNull(accessScope.requireUserId())
        .map(com.meridian.realestate.domain.AgentProfile::getId)
        .orElse(null);
  }
}
