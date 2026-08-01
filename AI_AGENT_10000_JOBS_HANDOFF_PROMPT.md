# TalentShift — AI Agent Handoff Prompt for 10,000 Verified Jobs

You are continuing development and operation of the TalentShift job-collection platform from its current state. Work autonomously and persist until the database contains at least **10,000 unique, active, eligible jobs**. Do not restart the project from scratch and do not replace its architecture or frontend design.

## Project and current state

- Project: `C:\Users\a6711\IdeaProjects\Talent-Shift-project`
- Runtime: Spring Boot from IntelliJ/Maven on port `8080`; PostgreSQL is in Docker container `talentshift-core-postgres-1`.
- At handoff on 2026-07-27, the system had approximately 974 enabled sources and 1,531 active Saudi-relevant jobs. Query the database first because collection continues automatically.
- The backend already supports Greenhouse, Lever, Ashby, SmartRecruiters, official career pages, Jooble, Arbeitnow, Remotive, seed imports, Tavily discovery, deduplication, expiry checks, and direct-link verification.
- The configured target is 4,000 for the current milestone. Your final target is 10,000.

## Non-negotiable acceptance rules

1. Store only real, currently active jobs.
2. Accept jobs located in Saudi Arabia, or fully remote jobs available to candidates in Saudi Arabia whose job text is English or Arabic.
3. Every job must contain a working direct application URL. Prefer the employer's application form or official ATS posting. Do not accept YouTube pages, articles, search-result pages, generic homepages, events, courses, or unrelated content.
4. Respect robots.txt, published public APIs/RSS feeds, rate limits, and website terms. Do not bypass authentication, CAPTCHAs, paywalls, access controls, or anti-bot protections.
5. Never fabricate a source or job. Never label an untested source as verified.
6. Automatically deduplicate jobs across different sources using canonical apply URL, ATS identity, and normalized company/title/location. Updating an existing job is not a new insertion.
7. Preserve CSRF protection for browser/account actions and all current security controls. Never print or commit secrets from `.env`.

## Required workflow

1. Inspect the current code, database counts, latest collection runs, source failures, and uncommitted user changes before editing.
2. Fix collector defects before increasing traffic. In particular, retain the correct handling of HTTP `304 Not Modified` before generic redirect handling.
3. Finish validating pending `seed_candidates`. Promote a source only after its public endpoint returns at least one active job with an application URL.
4. Discover additional sources yourself using public web search and official ATS/company career pages. Prioritize Saudi employers, GCC employers hiring in Saudi Arabia, and worldwide employers offering unrestricted remote roles.
5. Add new collection methods only when the provider explicitly offers a public API/RSS feed or permits access. Good source classes include official ATS APIs and attributed public remote-job feeds. Verify each provider's current terms before implementation.
6. Run backend collection across all enabled sources. Do not impose artificial application-level source or job limits, but retain safe concurrency, response-size, retry, circuit-breaker, and provider-rate-limit controls.
7. After each pass, measure: enabled verified sources, successful/failing sources, returned jobs, inserted jobs, updated jobs, policy rejections, duplicate merges, expired jobs, and active eligible jobs.
8. Diagnose low growth with evidence. Improve location parsing for legitimate Saudi variants and remote availability metadata, but do not weaken the Saudi/global-remote policy simply to inflate counts.
9. Recheck application links and expiry every three hours. Disable consistently dead sources and expire unavailable jobs without deleting audit history.
10. Continue in measured batches until the query below returns at least 10,000. If public, compliant sources are exhausted, report the evidence and exact external limitation; do not invent records.

## Measurement queries

Use PostgreSQL queries equivalent to:

```sql
SELECT count(*)
FROM jobs
WHERE status = 'ACTIVE'
  AND saudi_relevant = true;

SELECT count(*) AS total_sources,
       count(*) FILTER (WHERE enabled) AS enabled_sources,
       count(*) FILTER (WHERE last_success_at IS NOT NULL) AS successful_sources
FROM job_sources;

SELECT source, count(*)
FROM jobs
WHERE status = 'ACTIVE' AND saudi_relevant = true
GROUP BY source
ORDER BY count(*) DESC;
```

## Verification before completion

- Compile and run relevant automated tests.
- Start the application and confirm health on `http://127.0.0.1:8080/actuator/health`.
- Confirm the jobs API and frontend show the same active-job count.
- Sample application URLs from every source type and confirm they resolve to active job/application pages.
- Confirm no duplicate canonical application URLs remain.
- Provide a concise final report listing the final verified count, source count, inserted/updated/rejected totals, failures, code changes, and any provider limitations.

Begin by querying the live database and reading the latest collection-run records. Continue from the existing state—do not re-import or reprocess completed work unnecessarily.
