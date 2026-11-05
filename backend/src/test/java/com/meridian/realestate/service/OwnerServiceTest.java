package com.meridian.realestate.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.meridian.realestate.domain.Owner;
import com.meridian.realestate.domain.OwnerStatus;
import com.meridian.realestate.domain.OwnerType;
import com.meridian.realestate.domain.OwnershipRelationship;
import com.meridian.realestate.domain.Property;
import com.meridian.realestate.domain.PropertyOwnership;
import com.meridian.realestate.domain.User;
import com.meridian.realestate.dto.OwnerDtos;
import com.meridian.realestate.exception.ApiException;
import com.meridian.realestate.repository.OwnerRepository;
import com.meridian.realestate.repository.PropertyOwnershipRepository;
import com.meridian.realestate.repository.PropertyRepository;
import com.meridian.realestate.security.AccessScope;
import java.math.BigDecimal;
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
 * The ownership rules, with every collaborator mocked and no database.
 *
 * <p>The rule the schema cannot state - that the shares in force for one property total exactly 100%
 * - is the one a wrong refactor would quietly break, and no single screen would show it: the property
 * would simply have an owner statement that did not add up. Each of the four cases here pins one
 * half of it: a change that would push the open total above the whole is refused
 * ({@code OWNERSHIP_EXCEEDS_ONE_HUNDRED}); a holding of exactly the whole is accepted; a second
 * holding for the same owner in an overlapping period is refused ({@code OWNERSHIP_DUPLICATE_OWNER});
 * and an explicit validation reports the shortfall when the shares do not reach the whole
 * ({@code OWNERSHIP_NOT_ONE_HUNDRED}). Each is cheap to break and expensive to notice.</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("OwnerService: the 100% ownership rule")
class OwnerServiceTest {

  private static final UUID PROPERTY_ID = UUID.randomUUID();
  private static final UUID OWNER_ID = UUID.randomUUID();
  private static final UUID OTHER_OWNER_ID = UUID.randomUUID();
  private static final UUID SHARE_ID = UUID.randomUUID();

  @Mock private OwnerRepository ownerRepository;
  @Mock private PropertyOwnershipRepository ownershipRepository;
  @Mock private PropertyRepository propertyRepository;
  @Mock private BusinessNumberService businessNumbers;
  @Mock private AuditRecordingService audit;
  @Mock private AccessScope accessScope;

  private OwnerService service;

  @BeforeEach
  void setUp() {
    service =
        new OwnerService(
            ownerRepository,
            ownershipRepository,
            propertyRepository,
            businessNumbers,
            audit,
            accessScope);
  }

  private static User actor() {
    User user = new User();
    user.setId(UUID.randomUUID());
    user.setFirstName("Rohan");
    user.setLastName("Malhotra");
    return user;
  }

  private static Property property() {
    Property property = new Property();
    property.setId(PROPERTY_ID);
    property.setPropertyCode("ME-P-00001");
    property.setName("Meridian One");
    return property;
  }

  private static Owner owner(UUID id) {
    Owner owner = new Owner();
    owner.setId(id);
    owner.setOwnerCode("ME-O-00001");
    owner.setDisplayName("Rukmini Iyengar");
    owner.setOwnerType(OwnerType.INDIVIDUAL);
    owner.setStatus(OwnerStatus.ACTIVE);
    return owner;
  }

  /** An open holding (no end date) for an owner, at the given share. */
  private static PropertyOwnership openShare(UUID ownerId, String percentage) {
    PropertyOwnership share = new PropertyOwnership();
    share.setId(UUID.randomUUID());
    share.setOwner(owner(ownerId));
    share.setOwnershipPercentage(new BigDecimal(percentage));
    share.setRelationship(OwnershipRelationship.CO_OWNER);
    share.setEffectiveFrom(LocalDate.of(2016, 6, 1));
    share.setEffectiveTo(null);
    return share;
  }

  private OwnerDtos.CreateOwnershipRequest request(String percentage, UUID ownerId) {
    return new OwnerDtos.CreateOwnershipRequest(
        ownerId,
        new BigDecimal(percentage),
        "CO_OWNER",
        LocalDate.of(2024, 1, 1),
        null,
        null);
  }

  // ------------------------------------------------------------------- 100%

  @Test
  @DisplayName("a share that would push the open total above 100% is refused with 409 "
      + "OWNERSHIP_EXCEEDS_ONE_HUNDRED")
  void aShareThatExceedsOneHundredIsRefused() {
    when(accessScope.requireUser()).thenReturn(actor());
    when(propertyRepository.findByIdAndDeletedAtIsNull(PROPERTY_ID)).thenReturn(Optional.of(property()));
    when(ownerRepository.findByIdAndDeletedAtIsNull(OWNER_ID)).thenReturn(Optional.of(owner(OWNER_ID)));
    // Another owner already holds 60% of the property; a further 50% would make 110%.
    when(ownershipRepository.findAllForProperty(PROPERTY_ID))
        .thenReturn(List.of(openShare(OTHER_OWNER_ID, "60.00")));

    ApiException ex =
        catchThrowableOfType(
            () -> service.addOwnership(PROPERTY_ID, request("50.00", OWNER_ID)),
            ApiException.class);

    assertThat(ex.getCode()).isEqualTo("OWNERSHIP_EXCEEDS_ONE_HUNDRED");
    assertThat(ex.getStatus()).isEqualTo(HttpStatus.CONFLICT);
    verify(ownershipRepository, never()).save(any());
  }

  @Test
  @DisplayName("a holding of exactly 100% is accepted")
  void aHoldingOfExactlyOneHundredIsAccepted() {
    when(accessScope.requireUser()).thenReturn(actor());
    when(propertyRepository.findByIdAndDeletedAtIsNull(PROPERTY_ID)).thenReturn(Optional.of(property()));
    when(ownerRepository.findByIdAndDeletedAtIsNull(OWNER_ID)).thenReturn(Optional.of(owner(OWNER_ID)));
    when(ownershipRepository.findAllForProperty(PROPERTY_ID)).thenReturn(List.of());
    when(ownershipRepository.save(any(PropertyOwnership.class)))
        .thenAnswer(
            invocation -> {
              PropertyOwnership saved = invocation.getArgument(0);
              saved.setId(SHARE_ID);
              return saved;
            });

    OwnerDtos.OwnershipShare share = service.addOwnership(PROPERTY_ID, request("100.00", OWNER_ID));

    assertThat(share.percentage()).isEqualByComparingTo("100.00");
    assertThat(share.open()).isTrue();
    verify(ownershipRepository).save(any(PropertyOwnership.class));
  }

  // -------------------------------------------------------- duplicate owner

  @Test
  @DisplayName("a duplicate owner for the same period is refused with 409 OWNERSHIP_DUPLICATE_OWNER")
  void aDuplicateOwnerInTheSamePeriodIsRefused() {
    when(accessScope.requireUser()).thenReturn(actor());
    when(propertyRepository.findByIdAndDeletedAtIsNull(PROPERTY_ID)).thenReturn(Optional.of(property()));
    when(ownerRepository.findByIdAndDeletedAtIsNull(OWNER_ID)).thenReturn(Optional.of(owner(OWNER_ID)));
    // The same owner already holds an open share; a second would double-count them.
    when(ownershipRepository.findAllForProperty(PROPERTY_ID))
        .thenReturn(List.of(openShare(OWNER_ID, "100.00")));

    ApiException ex =
        catchThrowableOfType(
            () -> service.addOwnership(PROPERTY_ID, request("10.00", OWNER_ID)),
            ApiException.class);

    assertThat(ex.getCode()).isEqualTo("OWNERSHIP_DUPLICATE_OWNER");
    assertThat(ex.getStatus()).isEqualTo(HttpStatus.CONFLICT);
    verify(ownershipRepository, never()).save(any());
  }

  // ------------------------------------------------------------- validation

  @Test
  @DisplayName("validateTotal reports the shortfall when the shares total less than 100%")
  void validateTotalReportsTheShortfall() {
    when(propertyRepository.findByIdAndDeletedAtIsNull(PROPERTY_ID)).thenReturn(Optional.of(property()));
    when(ownershipRepository.findAllForProperty(PROPERTY_ID))
        .thenReturn(List.of(openShare(OTHER_OWNER_ID, "60.00")));

    ApiException ex =
        catchThrowableOfType(() -> service.validateTotal(PROPERTY_ID), ApiException.class);

    assertThat(ex.getCode()).isEqualTo("OWNERSHIP_NOT_ONE_HUNDRED");
    assertThat(ex.getStatus()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(ex.getDetails()).contains("total: 60.00", "shortfall: 40.00");
  }
}
