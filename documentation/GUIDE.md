# TesseraApp — The Guide

**Version:** 1.0
**Last Updated:** 2026-08-02
**Status:** Living — the single operational reference for this project.

## Overview

Everything you need to build, run, extend, secure, test and deploy TesseraApp. It replaces the
fourteen topic guides that used to live in this folder, because a set that large could not be kept
consistent with itself (see [IMPLEMENTATION-HISTORY.md §4.20](IMPLEMENTATION-HISTORY.md#420-the-documentation-itself-rotted)).

Two deep references sit alongside it and are **not** duplicated here:

- **[flows/](flows/README.md)** — click-to-database walkthroughs of every major flow, with sequence
  diagrams, JWT/header state, request/response JSON and real SQL.
- **[aws/RUNBOOK.md](../aws/RUNBOOK.md)** — the linear AWS deploy procedure.

> **Code wins over docs.** Where this guide and the source disagree, the source is authoritative and
> this guide should be fixed in the same change.

## Table of contents

- [1. Architecture](#1-architecture)
- [2. Getting started](#2-getting-started)
- [3. Configuration](#3-configuration)
- [4. The development loop](#4-the-development-loop)
- [5. Backend internals](#5-backend-internals)
- [6. Frontend internals](#6-frontend-internals)
- [7. Security model](#7-security-model)
- [8. API reference](#8-api-reference)
- [9. Database](#9-database)
- [10. Testing](#10-testing)
- [11. Deployment](#11-deployment)

---

## 1. Architecture

### 1.1 The five ideas

Hold these and the codebase makes sense:

1. **Stateless access, stateful refresh.** Access tokens are verified by signature alone — no
   database hit, no server session. Refresh tokens are *rows* in `refreshsessions`, so sessions can
   be rotated, listed and revoked. This split is the heart of the design: fast request handling
   **and** revocable sessions.
2. **Two persistence idioms.** Identity/auth uses `NamedParameterJdbcTemplate` with hand-written SQL;
   the business domain (customers/invoices/services) uses JPA. `User` is a plain POJO, not an entity.
3. **One envelope.** Every controller returns `HttpResponse { timeStamp, statusCode, status, message,
   data }` — successes and errors look identical to the client, which reads one field.
4. **Authorization is permission strings.** A user has exactly one role; its `permission` column
   becomes Spring authorities matched by `SecurityConfig` rules and `@PreAuthorize`.
5. **Audit is event-driven.** Controllers `publishEvent(new NewUserEvent(...))`; a single listener
   writes the `userevents` row off the request path.

### 1.2 Tiers

```
┌─────────────────────────────────────────────────────────────────┐
│  Angular 21 SPA — standalone, zoneless, signals                 │
│  features/ · service/ · interceptor/ · guard/ · directive/       │
└────────────────────────────┬────────────────────────────────────┘
                             │ HTTPS · Authorization: Bearer <JWT>
┌────────────────────────────▼────────────────────────────────────┐
│  Spring Boot 4 / Java 21                                        │
│    filter/       RateLimitFilter → CustomAuthFilter             │
│    configuration/SecurityConfig (matchers, CORS, headers)       │
│    controller/   thin @RestControllers, HttpResponse envelope   │
│    service/ + serviceimpl/    business logic                    │
│    repo/ + repoimpl/          JDBC  ·  Spring Data JPA          │
└────────────────────────────┬────────────────────────────────────┘
                             │ JDBC
┌────────────────────────────▼────────────────────────────────────┐
│  MySQL 8.4 — identity schema owned by schema.sql,               │
│              business tables owned by Hibernate                  │
└─────────────────────────────────────────────────────────────────┘
```

In production the Angular build is copied into `src/main/resources/static/` inside the Docker image,
so **Spring Boot serves the SPA itself** and both live on one origin. That single fact causes a whole
class of behaviour that never appears locally — see [§7.8](#78-deployment-parity).

### 1.3 A request end-to-end

Tracing `GET /customer/list` after a user clicks "Customers":

```
[Angular] CustomersComponent → CustomerService.customers$()   (HttpClient GET)
   ├─ cacheInterceptor   — cache hit? return it, stop here (tokenInterceptor never runs)
   ├─ tokenInterceptor   — not a public route → clone with Authorization: Bearer <access_token>
   │                       (on a later 401: single-flight refresh, then replay the request)
   ▼
[HTTP] → /customer/list
   ├─ CorsFilter
   ├─ RateLimitFilter    — per-IP bucket; 429 + Retry-After when exhausted
   ├─ CustomAuthFilter   — verify signature, expiry, passwordChangedAt; set Authentication
   ├─ SecurityFilterChain— rule "GET /** → READ:USER or READ:CUSTOMER" (401/403 if it fails)
   ▼
[Spring MVC] CustomerController.getCustomers(@AuthenticationPrincipal UserDTO user, page, size)
   ▼
CustomerService → CustomerRepo (Spring Data JPA) → MySQL
   ▼
HttpResponse { data: { user, page, stats }, message, status, statusCode }
   ▼
[Angular] service maps the envelope → component renders (DataState.LOADED)
```

Every protected endpoint follows this spine. Only the authority rule and the terminal
controller/service/repo change.

### 1.4 Package layout

Base package `com.bob.angularspringbootfullstack`, one package per responsibility:

| Package | Holds |
|---|---|
| `controller/` | Thin `@RestController`s. No business logic; return `ResponseEntity<HttpResponse>` |
| `service/` + `service/serviceimpl/` | Business logic, transactions, validation, encoding, generation |
| `repo/` + `repo/repoimpl/` | Data access. `UserRepoImpl` also implements `UserDetailsService` |
| `query/` | `public static final String` SQL constants with **named** parameters (`:email`) |
| `rowmapper/` | `ResultSet` → model, via the Lombok builder |
| `model/` | Domain POJOs and JPA `@Entity` classes |
| `dto/` + `dtomapper/` | What crosses the wire — `UserDTO` is returned, never `User` with its hash |
| `form/` | Request bodies, `@Valid`-annotated |
| `enumeration/` | `EventType`, `RoleType`, … |
| `event/` + `listener/` | `NewUserEvent` → `NewUserEventListener` (the single audit sink) |
| `exception/` | `ApiException`, two `@RestControllerAdvice` classes, `ErrorDetailScrubber` |
| `handler/` | 401 entry point, 403 access-denied, OAuth2 success |
| `filter/` | `RateLimitFilter`, `CustomAuthFilter` |
| `configuration/` | `SecurityConfig`, `OAuth2ClientConfig`, `AwsS3Config`, `WebMvcConfig` |
| `tokenprovider/` | `TokenProvider` — mint and verify |
| `utils/` | `RequestUtils`, `TotpUtils`, `SMSUtils`, `ExceptionUtils`, `BrowserErrorPage`, `AuthDiagnosticsLogger` |
| `constants/` | `Constants` (token lifetimes, `PUBLIC_URLS`/`PUBLIC_ROUTES`), `CapabilityCatalog` |
| `seed/` | `DemoDataSeeder` — dev profile only |
| `report/` | XLSX export |

---

## 2. Getting started

**Time to first login: about ten minutes.**

### 2.1 Prerequisites

| Tool | Version | Needed for |
|---|---|---|
| **JDK** | 21+ | the backend |
| **Maven** | 3.8+ | building — or use the bundled `./mvnw` |
| **Node.js** | 22 LTS (or 20.19+) | the frontend |
| **MySQL** | 8.x | the database — *or* use Docker |
| **Docker** | 24+ | optional: supplies MySQL, or runs the whole stack |
| **Bash** | any | `start.sh` is a Bash script — on Windows use Git Bash or WSL |

You need **either** a local MySQL **or** Docker, not both.

### 2.2 Configure `.env`

```bash
git clone <repo-url>
cd angularSpringBootFullStack
cp .env.example .env            # PowerShell: Copy-Item .env.example .env
```

Minimum viable settings:

```dotenv
MYSQL_DATABASE=db2
MYSQL_USERNAME=root
MYSQL_PASSWORD=your-db-password
MYSQL_ROOT_PASSWORD=your-db-password   # only used by the Docker MySQL container
JWT_SECRET=<a-long-random-string>
```

Generate a strong secret:

```bash
openssl rand -base64 48                                          # Git Bash / macOS / Linux
[Convert]::ToBase64String((1..48 | % { Get-Random -Max 256 }))   # PowerShell
```

Email, OAuth and Twilio variables are optional for a first run — full reference in [§3.2](#32-environment-variable-reference).

> **Never commit `.env`.** It is gitignored; only the placeholder-only `.env.example` is tracked
> (whitelisted with `!.env.example`).

### 2.3 Create and seed the database

```bash
mysql -u root -p -e "CREATE DATABASE IF NOT EXISTS db2;"
mysql -u root -p db2 < src/main/resources/schema.sql
```

`schema.sql` is **idempotent** — safe to re-run. The `customer`/`invoice`/`services` tables are
created by Hibernate on first boot; you run nothing for them. See [§9](#9-database).

### 2.4 Run it

Everything is driven by `start.sh`. Open it and set the two switches at the top:

```bash
ENV=local      # local | docker
DB=native      # native | local | aiven   (only consulted when ENV=local)
```

```bash
chmod +x start.sh
./start.sh
```

**Local mode (`ENV=local`)** — recommended. Spring Boot runs natively with DevTools hot-restart;
Angular runs `ng serve` with hot reload.

| `DB` | What happens | Requires |
|---|---|---|
| `native` *(default)* | Uses your **host's own MySQL** on `127.0.0.1:3306` (e.g. the Windows MySQL80 service); starts **no** container | Native MySQL on 3306 |
| `local` | Starts a **MySQL Docker container** and waits for health | Docker running, **and no native MySQL on 3306** |
| `aiven` | Skips Docker; connects to **Aiven cloud MySQL** over TLS | `AIVEN_DB_*` in `.env` |

> ⚠ **Never run `DB=local` while a native MySQL is also on 3306.** They collide on the port, and the
> empty container *shadows* your real database, making it look wiped. `native` is the default for
> exactly this reason — see [IMPLEMENTATION-HISTORY §4.2](IMPLEMENTATION-HISTORY.md#42-all-my-data-vanished--docker-mysql-shadowing-native-mysql).

➡ **http://localhost:4200**

**Docker mode (`ENV=docker`)** — production-like. Builds the multi-stage image (Angular compiled
*into* the Spring Boot JAR) and runs app + MySQL via Compose. No hot reload.

➡ **http://localhost:8090** (or `APP_PORT`)

Press **Ctrl+C** to stop everything cleanly — the `cleanup` trap kills the Spring/Angular/browser
PIDs and stops the MySQL container when `DB=local`.

### 2.5 Log in

On the `dev` profile a seeder creates one demo user per role, all with the password
**`TesseraDemo@1`**:

| Email | Role | Good for testing |
|---|---|---|
| `eve.admin@tessera.dev` | `ROLE_ADMIN` | the admin surface (`/users`, `/roles`, `/security`) |
| `frank.app@tessera.dev` | `ROLE_APPLICATION_ADMIN` | full access including delete |
| `alice.guest@tessera.dev` | `ROLE_GUEST` | minimal read-only access |

**You'll know it works when** signing in as `eve.admin@tessera.dev` lands you on the dashboard with
the Admin menu visible in the navbar.

Or register at `/register` — new accounts need email verification to become `enabled`, and in dev the
verification link is logged to the server console.

### 2.6 Running the halves individually

```bash
mvn spring-boot:run        # backend only (dev profile boots with literal defaults — no .env needed)
cd tesseraapp && npm install && npm start   # frontend only, :4200, calls the API on :8080
```

A bare `mvn spring-boot:run` still needs a MySQL reachable at `127.0.0.1:3306`.

### 2.7 Common first-run problems

| Symptom | Cause & fix |
|---|---|
| `Could not resolve placeholder 'MYSQL_USERNAME'` / `'JWT_SECRET'` | A **required** env var is missing. Happens in **prod/CI**, not dev — dev ships literal defaults. Run via `./start.sh`, export `.env`, or use the platform config |
| `Communications link failure` / `No such host is known (mysql)` | `MYSQL_HOST=mysql` (a Docker service name) used outside Docker. Set `MYSQL_HOST=127.0.0.1` |
| `Column 'using_totp' not found`, or missing-role errors | The database was never initialised. Apply `schema.sql` ([§2.3](#23-create-and-seed-the-database)) |
| Port 8080/4200/3306 already in use | Another process (or a previous run) holds it |
| MySQL container never becomes healthy | `docker compose logs mysql` — usually a wrong `MYSQL_ROOT_PASSWORD` or 3306 already bound |
| Everything looks empty and the tables are capitalized | You are on a Docker/Aiven (case-sensitive) server, not native. See [§9.7](#97-which-mysql-server) |

---

## 3. Configuration

### 3.1 Philosophy and precedence

Twelve-factor, environment-variable driven. **No secrets in source.** `application.yml` holds
*structure*, not values — it declares which env var feeds each setting, so the same JAR runs
unchanged in dev, Docker and the cloud.

At startup Spring merges property sources; highest priority wins:

```
1. OS / shell environment variables      (exported by start.sh from .env, or set by the host/cloud)
2. application-{profile}.yml             (application-dev.yml or application-prod.yml)
3. application.yml                        (base, references ${ENV_VARS})
```

Two consequences worth internalizing:

- **Spring never reads `.env` itself.** `start.sh` *sources* it into the shell; Spring then sees OS
  environment variables. In IntelliJ, point **Run ▸ Edit Configurations ▸ Spring Boot ▸ Environment
  file** at `.env`.
- **`SPRING_DATASOURCE_*` overrides the assembled URL.** Relaxed binding maps
  `SPRING_DATASOURCE_URL/USERNAME/PASSWORD` onto `spring.datasource.*`, which **takes precedence**
  over the URL built from `MYSQL_*`. This is the override seam Docker and the Aiven path rely on.

### 3.2 Environment variable reference

Defaults shown are the **`dev`-profile fallbacks**. Under `prod` there are **no fallbacks**.

**Database**

| Variable | Purpose | Dev default |
|---|---|---|
| `MYSQL_HOST` / `MYSQL_PORT` | DB host and port | `127.0.0.1` / `3306` |
| `MYSQL_DATABASE` | Schema name | `db2` |
| `MYSQL_USERNAME` / `MYSQL_PASSWORD` | Credentials | `root` / `password` |
| `MYSQL_ROOT_PASSWORD` | Root password for the **Docker** container | *(required for Docker)* |
| `SPRING_DATASOURCE_URL` | Full JDBC URL — **overrides** the assembled one | *(unset)* |
| `SPRING_DATASOURCE_USERNAME` / `_PASSWORD` | Override credentials | *(unset)* |

**Runtime**

| Variable | Purpose | Dev default |
|---|---|---|
| `SPRING_ACTIVE_PROFILES` | `dev` \| `prod` (also `qa`, `stage`) | `dev` |
| `CONTAINER_PORT` | Port Spring Boot listens on | `8080` |
| `APP_PORT` | Host port mapped to the container | `8090` |
| `SHOW_SQL` | JPA SQL logging | `false` |
| `LOG_LEVEL_*` / `DEBUG_REPORT` | CloudWatch log verbosity knobs | see [§11.6](#116-logging) |

**Authentication**

| Variable | Purpose | Dev default |
|---|---|---|
| `JWT_SECRET` | HMAC-SHA512 signing key, **≥32 chars** | a dev-only placeholder |

**Email**

| Variable | Purpose | Dev default |
|---|---|---|
| `MAIL_USERNAME` | Gmail address for outgoing mail | *(empty)* |
| `MAIL_PASSWORD` | **Gmail App Password** (16 chars, not the account password) | *(empty)* |
| `MAIL_HOST` / `MAIL_PORT` | SMTP | `smtp.gmail.com` / `587` |
| `VERIFY_EMAIL_HOST` | **Reserved, currently unused** — links are built from `UI_APP_URL` | `http://localhost:8080` |

Generate a Gmail App Password at <https://myaccount.google.com/apppasswords>. Verification and reset
emails send for real; only the **SMS** path is stubbed.

**Frontend / images**

| Variable | Purpose | Dev default |
|---|---|---|
| `UI_APP_URL` | SPA origin — drives CORS **and** the base of email verification links | `http://localhost:4200` |
| `IMAGE_STORAGE_TYPE` | `local` \| `s3` | `local` |
| `IMAGE_STORAGE_PATH` | Directory for local image storage | `~/tesseraapp/images` |

> `IMAGE_STORAGE_PATH`'s default lives in the **base** `application.yml`, not `application-dev.yml`,
> so it applies under `prod` too — a missing value will **not** fail fast. Always set it explicitly
> in containers, or set `IMAGE_STORAGE_TYPE=s3`.

**Reverse proxy — required whenever deployed**

Two independent settings, both defaulting to "no proxy", and each fails *silently* rather than
loudly.

| Variable | Governs | Default | Behind one ALB |
|---|---|---|---|
| `FORWARD_HEADERS_STRATEGY` | URLs the app **generates** (`server.forward-headers-strategy`) | `none` | `framework` |
| `TRUSTED_PROXY_COUNT` | The client IP the app **reads** (`app.security.trusted-proxy-count`) | `0` | `1` (`2` with a CDN) |

- Left at `none`, `OAuth2ClientConfig`'s `{baseUrl}` resolves to the container's own
  `http://10.0.1.23:8080`, so every federated sign-in fails with `redirect_uri_mismatch` — while
  working perfectly on localhost.
- Left at `0` behind a proxy, `RequestUtils.getIpAddress` returns the load balancer's address for
  everyone. **Two security controls degrade quietly:** the rate limiter collapses every user into one
  bucket, and the anomaly detector's `NEW_NETWORK` signal can never fire. Set it *too high* and an
  attacker-supplied header entry becomes trusted — match the real topology, do not pad it.

`TrustedProxyConfigurer` prints `[NET] trusted-proxy-count=…` at startup. Check that line; it is the
cheapest confirmation the setting took effect.

**Federated login (optional)**

| Variable | Purpose |
|---|---|
| `GOOGLE_CLIENT_ID` / `_SECRET` | Google OAuth client |
| `GITHUB_CLIENT_ID` / `_SECRET` | GitHub OAuth app |
| `MICROSOFT_CLIENT_ID` / `_SECRET` | Microsoft (Entra) app registration |
| `MICROSOFT_TENANT_ID` | `common` \| `consumers` \| `organizations` \| tenant GUID |

A provider's button appears only when its `CLIENT_ID` is set — the SPA discovers configured providers
via `GET /oauth2/providers`. See [§3.4](#34-setting-up-a-federated-provider).

**SMS 2FA (optional, stubbed):** `TWILIO_FROM_NUMBER`, `TWILIO_ACCOUNT_SID`, `TWILIO_AUTH_TOKEN`.
The send is commented out in `NotificationServiceImpl.sendTwoFactorCode` to avoid charges; the code
is logged to the server console.

**Aiven (only when `DB=aiven`):** `AIVEN_DB_HOST`, `_PORT`, `_NAME`, `_USERNAME`, `_PASSWORD`.
The current cloud database is `db3`.

### 3.3 Spring profiles

| Profile | Activated by | Behaviour | File |
|---|---|---|---|
| **`dev`** | default | Every variable has a literal local fallback; the app boots with a near-empty `.env`. The demo seeder runs. `ddl-auto: update` | `application-dev.yml` |
| **`prod`** | `SPRING_ACTIVE_PROFILES=prod`, or the image's `ENTRYPOINT` | **No fallbacks** — a missing variable is a startup failure. No seeder. `ddl-auto: validate`, `show-sql: false`, `app.error.expose-details: false` | `application-prod.yml` |
| `qa`, `stage` | declared in `pom.xml` | Set the profile name only; they fall back to base `application.yml` and require every env var | `application-qa.yml` / `application-stage.yml` |

### 3.4 Setting up a federated provider

All three use the same callback shape — `http://localhost:8080/login/oauth2/code/{provider}` — which
points at the **backend** (8080), not the SPA, because Spring Security handles the callback. Register
both the local and the deployed callback in each console; all three providers accept a list, so one
registration serves both with no per-environment client id.

**GitHub (fastest — no domain verification).** Settings ▸ Developer settings ▸ OAuth Apps ▸ New. Set
the homepage to `http://localhost:4200` and the callback to
`http://localhost:8080/login/oauth2/code/github`. Copy the Client ID, generate a secret, put both in
`.env`, restart. Confirm the log reads `Federated login providers configured: [github]`.

**Google.** Cloud Console ▸ APIs & Services ▸ **OAuth consent screen** first — choose **External**,
fill in the app name and contacts. Then Credentials ▸ Create ▸ OAuth client ID ▸ **Web application**,
authorized redirect URI `http://localhost:8080/login/oauth2/code/google` (JavaScript origins are not
needed — this is a server-side Authorization Code flow).

> ⚠ **While the consent screen is in "Testing", only accounts added under Audience ▸ Test users can
> sign in**; everyone else gets `Error 403: access_denied`. Add yourself, or Publish.
>
> **Internal vs External are mutually exclusive, and External is the superset.** External already
> includes your Workspace org *plus* personal and third-party accounts. Only choose Internal to
> deliberately *exclude* non-org accounts — and never flip External→Internal once personal accounts
> have signed in, because it blocks every already-linked one immediately.

**Microsoft (Entra ID).** App registrations ▸ New. Supported account types decides
`MICROSOFT_TENANT_ID` (`common` = personal + work/school, `organizations` = any org, `consumers` =
personal only, or the tenant GUID). Redirect URI → platform **Web** →
`http://localhost:8080/login/oauth2/code/microsoft`. Certificates & secrets ▸ New client secret ▸
copy the **Value** (not the Secret ID; it is shown once).

> ⚠ **Register the redirect URI under the Web platform, not SPA or mobile, and leave *Allow public
> client flows* set to No.** Registering it as a SPA makes Entra reject the correct
> `client_secret_post` authentication with `AADSTS90023: Public clients can't send a client secret`.
> That is a portal setting — `OAuth2ClientConfig` is already right.

### 3.5 Token settings

Compile-time constants in `constants/Constants.java`, not env vars:

| Setting | Value |
|---|---|
| Access-token lifetime | `1_800_000` ms (**30 min**) |
| Refresh-token lifetime | `432_000_000` ms (**5 days**, sliding) |
| Issuer (`iss`) | `BOBBYLON_LLC` |
| Audience (`aud`) | `BOBS_MANAGEMENT` |
| Authorities claim | `authorities` — access tokens only |
| Session-family claim | `sid` — both token types |

### 3.6 Configuration gotchas

1. **`Could not resolve placeholder '<NAME>'`** — a required variable is missing. Dev ships literals;
   prod deliberately keeps none, so it fails fast. *Never write `X: ${X}` or `X: ${X:default}` in a
   profile YAML* — that reintroduces the `Circular placeholder reference` bug.
2. **`globally_quoted_identifiers: true`** makes Hibernate create literal camelCase columns. Always
   add `@Column(name = "snake_case")` on entity fields.
3. **`MYSQL_HOST=mysql` outside Docker** → `No such host is known`. Use `127.0.0.1`.
4. **Cloud databases need TLS** — set `useSSL=true&requireSSL=true` in `SPRING_DATASOURCE_URL`.
5. **`show-sql: false` does not stop SQL logging.** `org.hibernate.SQL` at DEBUG is a separate SLF4J
   path, and `DEBUG_REPORT=true` reopens it.

### 3.7 Secrets handling

- Never commit `.env`. In the cloud, don't ship one at all — set variables through the platform.
- **Rotate anything that leaks.** Rotating `JWT_SECRET` invalidates every existing token and forces a
  universal re-login, which is exactly the desired effect after a leak.
- Use the `prod` profile in production so a missing secret fails fast rather than falling back.
- `JwtSecretGuard` refuses to start on a missing, placeholder, or under-32-character secret.

---

## 4. The development loop

### 4.1 IDE setup

This project is developed in **IntelliJ IDEA or VS Code**.

**IntelliJ:** open the root `pom.xml` as a project. Set **Project Structure ▸ Project ▸ SDK** to JDK
21 (language level 21). **Enable annotation processing** (Settings ▸ Build ▸ Compiler ▸ Annotation
Processors) — without it Lombok's `User.builder()` and `@RequiredArgsConstructor` fields show as
unresolved. Point the run config's **Environment file** at `.env`.

> If IntelliJ reports `Cannot resolve symbol 'String'` across every class, the **Project SDK is not
> attached**. Set it under Project *and* Modules ▸ Dependencies ▸ Module SDK, then Invalidate Caches.
> It is not a code problem — the tree still compiles and deploys.

**VS Code:** Extension Pack for Java + Spring Boot Extension Pack (Lombok support ships with the Java
pack); Angular Language Service, ESLint and Prettier for the frontend. Set
`"envFile": "${workspaceFolder}/.env"` in `launch.json`.

### 4.2 Debugging

**Backend hot reload.** `spring-boot-devtools` is on the classpath, so under `mvn spring-boot:run`
any recompile triggers a ~1–2 s context restart (the restart classloader reloads only your code). It
is excluded from the production jar.

**Breakpoints need the JVM under the IDE's debugger**, which means running the backend yourself
rather than through `start.sh` (which backgrounds `mvn` and owns the process):

```bash
# Terminal A — backend under the IDE debugger (with .env wired in)
# Terminal B — frontend only:
cd tesseraapp && npm start
```

To debug the process `start.sh` launches, add a JDWP agent via `MAVEN_OPTS`
(`-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005`) and attach to 5005.

**Frontend.** `ng serve` emits source maps, so breakpoints go directly on your TypeScript. Check the
Network tab for `Authorization: Bearer …` on protected calls and its absence on public ones. If a
stale list will not refresh, that is the HTTP cache — call `httpCache.logCache()` from the console.

### 4.3 Changing the schema

The data model has **two owners**:

| Table family | Owner | Created by | You edit |
|---|---|---|---|
| Identity / auth / audit | **`schema.sql`** (hand-written, idempotent) | Applied **by hand** (`sql.init.mode: never`) | `schema.sql` |
| Business (`customer`, `invoice`, `services`, `invoiceserviceitems`) | **Hibernate** | `ddl-auto: update` on boot (dev) | the `@Entity` class |

```bash
# identity-side change:
# 1. edit src/main/resources/schema.sql  (CREATE TABLE IF NOT EXISTS …, no DROPs)
# 2. re-apply (idempotent):
mysql -u root -p db2 < src/main/resources/schema.sql
# 3. add the model POJO + rowmapper + query + repo/impl
```

⚠ `CREATE TABLE IF NOT EXISTS` will **not** alter an existing table, and MySQL 8 has no
`ADD COLUMN IF NOT EXISTS`. For a new column on an existing table you must do **both**: add it to the
`CREATE` block (so fresh databases are correct) *and* run a one-off `ALTER` on each live database.
Forgetting the second half caused a production login outage — see
[IMPLEMENTATION-HISTORY §4.5](IMPLEMENTATION-HISTORY.md#45-login-started-returning-500--a-schema-drift-and-an-event-listener).

Full detail, including the JPA drift guard, in [§9.6](#96-schema-evolution).

### 4.4 Branch workflow

The integration branch is **`master`**; feature work happens on topic branches.

```bash
git checkout master && git pull
git checkout -b feat/<short-topic>
# … edit, build, test …
git add -p && git commit
git push -u origin feat/<short-topic>
gh pr create
```

Conventions that apply to every branch:

- **Branch before you commit.** Never commit straight to `master`.
- **Keep the public-route lists in lockstep.** A new public endpoint must be added to **both**
  `Constants.PUBLIC_URLS` (filter-chain `permitAll`) and `Constants.PUBLIC_ROUTES` (filter skip) — a
  route public in one but not the other breaks on a stale `Authorization` header.
- **Update the docs in the same change.** If a doc and the code disagree, the code wins and the doc
  gets fixed in that PR.
- **Never bypass hooks or signing.**

### 4.5 Build, test and verify

| Gate | Command | Notes |
|---|---|---|
| Backend tests | `./mvnw clean test` | 126 tests / 23 suites. `contextLoads` needs a live MySQL |
| Backend package | `./mvnw package` | Builds the jar (Angular is bundled only in the Docker build) |
| Dependency CVE scan | `./mvnw org.owasp:dependency-check-maven:check` | `failBuildOnCVSS=7`; first run downloads the NVD database |
| Frontend tests | `npm test` | Vitest + jsdom, 87 specs |
| Lint / format | `npm run lint` · `npm run format` | Lint **gates in CI** |
| Frontend build | `npm run build` | Production build with budgets |
| End-to-end smoke | `./start.sh`, log in as `eve.admin@tessera.dev` | The fastest "does it still work" check |

> ⚠ **`mvn compile` gives false passes** on signature changes — incremental output is not invalidated
> and it never compiles `src/test`. Use `clean test-compile` (or `clean test`).

> **Prefer running the real app over asserting from tests alone.** Every slice test deliberately
> skips the security filter chain, and nothing exercises a browser — so a green suite cannot tell you
> the whole system works. Ask before any destructive DB operation.

---

## 5. Backend internals

### 5.1 The data-access pattern

The signature pattern of this codebase. Per identity aggregate, **four cooperating pieces** wired
with `NamedParameterJdbcTemplate`:

1. **`XQuery`** — `public static final String` SQL constants using **named** parameters (`:email`).
2. **`XRowMapper`** — `ResultSet` → model, via the Lombok builder.
3. **`XRepo`** — the interface (the mockable seam).
4. **`XRepoImpl`** — `@Repository @RequiredArgsConstructor`; binds with `MapSqlParameterSource`, uses
   `GeneratedKeyHolder` for inserts, treats `EmptyResultDataAccessException` as not-found, and
   static-imports the query constants.

```
UserRepoImpl  ──uses──▶  UserQuery      (SQL constants)
     │                   UserRowMapper  (ResultSet → User POJO)
     ▼
NamedParameterJdbcTemplate ──▶ MySQL `users`
```

The business path is ordinary Spring Data JPA:

```
CustomerRepo (extends JpaRepository) ──▶ Hibernate ──▶ MySQL `Customer` / `Invoice`
```

**Choosing a path for new work:** identity / security / audit → JdbcTemplate (explicit SQL, lives in
`schema.sql`); business CRUD → JPA.

### 5.2 Recipes

**Add a REST endpoint (identity side)**

1. **Query** — add the SQL constant to the relevant `query/*Query.java`.
2. **Repo** — add the method to the interface and impl.
3. **Service** — add to the service interface and impl. **Keep business logic here**, not in the repo
   (encoding, UUID/code generation, validation) — that is what makes it unit-testable.
4. **Controller** — add the handler; return `HttpResponse.builder()…`.
5. **Security** — if the existing verb rules do not cover it, add a matcher in `SecurityConfig`
   **above** the catch-alls, plus `@PreAuthorize` if it is admin.
6. **Audit** — `eventPublisher.publishEvent(new NewUserEvent(email, SOME_EVENT))` if security-relevant.
7. **Frontend** — add a method to the Angular service and wire the component.

**Add an identity table**

1. Add `CREATE TABLE IF NOT EXISTS …` to `schema.sql` (snake_case; FK to `users` with
   `ON DELETE CASCADE` where appropriate; stable constraint names like `UQ_…`/`IX_…`).
2. Apply it ([§4.3](#43-changing-the-schema)).
3. Add the model POJO + rowmapper, then query/repo/service as above.

**Add an audit event type** — three places, because the `CHECK` constraint rejects unknown types:

1. `schema.sql` — extend the `CK_Events_Type` `CHECK` list **and** add the seed `INSERT`.
2. `enumeration/EventType.java` — add the constant with its description.
3. Publish it from the relevant code path.

On an existing database the `CHECK` must be rebuilt:
`ALTER TABLE events DROP CHECK CK_Events_Type;` then re-add with the new list. `schema.sql` rebuilds
it idempotently on every run for exactly this reason — a database created before a new type shipped
keeps the old constraint (`CREATE TABLE IF NOT EXISTS` is a no-op) and rejects the new type with
MySQL error 3819.

**Add or change a role/permission** — edit the seed `INSERT INTO roles …` in `schema.sql`
(permissions are comma-separated `RESOURCE:ACTION`), add the matching `hasAnyAuthority(...)` rule in
`SecurityConfig` if the authority is new, and keep `enumeration/RoleType.java` in sync.

### 5.3 Conventions

| Pattern | Where | Why |
|---|---|---|
| Interface + `…Impl` | services, repos | Depend on contracts; mockable seams |
| `HttpResponse` envelope | every controller | Uniform client parsing |
| DTO over entity | `UserDTO` returned, never `User` | Never leak the password hash |
| SQL constants in `query/` | identity repos | One place to read and audit the SQL |
| Event-driven audit | `publishEvent` → listener | Keeps logging off the hot path |
| Tokens **only** via `SessionService` | every login path | One revocable session seam |
| Guards = UX, backend = boundary | Angular vs `SecurityConfig` | Defense in depth |
| Frontend never attaches tokens manually | `tokenInterceptor` | One place, with refresh + concurrency guard |

### 5.4 Gotchas that will bite you

1. **Dev boots with literal defaults; prod fails fast.** Both are deliberate.
2. **`globally_quoted_identifiers: true`** → add `@Column(name="…")` on every entity field.
3. **`schema.sql` must be applied by hand** to a fresh database.
4. **Public-URL lockstep** — `PUBLIC_URLS` and `PUBLIC_ROUTES` must agree.
5. **`@ElementCollection` tables have no surrogate `id`** — `invoiceserviceitems` is Hibernate-owned;
   don't redeclare it in `schema.sql`.
6. **Audit writes must never be fatal.** `NewUserEventListener` swallows and logs failures; Spring's
   synchronous multicaster would otherwise propagate a logging failure into the request.

---

## 6. Frontend internals

**Root:** `tesseraapp/src/app/` · Angular 21 standalone (no NgModule), zoneless, signals ·
Bootstrap 5.3 color-mode · JWT in `localStorage`.

### 6.1 Bootstrap and providers

There is no `AppModule`. `appConfig` is passed to `bootstrapApplication()` in `main.ts`:

| Provider | What it does |
|---|---|
| `provideRouter(routes, withComponentInputBinding(), withPreloading(PreloadAllModules))` | Route table; binds `:id`/`:key` straight to component inputs; preloads lazy chunks after first paint |
| `provideHttpClient(withInterceptors([cacheInterceptor, tokenInterceptor]))` | The two interceptors, **in this exact order** |
| `provideTransloco(…)` | Runtime i18n |
| `provideToastr({ timeOut: 4000, positionClass: 'toast-bottom-right', preventDuplicates: true })` | Global toast defaults behind `NotificationsService` |
| `provideAnimationsAsync()`, `provideBrowserGlobalErrorListeners()`, `IMAGE_CONFIG` | Animations, global error routing, `NgOptimizedImage` config |

> **Interceptor array order is a contract, not a style choice.** `[cacheInterceptor,
> tokenInterceptor]` means a cache hit short-circuits *before* any `Authorization` header is
> computed.

**API base URL.** Every service reads `environment.apiUrl` — no hardcoded origin. Dev is
`http://localhost:8080`; production is `''`, i.e. same-origin relative URLs, because the SPA is
served from inside the Spring Boot jar. `angular.json` `fileReplacements` swaps the file at build
time, so services import one symbol with no runtime branching. **No rebuild is needed to change the
backend origin.**

### 6.2 Routes and guards

All routes are lazy via `loadComponent`. Public auth/verification routes carry no guard; feature
routes use `authenticationGuard`; admin pages add `adminGuard` **after** it, so an anonymous user is
sent to login before the authority check runs.

| Path | Guard | Purpose |
|---|---|---|
| `login`, `register`, `resetpassword`, `verify` | 🔓 none | Auth screens |
| `verify/account/:key`, `verify/password/:key` | 🔓 none | Email-link landings. **No `/user` prefix** — that would collide with the backend's own endpoint once SPA and API share an origin |
| `oauth2/callback` | 🔓 none | Federated landing; tokens or an MFA handoff arrive in the fragment |
| `''`, `customers`, `customers/:id`, `invoices`, `invoice/:id/:invoiceNumber` | 🔑 auth | Dashboard and business browsing |
| `customer/new`, `invoice/new` | 🔑 auth (+ capability gating) | Creation forms |
| `profile`, `security` | 🔑 auth | Self-service — **plain auth, no admin guard** |
| `services` | 🔑 auth | Catalog, all authenticated users |
| `services/manage` | 🔑🛡 admin | Catalog administration (note: `manage`, not `admin`) |
| `users`, `users/:id`, `roles`, `billing`, `analytics`, `security-overview` | 🔑🛡 admin | Staff surface |
| `**` | — | Redirect to `/` |

| Guard | Allows | Denies |
|---|---|---|
| `authenticationGuard` | Token present **and** not expired | `createUrlTree(['/login'])` — a redirect, not `false`. Does not inspect authorities |
| `adminGuard` | Authenticated **and** `hasAnyAuthority('UPDATE:USER','UPDATE:ROLE')` | Anonymous → `/login`; authenticated but unauthorized → `/` (avoiding a 403-filled broken view) |
| `capabilityGuard` | Route-data-driven (`requiredAuthorities`) | **Fails closed** when a route declares nothing |

**None of these is a security boundary** (NFR-SEC-4). The authorities come from a token the user
controls; the backend re-derives them from the database on every request.

### 6.3 Interceptors and caching

```
             request                              request
HttpClient ──────────▶ cacheInterceptor ──────────▶ tokenInterceptor ─────▶ server
                        │ bypass? evict? hit?       │ public route? attach Bearer
                        │                           │ 401 → silent refresh + retry
          cache HIT: of(cached) ◀── short-circuits; tokenInterceptor never runs
```

**`cacheInterceptor`** decides in order: bypass routes (`verify`, `login`, `register`, `refresh`,
`resetpassword`, `new/password`) pass straight through; any non-GET **or** a `download` URL calls
`evictAll()` then forwards; a cache hit returns `of(cached)` without calling `next()`; a miss
forwards and stores on the final response.

| Aspect | Behaviour |
|---|---|
| Store | A plain in-memory object |
| **Key** | The **full request URL** including query string — each page/size/search variant is distinct |
| **TTL** | ❌ **None.** No expiry, no max size — entries live until an `evictAll()` |
| **Invalidation** | Coarse: every non-GET mutation wipes **all** entries. One extra round-trip; guaranteed-fresh state after any write |
| Logout | `UserService.logOut()` calls `evictAll()` — essential, because `/user/login` is a bypass route, so a same-SPA user switch would otherwise serve the previous user's cached `/user/profile` |

**`tokenInterceptor`** attaches `Authorization: Bearer <token>` unless the URL matches its public
skip list, and on a **401** performs a **single-flight** refresh: the first 401 sets a module-level
flag and calls `refreshToken$()`; concurrent 401s park on a `BehaviorSubject` and replay once the new
token arrives (no thundering herd). If the refresh itself fails, both tokens are cleared and the app
falls through to `/login`.

> **Lockstep lists.** `tokenInterceptor.publicRoutes`, `cacheInterceptor.bypassRoutes`, and the
> backend's `PUBLIC_URLS`/`PUBLIC_ROUTES` must all stay aligned.

### 6.4 State: `DataState`, signals and the RxJS trio

Async UI state is a three-value machine held in a signal, with `ChangeDetectionStrategy.OnPush`:

```ts
homeState = signal<GlobalStateInterface<...>>({ dataState: DataState.LOADING });
readonly DataState = DataState;

ngOnInit(): void {
  combineLatest([this._currentPage$, this._pageSize$])      // toObservable(signal) bridges inputs
    .pipe(
      switchMap(([page, size]) =>                            // cancels stale in-flight requests
        this.customerService.customers$(page, size).pipe(
          map(response => ({ dataState: DataState.LOADED, appData: response })),
          startWith({ dataState: DataState.LOADING }),       // spinner on every refetch
          catchError(error => {                              // toast + swallow, stream survives
            this.notification.onError(error);
            return of({ dataState: DataState.ERROR, error });
          }),
        ),
      ),
      takeUntilDestroyed(this.destroyRef),                   // no manual ngOnDestroy
    )
    .subscribe(state => this.homeState.set(state));
}
```

Templates branch on `@switch`/`@if` against `DataState.LOADING/LOADED/ERROR`.

| Interface | Shape |
|---|---|
| `GlobalStateInterface<T>` | `{ dataState; appData?; error? }` |
| `CustomHttpResponseInterface<T>` | `{ statusCode; message; data?; timestamp; reason?; devMessage?; status }` — the mirror of the backend envelope |
| `Key` | `TOKEN` / `REFRESH_TOKEN` — the two `localStorage` keys |

### 6.5 Services

All are `providedIn: 'root'`. Every `$`-suffixed method returns
`Observable<CustomHttpResponseInterface<T>>` and ends with `catchError(this.handleError)`, which
prefers the server's `error.error.reason`.

| Service | Owns |
|---|---|
| `UserService` | Auth, self-service, MFA, sessions, federation; the `localStorage` token side-effects and the JWT decode/expiry (memoized). `refreshToken$()` and `updatePassword$()` rewrite **both** tokens |
| `AdminUserService` | `/admin/user/**` — kept **separate from `UserService`** so admin-on-other-user operations never share a code path with self-service |
| `AnalyticsService` | `/admin/analytics/**` — the admin-gated reporting surface |
| `CustomerService` | Customers, invoices, XLSX downloads |
| `SecurityDashboardService` | `/admin/security/overview` |
| `ThemeService` | Dark/light as a signal, mirrored to `data-bs-theme`. The pre-paint value is set by an inline script in `index.html` to avoid a flash; the service re-asserts it |
| `LanguageService` | Active locale, mirroring `ThemeService` |
| `NotificationsService` | Thin facade over `ngx-toastr` so the library can be swapped in one place |
| `HttpCacheService` | The in-memory cache behind `cacheInterceptor` |
| `CommandPaletteService` | The registry of navigable destinations with labels, icons and required authorities |

### 6.6 Internationalization

Six locales — en · es · fr · de · pt · zh — switchable at runtime from the navbar, persisted like the
theme. Dictionaries live in `public/assets/i18n/{lang}.json`.

**Why Transloco rather than `@angular/localize`:** the built-in tooling resolves translations at
*compile* time and emits one bundle per language, so switching would load a different build and the
user would lose their place. Transloco swaps a JSON dictionary in place. The cost — dictionaries are
not tree-shaken — is a few kilobytes, fetched lazily and cached.

Conventions that matter:

- **`fallbackLang: 'en'` + `useFallbackTranslation`** — a missing key renders English, not the raw
  key. This is what makes an incremental translation pass safe.
- **One `*transloco="let t"` scope per template.** Where the top level is `@if`/`@switch` rather than
  an element, wrap the file in `<ng-container *transloco="let t">` — a scope on one branch is
  invisible to the others, and the symptom is *"Property 't' does not exist"*.
- **Translate whole sentences, never fragments.** Word order and punctuation around a name are not
  universal; splitting a sentence silently forces every language into English's arrangement.
- **Label languages in their own language** ("Español", never "Spanish"). Someone stranded in a
  language they cannot read needs an exit they can recognise.
- **RTL locales are deliberately absent** — Arabic and Hebrew need `dir="rtl"` plus a pass converting
  physical CSS properties to logical ones. Shipping one before that work serves those readers worse
  than not offering the language.

### 6.7 Capability-level UI gating

Route guards answer "may you open this page?". These answer "may you use this control?", so a refusal
is felt *before* the click rather than as a 403 on submit.

| Piece | Purpose |
|---|---|
| `*appHasAuthority` | Structural — renders content only for a held authority; supports `; else` for a read-only substitute |
| `[appRequiresAuthority]` | Attribute — leaves the control visible but inert, with `aria-disabled`, an `.is-restricted` class, and a `title` naming the missing capability |

**Hide or disable?** Remove a control when its presence is pure noise (a Delete button a viewer can
never use). Disable it when absence would read as a rendering bug — a form whose submit button simply
is not there.

> ⚠ **Authority flags must be getters, not fields.** `hasAnyAuthority` returns `false` for an
> **expired** token, not only a missing authority. A flag captured once at construction latches
> whatever was true then — and on a page refresh that is usually an expired token, so an admin sees
> the non-admin view until something reconstructs the component. `UserService` memoizes the decode on
> the token string, so per-change-detection evaluation is a string compare.

### 6.8 Shared components

- **Command palette** — ⌘/Ctrl+K, mounted once in `AppComponent` beside the router outlet. It
  **rebuilds its command list from the live token on every open**, so admin destinations appear only
  for tokens carrying the authorities. Anything outside its subtree opens it through
  `CommandPaletteService` rather than by dispatching a synthetic keypress, which would couple the
  button to a keybinding rather than to an intent.
- **`app-page-size-select`** (`shared/page-size-select/`) — the "Rows per page" control beside every
  pager. Its output is named `sizeSelected`, **not** `sizeChange`, so `[(size)]` is rejected on
  purpose: changing the page size must reset the page index in the same breath, because page 4 of a
  10-row listing does not exist once rows-per-page becomes 100. Requiring an explicit handler means
  the two halves cannot be separated by accident. Its `<label>` **wraps** the `<select>` rather than
  linking by `for`/`id`, because the security overview renders two on one page and a hardcoded id
  would collide.

---

## 7. Security model

### 7.1 At a glance

An **in-house, stateless-first, zero-trust** core with federated edges. The defining property is a
**single token-exchange seam**: every authentication path — password, SMS, TOTP, refresh, password
change, federated — converges on `SessionService` minting **our own** JWT, so RBAC, MFA policy and
audit apply identically to a federated user and a password user.

| Built in-house | Delegated |
|---|---|
| Credential auth (`AuthenticationManager` + BCrypt), JWT mint/verify (`TokenProvider`, HMAC-SHA512), refresh rotation + reuse detection, permission RBAC, TOTP (RFC 6238), brute-force gate, audit, account lifecycle | Federated login via `spring-security-oauth2-client`; transactional email via Gmail SMTP; SMS via the Twilio SDK (**stubbed**) |

> **If you add a new way to log a user in, issue tokens through `SessionService`, never
> `TokenProvider` directly.** That is the only reason every login appears in the Security Center and
> participates in rotation and revocation.

### 7.2 Login

```
POST /user/login {email, password}
  1. Brute-force gate: ≥5 failed attempts for this email in 15 min → reject (generic message)
  2. Publish LOGIN_ATTEMPT  (only if the email maps to a real user → no enumeration)
  3. authenticationManager.authenticate()  →  UserRepoImpl.loadUserByUsername + BCrypt
  4. Branch:
       user.usingTotp  ──▶ { user, challenge }        (NO tokens)
       user.using2FA   ──▶ { user } + SMS sent        (NO tokens)
       anomaly + password-only ──▶ emailed code       (NO tokens)
       otherwise       ──▶ { user, access_token, refresh_token }
```

**Anti-enumeration is a standing rule.** An unknown email and a wrong password produce **byte-identical**
responses. `LOGIN_ATTEMPT`/`LOGIN_ATTEMPT_FAILURE` fire only for known accounts, so the audit log is
not an oracle either. Disabled and locked keep their own actionable messages on purpose — those are
account-state signals, not credential checks. `UserControllerLoginEnumerationTest` guards this.

### 7.3 Tokens

Both types are HMAC-SHA512 signed with `JWT_SECRET`, carrying `iss` and `aud`.

| Claim | Access | Refresh | Meaning |
|---|:---:|:---:|---|
| `sub` | ✅ | ✅ | user id |
| `authorities` | ✅ | ❌ **absent** | permissions |
| `sid` | ✅ | ✅ | refresh-session family |
| `jti` | ❌ | ✅ | this refresh token's rotation id |
| Lifetime | 30 min | 5 days | |

**Why the refresh token has no authorities:** the verifier does not *require* the claim, so refresh
tokens verify fine — but `CustomAuthFilter` refuses to *authenticate* any token whose authorities are
empty. That is what stops a refresh token being used as an access token.

**Per-request verification.** `CustomAuthFilter` (a `OncePerRequestFilter`, before
`UsernamePasswordAuthenticationFilter`) skips when there is no Bearer header, on `OPTIONS`
preflights, or on a `PUBLIC_ROUTES` prefix. Otherwise it checks signature, expiry, and that the
token's `iat` is **after** the user's `passwordChangedAt` — so a password change kills every
outstanding token. Non-empty authorities build the `Authentication` (principal = `UserDTO`); empty
ones clear the context.

### 7.4 Authorization

A user has exactly one role; its comma-separated `permission` string becomes
`SimpleGrantedAuthority` instances embedded in the access token.

**The rules are evaluated top-down — order matters:**

| # | Matcher | Requirement |
|---|---|---|
| 1 | `POST /user/register`, `POST /user/login`, `/actuator/**`, `PUBLIC_URLS` | `permitAll` |
| 2 | `DELETE /user/delete/**` | `DELETE:USER` |
| 3 | `DELETE /customer/delete/**` | `DELETE:CUSTOMER` |
| 4 | `PATCH /admin/user/*/role/**` | `UPDATE:ROLE` |
| 5 | `PATCH /admin/user/*/settings` | `UPDATE:USER` |
| 6 | `/admin/**` | `UPDATE:USER` **or** `UPDATE:ROLE` |
| 7 | `/user/totp/**`, `/user/sessions/**` | `authenticated` (self-service) |
| 8 | `GET /**` | `READ:USER` **or** `READ:CUSTOMER` |
| 9 | `POST /**` | `UPDATE:USER` **or** `UPDATE:CUSTOMER` |
| 10 | `PUT /**` | `UPDATE:USER`, `UPDATE:CUSTOMER`, **or** `UPDATE:ROLE` |
| 11 | anything else | `authenticated` |

Two ordering subtleties: admin rules (4–6) precede the verb catch-alls so role reassignment truly
demands `UPDATE:ROLE`; self-service rules (7) precede them so a `ROLE_GUEST` can still manage their
own TOTP and sessions. `@EnableMethodSecurity` + `@PreAuthorize` repeat the checks at method level.

### 7.5 Roles and capabilities

| # | Role | In one sentence | Permissions |
|---|---|---|---|
| 1 | `ROLE_GUEST` | Sign in and see their own account; cannot even list customers | `READ:USER` |
| 2 | `ROLE_USER` | The default. Browses customers and invoices; changes nothing | `READ:USER, READ:CUSTOMER` |
| 3 | `ROLE_MODERATOR` | May also edit customers and raise invoices. Not staff | `+ UPDATE:CUSTOMER` |
| 4 | `ROLE_HELP_DESK_ADMIN` | Support: fixes account state, **cannot assign roles** | `READ:*, UPDATE:USER` |
| 5 | `ROLE_ORGANIZATION_ADMIN` | Tenant admin: full admin surface, **restricted to their own orgs** | `+ UPDATE:ROLE` |
| 6 | `ROLE_ADMIN` | Platform admin. Unscoped, plus user deletion | `+ DELETE:USER` |
| 7 | `ROLE_APPLICATION_ADMIN` | Everything, plus `DELETE:CUSTOMER` | all |

New accounts — password or first federated sign-in — get `ROLE_USER`.

**Capability matrix** (✅ allowed · 🟡 org-scoped · ❌ 403):

| Capability | Guest | User | Mod | Help Desk | Org Admin | Admin | App Admin |
|---|:--:|:--:|:--:|:--:|:--:|:--:|:--:|
| Own profile, password, TOTP, sessions, providers | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| Browse customers / invoices / catalog | ❌ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| Create / edit customers and invoices | ❌ | ❌ | ✅ | ✅ | ✅ | ✅ | ✅ |
| Delete a customer | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ✅ |
| Add / edit / retire services | ❌ | ❌ | ❌ | ✅ | ✅ | ✅ | ✅ |
| User directory, detail, account state | ❌ | ❌ | ❌ | ✅ | 🟡 | ✅ | ✅ |
| **Assign roles** | ❌ | ❌ | ❌ | ❌ | 🟡 | ✅ | ✅ |
| Delete a user | ❌ | ❌ | ❌ | ❌ | ❌ | ✅ | ✅ |
| Billing / analytics / security dashboard | ❌ | ❌ | ❌ | ✅ | 🟡 | ✅ | ✅ |

> **The help-desk gap is deliberate.** `ROLE_HELP_DESK_ADMIN` holds `UPDATE:USER` but not
> `UPDATE:ROLE`, so support can unlock an account without being able to promote it. That is the whole
> point of splitting the two authorities.

**What nobody can do, including `ROLE_APPLICATION_ADMIN`:** change their own role or account state
through the admin surface (self-targeting is refused — a second administrator is always involved);
read another user's password; see recovery codes twice; learn whether an email is registered from any
failure response; disconnect an account's last sign-in method; delete a service or a role.

### 7.6 Organization scoping

A second authorization axis *on top of* RBAC. Where authorities answer **"what may you do?"**, scope
answers **"to whom may you do it?"**. Confusing them is the most common way to reason about this
system wrongly — passing the first check is not evidence about the second.

**The predicate:** an org admin may act on a user who shares at least one **active** membership with
them (a `COUNT` over a self-join of `userorganizations`). Only `ROLE_ORGANIZATION_ADMIN` is scoped;
`ROLE_ADMIN` and `ROLE_APPLICATION_ADMIN` act globally by design.

| Surface | Behaviour for an org admin |
|---|---|
| User directory | Lists only users sharing an organization (a scoped query, not a filtered page) |
| User detail, audit log | **403 for anyone outside scope — reads are scoped, not just writes** |
| Role / settings / profile changes | 403 for out-of-scope targets |
| Billing, analytics, security dashboard | Aggregates restricted to their organizations |

**Three rules that matter more than they look:**

- **Scope inside the SQL, never on the results.** An aggregate has discarded its attribution by the
  time it is a number — you cannot subtract another tenant's contribution from a `SUM` after the
  fact — and filtering a page after retrieval corrupts `totalElements` and returns short pages.
- **An empty scope means *nothing*, not *everything*.** An org admin with no active memberships sees
  zeros and empty lists. Collapsing that into "unscoped" would hand the platform-wide view to
  precisely the account with the least established standing.
- **Fail closed.** If the scope `COUNT` throws, `isWithinOrganizationScope` logs and returns `false`.
  An error in the check denies, never grants.

New customers are stamped with the creating user's organization **from the JWT, never from the
request body** — a client-supplied `organizationId` would let anyone file records into another
tenant's dashboards.

**Known limits** (tracked in [FUTURE-ENHANCEMENTS §3.2](FUTURE-ENHANCEMENTS.md#32-access-model)):
scope covers *user administration only*, so `/customer/**` is system-wide; scoping is keyed to the
literal role name, so `ROLE_HELP_DESK_ADMIN` (which also holds `UPDATE:USER`) is unscoped; and there
is no role-tier ceiling, so an org admin can promote an in-scope user above their own tier.

**To add a scope-respecting endpoint:** inject `OrganizationService`; call
`requireOrganizationScope(authentication, targetId)` as the *first* line of a single-target action;
for a list, branch on `isOrganizationScoped(caller)` to a scoped query — **do not fetch-then-filter
in Java**; audit against the **target** user, not the caller; never leak account existence in the
denial.

### 7.7 401 vs 403, and error handling

| Code | Meaning | Trigger | Handler |
|---|---|---|---|
| **401** | "I don't know who you are" | No / expired / invalid token | `CustomAuthenticationEntryPoint` |
| **403** | "I know you, but you can't" | Valid token lacking the authority | `CustomAccessDeniedHandler` |

Both return the standard `HttpResponse` envelope — **except for top-level browser navigations**,
which get a styled page from `BrowserErrorPage`. The status code is unchanged in both branches; only
the representation differs.

**The detection signal matters more than it looks:**

| Signal | Verdict | Why |
|---|---|---|
| `X-Requested-With: XMLHttpRequest` | JSON, always | Definitive "programmatic call" |
| `Sec-Fetch-Mode: navigate` | HTML | Fetch metadata is set by the *browser*, not page script, so it cannot be forged. This is the branch that runs in practice |
| No fetch metadata | JSON unless it is a `GET` explicitly asking for `text/html` with no `Authorization` | Conservative fallback |

> **Why not content-negotiate on `Accept`?** Angular's `HttpClient` sets no `Accept` of its own, so
> an XHR can arrive with none. Getting it wrong means serving HTML to `tokenInterceptor`, whose
> silent-refresh path keys off a clean JSON 401. **The bias is toward JSON:** a false negative
> degrades one person's error page; a false positive signs everybody out.

**Error bodies.** `reason` is the only client-safe field and the only one the SPA renders;
`devMessage` carries raw cause text. In production `app.error.expose-details: false` suppresses
`devMessage` and `path` and genericises raw exception text. Note that `ErrorDetailScrubber` is a
`ResponseBodyAdvice` and therefore **structurally cannot** reach bodies written straight to the
servlet stream from the filter chain — `ExceptionUtils` scrubs at its own point of writing, gated on
the same property.

> ⚠ **There is no `404` and no `409`.** A missing row surfaces as **400 `"Record not found"`** and a
> duplicate as **400 `"Duplicate entry"`**. If you are writing a client, branch on the body's
> `reason`/`statusCode`, not on an HTTP status you will not get. (Returning real 404/409 is desirable
> future work.)

> ⚠ **Two `@RestControllerAdvice` classes overlap.** `GlobalExceptionHandler` and `HandleException`
> both map `MethodArgumentNotValidException`, `ApiException`, `AccessDeniedException` and the
> catch-all — producing the same *shape* but different `reason` strings, with neither setting
> `@Order`. This is a known wart; they should be consolidated.

### 7.8 Deployment parity

Most behaviour is identical everywhere. This is the exception list — **controls whose effectiveness
depends on configuration that differs by environment.** Everything marked ⚠️ can appear to work
locally while being degraded or absent when deployed.

| Control | Depends on | Local | AWS (`prod`) |
|---|---|---|---|
| ⚠️ **Anomaly detection / step-up** | `TRUSTED_PROXY_COUNT` | `0` — correct, no proxy | Must be `1`. At `0` every request appears to come from the load balancer, so `NEW_NETWORK` can never fire and **the control is silently dead** |
| ⚠️ **Rate limiting** | Same IP resolution | Per-caller buckets | At `0`, every user collapses into **one** bucket — the whole tenant throttles as a single caller |
| ⚠️ **Federated redirect** | `FORWARD_HEADERS_STRATEGY` | Correct without config | Needs `framework`, else `redirect_uri_mismatch` |
| ⚠️ **Which providers appear** | Each `*_CLIENT_ID` | Whatever `.env` has | Whatever the task definition injects |
| ⚠️ **Email** | `MAIL_*` | `.env` | Secrets Manager. Step-up **withholds tokens until the emailed code is entered**, so unset mail credentials lock out any account the risk engine flags |
| ⚠️ **Profile images** | `IMAGE_STORAGE_TYPE` | `local` | `s3` — the task role needs `s3:PutObject`/`s3:GetObject` |
| ⚠️ **Schema + seed data** | `schema.sql`, applied by hand | `db2` | `db3`. Tables can exist without seed rows |
| ⚠️ **JPA drift** | `ddl-auto` | `update` — silently fixes it | `validate` — **fails fast at startup** |
| ✅ CSP / HSTS / Referrer / Permissions | Served by Spring, not `ng serve` | Not enforced | Enforced |
| ✅ Error-detail scrubbing | `app.error.expose-details` | `true` | `false` |
| ✅ Refresh rotation, TOTP, RBAC, org scoping | Database state only | Identical | Identical — DB-backed, survives multi-instance |

**Post-deploy smoke test**, in the order that isolates faults fastest:

1. `GET /actuator/health` → `{"status":"UP"}`. Anything else means it never booted — check for
   `ddl-auto: validate` failures first.
2. Sign in with a password account. Confirms `JWT_SECRET`, the datasource, and seed data.
3. Count the provider buttons on the login screen — that number *is* `/oauth2/providers`, so it tells
   you which `*_CLIENT_ID`s reached the container.
4. Complete one federated sign-in — the single best test of `FORWARD_HEADERS_STRATEGY`.
5. Register a throwaway account and click the emailed link. Confirms mail credentials, the HTML
   template, and that the link lands on `/verify/account/:key` rather than raw JSON.
6. Navigate directly to a protected URL while signed out → styled 401 page, not JSON.
7. Check the boot log for `[NET] trusted-proxy-count=` and confirm it is not `0`.

### 7.9 Transport, CORS and headers

CSRF and HTTP Basic are **disabled** — correct for a stateless, token-in-header API. Headers set by
`SecurityConfig`: `X-Frame-Options: DENY`, `X-Content-Type-Options: nosniff`, HSTS
(`max-age=31536000; includeSubDomains`, inert over plain HTTP), **CSP** (`default-src 'self'`, with
`script-src` allow-listing exactly one inline script *by SHA-256 hash* rather than opening
`'unsafe-inline'`; `style-src` does permit `'unsafe-inline'` because Angular injects component styles
at runtime; `img-src` includes `https:` for S3 avatars), `Referrer-Policy:
strict-origin-when-cross-origin`, and `Permissions-Policy: camera=(), microphone=(), geolocation=(),
payment=()`.

> **CSP is deliberately asymmetric.** `ng serve` sends no CSP at all, so the policy is only ever
> enforced once the SPA is served from inside the jar. **Assume any new third-party origin will fail
> in production only.**

> ⚠ **Two CORS configurations exist and disagree.** `SecurityConfig.corsConfigurationSource()`
> hardcodes a list; `AngularSpringBootFullStackApplication.corsFilter()` reads
> `app.cors.allowed-origin-patterns`. The security chain answers preflights, so the hardcoded list
> effectively wins. Harmless today — the deployed shape is single-origin, so those calls never
> trigger a CORS check — and a real bug the moment anything genuinely cross-origin is added. Tracked
> in [FUTURE-ENHANCEMENTS §3.1](FUTURE-ENHANCEMENTS.md#31-security--identity).

### 7.10 MFA, federation and account security

**TOTP (fully implemented).** Self-service under `/user/totp/**`. The **challenge is the security
boundary**: because a TOTP code always exists on the user's phone, a naked "verify TOTP" endpoint
would let anyone with the authenticator skip the password. The server mints a short-lived
`mfachallenges` row only at first-factor success, and `POST /user/verify/totp` refuses any code not
accompanied by a live challenge.

Recovery codes: **10 per batch**, format `XXXXX-XXXXX` from RFC 4648 Base32 (no ambiguous
`0/1/8/9`), ~50 bits of entropy, stored as **SHA-256** hex. SHA-256 rather than BCrypt is correct
here — these are machine-generated and not brute-forceable the way a low-entropy password is, so
BCrypt's deliberate slowness would buy nothing and would slow every login. Consumption is an atomic
`UPDATE … SET used_at = NOW() … WHERE used_at IS NULL` whose **affected-row count is the verdict**,
so two concurrent attempts can never double-spend. Plaintext is shown exactly once.

> ⚠ **There is no "regenerate codes" endpoint.** A fresh batch is minted only by a (re)enrollment
> confirmation, so replacing a depleted set today means disable-and-re-enroll.

**SMS 2FA** is wired but **stubbed** — the Twilio send is commented out and the code is logged to the
server console. TOTP takes precedence: a confirmed authenticator skips the SMS path entirely.

**Federated login** is a standard Authorization Code flow, active only when provider credentials are
set. `OAuth2LoginSuccessHandler` performs find-or-create on `(provider, subject)` and issues **our**
token pair. Only the provider name and stable subject id are stored — never a third-party credential —
and `UNIQUE(provider, provider_subject)` makes find-or-create idempotent.

> **What this federation is, and is not.** This is **inbound social login** (OAuth2/OIDC): the app is
> the Relying Party, the provider is the IdP. It is *not* Google Cloud Workforce or Workload Identity
> Federation (outbound, for GCP resources) and *not* SAML. The tell: ask **what resource is being
> protected?** If it is your app's login, you want OAuth2/OIDC social login. If it is Google Cloud
> resources, that is a different product you would only touch to manage GCP access.

**Account linking** ("Connect a provider" from the Security Center) is a distinct flow, not an
ordinary login. The SPA mints a single-use, five-minute, provider-bound ticket over an authenticated
call; the browser carries it to `GET /oauth2/link/{provider}`; the callback attaches
`(provider, subject)` to **that** account and issues no tokens. It **refuses an identity that already
belongs to another account** — without which linking would be an account-takeover primitive, since
links are keyed on the provider subject rather than on the verified email. Unlinking is refused when
it would remove the last sign-in method.

**Password security.** BCrypt hashing. Registration creates a **disabled** account plus a UUID
activation key. Reset is a one-time emailed key resolved server-side, so the key and the password
never travel in a query string. A password change sets `password_changed_at`, which **kills every
outstanding token**, revokes all refresh sessions, and opens a fresh one for the current browser.

**Anomaly detection.** Compares against **that account's own** history of devices and networks
(networks at prefix granularity, so a DHCP renewal is not "new"). On a mismatch, a TOTP or SMS user
gets their normal challenge; a **password-only** user gets an emailed code and **no tokens** until it
is entered. The user is told extra verification is needed, never *why* — the reason goes only to the
account owner's inbox and the audit row. The check **fails open**: it runs after the password has
already been accepted, so failing closed would break logins to protect nothing.

**Rate limiting and lockout.** `RateLimitFilter` (Bucket4j) applies a general per-caller budget and
answers `429` with `Retry-After`. Separately, per-account lockout persists after repeated failures
until an administrator unlocks it. Both are **per-instance today** — see
[FUTURE-ENHANCEMENTS §2.4](FUTURE-ENHANCEMENTS.md#24--move-per-instance-security-state-off-the-heap).

### 7.11 Public endpoints

Two lists in `Constants.java` must stay in lockstep:

- **`PUBLIC_URLS`** — `permitAll` matchers in the filter chain.
- **`PUBLIC_ROUTES`** — prefixes `CustomAuthFilter.shouldNotFilter()` skips.

> **Gotcha:** if a route is permitted by the chain but *not* skipped by the filter, a stale
> `Authorization: Bearer` header from the client makes the filter try — and fail — to parse a token
> before the request reaches the public controller.

Public surface: registration, login, SMS/TOTP login completion, account/password verification,
password reset, token refresh, profile images, OAuth2 routes, and Actuator (`health` and `info` only,
with `show-details: never`).

---

## 8. API reference

**Base URL:** `http://localhost:8080` local · **Auth:** `Authorization: Bearer <access_token>`

### 8.1 The envelope

Every endpoint — success or error — returns:

```json
{
  "timeStamp": "12:01:33.123",
  "statusCode": 200,
  "status": "OK",
  "message": "Human-readable summary",
  "data": { "...": "endpoint-specific payload" }
}
```

Errors add `reason` (the **only** field a client should render) and, outside production,
`devMessage`. `data` and `message` are omitted on errors — `@JsonInclude(NON_DEFAULT)` drops empty
fields.

**Pagination.** List endpoints take `?page=` (0-based) and `?size=`, and return the page plus
`…TotalElements` / `…TotalPages`. Sizes are clamped server-side, and responses report the size the
server **actually applied** — otherwise a client asking for 5000 rows would compute its page count
from a size the query never used and offer pages that do not exist.

### 8.2 Authentication & account — `/user`

| Method | Path | Auth | Body | Returns (`data`) |
|---|---|---|---|---|
| POST | `/user/register` | Public | `User { firstName, lastName, email, password }` | `201 { user }` |
| POST | `/user/login` | Public | `LoginForm { email, password }` | `{ user, access_token, refresh_token }` — **or** `{ user, challenge }` (TOTP) — **or** `{ user }` + code sent |
| GET | `/user/verify/code/{email}/{code}` | Public | — | `{ user, access_token, refresh_token }` |
| GET | `/user/verify/account/{key}` | Public | — | `{ message }` (activates) |
| GET | `/user/refresh/token` | Refresh token | — | rotated `{ user, access_token, refresh_token }`; `400` if the header is missing |
| GET | `/user/resetpassword/{email}` | Public | — | `{ message }` |
| GET | `/user/verify/password/{key}` | Public | — | `{ user }` |
| PUT | `/user/new/password` | Public | `NewPasswordForm { userID, newPassword, confirmPassword }` | `{ message }` |
| GET | `/user/image/{fileName}` | Public | — | raw PNG bytes |
| GET | `/user/profile` | Authenticated | — | `{ user, events, eventsTotalElements, eventsTotalPages, roles }` |
| GET | `/user/events?page&size` | Authenticated | — | `{ events, eventsTotalElements, eventsTotalPages }` |
| PATCH | `/user/update` | `UPDATE:*` | `UpdateForm` | `{ user, events, roles }` |
| PATCH | `/user/update/password` | `UPDATE:*` | `UpdatePasswordForm` | `{ …, access_token, refresh_token }` (revokes other sessions) |
| PATCH | `/user/update/settings` | `UPDATE:*` | `SettingsForm { enabled, notLocked }` | `{ user, events, roles }` |
| PATCH | `/user/update/togglemfa` | `UPDATE:*` | — | `{ user, events, roles }` (SMS 2FA; requires a phone) |
| PATCH | `/user/update/image` | `UPDATE:*` | multipart `image` | `{ user, events, roles }` |

> The old `PATCH /user/update/role/{roleName}` is **gone** — it once let any authenticated user
> reassign their own role. Role changes are admin-only.

### 8.3 MFA — `/user/totp`

| Method | Path | Auth | Body | Returns |
|---|---|---|---|---|
| POST | `/user/totp/setup` | Authenticated | — | `{ secret, otpauthUri, qrCode }` |
| POST | `/user/totp/enable` | Authenticated | `{ code }` | `{ user, recoveryCodes }` (shown once) |
| POST | `/user/totp/disable` | Authenticated | `{ code }` | `{ user }` |
| GET | `/user/totp/status` | Authenticated | — | `{ enabled, recoveryCodesRemaining }` |
| POST | `/user/verify/totp` | Public | `{ challenge, code }` | `{ user, access_token, refresh_token }` |

`/user/totp/**` is explicitly `authenticated()` with no staff authority so any user can secure their
own account. `verify/totp` is public because the caller is mid-login — the server-side `challenge` is
its boundary.

### 8.4 Sessions & connected accounts — `/user/sessions`

| Method | Path | Returns |
|---|---|---|
| GET | `/user/sessions` | `{ sessions, currentFamily }` |
| DELETE | `/user/sessions/{family}` | Revoke one |
| DELETE | `/user/sessions` | "Log out everywhere else" |
| POST | `/user/sessions/logout` | Ends *this* session server-side |
| GET | `/user/sessions/providers` | `{ providers }` linked to the caller's own account |
| DELETE | `/user/sessions/providers/{provider}` | Disconnect one — refused if it is the last sign-in method |

All authenticated. `currentFamily` is the caller's own `sid`, so the SPA can badge "this device" and
exclude it from mass logout. Every route is scoped to the JWT principal — the account is never taken
from the request — so there is no way to express "unlink somebody else's provider". The response
carries no `provider_subject`: that is the durable key the find-or-create matches on, and the UI has
no use for it.

`POST /logout` exists because without it, signing out cleared the SPA's `localStorage` and told the
server nothing — the session stayed live for its full five days.

### 8.5 Federated login — `/oauth2`

| Method | Path | Auth | Returns |
|---|---|---|---|
| GET | `/oauth2/providers` | Public | The configured providers, for the login buttons |

The dance itself is handled by Spring Security: `GET /oauth2/authorization/{provider}` starts the
flow, `GET /login/oauth2/code/{provider}` is the callback.

### 8.6 Administration — `/admin/user`

All routes require `UPDATE:USER` **or** `UPDATE:ROLE`; the `PATCH`es are stricter. For a
`ROLE_ORGANIZATION_ADMIN` everything is scoped to shared organizations.

| Method | Path | Auth | Returns |
|---|---|---|---|
| GET | `/admin/user/list?page&size&searchTerm` | `UPDATE:USER`/`UPDATE:ROLE` | `{ user, users, usersTotalElements, usersTotalPages, page, pageSize, roles }` |
| GET | `/admin/user/{id}` | same | `{ user, selectedUser, events, …, roles }` |
| GET | `/admin/user/{id}/events?page&size` | same | `{ events, eventsTotalElements, eventsTotalPages }` |
| PATCH | `/admin/user/{id}/role/{roleName}` | **`UPDATE:ROLE`** | Forbids self-targeting |
| PATCH | `/admin/user/{id}/settings` | **`UPDATE:USER`** | Forbids self-targeting |
| PATCH | `/admin/user/{id}/update` | **`UPDATE:USER`** | The `{id}` path variable **overwrites any body id** |

> `user` = the calling admin (for the navbar); `selectedUser` = the managed user. Mutations are
> audited against the **target**.

### 8.7 Security dashboard — `/admin/security`

| Method | Path | Auth |
|---|---|---|
| GET | `/admin/security/overview?days&suspiciousPage&suspiciousSize&restrictedPage&restrictedSize` | `UPDATE:USER`/`UPDATE:ROLE` |

`days` defaults to 7 and is **clamped server-side to 1–90** — it is caller-supplied input to a set of
aggregate queries, and an unclamped `?days=100000` is a denial of service that needs no
vulnerability. The two growing tables page **independently**, each with its own index and count, so
an admin working down the lockout list does not lose their place by stepping through flagged sign-ins
above it. Both sizes default to 50, clamped to 1–100.

**The whole screen is one response on purpose** — six endpoints would give six different instants of
the same database with no way to tell which panel was stale.

`overview` contains: `windowDays`/`scoped`; `eventCounts` (**every tracked type present, at zero if
unused**, so "0 suspicious logins" is a statement rather than a missing tile); `suspiciousLogins`
(newest first, with the `detail` string naming which signals fired); `trend` (per-day outcomes,
**gap-filled**, so a quiet day still appears); `restrictedAccounts` (locked *and* not-enabled
together — both present to the help desk as "I can't get in"); `mfaAdoption`; and
`activeSessions`/`accountsWithSessions`, meant to be read as a ratio.

### 8.8 Services catalog — `/admin/services`

| Method | Path | Returns |
|---|---|---|
| GET | `/admin/services/list` | `{ user, services }` — **includes retired** |
| GET | `/admin/services/get/{serviceId}` | `{ user, service }` |
| POST | `/admin/services/create` | `201 { user, service }` (submitted id ignored) |
| PUT | `/admin/services/update/{serviceId}` | `{ user, service }` |
| PATCH | `/admin/services/{serviceId}/active/{active}` | Retire / reinstate |

All `UPDATE:USER`/`UPDATE:ROLE`. Browsing stays on the public path (`GET /customer/invoice/new`),
which returns **active services only**.

> **Retire, never delete.** There is deliberately no `DELETE`. Invoices copy a service's name and
> price into their own line items when raised, so removing the row would not corrupt historical
> invoices — but it would erase the catalog's own history and turn "bring that offering back" into a
> retyping exercise.

### 8.9 Customers & invoices — `/customer`

`GET` needs `READ:USER`/`READ:CUSTOMER`; `POST` needs `UPDATE:USER`/`UPDATE:CUSTOMER`; `PUT` needs an
`UPDATE:*`.

| Method | Path | Returns |
|---|---|---|
| GET | `/customer/stats` | `{ user, stats }` |
| GET | `/customer/list?page&size` | `{ user, page, stats }` (size default 20) |
| GET | `/customer/get/{customerId}` · `/customer/search?name&page&size` | `{ user, … }` |
| POST | `/customer/create` · PUT `/customer/update/{customerId}` | `{ user, customer(s) }` |
| GET | `/customer/download/report` | XLSX attachment |
| GET | `/customer/invoice/list?page&size` · `/get/{id}` · `/new` | `{ user, … }` |
| POST | `/customer/invoice/create` · `/addtocustomer/{customerId}` | `201 { user, … }` |
| PUT | `/customer/invoice/{invoiceId}/addtocustomer/{customerId}` | Attach an **existing** draft |
| PATCH | `/customer/invoice/update/{invoiceId}` | Edit status, dates, amounts |
| GET | `/customer/invoice/download/report` | XLSX attachment |

`/admin/analytics/summary` · `/customers` · `/invoices` serve the admin reporting surface, gated on
`UPDATE:USER`/`UPDATE:ROLE`.

### 8.10 Examples

```bash
# Register
curl -X POST http://localhost:8080/user/register \
  -H "Content-Type: application/json" \
  -d '{"firstName":"John","lastName":"Doe","email":"john@example.com","password":"P@ssw0rd123"}'

# Login
curl -X POST http://localhost:8080/user/login \
  -H "Content-Type: application/json" \
  -d '{"email":"eve.admin@tessera.dev","password":"TesseraDemo@1"}'

# Call a protected endpoint
curl http://localhost:8080/user/profile -H "Authorization: Bearer <access_token>"

# Rotate tokens (note: send the REFRESH token)
curl http://localhost:8080/user/refresh/token -H "Authorization: Bearer <refresh_token>"

# Admin: reassign a role (needs UPDATE:ROLE)
curl -X PATCH http://localhost:8080/admin/user/23/role/ROLE_MODERATOR \
  -H "Authorization: Bearer <access_token>"
```

### 8.11 How a client should branch

| Status | Meaning | What to do |
|---|---|---|
| `2xx` | Success | Read `data`; it usually embeds the authenticated `user` alongside the payload |
| `400` | Bad input / business rejection / wrong credentials / not-found / duplicate | Show `error.error.reason`. Do **not** retry automatically |
| `401` | Token missing / expired / invalid | **Silent refresh-and-retry**, then `/login` if that fails. Never show the raw reason |
| `403` | Authenticated but not authorized | A hard no. Surface `reason` and stop |
| `500` | Server fault | Show a generic apology; `reason` is already generic |

**The 401 path auto-heals**, so a correctly-behaving SPA almost never surfaces one — it surfaces the
*post-refresh* result.

---

## 9. Database

**MySQL 8** · default schema `db2` locally, `db3` on Aiven.

### 9.1 Two persistence mechanisms

| Domain | Access | Schema owner | Tables |
|---|---|---|---|
| **Identity / auth** | `JdbcTemplate` + hand-written SQL | **`schema.sql`** | `users`, `roles`, `userroles`, `events`, `userevents`, `accountverifications`, `resetpasswordverifications`, `twofactorverifications`, `oauthproviderlinks`, `organizations`, `userorganizations`, `totpcredentials`, `totprecoverycodes`, `mfachallenges`, `refreshsessions` |
| **Business** | JPA / Hibernate | **Hibernate** `ddl-auto` | `Customer`, `Invoice`, `Services`, `invoiceserviceitems` |

The identity layer wants precise, auditable SQL and predictable column names; the CRUD-heavy business
domain is a better fit for entity mapping. `User` is a **plain POJO** mapped by `UserRowMapper`, not
an entity.

### 9.2 Entity-relationship map

```
                              ┌───────────────────────────────────┐
                              │              users                │
                              │ (id, email, password, enabled,    │
                              │  non_locked, using_mfa,           │
                              │  using_totp, password_changed_at) │
                              └───────────────────────────────────┘
        ┌──────────┬──────────────┬────────────┬───────────────┬──────────────┐
        │1 (UNIQUE)│1             │1..*        │*  (M:N)       │1..*          │1
   ┌─────────┐ ┌──────────┐ ┌────────────┐ ┌──────────────────┐ ┌───────────────┐
   │userroles│ │ *verif.* │ │ userevents │ │ userorganizations│ │refreshsessions│
   └────┬────┘ │  tables  │ └─────┬──────┘ └────────┬─────────┘ └───────────────┘
        │*     └──────────┘       │*                │*          ┌───────────────┐
   ┌─────────┐              ┌──────────┐    ┌──────────────┐    │totpcredentials│
   │  roles  │              │  events  │    │organizations │    │totprecovery…  │
   └─────────┘              └──────────┘    └──────────────┘    │mfachallenges  │
                                                                └───────────────┘
   oauthproviderlinks ──*──1 users

   Business (JPA):  Customer 1──* Invoice 1──* invoiceserviceitems (element collection)
```

- A user has **exactly one** role (`userroles` has `UNIQUE(user_id)`).
- At most one row per user in each verification table, `totpcredentials` and `mfachallenges`; **many**
  recovery codes, audit events, OAuth links, memberships and refresh sessions.

### 9.3 Key tables

**`users`** — the account record. `email` is `UNIQUE` and is the login identifier; `password` is a
BCrypt hash (null for federated-only accounts); `password_changed_at` invalidates tokens issued
before it; `image_url` is `VARCHAR(512)` — **widened from 255** because identity providers return
longer URLs and MySQL outside strict mode silently truncates, so the row writes, the login succeeds,
and the only symptom is a broken image.

**`roles`** — the seven-role catalogue, `permission` a comma-separated `RESOURCE:ACTION` list.
**Ids are pinned 1–7** — see [IMPLEMENTATION-HISTORY §4.7](IMPLEMENTATION-HISTORY.md#47-seeded-role-ids-drifted-between-databases) for why.

**`userevents` / `events`** — the audit log and its type catalogue, the latter guarded by a `CHECK`
constraint (`CK_Events_Type`) over 16 values: `LOGIN_ATTEMPT`, `LOGIN_ATTEMPT_SUCCESS`,
`LOGIN_ATTEMPT_FAILURE`, `PROFILE_UPDATE`, `PROFILE_PICTURE_UPDATE`, `ROLE_UPDATE`,
`ACCOUNT_SETTINGS_UPDATE`, `PASSWORD_UPDATE`, `MFA_UPDATE`, `FEDERATED_LOGIN`, `TOTP_ENROLLED`,
`TOTP_DISABLED`, `RECOVERY_CODE_USED`, `SESSION_REVOKED`, `TOKEN_REUSE_DETECTED`, `SUSPICIOUS_LOGIN`.

**`refreshsessions`** — the stateful half of the token model. `family` is one logical session (one
device login), stable across rotations and the unit the Security Center lists and revokes; `jti` is
one concrete refresh token. Rows are **retained** after revocation or supersession, because reuse
detection needs to recognise a replayed old token; `expires_at` bounds the window.

**`oauthproviderlinks`** — `UNIQUE(provider, provider_subject)` is what makes federated find-or-create
idempotent.

**`organizations` / `userorganizations`** — the scoping unit, with an `active` flag on membership. The
scope check honors only **active** memberships, so deactivating a row immediately removes a user from
an org admin's reach without destroying history.

**`totpcredentials` / `totprecoverycodes` / `mfachallenges`** — `confirmed` flips true only after the
user proves possession, so an unconfirmed secret can never satisfy a login. `mfachallenges` is the
security linchpin ([§7.10](#710-mfa-federation-and-account-security)).

**`Services`** — named with a capital because `Service` collides with a Spring stereotype. `active`
is how a service is retired; the seed deliberately **does not overwrite `active`**, so a retired
service stays retired across a re-run.

**`invoiceserviceitems`** — an `@ElementCollection` table with **no surrogate `id`**. Hibernate owns
it; do not redeclare it in `schema.sql`.

### 9.4 Audit-event triggers

**One pipe for every event.** Controllers publish `NewUserEvent(email, EventType)` via
`ApplicationEventPublisher`; `NewUserEventListener` is the single sink and enriches every row
uniformly from the **live HTTP request** — parsed User-Agent into `device`, resolved client IP into
`ip_address`. There is no per-call-site context plumbing.

> **Gotcha — context follows the request in flight, not the account owner.** The `device`/`ip_address`
> on `TOKEN_REUSE_DETECTED` reflect **whoever presented the replayed token**, because the reuse
> handler runs inside that refresh request. That is exactly what you want for forensics.

Audit writes are **non-fatal by design** — the listener swallows and logs failures. It is deliberately
not `@Async`, because it reads the request-scoped `HttpServletRequest`.

### 9.5 Reference data

Seeded idempotently by `schema.sql`: the seven roles (ids pinned 1–7, permissions as in
[§7.5](#75-roles-and-capabilities)), the 16 event types, 12 services with pinned ids, and two demo
organizations — **Tessera** and **Acme Partners**, the second existing specifically to demonstrate
the scope boundary.

On the `dev` profile, `DemoDataSeeder` additionally inserts one user per role (password
`TesseraDemo@1`) and sample audit events. It is idempotent and **never runs under `prod`**.

### 9.6 Schema evolution

There is no migration tool — Flyway was removed on purpose. The schema has two owners
([§9.1](#91-two-persistence-mechanisms)), and which you touch depends on the table.

`schema.sql` is **idempotent and non-destructive**: every statement is `CREATE TABLE IF NOT EXISTS`
or `INSERT … ON DUPLICATE KEY UPDATE`, FKs are inlined rather than added by separate `ALTER`s so it
stays re-runnable, and there are **deliberately no `DROP`s**.

**Adding an identity column or table** — see [§4.3](#43-changing-the-schema). The critical point:
`CREATE TABLE IF NOT EXISTS` will not alter an existing table, so you must both add the column to the
`CREATE` block *and* run a one-off `ALTER` on every live database.

**Adding or altering a JPA entity.** In dev, `ddl-auto: update` creates the column on next boot. But
**prod runs `ddl-auto: validate`** — Hibernate refuses to start if a mapped column is missing from
the hand-applied schema. So a JPA change is **not done** until `schema.sql` carries the generated DDL
too.

`JpaSchemaSyncTest` is the build-time guard and the reproducible DDL source: it drives Hibernate's
**offline** schema export (no database connection, dialect pinned to MySQL,
`globally_quoted_identifiers=true` mirrored) into `target/generated-jpa-schema.sql`, then asserts
`schema.sql` contains every backtick-quoted table and column Hibernate maps. A new entity field
without a matching `schema.sql` update **fails the build here**, not at the next prod deploy.

Workflow: run the test, open `target/generated-jpa-schema.sql`, copy the new `CREATE TABLE`/column
into `schema.sql` with FKs inlined and stable constraint names, re-run until green.

**Rollback.** There is **no down-migration mechanism** — the deliberate trade-off of dropping Flyway.
Roll back by hand, code-first: a compensating `ALTER … DROP COLUMN` or `DROP TABLE`, run manually and
kept in an ops note rather than in `schema.sql` (which has no `DROP`s). Reverting a JPA change means
reverting the entity and the DDL together. **Take a dump first**, and confirm any destructive
operation with a human before running it.

### 9.7 Which MySQL server?

The app runs against three different servers, and **they do not behave identically.**

| `DB=` | Connects to | Starts a container? | `lower_case_table_names` | Table names |
|---|---|---|---|---|
| **`native`** *(default)* | The host's own MySQL (e.g. Windows MySQL80) | **No** | `1` | case-**IN**sensitive |
| `local` | A Docker MySQL container | Yes | `0` | case-**sensitive** |
| `aiven` | Aiven cloud MySQL over TLS | No | `0` | case-**sensitive** |

Two hazards follow, both of which cost real hours:

**Port 3306 shadowing.** Two servers cannot both own the port. Running `DB=local` while a native
MySQL exists launches an empty container that seizes 3306 and *shadows* your real data — which looks
exactly like the database was wiped. **The tell:** capitalized `Customer`/`Invoice`/`Users` tables in
your client mean you are on a case-sensitive server; all-lowercase means native. Nothing is lost —
stop the container, ensure MySQL80 is running, restart with `DB=native`. Full write-up in
[IMPLEMENTATION-HISTORY §4.2](IMPLEMENTATION-HISTORY.md#42-all-my-data-vanished--docker-mysql-shadowing-native-mysql).

**Casing.** Resolved as of 2026-07-29: every query now matches `schema.sql`'s exact spelling — the
JDBC half uses lowercase `users`, the JPA half uses quoted-capital `` `Customer` ``/`` `Invoice` `` —
and `SqlTableCaseConsistencyTest` guards it offline. The compatibility views that used to bridge this
on Aiven `db3` are now droppable. Do not reintroduce mixed casing.

**Migrating native → Aiven** (how `db3` was created):

```bash
# 1. Dump (use --result-file so PowerShell does not corrupt the encoding)
mysqldump -u root -p --single-transaction --no-tablespaces --skip-column-statistics \
  --set-gtid-purged=OFF --default-character-set=utf8mb4 --result-file=native_db2.sql db2

# 2. Load into a NEW Aiven database. Two Aiven quirks: `source` does not work in -e, so pipe via
#    stdin; and Aiven enforces sql_require_primary_key=ON, which rejects invoiceserviceitems
#    (no PK) unless relaxed for the session.
mysql -h <aiven-host> -P <port> -u avnadmin -p<pw> --ssl-mode=REQUIRED \
  --init-command="SET SESSION sql_require_primary_key=0" db3 < native_db2.sql
```

---

## 10. Testing

Coverage is **modest but real** — every headline security claim has at least one dedicated test. What
remains uncovered is a specific, nameable shape: **nothing exercises the real filter chain, and
nothing exercises a real browser.**

### 10.1 Inventory

**126 backend tests across 23 suites** and **87 frontend specs across 8 files** (verified 2026-08-02).
Only one backend class needs a database.

| Suite | Tests | What it locks in |
|---|---:|---|
| `SessionServiceImplTest` | 4 | **Refresh rotation & replay detection** — the happy path, plus superseded/revoked replays revoking the whole family without rotating |
| `TotpServiceImplTest` | 5 | **TOTP challenge binding** — identity comes from the challenge, never the request; a wrong code refuses *without* burning the challenge; recovery codes validate-and-consume atomically |
| `LoginRiskServiceImplTest` | 12 | Anomaly detection, **both failure directions** — false positives matter as much as true ones |
| `SecurityDashboardServiceImplTest` | 14 | Window clamping, pagination clamping, zero-filled counters, gap-filled trend, empty scope failing closed *before* any query |
| `AdminUserControllerOrgScopeTest` | 5 | Org scoping on **reads as well as writes**; platform admins never scope-checked; non-enumerating 403 |
| `AnalyticsControllerOrgScopeTest` | 8 | Scoped analytics — assertions in pairs, because calling the *unscoped* variant is the bug |
| `AnalyticsControllerSecurityTest` | 3 | The `/admin/analytics/**` authority gate |
| `RequestUtilsIpAddressTest` | 10 | `X-Forwarded-For` trust, forgery cases included |
| `AuthDiagnosticsLoggerTest` | 9 | Console-only RBAC diagnostics stay off the client response |
| `CapabilityCatalogTest` | 17 | 403s name the blocked capability without leaking record existence |
| `ErrorDetailScrubberTest` | 6 | Prod error bodies carry no internal detail |
| `FederatedIdentityLinkTest` / `UnlinkTest` | 5 / 5 | Link refusal for an already-owned identity; both halves of the last-sign-in-method guard |
| `UserControllerLoginEnumerationTest` | 2 | Unknown-email and wrong-password failures are byte-identical bar the timestamp |
| `UserControllerBruteForceLockTest` | 2 | Per-account lockout |
| `AdminUserControllerTest` | 3 | Path id is authoritative; self-targeting refused |
| `CustomerServiceImplTest` | 5 | `createdAt` stamping, invoice numbering, not-found → `ApiException` |
| `EventServiceImplTest` / `NewUserEventListenerTest` | 2 / 2 | Audit recording; **a failing audit write must not break login** |
| `GlobalExceptionHandlerTest` | 4 | The envelope; a 500 never leaks its cause |
| `JpaSchemaSyncTest` / `SqlTableCaseConsistencyTest` | 1 / 1 | Offline schema-drift and table-casing guards |
| `AngularSpringBootFullStackApplicationTests` | 1 | `contextLoads` — needs a live MySQL |

Frontend: `user.service.authority.spec.ts` (20 — exact-not-prefix authority matching, expiry beating
a privileged claim, memo invalidation across rotation, six shapes of corrupt token that must grant
nothing *without throwing*), `token.interceptor.spec.ts` (15 — single-flight refresh, retry replaying
method/URL/body, parked requests failing rather than hanging), `command-palette` (12),
`has-authority.directive` (9), `authentication.guard` (9), `admin.guard` (7), `capability.guard` (7),
`page-size-select` (8).

> **Five of these are true regression tests** — confirmed to fail against the pre-fix code. They
> cover three defects found while writing them: a failed refresh left concurrently-parked requests
> hanging forever, an unparseable token threw *out of* `authenticationGuard` instead of redirecting,
> and the interceptor's public-route check matched substrings of the whole URL, so
> `/customer/search?name=login` was sent unauthenticated.

### 10.2 Running them

| Goal | Command |
|---|---|
| Whole backend suite | `./mvnw test` (Windows: `mvnw.cmd test`) |
| One class / one method | `./mvnw test -Dtest=CustomerServiceImplTest[#methodName]` |
| Everything **except** the DB-bound boot test | `./mvnw test -Dtest='!AngularSpringBootFullStackApplicationTests'` |
| Frontend | `npm test` · `npm run lint` · `npm run format:check` |

> **`contextLoads` needs a database.** There is no `src/test/resources/application*.yml` override, so
> it connects to the dev datasource — a live local MySQL with `schema.sql` applied. If MySQL is down,
> that one test errors while the rest pass.

### 10.3 Writing tests

**Unit test (mock the collaborators).** The interface-plus-`Impl` convention exists so collaborators
are mockable seams. `CustomerServiceImplTest` is the reference: the service is a plain object, every
collaborator is a Mockito `@Mock`, wired by `@InjectMocks`. No Spring, no database.

```java
@ExtendWith(MockitoExtension.class)              // Mockito, not Spring — no context boot
class CustomerServiceImplTest {
    @Mock private CustomerRepo customerRepo;
    @Mock private NamedParameterJdbcTemplate jdbcTemplate;
    @InjectMocks private CustomerServiceImpl customerService;
}
```

Three techniques worth copying: `thenAnswer(inv -> inv.getArgument(0))` for a `save` mock that echoes
its argument back (so you can assert on generated timestamps and numbers); `ArgumentCaptor` +
`verify(...).save(captor.capture())` to assert on the exact object handed to the repo; and
`assertThrows(ApiException.class, …)` against `findById(...).thenReturn(Optional.empty())`.

**Slice test (standalone MockMvc + the real advice).** When you need real HTTP-layer behaviour —
validation, status codes, the envelope — but not a context, filter chain or datasource:

```java
mockMvc = MockMvcBuilders.standaloneSetup(new ProbeController())
        .setControllerAdvice(new GlobalExceptionHandler())   // the real advice
        .build();
```

Standalone setup wires a validator, so `@Valid` produces a genuine
`MethodArgumentNotValidException` — the exact exception the handler claims to catch.

> **`@WebMvcTest` is deliberately not used**, to avoid loading any Spring context including security
> auto-config. The trade-off is that these tests do not exercise the real `SecurityConfig` matchers
> or `CustomAuthFilter` — an explicit gap.

**Where assertions point.** Keep business logic in the **service** layer so it is unit-testable
there. That is the project's standing rule and the reason these tests can exist at this level at all.

### 10.4 Other gates

| Gate | Command |
|---|---|
| OWASP dependency CVE scan (`failBuildOnCVSS=7`) | `./mvnw org.owasp:dependency-check-maven:check` |
| Dependency version report | `./mvnw versions:display-dependency-updates` |
| Frontend lint (**gates in CI**) | `npm run lint` |
| `npm audit --audit-level=high` | gates in CI |

**Manual smoke test:** sign in as `eve.admin@tessera.dev`, open the admin dashboard, enroll TOTP in
the Security Center, and check the audit log on the profile page. This is still the only coverage for
the federation, TOTP-enrollment, session-management and admin *flows* end to end.

### 10.5 Known gaps

| Area | Status | Gap |
|---|:--:|---|
| `schema.sql` ↔ JPA drift | 🔄 | **Offline only.** No prod-profile `validate` boot has ever run against a `schema.sql`-only database |
| Context wiring | 🔄 | `contextLoads` boots but asserts nothing; needs live MySQL; not hermetic |
| Frontend HTTP cache | ❌ | The one unspecced interceptor — and its invalidation rules are exactly the logic that silently serves one user another's data |
| Real `SecurityConfig` matchers / `CustomAuthFilter` | ❌ | Slice tests bypass the chain by design, so matcher **ordering** — the thing most likely to break — is unverified |
| HTTP-level integration | ❌ | No `TestRestTemplate`/Testcontainers layer |
| End-to-end | ❌ | Seams pass CI: interceptor ↔ backend, OAuth round-trip, federated link flow |

> **Standing honesty rule.** When coverage improves, update these counts **from the code**, never
> from another doc. "Near-zero tests" is stale; "modest but real" is accurate; do not let either
> drift into "well-tested" without the tests to back it.

---

## 11. Deployment

### 11.1 Options

| Target | How | Notes |
|---|---|---|
| Local full stack | `start.sh ENV=docker` | App + MySQL containers, production-like |
| **AWS** (live) | `.github/workflows/deploy.yml` → ECR + **ECS Fargate** | Secrets in AWS Secrets Manager; DB = Aiven. **Procedure: [aws/RUNBOOK.md](../aws/RUNBOOK.md)** |
| **GCP** | `.github/workflows/deploy-gcp.yml` → Artifact Registry + **Cloud Run** | Secrets in Secret Manager; Cloud Build + Cloud SQL boilerplate included in `gcp/` |
| Azure | `azure-pipelines.yml` → ACR + App Service | Earlier path; see [IMPLEMENTATION-HISTORY §6](IMPLEMENTATION-HISTORY.md#6-legacy-azure-deployment-reference) |
| Railway / Render / Fly.io | the Dockerfile | Set env vars in the platform; use a managed DB |

The single deployable artifact is the **Docker image**: a slim JRE running one JAR containing both
the API and the compiled SPA.

### 11.2 The image

```
Stage 1  node:22-alpine         → npm ci && npm run build → dist/tesseraapp/browser/
Stage 2  maven:3.9-temurin-21   → copy the Angular dist into src/main/resources/static/
                                   mvn package -DskipTests -Pprod
Stage 3  eclipse-temurin:21-jre-alpine
           - runs as a non-root user
           - EXPOSE 8080 · HEALTHCHECK → GET /actuator/health
           - ENTRYPOINT java -Dspring.profiles.active=prod -jar app.jar
```

> **`CONTAINER_PORT` is the *in-container* port, not the published one.** The healthcheck and
> `EXPOSE` are hard-wired to 8080; Compose maps the host side separately via `${APP_PORT:-8090}:8080`.
> Changing it desyncs the baked-in healthcheck — leave it at 8080 inside the container.

### 11.3 Cloud principles

- **All config is environment variables.** Never ship a `.env`.
- **Use the `prod` profile** so a missing variable fails fast.
- **Use a managed database** with `useSSL=true&requireSSL=true`.
- **Apply `schema.sql` once**, by hand, before first launch — the image never runs it.
- **Health probe → `GET /actuator/health`** (the only health endpoint exposed; details hidden).
- **Set `FORWARD_HEADERS_STRATEGY=framework` and `TRUSTED_PROXY_COUNT` correctly** behind any load
  balancer ([§3.2](#32-environment-variable-reference)).
- **Set `IMAGE_STORAGE_TYPE=s3`** for any multi-instance or ephemeral-filesystem deployment.

### 11.4 Pre-deployment checklist

- [ ] `SPRING_ACTIVE_PROFILES=prod`
- [ ] Managed MySQL provisioned; schema created; **`schema.sql` applied**
- [ ] `useSSL=true&requireSSL=true` in `SPRING_DATASOURCE_URL`
- [ ] Strong `JWT_SECRET` set via the platform (≥32 chars, not the placeholder)
- [ ] Mail and any OAuth/Twilio secrets set via the platform
- [ ] `UI_APP_URL` set to the public origin (drives CORS **and** email links)
- [ ] `FORWARD_HEADERS_STRATEGY=framework`, `TRUSTED_PROXY_COUNT` matching the topology
- [ ] Provider callback URLs registered for the deployed host
- [ ] Health probe → `GET /actuator/health`

### 11.5 Troubleshooting a deploy

The cloud image always runs **`prod`**, which supplies **no fallbacks** — by design. Most "won't
start" reports are a required variable that was not set. Start with the container's own logs; the
prod profile fails fast and loud, so the first stack trace almost always names the cause.

**Container will not start**

| Symptom | Cause | Fix |
|---|---|---|
| `JWT_SECRET is not set … Refusing to start.` | prod, no `JWT_SECRET` | `openssl rand -base64 48` |
| `JWT_SECRET is still the dev/placeholder value` | the `.env.example` placeholder leaked into prod | Use a unique random secret — the guard rejects both known literals |
| `JWT_SECRET is too short (N chars)` | under 32 chars | ≥32, 64+ preferred for HMAC512 |
| `Could not resolve placeholder '<NAME>'` | a required var is absent | Set it; confirm it reached the container |
| `Schema-validation: missing table [Customer]` | `ddl-auto: validate` found drift, or `schema.sql` was never applied | Apply the schema before first launch; reconcile entity vs column names |
| `Circular placeholder reference '<NAME>'` | a self-referential `X: ${X}` was reintroduced | Remove it ([§3.6](#36-configuration-gotchas)) |
| Container exits with **no** app log | wrong arch/base image, bad `ENTRYPOINT`, or the port is taken | `docker logs <id>` |
| HEALTHCHECK flaps though the app booted | The probe `wget`s `localhost:8080` **inside** the container | The app must listen on 8080 there regardless of the host mapping; a cold DB can push boot past the 60 s grace |

**Database connectivity.** A container that boots and then times out is almost always a **network**
problem, not credentials — credentials fail fast with an auth error, the network path fails *slowly*
with a timeout.

| Symptom | Cause | Fix |
|---|---|---|
| `Communications link failure` → `Connection refused` | wrong host/port, or the DB isn't listening | Verify `SPRING_DATASOURCE_URL` |
| `Communications link failure` + connect timeout | egress blocked | Allowlist the app's outbound IP (Aiven ▸ Allowed IPs, RDS security group, Cloud SQL authorized networks) |
| `SSL connection … is required` | managed DB with `useSSL=false` | Override the whole URL with the TLS variant |
| `Public Key Retrieval is not allowed` | MySQL 8 `caching_sha2_password` over a non-TLS connection | Enable TLS. Do **not** "fix" it with `allowPublicKeyRetrieval=true` on a managed DB |
| First request after idle hangs ~30 s | free-tier DB scaled to zero | Raise the platform's startup grace |

> Aiven assigns a **non-standard port** — read host, port, user and password from its dashboard.
> The Compose service's `useSSL=false&allowPublicKeyRetrieval=true` is for the throwaway local
> container only; never copy it to a managed database.

**A repeatable debug order, cheapest first:** read the container logs → confirm the image actually
changed (a pinned stale digest looks like "my fix did nothing") → reproduce the prod image locally
with `docker run --env-file ./prod.env` → probe `/actuator/health` → verify the env vars landed
(exact names — `SPRING_DATASOURCE_URL`, not `DATABASE_URL`) → confirm the schema exists.

### 11.6 Logging

CloudWatch with **7-day retention**, driven by env vars: `LOG_LEVEL_ROOT`, `LOG_LEVEL_APP`,
`LOG_LEVEL_SECURITY`, `LOG_LEVEL_SQL`, and `DEBUG_REPORT`.

> ⚠ **`show-sql: false` does not stop SQL logging.** `org.hibernate.SQL` at DEBUG is a separate SLF4J
> path, and `DEBUG_REPORT=true` switches Hibernate to DEBUG and reopens it. Both knobs must be off.

CloudWatch is **not** the cost driver anyone assumes — roughly 371 KB stored against 5 GB free. The
real spend is Fargate; see [FUTURE-ENHANCEMENTS §6.6](FUTURE-ENHANCEMENTS.md#66-what-it-costs-today).

---

## Related documents

- [IMPLEMENTATION-HISTORY.md](IMPLEMENTATION-HISTORY.md) — what was built, and the problem log
- [PHASE-2-IMPLEMENTATION.md](PHASE-2-IMPLEMENTATION.md) — everything delivered since the Phase 1 report (Jul 11 → Aug 3, 2026), with the roadmap scorecard and requirement traceability
- [FUTURE-ENHANCEMENTS.md](FUTURE-ENHANCEMENTS.md) — the backlog and the path to a product
- [flows/](flows/README.md) — click-to-database traces of every major flow
- [aws/RUNBOOK.md](../aws/RUNBOOK.md) — the linear AWS deploy procedure
