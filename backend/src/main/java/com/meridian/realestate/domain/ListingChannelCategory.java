package com.meridian.realestate.domain;

/**
 * The kind of channel a listing is published on.
 *
 * <p>Exactly the values the {@code ck_listing_channels_category} constraint allows.</p>
 */
public enum ListingChannelCategory {
  /** A property portal. */
  PORTAL,
  /** The firm's own website. */
  WEBSITE,
  /** The channel-partner network. */
  BROKER_NETWORK,
  /** A board on the building. */
  SIGNAGE,
  /** A social media channel. */
  SOCIAL,
  /** Print media. */
  PRINT,
  /** The firm's own register only. */
  INTERNAL,
  /** Not attributable to a known category. */
  OTHER
}
