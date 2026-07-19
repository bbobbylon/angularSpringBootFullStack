# API Reference

Every REST endpoint, grouped by controller, with method, path, authorization, request body, and response shape.

> **Base URL:** `http://localhost:8080` (local) · **Auth:** `Authorization: Bearer <access_token>` · **Envelope:** every response is an `HttpResponse` (below).
> **See also:** [security.md](security.md) (how auth/authority works) · [database.md](database.md) (data shapes).

---

## Conventions

### Response envelope
Every endpoint (success or error) returns the same JSON shape:

```json
{
  "timeStamp": "12:01:33.123",
  "statusCode": 200,
  "status": "OK",
  "message": "Human-readable summary",
  "data": { "...": "endpoint-specific payload" }
}
```
Errors add a `reason` field and use the matching 4xx/5xx `statusCode`.

### Authorization
- **Public** — no token needed.
- **Authenticated** — any valid access token.
- **Authority** — a valid token whose role grants the named permission (e.g. `UPDATE:ROLE`). See the [authority matrix](#authority-matrix) and [security.md §5](security.md#5-authorization-rbac).

### Pagination
List endpoints take `?page=` (0-based) and `?size=` and return the page plus `…TotalElements` / `…TotalPages` counts.

---

## Authentication & account — `UserController` (`/user`)

| Method | Path | Auth | Body | Returns (`data`) |
|--------|------|------|------|------------------|
| POST | `/user/register` | Public | `User { firstName, lastName, email, password }` | `201` `{ user }` |
| POST | `/user/login` | Public | `LoginForm { email, password }` | `{ user, access_token, refresh_token }` — **or** `{ user, challenge }` (TOTP) — **or** `{ user }` + SMS sent (SMS 2FA) |
| GET | `/user/verify/code/{email}/{code}` | Public | — | `{ user, access_token, refresh_token }` (completes SMS 2FA login) |
| GET | `/user/verify/account/{key}` | Public | — | `{ message }` (activates account) |
| GET | `/user/refresh/token` | Refresh token (Bearer) | — | `{ user, access_token, refresh_token }` (rotated); `400` if header missing |
| GET | `/user/resetpassword/{email}` | Public | — | `{ message }` (emails a reset link) |
| GET | `/user/verify/password/{key}` | Public | — | `{ user }` (resolves a reset link) |
| PUT | `/user/new/password` | Public | `NewPasswordForm { userID, newPassword, confirmPassword }` | `{ message }` |
| GET | `/user/image/{fileName}` | Public | — | raw PNG bytes (`image/png`) |
| GET | `/user/profile` | Authenticated | — | `{ user, events, eventsTotalElements, eventsTotalPages, roles }` |
| GET | `/user/events?page&size` | Authenticated | — | `{ events, eventsTotalElements, eventsTotalPages }` |
| PATCH | `/user/update` | `UPDATE:*` | `UpdateForm { id, firstName, lastName, email, address, phone, title, bio }` | `{ user, events, roles }` |
| PATCH | `/user/update/password` | `UPDATE:*` | `UpdatePasswordForm { currentPassword, newPassword, confirmPassword }` | `{ user, roles, events, access_token, refresh_token }` (revokes other sessions) |
| PATCH | `/user/update/settings` | `UPDATE:*` | `SettingsForm { enabled, notLocked }` | `{ user, events, roles }` |
| PATCH | `/user/update/togglemfa` | `UPDATE:*` | — | `{ user, events, roles }` (toggles SMS 2FA; requires a phone) |
| PATCH | `/user/update/image` | `UPDATE:*` | multipart `image` | `{ user, events, roles }` |

> `PATCH`/`POST` under `/user/**` fall through to the broad verb rules (`UPDATE:USER` or `UPDATE:CUSTOMER`), so self-service profile edits require an update authority. The removed `PATCH /user/update/role/{roleName}` is **gone** — role changes are admin-only (see below).

---

## Multi-factor (TOTP) — `TotpController` (`/user`)

| Method | Path | Auth | Body | Returns (`data`) |
|--------|------|------|------|------------------|
| POST | `/user/totp/setup` | Authenticated | — | `{ secret, otpauthUri, qrCode }` (begins enrollment) |
| POST | `/user/totp/enable` | Authenticated | `TotpCodeForm { code }` | `{ user, recoveryCodes }` (confirms; codes shown once) |
| POST | `/user/totp/disable` | Authenticated | `TotpCodeForm { code }` | `{ user }` (needs a live TOTP/recovery code) |
| GET | `/user/totp/status` | Authenticated | — | `{ enabled, recoveryCodesRemaining }` |
| POST | `/user/verify/totp` | Public | `TotpVerifyForm { challenge, code }` | `{ user, access_token, refresh_token }` (completes TOTP login) |

The `/user/totp/**` routes are explicitly `authenticated()` (no staff authority) so any user can secure their own account. `verify/totp` is public because the caller is mid-login; the server-side `challenge` is its security boundary. See [security.md §8](security.md#8-multi-factor-authentication).

---

## Sessions & devices — `SessionController` (`/user/sessions`)

| Method | Path | Auth | Returns (`data`) |
|--------|------|------|------------------|
| GET | `/user/sessions` | Authenticated | `{ sessions, currentFamily }` |
| DELETE | `/user/sessions/{family}` | Authenticated | `{ sessions, currentFamily }` (revoke one) |
| DELETE | `/user/sessions` | Authenticated | `{ sessions, currentFamily }` ("log out everywhere else") |

`currentFamily` is the `sid` of the caller's own session, so the SPA can badge "this device" and exclude it from mass logout. See [security.md §7](security.md#7-refresh-session-rotation--reuse-detection).

---

## Federated login — `FederatedAuthController` (`/oauth2`)

| Method | Path | Auth | Returns |
|--------|------|------|---------|
| GET | `/oauth2/providers` | Public | the OAuth2 providers that are configured (for the SPA's login buttons) |

The actual OAuth2 dance is handled by Spring Security, not this controller:
- `GET /oauth2/authorization/{provider}` — start the Authorization Code flow (`provider` = `google` \| `github` \| `microsoft`)
- `GET /login/oauth2/code/{provider}` — provider callback; on success `OAuth2LoginSuccessHandler` issues the app's own JWTs and redirects to the SPA `/oauth2/callback`.

Inactive until provider credentials are set. See [security.md §9](security.md#9-federated-login-oauth2--oidc).

---

## Administration — `AdminUserController` (`/admin/user`)

All routes require `UPDATE:USER` **or** `UPDATE:ROLE`; the two `PATCH`es are stricter. For a `ROLE_ORGANIZATION_ADMIN` the directory and every action are **scoped to shared organizations** (out-of-scope → `403`); `ROLE_ADMIN`/`ROLE_APPLICATION_ADMIN` are unscoped.

| Method | Path | Auth | Body | Returns (`data`) |
|--------|------|------|------|------------------|
| GET | `/admin/user/list?page&size&searchTerm` | `UPDATE:USER`/`UPDATE:ROLE` | — | `{ user, users, usersTotalElements, usersTotalPages, page, pageSize, roles }` |
| GET | `/admin/user/{id}` | `UPDATE:USER`/`UPDATE:ROLE` | — | `{ user, selectedUser, events, eventsTotalElements, eventsTotalPages, roles }` |
| GET | `/admin/user/{id}/events?page&size` | `UPDATE:USER`/`UPDATE:ROLE` | — | `{ events, eventsTotalElements, eventsTotalPages }` |
| PATCH | `/admin/user/{id}/role/{roleName}` | **`UPDATE:ROLE`** | — | `{ user, selectedUser, roles }` (forbids self-targeting) |
| PATCH | `/admin/user/{id}/settings` | **`UPDATE:USER`** | `SettingsForm { enabled, notLocked }` | `{ user, selectedUser, roles }` (forbids self-targeting) |

> `user` = the calling admin (for the navbar); `selectedUser` = the managed user. Mutations are audited against the **target** user.

---

## Customers & invoices — `CustomerController` (`/customer`)

All require authentication. `GET` needs `READ:USER`/`READ:CUSTOMER`; `POST` needs `UPDATE:USER`/`UPDATE:CUSTOMER`; `PUT` needs an `UPDATE:*` authority.

| Method | Path | Auth | Body | Returns (`data`) |
|--------|------|------|------|------------------|
| GET | `/customer/stats` | `READ:*` | — | `{ user, stats }` |
| GET | `/customer/list?page&size` | `READ:*` | — | `{ user, page, stats }` (size default 20) |
| GET | `/customer/get/{customerId}` | `READ:*` | — | `{ user, customers }` |
| GET | `/customer/search?name&page&size` | `READ:*` | — | `{ user, page }` |
| POST | `/customer/create` | `UPDATE:*` | `Customer` | `201` `{ user, customer }` |
| PUT | `/customer/update/{customerId}` | `UPDATE:*` | `Customer` | `{ user, customers }` |
| GET | `/customer/download/report` | `READ:*` | — | XLSX attachment |
| POST | `/customer/invoice/create` | `UPDATE:*` | `Invoice` | `201` `{ user, invoice }` (standalone) |
| GET | `/customer/invoice/list?page&size` | `READ:*` | — | `{ user, invoices }` |
| GET | `/customer/invoice/new` | `READ:*` | — | `{ user, customers, availableServices }` |
| GET | `/customer/invoice/get/{invoiceId}` | `READ:*` | — | `{ user, invoice, customer }` |
| POST | `/customer/invoice/addtocustomer/{customerId}` | `UPDATE:*` | `Invoice` | `{ user, customers }` |
| GET | `/customer/invoice/download/report` | `READ:*` | — | XLSX attachment |

---

## Authority matrix

| Verb / path pattern | Required authority |
|---------------------|--------------------|
| `POST /user/register`, `POST /user/login`, `/actuator/**`, public URLs | none |
| `/user/totp/**`, `/user/sessions/**` | authenticated |
| `DELETE /user/delete/**` | `DELETE:USER` |
| `DELETE /customer/delete/**` | `DELETE:CUSTOMER` |
| `PATCH /admin/user/*/role/**` | `UPDATE:ROLE` |
| `PATCH /admin/user/*/settings` | `UPDATE:USER` |
| `/admin/**` | `UPDATE:USER` or `UPDATE:ROLE` |
| `GET /**` | `READ:USER` or `READ:CUSTOMER` |
| `POST /**` | `UPDATE:USER` or `UPDATE:CUSTOMER` |
| `PUT /**` | `UPDATE:USER`, `UPDATE:CUSTOMER`, or `UPDATE:ROLE` |

Which roles hold which permissions: [database.md §12](database.md#12-reference-data).

---

## Examples

```bash
# Login
curl -X POST http://localhost:8080/user/login \
  -H "Content-Type: application/json" \
  -d '{"email":"eve.admin@tessera.dev","password":"TesseraDemo@1"}'

# Call a protected endpoint
curl http://localhost:8080/user/profile \
  -H "Authorization: Bearer <access_token>"

# Rotate tokens
curl http://localhost:8080/user/refresh/token \
  -H "Authorization: Bearer <refresh_token>"

# Admin: reassign a role (needs UPDATE:ROLE)
curl -X PATCH http://localhost:8080/admin/user/23/role/ROLE_MODERATOR \
  -H "Authorization: Bearer <access_token>"
```

---

## Error responses

Every failure — a `@Valid` rejection, a wrong password, a missing token, an unhandled crash — comes
back in the **same `HttpResponse` envelope** as a success, just with a 4xx/5xx `statusCode` and a
populated `reason`. The SPA never parses HTTP status text or stack traces; it reads one field
(`reason`) and shows it. This section is the catalog of where each status comes from, what `reason`
the client actually sees, and how it should branch.

> **Audience:** anyone wiring a new client, debugging why an error toast says what it says, or
> tightening the handlers for production.
> **Code wins.** Where this catalog and the handlers ever disagree, the handler source is
> authoritative and this doc should be fixed. Every row below cites its `file:line`.
> **Enumeration-safe standing rule:** an unknown email and a wrong password MUST be
> indistinguishable — same status, same body. See the [login-failure message](#anti-enumeration-login-failure) below.

### The error envelope

Errors reuse the [success envelope](#response-envelope) and add two fields:

```jsonc
{
  "timeStamp": "12:01:33.123",
  "statusCode": 400,                  // mirrors the HTTP status
  "status": "400 BAD_REQUEST",        // HttpStatus.toString() — code + name
  "reason": "Invalid email or password.",   // ← the ONLY field the SPA shows the user
  "devMessage": "Bad credentials"     // raw cause, for logs/non-prod ONLY — never render this
}
```

- `reason` is client-safe and is what `user.service.ts` / `customer.service.ts` surface
  (`error.error.reason`, `user.service.ts:425-426`).
- `devMessage` carries the raw exception text for diagnosis. It is **not** scrubbed today — there is
  a standing TODO to stop returning it (and `reason` cleanup) in production so PII / internals never
  leak (`exception/HandleException.java:31`). `GlobalExceptionHandler` is already stricter: its
  generic 500 puts only a safe string in `reason` and the raw cause in `devMessage` only
  (`exception/GlobalExceptionHandler.java:101-112`).
- `data` and `message` are simply omitted on errors — `@JsonInclude(NON_DEFAULT)` drops empty fields
  (`model/HttpResponse.java:32`).

> **Two handlers, one shape.** There are **two** `@RestControllerAdvice` classes —
> `exception/GlobalExceptionHandler.java` and `exception/HandleException.java` — and they overlap
> (both map `MethodArgumentNotValidException`, `ApiException`, `AccessDeniedException`, and the
> catch-all `Exception`). They produce the same *shape* but **different `reason` strings** for the
> same exception, and which advice resolves first is order-dependent (neither sets `@Order`). The
> "reason the client sees" column lists both wordings where they diverge. **This is a known wart** —
> the two advices should be consolidated. (Spring-level handlers like the entry point / access-denied
> handler below fire *before* dispatch and are unaffected.)

---

### Status code by mechanism

| HTTP | When it fires | Produced by (`file:line`) | `reason` the client sees |
|------|---------------|---------------------------|--------------------------|
| `400` | `@Valid` request-body constraint fails | `GlobalExceptionHandler.handleValidationException` (`GlobalExceptionHandler.java:53`) · `HandleException.handleMethodArgumentNotValid` (`HandleException.java:55`) | `"field: message, …"` (GEH, one entry per failed constraint) **or** the bare joined messages (HE) |
| `400` | `@RequestParam`/`@PathVariable` constraint fails on a `@Validated` controller | `GlobalExceptionHandler.handleConstraintViolation` (`GlobalExceptionHandler.java:69`) | `"path: message, …"` |
| `400` | Malformed / missing JSON body | `GlobalExceptionHandler.handleUnreadable` (`GlobalExceptionHandler.java:85`) | `"The request body is missing or malformed."` |
| `400` | A business rule rejected the request | `ApiException` → both advices (`GlobalExceptionHandler.java:132` · `HandleException.java:160`) | the exception's own message (e.g. `"Invalid email or password."`) |
| `400` | Wrong email/password **or** an undecodable bearer token | `HandleException.badCredentialsException` (`HandleException.java:128`) | `"<msg>, Incorrect email or password"` **or** `"The input is not a valid base 64 encoded string."` |
| `400` | Login on a disabled (e.g. unverified) account | `HandleException.disabledException` (`HandleException.java:267`) | `"User account is currently disabled"` |
| `400` | Login on a locked account | `HandleException.lockedException` (`HandleException.java:288`) | `"User account is currently locked"` |
| `400` | `queryForObject` expected one row, found none | `HandleException.emptyResultDataAccessException` (`HandleException.java:247`) | `"Record not found"` |
| `400` | Unique-key / duplicate insert | `HandleException.sQLIntegrityConstraintViolationException` (`HandleException.java:103`) · `dataAccessException` (`HandleException.java:310`) | `"Duplicate entry"` · `"You already verified your account."` · `"We already sent you an email to reset your password."` · `"Duplicate entry. Please try again."` |
| `400` | `GET /user/refresh/token` called with no / non-`Bearer` header | inline in `UserController.sendNewRefreshToken` (`UserController.java:397`) | `"Invalid or missing token. Please try again."` |
| `401` | No / invalid token reaching a protected route (filter chain, **before** the controller) | `CustomAuthenticationEntryPoint.commence` (`handler/CustomAuthenticationEntryPoint.java:53`) | `"I don't think you are logged in :(  Please login to access this resource!"` |
| `403` | Authenticated but missing the authority for the URL (filter chain) | `CustomAccessDeniedHandler.handle` (`handler/CustomAccessDeniedHandler.java:51`) | `"You don't have enough permission to access this resource!"` |
| `403` | Denied **inside** a controller — `@PreAuthorize`, or the org-scope check in `AdminUserController` | `AccessDeniedException` advice (`HandleException.java:180` · `GlobalExceptionHandler.java:159`) | `"Access denied. You don't have access"` **or** `"You do not have permission to perform this action."` |
| `500` | JWT library can't parse a token | `HandleException.exception(JWTDecodeException)` (`HandleException.java:227`) | `"Could not decode the token"` |
| `500` | Anything otherwise unhandled | `HandleException.exception(Exception)` (`HandleException.java:203`) · `GlobalExceptionHandler.handleGeneric` (`GlobalExceptionHandler.java:101`) | raw message, or `"Record not found"` (when it contains `"expected 1, actual 0"`), or `"Some error occurred"` (HE) — vs. the safe `"An unexpected error occurred. Please try again."` (GEH) |

> **Gotcha — there is no `404` and no `409`.** "Not found" and "conflict" are *concepts* here, not
> status codes. A missing row surfaces as **`400` `"Record not found"`** (`HandleException.java:247`,
> `:211`), and a duplicate/unique-key violation surfaces as **`400` `"Duplicate entry"`**
> (`HandleException.java:103`, `:331-344`) — not `404`/`409`. A genuinely unmatched route (no handler)
> still yields a framework `404`, but it is **not** wrapped in the `HttpResponse` envelope. If you are
> writing a client, branch on the `reason`/`statusCode` in the body, not on an HTTP `404`/`409` you
> will not get for these cases. (Returning real `404`/`409` is desirable future work; the code wins
> today.)

---

### Anti-enumeration login failure

The login path collapses every credential outcome into **one** indistinguishable response so the API
can't be used to discover which emails are registered (FR-AUTH-4 / NFR-SEC-7,
`UserController.authenticate` `UserController.java:657-695`).

| Login outcome | HTTP | `reason` |
|---------------|------|----------|
| Unknown email | `400` | `"Invalid email or password."` |
| Known email, wrong password | `400` | `"Invalid email or password."` *(byte-for-byte identical to "unknown email")* |
| ≥ 5 failures in a 15-min sliding window (known account only) | `400` | `"Too many failed login attempts. Please wait 15 minutes before trying again."` |
| Account disabled (e.g. unverified) | `400` | `"User account is currently disabled"` |
| Account locked | `400` | `"User account is currently locked"` |

- The unknown-email vs wrong-password equivalence is enforced by resolving the account through
  `findUserOrNull` (swallows `UsernameNotFoundException` → `null` so a miss can't escape as a leaky
  500) and rethrowing **all** credential failures as the single generic `ApiException`
  (`UserController.java:689-694`).
- The audit trail honours the same rule: `LOGIN_ATTEMPT` / `LOGIN_ATTEMPT_FAILURE` are recorded
  **only for a known account** (`UserController.java:724-728`), so the log itself is not an oracle.
- Brute-force threshold/window are constants (`BRUTE_FORCE_MAX = 5`, `BRUTE_FORCE_WINDOW_MINUTES = 15`;
  `UserController.java:90`, `:92`). This is **per-account** lockout — general/distributed rate
  limiting with `429` + `Retry-After` is not yet implemented.
- Disabled/locked keep their own actionable messages **on purpose**: those are legitimate
  account-state signals, not credential checks, and don't reveal whether a *password* was right.

---

### How a client should branch

The Angular services already implement this; mirror it in any other client.

| Status | Meaning | What the client should do |
|--------|---------|---------------------------|
| `2xx` | Success | Read `data`; the envelope usually embeds the authenticated `user` alongside the payload. |
| `400` | Bad input / business rejection / wrong credentials / not-found / duplicate | Show `error.error.reason` to the user (toast). Do **not** retry automatically. |
| `401` | Token missing / expired / invalid | **Silent refresh-and-retry**, then redirect to `/login` if refresh fails — see below. Never show the raw 401 reason. |
| `403` | Authenticated but not authorized | Treat as a hard "no". The admin pages also gate client-side via `adminGuard`, but the backend is the real boundary — surface `reason` and stop. |
| `500` | Server fault | Show a generic apology (`reason` is already generic from `GlobalExceptionHandler`); log and move on. |

**The `401` path is special — it auto-heals.** `401` rarely reaches application code: the
`tokenInterceptor` intercepts it, performs a **single-flight** refresh against
`GET /user/refresh/token` (rotating the token pair), and **replays the original request** with the new
access token. Concurrent `401`s queue on one in-flight refresh (no thundering herd). Only if the
refresh itself fails does it clear both tokens and let the app fall through to `/login`
(`interceptor/token.interceptor.ts`). Because of this, a correctly-behaving SPA almost never surfaces
a `401` to the user — it surfaces the *post-refresh* result.

**Everything else flows through one normaliser.** Each service's private `handleError` reads
`error.error.reason` when present (else falls back to `"Server returned code: <status>…"`) and rethrows
a plain `Error(reason)`; components catch it in their `DataState` pipe and call
`notification.onError(message)` (`user.service.ts:419-437`). So the chain is always:
`reason` → `Error` → `DataState.ERROR` → toast.

> **See also:** [security.md](security.md) (the filter chain, entry point, and access-denied handler that
> produce `401`/`403`) · [database.md §12](database.md#12-reference-data) (which roles hold which
> authorities, i.e. who gets a `403`) · [`flows/00-anatomy-of-a-request.md`](flows/00-anatomy-of-a-request.md)
> (how a request reaches a handler and where each error short-circuits).
