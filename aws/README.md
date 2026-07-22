# TesseraApp — AWS Deployment Guide

End-to-end checklist for deploying TesseraApp to AWS using ECS Fargate + RDS MySQL + S3 (image storage) + ALB (HTTPS termination) + Secrets Manager (secrets injection).

---

## AWS services you need

| Service | Purpose | Free-tier? |
|---|---|---|
| **ECR** | Container registry (store the Docker image) | 500 MB/month free |
| **ECS Fargate** | Run the containerised app (no servers to manage) | Compute billed per task |
| **RDS MySQL 8.x** | Managed MySQL (replaces local/Aiven MySQL) | db.t3.micro free for 12 months |
| **S3** | Profile image object storage | 5 GB free |
| **ALB** | HTTPS load balancer → ECS task | ~$16/month |
| **Secrets Manager** | Inject all secrets at task startup (JWT, DB, mail, Twilio, OAuth2) | $0.40/secret/month |
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

# Allow reading from Secrets Manager (needed for the `secrets:` block in task-definition.json):
aws iam attach-role-policy --role-name ecsTaskExecutionRole \
  --policy-arn arn:aws:iam::aws:policy/SecretsManagerReadWrite
```

### Task role (what the running container can do)
```bash
aws iam create-role --role-name tessera-app-task-role \
  --assume-role-policy-document '{"Version":"2012-10-17","Statement":[{"Effect":"Allow","Principal":{"Service":"ecs-tasks.amazonaws.com"},"Action":"sts:AssumeRole"}]}'

# Allow S3 image upload/download (replace YOUR_BUCKET_NAME):
aws iam put-role-policy --role-name tessera-app-task-role \
  --policy-name S3ImageStorage \
  --policy-document '{
    "Version":"2012-10-17",
    "Statement":[{
      "Effect":"Allow",
      "Action":["s3:PutObject","s3:GetObject","s3:DeleteObject"],
      "Resource":"arn:aws:s3:::YOUR_BUCKET_NAME/*"
    }]
  }'
```

---

## Step 2 — Create the S3 bucket for profile images

```bash
aws s3 mb s3://tessera-app-images --region us-east-1

# Allow public read (profile images are public):
aws s3api put-bucket-policy --bucket tessera-app-images --policy '{
  "Version":"2012-10-17",
  "Statement":[{
    "Effect":"Allow",
    "Principal":"*",
    "Action":"s3:GetObject",
    "Resource":"arn:aws:s3:::tessera-app-images/*"
  }]
}'

# CORS (Angular frontend reads images cross-origin from S3 — replace YOUR_DOMAIN):
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

# Build and push (from the repo root):
chmod +x aws/push-to-ecr.sh
AWS_REGION=us-east-1 ./aws/push-to-ecr.sh latest
```

---

## Step 4 — Create Secrets Manager secrets

```bash
chmod +x aws/secrets-setup.sh
AWS_REGION=us-east-1 ./aws/secrets-setup.sh

# Then update every CHANGE_ME value:
aws secretsmanager update-secret --region us-east-1 \
  --secret-id tessera-app/db-password \
  --secret-string 'your-real-db-password'
# ... repeat for all secrets
```

---

## Step 5 — Create RDS MySQL

```bash
aws rds create-db-instance \
  --db-instance-identifier tessera-app-db \
  --db-instance-class db.t3.micro \
  --engine mysql \
  --engine-version 8.0 \
  --master-username tessera \
  --master-user-password 'your-real-db-password' \
  --allocated-storage 20 \
  --db-name db2 \
  --vpc-security-group-ids sg-XXXXXXXXXXXXXXXXX \
  --publicly-accessible \
  --region us-east-1
```

Once the RDS instance is available, initialise the schema (run from your local machine or a bastion host):
```bash
mysql -h your-rds-endpoint.us-east-1.rds.amazonaws.com -u tessera -p db2 \
  < src/main/resources/schema.sql
```

---

## Step 6 — Fill in task-definition.json placeholders

Edit `aws/task-definition.json` and replace:
- `ACCOUNT_ID` → your 12-digit AWS account ID (`aws sts get-caller-identity --query Account`)
- `REGION` → your region (e.g. `us-east-1`)
- `RDS_ENDPOINT` → the RDS endpoint from Step 5
- `S3_BUCKET` → `tessera-app-images`
- `APP_DOMAIN` → your public URL (e.g. `https://app.tessera.example.com`)
- `DB_NAME` → `db2`
- `DB_USERNAME` → `tessera`

Then register the task definition:
```bash
aws ecs register-task-definition \
  --cli-input-json file://aws/task-definition.json \
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

```bash
# 1. Push new image:
AWS_REGION=us-east-1 ./aws/push-to-ecr.sh $(git rev-parse --short HEAD)

# 2. Update task definition with new image tag in task-definition.json, then register:
aws ecs register-task-definition --cli-input-json file://aws/task-definition.json

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
