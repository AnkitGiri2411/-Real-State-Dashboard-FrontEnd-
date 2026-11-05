# Setup

Two processes and one PostgreSQL database.

**In a hurry, or only want to look at the product?** You can skip the database entirely and run the
whole dashboard from the SQL files in this repository — no PostgreSQL to install, create or configure.
See [Running it from the SQL files](#running-it-from-the-sql-files). That is the mode this working copy
is configured for.

| Piece | Default | Notes |
| --- | --- | --- |
| Backend API | `http://localhost:8083` | Spring Boot, `/api/v1` |
| Frontend dev server | `http://localhost:5175` | Vite |
| Frontend preview (production bundle) | `http://localhost:4175` | `npm run preview` |
| Database host | `localhost:5432` | configurable |
| Database name / role / schema | `real_estate` / `real_estate` / `real_estate` | configurable |

5175 and 4175 are this product's own ports. Each product in the marketplace owns its own so several
can run side by side; do not reuse another product's ports.

---

## Prerequisites

| Tool | Version used | Notes |
| --- | --- | --- |
| JDK | **21** | `java.version` in `backend/pom.xml` is 21. |
| Maven | 3.9+ | |
| Node.js | 20+ | developed and verified on Node 24 |
| npm | 10+ | |
| PostgreSQL | 15+ | developed and verified on PostgreSQL 18. **Not needed at all** if you run from the SQL files — see below. |

No Docker is required.

---

## Running it from the SQL files

The demonstration portfolio *is* the Flyway chain in `backend/src/main/resources/db/migration`
(`V1`–`V23`). So it can be applied to a PostgreSQL that is created on this machine at boot and thrown
away at shutdown — no database to install, create or configure, and nothing read from any other
database. **Skip steps 1 and 2 entirely.**

```bash
cd backend
mvn spring-boot:run     # http://localhost:8083
```

That is the whole of it. The first start unpacks and initialises the embedded server, then applies
`V1`–`V23`, which seeds the same 14 properties, 222 units, 166 leases and rent ledger that an
installation seeded from empty gets. The log says so plainly:

```
WARN ... SqlFileDataSourcePostProcessor : Data source is the SQL files: an ephemeral PostgreSQL is
listening on localhost:58622 and the schema and the demonstration portfolio are being applied from
db/migration alone. Nothing is read from another database and nothing written here survives a restart.
...
INFO ... DbMigrate : Successfully applied 23 migrations to schema "real_estate", now at version v23
```

Then the frontend, and sign in with any account from [step 5](#5-sign-in):

```bash
cd apps/web && npm install && npm run dev      # http://localhost:5175
```

To confirm which mode an instance is actually in:

```bash
curl http://localhost:8083/api/v1/health/readiness
# {"status":"UP","app":"real-estate-backend","env":"development","dataSource":"sql-file","time":"..."}
```

### What to expect

| | |
| --- | --- |
| **Which PostgreSQL** | A real one, pinned to the version `render.yaml` provisions, started on an ephemeral port — so a server already on 5432 is not disturbed. |
| **Where its data lives** | A temporary directory, a fresh one per start, which is what makes the migrations always apply from empty rather than being skipped. |
| **Writes** | **Lost on restart.** Editing a record, recording a payment or running an import all work, and are gone when the process stops. That is inherent to a data source that exists only to show the SQL files — and precisely why the mode is refused outside development. |
| **Dates** | `V23` anchors every lease, invoice and receipt to today, so the dashboard's "this period" figures and trend charts are populated however old the checkout is. |
| **Cleanup** | The server is stopped on a normal exit (Ctrl-C). If a run is killed outright, the next start reclaims the leftover server and its directory by itself. |

### Turning it on and off

`APP_DATA_SOURCE` selects the mode. It is set in `backend/.env` (gitignored) for this working copy and
copied from `backend/.env.example`; `application.yml` carries the `postgres` default, so a deployment is
never affected by it.

| Value | What it does |
| --- | --- |
| `sqlfiles` | The ephemeral server above, seeded from the migrations alone. Refused unless `APP_ENV` is `development`, `local` or `test`. |
| `postgres` | The `DB_*` server. The default, and the only mode a deployment uses. |

To go back to a real database, set `APP_DATA_SOURCE=postgres` in `backend/.env` and follow steps 1–3.

> **On Windows** the embedded server may need the
> [Microsoft Visual C++ 2013 Redistributable](https://support.microsoft.com/en-us/help/3179560/update-for-visual-c-2013-and-visual-c-redistributable-package).
> The **2013** version specifically — installing another one does not substitute. If it is missing, the
> start stops with a message naming it; either install it, or use `postgres` mode. Verified running on
> this project's Windows (amd64) development machine.

There is also an end-to-end check of this path that needs no database prepared beforehand:

```bash
cd backend
mvn -B test -Dtest=SqlFileDataIT      # starts a server, applies V1–V23, asserts the portfolio
```

---

## 1. Create the database

The product owns one database and one schema and never reads another product's. Run as a PostgreSQL
superuser (`psql`), substituting your own password for the placeholder:

```sql
CREATE ROLE real_estate LOGIN PASSWORD 'change-me-in-your-env';
CREATE DATABASE real_estate OWNER real_estate;
```

The **schema is not created here** — Flyway creates `real_estate` on first start. Because the role
owns the database, it owns the schema it creates, so no superuser grant on `public` is needed
(PostgreSQL 15+ restricts `public`).

Connect the application with the same role. Nothing else has to be pre-created: migrations, reference
data and the demonstration portfolio all arrive as Flyway migrations.

---

## 2. Configure the backend

```bash
cd backend
cp .env.example .env
```

`spring-dotenv` loads `backend/.env` at startup. **A host environment variable always wins over
`.env`**, so a container or service manager can override any value without editing the file.

`.env` is gitignored; the committed template is `.env.example`, which carries placeholders only.
`backend/.env` is the one file in this project that holds a real credential.

### Environment variables

**Runtime**

| Variable | Default | Purpose |
| --- | --- | --- |
| `SERVER_PORT` | `8083` | HTTP port |
| `APP_ENV` | `development` | environment label |
| `CORS_ALLOWED_ORIGINS` | `http://localhost:5175,http://localhost:4175,http://localhost:3000` | comma-separated allowed browser origins |
| `APP_DATA_SOURCE` | `postgres` | `postgres` reads the `DB_*` server below; `sqlfiles` starts an ephemeral PostgreSQL seeded from the migrations alone. See [Running it from the SQL files](#running-it-from-the-sql-files). |

**Database**

| Variable | Default | Purpose |
| --- | --- | --- |
| `DB_HOST` | `localhost` | |
| `DB_PORT` | `5432` | |
| `DB_NAME` | `real_estate` | |
| `DB_USERNAME` | `real_estate` | |
| `DB_PASSWORD` | `real_estate` | **the only secret here** |
| `DB_SCHEMA` | `real_estate` | Hibernate's `default_schema` and Flyway's schema |
| `DATABASE_URL` | — | optional full JDBC URL that overrides the parts above |

**Secrets**

| Variable | Purpose |
| --- | --- |
| `JWT_SECRET` | signing key for access tokens. `SecretStartupGuard` refuses to start with the development default against a non-local database, so it cannot be shipped by accident. |

**Auth lifetimes**

| Variable | Default |
| --- | --- |
| `ACCESS_TOKEN_EXPIRATION_SECONDS` | `900` |
| `REFRESH_TOKEN_EXPIRATION_SECONDS` | `2592000` (30 days) |

**The firm** — one installation serves one firm, so the profile is configuration rather than a table.
`FIRM_NAME`, `FIRM_CODE`, `FIRM_EMAIL`, `FIRM_PHONE`, `FIRM_ADDRESS`, `FIRM_LICENCE`.

**Money** — `FEE_CURRENCY` (default `INR`), `FEE_CURRENCY_SYMBOL` (default `Rs.`).

**Portfolio policy** — the thresholds the dashboard and reports reason about:
`LEASE_EXPIRY_WARNING_DAYS` (60), `LEASE_EXPIRY_URGENT_DAYS` (30), `RENT_DUE_DAY` (5),
`RENT_OVERDUE_GRACE_DAYS` (5), `OCCUPANCY_TARGET_PERCENT` (92), `LISTING_STALE_DAYS` (45),
`VIEWING_REMINDER_HOURS` (24), `MAINTENANCE_URGENT_HOURS` (24), `TREND_MONTHS` (14).

**Data import** — `IMPORT_MAX_FILE_SIZE_MB` (10), `IMPORT_MAX_ROWS` (5000), `IMPORT_BATCH_SIZE` (200).

**Product platform** — `INDUSTRY` (`REAL_ESTATE`) and `PRODUCT_CODE` (`REAL_ESTATE_DASHBOARD`) are
chosen at build/deploy time. There is deliberately no runtime switcher, because a product ships as
source code configured for one industry. Optional rebranding: `BRAND_PRODUCT_NAME`, `BRAND_NAME`,
`BRAND_COMPANY_NAME`, `BRAND_LOGO_URL`, `BRAND_FAVICON_URL`, `BRAND_LOGIN_HEADLINE`,
`BRAND_LOGIN_SUBHEADLINE`, `BRAND_PRIMARY_COLOR`, `BRAND_SECONDARY_COLOR`.

---

## 3. Start the backend

```bash
cd backend
mvn spring-boot:run
```

or build and run the production JAR:

```bash
mvn -DskipTests clean package
java -jar target/real-estate-backend-0.1.0.jar
```

On first start Flyway applies `V1`–`V23` from an empty database (creating the schema, the tables, the
seeded roles and permissions, the staff accounts and the demonstration portfolio) and Hibernate then
validates the entity model against the result. **A model/schema drift fails startup** rather than being
silently rewritten.

Confirm it is up:

```bash
curl http://localhost:8083/api/v1/health/readiness
# {"status":"UP","app":"real-estate-backend","env":"development","time":"..."}
```

`GET /api/v1/health/readiness` is one of only three endpoints reachable without a token.

---

## 4. Start the frontend

```bash
cd apps/web
npm install
npm run dev            # http://localhost:5175
```

Frontend build-time settings live in `apps/web/.env` (copy from `apps/web/.env.example`). **Nothing
there may ever hold a secret** — every `VITE_*` value is compiled into the browser bundle and is
readable by anyone who loads the app.

| Variable | Default | Purpose |
| --- | --- | --- |
| `VITE_DATA_SOURCE` | `local` | `local` answers every request in the browser from the bundled dataset, so no backend is needed; `api` uses `VITE_API_BASE_URL`. See [DEPLOY_VERCEL.md](./DEPLOY_VERCEL.md). |
| `VITE_API_BASE_URL` | `http://localhost:8083/api/v1` | where the API lives, when `VITE_DATA_SOURCE=api` |
| `VITE_PRODUCT_CODE` | `REAL_ESTATE_DASHBOARD` | |
| `VITE_PRODUCT_NAME` | `Real Estate Management Dashboard` | |
| `VITE_INDUSTRY_CODE` | `REAL_ESTATE` | |
| `VITE_BRAND_*` | blank | optional rebranding fallback |

These are only the **fallback used before `GET /api/v1/platform/profile` answers**. The backend is
the runtime source of truth for the product's identity, the firm's vocabulary and the sidebar.

### Running it as one process instead

The two-process arrangement above is for development. To run the dashboard and the API together as a
single origin — which is what a deployment uses — build the container instead:

```bash
docker build -t meridian-estates .
docker run -p 8083:8083 \
  -e DATABASE_URL=postgres://real_estate:<password>@host:5432/real_estate \
  -e JWT_SECRET="$(openssl rand -base64 48)" \
  -e APP_ENV=production \
  meridian-estates            # http://localhost:8083
```

Nothing else changes: `.env` is not used by the container, because the platform supplies the values as
environment variables. See [DEPLOYMENT.md](./DEPLOYMENT.md) for the hosted path, the variable list and
the post-deployment checks.

---

## 5. Sign in

The seed creates **10 staff accounts**, one per role, all with the published demonstration password
`testing`:

| Account | Role |
| --- | --- |
| `owner@meridianestates.example` | `OWNER` |
| `admin@meridianestates.example` | `ADMIN` |
| `manager@meridianestates.example` | `PROPERTY_MANAGER` |
| `leasing@meridianestates.example` | `LEASING_AGENT` |
| `leasing2@meridianestates.example` | `LEASING_AGENT` |
| `sales@meridianestates.example` | `SALES_AGENT` |
| `sales2@meridianestates.example` | `SALES_AGENT` |
| `accounts@meridianestates.example` | `ACCOUNTING` |
| `maintenance@meridianestates.example` | `MAINTENANCE` |
| `viewer@meridianestates.example` | `VIEWER` |

> **The demonstration password is published on purpose.** The seed documents it and the installation
> notes tell the operator to change it. It is not a secret and was never intended to protect real
> data. Change every account's password before this installation holds anything real.

---

## Local development notes

- **Changing the schema** means adding a new `V<n>__*.sql` migration in
  `backend/src/main/resources/db/migration/`. Never edit an applied migration — its checksum is
  recorded and a change will fail the next start. See [DATABASE.md](./DATABASE.md).
- **`backend/target/`** is build output and is gitignored. `mvn clean` removes it.
- **Flyway will not run against a schema with a failed migration.** If a migration fails, fix the
  cause and either repair the history or drop the schema and re-migrate from empty.
- **`open-in-view` is off.** A lazily-mapped association reached outside a transaction throws rather
  than silently returning nothing; if you add a read path, keep the mapping inside the service's
  transaction.
- **Ports in use.** If 8083 or 5175 is already taken, change `SERVER_PORT` / Vite's `server.port`
  rather than killing an unrelated process.
