# Deployment Guide

How to package and run TesseraApp beyond local development: the Docker image, Docker Compose, the Azure CI/CD pipeline, and notes for other cloud platforms.

> **See also:** [getting-started.md](getting-started.md) (local dev) · [configuration.md](configuration.md) (env vars) · [database.md](database.md) (schema init).

---

## Table of contents

1. [Options at a glance](#1-options-at-a-glance)
2. [The Docker image (multi-stage build)](#2-the-docker-image-multi-stage-build)
3. [Docker Compose (local full stack)](#3-docker-compose-local-full-stack)
4. [Cloud deployment principles](#4-cloud-deployment-principles)
5. [Azure CI/CD pipeline](#5-azure-cicd-pipeline)
6. [Other platforms](#6-other-platforms)
7. [Pre-deployment checklist](#7-pre-deployment-checklist)
8. [Legacy Azure reference](#8-legacy-azure-reference)
9. [Troubleshooting](#9-troubleshooting)

---

## 1. Options at a glance

| Target | How | Notes |
|--------|-----|-------|
| Local full stack | `start.sh ENV=docker` → Docker Compose | App + MySQL containers; production-like |
| Manual image | `docker build` + `docker run` | The self-contained JAR image |
| Azure | `azure-pipelines.yml` → ACR + App Service | Auto-deploys on push to `master` |
| Railway / Render / Fly.io / Cloud Run | the Dockerfile | Set env vars in the platform; use a managed DB |

The single deployable artifact is the **Docker image**: a slim JRE running one JAR that contains both the Spring Boot API and the compiled Angular app.

---

## 2. The Docker image (multi-stage build)

`Dockerfile` uses **three stages** so the final image carries no build tooling:

```
Stage 1  node:22-alpine            → npm ci && npm run build   → dist/securecapitaapp/browser/
Stage 2  maven:3.9-temurin-21      → copy Angular dist into src/main/resources/static/
                                       mvn package -DskipTests -Pprod   → target/*.jar
Stage 3  eclipse-temurin:21-jre-alpine
           - runs as a non-root user (appuser)
           - EXPOSE 8080
           - HEALTHCHECK → GET /actuator/health
           - ENTRYPOINT java -Dspring.profiles.active=prod -jar app.jar
```

Key points:
- **The Angular app is baked into the JAR** (stage 2 copies the build output into `static/`), so in production Spring Boot serves the SPA itself — no separate web server.
- **Runs the `prod` profile**, so every required env var must be supplied (no dev fallbacks).
- **Non-root + healthcheck** make it container-orchestrator-friendly.

Build and run it directly:

```bash
docker build -t securecapita:latest .

docker run -p 8080:8080 \
  -e SPRING_ACTIVE_PROFILES=prod \
  -e SPRING_DATASOURCE_URL="jdbc:mysql://<host>:3306/db2?useSSL=true&requireSSL=true" \
  -e SPRING_DATASOURCE_USERNAME=<user> \
  -e SPRING_DATASOURCE_PASSWORD=<pass> \
  -e JWT_SECRET=<random> \
  -e CONTAINER_PORT=8080 \
  -e UI_APP_URL=https://your-app.example.com \
  securecapita:latest
```

> Remember to apply `schema.sql` to the target database once before first launch — the image does not run it (see [database.md](database.md)).

---

## 3. Docker Compose (local full stack)

`docker-compose.yml` defines two services:

- **`mysql`** (`mysql:8.4`) — with a healthcheck and a named volume (`mysql-data`) for persistence.
- **`app`** — built from the `Dockerfile`, published on `${APP_PORT:-8090}:8080`, `depends_on` MySQL being healthy. It overrides the datasource for Docker networking:
  ```yaml
  MYSQL_HOST: mysql
  SPRING_DATASOURCE_URL: jdbc:mysql://mysql:3306/${MYSQL_DATABASE}?useSSL=false&allowPublicKeyRetrieval=true
  CONTAINER_PORT: 8080
  UI_APP_URL: http://localhost:${APP_PORT:-8090}
  ```

Run it via the launcher (`ENV=docker` in `start.sh`) or directly:

```bash
docker compose up --build          # build + start
docker compose down -v             # stop + wipe the MySQL volume (fresh DB next time)
docker compose logs app            # tail app logs
docker compose logs mysql          # tail MySQL logs
```

➡ App at **http://localhost:8090**.

---

## 4. Cloud deployment principles

The image is built to be cloud-portable:

- **All config is environment variables** — set them through the platform (App Service settings, Cloud Run env vars, Kubernetes Secrets). Never ship a `.env`.
- **Use the `prod` profile** so a missing variable fails fast.
- **Use a managed database** (Aiven, RDS, Cloud SQL) — not the Compose MySQL container. Set `useSSL=true&requireSSL=true` in `SPRING_DATASOURCE_URL`.
- **Apply `schema.sql` once** to the managed DB before first launch.
- **Health check:** point the platform's probe at `GET /actuator/health` (the only health endpoint exposed; details are hidden).
- **The image is stateless** — except for profile-image uploads, which currently write to the container's local filesystem (a known limitation; see [security.md §13](security.md#13-known-limitations)).

---

## 5. Azure CI/CD pipeline

`azure-pipelines.yml` builds and deploys automatically on every push to **`master`**:

```
trigger: master
│
├─ Stage "Build"   → Docker@2 buildAndPush  → Azure Container Registry (tags: <BuildId>, latest)
│
└─ Stage "Deploy"  → AzureWebAppContainer@1 → Azure App Service (pulls <BuildId> from ACR)
```

**One-time setup** (documented in the file's header comments):
1. Create an **Azure Container Registry** (note its login server).
2. Create an **App Service** (Linux, Docker Container).
3. Create an **Azure Database for MySQL** (or use Aiven); create the `db2` schema and apply `schema.sql`.
4. Add the datasource env vars to **App Service ▸ Configuration**: `SPRING_DATASOURCE_URL/USERNAME/PASSWORD` (plus `JWT_SECRET`, mail, etc.).
5. Create two **Service Connections** in Azure DevOps: a Docker Registry connection (to ACR) and an Azure Resource Manager connection (to your subscription).
6. Update the `variables:` block (registry/app names + service-connection names) to match your resources.

Redeploy = just push:
```bash
git push        # pipeline builds the image, pushes to ACR, and restarts the App Service
```

---

## 6. Other platforms

The Dockerfile works as-is on most container hosts. Roughly in order of setup simplicity:

| Platform | Notes |
|----------|-------|
| **Railway** | Simplest: connect the repo, add a MySQL plugin, set env vars in the dashboard |
| **Render** | Similar; free tier spins down on inactivity |
| **Fly.io** | Docker-native, uses your Dockerfile directly, good free tier |
| **Google Cloud Run** | Serverless containers, scales to zero; pair with Cloud SQL |

For all of them: set the env vars, point at a managed MySQL, and apply `schema.sql` once.

---

## 7. Pre-deployment checklist

- [ ] `SPRING_ACTIVE_PROFILES=prod`
- [ ] Managed MySQL provisioned; `db2` schema created; **`schema.sql` applied**
- [ ] `useSSL=true&requireSSL=true` in `SPRING_DATASOURCE_URL`
- [ ] Strong `JWT_SECRET` set via the platform (not committed)
- [ ] Mail + any OAuth/Twilio secrets set via the platform
- [ ] `UI_APP_URL` set to the public frontend URL (drives CORS + email links)
- [ ] Health probe → `GET /actuator/health`
- [ ] Confirm the frontend's API base URL matches the deployed backend (see [frontend notes](../securecapitaapp/README.md))

> Note: the Angular services currently target `http://localhost:8080`; deploying to a different backend origin requires updating the frontend's environment files and rebuilding. This is a known rough edge.

---

## 8. Legacy Azure reference

> The project was previously deployed to Azure. This is **reference only** — it may be inactive — kept so the resources can be revisited or migrated.

**Live URL (may be inactive):** `https://angularspringbootfullstack-ehd6dkevc3edgxer.centralus-01.azurewebsites.net`

| Resource | Name |
|----------|------|
| Container Registry | `bobsAngularApp` |
| ACR login server | `bobsangularapp-cnh8fzfxasa6feav.azurecr.io` |
| App Service | `angularSpringBootFullStack` |
| Resource group | `bobsresourcegroup` |

**Database — Aiven MySQL (free tier):** host `bobbylonsdb-bobbylon.a.aivencloud.com`, port `11275`, schema `db2`, user `avnadmin`. Recreate the schema by running `src/main/resources/schema.sql` against it (it includes the seed roles + events).

**App Service settings:** `SPRING_DATASOURCE_URL = jdbc:mysql://bobbylonsdb-bobbylon.a.aivencloud.com:11275/db2?useSSL=true&requireSSL=true`, `SPRING_DATASOURCE_USERNAME = avnadmin`, `SPRING_DATASOURCE_PASSWORD = <from Aiven dashboard>`.

**Service connections:** `bobsDockerRegistryServiceConnection` (Docker Registry → ACR), `bobsAzureServiceConnection` (Azure Resource Manager → subscription).

---

## 9. Troubleshooting

> **Audience:** anyone whose container won't boot, can't reach the database, or whose pipeline deploys a dead image. Every symptom below is keyed to a real log line and the file that emits it. Start with the container's own logs (`docker logs <id>`, App Service ▸ Log stream, `docker compose logs app`) — the prod profile fails *fast* and *loud*, so the first stack trace almost always names the cause.
> **See also:** [configuration.md §8](configuration.md#8-configuration-gotchas-read-this) (the same gotchas from the config angle) · [database.md](database.md) (schema init) · [getting-started.md](getting-started.md) (local boot).

The cloud image always runs the **`prod`** profile (`Dockerfile:30` — `ENTRYPOINT ... -Dspring.profiles.active=prod`), which supplies **no fallbacks**. That is by design: a missing variable aborts startup instead of silently booting insecure. Most "won't start" reports are simply a required variable that wasn't set on the platform.

### Container will not start (common errors)

Two startup guards fail the boot before the web server binds a port: the prod placeholder resolution (a missing required env var) and `JwtSecretGuard` (a weak/placeholder `JWT_SECRET`). Both throw during `ApplicationContext` refresh, so the JVM exits non-zero and the orchestrator marks the container crashed/`unhealthy`.

| Symptom (in startup logs) | Likely cause | Fix |
|---|---|---|
| `JWT_SECRET is not set. The prod profile requires a strong, randomly generated secret ... Refusing to start.` | prod profile, `JWT_SECRET` env var absent | Set a strong secret on the platform: `openssl rand -base64 48` (`JwtSecretGuard.java:64-68`) |
| `JWT_SECRET is still the dev/placeholder value. ... Refusing to start.` | the `.env.example` placeholder (`replace-with-...`) or the dev fallback leaked into prod | Use a unique random secret — the guard rejects both known literals (`JwtSecretGuard.java:46-49`, `:69-73`) |
| `JWT_SECRET is too short (N chars); it must be at least 32 characters for HMAC512 signing.` | secret under 32 chars | Use ≥ 32 chars (64+ preferred for HMAC512) (`JwtSecretGuard.java:74-78`) |
| `Could not resolve placeholder 'MYSQL_USERNAME'` (or `MYSQL_PASSWORD` / `JWT_SECRET` / `MAIL_HOST` / `UI_APP_URL` …) | a **required** env var is absent and prod has no fallback | Set every variable in the [§7 checklist](#7-pre-deployment-checklist); confirm they reached the container (App Service ▸ Configuration, Compose `environment:`) |
| `Schema-validation: missing table [customer]` / `missing column [...]` | prod's `ddl-auto: validate` (`application-prod.yml:31`) found drift, or `schema.sql` was never applied | Apply `src/main/resources/schema.sql` to the managed DB **before** first launch (the image never runs it — `sql.init.mode: never`, `application.yml:52`); reconcile entity vs column names |
| `Circular placeholder reference '<NAME>'` | a self-referential override (`X: ${X}`) was reintroduced into a profile YAML | Should not occur in current code (see §9, *circular pitfall* below); remove any `X: ${X}` pass-through you added |
| container exits immediately, **no** app log at all | wrong arch/base image, malformed `ENTRYPOINT`, or the port is already taken | `docker logs <id>`; verify the host can run a `linux/amd64` (or matching) image and that `CONTAINER_PORT` is free |
| HEALTHCHECK flaps to `unhealthy` though the app booted | probe can't reach the app *inside* the container, or boot exceeded the grace window | The healthcheck `wget`s `http://localhost:8080/actuator/health` **inside** the container (`Dockerfile:28-29`) — the app must listen on `8080` there regardless of the host `APP_PORT` mapping; cold DBs can push boot past `--start-period=60s` |

> **Note — `CONTAINER_PORT` is the *in-container* port, not the published one.** The healthcheck and `EXPOSE` are hard-wired to `8080` (`Dockerfile:27-29`); Compose maps the host side separately via `${APP_PORT:-8090}:8080`. Changing `CONTAINER_PORT` to anything but `8080` desyncs it from the baked-in healthcheck — leave it at `8080` in the container.

### The circular config-placeholder pitfall (fixed)

A subtle Boot-4 startup crash the project hit and **fixed** — documented here so it isn't reintroduced. A property whose placeholder names *itself* (`MYSQL_USERNAME: ${MYSQL_USERNAME}`, `CONTAINER_PORT: ${CONTAINER_PORT:8080}` redeclared in a profile) resolves in a circle whenever the env var is absent, and Boot 4 aborts with `Circular placeholder reference '<NAME>'`. The two profiles now sidestep it with **opposite** strategies:

| Profile | Strategy | Why | Where |
|---|---|---|---|
| `dev` | **plain literals** — `MYSQL_USERNAME: root`, `JWT_SECRET: devOnly...`, `CONTAINER_PORT: 8080` | the app boots locally with **zero** config (no `.env`), yet an exported env var still overrides because OS env > profile YAML in precedence | `application-dev.yml:11-28` |
| `prod` | **direct env reads, no redeclaration / no fallback** — base `application.yml` reads `${MYSQL_USERNAME}`, `${JWT_SECRET}` etc. once, at the point of use | a missing secret **fails fast** at startup instead of silently using an insecure default; nothing is self-referential, so the circular error cannot arise | `application-prod.yml:1-12`, `application.yml:13-25` |

**The rule:** never write `X: ${X}` (or `X: ${X:default}`) in a profile YAML. Dev pins a literal; prod lets base `application.yml` read the env var directly. Both files carry header comments warning against the redeclaration that used to trip this. See [configuration.md §8 gotcha #1](configuration.md#8-configuration-gotchas-read-this).

### DB connection timeouts in the cloud

A container that boots but then times out talking to a managed database is almost always a **network reachability** problem (firewall / security group / VPC), not a credentials problem — credentials fail fast with an auth error, the network path fails *slowly* with a timeout.

| Symptom | Likely cause | Fix |
|---|---|---|
| `Communications link failure` then `Connection refused` | wrong host/port, or the DB isn't listening | Verify the host:port in `SPRING_DATASOURCE_URL`; confirm the managed instance is running |
| `Communications link failure` — `The last packet sent ... was N ms ago` / connect timeout | the app's egress is blocked from reaching the DB | Allowlist the app's outbound IP in the DB firewall: Aiven ▸ *Allowed IP Addresses*, RDS *security group*, Cloud SQL *authorized networks* / private IP |
| `No such host is known (mysql)` | using the Compose **service name** `mysql` outside Compose | Set a real hostname; `mysql` only resolves on the Compose network ([configuration.md §8 gotcha #4](configuration.md#8-configuration-gotchas-read-this)) |
| `Public Key Retrieval is not allowed` | MySQL 8 `caching_sha2_password` over a non-TLS connection without the flag | Use TLS in the cloud (next section) or add `allowPublicKeyRetrieval=true` (only acceptable for local/Docker, never managed) |
| First request after idle hangs ~30 s, then succeeds | free-tier DB scaled to zero / cold connection pool | Free tiers (Render, some Aiven plans) sleep on inactivity — the first request wakes them; raise the platform's startup grace if the probe trips |

> **Gotcha:** the Compose service overrides the URL to `useSSL=false&allowPublicKeyRetrieval=true` for the *local* container (`docker-compose.yml`, see [§3](#3-docker-compose-local-full-stack)). That string is for the throwaway container only — do **not** copy it to a managed DB, which needs TLS.

### SSL/TLS with managed MySQL (Aiven `requireSSL`)

Managed MySQL (Aiven, and most RDS/Cloud SQL setups) **requires** an encrypted connection — Aiven rejects plaintext outright. But the base datasource URL in `application.yml:23` is built for the *local* case and ends with `?useSSL=false&allowPublicKeyRetrieval=true`. So for a managed DB you must **override the whole URL** with the SSL variant; you do not edit `application.yml`.

Relaxed binding means `SPRING_DATASOURCE_URL` (and `SPRING_DATASOURCE_USERNAME` / `SPRING_DATASOURCE_PASSWORD`) **takes precedence** over the URL assembled from `MYSQL_*` — that is the supported override seam ([configuration.md §2](configuration.md#2-where-configuration-comes-from-and-precedence)). Set it on the platform to the TLS form (the exact shape `start.sh` uses for `DB=aiven`, `start.sh:133`):

```
SPRING_DATASOURCE_URL=jdbc:mysql://<host>:<port>/db2?useSSL=true&requireSSL=true
SPRING_DATASOURCE_USERNAME=<user>
SPRING_DATASOURCE_PASSWORD=<pass-from-provider-dashboard>
```

| Symptom | Likely cause | Fix |
|---|---|---|
| `SSL connection ... is required` / `Connections using insecure transport are prohibited` | connecting to Aiven/managed DB with `useSSL=false` | Override `SPRING_DATASOURCE_URL` with `useSSL=true&requireSSL=true` (above) |
| `Public Key Retrieval is not allowed` against a managed DB | no TLS, so MySQL 8 won't hand over the auth public key | Enable TLS as above (do not "fix" it with `allowPublicKeyRetrieval=true` on a managed DB) |
| TLS handshake / certificate-verification errors | server-cert verification turned on without a CA truststore | `useSSL=true&requireSSL=true` encrypts but does **not** verify the server cert; for full verification add the provider's CA to a JKS truststore and append `&verifyServerCertificate=true` plus the truststore JVM args. The shipped config encrypts only — adequate for the demo, hardened verification is future work |

> **Note:** Aiven assigns a **non-standard port** (the legacy instance used `11275`, see [§8](#8-legacy-azure-reference)), not `3306`. Read host, port, user, and password from the provider's dashboard and put them in `SPRING_DATASOURCE_URL` — `MYSQL_PORT`'s `3306` default does not apply once you override the full URL.

### Debugging a failed deploy

A repeatable order of checks, cheapest first:

1. **Read the container's own logs.** App Service ▸ *Log stream* (or *Deployment Center* logs), `docker logs <id>`, `docker compose logs app`. The prod profile fails fast, so the first exception is usually the whole story — match it against the tables above.
2. **Did the image even change?** The Azure pipeline tags each build with the Build ID and `latest` and the App Service pulls that tag ([§5](#5-azure-cicd-pipeline)). If behaviour didn't change after a push, confirm the new tag was pulled (App Service can pin a stale digest) and that the **Build** stage actually succeeded before **Deploy** ran.
3. **Reproduce the prod image locally.** Most "works on my machine" gaps are the dev-vs-prod profile difference. Run the real image with the prod profile and your cloud env vars:
   ```bash
   docker build -t tessera:debug .
   docker run -p 8080:8080 --env-file ./prod.env tessera:debug
   ```
   If it fails the same way locally, it's config/schema, not the platform.
4. **Probe health directly.** `curl http://<host>/actuator/health` should return `{"status":"UP"}`. Only `health` and `info` are exposed and health detail is hidden (`application.yml:103-109`), so a bare `UP`/`DOWN` is all you get — a `DOWN` (or connection refused) with a clean startup log points at the DB datasource. The platform probe must target `GET /actuator/health` ([§4](#4-cloud-deployment-principles)).
5. **Verify env vars landed.** A variable set in the dashboard but not visible to the process is the #1 cause of `Could not resolve placeholder`. Confirm names exactly (`SPRING_DATASOURCE_URL`, not `DATABASE_URL`) and that there's no stray `.env` baked into the image (never ship one — [§4](#4-cloud-deployment-principles)).
6. **Confirm the schema exists.** `ddl-auto: validate` (prod) refuses to start against a DB whose tables/columns don't match the entities. Apply `schema.sql` once by hand to a fresh managed DB ([database.md](database.md)).

| Failure | Where | What you see |
|---|---|---|
| Pipeline red before deploy | Azure **Build** stage | `mvn package` or `npm run build` error in the DevOps build log; no new image pushed to ACR |
| Image deploys but container crash-loops | App Service / orchestrator | container restarts; *Log stream* shows the startup exception (placeholder, JWT guard, or schema-validation) |
| Container `UP` but every API call 500s | runtime, post-boot | health is `UP` but requests fail — usually the DB went unreachable after boot (see *DB connection timeouts*) or `UI_APP_URL` CORS mismatch blocks the browser |
| App serves but login/email links point at the wrong host | config | `UI_APP_URL` not set to the public origin (drives CORS + email links — [§7 checklist](#7-pre-deployment-checklist)) |
