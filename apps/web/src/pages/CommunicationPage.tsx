import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { MailWarning, MessageSquare, Send, UserRound, Users } from 'lucide-react'
import { PageAccessGuard, PageHeader } from '../components/common'
import {
  Button, DetailItem, EmptyState, ErrorState, Field, Input, KpiTile, Panel, SectionHeader, Select,
  Skeleton, StatusBadge, Textarea, useToast,
} from '../components/ui'
import { count as formatCount, dateTime, humanize, initials, relative } from '../components/ui/formatters'
import { communicationApi } from '../services'
import { messageOf } from '../lib/errors'
import type { ConversationSummaryRow, MessageSummary } from '../types'

/* ============================================================
 * Reading a thread
 * ============================================================ */

/*
 * A message is `CommunicationDtos.MessageSummary`, which the API map declares for the thread read and
 * for the internal-notes read alike: the id, the stored `senderType`/`messageType`, the `internal` flag,
 * the sender, the body and the moments. It is read by name, so nothing here has to check a shape it
 * cannot know.
 *
 * Nothing is inferred from the body: `senderType`, `messageType` and `internal` are stored columns, and
 * the product deliberately has no free-text classifier, so a message is styled from those fields and
 * never from what it says.
 */

/** The pill a thread's own status wears on the rail. The word travels with the pill, never colour alone. */
function railPillClass(status: string): string {
  switch (status.toUpperCase()) {
    case 'OPEN': return 'rail-pill rail-pill-open'
    case 'WAITING': return 'rail-pill rail-pill-waiting'
    case 'RESOLVED':
    case 'CLOSED': return 'rail-pill rail-pill-closed'
    default: return 'rail-pill rail-pill-unowned'
  }
}

/**
 * The correspondence console.
 *
 * <p><b>Three panes, three materials.</b> The rail is the installation's deep forest plane, the thread
 * is parchment, and the contact file is sandstone — so a reader knows which zone they are in from the
 * ground they are standing on rather than from a heading they have to read. All three come from the
 * stylesheet's own console vocabulary (`.portfolio-rail`, `.thread-panel`, `.dossier-panel`), not from
 * colours picked here.</p>
 *
 * <p><b>The client-facing thread.</b> `GET /communications/{id}` is the read a client could see, and
 * an internal note is excluded from it at the query and again in the service. So the thread rendered
 * here is the client-facing one — and if a note ever reaches this pane, it arrives labelled as an
 * internal note in words, because a note the firm wrote for itself must never be mistaken for
 * something a tenant received. Nothing in this console classifies a message from its text: whether a
 * message is internal, who sent it and what kind it is are all stored fields.</p>
 */
export default function CommunicationPage() {
  const queryClient = useQueryClient()
  const toast = useToast()

  const [search, setSearch] = useState('')
  const [unreadOnly, setUnreadOnly] = useState(false)
  const [page, setPage] = useState(0)
  const [size, setSize] = useState(20)
  const [selectedId, setSelectedId] = useState<string | null>(null)
  const [draft, setDraft] = useState('')

  const listQuery = useQuery({
    queryKey: ['communications', 'list', { search, unreadOnly, page, size }],
    queryFn: () => communicationApi.list({
      page,
      size,
      // Most recent activity first, which is the register's own default ordering: a thread nobody has
      // written in has no activity to place in time and belongs at the bottom rather than the top.
      sort: 'lastMessageAt,desc',
      search: search || undefined,
      unreadOnly: unreadOnly ? true : undefined,
    }),
  })

  const unreadQuery = useQuery({
    queryKey: ['communications', 'unread-count'],
    queryFn: () => communicationApi.unreadCount(),
  })

  const threads = listQuery.data?.items ?? []

  // The thread on screen: the one the reader picked, or the newest one once the rail has answered. A
  // selection that is not on the current page falls back to the first row rather than leaving the
  // middle pane empty with nothing to explain it.
  const active: ConversationSummaryRow | null = threads.find((thread) => thread.id === selectedId) ?? threads[0] ?? null
  const activeId = active?.id ?? ''

  const messagesQuery = useQuery({
    queryKey: ['communications', activeId, 'messages'],
    queryFn: () => communicationApi.messages(activeId),
    enabled: activeId !== '',
  })

  const markRead = useMutation({
    mutationFn: () => communicationApi.markRead(activeId),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['communications'] })
      toast.push('The thread is marked read.', 'emerald')
    },
    onError: (error) => toast.push(messageOf(error), 'terracotta'),
  })

  const postMessage = useMutation({
    mutationFn: (body: string) => communicationApi.postMessage(activeId, {
      body,
      // A colleague logging correspondence: firm-side, typed as text, and never an internal note —
      // an internal note is a separate intent and this composer does not guess at it.
      senderType: 'FIRM',
      messageType: 'TEXT',
      internal: false,
    }),
    onSuccess: () => {
      setDraft('')
      void queryClient.invalidateQueries({ queryKey: ['communications'] })
      toast.push('The message was posted to the thread.', 'emerald')
    },
    onError: (error) => toast.push(messageOf(error), 'terracotta'),
  })

  const messages = messagesQuery.data ?? []

  const clientFacing = messages.filter((message) => !message.internal)
  const internalNotes = messages.filter((message) => message.internal)

  // `CommunicationDtos.UnreadCount` names the figure `unreadMessages`; there is no `count` on the
  // payload, so the badge is read from the field the endpoint actually sends.
  const unread = unreadQuery.data?.unreadMessages ?? 0

  return (
    <PageAccessGuard permission="CONVERSATION_VIEW">
      <PageHeader
        eyebrow="Correspondence"
        title="Communication"
        description="Every thread the desk has logged, the client-facing conversation on it, and who owns it. Internal notes are held apart from what a client reads."
      />

      <div className="mb-5 grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
        <KpiTile
          label="Threads"
          value={listQuery.isError ? '—' : formatCount(listQuery.data?.totalElements ?? 0)}
          tone="forest"
          icon={<MessageSquare size={15} />}
          caption={unreadOnly ? 'Unread threads only' : 'On the register'}
        />
        <KpiTile
          label="Unread messages"
          value={unreadQuery.isError ? '—' : formatCount(unread)}
          tone={unread > 0 ? 'amber' : 'emerald'}
          icon={<MailWarning size={15} />}
          caption={unreadQuery.isError ? 'The count could not be read' : unread > 0 ? 'Client messages nobody has opened' : 'Nothing is waiting on a reply'}
        />
        <KpiTile
          label="Threads on this page"
          value={listQuery.isError ? '—' : formatCount(threads.length)}
          tone="sapphire"
          caption={listQuery.data ? `Page ${(listQuery.data.page ?? 0) + 1} of ${Math.max(listQuery.data.totalPages, 1)}` : 'Not read yet'}
        />
        <KpiTile
          label="Messages open"
          value={active ? formatCount(messages.length) : '—'}
          tone="teal"
          icon={<Users size={15} />}
          caption={active ? `On ${active.conversationCode}` : 'Choose a thread'}
        />
      </div>

      {/* ── The three panes ──────────────────────────────────────────────── */}
      <div className="grid items-start gap-4 xl:grid-cols-[19rem_1fr_17rem]">

        {/* ---- Pane one: the rail ---------------------------------------------------------- */}
        <section
          aria-label="Conversations"
          className="portfolio-rail min-h-[32rem] rounded-[var(--radius-panel)] p-3 shadow-[var(--shadow-card)]"
        >
          <p className="rail-label eyebrow">Threads</p>

          <div className="mt-2 space-y-2">
            <div>
              <label htmlFor="thread-search" className="rail-label mb-1 block text-[11px] font-semibold">
                Search the register
              </label>
              <Input
                id="thread-search"
                className="rail-input"
                value={search}
                placeholder="Subject or code"
                onChange={(event) => { setSearch(event.target.value); setPage(0) }}
              />
            </div>
            <div className="flex items-center gap-2">
              <input
                id="thread-unread-only"
                type="checkbox"
                className="rail-checkbox focus-hero h-4 w-4 rounded"
                checked={unreadOnly}
                onChange={(event) => { setUnreadOnly(event.target.checked); setPage(0) }}
              />
              <label htmlFor="thread-unread-only" className="rail-label text-[11px] font-semibold">
                Unread threads only
              </label>
            </div>
          </div>

          <div className="mt-3">
            {listQuery.isLoading && (
              <div className="space-y-2 p-1" aria-hidden="true">
                {Array.from({ length: 5 }).map((_, index) => (
                  <div key={index} className="h-12 animate-pulse rounded-[var(--radius-card)]" style={{ background: 'rgba(255,255,255,0.08)' }} />
                ))}
              </div>
            )}

            {listQuery.isError && (
              <div className="mt-1">
                <ErrorState message={messageOf(listQuery.error)} onRetry={() => void listQuery.refetch()} />
              </div>
            )}

            {listQuery.isSuccess && threads.length === 0 && (
              // The rail is the one dark plane in the product, so its empty state is written in the
              // rail's own text tokens rather than dropped in as a light component whose colours
              // would sink into the ground. The words do the work, as they do everywhere else.
              <div className="rounded-[var(--radius-card)] border p-3" style={{ borderColor: 'rgba(255,255,255,0.16)' }}>
                <p className="rail-label text-xs font-semibold">
                  {search || unreadOnly ? 'No thread matches this filter' : 'No thread has been logged'}
                </p>
                <p className="rail-muted mt-1 text-[11px] leading-relaxed">
                  {search || unreadOnly
                    ? 'The register holds nothing with that subject or code. Clear the search to see every thread.'
                    : 'A thread is opened when a colleague logs a call, an e-mail or a portal message against a property, a lease, a tenant or a lead. Open one and it will appear here, newest activity first.'}
                </p>
                {(search || unreadOnly) && (
                  <div className="mt-2">
                    <Button size="sm" variant="outline" onClick={() => { setSearch(''); setUnreadOnly(false); setPage(0) }}>
                      Clear the filter
                    </Button>
                  </div>
                )}
              </div>
            )}

            {listQuery.isSuccess && threads.length > 0 && (
              <ul className="space-y-1">
                {threads.map((thread) => {
                  const isActive = active?.id === thread.id
                  const hasUnread = thread.unreadCount > 0
                  return (
                    <li key={thread.id}>
                      <button
                        type="button"
                        onClick={() => { setSelectedId(thread.id); setDraft('') }}
                        aria-current={isActive ? 'true' : undefined}
                        className={`rail-row focus-hero block w-full px-2.5 py-2 text-left ${isActive ? 'rail-row-active' : ''}`}
                      >
                        <span className="flex items-start justify-between gap-2">
                          <span
                            className="min-w-0 truncate text-xs font-semibold"
                            style={{ color: 'var(--hero-text)' }}
                          >
                            {thread.subject}
                          </span>
                          {/* Unread is said in words and in a number, not only in a colour. */}
                          {hasUnread && (
                            <span className="rail-pill rail-pill-new shrink-0">
                              {thread.unreadCount} unread
                            </span>
                          )}
                        </span>
                        <span className="mt-1 flex flex-wrap items-center gap-1.5">
                          <span className={railPillClass(thread.status)}>{thread.status.replace(/_/g, ' ')}</span>
                          <span className="rail-muted text-[10px]">{humanize(thread.channel)}</span>
                          <span className="rail-muted text-[10px] tabular-nums">{thread.conversationCode}</span>
                        </span>
                        <span className="rail-muted mt-1 block text-[10px]">
                          {thread.lastMessageAt
                            ? `Last message ${relative(thread.lastMessageAt)}`
                            : 'No message written yet'}
                          {thread.assignedToName ? ` · ${thread.assignedToName}` : ' · unassigned'}
                        </span>
                      </button>
                    </li>
                  )
                })}
              </ul>
            )}
          </div>

          {/* The rail's own pager: the register's pager uses light-surface tokens, which would not
              survive on deep forest, so the rail carries its own controls in its own materials. */}
          {(listQuery.data?.totalElements ?? 0) > 0 && (
            <nav className="mt-3 flex flex-wrap items-center justify-between gap-2 border-t pt-3" style={{ borderColor: 'rgba(255,255,255,0.16)' }} aria-label="Thread paging">
              <p className="rail-label text-[11px]">
                {formatCount(threads.length)} of {formatCount(listQuery.data?.totalElements ?? 0)}
              </p>
              <div className="flex items-center gap-2">
                <Button size="sm" variant="outline" onClick={() => setPage((current) => Math.max(0, current - 1))} disabled={page <= 0}>
                  Previous
                </Button>
                <Button size="sm" variant="outline" onClick={() => setPage((current) => current + 1)} disabled={!listQuery.data?.hasNext}>
                  Next
                </Button>
              </div>
              <label className="rail-label flex items-center gap-1 text-[11px]">
                Threads
                <Select
                  className="rail-input w-auto py-1 text-xs"
                  value={String(size)}
                  onChange={(event) => { setSize(Number(event.target.value)); setPage(0) }}
                >
                  {[10, 20, 50].map((option) => <option key={option} value={option}>{option}</option>)}
                </Select>
              </label>
            </nav>
          )}
        </section>

        {/* ---- Pane two: the thread -------------------------------------------------------- */}
        <section
          aria-label="Conversation"
          className="thread-panel min-h-[32rem] rounded-[var(--radius-panel)] border p-4 shadow-[var(--shadow-card)]"
          style={{ borderColor: 'var(--border)' }}
        >
          {!active && (
            <EmptyState
              icon={<MessageSquare size={22} aria-hidden="true" />}
              title="No thread is open"
              description="Choose a conversation on the rail to read it. A thread is opened when a colleague logs a call, an e-mail or a portal message, and the register orders them by their most recent activity."
            />
          )}

          {active && (
            <>
              <header className="mb-3 flex flex-wrap items-start justify-between gap-3 border-b pb-3" style={{ borderColor: 'var(--border)' }}>
                <div className="min-w-0">
                  <h2 className="font-display text-base font-semibold" style={{ color: 'var(--text-primary)' }}>
                    {active.subject}
                  </h2>
                  <p className="mt-0.5 flex flex-wrap items-center gap-2 text-[11px]" style={{ color: 'var(--text-secondary)' }}>
                    <span className="tabular-nums">{active.conversationCode}</span>
                    <span>{humanize(active.channel)}</span>
                    {active.propertyName && <span>{active.propertyName}</span>}
                  </p>
                </div>
                <div className="flex flex-wrap items-center gap-2">
                  <StatusBadge status={active.status} />
                  {active.unreadCount > 0 && (
                    <Button
                      size="sm"
                      variant="outline"
                      onClick={() => markRead.mutate()}
                      loading={markRead.isPending}
                    >
                      Mark read ({formatCount(active.unreadCount)})
                    </Button>
                  )}
                </div>
              </header>

              <p className="mb-3 rounded-[var(--radius-card)] border px-3 py-2 text-[11px] leading-relaxed" style={{ borderColor: 'var(--border)', background: 'var(--surface-muted)', color: 'var(--text-secondary)' }}>
                This is the <strong>client-facing</strong> thread: what an owner, tenant or prospect would
                read. Internal notes are held off this read by the register, so nothing here has been sent
                to nobody, and a note that ever appears is labelled as internal.
              </p>

              {messagesQuery.isLoading && <Skeleton rows={6} />}

              {messagesQuery.isError && (
                <ErrorState
                  message={messageOf(messagesQuery.error)}
                  details={['The thread itself is intact; this is the message read on it that did not answer.']}
                  onRetry={() => void messagesQuery.refetch()}
                />
              )}

              {messagesQuery.isSuccess && clientFacing.length === 0 && (
                <EmptyState
                  title="Nothing has been written on this thread yet"
                  description="A thread may be opened before anybody has spoken — a logged call with nothing said. The message you post below will be the first on it, and it will appear here."
                />
              )}

              {messagesQuery.isSuccess && clientFacing.length > 0 && (
                <ol className="space-y-3" aria-live="polite">
                  {clientFacing.map((message) => (
                    <li key={message.id} className={message.senderType === 'FIRM' ? 'flex justify-end' : ''}>
                      <MessageBubble message={message} />
                    </li>
                  ))}
                </ol>
              )}

              {/* Any note that did reach this pane is shown under its own heading, in words. */}
              {messagesQuery.isSuccess && internalNotes.length > 0 && (
                <div className="mt-4 border-t pt-3" style={{ borderColor: 'var(--border)' }}>
                  <p className="eyebrow" style={{ color: 'var(--ink-plum)' }}>
                    Internal notes ({internalNotes.length}) — never sent to the client
                  </p>
                  <ol className="mt-2 space-y-2">
                    {internalNotes.map((message) => (
                      <li key={message.id}><MessageBubble message={message} /></li>
                    ))}
                  </ol>
                </div>
              )}

              {/* ---- Composing ---------------------------------------------------------- */}
              <div className="mt-5 border-t pt-4" style={{ borderColor: 'var(--border)' }}>
                <Field
                  label="Reply on the client-facing thread"
                  htmlFor="thread-reply"
                  hint="Posted as a firm-side message. The register has no request field for a note here, so this composer writes client-facing correspondence only."
                >
                  <Textarea
                    id="thread-reply"
                    value={draft}
                    placeholder="What was said, or what the client should be told."
                    onChange={(event) => setDraft(event.target.value)}
                  />
                </Field>
                <div className="mt-2 flex flex-wrap items-center gap-2">
                  <Button
                    variant="primary"
                    onClick={() => postMessage.mutate(draft.trim())}
                    loading={postMessage.isPending}
                    disabled={draft.trim() === ''}
                  >
                    <Send size={15} aria-hidden="true" />
                    Post to the thread
                  </Button>
                  <span className="text-[11px]" style={{ color: 'var(--text-secondary)' }}>
                    {postMessage.isSuccess ? 'Posted.' : 'Press the button to post; nothing is sent until it is pressed.'}
                  </span>
                </div>
              </div>
            </>
          )}
        </section>

        {/* ---- Pane three: the thread file ------------------------------------------------- */}
        <aside
          aria-label="Thread file"
          className="dossier-panel min-h-[32rem] rounded-[var(--radius-panel)] border p-4 shadow-[var(--shadow-card)]"
          style={{ borderColor: 'var(--border)' }}
        >
          <p className="eyebrow" style={{ color: 'var(--ink-sandstone)' }}>Thread file</p>
          {!active
            ? (
              <p className="mt-2 text-xs leading-relaxed" style={{ color: 'var(--text-secondary)' }}>
                The file of the thread you are reading appears here: which record it concerns, who owns it
                and how it is progressing.
              </p>
            )
            : (
              <>
                <div className="mt-3 flex items-start gap-2">
                  <span
                    className="grid h-9 w-9 shrink-0 place-items-center rounded-[var(--radius-chip)] text-[11px] font-bold"
                    style={{ background: 'var(--surface-elevated)', color: 'var(--ink-sandstone)' }}
                    aria-hidden="true"
                  >
                    {initials(active.assignedToName ?? active.conversationCode)}
                  </span>
                  <div className="min-w-0">
                    <p className="truncate text-sm font-semibold" style={{ color: 'var(--text-primary)' }}>
                      {active.assignedToName ?? 'Unassigned'}
                    </p>
                    <p className="text-[11px]" style={{ color: 'var(--text-secondary)' }}>
                      {active.assignedToName ? 'Owns this thread' : 'Nobody owns this thread yet'}
                    </p>
                  </div>
                </div>

                <dl className="mt-4 space-y-3 border-t pt-3" style={{ borderColor: 'var(--border)' }}>
                  <DetailItem label="Code" value={<span className="tabular-nums">{active.conversationCode}</span>} />
                  <DetailItem label="Channel" value={humanize(active.channel)} />
                  <DetailItem label="Status" value={<StatusBadge status={active.status} />} />
                  <DetailItem label="Priority" value={<StatusBadge status={active.priority} />} />
                  <DetailItem label="Concerns" value={active.propertyName ?? 'No record named on the thread'} />
                  <DetailItem
                    label="Last message"
                    value={active.lastMessageAt
                      ? <span title={dateTime(active.lastMessageAt)}>{relative(active.lastMessageAt)}</span>
                      : 'Nothing written yet'}
                  />
                  <DetailItem
                    label="Unread"
                    value={active.unreadCount > 0 ? `${formatCount(active.unreadCount)} unread` : 'All read'}
                  />
                </dl>

                <div className="mt-4 border-t pt-3" style={{ borderColor: 'var(--border)' }}>
                  <p className="flex items-start gap-2 text-[11px] leading-relaxed" style={{ color: 'var(--text-secondary)' }}>
                    <UserRound size={13} aria-hidden="true" className="mt-0.5 shrink-0" />
                    <span>
                      The register&rsquo;s thread summary projects the thread&rsquo;s identity, its channel and
                      who owns it. It carries no counterparty contact details, so none are invented
                      here — the client&rsquo;s own record is the place to read those.
                    </span>
                  </p>
                </div>
              </>
            )}
        </aside>
      </div>

      <SectionHeader
        eyebrow="How this console reads"
        title="What a message is, and what it is not"
        rule="plum"
        description="Three rules this screen follows, because each of them is a way a correspondence console goes quietly wrong."
      />
      <div className="mb-4 grid gap-4 lg:grid-cols-3">
        <Panel tint="forest" title="Client-facing, by default">
          <p className="text-xs leading-relaxed" style={{ color: 'var(--text-secondary)' }}>
            The thread read returns the conversation as a client could see it. An internal note is
            excluded at the query and again in the service — not merely hidden in the interface — so
            what is on screen is what was actually said to the client.
          </p>
        </Panel>
        <Panel tint="plum" title="Never classified from its text">
          <p className="text-xs leading-relaxed" style={{ color: 'var(--text-secondary)' }}>
            Whether a message is internal, who sent it and what kind it is are stored columns. Nothing
            here reads the body to decide — a classifier guessing from free text is confidently wrong in
            a way nobody can debug.
          </p>
        </Panel>
        <Panel tint="sandstone" title="Ordered by what happened last">
          <p className="text-xs leading-relaxed" style={{ color: 'var(--text-secondary)' }}>
            The rail is ordered by each thread&rsquo;s most recent message, and a thread nobody has written
            in yet sorts last: it has no activity to place in time, and a timestamp is not invented for
            it.
          </p>
        </Panel>
      </div>
    </PageAccessGuard>
  )
}

/** One message, drawn in the material its stored sender type calls for. */
function MessageBubble({ message }: { message: MessageSummary }) {
  const internal = message.internal
  const fromFirm = message.senderType === 'FIRM'
  const isSystem = message.senderType === 'SYSTEM'
  const unread = !internal && !fromFirm && message.readAt === null

  const wrapper = internal
    ? 'message-note max-w-[85%] px-3 py-2'
    : fromFirm
      ? 'message-firm max-w-[85%] px-3 py-2'
      : isSystem
        ? 'max-w-[85%] rounded-[var(--radius-field)] border px-3 py-2'
        : `message-client max-w-[85%] px-3 py-2 ${unread ? 'message-unread' : ''}`

  const style = isSystem && !internal
    ? { borderColor: 'var(--border)', background: 'var(--surface-muted)', color: 'var(--text-secondary)' }
    : undefined

  const metaColour = internal
    ? 'var(--plum-ink)'
    : fromFirm
      ? 'var(--hero-muted)'
      : 'var(--text-secondary)'
  const bodyColour = internal ? 'var(--plum-ink)' : fromFirm ? 'var(--hero-text)' : 'var(--text-primary)'

  return (
    <article className={wrapper} style={style}>
      <header className="flex flex-wrap items-baseline gap-x-2 gap-y-1">
        <span className="text-[11px] font-bold uppercase tracking-wide" style={{ color: metaColour }}>
          {message.senderName ?? (fromFirm ? 'The firm' : humanize(message.senderType))}
        </span>
        <span className="text-[10px]" style={{ color: metaColour }}>
          {message.sentAt ? <span title={dateTime(message.sentAt)}>{relative(message.sentAt)}</span> : 'No time recorded'}
        </span>
        {internal && (
          <span className="text-[10px] font-bold uppercase tracking-wide" style={{ color: 'var(--ink-plum)' }}>
            Internal note — not sent to the client
          </span>
        )}
        {isSystem && (
          <span className="text-[10px] font-bold uppercase tracking-wide">
            System event{message.systemEvent ? `: ${humanize(message.systemEvent)}` : ''}
          </span>
        )}
        {unread && (
          <span className="text-[10px] font-bold uppercase tracking-wide" style={{ color: 'var(--ink-copper)' }}>
            Unread by the firm
          </span>
        )}
      </header>
      <p className="mt-1 whitespace-pre-wrap break-words text-sm" style={{ color: bodyColour }}>
        {message.body || 'This message carries no body — a system event with nothing further to say.'}
      </p>
      <p className="mt-1 text-[10px] tabular-nums" style={{ color: metaColour }}>
        {message.sentAt ? dateTime(message.sentAt) : ''}
        {message.messageType !== 'TEXT' ? ` · ${humanize(message.messageType)}` : ''}
      </p>
    </article>
  )
}
