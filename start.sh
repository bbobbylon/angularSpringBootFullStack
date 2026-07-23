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
#    native — Use the native MySQL already installed on the host (Windows MySQL80,
#             brew mysql, etc.) listening on ${MYSQL_HOST}:${MYSQL_PORT}. Does NOT
#             start any Docker container. This is the DEFAULT so that a stopped
#             native server is never silently shadowed by an empty Docker MySQL on
#             the same port 3306 — the bug that made the real database "vanish".
#
#    local — Start a MySQL Docker container and connect to it. ONLY use this if you
#            do NOT have a native MySQL on 3306; otherwise the two collide on the port.
#
#    aiven — Use Aiven cloud MySQL (no local Docker container needed).
#            Requires Aiven credentials in .env SPRING_DATASOURCE_* vars.
#
#  Usage:
#    chmod +x start.sh
#    ./start.sh
# ═══════════════════════════════════════════════════════════════════
ENV=local
DB=aiven   # native | local | aiven

# Auto-open the app in your default browser once it's responding (true | false).
# OPEN_BROWSER_TIMEOUT caps how long to wait for the server before giving up —
# raise it for first-time `docker` builds (they compile the Angular app + JAR).
OPEN_BROWSER=true
OPEN_BROWSER_TIMEOUT=180

# ── Internal config ────────────────────────────────────────────────
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ENV_FILE="$SCRIPT_DIR/.env"
ANGULAR_DIR="$SCRIPT_DIR/tesseraapp"
# Angular services hardcode localhost:8080 — Spring Boot must listen here in local mode
LOCAL_BACKEND_PORT=8080

# ── Terminal colors ────────────────────────────────────────────────
GREEN='\033[0;32m'; YELLOW='\033[1;33m'; BLUE='\033[0;34m'; RED='\033[0;31m'; NC='\033[0m'
log()  { echo -e "${GREEN}[start.sh]${NC} $1"; }
warn() { echo -e "${YELLOW}[start.sh]${NC} $1"; }
err()  { echo -e "${RED}[start.sh]${NC} $1" >&2; exit 1; }

# ── Browser auto-open ──────────────────────────────────────────────
# open_browser <url> — best-effort, cross-platform (Git Bash/MSYS, WSL,
# macOS, native Linux). Never fails the script.
open_browser() {
  local url="$1"
  case "$(uname -s)" in
    MINGW*|MSYS*|CYGWIN*)
      powershell.exe -NoProfile -Command "Start-Process '$url'" >/dev/null 2>&1 \
        || start "" "$url" >/dev/null 2>&1 || true ;;
    Linux*)
      if grep -qiE "microsoft|wsl" /proc/version 2>/dev/null; then
        powershell.exe -NoProfile -Command "Start-Process '$url'" >/dev/null 2>&1 \
          || cmd.exe /c start "" "$url" >/dev/null 2>&1 || true
      else
        xdg-open "$url" >/dev/null 2>&1 || true
      fi ;;
    Darwin*) open "$url" >/dev/null 2>&1 || true ;;
    *) warn "Don't know how to open a browser on '$(uname -s)' — visit $url manually." ;;
  esac
}

# wait_and_open <url> — polls (in the background) until the URL responds, then
# opens it. Honors OPEN_BROWSER and the OPEN_BROWSER_TIMEOUT cap; falls back to a
# short fixed wait if curl isn't installed.
wait_and_open() {
  local url="$1"
  [[ "$OPEN_BROWSER" == "true" ]] || return 0
  if command -v curl >/dev/null 2>&1; then
    local waited=0
    until curl -s -o /dev/null --max-time 3 "$url"; do
      sleep 2
      waited=$((waited + 2))
      if [[ $waited -ge $OPEN_BROWSER_TIMEOUT ]]; then
        warn "App didn't respond at $url within ${OPEN_BROWSER_TIMEOUT}s — open it manually."
        return 0
      fi
    done
  else
    sleep 15  # no curl — best-effort fixed wait for the server to come up
  fi
  log "Opening $url in your default browser..."
  open_browser "$url"
}

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
  if [[ "$DB" == "native" ]]; then
    # Use the host's own MySQL (e.g. Windows service MySQL80) already bound to
    # ${MYSQL_HOST}:${MYSQL_PORT}. Deliberately does NOT run `docker compose up mysql`,
    # so an empty Docker container can never grab port 3306 and shadow the real data.
    log "DB=native — using host MySQL at ${MYSQL_HOST}:${MYSQL_PORT} (no Docker container)."
    if command -v nc >/dev/null 2>&1 && ! nc -z "${MYSQL_HOST}" "${MYSQL_PORT}" 2>/dev/null; then
      warn "Nothing is listening on ${MYSQL_HOST}:${MYSQL_PORT}."
      warn "Start your native MySQL first (Windows: run 'net start MySQL80' in an ADMIN terminal),"
      warn "then re-run ./start.sh. Aborting so we don't boot against a dead datasource."
      exit 1
    fi
    log "Host MySQL is reachable."
  elif [[ "$DB" == "local" ]]; then
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

  # Open the frontend in the browser once it's actually serving (backgrounded;
  # never blocks). Lands on the base URL, which redirects to /login when signed out.
  wait_and_open "http://localhost:4200" &
  BROWSER_PID=$!

  # ── Cleanup ──────────────────────────────────────────────────────
  cleanup() {
    echo ""
    warn "Shutting down Spring Boot and Angular..."
    kill "$SPRING_PID" 2>/dev/null || true
    kill "$ANGULAR_PID" 2>/dev/null || true
    kill "$BROWSER_PID" 2>/dev/null || true
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

  # Published host port (compose maps ${APP_PORT:-8090}:8080). Read straight from
  # .env without sourcing, so URL values containing '&' aren't mangled by the shell.
  local app_port
  app_port="$(grep -E '^[[:space:]]*APP_PORT=' "$ENV_FILE" 2>/dev/null | tail -n1 | cut -d= -f2 | tr -d '[:space:]')"
  # Open the app once it responds; backgrounded so it never blocks the build, and
  # killed on exit so the poller doesn't linger after Ctrl+C.
  wait_and_open "http://localhost:${app_port:-8090}" &
  BROWSER_PID=$!
  trap 'kill "$BROWSER_PID" 2>/dev/null || true' EXIT

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
