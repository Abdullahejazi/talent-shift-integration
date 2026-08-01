# Security baseline

TalentShift is designed around OWASP ASVS-style application controls and the NIST Secure Software Development Framework. This is an engineering baseline, not a certification.

## Implemented controls

- BCrypt password hashing with cost 12; opaque random session tokens are stored only as SHA-256 hashes.
- HttpOnly, SameSite session cookies; secure cookies are supported with `COOKIE_SECURE=true`.
- CSRF tokens on state-changing browser requests and a restrictive Content Security Policy. Machine-only job collection and ingestion use `X-Admin-Key` instead of browser cookies and are excluded from CSRF processing.
- Generic login errors and per-IP/email login throttling.
- Parameterized database access, validated pagination limits, and HTTP(S)-only outbound job links.
- Source attribution, Saudi relevance filtering, duplicate prevention, and stale-listing retirement.
- CV size, extension, filename, and storage-path checks; extracted text and job descriptions are rendered as text.
- Non-root production container, pinned CI actions, automated tests, migration validation, and environment-based secrets.

## Production requirements

- Disable `APP_DEMO_USER_ENABLED`, replace every example secret, and set `COOKIE_SECURE=true` behind HTTPS.
- Restrict Swagger/OpenAPI and management endpoints to operators or disable them publicly.
- Put the application behind a trusted reverse proxy with request limits and centralized rate limiting.
- Use a managed secret store, encrypted backups, database least privilege, log monitoring, dependency scanning, and an incident-response process.
- Confirm each job provider's API terms before enabling it. Do not scrape employer pages without explicit permission.

## Remaining assurance work

- The in-memory login limiter is suitable for one instance; multi-instance production needs a shared limiter.
- Add SAST, software-composition analysis, DAST, SBOM generation, and periodic penetration testing before a public launch.
- Add malware scanning and sandboxed document parsing if CV uploads are exposed to untrusted public traffic.
- Complete privacy, retention, deletion, and Saudi PDPL legal review for candidate profiles and CVs.

Report suspected vulnerabilities privately to the project owner. Do not include secrets, CVs, or personal data in public issues.
