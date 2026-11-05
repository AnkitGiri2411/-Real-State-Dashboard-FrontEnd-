-- ============================================================================
-- V4 - Unit types, units and unit status history
-- ============================================================================
-- A unit is the smallest lettable or sellable thing in the portfolio, and it is
-- what occupancy, vacancy, the rent roll and the unit board are all computed
-- over. A property is the asset; a unit is what a tenant actually occupies and
-- what a buyer actually buys.
--
-- `leasable` lives on the unit as well as on the property type. A building's
-- common areas, a caretaker's flat or a marketing suite are real units but they
-- are not available to let, and counting them would understate occupancy - so
-- they exist in the register and are excluded from every occupancy figure at the
-- source rather than by a filter someone has to remember to apply.
-- ============================================================================


-- ----------------------------------------------------------------------------
-- 1. Unit types — reference data
-- ----------------------------------------------------------------------------

CREATE TABLE unit_types (
  id            uuid         PRIMARY KEY,
  code          varchar(40)  NOT NULL UNIQUE,
  name          varchar(100) NOT NULL,
  description   varchar(255),
  display_order integer      NOT NULL DEFAULT 0,
  created_at    timestamp    NOT NULL,
  updated_at    timestamp    NOT NULL
);


-- ----------------------------------------------------------------------------
-- 2. Units
-- ----------------------------------------------------------------------------

CREATE TABLE units (
  id                uuid          PRIMARY KEY,
  unit_code         varchar(30)   NOT NULL UNIQUE,
  property_id       uuid          NOT NULL REFERENCES properties (id),
  building_id       uuid          REFERENCES buildings (id),
  unit_type_id      uuid          NOT NULL REFERENCES unit_types (id),

  unit_number       varchar(30)   NOT NULL,
  -- Floor is a signed number so basements are real floors rather than a string
  -- someone has to parse. The label carries what is printed on the plan ("G",
  -- "B1", "12"), because a floor reads as a name at the desk and as a number to
  -- a sort.
  floor_number      integer       NOT NULL DEFAULT 0,
  floor_label       varchar(30),

  bedrooms          integer       NOT NULL DEFAULT 0,
  bathrooms         integer       NOT NULL DEFAULT 0,
  balconies         integer       NOT NULL DEFAULT 0,
  carpet_area_sqft  numeric(12, 2),
  built_up_area_sqft numeric(12, 2),
  parking_slots     integer       NOT NULL DEFAULT 0,

  furnishing        varchar(30)   NOT NULL DEFAULT 'UNFURNISHED',
  facing            varchar(10),

  status            varchar(30)   NOT NULL DEFAULT 'AVAILABLE',
  -- See the header: common areas and non-lettable stock are real units that are
  -- excluded from occupancy rather than absent from the register.
  leasable          boolean       NOT NULL DEFAULT true,

  asking_rent       numeric(12, 2),
  asking_price      numeric(14, 2),

  created_at        timestamp     NOT NULL,
  updated_at        timestamp     NOT NULL,
  created_by        uuid,
  updated_by        uuid,
  deleted_at        timestamp,

  CONSTRAINT uq_units_property_number   UNIQUE (property_id, unit_number),
  CONSTRAINT ck_units_status CHECK (status IN (
    'AVAILABLE', 'RESERVED', 'OCCUPIED', 'UNDER_MAINTENANCE',
    'LISTED', 'UNDER_CONTRACT', 'SOLD', 'NOT_LETTABLE'
  )),
  CONSTRAINT ck_units_furnishing CHECK (furnishing IN ('UNFURNISHED', 'SEMI_FURNISHED', 'FURNISHED')),
  CONSTRAINT ck_units_facing CHECK (facing IS NULL OR facing IN ('N', 'S', 'E', 'W', 'NE', 'NW', 'SE', 'SW')),
  CONSTRAINT ck_units_bedrooms   CHECK (bedrooms BETWEEN 0 AND 50),
  CONSTRAINT ck_units_bathrooms  CHECK (bathrooms BETWEEN 0 AND 50),
  CONSTRAINT ck_units_balconies  CHECK (balconies BETWEEN 0 AND 50),
  CONSTRAINT ck_units_parking    CHECK (parking_slots BETWEEN 0 AND 500),
  CONSTRAINT ck_units_floor_range CHECK (floor_number BETWEEN -10 AND 200),
  CONSTRAINT ck_units_carpet_positive   CHECK (carpet_area_sqft IS NULL OR carpet_area_sqft > 0),
  CONSTRAINT ck_units_builtup_positive  CHECK (built_up_area_sqft IS NULL OR built_up_area_sqft > 0),
  CONSTRAINT ck_units_asking_rent_non_negative  CHECK (asking_rent IS NULL OR asking_rent >= 0),
  CONSTRAINT ck_units_asking_price_non_negative CHECK (asking_price IS NULL OR asking_price >= 0),
  -- A non-lettable unit cannot be sitting in a lettable state. Enforcing it here
  -- means the occupancy figures cannot be corrupted by a row that claims both.
  CONSTRAINT ck_units_not_lettable_status CHECK (
    leasable = true OR status IN ('NOT_LETTABLE', 'UNDER_MAINTENANCE')
  )
);

CREATE INDEX ix_units_property ON units (property_id) WHERE deleted_at IS NULL;
CREATE INDEX ix_units_building ON units (building_id) WHERE deleted_at IS NULL;
CREATE INDEX ix_units_status ON units (status) WHERE deleted_at IS NULL;
-- Occupancy, vacancy and the unit board all read only the lettable stock, so the
-- index is partial on exactly that set.
CREATE INDEX ix_units_leasable_status ON units (status) WHERE leasable = true AND deleted_at IS NULL;


-- ----------------------------------------------------------------------------
-- 3. Status history — how the unit got where it is
-- ----------------------------------------------------------------------------

CREATE TABLE unit_status_history (
  id          uuid        PRIMARY KEY,
  unit_id     uuid        NOT NULL REFERENCES units (id) ON DELETE CASCADE,
  from_status varchar(30),
  to_status   varchar(30) NOT NULL,
  reason      varchar(500),
  changed_by  uuid        REFERENCES users (id),
  changed_at  timestamp   NOT NULL
);

CREATE INDEX ix_unit_status_history_unit ON unit_status_history (unit_id, changed_at DESC);


-- ----------------------------------------------------------------------------
-- 4. Reference data — unit types
-- ----------------------------------------------------------------------------

INSERT INTO unit_types (id, code, name, description, display_order, created_at, updated_at) VALUES
  (gen_random_uuid(), 'STUDIO',         'Studio',          'Single-room dwelling with a kitchenette.',           10, now(), now()),
  (gen_random_uuid(), 'BHK_1',          '1 BHK',           'One bedroom, hall and kitchen.',                     20, now(), now()),
  (gen_random_uuid(), 'BHK_2',          '2 BHK',           'Two bedrooms, hall and kitchen.',                    30, now(), now()),
  (gen_random_uuid(), 'BHK_3',          '3 BHK',           'Three bedrooms, hall and kitchen.',                  40, now(), now()),
  (gen_random_uuid(), 'BHK_4_PLUS',     '4 BHK and above', 'Four or more bedrooms.',                             50, now(), now()),
  (gen_random_uuid(), 'PENTHOUSE',      'Penthouse',       'Top-floor dwelling with terrace.',                   60, now(), now()),
  (gen_random_uuid(), 'OFFICE_SUITE',   'Office Suite',    'Fitted or shell office suite.',                      70, now(), now()),
  (gen_random_uuid(), 'RETAIL_UNIT',    'Retail Unit',     'Shop or showroom frontage.',                         80, now(), now()),
  (gen_random_uuid(), 'WAREHOUSE_BAY',  'Warehouse Bay',   'Racked or open warehouse bay.',                      90, now(), now()),
  (gen_random_uuid(), 'COMMON_AREA',    'Common Area',     'Lobby, plant room or amenity space — not lettable.', 100, now(), now());


-- ----------------------------------------------------------------------------
-- 5. Guard
-- ----------------------------------------------------------------------------

DO $$
DECLARE
  type_count integer;
BEGIN
  SELECT count(*) INTO type_count FROM unit_types;
  IF type_count < 10 THEN
    RAISE EXCEPTION 'Expected at least 10 unit types, found %', type_count;
  END IF;
END $$;
