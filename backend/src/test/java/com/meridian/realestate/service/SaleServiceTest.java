package com.meridian.realestate.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.meridian.realestate.domain.Buyer;
import com.meridian.realestate.domain.Offer;
import com.meridian.realestate.domain.OfferDirection;
import com.meridian.realestate.domain.OfferStatus;
import com.meridian.realestate.domain.Property;
import com.meridian.realestate.domain.SaleStage;
import com.meridian.realestate.domain.SaleStageHistory;
import com.meridian.realestate.domain.SalesOpportunity;
import com.meridian.realestate.domain.User;
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
import java.time.LocalDateTime;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

/**
 * The sale, its offers and its outcome, asserted without a database.
 *
 * <p>Four promises are pinned here, and each is one the schema states but cannot explain to a caller.
 * <b>At most one offer is accepted per sale</b>, checked before the acceptance is written so the
 * caller gets a named conflict rather than the partial unique index's opaque failure at flush time.
 * <b>Accepting an offer moves the sale's stage in the same transaction</b>, because an accepted offer
 * and a deal that still reads as an enquiry are two answers to one question. <b>A deal cannot be
 * closed without an agreed price</b> - the price is what was agreed, and a completion without one is
 * a number nobody can point at. And <b>the outcome columns are a single fact</b>: a deal that was lost
 * cannot also be closed as won, and the inverse holds too, so no row can carry both.</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SaleService: offers, stage moves and the outcome rules")
class SaleServiceTest {

  @Mock private SalesOpportunityRepository sales;
  @Mock private OfferRepository offers;
  @Mock private SaleStageHistoryRepository stageHistory;
  @Mock private PropertyRepository properties;
  @Mock private UnitRepository units;
  @Mock private BuyerRepository buyers;
  @Mock private LeadRepository leads;
  @Mock private AgentProfileRepository agents;
  @Mock private OfficeRepository offices;
  @Mock private BusinessNumberService numbers;
  @Mock private AuditRecordingService audit;
  @Mock private AccessScope accessScope;

  private SaleService service;

  @BeforeEach
  void setUp() {
    service =
        new SaleService(
            sales,
            offers,
            stageHistory,
            properties,
            units,
            buyers,
            leads,
            agents,
            offices,
            numbers,
            audit,
            accessScope);
  }

  // ---------------------------------------------------------------------------
  // Fixtures
  // ---------------------------------------------------------------------------

  private static User actor() {
    User user = new User();
    user.setId(UUID.randomUUID());
    user.setFirstName("Kavya");
    user.setLastName("Reddy");
    return user;
  }

  private static Property property() {
    Property property = new Property();
    property.setId(UUID.randomUUID());
    property.setPropertyCode("ME-P-00001");
    property.setName("Meridian House");
    return property;
  }

  private static Buyer buyer() {
    Buyer buyer = new Buyer();
    buyer.setId(UUID.randomUUID());
    buyer.setDisplayName("Ashwin Rao");
    return buyer;
  }

  private static SalesOpportunity sale(UUID id, SaleStage stage) {
    SalesOpportunity sale = new SalesOpportunity();
    sale.setId(id);
    sale.setSaleCode("ME-S-00001");
    sale.setProperty(property());
    sale.setBuyer(buyer());
    sale.setStage(stage);
    sale.setExpectedPrice(new BigDecimal("12000000.00"));
    sale.setCreatedAt(LocalDateTime.now().minusDays(5));
    return sale;
  }

  private static Offer offer(UUID id, SalesOpportunity sale, OfferStatus status, String amount) {
    Offer offer = new Offer();
    offer.setId(id);
    offer.setOfferNumber("ME-S-00002");
    offer.setSale(sale);
    offer.setAmount(new BigDecimal(amount));
    offer.setOfferedAt(LocalDateTime.now().minusDays(1));
    offer.setDirection(OfferDirection.BUYER);
    offer.setStatus(status);
    return offer;
  }

  private void stubActorVisible() {
    lenient().when(accessScope.requireUser()).thenReturn(actor());
    lenient().when(accessScope.isCompanyWide()).thenReturn(true);
  }

  private void stubSaleLoadable(SalesOpportunity sale) {
    when(sales.findByIdAndDeletedAtIsNull(sale.getId())).thenReturn(Optional.of(sale));
    lenient().when(sales.save(any(SalesOpportunity.class))).thenAnswer(call -> call.getArgument(0));
  }

  // ---------------------------------------------------------------------------
  // The map
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("the stage map is exactly the pipeline the schema states")
  void theStageMapIsExactlyThePipeline() {
    Map<SaleStage, Set<SaleStage>> expected = new EnumMap<>(SaleStage.class);
    expected.put(SaleStage.LEAD, EnumSet.of(SaleStage.OFFER, SaleStage.LOST));
    expected.put(
        SaleStage.OFFER, EnumSet.of(SaleStage.NEGOTIATION, SaleStage.UNDER_CONTRACT, SaleStage.LOST));
    expected.put(SaleStage.NEGOTIATION, EnumSet.of(SaleStage.UNDER_CONTRACT, SaleStage.LOST));
    expected.put(SaleStage.UNDER_CONTRACT, EnumSet.of(SaleStage.CLOSED, SaleStage.LOST));
    expected.put(SaleStage.CLOSED, EnumSet.noneOf(SaleStage.class));
    expected.put(SaleStage.LOST, EnumSet.noneOf(SaleStage.class));

    for (SaleStage stage : SaleStage.values()) {
      assertThat(SaleService.nextStages(stage))
          .as("the moves allowed from %s", stage)
          .containsExactlyInAnyOrderElementsOf(expected.get(stage));
    }

    assertThat(SaleService.canTransition(SaleStage.LEAD, SaleStage.OFFER)).isTrue();
    assertThat(SaleService.canTransition(SaleStage.OFFER, SaleStage.UNDER_CONTRACT)).isTrue();
    assertThat(SaleService.canTransition(SaleStage.NEGOTIATION, SaleStage.UNDER_CONTRACT)).isTrue();
    assertThat(SaleService.canTransition(SaleStage.UNDER_CONTRACT, SaleStage.CLOSED)).isTrue();

    // No skipping to the end, and nothing comes back from a concluded deal.
    assertThat(SaleService.canTransition(SaleStage.LEAD, SaleStage.CLOSED)).isFalse();
    assertThat(SaleService.canTransition(SaleStage.LEAD, SaleStage.UNDER_CONTRACT)).isFalse();
    assertThat(SaleService.canTransition(SaleStage.OFFER, SaleStage.CLOSED)).isFalse();
    assertThat(SaleService.canTransition(SaleStage.CLOSED, SaleStage.LOST)).isFalse();
    assertThat(SaleService.canTransition(SaleStage.LOST, SaleStage.CLOSED)).isFalse();
    assertThat(SaleService.canTransition(SaleStage.LEAD, SaleStage.LEAD)).isFalse();

    // Only an unanswered offer can be decided.
    assertThat(SaleService.canDecideOffer(OfferStatus.SUBMITTED, OfferStatus.ACCEPTED)).isTrue();
    assertThat(SaleService.canDecideOffer(OfferStatus.SUBMITTED, OfferStatus.COUNTERED)).isTrue();
    assertThat(SaleService.canDecideOffer(OfferStatus.SUBMITTED, OfferStatus.WITHDRAWN)).isTrue();
    assertThat(SaleService.canDecideOffer(OfferStatus.ACCEPTED, OfferStatus.REJECTED)).isFalse();
    assertThat(SaleService.canDecideOffer(OfferStatus.WITHDRAWN, OfferStatus.ACCEPTED)).isFalse();
    assertThat(SaleService.canDecideOffer(OfferStatus.REJECTED, OfferStatus.COUNTERED)).isFalse();
  }

  // ---------------------------------------------------------------------------
  // Offers
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("a second accepted offer on one sale is refused with OFFER_ALREADY_ACCEPTED")
  void aSecondAcceptedOfferIsRefused() {
    SalesOpportunity sale = sale(UUID.randomUUID(), SaleStage.OFFER);
    Offer alreadyAccepted = offer(UUID.randomUUID(), sale, OfferStatus.ACCEPTED, "11500000.00");
    Offer submitted = offer(UUID.randomUUID(), sale, OfferStatus.SUBMITTED, "11700000.00");

    stubActorVisible();
    when(offers.findByIdAndDeletedAtIsNull(submitted.getId())).thenReturn(Optional.of(submitted));
    stubSaleLoadable(sale);
    when(offers.findAcceptedForSale(sale.getId())).thenReturn(Optional.of(alreadyAccepted));

    ApiException refused =
        catchThrowableOfType(
            () ->
                service.decideOffer(
                    submitted.getId(), new SaleDtos.OfferDecisionRequest("ACCEPT", null, null, null)),
            ApiException.class);

    assertThat(refused.getCode()).isEqualTo("OFFER_ALREADY_ACCEPTED");
    assertThat(refused.getStatus()).isEqualTo(HttpStatus.CONFLICT);
    // Refused before anything is written: the second acceptance never reaches the database.
    verify(offers, never()).save(any());
    verify(sales, never()).save(any());
    assertThat(submitted.getStatus()).isEqualTo(OfferStatus.SUBMITTED);
  }

  @Test
  @DisplayName("accepting an offer marks it accepted and advances the sale's stage in one move")
  void acceptingAnOfferAdvancesTheSaleStage() {
    SalesOpportunity sale = sale(UUID.randomUUID(), SaleStage.OFFER);
    Offer submitted = offer(UUID.randomUUID(), sale, OfferStatus.SUBMITTED, "11700000.00");

    stubActorVisible();
    when(offers.findByIdAndDeletedAtIsNull(submitted.getId())).thenReturn(Optional.of(submitted));
    stubSaleLoadable(sale);
    when(offers.findAcceptedForSale(sale.getId())).thenReturn(Optional.empty());
    lenient().when(offers.save(any(Offer.class))).thenAnswer(call -> call.getArgument(0));

    service.decideOffer(
        submitted.getId(), new SaleDtos.OfferDecisionRequest("ACCEPT", null, null, "Accepted"));

    assertThat(submitted.getStatus()).isEqualTo(OfferStatus.ACCEPTED);
    assertThat(submitted.getRespondedAt()).isNotNull();
    // Terms agreed: the deal is under contract, not still being offered.
    assertThat(sale.getStage()).isEqualTo(SaleStage.UNDER_CONTRACT);

    ArgumentCaptor<SaleStageHistory> trail = ArgumentCaptor.forClass(SaleStageHistory.class);
    verify(stageHistory).save(trail.capture());
    assertThat(trail.getValue().getFromStage()).isEqualTo(SaleStage.OFFER);
    assertThat(trail.getValue().getToStage()).isEqualTo(SaleStage.UNDER_CONTRACT);
  }

  @Test
  @DisplayName("an offer that has already been decided cannot be decided again")
  void anAlreadyDecidedOfferCannotBeDecidedAgain() {
    SalesOpportunity sale = sale(UUID.randomUUID(), SaleStage.OFFER);
    Offer withdrawn = offer(UUID.randomUUID(), sale, OfferStatus.WITHDRAWN, "11700000.00");

    stubActorVisible();
    when(offers.findByIdAndDeletedAtIsNull(withdrawn.getId())).thenReturn(Optional.of(withdrawn));
    stubSaleLoadable(sale);
    // Nobody has accepted anything, so the refusal is about this offer's own state.
    when(offers.findAcceptedForSale(sale.getId())).thenReturn(Optional.empty());

    ApiException refused =
        catchThrowableOfType(
            () ->
                service.decideOffer(
                    withdrawn.getId(), new SaleDtos.OfferDecisionRequest("ACCEPT", null, null, null)),
            ApiException.class);

    assertThat(refused.getCode()).isEqualTo("OFFER_ALREADY_DECIDED");
    assertThat(refused.getStatus()).isEqualTo(HttpStatus.CONFLICT);
    verify(offers, never()).save(any());
  }

  @Test
  @DisplayName("recording the first offer moves a lead to OFFER, at the product's money scale")
  void recordingTheFirstOfferMovesALeadToOffer() {
    SalesOpportunity sale = sale(UUID.randomUUID(), SaleStage.LEAD);

    stubActorVisible();
    stubSaleLoadable(sale);
    when(numbers.next(BusinessNumberService.SALE)).thenReturn("ME-S-00007");
    lenient().when(offers.save(any(Offer.class))).thenAnswer(call -> call.getArgument(0));

    service.recordOffer(
        sale.getId(),
        new SaleDtos.RecordOfferRequest(new BigDecimal("11500000.005"), "BUYER", null, "Subject to survey"));

    assertThat(sale.getStage()).isEqualTo(SaleStage.OFFER);

    ArgumentCaptor<Offer> saved = ArgumentCaptor.forClass(Offer.class);
    verify(offers).save(saved.capture());
    // HALF_UP at the boundary, and stored at the money scale.
    assertThat(saved.getValue().getAmount()).isEqualByComparingTo("11500000.01");
    assertThat(saved.getValue().getAmount().scale()).isEqualTo(2);
    assertThat(saved.getValue().getStatus()).isEqualTo(OfferStatus.SUBMITTED);
    assertThat(saved.getValue().getDirection()).isEqualTo(OfferDirection.BUYER);

    ArgumentCaptor<SaleStageHistory> trail = ArgumentCaptor.forClass(SaleStageHistory.class);
    verify(stageHistory).save(trail.capture());
    assertThat(trail.getValue().getFromStage()).isEqualTo(SaleStage.LEAD);
    assertThat(trail.getValue().getToStage()).isEqualTo(SaleStage.OFFER);
  }

  @Test
  @DisplayName("a counter is a new offer in the firm's direction and takes the sale to NEGOTIATION")
  void counteringProducesANewOfferAndMovesTheStage() {
    SalesOpportunity sale = sale(UUID.randomUUID(), SaleStage.OFFER);
    Offer submitted = offer(UUID.randomUUID(), sale, OfferStatus.SUBMITTED, "11000000.00");

    stubActorVisible();
    when(offers.findByIdAndDeletedAtIsNull(submitted.getId())).thenReturn(Optional.of(submitted));
    stubSaleLoadable(sale);
    when(numbers.next(BusinessNumberService.SALE)).thenReturn("ME-S-00008");
    lenient().when(offers.save(any(Offer.class))).thenAnswer(call -> call.getArgument(0));

    service.decideOffer(
        submitted.getId(),
        new SaleDtos.OfferDecisionRequest("COUNTER", new BigDecimal("11600000.00"), null, "Too low"));

    assertThat(submitted.getStatus()).isEqualTo(OfferStatus.COUNTERED);
    assertThat(submitted.getRespondedAt()).isNotNull();
    assertThat(sale.getStage()).isEqualTo(SaleStage.NEGOTIATION);

    ArgumentCaptor<Offer> savedOffers = ArgumentCaptor.forClass(Offer.class);
    verify(offers, times(2)).save(savedOffers.capture());
    Offer counter = savedOffers.getAllValues().get(1);
    assertThat(counter.getDirection()).isEqualTo(OfferDirection.FIRM);
    assertThat(counter.getStatus()).isEqualTo(OfferStatus.SUBMITTED);
    assertThat(counter.getAmount()).isEqualByComparingTo("11600000.00");
    // An unanswered offer carries no response moment, as ck_offers_response requires.
    assertThat(counter.getRespondedAt()).isNull();
  }

  // ---------------------------------------------------------------------------
  // The outcome
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("closing a sale without an agreed price is refused")
  void closingWithoutAnAgreedPriceIsRefused() {
    SalesOpportunity sale = sale(UUID.randomUUID(), SaleStage.UNDER_CONTRACT);
    Offer accepted = offer(UUID.randomUUID(), sale, OfferStatus.ACCEPTED, "11700000.00");

    stubActorVisible();
    stubSaleLoadable(sale);
    when(offers.findAcceptedForSale(sale.getId())).thenReturn(Optional.of(accepted));

    ApiException refused =
        catchThrowableOfType(
            () ->
                service.transition(
                    sale.getId(),
                    new SaleDtos.SaleStageTransitionRequest("CLOSED", "signed", null, null)),
            ApiException.class);

    assertThat(refused.getCode()).isEqualTo("SALE_AMOUNT_REQUIRED");
    assertThat(refused.getStatus()).isEqualTo(HttpStatus.CONFLICT);
    verify(sales, never()).save(any());
    verify(stageHistory, never()).save(any());
    assertThat(sale.getStage()).isEqualTo(SaleStage.UNDER_CONTRACT);
  }

  @Test
  @DisplayName("closing a sale with nothing accepted is refused")
  void closingWithoutAnAcceptedOfferIsRefused() {
    SalesOpportunity sale = sale(UUID.randomUUID(), SaleStage.UNDER_CONTRACT);

    stubActorVisible();
    stubSaleLoadable(sale);
    when(offers.findAcceptedForSale(sale.getId())).thenReturn(Optional.empty());

    ApiException refused =
        catchThrowableOfType(
            () ->
                service.transition(
                    sale.getId(),
                    new SaleDtos.SaleStageTransitionRequest(
                        "CLOSED", "signed", new BigDecimal("11700000.00"), null)),
            ApiException.class);

    assertThat(refused.getCode()).isEqualTo("SALE_COMPLETION_REQUIRES_ACCEPTED_OFFER");
    assertThat(refused.getStatus()).isEqualTo(HttpStatus.CONFLICT);
    verify(sales, never()).save(any());
  }

  @Test
  @DisplayName("closing a sale writes the price and the moment together, at the money scale")
  void closingASaleWritesTheWholeOutcome() {
    SalesOpportunity sale = sale(UUID.randomUUID(), SaleStage.UNDER_CONTRACT);
    Offer accepted = offer(UUID.randomUUID(), sale, OfferStatus.ACCEPTED, "11700000.00");

    stubActorVisible();
    stubSaleLoadable(sale);
    when(offers.findAcceptedForSale(sale.getId())).thenReturn(Optional.of(accepted));

    service.transition(
        sale.getId(),
        new SaleDtos.SaleStageTransitionRequest(
            "CLOSED", "signed", new BigDecimal("11700000.005"), null));

    assertThat(sale.getStage()).isEqualTo(SaleStage.CLOSED);
    // HALF_UP at the boundary: the third decimal goes up rather than to even.
    assertThat(sale.getAgreedPrice()).isEqualByComparingTo("11700000.01");
    assertThat(sale.getAgreedPrice().scale()).isEqualTo(2);
    assertThat(sale.getClosedAt()).isNotNull();
    // The schema refuses a closed row carrying a loss, so the loss columns are cleared in the same
    // write rather than left as they were.
    assertThat(sale.getLostAt()).isNull();
    assertThat(sale.getLostReason()).isNull();

    // Declaring the deal won is a capability of its own, checked once the target is known.
    verify(accessScope).requirePermission("SALE_CLOSE");

    ArgumentCaptor<SaleStageHistory> trail = ArgumentCaptor.forClass(SaleStageHistory.class);
    verify(stageHistory).save(trail.capture());
    assertThat(trail.getValue().getToStage()).isEqualTo(SaleStage.CLOSED);
  }

  @Test
  @DisplayName("a sale that was lost cannot be closed as won, and the inverse is refused too")
  void theOutcomeColumnsAreOneFact() {
    // A deal already carrying a loss cannot be closed as won: the schema forbids a row with both.
    SalesOpportunity lostThenClosed = sale(UUID.randomUUID(), SaleStage.UNDER_CONTRACT);
    lostThenClosed.setLostAt(LocalDateTime.now().minusDays(2));
    lostThenClosed.setLostReason("Buyer withdrew");

    stubActorVisible();
    stubSaleLoadable(lostThenClosed);

    ApiException refused =
        catchThrowableOfType(
            () ->
                service.transition(
                    lostThenClosed.getId(),
                    new SaleDtos.SaleStageTransitionRequest(
                        "CLOSED", "signed", new BigDecimal("11700000.00"), null)),
            ApiException.class);

    assertThat(refused.getCode()).isEqualTo("SALE_TRANSITION_NOT_ALLOWED");
    assertThat(refused.getStatus()).isEqualTo(HttpStatus.CONFLICT);
    verify(sales, never()).save(any());

    // The inverse: a deal that completed at an agreed price cannot be recorded as lost.
    SalesOpportunity closedThenLost = sale(UUID.randomUUID(), SaleStage.UNDER_CONTRACT);
    closedThenLost.setClosedAt(LocalDateTime.now().minusDays(2));
    closedThenLost.setAgreedPrice(new BigDecimal("11700000.00"));

    clearInvocations(sales);
    stubActorVisible();
    stubSaleLoadable(closedThenLost);

    ApiException inverse =
        catchThrowableOfType(
            () ->
                service.transition(
                    closedThenLost.getId(),
                    new SaleDtos.SaleStageTransitionRequest("LOST", "backed out", null, "cold feet")),
            ApiException.class);

    assertThat(inverse.getCode()).isEqualTo("SALE_TRANSITION_NOT_ALLOWED");
    assertThat(inverse.getStatus()).isEqualTo(HttpStatus.CONFLICT);
    verify(sales, never()).save(any());

    // And the freeze on each concluded stage, which is the same rule seen from the other side.
    SalesOpportunity alreadyClosed = sale(UUID.randomUUID(), SaleStage.CLOSED);
    stubSaleLoadable(alreadyClosed);
    ApiException fromClosed =
        catchThrowableOfType(
            () ->
                service.transition(
                    alreadyClosed.getId(),
                    new SaleDtos.SaleStageTransitionRequest("LOST", "too late", null, "changed mind")),
            ApiException.class);
    assertThat(fromClosed.getCode()).isEqualTo("SALE_TRANSITION_NOT_ALLOWED");
    assertThat(fromClosed.getStatus()).isEqualTo(HttpStatus.CONFLICT);
    verify(sales, never()).save(any());
  }

  @Test
  @DisplayName("moving a live sale on clears the outcome columns the schema requires to be null")
  void movingALiveSaleOnClearsTheOutcomeColumns() {
    SalesOpportunity sale = sale(UUID.randomUUID(), SaleStage.OFFER);
    // An inconsistent row, as if an earlier write half-applied an outcome.
    sale.setAgreedPrice(new BigDecimal("1.00"));
    sale.setClosedAt(LocalDateTime.now().minusDays(1));

    stubActorVisible();
    stubSaleLoadable(sale);

    service.transition(
        sale.getId(), new SaleDtos.SaleStageTransitionRequest("NEGOTIATION", "countered", null, null));

    assertThat(sale.getStage()).isEqualTo(SaleStage.NEGOTIATION);
    assertThat(sale.getAgreedPrice()).isNull();
    assertThat(sale.getClosedAt()).isNull();
    assertThat(sale.getLostAt()).isNull();
    assertThat(sale.getLostReason()).isNull();

    ArgumentCaptor<SaleStageHistory> trail = ArgumentCaptor.forClass(SaleStageHistory.class);
    verify(stageHistory).save(trail.capture());
    assertThat(trail.getValue().getFromStage()).isEqualTo(SaleStage.OFFER);
    assertThat(trail.getValue().getToStage()).isEqualTo(SaleStage.NEGOTIATION);
  }

  @Test
  @DisplayName("the pipeline counts every stage and values only the deals still being worked")
  void thePipelineExcludesConcludedDealsFromItsValue() {
    SalesOpportunityRepository.StageCount lead = mock(SalesOpportunityRepository.StageCount.class);
    when(lead.getStage()).thenReturn(SaleStage.LEAD);
    when(lead.getTotal()).thenReturn(2L);
    SalesOpportunityRepository.StageCount closed = mock(SalesOpportunityRepository.StageCount.class);
    when(closed.getStage()).thenReturn(SaleStage.CLOSED);
    when(closed.getTotal()).thenReturn(1L);

    when(sales.countGroupedByStage()).thenReturn(List.of(lead, closed));
    when(sales.sumOpenPipelineValue()).thenReturn(new BigDecimal("25000000.00"));

    SaleDtos.PipelineResponse pipeline = service.pipeline();

    assertThat(pipeline.totalSales()).isEqualTo(3);
    assertThat(pipeline.openSales()).isEqualTo(2);
    assertThat(pipeline.openPipelineValue()).isEqualByComparingTo("25000000.00");
    assertThat(pipeline.stages()).hasSize(SaleStage.values().length);
    assertThat(
            pipeline.stages().stream()
                .filter(bucket -> bucket.stage().equals("CLOSED"))
                .findFirst()
                .orElseThrow()
                .open())
        .isFalse();
  }
}
