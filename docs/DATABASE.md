# Database

PostgreSQL, in one schema owned by the application role. **Flyway is the only schema authority.**
There is no manual DDL anywhere in this product, and Hibernate runs with `ddl-auto: validate`, so a
drift between the migrations and the entity model **fails startup** rather than being silently
rewritten.

- Database: `real_estate`
- Schema: `real_estate` (created by Flyway on first start)
- Role: `real_estate`, which owns the database and therefore the schema it creates

---

## Where the chain is applied

The migration chain below is the **sole** schema authority in both of this product's data sources. The
only thing that differs between them is which PostgreSQL receives it:

| `APP_DATA_SOURCE` | The database | Used for |
| --- | --- | --- |
| `postgres` (default) | The `DB_*` server, or `DATABASE_URL`. | Every deployment, and local development against a real database. |
| `sqlfiles` | A PostgreSQL started on this machine at boot, in a temporary directory, discarded at shutdown. | Running the product from these migrations alone, with no database to install. |

`sqlfiles` mode is not a cheaper substitute for PostgreSQL and does not re-implement the seed. It starts
a real PostgreSQL, pinned to the version `render.yaml` provisions, and points Flyway at it — because the
chain is written in PL/pgSQL (`DO $$` blocks with loops and `RAISE EXCEPTION` guards, `gen_random_uuid()`,
`LATERAL`, `generate_series`, partial indexes, `jsonb`, `ON CONFLICT`) and no other engine can run it.
Porting the seed would produce *reconstructed* data rather than the same data, which is the one thing
this mode exists to avoid.

Two consequences are worth stating plainly:

- **Everything written is lost when the process stops.** The database exists for the life of the run,
  and the schema history goes with it, so the next start applies `V1`–`V23` from empty again.
- **The dates are always current.** `V23` anchors the ledger to `CURRENT_DATE`, so a fresh start is
  always a live portfolio — where a long-lived installation's copy ages (see
  [Date-anchored seed behaviour](#date-anchored-seed-behaviour)).

`sqlfiles` is refused unless `APP_ENV` is `development`, `local` or `test`, so a deployment cannot come
up on a database it would silently discard. See `SqlFileDataSourcePostProcessor`, and
[SETUP.md](./SETUP.md#running-it-from-the-sql-files) for how to run it.

---

## Schema conventions

Applied to every table in the product, stated in `V1`'s header:

1. **UUID primary keys.** An id can be minted without a round trip, and it never leaks how many
   records exist or in what order they were created.
2. **`created_at` / `updated_at` / `created_by` / `updated_by` / `deleted_at`** on business tables
   (the `Auditable` entities). Every record therefore has a soft-delete path — a property, unit, owner,
   tenant, lease or document is **archived, not removed**, because the lease, ledger, audit and
   document rows that reference it must stay coherent.
3. **A check constraint on every status column**, so an invalid state cannot be stored even by a
   future migration that forgets the rule. Constraints are named by convention:
   `ck_<table>_<what>` (e.g. `ck_properties_status`), `uq_<table>_<what>`, `fk_<table>_<target>`.
4. **Money is `numeric`.** Hibernate maps it to `BigDecimal`; no amount anywhere is a `double` or a
   `float`. Values are declared at the scale the schema stores, and the service rounds them with
   `MoneyPolicy.money` before writing.
5. **No repository query carries a literal `order by`.** A hard-coded order inside a paged query
   silently overrides what the caller asked for, leaving the endpoint looking as though sorting is
   broken — the page changes, the order does not.

Comments are written onto tables and non-obvious columns (`COMMENT ON TABLE/COLUMN`) so the intent
travels with the schema rather than living only in a migration file.

---

## Migration behaviour

- **Location** — `backend/src/main/resources/db/migration/`, named `V<n>__<description>.sql`.
- **Authority** — Flyway owns the schema and records every applied migration (with its checksum) in
  `flyway_schema_history`.
- **From empty** — on first start against an empty database Flyway creates the schema and applies the
  whole chain. This has been verified from empty: `V1` through `V23` apply in order, then Hibernate
  validates the entity model against the result.
- **Never edit an applied migration.** Its checksum is recorded; changing it fails the next start.
  A correction is a new migration.
- **`validateOnMigrate`** (Flyway's default) is left on, so a checksum mismatch is caught before
  anything is applied rather than after.

### The chain

| Version | Purpose |
| --- | --- |
| `V1` | Identity, sessions, audit and business-number series |
| `V2` | Seed permissions, property roles and staff accounts |
| `V3` | Property, building, amenity and property media metadata |
| `V4` | Unit types, units and unit status history |
| `V5` | Owners and property ownership |
| `V6` | Tenants and buyers |
| `V7` | Offices, agent profiles and property assignments |
| `V8` | Lead sources, leads, activities, assignments and follow-ups |
| `V9` | Listing channels, listings and listing status history |
| `V10` | Viewings and viewing status history |
| `V11` | Rental applications, screening results and application status history |
| `V12` | Leases, lease terms, status history and renewals |
| `V13` | Payment methods, rent invoices, invoice lines, payments, allocations |
| `V14` | Vendors, maintenance requests and maintenance status history |
| `V15` | Expense categories and property expenses |
| `V16` | Sales opportunities, offers and sale stage history |
| `V17` | Document register |
| `V18` | Portfolios and portfolio allocation |
| `V19` | Conversations, messages and notifications |
| `V20` | Import jobs and staged rows |
| `V21` | Hardening: composite indexes for the queries the product actually runs |
| `V22` | Seed the Meridian Estates portfolio: offices, agents, assets and stock |
| `V23` | Seed the letting, ledger and operational history |

`V21` adds composite indexes only where a real read justifies one — a query that filters on one column
and orders by another, where a per-column index would still leave a sort behind. Partial indexes
(`WHERE deleted_at IS NULL`) match the soft-delete predicate every query carries. **An index no query
uses is a write cost with no benefit, so none is added speculatively.**

---

## Major domain tables

Grouped as the product's modules; the identity, asset, stock, ownership, party and agency tables
arrive first, then the letting, ledger, operations and records groups.

| Group | Tables |
| --- | --- |
| Identity | `permissions`, `roles`, `role_permissions`, `users`, `user_roles`, `refresh_tokens`, `audit_logs`, `business_number_sequences` |
| Assets | `property_types`, `properties`, `buildings`, `amenities`, `property_amenities`, `property_media`, `property_status_history` |
| Stock | `unit_types`, `units`, `unit_status_history` |
| Ownership | `owners`, `property_ownership` |
| Parties | `tenants`, `buyers`, `buyer_requirements` |
| Agency | `offices`, `agent_profiles`, `agent_property_assignments` |
| Letting | `lead_sources`, `leads`, `lead_activities`, `lead_assignments`, `lead_followups`, `listing_channels`, `listings`, `listing_status_history`, `viewings`, `viewing_status_history`, `rental_applications`, `application_screening`, `application_status_history`, `leases`, `lease_terms`, `lease_status_history`, `lease_renewals` |
| Ledger | `payment_methods`, `rent_invoices`, `rent_invoice_lines`, `payments`, `payment_allocations` |
| Operations | `vendors`, `maintenance_requests`, `maintenance_status_history`, `expense_categories`, `property_expenses` |
| Capital markets | `sales_opportunities`, `offers`, `sale_stage_history` |
| Records | `documents`, `portfolios`, `portfolio_properties` |
| Interaction | `conversations`, `conversation_messages`, `notifications` |
| Data | `import_jobs`, `import_job_rows` |

A note on the ledger: **settlement is never stored on a lease or an invoice.** `settled`,
`outstanding` and `overdue` are derived from the allocations posted against invoices, which is why an
invoice's register row cannot carry them (they would cost a query per row) and why they travel on the
invoice *detail* instead.

---

## Seed behaviour

`V2` seeds identity — **99 permissions, 8 roles, 10 staff accounts** — and then runs a guard block
that fails the migration if the identity seed is incomplete. A partial seed would look fine until
someone tried to sign in or was unexpectedly refused an operation, so failing here is the honest
outcome. The guard asserts:

- at least 95 permissions exist;
- exactly 8 roles exist;
- exactly 10 staff accounts exist;
- no role has zero permissions and no account has zero roles;
- **the read-only `VIEWER` role holds no non-`_VIEW` permission.**

That last assertion is why the read-only role is built from the permission table
(`p.code LIKE '%\_VIEW'`) rather than listed by hand: a permission added for a new module is granted
to the viewer automatically, and a write can never be granted there by a typo.

`V22` and `V23` seed the demonstration portfolio. `V22` creates the structural half — the firm's
offices, agent profiles, portfolios, 14 properties with their buildings and 222 units, and who owns
them. `V23` creates the transactional half — leases, invoices, receipts, viewings and operational
history.

### Date-anchored seed behaviour

**`V23` dates every lease, invoice, receipt and viewing relative to `CURRENT_DATE`, not to a fixed
calendar date.**

A fixed date would mean the demonstration dataset aged: within a year every lease would have expired,
every trend chart would be empty, and the "expiring soon" rail would be full of rows that expired
long ago. Relative dating keeps the dataset permanently current, **which is why there is no separate
re-anchoring migration** — a fresh start and a year-old installation both present a live portfolio.

`V22` deliberately **does not** use `CURRENT_DATE`: the assets themselves — when a building was built,
when it was acquired — are genuinely historical facts and are fixed.

The property addresses and coordinates are real places in real cities, even though the firm and the
properties are fictional: the portfolio map is a genuine projection over genuine coordinates, and
plotting invented positions would make the map a drawing of nothing.

### What the seed is not

The seed is **demonstration data with published credentials**. It is not a fixture the test suite
depends on, and it is not a migration to copy into a real installation. An installation that holds
real data should be started against an empty database, or the demonstration rows removed deliberately.

---

## Validation

Three independent checks stand between a schema change and a running application:

1. **`SchemaConformanceTest`** (backend, runs in milliseconds, needs no database). It parses the
   migrations' own DDL to learn what the schema actually contains, asks Hibernate's metadata what the
   entity model believes, and compares the two. It asserts that
   - every mapped entity's table exists in the migrations;
   - every mapped column exists on its table;
   - every `NOT NULL` column with no default is mapped by an entity (otherwise an insert would fail
     against a column the application can never populate);
   - every table the migrations create is either mapped by an entity or declared reference data — a
     table nobody maps is a silent hole, so it must be a deliberate decision.

   This is a static check. It cannot see types, lengths, check constraints or foreign keys, so it is
   **not** a substitute for the live validation below.

2. **Hibernate `ddl-auto: validate`** against a real PostgreSQL at startup. This is the authority on
   types and lengths: the application will not start if the entity model disagrees with the migrated
   schema.

3. **`SqlFileDataIT`** (backend, run on demand — `mvn -B test -Dtest=SqlFileDataIT`). It starts an
   ephemeral PostgreSQL, applies `V1`–`V23` to it from empty, and asserts the seeded portfolio row by
   row: 14 properties, 222 units, 166 leases, 194 documents, and the rest. It is the only check that
   runs the chain end to end without a database having been prepared by hand, which makes it the
   quickest way to confirm a change to the migrations still applies cleanly to an empty schema. It is
   named `IT` rather than `Test` deliberately, so `mvn test` stays database-free and fast.

Because of (1) most drift is caught without a database, because of (2) whatever (1) cannot see is caught
on the next real start, and (3) is what proves the chain still applies from empty — the case a
`schema_history` recorded against an existing database can never show you.

---

## Working with the database

```bash
# apply migrations only
cd backend && mvn spring-boot:run          # or: java -jar target/real-estate-backend-0.1.0.jar

# inspect what has been applied
psql -d real_estate -c "SELECT version, description, success FROM real_estate.flyway_schema_history ORDER BY installed_rank;"
```

If a migration fails: fix the cause, then either repair the history or drop the schema and re-migrate
from empty. **Do not edit an applied migration to make it pass** — the checksum will disagree on the
next start, and an installation that already applied the old version will never see the correction.
