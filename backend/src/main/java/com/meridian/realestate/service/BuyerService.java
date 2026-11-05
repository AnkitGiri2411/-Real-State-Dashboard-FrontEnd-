package com.meridian.realestate.service;

import com.meridian.realestate.domain.Buyer;
import com.meridian.realestate.domain.BuyerRequirement;
import com.meridian.realestate.domain.BuyerRequirementPurpose;
import com.meridian.realestate.domain.BuyerStatus;
import com.meridian.realestate.domain.BuyerType;
import com.meridian.realestate.domain.Offer;
import com.meridian.realestate.domain.PropertyType;
import com.meridian.realestate.domain.SalesOpportunity;
import com.meridian.realestate.domain.User;
import com.meridian.realestate.dto.BuyerDtos;
import com.meridian.realestate.dto.ErrorMessages;
import com.meridian.realestate.exception.ApiException;
import com.meridian.realestate.repository.BuyerRepository;
import com.meridian.realestate.repository.BuyerRequirementRepository;
import com.meridian.realestate.repository.OfferRepository;
import com.meridian.realestate.repository.PropertyTypeRepository;
import com.meridian.realestate.repository.SalesOpportunityRepository;
import com.meridian.realestate.repository.ViewingRepository;
import com.meridian.realestate.security.AccessScope;
import java.math.BigDecimal;
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
 * The buyer register and the briefs (requirements) that state what each buyer is looking for.
 *
 * <p><b>Why this class exists.</b> A buyer is a client with a relationship the firm keeps, and what
 * they are looking for changes over time - so the brief is a first-class record with its own active
 * flag rather than a set of columns on the buyer, and the history of briefs is what explains why a
 * property was or was not shown to them. The buyer's viewings and offers are projected here,
 * read-only, so the page can be drawn in one request; they belong to the viewing and sales modules
 * and are never written here.</p>
 *
 * <p><b>The rules it protects.</b></p>
 * <ul>
 *   <li><b>A brief's range runs forwards.</b> {@code minBudget <= maxBudget} and
 *       {@code minBedrooms <= maxBedrooms}, refused as a named 400 before the database's own check
 *       constraints would report them as opaque integrity errors.</li>
 *   <li><b>A brief is deactivated, not deleted, when it is superseded.</b> Setting
 *       {@code isActive = false} keeps it readable as history; the explicit delete is a soft delete
 *       so a brief can also be withdrawn outright without losing the record.</li>
 *   <li><b>The buyer code is unique.</b> There is no seeded business-number series for buyers, so
 *       the code is supplied by the caller on create and a duplicate is refused as
 *       {@code DUPLICATE_BUYER_CODE} (409).</li>
 *   <li><b>Archiving is soft, and once.</b> The offers, viewings and sales that reference the buyer
 *       stay coherent because the row survives.</li>
 * </ul>
 *
 * <p><b>A note on paging.</b> As everywhere in this module, the filters, the sort and the slice are
 * applied here because the combined filtered query does not exist and a literal {@code order by} in
 * a paged query is forbidden.</p>
 */
@Service
public class BuyerService {

  private final BuyerRepository buyerRepository;
  private final BuyerRequirementRepository requirementRepository;
  private final PropertyTypeRepository propertyTypeRepository;
  private final ViewingRepository viewingRepository;
  private final SalesOpportunityRepository salesRepository;
  private final OfferRepository offerRepository;
  private final AuditRecordingService audit;
  private final AccessScope accessScope;

  public BuyerService(
      BuyerRepository buyerRepository,
      BuyerRequirementRepository requirementRepository,
      PropertyTypeRepository propertyTypeRepository,
      ViewingRepository viewingRepository,
      SalesOpportunityRepository salesRepository,
      OfferRepository offerRepository,
      AuditRecordingService audit,
      AccessScope accessScope) {
    this.buyerRepository = buyerRepository;
    this.requirementRepository = requirementRepository;
    this.propertyTypeRepository = propertyTypeRepository;
    this.viewingRepository = viewingRepository;
    this.salesRepository = salesRepository;
    this.offerRepository = offerRepository;
    this.audit = audit;
    this.accessScope = accessScope;
  }

  /* ====================================================================== */
  /* Reads                                                                  */
  /* ====================================================================== */

  /** The live buyer behind an id, or a 404. Archived buyers are not addressable. */
  @Transactional(readOnly = true)
  public Buyer require(UUID buyerId) {
    return buyerRepository
        .findByIdAndDeletedAtIsNull(buyerId)
        .orElseThrow(() -> ApiException.notFound(ErrorMessages.friendly("BUYER_NOT_FOUND")));
  }

  /** The buyer register, filtered by status, type and city, with free-text search, paged. */
  @Transactional(readOnly = true)
  public Page<BuyerDtos.BuyerSummary> list(
      String status, String type, String city, String search, Pageable pageable) {

    BuyerStatus statusFilter = parseOptionalStatus(status);
    BuyerType typeFilter = parseOptionalType(type);
    String cityFilter = blankToNull(city);
    String term = blankToNull(search);

    List<Buyer> candidates =
        term == null
            ? buyerRepository.findAllByDeletedAtIsNull()
            : buyerRepository.search(term, Pageable.unpaged()).getContent();

    List<Buyer> filtered =
        candidates.stream()
            .filter(buyer -> statusFilter == null || buyer.getStatus() == statusFilter)
            .filter(buyer -> typeFilter == null || buyer.getBuyerType() == typeFilter)
            .filter(buyer -> cityFilter == null || cityFilter.equalsIgnoreCase(buyer.getCity()))
            .toList();

    return pageOf(sortBuyers(filtered, pageable.getSort()), pageable, BuyerDtos.BuyerSummary::from);
  }

  /** One buyer's full record, with their briefs, viewings and offers. */
  @Transactional(readOnly = true)
  public BuyerDtos.BuyerDetail detail(UUID buyerId) {
    return detailOf(require(buyerId));
  }

  /** A buyer's briefs, active first and then the superseded ones as history. */
  @Transactional(readOnly = true)
  public List<BuyerDtos.RequirementRecord> requirements(UUID buyerId) {
    require(buyerId);
    return requirementRepository.findAllForBuyer(buyerId).stream()
        .map(BuyerDtos.RequirementRecord::from)
        .toList();
  }

  /* ====================================================================== */
  /* Buyer writes                                                           */
  /* ====================================================================== */

  /** Add a buyer to the register. The code is supplied by the caller and must be free. */
  @Transactional
  public BuyerDtos.BuyerDetail create(BuyerDtos.CreateBuyerRequest request) {
    User actor = accessScope.requireUser();

    String code = request.buyerCode().trim();
    if (buyerRepository.existsByBuyerCodeIgnoreCaseAndDeletedAtIsNull(code)) {
      throw ApiException.duplicate(
          "DUPLICATE_BUYER_CODE", ErrorMessages.friendly("DUPLICATE_BUYER_CODE"));
    }

    Buyer buyer = new Buyer();
    buyer.setBuyerCode(code);
    buyer.setBuyerType(parseType(request.buyerType(), BuyerType.INDIVIDUAL));
    buyer.setDisplayName(request.displayName().trim());
    buyer.setPrimaryContactName(trim(request.primaryContactName()));
    buyer.setEmail(lower(request.email()));
    buyer.setPhone(trim(request.phone()));
    buyer.setAlternatePhone(trim(request.alternatePhone()));
    buyer.setIdReference(trim(request.idReference()));
    buyer.setTaxReference(trim(request.taxReference()));
    applyAddress(buyer, request);
    buyer.setStatus(parseStatusOrDefault(request.status(), BuyerStatus.ACTIVE));
    buyer.setNotes(request.notes());
    buyer.setCreatedBy(actor.getId());
    buyer.setUpdatedBy(actor.getId());

    Buyer saved = buyerRepository.save(buyer);
    recordAudit(actor, AuditActions.CREATE, "Added buyer " + saved.getBuyerCode(), saved);
    return detailOf(saved);
  }

  /** Change a buyer. Only the fields supplied are applied. */
  @Transactional
  public BuyerDtos.BuyerDetail update(UUID buyerId, BuyerDtos.UpdateBuyerRequest request) {
    User actor = accessScope.requireUser();
    Buyer buyer = require(buyerId);

    if (request.buyerType() != null) buyer.setBuyerType(parseType(request.buyerType(), null));
    if (request.displayName() != null && !request.displayName().isBlank()) {
      buyer.setDisplayName(request.displayName().trim());
    }
    if (request.primaryContactName() != null) {
      buyer.setPrimaryContactName(trim(request.primaryContactName()));
    }
    if (request.email() != null) buyer.setEmail(lower(request.email()));
    if (request.phone() != null) buyer.setPhone(trim(request.phone()));
    if (request.alternatePhone() != null) buyer.setAlternatePhone(trim(request.alternatePhone()));
    if (request.idReference() != null) buyer.setIdReference(trim(request.idReference()));
    if (request.taxReference() != null) buyer.setTaxReference(trim(request.taxReference()));
    applyAddressUpdate(buyer, request);
    if (request.status() != null) buyer.setStatus(parseStatusOrDefault(request.status(), null));
    if (request.notes() != null) buyer.setNotes(request.notes());
    buyer.setUpdatedBy(actor.getId());

    Buyer saved = buyerRepository.save(buyer);
    recordAudit(actor, AuditActions.UPDATE, "Changed buyer " + saved.getBuyerCode(), saved);
    return detailOf(saved);
  }

  /** Archive a buyer - a soft delete, once. */
  @Transactional
  public BuyerDtos.BuyerDetail archive(UUID buyerId) {
    User actor = accessScope.requireUser();
    Buyer buyer =
        buyerRepository
            .findById(buyerId)
            .orElseThrow(() -> ApiException.notFound(ErrorMessages.friendly("BUYER_NOT_FOUND")));

    if (buyer.isDeleted()) {
      throw ApiException.rule("BUYER_ALREADY_ARCHIVED", "This buyer has already been archived");
    }

    buyer.setDeletedAt(LocalDateTime.now());
    buyer.setUpdatedBy(actor.getId());
    Buyer saved = buyerRepository.save(buyer);
    recordAudit(actor, AuditActions.DELETE, "Archived buyer " + saved.getBuyerCode(), saved);
    return detailOf(saved);
  }

  /* ====================================================================== */
  /* Requirement writes                                                     */
  /* ====================================================================== */

  /** State a brief for a buyer, refusing a range that runs backwards. */
  @Transactional
  public BuyerDtos.RequirementRecord createRequirement(
      UUID buyerId, BuyerDtos.CreateRequirementRequest request) {
    User actor = accessScope.requireUser();
    Buyer buyer = require(buyerId);
    assertBudgetRange(request.minBudget(), request.maxBudget());
    assertBedroomRange(request.minBedrooms(), request.maxBedrooms());

    BuyerRequirement requirement = new BuyerRequirement();
    requirement.setBuyer(buyer);
    requirement.setPropertyType(resolvePropertyType(request.propertyTypeId()));
    applyRequirementFields(
        requirement,
        request.preferredCity(),
        request.preferredLocality(),
        request.minBudget(),
        request.maxBudget(),
        request.minBedrooms(),
        request.maxBedrooms(),
        request.minAreaSqft(),
        request.purpose(),
        request.notes());
    requirement.setActive(request.active() == null || request.active());
    requirement.setCreatedBy(actor.getId());
    requirement.setUpdatedBy(actor.getId());

    BuyerRequirement saved = requirementRepository.save(requirement);
    recordAudit(actor, AuditActions.CREATE, "Added a requirement for " + buyer.getBuyerCode(), buyer);
    return BuyerDtos.RequirementRecord.from(saved);
  }

  /** Change a brief. Only the fields supplied are applied; the range is re-checked. */
  @Transactional
  public BuyerDtos.RequirementRecord updateRequirement(
      UUID buyerId, UUID requirementId, BuyerDtos.UpdateRequirementRequest request) {
    User actor = accessScope.requireUser();
    Buyer buyer = require(buyerId);
    BuyerRequirement requirement = requireRequirement(buyerId, requirementId);

    BigDecimal minBudget =
        request.minBudget() == null ? requirement.getMinBudget() : request.minBudget();
    BigDecimal maxBudget =
        request.maxBudget() == null ? requirement.getMaxBudget() : request.maxBudget();
    Integer minBedrooms =
        request.minBedrooms() == null ? requirement.getMinBedrooms() : request.minBedrooms();
    Integer maxBedrooms =
        request.maxBedrooms() == null ? requirement.getMaxBedrooms() : request.maxBedrooms();
    assertBudgetRange(minBudget, maxBudget);
    assertBedroomRange(minBedrooms, maxBedrooms);

    if (request.propertyTypeId() != null) {
      requirement.setPropertyType(resolvePropertyType(request.propertyTypeId()));
    }
    if (request.preferredCity() != null) requirement.setPreferredCity(trim(request.preferredCity()));
    if (request.preferredLocality() != null) {
      requirement.setPreferredLocality(trim(request.preferredLocality()));
    }
    if (request.minBudget() != null) requirement.setMinBudget(request.minBudget());
    if (request.maxBudget() != null) requirement.setMaxBudget(request.maxBudget());
    if (request.minBedrooms() != null) requirement.setMinBedrooms(request.minBedrooms());
    if (request.maxBedrooms() != null) requirement.setMaxBedrooms(request.maxBedrooms());
    if (request.minAreaSqft() != null) requirement.setMinAreaSqft(request.minAreaSqft());
    if (request.purpose() != null) {
      requirement.setPurpose(parsePurpose(request.purpose(), requirement.getPurpose()));
    }
    if (request.active() != null) requirement.setActive(request.active());
    if (request.notes() != null) requirement.setNotes(request.notes());
    requirement.setUpdatedBy(actor.getId());

    BuyerRequirement saved = requirementRepository.save(requirement);
    recordAudit(actor, AuditActions.UPDATE, "Changed a requirement for " + buyer.getBuyerCode(), buyer);
    return BuyerDtos.RequirementRecord.from(saved);
  }

  /**
   * Withdraw a brief - a soft delete.
   *
   * <p>A superseded brief is normally deactivated through an update so it stays readable; this is
   * for a brief that was never meant to be there, and it removes the row from the active book
   * without losing the record.</p>
   */
  @Transactional
  public BuyerDtos.RequirementRecord archiveRequirement(UUID buyerId, UUID requirementId) {
    User actor = accessScope.requireUser();
    Buyer buyer = require(buyerId);
    BuyerRequirement requirement = requireRequirement(buyerId, requirementId);

    if (requirement.isDeleted()) {
      throw ApiException.rule(
          "BUYER_REQUIREMENT_ALREADY_ARCHIVED", "That requirement has already been withdrawn");
    }

    requirement.setDeletedAt(LocalDateTime.now());
    requirement.setActive(false);
    requirement.setUpdatedBy(actor.getId());
    BuyerRequirement saved = requirementRepository.save(requirement);
    recordAudit(actor, AuditActions.DELETE, "Withdrew a requirement for " + buyer.getBuyerCode(), buyer);
    return BuyerDtos.RequirementRecord.from(saved);
  }

  /* ====================================================================== */
  /* Internals                                                              */
  /* ====================================================================== */

  private BuyerDtos.BuyerDetail detailOf(Buyer buyer) {
    UUID buyerId = buyer.getId();

    List<BuyerRequirement> requirements =
        requirementRepository.findAllByBuyerIdAndDeletedAtIsNull(buyerId);
    List<BuyerDtos.RequirementRecord> requirementRows =
        requirements.stream().map(BuyerDtos.RequirementRecord::from).toList();
    long active = requirements.stream().filter(BuyerRequirement::isActive).count();

    List<BuyerDtos.ViewingRecord> viewings =
        viewingRepository.findAllForDiary().stream()
            .filter(
                viewing ->
                    viewing.getBuyer() != null && buyerId.equals(viewing.getBuyer().getId()))
            .map(BuyerDtos.ViewingRecord::from)
            .toList();

    List<BuyerDtos.OfferRecord> offers = new ArrayList<>();
    for (SalesOpportunity sale : salesRepository.findAllByBuyerIdAndDeletedAtIsNull(buyerId)) {
      for (Offer offer : offerRepository.findAllBySaleIdAndDeletedAtIsNull(sale.getId())) {
        offers.add(BuyerDtos.OfferRecord.from(offer, sale.getSaleCode()));
      }
    }

    return new BuyerDtos.BuyerDetail(
        buyer.getId(),
        buyer.getBuyerCode(),
        name(buyer.getBuyerType()),
        buyer.getDisplayName(),
        buyer.getPrimaryContactName(),
        buyer.getEmail(),
        buyer.getPhone(),
        buyer.getAlternatePhone(),
        buyer.getIdReference(),
        buyer.getTaxReference(),
        buyer.getAddressLine1(),
        buyer.getAddressLine2(),
        buyer.getLocality(),
        buyer.getCity(),
        buyer.getState(),
        buyer.getPostalCode(),
        buyer.getCountry(),
        name(buyer.getStatus()),
        buyer.getNotes(),
        requirementRows.size(),
        active,
        requirementRows,
        viewings,
        offers,
        buyer.getCreatedAt(),
        buyer.getUpdatedAt());
  }

  private BuyerRequirement requireRequirement(UUID buyerId, UUID requirementId) {
    BuyerRequirement requirement =
        requirementRepository
            .findByIdAndDeletedAtIsNull(requirementId)
            .orElseThrow(() -> ApiException.notFound("We could not find that requirement"));
    if (requirement.getBuyer() == null || !buyerId.equals(requirement.getBuyer().getId())) {
      throw ApiException.notFound("We could not find that requirement on this buyer");
    }
    return requirement;
  }

  private PropertyType resolvePropertyType(UUID propertyTypeId) {
    if (propertyTypeId == null) {
      return null;
    }
    return propertyTypeRepository
        .findById(propertyTypeId)
        .orElseThrow(() -> ApiException.notFound("That property type does not exist"));
  }

  private static void applyRequirementFields(
      BuyerRequirement requirement,
      String preferredCity,
      String preferredLocality,
      BigDecimal minBudget,
      BigDecimal maxBudget,
      Integer minBedrooms,
      Integer maxBedrooms,
      BigDecimal minAreaSqft,
      String purpose,
      String notes) {
    requirement.setPreferredCity(trim(preferredCity));
    requirement.setPreferredLocality(trim(preferredLocality));
    requirement.setMinBudget(minBudget);
    requirement.setMaxBudget(maxBudget);
    requirement.setMinBedrooms(minBedrooms);
    requirement.setMaxBedrooms(maxBedrooms);
    requirement.setMinAreaSqft(minAreaSqft);
    requirement.setPurpose(parsePurpose(purpose, BuyerRequirementPurpose.OWN_OCCUPANCY));
    requirement.setNotes(notes);
  }

  private static void assertBudgetRange(BigDecimal min, BigDecimal max) {
    if (min != null && max != null && max.compareTo(min) < 0) {
      throw ApiException.badRequest("The maximum budget cannot be less than the minimum budget");
    }
  }

  private static void assertBedroomRange(Integer min, Integer max) {
    if (min != null && max != null && max < min) {
      throw ApiException.badRequest(
          "The maximum number of bedrooms cannot be fewer than the minimum");
    }
  }

  private static void applyAddress(Buyer buyer, BuyerDtos.CreateBuyerRequest request) {
    buyer.setAddressLine1(request.addressLine1());
    buyer.setAddressLine2(request.addressLine2());
    buyer.setLocality(request.locality());
    buyer.setCity(request.city());
    buyer.setState(request.state());
    buyer.setPostalCode(request.postalCode());
    buyer.setCountry(
        request.country() == null || request.country().isBlank()
            ? "India"
            : request.country().trim());
  }

  private static void applyAddressUpdate(Buyer buyer, BuyerDtos.UpdateBuyerRequest request) {
    if (request.addressLine1() != null) buyer.setAddressLine1(request.addressLine1());
    if (request.addressLine2() != null) buyer.setAddressLine2(request.addressLine2());
    if (request.locality() != null) buyer.setLocality(request.locality());
    if (request.city() != null) buyer.setCity(request.city());
    if (request.state() != null) buyer.setState(request.state());
    if (request.postalCode() != null) buyer.setPostalCode(request.postalCode());
    if (request.country() != null) buyer.setCountry(request.country());
  }

  private void recordAudit(User actor, String action, String description, Buyer buyer) {
    audit.record(
        actor.getId(),
        actor.getFullName(),
        AuditActions.BUYER,
        action,
        description,
        "BUYER",
        buyer.getId(),
        buyer.getBuyerCode());
  }

  /**
   * A status supplied as a list filter, or null meaning "every status".
   *
   * <p>Deliberately separate from {@link #parseStatus}. The buyer register is read unfiltered by
   * default, so an absent status means "no filter" rather than a client mistake; reusing the
   * required-value parser here made {@code GET /api/v1/buyers} answer 400 to its own default
   * request. A value that is supplied still goes through {@link #parseStatus}.</p>
   */
  private static BuyerStatus parseOptionalStatus(String value) {
    return value == null || value.isBlank() ? null : parseStatus(value);
  }

  private static BuyerStatus parseStatus(String value) {
    if (value == null || value.isBlank()) return null;
    return parseStatusOrDefault(value, null);
  }

  private static BuyerStatus parseStatusOrDefault(String value, BuyerStatus fallback) {
    if (value == null || value.isBlank()) {
      if (fallback == null) {
        throw ApiException.badRequest("A buyer status is required");
      }
      return fallback;
    }
    try {
      return BuyerStatus.valueOf(value.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException ex) {
      throw ApiException.badRequest("'" + value + "' is not a buyer status");
    }
  }

  /**
   * A type supplied as a list filter, or null meaning "every type".
   *
   * <p>Separate from {@link #parseType}: the register is read unfiltered by default, so an absent
   * type means "no filter". Passing a null fallback from the list method made
   * {@code GET /api/v1/buyers} answer 400 to its own default request.</p>
   */
  private static BuyerType parseOptionalType(String value) {
    return value == null || value.isBlank() ? null : parseType(value, null);
  }

  private static BuyerType parseType(String value, BuyerType fallback) {
    if (value == null || value.isBlank()) {
      if (fallback == null) {
        throw ApiException.badRequest("A buyer type is required");
      }
      return fallback;
    }
    try {
      return BuyerType.valueOf(value.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException ex) {
      throw ApiException.badRequest("'" + value + "' is not a buyer type");
    }
  }

  private static BuyerRequirementPurpose parsePurpose(
      String value, BuyerRequirementPurpose fallback) {
    if (value == null || value.isBlank()) {
      return fallback == null ? BuyerRequirementPurpose.OWN_OCCUPANCY : fallback;
    }
    try {
      return BuyerRequirementPurpose.valueOf(value.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException ex) {
      throw ApiException.badRequest("'" + value + "' is not a requirement purpose");
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

  private static List<Buyer> sortBuyers(List<Buyer> items, Sort sort) {
    if (sort == null || sort.isUnsorted()) {
      return items;
    }
    Comparator<Buyer> comparator = null;
    for (Sort.Order order : sort) {
      Comparator<Buyer> next = buyerComparator(order.getProperty());
      if (order.isDescending()) {
        next = next.reversed();
      }
      comparator = comparator == null ? next : comparator.thenComparing(next);
    }
    List<Buyer> sorted = new ArrayList<>(items);
    if (comparator != null) {
      sorted.sort(comparator);
    }
    return sorted;
  }

  private static Comparator<Buyer> buyerComparator(String property) {
    Comparator<Buyer> byCreatedAt =
        Comparator.comparing(
            Buyer::getCreatedAt, Comparator.nullsLast(Comparator.<LocalDateTime>naturalOrder()));
    return switch (property == null ? "" : property) {
      case "displayName" ->
          Comparator.comparing(
              Buyer::getDisplayName, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER));
      case "buyerCode" ->
          Comparator.comparing(
              Buyer::getBuyerCode, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER));
      case "status" ->
          Comparator.comparing(
              current -> current.getStatus() == null ? "" : current.getStatus().name());
      case "city" ->
          Comparator.comparing(
              Buyer::getCity, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER));
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
