-- ============================================================================
-- V15 - Expense categories and property expenses
-- ============================================================================
-- What the portfolio costs to hold. An expense is recorded against a property,
-- optionally against a unit and optionally against the maintenance request that
-- caused it.
--
-- ----------------------------------------------------------------------------
-- CAPITAL VERSUS OPERATING - the distinction net income depends on
-- ----------------------------------------------------------------------------
-- `expense_categories.is_capital` is the flag that keeps net income honest. A
-- replacement lift is capital: it improves the asset and is not a cost of the
-- period's letting. A lift repair is operating: it is. Adding them together would
-- overstate cost and understate the portfolio's running result, and the error
-- would be invisible because both are real expenses that happened.
--
-- Net income is therefore: collected rent, less operating expenses, with capital
-- expenditure excluded and reported separately. Deposits are excluded entirely -
-- a deposit held is a liability (see V13), not a cost or a revenue.
--
-- Recoverable expenses are tracked so a cost the firm can recharge under a lease
-- is visibly distinct from one it cannot. `recovered_amount` records what was
-- actually recharged, so the two figures are never conflated.
-- ============================================================================


-- ----------------------------------------------------------------------------
-- 1. Expense categories — reference data
-- ----------------------------------------------------------------------------

CREATE TABLE expense_categories (
  id            uuid         PRIMARY KEY,
  code          varchar(40)  NOT NULL UNIQUE,
  name          varchar(120) NOT NULL,
  group_name    varchar(30)  NOT NULL,
  -- See the header: this is what separates a cost of letting from an investment
  -- in the asset.
  is_capital    boolean      NOT NULL DEFAULT false,
  -- True where this cost is normally recoverable from a tenant under a lease.
  typically_recoverable boolean NOT NULL DEFAULT false,
  description   varchar(255),
  display_order integer      NOT NULL DEFAULT 0,
  created_at    timestamp    NOT NULL,
  updated_at    timestamp    NOT NULL,
  CONSTRAINT ck_expense_categories_group CHECK (group_name IN (
    'OPERATING', 'REPAIR', 'UTILITY', 'TAX', 'INSURANCE', 'MANAGEMENT',
    'MARKETING', 'CAPITAL', 'OTHER'
  ))
);


-- ----------------------------------------------------------------------------
-- 2. Property expenses
-- ----------------------------------------------------------------------------

CREATE TABLE property_expenses (
  id                   uuid          PRIMARY KEY,
  expense_code         varchar(30)   NOT NULL UNIQUE,

  property_id          uuid          NOT NULL REFERENCES properties (id),
  unit_id              uuid          REFERENCES units (id),
  category_id          uuid          NOT NULL REFERENCES expense_categories (id),

  -- The fault that caused the cost, when there was one. This is what makes a
  -- property's maintenance spend traceable to the work that produced it.
  maintenance_request_id uuid        REFERENCES maintenance_requests (id),
  vendor_id            uuid          REFERENCES vendors (id),

  expense_date         date          NOT NULL,
  period_start         date,
  period_end           date,

  amount               numeric(14, 2) NOT NULL,
  tax_amount           numeric(14, 2) NOT NULL DEFAULT 0,
  total_amount         numeric(14, 2) NOT NULL,

  description          varchar(500)  NOT NULL,
  invoice_reference    varchar(120),

  -- Recharge. `is_recoverable` says the lease allows it; `recovered_amount` says
  -- what was actually recovered, which is routinely less and sometimes nothing.
  is_recoverable       boolean       NOT NULL DEFAULT false,
  recovered_amount     numeric(14, 2) NOT NULL DEFAULT 0,

  recorded_by          uuid          REFERENCES users (id),

  created_at           timestamp     NOT NULL,
  updated_at           timestamp     NOT NULL,
  created_by           uuid,
  updated_by           uuid,
  deleted_at           timestamp,

  CONSTRAINT ck_property_expenses_amount_non_negative CHECK (amount >= 0),
  CONSTRAINT ck_property_expenses_tax_non_negative CHECK (tax_amount >= 0),
  CONSTRAINT ck_property_expenses_total_non_negative CHECK (total_amount >= 0),
  -- As on an invoice, the arithmetic has to hold on the row.
  CONSTRAINT ck_property_expenses_total_is_parts CHECK (total_amount = amount + tax_amount),
  CONSTRAINT ck_property_expenses_recovered_non_negative CHECK (recovered_amount >= 0),
  -- A cost can never be recovered beyond what it cost, and nothing can be
  -- recovered that is not marked recoverable - that combination is a data error
  -- that would show as phantom credit in a recovery report.
  CONSTRAINT ck_property_expenses_recovery CHECK (
    recovered_amount <= total_amount AND (recovered_amount = 0 OR is_recoverable = true)
  ),
  CONSTRAINT ck_property_expenses_period CHECK (
    period_end IS NULL OR period_start IS NULL OR period_end >= period_start
  )
);

CREATE INDEX ix_property_expenses_property ON property_expenses (property_id) WHERE deleted_at IS NULL;
CREATE INDEX ix_property_expenses_unit ON property_expenses (unit_id)
  WHERE unit_id IS NOT NULL AND deleted_at IS NULL;
CREATE INDEX ix_property_expenses_category ON property_expenses (category_id) WHERE deleted_at IS NULL;
CREATE INDEX ix_property_expenses_vendor ON property_expenses (vendor_id)
  WHERE vendor_id IS NOT NULL AND deleted_at IS NULL;
CREATE INDEX ix_property_expenses_request ON property_expenses (maintenance_request_id)
  WHERE maintenance_request_id IS NOT NULL AND deleted_at IS NULL;
CREATE INDEX ix_property_expenses_date ON property_expenses (expense_date DESC) WHERE deleted_at IS NULL;
-- The income-versus-expense chart and the profitability report sum operating cost
-- per property per period, so the index carries the join columns the aggregate
-- actually walks.
CREATE INDEX ix_property_expenses_trend ON property_expenses (property_id, expense_date)
  WHERE deleted_at IS NULL;


-- ----------------------------------------------------------------------------
-- 3. Reference data — expense categories
-- ----------------------------------------------------------------------------

INSERT INTO expense_categories (id, code, name, group_name, is_capital, typically_recoverable, description, display_order, created_at, updated_at) VALUES
  (gen_random_uuid(), 'REPAIRS_PLUMBING',   'Plumbing repairs',            'REPAIR',      false, true,  'Repair and maintenance of water and drainage systems.',        10, now(), now()),
  (gen_random_uuid(), 'REPAIRS_ELECTRICAL', 'Electrical repairs',          'REPAIR',      false, true,  'Repair and maintenance of electrical systems.',                20, now(), now()),
  (gen_random_uuid(), 'REPAIRS_HVAC',       'HVAC servicing',              'REPAIR',      false, true,  'Air-conditioning servicing and repair.',                       30, now(), now()),
  (gen_random_uuid(), 'REPAIRS_CIVIL',      'Civil and structural repairs','REPAIR',      false, false, 'Structural, masonry and waterproofing work.',                  40, now(), now()),
  (gen_random_uuid(), 'PAINTING',           'Painting and finishing',      'REPAIR',      false, true,  'Painting and cosmetic finishing between tenancies.',           50, now(), now()),
  (gen_random_uuid(), 'PEST_CONTROL',       'Pest control',                'OPERATING',   false, true,  'Scheduled pest control treatment.',                            60, now(), now()),
  (gen_random_uuid(), 'HOUSEKEEPING',       'Common area housekeeping',    'OPERATING',   false, false, 'Cleaning and upkeep of common areas.',                         70, now(), now()),
  (gen_random_uuid(), 'SECURITY',           'Security services',           'OPERATING',   false, false, 'Manned guarding and security systems.',                        80, now(), now()),
  (gen_random_uuid(), 'UTILITY_ELECTRICITY','Electricity — common areas',  'UTILITY',     false, false, 'Common-area electricity consumption.',                         90, now(), now()),
  (gen_random_uuid(), 'UTILITY_WATER',      'Water charges',               'UTILITY',     false, true,  'Water supply and tanker charges.',                            100, now(), now()),
  (gen_random_uuid(), 'UTILITY_DG',         'Diesel for generator',        'UTILITY',     false, false, 'Fuel for backup generation.',                                 110, now(), now()),
  (gen_random_uuid(), 'PROPERTY_TAX',       'Property tax',                'TAX',         false, false, 'Municipal property tax.',                                     120, now(), now()),
  (gen_random_uuid(), 'INSURANCE',          'Insurance premium',           'INSURANCE',   false, false, 'Building and public liability cover.',                        130, now(), now()),
  (gen_random_uuid(), 'MANAGEMENT_FEE',     'Management fee',              'MANAGEMENT',  false, false, 'Firm or third-party management fee.',                         140, now(), now()),
  (gen_random_uuid(), 'MARKETING',          'Marketing and advertising',   'MARKETING',   false, false, 'Listing promotion and campaign spend.',                       150, now(), now()),
  (gen_random_uuid(), 'LEGAL_PROFESSIONAL','Legal and professional',      'OPERATING',   false, false, 'Legal, valuation and professional fees.',                      160, now(), now()),
  (gen_random_uuid(), 'CAPITAL_IMPROVEMENT','Capital improvement',         'CAPITAL',     true,  false, 'Improvement that increases the asset''s value or life.',       170, now(), now()),
  (gen_random_uuid(), 'CAPITAL_REPLACEMENT','Plant replacement',           'CAPITAL',     true,  false, 'Replacement of lifts, pumps and similar plant.',              180, now(), now()),
  (gen_random_uuid(), 'CAPITAL_FITOUT',     'Fit-out works',               'CAPITAL',     true,  false, 'Fit-out ahead of a letting.',                                  190, now(), now()),
  (gen_random_uuid(), 'OTHER_OPERATING',    'Other operating expense',     'OTHER',       false, false, 'Operating cost not covered by another category.',             200, now(), now());


-- ----------------------------------------------------------------------------
-- 4. Guard
-- ----------------------------------------------------------------------------
-- Net income depends on at least one operating category and at least one capital
-- category existing, so a seed that lost one of them is a real failure rather than
-- a cosmetic one.

DO $$
DECLARE
  category_count integer;
  operating_count integer;
  capital_count integer;
BEGIN
  SELECT count(*) INTO category_count FROM expense_categories;
  IF category_count < 18 THEN
    RAISE EXCEPTION 'Expected at least 18 expense categories, found %', category_count;
  END IF;

  SELECT count(*) INTO operating_count FROM expense_categories WHERE is_capital = false;
  SELECT count(*) INTO capital_count FROM expense_categories WHERE is_capital = true;

  IF operating_count < 10 THEN
    RAISE EXCEPTION 'Expected at least 10 operating categories, found %', operating_count;
  END IF;
  IF capital_count < 3 THEN
    RAISE EXCEPTION 'Expected at least 3 capital categories, found %', capital_count;
  END IF;
END $$;
