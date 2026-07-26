# TesseraApp — Roadmap & Backlog

**Version:** 1.0
**Last Updated:** 2026-07-24
**Status:** Living — the single source of truth for anything planned, deferred, or TODO.

## How to use this file

This is **the** place for future work. It supersedes and replaces the old scattered planning
files — `plan.md`, `phase2-proposals.md`, `rollout-plan.md`, and `assignments/week-5-plan.md`
(consolidated here and deleted 2026-07-24). When you add a TODO in code, either fix it or add a
one-line entry here and reference it; don't let planning re-scatter.

**Companion references (kept, not planning scratch):**
- [`documentation/project-status-and-roadmap.md`](documentation/project-status-and-roadmap.md) — detailed *built-vs-documented* reconciliation with `file:line` evidence.
- [`branch-changelog.md`](branch-changelog.md) — authoritative record of what landed on the branch.
- [`software_requirements_specification.md`](software_requirements_specification.md) — the SRS.

**Status legend:** ⬜ not started · 🔄 in progress · ✅ done.

---

## 1. Active queue (do in order)

The agreed near-term sequence (2026-07-24):

1. 🔄 **Lock in this session's fixes with tests**
   - ✅ Regression test: a **failing audit write must not break login** (`NewUserEventListenerTest`, 2 tests green — reproduces the exact `Unknown column 'detail'` incident and asserts it's swallowed).
   - ✅ **Analytics authz** boundary covered (`AnalyticsControllerSecurityTest`, 3 tests).
   - ✅ **Frontend test harness proven + first specs** (2026-07-25) — the blocker was overstated: `angular.json` already pointed `test` at `@angular/build:unit-test` with `vitest`+`jsdom` installed and `tsconfig.spec.json` typed for `vitest/globals`; it had simply never been *run*. `npm test` (`ng test --no-watch`) now green: **2 spec files / 15 tests**. The builder auto-generates the TestBed bootstrap (`init-testbed.js`) — no `test.ts`/`setupFiles` needed. App is **zoneless**, so specs must call `fixture.detectChanges()` after every dispatched event.
     - ✅ Smoke test for the **command palette** (`command-palette.component.spec.ts`, 9 tests) — hotkey gating for unauthenticated users, base vs. admin command sets, query filtering + empty state, Enter-runs-highlighted, arrow wrap-around, Escape/second-hotkey close. Driven via real `document` keyboard events and rendered DOM (every component member is `protected`).
     - ✅ **`adminGuard` spec** (`admin.guard.spec.ts`, 6 tests) — logged-out → `/login` with no misleading toast, staff-grade → allow, under-privileged → capability-named warning + bounce home, `deniedAction` fallback, and a non-enumeration assertion on the message text.
2. ✅ **FR-TPF-1 — login anomaly detection + step-up re-verification** (2026-07-25)
   - **Detection reuses the audit log as the baseline** — no new table. `LoginRiskQuery` reads `userevents` for the account's own successful sign-ins (`LOGIN_ATTEMPT_SUCCESS` / `FEDERATED_LOGIN` / `RECOVERY_CODE_USED`; `LOGIN_ATTEMPT` is excluded because it fires *before* the outcome, so failures would seed the baseline and let an attacker teach the system their own device). `GROUP BY device, ip_address` + `MAX(created_at)` means the limit bounds **distinct fingerprints**, not rows — a daily user can't age their own baseline out.
   - **Two signals, scoped to one account:** `NEW_DEVICE` (unseen `OS - Browser - Device` string) and `NEW_NETWORK` (IP outside every known network, compared at **prefix** granularity — /24 for IPv4, first 4 hextets for IPv6 — because exact-IP matching flags a user's own sofa on every DHCP renewal). No cross-account correlation (would make one user's verdict a function of another's behaviour — an enumeration channel) and no geo-IP (external dependency + licence for marginal gain).
   - **Escalation** (`UserController#login`): TOTP and SMS accounts are challenged as before, with the verdict only recorded; the branch FR-TPF-1 *adds* is `EMAIL_CODE` — a single-factor account with a flagged sign-in gets a one-time code emailed and **no tokens** until it's entered. Reuses the SMS flow's `twofactorverifications` row and the existing `GET /user/verify/code/{email}/{code}`, so the single-outstanding-code and expiry rules are inherited, not duplicated.
   - **Nothing leaks to the client.** The response carries `stepUp: true` (a rendering hint — the SPA must show the code panel for a user whose `using2FA` is false) but never the reason; that goes only to the account owner's inbox and the `SUSPICIOUS_LOGIN` audit row's `detail` column.
   - **Fail-open at three layers**, because this runs *after* the password was accepted: repo swallows read errors → empty history → "no baseline" → not risky; `recordSuspiciousLogin` swallows audit + mail failures. Degraded mode is "login proceeds without the check" (logged WARN), never "logins break".
   - **Config:** `app.security.anomaly.enabled` (`ANOMALY_DETECTION_ENABLED`, default true) and `.history-limit` (`ANOMALY_HISTORY_LIMIT`, default 50). Disable when demoing from a fresh network where every sign-in legitimately looks new.
   - ✅ **12 tests** (`LoginRiskServiceImplTest`) covering both failure directions — false positives (first-ever sign-in, same-/24 different host, `Unknown IP` sentinel) matter as much as true ones, since over-flagging trains users to click through prompts. Backend total now **14 suites / 50 tests**.
   - **DB step:** the new event type needs the `events` CHECK rebuilt + its lookup row inserted (schema.sql §events). ✅ **applied to local `db2`** 2026-07-25 (now 16 event types, `SUSPICIOUS_LOGIN` = id 16). ⚠️ **still pending on Aiven `db3`** — and `start.sh` currently ships `DB=aiven`, so the *running* app hits `db3`. Until `schema.sql` is executed there, the audit write fails (swallowed — login and step-up still work) and no `SUSPICIOUS_LOGIN` rows accumulate, which FR-TPF-2's dashboard will need. Folds into the existing "Aiven schema drift" item in §5.
3. ⬜ **Federation link/unlink** — explicit "Connect / Disconnect provider" in the Security Center, with a guard against removing the **last** sign-in method (else lockout). Uses the existing `oauthproviderlinks` table; handles the different-email case that same-email auto-linking misses. See [§2 note].
4. ✅ **Frontend resilience + log cleanup** (2026-07-24)
   - ✅ Login page **retries `GET /oauth2/providers`** (`retry({count:5, delay:2000})`) so federated buttons self-heal on any boot order / backend restart.
   - ✅ `[ROLE-CASING]` happy-path logs dropped to `debug` (`RoleRepoImpl`); failure paths stay WARN/ERROR.
   - ✅ Yauaa banner spam killed two ways: `RequestUtils` now builds **one** shared `UserAgentAnalyzer` (was per-request — also a perf win), and `nl.basjes.parse.useragent` clamped to `WARN` in `application.yml`.

Then → **§2 features** ("if we have time").

---

## 2. Near-term features ("if we have time")

| Feature | Notes | Touches |
|---|---|---|
| ⬜ **Edit invoices** *(new)* | Currently invoices are create-only. Add edit + the draft-invoice support the model TODO calls for (`Invoice.java:86` — flip `nullable=false → true` for draft fields). | `InvoiceController`, `InvoiceService`, `Invoice` entity, `invoices` feature |
| ⬜ **Create / manage services** *(new)* | The services catalog is browse-only today; add admin create/edit/deactivate of catalog services. | new `ServicesController` CRUD, `ServicesRepo`, `services-catalog` + an admin services form |
| ⬜ **Link invoice ↔ customer endpoint** | `CustomerController.java:186` TODO: `PUT /invoice/{invoiceId}/addtocustomer/{customerId}`. | `CustomerController`, `InvoiceService` |
| ⬜ **Invoice total aggregation query** | `InvoiceRepo.java:15` TODO(human): a `@Query` returning the sum of all invoice `totalAmount`. Billing derives this client-side today; a server aggregate is cleaner. | `InvoiceRepo` |
| ⬜ **List sorting / filtering / infinite scroll** | `customer.service.ts:57` TODO — customers (and invoices) currently load unpaged/unsorted. | `customer.service.ts`, list components |
| 🔄 **"Contact your admin to do X" — permission-denied UX** *(new 2026-07-24)* | **Route level ✅ (2026-07-24):** `adminGuard` now reads a per-route `data.deniedAction` string and raises a specific, non-enumerating toast via `NotificationsService` — e.g. "You don't have permission to **manage users** — contact your administrator." — before bouncing home (was a silent redirect). Actions wired: `manage users` (`/users`,`/users/:id`), `manage roles and permissions` (`/roles`), `view billing` (`/billing`), `view analytics` (`/analytics`); guard falls back to "access this area" if a route omits it. **Still open:** (a) *API level* — surface the backend 403 `CustomAccessDeniedHandler` reason per-endpoint (partly covered: `UserService.handleError` already forwards `error.error.reason`); (b) optional dedicated `/forbidden` view for bookmarked deep links; ~~(c) a guard spec~~ ✅ **done 2026-07-25** (`admin.guard.spec.ts`, 6 tests). | `adminGuard` ✅, `app.routes.ts` ✅, a forbidden view/toast, the 403 handler's response body, feature route guards |
| ⬜ **Capability-level RBAC gating (hide/disable controls, not just routes)** *(new 2026-07-25)* | Follow-on to the "Contact your admin" work. Today `adminGuard` gates whole *routes*, but inside a permitted page a read-only user still sees action controls (e.g. **Save**, edit fields, delete buttons) that only 403 on submit. Hide or disable those controls based on the user's authorities so the denial is felt *before* the click, not after. Design: a small structural directive or signal helper (e.g. `*hasAuthority="'UPDATE:CUSTOMER'"` / `[disabled]="!can('UPDATE:CUSTOMER')"`) backed by the existing `UserService.hasAnyAuthority`; keep it a **usability aid only** — the backend stays the boundary (NFR-SEC-4), and non-enumerating. Candidate screens: customer-details Save, invoice actions, user-details role edits, settings. | new `HasAuthorityDirective` (or `can()` helper), `UserService`, feature templates (customer-details, new/edit forms, user-details) |
| ⬜ **Internationalization (i18n) — multiple languages** *(new 2026-07-24)* | Runtime language switching (user picks language; no rebuild). Recommended: **Transloco** (modern, maintained, signal-friendly) over compile-time `@angular/localize` — better fit for an authed SPA with a live switcher. Scope: install Transloco, extract UI strings to `en.json` + ≥1 more locale (e.g. `es`, `fr`), add a navbar language selector persisting choice (like `ThemeService`), translate toasts/validation, handle date/number/currency via Angular locale data. Backend-emitted messages (errors, emails) are a **stretch** (Spring `MessageSource` + `Accept-Language`). | `app.config` (Transloco provider), `assets/i18n/*.json`, navbar selector, a `LanguageService`, every template's static strings |
| ⬜ **Batch upload (P2-2)** | CSV/Excel import for customers & invoices with per-row validation and a partial-success report (`{ imported, failed:[{row,reason}] }`); per-row/chunk commits; dedupe key; async job for large files. Gate behind `UPDATE:CUSTOMER`. Deps: Apache Commons CSV (+ POI only if `.xlsx`). Open Qs: CSV-only vs Excel, sync row cap, dedupe policy. | new `ImportController` + `ImportService`, optional `import_jobs` table |

---

## 3. Phase 2 proposals (larger — design-sketched, deferred)

Preserved from the retired `phase2-proposals.md`. Suggested order **P2-1 → P2-2 → P2-3**.

### P2-1 — User Type Classification ⬜
Classify each account so admins see identity origin. Enum `INTERNAL` / `EXTERNAL` / `FEDERATED` /
`AZURE_B2B`. **Key requirement:** "internal" must be reconfigurable (email-domain allowlist), not
baked in. **Recommended:** derive `INTERNAL/EXTERNAL` on read from an env-driven allowlist
(`app.internal-domains` / `INTERNAL_DOMAINS`); store an immutable `origin` fact for
`FEDERATED/AZURE_B2B`. Touches: new `UserType.java`, `User`/`UserDTO` `origin` + derived `userType`,
`OAuth2LoginSuccessHandler`/`FederatedIdentityService` (stamp origin; detect Azure B2B via `tid`/`idp`
claims), admin dashboard badge, idempotent guarded `users.origin` ALTER (same pattern as
`userevents.detail`). Open Qs: reliable B2B detection; does `INTERNAL` grant implicit authority; filter
directory by type (would argue for a stored column). Effort: Small–Medium, low risk.

### P2-3 — Machine-to-Machine Admin API Access ⬜
Let non-interactive callers (scripts, CI/CD, services) authenticate as a **service principal** without
a browser login. **Option A — API keys:** an `X-API-Key` filter ahead of `CustomAuthFilter` that looks
up a **hashed** key and installs an `Authentication` carrying the key's authorities — reusing the exact
authority-string model, so every `hasAnyAuthority`/`@PreAuthorize` rule applies unchanged. **Option B —
OAuth2 client-credentials:** a `POST /oauth/token` (grant_type=client_credentials) minting short-lived
tokens. Both converge: a request arrives already carrying authorities; the RBAC core doesn't change,
only a new *authentication* front door. Touches: `filter/ApiKeyAuthFilter` (or token endpoint),
`service_accounts` + `api_keys` (hashed) tables, `SecurityConfig` wiring (keep PUBLIC_URLS ↔
PUBLIC_ROUTES lockstep), audit event types, CI/CD secret wiring. Effort: Large, higher risk — do last
with dedicated review. Benefits from the mature audit + rate limiting already present.

---

## 4. Code TODO audit (2026-07-24)

Full sweep of `TODO`/`FIXME`/stub comments. **DONE** rows are stale and should have their comments
removed as each file is next touched; **OPEN** rows are tracked above/below.

### Backend
| Location | Intent | Status |
|---|---|---|
| `authentication.guard.ts:5` (FE) | "add guards for admin roles" | ✅ **DONE** — `adminGuard` exists & is applied (`/users`,`/roles`,`/billing`,`/analytics`). Comment removed 2026-07-24. |
| `AdminUserController.java:272` / `UserController.java:525` | admin edit-another-user | ✅ **DONE** — `PATCH /admin/user/{id}/update` shipped. Trim the historical "TODO(admin-update)/DONE" notes. |
| `HandleException.java:31` | Stop exposing `.reason`/`.message` (PII) under prod | ⬜ **OPEN** — security hardening; scrub client-facing error bodies in prod. |
| `AngularSpringBootFullStackApplication.java:73` | Real prod domain once DNS configured | ⬜ **OPEN** — deployment config. |
| `CustomerController.java:186` | `PUT /invoice/{id}/addtocustomer/{id}` | ⬜ **OPEN** — see §2. |
| `Invoice.java:86` | `nullable=true` for draft invoices | ⬜ **OPEN** — ties to Edit invoices, §2. |
| `InvoiceRepo.java:15` | sum-of-`totalAmount` `@Query` | ⬜ **OPEN** — see §2. |
| `UserQuery.java:36` + `UserRepoImpl.java:183` | rename `url` col → `verification_key` | ⬜ **OPEN** — cosmetic schema refactor (idempotent guarded rename). |
| `UserController.java:59` | `TODO(refactor-user-fetch)` standardize authed-user fetch | ⬜ **OPEN** — refactor. |
| `UserRepoImpl.java:63` | `TODO(refactor-architecture)` SRP violation | ⬜ **OPEN** — refactor. |
| `RoleRepoImpl.java:25` | `TODO(org-roles)` org-scoped role system | ⬜ **OPEN** — FR-ORG scopes *admin*, not per-org role definitions. |
| `RoleRepoImpl` create/update/delete/getById (`Not yet implemented; null/no-op`) | Role CRUD | ⬜ **OPEN** — roles are seed-only today; add CRUD if role management is wanted. |
| `NotificationServiceImpl.java:54` | enable SMS (Twilio) when ready | ⬜ **OPEN/known stub** — sends only when Twilio creds set; documented posture. |

### Frontend
| Location | Intent | Status |
|---|---|---|
| `cache.interceptor.ts:35` + `http-cache.service.ts:25` | move caching to backend (Cache-Control/ETag/Redis) | ⬜ **OPEN** — refactor. |
| `stats.component.ts:13` + `customer.service.ts:39` | `StatsComponent` self-fetch | ⬜ **OPEN** — refactor. |
| `navbar.component.ts:15` | decouple user data from `/customer/list` response | ⬜ **OPEN** — refactor (fetch `/user/profile` independently). |
| `home.component.html:1` | loading spinner for `DataState.LOADING` | ⬜ **OPEN** — UX (skeletons exist elsewhere; this template still renders nothing while loading). |
| `new-customer.component.ts:25/76/83` | lighter user-only prefill endpoint | ⬜ **OPEN** — refactor (stop fetching all customers to prefill). |
| `profile.component.ts:17` | reactive forms for profile | ⬜ **OPEN** — refactor. |
| `verify.component.ts:67` | robust route detection vs `window.location.href` | ⬜ **OPEN** — refactor. |
| `customer.service.ts:57` | sorting/filtering/infinite scroll | ⬜ **OPEN** — see §2. |

---

## 5. Engineering debt & hardening

- ⬜ **Security-critical-path tests** (the biggest real gap): refresh **rotation + reuse-detection** (`SessionService` — the zero-trust payoff), **TOTP challenge binding**, **org-scoped access** (200 in-org / 403 out-of-org with no info leak). *Frontend specs are no longer zero* (harness proven 2026-07-25, 15 tests) — next frontend candidates: `authenticationGuard`, `tokenInterceptor` (header attachment + refresh-on-401), `UserService.hasAnyAuthority` token-decoding edge cases.
- ⬜ **Prod-profile error hygiene** — `HandleException.java:31`: stop returning `.reason`/`.message` to clients in prod.
- 🔄 **Aiven schema drift** — root cause of the 2026-07 intermittent 500s on `/user/profile` + `/user/events`: Aiven `db3` lagged local `db2` (missing `userevents.detail` on an *unswallowed read path*, plus a stale `events` CHECK rejecting new event types with MySQL 3819). **FIX READY (2026-07-25):** `schema.sql` is now portable + drift-proof — make `db3` the active schema in Workbench and execute it. Closes once applied.
- ⬜ **`ng lint` is red (13 errors, pre-existing)** *(found 2026-07-25)* — none from the new specs; all in already-committed code. 10 are **accessibility**: `label-has-associated-control` (`new-customer` ×6, `new-invoice` ×1) and `click-events-have-key-events` / `interactive-supports-focus` (`invoice-detail` ×2, `command-palette` ×3 — the backdrop and result rows are click-only). 1 is trivial (`user.service.ts:130` inferrable type, `--fix`-able). Worth fixing: the a11y rules are flagging real keyboard-navigation gaps, and a permanently-red lint means CI can never gate on it.
- ⬜ **Drop redundant JWT lib** — `pom.xml` ships `jjwt-*` alongside the actually-used `com.auth0:java-jwt`; remove `jjwt` (no code change).
- ⬜ **Exercise a real prod boot** with `ddl-auto=validate` against a `schema.sql`-only MySQL (only offline `JpaSchemaSyncTest` has run).
- ⬜ **`start.sh` → use the Maven wrapper** (`./mvnw spring-boot:run` instead of bare `mvn`) for a pinned Maven.
- ⬜ **Refactors** (from §4): `UserRepoImpl` SRP split, standardize authed-user fetch, `url→verification_key` rename, reactive profile forms, stats self-fetch, navbar user-data decouple, caching-to-backend.

---

## 6. Recently completed (don't re-plan)

- ✅ **Frontend test harness + first 15 specs** (2026-07-25) — `npm test` runs Vitest/jsdom through `@angular/build:unit-test`; `command-palette.component.spec.ts` (9) and `admin.guard.spec.ts` (6) all green. Unblocks every future Angular spec; see §1.1 for the harness gotchas (zoneless `detectChanges`, auto-generated TestBed init). **Whole-project test count is now 13 backend suites / 38 tests + 2 frontend suites / 15 tests = 53**, both verified green on 2026-07-25.
- ✅ **Schema portability + CHECK drift-proofing** (2026-07-25) — `schema.sql` no longer hardcodes `USE db2`; it targets the active connection DB, so one file initialises local `db2` *or* Aiven `db3`. Idempotent rebuilds of the `events.type` and `organizations.status` CHECK constraints fix the stale-constraint failure (MySQL 3819) that blocked applying it to a pre-existing Aiven `db3`.
- ✅ **Yauaa analyzer thread-safety** (2026-07-25) — `RequestUtils.getDevice` now serialises access to the shared `UserAgentAnalyzer` (its cache/matcher state isn't concurrent-safe), preventing intermittent throws on concurrent login/refresh (`SessionServiceImpl`, an unswallowed path).
- ✅ **Permission-denied UX (route level) + history archive** (2026-07-24/25) — `adminGuard` raises a specific, non-enumerating toast via `data.deniedAction`; new [`documentation/history/PROJECT-HISTORY.md`](documentation/history/PROJECT-HISTORY.md) retrospective consolidates the retired planning/one-off docs.
- ✅ **M0–M7 UI/CIAM roadmap** — design tokens, auth screens, security/activity dashboard, roles×permissions matrix, TOTP MFA, sessions/devices + rotation & reuse-detection, **route transitions + ⌘/Ctrl+K command palette** (M7, 2026-07-24).
- ✅ **Analytics/Billing authz** — `AnalyticsController` at `/admin/analytics/**` (server-enforced `UPDATE:USER`/`UPDATE:ROLE`); closes the old "admin-only is frontend-only" gap. `AnalyticsControllerSecurityTest` green.
- ✅ **Non-fatal audit** — `NewUserEventListener` swallows+logs audit-write failures (a bad `userevents` insert can no longer 500 a login).
- ✅ **JWT secret-leak removed** — `TokenProvider` no longer prints the signing key to logs.
- ✅ **Startup UX** — `start.sh` holds the browser open until the backend's `/oauth2/providers` responds; removed the app-wide grid background.
- ✅ **Security hardening** — `RateLimitFilter` (Bucket4j, 429 + `Retry-After`), CSP/Referrer-Policy/Permissions-Policy headers, per-account brute-force lockout, persistent lock + admin unlock.
- ✅ **S3 image storage** abstraction (`ImageStorageService` + local/S3 impls) — profile images off the local FS when configured.
- ✅ **Admin user management + org scoping**, federated OAuth2/OIDC (Google/GitHub/Microsoft, incl. account-linking), multi-env config + CI/CD (QA/stage, GitHub Actions, ECR/ECS, Aiven).

---

## 7. Doc-drift corrections (verified built, keep docs honest)

The retired planning files and the in-progress **SRS rev 1.0** understated the build. Verified **implemented**: federated login, authenticator TOTP, refresh rotation + reuse detection, session/device management, organization scoping, environment-driven API base, and (new) general rate limiting. When reconciling the SRS, use `documentation/project-status-and-roadmap.md` §2/§5 and root SRS rev 0.3 Appendix C as the source of truth. Genuinely still open: the §5 debt list above (esp. security-path + frontend tests).
