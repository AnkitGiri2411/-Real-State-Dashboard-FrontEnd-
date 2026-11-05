package com.meridian.realestate.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.meridian.realestate.domain.MaintenanceCategory;
import com.meridian.realestate.domain.User;
import com.meridian.realestate.domain.Vendor;
import com.meridian.realestate.domain.VendorCategory;
import com.meridian.realestate.domain.VendorStatus;
import com.meridian.realestate.dto.PageResponse;
import com.meridian.realestate.dto.VendorDtos;
import com.meridian.realestate.exception.ApiException;
import com.meridian.realestate.repository.VendorRepository;
import com.meridian.realestate.security.AccessScope;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;

/**
 * The vendor register, asserted without a database.
 *
 * <p>Two promises are pinned here. <b>Approval is per trade and it is not the same as being
 * available</b>: the rule the maintenance service asks before handing work over requires an
 * empanelled vendor, of the request's own category, with a status that permits new work - so a
 * suspended plumber is refused even though it is on the approved list, and an empanelled electrician
 * is refused a plumbing fault. <b>Archiving is soft and once</b>: a second archive is refused rather
 * than silently re-stamped, because the work the vendor already did has to stay readable.</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("VendorService: empanelment, the trade match, and archiving once")
class VendorServiceTest {

  @Mock private VendorRepository vendors;
  @Mock private BusinessNumberService numbers;
  @Mock private AuditRecordingService audit;
  @Mock private AccessScope accessScope;

  private VendorService service;

  @BeforeEach
  void setUp() {
    service = new VendorService(vendors, numbers, audit, accessScope);
  }

  private static User actor() {
    User user = new User();
    user.setId(UUID.randomUUID());
    user.setFirstName("Priyanka");
    user.setLastName("Sethi");
    return user;
  }

  private static Vendor vendor(VendorCategory category, boolean empanelled, VendorStatus status) {
    Vendor vendor = new Vendor();
    vendor.setId(UUID.randomUUID());
    vendor.setVendorCode("ME-M-00009");
    vendor.setName("Aqua Plumbing Works");
    vendor.setCategory(category);
    vendor.setEmpanelled(empanelled);
    vendor.setStatus(status);
    return vendor;
  }

  @Test
  @DisplayName("available for a trade means: empanelled, active, and of that trade")
  void theEmpanelmentRuleNeedsAllThreeFacts() {
    Vendor plumber = vendor(VendorCategory.PLUMBING, true, VendorStatus.ACTIVE);
    assertThat(VendorService.isAvailableFor(plumber, MaintenanceCategory.PLUMBING)).isTrue();

    // On the approved list, but stopped for cause: no new work.
    Vendor suspended = vendor(VendorCategory.PLUMBING, true, VendorStatus.SUSPENDED);
    assertThat(VendorService.isAvailableFor(suspended, MaintenanceCategory.PLUMBING)).isFalse();

    // No longer engaged.
    Vendor inactive = vendor(VendorCategory.PLUMBING, true, VendorStatus.INACTIVE);
    assertThat(VendorService.isAvailableFor(inactive, MaintenanceCategory.PLUMBING)).isFalse();

    // Not on the approved list at all.
    Vendor unapproved = vendor(VendorCategory.PLUMBING, false, VendorStatus.ACTIVE);
    assertThat(VendorService.isAvailableFor(unapproved, MaintenanceCategory.PLUMBING)).isFalse();

    // Approved, active, and the wrong trade.
    Vendor electrician = vendor(VendorCategory.ELECTRICAL, true, VendorStatus.ACTIVE);
    assertThat(VendorService.isAvailableFor(electrician, MaintenanceCategory.PLUMBING)).isFalse();

    // A vendor with no trade recorded cannot be matched to anything.
    Vendor untraded = vendor(null, true, VendorStatus.ACTIVE);
    assertThat(VendorService.isAvailableFor(untraded, MaintenanceCategory.PLUMBING)).isFalse();

    assertThat(VendorService.isAvailableFor(null, MaintenanceCategory.PLUMBING)).isFalse();
    assertThat(VendorService.isAvailableFor(plumber, null)).isFalse();
  }

  @Test
  @DisplayName("creating a vendor mints its code and stores the e-mail lower-cased")
  void creatingAVendorMintsItsCode() {
    when(accessScope.requireUser()).thenReturn(actor());
    when(numbers.next(BusinessNumberService.MAINTENANCE)).thenReturn("ME-M-00042");
    when(vendors.save(any(Vendor.class))).thenAnswer(call -> call.getArgument(0));

    service.create(
        new VendorDtos.CreateVendorRequest(
            "Aqua Plumbing Works",
            "PLUMBING",
            "Suresh Nair",
            "+91-98200-30001",
            null,
            "Suresh@Example.COM",
            null,
            null,
            null,
            null,
            "Mumbai",
            "Maharashtra",
            null,
            null,
            // Empanelment is stated rather than assumed.
            true,
            4,
            30,
            "ACTIVE",
            null));

    ArgumentCaptor<Vendor> saved = ArgumentCaptor.forClass(Vendor.class);
    verify(vendors).save(saved.capture());
    assertThat(saved.getValue().getVendorCode()).isEqualTo("ME-M-00042");
    // ck_vendors_email_lower requires it, and a mixed-case row is a vendor no search can find.
    assertThat(saved.getValue().getEmail()).isEqualTo("suresh@example.com");
    assertThat(saved.getValue().getCategory()).isEqualTo(VendorCategory.PLUMBING);
    assertThat(saved.getValue().isEmpanelled()).isTrue();
    assertThat(saved.getValue().isAssignable()).isTrue();
  }

  @Test
  @DisplayName("archiving a vendor is refused a second time")
  void archivingAVendorIsOnceOnly() {
    Vendor archived = vendor(VendorCategory.PLUMBING, true, VendorStatus.ACTIVE);
    archived.setDeletedAt(LocalDateTime.now().minusDays(1));

    when(accessScope.requireUser()).thenReturn(actor());
    when(vendors.findById(archived.getId())).thenReturn(Optional.of(archived));

    ApiException refused =
        catchThrowableOfType(() -> service.archive(archived.getId()), ApiException.class);

    assertThat(refused.getCode()).isEqualTo("VENDOR_ALREADY_ARCHIVED");
    assertThat(refused.getStatus()).isEqualTo(HttpStatus.CONFLICT);
    verify(vendors, never()).save(any());
  }

  @Test
  @DisplayName("the register filters on trade, approval and status, and pages what it returns")
  void theRegisterFiltersAndPages() {
    Vendor plumber = vendor(VendorCategory.PLUMBING, true, VendorStatus.ACTIVE);
    Vendor unapproved = vendor(VendorCategory.PLUMBING, false, VendorStatus.ACTIVE);
    Vendor electrician = vendor(VendorCategory.ELECTRICAL, true, VendorStatus.ACTIVE);
    plumber.setCreatedAt(LocalDateTime.now().minusDays(1));
    unapproved.setCreatedAt(LocalDateTime.now().minusDays(2));
    electrician.setCreatedAt(LocalDateTime.now().minusDays(3));

    lenient().when(vendors.findAllByDeletedAtIsNull())
        .thenReturn(List.of(plumber, unapproved, electrician));

    PageResponse<VendorDtos.VendorSummary> approvedPlumbing =
        service.list("PLUMBING", true, "ACTIVE", null, PageRequest.of(0, 20));

    assertThat(approvedPlumbing.totalElements()).isEqualTo(1);
    assertThat(approvedPlumbing.items().get(0).id()).isEqualTo(plumber.getId());

    PageResponse<VendorDtos.VendorSummary> all =
        service.list(null, null, null, null, PageRequest.of(0, 2));

    assertThat(all.totalElements()).isEqualTo(3);
    assertThat(all.items()).hasSize(2);
    assertThat(all.hasNext()).isTrue();
  }
}
