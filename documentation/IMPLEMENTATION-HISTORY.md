# Implementation History

**Version:** 2.0
**Last Updated:** 2026-08-02
**Status:** Living archive — what was built over time, and what went wrong along the way.

## Overview

The retrospective counterpart to [FUTURE-ENHANCEMENTS.md](FUTURE-ENHANCEMENTS.md). Nothing here is a
plan or a TODO — those live there. This is the story and the receipts.

The most useful part of this document is **[§4, the problem log](#4-problems-we-hit-and-how-they-were-solved)**.
Every entry is a real failure with its real diagnosis, including the ones where the first hypothesis
was wrong. Several describe bugs that are invisible in local development and only appear once
deployed, which is exactly the class of problem that is most expensive to rediscover.

## Table of contents

- [1. The story so far](#1-the-story-so-far)
- [2. Milestones (M0–M7)](#2-milestones-m0m7)
- [3. Delivery timeline](#3-delivery-timeline)
- [4. Problems we hit, and how they were solved](#4-problems-we-hit-and-how-they-were-solved)
- [5. Retired documents (registry)](#5-retired-documents-registry)
- [6. Legacy Azure deployment (reference)](#6-legacy-azure-deployment-reference)

---

## 1. The story so far

**Origins.** The codebase began as a follow-along of a several-year-old "Get Arrays" full-stack
tutorial — Angular SPA + Spring Boot REST API, MySQL, JWT auth — branded **SecureCapita**. The early
goal was simply to finish and modernise that tutorial.

**Modernisation.** The stack was pulled up to current: **Angular (latest, standalone, zoneless,
signals)** on the front, **Spring Boot 4 / Java 21** on the back, with the core identity/auth domain
deliberately built on **`NamedParameterJdbcTemplate`** (hand-written SQL + row mappers) rather than
JPA, while the CRUD-heavy business domain (customers/invoices/services) stayed on JPA. Legacy
patterns were migrated to modern equivalents throughout.

**The CIAM / zero-trust overhaul (M0–M7).** The largest arc of work turned a basic login into a real
customer-identity platform: a design-token UI system, full auth screens, **federated OAuth2/OIDC
login** (Google/GitHub/Microsoft, with account linking), **authenticator-app TOTP MFA**,
**server-side refresh sessions with rotation and reuse detection**, a session/device management
panel, **organization-scoped admin access**, a roles × permissions matrix, and finally route
transitions and a ⌘/Ctrl-K command palette.

**Rebrand.** SecureCapita became **TesseraApp** (commit `0a2f3ea`, 2026-06-18), alongside new billing
and services-catalog features.

**Production hardening.** `TokenProvider` stopped logging the signing key; the app moved to
environment-driven config with `dev`/`prod`/`qa`/`stage` profiles; prod runs `ddl-auto=validate`
against a `schema.sql`-owned database; general **rate limiting** (Bucket4j, 429 + `Retry-After`),
security headers (CSP / Referrer-Policy / Permissions-Policy), and **per-account brute-force lockout**
were added. **Flyway was removed on purpose** — its baseline bookkeeping kept desyncing and wedging
startup — and the schema became a single idempotent `src/main/resources/schema.sql`.

**Cloud & CI/CD.** A single env-driven `Dockerfile`, `docker-compose`, GitHub Actions (build/test +
ECR/ECS deploy), an **S3 image storage** abstraction, and an **Aiven** managed-MySQL option landed.
Pipelines exist for AWS (ECS Fargate — the live one), GCP (Cloud Run) and Azure (App Service).

**Where it ended up.** Live on AWS ECS Fargate, 126 backend and 87 frontend tests green, CI gating on
lint + dependency audit + both suites, and six-language i18n across 26 of 28 templates.

---

## 2. Milestones (M0–M7)

| Milestone | Theme | Status |
|---|---|---|
| **M0** | Design tokens + base UI system (dark/indigo) | ✅ |
| **M1** | Auth screens (login/register/reset/verify) overhaul | ✅ |
| **M2** | Dashboard / insights | ✅ (ongoing polish) |
| **M3** | Roles × permissions matrix (RBAC visibility) | ✅ |
| **M4** | Authenticator-app TOTP MFA | ✅ |
| **M5** | Sessions & devices + refresh rotation / reuse detection | ✅ |
| **M6** | In-house CIAM / zero-trust hardening | ✅ |
| **M7** | Route transitions + ⌘/Ctrl-K command palette | ✅ |

---

## 3. Delivery timeline

What landed on `MastersProjectSRSImpl`, in order.

| Phase | When | What landed |
|---|---|---|
| **A** — Security feature drop | 2026-06-11 | Admin/RBAC surface, TOTP MFA, refresh sessions & rotation, organization scoping (~5.7k insertions) |
| **B** — SRS implementation | 2026-06-13 | Requirements traced into code |
| **C** — Documentation corpus | 2026-06-13 → 06-16 | The `documentation/` guides and the flow docs (~7.6k insertions) |
| **D** — Production hardening + UI consistency | 2026-06-17 | Prod profile hardening, the `sc-*` design layer |
| **E** — Prod-readiness finish | 2026-06-18 | JPA schema sync guard, config fail-fast |
| **F** — Rebrand + billing/services | 2026-06-18 | SecureCapita → TesseraApp, billing and services-catalog screens |
| **G** — Security & cloud hardening | 2026-07-21 → 07-24 | Rate limiting, security headers, S3 image storage, multi-env config + CI/CD, analytics authz, non-fatal audit |
| **H** — Threat protection | 2026-07-25 | FR-TPF-1 login anomaly detection + step-up, org-scoped analytics, `X-Forwarded-For` trust fix, frontend test harness proven |
| **I** — Feature completion | 2026-07-26 | Security dashboard (FR-TPF-2), business CRUD, capability-level RBAC gating, six-language i18n, CI gating on lint + audit, security-path tests, federated link/unlink |
| **J** — Single-origin parity | 2026-07-29 | Four defects that exist *only* once the SPA and API share an origin (§4.10) |
| **K** — Observability & performance | 2026-08-02 | CloudWatch logging config, the N+1 fix, JWT 401 correctness, prod error scrubbing, pagination across every list surface |

### Notable deliveries in detail

- **Federated account linking, enterprise pattern** (2026-07-26). "Connect a provider" from the
  Security Center. The design problem: linking acts on behalf of a signed-in user, but a JWT cannot
  ride a top-level navigation. Solved with a **single-use, five-minute, provider-bound ticket** that
  grants nothing on its own — redeeming it does not authenticate anybody, it only says which local
  account a *separately verified* provider identity attaches to. The security property is
  `FederatedIdentityService.linkProviderToUser` **refusing an identity that already belongs to
  another account**: without it, "Connect a provider" is an account-takeover primitive, and the usual
  "the email was verified" reasoning does not help, because links are keyed on the provider's stable
  subject rather than on email. Unlinking is refused when it would remove the last sign-in method.
- **Six-language i18n** (2026-07-26). Transloco runtime switching across 26 of 28 templates plus
  toasts and the command palette. Runtime rather than `@angular/localize` because compile-time i18n
  emits one bundle per language, so switching would reload a different build and the user would lose
  their place.
- **FR-TPF-2 security dashboard** (2026-07-26). The review surface FR-TPF-1 was missing — a detection
  control whose output nobody can look at cannot be tuned or shown to work. One response for the
  whole screen, because six endpoints would be six different instants of the same database.
- **Capability-level RBAC in the UI** (2026-07-26). `*appHasAuthority` / `[appRequiresAuthority]`
  directives and a fail-closed `capabilityGuard`. Found and fixed a real hole: `customer-details`
  gated ten bindings on `roleName === 'ROLE_USER'`, a string comparison that showed `ROLE_GUEST` a
  fully editable form that could only ever 403.

---

## 4. Problems we hit, and how they were solved

Grouped by theme. Each entry states the symptom, the actual cause, and the fix — including the cases
where the obvious hypothesis was wrong.

### 4.1 Flyway kept wedging startup — so it was removed

**Symptom.** Repeated startup failures where Flyway's `flyway_schema_history` baseline disagreed with
the live database, blocking boot until the bookkeeping was hand-repaired.

**Resolution.** Flyway was removed entirely (2026-06-13) and the cumulative result of migrations
`V1–V6` was baked into a single **idempotent** `src/main/resources/schema.sql` —
`CREATE TABLE IF NOT EXISTS`, `INSERT … ON DUPLICATE KEY UPDATE`, inlined FKs, deliberately no
`DROP`s — with `spring.sql.init.mode: never` so it is applied by hand.

**The trade-off, stated honestly.** There is now no down-migration mechanism and no schema
versioning, and every environment depends on a human remembering to apply the file. That has since
caused one production incident (§4.5). Do not reintroduce Flyway or Liquibase without revisiting this
decision deliberately — but do not pretend the cost is zero either.

### 4.2 "All my data vanished" — Docker MySQL shadowing native MySQL

**Symptom.** The app suddenly showed an empty database, login failed, and MySQL Workbench showed
unfamiliar capitalized `Customer`/`Invoice` tables. Hours were lost to this.

**Cause.** Two MySQL servers both wanted `127.0.0.1:3306`, and only one can own a port. `start.sh`
with `DB=local` launched a **Docker** MySQL on a fresh empty volume, which seized 3306 and *shadowed*
the native Windows MySQL80 service. The app connected to `localhost:3306`, got the empty container,
and looked wiped. **The real data was never touched** — it was in native MySQL80's data directory,
just not listening.

**The tell.** Capitalized `Customer`/`Invoice`/`Users` tables in your client mean you are on a
case-sensitive server (Docker or Aiven). All-lowercase means native.

**Fix.** `start.sh` now defaults to **`DB=native`**, which never starts the Docker MySQL. Set the
native service to auto-start (`sc config MySQL80 start= auto`, admin) so it always owns the port
first, and never run both at once.

### 4.3 The case-sensitivity landmine

**Symptom.** `Table 'db3.customer' doesn't exist` and `BadSqlGrammarException [... JOIN Users ...]`
on Docker and Aiven — but everything worked perfectly on native Windows MySQL.

**Cause.** MySQL's `lower_case_table_names` is `1` on Windows (case-insensitive) and `0` on Linux
(case-sensitive). The app was internally inconsistent about casing: JPA asked for capitalized
`Customer`/`Invoice`/`Services` (via `globally_quoted_identifiers`), hand-written JDBC asked for
lowercase `customer`/`invoice`, and `RoleQuery` joined capitalized `Users`. On a case-insensitive
server all three resolve to one table; on a case-sensitive server no single set of names satisfies
all three.

**First fix (a bridge).** Compatibility **views** on Aiven `db3` — lowercase views mirroring the
capitalized base tables — so every casing resolved.

**Durable fix (2026-07-29).** Every query was made to match `schema.sql`'s exact spelling: the JDBC
half uses lowercase `users`, the JPA half uses quoted-capital `` `Customer` ``/`` `Invoice` ``.
`SqlTableCaseConsistencyTest` now guards this offline, and the compatibility views are droppable. The
old "leave `JOIN Users` as-is" advice is superseded and must not be reinstated.

### 4.4 `Circular placeholder reference 'CONTAINER_PORT'`

**Symptom.** Boot 4 aborted at startup whenever an environment variable was absent.

**Cause.** The profile YAMLs declared each variable self-referentially — `CONTAINER_PORT:
${CONTAINER_PORT:8080}` — so when the env var was missing, the placeholder resolved back to its own
property and looped.

**Fix, using opposite strategies per profile.** `dev` pins **plain literals** (`MYSQL_USERNAME:
root`), so the app boots locally with zero config while an exported env var still overrides it (OS
env outranks profile YAML). `prod` **does not redeclare anything** — base `application.yml` reads
`${MYSQL_USERNAME}` once at the point of use, with no fallback, so a missing secret fails fast.

**The standing rule:** never write `X: ${X}` or `X: ${X:default}` in a profile YAML.

### 4.5 Login started returning 500 — a schema drift and an event listener

**Symptom** (2026-07-24). Every login returned 500 in the live environment.

**Cause, in two layers.** The live `db2.userevents` table lacked the `detail` column, because
`sql.init.mode: never` meant the idempotent `ALTER` in `schema.sql` had never run. That made the
audit write fail — and Spring's **synchronous** event multicaster propagated the audit failure back
into the login request, turning a logging problem into an authentication outage.

**Fix.** The column was added, but the durable fix is that `NewUserEventListener` now **swallows and
logs** audit-write failures instead of rethrowing. All audit writes funnel through that one listener,
so one change covers every event type. It is deliberately **not** `@Async` — the listener reads the
live `HttpServletRequest` for device and IP, which is request-scoped.

**Diagnostic note.** If the app still errors after the column exists, the backend was never actually
restarted. A Vite reload is not a backend bounce.

### 4.6 Hibernate created camelCase columns

**Cause.** `spring.jpa.properties.hibernate.globally_quoted_identifiers: true` quotes identifiers and
**bypasses the snake_case naming strategy**, so a `usingMfa` field maps to a column literally named
`usingMfa`.

**Fix.** Always put an explicit `@Column(name = "…")` on JPA entity fields. `JpaSchemaSyncTest` was
later added as the offline guard: it drives Hibernate's schema export with no database connection and
asserts `schema.sql` contains every quoted identifier Hibernate maps, so drift fails the **build**
rather than the next production start.

### 4.7 Seeded role ids drifted between databases

**Cause.** `INSERT … ON DUPLICATE KEY UPDATE` consumes an AUTO_INCREMENT value for every row it
touches — *including rows it merely updates*. Each re-run of the idempotent seed burned another seven
ids, which is why a database seeded five times showed roles numbered in the 30s. Since
`userroles.role_id` is a real foreign key, two databases seeded a different number of times disagreed
about which id meant which role.

**Fix.** Role ids are pinned 1–7 and the services catalog ids are pinned for the same reason.
Existing databases are not renumbered — the unique key is `name`, so seeded rows keep their drifted
ids and their foreign keys stay valid. Nothing was broken at the time (every assignment path resolves
by name first), but a dump moved between environments would have attached people to the wrong role.

### 4.8 Broken avatars after federated login

**Cause.** `users.image_url` was `VARCHAR(255)`. Identity providers return longer URLs, and MySQL
outside strict mode **silently truncates** on insert — so the row was written, the login succeeded,
and the only symptom was a broken image.

**Fix.** Widened to `VARCHAR(512)`.

### 4.9 The analytics authorization gap

**Symptom.** The billing and analytics screens gated their *route* behind `adminGuard`, but the
endpoints they called (`/customer/stats`, `/customer/list`, …) fell through `SecurityConfig`'s broad
`GET /**` rule requiring only `READ:USER`/`READ:CUSTOMER`. A non-admin calling them directly received
the same system-wide data — and `BillingComponent`'s docstring claimed the checks were
"double-checked server-side", which was false.

**Fix** (2026-07-24). A new `AnalyticsController` at `/admin/analytics/**` with `@PreAuthorize` on
`UPDATE:USER`/`UPDATE:ROLE`. Because it sits under `/admin/**`, the existing matcher gates it with
**zero `SecurityConfig` changes** — no new request matcher to mis-order. The frontend components were
repointed off `CustomerService`, and the false docstring was made true.

### 4.10 Four defects that only exist on a single origin

Found in 2026-07-29 while deploying. Every one is invisible in split-origin local development, which
is why 195 green tests missed them all.

| Defect | Cause | Fix |
|---|---|---|
| **SPA/API route collision** | The email-verification landing page was routed at `/user/verify/{type}/:key` in Angular — byte-for-byte the backend's own `@GetMapping`. On one origin the real controller wins, so the recipient of an activation email was shown raw JSON | Moved to `/verify/{type}/:key`, restoring the namespace split (bare/plural = SPA, `/user` `/customer` `/admin` = API) and documenting it as an invariant in `SecurityConfig` |
| **JSON 401/403 shown to humans** | The error handlers returned JSON to anyone, including a person who typed a protected URL | `BrowserErrorPage` content-negotiates on **fetch metadata** (`Sec-Fetch-Mode: navigate`), *not* `Accept` — Angular's `HttpClient` sets no `Accept` of its own, so negotiating on it would have served HTML to the token interceptor and silently signed everybody out. Navigations get a styled page; XHR keeps the exact JSON it had |
| **OAuth2 `redirect_uri` behind a load balancer** | `server.forward-headers-strategy` was unset, so `{baseUrl}` resolved to the container's own `http://<task-ip>:8080` instead of the public origin. Every federated sign-in failed with `redirect_uri_mismatch` while working perfectly on localhost | Env-driven `FORWARD_HEADERS_STRATEGY`, set to `framework` in the ECS task definition. Microsoft was also missing from `aws/task-definition.json` entirely — hence a provider that appeared locally and not when deployed |
| **Plain-text emails** | Verification mail was unstyled text | New `EmailTemplate` renders the app's design as table-based, inline-styled, `multipart/alternative` mail. The plain-text part is kept deliberately — it is both a fallback and a deliverability signal. No template engine added |

### 4.11 CSP broke icons and fonts — in production only

**Symptom.** Every `bi-*` icon rendered as nothing in production; fonts fell back to a system face.
Locally everything looked perfect.

**Cause.** Spring Boot serves the SPA out of `src/main/resources/static/`, so the SPA document
carries `SecurityConfig`'s CSP — and `style-src 'self'` / `font-src 'self'` blocked the jsDelivr icon
CSS and the Google Fonts woff2 files. **`ng serve` sends no CSP header at all**, so the policy is
only ever enforced once deployed.

**Fix.** Self-host: `bootstrap-icons` and `@fontsource/ibm-plex-sans`/`-mono` became npm
dependencies. Adding the CDN origins to the policy would also have worked, but would permanently
widen CSP to a third party for a cosmetic asset. **Assume any new third-party origin will fail in
production only.**

### 4.12 A rejected JWT returned 400, so silent refresh could never fire

**Symptom.** `curl -H "Authorization: Bearer bad.token" …` returned **400**, not 401. Because
`token.interceptor.ts` retries **only on 401**, any path that turned an invalid token into a 400
silently disabled silent refresh for that path.

**The first hypothesis was wrong.** The roadmap blamed `HandleException`'s `BadCredentialsException`
branch. The actual cause was `TokenProvider.getSubject` rethrowing JWT failures as
`BadCredentialsException`/`ApiException`, which `ExceptionUtils.processError` routes to its 400
branch. Expired tokens were always correct (401) — only malformed and bad-signature tokens were
wrong.

**Fix.** A new `@ExceptionHandler(JWTVerificationException.class)` → 401 in `HandleException` was
also required, or the refresh path (which runs through `SessionServiceImpl`, a controller) would have
500'd instead.

### 4.13 `devMessage` leaked in production despite the scrubber

**Symptom.** Production error bodies carried `devMessage` and `path` even though
`application-prod.yml` pins `app.error.expose-details: false` and the active profile was confirmed
`prod`.

**Cause, and why it is structural.** `ErrorDetailScrubber` is a `ResponseBodyAdvice`, so it only sees
bodies serialized through a controller's message converter. `ExceptionUtils` writes **straight to the
servlet output stream inside the filter chain**, before any controller is selected — the advice is
*structurally unable* to reach it. `CustomAccessDeniedHandler` already documented this for its own
body; the consequence for the filter path had been missed.

**Fix.** Scrub at the point of writing in `ExceptionUtils`, gated on the same property, via a nested
`@Component` bridge — rather than widening the advice.

### 4.14 N+1 queries on the customer and invoice lists

**Symptom.** Roughly 35 singleton `SELECT`s per page of customers.

**Fix.** `@BatchSize(size = 50)` on `Customer.invoices` and `Invoice.services`, which replaced them
with `WHERE customer IN (?,?,…)` — about 96% fewer SQL events.

**Use `@BatchSize`, not `JOIN FETCH`.** A collection fetch combined with `Pageable` makes Hibernate
paginate **in memory** (`HHH90003004`), which is strictly worse than the N+1 it replaces.

### 4.15 `show-sql: false` does not stop SQL logging

`org.hibernate.SQL` at `DEBUG` is a **separate SLF4J path** from `spring.jpa.show-sql`. Setting
`DEBUG_REPORT=true` enables Spring's debug mode, which switches Hibernate to DEBUG and reopens SQL
logging regardless of `show-sql`. Two independent knobs; both must be off in production.

### 4.16 Microsoft federated login: `AADSTS90023`

**Symptom.** `Public clients can't send a client secret`.

**Cause.** The redirect URI was registered under the SPA (or mobile) platform in Entra. That makes
Entra reject the — correct — `client_secret_post` authentication.

**Fix is a portal setting, not code.** Register the redirect URI under the **Web** platform and leave
*Allow public client flows* set to **No**. The Spring configuration in `OAuth2ClientConfig` was
already right.

### 4.17 A usable bearer token was written to the browser console

**Symptom.** `login$` piped `tap(console.log)` over a response containing `access_token` and
`refresh_token`, and the token interceptor logged the raw JWT.

**Why it mattered.** Angular does **not** strip `console.log` from production builds, so every
sign-in wrote a working bearer token into the browser console.

**Fix** (2026-07-26). Removed across 16 files, kept commented out for local debugging with
`DEBUG ONLY — DO NOT SHIP ENABLED` markers on the token-bearing ones.

### 4.18 Admins saw the non-admin view after a token refresh

**Cause.** Eleven `hasAnyAuthority` results were **eager field initializers**. `hasAnyAuthority`
returns `false` for an *expired* token, not only a missing authority — so a flag evaluated once at
construction latched whatever was true then, which after a page refresh is usually "expired token,
no authorities at all".

**Fix.** Converted to getters, with `UserService` memoizing the decode on the token string so
per-change-detection evaluation is a string compare.

### 4.19 Test-environment traps

- **`mvn compile` gives false passes** on signature changes — incremental output is not invalidated,
  and it never compiles `src/test`. Use `./mvnw clean test-compile`.
- **The Angular test environment's `localStorage` is an inert placeholder** with no `getItem`,
  `setItem` or `clear`. `@angular/build:unit-test` provides a `window`, but any spec touching tokens
  fails with a "not a function" `TypeError` unrelated to the code under test. `testing/local-storage.ts`
  installs a real in-memory `Storage` over the global.
- **`angular-jwt`'s `decodeToken` throws** on a malformed token; it never returns `null`.
- **The app is zoneless**, so call `detectChanges()` after every `dispatchEvent`.
- **`contextLoads` needs a live local MySQL.** It is the one suite that breaks in a database-less CI
  run; exclude it with `-Dtest='!AngularSpringBootFullStackApplicationTests'`.

### 4.20 The documentation itself rotted

**Symptom** (found 2026-08-02). `documentation/architecture.md` had been deleted during a
reorganization (commit `4c6fb12`, "Expand docs and reorganize guides") while **nine files still
linked to it**, including the hub's own "Understand the whole system" row. `PROJECT-HISTORY.md`
listed two documents as "companion living docs" that its own registry recorded as deleted. `testing.md`
said "zero frontend specs" in §7 while §1 of the same file listed 79 of them. The hub had the same
table row twice.

**Cause.** Forty-two markdown files with heavy cross-linking and no mechanism to detect a broken
reference. Every individual guide was good; the *set* was unmaintainable.

**Fix.** Consolidated to four documents (this one, [GUIDE.md](GUIDE.md),
[FUTURE-ENHANCEMENTS.md](FUTURE-ENHANCEMENTS.md), and the root README), keeping only
[flows/](flows/README.md) and [aws/RUNBOOK.md](../aws/RUNBOOK.md) as deep references — because
folding 3,600 lines of sequence diagrams and deploy steps into a general guide would have ruined
both. The architecture content was rewritten into GUIDE §1 rather than restored as a separate file.

**The standing rule this produced:** when a claim in a doc and the code disagree, **the code wins**,
and the doc gets fixed in the same change.

---

## 5. Retired documents (registry)

Where the old files went. Everything deleted remains recoverable from git history.

| Document | What it was | Where it went | Recover with |
|---|---|---|---|
| `plan.md` | Original approved UI + auth master plan (M0–M7) | → milestones, §2 | `git log --all -- plan.md` |
| `phase2-proposals.md` | Phase-2 proposals (user types, batch upload, M2M API) | → FUTURE-ENHANCEMENTS §3 | `git log --all -- phase2-proposals.md` |
| `rollout-plan.md` | Rollout / sequencing plan | → FUTURE-ENHANCEMENTS | `git log --all -- rollout-plan.md` |
| `assignments/week-5-plan.md` | Near-term weekly roadmap slice | → FUTURE-ENHANCEMENTS §2 | `git log --all -- assignments/week-5-plan.md` |
| `BRANCH_COMPARISON.md` | One-off `master` vs branch writeup | → §3 timeline | `git log --all -- BRANCH_COMPARISON.md` |
| `branch-changelog.md` | Chronological branch record | → §3 timeline | `git log --all -- branch-changelog.md` |
| `documentation/project-status-and-roadmap.md` | Built-vs-documented reconciliation | Superseded — its "actual" column is now the shipped app | `git log --all -- documentation/project-status-and-roadmap.md` |
| `documentation/architecture.md` | System design guide | Deleted in `4c6fb12`; rewritten as **GUIDE §1** (§4.20) | `git log --all -- documentation/architecture.md` |
| `ROADMAP.md` | Live planning document | → **FUTURE-ENHANCEMENTS.md** (2026-08-02) | `git log --all -- ROADMAP.md` |
| `documentation/history/PROJECT-HISTORY.md` | The previous archive | → **this file** (2026-08-02) | `git log --all -- documentation/history/PROJECT-HISTORY.md` |
| `documentation/{getting-started, developer-guide, development-workflow, configuration, api-reference, database, testing, deployment, cicd-setup, frontend-guide, backend-blueprint, email-and-notifications, security, roles-and-scenarios}.md` | The 14 topic guides | → **GUIDE.md** (2026-08-02) | `git log --all -- documentation/<name>.md` |
| `aws/README.md`, `gcp/README.md`, `aws/RUNBOOK.md`, `tesseraapp/README.md` | Per-cloud reference, deploy runbook, frontend quick start | **Kept** — each documents the scripts or workspace sitting beside it, so folding them would orphan those files. Their links were repointed at GUIDE, and the durable lessons from the AWS troubleshooting log were lifted into §4 above | — |
| `assignments/architecture.md`, `finalPresentation/final_implementation_guide.md` | Superseded coursework copies | Left in place — they are part of the submitted academic record, not live documentation | — |

> **Consolidation policy (2026-08-02).** There is exactly **one** operational guide, **one** archive
> (this file), and **one** planning document. `documentation/flows/` and `aws/RUNBOOK.md` are kept as
> deep references because nothing in the four documents duplicates them. A reader should never have
> to work out which of several documents is current.

---

## 6. Legacy Azure deployment (reference)

The project was deployed to Azure before the move to AWS. **No longer maintained** — kept so the
resources can be revisited or decommissioned.

**Live URL (may be inactive):**
`https://angularspringbootfullstack-ehd6dkevc3edgxer.centralus-01.azurewebsites.net`

| Resource | Name |
|---|---|
| Container Registry | `bobsAngularApp` |
| ACR login server | `bobsangularapp-cnh8fzfxasa6feav.azurecr.io` |
| App Service | `angularSpringBootFullStack` |
| Resource group | `bobsresourcegroup` |
| Subscription | `Azure subscription 1` |

**Database — Aiven MySQL (free tier):** host `bobbylonsdb-bobbylon.a.aivencloud.com`, port `11275`,
schema `db2`, user `avnadmin`. Note the **non-standard port** — Aiven does not use 3306.

**App Service settings:**
`SPRING_DATASOURCE_URL=jdbc:mysql://bobbylonsdb-bobbylon.a.aivencloud.com:11275/db2?useSSL=true&requireSSL=true`,
plus `SPRING_DATASOURCE_USERNAME` and `SPRING_DATASOURCE_PASSWORD`.

**Pipeline** (`azure-pipelines.yml`, triggered on push to `master`): a Build stage
(`Docker@2 buildAndPush` → ACR, tagged with the Build ID and `latest`) and a Deploy stage
(`AzureWebAppContainer@1` → App Service). Two service connections were required:
`bobsDockerRegistryServiceConnection` (Docker Registry → ACR) and `bobsAzureServiceConnection`
(Azure Resource Manager → subscription).

---

## Related documents

- [GUIDE.md](GUIDE.md) — how everything currently works
- [FUTURE-ENHANCEMENTS.md](FUTURE-ENHANCEMENTS.md) — what is planned
- [flows/](flows/README.md) — click-to-database traces
- [aws/RUNBOOK.md](../aws/RUNBOOK.md) — the live deploy procedure
