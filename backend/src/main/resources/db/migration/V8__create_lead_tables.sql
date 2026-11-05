-- ============================================================================
-- V8 - Lead sources, leads, activities, assignments and follow-ups
-- ============================================================================
-- The enquiry side of the business. A lead is a person or company that has
-- expressed interest in a property, a unit or a type of stock; the stage records
-- how far that interest has progressed, and the activities, assignments and
-- follow-ups record the work done on it.
--
-- The stage is a column with a check constraint rather than a `lead_stages`
-- table. The stages are fixed by the lifecycle the service enforces - a row in a
-- table would let an installation add a stage the state machine does not know how
-- to move through, which is a data change that silently breaks a workflow. The
-- permitted transitions live in `LeadLifecycle` and are refused server-side.
--
-- `lead_assignments` holds the history and `leads.owner_agent_id` holds the
-- current holder. That is a deliberate denormalisation: the pipeline board reads
-- the current holder on every row and must not join a history table to do it,
-- while "who had this lead in March" still has to be answerable.
-- ============================================================================


-- ----------------------------------------------------------------------------
-- 1. Lead sources — reference data
-- ----------------------------------------------------------------------------

CREATE TABLE lead_sources (
  id            uuid         PRIMARY KEY,
  code          varchar(40)  NOT NULL UNIQUE,
  name          varchar(120) NOT NULL,
  category      varchar(30)  NOT NULL,
  description   varchar(255),
  is_active     boolean      NOT NULL DEFAULT true,
  display_order integer      NOT NULL DEFAULT 0,
  created_at    timestamp    NOT NULL,
  updated_at    timestamp    NOT NULL,
  CONSTRAINT ck_lead_sources_category CHECK (category IN (
    'PORTAL', 'DIGITAL', 'REFERRAL', 'WALK_IN', 'BROKER', 'CAMPAIGN', 'SIGNAGE', 'CORPORATE', 'OTHER'
  ))
);

CREATE INDEX ix_lead_sources_active ON lead_sources (is_active);


-- ----------------------------------------------------------------------------
-- 2. Leads
-- ----------------------------------------------------------------------------

CREATE TABLE leads (
  id                  uuid          PRIMARY KEY,
  lead_code           varchar(30)   NOT NULL UNIQUE,
  lead_type           varchar(20)   NOT NULL DEFAULT 'RENTAL',
  source_id           uuid          REFERENCES lead_sources (id),
  stage               varchar(30)   NOT NULL DEFAULT 'NEW',
  priority            varchar(20)   NOT NULL DEFAULT 'MEDIUM',

  -- Contact. Held on the lead rather than pointing at a tenant or buyer, because
  -- most leads are neither yet - that is exactly what qualification decides.
  display_name        varchar(200)  NOT NULL,
  company_name        varchar(200),
  email               varchar(255),
  phone               varchar(30),
  alternate_phone     varchar(30),

  -- What they are looking for.
  property_type_id    uuid          REFERENCES property_types (id),
  preferred_city      varchar(120),
  preferred_locality  varchar(120),
  min_budget          numeric(14, 2),
  max_budget          numeric(14, 2),
  min_bedrooms        integer,
  requirement_notes   text,

  -- The specific thing they enquired about, when there is one. Nullable: a lead
  -- from a portal enquiry names a listing, a walk-in may not.
  property_id         uuid          REFERENCES properties (id),
  unit_id             uuid          REFERENCES units (id),

  -- The current holder. See the header for why this is denormalised.
  owner_agent_id      uuid          REFERENCES agent_profiles (id),
  office_id           uuid          REFERENCES offices (id),

  next_action         varchar(255),
  next_action_at      timestamp,

  -- The pipeline value. What the lead is worth if it converts, which is what the
  -- funnel's value column sums - never the budget maximum, which is a wish.
  expected_value      numeric(14, 2),

  -- Outcome.
  won_at              timestamp,
  lost_at             timestamp,
  lost_reason         varchar(500),

  -- Conversion targets. A lead that became a tenancy or a buyer keeps the link,
  -- which is what makes lead-conversion reporting possible at all.
  converted_tenant_id uuid          REFERENCES tenants (id),
  converted_buyer_id  uuid          REFERENCES buyers (id),
  converted_at        timestamp,

  created_at          timestamp     NOT NULL,
  updated_at          timestamp     NOT NULL,
  created_by          uuid,
  updated_by          uuid,
  deleted_at          timestamp,

  CONSTRAINT ck_leads_type CHECK (lead_type IN ('RENTAL', 'SALE', 'BOTH')),
  CONSTRAINT ck_leads_stage CHECK (stage IN (
    'NEW', 'CONTACTED', 'QUALIFIED', 'VIEWING', 'NEGOTIATION', 'WON', 'LOST'
  )),
  CONSTRAINT ck_leads_priority CHECK (priority IN ('LOW', 'MEDIUM', 'HIGH', 'URGENT')),
  CONSTRAINT ck_leads_email_lower CHECK (email IS NULL OR email = lower(email)),
  CONSTRAINT ck_leads_budget_range CHECK (
    min_budget IS NULL OR max_budget IS NULL OR max_budget >= min_budget
  ),
  CONSTRAINT ck_leads_budget_non_negative CHECK (
    (min_budget IS NULL OR min_budget >= 0) AND (max_budget IS NULL OR max_budget >= 0)
  ),
  CONSTRAINT ck_leads_expected_value_non_negative CHECK (expected_value IS NULL OR expected_value >= 0),
  CONSTRAINT ck_leads_min_bedrooms CHECK (min_bedrooms IS NULL OR min_bedrooms BETWEEN 0 AND 50),
  -- A lead cannot be both won and lost, and cannot have won without a won_at.
  CONSTRAINT ck_leads_outcome CHECK (
    (stage = 'WON' AND won_at IS NOT NULL AND lost_at IS NULL)
    OR (stage = 'LOST' AND lost_at IS NOT NULL AND won_at IS NULL)
    OR (stage NOT IN ('WON', 'LOST') AND won_at IS NULL AND lost_at IS NULL)
  ),
  -- A conversion is recorded with the record it produced, all or nothing.
  CONSTRAINT ck_leads_conversion CHECK (
    converted_at IS NULL
    OR converted_tenant_id IS NOT NULL
    OR converted_buyer_id IS NOT NULL
  )
);

CREATE INDEX ix_leads_stage ON leads (stage) WHERE deleted_at IS NULL;
CREATE INDEX ix_leads_agent ON leads (owner_agent_id) WHERE deleted_at IS NULL;
CREATE INDEX ix_leads_source ON leads (source_id) WHERE deleted_at IS NULL;
CREATE INDEX ix_leads_created ON leads (created_at DESC) WHERE deleted_at IS NULL;
-- The "needs attention" rail reads leads whose next action has come due. That is
-- a partial index on exactly the rows it can ever want.
CREATE INDEX ix_leads_next_action ON leads (next_action_at)
  WHERE next_action_at IS NOT NULL AND stage NOT IN ('WON', 'LOST') AND deleted_at IS NULL;
CREATE INDEX ix_leads_property ON leads (property_id) WHERE property_id IS NOT NULL AND deleted_at IS NULL;
-- The pipeline board groups by stage and sorts by value inside it.
CREATE INDEX ix_leads_pipeline ON leads (stage, expected_value DESC) WHERE deleted_at IS NULL;


-- ----------------------------------------------------------------------------
-- 3. Lead activities — the work done
-- ----------------------------------------------------------------------------

CREATE TABLE lead_activities (
  id            uuid         PRIMARY KEY,
  lead_id       uuid         NOT NULL REFERENCES leads (id) ON DELETE CASCADE,
  activity_type varchar(30)  NOT NULL,
  subject       varchar(255) NOT NULL,
  notes         text,
  occurred_at   timestamp    NOT NULL,
  duration_minutes integer,
  performed_by  uuid         REFERENCES users (id),
  -- Set when the activity is the record of a stage move, so the timeline shows
  -- why a lead advanced rather than merely that it did.
  from_stage    varchar(30),
  to_stage      varchar(30),
  created_at    timestamp    NOT NULL,
  updated_at    timestamp    NOT NULL,
  created_by    uuid,
  updated_by    uuid,
  deleted_at    timestamp,
  CONSTRAINT ck_lead_activities_type CHECK (activity_type IN (
    'CALL', 'EMAIL', 'MEETING', 'NOTE', 'SITE_VISIT', 'MESSAGE', 'STAGE_CHANGE', 'SYSTEM'
  )),
  CONSTRAINT ck_lead_activities_duration CHECK (duration_minutes IS NULL OR duration_minutes >= 0)
);

CREATE INDEX ix_lead_activities_lead ON lead_activities (lead_id, occurred_at DESC);
CREATE INDEX ix_lead_activities_type ON lead_activities (activity_type);
CREATE INDEX ix_lead_activities_by ON lead_activities (performed_by);


-- ----------------------------------------------------------------------------
-- 4. Lead assignments — the history
-- ----------------------------------------------------------------------------

CREATE TABLE lead_assignments (
  id            uuid         PRIMARY KEY,
  lead_id       uuid         NOT NULL REFERENCES leads (id) ON DELETE CASCADE,
  agent_id      uuid         NOT NULL REFERENCES agent_profiles (id),
  assigned_at   timestamp    NOT NULL,
  assigned_by   uuid         REFERENCES users (id),
  unassigned_at timestamp,
  reason        varchar(500),
  created_at    timestamp    NOT NULL,
  updated_at    timestamp    NOT NULL,
  created_by    uuid,
  updated_by    uuid,
  deleted_at    timestamp,
  CONSTRAINT ck_lead_assignments_period CHECK (unassigned_at IS NULL OR unassigned_at >= assigned_at)
);

CREATE INDEX ix_lead_assignments_lead ON lead_assignments (lead_id, assigned_at DESC);

-- One open assignment per lead. Two open rows would make "who holds this lead"
-- ambiguous, which is the one question an assignment exists to answer.
CREATE UNIQUE INDEX uq_lead_assignments_current ON lead_assignments (lead_id)
  WHERE unassigned_at IS NULL AND deleted_at IS NULL;

CREATE INDEX ix_lead_assignments_agent_open ON lead_assignments (agent_id)
  WHERE unassigned_at IS NULL AND deleted_at IS NULL;


-- ----------------------------------------------------------------------------
-- 5. Lead follow-ups — the diary
-- ----------------------------------------------------------------------------

CREATE TABLE lead_followups (
  id            uuid         PRIMARY KEY,
  lead_id       uuid         NOT NULL REFERENCES leads (id) ON DELETE CASCADE,
  due_at        timestamp    NOT NULL,
  channel       varchar(30)  NOT NULL DEFAULT 'CALL',
  notes         varchar(1000),
  status        varchar(20)  NOT NULL DEFAULT 'PENDING',
  assigned_to   uuid         REFERENCES agent_profiles (id),
  completed_at  timestamp,
  outcome       varchar(500),
  created_at    timestamp    NOT NULL,
  updated_at    timestamp    NOT NULL,
  created_by    uuid,
  updated_by    uuid,
  deleted_at    timestamp,
  CONSTRAINT ck_lead_followups_channel CHECK (channel IN (
    'CALL', 'EMAIL', 'MEETING', 'SITE_VISIT', 'MESSAGE'
  )),
  CONSTRAINT ck_lead_followups_status CHECK (status IN ('PENDING', 'DONE', 'CANCELLED', 'MISSED')),
  -- A completed follow-up must carry the moment it was completed; a pending one
  -- must not, or the overdue calculation would silently exclude it.
  CONSTRAINT ck_lead_followups_completion CHECK (
    (status = 'DONE' AND completed_at IS NOT NULL)
    OR (status <> 'DONE' AND completed_at IS NULL)
  )
);

CREATE INDEX ix_lead_followups_lead ON lead_followups (lead_id, due_at);
CREATE INDEX ix_lead_followups_due ON lead_followups (due_at) WHERE status = 'PENDING' AND deleted_at IS NULL;
CREATE INDEX ix_lead_followups_agent ON lead_followups (assigned_to) WHERE deleted_at IS NULL;


-- ----------------------------------------------------------------------------
-- 6. Reference data — lead sources
-- ----------------------------------------------------------------------------

INSERT INTO lead_sources (id, code, name, category, description, is_active, display_order, created_at, updated_at) VALUES
  (gen_random_uuid(), 'PORTAL_99ACRES',   '99acres',                 'PORTAL',    'Property portal enquiry.',              true,  10, now(), now()),
  (gen_random_uuid(), 'PORTAL_MAGICBRICKS','MagicBricks',            'PORTAL',    'Property portal enquiry.',              true,  20, now(), now()),
  (gen_random_uuid(), 'PORTAL_HOUSING',   'Housing.com',             'PORTAL',    'Property portal enquiry.',              true,  30, now(), now()),
  (gen_random_uuid(), 'WEBSITE',          'Firm website',            'DIGITAL',   'Enquiry from the firm''s own site.',    true,  40, now(), now()),
  (gen_random_uuid(), 'CAMPAIGN',         'Paid campaign',           'CAMPAIGN',  'Landing-page enquiry from paid media.', true,  50, now(), now()),
  (gen_random_uuid(), 'REFERRAL_CLIENT',  'Client referral',         'REFERRAL',  'Referred by an existing client.',       true,  60, now(), now()),
  (gen_random_uuid(), 'REFERRAL_RESIDENT','Resident referral',       'REFERRAL',  'Referred by a tenant in the building.', true,  70, now(), now()),
  (gen_random_uuid(), 'BROKER',           'Channel partner',         'BROKER',    'Introduced by a channel partner.',      true,  80, now(), now()),
  (gen_random_uuid(), 'WALK_IN',          'Walk-in',                 'WALK_IN',   'Enquiry at the office or a site.',      true,  90, now(), now()),
  (gen_random_uuid(), 'SIGNAGE',          'Site signage',            'SIGNAGE',   'Enquiry from a board on the building.', true, 100, now(), now()),
  (gen_random_uuid(), 'CORPORATE',        'Corporate account',       'CORPORATE', 'Direct approach from a company.',       true, 110, now(), now()),
  (gen_random_uuid(), 'EXISTING_TENANT',  'Existing tenant',         'REFERRAL',  'A current tenant taking more space.',   true, 120, now(), now()),
  (gen_random_uuid(), 'OTHER',            'Other',                   'OTHER',     'Not attributable to a known source.',   false,130, now(), now());


-- ----------------------------------------------------------------------------
-- 7. Guard
-- ----------------------------------------------------------------------------

DO $$
DECLARE
  source_count integer;
BEGIN
  SELECT count(*) INTO source_count FROM lead_sources;
  IF source_count < 12 THEN
    RAISE EXCEPTION 'Expected at least 12 lead sources, found %', source_count;
  END IF;
END $$;
