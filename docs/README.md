# Meridian Estates — Real Estate Management Dashboard

A full-stack, single-firm real estate management dashboard: an asset register, a letting desk,
a rent ledger, a sales pipeline and the operational work around them, backed by PostgreSQL.

The product is a **portfolio management system for a property firm**. It is not a listing portal and
not a CRM bolted onto one: the asset register is the spine, and units, leases, invoices, payments,
maintenance, documents, offers and conversations all hang off a property or a unit.

---

## Product overview

Meridian Estates is the demonstration identity the product ships with. The installation is
configured by environment (see [SETUP.md](./SETUP.md)) and can be rebranded without a code change.

The product serves **one firm**. There is no multi-tenant switcher, because a firm does not hide its
own portfolio from its own staff. What *is* scoped is the personally carried work — a user's own
leads, viewings and assignments — through an access scope that is reloaded from the database on
every request rather than trusted from the token.

### Who uses it

| Role | What they do |
| --- | --- |
| `OWNER` | Everything. The last active owner cannot be deactivated or have permissions reduced. |
| `ADMIN` | Staff, records, settings. |
| `PROPERTY_MANAGER` | The whole operation, the rent roll and the numbers. |
| `LEASING_AGENT` | Leads, listings, viewings, applications, tenancies and leases. |
| `SALES_AGENT` | Buyers, the offer and sale pipeline, and the listings that carry them. |
| `ACCOUNTING` | The rent roll, receipts, voids and refunds, expenses, read-only audit. |
| `MAINTENANCE` | Faults, their assignment, resolution and cost. |
| `VIEWER` | Read-only across the portfolio. |

See [RBAC.md](./RBAC.md) for the full permission model.

---

## Main capabilities

- **Asset register** — properties, buildings, units, amenities and property media metadata, with
  property type, valuation, area, lifecycle status and a status timeline.
- **Ownership** — owners and their holdings in a property, held as dated periods with a share, with
  the 100%-rule enforced.
- **Letting** — leads and their pipeline, listings across channels, viewings, rental applications
  with screening, and leases with a rent schedule, renewals and termination.
- **Rent ledger** — rent invoices with lines, receipts, allocations, voids, refunds and write-offs;
  settlement, outstanding and overdue are **derived from the ledger**, never stored on the lease.
- **Sales** — sale opportunities, offers with directions and statuses, and stage history.
- **Operations** — maintenance requests with vendors, status history, estimated and actual cost;
  property expenses with categories, recovery and a monthly trend.
- **Documents** — a document register with supersession, versions and an expiry watchlist.
- **Portfolios** — books of assets with dated allocations and per-book performance.
- **Communication** — conversations with channels, statuses, priorities, internal notes and messages.
- **Notifications** — a per-caller feed and an unread badge.
- **Reports** — portfolio, collections, occupancy and trend reporting.
- **Data import** — insert-only CSV/XLSX staging, validation, review and an idempotent commit.
- **Audit** — every mutation is recorded with actor, entity, before/after values and request context.
- **Dashboard** — a single summary projection covering portfolio standing, trends, pipelines and
  recent activity for one as-of date.

---

## Technology stack

**Backend**

| Concern | Choice |
| --- | --- |
| Language / runtime | Java 21 |
| Framework | Spring Boot 3.3.3 |
| Web | Spring MVC (`spring-boot-starter-web`) |
| Security | Spring Security, stateless, JWT bearer tokens (JJWT 0.12.6) |
| Persistence | Spring Data JPA + Hibernate (`ddl-auto: validate`) |
| Schema authority | Flyway (`flyway-core`, `flyway-database-postgresql`) |
| Database | PostgreSQL — either a server this installation is pointed at, or an ephemeral one started from the migrations at boot (`APP_DATA_SOURCE`) |
| Validation | Jakarta Bean Validation |
| Spreadsheet parsing | Apache POI 5.3.0 (`.xlsx`/`.xls`), commons-csv 1.12.0 (RFC-4180 CSV) |
| Configuration | `spring-dotenv` — `backend/.env` for local, host env vars win |
| Tests | JUnit 5, AssertJ, Spring Boot Test, Spring Security Test |

**Frontend** (`apps/web`)

| Concern | Choice |
| --- | --- |
| Language | TypeScript 5.7 (strict) |
| UI | React 18 |
| Build | Vite 5 |
| Routing | React Router 6 |
| Server state | TanStack Query 5 |
| Client state | Zustand 5 |
| Styling | Tailwind CSS 3 + CSS custom properties (design tokens) |
| Motion | framer-motion 11 |
| Icons | lucide-react |
| Tests | Vitest 2, Testing Library, axe-core, jsdom |

---

## Quick start

Two processes: the backend on **8083**, the frontend on **5175**. **No database to install** — the
backend starts an ephemeral PostgreSQL on this machine and seeds it from the migrations in this
repository, so the dashboard comes up with the full demonstration portfolio. See
[SETUP.md](./SETUP.md#running-it-from-the-sql-files) for what that does and does not give you, and for
how to point it at a real database instead.

```bash
# 1. Backend
cd backend
cp .env.example .env          # ships with APP_DATA_SOURCE=sqlfiles
mvn spring-boot:run           # starts PostgreSQL, migrates and seeds; Flyway applies V1-V23

# 2. Frontend
cd apps/web
npm install
npm run dev                   # http://localhost:5175
```

Sign in with a seeded demonstration account, e.g. `owner@meridianestates.example` /
`testing`. The password is a **published demonstration credential** and must be changed for any
installation that holds real data. The ten seeded accounts and the roles they hold are listed in
[RBAC.md](./RBAC.md).

Full prerequisites, environment variables and database creation are in [SETUP.md](./SETUP.md).

### Deploying it

The two processes above are how it is developed. There is also a **static deployment with no server at
all**: the front end serves sign-in and every register from a dataset bundled into the build, generated
from the migrations. That is what a Vercel deployment uses — see
[DEPLOY_VERCEL.md](./DEPLOY_VERCEL.md).

To put the full stack somewhere a client can open, build the container and let the service serve the
dashboard and the API from one origin — one URL, no CORS and no reverse proxy to configure:

```bash
docker build -t meridian-estates .
docker run -p 8083:8083 \
  -e DATABASE_URL=postgres://user:password@host:5432/real_estate \
  -e JWT_SECRET="$(openssl rand -base64 48)" \
  -e APP_ENV=production \
  meridian-estates
```

`render.yaml` turns that into a hosted deployment: **New → Blueprint** against this repository
provisions the service and a managed PostgreSQL together. The full walk-through, the variable list and
the post-deployment checks are in [DEPLOYMENT.md](./DEPLOYMENT.md).

---

## Project structure

```
real-estate-dashboard/
├── apps/
│   └── web/                     React + Vite single-page application
│       └── src/
│           ├── auth/            session, token rotation, auth store
│           ├── components/      UI kit, charts, formatters, common guards
│           ├── layout/          the application shell
│           ├── lib/             error decoding
│           ├── pages/           one module per page
│           ├── platform/        product identity / navigation profile
│           ├── services/        the API client and per-module endpoint maps
│           └── types/           payload types mirroring the backend DTOs
├── backend/                     Spring Boot service
│   ├── pom.xml
│   ├── .env.example
│   └── src/
│       ├── main/java/com/meridian/realestate/
│       │   ├── config/          security, properties, datasource hooks, startup guards
│       │   ├── controller/      HTTP layer — routing, validation, envelope
│       │   ├── domain/          JPA entities and enums
│       │   ├── dto/             the API contract (records) + ErrorMessages
│       │   ├── exception/       ApiException and the global handler
│       │   ├── platform/        industry/product identity
│       │   ├── repository/      Spring Data repositories
│       │   ├── security/        AccessScope (institutional scope vs personal scope)
│       │   ├── service/         business rules
│       │   └── web/             PageRequestFactory
│       ├── main/resources/
│       │   ├── application.yml
│       │   └── db/migration/    Flyway V1–V23
│       └── test/java/           unit, policy and schema-conformance tests
├── Dockerfile                   one image serving the dashboard and the API on one origin
├── render.yaml                  Render Blueprint: the service and its database, as code
├── docs/                        this documentation set
└── packages/api-types/          reserved for a shared generated type package
```

### Documentation set

| Document | Contents |
| --- | --- |
| [ARCHITECTURE.md](./ARCHITECTURE.md) | Layers, the backend/frontend split, auth, audit, domain modules |
| [SETUP.md](./SETUP.md) | Prerequisites, environment, database creation, startup |
| [DATABASE.md](./DATABASE.md) | Schema conventions, migrations, seeding, validation |
| [API.md](./API.md) | Base path, envelopes, pagination, filtering, representative endpoints |
| [RBAC.md](./RBAC.md) | Roles, permissions, scope, the authorization model |
| [TESTING.md](./TESTING.md) | Test suites, typecheck, builds, browser/a11y verification |
| [DEPLOYMENT.md](./DEPLOYMENT.md) | Production JAR, frontend bundle, configuration, operations |
| [DEPLOY_VERCEL.md](./DEPLOY_VERCEL.md) | The static deployment: no server, no database, the bundled dataset, and what it does not do |

---

## Verification status

The claims in this documentation set are backed by the commands in [TESTING.md](./TESTING.md) and
[DEPLOYMENT.md](./DEPLOYMENT.md). Anything not actually run is labelled **NOT VERIFIED** where it
appears. Do not treat a build succeeding as a substitute for the checks described there.
