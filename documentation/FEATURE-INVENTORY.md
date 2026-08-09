# Complete Feature & Technical Inventory

**Version:** 1.0
**Last Updated:** 2026-08-08
**Purpose:** An exhaustive, verifiable inventory of everything **actually built and working** in
TesseraApp — every library, every security control, every endpoint category, every convention.
Built specifically to be checked line-by-line against course deliverables (SRS, architecture doc,
implementation reports) to confirm nothing built was left undocumented.

**Scope boundary — read this first:**
- This document covers what **exists in the codebase today**, verified against the actual source,
  not what's planned. For planned/deferred work, see [FUTURE-ENHANCEMENTS.md](FUTURE-ENHANCEMENTS.md)
  — nothing in that file should be treated as built just because it's discussed there.
- "Built" means: the code exists, compiles, and (where applicable) has passing test coverage or was
  manually verified working. It does not mean "perfect" — known limitations are called out inline
  rather than hidden.
- Every claim below points at a real file/class/constant so it can be checked directly rather than
  taken on faith.

---

## 1. Technology stack

### 1.1 Backend — every direct dependency in `pom.xml`, and why it's there

| Dependency | Version | Purpose |
|---|---|---|
| Spring Boot (parent) | 4.0.6 | Application framework |
| Spring Framework | 7.0.7 | Core DI/MVC |
| Spring Security | 7.0.5 | Auth/authz filter chain |
| Java | 21 | Language/runtime |
| Tomcat (embedded) | 11.0.22 | Servlet container |
| `spring-boot-starter-webmvc` | — | REST controllers |
| `spring-boot-starter-security` | — | Filter chain, `AuthenticationManager`, BCrypt |
| `spring-boot-starter-oauth2-client` | — | Federated login protocol (Google/GitHub/Microsoft) |
| `spring-boot-starter-validation` | — | Jakarta Bean Validation (`@Valid`, `@Pattern`, `@NotEmpty`, …) |
| `spring-boot-starter-data-jdbc` | — | `NamedParameterJdbcTemplate` — the core domain's data access |
| `spring-boot-starter-data-jpa` | — | Hibernate — the business domain (Customer/Invoice/Services) only |
| `spring-boot-starter-mail` | — | SMTP (Gmail) for verification/reset/step-up/security-alert emails |
| `spring-boot-starter-actuator` | — | `/actuator/health`, `/actuator/info` (public, `show-details: never`) |
| `spring-boot-devtools` | — | Dev-only hot reload |
| `mysql-connector-j` | runtime | MySQL JDBC driver |
| `com.auth0:java-jwt` | 4.4.0 | JWT mint/verify (HMAC-SHA512) — the **only** JWT library on the classpath (jjwt was removed as redundant) |
| `org.projectlombok:lombok` | (BOM-managed) | `@Data`, `@Builder`/`@SuperBuilder`, `@RequiredArgsConstructor`, `@Slf4j`, etc. — excluded from the repackaged boot jar |
| `com.twilio.sdk:twilio` | 10.6.2 | SMS 2FA delivery |
| `com.google.zxing:core` + `:javase` | 3.5.3 | TOTP QR code rasterization (the RFC 6238 algorithm itself is hand-rolled in `TotpUtils`, not from a library) |
| `com.webauthn4j:webauthn4j-core` | 0.29.1.RELEASE | Passkey (WebAuthn) registration/assertion verification, used directly rather than Spring Security's session-based WebAuthn module (this app is stateless JWT) |
| `com.bucket4j:bucket4j-core` | 8.10.1 | In-memory token-bucket rate limiting |
| `software.amazon.awssdk:s3` | 2.30.0 (BOM) | S3 profile-image storage (`IMAGE_STORAGE_TYPE=s3`) |
| `org.apache.commons:commons-lang3` | 3.18.0 | `RandomStringUtils` (2FA codes), `StringUtils` |
| `org.apache.poi:poi-ooxml` | 5.5.1 | Excel/Office document generation |
| `nl.basjes.parse.useragent:yauaa` | 7.24.0 | User-agent parsing → "OS - Browser - Device" strings for the sessions list |
| `org.owasp:dependency-check-maven` | 12.1.1 | CVE scanning, gates CI at `failBuildOnCVSS=7` |
| `org.codehaus.mojo:versions-maven-plugin` | 2.18.0 | Dependency-freshness reporting |
| `spring-boot-starter-test` + `spring-security-test` | test scope | JUnit 5, Mockito, AssertJ, MockMvc |
| `webauthn4j-test` | test scope | Passkey ceremony test fixtures |

Maven profiles: `dev` (default), `prod`, `qa`, `stage`, `local` — each sets `spring.profiles.active`.

### 1.2 Frontend — every dependency in `tesseraapp/package.json`

| Dependency | Version | Purpose |
|---|---|---|
| `@angular/*` | ^21.2 | Standalone components, zoneless change detection, signals — no `NgModule` anywhere |
| `@angular/animations` | ^21.2.11 | Route/UI transitions |
| `@auth0/angular-jwt` | ^5.2.0 | JWT decode/expiry checks (never trusted for authorization — that's server-side) |
| `@jsverse/transloco` | ^8.4.0 | Runtime i18n (6 locales) |
| `bootstrap` + `bootstrap-icons` | ^5.3.8 / ^1.13.1 | UI framework, self-hosted (not CDN, for CSP compliance) |
| `@fontsource/ibm-plex-mono` + `-sans` | ^5.3.0 | Self-hosted fonts (same CSP reason) |
| `ngx-toastr` | ^20.0.5 | Toast notifications |
| `rxjs` | ~7.8.0 | Observables, the app's async/state backbone |
| `file-saver` | ^2.0.5 | Client-side file download triggers |
| `jspdf` (+ `html2canvas` transitively) | ^4.2.1 | Client-side PDF export (invoices) |
| `tslib` | ^2.3.0 | TypeScript helper runtime |
| **Dev:** `vitest` | ^4.0.8 | Test runner (via `@angular/build:unit-test`) |
| **Dev:** `eslint` + `angular-eslint` + `typescript-eslint` | — | Linting, gates CI |
| **Dev:** `prettier` | ^3.8.1 | Formatting |
| **Dev:** `jsdom` | ^28.0.0 | DOM environment for Vitest |

### 1.3 Infrastructure

- **Database:** MySQL 8.4 (local dev + Docker) / Aiven managed MySQL (`db3`, TLS `REQUIRED`) in production
- **Containerization:** Docker, multi-stage build (Angular compiled into the Spring Boot jar — one artifact, one origin)
- **Deployed on:** AWS ECS Fargate, behind CloudFront (custom domain `tesseraapp.dev`), Secrets Manager for credentials, CloudWatch for logs
- **CI/CD:** GitHub Actions — `ci.yml` (build + test against a MySQL service container, lint, dependency audit) and `deploy.yml` (ECR push + ECS deploy)
- **Also built (not the live deployment target):** GCP Cloud Run pipeline (`gcp/`), Azure App Service pipeline (legacy)

---

## 2. Architecture & conventions

- **Package layout** (`com.bob.angularspringbootfullstack`): `controller/` → `service/` + `service/serviceimpl/` → `repo/` + `repo/repoimpl/`, supported by `query/` (SQL constants), `rowmapper/`, `model/`, `dto/` + `dtomapper/`, `form/`, `enumeration/`, `event/` + `listener/`, `exception/`, `handler/`, `filter/`, `configuration/`, `tokenprovider/`, `utils/`, `constants/`, `seed/`, `report/`.
- **Data access split by domain, deliberately:**
  - **Core identity/auth domain** — hand-written SQL via `NamedParameterJdbcTemplate`. Per aggregate: `XQuery` (named-param SQL constants) + `XRowMapper` (`ResultSet`→model via Lombok builder) + `XRepo` (interface) + `XRepoImpl` (`@Repository`). `UserRepoImpl` additionally implements `UserDetailsService`.
  - **Business CRUD domain** (Customer/Invoice/Services) — JPA/Hibernate `@Entity` classes, explicit `@Column` annotations (required because `globally_quoted_identifiers: true` bypasses Hibernate's snake_case naming strategy).
- **Schema ownership:** one idempotent `src/main/resources/schema.sql` (`CREATE TABLE IF NOT EXISTS`, guarded `ALTER TABLE` via an `information_schema` check + `PREPARE`/`EXECUTE`, no `DROP` statements anywhere). `spring.sql.init.mode: never` — applied by hand, never automatically. **No Flyway or Liquibase** — removed deliberately (§4.1 of `IMPLEMENTATION-HISTORY.md`).
- **Response envelope:** every endpoint returns `ResponseEntity<HttpResponse>` — `{ timeStamp, statusCode, status, message, data }`, `data` a `Map<String,Object>` typically embedding the authenticated user alongside the payload.
- **DTO mapping:** `UserDTOMapper` (`BeanUtils.copyProperties`) — the boundary that keeps `password` off any API response.
- **Full Javadoc/TSDoc convention:** every class/method carries multi-line documentation explaining not just *what* but *how it relates to the rest of the system* — a project-wide convention, not incidental.

---

## 3. Identity & authentication (every mechanism, in full)

### 3.1 Password authentication
- Registration (`POST /user/register`) → email verification link required before `enabled=true`
- BCrypt password hashing, **strength 12** (`BCryptPasswordEncoder`)
- `AuthenticationManager` = `ProviderManager(DaoAuthenticationProvider)` + the BCrypt encoder + `UserRepoImpl` as `UserDetailsService`
- **Password complexity** (`constants/PasswordPolicy.java`, 2026-08-08): 8+ characters, uppercase, lowercase, digit, no whitespace — enforced identically on **all three** password-entry points (register, change, reset) via `@Pattern`. Frontend mirror (`constants/password-policy.ts`) for UX only.
- **Password reset** via emailed, single-use, expiring link (`resetpasswordverifications` table)
- **Anti-enumeration** (NFR-SEC-7): unknown-email and wrong-password failures are byte-identical (`UserControllerLoginEnumerationTest`); no error message ever confirms whether an email exists, anywhere in the app
- **Per-account brute-force lockout**: 5 failed attempts within 15 minutes locks the account (`notLocked=false`) until an administrator unlocks it

### 3.2 Multi-factor authentication — three independent second factors
- **TOTP (authenticator app)**, RFC 6238 implemented in-house (`TotpUtils`) — QR-code enrollment (ZXing rasterization), single-use recovery codes issued once and re-generatable only by disable-then-re-enroll, challenge-bound verification (a code is only valid against the specific challenge that requested it, never a bare "is this code right for this user")
- **SMS 2FA** via Twilio (`SMSUtils`) — sends a real text when `TWILIO_ACCOUNT_SID`/`TWILIO_AUTH_TOKEN`/`TWILIO_FROM_NUMBER` are configured; degrades to logging the code to the server console when they're blank/placeholder, so dev/CI never need a Twilio account. **Phone number shape validation** (`constants/PhonePolicy.java`, 2026-08-08) — real US 10-digit check, replacing a near-unrestricted length-only pattern. E.164 normalization (`SMSUtils.toE164US`) handles numbers with or without a leading country-code digit.
- **Passkeys (WebAuthn)**, built directly on `webauthn4j-core` (not Spring Security's session-based module, since this app is stateless JWT) — usernameless/discoverable registration and login, admin **revoke-only** control (no "reset" exists anywhere — a passkey's private key never leaves the authenticator by design)
- **MFA precedence when more than one is enrolled**: TOTP > SMS > (no second factor + anomalous login) → email step-up > plain tokens. Documented explicitly in `UserController.login`.

### 3.3 Federated login (OAuth2/OIDC)
- **Google, GitHub, Microsoft (Entra)** via `spring-security-oauth2-client` — protocol handling is entirely framework-provided; the bespoke logic is the token-exchange handler, `OAuth2LoginSuccessHandler`
- **Find-or-create identity resolution**: (provider, subject) link lookup → same-email account linking → new account creation, in that order (`FederatedIdentityServiceImpl.findOrCreateFederatedUser`)
- **Account linking/unlinking** from the Security Center — a single-use, five-minute, provider-bound ticket (`ProviderLinkTicketService`) authorizes attaching a *verified* external identity to the *currently signed-in* account, refusing an identity that already belongs to someone else (the account-takeover primitive this design specifically closes)
- **Unlinking refused** when it would remove the last sign-in method on an account
- **Account origin tracking** (`users.origin`, P2-1, 2026-08-08): an immutable fact stamped only at account creation (`FEDERATED_GOOGLE`/`FEDERATED_GITHUB`/`FEDERATED_MICROSOFT`), never touched again — including when a password account later links a federated identity

### 3.4 Sessions & tokens (the hybrid model)
- **Stateless access JWT** (30 min, HMAC-SHA512, `TokenProvider`) — never checked against a database on any request (NFR-PERF-2)
- **Stateful refresh sessions** (`refreshsessions` table, 5-day tokens) — every refresh rotates to a new token in the same "family," retiring the old one
- **Reuse/replay detection**: presenting an already-rotated (superseded) or revoked refresh token revokes the **entire family** and records a `TOKEN_REUSE_DETECTED` audit event — because the server cannot distinguish an attacker replaying a stolen token from the legitimate user, and treats it as theft either way
- **Self-service session management** (Security Center): list live sessions (device/IP/created/last-used/expires via `yauaa` user-agent parsing), revoke one, or "log out everywhere else"
- **Admin session management** (2026-08-08): the same capability exercised on *another* user's sessions — `GET /admin/user/{id}` returns their sessions, `DELETE /admin/user/{id}/sessions/{family}` revokes one, `DELETE /admin/user/{id}/sessions` revokes all — org-scoped, self-target-refused, audited against the target
- **`SessionService` is the single token-issuance seam** — every login path (password, SMS, TOTP, federated, refresh, password change) converges on `issueTokenPair`/`rotate`, so no JWT can exist without a corresponding tracked session row

### 3.5 Risk-adaptive step-up (anomaly detection)
- Every login is compared against **that account's own** history of devices and networks (network compared at prefix granularity, so a DHCP renewal isn't flagged as "new")
- An account **with** an enrolled second factor is challenged with it regardless of risk — the verdict never skips MFA
- An account **without** a second factor gets escalated to an **emailed one-time code** on an anomalous sign-in (`StepUpMethod.EMAIL_CODE`) — the one branch this control actually adds, since without it a leaked password alone would open a session
- No risk signal is ever echoed to the client — an anomaly-driven challenge and an ordinary MFA prompt look identical to the caller
- Configurable: `ANOMALY_DETECTION_ENABLED` (master switch), `ANOMALY_HISTORY_LIMIT` (distinct-fingerprint baseline size)
- `X-Forwarded-For` trust is explicit and required (`TRUSTED_PROXY_COUNT`) — defaults to 0 (header ignored) so a caller cannot forge a "familiar" network past this control by default

---

## 4. Authorization & access control

- **Permission-based RBAC**, not role-name checks — authority strings (`READ:USER`, `UPDATE:CUSTOMER`, `UPDATE:ROLE`, `DELETE:USER`, …) split off a role's stored permission string and matched via `hasAnyAuthority`/`@PreAuthorize`
- **Seven-role catalogue**, seeded with pinned IDs: `ROLE_GUEST`, `ROLE_USER`, `ROLE_MODERATOR`, `ROLE_HELP_DESK_ADMIN`, `ROLE_ORGANIZATION_ADMIN`, `ROLE_ADMIN`, `ROLE_APPLICATION_ADMIN`
- **Role-tier ceiling** (`enumeration/RoleType.java#canAssign`, built 2026-08-07): an administrator can never assign a role that outranks their own — closes a privilege-elevation-by-proxy hole where an org admin could otherwise promote someone to an unscoped top-tier role
- **Organization scoping** (FR-ORG): `ROLE_ORGANIZATION_ADMIN`'s user directory, single-user actions, analytics, **and — since 2026-08-08 — the shared `/customer/**` surface** (stats, list, single get, search, invoice list/get, the new-invoice picker, both XLSX exports) are all bounded to their active organizations; `ROLE_ADMIN`/`ROLE_APPLICATION_ADMIN` are unscoped by design, and every other role keeps system-wide business-data visibility (a deliberate scope decision, not yet full multi-tenancy — see `FUTURE-ENHANCEMENTS.md` §6.1). Enforced in SQL predicates, never by post-filtering a result set (a rule stated explicitly in code comments because post-filtering silently corrupts pagination totals). Single-record gets are checked post-fetch against the resolved scope.
- **Dual enforcement, every mutating admin endpoint**: the URL-level `SecurityConfig` matcher **and** a method-level `@PreAuthorize` repeat the same authority requirement, so a routing change alone can't reopen a gap
- **Self-targeting refused** on every admin mutation (role change, account-state change, session revoke, passkey revoke) — an administrator cannot elevate or lock themselves out through the admin surface; those belong to their own profile/Security Center
- **User-type classification** (`utils/UserTypeResolver.java`, P2-1, 2026-08-08): admin-facing `INTERNAL`/`EXTERNAL`/`FEDERATED` badge — federated status read from the immutable `origin` column, internal/external derived fresh on every read from an env-driven email-domain allowlist (`INTERNAL_DOMAINS`)
- **Capability-gated frontend UI** — `*appHasAuthority`/`[appRequiresAuthority]` directives and a fail-closed `capabilityGuard`, so the UI's own notion of "can I do this" is driven by the same authority strings the backend checks, not a role-name string comparison

---

## 5. Security hardening (transport, headers, rate limiting, audit)

- **Rate limiting** (`RateLimitFilter`, Bucket4j token-bucket) — `429` + `Retry-After` on excess requests
- **Security headers** (`SecurityConfig`): `X-Frame-Options: DENY`, `X-Content-Type-Options: nosniff`, HSTS (1 year, subdomains included), a real **Content-Security-Policy** (`default-src 'self'`, a `sha256`-pinned allowance for exactly one inline script — the theme-flash-prevention snippet — not a blanket `unsafe-inline`), `Referrer-Policy: strict-origin-when-cross-origin`, `Permissions-Policy` disabling camera/microphone/geolocation/payment
- **CORS**: single source of truth, env-driven allowed-origin patterns (`app.cors.allowed-origin-patterns`)
- **CSRF disabled** deliberately — stateless Bearer-token API, not cookie/session auth, so CSRF's threat model doesn't apply
- **Stateless session policy** (`SessionCreationPolicy.STATELESS`) throughout
- **Custom 401/403 handlers** (`CustomAuthenticationEntryPoint`, `CustomAccessDeniedHandler`) — always a clean JSON 401, never a redirect, so the frontend's silent-refresh interceptor logic can rely on it
- **Prod error hygiene**: no stack traces, no internal exception detail, ever reaches a production response body (`ErrorDetailScrubberTest`)
- **DB transport security**: `MYSQL_SSL_MODE: VERIFY_IDENTITY` in production (2026-08-08) — encrypts **and** authenticates the server, not just encrypts to it. Aiven's per-project CA (`certs/aiven-mysql-ca.pem`, public, safe to commit) is imported into the JRE's truststore at Docker build time. Verified three ways before touching production: the cert is well-formed, the import lands in the built image's truststore, and a live `VERIFY_IDENTITY` connection against the real Aiven instance succeeded (TLSv1.3). `qa`/`stage` deliberately stay at `REQUIRED` — different/no Aiven CA applies to those environments
- **Audit logging**: every security-relevant event (login, MFA changes, role changes, session revokes, passkey changes, suspicious logins, …) recorded to a `userevents` table via a **single event-listener seam** (`NewUserEventListener`) that never lets an audit-write failure break the action being audited
- **Console-only RBAC/auth diagnostics** (`AuthDiagnosticsLogger`) — tagged `[AUTH-GRANT]`/`[AUTH-DENY]`/`[AUTH-LOCK]`/`[RBAC-DENY]` server-side logs with the real reason a request succeeded or failed, while the client response stays generic (never leaks which specific check failed)
- **Secrets**: every credential (JWT secret, DB password, mail credentials, Twilio credentials, OAuth client secrets) is environment-variable-driven, never hardcoded; production supplies **no fallback default**, so a missing secret fails startup fast rather than booting insecurely
- **`CHANGE_ME` placeholder detection** (`OAuth2ClientConfigPlaceholderWarningTest`) — a boot-time warning fires if a federated-provider secret is still the literal `CHANGE_ME`, per provider

---

## 6. Notifications (email & SMS)

- **Email** (`EmailServiceImpl`, Gmail SMTP): account verification, password reset, step-up codes, security alerts — branded HTML via `EmailTemplate`, `multipart/alternative` (HTML + plain-text fallback)
- **SMS** (`SMSUtils`/`NotificationServiceImpl`): 2FA codes — real Twilio delivery when configured, console-log fallback otherwise; message body includes the brand name and a `Reply STOP to opt out` line (A2P 10DLC campaign compliance, 2026-08-08)
- **Async dispatch**: every notification fires on `CompletableFuture.runAsync`'s common pool so the triggering HTTP request returns without waiting on the SMTP/SMS round-trip; failures are funneled to SLF4J rather than the default uncaught-exception handler
- **Delivery-failure fallback for step-up codes**: if SMTP is unreachable, the code is written to the server log at WARN so the challenge stays completable locally — a deliberate dev affordance, not present in a properly-configured deployment

---

## 7. Business domain

- **Customers**: CRUD; every read (`stats`, list, single get, search, XLSX export) is org-scoped for `ROLE_ORGANIZATION_ADMIN` (2026-08-08) — every other role keeps system-wide visibility by design, see §4
- **Invoices**: CRUD, linked to customers, invoice numbering; list/get/export are org-scoped the same way, derived through the owning customer (invoices carry no tenant column of their own)
- **Services catalog**: CRUD (admin-managed) + a browse view (all authenticated users) for pre-filling a new invoice
- **Billing** and **Analytics**: admin-only dashboards — dual-area trend chart, acquisition bars, stacked status breakdown, service utilization; served from `/admin/analytics/**`, gated the same way every other admin surface is
- **Security dashboard** (FR-TPF-2): the review surface for anomaly detection — anomalous sign-ins, authentication trends, restricted accounts, MFA adoption, live sessions, all from one aggregated response rather than N separate "as of different instants" queries
- **Image storage**: pluggable abstraction (`ImageStorageService` → `LocalImageStorageService` or `S3ImageStorageService`, selected by `IMAGE_STORAGE_TYPE`) for profile images

---

## 8. Administration

- User directory (paginated, searchable) + single-user detail view
- Role reassignment, account enable/disable, lock/unlock — all audited against the **target** user, not just logged for the operator
- Admin-initiated profile-field edit (`PATCH /admin/user/{id}/update`) — the `{id}` path variable is authoritative and overwrites any body-supplied id (the inverse of the self-service endpoint's IDOR protection, safe here because the route is authority- and scope-gated)
- Session management (list + per-session + bulk revoke) — §3.4
- Passkey management (list + per-credential + bulk revoke, metadata only — nickname/transports/timestamps, never the credential ID or attestation object)
- Roles × Permissions matrix (read-only visibility into the seven-role catalogue)
- User-type badge (§4)

---

## 9. Frontend architecture

- **Angular 21**, 100% standalone components, zoneless change detection, **signals** as the primary state primitive
- **Lazy-loaded, preloaded routes** (`loadComponent` + `PreloadAllModules`)
- **Route guards**: `authenticationGuard`, `adminGuard`, `capabilityGuard` — usability aids; the backend independently enforces the same rules, so a guard bypass gains nothing
- **`token.interceptor`**: attaches `Authorization: Bearer`, and on a 401 silently calls the refresh endpoint and retries once — concurrent 401s share a single in-flight refresh via a `BehaviorSubject` guard rather than firing N parallel refresh calls
- **`cache.interceptor`**: client-side GET caching, invalidated on mutations (known limitation: purely client-side, so one user's write doesn't invalidate another user's cache — tracked, not yet backend-driven)
- **Design system**: dark/indigo theme, a shared `.sc-*` "data surface" CSS layer reused across every list/detail page, self-hosted fonts/icons (CSP-driven, not a preference)
- **⌘/Ctrl-K command palette** for navigation
- **Six-language i18n** (Transloco, runtime switching — not compile-time — across 26 of 28 templates, plus toasts and the command palette)
- **API base URL is environment-driven**, not hardcoded: dev derives it from `window.location.hostname` (works over LAN, not just `localhost`); production is a same-origin relative URL, since the SPA is compiled into the same jar as the API

---

## 10. Testing

- **Backend: 230 tests across 34 suites** (`mvn test`, Surefire-verified 2026-08-08) — JUnit 5, Mockito, AssertJ, MockMvc `standaloneSetup`. Covers: refresh rotation/replay, TOTP challenge binding, anomaly detection (both false-positive and false-negative directions), the security dashboard's query clamping, org-scope enforcement on reads *and* writes (user directory, analytics, **and now customer/invoice**), `X-Forwarded-For` forgery cases, RBAC/auth diagnostics, capability-denial messaging, prod error scrubbing, federated link/unlink refusal cases, login-enumeration resistance, brute-force lockout, role-tier ceiling, admin session revoke (bulk and per-session), passkey CRUD, WebAuthn challenge single-use/purpose-binding, the `CHANGE_ME` placeholder warning, password/phone policy regex correctness, offline schema-drift and table-casing guards.
- **Frontend: 87 specs across 8 files** (Vitest via `@angular/build:unit-test`) — token authority matching (exact, not prefix), the refresh interceptor's single-flight behavior, capability guard, admin guard, authentication guard, the command palette, the page-size selector.
- **Known, named gaps** (not hidden): no test exercises the *real* Spring Security filter chain (every backend test uses `standaloneSetup`, which bypasses `SecurityConfig` by design); no end-to-end/browser test suite exists (Playwright is tracked, not built); the frontend passkey UI has no dedicated spec coverage.
- **CI gates on**: `ng lint` (clean), `npm audit --audit-level=high` (exit 0), OWASP `dependency-check` (`failBuildOnCVSS=7`), both test suites, against a real MySQL service container (not mocked).

---

## 11. DevOps & deployment

- **Docker**: single multi-stage `Dockerfile`, env-driven (`SPRING_ACTIVE_PROFILES`) — one image serves dev/qa/stage/prod
- **AWS** (the live deployment): ECS Fargate, CloudFront (custom domain `tesseraapp.dev` + fallback `*.cloudfront.net`), Secrets Manager (all credentials), CloudWatch Logs (7-day retention, env-driven log levels), Aiven managed MySQL
- **GitHub Actions**: `ci.yml` (build/test/lint/audit) and `deploy.yml` (ECR push + ECS force-new-deployment)
- **GCP** and **Azure** deployment pipelines also exist (built, not the live target)
- **Multi-environment config**: `application-{dev,prod,qa,stage,local}.yml`, each with its own profile-specific overrides
- **Health checks**: `/actuator/health` (public, minimal detail), used by the ECS task definition

---

## 12. Documentation itself

- `README.md` — high-level project overview (not exhaustive by design — see this file for that)
- `documentation/GUIDE.md` — how everything currently works: architecture, getting started, security model, full API reference, testing
- `documentation/IMPLEMENTATION-HISTORY.md` — the retrospective: milestones, delivery timeline, and a detailed problem log (what broke, root cause, fix, standing lesson) for 25+ real incidents
- `documentation/FUTURE-ENHANCEMENTS.md` — the single source of truth for planned/deferred work (explicitly **out of scope** for this inventory)
- `documentation/flows/` — 16 click-to-database traces (Mermaid diagrams + file:line references + real request/response JSON + the SQL actually executed) covering every major user flow
- `aws/README.md` + `aws/RUNBOOK.md` — deployment and operations runbook, written for someone who didn't build the app
- Full multi-line Javadoc/TSDoc on every class — a project-wide convention, not spot coverage

---

## 13. What this inventory deliberately excludes

Per the scope boundary at the top: anything listed as ⬜ (not started) or 🔄 (in progress) in
`FUTURE-ENHANCEMENTS.md` is **not** repeated here as a built feature, including — for the avoidance
of doubt — full multi-tenancy for every role (only `ROLE_ORGANIZATION_ADMIN`'s customer/invoice
reads are scoped; every other role, including plain `ROLE_USER`, still sees business data
system-wide by design — see §4 and §6.1 of `FUTURE-ENHANCEMENTS.md`), the services catalog is not
org-scoped, Playwright/e2e coverage, backend HTTP caching, role CRUD,
self-service organization management, batch CSV upload, and machine-to-machine API access. If a
grader or reviewer asks whether one of those is done, the honest answer is no — check
`FUTURE-ENHANCEMENTS.md` §2–3 for the current state of each.
