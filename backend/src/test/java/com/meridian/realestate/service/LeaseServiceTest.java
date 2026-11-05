package com.meridian.realestate.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.meridian.realestate.config.AppProperties;
import com.meridian.realestate.domain.Lease;
import com.meridian.realestate.domain.LeaseRenewal;
import com.meridian.realestate.domain.LeaseRenewalStatus;
import com.meridian.realestate.domain.LeaseStatus;
import com.meridian.realestate.domain.LeaseTerm;
import com.meridian.realestate.domain.Unit;
import com.meridian.realestate.domain.User;
import com.meridian.realestate.dto.LeaseDtos;
import com.meridian.realestate.exception.ApiException;
import com.meridian.realestate.repository.LeaseRenewalRepository;
import com.meridian.realestate.repository.LeaseRepository;
import com.meridian.realestate.repository.LeaseStatusHistoryRepository;
import com.meridian.realestate.repository.LeaseTermRepository;
import com.meridian.realestate.repository.OwnerRepository;
import com.meridian.realestate.repository.PaymentAllocationRepository;
import com.meridian.realestate.repository.PaymentRepository;
import com.meridian.realestate.repository.PropertyRepository;
import com.meridian.realestate.repository.RentInvoiceRepository;
import com.meridian.realestate.repository.TenantRepository;
import com.meridian.realestate.repository.UnitRepository;
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
import org.springframework.http.HttpStatus;

/**
 * The lease's own rules, with the repositories mocked and no database.
 *
 * <p>Three of these cannot be enforced by the schema the way the refusal needs to read, so they live
 * in the service and are pinned here: one running lease per unit (the partial unique index backs it
 * up, but the refusal is a named {@code UNIT_ALREADY_LET}); an explicit transition map (an illegal
 * move is a named {@code LEASE_TRANSITION_NOT_ALLOWED}, not a raw constraint error from
 * {@code ck_leases_lifecycle}); and a rent schedule whose periods cannot overlap. A renewal may only
 * be offered on a lease that is still running.</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("LeaseService: occupancy, the state machine and the rent schedule")
class LeaseServiceTest {

  @Mock private LeaseRepository leases;
  @Mock private LeaseTermRepository terms;
  @Mock private LeaseRenewalRepository renewals;
  @Mock private LeaseStatusHistoryRepository statusHistory;
  @Mock private PropertyRepository properties;
  @Mock private UnitRepository units;
  @Mock private TenantRepository tenants;
  @Mock private OwnerRepository owners;
  @Mock private RentInvoiceRepository invoices;
  @Mock private PaymentAllocationRepository allocations;
  @Mock private PaymentRepository payments;
  @Mock private BusinessNumberService numbers;
  @Mock private AuditRecordingService audit;
  @Mock private AccessScope accessScope;

  private LeaseService service;

  @BeforeEach
  void setUp() {
    service =
        new LeaseService(
            leases,
            terms,
            renewals,
            statusHistory,
            properties,
            units,
            tenants,
            owners,
            invoices,
            allocations,
            payments,
            numbers,
            audit,
            accessScope,
            new AppProperties());
  }

  private static User actor() {
    User user = new User();
    user.setId(UUID.randomUUID());
    user.setFirstName("Sneha");
    user.setLastName("Kulkarni");
    return user;
  }

  private static Lease lease(UUID id, LeaseStatus status) {
    Lease lease = new Lease();
    lease.setId(id);
    lease.setLeaseCode("ME-L-00001");
    lease.setStatus(status);
    return lease;
  }

  @Test
  @DisplayName("a lease cannot be created when the unit already has a running lease (UNIT_ALREADY_LET)")
  void aLeaseCannotBeCreatedWhenTheUnitIsAlreadyLet() {
    when(accessScope.requireUser()).thenReturn(actor());

    Unit unit = new Unit();
    unit.setId(UUID.randomUUID());
    when(units.findByIdAndDeletedAtIsNull(unit.getId())).thenReturn(Optional.of(unit));

    Lease running = lease(UUID.randomUUID(), LeaseStatus.ACTIVE);
    when(leases.findRunningLeaseForUnit(unit.getId())).thenReturn(Optional.of(running));

    LeaseDtos.CreateLeaseRequest request =
        new LeaseDtos.CreateLeaseRequest(
            null,
            unit.getId(),
            UUID.randomUUID(),
            null,
            LocalDate.of(2026, 1, 1),
            LocalDate.of(2026, 12, 31),
            new BigDecimal("5000.00"),
            "MONTHLY",
            null,
            null,
            null,
            null,
            null);

    assertThatThrownBy(() -> service.createLease(request))
        .isInstanceOf(ApiException.class)
        .satisfies(
            ex -> {
              ApiException api = (ApiException) ex;
              assertThat(api.getCode()).isEqualTo("UNIT_ALREADY_LET");
              assertThat(api.getStatus()).isEqualTo(HttpStatus.CONFLICT);
            });
  }

  @Test
  @DisplayName("an illegal lease status transition is refused (LEASE_TRANSITION_NOT_ALLOWED)")
  void anIllegalLeaseStatusTransitionIsRefused() {
    // The map itself: a draft may be activated, but not jumped straight to RENEWED.
    assertThat(LeaseService.isAllowed(LeaseStatus.DRAFT, LeaseStatus.ACTIVE)).isTrue();
    assertThat(LeaseService.isAllowed(LeaseStatus.DRAFT, LeaseStatus.RENEWED)).isFalse();
    assertThat(LeaseService.isAllowed(LeaseStatus.TERMINATED, LeaseStatus.ACTIVE)).isFalse();

    when(accessScope.requireUser()).thenReturn(actor());
    UUID id = UUID.randomUUID();
    Lease lease = lease(id, LeaseStatus.DRAFT);
    when(leases.findByIdAndDeletedAtIsNull(id)).thenReturn(Optional.of(lease));

    LeaseDtos.ChangeStatusRequest request = new LeaseDtos.ChangeStatusRequest("RENEWED", "no");

    assertThatThrownBy(() -> service.changeStatus(id, request))
        .isInstanceOf(ApiException.class)
        .satisfies(
            ex -> {
              ApiException api = (ApiException) ex;
              assertThat(api.getCode()).isEqualTo("LEASE_TRANSITION_NOT_ALLOWED");
              assertThat(api.getStatus()).isEqualTo(HttpStatus.CONFLICT);
            });
  }

  @Test
  @DisplayName("a lease cannot end before it starts (LEASE_END_BEFORE_START)")
  void aLeaseCannotEndBeforeItStarts() {
    when(accessScope.requireUser()).thenReturn(actor());

    LeaseDtos.CreateLeaseRequest request =
        new LeaseDtos.CreateLeaseRequest(
            null,
            UUID.randomUUID(),
            UUID.randomUUID(),
            null,
            LocalDate.of(2026, 6, 10),
            LocalDate.of(2026, 6, 1),
            new BigDecimal("5000.00"),
            "MONTHLY",
            null,
            null,
            null,
            null,
            null);

    assertThatThrownBy(() -> service.createLease(request))
        .isInstanceOf(ApiException.class)
        .satisfies(
            ex -> {
              ApiException api = (ApiException) ex;
              assertThat(api.getCode()).isEqualTo("LEASE_END_BEFORE_START");
              assertThat(api.getStatus()).isEqualTo(HttpStatus.CONFLICT);
            });
  }

  @Test
  @DisplayName("a rent revision overlapping an existing term is refused (LEASE_TERM_OVERLAP)")
  void anOverlappingRentTermIsRefused() {
    when(accessScope.requireUser()).thenReturn(actor());
    UUID id = UUID.randomUUID();
    Lease lease = lease(id, LeaseStatus.ACTIVE);
    when(leases.findByIdAndDeletedAtIsNull(id)).thenReturn(Optional.of(lease));

    LeaseTerm open = new LeaseTerm();
    open.setEffectiveFrom(LocalDate.of(2026, 1, 1));
    open.setEffectiveTo(null); // an open-ended term, so anything from Jan 1 onward overlaps it
    when(terms.findAllByLease_IdAndDeletedAtIsNullOrderByTermNumberAsc(id)).thenReturn(List.of(open));

    LeaseDtos.CreateTermRequest request =
        new LeaseDtos.CreateTermRequest(
            LocalDate.of(2026, 6, 1), null, new BigDecimal("6000.00"), null, null);

    assertThatThrownBy(() -> service.createTerm(id, request))
        .isInstanceOf(ApiException.class)
        .satisfies(
            ex -> {
              ApiException api = (ApiException) ex;
              assertThat(api.getCode()).isEqualTo("LEASE_TERM_OVERLAP");
              assertThat(api.getStatus()).isEqualTo(HttpStatus.CONFLICT);
            });
  }

  @Test
  @DisplayName("a renewal cannot be offered on a lease that is not running (LEASE_RENEWAL_NOT_OFFERED)")
  void aRenewalCannotBeOfferedOnANonRunningLease() {
    when(accessScope.requireUser()).thenReturn(actor());

    Lease ended = lease(UUID.randomUUID(), LeaseStatus.TERMINATED);
    LeaseRenewal renewal = new LeaseRenewal();
    renewal.setId(UUID.randomUUID());
    renewal.setLease(ended);
    renewal.setStatus(LeaseRenewalStatus.PENDING);
    when(renewals.findByIdAndDeletedAtIsNull(renewal.getId())).thenReturn(Optional.of(renewal));

    LeaseDtos.OfferRenewalRequest request =
        new LeaseDtos.OfferRenewalRequest(
            new BigDecimal("6000.00"), LocalDate.of(2026, 4, 1), LocalDate.of(2027, 3, 31));

    assertThatThrownBy(() -> service.offerRenewal(renewal.getId(), request))
        .isInstanceOf(ApiException.class)
        .satisfies(
            ex -> {
              ApiException api = (ApiException) ex;
              assertThat(api.getCode()).isEqualTo("LEASE_RENEWAL_NOT_OFFERED");
              assertThat(api.getStatus()).isEqualTo(HttpStatus.CONFLICT);
            });
  }
}
