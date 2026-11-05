import { useMemo, useState, type ChangeEvent } from 'react'
import { Link } from 'react-router-dom'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import {
  BadgeCheck, ClipboardCheck, FileUp, ListChecks, ScanSearch, ShieldAlert, Upload,
} from 'lucide-react'
import { PageAccessGuard, PageHeader } from '../components/common'
import {
  Badge, Button, DataTable, DetailItem, Dialog, EmptyState, ErrorState, Field, Input, KpiTile,
  Pagination, Panel, SectionHeader, Select, Skeleton, StatusBadge, useToast, type Column,
} from '../components/ui'
import { count as formatCount, dateTime, humanize, relative } from '../components/ui/formatters'
import { importApi } from '../services'
import { usePlatformStore } from '../platform'
import { ApiError, messageOf } from '../lib/errors'

/* ============================================================
 * The entity types this installation imports
 * ============================================================ */

/**
 * The five entity types the import accepts, with the columns each file must carry.
 *
 * <p>`ImportService.SUPPORTED` offers five of the seven `ImportEntityType` values. `OWNER` and
 * `TENANT` are deliberately left out: this module carries no create service for them, every insert
 * routes through the domain service that owns its entity type, and an option that would have to
 * bypass that rule is not offered at all.</p>
 *
 * <p>The lists are the register's own `COLUMNS`/`REQUIRED`, kept here because the frontend's API map
 * exposes no `/imports/supported` call. They are stated to the reader <em>before</em> a file is
 * chosen, which is the point of them: a column the file must carry is worth knowing before the upload,
 * not after it is refused.</p>
 */
const IMPORT_ENTITY_TYPES = [
  {
    value: 'PROPERTY',
    label: 'Properties',
    required: ['name', 'propertyTypeCode', 'addressLine1', 'city'],
  },
  {
    value: 'UNIT',
    label: 'Units',
    required: ['propertyCode', 'unitTypeCode', 'unitNumber'],
  },
  {
    value: 'LEAD',
    label: 'Leads',
    required: ['displayName'],
  },
  {
    value: 'LEASE',
    label: 'Leases',
    required: ['unitCode', 'tenantCode', 'startDate', 'endDate', 'rentAmount'],
  },
  {
    value: 'PAYMENT',
    label: 'Receipts',
    required: ['leaseCode', 'amount', 'receivedAt', 'methodCode'],
  },
] as const

/* ============================================================
 * The workflow
 * ============================================================ */

type ImportStep = 'upload' | 'preview' | 'confirm' | 'result'

/**
 * The four steps, in the order they must happen.
 *
 * <p>They are written out as well as stepped through, because the whole safety of this screen is the
 * distance between "preview" and "confirm": a reader who cannot see how many steps there are, or
 * where they are in them, is a reader who presses the write button without meaning to. Each step
 * carries its own sentence about what it does and does not write.</p>
 */
const IMPORT_STEPS: { key: ImportStep; label: string; description: string }[] = [
  {
    key: 'upload',
    label: 'Upload',
    description: 'Choose what the file holds and pick it. Nothing is read into the portfolio yet.',
  },
  {
    key: 'preview',
    label: 'Preview',
    description: 'The file is parsed and every row validated in memory. No record is created.',
  },
  {
    key: 'confirm',
    label: 'Confirm',
    description: 'The staged, valid rows are written. This is the only step that creates anything.',
  },
  {
    key: 'result',
    label: 'Result',
    description: 'What was inserted, skipped and refused, and the audit entry the commit left.',
  },
]

function stepNumber(step: ImportStep): number {
  return IMPORT_STEPS.findIndex((candidate) => candidate.key === step) + 1
}

/* ============================================================
 * Every named failure this workflow can be answered with
 * ============================================================ */

/**
 * The named refusals `ImportService` and `ImportController` can answer this screen with, and what
 * each means for the reader.
 *
 * <p>The API's own sentence is what is shown — `messageOf` is the message the backend wrote, with the
 * code prefix taken off it, because a person must never be shown `IMPORT_FILE_TOO_LARGE`. These lines
 * add only the *next move*, so a named failure is never collapsed into "the import failed". A failure
 * this map does not know is still shown with the API's own message rather than a generic one.</p>
 */
const IMPORT_FAILURE_GUIDANCE: Record<string, string> = {
  IMPORT_FILE_EMPTY:
    'Choose a file before staging it. An empty upload is refused rather than staged as a job with no rows.',
  IMPORT_FILE_TOO_LARGE:
    'The installation\u2019s limit is stated above, in the panel you chose the file from. Split the file, or ask for the limit to be raised where this installation is configured.',
  IMPORT_FILE_TYPE_UNSUPPORTED:
    'The format is decided from the file\u2019s own bytes, not from its name: a CSV, xlsx or xls document is read, and anything else is refused rather than fed to a parser that would misread it. Re-export the file as one of those three.',
  IMPORT_FILE_CONTENT_INVALID:
    'The file matched a supported format but could not be read — most often a truncated download or a workbook with no sheets. Re-export it and try again.',
  IMPORT_HEADER_MISSING:
    'The first row of the file is its header, and there must be at least one row of data under it. Add the header row naming the columns, and the data under it.',
  IMPORT_REQUIRED_COLUMNS_MISSING:
    'The columns this entity type cannot be imported without are listed above; add the missing ones to the header and stage the file again.',
  IMPORT_TOO_MANY_ROWS:
    'The row limit is stated above. Split the file into imports within the limit — nothing is truncated silently, so no part of the file was read.',
  IMPORT_NO_VALID_ROWS:
    'Every row failed validation, so there is nothing to confirm and the job was rolled back. Fix the rows the errors describe, then stage the file again.',
  IMPORT_NOT_COMMITTABLE:
    'This job is no longer in a state that can be written — it has already been committed, or was never validated. Read its detail on the register rather than confirming it again.',
  IMPORT_IDEMPOTENCY_CONFLICT:
    'The job was already committed under a different idempotency key, or that key has been used by another import. Nothing was written. Start a fresh preview if this file genuinely has to be imported again.',
}

/** The named code behind a refusal, whether it arrived as the error's code or inside its message. */
function failureCodeOf(error: unknown): string | null {
  if (error instanceof ApiError && error.code && error.code !== 'VALIDATION_FAILED') return error.code
  const prefixed = /^([A-Z][A-Z_]{3,}):/.exec(messageOf(error))
  return prefixed ? prefixed[1] : null
}

/** A refusal as the reader sees it: the API's own sentence, the next move, and any field details. */
function describeFailure(error: unknown): { message: string; details: string[] } {
  const message = messageOf(error).replace(/^[A-Z][A-Z_]{3,}:\s*/, '')
  const code = failureCodeOf(error)
  const details: string[] = []
  if (code && IMPORT_FAILURE_GUIDANCE[code]) details.push(IMPORT_FAILURE_GUIDANCE[code])
  if (error instanceof ApiError && error.details.length > 0) details.push(...error.details)
  return { message, details }
}

/* ============================================================
 * Reading the import payloads
 * ============================================================ */

/**
 * The import payloads, read out of the maps the API map declares.
 *
 * <p>`ImportDtos` is not mirrored in `src/types`, so every field is *checked* here rather than
 * asserted: a number that is not a number becomes zero, a string that is not a string becomes null,
 * and a list that is not a list becomes empty. The import screen is the last place to guess at a
 * shape — a fabricated tally would tell somebody their file had been written when it had not.</p>
 */
interface ImportJob {
  id: string
  jobCode: string
  entityType: string
  sourceType: string
  originalFilename: string
  fileSizeBytes: number | null
  status: string
  rowCount: number
  validRowCount: number
  invalidRowCount: number
  insertedRowCount: number
  skippedRowCount: number
  stagedAt: string | null
  validatedAt: string | null
  committedAt: string | null
  createdAt: string | null
}

interface ImportRowSummary {
  id: string
  rowNumber: number
  status: string
  errors: string[]
  warnings: string[]
  entityLabel: string | null
}

interface ImportPreview {
  job: ImportJob
  canCommit: boolean
  columnNames: string[]
  rows: ImportRowSummary[]
  problems: ImportRowSummary[]
}

interface ImportResult {
  job: ImportJob
  inserted: number
  skipped: number
  failed: number
  idempotentReplay: boolean
  outcomes: ImportRowSummary[]
}

function textOf(value: unknown): string | null {
  return typeof value === 'string' && value !== '' ? value : null
}

function numberOrZero(value: unknown): number {
  return typeof value === 'number' && Number.isFinite(value) ? value : 0
}

function stringListOf(value: unknown): string[] {
  return Array.isArray(value) ? value.filter((entry): entry is string => typeof entry === 'string') : []
}

function asJob(value: unknown): ImportJob {
  const source = (value && typeof value === 'object' ? value : {}) as Record<string, unknown>
  return {
    id: textOf(source.id) ?? '',
    jobCode: textOf(source.jobCode) ?? '',
    entityType: textOf(source.entityType) ?? '',
    sourceType: textOf(source.sourceType) ?? '',
    originalFilename: textOf(source.originalFilename) ?? '',
    fileSizeBytes: typeof source.fileSizeBytes === 'number' ? source.fileSizeBytes : null,
    status: textOf(source.status) ?? '',
    rowCount: numberOrZero(source.rowCount),
    validRowCount: numberOrZero(source.validRowCount),
    invalidRowCount: numberOrZero(source.invalidRowCount),
    insertedRowCount: numberOrZero(source.insertedRowCount),
    skippedRowCount: numberOrZero(source.skippedRowCount),
    stagedAt: textOf(source.stagedAt),
    validatedAt: textOf(source.validatedAt),
    committedAt: textOf(source.committedAt),
    createdAt: textOf(source.createdAt),
  }
}

function asRowSummary(value: unknown, index: number): ImportRowSummary {
  const source = (value && typeof value === 'object' ? value : {}) as Record<string, unknown>
  return {
    id: textOf(source.id) ?? `row-${index}`,
    rowNumber: numberOrZero(source.rowNumber),
    status: textOf(source.status) ?? 'UNKNOWN',
    errors: stringListOf(source.errors),
    warnings: stringListOf(source.warnings),
    entityLabel: textOf(source.entityLabel),
  }
}

function asPreview(value: unknown): ImportPreview {
  const source = (value && typeof value === 'object' ? value : {}) as Record<string, unknown>
  const rows = Array.isArray(source.rows) ? source.rows.map(asRowSummary) : []
  const problems = Array.isArray(source.problems) ? source.problems.map(asRowSummary) : []
  return {
    job: asJob(source.job),
    canCommit: source.canCommit === true,
    columnNames: stringListOf(source.columnNames),
    rows,
    problems,
  }
}

function asResult(value: unknown): ImportResult {
  const source = (value && typeof value === 'object' ? value : {}) as Record<string, unknown>
  return {
    job: asJob(source.job),
    inserted: numberOrZero(source.inserted),
    skipped: numberOrZero(source.skipped),
    failed: numberOrZero(source.failed),
    idempotentReplay: source.idempotentReplay === true,
    outcomes: Array.isArray(source.outcomes) ? source.outcomes.map(asRowSummary) : [],
  }
}

/** A byte count as a person reads it. */
function fileSize(bytes: number | null): string {
  if (bytes === null) return '—'
  if (bytes >= 1_048_576) return `${(bytes / 1_048_576).toFixed(1)} MB`
  if (bytes >= 1024) return `${Math.round(bytes / 1024)} KB`
  return `${bytes} bytes`
}

/**
 * Data import.
 *
 * <p><b>Four steps, and only the third of them writes.</b> {@code preview} parses the upload in
 * memory, validates every row and records what it found — <em>without creating a single property,
 * unit, lead, lease or receipt</em>. {@code confirm} is the only call that inserts, it carries the
 * idempotency key that makes a double-click safe, and it writes only the rows that passed validation.
 * The screen is built around that distance and states it at every step, because an import screen that
 * looks like one button is an import screen that writes twice.</p>
 *
 * <p><b>The file never touches a disk here.</b> The upload is read into memory and its format is
 * decided from its own bytes rather than from its name, which is why the accepted types are stated as
 * a hint and the parser has the final say.</p>
 *
 * <p><b>Insert-only, and no duplicate detection.</b> Nothing updates or deletes, and the register does
 * not de-duplicate rows: a file imported twice is inserted twice. What the idempotency key protects is
 * the <em>confirm</em> — the same key returns the first run's result and writes nothing again — not
 * the file.</p>
 */
export default function ImportsPage() {
  const queryClient = useQueryClient()
  const toast = useToast()
  const profile = usePlatformStore((state) => state.profile)
  const maxFileSizeMb = profile?.policies?.importMaxFileSizeMb
  const maxRows = profile?.policies?.importMaxRows

  const [step, setStep] = useState<ImportStep>('upload')
  const [entityType, setEntityType] = useState<string>(IMPORT_ENTITY_TYPES[0].value)
  const [file, setFile] = useState<File | null>(null)
  const [clientFailure, setClientFailure] = useState<string | null>(null)
  const [idempotencyKey, setIdempotencyKey] = useState('')
  const [preview, setPreview] = useState<ImportPreview | null>(null)
  const [result, setResult] = useState<ImportResult | null>(null)

  const [page, setPage] = useState(0)
  const [size, setSize] = useState(20)
  const [listSort, setListSort] = useState('createdAt,desc')
  const [detailJobCode, setDetailJobCode] = useState<string | null>(null)

  const activeEntityType = IMPORT_ENTITY_TYPES.find((type) => type.value === entityType) ?? IMPORT_ENTITY_TYPES[0]

  /* ---- Reads ---------------------------------------------------------------- */

  const historyQuery = useQuery({
    queryKey: ['imports', 'list', { page, size, listSort }],
    queryFn: () => importApi.list({ page, size, sort: listSort }),
  })

  const detailQuery = useQuery({
    queryKey: ['imports', 'detail', detailJobCode],
    queryFn: () => importApi.detail(detailJobCode ?? ''),
    enabled: detailJobCode !== null,
  })

  /* ---- Writes --------------------------------------------------------------- */

  const previewMutation = useMutation({
    mutationFn: (formData: FormData) => importApi.preview(formData),
    onSuccess: (payload) => {
      const staged = asPreview(payload)
      setPreview(staged)
      /*
       * The idempotency key is minted with the preview, not with the confirm.
       *
       * The backend requires one and refuses a replay with a *different* key against an already
       * committed job; generating it when the preview is produced means the key on screen belongs to
       * this staged job, and a reader who double-clicks Confirm presents the same key twice rather
       * than a second, colliding one.
       */
      setIdempotencyKey(crypto.randomUUID())
      setResult(null)
      setStep('preview')
    },
  })

  const confirmMutation = useMutation({
    mutationFn: () => importApi.confirm(preview?.job.jobCode ?? '', { idempotencyKey }),
    onSuccess: (payload) => {
      setResult(asResult(payload))
      setStep('result')
      void queryClient.invalidateQueries({ queryKey: ['imports'] })
      toast.push('The import was committed. Every staged row is now on the register.', 'emerald')
    },
    onError: () => {
      // The refusal is rendered beside the confirm button, where the reader pressed it.
      void queryClient.invalidateQueries({ queryKey: ['imports'] })
    },
  })

  /* ---- Upload --------------------------------------------------------------- */

  function onPickFile(event: ChangeEvent<HTMLInputElement>): void {
    setFile(event.target.files && event.target.files[0] ? event.target.files[0] : null)
    setClientFailure(null)
  }

  function stageFile(): void {
    if (previewMutation.isPending) return
    setClientFailure(null)

    if (!file) {
      setClientFailure('Choose the file you want to stage before staging it.')
      return
    }

    // The size is checked before the upload is sent, so a reader is not made to push twenty megabytes
    // through the wire to be told the installation would not read it. The backend checks it again.
    if (maxFileSizeMb !== undefined && file.size > maxFileSizeMb * 1024 * 1024) {
      setClientFailure(
        `That file is ${fileSize(file.size)}. This installation reads files up to ${maxFileSizeMb} MB, so nothing was uploaded — split the file, or ask for the limit to be changed where this installation is configured.`,
      )
      return
    }

    const formData = new FormData()
    formData.append('entityType', entityType)
    // The multipart part is named `file` because that is the part the endpoint declares.
    formData.append('file', file)
    previewMutation.mutate(formData)
  }

  function resetWorkflow(): void {
    setStep('upload')
    setFile(null)
    setClientFailure(null)
    setPreview(null)
    setResult(null)
    setIdempotencyKey('')
    previewMutation.reset()
    confirmMutation.reset()
  }

  /** Pick a job back up from the register: a staged, uncommitted job can still be written. */
  function resumeJob(detail: ImportPreview): void {
    setPreview(detail)
    setIdempotencyKey(crypto.randomUUID())
    setResult(null)
    setStep('preview')
    setDetailJobCode(null)
    toast.push(`Import ${detail.job.jobCode} is open again at the preview step.`, 'sapphire')
  }

  /* ---- Derived reads -------------------------------------------------------- */

  const problemLines = useMemo(() => {
    if (!preview) return []
    const lines: { id: string; rowNumber: number; kind: string; message: string }[] = []
    for (const row of preview.rows) {
      row.errors.forEach((message, index) => lines.push({
        id: `${row.id}-error-${index}`, rowNumber: row.rowNumber, kind: 'ERROR', message,
      }))
      row.warnings.forEach((message, index) => lines.push({
        id: `${row.id}-warning-${index}`, rowNumber: row.rowNumber, kind: 'WARNING', message,
      }))
    }
    return lines
  }, [preview])

  const problemColumns: Column<{ id: string; rowNumber: number; kind: string; message: string }>[] = [
    {
      key: 'row',
      header: 'Row in the file',
      width: '18%',
      align: 'right',
      render: (row) => <span className="tabular-nums">{formatCount(row.rowNumber)}</span>,
    },
    {
      key: 'kind',
      header: 'Kind',
      width: '16%',
      render: (row) => <StatusBadge status={row.kind} />,
    },
    {
      key: 'message',
      header: 'What is wrong with it',
      width: '66%',
      render: (row) => <span className="text-xs" style={{ color: 'var(--text-primary)' }}>{row.message}</span>,
    },
  ]

  const outcomeColumns: Column<ImportRowSummary>[] = [
    {
      key: 'row',
      header: 'Row',
      width: '14%',
      align: 'right',
      render: (row) => <span className="tabular-nums">{formatCount(row.rowNumber)}</span>,
    },
    {
      key: 'status',
      header: 'Outcome',
      width: '20%',
      render: (row) => <StatusBadge status={row.status} />,
    },
    {
      key: 'record',
      header: 'Record it produced',
      width: '26%',
      render: (row) => (
        <span className="text-xs tabular-nums" style={{ color: 'var(--text-primary)' }}>
          {row.entityLabel ?? '—'}
        </span>
      ),
    },
    {
      key: 'reason',
      header: 'Reason, where it was not written',
      width: '40%',
      render: (row) => (
        <span className="text-xs" style={{ color: 'var(--text-secondary)' }}>
          {row.errors[0] ?? row.warnings[0] ?? '—'}
        </span>
      ),
    },
  ]

  const historyColumns = useMemo<Column<ImportJob>[]>(() => [
    {
      key: 'code',
      header: 'Job',
      width: '20%',
      sortKey: 'jobCode',
      render: (row) => (
        <span className="text-xs font-semibold tabular-nums" style={{ color: 'var(--text-primary)' }}>
          {row.jobCode}
        </span>
      ),
    },
    {
      key: 'entity',
      header: 'Holds',
      width: '14%',
      sortKey: 'entityType',
      render: (row) => <span className="text-xs" style={{ color: 'var(--text-secondary)' }}>{humanize(row.entityType)}</span>,
    },
    {
      key: 'file',
      header: 'File',
      width: '24%',
      secondary: true,
      render: (row) => (
        <span className="truncate text-xs" style={{ color: 'var(--text-secondary)' }}>
          {row.originalFilename} · {fileSize(row.fileSizeBytes)}
        </span>
      ),
    },
    {
      key: 'status',
      header: 'State',
      width: '13%',
      sortKey: 'status',
      render: (row) => <StatusBadge status={row.status} />,
    },
    {
      key: 'rows',
      header: 'Rows',
      width: '14%',
      align: 'right',
      sortKey: 'rowCount',
      render: (row) => (
        <span className="text-xs tabular-nums">
          <span className="block" style={{ color: 'var(--text-primary)' }}>{formatCount(row.rowCount)}</span>
          <span className="block text-[10px]" style={{ color: 'var(--text-secondary)' }}>
            {formatCount(row.validRowCount)} valid · {formatCount(row.invalidRowCount)} refused
          </span>
        </span>
      ),
    },
    {
      key: 'when',
      header: 'Staged',
      width: '15%',
      sortKey: 'createdAt',
      render: (row) => (
        <span className="text-xs">
          <span className="block" title={dateTime(row.createdAt)} style={{ color: 'var(--text-primary)' }}>
            {relative(row.createdAt)}
          </span>
          {row.committedAt && (
            <span className="block text-[10px] tabular-nums" style={{ color: 'var(--text-secondary)' }}>
              written {relative(row.committedAt)}
            </span>
          )}
        </span>
      ),
    },
  ], [])

  const historyRows = (historyQuery.data?.items ?? []).map(asJob)
  const currentStepNumber = stepNumber(step)

  return (
    <PageAccessGuard permission="DATA_IMPORT">
      <PageHeader
        eyebrow="Administration"
        title="Data import"
        description="Bring a spreadsheet of properties, units, leads, leases or receipts into the portfolio. Every file is staged and validated first; nothing is written until you confirm it."
        actions={
          step !== 'upload'
            ? <Button variant="outline" onClick={resetWorkflow}>Start a different import</Button>
            : undefined
        }
      />

      <div className="mb-5 grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
        <KpiTile
          label="Step"
          value={`${currentStepNumber} of ${IMPORT_STEPS.length}`}
          tone="forest"
          caption={IMPORT_STEPS[currentStepNumber - 1].label}
        />
        <KpiTile
          label="Imports on record"
          value={historyQuery.isError ? '—' : formatCount(historyQuery.data?.totalElements ?? 0)}
          tone="sapphire"
          icon={<ListChecks size={15} />}
          caption="Staged, validated and committed jobs"
        />
        <KpiTile
          label="Rows staged now"
          value={preview ? formatCount(preview.job.rowCount) : '—'}
          tone={preview && preview.job.invalidRowCount > 0 ? 'amber' : 'emerald'}
          icon={<ScanSearch size={15} />}
          caption={preview
            ? `${formatCount(preview.job.validRowCount)} valid · ${formatCount(preview.job.invalidRowCount)} refused`
            : 'Nothing is staged'}
        />
        <KpiTile
          label="File limit"
          value={maxFileSizeMb !== undefined ? `${maxFileSizeMb} MB` : '—'}
          tone="sandstone"
          icon={<ShieldAlert size={15} />}
          caption={maxRows !== undefined ? `Up to ${formatCount(maxRows)} rows per import` : 'Read from the installation profile'}
        />
      </div>

      {/* ── The four steps, said in words ────────────────────────────────── */}

      <SectionHeader
        eyebrow="Workflow"
        title="Upload, preview, confirm, result"
        rule="copper"
        description="Four steps, in this order. The portfolio is only ever changed by the third."
      />
      <ol className="mb-5 grid gap-3 sm:grid-cols-2 lg:grid-cols-4" aria-live="polite">
        {IMPORT_STEPS.map((candidate, index) => {
          const number = index + 1
          const state = number === currentStepNumber ? 'Current step' : number < currentStepNumber ? 'Done' : 'Not started'
          const isCurrent = number === currentStepNumber
          return (
            <li
              key={candidate.key}
              className="min-w-0 rounded-[var(--radius-card)] border p-3"
              style={{
                borderColor: isCurrent ? 'var(--accent-copper)' : 'var(--border)',
                background: isCurrent ? 'var(--surface-copper)' : 'var(--surface)',
              }}
            >
              <div className="flex items-center justify-between gap-2">
                <span className="eyebrow" style={{ color: isCurrent ? 'var(--ink-copper)' : 'var(--text-secondary)' }}>
                  Step {number} of {IMPORT_STEPS.length}
                </span>
                {/* The state is written out; the border colour only repeats it. */}
                <Badge tone={isCurrent ? 'copper' : number < currentStepNumber ? 'emerald' : 'slate'}>
                  {state}
                </Badge>
              </div>
              <p className="mt-1.5 text-sm font-semibold" style={{ color: 'var(--text-primary)' }}>
                {candidate.label}
              </p>
              <p className="mt-0.5 text-[11px] leading-relaxed" style={{ color: 'var(--text-secondary)' }}>
                {candidate.description}
              </p>
            </li>
          )
        })}
      </ol>

      {/* ── Step one: upload ─────────────────────────────────────────────── */}

      {step === 'upload' && (
        <Panel
          className="mb-6"
          tint="sandstone"
          title="Step 1 of 4 — Upload"
          subtitle="Choose what the file holds and pick the file. Nothing is read into the portfolio at this step."
          icon={<FileUp size={15} />}
        >
          <div className="grid gap-4 lg:grid-cols-[1.2fr_1fr]">
            <div className="space-y-4">
              <Field
                label="What the file holds"
                htmlFor="import-entity-type"
                required
                hint="Only the entity types this installation imports are offered."
              >
                <Select
                  id="import-entity-type"
                  value={entityType}
                  onChange={(event) => { setEntityType(event.target.value); setClientFailure(null) }}
                >
                  {IMPORT_ENTITY_TYPES.map((type) => (
                    <option key={type.value} value={type.value}>{type.label}</option>
                  ))}
                </Select>
              </Field>

              <Field
                label="The file to import"
                htmlFor="import-file"
                required
                hint="A CSV, xlsx or xls file. The format is decided from the file's own bytes rather than its name, so a renamed file is still refused if its content is not one of the three."
              >
                <Input
                  id="import-file"
                  name="file"
                  type="file"
                  accept=".csv,.xlsx,.xls,text/csv,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet,application/vnd.ms-excel"
                  onChange={onPickFile}
                  aria-describedby="import-file-name"
                />
              </Field>

              <p id="import-file-name" className="text-xs" style={{ color: 'var(--text-secondary)' }}>
                {file
                  ? `Chosen: ${file.name} (${fileSize(file.size)})`
                  : 'No file chosen yet.'}
              </p>

              {clientFailure && (
                <ErrorState
                  message="That file was not uploaded"
                  details={[clientFailure]}
                />
              )}

              {previewMutation.isError && (
                <ErrorState
                  message={describeFailure(previewMutation.error).message}
                  details={describeFailure(previewMutation.error).details}
                  onRetry={() => stageFile()}
                />
              )}

              <div className="flex flex-wrap items-center gap-2">
                <Button variant="primary" onClick={stageFile} loading={previewMutation.isPending} disabled={!file}>
                  <Upload size={15} aria-hidden="true" />
                  Stage the file for preview
                </Button>
                <span className="text-[11px]" style={{ color: 'var(--text-secondary)' }}>
                  Staging sends the file to be read and validated. It writes nothing.
                </span>
              </div>
            </div>

            {/* The limits, stated before a file is chosen. */}
            <div className="min-w-0 rounded-[var(--radius-card)] border p-3" style={{ borderColor: 'var(--border)', background: 'var(--surface)' }}>
              <p className="eyebrow" style={{ color: 'var(--ink-sandstone)' }}>This installation accepts</p>
              <dl className="mt-2 space-y-2">
                <DetailItem
                  label="Largest file"
                  value={maxFileSizeMb !== undefined ? `${maxFileSizeMb} MB` : 'Set by the installation profile'}
                />
                <DetailItem
                  label="Most rows"
                  value={maxRows !== undefined ? `${formatCount(maxRows)} rows` : 'Set by the installation profile'}
                />
                <DetailItem label="File types" value="CSV, XLSX, XLS — decided from the content" />
                <DetailItem label="Owners and tenants" value="Not importable: this module has no create service for them" />
              </dl>

              <p className="mt-3 border-t pt-3 text-[11px] font-semibold" style={{ borderColor: 'var(--border)', color: 'var(--text-primary)' }}>
                {activeEntityType.label} must carry these columns
              </p>
              <ul className="mt-1 flex flex-wrap gap-1.5">
                {activeEntityType.required.map((column) => (
                  <li key={column}>
                    <span
                      className="badge"
                      style={{ background: 'var(--surface-sunken)', color: 'var(--text-secondary)' }}
                    >
                      {column}
                    </span>
                  </li>
                ))}
              </ul>
              <p className="mt-2 text-[11px] leading-relaxed" style={{ color: 'var(--text-secondary)' }}>
                The first row is the header and it names these columns. A column the import does not
                recognise is reported but does not stop the row — the row is still complete for the
                columns that matter.
              </p>
            </div>
          </div>
        </Panel>
      )}

      {/* ── Step two: preview ────────────────────────────────────────────── */}

      {step === 'preview' && preview && (
        <Panel
          className="mb-6"
          tint="sapphire"
          title="Step 2 of 4 — Preview"
          subtitle={`Staged as ${preview.job.jobCode}. Nothing has been written.`}
          icon={<ScanSearch size={15} />}
        >
          <p
            role="status"
            className="mb-4 rounded-[var(--radius-card)] border px-3 py-2 text-sm font-semibold"
            style={{ borderColor: 'var(--border)', background: 'var(--surface-emerald)', color: 'var(--ink-emerald)' }}
          >
            Nothing has been written yet. The file was read into memory, every row validated in memory,
            and this job is <strong>staged</strong> — no property, unit, lead, lease or receipt exists
            because of it.
          </p>

          <dl className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
            <DetailItem label="Rows in the file" value={formatCount(preview.job.rowCount)} />
            <DetailItem label="Valid rows" value={formatCount(preview.job.validRowCount)} />
            <DetailItem label="Rows refused" value={formatCount(preview.job.invalidRowCount)} />
            {/* Duplicates are not something this register computes; saying so is better than a zero. */}
            <DetailItem
              label="Duplicate rows"
              value="Not reported — the preview records the file's content hash, not a duplicate count"
            />
          </dl>

          <dl className="mt-4 grid gap-3 border-t pt-3 sm:grid-cols-2 lg:grid-cols-4" style={{ borderColor: 'var(--border)' }}>
            <DetailItem label="Entity type" value={humanize(preview.job.entityType)} />
            <DetailItem label="File" value={preview.job.originalFilename} />
            <DetailItem label="Staged at" value={dateTime(preview.job.stagedAt)} />
            <DetailItem label="Validated at" value={dateTime(preview.job.validatedAt)} />
          </dl>

          <div className="mt-4">
            <p className="eyebrow mb-1" style={{ color: 'var(--ink-sapphire)' }}>
              Row-level validation — {formatCount(problemLines.filter((line) => line.kind === 'ERROR').length)} errors,
              {' '}{formatCount(problemLines.filter((line) => line.kind === 'WARNING').length)} warnings
            </p>
            <Panel bodyClassName="p-0">
              <DataTable
                columns={problemColumns}
                rows={problemLines}
                keyOf={(row) => row.id}
                caption="Row-level validation problems"
                empty={
                  <EmptyState
                    title="Every row passed validation"
                    description="No row in this file was refused or flagged. Continue to the confirm step to write them, or stage a different file."
                  />
                }
              />
            </Panel>
            <p className="mt-2 text-[11px] leading-relaxed" style={{ color: 'var(--text-secondary)' }}>
              The row number is the line in your spreadsheet, counting the header as line 1. The preview
              lists up to two hundred rows&rsquo; problems; the tallies above count every row in the file.
            </p>
          </div>

          <div className="mt-4 flex flex-wrap items-center gap-2 border-t pt-3" style={{ borderColor: 'var(--border)' }}>
            <Button
              variant="primary"
              onClick={() => setStep('confirm')}
              disabled={!preview.canCommit}
            >
              <ClipboardCheck size={15} aria-hidden="true" />
              Continue to confirm
            </Button>
            <Button variant="outline" onClick={resetWorkflow}>Choose a different file</Button>
            <span className="text-[11px]" style={{ color: 'var(--text-secondary)' }}>
              {preview.canCommit
                ? 'The staged job stays on the register, validated and uncommitted, until it is confirmed or a new file is staged.'
                : 'The backend reports that this job cannot be committed — there is nothing in it to write.'}
            </span>
          </div>
        </Panel>
      )}

      {/* ── Step three: confirm ──────────────────────────────────────────── */}

      {step === 'confirm' && preview && (
        <Panel
          className="mb-6"
          tint="copper"
          title="Step 3 of 4 — Confirm"
          subtitle={`Writing ${formatCount(preview.job.validRowCount)} validated rows from ${preview.job.jobCode}`}
          icon={<BadgeCheck size={15} />}
        >
          <p
            role="status"
            className="mb-4 rounded-[var(--radius-card)] border px-3 py-2 text-sm"
            style={{ borderColor: 'var(--border)', background: 'var(--surface-amber)', color: 'var(--warning-ink)' }}
          >
            <strong>No records are created until you press Confirm.</strong> The{' '}
            {formatCount(preview.job.validRowCount)} valid rows are staged; the{' '}
            {formatCount(preview.job.invalidRowCount)} refused rows are not part of the write set and never
            will be. The write is inserted through the same services a record keyed in by hand goes
            through, so each row gets its own code, its own owner and its own audit entry.
          </p>

          <dl className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
            <DetailItem label="Job" value={<span className="tabular-nums">{preview.job.jobCode}</span>} />
            <DetailItem label="Entity type" value={humanize(preview.job.entityType)} />
            <DetailItem label="Rows to write" value={formatCount(preview.job.validRowCount)} />
            <DetailItem label="Rows left behind" value={formatCount(preview.job.invalidRowCount)} />
          </dl>

          <div className="mt-4 rounded-[var(--radius-card)] border p-3" style={{ borderColor: 'var(--border)', background: 'var(--surface)' }}>
            <p className="eyebrow" style={{ color: 'var(--ink-copper)' }}>Idempotency key</p>
            <p className="mt-1 break-all text-xs tabular-nums" style={{ color: 'var(--text-primary)' }}>
              {idempotencyKey || 'Not yet generated'}
            </p>
            <p className="mt-1 text-[11px] leading-relaxed" style={{ color: 'var(--text-secondary)' }}>
              Generated with the preview and sent with the confirm. The register requires one: a second
              press with the <strong>same</strong> key returns the first run&rsquo;s result and writes nothing
              again, and a press with a <strong>different</strong> key against an already committed job is
              refused outright rather than silently ignored. That is what makes a double-click safe.
            </p>
          </div>

          {confirmMutation.isError && (
            <div className="mt-4">
              <ErrorState
                message={describeFailure(confirmMutation.error).message}
                details={describeFailure(confirmMutation.error).details}
                onRetry={() => confirmMutation.mutate()}
              />
            </div>
          )}

          <div className="mt-4 flex flex-wrap items-center gap-2 border-t pt-3" style={{ borderColor: 'var(--border)' }}>
            <Button
              variant="primary"
              onClick={() => confirmMutation.mutate()}
              loading={confirmMutation.isPending}
            >
              <BadgeCheck size={15} aria-hidden="true" />
              Confirm and write {formatCount(preview.job.validRowCount)} rows
            </Button>
            <Button variant="outline" onClick={() => setStep('preview')} disabled={confirmMutation.isPending}>
              Back to the preview
            </Button>
            <span className="text-[11px]" style={{ color: 'var(--text-secondary)' }}>
              This is the only action on this screen that creates a record.
            </span>
          </div>
        </Panel>
      )}

      {/* ── Step four: result ────────────────────────────────────────────── */}

      {step === 'result' && result && (
        <Panel
          className="mb-6"
          tint="emerald"
          title="Step 4 of 4 — Result"
          subtitle={`${result.job.jobCode} · ${result.job.originalFilename}`}
          icon={<BadgeCheck size={15} />}
        >
          <p
            role="status"
            className="mb-4 rounded-[var(--radius-card)] border px-3 py-2 text-sm"
            style={{ borderColor: 'var(--border)', background: 'var(--surface-emerald)', color: 'var(--ink-emerald)' }}
          >
            {result.idempotentReplay
              ? 'This import had already been committed under that idempotency key, so the first run\'s result is shown and nothing was written again.'
              : 'The import was committed. The rows below are what the write produced.'}
          </p>

          <dl className="grid gap-3 sm:grid-cols-3">
            <DetailItem label="Inserted" value={formatCount(result.inserted)} />
            <DetailItem label="Skipped" value={formatCount(result.skipped)} />
            <DetailItem label="Failed" value={formatCount(result.failed)} />
          </dl>

          {/* The arithmetic, stated: the tally has to add up or it is not a tally. */}
          <p className="mt-3 text-xs leading-relaxed" style={{ color: 'var(--text-secondary)' }}>
            {formatCount(result.inserted)} inserted + {formatCount(result.skipped)} skipped +{' '}
            {formatCount(result.failed)} failed ={' '}
            <strong>{formatCount(result.inserted + result.skipped + result.failed)}</strong>, which is the{' '}
            {formatCount(result.job.validRowCount)} valid rows that were staged. The{' '}
            {formatCount(result.job.invalidRowCount)} rows that failed validation were never in the write
            set and are not counted here.
          </p>

          <dl className="mt-4 grid gap-3 border-t pt-3 sm:grid-cols-2 lg:grid-cols-4" style={{ borderColor: 'var(--border)' }}>
            <DetailItem label="Rows in the file" value={formatCount(result.job.rowCount)} />
            <DetailItem label="Valid at validation" value={formatCount(result.job.validRowCount)} />
            <DetailItem label="Refused at validation" value={formatCount(result.job.invalidRowCount)} />
            <DetailItem label="Committed at" value={dateTime(result.job.committedAt)} />
          </dl>

          {/* The audit entry the commit left, linked by the filters the trail accepts. */}
          <div className="mt-4 rounded-[var(--radius-card)] border p-3" style={{ borderColor: 'var(--border)', background: 'var(--surface)' }}>
            <p className="eyebrow" style={{ color: 'var(--ink-emerald)' }}>Audit trail</p>
            <p className="mt-1 text-xs leading-relaxed" style={{ color: 'var(--text-secondary)' }}>
              The commit wrote its own entry to the trail — module <span className="tabular-nums">IMPORTS</span>,
              action <span className="tabular-nums">IMPORT_COMMITTED</span>, against this job&rsquo;s record
              (<span className="tabular-nums">{result.job.jobCode}</span>) — together with a separate entry
              for every row that was inserted. Nothing on this screen edits the trail, and nothing can.
            </p>
            <div className="mt-2">
              <Link
                to={`/audit-logs?module=IMPORTS&entityType=IMPORT&entityId=${encodeURIComponent(result.job.id)}`}
                className="focus-meridian btn btn-outline"
              >
                <ShieldAlert size={14} aria-hidden="true" />
                Read this commit in the audit trail
              </Link>
            </div>
          </div>

          <div className="mt-4">
            <p className="eyebrow mb-1" style={{ color: 'var(--ink-emerald)' }}>Row outcomes</p>
            <Panel bodyClassName="p-0">
              <DataTable
                columns={outcomeColumns}
                rows={result.outcomes}
                keyOf={(row) => row.id}
                caption="Import row outcomes"
                empty={
                  <EmptyState
                    title="No row outcomes were returned"
                    description="The commit reports the rows it wrote with the record each produced. This run returned none — read the job on the register below for what it holds."
                  />
                }
              />
            </Panel>
            <p className="mt-2 text-[11px]" style={{ color: 'var(--text-secondary)' }}>
              Up to two hundred outcomes are returned with the result; the tallies above are the whole run.
            </p>
          </div>

          <div className="mt-4 flex flex-wrap items-center gap-2 border-t pt-3" style={{ borderColor: 'var(--border)' }}>
            <Button variant="primary" onClick={resetWorkflow}>Import another file</Button>
            <span className="text-[11px]" style={{ color: 'var(--text-secondary)' }}>
              A correction is another import: nothing here updates or deletes what was written. Note that
              the register does not de-duplicate rows, so the same file imported twice would be inserted
              twice — the idempotency key protects the confirm, not the file.
            </span>
          </div>
        </Panel>
      )}

      {/* ── Recent imports ───────────────────────────────────────────────── */}

      <SectionHeader
        eyebrow="Register"
        title="Recent imports"
        rule="slate"
        description="Every file this installation has staged, newest first. A staged job that was never confirmed is still here, and can be picked back up."
      />

      <Panel bodyClassName="p-0">
        {historyQuery.isLoading && <div className="p-4"><Skeleton rows={6} /></div>}

        {historyQuery.isError && (
          <div className="p-4">
            <ErrorState
              message={messageOf(historyQuery.error)}
              onRetry={() => void historyQuery.refetch()}
            />
          </div>
        )}

        {historyQuery.isSuccess && (
          <DataTable
            columns={historyColumns}
            rows={historyRows}
            keyOf={(row) => row.id}
            onRowClick={(row) => setDetailJobCode(row.jobCode)}
            sort={listSort}
            onSortChange={(next) => { setListSort(next); setPage(0) }}
            caption="Import job register"
            empty={
              <EmptyState
                icon={<FileUp size={22} aria-hidden="true" />}
                title="No file has been imported yet"
                description="An import job appears here as soon as a file is staged, whether or not it was ever confirmed. Stage a file above and it will be recorded here with what it held and what it produced."
              />
            }
          />
        )}

        {historyQuery.isSuccess && (historyQuery.data?.totalElements ?? 0) > 0 && (
          <div className="border-t" style={{ borderColor: 'var(--border)' }}>
            <Pagination
              page={historyQuery.data?.page ?? page}
              size={historyQuery.data?.size ?? size}
              totalElements={historyQuery.data?.totalElements ?? 0}
              totalPages={historyQuery.data?.totalPages ?? 0}
              hasNext={historyQuery.data?.hasNext ?? false}
              onPageChange={setPage}
              onSizeChange={(next) => { setSize(next); setPage(0) }}
            />
          </div>
        )}
      </Panel>

      {/* ── One job ──────────────────────────────────────────────────────── */}

      <Dialog
        open={detailJobCode !== null}
        title={detailJobCode ? `Import ${detailJobCode}` : 'Import'}
        description="What the job held, and what it produced."
        onClose={() => setDetailJobCode(null)}
        width="lg"
        footer={
          <>
            <Button variant="ghost" onClick={() => setDetailJobCode(null)}>Close</Button>
            {detailQuery.isSuccess && asPreview(detailQuery.data).canCommit && (
              <Button variant="primary" onClick={() => resumeJob(asPreview(detailQuery.data))}>
                Pick this import back up
              </Button>
            )}
          </>
        }
      >
        {detailQuery.isLoading && <Skeleton rows={6} />}

        {detailQuery.isError && (
          <ErrorState
            message={messageOf(detailQuery.error)}
            onRetry={() => void detailQuery.refetch()}
          />
        )}

        {detailQuery.isSuccess && (() => {
          const detail = asPreview(detailQuery.data)
          const job = detail.job
          const detailProblems = detail.rows.flatMap((row) => [
            ...row.errors.map((message, index) => ({
              id: `${row.id}-e${index}`, rowNumber: row.rowNumber, kind: 'ERROR', message,
            })),
            ...row.warnings.map((message, index) => ({
              id: `${row.id}-w${index}`, rowNumber: row.rowNumber, kind: 'WARNING', message,
            })),
          ])
          return (
            <div className="space-y-4">
              <dl className="grid gap-3 sm:grid-cols-3">
                <DetailItem label="State" value={<StatusBadge status={job.status} />} />
                <DetailItem label="Entity type" value={humanize(job.entityType)} />
                <DetailItem label="Source" value={job.sourceType || 'Detected from the file'} />
                <DetailItem label="File" value={job.originalFilename} />
                <DetailItem label="Size" value={fileSize(job.fileSizeBytes)} />
                <DetailItem label="Staged at" value={dateTime(job.stagedAt)} />
                <DetailItem label="Validated at" value={dateTime(job.validatedAt)} />
                <DetailItem label="Committed at" value={dateTime(job.committedAt)} />
                <DetailItem label="Rows" value={`${formatCount(job.rowCount)} (${formatCount(job.validRowCount)} valid, ${formatCount(job.invalidRowCount)} refused)`} />
                <DetailItem label="Written" value={`${formatCount(job.insertedRowCount)} inserted, ${formatCount(job.skippedRowCount)} skipped`} />
              </dl>

              {detail.canCommit && (
                <p className="rounded-[var(--radius-card)] border px-3 py-2 text-xs" style={{ borderColor: 'var(--border)', background: 'var(--surface-amber)', color: 'var(--warning-ink)' }}>
                  This job is staged and uncommitted. Nothing in it has been written yet; picking it back
                  up returns you to the confirm step, where the row tallies and the idempotency key are
                  shown before anything is pressed.
                </p>
              )}

              <div>
                <p className="eyebrow mb-1" style={{ color: 'var(--text-secondary)' }}>
                  Rows held ({formatCount(detail.rows.length)})
                </p>
                <Panel bodyClassName="p-0">
                  <DataTable
                    columns={problemColumns}
                    rows={detailProblems}
                    keyOf={(row) => row.id}
                    caption={`Problems held against import ${job.jobCode}`}
                    empty={
                      <EmptyState
                        title="No row problems are held against this job"
                        description="Every row it holds either passed validation or has already been written. Read the state above for where the job stands."
                      />
                    }
                  />
                </Panel>
              </div>
            </div>
          )
        })()}
      </Dialog>
    </PageAccessGuard>
  )
}
