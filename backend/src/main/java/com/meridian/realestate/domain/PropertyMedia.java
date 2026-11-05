package com.meridian.realestate.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.util.UUID;

/**
 * Metadata for a drawing, brochure, photograph or video that lives outside this database.
 *
 * <p>Deliberately stores no binary: this product has no blob-storage service, and the interface
 * never renders a photograph it has not been given. A property without media shows a generated
 * plot mark, not an invented image - which is why the demo dataset seeds no rows here.</p>
 *
 * <p>Carries {@code created_by} but not {@code updated_by}, so it extends
 * {@link AuditableWithoutUpdater} rather than {@link Auditable}: the table records who attached a
 * file, and that signature is not something a later metadata edit rewrites.</p>
 */
@Entity
@Table(name = "property_media")
public class PropertyMedia extends AuditableWithoutUpdater {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "property_id", nullable = false)
  private Property property;

  @Enumerated(EnumType.STRING)
  @Column(name = "media_type", nullable = false, length = 30)
  private PropertyMediaType mediaType;

  @Column(name = "title", nullable = false, length = 180)
  private String title;

  @Column(name = "url", length = 1000)
  private String url;

  @Column(name = "caption", length = 500)
  private String caption;

  @Column(name = "mime_type", length = 120)
  private String mimeType;

  @Column(name = "byte_size")
  private Long byteSize;

  @Column(name = "width_px")
  private Integer widthPx;

  @Column(name = "height_px")
  private Integer heightPx;

  /** The one asset that represents the property in a list; at most one is meaningful per property. */
  @Column(name = "is_primary", nullable = false)
  private boolean isPrimary = false;

  @Column(name = "display_order", nullable = false)
  private int displayOrder = 0;

  public UUID getId() {
    return id;
  }

  public void setId(UUID id) {
    this.id = id;
  }

  public Property getProperty() {
    return property;
  }

  public void setProperty(Property property) {
    this.property = property;
  }

  public PropertyMediaType getMediaType() {
    return mediaType;
  }

  public void setMediaType(PropertyMediaType mediaType) {
    this.mediaType = mediaType;
  }

  public String getTitle() {
    return title;
  }

  public void setTitle(String title) {
    this.title = title;
  }

  public String getUrl() {
    return url;
  }

  public void setUrl(String url) {
    this.url = url;
  }

  public String getCaption() {
    return caption;
  }

  public void setCaption(String caption) {
    this.caption = caption;
  }

  public String getMimeType() {
    return mimeType;
  }

  public void setMimeType(String mimeType) {
    this.mimeType = mimeType;
  }

  public Long getByteSize() {
    return byteSize;
  }

  public void setByteSize(Long byteSize) {
    this.byteSize = byteSize;
  }

  public Integer getWidthPx() {
    return widthPx;
  }

  public void setWidthPx(Integer widthPx) {
    this.widthPx = widthPx;
  }

  public Integer getHeightPx() {
    return heightPx;
  }

  public void setHeightPx(Integer heightPx) {
    this.heightPx = heightPx;
  }

  public boolean isPrimary() {
    return isPrimary;
  }

  public void setIsPrimary(boolean isPrimary) {
    this.isPrimary = isPrimary;
  }

  public int getDisplayOrder() {
    return displayOrder;
  }

  public void setDisplayOrder(int displayOrder) {
    this.displayOrder = displayOrder;
  }
}
