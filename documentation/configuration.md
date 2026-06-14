# Configuration Guide

How SecureCapita is configured: where settings come from, every environment variable, the Spring profiles, an annotated walk-through of `application.yml`, and the configuration gotchas that will bite you (with fixes).

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

### Federated login (OAuth2 / OIDC) — optional

A provider's login button appears only when its `CLIENT_ID` is set (the SPA discovers configured providers via `GET /oauth2/providers`). With none set, the app logs `Federated login providers configured: none`.

| Variable | Purpose |
|----------|---------|
| `GOOGLE_CLIENT_ID` / `GOOGLE_CLIENT_SECRET` | Google OAuth client |
| `GITHUB_CLIENT_ID` / `GITHUB_CLIENT_SECRET` | GitHub OAuth app |
| `MICROSOFT_CLIENT_ID` / `MICROSOFT_CLIENT_SECRET` | Microsoft (Entra) app registration |
| `MICROSOFT_TENANT_ID` | `common` \| `consumers` \| `organizations` \| tenant GUID |

Register this callback in each provider console: `http://localhost:8080/login/oauth2/code/{provider}` where `{provider}` is `google`, `github`, or `microsoft`. See [security.md](security.md#federated-login) for the full flow.

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
    show-sql: true                      # log generated SQL (dev convenience)
    hibernate:
      ddl-auto: update                  # Hibernate creates/updates ONLY the JPA tables (customer/invoice/services)
    properties:
      hibernate:
        globally_quoted_identifiers: true   # ⚠ see gotcha #3
        format_sql: true
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

**1. `Circular placeholder reference 'CONTAINER_PORT'` on startup.**
`application-dev.yml` defines each variable self-referentially, e.g. `CONTAINER_PORT: ${CONTAINER_PORT:8080}`. This resolves *only when the matching OS environment variable is set* — which `start.sh` does (`export CONTAINER_PORT=8080`). If you launch with a bare `mvn spring-boot:run` or from an IDE **without** loading `.env`, the placeholder references itself and Spring throws `Circular placeholder reference`.
**Fix:** run via `./start.sh`, or load `.env` into the run configuration, or `export CONTAINER_PORT=8080` (and the other vars) before launching.

**2. `Could not resolve placeholder 'JWT_SECRET'` / datasource bind failure.**
Same root cause — the environment variables aren't present. Ensure `.env` exists and is being loaded.

**3. Hibernate column names — `globally_quoted_identifiers: true`.**
This flag makes Hibernate quote identifiers and **bypass the snake_case naming strategy**, so a `usingMfa` field maps to a column literally named `usingMfa`, not `using_mfa`. Always add an explicit `@Column(name = "using_mfa")` on JPA entity fields to keep them aligned with the `schema.sql` column names.

**4. `Communications link failure` / `No such host is known (mysql)` outside Docker.**
`MYSQL_HOST=mysql` is the Docker *service* name. Running natively, set `MYSQL_HOST=127.0.0.1`.

**5. Cloud DBs need TLS.** For managed databases (Aiven, RDS, Cloud SQL) set `useSSL=true&requireSSL=true` in `SPRING_DATASOURCE_URL`.

---

## 9. Secrets handling

- **Never commit `.env`.** Only `.env.example` (placeholders) is tracked.
- **Rotate anything that leaks.** If a real secret ever lands in a log, screenshot, or commit, rotate it (DB password, `JWT_SECRET`, Gmail app password, Twilio token, OAuth secrets). Rotating `JWT_SECRET` invalidates all existing tokens (everyone re-logs-in) — which is the desired effect after a leak.
- **In the cloud, don't ship a `.env`.** Set variables through the platform (App Service application settings, Cloud Run env vars, Kubernetes Secrets, etc.). See [deployment.md](deployment.md).
- **Use the `prod` profile in production** so a missing secret fails fast rather than falling back to a dev default.
