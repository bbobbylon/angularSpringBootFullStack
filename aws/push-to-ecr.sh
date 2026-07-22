#!/usr/bin/env bash
# push-to-ecr.sh — Build the TesseraApp Docker image and push it to Amazon ECR.
#
# Usage:
#   ./aws/push-to-ecr.sh [IMAGE_TAG]   # default tag: latest
#
# Prerequisites:
#   - AWS CLI configured (aws configure) or running on an EC2/ECS instance with an IAM role
#   - Docker daemon running
#   - The ECR repository must exist: aws ecr create-repository --repository-name tessera-app
#
# Required environment variables (or pass them as exports before calling the script):
#   AWS_REGION   — e.g. us-east-1 (default: us-east-1)
#   ECR_REPO     — ECR repository name (default: tessera-app)

set -euo pipefail

REGION="${AWS_REGION:-us-east-1}"
ECR_REPO="${ECR_REPO:-tessera-app}"
IMAGE_TAG="${1:-latest}"

echo "==> Resolving AWS account ID..."
ACCOUNT_ID=$(aws sts get-caller-identity --query Account --output text)
ECR_URI="${ACCOUNT_ID}.dkr.ecr.${REGION}.amazonaws.com/${ECR_REPO}"

echo "==> Authenticating Docker with ECR at ${ECR_URI}..."
aws ecr get-login-password --region "${REGION}" \
  | docker login --username AWS --password-stdin "${ACCOUNT_ID}.dkr.ecr.${REGION}.amazonaws.com"

echo "==> Building multi-stage Docker image (tag: ${IMAGE_TAG})..."
# Build from the repo root so the Dockerfile can reference tesseraapp/ and src/
docker build \
  --tag "tessera-app:${IMAGE_TAG}" \
  --file Dockerfile \
  .

echo "==> Tagging image for ECR: ${ECR_URI}:${IMAGE_TAG}..."
docker tag "tessera-app:${IMAGE_TAG}" "${ECR_URI}:${IMAGE_TAG}"

echo "==> Pushing to ECR..."
docker push "${ECR_URI}:${IMAGE_TAG}"

echo ""
echo "✓ Pushed: ${ECR_URI}:${IMAGE_TAG}"
echo ""
echo "Next steps:"
echo "  1. Update the image URI in aws/task-definition.json:"
echo "       \"image\": \"${ECR_URI}:${IMAGE_TAG}\""
echo "  2. Register the task definition:"
echo "       aws ecs register-task-definition --cli-input-json file://aws/task-definition.json"
echo "  3. Deploy to your ECS service:"
echo "       aws ecs update-service --cluster tessera-app-cluster --service tessera-app-service --force-new-deployment"
