# TalentShift Integration Hub

A standalone, high-performance job and talent aggregation hub engineered to collect, verify, deduplicate, and deliver genuine Saudi Arabian employment opportunities and verified candidate profiles to the origin company system.

---

## 🏛️ System Architecture & Data Flow

```text
┌─────────────────────────────────────────────────────────┐
│                      DATA INGESTION                     │
│  • Manual Admin Entry (Career Portals)                  │
│  • Batch REST API Ingestion (/api/admin/jobs/ingest)     │
│  • Automated ATS & Corporate Feeds (141 Sources)        │
└────────────────────────────┬────────────────────────────┘
                             │
                             ▼
┌─────────────────────────────────────────────────────────┐
│              TALENTSHIFT INTEGRATION HUB                │
│  • 18 Saudi Source Categorization Hierarchy             │
│  • Automated Link Health & Expiry Engine                │
│  • SHA-256 Deduplication & Saudi Location Policy        │
│  • Direct LinkedIn Profile Verification (Candidates)    │
│  • PostgreSQL Relational Storage                        │
└────────────────────────────┬────────────────────────────┘
                             │
                             ▼
┌─────────────────────────────────────────────────────────┐
│                 ORIGIN SYSTEM CONSUMPTION               │
│  • REST API: GET /api/jobs (Active verified jobs)       │
│  • REST API: GET /api/candidates (Candidate talent pool)│
│  • REST API: GET /api/jobs/count (Live metrics)         │
└─────────────────────────────────────────────────────────┘
```

The system operates as an **independent backend & admin hub** hosted on the company's server. Because it resides securely within the internal infrastructure, external AI tools and scrapers feed data in, and the hub continuously validates and cleanses the database before serving it to the main application ("Origin System").

---

## ⚙️ How the System Works

### 1. Ingestion Layer
* **External AI Collection:** The administrator gathers fresh jobs and company career portals using AI tools and imports them directly through the Admin Portal (`#approvals`), batch ingestion API (`POST /api/admin/jobs/ingest`), or direct database imports.
* **Continuous Background Polling:** The system periodically checks configured public ATS platforms (Workday, Greenhouse, Ashby, Lever, SmartRecruiters) and official company career sites.

### 2. Verification & Automated Cleaning Layer
* **Daily Job Link Verification (`JobLinkVerificationService`):**
  Runs automatically daily at 12:00 PM (Asia/Riyadh) or on-demand via the *"Recheck sources"* dashboard button. It checks every application link; if a job returns HTTP 404/410 or displays *"No longer accepting applications"*, it is moved to `EXPIRED` status.
* **Source Health Maintenance (`SourceMaintenanceService`):**
  Runs every 90 minutes to ensure career portals remain accessible, pruning invalid or dead endpoints.
* **Automated Deduplication (`JobDeduplicationService`):**
  Runs every 10 minutes using canonical URL matching and SHA-256 content hashing to ensure zero duplicate job postings exist in the database.

### 3. Origin System Consumption Layer
The main company system consumes clean, verified, and deduplicated records via lightweight, high-speed REST APIs.

---

## 📡 REST API Reference for Origin System

### Public & Application APIs

| Endpoint | Method | Description |
| :--- | :--- | :--- |
| `/api/jobs` | `GET` | Paginated search of verified active jobs. Parameters: `keyword`, `location`, `type`, `remote`, `page`, `size`. |
| `/api/jobs/count` | `GET` | Live counters for total active jobs and target metrics. |
| `/api/jobs/{id}` | `GET` | Detailed job description, requirements, company info, and direct application URL. |
| `/api/candidates` | `GET` | Verified tech candidate profiles with live direct LinkedIn links. |

### Administrative & Ingestion APIs

| Endpoint | Method | Description |
| :--- | :--- | :--- |
| `/api/auth/login` | `POST` | Authenticates administrator or candidate session. |
| `/api/admin/jobs/ingest` | `POST` | Ingests external job batches into the verification pipeline. |
| `/api/admin/jobs/collect` | `POST` | Triggers an immediate collection run across all active sources. |
| `/api/admin/job-sources/recheck` | `POST` | Triggers link health verification for all jobs and sources. |
| `/api/admin/job-sources/status` | `GET` | Operational metrics (active jobs, healthy sources, last collection). |
| `/api/admin/job-sources/categories` | `GET` | Live breakdown across the 18 Saudi source categories. |
| `/api/admin/job-sources/expired-jobs` | `GET` | History of expired and archived job records. |

---

## 🚀 Getting Started & Deployment

### Launching with Docker Compose

```powershell
docker compose up -d
```

### Access Points

* **Admin Portal (Hot-Reload Dev):** [http://localhost:5174](http://localhost:5174)
* **Production App & API:** [http://localhost:8080](http://localhost:8080)
* **Swagger API Documentation:** [http://localhost:8080/swagger-ui.html](http://localhost:8080/swagger-ui.html)
* **PostgreSQL Database:** Port `5434` (Database: `talentshift`, User: `talentshift`)
* **pgAdmin Database Dashboard:** [http://localhost:5050](http://localhost:5050)

### Default Admin Credentials
* **Email:** `admin@talentshift.ai`
* **Password:** `ChangeThisDemoPassword123!`
