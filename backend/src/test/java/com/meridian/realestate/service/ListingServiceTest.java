package com.meridian.realestate.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.meridian.realestate.config.AppProperties;
import com.meridian.realestate.domain.Listing;
import com.meridian.realestate.domain.ListingStatus;
import com.meridian.realestate.domain.Property;
import com.meridian.realestate.domain.Unit;
import com.meridian.realestate.domain.User;
import com.meridian.realestate.exception.ApiException;
import com.meridian.realestate.repository.AgentProfileRepository;
import com.meridian.realestate.repository.ListingChannelRepository;
import com.meridian.realestate.repository.ListingRepository;
import com.meridian.realestate.repository.ListingStatusHistoryRepository;
import com.meridian.realestate.repository.OfficeRepository;
import com.meridian.realestate.repository.PropertyRepository;
import com.meridian.realestate.repository.RentalApplicationRepository;
import com.meridian.realestate.repository.UnitRepository;
import com.meridian.realestate.repository.ViewingRepository;
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
import org.springframework.http.HttpStatus;

/**
 * The listing lifecycle, asserted without a database.
 *
 * <p>The promise pinned here is the ordering of the guard and the history write: publishing a listing
 * that is already live is refused with {@code LISTING_ALREADY_ACTIVE} (409) and leaves <b>no</b>
 * history row behind, so a refused publish never looks in the trail as though it had happened. A
 * successful publish, by contrast, writes exactly one row.</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ListingService: the publication state")
class ListingServiceTest {

  @Mock private ListingRepository listings;
  @Mock private ListingStatusHistoryRepository history;
  @Mock private ListingChannelRepository channels;
  @Mock private PropertyRepository properties;
  @Mock private UnitRepository units;
  @Mock private OfficeRepository offices;
  @Mock private AgentProfileRepository agents;
  @Mock private ViewingRepository viewings;
  @Mock private RentalApplicationRepository applications;
  @Mock private BusinessNumberService numbers;
  @Mock private AuditRecordingService audit;
  @Mock private AccessScope accessScope;

  private ListingService service;

  @BeforeEach
  void setUp() {
    service =
        new ListingService(
            listings, history, channels, properties, units, offices, agents, viewings, applications,
            numbers, audit, accessScope, new AppProperties());
  }

  private static User actor() {
    User user = new User();
    user.setId(UUID.randomUUID());
    user.setFirstName("Kavya");
    user.setLastName("Reddy");
    return user;
  }

  private static Property property(UUID id) {
    Property property = new Property();
    property.setId(id);
    property.setPropertyCode("ME-P-00001");
    property.setName("Marine Vista");
    return property;
  }

  private static Listing listing(UUID id, ListingStatus status, Property property, Unit unit) {
    Listing listing = new Listing();
    listing.setId(id);
    listing.setListingCode("ME-LS-000001");
    listing.setProperty(property);
    listing.setUnit(unit);
    listing.setStatus(status);
    listing.setListingStart(LocalDate.now());
    return listing;
  }

  @Test
  @DisplayName("publishing an already-active listing is refused and writes no history row")
  void publishingAnAlreadyActiveListingIsRefused() {
    UUID id = UUID.randomUUID();
    Listing live = listing(id, ListingStatus.ACTIVE, property(UUID.randomUUID()), null);
    when(accessScope.requireUser()).thenReturn(actor());
    when(accessScope.isCompanyWide()).thenReturn(true);
    when(listings.findByIdAndDeletedAtIsNull(id)).thenReturn(Optional.of(live));

    ApiException refused =
        catchThrowableOfType(() -> service.publish(id, null), ApiException.class);

    assertThat(refused.getCode()).isEqualTo("LISTING_ALREADY_ACTIVE");
    assertThat(refused.getStatus()).isEqualTo(HttpStatus.CONFLICT);
    // The refused publish leaves the listing and the trail untouched.
    verify(listings, never()).save(any());
    verify(history, never()).save(any());
    assertThat(live.getStatus()).isEqualTo(ListingStatus.ACTIVE);
  }

  @Test
  @DisplayName("a second live listing on the same unit is refused with LISTING_ALREADY_ACTIVE")
  void aSecondLiveListingOnTheSameUnitIsRefused() {
    UUID id = UUID.randomUUID();
    Unit unit = new Unit();
    unit.setId(UUID.randomUUID());
    unit.setUnitNumber("904");

    Listing draft = listing(id, ListingStatus.DRAFT, property(UUID.randomUUID()), unit);
    Listing competing = listing(UUID.randomUUID(), ListingStatus.ACTIVE, property(UUID.randomUUID()), unit);

    when(accessScope.requireUser()).thenReturn(actor());
    when(accessScope.isCompanyWide()).thenReturn(true);
    when(listings.findByIdAndDeletedAtIsNull(id)).thenReturn(Optional.of(draft));
    when(listings.findAllForRegister()).thenReturn(List.of(competing));

    ApiException refused =
        catchThrowableOfType(() -> service.publish(id, null), ApiException.class);

    assertThat(refused.getCode()).isEqualTo("LISTING_ALREADY_ACTIVE");
    verify(listings, never()).save(any());
    verify(history, never()).save(any());
  }

  @Test
  @DisplayName("publishing a draft listing writes exactly one history row")
  void publishingADraftListingWritesOneHistoryRow() {
    UUID id = UUID.randomUUID();
    Listing draft = listing(id, ListingStatus.DRAFT, property(UUID.randomUUID()), null);
    when(accessScope.requireUser()).thenReturn(actor());
    when(accessScope.isCompanyWide()).thenReturn(true);
    when(listings.findByIdAndDeletedAtIsNull(id)).thenReturn(Optional.of(draft));
    when(listings.save(any(Listing.class))).thenAnswer(call -> call.getArgument(0));

    service.publish(id, null);

    assertThat(draft.getStatus()).isEqualTo(ListingStatus.PUBLISHED);
    assertThat(draft.getPublishedAt()).isNotNull();
    assertThat(draft.getClosedAt()).isNull();
    verify(listings).save(draft);
    verify(history).save(any());
  }
}
