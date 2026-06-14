# Developer Guide (In-Depth)

The deep dive for anyone who will *work in* the codebase — not just run it. It traces a request end-to-end, walks the authentication lifecycle, and gives concrete recipes for the changes you'll actually make. The topic guides hold the reference detail; this guide is the connective tissue.

> **Read [architecture.md](architecture.md) first** for the map. Then use this to learn the territory.
> **Cross-refs:** [security.md](security.md) · [database.md](database.md) · [api-reference.md](api-reference.md) · [configuration.md](configuration.md)

---

## Table of contents

1. [Mental model](#1-mental-model)
2. [End-to-end: a request from click to database](#2-end-to-end-a-request-from-click-to-database)
3. [The authentication lifecycle](#3-the-authentication-lifecycle)
4. [The two data paths](#4-the-two-data-paths)
5. [Recipes: how to extend](#5-recipes-how-to-extend)
6. [Conventions & patterns](#6-conventions--patterns)
7. [Gotchas that will bite you](#7-gotchas-that-will-bite-you)
8. [Testing & quality](#8-testing--quality)
9. [Where to look](#9-where-to-look)

---

## 1. Mental model

Hold these five ideas and the codebase makes sense:

1. **Stateless access, stateful refresh.** Access tokens are verified by signature alone (no DB hit); refresh tokens are rows in `refreshsessions` so sessions can be rotated and revoked.
2. **Two persistence idioms.** Identity/auth = `JdbcTemplate` + hand-written SQL; business (customers/invoices) = JPA. `User` is a POJO, not an entity.
3. **One envelope.** Every controller returns `HttpResponse { timeStamp, statusCode, status, message, data }` — successes and errors look identical to the client.
4. **Authorization is permission strings.** A user has one role; its `permission` column becomes Spring authorities matched by `SecurityConfig` rules and `@PreAuthorize`.
5. **Audit is event-driven.** Controllers `publishEvent(new NewUserEvent(...))`; a listener writes the `userevents` row off the request path.

---

## 2. End-to-end: a request from click to database

Trace `GET /customer/list` after the user clicks "Customers":

```
[Angular] CustomersComponent → CustomerService.getCustomers$()  (HttpClient GET /customer/list)
   │
   ├─ cacheInterceptor   — cache hit? return cached response, stop here
   │                        (registered before tokenInterceptor in app.config.ts)
   ├─ tokenInterceptor   — not a public route → clone req with Authorization: Bearer <access_token>
   │                        (on 401 later: refresh once via BehaviorSubject guard, then retry)
   ▼
[HTTP] →  http://localhost:8080/customer/list
   │
   ├─ CorsFilter
   ├─ CustomAuthFilter   — validate JWT (signature, expiry, passwordChangedAt); set Authentication
   ├─ SecurityFilterChain — rule "GET /** → READ:USER or READ:CUSTOMER" (401/403 if it fails)
   ▼
[Spring MVC] CustomerController.getCustomers(@AuthenticationPrincipal UserDTO user, page, size)
   ▼
CustomerService → CustomerRepo (Spring Data JPA) → MySQL (customer + invoice tables)
   ▼
HttpResponse { data: { user, page, stats }, message, status, statusCode }
   │
   ▼
[Angular] CustomerService maps the envelope → component renders (DataState.LOADED)
```

Every protected endpoint follows this spine. The only variation is the authority rule and the controller/service/repo at the end.

---

## 3. The authentication lifecycle

The full arc, with the file that owns each step (details in [security.md](security.md)):

```
register ─ POST /user/register ─ UserService.createUser ─ disabled account + emailed UUID key
verify   ─ GET  /user/verify/account/{key} ─ account becomes enabled
login    ─ POST /user/login ─ authenticate() (brute-force gate + AuthenticationManager + BCrypt)
            ├─ TOTP user  → { challenge }   → POST /user/verify/totp  → tokens
            ├─ SMS user   → code sent       → GET  /user/verify/code  → tokens
            └─ plain      → tokens immediately
tokens   ─ SessionService.issueTokenPair ─ opens a refreshsessions family, returns access+refresh
use      ─ CustomAuthFilter validates the access token on every call (no DB hit)
refresh  ─ GET /user/refresh/token ─ SessionService.rotate ─ rotate jti, sliding 5-day expiry
                                       (replay of an old token → whole family revoked = reuse detection)
manage   ─ GET/DELETE /user/sessions ─ list/revoke devices
secure   ─ /user/totp/* enroll authenticator; password change kills all tokens (passwordChangedAt)
```

**The token-issuance seam.** *Every* path that mints tokens — login, SMS verify, TOTP verify, refresh, password change, federated login — goes through `SessionService` (`issueTokenPair` / `rotate`). That's deliberate: it's the single place a session is opened, so every login shows up in the Security Center and participates in rotation/revocation. If you add a new way to log a user in, **issue tokens through `SessionService`, never `TokenProvider` directly.**

---

## 4. The two data paths

### Identity path (JdbcTemplate)
Adding/altering identity data touches a predictable set of files. Example: how `users` is read.

```
UserRepoImpl  ──uses──▶  UserQuery (SQL constants)
     │                   UserRowMapper (ResultSet → User POJO)
     ▼
NamedParameterJdbcTemplate ──▶ MySQL `users`
```

### Business path (JPA)
```
CustomerRepo (extends a Spring Data interface) ──▶ Hibernate ──▶ MySQL `customer`/`invoice`
Customer / Invoice / Services = @Entity classes; schema via ddl-auto: update
```

Choosing a path for new work: **identity/security/audit → JdbcTemplate** (explicit SQL, lives in `schema.sql`); **business CRUD → JPA**. See [database.md](database.md).

---

## 5. Recipes: how to extend

### 5.1 Add a REST endpoint (identity side)
1. **Query** — add the SQL constant in the relevant `query/*Query.java`.
2. **Repo** — add the method to the `repo` interface + `repoimpl` (use `NamedParameterJdbcTemplate`; map rows with a `rowmapper`).
3. **Service** — add to the `service` interface + `serviceimpl`.
4. **Controller** — add the handler; return `HttpResponse.builder()…`.
5. **Security** — if it's not covered by the existing verb rules, add a matcher in `SecurityConfig` **above** the catch-alls (and `@PreAuthorize` if it's admin).
6. **Audit** — `eventPublisher.publishEvent(new NewUserEvent(email, SOME_EVENT))` if it's security-relevant.
7. **Frontend** — add a method to the Angular service + wire the component.

### 5.2 Add a database table (identity side)
1. Add `CREATE TABLE IF NOT EXISTS …` to `src/main/resources/schema.sql` (snake_case, FK to `users` with `ON DELETE CASCADE` where appropriate).
2. Apply it to your DB: `mysql -u root -p db2 < src/main/resources/schema.sql` (idempotent).
3. Add the model POJO + a `rowmapper`, then the query/repo/service as in 5.1.
> For a *business* table, instead add a JPA `@Entity` and let `ddl-auto: update` create it — and remember the `@Column(name="…")` rule (see gotchas).

### 5.3 Add an audit event type
1. Add the value to `enumeration/EventType.java`.
2. In `schema.sql`: add it to the `CK_Events_Type` `CHECK` list **and** the seed `INSERT` (both — see [database.md §12](database.md#12-reference-data)).
3. Apply the change to existing DBs (the `CHECK` needs rebuilding: `ALTER TABLE events DROP CHECK CK_Events_Type; ALTER TABLE events ADD CONSTRAINT CK_Events_Type CHECK (type IN (…));` then `INSERT … ON DUPLICATE KEY UPDATE`).
4. Publish it where the action happens.

### 5.4 Add or change a role/permission
- Edit the seed `INSERT INTO roles …` in `schema.sql` (and apply it). Permissions are comma-separated `RESOURCE:ACTION`.
- If you introduce a new authority, add the matching `hasAnyAuthority(...)` rule in `SecurityConfig`.
- Keep `enumeration/RoleType.java` in sync if you add a role name.

### 5.5 Add a frontend feature
1. Create the standalone component under `app/features/<area>/`.
2. Add a lazy route in `app.routes.ts` with the right guard (`authenticationGuard`, plus `adminGuard` for staff-only).
3. Add API calls to a service in `app/service/` (the `tokenInterceptor` handles auth automatically — don't attach tokens yourself).
4. Use the `DataState` enum for LOADING/LOADED/ERROR rendering.

---

## 6. Conventions & patterns

| Pattern | Where | Why |
|---------|-------|-----|
| Interface + `…Impl` | services, repos | depend on contracts; mockable seams |
| `HttpResponse` envelope | every controller | uniform client parsing |
| DTO over entity | `UserDTO` returned, never `User` with password | never leak the hash |
| SQL constants in `query/` | identity repos | one place to read/audit SQL |
| Event-driven audit | `publishEvent` → listener | keep logging off the hot path |
| Tokens only via `SessionService` | all login paths | one revocable session seam |
| Guards = UX, backend = boundary | Angular guards vs `SecurityConfig` | defense in depth |
| Frontend never attaches tokens manually | `tokenInterceptor` | one place, with refresh + concurrency guard |

---

## 7. Gotchas that will bite you

1. **Env vars must be loaded** or the app throws `Circular placeholder reference 'CONTAINER_PORT'` — `application-dev.yml` uses self-referential defaults. Use `start.sh` or load `.env` into your IDE. ([configuration.md §8](configuration.md#8-configuration-gotchas-read-this))
2. **`globally_quoted_identifiers: true`** makes Hibernate create literal camelCase columns. Add `@Column(name="snake_case")` on entity fields. ([database.md §13](database.md#13-conventions-gotchas--history))
3. **`schema.sql` must be applied by hand** to a fresh DB (`sql.init.mode: never`). Missing it → `Column 'using_totp' not found` / missing-role errors.
4. **Public-URL lockstep:** a public route must be in **both** `Constants.PUBLIC_URLS` (filter-chain `permitAll`) and `PUBLIC_ROUTES` (filter skip), or a stale `Bearer` header breaks it. ([security.md §12](security.md#12-public-endpoints))
5. **`@ElementCollection` tables have no `id`** — `invoiceserviceitems` is Hibernate-owned; don't redeclare it in `schema.sql`.
6. **Profile image storage is local + hardcoded** to `~/Downloads/images/` — not container/cloud-ready.

---

## 8. Testing & quality

- **Backend:** `mvn test` (the suite is currently sparse — a real gap, and a good place to contribute). `mvn package` builds the JAR.
- **Security scanning:** the OWASP `dependency-check-maven` plugin is configured (`failBuildOnCVSS=7`).
- **Frontend:** `npm test` (Vitest), `npm run lint` (ESLint), `npm run format` (Prettier).
- **Manual smoke test:** log in as `eve.admin@tessera.dev` / `TesseraDemo@1`, open the admin dashboard, enroll TOTP in the Security Center, and check the audit log on the profile page.

---

## 9. Where to look

| You want to… | Start at |
|--------------|----------|
| Run it | [getting-started.md](getting-started.md) |
| Understand the layout | [architecture.md](architecture.md) |
| Call/await an endpoint | [api-reference.md](api-reference.md) |
| Touch auth, tokens, MFA, RBAC | [security.md](security.md) + `SecurityConfig`, `TokenProvider`, `SessionServiceImpl` |
| Change the schema | [database.md](database.md) + `schema.sql` |
| Change a setting | [configuration.md](configuration.md) + `application*.yml` |
| Ship it | [deployment.md](deployment.md) |
| Work in the SPA | [../securecapitaapp/README.md](../securecapitaapp/README.md) |
