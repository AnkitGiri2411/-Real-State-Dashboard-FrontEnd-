-- ============================================================================
-- V14 - Vendors, maintenance requests and maintenance status history
-- ============================================================================
-- Keeping the stock in condition. A maintenance request is raised against a
-- property or a unit, carries a priority and a category, and moves through
-- assignment, work and resolution.
--
-- A request can be handled by an empanelled vendor, by a member of the firm's own
-- facilities team, or - briefly - by nobody. All three are real states, so both
-- assignment targets are nullable and the check constraint requires neither.
-- What it does require is that a request marked ASSIGNED names somebody: a request
-- that claims to be assigned to no one is how work silently stops.
--
-- Cost is carried in two places on purpose. The request holds an estimate and the
-- actual, and the actual is what the expense ledger records against the property
-- in V15 through `maintenance_request_id`. That link is what lets a property's
-- maintenance cost be traced back to the faults that caused it.
-- ============================================================================


-- ----------------------------------------------------------------------------
-- 1. Vendors — the empanelled trades
-- ----------------------------------------------------------------------------

CREATE TABLE vendors (
  id             uuid         PRIMARY KEY,
  vendor_code    varchar(30)  NOT NULL UNIQUE,
  name           varchar(200) NOT NULL,
  category       varchar(30)  NOT NULL,

  contact_name   varchar(200),
  phone          varchar(30),
  alternate_phone varchar(30),
  email          varchar(255),
  tax_reference  varchar(60),

  address_line1  varchar(200),
  address_line2  varchar(200),
  locality       varchar(120),
  city           varchar(120),
  state          varchar(120),
  postal_code    varchar(20),
  country        varchar(80)  NOT NULL DEFAULT 'India',

  -- True where the vendor is on the firm's approved list. A non-empanelled vendor
  -- may still be used in an emergency, so this is a preference rather than a gate.
  is_empanelled  boolean      NOT NULL DEFAULT true,
  -- Rate the firm gives the vendor, 1-5. Nullable: an unrated vendor is a genuinely
  -- different state from a poorly rated one.
  rating         integer,
  payment_terms_days integer,

  status         varchar(20)  NOT NULL DEFAULT 'ACTIVE',
  notes          text,

  created_at     timestamp    NOT NULL,
  updated_at     timestamp    NOT NULL,
  created_by     uuid,
  updated_by     uuid,
  deleted_at     timestamp,

  CONSTRAINT ck_vendors_category CHECK (category IN (
    'PLUMBING', 'ELECTRICAL', 'HVAC', 'CIVIL', 'PAINTING', 'CARPENTRY',
    'PEST_CONTROL', 'HOUSEKEEPING', 'SECURITY', 'LIFT', 'FIRE_SAFETY',
    'LANDSCAPING', 'GENERAL', 'OTHER'
  )),
  CONSTRAINT ck_vendors_status CHECK (status IN ('ACTIVE', 'INACTIVE', 'SUSPENDED')),
  CONSTRAINT ck_vendors_rating CHECK (rating IS NULL OR rating BETWEEN 1 AND 5),
  CONSTRAINT ck_vendors_payment_terms CHECK (payment_terms_days IS NULL OR payment_terms_days BETWEEN 0 AND 365),
  CONSTRAINT ck_vendors_email_lower CHECK (email IS NULL OR email = lower(email))
);

CREATE INDEX ix_vendors_category ON vendors (category) WHERE deleted_at IS NULL;
CREATE INDEX ix_vendors_status ON vendors (status) WHERE deleted_at IS NULL;


-- ----------------------------------------------------------------------------
-- 2. Maintenance requests
-- ----------------------------------------------------------------------------

CREATE TABLE maintenance_requests (
  id                uuid          PRIMARY KEY,
  request_code      varchar(30)   NOT NULL UNIQUE,

  property_id       uuid          NOT NULL REFERENCES properties (id),
  unit_id           uuid          REFERENCES units (id),
  building_id       uuid          REFERENCES buildings (id),

  category          varchar(30)   NOT NULL,
  priority          varchar(20)   NOT NULL DEFAULT 'MEDIUM',
  status            varchar(20)   NOT NULL DEFAULT 'OPEN',

  title             varchar(200)  NOT NULL,
  description       text,

  -- Who reported it. An internal reporter is a staff account; a tenant may report
  -- through the firm, and either may be absent when a fault is found on
  -- inspection - so all three are optional and a plain name covers the rest.
  reported_by       uuid          REFERENCES users (id),
  reported_by_tenant_id uuid      REFERENCES tenants (id),
  reporter_name     varchar(200),

  -- Who is doing the work: an empanelled vendor, an internal colleague, or nobody.
  assigned_vendor_id uuid         REFERENCES vendors (id),
  assigned_to       uuid          REFERENCES users (id),

  reported_at       timestamp     NOT NULL,
  target_date       date,
  assigned_at       timestamp,
  started_at        timestamp,
  resolved_at       timestamp,
  closed_at         timestamp,

  estimated_cost    numeric(12, 2),
  actual_cost       numeric(12, 2),

  resolution_notes  varchar(2000),
  -- True when the cost is recoverable from the tenant under their lease.
  chargeable_to_tenant boolean  NOT NULL DEFAULT false,

  created_at        timestamp     NOT NULL,
  updated_at        timestamp     NOT NULL,
  created_by        uuid,
  updated_by        uuid,
  deleted_at        timestamp,

  CONSTRAINT ck_maintenance_requests_category CHECK (category IN (
    'PLUMBING', 'ELECTRICAL', 'HVAC', 'CIVIL', 'PAINTING', 'CARPENTRY',
    'PEST_CONTROL', 'HOUSEKEEPING', 'SECURITY', 'LIFT', 'FIRE_SAFETY',
    'LANDSCAPING', 'GENERAL', 'OTHER'
  )),
  CONSTRAINT ck_maintenance_requests_priority CHECK (priority IN ('LOW', 'MEDIUM', 'HIGH', 'URGENT')),
  CONSTRAINT ck_maintenance_requests_status CHECK (status IN (
    'OPEN', 'ASSIGNED', 'IN_PROGRESS', 'RESOLVED', 'CLOSED', 'CANCELLED'
  )),
  CONSTRAINT ck_maintenance_requests_estimated_non_negative CHECK (
    estimated_cost IS NULL OR estimated_cost >= 0
  ),
  CONSTRAINT ck_maintenance_requests_actual_non_negative CHECK (actual_cost IS NULL OR actual_cost >= 0),

  -- An assigned request names its assignee. This is the constraint that stops work
  -- from sitting in ASSIGNED with nobody holding it.
  CONSTRAINT ck_maintenance_requests_assigned CHECK (
    (status = 'ASSIGNED' AND (assigned_vendor_id IS NOT NULL OR assigned_to IS NOT NULL)
      AND assigned_at IS NOT NULL)
    OR (status <> 'ASSIGNED')
  ),
  -- Work that has started records when, and a resolved or closed request records
  -- both the moment and what was done about it.
  CONSTRAINT ck_maintenance_requests_progress CHECK (
    (status = 'IN_PROGRESS' AND started_at IS NOT NULL)
    OR (status <> 'IN_PROGRESS')
  ),
  CONSTRAINT ck_maintenance_requests_resolution CHECK (
    (status IN ('RESOLVED', 'CLOSED') AND resolved_at IS NOT NULL AND resolution_notes IS NOT NULL)
    OR (status NOT IN ('RESOLVED', 'CLOSED'))
  ),
  -- Only a closed request has a closure moment, and it cannot precede resolution.
  CONSTRAINT ck_maintenance_requests_closed CHECK (
    (status = 'CLOSED' AND closed_at IS NOT NULL AND closed_at >= resolved_at)
    OR (status <> 'CLOSED' AND closed_at IS NULL)
  )
);

CREATE INDEX ix_maintenance_requests_property ON maintenance_requests (property_id) WHERE deleted_at IS NULL;
CREATE INDEX ix_maintenance_requests_unit ON maintenance_requests (unit_id)
  WHERE unit_id IS NOT NULL AND deleted_at IS NULL;
CREATE INDEX ix_maintenance_requests_status ON maintenance_requests (status) WHERE deleted_at IS NULL;
CREATE INDEX ix_maintenance_requests_priority ON maintenance_requests (priority) WHERE deleted_at IS NULL;
CREATE INDEX ix_maintenance_requests_vendor ON maintenance_requests (assigned_vendor_id)
  WHERE assigned_vendor_id IS NOT NULL AND deleted_at IS NULL;
CREATE INDEX ix_maintenance_requests_assignee ON maintenance_requests (assigned_to)
  WHERE assigned_to IS NOT NULL AND deleted_at IS NULL;
-- The open-work board reads only what is still outstanding, ordered by the moment
-- it was reported. Closing that set into the index is what keeps it small.
CREATE INDEX ix_maintenance_requests_open ON maintenance_requests (reported_at)
  WHERE status IN ('OPEN', 'ASSIGNED', 'IN_PROGRESS') AND deleted_at IS NULL;


-- ----------------------------------------------------------------------------
-- 3. Status history
-- ----------------------------------------------------------------------------

CREATE TABLE maintenance_status_history (
  id           uuid        PRIMARY KEY,
  request_id   uuid        NOT NULL REFERENCES maintenance_requests (id) ON DELETE CASCADE,
  from_status  varchar(20),
  to_status    varchar(20) NOT NULL,
  reason       varchar(500),
  changed_by   uuid        REFERENCES users (id),
  changed_at   timestamp   NOT NULL
);

CREATE INDEX ix_maintenance_status_history_request ON maintenance_status_history (request_id, changed_at DESC);
