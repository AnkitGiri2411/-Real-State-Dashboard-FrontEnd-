# Deployment

One Spring Boot service, one static bundle, one PostgreSQL database.

> **A deployment always uses a real database.** `APP_DATA_SOURCE=sqlfiles`, which starts an ephemeral
> PostgreSQL from the migrations, is refused unless `APP_ENV` is `development`, `local` or `test` — so a
> deployment cannot come up on a database it would silently discard, even if the variable were inherited
> from somebody's local environment. Nothing in this document sets it, `render.yaml` and the `Dockerfile`
> do not set it, and `application.yml` defaults to `postgres`.

There are two supported shapes, and the difference between them is who serves the dashboard's files.

## A. One origin — the service serves the dashboard itself

This is the default for a hosted installation and the one `render.yaml` and `Dockerfile` build. The
built bundle is placed on the service's own classpath, so the browser calls `/api/v1` on the very
address it loaded the page from.

```
        ┌──────────────────────────────────┐
Client ─┤ Spring Boot service (:8083)      │
        └──────────────────────────────────┘
             ├─ /                → classpath:/static/index.html
             ├─ /assets/*.js     → classpath:/static/assets/…
             ├─ /properties/<id> → index.html (client-routed deep link)
             └─ /api/v1/…        → the API
                                        │
                                        ▼
                              PostgreSQL, schema `real_estate`
```

Worth the small amount of wiring, because three problems stop existing at once: CORS never applies,
there is no reverse proxy to configure, and there is no SPA-fallback rule to write in one — the
service owns the fallback itself (`WebConfig`). A single URL is then the whole deployment.

Two rules hold that fallback in place, and both are deliberate:

- **It is restricted to one and two path segments.** Every route the dashboard defines is one or two
  deep (`/dashboard`, `/properties/{id}`); every API path is at least three (`/api/v1/…`). The
  patterns therefore cover the SPA completely while leaving the whole of `/api/` to the controllers.
  A greedy `/**` fallback would answer an unknown API path with an HTML shell and a wrong-method call
  with a shell instead of a 405 — a client and a monitoring probe would both read success where there
  is none. Adding a route deeper than two segments to the frontend means extending `WebConfig` too.
- **The shell is permitted before authentication, the API is not.** `SecurityConfig` lets a
  non-`/api`, extension-less GET through, because the shell carries no data of the firm's — every
  record is fetched afterwards from the authenticated API. An unauthenticated API path, **including
  one that does not exist**, still answers `401`, so a caller cannot learn which endpoints are real.

## B. Two origins — a reverse proxy in front

If the dashboard's files are served by something else (a CDN, a static host, an existing nginx), the
proxy takes on the fallback instead.

```
        ┌──────────────┐
Client ─┤ reverse proxy├─┬─ /          → static files from the frontend build
        └──────────────┘ ├─ /api/v1/…  → Spring Boot service (:8083)
                         └─ (SPA fallback: unknown paths → index.html)
                                        │
                                        ▼
                              PostgreSQL, schema `real_estate`
```

The proxy must serve `index.html` for any path that is not a file and does not begin `/api/`, or a
deep link such as `/properties/<id>` opened directly in a browser will 404. In this shape
`CORS_ALLOWED_ORIGINS` must name the dashboard's origin exactly, and the frontend must be built with
`VITE_API_BASE_URL` pointing at the API rather than the relative default.

---

## C. Static — no server at all

Where the platform cannot run a JVM or hold a database, the front end can serve itself. With
`VITE_DATA_SOURCE=local` — the default — every request is answered in the browser from a dataset
bundled into the build, generated from the migrations. Sign-in included.

```
        ┌──────────────────────────────────┐
Client ─┤ static host (Vercel)             │
        └──────────────────────────────────┘
             ├─ /assets/*.js      → the application and its data chunks
             ├─ /properties/<id>  → index.html (client-routed deep link)
             └─ (everything else) → index.html
```

One artefact, no origin to configure, no CORS, and nothing to keep running. It is what this
repository deploys to Vercel: see **[DEPLOY_VERCEL.md](./DEPLOY_VERCEL.md)** for the deployment, the
generator that produces the dataset, and the honest list of what a serverless build cannot do —
persist a write, or enforce a business rule.

`VITE_DATA_SOURCE=api` turns the same build back into the two-origin arrangement above.

---

## Artifacts

| Artifact | Built by | Output |
| --- | --- | --- |
| Backend | `cd backend && mvn -DskipTests clean package` | `backend/target/real-estate-backend-0.1.0.jar` — an executable Spring Boot JAR |
| Frontend | `cd apps/web && npm ci && npm run build` | `apps/web/dist/` — static files (`index.html` plus hashed assets under `assets/`) |
| Both, in one image | `docker build -t meridian-estates .` | An image serving the dashboard and the API on one origin |

Both have been built and run. The frontend build is produced by `tsc --noEmit && vite build`, so a
type error fails the build rather than shipping.

`npm run preview` serves the built bundle locally on **4175** for an acceptance run.

### The container image

`Dockerfile` is a three-stage build — the dashboard on Node, the service on a JDK with the bundle
copied onto its classpath, and a JRE that carries only the JAR. Neither Node nor Maven ends up in the
image that runs.

Two details of it are load-bearing:

- **`VITE_API_BASE_URL` defaults to `/api/v1`**, a relative path. Platform deployments hand the port in
  `$PORT`, so the entrypoint derives `SERVER_PORT` from it; `PORT` rather than `SERVER_PORT` is also
  what the image's own `HEALTHCHECK` probes, because an `export` inside the entrypoint's shell is not
  visible to that command.
- **Nothing in the image is a secret.** `.dockerignore` excludes every `.env`, so a development
  signing key cannot be baked into an image that gets pushed somewhere. Everything the service needs
  arrives as an environment variable at run time.

---

## Environment configuration

The service reads its configuration from the environment. `spring-dotenv` also loads `backend/.env`
for local development, and **a host environment variable always wins over `.env`** — so a container, a
systemd unit or a service manager overrides anything in the file without editing it.

`backend/.env` is gitignored and is the one file in this project that holds a real credential. The
committed template is `backend/.env.example`, which carries placeholders only.

### Required for any non-local installation

| Variable | Why |
| --- | --- |
| `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USERNAME`, `DB_PASSWORD` | the database |
| `DB_SCHEMA` | the schema the product owns (default `real_estate`) |
| `JWT_SECRET` | **signs every access token.** See the guard below |
| `APP_ENV` | the environment label the readiness probe reports |
| `CORS_ALLOWED_ORIGINS` | the exact browser origins the dashboard is served from |

### `DATABASE_URL` accepts a platform's connection string

Managed platforms do not hand out JDBC URLs. Render's `fromDatabase: connectionString` resolves to
`postgres://user:password@host/db`, and Heroku's is the same shape — neither of which the PostgreSQL
JDBC driver accepts, because it only takes a `jdbc:`-prefixed URL. An installation wired the obvious
way therefore fails at startup with a connection error that names none of the real cause.

`DataSourceUrlNormaliser` closes that gap. When `DATABASE_URL` is a bare `postgres://` or
`postgresql://` URI it is decomposed — before any bean is created — into
`spring.datasource.url`, `.username` and `.password`, taken from the URI's own parts. An
already-JDBC value, and every other scheme, is left completely alone, so local development driven by
`DB_HOST`/`DB_PORT` is unaffected.

So either form works, and on a managed platform the connection string is the only value to wire:

```
DATABASE_URL=postgres://user:password@host/db   # decomposed automatically
DATABASE_URL=jdbc:postgresql://host:5432/db     # used as given
```

Two details are deliberate. Credentials are read through `java.net.URI`, not `URLDecoder`: a URI
decodes its own `%xx` escapes and leaves `+` as a literal plus, which is what a base64 password
character means in that position — form decoding would read it as a space and produce an
authentication failure nobody could explain. And the value is never logged, only the host and
database name it resolved to.

### `JWT_SECRET` — the one that will stop you

`SecretStartupGuard` **refuses to start** when the database host is not this machine and
`JWT_SECRET` is still the development default. The dangerous case is "pointed at a real database",
which is a fact about the datasource rather than a label somebody typed into a config file, so that is
what the check reads.

It also enforces a minimum length of 32 characters (256 bits, the shortest key HS256 should be handed),
and it **never logs the value** — only the name of the variable and why it is unacceptable. A log that
echoes a secret is itself the leak.

Generate one, and keep it out of the repository:

```bash
openssl rand -base64 48
```

Changing it invalidates every issued token, which is the intended effect of a rotation.

### Full variable list

| Group | Variables |
| --- | --- |
| Runtime | `SERVER_PORT`, `APP_ENV`, `CORS_ALLOWED_ORIGINS` |
| Database | `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USERNAME`, `DB_PASSWORD`, `DB_SCHEMA`, `DATABASE_URL` (optional full JDBC URL, overrides the parts) |
| Auth | `JWT_SECRET`, `ACCESS_TOKEN_EXPIRATION_SECONDS` (900), `REFRESH_TOKEN_EXPIRATION_SECONDS` (2592000) |
| Firm | `FIRM_NAME`, `FIRM_CODE`, `FIRM_EMAIL`, `FIRM_PHONE`, `FIRM_ADDRESS`, `FIRM_LICENCE` |
| Money | `FEE_CURRENCY`, `FEE_CURRENCY_SYMBOL` |
| Policy | `LEASE_EXPIRY_WARNING_DAYS`, `LEASE_EXPIRY_URGENT_DAYS`, `RENT_DUE_DAY`, `RENT_OVERDUE_GRACE_DAYS`, `OCCUPANCY_TARGET_PERCENT`, `LISTING_STALE_DAYS`, `VIEWING_REMINDER_HOURS`, `MAINTENANCE_URGENT_HOURS`, `TREND_MONTHS` |
| Import | `IMPORT_MAX_FILE_SIZE_MB`, `IMPORT_MAX_ROWS`, `IMPORT_BATCH_SIZE` |
| Platform | `INDUSTRY`, `PRODUCT_CODE`, `BRAND_*` |

The frontend's build-time settings are separate and are compiled into the bundle:
`VITE_API_BASE_URL` (default `http://localhost:8083/api/v1`) and the `VITE_BRAND_*` fallbacks.
**Nothing there may hold a secret** — every `VITE_*` value is readable by anyone who loads the app.
The backend's `GET /api/v1/platform/profile` is the runtime source of truth for product identity,
terminology and the sidebar, so a rebrand is a backend setting and a rebuild rather than a code change.

---

## PostgreSQL

The product owns one database and one schema and never reads another installation's.

```sql
CREATE ROLE real_estate LOGIN PASSWORD '<generated>';
CREATE DATABASE real_estate OWNER real_estate;
```

The **schema is not created by hand** — Flyway creates it on first start. Because the role owns the
database, it owns the schema it creates, so no superuser grant on `public` is needed (PostgreSQL 15+
restricts `public`).

### First start

Flyway applies the whole chain (`V1`–`V23`) and Hibernate then **validates** the entity model against
the result. There is no manual DDL and no `ddl-auto: update`: a drift between the migrations and the
model fails startup rather than being silently rewritten.

**An installation that holds real data should be started against an empty database, or the
demonstration rows removed deliberately.** The seed is demonstration data with a published password;
see [DATABASE.md](./DATABASE.md).

### Upgrades

Deploy the new JAR and start it. Flyway applies only what has not been applied, in order, before the
application serves a request.

- **Back up first.** A migration that rewrites data is not reversible by restarting.
- **Rolling back the application does not roll back the schema.** An older JAR run against a newer
  schema will fail Hibernate's validation and refuse to start — which is the safe outcome, but it means
  a rollback plan has to include the database.
- Never edit an applied migration: its checksum is recorded, and changing it fails the next start.

---

## Deploying to Render (shape A, one origin)

`render.yaml` is a Blueprint. With it in the repository, **New → Blueprint** provisions the service
and the database together, so the deployment is a file in version control rather than a sequence of
clicks nobody recorded.

1. Create a Render account and give it access to this GitHub repository.
2. **New → Blueprint**, and point it at the repository — Render reads `render.yaml`.
3. Render creates the web service and the PostgreSQL instance, generates `JWT_SECRET`, and wires
   `DATABASE_URL` from the database's own connection string. No database value has to be copied out of
   the dashboard by hand.
4. On the first deploy Flyway applies `V1`–`V23` and Hibernate then validates the entity model. A
   schema/model drift fails the deploy rather than being silently rewritten, so the first deploy is
   also the slowest.
5. Share the `*.onrender.com` URL and sign in with a seeded demonstration account.

Three choices in that file are worth knowing about:

| Setting | Value | Why |
| --- | --- | --- |
| `plan` (service) | `1c-2g` | Render's free plan sleeps after inactivity — the first request after a quiet spell waits for a JVM to start, which is not what to show a client — and 512 MB is tight for a service that loads Apache POI for the spreadsheet import |
| `plan` (database) | `0.5c-1g` | Enough for the seeded portfolio; both are changed by editing one line |
| `region` | `singapore` | Closest to the firm. The database must be in the same region, because the connection string resolves over Render's private network |

`healthCheckPath` is `/api/v1/health/readiness` — public, and it does no database round trip, so a busy
database cannot make a healthy instance look unhealthy. `CORS_ALLOWED_ORIGINS` names the service's own
URL; it is inert while both halves share an origin, but it follows the service name if the service is
renamed.

**A demo instance must not hold real data.** It carries the seeded accounts whose password is published
in this repository. Change every account's password before it is given anything real.


## Running the service

```bash
java -jar real-estate-backend-0.1.0.jar
```

Useful flags for an unattended run:

```bash
java -jar real-estate-backend-0.1.0.jar \
  --server.port=8083 \
  --logging.file.name=/var/log/meridian/backend.log
```

`--logging.file.name` is worth setting behind a service manager: it writes the log to a file the
operator can read, which is what makes a stack trace retrievable after a failure.

### Health and probing

| Probe | Path | Auth |
| --- | --- | --- |
| Readiness | `GET /api/v1/health/readiness` | **none** |

```json
{ "status": "UP", "app": "real-estate-backend", "env": "production", "time": "…" }
```

The body is deliberately the smallest useful answer: no password, no connection string, no signing
key, no library version to fingerprint, and **no database round trip** — so a healthy instance is not
reported unready merely because a shared database is busy, and the probe cannot become a load source.

Point the load balancer's health check at it, and require `200`.

There is **no** separate liveness probe and no metrics endpoint. **NOT VERIFIED**: no Prometheus,
OpenTelemetry or APM integration has been configured or tested. Logs to stdout (or the file above) are
the only observability today; a container platform should collect them.

### Behind a reverse proxy

- **Terminate TLS at the proxy.** The service does not.
- **Forward the client address.** `X-Forwarded-For` is read, and **only its first hop** is treated as
  meaningful — the rest were appended by whatever the client sent, and a header a client controls is
  not evidence. This value is recorded on the session and in the audit trail as a fact about the
  network.
- **SPA fallback.** Serve the frontend's `index.html` for non-file, non-`/api/` paths.
- **Body size.** The proxy's request-body limit must be at least `IMPORT_MAX_FILE_SIZE_MB` (default
  10 MB), or an upload will be refused by the proxy with a body the application never sees.
- **CORS.** `CORS_ALLOWED_ORIGINS` must list the exact origins the browser uses, scheme and port
  included. Serving the frontend and the API from the same origin avoids the question entirely and is
  the simpler deployment. Only three headers are allowed through (`Authorization`, `Content-Type`,
  `X-Requested-With`), credentials are permitted, and no response header is exposed — there is nothing
  in this API a browser needs to read off a header rather than the body.

### Caching

The frontend's assets are content-hashed by Vite, so `assets/*` can be served
`Cache-Control: public, max-age=31536000, immutable`. **`index.html` must not be** — it names the
current asset hashes, and caching it is how a client keeps loading an old build after a release.

---

## Post-deployment checks

Run these against the deployed instance. Each is a check the build has already passed locally.

1. `GET /api/v1/health/readiness` → `200`, `"status": "UP"`.
2. Sign in and confirm `GET /api/v1/auth/me` matches the signed-in account.
3. Load the dashboard — the portfolio figures render.
4. Open one property and one lease from their registers, by **click and by keyboard**.
5. Change one record and confirm the change is visible after a reload and its entry is on the audit
   trail.
6. Sign out, then sign in again.
7. Watch the browser console and the network panel: after sign-in there should be **no errors and no
   failed requests**. (Before sign-in, three `401`s on `/auth/me` and `/auth/refresh` are expected —
   that is how the app establishes there is no session.)
8. Confirm the operator has changed the seeded demonstration passwords (see
   [SETUP.md](./SETUP.md) — the published password is not a secret and was never meant to protect real
   data).

---

## Rollback

| Layer | How |
| --- | --- |
| Application | Redeploy the previous JAR. If the schema has moved forward, it will refuse to start — see below. |
| Frontend | Restore the previous `dist/`. It is static and independently versioned, so a bad frontend release can be reverted without touching the API. |
| Database | Restore from the backup taken before the release. **A migration is not automatically reversible.** |

Because Hibernate validates rather than migrates, an older application against a newer schema fails
fast and loudly. That is intentional, but it means the rollback plan has to name the database step
rather than assuming the previous JAR will simply work.

---

## Not verified

Stated plainly rather than assumed:

- **No CI/CD pipeline is configured.** The container builds, but nothing runs the test suites on a
  push; `mvn -B test` and `npm test` are still a step an operator or a pipeline has to add.
- **No metrics/tracing integration.** Logs only.
- **No TLS configuration, WAF, rate limiting or reverse-proxy configuration is included** — those are
  deployment-environment concerns and are described above rather than shipped. Shape A terminates TLS
  at the platform's edge rather than in the service.
- **No load, soak or concurrency testing** has been run against the container.
- **The image has not been deployed to Render by this project**, only built and run locally against a
  local PostgreSQL. The Blueprint is unexercised against Render's own build and network until the
  first deploy.
