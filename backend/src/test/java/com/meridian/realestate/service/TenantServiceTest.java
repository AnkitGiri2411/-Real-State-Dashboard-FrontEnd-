package com.meridian.realestate.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.meridian.realestate.domain.Lease;
import com.meridian.realestate.domain.LeaseStatus;
import com.meridian.realestate.domain.Tenant;
import com.meridian.realestate.domain.TenantStatus;
import com.meridian.realestate.domain.TenantType;
import com.meridian.realestate.domain.User;
import com.meridian.realestate.dto.TenantDtos;
import com.meridian.realestate.exception.ApiException;
import com.meridian.realestate.repository.LeaseRepository;
import com.meridian.realestate.repository.MaintenanceRequestRepository;
import com.meridian.realestate.repository.PaymentRepository;
import com.meridian.realestate.repository.TenantRepository;
import com.meridian.realestate.security.AccessScope;
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
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;

/**
 * The tenant register's rules, with every collaborator mocked and no database.
 *
 * <p>Two refusals are pinned here because each protects against a wrong record rather than a wrong
 * screen: a tenant that still carries a running lease cannot be archived ({@code
 * TENANT_HAS_RUNNING_LEASE}, 409), because archiving the party out from under a live tenancy would
 * strand the lease and the rent it produces; and a second profile sharing an e-mail address is
 * refused ({@code DUPLICATE_TENANT_EMAIL}), because two profiles for one person split their lease
 * history and make collections reconcile against the wrong party.</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TenantService: the tenant register's rules")
class TenantServiceTest {

  private static final UUID TENANT_ID = UUID.randomUUID();

  @Mock private TenantRepository tenantRepository;
  @Mock private LeaseRepository leaseRepository;
  @Mock private PaymentRepository paymentRepository;
  @Mock private MaintenanceRequestRepository maintenanceRepository;
  @Mock private BusinessNumberService businessNumbers;
  @Mock private AuditRecordingService audit;
  @Mock private AccessScope accessScope;

  private TenantService service;

  @BeforeEach
  void setUp() {
    service =
        new TenantService(
            tenantRepository,
            leaseRepository,
            paymentRepository,
            maintenanceRepository,
            businessNumbers,
            audit,
            accessScope);
  }

  private static User actor() {
    User user = new User();
    user.setId(UUID.randomUUID());
    user.setFirstName("Sneha");
    user.setLastName("Kulkarni");
    return user;
  }

  private static Tenant tenant() {
    Tenant tenant = new Tenant();
    tenant.setId(TENANT_ID);
    tenant.setTenantCode("ME-T-00001");
    tenant.setDisplayName("Meridian Retail LLP");
    tenant.setTenantType(TenantType.COMPANY);
    tenant.setStatus(TenantStatus.ACTIVE);
    return tenant;
  }

  private static TenantDtos.CreateTenantRequest createRequest(String email) {
    return new TenantDtos.CreateTenantRequest(
        "INDIVIDUAL",
        "A New Tenant",
        null,
        email,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null);
  }

  // ----------------------------------------------------------------- archive

  @Test
  @DisplayName("archiving a tenant with a running lease is refused with 409 TENANT_HAS_RUNNING_LEASE")
  void archivingATenantWithARunningLeaseIsRefused() {
    when(accessScope.requireUser()).thenReturn(actor());
    when(tenantRepository.findById(TENANT_ID)).thenReturn(Optional.of(tenant()));

    Lease running = new Lease();
    running.setId(UUID.randomUUID());
    running.setLeaseCode("ME-L-00001");
    running.setStatus(LeaseStatus.ACTIVE);
    running.setStartDate(LocalDate.of(2024, 1, 1));
    running.setEndDate(LocalDate.of(2025, 1, 1));
    when(leaseRepository.findAllByTenantIdAndDeletedAtIsNull(eq(TENANT_ID), any(Pageable.class)))
        .thenReturn(new PageImpl<>(List.of(running)));

    ApiException ex = catchThrowableOfType(() -> service.archive(TENANT_ID), ApiException.class);

    assertThat(ex.getCode()).isEqualTo("TENANT_HAS_RUNNING_LEASE");
    assertThat(ex.getStatus()).isEqualTo(HttpStatus.CONFLICT);
    verify(tenantRepository, never()).save(any());
  }

  // ------------------------------------------------------------- duplicate

  @Test
  @DisplayName("a duplicate tenant e-mail is refused with 409 DUPLICATE_TENANT_EMAIL")
  void aDuplicateTenantEmailIsRefused() {
    when(accessScope.requireUser()).thenReturn(actor());
    when(tenantRepository.findByEmailIgnoreCaseAndDeletedAtIsNull("taken@example.com"))
        .thenReturn(Optional.of(tenant()));

    ApiException ex =
        catchThrowableOfType(
            () -> service.create(createRequest("Taken@Example.com")), ApiException.class);

    assertThat(ex.getCode()).isEqualTo("DUPLICATE_TENANT_EMAIL");
    assertThat(ex.getStatus()).isEqualTo(HttpStatus.CONFLICT);
    verify(tenantRepository, never()).save(any());
  }
}
