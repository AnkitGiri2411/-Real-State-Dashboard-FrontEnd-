package com.meridian.realestate.service;

import com.meridian.realestate.dto.ReferenceDtos;
import com.meridian.realestate.repository.PropertyTypeRepository;
import com.meridian.realestate.repository.UnitTypeRepository;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The property-type and unit-type reference lists.
 *
 * <p><b>Why this class exists.</b> Categories - Apartment, Villa, Land / Plot, and Studio, 2 BHK,
 * Warehouse Bay - are seeded by the schema migration because every installation needs the same
 * list, and are never created through the API. The two lists live in one service because they answer
 * one question - "what may I classify stock as" - and because they are read together by every form
 * that builds a property or a unit.</p>
 *
 * <p><b>The rule it protects.</b> A property type's {@code leasable} flag is a real business fact,
 * not a label: stock whose type is not normally let (a plot of land) is excluded from occupancy and
 * yield at the source. The read exposes the flag so a form can reflect it, rather than letting a
 * screen treat every category as lettable. Nothing here writes: a category is a fact about what the
 * product understands, not a record a desk changes.</p>
 */
@Service
public class ReferenceDataService {

  private final PropertyTypeRepository propertyTypeRepository;
  private final UnitTypeRepository unitTypeRepository;

  public ReferenceDataService(
      PropertyTypeRepository propertyTypeRepository, UnitTypeRepository unitTypeRepository) {
    this.propertyTypeRepository = propertyTypeRepository;
    this.unitTypeRepository = unitTypeRepository;
  }

  /** The property categories, in display order. */
  @Transactional(readOnly = true)
  public List<ReferenceDtos.PropertyTypeView> propertyTypes() {
    return propertyTypeRepository.findAllByOrderByDisplayOrderAsc().stream()
        .map(ReferenceDtos.PropertyTypeView::from)
        .toList();
  }

  /** The unit categories, in display order. */
  @Transactional(readOnly = true)
  public List<ReferenceDtos.UnitTypeView> unitTypes() {
    return unitTypeRepository.findAllByOrderByDisplayOrderAsc().stream()
        .map(ReferenceDtos.UnitTypeView::from)
        .toList();
  }
}
