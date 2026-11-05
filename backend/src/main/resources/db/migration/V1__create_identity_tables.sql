-- ============================================================================
-- V1 - Identity, sessions, audit and business-number series
-- ============================================================================
-- Meridian Estates - Real Estate Management Dashboard.
--
-- The foundation every other table in this product hangs off: who may sign in,
-- what a role is allowed to do, the sessions they hold, the trail of what they
-- did, and the human-readable number series the desk reads out loud.
--
-- Flyway owns the schema. There is no manual DDL anywhere in this product, and
-- Hibernate runs ddl-auto=validate so a drift between these migrations and the
-- entity model fails startup instead of being silently rewritten.
--
-- Conventions applied to every table in this product:
--   * uuid primary keys, so an id can be minted without a round trip and never
--     leaks how many records exist or in what order they were created;
--   * created_at / updated_at / created_by / updated_by / deleted_at on business
--     tables (Auditable), giving every record a soft-delete path - a property,
--     unit or lease is archived rather than removed, because the lease, ledger
--     and audit rows that reference it must stay coherent;
--   * check constraints on every status column, so an invalid state cannot be
--     stored even by a future migration that forgets the rule.
-- ============================================================================


-- ----------------------------------------------------------------------------
-- 1. Permissions - the actual control
-- ----------------------------------------------------------------------------
-- Every endpoint declares the permission code it needs. Roles are only bundles
-- of these codes, so renaming or adding a role can never widen access by itself.

CREATE TABLE permissions (
  id          uuid         PRIMARY KEY,
  code        varchar(80)  NOT NULL UNIQUE,
  module      varchar(60)  NOT NULL,
  description varchar(255)
);

COMMENT ON TABLE permissions IS 'One capability, gated on one endpoint.';
COMMENT ON COLUMN permissions.code IS 'The authority checked by @PreAuthorize and carried in the JWT.';


-- ----------------------------------------------------------------------------
-- 2. Roles - bundles of permissions, assigned to people
-- ----------------------------------------------------------------------------

CREATE TABLE roles (
  id          uuid         PRIMARY KEY,
  name        varchar(50)  NOT NULL UNIQUE,
  description varchar(255),
  system_role boolean      NOT NULL DEFAULT false,
  created_at  timestamp    NOT NULL,
  updated_at  timestamp    NOT NULL,
  created_by  uuid,
  updated_by  uuid,
  deleted_at  timestamp
);

COMMENT ON COLUMN roles.system_role IS 'A seeded role: cannot be deleted through the API.';


-- ----------------------------------------------------------------------------
-- 3. Staff accounts
-- ----------------------------------------------------------------------------
-- Status is checked AFTER the password, never before (see AuthService): refusing
-- a deactivated account earlier would tell an unauthenticated caller that the
-- address exists, which the sign-in path otherwise refuses to reveal.

CREATE TABLE users (
  id                   uuid         PRIMARY KEY,
  staff_code           varchar(30)  NOT NULL UNIQUE,
  first_name           varchar(100) NOT NULL,
  last_name            varchar(100) NOT NULL,
  email                varchar(255) NOT NULL UNIQUE,
  phone                varchar(30),
  password_hash        varchar(255) NOT NULL,
  department           varchar(80),
  designation          varchar(80),
  status               varchar(20)  NOT NULL DEFAULT 'ACTIVE',
  force_password_reset boolean      NOT NULL DEFAULT false,
  last_login_at        timestamp,
  created_at           timestamp    NOT NULL,
  updated_at           timestamp    NOT NULL,
  created_by           uuid,
  updated_by           uuid,
  deleted_at           timestamp,
  CONSTRAINT ck_users_status CHECK (status IN ('ACTIVE', 'INACTIVE', 'SUSPENDED')),
  -- Addresses are matched lower-cased, so storing a mixed-case one would create
  -- a second account nobody could sign in to predictably.
  CONSTRAINT ck_users_email_lower CHECK (email = lower(email))
);

CREATE INDEX ix_users_status ON users (status) WHERE deleted_at IS NULL;
CREATE INDEX ix_users_department ON users (department) WHERE deleted_at IS NULL;


-- ----------------------------------------------------------------------------
-- 4. Role <-> permission, and staff <-> role
-- ----------------------------------------------------------------------------

CREATE TABLE role_permissions (
  role_id       uuid NOT NULL REFERENCES roles (id) ON DELETE CASCADE,
  permission_id uuid NOT NULL REFERENCES permissions (id) ON DELETE CASCADE,
  PRIMARY KEY (role_id, permission_id)
);

CREATE TABLE user_roles (
  user_id uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
  role_id uuid NOT NULL REFERENCES roles (id) ON DELETE CASCADE,
  PRIMARY KEY (user_id, role_id)
);

CREATE INDEX ix_user_roles_role ON user_roles (role_id);


-- ----------------------------------------------------------------------------
-- 5. Sessions
-- ----------------------------------------------------------------------------
-- Only the SHA-256 hash of a refresh token is stored, so a database disclosure
-- does not hand over usable sessions. replaced_by_hash records the rotation,
-- which is what makes a stolen token detectable: whichever client refreshes
-- second presents an already-rotated token and is refused.

CREATE TABLE refresh_tokens (
  id               uuid         PRIMARY KEY,
  user_id          uuid         NOT NULL REFERENCES users (id) ON DELETE CASCADE,
  token_hash       varchar(128) NOT NULL UNIQUE,
  expires_at       timestamp    NOT NULL,
  revoked_at       timestamp,
  replaced_by_hash varchar(128),
  created_by_ip    varchar(60),
  user_agent       varchar(255),
  created_at       timestamp    NOT NULL
);

CREATE INDEX ix_refresh_tokens_user ON refresh_tokens (user_id);
CREATE INDEX ix_refresh_tokens_live ON refresh_tokens (user_id) WHERE revoked_at IS NULL;


-- ----------------------------------------------------------------------------
-- 6. Audit trail
-- ----------------------------------------------------------------------------
-- The entity reference columns are plain identifiers rather than foreign keys on
-- purpose: an audit entry must survive the record it describes being archived.
-- They let the desk ask "show me everything that happened to this property"
-- without parsing text.
--
-- entity_type / entity_id carry the generic case; the named columns carry the
-- cases the product actually filters on.
--
-- metadata is jsonb so an operational summary can be queried, and it must never
-- hold a credential - a login entry records that someone signed in, never how.

CREATE TABLE audit_logs (
  id                     uuid         PRIMARY KEY,
  module                 varchar(60)  NOT NULL,
  action                 varchar(80)  NOT NULL,
  severity               varchar(20)  NOT NULL DEFAULT 'INFO',
  description            varchar(500),
  actor_id               uuid         REFERENCES users (id),
  actor_name             varchar(200),
  entity_type            varchar(60),
  entity_id              uuid,
  entity_label           varchar(200),
  property_id            uuid,
  unit_id                uuid,
  owner_id               uuid,
  tenant_id              uuid,
  lead_id                uuid,
  listing_id             uuid,
  lease_id               uuid,
  payment_id             uuid,
  maintenance_request_id uuid,
  previous_value         varchar(500),
  new_value              varchar(500),
  metadata               jsonb,
  ip_address             varchar(60),
  user_agent             varchar(255),
  created_at             timestamp    NOT NULL,
  CONSTRAINT ck_audit_severity CHECK (severity IN ('INFO', 'WARNING', 'CRITICAL'))
);

-- The trail is read newest-first and filtered by module and action, so those are
-- the three indexes that matter. actor_id is kept so "everything this account
-- did" is a single lookup rather than a scan.
CREATE INDEX ix_audit_logs_created ON audit_logs (created_at DESC);
CREATE INDEX ix_audit_logs_module_action ON audit_logs (module, action);
CREATE INDEX ix_audit_logs_actor ON audit_logs (actor_id);
CREATE INDEX ix_audit_logs_entity ON audit_logs (entity_type, entity_id);
CREATE INDEX ix_audit_logs_property ON audit_logs (property_id) WHERE property_id IS NOT NULL;
CREATE INDEX ix_audit_logs_lease ON audit_logs (lease_id) WHERE lease_id IS NOT NULL;


-- ----------------------------------------------------------------------------
-- 7. Business number series
-- ----------------------------------------------------------------------------
-- Property, unit, lease, invoice and receipt numbers have to be unique and
-- readable aloud, which a UUID is not. The service takes a pessimistic row lock
-- while minting one, so two people working the same portfolio at the same moment
-- cannot be handed the same number.

CREATE TABLE business_number_sequences (
  id         uuid        PRIMARY KEY,
  code       varchar(40) NOT NULL UNIQUE,
  prefix     varchar(20) NOT NULL,
  next_value bigint      NOT NULL DEFAULT 1,
  padding    integer     NOT NULL DEFAULT 6,
  updated_at timestamp   NOT NULL,
  CONSTRAINT ck_sequence_next_positive CHECK (next_value > 0),
  CONSTRAINT ck_sequence_padding CHECK (padding BETWEEN 1 AND 12)
);

COMMENT ON TABLE business_number_sequences IS
  'Human-readable number series. Re-anchored by a later migration to sit above the seeded range.';
