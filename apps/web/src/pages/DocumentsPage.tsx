import { useMemo, useState } from 'react'
import { useMutation, useQueries, useQuery, useQueryClient } from '@tanstack/react-query'
import { CalendarClock, FilePlus2, FileText, Info, Layers } from 'lucide-react'
import { PageAccessGuard, PageHeader } from '../components/common'
import {
  Button, DataTable, DetailItem, Dialog, EmptyState, ErrorState, Field, Input, KpiTile,
  Pagination, Panel, SectionHeader, Select, Skeleton, StatusBadge, Textarea, Toolbar, useToast,
  type Column,
} from '../components/ui'
import { count as formatCount, date, daysUntil, humanize } from '../components/ui/formatters'
import {
  buyerApi, documentApi, leaseApi, maintenanceApi, ownerApi, propertyApi, saleApi, tenantApi, unitApi,
} from '../services'
import { usePlatformStore } from '../platform'
import { messageOf } from '../lib/errors'
import type { DocumentSummaryRow } from '../types'

/**
 * The closed sets the register enforces.
 *
 * `ck_documents_type` and `ck_documents_status` are the database's own checks, and
 * `DocumentDtos.typeValues()` is the backend's list of them. A picker has to offer *something*, so it
 * offers exactly these — a value outside the set would be refused by the register with a constraint
 * violation rather than being quietly ignored, and offering a free-text box for a closed set is how
 * a file ends up rejected after everything has been typed.
 */
const DOCUMENT_TYPES = [
  'LEASE_AGREEMENT', 'SALE_DEED', 'TITLE_DEED', 'AGREEMENT_TO_SELL', 'NOC', 'TAX_INVOICE',
  'INSURANCE_POLICY', 'COMPLIANCE_CERTIFICATE', 'ID_PROOF', 'FLOOR_PLAN', 'SITE_PLAN',
  'VALUATION_REPORT', 'HANDOVER_NOTE', 'POSSESSION_LETTER', 'UTILITY_BILL', 'OTHER',
] as const

const DOCUMENT_STATUSES = ['ACTIVE', 'SUPERSEDED', 'EXPIRED', 'ARCHIVED'] as const

/** A record a document can be filed against, in the shape a picker needs. */
interface PickerOption {
  id: string
  label: string
}

/**
 * The eight filing targets the register accepts.
 *
 * <p>A document is filed against a real foreign key — the schema requires at least one — so the
 * create dialog cannot accept a free-text record name; it has to offer the records that exist. Each
 * list below is the register's own first page, read from its own endpoint, and the option's label is
 * the code a person would recognise. No sort is requested: a register that does not whitelist a field
 * answers with a 400, so these read in the register's own order rather than in an order guessed at
 * here.</p>
 */
interface FilingTarget {
  /** The request field the identifier is sent in (`DocumentDtos.CreateDocumentRequest`). */
  field: string
  label: string
  load: () => Promise<PickerOption[]>
}

const FILING_TARGETS: FilingTarget[] = [
  {
    field: 'propertyId',
    label: 'Property',
    load: async () => (await propertyApi.list({ size: 100 })).items
      .map((row) => ({ id: row.id, label: `${row.propertyCode} · ${row.name}` })),
  },
  {
    field: 'unitId',
    label: 'Unit',
    load: async () => (await unitApi.list({ size: 100 })).items
      .map((row) => ({ id: row.id, label: `${row.unitCode} · ${row.propertyName ?? 'unit'}` })),
  },
  {
    field: 'leaseId',
    label: 'Lease',
    load: async () => (await leaseApi.list({ size: 100 })).items
      .map((row) => ({ id: row.id, label: `${row.leaseCode} · ${row.tenantName ?? 'tenant'}` })),
  },
  {
    field: 'tenantId',
    label: 'Tenant',
    load: async () => (await tenantApi.list({ size: 100 })).items
      .map((row) => ({ id: row.id, label: `${row.tenantCode} · ${row.displayName}` })),
  },
  {
    field: 'ownerId',
    label: 'Owner',
    load: async () => (await ownerApi.list({ size: 100 })).items
      .map((row) => ({ id: row.id, label: `${row.ownerCode} · ${row.displayName}` })),
  },
  {
    field: 'buyerId',
    label: 'Buyer',
    load: async () => (await buyerApi.list({ size: 100 })).items
      .map((row) => ({ id: row.id, label: `${row.buyerCode} · ${row.displayName}` })),
  },
  {
    field: 'saleId',
    label: 'Sale',
    load: async () => (await saleApi.list({ size: 100 })).items
      .map((row) => ({ id: row.id, label: `${row.saleCode} · ${row.buyerName ?? 'buyer'}` })),
  },
  {
    field: 'maintenanceRequestId',
    label: 'Maintenance request',
    load: async () => (await maintenanceApi.list({ size: 100 })).items
      .map((row) => ({ id: row.id, label: `${row.requestCode} · ${row.propertyName ?? 'request'}` })),
  },
]

/** "expires in 12 days", "expired 4 days ago" — the reading a watchlist is actually for. */
function expiryReading(expiryDate: string | null): string {
  const remaining = daysUntil(expiryDate)
  if (remaining === null) return 'No expiry date on record'
  if (remaining === 0) return 'Expires today'
  if (remaining > 0) return `Expires in ${remaining} ${remaining === 1 ? 'day' : 'days'}`
  const overdue = Math.abs(remaining)
  return `Expired ${overdue} ${overdue === 1 ? 'day' : 'days'} ago`
}

const EMPTY_CREATE_FORM = {
  title: '',
  documentType: '',
  targetField: '',
  targetId: '',
  fileReference: '',
  issuedDate: '',
  expiryDate: '',
  confidential: false,
  notes: '',
}

/**
 * The document register.
 *
 * <p><b>This register records that a document exists, and where it lives — it does not hold the
 * document.</b> `DocumentDtos` says so in as many words: the product has no object storage, and the
 * `fileReference` on an entry is the location a person noted, not bytes. So there is no upload
 * control anywhere on this screen and no URL is ever minted: the schema stores no file, and offering
 * a dropzone that had nowhere to put the file would be the single most misleading control the product
 * could hold.</p>
 *
 * <p><b>A document is versioned, never overwritten.</b> Superseding marks the earlier entry and points
 * it at its replacement; both stay readable, so a lease can still be read under the terms that were
 * actually signed. The screen explains that rule where a reader would look for a pencil — see the
 * superseding note in the entry dialog.</p>
 */
export default function DocumentsPage() {
  const queryClient = useQueryClient()
  const toast = useToast()
  const profile = usePlatformStore((state) => state.profile)
  const watchlistWindow = profile?.policies?.leaseExpiryWarningDays ?? 60

  const [page, setPage] = useState(0)
  const [size, setSize] = useState(20)
  const [sort, setSort] = useState('createdAt,desc')
  const [search, setSearch] = useState('')
  const [targetField, setTargetField] = useState('')
  const [targetId, setTargetId] = useState('')
  const [type, setType] = useState('')
  const [status, setStatus] = useState('')
  const [expiringWithin, setExpiringWithin] = useState('')

  const [createOpen, setCreateOpen] = useState(false)
  const [createForm, setCreateForm] = useState({ ...EMPTY_CREATE_FORM })
  const [formError, setFormError] = useState<string | null>(null)
  const [fieldErrors, setFieldErrors] = useState<{ title?: string; documentType?: string; target?: string; expiry?: string }>({})
  const [selected, setSelected] = useState<DocumentSummaryRow | null>(null)

  const targetQueries = useQueries({
    queries: FILING_TARGETS.map((target) => ({
      queryKey: ['documents', 'filing-targets', target.field],
      queryFn: target.load,
      staleTime: 300_000,
    })),
  })

  const listQuery = useQuery({
    queryKey: ['documents', 'list', {
      page, size, sort, search, targetField, targetId, type, status, expiringWithin,
    }],
    queryFn: () => documentApi.list({
      page,
      size,
      sort,
      search: search || undefined,
      type: type || undefined,
      status: status || undefined,
      expiringWithinDays: expiringWithin ? Number(expiringWithin) : undefined,
      // The register takes one filing target per filter, so the pair is sent together: a target type
      // with no record chosen is not a narrower question, it is no question at all.
      ...(targetField && targetId ? { [targetField]: targetId } : {}),
    }),
  })

  const expiringQuery = useQuery({
    queryKey: ['documents', 'expiring'],
    queryFn: () => documentApi.expiring(),
  })

  const createDocument = useMutation({
    mutationFn: (body: Record<string, unknown>) => documentApi.create(body),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['documents'] })
      toast.push('The document was registered. The register records metadata only — no file was uploaded.', 'emerald')
      setCreateOpen(false)
      setCreateForm({ ...EMPTY_CREATE_FORM })
      setFieldErrors({})
      setFormError(null)
    },
    onError: (error) => setFormError(messageOf(error)),
  })

  /** Validate locally, then post only the fields a person actually filled in. */
  function submitCreate(): void {
    if (createDocument.isPending) return

    const next: typeof fieldErrors = {}
    if (!createForm.title.trim()) next.title = 'Give the document the title it is known by'
    if (!createForm.documentType) next.documentType = 'Choose the type the register files it under'
    if (!createForm.targetField || !createForm.targetId) {
      next.target = 'File it against the record it belongs to — an entry filed against nothing is not a register entry'
    }
    if (createForm.issuedDate && createForm.expiryDate && createForm.expiryDate < createForm.issuedDate) {
      next.expiry = 'A document cannot expire before it was issued'
    }
    setFieldErrors(next)
    setFormError(null)
    if (Object.keys(next).length > 0) return

    const body: Record<string, unknown> = {
      title: createForm.title.trim(),
      documentType: createForm.documentType,
    }
    if (createForm.targetField && createForm.targetId) body[createForm.targetField] = createForm.targetId
    if (createForm.fileReference.trim()) body.fileReference = createForm.fileReference.trim()
    if (createForm.issuedDate) body.issuedDate = createForm.issuedDate
    if (createForm.expiryDate) body.expiryDate = createForm.expiryDate
    if (createForm.confidential) body.confidential = true
    if (createForm.notes.trim()) body.notes = createForm.notes.trim()

    createDocument.mutate(body)
  }

  /**
   * Begin the later version of an entry.
   *
   * This is the one step of superseding this screen can actually take: registering the replacement.
   * The register links the two when the replacement exists, which is why the dialog explains the rule
   * rather than offering a button that could not honour it.
   */
  function beginLaterVersion(row: DocumentSummaryRow): void {
    setSelected(null)
    setCreateForm({
      ...EMPTY_CREATE_FORM,
      title: row.title,
      documentType: row.documentType,
      notes: `A later version of ${row.documentCode}.`,
    })
    setFieldErrors({})
    setFormError(null)
    setCreateOpen(true)
  }

  const columns = useMemo<Column<DocumentSummaryRow>[]>(() => [
    {
      key: 'code',
      header: 'Code',
      width: '13%',
      sortKey: 'documentCode',
      render: (row) => (
        <span className="text-xs font-semibold tabular-nums" style={{ color: 'var(--text-primary)' }}>
          {row.documentCode}
        </span>
      ),
    },
    {
      key: 'title',
      header: 'Title',
      width: '22%',
      sortKey: 'title',
      render: (row) => (
        <span className="truncate text-xs font-medium" style={{ color: 'var(--text-primary)' }}>{row.title}</span>
      ),
    },
    {
      key: 'type',
      header: 'Type',
      width: '13%',
      sortKey: 'documentType',
      render: (row) => (
        <span className="text-xs" style={{ color: 'var(--text-secondary)' }}>{humanize(row.documentType)}</span>
      ),
    },
    {
      key: 'filed',
      header: 'Filed against',
      width: '14%',
      secondary: true,
      render: (row) => (
        <span className="truncate text-xs" style={{ color: 'var(--text-secondary)' }}>
          {row.propertyName ?? '—'}
        </span>
      ),
    },
    {
      key: 'version',
      header: 'Version',
      width: '8%',
      align: 'right',
      render: (row) => (
        <span className="tabular-nums text-xs" style={{ color: 'var(--text-primary)' }}>
          v{row.version}
        </span>
      ),
    },
    {
      key: 'status',
      header: 'Status',
      width: '11%',
      sortKey: 'status',
      render: (row) => <StatusBadge status={row.status} />,
    },
    {
      key: 'expiry',
      header: 'Expiry',
      width: '13%',
      sortKey: 'expiryDate',
      render: (row) => {
        const remaining = daysUntil(row.expiryDate)
        return (
          <span className="text-xs">
            <span className="block" style={{ color: 'var(--text-primary)' }}>{date(row.expiryDate)}</span>
            {remaining !== null && (
              <span
                className="block text-[10px]"
                // The state is written, not only coloured: "expired" and "expires today" are words.
                style={{ color: remaining < 0 ? 'var(--danger)' : remaining <= watchlistWindow ? 'var(--warning-ink)' : 'var(--text-secondary)' }}
              >
                {expiryReading(row.expiryDate)}
              </span>
            )}
          </span>
        )
      },
    },
    {
      key: 'created',
      header: 'Registered',
      width: '12%',
      secondary: true,
      sortKey: 'createdAt',
      render: (row) => (
        <span className="text-xs" style={{ color: 'var(--text-secondary)' }}>{date(row.createdAt)}</span>
      ),
    },
  ], [watchlistWindow])

  const targetOptions: PickerOption[] = useMemo(() => {
    const index = FILING_TARGETS.findIndex((target) => target.field === createForm.targetField)
    if (index < 0) return []
    return targetQueries[index]?.data ?? []
  }, [createForm.targetField, targetQueries])

  const filterTargetOptions: PickerOption[] = useMemo(() => {
    const index = FILING_TARGETS.findIndex((target) => target.field === targetField)
    if (index < 0) return []
    return targetQueries[index]?.data ?? []
  }, [targetField, targetQueries])

  const watchlist = expiringQuery.data ?? []
  const rows = listQuery.data?.items ?? []
  const hasFilters = Boolean(search || targetField || type || status || expiringWithin)

  function clearFilters(): void {
    setSearch('')
    setTargetField('')
    setTargetId('')
    setType('')
    setStatus('')
    setExpiringWithin('')
    setPage(0)
  }

  return (
    <PageAccessGuard permission="DOCUMENT_VIEW">
      <PageHeader
        eyebrow="Records"
        title="Documents"
        description="What the firm holds on record, what it is filed against, and when it lapses. The register records where a document lives — it does not hold the file itself."
        actions={
          <Button variant="primary" onClick={() => { setFormError(null); setCreateOpen(true) }}>
            <FilePlus2 size={15} aria-hidden="true" />
            Register a document
          </Button>
        }
      />

      <div className="mb-5 grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
        <KpiTile
          label="On record"
          value={listQuery.isError ? '—' : formatCount(listQuery.data?.totalElements ?? 0)}
          tone="forest"
          icon={<FileText size={15} />}
          caption={hasFilters ? 'Matching your filters' : 'The whole register'}
        />
        <KpiTile
          label="Lapsing soon"
          value={expiringQuery.isError ? '—' : formatCount(watchlist.length)}
          tone={watchlist.length > 0 ? 'amber' : 'emerald'}
          icon={<CalendarClock size={15} />}
          caption={`Within the firm's ${watchlistWindow}-day warning window`}
        />
        <KpiTile
          label="Versions held"
          value={rows.length > 0 ? `${Math.max(...rows.map((row) => row.version))}` : '—'}
          tone="sandstone"
          icon={<Layers size={15} />}
          caption="The highest version on this page"
        />
        <KpiTile
          label="Uploads"
          value="None"
          tone="slate"
          icon={<Info size={15} />}
          caption="This product stores no file; the register records where one lives"
        />
      </div>

      {/* ── The expiry watchlist ─────────────────────────────────────────── */}

      <SectionHeader
        eyebrow="Renewals"
        title="Expiry watchlist"
        rule="amber"
        description={`Live documents that lapse within ${watchlistWindow} days, soonest first — read from the register's own watchlist, which is driven by the installation's warning window.`}
      />
      <Panel className="mb-6" tint="amber">
        {expiringQuery.isLoading && <Skeleton rows={4} />}

        {expiringQuery.isError && (
          <ErrorState
            message={messageOf(expiringQuery.error)}
            onRetry={() => void expiringQuery.refetch()}
          />
        )}

        {expiringQuery.isSuccess && watchlist.length === 0 && (
          <EmptyState
            title="Nothing lapses in the warning window"
            description={`The watchlist holds live documents with an expiry date inside ${watchlistWindow} days. Nothing on record lapses that soon, so there is nothing to renew yet.`}
          />
        )}

        {expiringQuery.isSuccess && watchlist.length > 0 && (
          <ul className="divide-y" style={{ borderColor: 'var(--border)' }}>
            {watchlist.map((row) => {
              const remaining = daysUntil(row.expiryDate)
              const urgent = remaining !== null && remaining <= Math.round(watchlistWindow / 2)
              return (
                <li key={row.id} className="flex flex-wrap items-baseline justify-between gap-x-4 gap-y-1 py-2.5 first:pt-0 last:pb-0">
                  <span className="min-w-0">
                    <span className="block truncate text-xs font-semibold" style={{ color: 'var(--text-primary)' }}>
                      {row.title}
                    </span>
                    <span className="block text-[11px]" style={{ color: 'var(--text-secondary)' }}>
                      <span className="tabular-nums">{row.documentCode}</span> · {humanize(row.documentType)}
                      {row.propertyName ? ` · ${row.propertyName}` : ''}
                    </span>
                  </span>
                  <span className="shrink-0 text-right">
                    <span className="block text-xs tabular-nums" style={{ color: 'var(--text-primary)' }}>
                      {date(row.expiryDate)}
                    </span>
                    <span
                      className="block text-[11px] font-semibold"
                      style={{ color: urgent ? 'var(--danger)' : 'var(--warning-ink)' }}
                    >
                      {expiryReading(row.expiryDate)}
                    </span>
                  </span>
                </li>
              )
            })}
          </ul>
        )}

        <p className="mt-3 border-t pt-3 text-[11px]" style={{ color: 'var(--text-secondary)' }}>
          The watchlist is read, not clicked: this build has no per-document screen, so an entry is named
          with the code the register files it under rather than offered as a link to nothing.
        </p>
      </Panel>

      {/* ── The register ─────────────────────────────────────────────────── */}

      <SectionHeader
        eyebrow="Register"
        title="Every document on record"
        rule="copper"
        description="Filtered and paged by the endpoint, so the count above is the register's count rather than the page's."
      />

      <Panel bodyClassName="p-0">
        <Toolbar>
          <Field label="Search" htmlFor="document-search">
            <Input
              id="document-search"
              name="search"
              className="w-52"
              value={search}
              placeholder="Code, title or reference"
              onChange={(event) => { setSearch(event.target.value); setPage(0) }}
            />
          </Field>

          <Field label="Filed against" htmlFor="document-target-type">
            <Select
              id="document-target-type"
              className="w-40"
              value={targetField}
              onChange={(event) => { setTargetField(event.target.value); setTargetId(''); setPage(0) }}
            >
              <option value="">Any record type</option>
              {FILING_TARGETS.map((target) => (
                <option key={target.field} value={target.field}>{target.label}</option>
              ))}
            </Select>
          </Field>

          {targetField && (
            <Field label="That record" htmlFor="document-target-record">
              <Select
                id="document-target-record"
                className="w-56"
                value={targetId}
                onChange={(event) => { setTargetId(event.target.value); setPage(0) }}
              >
                <option value="">Choose a record</option>
                {filterTargetOptions.map((option) => (
                  <option key={option.id} value={option.id}>{option.label}</option>
                ))}
              </Select>
            </Field>
          )}

          <Field label="Type" htmlFor="document-type">
            <Select
              id="document-type"
              className="w-44"
              value={type}
              onChange={(event) => { setType(event.target.value); setPage(0) }}
            >
              <option value="">Any type</option>
              {DOCUMENT_TYPES.map((option) => (
                <option key={option} value={option}>{humanize(option)}</option>
              ))}
            </Select>
          </Field>

          <Field label="Status" htmlFor="document-status">
            <Select
              id="document-status"
              className="w-36"
              value={status}
              onChange={(event) => { setStatus(event.target.value); setPage(0) }}
            >
              <option value="">Any status</option>
              {DOCUMENT_STATUSES.map((option) => (
                <option key={option} value={option}>{humanize(option)}</option>
              ))}
            </Select>
          </Field>

          <Field label="Expiring within" htmlFor="document-expiring">
            <Select
              id="document-expiring"
              className="w-40"
              value={expiringWithin}
              onChange={(event) => { setExpiringWithin(event.target.value); setPage(0) }}
            >
              <option value="">Any time</option>
              <option value="30">30 days</option>
              <option value="60">60 days</option>
              <option value="90">90 days</option>
            </Select>
          </Field>

          {hasFilters && (
            <Button size="sm" variant="ghost" onClick={clearFilters}>Clear filters</Button>
          )}

          <p className="ml-auto text-xs" aria-live="polite" style={{ color: 'var(--text-secondary)' }}>
            {listQuery.isSuccess
              ? `Showing ${formatCount(rows.length)} of ${formatCount(listQuery.data?.totalElements ?? 0)}`
              : ''}
          </p>
        </Toolbar>

        {listQuery.isLoading && <div className="p-4"><Skeleton rows={6} /></div>}

        {listQuery.isError && (
          <div className="p-4">
            <ErrorState
              message={messageOf(listQuery.error)}
              onRetry={() => void listQuery.refetch()}
            />
          </div>
        )}

        {listQuery.isSuccess && (
          <DataTable
            columns={columns}
            rows={rows}
            keyOf={(row) => row.id}
            onRowClick={(row) => setSelected(row)}
            sort={sort}
            onSortChange={(next) => { setSort(next); setPage(0) }}
            caption="Document register"
            empty={
              <EmptyState
                icon={<FileText size={22} aria-hidden="true" />}
                title={hasFilters ? 'No documents match these filters' : 'No documents are on record'}
                description={hasFilters
                  ? 'The register holds nothing filed the way you asked. Clear a filter, or widen the expiry window, to see what the firm has.'
                  : 'A document is registered against the property, unit, lease, tenant, owner, buyer, sale or maintenance request it belongs to. Register the first one and this register will fill.'}
                action={hasFilters ? <Button size="sm" onClick={clearFilters}>Clear filters</Button> : undefined}
              />
            }
          />
        )}

        {listQuery.isSuccess && (listQuery.data?.totalElements ?? 0) > 0 && (
          <div className="border-t" style={{ borderColor: 'var(--border)' }}>
            <Pagination
              page={listQuery.data?.page ?? page}
              size={listQuery.data?.size ?? size}
              totalElements={listQuery.data?.totalElements ?? 0}
              totalPages={listQuery.data?.totalPages ?? 0}
              hasNext={listQuery.data?.hasNext ?? false}
              onPageChange={setPage}
              onSizeChange={(next) => { setSize(next); setPage(0) }}
            />
          </div>
        )}
      </Panel>

      <p className="mt-4 max-w-4xl text-xs leading-relaxed" style={{ color: 'var(--text-secondary)' }}>
        <strong>Where the file is, and where it is not.</strong> This register stores the metadata that
        identifies a document and the reference to where it lives; it does not store the file. There is
        no upload on this screen and no link is generated — a link this product cannot honour would be a
        promise the schema does not keep.
      </p>

      {/* ── One entry ────────────────────────────────────────────────────── */}

      <Dialog
        open={selected !== null}
        title={selected ? selected.title : 'Document'}
        description={selected ? `${selected.documentCode} · version ${selected.version}` : undefined}
        onClose={() => setSelected(null)}
        footer={<Button variant="outline" onClick={() => setSelected(null)}>Close</Button>}
      >
        {selected && (
          <div className="space-y-4">
            <dl className="grid gap-3 sm:grid-cols-2">
              <DetailItem label="Code" value={<span className="tabular-nums">{selected.documentCode}</span>} />
              <DetailItem label="Type" value={humanize(selected.documentType)} />
              <DetailItem label="Status" value={<StatusBadge status={selected.status} />} />
              <DetailItem label="Version" value={`v${selected.version}`} />
              <DetailItem label="Filed against" value={selected.propertyName ?? 'A record on the register'} />
              <DetailItem label="Registered" value={date(selected.createdAt)} />
              <DetailItem label="Expiry" value={date(selected.expiryDate)} />
              <DetailItem label="Expiry reading" value={expiryReading(selected.expiryDate)} />
            </dl>

            <div
              className="rounded-[var(--radius-card)] border p-3"
              style={{ borderColor: 'var(--border)', background: 'var(--surface-sandstone)' }}
            >
              <p className="eyebrow" style={{ color: 'var(--ink-sandstone)' }}>Superseding</p>
              <p className="mt-1.5 text-xs leading-relaxed" style={{ color: 'var(--text-primary)' }}>
                A document is <strong>versioned, never overwritten</strong>. A later version is registered
                as an entry of its own; the register then marks the entry you are looking at as
                superseded and points it at its replacement. Both stay readable — so a lease can still be
                read under the terms that were actually signed, which is the whole reason the register
                keeps versions instead of editing one.
              </p>
              <p className="mt-2 text-xs leading-relaxed" style={{ color: 'var(--text-primary)' }}>
                Superseding is recorded by the register against the replacement it names. This screen&rsquo;s
                document service exposes no supersede call, so no button here claims to perform it — what
                this screen can do is the step that has to come first: register the later version.
              </p>
              <div className="mt-3 flex flex-wrap items-center gap-2">
                <Button variant="primary" size="sm" onClick={() => beginLaterVersion(selected)}>
                  <Layers size={14} aria-hidden="true" />
                  Register the later version
                </Button>
                <span className="text-[11px]" style={{ color: 'var(--text-secondary)' }}>
                  Opens the register form with this entry&rsquo;s title and type, and a note naming the code it
                  follows.
                </span>
              </div>
            </div>

            <p className="text-xs leading-relaxed" style={{ color: 'var(--text-secondary)' }}>
              This entry records that a document exists and where it lives. The register holds no file, so
              there is nothing to open from here.
            </p>
          </div>
        )}
      </Dialog>

      {/* ── Registering a document ───────────────────────────────────────── */}

      <Dialog
        open={createOpen}
        title="Register a document"
        description="Metadata only: the register records that the document exists, where it lives and when it lapses."
        onClose={() => { setCreateOpen(false); setFormError(null) }}
        width="lg"
        footer={
          <>
            <Button variant="ghost" onClick={() => { setCreateOpen(false); setFormError(null) }} disabled={createDocument.isPending}>
              Cancel
            </Button>
            <Button variant="primary" onClick={submitCreate} loading={createDocument.isPending}>
              Register document
            </Button>
          </>
        }
      >
        <form
          className="space-y-4"
          onSubmit={(event) => { event.preventDefault(); submitCreate() }}
          noValidate
        >
          {formError && (
            <div
              role="alert"
              className="rounded-[var(--radius-card)] border px-3 py-2 text-sm"
              style={{ background: 'var(--danger-soft)', borderColor: 'var(--border)', color: 'var(--danger)' }}
            >
              {formError}
            </div>
          )}

          <Field label="Title" htmlFor="document-title" required error={fieldErrors.title}>
            <Input
              id="document-title"
              value={createForm.title}
              onChange={(event) => setCreateForm((form) => ({ ...form, title: event.target.value }))}
              aria-invalid={Boolean(fieldErrors.title) || undefined}
            />
          </Field>

          <div className="grid gap-4 sm:grid-cols-2">
            <Field label="Type" htmlFor="create-document-type" required error={fieldErrors.documentType}>
              <Select
                id="create-document-type"
                value={createForm.documentType}
                onChange={(event) => setCreateForm((form) => ({ ...form, documentType: event.target.value }))}
                aria-invalid={Boolean(fieldErrors.documentType) || undefined}
              >
                <option value="">Choose a type</option>
                {DOCUMENT_TYPES.map((option) => (
                  <option key={option} value={option}>{humanize(option)}</option>
                ))}
              </Select>
            </Field>

            <Field
              label="Version"
              htmlFor="create-document-version"
              hint="Assigned by the register: a new entry is written as its first version, and a later version is registered as an entry of its own which the register then links to this one."
            >
              <Input id="create-document-version" value="Assigned by the register" readOnly disabled />
            </Field>
          </div>

          <fieldset className="rounded-[var(--radius-card)] border p-3" style={{ borderColor: 'var(--border)' }}>
            <legend className="px-1 text-xs font-semibold" style={{ color: 'var(--text-secondary)' }}>
              Filed against
            </legend>
            <div className="grid gap-3 sm:grid-cols-2">
              <Field label="Record type" htmlFor="create-target-type" required error={fieldErrors.target}>
                <Select
                  id="create-target-type"
                  value={createForm.targetField}
                  onChange={(event) => setCreateForm((form) => ({ ...form, targetField: event.target.value, targetId: '' }))}
                  aria-invalid={Boolean(fieldErrors.target) || undefined}
                >
                  <option value="">Choose a record type</option>
                  {FILING_TARGETS.map((target) => (
                    <option key={target.field} value={target.field}>{target.label}</option>
                  ))}
                </Select>
              </Field>

              <Field label="The record" htmlFor="create-target-record" required>
                <Select
                  id="create-target-record"
                  value={createForm.targetId}
                  disabled={!createForm.targetField}
                  onChange={(event) => setCreateForm((form) => ({ ...form, targetId: event.target.value }))}
                >
                  <option value="">
                    {createForm.targetField ? 'Choose the record' : 'Choose a record type first'}
                  </option>
                  {targetOptions.map((option) => (
                    <option key={option.id} value={option.id}>{option.label}</option>
                  ))}
                </Select>
              </Field>
            </div>
            <p className="mt-2 text-[11px] leading-relaxed" style={{ color: 'var(--text-secondary)' }}>
              The register requires at least one record: a document filed against nothing is not a
              register entry, and the endpoint refuses one rather than storing it unfiled.
            </p>
          </fieldset>

          <Field
            label="Where the document lives"
            htmlFor="create-document-reference"
            hint="The reference a colleague would use to find it — a share, a vault, a counterparty's portal. No file is uploaded; this is recorded metadata."
          >
            <Input
              id="create-document-reference"
              value={createForm.fileReference}
              onChange={(event) => setCreateForm((form) => ({ ...form, fileReference: event.target.value }))}
            />
          </Field>

          <div className="grid gap-4 sm:grid-cols-2">
            <Field label="Issued" htmlFor="create-document-issued" hint="Optional">
              <Input
                id="create-document-issued"
                type="date"
                value={createForm.issuedDate}
                onChange={(event) => setCreateForm((form) => ({ ...form, issuedDate: event.target.value }))}
              />
            </Field>
            <Field
              label="Expires"
              htmlFor="create-document-expiry"
              hint="Optional — an entry with an expiry appears on the watchlist as it approaches"
              error={fieldErrors.expiry}
            >
              <Input
                id="create-document-expiry"
                type="date"
                value={createForm.expiryDate}
                min={createForm.issuedDate || undefined}
                onChange={(event) => setCreateForm((form) => ({ ...form, expiryDate: event.target.value }))}
                aria-invalid={Boolean(fieldErrors.expiry) || undefined}
              />
            </Field>
          </div>

          <Field label="Notes" htmlFor="create-document-notes" hint="Anything a colleague needs to know about the entry">
            <Textarea
              id="create-document-notes"
              value={createForm.notes}
              onChange={(event) => setCreateForm((form) => ({ ...form, notes: event.target.value }))}
            />
          </Field>

          <div className="flex items-start gap-2">
            <input
              id="create-document-confidential"
              type="checkbox"
              className="checkbox focus-meridian mt-0.5"
              checked={createForm.confidential}
              onChange={(event) => setCreateForm((form) => ({ ...form, confidential: event.target.checked }))}
            />
            <label htmlFor="create-document-confidential" className="text-xs" style={{ color: 'var(--text-secondary)' }}>
              Confidential — restrict this entry to the roles the register marks as entitled to it.
            </label>
          </div>

          <p className="text-[11px] leading-relaxed" style={{ color: 'var(--text-secondary)' }}>
            The register stores no file: there is no upload on this screen, and no URL is generated.
            What is recorded here is the document&rsquo;s identity, the record it belongs to, where it lives and
            when it lapses.
          </p>

          <button type="submit" className="sr-only">Register document</button>
        </form>
      </Dialog>
    </PageAccessGuard>
  )
}
