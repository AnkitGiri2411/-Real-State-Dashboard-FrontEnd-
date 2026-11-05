import { type CSSProperties } from 'react'
import { Link } from 'react-router-dom'
import { Compass } from 'lucide-react'
import { PageHeader } from '../components/common'
import { EmptyState, Panel, SectionHeader } from '../components/ui'

/**
 * The 404.
 *
 * An address that is not part of this build is a *place*, not a failure, so the reader is told
 * plainly that nothing is drawn there and is offered the doors that do exist. Every destination
 * listed below is a route this build actually serves (`src/routes.ts`), so none of them is a link to
 * nothing.
 *
 * This is the one screen in the product with no data states, and deliberately so: nothing is fetched,
 * so there is nothing to load, nothing to retry and nothing to be empty. Issuing a request from a
 * page that has no data to show would invent work, and a retry button on a page that cannot fail
 * would be a control that does nothing.
 */
export default function NotFoundPage() {
  const destinations = [
    { to: '/dashboard', label: 'Portfolio dashboard', note: 'The position as the ledger stands today' },
    { to: '/properties', label: 'Properties', note: 'The asset register, with occupancy and rent' },
    { to: '/documents', label: 'Documents', note: 'Agreements, deeds and certificates on record' },
    { to: '/reports', label: 'Reports', note: 'Performance, income and pipeline reports' },
    { to: '/audit-logs', label: 'Audit logs', note: 'The append-only trail of what was done' },
    { to: '/profile', label: 'Your profile', note: 'Your identity, roles and permissions' },
  ]

  return (
    <>
      <PageHeader
        eyebrow="404 · Not found"
        title="Nothing is drawn at this address"
        description="The link may be out of date, or it may point at a screen that belongs to another installation. The rail on the left lists every screen your role can open."
      />

      <Panel
        notch
        title="Page not found"
        subtitle="No route in this build answers this address"
        actions={
          <Link to="/dashboard" className="focus-meridian btn btn-primary">
            Back to the dashboard
          </Link>
        }
      >
        <EmptyState
          icon={<Compass size={24} aria-hidden="true" />}
          title="This address is not one of Meridian's screens"
          description="Check the address in the bar above, or start again from the portfolio dashboard. If you followed a link from inside the product, the screen it pointed at is not part of this build."
        />
      </Panel>

      <div className="mt-6">
        <SectionHeader
          eyebrow="Where to go"
          title="Registers this installation serves"
          rule="copper"
          description="Each of these is a real screen backed by real endpoints."
        />
        <ul className="grid gap-2 sm:grid-cols-2 lg:grid-cols-3">
          {destinations.map((destination) => (
            <li key={destination.to}>
              <Link
                to={destination.to}
                className="focus-meridian plot-card block"
                style={{ '--notch': 'var(--background)' } as CSSProperties}
              >
                <span
                  className="block text-sm font-semibold"
                  style={{ color: 'var(--text-primary)' }}
                >
                  {destination.label}
                </span>
                <span className="mt-0.5 block text-xs" style={{ color: 'var(--text-secondary)' }}>
                  {destination.note}
                </span>
              </Link>
            </li>
          ))}
        </ul>
      </div>
    </>
  )
}
