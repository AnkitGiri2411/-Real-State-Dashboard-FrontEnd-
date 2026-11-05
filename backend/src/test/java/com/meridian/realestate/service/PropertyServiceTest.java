package com.meridian.realestate.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.meridian.realestate.domain.Lease;
import com.meridian.realestate.domain.LeaseStatus;
import com.meridian.realestate.domain.Property;
import com.meridian.realestate.domain.PropertyStatus;
import com.meridian.realestate.domain.PropertyStatusHistory;
import com.meridian.realestate.domain.PropertyType;
import com.meridian.realestate.domain.User;
import com.meridian.realestate.dto.PropertyDtos;
import com.meridian.realestate.exception.ApiException;
import com.meridian.realestate.repository.AgentPropertyAssignmentRepository;
import com.meridian.realestate.repository.AmenityRepository;
import com.meridian.realestate.repository.BuildingRepository;
import com.meridian.realestate.repository.DocumentRepository;
import com.meridian.realestate.repository.LeaseRepository;
import com.meridian.realestate.repository.PropertyAmenityRepository;
import com.meridian.realestate.repository.PropertyMediaRepository;
import com.meridian.realestate.repository.PropertyOwnershipRepository;
import com.meridian.realestate.repository.PropertyRepository;
import com.meridian.realestate.repository.PropertyStatusHistoryRepository;
import com.meridian.realestate.repository.PropertyTypeRepository;
import com.meridian.realestate.repository.UnitRepository;
import com.meridian.realestate.security.AccessScope;
import java.math.BigDecimal;
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
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;

/**
 * The property register's rules, with every collaborator mocked and no database.
 *
 * <p>The rules asserted here are the ones a wrong refactor would quietly break and no screen would
 * show: a position that is half a coordinate pair is refused rather than saved (the map would
 * otherwise gain a marker at a guessed place); a property that still carries a running lease cannot
 * be archived, and the refusal is a <b>409</b> named {@code PROPERTY_HAS_ACTIVE_LEASES} rather than
 * a 500; a second archive of an already-archived property is refused; a terminal asset cannot move;
 * occupancy over no leasable units reads {@code 0%} rather than dividing by zero; the rent roll is
 * the asking rent over the leasable, live stock; and every status move is written to the history.
 * Each is cheap to break and expensive to notice, which is exactly why each is pinned here.</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PropertyService: the register's rules")
class PropertyServiceTest {

  private static final UUID PROPERTY_ID = UUID.randomUUID();
  private static final UUID TYPE_ID = UUID.randomUUID();

  @Mock private PropertyRepository propertyRepository;
  @Mock private PropertyTypeRepository propertyTypeRepository;
  @Mock private UnitRepository unitRepository;
  @Mock private BuildingRepository buildingRepository;
  @Mock private DocumentRepository documentRepository;
  @Mock private PropertyOwnershipRepository ownershipRepository;
  @Mock private PropertyAmenityRepository propertyAmenityRepository;
  @Mock private AmenityRepository amenityRepository;
  @Mock private PropertyMediaRepository propertyMediaRepository;
  @Mock private PropertyStatusHistoryRepository statusHistoryRepository;
  @Mock private LeaseRepository leaseRepository;
  @Mock private AgentPropertyAssignmentRepository agentAssignments;
  @Mock private BusinessNumberService businessNumbers;
  @Mock private AuditRecordingService audit;
  @Mock private AccessScope accessScope;

  private PropertyService service;

  @BeforeEach
  void setUp() {
    service =
        new PropertyService(
            propertyRepository,
            propertyTypeRepository,
            unitRepository,
            buildingRepository,
            documentRepository,
            ownershipRepository,
            propertyAmenityRepository,
            amenityRepository,
            propertyMediaRepository,
            statusHistoryRepository,
            leaseRepository,
            agentAssignments,
            businessNumbers,
            audit,
            accessScope);
  }

  private static User actor() {
    User user = new User();
    user.setId(UUID.randomUUID());
    user.setFirstName("Aditya");
    user.setLastName("Raghunathan");
    return user;
  }

  private static PropertyType type() {
    PropertyType type = new PropertyType();
    type.setId(TYPE_ID);
    type.setCode("RESIDENTIAL_APARTMENT");
    type.setName("Apartment");
    type.setLeasable(true);
    return type;
  }

  private static Property property(PropertyStatus status) {
    Property property = new Property();
    property.setId(PROPERTY_ID);
    property.setPropertyCode("ME-P-00001");
    property.setName("Meridian One");
    property.setStatus(status);
    property.setCity("Pune");
    property.setAddressLine1("1 Residency Road");
    property.setPropertyType(type());
    return property;
  }

  private static PropertyDtos.CreatePropertyRequest createRequest(
      BigDecimal latitude, BigDecimal longitude) {
    return new PropertyDtos.CreatePropertyRequest(
        "Meridian One",
        TYPE_ID,
        null,
        "1 Residency Road",
        null,
        null,
        "Pune",
        null,
        null,
        null,
        latitude,
        longitude,
        null,
        null,
        null,
        null,
        null,
        null,
        null);
  }

  // ---------------------------------------------------------------- position

  @Test
  @DisplayName("creating a property with only a latitude is refused as a 400, before anything is saved")
  void creatingAPropertyWithOnlyALatitudeIsRefused() {
    when(accessScope.requireUser()).thenReturn(actor());

    ApiException ex =
        catchThrowableOfType(
            () -> service.create(createRequest(new BigDecimal("18.5204"), null)),
            ApiException.class);

    assertThat(ex.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(ex.getCode()).isEqualTo("VALIDATION_FAILED");
    verify(propertyRepository, never()).save(any());
  }

  @Test
  @DisplayName("creating a property with only a longitude is refused as a 400, before anything is saved")
  void creatingAPropertyWithOnlyALongitudeIsRefused() {
    when(accessScope.requireUser()).thenReturn(actor());

    ApiException ex =
        catchThrowableOfType(
            () -> service.create(createRequest(null, new BigDecimal("73.8567"))),
            ApiException.class);

    assertThat(ex.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
    verify(propertyRepository, never()).save(any());
  }

  @Test
  @DisplayName("creating a property with both coordinates mints a code and persists it")
  void creatingAPropertyWithBothCoordinatesIsPersisted() {
    User actor = actor();
    when(accessScope.requireUser()).thenReturn(actor);
    when(propertyTypeRepository.findById(TYPE_ID)).thenReturn(Optional.of(type()));
    when(businessNumbers.next(BusinessNumberService.PROPERTY)).thenReturn("ME-P-00001");
    when(propertyRepository.save(any(Property.class)))
        .thenAnswer(
            invocation -> {
              Property saved = invocation.getArgument(0);
              saved.setId(PROPERTY_ID);
              return saved;
            });

    PropertyDtos.PropertyDetail detail =
        service.create(
            createRequest(new BigDecimal("18.5204"), new BigDecimal("73.8567")));

    assertThat(detail.propertyCode()).isEqualTo("ME-P-00001");
    assertThat(detail.positioned()).isTrue();
    assertThat(detail.latitude()).isEqualByComparingTo("18.5204");
    verify(audit)
        .record(
            eq(actor.getId()),
            eq(actor.getFullName()),
            eq(AuditActions.PROPERTY),
            eq(AuditActions.CREATE),
            anyString(),
            eq("PROPERTY"),
            eq(PROPERTY_ID),
            eq("ME-P-00001"));
  }

  // ----------------------------------------------------------------- archive

  @Test
  @DisplayName("archiving a property with running leases is refused with 409 PROPERTY_HAS_ACTIVE_LEASES")
  void archivingAPropertyWithRunningLeasesIsRefused() {
    when(accessScope.requireUser()).thenReturn(actor());
    Property property = property(PropertyStatus.LEASED);
    when(propertyRepository.findById(PROPERTY_ID)).thenReturn(Optional.of(property));

    Lease running = new Lease();
    running.setStatus(LeaseStatus.ACTIVE);
    when(leaseRepository.findAllByProperty_IdAndDeletedAtIsNull(eq(PROPERTY_ID), any(Pageable.class)))
        .thenReturn(new PageImpl<>(List.of(running)));

    ApiException ex =
        catchThrowableOfType(() -> service.archive(PROPERTY_ID), ApiException.class);

    assertThat(ex.getCode()).isEqualTo("PROPERTY_HAS_ACTIVE_LEASES");
    assertThat(ex.getStatus()).isEqualTo(HttpStatus.CONFLICT);
    verify(propertyRepository, never()).save(any());
  }

  @Test
  @DisplayName("archiving an already-archived property is refused rather than repeated")
  void archivingAnAlreadyArchivedPropertyIsRefused() {
    when(accessScope.requireUser()).thenReturn(actor());
    Property property = property(PropertyStatus.INACTIVE);
    property.setDeletedAt(LocalDateTime.now().minusDays(1));
    when(propertyRepository.findById(PROPERTY_ID)).thenReturn(Optional.of(property));

    ApiException ex =
        catchThrowableOfType(() -> service.archive(PROPERTY_ID), ApiException.class);

    assertThat(ex.getCode()).isEqualTo("PROPERTY_ALREADY_ARCHIVED");
    assertThat(ex.getStatus()).isEqualTo(HttpStatus.CONFLICT);
    verify(propertyRepository, never()).save(any());
  }

  // ------------------------------------------------------------ status moves

  @Test
  @DisplayName("a status change writes a history row from the old state to the new one")
  void aStatusChangeWritesAHistoryRow() {
    User actor = actor();
    when(accessScope.requireUser()).thenReturn(actor);
    Property property = property(PropertyStatus.DRAFT);
    when(propertyRepository.findByIdAndDeletedAtIsNull(PROPERTY_ID)).thenReturn(Optional.of(property));
    when(propertyRepository.save(any(Property.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    service.changeStatus(
        PROPERTY_ID, new PropertyDtos.ChangeStatusRequest("LISTED", "Owner instructed"));

    ArgumentCaptor<PropertyStatusHistory> captor =
        ArgumentCaptor.forClass(PropertyStatusHistory.class);
    verify(statusHistoryRepository).save(captor.capture());
    PropertyStatusHistory history = captor.getValue();
    assertThat(history.getFromStatus()).isEqualTo(PropertyStatus.DRAFT);
    assertThat(history.getToStatus()).isEqualTo(PropertyStatus.LISTED);
    assertThat(history.getReason()).isEqualTo("Owner instructed");
    assertThat(history.getChangedBy()).isEqualTo(actor.getId());
  }

  @Test
  @DisplayName("a property that is SOLD has reached a terminal state and cannot move")
  void aTerminalPropertyCannotChangeStatus() {
    when(accessScope.requireUser()).thenReturn(actor());
    Property property = property(PropertyStatus.SOLD);
    when(propertyRepository.findByIdAndDeletedAtIsNull(PROPERTY_ID)).thenReturn(Optional.of(property));

    ApiException ex =
        catchThrowableOfType(
            () ->
                service.changeStatus(
                    PROPERTY_ID, new PropertyDtos.ChangeStatusRequest("LISTED", null)),
            ApiException.class);

    assertThat(ex.getCode()).isEqualTo("PROPERTY_TRANSITION_NOT_ALLOWED");
    assertThat(ex.getStatus()).isEqualTo(HttpStatus.CONFLICT);
    verify(propertyRepository, never()).save(any());
  }

  // ----------------------------------------------------------------- metrics

  @Test
  @DisplayName("occupancy over no leasable units reads 0%, never a divide-by-zero")
  void occupancyIsZeroWhenThereAreNoLeasableUnits() {
    when(propertyRepository.findByIdAndDeletedAtIsNull(PROPERTY_ID))
        .thenReturn(Optional.of(property(PropertyStatus.LEASED)));
    when(unitRepository.countByPropertyIdAndDeletedAtIsNull(PROPERTY_ID)).thenReturn(12L);
    when(unitRepository.countByPropertyIdAndLeasableTrueAndDeletedAtIsNull(PROPERTY_ID)).thenReturn(0L);
    when(
            unitRepository.countByPropertyIdAndLeasableTrueAndStatusAndDeletedAtIsNull(
                PROPERTY_ID, com.meridian.realestate.domain.UnitStatus.OCCUPIED))
        .thenReturn(0L);
    when(unitRepository.sumAskingRentByProperty(PROPERTY_ID)).thenReturn(BigDecimal.ZERO);

    PropertyDtos.PropertyMetrics metrics = service.metrics(PROPERTY_ID);

    assertThat(metrics.occupancyPercent()).isZero();
    assertThat(metrics.leasableUnits()).isZero();
  }

  @Test
  @DisplayName("the monthly rent roll is the asking rent over the leasable, live units")
  void theRentRollComesFromTheLeasableLiveUnits() {
    when(propertyRepository.findByIdAndDeletedAtIsNull(PROPERTY_ID))
        .thenReturn(Optional.of(property(PropertyStatus.LEASED)));
    when(unitRepository.countByPropertyIdAndDeletedAtIsNull(PROPERTY_ID)).thenReturn(10L);
    when(unitRepository.countByPropertyIdAndLeasableTrueAndDeletedAtIsNull(PROPERTY_ID)).thenReturn(8L);
    when(
            unitRepository.countByPropertyIdAndLeasableTrueAndStatusAndDeletedAtIsNull(
                PROPERTY_ID, com.meridian.realestate.domain.UnitStatus.OCCUPIED))
        .thenReturn(6L);
    when(unitRepository.sumAskingRentByProperty(PROPERTY_ID)).thenReturn(new BigDecimal("250000.00"));

    PropertyDtos.PropertyMetrics metrics = service.metrics(PROPERTY_ID);

    // The rent roll is read from the one query that filters leasable, non-deleted stock, so the
    // figure and the occupancy denominator describe the same population.
    verify(unitRepository).sumAskingRentByProperty(PROPERTY_ID);
    assertThat(metrics.monthlyRentRoll()).isEqualByComparingTo("250000.00");
    assertThat(metrics.leasableUnits()).isEqualTo(8);
    assertThat(metrics.occupancyPercent()).isEqualTo(75);
  }
}
