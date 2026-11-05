# API

A JSON HTTP API. Every response — success or failure — is wrapped in one envelope, so a client has
exactly one place to unwrap a payload and one place to read a failure.

---

## Base

```
http://<host>:8083/api/v1
```

The frontend reads the base from `VITE_API_BASE_URL`. All paths below are relative to `/api/v1`.

Content type is `application/json` everywhere except the import upload, which is `multipart/form-data`.
No endpoint ever returns an entity: every payload is a `…Summary` or `…Detail` record from
`com.meridian.realestate.dto`, so a client never sees a lazy association or a housekeeping column it
was not promised.

---

## Authentication

Stateless. A bearer access token is the only credential a request carries. No cookies, no session.

```http
Authorization: Bearer <accessToken>
```

Only four endpoints are reachable without a token:

| Endpoint | Notes |
| --- | --- |
| `POST /auth/login` | |
| `POST /auth/refresh` | rotates the refresh token |
| `POST /auth/logout` | revokes the refresh token presented in its body |
| `GET /health/readiness` | readiness probe |

Everything else — including the rest of `/auth` — requires a token.

Sign-out is public because the **refresh token** is the credential being revoked: the service resolves
it by hash and audits against its own user, so it needs no access token. It is also a deliberate
design choice that it answers `200` for a token it does not recognise — the response cannot be used to
test whether a token is real.

### `POST /auth/login`

```json
{ "email": "owner@meridianestates.example", "password": "…" }
```

Returns `AuthTokens`: `accessToken`, `refreshToken`, `tokenType`, `expiresInSeconds`.

### `POST /auth/refresh`

```json
{ "refreshToken": "…" }
```

Returns a **new** `AuthTokens`. The refresh token is **rotated on every use**: a token that has been
used, revoked or expired is refused with a named code (`REFRESH_TOKEN_REVOKED`,
`REFRESH_TOKEN_EXPIRED`). A client must therefore persist the replacement it receives.

### Other identity endpoints

`POST /auth/logout`, `POST /auth/logout-all`, `GET /auth/me`, `POST /auth/change-password`.

### What the API deliberately does not reveal

An invalid, tampered or expired token is **ignored rather than thrown**, so the request simply
continues unauthenticated and the entry point answers `401 UNAUTHORIZED`. "No token" and "bad token"
therefore return the same shape — the API never tells a caller which of the two it was. Login
likewise checks the password before the account status, so a refusal never reveals whether an address
exists.

---

## Response envelope

### Success

```json
{
  "success": true,
  "data": { },
  "message": null
}
```

`data` is the payload; `message` is a short human sentence on the operations that warrant one
(`"Property added"`, `"Import committed"`), otherwise `null`. A payload can legitimately be `null`
(e.g. after a delete).

### Failure

```json
{
  "success": false,
  "error": {
    "code": "UNIT_ALREADY_LET",
    "message": "This unit already has a running lease and cannot be let twice",
    "details": []
  }
}
```

- **`code`** is a stable machine identifier — the thing a client branches on. It is never a sentence.
- **`message`** is the sentence a person reads, produced by `ErrorMessages` from the code. A code with
  no wording falls back to a neutral sentence rather than surfacing raw capitals, so an unmapped code
  can never reach a screen as `SOME_UNMAPPED_CODE`.
- **`details`** carries per-field messages for a validation failure, so a form can place
  "A unit with this code already exists" beside the field rather than showing a generic refusal. It is
  never used to leak SQL, stack traces or class names.

`details` is always present (an empty array when there is nothing to add).

---

## Pagination, filtering and sorting

### Pagination

Paged endpoints return:

```json
{
  "items": [],
  "page": 0,
  "size": 20,
  "totalElements": 0,
  "totalPages": 0,
  "hasNext": false
}
```

Spring Data's `Page` is deliberately **not** returned directly: its JSON form is not a stable contract
(it carries extra fields whose names have moved between versions). These six values are the contract.

Request parameters: `page` (zero-based; null or negative means the first page), `size` (null falls
back to 20; **capped at 100**), `sort`.

### Sorting

`sort=field` or `sort=field,asc|desc`. Each endpoint declares the fields it accepts through
`PageRequestFactory`:

- an unrecognised field is answered with **`400`** naming the accepted fields, **not a 500**. Handing
  the raw parameter to Spring Data would make an unrecognised field a `PropertyReferenceException`
  and therefore a server error, reporting an expected client mistake as a defect;
- the API field name and the entity property may differ on purpose (a register sorts by `owner` while
  the persistence model sorts by `owner.displayName`);
- the whitelist also closes a probing surface: the parameter names an entity *property*, so granting
  arbitrary values would let a caller walk the object graph.

### Filtering

Filters are explicit request parameters per endpoint (`status`, `city`, `propertyTypeId`, `ownerId`,
`agentId`, `search`, date windows, …). An absent parameter means "no filter" — a register is read
unfiltered by default, so a missing status is not treated as a client error.

> **Known deliberate limitation.** Several register endpoints apply their filters, their sort and the
> page slice **in memory** after reading the candidate rows, because the repository layer does not
> offer one combined query covering every filter (see [ARCHITECTURE.md](./ARCHITECTURE.md)). The
> visible behaviour is correct — the page asked for is the page returned, with correct totals — but
> the cost grows with the size of the table rather than the size of the page. This has not been
> redesigned. Where it matters, prefer the filters that map to a repository query.

---

## Representative endpoints

### Auth and platform

| Method | Path | Permission |
| --- | --- | --- |
| `POST` | `/auth/login` | — (public) |
| `POST` | `/auth/refresh` | — (public) |
| `POST` | `/auth/logout` | authenticated |
| `GET` | `/auth/me` | authenticated |
| `GET` | `/platform/profile` | authenticated |
| `GET` | `/health/readiness` | — (public) |

### Properties

| Method | Path | Permission |
| --- | --- | --- |
| `GET` | `/properties` | `PROPERTY_VIEW` |
| `GET` | `/properties/{id}` | `PROPERTY_VIEW` |
| `GET` | `/properties/{id}/metrics` | `PROPERTY_VIEW` |
| `GET` | `/properties/{id}/buildings` | `BUILDING_VIEW` |
| `GET` | `/properties/{id}/units` | `UNIT_VIEW` |
| `GET` | `/properties/{id}/amenities` | `PROPERTY_VIEW` |
| `POST` | `/properties/{id}/amenities` | `PROPERTY_UPDATE` |
| `DELETE` | `/properties/{id}/amenities/{amenityId}` | `PROPERTY_UPDATE` |
| `GET` | `/properties/{id}/ownership` | `OWNER_VIEW` |
| `GET` | `/properties/{id}/documents` | `DOCUMENT_VIEW` |
| `GET` | `/properties/{id}/status-history` | `PROPERTY_VIEW` |
| `GET` | `/properties/map` | `PROPERTY_VIEW` |
| `POST` | `/properties` | `PROPERTY_CREATE` |
| `PUT` | `/properties/{id}` | `PROPERTY_UPDATE` |
| `POST` | `/properties/{id}/archive` | `PROPERTY_DELETE` |
| `POST` | `/properties/{id}/status` | `PROPERTY_STATUS_CHANGE` |

`GET /properties` accepts `status`, `propertyTypeId`, `city`, `ownerId`, `agentId`, `search`, `page`,
`size`, `sort` and sorts by `createdAt`, `name`, `propertyCode`, `city`, `status`, `currentValuation`.

Two contract notes that matter to a client:

- **`PropertySummary` carries no unit count, occupancy or rent roll.** Those live on
  `GET /properties/{id}/metrics` (`PropertyMetrics`), which is read one property at a time. There is
  no endpoint that returns them beside a whole page of rows.
- **`PropertySummary` carries no coordinates.** It carries a boolean `positioned`. Only
  `GET /properties/map` returns markers with real latitudes and longitudes, and only positioned
  properties appear there — the service never invents a position.

### Leases

| Method | Path | Permission |
| --- | --- | --- |
| `GET` | `/leases` | `LEASE_VIEW` |
| `GET` | `/leases/{id}` | `LEASE_VIEW` |
| `POST` | `/leases` | `LEASE_CREATE` |
| `PUT` | `/leases/{id}` | `LEASE_UPDATE` |
| `POST` | `/leases/{id}/status` | `LEASE_ACTIVATE` |
| `POST` | `/leases/{id}/terminate` | `LEASE_TERMINATE` |
| `GET` | `/leases/expiring` | `LEASE_VIEW` |

`LeaseSummary` (the register row) carries **no `unitCode` and no `daysRemaining`** — the row names its
unit by id only, and a countdown is a reading a client takes from `endDate`. The projection that does
carry `daysUntilEnd` is `ExpiringLease`, from `/leases/expiring`.

### Other modules

| Module | Base path |
| --- | --- |
| Units | `/units` |
| Buildings | `/buildings` |
| Owners | `/owners` |
| Ownership | `/properties/{propertyId}/ownerships` |
| Tenants | `/tenants` |
| Buyers | `/buyers` |
| Offices | `/offices` |
| Agents | `/agents` |
| Leads | `/leads` |
| Listings | `/listings` |
| Viewings | `/viewings` |
| Applications | `/applications` |
| Rent invoices | `/rent-invoices` |
| Payments | `/payments` |
| Maintenance | `/maintenance-requests` |
| Vendors | `/vendors` |
| Expenses | `/expenses` |
| Expense categories | `/expense-categories` |
| Sales and offers | `/sales`, `/offers` |
| Documents | `/documents` |
| Portfolios | `/portfolios` |
| Communications | `/communications` |
| Notifications | `/notifications` |
| Audit | `/audit` |
| Dashboard | `/dashboard` |
| Reports | `/reports` |
| Reference data | `/property-types`, `/unit-types`, `/amenities` |
| Imports | `/imports` |

### Reports and dashboard

| Method | Path | Permission |
| --- | --- | --- |
| `GET` | `/dashboard/summary` | authenticated |
| `GET` | `/reports/…` | `REPORT_VIEW` |

The dashboard is **one** projection for one as-of date — portfolio standing, unit states, a trend
series, the property rankings, and the lead, listing, viewing, application, sales, lease and
maintenance counts — rather than a dozen calls the screen has to join.

---

## Errors

| Status | When | Typical codes |
| --- | --- | --- |
| `400` | Body validation failed; malformed body; bad path parameter; missing parameter; unsupported sort field; invalid content type on a part | `VALIDATION_FAILED`, `MALFORMED_BODY`, `INVALID_PATH_PARAMETER`, `MISSING_PARAMETER` |
| `401` | No token, bad token, expired session, bad credentials | `UNAUTHORIZED`, `INVALID_CREDENTIALS`, `REFRESH_TOKEN_EXPIRED`, `REFRESH_TOKEN_REVOKED` |
| `403` | Authenticated but not permitted, or outside the caller's scope | `FORBIDDEN`, `ACCOUNT_INACTIVE` |
| `404` | The record does not exist, or the address does not exist | `NOT_FOUND`, `PROPERTY_NOT_FOUND`, `LEASE_NOT_FOUND`, … |
| `405` | Method not supported on that address | `METHOD_NOT_ALLOWED` |
| `409` | A named conflict | `DUPLICATE_*`, `*_TRANSITION_NOT_ALLOWED`, `UNIT_ALREADY_LET`, `CONSTRAINT_VIOLATION` |
| `413` | Upload larger than the container accepts | `IMPORT_FILE_TOO_LARGE` |
| `415` | Unsupported content type | `UNSUPPORTED_CONTENT_TYPE` |
| `500` | An unhandled failure. The raw reason is logged, never returned; the body carries `INTERNAL_ERROR` and the neutral fallback sentence. | `INTERNAL_ERROR` |

The `500` case is the only place the API says nothing specific, and that is deliberate: an internal
identifier or a stack trace reaching a screen is worse than a plain apology.

---

## Import

Insert-only, and two steps with no shortcut.

| Method | Path | Permission | Purpose |
| --- | --- | --- | --- |
| `GET` | `/imports/supported` | `DATA_IMPORT` | the entity types this installation imports, with their accepted and required columns |
| `POST` | `/imports/preview` | `DATA_IMPORT` | stage a file: parse and validate, write nothing |
| `GET` | `/imports` | `DATA_IMPORT` | history, newest first |
| `GET` | `/imports/{jobCode}` | `DATA_IMPORT` | one staged or committed job with its rows |
| `POST` | `/imports/{jobCode}/confirm` | `DATA_IMPORT` | the only thing that writes |

### `POST /imports/preview`

`multipart/form-data` with `entityType` as a request parameter and `file` as a part.

- The file is read into memory and parsed there; it is **never written to disk**, so there is no
  temporary file to clean up and no path to traverse.
- Its format is decided from **its own bytes**, not from the extension or the browser's MIME type.
- Returns `PreviewResult`: the job summary, `canCommit` (**computed server-side**, so a screen never
  works it out for itself), the column names, the accepted rows and the problem rows with their
  per-field reasons.

### `POST /imports/{jobCode}/confirm`

```json
{ "idempotencyKey": "…" }
```

- The same key returns the **first run's result** and writes nothing, so a double-clicked or retried
  confirm cannot duplicate data. `idempotentReplay` in the response says which happened.
- A **different** key against an already-committed job is refused with
  `IMPORT_IDEMPOTENCY_CONFLICT` rather than silently ignored.
- Returns `ConfirmResult`: the job, `inserted`, `skipped`, `failed` and the per-row outcomes.

There is no endpoint that edits or deletes an imported record, and none that edits a committed job.
A correction is another import, and a committed import is a historical record of what was staged and
what it produced.

---

## Client conventions

- The frontend's `apiClient` returns the unwrapped `data` and nothing else; it never hands an envelope
  to a page.
- On `401` the client attempts a single token refresh and replays the request once. Concurrent `401`s
  share one refresh rather than each firing their own.
- `lib/errors.ts` turns a failure into an `ApiError` carrying `code`, `message` and `details`; pages
  branch on `code`, never on the message text, and render `message` verbatim.
