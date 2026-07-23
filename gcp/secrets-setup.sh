#!/usr/bin/env bash
# secrets-setup.sh — Create all required Secret Manager secrets for TesseraApp on GCP.
#
# Mirrors aws/secrets-setup.sh but uses Google Secret Manager. Run ONCE after setup.sh.
# Secrets are created with placeholder values (except the JWT secret, which is randomised);
# update each with the real value before deploying — the commands are printed at the end.
#
# Cloud Run references these by name via --set-secrets ENV_VAR=<secret-name>:latest
# (see cloudrun-service.yaml / cloudbuild.yaml / deploy-gcp.yml).
#
# Required env: GCP_PROJECT_ID   Optional: GCP_REGION (default us-central1)
set -euo pipefail

PROJECT_ID="${GCP_PROJECT_ID:?set GCP_PROJECT_ID}"
gcloud config set project "${PROJECT_ID}" >/dev/null

# create_secret <name> <value> — creates the secret and adds the first version.
create_secret() {
  local NAME="$1" VALUE="$2"
  echo -n "  tessera-${NAME} ... "
  if gcloud secrets describe "tessera-${NAME}" >/dev/null 2>&1; then
    echo "exists (skipping create)"
  else
    gcloud secrets create "tessera-${NAME}" --replication-policy="automatic" >/dev/null
    printf '%s' "${VALUE}" | gcloud secrets versions add "tessera-${NAME}" --data-file=- >/dev/null
    echo "created"
  fi
}

echo "==> Creating Secret Manager secrets in project ${PROJECT_ID}"

# Strong JWT secret generated immediately (ready to use).
create_secret "jwt-secret"            "$(openssl rand -base64 48)"

# Aiven MySQL password (host/port/db/user are non-sensitive → GitHub/env, not here).
create_secret "db-password"           "CHANGE_ME_aiven_db_password"

# Mail (Gmail SMTP)
create_secret "mail-username"         "CHANGE_ME@gmail.com"
create_secret "mail-password"         "CHANGE_ME_app_password_16chars"

# Twilio (SMS / MFA — stubbed in dev, real integration optional)
create_secret "twilio-sid"            "CHANGE_ME_twilio_account_sid"
create_secret "twilio-token"          "CHANGE_ME_twilio_auth_token"
create_secret "twilio-from-number"    "+10000000000"

# OAuth2 providers
create_secret "google-client-id"      "CHANGE_ME.apps.googleusercontent.com"
create_secret "google-client-secret"  "CHANGE_ME_google_secret"
create_secret "github-client-id"      "CHANGE_ME_github_oauth_client_id"
create_secret "github-client-secret"  "CHANGE_ME_github_oauth_secret"
create_secret "microsoft-client-id"   "CHANGE_ME_microsoft_app_id"
create_secret "microsoft-client-secret" "CHANGE_ME_microsoft_secret"

cat <<'EOF'

✓ Secrets created. Update every CHANGE_ME value before deploying, e.g.:

  printf '%s' 'AVNS_...your-aiven-password'    | gcloud secrets versions add tessera-db-password --data-file=-
  printf '%s' 'you@gmail.com'                  | gcloud secrets versions add tessera-mail-username --data-file=-
  printf '%s' '<16-char-app-password>'         | gcloud secrets versions add tessera-mail-password --data-file=-
  printf '%s' '<google-client-secret>'         | gcloud secrets versions add tessera-google-client-secret --data-file=-
  # ...repeat for github-*, microsoft-*, twilio-*

The JWT secret is already randomised:
  gcloud secrets versions access latest --secret=tessera-jwt-secret
EOF
