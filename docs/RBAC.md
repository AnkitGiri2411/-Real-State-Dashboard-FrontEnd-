# RBAC — roles, permissions and scope

Two separate questions, answered in two different places. Confusing them is the most common way an
authorization model goes wrong, so the product keeps them apart on purpose.

| Question | Answered by | Source of truth |
| --- | --- | --- |
| **What operation** may this caller perform? | `@PreAuthorize("hasAuthority('CODE')")` on the endpoint | permission codes carried on the access token |
| **Whose records** may this caller see? | `AccessScope` | the account, **reloaded from the database on every call** |

**Permissions are the actual control.** Roles are only convenient bundles of them. **No endpoint ever
checks a role name to decide a capability** — renaming or adding a role can never widen access by
itself.

---

## The eight roles

| Role | Scope | Permissions | Purpose |
| --- | --- | ---: | --- |
| `OWNER` | firm-wide | 99 | The firm's owner. Everything; cannot be locked out or have permissions reduced. |
| `ADMIN` | firm-wide | 99 | Firm administration: staff, records, settings. |
| `PROPERTY_MANAGER` | firm-wide | 97 | The whole operation, the rent roll and the numbers. |
| `LEASING_AGENT` | **own book** | 46 | Letting: leads, listings, viewings, applicants, tenancies, leases. |
| `SALES_AGENT` | **own book** | 33 | Sales: buyers, the offer pipeline, the listings that carry them. |
| `ACCOUNTING` | firm-wide | 26 | The rent roll, receipts, voids and refunds, expenses, read-only audit. |
| `VIEWER` | firm-wide | 26 | Read-only across the portfolio. A board member, an auditor, a client. |
| `MAINTENANCE` | **own desk** | 20 | The stock's condition: faults, their assignment, resolution and cost. |

Counts above are the **seeded** grants, read from `role_permissions` in a freshly migrated database.
There are **99 permissions across 27 modules**; "firm-wide" and "own book" are scope, explained below.

### Why `OWNER` and `ADMIN` are both full

They hold every permission, and they differ in what can be done **to** them rather than what they can
do: **the last active `OWNER` cannot be deactivated, cannot have the role removed, and cannot have its
permissions reduced** (`CANNOT_DEACTIVATE_LAST_OWNER`, `CANNOT_REMOVE_LAST_OWNER`). That is what makes
"cannot be locked out" true rather than aspirational.

### Why `PROPERTY_MANAGER` is one short of full

It holds everything **except** `ROLE_UPDATE` and `USER_DEACTIVATE`. Changing what a role may do, and
deactivating a colleague, are the owner's and the administrator's calls.

### Why `VIEWER` is built from the permission table, not listed by hand

```sql
WHERE r.name = 'VIEWER' AND (p.code LIKE '%\_VIEW' ESCAPE '\' OR p.code = 'NOTIFICATION_VIEW')
```

A permission added for a new module is granted to the read-only role automatically, and **a write
cannot be granted here by a typo**. The `V2` seed guard then asserts the outcome:

```sql
SELECT count(*) FROM role_permissions rp
  JOIN roles r ON r.id = rp.role_id
  JOIN permissions p ON p.id = rp.permission_id
 WHERE r.name = 'VIEWER' AND p.code NOT LIKE '%\_VIEW' ESCAPE '\';
-- must be zero, or the migration fails
```

Verified in the seeded database: **the `VIEWER` role holds 0 non-`_VIEW` permissions.**

---

## Permissions

99 codes across 27 modules, named `<MODULE>_<ACTION>`. They are inserted by `V2` and checked by
`@PreAuthorize` on the endpoint that needs them.

| Module | Codes |
| --- | --- |
| `PROPERTIES` | `PROPERTY_VIEW`, `PROPERTY_CREATE`, `PROPERTY_UPDATE`, `PROPERTY_DELETE`, `PROPERTY_STATUS_CHANGE` |
| `BUILDINGS` | `BUILDING_VIEW`, `BUILDING_CREATE`, `BUILDING_UPDATE`, `BUILDING_DELETE` |
| `UNITS` | `UNIT_VIEW`, `UNIT_CREATE`, `UNIT_UPDATE`, `UNIT_DELETE`, `UNIT_STATUS_CHANGE` |
| `OWNERS` | `OWNER_VIEW`, `OWNER_CREATE`, `OWNER_UPDATE`, `OWNER_DELETE` |
| `TENANTS` | `TENANT_VIEW`, `TENANT_CREATE`, `TENANT_UPDATE`, `TENANT_DELETE` |
| `BUYERS` | `BUYER_VIEW`, `BUYER_CREATE`, `BUYER_UPDATE`, `BUYER_DELETE` |
| `AGENTS` | `AGENT_VIEW`, `AGENT_CREATE`, `AGENT_UPDATE`, `AGENT_DEACTIVATE` |
| `LEADS` | `LEAD_VIEW`, `LEAD_CREATE`, `LEAD_UPDATE`, `LEAD_DELETE`, `LEAD_ASSIGN`, `LEAD_CONVERT` |
| `LISTINGS` | `LISTING_VIEW`, `LISTING_CREATE`, `LISTING_UPDATE`, `LISTING_DELETE`, `LISTING_PUBLISH` |
| `VIEWINGS` | `VIEWING_VIEW`, `VIEWING_CREATE`, `VIEWING_UPDATE`, `VIEWING_CANCEL` |
| `APPLICATIONS` | `APPLICATION_VIEW`, `APPLICATION_CREATE`, `APPLICATION_SCREEN`, `APPLICATION_DECIDE` |
| `LEASES` | `LEASE_VIEW`, `LEASE_CREATE`, `LEASE_UPDATE`, `LEASE_ACTIVATE`, `LEASE_RENEW`, `LEASE_TERMINATE` |
| `RENT` | `RENT_VIEW`, `RENT_GENERATE`, `RENT_UPDATE` |
| `PAYMENTS` | `PAYMENT_VIEW`, `PAYMENT_RECORD`, `PAYMENT_VOID`, `PAYMENT_REFUND` |
| `MAINTENANCE` | `MAINTENANCE_VIEW`, `MAINTENANCE_CREATE`, `MAINTENANCE_ASSIGN`, `MAINTENANCE_UPDATE`, `MAINTENANCE_RESOLVE` |
| `EXPENSES` | `EXPENSE_VIEW`, `EXPENSE_CREATE`, `EXPENSE_UPDATE`, `EXPENSE_DELETE` |
| `SALES` | `SALE_VIEW`, `SALE_CREATE`, `SALE_UPDATE`, `SALE_OFFER`, `SALE_CLOSE` |
| `DOCUMENTS` | `DOCUMENT_VIEW`, `DOCUMENT_CREATE`, `DOCUMENT_DELETE` |
| `PORTFOLIOS` | `PORTFOLIO_VIEW`, `PORTFOLIO_CREATE`, `PORTFOLIO_UPDATE`, `PORTFOLIO_DELETE` |
| `COMMUNICATION` | `CONVERSATION_VIEW`, `CONVERSATION_CREATE`, `CONVERSATION_REPLY`, `CONVERSATION_CLOSE` |
| `NOTIFICATIONS` | `NOTIFICATION_VIEW` |
| `REPORTS` | `REPORT_VIEW` |
| `DATA` | `DATA_IMPORT` |
| `AUDIT` | `AUDIT_VIEW` |
| `STAFF` | `USER_VIEW`, `USER_CREATE`, `USER_UPDATE`, `USER_DEACTIVATE` |
| `ROLES` | `ROLE_VIEW`, `ROLE_UPDATE` |
| `SETTINGS` | `SETTINGS_VIEW`, `SETTINGS_UPDATE` |

Note the deliberate absences. There is no `*_DELETE` for applications, leases, rent or payments:
a lease is terminated, an invoice is voided or written off, a receipt is voided or refunded — each a
named operation with its own audit trail, rather than a delete. There is no `DOCUMENT_UPDATE`, because
a document is superseded by a later version rather than edited in place.

---

## Scope behaviour

Scope is **not** permission. A scoped agent holds no fewer capabilities for the work they actually do
— they are simply shown **their own records** rather than the whole firm's.

### The rule

The firm does not hide its own portfolio from its own staff. **Properties, units, owners, tenants,
buyers, documents, portfolios and the ledger are protected by permission alone.** What is scoped is
the **personally carried work**: the leads, listings, viewings and sales one agent is working, and the
maintenance on one desk's stock.

That distinction is stated once, in `AccessScope`, rather than in each service.

### Firm-wide roles

```java
Set.of("OWNER", "ADMIN", "PROPERTY_MANAGER", "ACCOUNTING", "VIEWER")
```

Each is firm-wide for a reason specific to it:

- `OWNER` and `ADMIN` own the firm's records outright;
- `PROPERTY_MANAGER` runs the whole operation and the numbers;
- `ACCOUNTING` — the rent roll, the receipts and the audit trail are firm-wide facts, never one
  agent's private work;
- `VIEWER` is read-only **across the portfolio by definition**.

`LEASING_AGENT`, `SALES_AGENT` and `MAINTENANCE` are deliberately absent: the letting desk, the sales
desk and facilities each carry their own book of work, and that is exactly what scope restricts.

### What scoping does and does not do

Adding a permission to a scoped role **widens what they may do** but **never widens what they may
see** beyond their own desk. `LEASING_AGENT`, `SALES_AGENT` and `MAINTENANCE` stay scoped however they
are re-permissioned.

### Where it is enforced

`AccessScope` exposes:

| Method | Behaviour |
| --- | --- |
| `isCompanyWide()` | true when any of the caller's roles is in the firm-wide set |
| `requireCompanyWide(what)` | 403 unless firm-wide, naming the operation that was attempted |
| `canSeeAgentWork(agentProfileId)` | a company-wide caller sees every agent's work; a scoped caller sees only the profile tied to their own account |
| `requireAgentOrCompanyWide(agentProfileId)` | 403 unless visible |
| `requirePermission(code)` | 403 unless the caller holds the code — for a check a service must state in its own body, e.g. one that depends on a loaded record |

`@PreAuthorize` is preferred on an endpoint because it declares the requirement where the operation
is. `requirePermission` exists for the cases an annotation cannot express.

---

## The authorization model

### How a request is authorized

1. **The filter** (`JwtAuthenticationFilter`) reads the bearer token and, if it is valid, sets the
   principal (the account id) and the authorities (`ROLE_<name>` plus the raw permission codes). An
   invalid token is ignored, not thrown.
2. **The chain** requires authentication for everything except login, refresh and readiness.
3. **The endpoint** declares `@PreAuthorize("hasAuthority('CODE')")`.
4. **`AccessScope`** resolves *whose records* where scope applies, reloading the account from the
   database.
5. **The service** applies the business rules and may raise `ApiException.forbidden` for a rule that
   only matters once a record has loaded.

### Why scope is never read from the token

The token records who somebody was at the moment it was minted. `AccessScope` refuses to act on that:
the user is reloaded from the database on every call. This is what makes a deactivation, a role change
or a withdrawn permission apply to **the very next request** instead of whenever the access token
happens to expire.

Each read method opens its own read-only transaction, because `open-in-view: false` means there is no
session held for the request — a lazy permission graph reached outside a transaction would fail
rather than quietly return nothing, which is the outcome we want.

### Token contents vs. databases

| Fact | Source | Why |
| --- | --- | --- |
| Identity (account id) | token subject, then reloaded | the reload is the authority |
| Roles | token, as `ROLE_*` authorities | used for scope and for the `ROLE_*` convention only |
| Permissions | token, as raw codes | checked by `@PreAuthorize` |
| Account status | **database** | a deactivation takes effect immediately |
| Scope | **database** | derived from roles as they stand now |

A token is therefore not a grant that survives the account it was minted for.

### Refusals

| Situation | Status | Code |
| --- | --- | --- |
| No token / bad token / expired | `401` | `UNAUTHORIZED` |
| Bad credentials | `401` | `INVALID_CREDENTIALS` |
| Authenticated but lacks the permission | `403` | `FORBIDDEN` |
| Outside the caller's scope | `403` | `FORBIDDEN` |
| Account not active | `403` | `ACCOUNT_INACTIVE` |
| Refresh token used, revoked or expired | `401` | `REFRESH_TOKEN_REVOKED` / `REFRESH_TOKEN_EXPIRED` |

---

## IDOR protection

Direct object reference is where a permission-only model usually breaks: a caller holds
`PROPERTY_VIEW`, changes a UUID in the path, and reads another firm's record. In this product the
firm-wide/permission rule means an *asset* is visible to anyone who may see assets at all — so the
protection that matters is different, and it is layered:

1. **Scope on personally carried work.** A scoped caller who requests another agent's lead, listing,
   viewing, sale or maintenance record is refused by `AccessScope`, not merely filtered out of a list.
   `canSeeAgentWork` is checked against the caller's own agent profile, so a scoped caller has exactly
   one profile they can see.
2. **`404` rather than `403` where existence itself is the secret.** A record the caller may not see
   is reported as not found, so a probe cannot use the status code to confirm that a UUID exists.
3. **Every mutation is authorized on the record, not on the collection.** `PUT /properties/{id}` is
   checked against `PROPERTY_UPDATE`; the id is resolved through the service, which loads and
   validates the record before any change.
4. **Soft delete is not a bypass.** Archived records are excluded by the queries
   (`deleted_at IS NULL`), so an id that once existed does not resurrect through a stale reference.
5. **The mutation is audited against the resolved record**, so an attempt to act on a record outside
   the caller's scope either fails before the write or leaves a trail if it somehow did not.

Because the register is firm-wide by design, the meaningful IDOR question in this product is *"can a
scoped agent reach another desk's work?"* — and the answer is no, enforced centrally in `AccessScope`
rather than per endpoint.

---

## Operating notes

- **Seeded accounts.** `V2` creates 10 staff accounts, one per role, all with the published
  demonstration password `testing`. It is published on purpose and must be changed before the
  installation holds real data. See [SETUP.md](./SETUP.md).
- **Adding a permission.** Insert it in a **new** migration (never edit `V2`), grant it to the roles
  that should hold it, and add the `@PreAuthorize` on the endpoint that needs it. A `_VIEW`-suffixed
  code is granted to `VIEWER` automatically.
- **Roles are seeds, not data.** All eight are inserted with `system_role = true` and cannot be
  deleted through the API.
- **Changing a role takes effect immediately** for every holder, because authorization reads the
  database rather than the token.
