# Project Instructions

Full-stack app: **Angular (latest) frontend + Spring Boot (Boot 4.0.6 / Java 21) backend**,
MySQL via `NamedParameterJdbcTemplate`, stateless JWT auth. See `documentation/` for the full
guides (hub: `documentation/README.md`).

## Backend Blueprint (reusable — how this backend is built)

This section exists so the backend's shape is portable across environments (web, terminal,
teammates). Full detail: **[documentation/backend-blueprint.md](documentation/backend-blueprint.md)**.

**Layered package layout** (base package `com.bob.angularspringbootfullstack`):
`controller/` → `service/` + `service/serviceimpl/` → `repo/` + `repo/repoimpl/`, supported by
`query/` (SQL constants), `rowmapper/`, `model/`, `dto/` + `dtomapper/`, `form/`, `enumeration/`,
`event/` + `listener/`, `exception/`, `handler/`, `filter/`, `configuration/`, `tokenprovider/`,
`utils/`, `constants/`, `seed/`, `report/`.

**Data access (core domain uses JDBC, not JPA):** per aggregate, four pieces wired with
`NamedParameterJdbcTemplate` — `XQuery` (named-param SQL constants) + `XRowMapper`
(`ResultSet`→model via Lombok builder) + `XRepo` (interface) + `XRepoImpl` (`@Repository`).
`UserRepoImpl` also implements `UserDetailsService`. Schema is owned by
`src/main/resources/schema.sql` (idempotent, no DROPs, `sql.init.mode: never`); Hibernate
`ddl-auto: update` only manages the JPA customer/invoice/services tables. **No Flyway** (removed
on purpose). Add explicit `@Column` on entities — `globally_quoted_identifiers: true` bypasses the
snake_case strategy.

**Security (stateless JWT, permission-based):** `CustomAuthFilter` runs before
`UsernamePasswordAuthenticationFilter`, validates the Bearer JWT, and sets a `UserDTO` principal
(read via `@AuthenticationPrincipal UserDTO user`). Authority strings (`READ:USER`,
`UPDATE:CUSTOMER`, `DELETE:USER`, `UPDATE:ROLE`) gate endpoints; matchers are evaluated top-down,
so specific rules precede the broad `/**` catch-alls. `Constants.PUBLIC_URLS` (filter chain) and
`Constants.PUBLIC_ROUTES` (filter skip list) **must stay in lockstep**. Federated OAuth2 login
mints our own JWTs via `OAuth2LoginSuccessHandler`; token issuance is centralized in
`SessionService`.

**Response contract:** every endpoint returns `ResponseEntity<HttpResponse>` (timestamp / status /
message / `Map data` envelope), usually embedding the authenticated user alongside the payload.

## Conventions

- Always use the latest Angular and latest Spring; migrate old patterns to modern equivalents.
- Full multi-line Javadoc/TSDoc that explains how a class relates to the rest of the codebase.
- Never reveal whether an email/identifier exists via error messages (user-enumeration risk).
- Verify the app by running `./start.sh` (foreground); ask before any destructive DB operation.
