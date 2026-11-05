-- ============================================================================
-- V17 - Document register
-- ============================================================================
-- What the firm holds, and against what. Titles, agreements, NOCs, policies,
-- valuation reports and compliance certificates are all documents the business
-- runs on, and knowing that a document exists - and when it expires - is a
-- genuine operational need. The document itself lives outside this database.
--
-- ----------------------------------------------------------------------------
-- WHY EXPLICIT FOREIGN KEYS RATHER THAN A POLYMORPHIC (entity_type, entity_id)
-- ----------------------------------------------------------------------------
-- A polymorphic pair is the usual shortcut and it is the wrong one here: it
-- cannot be a foreign key, so a document can reference a lease that does not
-- exist, and a query to find "everything filed against this property" has to
-- trust a string. With explicit nullable columns the database enforces every
-- reference, at the cost of a wider table - and the wideness is honest, because
-- the number of things a document can be filed against is genuinely finite and
-- known.
--
-- A document must be filed against at least one of them. An unfiled document is
-- not a register entry.
-- ============================================================================


CREATE TABLE documents (
  id                   uuid          PRIMARY KEY,
  document_code        varchar(30)   NOT NULL UNIQUE,
  title                varchar(255)  NOT NULL,
  document_type        varchar(40)   NOT NULL,
  status               varchar(20)   NOT NULL DEFAULT 'ACTIVE',

  -- What it is filed against. At least one is required; see the check below.
  property_id          uuid          REFERENCES properties (id),
  unit_id              uuid          REFERENCES units (id),
  lease_id             uuid          REFERENCES leases (id),
  tenant_id            uuid          REFERENCES tenants (id),
  owner_id             uuid          REFERENCES owners (id),
  buyer_id             uuid          REFERENCES buyers (id),
  sale_id              uuid          REFERENCES sales_opportunities (id),
  maintenance_request_id uuid        REFERENCES maintenance_requests (id),

  -- Where it lives. A reference rather than a blob: this product has no
  -- object storage, and inventing one would be inventing infrastructure.
  file_reference       varchar(1000),
  mime_type            varchar(120),
  byte_size            bigint,
  page_count           integer,

  -- Versioning. A superseded document is kept and marked, not deleted, because
  -- "which version was in force then" is a question that gets asked.
  version              integer       NOT NULL DEFAULT 1,
  superseded_by_id     uuid          REFERENCES documents (id),
  is_confidential      boolean       NOT NULL DEFAULT false,

  issued_date          date,
  expiry_date          date,

  -- Verification. A verified document records who checked it and when.
  verified_by          uuid          REFERENCES users (id),
  verified_at          timestamp,

  uploaded_by          uuid          REFERENCES users (id),
  notes                varchar(1000),

  created_at           timestamp     NOT NULL,
  updated_at           timestamp     NOT NULL,
  created_by           uuid,
  updated_by           uuid,
  deleted_at           timestamp,

  CONSTRAINT ck_documents_type CHECK (document_type IN (
    'LEASE_AGREEMENT', 'SALE_DEED', 'TITLE_DEED', 'AGREEMENT_TO_SELL', 'NOC',
    'TAX_INVOICE', 'INSURANCE_POLICY', 'COMPLIANCE_CERTIFICATE', 'ID_PROOF',
    'FLOOR_PLAN', 'SITE_PLAN', 'VALUATION_REPORT', 'HANDOVER_NOTE',
    'POSSESSION_LETTER', 'UTILITY_BILL', 'OTHER'
  )),
  CONSTRAINT ck_documents_status CHECK (status IN ('ACTIVE', 'SUPERSEDED', 'EXPIRED', 'ARCHIVED')),
  -- A register entry is filed against something.
  CONSTRAINT ck_documents_scope CHECK (
    property_id IS NOT NULL OR unit_id IS NOT NULL OR lease_id IS NOT NULL
    OR tenant_id IS NOT NULL OR owner_id IS NOT NULL OR buyer_id IS NOT NULL
    OR sale_id IS NOT NULL OR maintenance_request_id IS NOT NULL
  ),
  CONSTRAINT ck_documents_period CHECK (expiry_date IS NULL OR issued_date IS NULL OR expiry_date >= issued_date),
  CONSTRAINT ck_documents_size CHECK (byte_size IS NULL OR byte_size >= 0),
  CONSTRAINT ck_documents_pages CHECK (page_count IS NULL OR page_count > 0),
  CONSTRAINT ck_documents_version CHECK (version > 0),
  CONSTRAINT ck_documents_supersede_not_self CHECK (superseded_by_id IS NULL OR superseded_by_id <> id),
  -- A superseded document names its replacement, and only a superseded one does.
  CONSTRAINT ck_documents_superseded CHECK (
    (status = 'SUPERSEDED' AND superseded_by_id IS NOT NULL)
    OR (status <> 'SUPERSEDED' AND superseded_by_id IS NULL)
  ),
  -- Verification is all-or-nothing.
  CONSTRAINT ck_documents_verification CHECK (
    (verified_at IS NULL AND verified_by IS NULL) OR (verified_at IS NOT NULL AND verified_by IS NOT NULL)
  ),
  -- An expired document records when it expired, so the renewal report has a date
  -- to work from rather than only a state.
  CONSTRAINT ck_documents_expired CHECK (status <> 'EXPIRED' OR expiry_date IS NOT NULL)
);

CREATE INDEX ix_documents_status ON documents (status) WHERE deleted_at IS NULL;
CREATE INDEX ix_documents_type ON documents (document_type) WHERE deleted_at IS NULL;
CREATE INDEX ix_documents_property ON documents (property_id)
  WHERE property_id IS NOT NULL AND deleted_at IS NULL;
CREATE INDEX ix_documents_unit ON documents (unit_id) WHERE unit_id IS NOT NULL AND deleted_at IS NULL;
CREATE INDEX ix_documents_lease ON documents (lease_id) WHERE lease_id IS NOT NULL AND deleted_at IS NULL;
CREATE INDEX ix_documents_tenant ON documents (tenant_id) WHERE tenant_id IS NOT NULL AND deleted_at IS NULL;
CREATE INDEX ix_documents_owner ON documents (owner_id) WHERE owner_id IS NOT NULL AND deleted_at IS NULL;
CREATE INDEX ix_documents_buyer ON documents (buyer_id) WHERE buyer_id IS NOT NULL AND deleted_at IS NULL;
CREATE INDEX ix_documents_sale ON documents (sale_id) WHERE sale_id IS NOT NULL AND deleted_at IS NULL;
-- The expiry report reads only documents that have an expiry and have not yet been
-- archived, which is a small subset of the register.
CREATE INDEX ix_documents_expiry ON documents (expiry_date)
  WHERE expiry_date IS NOT NULL AND status = 'ACTIVE' AND deleted_at IS NULL;
-- Register lookups are by number or title, and the number is already unique.
CREATE INDEX ix_documents_title ON documents (title) WHERE deleted_at IS NULL;
