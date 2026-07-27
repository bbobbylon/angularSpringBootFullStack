# TesseraApp — Roadmap & Backlog

**Version:** 1.2
**Last Updated:** 2026-07-26 — **116 backend / 35 frontend tests**, `ng lint` clean, `npm audit` free of high/critical. CI now gates on lint + audit + tests.
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
   - **DB step:** ✅ **DONE on both databases** (2026-07-25) — `events` CHECK rebuilt + `SUSPICIOUS_LOGIN` lookup row inserted on local `db2` *and* Aiven `db3` (16 event types each, id 16). `start.sh` ships `DB=aiven`, so `db3` is the one the running app actually uses.
3. ✅ **FR-ORG-2 — org-scoped analytics + FR-TPF-1 IP-spoofing fix** (2026-07-25)
   - **The gap:** `/admin/analytics/**` had a genuine server-side authority check, but authority answers *whether* you may open the dashboards, not *whose* numbers they contain. Every `ROLE_ORGANIZATION_ADMIN` saw system-wide customer counts and billed revenue — including organizations they have no relationship with.
   - **The blocker, and why it needed a schema change:** organizations scoped *users* only (`userorganizations`); `Customer` had no owner at all, so there was no tenant dimension to filter on. Added `Customer.organization_id` (plain `Long`, not a `@ManyToOne` — `organizations` is JDBC-owned while `Customer` is JPA-owned, and mapping across that line would put Hibernate's `ddl-auto: validate` in charge of a table it doesn't manage).
   - **Scoping is pushed into the SQL**, not applied to results: an aggregate has discarded its attribution by the time it's a number, and post-filtering a page corrupts `totalElements` and returns short pages. Invoices inherit tenancy from the customer they bill (`invoice.customer.organizationId`) so ownership lives in exactly one column.
   - **Empty scope means *nothing*, not *everything*.** An org admin with no active memberships gets zeros and an empty page — collapsing that into "unscoped" would hand the global view to the least-established account. The service fails closed on an empty scope; the controller short-circuits before reaching it.
   - **New customers are stamped from the JWT principal**, never from the request body — a client-supplied `organizationId` would let anyone file rows into another tenant's dashboards.
   - ✅ **8 tests** (`AnalyticsControllerOrgScopeTest`) — assertions come in pairs (scoped method called **and** unscoped method never called), because calling the unscoped variant *is* the bug.
   - ✅ **DB applied to both** `db2` and Aiven `db3`: column added (idempotent guard), 104 existing customers backfilled to org 1 (Tessera — the only org with members).
   - **Also landed: `X-Forwarded-For` is no longer trusted blindly.** It was returned verbatim, so callers chose their own recorded address — which defeated *two* live controls: `RateLimitFilter` (forge rotating IPs → unlimited request budget) and FR-TPF-1's `NEW_NETWORK` signal (forge a familiar network → no step-up). Now gated on `app.security.trusted-proxy-count` (`TRUSTED_PROXY_COUNT`, default **0** = ignore the header entirely); behind N proxies the real client is at `length - N`, since each trusted hop appends exactly one entry. ✅ 10 tests, forgery cases included. **Set this to the real hop count before any load-balanced deploy** — see `documentation/cicd-setup.md` §6.
4. ⬜ **Federation link/unlink** — explicit "Connect / Disconnect provider" in the Security Center, with a guard against removing the **last** sign-in method (else lockout). Uses the existing `oauthproviderlinks` table; handles the different-email case that same-email auto-linking misses. See [§2 note].
5. ✅ **Frontend resilience + log cleanup** (2026-07-24)
   - ✅ Login page **retries `GET /oauth2/providers`** (`retry({count:5, delay:2000})`) so federated buttons self-heal on any boot order / backend restart.
   - ✅ `[ROLE-CASING]` happy-path logs dropped to `debug` (`RoleRepoImpl`); failure paths stay WARN/ERROR.
   - ✅ Yauaa banner spam killed two ways: `RequestUtils` now builds **one** shared `UserAgentAnalyzer` (was per-request — also a perf win), and `nl.basjes.parse.useragent` clamped to `WARN` in `application.yml`.

Then → **§2 features** ("if we have time").

---

## 2. Near-term features ("if we have time")

| Feature | Notes | Touches |
|---|---|---|
| ✅ **Edit invoices** *(done 2026-07-26)* | `PATCH /customer/invoice/update/{id}` + an edit panel on the invoice detail page (rendered **outside** `#invoice` so it never reaches the exported PDF). The service copies only editable fields onto the managed entity — saving the request body wholesale would null every field the partial client payload omits. Invoice number and owning customer are deliberately not editable. `Invoice.customer` is now `nullable = true` with a guarded `MODIFY COLUMN` in `schema.sql`, closing the `Invoice.java:86` draft-invoice TODO. **`InvoiceInterface` was missing `amount` entirely** — added. | `CustomerController`, `CustomerService(+Impl)`, `Invoice`, `schema.sql`, `invoice-detail`, `customer.service.ts`, `invoice.interface.ts` |
| ✅ **Create / manage services** *(done 2026-07-26)* | New `ServicesCatalogController` at `/admin/services/**` (list / get / create / update / retire) — under the existing `/admin/**` matcher, so **zero SecurityConfig change**. `Services.active` added (`Boolean`, not primitive: `@JsonInclude(NON_DEFAULT)` would silently drop a primitive `false`) with a guarded idempotent ALTER. Retire, never delete. New `/services/manage` admin screen; the public catalog now returns **active only** via `findByActiveTrue()`. | new `ServicesCatalogController`, `ServicesCatalogService(+Impl)`, `ServicesRepo`, `Services`, `schema.sql`, new `services-admin` feature, `services-catalog.service.ts` |
| ✅ **Link invoice ↔ customer endpoint** *(done 2026-07-26)* | `PUT /customer/invoice/{invoiceId}/addtocustomer/{customerId}` — the other half of `POST /invoice/create`. Keeps the JPA association and the denormalized `customerId` in step. Closes the `CustomerController.java:186` TODO. | `CustomerController`, `CustomerService(+Impl)` |
| ⬜ **Invoice total aggregation query** | `InvoiceRepo.java:15` TODO(human): a `@Query` returning the sum of all invoice `totalAmount`. Billing derives this client-side today; a server aggregate is cleaner. | `InvoiceRepo` |
| ⬜ **List sorting / filtering / infinite scroll** | `customer.service.ts:57` TODO — customers (and invoices) currently load unpaged/unsorted. | `customer.service.ts`, list components |
| 🔄 **"Contact your admin to do X" — permission-denied UX** *(new 2026-07-24)* | **Route level ✅ (2026-07-24):** `adminGuard` now reads a per-route `data.deniedAction` string and raises a specific, non-enumerating toast via `NotificationsService` — e.g. "You don't have permission to **manage users** — contact your administrator." — before bouncing home (was a silent redirect). Actions wired: `manage users` (`/users`,`/users/:id`), `manage roles and permissions` (`/roles`), `view billing` (`/billing`), `view analytics` (`/analytics`); guard falls back to "access this area" if a route omits it. **Still open:** (a) *API level* — surface the backend 403 `CustomAccessDeniedHandler` reason per-endpoint (partly covered: `UserService.handleError` already forwards `error.error.reason`); (b) optional dedicated `/forbidden` view for bookmarked deep links; ~~(c) a guard spec~~ ✅ **done 2026-07-25** (`admin.guard.spec.ts`, 6 tests). | `adminGuard` ✅, `app.routes.ts` ✅, a forbidden view/toast, the 403 handler's response body, feature route guards |
| ✅ **Capability-level RBAC gating (hide/disable controls, not just routes)** *(done 2026-07-26)* | Two directives in `directive/has-authority.directive.ts`: `*hasAuthority` (structural, supports `; else`) for controls whose absence is not confusing, and `[requiresAuthority]` (attribute) which disables + sets `aria-disabled` + an explanatory `title` + `.is-restricted` for controls that must stay visible. Plus `capabilityGuard` — a route-data-driven gate (`requiredAuthorities` + `deniedAction`) that **fails closed** on a missing declaration, used on `/customer/new` and `/invoice/new` (deliberately not `adminGuard`: `ROLE_MODERATOR` holds `UPDATE:CUSTOMER` without staff authority). **Found and fixed:** `customer-details.component.html` gated 10 bindings on `roleName === 'ROLE_USER'`, a string comparison that let `ROLE_GUEST` — which holds neither write authority — see a fully editable form that could only 403. Now authority-based. Navbar + command palette hide creation entries for read-only accounts. 16 new specs. | `directive/has-authority.directive.ts` ✅, `guard/capability.guard.ts` ✅, `customer-details`, `navbar`, `command-palette`, `user-details`, `styles.css` §12 |
| 🔄 **Internationalization (i18n) — multiple languages** *(foundation done 2026-07-26)* | **Transloco 8.4 installed and wired** (`provideTransloco` in `app.config.ts`, `TranslocoHttpLoader`, `fallbackLang: en` + `useFallbackTranslation` so an untranslated key renders English rather than a raw key, `reRenderOnLangChange` for the live switch). `LanguageService` mirrors `ThemeService` — signal, localStorage persistence, browser-language fallback on primary subtag (`es-MX` → `es`), and it sets `<html lang>` so screen readers pick the right voice. Navbar selector beside the theme toggle, each language labelled **in its own language** ("Español", never "Spanish" — a user stranded in a language they can't read must be able to find the exit). Dictionaries: `public/assets/i18n/{en,es}.json`. **Translated so far:** the whole navbar, and both route guards' permission-denied toasts (routes now carry `deniedActionKey`, falling back to the English `deniedAction` literal). **Still to translate:** every other feature template, form validation text, and the remaining toasts. Backend-emitted messages remain a stretch (Spring `MessageSource` + `Accept-Language`). | `app.config` ✅, `service/transloco-loader.ts` ✅, `service/language.service.ts` ✅, `public/assets/i18n/*.json` ✅, navbar ✅, guards ✅, remaining feature templates ⬜ |
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
| `HandleException.java:31` | Stop exposing `.reason`/`.message` (PII) under prod | ✅ **DONE** — `ErrorDetailScrubber` (`ResponseBodyAdvice`) blanks `devMessage` and genericises `reason` on 4xx/5xx when `app.error.expose-details=false` (pinned false in `application-prod.yml`). Applied at the serialization boundary rather than at ~10 call sites, so it can't lapse. |
| `AngularSpringBootFullStackApplication.java:73` | Real prod domain once DNS configured | ⬜ **OPEN** — deployment config. |
| `CustomerController.java:186` | `PUT /invoice/{id}/addtocustomer/{id}` | ✅ **DONE** 2026-07-26 — shipped as `PUT /customer/invoice/{invoiceId}/addtocustomer/{customerId}`. |
| `Invoice.java:86` | `nullable=true` for draft invoices | ✅ **DONE** 2026-07-26 — entity relaxed + guarded `MODIFY COLUMN` in `schema.sql`. **Apply `schema.sql` to `db2`/`db3`** before relying on draft creation; Hibernate `validate` does not check nullability, so a stale DB fails at insert, not at boot. |
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
- ✅ **Prod-profile error hygiene** — done via `ErrorDetailScrubber` (see §4).
- 🔄 **Aiven schema drift** — root cause of the 2026-07 intermittent 500s on `/user/profile` + `/user/events`: Aiven `db3` lagged local `db2` (missing `userevents.detail` on an *unswallowed read path*, plus a stale `events` CHECK rejecting new event types with MySQL 3819). **FIX READY (2026-07-25):** `schema.sql` is now portable + drift-proof — make `db3` the active schema in Workbench and execute it. Closes once applied.
- ⬜ **`ng lint` is red (13 errors, pre-existing)** *(found 2026-07-25)* — none from the new specs; all in already-committed code. 10 are **accessibility**: `label-has-associated-control` (`new-customer` ×6, `new-invoice` ×1) and `click-events-have-key-events` / `interactive-supports-focus` (`invoice-detail` ×2, `command-palette` ×3 — the backdrop and result rows are click-only). 1 is trivial (`user.service.ts:130` inferrable type, `--fix`-able). Worth fixing: the a11y rules are flagging real keyboard-navigation gaps, and a permanently-red lint means CI can never gate on it.
- ⬜ **Drop redundant JWT lib** — `pom.xml` ships `jjwt-*` alongside the actually-used `com.auth0:java-jwt`; remove `jjwt` (no code change).
- ⬜ **Exercise a real prod boot** with `ddl-auto=validate` against a `schema.sql`-only MySQL (only offline `JpaSchemaSyncTest` has run).
- ⬜ **`start.sh` → use the Maven wrapper** (`./mvnw spring-boot:run` instead of bare `mvn`) for a pinned Maven.
- ⬜ **Refactors** (from §4): `UserRepoImpl` SRP split, standardize authed-user fetch, `url→verification_key` rename, reactive profile forms, stats self-fetch, navbar user-data decouple, caching-to-backend.

---

## 6. Recently completed (don't re-plan)

- ✅ **Cleanup sweep** (2026-07-26) — stale TODOs cleared and one real leak closed.
  - **Client-side token leak removed.** 76 `console.log` calls shipped to production (Angular does not strip them). `login$` piped `tap(console.log)` over a response containing `access_token` and `refresh_token`, and `token.interceptor.ts` logged the raw JWT explicitly — so every sign-in wrote a usable bearer token into the browser console, visible in devtools, screen recordings, and to any extension. This is the client-side twin of the `TokenProvider` secret-leak already fixed server-side. All response logging disabled across the 16 affected files. Per request the lines were **kept, commented out**, so they remain available for local debugging: standalone statements as `// console.log(...)` in place, and the operator form inline as `.pipe(/* tap(console.log), */ catchError(...))`. The ones that print tokens or PII carry a `DEBUG ONLY — DO NOT SHIP ENABLED` marker, and `user.service.ts` carries a class-level warning naming the token-bearing calls. `security-dashboard.service.ts` is deliberately left with no such affordance at all — its payload is flagged sign-ins, IPs and locked accounts. `console.error` on genuine error paths kept.
  - **Latent type bug surfaced and fixed.** Removing `tap(console.log)` broke the build on `downloadCustomerReport$`/`downloadInvoiceReport$`: with `observe: 'events'`, `http.get<T>` already returns `Observable<HttpEvent<T>>`, so `get<HttpEvent<Blob>>` was doubly-wrapping and never matched the declared return type. `tap` had been loosening inference enough to hide it.
  - **Stale TODOs removed** — `HandleException` (done by `ErrorDetailScrubber`), `home.component.html` loading spinner (the LOADING branch exists), and a dangling `TODO(admin-update)` reference pointing at a comment that no longer exists.
  - **Unimplemented repo stubs now throw** instead of returning `null`. `RoleRepoImpl.create/get/update` have zero callers and exist only to satisfy the interface; returning null made them a latent NPE for whoever called them next, whereas `UnsupportedOperationException` fails at the call site and names the real path (matching what `UserRepoImpl` already did).
  - Dead `logCache()` debug method and its one call site deleted.

- ✅ **CI is now genuinely gating** (2026-07-26) — both blockers cleared, so the pipeline can fail for real reasons instead of being permanently red and ignored.
  - **`npm audit` clean of high/critical.** Was 15 high + 1 critical, so the frontend job failed before it reached the tests. `npm update` inside the declared semver ranges took Angular 21.2.11 → 21.2.18, vite → 7.3.6, undici → 7.28.0, tar → 7.5.22. One of those is a real fix for this app, not hygiene: **an Angular template/attribute namespace sanitization bypass leading to XSS** (patched in 21.2.17). 3 moderate advisories remain, which the gate deliberately allows.
  - **`ng lint` clean, and wired into CI** ahead of the tests. The 10 accessibility errors were real defects: 7 `<label>`s with no control association (clicking them focused nothing, screen readers could not pair them) — one of which pointed `for="address"` at an `id` that did not exist anywhere. The command palette's backdrop/rows had click handlers with no keyboard path; fixed by removing the panel's `stopPropagation` entirely in favour of a target check on the backdrop, which deleted an interactive element rather than suppressing a warning about it.
- ✅ **Security-critical-path tests** (2026-07-26) — the gap §5 called "the biggest real gap" is closed. **+15 tests.**
  - **Refresh rotation** (`SessionServiceImplTest`, 2→4): added the **happy path** (old row superseded, new row inserted, a *different* jti issued, family preserved) — without it the whole suite would still pass if `rotate()` were changed to refuse everything, and the sliding session would be silently dead. Plus `revoked` (user-initiated) treated as reuse, distinct from `superseded` (rotation).
  - **TOTP challenge binding** (`TotpServiceImplTest`, 2→5): the identity comes from the **challenge**, never the request; a wrong code refuses *without* burning the challenge (otherwise anyone could cancel someone else's in-flight sign-in by spraying wrong codes); recovery codes validate-and-consume in one atomic UPDATE.
  - **Org-scoped access** (new `AdminUserControllerOrgScopeTest`, 5): in-org read → 200, out-of-scope → 403 **on reads as well as writes** (the classic omission — guarding mutations while `GET /admin/user/{id}` hands the same data over), platform admins never scope-checked at all, and the 403 body proven non-enumerating (no email, role, "not found", or echoed id).
- ✅ **i18n reaches TypeScript** (2026-07-26) — 15 toast messages and all 17 command-palette labels/hints now resolve through `TranslocoService`, so a language switch covers notifications and ⌘K, not just templates. The palette spec's stub is fed the shipped English copy so it still asserts on real user-facing text.
- ✅ **Federation link/unlink** (2026-07-26) — closes the last ⬜ in §1. `GET /user/sessions/providers` + `DELETE /user/sessions/providers/{provider}`, plus a Connected Accounts panel in the Security Center. **The guard is the feature:** unlinking is refused when it would remove the account's last sign-in method (no password *and* no second provider), because a federated-only user who disconnects their only provider is locked out of their own account with no self-service way back. Linking reuses the ordinary OAuth2 login (the find-or-create convergence step) rather than adding a second code path. The delete is scoped by `user_id AND provider`, never by row id, so cross-account unlinking is unrepresentable rather than merely refused. **5 tests** covering both halves of the guard's truth table.
- ℹ️ **`jjwt` was already removed** — the §5 entry was stale; `pom.xml` carries a comment recording the removal and no source imports `io.jsonwebtoken`.

- ✅ **FR-TPF-2 — administrative security dashboard** (2026-07-26) — the review surface FR-TPF-1 was missing. A detection control whose output nobody can look at cannot be tuned, cannot be shown to work, and cannot report that one account has been flagged eleven times this week. New `/admin/security/overview` (org-scoped like the analytics, under the existing `/admin/**` matcher so **no new request matcher**) returns the whole screen in **one** response — six endpoints would be six different instants of the same database, with no way to tell which panel was stale. Panels: headline counters, anomalous sign-in log (reads FR-TPF-1's `detail` column, so a reader can tell an authenticator challenge from an emailed-code fallback), gap-filled login-outcome trend, restricted accounts, MFA adoption, live sessions. Decisions worth keeping: window is **clamped** 1–90 (`?days=100000` is a DoS needing no vulnerability); counters are **zero-filled** so "0 suspicious logins" is a statement rather than a missing tile; the trend is **gap-filled** because a quiet weekend that vanishes from the axis turns a Monday burst into a gentle slope; every repo read is **non-fatal** (one broken panel must not take five working ones down); empty org scope returns zeros *before any query runs*. SPA at `/security-overview`, and the response carries `scoped` so an org admin is never left reading their own slice as the whole platform. **11 tests.**
- ✅ **Security quick wins** (already in the working tree at the start of 2026-07-26) — server-side logout (`POST /user/sessions/logout`; signing out previously revoked nothing and left the refresh session live for its full five days), prod error hygiene (`ErrorDetailScrubber`, a `ResponseBodyAdvice` that blanks `devMessage`/genericises `reason` on 4xx/5xx when `app.error.expose-details=false` — applied at the serialization boundary so it cannot be forgotten by the eleventh handler someone adds), and `PasswordPolicy` unifying the rule across register / change / reset (the reset path previously enforced only `@NotEmpty`, so an eight-character password could be reset to `"1"`).
- ✅ **Permission-denied UX at the API level** (2026-07-26) — `CapabilityCatalog` maps a forbidden request's method + path to the capability name, so `CustomAccessDeniedHandler` returns "You don't have permission to *assign roles* — contact your administrator" instead of one identical sentence for every endpoint. Same wording as the SPA's guards, so a user stopped at both reads one message. Still non-enumerating: capability names only, never record existence — which matters because a 403 also covers out-of-scope resources. The handler writes straight to the output stream, so `ErrorDetailScrubber` does not strip it (correctly — this is deliberate user-facing text, not incidental exception detail). **17 tests.**

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
