package com.meridian.realestate.controller;

import com.meridian.realestate.dto.ApiResponse;
import com.meridian.realestate.dto.OwnerDtos;
import com.meridian.realestate.service.OwnerService;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * A property's ownership shares - who holds what, in what proportion, for how long.
 *
 * <p><b>Why this controller exists.</b> A holding is read by the property it is a share of, so it
 * hangs off {@code /api/v1/properties/{propertyId}/ownerships}. It is a separate controller from the
 * property register because the rule it enforces - the shares in force must total exactly 100% - is
 * a rule about a property's holdings as a set, not about the asset itself, and because the writes
 * belong to the ownership slice rather than to the register (which only reports the shares).</p>
 *
 * <p><b>The rules it protects.</b> All of them are decided in {@link OwnerService}: adding or
 * changing a share must not push the open total above 100 ({@code OWNERSHIP_EXCEEDS_ONE_HUNDRED},
 * 409) and must not create a second holding for the same owner in an overlapping period
 * ({@code OWNERSHIP_DUPLICATE_OWNER}, 409); {@code /validate} reports a total that is not the whole
 * as {@code OWNERSHIP_NOT_ONE_HUNDRED} (409) with the total and the shortfall. Nothing here builds
 * an error response.</p>
 *
 * <p>A holding is dated rather than deleted: {@code DELETE} closes it by stamping its end date, so a
 * past ownership stays reportable. {@code PATCH} may also close one by supplying {@code effectiveTo}.</p>
 */
@RestController
@RequestMapping("/api/v1/properties/{propertyId}/ownerships")
public class PropertyOwnershipController {

  private final OwnerService ownerService;

  public PropertyOwnershipController(OwnerService ownerService) {
    this.ownerService = ownerService;
  }

  /**
   * The shares in force on a property, or the shares in force on a given date.
   *
   * <p>With no {@code asOf} the answer is today's open holdings; with one it is the holdings whose
   * period covers that date, so "who owned this when the invoice was raised" is a first-class
   * question rather than an inference.</p>
   */
  @GetMapping
  @PreAuthorize("hasAuthority('OWNER_VIEW')")
  public ApiResponse<List<OwnerDtos.OwnershipShare>> list(
      @PathVariable UUID propertyId, @RequestParam(required = false) LocalDate asOf) {
    return ApiResponse.ok(ownerService.ownerships(propertyId, asOf));
  }

  /** Confirm that the property's shares in force total exactly 100%. */
  @GetMapping("/validate")
  @PreAuthorize("hasAuthority('OWNER_VIEW')")
  public ApiResponse<OwnerDtos.OwnershipValidation> validate(@PathVariable UUID propertyId) {
    return ApiResponse.ok(ownerService.validateTotal(propertyId));
  }

  @PostMapping
  @PreAuthorize("hasAuthority('OWNER_UPDATE')")
  public ApiResponse<OwnerDtos.OwnershipShare> create(
      @PathVariable UUID propertyId,
      @Valid @RequestBody OwnerDtos.CreateOwnershipRequest request) {
    return ApiResponse.ok(ownerService.addOwnership(propertyId, request), "Holding recorded");
  }

  /** Change a holding, including closing it by supplying its end date. */
  @PatchMapping("/{ownershipId}")
  @PreAuthorize("hasAuthority('OWNER_UPDATE')")
  public ApiResponse<OwnerDtos.OwnershipShare> update(
      @PathVariable UUID propertyId,
      @PathVariable UUID ownershipId,
      @Valid @RequestBody OwnerDtos.UpdateOwnershipRequest request) {
    return ApiResponse.ok(ownerService.updateOwnership(propertyId, ownershipId, request));
  }

  /** Close a holding: the soft delete, which stamps its end date rather than removing the row. */
  @DeleteMapping("/{ownershipId}")
  @PreAuthorize("hasAuthority('OWNER_UPDATE')")
  public ApiResponse<OwnerDtos.OwnershipShare> close(
      @PathVariable UUID propertyId, @PathVariable UUID ownershipId) {
    return ApiResponse.ok(ownerService.closeOwnership(propertyId, ownershipId), "Holding closed");
  }
}
