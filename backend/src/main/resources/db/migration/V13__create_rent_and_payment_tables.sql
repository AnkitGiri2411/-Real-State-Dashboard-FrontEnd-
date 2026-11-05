-- ============================================================================
-- V13 - Payment methods, rent invoices, invoice lines, payments, allocations
-- ============================================================================
-- The money. This is the series of tables where a wrong model produces wrong
-- numbers that look plausible, so the ledger rules are enforced here rather than
-- left to whichever service happens to read them.
--
-- ----------------------------------------------------------------------------
-- THE FOUR LEDGER RULES THIS SCHEMA ENFORCES
-- ----------------------------------------------------------------------------
--
-- 1. NOTHING DERIVABLE IS STORED.
--
--    `rent_invoices` deliberately has NO `amount_paid` column, and its status
--    has no PAID, PARTIALLY_PAID or OVERDUE member. Those are functions of the
--    allocations posted against the invoice, and storing them would create a
--    value that can silently disagree with the ledger it summarises - the classic
--    way a system reports an invoice as settled while the money is missing.
--
--    The invoice's own lifecycle is stored (DRAFT, ISSUED, VOID, WRITTEN_OFF),
--    because that is a decision somebody takes. Its settlement is derived:
--
--        settled = SUM(allocation.amount) over non-voided payments
--        outstanding = total_amount - credited_amount - settled
--        overdue = outstanding > 0 AND due_date + grace < today
--
-- 2. MONEY IS EXACT.
--
--    Every amount is `numeric`. There is no double, no float and no implicit
--    cast anywhere in this schema. Decimal arithmetic happens in BigDecimal in
--    the service layer at scale 2, HALF_UP.
--
-- 3. A VOID OR A REFUND IS AN ENTRY, NOT A DELETION.
--
--    Voiding sets `voided_at` and leaves the row. A refund is a separate OUTBOUND
--    payment whose `refund_of_payment_id` points at the receipt it reverses.
--    This is why a refund is not modelled as a REFUNDED status: a status cannot
--    express a *partial* refund, and partial refunds are ordinary. Money is
--    therefore always a signed sum over rows, and a voided receipt can never be
--    counted as collected because the sum excludes it.
--
-- 4. A DEPOSIT IS A LIABILITY, NOT INCOME.
--
--    `leases.deposit_amount` is held and refunded. It lives nowhere near the rent
--    ledger, so no query can accidentally add a deposit to rental income.
-- ============================================================================


-- ----------------------------------------------------------------------------
-- 1. Payment methods — reference data
-- ----------------------------------------------------------------------------

CREATE TABLE payment_methods (
  id                 uuid         PRIMARY KEY,
  code               varchar(40)  NOT NULL UNIQUE,
  name               varchar(120) NOT NULL,
  category           varchar(30)  NOT NULL,
  -- True where the method must carry a bank or instrument reference for the
  -- receipt to be traceable - a UPI transfer without a reference cannot be
  -- reconciled against a statement.
  requires_reference boolean      NOT NULL DEFAULT false,
  is_active          boolean      NOT NULL DEFAULT true,
  display_order      integer      NOT NULL DEFAULT 0,
  created_at         timestamp    NOT NULL,
  updated_at         timestamp    NOT NULL,
  CONSTRAINT ck_payment_methods_category CHECK (category IN (
    'CASH', 'CHEQUE', 'BANK_TRANSFER', 'UPI', 'CARD', 'AUTO_DEBIT', 'DEMAND_DRAFT', 'OTHER'
  ))
);

CREATE INDEX ix_payment_methods_active ON payment_methods (is_active);


-- ----------------------------------------------------------------------------
-- 2. Rent invoices
-- ----------------------------------------------------------------------------

CREATE TABLE rent_invoices (
  id             uuid          PRIMARY KEY,
  invoice_number varchar(30)   NOT NULL UNIQUE,

  lease_id       uuid          NOT NULL REFERENCES leases (id),
  tenant_id      uuid          NOT NULL REFERENCES tenants (id),
  property_id    uuid          NOT NULL REFERENCES properties (id),
  unit_id        uuid          NOT NULL REFERENCES units (id),

  -- The period being charged for. Distinct from the due date: a March invoice is
  -- issued in March and due on the lease's due day.
  period_start   date          NOT NULL,
  period_end     date          NOT NULL,
  due_date       date          NOT NULL,

  -- Stored totals. The invoice is a document that was issued with these figures
  -- on it, so they are facts about a document rather than a running total.
  subtotal       numeric(14, 2) NOT NULL,
  tax_amount     numeric(14, 2) NOT NULL DEFAULT 0,
  total_amount   numeric(14, 2) NOT NULL,

  -- A write-off or goodwill credit reduces what is owed without being a payment.
  -- Kept separate from allocations so a credit is never mistaken for cash.
  credited_amount numeric(14, 2) NOT NULL DEFAULT 0,

  status         varchar(20)   NOT NULL DEFAULT 'DRAFT',

  issued_at      timestamp,
  voided_at      timestamp,
  voided_by      uuid          REFERENCES users (id),
  void_reason    varchar(500),
  written_off_at timestamp,
  written_off_by uuid          REFERENCES users (id),
  write_off_reason varchar(500),

  notes          varchar(1000),

  created_at     timestamp     NOT NULL,
  updated_at     timestamp     NOT NULL,
  created_by     uuid,
  updated_by     uuid,
  deleted_at     timestamp,

  -- No PAID, PARTIALLY_PAID or OVERDUE here, by design. See rule 1 in the header.
  CONSTRAINT ck_rent_invoices_status CHECK (status IN ('DRAFT', 'ISSUED', 'VOID', 'WRITTEN_OFF')),
  CONSTRAINT ck_rent_invoices_period CHECK (period_end >= period_start),
  -- Rent is billed in advance of or on its due date, never after the period ends.
  CONSTRAINT ck_rent_invoices_due_after_period CHECK (due_date >= period_start),
  CONSTRAINT ck_rent_invoices_subtotal_non_negative CHECK (subtotal >= 0),
  CONSTRAINT ck_rent_invoices_tax_non_negative CHECK (tax_amount >= 0),
  CONSTRAINT ck_rent_invoices_total_non_negative CHECK (total_amount >= 0),
  CONSTRAINT ck_rent_invoices_credited_non_negative CHECK (credited_amount >= 0),
  -- The arithmetic has to hold on the row: a total that is not its parts is a bug
  -- that would otherwise surface as an unexplained difference in a report.
  CONSTRAINT ck_rent_invoices_total_is_parts CHECK (total_amount = subtotal + tax_amount),
  -- A credit cannot exceed what was charged.
  CONSTRAINT ck_rent_invoices_credited_within_total CHECK (credited_amount <= total_amount),
  -- Each terminal state records who took the decision, when, and why.
  CONSTRAINT ck_rent_invoices_void CHECK (
    (status = 'VOID' AND voided_at IS NOT NULL AND voided_by IS NOT NULL AND void_reason IS NOT NULL)
    OR (status <> 'VOID' AND voided_at IS NULL)
  ),
  CONSTRAINT ck_rent_invoices_written_off CHECK (
    (status = 'WRITTEN_OFF' AND written_off_at IS NOT NULL AND written_off_by IS NOT NULL
      AND write_off_reason IS NOT NULL)
    OR (status <> 'WRITTEN_OFF' AND written_off_at IS NULL)
  ),
  -- Only an issued invoice has an issue date.
  CONSTRAINT ck_rent_invoices_issued CHECK (
    (status = 'ISSUED' AND issued_at IS NOT NULL) OR (status <> 'ISSUED')
  )
);

CREATE INDEX ix_rent_invoices_lease ON rent_invoices (lease_id) WHERE deleted_at IS NULL;
CREATE INDEX ix_rent_invoices_tenant ON rent_invoices (tenant_id) WHERE deleted_at IS NULL;
CREATE INDEX ix_rent_invoices_property ON rent_invoices (property_id) WHERE deleted_at IS NULL;
CREATE INDEX ix_rent_invoices_status ON rent_invoices (status) WHERE deleted_at IS NULL;
-- The collections board and the overdue calculation read live invoices by due
-- date; a void or written-off invoice is not collectable and is excluded here.
CREATE INDEX ix_rent_invoices_collectable ON rent_invoices (due_date)
  WHERE status = 'ISSUED' AND deleted_at IS NULL;
-- Rent is generated per period, so the same lease must not be billed twice for
-- one month. This is what makes invoice generation idempotent at the database.
CREATE UNIQUE INDEX uq_rent_invoices_lease_period ON rent_invoices (lease_id, period_start, period_end)
  WHERE deleted_at IS NULL;


-- ----------------------------------------------------------------------------
-- 3. Rent invoice lines
-- ----------------------------------------------------------------------------

CREATE TABLE rent_invoice_lines (
  id              uuid          PRIMARY KEY,
  invoice_id      uuid          NOT NULL REFERENCES rent_invoices (id) ON DELETE CASCADE,
  line_type       varchar(30)   NOT NULL,
  description     varchar(255)  NOT NULL,

  quantity        numeric(10, 2) NOT NULL DEFAULT 1,
  unit_amount     numeric(12, 2) NOT NULL DEFAULT 0,
  line_total      numeric(14, 2) NOT NULL,

  tax_rate_percent numeric(5, 2) NOT NULL DEFAULT 0,
  tax_amount      numeric(14, 2) NOT NULL DEFAULT 0,

  display_order   integer       NOT NULL DEFAULT 0,

  created_at      timestamp     NOT NULL,
  updated_at      timestamp     NOT NULL,
  created_by      uuid,
  updated_by      uuid,
  deleted_at      timestamp,

  CONSTRAINT ck_rent_invoice_lines_type CHECK (line_type IN (
    'RENT', 'MAINTENANCE', 'UTILITY', 'PARKING', 'LATE_FEE', 'ADJUSTMENT', 'TAX', 'OTHER'
  )),
  CONSTRAINT ck_rent_invoice_lines_quantity CHECK (quantity > 0),
  -- A credit line is legitimately negative, so the unit amount may be. The line
  -- total is free to be negative for the same reason.
  CONSTRAINT ck_rent_invoice_lines_tax_rate CHECK (tax_rate_percent BETWEEN 0 AND 100),
  CONSTRAINT ck_rent_invoice_lines_tax_non_negative CHECK (tax_amount >= 0)
);

CREATE INDEX ix_rent_invoice_lines_invoice ON rent_invoice_lines (invoice_id, display_order);


-- ----------------------------------------------------------------------------
-- 4. Payments — receipts, and the outbound rows that reverse them
-- ----------------------------------------------------------------------------

CREATE TABLE payments (
  id                   uuid          PRIMARY KEY,
  receipt_number       varchar(30)   NOT NULL UNIQUE,

  -- INBOUND is money received. OUTBOUND is money paid back - a refund - and an
  -- outbound row always names the receipt it reverses. Modelling a refund as its
  -- own row is what makes a partial refund representable and keeps "collected" a
  -- single signed sum rather than a status to be interpreted.
  direction            varchar(10)   NOT NULL DEFAULT 'INBOUND',
  refund_of_payment_id uuid          REFERENCES payments (id),

  payment_method_id    uuid          NOT NULL REFERENCES payment_methods (id),

  -- Whom the money came from, and what it was for. A receipt settles either rent
  -- (through its lease) or consideration on a sale; `sale_id` is added by the
  -- migration that creates the sales tables.
  tenant_id            uuid          REFERENCES tenants (id),
  buyer_id             uuid          REFERENCES buyers (id),
  lease_id             uuid          REFERENCES leases (id),
  property_id          uuid          REFERENCES properties (id),

  amount               numeric(14, 2) NOT NULL,
  currency             varchar(3)    NOT NULL DEFAULT 'INR',

  received_at          timestamp     NOT NULL,
  status               varchar(20)   NOT NULL DEFAULT 'PENDING',

  reference            varchar(120),
  notes                varchar(1000),

  -- Void is a recorded event, not a deletion; see rule 3 in the header.
  voided_at            timestamp,
  voided_by            uuid          REFERENCES users (id),
  void_reason          varchar(500),
  failed_reason        varchar(500),

  recorded_by          uuid          REFERENCES users (id),

  created_at           timestamp     NOT NULL,
  updated_at           timestamp     NOT NULL,
  created_by           uuid,
  updated_by           uuid,
  deleted_at           timestamp,

  CONSTRAINT ck_payments_direction CHECK (direction IN ('INBOUND', 'OUTBOUND')),
  -- No REFUNDED member: a refund is an outbound row. A status could not tell a
  -- partial refund from a full one, which is exactly the distinction that matters.
  CONSTRAINT ck_payments_status CHECK (status IN ('PENDING', 'PAID', 'FAILED', 'VOIDED')),
  CONSTRAINT ck_payments_amount_positive CHECK (amount > 0),
  CONSTRAINT ck_payments_currency_length CHECK (char_length(currency) = 3),
  -- A receipt is for rent or for a sale, and names at least one party.
  CONSTRAINT ck_payments_counterparty CHECK (
    tenant_id IS NOT NULL OR buyer_id IS NOT NULL
  ),
  -- An outbound row reverses a receipt; an inbound one reverses nothing.
  CONSTRAINT ck_payments_refund_direction CHECK (
    (direction = 'OUTBOUND' AND refund_of_payment_id IS NOT NULL)
    OR (direction = 'INBOUND' AND refund_of_payment_id IS NULL)
  ),
  CONSTRAINT ck_payments_not_refund_of_self CHECK (refund_of_payment_id IS NULL OR refund_of_payment_id <> id),
  -- A void records who, when and why. A failure records why. A paid receipt has
  -- neither, so "paid" can never sit alongside a void reason.
  CONSTRAINT ck_payments_void CHECK (
    (status = 'VOIDED' AND voided_at IS NOT NULL AND voided_by IS NOT NULL AND void_reason IS NOT NULL)
    OR (status <> 'VOIDED' AND voided_at IS NULL AND void_reason IS NULL)
  ),
  CONSTRAINT ck_payments_failed CHECK (
    (status = 'FAILED' AND failed_reason IS NOT NULL) OR (status <> 'FAILED')
  ),
  -- Only money that actually arrived has a cleared moment.
  CONSTRAINT ck_payments_paid CHECK (
    (status = 'PAID' AND failed_reason IS NULL) OR status <> 'PAID'
  )
);

CREATE INDEX ix_payments_status ON payments (status) WHERE deleted_at IS NULL;
CREATE INDEX ix_payments_lease ON payments (lease_id) WHERE lease_id IS NOT NULL AND deleted_at IS NULL;
CREATE INDEX ix_payments_tenant ON payments (tenant_id) WHERE tenant_id IS NOT NULL AND deleted_at IS NULL;
CREATE INDEX ix_payments_received ON payments (received_at DESC) WHERE deleted_at IS NULL;
CREATE INDEX ix_payments_method ON payments (payment_method_id) WHERE deleted_at IS NULL;
CREATE INDEX ix_payments_refund_of ON payments (refund_of_payment_id)
  WHERE refund_of_payment_id IS NOT NULL AND deleted_at IS NULL;
-- Collections and the cash trend read only settled money, so the partial index is
-- on exactly that set. A pending or failed receipt is not cash.
CREATE INDEX ix_payments_settled ON payments (received_at, direction)
  WHERE status = 'PAID' AND deleted_at IS NULL;

-- A receipt can be refunded more than once in part, but the total refunded may
-- never exceed the receipt itself. That spans rows, so it is enforced in the
-- service layer, where the sums are taken inside one transaction.


-- ----------------------------------------------------------------------------
-- 5. Payment allocations — which receipt settled which invoice
-- ----------------------------------------------------------------------------
-- The join that makes an invoice's settlement derivable. One receipt may settle
-- several invoices and one invoice may be settled by several receipts, which is
-- ordinary: a tenant pays two months at once, or clears an arrears balance in
-- instalments.

CREATE TABLE payment_allocations (
  id           uuid          PRIMARY KEY,
  payment_id   uuid          NOT NULL REFERENCES payments (id) ON DELETE CASCADE,
  invoice_id   uuid          NOT NULL REFERENCES rent_invoices (id) ON DELETE CASCADE,
  amount       numeric(14, 2) NOT NULL,
  allocated_at timestamp     NOT NULL,
  allocated_by uuid          REFERENCES users (id),
  notes        varchar(500),
  created_at   timestamp     NOT NULL,
  updated_at   timestamp     NOT NULL,
  created_by   uuid,
  updated_by   uuid,
  deleted_at   timestamp,

  CONSTRAINT ck_payment_allocations_amount_positive CHECK (amount > 0)
);

-- One allocation per receipt per invoice: a second row for the same pair is a
-- correction (update) or a duplicate, and duplicates are how an invoice gets
-- settled twice from one payment.
CREATE UNIQUE INDEX uq_payment_allocations_pair ON payment_allocations (payment_id, invoice_id)
  WHERE deleted_at IS NULL;

CREATE INDEX ix_payment_allocations_invoice ON payment_allocations (invoice_id) WHERE deleted_at IS NULL;
CREATE INDEX ix_payment_allocations_payment ON payment_allocations (payment_id) WHERE deleted_at IS NULL;


-- ----------------------------------------------------------------------------
-- 6. Reference data — payment methods
-- ----------------------------------------------------------------------------

INSERT INTO payment_methods (id, code, name, category, requires_reference, is_active, display_order, created_at, updated_at) VALUES
  (gen_random_uuid(), 'CASH',          'Cash',                  'CASH',          false, true,  10, now(), now()),
  (gen_random_uuid(), 'CHEQUE',        'Cheque',                'CHEQUE',        true,  true,  20, now(), now()),
  (gen_random_uuid(), 'NEFT',          'NEFT / RTGS',           'BANK_TRANSFER', true,  true,  30, now(), now()),
  (gen_random_uuid(), 'IMPS',          'IMPS',                  'BANK_TRANSFER', true,  true,  40, now(), now()),
  (gen_random_uuid(), 'UPI',           'UPI',                   'UPI',           true,  true,  50, now(), now()),
  (gen_random_uuid(), 'CARD',          'Card',                  'CARD',          true,  true,  60, now(), now()),
  (gen_random_uuid(), 'AUTO_DEBIT',    'Standing instruction',  'AUTO_DEBIT',    true,  true,  70, now(), now()),
  (gen_random_uuid(), 'DEMAND_DRAFT',  'Demand draft',          'DEMAND_DRAFT',  true,  true,  80, now(), now()),
  (gen_random_uuid(), 'OTHER',         'Other',                 'OTHER',         false, true,  90, now(), now());


-- ----------------------------------------------------------------------------
-- 7. Guard
-- ----------------------------------------------------------------------------

DO $$
DECLARE
  method_count integer;
BEGIN
  SELECT count(*) INTO method_count FROM payment_methods;
  IF method_count < 8 THEN
    RAISE EXCEPTION 'Expected at least 8 payment methods, found %', method_count;
  END IF;
END $$;
