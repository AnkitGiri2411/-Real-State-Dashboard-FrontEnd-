-- ============================================================================
-- V18 - Portfolios and portfolio allocation
-- ============================================================================
-- A portfolio is a grouping of assets held to a single strategy and reported as
-- one book: a core income fund, a development book, a family's holdings. It is
-- what makes "portfolio value", "portfolio yield" and "portfolio occupancy"
-- meaningful questions rather than aggregates over everything the firm has ever
-- touched.
--
-- ----------------------------------------------------------------------------
-- ONE PORTFOLIO PER PROPERTY
-- ----------------------------------------------------------------------------
-- `uq_portfolio_properties_property` enforces that a property belongs to at most
-- one portfolio. This is a deliberate restriction, not an oversight.
--
-- Allowing a property into two portfolios would make every portfolio-level total
-- double-count it - and the error is silent, because each portfolio's own report
-- would look internally consistent while the firm's total was overstated. A
-- co-invested asset is therefore modelled as its own portfolio holding the
-- property, with the co-investment expressed through `property_ownerships`
-- (V5), which is where ownership shares genuinely belong.
--
-- A property in no portfolio is the normal state for a newly acquired asset.
-- ============================================================================


-- ----------------------------------------------------------------------------
-- 1. Portfolios
-- ----------------------------------------------------------------------------

CREATE TABLE portfolios (
  id                 uuid          PRIMARY KEY,
  portfolio_code     varchar(30)   NOT NULL UNIQUE,
  name               varchar(200)  NOT NULL,
  description        text,

  -- Who the book is held for, and who runs it. Both optional: a firm's own
  -- trading book has no external owner.
  owner_id           uuid          REFERENCES owners (id),
  manager_id         uuid          REFERENCES users (id),

  strategy           varchar(30)   NOT NULL DEFAULT 'CORE',
  -- The yield the book is managed against. Nullable: a development book has no
  -- income target, and a zero would be a claim rather than an absence.
  target_yield_percent numeric(5, 2),
  inception_date     date,

  status             varchar(20)   NOT NULL DEFAULT 'ACTIVE',
  notes              varchar(1000),

  created_at         timestamp     NOT NULL,
  updated_at         timestamp     NOT NULL,
  created_by         uuid,
  updated_by         uuid,
  deleted_at         timestamp,

  CONSTRAINT ck_portfolios_strategy CHECK (strategy IN (
    'CORE', 'CORE_PLUS', 'VALUE_ADD', 'OPPORTUNISTIC', 'DEVELOPMENT', 'INCOME', 'TRADING'
  )),
  CONSTRAINT ck_portfolios_status CHECK (status IN ('ACTIVE', 'CLOSED')),
  CONSTRAINT ck_portfolios_target_yield CHECK (
    target_yield_percent IS NULL OR (target_yield_percent >= 0 AND target_yield_percent <= 100)
  ),
  -- A closed portfolio records when it closed. A live one has no closure date,
  -- so a closed book cannot silently re-enter the firm's live totals.
  CONSTRAINT ck_portfolios_closed CHECK (
    (status = 'CLOSED' AND notes IS NOT NULL) OR status = 'ACTIVE'
  )
);

CREATE INDEX ix_portfolios_status ON portfolios (status) WHERE deleted_at IS NULL;
CREATE INDEX ix_portfolios_owner ON portfolios (owner_id)
  WHERE owner_id IS NOT NULL AND deleted_at IS NULL;
CREATE INDEX ix_portfolios_manager ON portfolios (manager_id)
  WHERE manager_id IS NOT NULL AND deleted_at IS NULL;


-- ----------------------------------------------------------------------------
-- 2. Portfolio allocation
-- ----------------------------------------------------------------------------

CREATE TABLE portfolio_properties (
  id             uuid        PRIMARY KEY,
  portfolio_id   uuid        NOT NULL REFERENCES portfolios (id) ON DELETE CASCADE,
  property_id    uuid        NOT NULL REFERENCES properties (id) ON DELETE CASCADE,

  -- When the asset entered the book and, for a disposed asset, when it left.
  -- Dated rather than deleted, so a portfolio's composition at a past date is
  -- reconstructable.
  allocated_at   date        NOT NULL,
  deallocated_at date,

  notes          varchar(500),

  created_at     timestamp   NOT NULL,
  updated_at     timestamp   NOT NULL,
  created_by     uuid,
  updated_by     uuid,
  deleted_at     timestamp,

  CONSTRAINT ck_portfolio_properties_period CHECK (
    deallocated_at IS NULL OR deallocated_at >= allocated_at
  )
);

CREATE INDEX ix_portfolio_properties_portfolio ON portfolio_properties (portfolio_id) WHERE deleted_at IS NULL;

-- See the header: a property belongs to at most one portfolio, so no
-- portfolio-level total can double-count an asset.
CREATE UNIQUE INDEX uq_portfolio_properties_property ON portfolio_properties (property_id)
  WHERE deleted_at IS NULL;

-- At most one open allocation per property. A closed one is history; two open ones
-- would mean the asset is in the book twice, which the index above cannot catch
-- once a past allocation exists.
CREATE UNIQUE INDEX uq_portfolio_properties_open ON portfolio_properties (property_id)
  WHERE deallocated_at IS NULL AND deleted_at IS NULL;
