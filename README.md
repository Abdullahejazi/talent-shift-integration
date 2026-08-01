# Talent Shift Integration Hub

A standalone Java 21 / Spring Boot service that imports source and job records, preserves immutable raw payloads, normalizes and deduplicates them, routes uncertain matches to review, and stages canonical jobs for eventual transfer to Talent Shift.

No existing system is modified. All inbound and outbound integrations are mocks. No real secrets are included.

## Architecture and processing rules

1. `JobSystemProvider` fetches cursor pages of source records, followed by job records.
2. The exact serialized response DTO is inserted as JSONB into `raw_source` or `raw_job`. Rows are never updated except for processing status and are never deleted by the application.
3. SHA-256 checksums plus `(connected_system_id, external_record_id, checksum)` constraints make unchanged re-imports no-ops while retaining changed versions.
4. Sources are normalized and canonicalized before jobs. Strong source matches are normalized URL, ATS+tenant, or domain+organization. Domain-only and organization-only matches go to review.
5. Jobs are matched in order: canonical source+external ID, normalized job/apply URL, deterministic fingerprint, then Jaro-Winkler similarity. Only strong matches merge automatically.
6. `source_observation` and `job_observation` retain every raw-to-canonical relationship, original system/record ID, reason, and confidence.
7. Rejected candidates become separate canonical records. Raw records remain intact.
8. The outbox uses a stable unique idempotency key. Each invocation is recorded in `transfer_attempt`; failures use exponential backoff and can be retried manually.

Concurrency is controlled by a transaction-scoped PostgreSQL advisory lock per connected system, `SELECT ... FOR UPDATE` checkpoint locking, `FOR UPDATE SKIP LOCKED` work claiming, and database unique constraints. Imports are restartable from `sync_checkpoint`.

## Prerequisites

- Java 21
- Docker Desktop or another Docker runtime

The Maven Wrapper is included, so a separate Maven installation is not required.

## Run locally

```powershell
docker compose up -d
.\mvnw.cmd spring-boot:run
```

Swagger UI: `http://localhost:8080/swagger-ui.html`

Default local basic authentication is `admin` / `change-me`. Override it before using a shared environment:

```powershell
$env:HUB_USERNAME = "operator"
$env:HUB_PASSWORD = "a-local-development-password"
```

Import the supplied mock data:

```powershell
curl.exe -u admin:change-me -X POST http://localhost:8080/api/v1/imports/mock
```

## Future API configuration

Configuration placeholders are in `src/main/resources/application.yml`. Supply values as environment variables; do not commit secrets:

| Integration | URL variable | credential variable |
|---|---|---|
| Existing system one | `SYSTEM_ONE_URL` | `SYSTEM_ONE_TOKEN` |
| Existing system two | `SYSTEM_TWO_URL` | `SYSTEM_TWO_TOKEN` |
| Existing system three | `SYSTEM_THREE_URL` | `SYSTEM_THREE_TOKEN` |
| Talent Shift | `TALENT_SHIFT_URL` | `TALENT_SHIFT_TOKEN` |

To add each real inbound connector:

1. Implement `integration.client.JobSystemProvider`.
2. Give it a stable `systemKey()`.
3. Map the remote API response to `SourceRecordDto`, `JobRecordDto`, and `CursorPage`.
4. Put real implementations behind the `real-integrations` Spring profile (the mocks use `!real-integrations`).
5. Read URL/auth values from `HubProperties`; never add keys to source code.

To add Talent Shift, implement `TalentShiftClient` behind the same profile. Preserve the supplied idempotency key in the outbound request. The existing `TransferService`, outbox, retry, and attempt audit logic do not need to change.

## REST / Swagger endpoints

All endpoints are under `/api/v1` and require HTTP Basic authentication.

| Method | Path | Purpose |
|---|---|---|
| POST | `/imports/mock` | Import mock sources and jobs |
| POST | `/imports` | Start a batch for a configured provider |
| GET | `/imports` | View import batches and status |
| GET | `/raw/sources` | View immutable received source payloads |
| GET | `/raw/jobs` | View immutable received job payloads |
| GET | `/canonical/sources` | View canonical sources |
| GET | `/canonical/jobs` | View canonical jobs |
| GET | `/deduplication/decisions` | View reasons and confidence scores |
| GET | `/reviews?status=PENDING` | View uncertain matches |
| POST | `/reviews/{id}/approve` | Merge a reviewed match |
| POST | `/reviews/{id}/reject` | keep the record separate |
| GET | `/sync/status` | View cursors and checkpoint versions |
| POST | `/outbox/jobs/{jobId}` | Idempotently stage a canonical job |
| GET | `/outbox` | View Talent Shift outbound records |
| GET | `/outbox/{id}/attempts` | View transfer attempt history |
| POST | `/outbox/{id}/retry` | Retry a failed outbound record |
| POST | `/outbox/send-ready` | Process ready outbox records using the mock client |

The OpenAPI document is available at `/v3/api-docs`.

## Database migrations

- `V1__initial_schema.sql` creates all 13 requested tables, foreign keys, observation constraints, outbox idempotency, and the PostgreSQL `pg_trgm` extension.
- `V2__locking_and_search_indexes.sql` adds lookup/review/fuzzy-search indexes and documents raw-table immutability.

Flyway runs automatically. Hibernate uses `ddl-auto: validate`; it never creates or mutates the schema.

## Tests

Run everything:

```powershell
docker info
.\mvnw.cmd clean test
```

The unit suite covers URL/source matching, external IDs, normalized job URLs, fingerprints, fuzzy review routing, and records that remain separate. `HubPostgresIntegrationTest` uses PostgreSQL 16 via Testcontainers and covers repeated imports, concurrent imports, immutable/checksum behavior, and idempotent transfer attempts. It is skipped automatically when Docker is unavailable.

## Project structure

```text
.
├── .mvn/wrapper/
├── compose.yml
├── pom.xml
├── mvnw / mvnw.cmd
├── README.md
└── src
    ├── main
    │   ├── java/com/talentshift/hub
    │   │   ├── TalentShiftIntegrationHubApplication.java
    │   │   └── integration
    │   │       ├── client/          # DTOs, provider/client contracts, mocks
    │   │       ├── importer/        # cursor/checksum import and canonicalization
    │   │       ├── normalization/   # URL/domain/text normalization
    │   │       ├── deduplication/
    │   │       │   ├── source/      # source confidence policy
    │   │       │   └── job/         # job priority policy and fingerprints
    │   │       ├── canonical/       # JPA entity/repository
    │   │       ├── review/          # approve/reject workflow
    │   │       ├── transfer/        # outbox, retry, attempt audit
    │   │       ├── config/          # properties, security, OpenAPI, REST
    │   │       └── exception/       # typed API errors
    │   └── resources
    │       ├── application.yml
    │       └── db/migration/
    │           ├── V1__initial_schema.sql
    │           └── V2__locking_and_search_indexes.sql
    └── test/java/com/talentshift/hub
        ├── HubPostgresIntegrationTest.java
        └── integration/
            ├── normalization/NormalizationServiceTest.java
            └── deduplication/{source,job}/
```

## Production notes

This is intentionally not deployed. Before production use, replace basic authentication with the organization’s identity provider, encrypt integration credentials in a secret manager, add TLS and monitoring, calibrate similarity thresholds with representative data, and run migrations with a database role allowed to create `pg_trgm`.
