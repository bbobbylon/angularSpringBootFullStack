# End-to-End Flow Documentation

> **What this is.** A complete, click-to-database tracing of *every* use case in the
> application. Each flow is documented from the literal button press in the browser,
> through the exact HTML and TypeScript that handle it, the HTTP interceptors, the JWT
> and header state on the wire, the Spring Security filter chain, the controller →
> service → repository call stack, the response envelope, and finally back to the UI
> state change the user sees.
>
> Every box in every diagram maps to real code, cited as a clickable
> `path:line` reference. If a diagram and the code ever disagree, **the code wins** —
> please open an issue (or fix the doc) rather than trusting the picture.

---

## How to read these documents

### The shared cast (sequence-diagram lifelines)

Every flow rides the same request/response machinery. Rather than redraw it 30 times,
that machinery is documented **once** in [`00-anatomy-of-a-request.md`](./00-anatomy-of-a-request.md)
and each per-flow diagram reuses the same named lifelines:

| Lifeline | Real artifact | Source |
| --- | --- | --- |
| **User** | the person at the keyboard | — |
| **DOM** | the component's HTML template | `*.component.html` |
| **CMP** | the component class | `*.component.ts` |
| **SVC** | `UserService` / `AdminUserService` / `CustomerService` | `src/app/service/*.ts` |
| **CACHE** | `cacheInterceptor` (runs first) | `src/app/interceptor/cache.interceptor.ts` |
| **TOK** | `tokenInterceptor` (attaches the JWT) | `src/app/interceptor/token.interceptor.ts` |
| **LS** | `localStorage` (`access_token` / `refresh_token`) | browser |
| **NET** | the browser's HTTP stack / the wire | — |
| **CORS** | Spring's CORS layer | `SecurityConfig#corsConfigurationSource` |
| **FILT** | `CustomAuthFilter` (per-request JWT gate) | `src/main/java/.../filter/CustomAuthFilter.java` |
| **TP** | `TokenProvider` (sign/verify JWTs) | `src/main/java/.../tokenprovider/TokenProvider.java` |
| **SEC** | `SecurityConfig` authorization rules | `src/main/java/.../configuration/SecurityConfig.java` |
| **CTRL** | the REST controller | `src/main/java/.../controller/*.java` |
| **SRV** | the service layer (`*ServiceImpl`) | `src/main/java/.../service/serviceimpl/*.java` |
| **REPO** | the repository (Spring `JdbcTemplate`) | `src/main/java/.../repo/repoimpl/*.java` |
| **DB** | the relational database (accessed via JDBC) | — |

### Conventions

- **`file:line`** — every step cites the exact code that runs. Paths are repo-relative;
  most editors and terminals make them clickable.
- **Token state badges** — at each hop a diagram note states what credential is on the
  wire: `🔓 no token` (public route), `🔑 access token`, or `♻️ refresh token`.
- **Branches** — alternate paths (MFA required, 401 → silent refresh, validation error)
  are drawn as labeled `alt`/`opt` blocks, never hidden.
- **The envelope** — every backend response is the same `HttpResponse` shape
  (`src/main/java/.../model/HttpResponse.java`); the `data` map is what differs per flow.

### Start here

1. **[`00-anatomy-of-a-request.md`](./00-anatomy-of-a-request.md)** — read this first. It
   explains the interceptor chain, the JWT anatomy, the Spring Security filter pipeline,
   the authorization matcher table, and the error/refresh paths that **every** other flow
   depends on. The per-flow docs assume you've read it and only call out where they differ.

---

## Flow index

Legend: ✅ documented · ⏳ planned

### 0 · Cross-cutting spine

| # | Flow | Doc | Status |
| --- | --- | --- | --- |
| 00 | Anatomy of a request (interceptors → filter → authz → controller → UI) | [00-anatomy-of-a-request.md](./00-anatomy-of-a-request.md) | ✅ |

### 1 · Identity & authentication (public-facing)

| # | Flow | Doc | Status |
| --- | --- | --- | --- |
| 01 | Register / signup + account-email verification | [01-register-and-verify.md](./01-register-and-verify.md) | ✅ |
| 02 | Login (password) + SMS-MFA + TOTP branches | [02-login-and-mfa.md](./02-login-and-mfa.md) | ✅ |
| 03 | Forgot password → reset link → set new password | [03-password-reset.md](./03-password-reset.md) | ✅ |
| 04 | Federated / OAuth2 login (incl. MFA handoff) | [04-federated-oauth2.md](./04-federated-oauth2.md) | ✅ |
| 05 | Token refresh, silent 401 refresh & session rotation | [05-token-refresh-sessions.md](./05-token-refresh-sessions.md) | ✅ |

### 2 · Self-service account & Security Center

| # | Flow | Doc | Status |
| --- | --- | --- | --- |
| 10 | Profile: view, audit events, update, password, settings, image, toggle SMS-MFA | [10-profile-and-account.md](./10-profile-and-account.md) | ✅ |
| 11 | Authenticator (TOTP) enrollment: setup → enable → disable → status | [11-totp-enrollment.md](./11-totp-enrollment.md) | ✅ |
| 12 | Sessions & devices: list, revoke one, revoke all others | [12-sessions-and-devices.md](./12-sessions-and-devices.md) | ✅ |

### 3 · Administration & RBAC

| # | Flow | Doc | Status |
| --- | --- | --- | --- |
| 20 | Admin users: list, details, change role, change settings; roles × permissions matrix | [20-admin-users-rbac.md](./20-admin-users-rbac.md) | ✅ |

### 4 · Business domain (CRUD)

| # | Flow | Doc | Status |
| --- | --- | --- | --- |
| 30 | Customers: list/search, new, details, update | [30-customers.md](./30-customers.md) | ✅ |
| 31 | Invoices: list, new, details | [31-invoices.md](./31-invoices.md) | ✅ |
| 32 | Home dashboard & stats | [32-dashboard.md](./32-dashboard.md) | ✅ |
| 33 | Analytics hub (admin): KPIs, trends, status/type charts | [33-analytics.md](./33-analytics.md) | ✅ |
| 34 | Billing overview (admin): totals, collection rate, recent invoices | [34-billing.md](./34-billing.md) | ✅ |
| 35 | Services & apps catalog: available services + pricing | [35-services-catalog.md](./35-services-catalog.md) | ✅ |

---

## Forecasted & not-yet-implemented (gap register)

> So there are **no silent gaps**: every planned-but-unbuilt capability is listed here and
> cross-referenced to the flow it extends. Sourced from `plan.md` (M0–M7 roadmap),
> `software_requirements_specification.md`, and `TODO` / `Not yet implemented` markers in the code.
> Status as of 2026-06-15.

### Roadmap milestones still open (`plan.md`)

| Milestone | Status | What's missing | Flow |
| --- | --- | --- | --- |
| M1 — auth-screen polish | 🔄 | login/register/verify/reset redesigned ✓; **route transitions + skeleton loaders** open | [01](./01-register-and-verify.md), [02](./02-login-and-mfa.md), [03](./03-password-reset.md) |
| M2 — security/activity dashboard | ⬜ partial | Home is still the *business* dashboard; the **security overview** (login chart, MFA-coverage ring, active-session counter, audit feed on Home) isn't built — an audit feed exists only in the Security Center / Profile | [32](./32-dashboard.md), [10](./10-profile-and-account.md), [12](./12-sessions-and-devices.md) |
| M3 — roles × permissions matrix | ✅ read-only | grid shipped (`/roles`); **in-grid toggle assignment** not built — assignment is via the Users dashboard | [20](./20-admin-users-rbac.md) |
| M6 — risk-based step-up + lockout | 🔄 | brute-force lockout shipped (login gate, FR-EXT-1 partial); **new-device/IP risk step-up** not built | [02](./02-login-and-mfa.md) |
| M7 — micro-interactions | ⬜ | Ctrl+K palette, empty/error states, toast restyle | all |

### Backend endpoints / features planned

| Planned capability | Source marker | State | Flow |
| --- | --- | --- | --- |
| Admin **profile-field** update `PATCH /user/admin/update/{userId}` (org-scoped) | `UserController.java:519` | not built — admins change role + account-state only | [20](./20-admin-users-rbac.md) |
| Link a *standalone* invoice to a customer `PUT /customer/invoice/{invoiceId}/addtocustomer/{customerId}` | `CustomerController.java:183` | not built | [31](./31-invoices.md) |
| Draft invoices (nullable customer) | `Invoice.java:80` | not built — every invoice is created already linked | [31](./31-invoices.md) |
| Invoice total-sum `@Query` | `InvoiceRepo.java:15` | not built (stats use `CustomerQuery.STATS_QUERY`) | [31](./31-invoices.md), [32](./32-dashboard.md) |
| Deeper org-scoped role system | `RoleRepoImpl.java:24` | partial — several `RoleRepoImpl` methods are `Not yet implemented; return null` | [20](./20-admin-users-rbac.md) |
| Server-side `@Valid` on registration & customer-create | flows 01 / 30 | open — validation is **client-side only**, bypassable by a direct API call | [01](./01-register-and-verify.md), [30](./30-customers.md) |

### Known debt / hardening (tracked, non-blocking)

| Item | Source | Note |
| --- | --- | --- |
| SMS-2FA dispatch **stubbed** | `NotificationServiceImpl.java:54` | logged, not sent; TOTP ([11](./11-totp-enrollment.md)) is the production factor. Live SMS = Twilio creds + uncomment |
| Profile-image storage hardcoded to `~/Downloads/images` | `UserController.java:316,359`, `WebMvcConfig.java:20` | dev-only; move to a configurable path / object storage |
| HTTP caching is client-side | `cache.interceptor.ts` TODO | move to backend (ETag / Redis) and delete the interceptor |
| `url` column stores a bare key | `UserQuery.java:36`, `UserRepoImpl.java:182` | rename to `verification_key` (DB migration deferred) |
| Hardcoded API base `localhost:8080` | `environment.ts:11` | make environment-driven before any real deployment |
| Near-zero tests | `plan.md §3` | priority paths: rotation/reuse ([05](./05-token-refresh-sessions.md)), TOTP challenge binding ([02](./02-login-and-mfa.md)/[11](./11-totp-enrollment.md)), org scoping ([20](./20-admin-users-rbac.md)) |
| Two JWT libraries on the classpath | `plan.md §3` | `jjwt` + `java-jwt` — consolidation candidate |
| `HandleException` exposes `.reason`/`.message` | `HandleException.java:31` | strip PII before production |
| `.env` placeholder `jwt.secret` | `.env` | must be high-entropy anywhere reachable — it underpins every signature in [`00 §6`](./00-anatomy-of-a-request.md) |

### Explicitly out of scope (SRS §1.4)

Machine-to-machine / client-credentials authorization, SCIM provisioning, and SAML federation.
AI anomaly detection and a login-analytics dashboard (**FR-EXT-2**) are *planned, time-permitting*.

---

## Relationship to the rest of `documentation/`

These flow docs are the **dynamic** view — what happens *over time* on a single request.
They complement, and link back to, the **static** reference docs:

- [`../GUIDE.md` §1](../GUIDE.md#1-architecture) — the component/layer structure (the boxes).
- [`../GUIDE.md` §7](../GUIDE.md#7-security-model) — the security model and threat posture.
- [`../GUIDE.md` §8](../GUIDE.md#8-api-reference) — the endpoint catalog (request/response shapes).
- [`../GUIDE.md` §9](../GUIDE.md#9-database) — the schema the repositories read and write.
