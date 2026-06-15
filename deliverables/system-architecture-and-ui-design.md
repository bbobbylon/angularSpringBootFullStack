# System Architecture, Design & User Interface Design
### SecureCapita — An Angular + Spring Boot CIAM Platform

| | |
|---|---|
| **Author** | [Your Name] |
| **Co-author** | [Second Author] |
| **Course** | [Course Code / Title] |
| **Institution** | [Institution] |
| **Date** | [Date] |
| **Document version** | 1.0 |

---

## Abstract

This document presents the architecture and user-interface design of **SecureCapita**, a full-stack user-management and Customer Identity & Access Management (CIAM) platform built with an Angular 21 single-page application and a Spring Boot 4 REST API over MySQL. The architecture is described using Kruchten's **"4+1" view model** — logical, process, development, and physical views, unified by a set of architecturally significant scenarios — followed by the graphical user-interface design and screen prototypes. The system implements a zero-trust authentication core (stateless JWT access tokens with server-tracked, rotating refresh sessions), permission-based role access control, multi-factor authentication, and federated login.

---

## Table of contents

1. [Introduction](#1-introduction)
2. [Architectural goals & constraints](#2-architectural-goals--constraints)
3. [Logical view](#3-logical-view)
4. [Process view](#4-process-view)
5. [Development view](#5-development-view)
6. [Physical view](#6-physical-view)
7. [Scenarios (use-case view)](#7-scenarios-use-case-view)
8. [User-interface design](#8-user-interface-design)
9. [Design rationale & trade-offs](#9-design-rationale--trade-offs)

---

## 1. Introduction

### 1.1 Purpose
This document specifies the software architecture and UI design of SecureCapita for academic review. It serves as the bridge between the requirements (SRS) and the implementation, giving a structural and behavioural account of the system that a developer could build from or extend.

### 1.2 Scope
SecureCapita provides: secure registration and login, multi-factor authentication (authenticator-app TOTP and SMS), federated sign-in (OAuth2/OIDC), role-based access control across seven roles, organization-scoped administration, full audit logging, and a business domain of customer and invoice management with report export.

![SecureCapita system architecture diagram](../documentation/architectLayout.png)

*Figure 1 — High-level system architecture: the Angular client, the Spring Boot server (filters, services, repositories, email/SMS/report integrations, JPA + SQL), and the MySQL database, containerized with Docker. (The "Office365"/"RDS" labels are illustrative; the implementation uses SMTP email and MySQL.)*

### 1.3 The 4+1 view model
Architecture is multi-dimensional; no single diagram captures it. This document uses the **4+1 model**:

| View | Concern | Primary audience |
|------|---------|------------------|
| **Logical** | Functionality, structure | end users, designers |
| **Process** | Concurrency, runtime behaviour | integrators, performance engineers |
| **Development** | Module/code organization | developers |
| **Physical** | Deployment topology | operations/DevOps |
| **Scenarios (+1)** | Use cases that tie the four together | all stakeholders |

---

## 2. Architectural goals & constraints

| Goal | Architectural response |
|------|------------------------|
| **Zero-trust, scalable auth** | Stateless JWT access tokens (verified by signature, no DB hit per request) |
| **Revocable sessions** | Server-tracked refresh sessions with rotation + reuse detection |
| **Least privilege** | Permission-string RBAC enforced at URL *and* method level |
| **Auditability** | Event-driven audit trail of every security-relevant action |
| **Separation of concerns** | Layered backend; standalone-component frontend |
| **Portability** | 12-factor configuration; single self-contained Docker image |

**Key constraints:** Java 21 / Spring Boot 4 / Spring Security 7; Angular 21; MySQL 8; HMAC-signed JWTs; the frontend currently targets a fixed API origin (`http://localhost:8080`).

---

## 3. Logical view

The logical view describes *what the system does* — its functional decomposition into cooperating modules.

### 3.1 Functional decomposition

```
                         ┌─────────────────────────────────────────┐
                         │              SecureCapita                 │
                         └─────────────────────────────────────────┘
            ┌───────────────────────────┬───────────────────────────┐
            ▼                           ▼                           ▼
   ┌─────────────────┐        ┌──────────────────┐        ┌──────────────────┐
   │  Identity &     │        │   Access Control  │        │  Business Domain │
   │  Authentication │        │   (RBAC + Orgs)   │        │ (Customers/      │
   │                 │        │                   │        │  Invoices)       │
   │ • Registration  │        │ • 7-role catalog  │        │ • Customer CRUD  │
   │ • Login + MFA   │        │ • Permission gate │        │ • Invoice CRUD   │
   │ • JWT issuance  │        │ • Org scoping     │        │ • Stats          │
   │ • Refresh/rotate│        │ • Admin console   │        │ • XLSX reports   │
   │ • Federation    │        └──────────────────┘        └──────────────────┘
   └─────────────────┘                 │
            │                          ▼
            │                 ┌──────────────────┐
            └───────────────▶ │   Audit & Events │ ◀── every module publishes events
                              └──────────────────┘
```

### 3.2 Key abstractions (domain model)

| Abstraction | Role |
|-------------|------|
| `User` | The account (profile + auth flags); a POJO mapped from `users` |
| `Role` + permission string | One role per user; permissions are `RESOURCE:ACTION` grants |
| `UserPrincipal` | Spring Security `UserDetails` adapter exposing authorities |
| `RefreshSession` | One device login (a *family* of rotating refresh tokens) |
| `TotpCredential` / `MfaChallenge` | Authenticator secret + first-factor proof |
| `OAuthProviderLink` | Binding of a local user to an external identity |
| `Organization` / membership | Scoping unit for organization admins |
| `UserEvent` | An audit-log entry |
| `Customer` / `Invoice` | Business entities (JPA) |

### 3.3 Layered structure

```
Controller  →  Service (interface + impl)  →  Repository  →  Database
   │                                              ├─ JdbcTemplate (identity/auth)
   │                                              └─ JPA/Hibernate (business)
   └─ cross-cutting: SecurityFilter, TokenProvider, Exception handling, Audit events
```

Each request descends the layers and the response ascends, wrapped in a uniform `HttpResponse` envelope.

---

## 4. Process view

The process view describes runtime behaviour: threads, concurrency, and the sequences that matter.

### 4.1 Concurrency model
- **Stateless request handling.** Each HTTP request is served on a Tomcat worker thread with no server-side session; the JWT carries all state. This makes the API horizontally scalable.
- **Per-request security context.** `CustomAuthFilter` populates a thread-local `SecurityContext` for the duration of the request only.
- **Frontend refresh concurrency.** Concurrent 401s in the SPA are funnelled through a single token refresh (an RxJS `BehaviorSubject` guard) to avoid a thundering herd.

### 4.2 Sequence — login with token issuance

```
User        Angular         API (UserController)      AuthManager        SessionService     MySQL
 │  submit    │                    │                       │                   │              │
 │──────────▶ │ POST /user/login   │                       │                   │              │
 │            │──────────────────▶ │ authenticate()        │                   │              │
 │            │                    │─ brute-force gate ───────────────────────────────────────▶│
 │            │                    │──────────────────────▶│ BCrypt verify      │              │
 │            │                    │                       │◀── UserPrincipal ──│              │
 │            │                    │  (TOTP? SMS? else) ──▶ issueTokenPair() ──▶ insert session▶│
 │            │  {access,refresh}  │◀──────────────────────────────────────────│              │
 │            │◀───────────────────│                       │                   │              │
 │  app ready │ store tokens       │                       │                   │              │
```

### 4.3 Sequence — refresh with reuse detection

```
Angular            API                 SessionService.rotate()                 MySQL
  │ 401 on a call    │                          │                                │
  │ GET /refresh ───▶│ rotate(refreshToken) ───▶│ verify JWT + passwordChangedAt │
  │                  │                          │ lookup jti ───────────────────▶│
  │                  │                          │  ├─ superseded/revoked?         │
  │                  │                          │  │    → revoke whole family ───▶│  (commit)
  │                  │                          │  │    → audit TOKEN_REUSE; throw │
  │                  │                          │  └─ else: supersede + insert new▶│
  │ {new tokens} ◀───│◀─────────────────────────│                                │
```

### 4.4 Audit process (asynchronous-style decoupling)
Controllers publish a `NewUserEvent`; a listener writes the `userevents` row via the event-publisher, keeping audit writes off the main logic path.

---

## 5. Development view

The development view describes how the code is organized for the people building it.

### 5.1 Backend module organization (layered packages)

```
com.bob.angularspringbootfullstack
├── controller        REST endpoints (6 controllers)
├── service/serviceimpl  business logic behind interfaces
├── repo/repoimpl     data access (JdbcTemplate for identity)
├── model / dto / form    domain objects, API DTOs, validated request bodies
├── query / rowmapper     SQL constants + ResultSet mappers
├── tokenprovider / filter / handler   JWT + per-request auth + 401/403
├── configuration     SecurityConfig, OAuth2, WebMvc
├── event / listener  audit pipeline
├── exception / utils / report / seed / constants / enumeration
```

### 5.2 Frontend module organization (standalone Angular)

```
securecapitaapp/src/app
├── app.config.ts     providers (router, HttpClient + interceptors, toastr)
├── app.routes.ts     lazy routes + guards
├── features/         auth, home, customers, invoices, users, security, profile
├── service/          HTTP services
├── guard/ interceptor/  authentication/admin guards; token/cache interceptors
└── interface/ enumeration/   shared types
```

### 5.3 Build & dependency structure
- **Backend:** Maven (Spring Boot 4, Java 21); key libraries: Spring Security 7, `auth0/java-jwt`, ZXing (TOTP QR), Apache POI (XLSX), Twilio, OWASP dependency-check.
- **Frontend:** npm / Angular CLI 21; Bootstrap 5, RxJS, ngx-toastr, jsPDF.
- **Packaging:** a three-stage Docker build compiles the Angular app, bakes it into the Spring Boot JAR, and runs it on a slim JRE.

### 5.4 Layering rules
Dependencies point downward only (controller → service → repo); the identity layer never depends on the business layer; cross-cutting concerns (security, audit) are isolated in their own packages.

---

## 6. Physical view

The physical view maps software to hardware/runtime nodes.

### 6.1 Development topology
```
Developer machine
├── Node process   : Angular dev server (:4200, hot-reload)
├── JVM process    : Spring Boot (:8080, DevTools)
└── MySQL          : Docker container or Aiven cloud
```

### 6.2 Containerized / production topology
```
                 ┌─────────────────────────────────────┐
   Browser  ───▶ │  App container (:8080)              │ ───▶ ┌────────────────┐
                 │  Spring Boot JAR                    │      │  MySQL         │
                 │  + compiled Angular (static assets) │      │ (container or  │
                 │  Healthcheck: /actuator/health      │      │  managed cloud)│
                 └─────────────────────────────────────┘      └────────────────┘
```

### 6.3 Cloud topology (Azure reference)
Azure DevOps pipeline → Azure Container Registry → Azure App Service (Linux container); database on Aiven/Azure Database for MySQL with TLS. All configuration via platform environment variables.

---

## 7. Scenarios (use-case view)

The "+1" view: architecturally significant use cases that exercise the other four views.

| # | Scenario | Views exercised |
|---|----------|-----------------|
| UC-1 | **Register & verify** — new user registers, receives an email key, activates | Logical (identity), Process (email flow), Physical (SMTP) |
| UC-2 | **Login with MFA** — password + authenticator code → tokens | Process (login + challenge), Logical (MFA) |
| UC-3 | **Session management** — user views devices and revokes one | Logical (sessions), Process (rotation/revocation) |
| UC-4 | **Admin role reassignment** — admin changes another user's role (org-scoped) | Logical (RBAC/orgs), Development (`@PreAuthorize`) |
| UC-5 | **Token-reuse defence** — a replayed refresh token revokes the family | Process (reuse detection), Logical (audit) |
| UC-6 | **Business workflow** — create a customer, attach an invoice, export XLSX | Logical (business), Development (reports) |

**Primary actors:** Guest/User, Moderator, Help-Desk Admin, Organization Admin, Admin, Application Admin. The role hierarchy and permissions are defined in the SRS and realised in the `roles` catalog.

---

## 8. User-interface design

### 8.1 Design system
- **Framework:** Bootstrap 5 with a custom theme; responsive, mobile-friendly layouts.
- **Feedback:** non-blocking toasts (ngx-toastr) for success/error; explicit LOADING / LOADED / ERROR states via a `DataState` enum so every async view has a defined visual for each state.
- **Navigation:** a persistent authenticated navbar (profile, security, admin links shown by role), with public auth pages rendered without it.
- **Accessibility/consistency:** uniform forms, validation messaging, and a single error-presentation pattern fed by the API's `HttpResponse` envelope.

### 8.2 Screen inventory & prototypes

Low-fidelity wireframes for the principal screens (final styling is Bootstrap-themed).

**Login (`/login`)** — entry point; branches into MFA when required.
```
┌──────────────────────────────────────────┐
│                SecureCapita               │
│         Sign in to your account           │
│  ┌────────────────────────────────────┐  │
│  │ Email                              │  │
│  └────────────────────────────────────┘  │
│  ┌────────────────────────────────────┐  │
│  │ Password                       [👁] │  │
│  └────────────────────────────────────┘  │
│  [ Sign in ]                              │
│  ─────────── or continue with ─────────── │
│  [  Google ] [ GitHub ] [ Microsoft ]     │  ← shown only if configured
│  Forgot password?     Create account      │
└──────────────────────────────────────────┘
```

**MFA prompt (login continuation)** — authenticator or SMS code.
```
┌──────────────────────────────────────────┐
│  Two-factor authentication                │
│  Enter the 6-digit code from your app     │
│  ┌────┐┌────┐┌────┐ ┌────┐┌────┐┌────┐    │
│  │ _  ││ _  ││ _  │ │ _  ││ _  ││ _  │    │
│  └────┘└────┘└────┘ └────┘└────┘└────┘    │
│  [ Verify ]        Use a recovery code →  │
└──────────────────────────────────────────┘
```

**Dashboard / Home (`/`)** — KPIs and entry to features.
```
┌─ Navbar: SecureCapita  Home Customers Invoices [Security] [Users▾] Profile ⏻ ─┐
├──────────────────────────────────────────────────────────────────────────────┤
│  Welcome back, Eve                                                            │
│  ┌────────────┐ ┌────────────┐ ┌────────────┐                                │
│  │ Customers  │ │ Invoices   │ │ Total bill │   ← stat cards                  │
│  │    128     │ │    342     │ │  $1.2M     │                                │
│  └────────────┘ └────────────┘ └────────────┘                                │
│  Recent activity / quick links …                                             │
└──────────────────────────────────────────────────────────────────────────────┘
```

**Account Security Center (`/security`)** — MFA enrollment + device sessions.
```
┌──────────────────────────────────────────────────────────────┐
│  Account Security                                              │
│  ── Authenticator app ─────────────────────────────────────   │
│   Status: Not enrolled        [ Set up authenticator ]        │
│   (wizard: show QR → confirm code → reveal recovery codes)    │
│  ── Active sessions & devices ─────────────────────────────   │
│   • Chrome · Windows · 10.0.1.14   (this device)   —          │
│   • Safari · macOS  · 10.0.1.13           [ Revoke ]          │
│   [ Log out of all other devices ]                            │
└──────────────────────────────────────────────────────────────┘
```

**Admin — Users (`/users`)** — directory with search, role, and state (org-scoped for org admins).
```
┌──────────────────────────────────────────────────────────────┐
│  User Management        [ search…            ]                 │
│  Name          Email                 Role             State    │
│  Alice Guest   alice.guest@…         GUEST            ●Enabled │
│  Bob Mod       bob.mod@…             MODERATOR        ●Enabled │
│  …                                   [▾ change role] [⚙]       │
│  ◀ 1 2 3 … ▶                                                   │
└──────────────────────────────────────────────────────────────┘
```

**Roles × Permissions matrix (`/roles`)** — read view of the RBAC catalog.
```
┌──────────────────────────────────────────────────────────────┐
│  Roles & Permissions     READ  CREATE UPDATE DELETE  ROLE     │
│  GUEST                    ✔(user)  -     -      -      -       │
│  MODERATOR                ✔     -    ✔(cust)   -      -       │
│  ADMIN                    ✔     ✔     ✔       ✔(user) ✔       │
│  APPLICATION_ADMIN        ✔     ✔     ✔       ✔(all)  ✔       │
└──────────────────────────────────────────────────────────────┘
```

**Profile (`/profile`)** — profile fields, password, MFA toggle, paginated audit log.

**Customers / Invoices** — paginated tables with search, detail views, create forms, and XLSX export buttons.

### 8.3 Primary user flows
- **Authentication:** Login → (MFA) → Dashboard; Register → email verify → Login; Forgot password → email → reset.
- **Self-service security:** Dashboard → Security Center → enroll authenticator / revoke a session.
- **Administration:** Dashboard → Users → select user → change role / toggle state (audited against the target).
- **Business:** Customers → New customer; Invoices → New invoice → attach to customer → export report.

---

## 9. Design rationale & trade-offs

| Decision | Rationale | Trade-off |
|----------|-----------|-----------|
| Stateless access + stateful refresh | Scalable requests **and** revocable sessions | Two token types to manage |
| JdbcTemplate (identity) + JPA (business) | Explicit, auditable SQL where security lives | Two persistence idioms |
| Permission-string RBAC, one role/user | Simple, readable least-privilege model | No multi-role users without change |
| Event-driven audit | Logging off the hot path | Indirection when tracing writes |
| Frontend guards as UX, backend as boundary | Defense in depth | Rules expressed in two places |
| Single self-contained image | Portable, simple deploys | Frontend coupled to a fixed API origin (current limitation) |

> **Implementation cross-reference.** This design is realised in code; the developer-facing companions are [`documentation/architecture.md`](../documentation/architecture.md), [`documentation/security.md`](../documentation/security.md), [`documentation/database.md`](../documentation/database.md), and [`documentation/api-reference.md`](../documentation/api-reference.md).
