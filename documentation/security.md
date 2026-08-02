# Security & Authentication Guide

The complete authentication and authorization model: how login works, how JWTs are issued and verified, how authority-based access control is enforced, refresh-session rotation with reuse detection, multi-factor authentication, federated login, and the transport hardening around it all.

> This is the single, current security reference, covering refresh-session rotation (M5), authenticator-app MFA (M4), federated login, and the brute-force gate (M6).
>
> **Key source files:** `configuration/SecurityConfig.java` · `tokenprovider/TokenProvider.java` · `filter/CustomAuthFilter.java` · `constants/Constants.java` · `service/serviceimpl/SessionServiceImpl.java` · `controller/UserController.java`
> **See also:** [api-reference.md](api-reference.md) · [database.md](database.md) · [architecture.md](architecture.md)

---

## Table of contents

1. [The model at a glance](#1-the-model-at-a-glance)
2. [Login & authentication](#2-login--authentication)
3. [JWT tokens](#3-jwt-tokens)
4. [Per-request verification (the JWT filter)](#4-per-request-verification-the-jwt-filter)
5. [Authorization (RBAC)](#5-authorization-rbac)
6. [401 vs 403](#6-401-vs-403)
7. [Refresh-session rotation & reuse detection](#7-refresh-session-rotation--reuse-detection)
8. [Multi-factor authentication](#8-multi-factor-authentication)
9. [Federated login (OAuth2 / OIDC)](#9-federated-login-oauth2--oidc)
10. [Password & account security](#10-password--account-security)
11. [Transport, CORS & headers](#11-transport-cors--headers)
    - [11.1 Deployment parity — what changes between `start.sh` and AWS](#111-deployment-parity--what-changes-between-startsh-and-aws)
12. [Public endpoints](#12-public-endpoints)
13. [Known limitations & remaining work](#13-known-limitations--remaining-work)
14. [Organization-scoped administration](#14-organization-scoped-administration)

---

## 1. The model at a glance

TesseraApp uses an **in-house, stateless-first, zero-trust** authentication core:

- **Stateless access tokens.** Every API call carries a JWT access token that's verified by signature alone — no database lookup, no server session (`SessionCreationPolicy.STATELESS`).
- **Stateful refresh tokens.** Refresh tokens are tracked in the `refreshsessions` table so they can be **rotated**, **listed** (the Security Center device list), and **revoked** — the one place the system is intentionally stateful.
- **Permission-based authorization.** A user's single role carries a comma-separated permission string (e.g. `READ:USER, UPDATE:CUSTOMER`); each permission becomes a Spring Security authority matched against per-endpoint rules.

This "stateless access + stateful refresh" split is the heart of the design: fast, scalable request handling (no per-request DB hit) **and** revocable sessions.

### Hybrid CIAM: what's in-house vs what's federated

This is a **hybrid** Customer Identity & Access Management system: the identity *core* is built in-house, while sign-in at the *edges* can be delegated to external identity providers. The defining property is a **single token-exchange seam** — every authentication path, however it starts, converges on `SessionService` minting **our own** JWT, so RBAC, MFA policy, and audit logging apply identically to a federated user and a password user.

| Built in-house (our logic) | Delegated to a third party (the "hybrid" half) |
|---|---|
| Email/password credential auth (`AuthenticationManager` + BCrypt-12, our `users` store) | Federated login (Google / GitHub / Microsoft) via `spring-security-oauth2-client` |
| JWT mint/verify (`TokenProvider`, HMAC-SHA512) | Transactional email (verify/reset) via Gmail SMTP (JavaMail) |
| Refresh-session rotation + reuse detection (`SessionServiceImpl`) | SMS second factor via the Twilio SDK |
| Permission-based RBAC (`SecurityConfig`, authority strings) | |
| TOTP MFA (RFC 6238, `TotpUtils`) — algorithm implemented in-house | |
| Brute-force gate, audit logging, account lifecycle | |

The federated providers are a **convergence point, not a replacement**: `OAuth2LoginSuccessHandler` performs find-or-create on `(provider, subject)` and then issues *our* token pair (FR-FED-4), so a Google login becomes an ordinary tracked session subject to the same authority checks and refresh rotation.

### Third-party integrations — and how fully each is used

Not every declared dependency is exercised end-to-end. "Wired" (on the classpath, code present) is distinct from "utilized" (actually doing work at runtime):

| Dependency | Purpose | Utilization |
|---|---|---|
| `spring-security-oauth2-client` | Federated OAuth2/OIDC login | ✅ Fully wired; **env-gated** — active only when `GOOGLE_*` / `GITHUB_*` / `MICROSOFT_*` credentials are set (`OAuth2ClientConfig`); otherwise a boot-only placeholder registration and no login buttons |
| JavaMail (`spring-boot-starter-mail`) | Account/password verification emails | ✅ **Live** — real Gmail SMTP via `MAIL_USERNAME` / `MAIL_PASSWORD` |
| `com.auth0:java-jwt` | JWT mint/verify | ✅ Fully used — the actual token engine (`TokenProvider`, `ExceptionUtils`, `HandleException`) |
| ZXing (`core` + `javase`) | TOTP enrollment QR rasterization | ✅ Used |
| yauaa | Device/user-agent parsing for sessions + audit | ✅ Used |
| Apache POI | XLSX customer/invoice exports | ✅ Used |
| OWASP `dependency-check-maven` | Build-time CVE gate (`failBuildOnCVSS=7`) | ✅ Used in the build |
| **Twilio SDK** | SMS 2FA delivery | ⚠️ **Wired but stubbed** — `SMSUtils.sendSMS` is complete, but `NotificationServiceImpl.sendTwoFactorCode` keeps the call commented out and logs the code instead, to avoid Twilio charges in dev. Demonstrable, not production-delivered. |
| **`io.jsonwebtoken:jjwt`** (api/impl/jackson) | JWT | ❌ **Declared but unused** — zero imports anywhere in `src/`; all JWT work goes through `java-jwt`. Redundant; safe to remove. |

---

## 2. Login & authentication

### Endpoint
`POST /user/login` — body `LoginForm { email, password }` (both validated).

### Flow

```
POST /user/login {email, password}
        │
        ▼
authenticate(email, password)                          [UserController]
  1. Brute-force gate: if ≥5 failed attempts for this email in the last 15 min → reject
  2. Publish LOGIN_ATTEMPT  (only if the email maps to a real user → no enumeration)
  3. authenticationManager.authenticate(email, password)
        └─ DaoAuthenticationProvider
             ├─ UserRepoImpl.loadUserByUsername(email)   → UserPrincipal (UserDetails)
             └─ BCryptPasswordEncoder.matches(raw, hash) → verify password
  4. On success, publish LOGIN_ATTEMPT_SUCCESS (unless 2FA, where success fires after code check)
        │
        ▼
login() decides the response branch:
  ├─ user.usingTotp  ──▶ sendTotpChallenge()    → { user, challenge }      (NO tokens)
  ├─ user.using2FA   ──▶ sendVerificationCode() → { user } + SMS sent      (NO tokens)
  └─ otherwise       ──▶ sendResponse()         → { user, access_token, refresh_token }
```

### Brute-force protection (M6)
Before authenticating, `authenticate()` counts recent `LOGIN_ATTEMPT_FAILURE` audit events for the email over a **15-minute sliding window**; **5+ failures** short-circuits with a generic *"Too many failed login attempts"* message. The check runs only for known emails but returns the **same generic message** regardless, so it never reveals whether an account exists.

### Anti-enumeration
`LOGIN_ATTEMPT` / `LOGIN_ATTEMPT_FAILURE` events fire **only** when the email maps to a real user, and authentication failures return a generic message. Combined with `setHideUserNotFoundExceptions(false)` (so the global handler maps the failure uniformly), the API never discloses whether an email is registered. *(This mirrors the project's standing rule: never reveal account existence through responses or errors.)*

---

## 3. JWT tokens

Both token types are signed with **HMAC-SHA512** using `JWT_SECRET`, and both carry the issuer `BOBBYLON_LLC` and audience `BOBS_MANAGEMENT`.

| Claim | Access token | Refresh token | Meaning |
|-------|:---:|:---:|---------|
| `iss` | ✅ `BOBBYLON_LLC` | ✅ | issuer (verified) |
| `aud` | ✅ `BOBS_MANAGEMENT` | ✅ | audience |
| `sub` | ✅ user id | ✅ user id | the subject |
| `authorities` | ✅ `["READ:USER", …]` | ❌ **absent** | permissions |
| `sid` | ✅ family id | ✅ family id | refresh-session family |
| `jti` | ❌ | ✅ | this refresh token's rotation id |
| `iat` / `exp` | ✅ | ✅ | issued-at / expiry |
| **Lifetime** | **30 min** (`1_800_000` ms) | **5 days** (`432_000_000` ms) | |

**Decoded access token (payload):**
```json
{
  "iss": "BOBBYLON_LLC",
  "aud": "BOBS_MANAGEMENT",
  "sub": "21",
  "authorities": ["READ:USER", "READ:CUSTOMER", "UPDATE:USER", "UPDATE:ROLE", "DELETE:USER", ...],
  "sid": "5f1c…-family-uuid",
  "iat": 1750000000,
  "exp": 1750001800
}
```

**Why the refresh token has no `authorities`:** the verifier intentionally does **not** require the `authorities` claim, so refresh tokens verify fine — but `CustomAuthFilter` refuses to *authenticate* any token whose authorities are empty. This is what stops a refresh token from being used as an access token (see §4).

---

## 4. Per-request verification (the JWT filter)

`CustomAuthFilter` (a `OncePerRequestFilter`) runs **before** `UsernamePasswordAuthenticationFilter` on every non-public request.

**`shouldNotFilter()` skips** the filter when any of these is true:
- no `Authorization: Bearer …` header,
- the request is an `OPTIONS` preflight,
- the URI matches a `PUBLIC_ROUTES` prefix.

**`doFilterInternal()`** otherwise:
1. extracts the user id (`sub`) and raw token,
2. `tokenProvider.isTokenValid(userId, token)` — checks **signature**, **expiry**, and that the token's `iat` is **after** the user's `passwordChangedAt` (so tokens minted before a password change are dead),
3. reads authorities from the token:
   - **non-empty** → builds an `Authentication` (principal = the `UserDTO`) and sets it in the `SecurityContext`,
   - **empty** (a refresh token) → **clears** the context, so authority checks fail with 401,
4. always continues the chain, letting `SecurityConfig`'s rules + the entry point produce the final response.

---

## 5. Authorization (RBAC)

### Permissions → authorities
A user has exactly one `role`; its `permission` string (e.g. `READ:USER, UPDATE:CUSTOMER`) is split on commas into `SimpleGrantedAuthority` instances. These are embedded in the access token and reconstituted on each request.

### The rules (evaluated top-down)
From `SecurityConfig.securityFilterChain()` — **order matters**; specific rules precede catch-alls:

| # | Matcher | Requirement |
|---|---------|-------------|
| 1 | `POST /user/register`, `POST /user/login`, `/actuator/**`, `PUBLIC_URLS` | `permitAll` |
| 2 | `DELETE /user/delete/**` | `DELETE:USER` |
| 3 | `DELETE /customer/delete/**` | `DELETE:CUSTOMER` |
| 4 | `PATCH /admin/user/*/role/**` | `UPDATE:ROLE` |
| 5 | `PATCH /admin/user/*/settings` | `UPDATE:USER` |
| 6 | `/admin/**` | `UPDATE:USER` **or** `UPDATE:ROLE` |
| 7 | `/user/totp/**`, `/user/sessions/**` | `authenticated` (any logged-in user — self-service) |
| 8 | `GET /**` | `READ:USER` **or** `READ:CUSTOMER` |
| 9 | `POST /**` | `UPDATE:USER` **or** `UPDATE:CUSTOMER` |
| 10 | `PUT /**` | `UPDATE:USER`, `UPDATE:CUSTOMER`, **or** `UPDATE:ROLE` |
| 11 | any other request | `authenticated` |

Two ordering subtleties:
- **Admin rules (4–6) precede the broad verb catch-alls (8–10)** so role reassignment truly demands `UPDATE:ROLE`.
- **Self-service rules (7) precede the catch-alls** so a `ROLE_GUEST` can still manage their *own* TOTP/sessions without staff authorities.

### Defense in depth
`AdminUserController` repeats these checks with method-level `@PreAuthorize` (enabled via `@EnableMethodSecurity`), so URL-level and method-level enforcement stay in lockstep — and self-targeting is forbidden on role changes.

### The seven roles
See the catalog in [database.md §12](database.md#12-reference-data). Quick mental model: `GUEST < USER < MODERATOR < HELP_DESK_ADMIN < ORGANIZATION_ADMIN < ADMIN < APPLICATION_ADMIN`, where only `ADMIN`/`APPLICATION_ADMIN` hold `DELETE:*` and only admin tiers hold `UPDATE:ROLE`.

---

## 6. 401 vs 403

| Code | Meaning | Trigger | Handler |
|------|---------|---------|---------|
| **401 Unauthorized** | "I don't know who you are" | no/expired/invalid token | `CustomAuthenticationEntryPoint` |
| **403 Forbidden** | "I know you, but you can't" | valid token lacking the required authority | `CustomAccessDeniedHandler` |

Both return the application's standard `HttpResponse` JSON envelope rather than Spring's default HTML.

**Except for top-level browser navigations, which get a styled page (2026-07-29).** Once the SPA is served from the same origin as the API, these handlers also answer a human who typed a protected URL or followed a stale link — and that person was being shown a wall of raw JSON. `utils/BrowserErrorPage` distinguishes the two callers and renders a branded 401/403 page for the navigation case. **The status code is unchanged in both branches; only the representation differs.**

The detection signal matters more than it looks:

| Signal | Verdict | Why |
|---|---|---|
| `X-Requested-With: XMLHttpRequest` | JSON, always | Definitive "this is a programmatic call" |
| `Sec-Fetch-Mode: navigate` (+ `Sec-Fetch-Dest: document`) | HTML | Fetch metadata is set by the *browser*, not by page script, so it cannot be forged. This is the branch that runs in practice |
| No fetch metadata at all | JSON unless it is a `GET` that explicitly asks for `text/html` **and** carries no `Authorization` header | Conservative fallback for older clients |

> **Why not just content-negotiate on `Accept`?** Because Angular's `HttpClient` sets no `Accept` header of its own, so an XHR can arrive with none or with a browser-supplied default. Getting that wrong means serving HTML to `token.interceptor`, whose silent-refresh path keys off a clean JSON `401` — the whole reason `defaultAuthenticationEntryPointFor(AnyRequestMatcher)` exists (see the comment in `SecurityConfig`). The bias throughout is toward JSON: a false negative degrades one person's error page, a false positive signs everybody out.

---

## 7. Refresh-session rotation & reuse detection

The stateful half of the model, implemented in `SessionServiceImpl` over the `refreshsessions` table.

### Vocabulary
- **family** (`sid`) — one logical session (one device login). Stable across rotations; the unit the Security Center lists and revokes.
- **jti** — one concrete refresh token within a family. Every refresh mints a new `jti` and supersedes the old row.

### Issuing (`issueTokenPair`)
On login / 2FA completion: generate a new `family` + `jti`, insert a `refreshsessions` row (device + IP from the request, 5-day expiry), and return an access token stamped with the family and a refresh token stamped with `jti` + family.

### Rotating (`rotate`, called by `GET /user/refresh/token`)
1. Verify signature/expiry + `passwordChangedAt` (a stolen token dies here like on any call).
2. Resolve the `jti` → its `refreshsessions` row.
3. **If the row is `superseded` or `revoked` → reuse detected:** revoke the **entire family**, write a `TOKEN_REUSE_DETECTED` audit event, and reject. The user must re-authenticate.
4. Otherwise: supersede the old row, insert a new row in the same family (fresh 5-day expiry → **sliding sessions**), and return a rotated token pair.

### Two deliberate design choices
- **`rotate()` is NOT `@Transactional`.** Reuse detection must *commit* the family revocation and *then* throw; inside a transaction the throw would roll the revocation back, un-punishing the replay it just caught.
- **Fail-closed write order.** Supersede-old-then-insert-new: if the insert crashes mid-way, the presented token is already retired and the user simply re-logs in — an inconvenience, never two live tokens in one family.

### Managing sessions
- `GET /user/sessions` — list active sessions/devices (the caller's current one is identifiable via its `sid`).
- `DELETE /user/sessions/{family}` — revoke one session (ownership enforced by the `user_id` predicate).
- `DELETE /user/sessions` — revoke the *other* sessions (sign out everywhere else).
- A **password change** revokes all sessions, then opens a fresh one for the current browser.

---

## 8. Multi-factor authentication

Two independent second factors. **TOTP takes precedence over SMS** — a confirmed authenticator means the SMS path is skipped at login.

### TOTP (authenticator app) — fully implemented
Self-service endpoints under `/user/totp/**` (authentication required, no staff authority):

| Endpoint | Purpose |
|----------|---------|
| `POST /user/totp/setup` | Generate a Base32 secret + an `otpauth://` QR (PNG data URI) — secret stored **unconfirmed** |
| `POST /user/totp/enable` | Verify a code to **confirm** enrollment → flips `totpcredentials.confirmed` + `users.using_totp`, returns recovery codes |
| `POST /user/totp/disable` | Remove the authenticator |
| `GET /user/totp/status` | Whether TOTP is enrolled |
| `POST /user/verify/totp` | **Login completion** — public; accepts the `challenge` + code |

**The challenge is the security boundary.** Because a TOTP code always exists on the user's phone, a naked "verify TOTP" endpoint would let anyone with the authenticator skip the password. So at first-factor success the server mints a short-lived `mfachallenges` row and returns the opaque `challenge`; `POST /user/verify/totp` refuses any code not accompanied by a **live** challenge. Recovery codes are single-use, stored as SHA-256 hashes, consumed via `RECOVERY_CODE_USED`.

### SMS 2FA — wired but stubbed
When `using_mfa` is set (and TOTP is not), login sends a code via `GET /user/verify/code/{email}/{code}` to complete. **The Twilio send is stubbed in dev** — the code is logged to the server console, not delivered. The code lives in `twofactorverifications` with an expiry.

---

## 9. Federated login (OAuth2 / OIDC)

Standard Authorization Code flow via Spring Security's OAuth2 client. **Active only when provider credentials are configured** (`GOOGLE_*` / `GITHUB_*` / `MICROSOFT_*` in `.env`); otherwise the app logs `Federated login providers configured: none`.

```
SPA → GET /oauth2/providers                        (discover which providers are configured)
SPA → /oauth2/authorization/{provider}             (Spring initiates the Authorization Code flow)
        provider login + consent
provider → /login/oauth2/code/{provider}           (callback; Spring validates the `state`)
        │
        ▼
OAuth2LoginSuccessHandler                           (the token-exchange seam, FR-FED-4)
  ├─ find-or-create the local user via oauthproviderlinks (provider + provider_subject)
  ├─ issue OUR access + refresh tokens (a tracked session, like in-house login)
  └─ redirect the browser to the SPA /oauth2/callback with the tokens (or an MFA handoff)
```

- Only the **provider name + stable subject id** are stored (`oauthproviderlinks`), never a third-party credential. `UNIQUE(provider, provider_subject)` makes find-or-create idempotent.
- **Statelessness note:** the OAuth2 handshake briefly uses the servlet session to hold the CSRF `state` between redirect and callback; no `SecurityContext` is ever stored, and the session plays no part once our tokens are issued.

### What this federation *is* — and what it is *not*

"Federation" is an overloaded word; be precise about which one this is, because the provider consoles offer several similar-sounding features.

**What this app does — inbound social login (OAuth 2.0 / OpenID Connect).** The app is the **Relying Party (OAuth client)**; the provider (Google / GitHub / Microsoft) is the **Identity Provider (IdP)** that authenticates the user and returns a signed **OIDC ID token**. The app verifies it and mints *its own* JWTs — it *delegates authentication inbound* to an external IdP. That's the "Sign in with Google/GitHub/Microsoft" button, and it is the entirety of this app's FR-FED feature. Setup is just an **OAuth Client ID** (provider console → credentials) + the callback `…/login/oauth2/code/{provider}`; Spring Security's OAuth2 client does the code exchange and JWKS signature validation, so no `passport`/`authlib`-style hand-rolling is needed.

**What this app does NOT do — and must not be confused with:**

| Feature (provider naming) | Direction of trust | Purpose | Relevant here? |
|---|---|---|---|
| **This app's federated login** (OAuth2/OIDC social login) | **inbound** — external IdP → *our app* | end-users sign into TesseraApp with a Google/GitHub/Microsoft account | ✅ yes |
| **Google Cloud Workforce Identity Federation** | outbound — external IdP → *Google Cloud* | let enterprise employees from another IdP access **GCP** console/APIs | ❌ no (a GCP IAM product, unrelated to app login) |
| **Google Cloud Workload Identity Federation** | outbound — external workload → *Google Cloud* | let CI/apps access **GCP** without service-account keys | ❌ no |
| **SAML federation** | inbound (enterprise SSO) | XML-assertion SSO, common in enterprise | ❌ explicitly out of scope this revision (see [§12](#12-known-gaps--rejected-alternatives) — SAML) |

> **The tell — ask "what resource is being protected?"** If it's *your app's login*, you want OAuth2/OIDC social login (what's built here). If it's *Google Cloud resources*, that's Workforce/Workload Identity Federation — a different product you'd only touch to manage GCP access, never to add a login button. Creating an **OAuth Client ID** (as done for Google/GitHub) is social login; configuring a **workforce/workload identity pool** is not.

> **Consent-screen scope (Google):** because the app's OAuth consent screen is **External**, any Google account can sign in (verified by the fact that both a `@lewisu.edu` Workspace account and a personal `@gmail.com` account authenticate successfully). Switching the consent screen to **Internal** would restrict logins to a single Workspace org — a provider-console setting, not a code change.

---

## 10. Password & account security

- **Hashing:** BCrypt (`BCryptPasswordEncoder`) — verified by `DaoAuthenticationProvider` at login.
- **Registration → verification:** `POST /user/register` creates a disabled account; an email carries a UUID activation key resolved by `GET /user/verify/account/{key}` (in dev the link is logged to the console). Only verified accounts are `enabled`.
- **Password reset:** `GET /user/resetpassword/{email}` emails a one-time key → `GET /user/verify/password/{key}` resolves it to the user → `PUT /user/new/password` sets the new password (the key/password never travel in a query string).
- **Password change → token invalidation:** `users.password_changed_at` is set to `NOW()`; `isTokenValid` rejects any JWT issued before it, so **every outstanding token dies** on a password change (FR-JWT-6). The change handler also revokes all refresh sessions and opens a fresh one for the current browser.

> **Note (privilege escalation, fixed):** an old `PATCH /user/update/role/{roleName}` once let any authenticated user reassign their *own* role. It was **removed**; role reassignment is now admin-only via `PATCH /admin/user/{id}/role/{roleName}` (requires `UPDATE:ROLE`, forbids self-targeting).

---

## 11. Transport, CORS & headers

**CSRF is disabled** — correct for a stateless, token-in-header API (no cookies to forge). **HTTP Basic is disabled.**

**Security headers** (`SecurityConfig`):
- `X-Frame-Options: DENY` (clickjacking),
- `X-Content-Type-Options: nosniff`,
- HSTS — `max-age=31536000; includeSubDomains` (**inert over plain HTTP**, so it does nothing locally and everything behind the load balancer's TLS),
- **Content-Security-Policy** — `default-src 'self'`, with `script-src` allow-listing exactly one inline script *by SHA-256 hash* (the theme-flash-prevention snippet in `index.html`) rather than opening `'unsafe-inline'`. `style-src` does permit `'unsafe-inline'` because Angular injects component styles at runtime; `img-src` includes `https:` for S3-hosted avatars.
- **Referrer-Policy** — `strict-origin-when-cross-origin`, so path parameters never leak to third-party pages,
- **Permissions-Policy** — `camera=(), microphone=(), geolocation=(), payment=()`.

> **CSP is the one control that is deliberately asymmetric.** `ng serve` sends no CSP header at all, so the policy is only ever *enforced* once the SPA is served out of `src/main/resources/static/` by Spring Boot. That asymmetry has already produced one production-only bug — the CDN-hosted icon font and Google Fonts were blocked by `style-src`/`font-src 'self'`, so every `bi-*` icon silently rendered as nothing in production while looking perfect locally. The fix was to self-host rather than widen the policy. If you add a third-party origin, assume it will fail in production only.

**CORS — note there are currently TWO configurations, and they do not agree.**

| Bean | Origins | Reached via |
|---|---|---|
| `SecurityConfig.corsConfigurationSource()` | **Hardcoded**: `http://localhost:4200`, `http://localhost:3000`, `https://angularsecureapp.org` | `http.cors(c -> c.configurationSource(corsConfigurationSource()))` — the Spring Security filter chain |
| `AngularSpringBootFullStackApplication.corsFilter()` | **Config-driven**: `app.cors.allowed-origin-patterns` (env `CORS_ALLOWED_ORIGINS`), which `application-prod.yml` defaults to `${UI_APP_URL}` | A servlet `CorsFilter` bean, auto-registered by Boot |

Both permit `Authorization`/`Jwt-Token` on the way in and **expose** `Authorization`/`Jwt-Token`/`File-Name` on the way out so the SPA can read rotated tokens and download filenames; both allow credentials.

The security chain's source is the one that answers preflights, so **the hardcoded list is effectively authoritative** and `angularsecureapp.org` is a stale origin still shipping in the jar. This is not currently breaking anything — the deployed shape serves the SPA and the API from **one origin**, so those calls are same-origin and never trigger a CORS check at all, which is exactly why the divergence has gone unnoticed. It becomes a real bug the moment anything genuinely cross-origin is added (a second frontend, a mobile client, a split-origin staging deploy).

> ⬜ **Backlog item:** delete the hardcoded list and have `SecurityConfig` read `app.cors.allowed-origin-patterns` like the filter does, so there is one source of truth. Deliberately *not* fixed in passing — CORS changes are the kind that look inert in a single-origin deployment and then fail only for the one client you forgot about.

**Reverse-proxy awareness.** `server.forward-headers-strategy` (env `FORWARD_HEADERS_STRATEGY`, default `none`, set to `framework` in every deployment path) controls whether Spring reconstructs the public scheme/host/port from `X-Forwarded-Proto`/`-Host`/`-Port`. It is **off by default on purpose**: trusting those headers when nothing strips them lets any caller claim any origin. It is required in AWS because `OAuth2ClientConfig` registers every provider with `{baseUrl}/login/oauth2/code/{registrationId}` — see §11.1.

---

### 11.1 Deployment parity — what changes between `start.sh` and AWS

Most of this guide describes behaviour that is identical everywhere. The table below is the exception list: **controls whose effectiveness depends on configuration that differs by environment.** Everything marked ⚠️ is a control that can appear to work locally while being degraded or absent when deployed.

| Control | What it actually depends on | Local (`start.sh`) | AWS (ECS, `prod`) |
|---|---|---|---|
| ⚠️ **Anomaly detection / step-up (FR-TPF-1)** | The real client IP, via `app.security.trusted-proxy-count` | `0` — correct, no proxy | Must be `1` behind an ALB. **At `0` every request appears to come from the load balancer**, so `NEW_NETWORK` can never fire and the control is silently dead |
| ⚠️ **Rate limiting (`RateLimitFilter`)** | Same client-IP resolution | Per-caller buckets | At `TRUSTED_PROXY_COUNT=0`, every user collapses into **one** bucket — the whole tenant throttles as a single caller |
| ⚠️ **Federated login redirect** | `{baseUrl}`, resolved from forwarded headers | Correct without config | Needs `FORWARD_HEADERS_STRATEGY=framework`, else `redirect_uri` becomes `http://<task-ip>:8080/...` and every provider answers `redirect_uri_mismatch` |
| ⚠️ **Which providers appear** | Presence of each `*_CLIENT_ID` | Whatever `.env` has | Whatever the task definition injects — a provider present in one and not the other is exactly why Microsoft showed locally and not when deployed |
| ⚠️ **Email (verification, step-up codes, security alerts)** | `MAIL_USERNAME` / `MAIL_PASSWORD` | `.env` | Secrets Manager — the bootstrap scripts seed these as `CHANGE_ME`, and step-up **withholds tokens until the emailed code is entered**, so unset mail credentials lock out any account the risk engine flags |
| ⚠️ **Profile images** | `IMAGE_STORAGE_TYPE` | `local` filesystem | `s3` — needs the task role to hold `s3:PutObject`/`s3:GetObject` on the bucket |
| ⚠️ **Schema + seed data** | `schema.sql`, applied **by hand** (`sql.init.mode: never`) | `db2` | `db3`. Tables can exist without seed rows, which is how the services catalogue ends up empty while the app boots fine |
| ⚠️ **JPA schema drift** | `ddl-auto` | `update` — silently fixes drift | `validate` — **fails fast at startup**. Never yet exercised end-to-end (see §13) |
| ✅ HSTS | TLS termination | Inert (plain HTTP) | Active at the ALB |
| ✅ CSP / Referrer / Permissions | Served by Spring, not `ng serve` | Not enforced | Enforced |
| ✅ Error-detail scrubbing | `app.error.expose-details` | `true` | `false` — `devMessage`/raw `reason` suppressed |
| ✅ Refresh rotation, reuse detection, TOTP, RBAC, org scoping | Database state only | Identical | Identical — these are DB-backed and survive multi-instance |
| ✅ Actuator exposure | `management.endpoints.web.exposure` | `health, info`, `show-details: never` | Same — no new surface when public |
| ➖ SMS 2FA | `TWILIO_*` | Stubbed | Stubbed — identical, and documented in §13 |

**Post-deploy smoke test**, in the order that isolates faults fastest:

1. `GET /actuator/health` → `{"status":"UP"}`. Anything else means the app never booted; check `ddl-auto: validate` failures in the CloudWatch log first.
2. Sign in with a password account. Confirms `JWT_SECRET`, the datasource, and `db3` seed data.
3. Load the login screen and count provider buttons — that number *is* `/oauth2/providers`, and it tells you which `*_CLIENT_ID`s actually reached the container.
4. Complete one federated sign-in. This is the single best test of `FORWARD_HEADERS_STRATEGY`; a `redirect_uri_mismatch` means it did not take effect.
5. Register a throwaway account and click the emailed link. Confirms mail credentials, the HTML template, and that the link lands on `/verify/account/:key` rather than raw JSON.
6. Navigate directly to a protected URL while signed out → styled 401 page, not JSON.
7. Check the boot log for `[NET] trusted-proxy-count=` and confirm it is not `0`.

---

## 12. Public endpoints

Two lists must stay in lockstep (`Constants.java`):
- **`PUBLIC_URLS`** — `permitAll` matchers in the SecurityFilterChain.
- **`PUBLIC_ROUTES`** — prefixes `CustomAuthFilter.shouldNotFilter()` skips.

> **Gotcha:** if a route is permitted by the filter chain but *not* skipped by the filter, a stale `Authorization: Bearer` header from the client would make the filter try (and fail) to parse a token before the request reaches the public controller. Keep both lists aligned.

Public surface: registration, login, SMS/TOTP login completion, account/password verification, password reset, token refresh, profile images, OAuth2 routes, and Actuator.

---

## 13. Known limitations & remaining work

In the interest of honesty (and as a to-do list). Status legend: ⚠️ = built-but-not-production-wired · ❌ = open/planned.

**Third-party gaps (see §1 for the full utilization table):**
- ⚠️ **SMS 2FA is stubbed** — the Twilio send is commented out in `NotificationServiceImpl`; codes are logged, not delivered. Restoring it = set the three `TWILIO_*` vars + uncomment one call. TOTP is the real, complete second factor. **Decision still open:** wire it live or formally descope.
- ⚠️ **Federated login is inactive without credentials** — no provider buttons appear until `*_CLIENT_ID`/`*_CLIENT_SECRET` values are set (by design; env-gated).

**Closed since this section was written:**
- ✅ **Redundant JWT library removed** — `jjwt` is gone; the code uses `com.auth0:java-jwt` exclusively.
- ✅ **Federated provider name on the audit row (FR-FED-5)** — `userevents.detail` carries the provider on `FEDERATED_LOGIN`.
- ✅ **Risk-based / step-up auth (FR-TPF-1)** — an unrecognised device or network escalates to step-up verification rather than being waved through; single-factor accounts get an emailed code and no tokens until it is entered.
- ✅ **Login-analytics dashboard (FR-TPF-2)** — `/admin/security/overview`, org-scoped, is the review surface for the above.
- ✅ **Profile image storage** — `ImageStorageService` abstraction with local and S3 implementations; no hardcoded developer path.
- ✅ **General request rate limiting** — `RateLimitFilter` (Bucket4j) returns 429 with `Retry-After`; per-account brute-force lockout is now persistent with administrative unlock.
- ✅ **Federated account linking is a distinct flow.** "Connect a provider" no longer runs an ordinary login. The SPA mints a single-use, five-minute link ticket over an authenticated call; the browser carries it to `GET /oauth2/link/{provider}`, which validates it and records the intent in the session the OAuth handshake already uses; the callback then attaches `(provider, subject)` to **that** account and issues no tokens, so the caller's session simply continues. Refuses when the identity already belongs to another account — without which linking would be an account-takeover primitive, since links are keyed on the provider subject rather than on the verified email. Both directions are audited (`PROVIDER_LINKED` / `PROVIDER_UNLINKED`).
- ✅ **Security-critical-path tests** — refresh rotation *and* replay detection, TOTP challenge binding, and organization scope (reads as well as writes) are covered. The frontend has specs too (see [testing.md](testing.md)).

**Operational / hardening gaps:**
- ❌ **Prod secrets** (JWT secret, OAuth client secrets, mail creds) must be supplied by the platform, not `.env`.
- ❌ **Brute-force counting is audit-event based** (per-email sliding window) rather than a distributed counter — correct for a single instance, but two instances count separately.
- ❌ **A real production boot with `ddl-auto: validate`** against a `schema.sql`-only database has never been exercised — only the offline `JpaSchemaSyncTest`.

**Explicitly out of scope this revision:** machine-to-machine (client-credentials) authorization, SCIM provisioning, and SAML federation.

---

## 14. Organization-scoped administration

A second authorization axis layered *on top of* the permission-based RBAC of §5: where authorities answer **"what may you do?"**, organization scope answers **"to whom may you do it?"**. It exists so a tenant administrator (`ROLE_ORGANIZATION_ADMIN`) can run the admin surface for *their own* organization's users without becoming a system-wide admin (SRS §4.6, FR-ORG-1..3).

> **Key source files:** `controller/AdminUserController.java` · `service/OrganizationService.java` · `service/serviceimpl/OrganizationServiceImpl.java` · `query/OrganizationQuery.java` · `src/main/resources/schema.sql` (the `organizations` / `userorganizations` tables).
> **See also:** [`flows/20-admin-users-rbac.md`](flows/20-admin-users-rbac.md) (the click-to-DB trace, including the `requireOrganizationScope` gate) · [database.md §8](database.md#8-organizations) (the two membership tables) · [database.md §12](database.md#12-reference-data) (role catalogue).

This builds directly on the admin surface documented in [`flows/20-admin-users-rbac.md`](flows/20-admin-users-rbac.md): `AdminUserController` is the **only** place one user mutates another's role or account state, and scope is the fifth and innermost of its authorization layers — after frontend `adminGuard`, URL-level matcher, method-level `@PreAuthorize`, and the `requireNotSelf` guard.

### How scope is decided

Two independent pieces:

1. **Is the *caller* scoped at all?** `isOrganizationScoped(caller)` returns true **only** when the caller's role name is exactly `ROLE_ORGANIZATION_ADMIN` (`AdminUserController.java:292-294`). `ROLE_ADMIN` and `ROLE_APPLICATION_ADMIN` are never scoped — they act globally (FR-ORG-3). The role name is read off the JWT principal, so no DB hit is needed to make the decision.
2. **Is the *target* in scope?** `organizationService.isWithinOrganizationScope(adminId, targetId)` (`OrganizationService.java:29`, impl `OrganizationServiceImpl.java:46-56`) runs a single COUNT over a **self-join of `userorganizations`**: the two users are in scope of each other when both hold an `active = TRUE` membership in **at least one common organization** (`OrganizationQuery.java:21-24`). The organization's own `status` is *not* consulted — deactivating a membership row is the operational lever; retiring an org flips its member rows inactive.

For `ROLE_ORGANIZATION_ADMIN` to even reach this controller it must clear matcher #6 (`/admin/**` → `UPDATE:USER` **or** `UPDATE:ROLE`, see §5); its seeded permission string is `READ:USER, READ:CUSTOMER, UPDATE:USER, UPDATE:ROLE` (`schema.sql:68`), so it passes the authority gate and scope then narrows *which rows* it may touch.

### Where scope is enforced (per endpoint)

Every `AdminUserController` endpoint consults the service — list operations branch to a scoped query; single-target operations call a fail-closed guard.

| Endpoint | Scope mechanism | Out-of-scope result |
|---|---|---|
| `GET /admin/user/list` | branch: scoped path calls `countUsersSharingOrganizations` + `searchUsersSharingOrganizations` instead of the unscoped `UserService` methods (`AdminUserController.java:110-116`) | rows silently filtered out — the directory simply shrinks (the caller themselves appears; they share their own orgs) |
| `GET /admin/user/{id}` | `requireOrganizationScope(authentication, id)` first line (`:147`) | **403** via `AccessDeniedException` |
| `GET /admin/user/{id}/events` | `requireOrganizationScope` re-checked on every page turn (`:184`) — a scope reduction mid-session is enforced | **403** |
| `PATCH /admin/user/{id}/role/{roleName}` | `requireNotSelf` then `requireOrganizationScope` (`:215-216`) | **403** |
| `PATCH /admin/user/{id}/settings` | `requireNotSelf` then `requireOrganizationScope` (`:249-250`) | **403** |

`requireOrganizationScope` (`AdminUserController.java:306-312`) throws `AccessDeniedException("This user is outside your organization scope.")`, which `GlobalExceptionHandler` maps to the standard `HttpResponse` 403 envelope. The message names **no account data**, so it cannot be used to probe which user ids exist (NFR-SEC-7 — the same enumeration-safety rule as §2).

### The scope SQL

All three constants live in `OrganizationQuery` and run through `NamedParameterJdbcTemplate` from `OrganizationServiceImpl` (the service owns its SQL — there is no repo layer here).

| Purpose | Query constant | SQL shape |
|---|---|---|
| Scope predicate (single target) | `COUNT_SHARED_ACTIVE_ORGANIZATIONS_QUERY` | `SELECT COUNT(*) FROM userorganizations a JOIN userorganizations b ON a.organization_id = b.organization_id WHERE a.user_id = :adminId AND b.user_id = :targetId AND a.active = TRUE AND b.active = TRUE` |
| Scoped directory page | `SELECT_USERS_SHARING_ORGANIZATIONS_PAGED_QUERY` | `SELECT DISTINCT u.* FROM users u JOIN userorganizations b ON b.user_id = u.id AND b.active = TRUE JOIN userorganizations a ON a.organization_id = b.organization_id AND a.user_id = :adminId AND a.active = TRUE WHERE (u.first_name LIKE :searchTerm OR …) ORDER BY u.created_at DESC, u.id DESC LIMIT :pageSize OFFSET :offset` |
| Scoped directory count | `COUNT_USERS_SHARING_ORGANIZATIONS_QUERY` | the `COUNT(DISTINCT u.id)` twin of the page query, filter-compatible |

The `LIKE` term is normalized identically to the unscoped path (`toLikePattern` wraps a trimmed term in `%…%`, blank ⇒ match-everything, `OrganizationServiceImpl.java:102-104`), and `pageSize` is clamped to `[1, 100]` (`:66`), so the scoped directory is indistinguishable in shape from the system-wide one — just smaller.

### Two deliberate design choices

- **Fail-closed scope check.** If the COUNT query throws, `isWithinOrganizationScope` logs and returns `false` (`OrganizationServiceImpl.java:51-55`) — an error in the scope check **denies**, never grants. A scoped admin whose DB lookup fails sees a 403, not the user.
- **Scope is keyed to the role *name*, not a capability.** `isOrganizationScoped` matches the literal `ROLE_ORGANIZATION_ADMIN`. This is simple and unambiguous, but it means scoping is a property of *that one role*, not of "any non-global admin" — see the gap register below.

### What is enforced today vs partial

Status legend: ✅ enforced · ⚠️ partial / by-convention · ❌ not built.

| Concern | Status | Detail |
|---|:--:|---|
| Org admin sees only in-scope users | ✅ | list/count branch to the scoped self-join (`AdminUserController.java:110-116`) |
| Org admin can read/mutate only in-scope users | ✅ | `requireOrganizationScope` on `GET /{id}`, `/{id}/events`, `PATCH …/role`, `PATCH …/settings`; out-of-scope ⇒ 403 |
| Global admin tiers bypass scope | ✅ | `ROLE_ADMIN` / `ROLE_APPLICATION_ADMIN` are never `isOrganizationScoped` (FR-ORG-3) |
| Fail-closed on scope-check error | ✅ | `isWithinOrganizationScope` returns `false` on exception |
| Enumeration-safe denial | ✅ | 403 message names no account data (NFR-SEC-7) |
| Scope applies to **business** data (customers/invoices) | ❌ | scope covers **user administration only**. `/customer/**` is system-wide — those GETs only need `READ:USER`/`READ:CUSTOMER` (`SecurityConfig.java:160`), so an org admin sees *all* customers/invoices, just not all *users*. The Billing/Analytics "Your Organization" scope badge is cosmetic. |
| Other UPDATE:USER holders are scoped | ⚠️ | scope is keyed to the exact name `ROLE_ORGANIZATION_ADMIN`. `ROLE_HELP_DESK_ADMIN` *also* carries `UPDATE:USER` (`schema.sql:67`) and reaches `/admin/**`, but is **not** scoped — it sees the whole directory and can change any user's settings. Intentional today, but a footgun if more roles gain `UPDATE:USER`. |
| Role-tier ceiling on reassignment | ❌ | `updateUserRole` (`AdminUserController.java:210-231`) applies no ceiling. An org admin holds `UPDATE:ROLE`, so they can promote an **in-scope** user to `ROLE_ADMIN`/`ROLE_APPLICATION_ADMIN` — scope bounds *who*, not *which role*. Privilege-elevation-by-proxy; close it by rejecting target roles above the caller's tier. |
| Membership management API | ❌ | `organizations` + `userorganizations` are seeded/maintained in the DB directly (`schema.sql:239-264` seeds two orgs); there is no endpoint to create an org or assign/deactivate a membership. `OrganizationService` exposes only the scope check + the scoped directory. |

> **Gotcha (code-wins):** `OrganizationQuery`/`OrganizationServiceImpl` javadoc still references *"Flyway V4"* and an *"OrganizationRepoImpl"*. Both are stale — Flyway was removed (the tables are owned by `schema.sql`, §12) and the SQL is consumed straight from `OrganizationServiceImpl`, there is no repo. If a comment and the code disagree, **the code wins**; the comment should be fixed.

### Adding a feature that respects org boundaries

When you add an endpoint that acts on another user (or any org-owned entity), wire it into the same two-piece check rather than re-inventing it:

1. **Inject `OrganizationService`** into your controller (constructor injection via `@RequiredArgsConstructor`), exactly as `AdminUserController` does.
2. **Single-target action** → call `requireOrganizationScope(authentication, targetId)` as the *first* line, before any read or mutation. The helper already no-ops for global admins and throws the enumeration-safe `AccessDeniedException` (⇒ 403) for an out-of-scope target. Lift the private helper into a shared component if a second controller needs it.
3. **List/collection action** → branch on `isOrganizationScoped(caller)`: scoped callers use a new scoped query, global callers use the unscoped one (mirror `listUsers`, `AdminUserController.java:110-116`). Do **not** fetch-then-filter in Java — push the membership join into SQL so paging counts stay correct.
4. **New SQL** → add a constant to `OrganizationQuery` following the `userorganizations a JOIN userorganizations b … a.active = TRUE AND b.active = TRUE` self-join, with named params, and run it from a service via `NamedParameterJdbcTemplate`.
5. **Keep the invariants:** global admin tiers bypass (never scope `ROLE_ADMIN`/`ROLE_APPLICATION_ADMIN`); fail closed on any scope-check error; audit the action against the **target** user (`new NewUserEvent(target.getEmail(), …)`), not the caller; and never leak account existence in the denial message.

If the new resource is org-*owned* (not user-keyed), give it an `organization_id` and join through `userorganizations` on the caller's active memberships — the same predicate, one hop further.

### Cross-links

- The full administrative request trace, with the scope gate drawn into the authorization flowchart → [`flows/20-admin-users-rbac.md`](flows/20-admin-users-rbac.md)
- The `organizations` / `userorganizations` table columns and the ERD → [database.md §8](database.md#8-organizations)
- The role catalogue and which tiers hold `UPDATE:ROLE` → [database.md §12](database.md#12-reference-data)
- The permission-to-authority matchers this layers on top of → [§5](#5-authorization-rbac)
