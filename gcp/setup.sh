#!/usr/bin/env bash
# setup.sh — One-time GCP setup for deploying TesseraApp to Cloud Run.
#
# Enables the required APIs, creates the Artifact Registry Docker repo, and creates two
# service accounts:
#   • runtime  (tessera-run)      — the identity Cloud Run runs AS; only needs to read secrets.
#   • deployer (tessera-deployer) — the identity GitHub Actions / Cloud Build use to push
#                                   images and deploy the service.
#
# Run once per project. Idempotent-ish: re-running is safe (creates are guarded).
#
# Required env:
#   GCP_PROJECT_ID   — your GCP project id (created in the lewisu.edu org)
# Optional env:
#   GCP_REGION       — Cloud Run / Artifact Registry region (default: us-central1)
#   AR_REPO          — Artifact Registry repo name (default: tessera-app)
set -euo pipefail

PROJECT_ID="${GCP_PROJECT_ID:?set GCP_PROJECT_ID}"
REGION="${GCP_REGION:-us-central1}"
REPO="${AR_REPO:-tessera-app}"
RUNTIME_SA="tessera-run@${PROJECT_ID}.iam.gserviceaccount.com"
DEPLOY_SA="tessera-deployer@${PROJECT_ID}.iam.gserviceaccount.com"

echo "==> Project: ${PROJECT_ID}  Region: ${REGION}  Repo: ${REPO}"
gcloud config set project "${PROJECT_ID}" >/dev/null

echo "==> Enabling APIs (Run, Artifact Registry, Secret Manager, Cloud Build, Cloud SQL Admin)..."
gcloud services enable \
  run.googleapis.com \
  artifactregistry.googleapis.com \
  secretmanager.googleapis.com \
  cloudbuild.googleapis.com \
  sqladmin.googleapis.com

echo "==> Creating Artifact Registry Docker repo '${REPO}' in ${REGION}..."
gcloud artifacts repositories create "${REPO}" \
  --repository-format=docker \
  --location="${REGION}" \
  --description="TesseraApp container images" \
  || echo "    (repo already exists — skipping)"

echo "==> Creating runtime service account (Cloud Run runs as this)..."
gcloud iam service-accounts create tessera-run \
  --display-name="TesseraApp Cloud Run runtime" \
  || echo "    (already exists)"
# Runtime SA only needs to READ secrets injected via --set-secrets.
gcloud projects add-iam-policy-binding "${PROJECT_ID}" \
  --member="serviceAccount:${RUNTIME_SA}" \
  --role="roles/secretmanager.secretAccessor" >/dev/null

echo "==> Creating deployer service account (GitHub Actions / Cloud Build use this)..."
gcloud iam service-accounts create tessera-deployer \
  --display-name="TesseraApp deployer" \
  || echo "    (already exists)"
for ROLE in roles/run.admin roles/artifactregistry.writer roles/iam.serviceAccountUser roles/cloudbuild.builds.editor; do
  gcloud projects add-iam-policy-binding "${PROJECT_ID}" \
    --member="serviceAccount:${DEPLOY_SA}" --role="${ROLE}" >/dev/null
done

cat <<EOF

✓ Setup complete.

Next steps:
  1. Create secrets:     ./gcp/secrets-setup.sh
  2. Fill secret values  (the script prints the exact 'gcloud secrets versions add' commands)
  3. Deploy:             push to master (GitHub Actions) OR  gcloud builds submit --config gcp/cloudbuild.yaml

For keyless GitHub Actions auth (recommended over a downloaded key), set up Workload Identity
Federation and grant the deployer SA to your repo; otherwise create a key:
  gcloud iam service-accounts keys create key.json --iam-account=${DEPLOY_SA}
  # then paste key.json contents into the GitHub repo secret GCP_SA_KEY (and delete key.json locally)
EOF
