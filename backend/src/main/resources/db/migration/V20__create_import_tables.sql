-- ============================================================================
-- V20 - Import jobs and staged rows
-- ============================================================================
-- Data import, staged rather than direct. A file is parsed into staged rows,
-- validated, previewed, and only then committed. Nothing reaches an application
-- table until a person has seen what will happen.
--
-- ----------------------------------------------------------------------------
-- WHY THE FILE ITSELF IS NEVER STORED
-- ----------------------------------------------------------------------------
-- There is no column for uploaded bytes and no path on disk. A file is parsed in
-- memory, its rows are written here, and the upload is discarded when the request
-- ends. That removes the whole class of problems that comes with storing
-- user-supplied files - path traversal, unbounded disk growth, a directory that
-- has to be served, cleaned and secured - and it costs nothing, because the
-- staged rows are a superset of what the file contained.
--
-- ----------------------------------------------------------------------------
-- IDEMPOTENT REPLAY
-- ----------------------------------------------------------------------------
-- `idempotency_key` is unique. A client that retries an upload - because the
-- connection dropped, or a person pressed the button twice - presents the same
-- key and is answered with the job that already exists rather than inserting a
-- second copy of every row. `replay_of_job_id` records an intentional re-run of a
-- previously committed job, so a genuine repeat is distinguishable from a retry.
--
-- Committing is itself guarded: `import_job_rows.entity_id` records the record a
-- row produced, so a row that has already been committed is never committed twice.
-- ============================================================================


-- ----------------------------------------------------------------------------
-- 1. Import jobs
-- ----------------------------------------------------------------------------

CREATE TABLE import_jobs (
  id                  uuid         PRIMARY KEY,
  job_code            varchar(30)  NOT NULL UNIQUE,

  entity_type         varchar(30)  NOT NULL,
  source_type         varchar(20)  NOT NULL DEFAULT 'CSV',

  original_filename   varchar(255),
  file_size_bytes     bigint,
  -- SHA-256 of the uploaded content. Lets the UI warn that the same file has been
  -- imported before without storing the file to compare against.
  content_hash        varchar(64),

  status              varchar(20)  NOT NULL DEFAULT 'STAGED',

  row_count           integer      NOT NULL DEFAULT 0,
  valid_row_count     integer      NOT NULL DEFAULT 0,
  invalid_row_count   integer      NOT NULL DEFAULT 0,
  inserted_row_count  integer      NOT NULL DEFAULT 0,
  skipped_row_count   integer      NOT NULL DEFAULT 0,

  -- The mapping the uploader confirmed, and the summary of what validation found.
  -- jsonb because both are documents whose shape follows the entity type.
  column_mapping      jsonb,
  validation_summary  jsonb,

  -- Unique per import request. See the header.
  idempotency_key     varchar(64)  NOT NULL UNIQUE,
  -- Set when this job is a deliberate re-run of an earlier committed job.
  replay_of_job_id    uuid         REFERENCES import_jobs (id),

  staged_at           timestamp    NOT NULL,
  validated_at        timestamp,
  committed_at        timestamp,
  committed_by        uuid         REFERENCES users (id),
  cancelled_at        timestamp,
  error_message       varchar(2000),

  created_at          timestamp    NOT NULL,
  updated_at          timestamp    NOT NULL,
  created_by          uuid,
  updated_by          uuid,
  deleted_at          timestamp,

  CONSTRAINT ck_import_jobs_entity CHECK (entity_type IN (
    'PROPERTY', 'UNIT', 'OWNER', 'TENANT', 'LEAD', 'LEASE', 'PAYMENT'
  )),
  CONSTRAINT ck_import_jobs_source CHECK (source_type IN ('CSV', 'XLSX', 'XLS')),
  CONSTRAINT ck_import_jobs_status CHECK (status IN (
    'STAGED', 'VALIDATED', 'COMMITTED', 'FAILED', 'CANCELLED'
  )),
  CONSTRAINT ck_import_jobs_counts CHECK (
    row_count >= 0 AND valid_row_count >= 0 AND invalid_row_count >= 0
    AND inserted_row_count >= 0 AND skipped_row_count >= 0
  ),
  -- The row counts have to add up, or the preview and the commit disagree about
  -- what the file contained.
  CONSTRAINT ck_import_jobs_counts_partition CHECK (valid_row_count + invalid_row_count = row_count),
  CONSTRAINT ck_import_jobs_file_size CHECK (file_size_bytes IS NULL OR file_size_bytes >= 0),
  CONSTRAINT ck_import_jobs_replay_not_self CHECK (replay_of_job_id IS NULL OR replay_of_job_id <> id),
  -- A committed job records when and by whom. A cancelled one records when. A
  -- failed one records why. None of them can be true simultaneously.
  CONSTRAINT ck_import_jobs_outcome CHECK (
    (status = 'COMMITTED' AND committed_at IS NOT NULL AND committed_by IS NOT NULL
      AND cancelled_at IS NULL AND error_message IS NULL)
    OR (status = 'CANCELLED' AND cancelled_at IS NOT NULL
      AND committed_at IS NULL AND error_message IS NULL)
    OR (status = 'FAILED' AND error_message IS NOT NULL
      AND committed_at IS NULL AND cancelled_at IS NULL)
    OR (status IN ('STAGED', 'VALIDATED') AND committed_at IS NULL AND cancelled_at IS NULL)
  ),
  -- Only a job that has been validated can be committed.
  CONSTRAINT ck_import_jobs_validated CHECK (
    status NOT IN ('COMMITTED') OR validated_at IS NOT NULL
  )
);

CREATE INDEX ix_import_jobs_status ON import_jobs (status) WHERE deleted_at IS NULL;
CREATE INDEX ix_import_jobs_entity ON import_jobs (entity_type) WHERE deleted_at IS NULL;
CREATE INDEX ix_import_jobs_staged ON import_jobs (staged_at DESC) WHERE deleted_at IS NULL;
CREATE INDEX ix_import_jobs_content_hash ON import_jobs (content_hash)
  WHERE content_hash IS NOT NULL AND deleted_at IS NULL;


-- ----------------------------------------------------------------------------
-- 2. Staged rows
-- ----------------------------------------------------------------------------

CREATE TABLE import_job_rows (
  id               uuid         PRIMARY KEY,
  job_id           uuid         NOT NULL REFERENCES import_jobs (id) ON DELETE CASCADE,
  row_number       integer      NOT NULL,

  -- The row as parsed, keyed by the source column heading.
  raw_data         jsonb        NOT NULL,
  -- The row as it will be written, after mapping and coercion. Null until the
  -- mapping is applied.
  mapped_data      jsonb,

  status           varchar(20)  NOT NULL DEFAULT 'PENDING',
  -- One entry per problem, each naming the field and what was wrong with it, so
  -- the preview can point at a specific cell rather than rejecting a row.
  validation_errors jsonb,
  validation_warnings jsonb,

  -- The record this row produced, recorded when it is committed. This is what
  -- makes a re-commit unable to insert a second copy.
  entity_id        uuid,
  entity_label     varchar(200),

  created_at       timestamp    NOT NULL,
  updated_at       timestamp    NOT NULL,

  CONSTRAINT uq_import_job_rows_number UNIQUE (job_id, row_number),
  CONSTRAINT ck_import_job_rows_number CHECK (row_number > 0),
  CONSTRAINT ck_import_job_rows_status CHECK (status IN (
    'PENDING', 'VALID', 'INVALID', 'INSERTED', 'SKIPPED', 'FAILED'
  )),
  -- An invalid row says why. A valid one does not.
  CONSTRAINT ck_import_job_rows_errors CHECK (
    (status = 'INVALID' AND validation_errors IS NOT NULL)
    OR (status <> 'INVALID')
  ),
  -- A committed row names the record it created.
  CONSTRAINT ck_import_job_rows_inserted CHECK (
    (status = 'INSERTED' AND entity_id IS NOT NULL) OR (status <> 'INSERTED')
  )
);

CREATE INDEX ix_import_job_rows_job ON import_job_rows (job_id, row_number);
CREATE INDEX ix_import_job_rows_status ON import_job_rows (job_id, status);
-- The preview and the commit both read problems first.
CREATE INDEX ix_import_job_rows_invalid ON import_job_rows (job_id)
  WHERE status IN ('INVALID', 'FAILED');
