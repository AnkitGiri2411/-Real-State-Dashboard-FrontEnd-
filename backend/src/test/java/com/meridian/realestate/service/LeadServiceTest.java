package com.meridian.realestate.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.meridian.realestate.domain.AgentProfile;
import com.meridian.realestate.domain.AgentStatus;
import com.meridian.realestate.domain.Lead;
import com.meridian.realestate.domain.LeadStage;
import com.meridian.realestate.domain.User;
import com.meridian.realestate.dto.LeadDtos;
import com.meridian.realestate.exception.ApiException;
import com.meridian.realestate.repository.AgentProfileRepository;
import com.meridian.realestate.repository.BuyerRepository;
import com.meridian.realestate.repository.LeadActivityRepository;
import com.meridian.realestate.repository.LeadAssignmentRepository;
import com.meridian.realestate.repository.LeadFollowupRepository;
import com.meridian.realestate.repository.LeadRepository;
import com.meridian.realestate.repository.LeadSourceRepository;
import com.meridian.realestate.repository.OfficeRepository;
import com.meridian.realestate.repository.PropertyRepository;
import com.meridian.realestate.repository.PropertyTypeRepository;
import com.meridian.realestate.repository.RentalApplicationRepository;
import com.meridian.realestate.repository.TenantRepository;
import com.meridian.realestate.repository.UnitRepository;
import com.meridian.realestate.repository.ViewingRepository;
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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

/**
 * The lead pipeline, asserted without a database.
 *
 * <p>Three promises are pinned here. The stage machine permits exactly the moves the lifecycle
 * states and refuses the rest ({@code LEAD_TRANSITION_NOT_ALLOWED}, 409). An inactive agent cannot
 * be handed work ({@code LEAD_ASSIGN_INACTIVE_AGENT}). The pipeline groups by stage correctly, and
 * the funnel is cumulative - so it can only narrow, which is the one property that makes it a funnel
 * rather than a bar chart.</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("LeadService: the pipeline, its transitions and assignment")
class LeadServiceTest {

  @Mock private LeadRepository leads;
  @Mock private LeadActivityRepository activities;
  @Mock private LeadAssignmentRepository assignments;
  @Mock private LeadFollowupRepository followups;
  @Mock private LeadSourceRepository sources;
  @Mock private PropertyTypeRepository propertyTypes;
  @Mock private PropertyRepository properties;
  @Mock private UnitRepository units;
  @Mock private OfficeRepository offices;
  @Mock private AgentProfileRepository agents;
  @Mock private ViewingRepository viewings;
  @Mock private RentalApplicationRepository applications;
  @Mock private TenantRepository tenants;
  @Mock private BuyerRepository buyers;
  @Mock private BusinessNumberService numbers;
  @Mock private AuditRecordingService audit;
  @Mock private AccessScope accessScope;

  private LeadService service;

  @BeforeEach
  void setUp() {
    service =
        new LeadService(
            leads, activities, assignments, followups, sources, propertyTypes, properties, units,
            offices, agents, viewings, applications, tenants, buyers, numbers, audit, accessScope);
  }

  private static User actor() {
    User user = new User();
    user.setId(UUID.randomUUID());
    user.setFirstName("Imran");
    user.setLastName("Sheikh");
    return user;
  }

  private static Lead lead(UUID id, LeadStage stage) {
    Lead lead = new Lead();
    lead.setId(id);
    lead.setLeadCode("ME-LD-000001");
    lead.setDisplayName("Prospective tenant");
    lead.setStage(stage);
    return lead;
  }

  private static AgentProfile agent(AgentStatus status) {
    AgentProfile agent = new AgentProfile();
    agent.setId(UUID.randomUUID());
    agent.setStatus(status);
    agent.setUser(actor());
    return agent;
  }

  @Test
  @DisplayName("every legal stage transition is allowed and an illegal one is refused")
  void theTransitionMapIsExactlyAsDesigned() {
    assertThat(LeadService.canTransition(LeadStage.NEW, LeadStage.CONTACTED)).isTrue();
    assertThat(LeadService.canTransition(LeadStage.NEW, LeadStage.LOST)).isTrue();
    assertThat(LeadService.canTransition(LeadStage.CONTACTED, LeadStage.QUALIFIED)).isTrue();
    assertThat(LeadService.canTransition(LeadStage.CONTACTED, LeadStage.LOST)).isTrue();
    assertThat(LeadService.canTransition(LeadStage.QUALIFIED, LeadStage.VIEWING)).isTrue();
    assertThat(LeadService.canTransition(LeadStage.QUALIFIED, LeadStage.NEGOTIATION)).isTrue();
    assertThat(LeadService.canTransition(LeadStage.QUALIFIED, LeadStage.LOST)).isTrue();
    assertThat(LeadService.canTransition(LeadStage.VIEWING, LeadStage.NEGOTIATION)).isTrue();
    assertThat(LeadService.canTransition(LeadStage.VIEWING, LeadStage.WON)).isTrue();
    assertThat(LeadService.canTransition(LeadStage.VIEWING, LeadStage.LOST)).isTrue();
    assertThat(LeadService.canTransition(LeadStage.NEGOTIATION, LeadStage.WON)).isTrue();
    assertThat(LeadService.canTransition(LeadStage.NEGOTIATION, LeadStage.LOST)).isTrue();

    // Moves the lifecycle does not state are refused.
    assertThat(LeadService.canTransition(LeadStage.NEW, LeadStage.QUALIFIED)).isFalse();
    assertThat(LeadService.canTransition(LeadStage.NEW, LeadStage.WON)).isFalse();
    assertThat(LeadService.canTransition(LeadStage.CONTACTED, LeadStage.WON)).isFalse();
    assertThat(LeadService.canTransition(LeadStage.WON, LeadStage.CONTACTED)).isFalse();
    assertThat(LeadService.canTransition(LeadStage.LOST, LeadStage.NEW)).isFalse();
    assertThat(LeadService.canTransition(LeadStage.NEW, LeadStage.NEW)).isFalse();

    // Both terminal stages are terminal.
    assertThat(LeadService.nextStages(LeadStage.WON)).isEmpty();
    assertThat(LeadService.nextStages(LeadStage.LOST)).isEmpty();
  }

  @Test
  @DisplayName("a transition the lifecycle does not permit is refused through the service")
  void anIllegalTransitionIsRefusedThroughTheService() {
    UUID id = UUID.randomUUID();
    Lead lead = lead(id, LeadStage.NEW);
    when(accessScope.requireUser()).thenReturn(actor());
    when(accessScope.isCompanyWide()).thenReturn(true);
    when(leads.findByIdAndDeletedAtIsNull(id)).thenReturn(Optional.of(lead));

    ApiException refused =
        catchThrowableOfType(
            () -> service.transition(id, new LeadDtos.StageTransitionRequest("WON", null, null)),
            ApiException.class);

    assertThat(refused.getCode()).isEqualTo("LEAD_TRANSITION_NOT_ALLOWED");
    assertThat(refused.getStatus()).isEqualTo(HttpStatus.CONFLICT);
    verify(leads, never()).save(any());
  }

  @Test
  @DisplayName("a legal move to WON writes the won moment and clears any loss")
  void movingToWonWritesTheOutcomeColumns() {
    UUID id = UUID.randomUUID();
    Lead lead = lead(id, LeadStage.VIEWING);
    lead.setLostAt(LocalDateTime.now().minusDays(3));
    lead.setLostReason("stale");
    when(accessScope.requireUser()).thenReturn(actor());
    when(accessScope.isCompanyWide()).thenReturn(true);
    when(leads.findByIdAndDeletedAtIsNull(id)).thenReturn(Optional.of(lead));
    when(leads.save(any(Lead.class))).thenAnswer(call -> call.getArgument(0));

    service.transition(id, new LeadDtos.StageTransitionRequest("WON", "Signed", null));

    assertThat(lead.getStage()).isEqualTo(LeadStage.WON);
    assertThat(lead.getWonAt()).isNotNull();
    assertThat(lead.getLostAt()).isNull();
    assertThat(lead.getLostReason()).isNull();
    verify(leads).save(lead);
  }

  @Test
  @DisplayName("an inactive agent cannot be assigned a lead")
  void anInactiveAgentCannotBeAssigned() {
    UUID leadId = UUID.randomUUID();
    Lead lead = lead(leadId, LeadStage.NEW);
    AgentProfile inactive = agent(AgentStatus.INACTIVE);

    when(accessScope.requireUser()).thenReturn(actor());
    when(accessScope.isCompanyWide()).thenReturn(true);
    when(leads.findByIdAndDeletedAtIsNull(leadId)).thenReturn(Optional.of(lead));
    when(agents.findByIdAndDeletedAtIsNull(inactive.getId())).thenReturn(Optional.of(inactive));

    ApiException refused =
        catchThrowableOfType(
            () -> service.assign(leadId, new LeadDtos.AssignLeadRequest(inactive.getId(), "hand over")),
            ApiException.class);

    assertThat(refused.getCode()).isEqualTo("LEAD_ASSIGN_INACTIVE_AGENT");
    assertThat(refused.getStatus()).isEqualTo(HttpStatus.CONFLICT);
    // Checked before anything is written: neither the lead nor an assignment row is touched.
    verify(leads, never()).save(any());
    verify(assignments, never()).save(any());
  }

  @Test
  @DisplayName("the pipeline counts group by stage and sum only the open pipeline value")
  void thePipelineCountsGroupByStage() {
    when(leads.countGroupedByStage())
        .thenReturn(
            List.of(
                new Object[] {LeadStage.NEW, 3L},
                new Object[] {LeadStage.CONTACTED, 2L},
                new Object[] {LeadStage.WON, 1L}));
    when(leads.sumExpectedValueInOpenStages(any())).thenReturn(new BigDecimal("15000.00"));

    LeadDtos.PipelineResponse pipeline = service.pipeline();

    assertThat(pipeline.totalLeads()).isEqualTo(6);
    assertThat(pipeline.openLeads()).isEqualTo(5);
    assertThat(pipeline.openPipelineValue()).isEqualByComparingTo("15000.00");
    assertThat(pipeline.stages()).hasSize(LeadStage.values().length);

    LeadDtos.StageBucket newBucket =
        pipeline.stages().stream().filter(bucket -> bucket.stage().equals("NEW")).findFirst().orElseThrow();
    assertThat(newBucket.count()).isEqualTo(3);
    assertThat(newBucket.open()).isTrue();

    LeadDtos.StageBucket wonBucket =
        pipeline.stages().stream().filter(bucket -> bucket.stage().equals("WON")).findFirst().orElseThrow();
    assertThat(wonBucket.count()).isEqualTo(1);
    assertThat(wonBucket.open()).isFalse();
  }

  @Test
  @DisplayName("the funnel is monotonically non-increasing")
  void theFunnelIsMonotonicallyNonIncreasing() {
    when(leads.countGroupedByStage())
        .thenReturn(
            List.of(
                new Object[] {LeadStage.NEW, 5L},
                new Object[] {LeadStage.CONTACTED, 4L},
                new Object[] {LeadStage.QUALIFIED, 3L},
                new Object[] {LeadStage.VIEWING, 2L},
                new Object[] {LeadStage.NEGOTIATION, 1L},
                new Object[] {LeadStage.WON, 1L},
                new Object[] {LeadStage.LOST, 2L}));

    LeadDtos.FunnelResponse funnel = service.funnel();

    assertThat(funnel.rungs()).hasSize(6);
    assertThat(funnel.totalLeads()).isEqualTo(18);
    // The top rung is every lead, and each rung can only be as wide or narrower than the one above.
    assertThat(funnel.rungs().get(0).count()).isEqualTo(18);
    for (int i = 1; i < funnel.rungs().size(); i++) {
      assertThat(funnel.rungs().get(i).count())
          .as("rung %s must not exceed rung %s", funnel.rungs().get(i).stage(), funnel.rungs().get(i - 1).stage())
          .isLessThanOrEqualTo(funnel.rungs().get(i - 1).count());
    }
    assertThat(funnel.rungs().get(funnel.rungs().size() - 1).stage()).isEqualTo("WON");
    assertThat(funnel.rungs().get(funnel.rungs().size() - 1).count()).isEqualTo(1);
  }

  @Test
  @DisplayName("a converted lead cannot be converted again")
  void aConvertedLeadCannotBeConvertedAgain() {
    UUID id = UUID.randomUUID();
    Lead lead = lead(id, LeadStage.WON);
    lead.setConvertedAt(LocalDateTime.now().minusDays(1));
    when(accessScope.requireUser()).thenReturn(actor());
    when(accessScope.isCompanyWide()).thenReturn(true);
    when(leads.findByIdAndDeletedAtIsNull(id)).thenReturn(Optional.of(lead));

    ApiException refused =
        catchThrowableOfType(
            () -> service.convert(id, new LeadDtos.ConvertLeadRequest(null, null, null)),
            ApiException.class);

    assertThat(refused.getCode()).isEqualTo("LEAD_ALREADY_CONVERTED");
    assertThat(refused.getStatus()).isEqualTo(HttpStatus.CONFLICT);
    verify(leads, never()).save(any());
  }
}
