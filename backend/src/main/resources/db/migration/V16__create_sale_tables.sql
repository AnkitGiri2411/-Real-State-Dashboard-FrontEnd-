-- ============================================================================
-- V16 - Sales opportunities, offers and sale stage history
-- ============================================================================
-- The capital side of the business: selling an asset rather than letting it. The
-- pipeline runs LEAD -> OFFER -> NEGOTIATION -> UNDER_CONTRACT -> CLOSED, or LOST
-- from any point before contract.
--
-- A sale is a separate record from the listing it came through, because a sale is
-- a negotiation with one buyer about one asset while a listing is an offer to the
-- market. One listing can produce several offers from several buyers, and only one
-- of them becomes the sale - which is why `offers` is a table rather than a single
-- `offer_amount` column.
--
-- The final migration in this file adds the sale reference to `payments`. That is
-- deliberate: the payment ledger was created in V13, before sales existed, and a
-- migration that extends an earlier table is the honest way to model a dependency
-- that genuinely runs in that direction - rather than pre-creating a nullable
-- column for a table that did not yet exist.
-- ============================================================================


-- ----------------------------------------------------------------------------
-- 1. Sales opportunities
-- ----------------------------------------------------------------------------

CREATE TABLE sales_opportunities (
  id                uuid          PRIMARY KEY,
  sale_code         varchar(30)   NOT NULL UNIQUE,

  property_id       uuid          NOT NULL REFERENCES properties (id),
  -- Set when the sale is of a specific unit rather than of the whole asset.
  unit_id           uuid          REFERENCES units (id),
  buyer_id          uuid          NOT NULL REFERENCES buyers (id),
  -- The enquiry this grew out of, when it grew out of one.
  lead_id           uuid          REFERENCES leads (id),

  stage             varchar(20)   NOT NULL DEFAULT 'LEAD',

  asking_price      numeric(14, 2),
  expected_price    numeric(14, 2),
  -- The price the sale actually closed at. Only a CLOSED sale has one.
  agreed_price      numeric(14, 2),

  expected_close_date date,
  closed_at         timestamp,
  lost_at           timestamp,
  lost_reason       varchar(500),

  owner_agent_id    uuid          REFERENCES agent_profiles (id),
  office_id         uuid          REFERENCES offices (id),

  notes             text,

  created_at        timestamp     NOT NULL,
  updated_at        timestamp     NOT NULL,
  created_by        uuid,
  updated_by        uuid,
  deleted_at        timestamp,

  CONSTRAINT ck_sales_opportunities_stage CHECK (stage IN (
    'LEAD', 'OFFER', 'NEGOTIATION', 'UNDER_CONTRACT', 'CLOSED', 'LOST'
  )),
  CONSTRAINT ck_sales_opportunities_asking_non_negative CHECK (asking_price IS NULL OR asking_price >= 0),
  CONSTRAINT ck_sales_opportunities_expected_non_negative CHECK (expected_price IS NULL OR expected_price >= 0),
  CONSTRAINT ck_sales_opportunities_agreed_non_negative CHECK (agreed_price IS NULL OR agreed_price >= 0),
  -- A closed sale records the price and the moment. A lost one records why. Any
  -- other stage has neither, so a pipeline figure cannot accidentally include a
  -- deal that has already concluded.
  CONSTRAINT ck_sales_opportunities_outcome CHECK (
    (stage = 'CLOSED' AND agreed_price IS NOT NULL AND closed_at IS NOT NULL
      AND lost_at IS NULL AND lost_reason IS NULL)
    OR (stage = 'LOST' AND lost_at IS NOT NULL AND lost_reason IS NOT NULL
      AND closed_at IS NULL AND agreed_price IS NULL)
    OR (stage NOT IN ('CLOSED', 'LOST') AND closed_at IS NULL AND lost_at IS NULL
      AND agreed_price IS NULL)
  )
);

CREATE INDEX ix_sales_opportunities_stage ON sales_opportunities (stage) WHERE deleted_at IS NULL;
CREATE INDEX ix_sales_opportunities_property ON sales_opportunities (property_id) WHERE deleted_at IS NULL;
CREATE INDEX ix_sales_opportunities_buyer ON sales_opportunities (buyer_id) WHERE deleted_at IS NULL;
CREATE INDEX ix_sales_opportunities_agent ON sales_opportunities (owner_agent_id) WHERE deleted_at IS NULL;
CREATE INDEX ix_sales_opportunities_created ON sales_opportunities (created_at DESC) WHERE deleted_at IS NULL;
-- The pipeline board reads open stages and sorts by value within them.
CREATE INDEX ix_sales_opportunities_pipeline ON sales_opportunities (stage, expected_price DESC)
  WHERE stage NOT IN ('CLOSED', 'LOST') AND deleted_at IS NULL;

-- One live opportunity per buyer per unit-property: a buyer cannot be negotiating
-- the same asset twice, and two open rows would double-count the pipeline.
CREATE UNIQUE INDEX uq_sales_opportunities_open ON sales_opportunities (property_id, COALESCE(unit_id, '00000000-0000-0000-0000-000000000000'::uuid), buyer_id)
  WHERE stage NOT IN ('CLOSED', 'LOST') AND deleted_at IS NULL;


-- ----------------------------------------------------------------------------
-- 2. Offers
-- ----------------------------------------------------------------------------
-- Every offer made on an opportunity, in order. A negotiation is a sequence, and
-- keeping the sequence is what shows whether a deal is moving or stuck - a fact a
-- single "current offer" column cannot express.

CREATE TABLE offers (
  id              uuid          PRIMARY KEY,
  offer_number    varchar(30)   NOT NULL UNIQUE,
  sale_id         uuid          NOT NULL REFERENCES sales_opportunities (id) ON DELETE CASCADE,

  amount          numeric(14, 2) NOT NULL,
  offered_at      timestamp     NOT NULL,
  -- Who put the offer forward: the buyer through the firm, or the firm as a
  -- counter. Both are real and the distinction is the substance of a negotiation.
  direction       varchar(20)   NOT NULL DEFAULT 'BUYER',

  status          varchar(20)   NOT NULL DEFAULT 'SUBMITTED',
  expires_at      timestamp,
  responded_at    timestamp,
  responded_by    uuid          REFERENCES users (id),
  response_notes  varchar(1000),
  conditions      text,

  created_at      timestamp     NOT NULL,
  updated_at      timestamp     NOT NULL,
  created_by      uuid,
  updated_by      uuid,
  deleted_at      timestamp,

  CONSTRAINT ck_offers_direction CHECK (direction IN ('BUYER', 'FIRM')),
  CONSTRAINT ck_offers_status CHECK (status IN (
    'SUBMITTED', 'COUNTERED', 'ACCEPTED', 'REJECTED', 'WITHDRAWN', 'EXPIRED'
  )),
  CONSTRAINT ck_offers_amount_positive CHECK (amount > 0),
  CONSTRAINT ck_offers_expiry CHECK (expires_at IS NULL OR expires_at > offered_at),
  -- A responded offer records when it was answered. An open one has no response.
  CONSTRAINT ck_offers_response CHECK (
    (status IN ('SUBMITTED', 'WITHDRAWN') AND responded_at IS NULL)
    OR (status IN ('COUNTERED', 'ACCEPTED', 'REJECTED', 'EXPIRED') AND responded_at IS NOT NULL)
  )
);

CREATE INDEX ix_offers_sale ON offers (sale_id, offered_at DESC) WHERE deleted_at IS NULL;
CREATE INDEX ix_offers_status ON offers (status) WHERE deleted_at IS NULL;

-- One accepted offer per sale: acceptance is what ends the negotiation, so two
-- accepted offers on one sale would mean the asset was promised twice.
CREATE UNIQUE INDEX uq_offers_accepted_per_sale ON offers (sale_id)
  WHERE status = 'ACCEPTED' AND deleted_at IS NULL;


-- ----------------------------------------------------------------------------
-- 3. Stage history
-- ----------------------------------------------------------------------------

CREATE TABLE sale_stage_history (
  id         uuid        PRIMARY KEY,
  sale_id    uuid        NOT NULL REFERENCES sales_opportunities (id) ON DELETE CASCADE,
  from_stage varchar(20),
  to_stage   varchar(20) NOT NULL,
  reason     varchar(500),
  changed_by uuid        REFERENCES users (id),
  changed_at timestamp   NOT NULL
);

CREATE INDEX ix_sale_stage_history_sale ON sale_stage_history (sale_id, changed_at DESC);


-- ----------------------------------------------------------------------------
-- 4. Extend the payment ledger with its sale reference
-- ----------------------------------------------------------------------------
-- A receipt settles either rent (through its lease) or consideration on a sale.
-- The ledger was created in V13, before this table existed; extending it here
-- keeps the foreign key real instead of leaving a dangling identifier.

ALTER TABLE payments
  ADD COLUMN sale_id uuid REFERENCES sales_opportunities (id);

COMMENT ON COLUMN payments.sale_id IS
  'Set when the receipt is consideration on a sale rather than a rent payment. Added by V16, which creates sales_opportunities.';

CREATE INDEX ix_payments_sale ON payments (sale_id)
  WHERE sale_id IS NOT NULL AND deleted_at IS NULL;

-- A receipt settles rent or a sale, not both, and not neither. Now that the sale
-- reference exists, that can be enforced rather than merely intended.
ALTER TABLE payments
  ADD CONSTRAINT ck_payments_settles_one_thing CHECK (
    (lease_id IS NOT NULL AND sale_id IS NULL)
    OR (sale_id IS NOT NULL AND lease_id IS NULL)
  );
