# Testing

What each check covers, how to run it, and what it has actually been run against.

Two rules apply throughout. **A build succeeding is not a test.** And **a number here is a number that
was measured** — where a check has not been run against an environment, it is labelled
**NOT VERIFIED** rather than assumed.

---

## Summary

| Check | Command | Scope | Last verified |
| --- | --- | --- | --- |
| Backend tests | `cd backend && mvn -B test` | 30 suites | **187 tests, 0 failures, 0 errors, 0 skipped** |
| Frontend tests | `cd apps/web && npm test` | 4 suites | **56 tests passed** |
| Strict typecheck | `cd apps/web && npm run typecheck` | whole app | **PASS** (`tsc --noEmit`, exit 0) |
| Production frontend build | `cd apps/web && npm run build` | whole app | **PASS** |
| Production backend build | `cd backend && mvn -DskipTests clean package` | whole service | **PASS** |
| DTO consistency | see below | 122 interfaces | **121 exact, 1 by hand, 0 drift** |
| Fresh-database migration | boot the JAR on an empty database | V1–V23 | **23 migrations applied, Hibernate validated** |
| Browser verification | manual + axe | 29 routes | **0 console errors after sign-in, 0 failed requests** |
| axe (WCAG 2.0/2.1 A+AA) | axe-core 4.13.0 injected into the running build | 29 routes × 2 widths | **0 violations** |
| Contrast | `theme.test.tsx` | design tokens | **33 assertions pass** |
| Responsive | 6 widths × 29 routes | current build | **174 checks, 0 horizontal overflow** |
| Keyboard | real key events | shell, registers, dialogs | **PASS** |
| Reduced motion | `prefers-reduced-motion: reduce` | current build | **PASS** |
| Import workflow | end to end, live | current build | **PASS** |

---

## Scope: what these rows were measured against

A source change invalidates a gate that was passed against an earlier build, so the rows above have to
be read in two groups. The deployment work changed three things a browser-level gate depends on:
`SecurityConfig` (which requests are permitted before authentication), `WebConfig` (the fallback that
decides whether a path serves the shell or the product's JSON error envelope), and `apiClient`'s URL
composition.

**Re-measured against the current build.** The backend suite (187), the frontend suite (56), the strict
typecheck, both production builds, the migration chain from an empty database (`V1`–`V23`, then
Hibernate's validation), and an endpoint sweep in which 103 `GET` routes answered `200` — every read
endpoint, all 12 reports, and the CSV export. The import workflow was driven end to end (staged,
confirmed, replayed idempotently, then refused on a different key), and a mutation lifecycle was traced
from `201`-equivalent response through the database to its audit entries.

**Re-measured in a browser against the container image**, on the one-origin build: sign-in completed and
the dashboard, its registers and a deep-linked property detail rendered real seeded data, with **0
console errors and 0 failed requests** after sign-in; axe-core reported **0 violations across 9
routes**; and **21 route × width overflow checks** at 375/768/1440 found **0 overflowing**.

**Not re-measured against this build**, and therefore still describing the previous one: the full
29-route browser sweep, the full 29-route × 2-width axe matrix, the 174-check responsive matrix at six
widths, the keyboard suite and the reduced-motion checks. The rows above remain accurate for the build
they were taken from — re-run the sections below before treating them as current.

---

## Backend tests

```bash
cd backend
mvn -B test
```

30 test classes. Most are unit tests of the services and policies, run with Mockito and no Spring
context, so they need no database. The suite is **187 tests, 0 failures, 0 errors, 0 skipped** — the
173 the project already had, the two added for the audit regression described below, and twelve
added with `DataSourceUrlNormaliserTest` for the deployment work.

| Suite | What it pins |
| --- | --- |
| `SchemaConformanceTest` | the entity model against the migration chain, statically (see [DATABASE.md](./DATABASE.md)) |
| `AuthServiceTest`, `JwtServiceTest` | sign-in, token issue and refresh rotation |
| `AccessScopeTest` | the firm-wide/permission scope rules |
| `MoneyPolicyTest` | money rounding and scale |
| `BusinessNumberServiceTest` | the human-readable code series |
| `ErrorMessagesTest` | every code resolves to real wording; **no duplicate keys** |
| `DataSourceUrlNormaliserTest` | a platform's `postgres://` connection string becomes a JDBC URL — including a percent-escaped password, a literal `+` (the case form-decoding gets wrong), and the already-JDBC and absent values it must leave untouched |
| service suites | the business rules of each module |
| `AuditRecordingServiceTest` | the audit builder through a real CGLIB proxy (see below) |

### `AuditRecordingServiceTest` — why it exists

`AuditRecordingService` is CGLIB-proxied for its transactional boundaries, and a CGLIB proxy is
instantiated **without running the constructor**: only its method calls are intercepted and forwarded
to the target, so a *field* read through the proxy sees the proxy's own uninitialised fields. The
audit builder's `asCurrentUser()` read `service.accessScope` that way and threw a
`NullPointerException` on **every import commit** — the one and only caller of that method in the
product, which is why nothing had caught it.

The test therefore does not mock the service: a mock (or a normally-constructed instance) has its
fields set, so the defect is invisible through either. It builds a real `ProxyFactory` proxy over a
fully-constructed target — precisely the topology Spring creates — and drives an entry through it.

It was confirmed to **fail against the previous code** with the exact production exception
(`... because "this.service.accessScope" is null`) and to pass against the fix, so it is a real
regression guard rather than a test that happens to be green.

---

## Frontend tests

```bash
cd apps/web
npm test          # vitest run
```

4 suites, 56 tests, jsdom:

| Suite | Tests | What it pins |
| --- | ---: | --- |
| `components/theme.test.tsx` | 33 | the design system's **contrast guarantee**, measured: it parses the real `styles.css`, resolves the `var()` indirection, and computes the WCAG 2.1 relative-luminance ratio for every ink-on-its-own-wash pair (4.5:1 for text, 3:1 for a mark), the body text on every base surface, the semantic status colours, and the inverted hero band |
| `auth/session.test.ts` | 10 | token storage, expiry detection, rotation |
| `routes.test.ts` | 8 | the route table and the implemented-path list that keeps the sidebar honest |
| `services/apiClient.test.ts` | 5 | how a request URL is composed, and specifically that a **relative** base resolves. The deployed build sets `VITE_API_BASE_URL=/api/v1`, and `new URL('/api/v1/x')` on its own throws `Invalid URL` — which would fail every request in the product, sign-in included, while the build reported success and the page rendered perfectly |

`theme.test.tsx` runs in the `node` environment (it only reads a file), which is why `test/setup.ts`
guards everything on the DOM actually being present.

---

## Typecheck and builds

```bash
cd apps/web
npm run typecheck          # tsc --noEmit, strict
npm run build              # tsc --noEmit && vite build

cd ../../backend
mvn -DskipTests clean package
```

The build warns (does not fail) about two things, both of which are informational:

- `auth/session.ts` is both statically and dynamically imported, so the dynamic import does not move
  it into its own chunk;
- two chunks exceed Vite's 400 kB advisory threshold (`vendor-icons` is the largest).

Neither is an error, and neither has been suppressed.

---

## DTO consistency

The frontend's payload types must match the backend's DTO records **field for field**, because a
plausible-looking field the API never sends is how a screen renders blanks that look like data.

Two checks stand behind that claim.

**1. Static, against the source.** Every `public record` in `backend/src/main/java/.../dto/*.java` was
parsed into its component names and compared with every interface in `apps/web/src/types/index.ts`,
resolving `extends` and the `\`Dtos.Record\`` reference each interface documents:

| Result | Count |
| --- | ---: |
| Interfaces matching their DTO exactly | **121** |
| Verified by hand (`ApiFailure` ↔ `ErrorResponse`) | 1 |
| Drift (a field on one side and not the other) | **0** |

`ApiFailure` is the one interface with no `Dtos.Record` reference, because it is the failure envelope:
it mirrors `ErrorResponse` / `ErrorBody`, which is stated rather than annotated.

**2. Live, against the wire.** The running API was asked and its keys compared:

- `GET /properties` returns exactly `id, propertyCode, name, propertyTypeId, propertyTypeCode,
  propertyTypeName, status, city, locality, state, country, currentValuation, acquisitionValue,
  positioned, createdAt, updatedAt` — **no unit count, no occupancy, no rent roll, no coordinates**.
  Those live on `PropertyMetrics`, read one property at a time from `/properties/{id}/metrics`, which
  is why the register carries no such columns.
- `GET /leases` returns exactly `id, leaseCode, status, propertyId, propertyName, unitId, tenantId,
  tenantName, startDate, endDate, rentAmount, rentFrequency, dueDay, depositAmount, createdAt` —
  **no `unitCode` and no `daysRemaining`**. The row names its unit by id, and a countdown is read from
  `endDate`; `LeaseDtos.ExpiringLease` is the projection that carries `daysUntilEnd`.

### Endpoint consistency

The same exercise was applied to the endpoints. Every path declared in `apps/web/src/services/index.ts`
was normalised and matched against the real `@RequestMapping`/`@GetMapping`/… mappings across all
controllers: **99 of 104 matched**, and of the five that did not, four are not endpoints at all (a
dynamic segment resolved at runtime, and three bare path prefixes used to build others). The one real
finding — a call to a `/leases/{id}/finance` endpoint **that does not exist**, answered `404` on every
lease — was fixed by reading the `finance` block the detail payload already carries.

---

## Fresh-database migration and startup validation

Against an **empty** database, the production JAR was started and Flyway applied the whole chain:

```
V1 … V23 applied in order, then Hibernate validated the entity model against the result
```

Seeded afterwards: **14 properties, 222 units, 166 leases, 10 staff accounts, 8 roles, 99 permissions,
65 tables.** A model/schema drift would have failed startup rather than being silently rewritten
(`ddl-auto: validate`).

---

## Browser verification

Run against the **production bundle** served by `vite preview` (`:4175`) with the real API (`:8083`),
not against the dev server.

- **Sign-in**: `POST /auth/login` → `200`, then `/auth/me`, `/platform/profile`,
  `/notifications/unread-count`, `/dashboard/summary`, `/properties/map`, `/leases/expiring` all
  `200`.
- **Session lifecycle**: sign out through the account menu, then sign in again.
  `POST /auth/logout` → **`200`**, the revoked refresh token is afterwards refused by
  `POST /auth/refresh` with `401 REFRESH_TOKEN_REVOKED`, the next sign-in works, and the dashboard
  renders. **0 failed requests and 0 console errors** across the whole sign-out → sign-in sequence.
- **The public surface stays public and nothing else opens**: unauthenticated `GET /auth/me`,
  `/properties`, `/leases`, `/imports`, `/audit`, `/reports` and `POST /auth/logout-all`,
  `/auth/change-password`, `/properties` all answer **`401`**.
- **Refresh rotation**: the refresh token rotates on use, and **reusing the previous one is refused**
  with `401 REFRESH_TOKEN_REVOKED`.
- **Reports**: all **12** report names return `200` with real payloads, and the CSV export returns
  `text/csv`.
- **Console**: after sign-in, **0 errors**. The only errors in the log are the three `401`s the app
  receives *before* sign-in while it establishes that there is no session — which is the correct way
  to discover it.
- **Routes**: every route rendered its real data with no failed request.
- **Detail routes**: `/properties/{id}` → the real property with its Asset record, Metrics, Buildings,
  Ownership and Units sections; `/leases/{id}` → the real lease, with the money panel showing the
  ledger's own figures (`Invoiced Rs. 9,08,600.00 · Settled Rs. 9,08,600.00 · Outstanding Rs. 0.00 ·
  Overdue Rs. 0.00 · 2 invoices raised`).
- **Missing ids**: `/properties/{unknown}` → "Property not found"; `/leases/{unknown}` → "Lease not
  found". Controlled, named behaviour, and the only console entry is the browser logging the `404`
  the request deliberately earned.
- **No fabricated blanks**: the pages were read for values rendered as `—` because a field name was
  wrong. None were found beyond the one deliberate em-dash in descriptive copy.

### A defect this regression caught

Signing out produced a failed request and, worse, did not sign out. `authStore.signOut()` cleared the
local tokens **before** asking the server to revoke the refresh token, and `apiFetch` attaches the
access token by reading it out of storage — so `POST /auth/logout` always went out with no
`Authorization` header, was answered `401`, and the `catch` swallowed it. The reviewer saw a clean
sign-out; the server never revoked anything, and the refresh token stayed live for the rest of its
thirty days.

The endpoint is now reachable without an access token, on the same reasoning that makes
`/auth/refresh` public: it carries the refresh token in its body and **that token is the credential
being revoked**. It buys nothing to also require an access token — anyone holding the refresh token can
already do more with `/auth/refresh` — and requiring one is what broke the client. Verified after the
fix: `POST /auth/logout` → `200` with no `Authorization` header, the revoked token refused by
`/auth/refresh` with `401 REFRESH_TOKEN_REVOKED`, and every other endpoint still `401` unauthenticated.

---

## axe — WCAG 2.0/2.1 A + AA

axe-core 4.13.0 is injected into the running page and `axe.run` is called with
`runOnly: ['wcag2a','wcag2aa','wcag21a','wcag21aa']`. **No rule is suppressed.**

| Surface | Checks | Violations |
| --- | ---: | ---: |
| All 29 routes at 1280px | 29 | **0** |
| All 29 routes at 375px | 29 | **0** |
| A mutation dialog open (New property) | 1 | **0** |
| The mobile navigation drawer, open | 1 | **0** |
| The import screen at the preview step | 1 | **0** |

`color-contrast` reports as *incomplete* rather than passed, which is normal where a background is a
CSS variable or gradient and axe cannot compute it. That gap is closed by `theme.test.tsx`, which
computes the ratios from the real token values rather than sampling the rendered pixels.

Three genuine defects were found this way and fixed (see [ARCHITECTURE.md](./ARCHITECTURE.md) for the
code notes):

- **`nested-interactive` (serious)** — the portfolio map's `<svg role="img">` contained focusable
  marker links. The container is now a labelled `group` when its markers are links and an `image` when
  they are not.
- **`button-name` (critical)** — the account-menu trigger had no accessible name below `sm`, where its
  visible label is not rendered and its avatar is decorative.
- **`nested-interactive` (serious)** — the registers' stacked mobile view wrapped every row in a
  `<button>`, rendered `disabled` when the register has no row action, and the listings register put
  its Withdraw/Expire/Move/Edit buttons inside one. A row with no action of its own is now a plain
  container.

---

## Responsive

The **current** build, at six widths, checking `documentElement.scrollWidth` against `clientWidth` and
naming the widest offending element when a document overflows.

| Width | Routes checked | Horizontal overflow |
| ---: | ---: | ---: |
| 375 | 29 | **0** |
| 768 | 29 | **0** |
| 1024 | 29 | **0** |
| 1280 | 29 | **0** |
| 1440 | 29 | **0** |
| 1920 | 29 | **0** |

**174 route checks, 0 overflowing.** Mutation dialogs were measured with the dialog open — the New
property form at 375/768/1280 and the lease status dialog at 375/1440 — all with **0 overflow** and
the dialog fully inside the viewport.

---

## Keyboard

Verified with real key events (`Tab`, `Shift+Tab`, `Enter`, `Space`, `Escape`, arrow keys) against the
running build.

| Surface | Result |
| --- | --- |
| **Sign in** | Completed with the keyboard alone: Tab to the fields, type, `Enter` → `/dashboard` |
| **Focus indicator** | **0 of 61** focusable elements on the shell, and 0 across nine further routes, lacked a visible ring (`.focus-meridian` / `.focus-hero` draw a 4px ring on `:focus-visible`) |
| **Tab order** | Logical: skip-link → brand → Dashboard → Properties → Units → … matching the visible sidebar |
| **No keyboard trap** | 102 consecutive `Tab` presses on a register: focus moved on **every** press (longest run on one stop = 1), traversed the stops in order, and returned to the first — cyclic, never stuck |
| **Dialog** | Opened with `Enter`; focus moved inside; **0 escapes over 25 `Tab` presses** (the trap holds); `Escape` closed it and returned focus to the button that opened it |
| **Space** | Activates buttons (opens the New property dialog) |
| **Escape** | Closes dialogs, the mobile drawer and the account menu |
| **Select** | Operable with the arrow keys (`ArrowDown` moved the status filter from *Any* to *DRAFT*) |
| **Disabled controls** | Pagination's Previous/Next are `disabled` and correctly **not** in the tab order |
| **Register rows** | Reachable by `Tab` and opened with `Enter` (see the fix below) |
| **Mobile drawer** | Opened by `Enter`; focus moves into it; **0 escapes over 30 `Tab` presses**; `Escape` closes it and returns focus to the opener |
| **Account menu** | Opens with `Enter`; `Tab` reaches `role="menuitem"`; `Escape` closes and returns focus to the trigger |

Two genuine gaps were found and fixed:

- **Register rows were mouse-only on desktop.** `DataTable`'s desktop `<tr>` had an `onClick` and no
  keyboard equivalent — while the *same* table's stacked mobile view already rendered each row as a
  real `<button>`. The same action was therefore reachable by keyboard on a phone and unreachable on a
  desktop for every register whose columns render no link of their own. Rows are now focusable and
  answer `Enter` and `Space`, with `role` left as `row` so the table semantics survive and a key press
  on a control *inside* the row is not double-handled.
- **The drawer and the account menu could be opened from the keyboard and not left.** They had no
  `Escape` handling and no focus management: the opener kept focus, now behind the drawer's scrim, and
  tabbing went into the page underneath it. Both now move focus in on open, close on `Escape`, and
  hand focus back to the control that opened them; the drawer — which covers the page — also keeps
  `Tab` inside itself.

The registers' sideways scroll was also made reachable: a region that scrolls horizontally must be
focusable, or a keyboard user cannot scroll it.

---

## Reduced motion

`prefers-reduced-motion: reduce` is emulated, then the build is measured.

| Signal | No preference | Reduce |
| --- | --- | --- |
| Longest transition duration | 0.18 s | **0.000001 s** |
| Longest animation duration | 0 s | **0.000001 s** |
| Running animations (`document.getAnimations()`) | 0 | **0** |

- **Layout stable**: `scrollWidth` identical at load and after settling.
- **Drawer** works (opens, focus inside), **dialog** works (opens, focus inside), both close on
  `Escape`.
- **Charts render and are usable**: 43 `<svg>` elements on the dashboard, 30 on the register, in both
  modes.
- **Navigation usable**: routes load their real data with reduced motion on.

Two layers deliver this. A global `@media (prefers-reduced-motion: reduce)` rule in `styles.css`
neutralises animation and transition durations everywhere. That rule cannot reach an inline SVG
transform, so the charts — the only components that animate through JavaScript — gate on
`useReducedMotion` themselves and skip their animation entirely.

---

## Import workflow

Executed end to end against the running build with a controlled throwaway dataset. No seed business
data was altered and nothing was fabricated.

**Valid and partly-invalid file** (2 valid rows, 1 row naming a property type that does not exist):

```
GET  /imports                                  200
POST /imports/preview                          200   job staged, 3 rows, 3 valid
POST /imports/{jobCode}/confirm                200   inserted=2 skipped=0 failed=1
```

The failure is named, not generic: row 4 → *"property type 'NOT_A_REAL_TYPE' does not exist"*.
Persisted and read back from the database: `ME-P-00022` (Verification Tower Alpha, Mumbai, DRAFT,
14,500,000) and `ME-P-00023` (Verification Villa Beta, Pune, LISTED, 26,000,000); property count
14 → 16. The import row is `COMMITTED`, 3 rows, 3 valid, 0 invalid, 2 inserted, 0 skipped.

**Malformed rows** — refused at the *preview* step, before anything is staged as writable:

```
3 rows, 1 valid, 2 refused
  row 2: name is required
  row 3: acquisitionValue 'not-a-number' is not a number
```

Committing that job wrote **only** the one valid row (`ME-P-00024`; count 16 → 17) and left the two
refused rows behind, which is the documented behaviour.

**Idempotency** — the same key replays, a different key is refused:

| Call | Result |
| --- | --- |
| Confirm with the **same** key | `idempotentReplay=true`, the first run's result returned, property count **unchanged at 16** |
| Confirm with a **different** key | `409 IMPORT_IDEMPOTENCY_CONFLICT`, nothing written |

**Audit** — the commit left its own trail:

```
IMPORTS     | IMPORT_COMMITTED | Aditya Raghunathan | ME-IMP-0744400C | Committed import …: 2 inserted, 0 skipped, 1 failed
PROPERTIES  | CREATE           | Aditya Raghunathan | ME-P-00022
PROPERTIES  | CREATE           | Aditya Raghunathan | ME-P-00023
```

**Authorization** — the boundary holds:

| Caller | `GET /imports` | `POST /imports/preview` (real multipart) |
| --- | --- | --- |
| `OWNER` | 200 | 200 |
| `VIEWER` (26 permissions, no `DATA_IMPORT`) | **403** | **403** |
| `LEASING_AGENT` | **403** | — |

**UI refresh** — the register shows the imported records afterwards: 17 properties, and filtering for
"Verification" returns exactly the three imported rows with their minted codes.

---

## Mutation verification

A representative mutation was exercised through the real product rather than only asserted:
`POST /properties/{id}/status` and the register's create path were driven from the UI, and the change
was read back from PostgreSQL, with its audit entry naming the actor and carrying the before/after
values. The import commit above is itself a mutation that writes several records through the domain
services, and it was verified to the same standard: request → response → database → audit → UI.

---

## Isolated verification infrastructure

Verification runs against infrastructure this product owns, so nothing else on the machine is
disturbed:

- **Its own database and schema** — a private PostgreSQL cluster on a non-default port, created for
  the run, with the product's own role and database. No other installation's database is read or
  written, and the machine's shared instance is left alone.
- **Its own ports** — API `8083`, frontend preview `4175`.
- **`backend/.env`** — gitignored, carrying local development values only. The committed template is
  `.env.example`, which holds placeholders.

A note on a Windows quirk met during setup: `pg_ctl` needed the cluster started directly
(`postgres.exe -D …`) with `autovacuum` off, because the shared-memory reservation for child
processes failed intermittently under this shell. That is an environment detail, not a product one —
the application itself needed no accommodation.

---

## What is not verified here

- **Load, soak and concurrency testing.** Not run. See the in-memory list limitation in
  [ARCHITECTURE.md](./ARCHITECTURE.md) for where scale would bite first.
- **Cross-browser testing.** Verification was done in one Chromium-based engine. **NOT VERIFIED** in
  Firefox or WebKit.
- **Screen-reader testing with a real assistive technology.** The checks above verify names, roles,
  focus order, focus visibility, traps and escape behaviour programmatically; they are not a substitute
  for a session with NVDA or VoiceOver.
- **Automated tests for the accessibility fixes above.** The fixes were verified against the running
  build with axe and real key events. There is no `jsdom` test for the row keyboard handler or the
  drawer's focus management at the time of writing; adding one would be the next step, and the honest
  position is that the coverage is manual rather than automated.
