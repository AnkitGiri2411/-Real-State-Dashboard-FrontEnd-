package com.meridian.realestate.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.meridian.realestate.domain.Office;
import com.meridian.realestate.domain.OfficeStatus;
import com.meridian.realestate.domain.User;
import com.meridian.realestate.dto.OfficeDtos;
import com.meridian.realestate.exception.ApiException;
import com.meridian.realestate.repository.AgentProfileRepository;
import com.meridian.realestate.repository.OfficeRepository;
import com.meridian.realestate.security.AccessScope;
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
 * The office register's rule, with every collaborator mocked and no database.
 *
 * <p>There is exactly one head office - the schema's partial unique index makes it so - and this
 * service refuses a second one with a clear 409 rather than letting the index report an opaque
 * integrity error. The decision to refuse rather than to silently move the flag is deliberate and
 * documented on {@link OfficeService}: the registered office is a fact the operator states, and
 * silently demoting one and promoting another would shift firm-level attribution without anyone
 * asking for it.</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("OfficeService: the single head office")
class OfficeServiceTest {

  private static final UUID EXISTING_HEAD_ID = UUID.randomUUID();

  @Mock private OfficeRepository officeRepository;
  @Mock private AgentProfileRepository agentProfileRepository;
  @Mock private AuditRecordingService audit;
  @Mock private AccessScope accessScope;

  private OfficeService service;

  @BeforeEach
  void setUp() {
    service = new OfficeService(officeRepository, agentProfileRepository, audit, accessScope);
  }

  private static User actor() {
    User user = new User();
    user.setId(UUID.randomUUID());
    user.setFirstName("Priyanka");
    user.setLastName("Sethi");
    return user;
  }

  private static Office existingHeadOffice() {
    Office office = new Office();
    office.setId(EXISTING_HEAD_ID);
    office.setOfficeCode("ME-HO");
    office.setName("Meridian Head Office");
    office.setCity("Mumbai");
    office.setHeadOffice(true);
    office.setStatus(OfficeStatus.ACTIVE);
    return office;
  }

  private static OfficeDtos.CreateOfficeRequest createRequest(String code, boolean headOffice) {
    return new OfficeDtos.CreateOfficeRequest(
        code,
        "Meridian Branch",
        "1 Branch Road",
        null,
        null,
        "Pune",
        null,
        null,
        null,
        null,
        null,
        headOffice,
        null);
  }

  @Test
  @DisplayName("setting a second head office is refused with 409 OFFICE_HEAD_OFFICE_EXISTS")
  void settingASecondHeadOfficeIsRefused() {
    when(accessScope.requireUser()).thenReturn(actor());
    when(officeRepository.existsByOfficeCodeIgnoreCaseAndDeletedAtIsNull("ME-BR")).thenReturn(false);
    when(officeRepository.findByIsHeadOfficeTrueAndDeletedAtIsNull())
        .thenReturn(Optional.of(existingHeadOffice()));

    ApiException ex =
        catchThrowableOfType(
            () -> service.create(createRequest("ME-BR", true)), ApiException.class);

    assertThat(ex.getCode()).isEqualTo("OFFICE_HEAD_OFFICE_EXISTS");
    assertThat(ex.getStatus()).isEqualTo(HttpStatus.CONFLICT);
    verify(officeRepository, never()).save(any());
  }
}
