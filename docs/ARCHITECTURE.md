# Architecture

Two deployable artefacts and one database.

```
Browser
  │  React SPA (apps/web)  ── Vite dev server :5175 / static bundle :4175
  │
  │  HTTPS, JSON, Bearer access token
  ▼
Spring Boot service (backend)  ── :8083
  │  controller → service → repository → entity
  │
  ▼
PostgreSQL  ── schema `real_estate`, owned by the product
```

The frontend holds **no business rules**. It renders what the API returns, offers only the moves the
API permits, and treats every figure it displays as the backend's. Where a screen needs a rule before
a request leaves (a position is both coordinates or neither; a lease cannot end before it starts)
that rule is restated as a *courtesy check* so the reader is told beside the field — the backend
remains the authority and refuses the same request anyway.

---

## Frontend — React / Vite

A single-page application, TypeScript in strict mode, no SSR.

### Layering

| Directory | Responsibility |
| --- | --- |
| `src/pages/` | One module per screen. Composes the UI kit, owns screen state, calls `services`. |
| `src/components/ui/` | The design-system kit: `Button`, `DataTable`, `Dialog`, `Field`, `Select`, `Panel`, `Toolbar`, `Pagination`, `Skeleton`, `StatusBadge`, `EmptyState`, `ErrorState`, charts. |
| `src/components/` | Cross-cutting pieces: page header, access guard, formatters. |
| `src/layout/` | The application shell — sidebar, top bar, mobile drawer, notification bell. |
| `src/services/` | `apiClient` (fetch + envelope unwrapping + token refresh) and per-module endpoint maps (`propertyApi`, `leaseApi`, …). |
| `src/types/` | Payload types that mirror the backend DTO records **field for field**. |
| `src/auth/` | Session storage, token rotation, the Zustand auth store. |
| `src/platform/` | The product identity/navigation profile fetched from the backend. |
| `src/lib/` | Error decoding (`ApiError`, `messageOf`, `detailsOf`). |

### Routing and access

`src/routes.ts` is the route table; `routes.test.ts` asserts it. Every protected page is wrapped in
`PageAccessGuard`, which refuses a caller without the page's permission **before the page's query is
fired**. That ordering is deliberate: a route typed into the address bar must not cause a request on
the strength of the URL alone. The guard explains the refusal; the API enforces it.

### Server state

TanStack Query owns all server data. Registers are query-keyed by their filters, sort and page, and a
mutation invalidates the affected keys rather than patching the cache by hand — so a row's derived
figures always come from the same query the rest of the book does.

### Client state

Zustand holds only what the server does not: the auth session, the platform profile and UI
preferences. Nothing derived from an API payload is duplicated into client state.

### Type contracts

`src/types/index.ts` mirrors `com.meridian.realestate.dto.*` exactly. A field that is not in a DTO is
not in the types, because a plausible-looking field the API never sends is how a screen renders
blanks that look like data. Register rows use the `…Summary` projection, which is deliberately narrow;
the fuller `…Detail` projection is read one record at a time from the detail endpoint. **A figure
that exists only on the detail cannot be a column on the register.**

The `.env.example` documents the build-time fallbacks; the backend's `GET /api/v1/platform/profile`
is the runtime source of truth for product identity, terminology and the sidebar.

---

## Backend — Spring Boot

### Layers and the direction of dependency

```
controller  →  service  →  repository  →  entity
   │              │
   │              └── AccessScope, AuditRecordingService, BusinessNumberService, MoneyPolicy
   └── ApiResponse / PageResponse / ErrorMessages
```

- **`controller/`** — routing, request-shape validation, the response envelope. No business rule, no
  entity, no repository. 34 controllers under `/api/v1`.
- **`service/`** — where every rule lives. A violated rule is raised as `ApiException` with a named
  code; `GlobalExceptionHandler` turns it into the product's one error envelope. 37 services.
- **`repository/`** — Spring Data interfaces. **No repository query in this product carries a literal
  `order by`**, because a hard-coded order inside a paged query silently overrides what the caller
  asked for. 62 repositories.
- **`domain/`** — JPA entities and enums. 64 entities.
- **`dto/`** — records that are the API contract, plus `ErrorMessages`. 36 files. An entity is
  **never** returned from an endpoint: it carries lazy associations and housekeeping columns, and a
  caller could not tell which of its fields are a promise.

### Persistence

PostgreSQL is authoritative. Hibernate runs with `ddl-auto: validate` and `open-in-view: false`:

- a drift between the entity model and the migrations **fails startup** rather than being silently
  rewritten;
- no session is held open for the request duration, so a lazy read outside a transaction fails loudly
  instead of quietly returning nothing.

Schema changes are Flyway migrations only. See [DATABASE.md](./DATABASE.md).

### Authentication

Stateless. No HTTP session, no CSRF token; a bearer access token is the only credential a request
carries.

- **Access token** — short-lived (default 900s), signed, carries identity, role names and permission
  codes.
- **Refresh token** — long-lived (default 30 days), stored server-side and **rotated on every use**.
  A refresh token that has been used, revoked or expired is refused with a named code.

Only four endpoints are reachable without a token: `POST /api/v1/auth/login`,
`POST /api/v1/auth/refresh`, `POST /api/v1/auth/logout` and `GET /api/v1/health/readiness`.
Everything else must present one.

Sign-out is public for the same reason refresh is: it carries the **refresh token** in its body, and
that token is the credential being revoked. The service resolves it by hash, revokes it and audits
against its own user, never reading the caller's identity from a token header. Requiring an access
token as well bought nothing — anyone holding the refresh token can already do far more with
`/auth/refresh` — and it broke the client, which clears its local session *before* asking the server
to revoke: the request went out with no `Authorization` header, was answered `401`, and left the
refresh token live for the rest of its thirty days.

Two details are deliberate:

- **An invalid, tampered or expired token is ignored rather than thrown**, so the request simply
  continues unauthenticated and the entry point answers `401 UNAUTHORIZED`. "No token" and "bad token"
  therefore return the same shape — the API never tells a caller which of the two it was.
- **Account status is not enforced in the user-details lookup.** Disabling the account there would
  make the provider reject it *before* the password is checked, which reveals that the address
  exists. `AuthService` checks the password first and the status immediately after, so a deactivated
  member of staff gets an honest "your account is not active" while someone who does not know the
  password learns nothing.

Passwords are stored as BCrypt hashes, never reversibly.

### RBAC

Two separate questions, answered in two places:

| Question | Answered by | Source |
| --- | --- | --- |
| **What operation** may this caller perform? | `@PreAuthorize("hasAuthority('CODE')")` on the endpoint | permission codes on the access token |
| **Whose records** may this caller see? | `AccessScope` | the account, **reloaded from the database on every call** |

`AccessScope` never trusts the token for scope. That reload is the entire point: a deactivation, a
role change or a withdrawn permission applies to the very next request rather than whenever the token
happens to expire. No endpoint ever checks a role name for a *capability*; the eight roles are
convenient bundles of permissions.

Scope applies to **personally carried work** — leads, listings, viewings, sales and the maintenance on
a desk's stock. The firm does not hide its own portfolio from its own staff, so properties, units,
owners and tenants are protected by permission alone.

See [RBAC.md](./RBAC.md) for the full matrix.

### Audit

Every mutation funnels through `AuditRecordingService`, which records the actor, the module and
action, a severity, the entity type and id, a human label, the previous and new values, request
context (IP, user agent) and a timestamp. The audit trail is read through `AuditController` behind
`AUDIT_VIEW`. Status transitions additionally keep their own per-module history tables (property,
unit, lease, listing, viewing, application, sale, maintenance), so a record's own timeline is
queryable without walking the global trail.

### Errors

One envelope, one vocabulary. Services raise `ApiException` with a **code**; `ErrorMessages` is the
single place a code becomes a sentence a person can act on; `GlobalExceptionHandler` assembles the
body. An unknown code falls back to a neutral sentence rather than surfacing raw capitals, so a code
added in a service layer can never reach a screen as `SOME_UNMAPPED_CODE`.

| Status | Meaning |
| --- | --- |
| `400` | Validation failed, malformed body, bad path parameter, unsupported sort field |
| `401` | No token, bad token, expired session, bad credentials |
| `403` | Authenticated but not permitted, or outside the caller's scope |
| `404` | The record does not exist (or is not visible to this caller) |
| `405` / `415` | Method or content type not supported on that address |
| `409` | A named conflict (duplicate code, transition not allowed, already decided) |
| `500` | Internal error — the raw reason is never returned to the caller |

---

## Domain modules

| Module | Entity groups | Controllers |
| --- | --- | --- |
| **Identity** | `User`, `Role`, `Permission`, `UserRole`, `RolePermission`, `RefreshToken`, `AuditLog`, `BusinessNumberSequence` | Auth, Audit |
| **Platform** | — (configuration) | Platform, Health |
| **Assets** | `Property`, `PropertyType`, `Building`, `Amenity`, `PropertyAmenity`, `PropertyMedia`, `PropertyStatusHistory` | Property, ReferenceData, Amenity, Building, PropertyMedia |
| **Stock** | `Unit`, `UnitType`, `UnitStatusHistory` | Unit |
| **Ownership** | `Owner`, `PropertyOwnership` | Owner, PropertyOwnership |
| **Parties** | `Tenant`, `Buyer`, `BuyerRequirement` | Tenant, Buyer |
| **Agency** | `Office`, `AgentProfile`, `AgentPropertyAssignment` | Office, Agent |
| **Letting** | `Lead`, `LeadSource`, `LeadActivity`, `LeadAssignment`, `LeadFollowup`, `Listing`, `ListingChannel`, `ListingStatusHistory`, `Viewing`, `ViewingStatusHistory`, `RentalApplication`, `ApplicationScreening`, `ApplicationStatusHistory`, `Lease`, `LeaseTerm`, `LeaseStatusHistory`, `LeaseRenewal` | Lead, Listing, Viewing, Application, Lease |
| **Ledger** | `RentInvoice`, `RentInvoiceLine`, `Payment`, `PaymentAllocation`, `PaymentMethod` | RentInvoice, Payment |
| **Operations** | `MaintenanceRequest`, `MaintenanceStatusHistory`, `Vendor`, `PropertyExpense`, `ExpenseCategory` | Maintenance, Vendor, Expense, ExpenseCategory |
| **Capital markets** | `SalesOpportunity`, `Offer`, `SaleStageHistory` | Sale |
| **Records** | `Document`, `Portfolio`, `PortfolioProperty` | Document, Portfolio |
| **Interaction** | `Conversation`, `ConversationMessage`, `Notification` | Communication, Notification |
| **Reporting** | — (projections) | Dashboard, Report |
| **Data** | `ImportJob`, `ImportJobRow` | Import |

Cross-module rules never leak into a controller. A lease refuses a second running lease on one unit
inside `LeaseService` with the named `UNIT_ALREADY_LET`, rather than letting the database's unique
index surface as an opaque error.

---

## Known deliberate limitations

These are design decisions, not defects. They are documented so that a reviewer does not mistake them
for omissions.

### 1. Register lists filter, sort and slice in memory

Several register services (`PropertyService.list` is the clearest example) read their candidate rows
and then apply the filters, the sort and the page slice in memory.

The reason is specific: the repository layer offers search and a plain paged read but does not offer
one combined query covering the register's filters. The product forbids a literal `order by` in a
repository query, so the alternative would have been a query that silently overrides the caller's sort.

The consequence is that a register's cost grows with the size of the **table**, not the size of the
page. The visible behaviour is nevertheless correct — the page the caller asked for is the page they
get, with correct totals.

This is **not** a correctness or security problem, and it has not been redesigned. A future change
would add the combined repository queries and push filtering, sorting and slicing into SQL.

### 2. `packages/api-types` is reserved and empty

The workspace has a `packages/api-types` directory intended for a shared generated type package. It
is currently empty; the frontend types are hand-maintained in `apps/web/src/types/index.ts` and are
verified against the DTO records (see [TESTING.md](./TESTING.md)).

### 3. No in-memory demo profile

There is deliberately no `dev`-only H2 profile. The demonstration portfolio is seeded by the Flyway
chain into the real schema, so there is exactly one schema authority and one source of demo truth.
