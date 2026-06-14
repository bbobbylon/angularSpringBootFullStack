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
