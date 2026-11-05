-- ============================================================================
-- V3 - Property, building, amenity and property media metadata
-- ============================================================================
-- The register everything else in the portfolio hangs off. A property is the
-- asset; a building is a block within it; an amenity is what it offers; media
-- metadata describes drawings and documents that exist outside this database.
--
-- Latitude and longitude are first-class columns here because the portfolio map
-- is a real projection over real coordinates rather than a decorative graphic.
-- They are nullable: a property whose position is not yet known is omitted from
-- the map rather than plotted at a guessed point.
-- ============================================================================


-- ----------------------------------------------------------------------------
-- 1. Property types — reference data
-- ----------------------------------------------------------------------------

CREATE TABLE property_types (
  id          uuid         PRIMARY KEY,
  code        varchar(40)  NOT NULL UNIQUE,
  name        varchar(100) NOT NULL,
  description varchar(255),
  -- Whether stock of this type is normally let. Drives which properties appear
  -- in occupancy and yield, so it is a real business flag rather than a label.
  leasable    boolean      NOT NULL DEFAULT true,
  display_order integer    NOT NULL DEFAULT 0,
  created_at  timestamp    NOT NULL,
  updated_at  timestamp    NOT NULL
);

COMMENT ON COLUMN property_types.leasable IS
  'True when stock of this type is normally let. Non-leasable types are excluded from occupancy and yield.';


-- ----------------------------------------------------------------------------
-- 2. Properties — the asset register
-- ----------------------------------------------------------------------------

CREATE TABLE properties (
  id                  uuid          PRIMARY KEY,
  property_code       varchar(30)   NOT NULL UNIQUE,
  name                varchar(180)  NOT NULL,
  property_type_id    uuid          NOT NULL REFERENCES property_types (id),
  status              varchar(30)   NOT NULL DEFAULT 'DRAFT',

  -- Address. Kept as components rather than one blob so the portfolio can be
  -- grouped and filtered by city and locality, which is how the register is read.
  address_line1       varchar(200)  NOT NULL,
  address_line2       varchar(200),
  locality            varchar(120),
  city                varchar(120)  NOT NULL,
  state               varchar(120),
  postal_code         varchar(20),
  country             varchar(80)   NOT NULL DEFAULT 'India',

  -- Position. Both null or both present: half a coordinate pair cannot be plotted.
  latitude            numeric(9, 6),
  longitude           numeric(9, 6),

  description         text,
  year_built          integer,
  land_area_sqft      numeric(14, 2),
  built_area_sqft     numeric(14, 2),

  -- Valuation. acquisition_value is what was paid; current_valuation is the
  -- latest assessment and is what yield is measured against.
  acquisition_value   numeric(14, 2),
  current_valuation   numeric(14, 2),
  valuation_date      date,

  created_at          timestamp     NOT NULL,
  updated_at          timestamp     NOT NULL,
  created_by          uuid,
  updated_by          uuid,
  deleted_at          timestamp,

  CONSTRAINT ck_properties_status CHECK (status IN (
    'DRAFT', 'LISTED', 'AVAILABLE_ACTIVE', 'UNDER_CONTRACT', 'LEASED', 'SOLD', 'INACTIVE'
  )),
  CONSTRAINT ck_properties_latitude  CHECK (latitude IS NULL OR latitude BETWEEN -90 AND 90),
  CONSTRAINT ck_properties_longitude CHECK (longitude IS NULL OR longitude BETWEEN -180 AND 180),
  -- A coordinate pair is all-or-nothing, so the map never has half a position.
  CONSTRAINT ck_properties_position_paired CHECK (
    (latitude IS NULL AND longitude IS NULL) OR (latitude IS NOT NULL AND longitude IS NOT NULL)
  ),
  CONSTRAINT ck_properties_year_built CHECK (year_built IS NULL OR year_built BETWEEN 1700 AND 2200),
  -- Money that exists is never negative; a negative valuation is a data error,
  -- not a business state.
  CONSTRAINT ck_properties_acquisition_non_negative CHECK (acquisition_value IS NULL OR acquisition_value >= 0),
  CONSTRAINT ck_properties_valuation_non_negative  CHECK (current_valuation IS NULL OR current_valuation >= 0),
  CONSTRAINT ck_properties_land_area_positive      CHECK (land_area_sqft IS NULL OR land_area_sqft > 0),
  CONSTRAINT ck_properties_built_area_positive     CHECK (built_area_sqft IS NULL OR built_area_sqft > 0)
);

CREATE INDEX ix_properties_status ON properties (status) WHERE deleted_at IS NULL;
CREATE INDEX ix_properties_type ON properties (property_type_id) WHERE deleted_at IS NULL;
CREATE INDEX ix_properties_city ON properties (city) WHERE deleted_at IS NULL;
-- The map and any radius query read only positioned properties, so the index is
-- partial on exactly that set.
CREATE INDEX ix_properties_position ON properties (latitude, longitude)
  WHERE latitude IS NOT NULL AND deleted_at IS NULL;


-- ----------------------------------------------------------------------------
-- 3. Status history — how the asset got where it is
-- ----------------------------------------------------------------------------

CREATE TABLE property_status_history (
  id          uuid        PRIMARY KEY,
  property_id uuid        NOT NULL REFERENCES properties (id) ON DELETE CASCADE,
  from_status varchar(30),
  to_status   varchar(30) NOT NULL,
  reason      varchar(500),
  changed_by  uuid        REFERENCES users (id),
  changed_at  timestamp   NOT NULL
);

CREATE INDEX ix_property_status_history_property ON property_status_history (property_id, changed_at DESC);


-- ----------------------------------------------------------------------------
-- 4. Buildings — blocks within a property
-- ----------------------------------------------------------------------------
-- Several properties in the portfolio are multi-block, so the unit board groups
-- by building and then by floor. A single-block property has one building, which
-- keeps every unit's location path uniform.

CREATE TABLE buildings (
  id            uuid         PRIMARY KEY,
  property_id   uuid         NOT NULL REFERENCES properties (id) ON DELETE CASCADE,
  building_code varchar(30)  NOT NULL,
  name          varchar(150) NOT NULL,
  floors        integer,
  year_built    integer,
  description   varchar(500),
  created_at    timestamp    NOT NULL,
  updated_at    timestamp    NOT NULL,
  created_by    uuid,
  updated_by    uuid,
  deleted_at    timestamp,
  CONSTRAINT uq_buildings_property_code UNIQUE (property_id, building_code),
  CONSTRAINT ck_buildings_floors CHECK (floors IS NULL OR floors BETWEEN 0 AND 200)
);

CREATE INDEX ix_buildings_property ON buildings (property_id) WHERE deleted_at IS NULL;


-- ----------------------------------------------------------------------------
-- 5. Amenities — what the property offers
-- ----------------------------------------------------------------------------

CREATE TABLE amenities (
  id            uuid         PRIMARY KEY,
  code          varchar(50)  NOT NULL UNIQUE,
  name          varchar(120) NOT NULL,
  category      varchar(60)  NOT NULL,
  display_order integer      NOT NULL DEFAULT 0
);

CREATE TABLE property_amenities (
  property_id uuid NOT NULL REFERENCES properties (id) ON DELETE CASCADE,
  amenity_id  uuid NOT NULL REFERENCES amenities (id) ON DELETE CASCADE,
  notes       varchar(255),
  PRIMARY KEY (property_id, amenity_id)
);

CREATE INDEX ix_property_amenities_amenity ON property_amenities (amenity_id);


-- ----------------------------------------------------------------------------
-- 6. Property media - METADATA ONLY
-- ----------------------------------------------------------------------------
-- This table records that a drawing, brochure or photograph exists and where it
-- lives. It deliberately stores no binary: this product has no blob-storage
-- service, and the interface never renders a photograph it has not been given.
-- A property without media shows a generated plot mark, not an invented image.
--
-- The demo dataset seeds no rows here for exactly that reason: fabricating
-- property photography would be inventing data the product does not have.

CREATE TABLE property_media (
  id            uuid         PRIMARY KEY,
  property_id   uuid         NOT NULL REFERENCES properties (id) ON DELETE CASCADE,
  media_type    varchar(30)  NOT NULL,
  title         varchar(180) NOT NULL,
  url           varchar(1000),
  caption       varchar(500),
  mime_type     varchar(120),
  byte_size     bigint,
  width_px      integer,
  height_px     integer,
  is_primary    boolean      NOT NULL DEFAULT false,
  display_order integer      NOT NULL DEFAULT 0,
  created_at    timestamp    NOT NULL,
  updated_at    timestamp    NOT NULL,
  created_by    uuid,
  deleted_at    timestamp,
  CONSTRAINT ck_property_media_type CHECK (media_type IN (
    'PHOTO', 'FLOOR_PLAN', 'SITE_PLAN', 'BROCHURE', 'VIDEO', 'OTHER'
  )),
  CONSTRAINT ck_property_media_size CHECK (byte_size IS NULL OR byte_size >= 0)
);

CREATE INDEX ix_property_media_property ON property_media (property_id) WHERE deleted_at IS NULL;


-- ----------------------------------------------------------------------------
-- 7. Reference data — property types and amenities
-- ----------------------------------------------------------------------------
-- Lookup data, not demonstration data: these are the categories a property can be
-- classified into and the facilities it can offer. They belong with the tables
-- that reference them, and every installation needs them, so they are seeded by
-- the schema migration rather than by the demo dataset.
--
-- `leasable` is what keeps occupancy and yield honest. A plot of land cannot be
-- let in the sense an apartment can, so including it in an occupancy percentage
-- would understate the portfolio's performance - it is excluded at the source.

INSERT INTO property_types (id, code, name, description, leasable, display_order, created_at, updated_at) VALUES
  (gen_random_uuid(), 'RESIDENTIAL_APARTMENT', 'Apartment',       'Flats in a multi-unit residential block.',            true,  10, now(), now()),
  (gen_random_uuid(), 'RESIDENTIAL_VILLA',     'Villa',           'Detached or semi-detached dwelling with its own plot.', true, 20, now(), now()),
  (gen_random_uuid(), 'RESIDENTIAL_HOUSE',     'Independent House','Standalone dwelling on its own land.',              true,  30, now(), now()),
  (gen_random_uuid(), 'COMMERCIAL_OFFICE',     'Office',          'Office space let to corporate tenants.',              true,  40, now(), now()),
  (gen_random_uuid(), 'COMMERCIAL_RETAIL',     'Retail',          'High-street or mall retail unit.',                    true,  50, now(), now()),
  (gen_random_uuid(), 'INDUSTRIAL_WAREHOUSE',  'Warehouse',       'Industrial and logistics warehousing.',               true,  60, now(), now()),
  (gen_random_uuid(), 'MIXED_USE',             'Mixed Use',       'Residential and commercial in one asset.',            true,  70, now(), now()),
  (gen_random_uuid(), 'HOSPITALITY_HOTEL',     'Hotel',           'Serviced accommodation operated as a business.',      true,  80, now(), now()),
  (gen_random_uuid(), 'LAND_PLOT',             'Land / Plot',     'Undeveloped land held for development or resale.',    false, 90, now(), now());

INSERT INTO amenities (id, code, name, category, display_order) VALUES
  (gen_random_uuid(), 'POWER_BACKUP',        'Power Backup',              'UTILITY',    10),
  (gen_random_uuid(), 'DG_BACKUP',           'Diesel Generator Backup',   'UTILITY',    20),
  (gen_random_uuid(), 'WATER_24X7',          '24x7 Water Supply',         'UTILITY',    30),
  (gen_random_uuid(), 'SEWAGE_TREATMENT',    'Sewage Treatment Plant',    'UTILITY',    40),
  (gen_random_uuid(), 'RAINWATER_HARVESTING','Rainwater Harvesting',      'UTILITY',    50),
  (gen_random_uuid(), 'SOLAR_POWER',         'Solar Power',               'UTILITY',    60),
  (gen_random_uuid(), 'LIFT',                'Passenger Lift',            'ACCESS',     70),
  (gen_random_uuid(), 'FREIGHT_ELEVATOR',    'Freight Elevator',          'ACCESS',     80),
  (gen_random_uuid(), 'SECURITY_24X7',       '24x7 Security',             'SECURITY',   90),
  (gen_random_uuid(), 'CCTV',                'CCTV Surveillance',         'SECURITY',  100),
  (gen_random_uuid(), 'FIRE_SAFETY',         'Fire Safety System',        'SECURITY',  110),
  (gen_random_uuid(), 'PARKING_BASEMENT',    'Basement Parking',          'PARKING',   120),
  (gen_random_uuid(), 'PARKING_OPEN',        'Open Parking',              'PARKING',   130),
  (gen_random_uuid(), 'LOADING_DOCK',        'Loading Dock',              'LOGISTICS', 140),
  (gen_random_uuid(), 'GYM',                 'Gymnasium',                 'LIFESTYLE', 150),
  (gen_random_uuid(), 'SWIMMING_POOL',       'Swimming Pool',             'LIFESTYLE', 160),
  (gen_random_uuid(), 'CLUBHOUSE',           'Clubhouse',                 'LIFESTYLE', 170),
  (gen_random_uuid(), 'LANDSCAPED_GARDEN',   'Landscaped Garden',         'LIFESTYLE', 180);


-- ----------------------------------------------------------------------------
-- 8. Guard: reference data the register depends on
-- ----------------------------------------------------------------------------
-- Every property references a type, and the map is a core dashboard surface, so
-- the reference list has to be present before the demonstration data is seeded.

DO $$
DECLARE
  type_count integer;
  amenity_count integer;
BEGIN
  SELECT count(*) INTO type_count FROM property_types;
  IF type_count < 6 THEN
    RAISE EXCEPTION 'Expected at least 6 property types, found %', type_count;
  END IF;

  SELECT count(*) INTO amenity_count FROM amenities;
  IF amenity_count < 10 THEN
    RAISE EXCEPTION 'Expected at least 10 amenities, found %', amenity_count;
  END IF;
END $$;
