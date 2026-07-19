# Branch Comparison: `master` vs `MastersProjectSRSImpl`

**Generated:** 2026-06-28  
**Scope:** 20 commits, 176 files changed, +17,961 insertions, -3,715 deletions

---

## Executive Summary

This branch is **NOT a simple feature branch**. It includes:
- ✅ Major backend refactoring (7 new services, 15 updated service files)
- ✅ Complete schema redesign (314 lines changed in `schema.sql`)
- ✅ New database tables for OAuth2, TOTP, sessions, organizations
- ✅ Frontend rebrand (SecureCapita → TesseraApp)
- ✅ Production hardening (JWT, config placeholders, error handlers)
- ✅ Comprehensive documentation (13 new flow guides, architecture docs)

**To run this branch locally, you MUST initialize the database with the new schema.**

---

## Database Schema Changes

### New Tables (Required for this branch)

| Table | Purpose | Was in `master`? |
|-------|---------|------------------|
| `refreshsessions` | Server-side token rotation & reuse detection | ❌ NEW |
| `totpcredentials` | TOTP/authenticator-app secrets | ❌ NEW |
| `totprecoverycodes` | TOTP backup recovery codes | ❌ NEW |
| `mfachallenges` | MFA challenge tracking | ❌ NEW |
| `oauthproviderlinks` | OAuth2/federated identity links | ❌ NEW |
| `organizations` | Org-scoped access control | ❌ NEW |
| `userorganizations` | User-org membership | ❌ NEW |

### Modified Tables

| Column | Table | Change | Why |
|--------|-------|--------|-----|
| `using_totp` | `users` | ✅ ADDED | Tracks if user has enrolled TOTP |
| `password_changed_at` | `users` | ✅ ADDED | Auditing + session revocation |
| Various | `accountverifications`, `resetpasswordverifications` | ✅ RESTRUCTURED | Verification flow improvements |

### Removed Tables

- **Flyway migration tables** (`flyway_schema_history`) — Flyway was removed entirely

---

## Backend Service Changes

### NEW Services (7 total)

1. **`FederatedIdentityService`** — OAuth2/OIDC login integration
2. **`OrganizationService`** — Org-scoped access control
3. **`SessionService`** — Server-side refresh token rotation & reuse detection
4. **`TotpService`** — Authenticator app (TOTP) multi-factor authentication
5. **`EventService`** — Audit logging for all user actions
6. **`CustomerService`** — (refactored from `master`)
7. **`UserService`** — (refactored from `master`)

### Updated Service Implementations

- `EmailServiceImpl` — 4 lines changed
- `EventServiceImpl` — 9 lines added (new)
- `FederatedIdentityServiceImpl` — 129 lines added (new)
- `OrganizationServiceImpl` — 113 lines added (new)
- `SessionServiceImpl` — 220 lines added (new)
- `TotpServiceImpl` — 241 lines added (new)
- `UserServiceImpl` — 35 lines changed
- `TokenProvider` — **67 lines changed** (signature change for session rotation)

### Key Breaking Changes in Backend

1. **`TokenProvider` signature changed** — If you call `TokenProvider.generateToken()` anywhere in old code, it will fail. Now it's integrated with `SessionService`.

2. **Flyway REMOVED** — No migrations. Schema is now all in `src/main/resources/schema.sql` (idempotent, run by hand).

3. **`SecurityConfig` matcher ordering** — Specific routes (`/user/totp`, `/security/sessions`) are now gated before the broad `/**` catch-alls.

---

## Configuration Changes

### `application.yml`

| Setting | Old | New | Impact |
|---------|-----|-----|--------|
| `spring.jpa.hibernate.ddl-auto` | `update` | `validate` (prod) / `update` (dev) | Production fails-fast if schema drifts |
| `spring.sql.init.mode` | (implicit) | `never` | Schema.sql never runs automatically; run by hand |
| `show-sql` | Varies | `false` (prod) / `${SHOW_SQL:false}` (dev) | Better security + performance |
| `globally_quoted_identifiers` | Not explicit | `true` | Enables camelCase column names in JPA entities |

### `.env.example`

**New required variables:**

```bash
JWT_SECRET=your-secret-key-here
SMS_PROVIDER=TWILIO_STUB  # SMS is stubbed; not a real integration
VERIFY_EMAIL_HOST=<reserved for future use>
SHOW_SQL=false
CONTAINER_PORT=8080
DB_INIT_MODE=never
```

---

## Frontend Changes

### Rebrand: SecureCapita → TesseraApp

- App name updated throughout UI
- Routing, components, styling updated

### New Components

- `analytics/analytics.component.ts` — Dashboard insights hub
- Invoice/customer trend panels

### Updated Components

- `auth/login/login.component.html` — UI overhaul
- `auth/login/login.component.ts` — New error handling, TOTP flows

---

## Documentation Added

### New Guides (in `documentation/`)

1. **`README.md`** — Hub for all documentation
2. **`getting-started.md`** — Quickstart (run `schema.sql`, `.env`, start app)
3. **`developer-guide.md`** — Code patterns, layering, conventions
4. **`architecture.md`** — System design, data flow
5. **`backend-blueprint.md`** — Reusable backend shape (JDBC, security, patterns)
6. **`security.md`** — Auth flows, JWT, TOTP, OAuth2, sessions
7. **`database.md`** — Schema breakdown, JDBC patterns
8. **`api-reference.md`** — All REST endpoints
9. **`configuration.md`** — All env vars, profiles, settings
10. **`deployment.md`** — Cloud deployment shape

### New Flow Guides (in `documentation/flows/`)

- **00-anatomy-of-a-request.md** — How a request flows end-to-end
- **01-register-and-verify.md** — Email verification
- **02-login-and-mfa.md** — Login + TOTP/SMS
- **03-password-reset.md** — Forgot password flow
- **04-federated-oauth2.md** — Google/GitHub login
- **05-token-refresh-sessions.md** — Refresh token rotation
- **10-profile-and-account.md** — User profile CRUD
- **11-totp-enrollment.md** — Setting up authenticator app
- **12-sessions-and-devices.md** — Viewing & revoking sessions
- **20-admin-users-rbac.md** — Admin user management + RBAC
- **30-customers.md** — Customer CRUD
- **31-invoices.md** — Invoice lifecycle
- **32-dashboard.md` — Analytics & insights

---

## Testing Added

| Test | File | Purpose |
|------|------|---------|
| `UserControllerLoginEnumerationTest` | NEW | Security regression: prevents user-enumeration attacks |
| `GlobalExceptionHandlerTest` | NEW | Validates error response format |
| `CustomerServiceImplTest` | NEW | Customer CRUD behavior |
| `JpaSchemaSyncTest` | NEW | **CRITICAL**: Offline Hibernate drift detection (fails prod boot if schema doesn't match code) |

---

## How to Migrate from `master`

### ❌ DON'T Do This (What Your Teammate Did)

```bash
git checkout MastersProjectSRSImpl
# Run app against old master database
# Manually add missing columns
# Hope for the best
```

### ✅ DO This Instead

```bash
# 1. Backup old database (if needed)
mysqldump -u root -p db2 > db2_master_backup.sql

# 2. Reinitialize schema with new branch's schema.sql
mysql -u root -p db2 < src/main/resources/schema.sql

# 3. Update .env with new variables
cp .env.example .env
# Edit .env, set JWT_SECRET, etc.

# 4. Start the app (let Hibernate validate schema)
./start.sh

# 5. Check logs for "Hibernate validation passed" or similar
```

---

## Summary: What Your Teammate Missed

| Aspect | Status |
|--------|--------|
| Read documentation | ❌ No |
| Reviewed schema.sql changes | ❌ No |
| Ran schema initialization | ❌ No (manually patched instead) |
| Updated .env for new vars | ❌ No |
| Checked for new services | ❌ No |
| Reviewed breaking changes | ❌ No |
| Tested with full context | ❌ No (hacked database) |

**Result:** App "works" on hacked database, but is not production-ready or properly validated.

---

## See Also

- `branch-changelog.md` — Detailed commit-by-commit changelog
- `CLAUDE.md` — Backend blueprint & conventions
- `documentation/README.md` — Full documentation hub
- `documentation/getting-started.md` — Setup instructions
