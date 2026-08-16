# Post-Submission Upgrades

**Version:** 1.0
**Last Updated:** 2026-08-15
**Status:** Living — tracks work built *after* the Master's course submission.

## Overview

Everything in this document is **out of scope for the submitted deliverable**. The SRS, the final
report, and the presented demo describe the system as it stood at submission time; nothing tracked
here was part of that grading package, and none of it should be assumed present when reproducing
the submitted build.

This list exists so continued work on the app after the course ends doesn't get silently folded
back into academic deliverables that have already been written and submitted, and so a future
reader (including a future me) can tell at a glance which features are "the thing that got graded"
versus "the thing built afterward because the project kept going."

Everything here was pulled from [FUTURE-ENHANCEMENTS.md §3](FUTURE-ENHANCEMENTS.md) — that document
remains the working backlog and technical detail lives there; this document is the scope boundary,
not a duplicate spec.

**Status legend:** ⬜ not started · 🔄 in progress · ✅ done

## Boundary

Everything below is post-submission by definition. **Submission was Sunday, 2026-08-09** (per the
user, 2026-08-14). No exact commit was named as the cutoff, and Sunday itself has commits on both
sides of an unstated submission time (`5272506`, `2f72eaf`, `3a8200f`, `3c3066e` all land that day)
— so this pins the *date*, not a commit hash. Everything in [Row N of
IMPLEMENTATION-HISTORY.md](IMPLEMENTATION-HISTORY.md) (2026-08-09 → 08-14 and onward) should be
treated as post-submission work regardless of which side of Sunday it fell on, since the whole day
is inside the boundary's uncertainty window.

## Upgrade queue

Tracked in the order they're being picked up.

| # | Item | Spec | Status |
|---|---|---|---|
| 1 | List sorting & filtering | FUTURE-ENHANCEMENTS.md §3.3 | ✅ |
| 2 | Backend-driven i18n | FUTURE-ENHANCEMENTS.md §3.3 | ✅ |
| 3 | Backend HTTP caching | FUTURE-ENHANCEMENTS.md §3.4 | ✅ |
| 4 | API testing suite (Postman/Bruno/cURL) | FUTURE-ENHANCEMENTS.md §3.4 | ✅ |
| 5 | Structured logging + metrics | FUTURE-ENHANCEMENTS.md §3.4 | ✅ |
| 6 | Scheduled/on-demand report & metrics emails | FUTURE-ENHANCEMENTS.md §3.3 | ⬜ |
| 7 | Email invoices/documents as PDF attachments | FUTURE-ENHANCEMENTS.md §3.3 | ⬜ |
| 8 | Batch upload for customers/invoices (P2-2) | FUTURE-ENHANCEMENTS.md §3.3 | ⬜ |
| 9 | Filter-chain integration tests | FUTURE-ENHANCEMENTS.md §5 | ⬜ |
| 10 | Flag infra-only items needing manual/AWS action | FUTURE-ENHANCEMENTS.md | ⬜ |
| 11 | Admin User Directory sorting (JDBC) | FUTURE-ENHANCEMENTS.md §3.3 | ✅ |
| 12 | Live QA pass — UI regressions found & fixed | — | ✅ |
| — | Role CRUD | FUTURE-ENHANCEMENTS.md §3.2 | ⬜ Deferred — needs a decision on which tier may CRUD the role catalog before work starts |

Each row gets a full writeup (what/why/how, matching the style already used in
[FUTURE-ENHANCEMENTS.md §3](FUTURE-ENHANCEMENTS.md)) here once it's done, mirroring how §2/§3 of
that document records completed work — the difference is purely which document it lives in.

## Log

### 1. List sorting & filtering (2026-08-14)

Customers and Invoices lists are now sortable by clicking a column header — Name/Email/Status/Type
on Customers, Invoice Number/Status/Date/Total on Invoices — toggling ascending/descending on
repeat clicks, matching the conventional spreadsheet interaction.

**What/why.** `customer.service.ts:57` flagged this: pagination existed but sort/filter did not,
and the org-scoping work had already established "filter in SQL, never post-filter a page" as the
rule for exactly this class of problem (post-filtering after retrieval corrupts `totalElements` and
returns short pages). Sorting needed the same discipline.

**How.** Customer/Invoice use Spring Data JPA (unlike the JDBC-based User domain), and both
derived-query and non-native JPQL `@Query` methods already honor a `Pageable`'s embedded `Sort`
automatically — so this needed zero new SQL, only threading a `Sort` parameter through the existing
`PageRequest.of(page, size)` call sites. New `utils/SortUtils.resolveSort(Optional<String>,
Set<String>)` parses a `field,direction` query param against a per-entity allow-list (an
unrecognized JPA property path would otherwise throw deep inside Hibernate's query translator and
leak internals in the stack trace); an absent, blank, or disallowed value falls back to
`Sort.unsorted()` rather than a 400, since sorting degrading to "unsorted" is harmless while a
broken page is not. `CustomerController` and `AnalyticsController` (which duplicates the same
paginated calls for the admin-gated analytics surface) both gained a `sort` request param resolved
against their own allow-lists. Frontend: `customers.component.ts` / `invoices.component.ts` each
gained `sortField`/`sortDirection` signals folded into the same derived `query` signal that already
carries page/size/search term, so one fetch fires per change no matter how many inputs moved —
consistent with the pattern the org-scoping work established for this exact pipeline.

### 2. Backend-driven i18n (2026-08-14)

A 403 from a protected endpoint now speaks the caller's language — `Accept-Language: es` gets
"No tienes permiso para asignar roles — contacta a tu administrador." instead of the English
sentence regardless of what the SPA's own language switcher was set to.

**What/why.** FUTURE-ENHANCEMENTS.md §3.3 flagged that server-generated messages stayed English
while the UI switched language, and named `CapabilityCatalog`'s permission-denied phrases as the
natural first target since they already funneled through one shared template. This pass is
deliberately scoped to exactly that surface — validation messages and email bodies are still
English-only; widening that is future work, not a gap in this one.

**How.** `CapabilityCatalog` used to hand back a finished English sentence built with
`String#format`. It's a plain static utility with no Spring bean lifecycle, so it can't hold a
`MessageSource` itself — it now only resolves a request to a message *key*
(`capability.assignRoles`, `capability.deleteCustomers`, …), keeping the "which capability does
this request need" table (which must stay truthful against `SecurityConfig`'s matchers) separate
from "what does that capability read like in French." Resolution moved to
`CustomAccessDeniedHandler`, which *is* a `@Component` and now injects `MessageSource`; it
resolves via `HttpServletRequest#getLocale()` rather than `LocaleContextHolder`, because this
handler runs inside the Spring Security filter chain, before `DispatcherServlet`'s
`LocaleResolver` would ever have populated that holder for the request. Six
`messages*.properties` bundles (en default + es/fr/de/pt/zh, matching the SPA's
`LanguageService.available` list exactly) back the ~20 keys; the shared sentence template uses
`MessageFormat`'s `{0}` placeholder rather than `%s`, since `MessageSource#getMessage` runs every
value through `MessageFormat` — which is also why the English and French templates escape their
own apostrophes as `''`, while the individual capability phrases don't need that (they're
resolved with a null `args` array, which bypasses `MessageFormat` entirely).

On the frontend, dropping Spring Boot's default `MessageSource` auto-configuration into place is
only half the wiring — the server still has to be *told* which language to use. A new
`languageInterceptor` reads the active choice straight out of `localStorage` (the same
`LANGUAGE_STORAGE_KEY` `LanguageService` persists to, now exported so the interceptor doesn't
duplicate that string as a second literal) and sets `Accept-Language` on every outgoing request —
mirroring `token.interceptor.ts`'s reasoning for reading its own storage key directly rather than
injecting a service. Registered first in `app.config.ts`'s interceptor chain, ahead of
`cacheInterceptor`/`tokenInterceptor`, though its position there is arbitrary: it only ever adds a
header and forwards, never short-circuiting the request, so it can't affect caching or auth either
way.

Test coverage: `CapabilityCatalogTest` now asserts the request → key mapping only; a new
`CustomAccessDeniedHandlerTest` resolves real `messages*.properties` bundles through a genuine
`ResourceBundleMessageSource` (not a mock — the thing worth protecting is that the properties
files actually parse) and re-covers the non-enumeration property across all six languages. A new
`language.interceptor.spec.ts` covers the header-attachment, no-op, and immutability cases.

### 3. Backend HTTP caching (2026-08-14)

GET responses on data-bearing endpoints now carry `Cache-Control: private, no-cache` and an ETag;
the browser's own native HTTP cache handles the rest, and the client-side `cacheInterceptor` /
`HttpCacheService` this replaces (both had carried a `// TODO: Move caching to the backend ... and
delete this interceptor/service` comment since before this item was picked up) are deleted.

**What/why.** FUTURE-ENHANCEMENTS.md §3.4 named the exact defect: the old cache was an in-memory
`Record<string, HttpResponse>` in one browser tab, keyed by URL with no freshness check — if User A
edited a customer, User B's tab had no way to learn its cached copy was stale until B happened to
make a mutating request of their own (which evicted the *entire* cache as a blunt correctness
backstop) or reloaded the page. That is a real staleness bug, not a performance nit — it is
specifically the class of bug the org-scoping work earlier in this session was written to prevent
in the other direction (one org seeing another org's data), just triggered by a stale cache instead
of a missing `WHERE` clause.

**How.** `Cache-Control: no-cache` does not mean "do not cache" — combined with an ETag it means
"never reuse a cached response without asking the server first." Every GET now does a real network
round trip: unchanged data comes back as an empty `304 Not Modified` (cheap), changed data comes
back with a full fresh body and a new ETag. This needs no shared store (Redis, as the backlog entry
floated as one option) to be correct, because each ETag is a hash of that request's own response
body, not server-side state. `filter/HttpCacheHeadersFilter` (new, mirrors `RateLimitFilter`'s
plain-`@Component`-filter convention) sets the header and originally composed Spring's own
`ShallowEtagHeaderFilter` for the hash-and-304 logic — see the correction below for why that
composition was later replaced. `Cache-Control` is deliberately `private`: every response here is
JWT-authenticated and varies by caller, and the app deploys behind a CloudFront distribution
(`aws/RUNBOOK.md`) — without `private`, a shared cache sitting in front of the app could legally
serve one organization's customer list to a different organization's admin hitting the same URL.
The bypass list (auth/verification/download paths) is a direct port of the old interceptor's
`bypassRoutes`, so nothing deliberately excluded from caching before becomes accidentally cacheable
now — verification and password-reset codes are single-use, and an ETag/304 could otherwise let a
client treat a code the server already invalidated as still outstanding.

**Correction (2026-08-14, found via the item #4 cURL smoke-test suite the same day it shipped).**
The very first live run of `curl-smoke-test.sh` against a running instance caught a real defect this
feature's unit tests had not: `GET /customer/list` twice in a row, second call carrying
`If-None-Match` from the first, still came back `200` instead of the expected `304`. Cause — every
`HttpResponse` envelope carries a top-level `timeStamp` field every controller stamps fresh
(`now().toString()`, nanosecond-precision `LocalTime`) on every single response. `ShallowEtagHeaderFilter`
hashes the raw response body, so two calls returning byte-identical customer data still hashed
differently purely because of *when* the server happened to answer — the 304 short-circuit this
whole feature exists to provide could never fire in practice, on any endpoint, ever. `timeStamp`
carries no business data and the frontend never reads it (confirmed by grep — zero references in
`tesseraapp/src`), so excluding it from the hash costs nothing.

Fix: `HttpCacheHeadersFilter` no longer delegates to `ShallowEtagHeaderFilter`. It wraps the
response in a `ContentCachingResponseWrapper`, and for any JSON response, parses the buffered body
with Jackson, removes the top-level `timeStamp` field, and MD5-hashes *that* — replicating
`ShallowEtagHeaderFilter`'s own `"0" + md5hex` ETag format so nothing downstream (browsers, the
Postman/Bruno/cURL suite) sees a different shape. The client still receives the real, untouched body
— real timestamp included — on every non-304 response; only the hash input is sanitized, never what
reaches the wire. A response that isn't JSON (the one other GET this filter reaches,
`UserController#getProfileImage`, PNG bytes) or JSON without a `timeStamp` field (e.g. an actuator
endpoint) falls back to hashing the raw bytes, identical to the original behavior, so nothing outside
the one affected field-shape changed. Only 2xx responses get an ETag at all — an error response is
never eligible for a false 304.

Deleting the client-side cache reopened a narrower problem it used to paper over on the way out:
`UserService#logOut()` used to call `HttpCacheService#evictAll()` explicitly, because the old cache
had no freshness check of its own and `/user/login`'s bypass meant the usual mutation-triggered
eviction never fired on sign-in either — without that explicit call, a second user signing into the
same tab could be served the first user's still-cached `/user/profile` response. Always-revalidate
solves that for data that changed, but not for the edge case where two different users' responses
for the same URL hash to the *same* ETag (an empty list, for instance) — a stale 304 could then slip
through. `SessionController#logout` now answers with `Clear-Site-Data: "cache"`, which tells the
browser to drop its whole HTTP cache for the origin on sign-out, closing that window outright
instead of relying on hash collisions never happening.

Test coverage: `HttpCacheHeadersFilterTest` exercises the header/ETag pair, the 304 short circuit, a
stale-ETag-must-not-suppress-fresh-data case, and the full bypass list, mirroring
`RateLimitFilterTest`'s `MockHttpServletRequest`/`FilterChain` style rather than needing a full
Spring context. The deleted `cache.interceptor.spec.ts` (149 lines) is not replaced 1:1 on the
frontend — there is no longer any interceptor logic on that side to specify. The 2026-08-14
correction above added three more cases that are the actual regression test for the found defect:
two bodies differing only in `timeStamp` must hash identically and 304 on the second call; a
differing `timeStamp` alongside a genuinely changed `data` field must never mask that real change
behind a false 304; and a non-JSON body (the profile-image case) must still get a stable ETag from
its raw bytes, unaffected by the JSON-aware path. Live-verified post-fix by re-running
`curl-smoke-test.sh` end to end (29 passed, 0 failed — the exact `/customer/list` repeat-request
case that originally failed now reports the expected `304`) and by hand: two `GET /customer/list`
calls one second apart, second carrying the first's ETag as `If-None-Match`, came back a real `304`
with an empty body, while an unconditional third call still showed a live-advancing `timeStamp` in
its body — confirming the fix suppresses the redundant *network round trip's body*, not the
timestamp's own freshness, which was never the point.

### 4. API testing suite — Postman, Bruno, cURL (2026-08-14)

`documentation/api-testing/` now holds three parity-checked ways to exercise the API by hand or in
CI: `curl-smoke-test.sh` (dependency-free, colored pass/fail, CI-usable exit code),
`tesseraapp.postman_collection.json` + `.postman_environment.json`, and a `bruno/` collection
(plain-text `.bru` files, one per request, git-diffable). All three cover the same ~40 requests
across all 12 controllers.

**What/why.** Not pulled from the existing backlog — the user asked directly whether a Postman
collection already existed for testing endpoints. It didn't, in any current form: the only prior
artifact, `documentation/APIs.postman_collection`, was a `2026-05-10` export covering roughly five
endpoints (register/login plus an early customer/invoice slice) and predated MFA, sessions,
passkeys, admin user management, the security dashboard, analytics, services catalog, contact, and
this same week's org-scoping and HTTP-caching work — none of it reachable from that file. It's been
removed; nothing in it was worth carrying forward, and no other doc referenced its path.

**How.** All three artifacts share one source of truth for which endpoints exist and how they're
shaped: `GUIDE.md` §8, cross-checked against the twelve `@RestController` classes directly for the
routes that predate or postdate that section's own accuracy (`ContactController`,
`PublicServicesController`, `AnalyticsController` — none tabulated in §8 itself). Auth flows off
the seeded `DemoDataSeeder` accounts (`eve.admin@tessera.dev` / `TesseraDemo@1`, pre-verified, MFA
off) rather than requiring the caller to hand-register and hand-verify a throwaway account first —
the cURL script's login step captures `access_token`/`refresh_token` into shell variables via a
small `sed`-based `jget` helper (no `jq` dependency), the Postman collection does the same into
collection variables via a `pm.collectionVariables.set` test script on its Login request, and Bruno
mirrors that with `bru.setVar` — so every downstream authenticated request in all three just reads
the same variable, and running Login again transparently refreshes it everywhere else.

WebAuthn/passkey enrollment and login are deliberately **not** included in any of the three: those
endpoints consume a `PublicKeyCredential.toJSON()` blob a browser's platform authenticator
produces, which no scripted HTTP client can fabricate — attempting to fake one would either be
inert (server-side signature verification would just reject it) or worth flagging as a spoofing
concern rather than a testing convenience, so the honest answer is those flows can only be
exercised through the real UI. `POST /user/totp/enable` is present in all three as a template with
a `REPLACE_WITH_6_DIGIT_CODE` placeholder for the same reason (needs a live code from a real
authenticator app) — included so the shape is documented, not wired to run unattended.

Mutating requests got the most deliberate design attention. Customers and invoices have no `DELETE`
endpoint by design (§8.9) and services are retired, not deleted (§8.8) — so any create call leaves
a footprint that can't be fully cleaned up by a second API call. The cURL script defaults to
**read-only** (plus one harmless throwaway `POST /user/register`, which just leaves an unverified
account) and requires an explicit `--with-mutations` flag to create a customer/invoice/service, and
a *further*, separate `--with-contact-email` flag before it will fire `POST /contact` — which sends
a real message through `NotificationService`, a side effect visible outside the app's own database.
Postman and Bruno don't have an equivalent switch (they're click-to-run GUI tools, so every request
firing is already a deliberate act), but both carry the same warnings in each mutating request's
description/docs block and in this folder's `README.md`.

Also folds in a live regression check for item #3 above: the caching-demo folder/section in all
three captures an `ETag` from `GET /customer/list`, replays the same request with
`If-None-Match` and asserts a `304`, and checks that `POST /user/sessions/logout` responds with
`Clear-Site-Data: "cache"` — turning that feature's unit-test coverage into something also
verifiable against a live running instance, which was still an open item as of the caching work
landing. That live check is exactly what caught the timeStamp/ETag defect corrected in item #3's
log entry above, on the very first run against a live instance.

### 5. Structured logging + metrics (2026-08-15)

Two independent halves, built together: a live Micrometer counter for every audit event, and a
switch to JSON console logs on the three deployed profiles (`prod`/`qa`/`stage`) so CloudWatch Logs
Insights can query structured fields instead of pattern-matching text. `dev`/`local` console output
is unchanged.

**What/why — metrics.** `NewUserEventListener#onNewUserEvent` (`src/main/java/.../listener/`) is the
single seam every audit event in the app already flows through exactly once — login success/failure,
suspicious-login step-up, MFA/TOTP/passkey enrollment, token-reuse detection, federated login, every
`EventType` there is. Rather than instrumenting each of the ~20 call sites that publish a
`NewUserEvent`, one `MeterRegistry` counter (`user.events.total`, tagged `type=<EventType>`) was
added at that single listener, so a brand-new `EventType` added later is automatically counted with
zero extra wiring. It increments *before*, and independently of, the existing audit-write try/catch —
an in-memory counter can't fail the way a DB insert can, and this way the metric still reflects that
the event genuinely happened even on the (already-documented, pre-existing) run where the audit
write itself throws and gets swallowed. Exposed live at `/actuator/metrics/user.events.total`,
complementing the security dashboard's DB-query-driven historical view with a real-time one.

**What/why — JSON logging.** No `logback-spring.xml` existed before this; logging was entirely
Spring Boot's default plain-text console pattern. `src/main/resources/logback-spring.xml` now
branches on Spring profile: `prod,qa,stage` render one JSON object per line via
`net.logstash.logback:logstash-logback-encoder` (added to `pom.xml`, version `7.4`), `dev,local`
keep Boot's own human-readable console pattern verbatim. The file deliberately hardcodes no
`<logger>` level for any package this app already controls via `logging.level.*` in
`application.yml` — `LOG_LEVEL_APP`/`LOG_LEVEL_SECURITY`/`LOG_LEVEL_WEB`/`LOG_LEVEL_HIBERNATE`
(documented in `aws/RUNBOOK.md` Part H) keep working exactly as before, since Boot applies those
from the environment *after* this file loads and always wins for any logger it names.

**Bug caught before it shipped: `CONSOLE_LOG_PATTERN_IS_UNDEFINED`.** The first version of
`logback-spring.xml` put `<include resource=".../console-appender.xml"/>` directly inside the
`dev,local` `<springProfile>` block, reasoning that `console-appender.xml` already pulls in Boot's
`defaults.xml` (which defines `CONSOLE_LOG_PATTERN`) itself. In practice that left the property
unresolved at the point Joran evaluated the appender's pattern — reproduced against both a real
`spring-boot:run` boot and the full `mvn test` suite, where every console line printed the literal
text `CONSOLE_LOG_PATTERN_IS_UNDEFINED` instead of the real pattern. Fix: load
`org/springframework/boot/logging/logback/defaults.xml` unconditionally, before either
`<springProfile>` branch — the structure Spring Boot's own docs recommend for exactly this failure
mode. Re-verified clean against a real boot afterward (see Testing below).

**Security: `/actuator/metrics` had to be gated, not just exposed.** Widening
`management.endpoints.web.exposure.include` to add `metrics` (`application.yml`) would otherwise
have made per-`EventType` counts — login failure rates, suspicious-login rates, token-reuse
detections — readable by anyone, since `SecurityConfig` previously `permitAll()`'d the entire
`/actuator/**` path (needed only because the ALB target group and ECS container health check hit
`/actuator/health` with no `Authorization` header). Fixed by splitting that matcher:
`/actuator/health` and `/actuator/info` stay `permitAll()`; everything else under `/actuator/**`
now requires `UPDATE:USER`/`UPDATE:ROLE`, the same authority `/admin/**` requires. That alone would
have been a silent no-op, though: `Constants.PUBLIC_ROUTES` — the separate list `CustomAuthFilter`
uses to decide whether to even *attempt* parsing a Bearer token — had a bare `"/actuator"` entry,
matched via `startsWith`, which skipped JWT parsing for `/actuator/metrics` too. With that entry in
place, an admin's valid token would never have been parsed on that path, no principal would ever be
installed, and the new `hasAnyAuthority(...)` check would have denied *everyone*, admin or not —
turning the gate into a permanent 403 instead of an admin-only allow. Narrowed to
`"/actuator/health", "/actuator/info"` so a Bearer token on `/actuator/metrics` is actually parsed
and its authorities checked, restoring the lockstep this codebase's own comments already call out as
required between `SecurityConfig`'s matchers and `CustomAuthFilter`'s skip list.

**Testing.** `NewUserEventListenerTest` now constructs the listener with a real
`SimpleMeterRegistry` (Micrometer's own recommended test double — a plain in-memory POJO, not a
mock) instead of `@InjectMocks`, and asserts real counter values: one test confirms the counter
still increments on the pre-existing swallowed-audit-write-failure path, another confirms two
different `EventType`s accumulate under independent tags without bleeding into each other. Full
suite: 298 tests, 0 failures. Live-verified against a real local boot: `/actuator/health` reachable
with no token (200), `/actuator/metrics` denied with no token (401) *and* with a valid non-admin
(`ROLE_GUEST`) token (403), allowed with a valid admin token (200), and
`/actuator/metrics/user.events.total` returned real `LOGIN_ATTEMPT`/`LOGIN_ATTEMPT_SUCCESS` counts
after a real login. JSON shape sanity-checked by forcing `SPRING_ACTIVE_PROFILES=prod` for one boot
attempt and confirming valid `{"@timestamp":...,"level":"INFO","logger_name":"...",...}` lines
before tearing it down — the deployed CloudWatch pipeline itself can only be confirmed after an
actual deploy, which is outside what local verification can reach.

### 11. Admin User Directory sorting — JDBC (2026-08-14)

The admin User Directory (`/users`) is now sortable by clicking the Name or Email column header,
same interaction as Customers/Invoices — completing item #1 above, which explicitly scoped itself
to the JPA-based Customer/Invoice domain and left the JDBC-based User domain out.

**What/why.** Found during a live pass through the running app (not sourced from the existing
backlog): the User directory's columns didn't sort at all, unlike Customers/Invoices right next to
it in the same admin section — an inconsistency a user hits immediately by comparing the two
screens.

**How.** `SortUtils.resolveSort` (item #1) returns a Spring Data `Sort`, which has nowhere to plug
into a `NamedParameterJdbcTemplate` query — a bind parameter carries a value, never a column
identifier, so a `Sort` object can't parameterize an `ORDER BY` clause at all. New
`SortUtils.resolveSqlOrderBy(Optional<String>, Map<String,String>, String)` is the raw-JDBC sibling:
it validates the client field against a `Map` of client-facing name → actual SQL column (not just a
`Set`, since the JDBC path needs the real column name, not just permission to use the client's own
spelling of it), and returns an already-safe `"column ASC|DESC"` fragment. `UserQuery.SELECT_USERS_PAGED_QUERY`
and `OrganizationQuery.SELECT_USERS_SHARING_ORGANIZATIONS_PAGED_QUERY` (the org-scoped admin's
parallel query, joined against `userorganizations`) each gained a `%s` placeholder for that
fragment, spliced in via `String.format` — safe specifically because the fragment reaching
`String.format` is always one `AdminUserController`'s own allow-list map produced, never anything
from the request. The org-scoped query's allow-list is a separate, `u.`-qualified map
(`u.first_name`, not `first_name`) since that query joins `userorganizations` under aliases and an
unqualified column could otherwise collide with one on the joined table. `AdminUserController#listUsers`
resolves the param once and threads the same fragment through both the unscoped
(`UserService#searchUsers`) and organization-scoped (`OrganizationService#searchUsersSharingOrganizations`)
code paths, so sorting behaves identically for both admin tiers. Frontend: `users.component.ts`
gained the same `sortField`/`sortDirection` signal pair and `toggleSort()`/`sortIconClass()` methods
as `customers.component.ts`, and `AdminUserService#users$` gained an optional `sort` query param.

Live-verified against the running app: `GET /admin/user/list?sort=email,asc` and `...=email,desc`
each return the directory in the correct order (confirmed by inspecting the actual emails returned,
not just the response status), `sort=firstName,asc` likewise, and an unrecognized field
(`sort=bogusfield,asc`) degrades to the default order with a `200`, never a `500` — the allow-list
fallback working as designed. Full backend suite passes.

### 12. Live QA pass — UI regressions found and fixed (2026-08-14)

A pass through the actually-running app (not code review) surfaced four issues invisible to the
existing test suite because they're rendering/layout defects, not logic bugs. All four fixed same
day.

**Login page footer links wrapping mid-word.** `styles.css`'s `.sc-auth__links` used
`justify-content: space-between` with no `flex-wrap` or `white-space: nowrap`. It held two links
without visible trouble; the 2026-08-13 Feature Tour work added a third ("See what TesseraApp can
do") to the same row, and three links competing for the same width wrapped individual link text
mid-word ("Forgot" / "Password") instead of the row itself wrapping. Fixed with `flex-wrap: wrap`
plus `gap` on both axes and `white-space: nowrap` on the links themselves, so a link now wraps as a
whole unit onto its own line rather than breaking its own text apart.

**Stray avatar image escaping the table layout.** The Customers and Home dashboard tables rendered
`<img [ngSrc]="customer.imageUrl" fill>` with no positioned wrapper around it. Angular's
`NgOptimizedImage` `fill` attribute forces `position: absolute; width: 100%; height: 100%` on the
`<img>` and requires a `position: relative` ancestor with explicit dimensions — without one, the
image detaches from its table cell and pins to the nearest positioned ancestor (or the page itself),
which is what put a customer's photo in the corner of the table instead of their row. The Users
directory table already had the correct pattern (`<div style="position: relative; width: 42px;
height: 42px; ...">` wrapping the `<img>`) — copied that same wrapper into `customers.component.html`
and `home.component.html`.

**Footer jumping to the navbar during page navigation.** `route-animations.ts`'s route transition
pins both the outgoing and incoming views to `position: absolute` for its ~330ms duration, pulling
them out of document flow. `<main>` (the sticky-footer layout's flex-column "spacer") had no
independent sizing of its own, so for that whole window it measured `0px` tall, and the footer's
`margin-top: auto` snapped it straight up next to the navbar until the new route settled back into
flow. Fixed with one rule in `app.component.css` — `main { flex: 1; }` — so `<main>` claims its share
of the flex column itself, independent of what its (possibly absolutely-positioned) children
currently measure.

**Manage Services — investigated, no bug found.** Reported as "no option to add/remove services from
the catalog," but `services-admin.component.ts`/`.html` already implement create (`POST
/admin/services/create`), inline edit (`PUT /admin/services/update/{id}`), and retire/reinstate
(`PATCH /admin/services/{id}/active/{active}`) in full, and the navbar's "Manage Services"/"New
Service" links route correctly to `/services/manage`. Live-checked the demo admin account
(`eve.admin@tessera.dev`) and confirmed its `permissions` string includes both `UPDATE:USER` and
`UPDATE:ROLE`, which is what gates the nav link — the feature is reachable and functional for that
account. No code change made; most likely explanation is landing on the read-only public `/services`
catalog rather than `/services/manage`, though a permissions gap specific to a different, non-seed
account was not ruled out.
