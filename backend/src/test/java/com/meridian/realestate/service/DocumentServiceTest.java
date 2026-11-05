package com.meridian.realestate.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.meridian.realestate.config.AppProperties;
import com.meridian.realestate.domain.Document;
import com.meridian.realestate.domain.DocumentStatus;
import com.meridian.realestate.domain.DocumentType;
import com.meridian.realestate.domain.User;
import com.meridian.realestate.dto.DocumentDtos;
import com.meridian.realestate.exception.ApiException;
import com.meridian.realestate.repository.BuyerRepository;
import com.meridian.realestate.repository.DocumentRepository;
import com.meridian.realestate.repository.LeaseRepository;
import com.meridian.realestate.repository.OwnerRepository;
import com.meridian.realestate.repository.PropertyRepository;
import com.meridian.realestate.repository.TenantRepository;
import com.meridian.realestate.repository.UnitRepository;
import com.meridian.realestate.security.AccessScope;
import java.time.LocalDate;
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
 * The document register's rules, asserted without a database.
 *
 * <p>Three promises are pinned here. A document may not supersede itself
 * ({@code DOCUMENT_SUPERSEDES_ITSELF}, 409). An already-superseded document may not be superseded
 * again ({@code DOCUMENT_ALREADY_SUPERSEDED}, 409), and nothing at all is written on that refused path
 * - no status change is stamped. The expiry watchlist excludes archived documents, at the query and
 * again in the service, so a withdrawn entry cannot reach the desk as a renewal it has to act on.</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("DocumentService: versioning, superseding and the expiry watchlist")
class DocumentServiceTest {

  @Mock private DocumentRepository documents;
  @Mock private PropertyRepository properties;
  @Mock private UnitRepository units;
  @Mock private LeaseRepository leases;
  @Mock private TenantRepository tenants;
  @Mock private OwnerRepository owners;
  @Mock private BuyerRepository buyers;
  @Mock private AuditRecordingService audit;
  @Mock private AccessScope accessScope;

  private DocumentService service;

  @BeforeEach
  void setUp() {
    service =
        new DocumentService(
            documents,
            properties,
            units,
            leases,
            tenants,
            owners,
            buyers,
            audit,
            accessScope,
            new AppProperties());
  }

  private static User actor() {
    User user = new User();
    user.setId(UUID.randomUUID());
    user.setFirstName("Rohan");
    user.setLastName("Malhotra");
    return user;
  }

  private static Document document(UUID id, DocumentStatus status) {
    Document document = new Document();
    document.setId(id);
    document.setDocumentCode("ME-D-000001");
    document.setTitle("Building insurance");
    document.setDocumentType(DocumentType.INSURANCE_POLICY);
    document.setStatus(status);
    return document;
  }

  @Test
  @DisplayName("a document cannot supersede itself")
  void aDocumentCannotSupersedeItself() {
    UUID id = UUID.randomUUID();
    Document document = document(id, DocumentStatus.ACTIVE);
    when(accessScope.requireUser()).thenReturn(actor());
    when(documents.findByIdAndDeletedAtIsNull(id)).thenReturn(Optional.of(document));

    ApiException refused =
        catchThrowableOfType(() -> service.supersede(id, id), ApiException.class);

    assertThat(refused.getCode()).isEqualTo("DOCUMENT_SUPERSEDES_ITSELF");
    assertThat(refused.getStatus()).isEqualTo(HttpStatus.CONFLICT);
    // Nothing is written: neither the document nor any replacement.
    verify(documents, never()).save(any());
    assertThat(document.getStatus()).isEqualTo(DocumentStatus.ACTIVE);
  }

  @Test
  @DisplayName("an already-superseded document cannot be superseded again, and no status change is written")
  void anAlreadySupersededDocumentCannotBeSupersededAgain() {
    UUID id = UUID.randomUUID();
    UUID newer = UUID.randomUUID();
    Document document = document(id, DocumentStatus.SUPERSEDED);
    document.setSupersededBy(document(newer, DocumentStatus.ACTIVE));
    when(accessScope.requireUser()).thenReturn(actor());
    when(documents.findByIdAndDeletedAtIsNull(id)).thenReturn(Optional.of(document));

    ApiException refused =
        catchThrowableOfType(() -> service.supersede(id, newer), ApiException.class);

    assertThat(refused.getCode()).isEqualTo("DOCUMENT_ALREADY_SUPERSEDED");
    assertThat(refused.getStatus()).isEqualTo(HttpStatus.CONFLICT);
    // The refused path writes nothing at all: no status change, no replacement touched.
    verify(documents, never()).save(any());
    assertThat(document.getStatus()).isEqualTo(DocumentStatus.SUPERSEDED);
  }

  @Test
  @DisplayName("the expiry watchlist excludes archived documents")
  void theExpiryWatchlistExcludesArchivedDocuments() {
    LocalDate today = LocalDate.now();

    Document active = document(UUID.randomUUID(), DocumentStatus.ACTIVE);
    active.setExpiryDate(today.plusDays(10));

    // Withdrawn through the register's archive operation: the row survives but is soft-deleted.
    Document archived = document(UUID.randomUUID(), DocumentStatus.ACTIVE);
    archived.setExpiryDate(today.plusDays(5));
    archived.setDeletedAt(LocalDateTime.now());

    // Withdrawn by state rather than by delete - excluded all the same.
    Document statusArchived = document(UUID.randomUUID(), DocumentStatus.ARCHIVED);
    statusArchived.setExpiryDate(today.plusDays(2));

    when(documents.findExpiryWatchlist(eq(DocumentStatus.ACTIVE), any(LocalDate.class)))
        .thenReturn(List.of(active, archived, statusArchived));

    List<DocumentDtos.ExpiryEntry> watchlist = service.expiring(null);

    assertThat(watchlist).hasSize(1);
    assertThat(watchlist.get(0).id()).isEqualTo(active.getId());
  }
}
