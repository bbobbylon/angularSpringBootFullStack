#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# deploy.sh — one-command local deployment.
#
# What it does:
#   1. Verifies docker + docker compose are installed
#   2. Verifies .env exists (or creates it from .env.example with a warning)
#   3. Builds the multi-stage Docker image (Angular → Spring JAR → JRE runtime)
#   4. Starts the stack (app + mysql + adminer)
#   5. Waits for the app container to report healthy via Docker healthcheck
#   6. Prints connection info and a hint to view logs
#
# Usage:
#   ./deploy.sh           # build + start
#   ./deploy.sh --logs    # build + start, then tail logs
#   ./deploy.sh --clean   # wipe the DB volume first, then build + start
#   ./deploy.sh --down    # stop everything (volume preserved)
#
# Cloud subcommands (push the local image to ECR / ACR):
#   ./deploy.sh --aws-push    # build, tag, push to ECR (uses AWS CLI + .env.cloud)
#   ./deploy.sh --azure-push  # build, tag, push to ACR (uses Azure CLI + .env.cloud)
#
# .env.cloud is a separate, gitignored file. See .env.cloud.example for the schema.
# ─────────────────────────────────────────────────────────────────────────────
set -euo pipefail

# Resolve the script's own directory so it runs correctly from anywhere.
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

# ANSI colors — fall back to no-color if not a terminal.
if [[ -t 1 ]]; then
  C_INFO=$'\e[36m'; C_OK=$'\e[32m'; C_WARN=$'\e[33m'; C_ERR=$'\e[31m'; C_RESET=$'\e[0m'
else
  C_INFO=''; C_OK=''; C_WARN=''; C_ERR=''; C_RESET=''
fi
log()  { printf '%s==>%s %s\n' "$C_INFO" "$C_RESET" "$*"; }
ok()   { printf '%s✓%s   %s\n' "$C_OK"   "$C_RESET" "$*"; }
warn() { printf '%s!%s   %s\n' "$C_WARN" "$C_RESET" "$*" >&2; }
die()  { printf '%s✗%s   %s\n' "$C_ERR"  "$C_RESET" "$*" >&2; exit 1; }

# ── Parse args ───────────────────────────────────────────────────────────────
MODE="up"; TAIL_LOGS=0; CLEAN=0
for arg in "$@"; do
  case "$arg" in
    --logs)        TAIL_LOGS=1 ;;
    --clean)       CLEAN=1 ;;
    --down)        MODE="down" ;;
    --aws-push)    MODE="aws-push" ;;
    --azure-push)  MODE="azure-push" ;;
    -h|--help)
      sed -n '2,24p' "$0"; exit 0 ;;
    *) die "Unknown flag: $arg (try --help)" ;;
  esac
done

# ── Helper: load .env.cloud and require named vars ───────────────────────────
load_cloud_env() {
  if [[ ! -f .env.cloud ]]; then
    die ".env.cloud not found. Copy .env.cloud.example to .env.cloud and fill in your registry details."
  fi
  set -a
  # shellcheck disable=SC1091
  source .env.cloud
  set +a
  for var in "$@"; do
    if [[ -z "${!var:-}" ]]; then
      die "Missing required env var '$var' in .env.cloud"
    fi
  done
}

# ── Prerequisite checks ──────────────────────────────────────────────────────
log "Checking prerequisites"
command -v docker >/dev/null 2>&1 || die "docker not found. Install Docker Desktop (or docker engine)."
# Compose v2 is `docker compose` (subcommand); v1 was `docker-compose` (separate binary).
# We require v2 because docker-compose v1 is deprecated and lacks healthcheck gating.
docker compose version >/dev/null 2>&1 || die "docker compose v2 not found. Update Docker Desktop or install the compose plugin."
docker info >/dev/null 2>&1 || die "Docker daemon not reachable. Is Docker Desktop running?"
ok "docker + compose available"

# ── Tear-down mode short-circuits everything else ────────────────────────────
if [[ "$MODE" == "down" ]]; then
  log "Stopping stack (volume preserved)"
  docker compose down
  ok "Stack stopped. Volume 'securecapita-mysql-data' kept — restart with ./deploy.sh"
  exit 0
fi

# ── AWS push ─────────────────────────────────────────────────────────────────
if [[ "$MODE" == "aws-push" ]]; then
  log "Pushing image to Amazon ECR"
  command -v aws >/dev/null 2>&1 || die "aws CLI not found. Install it: https://aws.amazon.com/cli/"
  load_cloud_env AWS_REGION AWS_ACCOUNT_ID AWS_ECR_REPOSITORY

  ECR_URI="${AWS_ACCOUNT_ID}.dkr.ecr.${AWS_REGION}.amazonaws.com/${AWS_ECR_REPOSITORY}"
  TAG="${IMAGE_TAG:-$(git rev-parse --short HEAD 2>/dev/null || date +%Y%m%d-%H%M%S)}"

  log "Logging Docker in to ECR ($AWS_REGION)"
  aws ecr get-login-password --region "$AWS_REGION" \
    | docker login --username AWS --password-stdin "${ECR_URI%/*}"

  log "Building image"
  docker build -t "${ECR_URI}:${TAG}" -t "${ECR_URI}:latest" .

  log "Pushing ${ECR_URI}:${TAG}"
  docker push "${ECR_URI}:${TAG}"
  docker push "${ECR_URI}:latest"

  ok "Pushed ${ECR_URI}:${TAG}"
  ok "App Runner will auto-pull if AutoDeploymentsEnabled=true (see CloudFormation stack)."
  exit 0
fi

# ── Azure push ───────────────────────────────────────────────────────────────
if [[ "$MODE" == "azure-push" ]]; then
  log "Pushing image to Azure Container Registry"
  command -v az >/dev/null 2>&1 || die "az CLI not found. Install it: https://learn.microsoft.com/cli/azure/install-azure-cli"
  load_cloud_env AZURE_ACR_NAME AZURE_RESOURCE_GROUP

  ACR_LOGIN_SERVER="${AZURE_ACR_NAME}.azurecr.io"
  TAG="${IMAGE_TAG:-$(git rev-parse --short HEAD 2>/dev/null || date +%Y%m%d-%H%M%S)}"

  log "Logging Docker in to ACR"
  az acr login --name "$AZURE_ACR_NAME"

  log "Building image"
  docker build -t "${ACR_LOGIN_SERVER}/securecapita:${TAG}" -t "${ACR_LOGIN_SERVER}/securecapita:latest" .

  log "Pushing ${ACR_LOGIN_SERVER}/securecapita:${TAG}"
  docker push "${ACR_LOGIN_SERVER}/securecapita:${TAG}"
  docker push "${ACR_LOGIN_SERVER}/securecapita:latest"

  if [[ -n "${AZURE_APP_SERVICE_NAME:-}" ]]; then
    log "Restarting App Service '$AZURE_APP_SERVICE_NAME' to pull the new image"
    az webapp restart --name "$AZURE_APP_SERVICE_NAME" --resource-group "$AZURE_RESOURCE_GROUP"
    ok "App Service restart issued. New container booting up."
  else
    ok "Image pushed. Set AZURE_APP_SERVICE_NAME in .env.cloud to auto-restart on push."
  fi
  exit 0
fi

# ── .env handling ────────────────────────────────────────────────────────────
if [[ ! -f .env ]]; then
  if [[ -f .env.example ]]; then
    warn ".env not found — copying from .env.example."
    warn "EDIT .env and change MYSQL_ROOT_PASSWORD and JWT_SECRET before exposing this to anything real."
    cp .env.example .env
  else
    die "Neither .env nor .env.example present. Aborting."
  fi
fi

# Refuse to deploy with placeholder secrets — easy mistake, painful in prod.
if grep -qE '^(MYSQL_ROOT_PASSWORD=change-me|JWT_SECRET=replace-with)' .env; then
  warn "Your .env still has placeholder values from .env.example."
  warn "Local dev will work, but DO NOT use these defaults outside your machine."
fi
ok ".env present"

# ── Optional clean ───────────────────────────────────────────────────────────
if [[ "$CLEAN" -eq 1 ]]; then
  log "Wiping previous stack + DB volume (--clean)"
  docker compose down -v
  ok "Volume wiped — fresh schema will be applied on next start"
fi

# ── Build ────────────────────────────────────────────────────────────────────
log "Building images (this is the slow step — Angular + Maven dependency resolution)"
docker compose build
ok "Images built"

# ── Start ────────────────────────────────────────────────────────────────────
log "Starting stack in detached mode"
docker compose up -d
ok "Containers running"

# ── Health wait ──────────────────────────────────────────────────────────────
# We poll docker inspect for the healthcheck status declared in the Dockerfile.
# This avoids guessing how long Spring takes to boot — it tells us when it's ready.
log "Waiting for app to report healthy (up to 3 minutes)..."
APP_CONTAINER="securecapita-app"
DEADLINE=$(( $(date +%s) + 180 ))
LAST_STATUS=""
while [[ $(date +%s) -lt $DEADLINE ]]; do
  STATUS=$(docker inspect -f '{{.State.Health.Status}}' "$APP_CONTAINER" 2>/dev/null || echo "unknown")
  if [[ "$STATUS" != "$LAST_STATUS" ]]; then
    printf '   status: %s\n' "$STATUS"
    LAST_STATUS="$STATUS"
  fi
  case "$STATUS" in
    healthy) break ;;
    unhealthy)
      warn "Container reported unhealthy. Recent logs:"
      docker compose logs --tail=80 app >&2
      die "App failed to become healthy."
      ;;
  esac
  sleep 3
done

if [[ "$LAST_STATUS" != "healthy" ]]; then
  warn "Timed out waiting for healthy status. Recent logs:"
  docker compose logs --tail=80 app >&2
  die "Aborting — check logs above."
fi
ok "App is healthy"

# ── Done ─────────────────────────────────────────────────────────────────────
cat <<EOF

${C_OK}Deployment complete.${C_RESET}

  ${C_INFO}App${C_RESET}      http://localhost:8080
  ${C_INFO}Health${C_RESET}   http://localhost:8080/actuator/health
  ${C_INFO}Adminer${C_RESET}  http://localhost:8081   (server: mysql, user: root, db: db2)

Useful commands:
  docker compose logs -f app     # follow app logs
  docker compose logs -f mysql   # follow db logs
  docker compose ps              # see service status
  ./deploy.sh --down             # stop stack
  ./deploy.sh --clean            # wipe DB volume and rebuild
EOF

if [[ "$TAIL_LOGS" -eq 1 ]]; then
  log "Tailing app logs (Ctrl-C to detach — containers keep running)"
  docker compose logs -f app
fi
