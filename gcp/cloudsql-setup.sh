#!/usr/bin/env bash
# cloudsql-setup.sh — OPTIONAL: provision a Cloud SQL (MySQL) instance.
#
# BOILERPLATE FOR LATER. The active deployment uses Aiven (db3); run this only if you
# decide to move the database into Google Cloud SQL for colocation with Cloud Run.
#
# After provisioning:
#   1. Migrate db3 into Cloud SQL using the same dump/load flow as Aiven — see
#      documentation/database.md §17.4 (mysqldump → mysql import), applying the
#      case-sensitivity bridge (§17.2) since Cloud SQL MySQL is case-sensitive.
#   2. Point Cloud Run at it. Cloud SQL is reached via the built-in connector, NOT a public
#      host, so add the connection and use the socket/proxy instead of a TLS host:port:
#        gcloud run deploy $SERVICE \
#          --add-cloudsql-instances $PROJECT_ID:$REGION:$INSTANCE \
#          --set-env-vars SPRING_DATASOURCE_URL="jdbc:mysql:///${DB_NAME}?cloudSqlInstance=${PROJECT_ID}:${REGION}:${INSTANCE}&socketFactory=com.google.cloud.sql.mysql.SocketFactory&useSSL=false"
#      (add the `com.google.cloud.sql:mysql-socket-factory-connector-j-8` dependency to pom.xml).
#      NOTE the `useSSL=false` above is CORRECT here and is the one place it should stay — the
#      Cloud SQL socket factory establishes its own authenticated, encrypted tunnel, so asking
#      Connector/J to negotiate TLS on top of it is redundant and fails. Everywhere else in this
#      repo uses `sslMode` (REQUIRED when deployed); do not "fix" this line to match them.
#
# Required env: GCP_PROJECT_ID   Optional: GCP_REGION, CLOUDSQL_INSTANCE, CLOUDSQL_DB, CLOUDSQL_USER
set -euo pipefail

PROJECT_ID="${GCP_PROJECT_ID:?set GCP_PROJECT_ID}"
REGION="${GCP_REGION:-us-central1}"
INSTANCE="${CLOUDSQL_INSTANCE:-tessera-mysql}"
DB_NAME="${CLOUDSQL_DB:-db3}"
DB_USER="${CLOUDSQL_USER:-tessera}"
TIER="${CLOUDSQL_TIER:-db-f1-micro}"   # smallest/cheapest; bump for real workloads

gcloud config set project "${PROJECT_ID}" >/dev/null

echo "==> Creating Cloud SQL MySQL 8 instance '${INSTANCE}' (${TIER}) in ${REGION}..."
gcloud sql instances create "${INSTANCE}" \
  --database-version=MYSQL_8_0 \
  --tier="${TIER}" \
  --region="${REGION}" \
  --storage-auto-increase \
  || echo "    (instance already exists)"

echo "==> Creating database '${DB_NAME}'..."
gcloud sql databases create "${DB_NAME}" --instance="${INSTANCE}" || echo "    (db exists)"

echo "==> Creating app user '${DB_USER}' with a generated password..."
DB_PW="$(openssl rand -base64 24)"
gcloud sql users create "${DB_USER}" --instance="${INSTANCE}" --password="${DB_PW}" \
  || echo "    (user exists — password not changed)"

# Store the password in Secret Manager so Cloud Run can read it the same way as the Aiven one.
if gcloud secrets describe tessera-db-password >/dev/null 2>&1; then
  printf '%s' "${DB_PW}" | gcloud secrets versions add tessera-db-password --data-file=- >/dev/null
  echo "    (added new version to tessera-db-password)"
fi

cat <<EOF

✓ Cloud SQL instance ready: ${PROJECT_ID}:${REGION}:${INSTANCE}
  Database: ${DB_NAME}   User: ${DB_USER}

Remember (see the header of this script):
  • migrate db3 into it (documentation/database.md §17.4) + apply the casing bridge (§17.2)
  • add mysql-socket-factory-connector-j to pom.xml and switch SPRING_DATASOURCE_URL to the
    Cloud SQL connector form, and add --add-cloudsql-instances to the Cloud Run deploy.
EOF
