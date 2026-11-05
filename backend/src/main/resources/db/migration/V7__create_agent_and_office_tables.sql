-- ============================================================================
-- V7 - Offices, agent profiles and property assignments
-- ============================================================================
-- An agent is a staff account that carries a portfolio. The account and the
-- profile are separate because they answer different questions: `users` is who
-- may sign in and what they may do, `agent_profiles` is who carries which stock,
-- under which licence, out of which office.
--
-- Not every staff account is an agent - accounts, administration and facilities
-- hold permissions but no portfolio - so the profile is a 1:1 extension that only
-- the letting and sales sides have. That is why a lead is assigned to an agent
-- profile rather than to a user: the assignment is about carrying stock, and
-- "who is responsible for this property" has to have an answer.
-- ============================================================================


-- ----------------------------------------------------------------------------
-- 1. Offices
-- ----------------------------------------------------------------------------

CREATE TABLE offices (
  id            uuid         PRIMARY KEY,
  office_code   varchar(30)  NOT NULL UNIQUE,
  name          varchar(150) NOT NULL,
  address_line1 varchar(200),
  address_line2 varchar(200),
  locality      varchar(120),
  city          varchar(120) NOT NULL,
  state         varchar(120),
  postal_code   varchar(20),
  country       varchar(80)  NOT NULL DEFAULT 'India',
  phone         varchar(30),
  email         varchar(255),
  -- True for the registered office, so reporting can attribute to a head office
  -- without matching on a name.
  is_head_office boolean     NOT NULL DEFAULT false,
  status        varchar(20)  NOT NULL DEFAULT 'ACTIVE',
  created_at    timestamp    NOT NULL,
  updated_at    timestamp    NOT NULL,
  created_by    uuid,
  updated_by    uuid,
  deleted_at    timestamp,
  CONSTRAINT ck_offices_status CHECK (status IN ('ACTIVE', 'INACTIVE')),
  CONSTRAINT ck_offices_email_lower CHECK (email IS NULL OR email = lower(email))
);

CREATE INDEX ix_offices_city ON offices (city) WHERE deleted_at IS NULL;

-- Exactly one head office, enforced by a partial unique index rather than by
-- convention: two rows claiming to be the registered office would silently
-- double-count in office-level reporting.
CREATE UNIQUE INDEX uq_offices_single_head ON offices ((is_head_office))
  WHERE is_head_office = true AND deleted_at IS NULL;


-- ----------------------------------------------------------------------------
-- 2. Agent profiles
-- ----------------------------------------------------------------------------

CREATE TABLE agent_profiles (
  id                uuid         PRIMARY KEY,
  -- One profile per staff account: the account is the person.
  user_id           uuid         NOT NULL UNIQUE REFERENCES users (id) ON DELETE CASCADE,
  agent_code        varchar(30)  NOT NULL UNIQUE,
  office_id         uuid         REFERENCES offices (id),
  manager_id        uuid         REFERENCES agent_profiles (id),

  specialisation    varchar(30)  NOT NULL DEFAULT 'BOTH',
  licence_number    varchar(60),
  -- The date the agent joined, used by the agent-performance report to pro-rate
  -- a target rather than comparing a new joiner against a full year.
  joined_on         date,
  monthly_target    numeric(14, 2),
  bio               varchar(1000),
  status            varchar(20)  NOT NULL DEFAULT 'ACTIVE',

  created_at        timestamp    NOT NULL,
  updated_at        timestamp    NOT NULL,
  created_by        uuid,
  updated_by        uuid,
  deleted_at        timestamp,

  CONSTRAINT ck_agent_profiles_specialisation CHECK (specialisation IN (
    'LETTING', 'SALES', 'BOTH', 'PROPERTY_MANAGEMENT', 'VALUATION'
  )),
  CONSTRAINT ck_agent_profiles_status CHECK (status IN ('ACTIVE', 'INACTIVE')),
  CONSTRAINT ck_agent_profiles_target_non_negative CHECK (monthly_target IS NULL OR monthly_target >= 0),
  -- An agent cannot manage themselves; that row would make any upward traversal
  -- a cycle.
  CONSTRAINT ck_agent_profiles_manager_not_self CHECK (manager_id IS NULL OR manager_id <> id)
);

CREATE INDEX ix_agent_profiles_office ON agent_profiles (office_id) WHERE deleted_at IS NULL;
CREATE INDEX ix_agent_profiles_status ON agent_profiles (status) WHERE deleted_at IS NULL;
CREATE INDEX ix_agent_profiles_specialisation ON agent_profiles (specialisation) WHERE deleted_at IS NULL;


-- ----------------------------------------------------------------------------
-- 3. Agent <-> property assignment
-- ----------------------------------------------------------------------------
-- Who carries which asset, and in what capacity. Dated rather than deleted, so
-- "who was responsible for this property last quarter" is answerable - which is
-- what an agent-performance report has to ask.

CREATE TABLE agent_property_assignments (
  id            uuid         PRIMARY KEY,
  agent_id      uuid         NOT NULL REFERENCES agent_profiles (id) ON DELETE CASCADE,
  property_id   uuid         NOT NULL REFERENCES properties (id) ON DELETE CASCADE,
  role          varchar(20)  NOT NULL DEFAULT 'PRIMARY',

  assigned_from date         NOT NULL,
  assigned_to   date,

  created_at    timestamp    NOT NULL,
  updated_at    timestamp    NOT NULL,
  created_by    uuid,
  updated_by    uuid,
  deleted_at    timestamp,

  CONSTRAINT ck_agent_property_assignments_role CHECK (role IN ('PRIMARY', 'SECONDARY', 'CO_AGENT')),
  CONSTRAINT ck_agent_property_assignments_period CHECK (assigned_to IS NULL OR assigned_to >= assigned_from)
);

CREATE INDEX ix_agent_property_assignments_agent ON agent_property_assignments (agent_id)
  WHERE deleted_at IS NULL;
CREATE INDEX ix_agent_property_assignments_property ON agent_property_assignments (property_id)
  WHERE deleted_at IS NULL;
-- The dashboard asks "who carries this property now", which reads only open rows.
CREATE INDEX ix_agent_property_assignments_open ON agent_property_assignments (property_id, role)
  WHERE assigned_to IS NULL AND deleted_at IS NULL;

-- One current primary agent per property. A second open PRIMARY row would make
-- "the property's agent" ambiguous, and every report that attributes a property
-- to a person would have to guess between them.
CREATE UNIQUE INDEX uq_agent_property_primary_open ON agent_property_assignments (property_id)
  WHERE role = 'PRIMARY' AND assigned_to IS NULL AND deleted_at IS NULL;
