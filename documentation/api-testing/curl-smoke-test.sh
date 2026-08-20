#!/usr/bin/env bash
#
# TesseraApp API smoke test — a runnable cURL suite covering every endpoint
# family documented in documentation/GUIDE.md §8 (except WebAuthn/passkeys,
# which need a real browser-generated public-key credential and cannot be
# faked from a shell script — see the README in this folder).
#
# Usage:
#   ./curl-smoke-test.sh                       # safe: reads only, plus one throwaway register
#   ./curl-smoke-test.sh --with-mutations      # also creates a customer/invoice/service (see WARNING)
#   ./curl-smoke-test.sh --with-contact-email  # also fires POST /contact/send (sends a REAL email)
#   BASE_URL=http://localhost:8080 ./curl-smoke-test.sh
#
# Requires: bash, curl. No jq dependency — field extraction is a small sed helper.
# Assumes the app is already running (./start.sh) and the demo seed data exists
# (DemoDataSeeder runs on every boot in non-prod profiles).

set -uo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
ADMIN_EMAIL="${ADMIN_EMAIL:-eve.admin@tessera.dev}"
ADMIN_PASSWORD="${ADMIN_PASSWORD:-TesseraDemo@1}"
WITH_MUTATIONS=0
WITH_CONTACT_EMAIL=0

usage() {
  sed -n '2,15p' "$0" | sed 's/^# \{0,1\}//'
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --base-url) BASE_URL="$2"; shift 2 ;;
    --admin-email) ADMIN_EMAIL="$2"; shift 2 ;;
    --admin-password) ADMIN_PASSWORD="$2"; shift 2 ;;
    --with-mutations) WITH_MUTATIONS=1; shift ;;
    --with-contact-email) WITH_CONTACT_EMAIL=1; shift ;;
    -h|--help) usage; exit 0 ;;
    *) echo "Unknown option: $1" >&2; usage; exit 1 ;;
  esac
done

PASS=0
FAIL=0
WARN=0
GREEN=$'\033[32m'; RED=$'\033[31m'; YELLOW=$'\033[33m'; DIM=$'\033[2m'; BOLD=$'\033[1m'; RESET=$'\033[0m'

section() { printf '\n%s%s%s\n' "$BOLD" "== $1 ==" "$RESET"; }
note()    { printf '  %sNOTE%s  %s\n' "$YELLOW" "$RESET" "$1"; }
warn()    { WARN=$((WARN+1)); printf '  %sWARN%s  %s\n' "$YELLOW" "$RESET" "$1"; }

# Pulls a flat "field": value out of a JSON blob without a jq dependency.
# Good enough for the single-occurrence top-level-ish fields this script reads
# (access_token, refresh_token, id, message) — not a general JSON parser.
jget() {
  printf '%s' "$1" | sed -n "s/.*\"$2\"[[:space:]]*:[[:space:]]*\"\{0,1\}\([^\",}]*\)\"\{0,1\}.*/\1/p" | head -n1
}

# call METHOD PATH EXPECTED_STATUS [JSON_BODY] [BEARER_TOKEN] [LABEL]
# Leaves the response body in $LAST_BODY and status in $LAST_STATUS.
call() {
  local method="$1" path="$2" expected="$3" data="${4:-}" auth="${5:-}" label="${6:-}"
  local bodyfile status
  bodyfile="$(mktemp)"
  local -a args=(-sS -o "$bodyfile" -w '%{http_code}' -X "$method" "${BASE_URL}${path}")
  [[ -n "$data" ]] && args+=(-H "Content-Type: application/json" -d "$data")
  [[ -n "$auth" ]] && args+=(-H "Authorization: Bearer $auth")
  status="$(curl "${args[@]}")"
  LAST_BODY="$(cat "$bodyfile")"; rm -f "$bodyfile"
  LAST_STATUS="$status"
  local shown="${label:-$method $path}"
  if [[ "$status" == "$expected" ]]; then
    PASS=$((PASS+1)); printf '  %sPASS%s  %-7s %-55s -> %s\n' "$GREEN" "$RESET" "$method" "$shown" "$status"
  else
    FAIL=$((FAIL+1)); printf '  %sFAIL%s  %-7s %-55s -> %s (expected %s)\n' "$RED" "$RESET" "$method" "$shown" "$status" "$expected"
    printf '        %s%s%s\n' "$DIM" "${LAST_BODY:0:200}" "$RESET"
  fi
}

# call_headers METHOD PATH [BEARER_TOKEN] [EXTRA_HEADER]
# Same as call() but also captures response headers into $LAST_HEADERS.
call_headers() {
  local method="$1" path="$2" auth="${3:-}" extra="${4:-}"
  local bodyfile hdrfile
  bodyfile="$(mktemp)"; hdrfile="$(mktemp)"
  local -a args=(-sS -D "$hdrfile" -o "$bodyfile" -w '%{http_code}' -X "$method" "${BASE_URL}${path}")
  [[ -n "$auth" ]] && args+=(-H "Authorization: Bearer $auth")
  [[ -n "$extra" ]] && args+=(-H "$extra")
  LAST_STATUS="$(curl "${args[@]}")"
  LAST_HEADERS="$(cat "$hdrfile")"; LAST_BODY="$(cat "$bodyfile")"
  rm -f "$bodyfile" "$hdrfile"
}

printf '%sTesseraApp API smoke test%s — %s\n' "$BOLD" "$RESET" "$BASE_URL"
[[ "$WITH_MUTATIONS" == 1 ]] && note "mutations ENABLED — this run will create a permanent customer/invoice row (no delete endpoint exists) and a service row (retired automatically afterward)."
[[ "$WITH_CONTACT_EMAIL" == 1 ]] && note "contact-email ENABLED — POST /contact/send will fire a real notification via NotificationService."

# ---------------------------------------------------------------------------
section "Public account endpoints (§8.2)"
# ---------------------------------------------------------------------------
STAMP="$(date +%s)"
call POST /user/register 201 "{\"firstName\":\"Smoke\",\"lastName\":\"Test\",\"email\":\"smoketest+${STAMP}@example.com\",\"password\":\"SmokeTest@123\"}"
note "the registered account stays unverified — this script can't read the verification email link."

call GET "/user/resetpassword/smoketest+${STAMP}@example.com" 200 "" "" "GET /user/resetpassword/{email}"
note "reset-password always returns 200 regardless of whether the email exists (user-enumeration protection) — see feedback_security memory."

# ---------------------------------------------------------------------------
section "Login as seeded demo admin (${ADMIN_EMAIL})"
# ---------------------------------------------------------------------------
call POST /user/login 200 "{\"email\":\"${ADMIN_EMAIL}\",\"password\":\"${ADMIN_PASSWORD}\"}" "" "POST /user/login"
ADMIN_TOKEN="$(jget "$LAST_BODY" access_token)"
REFRESH_TOKEN="$(jget "$LAST_BODY" refresh_token)"
ADMIN_ID="$(jget "$LAST_BODY" id)"

if [[ -z "$ADMIN_TOKEN" ]]; then
  warn "no access_token in the login response — the demo account may have MFA enabled now, or credentials are wrong. Skipping every authenticated call below."
  SKIP_AUTH=1
else
  SKIP_AUTH=0
fi

if [[ "$SKIP_AUTH" == 0 ]]; then

  # -------------------------------------------------------------------------
  section "Profile & account (§8.2, authenticated)"
  # -------------------------------------------------------------------------
  call GET /user/profile 200 "" "$ADMIN_TOKEN"
  call GET "/user/events?page=0&size=5" 200 "" "$ADMIN_TOKEN"

  # -------------------------------------------------------------------------
  section "MFA status (§8.3)"
  # -------------------------------------------------------------------------
  call GET /user/totp/status 200 "" "$ADMIN_TOKEN"
  note "totp/enable and webauthn/* are skipped — they need a real TOTP code / browser-generated credential, see README."

  # -------------------------------------------------------------------------
  section "Sessions & connected accounts (§8.4)"
  # -------------------------------------------------------------------------
  call GET /user/sessions 200 "" "$ADMIN_TOKEN"
  call GET /user/sessions/providers 200 "" "$ADMIN_TOKEN"

  # -------------------------------------------------------------------------
  section "Federated login providers (§8.5)"
  # -------------------------------------------------------------------------
  call GET /oauth2/providers 200

  # -------------------------------------------------------------------------
  section "Admin — user management (§8.6, needs UPDATE:USER/UPDATE:ROLE)"
  # -------------------------------------------------------------------------
  call GET "/admin/user/list?page=0&size=10" 200 "" "$ADMIN_TOKEN"
  if [[ -n "$ADMIN_ID" ]]; then
    call GET "/admin/user/${ADMIN_ID}" 200 "" "$ADMIN_TOKEN" "GET /admin/user/{id} (self)"
    call GET "/admin/user/${ADMIN_ID}/events?page=0&size=5" 200 "" "$ADMIN_TOKEN" "GET /admin/user/{id}/events"
  else
    warn "couldn't extract the admin's own id from the login response — skipping /admin/user/{id} checks."
  fi

  # -------------------------------------------------------------------------
  section "Security dashboard (§8.7, needs UPDATE:USER/UPDATE:ROLE)"
  # -------------------------------------------------------------------------
  call GET "/admin/security/overview?days=7&suspiciousPage=0&suspiciousSize=10&restrictedPage=0&restrictedSize=10" 200 "" "$ADMIN_TOKEN"

  # -------------------------------------------------------------------------
  section "Services catalog (§8.8)"
  # -------------------------------------------------------------------------
  call GET /admin/services/list 200 "" "$ADMIN_TOKEN"
  call GET /services/public 200 "" "" "GET /services/public (no auth)"

  SERVICE_ID=""
  if [[ "$WITH_MUTATIONS" == 1 ]]; then
    call POST /admin/services/create 201 '{"name":"Smoke Test Service","description":"Created by curl-smoke-test.sh — safe to leave retired.","price":1.00}' "$ADMIN_TOKEN"
    SERVICE_ID="$(jget "$LAST_BODY" id)"
    if [[ -n "$SERVICE_ID" ]]; then
      call GET "/admin/services/get/${SERVICE_ID}" 200 "" "$ADMIN_TOKEN"
      call PUT "/admin/services/update/${SERVICE_ID}" 200 '{"name":"Smoke Test Service","description":"Updated by curl-smoke-test.sh.","price":2.00}' "$ADMIN_TOKEN"
      call PATCH "/admin/services/${SERVICE_ID}/active/false" 200 "" "$ADMIN_TOKEN" "PATCH retire service ${SERVICE_ID}"
      note "service ${SERVICE_ID} retired automatically (services have no DELETE endpoint by design — retire, never delete)."
    else
      warn "service create didn't return an id — skipping its update/retire follow-ups."
    fi
  else
    note "service create/update/retire skipped — pass --with-mutations to exercise them."
  fi

  # -------------------------------------------------------------------------
  section "Customers & invoices (§8.9)"
  # -------------------------------------------------------------------------
  call GET /customer/stats 200 "" "$ADMIN_TOKEN"
  call GET "/customer/list?page=0&size=10" 200 "" "$ADMIN_TOKEN"
  call GET "/customer/search?name=a&page=0&size=5" 200 "" "$ADMIN_TOKEN"
  call GET /customer/invoice/new 200 "" "$ADMIN_TOKEN"
  call GET "/customer/invoice/list?page=0&size=10" 200 "" "$ADMIN_TOKEN"

  if [[ "$WITH_MUTATIONS" == 1 ]]; then
    call POST /customer/create 201 '{"firstName":"Smoke","lastName":"Customer","email":"smoke.customer@example.com","address":"123 Test St","type":"INDIVIDUAL"}' "$ADMIN_TOKEN"
    CUSTOMER_ID="$(jget "$LAST_BODY" id)"
    if [[ -n "$CUSTOMER_ID" ]]; then
      call GET "/customer/get/${CUSTOMER_ID}" 200 "" "$ADMIN_TOKEN"
      call PUT "/customer/update/${CUSTOMER_ID}" 200 "{\"id\":${CUSTOMER_ID},\"firstName\":\"Smoke\",\"lastName\":\"Customer\",\"email\":\"smoke.customer@example.com\",\"address\":\"456 Updated St\",\"type\":\"INDIVIDUAL\"}" "$ADMIN_TOKEN"
      call POST "/customer/invoice/addtocustomer/${CUSTOMER_ID}" 201 '{"invoiceNumber":"SMOKE-001","status":"PENDING","totalAmount":10.0,"invoiceDate":"2026-01-01"}' "$ADMIN_TOKEN" "POST /customer/invoice/addtocustomer/{id}"
      INVOICE_ID="$(jget "$LAST_BODY" id)"
      [[ -n "$INVOICE_ID" ]] && call PATCH "/customer/invoice/update/${INVOICE_ID}" 200 '{"status":"PAID"}' "$ADMIN_TOKEN"
      note "customer ${CUSTOMER_ID} and its invoice are permanent — no DELETE endpoint exists for either. Clean up by hand if this was a real database."
    else
      warn "customer create didn't return an id — skipping its dependent follow-ups."
    fi
  else
    note "customer/invoice create/update skipped — pass --with-mutations to exercise them (leaves permanent rows, see README)."
  fi

  # -------------------------------------------------------------------------
  section "Analytics (admin reporting surface)"
  # -------------------------------------------------------------------------
  call GET /admin/analytics/summary 200 "" "$ADMIN_TOKEN"
  call GET "/admin/analytics/customers?page=0&size=10" 200 "" "$ADMIN_TOKEN"
  call GET "/admin/analytics/invoices?page=0&size=10" 200 "" "$ADMIN_TOKEN"
  call GET /admin/analytics/invoices/all 200 "" "$ADMIN_TOKEN"

  # -------------------------------------------------------------------------
  section "Contact form (public)"
  # -------------------------------------------------------------------------
  if [[ "$WITH_CONTACT_EMAIL" == 1 ]]; then
    call POST /contact/send 200 '{"name":"Smoke Test","email":"smoketest@example.com","subject":"Smoke test","message":"Sent by curl-smoke-test.sh — safe to ignore."}' "" "POST /contact/send"
  else
    note "POST /contact/send skipped — it sends a real email, pass --with-contact-email to exercise it."
  fi

  # -------------------------------------------------------------------------
  section "Backend HTTP caching (Cache-Control / ETag / 304)"
  # -------------------------------------------------------------------------
  call_headers GET "/customer/list?page=0&size=5" "$ADMIN_TOKEN"
  CACHE_CONTROL="$(printf '%s' "$LAST_HEADERS" | grep -i '^Cache-Control:' | tr -d '\r')"
  ETAG="$(printf '%s' "$LAST_HEADERS" | grep -i '^ETag:' | sed 's/^[Ee][Tt][Aa][Gg]:[[:space:]]*//' | tr -d '\r')"
  if [[ "$LAST_STATUS" == "200" && -n "$ETAG" && "$CACHE_CONTROL" == *"private"* && "$CACHE_CONTROL" == *"no-cache"* ]]; then
    PASS=$((PASS+1)); printf '  %sPASS%s  %-7s %-55s -> %s (%s, ETag present)\n' "$GREEN" "$RESET" "GET" "/customer/list (initial, capture ETag)" "$LAST_STATUS" "$CACHE_CONTROL"
  else
    FAIL=$((FAIL+1)); printf '  %sFAIL%s  %-7s %-55s -> %s (missing private/no-cache or ETag)\n' "$RED" "$RESET" "GET" "/customer/list (initial, capture ETag)" "$LAST_STATUS"
  fi

  if [[ -n "$ETAG" ]]; then
    call_headers GET "/customer/list?page=0&size=5" "$ADMIN_TOKEN" "If-None-Match: $ETAG"
    if [[ "$LAST_STATUS" == "304" ]]; then
      PASS=$((PASS+1)); printf '  %sPASS%s  %-7s %-55s -> %s (revalidated, no body sent)\n' "$GREEN" "$RESET" "GET" "/customer/list (repeat, If-None-Match)" "$LAST_STATUS"
    else
      FAIL=$((FAIL+1)); printf '  %sFAIL%s  %-7s %-55s -> %s (expected 304)\n' "$RED" "$RESET" "GET" "/customer/list (repeat, If-None-Match)" "$LAST_STATUS"
    fi
  else
    warn "no ETag captured — skipping the 304 revalidation check."
  fi

  call GET /customer/download/report 200 "" "$ADMIN_TOKEN" "GET /customer/download/report (XLSX, uncached — bypass list)"

  # -------------------------------------------------------------------------
  section "Refresh token rotation (§8.2)"
  # -------------------------------------------------------------------------
  if [[ -n "$REFRESH_TOKEN" ]]; then
    call GET /user/refresh/token 200 "" "$REFRESH_TOKEN" "GET /user/refresh/token (note: sends the REFRESH token)"
    NEW_TOKEN="$(jget "$LAST_BODY" access_token)"
    [[ -n "$NEW_TOKEN" ]] && ADMIN_TOKEN="$NEW_TOKEN"
  else
    warn "no refresh_token captured at login — skipping rotation check."
  fi

  # -------------------------------------------------------------------------
  section "Logout (Clear-Site-Data check)"
  # -------------------------------------------------------------------------
  call_headers POST /user/sessions/logout "$ADMIN_TOKEN"
  CLEAR_SITE_DATA="$(printf '%s' "$LAST_HEADERS" | grep -i '^Clear-Site-Data:' | tr -d '\r')"
  if [[ "$LAST_STATUS" == "200" && -n "$CLEAR_SITE_DATA" ]]; then
    PASS=$((PASS+1)); printf '  %sPASS%s  %-7s %-55s -> %s (%s)\n' "$GREEN" "$RESET" "POST" "/user/sessions/logout" "$LAST_STATUS" "$CLEAR_SITE_DATA"
  else
    FAIL=$((FAIL+1)); printf '  %sFAIL%s  %-7s %-55s -> %s (missing Clear-Site-Data)\n' "$RED" "$RESET" "POST" "/user/sessions/logout" "$LAST_STATUS"
  fi

fi

# ---------------------------------------------------------------------------
section "Summary"
# ---------------------------------------------------------------------------
printf '  %s%d passed%s, %s%d failed%s, %s%d warnings%s\n' "$GREEN" "$PASS" "$RESET" "$RED" "$FAIL" "$RESET" "$YELLOW" "$WARN" "$RESET"
[[ "$FAIL" -gt 0 ]] && exit 1
exit 0
