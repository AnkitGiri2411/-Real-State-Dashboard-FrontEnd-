package com.meridian.realestate.service;

import com.meridian.realestate.domain.Amenity;
import com.meridian.realestate.domain.Property;
import com.meridian.realestate.domain.PropertyAmenity;
import com.meridian.realestate.domain.PropertyStatus;
import com.meridian.realestate.domain.PropertyStatusHistory;
import com.meridian.realestate.domain.PropertyType;
import com.meridian.realestate.domain.UnitStatus;
import com.meridian.realestate.domain.User;
import com.meridian.realestate.dto.BuildingDtos;
import com.meridian.realestate.dto.ErrorMessages;
import com.meridian.realestate.dto.PropertyDtos;
import com.meridian.realestate.dto.UnitDtos;
import com.meridian.realestate.exception.ApiException;
import com.meridian.realestate.repository.AgentPropertyAssignmentRepository;
import com.meridian.realestate.repository.AmenityRepository;
import com.meridian.realestate.repository.BuildingRepository;
import com.meridian.realestate.repository.DocumentRepository;
import com.meridian.realestate.repository.LeaseRepository;
import com.meridian.realestate.repository.PropertyAmenityRepository;
import com.meridian.realestate.repository.PropertyMediaRepository;
import com.meridian.realestate.repository.PropertyOwnershipRepository;
import com.meridian.realestate.repository.PropertyRepository;
import com.meridian.realestate.repository.PropertyStatusHistoryRepository;
import com.meridian.realestate.repository.PropertyTypeRepository;
import com.meridian.realestate.repository.UnitRepository;
import com.meridian.realestate.security.AccessScope;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The asset register: properties, what hangs off them, and the rules that keep the register coherent.
 *
 * <p><b>Why this class exists.</b> A property is the record everything else in the portfolio points
 * at - buildings, units, leases, invoices, documents - so the operations that create, change, move
 * and archive one have to be stated in a single place where the whole shape of an asset is visible.
 * The list, the detail, the metrics projection (occupancy, rent roll, valuation), the buildings and
 * units beneath it, its amenities and its ownership shares all belong to the same question - "what
 * is this asset and what is it doing" - and splitting them across a controller and several
 * repositories is how two screens end up computing occupancy differently.</p>
 *
 * <p><b>The rules it protects.</b></p>
 * <ul>
 *   <li><b>Position is all-or-nothing.</b> A latitude without a longitude is refused as a 400 before
 *       the database's paired constraint can reject it as an opaque error. The map is a real
 *       projection over real coordinates, so half a position is never saved, and
 *       {@link #map()} returns only the properties that genuinely carry both - it never invents a
 *       coordinate for a property whose position is unknown.</li>
 *   <li><b>Archiving is soft, and once.</b> A property is never hard-deleted - the leases, invoices
 *       and audit rows that reference it must stay coherent - so archiving stamps {@code deletedAt}.
 *       A second archive of an already-archived property is refused rather than silently
 *       re-stamping it, which is what makes the operation idempotent in outcome without being
 *       meaningless in effect.</li>
 *   <li><b>A property with running leases cannot be archived.</b> The rent it produces and the
 *       tenancies in it are live facts; archiving the asset out from under them would strand them.
 *       The refusal is a 409 named {@code PROPERTY_HAS_ACTIVE_LEASES}.</li>
 *   <li><b>A terminal asset stays terminal.</b> Once a property is {@code SOLD} it has left the
 *       register's lifecycle; a status change out of it is refused.</li>
 * </ul>
 *
 * <p><b>Occupancy and the rent roll share one population.</b> Both are taken over the leasable,
 * non-archived units, so the headline rent and the occupancy percentage cannot describe different
 * sets of stock. The rent roll is the asking rent the desk is asking across that stock; the
 * contractual rent actually billed is the rent module's question. Occupancy is guarded: no leasable
 * units reads {@code 0%}, never a divide-by-zero.</p>
 *
 * <p><b>Scope.</b> The property register is firm-wide and protected by permission alone (see
 * {@link AccessScope}) - a firm does not hide its own portfolio from its own staff, and it is the
 * personally carried work that is scoped, not the assets. This service therefore uses
 * {@link AccessScope} to resolve the <em>acting</em> account for a change and its audit entry, not
 * to narrow whose properties a reader may see.</p>
 *
 * <p><b>A note on paging.</b> {@code PropertyRepository} offers search and a plain paged read, but
 * not one combined query for the register's five filters (status, type, city, owner, agent). Rather
 * than put a literal {@code order by} in a query - which the product forbids - the register is
 * filtered, sorted and sliced here, so the page the caller asked for is the page they get, with
 * correct totals. The missing combined repository method is reported rather than added, because the
 * repository layer is not this task's to change.</p>
 */
@Service
public class PropertyService {

  private final PropertyRepository propertyRepository;
  private final PropertyTypeRepository propertyTypeRepository;
  private final UnitRepository unitRepository;
  private final BuildingRepository buildingRepository;
  private final DocumentRepository documentRepository;
  private final PropertyOwnershipRepository ownershipRepository;
  private final PropertyAmenityRepository propertyAmenityRepository;
  private final AmenityRepository amenityRepository;
  private final PropertyMediaRepository propertyMediaRepository;
  private final PropertyStatusHistoryRepository statusHistoryRepository;
  private final LeaseRepository leaseRepository;
  private final AgentPropertyAssignmentRepository agentAssignments;
  private final BusinessNumberService businessNumbers;
  private final AuditRecordingService audit;
  private final AccessScope accessScope;

  public PropertyService(
      PropertyRepository propertyRepository,
      PropertyTypeRepository propertyTypeRepository,
      UnitRepository unitRepository,
      BuildingRepository buildingRepository,
      DocumentRepository documentRepository,
      PropertyOwnershipRepository ownershipRepository,
      PropertyAmenityRepository propertyAmenityRepository,
      AmenityRepository amenityRepository,
      PropertyMediaRepository propertyMediaRepository,
      PropertyStatusHistoryRepository statusHistoryRepository,
      LeaseRepository leaseRepository,
      AgentPropertyAssignmentRepository agentAssignments,
      BusinessNumberService businessNumbers,
      AuditRecordingService audit,
      AccessScope accessScope) {
    this.propertyRepository = propertyRepository;
    this.propertyTypeRepository = propertyTypeRepository;
    this.unitRepository = unitRepository;
    this.buildingRepository = buildingRepository;
    this.documentRepository = documentRepository;
    this.ownershipRepository = ownershipRepository;
    this.propertyAmenityRepository = propertyAmenityRepository;
    this.amenityRepository = amenityRepository;
    this.propertyMediaRepository = propertyMediaRepository;
    this.statusHistoryRepository = statusHistoryRepository;
    this.leaseRepository = leaseRepository;
    this.agentAssignments = agentAssignments;
    this.businessNumbers = businessNumbers;
    this.audit = audit;
    this.accessScope = accessScope;
  }

  /* ====================================================================== */
  /* Reads                                                                  */
  /* ====================================================================== */

  /** The live property behind an id, or a 404. Archived properties are not addressable. */
  @Transactional(readOnly = true)
  public Property require(UUID propertyId) {
    return propertyRepository
        .findByIdAndDeletedAtIsNull(propertyId)
        .orElseThrow(() -> ApiException.notFound(ErrorMessages.friendly("PROPERTY_NOT_FOUND")));
  }

  /**
   * The register, filtered, searched, sorted and paged.
   *
   * <p>See the class note on paging: the filters and the sort are applied here because the combined
   * repository query does not exist, and a literal {@code order by} in a paged query is forbidden.
   * The free-text term is pushed to the database's own search where one is given, so only the term
   * is transcribed into SQL and the remaining filters are applied to the rows it returns.</p>
   */
  @Transactional(readOnly = true)
  public Page<PropertyDtos.PropertySummary> list(
      String status,
      UUID propertyTypeId,
      String city,
      UUID ownerId,
      UUID agentId,
      String search,
      Pageable pageable) {

    PropertyStatus statusFilter = parseOptionalStatus(status);
    String term = blankToNull(search);
    String cityFilter = blankToNull(city);

    List<Property> candidates =
        term == null
            ? propertyRepository.findAllByDeletedAtIsNull()
            : propertyRepository.search(term, Pageable.unpaged()).getContent();

    Set<UUID> owned = ownerId == null ? null : propertyIdsHeldBy(ownerId);
    Set<UUID> carried = agentId == null ? null : propertyIdsCarriedBy(agentId);

    List<Property> filtered =
        candidates.stream()
            .filter(property -> statusFilter == null || property.getStatus() == statusFilter)
            .filter(property -> propertyTypeId == null || isOfType(property, propertyTypeId))
            .filter(property -> cityFilter == null || cityFilter.equalsIgnoreCase(property.getCity()))
            .filter(property -> owned == null || owned.contains(property.getId()))
            .filter(property -> carried == null || carried.contains(property.getId()))
            .toList();

    return pageOf(sortProperties(filtered, pageable.getSort()), pageable, PropertyDtos.PropertySummary::from);
  }

  /** One property's full record, with the counts the header shows. */
  @Transactional(readOnly = true)
  public PropertyDtos.PropertyDetail detail(UUID propertyId) {
    return detail(require(propertyId));
  }

  /**
   * One property's operational metrics.
   *
   * <p>Occupancy is guarded against a zero denominator; the rent roll is the asking rent over the
   * same leasable, live stock the occupancy denominator is taken from, so the two figures describe
   * one population.</p>
   */
  @Transactional(readOnly = true)
  public PropertyDtos.PropertyMetrics metrics(UUID propertyId) {
    Property property = require(propertyId);
    long units = unitRepository.countByPropertyIdAndDeletedAtIsNull(propertyId);
    long leasable = unitRepository.countByPropertyIdAndLeasableTrueAndDeletedAtIsNull(propertyId);
    long occupied =
        unitRepository.countByPropertyIdAndLeasableTrueAndStatusAndDeletedAtIsNull(
            propertyId, UnitStatus.OCCUPIED);

    return new PropertyDtos.PropertyMetrics(
        property.getId(),
        property.getPropertyCode(),
        units,
        leasable,
        occupied,
        occupancyPercent(occupied, leasable),
        MoneyPolicy.money(unitRepository.sumAskingRentByProperty(propertyId)),
        MoneyPolicy.money(property.getCurrentValuation()),
        MoneyPolicy.money(property.getAcquisitionValue()),
        property.getLandAreaSqft(),
        property.getBuiltAreaSqft());
  }

  /**
   * The portfolio map: only properties that carry a real coordinate pair.
   *
   * <p>The set comes from {@code findAllPositioned()}, which filters on both coordinates being
   * present, and each marker's position is the stored value - never a guessed one. A property whose
   * position is not known is simply absent, which is the honest answer rather than a marker placed
   * at an invented point.</p>
   */
  @Transactional(readOnly = true)
  public List<PropertyDtos.MapMarker> map() {
    return propertyRepository.findAllPositioned().stream().map(this::marker).toList();
  }

  /** The blocks within one property, in the order the unit board presents them. */
  @Transactional(readOnly = true)
  public List<BuildingDtos.BuildingSummary> buildings(UUID propertyId) {
    require(propertyId);
    return buildingRepository
        .findAllByPropertyIdAndDeletedAtIsNullOrderByNameAsc(propertyId)
        .stream()
        .map(
            building ->
                BuildingDtos.BuildingSummary.from(
                    building,
                    unitRepository.countByBuildingIdAndDeletedAtIsNull(building.getId())))
        .toList();
  }

  /** A paged view of one property's units. */
  @Transactional(readOnly = true)
  public Page<UnitDtos.UnitSummary> units(UUID propertyId, Pageable pageable) {
    require(propertyId);
    return unitRepository
        .findAllByPropertyIdAndDeletedAtIsNull(propertyId, pageable)
        .map(UnitDtos.UnitSummary::from);
  }

  /** The facilities one property offers, with the per-property note on each. */
  @Transactional(readOnly = true)
  public List<PropertyDtos.AmenityLink> amenities(UUID propertyId) {
    require(propertyId);
    return propertyAmenityRepository.findAllByProperty_Id(propertyId).stream()
        .map(
            link ->
                new PropertyDtos.AmenityLink(
                    link.getAmenity() == null ? null : link.getAmenity().getId(),
                    link.getAmenity() == null ? null : link.getAmenity().getCode(),
                    link.getAmenity() == null ? null : link.getAmenity().getName(),
                    link.getAmenity() == null ? null : link.getAmenity().getCategory(),
                    link.getNotes()))
        .toList();
  }

  /**
   * Who owns this property, read from the ownership register.
   *
   * <p>Read-only here: the ownership slice owns the 100%-rule arithmetic and its own writes, so this
   * endpoint reports the shares rather than deriving or changing them. Writing a holding belongs to
   * the owner service, not to the property register.</p>
   */
  @Transactional(readOnly = true)
  public List<PropertyDtos.OwnershipShare> ownership(UUID propertyId) {
    require(propertyId);
    return ownershipRepository.findAllForProperty(propertyId).stream()
        .map(
            share ->
                new PropertyDtos.OwnershipShare(
                    share.getId(),
                    share.getOwner() == null ? null : share.getOwner().getId(),
                    share.getOwner() == null ? null : share.getOwner().getOwnerCode(),
                    share.getOwner() == null ? null : share.getOwner().getDisplayName(),
                    share.getOwnershipPercentage(),
                    share.getRelationship() == null ? null : share.getRelationship().name(),
                    share.getEffectiveFrom(),
                    share.getEffectiveTo(),
                    share.isOpen()))
        .toList();
  }

  /** How many documents are filed against this property. The documents themselves are elsewhere. */
  @Transactional(readOnly = true)
  public PropertyDtos.DocumentCount documents(UUID propertyId) {
    require(propertyId);
    return new PropertyDtos.DocumentCount(
        documentRepository.findAllByPropertyIdAndDeletedAtIsNull(propertyId).size());
  }

  /** A property's status timeline, most recent first - how the asset got where it is. */
  @Transactional(readOnly = true)
  public List<PropertyDtos.StatusHistoryEntry> statusHistory(UUID propertyId) {
    require(propertyId);
    return statusHistoryRepository.findAllByPropertyIdOrderByChangedAtDesc(propertyId).stream()
        .map(
            entry ->
                new PropertyDtos.StatusHistoryEntry(
                    entry.getId(),
                    entry.getFromStatus() == null ? null : entry.getFromStatus().name(),
                    entry.getToStatus() == null ? null : entry.getToStatus().name(),
                    entry.getReason(),
                    entry.getChangedBy(),
                    entry.getChangedAt()))
        .toList();
  }

  /* ====================================================================== */
  /* Writes                                                                 */
  /* ====================================================================== */

  /** Add a property to the register, minting its human-readable code. */
  @Transactional
  public PropertyDtos.PropertyDetail create(PropertyDtos.CreatePropertyRequest request) {
    User actor = accessScope.requireUser();
    assertPositionPaired(request.latitude(), request.longitude());

    PropertyType type =
        propertyTypeRepository
            .findById(request.propertyTypeId())
            .orElseThrow(() -> ApiException.notFound("That property type does not exist"));

    Property property = new Property();
    property.setPropertyCode(businessNumbers.next(BusinessNumberService.PROPERTY));
    property.setName(request.name().trim());
    property.setPropertyType(type);
    property.setStatus(parseStatus(request.status(), PropertyStatus.DRAFT));
    applyAddress(property, request);
    property.setLatitude(request.latitude());
    property.setLongitude(request.longitude());
    property.setDescription(request.description());
    property.setYearBuilt(request.yearBuilt());
    property.setLandAreaSqft(request.landAreaSqft());
    property.setBuiltAreaSqft(request.builtAreaSqft());
    property.setAcquisitionValue(MoneyPolicy.money(request.acquisitionValue()));
    property.setCurrentValuation(MoneyPolicy.money(request.currentValuation()));
    property.setValuationDate(request.valuationDate());
    property.setCreatedBy(actor.getId());
    property.setUpdatedBy(actor.getId());

    Property saved = propertyRepository.save(property);

    audit.record(
        actor.getId(),
        actor.getFullName(),
        AuditActions.PROPERTY,
        AuditActions.CREATE,
        "Added property " + saved.getPropertyCode() + " (" + saved.getName() + ")",
        "PROPERTY",
        saved.getId(),
        saved.getPropertyCode());

    return detail(saved);
  }

  /** Change a property's details. Only the fields supplied are applied. */
  @Transactional
  public PropertyDtos.PropertyDetail update(
      UUID propertyId, PropertyDtos.UpdatePropertyRequest request) {
    User actor = accessScope.requireUser();
    Property property = require(propertyId);

    if (request.propertyTypeId() != null && !isOfType(property, request.propertyTypeId())) {
      PropertyType type =
          propertyTypeRepository
              .findById(request.propertyTypeId())
              .orElseThrow(() -> ApiException.notFound("That property type does not exist"));
      property.setPropertyType(type);
    }
    if (request.name() != null) property.setName(request.name().trim());
    if (request.addressLine1() != null) property.setAddressLine1(request.addressLine1());
    if (request.addressLine2() != null) property.setAddressLine2(request.addressLine2());
    if (request.locality() != null) property.setLocality(request.locality());
    if (request.city() != null) property.setCity(request.city());
    if (request.state() != null) property.setState(request.state());
    if (request.postalCode() != null) property.setPostalCode(request.postalCode());
    if (request.country() != null) property.setCountry(request.country());
    if (request.description() != null) property.setDescription(request.description());
    if (request.yearBuilt() != null) property.setYearBuilt(request.yearBuilt());
    if (request.landAreaSqft() != null) property.setLandAreaSqft(request.landAreaSqft());
    if (request.builtAreaSqft() != null) property.setBuiltAreaSqft(request.builtAreaSqft());
    if (request.acquisitionValue() != null) {
      property.setAcquisitionValue(MoneyPolicy.money(request.acquisitionValue()));
    }
    if (request.currentValuation() != null) {
      property.setCurrentValuation(MoneyPolicy.money(request.currentValuation()));
    }
    if (request.valuationDate() != null) property.setValuationDate(request.valuationDate());

    // Position is edited as a pair: an omitted side falls back to what is stored, and the result is
    // refused if that leaves exactly one coordinate - the rule the schema also protects.
    BigDecimal latitude = request.latitude() == null ? property.getLatitude() : request.latitude();
    BigDecimal longitude =
        request.longitude() == null ? property.getLongitude() : request.longitude();
    assertPositionPaired(latitude, longitude);
    property.setLatitude(latitude);
    property.setLongitude(longitude);

    property.setUpdatedBy(actor.getId());
    Property saved = propertyRepository.save(property);

    audit.record(
        actor.getId(),
        actor.getFullName(),
        AuditActions.PROPERTY,
        AuditActions.UPDATE,
        "Changed property " + saved.getPropertyCode(),
        "PROPERTY",
        saved.getId(),
        saved.getPropertyCode());

    return detail(saved);
  }

  /**
   * Archive a property - soft delete, once.
   *
   * <p>Refused (409) when the property still carries a running lease: the tenancy and the rent it
   * produces are live facts, and archiving the asset out from under them would strand them. Refused
   * also when the property has already been archived, so the operation is not silently repeated.</p>
   */
  @Transactional
  public PropertyDtos.PropertyDetail archive(UUID propertyId) {
    User actor = accessScope.requireUser();

    // Loaded including archived rows so "already archived" is distinguished from "never existed".
    Property property =
        propertyRepository
            .findById(propertyId)
            .orElseThrow(() -> ApiException.notFound(ErrorMessages.friendly("PROPERTY_NOT_FOUND")));

    if (property.isDeleted()) {
      throw ApiException.rule(
          "PROPERTY_ALREADY_ARCHIVED", "This property has already been archived");
    }
    if (hasRunningLease(propertyId)) {
      throw ApiException.rule(
          "PROPERTY_HAS_ACTIVE_LEASES",
          ErrorMessages.friendly("PROPERTY_HAS_ACTIVE_LEASES"));
    }

    property.setDeletedAt(LocalDateTime.now());
    property.setUpdatedBy(actor.getId());
    Property saved = propertyRepository.save(property);

    audit.record(
        actor.getId(),
        actor.getFullName(),
        AuditActions.PROPERTY,
        AuditActions.DELETE,
        "Archived property " + saved.getPropertyCode() + " (" + saved.getName() + ")",
        "PROPERTY",
        saved.getId(),
        saved.getPropertyCode());

    return detail(saved);
  }

  /**
   * Move a property between lifecycle states, recording the transition.
   *
   * <p>A no-op move (to the state it is already in) is not written to the history: a transition row
   * for something that did not happen would make the trail count an event nobody performed. A move
   * out of a terminal state ({@code SOLD}) is refused.</p>
   */
  @Transactional
  public PropertyDtos.PropertyDetail changeStatus(
      UUID propertyId, PropertyDtos.ChangeStatusRequest request) {
    User actor = accessScope.requireUser();
    Property property = require(propertyId);

    PropertyStatus from = property.getStatus();
    PropertyStatus target = parseStatus(request.status(), null);
    if (from == target) {
      return detail(property);
    }
    if (from != null && from.isTerminal()) {
      throw ApiException.rule(
          "PROPERTY_TRANSITION_NOT_ALLOWED",
          "A property that is "
              + from
              + " has reached a terminal state and cannot move to "
              + target);
    }

    property.setStatus(target);
    property.setUpdatedBy(actor.getId());
    Property saved = propertyRepository.save(property);

    recordStatusHistory(saved, from, target, request.reason(), actor.getId());

    audit.record(
        actor.getId(),
        actor.getFullName(),
        AuditActions.PROPERTY,
        AuditActions.STATUS_CHANGE,
        "Property " + saved.getPropertyCode() + " " + from + " -> " + target,
        "PROPERTY",
        saved.getId(),
        saved.getPropertyCode());

    return detail(saved);
  }

  /** Attach a facility to a property, with an optional note. Idempotent: re-attaching updates it. */
  @Transactional
  public List<PropertyDtos.AmenityLink> attachAmenity(
      UUID propertyId, PropertyDtos.AttachAmenityRequest request) {
    User actor = accessScope.requireUser();
    require(propertyId);

    Amenity amenity =
        amenityRepository
            .findById(request.amenityId())
            .orElseThrow(() -> ApiException.notFound("That amenity does not exist"));

    PropertyAmenity.PropertyAmenityId id =
        new PropertyAmenity.PropertyAmenityId(propertyId, amenity.getId());
    PropertyAmenity link =
        propertyAmenityRepository
            .findById(id)
            .orElseGet(
                () -> {
                  PropertyAmenity created = new PropertyAmenity();
                  created.setProperty(propertyRepository.getReferenceById(propertyId));
                  created.setAmenity(amenity);
                  return created;
                });
    if (request.notes() != null) {
      link.setNotes(request.notes());
    }
    propertyAmenityRepository.save(link);

    audit.record(
        actor.getId(),
        actor.getFullName(),
        AuditActions.PROPERTY,
        AuditActions.UPDATE,
        "Attached amenity " + amenity.getCode() + " to property " + propertyId,
        "PROPERTY",
        propertyId,
        amenity.getCode());

    return amenities(propertyId);
  }

  /** Detach a facility from a property. The link is removed outright; the facility is either on or not. */
  @Transactional
  public void detachAmenity(UUID propertyId, UUID amenityId) {
    User actor = accessScope.requireUser();
    require(propertyId);

    PropertyAmenity.PropertyAmenityId id =
        new PropertyAmenity.PropertyAmenityId(propertyId, amenityId);
    PropertyAmenity link =
        propertyAmenityRepository
            .findById(id)
            .orElseThrow(
                () -> ApiException.notFound("That amenity is not attached to this property"));
    propertyAmenityRepository.delete(link);

    audit.record(
        actor.getId(),
        actor.getFullName(),
        AuditActions.PROPERTY,
        AuditActions.UPDATE,
        "Detached amenity " + amenityId + " from property " + propertyId,
        "PROPERTY",
        propertyId,
        amenityId == null ? null : amenityId.toString());
  }

  /* ====================================================================== */
  /* Internals                                                              */
  /* ====================================================================== */

  private PropertyDtos.PropertyDetail detail(Property property) {
    UUID propertyId = property.getId();
    return new PropertyDtos.PropertyDetail(
        propertyId,
        property.getPropertyCode(),
        property.getName(),
        property.getPropertyType() == null ? null : property.getPropertyType().getId(),
        property.getPropertyType() == null ? null : property.getPropertyType().getCode(),
        property.getPropertyType() == null ? null : property.getPropertyType().getName(),
        property.getPropertyType() != null && property.getPropertyType().isLeasable(),
        property.getStatus() == null ? null : property.getStatus().name(),
        property.getAddressLine1(),
        property.getAddressLine2(),
        property.getLocality(),
        property.getCity(),
        property.getState(),
        property.getPostalCode(),
        property.getCountry(),
        property.getLatitude(),
        property.getLongitude(),
        property.hasPosition(),
        property.getDescription(),
        property.getYearBuilt(),
        property.getLandAreaSqft(),
        property.getBuiltAreaSqft(),
        property.getAcquisitionValue(),
        property.getCurrentValuation(),
        property.getValuationDate(),
        buildingRepository.countByPropertyIdAndDeletedAtIsNull(propertyId),
        unitRepository.countByPropertyIdAndDeletedAtIsNull(propertyId),
        documentRepository.findAllByPropertyIdAndDeletedAtIsNull(propertyId).size(),
        propertyMediaRepository.countByPropertyIdAndDeletedAtIsNull(propertyId),
        property.getCreatedAt(),
        property.getUpdatedAt());
  }

  private PropertyDtos.MapMarker marker(Property property) {
    long leasable =
        unitRepository.countByPropertyIdAndLeasableTrueAndDeletedAtIsNull(property.getId());
    long occupied =
        unitRepository.countByPropertyIdAndLeasableTrueAndStatusAndDeletedAtIsNull(
            property.getId(), UnitStatus.OCCUPIED);

    return new PropertyDtos.MapMarker(
        property.getId(),
        property.getPropertyCode(),
        property.getName(),
        property.getCity(),
        property.getStatus() == null ? null : property.getStatus().name(),
        property.getLatitude(),
        property.getLongitude(),
        MoneyPolicy.money(property.getCurrentValuation()),
        occupancyPercent(occupied, leasable));
  }

  private void recordStatusHistory(
      Property property,
      PropertyStatus from,
      PropertyStatus to,
      String reason,
      UUID actorId) {
    PropertyStatusHistory entry = new PropertyStatusHistory();
    entry.setProperty(property);
    entry.setFromStatus(from);
    entry.setToStatus(to);
    entry.setReason(reason);
    entry.setChangedBy(actorId);
    entry.setChangedAt(LocalDateTime.now());
    statusHistoryRepository.save(entry);
  }

  private boolean hasRunningLease(UUID propertyId) {
    return leaseRepository
        .findAllByProperty_IdAndDeletedAtIsNull(propertyId, Pageable.unpaged())
        .getContent()
        .stream()
        .anyMatch(lease -> lease.getStatus() != null && lease.getStatus().isRunning());
  }

  private Set<UUID> propertyIdsHeldBy(UUID ownerId) {
    return new HashSet<>(
        ownershipRepository.findPropertyIdsHeldByOwnerOn(ownerId, LocalDate.now()));
  }

  private Set<UUID> propertyIdsCarriedBy(UUID agentId) {
    return agentAssignments.findCurrentForAgent(agentId).stream()
        .map(assignment -> assignment.getProperty() == null ? null : assignment.getProperty().getId())
        .filter(Objects::nonNull)
        .collect(Collectors.toSet());
  }

  private static boolean isOfType(Property property, UUID propertyTypeId) {
    return property.getPropertyType() != null
        && propertyTypeId.equals(property.getPropertyType().getId());
  }

  private static void applyAddress(Property property, PropertyDtos.CreatePropertyRequest request) {
    property.setAddressLine1(request.addressLine1());
    property.setAddressLine2(request.addressLine2());
    property.setLocality(request.locality());
    property.setCity(request.city());
    property.setState(request.state());
    property.setPostalCode(request.postalCode());
    property.setCountry(
        request.country() == null || request.country().isBlank()
            ? "India"
            : request.country().trim());
  }

  /**
   * A position is both coordinates or neither.
   *
   * <p>Surfaced as a named 400 rather than left to the database's paired constraint, which would
   * report it as an opaque integrity error - see the class note.</p>
   */
  private static void assertPositionPaired(BigDecimal latitude, BigDecimal longitude) {
    if ((latitude == null) == (longitude == null)) {
      return;
    }
    throw ApiException.badRequest(
        latitude != null
            ? "A longitude is required when a latitude is given; a property's position is both "
                + "coordinates or neither"
            : "A latitude is required when a longitude is given; a property's position is both "
                + "coordinates or neither");
  }

  /** The occupancy percentage over the leasable stock, guarded against a zero denominator. */
  static int occupancyPercent(long occupied, long leasable) {
    return leasable <= 0 ? 0 : (int) Math.round((occupied * 100.0) / leasable);
  }

  /**
   * A status supplied as a list filter, or null meaning "every status".
   *
   * <p>Deliberately separate from {@link #parseStatus}. A register is read unfiltered by default, so
   * an absent status means "no filter" and not a client mistake. Reusing the required-value parser
   * here made {@code GET /api/v1/properties} answer 400 to its own default request, which is the
   * list endpoint's most common call. Once a value is actually supplied, it is parsed by
   * {@link #parseStatus} so an unrecognised status still gets the named refusal.</p>
   */
  private static PropertyStatus parseOptionalStatus(String value) {
    return value == null || value.isBlank() ? null : parseStatus(value, null);
  }

  private static PropertyStatus parseStatus(String value, PropertyStatus fallback) {
    if (value == null || value.isBlank()) {
      if (fallback == null) {
        throw ApiException.badRequest("A property status is required");
      }
      return fallback;
    }
    try {
      return PropertyStatus.valueOf(value.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException ex) {
      throw ApiException.badRequest("'" + value + "' is not a property status");
    }
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }

  // --------------------------------------------------------------------------
  // Paging and sorting, applied here because the combined repository query does
  // not exist (see the class note on paging).
  // --------------------------------------------------------------------------

  private static List<Property> sortProperties(List<Property> items, Sort sort) {
    if (sort == null || sort.isUnsorted()) {
      return items;
    }
    Comparator<Property> comparator = null;
    for (Sort.Order order : sort) {
      Comparator<Property> next = propertyComparator(order.getProperty());
      if (order.isDescending()) {
        next = next.reversed();
      }
      comparator = comparator == null ? next : comparator.thenComparing(next);
    }
    List<Property> sorted = new ArrayList<>(items);
    if (comparator != null) {
      sorted.sort(comparator);
    }
    return sorted;
  }

  private static Comparator<Property> propertyComparator(String property) {
    Comparator<Property> byCreatedAt =
        Comparator.comparing(
            Property::getCreatedAt, Comparator.nullsLast(Comparator.<LocalDateTime>naturalOrder()));
    return switch (property == null ? "" : property) {
      case "name" ->
          Comparator.comparing(
              Property::getName, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER));
      case "propertyCode" ->
          Comparator.comparing(
              Property::getPropertyCode, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER));
      case "city" ->
          Comparator.comparing(
              Property::getCity, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER));
      case "status" ->
          Comparator.comparing(
              current -> current.getStatus() == null ? "" : current.getStatus().name());
      case "currentValuation" ->
          Comparator.comparing(
              Property::getCurrentValuation,
              Comparator.nullsLast(Comparator.<BigDecimal>naturalOrder()));
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
