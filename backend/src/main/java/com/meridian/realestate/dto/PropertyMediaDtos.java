package com.meridian.realestate.dto;

import com.meridian.realestate.domain.PropertyMedia;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * The property media API contract.
 *
 * <p><b>Why this record exists and what it deliberately is not.</b> {@code property_media} is
 * metadata only: it records that a drawing, brochure or photograph exists and where it lives. This
 * product has no blob-storage service and the schema stores no binary, so every shape here describes
 * a file that already exists outside the database. The demo dataset seeds no media rows for exactly
 * that reason.</p>
 *
 * <p><b>The rule it protects - never invent an image URL.</b> {@code url} is optional and is only
 * ever whatever the caller supplied; nothing in the service fabricates a path, a placeholder or a
 * generated image address. A property without media shows a generated plot mark, not an invented
 * photograph, and a metadata row with no {@code url} is an honest row that says the file's address
 * is not yet known.</p>
 */
public final class PropertyMediaDtos {

  private PropertyMediaDtos() {}

  /** The closed set of media types the schema's {@code ck_property_media_type} allows. */
  private static final String MEDIA_TYPE_SET =
      "PHOTO|FLOOR_PLAN|SITE_PLAN|BROCHURE|VIDEO|OTHER";

  /** One piece of property media, as metadata. */
  public record PropertyMediaView(
      UUID id,
      UUID propertyId,
      String mediaType,
      String title,
      String url,
      String caption,
      String mimeType,
      Long byteSize,
      Integer widthPx,
      Integer heightPx,
      boolean primary,
      int displayOrder,
      LocalDateTime createdAt,
      LocalDateTime updatedAt) {

    public static PropertyMediaView from(PropertyMedia media) {
      return new PropertyMediaView(
          media.getId(),
          media.getProperty() == null ? null : media.getProperty().getId(),
          media.getMediaType() == null ? null : media.getMediaType().name(),
          media.getTitle(),
          media.getUrl(),
          media.getCaption(),
          media.getMimeType(),
          media.getByteSize(),
          media.getWidthPx(),
          media.getHeightPx(),
          media.isPrimary(),
          media.getDisplayOrder(),
          media.getCreatedAt(),
          media.getUpdatedAt());
    }
  }

  /**
   * Register a piece of media metadata.
   *
   * <p>No field here is a file. {@code url} is the address the asset already has; a caller that does
   * not have one simply omits it.</p>
   */
  public record CreatePropertyMediaRequest(
      @NotBlank
          @Pattern(regexp = MEDIA_TYPE_SET, message = "must be a property media type")
          String mediaType,
      @NotBlank @Size(max = 180) String title,
      @Size(max = 1000) String url,
      @Size(max = 500) String caption,
      @Size(max = 120) String mimeType,
      @Min(value = 0, message = "cannot be negative") Long byteSize,
      @Min(value = 0, message = "cannot be negative") Integer widthPx,
      @Min(value = 0, message = "cannot be negative") Integer heightPx,
      Boolean primary,
      @Min(value = 0, message = "cannot be negative") Integer displayOrder) {}

  /** Change a media row's metadata. Every field is optional; only the ones supplied are applied. */
  public record UpdatePropertyMediaRequest(
      @Pattern(regexp = MEDIA_TYPE_SET, message = "must be a property media type") String mediaType,
      @Size(max = 180) String title,
      @Size(max = 1000) String url,
      @Size(max = 500) String caption,
      @Size(max = 120) String mimeType,
      @Min(value = 0, message = "cannot be negative") Long byteSize,
      @Min(value = 0, message = "cannot be negative") Integer widthPx,
      @Min(value = 0, message = "cannot be negative") Integer heightPx,
      Boolean primary,
      @Min(value = 0, message = "cannot be negative") Integer displayOrder) {}
}
