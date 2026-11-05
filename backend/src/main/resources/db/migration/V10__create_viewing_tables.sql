-- ============================================================================
-- V10 - Viewings and viewing status history
-- ============================================================================
-- A viewing is an appointment to inspect a specific property or unit, with a
-- specific prospect, run by a specific agent. It is the point at which an
-- enquiry becomes a genuine buying or letting intention.
--
-- The prospect may be a lead, a buyer or a tenant - all three are real, and which
-- one it is depends on how far the relationship has progressed. A single
-- `prospect_id` would need a type column and no referential integrity; three
-- nullable foreign keys with a check that at least one is present keeps the
-- database able to enforce that the prospect exists.
--
-- Feedback and the interested flag are recorded per viewing rather than on the
-- lead, because a prospect's reaction to one property says nothing about the next
-- one - and it is that per-viewing reaction that makes a viewing useful evidence.
-- ============================================================================


-- ----------------------------------------------------------------------------
-- 1. Viewings
-- ----------------------------------------------------------------------------

CREATE TABLE viewings (
  id                uuid          PRIMARY KEY,
  viewing_code      varchar(30)   NOT NULL UNIQUE,

  property_id       uuid          NOT NULL REFERENCES properties (id),
  unit_id           uuid          REFERENCES units (id),

  -- The prospect. At least one of the three is required; see the header.
  lead_id           uuid          REFERENCES leads (id),
  buyer_id          uuid          REFERENCES buyers (id),
  tenant_id         uuid          REFERENCES tenants (id),

  -- The prospect's own name, for a walk-in who is not yet any of the three.
  prospect_name     varchar(200),
  prospect_phone    varchar(30),
  prospect_email    varchar(255),

  agent_id          uuid          REFERENCES agent_profiles (id),

  scheduled_at      timestamp     NOT NULL,
  duration_minutes  integer       NOT NULL DEFAULT 30,
  status            varchar(20)   NOT NULL DEFAULT 'SCHEDULED',

  -- Outcome. Feedback is required to complete a viewing, because a completed
  -- viewing with no reaction recorded is a lost opportunity rather than a result.
  feedback          text,
  rating            integer,
  interested        boolean,

  cancelled_reason  varchar(500),
  completed_at      timestamp,

  created_at        timestamp     NOT NULL,
  updated_at        timestamp     NOT NULL,
  created_by        uuid,
  updated_by        uuid,
  deleted_at        timestamp,

  CONSTRAINT ck_viewings_status CHECK (status IN (
    'SCHEDULED', 'CONFIRMED', 'COMPLETED', 'CANCELLED', 'NO_SHOW'
  )),
  CONSTRAINT ck_viewings_prospect CHECK (
    lead_id IS NOT NULL OR buyer_id IS NOT NULL OR tenant_id IS NOT NULL
    OR prospect_name IS NOT NULL
  ),
  CONSTRAINT ck_viewings_rating CHECK (rating IS NULL OR rating BETWEEN 1 AND 5),
  CONSTRAINT ck_viewings_duration CHECK (duration_minutes > 0 AND duration_minutes <= 1440),
  CONSTRAINT ck_viewings_email_lower CHECK (
    prospect_email IS NULL OR prospect_email = lower(prospect_email)
  ),
  -- A completed viewing records when it finished and must carry feedback. A
  -- cancelled or no-show viewing records why. Anything else has neither.
  CONSTRAINT ck_viewings_outcome CHECK (
    (status = 'COMPLETED' AND completed_at IS NOT NULL AND feedback IS NOT NULL)
    OR (status IN ('CANCELLED', 'NO_SHOW') AND cancelled_reason IS NOT NULL AND completed_at IS NULL)
    OR (status IN ('SCHEDULED', 'CONFIRMED') AND completed_at IS NULL)
  )
);

CREATE INDEX ix_viewings_property ON viewings (property_id) WHERE deleted_at IS NULL;
CREATE INDEX ix_viewings_unit ON viewings (unit_id) WHERE unit_id IS NOT NULL AND deleted_at IS NULL;
CREATE INDEX ix_viewings_lead ON viewings (lead_id) WHERE lead_id IS NOT NULL AND deleted_at IS NULL;
CREATE INDEX ix_viewings_agent ON viewings (agent_id) WHERE deleted_at IS NULL;
-- The viewing diary is read by date, and the dashboard reads only what is still
-- ahead of the reader.
CREATE INDEX ix_viewings_schedule ON viewings (scheduled_at) WHERE deleted_at IS NULL;
CREATE INDEX ix_viewings_upcoming ON viewings (scheduled_at)
  WHERE status IN ('SCHEDULED', 'CONFIRMED') AND deleted_at IS NULL;


-- ----------------------------------------------------------------------------
-- 2. Status history
-- ----------------------------------------------------------------------------

CREATE TABLE viewing_status_history (
  id         uuid        PRIMARY KEY,
  viewing_id uuid        NOT NULL REFERENCES viewings (id) ON DELETE CASCADE,
  from_status varchar(20),
  to_status  varchar(20) NOT NULL,
  reason     varchar(500),
  changed_by uuid        REFERENCES users (id),
  changed_at timestamp   NOT NULL
);

CREATE INDEX ix_viewing_status_history_viewing ON viewing_status_history (viewing_id, changed_at DESC);
