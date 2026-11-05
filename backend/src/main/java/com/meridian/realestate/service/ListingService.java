package com.meridian.realestate.service;

import com.meridian.realestate.config.AppProperties;
import com.meridian.realestate.domain.AgentProfile;
import com.meridian.realestate.domain.Listing;
import com.meridian.realestate.domain.ListingChannel;
import com.meridian.realestate.domain.ListingStatus;
import com.meridian.realestate.domain.ListingStatusHistory;
import com.meridian.realestate.domain.ListingType;
import com.meridian.realestate.domain.Office;
import com.meridian.realestate.domain.Property;
import com.meridian.realestate.domain.Unit;
import com.meridian.realestate.domain.User;
import com.meridian.realestate.domain.Viewing;
import com.meridian.realestate.dto.ApplicationDtos;
import com.meridian.realestate.dto.ListingDtos;
import com.meridian.realestate.dto.PageResponse;
import com.meridian.realestate.dto.ViewingDtos;
import com.meridian.realestate.exception.ApiException;
import com.meridian.realestate.repository.AgentProfileRepository;
import com.meridian.realestate.repository.ListingChannelRepository;
import com.meridian.realestate.repository.ListingRepository;
import com.meridian.realestate.repository.ListingStatusHistoryRepository;
import com.meridian.realestate.repository.OfficeRepository;
import com.meridian.realestate.repository.PropertyRepository;
import com.meridian.realestate.repository.RentalApplicationRepository;
import com.meridian.realestate.repository.UnitRepository;
import com.meridian.realestate.repository.ViewingRepository;
import com.meridian.realestate.security.AccessScope;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
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
 * The listing board, and everything that moves a listing.
 *
 * <h2>Status changes are operations, not edits</h2>
 * There is no method here that writes a publication state from a request field. A listing moves only
 * through publish, unpublish, expire or {@link #changeStatus}, each validating the move against
 * {@link #ALLOWED}. Publishing a listing that is already live on the board is refused with
 * {@code LISTING_ALREADY_ACTIVE} (409), as is publishing a second live listing on the same target.
 *
 * <h2>Every change is history</h2>
 * Every status change writes a {@code listing_status_history} row, and writes it <em>after</em> the
 * guard has passed - so a refused move leaves no history row behind. That ordering is what keeps a
 * refused publish from looking, in the trail, as though it had happened.
 *
 * <h2>Stale listings</h2>
 * A listing is stale when it is still live but has been on the market longer than the installation's
 * configured window ({@code app.portfolio.listing-stale-days}). The flag is derived, not stored,
 * because the threshold is policy.
 */
@Service
public class ListingService {

  /** The permitted publication-state moves. */
  private static final Map<ListingStatus, Set<ListingStatus>> ALLOWED =
      new EnumMap<>(ListingStatus.class);

  static {
    ALLOWED.put(
        ListingStatus.DRAFT,
        EnumSet.of(ListingStatus.PUBLISHED, ListingStatus.ACTIVE, ListingStatus.WITHDRAWN));
    ALLOWED.put(
        ListingStatus.PUBLISHED,
        EnumSet.of(
            ListingStatus.ACTIVE,
            ListingStatus.UNDER_OFFER,
            ListingStatus.WITHDRAWN,
            ListingStatus.EXPIRED));
    ALLOWED.put(
        ListingStatus.ACTIVE,
        EnumSet.of(
            ListingStatus.UNDER_OFFER,
            ListingStatus.LET_AGREED,
            ListingStatus.SOLD,
            ListingStatus.WITHDRAWN,
            ListingStatus.EXPIRED));
    ALLOWED.put(
        ListingStatus.UNDER_OFFER,
        EnumSet.of(
            ListingStatus.ACTIVE,
            ListingStatus.LET_AGREED,
            ListingStatus.SOLD,
            ListingStatus.WITHDRAWN,
            ListingStatus.EXPIRED));
    // Terminal: a closed listing is not re-opened.
    ALLOWED.put(ListingStatus.LET_AGREED, EnumSet.noneOf(ListingStatus.class));
    ALLOWED.put(ListingStatus.SOLD, EnumSet.noneOf(ListingStatus.class));
    ALLOWED.put(ListingStatus.WITHDRAWN, EnumSet.noneOf(ListingStatus.class));
    ALLOWED.put(ListingStatus.EXPIRED, EnumSet.noneOf(ListingStatus.class));
  }

  private static final Map<String, Comparator<Listing>> SORTS =
      Map.ofEntries(
          Map.entry("createdAt", Comparator.comparing(Listing::getCreatedAt, nullSafe())),
          Map.entry("updatedAt", Comparator.comparing(Listing::getUpdatedAt, nullSafe())),
          Map.entry("listingCode", Comparator.comparing(Listing::getListingCode, nullSafe())),
          Map.entry("headline", Comparator.comparing(Listing::getHeadline, nullSafe())),
          Map.entry("status", Comparator.comparing((Listing l) -> enumName(l.getStatus()), nullSafe())),
          Map.entry(
              "listingType", Comparator.comparing((Listing l) -> enumName(l.getListingType()), nullSafe())),
          Map.entry("listingStart", Comparator.comparing(Listing::getListingStart, nullSafe())),
          Map.entry("askingRent", Comparator.comparing(Listing::getAskingRent, nullSafe())),
          Map.entry("askingPrice", Comparator.comparing(Listing::getAskingPrice, nullSafe())),
          Map.entry(
              "property.name",
              Comparator.comparing(
                  (Listing l) -> l.getProperty() == null ? null : l.getProperty().getName(),
                  nullSafe())),
          Map.entry(
              "unit.unitNumber",
              Comparator.comparing(
                  (Listing l) -> l.getUnit() == null ? null : l.getUnit().getUnitNumber(),
                  nullSafe())),
          Map.entry(
              "listedBy.agentCode",
              Comparator.comparing(
                  (Listing l) -> l.getListedBy() == null ? null : l.getListedBy().getAgentCode(),
                  nullSafe())),
          Map.entry(
              "channel.name",
              Comparator.comparing(
                  (Listing l) -> l.getChannel() == null ? null : l.getChannel().getName(),
                  nullSafe())));

  private final ListingRepository listings;
  private final ListingStatusHistoryRepository history;
  private final ListingChannelRepository channels;
  private final PropertyRepository properties;
  private final UnitRepository units;
  private final OfficeRepository offices;
  private final AgentProfileRepository agents;
  private final ViewingRepository viewings;
  private final RentalApplicationRepository applications;
  private final BusinessNumberService numbers;
  private final AuditRecordingService audit;
  private final AccessScope accessScope;
  private final AppProperties appProperties;

  public ListingService(
      ListingRepository listings,
      ListingStatusHistoryRepository history,
      ListingChannelRepository channels,
      PropertyRepository properties,
      UnitRepository units,
      OfficeRepository offices,
      AgentProfileRepository agents,
      ViewingRepository viewings,
      RentalApplicationRepository applications,
      BusinessNumberService numbers,
      AuditRecordingService audit,
      AccessScope accessScope,
      AppProperties appProperties) {
    this.listings = listings;
    this.history = history;
    this.channels = channels;
    this.properties = properties;
    this.units = units;
    this.offices = offices;
    this.agents = agents;
    this.viewings = viewings;
    this.applications = applications;
    this.numbers = numbers;
    this.audit = audit;
    this.accessScope = accessScope;
    this.appProperties = appProperties;
  }

  // ---------------------------------------------------------------------------
  // The lifecycle, exposed so a screen can offer only the moves that will succeed
  // ---------------------------------------------------------------------------

  public static boolean canTransition(ListingStatus from, ListingStatus to) {
    if (from == null || to == null || from == to) return false;
    return ALLOWED.getOrDefault(from, Set.of()).contains(to);
  }

  public static Set<ListingStatus> nextStates(ListingStatus from) {
    return ALLOWED.getOrDefault(from, Set.of());
  }

  private static Set<String> transitionsFor(Listing listing) {
    Set<String> names = new LinkedHashSet<>();
    for (ListingStatus next : nextStates(listing.getStatus())) names.add(next.name());
    return names;
  }

  // ---------------------------------------------------------------------------
  // Reads
  // ---------------------------------------------------------------------------

  @Transactional(readOnly = true)
  public PageResponse<ListingDtos.ListingSummary> list(
      UUID propertyId,
      UUID unitId,
      String status,
      String type,
      String channel,
      UUID agentId,
      Boolean staleOnly,
      String search,
      Pageable pageable) {

    ScopeFilter scope = scopeFor(agentId);
    ListingStatus statusFilter = status == null || status.isBlank() ? null : parseStatus(status);
    ListingType typeFilter = type == null || type.isBlank() ? null : parseType(type);
    String channelFilter = channel == null || channel.isBlank() ? null : channel.trim();
    String term = search == null || search.isBlank() ? null : search.trim().toLowerCase(Locale.ROOT);
    boolean wantStale = Boolean.TRUE.equals(staleOnly);
    LocalDate today = LocalDate.now();

    List<ListingDtos.ListingSummary> rows =
        listings.findAllForRegister().stream()
            .filter(listing -> scope.matches(agentIdOf(listing)))
            .filter(
                listing ->
                    propertyId == null
                        || (listing.getProperty() != null
                            && propertyId.equals(listing.getProperty().getId())))
            .filter(
                listing ->
                    unitId == null
                        || (listing.getUnit() != null && unitId.equals(listing.getUnit().getId())))
            .filter(listing -> statusFilter == null || statusFilter == listing.getStatus())
            .filter(listing -> typeFilter == null || typeFilter == listing.getListingType())
            .filter(
                listing ->
                    channelFilter == null
                        || (listing.getChannel() != null
                            && listing.getChannel().getCode() != null
                            && listing.getChannel().getCode().equalsIgnoreCase(channelFilter)))
            .filter(listing -> !wantStale || isStale(listing, today))
            .filter(listing -> term == null || matchesSearch(listing, term))
            .sorted(comparator(pageable))
            .map(listing -> ListingDtos.ListingSummary.from(listing, isStale(listing, today)))
            .toList();

    return paginate(rows, pageable);
  }

  /** One listing, with its status trail, its viewings and its applications. */
  @Transactional(readOnly = true)
  public ListingDtos.ListingDetail detail(UUID listingId) {
    return detailOf(require(listingId));
  }

  private ListingDtos.ListingDetail detailOf(Listing listing) {
    requireVisible(listing);
    UUID listingId = listing.getId();
    UUID propertyId = listing.getProperty() == null ? null : listing.getProperty().getId();
    UUID unitId = listing.getUnit() == null ? null : listing.getUnit().getId();

    List<ListingDtos.ListingStatusHistoryEntry> historyRows =
        history.findAllByListingIdOrderByChangedAtDesc(listingId).stream()
            .map(ListingDtos.ListingStatusHistoryEntry::from)
            .toList();

    List<ViewingDtos.ViewingSummary> viewingRows =
        viewings.findAllForDiary().stream()
            .filter(v -> v.getProperty() != null && propertyId != null && propertyId.equals(v.getProperty().getId()))
            .filter(v -> unitId == null || (v.getUnit() != null && unitId.equals(v.getUnit().getId())))
            .map(ViewingDtos.ViewingSummary::from)
            .toList();

    List<ApplicationDtos.ApplicationSummary> applicationRows =
        applications.findAllForRegister().stream()
            .filter(
                a ->
                    propertyId != null
                        && a.getProperty() != null
                        && propertyId.equals(a.getProperty().getId()))
            .filter(a -> unitId == null || (a.getUnit() != null && unitId.equals(a.getUnit().getId())))
            .map(a -> ApplicationDtos.ApplicationSummary.from(a, 0))
            .toList();

    return new ListingDtos.ListingDetail(
        listing.getId(),
        listing.getListingCode(),
        idOf(listing.getProperty()),
        listing.getProperty() == null ? null : listing.getProperty().getPropertyCode(),
        listing.getProperty() == null ? null : listing.getProperty().getName(),
        idOf(listing.getUnit()),
        listing.getUnit() == null ? null : listing.getUnit().getUnitNumber(),
        enumName(listing.getListingType()),
        listing.getChannel() == null ? null : listing.getChannel().getCode(),
        listing.getChannel() == null ? null : listing.getChannel().getName(),
        enumName(listing.getStatus()),
        listing.getHeadline(),
        listing.getDescription(),
        listing.getAskingRent(),
        listing.getAskingPrice(),
        listing.isExclusive(),
        listing.isNegotiable(),
        listing.getListingStart(),
        listing.getListingEnd(),
        listing.getPublishedAt(),
        listing.getClosedAt(),
        listing.getClosedReason(),
        idOf(listing.getListedBy()),
        agentName(listing.getListedBy()),
        idOf(listing.getOffice()),
        isStale(listing, LocalDate.now()),
        listing.getCreatedAt(),
        listing.getUpdatedAt(),
        transitionsFor(listing),
        historyRows,
        viewingRows,
        applicationRows);
  }

  /** The publication-state trail of one listing. */
  @Transactional(readOnly = true)
  public List<ListingDtos.ListingStatusHistoryEntry> statusHistory(UUID listingId) {
    requireVisible(require(listingId));
    return history.findAllByListingIdOrderByChangedAtDesc(listingId).stream()
        .map(ListingDtos.ListingStatusHistoryEntry::from)
        .toList();
  }

  /** The viewings booked on the stock this listing offers. */
  @Transactional(readOnly = true)
  public List<ViewingDtos.ViewingSummary> viewingsFor(UUID listingId) {
    Listing listing = require(listingId);
    requireVisible(listing);
    UUID propertyId = listing.getProperty() == null ? null : listing.getProperty().getId();
    UUID unitId = listing.getUnit() == null ? null : listing.getUnit().getId();
    return viewings.findAllForDiary().stream()
        .filter(v -> v.getProperty() != null && propertyId != null && propertyId.equals(v.getProperty().getId()))
        .filter(v -> unitId == null || (v.getUnit() != null && unitId.equals(v.getUnit().getId())))
        .sorted(Comparator.comparing((Viewing v) -> v.getScheduledAt(), nullSafe()).reversed())
        .map(ViewingDtos.ViewingSummary::from)
        .toList();
  }

  /** The applications taken on the stock this listing offers. */
  @Transactional(readOnly = true)
  public List<ApplicationDtos.ApplicationSummary> applicationsFor(UUID listingId) {
    Listing listing = require(listingId);
    requireVisible(listing);
    UUID propertyId = listing.getProperty() == null ? null : listing.getProperty().getId();
    UUID unitId = listing.getUnit() == null ? null : listing.getUnit().getId();
    return applications.findAllForRegister().stream()
        .filter(a -> propertyId != null && a.getProperty() != null && propertyId.equals(a.getProperty().getId()))
        .filter(a -> unitId == null || (a.getUnit() != null && unitId.equals(a.getUnit().getId())))
        .map(a -> ApplicationDtos.ApplicationSummary.from(a, 0))
        .toList();
  }

  // ---------------------------------------------------------------------------
  // The operations
  // ---------------------------------------------------------------------------

  /** Create a listing. It starts as a DRAFT; publishing is a separate, guarded act. */
  @Transactional
  public ListingDtos.ListingDetail create(ListingDtos.CreateListingRequest request) {
    User actor = accessScope.requireUser();

    Property property = requireProperty(request.propertyId());
    Unit unit = request.unitId() == null ? null : requireUnit(request.unitId());
    if (unit != null && !unitBelongsTo(unit, property)) {
      throw ApiException.badRequest("The unit does not belong to the chosen property");
    }

    ListingType type = parseType(request.listingType());
    assertTermsPresent(type, request.askingRent(), request.askingPrice());

    LocalDate start = request.listingStart() == null ? LocalDate.now() : request.listingStart();
    assertPeriod(start, request.listingEnd());

    Listing listing = new Listing();
    listing.setListingCode(numbers.next(BusinessNumberService.LISTING));
    listing.setProperty(property);
    listing.setUnit(unit);
    listing.setListingType(type);
    listing.setChannel(
        request.channelCode() == null || request.channelCode().isBlank()
            ? null
            : requireChannel(request.channelCode()));
    // A new listing is a DRAFT: it has no published or closed moment yet.
    listing.setStatus(ListingStatus.DRAFT);
    listing.setPublishedAt(null);
    listing.setClosedAt(null);
    listing.setClosedReason(null);
    listing.setHeadline(request.headline().trim());
    listing.setDescription(request.description());
    listing.setAskingRent(request.askingRent());
    listing.setAskingPrice(request.askingPrice());
    listing.setExclusive(Boolean.TRUE.equals(request.exclusive()));
    listing.setNegotiable(request.negotiable() == null || Boolean.TRUE.equals(request.negotiable()));
    listing.setListingStart(start);
    listing.setListingEnd(request.listingEnd());
    listing.setListedBy(resolveAgent(request.listedById(), actor));
    listing.setOffice(request.officeId() == null ? null : requireOffice(request.officeId()));
    listing.setCreatedBy(actor.getId());
    listing.setUpdatedBy(actor.getId());

    Listing saved = listings.save(listing);
    recordAudit(actor, AuditActions.CREATE, "Drafted listing " + saved.getListingCode(), saved);
    return detailOf(saved);
  }

  /** Change a listing's details. A null field is left unchanged; the state has its own endpoints. */
  @Transactional
  public ListingDtos.ListingDetail update(UUID listingId, ListingDtos.UpdateListingRequest request) {
    User actor = accessScope.requireUser();
    Listing listing = require(listingId);
    requireVisible(listing);

    if (request.channelCode() != null) {
      listing.setChannel(
          request.channelCode().isBlank() ? null : requireChannel(request.channelCode()));
    }
    if (request.listingType() != null) listing.setListingType(parseType(request.listingType()));
    if (request.headline() != null && !request.headline().isBlank()) {
      listing.setHeadline(request.headline().trim());
    }
    if (request.description() != null) listing.setDescription(request.description());
    if (request.askingRent() != null) listing.setAskingRent(request.askingRent());
    if (request.askingPrice() != null) listing.setAskingPrice(request.askingPrice());
    if (request.exclusive() != null) listing.setExclusive(request.exclusive());
    if (request.negotiable() != null) listing.setNegotiable(request.negotiable());
    if (request.listingStart() != null) listing.setListingStart(request.listingStart());
    if (request.listingEnd() != null) listing.setListingEnd(request.listingEnd());
    if (request.listedById() != null) listing.setListedBy(requireAgent(request.listedById()));
    if (request.officeId() != null) listing.setOffice(requireOffice(request.officeId()));

    assertTermsPresent(listing.getListingType(), listing.getAskingRent(), listing.getAskingPrice());
    assertPeriod(listing.getListingStart(), listing.getListingEnd());
    listing.setUpdatedBy(actor.getId());

    Listing saved = listings.save(listing);
    recordAudit(actor, AuditActions.UPDATE, "Changed listing " + saved.getListingCode(), saved);
    return detailOf(saved);
  }

  /**
   * Publish a listing onto the market.
   *
   * <p>Refused with {@code LISTING_ALREADY_ACTIVE} when the listing is already live, and when
   * another live listing already offers the same unit (or, for a building-level listing, the same
   * property). Both guards run before anything is written, so a refused publish leaves no history
   * row.</p>
   */
  @Transactional
  public ListingDtos.ListingDetail publish(UUID listingId, ListingDtos.ListingActionRequest request) {
    User actor = accessScope.requireUser();
    Listing listing = require(listingId);
    requireVisible(listing);

    ListingStatus from = listing.getStatus();
    if (from != null && from.isLive()) {
      throw ApiException.rule(
          "LISTING_ALREADY_ACTIVE",
          "Listing " + listing.getListingCode() + " is already live on the board (" + from + ").");
    }
    if (from != null && from.isClosed()) {
      throw ApiException.rule(
          "LISTING_NOT_PUBLISHABLE",
          "Listing " + listing.getListingCode() + " is closed (" + from + ") and cannot be re-published.");
    }
    assertNoCompetingLiveListing(listing);

    move(listing, ListingStatus.PUBLISHED, reasonOf(request), actor);
    return detailOf(listing);
  }

  /** Withdraw a live listing from the market. */
  @Transactional
  public ListingDtos.ListingDetail unpublish(UUID listingId, ListingDtos.ListingActionRequest request) {
    User actor = accessScope.requireUser();
    Listing listing = require(listingId);
    requireVisible(listing);

    ListingStatus from = listing.getStatus();
    if (from == null || !from.isLive()) {
      throw ApiException.rule(
          "LISTING_NOT_ACTIVE",
          "Listing " + listing.getListingCode() + " is not live (" + from + ") and cannot be withdrawn.");
    }
    move(listing, ListingStatus.WITHDRAWN, reasonOf(request), actor);
    return detailOf(listing);
  }

  /** Close a live listing as expired because it ran past its end date. */
  @Transactional
  public ListingDtos.ListingDetail expire(UUID listingId, ListingDtos.ListingActionRequest request) {
    User actor = accessScope.requireUser();
    Listing listing = require(listingId);
    requireVisible(listing);

    ListingStatus from = listing.getStatus();
    if (from == null || !from.isLive()) {
      throw ApiException.rule(
          "LISTING_NOT_ACTIVE",
          "Listing " + listing.getListingCode() + " is not live (" + from + ") and cannot expire.");
    }
    move(listing, ListingStatus.EXPIRED, reasonOf(request), actor);
    return detailOf(listing);
  }

  /** Move a listing to any state the lifecycle permits - UNDER_OFFER, LET_AGREED, SOLD and so on. */
  @Transactional
  public ListingDtos.ListingDetail changeStatus(
      UUID listingId, ListingDtos.ListingStatusChangeRequest request) {
    User actor = accessScope.requireUser();
    Listing listing = require(listingId);
    requireVisible(listing);
    move(listing, parseStatus(request.status()), request.reason(), actor);
    return detailOf(listing);
  }

  // ---------------------------------------------------------------------------
  // Internals
  // ---------------------------------------------------------------------------

  @Transactional(readOnly = true)
  public Listing require(UUID listingId) {
    return listings
        .findByIdAndDeletedAtIsNull(listingId)
        .orElseThrow(() -> ApiException.notFound("We could not find that listing"));
  }

  /**
   * The single write path for a state change.
   *
   * <p>Validates the move, then sets the status <em>and</em> the matching {@code closed_at} /
   * {@code published_at} columns together in one write, so the schema's {@code ck_listings_closed}
   * constraint can never be presented with a half-applied state. The history row is written only
   * after the guard has passed.</p>
   */
  private void move(Listing listing, ListingStatus target, String reason, User actor) {
    ListingStatus from = listing.getStatus();
    if (from == target) {
      throw ApiException.rule("LISTING_STATUS_UNCHANGED", "This listing is already " + target + ".");
    }
    if (!canTransition(from, target)) {
      throw ApiException.rule(
          "LISTING_TRANSITION_NOT_ALLOWED",
          "A listing cannot move from " + from + " to " + target + ". From " + from + " the possible moves are "
              + nextStates(from) + ".");
    }

    LocalDateTime now = LocalDateTime.now();
    listing.setStatus(target);
    // A closed state carries the moment it closed; anything else must not.
    if (target.isClosed()) {
      listing.setClosedAt(now);
      listing.setClosedReason(reason);
    } else {
      listing.setClosedAt(null);
      listing.setClosedReason(null);
    }
    // The first moment the listing is live, recorded once.
    if (target.isLive() && listing.getPublishedAt() == null) {
      listing.setPublishedAt(now);
    }
    listing.setUpdatedBy(actor.getId());

    Listing saved = listings.save(listing);
    recordHistory(saved, from, target, reason, actor);
    recordAudit(
        actor,
        AuditActions.STATUS_CHANGE,
        "Moved listing " + saved.getListingCode() + " from " + from + " to " + target,
        saved);
  }

  private void assertNoCompetingLiveListing(Listing listing) {
    UUID unitId = listing.getUnit() == null ? null : listing.getUnit().getId();
    UUID propertyId = listing.getProperty() == null ? null : listing.getProperty().getId();
    boolean clash =
        listings.findAllForRegister().stream()
            .filter(other -> !other.getId().equals(listing.getId()))
            .filter(other -> other.getStatus() != null && other.getStatus().isLive())
            .anyMatch(
                other -> {
                  if (unitId != null) {
                    return other.getUnit() != null && unitId.equals(other.getUnit().getId());
                  }
                  // A building-level listing clashes only with another building-level listing.
                  return other.getUnit() == null
                      && propertyId != null
                      && other.getProperty() != null
                      && propertyId.equals(other.getProperty().getId());
                });
    if (clash) {
      throw ApiException.rule(
          "LISTING_ALREADY_ACTIVE",
          "This property or unit is already listed: another live listing already offers it.");
    }
  }

  private void recordHistory(
      Listing listing, ListingStatus from, ListingStatus to, String reason, User actor) {
    ListingStatusHistory entry = new ListingStatusHistory();
    entry.setListing(listing);
    entry.setFromStatus(from);
    entry.setToStatus(to);
    entry.setReason(reason);
    entry.setChangedBy(actor);
    entry.setChangedAt(LocalDateTime.now());
    history.save(entry);
  }

  private void recordAudit(User actor, String action, String description, Listing listing) {
    audit.record(
        actor.getId(),
        actor.getFullName(),
        AuditActions.LISTING,
        action,
        description,
        "LISTING",
        listing.getId(),
        listing.getListingCode());
  }

  private void requireVisible(Listing listing) {
    if (accessScope.isCompanyWide()) {
      return;
    }
    accessScope.requireAgentOrCompanyWide(
        listing.getListedBy() == null ? null : listing.getListedBy().getId());
  }

  private boolean isStale(Listing listing, LocalDate today) {
    if (listing.getStatus() == null || !listing.getStatus().isLive()) return false;
    LocalDate start = listing.getListingStart();
    if (start == null) return false;
    long days = ChronoUnit.DAYS.between(start, today);
    return days > staleDays();
  }

  private int staleDays() {
    return appProperties.getPortfolio() == null ? 45 : appProperties.getPortfolio().getListingStaleDays();
  }

  private AgentProfile resolveAgent(UUID requestedAgentId, User actor) {
    if (requestedAgentId != null) return requireAgent(requestedAgentId);
    return agents.findByUserIdAndDeletedAtIsNull(actor.getId()).orElse(null);
  }

  private static void assertTermsPresent(ListingType type, BigDecimal rent, BigDecimal price) {
    boolean ok =
        switch (type) {
          case RENT -> rent != null;
          case SALE -> price != null;
          case RENT_AND_SALE -> rent != null || price != null;
        };
    if (!ok) {
      throw ApiException.badRequest(
          "A " + type + " listing needs its asking terms: "
              + (type == ListingType.RENT
                  ? "an asking rent"
                  : type == ListingType.SALE ? "an asking price" : "an asking rent or an asking price")
              + ".");
    }
  }

  private static void assertPeriod(LocalDate start, LocalDate end) {
    if (start != null && end != null && end.isBefore(start)) {
      throw ApiException.badRequest("A listing cannot end before it starts");
    }
  }

  private static String reasonOf(ListingDtos.ListingActionRequest request) {
    return request == null ? null : request.reason();
  }

  private static boolean matchesSearch(Listing listing, String term) {
    return contains(listing.getHeadline(), term)
        || contains(listing.getListingCode(), term)
        || contains(listing.getDescription(), term)
        || contains(listing.getProperty() == null ? null : listing.getProperty().getName(), term)
        || contains(listing.getUnit() == null ? null : listing.getUnit().getUnitNumber(), term);
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

  private AgentProfile requireAgent(UUID id) {
    return agents
        .findByIdAndDeletedAtIsNull(id)
        .orElseThrow(() -> ApiException.notFound("We could not find that agent"));
  }

  private ListingChannel requireChannel(String code) {
    return channels
        .findByCodeIgnoreCase(code.trim())
        .orElseThrow(() -> ApiException.notFound("'" + code + "' is not a known listing channel"));
  }

  private static UUID idOf(Property property) {
    return property == null ? null : property.getId();
  }

  private static UUID idOf(Unit unit) {
    return unit == null ? null : unit.getId();
  }

  private static UUID idOf(AgentProfile agent) {
    return agent == null ? null : agent.getId();
  }

  private static UUID idOf(Office office) {
    return office == null ? null : office.getId();
  }

  private static UUID agentIdOf(Listing listing) {
    return listing.getListedBy() == null ? null : listing.getListedBy().getId();
  }

  private static String agentName(AgentProfile agent) {
    if (agent == null || agent.getUser() == null) return null;
    return agent.getUser().getFullName();
  }

  private static String enumName(Enum<?> value) {
    return value == null ? null : value.name();
  }

  private static ListingStatus parseStatus(String status) {
    try {
      return ListingStatus.valueOf(status.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException ex) {
      throw ApiException.badRequest("'" + status + "' is not a listing status");
    }
  }

  private static ListingType parseType(String type) {
    if (type == null || type.isBlank()) return ListingType.RENT;
    try {
      return ListingType.valueOf(type.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException ex) {
      throw ApiException.badRequest("'" + type + "' is not a listing type");
    }
  }

  private static <T extends Comparable<? super T>> Comparator<T> nullSafe() {
    return Comparator.nullsLast(Comparator.naturalOrder());
  }

  private static Comparator<Listing> comparator(Pageable pageable) {
    Comparator<Listing> tieBreak =
        Comparator.comparing(Listing::getCreatedAt, nullSafe())
            .thenComparing(Listing::getId, nullSafe());
    Comparator<Listing> chosen = null;
    Sort sort = pageable == null ? Sort.unsorted() : pageable.getSort();
    for (Sort.Order order : sort) {
      Comparator<Listing> by = SORTS.get(order.getProperty());
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
