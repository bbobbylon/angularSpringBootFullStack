# TesseraApp — Final Implementation Guide

**Development guide for completing the remaining Phase 2 work.**
Authors: Robert C. Oliver, Jr. & Travis L. Lester · Advisor: Prof. Larry Motuzis · CPSC 69100, Lewis University

> This document combines what was proposed across the Project Proposal, the Literature Review,
> the SRS, and the Architecture & UI Design report into a single, actionable development plan.
> It is the canonical "what's left and how to finish it" reference. Each feature lists the
> exact files to touch, the configuration required, and acceptance criteria tied back to SRS
> requirement IDs.

---

## 0. Current state (ground truth)

Working branch: **`MastersProjectSRSImpl`** (ahead of `master`). Verify against your local tree before starting each item.

### Already implemented — claim as done
- **In-house auth** — BCrypt credentials, `DaoAuthenticationProvider`, disabled-until-verified accounts.
- **JWT** — access (30 min) + refresh (5 day), HMAC-SHA-512; `CustomAuthFilter`; `passwordChangedAt` invalidation (FR-JWT-1..8).
- **Refresh sessions: rotation + reuse detection** — `SessionServiceImpl`, `RefreshSession`, family/`jti`, `TOKEN_REUSE_DETECTED`, Security Center list/revoke (FR-JWT-6/7, FR-SDM).
- **TOTP authenticator MFA** — `TotpService`/`TotpServiceImpl`/`TotpUtils`, QR enrollment, challenge-bound verify, SHA-256 recovery codes (FR-MFA-4).
- **Federated login (code complete)** — `OAuth2ClientConfig`, `OAuth2LoginSuccessHandler`, `FederatedAuthController`, `FederatedProviderCatalog`, `FederatedIdentityService(Impl)`, `OAuthQuery` (FR-FA-1..6).
- **RBAC** — seven roles, permission strings, URL + `@PreAuthorize`; `AdminUserController`; org services present (FR-RBAC, FR-ORG).
- **Audit** — `UserEvent`, `EventService`, async event listener (FR-AEL).
- **Business domain** — customers, invoices, services; XLSX export (Apache POI) (FR-BM).
- **Packaging** — multi-stage `Dockerfile`; OWASP Dependency-Check in the Maven build (fails on CVSS ≥ 7); idempotent `schema.sql`; `DemoDataSeeder`.

### Remaining — the work this guide covers
| # | Feature | Status today | SRS refs |
|---|---------|--------------|----------|
| 1 | **Real SMS 2FA delivery** | Stubbed — code logged, not sent | FR-MFA-2, EIR-SW-2 |
| 2 | **Federated login go-live** | Code complete; not validated against real providers | FR-FA, EIR-SW-1 |
| 3 | **AWS CI/CD + cloud deployment** | Only legacy Azure + build-time scan | NFR-PORT, NFR-AVAIL, OR-4 |
| 4 | **Server-side org-scope + rate limiting** | Org model present; analytics/billing gated only in Angular; no general limiter | FR-ORG-2, NFR-SEC-4, FR-TPF-3 |
| 5 | **Config hardening** | API base URL / image storage / fail-fast not fully externalized | NFR-PORT-3, FR-BM-4, OR-2 |
| 6 | **Tests in CI** | ~5 test classes, run locally | NFR-MAINT-1 |

**Suggested order:** 1 → 2 → 5 → 4 → 3 → 6 (SMS and federation make the demo complete; config hardening unblocks a clean AWS deploy; org-scope/rate-limit close security gaps; CI/CD ships it; tests gate the pipeline).

---

## 1. Real SMS 2FA delivery (un-stub Twilio)

**Goal:** deliver the one-time 2FA code by SMS instead of logging it. The wiring already exists end-to-end; only the send call is disabled.

### Files
- `service/serviceimpl/NotificationServiceImpl.java` → `sendTwoFactorCode(...)` — the send is commented out; a `log.info` prints the code.
- `utils/SMSUtils.java` → `sendSMS(toNumber, messageBody)` — initializes Twilio and creates the message, but ends on `System.out.println(messageBody)` instead of returning the send result.

### Steps
1. In `SMSUtils.sendSMS`, keep the `Twilio.init(...)` + `Message.creator(...).create()` call; remove the `System.out.println` and instead log the returned message SID (`log.info("SMS queued, sid={}", message.getSid())`). Rename the misleading `FAKE_ONE` / `FAKE_TWO` constants to `ACCOUNT_SID` / `AUTH_TOKEN`.
2. In `NotificationServiceImpl.sendTwoFactorCode`, uncomment the `SMSUtils.sendSMS(phoneNumber, "Hi " + firstName + ", your 2FA code is: " + code + ...)` call. Keep it inside the existing `CompletableFuture.runAsync(...)` + `.exceptionally(...)` so a Twilio failure is logged, never blocks the HTTP thread, and surfaces an actionable error (NFR-REL-2).
3. Gate real sending behind config so dev stays free: read a flag such as `sms.enabled` (default `false`); when `false`, keep the log-only behaviour. This preserves the "no Twilio charges in dev" property while allowing a real send in staging/prod.
4. Normalize phone numbers to E.164 before sending (the current code prepends `+1`; validate length and non-US cases, or store already-normalized numbers at enrollment).

### Configuration (env vars, already referenced in `.env.example`)
```
TWILIO_ACCOUNT_SID=ACxxxxxxxx
TWILIO_AUTH_TOKEN=xxxxxxxx
TWILIO_FROM_NUMBER=+1xxxxxxxxxx
SMS_ENABLED=true            # false in dev
```
Never commit these; inject via environment / secrets manager (EIR-SW-5, DC-7).

### Acceptance criteria
- With `SMS_ENABLED=true` and a verified Twilio number, enabling MFA and logging in delivers a real SMS; the code verifies and issues tokens (FR-MFA-2/3).
- With `SMS_ENABLED=false`, behaviour is unchanged (code logged), no Twilio call is made.
- A Twilio outage produces a logged error and a user-facing "couldn't send code" message, not a hung request or a corrupt session (NFR-REL-2).

---

## 2. Federated login go-live (OAuth2 / OIDC)

**Goal:** the code path (`OAuth2LoginSuccessHandler` etc.) is complete; make it work against **real** Google, GitHub, and Microsoft credentials and validate the round trip.

### Files (all present on the working branch)
- `configuration/OAuth2ClientConfig.java` — client registrations.
- `configuration/FederatedProviderCatalog.java` — provider metadata surfaced to the SPA.
- `handler/OAuth2LoginSuccessHandler.java` — the token-exchange point: per-provider profile extraction (Google/Microsoft `sub`, GitHub numeric `id`), find-or-create, MFA parity, issues our JWT, redirects to the SPA `/oauth2/callback` with tokens in the URL **fragment**.
- `controller/FederatedAuthController.java`, `service/serviceimpl/FederatedIdentityServiceImpl.java`, `query/OAuthQuery.java`.
- `configuration/SecurityConfig.java` — confirm `.oauth2Login(...)` wires the success handler and that `/oauth2/**` + `/login/oauth2/**` are permitted.
- Frontend: the `/oauth2/callback` route that reads the fragment (`#access_token=…&refresh_token=…`, or `#mfa=totp&challenge=…`, or `#mfa=true&…`) and stores tokens / routes to the MFA screen.

### Register OAuth apps (per provider)
For each provider, create an app and set the **redirect/callback URI** to
`{BACKEND_URL}/login/oauth2/code/{provider}` (Spring Security default), e.g.
`https://api.tesseraapp.example/login/oauth2/code/google`.

| Provider | Console | Notes |
|----------|---------|-------|
| Google | Google Cloud → APIs & Services → Credentials → OAuth client (Web) | OIDC; scopes `openid email profile`; stable id = `sub` |
| GitHub | Settings → Developer settings → OAuth Apps | plain OAuth2; email may be private → handler synthesizes `<login>@users.noreply.github.com`; stable id = numeric `id` |
| Microsoft | Entra ID → App registrations | OIDC; scopes `openid email profile`; `email` may fall back to `preferred_username` |

### Configuration
```
GOOGLE_CLIENT_ID / GOOGLE_CLIENT_SECRET
GITHUB_CLIENT_ID / GITHUB_CLIENT_SECRET
MICROSOFT_CLIENT_ID / MICROSOFT_CLIENT_SECRET
UI_APP_URL=https://app.tesseraapp.example      # SPA origin for post-login redirect
```
Map these into `spring.security.oauth2.client.registration.*` in `application-prod.yml` (or the registrations built by `OAuth2ClientConfig`). Add each provider's redirect URI to its console for **both** local (`http://localhost:8080/...`) and deployed backend URLs.

### Validation checklist
- Each provider: click "Continue with …" → provider consent → callback → land in the app authenticated, with a `FEDERATED_LOGIN` audit event and a tracked session in the device list (FR-FA-4/5).
- A disabled/locked local account is refused via `/login?error=account` (FR-AUTH-5 parity).
- An MFA-enabled account is bounced to the TOTP/SMS challenge before any token is issued (FR-MFA-2).
- Only `(provider, subject)` + minimal profile are persisted — no third-party password/token (FR-FA-6, NFR-SEC-9).

### Acceptance criteria
- All three providers complete a real login end-to-end against production credentials.
- Federated and in-house sessions are indistinguishable downstream (same RBAC, MFA, audit).

---

## 3. AWS CI/CD pipeline & cloud deployment

**Goal:** ship the same container image to AWS automatically on every push to the main branch, against managed MySQL, behind TLS, passing health checks. This replaces the legacy Azure pipeline described in the README.

### Target topology (Architecture report §7.3)
```
GitHub push ─▶ CI (GitHub Actions / CodePipeline)
                 │ build+test → docker build → push
                 ▼
             Amazon ECR (image registry)
                 │ deploy
                 ▼
   ECS Fargate service  ◀── ALB + ACM (TLS)  ◀── Route 53
   (or AWS App Runner)        │
                 ├─ env/secrets ── AWS Secrets Manager / SSM Parameter Store
                 └─ JDBC ─────────▶ Amazon RDS for MySQL 8 (Multi-AZ)
```

### One-time AWS setup
1. **ECR** repository, e.g. `tesseraapp`.
2. **RDS for MySQL 8** instance; create schema `db2`; apply `src/main/resources/schema.sql` (idempotent). Set `useSSL=true&requireSSL=true` in the JDBC URL.
3. **Secrets Manager** entries for every required prod var (see below); grant the task role read access.
4. **ECS Fargate** cluster + service (or **App Runner** for less ops), task definition pointing at the ECR image, port `8080`, health check `GET /actuator/health`.
5. **ALB + ACM** certificate for TLS termination; **Route 53** record for the API and SPA hosts. Enforce HSTS (already set by `SecurityConfig`).

### Pipeline stages (GitHub Actions example)
1. **Build & test** — `mvn -B verify` (runs unit/integration tests + OWASP dependency-check; fails on CVSS ≥ 7).
2. **Image** — the existing three-stage `Dockerfile` (Node build → Maven package `-Pprod` → JRE runtime) → tag with commit SHA + `latest`.
3. **Push** — authenticate to ECR (`aws-actions/amazon-ecr-login`) and push.
4. **Deploy** — render the ECS task definition with the new image and `aws-actions/amazon-ecs-deploy-task-definition` (or `aws apprunner update-service`); wait for the service to stabilize and the health check to pass.

### Required prod environment (no `.env` in cloud — inject via Secrets Manager / task def)
```
SPRING_ACTIVE_PROFILES=prod
SPRING_DATASOURCE_URL=jdbc:mysql://<rds-endpoint>:3306/db2?useSSL=true&requireSSL=true
SPRING_DATASOURCE_USERNAME / SPRING_DATASOURCE_PASSWORD
JWT_SECRET                    # >= 32 chars; JwtSecretGuard fails startup if weak/missing
MAIL_USERNAME / MAIL_PASSWORD / MAIL_HOST / MAIL_PORT
UI_APP_URL                    # SPA origin (CORS + OAuth redirects)
TWILIO_* + SMS_ENABLED        # from §1
GOOGLE_/GITHUB_/MICROSOFT_ client id+secret   # from §2
```

### Pre-cloud checklist (from README, still open)
- [ ] Managed DB (RDS) instead of the Docker MySQL container.
- [ ] `useSSL=true` on the datasource URL.
- [ ] All prod vars set via the platform (no committed `.env`).
- [ ] Consider `ddl-auto: validate` + `schema.sql` as the single source of truth (avoid `update` in prod).
- [ ] Add the deployed SPA + API origins to the CORS whitelist in `SecurityConfig` and to each OAuth app's redirect URIs.

### Acceptance criteria
- A push to the main branch builds, tests, pushes, and deploys automatically with no manual steps (OR-4).
- The deployed app serves over HTTPS, passes `/actuator/health`, and a full login (in-house + one federated provider) works against RDS.
- Instances can be replaced without forcing re-authentication of valid-token holders (NFR-AVAIL-1/3).

---

## 4. Server-side org-scope enforcement & rate limiting

**Goal:** close the two known authorization gaps.

### 4a. Org-scope on analytics/billing (FR-ORG-2, NFR-SEC-4)
The analytics and billing endpoints are currently gated only in the Angular app. Add server-side enforcement:
- Add `@PreAuthorize` / explicit authority checks on the analytics/billing controller methods, mirroring `AdminUserController`'s scope checks (org admins limited to their organization; application admin bypasses).
- Return `403` via the existing `CustomAccessDeniedHandler` for out-of-scope access. Route guards remain a usability aid only.

### 4b. General rate limiter (FR-TPF-3)
A per-account brute-force lockout already exists (5 failures / 15 min). Add a general limiter on `login`, `register`, `password-reset`, and `mfa` submission:
- Implement a filter/interceptor (e.g. Bucket4j) keyed by IP + endpoint; return `429` with a `Retry-After` header; make limits configurable.
- For horizontal scale-out, back the buckets with a shared store (e.g. Redis / ElastiCache) so limits hold across instances.

### Acceptance criteria
- Out-of-scope analytics/billing requests return `403` even when the frontend guard is bypassed.
- Exceeding the configured request rate returns `429` + `Retry-After`; limits hold across replicas.

---

## 5. Configuration hardening

- **Frontend API origin** — replace the hardcoded `localhost:8080` base with an environment-driven value (Angular environment file / build-time replacement) so promoting a build needs no code change (NFR-PORT-3).
- **Profile-image storage** — move off the local container filesystem (`FR-BM-4`) to object storage (S3) so images survive restarts and scale across replicas; serve via a dedicated endpoint or signed URL.
- **Fail-fast config** — `JwtSecretGuard` already fails startup on a weak/missing secret; extend the same discipline (prod profile has no fallbacks) to other required vars so a misconfiguration fails at boot, not at first request.
- **Dev conveniences off in prod** — ensure simulated latency and any log-only stubs (SMS) are disabled under the `prod` profile (OR-2).

---

## 6. Automated tests in CI

- Grow the existing set (login-enumeration, global exception handler, customer service, JPA schema-sync) into a suite covering the **security seams**: token rotation + reuse detection, `passwordChangedAt` invalidation, RBAC allow/deny at URL and method level, org-scope, TOTP enroll/verify, and the anti-enumeration guarantee.
- Run `mvn verify` in the CI stage (§3) so the pipeline fails on a red test or a CVSS ≥ 7 dependency.
- Add at least one integration test per remaining feature (SMS toggle, a federated find-or-create, a rate-limit 429) as its acceptance gate.

---

## Definition of done (project-level)
1. In-house **and** federated login work end-to-end against real credentials, both honouring MFA.
2. SMS 2FA delivers real codes when enabled; dev stays free.
3. The app is deployed on AWS via an automated pipeline, over TLS, on RDS, passing health checks.
4. Org-scope and rate limits are enforced **server-side**; the frontend is not the security boundary.
5. Config is fully externalized; the same image runs in every environment unchanged.
6. The security-critical paths are covered by tests that run in CI.

*Every item above traces to an SRS requirement ID; keep the traceability matrix (SRS Appendix B) updated as each lands.*
