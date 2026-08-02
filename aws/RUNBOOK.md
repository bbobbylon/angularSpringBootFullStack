# TesseraApp — AWS Deploy Runbook

**Version:** 1.0
**Last Updated:** 2026-07-29
**Status:** Final
**Audience:** anyone who needs to deploy or redeploy this app to AWS without asking the person who built it.

## Overview

This is the **linear, do-this-in-order** procedure. It assumes nothing and skips no step. Follow it top to bottom and you get a running deployment.

Its companion, [`README.md`](README.md), is the **reference**: what each AWS resource is for, and a long troubleshooting log of real errors hit building this. When something here fails, that is where you look. This file tells you *what to do*; that one tells you *why it broke*.

- **Deploying a code change to an already-working environment?** Skip to [Part D](#part-d--redeploy-the-90-second-loop).
- **Standing it up from nothing?** Start at Part A.

## Table of contents

- [What already exists](#what-already-exists)
- [Part A — One-time prerequisites](#part-a--one-time-prerequisites-your-machine)
- [Part B — One-time AWS infrastructure](#part-b--one-time-aws-infrastructure)
- [Part C — One-time application setup](#part-c--one-time-application-setup-the-part-people-forget)
- [Part D — Redeploy: the 90-second loop](#part-d--redeploy-the-90-second-loop)
- [Part E — Verify a deployment](#part-e--verify-a-deployment)
- [Part F — Known limitations right now](#part-f--known-limitations-right-now)
- [Part G — Clean rebuild from zero](#part-g--clean-rebuild-from-zero)
- [Part H — Reading the logs](#part-h--reading-the-logs)
- [Appendix — Every environment variable](#appendix--every-environment-variable)

---

## What already exists

The deployment is live. These are the real, current values — you do not need to invent any of them.

| Thing | Value |
|---|---|
| App URL | `http://tessera-app-alb-1750339159.us-east-1.elb.amazonaws.com` |
| Region | `us-east-1` |
| ECS cluster | `tessera-app-cluster` |
| ECS service | `tessera-app-service` |
| Task definition family | `tessera-app` |
| ECR repository | `tessera-app` |
| S3 bucket (profile images) | `tessera-app-images` |
| Database | **Aiven MySQL, database `db3`** |
| Spring profile | `prod` |
| Secrets Manager prefix | `tessera-app/` |
| CloudWatch log group | `/ecs/tessera-app` |

> **`db3`, never `defaultdb`.** `defaultdb` is the empty database Aiven auto-creates with every service. Point a task at it and the app boots perfectly, serves the SPA perfectly, and fails every single sign-in — because the schema may well be there but there is not one user row in it. This has cost real hours. Verify with the command in [Part E](#part-e--verify-a-deployment).

---

## Part A — One-time prerequisites (your machine)

### A1. Install the tooling

| Tool | Verify with | Windows install |
|---|---|---|
| AWS CLI v2 | `aws --version` | `winget install Amazon.AWSCLI` |
| `jq` | `jq --version` | `winget install jqlang.jq` |
| Docker Desktop | `docker info` | `winget install Docker.DockerDesktop` |
| Git Bash | you're in it | ships with Git for Windows |
| MySQL client | `mysql --version` | ships with MySQL Server; on this machine: `C:\Program Files\MySQL\MySQL Server 8.0\bin` |

**Run every command in this runbook from Git Bash or WSL, never PowerShell.** These are shell scripts. In PowerShell `chmod` does not exist and `./aws/setup.sh` silently does nothing, which looks exactly like success.

**Docker Desktop must be actually running**, not just installed — watch for "Engine running" in the system tray. If it isn't, the image build fails with `failed to connect to the docker API at npipe://…`. This is the single most common false start.

### A2. Configure AWS credentials

```bash
aws configure          # access key, secret key, region us-east-1, output json
aws sts get-caller-identity    # must print your account id

# Do this too — see below. One line, saves you a lot of irritation.
aws configure set cli_pager ""
```

**Turn the pager off.** AWS CLI v2 pipes every response through a pager (`more` on Windows, `less` elsewhere). `more` advances **one line per Enter**, so a command like `aws ecs update-service` — which returns the entire service object — leaves you holding Enter for a minute to get back to a prompt. If you're stuck in it right now, press **`q`**.

`aws configure set cli_pager ""` writes `cli_pager =` to `~/.aws/config` and disables it permanently. Per-shell (`export AWS_PAGER=""`) and per-command (`--no-cli-pager`) alternatives exist, but the config setting is the one you want: **a pager in a script is not a nuisance, it is a hang.** It blocks waiting for a keypress that never arrives, which in CI looks like a job that times out with no error message. That is why every AWS command in this runbook ends in `--query … --output text` or `>/dev/null` — it is not stylistic.

> ⚠️ **Do not use root account keys.** The identity currently configured on the original dev machine is the account root, which is a standing risk: a leaked root key is total account compromise, and it cannot be scoped. Create the limited `tessera-app-deploy` IAM user documented in [`README.md` → GitHub Actions secrets](README.md#github-actions-secrets-to-configure) and use that instead. The policy there grants exactly ECR push + ECS register/update + `iam:PassRole` on the two task roles + `secretsmanager:DescribeSecret` — nothing else.

### A3. Get the repo and make the scripts executable

```bash
git clone <repo-url>
cd angularSpringBootFullStack
chmod +x aws/setup.sh aws/secrets-setup.sh aws/push-to-ecr.sh
```

`chmod +x` sets a bit on the file. It persists. You do not need to re-run it before every invocation.

---

## Part B — One-time AWS infrastructure

**If the resources in [What already exists](#what-already-exists) are present, skip to Part C.** This section is for building a fresh environment (a new account, a second region, a teammate's own sandbox).

### B1. Run the bootstrap script

`aws/setup.sh` performs all nine infrastructure steps — IAM roles, S3 bucket, ECR repo, Secrets Manager entries, ECS cluster, security groups, ALB, target group, ECS service — and prints your ALB DNS name at the end.

```bash
AWS_REGION=us-east-1 ./aws/setup.sh \
  --domain      tessera-app-alb-1750339159.us-east-1.elb.amazonaws.com \
  --aiven-host  <your-service>.aivencloud.com \
  --aiven-port  <your-port> \
  --aiven-db    db3 \
  --aiven-user  avnadmin
```

Three things people get wrong here:

1. **`--domain` is the hostname only**, no scheme. The script prepends `http://` itself. On a fresh environment you don't have the ALB DNS name yet — run once with any placeholder, read the real name from the script's own Step 7 output, then re-run.
2. **`--aiven-db` must be `db3`.** See the warning above.
3. **`--aiven-host` must be your real Aiven hostname.** The `mysql-xyz.aivencloud.com` in the docs is illustrative and has been pasted verbatim before, producing a crash-loop with `UnknownHostException` after everything else reported healthy. Get it from **Aiven console → your service → Overview → Connection information**.

Omit `--aiven-password` and the script prompts, keeping it out of your shell history.

### B2. Fill in the real secret values

`setup.sh` creates every secret with a `CHANGE_ME` placeholder so the task definition can reference a real ARN. **Replace them before the first deploy** — several are load-bearing in non-obvious ways:

```bash
R="--region us-east-1"
aws secretsmanager update-secret $R --secret-id tessera-app/db-password           --secret-string '<aiven password>'
aws secretsmanager update-secret $R --secret-id tessera-app/mail-username         --secret-string '<gmail address>'
aws secretsmanager update-secret $R --secret-id tessera-app/mail-password         --secret-string '<16-char gmail app password>'
aws secretsmanager update-secret $R --secret-id tessera-app/jwt-secret            --secret-string "$(openssl rand -base64 48)"
aws secretsmanager update-secret $R --secret-id tessera-app/google-client-id      --secret-string '<...>'
aws secretsmanager update-secret $R --secret-id tessera-app/google-client-secret  --secret-string '<...>'
aws secretsmanager update-secret $R --secret-id tessera-app/github-client-id      --secret-string '<...>'
aws secretsmanager update-secret $R --secret-id tessera-app/github-client-secret  --secret-string '<...>'
aws secretsmanager update-secret $R --secret-id tessera-app/microsoft-client-id     --secret-string '<...>'
aws secretsmanager update-secret $R --secret-id tessera-app/microsoft-client-secret --secret-string '<...>'
```

If the values already exist in a local `.env`, copy them across without ever printing them:

```bash
set -a; source .env; set +a
aws secretsmanager create-secret --region us-east-1 \
  --name tessera-app/microsoft-client-id --secret-string "$MICROSOFT_CLIENT_ID"
```

**Why mail credentials matter more than they look:** risk-based step-up (FR-TPF-1) **withholds tokens until the emailed code is entered**. Leave mail credentials as `CHANGE_ME` and any account the risk engine flags becomes unreachable — the user is not rejected, they are simply never sent the code they are being asked for.

**Why a provider's secrets decide whether it exists:** `OAuth2ClientConfig#federatedProviderCatalog` skips any provider with a blank client id, and the login screen renders exactly what `GET /oauth2/providers` returns. A provider configured in `.env` but not in Secrets Manager appears locally and is silently absent when deployed. That is precisely what happened with Microsoft.

### B3. Register the callback URLs in each provider console

Register **both** — all three providers accept a list, so one app registration serves local and deployed use:

```
http://localhost:8080/login/oauth2/code/{provider}                                    # start.sh
http://tessera-app-alb-1750339159.us-east-1.elb.amazonaws.com/login/oauth2/code/{provider}   # AWS
```

- **GitHub** → Settings ▸ Developer settings ▸ OAuth Apps. Accepts `http`. **This is the only provider that works on the current URL** (see [Part F](#part-f--known-limitations-right-now)).
- **Google** → Cloud Console ▸ APIs & Services ▸ Credentials. Requires `https` for anything but `localhost`.
- **Microsoft** → entra.microsoft.com ▸ your app ▸ **Authentication**. Requires `https` for anything but `localhost`. The redirect URI must sit under the **Web** platform, not SPA or mobile, and *Allow public client flows* must be **No** — otherwise Entra treats the app as a public client and rejects the (correct) `client_secret_post` auth with `AADSTS90023: Public clients can't send a client secret`. That is a portal setting; the Spring config is already right.

Client **id** and tenant id come from the app's **Overview** page. The client **secret** comes from **Certificates & secrets ▸ Client secrets**, and you must copy the **Value** column, not **Secret ID** — the Value is displayed once, immediately after creation, and is unrecoverable afterwards. *Federated credentials* on that same page are a different feature (workload identity for CI/CD) and are not what this app uses.

---

## Part C — One-time application setup (the part people forget)

Infrastructure being healthy does not mean the app is usable. These three steps are inside the database, and nothing automates them.

### C1. Apply the schema to `db3`

The app **never** creates its own schema: `spring.sql.init.mode: never`, and the `prod` profile runs `ddl-auto: validate`, which verifies the JPA tables and **fails startup** on drift rather than silently altering anything. So `schema.sql` is applied by hand, exactly once per database.

```bash
# CA cert from Aiven console → Service → Overview → CA Certificate
mysql --host=<your-service>.aivencloud.com --port=<port> --user=avnadmin --password \
      --ssl-ca=aiven-ca.pem --ssl-mode=REQUIRED \
      db3 < src/main/resources/schema.sql
```

It is idempotent — `CREATE TABLE IF NOT EXISTS`, no `DROP`s — so re-running is safe and is the correct fix when a column is missing.

> **Run the whole file, not the parts you think you need.** `schema.sql` carries seed data as well as DDL: the roles and their authority strings, the events catalogue, and the services catalogue. A database with the tables but not the seeds boots fine and then behaves strangely — most visibly, the services catalogue is empty and the page tells you to add entries via an admin panel.

### C2. Give yourself an admin role

`UserRepoImpl` grants every newly registered account **`ROLE_USER`**, which holds `READ:USER` / `READ:CUSTOMER` and nothing else. That is correct and deliberate — but it means the first account you register on a fresh database cannot see the Admin menu, the user directory, the roles matrix, the security overview, or the services admin screen. Nothing is broken; you simply have not granted yourself anything.

```sql
SELECT id, name FROM roles;                       -- find ROLE_SUPER_ADMIN's id
SELECT id, email FROM users;                      -- find your own id

UPDATE userroles
   SET role_id = (SELECT id FROM roles WHERE name = 'ROLE_SUPER_ADMIN')
 WHERE user_id = (SELECT id FROM users WHERE email = 'you@example.com');
```

**Then sign out and back in.** Authorities are baked into the JWT at issuance; your existing token still says `ROLE_USER` and will keep saying so until it is reissued.

### C3. Confirm the proxy settings are in the task definition

Both are already in `aws/task-definition.json`. Confirm they survived into the running revision — each fails *silently*, which is what makes them worth checking rather than assuming:

| Variable | Value | What breaks at the default |
|---|---|---|
| `FORWARD_HEADERS_STRATEGY` | `framework` | `{baseUrl}` in the OAuth2 redirect-uri template resolves to the container's own `http://<task-ip>:8080`. Every federated sign-in dies on `redirect_uri_mismatch` while working perfectly on localhost. |
| `TRUSTED_PROXY_COUNT` | `1` | `X-Forwarded-For` is ignored and every user appears to be the load balancer. The rate limiter throttles the whole tenant as one caller, and the anomaly detector's `NEW_NETWORK` signal can never fire — so step-up looks present and does nothing. |

---

## Part D — Redeploy: the 90-second loop

This is the loop you will actually use day to day.

```bash
cd /path/to/angularSpringBootFullStack

# 0. Docker Desktop must be running.

# 1. Build the image from your CURRENT working tree and push it to ECR.
#    Multi-stage: Angular is compiled and baked into the Spring Boot jar.
AWS_REGION=us-east-1 ./aws/push-to-ecr.sh latest

# 2. Roll the service onto the new image.
aws ecs update-service \
  --cluster tessera-app-cluster \
  --service tessera-app-service \
  --force-new-deployment \
  --region us-east-1

# 3. Watch it converge (Ctrl-C once runningCount reaches 1 and stays).
aws ecs describe-services --cluster tessera-app-cluster --services tessera-app-service \
  --region us-east-1 --query 'services[0].{running:runningCount,desired:desiredCount,status:status}'
```

**Step 2 is enough only when the image tag is unchanged.** ECS pulls `:latest` fresh on each new task, so a code change needs no new task-definition revision.

**You must re-register the task definition when you change a task-definition value** — an environment variable, a secret reference, CPU/memory. Those are baked into the revision, not read live:

```bash
export AWS_ACCOUNT_ID=$(aws sts get-caller-identity --query Account --output text)
export AWS_REGION=us-east-1
export ECR_IMAGE_URI="${AWS_ACCOUNT_ID}.dkr.ecr.${AWS_REGION}.amazonaws.com/tessera-app:latest"
export AIVEN_HOST=<your-service>.aivencloud.com
export AIVEN_PORT=<port>
export AIVEN_DB=db3
export AIVEN_USER=avnadmin
export S3_BUCKET=tessera-app-images
export APP_DOMAIN=http://tessera-app-alb-1750339159.us-east-1.elb.amazonaws.com

# Resolve every secret's COMPLETE ARN — the random suffix is required. A bare name or a
# hand-built ARN is ambiguous to ECS, which falls back to an SSM Parameter Store lookup
# and fails with AccessDeniedException on ssm:GetParameters.
for s in JWT_SECRET:jwt-secret DB_PASSWORD:db-password MAIL_USERNAME:mail-username \
         MAIL_PASSWORD:mail-password TWILIO_SID:twilio-sid TWILIO_TOKEN:twilio-token \
         TWILIO_FROM_NUMBER:twilio-from-number GOOGLE_CLIENT_ID:google-client-id \
         GOOGLE_CLIENT_SECRET:google-client-secret GITHUB_CLIENT_ID:github-client-id \
         GITHUB_CLIENT_SECRET:github-client-secret MICROSOFT_CLIENT_ID:microsoft-client-id \
         MICROSOFT_CLIENT_SECRET:microsoft-client-secret; do
  export "${s%%:*}_ARN=$(aws secretsmanager describe-secret --secret-id "tessera-app/${s##*:}" \
                          --query ARN --output text --region us-east-1)"
done

# Strip the _comment/_variables documentation keys — register-task-definition validates
# strictly and rejects unknown keys. Inline the JSON rather than using file:// — a native
# aws.exe on Git Bash resolves file:// URIs unreliably even when the file exists.
FILLED="$(envsubst < aws/task-definition.json | jq 'del(._comment, ._variables)')"

# GUARD — do not skip this. envsubst replaces an UNSET variable with an empty string and
# exits 0, so a forgotten export produces a structurally valid task definition carrying a
# silently empty value. ECS then accepts the revision and every task fails to place.
BAD=$(echo "$FILLED" | jq '
  [ .containerDefinitions[0].environment[]?          | select(.value    == "") ]
+ [ .containerDefinitions[0].secrets[]?              | select(.valueFrom== "") ]
+ [ .containerDefinitions[0].logConfiguration.options | to_entries[] | select(.value == "") ]
| length')
if [ "$BAD" -ne 0 ]; then
  echo "ABORT: $BAD empty value(s) after envsubst — an export is missing:"
  echo "$FILLED" | jq '.containerDefinitions[0]
    | {env: [.environment[]?|select(.value=="")|.name],
       secrets: [.secrets[]?|select(.valueFrom=="")|.name],
       logs: [.logConfiguration.options|to_entries[]|select(.value=="")|.key]}'
  exit 1
fi

aws ecs register-task-definition --cli-input-json "$FILLED" --region us-east-1

aws ecs update-service --cluster tessera-app-cluster --service tessera-app-service \
  --task-definition tessera-app --force-new-deployment --region us-east-1
```

**Rotating a secret's value needs no new revision** — the reference is to the secret, not its contents — but it *does* need a task restart, because ECS resolves secrets at container start:

```bash
aws secretsmanager update-secret --region us-east-1 \
  --secret-id tessera-app/jwt-secret --secret-string "$(openssl rand -base64 48)"
aws ecs update-service --cluster tessera-app-cluster --service tessera-app-service \
  --force-new-deployment --region us-east-1
```

**Cold start is ~80–85 seconds** on 512 CPU / 1024 MB (JVM + Spring context + Hibernate + S3 client). The service's health-check grace period is 300s to accommodate that. If you see tasks looping on `Target.FailedHealthChecks`, the grace period is too short — not the app failing.

---

## Part E — Verify a deployment

Run these in order; each isolates a different failure.

```bash
# 1. Which task definition is the service ACTUALLY running, and against which database?
#    (`--task-definition tessera-app` alone gives the newest revision, not the deployed one.)
aws ecs describe-task-definition \
  --task-definition "$(aws ecs describe-services --cluster tessera-app-cluster \
      --services tessera-app-service --query 'services[0].taskDefinition' --output text)" \
  --query "taskDefinition.containerDefinitions[0].environment[?starts_with(name,'MYSQL')]" \
  --output table
#    → MYSQL_DATABASE must be db3.

# 2. Is it up?
curl -s http://tessera-app-alb-1750339159.us-east-1.elb.amazonaws.com/actuator/health
#    → {"status":"UP"}

# 3. Which federated providers actually reached the container?
curl -s http://tessera-app-alb-1750339159.us-east-1.elb.amazonaws.com/oauth2/providers

# 4. Recent logs (startup failures, ddl-auto validation errors, the [NET] line).
#
#    MSYS2_ARG_CONV_EXCL is REQUIRED on Git Bash and is not optional decoration. MSYS rewrites
#    any bare argument starting with "/" into a Windows path before handing it to the native
#    aws.exe, so "/ecs/tessera-app" arrives as "C:/Program Files/Git/ecs/tessera-app" and AWS
#    rejects it: "Value at 'logGroupName' failed to satisfy constraint: Member must satisfy
#    regular expression pattern: [\.\-_/#A-Za-z0-9]+" — note that pattern allows no colon and
#    no space, which is what the mangled path introduced. On Linux/macOS the prefix is a no-op.
MSYS2_ARG_CONV_EXCL='/ecs/tessera-app' \
  aws logs tail /ecs/tessera-app --since 10m --region us-east-1

# The three lines worth grepping for on any fresh deploy:
MSYS2_ARG_CONV_EXCL='/ecs/tessera-app' \
  aws logs tail /ecs/tessera-app --since 15m --region us-east-1 \
  | grep -E "Started Angular|\[NET\]|Federated login providers"
```

A healthy boot looks like this — check all three:

```
[NET] trusted-proxy-count=1 — the client address is read from X-Forwarded-For ...
Federated login providers configured: [google, github, microsoft]
Started AngularSpringBootFullStackApplication in 86.503 seconds
```

`trusted-proxy-count=0` means the anomaly detector and rate limiter are silently degraded (Part C3).
A short provider list means a `*_CLIENT_ID` never reached the container (Part B2). ~85s is the
expected cold start, not a problem.

Then, in a browser:

| Check | Confirms |
|---|---|
| Sign in with a password account | `JWT_SECRET`, the datasource, `db3` seed data |
| Count the provider buttons on the login screen | which `*_CLIENT_ID`s reached the container — this number *is* `/oauth2/providers` |
| Complete one federated sign-in | `FORWARD_HEADERS_STRATEGY` took effect; `redirect_uri_mismatch` means it did not |
| Register a throwaway account, click the emailed link | mail credentials, the HTML template, and that the link lands on `/verify/account/:key` rather than raw JSON |
| Navigate to a protected URL while signed out | a styled 401 page, not raw JSON |
| Grep the boot log for `[NET] trusted-proxy-count=` | it is **not** `0` |
| Open the services catalogue | `schema.sql` seeds were applied (Part C1) |
| Open the Admin menu | your account has a staff role (Part C2) |

---

## Part F — Known limitations right now

**The ALB serves plain HTTP.** Step 8 of [`README.md`](README.md) (ACM certificate + HTTPS listener) has not been done, because it requires a domain you can prove ownership of. Consequences:

| Affected | Effect |
|---|---|
| Google federated login | **Blocked** — Google requires `https` on redirect URIs, excepting only `localhost` |
| Microsoft federated login | **Blocked** — same rule in Entra |
| GitHub federated login | Works; GitHub permits `http` callbacks |
| WebAuthn / passkeys (roadmap §3.1) | Would not work — the API requires a secure context |
| HSTS | Sent but inert |

**The cheapest fix is CloudFront**, not a domain purchase: a distribution in front of the ALB using its auto-issued `*.cloudfront.net` certificate gives a real, publicly trusted HTTPS origin for free, and `https://d1234abcd5678.cloudfront.net` **is** accepted by Google and Entra. The hostname is randomly assigned and cannot be customised. Afterwards: update `APP_DOMAIN`/`UI_APP_URL` to the new origin, re-register the task definition, add the new callback URLs in all three provider consoles, and set `TRUSTED_PROXY_COUNT=2` (CloudFront adds a hop).

**Security state is per-instance.** The brute-force counter, the rate limiter's buckets, and `ProviderLinkTicketService` all live in the task's memory. Harmless at `desiredCount: 1`; a real bypass the moment a second task runs, because an attacker routed to the other instance gets a fresh budget. Tracked in [`ROADMAP.md`](../ROADMAP.md) §3.1.

**A production boot against a `schema.sql`-only database with `ddl-auto: validate` has never been exercised end to end.** Only the offline `JpaSchemaSyncTest` has run, and it catches entity/DDL drift but not a schema the app has never actually started against. Tracked in `ROADMAP.md` §2.3.

---

## Part G — Clean rebuild from zero

For proving the procedure actually works end to end, or handing a teammate their own sandbox.

### G0. What you must NOT delete

| Keep | Why |
|---|---|
| **The Aiven database (`db3`)** | It holds every user, role, customer and invoice. Deleting the AWS side costs you nothing permanent; deleting `db3` costs you the application's entire state. **Nothing in this teardown touches Aiven.** |
| **Your local `.env`** | The only copy of several credential values that are otherwise unrecoverable — notably the Entra client secret, whose Value cannot be re-read from the portal after creation. |

### G1. Tear down, in dependency order

Resources have to go in this order; a cluster will not delete while a service exists, and a bucket will not delete while it holds objects.

```bash
R="--region us-east-1"

# 1. Drain and delete the ECS service (scale to 0 first, or the delete is rejected).
aws ecs update-service $R --cluster tessera-app-cluster --service tessera-app-service --desired-count 0 >/dev/null
aws ecs wait services-stable $R --cluster tessera-app-cluster --services tessera-app-service
aws ecs delete-service $R --cluster tessera-app-cluster --service tessera-app-service --force >/dev/null

# 2. Deregister every task-definition revision (they linger as INACTIVE; harmless but noisy).
for arn in $(aws ecs list-task-definitions $R --family-prefix tessera-app --query 'taskDefinitionArns[]' --output text); do
  aws ecs deregister-task-definition $R --task-definition "$arn" >/dev/null
done

# 3. Cluster.
aws ecs delete-cluster $R --cluster tessera-app-cluster >/dev/null

# 4. Empty, then delete, the S3 bucket. Delete refuses on a non-empty bucket.
aws s3 rm s3://tessera-app-images --recursive
aws s3 rb s3://tessera-app-images

# 5. ECR repository (--force deletes the images inside it too).
aws ecr delete-repository $R --repository-name tessera-app --force >/dev/null

# 6. CloudWatch log group.
aws logs delete-log-group $R --log-group-name /ecs/tessera-app

# 7. ALB, listeners and target group. Listeners go with the load balancer; the target group
#    must be deleted separately and only AFTER the ALB, or it is reported as still in use.
ALB=$(aws elbv2 describe-load-balancers $R --names tessera-app-alb --query 'LoadBalancers[0].LoadBalancerArn' --output text)
aws elbv2 delete-load-balancer $R --load-balancer-arn "$ALB"
TG=$(aws elbv2 describe-target-groups $R --names tessera-app-tg --query 'TargetGroups[0].TargetGroupArn' --output text)
aws elbv2 delete-target-group $R --target-group-arn "$TG"

# 8. IAM roles — detach/delete policies first; a role with attachments will not delete.
aws iam delete-role-policy --role-name tessera-app-task-role --policy-name S3ImageStorage
aws iam delete-role --role-name tessera-app-task-role
aws iam detach-role-policy --role-name ecsTaskExecutionRole --policy-arn arn:aws:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy
aws iam detach-role-policy --role-name ecsTaskExecutionRole --policy-arn arn:aws:iam::aws:policy/SecretsManagerReadWrite
aws iam delete-role --role-name ecsTaskExecutionRole
```

> **Security groups** created by `setup.sh` can only be deleted once nothing references them — the
> ALB's ENIs take a few minutes to disappear after step 7. If `DependencyViolation` comes back, wait
> and retry; it is not a stuck state.

### G2. Secrets — the trap that will cost you a week if you get it wrong

**Do not run a plain `delete-secret` if you intend to rebuild soon.** Secrets Manager defaults to a
**7-to-30-day recovery window**, during which the secret still exists in a `PendingDeletion` state
and **its name cannot be reused**. `setup.sh` will then fail to create `tessera-app/jwt-secret`
because the name is taken by something you cannot see in the console's default view.

The right move is usually **to leave the secrets alone entirely.** They cost $0.40/month each, they
hold the values you would otherwise have to hunt down again (including the unrecoverable Entra
secret), and `setup.sh` skips any that already exist. A clean rebuild of the *infrastructure* does
not require destroying the *credentials*.

If you genuinely want them gone and recreatable immediately:

```bash
for s in jwt-secret db-password mail-username mail-password twilio-sid twilio-token \
         twilio-from-number google-client-id google-client-secret github-client-id \
         github-client-secret microsoft-client-id microsoft-client-secret; do
  aws secretsmanager delete-secret --region us-east-1 \
    --secret-id "tessera-app/${s}" --force-delete-without-recovery >/dev/null
done
```

`--force-delete-without-recovery` is **irreversible and immediate**. Confirm your `.env` still holds
every value before running it.

### G3. Rebuild

```bash
# Infrastructure — Part B.
AWS_REGION=us-east-1 ./aws/setup.sh \
  --domain      <placeholder-then-rerun-with-the-real-ALB-DNS> \
  --aiven-host  <your-service>.aivencloud.com \
  --aiven-port  <port> \
  --aiven-db    db3 \
  --aiven-user  avnadmin

# Image — Part D.
AWS_REGION=us-east-1 ./aws/push-to-ecr.sh latest

# The new ALB has a NEW DNS name. Re-run setup.sh with it as --domain so UI_APP_URL is
# correct, then add the new callback URLs to all three provider consoles (Part B3).
```

**`schema.sql` and your admin-role grant (Part C) do NOT need re-running** — they live in `db3`,
which this teardown never touched. That is the whole reason for the split: infrastructure is
disposable, the database is not.

### G4. Confirm

Run every check in [Part E](#part-e--verify-a-deployment). A rebuild is only proven when a real
sign-in works against the new ALB, not when the resources merely exist.

---

## Part H — Reading the logs

### H1. Where the logs actually are

There is **no log file anywhere** — not in the container, not on a volume, not on a host you
can reach. That is correct for Fargate, not an oversight: a file written inside the container
is destroyed when the task stops, which is exactly the moment you most want to read it.

```
Spring Boot (Logback console appender)
   │  no logback-spring.xml in the repo; no logging.file.* set anywhere
   ▼
Container stdout/stderr   (Dockerfile: ENTRYPOINT ["java","-jar","app.jar"], no redirection)
   ▼
ECS awslogs driver        (task-definition.json → logConfiguration)
   ▼
CloudWatch Logs
   ├─ Group:     /ecs/tessera-app
   ├─ Region:    us-east-1
   ├─ Stream:    ecs/tessera-app/<task-uuid>   ← one per task, so one per restart
   └─ Retention: 7 days                        ← set by setup.sh; see H5
```

ECS treats logging as a hard dependency: if the awslogs driver cannot initialise, the task does
not start at all. That is why a missing `AWS_REGION` surfaces as `ResourceInitializationError`
rather than as silently missing logs.

### H2. Reading them

**Live tail** — the day-to-day loop. `MSYS2_ARG_CONV_EXCL` is mandatory on Git Bash (see Part E
for why the log-group name gets mangled without it):

```bash
MSYS2_ARG_CONV_EXCL='/ecs/tessera-app' \
  aws logs tail /ecs/tessera-app --since 15m --follow --region us-east-1
```

Run it in one window while you `--force-new-deployment` in another to watch a boot in real time.

**Logs Insights** — CloudWatch console ▸ Logs Insights. Always scope to the log group:

```
SOURCE logGroups(namePrefix: ["/ecs/tessera-app"]) START=-3600s END=0s
| fields @timestamp, @message
| sort @timestamp desc
| limit 100
```

```
-- Auth and RBAC decisions (AuthDiagnosticsLogger's four tags)
SOURCE logGroups(namePrefix: ["/ecs/tessera-app"]) START=-86400s END=0s
| fields @timestamp, @message
| filter @message like /\[AUTH-GRANT\]|\[AUTH-DENY\]|\[AUTH-LOCK\]|\[RBAC-DENY\]/
| sort @timestamp desc
| limit 50
```

```
-- Crash-loop detector: a healthy service is ONE stream with many events.
-- Ten streams of ~200 events each = ten tasks that booted and died.
SOURCE logGroups(namePrefix: ["/ecs/tessera-app"]) START=-3600s END=0s
| stats count(*) as events by @logStream
| sort events asc
```

### H3. Why you can't find the startup block

**`| sort @timestamp desc | limit 100` returns the 100 most recent events.** The boot sequence
happens once, when the task starts — possibly days ago. On a service that has been up a while it
is nowhere near the most recent 100 lines, so a descending query will *never* show it no matter
how far back `START` goes.

To read a boot, go to the **beginning of a stream** instead — each stream starts at container
start, so its first lines are always the banner and startup:

```bash
# Newest stream name, then its first 100 lines in order.
STREAM=$(MSYS2_ARG_CONV_EXCL='/ecs/tessera-app' aws logs describe-log-streams \
  --log-group-name /ecs/tessera-app --order-by LastEventTime --descending \
  --max-items 1 --query 'logStreams[0].logStreamName' --output text --region us-east-1)

MSYS2_ARG_CONV_EXCL='/ecs/tessera-app' aws logs get-log-events \
  --log-group-name /ecs/tessera-app --log-stream-name "$STREAM" \
  --start-from-head --limit 100 --region us-east-1 \
  --query 'events[].message' --output text
```

Or in Insights, `sort @timestamp asc` with a window that contains a deploy.

### H4. Local console vs AWS — what exists and what cannot

Running `./start.sh` puts **three** output sources in one terminal. Only one of them exists on ECS,
which is why the deployed logs feel sparse even though nothing is being dropped.

| Local console output | On AWS | Why |
|---|---|---|
| Spring Boot startup: banner, `Starting AngularSpringBootFullStackApplication`, active profile, Spring Data scan, Tomcat init, HikariPool, Hibernate `HHH…` + Database info, `Started … in Ns` | ✅ **Present** | Identical INFO logging — both profiles inherit the one `logging:` block in the base `application.yml`. If you can't see it, read H3. |
| `[NET] trusted-proxy-count=…`, `Federated login providers configured: […]` | ✅ Present | The two boot markers worth grepping on every deploy (Part E). |
| `[AUTH-GRANT]` / `[AUTH-DENY]`, `NewUserEvent received`, `Opened refresh session family` | ✅ Present | Runtime logging, unchanged. |
| `spring.jpa.open-in-view` WARN | ✅ Present | — |
| **Vite / Angular dev server** | ❌ Cannot exist | Angular is compiled into the jar at image-build time. There is no Angular *process* in prod — Tomcat serves static files. |
| **Maven** (`[INFO] --- spring-boot:run`, compiling, reactor summary) | ❌ Cannot exist | The jar is prebuilt in the image; Maven is not in the runtime layer. |
| **devtools**: `Restarting due to N class path changes`, `[restartedMain]` thread, `GracefulShutdown` on reload, `CONDITION EVALUATION DELTA` | ❌ Cannot exist | `spring-boot-devtools` is `<optional>true</optional>` / `runtime` scope, excluded from the packaged jar, and self-disables when it detects `java -jar`. Hot-restart in production would be a liability. Prod threads are `[main]` and `[nio-8080-exec-N]`, never `[restartedMain]`. |
| **`DemoDataSeeder`** | ❌ Dev only | Seeder runs on the `dev` profile. |
| **Hibernate SQL** | ⚠️ Off by default, but **reachable** | `application-prod.yml` pins `show-sql: false`, which closes the *System.out* path only. The `org.hibernate.SQL` **logger** at DEBUG is a separate SLF4J path that `show-sql` does not affect — so `LOG_LEVEL_HIBERNATE=DEBUG` **or** `DEBUG_REPORT=true` will put query text in CloudWatch under the prod profile. See [H6](#h6-turning-verbosity-up-on-a-deployed-task). |
| **ANSI colour** | ❌ No TTY | Spring Boot auto-detects a terminal to decide on colour; a container has none. Same text, plain. |

Two consequences worth internalising:

- **Prod logs matter more than dev logs.** `application-prod.yml` sets `expose-details: false`, which
  strips `devMessage` and the raw exception `reason` out of the HTTP response. That detail does not
  disappear — it lives *only* in CloudWatch now.
- **`PID 1`** in every prod line (`INFO 1 ---`) confirms Java is PID 1, as the `ENTRYPOINT` intends.
  Locally you see the real OS PID.

### H5. Free tier — the two things that actually cost

CloudWatch's free tier is **always-free**, not a 12-month trial: 5 GB ingestion, 5 GB archived
storage, 5 GB scanned by Logs Insights, 10 custom metrics, 10 alarms, 3 dashboards — per month.

**1. Retention.** `awslogs-create-group: true` creates a group with retention **Never Expire**.
Ingestion for one task stays far under 5 GB, but archived storage then grows forever against a
separate allowance, and you find out months later. `setup.sh` now creates the group explicitly and
sets **7 days**. Override with `LOG_RETENTION_DAYS=14 ./aws/setup.sh …`, or on an existing group:

```bash
MSYS2_ARG_CONV_EXCL='/ecs/tessera-app' \
  aws logs put-retention-policy --log-group-name /ecs/tessera-app \
    --retention-in-days 7 --region us-east-1
```

**2. Query scope.** Logs Insights bills on **bytes scanned**, and scanned bytes are determined
*only* by log groups × time range:

- `| limit 100` does **not** reduce cost — it truncates results after the scan.
- `| filter …` does **not** reduce cost either — same reason.
- `SOURCE logGroups(namePrefix: [], …)` with an **empty** prefix scans **every** log group in the
  account. Always name the group.

Start at 1 hour and widen only when you need to. Seven days is what your *retention* should be,
not your default query window.

**Do not enable Container Insights.** It is a one-click checkbox on the ECS cluster and it looks
like exactly what you want, but it publishes dozens of custom metrics per task and blows past the
10-free-custom-metric allowance immediately. On free tier, use log-based metric filters instead:

```bash
MSYS2_ARG_CONV_EXCL='/ecs/tessera-app' \
aws logs put-metric-filter --region us-east-1 \
  --log-group-name /ecs/tessera-app \
  --filter-name auth-lockouts \
  --filter-pattern '"[AUTH-LOCK]"' \
  --metric-transformations metricName=AuthLockouts,metricNamespace=TesseraApp,metricValue=1
```

### H6. Turning verbosity up on a deployed task

Log levels are environment-driven (`application.yml` → `logging.level`), so you can raise them
without a code change or a rebuild. Defaults are `INFO`, so nothing changes until you set one.

| Variable | Default | Use it when |
|---|---|---|
| `LOG_LEVEL_APP` | `INFO` | Something is wrong in **our** code. Cheapest, most targeted knob — start here. |
| `LOG_LEVEL_SECURITY` | `INFO` | You need to know *why* a request was rejected — which matcher matched, which authority was missing. |
| `LOG_LEVEL_WEB` | `INFO` | A route 404s in the deployed jar but works locally (SPA forwarding only applies in prod). |
| `LOG_LEVEL_HIBERNATE` | `INFO` | JPA/entity problems. ⚠️ DEBUG **does** print SQL — `show-sql: false` does not stop it (see below). |
| `DEBUG_REPORT` | `false` | "Did `S3ImageStorageService` actually activate, or did it fall back to local?" Prints the auto-configuration report at startup — the prod-available substitute for devtools' condition delta. **Broader than it sounds — see the note below.** |

> ### ⚠️ `show-sql: false` does NOT prevent SQL from being logged
>
> These are **two independent mechanisms**, and conflating them is the trap:
>
> | Mechanism | What turns it on | What prod does |
> |---|---|---|
> | `spring.jpa.show-sql=true` | Hibernate writes to **System.out**, prefixed `Hibernate: ` | Pinned **false** in `application-prod.yml` |
> | Logger `org.hibernate.SQL` at **DEBUG** | Hibernate logs the same statements via **SLF4J/Logback** | **Not covered by `show-sql` at all** |
>
> So **`LOG_LEVEL_HIBERNATE=DEBUG` *or* `DEBUG_REPORT=true` puts query text into CloudWatch even
> under the prod profile.** Statements carry `?` placeholders rather than bound values, so what
> leaks is the **schema** — tables, columns, join shape — not row data. Still a map of the system.
>
> **Observed for real on 2026-08-02:** `DEBUG_REPORT=true` on revision 10 produced **546
> `org.hibernate.SQL` events in 15 minutes** of ordinary browsing (~390 MB/month projected, ~8% of
> the free tier). Reverted in revision 11 the same day.
>
> **`DEBUG_REPORT=true` is not only a boot report.** Spring Boot's debug mode switches a selection
> of core loggers — embedded container, **Hibernate**, Spring Boot internals — to DEBUG. The report
> itself is ~930 lines once per boot; the mode then adds ~2-3 DEBUG lines per ALB health check
> indefinitely, *plus* all the Hibernate SQL above.
>
> The report's header is **`CONDITIONS EVALUATION REPORT`** — plural. Grepping for the singular
> form silently finds nothing.
>
> For the report *without* debug mode's other effects, leave `DEBUG_REPORT=false` and set
> `logging.level.org.springframework.boot.autoconfigure.logging.ConditionEvaluationReportLogger=DEBUG`.

These are plain `environment` entries, so they are baked into a task-definition **revision**:

```bash
# 1. Edit the value in aws/task-definition.json
# 2. Re-register (full export + guard sequence in Part D)
# 3. aws ecs update-service … --force-new-deployment
```

> ⚠️ `LOG_LEVEL_SECURITY=DEBUG` logs the entire Spring Security filter chain **for every request**.
> It is by far the fastest way to spend the 5 GB/month ingestion allowance, and it prints request
> detail. Diagnose with it, then put it back to `INFO`.

---

## Appendix — Every environment variable

Set in `aws/task-definition.json`. Plain values are in `environment`; the rest are `secrets` resolved from Secrets Manager at container start.

| Variable | Source | Value / note |
|---|---|---|
| `SPRING_ACTIVE_PROFILES` | env | `prod` |
| `CONTAINER_PORT` | env | `8080` |
| `MYSQL_HOST` / `MYSQL_PORT` | env | Aiven host and port |
| `MYSQL_DATABASE` | env | **`db3`** |
| `MYSQL_USERNAME` | env | `avnadmin` |
| `MAIL_HOST` / `MAIL_PORT` | env | `smtp.gmail.com` / `587` |
| `UI_APP_URL` | env | the app's public origin, **no trailing slash** — drives email links and the prod CORS default |
| `IMAGE_STORAGE_TYPE` | env | `s3` |
| `AWS_S3_BUCKET` / `AWS_REGION` | env | `tessera-app-images` / `us-east-1` |
| `FORWARD_HEADERS_STRATEGY` | env | `framework` — see C3 |
| `TRUSTED_PROXY_COUNT` | env | `1` — see C3 |
| `MICROSOFT_TENANT_ID` | env | `common` (not sensitive) |
| `LOG_LEVEL_APP` / `LOG_LEVEL_SECURITY` / `LOG_LEVEL_WEB` / `LOG_LEVEL_HIBERNATE` | env | All `INFO`. Raise one to `DEBUG` to diagnose, then put it back — see [H6](#h6-turning-verbosity-up-on-a-deployed-task) |
| `DEBUG_REPORT` | env | `false`. `true` prints the auto-configuration report at startup — see [H6](#h6-turning-verbosity-up-on-a-deployed-task) |
| `LOG_RETENTION_DAYS` | `setup.sh` only | `7`. Not read by the app; consumed by `setup.sh` when it sets CloudWatch retention — see [H5](#h5-free-tier--the-two-things-that-actually-cost) |
| `JWT_SECRET` | secret | `openssl rand -base64 48` |
| `MYSQL_PASSWORD` | secret | Aiven password |
| `MAIL_USERNAME` / `MAIL_PASSWORD` | secret | Gmail address + 16-char app password |
| `TWILIO_ACCOUNT_SID` / `TWILIO_AUTH_TOKEN` / `TWILIO_FROM_NUMBER` | secret | SMS 2FA is a documented stub; placeholders are fine |
| `GOOGLE_CLIENT_ID` / `GOOGLE_CLIENT_SECRET` | secret | omit to hide the Google button |
| `GITHUB_CLIENT_ID` / `GITHUB_CLIENT_SECRET` | secret | omit to hide the GitHub button |
| `MICROSOFT_CLIENT_ID` / `MICROSOFT_CLIENT_SECRET` | secret | omit to hide the Microsoft button |

`IMAGE_STORAGE_PATH` is deliberately absent: its default lives in the **base** `application.yml`, so a missing value does **not** fail fast under `prod` — it silently writes to `~/tesseraapp/images` inside the container, which vanishes on restart. Irrelevant while `IMAGE_STORAGE_TYPE=s3`; set it explicitly if you ever switch back to local storage.

## Related documents

- [`aws/README.md`](README.md) — AWS reference + troubleshooting log of real errors
- [`documentation/security.md` §11.1](../documentation/security.md) — local-vs-AWS parity table for every security control
- [`documentation/configuration.md`](../documentation/configuration.md) — every environment variable, with dev defaults
- [`documentation/database.md`](../documentation/database.md) — schema ownership and the `db3` migration
- [`ROADMAP.md`](../ROADMAP.md) — the single source of truth for planned and deferred work
