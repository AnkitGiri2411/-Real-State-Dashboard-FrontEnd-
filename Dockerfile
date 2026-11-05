# syntax=docker/dockerfile:1
#
# ============================================================================
# Meridian Estates — the dashboard and its API in a single image.
#
# The built React bundle is placed on the Spring Boot service's own classpath,
# so the browser calls /api/v1 on the very address it loaded the page from.
# One origin is worth a lot here: CORS stops being a question, no reverse proxy
# is needed, there is no SPA-fallback rule to configure in one, and the operator
# has a single URL to share.
#
# Three stages. The first needs Node, the second needs a JDK, and neither
# belongs in the image that runs — the last stage carries a JRE and the JAR.
# ============================================================================

# ---------------------------------------------------------------------------
# 1. The dashboard
# ---------------------------------------------------------------------------
FROM node:20-alpine AS web

WORKDIR /web

# Dependencies first, from the lockfile alone, so editing a page does not
# re-resolve the tree. `npm ci` rather than `npm install`: it installs exactly
# the locked versions and fails outright if the lockfile and package.json have
# drifted apart, which is the failure worth having at build time.
COPY apps/web/package.json apps/web/package-lock.json ./
RUN npm ci

COPY apps/web/ ./

# Where the API lives is compiled into the bundle, so it is decided here or not
# at all. The default is *relative* — the same origin the page came from — which
# is what lets one image serve a platform subdomain, a custom domain and a local
# container without being rebuilt for each.
ARG VITE_API_BASE_URL=/api/v1
ENV VITE_API_BASE_URL=${VITE_API_BASE_URL}

# `npm run build` runs `tsc --noEmit` first, so a type error fails the image
# rather than shipping.
RUN npm run build

# ---------------------------------------------------------------------------
# 2. The service, with the dashboard on its classpath
# ---------------------------------------------------------------------------
FROM maven:3.9-eclipse-temurin-21 AS api

WORKDIR /build

# The POM on its own first, so the dependency download is a layer that survives
# every source change.
COPY backend/pom.xml ./
RUN mvn -B -q dependency:go-offline

COPY backend/src ./src

# Spring Boot serves classpath:/static/ at the root, which is where the bundle
# goes. Assembling it here rather than committing it keeps build output out of
# the repository and guarantees the two halves are the same revision.
COPY --from=web /web/dist ./src/main/resources/static

# Tests are a gate of their own, not an image step: the suite needs no database
# and running it twice per deploy helps nobody. Run `mvn -B test` in CI.
RUN mvn -B -DskipTests package

# ---------------------------------------------------------------------------
# 3. Runtime
# ---------------------------------------------------------------------------
FROM eclipse-temurin:21-jre-alpine AS runtime

# A JRE and no Maven: the image carries what runs the service, not what builds
# it. The service runs as a non-root user because nothing it does needs more.
RUN addgroup -S app && adduser -S -G app app
WORKDIR /app
COPY --from=api /build/target/real-estate-backend-0.1.0.jar ./app.jar
USER app

# Platforms hand the port in $PORT and expect the service to bind to it, while
# the application reads its own SERVER_PORT. Deriving one from the other keeps
# both conventions satisfied, and 8083 stays the default for a bare `docker run`.
ENV SERVER_PORT=8083
EXPOSE 8083

# MaxRAMPercentage sizes the heap from the *container's* limit rather than the
# host's, which is what makes a small instance viable instead of being killed.
#
# The probe is the product's own readiness endpoint: it is public, and it does
# not touch the database, so a busy database cannot make a healthy instance look
# unhealthy. `$PORT` rather than `$SERVER_PORT` because the export above happens
# inside the entrypoint's shell and is not visible to this command.
HEALTHCHECK --interval=30s --timeout=5s --start-period=90s --retries=3 \
  CMD wget -qO- "http://127.0.0.1:${PORT:-8083}/api/v1/health/readiness" >/dev/null 2>&1 || exit 1

ENTRYPOINT ["/bin/sh", "-c", "export SERVER_PORT=\"${PORT:-8083}\"; exec java -XX:MaxRAMPercentage=75 -jar /app/app.jar"]
