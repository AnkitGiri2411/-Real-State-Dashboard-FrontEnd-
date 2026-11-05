package com.meridian.realestate.controller;

import com.meridian.realestate.dto.ApiResponse;
import com.meridian.realestate.dto.PropertyMediaDtos;
import com.meridian.realestate.service.PropertyMediaService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Property media as metadata.
 *
 * <p><b>Why this controller exists.</b> Media is registered against a property but is addressed in
 * its own right (a row is changed or detached by its own id), so it spans two base paths: listing
 * and creating under the property, and reading, changing and detaching under the media row.</p>
 *
 * <p><b>The rule it protects - never invent an image URL.</b> No endpoint here returns or creates a
 * generated image address. {@code url} is only ever what a caller supplied; a property with no media
 * simply has no media, and the interface draws its generated plot mark rather than a photograph the
 * product made up.</p>
 *
 * <p>The write endpoints are guarded by {@code PROPERTY_UPDATE} and the detach by
 * {@code PROPERTY_DELETE}, because media metadata is part of the property record rather than a
 * module of its own.</p>
 */
@RestController
public class PropertyMediaController {

  private final PropertyMediaService mediaService;

  public PropertyMediaController(PropertyMediaService mediaService) {
    this.mediaService = mediaService;
  }

  /** A property's media metadata. */
  @GetMapping("/api/v1/properties/{propertyId}/media")
  @PreAuthorize("hasAuthority('PROPERTY_VIEW')")
  public ApiResponse<List<PropertyMediaDtos.PropertyMediaView>> list(
      @PathVariable UUID propertyId) {
    return ApiResponse.ok(mediaService.list(propertyId));
  }

  /** The media row currently marked as the property's representative, if any. */
  @GetMapping("/api/v1/properties/{propertyId}/media/primary")
  @PreAuthorize("hasAuthority('PROPERTY_VIEW')")
  public ApiResponse<List<PropertyMediaDtos.PropertyMediaView>> primary(
      @PathVariable UUID propertyId) {
    return ApiResponse.ok(mediaService.primary(propertyId));
  }

  /** Register a piece of media metadata against a property. */
  @PostMapping("/api/v1/properties/{propertyId}/media")
  @PreAuthorize("hasAuthority('PROPERTY_UPDATE')")
  public ApiResponse<PropertyMediaDtos.PropertyMediaView> create(
      @PathVariable UUID propertyId,
      @Valid @RequestBody PropertyMediaDtos.CreatePropertyMediaRequest request) {
    return ApiResponse.ok(mediaService.create(propertyId, request), "Media registered");
  }

  @GetMapping("/api/v1/property-media/{mediaId}")
  @PreAuthorize("hasAuthority('PROPERTY_VIEW')")
  public ApiResponse<PropertyMediaDtos.PropertyMediaView> detail(@PathVariable UUID mediaId) {
    return ApiResponse.ok(mediaService.detail(mediaId));
  }

  @PutMapping("/api/v1/property-media/{mediaId}")
  @PreAuthorize("hasAuthority('PROPERTY_UPDATE')")
  public ApiResponse<PropertyMediaDtos.PropertyMediaView> update(
      @PathVariable UUID mediaId,
      @Valid @RequestBody PropertyMediaDtos.UpdatePropertyMediaRequest request) {
    return ApiResponse.ok(mediaService.update(mediaId, request));
  }

  /** Detach a media row: a soft delete of the metadata, never the file. */
  @PostMapping("/api/v1/property-media/{mediaId}/archive")
  @PreAuthorize("hasAuthority('PROPERTY_DELETE')")
  public ApiResponse<Void> archive(@PathVariable UUID mediaId) {
    mediaService.archive(mediaId);
    return ApiResponse.ok(null, "Media detached");
  }
}
