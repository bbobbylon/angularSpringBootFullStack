# Configuration Guide

How TesseraApp is configured: where settings come from, every environment variable, the Spring profiles, an annotated walk-through of `application.yml`, and the configuration gotchas that will bite you (with fixes).

> **Audience:** anyone setting the app up locally, in Docker, or in the cloud.
> **See also:** [getting-started.md](getting-started.md) (fastest path to running) · [deployment.md](deployment.md) (Docker/cloud) · [security.md](security.md) (token/auth internals).

---

## Table of contents

1. [Configuration philosophy](#1-configuration-philosophy)
2. [Where configuration comes from (and precedence)](#2-where-configuration-comes-from-and-precedence)
3. [The `.env` file](#3-the-env-file)
4. [Environment variable reference](#4-environment-variable-reference)
5. [Spring profiles](#5-spring-profiles)
6. [`application.yml` annotated](#6-applicationyml-annotated)
7. [Security token settings](#7-security-token-settings)
8. [Configuration gotchas (read this)](#8-configuration-gotchas-read-this)
9. [Secrets handling](#9-secrets-handling)

---

## 1. Configuration philosophy

The backend follows a **12-factor-style, environment-variable-driven** approach:

- **No secrets in source.** Every credential (DB password, JWT secret, mail password, OAuth secrets) is read from an environment variable at runtime.
- **`application.yml` holds structure, not values.** It declares *which* env var feeds each setting (e.g. `password: ${MYSQL_PASSWORD}`), so the same JAR runs unchanged in dev, Docker, and the cloud.
- **Profiles supply environment-specific defaults.** The `dev` profile provides safe local fallbacks so the app boots with almost no setup; the `prod` profile provides none, so a missing variable fails fast instead of silently using a dev default.

---

## 2. Where configuration comes from (and precedence)

At startup Spring merges several property sources. Highest priority wins:

```
1. OS / shell environment variables      (exported by start.sh from .env, or set by the host/cloud)
2. application-{profile}.yml             (application-dev.yml or application-prod.yml)
3. application.yml                        (base config, references ${ENV_VARS})
```

Two consequences worth internalizing:

- **`.env` is not read by Spring directly.** `start.sh` *sources* `.env` into the shell, and Spring then reads those as OS environment variables. (IntelliJ users: point **Run ▸ Edit Configurations ▸ Spring Boot ▸ Environment file** at `.env`.)
- **`SPRING_DATASOURCE_*` overrides the assembled URL.** Spring's relaxed binding maps `SPRING_DATASOURCE_URL/USERNAME/PASSWORD` onto `spring.datasource.*`, which **takes precedence** over the URL `application.yml` assembles from `MYSQL_*`. This is the override path used by Docker (`docker-compose.yml`) and by `start.sh` in `DB=aiven` mode.

---

## 3. The `.env` file

All local configuration lives in a single `.env` file at the project root.

```bash
cp .env.example .env            # Linux / macOS / Git Bash
Copy-Item .env.example .env     # PowerShell
```

- **`.env` is gitignored and must never be committed.** Only the sanitized **`.env.example`** (placeholders only) is tracked, via a `!.env.example` whitelist in `.gitignore`.
- `start.sh` loads it automatically. For a bare `mvn spring-boot:run` (no `start.sh`) you must export the variables yourself — see [gotchas](#8-configuration-gotchas-read-this).

---

## 4. Environment variable reference

Defaults shown are the **`dev`-profile fallbacks** (from `application-dev.yml`). Under the `prod` profile there are **no fallbacks** — every variable the app reads must be supplied.

### Database (MySQL)

| Variable | Purpose | Dev default |
|----------|---------|-------------|
| `MYSQL_HOST` | DB hostname | `127.0.0.1` |
| `MYSQL_PORT` | DB port | `3306` |
| `MYSQL_DATABASE` | Schema name | `db2` |
| `MYSQL_USERNAME` | DB user | `root` |
| `MYSQL_PASSWORD` | DB password | `password` |
| `MYSQL_ROOT_PASSWORD` | Root password for the **Docker** MySQL container | *(required for Docker)* |
| `SPRING_DATASOURCE_URL` | Full JDBC URL — **overrides** the URL built from `MYSQL_*` | *(unset)* |
| `SPRING_DATASOURCE_USERNAME` / `SPRING_DATASOURCE_PASSWORD` | Override credentials | *(unset)* |

### Spring runtime

| Variable | Purpose | Dev default |
|----------|---------|-------------|
| `SPRING_ACTIVE_PROFILES` | Active profile (`dev` \| `prod`) | `dev` |
| `CONTAINER_PORT` | Port Spring Boot listens on | `8080` |
| `APP_PORT` | Host port mapped to the container (Docker mode) | `8090` |

### Authentication

| Variable | Purpose | Dev default |
|----------|---------|-------------|
| `JWT_SECRET` | HMAC-SHA512 signing key for all JWTs — **use a long random value** | a dev-only placeholder |

Generate a strong secret:
```bash
openssl rand -base64 48                                   # Git Bash / macOS / Linux
[Convert]::ToBase64String((1..48 | % { Get-Random -Max 256 }))   # PowerShell
```

### Email (Gmail SMTP)

| Variable | Purpose | Dev default |
|----------|---------|-------------|
| `MAIL_USERNAME` | Gmail address for outgoing mail | *(empty)* |
| `MAIL_PASSWORD` | **Gmail App Password** (16 chars, not your account password) | *(empty)* |
| `MAIL_HOST` | SMTP host | `smtp.gmail.com` |
| `MAIL_PORT` | SMTP port | `587` |
| `VERIFY_EMAIL_HOST` | Base URL embedded in verification/reset email links | `http://localhost:8080` |

> Generate a Gmail App Password at <https://myaccount.google.com/apppasswords>. Account-verification and password-reset emails are sent for real; only the **SMS** 2FA path is stubbed.

### Frontend

| Variable | Purpose | Dev default |
|----------|---------|-------------|
| `UI_APP_URL` | Angular app origin; used for CORS and the federated-login failure redirect | `http://localhost:4200` |

### Profile image storage

Uploaded profile images are written to and served from a single filesystem directory, resolved from `app.image.storage-path` (env `IMAGE_STORAGE_PATH`) in `application.yml:100`. The same value is injected into `WebMvcConfig.java:28` (which serves the files at `/user/profile/image/**`), `UserController.java:108` (read/write + the public `GET /user/image/{file}` path), and `UserRepoImpl.java:142` (the upload write path), so one variable controls where images live and where they are served from.

| Variable | Purpose | Dev default |
|----------|---------|-------------|
| `IMAGE_STORAGE_PATH` | Filesystem directory where uploaded profile images are written and served from | `~/tesseraapp/images` (i.e. `${user.home}/tesseraapp/images`) |

> **Docker / cloud:** point this at a **mounted volume** so images survive container restarts. `docker-compose.yml:30` sets `IMAGE_STORAGE_PATH: /app/data/images` and maps the named `app-images` volume there (`docker-compose.yml:32,39`). Leave it unset locally to fall back to the home-directory default.
> **Note:** unlike the DB/JWT/mail secrets, this default lives in the **base** `application.yml` (not `application-dev.yml`), so the fallback applies under the `prod` profile too — a missing `IMAGE_STORAGE_PATH` will not fail fast; it silently uses `~/tesseraapp/images`. Always set it explicitly in containers.
> **History:** this replaced a brittle hardcoded `~/Downloads/images` path that only worked on the original developer's machine (`UserRepoImpl.java:138`); any doc still citing `~/Downloads/images` (e.g. `developer-guide.md`, `flows/10-profile-and-account.md`) is stale — **the code wins**.

### Reverse proxy / load balancer — required when deployed

Two independent settings, both defaulting to "no proxy". Local runs need neither; **any** load-balanced deployment (ECS behind an ALB, Cloud Run, App Service) needs both, and each fails silently rather than loudly when left at its default.

| Variable | Purpose | Default | Behind one ALB |
|----------|---------|---------|----------------|
| `FORWARD_HEADERS_STRATEGY` | Whether Spring rebuilds the request's **public** scheme/host/port from `X-Forwarded-Proto` / `-Host` / `-Port` (`server.forward-headers-strategy`) | `none` | `framework` |
| `TRUSTED_PROXY_COUNT` | How many proxy hops append to **`X-Forwarded-For`**, i.e. which entry is the real client IP (`app.security.trusted-proxy-count`) | `0` | `1` (`2` with a CDN in front) |

They are easy to confuse and govern different things:

- **`FORWARD_HEADERS_STRATEGY` affects URLs the app *generates*.** `OAuth2ClientConfig` registers every provider with the redirect-URI template `{baseUrl}/login/oauth2/code/{registrationId}`, and `{baseUrl}` is resolved per request. Left at `none` behind a proxy, the container sees a plain-HTTP request to its own task IP and emits `http://10.0.1.23:8080/login/oauth2/code/google` — a URL registered nowhere and reachable by nobody. Every federated sign-in then fails with `redirect_uri_mismatch`, while working perfectly on localhost.
- **`TRUSTED_PROXY_COUNT` affects the client IP the app *reads*.** Left at `0` behind a proxy, `RequestUtils.getIpAddress` ignores `X-Forwarded-For` and returns the load balancer's address for everyone. Two security controls degrade quietly: the rate limiter collapses every user into a single bucket (so the whole tenant throttles as one caller), and the login-anomaly detector's `NEW_NETWORK` signal can never fire, because every sign-in appears to come from the same network. Set it *too high* and an attacker-supplied header entry becomes trusted — which is the exact vulnerability the mechanism exists to prevent, so match the real topology rather than padding it.

`TrustedProxyConfigurer` prints the effective value at startup (`[NET] trusted-proxy-count=…`). Check that line in the deployment log; it is the cheapest confirmation that the setting took effect.

> Both are already set in `aws/task-definition.json`. Mirror them in any other deployment target.

### Federated login (OAuth2 / OIDC) — optional

A provider's login button appears only when its `CLIENT_ID` is set (the SPA discovers configured providers via `GET /oauth2/providers`). With none set, the app logs `Federated login providers configured: none`.

| Variable | Purpose |
|----------|---------|
| `GOOGLE_CLIENT_ID` / `GOOGLE_CLIENT_SECRET` | Google OAuth client |
| `GITHUB_CLIENT_ID` / `GITHUB_CLIENT_SECRET` | GitHub OAuth app |
| `MICROSOFT_CLIENT_ID` / `MICROSOFT_CLIENT_SECRET` | Microsoft (Entra) app registration |
| `MICROSOFT_TENANT_ID` | `common` \| `consumers` \| `organizations` \| tenant GUID |

Register **both** callbacks in each provider console — all three providers accept a list, so one app registration serves local and deployed use with no code change and no per-environment client id:

```
http://localhost:8080/login/oauth2/code/{provider}     # start.sh
https://<your-domain>/login/oauth2/code/{provider}      # deployed
```

…where `{provider}` is `google`, `github`, or `microsoft`. The deployed callback additionally requires `FORWARD_HEADERS_STRATEGY=framework` (see the section above) — without it the app sends a redirect URI matching neither entry.

> **Microsoft specifically:** register the redirect URI under the **Web** platform, not SPA or mobile, and leave *Allow public client flows* set to **No**. Registering it as a SPA makes Entra reject the (correct) `client_secret_post` authentication with `AADSTS90023: Public clients can't send a client secret`. That is a portal setting; the Spring configuration in `OAuth2ClientConfig` is already right.

See [security.md](security.md#federated-login) for the full flow and [flows/04-federated-oauth2.md](flows/04-federated-oauth2.md) for the click-to-token walkthrough.

#### Step-by-step: enabling GitHub (the fastest provider)

GitHub needs no domain verification, so it's the quickest to demo end-to-end:

1. **Create the OAuth app.** GitHub → **Settings ▸ Developer settings ▸ OAuth Apps ▸ New OAuth App**.
   - **Homepage URL:** `http://localhost:4200`
   - **Authorization callback URL:** `http://localhost:8080/login/oauth2/code/github` — this must match **exactly** (scheme, host, port, path). This points at the **backend** (8080), not the SPA (4200), because Spring Security handles the OAuth callback.
2. **Copy the credentials.** Register → copy the **Client ID**, then **Generate a new client secret** and copy it (shown once).
3. **Put them in `.env`:**
   ```bash
   GITHUB_CLIENT_ID=Iv1.xxxxxxxxxxxx
   GITHUB_CLIENT_SECRET=xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx
   ```
4. **Restart** the backend. Confirm the log now reads `Federated login providers configured: [github]` (not `none`).
5. **Verify the wiring:**
   - `GET http://localhost:8080/oauth2/providers` should return `{"data":{"providers":["github"]}, ...}`, and a **GitHub button** appears on the login screen automatically.
   - After a successful GitHub login, a row lands in `oauthproviderlinks` (`SELECT * FROM oauthproviderlinks;`) linking your local user to the GitHub subject id — that's the find-or-create in action ([database.md §7](database.md#7-federated-identity)).

> **How activation works:** each provider registers **only** when its `CLIENT_ID` is non-blank (`OAuth2ClientConfig` builds the `ClientRegistrationRepository` from whichever creds are present). With none set, a non-functional placeholder registration keeps the OAuth filter chain bootable while the login screen stays free of dead buttons. Google and Microsoft follow the identical pattern — fill in their `CLIENT_ID`/`CLIENT_SECRET` and restart.

#### Step-by-step: enabling Google

Google requires an OAuth **consent screen** in addition to the credentials, and gates non-test users until the app is published.

1. **Google Cloud Console** (<https://console.cloud.google.com>) → create or select a project.
2. **APIs & Services → OAuth consent screen** → choose **External** → fill in app name + user-support email + developer contact. Save.
   - ⚠ **If the app stays in "Testing" mode**, only accounts you add under **Audience → Test users** can log in; everyone else gets `Error 403: access_denied`. Add your own Google account as a test user, or **Publish** the app.
3. **APIs & Services → Credentials → Create Credentials → OAuth client ID** → Application type: **Web application**.
4. **Authorized redirect URIs → Add URI** — exactly (backend port, not the SPA):
   ```
   http://localhost:8080/login/oauth2/code/google
   ```
   (Authorized JavaScript origins are **not** required — this is a server-side Authorization Code flow, not implicit.)
5. **Create** → copy the **Client ID** (`…apps.googleusercontent.com`) and **Client Secret**.
6. Put them in `.env`:
   ```bash
   GOOGLE_CLIENT_ID=xxxxxxxx.apps.googleusercontent.com
   GOOGLE_CLIENT_SECRET=xxxxxxxxxxxxxxxxxxxx
   ```
7. **Restart** → log reads `Federated login providers configured: [github, google]`; a **Google** button joins the login screen. Google uses OIDC, so the id-token carries `sub` + `email` for find-or-create (`CommonOAuth2Provider.GOOGLE` preset in `OAuth2ClientConfig`).

> **Consent-screen audience — Internal vs External (they're mutually exclusive).** The OAuth consent screen has one **audience** setting, not a pair of toggles, so you pick exactly one:
> | Audience | Who can sign in | Notes |
> |----------|-----------------|-------|
> | **External** *(use this for open sign-up)* | **Any** Google account — your Workspace org **and** personal `@gmail.com` **and** other orgs | External is a **superset** — it already includes your org's users. In "Testing" status it's limited to added test users until you **Publish**; sensitive scopes trigger Google verification |
> | **Internal** | **Only** users in the Workspace org that owns the project (e.g. `@lewisu.edu`) | The *restrictive* option; no Google verification needed. Only available when the project is owned by a Workspace org |
>
> You **cannot** enable both — "Internal" is org-only and "External" is everyone, so **External already covers "org + outsiders."** Only choose Internal to deliberately *exclude* non-org accounts. Switching External→Internal later immediately blocks every already-linked non-org user, so don't flip it once personal accounts have signed in.

#### Step-by-step: enabling Microsoft (Entra ID)

Microsoft has **no `CommonOAuth2Provider` preset**, so `OAuth2ClientConfig.microsoftRegistration()` declares the endpoints explicitly against the configured tenant (`login.microsoftonline.com/{tenant}`), using OIDC scopes and `client_secret_post` auth.

1. **Entra admin center / Azure Portal → Microsoft Entra ID → App registrations → New registration**.
2. **Name** it, and under **Supported account types** pick who can sign in:
   - *Personal + work/school accounts* → set `MICROSOFT_TENANT_ID=common`
   - *Any org (multi-tenant)* → `organizations`
   - *Personal Microsoft accounts only* → `consumers`
   - *Your single tenant only* → the **tenant GUID**
3. **Redirect URI** → platform **Web** → exactly:
   ```
   http://localhost:8080/login/oauth2/code/microsoft
   ```
4. **Register**, then copy the **Application (client) ID**.
5. **Certificates & secrets → New client secret** → copy the secret **Value** (not the Secret ID — it's shown once).
6. Put them in `.env` (the tenant must match step 2):
   ```bash
   MICROSOFT_CLIENT_ID=xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx
   MICROSOFT_CLIENT_SECRET=xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx
   MICROSOFT_TENANT_ID=common
   ```
7. **Restart** → log reads `Federated login providers configured: [github, google, microsoft]`; a **Microsoft** button appears. The registration requests `openid profile email` and reads the stable `sub` claim as the provider subject id.

> **All three share the same callback shape** — `http://localhost:8080/login/oauth2/code/{provider}` — because that's Spring Security's default redirect template. In production, register the same path on your real host (e.g. `https://yourdomain/login/oauth2/code/google`) and keep `UI_APP_URL` pointed at the deployed SPA (used for the federated-login **failure** redirect).

### SMS 2FA (Twilio) — optional, stubbed

| Variable | Purpose |
|----------|---------|
| `TWILIO_FROM_NUMBER` | Sender number |
| `TWILIO_ACCOUNT_SID` | Twilio account SID |
| `TWILIO_AUTH_TOKEN` | Twilio auth token |

> The SMS send is **stubbed in dev** — the code is logged to the server console, not delivered — so these can stay as placeholders.

### Aiven cloud MySQL — only when `start.sh` `DB=aiven`

| Variable | Purpose |
|----------|---------|
| `AIVEN_DB_HOST` / `AIVEN_DB_PORT` / `AIVEN_DB_NAME` | Aiven instance + schema |
| `AIVEN_DB_USERNAME` / `AIVEN_DB_PASSWORD` | Aiven credentials |

`start.sh` uses these to assemble a TLS JDBC URL and point local Spring Boot at Aiven instead of the Docker container.

> **Current cloud database:** `AIVEN_DB_NAME=db3` — a migrated copy of the local `db2`, with the case-sensitivity bridge applied (Aiven is case-sensitive; see [database.md §17.2](database.md#172-the-case-sensitivity-landmine-lower_case_table_names)). How it was created and how to re-migrate: [database.md §17.4](database.md#174-migrating-native--aiven-how-db3-was-created).

---

## 5. Spring profiles

| Profile | Activated by | Behaviour | Config file |
|---------|--------------|-----------|-------------|
| **`dev`** | default (`SPRING_ACTIVE_PROFILES=dev`) | Every variable has a safe local fallback; the app boots with a near-empty `.env`. The demo-data seeder runs. | `application-dev.yml` |
| **`prod`** | `SPRING_ACTIVE_PROFILES=prod`, or the Docker image's `ENTRYPOINT -Dspring.profiles.active=prod` | **No fallbacks** — a missing variable is a startup failure. The seeder does not run. | `application-prod.yml` |
| `qa`, `stage`, `local` | declared in `pom.xml` | Set the active profile name only; there are **no** dedicated `application-{qa,stage,local}.yml` files, so they fall back to base `application.yml` and require all env vars. Treat `dev`/`prod` as the supported pair. | — |

The base `application.yml` itself sets `spring.profiles.active: ${SPRING_ACTIVE_PROFILES:dev}`, so dev is the default when nothing is specified.

---

## 6. `application.yml` annotated

The base config (`src/main/resources/application.yml`), section by section:

```yaml
server:
  port: ${CONTAINER_PORT:8080}          # backend HTTP port

spring:
  datasource:
    url: jdbc:mysql://${MYSQL_HOST}:${MYSQL_PORT}/${MYSQL_DATABASE}?useSSL=false&allowPublicKeyRetrieval=true
    username: ${MYSQL_USERNAME}
    password: ${MYSQL_PASSWORD}
  jpa:
    show-sql: ${SHOW_SQL:false}         # SQL logging OFF by default (prod-safe); opt in with SHOW_SQL=true
    hibernate:
      ddl-auto: update                  # Hibernate creates/updates ONLY the JPA tables (customer/invoice/services); prod overrides to `validate`
    properties:
      hibernate:
        globally_quoted_identifiers: true   # ⚠ see gotcha #3
        format_sql: ${SHOW_SQL:false}
    generate-ddl: true
  sql:
    init:
      mode: never                       # schema.sql is NOT auto-run; apply it by hand (post-Flyway)
      continue-on-error: true
  mail:
    host: ${MAIL_HOST}
    port: ${MAIL_PORT}
    username: ${MAIL_USERNAME}
    password: ${MAIL_PASSWORD}
    properties.mail.smtp: { auth: true, starttls: { enable: true, required: true }, timeout: 5000, ... }
    verify.host: ${VERIFY_EMAIL_HOST}

jwt:
  secret: ${JWT_SECRET}                 # HMAC-SHA512 signing key (see security.md)

ui:
  app:
    url: ${UI_APP_URL:http://localhost:4200}

management:
  endpoints.web.exposure.include: health, info   # only these two Actuator endpoints are public
  endpoint.health.show-details: never
```

Key takeaways:

- **Two persistence mechanisms coexist.** Hibernate `ddl-auto: update` manages *only* the JPA entity tables (customers, invoices, services); the entire identity/auth schema (users, roles, sessions, TOTP, orgs, …) is owned by `schema.sql` and accessed via `JdbcTemplate`. See [database.md](database.md).
- **`sql.init.mode: never`** means `schema.sql` does not run on startup — you apply it once by hand for a fresh database. (This replaced a removed Flyway setup; it is safe to switch to `always` because `schema.sql` is idempotent.)
- **Actuator is locked down** to `health` and `info` only, with no health detail — the Docker healthcheck hits `GET /actuator/health`.

---

## 7. Security token settings

These are compile-time constants (`constants/Constants.java`), not env vars, but they define runtime auth behaviour:

| Setting | Value | Meaning |
|---------|-------|---------|
| Access-token lifetime | `1_800_000` ms (**30 min**) | How long an access token is accepted |
| Refresh-token lifetime | `432_000_000` ms (**5 days**) | How long a refresh token can mint new access tokens |
| Issuer (`iss`) | `BOBBYLON_LLC` | Verified on every token |
| Audience (`aud`) | `BOBS_MANAGEMENT` | Stamped on issue |
| Authorities claim | `authorities` | Present on access tokens only |
| Session-family claim | `sid` | Present on both token types (refresh-session rotation) |
| Token prefix | `Bearer ` | Authorization header scheme |

The signing key itself is the `JWT_SECRET` env var. Full token mechanics live in [security.md](security.md).

---

## 8. Configuration gotchas (read this)

**1. `Could not resolve placeholder 'MYSQL_USERNAME'` (or similar) on startup.**
The **dev** profile (`application-dev.yml`) ships literal defaults, so a bare `mvn spring-boot:run` or an IDE launch boots with **no `.env`** (you still need a running MySQL — see #4). The **prod** profile deliberately keeps **no fallback** for secrets, DB credentials, mail credentials, or the UI/verify URLs: they are read straight from the environment, so a missing one fails fast at startup with `Could not resolve placeholder '<NAME>'`. **Fix:** set every required variable (see the table in §1) before launching prod, e.g. via `./start.sh`, the platform's config, or an exported `.env`.
> _Historical note: earlier revisions declared each variable self-referentially (`CONTAINER_PORT: ${CONTAINER_PORT:8080}`), which threw `Circular placeholder reference 'CONTAINER_PORT'` whenever the env var was absent (the placeholder resolved back to its own property). Those self-references were replaced with literals (dev) and direct env reads (prod), so that error no longer occurs._

**2. Datasource / `JWT_SECRET` bind failure in prod or CI.**
Same root cause as #1 — a **required** environment variable isn't present. Dev supplies a literal `JWT_SECRET` and DB defaults; prod (and any environment launched without `.env`) must provide them explicitly.

**3. Hibernate column names — `globally_quoted_identifiers: true`.**
This flag makes Hibernate quote identifiers and **bypass the snake_case naming strategy**, so a `usingMfa` field maps to a column literally named `usingMfa`, not `using_mfa`. Always add an explicit `@Column(name = "using_mfa")` on JPA entity fields to keep them aligned with the `schema.sql` column names.

**4. `Communications link failure` / `No such host is known (mysql)` outside Docker.**
`MYSQL_HOST=mysql` is the Docker *service* name. Running natively, set `MYSQL_HOST=127.0.0.1`.

**5. Cloud DBs need TLS.** For managed databases (Aiven, RDS, Cloud SQL) set `useSSL=true&requireSSL=true` in `SPRING_DATASOURCE_URL`.

**6. "All my data vanished" — Docker MySQL shadowing native MySQL on port 3306.**
If you run `start.sh` with `DB=local`, it starts a **Docker** MySQL (empty, fresh volume) that seizes `127.0.0.1:3306` and *shadows* a native MySQL (e.g. Windows **MySQL80**) that would otherwise own the port. The app connects to `localhost:3306`, gets the empty Docker DB, and looks wiped — but your real data is safe in native MySQL, just not listening. **Fix:** use the default `DB=native` (never starts Docker MySQL), set the native service to auto-start (`sc config MySQL80 start= auto`), and never run both at once. Full incident write-up + recovery in [database.md §17.3](database.md#173-the-port-3306-shadowing-trap-the-vanished-data-incident).

**7. `Table 'X.customer' doesn't exist` or `BadSqlGrammar [... JOIN Users ...]` on Docker/Aiven, but fine natively.**
Case-sensitivity. Native Windows MySQL is case-**in**sensitive (`lower_case_table_names=1`); Docker and Aiven are case-**sensitive** (`=0`). The app mixes casings (`Customer`/`customer`, `Users`/`users`), which only collide on a case-sensitive server. **Fix:** the compatibility views documented in [database.md §17.2](database.md#172-the-case-sensitivity-landmine-lower_case_table_names), or the durable "lowercase everything" refactor noted there.

---

## 9. Secrets handling

- **Never commit `.env`.** Only `.env.example` (placeholders) is tracked.
- **Rotate anything that leaks.** If a real secret ever lands in a log, screenshot, or commit, rotate it (DB password, `JWT_SECRET`, Gmail app password, Twilio token, OAuth secrets). Rotating `JWT_SECRET` invalidates all existing tokens (everyone re-logs-in) — which is the desired effect after a leak.
- **In the cloud, don't ship a `.env`.** Set variables through the platform (App Service application settings, Cloud Run env vars, Kubernetes Secrets, etc.). See [deployment.md](deployment.md).
- **Use the `prod` profile in production** so a missing secret fails fast rather than falling back to a dev default.
