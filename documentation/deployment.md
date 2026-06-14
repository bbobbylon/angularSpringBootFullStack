# Deployment Guide

How to package and run SecureCapita beyond local development: the Docker image, Docker Compose, the Azure CI/CD pipeline, and notes for other cloud platforms.

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
