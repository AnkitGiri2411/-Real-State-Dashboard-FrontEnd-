package com.meridian.realestate.controller;

import com.meridian.realestate.dto.ApiResponse;
import com.meridian.realestate.dto.OfficeDtos;
import com.meridian.realestate.dto.PageResponse;
import com.meridian.realestate.service.OfficeService;
import com.meridian.realestate.web.PageRequestFactory;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The office (branch) register.
 *
 * <p><b>Why this controller exists.</b> An office is a real place - agents sit in it, viewings are
 * booked from it, properties are reported under it - so it has its own endpoints rather than being a
 * string on a staff record. The office roster a manager reads is the agents of one office, which is
 * the agent register filtered by office.</p>
 *
 * <p><b>The rules it protects.</b> No business rule lives here. The single head office and the
 * duplicate office code are decided in {@link OfficeService} and raised as
 * {@link com.meridian.realestate.exception.ApiException} - a second head office as a 409 named
 * {@code OFFICE_HEAD_OFFICE_EXISTS}, a duplicate code as {@code DUPLICATE_OFFICE_CODE}.</p>
 *
 * <p><b>On the permission codes.</b> {@code V2} seeds no {@code OFFICE_*} permission - the product
 * defines none - and offices belong to the agents module in the schema ({@code V7} creates offices,
 * agent profiles and assignments together). The endpoints therefore use the agents module's
 * capability codes ({@code AGENT_VIEW}, {@code AGENT_CREATE}, {@code AGENT_UPDATE}) rather than
 * inventing one, and an archived branch is an {@code AGENT_UPDATE} over an existing record.</p>
 */
@RestController
@RequestMapping("/api/v1/offices")
public class OfficeController {

  private static final PageRequestFactory PAGES =
      PageRequestFactory.sortedBy("createdAt,desc")
          .allow("createdAt", "createdAt")
          .allow("name", "name")
          .allow("officeCode", "officeCode")
          .allow("city", "city")
          .allow("status", "status")
          .build();

  private final OfficeService officeService;

  public OfficeController(OfficeService officeService) {
    this.officeService = officeService;
  }

  /** The office register, filtered by status and city, with free-text search. */
  @GetMapping
  @PreAuthorize("hasAuthority('AGENT_VIEW')")
  public ApiResponse<PageResponse<OfficeDtos.OfficeSummary>> list(
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String city,
      @RequestParam(required = false) String search,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size,
      @RequestParam(required = false) String sort) {
    return ApiResponse.ok(
        PageResponse.from(
            officeService.list(status, city, search, PAGES.toPageable(page, size, sort))));
  }

  /** One office's full record, with the number of agents who sit in it. */
  @GetMapping("/{officeId}")
  @PreAuthorize("hasAuthority('AGENT_VIEW')")
  public ApiResponse<OfficeDtos.OfficeDetail> detail(@PathVariable UUID officeId) {
    return ApiResponse.ok(officeService.detail(officeId));
  }

  @PostMapping
  @PreAuthorize("hasAuthority('AGENT_CREATE')")
  public ApiResponse<OfficeDtos.OfficeDetail> create(
      @Valid @RequestBody OfficeDtos.CreateOfficeRequest request) {
    return ApiResponse.ok(officeService.create(request), "Office added");
  }

  @PutMapping("/{officeId}")
  @PreAuthorize("hasAuthority('AGENT_UPDATE')")
  public ApiResponse<OfficeDtos.OfficeDetail> update(
      @PathVariable UUID officeId, @Valid @RequestBody OfficeDtos.UpdateOfficeRequest request) {
    return ApiResponse.ok(officeService.update(officeId, request));
  }

  /** Archive a branch: a soft delete. */
  @PostMapping("/{officeId}/archive")
  @PreAuthorize("hasAuthority('AGENT_UPDATE')")
  public ApiResponse<OfficeDtos.OfficeDetail> archive(@PathVariable UUID officeId) {
    return ApiResponse.ok(officeService.archive(officeId), "Office archived");
  }
}
