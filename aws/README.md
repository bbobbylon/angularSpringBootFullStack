# TesseraApp — AWS Deployment Guide

End-to-end checklist for deploying TesseraApp to AWS using ECS Fargate + **Aiven MySQL** (managed DB) + S3 (image storage) + ALB (HTTPS termination) + Secrets Manager (secrets injection).

---

## Fastest path — one-command bootstrap

`aws/setup.sh` runs all 9 infrastructure steps below in order and prints a final banner with your ALB DNS name:

```bash
# Prerequisites: AWS CLI configured, correct region set
export AWS_REGION=us-east-1
export AWS_ACCOUNT_ID=$(aws sts get-caller-identity --query Account --output text)
export AIVEN_HOST=mysql-xyz.aivencloud.com
export AIVEN_PORT=28674
export AIVEN_DB=defaultdb
export AIVEN_USER=avnadmin
export AIVEN_PASSWORD=your-aiven-password
export APP_DOMAIN=https://app.tessera.example.com

chmod +x aws/setup.sh aws/secrets-setup.sh aws/push-to-ecr.sh
./aws/setup.sh
```

The manual steps below explain what `setup.sh` does, which is useful for debugging or incremental changes.

---

## AWS services used

| Service | Purpose | Free-tier? |
|---|---|---|
| **ECR** | Container registry | 500 MB/month free |
| **ECS Fargate** | Run the containerised app | Compute billed per task |
| **Aiven MySQL** | Managed MySQL (replaces RDS) | Paid; ~$19/month starter |
| **S3** | Profile image object storage | 5 GB free |
| **ALB** | HTTPS load balancer → ECS | ~$16/month |
| **Secrets Manager** | Inject all secrets at task startup | $0.40/secret/month |
| **CloudWatch Logs** | Container log output | 5 GB free |
| **IAM** | Roles for the ECS task and execution | Free |

---

## Step 1 — Create IAM roles

### Execution role (pulls image + reads secrets)
```bash
aws iam create-role --role-name ecsTaskExecutionRole \
  --assume-role-policy-document '{"Version":"2012-10-17","Statement":[{"Effect":"Allow","Principal":{"Service":"ecs-tasks.amazonaws.com"},"Action":"sts:AssumeRole"}]}'

aws iam attach-role-policy --role-name ecsTaskExecutionRole \
  --policy-arn arn:aws:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy

aws iam attach-role-policy --role-name ecsTaskExecutionRole \
  --policy-arn arn:aws:iam::aws:policy/SecretsManagerReadWrite
```

### Task role (what the running container can do — S3 access)
```bash
aws iam create-role --role-name tessera-app-task-role \
  --assume-role-policy-document '{"Version":"2012-10-17","Statement":[{"Effect":"Allow","Principal":{"Service":"ecs-tasks.amazonaws.com"},"Action":"sts:AssumeRole"}]}'

aws iam put-role-policy --role-name tessera-app-task-role \
  --policy-name S3ImageStorage \
  --policy-document '{
    "Version":"2012-10-17",
    "Statement":[{
      "Effect":"Allow",
      "Action":["s3:PutObject","s3:GetObject","s3:DeleteObject"],
      "Resource":"arn:aws:s3:::tessera-app-images/*"
    }]
  }'
```

---

## Step 2 — Create the S3 bucket for profile images

```bash
aws s3 mb s3://tessera-app-images --region us-east-1

aws s3api put-bucket-policy --bucket tessera-app-images --policy '{
  "Version":"2012-10-17",
  "Statement":[{
    "Effect":"Allow",
    "Principal":"*",
    "Action":"s3:GetObject",
    "Resource":"arn:aws:s3:::tessera-app-images/*"
  }]
}'

# Replace YOUR_DOMAIN with your actual domain:
aws s3api put-bucket-cors --bucket tessera-app-images --cors-configuration '{
  "CORSRules":[{
    "AllowedOrigins":["https://YOUR_DOMAIN", "http://localhost:4200"],
    "AllowedMethods":["GET"],
    "AllowedHeaders":["*"],
    "MaxAgeSeconds":3600
  }]
}'
```

---

## Step 3 — Create ECR repository and push the image

```bash
aws ecr create-repository --repository-name tessera-app --region us-east-1

chmod +x aws/push-to-ecr.sh
AWS_REGION=us-east-1 ./aws/push-to-ecr.sh latest
```

---

## Step 4 — Create Secrets Manager secrets

```bash
chmod +x aws/secrets-setup.sh
AWS_REGION=us-east-1 ./aws/secrets-setup.sh

# Then fill in the real Aiven values (from Aiven console → Service → Connection info):
aws secretsmanager update-secret --region us-east-1 \
  --secret-id tessera-app/aiven-host     --secret-string 'mysql-xyz.aivencloud.com'
aws secretsmanager update-secret --region us-east-1 \
  --secret-id tessera-app/aiven-port     --secret-string '28674'
aws secretsmanager update-secret --region us-east-1 \
  --secret-id tessera-app/aiven-db       --secret-string 'defaultdb'
aws secretsmanager update-secret --region us-east-1 \
  --secret-id tessera-app/aiven-user     --secret-string 'avnadmin'
aws secretsmanager update-secret --region us-east-1 \
  --secret-id tessera-app/db-password    --secret-string 'your-real-aiven-password'
# ... repeat for mail-*, twilio-*, google-*, github-*
```

---

## Step 5 — Initialise the Aiven database schema

TesseraApp uses Aiven MySQL as the production database. Connect to it and run the schema once:

```bash
# Install Aiven CA cert first (download from Aiven console → Service → Overview → CA Certificate):
mysql \
  --host=mysql-xyz.aivencloud.com \
  --port=28674 \
  --user=avnadmin \
  --password \
  --ssl-ca=aiven-ca.pem \
  --ssl-mode=REQUIRED \
  defaultdb < src/main/resources/schema.sql
```

The schema is idempotent (`CREATE TABLE IF NOT EXISTS`, no DROPs) so re-running it is safe.

---

## Step 6 — Fill in task-definition.json and register it

`aws/task-definition.json` is a template with `${VARIABLE}` tokens. Fill and register it:

```bash
export AWS_ACCOUNT_ID=$(aws sts get-caller-identity --query Account --output text)
export AWS_REGION=us-east-1
export ECR_IMAGE_URI="${AWS_ACCOUNT_ID}.dkr.ecr.${AWS_REGION}.amazonaws.com/tessera-app:latest"
export AIVEN_HOST=mysql-xyz.aivencloud.com
export AIVEN_PORT=28674
export AIVEN_DB=defaultdb
export AIVEN_USER=avnadmin
export S3_BUCKET=tessera-app-images
export APP_DOMAIN=https://app.tessera.example.com

envsubst < aws/task-definition.json > /tmp/task-definition.filled.json
aws ecs register-task-definition \
  --cli-input-json file:///tmp/task-definition.filled.json \
  --region us-east-1
```

---

## Step 7 — Create the ECS cluster and service

```bash
aws ecs create-cluster --cluster-name tessera-app-cluster --region us-east-1

# Edit aws/ecs-service.json to fill in subnets, security group, ALB target group ARN:
aws ecs create-service \
  --cli-input-json file://aws/ecs-service.json \
  --region us-east-1
```

---

## Step 8 — Set up ALB + HTTPS

1. Create an Application Load Balancer (port 80 → 443 redirect, port 443 → target group → ECS container:8080).
2. Request a certificate via **AWS Certificate Manager (ACM)** for your domain.
3. Attach the certificate to the ALB HTTPS listener.
4. Point your domain's A record (Route 53 or your registrar) to the ALB DNS name.

---

## Redeploy after a code change

The GitHub Actions `deploy.yml` workflow handles this automatically on every push to `master`. To trigger manually:

```bash
# 1. Push new image:
AWS_REGION=us-east-1 ./aws/push-to-ecr.sh $(git rev-parse --short HEAD)

# 2. Fill template and register:
envsubst < aws/task-definition.json > /tmp/task-definition.filled.json
aws ecs register-task-definition --cli-input-json file:///tmp/task-definition.filled.json

# 3. Force a rolling deployment:
aws ecs update-service \
  --cluster tessera-app-cluster \
  --service tessera-app-service \
  --force-new-deployment \
  --region us-east-1
```

---

## Rotating secrets

```bash
# Generate a new JWT secret and update Secrets Manager:
aws secretsmanager update-secret \
  --secret-id tessera-app/jwt-secret \
  --secret-string "$(openssl rand -base64 48)" \
  --region us-east-1

# Force ECS to restart tasks and pick up the new secret:
aws ecs update-service \
  --cluster tessera-app-cluster \
  --service tessera-app-service \
  --force-new-deployment \
  --region us-east-1
```

---

## GitHub Actions secrets to configure

Go to **GitHub → Repository → Settings → Secrets and variables → Actions** and add:

| Secret name | Where to find it |
|---|---|
| `AWS_ACCESS_KEY_ID` | IAM → Users → deploy user → Security credentials |
| `AWS_SECRET_ACCESS_KEY` | Same as above |
| `AWS_REGION` | e.g. `us-east-1` |
| `AWS_ACCOUNT_ID` | `aws sts get-caller-identity --query Account` |
| `ECR_REPOSITORY` | e.g. `tessera-app` |
| `ECS_CLUSTER` | e.g. `tessera-app-cluster` |
| `ECS_SERVICE` | e.g. `tessera-app-service` |
| `AIVEN_HOST` | Aiven console → Service → Overview → Host |
| `AIVEN_PORT` | Aiven console → Service → Overview → Port |
| `AIVEN_DB` | e.g. `defaultdb` |
| `AIVEN_USER` | e.g. `avnadmin` |
| `S3_BUCKET` | e.g. `tessera-app-images` |
| `APP_DOMAIN` | e.g. `https://app.tessera.example.com` |
