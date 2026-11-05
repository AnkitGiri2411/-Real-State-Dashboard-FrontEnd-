-- ============================================================================
-- V12 - Leases, lease terms, status history and renewals
-- ============================================================================
-- A lease is the contract that makes a unit occupied. It is the single most
-- load-bearing record in the rent side of the product: occupancy, the rent roll,
-- collections, lease-expiry reporting and yield are all derived from it.
--
-- `lease_terms` holds the rent schedule - what the rent is, from when, and the
-- escalation that produced it. It is separate from the lease because the rent
-- changes during a tenancy while the contract does not, and because a rent roll
-- for a past month has to be answerable at the rate that actually applied then,
-- not at today's rate.
--
-- A renewal is a new lease, not an edit of the old one. The old lease keeps its
-- end date and moves to RENEWED; the new lease points back at it through
-- renewal_of_lease_id. That is what makes a tenancy's full history - across three
-- renewals and two rent revisions - reconstructable years later.
-- ============================================================================


-- ----------------------------------------------------------------------------
-- 1. Leases
-- ----------------------------------------------------------------------------

CREATE TABLE leases (
  id                    uuid          PRIMARY KEY,
  lease_code            varchar(30)   NOT NULL UNIQUE,

  property_id           uuid          NOT NULL REFERENCES properties (id),
  unit_id               uuid          NOT NULL REFERENCES units (id),
  tenant_id             uuid          NOT NULL REFERENCES tenants (id),
  -- The owner who is letting. Nullable: some stock is let on a firm's own
  -- account, and a missing landlord must not block a lease from existing.
  primary_owner_id      uuid          REFERENCES owners (id),

  status                varchar(20)   NOT NULL DEFAULT 'DRAFT',

  start_date            date          NOT NULL,
  end_date              date          NOT NULL,

  -- The headline rent and how it is billed. The authoritative figure for any
  -- given month is the lease_terms row in force on that date.
  rent_amount           numeric(12, 2) NOT NULL,
  rent_frequency        varchar(20)   NOT NULL DEFAULT 'MONTHLY',
  due_day               integer       NOT NULL DEFAULT 5,
  escalation_percent    numeric(5, 2),

  -- Deposit held is a liability, not income. It is tracked here and refunded on
  -- exit; it is never included in collected rent.
  deposit_amount        numeric(12, 2) NOT NULL DEFAULT 0,
  deposit_received_at   timestamp,
  deposit_refunded_amount numeric(12, 2),
  deposit_refunded_at   timestamp,

  notice_period_days    integer       NOT NULL DEFAULT 30,
  agreement_reference   varchar(120),

  signed_at             timestamp,
  activated_at          timestamp,
  terminated_at         timestamp,
  termination_reason    varchar(500),

  -- Set on a renewal lease, pointing at the lease it replaced.
  renewal_of_lease_id   uuid          REFERENCES leases (id),

  created_at            timestamp     NOT NULL,
  updated_at            timestamp     NOT NULL,
  created_by            uuid,
  updated_by            uuid,
  deleted_at            timestamp,

  CONSTRAINT ck_leases_status CHECK (status IN (
    'DRAFT', 'PENDING', 'ACTIVE', 'EXPIRING', 'RENEWED', 'TERMINATED', 'CANCELLED'
  )),
  CONSTRAINT ck_leases_frequency CHECK (rent_frequency IN ('MONTHLY', 'QUARTERLY', 'HALF_YEARLY', 'ANNUAL')),
  CONSTRAINT ck_leases_period CHECK (end_date > start_date),
  CONSTRAINT ck_leases_rent_positive CHECK (rent_amount > 0),
  CONSTRAINT ck_leases_deposit_non_negative CHECK (deposit_amount >= 0),
  CONSTRAINT ck_leases_escalation_non_negative CHECK (escalation_percent IS NULL OR escalation_percent >= 0),
  CONSTRAINT ck_leases_due_day CHECK (due_day BETWEEN 1 AND 28),
  CONSTRAINT ck_leases_notice CHECK (notice_period_days BETWEEN 0 AND 365),

  -- An active lease has been activated. A terminated one records when and why.
  -- Applying this at the row level means the rent roll cannot pick up a lease
  -- that was never activated, or silently drop the reason a tenancy ended.
  CONSTRAINT ck_leases_lifecycle CHECK (
    (status IN ('DRAFT', 'PENDING', 'CANCELLED') AND activated_at IS NULL AND terminated_at IS NULL)
    OR (status IN ('ACTIVE', 'EXPIRING', 'RENEWED') AND activated_at IS NOT NULL AND terminated_at IS NULL)
    OR (status = 'TERMINATED' AND activated_at IS NOT NULL AND terminated_at IS NOT NULL AND termination_reason IS NOT NULL)
  ),

  -- A deposit cannot be refunded without having been received, nor refunded twice.
  CONSTRAINT ck_leases_deposit_refund CHECK (
    (deposit_refunded_amount IS NULL AND deposit_refunded_at IS NULL)
    OR (deposit_refunded_amount IS NOT NULL AND deposit_refunded_at IS NOT NULL
        AND deposit_received_at IS NOT NULL AND deposit_refunded_amount <= deposit_amount)
  ),

  -- A lease cannot renew itself.
  CONSTRAINT ck_leases_renewal_not_self CHECK (renewal_of_lease_id IS NULL OR renewal_of_lease_id <> id)
);

CREATE INDEX ix_leases_status ON leases (status) WHERE deleted_at IS NULL;
CREATE INDEX ix_leases_unit ON leases (unit_id) WHERE deleted_at IS NULL;
CREATE INDEX ix_leases_property ON leases (property_id) WHERE deleted_at IS NULL;
CREATE INDEX ix_leases_tenant ON leases (tenant_id) WHERE deleted_at IS NULL;
CREATE INDEX ix_leases_owner ON leases (primary_owner_id) WHERE primary_owner_id IS NOT NULL AND deleted_at IS NULL;

-- The expiring-lease report and the "needs attention" rail both read leases that
-- are still running, ordered by when they end.
CREATE INDEX ix_leases_running_end ON leases (end_date)
  WHERE status IN ('ACTIVE', 'EXPIRING') AND deleted_at IS NULL;

-- One running lease per unit. This is the constraint that makes occupancy a fact
-- rather than an inference: a unit cannot be let to two tenants at once, so the
-- database refuses to record it.
CREATE UNIQUE INDEX uq_leases_one_running_per_unit ON leases (unit_id)
  WHERE status IN ('ACTIVE', 'EXPIRING') AND deleted_at IS NULL;


-- ----------------------------------------------------------------------------
-- 2. Lease terms — the rent schedule over time
-- ----------------------------------------------------------------------------

CREATE TABLE lease_terms (
  id              uuid          PRIMARY KEY,
  lease_id        uuid          NOT NULL REFERENCES leases (id) ON DELETE CASCADE,
  term_number     integer       NOT NULL,

  effective_from  date          NOT NULL,
  effective_to    date,

  rent_amount     numeric(12, 2) NOT NULL,
  escalation_percent numeric(5, 2),
  notes           varchar(500),

  created_at      timestamp     NOT NULL,
  updated_at      timestamp     NOT NULL,
  created_by      uuid,
  updated_by      uuid,
  deleted_at      timestamp,

  CONSTRAINT uq_lease_terms_number UNIQUE (lease_id, term_number),
  CONSTRAINT ck_lease_terms_rent_positive CHECK (rent_amount > 0),
  CONSTRAINT ck_lease_terms_period CHECK (effective_to IS NULL OR effective_to >= effective_from),
  CONSTRAINT ck_lease_terms_escalation_non_negative CHECK (
    escalation_percent IS NULL OR escalation_percent >= 0
  )
);

CREATE INDEX ix_lease_terms_lease ON lease_terms (lease_id, effective_from);

-- One open-ended term per lease, and one term per effective date - otherwise
-- "the rent in force on 1 March" has more than one answer.
CREATE UNIQUE INDEX uq_lease_terms_open ON lease_terms (lease_id)
  WHERE effective_to IS NULL AND deleted_at IS NULL;
CREATE UNIQUE INDEX uq_lease_terms_effective ON lease_terms (lease_id, effective_from)
  WHERE deleted_at IS NULL;


-- ----------------------------------------------------------------------------
-- 3. Status history
-- ----------------------------------------------------------------------------

CREATE TABLE lease_status_history (
  id         uuid        PRIMARY KEY,
  lease_id   uuid        NOT NULL REFERENCES leases (id) ON DELETE CASCADE,
  from_status varchar(20),
  to_status  varchar(20) NOT NULL,
  reason     varchar(500),
  changed_by uuid        REFERENCES users (id),
  changed_at timestamp   NOT NULL
);

CREATE INDEX ix_lease_status_history_lease ON lease_status_history (lease_id, changed_at DESC);


-- ----------------------------------------------------------------------------
-- 4. Renewals
-- ----------------------------------------------------------------------------
-- The renewal conversation, tracked separately from the leases themselves. A
-- renewal offer exists before the new lease does, and its outcome is what tells
-- the firm whether a tenancy is continuing - which is the single most valuable
-- thing to know about a lease that is about to end.

CREATE TABLE lease_renewals (
  id               uuid          PRIMARY KEY,
  lease_id         uuid          NOT NULL REFERENCES leases (id) ON DELETE CASCADE,
  -- Set once the renewal is agreed and the new lease drafted.
  renewed_lease_id uuid          REFERENCES leases (id),

  status           varchar(20)   NOT NULL DEFAULT 'PENDING',

  proposed_at      timestamp,
  proposed_rent    numeric(12, 2),
  proposed_start_date date,
  proposed_end_date date,

  decided_at       timestamp,
  decided_by       uuid          REFERENCES users (id),
  decision_notes   varchar(1000),

  created_at       timestamp     NOT NULL,
  updated_at       timestamp     NOT NULL,
  created_by       uuid,
  updated_by       uuid,
  deleted_at       timestamp,

  CONSTRAINT ck_lease_renewals_status CHECK (status IN (
    'PENDING', 'OFFERED', 'ACCEPTED', 'DECLINED', 'LAPSED'
  )),
  CONSTRAINT ck_lease_renewals_rent_non_negative CHECK (proposed_rent IS NULL OR proposed_rent >= 0),
  CONSTRAINT ck_lease_renewals_period CHECK (
    proposed_end_date IS NULL OR proposed_start_date IS NULL OR proposed_end_date > proposed_start_date
  ),
  CONSTRAINT ck_lease_renewals_not_self CHECK (renewed_lease_id IS NULL OR renewed_lease_id <> lease_id),
  -- An accepted renewal names the lease it produced; a declined one records when
  -- the decision was taken.
  CONSTRAINT ck_lease_renewals_decision CHECK (
    (status = 'ACCEPTED' AND renewed_lease_id IS NOT NULL AND decided_at IS NOT NULL)
    OR (status = 'DECLINED' AND renewed_lease_id IS NULL AND decided_at IS NOT NULL)
    OR (status IN ('PENDING', 'OFFERED', 'LAPSED') AND decided_at IS NULL)
  )
);

CREATE INDEX ix_lease_renewals_lease ON lease_renewals (lease_id);
CREATE INDEX ix_lease_renewals_status ON lease_renewals (status) WHERE deleted_at IS NULL;

-- One live renewal conversation per lease.
CREATE UNIQUE INDEX uq_lease_renewals_open ON lease_renewals (lease_id)
  WHERE status IN ('PENDING', 'OFFERED') AND deleted_at IS NULL;
