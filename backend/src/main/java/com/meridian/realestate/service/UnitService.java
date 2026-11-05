package com.meridian.realestate.service;

import com.meridian.realestate.domain.Building;
import com.meridian.realestate.domain.Lease;
import com.meridian.realestate.domain.Property;
import com.meridian.realestate.domain.Tenant;
import com.meridian.realestate.domain.Unit;
import com.meridian.realestate.domain.UnitFacing;
import com.meridian.realestate.domain.UnitFurnishing;
import com.meridian.realestate.domain.UnitStatus;
import com.meridian.realestate.domain.UnitStatusHistory;
import com.meridian.realestate.domain.UnitType;
import com.meridian.realestate.domain.User;
import com.meridian.realestate.dto.ErrorMessages;
import com.meridian.realestate.dto.UnitDtos;
import com.meridian.realestate.exception.ApiException;
import com.meridian.realestate.repository.BuildingRepository;
import com.meridian.realestate.repository.LeaseRepository;
import com.meridian.realestate.repository.PropertyRepository;
import com.meridian.realestate.repository.TenantRepository;
import com.meridian.realestate.repository.UnitRepository;
import com.meridian.realestate.repository.UnitStatusHistoryRepository;
import com.meridian.realestate.repository.UnitTypeRepository;
import com.meridian.realestate.security.AccessScope;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The unit register, the unit board and the occupancy figures over them.
 *
 * <p><b>Why this class exists.</b> A unit is the smallest lettable or sellable thing in the
 * portfolio and the busiest read in the product: occupancy, vacancy, the rent roll and the unit
 * board are all computed over units. Keeping the reads and the writes together is what stops the
 * board, the occupancy count and a lease check-in disagreeing about whether a unit is let.</p>
 *
 * <p><b>The rules it protects.</b></p>
 * <ul>
 *   <li><b>Leasability and lifecycle are kept consistent.</b> A non-lettable unit - a lobby, a plant
 *       room, a marketing suite - is real stock that is never part of an occupancy figure. The
 *       database refuses to store one in a lettable state ({@code ck_units_not_lettable_status});
 *       the service refuses it first, as a named answer, rather than letting the constraint report
 *       it as an opaque integrity error.</li>
 *   <li><b>A unit that is already let is not let again.</b> Marking a unit as let is checked against
 *       the lease that is actually in force on it: a unit that already carries a running lease is
 *       refused with {@code UNIT_ALREADY_LET} (409), read from
 *       {@code LeaseRepository.findRunningLeaseForUnit} - the same query the one-running-lease
 *       index is built around, so the two cannot disagree.</li>
 *   <li><b>Occupancy is guarded, never a divide-by-zero.</b> A property with no leasable units reads
 *       {@code 0%}, not {@code NaN}.</li>
 *   <li><b>Every status move is written down.</b> The current status lives on the unit and the move
 *       is appended to {@code unit_status_history}, which is what lets the board answer "when did
 *       this become occupied" rather than only "is it occupied now". A no-op move is not recorded.</li>
 * </ul>
 *
 * <p><b>Scope.</b> Units are firm-wide and protected by permission alone; {@link AccessScope}
 * resolves the acting account for a change and its audit entry. Occupancy counts are computed over
 * the property's live units in one read rather than by a count per state, so the figures describe
 * one population at one instant.</p>
 *
 * <p><b>A note on the board.</b> The board groups by building and then by floor, ordered by floor
 * then unit number - the order the query already returns - because that is the physical stack a
 * supervisor walks. A unit in no block is grouped under an {@code Unassigned} group rather than
 * dropped.</p>
 */
@Service
public class UnitService {

  private final UnitRepository unitRepository;
  private final PropertyRepository propertyRepository;
  private final BuildingRepository buildingRepository;
  private final UnitTypeRepository unitTypeRepository;
  private final UnitStatusHistoryRepository statusHistoryRepository;
  private final LeaseRepository leaseRepository;
  private final TenantRepository tenantRepository;
  private final BusinessNumberService businessNumbers;
  private final AuditRecordingService audit;
  private final AccessScope accessScope;

  public UnitService(
      UnitRepository unitRepository,
      PropertyRepository propertyRepository,
      BuildingRepository buildingRepository,
      UnitTypeRepository unitTypeRepository,
      UnitStatusHistoryRepository statusHistoryRepository,
      LeaseRepository leaseRepository,
      TenantRepository tenantRepository,
      BusinessNumberService businessNumbers,
      AuditRecordingService audit,
      AccessScope accessScope) {
    this.unitRepository = unitRepository;
    this.propertyRepository = propertyRepository;
    this.buildingRepository = buildingRepository;
    this.unitTypeRepository = unitTypeRepository;
    this.statusHistoryRepository = statusHistoryRepository;
    this.leaseRepository = leaseRepository;
    this.tenantRepository = tenantRepository;
    this.businessNumbers = businessNumbers;
    this.audit = audit;
    this.accessScope = accessScope;
  }

  /* ====================================================================== */
  /* Reads                                                                  */
  /* ====================================================================== */

  /** The live unit behind an id, or a 404. Archived units are not addressable. */
  @Transactional(readOnly = true)
  public Unit require(UUID unitId) {
    return unitRepository
        .findByIdAndDeletedAtIsNull(unitId)
        .orElseThrow(() -> ApiException.notFound(ErrorMessages.friendly("UNIT_NOT_FOUND")));
  }

  /**
   * The unit register, filtered, searched, sorted and paged.
   *
   * <p>{@code UnitRepository} offers a plain paged read and a by-property read but no combined
   * filtered query and no free-text search, so the filters, the sort and the slice are applied here.
   * No repository query carries a literal {@code order by}.</p>
   */
  @Transactional(readOnly = true)
  public Page<UnitDtos.UnitSummary> list(
      UUID propertyId,
      UUID buildingId,
      UUID unitTypeId,
      String status,
      Boolean leasable,
      Integer floor,
      Integer bedrooms,
      String search,
      Pageable pageable) {

    UnitStatus statusFilter = parseOptionalStatus(status);
    String term = blankToNull(search);

    List<Unit> candidates =
        propertyId == null
            ? unitRepository.findAllByDeletedAtIsNull()
            : unitRepository.findAllByPropertyIdAndDeletedAtIsNull(propertyId);

    List<Unit> filtered =
        candidates.stream()
            .filter(unit -> buildingId == null || belongsToBuilding(unit, buildingId))
            .filter(unit -> unitTypeId == null || isOfType(unit, unitTypeId))
            .filter(unit -> statusFilter == null || unit.getStatus() == statusFilter)
            .filter(unit -> leasable == null || unit.isLeasable() == leasable)
            .filter(unit -> floor == null || unit.getFloorNumber() == floor)
            .filter(unit -> bedrooms == null || unit.getBedrooms() == bedrooms)
            .filter(unit -> term == null || matchesTerm(unit, term))
            .toList();

    return pageOf(sortUnits(filtered, pageable.getSort()), pageable, UnitDtos.UnitSummary::from);
  }

  /** One unit's full record, including the lease currently in force on it. */
  @Transactional(readOnly = true)
  public UnitDtos.UnitDetail detail(UUID unitId) {
    return detail(require(unitId));
  }

  /**
   * The unit board for one property: its units grouped by building and then by floor.
   *
   * <p>Read from {@code ...OrderByFloorNumberAscUnitNumberAsc}, so the units of each floor are
   * already in the order a person walks them and the view does not re-sort them.</p>
   */
  @Transactional(readOnly = true)
  public UnitDtos.UnitBoard board(UUID propertyId) {
    Property property = requireProperty(propertyId);
    List<Unit> units =
        unitRepository.findAllByPropertyIdAndDeletedAtIsNullOrderByFloorNumberAscUnitNumberAsc(
            propertyId);

    Map<UUID, List<Unit>> byBuilding = new LinkedHashMap<>();
    Map<UUID, Building> buildings = new LinkedHashMap<>();
    for (Unit unit : units) {
      UUID key = unit.getBuilding() == null ? null : unit.getBuilding().getId();
      byBuilding.computeIfAbsent(key, ignored -> new ArrayList<>()).add(unit);
      if (key != null) {
        buildings.putIfAbsent(key, unit.getBuilding());
      }
    }

    List<UnitDtos.BuildingGroup> groups = new ArrayList<>();
    byBuilding.forEach(
        (buildingId, groupUnits) -> {
          Building building = buildings.get(buildingId);
          groups.add(
              new UnitDtos.BuildingGroup(
                  buildingId,
                  building == null ? null : building.getBuildingCode(),
                  building == null ? "Unassigned" : building.getName(),
                  floorGroups(groupUnits)));
        });

    return new UnitDtos.UnitBoard(property.getId(), property.getPropertyCode(), groups);
  }

  /**
   * The occupancy figures for one property, computed over one read of its live units.
   *
   * <p>Occupancy is over the leasable stock only, and the percentage is guarded: zero leasable units
   * reads {@code 0%}.</p>
   */
  @Transactional(readOnly = true)
  public UnitDtos.UnitOccupancy occupancy(UUID propertyId) {
    requireProperty(propertyId);
    List<Unit> units = unitRepository.findAllByPropertyIdAndDeletedAtIsNull(propertyId);

    long total = units.size();
    long leasable = units.stream().filter(Unit::isLeasable).count();
    long occupied = countStatus(units, UnitStatus.OCCUPIED, true);
    long available = countStatus(units, UnitStatus.AVAILABLE, true);
    long reserved = countStatus(units, UnitStatus.RESERVED, true);
    long underMaintenance = countStatus(units, UnitStatus.UNDER_MAINTENANCE, false);
    long notLettable = units.stream().filter(unit -> !unit.isLeasable()).count();

    return new UnitDtos.UnitOccupancy(
        propertyId,
        total,
        leasable,
        occupied,
        available,
        reserved,
        underMaintenance,
        notLettable,
        PropertyService.occupancyPercent(occupied, leasable));
  }

  /**
   * The lease currently in force on a unit, as a read-only summary.
   *
   * <p>Resolved through {@code findRunningLeaseForUnit}, so "is this unit let" is a fact read from
   * the one-running-lease index rather than an inference over the lease list. The tenant's name is
   * attached only when the tenant profile is reachable; a missing name is left absent, never
   * invented.</p>
   */
  @Transactional(readOnly = true)
  public UnitDtos.UnitLeaseSummary leaseSummary(UUID unitId) {
    require(unitId);
    return leaseSummaryFor(unitId);
  }

  /* ====================================================================== */
  /* Writes                                                                 */
  /* ====================================================================== */

  /** Add a unit to the stock, minting its human-readable code. */
  @Transactional
  public UnitDtos.UnitDetail create(UnitDtos.CreateUnitRequest request) {
    User actor = accessScope.requireUser();

    Property property = requireProperty(request.propertyId());
    UnitType unitType =
        unitTypeRepository
            .findById(request.unitTypeId())
            .orElseThrow(() -> ApiException.notFound("That unit type does not exist"));
    Building building = resolveBuilding(property.getId(), request.buildingId());

    String unitNumber = request.unitNumber().trim();
    assertUnitNumberFree(property.getId(), unitNumber, null);

    boolean leasable = request.leasable() == null || request.leasable();
    UnitStatus status = resolveCreationStatus(request.status(), leasable);
    assertLeasableStatus(leasable, status);

    Unit unit = new Unit();
    unit.setUnitCode(businessNumbers.next(BusinessNumberService.UNIT));
    unit.setProperty(property);
    unit.setBuilding(building);
    unit.setUnitType(unitType);
    unit.setUnitNumber(unitNumber);
    unit.setFloorNumber(request.floorNumber() == null ? 0 : request.floorNumber());
    unit.setFloorLabel(request.floorLabel());
    unit.setBedrooms(orZero(request.bedrooms()));
    unit.setBathrooms(orZero(request.bathrooms()));
    unit.setBalconies(orZero(request.balconies()));
    unit.setCarpetAreaSqft(request.carpetAreaSqft());
    unit.setBuiltUpAreaSqft(request.builtUpAreaSqft());
    unit.setParkingSlots(orZero(request.parkingSlots()));
    unit.setFurnishing(parseFurnishing(request.furnishing(), UnitFurnishing.UNFURNISHED));
    unit.setFacing(parseFacing(request.facing()));
    unit.setStatus(status);
    unit.setLeasable(leasable);
    unit.setAskingRent(MoneyPolicy.money(request.askingRent()));
    unit.setAskingPrice(MoneyPolicy.money(request.askingPrice()));
    unit.setCreatedBy(actor.getId());
    unit.setUpdatedBy(actor.getId());

    Unit saved = unitRepository.save(unit);

    audit.record(
        actor.getId(),
        actor.getFullName(),
        AuditActions.UNIT,
        AuditActions.CREATE,
        "Added unit " + saved.getUnitCode() + " (" + saved.getUnitNumber() + ")",
        "UNIT",
        saved.getId(),
        saved.getUnitCode());

    return detail(saved);
  }

  /** Change a unit's details. Only the fields supplied are applied; status moves elsewhere. */
  @Transactional
  public UnitDtos.UnitDetail update(UUID unitId, UnitDtos.UpdateUnitRequest request) {
    User actor = accessScope.requireUser();
    Unit unit = require(unitId);

    if (request.buildingId() != null && !isOfBuilding(unit, request.buildingId())) {
      unit.setBuilding(resolveBuilding(unit.getProperty().getId(), request.buildingId()));
    }
    if (request.unitTypeId() != null) {
      unit.setUnitType(
          unitTypeRepository
              .findById(request.unitTypeId())
              .orElseThrow(() -> ApiException.notFound("That unit type does not exist")));
    }
    if (request.unitNumber() != null) {
      String number = request.unitNumber().trim();
      assertUnitNumberFree(unit.getProperty().getId(), number, unit.getId());
      unit.setUnitNumber(number);
    }
    if (request.floorNumber() != null) unit.setFloorNumber(request.floorNumber());
    if (request.floorLabel() != null) unit.setFloorLabel(request.floorLabel());
    if (request.bedrooms() != null) unit.setBedrooms(request.bedrooms());
    if (request.bathrooms() != null) unit.setBathrooms(request.bathrooms());
    if (request.balconies() != null) unit.setBalconies(request.balconies());
    if (request.carpetAreaSqft() != null) unit.setCarpetAreaSqft(request.carpetAreaSqft());
    if (request.builtUpAreaSqft() != null) unit.setBuiltUpAreaSqft(request.builtUpAreaSqft());
    if (request.parkingSlots() != null) unit.setParkingSlots(request.parkingSlots());
    if (request.furnishing() != null) {
      unit.setFurnishing(parseFurnishing(request.furnishing(), unit.getFurnishing()));
    }
    if (request.facing() != null) unit.setFacing(parseFacing(request.facing()));
    if (request.askingRent() != null) unit.setAskingRent(MoneyPolicy.money(request.askingRent()));
    if (request.askingPrice() != null) {
      unit.setAskingPrice(MoneyPolicy.money(request.askingPrice()));
    }
    if (request.leasable() != null) {
      // Turning a unit non-lettable while it sits in a lettable state is refused; see the class note.
      assertLeasableStatus(request.leasable(), unit.getStatus());
      unit.setLeasable(request.leasable());
    }
    unit.setUpdatedBy(actor.getId());

    Unit saved = unitRepository.save(unit);

    audit.record(
        actor.getId(),
        actor.getFullName(),
        AuditActions.UNIT,
        AuditActions.UPDATE,
        "Changed unit " + saved.getUnitCode(),
        "UNIT",
        saved.getId(),
        saved.getUnitCode());

    return detail(saved);
  }

  /**
   * Archive a unit - soft delete, once.
   *
   * <p>Refused (409) while the unit carries a running lease: archiving the unit out from under a
   * live tenancy would strand the lease and the rent it produces.</p>
   */
  @Transactional
  public UnitDtos.UnitDetail archive(UUID unitId) {
    User actor = accessScope.requireUser();

    Unit unit =
        unitRepository
            .findById(unitId)
            .orElseThrow(() -> ApiException.notFound(ErrorMessages.friendly("UNIT_NOT_FOUND")));

    if (unit.isDeleted()) {
      throw ApiException.rule("UNIT_ALREADY_ARCHIVED", "This unit has already been archived");
    }
    if (leaseRepository.findRunningLeaseForUnit(unitId).isPresent()) {
      throw ApiException.rule(
          "UNIT_ALREADY_LET",
          "This unit has a running lease and cannot be archived while the tenancy is live");
    }

    unit.setDeletedAt(LocalDateTime.now());
    unit.setUpdatedBy(actor.getId());
    Unit saved = unitRepository.save(unit);

    audit.record(
        actor.getId(),
        actor.getFullName(),
        AuditActions.UNIT,
        AuditActions.DELETE,
        "Archived unit " + saved.getUnitCode() + " (" + saved.getUnitNumber() + ")",
        "UNIT",
        saved.getId(),
        saved.getUnitCode());

    return detail(saved);
  }

  /**
   * Move a unit between lifecycle states, recording the transition.
   *
   * <p>Marking a unit as let (a letting state) is refused when the unit is not leasable
   * ({@code UNIT_NOT_LEASABLE}) and when it already carries a running lease
   * ({@code UNIT_ALREADY_LET}, 409). A no-op move is not recorded, and a move out of a sold unit's
   * terminal state is refused.</p>
   */
  @Transactional
  public UnitDtos.UnitDetail changeStatus(UUID unitId, UnitDtos.ChangeStatusRequest request) {
    User actor = accessScope.requireUser();
    Unit unit = require(unitId);

    UnitStatus from = unit.getStatus();
    UnitStatus target = parseStatus(request.status(), null);
    if (from == target) {
      return detail(unit);
    }

    if (target.isLettingState()) {
      if (!unit.isLeasable()) {
        throw ApiException.rule(
            "UNIT_NOT_LEASABLE", ErrorMessages.friendly("UNIT_NOT_LEASABLE"));
      }
      if (leaseRepository.findRunningLeaseForUnit(unitId).isPresent()) {
        throw ApiException.rule("UNIT_ALREADY_LET", ErrorMessages.friendly("UNIT_ALREADY_LET"));
      }
    }
    assertLeasableStatus(unit.isLeasable(), target);
    if (from == UnitStatus.SOLD) {
      throw ApiException.rule(
          "UNIT_TRANSITION_NOT_ALLOWED",
          "A sold unit has reached a terminal state and cannot move to " + target);
    }

    unit.setStatus(target);
    unit.setUpdatedBy(actor.getId());
    Unit saved = unitRepository.save(unit);

    recordStatusHistory(saved, from, target, request.reason(), actor.getId());

    audit.record(
        actor.getId(),
        actor.getFullName(),
        AuditActions.UNIT,
        AuditActions.STATUS_CHANGE,
        "Unit " + saved.getUnitCode() + " " + from + " -> " + target,
        "UNIT",
        saved.getId(),
        saved.getUnitCode());

    return detail(saved);
  }

  /* ====================================================================== */
  /* Internals                                                              */
  /* ====================================================================== */

  private UnitDtos.UnitDetail detail(Unit unit) {
    return new UnitDtos.UnitDetail(
        unit.getId(),
        unit.getUnitCode(),
        unit.getUnitNumber(),
        unit.getProperty() == null ? null : unit.getProperty().getId(),
        unit.getProperty() == null ? null : unit.getProperty().getName(),
        unit.getBuilding() == null ? null : unit.getBuilding().getId(),
        unit.getBuilding() == null ? null : unit.getBuilding().getBuildingCode(),
        unit.getBuilding() == null ? null : unit.getBuilding().getName(),
        unit.getUnitType() == null ? null : unit.getUnitType().getId(),
        unit.getUnitType() == null ? null : unit.getUnitType().getCode(),
        unit.getUnitType() == null ? null : unit.getUnitType().getName(),
        unit.getFloorNumber(),
        unit.getFloorLabel(),
        unit.getBedrooms(),
        unit.getBathrooms(),
        unit.getBalconies(),
        unit.getParkingSlots(),
        unit.getCarpetAreaSqft(),
        unit.getBuiltUpAreaSqft(),
        unit.getFurnishing() == null ? null : unit.getFurnishing().name(),
        unit.getFacing() == null ? null : unit.getFacing().name(),
        unit.getStatus() == null ? null : unit.getStatus().name(),
        unit.isLeasable(),
        unit.getAskingRent(),
        unit.getAskingPrice(),
        leaseSummaryFor(unit.getId()),
        unit.getCreatedAt(),
        unit.getUpdatedAt());
  }

  private UnitDtos.UnitLeaseSummary leaseSummaryFor(UUID unitId) {
    Lease lease = leaseRepository.findRunningLeaseForUnit(unitId).orElse(null);
    if (lease == null) {
      return new UnitDtos.UnitLeaseSummary(
          unitId, false, null, null, null, null, null, null, null, null, null);
    }
    String tenantName =
        tenantRepository
            .findByIdAndDeletedAtIsNull(lease.getTenantId())
            .map(Tenant::getDisplayName)
            .orElse(null);

    return new UnitDtos.UnitLeaseSummary(
        unitId,
        true,
        lease.getId(),
        lease.getLeaseCode(),
        lease.getStatus() == null ? null : lease.getStatus().name(),
        lease.getStartDate(),
        lease.getEndDate(),
        lease.getRentAmount(),
        lease.getRentFrequency() == null ? null : lease.getRentFrequency().name(),
        lease.getTenantId(),
        tenantName);
  }

  private List<UnitDtos.FloorGroup> floorGroups(List<Unit> units) {
    Map<Integer, List<Unit>> byFloor = new LinkedHashMap<>();
    for (Unit unit : units) {
      byFloor.computeIfAbsent(unit.getFloorNumber(), ignored -> new ArrayList<>()).add(unit);
    }
    return byFloor.entrySet().stream()
        .sorted(Map.Entry.comparingByKey())
        .map(
            entry -> {
              List<Unit> floorUnits =
                  entry.getValue().stream()
                      .sorted(
                          Comparator.comparing(
                              Unit::getUnitNumber,
                              Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)))
                      .toList();
              String label =
                  floorUnits.stream()
                      .map(Unit::getFloorLabel)
                      .filter(Objects::nonNull)
                      .findFirst()
                      .orElse(null);
              return new UnitDtos.FloorGroup(
                  entry.getKey(), label, floorUnits.stream().map(UnitService::card).toList());
            })
        .toList();
  }

  private static UnitDtos.UnitCard card(Unit unit) {
    return new UnitDtos.UnitCard(
        unit.getId(),
        unit.getUnitCode(),
        unit.getUnitNumber(),
        unit.getBuilding() == null ? null : unit.getBuilding().getId(),
        unit.getUnitType() == null ? null : unit.getUnitType().getCode(),
        unit.getUnitType() == null ? null : unit.getUnitType().getName(),
        unit.getFloorNumber(),
        unit.getFloorLabel(),
        unit.getBedrooms(),
        unit.getStatus() == null ? null : unit.getStatus().name(),
        unit.isLeasable(),
        unit.getStatus() == UnitStatus.OCCUPIED,
        unit.getAskingRent());
  }

  private void recordStatusHistory(
      Unit unit, UnitStatus from, UnitStatus to, String reason, UUID actorId) {
    UnitStatusHistory entry = new UnitStatusHistory();
    entry.setUnit(unit);
    entry.setFromStatus(from);
    entry.setToStatus(to);
    entry.setReason(reason);
    entry.setChangedBy(actorId);
    entry.setChangedAt(LocalDateTime.now());
    statusHistoryRepository.save(entry);
  }

  private Property requireProperty(UUID propertyId) {
    return propertyRepository
        .findByIdAndDeletedAtIsNull(propertyId)
        .orElseThrow(() -> ApiException.notFound(ErrorMessages.friendly("PROPERTY_NOT_FOUND")));
  }

  private Building resolveBuilding(UUID propertyId, UUID buildingId) {
    if (buildingId == null) {
      return null;
    }
    Building building =
        buildingRepository
            .findByIdAndDeletedAtIsNull(buildingId)
            .orElseThrow(() -> ApiException.notFound(ErrorMessages.friendly("BUILDING_NOT_FOUND")));
    if (building.getProperty() == null || !propertyId.equals(building.getProperty().getId())) {
      throw ApiException.badRequest("That building does not belong to this property");
    }
    return building;
  }

  private void assertUnitNumberFree(UUID propertyId, String unitNumber, UUID currentUnitId) {
    boolean taken =
        unitRepository.existsByPropertyIdAndUnitNumberIgnoreCaseAndDeletedAtIsNull(
            propertyId, unitNumber);
    if (!taken) {
      return;
    }
    // On an edit, a unit keeping its own number is not a clash.
    if (currentUnitId != null) {
      Unit existing = require(currentUnitId);
      if (existing.getUnitNumber() != null
          && existing.getUnitNumber().equalsIgnoreCase(unitNumber)) {
        return;
      }
    }
    throw ApiException.duplicate(
        "DUPLICATE_UNIT_NUMBER", "This property already has a unit numbered " + unitNumber);
  }

  private static boolean isOfType(Unit unit, UUID unitTypeId) {
    return unit.getUnitType() != null && unitTypeId.equals(unit.getUnitType().getId());
  }

  private static boolean belongsToBuilding(Unit unit, UUID buildingId) {
    return unit.getBuilding() != null && buildingId.equals(unit.getBuilding().getId());
  }

  private static boolean isOfBuilding(Unit unit, UUID buildingId) {
    return unit.getBuilding() != null && buildingId.equals(unit.getBuilding().getId());
  }

  private static boolean matchesTerm(Unit unit, String term) {
    String needle = term.toLowerCase(Locale.ROOT);
    return contains(unit.getUnitNumber(), needle) || contains(unit.getUnitCode(), needle);
  }

  private static boolean contains(String value, String needle) {
    return value != null && value.toLowerCase(Locale.ROOT).contains(needle);
  }

  private static long countStatus(List<Unit> units, UnitStatus status, boolean leasableOnly) {
    return units.stream()
        .filter(unit -> unit.getStatus() == status)
        .filter(unit -> !leasableOnly || unit.isLeasable())
        .count();
  }

  private static UnitStatus resolveCreationStatus(String status, boolean leasable) {
    if (status != null && !status.isBlank()) {
      return parseStatus(status, null);
    }
    return leasable ? UnitStatus.AVAILABLE : UnitStatus.NOT_LETTABLE;
  }

  /**
   * A non-lettable unit may not sit in a lettable state.
   *
   * <p>The database carries {@code ck_units_not_lettable_status} for this; the service raises the
   * named {@code UNIT_NOT_LEASABLE} for a letting state (the case the desk actually hits) and a 400
   * for the sale states the constraint also forbids, rather than letting either reach the caller as
   * an opaque integrity error.</p>
   */
  private static void assertLeasableStatus(boolean leasable, UnitStatus status) {
    if (leasable || status == null) {
      return;
    }
    if (status.isLettingState()) {
      throw ApiException.rule("UNIT_NOT_LEASABLE", ErrorMessages.friendly("UNIT_NOT_LEASABLE"));
    }
    if (status != UnitStatus.NOT_LETTABLE && status != UnitStatus.UNDER_MAINTENANCE) {
      throw ApiException.badRequest(
          "A unit that is not available to let can only be NOT_LETTABLE or UNDER_MAINTENANCE");
    }
  }

  /**
   * A status supplied as a list filter, or null meaning "every status".
   *
   * <p>Deliberately separate from {@link #parseStatus}. The unit register is read unfiltered by
   * default, so an absent status means "no filter" and not a client mistake. Reusing the
   * required-value parser here made {@code GET /api/v1/units} answer 400 to its own default request.
   * A value that <em>is</em> supplied still goes through {@link #parseStatus}, so an unrecognised
   * status keeps its named refusal.</p>
   */
  private static UnitStatus parseOptionalStatus(String value) {
    return value == null || value.isBlank() ? null : parseStatus(value, null);
  }

  private static UnitStatus parseStatus(String value, UnitStatus fallback) {
    if (value == null || value.isBlank()) {
      if (fallback == null) {
        throw ApiException.badRequest("A unit status is required");
      }
      return fallback;
    }
    try {
      return UnitStatus.valueOf(value.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException ex) {
      throw ApiException.badRequest("'" + value + "' is not a unit status");
    }
  }

  private static UnitFurnishing parseFurnishing(String value, UnitFurnishing fallback) {
    if (value == null || value.isBlank()) {
      return fallback == null ? UnitFurnishing.UNFURNISHED : fallback;
    }
    try {
      return UnitFurnishing.valueOf(value.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException ex) {
      throw ApiException.badRequest("'" + value + "' is not a furnishing state");
    }
  }

  private static UnitFacing parseFacing(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    try {
      return UnitFacing.valueOf(value.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException ex) {
      throw ApiException.badRequest("'" + value + "' is not a compass direction");
    }
  }

  private static int orZero(Integer value) {
    return value == null ? 0 : value;
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }

  // --------------------------------------------------------------------------
  // Paging and sorting, applied here because the combined repository query does
  // not exist. No repository query carries a literal order by.
  // --------------------------------------------------------------------------

  private static List<Unit> sortUnits(List<Unit> items, Sort sort) {
    if (sort == null || sort.isUnsorted()) {
      return items;
    }
    Comparator<Unit> comparator = null;
    for (Sort.Order order : sort) {
      Comparator<Unit> next = unitComparator(order.getProperty());
      if (order.isDescending()) {
        next = next.reversed();
      }
      comparator = comparator == null ? next : comparator.thenComparing(next);
    }
    List<Unit> sorted = new ArrayList<>(items);
    if (comparator != null) {
      sorted.sort(comparator);
    }
    return sorted;
  }

  private static Comparator<Unit> unitComparator(String property) {
    Comparator<Unit> byNumber =
        Comparator.comparing(
            Unit::getUnitNumber, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER));
    return switch (property == null ? "" : property) {
      case "unitCode" ->
          Comparator.comparing(
              Unit::getUnitCode, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER));
      case "floorNumber" -> Comparator.comparing(Unit::getFloorNumber);
      case "bedrooms" -> Comparator.comparing(Unit::getBedrooms);
      case "status" ->
          Comparator.comparing(current -> current.getStatus() == null ? "" : current.getStatus().name());
      case "askingRent" ->
          Comparator.comparing(
              Unit::getAskingRent, Comparator.nullsLast(Comparator.<BigDecimal>naturalOrder()));
      case "createdAt" ->
          Comparator.comparing(
              Unit::getCreatedAt,
              Comparator.nullsLast(Comparator.<LocalDateTime>naturalOrder()));
      default -> byNumber;
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
