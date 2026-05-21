# Angular + Spring Boot Full Stack Application

A production-ready full-stack application combining an **Angular 21** SPA with a **Spring Boot 4 / Java 21**
backend. Security features include JWT access and refresh tokens, two-factor authentication, role-based
access control, account verification, and password reset flows.

The app is containerized end-to-end: a single Docker image holds both the Angular bundle and the
Spring Boot JAR, deployable to Azure App Service, AWS App Runner, AWS ECS Fargate, Google Cloud Run,
or any host with a Docker runtime.

---

## Glossary — What you're going to be running

Before the steps, here's what each piece is, in plain English:

| Term               | What it actually is                                                                                                                            |
|--------------------|------------------------------------------------------------------------------------------------------------------------------------------------|
| **SPA**            | "Single Page Application" — the Angular frontend. Loads once, then runs in the browser; navigation doesn't reload the page.                    |
| **Spring Boot**    | The Java REST API backend. Also serves the Angular SPA from `/static/` when running in production (single-container model).                    |
| **MySQL**          | The relational database (users, roles, events, etc.). Locally it's a Docker container. In production it's Aiven / RDS / Azure MySQL.           |
| **Docker**         | Packages the app + its dependencies into a portable container. Same container runs on your laptop, AWS, and Azure.                             |
| **Compose**        | `docker-compose.yml` — describes the *stack* (multiple containers together: app + database + admin UI).                                        |
| **Adminer**        | An open-source web UI for browsing/editing the MySQL database. Think phpMyAdmin but lighter. Runs as one of the compose services.              |
| **ECR / ACR**      | **E**lastic / **A**zure **C**ontainer **R**egistry — cloud storage for Docker images. Like Docker Hub but private to your AWS / Azure account. |
| **App Runner**     | AWS service that turns a container image into a public URL. "Plug-and-play" deployment.                                                        |
| **App Service**    | Azure equivalent of App Runner.                                                                                                                |
| **Bicep**          | Microsoft's IaC (Infrastructure-as-Code) language for Azure. Describes the resources in a `.bicep` file; `az deployment` creates them.         |
| **CloudFormation** | AWS equivalent of Bicep. Resources described in a YAML file; `aws cloudformation deploy` creates them.                                         |
| **OIDC**           | Auth pattern used by GitHub Actions to deploy to AWS / Azure *without storing long-lived access keys*.                                         |

---

## Concepts in Depth

Most of the terms above are easy to look up. A few are worth a fuller explanation because they
shape how this app is built or how you'll work with it day-to-day.

### Why this app is a Single Page Application (SPA)

When you click around — login → dashboard → profile → customers — the page doesn't reload. Angular
just swaps which components render while the URL changes. That's what makes it a "Single Page
Application": the browser loads **one** HTML document (`index.html`) plus a JavaScript bundle, and
all navigation after that is driven by JS in the browser.

The alternative is a **Multi-Page Application (MPA)** — every click loads a fresh HTML page from the
server (classic PHP, JSP, Rails). MPAs are how the web worked for its first decade.

**Tradeoffs of the SPA choice:**

| Concern             | SPA (this app)                                                          | MPA                                 |
|---------------------|-------------------------------------------------------------------------|-------------------------------------|
| Initial load        | Slower — must download the whole bundle (~1.17 MB raw, ~350 KB gzipped) | Faster — just one page's HTML       |
| Subsequent clicks   | Instant (no reload)                                                     | Slow (full page reload every click) |
| SEO                 | Bad by default (crawlers see empty `<div>`)                             | Excellent (every page is real HTML) |
| JavaScript required | Yes — site is broken without it                                         | No                                  |
| Backend complexity  | Just a JSON API                                                         | Renders HTML + handles state        |
| Mobile app reuse    | Same API works for iOS / Android / React Native                         | Would need a parallel JSON API      |
| Stateful UI         | Form data persists across in-app navigation                             | Each click is a fresh page          |

**Why an SPA fits *this* app:**

- It's behind a login wall — SEO doesn't matter (Google can't crawl past authentication)
- Users hit it daily — the bundle gets cached after the first visit
- Lots of forms and state — SPAs preserve them across navigation
- JWT auth pairs naturally with an API-only backend
- If you ever build a mobile client, the same REST API works without rewrites

**If the initial load ever feels slow**, the optimization menu (cheapest first):

1. Enable gzip in `application.yml` (`server.compression.enabled: true`) — ~3× smaller transfer, zero code changes
2. Lazy-load routes with `loadComponent: () => import(...)` in the router — cuts initial bundle 40–60%
3. Dynamic-import jspdf inside the "Export PDF" click handler — moves ~300 KB out of initial load
4. `ng add @angular/pwa` — service worker caches the bundle for repeat visits
5. Angular Universal (SSR) — biggest first-paint win, biggest project lift

### What Adminer is and what to use it for

Adminer is a tiny (~500 KB single PHP file) web UI for browsing and editing databases. It's open
source and supports MySQL, PostgreSQL, SQLite, Oracle, and others. Think of it as a lighter
phpMyAdmin or a browser-based DataGrip.

It's bundled into `docker-compose.yml` as a development convenience. When `./deploy.sh` is running,
visit **http://localhost:8081** to:

- Browse the `users`, `roles`, `events`, `userevents`, `userroles`, etc. tables
- Manually toggle `enabled = TRUE` on a user instead of clicking the verification URL
  (see [First-Time User Flow](#first-time-user-flow--important))
- Run ad-hoc SQL queries while debugging
- Inspect what Hibernate's `ddl-auto: update` actually did to the schema

**Adminer is NOT deployed to production.** It's a local dev tool only. In production, you'd
connect via the cloud provider's DB console (AWS RDS Query Editor, Azure Portal) or a desktop tool
like DataGrip / MySQL Workbench.

Adminer login when prompted:

- **System:** MySQL
- **Server:** `mysql` (the Docker service name)
- **Username:** `root`
- **Password:** the value of `MYSQL_ROOT_PASSWORD` in your `.env`
- **Database:** `db2`

### Container vs. Image (used interchangeably, but they're different)

- **Image** — an immutable blueprint. `securecapita-app:local` is an image. It's a stack of
  read-only layers on a disk.
- **Container** — a *running instance* of an image. You can have many containers from the same
  image.

Class vs. object in OOP: an image is the class, containers are instances.

`docker compose build` produces images. `docker compose up` creates containers from those images.
`docker images` lists images; `docker ps` lists running containers.

### What OIDC means in the GitHub Actions workflows

The deployment workflows authenticate to AWS / Azure using **OpenID Connect**. The old pattern was:

> Generate an AWS access key → store it as a GitHub secret → trust nothing leaks.

That's brittle: anyone who can read the secret has the same permissions as the key, the key never
expires until you rotate it, and you usually don't notice a leak until something bad happens.

With OIDC:

1. GitHub Actions presents a *short-lived signed token* claiming "I'm running in repo
   `bbobbylon/angularSpringBootFullStack` on branch `master`."
2. AWS / Azure has a trust policy that says, "I trust tokens from that exact repo."
3. If the token's signature checks out, the cloud issues temporary credentials (~1 hour).
4. No long-lived secrets exist anywhere.

The one-time trust setup is documented in the top-of-file comments in each deployment workflow.

### What Cloud Native Buildpacks are (the `<image>` block in `pom.xml`)

The Spring Boot Maven plugin can build an OCI container image **without a Dockerfile**, using
community-maintained "buildpacks" that auto-detect your project type and assemble a layered image
optimized for the JVM:

```bash
./mvnw spring-boot:build-image -Pprod
# Produces: docker.io/securecapita/app:0.0.1-SNAPSHOT
```

This project uses the Dockerfile path *because* the Dockerfile also bakes the Angular SPA into the
JAR — something Buildpacks don't handle automatically. The Buildpacks config is there in `pom.xml`
as a ready-to-go fallback if you ever split frontend and backend into two containers (one for the
SPA served by nginx, one for the API).

### What `actuator` is

Spring Boot Actuator is a built-in management module that exposes endpoints under `/actuator/**`.
Only `/actuator/health` and `/actuator/info` are exposed by configuration (`application.yml`); the
rest (`/actuator/env`, `/actuator/metrics`, `/actuator/loggers`) exist but are gated behind
authentication or completely disabled for security.

`/actuator/health` is what the Dockerfile's `HEALTHCHECK` polls, what App Runner / App Service /
ECS use as their readiness probe, and what `./deploy.sh` waits for before declaring success.
Spring's health check includes a DB connectivity test by default, so a `"status":"UP"` response
genuinely means "the app is up AND the database is reachable."

---

## Software Requirements

You can run the app two ways. Pick one — you don't need everything.

### Option A — Run with Docker (recommended, simplest)

**You only need one thing installed:** [Docker Desktop](https://www.docker.com/products/docker-desktop/).

Everything else (Java, Node, Maven, MySQL) lives inside containers built from `Dockerfile`. You don't
need them on your host machine.

| Tool                 | Required version | Why                                                        |
|----------------------|------------------|------------------------------------------------------------|
| Docker Desktop       | 20+              | Provides `docker`, `docker compose`, and the daemon        |
| Bash _or_ PowerShell | any              | To run `./deploy.sh` (Bash) or `.\deploy.ps1` (PowerShell) |

### Option B — Run natively (faster hot-reload for development)

If you want Angular hot-reload while editing and Java debugger attachment:

| Tool        | Required version            | Notes                                          |
|-------------|-----------------------------|------------------------------------------------|
| Java JDK    | 21+                         | Temurin, Microsoft Build, or Corretto all work |
| Maven       | Bundled via `./mvnw` (3.9+) | Don't install separately — use the wrapper     |
| Node.js     | 22+                         | Angular 21 minimum                             |
| Angular CLI | 21+                         | `npm install -g @angular/cli`                  |
| MySQL       | 8.0+                        | Or run *just* the MySQL service via Docker     |

### Cloud-deploy extras (optional, only if you deploy yourself)

| Tool      | Needed for                                       | Install                                                 |
|-----------|--------------------------------------------------|---------------------------------------------------------|
| AWS CLI   | `./deploy.sh --aws-push`, CloudFormation deploys | https://aws.amazon.com/cli/                             |
| Azure CLI | `./deploy.sh --azure-push`, Bicep deploys        | https://learn.microsoft.com/cli/azure/install-azure-cli |

---

## Quick Start (Docker)

```bash
# 1. Clone and enter the repo
git clone <this-repo-url>
cd angularSpringBootFullStack

# 2. Run the deploy script
./deploy.sh        # macOS / Linux / Git Bash / WSL
.\deploy.ps1       # Windows PowerShell
```

That script:

1. Verifies Docker & Compose is installed and the daemon is reachable
2. Creates a `.env` from `.env.example` if you don't already have one
3. Builds the multi-stage Docker image (Angular → Spring Boot JAR → JRE runtime)
4. Starts the stack: `app` + `mysql` + `adminer`
5. Waits for the app container to report **healthy** via its Docker health check
6. Prints the local URLs and useful follow-up commands

When the script finishes, you can open:

| URL                                           | What's there                                             |
|-----------------------------------------------|----------------------------------------------------------|
| `http://localhost:8090`                       | The Angular SPA (served by Spring Boot from `/static/`)  |
| `http://localhost:8090/actuator/health`       | Health probe — `{"status":"UP"}` when ready              |
| `http://localhost:8090/user/login` and others | REST API endpoints (see [API Reference](#api-reference)) |
| `http://localhost:8081`                       | Adminer — visual MySQL browser                           |
| `localhost:3307` (MySQL)                      | DB port for external clients (DataGrip / Workbench)      |

> The default host port is **8090**, not 8080, so the Docker stack doesn't clash with a
> native `./mvnw spring-boot:run` already on 8080. Override by setting `APP_PORT=8080` in
> `.env` if 8080 is free. The container internally always listens on 8080.

### Deploy script flags

| Flag (Bash)    | Flag (PowerShell) | Effect                                                                                               |
|----------------|-------------------|------------------------------------------------------------------------------------------------------|
| *(none)*       | *(none)*          | Build + start + wait for healthy                                                                     |
| `--logs`       | `-Logs`           | Same as above, then tail `app` logs                                                                  |
| `--clean`      | `-Clean`          | Wipe the MySQL volume first (fresh schema), then build + start                                       |
| `--down`       | `-Down`           | Stop containers but **keep** the DB volume (resume later with `./deploy.sh`)                         |
| `--aws-push`   | `-AwsPush`        | Build the image, tag it, push to ECR (needs `.env.cloud`, see [Cloud Deployment](#cloud-deployment)) |
| `--azure-push` | `-AzurePush`      | Build, tag, push to ACR, restart App Service                                                         |

---

## First-Time User Flow — IMPORTANT

The app uses **email verification** before allowing login. If you register and then try to log in
immediately, you will get **`400 Bad Request — "User is disabled"`**. That's not a bug — it's by
design.

### How to verify a freshly registered user

When you `POST /user/register`, the backend creates the user with `enabled=false` and logs a
verification URL to the server console. You need to visit that URL once to flip `enabled=true`.

#### Step 1 — Register

```bash
curl -X POST http://localhost:8090/user/register \
  -H "Content-Type: application/json" \
  -d '{"firstName":"John","lastName":"Doe","email":"john@example.com","password":"P@ssw0rd123"}'
```

#### Step 2 — Find the verification URL in the logs

```bash
docker compose logs app | grep "verification url"
# Example log line:
# INFO ... Account verification url http://localhost:8090/user/verify/account/550e8400-e29b-41d4-a716-446655440000 sent to user with email: john@example.com
```

#### Step 3 — Visit the URL once

```bash
curl http://localhost:8090/user/verify/account/550e8400-e29b-41d4-a716-446655440000
```

#### Step 4 — Now login works

```bash
curl -X POST http://localhost:8090/user/login \
  -H "Content-Type: application/json" \
  -d '{"email":"john@example.com","password":"P@ssw0rd123"}'
# Returns access_token + refresh_token
```

### Or: enable a user directly in the DB

If you don't want to fiddle with verification URLs during development, you can flip `enabled` to
`true` in Adminer (http://localhost:8081 — server: `mysql`, user: `root`, db: `db2`):

```sql UPDATE users SET enabled = TRUE WHERE email = 'john@example.com'; ```

> Why this exists: in real production, the verification URL would be emailed to the user. The tutorial
> doesn't ship with a configured SMTP server, so the URL is just logged. This is the part of the app
> that would change first when you add real email sending (e.g., SendGrid, Amazon SES, Azure Communication Services).

---

## Architecture

```
                  ┌─────────────────────────────────────────────────────────┐
                  │  Browser                                                │
                  │  ── Angular 21 SPA (Bootstrap 5, JWT in localStorage) ──│
                  └─────────────────────────────────────────────────────────┘
                                            │  HTTPS / HTTP
                                            ▼
┌─────────────────────────────────────────────────────────────────────────────────┐
│  Spring Boot 4.0.6 / Java 21 — single JAR, port 8080                            │
│                                                                                 │
│   /static/**       Angular dist/ files (index.html, bundles, assets)            │
│   /user/**         REST API (register, login, MFA, profile, refresh, ...)       │
│   /actuator/**     health, info (only these two endpoints exposed)              │
│                                                                                 │
│   Filter chain:  CustomAuthFilter (JWT validation)                              │
│                → SecurityFilterChain (authorization rules)                      │
│                → Controllers → Services → Repositories                          │
└─────────────────────────────────────────────────────────────────────────────────┘
                                            │  JDBC
                                            ▼
                  ┌─────────────────────────────────────────────────────────┐
                  │  MySQL 8.x                                              │
                  │   Local dev → mysql:8.4 container (compose)             │
                  │   Production → Aiven managed MySQL (Azure / AWS env)    │
                  └─────────────────────────────────────────────────────────┘
```

**Why one container instead of two (nginx + Spring)?** Same-origin: no CORS, no reverse-proxy config,
one deployment target. The Angular bundle lands in `src/main/resources/static/` at build time, so
Spring serves it as static content. The tradeoff is you can't scale frontend and backend
independently — fine for this size of app, easy to split later if needed.

---

## Local Development (without Docker)

If you want to hot-reload while editing, run the two tiers natively. You'll still need a MySQL instance
— easiest is to just start the compose MySQL service and ignore the rest:

```bash
docker compose up -d mysql       # local MySQL only
```

Then in two terminals:

```bash
# Terminal 1 — backend (dev profile uses application-dev.yml, hardcoded localhost MySQL)
./mvnw spring-boot:run
# Windows: .\mvnw.cmd spring-boot:run
```

```bash
# Terminal 2 — frontend
cd securecapitaapp
npm install
npm start                        # ng serve on http://localhost:4200
```

In dev mode the Angular SPA proxies API calls; the dev server runs on **4200** and the API on **8080**.

---

## Docker Stack Layout

```
docker-compose.yml
├── mysql (mysql:8.4)
│     ├── Schema bootstrapped from src/main/resources/schema.sql on first start
│     └── Data persisted in named volume 'securecapita-mysql-data'
├── app (built from Dockerfile)
│     ├── Stage 1: node:25-alpine — builds Angular dist/
│     ├── Stage 2: maven:3.9-eclipse-temurin-21 — bakes dist/ into static/, builds JAR
│     └── Stage 3: eclipse-temurin:21-jre-alpine — runtime, non-root user, HEALTHCHECK
└── adminer (adminer:5) — http://localhost:8081
```

### Dockerfile stage rationale

| Stage            | Why a separate stage                                                                |
|------------------|-------------------------------------------------------------------------------------|
| `frontend-build` | Node tooling is huge — keep it out of the runtime image                             |
| `backend-build`  | Maven + JDK is also huge; we only need the resulting JAR in the runtime             |
| Runtime          | `eclipse-temurin:21-jre-alpine` ≈ 200MB vs ~1.5GB if we shipped a JDK + Maven image |

The HEALTHCHECK polls `/actuator/health` so Compose, Kubernetes, App Service, ECS, and Cloud Run all
know when the container is actually ready to serve traffic — not just "process started."

### Alternative: Cloud Native Buildpacks (no Dockerfile)

If you'd rather not maintain a Dockerfile, Spring Boot can build the image directly via the
[Buildpacks](https://buildpacks.io/) plugin (already configured in `pom.xml`):

```bash
./mvnw spring-boot:build-image -Pprod
# Produces: securecapita/app:0.0.1-SNAPSHOT
```

The Buildpacks-produced image will *not* embed the Angular dist automatically — for that the
Dockerfile route is currently easier. Use Buildpacks if you split the frontend and backend into
two containers later.

---

## Environment Variables

Local Docker reads these from `.env` (copied from `.env.example` on first run). Cloud deployments
set them as platform secrets (App Service Configuration, App Runner env, Secrets Manager).

### Required at runtime

| Variable                     | Purpose                                                                 | Local default (`.env.example`)             |
|------------------------------|-------------------------------------------------------------------------|--------------------------------------------|
| `MYSQL_ROOT_PASSWORD`        | MySQL root password — used by both the DB container and the app         | `change-me-strong-password`                |
| `MYSQL_DATABASE`             | Schema/database name created on first MySQL boot                        | `db2`                                      |
| `APP_PORT`                   | Host-side port to publish the app on (avoid 8080 if native dev uses it) | `8090`                                     |
| `SPRING_DATASOURCE_URL`      | JDBC URL the app uses (set inside compose)                              | `jdbc:mysql://mysql:3306/db2?useSSL=false` |
| `SPRING_DATASOURCE_USERNAME` | DB user (set inside compose)                                            | `root`                                     |
| `SPRING_DATASOURCE_PASSWORD` | Same as `MYSQL_ROOT_PASSWORD` (compose forwards it)                     | `${MYSQL_ROOT_PASSWORD}`                   |
| `JWT_SECRET`                 | Signing key for access + refresh tokens — **rotate when leaked**        | `replace-with-a-long-random-secret-...`    |
| `SPRING_PROFILES_ACTIVE`     | Which `application-*.yml` to merge over base config                     | `prod` (in container)                      |

### Optional connection-pool tuning (used by `application-prod.yml`)

| Variable                    | Default | What it tunes                                                                             |
|-----------------------------|---------|-------------------------------------------------------------------------------------------|
| `DB_POOL_MAX_SIZE`          | 10      | Max simultaneous connections HikariCP will open                                           |
| `DB_POOL_MIN_IDLE`          | 2       | Connections to keep warm even when idle                                                   |
| `DB_CONNECTION_TIMEOUT_MS`  | 30000   | How long Hikari waits for a connection from the pool before giving up                     |
| `DB_INIT_FAIL_TIMEOUT_MS`   | 60000   | How long Spring waits for the DB on startup before crashing (set high for RDS cold-start) |
| `SERVER_TOMCAT_MAX_THREADS` | 200     | Tomcat worker thread cap                                                                  |
| `LOG_LEVEL_ROOT`            | INFO    | Root log level (`DEBUG` for verbose, `WARN` for quiet)                                    |

### Spring profiles

| Profile                | When used                                  | Where DB credentials come from               |
|------------------------|--------------------------------------------|----------------------------------------------|
| `dev`                  | `./mvnw spring-boot:run` locally (default) | Hardcoded `root:password@localhost:3306/db2` |
| `prod`                 | Inside the Docker image / cloud deployment | `${SPRING_DATASOURCE_*}` env vars            |
| `qa`, `stage`, `local` | Reserved profile names (not wired up yet)  | n/a                                          |

---

## Cloud Deployment

The same Docker image deploys to AWS or Azure. Infrastructure-as-Code templates and GitHub Actions
workflows are checked in.

### Repo layout for deployment

```
infrastructure/
├── aws/
│   ├── README.md                 ← AWS step-by-step
│   ├── cloudformation-stack.yaml ← One-shot IaC: ECR + RDS + App Runner + Secrets Manager
│   ├── apprunner.yaml            ← Standalone App Runner config (alternative to CFN)
│   └── ecs-task-definition.json  ← Alternative: ECS Fargate
└── azure/
    ├── README.md                 ← Azure step-by-step
    ├── main.bicep                ← Bicep IaC: ACR + App Service Plan + App Service + Key Vault [+ MySQL]
    └── main.parameters.example.json

.github/workflows/
├── ci.yml                ← PR build + test (no deploy)
├── aws-deploy.yml        ← Push to master → build, push to ECR, App Runner picks up
└── azure-deploy.yml      ← Push to master → build, push to ACR, restart App Service

azure-pipelines.yml       ← Existing Azure DevOps pipeline (alternative to azure-deploy.yml)
```

### Option 1 — AWS (App Runner, plug-and-play)

**One-shot setup:**

```bash
# Generate a strong JWT secret
JWT=$(openssl rand -base64 48)

# Provision ECR + RDS + App Runner + Secrets Manager
aws cloudformation deploy \
  --template-file infrastructure/aws/cloudformation-stack.yaml \
  --stack-name securecapita-prod \
  --capabilities CAPABILITY_NAMED_IAM \
  --parameter-overrides \
      DBMasterPassword='STRONG_DB_PASSWORD' \
      JwtSecret="$JWT"

# Push the first image (App Runner can't start without one)
cp .env.cloud.example .env.cloud
# Edit .env.cloud with your AWS_ACCOUNT_ID, then:
./deploy.sh --aws-push
```

Full guide: [`infrastructure/aws/README.md`](infrastructure/aws/README.md)

### Option 2 — Azure (App Service, currently live)

**Existing live deployment:** `https://angularspringbootfullstack-ehd6dkevc3edgxer.centralus-01.azurewebsites.net`

**To re-create from scratch (Bicep):**

```powershell
$jwt = [Convert]::ToBase64String((1..48 | ForEach-Object { Get-Random -Maximum 256 }))
az group create --name bobsresourcegroup --location centralus
az deployment group create `
  --resource-group bobsresourcegroup `
  --template-file infrastructure/azure/main.bicep `
  --parameters '@infrastructure/azure/main.parameters.json' `
  --parameters jwtSecret=$jwt

# Push image
cp .env.cloud.example .env.cloud
# Edit .env.cloud with your AZURE_ACR_NAME, then:
./deploy.sh --azure-push
```

Full guide: [`infrastructure/azure/README.md`](infrastructure/azure/README.md)

### Continuous Deployment options

Pick **one** CI host per cloud to avoid deploying races:

| CI host        | Workflow file                        | Triggers on      |
|----------------|--------------------------------------|------------------|
| GitHub Actions | `.github/workflows/aws-deploy.yml`   | push to `master` |
| GitHub Actions | `.github/workflows/azure-deploy.yml` | push to `master` |
| Azure DevOps   | `azure-pipelines.yml`                | push to `master` |

The GitHub Actions deploy workflows use **OIDC** for short-lived credentials — no AWS access keys
or Azure service principal passwords are stored as secrets. See the comments at the top of each
workflow file for the one-time OIDC setup.

### GitHub Actions CI

`.github/workflows/ci.yml` runs on every PR:

- Maven backend `verify` (compile + test)
- Angular frontend `lint` + `build`
- Docker image build (no push) — confirms the Dockerfile is healthy

---

## API Reference

> **Note:** A Postman collection ships in `documentation/APIs.postman_collection`.

### 1. User Registration

```bash
curl -X POST http://localhost:8090/user/register \
  -H "Content-Type: application/json" \
  -d '{"firstName":"John","lastName":"Doe","email":"john@example.com","password":"P@ssw0rd123"}'
```

**Returns 201 Created**. User is created with `enabled=false` — see
[First-Time User Flow](#first-time-user-flow--important) above.

### 2. Account Verification

```bash
curl "http://localhost:8090/user/verify/account/<key-from-server-log>"
```

Sets `enabled=true` so the user can log in.

### 3. Login

```bash
curl -X POST http://localhost:8090/user/login \
  -H "Content-Type: application/json" \
  -d '{ "email": "john@example.com", "password": "P@ssw0rd123" }'
```

Returns `access_token` (30 min) and `refresh_token` (5 days). Use the access token in later requests:

```bash
curl -H "Authorization: Bearer <access_token>" http://localhost:8090/user/profile
```

### 4. Refresh access token

When the access token expires (401 response):

```bash
curl -H "Authorization: Bearer <refresh_token>" http://localhost:8090/user/refresh/token
```

### 5. Password reset flow

```bash
# Step 1 — request a reset link (server logs the URL)
curl http://localhost:8090/user/resetpassword/john@example.com

# Step 2 — verify the link
curl "http://localhost:8090/user/verify/password/<key>"

# Step 3 — set new password
curl -X POST "http://localhost:8090/user/resetpassword/<key>/NewPassword123/NewPassword123"
```

---

## Authentication & Authorization

### JWT lifecycle

1. **Login** → server verifies credentials → issues access (30 min) + refresh (5 days) tokens
2. **Protected request** → client sends `Authorization: Bearer <access>` header
3. **CustomAuthFilter** validates signature and expiration on every request
4. **SecurityFilterChain** checks authorities required for the endpoint
5. **Expired access** → client calls `/user/refresh/token` with the refresh token to get a new pair

### Token claims

**Access token** (carries authorities):

```json
{
  "sub": "1",
  "authorities": [
    "READ:USER",
    "UPDATE:USER",
    "DELETE:USER"
  ],
  "iss": "BOBBYLON_LLC",
  "aud": "BOBS_MANAGEMENT",
  "exp": 1715000000,
  "iat": 1714995600
}
```

**Refresh token** (no authorities — prevents misuse if leaked):

```json
{
  "sub": "1",
  "iss": "BOBBYLON_LLC",
  "aud": "BOBS_MANAGEMENT",
  "exp": 1715259600,
  "iat": 1714995600
}
```

---

## Error Responses

| Status | Body shape                                                     | When                                                                                |
|--------|----------------------------------------------------------------|-------------------------------------------------------------------------------------|
| 400    | `{ "reason": "User is disabled", ... }`                        | Account not verified — see [First-Time User Flow](#first-time-user-flow--important) |
| 400    | `{ "reason": "Could not decode the token. ...", ... }`         | Malformed JWT                                                                       |
| 401    | `{ "reason": "Token has expired", ... }`                       | Access token expired — call refresh                                                 |
| 403    | `{ "reason": "Access Denied: ...", ... }`                      | Authenticated but missing required authority                                        |
| 500    | `{ "reason": "An error has occurred, please try again", ... }` | Unhandled server error                                                              |

---

## Documentation Index

Deeper docs live in `documentation/`:

| File                                | What's inside                                                    |
|-------------------------------------|------------------------------------------------------------------|
| `DOCUMENTATION_INDEX.md`            | Map of architectural docs and where each concept is explained    |
| `DOCUMENTATION_SUMMARY.md`          | High-level overview of the documented files in the codebase      |
| `SPRING_SECURITY_DETAILED_GUIDE.md` | Complete Spring Security filter-chain walkthrough                |
| `FRONTEND_DOCUMENTATION_SETUP.md`   | Angular project structure and conventions                        |
| `REFACTORING_COMPLETE_SUMMARY.md`   | Summary of major refactors applied to the original tutorial code |
| `HELP.md`                           | Original Spring Initializr help notes                            |
| `APIs.postman_collection`           | Postman collection — import to exercise the API                  |
| `architectLayout.png`               | Architecture diagram                                             |

---

## Troubleshooting

### `400 Bad Request — "User is disabled"` on login

You registered but didn't verify. See [First-Time User Flow](#first-time-user-flow--important).
TL;DR: `docker compose logs app | grep "verification url"`, visit that URL, then log in again.

### Port 8080 (or 8090, or 3307) is in use

Edit `.env` and override `APP_PORT`, or change the published port in `docker-compose.yml`.

For native dev override Spring's port:
`./mvnw spring-boot:run -Dspring-boot.run.arguments=--server.port=8081`

### MySQL container won't start with "Access denied"

You changed `MYSQL_ROOT_PASSWORD` in `.env` after the volume was created. The original password is
baked into the volume's `mysql.user` table. Wipe the volume:

```bash
./deploy.sh --clean        # or .\deploy.ps1 -Clean
```

### App container is "unhealthy"

```bash
docker compose logs --tail=200 app
```

Usually one of:

- DB unreachable → check `mysql` container is healthy: `docker compose ps`
- Hibernate schema conflict → see the "Hibernate Column Naming Gotcha" note in `documentation/`
- `JWT_SECRET` missing → confirm `.env` has it

### Refreshing the page on an Angular route returns 401

The SPA loads from `/` correctly, but refreshing on a deep link like `/dashboard` or `/profile`
returns 401. This is because Spring sees the URL, doesn't find a matching static file, and the
catch-all security rule (`GET /**` requires `READ:USER`) takes over.

The fix is an SPA-forwarding mechanism — either:

1. A `@Controller` that forwards unmatched non-API paths to `/index.html`, or
2. A custom `PathResourceResolver` in `WebMvcConfig` that falls back to `index.html`.

Either approach requires also adding the forwarding paths to the Spring Security `permitAll` list
so the forward isn't itself blocked. This is a tracked future enhancement — for now, always
navigate from the root URL and let Angular Router handle in-app navigation.

### "Image isn’t found" from App Service / App Runner after first deployment

The CloudFormation / Bicep stack creates the registry but doesn't push the first image. Run
`./deploy.sh --aws-push` (or `--azure-push`) once, then the service can pull and start.

---

## Attribution

This project is based on **"Full Stack Spring Boot API with Angular (ADVANCED)"** by Junior from
[Get Arrays](https://www.getarrays.io/) on Udemy. Images in the UI are from
[unsplash.com](https://unsplash.com) and remain the property of their respective copyright holders.
Educational / portfolio use only.
