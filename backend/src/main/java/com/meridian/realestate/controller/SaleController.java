package com.meridian.realestate.controller;

import com.meridian.realestate.dto.ApiResponse;
import com.meridian.realestate.dto.PageResponse;
import com.meridian.realestate.dto.SaleDtos;
import com.meridian.realestate.service.SaleService;
import com.meridian.realestate.web.PageRequestFactory;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The capital side of the business: sales opportunities, the offers made on them and the pipeline.
 *
 * <p><b>Why the offers live in this controller.</b> An offer has no life outside the sale it is made
 * on, and this class is mapped at {@code /api/v1} rather than at a single resource path so that the
 * sale's own address ({@code /sales}) and the offer's ({@code /offers}) are served together. A caller
 * records an offer on a sale - {@code POST /sales/{id}/offers} - and then decides it by its own id -
 * {@code PATCH /offers/{offerId}} - which is why the two paths exist: the second does not need the
 * sale carried around to revise one step of the negotiation. Splitting them into two controllers would
 * put the sale, its offers and its stage in three places, which is exactly how a negotiation's
 * sequence and its stage stop agreeing.</p>
 *
 * <p><b>Note the shape of this controller: there is no endpoint that writes a stage from a request
 * field of its own.</b> A sale moves only through {@code POST /sales/{id}/stage}, which validates the
 * move against the service's stage map and writes the whole outcome - agreed price and closure, or
 * loss moment and reason - in one write, because the schema refuses a half-applied outcome and refuses
 * an outcome on a stage that should not carry one. The closing move additionally requires
 * {@code SALE_CLOSE}, checked in the service once the target is known, so an account that may move a
 * deal along is not thereby allowed to declare it won.</p>
 *
 * <p><b>Every endpoint carries a permission code that exists in the {@code V2} seed</b>:
 * {@code SALE_VIEW}, {@code SALE_CREATE}, {@code SALE_UPDATE}, {@code SALE_OFFER} and - through the
 * service - {@code SALE_CLOSE}. No role name appears here, so changing what a role may do is a data
 * change and never a code change.</p>
 *
 * <p>Sorting is honoured through {@link PageRequestFactory}: a caller may sort by the fields the
 * register offers and by nothing else, and an unsupported field is answered with a 400 that names the
 * accepted ones rather than a 500.</p>
 */
@RestController
@RequestMapping("/api/v1")
public class SaleController {

  private static final PageRequestFactory PAGES =
      PageRequestFactory.sortedBy("createdAt,desc")
          .allow("createdAt", "createdAt")
          .allow("updatedAt", "updatedAt")
          .allow("saleCode", "saleCode")
          .allow("stage", "stage")
          .allow("expectedPrice", "expectedPrice")
          .allow("askingPrice", "askingPrice")
          .allow("agreedPrice", "agreedPrice")
          .allow("expectedCloseDate", "expectedCloseDate")
          .allow("property", "property.name")
          .allow("buyer", "buyer.name")
          .build();

  /** The offer register is its own query, so it offers only fields an offer actually has. */
  private static final PageRequestFactory OFFER_PAGES =
      PageRequestFactory.sortedBy("offeredAt,desc")
          .allow("offeredAt", "offeredAt")
          .allow("amount", "amount")
          .allow("status", "status")
          .allow("direction", "direction")
          .allow("offerNumber", "offerNumber")
          .build();

  private final SaleService saleService;

  public SaleController(SaleService saleService) {
    this.saleService = saleService;
  }

  /** The pipeline register, filtered by stage, asset, buyer, owner and creation date. */
  @GetMapping("/sales")
  @PreAuthorize("hasAuthority('SALE_VIEW')")
  public ApiResponse<PageResponse<SaleDtos.SaleSummary>> list(
      @RequestParam(required = false) String stage,
      @RequestParam(required = false) UUID propertyId,
      @RequestParam(required = false) UUID unitId,
      @RequestParam(required = false) UUID buyerId,
      @RequestParam(required = false) UUID ownerAgentId,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate createdFrom,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate createdTo,
      @RequestParam(required = false) Boolean openOnly,
      @RequestParam(required = false) String search,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size,
      @RequestParam(required = false) String sort) {
    return ApiResponse.ok(
        saleService.list(
            stage,
            propertyId,
            unitId,
            buyerId,
            ownerAgentId,
            createdFrom,
            createdTo,
            openOnly,
            search,
            PAGES.toPageable(page, size, sort)));
  }

  /** Counts per stage and the value of the live pipeline. */
  @GetMapping("/sales/pipeline")
  @PreAuthorize("hasAuthority('SALE_VIEW')")
  public ApiResponse<SaleDtos.PipelineResponse> pipeline() {
    return ApiResponse.ok(saleService.pipeline());
  }

  /** The sales card: the live pipeline and what has actually completed. */
  @GetMapping("/sales/summary")
  @PreAuthorize("hasAuthority('SALE_VIEW')")
  public ApiResponse<SaleDtos.SalesSummary> summary() {
    return ApiResponse.ok(saleService.summary());
  }

  /** One sale, with its offers and its stage trail. */
  @GetMapping("/sales/{saleId}")
  @PreAuthorize("hasAuthority('SALE_VIEW')")
  public ApiResponse<SaleDtos.SaleDetail> detail(@PathVariable UUID saleId) {
    return ApiResponse.ok(saleService.detail(saleId));
  }

  /** Open a sale. Every sale opens at LEAD. */
  @PostMapping("/sales")
  @PreAuthorize("hasAuthority('SALE_CREATE')")
  public ApiResponse<SaleDtos.SaleDetail> create(
      @Valid @RequestBody SaleDtos.CreateSaleRequest request) {
    return ApiResponse.ok(saleService.create(request), "Sale opened");
  }

  /** Change a sale's commercial details. A concluded deal is refused. */
  @PutMapping("/sales/{saleId}")
  @PreAuthorize("hasAuthority('SALE_UPDATE')")
  public ApiResponse<SaleDtos.SaleDetail> update(
      @PathVariable UUID saleId, @Valid @RequestBody SaleDtos.UpdateSaleRequest request) {
    return ApiResponse.ok(saleService.update(saleId, request));
  }

  /** Move a sale between stages, writing the outcome when the move concludes it. */
  @PostMapping("/sales/{saleId}/stage")
  @PreAuthorize("hasAuthority('SALE_UPDATE')")
  public ApiResponse<SaleDtos.SaleDetail> transition(
      @PathVariable UUID saleId, @Valid @RequestBody SaleDtos.SaleStageTransitionRequest request) {
    return ApiResponse.ok(saleService.transition(saleId, request), "Sale stage changed");
  }

  /** The offers made across the pipeline, filtered by sale, status and direction. */
  @GetMapping("/offers")
  @PreAuthorize("hasAuthority('SALE_VIEW')")
  public ApiResponse<PageResponse<SaleDtos.OfferEntry>> offers(
      @RequestParam(required = false) UUID saleId,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String direction,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size,
      @RequestParam(required = false) String sort) {
    return ApiResponse.ok(
        saleService.offers(saleId, status, direction, OFFER_PAGES.toPageable(page, size, sort)));
  }

  /** Record an offer on a sale. The first offer moves the sale out of LEAD. */
  @PostMapping("/sales/{saleId}/offers")
  @PreAuthorize("hasAuthority('SALE_OFFER')")
  public ApiResponse<SaleDtos.SaleDetail> recordOffer(
      @PathVariable UUID saleId, @Valid @RequestBody SaleDtos.RecordOfferRequest request) {
    return ApiResponse.ok(saleService.recordOffer(saleId, request), "Offer recorded");
  }

  /** Accept, counter, reject or withdraw an offer. Acceptance moves the sale's stage with it. */
  @PatchMapping("/offers/{offerId}")
  @PreAuthorize("hasAuthority('SALE_OFFER')")
  public ApiResponse<SaleDtos.SaleDetail> decideOffer(
      @PathVariable UUID offerId, @Valid @RequestBody SaleDtos.OfferDecisionRequest request) {
    return ApiResponse.ok(saleService.decideOffer(offerId, request), "Offer decided");
  }
}
