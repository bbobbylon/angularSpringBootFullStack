# TesseraApp — Project History & Progress Archive

**Version:** 1.0
**Last Updated:** 2026-07-25
**Status:** Living archive — the single place to "look back" at how this app was built over time.

## Purpose

This file is the **retrospective** counterpart to the forward-looking [`ROADMAP.md`](../../ROADMAP.md).
It exists so the *progress made over time* is captured in one readable place instead of scattered
across retired planning files (now deleted) and one-off comparison docs. Nothing here is a plan or a
TODO — those live in `ROADMAP.md`. This is the story and the receipts.

**Companion living docs (current, not history):**
- [`ROADMAP.md`](../../ROADMAP.md) — everything planned / deferred / TODO.
- [`branch-changelog.md`](../../branch-changelog.md) — authoritative commit-by-commit record of the branch.
- [`documentation/README.md`](../README.md) — hub for all current guides.
- [`documentation/project-status-and-roadmap.md`](../project-status-and-roadmap.md) — built-vs-documented reconciliation with `file:line` evidence.

---

## 1. The story so far (narrative)

**Origins.** The codebase began life as a follow-along of a several-year-old "Get Arrays" full-stack
tutorial (Angular SPA + Spring Boot REST API, MySQL, JWT auth) branded **SecureCapita**. The early
goal was simply to finish and modernise that tutorial.

**Modernisation.** The stack was pulled up to current: **Angular (latest, standalone components)** on
the front, **Spring Boot 4 / Java 21** on the back, with the core identity/auth domain deliberately
built on **`NamedParameterJdbcTemplate`** (hand-written SQL + row mappers) rather than JPA, while the
CRUD-heavy business domain (customers/invoices/services) stayed on JPA. Legacy patterns were migrated
to modern equivalents throughout.

**The CIAM / zero-trust overhaul (M0–M7).** The largest arc of work turned a basic login into a real
customer-identity platform: design-token UI system, full auth screens, **federated OAuth2/OIDC login**
(Google/GitHub/Microsoft, with account linking), **authenticator-app TOTP MFA**, **server-side refresh
sessions with rotation + reuse detection**, a session/device management panel, **organization-scoped
admin access**, a roles×permissions matrix, and finally route transitions + a ⌘/Ctrl-K command palette.

**Rebrand.** SecureCapita became **TesseraApp** (frontend rebrand, commit `0a2f3ea`, 2026-06-18),
alongside new billing and services-catalog features.

**Production hardening.** `TokenProvider` stopped logging the signing key; the app moved to
environment-driven config with `dev`/`prod`/`qa`/`stage` profiles; prod runs `ddl-auto=validate`
against a `schema.sql`-owned database; general **rate limiting** (Bucket4j, 429 + `Retry-After`),
security headers (CSP/Referrer-Policy/Permissions-Policy), and **per-account brute-force lockout** were
added. **Flyway was removed on purpose** (its baseline bookkeeping kept desyncing and wedging startup);
the schema is now a single idempotent `src/main/resources/schema.sql`.

**Cloud & CI/CD.** A single env-driven `Dockerfile`, `docker-compose`, GitHub Actions (build/test +
ECR/ECS deploy), **S3 image storage** abstraction, and an **Aiven** managed-MySQL option landed. Aiven
is DB-only (never hit in CI; credentials live in AWS Secrets Manager for ECS).

**Recent (2026-07).** Login-500 incident hardening (audit writes made non-fatal), analytics/billing
authorization boundary closed server-side (`AnalyticsController`), console-only auth/RBAC diagnostics,
a friendly "contact your admin" permission-denied UX, and `schema.sql` made **portable + drift-proof**
(idempotent CHECK-constraint rebuilds after a stale-constraint failure surfaced against Aiven `db3`).

---

## 2. Milestones (M0–M7)

| Milestone | Theme | Status |
|---|---|---|
| **M0** | Design tokens + base UI system (dark/indigo) | ✅ |
| **M1** | Auth screens (login/register/reset/verify) overhaul | ✅ (polished over time) |
| **M2** | Dashboard / insights | 🔄 (ongoing polish) |
| **M3** | Roles × permissions matrix (RBAC visibility) | ✅ |
| **M4** | Authenticator-app TOTP MFA | ✅ |
| **M5** | Sessions & devices + refresh rotation / reuse detection | ✅ |
| **M6** | In-house CIAM / zero-trust hardening | ✅ (core), ongoing |
| **M7** | Route transitions + ⌘/Ctrl-K command palette | ✅ |

*(Milestone lineage is preserved from the retired `plan.md` / `week-5-plan.md`; see the registry below.)*

---

## 3. Major capabilities delivered (by area)

Condensed from the branch comparison snapshot (Appendix A) and `ROADMAP.md §6`.

- **Auth / identity:** JWT access + server-side refresh sessions; federated OAuth2/OIDC (Google/GitHub/
  Microsoft) with account linking; TOTP MFA with recovery codes; per-account brute-force lockout + admin unlock.
- **RBAC:** permission-string authorities (`READ:USER`, `UPDATE:CUSTOMER`, `UPDATE:ROLE`, …); org-scoped
  admin; roles×permissions matrix; route guards as UX + server-enforced boundary.
- **Business domain:** customers, invoices, services catalog, billing overview, analytics hub, XLSX reports.
- **Platform:** rate limiting; security headers; S3 image storage; multi-env config + CI/CD; Aiven option.
- **Data:** single idempotent `schema.sql` (Flyway removed); JDBC identity core + JPA business domain;
  `JpaSchemaSyncTest` offline drift guard.

---

## 4. Retired & legacy documents (registry)

Where the old planning/one-off docs went. The retired planning files were **consolidated into
`ROADMAP.md`** on 2026-07-24 and deleted; they remain fully recoverable from git history.

| Document | What it was | Status / where it went | Recover with |
|---|---|---|---|
| `plan.md` | Original approved UI + auth master plan (M0–M7) | Consolidated → `ROADMAP.md`; deleted 2026-07-24 | `git log --all -- plan.md` |
| `phase2-proposals.md` | Phase-2 proposals (user-type classification, batch upload, M2M API) | Consolidated → `ROADMAP.md §3`; deleted | `git log --all -- phase2-proposals.md` |
| `rollout-plan.md` | Rollout / sequencing plan | Consolidated → `ROADMAP.md`; deleted | `git log --all -- rollout-plan.md` |
| `assignments/week-5-plan.md` | Near-term weekly roadmap slice | Consolidated → `ROADMAP.md §1`; deleted | `git log --all -- assignments/week-5-plan.md` |
| `BRANCH_COMPARISON.md` | One-off `master` vs branch migration writeup (2026-06-28) | Snapshot preserved in **Appendix A**; deleted 2026-07-27 | `git log --all -- BRANCH_COMPARISON.md` |
| `branch-changelog.md` | Chronological record of everything that landed on the branch | Timeline preserved in **Appendix B**; deleted 2026-07-27 | `git log --all -- branch-changelog.md` |
| `documentation/project-status-and-roadmap.md` | Built-vs-documented reconciliation against the submitted deliverables | Superseded — its "actual" column is now the shipped app, and its backlog moved to `ROADMAP.md`; deleted 2026-07-27 | `git log --all -- documentation/project-status-and-roadmap.md` |
| `assignments/architecture.md` | Early assignment architecture doc | Superseded by [`documentation/architecture.md`](../architecture.md) | still in working tree |

> **Consolidation policy (2026-07-27).** There is now exactly **one live planning document** —
> [`ROADMAP.md`](../../ROADMAP.md) — and exactly **one archive**, this file. Everything retired has
> its essential content preserved in an appendix below, and every deleted file remains recoverable
> from git history. The point is that a reader never has to work out which of four planning
> documents is the current one.

---

## Appendix A — Branch snapshot: `master` vs `MastersProjectSRSImpl` (2026-06-28)

Preserved from the retired `BRANCH_COMPARISON.md`. A point-in-time picture of how far the branch had
diverged (20 commits, 176 files, +17,961 / −3,715 at the time).

### New tables introduced by the branch
`refreshsessions` (token rotation + reuse detection), `totpcredentials`, `totprecoverycodes`,
`mfachallenges`, `oauthproviderlinks` (federated identity), `organizations`, `userorganizations`.
Modified: `users` gained `using_totp` and `password_changed_at`. Removed: Flyway's `flyway_schema_history`.

### New backend services
`FederatedIdentityService`, `OrganizationService`, `SessionService` (refresh rotation/reuse),
`TotpService`, `EventService` (audit). Notable change: **`TokenProvider` signature changed** to integrate
with `SessionService`; **`SecurityConfig` matcher ordering** now gates specific routes before `/**`.

### Key configuration changes
- `spring.jpa.hibernate.ddl-auto`: `update` → **`validate` (prod)** / `update` (dev).
- `spring.sql.init.mode`: now **`never`** (schema.sql run by hand; later made portable — see `schema.sql` header).
- `show-sql`: pinned **off** in prod; `globally_quoted_identifiers: true` for JPA camelCase columns.

### Testing added
`UserControllerLoginEnumerationTest` (user-enumeration regression), `GlobalExceptionHandlerTest`,
`CustomerServiceImplTest`, `JpaSchemaSyncTest` (offline Hibernate schema-drift guard).

### Migration lesson captured at the time
Do **not** point the branch at an old `master` database and hand-patch columns. Initialise the schema
with the branch's `schema.sql`, update `.env` for new variables, then boot and let Hibernate validate.
*(This exact class of drift later recurred against Aiven `db3` — see `ROADMAP.md §5` and the 2026-07
schema-portability work.)*

---

## Appendix B — Branch delivery timeline

Preserved from the retired `branch-changelog.md`. What landed on `MastersProjectSRSImpl`, in order.

| Phase | When | What landed |
|---|---|---|
| **A** — Security feature drop | 2026-06-11 | Admin/RBAC surface, TOTP MFA, refresh sessions & rotation, organization scoping (~5.7k insertions) |
| **B** — SRS implementation | 2026-06-13 | Requirements traced into code (+1,337) |
| **C** — Documentation corpus | 2026-06-13 → 06-16 | The `documentation/` guides and the flow docs (~7.6k insertions) |
| **D** — Production hardening + UI consistency | 2026-06-17 | Prod profile hardening, the `sc-*` design layer |
| **E** — Prod-readiness finish | 2026-06-18 | JPA schema sync, config fail-fast |
| **F** — TesseraApp rebrand + billing/services | 2026-06-18 | SecureCapita → TesseraApp, billing and services-catalog screens |
| **G** — Security & cloud hardening | 2026-07-21 → 07-24 | Rate limiting, security headers, S3 image storage, multi-env config + CI/CD, analytics authz, non-fatal audit |
| **H** — Threat protection | 2026-07-25 | FR-TPF-1 login anomaly detection + step-up, org-scoped analytics, `X-Forwarded-For` trust fix, frontend test harness proven |
| **I** — Feature completion | 2026-07-26 | Security dashboard (FR-TPF-2), business CRUD (invoice edit, services catalog), capability-level RBAC gating, six-language i18n, CI gating on lint + audit, security-path tests, federated link/unlink |

For the current state rather than the history, see [`ROADMAP.md`](../../ROADMAP.md) and
[`documentation/README.md`](../README.md).

---

## Related documents

- [`ROADMAP.md`](../../ROADMAP.md) · [`branch-changelog.md`](../../branch-changelog.md) ·
  [`software_requirements_specification.md`](../../software_requirements_specification.md)
- [`documentation/architecture.md`](../architecture.md) · [`documentation/security.md`](../security.md) ·
  [`documentation/database.md`](../database.md)
