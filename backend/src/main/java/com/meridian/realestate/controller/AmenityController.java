package com.meridian.realestate.controller;

import com.meridian.realestate.dto.AmenityDtos;
import com.meridian.realestate.dto.ApiResponse;
import com.meridian.realestate.service.AmenityService;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The amenity reference list.
 *
 * <p><b>Why this controller exists.</b> The property form needs the full list of facilities a
 * property can offer, in the product's own order, so the desk can tick them. It is read-only: this
 * class exposes no create, change or archive, because an amenity is a fact about what the product
 * understands rather than a record the desk maintains.</p>
 *
 * <p>The read is guarded by {@code PROPERTY_VIEW}, the permission that lets a caller see the
 * property register these facilities describe. The grouped shape exists so the property page can lay
 * its sections out (utility, access, security, parking, logistics, lifestyle) without re-grouping the
 * list itself.</p>
 */
@RestController
@RequestMapping("/api/v1/amenities")
public class AmenityController {

  private final AmenityService amenityService;

  public AmenityController(AmenityService amenityService) {
    this.amenityService = amenityService;
  }

  /** Every facility in display order, or one category when {@code category} is given. */
  @GetMapping
  @PreAuthorize("hasAuthority('PROPERTY_VIEW')")
  public ApiResponse<List<AmenityDtos.AmenityView>> list(
      @RequestParam(required = false) String category) {
    return ApiResponse.ok(amenityService.byCategory(category));
  }

  /** Every facility grouped by category, in display order. */
  @GetMapping("/grouped")
  @PreAuthorize("hasAuthority('PROPERTY_VIEW')")
  public ApiResponse<List<AmenityDtos.AmenityGroup>> grouped() {
    return ApiResponse.ok(amenityService.grouped());
  }
}
