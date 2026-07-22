#!/usr/bin/env bash
# secrets-setup.sh — Create all required AWS Secrets Manager secrets for TesseraApp.
#
# Run this ONCE before the first ECS deployment. Secrets are created with placeholder
# values; update each one with the real value before starting the task:
#
#   aws secretsmanager update-secret \
#     --secret-id tessera-app/jwt-secret \
#     --secret-string "$(openssl rand -base64 48)"
#
# Required env:
#   AWS_REGION — target region (default: us-east-1)
#
# The ARNs printed at the end map 1-to-1 to the "valueFrom" fields in task-definition.json.

set -euo pipefail

REGION="${AWS_REGION:-us-east-1}"
PREFIX="tessera-app"

create_secret() {
  local NAME="$1"
  local VALUE="$2"
  echo -n "  Creating ${PREFIX}/${NAME} ... "
  ARN=$(aws secretsmanager create-secret \
    --name "${PREFIX}/${NAME}" \
    --secret-string "${VALUE}" \
    --region "${REGION}" \
    --query ARN --output text 2>/dev/null \
    || aws secretsmanager describe-secret \
         --secret-id "${PREFIX}/${NAME}" \
         --region "${REGION}" \
         --query ARN --output text)
  echo "${ARN}"
}

echo "==> Creating Secrets Manager secrets in region: ${REGION}"
echo ""

# Generate a cryptographically-strong JWT secret immediately
JWT_SECRET=$(openssl rand -base64 48)

# ── Application secrets ────────────────────────────────────────────────────────
create_secret "jwt-secret"           "${JWT_SECRET}"

# ── Aiven MySQL password ───────────────────────────────────────────────────────
# The Aiven hostname/port/db/user are non-sensitive config stored in GitHub
# Secrets and injected via envsubst at deploy time (see deploy.yml).
# Only the password is a secret and lives in Secrets Manager.
create_secret "db-password"          "CHANGE_ME_aiven_db_password"

# ── Mail (Gmail SMTP) ─────────────────────────────────────────────────────────
create_secret "mail-username"        "CHANGE_ME@gmail.com"
create_secret "mail-password"        "CHANGE_ME_app_password_16chars"

# ── Twilio (SMS / MFA) ────────────────────────────────────────────────────────
create_secret "twilio-sid"           "CHANGE_ME_twilio_account_sid"
create_secret "twilio-token"         "CHANGE_ME_twilio_auth_token"
create_secret "twilio-from-number"   "+10000000000"

# ── OAuth2 providers ──────────────────────────────────────────────────────────
create_secret "google-client-id"     "CHANGE_ME.apps.googleusercontent.com"
create_secret "google-client-secret" "CHANGE_ME_google_secret"
create_secret "github-client-id"     "CHANGE_ME_github_oauth_client_id"
create_secret "github-client-secret" "CHANGE_ME_github_oauth_secret"

echo ""
echo "✓ All secrets created in ${REGION}."
echo ""
echo "IMPORTANT: Update every CHANGE_ME value before deploying."
echo ""
echo "Aiven MySQL password (host/port/db/user go in GitHub Secrets, not here):"
echo "  aws secretsmanager update-secret --region ${REGION} --secret-id ${PREFIX}/db-password    --secret-string '<your-aiven-password>'"
echo ""
echo "Mail / OAuth2 / Twilio:"
echo "  aws secretsmanager update-secret --region ${REGION} --secret-id ${PREFIX}/mail-username  --secret-string 'you@gmail.com'"
echo "  aws secretsmanager update-secret --region ${REGION} --secret-id ${PREFIX}/mail-password  --secret-string '<16-char-app-password>'"
echo "  ... (repeat for twilio-*, google-*, github-*)"
echo ""
echo "The JWT secret was already randomised and is ready to use:"
echo "  aws secretsmanager get-secret-value --region ${REGION} --secret-id ${PREFIX}/jwt-secret"
