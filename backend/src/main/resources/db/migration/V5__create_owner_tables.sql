-- ============================================================================
-- V5 - Owners and property ownership
-- ============================================================================
-- Who owns the asset, and in what proportion. Ownership is a relationship with a
-- percentage rather than a column on the property, because a portfolio is
-- routinely held by several parties - a family, a company and a development
-- partner on one title - and because a holding has a start and an end date.
--
-- The percentage is what makes owner reporting real: an owner's share of a
-- portfolio's value and income is derived from these rows, not stored and
-- maintained by hand. It is constrained to (0, 100] per row, and the service
-- layer additionally refuses to leave a property's active holdings summing to
-- anything other than 100 - a rule that cannot be expressed as a single-column
-- check because it spans rows.
-- ============================================================================


-- ----------------------------------------------------------------------------
-- 1. Owners
-- ----------------------------------------------------------------------------

CREATE TABLE owners (
  id                 uuid         PRIMARY KEY,
  owner_code         varchar(30)  NOT NULL UNIQUE,
  owner_type         varchar(30)  NOT NULL,
  display_name       varchar(200) NOT NULL,

  -- The natural person to call, when the owner is an entity rather than a person.
  primary_contact_name varchar(200),
  email              varchar(255),
  phone              varchar(30),
  alternate_phone    varchar(30),

  -- Identity and tax references. Deliberately opaque strings: this product
  -- records that a reference exists and what it is, and never validates it
  -- against a jurisdiction.
  id_reference       varchar(60),
  tax_reference      varchar(60),

  -- Correspondence address.
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

  CONSTRAINT ck_owners_type CHECK (owner_type IN (
    'INDIVIDUAL', 'COMPANY', 'PARTNERSHIP', 'TRUST', 'JOINT_VENTURE', 'GOVERNMENT', 'OTHER'
  )),
  CONSTRAINT ck_owners_status CHECK (status IN ('ACTIVE', 'INACTIVE')),
  -- Matched lower-cased on lookup, so storing a mixed-case address would create a
  -- second owner nobody could find predictably.
  CONSTRAINT ck_owners_email_lower CHECK (email IS NULL OR email = lower(email))
);

CREATE INDEX ix_owners_status ON owners (status) WHERE deleted_at IS NULL;
CREATE INDEX ix_owners_name ON owners (display_name) WHERE deleted_at IS NULL;
CREATE INDEX ix_owners_type ON owners (owner_type) WHERE deleted_at IS NULL;


-- ----------------------------------------------------------------------------
-- 2. Property ownership — the holding, with its share and its period
-- ----------------------------------------------------------------------------

CREATE TABLE property_ownerships (
  id                    uuid         PRIMARY KEY,
  property_id           uuid         NOT NULL REFERENCES properties (id) ON DELETE CASCADE,
  owner_id              uuid         NOT NULL REFERENCES owners (id),

  -- (0, 100]: a zero holding is not a holding, and more than the whole is a data
  -- error. The per-property sum is enforced in the service layer, where it can be
  -- checked across rows inside one transaction.
  ownership_percentage  numeric(5, 2) NOT NULL,

  relationship          varchar(30)  NOT NULL DEFAULT 'CO_OWNER',

  effective_from        date         NOT NULL,
  -- Null means "still held". A closed holding is dated rather than deleted, so a
  -- past ownership is reportable rather than lost.
  effective_to          date,

  notes                 varchar(500),

  created_at            timestamp    NOT NULL,
  updated_at            timestamp    NOT NULL,
  created_by            uuid,
  updated_by            uuid,
  deleted_at            timestamp,

  CONSTRAINT ck_property_ownerships_percentage CHECK (
    ownership_percentage > 0 AND ownership_percentage <= 100
  ),
  CONSTRAINT ck_property_ownerships_relationship CHECK (relationship IN (
    'SOLE_OWNER', 'CO_OWNER', 'MAJORITY_HOLDER', 'MINORITY_HOLDER',
    'BENEFICIARY', 'DEVELOPMENT_PARTNER', 'LESSOR', 'OTHER'
  )),
  CONSTRAINT ck_property_ownerships_period CHECK (
    effective_to IS NULL OR effective_to >= effective_from
  )
);

CREATE INDEX ix_property_ownerships_property ON property_ownerships (property_id) WHERE deleted_at IS NULL;
CREATE INDEX ix_property_ownerships_owner ON property_ownerships (owner_id) WHERE deleted_at IS NULL;
-- The dashboard and owner reports read open holdings only.
CREATE INDEX ix_property_ownerships_open ON property_ownerships (property_id)
  WHERE effective_to IS NULL AND deleted_at IS NULL;
