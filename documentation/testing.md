# Testing Guide

The honest state of automated testing in TesseraApp: the current test inventory, how to run the backend and frontend suites, how to write a backend unit/slice test against this codebase's mocking seams, the Angular 21 Vitest setup, the integration approach (a full-context boot against local MySQL), the offline JPA schema-drift guard, and a frank gap register with a roadmap to broaden coverage.

> **Audience:** contributors adding or maintaining tests. This guide does **not** overstate coverage — where something is untested it says so.
> **Code wins over docs:** the counts below were read off `src/test/**` and `tesseraapp/src/**` directly. If a doc and the code disagree, the code wins and the doc should be fixed.
> **See also:** [developer-guide.md §8](developer-guide.md#8-testing--quality) (one-line summary this guide expands) · [configuration.md](configuration.md) (profiles/env the context-load test needs) · [security.md](security.md) (the anti-enumeration contract one test guards) · [database.md](database.md) (the schema the drift guard validates).

---

## Table of contents

1. [Current test inventory](#1-current-test-inventory)
2. [How to run the tests](#2-how-to-run-the-tests)
3. [Writing a backend unit test (mock the repos/services)](#3-writing-a-backend-unit-test-mock-the-reposservices)
4. [Writing a backend slice test (standalone MockMvc + advice)](#4-writing-a-backend-slice-test-standalone-mockmvc--advice)
5. [Integration approach: contextLoads against local MySQL](#5-integration-approach-contextloads-against-local-mysql)
6. [The JPA schema-drift guard](#6-the-jpa-schema-drift-guard)
7. [Frontend testing (Angular 21 Vitest)](#7-frontend-testing-angular-21-vitest)
8. [Other quality gates](#8-other-quality-gates)
9. [Known coverage gaps & roadmap](#9-known-coverage-gaps--roadmap)

---

## 1. Current test inventory

**21 backend test classes / 116 `@Test` methods**, plus **7 frontend spec files / 79 specs**. Only one
backend class needs a database; everything else runs in milliseconds against mocks, standalone
MockMvc, or Hibernate's offline schema export — which is the whole point of how they are written.

### Backend — security & access control

| Suite | Tests | What it locks in |
|-------|------:|------------------|
| `service/serviceimpl/SessionServiceImplTest` | 4 | **Refresh rotation & replay detection** — the happy path (old row superseded, a *different* jti issued, family preserved), plus superseded/revoked replays revoking the whole family without rotating |
| `service/serviceimpl/TotpServiceImplTest` | 5 | **TOTP challenge binding** — identity comes from the challenge, never the request; a wrong code refuses *without* burning the challenge; recovery codes validate-and-consume atomically |
| `service/serviceimpl/LoginRiskServiceImplTest` | 12 | Anomaly detection (FR-TPF-1), both failure directions — false positives matter as much as true ones |
| `controller/AdminUserControllerOrgScopeTest` | 5 | **Org scoping on reads as well as writes**, platform admins never scope-checked, and a non-enumerating 403 |
| `controller/AnalyticsControllerOrgScopeTest` | 8 | Scoped analytics: assertions in pairs, because calling the *unscoped* variant is the bug |
| `controller/AnalyticsControllerSecurityTest` | 3 | The `/admin/analytics/**` authority gate |
| `service/serviceimpl/FederatedIdentityUnlinkTest` | 5 | The unlink guard — both halves of "no password *and* no second provider" |
| `constants/CapabilityCatalogTest` | 6 | 403s name the blocked capability without leaking record existence |
| `controller/UserControllerLoginEnumerationTest` | 2 | Anti-enumeration: unknown-email and wrong-password failures are byte-identical bar the timestamp |
| `controller/UserControllerBruteForceLockTest` | 2 | Per-account lockout |
| `controller/AdminUserControllerTest` | 3 | Path id is authoritative; self-targeting refused |
| `utils/RequestUtilsIpAddressTest` | 10 | `X-Forwarded-For` trust, forgery cases included |
| `utils/AuthDiagnosticsLoggerTest` | 9 | Console-only RBAC diagnostics stay off the client response |
| `exception/ErrorDetailScrubberTest` | 6 | Prod error bodies carry no internal detail |

### Backend — application & infrastructure

| Suite | Tests | What it locks in | Needs MySQL? |
|-------|------:|------------------|:------------:|
| `service/SecurityDashboardServiceImplTest` | 11 | Window clamping, zero-filled counters, gap-filled trend, empty scope failing closed *before* any query | ❌ |
| `service/serviceimpl/CustomerServiceImplTest` | 5 | Service business rules: `createdAt` stamping, invoice numbering, not-found → `ApiException` | ❌ |
| `service/serviceimpl/EventServiceImplTest` | 2 | Audit event recording | ❌ |
| `listener/NewUserEventListenerTest` | 2 | A failing audit write must not break login | ❌ |
| `exception/GlobalExceptionHandlerTest` | 4 | The `HttpResponse` envelope; a 500 never leaks its cause | ❌ |
| `tooling/JpaSchemaSyncTest` | 1 | `schema.sql` contains every table/column Hibernate maps (drift guard for `ddl-auto: validate`) | ❌ (offline DDL export) |
| `AngularSpringBootFullStackApplicationTests` | 1 | `contextLoads` — the full context wires up end-to-end | ✅ |
| **Total** | **116** | | |

### Frontend (Vitest + jsdom)

| Spec | Tests | What it locks in |
|------|------:|------------------|
| `service/user.service.authority.spec.ts` | 20 | **JWT authority decoding** — exact (not prefix) matching, expiry beating a privileged claim, memo invalidation across token rotation, and six shapes of corrupt token that must grant nothing *without throwing* |
| `interceptor/token.interceptor.spec.ts` | 15 | **Silent refresh on 401** — one refresh shared by concurrent 401s, retry replaying method/URL/body, token clearing on refresh failure, and parked requests failing rather than hanging |
| `shared/command-palette/command-palette.component.spec.ts` | 12 | Hotkey gating, authority-filtered command sets, filtering, keyboard model |
| `directive/has-authority.directive.spec.ts` | 9 | Capability gating: hide vs disable, `else` templates, accessibility of the disabled state |
| `guard/authentication.guard.spec.ts` | 9 | The session gate, driven against **real token storage** as well as a double — a corrupt token must redirect to `/login`, not throw out of the guard |
| `guard/admin.guard.spec.ts` | 7 | Route gating + the localized, non-enumerating denial message |
| `guard/capability.guard.spec.ts` | 7 | Route-data-driven gating, fail-closed on a missing declaration |
| **Total** | **79** | |

> **Five of these are true regression tests.** They were confirmed to fail against the pre-fix code
> before the fixes landed — a green suite written after the fix proves only that the suite runs. They
> cover three defects found while writing them: a failed token refresh left concurrently-parked
> requests hanging forever (no value, no error, no completion), an unparseable token in
> `localStorage` threw *out of* `authenticationGuard` instead of redirecting, and the interceptor's
> public-route check matched substrings of the whole URL, so `/customer/search?name=login` was sent
> unauthenticated.

> **Two test helpers support these.** `testing/jwt.ts` builds unsigned tokens in shapes a real
> `TokenProvider` would never emit (no `exp`, no `authorities`, truncated) — legitimate, because the
> browser never verifies a signature. `testing/local-storage.ts` installs an in-memory `Storage` over
> the global: the `@angular/build:unit-test` environment provides a `window` but its `localStorage`
> is an inert placeholder with no `getItem`/`setItem`/`clear`, so any spec touching tokens fails on
> a "not a function" `TypeError` unrelated to the code under test.

> **Why so little needs MySQL.** Only `contextLoads` boots the real application context. Everything else was written to exercise its target without a context or a connection, so the meaningful unit/slice tests stay green in CI with no database.

Test-scope dependencies (both in `pom.xml`): `spring-boot-starter-test` (JUnit 5 / Jupiter, Mockito, AssertJ, Hamcrest, Spring `MockMvc`) at `pom.xml:130-133`, and `spring-security-test` at `pom.xml:135-138`.

---

## 2. How to run the tests

### Backend (Maven)

The repo ships the Maven wrapper, so no local Maven install is required.

| Goal | Command |
|------|---------|
| Run the whole suite (wrapper) | `./mvnw test` (Windows: `mvnw.cmd test`) |
| Run the whole suite (system Maven) | `mvn test` |
| Build the jar (runs tests first) | `./mvnw package` |
| Run one class | `./mvnw test -Dtest=CustomerServiceImplTest` |
| Run one method | `./mvnw test -Dtest=CustomerServiceImplTest#createInvoice_generatesInvoiceNumber` |
| Run everything **except** the DB-bound boot test (e.g. CI with no MySQL) | `./mvnw test -Dtest='!AngularSpringBootFullStackApplicationTests'` |

> **Gotcha — `contextLoads` needs a database.** `./mvnw test` activates the default `dev` profile (`pom.xml:174-185`) and there is **no `src/test/resources/application*.yml`** override, so `AngularSpringBootFullStackApplicationTests` connects to the dev datasource — i.e. a live local MySQL (`db2`) must be up, with `schema.sql` already applied. If MySQL is down, that one test errors while the other four still pass. To run the fast suite in isolation, exclude it as shown above. See [getting-started.md](getting-started.md) for bringing the DB up and [configuration.md](configuration.md) for the dev profile.

### Frontend (npm)

From `tesseraapp/`:

| Goal | Command |
|------|---------|
| Run unit tests (Vitest via Angular builder) | `npm test` (alias for `ng test`) |
| Lint | `npm run lint` |
| Format check / write | `npm run format:check` / `npm run format` |

`npm test` runs 4 spec files / 35 specs through Vitest + jsdom — see [§7](#7-frontend-testing-angular-21-vitest).

---

## 3. Writing a backend unit test (mock the repos/services)

The codebase's interface-plus-`Impl` convention ([developer-guide.md §6](developer-guide.md#6-conventions--patterns)) exists precisely so collaborators are mockable seams. `CustomerServiceImplTest` is the reference pattern: the service under test is a plain object, every collaborator (`CustomerRepo`, `InvoiceRepo`, `ServicesRepo`, `NamedParameterJdbcTemplate`) is a Mockito `@Mock`, and Mockito injects them via `@InjectMocks`. No Spring, no DB.

The skeleton, lifted from `CustomerServiceImplTest:34-47`:

```java
@ExtendWith(MockitoExtension.class)              // Mockito, not Spring — no context boot
class CustomerServiceImplTest {

    @Mock private CustomerRepo customerRepo;       // JPA repo → mocked
    @Mock private InvoiceRepo invoiceRepo;
    @Mock private ServicesRepo servicesRepo;
    @Mock private NamedParameterJdbcTemplate jdbcTemplate; // identity-side JDBC seam

    @InjectMocks private CustomerServiceImpl customerService; // SUT, mocks wired in
}
```

Three techniques worth copying:

| Technique | Example in the suite | Use it for |
|-----------|----------------------|------------|
| `thenAnswer(inv -> inv.getArgument(0))` | `CustomerServiceImplTest:53,64,96` | A `save` mock that echoes its argument back, so you can assert on what the service produced (timestamps, generated numbers) |
| `ArgumentCaptor<Customer>` + `verify(...).save(captor.capture())` | `CustomerServiceImplTest:95-101` | Assert on the exact object handed to the repo (id preserved, editable fields applied) |
| `assertThrows(ApiException.class, () -> ...)` on `findById(...).thenReturn(Optional.empty())` | `CustomerServiceImplTest:73-87` | Not-found behaviour — the service raises `ApiException`, which the global handler maps to a 400 |

**Where the assertions point.** Keep business logic (UUID/code generation, timestamping, validation, encoding) in the **service** layer so it is unit-testable here, not in the repo — this is the project's standing rule and the reason these tests can exist at the service level at all.

> **AssertJ over plain JUnit.** The suite uses `assertThat(...).isEqualTo/isNotNull/hasSize/matches(...)` (`spring-boot-starter-test` bundles AssertJ). Prefer it for readability; `assertThrows` from Jupiter is used only for the exception cases.

---

## 4. Writing a backend slice test (standalone MockMvc + advice)

When you need the real HTTP-layer behaviour (validation, status codes, the `HttpResponse` envelope) but **not** a full context, security filter chain, or datasource, use `MockMvcBuilders.standaloneSetup(...)` with the real `@RestControllerAdvice` registered. Two suites demonstrate this.

**`GlobalExceptionHandlerTest`** wires a throwaway `ProbeController` plus the genuine `GlobalExceptionHandler` (`GlobalExceptionHandlerTest:48-50`):

```java
mockMvc = MockMvcBuilders.standaloneSetup(new ProbeController())
        .setControllerAdvice(new GlobalExceptionHandler())   // the real advice
        .build();
```

Standalone setup wires a validator, so `@Valid` on the probe body produces a genuine `MethodArgumentNotValidException` — the exact exception the handler claims to catch — and the test asserts the envelope shape with `jsonPath` (`GlobalExceptionHandlerTest:60-66`):

```java
.andExpect(status().isBadRequest())
.andExpect(jsonPath("$.statusCode", is(400)))
.andExpect(jsonPath("$.status", is("400 BAD_REQUEST")))   // HttpStatus.toString(), not just the name
.andExpect(jsonPath("$.reason", containsString("name")));
```

**`UserControllerLoginEnumerationTest`** goes one step further: it constructs the real `UserController` with all eight collaborators mocked (`UserControllerLoginEnumerationTest:74-88`), then stubs the two divergent internal paths so it can prove they converge:

| Failure mode | Stub | Internal path |
|--------------|------|---------------|
| Unknown email | `userService.getUserByEmail(UNKNOWN).thenThrow(UsernameNotFoundException)` | swallowed to `null`, **no** audit events fire — so the audit log is not an oracle either |
| Wrong password | `getUserByEmail(KNOWN).thenReturn(new UserDTO())` + `authenticationManager.authenticate(any()).thenThrow(BadCredentialsException)` | account resolves, audit events fire, then credentials rejected |

Both must produce a byte-identical 400 envelope; the test strips only the per-call `timeStamp` and asserts full-body equality (`UserControllerLoginEnumerationTest:120-142`). This is the regression guard for the enumeration-safety standing rule (generic "Invalid email or password." for unknown-email vs wrong-password) — see [security.md](security.md).

**When to use which.** Use the standalone slice test when the behaviour under test lives in the controller↔advice boundary (status codes, validation, error shape, header exposure) and you can mock the services beneath it. Reach for a full `@SpringBootTest` ([§5](#5-integration-approach-contextloads-against-local-mysql)) only when you genuinely need the wired graph (real security filters, real datasource).

> **Note — `@WebMvcTest` is not used here.** Both slice tests use `standaloneSetup` rather than `@WebMvcTest`, deliberately, to avoid loading any Spring context (including security auto-config) at all. That keeps them context-free and DB-free; the trade-off is they don't exercise the real `SecurityConfig` matchers or `CustomAuthFilter` — that is an explicit gap ([§9](#9-known-coverage-gaps--roadmap)).

---

## 5. Integration approach: contextLoads against local MySQL

The single integration test is the canonical Spring Boot smoke test (`AngularSpringBootFullStackApplicationTests:6-12`):

```java
@SpringBootTest
class AngularSpringBootFullStackApplicationTests {
    @Test void contextLoads() {}
}
```

`@SpringBootTest` with no `webEnvironment` and no profile override boots the **entire** application context under the default `dev` profile: every controller/service/repo bean, the security filter chain, the JDBC identity path, the JPA business path, and the real datasource. Because there is no test-scoped config and `spring.sql.init.mode: never`, this boots against your **live local MySQL `db2`** with `schema.sql` already applied. If the wiring is broken (a missing bean, a bad `@Value`, a circular placeholder, a datasource that won't connect) this test fails — which is exactly its value as a cheap end-to-end wiring check.

What it does **not** do: it asserts nothing beyond "the context started." It is not an API integration test — there are no `TestRestTemplate`/`WebTestClient` round-trips, no `@Sql` fixtures, no Testcontainers. Adding a true HTTP-level integration layer (ideally with Testcontainers MySQL so it is hermetic and CI-friendly) is on the roadmap ([§9](#9-known-coverage-gaps--roadmap)).

> **Gotcha:** because this test depends on a real DB, it is the one suite that breaks in a database-less CI run. Exclude it there (`-Dtest='!AngularSpringBootFullStackApplicationTests'`) until it is replaced or supplemented by a Testcontainers-backed version.

---

## 6. The JPA schema-drift guard

`JpaSchemaSyncTest` is a bespoke, database-free guard that keeps `src/main/resources/schema.sql` in lockstep with the JPA-mapped entities (`Customer`, `Invoice`, `Services`, and the `InvoiceLineItem` `@ElementCollection`). It exists because **production runs `spring.jpa.hibernate.ddl-auto: validate`** (`application-prod.yml`), so Hibernate refuses to boot if a mapped table/column is missing from the hand-maintained schema — and `globally_quoted_identifiers: true` means the expected columns are quoted camelCase (`` `phoneNumber` ``, `` `invoiceNumber` ``), which are easy to get wrong by hand ([database.md](database.md)).

How it works (`JpaSchemaSyncTest:91-120`): it drives JPA-standard schema-script generation with the dialect pinned to `MySQLDialect`, `globally_quoted_identifiers=true` to mirror runtime, and `hibernate.boot.allow_jdbc_metadata_access=false` so a CREATE script is produced **without opening any DB connection**. It then extracts every backtick-quoted identifier from the `CREATE TABLE` section (excluding Hibernate's randomly-named FK `ALTER`s) and asserts `schema.sql` contains each one (`JpaSchemaSyncTest:53-79`).

| Property | Value | Why |
|----------|-------|-----|
| Output file | `target/generated-jpa-schema.sql` | Also the documented source for transplanting DDL into `schema.sql` |
| DB needed | none | Offline export; `allow_jdbc_metadata_access=false` |
| Failure mode | "schema.sql is missing the quoted identifier `X`…" | Caught at **build time**, not at the next prod deploy |

> **Recipe — adding a JPA entity field.** Add the field, run `./mvnw test -Dtest=JpaSchemaSyncTest`, open `target/generated-jpa-schema.sql`, copy the new column into the matching `CREATE TABLE IF NOT EXISTS` in `schema.sql` (FKs inlined with your own stable constraint name for idempotency), and apply it to your DB by hand. This test is the offline stand-in for a real prod-profile `validate` boot, which has not yet been exercised against a `schema.sql`-only database.

---

## 7. Frontend testing (Angular 21 Vitest)

**Status: ❌ zero specs.** There are no `*.spec.ts` files anywhere under `tesseraapp/src/` (the only `.spec.ts` matches are inside `node_modules/`). `npm test` runs but discovers nothing.

The harness, however, is configured and ready:

| Piece | Where | Value |
|-------|-------|-------|
| Test builder | `angular.json:73-75` | `@angular/build:unit-test` (Angular's Vitest-based unit runner) |
| Runner + DOM | `package.json:43,47` | `vitest ^4.0.8`, `jsdom ^28.0.0` (devDependencies) |
| Spec TS config | `tsconfig.spec.json` | `types: ["vitest/globals"]`, includes `src/**/*.spec.ts` |
| Script | `package.json:9` | `"test": "ng test"` |

Because `vitest/globals` is configured, `describe/it/expect/vi` are available without imports. The standard Angular pattern still applies — use `TestBed` for components/services and `HttpTestingController` (via `provideHttpClientTesting()`) for the HTTP facades.

**The highest-value first specs**, given this app's frontend internals, would target the pieces with real logic rather than templates:

| Target | File | What to assert |
|--------|------|----------------|
| `tokenInterceptor` | `tesseraapp/src/app/interceptor/token.interceptor.ts` | Public routes get **no** `Authorization` header; a 401 triggers a single-flight refresh-and-retry; concurrent 401s wait on the shared `BehaviorSubject` (no thundering herd) |
| `cacheInterceptor` | `tesseraapp/src/app/interceptor/cache.interceptor.ts` | GET caches by full URL; any non-GET evicts the **entire** cache; a cache hit short-circuits before `tokenInterceptor` runs (registration order is load-bearing, `app.config.ts`) |
| `adminGuard` | `tesseraapp/src/app/guard/admin.guard.ts` | Anonymous → redirect `/login`; authenticated-but-unauthorized → redirect `/`; `UPDATE:USER`/`UPDATE:ROLE` → allow |
| `UserService` token side-effects | `tesseraapp/src/app/service/user.service.ts` | `refreshToken$()`/`updatePassword$()` rewrite both tokens in `localStorage`; `logOut()` clears tokens **and** calls `httpCache.evictAll()`; `handleError` surfaces `error.error.reason` |

Sketch using the configured globals + `HttpTestingController`:

```ts
describe('cacheInterceptor', () => {
  it('evicts the whole cache on a POST', () => {
    const cache = TestBed.inject(HttpCacheService);
    const evict = vi.spyOn(cache, 'evictAll');
    // ...fire a POST through HttpClient, then:
    expect(evict).toHaveBeenCalled();
  });
});
```

> **Note.** `npm test` "passing" today means "nothing to run," not "everything green." Do not read the green as coverage.

---

## 8. Other quality gates

Beyond the JUnit/Vitest suites, the build wires two Maven quality plugins (neither bound to a lifecycle phase, so they run on demand):

| Gate | Command | Config | Notes |
|------|---------|--------|-------|
| OWASP dependency CVE scan | `./mvnw org.owasp:dependency-check-maven:check` | `pom.xml:222-229`, `failBuildOnCVSS=7` | Fails the build on any dependency CVE ≥ 7.0. First run downloads the NVD database (slow) |
| Dependency/plugin version report | `./mvnw versions:display-dependency-updates` | `pom.xml:230-234` | Reports newer artifact versions (latest-Spring/Angular convention) |
| Frontend lint | `npm run lint` | `angular.json:76-81`, angular-eslint | Lints `src/**/*.{ts,html}` |
| Frontend format | `npm run format:check` | Prettier | |

**Manual smoke test** (from [developer-guide.md §8](developer-guide.md#8-testing--quality)): log in as `eve.admin@tessera.dev` / `TesseraDemo@1`, open the admin dashboard, enroll TOTP in the Security Center, and check the audit log on the profile page. This is currently the only coverage for the federation, TOTP, session-management, and admin flows.

---

## 9. Known coverage gaps & roadmap

Stated plainly: coverage is **modest but real** — 195 tests across 28 files, and every headline
security claim now has at least one dedicated test. What remains uncovered is a specific, nameable
shape: **nothing exercises the real filter chain, and nothing exercises a real browser.** Every
test below either mocks its collaborators or drives a standalone `MockMvc` that skips
`SecurityConfig` by design. Status legend: ✅ covered · 🔄 partial · ❌ not covered.

| Area | Status | Where it lives | Gap |
|------|:------:|----------------|-----|
| Refresh-token **rotation & reuse detection** | ✅ | `SessionServiceImplTest` | — |
| **TOTP** challenge binding / single-use recovery codes | ✅ | `TotpServiceImplTest` | RFC-6238 time-window drift not directly exercised |
| **Organization-scoped** admin authorization | ✅ | `AdminUserControllerOrgScopeTest`, `AnalyticsControllerOrgScopeTest` | — |
| Brute-force per-account lockout | ✅ | `UserControllerBruteForceLockTest` | — |
| Login anti-enumeration | ✅ | `UserControllerLoginEnumerationTest` | — |
| Anomaly detection / step-up (FR-TPF-1) | ✅ | `LoginRiskServiceImplTest` | — |
| `X-Forwarded-For` trust | ✅ | `RequestUtilsIpAddressTest` | Trust depth is config; no deployed proxy has confirmed it |
| Error → `HttpResponse` envelope | ✅ | `GlobalExceptionHandlerTest`, `ErrorDetailScrubberTest` | — |
| Service business rules (customer/invoice) | ✅ | `CustomerServiceImplTest` | Only the customer service; other services untested |
| **Frontend token refresh on 401** | ✅ | `token.interceptor.spec.ts` | — |
| **Frontend session gate + JWT decoding** | ✅ | `authentication.guard.spec.ts`, `user.service.authority.spec.ts` | — |
| Frontend capability gating (guards, directives) | ✅ | `admin.guard`, `capability.guard`, `has-authority.directive` specs | — |
| `schema.sql` ↔ JPA drift | 🔄 | `JpaSchemaSyncTest` | **Offline only.** No prod-profile `ddl-auto=validate` boot has ever run against a `schema.sql`-only database |
| Context wiring | 🔄 | `AngularSpringBootFullStackApplicationTests` | Boots, asserts nothing; requires live MySQL; not hermetic |
| Frontend HTTP cache (`cacheInterceptor`) | ❌ | `tesseraapp/src/app/interceptor/cache.interceptor.ts` | Client-only cache with no cross-user invalidation — the one interceptor still unspecced |
| Real `SecurityConfig` matchers / `CustomAuthFilter` | ❌ | `configuration/`, `filter/` | Slice tests bypass the filter chain by design, so matcher **ordering** — the thing most likely to break — is unverified |
| HTTP-level integration (real requests, fixtures) | ❌ | — | No `TestRestTemplate`/Testcontainers layer |
| End-to-end (browser against a running stack) | ❌ | — | Seams pass CI: interceptor ↔ backend, OAuth redirect round-trip, federated link flow |

### Roadmap to broaden (prioritized)

| Priority | Step | Why first |
|:--------:|------|-----------|
| **P1** | A real prod-profile boot with `ddl-auto=validate` against a `schema.sql`-only MySQL | The single largest untested assumption in the project: `JpaSchemaSyncTest` validates the *premise* offline, but a deploy that builds cleanly and then fails at startup is the most likely production failure |
| **P1** | Add a `@SpringBootTest(webEnvironment=RANDOM_PORT)` + `TestRestTemplate` happy path per controller, asserting the envelope and the **real** `SecurityConfig` authority rules | Covers the filter chain every slice test skips — including `PUBLIC_URLS` ↔ `PUBLIC_ROUTES` lockstep and matcher ordering, which have no automated guard at all |
| **P2** | Replace/supplement `contextLoads` with a **Testcontainers MySQL** `@SpringBootTest` | Unblocks DB-backed tests in CI without a manual MySQL, and removes the "needs local MySQL" footgun in [§5](#5-integration-approach-contextloads-against-local-mysql) |
| **P2** | Specs for `cacheInterceptor` | The last unspecced interceptor; its invalidation rules are the kind of logic that silently serves one user another's data |
| **P3** | Playwright end-to-end against `docker-compose up` | The only way to catch seam breaks; also the only way to exercise a federated login round-trip |
| **P3** | Distributed-state tests for rate limiting and brute-force counters once they move off per-instance memory | Meaningless until the state is actually shared — see [cicd-setup.md §6](cicd-setup.md#6-security-controls-that-depend-on-the-pipeline) |

> **Closed since the last revision.** Refresh rotation, TOTP, org scoping, brute-force lockout, the
> `tokenInterceptor` refresh path and the frontend JWT-decoding edges all moved from ❌ to ✅. CI now
> runs the dependency-check gate, `npm audit`, `ng lint` and both test suites on every push, and
> gates both deploy pipelines — so these are continuous rather than on-demand.

> **Standing honesty rule for this doc:** when coverage genuinely improves, update [§1](#1-current-test-inventory)'s counts and this register from the code — never from another doc. The "near-zero tests" framing in older artifacts is stale; "modest but real" is accurate; do not let either drift into "well-tested" without the tests to back it.
