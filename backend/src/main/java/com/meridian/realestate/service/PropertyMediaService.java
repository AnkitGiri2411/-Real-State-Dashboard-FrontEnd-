package com.meridian.realestate.service;

import com.meridian.realestate.domain.Property;
import com.meridian.realestate.domain.PropertyMedia;
import com.meridian.realestate.domain.PropertyMediaType;
import com.meridian.realestate.domain.User;
import com.meridian.realestate.dto.ErrorMessages;
import com.meridian.realestate.dto.PropertyMediaDtos;
import com.meridian.realestate.exception.ApiException;
import com.meridian.realestate.repository.PropertyMediaRepository;
import com.meridian.realestate.repository.PropertyRepository;
import com.meridian.realestate.security.AccessScope;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Property media as metadata.
 *
 * <p><b>Why this class exists.</b> {@code property_media} records that a drawing, brochure or
 * photograph exists and where it lives; the schema stores no binary, this product has no
 * blob-storage service, and the demo dataset seeds no rows. The service therefore has one job -
 * registering, changing and detaching those metadata rows - and offers nothing that would imply it
 * holds a file.</p>
 *
 * <p><b>The rule it protects - never invent an image URL.</b> Every URL that leaves or enters this
 * service is one a caller supplied; nothing here fabricates a path, a placeholder or a generated
 * image address. A property without media shows a generated plot mark, not an invented photograph,
 * and a metadata row with no {@code url} is an honest row that says the file's address is not yet
 * known. This is why the demo dataset seeds no media: fabricating photography would be inventing
 * data the product does not have.</p>
 *
 * <p>Media is detached rather than deleted - {@code deletedAt} is stamped - because a record that a
 * brochure was once attached is worth keeping, and because the soft delete is the same discipline
 * every other business table in the product follows.</p>
 */
@Service
public class PropertyMediaService {

  private final PropertyMediaRepository mediaRepository;
  private final PropertyRepository propertyRepository;
  private final AuditRecordingService audit;
  private final AccessScope accessScope;

  public PropertyMediaService(
      PropertyMediaRepository mediaRepository,
      PropertyRepository propertyRepository,
      AuditRecordingService audit,
      AccessScope accessScope) {
    this.mediaRepository = mediaRepository;
    this.propertyRepository = propertyRepository;
    this.audit = audit;
    this.accessScope = accessScope;
  }

  /* ====================================================================== */
  /* Reads                                                                  */
  /* ====================================================================== */

  /** A property's media metadata, in the order the gallery presents it. */
  @Transactional(readOnly = true)
  public List<PropertyMediaDtos.PropertyMediaView> list(UUID propertyId) {
    requireProperty(propertyId);
    return mediaRepository
        .findAllByPropertyIdAndDeletedAtIsNullOrderByDisplayOrderAsc(propertyId)
        .stream()
        .map(PropertyMediaDtos.PropertyMediaView::from)
        .toList();
  }

  /** One media row's metadata. */
  @Transactional(readOnly = true)
  public PropertyMediaDtos.PropertyMediaView detail(UUID mediaId) {
    return PropertyMediaDtos.PropertyMediaView.from(require(mediaId));
  }

  /** The asset that currently represents the property in a list, if one has been marked. */
  @Transactional(readOnly = true)
  public List<PropertyMediaDtos.PropertyMediaView> primary(UUID propertyId) {
    requireProperty(propertyId);
    return mediaRepository.findAllByPropertyIdAndIsPrimaryTrueAndDeletedAtIsNull(propertyId).stream()
        .map(PropertyMediaDtos.PropertyMediaView::from)
        .toList();
  }

  /* ====================================================================== */
  /* Writes                                                                 */
  /* ====================================================================== */

  /** Register a piece of media metadata. No URL is invented; a row with none is a valid row. */
  @Transactional
  public PropertyMediaDtos.PropertyMediaView create(
      UUID propertyId, PropertyMediaDtos.CreatePropertyMediaRequest request) {
    User actor = accessScope.requireUser();
    Property property = requireProperty(propertyId);

    PropertyMedia media = new PropertyMedia();
    media.setProperty(property);
    media.setMediaType(parseType(request.mediaType(), null));
    media.setTitle(request.title().trim());
    // Exactly what the caller supplied, never a generated address. See the class note.
    media.setUrl(request.url());
    media.setCaption(request.caption());
    media.setMimeType(request.mimeType());
    media.setByteSize(request.byteSize());
    media.setWidthPx(request.widthPx());
    media.setHeightPx(request.heightPx());
    media.setIsPrimary(request.primary() != null && request.primary());
    media.setDisplayOrder(request.displayOrder() == null ? 0 : request.displayOrder());
    media.setCreatedBy(actor.getId());

    PropertyMedia saved = mediaRepository.save(media);

    audit.record(
        actor.getId(),
        actor.getFullName(),
        AuditActions.PROPERTY,
        AuditActions.CREATE,
        "Attached " + saved.getMediaType() + " metadata '" + saved.getTitle() + "'",
        "PROPERTY_MEDIA",
        saved.getId(),
        saved.getTitle());

    return PropertyMediaDtos.PropertyMediaView.from(saved);
  }

  /** Change a media row's metadata. Only the fields supplied are applied; no URL is generated. */
  @Transactional
  public PropertyMediaDtos.PropertyMediaView update(
      UUID mediaId, PropertyMediaDtos.UpdatePropertyMediaRequest request) {
    User actor = accessScope.requireUser();
    PropertyMedia media = require(mediaId);

    if (request.mediaType() != null) media.setMediaType(parseType(request.mediaType(), null));
    if (request.title() != null) media.setTitle(request.title().trim());
    if (request.url() != null) media.setUrl(request.url());
    if (request.caption() != null) media.setCaption(request.caption());
    if (request.mimeType() != null) media.setMimeType(request.mimeType());
    if (request.byteSize() != null) media.setByteSize(request.byteSize());
    if (request.widthPx() != null) media.setWidthPx(request.widthPx());
    if (request.heightPx() != null) media.setHeightPx(request.heightPx());
    if (request.primary() != null) media.setIsPrimary(request.primary());
    if (request.displayOrder() != null) media.setDisplayOrder(request.displayOrder());

    PropertyMedia saved = mediaRepository.save(media);

    audit.record(
        actor.getId(),
        actor.getFullName(),
        AuditActions.PROPERTY,
        AuditActions.UPDATE,
        "Changed media metadata '" + saved.getTitle() + "'",
        "PROPERTY_MEDIA",
        saved.getId(),
        saved.getTitle());

    return PropertyMediaDtos.PropertyMediaView.from(saved);
  }

  /** Detach a media row: soft delete, because the record that it was attached is worth keeping. */
  @Transactional
  public void archive(UUID mediaId) {
    User actor = accessScope.requireUser();
    PropertyMedia media = require(mediaId);

    media.setDeletedAt(LocalDateTime.now());
    mediaRepository.save(media);

    audit.record(
        actor.getId(),
        actor.getFullName(),
        AuditActions.PROPERTY,
        AuditActions.DELETE,
        "Detached media metadata '" + media.getTitle() + "'",
        "PROPERTY_MEDIA",
        media.getId(),
        media.getTitle());
  }

  /* ====================================================================== */
  /* Internals                                                              */
  /* ====================================================================== */

  private PropertyMedia require(UUID mediaId) {
    return mediaRepository
        .findByIdAndDeletedAtIsNull(mediaId)
        .orElseThrow(() -> ApiException.notFound("We could not find that media record"));
  }

  private Property requireProperty(UUID propertyId) {
    return propertyRepository
        .findByIdAndDeletedAtIsNull(propertyId)
        .orElseThrow(() -> ApiException.notFound(ErrorMessages.friendly("PROPERTY_NOT_FOUND")));
  }

  private static PropertyMediaType parseType(String value, PropertyMediaType fallback) {
    if (value == null || value.isBlank()) {
      if (fallback == null) {
        throw ApiException.badRequest("A media type is required");
      }
      return fallback;
    }
    try {
      return PropertyMediaType.valueOf(value.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException ex) {
      throw ApiException.badRequest("'" + value + "' is not a property media type");
    }
  }
}
