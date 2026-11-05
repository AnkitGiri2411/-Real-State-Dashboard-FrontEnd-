-- ============================================================================
-- V6 - Tenants and buyers
-- ============================================================================
-- The two sides of demand. A tenant occupies a unit under a lease; a buyer
-- acquires one under a sale. Both are parties the firm holds a relationship
-- with, so both are first-class records with their own code, rather than a name
-- typed onto a lease.
--
-- They are separate tables rather than one `parties` table with a type column
-- because what the firm knows about them genuinely differs: a tenant carries an
-- occupation, an employer and an income; a buyer carries a brief - what they
-- want, where and for how much. A single table would be half-null on both sides
-- and the brief could not be expressed at all.
-- ============================================================================


-- ----------------------------------------------------------------------------
-- 1. Tenants
-- ----------------------------------------------------------------------------

CREATE TABLE tenants (
  id                 uuid         PRIMARY KEY,
  tenant_code        varchar(30)  NOT NULL UNIQUE,
  tenant_type        varchar(30)  NOT NULL,
  -- The name the desk says out loud: a person's name, or a company's.
  display_name       varchar(200) NOT NULL,
  primary_contact_name varchar(200),

  email              varchar(255),
  phone              varchar(30),
  alternate_phone    varchar(30),

  id_reference       varchar(60),
  tax_reference      varchar(60),
  -- Company tenants only; null for an individual.
  registration_number varchar(60),

  occupation         varchar(120),
  employer           varchar(200),
  -- Used by application screening. Null means "not disclosed", which is a real
  -- and different state from zero.
  annual_income      numeric(14, 2),

  address_line1      varchar(200),
  address_line2      varchar(200),
  locality           varchar(120),
  city               varchar(120),
  state              varchar(120),
  postal_code        varchar(20),
  country            varchar(80)  NOT NULL DEFAULT 'India',

  status             varchar(20)  NOT NULL DEFAULT 'ACTIVE',
  notes              text,

  created_at         timestamp    NOT NULL,
  updated_at         timestamp    NOT NULL,
  created_by         uuid,
  updated_by         uuid,
  deleted_at         timestamp,

  CONSTRAINT ck_tenants_type CHECK (tenant_type IN ('INDIVIDUAL', 'COMPANY', 'TRUST', 'GOVERNMENT', 'OTHER')),
  -- Formally distinct from INACTIVE: a blacklisted party is one the firm has
  -- decided not to transact with, which is a decision rather than a silence.
  CONSTRAINT ck_tenants_status CHECK (status IN ('ACTIVE', 'INACTIVE', 'BLACKLISTED')),
  CONSTRAINT ck_tenants_email_lower CHECK (email IS NULL OR email = lower(email)),
  CONSTRAINT ck_tenants_income_non_negative CHECK (annual_income IS NULL OR annual_income >= 0)
);

CREATE INDEX ix_tenants_status ON tenants (status) WHERE deleted_at IS NULL;
CREATE INDEX ix_tenants_name ON tenants (display_name) WHERE deleted_at IS NULL;
CREATE INDEX ix_tenants_type ON tenants (tenant_type) WHERE deleted_at IS NULL;


-- ----------------------------------------------------------------------------
-- 2. Buyers
-- ----------------------------------------------------------------------------

CREATE TABLE buyers (
  id                 uuid         PRIMARY KEY,
  buyer_code         varchar(30)  NOT NULL UNIQUE,
  buyer_type         varchar(30)  NOT NULL,
  display_name       varchar(200) NOT NULL,
  primary_contact_name varchar(200),

  email              varchar(255),
  phone              varchar(30),
  alternate_phone    varchar(30),
  id_reference       varchar(60),
  tax_reference      varchar(60),

  address_line1      varchar(200),
  address_line2      varchar(200),
  locality           varchar(120),
  city               varchar(120),
  state              varchar(120),
  postal_code        varchar(20),
  country            varchar(80)  NOT NULL DEFAULT 'India',

  status             varchar(20)  NOT NULL DEFAULT 'ACTIVE',
  notes              text,

  created_at         timestamp    NOT NULL,
  updated_at         timestamp    NOT NULL,
  created_by         uuid,
  updated_by         uuid,
  deleted_at         timestamp,

  CONSTRAINT ck_buyers_type CHECK (buyer_type IN (
    'INDIVIDUAL', 'COMPANY', 'NRI', 'TRUST', 'FUND', 'JOINT_VENTURE', 'OTHER'
  )),
  -- CONVERTED records that this buyer completed a purchase - a lifecycle fact
  -- worth keeping, rather than archiving the record.
  CONSTRAINT ck_buyers_status CHECK (status IN ('ACTIVE', 'INACTIVE', 'CONVERTED')),
  CONSTRAINT ck_buyers_email_lower CHECK (email IS NULL OR email = lower(email))
);

CREATE INDEX ix_buyers_status ON buyers (status) WHERE deleted_at IS NULL;
CREATE INDEX ix_buyers_name ON buyers (display_name) WHERE deleted_at IS NULL;


-- ----------------------------------------------------------------------------
-- 3. Buyer requirements — the brief
-- ----------------------------------------------------------------------------
-- A buyer states a brief, and the firm matches stock against it. Several briefs
-- per buyer are allowed and each has its own active flag, because a buyer's
-- requirement changes over time and the history of what they were looking for is
-- what explains why a property was or was not shown to them.
--
-- Every bound is nullable: "anywhere in Pune, two bedrooms or more" is a complete
-- brief, and a NOT NULL budget would force a number the buyer never gave.

CREATE TABLE buyer_requirements (
  id                uuid          PRIMARY KEY,
  buyer_id          uuid          NOT NULL REFERENCES buyers (id) ON DELETE CASCADE,
  property_type_id  uuid          REFERENCES property_types (id),

  preferred_city    varchar(120),
  preferred_locality varchar(120),

  min_budget        numeric(14, 2),
  max_budget        numeric(14, 2),
  min_bedrooms      integer,
  max_bedrooms      integer,
  min_area_sqft     numeric(12, 2),

  purpose           varchar(30)   NOT NULL DEFAULT 'OWN_OCCUPANCY',
  -- False rather than deleted, so a superseded brief stays readable as history.
  is_active         boolean       NOT NULL DEFAULT true,
  notes             varchar(1000),

  created_at        timestamp     NOT NULL,
  updated_at        timestamp     NOT NULL,
  created_by        uuid,
  updated_by        uuid,
  deleted_at        timestamp,

  CONSTRAINT ck_buyer_requirements_purpose CHECK (purpose IN (
    'OWN_OCCUPANCY', 'INVESTMENT', 'LETTING', 'REDEVELOPMENT'
  )),
  CONSTRAINT ck_buyer_requirements_budget_range CHECK (
    min_budget IS NULL OR max_budget IS NULL OR max_budget >= min_budget
  ),
  CONSTRAINT ck_buyer_requirements_bedroom_range CHECK (
    min_bedrooms IS NULL OR max_bedrooms IS NULL OR max_bedrooms >= min_bedrooms
  ),
  CONSTRAINT ck_buyer_requirements_budget_non_negative CHECK (
    (min_budget IS NULL OR min_budget >= 0) AND (max_budget IS NULL OR max_budget >= 0)
  ),
  CONSTRAINT ck_buyer_requirements_area_positive CHECK (min_area_sqft IS NULL OR min_area_sqft > 0)
);

CREATE INDEX ix_buyer_requirements_buyer ON buyer_requirements (buyer_id) WHERE deleted_at IS NULL;
CREATE INDEX ix_buyer_requirements_active ON buyer_requirements (buyer_id)
  WHERE is_active = true AND deleted_at IS NULL;
