# CI/CD Setup Guide — AWS, Google Cloud, Azure

**Version:** 1.0
**Last Updated:** 2026-07-25
**Author:** Robert C. Oliver, Jr.
**Status:** Final

## Overview

How TesseraApp gets from a `git push` to a running container, on each of the three major clouds.
This is the **hub** for continuous integration and deployment: it explains the shared pipeline
shape, then routes to the per-cloud runbook that carries the actual commands.

It also records something the per-cloud guides do not: **which security controls only become real
once the pipeline exists.** Several features in this codebase are written and tested but are
inert — or actively weaker — until a deployment topology is in place. Those are called out in
§6 so they are not mistaken for gaps in the application code.

## Table of contents

- [1. What already exists](#1-what-already-exists)
- [2. The shared shape](#2-the-shared-shape)
- [3. Continuous integration (all clouds)](#3-continuous-integration-all-clouds)
- [4. Per-cloud deployment](#4-per-cloud-deployment)
- [5. Required configuration](#5-required-configuration)
- [6. Security controls that depend on the pipeline](#6-security-controls-that-depend-on-the-pipeline)
- [7. Choosing a cloud](#7-choosing-a-cloud)
- [8. Verifying a deploy](#8-verifying-a-deploy)

---

## 1. What already exists

Every asset below is committed. Nothing here needs writing from scratch — the remaining work is
account setup and secret population.

| Path | Cloud | What it is |
|---|---|---|
| [`.github/workflows/ci.yml`](../.github/workflows/ci.yml) | all | Build + test on every push, PR-gated on `master` |
| [`.github/workflows/deploy.yml`](../.github/workflows/deploy.yml) | AWS | Build → ECR → ECS Fargate |
| [`.github/workflows/deploy-gcp.yml`](../.github/workflows/deploy-gcp.yml) | GCP | Build → Artifact Registry → Cloud Run |
| [`azure-pipelines.yml`](../azure-pipelines.yml) | Azure | Azure DevOps pipeline (legacy path) |
| [`aws/`](../aws/) | AWS | `setup.sh`, `secrets-setup.sh`, `push-to-ecr.sh`, `task-definition.json`, `ecs-service.json` + [full runbook](../aws/README.md) |
| [`gcp/`](../gcp/) | GCP | `setup.sh`, `secrets-setup.sh`, `cloudrun-service.yaml`, `cloudbuild.yaml`, `cloudsql-setup.sh` + [full runbook](../gcp/README.md) |
| [`Dockerfile`](../Dockerfile) | all | One multi-stage image (Angular → Maven → JRE) used by every path |

> **Why one image for three clouds.** The Dockerfile compiles the Angular bundle *into* the Spring
> Boot jar, so the deployable artifact is a single container with no static-hosting story to
> configure separately. Environment differences are injected at runtime through
> `SPRING_ACTIVE_PROFILES` and env vars — never baked in — which is what makes the same image
> promotable across dev → qa → stage → prod without a rebuild.

---

## 2. The shared shape

All three clouds implement the same five stages. Only the vendor nouns change.

```
git push ──▶ CI: mvn verify + npm build ──▶ docker build (multi-stage)
                                                   │
                                                   ▼
                                        push to a container registry
                                     (ECR / Artifact Registry / ACR)
                                                   │
                                                   ▼
                                    deploy to a container runtime
                            (ECS Fargate / Cloud Run / App Service)
                                    │                          │
                        secrets from a vault          JDBC over TLS
                (Secrets Manager / Secret Manager      to managed MySQL
                        / Key Vault)                      (Aiven)
```

**The database is deliberately not per-cloud.** All three deploy paths point at the same managed
**Aiven MySQL** instance rather than RDS/Cloud SQL/Azure Database. That keeps the comparison
honest — the only variable between clouds is the compute and secret plumbing — and it avoids
paying for three managed databases during evaluation. `gcp/cloudsql-setup.sh` exists if you later
want to move.

---

## 3. Continuous integration (all clouds)

[`ci.yml`](../.github/workflows/ci.yml) runs on **every push to every branch**, and gates PRs into
`master`. Two parallel jobs:

**Backend** — Java 21, spins up a MySQL service container, applies `schema.sql` to it, then runs
`mvn --no-transfer-progress verify`. That single command covers more than tests: the OWASP
[`dependency-check-maven`](../pom.xml) plugin is bound into the build and **fails on any dependency
with CVSS ≥ 7**, so a vulnerable transitive dependency breaks the build rather than shipping.

**Frontend** — Node 22, `npm ci`, then four gates in deliberate order: `npm audit --audit-level=high`
(CVE scan), `npm run lint`, `npm test -- --no-watch` (Vitest, headless), and finally a production
Angular build. Lint runs *before* the tests because a missing `for` attribute is cheap to find and
there is no value in spending a test run to report it.

**CI is now a deploy gate, not just a signal.** Both deploy workflows call this one via
`workflow_call` and will not build an image until it passes. They previously triggered
independently on a push to `master`, so they raced — a commit with failing tests could reach the
registry before CI finished going red.

### Known CI gaps

All three previously-recorded gaps are **closed** (frontend tests now run in CI; the 13 standing
lint errors — 10 of them real accessibility defects — were fixed and `npm run lint` now gates;
`npm audit --audit-level=high` was added). What remains:

| Gap | Impact | Fix |
|---|---|---|
| **No end-to-end test** | Every layer is unit-tested, but nothing exercises a real browser against a running stack. A break at a seam — interceptor ↔ backend, OAuth redirect — passes CI. | Playwright against `docker-compose up` |
| **`npm audit` moderate findings are not gated** | Deliberate: Angular's transitive dev-dependency churn produces a steady trickle, and a permanently-red gate gets ignored. High/critical do fail. | Revisit only if moderates stop being noise |
| **Deploy is never rehearsed** | The pipeline is verified by reading, not by running. Neither cloud path has executed end to end. | One dispatch to a throwaway `qa` environment |

---

## 4. Per-cloud deployment

### 4.1 AWS — ECS Fargate (most complete)

**Runbook: [`aws/README.md`](../aws/README.md)** — nine infrastructure steps, or `./aws/setup.sh`
to run them all and print the ALB DNS name.

- **Registry:** ECR · **Runtime:** ECS Fargate · **Ingress:** ALB + ACM (TLS) · **Secrets:** Secrets Manager · **Logs:** CloudWatch · **Images:** S3
- **Pipeline:** [`deploy.yml`](../.github/workflows/deploy.yml) on push to `master` — configure credentials → ECR login → build/tag/push (commit SHA + `latest`) → render `task-definition.json` → deploy → wait for stability.
- **Two IAM roles, and the distinction matters:** the *execution* role is what ECS uses to pull the image and read secrets **before** your code runs; the *task* role is what your running container may do (S3 read/write for profile images). Merging them would hand the application permission to read every secret at runtime, not just receive the ones it was given.
- **Cost:** ALB (~$16/mo) and Aiven (~$19/mo) dominate; Fargate is per-task.

### 4.2 GCP — Cloud Run (cheapest to demo)

**Runbook: [`gcp/README.md`](../gcp/README.md)**

- **Registry:** Artifact Registry · **Runtime:** Cloud Run · **Ingress:** built-in HTTPS (no load balancer to buy) · **Secrets:** Secret Manager
- **Pipeline:** [`deploy-gcp.yml`](../.github/workflows/deploy-gcp.yml) — authenticate → build → push → `gcloud run deploy`. Currently **manual-trigger only**; the `push: branches: [master]` trigger is commented out. Uncomment it to make GCP the automatic path.
- **Scales to zero,** which is why it is the cheapest demo target — but it also means a cold start on the first request after idle, and **in-memory state does not survive**. See §6 on rate limiting.
- **Billing caveat:** a project inside a Workspace org (e.g. `lewisu.edu`) does *not* come with free compute. A billing account is required; new ones get a $300/90-day trial.

### 4.3 Azure — App Service (legacy)

**Reference: [`azure-pipelines.yml`](../azure-pipelines.yml) and [deployment.md §5, §8](deployment.md)**

This was the original target and is documented for completeness. It uses Azure DevOps rather than
GitHub Actions, so it is the odd one out in tooling as well as being the least recently exercised.
Prefer AWS or GCP for new work; keep this as the migration-history record.

---

## 5. Required configuration

Injected by the platform's secret store — never committed, never in a `.env` in the cloud.

```
SPRING_ACTIVE_PROFILES=prod
SPRING_DATASOURCE_URL=jdbc:mysql://<host>:<port>/<db>?useSSL=true&requireSSL=true
SPRING_DATASOURCE_USERNAME / SPRING_DATASOURCE_PASSWORD
JWT_SECRET                 # >= 32 chars — JwtSecretGuard FAILS STARTUP if weak or missing
UI_APP_URL                 # SPA origin: drives email links, OAuth redirects, and CORS
CONTAINER_PORT=8080
MAIL_HOST / MAIL_PORT / MAIL_USERNAME / MAIL_PASSWORD
IMAGE_STORAGE_TYPE=s3      # plus AWS_S3_BUCKET + AWS_REGION when s3
CORS_ALLOWED_ORIGINS       # optional — defaults to UI_APP_URL; set only for multiple origins
GOOGLE_/GITHUB_/MICROSOFT_ CLIENT_ID + CLIENT_SECRET   # federated login
TWILIO_ACCOUNT_SID / TWILIO_AUTH_TOKEN / TWILIO_FROM_NUMBER   # once SMS is un-stubbed
```

**Fail-fast is the design.** The `prod` profile carries no fallback values (unlike `dev`, which is
all literals so the app runs with zero configuration). A missing variable stops startup instead of
surfacing as a confusing runtime failure hours later.

> **Schema is not automated.** `spring.sql.init.mode` is `never` and `ddl-auto` is `validate` in
> prod. [`schema.sql`](../src/main/resources/schema.sql) is idempotent but must be applied **by
> hand** to a new database. This is a deliberate choice (Flyway was removed on purpose), and it is
> the one manual step in an otherwise automated pipeline — the most common cause of a deploy that
> builds cleanly and then fails at runtime.

---

## 6. Security controls that depend on the pipeline

These are written and tested in the codebase but are **not fully effective until deployed behind
real infrastructure**. Listing them here prevents two opposite errors: claiming them as complete,
and re-implementing them as if they were missing.

| Control | Why it needs the pipeline | Risk if deployed without it |
|---|---|---|
| **HSTS + secure cookies** | `SecurityConfig` already sends `Strict-Transport-Security`, but the header is meaningless over plain HTTP. TLS terminates at the ALB (AWS) or is built in (Cloud Run). | Credentials and JWTs travel in cleartext |
| **Rate limiting across replicas** | `RateLimitFilter` holds Bucket4j buckets **in memory, per instance**. With N replicas the effective limit is N× the configured one, and Cloud Run's scale-to-zero discards buckets entirely. | Brute-force protection weakens exactly as you scale up. **Fix: back buckets with Redis/ElastiCache/Memorystore.** |
| **Client IP accuracy** | `RequestUtils.getIpAddress` reads `X-Forwarded-For` and `server.forward-headers-strategy` is **not set**. Behind a load balancer the header is set correctly — but the app trusts it *unconditionally*, so a caller can forge it. | Two live controls degrade: rate limiting can be bypassed by rotating a spoofed header, and FR-TPF-1's `NEW_NETWORK` anomaly signal can be defeated by forging a familiar network. **Fix before deploy: trust only the LB-appended entry.** |
| **Secret rotation** | Secrets Manager / Secret Manager entries exist; nothing rotates them. | A leaked secret stays valid indefinitely |
| **Dependency CVE gate** | Enforced by `mvn verify` — only meaningful because CI runs it on every push. | Vulnerable dependencies reach production |
| **Image storage durability** | `IMAGE_STORAGE_TYPE=s3` must be set; the local-filesystem default does not survive a container restart or span replicas. | Profile images vanish on redeploy |

---

## 7. Choosing a cloud

| If you want… | Choose | Because |
|---|---|---|
| The most complete, closest-to-industry setup | **AWS** | Nine documented steps, explicit IAM role separation, ALB + ACM, the only path with a one-command bootstrap |
| The cheapest way to show a live URL | **GCP Cloud Run** | Scales to zero, HTTPS included, no load balancer to pay for |
| Continuity with the original build | **Azure** | Already written, but Azure DevOps rather than GitHub Actions |

For a demo where cost matters, GCP; for the deliverable that best demonstrates production
practice, AWS.

---

## 8. Verifying a deploy

1. `GET /actuator/health` returns `200 {"status":"UP"}` — the container's health check. Only `health` and `info` are exposed, with `show-details: never`, so this leaks nothing.
2. Register a user, verify by email — proves SMTP and `UI_APP_URL` are correct.
3. Log in — proves the datasource, `JWT_SECRET`, and schema all line up.
4. Sign in from a second device, then check **Security Center → Sessions & devices** — proves session tracking works behind the load balancer, and shows whether client IPs are real or all reading as the LB (see §6).
5. Complete one federated login — proves the OAuth redirect URIs registered in each provider's console match the deployed backend URL.
6. Upload a profile image, then force a new deployment and reload it — proves S3 storage rather than container-local disk.

## Related documents

- [deployment.md](deployment.md) — the Docker image, Compose, and cloud principles
- [configuration.md](configuration.md) — every environment variable in detail
- [security.md](security.md) — the security model these controls implement
- [database.md](database.md) — applying `schema.sql` to a new instance
- [aws/README.md](../aws/README.md) · [gcp/README.md](../gcp/README.md) — the per-cloud runbooks
