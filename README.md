# TalentShift Core MVP

## Merged local architecture

The integration-hub frontend is the only active frontend. The previous frontend is retained in
`frontend.project-a-backup/` for recovery and is not built or served.

- Frontend development server: `http://127.0.0.1:5174`
- Spring Boot API: `http://127.0.0.1:8081`
- PostgreSQL: `127.0.0.1:5435`

The backend combines the public job collection pipeline with integration-hub capabilities:
immutable raw records, import batches, job lineage, manual deduplication review, connected
systems, sync checkpoints, audit history, merge history, and an idempotent delivery outbox.
Credential-backed collectors and AI are disabled by default. Public ATS, official careers,
JSON-LD, RSS, and permitted public-web collectors continue to operate without API keys.

AI can be added later by implementing `AiDiscoveryProvider` in `IntegrationHubModule.java` and
setting `app.ai.enabled=true`. Its default fast profile matches the keyless collector profile:
24 global workers, 2 requests per domain, 2,500 records per batch, and `0` for no job ceiling.

TalentShift Core is the focused rebuild of the job-discovery product. The previous implementation remains recoverable in Git history; this branch intentionally contains only the approved MVP.

## Product scope

- Main page
- Candidate login
- Automated job collection from configured public sources
- Saudi Arabia relevance filtering and a configurable 4,000-job inventory target
- PostgreSQL job storage with duplicate prevention
- Jobs page with keyword, location, type, source, and remote filters
- Job detail view with the original direct-apply URL
- Verified Saudi employer directory with official career links
- Job-count status bar
- Candidate profile and CV upload
- CV profile agent that extracts reusable candidate information
- Optional profile-based job matching
- Plans page with frontend-only selection logic

There is no payment backend in this MVP.

## Tool responsibilities

| Tool | Responsibility |
|---|---|
| Docker Compose | Starts the complete local environment consistently. |
| PostgreSQL | Stores users, sessions, jobs, profiles, CV metadata, and collection runs. |
| pgAdmin 4 | Inspects and manages PostgreSQL data. It is not used for API testing. |
| Swagger UI | Tests and documents one backend endpoint at a time. |
| Bruno | Saves repeatable multi-request API workflows and team collections. |
| Automated tests | Verify startup, migrations, public APIs, normalization, and packaging without manual endpoint testing. |

## Start the environment

Copy `.env.example` to `.env`, change every password and key, then run:

```powershell
./scripts/start-local.ps1
```

Or run directly:

```powershell
docker compose up --build -d
```

Open:

- Application: `http://localhost:8080`
- Swagger UI: `http://localhost:8080/swagger-ui.html`
- pgAdmin: `http://localhost:5050`

## Job collection

The backend collection worker runs continuously and checks its permanent `job_sources` registry every minute. Each registered source has its own safe refresh interval, permission state, synchronization metadata, success/failure history, and next retry time. Supported paths are Greenhouse, Lever, Ashby and SmartRecruiters public endpoints; JobPosting JSON-LD; RSS/Atom; conservative HTML links; and an optional browser-renderer fallback. Browser rendering is disabled unless `JOB_BROWSER_RENDERER_URL` is explicitly configured, and it can only receive an already approved registry URL.

Collection prefers official employer and ATS data, then structured metadata, then public HTML. It identifies ATS providers once rather than rediscovering them on every run. Requests use a named user agent, robots checks, timeouts, redirect limits, compressed responses, response-size limits, ETag/Last-Modified caching, SSRF protection, global and per-domain concurrency limits, provider `Retry-After`, and exponential backoff. A failed source does not stop other sources.

To collect at the volume needed for the 4,000-job target, [create a Jooble API key](https://jooble.org/api/about) and set `JOOBLE_API_KEY`. The collector sends only `location: Saudi Arabia` queries. It uses up to 100 requests for the initial backfill and one request per three-hour refresh. Request usage is stored in PostgreSQL and cannot exceed `JOOBLE_REQUEST_BUDGET=500`, including after restarts. The default does not automatically reset that per-key quota; set `JOOBLE_BUDGET_RESET_DAYS=30` only if the provider confirms that your plan resets monthly.

Arbeitnow and Remotive are supplemental public feeds. Saudi-located roles are accepted; fully remote roles outside Saudi Arabia are accepted only when their text meets the configured English/Arabic remote policy. The target is reported by `GET /api/jobs/count`; the application never generates fake jobs to make the number appear complete.

Remotive listings retain the Remotive direct URL and source attribution. Review each provider's current terms before production deployment.

The Companies page is backed by `GET /api/companies` and seeds only official Saudi employer career pages. The public HTML connector starts with Saudia's official career listing, checks `robots.txt`, stays on the approved host, and never accesses apply/account routes prohibited by robots rules. Additional companies are enabled only after their recruitment platform and access policy are verified.

Tavily discovery does not run on a schedule. An administrator starts it from the **Manual AI web search** button on the Home page or by calling `POST /api/admin/jobs/agent-search`. Configure `TAVILY_API_KEY` in `.env`; never place it in frontend code or commit it. Tavily results pass through the same Saudi-only validation and deduplication as every other source.

Source discovery and job collection are separate. When Tavily verifies a public Lever or Greenhouse board, it registers the board for future backend collection. Tavily is never required for scheduled collection. Sources with unknown or blocked permission are never fetched.

An authorized discovery agent can submit jobs found on other public sources in batches of up to 500. The backend applies the same Saudi-only validation, direct-link validation, and global duplicate key (normalized title + company + location) before storing them:

```http
POST /api/admin/jobs/ingest
Cookie: TS_SESSION=<administrator-session>
X-XSRF-TOKEN: <csrf-token>
Content-Type: application/json

{"jobs":[{"source":"PUBLIC_SOURCE","externalId":"123","title":"Java Engineer","company":"Example","location":"Riyadh, Saudi Arabia","applyUrl":"https://employer.example/jobs/123"}]}
```

Every accepted job must include an HTTP(S) direct application URL. The Jobs API returns that URL and the **View role** page uses it for the **Apply directly** button. Jobs are bulk-upserted in batches, deduplicated globally, and source quality decides which duplicate keeps the canonical link (official employer sources win over aggregators and web discovery). Content changes requeue enrichment without blocking collection.

Lifecycle handling is deliberately conservative. A role missing from a complete source snapshot first becomes `PENDING_RECHECK` and expires only after a second missing observation. Age expiry is checked every three hours. At 12:00 Asia/Riyadh each day, the verifier rechecks every active canonical application URL; only repeated definitive 404/410 responses expire a link. Temporary network and provider failures do not delete jobs.

Enrichment runs asynchronously after storage. It currently provides deterministic category, seniority, and a short clean summary while maintaining `PENDING`, `PROCESSING`, `COMPLETE`, and `FAILED` states. This boundary allows a future AI enricher to be added without making collection dependent on an AI provider.

Manual collection:

```http
POST /api/admin/jobs/collect
Cookie: TS_SESSION=<administrator-session>
X-XSRF-TOKEN: <csrf-token>
```

Operational source status and fetch totals:

```http
GET /api/admin/job-sources
Cookie: TS_SESSION=<administrator-session>
```

All administration and integration endpoints require the single authenticated administrator account. Candidate accounts are denied at the server even if they call those URLs directly, and state-changing administrator requests require CSRF protection.

## pgAdmin

The server is pre-registered as **TalentShift PostgreSQL**. Use host `postgres`, port `5432`, and the database credentials from `.env`.

## Swagger and Bruno

Use Swagger for individual API exploration and schema inspection. Open the `bruno` directory in Bruno for the saved CSRF, login, collection, filtering, profile, and CV workflows.

## Automated verification

```powershell
mvn clean verify
docker compose config --quiet
docker build -t talentshift-core:local .
```

The integration test uses a disposable PostgreSQL Testcontainer and applies the production Flyway migration. GitHub Actions also uploads the Maven log and test reports as the `core-verification-reports` artifact on every run, including failed runs.

## Security baseline

- BCrypt password hashing
- Login rate limiting and generic authentication errors
- Random opaque session tokens; only SHA-256 hashes are stored
- HttpOnly, SameSite session cookies
- CSRF protection for state-changing requests
- Restrictive Content Security Policy and browser security headers
- Parameterized SQL through Spring JDBC
- File size, extension, filename, and path validation for CV uploads
- Job descriptions rendered as text rather than executable HTML
- Server-enforced candidate/administrator role separation and a database constraint allowing only one administrator
- Environment-based secrets

Local demo sign-in is controlled by `APP_DEMO_USER_ENABLED`. When enabled, the configured demo password is synchronized at startup, so changing `.env` no longer leaves an old password in the database. Disable the demo user in production and set `COOKIE_SECURE=true` behind HTTPS.

Create or rotate the one administrator account at startup with `APP_ADMIN_USER_ENABLED=true`, `APP_ADMIN_EMAIL`, and `APP_ADMIN_PASSWORD`. The database permits only one row with the `ADMIN` role; later startup configuration updates that same account rather than creating another administrator.

## Architecture

```text
Approved source registry -> ATS/JSON-LD/RSS/HTML adapters -> validation + bulk dedup -> PostgreSQL
PostgreSQL -> asynchronous enrichment + lifecycle verification -> Jobs API -> direct apply URL
Manual Tavily discovery -> verified jobs and public ATS sources -> same validation pipeline
CV upload -> safe storage -> text extraction -> profile agent -> candidate profile -> optional job matching
```
