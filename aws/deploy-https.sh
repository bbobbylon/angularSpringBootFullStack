#!/usr/bin/env bash
# ──────────────────────────────────────────────────────────────────────────────
# deploy-https.sh — Put TLS in front of TesseraApp's ALB.
#
# This is "Step 8" from README.md, automated. It requests an ACM certificate for
# a domain you own, validates it via DNS, attaches it to an HTTPS:443 listener on
# the existing load balancer, and turns the plain :80 listener into a redirect.
#
# Usage:
#   AWS_REGION=us-east-1 ./aws/deploy-https.sh --domain app.example.com
#
#   # reuse a certificate you already have
#   ./aws/deploy-https.sh --domain app.example.com --cert-arn arn:aws:acm:...
#
#   # keep :80 serving traffic instead of redirecting (not recommended)
#   ./aws/deploy-https.sh --domain app.example.com --no-redirect
#
# What it does:
#   1. Finds the existing ALB and its target group (created by setup.sh)
#   2. Requests — or reuses — an ACM certificate, DNS validation
#   3. Auto-creates the validation CNAME if the zone is in Route 53;
#      otherwise prints the exact record and waits for you to add it
#   4. Waits for the certificate to reach ISSUED
#   5. Opens :443 on the ALB security group
#   6. Creates (or updates) the HTTPS:443 listener → existing target group
#   7. Rewrites the :80 listener as a 301 redirect to :443
#
# It is IDEMPOTENT — re-running it reuses whatever already exists and only fills
# in what is missing. Safe to run again after a partial failure.
#
# WHY A DOMAIN IS REQUIRED: ACM will not issue a certificate without proof that
# you control the domain's DNS, and AWS will not let you request one for the
# ALB's own *.elb.amazonaws.com name — that name belongs to AWS, not to you.
# There is no certificate available for the bare ALB hostname, from ACM or from
# anyone else. If you need HTTPS *without* a domain, put CloudFront in front of
# the ALB instead and use its auto-issued *.cloudfront.net certificate — see
# documentation/FUTURE-ENHANCEMENTS.md §6.8 for that route and its three gotchas.
#
# AFTER this script succeeds you still must (it prints these too):
#   A. Set APP_DOMAIN/UI_APP_URL to https://<domain> and RE-REGISTER the task
#      definition — the value is read once at container start, so changing the
#      variable without rolling the service does nothing.
#   B. Add https://<domain>/login/oauth2/code/{google,github,microsoft} in each
#      provider console. This is what unblocks Google and Microsoft sign-in,
#      which reject non-https redirect URIs outside localhost.
#
# Dependencies: aws CLI v2, jq
# ──────────────────────────────────────────────────────────────────────────────
set -euo pipefail

# Git Bash rewrites bare arguments that look like POSIX paths before exec'ing a
# native aws.exe (see setup.sh's longer note). Nothing here passes one: the redirect
# action deliberately omits Host/Path/Query, because the ALB already defaults them to
# "#{host}" / "/#{path}" / "#{query}" — spelling them out would hand MSYS a literal
# "/#{path}" to mangle for no behavioural gain.

# ── Defaults ──────────────────────────────────────────────────────────────────
REGION="${AWS_REGION:-us-east-1}"
ALB_NAME="${ALB_NAME:-tessera-app-alb}"
TG_NAME="${TG_NAME:-tessera-app-tg}"
DOMAIN="${APP_DOMAIN_NAME:-}"
CERT_ARN="${CERT_ARN:-}"
HOSTED_ZONE_ID="${HOSTED_ZONE_ID:-}"
DO_REDIRECT=true
INCLUDE_WWW=true
WAIT_MINUTES="${WAIT_MINUTES:-30}"

# ── Terminal colours ───────────────────────────────────────────────────────────
GREEN='\033[0;32m'; YELLOW='\033[1;33m'; RED='\033[0;31m'; BLUE='\033[0;34m'; NC='\033[0m'
ok()   { echo -e "${GREEN}  ✓  $1${NC}"; }
info() { echo -e "${BLUE}  →  $1${NC}"; }
warn() { echo -e "${YELLOW}  !  $1${NC}"; }
die()  { echo -e "${RED}  ✗  $1${NC}" >&2; exit 1; }

# ── Argument parsing ───────────────────────────────────────────────────────────
while [[ $# -gt 0 ]]; do
  case "$1" in
    --domain)          DOMAIN="$2";          shift 2 ;;
    --cert-arn)        CERT_ARN="$2";        shift 2 ;;
    --alb-name)        ALB_NAME="$2";        shift 2 ;;
    --target-group)    TG_NAME="$2";         shift 2 ;;
    --hosted-zone-id)  HOSTED_ZONE_ID="$2";  shift 2 ;;
    --region)          REGION="$2";          shift 2 ;;
    --wait-minutes)    WAIT_MINUTES="$2";    shift 2 ;;
    --no-redirect)     DO_REDIRECT=false;    shift ;;
    --no-www)          INCLUDE_WWW=false;    shift ;;
    -h|--help)         sed -n '2,50p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *)                 die "Unknown argument: $1  (try --help)" ;;
  esac
done

# ── Preflight ──────────────────────────────────────────────────────────────────
echo ""
echo "── Preflight ────────────────────────────────────────────────"

command -v aws >/dev/null 2>&1 || die "aws CLI not found. Install AWS CLI v2."
command -v jq  >/dev/null 2>&1 || die "jq not found. brew install jq / apt install jq"
[[ -n "$DOMAIN" ]] || die "--domain is required (e.g. --domain app.example.com)"

# Reject the one input that cannot possibly work, with the reason, rather than
# letting ACM fail several minutes later with a vaguer message.
if [[ "$DOMAIN" == *".elb.amazonaws.com" || "$DOMAIN" == *".amazonaws.com" ]]; then
  die "ACM cannot issue a certificate for '${DOMAIN}' — AWS owns that name, so you
     cannot prove control of its DNS. Use a domain you registered, or put
     CloudFront in front of the ALB for free HTTPS on a *.cloudfront.net name.
     See documentation/FUTURE-ENHANCEMENTS.md §6.8."
fi

ACCOUNT=$(aws sts get-caller-identity --query Account --output text 2>/dev/null) \
  || die "AWS credentials are not configured. Run: aws configure"
ok "AWS account ${ACCOUNT}, region ${REGION}"

# The certificate must live in the SAME REGION as the load balancer. (The
# us-east-1 rule people remember is CloudFront's, not the ALB's — attaching a
# cert from another region to an ALB fails with a ValidationError.)
ALB_ARN=$(aws elbv2 describe-load-balancers --names "$ALB_NAME" --region "$REGION" \
  --query 'LoadBalancers[0].LoadBalancerArn' --output text 2>/dev/null || echo "None")
[[ "$ALB_ARN" != "None" && -n "$ALB_ARN" ]] \
  || die "Load balancer '${ALB_NAME}' not found in ${REGION}. Run ./aws/setup.sh first."

ALB_DNS=$(aws elbv2 describe-load-balancers --load-balancer-arns "$ALB_ARN" --region "$REGION" \
  --query 'LoadBalancers[0].DNSName' --output text)
ALB_ZONE=$(aws elbv2 describe-load-balancers --load-balancer-arns "$ALB_ARN" --region "$REGION" \
  --query 'LoadBalancers[0].CanonicalHostedZoneId' --output text)
ok "ALB ${ALB_NAME} → ${ALB_DNS}"

TG_ARN=$(aws elbv2 describe-target-groups --names "$TG_NAME" --region "$REGION" \
  --query 'TargetGroups[0].TargetGroupArn' --output text 2>/dev/null || echo "None")
[[ "$TG_ARN" != "None" && -n "$TG_ARN" ]] \
  || die "Target group '${TG_NAME}' not found in ${REGION}. Run ./aws/setup.sh first."
ok "Target group ${TG_NAME}"

# Add www as a SAN only for an apex domain (example.com), never for a subdomain —
# "www.app.example.com" is not a name anybody will type.
SAN_ARGS=()
LABEL_COUNT=$(awk -F. '{print NF}' <<<"$DOMAIN")
if [[ "$INCLUDE_WWW" == true && "$LABEL_COUNT" -eq 2 ]]; then
  SAN_ARGS=(--subject-alternative-names "www.${DOMAIN}")
  info "Apex domain detected — www.${DOMAIN} will be added as a SAN (--no-www to skip)"
fi

# ── 1. Certificate ─────────────────────────────────────────────────────────────
echo ""
echo "── Step 1/4 — ACM certificate ───────────────────────────────"

if [[ -z "$CERT_ARN" ]]; then
  # Reuse an existing cert for this exact domain if one is already ISSUED or
  # mid-validation. Requesting a duplicate is legal but leaves orphans behind and
  # makes a re-run look like it did nothing.
  # No --includes filter: request-certificate below defaults to RSA_2048, which is
  # exactly what an unfiltered list-certificates returns, so adding one would only
  # create a way for the lookup to miss the cert it just made.
  CERT_ARN=$(aws acm list-certificates --region "$REGION" \
    --query "CertificateSummaryList[?DomainName=='${DOMAIN}'].CertificateArn | [0]" \
    --output text 2>/dev/null || echo "None")
fi

if [[ "$CERT_ARN" == "None" || -z "$CERT_ARN" ]]; then
  info "Requesting a new certificate for ${DOMAIN}…"
  CERT_ARN=$(aws acm request-certificate \
    --domain-name "$DOMAIN" \
    "${SAN_ARGS[@]}" \
    --validation-method DNS \
    --region "$REGION" \
    --query CertificateArn --output text)
  ok "Requested ${CERT_ARN}"
else
  ok "Reusing existing certificate ${CERT_ARN}"
fi

CERT_STATUS=$(aws acm describe-certificate --certificate-arn "$CERT_ARN" --region "$REGION" \
  --query 'Certificate.Status' --output text)
info "Status: ${CERT_STATUS}"

# ── 2. DNS validation ──────────────────────────────────────────────────────────
echo ""
echo "── Step 2/4 — DNS validation ────────────────────────────────"

if [[ "$CERT_STATUS" == "ISSUED" ]]; then
  ok "Already validated — skipping"
else
  # ACM populates ResourceRecord asynchronously; immediately after request-certificate
  # the field is null. Poll briefly rather than printing "null" as the record to add.
  info "Waiting for ACM to publish the validation record…"
  for _ in $(seq 1 30); do
    RECORDS=$(aws acm describe-certificate --certificate-arn "$CERT_ARN" --region "$REGION" \
      --query 'Certificate.DomainValidationOptions[?ValidationMethod==`DNS`].ResourceRecord' \
      --output json)
    [[ "$(jq 'map(select(. != null)) | length' <<<"$RECORDS")" -gt 0 ]] && break
    sleep 2
  done
  RECORDS=$(jq 'map(select(. != null)) | unique_by(.Name)' <<<"$RECORDS")
  [[ "$(jq 'length' <<<"$RECORDS")" -gt 0 ]] || die "ACM never published a validation record."

  # Find the Route 53 hosted zone that actually hosts this domain, if any. Match the
  # LONGEST suffix: with both "example.com" and "app.example.com" as zones, the record
  # belongs in the more specific one, and writing it to the parent silently never
  # resolves.
  if [[ -z "$HOSTED_ZONE_ID" ]]; then
    HOSTED_ZONE_ID=$(aws route53 list-hosted-zones --output json 2>/dev/null \
      | jq -r --arg d "${DOMAIN}." '
          [ .HostedZones[]
            | select(.Config.PrivateZone == false)
            | select($d | endswith(.Name)) ]
          | sort_by(.Name | length) | last | .Id // empty' | sed 's|/hostedzone/||' || true)
  fi

  if [[ -n "$HOSTED_ZONE_ID" ]]; then
    ok "Route 53 hosted zone ${HOSTED_ZONE_ID} — creating the record(s) automatically"
    # Inline the JSON rather than using file:// — a native aws.exe on Git Bash
    # resolves file:// URIs unreliably even when the file exists (see README
    # troubleshooting). UPSERT so a re-run is not an error.
    BATCH=$(jq -n --argjson recs "$RECORDS" '{
      Comment: "ACM DNS validation for TesseraApp",
      Changes: ($recs | map({
        Action: "UPSERT",
        ResourceRecordSet: {
          Name: .Name, Type: .Type, TTL: 300,
          ResourceRecords: [{ Value: .Value }]
        }
      }))
    }')
    CHANGE_ID=$(aws route53 change-resource-record-sets \
      --hosted-zone-id "$HOSTED_ZONE_ID" \
      --change-batch "$BATCH" \
      --query 'ChangeInfo.Id' --output text)
    info "Submitted ${CHANGE_ID}; waiting for propagation…"
    aws route53 wait resource-record-sets-changed --id "$CHANGE_ID" || true
    ok "Validation record(s) live"
  else
    warn "No Route 53 hosted zone found for ${DOMAIN} — add these record(s) at your DNS host:"
    echo ""
    jq -r '.[] | "     TYPE : \(.Type)\n     NAME : \(.Name)\n     VALUE: \(.Value)\n"' <<<"$RECORDS"
    echo "     (Most registrars strip the trailing dot and the base domain automatically."
    echo "      If yours rejects the full name, enter only the leading _xxxx label.)"
    echo ""
    info "This script will now poll until ACM sees them (up to ${WAIT_MINUTES} minutes)."
  fi

  info "Waiting for the certificate to be issued…"
  DEADLINE=$(( SECONDS + WAIT_MINUTES * 60 ))
  while (( SECONDS < DEADLINE )); do
    CERT_STATUS=$(aws acm describe-certificate --certificate-arn "$CERT_ARN" --region "$REGION" \
      --query 'Certificate.Status' --output text)
    case "$CERT_STATUS" in
      ISSUED)  ok "Certificate issued"; break ;;
      FAILED|VALIDATION_TIMED_OUT|REVOKED)
        die "Certificate ended in status ${CERT_STATUS}.
     If this is 'Additional verification required', the domain is one ACM flags for
     manual review — free dynamic-DNS hosts and the .tk/.ml/.ga/.cf TLDs are the
     usual causes. A normal paid domain avoids this entirely." ;;
      *) sleep 15 ;;
    esac
  done
  [[ "$CERT_STATUS" == "ISSUED" ]] \
    || die "Timed out after ${WAIT_MINUTES}m with status ${CERT_STATUS}. The record may not have
     propagated yet — re-run this script, it will pick up where it left off."
fi

# ── 3. Security group + HTTPS listener ─────────────────────────────────────────
echo ""
echo "── Step 3/4 — HTTPS listener ────────────────────────────────"

# setup.sh opened :80 on the ALB security group but not :443, so without this the
# listener comes up and every request times out at the security group instead.
ALB_SGS=$(aws elbv2 describe-load-balancers --load-balancer-arns "$ALB_ARN" --region "$REGION" \
  --query 'LoadBalancers[0].SecurityGroups' --output text)
for SG in $ALB_SGS; do
  if aws ec2 authorize-security-group-ingress --group-id "$SG" --protocol tcp --port 443 \
       --cidr 0.0.0.0/0 --region "$REGION" >/dev/null 2>&1; then
    ok "Opened :443 on ${SG}"
  else
    ok "Port 443 already open on ${SG}"
  fi
done

HTTPS_ARN=$(aws elbv2 describe-listeners --load-balancer-arn "$ALB_ARN" --region "$REGION" \
  --query 'Listeners[?Port==`443`].ListenerArn | [0]' --output text 2>/dev/null || echo "None")

if [[ "$HTTPS_ARN" == "None" || -z "$HTTPS_ARN" ]]; then
  HTTPS_ARN=$(aws elbv2 create-listener \
    --load-balancer-arn "$ALB_ARN" \
    --protocol HTTPS --port 443 \
    --certificates "CertificateArn=${CERT_ARN}" \
    --ssl-policy ELBSecurityPolicy-TLS13-1-2-2021-06 \
    --default-actions "Type=forward,TargetGroupArn=${TG_ARN}" \
    --region "$REGION" \
    --query 'Listeners[0].ListenerArn' --output text)
  ok "Created HTTPS:443 listener"
else
  aws elbv2 modify-listener --listener-arn "$HTTPS_ARN" \
    --certificates "CertificateArn=${CERT_ARN}" \
    --ssl-policy ELBSecurityPolicy-TLS13-1-2-2021-06 \
    --default-actions "Type=forward,TargetGroupArn=${TG_ARN}" \
    --region "$REGION" >/dev/null
  ok "Updated the existing HTTPS:443 listener"
fi

# ── 4. Redirect :80 → :443 ─────────────────────────────────────────────────────
echo ""
echo "── Step 4/4 — HTTP redirect ─────────────────────────────────"

HTTP_ARN=$(aws elbv2 describe-listeners --load-balancer-arn "$ALB_ARN" --region "$REGION" \
  --query 'Listeners[?Port==`80`].ListenerArn | [0]' --output text 2>/dev/null || echo "None")

if [[ "$DO_REDIRECT" != true ]]; then
  warn "Skipping the redirect (--no-redirect). :80 still serves the app in the clear."
elif [[ "$HTTP_ARN" == "None" || -z "$HTTP_ARN" ]]; then
  warn "No :80 listener found — nothing to redirect."
else
  # 301, not 302: a permanent redirect is what lets browsers and HSTS remember the
  # upgrade, which is the whole point of turning TLS on.
  aws elbv2 modify-listener --listener-arn "$HTTP_ARN" --region "$REGION" \
    --default-actions 'Type=redirect,RedirectConfig={Protocol=HTTPS,Port=443,StatusCode=HTTP_301}' \
    >/dev/null
  ok ":80 now redirects to :443 (HTTP 301)"
fi

# ── Summary ────────────────────────────────────────────────────────────────────
echo ""
echo -e "${GREEN}════════════════════════════════════════════════════════════${NC}"
echo -e "${GREEN}  TLS is live${NC}"
echo -e "${GREEN}════════════════════════════════════════════════════════════${NC}"
echo ""
echo "  Certificate : ${CERT_ARN}"
echo "  ALB DNS     : ${ALB_DNS}"
echo "  ALB zone id : ${ALB_ZONE}"
echo ""

if [[ -n "$HOSTED_ZONE_ID" ]]; then
  echo "  Point the domain at the ALB (Route 53 alias A record):"
  echo ""
  echo "    aws route53 change-resource-record-sets --hosted-zone-id ${HOSTED_ZONE_ID} \\"
  echo "      --change-batch '{\"Changes\":[{\"Action\":\"UPSERT\",\"ResourceRecordSet\":{"
  echo "        \"Name\":\"${DOMAIN}\",\"Type\":\"A\",\"AliasTarget\":{"
  echo "          \"HostedZoneId\":\"${ALB_ZONE}\",\"DNSName\":\"${ALB_DNS}\","
  echo "          \"EvaluateTargetHealth\":false}}}]}'"
else
  echo "  Point the domain at the ALB at your DNS host:"
  echo "    CNAME  ${DOMAIN}  →  ${ALB_DNS}"
  echo "    (An apex domain cannot be a CNAME — use your host's ALIAS/ANAME record,"
  echo "     or move the zone to Route 53 and use an alias A record.)"
fi

echo ""
echo -e "${YELLOW}  Two manual steps remain — HTTPS alone does not finish this:${NC}"
echo ""
echo "   A. Re-point the app at the new origin, then ROLL the service:"
echo "        - set APP_DOMAIN / UI_APP_URL to  https://${DOMAIN}"
echo "        - re-register the task definition, then:"
echo "            aws ecs update-service --cluster tessera-app-cluster \\"
echo "              --service tessera-app-service --force-new-deployment --region ${REGION}"
echo "      The value is read ONCE at container start. Editing the variable without"
echo "      rolling the service changes nothing, and CORS + verification-email links"
echo "      will keep pointing at the old http:// origin."
echo ""
echo "   B. Register the new callbacks in each provider console:"
echo "        https://${DOMAIN}/login/oauth2/code/google"
echo "        https://${DOMAIN}/login/oauth2/code/github"
echo "        https://${DOMAIN}/login/oauth2/code/microsoft"
echo "      Keep the localhost entries — all three providers accept a list."
echo "      THIS is what unblocks Google and Microsoft sign-in: both reject"
echo "      non-https redirect URIs everywhere except localhost, which is why"
echo "      only GitHub worked on the plain-HTTP ALB."
echo ""
echo "  Verify once DNS propagates:"
echo "    curl -sI https://${DOMAIN}/actuator/health | head -1"
echo "    curl -sI http://${DOMAIN}/            | head -1   # expect 301"
echo ""
