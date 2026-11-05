package com.meridian.realestate.service;

import com.meridian.realestate.domain.Office;
import com.meridian.realestate.domain.OfficeStatus;
import com.meridian.realestate.domain.User;
import com.meridian.realestate.dto.ErrorMessages;
import com.meridian.realestate.dto.OfficeDtos;
import com.meridian.realestate.exception.ApiException;
import com.meridian.realestate.repository.AgentProfileRepository;
import com.meridian.realestate.repository.OfficeRepository;
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
 * The office (branch) register.
 *
 * <p><b>Why this class exists.</b> An office is a real place that agents sit in and properties are
 * reported under, so it has its own register rather than being a string on a staff record. Keeping
 * its operations together is what lets office-level reporting attribute to a branch and its head
 * office consistently.</p>
 *
 * <p><b>The rule it protects: there is exactly one head office.</b> The schema enforces it with the
 * partial unique index {@code uq_offices_single_head} over
 * {@code (is_head_office) WHERE is_head_office = true AND deleted_at IS NULL} - two rows claiming to
 * be the registered office would silently double-count in office-level reporting. <b>This service's
 * decision is to refuse a second head office with a clear 409 rather than to silently move the
 * flag.</b> The reason is that the head office is a fact the operator states, not one the system
 * should infer: silently demoting the current registered office and promoting another would shift
 * firm-level attribution without anyone asking for it, and the operator would have no record of what
 * changed. So a second head office is refused, and the operator must first demote the current one.
 * The refusal is named {@code OFFICE_HEAD_OFFICE_EXISTS} and is a 409, not the opaque integrity
 * error the index would otherwise produce.</p>
 *
 * <p><b>On the audit module.</b> {@code V2} seeds no {@code OFFICE_*} permission and no {@code OFFICE}
 * audit module; offices belong to the agents domain in the schema ({@code V7} creates offices,
 * agent profiles and assignments together), so office changes are recorded under the
 * {@link AuditActions#AGENT} module with an {@code OFFICE} entity type. See the class note on the
 * controller for the permission codes this uses, which are likewise drawn from the agents module
 * because the product defines no office codes.</p>
 *
 * <p><b>A note on paging.</b> {@code OfficeRepository} offers no filtered query, so the filters, the
 * sort and the slice are applied here rather than putting a literal {@code order by} in a paged
 * query, which the product forbids.</p>
 */
@Service
public class OfficeService {

  private final OfficeRepository officeRepository;
  private final AgentProfileRepository agentProfileRepository;
  private final AuditRecordingService audit;
  private final AccessScope accessScope;

  public OfficeService(
      OfficeRepository officeRepository,
      AgentProfileRepository agentProfileRepository,
      AuditRecordingService audit,
      AccessScope accessScope) {
    this.officeRepository = officeRepository;
    this.agentProfileRepository = agentProfileRepository;
    this.audit = audit;
    this.accessScope = accessScope;
  }

  /* ====================================================================== */
  /* Reads                                                                  */
  /* ====================================================================== */

  /** The live office behind an id, or a 404. Archived offices are not addressable. */
  @Transactional(readOnly = true)
  public Office require(UUID officeId) {
    return officeRepository
        .findByIdAndDeletedAtIsNull(officeId)
        .orElseThrow(() -> ApiException.notFound(ErrorMessages.friendly("OFFICE_NOT_FOUND")));
  }

  /** The office register, filtered by status and city, with free-text search, paged. */
  @Transactional(readOnly = true)
  public Page<OfficeDtos.OfficeSummary> list(
      String status, String city, String search, Pageable pageable) {

    OfficeStatus statusFilter = parseStatus(status);
    String cityFilter = blankToNull(city);
    String term = blankToNull(search);
    String needle = term == null ? null : term.toLowerCase(Locale.ROOT);

    List<Office> filtered =
        officeRepository.findAllByDeletedAtIsNull().stream()
            .filter(office -> statusFilter == null || office.getStatus() == statusFilter)
            .filter(office -> cityFilter == null || cityFilter.equalsIgnoreCase(office.getCity()))
            .filter(office -> needle == null || matches(office, needle))
            .toList();

    return pageOf(sortOffices(filtered, pageable.getSort()), pageable, OfficeDtos.OfficeSummary::from);
  }

  /** One office's full record, with the number of agents who sit in it. */
  @Transactional(readOnly = true)
  public OfficeDtos.OfficeDetail detail(UUID officeId) {
    return detailOf(require(officeId));
  }

  /* ====================================================================== */
  /* Writes                                                                 */
  /* ====================================================================== */

  /** Add a branch. A second head office is refused; see the class note. */
  @Transactional
  public OfficeDtos.OfficeDetail create(OfficeDtos.CreateOfficeRequest request) {
    User actor = accessScope.requireUser();

    String code = request.officeCode().trim();
    if (officeRepository.existsByOfficeCodeIgnoreCaseAndDeletedAtIsNull(code)) {
      throw ApiException.duplicate(
          "DUPLICATE_OFFICE_CODE", ErrorMessages.friendly("DUPLICATE_OFFICE_CODE"));
    }

    boolean headOffice = Boolean.TRUE.equals(request.headOffice());
    if (headOffice) {
      assertNoOtherHeadOffice(null);
    }

    Office office = new Office();
    office.setOfficeCode(code);
    office.setName(request.name().trim());
    office.setAddressLine1(trim(request.addressLine1()));
    office.setAddressLine2(trim(request.addressLine2()));
    office.setLocality(trim(request.locality()));
    office.setCity(request.city().trim());
    office.setState(trim(request.state()));
    office.setPostalCode(trim(request.postalCode()));
    office.setCountry(
        request.country() == null || request.country().isBlank() ? "India" : request.country().trim());
    office.setPhone(trim(request.phone()));
    office.setEmail(lower(request.email()));
    office.setHeadOffice(headOffice);
    office.setStatus(parseStatusOrDefault(request.status(), OfficeStatus.ACTIVE));
    office.setCreatedBy(actor.getId());
    office.setUpdatedBy(actor.getId());

    Office saved = officeRepository.save(office);
    recordAudit(actor, AuditActions.CREATE, "Added office " + saved.getOfficeCode(), saved);
    return detailOf(saved);
  }

  /** Change a branch. A second head office is refused; see the class note. */
  @Transactional
  public OfficeDtos.OfficeDetail update(UUID officeId, OfficeDtos.UpdateOfficeRequest request) {
    User actor = accessScope.requireUser();
    Office office = require(officeId);

    if (request.name() != null && !request.name().isBlank()) office.setName(request.name().trim());
    if (request.addressLine1() != null) office.setAddressLine1(trim(request.addressLine1()));
    if (request.addressLine2() != null) office.setAddressLine2(trim(request.addressLine2()));
    if (request.locality() != null) office.setLocality(trim(request.locality()));
    if (request.city() != null) office.setCity(request.city().trim());
    if (request.state() != null) office.setState(trim(request.state()));
    if (request.postalCode() != null) office.setPostalCode(trim(request.postalCode()));
    if (request.country() != null) office.setCountry(request.country());
    if (request.phone() != null) office.setPhone(trim(request.phone()));
    if (request.email() != null) office.setEmail(lower(request.email()));
    if (request.headOffice() != null) {
      if (Boolean.TRUE.equals(request.headOffice())) {
        assertNoOtherHeadOffice(office.getId());
      }
      office.setHeadOffice(request.headOffice());
    }
    if (request.status() != null) office.setStatus(parseStatusOrDefault(request.status(), null));
    office.setUpdatedBy(actor.getId());

    Office saved = officeRepository.save(office);
    recordAudit(actor, AuditActions.UPDATE, "Changed office " + saved.getOfficeCode(), saved);
    return detailOf(saved);
  }

  /** Archive a branch - a soft delete, once. */
  @Transactional
  public OfficeDtos.OfficeDetail archive(UUID officeId) {
    User actor = accessScope.requireUser();
    Office office =
        officeRepository
            .findById(officeId)
            .orElseThrow(() -> ApiException.notFound(ErrorMessages.friendly("OFFICE_NOT_FOUND")));

    if (office.isDeleted()) {
      throw ApiException.rule("OFFICE_ALREADY_ARCHIVED", "This office has already been archived");
    }

    office.setDeletedAt(LocalDateTime.now());
    office.setUpdatedBy(actor.getId());
    Office saved = officeRepository.save(office);
    recordAudit(actor, AuditActions.DELETE, "Archived office " + saved.getOfficeCode(), saved);
    return detailOf(saved);
  }

  /* ====================================================================== */
  /* Internals                                                              */
  /* ====================================================================== */

  private OfficeDtos.OfficeDetail detailOf(Office office) {
    long agentCount =
        agentProfileRepository.findAllByOfficeIdAndDeletedAtIsNull(office.getId()).size();
    return new OfficeDtos.OfficeDetail(
        office.getId(),
        office.getOfficeCode(),
        office.getName(),
        office.getAddressLine1(),
        office.getAddressLine2(),
        office.getLocality(),
        office.getCity(),
        office.getState(),
        office.getPostalCode(),
        office.getCountry(),
        office.getPhone(),
        office.getEmail(),
        office.isHeadOffice(),
        name(office.getStatus()),
        agentCount,
        office.getCreatedAt(),
        office.getUpdatedAt());
  }

  /**
   * Refuse a head office when another office already holds the flag.
   *
   * <p>The current head office is excluded by id so promoting an office that is already the head
   * office is not a clash with itself. See the class note on why the flag is refused rather than
   * moved.</p>
   */
  private void assertNoOtherHeadOffice(UUID currentOfficeId) {
    officeRepository
        .findByIsHeadOfficeTrueAndDeletedAtIsNull()
        .filter(existing -> currentOfficeId == null || !currentOfficeId.equals(existing.getId()))
        .ifPresent(
            existing -> {
              throw ApiException.rule(
                  "OFFICE_HEAD_OFFICE_EXISTS",
                  "The registered head office is already "
                      + existing.getName()
                      + " ("
                      + existing.getOfficeCode()
                      + "). Demote it before designating another.");
            });
  }

  private static boolean matches(Office office, String needle) {
    return contains(office.getName(), needle)
        || contains(office.getOfficeCode(), needle)
        || contains(office.getCity(), needle)
        || contains(office.getEmail(), needle);
  }

  private static boolean contains(String value, String needle) {
    return value != null && value.toLowerCase(Locale.ROOT).contains(needle);
  }

  private void recordAudit(User actor, String action, String description, Office office) {
    audit.record(
        actor.getId(),
        actor.getFullName(),
        AuditActions.AGENT,
        action,
        description,
        "OFFICE",
        office.getId(),
        office.getOfficeCode());
  }

  private static OfficeStatus parseStatus(String value) {
    if (value == null || value.isBlank()) return null;
    return parseStatusOrDefault(value, null);
  }

  private static OfficeStatus parseStatusOrDefault(String value, OfficeStatus fallback) {
    if (value == null || value.isBlank()) {
      if (fallback == null) {
        throw ApiException.badRequest("An office status is required");
      }
      return fallback;
    }
    try {
      return OfficeStatus.valueOf(value.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException ex) {
      throw ApiException.badRequest("'" + value + "' is not an office status");
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

  private static List<Office> sortOffices(List<Office> items, Sort sort) {
    if (sort == null || sort.isUnsorted()) {
      return items;
    }
    Comparator<Office> comparator = null;
    for (Sort.Order order : sort) {
      Comparator<Office> next = officeComparator(order.getProperty());
      if (order.isDescending()) {
        next = next.reversed();
      }
      comparator = comparator == null ? next : comparator.thenComparing(next);
    }
    List<Office> sorted = new ArrayList<>(items);
    if (comparator != null) {
      sorted.sort(comparator);
    }
    return sorted;
  }

  private static Comparator<Office> officeComparator(String property) {
    Comparator<Office> byCreatedAt =
        Comparator.comparing(
            Office::getCreatedAt, Comparator.nullsLast(Comparator.<LocalDateTime>naturalOrder()));
    return switch (property == null ? "" : property) {
      case "name" ->
          Comparator.comparing(
              Office::getName, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER));
      case "officeCode" ->
          Comparator.comparing(
              Office::getOfficeCode, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER));
      case "status" ->
          Comparator.comparing(
              current -> current.getStatus() == null ? "" : current.getStatus().name());
      case "city" ->
          Comparator.comparing(
              Office::getCity, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER));
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
