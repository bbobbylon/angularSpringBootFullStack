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

---

## Relationship to the rest of `documentation/`

These flow docs are the **dynamic** view — what happens *over time* on a single request.
They complement, and link back to, the **static** reference docs:

- [`../architecture.md`](../architecture.md) — the component/layer structure (the boxes).
- [`../security.md`](../security.md) — the security model and threat posture.
- [`../api-reference.md`](../api-reference.md) — the endpoint catalog (request/response shapes).
- [`../database.md`](../database.md) — the schema the repositories read and write.
