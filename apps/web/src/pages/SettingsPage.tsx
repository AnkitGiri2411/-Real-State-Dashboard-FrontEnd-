import { useQuery } from '@tanstack/react-query'
import { Building2, FileCog, Layers, Palette, Scale, ShieldCheck } from 'lucide-react'
import { PageAccessGuard, PageHeader } from '../components/common'
import {
  Badge, DetailItem, EmptyState, ErrorState, Panel, SectionHeader, Skeleton,
} from '../components/ui'
import { humanize } from '../components/ui/formatters'
import { platformApi } from '../services'
import { usePlatformStore } from '../platform'
import { messageOf } from '../lib/errors'
import type { PlatformProfile, PolicyThresholdsView } from '../types'

/**
 * Settings — read-only, on purpose.
 *
 * <p><b>Why there is nothing to change here.</b> This product has no settings write API. The firm's
 * identity, the product and industry it is built for, the branding the installation resolved and the
 * policy thresholds the dashboard and the reports reason about are all *configuration* on the
 * backend (`app.firm.*`, `app.portfolio.*`, `app.money.*`, `app.imports.*`, `app.platform.*` in
 * `application.yml`), served by `GET /platform/profile`. One installation serves one firm, so those
 * values are settled when it is deployed rather than edited from a screen.</p>
 *
 * <p>A screen like this is normally where a product grows a row of switches that write nowhere: a
 * toggle that flips in the browser and changes nothing on the server is worse than no toggle at all,
 * because it teaches the reader that this desk cannot be trusted about its own state. So this page
 * renders what the backend actually publishes, in a labelled definition list, each line explaining
 * what the value governs — and states plainly where the values come from.</p>
 *
 * <p>The permission seed carries a `SETTINGS_VIEW` code, which the sidebar's own entry names. The
 * endpoint behind this screen requires only a signed-in session, and everything shown here is
 * already published to every session by the shell's boot read — so this screen is guarded by the
 * session rather than by inventing a code the endpoint does not check.</p>
 */
export default function SettingsPage() {
  // The same read the shell boots from, so the two can never disagree about the firm's name or the
  // import limits. `initialData` is the copy the shell already holds, so opening Settings is not a
  // second request; when the shell has not loaded it, this query fetches it and its own failure is
  // what the reader is shown, with a retry that re-runs the read.
  const cached = usePlatformStore((state) => state.profile)
  const profileQuery = useQuery({
    queryKey: ['platform', 'profile'],
    queryFn: () => platformApi.profile(),
    initialData: cached ?? undefined,
  })

  return (
    <PageAccessGuard permission={null}>
      <PageHeader
        eyebrow="Installation"
        title="Settings"
        description="What this installation is, which firm it serves, and the thresholds it reasons about — read from the backend's own configuration."
      />

      {profileQuery.isLoading && <Skeleton rows={6} />}

      {profileQuery.isError && (
        <ErrorState
          message={messageOf(profileQuery.error)}
          details={[
            'The installation profile could not be read, so nothing about this firm is being guessed at.',
          ]}
          onRetry={() => void profileQuery.refetch()}
        />
      )}

      {!profileQuery.isLoading && !profileQuery.isError && !profileQuery.data && (
        <EmptyState
          title="This installation has not published a profile"
          description="The firm's identity, branding and policy thresholds are configuration on the backend. Until that configuration is served, there is nothing to read here."
        />
      )}

      {!profileQuery.isLoading && !profileQuery.isError && profileQuery.data && (
        <SettingsBody profile={profileQuery.data} />
      )}
    </PageAccessGuard>
  )
}

/**
 * The policy thresholds, each with the sentence that explains what it governs.
 *
 * The list is typed against the profile's own `policies` record, so a threshold the backend stops
 * sending becomes a compile error here rather than a blank line on the screen.
 */
interface PolicyLine {
  key: keyof PolicyThresholdsView
  label: string
  governs: string
  format: (policies: PolicyThresholdsView) => string
}

const POLICY_LINES: PolicyLine[] = [
  {
    key: 'leaseExpiryWarningDays',
    label: 'Lease expiry warning',
    governs: 'How far ahead a lease entering its final stretch is flagged for renewal — on the dashboard\u2019s attention panel, on the lease register and in the expiry report.',
    format: (p) => `${p.leaseExpiryWarningDays} days`,
  },
  {
    key: 'leaseExpiryUrgentDays',
    label: 'Lease expiry urgent',
    governs: 'The shorter window inside that warning where a renewal is treated as urgent rather than merely due, and is marked as such rather than left to the reader to judge.',
    format: (p) => `${p.leaseExpiryUrgentDays} days`,
  },
  {
    key: 'rentDueDay',
    label: 'Rent due day',
    governs: 'The day of the month rent falls due where an invoice does not state a due date of its own.',
    format: (p) => `Day ${p.rentDueDay}`,
  },
  {
    key: 'rentOverdueGraceDays',
    label: 'Overdue grace',
    governs: 'How long an unpaid invoice may stand past its due date before it is reported as overdue, and so before it appears in arrears.',
    format: (p) => `${p.rentOverdueGraceDays} days`,
  },
  {
    key: 'occupancyTargetPercent',
    label: 'Occupancy target',
    governs: 'The occupancy the portfolio is managed against. The dashboard draws this as the target line on its occupancy ring, and the occupancy report compares each property with it.',
    format: (p) => `${p.occupancyTargetPercent}%`,
  },
  {
    key: 'listingStaleDays',
    label: 'Listing stale after',
    governs: 'How long a listing may stand without activity before it is marked stale and raised on the listing register.',
    format: (p) => `${p.listingStaleDays} days`,
  },
  {
    key: 'viewingReminderHours',
    label: 'Viewing reminder',
    governs: 'How long before a scheduled viewing the desk is reminded that it is due.',
    format: (p) => `${p.viewingReminderHours} hours`,
  },
  {
    key: 'maintenanceUrgentHours',
    label: 'Urgent maintenance window',
    governs: 'The response window an urgent maintenance request is measured against before it is reported as late.',
    format: (p) => `${p.maintenanceUrgentHours} hours`,
  },
  {
    key: 'trendMonths',
    label: 'Trend history',
    governs: 'How many months of history the dashboard\u2019s trend series and the windowed reports cover by default.',
    format: (p) => `${p.trendMonths} months`,
  },
  {
    key: 'importMaxFileSizeMb',
    label: 'Import file size limit',
    governs: 'The largest spreadsheet the import will read. A file above this is refused before it is parsed, and the limit is stated on the import screen before a file is chosen.',
    format: (p) => `${p.importMaxFileSizeMb} MB`,
  },
  {
    key: 'importMaxRows',
    label: 'Import row limit',
    governs: 'The most rows a single import will stage. A file with more rows is refused rather than quietly truncated.',
    format: (p) => `${p.importMaxRows} rows`,
  },
  {
    key: 'currency',
    label: 'Currency code',
    governs: 'The currency every figure in the product is expressed in, and the code the reports and exports state.',
    format: (p) => p.currency,
  },
  {
    key: 'currencySymbol',
    label: 'Currency symbol',
    governs: 'The symbol prefixed to every money figure, so a table of amounts is never labelled with a symbol the backend did not send.',
    format: (p) => p.currencySymbol,
  },
]

function SettingsBody({ profile }: { profile: PlatformProfile }) {
  const { firm, policies, product, industry, theme } = profile

  return (
    <>
      {/* ── The firm ─────────────────────────────────────────────────────── */}
      <SectionHeader
        eyebrow="The firm"
        title={firm.name}
        rule="forest"
        description="Served by GET /platform/profile from the installation's own configuration — not from a table this screen could write to."
      />
      <div className="mb-6 grid gap-4 lg:grid-cols-2">
        <Panel notch title="Firm profile" icon={<Building2 size={15} />}>
          <dl className="grid gap-3 sm:grid-cols-2">
            <DetailItem label="Registered name" value={firm.name} />
            <DetailItem label="Firm code" value={firm.code} />
            <DetailItem label="E-mail" value={firm.email} />
            <DetailItem label="Telephone" value={firm.phone} />
            <DetailItem label="Licence" value={firm.licence} />
            <DetailItem label="Address" value={firm.address} />
          </dl>
        </Panel>

        <Panel title="Product and industry" subtitle="What this build is, and what it is built for" tint="copper" icon={<Layers size={15} />}>
          <dl className="grid gap-3 sm:grid-cols-2">
            <DetailItem label="Product" value={product.name} />
            <DetailItem label="Product code" value={product.code} />
            <DetailItem label="Version" value={product.version} />
            <DetailItem label="Industry" value={industry.name} />
            <DetailItem label="Industry code" value={industry.code} />
            <DetailItem
              label="Modules enabled"
              value={`${profile.enabledModules.length} of ${profile.navigation.length} navigation entries`}
            />
          </dl>
          {profile.enabledModules.length > 0 && (
            <ul className="mt-3 flex flex-wrap gap-1.5 border-t pt-3" style={{ borderColor: 'var(--border)' }}>
              {profile.enabledModules.map((moduleKey) => (
                <li key={moduleKey}>
                  <Badge tone="sandstone">{humanize(moduleKey)}</Badge>
                </li>
              ))}
            </ul>
          )}
          <p className="mt-3 text-[11px]" style={{ color: 'var(--text-secondary)' }}>
            A build is one product for one industry. There is no industry or product switcher here, and
            there is deliberately no endpoint that changes either.
          </p>
        </Panel>
      </div>

      {/* ── Branding ─────────────────────────────────────────────────────── */}
      <SectionHeader
        eyebrow="Branding"
        title="What this installation resolved"
        rule="champagne"
        description="The names and colours the shell, the sign-in screen and the reports are read in. A rebrand is a configuration change rather than a rebuild."
      />
      <div className="mb-6 grid gap-4 lg:grid-cols-[1fr_1.4fr]">
        <Panel notch title="Identity" tint="champagne" icon={<Palette size={15} />}>
          {theme.logoUrl ? (
            <img
              src={theme.logoUrl}
              alt={`${theme.brandName} mark`}
              className="mb-3 h-12 w-auto"
              style={{ maxWidth: '100%' }}
            />
          ) : (
            <p className="mb-3 text-xs" style={{ color: 'var(--text-secondary)' }}>
              No logo file was configured for this installation.
            </p>
          )}
          <dl className="grid gap-3 sm:grid-cols-2">
            <DetailItem label="Brand name" value={theme.brandName} />
            <DetailItem label="Company name" value={theme.companyName} />
            <DetailItem label="Product name" value={theme.productName} />
            <DetailItem label="Sidebar label" value={theme.sidebarBrandLabel} />
            <DetailItem label="Footer" value={theme.footerText} />
            <DetailItem label="Colour scheme" value={theme.colorScheme} />
          </dl>
        </Panel>

        <Panel title="Sign-in copy" subtitle="The words a colleague meets before they are inside">
          <dl className="space-y-3">
            <DetailItem label="Headline" value={theme.loginHeadline} />
            <DetailItem label="Subheadline" value={theme.loginSubheadline} />
          </dl>
          <dl className="mt-4 grid gap-3 border-t pt-3 sm:grid-cols-3" style={{ borderColor: 'var(--border)' }}>
            <DetailItem
              label="Primary colour"
              value={
                theme.primaryColor
                  ? (
                    <span className="inline-flex items-center gap-2">
                      <span
                        aria-hidden="true"
                        className="inline-block h-3 w-3 rounded-[2px] border"
                        style={{ background: theme.primaryColor, borderColor: 'var(--border-strong)' }}
                      />
                      <span className="tabular-nums">{theme.primaryColor}</span>
                    </span>
                  )
                  : 'Not set — Meridian\u2019s own forest is kept'
              }
            />
            <DetailItem
              label="Primary soft"
              value={
                theme.primarySoftColor
                  ? (
                    <span className="inline-flex items-center gap-2">
                      <span
                        aria-hidden="true"
                        className="inline-block h-3 w-3 rounded-[2px] border"
                        style={{ background: theme.primarySoftColor, borderColor: 'var(--border-strong)' }}
                      />
                      <span className="tabular-nums">{theme.primarySoftColor}</span>
                    </span>
                  )
                  : 'Not set'
              }
            />
            <DetailItem
              label="Secondary colour"
              value={
                theme.secondaryColor
                  ? (
                    <span className="inline-flex items-center gap-2">
                      <span
                        aria-hidden="true"
                        className="inline-block h-3 w-3 rounded-[2px] border"
                        style={{ background: theme.secondaryColor, borderColor: 'var(--border-strong)' }}
                      />
                      <span className="tabular-nums">{theme.secondaryColor}</span>
                    </span>
                  )
                  : 'Not set — Meridian\u2019s own copper is kept'
              }
            />
          </dl>
          <p className="mt-3 text-[11px]" style={{ color: 'var(--text-secondary)' }}>
            A colour the installation did not supply is left alone rather than replaced with an empty
            value, so an un-rebranded installation keeps Meridian&rsquo;s forest and copper.
          </p>
        </Panel>
      </div>

      {/* ── Policy thresholds ────────────────────────────────────────────── */}
      <SectionHeader
        eyebrow="Policy"
        title="Thresholds this installation reasons about"
        rule="emerald"
        description="The dashboard, the registers and the reports all read these rather than each carrying its own number."
      />
      <Panel
        className="mb-6"
        icon={<Scale size={15} />}
        tint="emerald"
        title="Policy thresholds"
        subtitle="Read from the installation's configuration — each line says what the value governs"
      >
        <dl className="divide-y" style={{ borderColor: 'var(--border)' }}>
          {POLICY_LINES.map((line) => (
            <div key={line.key} className="grid gap-1 py-3 first:pt-0 last:pb-0 sm:grid-cols-[16rem_9rem_1fr] sm:items-baseline sm:gap-4">
              <dt className="text-xs font-semibold" style={{ color: 'var(--text-primary)' }}>
                {line.label}
              </dt>
              <dd className="kpi-value text-sm" style={{ color: 'var(--ink-emerald)' }}>
                {line.format(policies)}
              </dd>
              <dd className="text-xs leading-relaxed" style={{ color: 'var(--text-secondary)' }}>
                {line.governs}
              </dd>
            </div>
          ))}
        </dl>
      </Panel>

      {/* ── Where these come from ────────────────────────────────────────── */}
      <Panel tint="slate" title="Where these values come from" icon={<FileCog size={15} />}>
        <ul className="space-y-2 text-sm" style={{ color: 'var(--text-secondary)' }}>
          <li className="flex gap-2">
            <ShieldCheck size={15} aria-hidden="true" className="mt-0.5 shrink-0" style={{ color: 'var(--ink-slate)' }} />
            <span>
              Every value on this page is <strong>set by this installation&rsquo;s configuration</strong> — the
              backend&rsquo;s own application properties (<span className="tabular-nums">app.firm</span>,{' '}
              <span className="tabular-nums">app.portfolio</span>,{' '}
              <span className="tabular-nums">app.money</span>,{' '}
              <span className="tabular-nums">app.imports</span>,{' '}
              <span className="tabular-nums">app.platform</span>) — and published by{' '}
              <span className="tabular-nums">GET /platform/profile</span>.
            </span>
          </li>
          <li className="flex gap-2">
            <ShieldCheck size={15} aria-hidden="true" className="mt-0.5 shrink-0" style={{ color: 'var(--ink-slate)' }} />
            <span>
              <strong>This screen is read-only.</strong> The product has no settings write API, so there is
              no Save button, no switch and no toggle here — an unwired control would tell you that a
              change had been made when nothing had. A change to any value is a configuration change,
              made where this installation is deployed and applied when it restarts.
            </span>
          </li>
          <li className="flex gap-2">
            <ShieldCheck size={15} aria-hidden="true" className="mt-0.5 shrink-0" style={{ color: 'var(--ink-slate)' }} />
            <span>
              Nothing on this page is cached from a build. The shell boots from the same read, so what
              you see here is what the rest of the product is using.
            </span>
          </li>
        </ul>
      </Panel>
    </>
  )
}
