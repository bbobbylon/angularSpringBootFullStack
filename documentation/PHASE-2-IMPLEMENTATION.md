# Phase 2 Implementation Record

**Version:** 1.0
**Last Updated:** August 3, 2026
**Author:** Robert C. Oliver Jr.
**Status:** Draft — source document for the Implementation 2 deliverable

## Overview

This document records **everything built between the Phase 1 deliverable (Implementation 1, dated
July 11, 2026) and August 3, 2026**. It exists to be the single input for producing the
Implementation 2 report, the final presentation, and the demo script — so it is written as evidence,
not as narrative: every claim is traceable to a file, a commit, or a test count.

It answers three questions in order: *what did Phase 1 promise*, *what was actually delivered*, and
*what is still honestly outstanding*.

### Scope boundary

| | |
|---|---|
| **Baseline** | Implementation 1 report, July 11, 2026 (`assignments/implementation-1-report.md`) |
| **Head** | August 3, 2026 — `de55b9d` plus one verified-but-uncommitted change set |
| **Commits in range** | 31 |
| **Net change** | 416 files, +32,931 / −6,197 lines |

> **Note on uncommitted work.** The final change set (privilege-tier RBAC, admin session revocation,
> CORS unification, navbar decoupling) is complete and fully verified green but **not yet committed**
> as of this writing. It is marked ⚠ throughout. Commit it before citing it as delivered.

## Table of contents

- [1. Phase 2 roadmap scorecard](#1-phase-2-roadmap-scorecard)
- [2. Phase 1 limitations — then and now](#2-phase-1-limitations--then-and-now)
- [3. Delivered beyond the roadmap](#3-delivered-beyond-the-roadmap)
- [4. Quality and verification](#4-quality-and-verification)
- [5. Requirements traceability](#5-requirements-traceability)
- [6. Honest remaining gaps](#6-honest-remaining-gaps)
- [7. Figure manifest for the deliverable](#7-figure-manifest-for-the-deliverable)
- [8. Related documents](#8-related-documents)

---

## 1. Phase 2 roadmap scorecard

Phase 1 §9 committed to eight items. **Seven were delivered; one was not.**

| Pri | Commitment | Status | Evidence |
|---|---|---|---|
| **P1** | Security-critical-path tests — refresh rotation / reuse detection, TOTP challenge binding, org-scope enforcement | ✅ **Delivered** | `SessionServiceImplTest` (4), `TotpServiceImplTest` (5), `AdminUserControllerOrgScopeTest` (5), `AnalyticsControllerOrgScopeTest` (8) |
| **P1** | Frontend specs — Angular component and service tests | ✅ **Delivered** | 8 spec files / **87 tests**, Vitest + jsdom via `@angular/build:unit-test` |
| **P2** | Switch `start.sh` to the Maven wrapper for reproducible builds | ❌ **Not done** | `start.sh:208` still calls bare `mvn spring-boot:run` |
| **P2** | Expose MySQL `3306` in `docker-compose.yml` | ✅ **Delivered** | `docker-compose.yml:8` — `"${MYSQL_HOST_PORT:-3306}:3306"` |
| **P3** | Drop redundant `jjwt` artifacts from `pom.xml` | ✅ **Delivered** | `pom.xml:37` carries the removal note; only `com.auth0:java-jwt` remains |
| **P3** | Validate a full prod-profile boot (`ddl-auto: validate`) | ✅ **Delivered** | `application-prod.yml:31-32` pins `ddl-auto: validate` + `show-sql: false`; runs on AWS ECS Fargate via `deploy.yml`. *Live service state is external to the repo — re-check before citing.* |
| **P3** | Migrate profile images to object storage | ✅ **Delivered** | `ImageStorageService` abstraction with `LocalImageStorageService` / `S3ImageStorageService` |
| **P3** | General rate limiting (`429 + Retry-After`) | ✅ **Delivered** | `filter/RateLimitFilter.java`, Bucket4j 8.10.1 |

**The one miss is worth stating plainly rather than quietly dropping:** `start.sh` still invokes the
system `mvn`, so a machine with a different Maven version can still produce a different build. It is
a one-line change (`mvn` → `./mvnw`) and is the cheapest outstanding item in the project.

---

## 2. Phase 1 limitations — then and now

Phase 1 §8 listed seven limitations carried forward. **Five are closed, two remain.**

| Limitation (Phase 1) | Status | What changed |
|---|---|---|
| Security-critical-path tests absent | ✅ **Closed** | See scorecard P1 |
| No frontend specs | ✅ **Closed** | 87 tests; `ng lint` green and gating in CI |
| Profile-image storage is local filesystem | ✅ **Closed** | S3 behind an interface; local impl retained for dev |
| No general / distributed rate limiting | ✅ **Closed** | `RateLimitFilter` returns `429` + `Retry-After` |
| Federated login dormant pending credentials | ✅ **Closed (2026-08-08)** | All three providers wired (`47acebb`), working locally, and now live in production. The deployed environment was blocked because Google and Entra refuse any `http://` redirect URI outside `localhost`; solved via a CloudFront distribution on `https://tesseraapp.dev` with `OAUTH2_REDIRECT_BASE_URL` pinning the redirect origin (see §6.1), plus real Google/GitHub/Microsoft credentials populated in Secrets Manager and verified live via the authorize redirect |
| **SMS 2FA is stubbed** | ✅ **Closed, voice-delivered for now (2026-08-09)** | `NotificationServiceImpl.sendTwoFactorCode` dispatches the code as a spoken phone call via `VoiceUtils` rather than SMS: this Twilio number's US A2P 10DLC campaign registration is still pending carrier review (typically 5–10+ business days), and — confirmed against this account's Twilio billing — an A2P-blocked SMS send is accepted and charged by Twilio's API, then silently dropped by the carrier with no exception thrown, so an SMS-first design could never detect the failure to fall back from. Voice isn't A2P-gated, so it works today; confirmed live via a real call. Still degrades to a logged code when Twilio secrets are unconfigured (dev/CI). Revert to SMS as the primary channel once the A2P campaign clears review. TOTP remains the other fully functional second factor |
| **Placeholder JWT secret** | ⚠ **Still open** | `.env.example` still ships a placeholder. Mitigated, not removed: `JwtSecretGuard` fails fast under `prod` if the placeholder is present or the secret is too short |

---

## 3. Delivered beyond the roadmap

The roadmap was a hardening list. Most of Phase 2's volume went into capability that was never on it.

### 3.1 Security and identity

- **Risk-based login step-up (FR-TPF-1)** — `LoginRiskServiceImpl` scores each sign-in for new
  device / new network location and escalates to `EMAIL_CODE` or `TOTP`. **No risk signal is echoed
  to the client**, preserving anti-enumeration.
- **Organization-scoped access control (FR-ORG-2)** — tenant boundaries enforced on users, analytics,
  customers, and invoices. `Customer.organization_id` added.
- **Persistent brute-force lockout** — 5 failures in a 15-minute window now produces a *persistent*
  lock requiring administrator unlock, replacing the in-memory counter.
- **Auth diagnostics (`AuthDiagnosticsLogger`)** — server-side classification of every denial into
  seven reasons (`[AUTH-DENY]`, `[AUTH-GRANT]`, `[RBAC-DENY]`, `[AUTH-LOCK]`). **The client response
  is deliberately unchanged** — diagnosis for the operator, opacity for the attacker.
- **Security headers** — CSP, `Referrer-Policy`, `Permissions-Policy` in `SecurityConfig`.
- **Trusted proxy handling** — `X-Forwarded-For` parsed only to a configured depth
  (`TRUSTED_PROXY_COUNT`), so a client cannot spoof its source IP behind a load balancer.
- ⚠ **Privilege-tier RBAC** — `RoleType` gained a 1–7 tier ladder plus `canAssign()`;
  `AdminUserController.requireAssignableTier` blocks **privilege-elevation-by-proxy** (an org admin
  promoting an in-scope user to unscoped `ROLE_ADMIN`, then acting through them).
- ⚠ **Administrative session revocation** — `DELETE /admin/user/{id}/sessions`. Locking stops the
  *next* sign-in; only revoking refresh families ends an intrusion already in progress.

### 3.2 Administration and RBAC surface

- **Admin security dashboard** (`SecurityDashboardController` → `/security` Security Center)
- **Capability-based RBAC guards and UI gating** — `hasAuthority` directive + `capability.guard`
- **Admin user management** — `AdminUserController` with role assignment, account state, audit
  history paging (FR-ADMIN-1…5)
- **Roles matrix** view for the permission catalogue
- **Analytics API** (`AnalyticsController`) — closed an authorization gap where billing/analytics
  data was reachable through a customer-scoped endpoint

### 3.3 Product features

Ten frontend feature areas now exist (`analytics`, `auth`, `billing`, `customers`, `home`,
`invoices`, `profile`, `security`, `services`, `users`) against nine backend controllers.
New in Phase 2: **billing overview**, **analytics dashboards**, **services catalog** (+ admin
management), **invoice editing**, and a **global command palette**.

### 3.4 Internationalization

**Six locales** — English, German, Spanish, French, Portuguese, Chinese — at **495 keys each**,
verified at **exact parity** (zero missing, zero extra). Implemented with Transloco.

### 3.5 Cloud, CI/CD and observability

- **AWS** — ECR + ECS Fargate via `deploy.yml`; secrets in AWS Secrets Manager; Aiven MySQL;
  `aws/RUNBOOK.md` as the operational procedure; `aws/deploy-https.sh` ⚠ (untracked) automates ACM
  certificate + ALB HTTPS:443 listener + `:80→:443` redirect
- **GCP** — Artifact Registry + Cloud Run via `deploy-gcp.yml`
- **CI** — `ci.yml` builds and tests against a live MySQL; **`ng lint` gates**
- **Four environments** — `dev` / `qa` / `stage` / `prod` profiles, single env-driven image
- **CloudWatch logging** — 7-day retention, env-driven `LOG_LEVEL_*` knobs

### 3.6 Documentation

Consolidated **42 markdown files → 26**: fourteen granular topic guides collapsed into
`documentation/GUIDE.md` (eleven sections), with `IMPLEMENTATION-HISTORY.md` and
`FUTURE-ENHANCEMENTS.md` alongside. Complete click-to-database **flow documentation** (14 files)
added under `documentation/flows/`.

---

## 4. Quality and verification

| Metric | Phase 1 (Jul 11) | Phase 2 (Aug 3) | Change |
|---|---|---|---|
| **Backend tests** | 14 / 5 suites | **134 / 23 suites** | ×9.6 |
| **Frontend tests** | 0 | **87 / 8 files** | new |
| **Total** | **14** | **221** | **×15.8** |
| `ng lint` | not gating | **green, gating in CI** | — |
| Production build | manual | **green, CI-verified** | — |
| i18n locales | 1 | **6 @ 495 keys, parity verified** | — |

Notable guard tests, each written against a real defect rather than for coverage:

- `UserControllerLoginEnumerationTest` — unknown email and wrong password produce byte-identical responses
- `JpaSchemaSyncTest` — offline Hibernate-vs-`schema.sql` drift guard
- `SqlTableCaseConsistencyTest` — every query matches `schema.sql`'s exact table-name casing
- `NewUserEventListenerTest` — proves a failing audit write can never break authentication

> **Reading the backend test output:** it prints a full `Unknown column 'detail' in 'field list'`
> stack trace. That is **deliberately injected** by `NewUserEventListenerTest` to reproduce a real
> production incident. The suite is green when it appears — trust `target/surefire-reports/*.txt`,
> not the console tail.

---

## 5. Requirements traceability

Requirement IDs are cited **in the code itself** — 36 distinct IDs across 400+ citation sites,
which makes traceability verifiable by `grep` rather than by assertion:

```bash
grep -rhoE "\b(FR|NFR)-[A-Z]+-[0-9]+" src/main/java tesseraapp/src | sort | uniq -c | sort -rn
```

Most-cited, with the Phase 2 work that realises them:

| ID | Citations | Realised by |
|---|---|---|
| `FR-TPF-1` | 42 | Risk-based login step-up |
| `FR-ORG-2` | 32 | Organization-scoped analytics and data access |
| `FR-TPF-2` | 20 | Threat-protection follow-through |
| `NFR-SEC-4` | 18 | Transport and header hardening |
| `FR-MFA-4` | 18 | TOTP enrollment and challenge binding |
| `NFR-SEC-7` | 15 | Anti-enumeration across auth and admin surfaces |
| `FR-ADMIN-1` | 12 | Administrative user management |
| `FR-JWT-5` | 11 | Refresh rotation and reuse detection |
| `FR-RBAC-4` | 10 | Capability-based authorization |
| `FR-FED-5` | 9 | Federated provider audit attribution |

---

## 6. Honest remaining gaps

State these in the deliverable rather than omitting them; each is small, known, and evidenced.

### 6.1 The domain question — solved without buying a domain

**No domain has been purchased, and none is needed.** This section previously recorded the missing
domain as the project's most consequential open item, because it blocked HTTPS and therefore Google
and Microsoft federated login. That block has been removed.

**What was true.** AWS Certificate Manager will not issue a certificate for an `*.amazonaws.com`
hostname the account does not control, so the ALB could only ever serve `80/HTTP`. Google and Entra
both refuse to register any `http://` redirect URI that is not `localhost`, so the deployed app's
callback could not be entered into either console *at all*. The OAuth2 code was complete and worked
locally against all three providers the whole time — this was never a code defect.

**What changed (August 4, 2026).** A **CloudFront distribution in front of the ALB costs nothing**
and supplies a publicly trusted origin on a certificate AWS already owns:

**`https://d3911jyxcju4q4.cloudfront.net`** — distribution `E1WWY6FHSKI84P`, status `Deployed`,
created by the idempotent [`aws/setup-cloudfront.sh`](../aws/setup-cloudfront.sh).

Google and Entra *do* accept a `*.cloudfront.net` redirect URI, so this unblocks TLS, both federated
providers, WebAuthn (which needs a secure context) and HSTS — for free.

> **Postscript, added 2026-08-07.** WebAuthn has since been built, not just unblocked — see
> `documentation/GUIDE.md` §7.10/§8.3/§9.3 for the passkey registration/login/admin-revoke
> implementation.

The trade-off is a randomly assigned hostname that cannot be customised: fine for a demo, wrong
for a product. Buying a domain
and running `aws/deploy-https.sh` remains the production-shaped alternative, and that script is
written and ready.

#### The non-obvious part, and a correction to this document

Earlier revisions of this section claimed that `FORWARD_HEADERS_STRATEGY=framework` was already set
"so Spring emits `https://` redirect URIs as soon as TLS is in front of it." **That was wrong**, and
testing against the live distribution proved it. Spring derives the redirect URI's scheme from
`X-Forwarded-Proto`. CloudFront sets that header to `https` — and then **the ALB overwrites it** with
its own listener protocol, `http`, because the ALB has no TLS listener. `framework` honours the
header faithfully; the header is the thing that is wrong. The deployed app therefore emitted:

```
redirect_uri=http://d3911jyxcju4q4.cloudfront.net/login/oauth2/code/github
```

Correct host, wrong scheme — and that one character reinstates the exact rejection CloudFront was
deployed to eliminate. `OAuth2ClientConfig` now reads **`OAUTH2_REDIRECT_BASE_URL`** and pins the
scheme and host of the redirect-URI template rather than deriving them from a header the application
does not control. It is applied to all three provider registrations so they cannot drift apart, and
left blank locally, where the request-derived default is correct.

This is a worthwhile finding in its own right for the deliverable: a defence-in-depth configuration
(`framework`) that is *correct in isolation* silently produced a wrong result because a second proxy
rewrote the signal it depends on. Trusting a forwarded header is only sound when you control every
hop that can write it.

#### Remaining, and honest about it

| Step | State |
|---|---|
| CloudFront distribution live on HTTPS | ✅ Done, verified |
| `OAUTH2_REDIRECT_BASE_URL` implemented in `OAuth2ClientConfig` | ✅ Done |
| Deployed on **task-definition rev 14** with the variable set | ✅ **Done — verified August 4, 2026.** `redirect_uri` now comes back `https://…`; `[NET] trusted-proxy-count=2` in the boot log |
| Callback URLs registered in the Google, GitHub and Entra consoles | ✅ **Done (2026-08-08)** |
| Google and GitHub credentials in Secrets Manager | ✅ **Done (2026-08-08).** All three providers hold real credentials, confirmed live via the authorize redirect on `https://tesseraapp.dev` |

The deployed app confirms the mechanism works end to end, including credentials. GitHub only works
on `tesseraapp.dev` — its original OAuth App registered for the bare CloudFront URL is orphaned;
Google and Microsoft work on both URLs.

Nothing else ever depended on the domain: deploys, the database, analytics, RBAC and i18n all ran
normally throughout. Full operational detail: [`aws/RUNBOOK.md`](../aws/RUNBOOK.md) Part F.

### 6.2 Other gaps

1. **SMS 2FA remains a stub** — TOTP is the working second factor.
2. **Placeholder `JWT_SECRET` in `.env.example`** — mitigated by `JwtSecretGuard` fail-fast under `prod`.
3. **`start.sh` uses system `mvn`**, not `./mvnw` — the one unmet Phase 2 roadmap item.
4. **N+1 queries on the customer and invoice lists** — identified, documented, not yet fixed.
5. **Microsoft federated login blocked by `AADSTS90023`** — an Entra portal configuration task.
6. **`schema.sql` is applied by hand** — a deliberate trade (no migration tool) with a documented cost.
7. **The `assignments/` directory is gitignored** — only two files there are tracked, so the SRS and
   the Phase 1 report have **no version-control backup**.

---

## 7. Figure manifest for the deliverable

A text-only submission has already cost marks once on this project. Produce these before drafting:

| # | Figure | Source |
|---|---|---|
| 1 | System context / deployment diagram (browser → ALB → ECS → Aiven MySQL → S3) | `documentation/GUIDE.md` §1, `aws/RUNBOOK.md` |
| 2 | Sequence: risk-based login with step-up (FR-TPF-1) | `LoginRiskServiceImpl` |
| 3 | Sequence: refresh-token rotation and reuse detection | `SessionServiceImpl`, `flows/05-token-refresh-sessions.md` |
| 4 | RBAC privilege-tier ladder (GUEST 1 → APPLICATION_ADMIN 7) | `RoleType` |
| 5 | Organization-scoping boundary diagram | `flows/20-admin-users-rbac.md` |
| 6 | ER diagram (identity tables vs JPA business tables) | `GUIDE.md` §9.2 |
| 7 | CI/CD pipeline (GitHub Actions → ECR → ECS; parallel GCP path) | `.github/workflows/` |
| 8 | Test-growth chart, 14 → 221 | §4 above |
| 9 | UI screenshots — dashboard, analytics, Security Center, admin users, catalog, i18n switcher | running app |

> The Phase 1 report's eight `figures/fig-4-*.png` were **never committed** and are unrecoverable —
> screenshots for Phase 2 must be retaken.

---

## 8. Related documents

- [`GUIDE.md`](GUIDE.md) — how the system works (architecture, security model, API, database, deployment)
- [`IMPLEMENTATION-HISTORY.md`](IMPLEMENTATION-HISTORY.md) — whole-project narrative and the 20-entry problem log
- [`FUTURE-ENHANCEMENTS.md`](FUTURE-ENHANCEMENTS.md) — forward backlog with evidence citations
- [`flows/`](flows/README.md) — click-to-database traces for every user flow
- [`../aws/RUNBOOK.md`](../aws/RUNBOOK.md) — AWS operational procedure
