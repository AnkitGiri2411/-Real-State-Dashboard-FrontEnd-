/**
 * The destinations this build actually serves.
 *
 * The sidebar is served by the backend, so this list is what keeps the menu honest: a navigation
 * entry is offered only once its screen exists. A route named in the platform profile but absent
 * here is hidden rather than shown as a link to nothing - which is the bug this list exists to
 * catch, and the reason it is a list of literals rather than something derived.
 *
 * Every path below is a real screen backed by real endpoints.
 */
export const IMPLEMENTED_PATHS = [
  '/dashboard',

  '/properties',
  '/units',
  '/owners',
  '/portfolios',

  '/tenants',
  '/buyers',
  '/agents',
  '/offices',

  '/listings',
  '/viewings',
  '/applications',
  '/leases',
  '/payments',

  '/leads',
  '/offers',
  '/sales',

  '/maintenance',
  '/expenses',
  '/documents',

  '/communication',
  '/notifications',

  '/reports',
  '/audit-logs',

  '/imports',
  '/settings',
  '/profile',
] as const

export type ImplementedPath = (typeof IMPLEMENTED_PATHS)[number]

/** True when a navigation entry points at a route this build serves. */
export function isImplemented(to: string): boolean {
  return (IMPLEMENTED_PATHS as readonly string[]).includes(to)
}
