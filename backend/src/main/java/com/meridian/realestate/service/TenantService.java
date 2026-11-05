package com.meridian.realestate.service;

import com.meridian.realestate.domain.Lease;
import com.meridian.realestate.domain.MaintenanceRequest;
import com.meridian.realestate.domain.Payment;
import com.meridian.realestate.domain.Tenant;
import com.meridian.realestate.domain.TenantStatus;
import com.meridian.realestate.domain.TenantType;
import com.meridian.realestate.domain.User;
import com.meridian.realestate.dto.ErrorMessages;
import com.meridian.realestate.dto.TenantDtos;
import com.meridian.realestate.exception.ApiException;
import com.meridian.realestate.repository.LeaseRepository;
import com.meridian.realestate.repository.MaintenanceRequestRepository;
import com.meridian.realestate.repository.PaymentRepository;
import com.meridian.realestate.repository.TenantRepository;
import com.meridian.realestate.security.AccessScope;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The tenant register and the read-only projections of what sits behind a tenant.
 *
 * <p><b>Why this class exists.</b> A tenant is the party that occupies a unit under a lease, so a
 * tenant profile is the hinge between the demand side and the rent side of the product: the page a
 * manager opens on a tenant is "who is this, what are they leasing, what have they paid and what
 * have they reported". Keeping the profile's writes and those three projections together is what
 * stops the tenant screen disagreeing with the lease, payment and maintenance modules about the
 * same party.</p>
 *
 * <p><b>The rules it protects.</b></p>
 * <ul>
 *   <li><b>A tenant's e-mail and phone are unique on the register.</b> Two profiles for one person
 *       split their lease history and make collections reconcile against the wrong party, so a
 *       second profile sharing either is refused as {@code DUPLICATE_TENANT_EMAIL} or
 *       {@code DUPLICATE_TENANT_PHONE} (409). The e-mail is stored lower-cased, matching the
 *       database's {@code ck_tenants_email_lower}, so the duplicate check and the lookup agree.</li>
 *   <li><b>A tenant with a running lease cannot be archived.</b> The tenancy and the rent it
 *       produces are live facts; archiving the party out from under them would strand them, so the
 *       refusal is a 409 named {@code TENANT_HAS_RUNNING_LEASE}.</li>
 *   <li><b>Archiving is soft, and once.</b> The leases, receipts and audit rows that reference the
 *       tenant stay coherent because the row survives; a second archive is refused.</li>
 * </ul>
 *
 * <p><b>What is a projection and what is not.</b> The leases, receipts and faults on the detail are
 * read-only projections from other modules - they are reported so the page can be drawn in one
 * request, but they are never written here. See the class note on the repository methods used for
 * each: the tenant's maintenance is reached through the board query and filtered here because no
 * repository method reads maintenance by its reporting tenant.</p>
 */
@Service
public class TenantService {

  private final TenantRepository tenantRepository;
  private final LeaseRepository leaseRepository;
  private final PaymentRepository paymentRepository;
  private final MaintenanceRequestRepository maintenanceRepository;
  private final BusinessNumberService businessNumbers;
  private final AuditRecordingService audit;
  private final AccessScope accessScope;

  public TenantService(
      TenantRepository tenantRepository,
      LeaseRepository leaseRepository,
      PaymentRepository paymentRepository,
      MaintenanceRequestRepository maintenanceRepository,
      BusinessNumberService businessNumbers,
      AuditRecordingService audit,
      AccessScope accessScope) {
    this.tenantRepository = tenantRepository;
    this.leaseRepository = leaseRepository;
    this.paymentRepository = paymentRepository;
    this.maintenanceRepository = maintenanceRepository;
    this.businessNumbers = businessNumbers;
    this.audit = audit;
    this.accessScope = accessScope;
  }

  /* ====================================================================== */
  /* Reads                                                                  */
  /* ====================================================================== */

  /** The live tenant behind an id, or a 404. Archived tenants are not addressable. */
  @Transactional(readOnly = true)
  public Tenant require(UUID tenantId) {
    return tenantRepository
        .findByIdAndDeletedAtIsNull(tenantId)
        .orElseThrow(() -> ApiException.notFound(ErrorMessages.friendly("TENANT_NOT_FOUND")));
  }

  /** The tenant register, filtered by status, type and city, with free-text search, paged. */
  @Transactional(readOnly = true)
  public Page<TenantDtos.TenantSummary> list(
      String status, String type, String city, String search, Pageable pageable) {

    TenantStatus statusFilter = parseOptionalStatus(status);
    TenantType typeFilter = parseOptionalType(type);
    String cityFilter = blankToNull(city);
    String term = blankToNull(search);

    List<Tenant> candidates =
        term == null
            ? tenantRepository.findAllByDeletedAtIsNull()
            : tenantRepository.search(term, Pageable.unpaged()).getContent();

    List<Tenant> filtered =
        candidates.stream()
            .filter(tenant -> statusFilter == null || tenant.getStatus() == statusFilter)
            .filter(tenant -> typeFilter == null || tenant.getTenantType() == typeFilter)
            .filter(
                tenant -> cityFilter == null || cityFilter.equalsIgnoreCase(tenant.getCity()))
            .toList();

    return pageOf(
        sortTenants(filtered, pageable.getSort()), pageable, TenantDtos.TenantSummary::from);
  }

  /** One tenant's full record, with the leases, receipts and faults behind them. */
  @Transactional(readOnly = true)
  public TenantDtos.TenantDetail detail(UUID tenantId) {
    return detailOf(require(tenantId));
  }

  /* ====================================================================== */
  /* Writes                                                                 */
  /* ====================================================================== */

  /** Create a tenant profile, minting their human-readable code and refusing a duplicate contact. */
  @Transactional
  public TenantDtos.TenantDetail create(TenantDtos.CreateTenantRequest request) {
    User actor = accessScope.requireUser();

    String email = lower(request.email());
    String phone = trim(request.phone());
    assertEmailFree(email, null);
    assertPhoneFree(phone, null);

    Tenant tenant = new Tenant();
    tenant.setTenantCode(businessNumbers.next(BusinessNumberService.TENANT));
    tenant.setTenantType(parseType(request.tenantType(), TenantType.INDIVIDUAL));
    tenant.setDisplayName(request.displayName().trim());
    tenant.setPrimaryContactName(trim(request.primaryContactName()));
    tenant.setEmail(email);
    tenant.setPhone(phone);
    tenant.setAlternatePhone(trim(request.alternatePhone()));
    tenant.setIdReference(trim(request.idReference()));
    tenant.setTaxReference(trim(request.taxReference()));
    tenant.setRegistrationNumber(trim(request.registrationNumber()));
    tenant.setOccupation(trim(request.occupation()));
    tenant.setEmployer(trim(request.employer()));
    tenant.setAnnualIncome(MoneyPolicy.money(request.annualIncome()));
    applyAddress(tenant, request);
    tenant.setStatus(parseStatusOrDefault(request.status(), TenantStatus.ACTIVE));
    tenant.setNotes(request.notes());
    tenant.setCreatedBy(actor.getId());
    tenant.setUpdatedBy(actor.getId());

    Tenant saved = tenantRepository.save(tenant);
    recordAudit(actor, AuditActions.CREATE, "Created tenant " + saved.getTenantCode(), saved);
    return detailOf(saved);
  }

  /** Change a tenant. Only the fields supplied are applied; a changed contact is re-checked. */
  @Transactional
  public TenantDtos.TenantDetail update(UUID tenantId, TenantDtos.UpdateTenantRequest request) {
    User actor = accessScope.requireUser();
    Tenant tenant = require(tenantId);

    if (request.tenantType() != null) tenant.setTenantType(parseType(request.tenantType(), null));
    if (request.displayName() != null && !request.displayName().isBlank()) {
      tenant.setDisplayName(request.displayName().trim());
    }
    if (request.primaryContactName() != null) {
      tenant.setPrimaryContactName(trim(request.primaryContactName()));
    }
    if (request.email() != null) {
      String email = lower(request.email());
      assertEmailFree(email, tenant.getId());
      tenant.setEmail(email);
    }
    if (request.phone() != null) {
      String phone = trim(request.phone());
      assertPhoneFree(phone, tenant.getId());
      tenant.setPhone(phone);
    }
    if (request.alternatePhone() != null) tenant.setAlternatePhone(trim(request.alternatePhone()));
    if (request.idReference() != null) tenant.setIdReference(trim(request.idReference()));
    if (request.taxReference() != null) tenant.setTaxReference(trim(request.taxReference()));
    if (request.registrationNumber() != null) {
      tenant.setRegistrationNumber(trim(request.registrationNumber()));
    }
    if (request.occupation() != null) tenant.setOccupation(trim(request.occupation()));
    if (request.employer() != null) tenant.setEmployer(trim(request.employer()));
    if (request.annualIncome() != null) {
      tenant.setAnnualIncome(MoneyPolicy.money(request.annualIncome()));
    }
    applyAddressUpdate(tenant, request);
    if (request.status() != null) tenant.setStatus(parseStatusOrDefault(request.status(), null));
    if (request.notes() != null) tenant.setNotes(request.notes());
    tenant.setUpdatedBy(actor.getId());

    Tenant saved = tenantRepository.save(tenant);
    recordAudit(actor, AuditActions.UPDATE, "Changed tenant " + saved.getTenantCode(), saved);
    return detailOf(saved);
  }

  /** Archive a tenant - a soft delete, once, refused while a lease is running. */
  @Transactional
  public TenantDtos.TenantDetail archive(UUID tenantId) {
    User actor = accessScope.requireUser();
    Tenant tenant =
        tenantRepository
            .findById(tenantId)
            .orElseThrow(() -> ApiException.notFound(ErrorMessages.friendly("TENANT_NOT_FOUND")));

    if (tenant.isDeleted()) {
      throw ApiException.rule(
          "TENANT_ALREADY_ARCHIVED", "This tenant has already been archived");
    }
    if (hasRunningLease(tenantId)) {
      throw ApiException.rule(
          "TENANT_HAS_RUNNING_LEASE", ErrorMessages.friendly("TENANT_HAS_RUNNING_LEASE"));
    }

    tenant.setDeletedAt(LocalDateTime.now());
    tenant.setUpdatedBy(actor.getId());
    Tenant saved = tenantRepository.save(tenant);
    recordAudit(actor, AuditActions.DELETE, "Archived tenant " + saved.getTenantCode(), saved);
    return detailOf(saved);
  }

  /* ====================================================================== */
  /* Internals                                                              */
  /* ====================================================================== */

  private TenantDtos.TenantDetail detailOf(Tenant tenant) {
    UUID tenantId = tenant.getId();

    List<Lease> leases =
        leaseRepository
            .findAllByTenantIdAndDeletedAtIsNull(tenantId, Pageable.unpaged())
            .getContent();
    List<TenantDtos.LeaseProjection> leaseRows = leases.stream().map(TenantDtos.LeaseProjection::from).toList();
    boolean runningLease =
        leases.stream().anyMatch(lease -> lease.getStatus() != null && lease.getStatus().isRunning());

    List<TenantDtos.PaymentProjection> paymentRows =
        paymentRepository
            .findAllByTenantIdAndDeletedAtIsNull(tenantId, Pageable.unpaged())
            .getContent()
            .stream()
            .map(TenantDtos.PaymentProjection::from)
            .toList();

    // The tenant's maintenance is reached through the board query and filtered here: there is no
    // repository method that reads maintenance by its reporting tenant, and the repository layer is
    // not ours to change. A fault the tenant did not report is not "the tenant's", so we filter on
    // the reporting column rather than on the property or the unit.
    List<TenantDtos.MaintenanceProjection> maintenanceRows =
        maintenanceRepository.findAllForBoard().stream()
            .filter(
                request ->
                    request.getReportedByTenant() != null
                        && tenantId.equals(request.getReportedByTenant().getId()))
            .map(TenantDtos.MaintenanceProjection::from)
            .toList();

    return new TenantDtos.TenantDetail(
        tenant.getId(),
        tenant.getTenantCode(),
        name(tenant.getTenantType()),
        tenant.getDisplayName(),
        tenant.getPrimaryContactName(),
        tenant.getEmail(),
        tenant.getPhone(),
        tenant.getAlternatePhone(),
        tenant.getIdReference(),
        tenant.getTaxReference(),
        tenant.getRegistrationNumber(),
        tenant.getOccupation(),
        tenant.getEmployer(),
        tenant.getAnnualIncome(),
        tenant.getAddressLine1(),
        tenant.getAddressLine2(),
        tenant.getLocality(),
        tenant.getCity(),
        tenant.getState(),
        tenant.getPostalCode(),
        tenant.getCountry(),
        name(tenant.getStatus()),
        tenant.getNotes(),
        runningLease,
        leaseRows,
        paymentRows,
        maintenanceRows,
        tenant.getCreatedAt(),
        tenant.getUpdatedAt());
  }

  private boolean hasRunningLease(UUID tenantId) {
    return leaseRepository
        .findAllByTenantIdAndDeletedAtIsNull(tenantId, Pageable.unpaged())
        .getContent()
        .stream()
        .anyMatch(lease -> lease.getStatus() != null && lease.getStatus().isRunning());
  }

  private void assertEmailFree(String email, UUID editingId) {
    if (email == null) {
      return;
    }
    tenantRepository
        .findByEmailIgnoreCaseAndDeletedAtIsNull(email)
        .filter(existing -> editingId == null || !editingId.equals(existing.getId()))
        .ifPresent(
            existing -> {
              throw ApiException.duplicate(
                  "DUPLICATE_TENANT_EMAIL", ErrorMessages.friendly("DUPLICATE_TENANT_EMAIL"));
            });
  }

  private void assertPhoneFree(String phone, UUID editingId) {
    if (phone == null) {
      return;
    }
    // The repository's phone lookup is an existence check without an exclusion, so a tenant keeping
    // its own number is not a clash: the check is skipped when the value is unchanged.
    if (editingId != null && phone.equalsIgnoreCase(phoneOf(editingId))) {
      return;
    }
    if (tenantRepository.existsByPhoneAndDeletedAtIsNull(phone)) {
      throw ApiException.duplicate(
          "DUPLICATE_TENANT_PHONE", ErrorMessages.friendly("DUPLICATE_TENANT_PHONE"));
    }
  }

  private String phoneOf(UUID tenantId) {
    return tenantRepository.findByIdAndDeletedAtIsNull(tenantId).map(Tenant::getPhone).orElse(null);
  }

  private static void applyAddress(Tenant tenant, TenantDtos.CreateTenantRequest request) {
    tenant.setAddressLine1(request.addressLine1());
    tenant.setAddressLine2(request.addressLine2());
    tenant.setLocality(request.locality());
    tenant.setCity(request.city());
    tenant.setState(request.state());
    tenant.setPostalCode(request.postalCode());
    tenant.setCountry(
        request.country() == null || request.country().isBlank()
            ? "India"
            : request.country().trim());
  }

  private static void applyAddressUpdate(Tenant tenant, TenantDtos.UpdateTenantRequest request) {
    if (request.addressLine1() != null) tenant.setAddressLine1(request.addressLine1());
    if (request.addressLine2() != null) tenant.setAddressLine2(request.addressLine2());
    if (request.locality() != null) tenant.setLocality(request.locality());
    if (request.city() != null) tenant.setCity(request.city());
    if (request.state() != null) tenant.setState(request.state());
    if (request.postalCode() != null) tenant.setPostalCode(request.postalCode());
    if (request.country() != null) tenant.setCountry(request.country());
  }

  private void recordAudit(User actor, String action, String description, Tenant tenant) {
    audit.record(
        actor.getId(),
        actor.getFullName(),
        AuditActions.TENANT,
        action,
        description,
        "TENANT",
        tenant.getId(),
        tenant.getTenantCode());
  }

  /**
   * A status supplied as a list filter, or null meaning "every status".
   *
   * <p>Deliberately separate from {@link #parseStatus}. The tenant register is read unfiltered by
   * default, so an absent status means "no filter" rather than a client mistake; reusing the
   * required-value parser here made {@code GET /api/v1/tenants} answer 400 to its own default
   * request. A value that is supplied still goes through {@link #parseStatus}.</p>
   */
  private static TenantStatus parseOptionalStatus(String value) {
    return value == null || value.isBlank() ? null : parseStatus(value);
  }

  private static TenantStatus parseStatus(String value) {
    if (value == null || value.isBlank()) return null;
    return parseStatusOrDefault(value, null);
  }

  private static TenantStatus parseStatusOrDefault(String value, TenantStatus fallback) {
    if (value == null || value.isBlank()) {
      if (fallback == null) {
        throw ApiException.badRequest("A tenant status is required");
      }
      return fallback;
    }
    try {
      return TenantStatus.valueOf(value.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException ex) {
      throw ApiException.badRequest("'" + value + "' is not a tenant status");
    }
  }

  /**
   * A type supplied as a list filter, or null meaning "every type".
   *
   * <p>Separate from {@link #parseType}: the register is read unfiltered by default, so an absent
   * type means "no filter". Passing a null fallback from the list method made
   * {@code GET /api/v1/tenants} answer 400 to its own default request.</p>
   */
  private static TenantType parseOptionalType(String value) {
    return value == null || value.isBlank() ? null : parseType(value, null);
  }

  private static TenantType parseType(String value, TenantType fallback) {
    if (value == null || value.isBlank()) {
      if (fallback == null) {
        throw ApiException.badRequest("A tenant type is required");
      }
      return fallback;
    }
    try {
      return TenantType.valueOf(value.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException ex) {
      throw ApiException.badRequest("'" + value + "' is not a tenant type");
    }
  }

  private static String name(Enum<?> value) {
    return value == null ? null : value.name();
  }

  private static String trim(String value) {
    return value == null ? null : value.trim();
  }

  private static String lower(String value) {
    return value == null || value.isBlank() ? null : value.trim().toLowerCase(Locale.ROOT);
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }

  // --------------------------------------------------------------------------
  // Paging and sorting, applied here because the combined repository query does
  // not exist. No repository query carries a literal order by.
  // --------------------------------------------------------------------------

  private static List<Tenant> sortTenants(List<Tenant> items, Sort sort) {
    if (sort == null || sort.isUnsorted()) {
      return items;
    }
    Comparator<Tenant> comparator = null;
    for (Sort.Order order : sort) {
      Comparator<Tenant> next = tenantComparator(order.getProperty());
      if (order.isDescending()) {
        next = next.reversed();
      }
      comparator = comparator == null ? next : comparator.thenComparing(next);
    }
    List<Tenant> sorted = new ArrayList<>(items);
    if (comparator != null) {
      sorted.sort(comparator);
    }
    return sorted;
  }

  private static Comparator<Tenant> tenantComparator(String property) {
    Comparator<Tenant> byCreatedAt =
        Comparator.comparing(
            Tenant::getCreatedAt, Comparator.nullsLast(Comparator.<LocalDateTime>naturalOrder()));
    return switch (property == null ? "" : property) {
      case "displayName" ->
          Comparator.comparing(
              Tenant::getDisplayName, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER));
      case "tenantCode" ->
          Comparator.comparing(
              Tenant::getTenantCode, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER));
      case "status" ->
          Comparator.comparing(
              current -> current.getStatus() == null ? "" : current.getStatus().name());
      case "city" ->
          Comparator.comparing(
              Tenant::getCity, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER));
      default -> byCreatedAt;
    };
  }

  private static <T, R> Page<R> pageOf(List<T> all, Pageable pageable, Function<T, R> mapper) {
    int total = all.size();
    int size = pageable.isPaged() ? pageable.getPageSize() : total;
    int start = pageable.isPaged() ? (int) Math.min(pageable.getOffset(), (long) total) : 0;
    int end = Math.min(start + size, total);
    List<R> content = all.subList(start, end).stream().map(mapper).toList();
    return new PageImpl<>(content, pageable, total);
  }
}
