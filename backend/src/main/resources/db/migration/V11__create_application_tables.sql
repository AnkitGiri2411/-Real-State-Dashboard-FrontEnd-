-- ============================================================================
-- V11 - Rental applications, screening results and application status history
-- ============================================================================
-- A rental application is a formal request for a specific unit, with the terms
-- the applicant proposes and the checks the firm runs before deciding.
--
-- Screening results are separate rows rather than columns on the application,
-- because a firm runs several distinct checks - identity, employment, income, a
-- previous landlord's reference - and each has its own outcome, its own evidence
-- and its own date. Folding them into columns would cap the number of checks at
-- whatever the schema happened to guess, and would lose who ran each one.
--
-- A decision records who made it and when, and an approval cannot exist without
-- both. That is what makes "who approved this tenant" answerable after the fact,
-- which is the question an audit actually asks.
-- ============================================================================


-- ----------------------------------------------------------------------------
-- 1. Rental applications
-- ----------------------------------------------------------------------------

CREATE TABLE rental_applications (
  id                  uuid          PRIMARY KEY,
  application_code    varchar(30)   NOT NULL UNIQUE,

  property_id         uuid          NOT NULL REFERENCES properties (id),
  unit_id             uuid          NOT NULL REFERENCES units (id),

  -- Who is applying. Linked when the applicant is already known to the firm.
  lead_id             uuid          REFERENCES leads (id),
  tenant_id           uuid          REFERENCES tenants (id),

  applicant_type      varchar(20)   NOT NULL DEFAULT 'INDIVIDUAL',
  applicant_name      varchar(200)  NOT NULL,
  applicant_email     varchar(255),
  applicant_phone     varchar(30),

  status              varchar(20)   NOT NULL DEFAULT 'SUBMITTED',

  -- The terms the applicant is asking for. Kept apart from the lease that
  -- follows: what was applied for and what was signed genuinely differ, and
  -- conflating them loses the negotiation.
  proposed_start_date date,
  proposed_end_date   date,
  proposed_rent       numeric(12, 2),
  proposed_deposit    numeric(12, 2),

  -- Circumstances the decision is made on.
  occupancy_count     integer,
  has_pets            boolean       NOT NULL DEFAULT false,
  employment_type     varchar(30),
  annual_income       numeric(14, 2),
  notes               text,

  submitted_at        timestamp     NOT NULL,
  decided_at          timestamp,
  decided_by          uuid          REFERENCES users (id),
  decision_notes      varchar(1000),
  rejection_reason    varchar(500),

  created_at          timestamp     NOT NULL,
  updated_at          timestamp     NOT NULL,
  created_by          uuid,
  updated_by          uuid,
  deleted_at          timestamp,

  CONSTRAINT ck_rental_applications_status CHECK (status IN (
    'SUBMITTED', 'SCREENING', 'APPROVED', 'REJECTED', 'WITHDRAWN'
  )),
  CONSTRAINT ck_rental_applications_applicant_type CHECK (applicant_type IN (
    'INDIVIDUAL', 'COMPANY', 'TRUST', 'GOVERNMENT', 'OTHER'
  )),
  CONSTRAINT ck_rental_applications_employment CHECK (employment_type IS NULL OR employment_type IN (
    'SALARIED', 'SELF_EMPLOYED', 'BUSINESS_OWNER', 'STUDENT', 'RETIRED', 'OTHER'
  )),
  CONSTRAINT ck_rental_applications_email_lower CHECK (
    applicant_email IS NULL OR applicant_email = lower(applicant_email)
  ),
  CONSTRAINT ck_rental_applications_occupancy CHECK (occupancy_count IS NULL OR occupancy_count > 0),
  CONSTRAINT ck_rental_applications_income_non_negative CHECK (annual_income IS NULL OR annual_income >= 0),
  CONSTRAINT ck_rental_applications_rent_non_negative CHECK (proposed_rent IS NULL OR proposed_rent >= 0),
  CONSTRAINT ck_rental_applications_deposit_non_negative CHECK (proposed_deposit IS NULL OR proposed_deposit >= 0),
  CONSTRAINT ck_rental_applications_period CHECK (
    proposed_end_date IS NULL OR proposed_start_date IS NULL OR proposed_end_date > proposed_start_date
  ),
  -- A decision is recorded whole: who, when, and - if rejected - why.
  CONSTRAINT ck_rental_applications_decision CHECK (
    (status = 'APPROVED' AND decided_at IS NOT NULL AND decided_by IS NOT NULL AND rejection_reason IS NULL)
    OR (status = 'REJECTED' AND decided_at IS NOT NULL AND decided_by IS NOT NULL AND rejection_reason IS NOT NULL)
    OR (status IN ('SUBMITTED', 'SCREENING', 'WITHDRAWN') AND decided_at IS NULL)
  )
);

CREATE INDEX ix_rental_applications_status ON rental_applications (status) WHERE deleted_at IS NULL;
CREATE INDEX ix_rental_applications_unit ON rental_applications (unit_id) WHERE deleted_at IS NULL;
CREATE INDEX ix_rental_applications_property ON rental_applications (property_id) WHERE deleted_at IS NULL;
CREATE INDEX ix_rental_applications_tenant ON rental_applications (tenant_id)
  WHERE tenant_id IS NOT NULL AND deleted_at IS NULL;
CREATE INDEX ix_rental_applications_submitted ON rental_applications (submitted_at DESC) WHERE deleted_at IS NULL;

-- An applicant cannot hold two undecided applications for the same unit. Two open
-- rows would let the same applicant be approved twice for one flat, and would
-- make "who is in the running for this unit" ambiguous.
CREATE UNIQUE INDEX uq_rental_applications_open_on_unit ON rental_applications (unit_id, applicant_email)
  WHERE status IN ('SUBMITTED', 'SCREENING') AND applicant_email IS NOT NULL AND deleted_at IS NULL;


-- ----------------------------------------------------------------------------
-- 2. Screening results — one row per check
-- ----------------------------------------------------------------------------

CREATE TABLE application_screenings (
  id              uuid         PRIMARY KEY,
  application_id  uuid         NOT NULL REFERENCES rental_applications (id) ON DELETE CASCADE,
  check_type      varchar(30)  NOT NULL,
  status          varchar(20)  NOT NULL DEFAULT 'PENDING',

  -- A numeric score where the check produces one, so a firm that scores credit or
  -- affordability can rank applicants rather than only pass or fail them.
  score           integer,
  notes           varchar(1000),
  -- Where the evidence lives (a file reference, a provider's case id). The
  -- product records the reference; it does not store the report itself.
  evidence_reference varchar(255),

  performed_by    uuid         REFERENCES users (id),
  performed_at    timestamp,
  created_at      timestamp    NOT NULL,
  updated_at      timestamp    NOT NULL,
  created_by      uuid,
  updated_by      uuid,
  deleted_at      timestamp,

  CONSTRAINT ck_application_screenings_type CHECK (check_type IN (
    'IDENTITY', 'EMPLOYMENT', 'INCOME', 'CREDIT', 'REFERENCE_LANDLORD',
    'REFERENCE_EMPLOYER', 'BACKGROUND', 'AFFORDABILITY', 'OTHER'
  )),
  CONSTRAINT ck_application_screenings_status CHECK (status IN (
    'PENDING', 'PASSED', 'FAILED', 'WAIVED'
  )),
  CONSTRAINT ck_application_screenings_score CHECK (score IS NULL OR score BETWEEN 0 AND 1000),
  -- A completed check records who completed it and when. WAIVED is an explicit
  -- decision too, and records who made it - a waiver is not an absence of a check.
  CONSTRAINT ck_application_screenings_performed CHECK (
    (status = 'PENDING' AND performed_at IS NULL AND performed_by IS NULL)
    OR (status <> 'PENDING' AND performed_at IS NOT NULL AND performed_by IS NOT NULL)
  ),
  -- One row per check type per application: a second identity check on the same
  -- application is either a correction (update) or a duplicate.
  CONSTRAINT uq_application_screenings_check UNIQUE (application_id, check_type)
);

CREATE INDEX ix_application_screenings_application ON application_screenings (application_id);
CREATE INDEX ix_application_screenings_status ON application_screenings (status) WHERE deleted_at IS NULL;


-- ----------------------------------------------------------------------------
-- 3. Status history
-- ----------------------------------------------------------------------------

CREATE TABLE application_status_history (
  id             uuid        PRIMARY KEY,
  application_id uuid        NOT NULL REFERENCES rental_applications (id) ON DELETE CASCADE,
  from_status    varchar(20),
  to_status      varchar(20) NOT NULL,
  reason         varchar(500),
  changed_by     uuid        REFERENCES users (id),
  changed_at     timestamp   NOT NULL
);

CREATE INDEX ix_application_status_history_application
  ON application_status_history (application_id, changed_at DESC);
