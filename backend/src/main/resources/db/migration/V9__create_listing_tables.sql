-- ============================================================================
-- V9 - Listing channels, listings and listing status history
-- ============================================================================
-- A listing is the offer of a specific property or unit to the market. It carries
-- the asking terms, the channel it is advertised on and its publication state.
--
-- A listing points at a property or at a unit, or both. A whole building offered
-- to let is listed at the property level; a single flat is listed at the unit
-- level. The check constraint allows either but not neither, because a listing
-- that offers nothing is not a listing.
--
-- The asking terms are separate from the property's valuation. A property is
-- worth what it is worth; a listing is what the firm is currently asking, which
-- is a commercial decision that changes while the valuation does not.
-- ============================================================================


-- ----------------------------------------------------------------------------
-- 1. Listing channels — reference data
-- ----------------------------------------------------------------------------

CREATE TABLE listing_channels (
  id            uuid         PRIMARY KEY,
  code          varchar(40)  NOT NULL UNIQUE,
  name          varchar(120) NOT NULL,
  category      varchar(30)  NOT NULL,
  description   varchar(255),
  is_active     boolean      NOT NULL DEFAULT true,
  display_order integer      NOT NULL DEFAULT 0,
  created_at    timestamp    NOT NULL,
  updated_at    timestamp    NOT NULL,
  CONSTRAINT ck_listing_channels_category CHECK (category IN (
    'PORTAL', 'WEBSITE', 'BROKER_NETWORK', 'SIGNAGE', 'SOCIAL', 'PRINT', 'INTERNAL', 'OTHER'
  ))
);

CREATE INDEX ix_listing_channels_active ON listing_channels (is_active);


-- ----------------------------------------------------------------------------
-- 2. Listings
-- ----------------------------------------------------------------------------

CREATE TABLE listings (
  id                uuid          PRIMARY KEY,
  listing_code      varchar(30)   NOT NULL UNIQUE,
  property_id       uuid          NOT NULL REFERENCES properties (id),
  unit_id           uuid          REFERENCES units (id),
  listing_type      varchar(30)   NOT NULL DEFAULT 'RENT',
  channel_id        uuid          REFERENCES listing_channels (id),
  status            varchar(30)   NOT NULL DEFAULT 'DRAFT',

  headline          varchar(255)  NOT NULL,
  description       text,

  -- The asking terms. Either may be null: a rental listing has no sale price.
  asking_rent       numeric(12, 2),
  asking_price      numeric(14, 2),
  -- True when the firm holds the sole mandate, which changes what the fee is and
  -- whether another agent may be shown the stock.
  exclusive         boolean       NOT NULL DEFAULT false,
  negotiable        boolean       NOT NULL DEFAULT true,

  listing_start     date          NOT NULL,
  listing_end       date,
  published_at      timestamp,
  closed_at         timestamp,
  closed_reason     varchar(500),

  -- Who is running the listing.
  listed_by         uuid          REFERENCES agent_profiles (id),
  office_id         uuid          REFERENCES offices (id),

  created_at        timestamp     NOT NULL,
  updated_at        timestamp     NOT NULL,
  created_by        uuid,
  updated_by        uuid,
  deleted_at        timestamp,

  CONSTRAINT ck_listings_type CHECK (listing_type IN ('RENT', 'SALE', 'RENT_AND_SALE')),
  CONSTRAINT ck_listings_status CHECK (status IN (
    'DRAFT', 'PUBLISHED', 'ACTIVE', 'UNDER_OFFER', 'LET_AGREED', 'SOLD', 'WITHDRAWN', 'EXPIRED'
  )),
  -- A listing must offer a property or a unit, and may offer both.
  CONSTRAINT ck_listings_target CHECK (property_id IS NOT NULL OR unit_id IS NOT NULL),
  CONSTRAINT ck_listings_period CHECK (listing_end IS NULL OR listing_end >= listing_start),
  CONSTRAINT ck_listings_asking_rent_non_negative  CHECK (asking_rent IS NULL OR asking_rent >= 0),
  CONSTRAINT ck_listings_asking_price_non_negative CHECK (asking_price IS NULL OR asking_price >= 0),
  -- A rental listing must state a rent; a sale listing must state a price. Without
  -- this a listing can be published with no terms at all, which is not an offering.
  CONSTRAINT ck_listings_terms_present CHECK (
    (listing_type = 'RENT' AND asking_rent IS NOT NULL)
    OR (listing_type = 'SALE' AND asking_price IS NOT NULL)
    OR (listing_type = 'RENT_AND_SALE' AND (asking_rent IS NOT NULL OR asking_price IS NOT NULL))
  ),
  -- A closed listing records when it closed. Anything else must not, so the
  -- "stale listing" calculation cannot silently skip a withdrawn row.
  CONSTRAINT ck_listings_closed CHECK (
    (status IN ('LET_AGREED', 'SOLD', 'WITHDRAWN', 'EXPIRED') AND closed_at IS NOT NULL)
    OR (status NOT IN ('LET_AGREED', 'SOLD', 'WITHDRAWN', 'EXPIRED'))
  )
);

CREATE INDEX ix_listings_status ON listings (status) WHERE deleted_at IS NULL;
CREATE INDEX ix_listings_property ON listings (property_id) WHERE deleted_at IS NULL;
CREATE INDEX ix_listings_unit ON listings (unit_id) WHERE unit_id IS NOT NULL AND deleted_at IS NULL;
CREATE INDEX ix_listings_agent ON listings (listed_by) WHERE deleted_at IS NULL;
CREATE INDEX ix_listings_channel ON listings (channel_id) WHERE deleted_at IS NULL;
-- The live board reads only the listings still on the market.
CREATE INDEX ix_listings_live ON listings (status, listing_start DESC)
  WHERE status IN ('PUBLISHED', 'ACTIVE', 'UNDER_OFFER') AND deleted_at IS NULL;


-- ----------------------------------------------------------------------------
-- 3. Status history
-- ----------------------------------------------------------------------------

CREATE TABLE listing_status_history (
  id         uuid        PRIMARY KEY,
  listing_id uuid        NOT NULL REFERENCES listings (id) ON DELETE CASCADE,
  from_status varchar(30),
  to_status  varchar(30) NOT NULL,
  reason     varchar(500),
  changed_by uuid        REFERENCES users (id),
  changed_at timestamp   NOT NULL
);

CREATE INDEX ix_listing_status_history_listing ON listing_status_history (listing_id, changed_at DESC);


-- ----------------------------------------------------------------------------
-- 4. Reference data — listing channels
-- ----------------------------------------------------------------------------

INSERT INTO listing_channels (id, code, name, category, description, is_active, display_order, created_at, updated_at) VALUES
  (gen_random_uuid(), 'PORTAL_99ACRES',    '99acres',              'PORTAL',         'Property portal listing.',         true,  10, now(), now()),
  (gen_random_uuid(), 'PORTAL_MAGICBRICKS','MagicBricks',          'PORTAL',         'Property portal listing.',         true,  20, now(), now()),
  (gen_random_uuid(), 'PORTAL_HOUSING',    'Housing.com',          'PORTAL',         'Property portal listing.',         true,  30, now(), now()),
  (gen_random_uuid(), 'WEBSITE',           'Firm website',         'WEBSITE',        'Published on the firm''s own site.', true, 40, now(), now()),
  (gen_random_uuid(), 'BROKER_NETWORK',    'Channel partner network','BROKER_NETWORK','Shared with the partner network.', true,  50, now(), now()),
  (gen_random_uuid(), 'SIGNAGE',           'Site signage',         'SIGNAGE',        'Board on the building.',           true,  60, now(), now()),
  (gen_random_uuid(), 'SOCIAL',            'Social media',         'SOCIAL',         'Social channel campaign.',         true,  70, now(), now()),
  (gen_random_uuid(), 'PRINT',             'Print media',          'PRINT',          'Newspaper or magazine insert.',    false, 80, now(), now()),
  (gen_random_uuid(), 'INTERNAL',          'Internal register',    'INTERNAL',       'Held in the firm''s own register only.', true, 90, now(), now()),
  (gen_random_uuid(), 'OTHER',             'Other',                'OTHER',          'Not attributable to a known channel.', true,100, now(), now());


-- ----------------------------------------------------------------------------
-- 5. Guard
-- ----------------------------------------------------------------------------

DO $$
DECLARE
  channel_count integer;
BEGIN
  SELECT count(*) INTO channel_count FROM listing_channels;
  IF channel_count < 10 THEN
    RAISE EXCEPTION 'Expected at least 10 listing channels, found %', channel_count;
  END IF;
END $$;
