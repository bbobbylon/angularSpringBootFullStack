# TesseraApp — AWS Deployment Guide

End-to-end checklist for deploying TesseraApp to AWS using ECS Fargate + **Aiven MySQL** (managed DB) + S3 (image storage) + ALB (currently **plain HTTP** — see below) + Secrets Manager (secrets injection).

---

## Current deployment — the two environments

Everything in this repo is written to run identically in both. Where behaviour differs, it is
configuration, not code — see
[`GUIDE.md` §7.8](../documentation/GUIDE.md#7-security-model)
for the full parity table.

| | Local (`./start.sh`) | AWS (ECS Fargate) |
|---|---|---|
| **App URL** | `http://localhost:4200` (Angular dev server) | **`https://tesseraapp.dev`** (also reachable at `https://d3911jyxcju4q4.cloudfront.net` — same distribution, same backend; see below for the one thing that differs between them) |
| **API URL** | `http://localhost:8080` (separate origin) | same origin as the app — Angular is compiled into the jar |
| **`UI_APP_URL`** | `http://localhost:4200` | `https://tesseraapp.dev` |
| **`APP_DOMAIN`** (setup.sh / GitHub Secret) | n/a | `https://tesseraapp.dev` |
| **`OAUTH2_REDIRECT_BASE_URL`** | *unset* — request-derived is correct | `https://tesseraapp.dev` |
| **Database** | `db2` (local MySQL) | `db3` (Aiven) |
| **Images** | local filesystem | S3 (`tessera-app-images`) |
| **Spring profile** | `dev` | `prod` |
| **OAuth2 callback** | `http://localhost:8080/login/oauth2/code/{provider}` | `https://tesseraapp.dev/login/oauth2/code/{provider}` |

Set `UI_APP_URL` with **no trailing slash**. `UserRepoImpl#getVerificationURL` trims one defensively,
but nothing else does.

### HTTPS: CloudFront in front of the ALB, with a real domain on top

The **ALB itself still serves plain HTTP** — nothing changed there, and nothing needs to. As of
**August 4, 2026** a CloudFront distribution (`E1WWY6FHSKI84P`, `Deployed`) sits in front of it and
terminates TLS; as of **August 8, 2026** that distribution also has a real custom domain attached —
**`tesseraapp.dev`**, registered on Porkbun for $8.75/yr — with its own ACM certificate (`us-east-1`,
required by CloudFront regardless of where anything else lives), rather than relying solely on
AWS's auto-issued `*.cloudfront.net` name.

**Both URLs work and hit the identical backend** — CloudFront serves the SAME ECS service either
way, there is no separate deployment target. The one asymmetry is federated login, because of
GitHub's one-callback-per-OAuth-App limit (see [Troubleshooting](#a-provider-button-is-missing-or-the-authorize-url-carries-client_idchange_me)
below): the *production* GitHub OAuth App's callback was swapped to `tesseraapp.dev` once the
domain went live, so **GitHub login only works on `tesseraapp.dev` now, not on the bare CloudFront
URL**. Google and Microsoft both accept a list of redirect URIs, so they still work on either.

Getting a domain wired up isn't scripted end-to-end by one file — [`setup-cloudfront.sh`](setup-cloudfront.sh)
stands up CloudFront itself with *no* domain (the `*.cloudfront.net` name only), and
[`RUNBOOK.md` §B1.6](RUNBOOK.md#b16-point-a-real-domain-at-cloudfront-optional--once-you-own-one)
is the step-by-step for adding a real domain on top once you own one — ACM cert, DNS validation,
CloudFront alternate domain name, DNS pointing, app re-config. **`deploy-https.sh` is a different,
unrelated procedure** — it puts TLS directly on the **ALB**, bypassing CloudFront entirely. Don't
run it as part of this flow; it would stand up a second, parallel HTTPS surface for no benefit,
since CloudFront is this project's actual front door.

| Capability | State now |
|---|---|
| **Google federated login** | ✅ **Live** (2026-08-08) — real credentials from a Web application OAuth client, registered for both the CloudFront URL and `tesseraapp.dev` |
| **Microsoft (Entra) federated login** | ✅ **Live** (2026-08-08) — credentials were already real; the blocker was the redirect URI missing from the Entra app's Web platform, now added for both URLs |
| **GitHub federated login** | ✅ **Live on `tesseraapp.dev`** (2026-08-08) — three separate GitHub OAuth Apps exist in total: one for `localhost` (local dev), one originally registered for the bare CloudFront URL (now orphaned — its credentials are no longer in Secrets Manager), and the current production one registered for `tesseraapp.dev`, whose credentials are what's live |
| **WebAuthn / passkeys** | ✅ **Live and confirmed working** — registration, usernameless login, and admin revoke; any HTTPS origin supplies the secure context they need |
| **HSTS** | Meaningful on both the CloudFront URL and `tesseraapp.dev` |

⚠️ **CloudFront by itself was not enough — the ALB overwrites `X-Forwarded-Proto`.** CloudFront sets
it to `https`, the ALB replaces it with its own listener protocol (`http`), and Spring builds the
OAuth `redirect_uri` from that header — so the app emitted `redirect_uri=http://…` even through the
HTTPS front door. `FORWARD_HEADERS_STRATEGY=framework` does not help; it honours the header
faithfully and the header is wrong. `OAUTH2_REDIRECT_BASE_URL` pins the redirect origin instead of
deriving it. Verify after any front-door change:

```bash
curl -si "https://d3911jyxcju4q4.cloudfront.net/oauth2/authorization/github" | grep -i location
# redirect_uri= MUST start with https://
```

Whenever the public origin changes, `UI_APP_URL` / `APP_DOMAIN` / `OAUTH2_REDIRECT_BASE_URL` must all
be updated and **the task definition re-registered** — they are read once at container start — and
`TRUSTED_PROXY_COUNT` must match the number of proxies in front of the container (`2` with CloudFront
over the ALB).

> The ALB stays reachable on plain `http://`; CloudFront does not close it. To force all traffic
> through CloudFront, restrict the ALB security group to the managed prefix list
> `com.amazonaws.global.cloudfront.origin-facing`.

---

> 📕 **Looking for "just tell me the steps"?** See **[RUNBOOK.md](RUNBOOK.md)** — the linear,
> assumes-nothing procedure, including the application-level setup (apply `schema.sql` to `db3`,
> grant yourself an admin role) that this file does not cover and that infrastructure success does
> not give you. **This** file is the reference: what each resource is for, and a troubleshooting log
> of every real error hit building it. Use the runbook to *do*; use this one when something *breaks*.

## Fastest path — one-command bootstrap

`aws/setup.sh` runs all 9 infrastructure steps below in order and prints a final banner with your ALB DNS name:

**Run it from a Bash shell.** On Windows use **Git Bash** or WSL — in PowerShell, `chmod` does not
exist and `./aws/setup.sh` will not execute a shell script, which looks like "nothing happened".

**Install these first** (the script exits at preflight without them):

| Tool | Check | Windows install |
|---|---|---|
| AWS CLI v2 | `aws --version` | `winget install Amazon.AWSCLI` |
| `jq` | `jq --version` | `winget install jqlang.jq` |
| Credentials | `aws sts get-caller-identity` | `aws configure` |

Then, from Git Bash at the repo root:

```bash
chmod +x aws/setup.sh aws/secrets-setup.sh aws/push-to-ecr.sh

# --domain below is illustrative ONLY — do not copy it verbatim. It's a placeholder under
# example.com, a domain reserved for documentation (RFC 2606) that nobody can actually validate
# ownership of. Pass your ALB's own DNS name (find it from this script's own output after Step 7)
# if you don't own a real domain yet, or your real domain if you do. See Troubleshooting.
AWS_REGION=us-east-1 ./aws/setup.sh \
  --domain      app.tessera.example.com \
  --aiven-host  mysql-xyz.aivencloud.com \
  --aiven-port  28674 \
  --aiven-db    db3 \
  --aiven-user  avnadmin
```

> **`--aiven-db` must be `db3`, not `defaultdb`.** `defaultdb` is the empty database Aiven
> auto-creates with every service; this application's data — users, roles, customers, invoices —
> lives in **`db3`** (see [GUIDE.md §9.7](../documentation/GUIDE.md#9-database)
> for how it was migrated there). Pointing a task at `defaultdb` produces the most confusing
> possible failure: the SPA loads perfectly, then every sign-in attempt fails, because the schema
> may well be there but there is not a single user row in it. Unlike `mysql-xyz.aivencloud.com`
> above, `defaultdb` is a real Aiven database name, so it does not look like a placeholder —
> which is exactly how it gets copied verbatim.

`--domain` is the **hostname only** — the script builds `http://${DOMAIN}` itself (not `https://`:
see [Troubleshooting](#troubleshooting-real-errors-hit-running-setupsh) for why). Omit
`--aiven-password` and the script prompts for it, so it stays out of your shell history.

Every flag also has an environment-variable equivalent (`APP_DOMAIN`, `AIVEN_HOST`, `AIVEN_PORT`,
`AIVEN_DB`, `AIVEN_USER`, `AIVEN_PASSWORD`, `S3_BUCKET`, `ECR_REPO`, `ECS_CLUSTER`, `ECS_SERVICE`),
which is the style `secrets-setup.sh` and `push-to-ecr.sh` use. Flags win where both are given.

The manual steps below explain what `setup.sh` does, which is useful for debugging or incremental changes.

---

## AWS services used

| Service | Purpose | Free-tier? |
|---|---|---|
| **ECR** | Container registry | 500 MB/month free |
| **ECS Fargate** | Run the containerised app | Compute billed per task |
| **Aiven MySQL** | Managed MySQL (replaces RDS) | Paid; ~$19/month starter |
| **S3** | Profile image object storage | 5 GB free |
| **ALB** | HTTPS load balancer → ECS | ~$16/month |
| **Secrets Manager** | Inject all secrets at task startup | $0.40/secret/month |
| **CloudWatch Logs** | Container log output | 5 GB free |
| **IAM** | Roles for the ECS task and execution | Free |

---

## Step 1 — Create IAM roles

### Execution role (pulls image + reads secrets)
```bash
aws iam create-role --role-name ecsTaskExecutionRole \
  --assume-role-policy-document '{"Version":"2012-10-17","Statement":[{"Effect":"Allow","Principal":{"Service":"ecs-tasks.amazonaws.com"},"Action":"sts:AssumeRole"}]}'

aws iam attach-role-policy --role-name ecsTaskExecutionRole \
  --policy-arn arn:aws:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy

aws iam attach-role-policy --role-name ecsTaskExecutionRole \
  --policy-arn arn:aws:iam::aws:policy/SecretsManagerReadWrite
```

### Task role (what the running container can do — S3 access)
```bash
aws iam create-role --role-name tessera-app-task-role \
  --assume-role-policy-document '{"Version":"2012-10-17","Statement":[{"Effect":"Allow","Principal":{"Service":"ecs-tasks.amazonaws.com"},"Action":"sts:AssumeRole"}]}'

aws iam put-role-policy --role-name tessera-app-task-role \
  --policy-name S3ImageStorage \
  --policy-document '{
    "Version":"2012-10-17",
    "Statement":[{
      "Effect":"Allow",
      "Action":["s3:PutObject","s3:GetObject","s3:DeleteObject"],
      "Resource":"arn:aws:s3:::tessera-app-images/*"
    }]
  }'
```

---

## Step 2 — Create the S3 bucket for profile images

```bash
aws s3 mb s3://tessera-app-images --region us-east-1

# Buckets created via `aws s3 mb` have S3 Block Public Access ON by default (since April 2023),
# which rejects the public-read policy below with AccessDenied unless cleared first — scoped to
# just this bucket, not an account-wide change (see Troubleshooting):
aws s3api put-public-access-block --bucket tessera-app-images --public-access-block-configuration \
  BlockPublicAcls=false,IgnorePublicAcls=false,BlockPublicPolicy=false,RestrictPublicBuckets=false

aws s3api put-bucket-policy --bucket tessera-app-images --policy '{
  "Version":"2012-10-17",
  "Statement":[{
    "Effect":"Allow",
    "Principal":"*",
    "Action":"s3:GetObject",
    "Resource":"arn:aws:s3:::tessera-app-images/*"
  }]
}'

# Every origin the images are actually loaded from. The CloudFront origin is the live one; the ALB
# entry is kept because the ALB is still directly reachable, and localhost is the dev server.
# A scheme change makes it a DIFFERENT origin to the browser — which is why adding CloudFront in
# front of the ALB requires touching this list, not just the task definition.
#
# NOTE the live bucket policy is currently WRONG on both counts (checked 2026-08-04): it allows
#   https://tessera-app-alb-…   <- https, but the ALB only ever listened on :80/HTTP, so this
#                                  entry has never matched a real request
# and it does not list the CloudFront origin at all. It is not breaking anything *yet* — the app
# renders avatars with plain <img [ngSrc]> and no crossorigin attribute, and simple image loads are
# not subject to CORS — but it will bite the moment anything fetches an object with fetch/XHR
# (canvas cropping, a download button, presigned-URL uploads). Re-run this command to fix it.
aws s3api put-bucket-cors --bucket tessera-app-images --cors-configuration '{
  "CORSRules":[{
    "AllowedOrigins":["https://d3911jyxcju4q4.cloudfront.net", "http://tessera-app-alb-1750339159.us-east-1.elb.amazonaws.com", "http://localhost:4200"],
    "AllowedMethods":["GET"],
    "AllowedHeaders":["*"],
    "MaxAgeSeconds":3600
  }]
}'
```

---

## Step 3 — Create ECR repository and push the image

`push-to-ecr.sh` does the actual Docker work: builds the multi-stage image from the repo's
`Dockerfile` (Angular compiled into the Spring Boot jar), tags it, and pushes it to ECR.
**Docker Desktop must be running first** — if it isn't, this fails with `failed to connect to the
docker API at npipe://...` (see Troubleshooting). Run it again any time you need to ship a new
image (a new git commit, a config change baked into the image, etc.) — it always builds fresh
from your current working tree, it doesn't reuse a stale image.

```bash
aws ecr create-repository --repository-name tessera-app --region us-east-1

chmod +x aws/push-to-ecr.sh
AWS_REGION=us-east-1 ./aws/push-to-ecr.sh latest
```

---

## Step 4 — Create Secrets Manager secrets

```bash
chmod +x aws/secrets-setup.sh
AWS_REGION=us-east-1 ./aws/secrets-setup.sh

# Then fill in the real Aiven values (from Aiven console → Service → Connection info):
aws secretsmanager update-secret --region us-east-1 \
  --secret-id tessera-app/aiven-host     --secret-string 'mysql-xyz.aivencloud.com'
aws secretsmanager update-secret --region us-east-1 \
  --secret-id tessera-app/aiven-port     --secret-string '28674'
aws secretsmanager update-secret --region us-east-1 \
  --secret-id tessera-app/aiven-db       --secret-string 'db3'
aws secretsmanager update-secret --region us-east-1 \
  --secret-id tessera-app/aiven-user     --secret-string 'avnadmin'
aws secretsmanager update-secret --region us-east-1 \
  --secret-id tessera-app/db-password    --secret-string 'your-real-aiven-password'
# ... repeat for mail-*, twilio-*, google-*, github-*, microsoft-*
```

> **A provider whose secrets are missing here simply will not appear on the login screen.**
> `OAuth2ClientConfig#federatedProviderCatalog` skips any provider with a blank client id, and the
> SPA renders exactly what `GET /oauth2/providers` returns. That is by design (a deployment with only
> GitHub credentials gets a working GitHub button and no dead ones) but it means a provider that works
> locally can be silently absent when deployed — which is precisely what happened with Microsoft, whose
> secrets existed in `.env` but not in Secrets Manager or `task-definition.json`. Counting the buttons
> on the deployed login screen is the fastest way to see which client ids actually reached the container.

---

## Step 5 — Initialise the Aiven database schema

TesseraApp uses Aiven MySQL as the production database. Connect to it and run the schema once:

```bash
# Install Aiven CA cert first (download from Aiven console → Service → Overview → CA Certificate):
mysql \
  --host=mysql-xyz.aivencloud.com \
  --port=28674 \
  --user=avnadmin \
  --password \
  --ssl-ca=aiven-ca.pem \
  --ssl-mode=REQUIRED \
  db3 < src/main/resources/schema.sql
```

The schema is idempotent (`CREATE TABLE IF NOT EXISTS`, no DROPs) so re-running it is safe.

Note the database name is **`db3`** here too. Running this against `defaultdb` is not destructive —
it just creates a second, empty copy of the schema in the wrong place, which then looks like a
working database to anything that connects to it while containing no accounts to sign in with.

---

## Step 6 — Fill in task-definition.json and register it

`aws/task-definition.json` is a template with `${VARIABLE}` tokens. Fill and register it:

```bash
export AWS_ACCOUNT_ID=$(aws sts get-caller-identity --query Account --output text)
export AWS_REGION=us-east-1
export ECR_IMAGE_URI="${AWS_ACCOUNT_ID}.dkr.ecr.${AWS_REGION}.amazonaws.com/tessera-app:latest"
export AIVEN_HOST=mysql-xyz.aivencloud.com
export AIVEN_PORT=28674
export AIVEN_DB=db3
export AIVEN_USER=avnadmin
export S3_BUCKET=tessera-app-images
# http:// unless you've already completed Step 8 (HTTPS) for real — see Troubleshooting.
export APP_DOMAIN=http://your-alb-dns-name-or-real-domain

# Inline the filled JSON with $(cat ...) rather than a file:// reference — on Windows/Git Bash,
# handing a bare filesystem path to aws.exe (a native, non-MSYS binary) is unreliable even when
# the file genuinely exists (see Troubleshooting). Also strip the _comment/_variables
# documentation keys, which register-task-definition's strict schema validation rejects.
FILLED_JSON="$(envsubst < aws/task-definition.json | jq 'del(._comment, ._variables)')"
aws ecs register-task-definition \
  --cli-input-json "$FILLED_JSON" \
  --region us-east-1
```

---

## Step 7 — Create the ECS cluster and service

```bash
aws ecs create-cluster --cluster-name tessera-app-cluster --region us-east-1

# Edit aws/ecs-service.json to fill in subnets, security group, ALB target group ARN:
aws ecs create-service \
  --cli-input-json file://aws/ecs-service.json \
  --region us-east-1
```

---

## Step 8 — Set up ALB + HTTPS (optional — requires a real domain)

**Skip this entirely if you don't own a domain.** The app works fine over plain HTTP at the ALB's
own DNS name once Steps 1-7 are done — this step only adds a custom domain + certificate on top.
ACM cannot issue a certificate without proving you control the domain's DNS, so a placeholder like
`example.com` (reserved, RFC 2606) or a free dynamic-DNS subdomain will not work — see
[Troubleshooting](#troubleshooting-real-errors-hit-running-setupsh).

1. Create an Application Load Balancer (port 80 → 443 redirect, port 443 → target group → ECS container:8080).
2. Request a certificate via **AWS Certificate Manager (ACM)** for your real domain, DNS validation.
3. Attach the certificate to the ALB HTTPS listener.
4. Point your domain's A record (Route 53 or your registrar) to the ALB DNS name.
5. Update `APP_DOMAIN` (Step 6, and the `APP_DOMAIN` GitHub Secret) to `https://your-real-domain`
   and re-register the task definition — it's read once at container start, not live.

---

## Redeploy after a code change

The GitHub Actions `deploy.yml` workflow handles this automatically on every push to `master`. A push
to any other branch only runs `ci.yml` (build + test) — it shows green in the Actions tab but never
touches AWS, which is a common source of "I pushed but nothing changed" confusion.

**To deploy a branch other than `master`**, either:
- Run the manual commands below locally — they build and push whatever is currently checked out
  (`git rev-parse --short HEAD` on your current branch), so there's nothing master-specific about
  them; or
- GitHub → Actions → `deploy.yml` → "Run workflow" → pick your branch from the dropdown (it defaults
  to `master`, so this must be changed explicitly).

See [`RUNBOOK.md` Part D](RUNBOOK.md#part-d--redeploy-the-90-second-loop) for the day-to-day version
of this loop with the same branch-agnostic caveat spelled out in more detail.

To trigger manually:

```bash
# 1. Push new image:
AWS_REGION=us-east-1 ./aws/push-to-ecr.sh $(git rev-parse --short HEAD)

# 2. Fill template and register (inline with $(cat...), not file:// — see Troubleshooting):
FILLED_JSON="$(envsubst < aws/task-definition.json | jq 'del(._comment, ._variables)')"
aws ecs register-task-definition --cli-input-json "$FILLED_JSON" --region us-east-1

# 3. Force a rolling deployment:
aws ecs update-service \
  --cluster tessera-app-cluster \
  --service tessera-app-service \
  --force-new-deployment \
  --region us-east-1
```

---

## Rotating secrets

```bash
# Generate a new JWT secret and update Secrets Manager:
aws secretsmanager update-secret \
  --secret-id tessera-app/jwt-secret \
  --secret-string "$(openssl rand -base64 48)" \
  --region us-east-1

# Force ECS to restart tasks and pick up the new secret:
aws ecs update-service \
  --cluster tessera-app-cluster \
  --service tessera-app-service \
  --force-new-deployment \
  --region us-east-1
```

---

## Checking and updating secret values from the CLI

Everything below runs against **your own AWS CLI credentials** — there is no separate access
path. If you've run `aws configure` (or are on a machine with an IAM role attached) with a
principal that holds `secretsmanager:*` on the `tessera-app/*` secrets, these are exactly the
commands that read and write them; a session driving the CLI on your behalf is using that same
local credential, nothing more privileged.

Note that whichever secret you touch, **a change here does not affect the currently running ECS
task** — it only takes effect on the next task boot (see "Redeploy after a code change" /
"Rotating secrets" above, or the one-liner at the end of this section, to force that).

### AWS Console

1. Sign in to the [Secrets Manager console](https://console.aws.amazon.com/secretsmanager/) in
   the correct region (`us-east-1` for this project).
2. Click the secret by name, e.g. `tessera-app/google-client-id`.
3. Under **Secret value**, click **Retrieve secret value** to view it, or **Edit** to change it.

### AWS CLI — checking

```bash
# List every secret's name and when it was last changed (no value exposed):
aws secretsmanager list-secrets --region us-east-1 \
  --query 'SecretList[?starts_with(Name, `tessera-app/`)].{Name:Name,LastChanged:LastChangedDate}' \
  --output table

# Reveal one specific secret's current value:
aws secretsmanager get-secret-value --region us-east-1 \
  --secret-id tessera-app/google-client-id --query SecretString --output text
```

Swap `google-client-id` for any of: `jwt-secret`, `db-password`, `mail-username`, `mail-password`,
`twilio-sid`, `twilio-token`, `twilio-from-number`, `google-client-id`, `google-client-secret`,
`github-client-id`, `github-client-secret`, `microsoft-client-id`, `microsoft-client-secret`,
`aiven-host`, `aiven-port`, `aiven-db`, `aiven-user`.

> **Never paste the output of `get-secret-value` into a chat, ticket, commit, or log** — treat it
> exactly like the access keys warning further down this file. If a real value does end up
> somewhere it shouldn't, rotate it immediately (see "Rotating secrets" above). If you're driving
> this through an AI coding assistant with tool-permission controls, expect (and want) a raw
> `get-secret-value` call to be the one thing it's blocked from running unattended — that's the
> control working correctly, not a bug to route around. Checking a secret's *shape* (length,
> prefix, or an exact-match comparison against a known placeholder like `CHANGE_ME`) without ever
> displaying the real value is a reasonable middle ground if you need to script a check.

### AWS CLI — updating

```bash
aws secretsmanager update-secret --region us-east-1 \
  --secret-id tessera-app/twilio-sid --secret-string '<the real value>' \
  --query '{Name:Name,VersionId:VersionId}' --output table
```

`update-secret` only ever needs `--secret-id` and `--secret-string`; the `--query`/`--output`
above just trims the response to a version ID so you get a confirmation without the CLI echoing
the value back. **The secret must already exist** — every `tessera-app/*` secret was created once
by `setup.sh`, so day-to-day work is always `update-secret`, never `create-secret` (that's only
for a genuinely new secret name, shown further down for the QA/stage secret sets).

The change is inert until a task actually boots with it — finish with a force-new-deployment
(same command as "Redeploy after a code change" above) so the running container picks it up:

```bash
aws ecs update-service --region us-east-1 \
  --cluster tessera-app-cluster --service tessera-app-service --force-new-deployment
```

---

## GitHub Actions secrets to configure

### Create a dedicated deploy IAM user first — never use root/admin keys here

A leaked root access key is a full account compromise; a leaked deploy-user key is limited to
exactly what this pipeline needs. Create one with a scoped policy (fill in your account ID):

```bash
aws iam create-user --user-name tessera-app-deploy

cat <<'EOF' > /tmp/tessera-deploy-policy.json
{
  "Version": "2012-10-17",
  "Statement": [
    { "Effect": "Allow", "Action": ["ecr:GetAuthorizationToken"], "Resource": "*" },
    { "Effect": "Allow",
      "Action": ["ecr:BatchCheckLayerAvailability","ecr:GetDownloadUrlForLayer","ecr:BatchGetImage",
                 "ecr:PutImage","ecr:InitiateLayerUpload","ecr:UploadLayerPart","ecr:CompleteLayerUpload"],
      "Resource": "arn:aws:ecr:us-east-1:YOUR_ACCOUNT_ID:repository/tessera-app" },
    { "Effect": "Allow", "Action": ["ecs:RegisterTaskDefinition"], "Resource": "*" },
    { "Effect": "Allow", "Action": ["ecs:UpdateService","ecs:DescribeServices"],
      "Resource": "arn:aws:ecs:us-east-1:YOUR_ACCOUNT_ID:service/tessera-app-cluster/tessera-app-service" },
    { "Effect": "Allow", "Action": "iam:PassRole",
      "Resource": ["arn:aws:iam::YOUR_ACCOUNT_ID:role/ecsTaskExecutionRole",
                   "arn:aws:iam::YOUR_ACCOUNT_ID:role/tessera-app-task-role"] },
    { "Effect": "Allow", "Action": ["secretsmanager:DescribeSecret"],
      "Resource": "arn:aws:secretsmanager:us-east-1:YOUR_ACCOUNT_ID:secret:tessera-app/*" }
  ]
}
EOF

aws iam put-user-policy --user-name tessera-app-deploy --policy-name TesseraDeployPolicy \
  --policy-document "$(cat /tmp/tessera-deploy-policy.json)"

aws iam create-access-key --user-name tessera-app-deploy
```

The last command prints an `AccessKeyId` and `SecretAccessKey` **once** — copy them immediately
into the GitHub Secrets below and never paste them anywhere else (a chat log, a commit, a ticket).
If one ever does leak, rotate it immediately:
`aws iam delete-access-key --user-name tessera-app-deploy --access-key-id <leaked-id>` then
re-run `create-access-key` above.

### Add the secrets

Go to **GitHub → Repository → Settings → Secrets and variables → Actions** and add each one
individually — a GitHub repository secret is just a name + value, there's no "type" to pick. The
`gh secret set NAME` CLI command is faster than the web form if you have `gh` installed (it
prompts for the value rather than taking it as a visible argument):

| Secret name | Where to find it |
|---|---|
| `AWS_ACCESS_KEY_ID` | IAM → Users → `tessera-app-deploy` → Security credentials (created above) |
| `AWS_SECRET_ACCESS_KEY` | Same as above |
| `AWS_REGION` | e.g. `us-east-1` |
| `AWS_ACCOUNT_ID` | `aws sts get-caller-identity --query Account` |
| `ECR_REPOSITORY` | e.g. `tessera-app` |
| `ECS_CLUSTER` | e.g. `tessera-app-cluster` |
| `ECS_SERVICE` | e.g. `tessera-app-service` |
| `AIVEN_HOST` | Aiven console → Service → Overview → Host |
| `AIVEN_PORT` | Aiven console → Service → Overview → Port |
| `AIVEN_DB` | **`db3`** — not `defaultdb`, which is Aiven's empty auto-created database |
| `AIVEN_USER` | e.g. `avnadmin` |
| `S3_BUCKET` | e.g. `tessera-app-images` |
| `APP_DOMAIN` | Currently `https://d3911jyxcju4q4.cloudfront.net` — the CloudFront distribution, **not** the ALB. It fills both `UI_APP_URL` and `OAUTH2_REDIRECT_BASE_URL` in the task-definition template, so one export covers both. Change it only when the public front door changes, and re-register the task definition when you do |

---

## Troubleshooting: real errors hit running `setup.sh`

Every one of these was hit running this exact script end-to-end on Windows/Git Bash. Most of the
script-level bugs are already fixed in `aws/setup.sh` and `aws/task-definition.json` — they're
documented here so you recognize the symptom instantly if it resurfaces (e.g. a teammate on an
older checkout, or the same mistake in a hand-run manual command) instead of re-debugging from
scratch.

### Tasks fail to place: "failed to validate logger args ... awslogs-region is required"
**Symptom:** the service loops `has started 1 tasks` → `was unable to place a task` forever, with
`ResourceInitializationError: failed to validate logger args: unable to get awslogs driver
arguments: awslogs-region is required`. `runningCount` on the new revision never leaves 0. The
previous revision keeps serving, so **the app stays up** — this fails the rollout, not the site.
**Cause:** `AWS_REGION` was not exported before `envsubst`. `aws/task-definition.json` uses
`"awslogs-region": "${AWS_REGION}"`, and **`envsubst` replaces an unset variable with an empty
string and exits 0** — it does not warn and it does not fail. The result is structurally valid
JSON, so `register-task-definition` accepts it happily; the emptiness only surfaces when ECS tries
to start a container with a log driver that has no region.
**Why the obvious guard misses it:** checking the filled JSON for a leftover `${` finds nothing —
the token *was* substituted, just with nothing. You have to check for empty **values**, not
unresolved tokens. The `BAD=$(… | jq …)` guard in [RUNBOOK.md Part D](RUNBOOK.md#part-d--redeploy-the-90-second-loop)
does exactly that, across `environment`, `secrets` and `logConfiguration.options`.
**Fix:** `export AWS_REGION=us-east-1` before `envsubst`, re-register, and point the service back
at a known-good revision meanwhile:
```bash
aws ecs update-service --cluster tessera-app-cluster --service tessera-app-service \
  --task-definition tessera-app:<last-good-revision> --force-new-deployment --region us-east-1
```
Find the last good one by comparing log config across revisions:
```bash
aws ecs describe-task-definition --task-definition tessera-app:8 --region us-east-1 \
  --query 'taskDefinition.containerDefinitions[0].logConfiguration.options'
```

### An `aws` command "won't finish" — you have to hold Enter to get back to a prompt
**Cause:** not a hang. AWS CLI v2 pipes output through a pager by default (`more` on Windows,
`less` elsewhere), and `more` advances one line per Enter. Commands that return a large object —
`ecs update-service` dumps the entire service, every deployment and event included — take a very
long time to page through one line at a time.
**Escape right now:** press `q` (or `Ctrl+C`).
**Fix permanently:** `aws configure set cli_pager ""`. Per-shell `export AWS_PAGER=""` and
per-command `--no-cli-pager` also work.
**Why it matters beyond irritation:** in a script or a CI job the pager has no terminal to page to,
so the command blocks on input that never arrives and the job times out with no error. That is why
every AWS invocation in these scripts ends in `--query … --output text` or `>/dev/null`.

### `chmod: cannot access 'aws/setup.sh': No such file or directory`
**Cause:** not running from the repo root — `aws/setup.sh` is a relative path.
**Fix:** `cd` to the repo root first, then re-run.

### S3 `AccessDenied` on `PutBucketPolicy` — "public policies are prevented by BlockPublicAccess"
**Cause:** `aws s3 mb` creates buckets with S3 Block Public Access **ON by default** (an AWS
default since April 2023), which blocks a public-read bucket policy even though nothing in your
account explicitly enabled it.
**Fix:** clear it on just that bucket before applying the policy — not an account-wide change:
```bash
aws s3api put-public-access-block --bucket tessera-app-images --public-access-block-configuration \
  BlockPublicAcls=false,IgnorePublicAcls=false,BlockPublicPolicy=false,RestrictPublicBuckets=false
```
Already baked into `aws/setup.sh` (Step 2). Confirmed via `aws s3control get-public-access-block
--account-id <id>` that this was purely bucket-level — an account with no configuration at all
returns `NoSuchPublicAccessBlockConfiguration`, which means "nothing to worry about," not an error.

### Had to create an AWS Organization + enable Storage Lens trusted access just to run this
Some AWS accounts require being part of an Organization with trusted access enabled for certain
S3 account-level features before related S3 operations succeed, even for a solo account with one
member. This is an AWS platform quirk, not something this script or your account configuration
did wrong — if you hit it, create the Organization (Organizations console → Create organization,
you'll automatically become the management account) and enable the trusted access it asks for,
then continue.

### `CreateSecurityGroup`: "Character sets beyond ASCII are not supported"
**Cause:** the ALB/app security group `--description` values originally used an em dash (`—`)
for style. EC2's API rejects any non-ASCII character in that field.
**Fix:** already changed to plain hyphens in `aws/setup.sh`. If you add your own `--description`
text anywhere in these scripts, keep it plain ASCII.

### ALB target group health check path silently becomes a Windows path
**Symptom:** `CreateTargetGroup` fails with something like `Health check path
'C:/Program Files/Git/actuator/health' must begin with a '/'`.
**Cause:** Git Bash (MSYS) rewrites any bare CLI argument that looks like a POSIX path (starts
with `/`) into a Windows path before handing it to a native `.exe` like `aws.exe` — it mistook the
URL path `/actuator/health` for a filesystem path.
**Fix:** `export MSYS2_ARG_CONV_EXCL="/actuator/health"` near the top of `aws/setup.sh` (already
there) excludes just that one literal value from path conversion. **Don't** reach for the blanket
`MSYS_NO_PATHCONV=1` instead — it disables path conversion for *everything*, which breaks the very
next issue below.

### `register-task-definition`: "Unable to load paramfile file:///tmp/....json: No such file or directory"
**Cause:** two distinct traps, both about handing a real filesystem path to `aws.exe` as a bare
string on Git Bash:
1. A blanket `MSYS_NO_PATHCONV=1` (see above) stops MSYS from translating `/tmp/...` into a real
   Windows path, so `aws.exe` never gets something it can resolve.
2. Even with correct path translation, a `file://` URI handed to a native, non-MSYS binary is
   unreliable on Git Bash in general — it can fail even when the file genuinely exists exactly
   where it says.
**Fix:** don't pass a path at all — inline the file's content directly as the parameter value:
```bash
aws ecs register-task-definition --cli-input-json "$(cat "$TASK_DEF_FILLED")" --region "$REGION"
```
This is what `aws/setup.sh` and `deploy.yml` both do now, and it's also portable to Linux/macOS
(no MSYS-specific behavior to depend on). The same fix applies to the deploy IAM policy
(`put-user-policy --policy-document "$(cat file.json)"`, not `file://file.json`) — hit the
identical error there for the identical reason.

### `register-task-definition`: "Unknown parameter in input: '_comment' / '_variables'"
**Cause:** `aws/task-definition.json` is a template that carries `_comment` and `_variables` as
human-readable documentation for the `${VAR}` tokens. `register-task-definition` validates
strictly against ECS's real schema and rejects any key it doesn't recognize.
**Fix:** strip them after `envsubst`, before registering:
```bash
envsubst < aws/task-definition.json | jq 'del(._comment, ._variables)' > filled.json
```
Fixed in both `aws/setup.sh` and `.github/workflows/deploy.yml` — this would have failed on the
first real CI deploy too, not just the local script.

### ECS tasks fail forever: two DIFFERENT secret-resolution errors, in sequence
**Symptom:** the service cycles through `has started 1 tasks` → `was unable to place a task` on
loop, every ~30 seconds, indefinitely — `runningCount` never leaves 0. Every task fails at the
pull-secrets-before-start step, before your application code ever runs at all.

This one took two attempts to actually fix, because each wrong `valueFrom` format fails with a
*different*, equally plausible-looking error — worth knowing both so you recognize either instantly:

**Attempt 1 — hand-built "partial" ARN:**
```
ResourceNotFoundException: Secrets Manager can't find the specified secret
arn:aws:secretsmanager:us-east-1:ACCOUNT:secret:tessera-app/jwt-secret
```
`aws/task-definition.json` originally built each secret's ARN by hand. Real Secrets Manager ARNs
always carry an extra random 6-character suffix (`tessera-app/jwt-secret-AbC123`) appended at
creation time — the hand-built ARN is missing it, so it matches nothing.

**Attempt 2 — bare secret name, no ARN at all:**
```
AccessDeniedException: ... not authorized to perform: ssm:GetParameters ...
because no identity-based policy allows the ssm:GetParameters action
```
Switching to just the friendly name (`"tessera-app/jwt-secret"`, no ARN prefix) seems like the
obvious fix — and AWS's own docs read as if it should work — but a `valueFrom` with **no** ARN
prefix at all is ambiguous to ECS, which defaults to treating it as an **SSM Parameter Store**
parameter name instead of a Secrets Manager one. The execution role only has Secrets Manager
permissions, so it fails on an `ssm:GetParameters` call for a parameter that isn't even SSM.

**Actual fix — resolve and use the COMPLETE ARN, suffix included, every time:**
```bash
aws secretsmanager describe-secret --secret-id tessera-app/jwt-secret --query ARN --output text
```
`aws/task-definition.json` now takes `${JWT_SECRET_ARN}`-style tokens (one per secret) instead of
building or guessing the ARN itself, and `aws/setup.sh` / `deploy.yml` resolve each one dynamically
via `describe-secret` right before filling the template. This also means the CI deploy user needs
`secretsmanager:DescribeSecret` on `tessera-app/*` — already folded into the policy above.

**If you already have a service stuck in this failure loop from either wrong attempt, fixing the
template is not enough on its own** — you must register a new task definition revision from the
fixed template AND explicitly force the service onto it:
```bash
aws ecs update-service --cluster tessera-app-cluster --service tessera-app-service \
  --task-definition tessera-app --force-new-deployment --region us-east-1
```
Re-running `setup.sh` alone will NOT do this — its Step 9 only creates the service if it doesn't
exist yet; if it already exists, it just prints "already exists" and does nothing further, even
after a corrected revision is registered.

### The task definition bakes in `https://` before HTTPS actually exists
**Cause:** `setup.sh` used to hardcode `APP_DOMAIN="https://${DOMAIN}"` regardless of whether Step
8 (ACM cert + HTTPS listener) had been done. Since Step 8 is documented as a manual step that
happens *after* this script runs, the container's `UI_APP_URL` — which becomes the base URL for
password-reset/email-verification links — pointed at a scheme that didn't exist yet, breaking
those links until someone noticed and fixed it by hand.
**Fix:** `setup.sh` now defaults to `http://${DOMAIN}`. Update `APP_DOMAIN` (both the GitHub Secret
and, if you registered a task definition by hand, the env var) to `https://your-real-domain` only
once Step 8 is genuinely complete.

### Federated login: the provider console refuses to accept the ALB's `http://` callback
**Symptom:** adding `http://tessera-app-alb-….elb.amazonaws.com/login/oauth2/code/google` in the
Google Cloud console (or the equivalent under Entra's **Authentication** blade) is rejected outright
at save time, with a message about the URI needing to use HTTPS.
**Cause:** not a bug and not fixable in this repo. Google and Microsoft both require the `https`
scheme on redirect URIs, with a carve-out **only** for `http://localhost`. That carve-out is exactly
why federated login works on `start.sh` and cannot be made to work on a plain-HTTP ALB.
**Fix:** already done — **register the CloudFront callback instead**:
`https://d3911jyxcju4q4.cloudfront.net/login/oauth2/code/{google,github,microsoft}`. Keep the
localhost entries; Google and Entra both accept a list. (A GitHub *OAuth App* accepts only **one**
callback URL, so use a second OAuth App for the deployed environment.)

### Federated login still emits `redirect_uri=http://…` even through CloudFront
**Symptom:** `curl -si https://d3911jyxcju4q4.cloudfront.net/oauth2/authorization/github` returns a
`Location` whose `redirect_uri=` parameter starts with `http://`, and the provider rejects it — the
host is right, only the scheme is wrong.
**Cause:** the ALB **overwrites** `X-Forwarded-Proto`. CloudFront sets it to `https`; the ALB then
replaces it with its own listener protocol, which is `http` because the ALB has no TLS listener.
Spring reconstructs `{baseUrl}` — and the redirect URI — from that header, so
`FORWARD_HEADERS_STRATEGY=framework` faithfully propagates a wrong value. Setting it to `native`
does not help either; the header is the problem, not the strategy.
**Fix:** set **`OAUTH2_REDIRECT_BASE_URL`** to the public origin. `OAuth2ClientConfig` then pins the
scheme and host of the redirect-URI template for all three providers instead of deriving them.
It is a task-definition value, so it needs a **re-register plus a new deployment** — and the running
image must be one built after the property was added, or it is simply ignored.

### A provider button is missing, or the authorize URL carries `client_id=CHANGE_ME`
**Symptom:** the login screen shows fewer provider buttons than expected, or clicking one lands on a
provider error page; the `Location` header contains `client_id=CHANGE_ME`.
**Cause:** the credential in Secrets Manager is a placeholder. `OAuth2ClientConfig#federatedProviderCatalog`
skips a provider whose client id is **blank**, but `CHANGE_ME` is not blank — so the button renders
and the flow fails at the provider instead of being hidden. This bit the project twice
(2026-08-04, then again 2026-08-08 when re-pasted credentials turned out to be UUIDs, not real
provider secrets) before all three — Google, GitHub, Microsoft — were confirmed real and live on
`tesseraapp.dev`. If this symptom recurs, check the live `client_id` format in the authorize
redirect before assuming code is broken: a UUID or the literal `CHANGE_ME` means the Secrets
Manager value is wrong, not a bug.
**Fix:** `aws secretsmanager put-secret-value --secret-id tessera-app/github-client-id
--secret-string '<real id>'` (repeat per credential), then force a new deployment — ECS resolves
secrets at container start, so no new revision is needed but a restart is.

### Federated login redirects to `http://10.0.x.x:8080/...` instead of the ALB
**Symptom:** the provider returns `redirect_uri_mismatch`, and the URI in the error is the ECS task's
own private IP rather than any hostname you registered anywhere.
**Cause:** `server.forward-headers-strategy` left at its default `none`. `OAuth2ClientConfig`
registers every provider with the template `{baseUrl}/login/oauth2/code/{registrationId}`, and
`{baseUrl}` is resolved per request from what the servlet container thinks it is serving. Behind an
ALB with forwarded headers ignored, that is the container's own scheme, address and port.
**Fix:** `FORWARD_HEADERS_STRATEGY=framework` — already set in `aws/task-definition.json`. It is off
by default on purpose: trusting `X-Forwarded-*` when nothing strips them lets any caller claim any
origin. Requires a task-definition revision, since it is a plain environment value.

### Rate limiting throttles everyone at once / anomaly detection never fires
**Symptom:** one user tripping the rate limiter appears to 429 the whole app; the security dashboard
never records a `NEW_NETWORK` signal no matter which device signs in.
**Cause:** `app.security.trusted-proxy-count` (env `TRUSTED_PROXY_COUNT`) left at its default `0`,
which makes `RequestUtils.getIpAddress` ignore `X-Forwarded-For` and return the transport peer — the
**load balancer** — for every request. Every user therefore shares one rate-limit bucket and one
apparent network, so FR-TPF-1 step-up looks present and does nothing.
**Fix:** `TRUSTED_PROXY_COUNT=1` behind a single ALB (`2` with CloudFront in front of it) — already
set in `aws/task-definition.json`. Confirm from the boot log line `[NET] trusted-proxy-count=…`.
Do not pad the number: set it too high and an attacker-supplied header entry becomes trusted, which
is the exact vulnerability the mechanism exists to prevent.

### ACM: "Additional verification required to request certificates for one or more domain names"
**Cause:** requesting a certificate for a placeholder domain that nobody can actually prove
ownership of. `example.com` (and any subdomain of it) is reserved for documentation under RFC
2606 — it's never delegated to anyone, so DNS validation can never complete. The same wall shows
up for free dynamic-DNS services (you usually can't add the specific validation record ACM asks
for) and for free "abuse-magnet" TLDs like `.tk`/`.ml`/`.ga`/`.cf` (ACM's manual review flags these
even more often than a normal domain).
**Fix:** either skip HTTPS/custom domain entirely and use the ALB's own DNS name over plain HTTP
(free, works immediately), or buy a real cheap domain (often $1-12/year on Namecheap/Porkbun) and
point its DNS at the ALB — a domain you genuinely control validates in minutes. **AWS Private CA**
is not a substitute here: it issues certificates only clients you configure will trust, so a
public browser would show a hard security warning, and it costs a real recurring fee (~$50-400+/mo)
on top of not solving the actual problem.

### CloudFront's default domain can't be customized
If you go the free-HTTPS-without-a-domain route (CloudFront in front of the ALB, using its
auto-issued `*.cloudfront.net` certificate), the `d1234abcd5678.cloudfront.net`-style hostname is
randomly assigned per distribution and cannot be changed to include your app's name at any price
tier — that requires a real domain (see above) added as a CloudFront alternate domain name.

### `chmod +x` "isn't working" on a re-run
`chmod +x` sets a permission bit that persists on the file itself — it only needs to run once per
checkout, not before every single invocation of the script.

### A multi-line command with `\` continuations silently splits into separate commands
**Symptom:** the first line runs alone and fails with "required arguments missing," then each
following line errors with `bash: --some-flag: command not found`.
**Cause:** a trailing space after the `\` (common when copy-pasting from a rendered/markdown
source) turns it into an escaped space instead of a line continuation, ending the command early.
**Fix:** paste the command as one single line, or use a heredoc, instead of relying on `\`
continuations surviving a copy-paste round-trip.

### Never paste a real AWS access key or secret into a chat, ticket, or log
If one ever does end up somewhere it shouldn't (a transcript, a shared doc, a commit), treat it as
compromised immediately and rotate it — don't just remove the message/file, the key itself must be
invalidated:
```bash
aws iam delete-access-key --user-name tessera-app-deploy --access-key-id <the-leaked-id>
aws iam create-access-key --user-name tessera-app-deploy
```
Prefer `gh secret set NAME` (prompts for the value, never takes it as a visible argument or echoes
it) over any command form that puts the secret value in plain text on the command line.

### `gh: command not found`
The GitHub CLI isn't installed. Either install it (`winget install GitHub.cli`) or just use the
GitHub web UI (**Settings → Secrets and variables → Actions → New repository secret**) — it's only
a name + value form either way, nothing CLI-specific is required.

### Docker build fails: "failed to connect to the docker API at npipe://.../dockerDesktopLinuxEngine"
**Cause:** Docker Desktop isn't running. This one's exactly what it looks like.
**Fix:** start Docker Desktop, wait for it to report "running" in the system tray, then re-run
`./aws/push-to-ecr.sh`.

### ECS task fails at a THIRD stage, after secrets resolve fine: "AccessDeniedException ... logs:CreateLogGroup"
**Symptom:** the task now gets past secrets injection (no more SSM/Secrets Manager errors) and
actually starts, then still fails to place with:
```
ResourceInitializationError: failed to validate logger args: create stream has been retried ...:
failed to create Cloudwatch log group: ... AccessDeniedException: ... not authorized to perform:
logs:CreateLogGroup on resource: arn:aws:logs:...:log-group:/ecs/tessera-app:log-stream:
```
**Cause:** `aws/task-definition.json` sets `"awslogs-create-group": "true"`, asking the execution
role to create the `/ecs/tessera-app` CloudWatch log group itself on first run. The managed policy
attached to `ecsTaskExecutionRole` in Step 1 (`AmazonECSTaskExecutionRolePolicy`) grants
`logs:CreateLogStream` and `logs:PutLogEvents`, but **not** `logs:CreateLogGroup` — a genuine gap
in that AWS-managed policy for this exact setting, not something Step 1 did wrong per se.
**Fix:** `aws/setup.sh` now attaches an inline policy granting `logs:CreateLogGroup` scoped to
`/ecs/tessera-app`, applied unconditionally in Step 1 (so it self-heals even on an already-existing
role from before this fix). Re-running `setup.sh` is enough — no new task definition revision is
needed for this one, since it's a pure IAM gap, not a template bug; ECS will retry placing the
task automatically once the role has the permission.

### `--aiven-host mysql-xyz.aivencloud.com` was never a real hostname
**Symptom:** everything up through this point succeeds — IAM, secrets, ECS, ALB all report
healthy — but the container itself crashes on boot with `java.net.UnknownHostException:
mysql-xyz.aivencloud.com: Name does not resolve`, and it keeps crash-looping.
**Cause:** `mysql-xyz.aivencloud.com` is the illustrative placeholder from this file's own usage
example (same trap as the `example.com` domain above) — it was used verbatim across several
`setup.sh` runs and even the manual `mysql ... < schema.sql` command before anyone noticed, simply
because it *looks* like a real Aiven hostname. Unlike the domain placeholder, nothing earlier in
the pipeline validates DB connectivity — Secrets Manager and the task definition happily store and
pass along a string that just happens to not resolve to anything.
**Fix:** get your actual host and port from **Aiven console → your service → Overview → Connection
information**, then re-run `setup.sh` with the real `--aiven-host`/`--aiven-port` (this registers a
corrected task definition revision — `MYSQL_HOST`/`MYSQL_PORT` are baked in as plain environment
values, not secrets, so a new revision is the only way to change them) and re-run the schema-apply
command with the real host too, since it silently "succeeded" at connecting to nothing before.

### App container starts fine, connects to the real DB, but every page returns 401
**Symptom:** the container boots successfully, logs show `Started
AngularSpringBootFullStackApplication`, Tomcat comes up on 8080 — but loading the app in a browser
(even the bare root URL, not logged in yet) returns a raw JSON `401 UNAUTHORIZED` instead of the
Angular login page.
**Cause:** a genuinely new bug, only possible to discover once this deployment shape (Angular
compiled into the same jar as the API — see `Dockerfile`) was actually hit by a real browser for
the first time. `SecurityConfig`'s `.requestMatchers(GET, "/**").hasAnyAuthority("READ:USER",
"READ:CUSTOMER")` requires an authenticated, authorized user for **every** GET request — including
the SPA's own `index.html` and JS/CSS bundles. This was never a problem in local dev, where
Angular runs on its own separate dev server (port 4200) and never touches this backend's security
filter chain at all; Docker/prod is the only environment where the SPA and the API share one
origin and one filter chain.
**Fix:** explicitly `permitAll` the SPA shell and static assets (`/`, `/index.html`, `/assets/**`,
JS/CSS/image/font extensions) **before** the broad `GET /**` rule in `SecurityConfig` — matchers
are evaluated top-down, first match wins, so the specific permits must precede the broad
authority requirement. Also added a `WebMvcConfig#addViewControllers` forward-to-`index.html` for
any extensionless path, so a hard refresh on a deep Angular route (e.g. `/dashboard`) doesn't 404 —
real `@RequestMapping` controllers still take precedence over this lower-priority view-controller
mapping, so no actual API route is shadowed. Requires an actual code change + rebuild
(`push-to-ecr.sh` rebuilds and pushes the jar with the fix baked in, then `--force-new-deployment`
picks up the new `:latest` image), not just an infra/IAM change like the ones above — and since
the tag stays `:latest`, there's no need to re-register the task definition for this one.

### New task starts and connects fine, but still gets killed and replaced in a loop
**Symptom:** the ECS task boots successfully (logs show `Started
AngularSpringBootFullStackApplication in ~85 seconds`, no errors) and even serves at least one
request — but shortly after, it's marked `Target.FailedHealthChecks` and stopped, and a new task
starts in its place, repeating indefinitely. The service never converges.
**Cause:** a timing gap, not a bug in the app. This app's real cold start (JVM + full Spring
context + Hibernate + JPA + S3 client init) consistently takes ~80-85 seconds on the task's 512
CPU / 1024 MB allocation. `setup.sh` originally set `--health-check-grace-period-seconds 90` —
barely more than the boot time itself, leaving almost no buffer for the ALB to then accumulate
enough *consecutive passing* checks to flip the target from `initial`/`unhealthy` to `healthy`
before ECS's grace period expires and starts trusting the ALB's (still-catching-up) verdict. The
ALB's own health checks fail outright for the first ~80 seconds simply because nothing is
listening on 8080 yet, which is enough failures to flag the target unhealthy well before the app
ever gets a chance to prove otherwise.
**Fix:** `setup.sh` now uses `--health-check-grace-period-seconds 300`. For a service that already
exists (so `setup.sh`'s create-service branch won't touch it), apply it directly and force one more
attempt with the longer runway:
```bash
aws ecs update-service --cluster tessera-app-cluster --service tessera-app-service \
  --health-check-grace-period-seconds 300 --force-new-deployment --region us-east-1
```
Gotcha hit while writing this exact fix: don't put a `#` comment in the middle of a
backslash-continued multi-line command — everything after the `#` on that joined logical line,
including subsequent continuation lines, silently becomes part of the comment and is dropped.
Comments belong entirely before the command starts, never interleaved between its `\`-continued
arguments.
