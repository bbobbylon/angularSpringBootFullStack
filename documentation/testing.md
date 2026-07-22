# Testing Guide

The honest state of automated testing in TesseraApp: the current test inventory, how to run the backend and frontend suites, how to write a backend unit/slice test against this codebase's mocking seams, the Angular 21 Vitest setup, the integration approach (a full-context boot against local MySQL), the offline JPA schema-drift guard, and a frank gap register with a roadmap to broaden coverage.

> **Audience:** contributors adding or maintaining tests. This guide does **not** overstate coverage — it is deliberately modest, because the suite is modest.
> **Code wins over docs:** the counts below were read off `src/test/**` directly. Other artifacts (`week-5-plan.md`, `branch-changelog.md`) cite "6 suites / 14 tests"; the working tree actually has **5 test classes / 13 `@Test` methods**. If a doc and the code disagree, the code wins and the doc should be fixed.
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

Five backend test classes live under `src/test/java/com/bob/angularspringbootfullstack/`. There are **zero** frontend specs (see [§7](#7-frontend-testing-angular-21-vitest)). The suite is small but real; four of the five classes are pure/standalone and run in milliseconds with **no database**, which is the whole point of how they are written.

| Suite | `@Test`s | Type | What it locks in | Needs MySQL? |
|-------|---------:|------|------------------|:------------:|
| `AngularSpringBootFullStackApplicationTests` | 1 | `@SpringBootTest` integration | `contextLoads` — the full Spring context wires up end-to-end (all beans, security, both data paths) | ✅ yes |
| `service/serviceimpl/CustomerServiceImplTest` | 5 | Mockito unit | Service business rules: `createdAt` stamping, 10-char uppercase invoice numbers, not-found → `ApiException`, editable-field merge | ❌ no |
| `exception/GlobalExceptionHandlerTest` | 4 | Standalone MockMvc | `@RestControllerAdvice` → `HttpResponse` envelope: 400 on `@Valid`/malformed JSON, `ApiException` message pass-through, 500 that never leaks the cause | ❌ no |
| `controller/UserControllerLoginEnumerationTest` | 2 | Standalone MockMvc | Anti-enumeration (FR-AUTH-4 / NFR-SEC-7): unknown-email and wrong-password login failures are byte-identical bar the timestamp | ❌ no |
| `tooling/JpaSchemaSyncTest` | 1 | Offline Hibernate | `schema.sql` contains every table/column Hibernate maps (drift guard for `ddl-auto: validate` in prod) | ❌ no (no DB, offline DDL export) |
| **Total** | **13** | | | |

> **Why so few need MySQL.** Only `contextLoads` boots the real application context (and therefore the real datasource). Every other class was written specifically to exercise its target without a context or a connection — they use Mockito mocks, `MockMvcBuilders.standaloneSetup`, or Hibernate's offline schema export. So the meaningful unit/slice tests stay green in CI even with no database (`CustomerServiceImplTest:30`, `GlobalExceptionHandlerTest:33-35`, `UserControllerLoginEnumerationTest:52-55`).

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

From `securecapitaapp/`:

| Goal | Command |
|------|---------|
| Run unit tests (Vitest via Angular builder) | `npm test` (alias for `ng test`) |
| Lint | `npm run lint` |
| Format check / write | `npm run format:check` / `npm run format` |

`npm test` currently passes by finding **no spec files** — see [§7](#7-frontend-testing-angular-21-vitest).

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

**Status: ❌ zero specs.** There are no `*.spec.ts` files anywhere under `securecapitaapp/src/` (the only `.spec.ts` matches are inside `node_modules/`). `npm test` runs but discovers nothing.

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
| `tokenInterceptor` | `securecapitaapp/src/app/interceptor/token.interceptor.ts` | Public routes get **no** `Authorization` header; a 401 triggers a single-flight refresh-and-retry; concurrent 401s wait on the shared `BehaviorSubject` (no thundering herd) |
| `cacheInterceptor` | `securecapitaapp/src/app/interceptor/cache.interceptor.ts` | GET caches by full URL; any non-GET evicts the **entire** cache; a cache hit short-circuits before `tokenInterceptor` runs (registration order is load-bearing, `app.config.ts`) |
| `adminGuard` | `securecapitaapp/src/app/guard/admin.guard.ts` | Anonymous → redirect `/login`; authenticated-but-unauthorized → redirect `/`; `UPDATE:USER`/`UPDATE:ROLE` → allow |
| `UserService` token side-effects | `securecapitaapp/src/app/service/user.service.ts` | `refreshToken$()`/`updatePassword$()` rewrite both tokens in `localStorage`; `logOut()` clears tokens **and** calls `httpCache.evictAll()`; `handleError` surfaces `error.error.reason` |

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

Stated plainly: coverage is **modest** — 5 backend classes / 13 tests and 0 frontend specs. The existing tests are well-targeted (anti-enumeration, the error envelope, service rules, schema drift, and a context-load wiring check), but the project's headline security features are **not** directly tested, and the SPA is entirely untested. Status legend: ✅ covered · 🔄 partial · ❌ not covered.

| Area | Status | Where it lives | Gap |
|------|:------:|----------------|-----|
| Service business rules (customer/invoice) | ✅ | `CustomerServiceImplTest` | Only the customer service; other services untested |
| Error → `HttpResponse` envelope | ✅ | `GlobalExceptionHandlerTest` | — |
| Login anti-enumeration | ✅ | `UserControllerLoginEnumerationTest` | — |
| `schema.sql` ↔ JPA drift | ✅ | `JpaSchemaSyncTest` | Offline only; no real prod-profile `validate` boot ever run |
| Context wiring | 🔄 | `AngularSpringBootFullStackApplicationTests` | Boots, asserts nothing; requires live MySQL; not hermetic |
| Refresh-token **rotation & reuse detection** | ❌ | `SessionServiceImpl` (token-issuance seam) | The project's headline security feature has no dedicated test |
| **TOTP** enrollment / challenge-bound verify / single-use recovery codes | ❌ | `TotpServiceImpl`, `TotpUtils`, `TotpController` | No test for RFC-6238 verification or challenge binding |
| **Organization-scoped** admin authorization | ❌ | `AdminUserController`, `OrganizationServiceImpl` | No test that out-of-scope access returns 403 / `APPLICATION_ADMIN` bypasses |
| Real `SecurityConfig` matchers / `CustomAuthFilter` | ❌ | `configuration/`, `filter/` | Slice tests bypass the filter chain by design |
| Brute-force per-account lockout | ❌ | `UserController.authenticate` + `EventService` | 5-failures/15-min window untested |
| Frontend (interceptors, guards, services, components) | ❌ | `securecapitaapp/src/` | Zero specs despite a configured Vitest harness ([§7](#7-frontend-testing-angular-21-vitest)) |
| HTTP-level integration (real requests, fixtures) | ❌ | — | No `TestRestTemplate`/Testcontainers layer |

### Roadmap to broaden (prioritized)

| Priority | Step | Why first |
|:--------:|------|-----------|
| **P1** | Unit-test the security-critical seams with Mockito (same pattern as [§3](#3-writing-a-backend-unit-test-mock-the-reposservices)): `SessionServiceImpl` rotation + family-wide reuse revocation; `TotpServiceImpl`/`TotpUtils` verify + recovery-code single-use; `AdminUserController` org-scope 403/bypass | These are the headline claims and carry the most risk if they regress; they need no DB |
| **P1** | Add the first frontend specs for `tokenInterceptor`, `cacheInterceptor`, and `adminGuard` ([§7](#7-frontend-testing-angular-21-vitest)) | Most logic-dense, highest-leverage; harness already configured |
| **P2** | Replace/supplement `contextLoads` with a **Testcontainers MySQL** `@SpringBootTest` so integration is hermetic and CI-friendly (removes the "needs local MySQL" footgun in [§5](#5-integration-approach-contextloads-against-local-mysql)) | Unblocks DB-backed tests in CI without a manual MySQL |
| **P2** | Add a `@SpringBootTest(webEnvironment=RANDOM_PORT)` + `TestRestTemplate` happy-path per controller, asserting the `HttpResponse` envelope and real `SecurityConfig` authority rules | Covers the filter chain the standalone slices skip |
| **P3** | A real prod-profile boot with `ddl-auto=validate` against a `schema.sql`-only database, to retire `JpaSchemaSyncTest`'s "offline stand-in" caveat | Confirms the drift guard's premise end-to-end |
| **P3** | Wire `dependency-check` and the test suite into CI so they gate merges | Makes the gates above continuous, not on-demand |

> **Standing honesty rule for this doc:** when coverage genuinely improves, update [§1](#1-current-test-inventory)'s counts and this register from the code — never from another doc. The "near-zero tests" framing in older artifacts is stale; "modest but real" is accurate; do not let either drift into "well-tested" without the tests to back it.
