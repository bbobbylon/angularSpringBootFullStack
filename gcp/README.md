# GCP Deployment (Cloud Run)

**Version:** 1.2
**Last Updated:** 2026-09-05
**Status:** **Decided, not yet cut over.** This pipeline is built and ready to auto-deploy on push
to `master`, but production is still running on AWS ECS Fargate — the account-side steps below
(§2.8 of FUTURE-ENHANCEMENTS.md) have not been done yet. Once they are, AWS moves to **paused, not
deleted** (see [`aws/RUNBOOK.md` → Pausing AWS](../aws/RUNBOOK.md#pausing-aws--stop-the-bill-without-deleting-anything)) and this file's status line should be updated to say so.

Deploy TesseraApp to **Google Cloud Run** — serverless containers, the GCP analog of the
AWS ECS setup in [`../aws/`](../aws/). This reuses the same multi-stage [`Dockerfile`](../Dockerfile)
(Angular compiled into the Spring Boot JAR) and, by default, the same **Aiven** MySQL database
you already migrated to (`db3`). Cloud SQL and Cloud Build boilerplate are included for later.

> **Billing account required.** Creating a project in a Workspace org (e.g. `lewisu.edu`) does
> **not** grant free compute — Cloud Run's always-free tier is applied *to* a billed project, it is
> not a substitute for one. New billing accounts also get a $300/90-day trial. The cost comparison
> that motivated the move is in the next section.

---

## Why Cloud Run is the planned move (decided 2026-09-05)

The move is about cost, not capability — both paths run the same image against the same database.

| | AWS (ECS Fargate + ALB), steady state | Cloud Run, steady state |
|---|---:|---:|
| Compute | ~$18 (one Fargate task, always on) | **~$0** — `--min-instances 0` scales to zero; CPU is billed only while a request is in flight, and the always-free tier (2M requests, 180k vCPU-s, 360k GiB-s per month) covers a demo's traffic outright |
| Load balancer / TLS | ~$16 (ALB — an hourly charge even at zero traffic) | **$0** — Cloud Run terminates TLS itself; the custom domain is a free *domain mapping*, not a Cloud Load Balancer (which would cost ~$18/mo and defeat the point) |
| Secrets | ~$4 (10 × $0.40) | ~$0.60 (six secret versions free, $0.06 each after) |
| Container registry | ~$0 | ~$0 (0.5 GB free — prune old tags; each image is ~300 MB) |
| Logs | ~$0 | ~$0 (50 GiB/month free) |
| Aiven MySQL | ~$19 | ~$19 — **unchanged**, same `db3` |
| **Total** | **~$57/mo** | **~$20/mo** — the database is now the whole bill |

Figures are list prices as of 2026-09; read the billing console after the first full month. AWS is
currently billing its full ~$57/mo steady-state rate — the ~$20/mo Cloud Run figure only applies
once the cutover below is complete and AWS is paused per the RUNBOOK section linked above.

The one thing this shape trades away is **cold starts**: a scaled-to-zero JVM takes roughly
10–20 s to answer the first request after an idle period. `--cpu-boost` (startup CPU boost)
roughly halves that. If it ever bothers you, `--min-instances 1` removes it entirely for about
$8–10/mo — still well under the AWS figure.

**Why not Render?** Render's free and $7 Starter tiers give a service 512 MB of RAM. A Spring
Boot 4 JVM serving the embedded Angular app is sized at 1 GiB here (the AWS task ran at 1024 MB),
and Render's first 2 GB tier is $25/mo — more than Cloud Run with a permanently warm instance.
Render also has no secret store beyond plain env vars, and this Cloud Run pipeline, its secrets
kit and its docs already existed. Same reasoning applies to Railway and Fly.io at the RAM this
JVM needs.

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

## ✅ Proxy variables — fixed

**Cloud Run is a reverse proxy in front of your container, exactly like the ALB is on AWS.**
`deploy-gcp.yml`'s `--set-env-vars` list now sets all three proxy controls (`TRUSTED_PROXY_COUNT=1`,
`FORWARD_HEADERS_STRATEGY=framework`, `OAUTH2_REDIRECT_BASE_URL=${{ secrets.APP_DOMAIN }}`) — this
section previously (as of 2026-08-19) documented them as unset; that has since been corrected in the
workflow but the doc wasn't updated until now. Confirm from the boot log line
`[NET] trusted-proxy-count=…` after your first deploy. Full rationale:
[GUIDE.md §3.2](../documentation/GUIDE.md#32-environment-variable-reference) and
[§7.8](../documentation/GUIDE.md#78-deployment-parity).

---

## Profile image storage — the existing S3 bucket, reused

Cloud Run's filesystem is **ephemeral** (wiped on every cold start and every new revision), so
`IMAGE_STORAGE_TYPE=local` would lose every uploaded avatar. The workflow therefore sets
`IMAGE_STORAGE_TYPE=s3` and points at the **same `tessera-app-images` bucket the AWS deployment
used** — nothing is migrated, and avatars uploaded before the move keep working. Cloud Run cannot
assume an ECS task role, so it authenticates with static keys for a **bucket-scoped IAM user**,
created in [`aws/RUNBOOK.md` → Pausing AWS](../aws/RUNBOOK.md#pausing-aws--stop-the-bill-without-deleting-anything)
and stored in Secret Manager as `tessera-aws-access-key-id` / `tessera-aws-secret-access-key`
(`secrets-setup.sh` creates both with placeholders). S3 stays inside its 5 GB free tier, so this
is the cheapest persistent option. A GCS adapter for `ImageStorageService` is the all-GCP
follow-up ([FUTURE-ENHANCEMENTS.md §6.2](../documentation/FUTURE-ENHANCEMENTS.md#62-scale-out-blockers))
and buys nothing on cost.

---

## Scaling is pinned to one instance — on purpose

`--max-instances 1` is **not** a cost setting. The rate limiter keeps its buckets in-process (a
documented, accepted constraint —
[FUTURE-ENHANCEMENTS.md §2.4](../documentation/FUTURE-ENHANCEMENTS.md#24--move-per-instance-security-state-off-the-heap)),
so a second instance silently doubles every caller's request budget. On ECS the trigger was
`desired_count > 1`; on Cloud Run it is `--max-instances > 1`, and autoscaling would cross it *for
you* under load, which is why it is pinned rather than left at a default. Raise it only together
with the shared-rate-limit work. Concurrency stays at Cloud Run's default of 80 requests per
instance, ample for a single tenant.

---

## Cutover from AWS — what still needs your account (nothing here is code)

Everything in the repository is done: workflow, service template, secrets script, docs. The
remaining steps need `gcloud`, AWS and Porkbun access, in this order. Do **not** tear AWS down —
pause it (step 7).

1. **GCP project + billing.** Create the project and attach a billing account. Then
   `./gcp/setup.sh` and `./gcp/secrets-setup.sh`.
2. **Fill the secrets.** Copy the real values out of AWS Secrets Manager
   (`aws secretsmanager get-secret-value` — see [`aws/README.md`](../aws/README.md) "Checking
   secret values") into the matching `tessera-*` secrets: DB password, mail, the three OAuth pairs,
   Twilio. The **JWT secret is freshly generated**, so every existing session is signed out once —
   expected, and no data is affected. Then create the S3 IAM user (RUNBOOK → Pausing AWS) and store
   its two keys.
3. **GitHub secrets** listed at the top of `deploy-gcp.yml`. `S3_BUCKET` and `AWS_REGION` already
   exist for `deploy.yml` and are reused as-is. Set `APP_DOMAIN=https://tesseraapp.dev` from the
   start — the domain does not change, so there are **no OAuth callback URL changes** anywhere
   (this matters: GitHub OAuth Apps allow exactly one callback URL each).
4. **First deploy.** Run the *Deploy (GCP Cloud Run)* workflow manually, or push to `master` — the
   push trigger is on. Confirm `GET <run.app URL>/actuator/health` → `{"status":"UP"}` and that the
   boot log shows `[NET] trusted-proxy-count=1`.
5. **Custom domain** — next section. Until DNS moves, `tesseraapp.dev` keeps serving from AWS, so
   there is no downtime window to plan around.
6. **Smoke test** on `https://tesseraapp.dev` per
   [GUIDE.md §7.8](../documentation/GUIDE.md#78-deployment-parity): password login, one federated
   login, one passkey login (passkeys are bound to the domain, not the cloud — they survive
   unchanged), one avatar upload.
7. **Pause AWS** — RUNBOOK → Pausing AWS. Scale the ECS service to 0; optionally delete only the
   ALB. Leave secrets, S3, CloudFront, ECR and IAM alone.
8. **Billing alert on the GCP side** — Billing → Budgets & alerts: a $10/month budget with email
   at 50/90/100 %. This is the GCP half of
   [FUTURE-ENHANCEMENTS.md §2.5](../documentation/FUTURE-ENHANCEMENTS.md#25---turn-on-cost-visibility--infra-only-no-code).

---

## Custom domain — `tesseraapp.dev` on Cloud Run

Cloud Run **domain mappings** put the existing domain on the service with no load balancer and
no charge. They are a preview feature with somewhat higher TLS-handshake latency than a Cloud
Load Balancer; for a single-tenant demo that trade is the right one.

```bash
# 1. Prove domain ownership once (a Search Console TXT record at Porkbun; gcloud prints the value)
gcloud domains verify tesseraapp.dev

# 2. Map apex and www to the service
gcloud beta run domain-mappings create --service tessera-app --domain tesseraapp.dev     --region "$GCP_REGION"
gcloud beta run domain-mappings create --service tessera-app --domain www.tesseraapp.dev --region "$GCP_REGION"

# 3. Read back the DNS records to create
gcloud beta run domain-mappings describe --domain tesseraapp.dev --region "$GCP_REGION" \
  --format='yaml(status.resourceRecords)'
```

At Porkbun, **replace** the CloudFront ALIAS/CNAME records from
[`aws/RUNBOOK.md` B1.6](../aws/RUNBOOK.md#b16-point-a-real-domain-at-cloudfront-done--tesseraappdev-2026-08-08)
with the records step 3 prints: `A`/`AAAA` records for the apex, a `CNAME` to
`ghs.googlehosted.com.` for `www` (the "Host" field excludes the base domain, as before). The
Google-managed certificate issues automatically once DNS resolves — typically 15–60 minutes; the
mapping reports `CertificateProvisioned` when ready. `TRUSTED_PROXY_COUNT` stays `1`: a domain
mapping is still Cloud Run's own front end, not an extra hop.

Rolling back is the same DNS edit in reverse — CloudFront, its certificate and the ALB origin are
untouched by the pause.
