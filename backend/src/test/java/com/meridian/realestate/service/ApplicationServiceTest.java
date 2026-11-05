package com.meridian.realestate.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.meridian.realestate.domain.ApplicationScreening;
import com.meridian.realestate.domain.ApplicationStatus;
import com.meridian.realestate.domain.Property;
import com.meridian.realestate.domain.RentalApplication;
import com.meridian.realestate.domain.ScreeningCheckType;
import com.meridian.realestate.domain.ScreeningStatus;
import com.meridian.realestate.domain.Unit;
import com.meridian.realestate.domain.User;
import com.meridian.realestate.dto.ApplicationDtos;
import com.meridian.realestate.exception.ApiException;
import com.meridian.realestate.repository.AgentProfileRepository;
import com.meridian.realestate.repository.ApplicationScreeningRepository;
import com.meridian.realestate.repository.ApplicationStatusHistoryRepository;
import com.meridian.realestate.repository.LeadRepository;
import com.meridian.realestate.repository.PropertyRepository;
import com.meridian.realestate.repository.RentalApplicationRepository;
import com.meridian.realestate.repository.TenantRepository;
import com.meridian.realestate.repository.UnitRepository;
import com.meridian.realestate.security.AccessScope;
import java.time.LocalDateTime;
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
 * The rental application's two gates, asserted without a database: one open application per unit, and
 * a decision that waits for screening.
 *
 * <p>The {@code WAIVED} case is the one worth stating out loud. A waiver is an explicit, attributed
 * decision to run no check - it is a finished check, not an absence of one - so it must not block a
 * decision that the firm has already decided to allow. Only a {@code PENDING} check blocks.</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ApplicationService: one open application per unit, and screening gates the decision")
class ApplicationServiceTest {

  @Mock private RentalApplicationRepository applications;
  @Mock private ApplicationScreeningRepository screenings;
  @Mock private ApplicationStatusHistoryRepository history;
  @Mock private PropertyRepository properties;
  @Mock private UnitRepository units;
  @Mock private LeadRepository leads;
  @Mock private TenantRepository tenants;
  @Mock private AgentProfileRepository agents;
  @Mock private BusinessNumberService numbers;
  @Mock private AuditRecordingService audit;
  @Mock private AccessScope accessScope;

  private ApplicationService service;

  @BeforeEach
  void setUp() {
    service =
        new ApplicationService(
            applications, screenings, history, properties, units, leads, tenants, agents, numbers,
            audit, accessScope);
  }

  private static User actor() {
    User user = new User();
    user.setId(UUID.randomUUID());
    user.setFirstName("Rohan");
    user.setLastName("Malhotra");
    return user;
  }

  private static Property property(UUID id) {
    Property property = new Property();
    property.setId(id);
    property.setPropertyCode("ME-P-00001");
    property.setName("Marine Vista");
    return property;
  }

  private static Unit unit(UUID id, Property property) {
    Unit unit = new Unit();
    unit.setId(id);
    unit.setUnitNumber("1204");
    unit.setProperty(property);
    return unit;
  }

  private static RentalApplication application(UUID id, ApplicationStatus status) {
    RentalApplication application = new RentalApplication();
    application.setId(id);
    application.setApplicationCode("ME-A-00001");
    application.setStatus(status);
    application.setApplicantName("A. Applicant");
    application.setSubmittedAt(LocalDateTime.now().minusDays(1));
    return application;
  }

  private static ApplicationScreening screening(ScreeningCheckType type, ScreeningStatus status) {
    ApplicationScreening screening = new ApplicationScreening();
    screening.setId(UUID.randomUUID());
    screening.setCheckType(type);
    screening.setStatus(status);
    return screening;
  }

  private static ApplicationDtos.CreateApplicationRequest newApplication(UUID propertyId, UUID unitId) {
    return new ApplicationDtos.CreateApplicationRequest(
        propertyId, unitId, null, null, null, "A. Applicant", "applicant@example.com", null,
        null, null, null, null, null, null, null, null, null);
  }

  @Test
  @DisplayName("a second open application on the same unit is refused with a 409 code")
  void aSecondOpenApplicationOnTheSameUnitIsRefused() {
    UUID propertyId = UUID.randomUUID();
    UUID unitId = UUID.randomUUID();
    Property property = property(propertyId);
    Unit unit = unit(unitId, property);

    when(accessScope.requireUser()).thenReturn(actor());
    when(properties.findByIdAndDeletedAtIsNull(propertyId)).thenReturn(Optional.of(property));
    when(units.findByIdAndDeletedAtIsNull(unitId)).thenReturn(Optional.of(unit));
    when(applications.findAllByUnitIdAndStatusInAndDeletedAtIsNull(eq(unitId), any()))
        .thenReturn(List.of(application(UUID.randomUUID(), ApplicationStatus.SUBMITTED)));

    ApiException refused =
        catchThrowableOfType(
            () -> service.create(newApplication(propertyId, unitId)), ApiException.class);

    assertThat(refused.getCode()).isEqualTo("APPLICATION_ALREADY_OPEN_ON_UNIT");
    assertThat(refused.getStatus()).isEqualTo(HttpStatus.CONFLICT);
    // Nothing is written: the second application is refused rather than half-taken.
    verify(applications, never()).save(any());
  }

  @Test
  @DisplayName("approving with a PENDING screening is refused with SCREENING_INCOMPLETE")
  void approvingWithAPendingScreeningIsRefused() {
    UUID id = UUID.randomUUID();
    RentalApplication application = application(id, ApplicationStatus.SCREENING);
    when(accessScope.requireUser()).thenReturn(actor());
    when(accessScope.isCompanyWide()).thenReturn(true);
    when(applications.findByIdAndDeletedAtIsNull(id)).thenReturn(Optional.of(application));
    when(screenings.findAllByApplicationIdAndDeletedAtIsNull(id))
        .thenReturn(
            List.of(
                screening(ScreeningCheckType.IDENTITY, ScreeningStatus.PASSED),
                screening(ScreeningCheckType.INCOME, ScreeningStatus.PENDING)));

    ApiException refused =
        catchThrowableOfType(() -> service.approve(id, null), ApiException.class);

    assertThat(refused.getCode()).isEqualTo("SCREENING_INCOMPLETE");
    assertThat(refused.getStatus()).isEqualTo(HttpStatus.CONFLICT);
    verify(applications, never()).save(any());
  }

  @Test
  @DisplayName("rejecting with a PENDING screening is refused with SCREENING_INCOMPLETE")
  void rejectingWithAPendingScreeningIsRefused() {
    UUID id = UUID.randomUUID();
    RentalApplication application = application(id, ApplicationStatus.SUBMITTED);
    when(accessScope.requireUser()).thenReturn(actor());
    when(accessScope.isCompanyWide()).thenReturn(true);
    when(applications.findByIdAndDeletedAtIsNull(id)).thenReturn(Optional.of(application));
    when(screenings.findAllByApplicationIdAndDeletedAtIsNull(id))
        .thenReturn(List.of(screening(ScreeningCheckType.CREDIT, ScreeningStatus.PENDING)));

    ApiException refused =
        catchThrowableOfType(
            () -> service.reject(id, new ApplicationDtos.RejectRequest("Affordability", null)),
            ApiException.class);

    assertThat(refused.getCode()).isEqualTo("SCREENING_INCOMPLETE");
    verify(applications, never()).save(any());
  }

  @Test
  @DisplayName("a WAIVED screening does not block a decision")
  void aWaivedScreeningDoesNotBlockADecision() {
    UUID id = UUID.randomUUID();
    RentalApplication application = application(id, ApplicationStatus.SCREENING);
    List<ApplicationScreening> checks =
        List.of(
            screening(ScreeningCheckType.IDENTITY, ScreeningStatus.PASSED),
            screening(ScreeningCheckType.CREDIT, ScreeningStatus.WAIVED),
            screening(ScreeningCheckType.EMPLOYMENT, ScreeningStatus.FAILED));

    when(accessScope.requireUser()).thenReturn(actor());
    when(accessScope.isCompanyWide()).thenReturn(true);
    when(applications.findByIdAndDeletedAtIsNull(id)).thenReturn(Optional.of(application));
    when(screenings.findAllByApplicationIdAndDeletedAtIsNull(id)).thenReturn(checks);
    when(applications.save(any(RentalApplication.class))).thenAnswer(call -> call.getArgument(0));

    ApplicationDtos.ApplicationDetail detail =
        service.approve(id, new ApplicationDtos.ApproveRequest("Sound tenant"));

    // No check is PENDING, so the decision is allowed and written whole.
    assertThat(application.getStatus()).isEqualTo(ApplicationStatus.APPROVED);
    assertThat(application.getDecidedAt()).isNotNull();
    assertThat(application.getDecidedBy()).isNotNull();
    assertThat(application.getRejectionReason()).isNull();
    assertThat(detail.status()).isEqualTo("APPROVED");
    verify(applications).save(application);
  }
}
