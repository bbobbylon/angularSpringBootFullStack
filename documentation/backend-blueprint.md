# Backend Blueprint (Reusable)

A copy-this-shape guide to how this Spring Boot backend is built, so a new backend can
be stood up quickly by replicating the structure. Everything here was verified against
the source on 2026-06-16. Base package: `com.bob.angularspringbootfullstack`.

Related: [architecture.md](architecture.md) (system design), [database.md](database.md)
(schema), [security.md](security.md) (auth deep dive), and
[flows/00-anatomy-of-a-request.md](flows/00-anatomy-of-a-request.md) (request lifecycle).

---

## 1. Build & stack (`pom.xml`)

- **Spring Boot parent 4.0.6**, **Java 21**, Spring Framework 7 / Spring Security 7, Tomcat 11.
- **Lombok** as an annotation processor (excluded from the boot jar).
- Starters: `webmvc`, `security`, `oauth2-client`, `validation`, **`data-jdbc`** (the workhorse),
  `data-jpa` (only for the customer/invoice/services tables), `mail`, `actuator`, `devtools`.
- Libraries: `jjwt 0.12.6` + `auth0 java-jwt` (JWT), `mysql-connector-j` (runtime),
  `commons-lang3`, `twilio` (SMS — stubbed), `zxing` (TOTP QR), `yauaa` (user-agent parsing
  for session/device listing), `poi-ooxml` (Excel reports).
- **Maven profiles**: `dev` (default), `prod`, `qa`, `stage`, `local` — each sets
  `spring.profiles.active`. The OWASP `dependency-check` plugin fails the build at CVSS ≥ 7.

---

## 2. Package layout (the convention to copy)

One package per responsibility under the base package:

| Package | Responsibility |
| --- | --- |
| `controller/` | `@RestController`s — thin; build an `HttpResponse` and delegate to a service. |
| `service/` + `service/serviceimpl/` | Business-logic interface + `@Service` implementation. |
| `repo/` + `repo/repoimpl/` | Persistence interface + `@Repository` implementation. |
| `query/` | Classes of `public static final String` SQL constants with **named** params (`:email`), one per aggregate (`UserQuery`, `RoleQuery`, …). Single source of truth for SQL. |
| `rowmapper/` | `RowMapper<T>` implementations; `mapRow` builds the model via Lombok builder. |
| `model/` | Domain objects + `HttpResponse`, `UserPrincipal`, `Stats`, etc. |
| `dto/` + `dtomapper/` | `UserDTO` (the exposed/JWT-principal shape) + model→DTO mapper. |
| `form/` | Request bodies (`LoginForm`, `UpdateForm`, …). |
| `enumeration/` | Enums (`RoleType`, `EventType`, `VerificationType`). |
| `event/` + `listener/` | Spring `ApplicationEvent`s and their listeners (e.g. new-user audit events). |
| `exception/` | `ApiException` + `@RestControllerAdvice` `GlobalExceptionHandler`. |
| `handler/` | Auth entry point (401), access-denied (403), OAuth2 success handler. |
| `filter/` | `CustomAuthFilter` (per-request JWT validation). |
| `configuration/` | `SecurityConfig`, `WebMvcConfig`, OAuth2 config, federated-provider catalog. |
| `tokenprovider/` | `TokenProvider` (mint/parse JWTs). |
| `utils/`, `constants/` | Cross-cutting helpers and constants. |
| `seed/` | `DemoDataSeeder` (idempotent demo data on startup). |
| `report/` | Excel/report generation. |

---

## 3. Data-access pattern (no JPA for the core domain — the key trick)

Instead of JPA repositories, the core domain uses **`NamedParameterJdbcTemplate`** with four
cooperating pieces per aggregate:

1. **`XQuery`** — SQL string constants with named params, each documented.
2. **`XRowMapper`** — `ResultSet` → model via `Model.builder()...build()`.
3. **`XRepo`** (interface) — the CRUD contract.
4. **`XRepoImpl`** (`@Repository`, `@RequiredArgsConstructor`) — injects
   `NamedParameterJdbcTemplate`; binds with `MapSqlParameterSource`, inserts with
   `GeneratedKeyHolder`, treats `EmptyResultDataAccessException` as not-found, and
   `static`-imports the query constants.

`UserRepoImpl` additionally implements `UserDetailsService.loadUserByUsername` — that is the
authentication seam Spring Security calls during login.

> **Schema ownership:** `src/main/resources/schema.sql` is the single, idempotent definition
> of every JdbcTemplate-managed table (`CREATE TABLE IF NOT EXISTS`, no `DROP`s,
> `spring.sql.init.mode: never` — run by hand). Hibernate `ddl-auto: update` only manages the
> JPA-mapped customer/invoice/services tables. **Flyway was deliberately removed** (its baseline
> bookkeeping kept desyncing from the live DB). When mapping entities, always add an explicit
> `@Column` because `globally_quoted_identifiers: true` bypasses the snake_case strategy.

---

## 4. Security — stateless JWT, permission-based (`configuration/SecurityConfig.java`)

- `@EnableWebSecurity @EnableMethodSecurity`; `SessionCreationPolicy.STATELESS`; CSRF and
  HTTP Basic disabled; a CORS bean whitelists the SPA origins and exposes the `Jwt-Token` /
  `Authorization` headers.
- `AuthenticationManager` = `ProviderManager(DaoAuthenticationProvider)` with a
  `BCryptPasswordEncoder` (strength 12, defined as a bean in the main application class) and the
  `UserDetailsService` (`UserRepoImpl`).
- **`CustomAuthFilter` is registered `addFilterBefore(... UsernamePasswordAuthenticationFilter)`** —
  it parses/validates the Bearer JWT on every request and populates the `SecurityContext` with the
  `UserDTO` principal. Controllers read it via `@AuthenticationPrincipal UserDTO user`.
- **Authorization is authority-string based.** A Role's permission string is split into
  `SimpleGrantedAuthority` values like `READ:USER`, `UPDATE:CUSTOMER`, `DELETE:USER`,
  `UPDATE:ROLE`. Request matchers are evaluated **top-down**, so specific admin/self-service rules
  MUST precede the broad `GET/POST/PUT /**` catch-alls.
- Custom **401** (`CustomAuthenticationEntryPoint`) and **403** (`CustomAccessDeniedHandler`)
  handlers are wired into `exceptionHandling`.
- **Two public-URL lists must stay in lockstep:** `Constants.PUBLIC_URLS` (the filter chain's
  `permitAll`) and `Constants.PUBLIC_ROUTES` (the `CustomAuthFilter`'s `startsWith` skip list). If a
  route is permitted by the chain but missing from the filter's skip list, a stale client
  `Authorization: Bearer` header makes the filter try to parse a token and fail *before* the request
  reaches the public controller.
- **Federated login** via `oauth2Login` + `OAuth2LoginSuccessHandler` mints *our* JWTs (the
  token-exchange seam). Token issuance is centralized in `SessionService` (rotation, session
  families via the `sid` claim). Access token ≈ 30 min, refresh ≈ 5 days (see `Constants`).

---

## 5. Response contract

Every endpoint returns `ResponseEntity<HttpResponse>`. `HttpResponse` (`@SuperBuilder`,
`@JsonInclude(NON_DEFAULT)`) carries: `timeStamp`, `statusCode`, `status`, `reason`, `message`,
`devMessage`, `Map<?,?> data`, `path`. Controllers build it with `HttpResponse.builder()…` and
typically set `data(of("user", …, "<payload>", …))` — i.e. the authenticated user is embedded
alongside the requested payload, a contract the Angular client relies on.

---

## 6. Configuration & profiles (`src/main/resources/`)

- `application.yml` (shared) + `application-dev.yml` / `application-prod.yml`.
- All values come from environment variables (see `.env.example`); the `dev` profile supplies safe
  local fallbacks so the app boots without a full `.env`.
- Datasource = MySQL via `MYSQL_HOST/PORT/DATABASE/USERNAME/PASSWORD`; `jwt.secret=${JWT_SECRET}`;
  `ui.app.url` drives email/redirect links; mail via Gmail SMTP env vars. Actuator exposes only
  `health` + `info`.

---

## 7. To stand up a NEW backend fast (checklist)

1. Copy the `pom.xml` starters/profiles; rename the base package.
2. Recreate the package skeleton in §2.
3. For each table: write `XQuery` (named-param SQL) → `XRowMapper` → `XRepo`/`XRepoImpl`
   (`NamedParameterJdbcTemplate`) → `XService`/`XServiceImpl` → `XController` returning
   `HttpResponse`.
4. Add `schema.sql` (idempotent) and point the datasource env vars at it.
5. Reuse `SecurityConfig` + `CustomAuthFilter` + `TokenProvider` + the two
   `PUBLIC_URLS`/`PUBLIC_ROUTES` lists; define your Role permission strings and keep the matcher
   ordering specific-before-broad.
6. Reuse `HttpResponse` + `GlobalExceptionHandler` + the custom 401/403 handlers for a consistent
   API surface.

---

## 8. Honest gaps to fix in a clean copy (don't blind-copy)

- Near-zero automated tests.
- Missing `@Valid` on `register` / customer-create endpoints.
- Repository-layer business-logic bleed — `UserRepoImpl` carries password encoding, UUID/2FA-code
  generation, and validation that belong in the service layer (there's a standing TODO to extract it).
- SMS-based 2FA is a Twilio stub, not a live integration.
