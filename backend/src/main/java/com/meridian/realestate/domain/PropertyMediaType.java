package com.meridian.realestate.domain;

/**
 * What a piece of property media actually is.
 *
 * <p>Exactly the values the {@code ck_property_media_type} constraint allows. The table stores
 * metadata only - this product has no blob-storage service, and the interface never renders a
 * photograph it has not been given - so the type is what tells the reader whether a row is a
 * photograph, a drawing or a document.</p>
 */
public enum PropertyMediaType {
  /** A photograph. */
  PHOTO,
  /** A floor plan for a building or unit. */
  FLOOR_PLAN,
  /** A site or layout plan for the whole property. */
  SITE_PLAN,
  /** A marketing brochure. */
  BROCHURE,
  /** A video walkthrough. */
  VIDEO,
  /** Anything that does not fit the above. */
  OTHER
}
