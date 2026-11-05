package com.meridian.realestate.service;

import com.meridian.realestate.domain.Building;
import com.meridian.realestate.domain.Property;
import com.meridian.realestate.domain.User;
import com.meridian.realestate.dto.BuildingDtos;
import com.meridian.realestate.dto.ErrorMessages;
import com.meridian.realestate.exception.ApiException;
import com.meridian.realestate.repository.BuildingRepository;
import com.meridian.realestate.repository.PropertyRepository;
import com.meridian.realestate.repository.UnitRepository;
import com.meridian.realestate.security.AccessScope;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The buildings (blocks) within the properties.
 *
 * <p><b>Why this class exists.</b> A block is a small record, but it is the grouping the unit board
 * is read by and the thing a building code is unique within, so creating, renaming, archiving and
 * counting the units of one belongs together. Several properties in the portfolio are multi-block,
 * and a single-block property has exactly one building, which keeps every unit's location path
 * uniform - a unit always sits at property / building / floor.</p>
 *
 * <p><b>The rules it protects.</b></p>
 * <ul>
 *   <li><b>A building code is unique within its property, not globally.</b> "Block A" exists in more
 *       than one property, so the service enforces the composite rule the schema declares
 *       ({@code uq_buildings_property_code}) rather than a global one that would wrongly forbid a
 *       second property having a Block A.</li>
 *   <li><b>A building with units cannot be archived</b> (409, {@code BUILDING_HAS_UNITS}). Archiving
 *       a block out from under the units that sit in it would leave them pointing at something the
 *       desk can no longer name, so the refusal names the count.</li>
 *   <li><b>Archiving is soft.</b> A block is archived ({@code deletedAt}), never removed, because
 *       the units and their history must stay coherent.</li>
 * </ul>
 *
 * <p>As with the property register, the building list is firm-wide and protected by permission
 * alone; {@link AccessScope} resolves the acting account for a change and its audit entry. Paging is
 * applied here rather than by a combined repository query, and no query carries a literal
 * {@code order by}.</p>
 */
@Service
public class BuildingService {

  private final BuildingRepository buildingRepository;
  private final PropertyRepository propertyRepository;
  private final UnitRepository unitRepository;
  private final AuditRecordingService audit;
  private final AccessScope accessScope;

  public BuildingService(
      BuildingRepository buildingRepository,
      PropertyRepository propertyRepository,
      UnitRepository unitRepository,
      AuditRecordingService audit,
      AccessScope accessScope) {
    this.buildingRepository = buildingRepository;
    this.propertyRepository = propertyRepository;
    this.unitRepository = unitRepository;
    this.audit = audit;
    this.accessScope = accessScope;
  }

  /* ====================================================================== */
  /* Reads                                                                  */
  /* ====================================================================== */

  /** The live building behind an id, or a 404. */
  @Transactional(readOnly = true)
  public Building require(UUID buildingId) {
    return buildingRepository
        .findByIdAndDeletedAtIsNull(buildingId)
        .orElseThrow(() -> ApiException.notFound(ErrorMessages.friendly("BUILDING_NOT_FOUND")));
  }

  /**
   * The buildings, optionally narrowed to one property, sorted and paged.
   *
   * <p>{@code BuildingRepository} offers a paged plain read and an ordered by-property read but no
   * single filtered paged query; the filtering, sorting and slicing are therefore applied here so
   * the page the caller asked for is the page they get.</p>
   */
  @Transactional(readOnly = true)
  public Page<BuildingDtos.BuildingSummary> list(UUID propertyId, Pageable pageable) {
    List<Building> candidates =
        propertyId == null
            ? buildingRepository.findAllByDeletedAtIsNull()
            : buildingRepository.findAllByPropertyIdAndDeletedAtIsNull(propertyId);

    return pageOf(
        sortBuildings(candidates, pageable.getSort()),
        pageable,
        building -> BuildingDtos.BuildingSummary.from(building, unitCount(building.getId())));
  }

  /** One building's full record. */
  @Transactional(readOnly = true)
  public BuildingDtos.BuildingDetail detail(UUID buildingId) {
    return detail(require(buildingId));
  }

  /* ====================================================================== */
  /* Writes                                                                 */
  /* ====================================================================== */

  /** Add a block to a property. */
  @Transactional
  public BuildingDtos.BuildingDetail create(BuildingDtos.CreateBuildingRequest request) {
    User actor = accessScope.requireUser();

    Property property =
        propertyRepository
            .findByIdAndDeletedAtIsNull(request.propertyId())
            .orElseThrow(() -> ApiException.notFound(ErrorMessages.friendly("PROPERTY_NOT_FOUND")));

    String code = request.buildingCode().trim();
    if (buildingRepository.existsByPropertyIdAndBuildingCodeIgnoreCaseAndDeletedAtIsNull(
        property.getId(), code)) {
      throw ApiException.duplicate(
          "DUPLICATE_BUILDING_CODE",
          "This property already has a block with the code " + code);
    }

    Building building = new Building();
    building.setProperty(property);
    building.setBuildingCode(code);
    building.setName(request.name().trim());
    building.setFloors(request.floors());
    building.setYearBuilt(request.yearBuilt());
    building.setDescription(request.description());
    building.setCreatedBy(actor.getId());
    building.setUpdatedBy(actor.getId());

    Building saved = buildingRepository.save(building);

    audit.record(
        actor.getId(),
        actor.getFullName(),
        AuditActions.PROPERTY,
        AuditActions.CREATE,
        "Added building " + saved.getBuildingCode() + " to " + property.getPropertyCode(),
        "BUILDING",
        saved.getId(),
        saved.getBuildingCode());

    return detail(saved);
  }

  /** Change a block. Only the fields supplied are applied. */
  @Transactional
  public BuildingDtos.BuildingDetail update(
      UUID buildingId, BuildingDtos.UpdateBuildingRequest request) {
    User actor = accessScope.requireUser();
    Building building = require(buildingId);

    if (request.buildingCode() != null) {
      String code = request.buildingCode().trim();
      if (!code.equalsIgnoreCase(building.getBuildingCode())
          && buildingRepository.existsByPropertyIdAndBuildingCodeIgnoreCaseAndDeletedAtIsNull(
              building.getProperty().getId(), code)) {
        throw ApiException.duplicate(
            "DUPLICATE_BUILDING_CODE",
            "This property already has a block with the code " + code);
      }
      building.setBuildingCode(code);
    }
    if (request.name() != null) building.setName(request.name().trim());
    if (request.floors() != null) building.setFloors(request.floors());
    if (request.yearBuilt() != null) building.setYearBuilt(request.yearBuilt());
    if (request.description() != null) building.setDescription(request.description());
    building.setUpdatedBy(actor.getId());

    Building saved = buildingRepository.save(building);

    audit.record(
        actor.getId(),
        actor.getFullName(),
        AuditActions.PROPERTY,
        AuditActions.UPDATE,
        "Changed building " + saved.getBuildingCode(),
        "BUILDING",
        saved.getId(),
        saved.getBuildingCode());

    return detail(saved);
  }

  /**
   * Archive a block - soft delete, refused while units still sit in it.
   *
   * <p>The unit count is the guard, and the message names it: a caller told only "no" would have to
   * go looking for which units blocked the change.</p>
   */
  @Transactional
  public BuildingDtos.BuildingDetail archive(UUID buildingId) {
    User actor = accessScope.requireUser();
    Building building = require(buildingId);

    long units = unitCount(buildingId);
    if (units > 0) {
      throw ApiException.rule(
          "BUILDING_HAS_UNITS",
          building.getName()
              + " still has "
              + units
              + " unit(s). Move or archive those units first.");
    }

    building.setDeletedAt(LocalDateTime.now());
    building.setUpdatedBy(actor.getId());
    Building saved = buildingRepository.save(building);

    audit.record(
        actor.getId(),
        actor.getFullName(),
        AuditActions.PROPERTY,
        AuditActions.DELETE,
        "Archived building " + saved.getBuildingCode() + " (" + saved.getName() + ")",
        "BUILDING",
        saved.getId(),
        saved.getBuildingCode());

    return detail(saved);
  }

  /* ====================================================================== */
  /* Internals                                                              */
  /* ====================================================================== */

  private BuildingDtos.BuildingDetail detail(Building building) {
    return new BuildingDtos.BuildingDetail(
        building.getId(),
        building.getProperty() == null ? null : building.getProperty().getId(),
        building.getProperty() == null ? null : building.getProperty().getName(),
        building.getBuildingCode(),
        building.getName(),
        building.getFloors(),
        building.getYearBuilt(),
        building.getDescription(),
        unitCount(building.getId()),
        building.getCreatedAt(),
        building.getUpdatedAt());
  }

  private long unitCount(UUID buildingId) {
    return unitRepository.countByBuildingIdAndDeletedAtIsNull(buildingId);
  }

  private static List<Building> sortBuildings(List<Building> items, Sort sort) {
    if (sort == null || sort.isUnsorted()) {
      return items;
    }
    Comparator<Building> comparator = null;
    for (Sort.Order order : sort) {
      Comparator<Building> next = buildingComparator(order.getProperty());
      if (order.isDescending()) {
        next = next.reversed();
      }
      comparator = comparator == null ? next : comparator.thenComparing(next);
    }
    List<Building> sorted = new ArrayList<>(items);
    if (comparator != null) {
      sorted.sort(comparator);
    }
    return sorted;
  }

  private static Comparator<Building> buildingComparator(String property) {
    Comparator<Building> byName =
        Comparator.comparing(
            Building::getName, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER));
    return switch (property == null ? "" : property) {
      case "buildingCode" ->
          Comparator.comparing(
              Building::getBuildingCode, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER));
      case "floors" ->
          Comparator.comparing(
              Building::getFloors, Comparator.nullsLast(Comparator.<Integer>naturalOrder()));
      case "createdAt" ->
          Comparator.comparing(
              Building::getCreatedAt,
              Comparator.nullsLast(Comparator.<java.time.LocalDateTime>naturalOrder()));
      default -> byName;
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
