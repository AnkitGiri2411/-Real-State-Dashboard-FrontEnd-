package com.meridian.realestate.service;

import com.meridian.realestate.domain.Owner;
import com.meridian.realestate.domain.OwnerStatus;
import com.meridian.realestate.domain.OwnerType;
import com.meridian.realestate.domain.OwnershipRelationship;
import com.meridian.realestate.domain.Property;
import com.meridian.realestate.domain.PropertyOwnership;
import com.meridian.realestate.domain.User;
import com.meridian.realestate.dto.ErrorMessages;
import com.meridian.realestate.dto.OwnerDtos;
import com.meridian.realestate.exception.ApiException;
import com.meridian.realestate.repository.OwnerRepository;
import com.meridian.realestate.repository.PropertyOwnershipRepository;
import com.meridian.realestate.repository.PropertyRepository;
import com.meridian.realestate.security.AccessScope;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
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
 * The owner register and the holdings beneath it - and the one rule the schema cannot state.
 *
 * <p><b>Why this class exists.</b> An owner is the party whose asset is being managed, and what they
 * hold is a relationship with a share and a period rather than a column on a property: a portfolio
 * is routinely held by several parties at once - a family, a company and a development partner on one
 * title - and a holding begins and ends. Keeping the owner's own operations and the arithmetic of
 * their holdings in one place is what stops the owner statement and the property page disagreeing
 * about who owns what.</p>
 *
 * <p><b>The rules it protects.</b></p>
 * <ul>
 *   <li><b>The shares in force for one property total exactly 100%.</b> The schema constrains a
 *       single row to {@code (0, 100]} and to a period that runs forwards, but it cannot say that the
 *       holdings in force for one property sum to the whole - that spans rows. So every write here
 *       reads the property's holdings, applies the proposed change and refuses to leave the open
 *       total above 100 ({@code OWNERSHIP_EXCEEDS_ONE_HUNDRED}, 409), inside the one transaction that
 *       writes the change. {@link #validateTotal(UUID)} states the same check explicitly and reports
 *       the shortfall as {@code OWNERSHIP_NOT_ONE_HUNDRED} (409) when the total is not the whole.</li>
 *   <li><b>One owner, one holding, per period.</b> The same owner recorded twice on a property for
 *       an overlapping period would double-count them in every owner report, so a second holding
 *       whose period overlaps an existing one is refused with {@code OWNERSHIP_DUPLICATE_OWNER}
 *       (409), rather than being stored and silently mis-attributed.</li>
 *   <li><b>A holding is dated, never deleted.</b> Closing a share stamps {@code effectiveTo}, because
 *       a past ownership is reportable history rather than a mistake - "who owned this when the
 *       invoice was raised" has to stay answerable.</li>
 *   <li><b>Archiving is soft, and once.</b> An owner is never hard-deleted: the ownership rows,
 *       statements and audit trail that reference them must stay coherent, so archiving stamps
 *       {@code deletedAt} and a second archive of an already-archived owner is refused.</li>
 * </ul>
 *
 * <p><b>A note on paging.</b> {@code OwnerRepository} offers search and a plain paged read but not
 * one combined query for the register's filters (status, type, city, search), so - as the register
 * does everywhere in this module - the filters, the sort and the slice are applied here rather than
 * putting a literal {@code order by} in a paged query, which the product forbids.</p>
 */
@Service
public class OwnerService {

  /** The whole: the total the shares in force on a property must come to. */
  private static final BigDecimal ONE_HUNDRED = new BigDecimal("100");

  /** The decimal places a share carries - the scale {@code property_ownerships.ownership_percentage} stores. */
  private static final int PERCENT_SCALE = 2;

  private final OwnerRepository ownerRepository;
  private final PropertyOwnershipRepository ownershipRepository;
  private final PropertyRepository propertyRepository;
  private final BusinessNumberService businessNumbers;
  private final AuditRecordingService audit;
  private final AccessScope accessScope;

  public OwnerService(
      OwnerRepository ownerRepository,
      PropertyOwnershipRepository ownershipRepository,
      PropertyRepository propertyRepository,
      BusinessNumberService businessNumbers,
      AuditRecordingService audit,
      AccessScope accessScope) {
    this.ownerRepository = ownerRepository;
    this.ownershipRepository = ownershipRepository;
    this.propertyRepository = propertyRepository;
    this.businessNumbers = businessNumbers;
    this.audit = audit;
    this.accessScope = accessScope;
  }

  /* ====================================================================== */
  /* Reads                                                                  */
  /* ====================================================================== */

  /** The live owner behind an id, or a 404. Archived owners are not addressable. */
  @Transactional(readOnly = true)
  public Owner require(UUID ownerId) {
    return ownerRepository
        .findByIdAndDeletedAtIsNull(ownerId)
        .orElseThrow(() -> ApiException.notFound(ErrorMessages.friendly("OWNER_NOT_FOUND")));
  }

  /** The owner register, filtered by status, type and city, with free-text search, paged. */
  @Transactional(readOnly = true)
  public Page<OwnerDtos.OwnerSummary> list(
      String status, String type, String city, String search, Pageable pageable) {

    OwnerStatus statusFilter = parseOptionalStatus(status);
    OwnerType typeFilter = parseOptionalType(type);
    String cityFilter = blankToNull(city);
    String term = blankToNull(search);

    List<Owner> candidates =
        term == null
            ? ownerRepository.findAllByDeletedAtIsNull()
            : ownerRepository.search(term, Pageable.unpaged()).getContent();

    List<Owner> filtered =
        candidates.stream()
            .filter(owner -> statusFilter == null || owner.getStatus() == statusFilter)
            .filter(owner -> typeFilter == null || owner.getOwnerType() == typeFilter)
            .filter(
                owner -> cityFilter == null || cityFilter.equalsIgnoreCase(owner.getCity()))
            .toList();

    return pageOf(sortOwners(filtered, pageable.getSort()), pageable, OwnerDtos.OwnerSummary::from);
  }

  /** One owner's full record, with every holding they carry across the portfolio. */
  @Transactional(readOnly = true)
  public OwnerDtos.OwnerDetail detail(UUID ownerId) {
    return detailOf(require(ownerId));
  }

  /** The shares in force on a property today, or on a given date. */
  @Transactional(readOnly = true)
  public List<OwnerDtos.OwnershipShare> ownerships(UUID propertyId, LocalDate asOf) {
    requireProperty(propertyId);
    List<PropertyOwnership> shares =
        asOf == null
            ? ownershipRepository.findOpenForProperty(propertyId)
            : ownershipRepository.findEffectiveForPropertyOn(propertyId, asOf);
    return shares.stream().map(OwnerDtos.OwnershipShare::from).toList();
  }

  /**
   * Check that a property's shares in force total exactly 100%.
   *
   * <p>When they do, the answer is the report itself. When they do not, the operation is refused
   * with a 409 named {@code OWNERSHIP_NOT_ONE_HUNDRED} whose {@code details} carry the current total
   * and the shortfall (or the excess, when the shares overshoot), so the caller is told how far off
   * the property is rather than merely that it is wrong.</p>
   */
  @Transactional(readOnly = true)
  public OwnerDtos.OwnershipValidation validateTotal(UUID propertyId) {
    requireProperty(propertyId);
    List<PropertyOwnership> all = ownershipRepository.findAllForProperty(propertyId);
    List<PropertyOwnership> open = all.stream().filter(PropertyOwnership::isOpen).toList();
    BigDecimal total = sumPercentages(open);
    BigDecimal shortfall = total.compareTo(ONE_HUNDRED) < 0 ? ONE_HUNDRED.subtract(total) : zero();
    BigDecimal surplus = total.compareTo(ONE_HUNDRED) > 0 ? total.subtract(ONE_HUNDRED) : zero();
    boolean valid = total.compareTo(ONE_HUNDRED) == 0;

    if (!valid) {
      List<String> details = new ArrayList<>();
      details.add("total: " + total.toPlainString());
      details.add(
          (total.compareTo(ONE_HUNDRED) < 0 ? "shortfall: " : "excess: ")
              + (total.compareTo(ONE_HUNDRED) < 0 ? shortfall : surplus).toPlainString());
      throw ApiException.rule(
          "OWNERSHIP_NOT_ONE_HUNDRED",
          ErrorMessages.friendly("OWNERSHIP_NOT_ONE_HUNDRED"),
          details);
    }

    return new OwnerDtos.OwnershipValidation(
        propertyId,
        total,
        shortfall,
        surplus,
        true,
        open.size(),
        open.stream().map(OwnerDtos.OwnershipShare::from).toList());
  }

  /* ====================================================================== */
  /* Owner writes                                                           */
  /* ====================================================================== */

  /** Add an owner to the register, minting their human-readable code. */
  @Transactional
  public OwnerDtos.OwnerDetail create(OwnerDtos.CreateOwnerRequest request) {
    User actor = accessScope.requireUser();

    Owner owner = new Owner();
    owner.setOwnerCode(businessNumbers.next(BusinessNumberService.OWNER));
    owner.setOwnerType(parseType(request.ownerType(), OwnerType.INDIVIDUAL));
    owner.setDisplayName(request.displayName().trim());
    owner.setPrimaryContactName(trim(request.primaryContactName()));
    owner.setEmail(lower(request.email()));
    owner.setPhone(trim(request.phone()));
    owner.setAlternatePhone(trim(request.alternatePhone()));
    owner.setIdReference(trim(request.idReference()));
    owner.setTaxReference(trim(request.taxReference()));
    applyAddress(owner, request);
    owner.setStatus(parseStatusOrDefault(request.status(), OwnerStatus.ACTIVE));
    owner.setNotes(request.notes());
    owner.setCreatedBy(actor.getId());
    owner.setUpdatedBy(actor.getId());

    Owner saved = ownerRepository.save(owner);
    recordAudit(actor, AuditActions.CREATE, "Added owner " + saved.getOwnerCode(), saved);
    return detailOf(saved);
  }

  /** Change an owner. Only the fields supplied are applied. */
  @Transactional
  public OwnerDtos.OwnerDetail update(UUID ownerId, OwnerDtos.UpdateOwnerRequest request) {
    User actor = accessScope.requireUser();
    Owner owner = require(ownerId);

    if (request.ownerType() != null) owner.setOwnerType(parseType(request.ownerType(), null));
    if (request.displayName() != null && !request.displayName().isBlank()) {
      owner.setDisplayName(request.displayName().trim());
    }
    if (request.primaryContactName() != null) {
      owner.setPrimaryContactName(trim(request.primaryContactName()));
    }
    if (request.email() != null) owner.setEmail(lower(request.email()));
    if (request.phone() != null) owner.setPhone(trim(request.phone()));
    if (request.alternatePhone() != null) owner.setAlternatePhone(trim(request.alternatePhone()));
    if (request.idReference() != null) owner.setIdReference(trim(request.idReference()));
    if (request.taxReference() != null) owner.setTaxReference(trim(request.taxReference()));
    applyAddressUpdate(owner, request);
    if (request.status() != null) owner.setStatus(parseStatusOrDefault(request.status(), null));
    if (request.notes() != null) owner.setNotes(request.notes());
    owner.setUpdatedBy(actor.getId());

    Owner saved = ownerRepository.save(owner);
    recordAudit(actor, AuditActions.UPDATE, "Changed owner " + saved.getOwnerCode(), saved);
    return detailOf(saved);
  }

  /** Archive an owner - a soft delete, once. */
  @Transactional
  public OwnerDtos.OwnerDetail archive(UUID ownerId) {
    User actor = accessScope.requireUser();
    Owner owner =
        ownerRepository
            .findById(ownerId)
            .orElseThrow(() -> ApiException.notFound(ErrorMessages.friendly("OWNER_NOT_FOUND")));

    if (owner.isDeleted()) {
      throw ApiException.rule(
          "OWNER_ALREADY_ARCHIVED", "This owner has already been archived");
    }

    owner.setDeletedAt(LocalDateTime.now());
    owner.setUpdatedBy(actor.getId());
    Owner saved = ownerRepository.save(owner);
    recordAudit(actor, AuditActions.DELETE, "Archived owner " + saved.getOwnerCode(), saved);
    return detailOf(saved);
  }

  /* ====================================================================== */
  /* Ownership writes                                                       */
  /* ====================================================================== */

  /** Record an owner's holding in a property, refusing a change that breaks the 100% rule. */
  @Transactional
  public OwnerDtos.OwnershipShare addOwnership(
      UUID propertyId, OwnerDtos.CreateOwnershipRequest request) {
    User actor = accessScope.requireUser();
    Property property = requireProperty(propertyId);
    Owner owner = require(request.ownerId());

    LocalDate from = request.effectiveFrom();
    LocalDate to = request.effectiveTo();
    assertPeriod(from, to);
    BigDecimal percentage = percent(request.ownershipPercentage());

    List<PropertyOwnership> existing = ownershipRepository.findAllForProperty(propertyId);
    assertNoDuplicateOwner(existing, owner.getId(), from, to, null);
    assertOpenTotalWithinHundred(existing, null, to == null ? percentage : null);

    PropertyOwnership share = new PropertyOwnership();
    share.setProperty(property);
    share.setOwner(owner);
    share.setOwnershipPercentage(percentage);
    share.setRelationship(parseRelationship(request.relationship(), OwnershipRelationship.CO_OWNER));
    share.setEffectiveFrom(from);
    share.setEffectiveTo(to);
    share.setNotes(request.notes());
    share.setCreatedBy(actor.getId());
    share.setUpdatedBy(actor.getId());

    PropertyOwnership saved = ownershipRepository.save(share);
    recordOwnershipAudit(
        actor, AuditActions.CREATE, "Recorded " + owner.getOwnerCode() + "'s holding in "
            + property.getPropertyCode(), saved, property, owner);
    return OwnerDtos.OwnershipShare.from(saved);
  }

  /** Change a holding. Only the fields supplied are applied; the same 100% rule holds. */
  @Transactional
  public OwnerDtos.OwnershipShare updateOwnership(
      UUID propertyId, UUID ownershipId, OwnerDtos.UpdateOwnershipRequest request) {
    User actor = accessScope.requireUser();
    Property property = requireProperty(propertyId);
    PropertyOwnership share = requireShare(propertyId, ownershipId);

    BigDecimal percentage =
        request.ownershipPercentage() == null
            ? share.getOwnershipPercentage()
            : percent(request.ownershipPercentage());
    LocalDate from = request.effectiveFrom() == null ? share.getEffectiveFrom() : request.effectiveFrom();
    LocalDate to = request.effectiveTo() == null ? share.getEffectiveTo() : request.effectiveTo();
    assertPeriod(from, to);

    List<PropertyOwnership> existing = ownershipRepository.findAllForProperty(propertyId);
    UUID ownerId = share.getOwner() == null ? null : share.getOwner().getId();
    assertNoDuplicateOwner(existing, ownerId, from, to, share.getId());
    assertOpenTotalWithinHundred(existing, share.getId(), to == null ? percentage : null);

    share.setOwnershipPercentage(percentage);
    if (request.relationship() != null) {
      share.setRelationship(
          parseRelationship(request.relationship(), share.getRelationship()));
    }
    share.setEffectiveFrom(from);
    share.setEffectiveTo(to);
    if (request.notes() != null) share.setNotes(request.notes());
    share.setUpdatedBy(actor.getId());

    PropertyOwnership saved = ownershipRepository.save(share);
    recordOwnershipAudit(
        actor, AuditActions.UPDATE, "Changed a holding in " + property.getPropertyCode(),
        saved, property, share.getOwner());
    return OwnerDtos.OwnershipShare.from(saved);
  }

  /**
   * Close a holding - the soft delete. The row survives; only its end date is stamped, so a past
   * ownership stays reportable.
   */
  @Transactional
  public OwnerDtos.OwnershipShare closeOwnership(UUID propertyId, UUID ownershipId) {
    User actor = accessScope.requireUser();
    Property property = requireProperty(propertyId);
    PropertyOwnership share = requireShare(propertyId, ownershipId);

    if (!share.isOpen()) {
      throw ApiException.rule(
          "OWNERSHIP_ALREADY_CLOSED",
          "That holding has already ended and cannot be closed again");
    }

    share.setEffectiveTo(closingDate(share.getEffectiveFrom()));
    share.setUpdatedBy(actor.getId());
    PropertyOwnership saved = ownershipRepository.save(share);
    recordOwnershipAudit(
        actor, AuditActions.DELETE, "Closed a holding in " + property.getPropertyCode(),
        saved, property, share.getOwner());
    return OwnerDtos.OwnershipShare.from(saved);
  }

  /* ====================================================================== */
  /* Internals                                                              */
  /* ====================================================================== */

  private OwnerDtos.OwnerDetail detailOf(Owner owner) {
    List<OwnerDtos.OwnershipShare> holdings =
        ownershipRepository.findAllForOwner(owner.getId()).stream()
            .map(OwnerDtos.OwnershipShare::from)
            .toList();
    long open = holdings.stream().filter(OwnerDtos.OwnershipShare::open).count();

    return new OwnerDtos.OwnerDetail(
        owner.getId(),
        owner.getOwnerCode(),
        name(owner.getOwnerType()),
        owner.getDisplayName(),
        owner.getPrimaryContactName(),
        owner.getEmail(),
        owner.getPhone(),
        owner.getAlternatePhone(),
        owner.getIdReference(),
        owner.getTaxReference(),
        owner.getAddressLine1(),
        owner.getAddressLine2(),
        owner.getLocality(),
        owner.getCity(),
        owner.getState(),
        owner.getPostalCode(),
        owner.getCountry(),
        name(owner.getStatus()),
        owner.getNotes(),
        holdings.size(),
        open,
        holdings,
        owner.getCreatedAt(),
        owner.getUpdatedAt());
  }

  private PropertyOwnership requireShare(UUID propertyId, UUID ownershipId) {
    PropertyOwnership share =
        ownershipRepository
            .findByIdAndDeletedAtIsNull(ownershipId)
            .orElseThrow(
                () -> ApiException.notFound("We could not find that ownership record"));
    if (share.getProperty() == null || !propertyId.equals(share.getProperty().getId())) {
      throw ApiException.notFound("We could not find that ownership record on this property");
    }
    return share;
  }

  private Property requireProperty(UUID propertyId) {
    return propertyRepository
        .findByIdAndDeletedAtIsNull(propertyId)
        .orElseThrow(() -> ApiException.notFound(ErrorMessages.friendly("PROPERTY_NOT_FOUND")));
  }

  /**
   * No owner may hold two shares of one property whose periods overlap.
   *
   * <p>Two overlapping rows for the same owner would be counted twice by every owner statement, so
   * the second is refused rather than stored. The row being edited is excluded so a change that
   * keeps its own period is not a clash with itself.</p>
   */
  private static void assertNoDuplicateOwner(
      List<PropertyOwnership> existing,
      UUID ownerId,
      LocalDate from,
      LocalDate to,
      UUID editingId) {
    if (ownerId == null) {
      return;
    }
    for (PropertyOwnership other : existing) {
      if (other.getId() != null && other.getId().equals(editingId)) continue;
      UUID otherOwner = other.getOwner() == null ? null : other.getOwner().getId();
      if (ownerId.equals(otherOwner)
          && overlaps(from, to, other.getEffectiveFrom(), other.getEffectiveTo())) {
        throw ApiException.duplicate(
            "OWNERSHIP_DUPLICATE_OWNER",
            ErrorMessages.friendly("OWNERSHIP_DUPLICATE_OWNER"));
      }
    }
  }

  /**
   * The shares in force for a property may not, once this change is applied, come to more than the
   * whole.
   *
   * <p>The check is over the open holdings (those with no end date), which is the population the
   * rule is about; a closed holding is history and does not count. It runs inside the write's own
   * transaction, so a total that is momentarily above 100 is never committed.</p>
   */
  private static void assertOpenTotalWithinHundred(
      List<PropertyOwnership> existing, UUID editingId, BigDecimal adding) {
    BigDecimal total = zero();
    for (PropertyOwnership other : existing) {
      if (other.getId() != null && other.getId().equals(editingId)) continue;
      if (!other.isOpen()) continue;
      total = total.add(safe(other.getOwnershipPercentage()));
    }
    if (adding != null) {
      total = total.add(adding);
    }
    if (total.compareTo(ONE_HUNDRED) > 0) {
      throw ApiException.rule(
          "OWNERSHIP_EXCEEDS_ONE_HUNDRED",
          ErrorMessages.friendly("OWNERSHIP_EXCEEDS_ONE_HUNDRED"),
          List.of(
              "existing: " + sumPercentages(openOnly(existing, editingId)).toPlainString(),
              "attempted: " + (adding == null ? zero() : adding).toPlainString()));
    }
  }

  private static List<PropertyOwnership> openOnly(List<PropertyOwnership> existing, UUID excludeId) {
    List<PropertyOwnership> open = new ArrayList<>();
    for (PropertyOwnership other : existing) {
      if (other.getId() != null && other.getId().equals(excludeId)) continue;
      if (other.isOpen()) open.add(other);
    }
    return open;
  }

  private static BigDecimal sumPercentages(List<PropertyOwnership> shares) {
    BigDecimal total = zero();
    for (PropertyOwnership share : shares) {
      total = total.add(safe(share.getOwnershipPercentage()));
    }
    return total;
  }

  /** Two periods overlap when neither ends before the other begins; a null end means "still held". */
  private static boolean overlaps(LocalDate fromA, LocalDate toA, LocalDate fromB, LocalDate toB) {
    if (fromA == null || fromB == null) return false;
    LocalDate endA = toA == null ? LocalDate.MAX : toA;
    LocalDate endB = toB == null ? LocalDate.MAX : toB;
    return !fromA.isAfter(endB) && !fromB.isAfter(endA);
  }

  /** A holding cannot end before it begins, and it starts from the day it was recorded forwards. */
  private static void assertPeriod(LocalDate from, LocalDate to) {
    if (from == null) {
      throw ApiException.badRequest("A holding must have an effective start date");
    }
    if (to != null && to.isBefore(from)) {
      throw ApiException.badRequest("A holding cannot end before it begins");
    }
  }

  /** A close is stamped today, but never before the holding began - the schema forbids that. */
  private static LocalDate closingDate(LocalDate effectiveFrom) {
    LocalDate today = LocalDate.now();
    return effectiveFrom != null && today.isBefore(effectiveFrom) ? effectiveFrom : today;
  }

  private static BigDecimal percent(BigDecimal value) {
    if (value == null) {
      throw ApiException.badRequest("A holding must carry a share");
    }
    BigDecimal scaled = value.setScale(PERCENT_SCALE, RoundingMode.HALF_UP);
    if (scaled.signum() <= 0 || scaled.compareTo(ONE_HUNDRED) > 0) {
      throw ApiException.badRequest("A holding's share must be greater than 0 and no more than 100");
    }
    return scaled;
  }

  private static BigDecimal safe(BigDecimal value) {
    return value == null ? BigDecimal.ZERO : value;
  }

  private static BigDecimal zero() {
    return BigDecimal.ZERO.setScale(PERCENT_SCALE, RoundingMode.HALF_UP);
  }

  private static void applyAddress(Owner owner, OwnerDtos.CreateOwnerRequest request) {
    owner.setAddressLine1(request.addressLine1());
    owner.setAddressLine2(request.addressLine2());
    owner.setLocality(request.locality());
    owner.setCity(request.city());
    owner.setState(request.state());
    owner.setPostalCode(request.postalCode());
    owner.setCountry(
        request.country() == null || request.country().isBlank()
            ? "India"
            : request.country().trim());
  }

  private static void applyAddressUpdate(Owner owner, OwnerDtos.UpdateOwnerRequest request) {
    if (request.addressLine1() != null) owner.setAddressLine1(request.addressLine1());
    if (request.addressLine2() != null) owner.setAddressLine2(request.addressLine2());
    if (request.locality() != null) owner.setLocality(request.locality());
    if (request.city() != null) owner.setCity(request.city());
    if (request.state() != null) owner.setState(request.state());
    if (request.postalCode() != null) owner.setPostalCode(request.postalCode());
    if (request.country() != null) owner.setCountry(request.country());
  }

  private void recordAudit(User actor, String action, String description, Owner owner) {
    audit.record(
        actor.getId(),
        actor.getFullName(),
        AuditActions.OWNER,
        action,
        description,
        "OWNER",
        owner.getId(),
        owner.getOwnerCode());
  }

  private void recordOwnershipAudit(
      User actor,
      String action,
      String description,
      PropertyOwnership share,
      Property property,
      Owner owner) {
    audit.record(
        actor.getId(),
        actor.getFullName(),
        AuditActions.OWNER,
        action,
        description,
        "PROPERTY_OWNERSHIP",
        share.getId(),
        property.getPropertyCode() + " / " + (owner == null ? null : owner.getOwnerCode()));
  }

  /**
   * A status supplied as a list filter, or null meaning "every status".
   *
   * <p>Deliberately separate from {@link #parseStatus}. The owner register is read unfiltered by
   * default, so an absent status means "no filter" rather than a client mistake; reusing the
   * required-value parser here made {@code GET /api/v1/owners} answer 400 to its own default
   * request. A value that is supplied still goes through {@link #parseStatus}, so an unrecognised
   * status keeps its named refusal.</p>
   */
  private static OwnerStatus parseOptionalStatus(String value) {
    return value == null || value.isBlank() ? null : parseStatus(value);
  }

  private static OwnerStatus parseStatus(String value) {
    if (value == null || value.isBlank()) return null;
    return parseStatusOrDefault(value, null);
  }

  private static OwnerStatus parseStatusOrDefault(String value, OwnerStatus fallback) {
    if (value == null || value.isBlank()) {
      if (fallback == null) {
        throw ApiException.badRequest("An owner status is required");
      }
      return fallback;
    }
    try {
      return OwnerStatus.valueOf(value.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException ex) {
      throw ApiException.badRequest("'" + value + "' is not an owner status");
    }
  }

  /**
   * A type supplied as a list filter, or null meaning "every type".
   *
   * <p>Separate from {@link #parseType} for the same reason the optional status parser is separate:
   * the register is read unfiltered by default, so an absent type means "no filter". Passing a null
   * fallback to {@link #parseType} from the list method made {@code GET /api/v1/owners} answer 400
   * to its own default request.</p>
   */
  private static OwnerType parseOptionalType(String value) {
    return value == null || value.isBlank() ? null : parseType(value, null);
  }

  private static OwnerType parseType(String value, OwnerType fallback) {
    if (value == null || value.isBlank()) {
      if (fallback == null) {
        throw ApiException.badRequest("An owner type is required");
      }
      return fallback;
    }
    try {
      return OwnerType.valueOf(value.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException ex) {
      throw ApiException.badRequest("'" + value + "' is not an owner type");
    }
  }

  private static OwnershipRelationship parseRelationship(
      String value, OwnershipRelationship fallback) {
    if (value == null || value.isBlank()) {
      return fallback == null ? OwnershipRelationship.CO_OWNER : fallback;
    }
    try {
      return OwnershipRelationship.valueOf(value.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException ex) {
      throw ApiException.badRequest("'" + value + "' is not an ownership relationship");
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

  private static List<Owner> sortOwners(List<Owner> items, Sort sort) {
    if (sort == null || sort.isUnsorted()) {
      return items;
    }
    Comparator<Owner> comparator = null;
    for (Sort.Order order : sort) {
      Comparator<Owner> next = ownerComparator(order.getProperty());
      if (order.isDescending()) {
        next = next.reversed();
      }
      comparator = comparator == null ? next : comparator.thenComparing(next);
    }
    List<Owner> sorted = new ArrayList<>(items);
    if (comparator != null) {
      sorted.sort(comparator);
    }
    return sorted;
  }

  private static Comparator<Owner> ownerComparator(String property) {
    Comparator<Owner> byCreatedAt =
        Comparator.comparing(
            Owner::getCreatedAt, Comparator.nullsLast(Comparator.<LocalDateTime>naturalOrder()));
    return switch (property == null ? "" : property) {
      case "displayName" ->
          Comparator.comparing(
              Owner::getDisplayName, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER));
      case "ownerCode" ->
          Comparator.comparing(
              Owner::getOwnerCode, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER));
      case "status" ->
          Comparator.comparing(
              current -> current.getStatus() == null ? "" : current.getStatus().name());
      case "city" ->
          Comparator.comparing(
              Owner::getCity, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER));
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
