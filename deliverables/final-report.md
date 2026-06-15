# SecureCapita — Final Report
### A Full-Stack Customer Identity & Access Management Platform

| | |
|---|---|
| **Author** | [Your Name] |
| **Co-author** | [Second Author] |
| **Course** | [Course Code / Title] |
| **Supervisor / Instructor** | [Name] |
| **Institution** | [Institution] |
| **Date** | [Date] |
| **Document version** | 1.0 |

> **Note on assembly.** This report is the combination of the project's documents — Proposal, Literature Review, SRS, System Architecture, and UI Design — plus Installation and User manuals. Sections 2–4 summarise documents that exist as standalone artifacts; **merge your full existing text where marked** `‹MERGE …›`. Sections 5–9 are authored here in full.

---

## Abstract

SecureCapita is a full-stack Customer Identity & Access Management (CIAM) platform that pairs an Angular 21 single-page application with a Spring Boot 4 REST API over MySQL. It implements a zero-trust authentication core — stateless JWT access tokens combined with server-tracked, rotating refresh sessions with reuse detection — alongside authenticator-app multi-factor authentication, OAuth2 federated login, permission-based role access control with organization scoping, comprehensive audit logging, and a business domain of customer and invoice management. This report presents the project end to end: motivation and objectives, background, requirements, architecture and interface design, implementation, and operational manuals, concluding with an evaluation and future-work agenda.

---

## Table of contents

1. [Introduction](#1-introduction)
2. [Project proposal (summary)](#2-project-proposal-summary)
3. [Literature review (summary)](#3-literature-review-summary)
4. [Software requirements specification (summary)](#4-software-requirements-specification-summary)
5. [System architecture & design](#5-system-architecture--design)
6. [User-interface design](#6-user-interface-design)
7. [Implementation](#7-implementation)
8. [Installation manual](#8-installation-manual)
9. [User manual](#9-user-manual)
10. [Testing & evaluation](#10-testing--evaluation)
11. [Conclusion & future work](#11-conclusion--future-work)
12. [References](#12-references)
13. [Appendices](#13-appendices)

---

## 1. Introduction

### 1.1 Problem statement
Most applications must manage user identity, but building authentication correctly is difficult and the most common source of security vulnerabilities. Users now expect modern multi-factor authentication, single sign-on, and control over their own sessions and devices; organizations expect least-privilege administration and a defensible audit trail.

### 1.2 Objectives
1. Implement a production-style, in-house authentication core (JWT, refresh-session rotation, MFA, federation).
2. Provide permission-based role access control with organization scoping.
3. Deliver a complete administrative console and user self-service experience.
4. Secure a real business domain (customers and invoices).
5. Record every security-relevant action for audit.

### 1.3 Scope & contributions
The project delivers a working, end-to-end system and a complete documentation set. Its principal contribution is a clear, well-documented reference implementation of a **hybrid stateless/stateful** authentication model that balances scalability with revocability.

---

## 2. Project proposal (summary)

> ‹MERGE your full Project Proposal here.›

In brief, the proposal set out to build SecureCapita as a CIAM reference platform, motivated by the prevalence of identity-related security failures and the educational value of implementing — rather than outsourcing — a modern authentication core. Planned scope, timeline (Phase 1 prototype, Phase 2 final), and success criteria are defined in the original proposal document.

---

## 3. Literature review (summary)

> ‹MERGE your full Literature Review here. The text below is a neutral scaffold — insert your actual sources and analysis; do not rely on it for citations.›

The review situates the project within: (a) **identity & access management** and the emergence of CIAM for external-facing, self-service user bases; (b) **token-based authentication**, contrasting stateless JWTs (scalability, no server session) with stateful sessions (revocability), and the hybrid approaches that combine them via refresh-token rotation and reuse detection; (c) **multi-factor authentication**, in particular time-based one-time passwords (RFC 6238) and recovery mechanisms; (d) **federated identity** via OAuth2/OpenID Connect; and (e) **role- and attribute-based access control** models. SecureCapita's design choices are positioned against this literature in the architecture section.

---

## 4. Software requirements specification (summary)

> ‹MERGE / reference your full SRS (`software_requirements_specification.docx`).›

### 4.1 Functional requirements (selected)
- Registration, email verification, login, logout, password reset.
- Multi-factor authentication: authenticator app (TOTP) and SMS.
- Federated login via Google/GitHub/Microsoft.
- Seven-role permission-based access control; organization-scoped administration.
- User self-service: profile, password, MFA, and session/device management.
- Administrative user management: directory, role reassignment, account-state control.
- Audit logging of security-relevant events.
- Business domain: customer and invoice management with report export.

### 4.2 Non-functional requirements (selected)
- **Security:** least privilege; no credential leakage; no user enumeration; password-change token invalidation.
- **Performance:** stateless access-token validation (no per-request database lookup).
- **Portability:** environment-variable configuration; single deployable image.
- **Maintainability:** layered architecture; comprehensive documentation.

---

## 5. System architecture & design

This section condenses the standalone *System Architecture & UI Design* document (`system-architecture-and-ui-design.md`), which contains the full 4+1 treatment and diagrams.

### 5.1 Overview
Three tiers — Angular SPA, Spring Boot REST API, MySQL — communicating over JSON and JDBC. The API is stateless; the only intentionally stateful element is the refresh-session store.

### 5.2 The 4+1 views (condensed)
- **Logical:** three functional domains (Identity & Authentication, Access Control, Business) plus a cross-cutting Audit module; a layered Controller → Service → Repository structure.
- **Process:** stateless request handling; the login → token-issuance sequence; the refresh → rotation/reuse-detection sequence; event-driven audit.
- **Development:** layered backend packages; standalone-component frontend; Maven/npm builds; multi-stage Docker packaging.
- **Physical:** native dev processes; containerized app+DB; cloud deployment (Azure/managed MySQL).
- **Scenarios:** register/verify, login-with-MFA, session management, admin role reassignment, token-reuse defence, business workflow.

### 5.3 Key design decision
A **hybrid token model** — stateless access tokens for scale, server-tracked rotating refresh sessions for revocability — is the architectural centrepiece, enabling both high-throughput request handling and fine-grained session control. Full rationale and the developer-facing companions are in `documentation/architecture.md` and `documentation/security.md`.

---

## 6. User-interface design

Condensed from the *System Architecture & UI Design* document, §8.

- **Design system:** Bootstrap 5, responsive layouts, a role-aware authenticated navbar, non-blocking toast feedback, and explicit LOADING/LOADED/ERROR states for every async view.
- **Primary screens:** login (with federated options), registration, verification landings, dashboard, Account Security Center (MFA + sessions), administration (user directory, user detail, roles matrix), profile, and customers/invoices.
- **Principal flows:** authentication (incl. MFA), self-service security, administration, and business workflows.

Low-fidelity prototypes for each screen are in the architecture document; the live UI is shown in the demo videos.

---

## 7. Implementation

Delivered in two phases (full detail in `implementation-1-report.md` and `implementation-2-report.md`).

- **Phase 1 — prototype:** stood up the full stack and demonstrated the core CIAM features and the complete UI working end-to-end.
- **Phase 2 — final:** completed the security feature set (rotation, reuse detection, organization-scoped admin), consolidated schema management into an idempotent script, authored the full documentation set, and packaged the system via multi-stage Docker with CI/CD.

**Technology:** Angular 21 / Bootstrap 5 (frontend); Spring Boot 4 / Java 21 / Spring Security 7 (backend); MySQL 8 (`JdbcTemplate` for identity, JPA for the business domain); HMAC-512 JWTs; ZXing (TOTP QR); Apache POI (XLSX); Docker; Azure DevOps.

**Security seams (load-bearing classes):** `SecurityConfig`, `CustomAuthFilter`, `TokenProvider`, `SessionServiceImpl` — see `documentation/security.md`.

---

## 8. Installation manual

### 8.1 Prerequisites
| Tool | Version |
|------|---------|
| JDK | 21+ |
| Maven | 3.8+ |
| Node.js + npm | 22 LTS (or 20.19+) |
| MySQL | 8.x (or Docker) |
| Bash | Git Bash / WSL on Windows (for `start.sh`) |

### 8.2 Obtain the source
```bash
git clone <repo-url>
cd angularSpringBootFullStack
```

### 8.3 Configure environment
```bash
cp .env.example .env        # then edit
```
Set at minimum `MYSQL_USERNAME`, `MYSQL_PASSWORD`, `MYSQL_DATABASE` (e.g. `db2`), and a strong `JWT_SECRET` (`openssl rand -base64 48`). Optional: mail (`MAIL_*`), OAuth (`GOOGLE_*`/`GITHUB_*`/`MICROSOFT_*`), Twilio. Full reference: `documentation/configuration.md`.

### 8.4 Initialise the database
```bash
mysql -u root -p -e "CREATE DATABASE IF NOT EXISTS db2;"
mysql -u root -p db2 < src/main/resources/schema.sql
```
`schema.sql` is idempotent (safe to re-run). Business tables are created automatically by Hibernate on first boot.

### 8.5 Run

**Local (development)** — edit `start.sh` (`ENV=local`, `DB=local|aiven`), then:
```bash
chmod +x start.sh
./start.sh                  # frontend → http://localhost:4200, backend → :8080
```

**Docker (production-like)** — set `ENV=docker` in `start.sh` (or `docker compose up --build`):
```bash
./start.sh                  # app → http://localhost:8090
```

### 8.6 Cloud deployment
Build the image (`docker build -t securecapita .`), supply configuration via the platform's environment variables, point at a managed MySQL (TLS on), apply `schema.sql` once, and use the `prod` profile. Azure CI/CD and other platforms: `documentation/deployment.md`.

### 8.7 Verifying the installation
Open the app, log in as `eve.admin@tessera.dev` / `TesseraDemo@1`; you should reach the dashboard with the admin menu visible. Troubleshooting: `documentation/getting-started.md §8`.

---

## 9. User manual

### 9.1 Roles at a glance
SecureCapita has seven roles, from `ROLE_GUEST` (read-only) up to `ROLE_APPLICATION_ADMIN` (full access). Your role determines which menus and actions are available.

### 9.2 Getting an account
- **Register** at `/register`, then click the verification link emailed to you (or provided by an administrator) to activate the account.
- **Or sign in with Google/GitHub/Microsoft** if federated login is enabled.

### 9.3 Signing in
1. Go to `/login`, enter your email and password.
2. If multi-factor authentication is enabled, enter the 6-digit code from your authenticator app (or a recovery code).
3. You'll land on the dashboard.

> After five failed attempts within 15 minutes, sign-in is temporarily blocked.

### 9.4 Securing your account (Account Security Center → `/security`)
- **Enable an authenticator app:** choose *Set up authenticator*, scan the QR code with Google Authenticator (or similar), enter a code to confirm, then **save your recovery codes** — they are shown only once.
- **Manage devices:** review your active sessions; click *Revoke* to end one, or *Log out everywhere else* to end all but your current session.

### 9.5 Managing your profile (`/profile`)
- Edit your name, contact details, title, and bio.
- **Change your password** (this signs out your other sessions).
- Toggle SMS two-factor; view your personal activity log.

### 9.6 Administration (staff roles only)
- **Users (`/users`):** search the directory, open a user to view their details and activity, reassign their role, or enable/lock their account. *(Organization administrators see only users in their organization.)*
- **Roles (`/roles`):** review the role-to-permission matrix.
- You cannot change your **own** role or lock your **own** account from the admin console — ask another administrator.

### 9.7 Customers & invoices
- **Customers:** browse and search the list, open a customer, create a new one, and export the list to Excel.
- **Invoices:** browse invoices, create one (standalone or attached to a customer), and export to Excel.

### 9.8 Signing out
Use the power/exit icon in the navbar. Your session ends; tokens are cleared from the browser.

---

## 10. Testing & evaluation

- **Security tooling:** OWASP dependency-check in the build (fails on CVSS ≥ 7); ESLint/Prettier on the frontend.
- **Manual verification:** all primary flows were exercised end-to-end against a live database (evidenced by the demo videos).
- **Functional evaluation:** the system satisfies the functional requirements summarised in §4.1 — authentication, MFA, RBAC, organization scoping, audit, administration, and the business domain all operate as specified.
- **Honest gaps:** automated test coverage is currently minimal; SMS delivery is stubbed; federated login requires provider credentials; image storage and the frontend API origin are not yet environment-parameterised. These are the substance of the future-work agenda.

> *No performance benchmarks are claimed in this report; where quantitative evaluation is required, measure on your target environment and insert results here.*

---

## 11. Conclusion & future work

SecureCapita demonstrates a complete, modern CIAM core built on a real application, validating a hybrid stateless/stateful authentication model that balances scalability and revocability. The codebase is layered, documented, and extensible.

**Future work:** a comprehensive automated test suite; externalising the frontend API base URL for multi-environment deployment; cloud object storage for profile images; real SMS delivery; distributed rate limiting; and broader administrative tooling.

---

## 12. References

> ‹Insert your citations here (IEEE or your course's required style), drawn from the Literature Review.›

---

## 13. Appendices

### Appendix A — Demo accounts
All seeded with password `TesseraDemo@1`: `alice.guest` (GUEST), `bob.mod` (MODERATOR), `carol.help` (HELP_DESK_ADMIN), `dave.org` (ORGANIZATION_ADMIN), `eve.admin` (ADMIN), `frank.app` (APPLICATION_ADMIN) — all `@tessera.dev`.

### Appendix B — API summary
Full reference: `documentation/api-reference.md`. Endpoint groups: `/user/**` (auth, profile, MFA, sessions), `/oauth2/**` (federation), `/admin/user/**` (administration), `/customer/**` (customers & invoices).

### Appendix C — Companion documents
- Architecture & UI: `deliverables/system-architecture-and-ui-design.md`
- Implementation reports: `deliverables/implementation-1-report.md`, `deliverables/implementation-2-report.md`
- Presentation: `deliverables/final-presentation-slides.md`
- Developer & operations docs: `documentation/`
