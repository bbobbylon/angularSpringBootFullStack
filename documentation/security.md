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
12. [Public endpoints](#12-public-endpoints)
13. [Known limitations](#13-known-limitations)

---

## 1. The model at a glance

SecureCapita uses an **in-house, stateless-first, zero-trust** authentication core:

- **Stateless access tokens.** Every API call carries a JWT access token that's verified by signature alone — no database lookup, no server session (`SessionCreationPolicy.STATELESS`).
- **Stateful refresh tokens.** Refresh tokens are tracked in the `refreshsessions` table so they can be **rotated**, **listed** (the Security Center device list), and **revoked** — the one place the system is intentionally stateful.
- **Permission-based authorization.** A user's single role carries a comma-separated permission string (e.g. `READ:USER, UPDATE:CUSTOMER`); each permission becomes a Spring Security authority matched against per-endpoint rules.

This "stateless access + stateful refresh" split is the heart of the design: fast, scalable request handling (no per-request DB hit) **and** revocable sessions.

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
- HSTS — `max-age=31536000; includeSubDomains`.

**CORS** allows the SPA origins (`http://localhost:4200`, `http://localhost:3000`, `https://angularsecureapp.org`), permits the `Authorization`/`Jwt-Token`/content headers, **exposes** `Authorization`/`Jwt-Token`/`File-Name` so the SPA can read rotated tokens and download filenames, and allows credentials.

---

## 12. Public endpoints

Two lists must stay in lockstep (`Constants.java`):
- **`PUBLIC_URLS`** — `permitAll` matchers in the SecurityFilterChain.
- **`PUBLIC_ROUTES`** — prefixes `CustomAuthFilter.shouldNotFilter()` skips.

> **Gotcha:** if a route is permitted by the filter chain but *not* skipped by the filter, a stale `Authorization: Bearer` header from the client would make the filter try (and fail) to parse a token before the request reaches the public controller. Keep both lists aligned.

Public surface: registration, login, SMS/TOTP login completion, account/password verification, password reset, token refresh, profile images, OAuth2 routes, and Actuator.

---

## 13. Known limitations

In the interest of honesty (and as a to-do list):

- **SMS 2FA is stubbed** — the Twilio send is commented out in dev; codes are logged, not delivered. TOTP is the real, complete MFA.
- **Federated login is inactive without credentials** — no provider buttons appear until `*_CLIENT_ID` values are set.
- **Profile image storage is local + hardcoded** to the developer's home directory (`~/Downloads/images/`) — not container/cloud-ready (see the `TODO(dev-only)` in `UserController`).
- **Brute-force counting is audit-event based** (per-email sliding window), not a distributed rate limiter — adequate for a single instance, not for horizontal scale.
- **Tests are sparse** — the security model is not yet covered by an automated suite.
