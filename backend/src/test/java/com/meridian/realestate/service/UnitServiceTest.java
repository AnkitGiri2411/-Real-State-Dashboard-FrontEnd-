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
import com.meridian.realestate.domain.Property;
import com.meridian.realestate.domain.Unit;
import com.meridian.realestate.domain.UnitStatus;
import com.meridian.realestate.domain.UnitStatusHistory;
import com.meridian.realestate.domain.UnitType;
import com.meridian.realestate.domain.User;
import com.meridian.realestate.dto.UnitDtos;
import com.meridian.realestate.exception.ApiException;
import com.meridian.realestate.repository.BuildingRepository;
import com.meridian.realestate.repository.LeaseRepository;
import com.meridian.realestate.repository.PropertyRepository;
import com.meridian.realestate.repository.TenantRepository;
import com.meridian.realestate.repository.UnitRepository;
import com.meridian.realestate.repository.UnitStatusHistoryRepository;
import com.meridian.realestate.repository.UnitTypeRepository;
import com.meridian.realestate.security.AccessScope;
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
import org.springframework.http.HttpStatus;

/**
 * The unit register's rules, with every collaborator mocked and no database.
 *
 * <p>What is pinned here is the pair of guards that keep occupancy honest. <b>A non-lettable unit
 * cannot be marked as let</b>: a lobby or a plant room is real stock that is never part of an
 * occupancy figure, and the refusal is {@code UNIT_NOT_LEASABLE} rather than a database constraint
 * error. <b>A unit that already carries a running lease cannot be let again</b>: the check reads the
 * one-running-lease query, and the refusal is a <b>409</b> named {@code UNIT_ALREADY_LET}. Also
 * pinned: a status move is written to the unit's history, and occupancy over a property with no
 * leasable stock reads {@code 0%} rather than dividing by zero.</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("UnitService: leasability, occupancy and the register's rules")
class UnitServiceTest {

  private static final UUID PROPERTY_ID = UUID.randomUUID();
  private static final UUID UNIT_ID = UUID.randomUUID();
  private static final UUID UNIT_TYPE_ID = UUID.randomUUID();

  @Mock private UnitRepository unitRepository;
  @Mock private PropertyRepository propertyRepository;
  @Mock private BuildingRepository buildingRepository;
  @Mock private UnitTypeRepository unitTypeRepository;
  @Mock private UnitStatusHistoryRepository statusHistoryRepository;
  @Mock private LeaseRepository leaseRepository;
  @Mock private TenantRepository tenantRepository;
  @Mock private BusinessNumberService businessNumbers;
  @Mock private AuditRecordingService audit;
  @Mock private AccessScope accessScope;

  private UnitService service;

  @BeforeEach
  void setUp() {
    service =
        new UnitService(
            unitRepository,
            propertyRepository,
            buildingRepository,
            unitTypeRepository,
            statusHistoryRepository,
            leaseRepository,
            tenantRepository,
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

  private static Property property() {
    Property property = new Property();
    property.setId(PROPERTY_ID);
    property.setPropertyCode("ME-P-00001");
    property.setName("Meridian One");
    return property;
  }

  private static UnitType unitType() {
    UnitType type = new UnitType();
    type.setId(UNIT_TYPE_ID);
    type.setCode("BHK_2");
    type.setName("2 BHK");
    return type;
  }

  private static Unit unit(boolean leasable, UnitStatus status) {
    Unit unit = new Unit();
    unit.setId(UNIT_ID);
    unit.setUnitCode("ME-U-000001");
    unit.setUnitNumber("101");
    unit.setProperty(property());
    unit.setUnitType(unitType());
    unit.setLeasable(leasable);
    unit.setStatus(status);
    return unit;
  }

  // ---------------------------------------------------------------- lettability

  @Test
  @DisplayName("marking a non-leasable unit as let is refused with UNIT_NOT_LEASABLE")
  void markingANonLeasableUnitAsLetIsRefused() {
    when(accessScope.requireUser()).thenReturn(actor());
    Unit unit = unit(false, UnitStatus.NOT_LETTABLE);
    when(unitRepository.findByIdAndDeletedAtIsNull(UNIT_ID)).thenReturn(Optional.of(unit));

    ApiException ex =
        catchThrowableOfType(
            () ->
                service.changeStatus(
                    UNIT_ID, new UnitDtos.ChangeStatusRequest("OCCUPIED", null)),
            ApiException.class);

    assertThat(ex.getCode()).isEqualTo("UNIT_NOT_LEASABLE");
    assertThat(ex.getStatus()).isEqualTo(HttpStatus.CONFLICT);
    verify(unitRepository, never()).save(any());
  }

  @Test
  @DisplayName("creating a non-leasable unit in a letting state is refused")
  void creatingANonLeasableUnitInALettingStateIsRefused() {
    when(accessScope.requireUser()).thenReturn(actor());
    when(propertyRepository.findByIdAndDeletedAtIsNull(PROPERTY_ID))
        .thenReturn(Optional.of(property()));
    when(unitTypeRepository.findById(UNIT_TYPE_ID)).thenReturn(Optional.of(unitType()));
    when(unitRepository.existsByPropertyIdAndUnitNumberIgnoreCaseAndDeletedAtIsNull(
            PROPERTY_ID, "CA-1"))
        .thenReturn(false);

    UnitDtos.CreateUnitRequest request =
        new UnitDtos.CreateUnitRequest(
            PROPERTY_ID,
            null,
            UNIT_TYPE_ID,
            "CA-1",
            0,
            "G",
            0,
            0,
            0,
            null,
            null,
            0,
            null,
            null,
            "AVAILABLE",
            false,
            null,
            null);

    ApiException ex =
        catchThrowableOfType(() -> service.create(request), ApiException.class);

    assertThat(ex.getCode()).isEqualTo("UNIT_NOT_LEASABLE");
    verify(unitRepository, never()).save(any());
  }

  @Test
  @DisplayName("a unit that already carries a running lease cannot be marked as let again")
  void aUnitAlreadyCarryingARunningLeaseCannotBeMarkedAsLet() {
    when(accessScope.requireUser()).thenReturn(actor());
    Unit unit = unit(true, UnitStatus.AVAILABLE);
    when(unitRepository.findByIdAndDeletedAtIsNull(UNIT_ID)).thenReturn(Optional.of(unit));

    Lease running = new Lease();
    running.setStatus(LeaseStatus.ACTIVE);
    when(leaseRepository.findRunningLeaseForUnit(UNIT_ID)).thenReturn(Optional.of(running));

    ApiException ex =
        catchThrowableOfType(
            () ->
                service.changeStatus(
                    UNIT_ID, new UnitDtos.ChangeStatusRequest("OCCUPIED", null)),
            ApiException.class);

    assertThat(ex.getCode()).isEqualTo("UNIT_ALREADY_LET");
    assertThat(ex.getStatus()).isEqualTo(HttpStatus.CONFLICT);
    verify(unitRepository, never()).save(any());
  }

  // ------------------------------------------------------------------ history

  @Test
  @DisplayName("a unit status change writes a history row from the old state to the new one")
  void aUnitStatusChangeWritesAHistoryRow() {
    User actor = actor();
    when(accessScope.requireUser()).thenReturn(actor);
    Unit unit = unit(true, UnitStatus.AVAILABLE);
    when(unitRepository.findByIdAndDeletedAtIsNull(UNIT_ID)).thenReturn(Optional.of(unit));
    when(leaseRepository.findRunningLeaseForUnit(UNIT_ID)).thenReturn(Optional.empty());
    when(unitRepository.save(any(Unit.class))).thenAnswer(invocation -> invocation.getArgument(0));

    service.changeStatus(
        UNIT_ID, new UnitDtos.ChangeStatusRequest("OCCUPIED", "Tenant checked in"));

    ArgumentCaptor<UnitStatusHistory> captor = ArgumentCaptor.forClass(UnitStatusHistory.class);
    verify(statusHistoryRepository).save(captor.capture());
    UnitStatusHistory history = captor.getValue();
    assertThat(history.getFromStatus()).isEqualTo(UnitStatus.AVAILABLE);
    assertThat(history.getToStatus()).isEqualTo(UnitStatus.OCCUPIED);
    assertThat(history.getReason()).isEqualTo("Tenant checked in");
    assertThat(history.getChangedBy()).isEqualTo(actor.getId());
  }

  @Test
  @DisplayName("creating a leasable unit mints a code and defaults it to AVAILABLE")
  void creatingALeasableUnitMintsACode() {
    User actor = actor();
    when(accessScope.requireUser()).thenReturn(actor);
    when(propertyRepository.findByIdAndDeletedAtIsNull(PROPERTY_ID))
        .thenReturn(Optional.of(property()));
    when(unitTypeRepository.findById(UNIT_TYPE_ID)).thenReturn(Optional.of(unitType()));
    when(unitRepository.existsByPropertyIdAndUnitNumberIgnoreCaseAndDeletedAtIsNull(
            PROPERTY_ID, "101"))
        .thenReturn(false);
    when(businessNumbers.next(BusinessNumberService.UNIT)).thenReturn("ME-U-000001");
    when(unitRepository.save(any(Unit.class)))
        .thenAnswer(
            invocation -> {
              Unit saved = invocation.getArgument(0);
              saved.setId(UNIT_ID);
              return saved;
            });
    when(leaseRepository.findRunningLeaseForUnit(UNIT_ID)).thenReturn(Optional.empty());

    UnitDtos.CreateUnitRequest request =
        new UnitDtos.CreateUnitRequest(
            PROPERTY_ID,
            null,
            UNIT_TYPE_ID,
            "101",
            3,
            "3",
            2,
            2,
            1,
            null,
            null,
            0,
            null,
            null,
            null,
            true,
            null,
            null);

    UnitDtos.UnitDetail detail = service.create(request);

    assertThat(detail.unitCode()).isEqualTo("ME-U-000001");
    assertThat(detail.status()).isEqualTo("AVAILABLE");
    assertThat(detail.leasable()).isTrue();
    assertThat(detail.currentLease().let()).isFalse();
  }

  // ----------------------------------------------------------------- occupancy

  @Test
  @DisplayName("occupancy over a property with no leasable units reads 0%, never a divide-by-zero")
  void occupancyIsZeroWhenThereAreNoLeasableUnits() {
    when(propertyRepository.findByIdAndDeletedAtIsNull(PROPERTY_ID))
        .thenReturn(Optional.of(property()));
    Unit lobby = unit(false, UnitStatus.NOT_LETTABLE);
    Unit plant = unit(false, UnitStatus.UNDER_MAINTENANCE);
    when(unitRepository.findAllByPropertyIdAndDeletedAtIsNull(PROPERTY_ID))
        .thenReturn(List.of(lobby, plant));

    UnitDtos.UnitOccupancy occupancy = service.occupancy(PROPERTY_ID);

    assertThat(occupancy.leasableUnits()).isZero();
    assertThat(occupancy.occupancyPercent()).isZero();
    assertThat(occupancy.totalUnits()).isEqualTo(2);
    assertThat(occupancy.notLettableUnits()).isEqualTo(2);
  }
}
