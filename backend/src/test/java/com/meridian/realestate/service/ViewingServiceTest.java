package com.meridian.realestate.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.meridian.realestate.domain.Property;
import com.meridian.realestate.domain.User;
import com.meridian.realestate.domain.Viewing;
import com.meridian.realestate.domain.ViewingStatus;
import com.meridian.realestate.dto.ViewingDtos;
import com.meridian.realestate.exception.ApiException;
import com.meridian.realestate.repository.AgentProfileRepository;
import com.meridian.realestate.repository.BuyerRepository;
import com.meridian.realestate.repository.LeadRepository;
import com.meridian.realestate.repository.PropertyRepository;
import com.meridian.realestate.repository.TenantRepository;
import com.meridian.realestate.repository.UnitRepository;
import com.meridian.realestate.repository.ViewingRepository;
import com.meridian.realestate.repository.ViewingStatusHistoryRepository;
import com.meridian.realestate.security.AccessScope;
import java.time.LocalDateTime;
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
 * The viewing outcome, asserted without a database.
 *
 * <p>What is pinned here is exactly the behaviour {@code ck_viewings_outcome} makes non-negotiable:
 * a completion is refused without feedback, a cancellation is refused on an already-cancelled
 * viewing, and a successful completion writes the status, the completion time and the feedback
 * <em>together</em> - the entity is checked after the call to prove the three moved as one.</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ViewingService: the viewing outcome")
class ViewingServiceTest {

  @Mock private ViewingRepository viewings;
  @Mock private ViewingStatusHistoryRepository history;
  @Mock private PropertyRepository properties;
  @Mock private UnitRepository units;
  @Mock private LeadRepository leads;
  @Mock private BuyerRepository buyers;
  @Mock private TenantRepository tenants;
  @Mock private AgentProfileRepository agents;
  @Mock private BusinessNumberService numbers;
  @Mock private AuditRecordingService audit;
  @Mock private AccessScope accessScope;

  private ViewingService service;

  @BeforeEach
  void setUp() {
    service =
        new ViewingService(
            viewings, history, properties, units, leads, buyers, tenants, agents, numbers, audit,
            accessScope);
  }

  private static User actor() {
    User user = new User();
    user.setId(UUID.randomUUID());
    user.setFirstName("Sneha");
    user.setLastName("Kulkarni");
    return user;
  }

  private static Viewing viewing(UUID id, ViewingStatus status) {
    Viewing viewing = new Viewing();
    viewing.setId(id);
    viewing.setViewingCode("ME-V-000001");
    viewing.setProperty(new Property());
    viewing.setScheduledAt(LocalDateTime.now().plusDays(1));
    viewing.setStatus(status);
    return viewing;
  }

  @Test
  @DisplayName("completing a viewing without feedback is refused")
  void completingWithoutFeedbackIsRefused() {
    UUID id = UUID.randomUUID();
    Viewing viewing = viewing(id, ViewingStatus.SCHEDULED);
    when(accessScope.requireUser()).thenReturn(actor());
    when(accessScope.isCompanyWide()).thenReturn(true);
    when(viewings.findByIdAndDeletedAtIsNull(id)).thenReturn(Optional.of(viewing));

    ApiException refused =
        catchThrowableOfType(
            () -> service.complete(id, new ViewingDtos.CompleteViewingRequest(null, 4, true, null)),
            ApiException.class);

    assertThat(refused.getCode()).isEqualTo("VIEWING_NOT_COMPLETABLE");
    assertThat(refused.getStatus()).isEqualTo(HttpStatus.CONFLICT);
    // Nothing is written: the viewing is not left half-completed.
    verify(viewings, never()).save(any());
    assertThat(viewing.getStatus()).isEqualTo(ViewingStatus.SCHEDULED);
    assertThat(viewing.getCompletedAt()).isNull();
  }

  @Test
  @DisplayName("completing a viewing that is already closed is refused")
  void completingAnAlreadyClosedViewingIsRefused() {
    UUID id = UUID.randomUUID();
    Viewing viewing = viewing(id, ViewingStatus.CANCELLED);
    when(accessScope.requireUser()).thenReturn(actor());
    when(accessScope.isCompanyWide()).thenReturn(true);
    when(viewings.findByIdAndDeletedAtIsNull(id)).thenReturn(Optional.of(viewing));

    ApiException refused =
        catchThrowableOfType(
            () -> service.complete(id, new ViewingDtos.CompleteViewingRequest("Good visit", 4, true, null)),
            ApiException.class);

    assertThat(refused.getCode()).isEqualTo("VIEWING_NOT_COMPLETABLE");
    verify(viewings, never()).save(any());
  }

  @Test
  @DisplayName("cancelling an already-cancelled viewing is refused")
  void cancellingAnAlreadyCancelledViewingIsRefused() {
    UUID id = UUID.randomUUID();
    Viewing viewing = viewing(id, ViewingStatus.CANCELLED);
    when(accessScope.requireUser()).thenReturn(actor());
    when(accessScope.isCompanyWide()).thenReturn(true);
    when(viewings.findByIdAndDeletedAtIsNull(id)).thenReturn(Optional.of(viewing));

    ApiException refused =
        catchThrowableOfType(
            () -> service.cancel(id, new ViewingDtos.CancelViewingRequest("Called off")),
            ApiException.class);

    assertThat(refused.getCode()).isEqualTo("VIEWING_ALREADY_CANCELLED");
    assertThat(refused.getStatus()).isEqualTo(HttpStatus.CONFLICT);
    verify(viewings, never()).save(any());
  }

  @Test
  @DisplayName("completing a viewing sets the completion time and the feedback together")
  void completingSetsTheCompletionTimeAndFeedbackTogether() {
    UUID id = UUID.randomUUID();
    Viewing viewing = viewing(id, ViewingStatus.CONFIRMED);
    when(accessScope.requireUser()).thenReturn(actor());
    when(accessScope.isCompanyWide()).thenReturn(true);
    when(viewings.findByIdAndDeletedAtIsNull(id)).thenReturn(Optional.of(viewing));
    when(viewings.save(any(Viewing.class))).thenAnswer(call -> call.getArgument(0));

    ViewingDtos.ViewingDetail detail =
        service.complete(id, new ViewingDtos.CompleteViewingRequest("Loved the light", 5, true, null));

    // The status and its outcome columns moved as one.
    assertThat(viewing.getStatus()).isEqualTo(ViewingStatus.COMPLETED);
    assertThat(viewing.getCompletedAt()).isNotNull();
    assertThat(viewing.getFeedback()).isEqualTo("Loved the light");
    assertThat(viewing.getRating()).isEqualTo(5);
    assertThat(viewing.getInterested()).isTrue();
    assertThat(viewing.getCancelledReason()).isNull();

    // And it was one write, not a status write followed by a feedback write.
    verify(viewings).save(viewing);
    assertThat(detail.status()).isEqualTo("COMPLETED");
    assertThat(detail.feedback()).isEqualTo("Loved the light");
  }

  @Test
  @DisplayName("cancelling a scheduled viewing records the reason and no completion time")
  void cancellingRecordsTheReasonAndNoCompletionTime() {
    UUID id = UUID.randomUUID();
    Viewing viewing = viewing(id, ViewingStatus.SCHEDULED);
    viewing.setCompletedAt(null);
    when(accessScope.requireUser()).thenReturn(actor());
    when(accessScope.isCompanyWide()).thenReturn(true);
    when(viewings.findByIdAndDeletedAtIsNull(id)).thenReturn(Optional.of(viewing));
    when(viewings.save(any(Viewing.class))).thenAnswer(call -> call.getArgument(0));

    service.cancel(id, new ViewingDtos.CancelViewingRequest("Prospect postponed"));

    assertThat(viewing.getStatus()).isEqualTo(ViewingStatus.CANCELLED);
    assertThat(viewing.getCancelledReason()).isEqualTo("Prospect postponed");
    assertThat(viewing.getCompletedAt()).isNull();
    verify(viewings).save(viewing);
  }

  @Test
  @DisplayName("only a scheduled or confirmed viewing is open, and a completed one is terminal")
  void theLifecycleHasNoMoveOutOfAClosedViewing() {
    assertThat(ViewingService.nextStates(ViewingStatus.SCHEDULED)).containsExactlyInAnyOrder(
        ViewingStatus.CONFIRMED, ViewingStatus.COMPLETED, ViewingStatus.CANCELLED, ViewingStatus.NO_SHOW);
    assertThat(ViewingService.canTransition(ViewingStatus.CONFIRMED, ViewingStatus.COMPLETED)).isTrue();
    assertThat(ViewingService.canTransition(ViewingStatus.COMPLETED, ViewingStatus.CONFIRMED)).isFalse();
    assertThat(ViewingService.canTransition(ViewingStatus.CANCELLED, ViewingStatus.COMPLETED)).isFalse();
  }
}
