# GCP Deployment (Cloud Run)

**Version:** 1.1
**Last Updated:** 2026-08-19
**Status:** Reference — the GCP analog of [`../aws/README.md`](../aws/README.md). AWS is the live deployment; this path is built and deployable but not what production runs on.

Deploy TesseraApp to **Google Cloud Run** — serverless containers, the GCP analog of the
AWS ECS setup in [`../aws/`](../aws/). This reuses the same multi-stage [`Dockerfile`](../Dockerfile)
(Angular compiled into the Spring Boot JAR) and, by default, the same **Aiven** MySQL database
you already migrated to (`db3`). Cloud SQL and Cloud Build boilerplate are included for later.

> **Cost note:** creating a project in a Workspace org (e.g. `lewisu.edu`) does **not** grant free
> compute — you need a **billing account**. Cloud Run has a real always-free monthly tier that
> usually covers a demo; new billing accounts also get a $300/90-day trial. Check whether Lewis
> University provides Google Cloud credits (Google for Education) before assuming it's free.

---

## What's here

| File | Purpose |
|------|---------|
| `setup.sh` | One-time: enable APIs, create Artifact Registry repo, create runtime + deployer service accounts, grant roles |
| `secrets-setup.sh` | Create the app's secrets in **Secret Manager** (mirrors `aws/secrets-setup.sh`) |
| `cloudrun-service.yaml` | Declarative Cloud Run service (Knative) template — env + secret references |
| `cloudbuild.yaml` | **Cloud Build** pipeline (build → push → deploy), the GCP-native CI alternative |
| `cloudsql-setup.sh` | **Optional** — provision a Cloud SQL MySQL instance if you later move off Aiven |
| `../.github/workflows/deploy-gcp.yml` | **GitHub Actions** pipeline (the active CI path) |

## Active vs. later

- **Active:** GitHub Actions (`deploy-gcp.yml`) → build → **Artifact Registry** → **Cloud Run**, with the DB pointed at **Aiven `db3`**.
- **Later (boilerplate only):** `cloudbuild.yaml` (Cloud Build) and `cloudsql-setup.sh` (Cloud SQL) — wired but not the default path.

---

## Prerequisites

```bash
gcloud auth login
export GCP_PROJECT_ID="your-project-id"        # the project you created in the lewisu.edu org
export GCP_REGION="us-central1"                 # any Cloud Run region
```

## 1. One-time setup

```bash
./gcp/setup.sh          # enables APIs, creates the Artifact Registry repo + service accounts
./gcp/secrets-setup.sh  # creates Secret Manager secrets (with CHANGE_ME placeholders)
```

Then fill in the real secret values (the script prints the exact commands), e.g.:

```bash
printf '%s' "$(openssl rand -base64 48)" | gcloud secrets versions add tessera-jwt-secret --data-file=-
printf '%s' 'AVNS_...your-aiven-password' | gcloud secrets versions add tessera-db-password --data-file=-
```

## 2. Deploy

### Option A — GitHub Actions (active)
Add the repository secrets listed in [`deploy-gcp.yml`](../.github/workflows/deploy-gcp.yml), then
push to `master` or run the workflow manually. It builds, pushes to Artifact Registry, and deploys Cloud Run.

### Option B — Cloud Build (GCP-native, boilerplate)
```bash
gcloud builds submit --config gcp/cloudbuild.yaml \
  --substitutions=_REGION="$GCP_REGION",_REPO=tessera-app,_SERVICE=tessera-app
```

### Option C — one-off manual deploy
```bash
IMAGE="${GCP_REGION}-docker.pkg.dev/${GCP_PROJECT_ID}/tessera-app/tessera-app:latest"
gcloud builds submit --tag "$IMAGE" .        # or: docker build + docker push
gcloud run deploy tessera-app \
  --image "$IMAGE" --region "$GCP_REGION" \
  --platform managed --port 8080 --allow-unauthenticated
```
> `--image` and `--source` are mutually exclusive: passing `--image` is what makes this a
> deploy-a-prebuilt-image call rather than a build-from-source one. `--port 8080` must match the
> `EXPOSE`/`HEALTHCHECK` baked into the Dockerfile — see
> [GUIDE.md §11.2](../documentation/GUIDE.md#112-the-image).

This bare form deploys the image but carries **no environment variables and no secrets**, so the app
will fail fast on the `prod` profile's first missing placeholder — by design. The GitHub Actions
workflow and `cloudbuild.yaml` show the full flag set (`--set-env-vars` + `--set-secrets`); copy it
from there rather than hand-assembling one.

---

## Database

**Default: Aiven** — Cloud Run connects to your existing Aiven `db3` over public TLS, exactly like the
AWS ECS deploy does. Non-sensitive Aiven config (host/port/db/user) is passed as **env vars**; only the
**password** lives in Secret Manager. No VPC connector is needed because Aiven is publicly reachable.


**Later: Cloud SQL** — run `./gcp/cloudsql-setup.sh` to provision a managed MySQL instance, migrate `db3`
into it (same `mysqldump` flow as [GUIDE.md §9.7](../documentation/GUIDE.md#97-which-mysql-server)),
and add the Cloud Run `--add-cloudsql-instances` connection. Boilerplate is included but commented off.

---

## ⚠ Known gap — the proxy variables are not set on this path

**Cloud Run is a reverse proxy in front of your container, exactly like the ALB is on AWS** — but
unlike `aws/task-definition.json`, **`deploy-gcp.yml` sets none of the three proxy variables today**
(verified 2026-08-19). Deployed as-is, three controls are wrong and two of them fail *silently*:

| Variable | Needs to be | Left unset (default) |
|---|---|---|
| `TRUSTED_PROXY_COUNT` | `1` — Cloud Run's own front end is the single hop; add one more per CDN/LB you put ahead of it | `0`. `RequestUtils.getIpAddress` returns the front end's address for **everyone**, so the rate limiter collapses every caller into one bucket and the anomaly detector's `NEW_NETWORK` signal can never fire. Step-up verification looks present and does nothing |
| `FORWARD_HEADERS_STRATEGY` | `framework` | `none`. `{baseUrl}` in the OAuth2 redirect template resolves to the container's own address, so every federated sign-in dies on `redirect_uri_mismatch` |
| `OAUTH2_REDIRECT_BASE_URL` | the public Cloud Run origin | unset — derived from request headers, which is only correct when nothing rewrites `X-Forwarded-Proto` |

Add all three to the `--set-env-vars` list in
[`deploy-gcp.yml`](../.github/workflows/deploy-gcp.yml) before treating this path as production-ready.
Confirm from the boot log line `[NET] trusted-proxy-count=…`. Full rationale:
[GUIDE.md §3.2](../documentation/GUIDE.md#32-environment-variable-reference) and
[§7.8](../documentation/GUIDE.md#78-deployment-parity).

---

## ⚠ Known gap — profile image storage on Cloud Run

Cloud Run's filesystem is **ephemeral** (wiped on every cold start / new revision). The default
`IMAGE_STORAGE_PATH=/tmp/images` therefore **loses uploaded profile images** across restarts. For a
persistent deploy, use object storage:
- reuse the existing **S3** `ImageStorageService` implementation (set `IMAGE_STORAGE_TYPE=s3` + AWS creds), or
- add a **GCS** adapter to `ImageStorageService` (not yet built — a natural follow-up for an all-GCP setup).

This is fine for a demo; just know uploaded avatars won't survive a revision until object storage is wired.
