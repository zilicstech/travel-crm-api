# Deployment — Google Cloud Run

## Prerequisites

- A GCP project with billing enabled.
- A Cloud SQL for PostgreSQL 16 instance, with a database and a `voyra` role created (matching
  the local dev convention — rename if you prefer).
- An Artifact Registry Docker repository to push the built image to.
- Three Secret Manager entries, populated with real values (never the local dev ones):
  - `voyra-jwt-secret` — `openssl rand -base64 32`
  - `voyra-password-key` — `openssl rand -base64 32` (a different key from the JWT secret)
  - `voyra-db-password` — the Cloud SQL role's password
- The Cloud Run service account granted `roles/cloudsql.client` on the Cloud SQL instance and
  `roles/secretmanager.secretAccessor` on the three secrets above.

## Cloud SQL JDBC URL

The app connects to Cloud SQL via the Cloud SQL Postgres socket factory (already on the
classpath — see `pom.xml`), not a public IP:

```
jdbc:postgresql:///voyra_crm?cloudSqlInstance=PROJECT:REGION:INSTANCE&socketFactory=com.google.cloud.sql.postgres.SocketFactory
```

Substitute your actual `PROJECT:REGION:INSTANCE` connection name (found on the Cloud SQL
instance's Overview page) and database name.

## Build and deploy

```bash
gcloud builds submit --tag REGION-docker.pkg.dev/PROJECT/voyra/travel-crm-backend

gcloud run deploy travel-crm-backend \
  --image REGION-docker.pkg.dev/PROJECT/voyra/travel-crm-backend \
  --region REGION \
  --add-cloudsql-instances PROJECT:REGION:INSTANCE \
  --set-env-vars SPRING_PROFILES_ACTIVE=cloudrun \
  --set-env-vars DB_URL='jdbc:postgresql:///voyra_crm?cloudSqlInstance=PROJECT:REGION:INSTANCE&socketFactory=com.google.cloud.sql.postgres.SocketFactory' \
  --set-env-vars DB_USERNAME=voyra \
  --set-env-vars CORS_ALLOWED_ORIGINS=https://app.voyraglobal.com \
  --set-env-vars RUN_TENANT_MIGRATIONS=true \
  --set-secrets JWT_SECRET=voyra-jwt-secret:latest \
  --set-secrets PASSWORD_ENCRYPTION_KEY=voyra-password-key:latest \
  --set-secrets DB_PASSWORD=voyra-db-password:latest \
  --min-instances 1 \
  --cpu-boost \
  --no-allow-unauthenticated
```

`--no-allow-unauthenticated` assumes the frontend calls through an authenticated proxy or
you'll layer on Cloud Run IAM / a load balancer. Switch to `--allow-unauthenticated` if the
API is meant to be called directly from a browser (it still enforces its own JWT auth on every
route except `/api/auth/**` and `/api/public/**`).

**`--min-instances 1` is deliberate**: it keeps one warm instance running, so the per-tenant
Flyway catch-up migration and the in-memory cache loaders (`TenantCache`, `AgentCache`,
`PlatformAdminCache`) aren't repeated on every cold start. Once traffic justifies scaling to
zero, revisit `RUN_TENANT_MIGRATIONS` (move the catch-up to a one-off Cloud Run Job instead)
and the cache-consistency note below.

## Scheduled jobs need CPU always allocated

The subscription lifecycle, gateway reconciliation, booking poll and repair, and purge jobs are
`@Scheduled` tasks inside the app process. Deploy with `--no-cpu-throttling` and `--min-instances 1`
(or more); with request-only CPU they pause between requests and do not run reliably.

## Connection pool

Each instance keeps up to `DB_POOL_SIZE` connections (default 10, base config and `cloudrun`
profile). Keep `instances x DB_POOL_SIZE`, doubled for the overlap of a rolling deploy, under the
database's connection limit: a 1 GB Cloud SQL instance allows about 22, so one instance at 10 fits,
two need `DB_POOL_SIZE=5`.

## Tripjack live switch

`FLIGHT_BOOKING_PROVIDER=tripjack` or `HOTEL_BOOKING_PROVIDER=tripjack` makes the app refuse to
start, and saving a PROD Tripjack credential returns 409, until platform setting
`tripjack_live_ready` is true. One platform admin requests it
(`POST /api/platform/settings/tripjack_live_ready/requests` with `{"value":"true"}`), a second
admin approves it (`POST /api/platform/settings/requests/{id}/approve`); both steps are audited.
Turning it back to `false` is a plain setting change. Work through the "Before enabling ...=tripjack"
checklists in the B2B PR description first.

## Pre-deploy checklist

- [ ] All three Secret Manager values are the real production secrets, not copies of local `.env`.
- [ ] `SUPER_ADMIN_EMAIL` / `SUPER_ADMIN_PASSWORD` are set for the first boot (see
      `PlatformAdminSeedRunner`); demo seeding has been removed from the codebase.
- [ ] `CORS_ALLOWED_ORIGINS` is set to the real frontend origin(s), not `localhost`.
- [ ] The old demo `SUPER_ADMIN` (`admin@travelos.com`) does not exist in the production
      database - delete the row if it was seeded during an earlier test deploy.
- [ ] `gcloud sql instances describe` confirms automated backups are enabled on the Cloud SQL
      instance before the first real tenant is onboarded.

## Known limitations (accepted for v1)

**In-memory caches are per-instance.** `TenantCache`, `AgentCache`, and `PlatformAdminCache`
(blueprint §8.10) are static `ConcurrentHashMap`s local to each running instance. A write that
updates one instance's cache (e.g. deactivating an agent) takes effect on that instance
immediately, but other instances only pick it up after they restart or are replaced. With
`--min-instances 1` and the traffic this service currently handles, that's a single instance in
practice, so the inconsistency window is effectively zero. If this scales out to multiple
concurrent instances, revisit blueprint §8.10 rule 4 explicitly — either accept the eventual-
consistency window (bounded by instance restart/redeploy cadence) or move these caches to a
shared store (e.g. Redis).

**JWT lifecycle is short-lived-token-only, no refresh.** Tokens are stateless, 24-hour-lived
(`app.jwt.expiration-ms`, default 86400000ms), with no refresh-token flow and no logout
endpoint. Revocation works via the per-request active-flag check in `JwtAuthenticationFilter`
(a deactivated Agent's or Tenant's token stops working on their very next request, independent
of token expiry), which covers the "deactivate this account" case. It does not cover "a token
was stolen but the account should keep working" — there is no way to revoke one specific token
without deactivating the whole account. This was an accepted v1 scope decision, not an
oversight; the upgrade path when it's needed is a short-lived access token (e.g. 15 minutes)
paired with a longer-lived, server-tracked refresh token.
