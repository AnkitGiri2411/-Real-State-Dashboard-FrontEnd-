package com.meridian.realestate.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.Mockito.when;

import com.meridian.realestate.domain.AgentProfile;
import com.meridian.realestate.domain.AgentSpecialisation;
import com.meridian.realestate.domain.AgentStatus;
import com.meridian.realestate.domain.AssignmentRole;
import com.meridian.realestate.domain.Property;
import com.meridian.realestate.domain.User;
import com.meridian.realestate.dto.AgentDtos;
import com.meridian.realestate.exception.ApiException;
import com.meridian.realestate.repository.AgentProfileRepository;
import com.meridian.realestate.repository.AgentPropertyAssignmentRepository;
import com.meridian.realestate.repository.LeadRepository;
import com.meridian.realestate.repository.ListingRepository;
import com.meridian.realestate.repository.MaintenanceRequestRepository;
import com.meridian.realestate.repository.OfficeRepository;
import com.meridian.realestate.repository.PropertyRepository;
import com.meridian.realestate.repository.UserRepository;
import com.meridian.realestate.repository.ViewingRepository;
import com.meridian.realestate.security.AccessScope;
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
 * The agent register's rules, with every collaborator mocked and no database.
 *
 * <p>Three things are pinned here, each mirroring a schema constraint the service refuses clearly
 * instead of letting the database report it opaquely. One profile per staff account
 * ({@code AGENT_USER_ALREADY_LINKED}). One open primary agent per property
 * ({@code AGENT_PRIMARY_ALREADY_SET}). And the guarded arithmetic: an agent with no leads has a
 * conversion rate of {@code 0}, never {@code NaN} - a rate that cannot be computed is honestly zero
 * rather than an error, and the raw counts travel beside it.</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AgentService: the agent register's rules")
class AgentServiceTest {

  private static final UUID AGENT_ID = UUID.randomUUID();
  private static final UUID PROPERTY_ID = UUID.randomUUID();

  @Mock private AgentProfileRepository agentRepository;
  @Mock private OfficeRepository officeRepository;
  @Mock private UserRepository userRepository;
  @Mock private PropertyRepository propertyRepository;
  @Mock private AgentPropertyAssignmentRepository assignmentRepository;
  @Mock private LeadRepository leadRepository;
  @Mock private ListingRepository listingRepository;
  @Mock private ViewingRepository viewingRepository;
  @Mock private MaintenanceRequestRepository maintenanceRepository;
  @Mock private AuditRecordingService audit;
  @Mock private AccessScope accessScope;

  private AgentService service;

  @BeforeEach
  void setUp() {
    service =
        new AgentService(
            agentRepository,
            officeRepository,
            userRepository,
            propertyRepository,
            assignmentRepository,
            leadRepository,
            listingRepository,
            viewingRepository,
            maintenanceRepository,
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

  private static AgentProfile agent() {
    AgentProfile agent = new AgentProfile();
    agent.setId(AGENT_ID);
    agent.setAgentCode("ME-AG-001");
    agent.setSpecialisation(AgentSpecialisation.BOTH);
    agent.setStatus(AgentStatus.ACTIVE);
    return agent;
  }

  private static Property property() {
    Property property = new Property();
    property.setId(PROPERTY_ID);
    property.setPropertyCode("ME-P-00001");
    property.setName("Meridian One");
    return property;
  }

  private static AgentDtos.CreateAgentRequest createRequest(UUID userId) {
    return new AgentDtos.CreateAgentRequest(
        userId, "ME-AG-099", null, null, null, null, null, null, null, null);
  }

  @Test
  @DisplayName("a second agent profile for the same staff account is refused with 409 "
      + "AGENT_USER_ALREADY_LINKED")
  void aSecondProfileForTheSameStaffAccountIsRefused() {
    when(accessScope.requireUser()).thenReturn(actor());
    UUID userId = UUID.randomUUID();
    when(agentRepository.existsByUserIdAndDeletedAtIsNull(userId)).thenReturn(true);

    ApiException ex =
        catchThrowableOfType(() -> service.create(createRequest(userId)), ApiException.class);

    assertThat(ex.getCode()).isEqualTo("AGENT_USER_ALREADY_LINKED");
    assertThat(ex.getStatus()).isEqualTo(HttpStatus.CONFLICT);
  }

  @Test
  @DisplayName("a second open primary assignment for one property is refused with 409 "
      + "AGENT_PRIMARY_ALREADY_SET")
  void aSecondOpenPrimaryAssignmentIsRefused() {
    when(accessScope.requireUser()).thenReturn(actor());
    when(agentRepository.findByIdAndDeletedAtIsNull(AGENT_ID)).thenReturn(Optional.of(agent()));
    when(propertyRepository.findByIdAndDeletedAtIsNull(PROPERTY_ID)).thenReturn(Optional.of(property()));
    when(
            assignmentRepository
                .existsByPropertyIdAndRoleAndAssignedToIsNullAndDeletedAtIsNull(
                    PROPERTY_ID, AssignmentRole.PRIMARY))
        .thenReturn(true);

    ApiException ex =
        catchThrowableOfType(
            () ->
                service.assign(
                    AGENT_ID, new AgentDtos.CreateAssignmentRequest(PROPERTY_ID, "PRIMARY", null)),
            ApiException.class);

    assertThat(ex.getCode()).isEqualTo("AGENT_PRIMARY_ALREADY_SET");
    assertThat(ex.getStatus()).isEqualTo(HttpStatus.CONFLICT);
  }

  @Test
  @DisplayName("the conversion rate is 0, not NaN, when the agent holds no leads")
  void conversionRateIsZeroWhenThereAreNoLeads() {
    when(agentRepository.findByIdAndDeletedAtIsNull(AGENT_ID)).thenReturn(Optional.of(agent()));
    when(leadRepository.findAllByOwnerAgentIdAndDeletedAtIsNull(AGENT_ID)).thenReturn(List.of());
    when(viewingRepository.findAllByAgentIdAndDeletedAtIsNull(AGENT_ID)).thenReturn(List.of());
    when(listingRepository.findAllForRegister()).thenReturn(List.of());

    AgentDtos.PerformanceResponse performance = service.performance(AGENT_ID);

    assertThat(performance.totalLeads()).isZero();
    assertThat(performance.leadsConverted()).isZero();
    assertThat(performance.conversionRatePercent()).isZero();
  }
}
