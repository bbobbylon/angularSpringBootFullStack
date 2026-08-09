# Future Enhancements & Roadmap

**Version:** 3.1
**Last Updated:** 2026-08-08
**Status:** Living — the single source of truth for anything planned, deferred, or TODO.

## Overview

This is **the** place for future work. Everything already built lives in
[IMPLEMENTATION-HISTORY.md](IMPLEMENTATION-HISTORY.md); everything operational lives in
[GUIDE.md](GUIDE.md). When you add a `TODO` in code, either fix it or add a one-line entry here and
reference it — planning that re-scatters across files is the exact problem this document exists to
prevent.

**Status legend:** ⬜ not started · 🔄 in progress · ✅ done · 🔴 open defect

## Table of contents

- [1. Where the project actually stands](#1-where-the-project-actually-stands)
- [2. Active queue](#2-active-queue)
- [3. Enhancement backlog](#3-enhancement-backlog)
- [4. Code TODO audit](#4-code-todo-audit)
- [5. Engineering debt](#5-engineering-debt)
- [6. From demo to product — what a small business would need](#6-from-demo-to-product--what-a-small-business-would-need)

---

## 1. Where the project actually stands

Every functional requirement in the SRS is implemented. The remaining work is **quality and
operability**, not features.

| Dimension | State |
|---|---|
| Functional scope | ✅ Complete — auth, MFA, passkeys, federation (login **and** link/unlink), real SMS 2FA, sessions (self-service **and** granular admin revoke), RBAC, org scoping, user-type classification, anomaly detection + step-up, security dashboard, business CRUD, i18n |
| Tests | ✅ **230 backend / 87 frontend**, all green (re-verified 2026-08-08 via actual Surefire execution counts) |
| Lint | ✅ `ng lint` clean and gating in CI |
| Dependency audit | ✅ `npm audit --audit-level=high` exit 0; OWASP `dependency-check` wired at `failBuildOnCVSS=7` |
| CI | ✅ Gating on lint + audit + both test suites against a MySQL service container |
| Deployment | ✅ Live on AWS ECS Fargate; GCP Cloud Run and Azure App Service pipelines also built |
| Docs | ✅ Consolidated to four documents (2026-08-02) |
| **Performance** | 🔄 Lighthouse round 1 done; round 2 candidates in §2.1 |
| **Multi-instance readiness** | ⬜ The largest structural gap — see §2.4 and §6.2 |

---

## 2. Active queue

Do these in order.

### 2.1 🔄 Frontend performance — Lighthouse 57 → 90s

**Round 1 is done (2026-07-26): every third-party origin removed from the critical path.**

Reading the *built* `index.html` against the *deployed* headers turned up a production bug, not just
a slow page. Angular was already doing the hard parts — Beasties inlines the critical CSS and turns
the 256 kB stylesheet into a non-blocking `media="print" onload="this.media='all'"` link — so the
head contained exactly two third-party requests, and both were **blocked by our own CSP in
production**: `bootstrap-icons.min.css` from jsDelivr (`style-src 'self'`) and the IBM Plex woff2
files from `fonts.gstatic.com` (`font-src 'self'`).

Both were fixed by **self-hosting** rather than by widening the policy, which would have permanently
opened CSP to a third party for a cosmetic asset. Measured, production build:

| | Before | After |
|---|---|---|
| Third-party origins in `<head>` | 2 | **0** |
| Render-blocking stylesheets | 1 (cross-origin) | **0** |
| Initial JS (transfer) | 27.81 kB over 2 requests | **21.76 kB over 1** |
| Icons/fonts in production | broken by CSP | working |

**Round 2 candidates, if the score still needs moving:**

| Candidate | Payoff | Risk |
|---|---|---|
| **Trim Bootstrap's CSS** — import only the needed layers via SCSS | `bootstrap.min.css` is ~230 kB of the 344 kB stylesheet | Real visual-regression risk across 28 templates. Measure first, do it deliberately |
| **Subset `bootstrap-icons.css`** | 90 icons used out of ~2,000, so ~95% is dead weight | Only worth it behind a build step that scans the templates — a hand-maintained subset breaks silently the first time someone adds an icon |
| **Preload the icon woff2** | Icons pop in slightly late; discovered only after the deferred stylesheet applies | Needs a build-time hook because `outputHashing` renames the file |
| **Confirm `jspdf`/`html2canvas` laziness** | The 427 kB `invoice-detail` chunk is the largest in the app | Already lazy; verify it loads on the export click, not on route entry |

### 2.2 ⬜ Remaining frontend specs

Backend security paths are covered. The one meaningful client-side gap left is **`cacheInterceptor`**
(`interceptor/cache.interceptor.ts`) — the last unspecced interceptor, and its invalidation rules are
exactly the kind of logic that silently serves one user another user's data.

### 2.3 ⬜ Exercise a real production boot

`ddl-auto=validate` against a MySQL initialised **only** by `schema.sql`. Only the offline
`JpaSchemaSyncTest` has run; that catches entity/DDL drift but cannot catch a schema the app has
never actually started against. This is the single largest untested assumption in the project — a
deploy that builds cleanly and then fails at startup is the most likely production failure mode.

### 2.4 ⬜ Move per-instance security state off the heap

Three controls live in a `ConcurrentHashMap` on one JVM: the brute-force counter, the rate limiter's
buckets, and `ProviderLinkTicketService`. Behind a load balancer without sticky sessions, an attacker
gets N× the attempt budget simply by being routed around. Not a bug today (one instance); a real hole
the moment a second exists — which makes it a **blocker for horizontal scaling**, not merely a
backlog item. See §6.2.

### 2.5 ⬜ Turn on cost visibility

**Cost Explorer and billing alerts are both off** in the AWS account — console-only toggles nobody
has flipped. There are zero SNS topics and zero CloudWatch alarms, so the first signal of a runaway
bill would be the bill. Current steady-state spend is roughly **$57/month** (§6.6); the risk is not
the current number but the absence of anything that would tell you it changed.

---

## 3. Enhancement backlog

Ranked roughly by value-to-effort. Nothing here is committed work — it is the menu.

### 3.1 Security & identity

| Enhancement | Why it is worth doing | Sketch |
|---|---|---|
| ⬜ **Distributed brute-force + rate-limit + link-ticket state** | See §2.4 — the scale-out blocker | Move counters to the database or Redis; the service interfaces do not change (`bucket4j-redis` exists for exactly this) |
| ✅ **Session revocation from the security dashboard** | Done (2026-08-08) — `GET /admin/user/{id}` now returns the target's live sessions (same `RefreshSession` shape as the Security Center, already `@JsonIgnore`-safe); `DELETE /admin/user/{id}/sessions/{family}` revokes one device, alongside the existing bulk `DELETE /admin/user/{id}/sessions`. Org-scoped, self-target-refused, audited against the target — same convention as every other admin action. Frontend: a Sessions panel on the user-detail page (`user-details.component`), per-row revoke + a bulk "sign out everywhere" button | |
| ⬜ **Admin-initiated MFA reset** | An account that loses both its authenticator and its recovery codes is currently unrecoverable without direct DB access | Admin action + an `MFA_RESET` audit event; gate on staff authority and audit loudly |
| ⬜ **Regenerate recovery codes** | There is no standalone endpoint — replacing a depleted set today means disable-and-re-enroll TOTP | `issueRecoveryCodes()` already does the delete-then-insert this needs; it just has no route |
| ✅ **Role-tier ceiling on reassignment** | Done (2026-08-07, `ec26adc`) — `RoleType.canAssign` + `AdminUserController#requireAssignableTier` reject assigning any role above the caller's own tier, fails closed on an unrecognised role name | |
| ⬜ **Single CORS source of truth** | Two CORS configurations disagree (`SecurityConfig`'s hardcoded list vs the config-driven `corsFilter`), and the hardcoded one wins. Inert in a single-origin deploy; a real bug the moment a second client exists | Delete the hardcoded list; have `SecurityConfig` read `app.cors.allowed-origin-patterns` like the filter does |
| ⬜ **Anomaly signal tuning UI** | `ANOMALY_HISTORY_LIMIT` and the enable flag are env-only, so tuning needs a redeploy | Persisted settings + an admin panel; keep env as the fallback default |
| ⬜ **P2-3 — Machine-to-machine API access** | Lets scripts and CI authenticate without a browser. Deferred deliberately: it adds a second authentication front door, the highest-risk change on this list | **Option A — API keys:** an `X-API-Key` filter ahead of `CustomAuthFilter` resolving a **hashed** key to an `Authentication` carrying authority strings, so every existing `hasAnyAuthority`/`@PreAuthorize` rule applies unchanged. **Option B — OAuth2 client-credentials:** `POST /oauth/token`. Both converge on "a request arrives already carrying authorities". Needs `service_accounts` + hashed `api_keys` tables, new audit event types, and `PUBLIC_URLS` ↔ `PUBLIC_ROUTES` lockstep. **Large, higher risk — do last, with dedicated review** |

### 3.2 Access model

| Enhancement | Why | Sketch |
|---|---|---|
| ⬜ **Role CRUD** | Roles are seed-only; `RoleRepoImpl.create/update/delete` throw `UnsupportedOperationException`. Fine while the seven roles are fixed, blocking the moment anyone wants an eighth | Implement the stubs + an admin screen; the permissions matrix UI already exists to edit against |
| ⬜ **Self-service organization management** | Orgs are seeded/DB-managed; there is no UI to create one or move a user between them. This is the single biggest gap between "demo" and "a business could run this" | Admin CRUD over `organizations` + `userorganizations`; must respect the same org scope it edits |
| ✅ **Org scope for business data** | Done (2026-08-08) — every `/customer/**` read (`stats`, list, single get, search, invoice list/get, the new-invoice picker, both XLSX exports) is now restricted for `ROLE_ORGANIZATION_ADMIN` to customers/invoices owned by their active organizations, reusing the exact `*ForOrganizations` service methods `AnalyticsController` already had. Every other role (including plain `ROLE_USER`) keeps today's system-wide view — this closes the specific "org admin sees every customer" gap, not a broader per-user multi-tenancy wall. Found and fixed two pre-existing bugs along the way: `List.of(...).contains(null)` throws instead of returning `false` (would have crashed the scope check on any unowned row), and `GET /customer/invoice/get/{id}` 500'd unconditionally on a draft invoice (`Map.of` rejects a null value) — both predate this change. New suite: `CustomerControllerOrgScopeTest` (14 tests) | |
| ⬜ **Scope every `UPDATE:USER` holder, not just one role name** | `isOrganizationScoped` matches the literal `ROLE_ORGANIZATION_ADMIN`. `ROLE_HELP_DESK_ADMIN` also carries `UPDATE:USER` and reaches `/admin/**` unscoped | Key scoping off a capability rather than a role name |
| ⬜ **Per-organization role definitions** | `RoleRepoImpl.java` `TODO(org-roles)`. FR-ORG scopes *administration*, not role *definitions* — every org shares one role catalogue | Only worth it for genuine multi-tenancy; it changes the authority-string model, so not a small change |
| ✅ **P2-1 — User type classification** | Done (2026-08-08) — badge shows `INTERNAL` / `EXTERNAL` / `FEDERATED` on the admin Users list and detail pages. `users.origin` is an immutable fact stamped once, at account creation, by `FederatedIdentityServiceImpl#insertFederatedUser` (`"FEDERATED_" + provider`) — never touched again, including when an existing password account later links a federated identity. `UserTypeResolver` derives `INTERNAL`/`EXTERNAL` fresh on every read from the email domain against the env-driven `INTERNAL_DOMAINS` allowlist (`AdminUserController`). `AZURE_B2B` was dropped from scope — this app has no actual Azure B2B guest integration, only consumer OAuth via Google/GitHub/Microsoft, and fabricating a category for a provider that isn't built would misrepresent capability | |

### 3.3 Product & data

| Enhancement | Why | Sketch |
|---|---|---|
| ⬜ **List sorting & filtering** | `customer.service.ts:57`. Customers and invoices are paged but not sortable or filterable. Becomes a real problem at a few thousand rows | Push sort/filter into the query params and the SQL — the org-scoping work already established the "filter in SQL, never post-filter a page" rule for exactly this reason |
| ⬜ **Invoice total aggregation query** | `InvoiceRepo.java:15`. Billing sums invoices client-side, so the total is only ever as complete as the page you fetched | A `@Query` returning `SUM(totalAmount)`, org-scoped |
| ⬜ **P2-2 — Batch upload** | CSV/Excel import for customers and invoices — the most-requested "real business app" feature still missing | Per-row validation with a partial-success report (`{ imported, failed: [{row, reason}] }`), per-chunk commits, a dedupe key, async job for large files. Gate on `UPDATE:CUSTOMER`. Apache Commons CSV (+ POI only for `.xlsx`) |
| ⬜ **Favorites / pinned destinations bar** | Navigation has outgrown the navbar: the Admin dropdown alone holds six destinations, so common pages are two clicks deep behind a menu that must be opened to be read | **Build it on `command-palette.service.ts`, not a new route list** — see the design note below |
| ⬜ **Backend-driven i18n** | Server-generated messages (validation, email bodies, capability-denied text) stay English while the UI switches language | Spring `MessageSource` + `Accept-Language`; the `CapabilityCatalog` phrases are the natural first target since they already have a message template |
| ⬜ **Resolve `VERIFY_EMAIL_HOST`** | Reserved and unused (`UI_APP_URL` drives links today). Keep or remove deliberately — do not let it rot as ambiguous config | |
| ✅ **DB connection: `VERIFY_IDENTITY` instead of `REQUIRED`** | Done (2026-08-08) — Aiven's per-project CA (`certs/aiven-mysql-ca.pem`, a public certificate, safe to commit) is imported into the JRE's default truststore at Docker build time (`keytool -importcert`), and `MYSQL_SSL_MODE` is now `VERIFY_IDENTITY`. Verified three ways before touching production: the cert is well-formed (`openssl x509`), the import actually lands in the built image's truststore (`keytool -list` inside the container), and — the real test — a live `mysql.exe --ssl-mode=VERIFY_IDENTITY --ssl-ca=...` connection against the actual Aiven instance succeeded (TLSv1.3). Not yet redeployed to production as of this writing — see the RUNBOOK for the redeploy step | |
| ⬜ **Email invoices/documents as PDF attachments** | The app can already export an invoice (and other records) to PDF client-side for printing/download, but there's no way to have that PDF emailed to the customer or to yourself | Reuse `EmailServiceImpl`'s existing `multipart/alternative` + `EmailTemplate` branded-HTML pattern (2026-08-08 session confirmed this is already solid, not a placeholder) and attach the PDF via `MimeMessageHelper#addAttachment`. The PDF generation itself likely needs to move server-side (or accept a client-generated blob upload) since the current export path is frontend-only — investigate `jspdf` usage in `tesseraapp/` before assuming which side should own rendering |
| ⬜ **Scheduled/on-demand report & metrics emails** | Admins can see stats/analytics live in the Security Center and dashboards, but there's no way to get a periodic digest (login counts, MFA enrollment %, audit summary) or a one-off "email me this view" without staying logged in | A digest email reusing `EmailTemplate`; scheduling likely wants a lightweight cron (Spring `@Scheduled`) rather than a new job-queue dependency, given this project's small-team scale. Natural pairing with `SecurityDashboardServiceImpl`'s existing tile data — render the same numbers into an email instead of a new query path |

#### Favorites bar — decisions taken up front

**Reuse the command-palette registry.** `command-palette.service.ts` already holds every navigable
destination with its label, icon and required authorities. Favorites must be *a set of ids into that
registry*, never a parallel list of routes — otherwise the two drift the first time someone adds a
page, and only one of them gets it. The corollary is that the add/remove affordance is a star on each
palette result, which teaches the feature exactly when someone is hunting for a page.

Two decisions to settle before implementation:

1. **Storage — `localStorage` or a `userpreferences` table?** `localStorage` mirrors how the theme
   toggle already works and is roughly an hour of work, but pins are per-browser and vanish on a new
   device. A DB-backed table is closer to half a day and makes the workspace follow the *identity*
   rather than the browser, reusing the existing Query + RowMapper + Repo/RepoImpl pattern.
   **Leaning DB-backed**, with `localStorage` as an offline cache rather than the source of truth.
2. **RBAC filtering must happen on *render*, not only on *add*.** A role reassignment can strip
   `UPDATE:USER` from someone who pinned `/analytics` last week, and a pin that survives into a menu
   and then 403s is worse than no pin. The existing `*appHasAuthority` directive handles this for
   free — the second reason to reuse the palette registry, since that is where the authority metadata
   already lives. Decide whether a now-invisible pin is *hidden* or *removed*; hidden is kinder,
   because a temporary role change should not silently destroy someone's setup.

Open: where the bar physically sits (a second row under the navbar vs. inline beside the palette
trigger), and whether there is a cap on pin count.

### 3.4 Operations

| Enhancement | Why | Sketch |
|---|---|---|
| ⬜ **Backend HTTP caching** | `cache.interceptor.ts:35` + `http-cache.service.ts:25` — caching is client-side only, so it cannot be invalidated by a write from another user | `Cache-Control`/`ETag` on read endpoints; Redis if it needs to be shared |
| ⬜ **Structured logging + metrics** | Actuator is present but nothing scrapes it; the audit log is the only operational visibility | Micrometer + JSON logs; the security dashboard's counters are the obvious first metrics to export |
| 🔴 **HTTPS on the ALB** | **The highest-priority open item.** Plain HTTP today, which makes HSTS inert, leaves every token transit unencrypted, and **blocks Google and Microsoft federated login outright** — both reject non-`https` redirect URIs except on `localhost`. Only GitHub works on the deployed URL. It would also block WebAuthn (§3.1), which needs a secure context | Two routes, see §6.8 |
| ⬜ **Real production domain** | Same blocker as HTTPS: ACM cannot issue a certificate without proving DNS control, and you cannot prove control of the ALB's own `*.elb.amazonaws.com` name. No code marker remains — CORS origins are already env-driven | Buy a domain → ACM DNS validation → HTTPS listener |
| ⬜ **`start.sh` → Maven wrapper** | Uses bare `mvn`; `./mvnw` pins the version so a teammate's Maven install cannot change the build | One-line change |

---

## 4. Code TODO audit

**17 `TODO` comments — 7 backend, 10 frontend — across 8 distinct work items.** Re-verified by
grepping the tree on 2026-08-02; line numbers are from that grep.

None is a defect. Every one is either a deliberate stub (SMS), a cosmetic rename, or a refactor that
works correctly today and would simply be tidier done differently.

### Backend (7)

| Location | Intent | Notes |
|---|---|---|
| `InvoiceRepo.java:21` | sum-of-`totalAmount` `@Query` | §3.3 — billing currently sums client-side, so the total is only as complete as the page fetched |
| `UserQuery.java:36` + `UserRepoImpl.java:183` | Rename the `url` column → `verification_key` | Cosmetic — it holds a bare UUID, not a URL. Needs a guarded idempotent rename |
| `UserController.java:62` | `TODO(refactor-user-fetch)` — standardize the authenticated-user fetch | Refactor |
| `UserRepoImpl.java:63` | `TODO(refactor-architecture)` — SRP violation | It is a repo, a `UserDetailsService`, **and** a business-logic holder |
| `RoleRepoImpl.java:25` | `TODO(org-roles)` — org-scoped role system | §3.2 |
| `NotificationServiceImpl.java:54` | Enable SMS (Twilio) when ready | §3.3 — deliberate, documented stub |

Not marked by a `TODO` but tracked in §3.2: `RoleRepoImpl.create/update/delete/getById` throw
`UnsupportedOperationException` (roles are seed-only).

### Frontend (10)

| Location | Intent | Notes |
|---|---|---|
| `cache.interceptor.ts:35` + `http-cache.service.ts:25` | Move caching to the backend and delete both | §3.4 |
| `new-customer.component.ts:27, :80, :87` | Lighter user-only prefill endpoint | Currently fetches **every customer** to prefill one field |
| `customer.service.ts:41` + `stats.component.ts:14` | `StatsComponent` self-fetch — `stats$()` is declared but unused | Refactor |
| `customer.service.ts:59` | Sorting / filtering / infinite scroll | §3.3 |
| `navbar.component.ts:18` | Decouple user data from the `/customer/list` response | Fetch `/user/profile` independently |
| `profile.component.ts:19` | Reactive forms for profile | Refactor |

> **Closed since the last audit:** the "real prod domain" `TODO` in
> `AngularSpringBootFullStackApplication.java` is **gone** — CORS origins are now read from
> `app.cors.allowed-origin-patterns` (env `CORS_ALLOWED_ORIGINS`) per profile, so there is no
> hardcoded domain left to replace. The remaining domain work is infrastructure only (§3.4).

---

## 5. Engineering debt

- ⬜ **Per-instance security state** (brute force, rate limiting, link tickets) — §2.4. The scale-out blocker.
- ⬜ **Untried production boot** with `ddl-auto=validate` — §2.3.
- ⬜ **No test touches the real filter chain.** Every slice test uses `standaloneSetup`, which skips
  `SecurityConfig` by design — so matcher **ordering**, the thing most likely to break, has no
  automated guard at all. A `@SpringBootTest(webEnvironment=RANDOM_PORT)` + `TestRestTemplate` happy
  path per controller would close it, and would also cover the `PUBLIC_URLS` ↔ `PUBLIC_ROUTES`
  lockstep.
- ⬜ **`contextLoads` needs a live local MySQL**, so it is the one suite that breaks in a
  database-less CI run. Replacing it with a Testcontainers-backed `@SpringBootTest` removes the
  footgun and unblocks DB-backed tests in CI.
- ⬜ **No end-to-end coverage.** Playwright against `docker compose up` is the only way to catch seam
  breaks (interceptor ↔ backend, OAuth redirect round-trip, federated link flow).
- ⬜ **Refactors** listed in §4 — none urgent, all the kind that get harder the longer they wait.
- ✅ Security-critical-path tests · `ng lint` · `npm audit` · prod error hygiene · Aiven schema
  drift · redundant JWT library — all closed; see [IMPLEMENTATION-HISTORY.md](IMPLEMENTATION-HISTORY.md).

---

## 6. From demo to product — what a small business would need

The app is feature-complete against its own requirements and is genuinely deployed. That is not the
same as being **sellable**. This section is the honest gap between "a working system" and "something
a small business could buy, run, and depend on" — written as the work it actually implies rather than
as a wish list.

The ordering matters: §6.1–6.3 are prerequisites for taking *anyone's* money, §6.4–6.5 are what make
it a product rather than an installation, and §6.6 is what it costs.

### 6.1 Tenancy — the foundational decision

**Updated 2026-08-08**: `ROLE_ORGANIZATION_ADMIN`'s reads of customer/invoice data are now scoped
to their own organizations (§3.2 below is done). That closes the specific "an org admin sees
everyone's data" gap, but it is a narrower fix than full multi-tenancy: every *other* role,
including a plain `ROLE_USER` with `READ:CUSTOMER`, still sees every customer, invoice, and service
row system-wide — that was a deliberate scope decision (mirroring how `AnalyticsController`'s
scoping already worked), not an oversight, but it means the table below is still live. For a single
business running its own instance the current state is fine. For a product serving several
businesses as genuinely separate tenants, it is still disqualifying, and it is the decision
everything else hangs off:

| Model | What it means here | Effort |
|---|---|---|
| **Single-tenant** — one deployment per customer | Ship as-is; each business gets its own container + database. Simplest and safest, but per-customer operational cost and no economy of scale | Low — mostly packaging and a provisioning script |
| **Shared-schema multi-tenant** — `organization_id` on every business table | The natural extension of what already exists; the analytics aggregates already do this. Needs the column on `customer`/`invoice`/`services`, the predicate pushed into every query, and a fail-closed default | **Medium — the recommended path.** The org-scoping pattern is already proven in `OrganizationServiceImpl` |
| **Schema-per-tenant** | Strongest isolation, worst migration story with a hand-applied `schema.sql` and no migration tool | High, and it fights the current schema strategy |

If shared-schema is chosen, two rules from the existing org work carry over verbatim and are worth
restating because getting them wrong is silent: **scope inside the SQL, never filter the result set**
(an aggregate has discarded its attribution by the time it is a number, and post-filtering a page
corrupts `totalElements`), and **an empty scope means nothing, not everything**.

### 6.2 Scale-out blockers

The app runs on one Fargate task. Running two would break these, in this order of severity:

| Blocker | What breaks on instance #2 | Fix |
|---|---|---|
| **Brute-force counter, rate-limit buckets, link tickets** | All three are per-JVM maps. An attacker routed across instances gets N× the budget; a link ticket minted on A cannot be redeemed on B | Move to the database or Redis (§2.4). The service interfaces do not change |
| **Profile-image storage** | `IMAGE_STORAGE_TYPE=local` writes to the container filesystem, so an avatar uploaded to A 404s from B — and vanishes on redeploy | Already solved: set `IMAGE_STORAGE_TYPE=s3` and grant the task role `s3:PutObject`/`s3:GetObject`. **This is configuration, not code** |
| **HTTP cache** | Client-side only, so a write by one user never invalidates another user's cache | `Cache-Control`/`ETag` on read endpoints (§3.4) |
| **The `dev` seeder** | Harmless — it never runs under `prod` | — |

Notably, the things you would *expect* to break do not: refresh rotation, reuse detection, TOTP, RBAC
and org scoping are all database-backed and survive multi-instance unchanged. The stateless-access /
stateful-refresh split was the right call.

### 6.3 Data durability and recovery

This is the gap that would end a business, and it is currently unaddressed:

- **No backup policy.** Aiven takes its own backups on its plan, but nobody has written down the
  retention, and **nobody has ever performed a restore**. A backup you have not restored from is a
  hypothesis, not a backup.
- **No restore drill, no RPO/RTO.** "How much data can we lose and how long can we be down" has no
  answer, which means it has the worst possible answer.
- **Rollback is manual SQL.** Dropping Flyway was the right call for this project's pain, but it
  means there is no down-migration mechanism — see [GUIDE.md §9.6](GUIDE.md#96-schema-evolution).
  For a product, either reintroduce a migration tool with real discipline or write and rehearse the
  manual runbook.
- **Schema is applied by hand.** `sql.init.mode: never` means every environment depends on a human
  remembering. It has already caused one production incident (the missing `userevents.detail` column
  → login 500s). Automate it, or gate deploys on a schema-version check.

### 6.4 Operational readiness

| Area | State today | What a paying customer needs |
|---|---|---|
| **Logging** | ✅ CloudWatch, 7-day retention, env-driven levels | Longer retention for audit; log-based alerting |
| **Metrics** | ❌ Actuator exposed but nothing scrapes it | Micrometer → CloudWatch/Prometheus; dashboards for error rate, latency, login failures |
| **Alerting** | ❌ Zero alarms, zero SNS topics | At minimum: 5xx rate, health-check failure, DB connection exhaustion, and a billing alarm (§2.5) |
| **Uptime target** | ❌ None stated | An SLA implies redundancy — which implies §6.2 |
| **On-call / runbook** | 🔄 [aws/RUNBOOK.md](../aws/RUNBOOK.md) covers deploy, not incidents | Incident runbooks: "login is failing", "the database is unreachable", "we think a token leaked" |
| **Status page** | ❌ | Even a static one; customers ask before they email |

### 6.5 Commercial and legal prerequisites

None of these are technically hard. All of them are non-optional before charging money.

- **Data subject rights (GDPR/CCPA).** The app stores names, emails, phone numbers, IP addresses and
  device strings. It has **no export and no deletion path** — `DELETE:USER` exists as an authority
  but there is no "erase everything about this person" operation, and `ON DELETE CASCADE` on the
  audit tables would destroy the audit trail you may be required to keep. That tension needs a
  deliberate answer, not a default.
- **Audit retention policy.** `userevents` grows forever. Decide the retention window, then enforce
  it — and note that "delete the user" and "keep the security audit log" pull in opposite directions.
- **Terms of service, privacy policy, DPA.** Standard, but they gate the first enterprise customer.
- **Payments.** If the product bills, that is a PCI conversation — which is an argument for never
  touching card data directly (Stripe/Paddle hosted checkout) rather than for building it.
- **A security review by someone who did not write it.** Every control here was designed and tested
  by one person. The tests are real, but they encode the author's model of the threat. A pen test is
  the cheapest way to find the assumption nobody thought to question.

### 6.6 What it costs today

Steady-state, one instance, us-east-1:

| Item | Monthly | Note |
|---|---:|---|
| ECS Fargate | ~$18 | **No free tier** — the real cost driver |
| ALB | ~$16 | Fixed hourly charge regardless of traffic |
| Aiven MySQL | ~$19 | The managed database |
| Secrets Manager | ~$4 | 10 secrets × $0.40 |
| CloudWatch Logs | ~$0 | ~371 KB stored against 5 GB free |
| **Total** | **~$57** | |

Two things worth knowing: CloudWatch is *not* the risk anyone assumes it is, and there is currently
**nothing watching this number** (§2.5). For a small business the shape is fine — it is the absence
of an alarm, not the amount, that is the problem.

### 6.7 A staged plan

If this were to be taken seriously as a product, the order would be:

| Phase | Goal | Contents |
|---|---|---|
| **1 — Safe to run** | Nothing can be lost or silently broken | Backup + rehearsed restore (§6.3), alerting + billing alarm (§2.5, §6.4), HTTPS on the ALB (§3.4), the real prod-profile boot (§2.3) |
| **2 — Safe to sell** | Legally and contractually shippable | Data export/deletion (§6.5), retention policy, ToS/privacy, an external security review |
| **3 — Able to grow** | More than one customer, more than one instance | The tenancy decision (§6.1), distributed security state (§2.4), S3 images + backend caching (§6.2), org self-management (§3.2) |
| **4 — Competitive** | Reasons to choose it | Batch upload (§3.3), sorting/filtering, session revocation from the dashboard, backend i18n |

Phase 1 is roughly a week of focused work and removes every risk that could destroy trust
irrecoverably. Phase 3 is the one that requires a real architectural decision rather than execution.

### 6.8 The HTTPS / domain decision (do this first)

> **Status, August 8, 2026 — superseded. A real domain was bought after all.** Route B (below) was
> the answer from August 4 through August 7 — CloudFront on the auto-issued `*.cloudfront.net`
> name, no domain. On August 8 a real domain was purchased anyway (**`tesseraapp.dev`**, Porkbun,
> $8.75/yr) and attached to the same CloudFront distribution as an alternate domain name, following
> the Route A procedure below but against CloudFront's origin rather than the ALB directly — see
> `aws/RUNBOOK.md` §B1.6 for the exact steps actually run. **Both URLs still work** and hit the
> identical backend; the one asymmetry is GitHub, whose one-callback-per-app limit means GitHub
> login only works on `tesseraapp.dev` now. The analysis below (Route A and Route B both) is kept
> because the reasoning still applies and Route B is still the free fallback if a domain is ever
> not an option.

**The domain and the HTTPS problem are the same problem.** ACM will not issue a certificate without
proving you control the domain's DNS, and AWS will not let you request one for the ALB's own
`*.elb.amazonaws.com` hostname — they own it, not you. So there is no path to a certificate on the
current URL. Getting a domain *is* the HTTPS fix.

This matters more than "unencrypted transit," which is what it sounds like:

| Blocked *before CloudFront* | Why |
|---|---|
| **Google federated login** | Google requires the `https` scheme on every authorized redirect URI. The only exception is `localhost`. The ALB's `http://` callback **cannot be registered at all** |
| **Microsoft (Entra) federated login** | Same rule — Entra rejects non-`https` redirect URIs outside `http://localhost` |
| ~~**WebAuthn / passkeys**~~ | ✅ **Done** (2026-08-07). The secure-context requirement this row warned about was resolved by the CloudFront HTTPS fix (§3.4/§6.8); the feature itself is now built end-to-end — see `documentation/GUIDE.md` §7.10/§8.3/§9.3 |
| **HSTS** | Sent, but inert — the header only means something over TLS |
| GitHub federated login | GitHub permits `http` callbacks, so it was the one provider demonstrable on the ALB URL |

So two of the three federated providers — the two enterprises actually care about — were dark in the
deployed app while working perfectly on localhost. For a project whose thesis is federated CIAM, that
was the most consequential open item on this page. **The transport half of that is now solved by
Route B below.** What remains is not architectural:

| Remaining step | State |
|---|---|
| Deploy on a task-definition revision that sets `OAUTH2_REDIRECT_BASE_URL` | ✅ **Done — rev 14, verified August 4, 2026.** The live authorize redirect returns an `https://` `redirect_uri`, and the boot log prints `[NET] trusted-proxy-count=2` |
| Register `https://d3911jyxcju4q4.cloudfront.net/login/oauth2/code/{google,github,microsoft}` in each provider console | ⚠ Pending — manual, per provider |
| Real Google and GitHub credentials in Secrets Manager | ⚠ **Pending — `tessera-app/google-client-secret`, `github-client-id` and `github-client-secret` still hold the literal `CHANGE_ME`, and `google-client-id` is a placeholder too.** Microsoft's are populated. This means GitHub sign-in has *never* worked in the deployed environment, contrary to what the table above implied — it was blocked by a missing credential, not by the callback scheme |

**Route A — get a domain (the real fix).** Steps 2–4 below are automated by
**[`aws/deploy-https.sh`](../aws/deploy-https.sh)**:

```bash
AWS_REGION=us-east-1 ./aws/deploy-https.sh --domain app.example.com
```

1. Obtain a domain — see the cost note below; it does not have to be paid.
2. Request an **ACM certificate in the same region as the ALB** (`us-east-1` here) using **DNS
   validation**, and add the CNAME ACM asks for. *The "certificates must be in us-east-1" rule
   people remember is **CloudFront's**, not the ALB's — an ALB requires the certificate in its own
   region, and one from elsewhere fails with a `ValidationError`.* The script auto-creates the
   validation record when the zone is in Route 53, and prints it otherwise.
3. Add an **HTTPS:443 listener** with that certificate and rewrite the **:80 listener as a 301
   redirect**. The script also opens `:443` on the ALB security group — `setup.sh` only opened `:80`,
   so without it the listener comes up and every request times out at the security group.
4. Point the domain at the ALB (alias A record in Route 53, or ALIAS/ANAME elsewhere — an apex
   domain cannot be a CNAME).
5. Set `APP_DOMAIN`/`UI_APP_URL` to `https://your-domain` and **re-register the task definition, then
   force a new deployment** — the value is read once at container start, so editing the variable
   without rolling the service changes nothing and leaves CORS and verification-email links pointing
   at the old `http://` origin.
6. Add the `https://your-domain/login/oauth2/code/{google,github,microsoft}` callbacks in each
   provider console. Keep the localhost entries; all three providers accept a list.

`TRUSTED_PROXY_COUNT` stays `1`. Nothing in the application code changes.

**On cost — a domain is required, but it need not be bought.** AWS does not give domains away; Route
53 registration is ordinary paid registration (~$13/year for a `.com`) and there is no free tier for
it. ACM certificates are free, but only for a domain you can prove control of. Genuinely free
options that still satisfy ACM:

| Source | What you get | Catch |
|---|---|---|
| **GitHub Student Developer Pack** | A free `.me` for a year (Namecheap) and other registrar credits | Requires student status — **applicable here** |
| A registrar's first-year promo | `.xyz`/`.online` often ~$1–3 for year one | Renewal jumps to normal price |
| Free subdomain hosts (afraid.org &c.) | A subdomain of a shared domain | You must be able to add the **CNAME** ACM asks for; many only support A/TXT, which will not validate |

**Do not** use Freenom-style free TLDs (`.tk`, `.ml`, `.ga`, `.cf`) or dynamic-DNS hostnames. With
dynamic DNS you usually cannot add the exact validation record, and ACM's manual review flags those
TLDs — you get *"Additional verification required to request certificates for one or more domain
names"* and wait indefinitely. `deploy-https.sh` surfaces that status with this explanation rather
than just failing.

<a id="route-b"></a>
**Route B — CloudFront, HTTPS today without waiting on a domain (free). ✅ This is the route taken.**

This is the answer to "is a domain the *only* way to get HTTPS?" — it is not. A domain is the only
way to get HTTPS **on a name you choose**. AWS will happily give you HTTPS on a name *it* chooses,
because it already owns a certificate for that name.

Put a CloudFront distribution in front of the ALB and use its auto-issued `*.cloudfront.net`
certificate. `https://d3911jyxcju4q4.cloudfront.net` is a real, publicly trusted origin and **is
accepted by Google and Entra as a redirect URI**. Automated end to end by
[`aws/setup-cloudfront.sh`](../aws/setup-cloudfront.sh), which is idempotent — re-running it finds
the existing distribution rather than creating a second one. Four things must be right for this app
specifically:

- **Forward the `Authorization` header.** CloudFront's default cache policy *strips* it, which would
  make every authenticated request 401. Use the **CachingDisabled** cache policy plus the
  **AllViewer** origin request policy so headers, cookies and query strings pass through untouched.
- **Allow every HTTP method.** CloudFront defaults to `GET`/`HEAD`, which would break every
  `POST`/`PATCH`/`DELETE` in the API.
- **`TRUSTED_PROXY_COUNT` becomes `2`**, not `1` — there are now two proxies appending to
  `X-Forwarded-For`. Leave it at `1` and the anomaly detector and rate limiter both degrade silently
  ([GUIDE §7.8](GUIDE.md#78-deployment-parity)).
- **`OAUTH2_REDIRECT_BASE_URL` must be set** to the CloudFront origin, alongside moving
  `APP_DOMAIN`/`UI_APP_URL` there and re-registering the task definition. See the correction below
  for why this is not optional.

#### ⚠ The correction: `FORWARD_HEADERS_STRATEGY=framework` is **not** sufficient

Earlier revisions of this document (and of `PHASE-2-IMPLEMENTATION.md` and `aws/RUNBOOK.md`) asserted
that because `FORWARD_HEADERS_STRATEGY` is already `framework`, *"Spring will correctly emit
`https://` redirect URIs the moment TLS is in front of it."* **That is wrong, and it was verified
wrong against the live distribution on August 4, 2026.**

Spring builds the redirect URI from `{baseUrl}`, which it reconstructs from the request — and takes
the scheme from `X-Forwarded-Proto`. In a CloudFront→ALB chain that header is *not* trustworthy:
CloudFront sets it to `https`, and then **the ALB overwrites it** with its own listener protocol,
which is `http` because the ALB has no TLS listener (that being the whole reason CloudFront is
there). `framework` then faithfully honours a header that is itself a lie. The observed result,
straight off the deployed app:

```
GET https://d3911jyxcju4q4.cloudfront.net/oauth2/authorization/github
→ 302 Location: https://github.com/login/oauth/authorize?…
      &redirect_uri=http://d3911jyxcju4q4.cloudfront.net/login/oauth2/code/github
                    ^^^^ the host is right; the scheme is not
```

Google and Entra reject that URI outright, so the single wrong character reinstates exactly the block
CloudFront was deployed to remove. The fix is to stop deriving the origin from a header the app does
not control: `OAuth2ClientConfig` now reads **`OAUTH2_REDIRECT_BASE_URL`** and pins the scheme+host
of the redirect-URI template, applying it to all three provider registrations so they cannot drift.
Left blank (the local default) the request-derived behaviour is unchanged.

The trade-off of Route B is that the hostname is randomly assigned and cannot be customised — fine
for a demo, wrong for a product. Route A remains the production-shaped answer, and
`aws/deploy-https.sh` is still written and ready for the day a domain exists.

---

## Related documents

- [GUIDE.md](GUIDE.md) — how everything currently works
- [FEATURE-INVENTORY.md](FEATURE-INVENTORY.md) — the exhaustive, verifiable "everything that's built" checklist (explicitly excludes everything in this file)
- [IMPLEMENTATION-HISTORY.md](IMPLEMENTATION-HISTORY.md) — what was built, and what went wrong
- [flows/](flows/README.md) — click-to-database traces
