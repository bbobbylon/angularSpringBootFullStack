# Project Status & Roadmap

A planning snapshot that reconciles what the **submitted course deliverables** (commit `0a2f3ea`) and the **in-progress SRS revision 1.0** claim against what the code on branch `MastersProjectSRSImpl` actually does today: the SecureCapita→TesseraApp rebrand drift, the planned-vs-implemented feature picture (federation, TOTP, refresh-session rotation, org scoping, the genuine rate-limiting gap), the corrected test-coverage story, the honest remaining leftovers, the next deliverable, and a prioritized post-assignment-5 backlog.

> **Audience:** the author/maintainer and graders tracking what is built vs documented; anyone resubmitting or reusing the deliverables.
> **Status legend:** `✅` implemented & wired · `⚠️` built but conditional/not production-wired · `❌` not built / planned. **Severity:** High / Medium / Low (documentary impact, not runtime risk).
> **Code wins.** Where a deliverable, the SRS, or this doc disagrees with the code, **the code is authoritative** and the document should be corrected. Every status claim below carries a `file:line` (or `XClass.java`) citation so it can be re-verified.
> **See also:** [README.md](README.md) (docs hub) · [security.md](security.md) (auth/token internals the feature table cites) · [configuration.md](configuration.md) (env vars, secrets, gotchas) · [06-26-26SRSv1.0.md](06-26-26SRSv1.0.md) (the SRS due next) · [`../branch-changelog.md`](../branch-changelog.md) (authoritative change record) · [`../software_requirements_specification.md`](../software_requirements_specification.md) (root SRS rev 0.3 — accurate status table).

---

## Table of contents

1. [Executive status](#1-executive-status)
2. [Proposed vs actual](#2-proposed-vs-actual)
3. [Feature implementation snapshot](#3-feature-implementation-snapshot)
4. [Known limitations & remaining leftovers](#4-known-limitations--remaining-leftovers)
5. [Upcoming deliverables](#5-upcoming-deliverables)
6. [Post-assignment-5 backlog](#6-post-assignment-5-backlog)
7. [Change-record provenance & doc consolidation](#7-change-record-provenance--doc-consolidation)

---

## 1. Executive status

**The code is ahead of every document that describes it.** The headline CIAM features — in-house JWT auth, refresh-session rotation with family-wide reuse detection, authenticator-app TOTP MFA, federated OAuth2/OIDC login, permission-based RBAC with organization scoping, audit logging, and admin user management — are all implemented and verifiable in source on branch `MastersProjectSRSImpl`. What lags is the paperwork: the submitted deliverables still say "SecureCapita" (the app was renamed **TesseraApp** in commit `0a2f3ea`), and the SRS revision now in progress (`documentation/06-26-26SRSv1.0.md`) *understates* the build by re-flagging several shipped features as "(planned)."

Two limitations the deliverables list as future work are **already fixed**: the frontend API base is environment-driven (`environment.apiUrl`; production resolves to a relative origin), and test coverage rose from the oft-repeated "near-zero / single context-load test" to **14 tests across 6 suites, all green**. The genuinely open items are narrow and named in §4: no dedicated tests for the security-critical paths, zero frontend specs, SMS that only dispatches when Twilio credentials are present, a placeholder `JWT_SECRET` in `.env.example` (fail-closed in prod via `JwtSecretGuard`), a redundant JWT library on the classpath, and local-filesystem profile-image storage. General/distributed request rate limiting (429 + `Retry-After`) is the one advertised security control that is **not** built — though a per-account brute-force lockout is.

---

## 2. Proposed vs actual

Each row reconciles a claim in the **submitted deliverables** (`git show 0a2f3ea:deliverables/*.md`; the `deliverables/` tree was pruned from the working tree post-submission, commit `37cfefb`, recoverable from history) and/or the **in-progress SRS rev 1.0** against the current code. *Severity* is documentary impact.

| Topic | Proposed / submitted | Actual now | Severity | Recommendation |
|---|---|---|---|---|
| **Brand: SecureCapita → TesseraApp** | All five deliverables name the product "SecureCapita" throughout (titles, abstracts, login wireframe, navbar mockup). | Rebranded to **TesseraApp** in `0a2f3ea`; `Grep "SecureCapita"` over `securecapitaapp/src/**` returns **0** matches; both SRS docs say TesseraApp. (Submitted docs were already inconsistent — demo accounts use `@tessera.dev` / `TesseraDemo@1`.) | **High** | On any resubmission/reuse, global-replace SecureCapita→TesseraApp (incl. the shield-lock mark + login mockup). The SRS is now the single source of brand truth. |
| **Federated OAuth2/OIDC login** | Deliverables: IMPLEMENTED (correct). **SRS rev 1.0 regresses it** — §4.3 "(planned)", EIR-SW-1 "(planned)", DB-6 "(planned)". | ✅ Implemented: `OAuth2LoginSuccessHandler` (token-exchange seam), `FederatedAuthController` (`GET /oauth2/providers`), `OAuth2ClientConfig` + `FederatedProviderCatalog` (env-conditional), `oauthproviderlinks` table. Root SRS rev 0.3 documents it as built. | Medium | In the SRS, mark federation IMPLEMENTED (qualify "dormant until provider credentials are supplied"); un-flag DB-6 and EIR-SW-1. |
| **Authenticator-app TOTP MFA** | Deliverables: IMPLEMENTED. **SRS rev 1.0**: FR-MFA-4 "(planned)"; §4.5 describes the second factor as SMS-only. | ✅ In-house: `TotpService`/`TotpServiceImpl`, `TotpUtils` (RFC 6238, no external lib), `TotpController` (`/user/totp` setup/enable/disable/status + `/user/verify/totp`), hashed single-use recovery codes, server-side challenge binding. | Medium | Promote FR-MFA-4 to implemented; restore the DB-15..18 entities (TOTP credentials, recovery codes, MFA challenges) rev 1.0 dropped from rev 0.3. |
| **Refresh-token rotation, reuse detection & session/device mgmt** | Deliverables: the architectural centerpiece (IMPLEMENTED). **SRS rev 1.0 sells it short** — FR-JWT-4 has no rotation, FR-JWT-5 appends "(rotation w/ reuse detection: planned)", and §4.11 Session & Device Management (FR-SES-1..4) + DB-18 `refreshsessions` were dropped. | ✅ `SessionService`/`SessionServiceImpl` (single issuance seam: `issueTokenPair` + `rotate` with family-wide reuse revocation), `refreshsessions` table, `SessionController` (list/revoke/log-out-everywhere), Security Center sessions panel. | Medium | Restore rotation/reuse-detection + Session & Device Management (and DB-18) as IMPLEMENTED — this is the project's headline security feature. |
| **Organization-scoped administration** | Deliverables: IMPLEMENTED (FR-ORG). **SRS rev 1.0** keeps FR-ORG-1..3 as "shall" but flags DB-4 Organizations / DB-5 User-Organization "(planned)" — self-contradictory. | ✅ `OrganizationService`/`OrganizationServiceImpl` (shared-active-membership predicate), `OrganizationQuery`, `organizations` + `userorganizations` tables, enforced in `AdminUserController`; out-of-scope → 403, `APPLICATION_ADMIN` bypasses. | Medium | Un-flag DB-4/DB-5 so the entities match the FR-ORG "shall" statements and the real schema. |
| **Rate limiting / brute-force lockout** | Deliverables list only "distributed rate limiting" as future work; don't credit the lockout. **SRS rev 1.0**: FR-TPF-3 (429 + `Retry-After`) "(planned)", no mention of the lockout. | ⚠️/❌ split: per-account brute-force lockout **is** built — `UserController.authenticate()` rejects after 5 `LOGIN_ATTEMPT_FAILURE` events in a 15-min window (`EventService.countRecentFailuresByEmail`). General/distributed rate limiting with 429/`Retry-After` is **not** built. | Low | Credit the per-account lockout (as rev 0.3 does); scope FR-TPF-3 to the still-planned *general/distributed* limiter so the doc neither over- nor under-states. |
| **Automated test coverage** | Deliverables/root SRS C.1: "currently minimal" / "a single context-load test and no frontend specs." | ⚠️ Stale framing. **14 tests / 6 suites**, all green: `AngularSpringBootFullStackApplicationTests` (full-context `contextLoads`), `CustomerServiceImplTest`, `GlobalExceptionHandlerTest`, `UserControllerLoginEnumerationTest` (anti-enumeration regression), `tooling/JpaSchemaSyncTest` (offline schema-drift guard). | Medium | Reword to "modest but real (14 tests / 6 suites, incl. a full context load + a schema-drift guard)," keeping the honest note that security-path tests and frontend specs remain the priority gap. |
| **Frontend API base "hardcoded"** | Deliverables + root SRS C.1 (NFR-PORT-3): "frontend coupled to a fixed API origin / pins `http://localhost:8080`." | ✅ Already fixed. `user/customer/admin-user.service.ts` all use `environment.apiUrl`; `environment.ts` = `http://localhost:8080` (dev), `environment.production.ts` = `''` (relative). | Medium | Remove from limitations/future-work and delete the stale NFR-PORT-3 bullet from root SRS Appendix C.1 — no longer true. |
| **SMS/Twilio "commented out"** | Root SRS C.1: "The Twilio API call is commented out to avoid charges." (Deliverables' "stubbed in dev" framing is fine.) | ⚠️ Graceful degradation, not commented out: `SMSUtils.java` imports + calls `Twilio.init` + `Message.creator` and **sends** when `TWILIO_ACCOUNT_SID`/`AUTH_TOKEN`/`FROM_NUMBER` are all set; otherwise `log.warn "Twilio is not configured; SMS not sent."` | Low | Reword root SRS C.1 to "SMS degrades gracefully — sends via Twilio when credentials are configured, otherwise logs the code." |
| **Business UI (billing + services catalog)** | Submitted screen/UI inventory (architecture §8, impl-1 §4) lists customers + invoices but **not** a billing overview or services catalog. | ✅ Both exist: `features/billing/billing/` and `features/services/services-catalog/`, added in `0a2f3ea`. SRS rev 1.0 does cover them (FR-BM-3); the submitted architecture/impl docs don't. | Low | If resubmitting architecture/impl docs, add both screens to the UI inventory. |
| **Placeholders & broken cross-refs** | Deliverables carry `[Your Name]`/`[Second Author]`/`[Course Code]`/`[Date]` and scaffold markers; final-report Appendix C cross-references `deliverables/*.md` + `documentation/*`. | ⚠️ SRS now has real metadata (Robert C. Oliver Jr., Travis L. Lester; CPSC-69100-007). The `deliverables/` corpus + several docs were pruned (`37cfefb`), so Appendix C cross-refs point to deleted files. | Low | Before reuse, fill author/course/date (mirror the SRS) and restore companion files from git or fix the cross-refs. Citation sections are still empty scaffolds. |
| **Redundant JWT library** | Not in deliverables; root SRS C.1 flags both `jjwt` and `java-jwt` declared, only `java-jwt` used. | ⚠️ Confirmed: `pom.xml` declares `jjwt-api`/`jjwt-impl`/`jjwt-jackson` **and** `com.auth0:java-jwt`; only `java-jwt` is used in `src`. | Low | Drop the three `jjwt` artifacts to shrink the dependency/CVE surface — no code change. |

---

## 3. Feature implementation snapshot

The status of each headline capability **as built**, independent of how any document describes it. This is the quick reference the §2 reconciliation defers to.

| Capability | Status | Code evidence |
|---|---|---|
| In-house email/password auth + JWT issue/verify | ✅ | `controller/UserController.java`, `tokenprovider/TokenProvider.java` (HMAC-SHA512) |
| Refresh-token rotation + family-wide reuse detection | ✅ | `service/serviceimpl/SessionServiceImpl.java`, `refreshsessions` table |
| Session / device management (list, revoke, log-out-everywhere) | ✅ | `controller/SessionController.java`; frontend `features/security/` Security Center |
| Authenticator-app TOTP MFA (RFC 6238, in-house) | ✅ | `service/serviceimpl/TotpServiceImpl.java`, `utils/TotpUtils.java`, `controller/TotpController.java` |
| TOTP recovery codes (10, `XXXXX-XXXXX`, SHA-256 hex, single-use) | ✅ | `service/serviceimpl/TotpServiceImpl.java`, `totprecoverycodes` table |
| SMS second factor | ⚠️ | `utils/SMSUtils.java` — live Twilio call **gated on credentials**; logs the code when unconfigured |
| Federated OAuth2/OIDC login | ✅ (dormant until creds) | `OAuth2LoginSuccessHandler`, `controller/FederatedAuthController.java`, `oauthproviderlinks` table |
| Permission-based RBAC (authority strings, top-down matchers) | ✅ | `configuration/SecurityConfig.java` |
| Organization-scoped administration | ✅ | `service/serviceimpl/OrganizationServiceImpl.java`, `controller/AdminUserController.java`, `organizations`/`userorganizations` |
| Admin user management | ✅ | `controller/AdminUserController.java`; frontend `features/users/`, `features/roles/` |
| Audit logging (15 event types, device + IP) | ✅ | `enumeration/EventType.java`, `listener/NewUserEventListener` |
| Customers / invoices business domain | ✅ | `controller/CustomerController.java`, JPA `repo/CustomerRepo`/`InvoiceRepo`/`ServicesRepo` |
| Billing overview + services catalog pages | ✅ (client-derived, no new endpoints) | `features/billing/billing/`, `features/services/services-catalog/` — reuse existing `/customer/*` GETs |
| Per-account brute-force login lockout (5 fails / 15 min) | ✅ | `UserController.authenticate()` + `EventService.countRecentFailuresByEmail` |
| General / distributed rate limiting (429 + `Retry-After`) | ❌ | Not implemented — the one advertised control that is genuinely absent |
| Frontend API base environment-driven | ✅ | `securecapitaapp/src/app/service/{user,customer,admin-user}.service.ts` → `environment.apiUrl` |
| Anti-enumeration login (generic error for unknown-email vs wrong-password) | ✅ | `UserController.authenticate()`; regression-guarded by `UserControllerLoginEnumerationTest` |

> **Note — admin-only is frontend-only for Analytics/Billing.** The `/analytics` and `/billing` routes are gated by `adminGuard` in the SPA, but the GET endpoints they consume (`/customer/stats`, `/customer/list`, `/customer/invoice/list`, `/customer/invoice/new`) fall through to `SecurityConfig.java:160`'s broad `requestMatchers(GET, "/**").hasAnyAuthority("READ:USER","READ:CUSTOMER")` rule — they do **not** require an admin authority. A non-admin authenticated user blocked from the *route* could still call those endpoints directly and receive the same system-wide data. The `BillingComponent` docstring's claim that admin scope is "double-checked server-side on every API call" is **inaccurate**; resolve the intended behavior before documenting it as a control.

---

## 4. Known limitations & remaining leftovers

Honest gap register — debt and stubs called out by name, never glossed. **Code wins:** each entry is a fact in source, not a plan.

| Leftover | Status | Where | Detail |
|---|---|---|---|
| Security-critical-path tests | ❌ | `src/test/java/...` | No dedicated tests for refresh rotation/reuse, TOTP challenge binding, or org scoping — the highest-value paths. The 14 existing tests cover context load, customer service, the global exception handler, login anti-enumeration, and offline schema drift. |
| Frontend specs | ❌ | `securecapitaapp/` | Zero Angular component/service specs. |
| SMS dispatch | ⚠️ | `utils/SMSUtils.java` | Sends only when `TWILIO_ACCOUNT_SID` / `AUTH_TOKEN` / `FROM_NUMBER` are all set; otherwise logs the code. Live + conditional, not commented out. |
| `.env.example` secret | ⚠️ | `.env.example` · `configuration/JwtSecretGuard.java` | Ships a placeholder `JWT_SECRET=replace-with-random-base64-string-at-least-32-chars`. `JwtSecretGuard` fails fast under the **prod** profile if the secret is the placeholder or too short, so the placeholder is safe-by-default but must be replaced before any non-local run. A committed `.env` also exists. |
| Redundant JWT library | ⚠️ | `pom.xml` | `jjwt-api`/`jjwt-impl`/`jjwt-jackson` declared alongside the actually-used `com.auth0:java-jwt`. Removable with no code change. |
| Profile-image storage | ⚠️ | `IMAGE_STORAGE_PATH` (env) | Now env-configurable (Docker maps it to a named volume), but still **local filesystem**, not object storage. |
| General/distributed rate limiting | ❌ | — | No 429 + `Retry-After` limiter (per-account brute-force lockout exists; see §3). |
| Prod-profile boot never exercised | ⚠️ | `rollout-plan.md` | A real prod-profile boot with `ddl-auto=validate` against a `schema.sql`-only MySQL has never been run; `JpaSchemaSyncTest` is the offline stand-in. |

> **Security standing rule (unchanged).** Error messages must never reveal whether an email/account exists (user-enumeration safety): unknown-email, wrong-password, and rate-limited all return the same generic message. This is enforced in `UserController.authenticate()` and regression-guarded by `UserControllerLoginEnumerationTest`.
> **Reserved, not dead.** `VERIFY_EMAIL_HOST` is intentionally unused today (verification links are built from `UI_APP_URL`) and is reserved for future use — do not flag it as removable.

---

## 5. Upcoming deliverables

| Deliverable | Status | Artifact(s) |
|---|---|---|
| **SRS (assignment 5)** | In progress — **next due** | `documentation/06-26-26SRSv1.0.md` (TesseraApp, rev 1.0, June 23 2026, Oliver Jr. / Lester, CPSC-69100-007), staged for `.docx` regeneration (`documentation/06-26-26SRSv1.0.docx`, `documentation/software_requirements_specification (1).docx`). |

**Caution — SRS rev 1.0 understates the codebase.** It marks federation (§4.3, EIR-SW-1, DB-6), authenticator TOTP (FR-MFA-4), refresh-token rotation + reuse detection and session/device management (FR-JWT-4/5; dropped §4.11 and DB-15..18), the organization entities (DB-4/DB-5), and rate limiting (FR-TPF-3) as "(planned)" — although all but general rate limiting are **implemented** (see §3) and were documented as built in root SRS rev 0.3.

**Before submitting the SRS:** reconcile rev 1.0's "(planned)" flags against the actual build using **root SRS rev 0.3's Appendix C as the source of truth** ([`../software_requirements_specification.md`](../software_requirements_specification.md)), and carry forward the corrected leftovers from §2/§4 — SMS graceful-degradation wording, API-base-now-env-driven, the 14-test suite, and the `jjwt` removal.

| SRS rev 1.0 flag to reconcile | Correct status | Action |
|---|---|---|
| §4.3 / EIR-SW-1 / DB-6 federation "(planned)" | ✅ implemented | Mark implemented; restore DB-6. |
| FR-MFA-4 TOTP "(planned)"; §4.5 SMS-only | ✅ implemented | Promote FR-MFA-4; restore DB-15..18. |
| FR-JWT-4/5 (no rotation/reuse); §4.11 + DB-18 dropped | ✅ implemented | Restore rotation/reuse + Session & Device Mgmt + DB-18. |
| DB-4 / DB-5 org entities "(planned)" | ✅ implemented | Un-flag to match FR-ORG "shall" + schema. |
| FR-TPF-3 rate limiting "(planned)" | ✅ lockout / ❌ distributed | Credit per-account lockout; scope FR-TPF-3 to distributed limiter. |

---

## 6. Post-assignment-5 backlog

Prioritized, all verified against current source. P1 = do first.

| Pri | Item | Why | Evidence |
|---|---|---|---|
| **P1** | Add security-critical-path + frontend tests | The largest real gap; the 14 existing tests skip rotation/reuse, TOTP challenge binding, org scope, and all frontend specs. | §4; `week-5-plan.md` P1, `rollout-plan.md` §C |
| **P1** | Reconcile SRS rev 1.0 "(planned)" flags before submission | The next deliverable currently understates the build. | §5 |
| **P2** | Use the Maven wrapper in `start.sh` | `start.sh:153` invokes bare `mvn spring-boot:run`; the repo ships `mvnw`/`mvnw.cmd` + `.mvn/wrapper/`, so the wrapper pins a reproducible Maven version. Switch to `./mvnw spring-boot:run`. | `start.sh:153`; `mvnw`, `mvnw.cmd` present |
| **P2** | Expose MySQL `3306` in `docker-compose.yml` | The `mysql` service publishes **no** host port, so a SQL client (or the app run natively against the container) can't reach it. The `app` service talks to it over the compose network only. Add `ports: ["3306:3306"]` (or a guarded host port) for local DB access. | `docker-compose.yml` `mysql` service (lines 2–14, no `ports:`) |
| **P2** | Close the documentation drift this pass began | The deliverables/SRS corrections in §2/§5 (brand, feature flags, SMS wording, API-base, test count, `jjwt`) need to land in the actual documents, not just this status doc. | §2, §5 |
| **P3** | Drop the redundant `jjwt` artifacts from `pom.xml` | Shrinks dependency/CVE surface; only `java-jwt` is used. | §4; `pom.xml` |
| **P3** | Exercise a real prod-profile boot (`ddl-auto=validate`, `schema.sql`-only MySQL) | Never run end-to-end; `JpaSchemaSyncTest` is only an offline stand-in. | §4; `rollout-plan.md` |
| **P3** | Migrate profile-image storage off the local filesystem | Currently env-configurable local path; object storage would survive multi-instance/ephemeral hosts. | §4; `IMAGE_STORAGE_PATH` |

---

## 7. Change-record provenance & doc consolidation

There are **two** branch-level change records, and they overlap:

| Doc | Scope | Authority |
|---|---|---|
| [`../branch-changelog.md`](../branch-changelog.md) | Every addition since the branch diverged from `master` at `617ae18` (209 files, +17,865 / −1,362 through `0a2f3ea`), reconstructed from the diff because commit messages are terse. | **Authoritative.** It states this explicitly ("this file … is the authoritative record of what landed"). |
| [`../BRANCH_COMPARISON.md`](../BRANCH_COMPARISON.md) | `master` vs `MastersProjectSRSImpl` (generated 2026-06-28; 20 commits, 176 files, +17,961 / −3,715) — schema changes, new services/tables, run-this-branch notes. | Overlapping secondary view. |

**Flag for consolidation.** The two cover much the same ground (new tables, new services, the rebrand, prod hardening) with slightly different counts because they measure different spans (`branch-changelog.md` stops at `0a2f3ea` and excludes the later doc-only cleanup; `BRANCH_COMPARISON.md` measures the full branch-vs-`master` delta as of 2026-06-28). Recommendation: keep **`branch-changelog.md` as the single authoritative narrative**, and fold `BRANCH_COMPARISON.md`'s genuinely distinct content (the master-vs-branch table and the "to run this branch you must initialize the schema" callout) into it as a section, then retire the standalone comparison file — so there is one place to look and no diverging file/insertion counts.

> **History.** `plan.md` (§3) still references Flyway `V1`–`V6` and "near-zero tests" — both superseded. Flyway was removed on purpose; the schema is now the idempotent `src/main/resources/schema.sql`, and 14 tests exist. `plan.md`'s debt list is explicitly overridden by `week-5-plan.md` §0. Treat `plan.md` as historical roadmap, not current truth.
