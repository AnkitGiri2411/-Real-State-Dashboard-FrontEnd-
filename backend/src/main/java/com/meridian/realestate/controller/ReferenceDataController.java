package com.meridian.realestate.controller;

import com.meridian.realestate.dto.ApiResponse;
import com.meridian.realestate.dto.ReferenceDtos;
import com.meridian.realestate.service.ReferenceDataService;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The property-type and unit-type reference lists.
 *
 * <p><b>Why this controller exists.</b> The property and unit forms need the categories the product
 * understands - Apartment, Villa, Land / Plot; Studio, 2 BHK, Warehouse Bay - in the product's own
 * order. Both are read-only: no create, change or archive, because a category is seeded by the
 * schema migration rather than maintained by the desk.</p>
 *
 * <p>{@code /property-types} is guarded by {@code PROPERTY_VIEW} and {@code /unit-types} by
 * {@code UNIT_VIEW}, the permissions that let a caller see the registers the categories classify.
 * The property-type read exposes its {@code leasable} flag so a form can reflect that stock of that
 * type is not normally let, rather than treating every category as lettable.</p>
 */
@RestController
public class ReferenceDataController {

  private final ReferenceDataService referenceDataService;

  public ReferenceDataController(ReferenceDataService referenceDataService) {
    this.referenceDataService = referenceDataService;
  }

  /** The property categories, in display order. */
  @GetMapping("/api/v1/property-types")
  @PreAuthorize("hasAuthority('PROPERTY_VIEW')")
  public ApiResponse<List<ReferenceDtos.PropertyTypeView>> propertyTypes() {
    return ApiResponse.ok(referenceDataService.propertyTypes());
  }

  /** The unit categories, in display order. */
  @GetMapping("/api/v1/unit-types")
  @PreAuthorize("hasAuthority('UNIT_VIEW')")
  public ApiResponse<List<ReferenceDtos.UnitTypeView>> unitTypes() {
    return ApiResponse.ok(referenceDataService.unitTypes());
  }
}
