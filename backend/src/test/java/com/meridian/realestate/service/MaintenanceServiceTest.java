package com.meridian.realestate.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.meridian.realestate.config.AppProperties;
import com.meridian.realestate.domain.MaintenanceCategory;
import com.meridian.realestate.domain.MaintenancePriority;
import com.meridian.realestate.domain.MaintenanceRequest;
import com.meridian.realestate.domain.MaintenanceStatus;
import com.meridian.realestate.domain.MaintenanceStatusHistory;
import com.meridian.realestate.domain.Property;
import com.meridian.realestate.domain.User;
import com.meridian.realestate.domain.Vendor;
import com.meridian.realestate.domain.VendorCategory;
import com.meridian.realestate.domain.VendorStatus;
import com.meridian.realestate.dto.MaintenanceDtos;
import com.meridian.realestate.exception.ApiException;
import com.meridian.realestate.repository.BuildingRepository;
import com.meridian.realestate.repository.MaintenanceRequestRepository;
import com.meridian.realestate.repository.MaintenanceStatusHistoryRepository;
import com.meridian.realestate.repository.PropertyRepository;
import com.meridian.realestate.repository.TenantRepository;
import com.meridian.realestate.repository.UnitRepository;
import com.meridian.realestate.repository.UserRepository;
import com.meridian.realestate.repository.VendorRepository;
import com.meridian.realestate.security.AccessScope;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
 * The maintenance lifecycle, asserted without a database.
 *
 * <p>Four promises are pinned here, and each is one the schema cannot state on its own. The status
 * machine permits exactly the moves the lifecycle allows and refuses the rest, and <b>a refused move
 * leaves no history row</b> - the trail must record what happened rather than what was attempted.
 * Resolving stores the actual cost at the product's money scale, and does not force it to equal the
 * estimate, because the variance between the two is a fact operations reads. A vendor that is not on
 * the approved list for the fault's trade cannot be given the work. And the late flag is the
 * configured threshold applied to a request nobody has answered.</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MaintenanceService: the lifecycle, its trail, empanelment and the late flag")
class MaintenanceServiceTest {

  private static final int URGENT_HOURS = 24;

  @Mock private MaintenanceRequestRepository requests;
  @Mock private MaintenanceStatusHistoryRepository history;
  @Mock private VendorRepository vendors;
  @Mock private PropertyRepository properties;
  @Mock private UnitRepository units;
  @Mock private BuildingRepository buildings;
  @Mock private TenantRepository tenants;
  @Mock private UserRepository users;
  @Mock private BusinessNumberService numbers;
  @Mock private AuditRecordingService audit;
  @Mock private AccessScope accessScope;

  private MaintenanceService service;

  @BeforeEach
  void setUp() {
    AppProperties appProperties = new AppProperties();
    appProperties.getPortfolio().setMaintenanceUrgentHours(URGENT_HOURS);
    appProperties.getPortfolio().setTrendMonths(6);

    service =
        new MaintenanceService(
            requests,
            history,
            vendors,
            properties,
            units,
            buildings,
            tenants,
            users,
            numbers,
            audit,
            accessScope,
            appProperties);
  }

  // ---------------------------------------------------------------------------
  // Fixtures
  // ---------------------------------------------------------------------------

  private static User actor() {
    User user = new User();
    user.setId(UUID.randomUUID());
    user.setFirstName("Ganesh");
    user.setLastName("Patil");
    return user;
  }

  private static Property property(UUID id) {
    Property property = new Property();
    property.setId(id);
    property.setPropertyCode("ME-P-00001");
    property.setName("Meridian House");
    return property;
  }

  private static MaintenanceRequest request(UUID id, MaintenanceStatus status) {
    MaintenanceRequest request = new MaintenanceRequest();
    request.setId(id);
    request.setRequestCode("ME-M-00001");
    request.setProperty(property(UUID.randomUUID()));
    request.setCategory(MaintenanceCategory.PLUMBING);
    request.setPriority(MaintenancePriority.MEDIUM);
    request.setStatus(status);
    request.setTitle("Leaking riser");
    request.setReportedAt(LocalDateTime.now().minusDays(2));
    return request;
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

  private void stubActor() {
    lenient().when(accessScope.requireUser()).thenReturn(actor());
  }

  private void stubLoadable(MaintenanceRequest request) {
    when(requests.findByIdAndDeletedAtIsNull(request.getId())).thenReturn(Optional.of(request));
    lenient().when(requests.save(any(MaintenanceRequest.class))).thenAnswer(call -> call.getArgument(0));
  }

  // ---------------------------------------------------------------------------
  // The map
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("the transition map is exactly the lifecycle the schema states")
  void theTransitionMapIsExactlyTheLifecycle() {
    Map<MaintenanceStatus, Set<MaintenanceStatus>> expected = new EnumMap<>(MaintenanceStatus.class);
    expected.put(
        MaintenanceStatus.OPEN,
        EnumSet.of(
            MaintenanceStatus.ASSIGNED, MaintenanceStatus.IN_PROGRESS, MaintenanceStatus.CANCELLED));
    expected.put(
        MaintenanceStatus.ASSIGNED,
        EnumSet.of(
            MaintenanceStatus.OPEN, MaintenanceStatus.IN_PROGRESS, MaintenanceStatus.CANCELLED));
    expected.put(
        MaintenanceStatus.IN_PROGRESS,
        EnumSet.of(MaintenanceStatus.RESOLVED, MaintenanceStatus.CANCELLED));
    expected.put(MaintenanceStatus.RESOLVED, EnumSet.of(MaintenanceStatus.CLOSED));
    expected.put(MaintenanceStatus.CLOSED, EnumSet.noneOf(MaintenanceStatus.class));
    expected.put(MaintenanceStatus.CANCELLED, EnumSet.noneOf(MaintenanceStatus.class));

    for (MaintenanceStatus status : MaintenanceStatus.values()) {
      assertThat(MaintenanceService.nextStatuses(status))
          .as("the moves allowed from %s", status)
          .containsExactlyInAnyOrderElementsOf(expected.get(status));
    }

    // Every legal move is allowed ...
    assertThat(MaintenanceService.canTransition(MaintenanceStatus.OPEN, MaintenanceStatus.ASSIGNED)).isTrue();
    assertThat(MaintenanceService.canTransition(MaintenanceStatus.OPEN, MaintenanceStatus.IN_PROGRESS)).isTrue();
    assertThat(MaintenanceService.canTransition(MaintenanceStatus.OPEN, MaintenanceStatus.CANCELLED)).isTrue();
    assertThat(MaintenanceService.canTransition(MaintenanceStatus.ASSIGNED, MaintenanceStatus.OPEN)).isTrue();
    assertThat(MaintenanceService.canTransition(MaintenanceStatus.ASSIGNED, MaintenanceStatus.IN_PROGRESS)).isTrue();
    assertThat(MaintenanceService.canTransition(MaintenanceStatus.ASSIGNED, MaintenanceStatus.CANCELLED)).isTrue();
    assertThat(MaintenanceService.canTransition(MaintenanceStatus.IN_PROGRESS, MaintenanceStatus.RESOLVED)).isTrue();
    assertThat(MaintenanceService.canTransition(MaintenanceStatus.IN_PROGRESS, MaintenanceStatus.CANCELLED)).isTrue();
    assertThat(MaintenanceService.canTransition(MaintenanceStatus.RESOLVED, MaintenanceStatus.CLOSED)).isTrue();

    // ... and the rest are refused: no skipping the work, and nothing comes back from a settled fault.
    assertThat(MaintenanceService.canTransition(MaintenanceStatus.OPEN, MaintenanceStatus.RESOLVED)).isFalse();
    assertThat(MaintenanceService.canTransition(MaintenanceStatus.OPEN, MaintenanceStatus.CLOSED)).isFalse();
    assertThat(MaintenanceService.canTransition(MaintenanceStatus.ASSIGNED, MaintenanceStatus.RESOLVED)).isFalse();
    assertThat(MaintenanceService.canTransition(MaintenanceStatus.ASSIGNED, MaintenanceStatus.CLOSED)).isFalse();
    assertThat(MaintenanceService.canTransition(MaintenanceStatus.RESOLVED, MaintenanceStatus.IN_PROGRESS)).isFalse();
    assertThat(MaintenanceService.canTransition(MaintenanceStatus.CLOSED, MaintenanceStatus.OPEN)).isFalse();
    assertThat(MaintenanceService.canTransition(MaintenanceStatus.CANCELLED, MaintenanceStatus.OPEN)).isFalse();
    assertThat(MaintenanceService.canTransition(MaintenanceStatus.OPEN, MaintenanceStatus.OPEN)).isFalse();

    // The two terminal states are terminal; a resolved fault is not, because closing is still to come.
    assertThat(MaintenanceService.isTerminal(MaintenanceStatus.CLOSED)).isTrue();
    assertThat(MaintenanceService.isTerminal(MaintenanceStatus.CANCELLED)).isTrue();
    assertThat(MaintenanceService.isTerminal(MaintenanceStatus.RESOLVED)).isFalse();
    assertThat(MaintenanceService.isTerminal(MaintenanceStatus.IN_PROGRESS)).isFalse();
  }

  @Test
  @DisplayName("every legal move in the lifecycle is allowed through the service, and writes its trail")
  void everyLegalMoveIsAllowedThroughTheService() {
    List<MaintenanceStatus[]> moves =
        List.of(
            new MaintenanceStatus[] {MaintenanceStatus.OPEN, MaintenanceStatus.ASSIGNED},
            new MaintenanceStatus[] {MaintenanceStatus.OPEN, MaintenanceStatus.IN_PROGRESS},
            new MaintenanceStatus[] {MaintenanceStatus.OPEN, MaintenanceStatus.CANCELLED},
            new MaintenanceStatus[] {MaintenanceStatus.ASSIGNED, MaintenanceStatus.OPEN},
            new MaintenanceStatus[] {MaintenanceStatus.ASSIGNED, MaintenanceStatus.IN_PROGRESS},
            new MaintenanceStatus[] {MaintenanceStatus.ASSIGNED, MaintenanceStatus.CANCELLED},
            new MaintenanceStatus[] {MaintenanceStatus.IN_PROGRESS, MaintenanceStatus.CANCELLED},
            new MaintenanceStatus[] {MaintenanceStatus.RESOLVED, MaintenanceStatus.CLOSED});

    for (MaintenanceStatus[] move : moves) {
      MaintenanceStatus from = move[0];
      MaintenanceStatus to = move[1];

      MaintenanceRequest entity = request(UUID.randomUUID(), from);
      // Set up the facts the schema insists on for the target state, as an earlier operation would
      // have left them.
      entity.setAssignedVendor(vendor(VendorCategory.PLUMBING, true, VendorStatus.ACTIVE));
      entity.setAssignedAt(LocalDateTime.now().minusDays(1));
      entity.setStartedAt(LocalDateTime.now().minusHours(12));
      entity.setResolvedAt(LocalDateTime.now().minusHours(2));
      entity.setResolutionNotes("Repaired");

      stubActor();
      stubLoadable(entity);
      clearInvocations(history);

      service.transition(entity.getId(), new MaintenanceDtos.StatusTransitionRequest(to.name(), "moving on"));

      assertThat(entity.getStatus()).as("%s -> %s", from, to).isEqualTo(to);

      ArgumentCaptor<MaintenanceStatusHistory> trail =
          ArgumentCaptor.forClass(MaintenanceStatusHistory.class);
      verify(history).save(trail.capture());
      assertThat(trail.getValue().getFromStatus()).isEqualTo(from);
      assertThat(trail.getValue().getToStatus()).isEqualTo(to);
    }
  }

  @Test
  @DisplayName("an illegal transition is refused and writes no history row")
  void anIllegalTransitionIsRefusedAndWritesNoHistory() {
    UUID id = UUID.randomUUID();
    MaintenanceRequest entity = request(id, MaintenanceStatus.OPEN);

    stubActor();
    when(requests.findByIdAndDeletedAtIsNull(id)).thenReturn(Optional.of(entity));

    ApiException refused =
        catchThrowableOfType(
            () ->
                service.transition(
                    id, new MaintenanceDtos.StatusTransitionRequest("CLOSED", "close it")),
            ApiException.class);

    assertThat(refused.getCode()).isEqualTo("MAINTENANCE_TRANSITION_NOT_ALLOWED");
    assertThat(refused.getStatus()).isEqualTo(HttpStatus.CONFLICT);
    // Nothing was written: not the request, and not a trail row for a move that never happened.
    verify(requests, never()).save(any());
    verify(history, never()).save(any());
    assertThat(entity.getStatus()).isEqualTo(MaintenanceStatus.OPEN);
  }

  @Test
  @DisplayName("moving to ASSIGNED with nobody holding the work is refused")
  void movingToAssignedWithoutAHolderIsRefused() {
    UUID id = UUID.randomUUID();
    MaintenanceRequest entity = request(id, MaintenanceStatus.OPEN);

    stubActor();
    when(requests.findByIdAndDeletedAtIsNull(id)).thenReturn(Optional.of(entity));

    ApiException refused =
        catchThrowableOfType(
            () ->
                service.transition(
                    id, new MaintenanceDtos.StatusTransitionRequest("ASSIGNED", null)),
            ApiException.class);

    assertThat(refused.getCode()).isEqualTo("MAINTENANCE_TRANSITION_NOT_ALLOWED");
    assertThat(refused.getStatus()).isEqualTo(HttpStatus.CONFLICT);
    verify(requests, never()).save(any());
    verify(history, never()).save(any());
  }

  @Test
  @DisplayName("a move to RESOLVED is refused by the status endpoint and points at resolve")
  void movingToResolvedIsRefusedByTheStatusEndpoint() {
    UUID id = UUID.randomUUID();
    MaintenanceRequest entity = request(id, MaintenanceStatus.IN_PROGRESS);
    entity.setStartedAt(LocalDateTime.now().minusHours(3));

    stubActor();
    when(requests.findByIdAndDeletedAtIsNull(id)).thenReturn(Optional.of(entity));

    ApiException refused =
        catchThrowableOfType(
            () ->
                service.transition(
                    id, new MaintenanceDtos.StatusTransitionRequest("RESOLVED", null)),
            ApiException.class);

    assertThat(refused.getCode()).isEqualTo("MAINTENANCE_TRANSITION_NOT_ALLOWED");
    assertThat(refused.getStatus()).isEqualTo(HttpStatus.CONFLICT);
    verify(requests, never()).save(any());
    verify(history, never()).save(any());
  }

  @Test
  @DisplayName("resolving stores the actual cost at the product's scale and leaves the estimate alone")
  void resolvingStoresTheActualCost() {
    UUID id = UUID.randomUUID();
    MaintenanceRequest entity = request(id, MaintenanceStatus.IN_PROGRESS);
    entity.setStartedAt(LocalDateTime.now().minusHours(5));
    entity.setEstimatedCost(new BigDecimal("1000.00"));

    stubActor();
    stubLoadable(entity);

    service.resolve(
        id,
        new MaintenanceDtos.ResolveMaintenanceRequest(
            new BigDecimal("1234.565"), "Replaced the riser and tested the run", "done"));

    // Money is two decimal places, rounded HALF_UP - the third decimal goes up, not to even.
    assertThat(entity.getActualCost()).isEqualByComparingTo("1234.57");
    assertThat(entity.getActualCost().scale()).isEqualTo(2);
    // The estimate is what the approval conversation was about, and the two are not forced to agree.
    assertThat(entity.getEstimatedCost()).isEqualByComparingTo("1000.00");
    assertThat(entity.getStatus()).isEqualTo(MaintenanceStatus.RESOLVED);
    assertThat(entity.getResolvedAt()).isNotNull();
    assertThat(entity.getResolutionNotes()).isEqualTo("Replaced the riser and tested the run");

    ArgumentCaptor<MaintenanceStatusHistory> trail =
        ArgumentCaptor.forClass(MaintenanceStatusHistory.class);
    verify(history).save(trail.capture());
    assertThat(trail.getValue().getFromStatus()).isEqualTo(MaintenanceStatus.IN_PROGRESS);
    assertThat(trail.getValue().getToStatus()).isEqualTo(MaintenanceStatus.RESOLVED);
  }

  @Test
  @DisplayName("resolving a request that never started is refused (the lifecycle says so)")
  void resolvingAnUnstartedRequestIsRefused() {
    UUID id = UUID.randomUUID();
    MaintenanceRequest entity = request(id, MaintenanceStatus.OPEN);

    stubActor();
    when(requests.findByIdAndDeletedAtIsNull(id)).thenReturn(Optional.of(entity));

    ApiException refused =
        catchThrowableOfType(
            () ->
                service.resolve(
                    id,
                    new MaintenanceDtos.ResolveMaintenanceRequest(
                        new BigDecimal("10.00"), "quick fix", null)),
            ApiException.class);

    assertThat(refused.getCode()).isEqualTo("MAINTENANCE_TRANSITION_NOT_ALLOWED");
    assertThat(refused.getStatus()).isEqualTo(HttpStatus.CONFLICT);
    verify(requests, never()).save(any());
    verify(history, never()).save(any());
  }

  // ---------------------------------------------------------------------------
  // Empanelment
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("a vendor that is not on the approved list for the trade is refused")
  void aNonEmpanelledVendorIsRefused() {
    UUID id = UUID.randomUUID();
    MaintenanceRequest entity = request(id, MaintenanceStatus.OPEN);
    Vendor notEmpanelled = vendor(VendorCategory.PLUMBING, false, VendorStatus.ACTIVE);

    stubActor();
    when(requests.findByIdAndDeletedAtIsNull(id)).thenReturn(Optional.of(entity));
    when(vendors.findByIdAndDeletedAtIsNull(notEmpanelled.getId()))
        .thenReturn(Optional.of(notEmpanelled));

    ApiException refused =
        catchThrowableOfType(
            () ->
                service.assign(
                    id,
                    new MaintenanceDtos.AssignMaintenanceRequest(
                        notEmpanelled.getId(), null, "send the plumber")),
            ApiException.class);

    assertThat(refused.getCode()).isEqualTo("VENDOR_NOT_EMPANELLED");
    assertThat(refused.getStatus()).isEqualTo(HttpStatus.CONFLICT);
    // Checked before anything is written: neither the request nor a trail row is touched.
    verify(requests, never()).save(any());
    verify(history, never()).save(any());
  }

  @Test
  @DisplayName("an empanelled vendor of the wrong trade is refused as not approved for this work")
  void aVendorOfTheWrongTradeIsRefused() {
    UUID id = UUID.randomUUID();
    MaintenanceRequest entity = request(id, MaintenanceStatus.OPEN);
    Vendor electrician = vendor(VendorCategory.ELECTRICAL, true, VendorStatus.ACTIVE);

    stubActor();
    when(requests.findByIdAndDeletedAtIsNull(id)).thenReturn(Optional.of(entity));
    when(vendors.findByIdAndDeletedAtIsNull(electrician.getId())).thenReturn(Optional.of(electrician));

    ApiException refused =
        catchThrowableOfType(
            () ->
                service.assign(
                    id,
                    new MaintenanceDtos.AssignMaintenanceRequest(electrician.getId(), null, null)),
            ApiException.class);

    assertThat(refused.getCode()).isEqualTo("VENDOR_NOT_EMPANELLED");
    assertThat(refused.getStatus()).isEqualTo(HttpStatus.CONFLICT);
    verify(requests, never()).save(any());
    verify(history, never()).save(any());
  }

  @Test
  @DisplayName("a suspended vendor cannot be given new work, even though it is empanelled")
  void aSuspendedVendorIsRefused() {
    UUID id = UUID.randomUUID();
    MaintenanceRequest entity = request(id, MaintenanceStatus.OPEN);
    Vendor suspended = vendor(VendorCategory.PLUMBING, true, VendorStatus.SUSPENDED);

    stubActor();
    when(requests.findByIdAndDeletedAtIsNull(id)).thenReturn(Optional.of(entity));
    when(vendors.findByIdAndDeletedAtIsNull(suspended.getId())).thenReturn(Optional.of(suspended));

    ApiException refused =
        catchThrowableOfType(
            () ->
                service.assign(
                    id,
                    new MaintenanceDtos.AssignMaintenanceRequest(suspended.getId(), null, null)),
            ApiException.class);

    assertThat(refused.getCode()).isEqualTo("VENDOR_NOT_ASSIGNABLE");
    assertThat(refused.getStatus()).isEqualTo(HttpStatus.CONFLICT);
    verify(requests, never()).save(any());
  }

  @Test
  @DisplayName("assigning an empanelled vendor of the trade moves the request to ASSIGNED")
  void assigningAnEmpanelledVendorOfTheTradeIsAllowed() {
    UUID id = UUID.randomUUID();
    MaintenanceRequest entity = request(id, MaintenanceStatus.OPEN);
    Vendor plumber = vendor(VendorCategory.PLUMBING, true, VendorStatus.ACTIVE);

    stubActor();
    stubLoadable(entity);
    when(vendors.findByIdAndDeletedAtIsNull(plumber.getId())).thenReturn(Optional.of(plumber));

    service.assign(
        id, new MaintenanceDtos.AssignMaintenanceRequest(plumber.getId(), null, "send the plumber"));

    assertThat(entity.getAssignedVendor()).isSameAs(plumber);
    assertThat(entity.getStatus()).isEqualTo(MaintenanceStatus.ASSIGNED);
    assertThat(entity.getAssignedAt()).isNotNull();

    ArgumentCaptor<MaintenanceStatusHistory> trail =
        ArgumentCaptor.forClass(MaintenanceStatusHistory.class);
    verify(history).save(trail.capture());
    assertThat(trail.getValue().getFromStatus()).isEqualTo(MaintenanceStatus.OPEN);
    assertThat(trail.getValue().getToStatus()).isEqualTo(MaintenanceStatus.ASSIGNED);
  }

  // ---------------------------------------------------------------------------
  // Reads
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("a request nobody has answered past the threshold is flagged late, and filters overdue")
  void lateWorkIsFlaggedAndFiltered() {
    MaintenanceRequest unansweredOld = request(UUID.randomUUID(), MaintenanceStatus.OPEN);
    unansweredOld.setReportedAt(LocalDateTime.now().minusHours(URGENT_HOURS + 1));
    unansweredOld.setTitle("Leaking riser");
    unansweredOld.setCreatedAt(LocalDateTime.now().minusDays(3));

    MaintenanceRequest answeredOld = request(UUID.randomUUID(), MaintenanceStatus.ASSIGNED);
    answeredOld.setReportedAt(LocalDateTime.now().minusHours(URGENT_HOURS + 1));
    answeredOld.setAssignedAt(LocalDateTime.now().minusHours(1));
    answeredOld.setCreatedAt(LocalDateTime.now().minusDays(2));

    MaintenanceRequest unansweredFresh = request(UUID.randomUUID(), MaintenanceStatus.OPEN);
    unansweredFresh.setReportedAt(LocalDateTime.now().minusHours(1));
    unansweredFresh.setCreatedAt(LocalDateTime.now().minusDays(1));

    when(requests.findAllForBoard())
        .thenReturn(List.of(unansweredOld, answeredOld, unansweredFresh));

    List<MaintenanceDtos.MaintenanceSummary> board =
        service.list(
                null, null, null, null, null, null, null, null, null, null, null, PageRequest.of(0, 20))
            .items();

    assertThat(board).hasSize(3);
    assertThat(lateOf(board, unansweredOld.getId()))
        .as("nobody has answered it, past the threshold")
        .isTrue();
    assertThat(overdueOf(board, unansweredOld.getId())).isTrue();
    assertThat(lateOf(board, answeredOld.getId())).as("an assignee is an answer").isFalse();
    assertThat(lateOf(board, unansweredFresh.getId())).as("still inside the threshold").isFalse();

    List<MaintenanceDtos.MaintenanceSummary> overdue =
        service.list(
                null, null, null, null, null, null, null, null, null, Boolean.TRUE, null,
                PageRequest.of(0, 20))
            .items();

    assertThat(overdue).hasSize(1);
    assertThat(overdue.get(0).id()).isEqualTo(unansweredOld.getId());
  }

  private static boolean lateOf(
      List<MaintenanceDtos.MaintenanceSummary> rows, UUID requestId) {
    return byId(rows, requestId).late();
  }

  private static boolean overdueOf(
      List<MaintenanceDtos.MaintenanceSummary> rows, UUID requestId) {
    return byId(rows, requestId).overdue();
  }

  private static MaintenanceDtos.MaintenanceSummary byId(
      List<MaintenanceDtos.MaintenanceSummary> rows, UUID requestId) {
    return rows.stream().filter(row -> requestId.equals(row.id())).findFirst().orElseThrow();
  }

  @Test
  @DisplayName("the summary reads the open and urgent sets the schema indexes, and sums only actual cost")
  void theSummaryReadsTheOpenAndUrgentSets() {
    MaintenanceRequestRepository.StatusCount openRow =
        mock(MaintenanceRequestRepository.StatusCount.class);
    when(openRow.getStatus()).thenReturn(MaintenanceStatus.OPEN);
    when(openRow.getTotal()).thenReturn(4L);
    MaintenanceRequestRepository.StatusCount closedRow =
        mock(MaintenanceRequestRepository.StatusCount.class);
    when(closedRow.getStatus()).thenReturn(MaintenanceStatus.CLOSED);
    when(closedRow.getTotal()).thenReturn(2L);

    MaintenanceRequestRepository.PriorityCount urgentRow =
        mock(MaintenanceRequestRepository.PriorityCount.class);
    when(urgentRow.getPriority()).thenReturn(MaintenancePriority.URGENT);
    when(urgentRow.getTotal()).thenReturn(3L);

    MaintenanceRequest late = request(UUID.randomUUID(), MaintenanceStatus.OPEN);
    late.setReportedAt(LocalDateTime.now().minusHours(URGENT_HOURS + 5));

    when(requests.countByStatusInAndDeletedAtIsNull(eq(MaintenanceService.OPEN_STATUSES))).thenReturn(5L);
    when(requests.countByStatusInAndPriorityInAndDeletedAtIsNull(
            eq(MaintenanceService.OPEN_STATUSES), eq(MaintenanceService.URGENT_PRIORITIES)))
        .thenReturn(2L);
    when(requests.countGroupedByStatus()).thenReturn(List.of(openRow, closedRow));
    when(requests.countGroupedByPriority()).thenReturn(List.of(urgentRow));
    when(requests.sumActualCostBetween(any(), any())).thenReturn(new BigDecimal("48250.5"));
    when(requests.findAllForBoard()).thenReturn(List.of(late));

    MaintenanceDtos.MaintenanceSummaryResponse summary = service.summary();

    assertThat(summary.openCount()).isEqualTo(5);
    assertThat(summary.urgentOpenCount()).isEqualTo(2);
    assertThat(summary.lateOpenCount()).isEqualTo(1);
    // Actual spend, at the product's money scale - never the estimate.
    assertThat(summary.costToDate()).isEqualByComparingTo("48250.50");
    assertThat(summary.costToDate().scale()).isEqualTo(2);
    assertThat(summary.byStatus()).hasSize(MaintenanceStatus.values().length);
    assertThat(summary.byPriority()).hasSize(MaintenancePriority.values().length);
    assertThat(summary.byStatus().stream().filter(b -> b.status().equals("OPEN")).findFirst().orElseThrow()
            .count())
        .isEqualTo(4);
    assertThat(summary.byStatus().stream().filter(b -> b.status().equals("CLOSED")).findFirst().orElseThrow()
            .open())
        .isFalse();
    assertThat(summary.byPriority().stream().filter(b -> b.priority().equals("URGENT")).findFirst()
            .orElseThrow()
            .urgent())
        .isTrue();
  }

  @Test
  @DisplayName("the cost trend buckets actual spend by the month the fault was reported")
  void theCostTrendBucketsByReportedMonth() {
    LocalDate today = LocalDate.now();

    MaintenanceRequest thisMonth = request(UUID.randomUUID(), MaintenanceStatus.CLOSED);
    thisMonth.setReportedAt(today.atStartOfDay().plusHours(1));
    thisMonth.setActualCost(new BigDecimal("100.005"));
    thisMonth.setResolvedAt(today.atStartOfDay().plusHours(2));

    MaintenanceRequest lastMonth = request(UUID.randomUUID(), MaintenanceStatus.RESOLVED);
    lastMonth.setReportedAt(today.minusMonths(1).withDayOfMonth(1).atStartOfDay());
    lastMonth.setActualCost(new BigDecimal("50.00"));
    lastMonth.setResolvedAt(today.minusMonths(1).withDayOfMonth(2).atStartOfDay());

    when(requests.findAllForBoard()).thenReturn(List.of(thisMonth, lastMonth));

    MaintenanceDtos.CostTrendResponse trend =
        service.costTrend(today.minusMonths(1).withDayOfMonth(1), today);

    assertThat(trend.months()).hasSize(2);
    // HALF_UP at the boundary: a third decimal of five goes up.
    assertThat(trend.totalCost()).isEqualByComparingTo("150.01");
    assertThat(trend.months().get(1).actualCost()).isEqualByComparingTo("100.01");
    assertThat(trend.months().get(1).actualCost().scale()).isEqualTo(2);
    assertThat(trend.resolvedCount()).isEqualTo(2);
  }
}
