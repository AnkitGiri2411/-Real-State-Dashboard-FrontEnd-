# Deploying on Vercel

One static site. No server, no database, no environment variables.

```bash
# Local check of exactly what Vercel builds:
cd apps/web
npm ci --include=dev
npm run build          # → apps/web/dist
npm run preview        # http://localhost:4175
```

There is nothing else to configure. Import the repository at Vercel and it will read
[`vercel.json`](../vercel.json) and do the rest.

---

## How a deployment with no backend works

Vercel cannot run this product's Spring Boot service, and a static host cannot hold a database. So
the application carries its own data.

```
pages/*.tsx → services/index.ts → apiFetch (services/apiClient.ts)
                                      │
                                      ├── VITE_DATA_SOURCE=local ──→ src/local  (the bundled dataset)
                                      └── VITE_DATA_SOURCE=api   ──→ the Spring Boot service
```

Every screen in the product goes through `apiFetch`, and **no page builds a URL** -
`services/index.ts` is the complete API map. So implementing the API *behind* that one function makes
the entire application work offline, without editing a single page: the shell, the sidebar, the route
guards, the auth store and the sign-in screen all included, because they read through it too.

`src/local` is a real implementation of the API rather than a set of fixtures:

| Concern | How it is answered |
| --- | --- |
| Registers | The captured rows, filtered, sorted and paged to the same envelope (`items`, `page`, `size`, `totalElements`, `totalPages`, `hasNext`) |
| Records | The captured detail, or the register row where no screen opens one |
| Projections | Captured verbatim - a property's metrics, an agent's workload, the dashboard summary |
| Reports | Captured rows, narrowed to the requested window with the totals rebuilt |
| Sign-in | The ten seeded accounts, each with its real roles and permissions |
| Writes | Applied in memory, so the screen updates; discarded on reload |

### Where the bundled data comes from

`apps/web/src/data` is **generated from the migrations**, not written by hand:

```bash
cd backend && mvn spring-boot:run          # APP_DATA_SOURCE=sqlfiles in backend/.env
cd ../apps/web && npm run generate:demo-data
```

The generator signs in as all ten accounts, reads every register to the end, opens each record a
screen can reach, and captures the projections and the twelve reports. So the deployed site shows the
data the Flyway chain creates - 14 properties, 222 units, 166 leases, 1,921 invoices and the rest -
because it is the same responses the service gives.

It is ~4.8 MB, split **one chunk per module**, so a screen downloads only what it renders. The entry
bundle does not contain any of it: opening the dashboard pulls `platform`, `accounts`, `aggregates`
and `notifications`, and never the ledger.

Re-run the generator whenever the migrations or the DTOs change; `src/data/manifest.json` records what
was captured and when, so a stale bundle is identifiable.

---

## The one deployment trap

`vercel.json` installs with **`npm ci --include=dev`**, and that is not tidiness.

Vercel builds with `NODE_ENV=production` set, and npm reads that as "omit devDependencies". Vite and
TypeScript *are* devDependencies here, so a plain `npm ci` produces a tree without them and the build
fails with `'tsc' is not recognized as an internal or external command`. This was hit locally while
building this deployment, on a machine with `NODE_ENV=production`; the flag is what makes the install
independent of the ambient environment.

Nothing else is needed: no `VITE_*` variable has to be set, because the `local` default is in the code
(`src/local/index.ts`), not in a `.env` file that Vercel would not have.

---

## Why `vercel.json` looks the way it does

```json
{
  "installCommand": "cd apps/web && npm ci --include=dev",
  "buildCommand": "cd apps/web && npm run build",
  "outputDirectory": "apps/web/dist",
  "rewrites": [{ "source": "/(.*)", "destination": "/index.html" }]
}
```

- **`rewrites`** is what makes a deep link work. Vercel serves a real file when one exists and falls
  back to `index.html` for anything else, so opening `/properties/<id>` directly loads the app rather
  than a 404. It is the same job `WebConfig` does for the container.
- **`outputDirectory`** points at the Vite build, because the repository root holds the backend too.
- **The headers** cache `/assets/*` immutably (Vite fingerprints those filenames) and keep
  `index.html` revalidated, so a new deploy is picked up immediately.

`npm run build` typechecks before it bundles (`tsc --noEmit && vite build`), so a type error fails the
deployment rather than shipping.

---

## What works, and what deliberately does not

**Works.** Every screen: the dashboard with its charts, all 27 registers with paging, sorting,
filtering and search, the detail pages, the twelve reports with their windows and CSV export, settings,
profile, the notification feed per account, and sign-in with the real role model - `viewer@…` gets a
read-only product and cannot open `/imports`; `maintenance@…` sees only its own modules.

**Writes last until reload.** Creating a property, changing a lease's status, recording a payment -
each applies immediately and is visible in the register, in search and in paging, then gone when the
page reloads. There is no server to persist to.

**Business rules are not enforced.** The local layer will not refuse to approve an application while a
screening is pending, will not stop a payment exceeding its invoice, and will not cascade a lease
termination through the ledger. Reproducing those honestly would mean reimplementing the domain, and a
half-reimplementation would teach the reader something false about the product.

**The figures are a snapshot.** The dashboard is as at the date `manifest.json` records; it does not
move forward on its own. Re-running the generator refreshes it.

**It is not a secure system.** The password is compared against a value compiled into the bundle. That
is why the accounts are the published demonstration ones. A static site cannot hold a secret, and this
does not pretend otherwise.

---

## Running it locally

Two ways, and they are one build:

```bash
# Static, exactly as deployed - no backend needed
cd apps/web && npm ci --include=dev && npm run dev      # http://localhost:5175

# Against the real service
cd backend && mvn spring-boot:run                       # :8083
cd apps/web && VITE_DATA_SOURCE=api npm run dev         # http://localhost:5175
```

Sign in with any of the ten seeded accounts - `owner@meridianestates.example`,
`viewer@meridianestates.example`, `maintenance@meridianestates.example` and so on - all with the
published demonstration password `testing`. [SETUP.md](./SETUP.md#5-sign-in) lists them with their
roles.

The Spring Boot service, the migrations and the SQL-file mode are all untouched by this deployment and
still work as [SETUP.md](./SETUP.md) describes. Vercel simply does not build them.
