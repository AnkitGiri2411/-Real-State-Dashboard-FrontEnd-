package com.meridian.realestate.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.meridian.realestate.config.AppProperties;
import com.meridian.realestate.domain.Lease;
import com.meridian.realestate.domain.Portfolio;
import com.meridian.realestate.domain.PortfolioProperty;
import com.meridian.realestate.domain.PortfolioStatus;
import com.meridian.realestate.domain.PortfolioStrategy;
import com.meridian.realestate.domain.Property;
import com.meridian.realestate.domain.UnitStatus;
import com.meridian.realestate.domain.User;
import com.meridian.realestate.dto.PortfolioDtos;
import com.meridian.realestate.exception.ApiException;
import com.meridian.realestate.repository.LeaseRepository;
import com.meridian.realestate.repository.OwnerRepository;
import com.meridian.realestate.repository.PortfolioPropertyRepository;
import com.meridian.realestate.repository.PortfolioRepository;
import com.meridian.realestate.repository.PropertyExpenseRepository;
import com.meridian.realestate.repository.PropertyRepository;
import com.meridian.realestate.repository.UnitRepository;
import com.meridian.realestate.repository.UserRepository;
import com.meridian.realestate.security.AccessScope;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;

/**
 * The portfolio register's rules and its performance formula, asserted without a database.
 *
 * <p>Three promises are pinned here. A property that is already in a book cannot be allocated again
 * ({@code PROPERTY_ALREADY_IN_PORTFOLIO}, 409), and nothing is written on the refused path. A book with
 * a zero valuation reports {@code 0.00} achieved yield - never {@code NaN} and never an exception. A
 * book with no leasable units reports {@code 0%} occupancy, for the same reason. Both guards are the
 * difference between a book that holds land reporting an honest zero and one that throws inside a
 * report.</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PortfolioService: allocation, and the guarded performance figures")
class PortfolioServiceTest {

  @Mock private PortfolioRepository portfolios;
  @Mock private PortfolioPropertyRepository allocations;
  @Mock private PropertyRepository properties;
  @Mock private UnitRepository units;
  @Mock private LeaseRepository leases;
  @Mock private PropertyExpenseRepository expenses;
  @Mock private OwnerRepository owners;
  @Mock private UserRepository users;
  @Mock private AuditRecordingService audit;
  @Mock private AccessScope accessScope;

  private AppProperties config;
  private PortfolioService service;

  @BeforeEach
  void setUp() {
    config = new AppProperties();
    service =
        new PortfolioService(
            portfolios,
            allocations,
            properties,
            units,
            leases,
            expenses,
            owners,
            users,
            audit,
            accessScope,
            config);
  }

  private static User actor() {
    User user = new User();
    user.setId(UUID.randomUUID());
    user.setFirstName("Priyanka");
    user.setLastName("Sethi");
    return user;
  }

  private static Portfolio portfolio(UUID id) {
    Portfolio portfolio = new Portfolio();
    portfolio.setId(id);
    portfolio.setPortfolioCode("ME-CORE");
    portfolio.setName("Meridian Core Income");
    portfolio.setStrategy(PortfolioStrategy.INCOME);
    portfolio.setStatus(PortfolioStatus.ACTIVE);
    portfolio.setTargetYieldPercent(new BigDecimal("6.50"));
    return portfolio;
  }

  private static Property property(UUID id, BigDecimal valuation) {
    Property property = new Property();
    property.setId(id);
    property.setPropertyCode("ME-P-00001");
    property.setName("Meridian Tower");
    property.setCurrentValuation(valuation);
    property.setAcquisitionValue(new BigDecimal("10000000.00"));
    return property;
  }

  private static PortfolioProperty member(Property property) {
    PortfolioProperty member = new PortfolioProperty();
    member.setId(UUID.randomUUID());
    member.setProperty(property);
    member.setAllocatedAt(LocalDate.now().minusYears(1));
    return member;
  }

  private void stubExpenseAndRentQueries(UUID propertyId) {
    when(units.countByPropertyIdAndLeasableTrueAndDeletedAtIsNull(propertyId)).thenReturn(0L);
    when(units.countByPropertyIdAndLeasableTrueAndStatusAndDeletedAtIsNull(propertyId, UnitStatus.OCCUPIED))
        .thenReturn(0L);
    when(leases.findAllByProperty_IdAndDeletedAtIsNull(eq(propertyId), any(Pageable.class)))
        .thenReturn(Page.<Lease>empty());
    when(expenses.sumOperatingExpensesForProperty(eq(propertyId), any(), any()))
        .thenReturn(BigDecimal.ZERO);
    when(expenses.sumCapitalExpensesForProperty(eq(propertyId), any(), any()))
        .thenReturn(BigDecimal.ZERO);
  }

  @Test
  @DisplayName("allocating a property that is already in a portfolio is refused")
  void allocatingAnAlreadyAllocatedPropertyIsRefused() {
    UUID portfolioId = UUID.randomUUID();
    UUID propertyId = UUID.randomUUID();
    when(accessScope.requireUser()).thenReturn(actor());
    when(portfolios.findByIdAndDeletedAtIsNull(portfolioId)).thenReturn(Optional.of(portfolio(portfolioId)));
    when(properties.findByIdAndDeletedAtIsNull(propertyId))
        .thenReturn(Optional.of(property(propertyId, new BigDecimal("1000000.00"))));
    when(allocations.findByPropertyIdAndDeallocatedAtIsNullAndDeletedAtIsNull(propertyId))
        .thenReturn(Optional.of(new PortfolioProperty()));

    ApiException refused =
        catchThrowableOfType(
            () ->
                service.allocate(
                    portfolioId, new PortfolioDtos.AllocatePropertyRequest(propertyId, null, null, null)),
            ApiException.class);

    assertThat(refused.getCode()).isEqualTo("PROPERTY_ALREADY_IN_PORTFOLIO");
    assertThat(refused.getStatus()).isEqualTo(HttpStatus.CONFLICT);
    // Checked before anything is written: no allocation row is created.
    verify(allocations, never()).save(any());
  }

  @Test
  @DisplayName("yield is 0, not NaN, when the portfolio's valuation is zero")
  void yieldIsZeroWhenValuationIsZero() {
    UUID portfolioId = UUID.randomUUID();
    Property property = property(UUID.randomUUID(), BigDecimal.ZERO);

    when(portfolios.findByIdAndDeletedAtIsNull(portfolioId)).thenReturn(Optional.of(portfolio(portfolioId)));
    when(allocations.findAllByPortfolioIdAndDeallocatedAtIsNullAndDeletedAtIsNull(portfolioId))
        .thenReturn(List.of(member(property)));
    stubExpenseAndRentQueries(property.getId());

    PortfolioDtos.PortfolioPerformance performance = service.performance(portfolioId);

    assertThat(performance.propertyCount()).isEqualTo(1);
    assertThat(performance.achievedYieldPercent()).isEqualByComparingTo("0.00");
    assertThat(performance.achievedYieldPercent().signum()).isZero();
    // The target is reported alongside the achieved figure, never in place of it.
    assertThat(performance.targetYieldPercent()).isEqualByComparingTo("6.50");
  }

  @Test
  @DisplayName("occupancy is 0, not NaN, when there are no leasable units")
  void occupancyIsZeroWhenThereAreNoLeasableUnits() {
    UUID portfolioId = UUID.randomUUID();
    Property property = property(UUID.randomUUID(), new BigDecimal("25000000.00"));

    when(portfolios.findByIdAndDeletedAtIsNull(portfolioId)).thenReturn(Optional.of(portfolio(portfolioId)));
    when(allocations.findAllByPortfolioIdAndDeallocatedAtIsNullAndDeletedAtIsNull(portfolioId))
        .thenReturn(List.of(member(property)));
    stubExpenseAndRentQueries(property.getId());

    PortfolioDtos.PortfolioPerformance performance = service.performance(portfolioId);

    assertThat(performance.occupancyPercent()).isZero();
    assertThat(performance.totalValuation()).isEqualByComparingTo("25000000.00");
    assertThat(performance.occupancyTargetPercent())
        .isEqualTo(config.getPortfolio().getOccupancyTargetPercent());
  }
}
