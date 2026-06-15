# Implementation 1 — System Prototype & User Interface Design
### Phase 1 Progress Report — SecureCapita

| | |
|---|---|
| **Author** | [Your Name] |
| **Co-author** | [Second Author] |
| **Course** | [Course Code / Title] |
| **Institution** | [Institution] |
| **Date** | [Date] |
| **Deliverable** | 5.1 — Implementation 1 (Document/Report) |
| **Companion** | Software Demo 1 video — see [`software-demo-1-video-script.md`](software-demo-1-video-script.md) |

---

## 1. Introduction

This report documents **Phase 1** of the SecureCapita implementation: a working system prototype demonstrating the core Customer Identity & Access Management (CIAM) capabilities and the full user-interface design. It accompanies the **Software Demo 1** video (15 minutes), which shows the prototype in action.

The prototype validates the architecture defined in the *System Architecture & UI Design* document and realises the functional requirements from the SRS. It is a running, end-to-end full-stack application — not mock-ups — exercising real authentication, authorization, and data flows against a live database.

---

## 2. Phase 1 objectives

| ID | Objective | Status |
|----|-----------|:------:|
| O1 | Stand up the full stack (Angular SPA ↔ Spring Boot API ↔ MySQL) | ✅ Done |
| O2 | Secure registration, login, and JWT-based session handling | ✅ Done |
| O3 | Role-based access control across the SRS role catalog | ✅ Done |
| O4 | Multi-factor authentication (authenticator app) | ✅ Done |
| O5 | Complete UI for all primary screens | ✅ Done |
| O6 | Business domain (customers/invoices) with reporting | ✅ Done |
| O7 | Audit logging of security-relevant actions | ✅ Done |

---

## 3. Implemented functionality

| Area | Capability | Status |
|------|-----------|:------:|
| **Authentication** | Registration, email verification, login, logout | ✅ |
| | JWT access tokens (stateless, 30-min) | ✅ |
| | Refresh-session rotation with reuse detection (5-day sliding) | ✅ |
| | Brute-force throttling (5 attempts / 15-min window) | ✅ |
| **MFA** | Authenticator-app TOTP (QR enrollment, recovery codes, challenge-bound verify) | ✅ |
| | SMS 2FA (Twilio integration) | ⚠ Stubbed (code logged, not sent) |
| **Federation** | OAuth2/OIDC login (Google/GitHub/Microsoft) | ✅ (inactive until provider credentials are set) |
| **Authorization** | Permission-string RBAC, 7 roles | ✅ |
| | URL- + method-level enforcement (`@PreAuthorize`) | ✅ |
| | Organization-scoped administration | ✅ |
| **Administration** | User directory, role reassignment, account-state control | ✅ |
| **Audit** | Per-user event log (15 event types) | ✅ |
| **Business domain** | Customers & invoices CRUD, dashboard stats | ✅ |
| | XLSX report export (customers, invoices) | ✅ |
| **Account self-service** | Profile, password change, MFA toggle, session/device management | ✅ |

---

## 4. System prototype walkthrough

The prototype delivers every primary screen from the UI design:

1. **Authentication** — login (with federated buttons when configured), registration, email-verification landing, password reset.
2. **MFA** — authenticator code prompt at login; recovery-code fallback.
3. **Dashboard** — KPI stat cards (customers, invoices, totals).
4. **Account Security Center** — authenticator enrollment wizard (QR → confirm → recovery codes) and an active-sessions/devices panel with per-device and "log out everywhere else" revocation.
5. **Administration** — searchable user directory, per-user detail with audit history, role reassignment, and account enable/lock — scoped to the admin's organization where applicable.
6. **Roles & Permissions matrix** — read view of the RBAC catalog.
7. **Profile** — editable profile, password change, MFA toggle, paginated personal audit log.
8. **Customers & Invoices** — paginated lists with search, detail views, creation forms, and report export.

(See the *System Architecture & UI Design* document, §8, for the wireframes; the demo video shows them live.)

---

## 5. Architecture & technology realised

- **Frontend:** Angular 21 standalone components, Bootstrap 5, RxJS, an HTTP interceptor handling token attachment + silent refresh.
- **Backend:** Spring Boot 4 / Java 21, Spring Security 7; a layered Controller → Service → Repository design; `JdbcTemplate` for the identity/auth domain and JPA/Hibernate for the business domain.
- **Security:** HMAC-SHA512 JWTs, BCrypt password hashing, stateless access + server-tracked refresh sessions.
- **Database:** MySQL 8; identity schema in an idempotent `schema.sql`, business tables via Hibernate.
- **Packaging:** a three-stage Docker build producing a single self-contained image.

Developer-facing detail: [`documentation/architecture.md`](../documentation/architecture.md), [`documentation/security.md`](../documentation/security.md).

---

## 6. Engineering challenges & resolutions

| Challenge | Resolution |
|-----------|-----------|
| A database-migration tool's baseline bookkeeping repeatedly desynced from the live database and blocked startup | Removed the migration tool in favour of a single **idempotent `schema.sql`**; the live DB was reconciled directly |
| A demo-data seeder could abort startup when expected roles were absent | Hardened it to resolve roles defensively and never fail the boot |
| Hibernate's `globally_quoted_identifiers` produced unexpected column names | Documented the behaviour and applied explicit `@Column` mappings |
| Environment-variable configuration was order-sensitive (self-referential defaults) | Documented the launch path (`start.sh` / IDE env file) and the failure mode |

These are recorded transparently in the configuration and database guides.

---

## 7. Quality & testing status

- **Static security scanning:** OWASP `dependency-check` is wired into the Maven build (fails on CVSS ≥ 7).
- **Linting/formatting:** ESLint + Prettier (frontend).
- **Automated tests:** **currently minimal** — a recognised gap and a priority for Phase 2.
- **Manual verification:** all flows above were exercised end-to-end against a live MySQL instance (the demo video is the evidence).

---

## 8. Known limitations (carried into Phase 2)

- SMS 2FA send is stubbed (TOTP is the functional MFA).
- Federated login requires provider credentials to activate.
- Profile-image storage writes to a local path (not container/cloud-ready).
- The frontend targets a fixed API origin; multi-environment builds need parameterising.
- Test coverage is sparse.

---

## 9. Phase 2 roadmap

Phase 2 (final implementation) will focus on hardening and completion: a meaningful automated test suite, externalising the frontend API base URL, cloud-ready image storage, optional real SMS delivery, and end-to-end deployment validation. See deliverable 5 (*Implementation 2*).

---

## 10. Software Demo 1 (video)

A 15-minute demonstration accompanies this report. The full segment-by-segment script (timings, talking points, and the scenarios to show) is in **[`software-demo-1-video-script.md`](software-demo-1-video-script.md)**. The video will be uploaded to YouTube and the link added to the submission system.

---

## Appendix A — Running the prototype

Briefly (full detail in [`documentation/getting-started.md`](../documentation/getting-started.md)):

```bash
cp .env.example .env                  # set MYSQL_*, JWT_SECRET
mysql -u root -p db2 < src/main/resources/schema.sql
./start.sh                            # ENV=local → http://localhost:4200
```

Demo accounts (password `TesseraDemo@1`): `eve.admin@tessera.dev` (admin), `alice.guest@tessera.dev` (basic user).
