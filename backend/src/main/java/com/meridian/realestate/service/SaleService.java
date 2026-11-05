package com.meridian.realestate.service;

import com.meridian.realestate.domain.AgentProfile;
import com.meridian.realestate.domain.AgentStatus;
import com.meridian.realestate.domain.Buyer;
import com.meridian.realestate.domain.Lead;
import com.meridian.realestate.domain.Offer;
import com.meridian.realestate.domain.OfferDirection;
import com.meridian.realestate.domain.OfferStatus;
import com.meridian.realestate.domain.Office;
import com.meridian.realestate.domain.Property;
import com.meridian.realestate.domain.SaleStage;
import com.meridian.realestate.domain.SaleStageHistory;
import com.meridian.realestate.domain.SalesOpportunity;
import com.meridian.realestate.domain.Unit;
import com.meridian.realestate.domain.User;
import com.meridian.realestate.dto.ErrorMessages;
import com.meridian.realestate.dto.PageResponse;
import com.meridian.realestate.dto.SaleDtos;
import com.meridian.realestate.exception.ApiException;
import com.meridian.realestate.repository.AgentProfileRepository;
import com.meridian.realestate.repository.BuyerRepository;
import com.meridian.realestate.repository.LeadRepository;
import com.meridian.realestate.repository.OfficeRepository;
import com.meridian.realestate.repository.OfferRepository;
import com.meridian.realestate.repository.PropertyRepository;
import com.meridian.realestate.repository.SaleStageHistoryRepository;
import com.meridian.realestate.repository.SalesOpportunityRepository;
import com.meridian.realestate.repository.UnitRepository;
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
 * The capital side of the business: the sale of an asset, the offers made on it and the stages it
 * passes through.
 *
 * <h2>Why this class exists</h2>
 * A sale is a negotiation with one buyer about one asset, and almost everything about it is a
 * sequence rather than a field. Offers are a sequence (which is what shows whether a deal is moving
 * or stuck), stages are a sequence (which is what the funnel and the velocity figures are drawn
 * from), and the outcome the deal reached has to be coherent with the stage it is in. Those are one
 * subject, and stating them together is what stops the pipeline, the board and the deal screen
 * disagreeing about what is live and what has concluded.
 *
 * <h2>Status changes are operations, not edits</h2>
 * There is no method here that writes a stage from a request field. A sale moves only through
 * {@link #transition}, which validates the move against the map in {@link #ALLOWED} and refuses an
 * illegal one with {@code SALE_TRANSITION_NOT_ALLOWED} (409), or through the offer operations, which
 * move it as a consequence of the thing that actually happened - recording an offer takes a lead to
 * {@code OFFER}, accepting one takes the deal to {@code UNDER_CONTRACT}, and a counter takes it to
 * {@code NEGOTIATION}. <b>Every stage change writes a {@link SaleStageHistory} row</b>, including the
 * first: {@code from_stage} is nullable precisely so a sale's chronology can start at nothing becoming
 * {@code LEAD}.
 *
 * <h2>The outcome rules, stated exactly as the schema has them</h2>
 * {@code ck_sales_opportunities_outcome} allows three shapes and nothing else, and this service sets
 * the whole outcome in one write rather than one column at a time, because a half-applied outcome is
 * refused by the database as an opaque integrity error:
 * <ul>
 *   <li>a {@code CLOSED} sale needs a non-null agreed price and a closure moment, and must carry
 *       neither a loss moment nor a reason. Closing without a price is refused as
 *       {@code SALE_AMOUNT_REQUIRED}, and closing a sale that was lost is refused because the outcome
 *       columns are a single fact;</li>
 *   <li>a {@code LOST} sale needs a loss moment and a reason, and must not carry a closure moment or
 *       an agreed price. Losing a sale that already completed is refused for the same reason;</li>
 *   <li>every other stage must carry all three as null - so a deal in negotiation has an
 *       {@code expectedPrice} and no agreed one, and a pipeline figure cannot accidentally include a
 *       deal that has already concluded.</li>
 * </ul>
 * <p>Additionally, a sale cannot reach {@code CLOSED} without an accepted offer
 * ({@code SALE_COMPLETION_REQUIRES_ACCEPTED_OFFER}). Completion is what an accepted offer means; a
 * completed sale with nothing accepted is a price nobody agreed to.</p>
 *
 * <h2>One accepted offer per sale, checked before it is written</h2>
 * The schema enforces it with the partial unique index {@code uq_offers_accepted_per_sale}, but the
 * index only sees rows already marked {@code ACCEPTED}, so it refuses the second acceptance at flush
 * time as a driver-level constraint violation. This service therefore reads the sale for an existing
 * accepted offer first and fails with {@code OFFER_ALREADY_ACCEPTED} (409), so the caller is told the
 * asset is already under offer rather than being handed an error it cannot act on. Acceptance is what
 * ends the negotiation: a run of offers is the record, and the accepted one is the deal.
 *
 * <h2>Pipeline and scope</h2>
 * Concluded stages - {@code CLOSED} and {@code LOST} - are not pipeline, and no pipeline figure
 * includes them; including one would double-count it against the sale it became. A scoped caller (a
 * sales agent) sees only the deals they own while a firm-wide role sees every deal, and that is
 * enforced here rather than only at the endpoint.
 *
 * <h2>A note on paging, and on a gap reported rather than patched</h2>
 * There is no combined paged query for this register's seven filters and no read for the whole offer
 * table, so the pipeline and the offers are filtered, sorted and sliced here - rather than a literal
 * {@code order by} being put in a query, which this product forbids because it silently overrides the
 * order the caller asked for. {@code OfferRepository} has no {@code findAll*DeletedAtIsNull} read, so
 * {@link #offers} reads the table and filters archived rows out in memory; that method is reported as
 * a needed-but-not-added repository read. The migration chain also seeds no business-number series for
 * offers, so an offer number is minted from the {@code SALE} series - reported, likewise, rather than
 * worked around.
 */
@Service
public class SaleService {

  /** The permitted stage moves. See the class note: the map is the rule, not a suggestion. */
  private static final Map<SaleStage, Set<SaleStage>> ALLOWED = new EnumMap<>(SaleStage.class);

  static {
    ALLOWED.put(SaleStage.LEAD, EnumSet.of(SaleStage.OFFER, SaleStage.LOST));
    ALLOWED.put(
        SaleStage.OFFER, EnumSet.of(SaleStage.NEGOTIATION, SaleStage.UNDER_CONTRACT, SaleStage.LOST));
    ALLOWED.put(SaleStage.NEGOTIATION, EnumSet.of(SaleStage.UNDER_CONTRACT, SaleStage.LOST));
    ALLOWED.put(SaleStage.UNDER_CONTRACT, EnumSet.of(SaleStage.CLOSED, SaleStage.LOST));
    // Terminal, and frozen: a concluded deal is a fact and is not reopened.
    ALLOWED.put(SaleStage.CLOSED, EnumSet.noneOf(SaleStage.class));
    ALLOWED.put(SaleStage.LOST, EnumSet.noneOf(SaleStage.class));
  }

  /** The order the funnel and the board present the stages in. */
  private static final List<SaleStage> STAGE_ORDER =
      List.of(
          SaleStage.LEAD,
          SaleStage.OFFER,
          SaleStage.NEGOTIATION,
          SaleStage.UNDER_CONTRACT,
          SaleStage.CLOSED,
          SaleStage.LOST);

  /** The actions an offer can be decided with. */
  private enum OfferAction {
    ACCEPT,
    COUNTER,
    REJECT,
    WITHDRAW
  }

  private static final Map<String, Comparator<SalesOpportunity>> SORTS =
      Map.ofEntries(
          Map.entry("createdAt", Comparator.comparing(SalesOpportunity::getCreatedAt, nullSafe())),
          Map.entry("updatedAt", Comparator.comparing(SalesOpportunity::getUpdatedAt, nullSafe())),
          Map.entry("saleCode", Comparator.comparing(SalesOpportunity::getSaleCode, nullSafe())),
          Map.entry(
              "stage",
              Comparator.comparing((SalesOpportunity s) -> enumName(s.getStage()), nullSafe())),
          Map.entry("expectedPrice", Comparator.comparing(SalesOpportunity::getExpectedPrice, nullSafe())),
          Map.entry("askingPrice", Comparator.comparing(SalesOpportunity::getAskingPrice, nullSafe())),
          Map.entry("agreedPrice", Comparator.comparing(SalesOpportunity::getAgreedPrice, nullSafe())),
          Map.entry(
              "expectedCloseDate", Comparator.comparing(SalesOpportunity::getExpectedCloseDate, nullSafe())),
          Map.entry(
              "property.name",
              Comparator.comparing(
                  (SalesOpportunity s) -> s.getProperty() == null ? null : s.getProperty().getName(),
                  nullSafe())),
          Map.entry(
              "buyer.name",
              Comparator.comparing(
                  (SalesOpportunity s) -> s.getBuyer() == null ? null : s.getBuyer().getDisplayName(),
                  nullSafe())));

  /** The offer register's sortable fields - the ones an offer actually has. */
  private static final Map<String, Comparator<Offer>> OFFER_SORTS =
      Map.of(
          "offeredAt", Comparator.comparing(Offer::getOfferedAt, nullSafe()),
          "amount", Comparator.comparing(Offer::getAmount, nullSafe()),
          "status", Comparator.comparing((Offer o) -> enumName(o.getStatus()), nullSafe()),
          "direction", Comparator.comparing((Offer o) -> enumName(o.getDirection()), nullSafe()),
          "offerNumber", Comparator.comparing(Offer::getOfferNumber, nullSafe()));

  private final SalesOpportunityRepository sales;
  private final OfferRepository offers;
  private final SaleStageHistoryRepository stageHistory;
  private final PropertyRepository properties;
  private final UnitRepository units;
  private final BuyerRepository buyers;
  private final LeadRepository leads;
  private final AgentProfileRepository agents;
  private final OfficeRepository offices;
  private final BusinessNumberService numbers;
  private final AuditRecordingService audit;
  private final AccessScope accessScope;

  public SaleService(
      SalesOpportunityRepository sales,
      OfferRepository offers,
      SaleStageHistoryRepository stageHistory,
      PropertyRepository properties,
      UnitRepository units,
      BuyerRepository buyers,
      LeadRepository leads,
      AgentProfileRepository agents,
      OfficeRepository offices,
      BusinessNumberService numbers,
      AuditRecordingService audit,
      AccessScope accessScope) {
    this.sales = sales;
    this.offers = offers;
    this.stageHistory = stageHistory;
    this.properties = properties;
    this.units = units;
    this.buyers = buyers;
    this.leads = leads;
    this.agents = agents;
    this.offices = offices;
    this.numbers = numbers;
    this.audit = audit;
    this.accessScope = accessScope;
  }

  // ---------------------------------------------------------------------------
  // The lifecycle, exposed so a screen can offer only the moves that will succeed
  // ---------------------------------------------------------------------------

  public static boolean canTransition(SaleStage from, SaleStage to) {
    if (from == null || to == null || from == to) return false;
    return ALLOWED.getOrDefault(from, Set.of()).contains(to);
  }

  public static Set<SaleStage> nextStages(SaleStage from) {
    return ALLOWED.getOrDefault(from, Set.of());
  }

  /**
   * True when an offer in this status can move to that one.
   *
   * <p>Only an unanswered offer can be decided; a decided one is a fact and is not revised, which is
   * why a counter is a new offer in the other direction rather than an amendment of the original.</p>
   */
  public static boolean canDecideOffer(OfferStatus from, OfferStatus to) {
    if (from == null || to == null || from == to) return false;
    if (from != OfferStatus.SUBMITTED) return false;
    return to == OfferStatus.ACCEPTED
        || to == OfferStatus.COUNTERED
        || to == OfferStatus.REJECTED
        || to == OfferStatus.WITHDRAWN
        || to == OfferStatus.EXPIRED;
  }

  private static Set<String> transitionsFor(SaleStage stage) {
    Set<String> names = new LinkedHashSet<>();
    for (SaleStage next : nextStages(stage)) names.add(next.name());
    return names;
  }

  // ---------------------------------------------------------------------------
  // Reads
  // ---------------------------------------------------------------------------

  /** The live sale behind an id, or a 404. Archived sales are not addressable. */
  @Transactional(readOnly = true)
  public SalesOpportunity require(UUID saleId) {
    return sales
        .findByIdAndDeletedAtIsNull(saleId)
        .orElseThrow(() -> ApiException.notFound(ErrorMessages.friendly("SALE_NOT_FOUND")));
  }

  /** The pipeline register, filtered, searched, sorted and paged. */
  @Transactional(readOnly = true)
  public PageResponse<SaleDtos.SaleSummary> list(
      String stage,
      UUID propertyId,
      UUID unitId,
      UUID buyerId,
      UUID ownerAgentId,
      LocalDate createdFrom,
      LocalDate createdTo,
      Boolean openOnly,
      String search,
      Pageable pageable) {

    SaleStage stageFilter = stage == null || stage.isBlank() ? null : parseStage(stage);
    String term = search == null || search.isBlank() ? null : search.trim().toLowerCase(Locale.ROOT);
    boolean openFilter = Boolean.TRUE.equals(openOnly);

    List<SaleDtos.SaleSummary> rows =
        sales.findAllForPipeline().stream()
            .filter(this::visible)
            .filter(sale -> stageFilter == null || stageFilter == sale.getStage())
            .filter(sale -> !openFilter || sale.isOpen())
            .filter(
                sale ->
                    propertyId == null
                        || (sale.getProperty() != null
                            && propertyId.equals(sale.getProperty().getId())))
            .filter(
                sale -> unitId == null || (sale.getUnit() != null && unitId.equals(sale.getUnit().getId())))
            .filter(
                sale ->
                    buyerId == null || (sale.getBuyer() != null && buyerId.equals(sale.getBuyer().getId())))
            .filter(
                sale ->
                    ownerAgentId == null
                        || (sale.getOwnerAgent() != null
                            && ownerAgentId.equals(sale.getOwnerAgent().getId())))
            .filter(sale -> withinCreated(sale, createdFrom, createdTo))
            .filter(sale -> term == null || matchesSearch(sale, term))
            .sorted(comparator(pageable))
            .map(SaleDtos.SaleSummary::from)
            .toList();

    return paginate(rows, pageable);
  }

  /** One sale, with its offers and its stage trail. */
  @Transactional(readOnly = true)
  public SaleDtos.SaleDetail detail(UUID saleId) {
    SalesOpportunity sale = require(saleId);
    accessScope.requireAgentOrCompanyWide(
        sale.getOwnerAgent() == null ? null : sale.getOwnerAgent().getId());
    return detailOf(sale);
  }

  /**
   * The board: how many deals sit in each stage and what the live pipeline is worth.
   *
   * <p>The value is summed over {@code expected_price} and only over the deals still being worked -
   * never the asking price, which is what the asset was marketed at, and never a concluded deal's
   * agreed price, which is no longer pipeline at all. Each column's own value is deliberately not
   * reported: the repository's sum is the one number that has to reconcile, and inventing a per-column
   * split from a different population is how a board's columns stop adding up to its own total.</p>
   */
  @Transactional(readOnly = true)
  public SaleDtos.PipelineResponse pipeline() {
    Map<SaleStage, Long> counts = countsByStage();
    long total = counts.values().stream().mapToLong(Long::longValue).sum();
    long open =
        counts.entrySet().stream()
            .filter(entry -> !entry.getKey().isConcluded())
            .mapToLong(Map.Entry::getValue)
            .sum();

    List<SaleDtos.StageBucket> buckets = new ArrayList<>();
    for (SaleStage stage : STAGE_ORDER) {
      buckets.add(
          new SaleDtos.StageBucket(
              stage.name(), counts.getOrDefault(stage, 0L), null, !stage.isConcluded()));
    }

    return new SaleDtos.PipelineResponse(
        total, open, safe(sales.sumOpenPipelineValue()), LocalDateTime.now(), buckets);
  }

  /** The sales card: the live pipeline and what has actually completed. */
  @Transactional(readOnly = true)
  public SaleDtos.SalesSummary summary() {
    Map<SaleStage, Long> counts = countsByStage();
    long total = counts.values().stream().mapToLong(Long::longValue).sum();
    long open =
        counts.entrySet().stream()
            .filter(entry -> !entry.getKey().isConcluded())
            .mapToLong(Map.Entry::getValue)
            .sum();

    BigDecimal closedValue =
        sales.findAllForPipeline().stream()
            .filter(this::visible)
            .filter(sale -> sale.getStage() == SaleStage.CLOSED)
            .map(sale -> safe(sale.getAgreedPrice()))
            .reduce(BigDecimal.ZERO, BigDecimal::add);

    return new SaleDtos.SalesSummary(
        total,
        open,
        counts.getOrDefault(SaleStage.CLOSED, 0L),
        counts.getOrDefault(SaleStage.LOST, 0L),
        safe(sales.sumOpenPipelineValue()),
        closedValue,
        LocalDateTime.now());
  }

  /**
   * The offers made across the pipeline, filtered.
   *
   * <p>{@code OfferRepository} carries per-sale reads and no read of the live table, so this filters
   * archived rows out in memory rather than adding a repository method - see the class note. Ordering
   * arrives as a {@code Pageable} the caller has already validated.</p>
   */
  @Transactional(readOnly = true)
  public PageResponse<SaleDtos.OfferEntry> offers(
      UUID saleId, String status, String direction, Pageable pageable) {

    OfferStatus statusFilter = status == null || status.isBlank() ? null : parseOfferStatus(status);
    OfferDirection directionFilter =
        direction == null || direction.isBlank() ? null : parseOfferDirection(direction);

    List<SaleDtos.OfferEntry> rows =
        offers.findAll().stream()
            .filter(offer -> offer.getDeletedAt() == null)
            .filter(
                offer ->
                    saleId == null
                        || (offer.getSale() != null && saleId.equals(offer.getSale().getId())))
            .filter(offer -> statusFilter == null || statusFilter == offer.getStatus())
            .filter(offer -> directionFilter == null || directionFilter == offer.getDirection())
            .sorted(offerComparator(pageable))
            .map(SaleDtos.OfferEntry::from)
            .toList();

    return paginate(rows, pageable);
  }

  // ---------------------------------------------------------------------------
  // The operations
  // ---------------------------------------------------------------------------

  /** Open a sale. Every sale opens at LEAD, whatever a caller may have hoped. */
  @Transactional
  public SaleDtos.SaleDetail create(SaleDtos.CreateSaleRequest request) {
    User actor = accessScope.requireUser();

    Property property = requireProperty(request.propertyId());
    Unit unit = request.unitId() == null ? null : requireUnit(request.unitId());
    assertUnitBelongs(property, unit);
    Buyer buyer = requireBuyer(request.buyerId());
    Lead lead = request.leadId() == null ? null : requireLead(request.leadId());

    // One live opportunity per buyer per property-and-unit: two open rows would double-count the
    // pipeline, and the schema's partial unique index would refuse the second as an opaque error.
    if (hasOpenOpportunity(property, unit, buyer)) {
      throw ApiException.duplicate(
          "DUPLICATE_OPEN_SALE",
          "This buyer already has a live opportunity on this "
              + (unit == null ? "property" : "unit")
              + "; a buyer cannot be negotiating the same asset twice.");
    }

    SalesOpportunity sale = new SalesOpportunity();
    sale.setSaleCode(numbers.next(BusinessNumberService.SALE));
    sale.setProperty(property);
    sale.setUnit(unit);
    sale.setBuyer(buyer);
    sale.setLead(lead);
    sale.setStage(SaleStage.LEAD);
    sale.setAskingPrice(moneyOrNull(request.askingPrice()));
    sale.setExpectedPrice(moneyOrNull(request.expectedPrice()));
    sale.setExpectedCloseDate(request.expectedCloseDate());
    sale.setOffice(request.officeId() == null ? null : requireOffice(request.officeId()));
    sale.setNotes(request.notes());
    sale.setCreatedBy(actor.getId());
    sale.setUpdatedBy(actor.getId());

    if (request.ownerAgentId() != null) {
      sale.setOwnerAgent(requireActiveAgent(request.ownerAgentId()));
    }

    SalesOpportunity saved = sales.save(sale);

    // The first row of the trail: nothing became LEAD.
    writeStageHistory(saved, null, SaleStage.LEAD, "Sale opened", actor, LocalDateTime.now());

    recordAudit(
        actor,
        AuditActions.CREATE,
        "Opened sale " + saved.getSaleCode() + " for " + buyer.getDisplayName()
            + " at " + property.getPropertyCode(),
        saved);
    return detailOf(saved);
  }

  /** Change a sale's commercial details. A concluded deal is a fact and is not edited back. */
  @Transactional
  public SaleDtos.SaleDetail update(UUID saleId, SaleDtos.UpdateSaleRequest request) {
    User actor = accessScope.requireUser();
    SalesOpportunity sale = require(saleId);
    requireVisible(sale);

    if (sale.getStage() != null && sale.getStage().isConcluded()) {
      throw ApiException.rule(
          "SALE_ALREADY_CONCLUDED",
          "A sale that is " + sale.getStage() + " has concluded and is not edited back.");
    }

    if (request.askingPrice() != null) sale.setAskingPrice(MoneyPolicy.money(request.askingPrice()));
    if (request.expectedPrice() != null) {
      sale.setExpectedPrice(MoneyPolicy.money(request.expectedPrice()));
    }
    if (request.expectedCloseDate() != null) sale.setExpectedCloseDate(request.expectedCloseDate());
    if (request.ownerAgentId() != null) {
      sale.setOwnerAgent(requireActiveAgent(request.ownerAgentId()));
    }
    if (request.officeId() != null) sale.setOffice(requireOffice(request.officeId()));
    if (request.notes() != null) sale.setNotes(request.notes());
    sale.setUpdatedBy(actor.getId());

    SalesOpportunity saved = sales.save(sale);
    recordAudit(actor, AuditActions.UPDATE, "Changed sale " + saved.getSaleCode(), saved);
    return detailOf(saved);
  }

  /**
   * Move a sale between stages, writing the whole outcome when the move concludes it.
   *
   * <p>The closing move needs a capability the other moves do not, and that is checked here rather
   * than at the endpoint because it only becomes relevant once the target is known: the endpoint
   * gates "change the stage", and this gates "declare the deal won or lost". Both are held by a sales
   * agent, so the ordinary path is unchanged, and an account that may move a deal along without being
   * allowed to conclude one is refused with a 403 rather than quietly completing a sale.</p>
   */
  @Transactional
  public SaleDtos.SaleDetail transition(
      UUID saleId, SaleDtos.SaleStageTransitionRequest request) {
    User actor = accessScope.requireUser();
    SalesOpportunity sale = require(saleId);
    requireVisible(sale);

    SaleStage from = sale.getStage();
    SaleStage target = parseStage(request.stage());

    if (from == target) {
      throw ApiException.rule(
          "SALE_TRANSITION_NOT_ALLOWED", "This sale is already at " + target + ".");
    }
    if (!canTransition(from, target)) {
      throw ApiException.rule(
          "SALE_TRANSITION_NOT_ALLOWED",
          "A sale cannot move from " + from + " to " + target + "."
              + (nextStages(from).isEmpty()
                  ? " A " + from + " sale has concluded and cannot move again."
                  : " From " + from + " the possible moves are " + nextStages(from) + "."));
    }
    if (target.isConcluded()) {
      accessScope.requirePermission("SALE_CLOSE");
    }

    LocalDateTime now = LocalDateTime.now();
    // The stage and its outcome columns are written together: the schema refuses a half-applied
    // outcome, and it refuses an outcome on a stage that should not carry one.
    if (target == SaleStage.CLOSED) {
      assertNotPreviouslyLost(sale);
      // Completion is what an accepted offer means; a completed sale with nothing accepted is a
      // price nobody agreed to.
      if (offers.findAcceptedForSale(sale.getId()).isEmpty()) {
        throw ApiException.rule(
            "SALE_COMPLETION_REQUIRES_ACCEPTED_OFFER",
            ErrorMessages.friendly("SALE_COMPLETION_REQUIRES_ACCEPTED_OFFER"));
      }
      if (request.agreedPrice() == null
          || !MoneyPolicy.isPositive(MoneyPolicy.money(request.agreedPrice()))) {
        throw ApiException.rule("SALE_AMOUNT_REQUIRED", ErrorMessages.friendly("SALE_AMOUNT_REQUIRED"));
      }
      sale.setAgreedPrice(MoneyPolicy.money(request.agreedPrice()));
      sale.setClosedAt(now);
      sale.setLostAt(null);
      sale.setLostReason(null);
    } else if (target == SaleStage.LOST) {
      assertNotPreviouslyClosed(sale);
      if (request.lostReason() == null || request.lostReason().isBlank()) {
        throw ApiException.badRequest("A lost sale must record why it was lost");
      }
      sale.setLostAt(now);
      sale.setLostReason(request.lostReason().trim());
      sale.setClosedAt(null);
      sale.setAgreedPrice(null);
    } else {
      // Every other stage carries neither outcome; the schema requires all three to be null.
      sale.setClosedAt(null);
      sale.setLostAt(null);
      sale.setLostReason(null);
      sale.setAgreedPrice(null);
    }

    sale.setStage(target);
    sale.setUpdatedBy(actor.getId());
    SalesOpportunity saved = sales.save(sale);

    writeStageHistory(saved, from, target, request.reason(), actor, now);
    recordAudit(
        actor,
        AuditActions.SALE_STAGE_CHANGED,
        "Sale " + saved.getSaleCode() + " " + from + " -> " + target,
        saved);

    return detailOf(saved);
  }

  /**
   * Record an offer made on a sale.
   *
   * <p>The first offer takes a lead to {@code OFFER}, in the same transaction: a deal with an offer on
   * the table is no longer an enquiry, and leaving it as one is how a pipeline under-reports what is
   * actually happening.</p>
   */
  @Transactional
  public SaleDtos.SaleDetail recordOffer(UUID saleId, SaleDtos.RecordOfferRequest request) {
    User actor = accessScope.requireUser();
    SalesOpportunity sale = require(saleId);
    requireVisible(sale);

    if (sale.getStage() != null && sale.getStage().isConcluded()) {
      throw ApiException.rule(
          "SALE_ALREADY_CONCLUDED",
          "A sale that is " + sale.getStage() + " has concluded and cannot take a new offer.");
    }

    BigDecimal amount = MoneyPolicy.money(request.amount());
    if (!MoneyPolicy.isPositive(amount)) {
      throw ApiException.badRequest("An offer must be for an amount greater than zero");
    }

    LocalDateTime now = LocalDateTime.now();
    Offer offer = new Offer();
    offer.setOfferNumber(numbers.next(BusinessNumberService.SALE));
    offer.setSale(sale);
    offer.setAmount(amount);
    offer.setOfferedAt(now);
    offer.setDirection(parseOfferDirection(request.direction()));
    // SUBMITTED: an offer nobody has answered yet. ck_offers_response requires no response moment.
    offer.setStatus(OfferStatus.SUBMITTED);
    offer.setExpiresAt(request.expiresAt());
    offer.setConditions(request.conditions());
    offer.setRespondedAt(null);
    offer.setRespondedBy(null);
    offer.setCreatedBy(actor.getId());
    offer.setUpdatedBy(actor.getId());

    Offer saved = offers.save(offer);

    if (sale.getStage() == SaleStage.LEAD || canTransition(sale.getStage(), SaleStage.OFFER)) {
      moveStage(sale, SaleStage.OFFER, "Offer recorded", actor);
    }

    audit
        .recordEntityChange(
            actor.getId(),
            actor.getFullName(),
            AuditActions.SALE,
            AuditActions.CREATE,
            "Recorded " + saved.getDirection() + " offer " + saved.getOfferNumber() + " of "
                + saved.getAmount() + " on sale " + sale.getSaleCode(),
            "OFFER",
            saved.getId(),
            saved.getOfferNumber(),
            null,
            OfferStatus.SUBMITTED.name(),
            referencesFor(sale));

    return detailOf(sale);
  }

  /**
   * Decide an offer: accept it, counter it, reject it or withdraw it.
   *
   * <p>Acceptance is the one that changes the deal, and it does three things in one transaction: it
   * checks the sale for an existing accepted offer and refuses with {@code OFFER_ALREADY_ACCEPTED} if
   * there is one - the check the partial unique index cannot give the caller a readable answer for -
   * it marks the offer answered, and it takes the sale to {@code UNDER_CONTRACT}, which is the stage
   * that means "terms agreed, contracts being executed". The stage move can be refused by the stage
   * map, in which case the sale is simply already there.</p>
   */
  @Transactional
  public SaleDtos.SaleDetail decideOffer(UUID offerId, SaleDtos.OfferDecisionRequest request) {
    User actor = accessScope.requireUser();

    Offer offer =
        offers
            .findByIdAndDeletedAtIsNull(offerId)
            .orElseThrow(() -> ApiException.notFound(ErrorMessages.friendly("OFFER_NOT_FOUND")));
    SalesOpportunity sale = require(offer.getSale().getId());
    requireVisible(sale);

    OfferAction action = parseOfferAction(request.action());
    LocalDateTime now = LocalDateTime.now();
    String notes = request.responseNotes() == null ? null : request.responseNotes().trim();

    switch (action) {
      case ACCEPT -> {
        // Checked before anything is written, so the caller gets a named conflict rather than the
        // index's opaque constraint failure at flush time.
        if (offers.findAcceptedForSale(sale.getId()).isPresent()) {
          throw ApiException.rule(
              "OFFER_ALREADY_ACCEPTED", ErrorMessages.friendly("OFFER_ALREADY_ACCEPTED"));
        }
        requireUndecided(offer);
        offer.setStatus(OfferStatus.ACCEPTED);
        offer.setRespondedAt(now);
        offer.setRespondedBy(actor);
        offer.setResponseNotes(notes);
        offer.setUpdatedBy(actor.getId());
        Offer accepted = offers.save(offer);

        // The sale moves in the same transaction: an accepted offer and a deal still reading as an
        // enquiry would be two answers to one question.
        if (canTransition(sale.getStage(), SaleStage.UNDER_CONTRACT)) {
          moveStage(sale, SaleStage.UNDER_CONTRACT, "Offer accepted", actor);
        }

        recordOfferAudit(
            actor,
            AuditActions.OFFER_ACCEPTED,
            "Accepted offer " + accepted.getOfferNumber() + " of " + accepted.getAmount()
                + " on sale " + sale.getSaleCode(),
            sale,
            accepted,
            OfferStatus.SUBMITTED.name(),
            OfferStatus.ACCEPTED.name());
      }
      case REJECT -> {
        requireUndecided(offer);
        offer.setStatus(OfferStatus.REJECTED);
        offer.setRespondedAt(now);
        offer.setRespondedBy(actor);
        offer.setResponseNotes(notes);
        offer.setUpdatedBy(actor.getId());
        offers.save(offer);
        recordOfferAudit(
            actor,
            AuditActions.UPDATE,
            "Rejected offer " + offer.getOfferNumber() + " on sale " + sale.getSaleCode(),
            sale,
            offer,
            OfferStatus.SUBMITTED.name(),
            OfferStatus.REJECTED.name());
      }
      case WITHDRAW -> {
        requireUndecided(offer);
        // ck_offers_response: a withdrawn offer is not an answered one, so it carries no response.
        offer.setStatus(OfferStatus.WITHDRAWN);
        offer.setRespondedAt(null);
        offer.setRespondedBy(null);
        offer.setResponseNotes(notes);
        offer.setUpdatedBy(actor.getId());
        offers.save(offer);
        recordOfferAudit(
            actor,
            AuditActions.UPDATE,
            "Withdrew offer " + offer.getOfferNumber() + " on sale " + sale.getSaleCode(),
            sale,
            offer,
            OfferStatus.SUBMITTED.name(),
            OfferStatus.WITHDRAWN.name());
      }
      case COUNTER -> {
        requireUndecided(offer);
        if (request.counterAmount() == null
            || !MoneyPolicy.isPositive(MoneyPolicy.money(request.counterAmount()))) {
          throw ApiException.badRequest(
              "A counter must name the amount the firm is putting forward");
        }
        // The original is answered rather than overwritten, and the counter is a new offer in the
        // other direction: the sequence is what shows the negotiation moving.
        offer.setStatus(OfferStatus.COUNTERED);
        offer.setRespondedAt(now);
        offer.setRespondedBy(actor);
        offer.setResponseNotes(notes);
        offer.setUpdatedBy(actor.getId());
        offers.save(offer);

        Offer counter = new Offer();
        counter.setOfferNumber(numbers.next(BusinessNumberService.SALE));
        counter.setSale(sale);
        counter.setAmount(MoneyPolicy.money(request.counterAmount()));
        counter.setOfferedAt(now);
        counter.setDirection(OfferDirection.FIRM);
        counter.setStatus(OfferStatus.SUBMITTED);
        counter.setExpiresAt(request.expiresAt());
        counter.setCreatedBy(actor.getId());
        counter.setUpdatedBy(actor.getId());
        Offer savedCounter = offers.save(counter);

        if (canTransition(sale.getStage(), SaleStage.NEGOTIATION)) {
          moveStage(sale, SaleStage.NEGOTIATION, "Countered", actor);
        }

        recordOfferAudit(
            actor,
            AuditActions.CREATE,
            "Countered offer " + offer.getOfferNumber() + " with " + savedCounter.getOfferNumber()
                + " of " + savedCounter.getAmount() + " on sale " + sale.getSaleCode(),
            sale,
            savedCounter,
            OfferStatus.SUBMITTED.name(),
            OfferStatus.SUBMITTED.name());
      }
    }

    return detailOf(sale);
  }

  // ---------------------------------------------------------------------------
  // Internals
  // ---------------------------------------------------------------------------

  private void requireUndecided(Offer offer) {
    if (offer.getStatus() != OfferStatus.SUBMITTED) {
      throw ApiException.rule(
          "OFFER_ALREADY_DECIDED",
          ErrorMessages.friendly("OFFER_ALREADY_DECIDED")
              + " It is " + offer.getStatus() + ".");
    }
  }

  /** The outcome columns are a single fact: a deal that was lost is not also closed as won. */
  private static void assertNotPreviouslyLost(SalesOpportunity sale) {
    if (sale.getLostAt() != null || sale.getLostReason() != null) {
      throw ApiException.rule(
          "SALE_TRANSITION_NOT_ALLOWED",
          "This deal was recorded as lost, so it cannot now be closed as won; the outcome columns "
              + "are one fact and the schema refuses a row carrying both.");
    }
  }

  /** And the inverse: a deal that completed is not also lost. */
  private static void assertNotPreviouslyClosed(SalesOpportunity sale) {
    if (sale.getClosedAt() != null || sale.getAgreedPrice() != null) {
      throw ApiException.rule(
          "SALE_TRANSITION_NOT_ALLOWED",
          "This deal completed at an agreed price, so it cannot now be recorded as lost; the outcome "
              + "columns are one fact and the schema refuses a row carrying both.");
    }
  }

  /** Move a sale's stage as a consequence of something that happened, writing the trail. */
  private void moveStage(SalesOpportunity sale, SaleStage target, String reason, User actor) {
    SaleStage from = sale.getStage();
    if (from == target) return;
    if (!canTransition(from, target)) {
      throw ApiException.rule(
          "SALE_TRANSITION_NOT_ALLOWED",
          "A sale cannot move from " + from + " to " + target + ".");
    }

    LocalDateTime now = LocalDateTime.now();
    sale.setStage(target);
    sale.setUpdatedBy(actor.getId());
    SalesOpportunity saved = sales.save(sale);

    writeStageHistory(saved, from, target, reason, actor, now);
    recordAudit(
        actor,
        AuditActions.SALE_STAGE_CHANGED,
        "Sale " + saved.getSaleCode() + " " + from + " -> " + target,
        saved);
  }

  private SaleDtos.SaleDetail detailOf(SalesOpportunity sale) {
    UUID saleId = sale.getId();

    List<SaleDtos.OfferEntry> offerRows =
        saleId == null
            ? List.of()
            : offers.findAllBySaleIdAndDeletedAtIsNull(saleId).stream()
                .map(SaleDtos.OfferEntry::from)
                .toList();

    List<SaleDtos.StageHistoryEntry> trail =
        saleId == null
            ? List.of()
            : stageHistory.findAllBySale_IdOrderByChangedAtDesc(saleId).stream()
                .map(
                    entry ->
                        new SaleDtos.StageHistoryEntry(
                            entry.getId(),
                            entry.getFromStage() == null ? null : entry.getFromStage().name(),
                            entry.getToStage() == null ? null : entry.getToStage().name(),
                            entry.getReason(),
                            entry.getChangedBy() == null ? null : entry.getChangedBy().getId(),
                            entry.getChangedAt()))
                .toList();

    UUID acceptedOfferId =
        offerRows.stream()
            .filter(offer -> OfferStatus.ACCEPTED.name().equals(offer.status()))
            .map(SaleDtos.OfferEntry::id)
            .findFirst()
            .orElse(null);

    BigDecimal totalOffered =
        offerRows.stream()
            .map(offer -> safe(offer.amount()))
            .reduce(BigDecimal.ZERO, BigDecimal::add);

    return new SaleDtos.SaleDetail(
        sale.getId(),
        sale.getSaleCode(),
        sale.getProperty() == null ? null : sale.getProperty().getId(),
        sale.getProperty() == null ? null : sale.getProperty().getPropertyCode(),
        sale.getProperty() == null ? null : sale.getProperty().getName(),
        sale.getUnit() == null ? null : sale.getUnit().getId(),
        sale.getUnit() == null ? null : sale.getUnit().getUnitCode(),
        sale.getBuyer() == null ? null : sale.getBuyer().getId(),
        sale.getBuyer() == null ? null : sale.getBuyer().getDisplayName(),
        sale.getLead() == null ? null : sale.getLead().getId(),
        sale.getStage() == null ? null : sale.getStage().name(),
        sale.getAskingPrice(),
        sale.getExpectedPrice(),
        sale.getAgreedPrice(),
        sale.getExpectedCloseDate(),
        sale.getClosedAt(),
        sale.getLostAt(),
        sale.getLostReason(),
        sale.getOwnerAgent() == null ? null : sale.getOwnerAgent().getId(),
        SaleDtos.agentName(sale),
        sale.getOffice() == null ? null : sale.getOffice().getId(),
        sale.getOffice() == null ? null : sale.getOffice().getName(),
        sale.getNotes(),
        sale.isOpen(),
        acceptedOfferId,
        totalOffered,
        sale.getCreatedAt(),
        sale.getUpdatedAt(),
        transitionsFor(sale.getStage()),
        offerRows,
        trail);
  }

  private void writeStageHistory(
      SalesOpportunity sale,
      SaleStage from,
      SaleStage to,
      String reason,
      User actor,
      LocalDateTime at) {
    SaleStageHistory row = new SaleStageHistory();
    row.setSale(sale);
    row.setFromStage(from);
    row.setToStage(to);
    row.setReason(reason == null ? null : reason.trim());
    row.setChangedBy(actor);
    row.setChangedAt(at);
    stageHistory.save(row);
  }

  private void recordAudit(
      User actor,
      String action,
      String description,
      SalesOpportunity sale,
      String previousValue,
      String newValue) {
    // The property and unit references are written through the audit entry's own columns: that is what
    // the property screen's activity tab reads a sale by.
    audit.recordEntityChange(
        actor.getId(),
        actor.getFullName(),
        AuditActions.SALE,
        action,
        description,
        "SALE",
        sale.getId(),
        sale.getSaleCode(),
        previousValue,
        newValue,
        referencesFor(sale));
  }

  private void recordAudit(User actor, String action, String description, SalesOpportunity sale) {
    recordAudit(actor, action, description, sale, null, null);
  }

  private void recordOfferAudit(
      User actor,
      String action,
      String description,
      SalesOpportunity sale,
      Offer offer,
      String previousValue,
      String newValue) {
    audit.recordEntityChange(
        actor.getId(),
        actor.getFullName(),
        AuditActions.SALE,
        action,
        description,
        "OFFER",
        offer.getId(),
        offer.getOfferNumber(),
        previousValue,
        newValue,
        referencesFor(sale));
  }

  /**
   * The audit entry's named references for a deal.
   *
   * <p>One helper, because the columns are positional and most of them are null: a single mis-ordered
   * call would file an entry against another asset while still looking correct.</p>
   */
  private static AuditRecordingService.EntityReferences referencesFor(SalesOpportunity sale) {
    return new AuditRecordingService.EntityReferences(
        sale.getProperty() == null ? null : sale.getProperty().getId(),
        sale.getUnit() == null ? null : sale.getUnit().getId(),
        null,
        null,
        null,
        null,
        null,
        null,
        null);
  }

  private Map<SaleStage, Long> countsByStage() {
    Map<SaleStage, Long> counts = new EnumMap<>(SaleStage.class);
    for (SalesOpportunityRepository.StageCount row : sales.countGroupedByStage()) {
      if (row == null || row.getStage() == null) continue;
      counts.merge(row.getStage(), row.getTotal(), Long::sum);
    }
    return counts;
  }

  /** The pipeline is scoped: a scoped caller sees only the deals they own. */
  private boolean visible(SalesOpportunity sale) {
    if (accessScope.isCompanyWide()) return true;
    UUID owner = sale.getOwnerAgent() == null ? null : sale.getOwnerAgent().getId();
    return accessScope.canSeeAgentWork(owner) || ownsSale(sale);
  }

  /**
   * A scoped caller also sees a deal they are the named agent on, which is how a sales agent finds
   * work assigned to them before an agent profile is in play.
   */
  private boolean ownsSale(SalesOpportunity sale) {
    if (sale.getOwnerAgent() == null || sale.getOwnerAgent().getUser() == null) {
      return false;
    }
    User current = accessScope.currentUser().orElse(null);
    return current != null && current.getId().equals(sale.getOwnerAgent().getUser().getId());
  }

  private void requireVisible(SalesOpportunity sale) {
    if (sale == null || accessScope.isCompanyWide()) return;
    if (ownsSale(sale)) return;
    accessScope.requireAgentOrCompanyWide(
        sale.getOwnerAgent() == null ? null : sale.getOwnerAgent().getId());
  }

  private boolean hasOpenOpportunity(Property property, Unit unit, Buyer buyer) {
    return sales.findAllByPropertyIdAndDeletedAtIsNull(property.getId()).stream()
        .filter(SalesOpportunity::isOpen)
        .filter(sale -> sale.getBuyer() != null && buyer.getId().equals(sale.getBuyer().getId()))
        .anyMatch(
            sale ->
                unit == null
                    ? sale.getUnit() == null
                    : sale.getUnit() != null && unit.getId().equals(sale.getUnit().getId()));
  }

  private static void assertUnitBelongs(Property property, Unit unit) {
    if (unit == null) return;
    if (property == null) {
      throw ApiException.badRequest("A unit cannot be set without its property");
    }
    if (unit.getProperty() == null || !property.getId().equals(unit.getProperty().getId())) {
      throw ApiException.badRequest("The unit does not belong to the chosen property");
    }
  }

  private Property requireProperty(UUID id) {
    return properties
        .findByIdAndDeletedAtIsNull(id)
        .orElseThrow(() -> ApiException.notFound(ErrorMessages.friendly("PROPERTY_NOT_FOUND")));
  }

  private Unit requireUnit(UUID id) {
    return units
        .findByIdAndDeletedAtIsNull(id)
        .orElseThrow(() -> ApiException.notFound(ErrorMessages.friendly("UNIT_NOT_FOUND")));
  }

  private Buyer requireBuyer(UUID id) {
    return buyers
        .findByIdAndDeletedAtIsNull(id)
        .orElseThrow(() -> ApiException.notFound(ErrorMessages.friendly("BUYER_NOT_FOUND")));
  }

  private Lead requireLead(UUID id) {
    return leads
        .findByIdAndDeletedAtIsNull(id)
        .orElseThrow(() -> ApiException.notFound(ErrorMessages.friendly("LEAD_NOT_FOUND")));
  }

  private Office requireOffice(UUID id) {
    return offices
        .findByIdAndDeletedAtIsNull(id)
        .orElseThrow(() -> ApiException.notFound(ErrorMessages.friendly("OFFICE_NOT_FOUND")));
  }

  private AgentProfile requireActiveAgent(UUID agentId) {
    AgentProfile agent =
        agents
            .findByIdAndDeletedAtIsNull(agentId)
            .orElseThrow(() -> ApiException.notFound(ErrorMessages.friendly("AGENT_NOT_FOUND")));
    if (agent.getStatus() != AgentStatus.ACTIVE) {
      throw ApiException.rule(
          "SALE_ASSIGN_INACTIVE_AGENT",
          "That agent is " + agent.getStatus() + " and cannot be given a live deal.");
    }
    return agent;
  }

  private static boolean matchesSearch(SalesOpportunity sale, String term) {
    return contains(sale.getSaleCode(), term)
        || contains(
            sale.getProperty() == null ? null : sale.getProperty().getName(), term)
        || contains(
            sale.getProperty() == null ? null : sale.getProperty().getPropertyCode(), term)
        || contains(sale.getUnit() == null ? null : sale.getUnit().getUnitCode(), term)
        || contains(sale.getBuyer() == null ? null : sale.getBuyer().getDisplayName(), term)
        || contains(sale.getNotes(), term);
  }

  private static boolean contains(String value, String term) {
    return value != null && value.toLowerCase(Locale.ROOT).contains(term);
  }

  private static boolean withinCreated(SalesOpportunity sale, LocalDate from, LocalDate to) {
    LocalDateTime createdAt = sale.getCreatedAt();
    if (from == null && to == null) return true;
    if (createdAt == null) return false;
    LocalDate date = createdAt.toLocalDate();
    if (from != null && date.isBefore(from)) return false;
    if (to != null && date.isAfter(to)) return false;
    return true;
  }

  private static BigDecimal moneyOrNull(BigDecimal value) {
    return value == null ? null : MoneyPolicy.money(value);
  }

  private static BigDecimal safe(BigDecimal value) {
    return value == null ? MoneyPolicy.zero() : value;
  }

  private static String enumName(Enum<?> value) {
    return value == null ? null : value.name();
  }

  private static SaleStage parseStage(String value) {
    try {
      return SaleStage.valueOf(value.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException ex) {
      throw ApiException.badRequest("'" + value + "' is not a sale stage");
    }
  }

  private static OfferStatus parseOfferStatus(String value) {
    try {
      return OfferStatus.valueOf(value.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException ex) {
      throw ApiException.badRequest("'" + value + "' is not an offer status");
    }
  }

  private static OfferDirection parseOfferDirection(String value) {
    if (value == null || value.isBlank()) return OfferDirection.BUYER;
    try {
      return OfferDirection.valueOf(value.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException ex) {
      throw ApiException.badRequest("'" + value + "' is not an offer direction");
    }
  }

  private static OfferAction parseOfferAction(String value) {
    if (value == null || value.isBlank()) {
      throw ApiException.badRequest("An offer decision must name what is being done with it");
    }
    try {
      return OfferAction.valueOf(value.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException ex) {
      throw ApiException.badRequest("'" + value + "' is not an offer decision");
    }
  }

  private static <T extends Comparable<? super T>> Comparator<T> nullSafe() {
    return Comparator.nullsLast(Comparator.naturalOrder());
  }

  private static Comparator<Offer> offerComparator(Pageable pageable) {
    Comparator<Offer> tieBreak =
        Comparator.comparing(Offer::getOfferedAt, nullSafe()).thenComparing(Offer::getId, nullSafe());
    Comparator<Offer> chosen = null;
    Sort sort = pageable == null ? Sort.unsorted() : pageable.getSort();
    for (Sort.Order order : sort) {
      Comparator<Offer> by = OFFER_SORTS.get(order.getProperty());
      if (by == null) continue;
      if (order.isDescending()) by = by.reversed();
      chosen = chosen == null ? by : chosen.thenComparing(by);
    }
    return chosen == null ? tieBreak.reversed() : chosen.thenComparing(tieBreak);
  }

  private static Comparator<SalesOpportunity> comparator(Pageable pageable) {
    Comparator<SalesOpportunity> tieBreak =
        Comparator.comparing(SalesOpportunity::getCreatedAt, nullSafe())
            .thenComparing(SalesOpportunity::getId, nullSafe());
    Comparator<SalesOpportunity> chosen = null;
    Sort sort = pageable == null ? Sort.unsorted() : pageable.getSort();
    for (Sort.Order order : sort) {
      Comparator<SalesOpportunity> by = SORTS.get(order.getProperty());
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
}
