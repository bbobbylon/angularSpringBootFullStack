# TesseraApp — Roadmap & Backlog

**Version:** 2.0
**Last Updated:** 2026-07-26
**Status:** Living — **the single source of truth** for anything planned, deferred, or TODO.

## How to use this file

This is **the** place for future work. There is exactly one live planning document (this one) and
exactly one archive ([`documentation/history/PROJECT-HISTORY.md`](documentation/history/PROJECT-HISTORY.md)).
Every earlier planning file — `plan.md`, `phase2-proposals.md`, `rollout-plan.md`,
`assignments/week-5-plan.md`, `branch-changelog.md`, `BRANCH_COMPARISON.md`,
`documentation/project-status-and-roadmap.md` — has been folded into one of those two and deleted;
the archive's §4 registry says where each one's content went and how to recover it from git.

When you add a `TODO` in code, either fix it or add a one-line entry here and reference it. Don't
let planning re-scatter — that is the exact problem this consolidation just undid.

**Companion references (documentation, not planning scratch):**
- [`documentation/README.md`](documentation/README.md) — the documentation hub.
- [`documentation/roles-and-scenarios.md`](documentation/roles-and-scenarios.md) — who can do what, end to end, for every role.
- [`documentation/history/PROJECT-HISTORY.md`](documentation/history/PROJECT-HISTORY.md) — the archive: milestones, delivery timeline, retired-document registry.
- [`assignments/software_requirements_specification.md`](assignments/software_requirements_specification.md) — the SRS.

**Status legend:** ⬜ not started · 🔄 in progress · ✅ done.

---

## 1. Where the project actually stands

Every functional requirement in the SRS is implemented. The remaining work is **quality**, not
features: performance, test depth, and the handful of refactors listed in §4.

| Dimension | State |
|---|---|
| Functional scope | ✅ Complete — auth, MFA, federation (login **and** link/unlink), sessions, RBAC, org scoping, anomaly detection + step-up, security dashboard, business CRUD, i18n |
| Tests | ✅ 122 backend / 35 frontend, all green |
| Lint | ✅ `ng lint` clean |
| Dependency audit | ✅ `npm audit --audit-level=high` exit 0 |
| CI | ✅ Gating on lint + audit + tests against a MySQL service container |
| Docs | ✅ Consolidated — one hub, one roadmap, one archive |
| **Performance** | 🔄 **The current work** — Lighthouse ~57; see §2 |

---

## 2. Active queue (do in order)

### 2.1 🔄 Frontend performance — Lighthouse 57 → 90s

**Round 1 done (2026-07-26): every third-party origin removed from the critical path.**

Reading the *built* `index.html` against the *deployed* headers turned up a production bug, not
just a slow page. Angular was already doing the hard parts — Beasties inlines the critical CSS and
turns the 256 kB stylesheet into a non-blocking `media="print" onload="this.media='all'"` link — so
the head contained exactly two third-party requests, and both were **blocked by our own CSP in
production**:

| Resource | Origin | Blocked by | Symptom in production |
|---|---|---|---|
| `bootstrap-icons.min.css` | `cdn.jsdelivr.net` | `style-src 'self'` | every `bi-*` icon renders as nothing |
| IBM Plex woff2 files | `fonts.gstatic.com` | `font-src 'self'` | falls back to a system font |

Spring Boot serves the SPA out of `src/main/resources/static/` (see the Dockerfile's
`COPY --from=frontend-build`), so the SPA document carries `SecurityConfig`'s CSP. Local `ng serve`
sends no such header, which is why this only ever failed once deployed and never in development.

Both were fixed the same way, and it is the fix that keeps the policy tight rather than the one
that widens it: **self-host**. Adding the CDN origins to `style-src`/`font-src` would also have
worked, but that permanently widens the policy to a third party for a cosmetic asset.

- `bootstrap-icons` + `@fontsource/ibm-plex-sans` / `-mono` are now npm dependencies, pulled in
  through the `styles` array (`src/fonts.css` documents the weight and subset choices — `latin` and
  `latin-ext` only, because `latin-ext` is what carries the accents the es/fr/de/pt translations
  need, and Chinese falls back to a system CJK face regardless).
- `index.html` now references **no external origin at all**.
- **Bootstrap's JS bundle replaced with four targeted ESM imports** in `main.ts`. `bootstrap.bundle.min.js`
  arrived through the `scripts` array as a classic script, so esbuild could not see inside it and shipped
  every component — modals, carousels, tooltips, popovers, offcanvas, toasts, scrollspy — for an app whose
  templates use only `dropdown`, `collapse`, `tab` and `alert`.

Measured, production build, before → after:

| | Before | After |
|---|---|---|
| Third-party origins in `<head>` | 2 | **0** |
| Render-blocking stylesheets | 1 (cross-origin) | **0** |
| Initial JS (transfer) | 27.81 kB over 2 requests | **21.76 kB over 1** |
| Initial CSS (transfer) | 27.91 kB + a blocking cross-origin fetch | 38.77 kB, non-blocking, same-origin |
| Icons/fonts in production | broken by CSP | working |

Raw initial total went up (708 → 768 kB) because the icon font's CSS moved into our bundle, but raw
size is not what the score measures: the bytes that grew are on the **deferred** stylesheet, and what
shrank is the blocking path. Re-run Lighthouse the same way the 57 was measured to get the new number.

**Round 2 candidates, if the score still needs moving:**
- **Trim Bootstrap's CSS.** `bootstrap.min.css` is ~230 kB of the 344 kB stylesheet and the app uses a
  fraction of it. Importing only the needed layers via SCSS would cut most of it — but it carries real
  visual-regression risk across 28 templates, so measure first and do it deliberately.
- **Subset `bootstrap-icons.css`.** 90 distinct icons are used out of roughly 2,000, so ~95% of those
  rules are dead weight. Worth it only behind a build step that scans the templates — a hand-maintained
  subset breaks silently the first time someone adds an icon.
- **Preload the icon woff2.** It is only discovered after the deferred stylesheet applies, so icons pop
  in slightly late. Needs a build-time hook because `outputHashing` renames the file.
- **Lazy-load `jspdf`/`html2canvas`.** Already lazy (the 427 kB `invoice-detail` chunk), but that chunk
  is by far the largest in the app — worth confirming it is only pulled on the export click, not on
  route entry.

### 2.2 ⬜ Remaining frontend specs

The backend security paths are covered (§5 debt closed). The frontend gaps that matter:
- `tokenInterceptor` — header attachment, and the refresh-on-401 retry path (currently untested, and
  it is the one piece of client code that can silently sign everybody out).
- `authenticationGuard`.
- `UserService.hasAnyAuthority` token-decoding edge cases — expired token, malformed token, missing
  claim. This is the code path behind the "Jump back in" stale-flag bug, so a regression here is
  proven possible.

### 2.4 🔴 Two defects found by probing the live deployment (2026-07-29)

Both were found by curling the deployed ALB, not by any test. Neither is reproducible locally with
the dev profile, which is why 195 green tests missed them.

**(a) A rejected JWT returns `400`, not `401` — so the silent refresh can never fire.**

```
curl -H "Authorization: Bearer bad.token" -H "X-Requested-With: XMLHttpRequest" .../customer/list
→ 400 {"devMessage":"Could not decode the token…","reason":…}
```

**The observed status is 400. The cause is NOT yet established — do not fix from a guess.**

What is confirmed:
- Production returns `400` with `reason`/`devMessage` = *"Could not decode the token. The input is
  not a valid Base64-encoded JWT."* for a malformed bearer token on an authenticated GET.
- `ExceptionUtils.processError` maps `JWTVerificationException` (which `JWTDecodeException`
  extends) to **`UNAUTHORIZED`**, with the canned message *"Invalid token. Please log in again."*

Those two cannot both describe the same code path: the message returned is auth0's raw decode text,
not the canned one, so the response did **not** come from `processError`'s 401 branch. Something
upstream or downstream — most likely `HandleException`'s `BadCredentialsException` branch after a
rethrow, since that branch returns 400 and passes `exception.getMessage()` straight through — is
answering instead.

Why it matters: `token.interceptor.ts` retries **only on 401**. Any path that turns an invalid or
expired token into a 400 silently disables the silent refresh for that path.

Next step is to pinpoint, not to patch: log or breakpoint the actual handler for a malformed token
and for a genuinely expired one (they may differ), then decide where the mapping should live. Note
`SecurityConfig`'s `defaultAuthenticationEntryPointFor(AnyRequestMatcher)` does not help here — it
governs requests that reach the *entry point*, i.e. ones carrying no credential at all, whereas a
present-but-invalid token throws earlier in the filter.

**(b) Filter-written error bodies bypass `ErrorDetailScrubber`, leaking `devMessage` in prod.**

The response above carries `devMessage` and `path` even though `application-prod.yml` pins
`app.error.expose-details: false` and the active profile is confirmed `prod`. The scrubber is a
`ResponseBodyAdvice`, so it only sees bodies serialized through a controller's message converter.
`ExceptionUtils` writes straight to the servlet output stream inside the filter chain, before any
controller is selected — the advice is structurally unable to reach it. (`CustomAccessDeniedHandler`
already documents this for its own body; the consequence for the *filter* path was missed.)
Fix by scrubbing at the point of writing in `ExceptionUtils`, gated on the same property, rather
than by widening the advice.

### 2.3 ⬜ Exercise a real production boot

`ddl-auto=validate` against a MySQL initialised **only** by `schema.sql`. Only the offline
`JpaSchemaSyncTest` has run; that catches entity/DDL drift but cannot catch a schema the app has
never actually started against.

---

## 3. Enhancement backlog

Ranked roughly by value-to-effort. Nothing here is committed work — it is the menu.

### 3.1 Security & identity

| Enhancement | Why it is worth doing | Sketch |
|---|---|---|
| ⬜ **Distributed brute-force + link-ticket state** | Three controls are **per-instance** today: the brute-force counter, the rate limiter's buckets, and `ProviderLinkTicketService`. Behind a load balancer without sticky sessions, an attacker gets N× the attempt budget simply by being routed around. | Move counters to the database or Redis; the service interfaces do not change. |
| ⬜ **WebAuthn / passkeys** | The natural next factor after TOTP, and the one that is actually phishing-resistant — TOTP codes can be relayed to an attacker in real time, a passkey signature cannot be. Fits the existing challenge model exactly: `TotpService` already proves identity from a server-side challenge rather than the request. | `webauthn4j`; new `credentials` table; a third branch in the login step-up switch. |
| ⬜ **Admin-initiated MFA reset** | An account that loses both its authenticator and its recovery codes is currently unrecoverable without direct DB access. | Admin action + a `MFA_RESET` audit event; must be gated on staff authority and audited loudly. |
| ⬜ **Session revocation from the security dashboard** | Admins can *see* live sessions but not end them. Seeing a compromised session and being unable to kill it is the wrong half of the feature. | Reuse `SessionService`'s revoke-family path; org-scope the target. |
| ⬜ **Anomaly signal tuning UI** | `ANOMALY_HISTORY_LIMIT` and the enable flag are env-only, so tuning needs a redeploy. | Persisted settings + an admin panel; keep env as the fallback default. |
| ⬜ **P2-3 — Machine-to-machine API access** | Lets scripts and CI authenticate without a browser. Deferred deliberately: it adds a second authentication front door, which is the highest-risk change on this list. | **Option A — API keys:** an `X-API-Key` filter ahead of `CustomAuthFilter` resolving a **hashed** key to an `Authentication` carrying authority strings, so every existing `hasAnyAuthority`/`@PreAuthorize` rule applies unchanged. **Option B — OAuth2 client-credentials:** `POST /oauth/token`. Both converge on "a request arrives already carrying authorities"; the RBAC core is untouched. Needs `service_accounts` + hashed `api_keys` tables, new audit event types, and `PUBLIC_URLS` ↔ `PUBLIC_ROUTES` lockstep. **Large, higher risk — do last, with dedicated review.** |

### 3.2 Access model

| Enhancement | Why | Sketch |
|---|---|---|
| ⬜ **Role CRUD** | Roles are seed-only; `RoleRepoImpl.create/update/delete` throw `UnsupportedOperationException`. Fine while the seven roles are fixed, blocking the moment anyone wants an eighth. | Implement the stubs + an admin screen; the permissions matrix UI already exists to edit against. |
| ⬜ **Per-organization role definitions** | `RoleRepoImpl.java` `TODO(org-roles)`. FR-ORG scopes *administration*, not role *definitions* — every org shares one role catalogue. | Only worth it for genuine multi-tenancy; note it changes the authority-string model, so it is not a small change. |
| ⬜ **P2-1 — User type classification** | Show admins where an identity came from: `INTERNAL` / `EXTERNAL` / `FEDERATED` / `AZURE_B2B`. | Derive `INTERNAL/EXTERNAL` **on read** from an env-driven domain allowlist (`INTERNAL_DOMAINS`) — it must be reconfigurable, not baked in — and store an immutable `origin` fact for `FEDERATED`/`AZURE_B2B` stamped by `OAuth2LoginSuccessHandler`. Guarded `users.origin` ALTER, same pattern as `userevents.detail`. **Small–medium, low risk.** Open: reliable Azure B2B detection (`tid`/`idp` claims); whether filtering the directory by type argues for a stored column instead. |
| ⬜ **Self-service organization management** | Orgs are seeded/DB-managed; there is no UI to create one or move a user between them. | Admin CRUD over `organizations` + `userorganizations`; must respect the same org scope it edits. |

### 3.3 Product & data

| Enhancement | Why | Sketch |
|---|---|---|
| ⬜ **List sorting / filtering / server-side paging** | `customer.service.ts:57`. Customers and invoices load unsorted; the page is already paged but not sortable or filterable. Becomes a real problem at a few thousand rows. | Push sort/filter into the query params and the SQL — the org-scoping work already established the "filter in SQL, never post-filter a page" rule for exactly this reason. |
| ⬜ **Invoice total aggregation query** | `InvoiceRepo.java:15`. Billing sums invoices client-side, so the total is only ever as complete as the page you fetched. | A `@Query` returning `SUM(totalAmount)`, org-scoped. |
| ⬜ **P2-2 — Batch upload** | CSV/Excel import for customers and invoices — the most-requested "real business app" feature still missing. | Per-row validation with a partial-success report (`{ imported, failed: [{row, reason}] }`), per-chunk commits, a dedupe key, async job for large files. Gate on `UPDATE:CUSTOMER`. Apache Commons CSV (+ POI only for `.xlsx`). Open: CSV-only vs Excel, sync row cap, dedupe policy. |
| ⬜ **Favorites / pinned destinations bar** | Navigation has outgrown the navbar: the Admin dropdown alone holds six destinations, and Services splits into browse vs. manage, so common pages are two clicks deep behind a menu that has to be opened to be read. A pinned row surfaces each person's actual working set. | **Build it on `command-palette.service.ts`, not on a new route list** — see below. |
| ⬜ **Backend-driven i18n** | Server-generated messages (validation, email bodies, capability-denied text) stay English while the UI switches language. | Spring `MessageSource` + `Accept-Language`; the `CapabilityCatalog` phrases are the natural first target since they already have a message template. |

#### Favorites bar — design decisions taken up front (2026-07-29)

**Reuse the command-palette registry.** `command-palette.service.ts` already holds every navigable
destination with its label, icon and required authorities. Favorites must be *a set of ids into that
registry*, never a parallel list of routes — otherwise the two drift the first time someone adds a
page, and only one of them gets it. Corollary: the add/remove affordance is a star on each palette
result, which teaches the feature exactly when someone is hunting for a page.

Two decisions to settle before implementation:

1. **Storage — `localStorage` or a `userpreferences` table?**
   `localStorage` mirrors how the theme toggle already works and is roughly an hour of work, but the
   pins are per-browser and vanish on a new device. A DB-backed table is closer to half a day and
   makes the workspace follow the *identity* rather than the browser — a better fit for what this app
   is demonstrating, and it reuses the existing Query + RowMapper + Repo/RepoImpl pattern. **Leaning
   DB-backed**, with `localStorage` as the offline cache rather than the source of truth.
2. **RBAC filtering must happen on *render*, not only on *add*.**
   Authorities change: a role reassignment can strip `UPDATE:USER` from someone who pinned
   `/analytics` last week. A pin that survives into a menu and then 403s is worse than no pin. The
   existing `*appHasAuthority` directive handles this for free — which is the second reason to reuse
   the palette registry, since that is where the authority metadata already lives. Also decide
   whether a now-invisible pin is *hidden* or *removed*; hidden is kinder, because a temporary role
   change should not silently destroy someone's setup.

Open: where the bar physically sits (a second row under the navbar vs. inline beside the command
palette trigger), and whether there is a cap on pin count.
| ⬜ **Real SMS delivery** | `NotificationServiceImpl.java:54` — SMS 2FA sends only when Twilio credentials are set, and is otherwise a documented no-op. Honest, but it means one advertised factor is not exercisable. | Either wire Twilio properly or drop SMS from the factor list; a factor that silently does nothing is worse than one that does not exist. |
| ⬜ **Email verification host** | `VERIFY_EMAIL_HOST` is reserved and unused (`UI_APP_URL` drives links today). Keep or remove deliberately — do not let it rot as ambiguous config. | |

### 3.4 Operations

| Enhancement | Why | Sketch |
|---|---|---|
| ⬜ **Backend HTTP caching** | `cache.interceptor.ts:35` + `http-cache.service.ts:25` — caching is client-side only, so it cannot be invalidated by a write from another user. | `Cache-Control`/`ETag` on read endpoints; Redis if it needs to be shared. |
| ⬜ **Real production domain** | `AngularSpringBootFullStackApplication.java:73` — pending DNS. | Deployment config only. |
| ⬜ **`start.sh` → Maven wrapper** | Uses bare `mvn`; `./mvnw` pins the version so a teammate's Maven install cannot change the build. | One-line change. |
| ⬜ **Structured logging + metrics** | Actuator is present but nothing scrapes it; the audit log is the only operational visibility. | Micrometer + JSON logs; the security dashboard's counters are the obvious first metrics to export. |

---

## 4. Code TODO audit

Open items only — completed rows moved to the archive. Verified against the tree on 2026-07-26.

### Backend
| Location | Intent | Notes |
|---|---|---|
| `AngularSpringBootFullStackApplication.java:73` | Real prod domain once DNS configured | §3.4 |
| `InvoiceRepo.java:15` | sum-of-`totalAmount` `@Query` | §3.3 |
| `UserQuery.java:36` + `UserRepoImpl.java:183` | rename `url` column → `verification_key` | Cosmetic; idempotent guarded rename |
| `UserController.java:59` | `TODO(refactor-user-fetch)` — standardize authenticated-user fetch | Refactor |
| `UserRepoImpl.java:63` | `TODO(refactor-architecture)` — SRP violation | Refactor: it is a repo, a `UserDetailsService`, and a business-logic holder |
| `RoleRepoImpl.java:25` | `TODO(org-roles)` — org-scoped role system | §3.2 |
| `RoleRepoImpl` create/update/delete/getById | Role CRUD — stubs throw `UnsupportedOperationException` | §3.2 |
| `NotificationServiceImpl.java:54` | Enable SMS (Twilio) when ready | §3.3 — known, documented stub |

### Frontend
| Location | Intent | Notes |
|---|---|---|
| `cache.interceptor.ts:35` + `http-cache.service.ts:25` | Move caching to the backend | §3.4 |
| `stats.component.ts:13` + `customer.service.ts:39` | `StatsComponent` self-fetch | Refactor |
| `navbar.component.ts:15` | Decouple user data from the `/customer/list` response | Refactor — fetch `/user/profile` independently |
| `new-customer.component.ts:25/76/83` | Lighter user-only prefill endpoint | Refactor — stop fetching all customers to prefill one field |
| `profile.component.ts:17` | Reactive forms for profile | Refactor |
| `customer.service.ts:57` | Sorting / filtering / infinite scroll | §3.3 |

---

## 5. Engineering debt

- ⬜ **Frontend specs** for `tokenInterceptor`, `authenticationGuard`, and `UserService.hasAnyAuthority` — see §2.2.
- ⬜ **Untried production boot** with `ddl-auto=validate` — see §2.3.
- ⬜ **Per-instance security state** (brute force, rate limiting, link tickets) — see §3.1. Not a bug today (single instance); a real hole the moment a second instance exists.
- ⬜ **Refactors** listed in §4 — none are urgent, all are the kind of thing that gets harder the longer it waits.
- ✅ **Security-critical-path tests** — closed 2026-07-26 (refresh rotation, TOTP challenge binding, org-scoped access).
- ✅ **`ng lint`** — clean and gating in CI.
- ✅ **`npm audit`** — clean of high/critical and gating in CI.
- ✅ **Prod error hygiene** — `ErrorDetailScrubber`.
- ✅ **Aiven schema drift** — `schema.sql` made portable and drift-proof; applied to both `db2` and `db3`.
- ✅ **Redundant JWT library** — `jjwt` already removed; `com.auth0:java-jwt` is the only one.

---

## 6. Recently completed (don't re-plan)

Newest first. Anything older lives in
[`documentation/history/PROJECT-HISTORY.md`](documentation/history/PROJECT-HISTORY.md).

- ✅ **Single-origin parity pass** (2026-07-29) — four defects that exist *only* when the SPA and API
  share an origin, i.e. only once deployed. All were invisible in split-origin local dev.
  - **SPA/API route collision.** The email-verification landing page was routed at
    `/user/verify/{type}/:key` in Angular — byte-for-byte the backend's own `@GetMapping`. On one
    origin the real controller wins and the recipient of an activation email is shown the raw JSON
    envelope. Moved to `/verify/{type}/:key`, restoring the app's namespace split (bare/plural =
    SPA, `/user` `/customer` `/admin` = API) and documenting it as an invariant in `SecurityConfig`.
    Closes the `verify.component.ts:67` TODO on the way past: the flow is now carried in route
    `data`, not sniffed out of `window.location.href`.
  - **Plain-text emails → branded HTML.** New `EmailTemplate` renders the app's dark/iris design as
    table-based, inline-styled, `multipart/alternative` mail (the plain-text part is kept and
    written deliberately — it is both a fallback and a deliverability signal). No template engine
    added.
  - **JSON 401/403 shown to humans.** `BrowserErrorPage` content-negotiates on *fetch metadata*
    (`Sec-Fetch-Mode: navigate`), not `Accept` — Angular's `HttpClient` sets no `Accept` of its own,
    so negotiating on it would have served HTML to the token interceptor and silently killed
    auto-refresh. Navigations get a styled page; XHR keeps the exact JSON body it had.
  - **OAuth2 redirect_uri behind a load balancer.** `server.forward-headers-strategy` was unset, so
    `{baseUrl}` in the redirect-uri template resolved to the container's own `http://<task-ip>:8080`
    instead of the public origin. Now env-driven (`FORWARD_HEADERS_STRATEGY`, default `none`), set
    to `framework` in the ECS task definition. Microsoft was also missing from
    `aws/task-definition.json` entirely — hence a provider that appears locally and not when
    deployed — and is now wired through Secrets Manager alongside Google and GitHub.
- ✅ **Documentation consolidation** (2026-07-26) — one live plan (this file), one archive, and a new [`roles-and-scenarios.md`](documentation/roles-and-scenarios.md) giving the end-to-end capability matrix and walk-throughs for all seven roles. Three superseded planning documents deleted after their content was preserved.
- ✅ **Federated account linking, enterprise pattern** (2026-07-26) — "Connect a provider" from the Security Center. The design problem: linking acts on behalf of a signed-in user, but a JWT cannot ride a top-level navigation. Solved with a **single-use, five-minute, provider-bound ticket** that grants nothing on its own — redeeming it does not authenticate anybody, it only says which local account a *separately verified* provider identity attaches to. The security property is `FederatedIdentityService.linkProviderToUser` **refusing an identity that already belongs to another account**: without it, "Connect a provider" is an account-takeover primitive, and the usual "the email was verified" reasoning does not help because links are keyed on the provider's stable subject, not on email. Unlinking is refused when it would remove the last sign-in method. **10 tests.**
- ✅ **Six-language i18n** (2026-07-26) — Transloco runtime switching across 26 of 28 templates plus toasts and the command palette; `en`/`es`/`fr`/`de`/`pt`/`zh`, each labelled in its own language.
- ✅ **Capability-level RBAC in the UI** (2026-07-26) — `*appHasAuthority` / `[appRequiresAuthority]` directives and a fail-closed `capabilityGuard`. Found and fixed a real hole: `customer-details` gated ten bindings on `roleName === 'ROLE_USER'`, a string comparison that showed `ROLE_GUEST` a fully editable form that could only ever 403.
- ✅ **FR-TPF-2 security dashboard** (2026-07-26) — the review surface FR-TPF-1 was missing; a detection control whose output nobody can look at cannot be tuned or shown to work. One response for the whole screen (six endpoints would be six different instants of the same database), clamped window, zero-filled counters, gap-filled trend, non-fatal per-panel reads, org-scoped.
- ✅ **Business CRUD** (2026-07-26) — invoice editing, the services catalog admin screen (retire, never delete), and the invoice↔customer link endpoint.
- ✅ **Client-side token leak closed** (2026-07-26) — `login$` piped `tap(console.log)` over a response containing `access_token` and `refresh_token`, and the token interceptor logged the raw JWT. Angular does not strip `console.log` from production builds, so every sign-in wrote a usable bearer token into the browser console. Removed across 16 files; kept commented out for local debugging, with `DEBUG ONLY — DO NOT SHIP ENABLED` markers on the token-bearing ones.
- ✅ **Stale authority flags fixed** (2026-07-26) — eleven `hasAnyAuthority` results were eager field initializers, and the check returns `false` for an *expired* token, so after a refresh an admin saw the non-admin view until something reconstructed the component. Converted to getters with a memoized decode.
- ✅ **CI genuinely gating** (2026-07-26) — `npm audit` 15 high + 1 critical → 0 (including a real Angular template sanitization bypass leading to XSS, patched in 21.2.17) and `ng lint` 12 errors → 0, ten of which were genuine accessibility defects.
- ✅ **Security-critical-path tests** (2026-07-26) — refresh rotation, TOTP challenge binding, org-scoped reads *and* writes. **+15 tests.**
- ✅ **FR-TPF-1 anomaly detection + step-up**, **FR-ORG-2 org-scoped analytics**, and the **`X-Forwarded-For` trust fix** (2026-07-25).
