# Phase 2 — Complete Catalog of Additions

**Version:** 1.0
**Last Updated:** August 3, 2026
**Author:** Robert C. Oliver Jr.
**Status:** Final — handoff document for deliverable production

## Overview

An exhaustive, itemized catalog of **everything added to TesseraApp since the Phase 1 deliverable**
(Implementation 1, July 11, 2026). This document is written to be **self-contained**: it is the
single artifact needed to produce the Implementation 2 report, the final presentation, and the demo
script, without cross-referencing anything else.

Where [`PHASE-2-IMPLEMENTATION.md`](PHASE-2-IMPLEMENTATION.md) is a *scorecard* (what was promised
vs. delivered), this document is the *inventory* (every artifact that now exists and did not before).

### Measurement basis

| | |
|---|---|
| **Baseline commit** | `725c572` (June 19, 2026) — the last commit before the Phase 1 report |
| **Head** | `b7e087e` (August 3, 2026) |
| **Commits** | 31 |
| **Net change** | **423 files, +34,309 / −6,693 lines** |
| **New backend classes** | **43** |
| **New backend test classes** | **18** |
| **New frontend source files** | **18** (excluding specs) |
| **New frontend spec files** | **8** |
| **Existing frontend files reworked** | **103** |
| **New locale files** | **6** (495 keys each) |

> **Methodology note.** Commit `f914133` renamed the frontend workspace `securecapitaapp/` →
> `tesseraapp/`, which makes a naive diff report all 136 frontend files as "new." The counts above
> compare *app-relative paths* across the rename, so only genuine additions are counted. The bulk of
> Phase 2's frontend work was **reworking** the 103 carried-over files (i18n, paging, charts,
> authority gating), not creating new ones.

## Table of contents

- [1. Backend — 43 new classes](#1-backend--43-new-classes)
- [2. Frontend — new source files](#2-frontend--new-source-files)
- [3. Internationalization](#3-internationalization)
- [4. Testing — 0 → 221](#4-testing--0--221)
- [5. Cloud, CI/CD and infrastructure](#5-cloud-cicd-and-infrastructure)
- [6. Documentation](#6-documentation)
- [7. Defects fixed](#7-defects-fixed)
- [8. Status, and what is blocked](#8-status-and-what-is-blocked)

---

## 1. Backend — 43 new classes

### 1.1 Risk-based login and step-up authentication (10 classes)

Scores every sign-in against the account's history and escalates to a second factor when the attempt
looks anomalous. **No risk signal is echoed to the client** — the response is byte-identical to a
normal login, preserving anti-enumeration.

`LoginContext` · `LoginContextRowMapper` · `LoginRiskAssessment` · `LoginRiskQuery` ·
`LoginRiskReason` · `LoginRiskRepo` · `LoginRiskRepoImpl` · `LoginRiskService` ·
`LoginRiskServiceImpl` · `StepUpMethod`

Signals: **new device**, **new network location**. Escalation: `EMAIL_CODE` or `TOTP`.

### 1.2 Security dashboard (15 classes)

An administrative view of the identity system's security posture, backed by SQL aggregates.

`SecurityDashboardController` · `SecurityDashboardQuery` · `SecurityDashboardRepo` ·
`SecurityDashboardRepoImpl` · `SecurityDashboardService` · `SecurityDashboardServiceImpl` ·
`SecurityOverview` · `SuspiciousLoginEntry` · `SuspiciousLoginEntryRowMapper` · `RestrictedAccount` ·
`RestrictedAccountRowMapper` · `SessionActivity` · `MfaAdoption` · `DailyEventCount` ·
`LoginOutcomeTrendPoint`

Surfaces: suspicious-login feed, restricted/locked accounts, active session activity, MFA adoption
rate, daily event counts, and login outcome trends.

### 1.3 Analytics and services catalog (4 classes)

`AnalyticsController` — a dedicated, authority-gated analytics API. **This closed a real
authorization gap:** billing and analytics figures had been reachable through a customer-scoped
endpoint with only client-side gating.

`ServicesCatalogController` · `ServicesCatalogService` · `ServicesCatalogServiceImpl` — the billable
services catalog with administrative management.

### 1.4 Object storage (4 classes)

`ImageStorageService` (interface) · `LocalImageStorageService` · `S3ImageStorageService` ·
`AwsS3Config`

Profile images moved off the container filesystem to S3 behind an interface, so images survive
restarts and work across replicas. The local implementation is retained for development.

### 1.5 Security hardening and infrastructure (10 classes)

| Class | Purpose |
|---|---|
| `RateLimitFilter` | General rate limiting (Bucket4j) → `429` + `Retry-After` |
| `TrustedProxyConfigurer` | Parses `X-Forwarded-For` only to a configured depth, so a client cannot spoof its source IP behind a load balancer |
| `AuthDiagnosticsLogger` | Classifies every auth denial into 7 reasons (`[AUTH-DENY]`, `[AUTH-GRANT]`, `[RBAC-DENY]`, `[AUTH-LOCK]`) **server-side only** — the client response is unchanged |
| `ErrorDetailScrubber` | Strips `devMessage` and raw exception text from production error responses |
| `CapabilityCatalog` | The authoritative permission catalogue backing capability-based RBAC |
| `PasswordPolicy` | Centralized password rules |
| `ProviderLinkTicketService` | Short-lived, provider-bound tickets for federated account linking |
| `BrowserErrorPage` | Server-rendered error page for non-API navigation failures |
| `JacksonConfig` | JSON serialization contract |
| `EmailTemplate` | Typed email template definitions |

### 1.6 Extended existing backend

- **`RoleType`** — a 1–7 **privilege tier ladder** plus `canAssign()`, blocking
  privilege-elevation-by-proxy (an organization admin promoting a user to an unscoped higher role,
  then acting through them). Tiers are declared in code, deliberately **not** read from the database,
  because seeded role ids have drifted between environments.
- **`AdminUserController`** — organization scope enforcement, tier checks, audit-history paging, and
  `DELETE /{id}/sessions` (administrative session revocation — locking stops the *next* sign-in;
  revoking refresh families ends an intrusion already in progress).
- **`SecurityConfig`** — CSP, `Referrer-Policy`, `Permissions-Policy`, and a **single** CORS policy
  bean (it previously had a rival hardcoded list that silently won).
- **`NewUserEventListener`** — audit write failures are now swallowed and logged, so a failed audit
  insert can never break authentication.

**New endpoint groups:** `/admin/analytics/**`, `/admin/security/**`, `/admin/services/**`,
`DELETE /admin/user/{id}/sessions`.

---

## 2. Frontend — new source files

### 2.1 New feature screens

| File | Purpose |
|---|---|
| `features/security/security-overview/` | Security posture dashboard with independent paging |
| `features/services/services-admin/` | Administrative management of the services catalog |

### 2.2 New shared components

| File | Purpose |
|---|---|
| `shared/command-palette/` | Global ⌘K-style command palette |
| `shared/page-size-select/` | Unified page-size control used across every paged list |
| `shared/animations/route-animations.ts` | Route transition animations |

### 2.3 New services

`analytics.service.ts` · `security-dashboard.service.ts` · `services-catalog.service.ts` ·
`command-palette.service.ts` · `language.service.ts` · `transloco-loader.ts` ·
`current-user.service.ts`

`current-user.service.ts` decoupled the navbar from the customer-list response — the user's name
previously could not render until a page of customers had loaded, and seventeen templates had to
thread identity down purely on the navbar's behalf.

### 2.4 New authorization primitives

| File | Purpose |
|---|---|
| `directive/has-authority.directive.ts` | Declarative capability gating in templates |
| `guard/capability.guard.ts` | Route-level capability enforcement |
| `interface/security-overview.interface.ts` | Security dashboard contract |

### 2.5 Reworked (not new)

**103 existing frontend files were substantially reworked** — every feature screen received
internationalization, unified pagination, authority-aware rendering, and chart/empty-state
treatment. Notable: `stats.component.ts`'s `canViewBilling` moved from a field to a getter, fixing a
real defect where an expired token at page-load latched "no authorities" and routed administrators
to the wrong screen.

---

## 3. Internationalization

**Six locales, 495 keys each, at verified exact parity** (zero missing, zero extra keys in any
locale — machine-checked):

| Locale | File |
|---|---|
| English | `en.json` |
| German | `de.json` |
| Spanish | `es.json` |
| French | `fr.json` |
| Portuguese | `pt.json` |
| Chinese | `zh.json` |

Implemented with Transloco (`transloco-loader.ts`, `language.service.ts`) and a navbar language
selector. Every user-facing string across all feature screens was extracted to keys.

---

## 4. Testing — 0 → 221

Phase 1 shipped **14 backend tests and zero frontend specs**. Phase 2 ends at **221 total**.

### 4.1 New backend test classes (18)

| Test | Guards |
|---|---|
| `SessionServiceImplTest` | Refresh rotation + **reuse detection** |
| `TotpServiceImplTest` | TOTP enrollment and **challenge binding** |
| `AdminUserControllerOrgScopeTest` | Organization scope enforcement |
| `AdminUserControllerTest` | Admin operations, tier checks, self-target refusal |
| `AnalyticsControllerOrgScopeTest` | Analytics org scoping |
| `AnalyticsControllerSecurityTest` | Analytics method security |
| `LoginRiskServiceImplTest` | Risk scoring and step-up selection |
| `UserControllerBruteForceLockTest` | Persistent lockout after 5 failures / 15 min |
| `FederatedIdentityLinkTest` / `FederatedIdentityUnlinkTest` | Federated link/unlink, duplicate refusal |
| `SecurityDashboardServiceImplTest` | Dashboard aggregates |
| `CapabilityCatalogTest` | Permission catalogue integrity |
| `AuthDiagnosticsLoggerTest` | Denial classification |
| `ErrorDetailScrubberTest` | Production error hygiene |
| `NewUserEventListenerTest` | **A failing audit write cannot break authentication** |
| `EventServiceImplTest` | Audit persistence |
| `RequestUtilsIpAddressTest` | `X-Forwarded-For` parsing depth |
| `SqlTableCaseConsistencyTest` | Every query matches `schema.sql`'s exact table-name casing |

### 4.2 New frontend spec files (8)

`admin.guard.spec.ts` · `authentication.guard.spec.ts` · `capability.guard.spec.ts` ·
`has-authority.directive.spec.ts` · `token.interceptor.spec.ts` · `user.service.authority.spec.ts` ·
`command-palette.component.spec.ts` · `page-size-select.component.spec.ts`

Plus a reusable harness: `testing/jwt.ts`, `testing/local-storage.ts`, `testing/transloco-stub.ts`.

### 4.3 Totals

| | Phase 1 | Phase 2 | Change |
|---|---|---|---|
| Backend | 14 / 5 suites | **134 / 23 suites** | ×9.6 |
| Frontend | 0 | **87 / 8 files** | new |
| **Total** | **14** | **221** | **×15.8** |

`ng lint` is green and **gates in CI**. The production build is CI-verified.

> **Reading the test output:** the backend prints a full `Unknown column 'detail' in 'field list'`
> stack trace. It is **deliberately injected** by `NewUserEventListenerTest` to reproduce a real
> production incident. The suite is green when it appears.

---

## 5. Cloud, CI/CD and infrastructure

### 5.1 AWS (live)

| File | Purpose |
|---|---|
| `aws/setup.sh` | One-time infrastructure bootstrap |
| `aws/secrets-setup.sh` | Populates AWS Secrets Manager |
| `aws/push-to-ecr.sh` | Manual image push |
| `aws/task-definition.json` | ECS task template (`${VAR}` tokens filled at deploy) |
| `aws/ecs-service.json` | Service definition |
| `aws/deploy-https.sh` | ACM certificate + ALB `:443` listener + `:80`→`:443` redirect (**requires a domain; not run**) |
| `aws/setup-cloudfront.sh` | CloudFront distribution in front of the ALB — **HTTPS with no domain required**; idempotent (**run; live**) |
| `aws/RUNBOOK.md` / `.html` | Linear operational procedure |
| `aws/README.md` | AWS directory guide |

**Live and verified August 3, 2026:** ECS cluster `tessera-app-cluster`, service 1/1 running on task
definition **rev 13**, behind ALB `tessera-app-alb`, against **Aiven** managed MySQL (`db3`), with
CloudWatch logging at 7-day retention and env-driven log-level controls.

### 5.2 GCP (built, not deployed)

`gcp/setup.sh` · `gcp/secrets-setup.sh` · `gcp/cloudsql-setup.sh` · `gcp/cloudbuild.yaml` ·
`gcp/cloudrun-service.yaml` · `gcp/README.md` — Artifact Registry + Cloud Run.

### 5.3 CI/CD

| Workflow | Does |
|---|---|
| `.github/workflows/ci.yml` | `mvn verify` (OWASP dependency-check, **fails on CVSS ≥ 7**), `npm audit`, **`ng lint` gate**, frontend tests, production build |
| `.github/workflows/deploy.yml` | Push to `master` → ECR → ECS. **Gated behind the full CI workflow** |
| `.github/workflows/deploy-gcp.yml` | Artifact Registry → Cloud Run |

### 5.4 Multi-environment

Four profiles — `dev` / `qa` / `stage` / `prod` — driven from a **single env-configured image**
(`SPRING_ACTIVE_PROFILES`), with `.env.qa.example` and `.env.stage.example`. Production pins
`ddl-auto: validate` and `show-sql: false`.

---

## 6. Documentation

Consolidated **42 markdown files → 26**: fourteen granular topic guides folded into
`documentation/GUIDE.md` (11 sections, ~1,850 lines).

| Document | Holds |
|---|---|
| `GUIDE.md` | Architecture, setup, configuration, dev loop, backend, frontend, security, API, database, testing, deployment |
| `IMPLEMENTATION-HISTORY.md` | Build narrative, milestones, **a 20-entry problem log** |
| `FUTURE-ENHANCEMENTS.md` | Backlog, TODO audit, demo→product path |
| `PHASE-2-IMPLEMENTATION.md` | Roadmap scorecard and requirement traceability |
| `PHASE-2-ADDITIONS.md` | *This document* |
| `flows/` (17 files) | Click-to-database traces, Mermaid + `file:line` + JSON + SQL |

**Requirement traceability is greppable**, not asserted — **36 distinct FR/NFR IDs across 400+
citation sites in the code itself**:

```bash
grep -rhoE "\b(FR|NFR)-[A-Z]+-[0-9]+" src/main/java tesseraapp/src | sort | uniq -c | sort -rn
```

Top cited: `FR-TPF-1` (42) · `FR-ORG-2` (32) · `FR-TPF-2` (20) · `NFR-SEC-4` (18) · `FR-MFA-4` (18) ·
`NFR-SEC-7` (15) · `FR-ADMIN-1` (12) · `FR-JWT-5` (11) · `FR-RBAC-4` (10) · `FR-FED-5` (9).

---

## 7. Defects fixed

Documented in full as a 20-entry problem log in `IMPLEMENTATION-HISTORY.md` §4. Highlights:

| Defect | Resolution |
|---|---|
| Login returned **500** for every user | A schema drift made audit inserts fail, and Spring's synchronous event multicaster propagated the failure into login. The listener now swallows and logs |
| **Analytics authorization gap** | Billing/analytics data was gated only client-side → dedicated `AnalyticsController` with `@PreAuthorize` |
| **Two CORS policies** silently disagreed | A hardcoded list in `SecurityConfig` beat the configurable one; unified into one bean |
| Admins saw the **non-admin view** after token refresh | Authority flags were latched at construction rather than read from the current token |
| A usable **bearer token was logged to the browser console** | Removed |
| **Table-name casing** differed between environments | Every query aligned to `schema.sql`; guarded offline by `SqlTableCaseConsistencyTest` |
| **CSP broke icons and fonts** in production only | Policy corrected |
| A rejected JWT returned **400**, so silent refresh never fired | Corrected to 401 |
| `devMessage` **leaked in production** | `ErrorDetailScrubber` |
| Seeded **role ids drifted** between databases | Privilege tiers moved into code |
| **N+1 queries** on customer and invoice lists | Identified and documented; **not yet fixed** |

---

## 8. Status, and what is blocked

### 8.1 Complete

Risk-based step-up · organization scoping · rate limiting · S3 storage · security dashboard ·
analytics API · services catalog · capability RBAC · i18n (6 locales) · command palette ·
221 tests + lint gating in CI · AWS ECS deployment · GCP pipeline · multi-environment config ·
documentation consolidation.

### 8.2 The domain block — removed, without buying a domain

**A domain has still not been purchased.** Until August 4, 2026 that blocked one capability chain,
and this section previously recorded it as the project's headline limitation. **It no longer is.**

#### What was blocked, and why

1. **HTTPS/TLS on the load balancer.** AWS Certificate Manager will not issue a certificate for an
   `*.amazonaws.com` hostname the account does not control, so the ALB could only serve **plain
   HTTP** on port 80. `aws/deploy-https.sh` is written and ready but cannot run without a domain.
2. **Microsoft and Google federated login, in the deployed environment.** Both providers **refuse to
   register any `http://` redirect URI that is not `localhost`**, so the deployed app's callback
   *could not be entered into either console at all*. Never a code defect — the OAuth2
   implementation is complete and works locally against all three providers.

Two further capabilities had the same root cause: **WebAuthn / passkeys** (needs a secure context)
and **HSTS** (sent but inert without TLS).

> **Postscript, added 2026-08-07 — after this document's original snapshot date.** WebAuthn is no
> longer just unblocked; it is **built**: passkey registration, usernameless login, and
> admin-assisted revocation are implemented end-to-end (`PasskeyController`, `PasskeyServiceImpl`,
> `webauthn4j-core`, new `passkeycredentials` table). Full technical detail lives in
> `documentation/GUIDE.md` §7.10/§8.3/§9.3, not restated here to keep this snapshot's original
> content intact.

#### How it was solved — CloudFront, free

**`https://d3911jyxcju4q4.cloudfront.net`** — CloudFront distribution `E1WWY6FHSKI84P`, status
`Deployed`, fronting the ALB and terminating TLS on the auto-issued `*.cloudfront.net` certificate.
Created by the new, idempotent `aws/setup-cloudfront.sh`.

The insight: a domain is the only way to get HTTPS **on a name you choose**, but AWS will readily
give you HTTPS on a name *it* chooses, because it already holds a certificate for that name. Google
and Entra accept a `*.cloudfront.net` redirect URI, so this unblocks TLS, both federated providers,
WebAuthn and HSTS at zero cost. Three configuration details matter for this app specifically, all
handled by the script: forward the `Authorization` header (CloudFront's default cache policy
**strips** it, which would 401 every authenticated request), allow all seven HTTP methods
(the default is `GET`/`HEAD`), and raise `TRUSTED_PROXY_COUNT` to `2` — CloudFront and the ALB both
append to `X-Forwarded-For`, and getting this wrong degrades the anomaly detector and rate limiter
**silently**.

Buying a domain and running `aws/deploy-https.sh` remains the production-shaped answer (~$12–15/yr);
the CloudFront hostname is randomly assigned and cannot be customised — fine for a demo, wrong for a
product.

#### ⚠ The finding worth putting in the report

CloudFront alone did **not** fix federated login, and the reason is a good defence-in-depth lesson.

This document previously asserted that `FORWARD_HEADERS_STRATEGY=framework` was already set "so
Spring will correctly emit `https://` redirect URIs the moment TLS is in front of it." **Testing
against the live distribution proved that wrong.** Spring reconstructs the OAuth redirect URI's
scheme from `X-Forwarded-Proto`. CloudFront sets it to `https`; **the ALB then overwrites it** with
its own listener protocol — `http`, because the ALB has no TLS listener, that being the entire reason
CloudFront is there. The deployed app emitted:

```
redirect_uri=http://d3911jyxcju4q4.cloudfront.net/login/oauth2/code/github
              ^^^^ correct host, wrong scheme
```

One character, and the exact provider rejection CloudFront was deployed to eliminate comes back.
`OAuth2ClientConfig` now reads **`OAUTH2_REDIRECT_BASE_URL`** and pins the scheme and host of the
redirect-URI template instead of deriving them from a header the app does not control — applied to
all three provider registrations so they cannot drift, and left blank locally where the
request-derived default is correct.

**The generalisable point:** a setting that is correct in isolation (`framework` faithfully honours
the forwarded headers) produced a wrong result because a second proxy rewrote the signal it depends
on. Trusting a forwarded header is only sound when you control *every* hop that can write it — which
is the same principle `TRUSTED_PROXY_COUNT` exists to enforce for client IPs.

#### Remaining steps — operational, not architectural

| Step | State |
|---|---|
| CloudFront distribution live on HTTPS | ✅ Done, verified live |
| `OAUTH2_REDIRECT_BASE_URL` implemented and pinned across all three providers | ✅ Done |
| Deployed on **task-definition rev 14** setting `OAUTH2_REDIRECT_BASE_URL`, `UI_APP_URL` and `TRUSTED_PROXY_COUNT=2` | ✅ **Done — verified August 4, 2026.** The live authorize redirect now returns `redirect_uri=https://d3911jyxcju4q4.cloudfront.net/login/oauth2/code/github`, and the boot log prints `[NET] trusted-proxy-count=2` |
| `https://tesseraapp.dev/login/oauth2/code/{google,github,microsoft}` registered in each provider console | ✅ **Done (2026-08-08)** |
| Real Google and GitHub credentials in Secrets Manager | ✅ **Done (2026-08-08)** — see below |

**The transport problem is fully closed, and federation is now fully closed too.** All three
providers — Google, GitHub, Microsoft — hold real credentials in Secrets Manager and are confirmed
live via the authorize redirect on `https://tesseraapp.dev`.

> **Correction, superseded 2026-08-08.** Earlier revisions of this document (through 2026-08-04)
> stated that GitHub federated login worked in the deployed environment when it did not —
> `tessera-app/github-client-id` and `github-client-secret` held the literal `CHANGE_ME`, and the
> live authorize redirect returned `client_id=CHANGE_ME`. That was a real finding at the time, and
> it recurred once more on 2026-08-08 (re-pasted credentials turned out to be UUIDs, not real
> provider secrets, the same failure mode) before all three providers were verified with genuine
> credentials. GitHub sign-in now works, but only on `tesseraapp.dev` — its original OAuth App
> registered for the bare CloudFront URL is orphaned; Google and Microsoft still work on both.

### 8.3 ⚠ Other known gaps, stated plainly

| Gap | Detail |
|---|---|
| ~~**Database connection is not encrypted**~~ | ✅ **Fixed August 4, 2026.** The JDBC URL had `useSSL=false` hardcoded with no production override, while Aiven is reached over the public internet. Now `sslMode` is env-driven (`MYSQL_SSL_MODE`), defaulting to `PREFERRED` and pinned to `REQUIRED` by the `prod`/`qa`/`stage` profiles, with `allowPublicKeyRetrieval` off in those profiles. Verified against the live Aiven instance before enabling: it negotiates **TLSv1.3 / TLS_AES_256_GCM_SHA384**. ⚠ Still `REQUIRED`, not `VERIFY_IDENTITY` — the connection is encrypted but the server's certificate is not validated, which needs Aiven's per-project CA shipped into the image |
| ~~**SMS 2FA is stubbed**~~ | ✅ **Closed, delivered via voice call for now (2026-08-09).** `NotificationServiceImpl.sendTwoFactorCode` dispatches the code as a spoken phone call through `VoiceUtils`, not SMS: this Twilio number's US A2P 10DLC campaign registration is still pending carrier review (typically 5–10+ business days, sometimes weeks — too long for this deadline), and Twilio's Messaging API returns success the instant it *accepts* a message rather than once it's delivered, so an A2P-blocked text is silently dropped by the carrier with no exception thrown and gets billed anyway — confirmed against this account's own Twilio billing. Voice isn't A2P-gated, so it works today; confirmed live via a real call on 2026-08-09. Revert to SMS as the primary channel once the A2P campaign clears review. Still degrades to a logged code when Twilio secrets are unconfigured (dev/CI). **TOTP is the other fully functional second factor** |
| **Rate-limit buckets are in-memory** | Limits will not hold across replicas; a shared store is needed for scale-out |
| **No rate-limit `429` test, no SMS-toggle test** | Both were specified and are absent |
| **`start.sh` uses system `mvn`**, not `./mvnw` | Reproducibility gap; one-line fix |
| **N+1 queries** on customer/invoice lists | Identified, documented, unfixed |
| **Placeholder `JWT_SECRET`** in `.env.example` | Mitigated: `JwtSecretGuard` fails fast under `prod` |
| ~~**Placeholder OAuth credentials in Secrets Manager**~~ | ✅ **Closed (2026-08-08)** — all three providers now hold real credentials, confirmed live. There is still **no fail-fast guard** for a placeholder slipping back in, unlike `JWT_SECRET` — the app would boot happily and only fail at the provider's authorize endpoint. A worthwhile small addition |
| **`schema.sql` applied by hand** | Deliberate trade — no migration tool |
| **`assignments/` is gitignored** | The SRS and Phase 1 report have **no version-control backup** |

### 8.4 Figures to produce for the deliverable

1. Deployment topology (browser → **CloudFront (TLS)** → ALB → ECS Fargate → Aiven MySQL → S3).
   Annotate the two `X-Forwarded-*` rewrite points — it is what makes `TRUSTED_PROXY_COUNT=2` and
   `OAUTH2_REDIRECT_BASE_URL` necessary, and it makes the §8.2 finding legible in one picture
2. Sequence: risk-based login with step-up
3. Sequence: refresh-token rotation and reuse detection
4. RBAC privilege-tier ladder (1–7)
5. Organization-scoping boundary
6. ER diagram (identity tables vs. JPA business tables)
7. CI/CD pipeline
8. Test growth, 14 → 221
9. UI screenshots — dashboard, analytics, Security Center, admin users, services catalog, language switcher

> The Phase 1 report's eight figure images were never committed and are unrecoverable; screenshots
> must be retaken.
