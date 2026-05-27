#!/usr/bin/env bash

# ═══════════════════════════════════════════════════════════════════
#  start.sh — unified startup script
#
#  Change ENV below to switch modes:
#
#    local  — Spring Boot + Angular run natively with hot-reload.
#             Access the app at: http://localhost:4200
#
#    docker — Full Docker Compose build. Angular is compiled into the
#             Spring Boot JAR (no hot-reload). Closest to production.
#             Access the app at: http://localhost:8090  (or APP_PORT in .env)
#
#  DB controls which database local mode connects to:
#
#    aiven — Use Aiven cloud MySQL (no local Docker container needed).
#            Requires Aiven credentials in .env SPRING_DATASOURCE_* vars.
#
#    local — Start a MySQL Docker container and connect to it.
#
#  Usage:
#    chmod +x start.sh
#    ./start.sh
# ═══════════════════════════════════════════════════════════════════
ENV=local
DB=aiven   # aiven | local

# ── Internal config ────────────────────────────────────────────────
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ENV_FILE="$SCRIPT_DIR/.env"
ANGULAR_DIR="$SCRIPT_DIR/securecapitaapp"
# Angular services hardcode localhost:8080 — Spring Boot must listen here in local mode
LOCAL_BACKEND_PORT=8080

# ── Terminal colors ────────────────────────────────────────────────
GREEN='\033[0;32m'; YELLOW='\033[1;33m'; BLUE='\033[0;34m'; RED='\033[0;31m'; NC='\033[0m'
log()  { echo -e "${GREEN}[start.sh]${NC} $1"; }
warn() { echo -e "${YELLOW}[start.sh]${NC} $1"; }
err()  { echo -e "${RED}[start.sh]${NC} $1" >&2; exit 1; }

# ═══════════════════════════════════════════════════════════════════
#  LOCAL MODE
#  1. Load .env
#  2. If DB=local: start MySQL Docker container and wait for health
#     If DB=aiven: skip Docker MySQL — connect directly to Aiven cloud
#  3. Start Spring Boot via Maven in background
#  4. Start Angular dev server in background
#  5. Ctrl+C tears everything down cleanly
# ═══════════════════════════════════════════════════════════════════
start_local() {
  [[ -f "$ENV_FILE" ]] || err ".env not found at $ENV_FILE — cannot start."

  log "Loading environment variables from .env..."
  set -a
  # shellcheck source=.env
  source "$ENV_FILE"
  set +a

  # Angular's HTTP services point to localhost:8080 — override whatever .env says
  export CONTAINER_PORT=$LOCAL_BACKEND_PORT

  # ── Database ─────────────────────────────────────────────────────
  if [[ "$DB" == "local" ]]; then
    log "Starting local MySQL container..."
    docker compose up -d mysql

    log "Waiting for MySQL to be healthy..."
    WAIT_SECONDS=0
    until docker compose ps mysql 2>/dev/null | grep -q "healthy"; do
      printf '.'
      sleep 2
      WAIT_SECONDS=$((WAIT_SECONDS + 2))
      if [[ $WAIT_SECONDS -ge 60 ]]; then
        echo ""
        err "MySQL did not become healthy within 60 seconds. Check: docker compose logs mysql"
      fi
    done
    echo ""
    log "MySQL is ready."
  else
    log "DB=aiven — overriding datasource to Aiven cloud MySQL (skipping local Docker container)."
    export SPRING_DATASOURCE_URL="jdbc:mysql://${AIVEN_DB_HOST}:${AIVEN_DB_PORT}/${AIVEN_DB_NAME}?useSSL=true&requireSSL=true"
    export SPRING_DATASOURCE_USERNAME="${AIVEN_DB_USERNAME}"
    export SPRING_DATASOURCE_PASSWORD="${AIVEN_DB_PASSWORD}"
  fi

  # ── Angular ──────────────────────────────────────────────────────
  if [[ ! -d "$ANGULAR_DIR/node_modules" ]]; then
    warn "node_modules not found — running npm install first..."
    cd "$ANGULAR_DIR" && npm install && cd "$SCRIPT_DIR"
  fi

  log "Starting Angular dev server on port 4200..."
  cd "$ANGULAR_DIR"
  npm run start &
  ANGULAR_PID=$!
  cd "$SCRIPT_DIR"

  # ── Spring Boot ──────────────────────────────────────────────────
  log "Starting Spring Boot on port $LOCAL_BACKEND_PORT (dev profile)..."
  cd "$SCRIPT_DIR"
  mvn spring-boot:run --no-transfer-progress &
  SPRING_PID=$!

  # ── Ready banner ─────────────────────────────────────────────────
  log "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
  log "  Frontend : ${BLUE}http://localhost:4200${NC}"
  log "  Backend  : ${BLUE}http://localhost:$LOCAL_BACKEND_PORT${NC}"
  if [[ "$DB" == "aiven" ]]; then
    log "  Database : ${BLUE}Aiven cloud MySQL${NC}"
  else
    log "  Database : ${BLUE}Local Docker MySQL${NC}"
  fi
  log "  Press Ctrl+C to stop all services."
  log "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"

  # ── Cleanup ──────────────────────────────────────────────────────
  cleanup() {
    echo ""
    warn "Shutting down Spring Boot and Angular..."
    kill "$SPRING_PID" 2>/dev/null || true
    kill "$ANGULAR_PID" 2>/dev/null || true
    if [[ "$DB" == "local" ]]; then
      docker compose stop mysql
    fi
    log "All services stopped."
    exit 0
  }
  trap cleanup INT TERM

  # Wait until one of the background processes exits, then clean up
  wait "$SPRING_PID" "$ANGULAR_PID"
}

# ═══════════════════════════════════════════════════════════════════
#  DOCKER MODE
#  Full Docker Compose — builds the multi-stage image (Angular compiled
#  into the Spring Boot JAR), starts MySQL, waits for health, starts app.
#  Access: http://localhost:${APP_PORT:-8090}
# ═══════════════════════════════════════════════════════════════════
start_docker() {
  [[ -f "$ENV_FILE" ]] || err ".env not found at $ENV_FILE — cannot start."
  log "Building and starting full Docker Compose stack..."
  log "(Angular will be compiled into the Spring Boot JAR — this may take a few minutes)"
  cd "$SCRIPT_DIR"
  docker compose up --build
}

# ═══════════════════════════════════════════════════════════════════
#  DISPATCH
# ═══════════════════════════════════════════════════════════════════
cd "$SCRIPT_DIR"

case "$ENV" in
  local)  start_local  ;;
  docker) start_docker ;;
  *)      err "Unknown ENV='$ENV'. Valid values: local | docker" ;;
esac
