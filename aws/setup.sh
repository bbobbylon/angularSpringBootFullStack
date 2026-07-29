#!/usr/bin/env bash
# ──────────────────────────────────────────────────────────────────────────────
# setup.sh — One-time AWS infrastructure bootstrap for TesseraApp.
#
# Run this ONCE from a machine where the AWS CLI is configured with admin-level
# access. It creates every AWS resource needed for ECS Fargate deployment in
# the correct order, using your Aiven cloud MySQL as the database.
#
# Usage:
#   AWS_REGION=us-east-1 ./aws/setup.sh \
#     --domain          app.tessera.example.com \
#     --aiven-host      your-instance.aivencloud.com \
#     --aiven-port      3306 \
#     --aiven-db        tessera \
#     --aiven-user      avnadmin \
#     --s3-bucket       tessera-app-images \
#     --ecr-repo        tessera-app \
#     --cluster         tessera-app-cluster
#
# What this creates (in order):
#   1. IAM execution role  + task role (S3 + Secrets Manager permissions)
#   2. S3 bucket           + bucket policy (public read) + CORS
#   3. ECR repository
#   4. Secrets Manager     (JWT auto-generated; Aiven creds + mail + OAuth stubs)
#   5. ECS cluster (Fargate)
#   6. Security groups     (ALB + app)
#   7. Application Load Balancer + target group + HTTP listener
#   8. task-definition.json filled + registered with ECS
#   9. ECS service created (desired count 1, attached to ALB)
#
# What you still need to do after this script:
#   A. Request an ACM certificate for your domain (requires DNS validation)
#   B. Add an HTTPS listener (443) to the ALB and attach the certificate
#   C. Update the Secrets Manager secrets that have CHANGE_ME values:
#        tessera-app/db-password, mail-username, mail-password,
#        twilio-*, google-*, github-*
#   D. Point your domain's DNS A record to the ALB DNS name (printed at the end)
#   E. Push an initial Docker image:  AWS_REGION=us-east-1 ./aws/push-to-ecr.sh
#
# Dependencies: aws CLI v2, jq (brew install jq / apt install jq)
# ──────────────────────────────────────────────────────────────────────────────
set -euo pipefail

# Git Bash / MSYS on Windows rewrites any bare CLI argument that looks like a POSIX
# path (starts with '/') into a Windows path before exec'ing a native .exe like
# aws.exe — e.g. --health-check-path /actuator/health becomes .../actuator/health
# under your Git install dir. A blanket MSYS_NO_PATHCONV=1 "fixes" that but also
# stops MSYS from translating REAL filesystem paths this script passes to aws.exe
# later (e.g. file:///tmp/tessera-task-definition-filled.json), which then fails
# with "No such file or directory" because aws.exe never gets a resolvable Windows
# path. MSYS2_ARG_CONV_EXCL excludes only the one literal value that must stay a
# URL path, leaving every other argument's normal (correct) translation intact.
# No-op on Linux/macOS, where neither variable means anything.
export MSYS2_ARG_CONV_EXCL="/actuator/health"

# ── Defaults ──────────────────────────────────────────────────────────────────
# Every setting may be supplied as an environment variable OR as a flag; flags win.
#
# The env-var half exists because the sibling scripts (secrets-setup.sh, push-to-ecr.sh)
# are env-var driven, and this one taking flags *only* meant a documented "export these,
# then run ./aws/setup.sh" sequence silently ignored every export and died on the first
# required flag. Accepting both removes that trap rather than relying on the reader to
# notice which script wants which style.
REGION="${AWS_REGION:-us-east-1}"
S3_BUCKET="${S3_BUCKET:-tessera-app-images}"
ECR_REPO="${ECR_REPO:-tessera-app}"
CLUSTER="${ECS_CLUSTER:-tessera-app-cluster}"
SERVICE="${ECS_SERVICE:-tessera-app-service}"
DOMAIN="${APP_DOMAIN:-}"
AIVEN_HOST="${AIVEN_HOST:-}"
AIVEN_PORT="${AIVEN_PORT:-3306}"
# db3 is this application's real Aiven database (see documentation/database.md §17.4). The two
# tempting wrong values both fail confusingly rather than loudly: `tessera` does not exist on the
# Aiven instance at all, and `defaultdb` is the empty database Aiven auto-creates with every
# service — connect to that and the app boots fine, serves the SPA, and rejects every sign-in
# because there are no user rows to authenticate against.
AIVEN_DB="${AIVEN_DB:-db3}"
AIVEN_USER="${AIVEN_USER:-avnadmin}"
AIVEN_PASSWORD="${AIVEN_PASSWORD:-}"

# ── Terminal colours ───────────────────────────────────────────────────────────
GREEN='\033[0;32m'; YELLOW='\033[1;33m'; RED='\033[0;31m'; BLUE='\033[0;34m'; NC='\033[0m'
ok()   { echo -e "${GREEN}  ✓  $1${NC}"; }
info() { echo -e "${BLUE}  →  $1${NC}"; }
warn() { echo -e "${YELLOW}  !  $1${NC}"; }
die()  { echo -e "${RED}  ✗  $1${NC}" >&2; exit 1; }

# ── Argument parsing ───────────────────────────────────────────────────────────
while [[ $# -gt 0 ]]; do
  case "$1" in
    --domain)         DOMAIN="$2";        shift 2 ;;
    --aiven-host)     AIVEN_HOST="$2";    shift 2 ;;
    --aiven-port)     AIVEN_PORT="$2";    shift 2 ;;
    --aiven-db)       AIVEN_DB="$2";      shift 2 ;;
    --aiven-user)     AIVEN_USER="$2";    shift 2 ;;
    --aiven-password) AIVEN_PASSWORD="$2"; shift 2 ;;
    --s3-bucket)      S3_BUCKET="$2";     shift 2 ;;
    --ecr-repo)       ECR_REPO="$2";      shift 2 ;;
    --cluster)        CLUSTER="$2";       shift 2 ;;
    *) die "Unknown argument: $1" ;;
  esac
done

# The script builds "https://${DOMAIN}" itself (S3 CORS origins, APP_DOMAIN for the task
# definition), so a value pasted with its scheme still attached would yield
# "https://https://app.example.com". Strip it rather than fail: pasting the full URL is the
# natural mistake, and it is unambiguous what was meant.
DOMAIN="${DOMAIN#https://}"
DOMAIN="${DOMAIN#http://}"
DOMAIN="${DOMAIN%/}"

[[ -n "$DOMAIN" ]] || die "Domain is required. Pass --domain app.tessera.example.com (hostname
       only, no https://), or export APP_DOMAIN before running."
[[ -n "$AIVEN_HOST" ]] || die "Aiven host is required. Pass --aiven-host <host>.aivencloud.com,
       or export AIVEN_HOST before running."

# Prompt for Aiven password if not passed as argument (avoids it appearing in shell history)
if [[ -z "$AIVEN_PASSWORD" ]]; then
  read -rsp "  Aiven MySQL password for user '${AIVEN_USER}': " AIVEN_PASSWORD
  echo ""
fi

# ── Preflight ──────────────────────────────────────────────────────────────────
command -v aws  >/dev/null || die "aws CLI not installed: https://docs.aws.amazon.com/cli/latest/userguide/install-cliv2.html"
command -v jq   >/dev/null || die "jq not installed: brew install jq  /  apt install jq"

ACCOUNT_ID=$(aws sts get-caller-identity --query Account --output text 2>/dev/null) \
  || die "AWS credentials not configured. Run: aws configure"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

echo ""
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo "  TesseraApp — AWS Infrastructure Setup"
echo "  Account  : ${ACCOUNT_ID}"
echo "  Region   : ${REGION}"
echo "  Domain   : ${DOMAIN}"
echo "  Aiven DB : ${AIVEN_HOST}:${AIVEN_PORT}/${AIVEN_DB}"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo ""

# ── Helper: idempotent resource creation ──────────────────────────────────────
# Returns "existed" or "created" for logging; never fails if the resource exists.
aws_idempotent() {
  # $1 = description, $2 = "create" cmd, $3 = "check existence" cmd
  if eval "$3" >/dev/null 2>&1; then
    echo "existed"
  else
    eval "$2"
    echo "created"
  fi
}

# ── 1. IAM Roles ──────────────────────────────────────────────────────────────
echo "── Step 1/9 — IAM roles ─────────────────────────────────────"

TRUST_DOC='{"Version":"2012-10-17","Statement":[{"Effect":"Allow","Principal":{"Service":"ecs-tasks.amazonaws.com"},"Action":"sts:AssumeRole"}]}'

# Execution role: pulls image from ECR + reads Secrets Manager
if ! aws iam get-role --role-name ecsTaskExecutionRole >/dev/null 2>&1; then
  aws iam create-role --role-name ecsTaskExecutionRole \
    --assume-role-policy-document "$TRUST_DOC" --output text >/dev/null
  aws iam attach-role-policy --role-name ecsTaskExecutionRole \
    --policy-arn arn:aws:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy
  aws iam attach-role-policy --role-name ecsTaskExecutionRole \
    --policy-arn arn:aws:iam::aws:policy/SecretsManagerReadWrite
  ok "Created ecsTaskExecutionRole"
else
  ok "ecsTaskExecutionRole already exists"
fi

# AmazonECSTaskExecutionRolePolicy (attached above) grants logs:CreateLogStream and
# logs:PutLogEvents, but NOT logs:CreateLogGroup — task-definition.json's
# awslogs-create-group:true asks the execution role to create /ecs/tessera-app itself
# on first run, which then fails with AccessDeniedException without this. Runs
# unconditionally (idempotent put-role-policy) so it's applied whether the role above
# was just created or already existed from an earlier, pre-fix run.
aws iam put-role-policy --role-name ecsTaskExecutionRole \
  --policy-name CloudWatchLogsCreateGroup \
  --policy-document "{
    \"Version\":\"2012-10-17\",
    \"Statement\":[{
      \"Effect\":\"Allow\",
      \"Action\":[\"logs:CreateLogGroup\"],
      \"Resource\":\"arn:aws:logs:${REGION}:*:log-group:/ecs/tessera-app:*\"
    }]
  }"

# Task role: what the running container itself can do (S3 image storage)
if ! aws iam get-role --role-name tessera-app-task-role >/dev/null 2>&1; then
  aws iam create-role --role-name tessera-app-task-role \
    --assume-role-policy-document "$TRUST_DOC" --output text >/dev/null
  aws iam put-role-policy --role-name tessera-app-task-role \
    --policy-name S3ImageStorage \
    --policy-document "{
      \"Version\":\"2012-10-17\",
      \"Statement\":[{
        \"Effect\":\"Allow\",
        \"Action\":[\"s3:PutObject\",\"s3:GetObject\",\"s3:DeleteObject\"],
        \"Resource\":\"arn:aws:s3:::${S3_BUCKET}/*\"
      }]
    }"
  ok "Created tessera-app-task-role with S3 policy"
else
  ok "tessera-app-task-role already exists"
fi

# ── 2. S3 bucket ──────────────────────────────────────────────────────────────
echo ""
echo "── Step 2/9 — S3 bucket ─────────────────────────────────────"

if ! aws s3api head-bucket --bucket "$S3_BUCKET" --region "$REGION" 2>/dev/null; then
  if [[ "$REGION" == "us-east-1" ]]; then
    aws s3 mb "s3://${S3_BUCKET}" --region "$REGION" >/dev/null
  else
    aws s3 mb "s3://${S3_BUCKET}" --region "$REGION" \
      --create-bucket-configuration LocationConstraint="$REGION" >/dev/null
  fi
  ok "Created S3 bucket: ${S3_BUCKET}"
else
  ok "S3 bucket ${S3_BUCKET} already exists"
fi

# Buckets created via `aws s3 mb` have S3 Block Public Access enabled by default
# (AWS default since April 2023) regardless of account-level settings, which rejects
# the public-read policy below with AccessDenied on PutBucketPolicy. Scoped to just
# this bucket — not an account-wide relaxation — because this bucket is deliberately
# public-read for profile images (see app.image.storage-type: s3 in application.yml).
aws s3api put-public-access-block --bucket "$S3_BUCKET" --public-access-block-configuration \
  BlockPublicAcls=false,IgnorePublicAcls=false,BlockPublicPolicy=false,RestrictPublicBuckets=false
ok "Disabled Block Public Access on ${S3_BUCKET} (scoped to this bucket)"

# Allow public read for profile images
aws s3api put-bucket-policy --bucket "$S3_BUCKET" --policy "{
  \"Version\":\"2012-10-17\",
  \"Statement\":[{
    \"Effect\":\"Allow\",
    \"Principal\":\"*\",
    \"Action\":\"s3:GetObject\",
    \"Resource\":\"arn:aws:s3:::${S3_BUCKET}/*\"
  }]
}" >/dev/null
ok "Applied public-read bucket policy"

# CORS for cross-origin image loads from the Angular app
aws s3api put-bucket-cors --bucket "$S3_BUCKET" --cors-configuration "{
  \"CORSRules\":[{
    \"AllowedOrigins\":[\"https://${DOMAIN}\",\"http://localhost:4200\"],
    \"AllowedMethods\":[\"GET\"],
    \"AllowedHeaders\":[\"*\"],
    \"MaxAgeSeconds\":3600
  }]
}" >/dev/null
ok "Applied CORS policy (allows GET from https://${DOMAIN} and localhost:4200)"

# ── 3. ECR repository ─────────────────────────────────────────────────────────
echo ""
echo "── Step 3/9 — ECR repository ────────────────────────────────"

if ! aws ecr describe-repositories --repository-names "$ECR_REPO" --region "$REGION" >/dev/null 2>&1; then
  aws ecr create-repository --repository-name "$ECR_REPO" --region "$REGION" >/dev/null
  ok "Created ECR repository: ${ECR_REPO}"
else
  ok "ECR repository ${ECR_REPO} already exists"
fi
ECR_URI="${ACCOUNT_ID}.dkr.ecr.${REGION}.amazonaws.com/${ECR_REPO}"

# ── 4. Secrets Manager ────────────────────────────────────────────────────────
echo ""
echo "── Step 4/9 — Secrets Manager ───────────────────────────────"

PREFIX="tessera-app"
JWT_SECRET=$(openssl rand -base64 48)

create_secret() {
  local name="$1" value="$2"
  if aws secretsmanager describe-secret --secret-id "${PREFIX}/${name}" \
       --region "$REGION" >/dev/null 2>&1; then
    ok "  ${PREFIX}/${name} already exists (skipped)"
  else
    aws secretsmanager create-secret \
      --name "${PREFIX}/${name}" \
      --secret-string "${value}" \
      --region "$REGION" \
      --output text >/dev/null
    ok "  Created ${PREFIX}/${name}"
  fi
}

create_secret "jwt-secret"            "${JWT_SECRET}"
create_secret "db-password"           "${AIVEN_PASSWORD}"
create_secret "mail-username"         "CHANGE_ME@gmail.com"
create_secret "mail-password"         "CHANGE_ME_16char_app_password"
create_secret "twilio-sid"            "CHANGE_ME_ACxxxxxxx"
create_secret "twilio-token"          "CHANGE_ME_twilio_auth_token"
create_secret "twilio-from-number"    "+10000000000"
create_secret "google-client-id"      "CHANGE_ME.apps.googleusercontent.com"
create_secret "google-client-secret"  "CHANGE_ME"
create_secret "github-client-id"      "CHANGE_ME"
create_secret "github-client-secret"  "CHANGE_ME"

warn "Update every CHANGE_ME secret before your first deployment:"
warn "  aws secretsmanager update-secret --region ${REGION} --secret-id ${PREFIX}/mail-username --secret-string 'you@gmail.com'"

# ── 5. ECS cluster ────────────────────────────────────────────────────────────
echo ""
echo "── Step 5/9 — ECS cluster ───────────────────────────────────"

if ! aws ecs describe-clusters --clusters "$CLUSTER" --region "$REGION" \
     --query 'clusters[?status==`ACTIVE`].clusterName' --output text | grep -q "$CLUSTER"; then
  aws ecs create-cluster --cluster-name "$CLUSTER" --region "$REGION" >/dev/null
  ok "Created ECS cluster: ${CLUSTER}"
else
  ok "ECS cluster ${CLUSTER} already exists"
fi

# ── 6. Security groups ────────────────────────────────────────────────────────
echo ""
echo "── Step 6/9 — VPC + Security groups ─────────────────────────"

# Use the default VPC
VPC_ID=$(aws ec2 describe-vpcs --filters "Name=isDefault,Values=true" \
  --query 'Vpcs[0].VpcId' --output text --region "$REGION")
[[ "$VPC_ID" == "None" || -z "$VPC_ID" ]] && die "No default VPC found in ${REGION}. Create one or specify subnets manually."
ok "Using default VPC: ${VPC_ID}"

# Get two subnets from the default VPC (across different AZs for high-availability)
SUBNETS=$(aws ec2 describe-subnets \
  --filters "Name=vpc-id,Values=${VPC_ID}" "Name=defaultForAz,Values=true" \
  --query 'Subnets[*].SubnetId' --output text --region "$REGION")
SUBNET_1=$(echo "$SUBNETS" | awk '{print $1}')
SUBNET_2=$(echo "$SUBNETS" | awk '{print $2}')
[[ -z "$SUBNET_1" ]] && die "Could not find default subnets in VPC ${VPC_ID}"
ok "Subnets: ${SUBNET_1}, ${SUBNET_2}"

# ALB security group — public-facing (80, 443)
ALB_SG=$(aws ec2 describe-security-groups \
  --filters "Name=group-name,Values=tessera-alb-sg" "Name=vpc-id,Values=${VPC_ID}" \
  --query 'SecurityGroups[0].GroupId' --output text --region "$REGION" 2>/dev/null || echo "None")

if [[ "$ALB_SG" == "None" || -z "$ALB_SG" ]]; then
  ALB_SG=$(aws ec2 create-security-group \
    --group-name tessera-alb-sg \
    --description "TesseraApp ALB - inbound HTTP/HTTPS" \
    --vpc-id "$VPC_ID" \
    --region "$REGION" \
    --query GroupId --output text)
  aws ec2 authorize-security-group-ingress --group-id "$ALB_SG" --region "$REGION" \
    --ip-permissions \
      '[{"IpProtocol":"tcp","FromPort":80,"ToPort":80,"IpRanges":[{"CidrIp":"0.0.0.0/0"}]},
        {"IpProtocol":"tcp","FromPort":443,"ToPort":443,"IpRanges":[{"CidrIp":"0.0.0.0/0"}]}]' >/dev/null
  ok "Created ALB security group: ${ALB_SG}"
else
  ok "ALB security group already exists: ${ALB_SG}"
fi

# App security group — accepts traffic only from the ALB SG on port 8080
APP_SG=$(aws ec2 describe-security-groups \
  --filters "Name=group-name,Values=tessera-app-sg" "Name=vpc-id,Values=${VPC_ID}" \
  --query 'SecurityGroups[0].GroupId' --output text --region "$REGION" 2>/dev/null || echo "None")

if [[ "$APP_SG" == "None" || -z "$APP_SG" ]]; then
  APP_SG=$(aws ec2 create-security-group \
    --group-name tessera-app-sg \
    --description "TesseraApp container - inbound from ALB only" \
    --vpc-id "$VPC_ID" \
    --region "$REGION" \
    --query GroupId --output text)
  aws ec2 authorize-security-group-ingress --group-id "$APP_SG" --region "$REGION" \
    --protocol tcp --port 8080 --source-group "$ALB_SG" >/dev/null
  ok "Created app security group: ${APP_SG}"
else
  ok "App security group already exists: ${APP_SG}"
fi

# ── 7. ALB + target group + HTTP listener ─────────────────────────────────────
echo ""
echo "── Step 7/9 — Application Load Balancer ─────────────────────"

ALB_ARN=$(aws elbv2 describe-load-balancers --names tessera-app-alb \
  --query 'LoadBalancers[0].LoadBalancerArn' --output text --region "$REGION" 2>/dev/null || echo "None")

if [[ "$ALB_ARN" == "None" || -z "$ALB_ARN" ]]; then
  ALB_ARN=$(aws elbv2 create-load-balancer \
    --name tessera-app-alb \
    --type application \
    --subnets "$SUBNET_1" "$SUBNET_2" \
    --security-groups "$ALB_SG" \
    --region "$REGION" \
    --query 'LoadBalancers[0].LoadBalancerArn' --output text)
  ok "Created ALB: tessera-app-alb"
else
  ok "ALB tessera-app-alb already exists"
fi

ALB_DNS=$(aws elbv2 describe-load-balancers \
  --load-balancer-arns "$ALB_ARN" \
  --query 'LoadBalancers[0].DNSName' --output text --region "$REGION")

TG_ARN=$(aws elbv2 describe-target-groups --names tessera-app-tg \
  --query 'TargetGroups[0].TargetGroupArn' --output text --region "$REGION" 2>/dev/null || echo "None")

if [[ "$TG_ARN" == "None" || -z "$TG_ARN" ]]; then
  TG_ARN=$(aws elbv2 create-target-group \
    --name tessera-app-tg \
    --protocol HTTP \
    --port 8080 \
    --vpc-id "$VPC_ID" \
    --target-type ip \
    --health-check-path /actuator/health \
    --health-check-interval-seconds 30 \
    --healthy-threshold-count 2 \
    --unhealthy-threshold-count 3 \
    --region "$REGION" \
    --query 'TargetGroups[0].TargetGroupArn' --output text)
  ok "Created target group: tessera-app-tg → port 8080 via /actuator/health"
else
  ok "Target group tessera-app-tg already exists"
fi

# HTTP listener — forwards to the target group (HTTPS added manually after cert)
LISTENER_ARN=$(aws elbv2 describe-listeners \
  --load-balancer-arn "$ALB_ARN" \
  --query 'Listeners[?Port==`80`].ListenerArn' --output text --region "$REGION" 2>/dev/null)
if [[ -z "$LISTENER_ARN" ]]; then
  aws elbv2 create-listener \
    --load-balancer-arn "$ALB_ARN" \
    --protocol HTTP \
    --port 80 \
    --default-actions "Type=forward,TargetGroupArn=${TG_ARN}" \
    --region "$REGION" >/dev/null
  ok "Created HTTP listener (port 80 → tessera-app-tg)"
else
  ok "HTTP listener already exists"
fi

# ── 8. Fill task-definition.json and register ─────────────────────────────────
echo ""
echo "── Step 8/9 — Task definition ───────────────────────────────"

TASK_DEF_TEMPLATE="$SCRIPT_DIR/task-definition.json"
TASK_DEF_FILLED="/tmp/tessera-task-definition-filled.json"

export AWS_ACCOUNT_ID="$ACCOUNT_ID"
export AWS_REGION="$REGION"
export AIVEN_HOST="$AIVEN_HOST"
export AIVEN_PORT="$AIVEN_PORT"
export AIVEN_DB="$AIVEN_DB"
export AIVEN_USER="$AIVEN_USER"
export S3_BUCKET="$S3_BUCKET"
# http:// here, not https:// — Step D (ACM cert + HTTPS listener) is a manual
# step documented to run AFTER this script, so at the moment this task definition
# is registered, HTTPS never actually exists yet regardless of which domain was
# passed in. Baking https:// here would put a dead-end scheme into every
# verification/reset-password email link until someone manually re-registers a
# corrected revision. Once HTTPS is really set up, update the APP_DOMAIN GitHub
# Secret to https://your-domain — deploy.yml passes that value through as-is,
# unlike this script, so it doesn't need a matching fix.
export APP_DOMAIN="http://${DOMAIN}"
export ECR_IMAGE_URI="${ECR_URI}:latest"

# Resolve each secret's COMPLETE ARN (random suffix included) dynamically rather than
# hand-building or guessing at it. ECS's `valueFrom` needs the full ARN — a bare name
# or a hand-built ARN missing the suffix is ambiguous and ECS defaults to treating it
# as an SSM Parameter Store lookup instead of Secrets Manager, which then fails with
# AccessDeniedException on ssm:GetParameters (see Troubleshooting in aws/README.md).
for s in JWT_SECRET:jwt-secret DB_PASSWORD:db-password MAIL_USERNAME:mail-username \
         MAIL_PASSWORD:mail-password TWILIO_SID:twilio-sid TWILIO_TOKEN:twilio-token \
         TWILIO_FROM_NUMBER:twilio-from-number GOOGLE_CLIENT_ID:google-client-id \
         GOOGLE_CLIENT_SECRET:google-client-secret GITHUB_CLIENT_ID:github-client-id \
         GITHUB_CLIENT_SECRET:github-client-secret; do
  var_name="${s%%:*}_ARN"
  secret_name="${s##*:}"
  arn=$(aws secretsmanager describe-secret --secret-id "tessera-app/${secret_name}" \
          --query ARN --output text --region "$REGION")
  export "${var_name}=${arn}"
done

# Use envsubst to substitute ${VAR} placeholders in the task definition template,
# then strip the _comment/_variables documentation keys — register-task-definition
# validates strictly and rejects any key outside its known schema.
envsubst < "$TASK_DEF_TEMPLATE" | jq 'del(._comment, ._variables)' > "$TASK_DEF_FILLED"

# Pass the JSON content directly rather than a file:// URI. Bash resolves
# /tmp/... itself just fine (its own MSYS filesystem emulation on Windows) — it's
# only handing that path as a bare STRING to aws.exe (a native, non-MSYS binary)
# that's unreliable across shells. Inlining the content sidesteps path translation
# entirely and behaves identically on Linux/macOS/Windows.
TASK_DEF_ARN=$(aws ecs register-task-definition \
  --cli-input-json "$(cat "$TASK_DEF_FILLED")" \
  --region "$REGION" \
  --query 'taskDefinition.taskDefinitionArn' --output text)
ok "Registered task definition: ${TASK_DEF_ARN}"

# ── 9. ECS service ────────────────────────────────────────────────────────────
echo ""
echo "── Step 9/9 — ECS service ───────────────────────────────────"

SERVICE_EXISTS=$(aws ecs describe-services --cluster "$CLUSTER" --services "$SERVICE" \
  --query 'services[?status==`ACTIVE`].serviceName' --output text --region "$REGION" 2>/dev/null)

# 300s, not 90s — this app's actual cold start (JVM + full Spring context + Hibernate + JPA +
# S3 client) consistently takes ~80-85s on this task's 512 CPU / 1024 MB allocation, leaving
# almost no buffer for the ALB to then accumulate enough consecutive passing health checks to
# flip to "healthy" before the grace period expires — ECS was killing tasks that were actually
# fine, just still converging (see Troubleshooting in aws/README.md).
if [[ -z "$SERVICE_EXISTS" ]]; then
  aws ecs create-service \
    --cluster "$CLUSTER" \
    --service-name "$SERVICE" \
    --task-definition "tessera-app" \
    --desired-count 1 \
    --launch-type FARGATE \
    --network-configuration "awsvpcConfiguration={subnets=[${SUBNET_1},${SUBNET_2}],securityGroups=[${APP_SG}],assignPublicIp=ENABLED}" \
    --load-balancers "targetGroupArn=${TG_ARN},containerName=tessera-app,containerPort=8080" \
    --health-check-grace-period-seconds 300 \
    --region "$REGION" \
    --output text >/dev/null
  ok "Created ECS service: ${SERVICE} (desired count: 1)"
else
  ok "ECS service ${SERVICE} already exists"
fi

# ── Done — next steps ─────────────────────────────────────────────────────────
echo ""
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo -e "${GREEN}  ✓  Infrastructure ready!${NC}"
echo ""
echo "  ALB DNS:  ${ALB_DNS}"
echo "  ECR repo: ${ECR_URI}"
echo ""
echo "  NEXT STEPS (manual — 4 remaining):"
echo ""
echo "  A. Apply schema.sql to your Aiven database:"
echo "       mysql -h ${AIVEN_HOST} -P ${AIVEN_PORT} -u ${AIVEN_USER} -p ${AIVEN_DB} \\"
echo "         < $(cd "$REPO_ROOT" && pwd)/src/main/resources/schema.sql"
echo ""
echo "  B. Update CHANGE_ME secrets in Secrets Manager:"
echo "       aws secretsmanager update-secret --region ${REGION} \\"
echo "         --secret-id tessera-app/mail-username --secret-string 'you@gmail.com'"
echo "       # Repeat for mail-password, twilio-*, google-*, github-*"
echo ""
echo "  C. Push the initial Docker image:"
echo "       AWS_REGION=${REGION} ./aws/push-to-ecr.sh latest"
echo ""
echo "  D. Set up HTTPS:"
echo "     1. Request cert: AWS Console → Certificate Manager → Request → DNS validation"
echo "        Domain: ${DOMAIN}"
echo "     2. After validation: ALB → Listeners → Add HTTPS (443) → attach cert"
echo "     3. Add HTTP→HTTPS redirect rule on port 80"
echo "     4. Point your DNS A record to: ${ALB_DNS}"
echo ""
echo "  E. Add GitHub Secrets for the deploy pipeline (Settings → Secrets → Actions):"
echo "       AWS_ACCESS_KEY_ID, AWS_SECRET_ACCESS_KEY"
echo "       AWS_ACCOUNT_ID=${ACCOUNT_ID}"
echo "       AWS_REGION=${REGION}"
echo "       ECR_REPOSITORY=${ECR_REPO}"
echo "       ECS_CLUSTER=${CLUSTER}"
echo "       ECS_SERVICE=${SERVICE}"
echo "       AIVEN_HOST=${AIVEN_HOST}"
echo "       AIVEN_PORT=${AIVEN_PORT}"
echo "       AIVEN_DB=${AIVEN_DB}"
echo "       AIVEN_USER=${AIVEN_USER}"
echo "       S3_BUCKET=${S3_BUCKET}"
echo "       APP_DOMAIN=http://${DOMAIN}   (switch to https:// once Step D is actually done)"
echo ""
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
