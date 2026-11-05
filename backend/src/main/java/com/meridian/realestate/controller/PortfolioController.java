package com.meridian.realestate.controller;

import com.meridian.realestate.dto.ApiResponse;
import com.meridian.realestate.dto.PageResponse;
import com.meridian.realestate.dto.PortfolioDtos;
import com.meridian.realestate.service.PortfolioService;
import com.meridian.realestate.web.PageRequestFactory;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The portfolio register and the assets allocated to each book.
 *
 * <p><b>Why this controller exists.</b> The register is one resource with a sub-resource that reads it
 * for a different purpose: the performance projection, which answers "what is this book worth and what
 * does it return" rather than "what does it hold". Allocation and deallocation sit under the book too,
 * because an asset's membership is the book's composition and not a record of its own.</p>
 *
 * <p><b>The rules it protects.</b> No business rule lives here. A property allocated twice, a closed
 * book with no reason, a period that runs backwards - all of those are decided in
 * {@link PortfolioService} and raised as {@link com.meridian.realestate.exception.ApiException}, which
 * {@code GlobalExceptionHandler} turns into the product's one error envelope. This class only routes,
 * validates the shape of the request body and wraps the answer in {@link ApiResponse}. No entity and
 * no repository ever appears here.</p>
 *
 * <p><b>Permissions.</b> Every endpoint names a code that exists in the {@code V2} seed -
 * {@code PORTFOLIO_VIEW}, {@code PORTFOLIO_CREATE}, {@code PORTFOLIO_UPDATE} and
 * {@code PORTFOLIO_DELETE}. Allocating and deallocating a property is a change to a book's composition
 * and is gated by {@code PORTFOLIO_UPDATE}, which is the capability the seed describes as "change a
 * portfolio or its allocation". No role name appears anywhere in this class.</p>
 */
@RestController
@RequestMapping("/api/v1/portfolios")
public class PortfolioController {

  private static final PageRequestFactory PAGES =
      PageRequestFactory.sortedBy("createdAt,desc")
          .allow("createdAt", "createdAt")
          .allow("updatedAt", "updatedAt")
          .allow("portfolioCode", "portfolioCode")
          .allow("name", "name")
          .allow("strategy", "strategy")
          .allow("status", "status")
          .allow("targetYieldPercent", "targetYieldPercent")
          .allow("inceptionDate", "inceptionDate")
          .build();

  private final PortfolioService portfolioService;

  public PortfolioController(PortfolioService portfolioService) {
    this.portfolioService = portfolioService;
  }

  /** The register, filtered by status, strategy, owner and manager, with free-text search. */
  @GetMapping
  @PreAuthorize("hasAuthority('PORTFOLIO_VIEW')")
  public ApiResponse<PageResponse<PortfolioDtos.PortfolioSummary>> list(
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String strategy,
      @RequestParam(required = false) UUID ownerId,
      @RequestParam(required = false) UUID managerId,
      @RequestParam(required = false) String search,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size,
      @RequestParam(required = false) String sort) {
    return ApiResponse.ok(
        PageResponse.from(
            portfolioService.list(status, strategy, ownerId, managerId, search, PAGES.toPageable(page, size, sort))));
  }

  /** One book, with the assets currently allocated to it. */
  @GetMapping("/{portfolioId}")
  @PreAuthorize("hasAuthority('PORTFOLIO_VIEW')")
  public ApiResponse<PortfolioDtos.PortfolioDetail> detail(@PathVariable UUID portfolioId) {
    return ApiResponse.ok(portfolioService.detail(portfolioId));
  }

  /**
   * One book's performance: what it holds, what it is worth, what it earns.
   *
   * <p>Every figure is read from a real query, every ratio is guarded against a zero denominator, and
   * the achieved yield is reported beside - never in place of - the book's target yield.</p>
   */
  @GetMapping("/{portfolioId}/performance")
  @PreAuthorize("hasAuthority('PORTFOLIO_VIEW')")
  public ApiResponse<PortfolioDtos.PortfolioPerformance> performance(@PathVariable UUID portfolioId) {
    return ApiResponse.ok(portfolioService.performance(portfolioId));
  }

  /** Create a book. */
  @PostMapping
  @PreAuthorize("hasAuthority('PORTFOLIO_CREATE')")
  public ApiResponse<PortfolioDtos.PortfolioDetail> create(
      @Valid @RequestBody PortfolioDtos.CreatePortfolioRequest request) {
    return ApiResponse.ok(portfolioService.create(request), "Portfolio created");
  }

  /** Change a book. */
  @PutMapping("/{portfolioId}")
  @PreAuthorize("hasAuthority('PORTFOLIO_UPDATE')")
  public ApiResponse<PortfolioDtos.PortfolioDetail> update(
      @PathVariable UUID portfolioId,
      @Valid @RequestBody PortfolioDtos.UpdatePortfolioRequest request) {
    return ApiResponse.ok(portfolioService.update(portfolioId, request));
  }

  /** Archive a book: a soft delete, refused when it has already been archived. */
  @PostMapping("/{portfolioId}/archive")
  @PreAuthorize("hasAuthority('PORTFOLIO_DELETE')")
  public ApiResponse<PortfolioDtos.PortfolioDetail> archive(@PathVariable UUID portfolioId) {
    return ApiResponse.ok(portfolioService.archive(portfolioId), "Portfolio archived");
  }

  /** Allocate a property to a book. A property already in a book is refused. */
  @PostMapping("/{portfolioId}/properties")
  @PreAuthorize("hasAuthority('PORTFOLIO_UPDATE')")
  public ApiResponse<PortfolioDtos.PortfolioDetail> allocate(
      @PathVariable UUID portfolioId,
      @Valid @RequestBody PortfolioDtos.AllocatePropertyRequest request) {
    return ApiResponse.ok(portfolioService.allocate(portfolioId, request), "Property allocated");
  }

  /** Deallocate a property from a book, closing its allocation period. */
  @DeleteMapping("/{portfolioId}/properties/{propertyId}")
  @PreAuthorize("hasAuthority('PORTFOLIO_UPDATE')")
  public ApiResponse<PortfolioDtos.PortfolioDetail> deallocate(
      @PathVariable UUID portfolioId, @PathVariable UUID propertyId) {
    return ApiResponse.ok(portfolioService.deallocate(portfolioId, propertyId), "Property deallocated");
  }
}
