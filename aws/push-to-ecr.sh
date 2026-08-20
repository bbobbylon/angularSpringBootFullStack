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
if [[ "$IMAGE_TAG" == "latest" ]]; then
  echo "  setup.sh always points the task definition at the ':latest' tag, so nothing in"
  echo "  aws/task-definition.json needs to change — just force ECS to pull the image you"
  echo "  just pushed:"
  echo "       aws ecs update-service --cluster tessera-app-cluster --service tessera-app-service \\"
  echo "         --force-new-deployment --region ${REGION}"
else
  echo "  You pushed a non-'latest' tag (${IMAGE_TAG}), but setup.sh always fills the task"
  echo "  definition's image with ':latest'. To actually deploy this specific tag, export"
  echo "  ECR_IMAGE_URI=\"${ECR_URI}:${IMAGE_TAG}\" and re-run setup.sh's Step 8 fill+register"
  echo "  (see aws/README.md), then force a new deployment as above."
fi
