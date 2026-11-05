import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { PageAccessGuard, PageHeader } from '../components/common'
import {
  Badge, DataTable, EmptyState, ErrorState, Panel, Pagination, SectionHeader, Skeleton, StatusBadge,
  type Column,
} from '../components/ui'
import { count as fmtCount } from '../components/ui/formatters'
import { officeApi } from '../services'
import { messageOf } from '../lib/errors'
import type { OfficeSummary } from '../types'

/**
 * The office (branch) register.
 *
 * An office is a real place — agents sit in it, viewings are booked from it, properties are reported
 * under it — so it reads as its own register rather than as a string on a staff record.
 *
 * **On the permission.** `V2` seeds no `OFFICE_*` permission, because the product defines none.
 * Offices belong to the agents domain in this schema (`V7` creates offices, agent profiles and
 * assignments together), and the office endpoints re-check the agents module's codes. The screen is
 * therefore gated on `AGENT_VIEW`, which is what the API itself requires — inventing an `OFFICE_VIEW`
 * here would hide the screen from users the API would happily serve.
 *
 * **The head office is a fact, not a colour.** Exactly one branch may hold the flag (the service
 * refuses a second as a named 409 rather than silently moving it), so the flag is spelled out as a
 * badge in its own column rather than signalled by a tint alone.
 */

export default function OfficesPage() {
  return (
    <PageAccessGuard permission="AGENT_VIEW">
      <OfficeRegister />
    </PageAccessGuard>
  )
}

function OfficeRegister() {
  const [page, setPage] = useState(0)
  const [size, setSize] = useState(20)

  const query = useQuery({
    queryKey: ['offices', { page, size }],
    queryFn: () => officeApi.list({ page, size, sort: 'name,asc' }),
  })

  const columns: Column<OfficeSummary>[] = [
    {
      key: 'office',
      header: 'Office',
      width: '34%',
      render: (row) => (
        <span className="block min-w-0">
          <span className="block truncate font-medium" style={{ color: 'var(--text-primary)' }}>{row.name}</span>
          <span className="block truncate text-[11px] tabular-nums" style={{ color: 'var(--text-secondary)' }}>{row.officeCode}</span>
        </span>
      ),
    },
    {
      key: 'city',
      header: 'City',
      width: '22%',
      secondary: true,
      render: (row) => row.city ?? '—',
    },
    {
      key: 'role',
      header: 'Office role',
      width: '18%',
      render: (row) => (
        // The wording is the distinction; the tint only reinforces it, so a reader who cannot tell
        // the two hues apart still knows which branch is the registered office.
        <Badge tone={row.headOffice ? 'copper' : 'slate'}>
          {row.headOffice ? 'Head office' : 'Branch'}
        </Badge>
      ),
    },
    /*
     * This column used to be "Agents", read from an `agentCount` the register row does not send: a
     * branch's headcount is counted for one office by `OfficeDtos.OfficeDetail.agentCount`, and no
     * office row carries it. The slot now holds the office's state, which `OfficeDtos.OfficeSummary`
     * does promise - and the agents who sit in a branch are the agent register filtered by office.
     */
    {
      key: 'state',
      header: 'State',
      width: '12%',
      secondary: true,
      render: (row) => row.state ?? '—',
    },
    {
      key: 'status',
      header: 'Status',
      width: '14%',
      render: (row) => <StatusBadge status={row.status} />,
    },
  ]

  if (query.isLoading) {
    return (
      <>
        <PageHeader title="Offices" description="Reading the office register…" />
        <Skeleton rows={6} />
      </>
    )
  }

  if (query.isError) {
    return (
      <>
        <PageHeader title="Offices" />
        <ErrorState message={messageOf(query.error)} onRetry={() => void query.refetch()} />
      </>
    )
  }

  const items = query.data?.items ?? []
  const totalElements = query.data?.totalElements ?? 0

  return (
    <>
      <PageHeader
        eyebrow="Network"
        title="Offices"
        description="The firm's branches. Each office is a place agents sit in and viewings are booked from, so it is its own register rather than a field on a staff record."
      />

      <SectionHeader
        eyebrow="Branches"
        title="The office register"
        rule="sandstone"
        description="The registered head office is marked in the Office role column; exactly one branch may hold it at a time."
      />

      <Panel notch>
        <p className="px-4 pt-3 text-xs" aria-live="polite" style={{ color: 'var(--text-secondary)' }}>
          {query.isFetching ? 'Reading the register…' : `${fmtCount(totalElements)} office${totalElements === 1 ? '' : 's'} on the register`}
        </p>

        <DataTable
          columns={columns}
          rows={items}
          keyOf={(row) => row.id}
          caption="Office register"
          empty={
            <EmptyState
              title="The office register is empty"
              description="No branches have been added yet. An office appears here once one is created, and the agents who sit in it are counted against its row."
            />
          }
        />

        <Pagination
          page={page}
          size={size}
          totalElements={totalElements}
          totalPages={query.data?.totalPages ?? 0}
          hasNext={query.data?.hasNext ?? false}
          onPageChange={setPage}
          onSizeChange={(next) => { setSize(next); setPage(0) }}
        />
      </Panel>
    </>
  )
}
