import { useState } from 'react'
import { Link } from 'react-router-dom'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { BellOff, BellRing, Check, CheckCheck, ExternalLink } from 'lucide-react'
import { PageAccessGuard, PageHeader } from '../components/common'
import {
  Badge, Button, EmptyState, ErrorState, KpiTile, Pagination, Panel, SectionHeader, Select,
  Skeleton, StatusBadge, Toolbar, useToast,
} from '../components/ui'
import { count as formatCount, dateTime, humanize, relative } from '../components/ui/formatters'
import { notificationApi } from '../services'
import { messageOf } from '../lib/errors'
import type { NotificationRow } from '../types'

/**
 * The record routes a notification's entity pointer can actually be opened at.
 *
 * A notification carries a *generic* pointer - an `entityType` and an `entityId` - because it is
 * raised by whatever operation caused it, and that vocabulary is open (the seed alone uses LEASE,
 * RENT_INVOICE, MAINTENANCE, LEAD, LISTING and PAYMENT). Only three of those types have a detail
 * route in this build (`App.tsx`: `/properties/:propertyId`, `/leases/:leaseId`,
 * `/leads/:leadId`), so only those three are linked. Everything else is **named** rather than turned
 * into a link to a screen that would open a register the reader then has to search - a link that
 * does not go where it says is worse than no link at all.
 */
const RECORD_ROUTES: Record<string, string> = {
  PROPERTY: '/properties',
  LEASE: '/leases',
  LEAD: '/leads',
}

/** The severities the feed's own vocabulary holds, so the filter offers values that exist. */
const SEVERITIES = ['INFO', 'WARNING', 'CRITICAL'] as const

/**
 * Notifications.
 *
 * <p>The caller's own feed, and only their own: what a reader sees is resolved from the signed-in
 * account on the server and never from a request parameter, so no filter on this screen can widen it
 * to another colleague's rows.</p>
 *
 * <p><b>The entity pointer is generic, and this screen says so.</b> `entityType`/`entityId` name a
 * record, but the set of types is open and only a few of them are navigable here. Where a route
 * exists the row offers it; where none exists the type is written out instead of being dressed as a
 * link.</p>
 */
export default function NotificationsPage() {
  const queryClient = useQueryClient()
  const toast = useToast()

  const [page, setPage] = useState(0)
  const [size, setSize] = useState(20)
  const [unreadOnly, setUnreadOnly] = useState(false)
  const [severity, setSeverity] = useState('')

  const listQuery = useQuery({
    queryKey: ['notifications', 'list', { page, size, unreadOnly, severity }],
    queryFn: () => notificationApi.list({
      page,
      size,
      sort: 'createdAt,desc',
      unreadOnly: unreadOnly ? true : undefined,
      severity: severity || undefined,
    }),
  })

  const unreadQuery = useQuery({
    queryKey: ['notifications', 'unread-count'],
    queryFn: () => notificationApi.unreadCount(),
  })

  /** Both reads move together: marking one read changes the feed *and* the badge. */
  function invalidateFeed() {
    void queryClient.invalidateQueries({ queryKey: ['notifications'] })
  }

  const markRead = useMutation({
    mutationFn: (id: string) => notificationApi.markRead(id),
    onSuccess: () => {
      invalidateFeed()
      toast.push('Notification marked read.', 'emerald')
    },
    onError: (error) => toast.push(messageOf(error), 'terracotta'),
  })

  const markAllRead = useMutation({
    mutationFn: () => notificationApi.markAllRead(),
    onSuccess: () => {
      invalidateFeed()
      toast.push('Everything in your feed is marked read.', 'emerald')
    },
    onError: (error) => toast.push(messageOf(error), 'terracotta'),
  })

  // `NotificationDtos.UnreadCount` names the figure `unread`; there is no `count` on the payload.
  const unread = unreadQuery.data?.unread ?? 0
  const items = listQuery.data?.items ?? []

  return (
    <PageAccessGuard permission="NOTIFICATION_VIEW">
      <PageHeader
        eyebrow="Your desk"
        title="Notifications"
        description="Everything this installation has raised for you, newest first — and only for you: the feed is scoped to your account on the server."
        actions={
          /* Disabled only when the count was actually read and is zero. If the count read failed, this
             stays available rather than becoming a control the reader cannot use because an unrelated
             read went wrong. */
          <Button
            variant="primary"
            onClick={() => markAllRead.mutate()}
            loading={markAllRead.isPending}
            disabled={!unreadQuery.isError && unread === 0}
          >
            <CheckCheck size={15} aria-hidden="true" />
            Mark all read
          </Button>
        }
      />

      {/* ── The badge ────────────────────────────────────────────────────── */}

      <div className="mb-5 grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
        <KpiTile
          label="Unread"
          value={unreadQuery.isError ? '—' : formatCount(unread)}
          tone={unread > 0 ? 'amber' : 'emerald'}
          icon={unread > 0 ? <BellRing size={15} /> : <BellOff size={15} />}
          caption={
            unreadQuery.isError
              ? 'The count could not be read'
              : unread > 0
                ? 'Waiting on you'
                : 'Nothing is waiting on you'
          }
        />
        <KpiTile
          label="In this filter"
          value={listQuery.isError ? '—' : formatCount(listQuery.data?.totalElements ?? 0)}
          tone="sapphire"
          icon={<BellRing size={15} />}
          caption={unreadOnly ? 'Unread only' : severity ? `Severity ${humanize(severity)}` : 'All of your feed'}
        />
      </div>

      <SectionHeader
        eyebrow="Feed"
        title="Your notifications"
        rule="copper"
        description="Severity, what it says, when it arrived, and which record it concerns."
      />

      <Panel>
        <Toolbar>
          <label className="flex flex-col gap-1 text-xs" style={{ color: 'var(--text-secondary)' }}>
            Read state
            <Select
              className="w-40"
              value={unreadOnly ? 'unread' : 'all'}
              onChange={(event) => { setUnreadOnly(event.target.value === 'unread'); setPage(0) }}
            >
              <option value="all">Everything</option>
              <option value="unread">Unread only</option>
            </Select>
          </label>

          <label className="flex flex-col gap-1 text-xs" style={{ color: 'var(--text-secondary)' }}>
            Severity
            <Select
              className="w-40"
              value={severity}
              onChange={(event) => { setSeverity(event.target.value); setPage(0) }}
            >
              <option value="">Any severity</option>
              {SEVERITIES.map((option) => (
                <option key={option} value={option}>{humanize(option)}</option>
              ))}
            </Select>
          </label>

          {(unreadOnly || severity) && (
            <Button
              size="sm"
              variant="ghost"
              onClick={() => { setUnreadOnly(false); setSeverity(''); setPage(0) }}
            >
              Clear filters
            </Button>
          )}

          <p className="ml-auto text-xs" aria-live="polite" style={{ color: 'var(--text-secondary)' }}>
            {listQuery.isSuccess
              ? `Showing ${formatCount(items.length)} of ${formatCount(listQuery.data?.totalElements ?? 0)}`
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

        {listQuery.isSuccess && items.length === 0 && (
          <EmptyState
            icon={<BellOff size={22} aria-hidden="true" />}
            title={unreadOnly || severity ? 'Nothing in your feed matches these filters' : 'Nothing has been raised for you yet'}
            description={
              unreadOnly || severity
                ? 'Your feed holds nothing with the read state and severity you selected. Clear the filters to see everything the firm has raised for you.'
                : 'A notification is raised by the operation that causes it — a rent invoice falling overdue, a lease reaching its warning window, a lead being assigned to your desk. Your feed fills as the portfolio moves.'
            }
            action={
              (unreadOnly || severity)
                ? (
                  <Button size="sm" onClick={() => { setUnreadOnly(false); setSeverity(''); setPage(0) }}>
                    Clear filters
                  </Button>
                )
                : undefined
            }
          />
        )}

        {listQuery.isSuccess && items.length > 0 && (
          <ul className="divide-y" style={{ borderColor: 'var(--border)' }}>
            {items.map((row: NotificationRow) => {
              const relatedRoute = row.entityType ? RECORD_ROUTES[row.entityType.toUpperCase()] : undefined
              const href = relatedRoute && row.entityId ? `${relatedRoute}/${row.entityId}` : null
              return (
                <li
                  key={row.id}
                  className="grid gap-3 px-4 py-3.5 sm:grid-cols-[9rem_1fr_11rem] sm:items-start"
                  style={row.read ? undefined : { background: 'var(--surface-amber)' }}
                >
                  {/* Severity, and the read state said in words rather than only in colour. */}
                  <div className="flex flex-wrap items-start gap-1.5">
                    <StatusBadge status={row.severity} />
                    {row.read
                      ? <Badge tone="slate">Read</Badge>
                      : <Badge tone="amber">Unread</Badge>}
                  </div>

                  <div className="min-w-0">
                    <p className="text-sm font-semibold" style={{ color: 'var(--text-primary)' }}>
                      {row.title}
                    </p>
                    {row.body && (
                      <p className="mt-0.5 text-xs leading-relaxed" style={{ color: 'var(--text-secondary)' }}>
                        {row.body}
                      </p>
                    )}
                    <p className="mt-1.5 flex flex-wrap items-center gap-x-3 gap-y-1 text-[11px]" style={{ color: 'var(--text-secondary)' }}>
                      <span>{humanize(row.type)}</span>
                      <span title={dateTime(row.createdAt)}>{relative(row.createdAt)}</span>
                      <span className="tabular-nums">{dateTime(row.createdAt)}</span>
                    </p>

                    {/* The entity pointer. A link only where this build has the route. */}
                    {row.entityType && (
                      <p className="mt-1.5 text-[11px]" style={{ color: 'var(--text-secondary)' }}>
                        {href
                          ? (
                            <Link
                              to={href}
                              className="focus-meridian inline-flex items-center gap-1 underline"
                              style={{ color: 'var(--ink-sapphire)' }}
                            >
                              <ExternalLink size={12} aria-hidden="true" />
                              Open the {humanize(row.entityType).toLowerCase()} this concerns
                            </Link>
                          )
                          : (
                            <>
                              Concerns a {humanize(row.entityType).toLowerCase()} record
                              {row.entityId ? ` (${row.entityId})` : ''} — this build has no screen that
                              opens one directly, so the register is the way in.
                            </>
                          )}
                      </p>
                    )}
                  </div>

                  <div className="flex flex-wrap items-start justify-start gap-2 sm:justify-end">
                    {!row.read && (
                      <Button
                        size="sm"
                        variant="outline"
                        onClick={() => markRead.mutate(row.id)}
                        loading={markRead.isPending && markRead.variables === row.id}
                      >
                        <Check size={14} aria-hidden="true" />
                        Mark read
                      </Button>
                    )}
                    {row.read && (
                      <span className="text-[11px]" style={{ color: 'var(--text-secondary)' }}>
                        Nothing to do
                      </span>
                    )}
                  </div>
                </li>
              )
            })}
          </ul>
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
        A notification&rsquo;s record reference is a <strong>generic pointer</strong> — a type name and an
        identifier, written by whatever operation raised it — and the set of types is open, so not every
        one of them can be opened from here. Even a type that appears in a list is not always navigable:
        the firm&rsquo;s registers are the index, and this feed points at them rather than replacing them.
        {unreadQuery.isError && ' The unread count could not be read, so no number is shown for it.'}
      </p>

      <div aria-live="polite" className="sr-only">
        {markRead.isSuccess ? 'A notification was marked read.' : ''}
        {markAllRead.isSuccess ? 'All notifications were marked read.' : ''}
      </div>
    </PageAccessGuard>
  )
}
