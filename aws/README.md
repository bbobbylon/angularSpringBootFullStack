# TesseraApp — AWS Deployment Guide

End-to-end checklist for deploying TesseraApp to AWS using ECS Fargate + **Aiven MySQL** (managed DB) + S3 (image storage) + ALB (HTTPS termination) + Secrets Manager (secrets injection).

---

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
> lives in **`db3`** (see [database.md §17.4](../documentation/database.md#174-migrating-native--aiven-how-db3-was-created)
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

# Replace YOUR_DOMAIN with your actual domain:
aws s3api put-bucket-cors --bucket tessera-app-images --cors-configuration '{
  "CORSRules":[{
    "AllowedOrigins":["https://YOUR_DOMAIN", "http://localhost:4200"],
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
# ... repeat for mail-*, twilio-*, google-*, github-*
```

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

The GitHub Actions `deploy.yml` workflow handles this automatically on every push to `master`. To trigger manually:

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
| `APP_DOMAIN` | `http://your-alb-dns-name` unless Step 8 (real HTTPS) is actually done, then `https://your-real-domain` |

---

## Troubleshooting: real errors hit running `setup.sh`

Every one of these was hit running this exact script end-to-end on Windows/Git Bash. Most of the
script-level bugs are already fixed in `aws/setup.sh` and `aws/task-definition.json` — they're
documented here so you recognize the symptom instantly if it resurfaces (e.g. a teammate on an
older checkout, or the same mistake in a hand-run manual command) instead of re-debugging from
scratch.

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
