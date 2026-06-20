# Vertical-Slice Rollout Plan — `MastersProjectSRSImpl` → `master`

For the teammate review. This branch holds ~25 new backend classes + a full frontend overhaul. Rather
than merge it as one wall (or split it by layer, which breaks — the UI calls endpoints that must already
exist), we merge it as **vertical slices**: each PR carries the backend, frontend, schema, and security
config for *one capability*, so `master` is always shippable and each PR reviews as a coherent feature.

> Verified 2026-06-18 by tracing frontend endpoint calls (`securecapitaapp/src/app/service/*` + feature
> components) against backend controller mappings (`src/main/java/.../controller/*`).

---

## How to read this

Each slice lists **frontend**, **backend**, **schema/config**, **depends-on**, and a rough **PR size**.
Cross-cutting rules (schema, security-config lockstep, the token seam) are in §B — read those first.

---

## A. The slices (recommended merge order)

### Slice 0 — Visual layer + endpoint-reusing features  ·  *no new backend*  ·  **ship first**
Pure look-and-feel plus three feature pages that reuse endpoints already on `master`. Safe, high-impact,
unblocks the "I love the frontend" win immediately.
- **Frontend:** TesseraApp rebrand (`navbar`, `index.html`, `styles.css`, auth screens) · the `.sc-*`
  design-token surface layer in `styles.css` · restyle of existing pages (home chrome, customers,
  invoices, profile) · `environments/environment.ts` + `environment.production.ts` ·
  `features/users/roles-matrix/` (reads `GET /user/profile`) · `features/billing/billing/`
  (`GET /customer/stats`, `GET /customer/invoice/list`) · `features/services/services-catalog/`
  (`GET /customer/invoice/new`).
- **Backend:** none.
- **Watch:** confirm `billing` computes its status breakdown client-side from `invoice/list` and does
  **not** read the new `status-breakdown` field on `/customer/stats` (that field is Slice 1). If it does,
  pull billing into Slice 1.
- **PR size:** large diff, low risk (CSS/markup + 3 read-only components).

### Slice 1 — Dashboard insights  ·  *small backend mod*
- **Frontend:** `shared/insights/` (status donut + billing ratios) · the home-dashboard wiring.
- **Backend:** **modification** (not new controller) — `CustomerController#/stats` + `CustomerService` +
  `CustomerQuery`/`EventQuery` gain the `GROUP BY status` breakdown field.
- **Depends on:** Slice 0 (token layer for the donut styling).
- **PR size:** small.

### Slice 2 — Token-issuance seam + Sessions/Devices  ·  *foundational*
This is the **keystone**: `SessionService` is the single place tokens are minted, and it backs login,
refresh, TOTP-verify, and federated login. Introducing it refactors the existing login path to issue
through `SessionService`, so it should land **before** Slices 3–4.
- **Frontend:** `features/security/security-center/` (Sessions tab) · `interface/security.interface.ts`
  (session shapes) · `user.service` session calls.
- **Backend:** `SessionController` (`GET /user/sessions`, `DELETE /user/sessions/{family}`,
  `DELETE /user/sessions`) · `SessionService` (+Impl) · `model/RefreshSession.java` · `query/SessionQuery`
  · token-rotation + reuse-detection changes in `TokenProvider`.
- **Schema:** `refreshsessions` table.
- **Depends on:** Slice 0.
- **PR size:** medium-large (touches the core login path — review carefully).

### Slice 3 — TOTP MFA  ·  *builds on Slice 2*
- **Frontend:** `security-center` (TOTP tab) · login MFA step (`/user/verify/totp`) ·
  `user.service` totp methods · `security.interface` totp shapes.
- **Backend:** `TotpController` (`/user/totp/setup|enable|disable|status`, `/user/verify/totp`) ·
  `TotpService` (+Impl) · `utils/TotpUtils` (RFC-6238) · `form/TotpCodeForm` + `TotpVerifyForm` ·
  `query/TotpQuery` · `UserController#/update/togglemfa`.
- **Schema:** TOTP columns on the user table (secret, enabled, hashed recovery codes).
- **Depends on:** Slice 2 (TOTP-verify mints a session via `SessionService`).
- **PR size:** medium.

### Slice 4 — Federated OAuth2 / OIDC login  ·  *builds on Slice 2*
- **Frontend:** `features/auth/oauth2-callback/` · provider-buttons row on login (`GET /oauth2/providers`).
- **Backend:** `FederatedAuthController` (`/oauth2/providers`) · `handler/OAuth2LoginSuccessHandler`
  (mints our JWT via `SessionService`) · `configuration/OAuth2ClientConfig` +
  `FederatedProviderCatalog` · `FederatedIdentityService` (+Impl) · `query/OAuthQuery`.
- **Schema:** federated-identity columns/table.
- **Config:** OAuth client IDs/secrets (Google/GitHub/Microsoft) per environment — providers appear only
  when configured.
- **Depends on:** Slice 2.
- **PR size:** medium.

### Slice 5 — Admin user management + Org scoping  ·  *mostly independent*
- **Frontend:** `features/users/users/` (list) · `features/users/user-details/` (manage) ·
  `service/admin-user.service` · `guard/admin.guard` · `interface/admin.interface`.
- **Backend:** `AdminUserController` (`/admin/user/list|{id}|{id}/events|{id}/role/{roleName}|{id}/settings`)
  · `OrganizationService` (+Impl) · `query/OrganizationQuery` · role/settings updates on the user aggregate.
- **Schema:** organization column(s); seven-role catalog seed.
- **Depends on:** the role/permission model (already on master) + org schema. Independent of Slices 2–4,
  so it can move earlier if the admin UI is the priority.
- **PR size:** medium-large.

### Cross-cutting (rides along with the relevant slice, not its own PR)
- `seed/DemoDataSeeder` — dev-only seed data (lands with Slice 2 or whichever first needs seeded accounts).
- `utils/event-display.utils` + `enumeration/event-type.enum` changes — display-only; harmless early,
  meaningful once the slices that emit new audit `EventType`s land.
- The 4 test suites — attach each to the slice it covers; add the missing security-path tests (see §C).

---

## B. Cross-cutting rules (every coupled slice must honor)

1. **`schema.sql` is hand-applied** (no Flyway; `sql.init.mode: never`). Each slice's schema additions
   must be run against every environment when that slice merges. The file is idempotent
   (`CREATE TABLE IF NOT EXISTS`), so applying the whole file is safe, but **coordinate who runs it and
   when** — this is the #1 team-coordination footgun.
2. **Public-route lockstep:** any slice adding a public endpoint must update **both**
   `Constants.PUBLIC_URLS` (filter-chain `permitAll`) **and** `Constants.PUBLIC_ROUTES` (filter skip list),
   or a stale `Bearer` header breaks the route. Matchers in `SecurityConfig` are top-down — specific rules
   before the `/**` catch-alls.
3. **The token seam:** all token minting goes through `SessionService` (Slice 2). Slices 3–4 depend on it.
   Don't let a later slice mint tokens directly.
4. **Prod profile:** `ddl-auto: validate` means the JPA tables must exist before boot; `JpaSchemaSyncTest`
   guards `schema.sql` against the entity mappings at build time.

---

## C. What else to plan (beyond the merge)

**Team workflow (now that there are two of you):**
- **CI gate on PRs.** An Azure deploy pipeline exists, but add a PR build that runs `mvn test` (+ `ng build`)
  so a slice can't merge red. This is the highest-leverage thing to set up before slice merges start.
- **Ownership split.** Decide who owns which slices; Slices 2–4 (auth core) are best owned by one person to
  keep the token seam coherent.
- **`api-reference.md` sync.** It likely predates the newest admin/oauth2/totp/session endpoints — refresh
  it as each slice lands so your teammate has accurate API docs.

**Production-readiness (tracked, address per slice or before a real deploy):**
- **Tests for security-critical paths** — refresh rotation + reuse-detection, TOTP challenge binding, org
  scope (see `week-5-plan.md`). Frontend has **zero** specs; add at least smoke tests.
- **Real prod boot with `validate`** against a MySQL seeded only from `schema.sql` — never exercised yet.
- **Secrets management** — prod needs a strong `JWT_SECRET`, DB creds, OAuth client secrets, and mail creds
  supplied via the platform, not `.env`. Plan this before Slice 4 (OAuth secrets) merges.
- **SMS 2FA decision** — currently stubbed (code logged, not sent). Either wire Twilio or formally document
  it as out of scope; don't leave it ambiguous.
- **JWT library consolidation** — `jjwt` + `java-jwt` are both on the classpath; pick one.
- **Rate limiting** — brute-force lockout exists on login, but there's no general request rate limit (open NFR).

**Remaining roadmap (`plan.md`):** M2 security dashboard (Slice 1 is the first cut), M6 risk-based step-up
(partial — new-device re-verification still open), M7 polish (command palette, empty states).

---

## D. Dependency order at a glance

```
Slice 0 (cosmetic + reuse) ──┬─> Slice 1 (insights)
                             ├─> Slice 5 (admin/RBAC + org)      [independent of 2–4]
                             └─> Slice 2 (sessions + token seam) ──┬─> Slice 3 (TOTP MFA)
                                                                   └─> Slice 4 (federated login)
```

Start with **0**, land **1** and **5** whenever (independent), and treat **2 → 3 → 4** as an ordered chain.

---

*Companion to [`plan.md`](plan.md) (roadmap), [`week-5-plan.md`](week-5-plan.md) (near-term), and
[`branch-changelog.md`](branch-changelog.md) (what's on the branch). Update as slices merge.*
