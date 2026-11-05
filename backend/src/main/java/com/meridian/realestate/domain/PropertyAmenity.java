package com.meridian.realestate.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapsId;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.util.UUID;

/**
 * The join between a property and the facilities it offers.
 *
 * <p>A pure link table with one extra column: {@code notes}, which carries the property-specific
 * detail ("two 250 kVA generators") that a bare amenity cannot. It has no surrogate key, no
 * timestamps and no soft delete - removing an amenity from a property is a real deletion, because
 * the facility is either offered or it is not.</p>
 *
 * <p>The identity is the pair, so it is modelled as an {@link EmbeddedId} and the two associations
 * are mapped with {@code @MapsId}: the foreign key columns <em>are</em> the primary key columns,
 * rather than a surrogate id sitting beside them.</p>
 */
@Entity
@Table(name = "property_amenities")
public class PropertyAmenity {

  @EmbeddedId
  private PropertyAmenityId id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @MapsId("propertyId")
  @JoinColumn(name = "property_id", nullable = false)
  private Property property;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @MapsId("amenityId")
  @JoinColumn(name = "amenity_id", nullable = false)
  private Amenity amenity;

  /** The property-specific detail a bare amenity cannot carry. */
  @Column(name = "notes", length = 255)
  private String notes;

  public PropertyAmenityId getId() {
    return id;
  }

  public void setId(PropertyAmenityId id) {
    this.id = id;
  }

  public Property getProperty() {
    return property;
  }

  public void setProperty(Property property) {
    this.property = property;
  }

  public Amenity getAmenity() {
    return amenity;
  }

  public void setAmenity(Amenity amenity) {
    this.amenity = amenity;
  }

  public String getNotes() {
    return notes;
  }

  public void setNotes(String notes) {
    this.notes = notes;
  }

  /**
   * The composite primary key: the property and the amenity.
   *
   * <p>A record, because the identity of a link row is exactly its two columns and nothing else -
   * the generated {@code equals} / {@code hashCode} are the ones JPA needs for a composite key, and
   * being immutable they cannot drift from the associations they mirror.</p>
   */
  @Embeddable
  public record PropertyAmenityId(UUID propertyId, UUID amenityId) implements Serializable {}
}
