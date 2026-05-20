# AWS Deployment Guide

This directory contains everything you need to deploy the app to AWS. Two paths are documented:

| Path                | Best for                                            | Files                                          |
|---------------------|------------------------------------------------------|------------------------------------------------|
| **AWS App Runner**  | Plug-and-play. Container in, URL out.                | `cloudformation-stack.yaml`, `apprunner.yaml`  |
| **AWS ECS Fargate** | More control: VPC, subnets, scaling rules, ALB.      | `ecs-task-definition.json`                     |

**Recommendation:** Start with App Runner. Switch to ECS later if you need per-region multi-AZ or
private VPC networking. The Docker image is identical for both.

---

## Path A — App Runner (recommended)

### One-time prerequisites

1. **AWS account** with billing alerts enabled
2. **AWS CLI** installed and `aws configure` run (creates `~/.aws/credentials`)
3. **GitHub repo connection** (optional, only for auto-deploy on push) — set up in AWS console: App Runner → Connections → Create connection → GitHub

### Deploy with CloudFormation (one command)

```bash
aws cloudformation deploy \
  --template-file infrastructure/aws/cloudformation-stack.yaml \
  --stack-name securecapita-prod \
  --capabilities CAPABILITY_NAMED_IAM \
  --parameter-overrides \
      DBMasterPassword='REPLACE_WITH_STRONG_PASSWORD' \
      JwtSecret='REPLACE_WITH_BASE64_48_BYTE_SECRET'
```

This creates:

- **ECR repository** `securecapita` for the Docker image
- **RDS MySQL** instance (db.t4g.micro, single-AZ, encrypted at rest)
- **Secrets Manager secrets** for DB password and JWT key
- **App Runner service** wired to the ECR image, with health checks at `/actuator/health`
- **IAM roles** for App Runner → ECR pull and App Runner → Secrets Manager read

The stack outputs the App Runner service URL when it finishes — that's your live app.

### Push the first image

App Runner can't start until there's an image in ECR. From the repo root:

```bash
# Get the ECR login + push URL from the stack outputs
ECR_URI=$(aws cloudformation describe-stacks --stack-name securecapita-prod \
  --query 'Stacks[0].Outputs[?OutputKey==`EcrRepositoryUri`].OutputValue' --output text)

# Log Docker in to ECR
aws ecr get-login-password --region us-east-1 | docker login --username AWS --password-stdin "${ECR_URI%/*}"

# Build and push
docker build -t "${ECR_URI}:latest" .
docker push "${ECR_URI}:latest"
```

App Runner will pull the new image automatically (the service is configured with
`AutoDeploymentsEnabled: true`).

### Tear down

```bash
aws cloudformation delete-stack --stack-name securecapita-prod
```

> WARNING: This deletes the RDS instance and all data. Take a manual snapshot first if you care about
> the data. Set `DBDeletionProtection: true` in the template parameters to require a snapshot before
> deletion.

---

## Path B — ECS Fargate (more control)

Use `ecs-task-definition.json` as the starting point. You'll also need:

1. A **VPC + subnets** (use the default VPC for simplicity)
2. An **ECS cluster** — `aws ecs create-cluster --cluster-name securecapita`
3. An **ALB** with target group pointing at port 8080 and health check at `/actuator/health`
4. A **service** that references the task definition and the target group

Full step-by-step is in AWS's own ECS Fargate tutorial — the task definition here gives you the
container spec, env vars, and IAM role.

---

## CI/CD options

Two GitHub Actions workflows are provided in `.github/workflows/`:

| Workflow            | Trigger                       | What it does                                              |
|---------------------|-------------------------------|-----------------------------------------------------------|
| `ci.yml`            | Every PR                      | Maven test, Angular lint+build, Docker build (no push)    |
| `aws-deploy.yml`    | Push to `master`              | Build image, push to ECR, App Runner picks it up          |

The deploy workflow expects these **GitHub repository secrets**:

| Secret                  | Where to get it                                            |
|-------------------------|-------------------------------------------------------------|
| `AWS_ROLE_ARN`          | IAM role for GitHub OIDC. Create with `aws-actions/configure-aws-credentials` docs. |
| `AWS_REGION`            | e.g. `us-east-1`                                            |
| `ECR_REPOSITORY`        | `securecapita` (matches the CloudFormation stack)           |

OIDC trust is preferred over long-lived access keys. See AWS's
[Configuring OpenID Connect in Amazon Web Services](https://docs.github.com/en/actions/deployment/security-hardening-your-deployments/configuring-openid-connect-in-amazon-web-services)
guide for the role trust policy.

---

## Cost estimate (App Runner path)

| Resource           | Estimated monthly cost (us-east-1, free tier-aware) |
|--------------------|------------------------------------------------------|
| App Runner         | ~$5 idle ($0.064/vCPU-hr + memory; paused when no traffic on min-size deployments) |
| RDS db.t4g.micro   | Free tier first 12 months, then ~$13/mo              |
| ECR storage        | Free tier 500MB/mo, then ~$0.10/GB                   |
| Secrets Manager    | $0.40/secret/mo (2 secrets = $0.80)                  |
| **Total**          | ~$0–6 first year, ~$20/mo after free tier ends       |

Setting App Runner to **Pause on no traffic** drops idle cost to $0. The trade-off is a cold-start
of ~30–60s on the first request after pause.
