import { useMemo, useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { FilterX, Lock, ScrollText, ShieldAlert } from 'lucide-react'
import { PageAccessGuard, PageHeader } from '../components/common'
import {
  Button, DataTable, DetailItem, Dialog, EmptyState, ErrorState, Field, Input, KpiTile, Pagination,
  Panel, SectionHeader, Select, Skeleton, StatusBadge, type Column,
} from '../components/ui'
import { count as formatCount, dateTime, humanize, relative } from '../components/ui/formatters'
import { auditApi } from '../services'
import { messageOf } from '../lib/errors'
import type { AuditRow } from '../types'

/**
 * The trail's own severity vocabulary (`AuditSeverity`), so the filter offers the three values the
 * register stores rather than a free-text box a reader has to guess at.
 */
const SEVERITIES = ['INFO', 'WARNING', 'CRITICAL'] as const

/**
 * The fields the trail can be sorted by.
 *
 * `AuditController` whitelists these six and answers anything else with a 400 that names the accepted
 * fields, so a header is only made sortable where the backend will honour it.
 */
const SORTABLE = {
  when: 'createdAt',
  actor: 'actor',
  module: 'module',
  action: 'action',
  severity: 'severity',
  entity: 'entityType',
} as const

/**
 * The extra fields the trail holds against an entry.
 *
 * <p>`AuditRow` in `src/types` is the register projection — the seven fields the table scans. The
 * endpoint answers with `AuditDtos.AuditEntry`, which is the same record with more of it: the values
 * the change moved between, and the request the change arrived on. Those are read here by name and
 * rendered **only when the trail actually recorded one** — `AuditRecordingService` leaves them empty
 * wherever there is nothing to say, and an entry that recorded no before/after must not be shown one.
 * Nothing is inferred: an absent value is absent.</p>
 */
interface AuditEntryDetail {
  previousValue: string | null
  newValue: string | null
  ipAddress: string | null
  userAgent: string | null
}

function detailOf(row: AuditRow): AuditEntryDetail {
  const source = row as AuditRow & Partial<Record<keyof AuditEntryDetail, unknown>>
  const text = (value: unknown): string | null => (typeof value === 'string' && value !== '' ? value : null)
  return {
    previousValue: text(source.previousValue),
    newValue: text(source.newValue),
    ipAddress: text(source.ipAddress),
    userAgent: text(source.userAgent),
  }
}

/**
 * The audit trail.
 *
 * <p><b>Every filter and every page of this register is answered by the server.</b> `module`,
 * `action`, `severity`, `actor`, `entityType`, `entityId`, the date range, the sort and the page are
 * all sent to `GET /audit`; nothing is fetched whole and filtered in the browser. A firm's trail is
 * the largest table in the product and the one nobody may read a subset of by accident, so narrowing
 * it in the browser would mean loading entries the reader never asked for — and would make the count
 * on screen a count of what happened to be downloaded.</p>
 *
 * <p>The filters live in the address, so a reviewer can paste the exact question they asked — and the
 * import screen links straight to the entry a commit left, by module and by the entity it names.</p>
 *
 * <p><b>This trail is append-only, and this screen cannot change it.</b> There is no endpoint that
 * writes, edits or deletes an entry: each is written by the operation it describes, inside that
 * operation's own transaction. A trail that could be touched by hand would stop being evidence, so
 * the screen says so rather than leaving the reader to wonder why there is no pencil.</p>
 */
export default function AuditLogsPage() {
  const [searchParams, setSearchParams] = useSearchParams()
  const [selected, setSelected] = useState<AuditRow | null>(null)

  const filters = {
    module: searchParams.get('module') ?? '',
    action: searchParams.get('action') ?? '',
    severity: searchParams.get('severity') ?? '',
    actor: searchParams.get('actor') ?? '',
    entityType: searchParams.get('entityType') ?? '',
    entityId: searchParams.get('entityId') ?? '',
    from: searchParams.get('from') ?? '',
    to: searchParams.get('to') ?? '',
  }
  const page = Math.max(0, Number(searchParams.get('page') ?? '0') || 0)
  const size = Math.min(100, Math.max(1, Number(searchParams.get('size') ?? '20') || 20))
  const sort = searchParams.get('sort') ?? 'createdAt,desc'

  /** Write filters into the address. Changing a filter returns the reader to the first page. */
  function applyFilters(next: Partial<typeof filters>, resetPage = true): void {
    const params = new URLSearchParams(searchParams)
    for (const [key, value] of Object.entries(next)) {
      if (value === undefined || value === '') params.delete(key)
      else params.set(key, value)
    }
    if (resetPage) params.delete('page')
    setSearchParams(params, { replace: true })
  }

  function setPage(next: number): void {
    const params = new URLSearchParams(searchParams)
    if (next <= 0) params.delete('page')
    else params.set('page', String(next))
    setSearchParams(params, { replace: true })
  }

  function setSize(next: number): void {
    const params = new URLSearchParams(searchParams)
    params.set('size', String(next))
    params.delete('page')
    setSearchParams(params, { replace: true })
  }

  function setSort(next: string): void {
    const params = new URLSearchParams(searchParams)
    params.set('sort', next)
    params.delete('page')
    setSearchParams(params, { replace: true })
  }

  function clearFilters(): void {
    const params = new URLSearchParams()
    params.set('sort', 'createdAt,desc')
    setSearchParams(params, { replace: true })
  }

  const activeFilterCount = Object.values(filters).filter((value) => value !== '').length
  const hasFilters = activeFilterCount > 0

  const auditQuery = useQuery({
    queryKey: ['audit', 'list', { ...filters, page, size, sort }],
    // Only what the reader asked for goes on the wire: an empty box is an absent filter, not a blank
    // one, because `apiFetch` omits empty values and the backend reads an absent filter as "any".
    queryFn: () => auditApi.list({ ...filters, page, size, sort }),
  })

  const items = auditQuery.data?.items ?? []

  const columns: Column<AuditRow>[] = useMemo(() => [
    {
      key: 'when',
      header: 'When',
      width: '12%',
      sortKey: SORTABLE.when,
      render: (row) => (
        // The relative reading is what a reviewer scans; the exact timestamp is one hover away and
        // written beside it, because "8h ago" is not evidence.
        <span title={dateTime(row.createdAt)} className="text-xs">
          <span className="block" style={{ color: 'var(--text-primary)' }}>{relative(row.createdAt)}</span>
          <span className="block text-[10px] tabular-nums" style={{ color: 'var(--text-secondary)' }}>
            {dateTime(row.createdAt)}
          </span>
        </span>
      ),
    },
    {
      key: 'actor',
      header: 'Actor',
      width: '13%',
      secondary: true,
      sortKey: SORTABLE.actor,
      render: (row) => row.actorName ?? 'System',
    },
    {
      key: 'module',
      header: 'Module',
      width: '11%',
      sortKey: SORTABLE.module,
      render: (row) => (
        <span className="text-xs" style={{ color: 'var(--text-primary)' }}>{humanize(row.module)}</span>
      ),
    },
    {
      key: 'action',
      header: 'Action',
      width: '15%',
      sortKey: SORTABLE.action,
      render: (row) => (
        <span className="text-xs" style={{ color: 'var(--text-primary)' }}>{humanize(row.action)}</span>
      ),
    },
    {
      key: 'description',
      header: 'What happened',
      width: '26%',
      // Not sortable: `description` is not on the controller's whitelist, and a header that looks
      // sortable and is refused with a 400 is worse than a header that is plainly a column.
      render: (row) => (
        <span className="truncate text-xs" style={{ color: 'var(--text-secondary)' }}>
          {row.description ?? '—'}
        </span>
      ),
    },
    {
      key: 'entity',
      header: 'Record',
      width: '15%',
      sortKey: SORTABLE.entity,
      render: (row) => (
        <span className="text-xs">
          <span className="block" style={{ color: 'var(--text-primary)' }}>
            {row.entityLabel ?? row.entityId ?? '—'}
          </span>
          {row.entityType && (
            <span className="block text-[10px] uppercase tracking-wide" style={{ color: 'var(--text-secondary)' }}>
              {row.entityType}
            </span>
          )}
        </span>
      ),
    },
    {
      key: 'severity',
      header: 'Severity',
      width: '8%',
      sortKey: SORTABLE.severity,
      render: (row) => <StatusBadge status={row.severity} />,
    },
  ], [])

  const detail = selected ? detailOf(selected) : null

  return (
    <PageAccessGuard permission="AUDIT_VIEW">
      <PageHeader
        eyebrow="Governance"
        title="Audit log"
        description="Every recorded change to the portfolio, who made it, and what it moved between. The trail is written by the operations themselves and is append-only — nothing on this screen can edit or remove an entry."
        actions={
          <Button variant="outline" onClick={clearFilters} disabled={!hasFilters}>
            <FilterX size={15} aria-hidden="true" />
            Clear filters
          </Button>
        }
      />

      <div className="mb-5 grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
        <KpiTile
          label="Entries in this filter"
          value={auditQuery.isError ? '—' : formatCount(auditQuery.data?.totalElements ?? 0)}
          tone="sapphire"
          icon={<ScrollText size={15} />}
          caption="Counted by the server, not by the page"
        />
        <KpiTile
          label="Filters applied"
          value={formatCount(activeFilterCount)}
          tone={hasFilters ? 'amber' : 'slate'}
          icon={<ShieldAlert size={15} />}
          caption={hasFilters ? 'Narrowing the trail' : 'The whole trail, newest first'}
        />
        <KpiTile
          label="Entries on this page"
          value={auditQuery.isError ? '—' : formatCount(items.length)}
          tone="forest"
          caption={auditQuery.data ? `Page ${(auditQuery.data.page ?? 0) + 1} of ${Math.max(auditQuery.data.totalPages, 1)}` : 'Not read yet'}
        />
        <KpiTile
          label="Order"
          value={sort.split(',')[1] === 'desc' ? 'Newest first' : 'Oldest first'}
          tone="sandstone"
          caption={`Sorted by ${humanize(sort.split(',')[0])}`}
        />
      </div>

      {/* ── The filters ──────────────────────────────────────────────────── */}

      <SectionHeader
        eyebrow="Search"
        title="Narrow the trail"
        rule="slate"
        description="Every one of these is answered by the endpoint: nothing is downloaded and filtered here, so the count above is the firm's count and not the page's."
      />

      <Panel className="mb-5" tint="slate">
        <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
          <Field label="Module" htmlFor="audit-module" hint="e.g. LEASES, PAYMENTS, DOCUMENTS">
            <Input
              id="audit-module"
              name="module"
              value={filters.module}
              placeholder="Any module"
              onChange={(event) => applyFilters({ module: event.target.value.trim().toUpperCase() })}
            />
          </Field>
          <Field label="Action" htmlFor="audit-action" hint="e.g. CREATE, STATUS_CHANGE">
            <Input
              id="audit-action"
              name="action"
              value={filters.action}
              placeholder="Any action"
              onChange={(event) => applyFilters({ action: event.target.value.trim().toUpperCase() })}
            />
          </Field>
          <Field label="Severity" htmlFor="audit-severity">
            <Select
              id="audit-severity"
              value={filters.severity}
              onChange={(event) => applyFilters({ severity: event.target.value })}
            >
              <option value="">Any severity</option>
              {SEVERITIES.map((option) => (
                <option key={option} value={option}>{humanize(option)}</option>
              ))}
            </Select>
          </Field>
          <Field label="Actor" htmlFor="audit-actor" hint="Matches the name recorded on the entry">
            <Input
              id="audit-actor"
              name="actor"
              value={filters.actor}
              placeholder="Anyone"
              onChange={(event) => applyFilters({ actor: event.target.value })}
            />
          </Field>
          <Field label="Entity type" htmlFor="audit-entity-type" hint="e.g. LEASE, IMPORT, PROPERTY">
            <Input
              id="audit-entity-type"
              name="entityType"
              value={filters.entityType}
              placeholder="Any record type"
              onChange={(event) => applyFilters({ entityType: event.target.value.trim().toUpperCase() })}
            />
          </Field>
          <Field label="Entity id" htmlFor="audit-entity-id" hint="The exact record's identifier">
            <Input
              id="audit-entity-id"
              name="entityId"
              value={filters.entityId}
              placeholder="Any record"
              onChange={(event) => applyFilters({ entityId: event.target.value.trim() })}
            />
          </Field>
          <Field label="From" htmlFor="audit-from" hint="Inclusive">
            <Input
              id="audit-from"
              name="from"
              type="date"
              value={filters.from}
              onChange={(event) => applyFilters({ from: event.target.value })}
            />
          </Field>
          <Field label="To" htmlFor="audit-to" hint="Inclusive">
            <Input
              id="audit-to"
              name="to"
              type="date"
              value={filters.to}
              onChange={(event) => applyFilters({ to: event.target.value })}
            />
          </Field>
        </div>
        <p className="mt-3 border-t pt-3 text-[11px] leading-relaxed" style={{ borderColor: 'var(--border)', color: 'var(--text-secondary)' }}>
          <Lock size={12} aria-hidden="true" className="mr-1 inline" />
          The address bar carries these filters, so a review can be shared or bookmarked and a refresh
          reopens the same question.
        </p>
      </Panel>

      {/* ── The trail ────────────────────────────────────────────────────── */}

      <Panel bodyClassName="p-0" title="Trail" subtitle="Click an entry to read the whole of it">
        {auditQuery.isLoading && <div className="p-4"><Skeleton rows={6} /></div>}

        {auditQuery.isError && (
          <div className="p-4">
            <ErrorState
              message={messageOf(auditQuery.error)}
              details={hasFilters
                ? ['Some filter values may not be ones this register accepts; clearing them is the fastest way back.']
                : undefined}
              onRetry={() => void auditQuery.refetch()}
            />
          </div>
        )}

        {auditQuery.isSuccess && (
          <DataTable
            columns={columns}
            rows={items}
            keyOf={(row) => row.id}
            onRowClick={(row) => setSelected(row)}
            sort={sort}
            onSortChange={setSort}
            caption="Audit trail entries"
            empty={
              <EmptyState
                icon={<ScrollText size={22} aria-hidden="true" />}
                title={hasFilters ? 'No entries match these filters' : 'The trail is empty'}
                description={hasFilters
                  ? 'Nothing in the trail was recorded with the module, action, actor, severity or window you asked for. Widen the date range, or clear the filters to read the whole trail. Filter values are matched exactly (and module, action and entity type are upper case), so a partial word will find nothing.'
                  : 'An entry is written by the operation it describes — a record created, a lease activated, a receipt posted, a sign-in, an import committed. Until the portfolio is worked on, there is nothing to record.'}
                action={hasFilters ? <Button size="sm" onClick={clearFilters}>Clear filters</Button> : undefined}
              />
            }
          />
        )}

        {auditQuery.isSuccess && (auditQuery.data?.totalElements ?? 0) > 0 && (
          <div className="border-t" style={{ borderColor: 'var(--border)' }}>
            <Pagination
              page={auditQuery.data?.page ?? page}
              size={auditQuery.data?.size ?? size}
              totalElements={auditQuery.data?.totalElements ?? 0}
              totalPages={auditQuery.data?.totalPages ?? 0}
              hasNext={auditQuery.data?.hasNext ?? false}
              onPageChange={setPage}
              onSizeChange={setSize}
            />
          </div>
        )}
      </Panel>

      <p className="mt-4 max-w-4xl text-xs leading-relaxed" style={{ color: 'var(--text-secondary)' }}>
        <strong>This trail is append-only.</strong> There is no endpoint that writes, edits or deletes an
        entry, and this screen is a read: every entry was written by the operation it describes, inside
        that operation&rsquo;s own transaction. That is what makes it evidence rather than a log — a
        correction is a further entry, never a change to an earlier one.
      </p>

      <div aria-live="polite" className="sr-only">
        {auditQuery.isSuccess
          ? `${items.length} audit entries shown, of ${auditQuery.data?.totalElements ?? 0} matching.`
          : ''}
      </div>

      {/* ── One entry, in full ───────────────────────────────────────────── */}

      <Dialog
        open={selected !== null}
        title={selected ? humanize(selected.action) : 'Audit entry'}
        description={selected?.entityLabel ?? selected?.entityType ?? 'Recorded change'}
        onClose={() => setSelected(null)}
        width="lg"
        footer={<Button variant="outline" onClick={() => setSelected(null)}>Close</Button>}
      >
        {selected && detail && (
          <div className="space-y-4">
            <p className="text-sm leading-relaxed" style={{ color: 'var(--text-primary)' }}>
              {selected.description ?? 'This entry carries no description of its own — the module, the action and the record are what it says.'}
            </p>

            <dl className="grid gap-3 sm:grid-cols-3">
              <DetailItem label="Module" value={humanize(selected.module)} />
              <DetailItem label="Action" value={humanize(selected.action)} />
              <DetailItem label="Severity" value={<StatusBadge status={selected.severity} />} />
              <DetailItem label="Actor" value={selected.actorName ?? 'Recorded as the system'} />
              <DetailItem label="Recorded at" value={dateTime(selected.createdAt)} />
              <DetailItem label="Entity type" value={selected.entityType ?? 'Not named'} />
              <DetailItem
                label="Entity id"
                value={selected.entityId
                  ? <span className="tabular-nums text-xs">{selected.entityId}</span>
                  : 'Not named'}
              />
              <DetailItem label="Entity label" value={selected.entityLabel ?? '—'} />
            </dl>

            {/* The before and after, where the trail recorded one. */}
            <div className="rounded-[var(--radius-card)] border p-3" style={{ borderColor: 'var(--border)', background: 'var(--surface-muted)' }}>
              <p className="eyebrow" style={{ color: 'var(--text-secondary)' }}>What changed</p>
              {detail.previousValue || detail.newValue
                ? (
                  <dl className="mt-2 grid gap-3 sm:grid-cols-2">
                    <div className="min-w-0">
                      <dt className="text-[10px] font-bold uppercase tracking-wide" style={{ color: 'var(--text-secondary)' }}>
                        Before
                      </dt>
                      <dd className="mt-0.5 break-words text-xs" style={{ color: 'var(--text-primary)' }}>
                        {detail.previousValue ?? '— nothing recorded'}
                      </dd>
                    </div>
                    <div className="min-w-0">
                      <dt className="text-[10px] font-bold uppercase tracking-wide" style={{ color: 'var(--text-secondary)' }}>
                        After
                      </dt>
                      <dd className="mt-0.5 break-words text-xs" style={{ color: 'var(--text-primary)' }}>
                        {detail.newValue ?? '— nothing recorded'}
                      </dd>
                    </div>
                  </dl>
                )
                : (
                  <p className="mt-1 text-xs" style={{ color: 'var(--text-secondary)' }}>
                    This entry recorded no before and after. Not every operation moves a value — recording
                    a receipt or committing an import states a fact rather than changing one — so where the
                    trail has nothing to compare, nothing is shown rather than two empty boxes.
                  </p>
                )}
            </div>

            {/* Where the change came from, when the trail recorded it. */}
            <div className="rounded-[var(--radius-card)] border p-3" style={{ borderColor: 'var(--border)' }}>
              <p className="eyebrow" style={{ color: 'var(--text-secondary)' }}>Request origin</p>
              {detail.ipAddress || detail.userAgent
                ? (
                  <dl className="mt-2 grid gap-3">
                    <DetailItem
                      label="IP address"
                      value={detail.ipAddress
                        ? <span className="tabular-nums">{detail.ipAddress}</span>
                        : 'Not recorded'}
                    />
                    <DetailItem
                      label="User agent"
                      value={detail.userAgent
                        ? <span className="break-words text-xs">{detail.userAgent}</span>
                        : 'Not recorded'}
                    />
                  </dl>
                )
                : (
                  <p className="mt-1 text-xs" style={{ color: 'var(--text-secondary)' }}>
                    This entry holds no request origin. The trail records the address and the client only
                    where the operation was reached over HTTP with them available — an entry written by a
                    scheduled job or by the seed has neither, and one is not invented for it.
                  </p>
                )}
            </div>

            <p className="text-xs leading-relaxed" style={{ color: 'var(--text-secondary)' }}>
              <strong>This entry cannot be edited here.</strong> The trail is append-only: every entry was
              written inside the transaction of the operation it describes, and there is no endpoint that
              changes or removes one. What you are reading is the record as it was written.
            </p>
          </div>
        )}
      </Dialog>
    </PageAccessGuard>
  )
}
