# Branch Changelog — `MastersProjectSRSImpl`

Complete record of every addition and change made on this branch since it diverged from `master`.
The branch holds experimental, master's-project work that was **not pushed consistently and has
terse commit messages**, so this file — reconstructed from the actual diff — is the authoritative
record of what landed.

> **Reproduce the raw data:**
> ```bash
> BASE=$(git merge-base master HEAD)      # 617ae18  (2026-06-06)
> git log --reverse --stat $BASE..HEAD    # full per-commit changes
> git diff --stat $BASE..HEAD             # aggregate
> ```

> **Post-submission cleanup (2026-06-18):** after these artifacts were submitted to the course
> portal, the `deliverables/` corpus (§6), the literature review, the stray `~WRL3006.tmp`, and the
> six legacy `documentation/` files (§8) were **removed from the working tree** to slim the repo.
> They remain recoverable from git history (any commit up to `8c42c3e`). The **SRS** was kept (due
> next). Sections 6 and 8 below describe what was on the branch *before* this cleanup.

---

## At a glance

| | |
|---|---|
| **Branch** | `MastersProjectSRSImpl` |
| **Forked from `master` at** | `617ae18` — *"Store bare verification keys; build frontend links"* (2026-06-06) |
| **Span of work** | 2026-06-11 → 2026-06-18 (18 feature commits over 12 days) |
| **Aggregate diff** | **209 files changed, +17,865 / −1,362** (measured through `0a2f3ea`; excludes the later doc-only post-submission cleanup) |
| **New files** | **133** (25 backend classes · 4 tests · 27 frontend files · 24 doc guides · 47 deliverable artifacts · root docs) |
| **Headline themes** | Federated OAuth2 login · TOTP MFA · stateful refresh-sessions + reuse detection · admin/RBAC + org scoping · dashboard insights + UI token layer · TesseraApp rebrand + billing overview & services catalog · production hardening · a full documentation + deliverables corpus |

**Caveat on attribution:** the five 2026-06-11 commits were authored seconds apart (a single
working session split by area), so "when" below is grouped by *phase*, not by literal commit
boundary. Purposes are reconstructed from code + context where commit messages were uninformative.

---

## 1. Timeline (chronological)

### Phase A — Security feature drop (2026-06-11, commits `5d9f078`→`98d5d79`, ~5.7k insertions)
The bulk of the new security surface landed here across five same-session commits:
- `5d9f078` *Update POM, front end interfaces and services* — deps + frontend interfaces/services groundwork (+529).
- `7d5c6b7` *Update frontend - various* — frontend feature components (+1,544).
- `78d2d7b` *Update backend configs, constants, controllers and DTOs* — new controllers/config/DTO wiring (+1,104).
- `9b435f6` *Update query and Utils* — SQL-constant query classes + utils, incl. `TotpUtils` (+875).
- `98d5d79` *Update DB ... federation, IDM, and refresh sessions* — `schema.sql` + model/repo for federation, identity, refresh sessions (+1,638).

**Net capability added:** federated OAuth2/OIDC login, TOTP authenticator MFA, stateful
refresh-session store with rotation/reuse-detection, admin user management, organization scoping.

### Phase B — SRS implementation (2026-06-13, `96751cd`, +1,337)
*"Further implementation of required SRS sheet"* — code + config to satisfy the SRS functional
requirements (federation/MFA/session/org features wired to FR-* IDs).

### Phase C — Documentation corpus (2026-06-13 → 2026-06-16, `3c2e97c`,`fc3ef26`,`aa1c92b`,`c2b9a40`,`b1c77d3`, ~7.6k insertions)
The large documentation build-out:
- `3c2e97c` — topic guides brought to current project state (+2,225).
- `fc3ef26` — 61 files: the `documentation/flows/` click-to-DB flow docs + deliverables scaffolding (+2,316).
- `aa1c92b` — architecture documents + diagrams (+2,792).
- `c2b9a40` — documentation polish (+118).
- `b1c77d3` — reusable backend blueprint + `CLAUDE.md` (+194).

### Phase D — Production hardening + UI consistency (2026-06-17, `0e33ea7`→`353f1b5`)
- `0e33ea7` *Dashboard insights + UI consistency layer + production hardening* — `insights` component, `styles.css` token/surface layer, `DemoDataSeeder`, schema/status-breakdown (+1,812 / −789).
- `709db3e` *Make profile-image storage portable* — `WebMvcConfig` + `application.yml` image-path env-driven (+90 / −45).
- `9174876` *Prod hardening* — `JwtSecretGuard`, removed artificial auth delays, `GlobalExceptionHandlerTest` (+217 / −20).
- `353f1b5` *Fix login user-enumeration* — unify unknown-email vs wrong-password failures (+69 / −23).

### Phase E — Prod-readiness finish (2026-06-18, `a891bd2`,`8c42c3e`)
- `a891bd2` — fixed circular-placeholder config bug (dev→literals, prod→direct env reads), SRS rev 0.3, JPA `validate` + `JpaSchemaSyncTest` drift guard, login-enumeration regression test (+463 / −52).
- `8c42c3e` — corrected `show-sql` default in the annotated config doc (+3 / −3).

### Phase F — TesseraApp rebrand + billing/services (2026-06-18, `0a2f3ea`, +1,281 / −53)
Author's own frontend feature drop on top of the prod-readiness work:
- **TesseraApp rebrand** — app renamed across navbar, `index.html`, `styles.css`, auth screens.
- **Billing overview** — new `features/billing/billing/` component (`.ts/.html/.css`).
- **Services catalog** — new `features/services/services-catalog/` component (`.ts/.html/.css`).
- **Clickable metrics** — `shared/stats/` made interactive; navbar + routes extended for the new pages.

> *Post-cutoff note:* the original §-by-§ inventory below was compiled through `8c42c3e`; the two
> new components from `0a2f3ea` are listed in §4. The doc-only post-submission cleanup commits that
> follow `0a2f3ea` are not feature additions.

---

## 2. Backend additions (`src/main/java`) — 25 new classes

### Configuration (3)
- `configuration/OAuth2ClientConfig.java` — registers OAuth2 client registrations, conditionally on env-provided client IDs/secrets.
- `configuration/FederatedProviderCatalog.java` — enumerates which federated providers are enabled, backing the provider-discovery endpoint (login buttons appear only when configured).
- `configuration/JwtSecretGuard.java` — fail-fast startup guard: rejects boot if `JWT_SECRET` is missing, the dev placeholder, or too short (prod profile).

### Controllers (4)
- `controller/AdminUserController.java` — admin user management (`/admin/user/**`, `/users`): role reassignment, enable/lock, audit history.
- `controller/FederatedAuthController.java` — federated provider discovery + federated auth endpoints.
- `controller/SessionController.java` — list active refresh-sessions (device/IP), revoke one, "log out everywhere".
- `controller/TotpController.java` — TOTP enroll / verify / disable.

### Services + impls (8)
- `service/FederatedIdentityService.java` (+ `serviceimpl/FederatedIdentityServiceImpl.java`) — links external OIDC identities to local accounts.
- `service/OrganizationService.java` (+ `serviceimpl/OrganizationServiceImpl.java`) — organization-scoped access (FR-ORG): org-admins see/manage only same-org users.
- `service/SessionService.java` (+ `serviceimpl/SessionServiceImpl.java`) — **the single token-issuance seam**: mints JWTs, persists refresh sessions, rotates tokens, and performs family-wide reuse detection.
- `service/TotpService.java` (+ `serviceimpl/TotpServiceImpl.java`) — RFC-6238 enrollment, challenge-bound verification, hashed recovery codes.

### Queries / model / forms / handler / utils / seed (10)
- `query/OAuthQuery.java`, `query/OrganizationQuery.java`, `query/SessionQuery.java`, `query/TotpQuery.java` — named-param SQL constants for the new aggregates.
- `model/RefreshSession.java` — the stateful refresh-session row (device, IP, timestamps).
- `form/TotpCodeForm.java`, `form/TotpVerifyForm.java` — validated TOTP request bodies.
- `handler/OAuth2LoginSuccessHandler.java` — on successful federated login, mints our own JWT (centralized via `SessionService`).
- `utils/TotpUtils.java` — in-house RFC-6238 TOTP code generation/validation (no external TOTP lib).
- `seed/DemoDataSeeder.java` — seeds demo data on dev startup (idempotent).

### Notable backend modifications
- `configuration/SecurityConfig.java` — authz matchers for the new `/admin`, `/user/totp`, `/session`, `/oauth2` routes (top-down ordering); OAuth2 login wiring.
- `constants/Constants.java` — `PUBLIC_URLS` / `PUBLIC_ROUTES` extended for the new public endpoints (kept in lockstep).
- `tokenprovider/TokenProvider.java` — token issuance/claims changes feeding `SessionService` (signature change noted in memory).
- `controller/UserController.java` — MFA/TOTP login branch, session issuance, enumeration-safe failure unification, 30-min TTL doc fix.
- `repo/UserRepo.java` + `repoimpl/UserRepoImpl.java`, `rowmapper/UserRowMapper.java`, `dto/UserDTO.java`, `model/User.java` — federation/MFA/org columns threaded through the user aggregate.
- `repo/EventRepo.java` + `repoimpl/EventRepoImpl.java`, `query/EventQuery.java`, `enumeration/EventType.java` — audit events for the new flows; status-breakdown aggregation for the dashboard.
- `model/Customer.java`, `model/Invoice.java`, `query/CustomerQuery.java`, `service*/Customer*`, `Event*` — dashboard insights (status grouping) + JPA hardening alignment.
- `configuration/WebMvcConfig.java` — portable profile-image storage path.
- `utils/SMSUtils.java` — SMS-2FA dispatch (still stubbed — code logged, not sent).
- `resources/{application.yml,application-dev.yml,application-prod.yml,schema.sql}` — profile/config/schema changes (incl. the circular-placeholder fix and prod JPA `validate`).

---

## 3. Tests added (`src/test/java`) — 4 suites
- `controller/UserControllerLoginEnumerationTest.java` — unknown-email vs wrong-password return byte-identical 400s (FR-AUTH-4 / NFR-SEC-7).
- `exception/GlobalExceptionHandlerTest.java` — `@Valid` body failure → 400 envelope listing every field message.
- `service/serviceimpl/CustomerServiceImplTest.java` — customer-service unit coverage.
- `tooling/JpaSchemaSyncTest.java` — offline-Hibernate drift guard: `schema.sql` must contain every JPA-mapped table/column.

*(Suite as of branch tip: 6 classes / 14 tests, all green — `contextLoads` boots the full context end-to-end.)*

---

## 4. Frontend additions (`securecapitaapp/src`) — 27 new files

### Feature components (11)
- `features/auth/oauth2-callback/` — handles the OAuth2 redirect, exchanges for our JWT.
- `features/security/security-center/` (`.ts` + `.html`) — the Account Security Center (`/security`): MFA + sessions.
- `features/users/users/` (`.ts/.html/.css`) — admin users list (`/users`).
- `features/users/user-details/` (`.ts/.html/.css`) — admin manage a single user (`/users/:id`).
- `features/users/roles-matrix/` (`.ts/.html`) — read-only roles × permissions grid (`/roles`).

### Shared / services / guards / interfaces / utils / env (10)
- `shared/insights/` (`.ts/.html/.css`) — dashboard insights (status donut + billing ratios for admins, quick-actions otherwise).
- `service/admin-user.service.ts` — calls the `/admin/user/**` endpoints.
- `guard/admin.guard.ts` — route guard restricting admin-only routes.
- `interface/admin.interface.ts`, `interface/security.interface.ts` — typed contracts for the new responses.
- `utils/event-display.utils.ts` — maps audit `EventType`s to display labels/icons.
- `environments/environment.ts` (`apiUrl: http://localhost:8080`) + `environments/environment.production.ts` (`apiUrl: ''`, relative) — environment-driven API base.

### Billing & services (6, from `0a2f3ea` — TesseraApp rebrand)
- `features/billing/billing/` (`.ts/.html/.css`) — billing overview page.
- `features/services/services-catalog/` (`.ts/.html/.css`) — services catalog page.

### Notable frontend modifications
- `app.routes.ts` — routes for users/roles/security/oauth2-callback (admin-guarded).
- `styles.css` — the design-token + `.sc-*` surface/consistency layer (dark-first, electric-iris accent).
- `shared/navbar/`, `shared/stats/` — rebrand + admin nav + stat surfacing.
- `features/home/`, `features/profile/`, `features/customers/*`, `features/invoices/*` — UI consistency pass + insights integration.
- `features/auth/login/`, `interface/user.interface.ts`, `interface/appstates.interface.ts`, `enumeration/event-type.enum.ts`, `service/{user,customer}.service.ts` — MFA login flow + typed state + new event types.

---

## 5. Documentation additions (`documentation/`) — 24 guides
- **Hub + topic guides:** `README.md`, `getting-started.md`, `developer-guide.md`, `architecture.md`, `api-reference.md`, `security.md`, `database.md`, `configuration.md`, `deployment.md`, `backend-blueprint.md`.
- **Flow docs (`documentation/flows/`, 14):** `README.md` (hub) + `00-anatomy-of-a-request` and per-flow `01`–`32` (register/verify, login+MFA, password reset, federated OAuth2, token-refresh/sessions, profile/account, TOTP enrollment, sessions/devices, admin/RBAC, customers, invoices, dashboard) — Mermaid + `file:line` + JSON + SQL.

---

## 6. Deliverables corpus (`deliverables/`) — 47 artifacts
Master's-course deliverables (Markdown sources + generated `.docx`/`.pptx`, built via
`build_deliverables.py`):
- **Reports/scripts/slides:** `system-architecture-and-ui-design`, `implementation-1-report`, `implementation-2-report`, `final-report`, `final-presentation-slides` (+ `.pptx`), `final-presentation-video-script`, `software-demo-1-video-script`, `software-implementation-2-video-script` (each `.md` + generated binary).
- **Diagrams (`deliverables/documentation/diagrams/`):** 11 Mermaid `.mmd` sources → rendered `.png`/`.svg` (architecture, ER data model, auth flows, API endpoint map, frontend component tree + route map), plus `render.sh` and `VISUALS.md`.
- `build_deliverables.py` — pypandoc/pandoc generator for the `.docx`/`.pptx` outputs.

---

## 7. Root-level additions
- `CLAUDE.md` — project instructions (backend blueprint, conventions).
- `software_requirements_specification.md` (+ `.docx`) — the SRS (now rev 0.3; single source of truth).
- `literature_review_v2.md` + `lit-review-final-06-14-26 (1).docx` — CIAM literature review.

---

## 8. Housekeeping flags (worth cleaning before/at merge)
- ⚠ **`~WRL3006.tmp`** — a Microsoft Word temp file committed by accident. Should be removed and added to `.gitignore`.
- ⚠ **`lit-review-final-06-14-26 (1).docx`** — the ` (1)` suggests a duplicate-download artifact; confirm it's the intended file and rename.
- 📦 **Generated binaries tracked in git** — the `.docx`/`.pptx`/`.png`/`.svg` deliverables are regenerated from their sources (`build_deliverables.py`, `render.sh`). Fine to keep for submission, but they inflate the diff and can drift from sources; consider regenerating at build time instead.
- 🔀 **Branch is ahead of `master`** — all 17 commits above are still only on `MastersProjectSRSImpl`; none merged. See `week-5-plan.md` P0.

---

*Generated 2026-06-18 from `git diff 617ae18..HEAD`. Update if the branch grows before merge.*
