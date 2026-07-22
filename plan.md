# TesseraApp — Master's Project Plan

> **A hybrid, zero-trust identity platform with a UI that *visualizes* the security domain.**
> The graded auth/security engineering and the visual overhaul are the same project: the
> interface's job is to make the identity & access work *visible*, which simultaneously
> solves "there's nothing interesting to display" and turns the security model into the
> centerpiece.

---

## 1. North star

Two tracks, one story:

1. **Security track** — build out in-house **CIAM** (customer identity & access management) with a **zero-trust** posture: never trust, always verify; every request authenticated, every event audited, access re-evaluated on risk.
2. **UI track** — a custom design-token layer over Bootstrap 5.3 that renders the above as a polished, dark-first "security console."

The unifying enabler already exists: the **`UserEvent` audit model** captures `type`, `device`, `ipAddress`, and `createdAt` on security events — so the dashboards run largely on data we already collect.

---

## 2. What "hybrid" means here

The auth architecture is deliberately **hybrid: stateless tokens + a stateful trust layer.** Neither alone is sufficient for zero-trust.

| Concern | Stateless half (JWT) | Stateful half (server session/refresh store) |
|---|---|---|
| **Access token** | HMAC-SHA512-signed JWT, 30-min TTL, verified with no DB hit | — |
| **Revocation** | (impossible alone — token valid until expiry) | Refresh tokens tracked server-side → instant revoke / logout-everywhere |
| **Device & session mgmt** | — | Each session row carries device + IP; user can see/revoke them |
| **Refresh** | Short access token keeps blast radius small | **Rotation + reuse-detection**: a replayed refresh token nukes the family |
| **Risk / step-up** | Claims travel with the request | New device/IP or sensitive action → re-verify (stateful decision) |

**Why hybrid:** the stateless JWT gives scale and a clean request path; the stateful layer restores the *control and visibility* (revocation, device list, reuse detection, risk decisions) that pure-JWT throws away. That tension — and resolving it — is the project's core argument.

---

## 3. Snapshot — where we are today

**Stack:** Angular 21 SPA (`tesseraapp/`) · Spring Boot 4 / Spring Security 7 / Java 21 (`src/`) · MySQL 8.4 · JDBC (not JPA repositories). Run via `start.sh` (`ENV=local|docker`, `DB=local|aiven`). Containerized (multi-stage Dockerfile) with an Azure pipeline.

**Backend already provides — claim credit, don't rebuild:**
- Stateless JWT (HMAC-SHA512), 30-min access + 5-day refresh, refresh endpoint
- Permission-based RBAC
- BCrypt password hashing
- Password-change token invalidation (`passwordChangedAt`)
- Audit logging with device + IP (`UserEvent`)
- Anti-enumeration on auth flows · security response headers
- **Federated OAuth2/OIDC login** (Google/GitHub/Microsoft, env-conditional) converging at a token-exchange point that issues our JWTs; MFA + disable/lock policy applied identically to federated sessions (SRS FR-FED)
- **Admin user management** (`/admin/user/**` + `/users` dashboard) with FR-RBAC-4 closed (no self role-change anywhere)
- **Organization-scoped administration** — `ROLE_ORGANIZATION_ADMIN` sees/manages only same-org users, 403 outside scope (SRS FR-ORG); schema via Flyway V1–V4

**Known debt / gaps (track, address opportunistically):**
- SMS-2FA dispatch remains **stubbed** (code logged, not sent) — acceptable now that TOTP (M4) is the production-grade factor; live SMS needs only Twilio creds + uncommenting the call
- **Near-zero tests** (one context-load test; no frontend specs) — rotation/reuse, TOTP challenge binding, and org scoping are the priority candidates
- Frontend **hardcodes `localhost:8080`** as the API base — portability gap
- **Two JWT libraries** on the classpath (`jjwt` + `java-jwt`) — consolidation candidate
- `ddl-auto: update` + `globally_quoted_identifiers` — README itself recommends Flyway + `validate` for prod (identity/security tables are now Flyway V1–V6; the JPA customer/invoice tables are the remainder)
- ~~Access-token Javadoc wrongly says 230 min~~ — fixed in M5 (`UserController#sendResponse` doc + devMessage now say 30 min)

---

## 4. Design system — **M0 ✅ shipped**

Custom CSS-variable token layer mapped onto Bootstrap's `--bs-*` vars (`src/styles.css`), so existing markup re-skins with no template rewrites:
- Dark-first; light theme via `[data-bs-theme]`, toggled by `ThemeService` (signal) with pre-paint flash prevention in `index.html`
- Deep-slate surfaces, **electric-iris `#6b5bff`** accent, glassmorphism cards, aurora + technical-grid atmosphere
- **IBM Plex Sans / Mono** (mono = "security console" voice for eyebrows, labels, table headers)
- ~10 semantic tokens → palette is trivially reversible
- Rebranded navbar (gradient shield-lock mark, animated theme toggle)

---

## 5. Roadmap — M0 → M7 (dependency-ordered)

| # | Milestone | Status | Key work |
|---|---|---|---|
| M0 | Design foundation | ✅ done | tokens, dark/light, IBM Plex, navbar rebrand |
| M1 | Auth screens + shell polish | 🔄 in progress | login/register/verify/reset redesigned ✓ · route transitions · skeleton loaders |
| M2 | Security / activity dashboard | ⬜ | count-up stats, login chart, MFA ring, audit feed, device/IP list |
| M3 | Roles × Permissions matrix | ✅ done | admin Users dashboard shipped (`/users` list + `/users/:id` manage: role reassign, enable/lock, audit history; FR-ADMIN-1..5); FR-RBAC-4 self-elevation gap closed; SRS seven-role catalog via Flyway V2; visual matrix grid shipped (`RolesMatrixComponent`, `/roles`; read-only — assignment via the Users dashboard) |
| M4 | TOTP authenticator MFA | ✅ done | in-house RFC-6238 (`TotpUtils`) + zxing QR enroll; challenge-bound verify; hashed recovery codes; **created** the Account Security Center (`/security`) |
| M5 | Sessions & device management | ✅ done | `refreshsessions` (Flyway V6); rotation + family-wide **reuse detection** via `SessionService` (the single token-issuance seam); sessions panel + revoke / log-out-everywhere; 30-min TTL doc fixed |
| M6 | Risk-based step-up + lockout | 🔄 partial | brute-force lockout shipped (login gate, SRS FR-EXT-1 partial); new device/IP → re-verify still open |
| M7 | Micro-interactions & polish | ⬜ | Ctrl+K command palette, empty states, toast restyle |

> The **Account Security Center** is not a milestone — it's the surface M4 creates and M5/M6 populate.

---

## 6. Milestone detail

### M1 — Auth screens + shell polish  🔄
- [x] **Login / MFA → "access console"** two-panel redesign (token-driven, dark/light, all bindings preserved; fixed `#0d6efd` drift)
- [x] **Register** — two-panel "access console" redesign shipped; inline validation + "check your inbox" success state
- [x] **Verify / reset-password** — shipped; clear success / expired-link states (`@switch` on `DataState`)
- [ ] **Route transitions** via the already-installed `@angular/animations`
- [ ] **Skeleton loaders** — also fixes the "renders nothing during `LOADING`" gap
- [ ] *(Optional)* lift the navbar into the app shell so authed pages share one chrome

### M2 — Security / activity dashboard  ⬜
Home becomes a security overview driven by `UserEvent`: animated stat counters (logins, active sessions, MFA %), a login-activity sparkline/chart, an MFA-coverage ring, a live audit feed, and a device/IP list. *Mostly existing data — minimal backend.*

### M3 — Roles × Permissions matrix  ✅
Interactive grid of roles vs. permissions; admin toggles assignments. Ties into the planned **admin update endpoint** and **org-scoped access**.

### M4 — TOTP authenticator MFA  ✅
Swap stubbed SMS for RFC-6238 TOTP: secret + QR enrollment, verify on login, recovery codes. Establishes the **Account Security Center** page.

### M5 — Sessions, devices & token hardening  ✅
The stateful half of the hybrid model: persist refresh tokens/sessions with device+IP, list + revoke ("log out everywhere"), implement **rotation with reuse-detection**, confirm the 30-min access TTL and fix the stale Javadoc.

### M6 — Risk-based step-up + brute-force lockout  🔄
Score each login against known device/IP history (from `UserEvent`); unknown context forces re-verification. Add lockout/backoff after repeated failures.

### M7 — Micro-interactions & final polish  ⬜
`Ctrl+K` command palette, considered empty/error states, restyled toasts, final spacing/motion pass.

---

## 7. Build-order rationale

Design tokens (M0) gate every visual; auth screens (M1) are the highest-traffic surface and the demo's first impression; the dashboard (M2) gives the app something compelling to *show* using existing data; RBAC/MFA/sessions/risk (M3–M6) deepen the actual security story in dependency order (you need MFA before a "security center," sessions before risk-based step-up); polish (M7) lands last so it isn't redone.

---

## 8. Risks & open questions

- **Deadline** — the previously-logged 2026-05-26 date has passed; confirm the real submission date so milestones can be timeboxed.
- **Test coverage** — near-zero today; decide how much to add for the security-critical paths (M5/M6 especially).
- **API base URL** — the hardcoded `localhost:8080` should become environment-driven before any real deployment demo.
- **Scope vs. time** — M0–M2 already make a strong visual + "live data" story; M3–M7 are where scope can flex if time is tight.

---

*This file is the canonical roadmap. Status markers (✅ / 🔄 / ⬜) are updated as milestones land.*
