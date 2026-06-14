# Architecture Guide

How SecureCapita is put together — the tiers, the backend's layered design, the request lifecycle, the two persistence strategies, the event-driven audit, and the Angular frontend — plus the directory map and the design decisions behind it.

> **See also:** [security.md](security.md) (auth internals) · [database.md](database.md) (data model) · [api-reference.md](api-reference.md) (endpoints) · [configuration.md](configuration.md) (settings).

---

## Table of contents

1. [System overview](#1-system-overview)
2. [Backend: layered design](#2-backend-layered-design)
3. [Backend: request lifecycle](#3-backend-request-lifecycle)
4. [Backend: two persistence strategies](#4-backend-two-persistence-strategies)
5. [Backend: cross-cutting concerns](#5-backend-cross-cutting-concerns)
6. [Frontend architecture](#6-frontend-architecture)
7. [Runtime topology](#7-runtime-topology)
8. [Directory map](#8-directory-map)
9. [Design decisions & trade-offs](#9-design-decisions--trade-offs)

---

## 1. System overview

Three tiers, talking over JSON and JDBC:

```
┌───────────────┐   HTTPS / JSON      ┌──────────────────────────┐    JDBC     ┌──────────┐
│  Angular 21   │ ──────────────────▶ │     Spring Boot 4        │ ──────────▶ │ MySQL 8  │
│  SPA (:4200)  │ ◀────────────────── │     REST API (:8080)     │ ◀────────── │  (db2)   │
└───────────────┘   JWT access +      └──────────────────────────┘             └──────────┘
                    refresh tokens
```

- **Frontend** — an Angular single-page app (standalone components). In development it's served separately on `:4200`; for Docker/production it's compiled into the Spring Boot JAR and served as static resources from `:8080`.
- **Backend** — a stateless Spring Boot REST API: JWT auth, permission-based RBAC, and the business endpoints.
- **Database** — MySQL 8, accessed two ways (see §4).

---

## 2. Backend: layered design

A classic layered architecture — each request flows **Controller → Service → Repository → Database** and back, with supporting packages around the edges.

| Package | Responsibility |
|---------|----------------|
| `controller` | REST endpoints; wrap every response in `HttpResponse`. (`UserController`, `AdminUserController`, `CustomerController`, `TotpController`, `SessionController`, `FederatedAuthController`) |
| `service` + `service/serviceimpl` | Business logic behind interfaces (`UserService`, `RoleService`, `CustomerService`, `EventService`, `SessionService`, `TotpService`, `OrganizationService`, `FederatedIdentityService`, `EmailService`, `NotificationService`) |
| `repo` + `repo/repoimpl` | Data access. Identity repos use `JdbcTemplate` (`UserRepoImpl`, `RoleRepoImpl`, `EventRepoImpl`); `CustomerRepo`/`InvoiceRepo`/`ServicesRepo` are Spring Data JPA |
| `model` | Domain objects (`User`, `Role`, `UserPrincipal`, `RefreshSession`, `Customer`, `Invoice`, …) and the `HttpResponse` envelope |
| `dto` + `dtomapper` | `UserDTO` (password-free view) + `UserDTOMapper` |
| `form` | Validated request bodies (`LoginForm`, `UpdateForm`, `UpdatePasswordForm`, `NewPasswordForm`, `SettingsForm`, `TotpCodeForm`, `TotpVerifyForm`) |
| `query` | SQL constants per domain (`UserQuery`, `RoleQuery`, `SessionQuery`, `TotpQuery`, `OrganizationQuery`, `OAuthQuery`, `EventQuery`, `CustomerQuery`) |
| `rowmapper` | `ResultSet` → model mappers for the JDBC layer |
| `tokenprovider` | `TokenProvider` — JWT mint/verify |
| `filter` | `CustomAuthFilter` — per-request JWT authentication |
| `handler` | `CustomAuthenticationEntryPoint` (401), `CustomAccessDeniedHandler` (403), `OAuth2LoginSuccessHandler` (federated token exchange) |
| `configuration` | `SecurityConfig`, `OAuth2ClientConfig`, `FederatedProviderCatalog`, `WebMvcConfig` |
| `event` + `listener` | `NewUserEvent` + `NewUserEventListener` — the audit pipeline |
| `exception` | `ApiException`, `HandleException`, `GlobalExceptionHandler` |
| `enumeration` | `RoleType`, `EventType`, `VerificationType` |
| `utils` | `UserUtils`, `RequestUtils` (device/IP), `ExceptionUtils`, `TotpUtils` (RFC 6238), `SMSUtils` |
| `report` | `CustomerReport`, `InvoiceReport` — XLSX exports (Apache POI) |
| `seed` | `DemoDataSeeder` (dev-only) |
| `constants` | `Constants` (public URLs, token settings) |

**Why interfaces + impls?** The service and repo layers are split into an interface (`UserService`) and an implementation (`UserServiceImpl`). It keeps controllers depending on contracts, makes the seams mockable, and is the same convention throughout.

---

## 3. Backend: request lifecycle

What happens to an authenticated `GET /customer/list` request:

```
HTTP request
   │
   ▼
CorsFilter ─────────────── preflight/headers
   │
   ▼
CustomAuthFilter ───────── skips public routes & OPTIONS; else validates the Bearer JWT
   │                       and sets the Authentication in the SecurityContext (see security.md §4)
   ▼
SecurityFilterChain ─────── authorizeHttpRequests rules: GET /** needs READ:USER or READ:CUSTOMER
   │                         (401 via entry point if unauthenticated, 403 via handler if unauthorized)
   ▼
DispatcherServlet ───────── routes to the controller method
   │
   ▼
CustomerController.getCustomers()
   │
   ▼
CustomerService → CustomerRepo (JPA)  ──▶  MySQL
   │
   ▼
HttpResponse { timeStamp, data{…}, message, status, statusCode }   ←  consistent JSON envelope
```

Every controller returns the same **`HttpResponse`** envelope, so the frontend can parse success and error responses uniformly:

```json
{
  "timeStamp": "12:01:33.123",
  "statusCode": 200,
  "status": "OK",
  "message": "Customers retrieved",
  "data": { "page": { /* … */ } }
}
```

Errors funnel through `GlobalExceptionHandler` (and `ExceptionUtils` for filter-level failures), which produces the same shape with `reason` populated — so a 401/403/400 looks structurally identical to a 200 to the client.

---

## 4. Backend: two persistence strategies

A deliberate split (detailed in [database.md](database.md)):

| | Identity / auth domain | Business domain |
|--|------------------------|-----------------|
| **Access** | `JdbcTemplate` + hand-written SQL (`query/*`) + `rowmapper/*` | Spring Data JPA (`@Entity`) |
| **Tables** | `users`, `roles`, `userroles`, `events`, `userevents`, sessions, TOTP, orgs, … | `customer`, `invoice`, `services`, `invoiceserviceitems` |
| **Schema owned by** | `schema.sql` (idempotent, run by hand) | Hibernate `ddl-auto: update` |

The identity layer favors explicit, auditable SQL (it underpins security); the CRUD-heavy business layer leans on JPA's mapping. Note `User` is a **plain POJO** mapped by `UserRowMapper`, *not* a JPA entity.

---

## 5. Backend: cross-cutting concerns

**Authentication & authorization** — a stateless JWT core with stateful refresh sessions; permission-based RBAC. Fully covered in [security.md](security.md).

**Event-driven audit.** Controllers publish a `NewUserEvent` via Spring's `ApplicationEventPublisher`; `NewUserEventListener` consumes it and writes a `userevents` row through `EventService`. This decouples "something happened" from "record it," so audit logging never clutters the main request path:

```
Controller ── publishEvent(NewUserEvent(email, LOGIN_ATTEMPT_SUCCESS))
                       │
                       ▼
              NewUserEventListener ──▶ EventService ──▶ userevents table
```

**Configuration & profiles** — env-var driven, `dev`/`prod` profiles. See [configuration.md](configuration.md).

**Reporting** — `report/*` builds XLSX exports with Apache POI for customers and invoices.

---

## 6. Frontend architecture

A modern **standalone-component** Angular 21 app (no `NgModule`).

| Concern | Where | Notes |
|---------|-------|-------|
| Bootstrap | `app.config.ts` | Registers providers: the router, `HttpClient`, and the HTTP interceptors |
| Routing | `app.routes.ts` | Lazy `loadComponent` routes guarded by `authenticationGuard` / `adminGuard` |
| Features | `app/features/` | `auth/` (login, register, reset, verify, oauth2-callback), `home/`, `customers/`, `invoices/`, `users/` (admin), `security/` (Security Center), `profile/` |
| Services | `app/service/` | `user.service`, `admin-user.service`, `customer.service`, `theme.service`, `notifications-service`, `http-cache.service` |
| Guards | `app/guard/` | `authentication.guard` (logged-in), `admin.guard` (staff authority) |
| Interceptors | `app/interceptor/` | `token.interceptor` (attach access token; refresh on 401), `cache.interceptor` |
| Types | `app/interface/`, `app/enumeration/` | API contracts + `DataState` (LOADING/LOADED/ERROR) enum |

**Frontend ↔ backend integration:**

```
Component → UserService (HttpClient)
                 │
                 ▼
        token.interceptor
          ├─ attach  Authorization: Bearer <access_token>
          └─ on 401  → GET /user/refresh/token → store rotated tokens → retry the original request
                 │
                 ▼
        Spring Boot REST API
```

Tokens live in `localStorage` (keyed by the `Key` enum). The `adminGuard` is a **usability aid only** — it hides admin routes a user can't use, but the backend independently enforces the same authorities on every `/admin/**` request, so the guard is never the security boundary.

---

## 7. Runtime topology

| Mode | Frontend | Backend | DB | Entry URL |
|------|----------|---------|----|-----------|
| **Local dev** (`start.sh ENV=local`) | `ng serve` on `:4200` (hot-reload) | `mvn spring-boot:run` on `:8080` (DevTools restart) | Docker MySQL *or* Aiven | http://localhost:4200 |
| **Docker** (`ENV=docker`) | compiled into the JAR | JAR in a container on `:8080` (mapped to `:8090`) | MySQL container | http://localhost:8090 |
| **Cloud** | in the image | container on App Service / Cloud Run | managed MySQL (Aiven/RDS/Cloud SQL) | platform URL |

The multi-stage `Dockerfile` builds Angular → bakes it into the Spring Boot JAR → runs on a slim JRE. See [deployment.md](deployment.md).

---

## 8. Directory map

```
src/main/java/com/bob/angularspringbootfullstack/
├── controller/        REST endpoints
├── service/           business-logic interfaces
│   └── serviceimpl/   implementations
├── repo/              data-access interfaces
│   └── repoimpl/      JdbcTemplate implementations
├── model/             domain objects + HttpResponse + UserPrincipal
├── dto/ + dtomapper/  UserDTO + mapping
├── form/              validated request bodies
├── query/             SQL constants
├── rowmapper/         ResultSet → model
├── tokenprovider/     JWT mint/verify
├── filter/            CustomAuthFilter
├── handler/           401/403 + OAuth2 success
├── configuration/     SecurityConfig, OAuth2, WebMvc
├── event/ + listener/ audit pipeline
├── exception/         ApiException, GlobalExceptionHandler
├── enumeration/       RoleType, EventType, VerificationType
├── utils/             UserUtils, RequestUtils, TotpUtils, …
├── report/            POI XLSX exports
├── seed/              DemoDataSeeder (dev)
└── constants/         Constants

securecapitaapp/src/app/
├── app.config.ts      standalone providers
├── app.routes.ts      lazy routes + guards
├── features/          auth, home, customers, invoices, users, security, profile
├── service/           HTTP services
├── guard/             authentication, admin
├── interceptor/       token (refresh), cache
├── interface/         API/UI contracts
└── enumeration/       Key, DataState, EventType
```

---

## 9. Design decisions & trade-offs

| Decision | Rationale | Trade-off |
|----------|-----------|-----------|
| **Stateless access + stateful refresh** | Fast request handling (no DB hit per call) **and** revocable sessions | Slightly more moving parts than pure-stateless JWT |
| **JdbcTemplate for identity, JPA for business** | Explicit, auditable SQL where security lives; mapping convenience for CRUD | Two persistence idioms to learn |
| **Permission strings on one role per user** | Simple, readable RBAC that's easy to reason about | No multi-role users without a model change |
| **Event-driven audit** | Keeps logging off the request hot path | Indirection when tracing "who wrote this row" |
| **`schema.sql` over a migration tool** | Predictable, idempotent, no migration-state machine to desync | No automatic versioning/rollback (a prior Flyway setup was removed for exactly this reason — see [database.md §13](database.md#13-conventions-gotchas--history)) |
| **Frontend guards as UX, backend as the boundary** | Defense in depth; the API never trusts the client | Authorization rules expressed in two places (kept in lockstep) |
