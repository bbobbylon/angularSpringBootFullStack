# Implementation 2 — Software System Implementation & UI Design
### Phase 2 (Final) Report — SecureCapita

| | |
|---|---|
| **Author** | [Your Name] |
| **Co-author** | [Second Author] |
| **Course** | [Course Code / Title] |
| **Institution** | [Institution] |
| **Date** | [Date] |
| **Deliverable** | Implementation 2 — Software System Implementation (final) |
| **Companion** | Implementation video — see [`software-implementation-2-video-script.md`](software-implementation-2-video-script.md) |

---

## 1. Introduction

This report documents **Phase 2 (final)** of SecureCapita: the complete software implementation, an explanation of how the code is structured and works, and the hardening carried out since the Phase 1 prototype. It accompanies a video in which the implementation and code are explained.

Where Phase 1 proved the concept end-to-end, Phase 2 is about the **realised system** — the actual modules, the security seams, the data layer, the frontend, and how they fit together — described at the level a reviewer or future maintainer needs.

---

## 2. Phase 2 objectives

| ID | Objective | Status |
|----|-----------|:------:|
| P2-1 | Complete the security feature set (rotation, reuse detection, org-scoped admin) | ✅ |
| P2-2 | Consolidate schema management into an idempotent script | ✅ |
| P2-3 | Comprehensive developer & API documentation | ✅ |
| P2-4 | Build/packaging via multi-stage Docker + CI/CD | ✅ |
| P2-5 | Automated test suite | ⚠ Partial (a recognised gap) |
| P2-6 | Externalise frontend API origin / cloud-ready image storage | ⏳ Future work |

---

## 3. The complete system

The final system implements the full SRS scope:

- **Authentication:** registration + email verification, login, logout, password reset, stateless JWT access tokens, server-tracked rotating refresh sessions with reuse detection, brute-force throttling.
- **MFA:** authenticator-app TOTP (QR enrollment, challenge-bound login verification, single-use recovery codes); SMS 2FA (stubbed).
- **Federation:** OAuth2/OIDC login with find-or-create account linking.
- **Authorization:** seven-role permission-based RBAC, enforced at URL and method level, with organization-scoped administration.
- **Administration:** user directory, role reassignment, account-state control — audited.
- **Audit:** 15 event types recorded per user via an event-driven pipeline.
- **Business domain:** customers & invoices CRUD, dashboard statistics, XLSX export.
- **Self-service:** profile, password, MFA, and session/device management.

---

## 4. How the code is organised

A layered backend and a standalone-component frontend (full map: [`documentation/architecture.md`](../documentation/architecture.md)).

**Backend** (`com.bob.angularspringbootfullstack`):
```
controller → service/serviceimpl → repo/repoimpl → MySQL
            (cross-cutting: filter, tokenprovider, handler, configuration, event/listener)
```
- `controller/` — 6 REST controllers; every response uses the `HttpResponse` envelope.
- `service/serviceimpl/` — business logic behind interfaces.
- `repo/repoimpl/` — `JdbcTemplate` for identity; Spring Data JPA for the business domain.
- `query/`, `rowmapper/` — SQL constants and `ResultSet` mappers for the JDBC layer.

**Frontend** (`securecapitaapp/src/app`): `app.config.ts` (providers), `app.routes.ts` (lazy routes + guards), `features/`, `service/`, `guard/`, `interceptor/`.

---

## 5. Security implementation (the core seams)

Explained in depth in [`documentation/security.md`](../documentation/security.md); the load-bearing classes:

- **`SecurityConfig`** — the `SecurityFilterChain`: CSRF off (stateless), CORS, `STATELESS` sessions, the ordered authority rules, OAuth2 login, and the custom 401/403 handlers. Registers `CustomAuthFilter` before `UsernamePasswordAuthenticationFilter`.
- **`CustomAuthFilter`** — per-request: skips public routes; validates the Bearer token; sets the `SecurityContext` *only* when the token carries authorities (so a refresh token can't act as an access token).
- **`TokenProvider`** — mints/verifies HMAC-512 JWTs. Access tokens carry `authorities` + `sid` (session family); refresh tokens carry `jti` + `sid`. Validation also enforces `passwordChangedAt` (a password change kills outstanding tokens).
- **`SessionServiceImpl`** — the single token-issuance seam: `issueTokenPair` (opens a family) and `rotate` (rotates the `jti`, detects reuse → revokes the family → audits). Deliberately **not** `@Transactional`, so the reuse revocation commits before the rejection throws.

---

## 6. Data layer

- **Identity/auth** tables are defined in an **idempotent `schema.sql`** (`CREATE TABLE IF NOT EXISTS` + `INSERT … ON DUPLICATE KEY`) and accessed via `JdbcTemplate` — see [`documentation/database.md`](../documentation/database.md).
- **Business** tables (`customer`, `invoice`, `services`, `invoiceserviceitems`) are JPA-managed by Hibernate (`ddl-auto: update`).
- A prior Flyway migration set was **removed** after its baseline bookkeeping repeatedly blocked startup; the cumulative schema now lives in `schema.sql`.

---

## 7. Frontend implementation

- **Standalone components** bootstrapped from `app.config.ts`; no `NgModule`.
- **`tokenInterceptor`** attaches the access token and performs **single-flight silent refresh** on 401 (concurrent 401s share one refresh via an RxJS `BehaviorSubject`).
- **Guards**: `authenticationGuard` (valid JWT) and `adminGuard` (staff authority) — UX aids; the backend remains the security boundary.
- **State**: a `DataState` enum drives LOADING/LOADED/ERROR rendering; toasts via ngx-toastr.

---

## 8. Build, packaging & deployment

- **Maven** builds the backend; **npm/Angular CLI** the frontend.
- A **three-stage Dockerfile** compiles Angular, bakes it into the Spring Boot JAR, and runs it on a slim JRE with a healthcheck.
- **Azure DevOps** pipeline builds/pushes to ACR and deploys to App Service. Full detail: [`documentation/deployment.md`](../documentation/deployment.md).

---

## 9. Testing & quality

- OWASP `dependency-check` in the Maven build (fails on CVSS ≥ 7); ESLint + Prettier on the frontend.
- **Automated test coverage is partial** — the most significant remaining gap; a unit/integration suite for the security seams is the top future-work item.
- Manual end-to-end verification across all flows (evidenced by the demo videos).

---

## 10. Changes since Phase 1

- Completed refresh-session rotation + reuse detection and organization-scoped admin.
- Removed the migration tool; reconciled the live schema via `schema.sql`.
- Authored a complete documentation set (architecture, security, API, database, configuration, deployment, developer guide).
- Hardened startup robustness (defensive demo-data seeding).

---

## 11. Limitations & future work

- Externalise the frontend API base URL for multi-environment builds.
- Move profile-image storage off the local filesystem (object storage).
- Real SMS delivery (currently stubbed); distributed rate limiting.
- A meaningful automated test suite.

---

## 12. Implementation video

A video explaining the implementation and walking through the code accompanies this report. The segment plan and talking points are in **[`software-implementation-2-video-script.md`](software-implementation-2-video-script.md)**.
