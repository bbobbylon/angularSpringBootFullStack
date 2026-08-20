#!/usr/bin/env bash
# ──────────────────────────────────────────────────────────────────────────────
# setup-cloudfront.sh — Put HTTPS in front of TesseraApp WITHOUT owning a domain.
#
# This is "Route B" from documentation/FUTURE-ENHANCEMENTS.md §6.8. It creates a
# CloudFront distribution in front of the existing ALB and uses CloudFront's
# auto-issued *.cloudfront.net certificate.
#
# WHY THIS EXISTS: ACM will not issue a certificate for the ALB's own
# *.elb.amazonaws.com hostname — AWS owns that name, not you — so there is no
# path to HTTPS on the current URL. CloudFront sidesteps it: AWS will happily
# give you HTTPS on a name IT chooses, because it already holds a certificate
# for that name. https://d1234abcd5678.cloudfront.net is a real, publicly
# trusted origin and IS accepted by Google and Entra as an OAuth redirect URI —
# which is what unblocks Google and Microsoft sign-in.
#
# Usage:
#   AWS_REGION=us-east-1 ./aws/setup-cloudfront.sh
#   ./aws/setup-cloudfront.sh --no-wait          # don't block on deployment
#
# It is IDEMPOTENT — re-running finds the existing distribution for this ALB
# origin and prints it rather than creating a second one.
#
# THREE THINGS THAT MUST BE RIGHT for this app specifically (all handled here):
#   1. Forward the Authorization header. CloudFront's DEFAULT cache policy
#      STRIPS it, which would 401 every authenticated request. We use the
#      managed CachingDisabled cache policy + AllViewer origin request policy so
#      headers, cookies and query strings pass through untouched. Caching is off
#      on purpose: this origin is a live API, not a static site.
#   2. Allow every HTTP method. CloudFront defaults to GET/HEAD only, which
#      would break every POST/PATCH/DELETE in the API.
#   3. TRUSTED_PROXY_COUNT must become 2 (CloudFront + ALB both append to
#      X-Forwarded-For). This script does NOT change it — see the next-steps
#      output; it is a task-definition change.
#
# AFTER this script succeeds you still must (it prints these too):
#   A. Set TRUSTED_PROXY_COUNT=2 and APP_DOMAIN/UI_APP_URL to the https://
#      CloudFront origin, then RE-REGISTER the task definition and force a new
#      deployment — these are read once at container start.
#   B. Add https://<cf-domain>/login/oauth2/code/{google,github,microsoft} in
#      each provider console.
#
# Dependencies: aws CLI v2
# ──────────────────────────────────────────────────────────────────────────────
set -euo pipefail

# ── Defaults ──────────────────────────────────────────────────────────────────
REGION="${AWS_REGION:-us-east-1}"
ALB_NAME="${ALB_NAME:-tessera-app-alb}"
DO_WAIT=true

# Managed policy ids are global AWS constants, identical in every account.
# CachingDisabled: forward nothing to the cache key, cache nothing.
CACHE_POLICY_ID="4135ea2d-6df8-44a3-9df3-4b5a84be39ad"
# AllViewer: forward ALL viewer headers (incl. Authorization), cookies, query strings.
ORIGIN_REQUEST_POLICY_ID="216adef6-5c7f-47e4-b989-5492eafa07d3"

# ── Terminal colours ───────────────────────────────────────────────────────────
GREEN='\033[0;32m'; YELLOW='\033[1;33m'; RED='\033[0;31m'; BLUE='\033[0;34m'; NC='\033[0m'
ok()   { echo -e "${GREEN}  ✓  $1${NC}"; }
warn() { echo -e "${YELLOW}  !  $1${NC}"; }
info() { echo -e "${BLUE}  ·  $1${NC}"; }
die()  { echo -e "${RED}  ✗  $1${NC}" >&2; exit 1; }

while [[ $# -gt 0 ]]; do
  case "$1" in
    --region)   REGION="$2";   shift 2 ;;
    --alb-name) ALB_NAME="$2"; shift 2 ;;
    --no-wait)  DO_WAIT=false; shift ;;
    -h|--help)  sed -n '2,45p' "$0"; exit 0 ;;
    *)          die "Unknown argument: $1" ;;
  esac
done

export AWS_PAGER=""

echo ""
echo "──────────────────────────────────────────────────────────────"
echo "  TesseraApp — CloudFront HTTPS (no domain required)"
echo "──────────────────────────────────────────────────────────────"
echo "  Region : ${REGION}"
echo "  ALB    : ${ALB_NAME}"
echo ""

# ── Step 1 — find the ALB ─────────────────────────────────────────────────────
info "Looking up the load balancer…"
ALB_DNS="$(aws elbv2 describe-load-balancers \
  --names "$ALB_NAME" --region "$REGION" \
  --query 'LoadBalancers[0].DNSName' --output text 2>/dev/null || true)"

[[ -n "$ALB_DNS" && "$ALB_DNS" != "None" ]] \
  || die "Load balancer '${ALB_NAME}' not found in ${REGION}. Run aws/setup.sh first."
ok "Origin will be: ${ALB_DNS}"

# ── Step 2 — idempotency: is there already a distribution for this origin? ────
info "Checking for an existing distribution…"
EXISTING_ID="$(aws cloudfront list-distributions \
  --query "DistributionList.Items[?Origins.Items[0].DomainName=='${ALB_DNS}'].Id | [0]" \
  --output text 2>/dev/null || true)"

if [[ -n "$EXISTING_ID" && "$EXISTING_ID" != "None" ]]; then
  EXISTING_DOMAIN="$(aws cloudfront get-distribution --id "$EXISTING_ID" \
    --query 'Distribution.DomainName' --output text)"
  EXISTING_STATUS="$(aws cloudfront get-distribution --id "$EXISTING_ID" \
    --query 'Distribution.Status' --output text)"
  warn "A distribution already fronts this ALB — not creating a second one."
  ok   "Id     : ${EXISTING_ID}"
  ok   "Domain : https://${EXISTING_DOMAIN}"
  ok   "Status : ${EXISTING_STATUS}"
  CF_ID="$EXISTING_ID"; CF_DOMAIN="$EXISTING_DOMAIN"
else
  # ── Step 3 — create it ──────────────────────────────────────────────────────
  info "Creating the distribution…"

  # CallerReference must be unique per create call; it is CloudFront's
  # idempotency token. We never reuse one — the guard above is what makes
  # re-running this script safe.
  CALLER_REF="tessera-app-cf-$(date +%Y%m%d%H%M%S)"

  # Deliberately a RELATIVE path in the working directory, not mktemp. Under Git
  # Bash on Windows, mktemp returns an MSYS path (/tmp/tmp.XXXX) that the native
  # aws.exe cannot resolve — it fails with "Unable to load paramfile". A relative
  # name resolves correctly for both the native and POSIX CLIs. Same class of
  # MSYS path hazard setup.sh documents at its top.
  CONFIG_FILE="tessera-cf-config-$$.json"
  trap 'rm -f "$CONFIG_FILE"' EXIT

  cat > "$CONFIG_FILE" <<JSON
{
  "CallerReference": "${CALLER_REF}",
  "Comment": "TesseraApp - HTTPS in front of the ALB (no custom domain)",
  "Enabled": true,
  "HttpVersion": "http2and3",
  "IsIPV6Enabled": true,
  "PriceClass": "PriceClass_100",
  "Origins": {
    "Quantity": 1,
    "Items": [
      {
        "Id": "tessera-alb-origin",
        "DomainName": "${ALB_DNS}",
        "OriginPath": "",
        "ConnectionAttempts": 3,
        "ConnectionTimeout": 10,
        "CustomHeaders": {
          "Quantity": 1,
          "Items": [
            {
              "HeaderName": "X-Forwarded-Proto",
              "HeaderValue": "https"
            }
          ]
        },
        "CustomOriginConfig": {
          "HTTPPort": 80,
          "HTTPSPort": 443,
          "OriginProtocolPolicy": "http-only",
          "OriginSslProtocols": { "Quantity": 1, "Items": ["TLSv1.2"] },
          "OriginReadTimeout": 60,
          "OriginKeepaliveTimeout": 5
        }
      }
    ]
  },
  "DefaultCacheBehavior": {
    "TargetOriginId": "tessera-alb-origin",
    "ViewerProtocolPolicy": "redirect-to-https",
    "Compress": true,
    "AllowedMethods": {
      "Quantity": 7,
      "Items": ["GET", "HEAD", "POST", "PUT", "PATCH", "OPTIONS", "DELETE"],
      "CachedMethods": { "Quantity": 2, "Items": ["GET", "HEAD"] }
    },
    "CachePolicyId": "${CACHE_POLICY_ID}",
    "OriginRequestPolicyId": "${ORIGIN_REQUEST_POLICY_ID}"
  }
}
JSON

  CREATE_OUT="$(aws cloudfront create-distribution \
    --distribution-config "file://${CONFIG_FILE}" \
    --query 'Distribution.{Id:Id,Domain:DomainName}' --output text)"

  CF_ID="$(echo "$CREATE_OUT" | awk '{print $2}')"
  CF_DOMAIN="$(echo "$CREATE_OUT" | awk '{print $1}')"

  ok "Created distribution ${CF_ID}"
  ok "HTTPS origin: https://${CF_DOMAIN}"
fi

# ── Step 4 — wait for deployment ──────────────────────────────────────────────
if [[ "$DO_WAIT" == true ]]; then
  echo ""
  info "Waiting for the distribution to deploy to the edge (typically 3-10 min)…"
  info "Ctrl-C is safe — deployment continues without this script."
  if aws cloudfront wait distribution-deployed --id "$CF_ID" 2>/dev/null; then
    ok "Distribution is Deployed."
  else
    warn "Wait timed out. Check status with:"
    echo "      aws cloudfront get-distribution --id ${CF_ID} --query 'Distribution.Status'"
  fi
fi

# ── Next steps ────────────────────────────────────────────────────────────────
cat <<EOF

──────────────────────────────────────────────────────────────
  HTTPS origin ready:  https://${CF_DOMAIN}
──────────────────────────────────────────────────────────────

STILL TO DO — none of this is automatic:

  A. Re-point the app at the HTTPS origin AND fix the proxy depth.
     Both are read ONCE at container start, so the task definition must be
     re-registered and the service rolled. Editing nothing else does nothing.

       APP_DOMAIN / UI_APP_URL = https://${CF_DOMAIN}
       TRUSTED_PROXY_COUNT     = 2      <-- was 1; CloudFront + ALB both
                                            append to X-Forwarded-For. Leave it
                                            at 1 and the anomaly detector and
                                            rate limiter degrade SILENTLY.

  B. Register the OAuth callbacks in each provider console:

       https://${CF_DOMAIN}/login/oauth2/code/google
       https://${CF_DOMAIN}/login/oauth2/code/github
       https://${CF_DOMAIN}/login/oauth2/code/microsoft

     Keep the localhost entries — Google and Entra accept a list. (A GitHub
     OAuth App accepts only ONE callback URL, so use a second OAuth App for the
     deployed environment.)

  C. Verify the app generates https:// redirect URIs, not http://:

       curl -si "https://${CF_DOMAIN}/oauth2/authorization/github" | grep -i location

     The redirect_uri= parameter in that Location header must start with
     https://${CF_DOMAIN}. If it says http://, the ALB overwrote
     X-Forwarded-Proto and the redirect URI needs to be pinned explicitly.

  NOTE: the ALB stays reachable on plain http:// — CloudFront does not close it.
  Restrict the ALB security group to CloudFront's managed prefix list
  (com.amazonaws.global.cloudfront.origin-facing) to force all traffic through
  CloudFront once you are satisfied it works.

EOF
