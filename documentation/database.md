# Database Guide

The complete data model: the two persistence mechanisms, how the schema is created, an entity-relationship map, a table-by-table reference, and the reference data (roles + audit events).

> **Database:** MySQL 8 · default schema name `db2`
> **See also:** [configuration.md](configuration.md) (datasource/env) · [architecture.md](architecture.md) (where the DB sits) · [security.md](security.md) (how the auth tables are used).

---

## Table of contents

1. [Two persistence mechanisms](#1-two-persistence-mechanisms)
2. [Initialising the database](#2-initialising-the-database)
3. [Entity-relationship map](#3-entity-relationship-map)
4. [Identity & access tables](#4-identity--access-tables)
5. [Audit tables](#5-audit-tables)
6. [Verification & SMS-2FA tables](#6-verification--sms-2fa-tables)
7. [Federated identity](#7-federated-identity)
8. [Organizations](#8-organizations)
9. [Authenticator (TOTP) MFA tables](#9-authenticator-totp-mfa-tables)
10. [Refresh-session tables](#10-refresh-session-tables)
11. [Business-domain tables (JPA)](#11-business-domain-tables-jpa)
12. [Reference data](#12-reference-data)
13. [Conventions, gotchas & history](#13-conventions-gotchas--history)
14. [TOTP recovery codes (detail)](#14-totp-recovery-codes-detail)
15. [Audit-event trigger reference](#15-audit-event-trigger-reference)
16. [Schema evolution & migration](#16-schema-evolution--migration)

---

## 1. Two persistence mechanisms

The application deliberately uses **two** data-access strategies, split by domain:

| Domain | Access | Schema owner | Tables |
|--------|--------|--------------|--------|
| **Identity / auth** | `JdbcTemplate` (hand-written SQL + row mappers) | **`schema.sql`** | `users`, `roles`, `userroles`, `events`, `userevents`, `accountverifications`, `resetpasswordverifications`, `twofactorverifications`, `oauthproviderlinks`, `organizations`, `userorganizations`, `totpcredentials`, `totprecoverycodes`, `mfachallenges`, `refreshsessions` |
| **Business domain** | JPA / Hibernate (`@Entity`) | **Hibernate** `ddl-auto: update` | `customer`, `invoice`, `services`, `invoiceserviceitems` |

Why two? The identity layer wants precise, auditable SQL and predictable column names (it predates and underpins security), so it uses `JdbcTemplate`. The CRUD-heavy business domain (customers/invoices) is a better fit for JPA's entity mapping. `User` itself is a **plain POJO** mapped by `UserRowMapper` — *not* a JPA entity.

---

## 2. Initialising the database

```bash
# 1. Create the schema
mysql -u root -p -e "CREATE DATABASE IF NOT EXISTS db2;"

# 2. Create the identity/auth tables + seed reference data (roles, events, orgs)
mysql -u root -p db2 < src/main/resources/schema.sql
```

- **`schema.sql` is idempotent and non-destructive** — `CREATE TABLE IF NOT EXISTS` + `INSERT ... ON DUPLICATE KEY UPDATE`, no `DROP`s. Safe to re-run.
- **The JPA tables are created automatically** by Hibernate (`ddl-auto: update`) the first time the app boots — you do not run anything for `customer`/`invoice`/`services`/`invoiceserviceitems`.
- `spring.sql.init.mode` is `never`, so `schema.sql` does **not** auto-run; you apply it once by hand (see [configuration.md](configuration.md)). It is safe to switch to `always` since it is idempotent.

> **History:** schema changes used to be applied by Flyway (`db/migration/V1..V6`). Flyway was removed (its baseline bookkeeping kept desyncing and blocking startup); `schema.sql` is now the single source of truth. See [§13](#13-conventions-gotchas--history).

---

## 3. Entity-relationship map

```
                         ┌──────────────────────────────────────────────┐
                         │                   users                      │
                         │  (id, email, password, enabled, non_locked,  │
                         │   using_mfa, using_totp, password_changed_at)│
                         └──────────────────────────────────────────────┘
                            │1     │1        │1..*      │*        │1..*  │1
        ┌───────────────────┘      │         │          │         │      └────────────┐
        │1 (UNIQUE user_id)        │1        │          │ (M:N)   │                   │
   ┌─────────┐   ┌──────────┐  ┌────────────────┐  ┌──────────────────┐  ┌──────────────────┐  ┌──────────────┐
   │userroles│   │ *verif.* │  │   userevents   │  │ userorganizations│  │ refreshsessions  │  │totpcredentials│
   └─────────┘   │ tables   │  └────────────────┘  └──────────────────┘  └──────────────────┘  │totprecovery..│
        │*       └──────────┘          │*                  │*                                    │mfachallenges │
        │1                             │1                  │1                                    └──────────────┘
   ┌─────────┐                    ┌──────────┐       ┌──────────────┐
   │  roles  │                    │  events  │       │organizations │       oauthproviderlinks ──*──1 users
   └─────────┘                    └──────────┘       └──────────────┘

   Business domain (JPA):   customer 1──* invoice 1──* invoiceserviceitems (element collection)
```

Notes:
- A user has **exactly one** role (`userroles` has `UNIQUE(user_id)`).
- A user has **at most one** row in each verification table and in `totpcredentials`/`mfachallenges` (all `UNIQUE(user_id)`), but **many** recovery codes, audit events, OAuth links, org memberships, and refresh sessions.

---

## 4. Identity & access tables

### `users`
The account record. Mapped by `UserRowMapper` to the `User` POJO.

| Column | Type | Notes |
|--------|------|-------|
| `id` | BIGINT UNSIGNED PK | auto-increment |
| `first_name`, `last_name` | VARCHAR(50) | required |
| `email` | VARCHAR(100) | `UNIQUE` — the login identifier |
| `password` | VARCHAR(255) | BCrypt hash (null for federated-only accounts) |
| `address`, `phone`, `title`, `bio` | VARCHAR | profile fields |
| `enabled` | BOOLEAN | account activated (email verified) |
| `non_locked` | BOOLEAN | not administratively locked |
| `using_mfa` | BOOLEAN | SMS-based 2FA enabled |
| `using_totp` | BOOLEAN | authenticator-app MFA enrolled (denormalized from `totpcredentials`) |
| `created_at` | DATETIME | defaults to `CURRENT_TIMESTAMP` |
| `password_changed_at` | DATETIME | tokens issued before this are rejected (see [security.md](security.md)) |
| `image_url` | VARCHAR(255) | avatar; defaults to a placeholder icon |

### `roles`
The seven-role catalog. One row per role; `permission` is a comma-separated list of `RESOURCE:ACTION` grants.

| Column | Type | Notes |
|--------|------|-------|
| `id` | BIGINT UNSIGNED PK | |
| `name` | VARCHAR(50) | `UNIQUE` (e.g. `ROLE_ADMIN`) |
| `permission` | VARCHAR(255) | e.g. `READ:USER, UPDATE:CUSTOMER` |

See the full catalog in [§12](#12-reference-data).

### `userroles`
Join table assigning a role to a user. `UNIQUE(user_id)` enforces **one role per user**. FK to `users` (`ON DELETE CASCADE`) and `roles` (`ON DELETE RESTRICT` — you can't delete a role still in use).

---

## 5. Audit tables

### `events`
Catalog of auditable event types, guarded by a `CHECK` constraint (`CK_Events_Type`) listing the 15 valid types. See [§12](#12-reference-data).

| Column | Type | Notes |
|--------|------|-------|
| `id` | BIGINT UNSIGNED PK | |
| `type` | VARCHAR(50) | `UNIQUE`, constrained by `CK_Events_Type` |
| `description` | VARCHAR(255) | human-readable text |

### `userevents`
The per-user audit log — one row each time a user triggers an event.

| Column | Type | Notes |
|--------|------|-------|
| `id` | BIGINT UNSIGNED PK | |
| `user_id` | FK → users | `ON DELETE CASCADE` |
| `event_id` | FK → events | `ON DELETE RESTRICT` |
| `device` | VARCHAR(100) | parsed User-Agent |
| `ip_address` | VARCHAR(100) | originating IP |
| `created_at` | DATETIME | event timestamp |

---

## 6. Verification & SMS-2FA tables

All three are one-row-per-user (`UNIQUE(user_id)`) and cascade-delete with the user. `url` holds a **bare UUID key** (not a full URL) — the app builds the clickable email link from it.

| Table | Purpose | Key columns |
|-------|---------|-------------|
| `accountverifications` | Email account-activation key | `user_id`, `url` (UNIQUE) |
| `resetpasswordverifications` | Password-reset key | `user_id`, `url` (UNIQUE), `expiration_date` |
| `twofactorverifications` | SMS 2FA login code | `user_id`, `code` (UNIQUE), `expiration_date` |

---

## 7. Federated identity

### `oauthproviderlinks`
Links a local user to an external identity provider. Per FR-FED-6 the system stores **only** the provider name and the provider's stable subject id — never a third-party credential.

| Column | Type | Notes |
|--------|------|-------|
| `id` | BIGINT UNSIGNED PK | |
| `user_id` | FK → users | `ON DELETE CASCADE` |
| `provider` | VARCHAR(30) | `google` / `github` / `microsoft` |
| `provider_subject` | VARCHAR(255) | the provider's stable subject id |
| `created_at` | DATETIME | |

`UNIQUE(provider, provider_subject)` is what makes federated "find-or-create" idempotent — the same external identity always resolves to the same local user.

---

## 8. Organizations

### `organizations`
The scoping unit for `ROLE_ORGANIZATION_ADMIN`. `status` (`CHECK` in `('ACTIVE','INACTIVE')`) lets an org be retired without deletion. Seeded with **`Tessera`** (demo org) and **`Acme Partners`** (to demonstrate the scope boundary).

### `userorganizations`
Many-to-many membership with an `active` flag. `UNIQUE(user_id, organization_id)`. The org-scope check honors only **active** memberships, so deactivating a row immediately removes a user from an org admin's reach without destroying history.

---

## 9. Authenticator (TOTP) MFA tables

| Table | Purpose | Notes |
|-------|---------|-------|
| `totpcredentials` | One Base32 RFC-6238 secret per user | `UNIQUE(user_id)`; `confirmed` flips true only after the user proves possession; an unconfirmed secret can never satisfy a login |
| `totprecoverycodes` | Single-use fallback codes | stored as SHA-256 hex (`code_hash CHAR(64)`); `used_at` marks consumption (never reset to null) |
| `mfachallenges` | Server-side proof the **first** factor succeeded | `UNIQUE(user_id)` + `UNIQUE(challenge)`; short-lived; the public TOTP-verify endpoint refuses any code not accompanied by a live challenge |

`mfachallenges` is the security linchpin for TOTP login: because a TOTP code always exists on the user's phone, a naked "verify TOTP" endpoint would let anyone with the authenticator skip the password. The login flow inserts a challenge only **after** first-factor success. See [security.md](security.md#totp-mfa).

---

## 10. Refresh-session tables

### `refreshsessions`
The **stateful** half of the hybrid token model — tracks refresh tokens so they can be rotated, listed, and revoked.

| Column | Type | Notes |
|--------|------|-------|
| `id` | BIGINT UNSIGNED PK | |
| `user_id` | FK → users | `ON DELETE CASCADE` |
| `family` | CHAR(36) | one logical session (one device login); stable across rotations — the unit the Security Center lists/revokes |
| `jti` | CHAR(36) | `UNIQUE`; one concrete refresh token; each refresh mints a new `jti` and supersedes the old |
| `device`, `ip_address` | VARCHAR(100) | for the device list |
| `created_at`, `last_used_at`, `expires_at` | DATETIME | `expires_at` bounds retention |
| `revoked`, `superseded` | BOOLEAN | rows are retained (not deleted) so reuse detection can recognise replayed old tokens |

Indexes: `IX_RefreshSessions_User_Id`, `IX_RefreshSessions_Family`. The full rotation + reuse-detection logic is in [security.md](security.md#refresh-session-rotation).

---

## 11. Business-domain tables (JPA)

These are **Hibernate-managed** (`ddl-auto: update`). Column names reflect the `globally_quoted_identifiers` behavior — explicitly mapped fields are snake_case, the rest stay camelCase (see [§13](#13-conventions-gotchas--history)).

### `customer`
| Column | Type | Notes |
|--------|------|-------|
| `id` | BIGINT PK | auto-increment |
| `customer_name` | VARCHAR(255) | explicitly `@Column(name="customer_name")` |
| `type`, `email`, `phoneNumber`, `address`, `status`, `imageUrl` | VARCHAR(255) | camelCase columns (Hibernate quoting) |
| `createdAt` | DATETIME(6) | |

One customer has many invoices (`@OneToMany`, eager).

### `invoice`
| Column | Type | Notes |
|--------|------|-------|
| `id` | BIGINT PK | |
| `invoiceNumber` | VARCHAR(255) | random alphanumeric reference |
| `amount`, `totalAmount` | DOUBLE | |
| `status` | VARCHAR(255) | Pending / Paid / Overdue |
| `customerId` | BIGINT | denormalized FK, for direct queries |
| `customer` | BIGINT (`MUL`) | JPA `@ManyToOne` join column → `customer.id` |
| `invoiceDate` | DATETIME(6) | |

> Legacy columns `service` and `services` (VARCHAR) also exist on this table from earlier mappings; line items now live in `invoiceserviceitems`. They're harmless leftovers (Hibernate `update` never drops columns).

### `services`
Reference table of service offerings: `id`, `name`, `description`, `price` (DOUBLE). Named `Services` because `Service` collides with a Spring stereotype.

### `invoiceserviceitems`
The `@ElementCollection` table for an invoice's line items — **no surrogate `id`** (Hibernate-managed):

| Column | Type | Notes |
|--------|------|-------|
| `invoice_id` | BIGINT (`MUL`) | owning invoice |
| `item_order` | INT | list position (`@OrderColumn`) |
| `name` | VARCHAR(255) | service name |
| `price` | DOUBLE | line price |

---

## 12. Reference data

### Role catalog (seeded by `schema.sql`)

| Role | Permissions |
|------|-------------|
| `ROLE_GUEST` | `READ:USER` |
| `ROLE_USER` | `READ:USER, READ:CUSTOMER` |
| `ROLE_MODERATOR` | `READ:USER, READ:CUSTOMER, UPDATE:CUSTOMER` |
| `ROLE_HELP_DESK_ADMIN` | `READ:USER, READ:CUSTOMER, UPDATE:USER` |
| `ROLE_ORGANIZATION_ADMIN` | `READ:USER, READ:CUSTOMER, UPDATE:USER, UPDATE:ROLE` |
| `ROLE_ADMIN` | `READ/CREATE/UPDATE:USER+CUSTOMER, UPDATE:ROLE, DELETE:USER` |
| `ROLE_APPLICATION_ADMIN` | all of the above **+ `DELETE:CUSTOMER`** (full access) |

How permissions map to endpoints is in [security.md](security.md#rbac) and [api-reference.md](api-reference.md).

### Event catalog (the 15 `CK_Events_Type` values)

`LOGIN_ATTEMPT`, `LOGIN_ATTEMPT_SUCCESS`, `LOGIN_ATTEMPT_FAILURE`, `PROFILE_UPDATE`, `PROFILE_PICTURE_UPDATE`, `ROLE_UPDATE`, `ACCOUNT_SETTINGS_UPDATE`, `PASSWORD_UPDATE`, `MFA_UPDATE`, `FEDERATED_LOGIN`, `TOTP_ENROLLED`, `TOTP_DISABLED`, `RECOVERY_CODE_USED`, `SESSION_REVOKED`, `TOKEN_REUSE_DETECTED`.

> Adding a new event type means updating **both** the `CHECK` constraint and the seed `INSERT` in `schema.sql`.

---

## 13. Conventions, gotchas & history

- **Naming.** Identity tables (`schema.sql`) use clean `snake_case`. JPA tables show mixed casing because `spring.jpa.properties.hibernate.globally_quoted_identifiers: true` suppresses the snake_case strategy — Hibernate creates literal `phoneNumber`/`imageUrl` columns. **Always add `@Column(name="…")`** on entity fields you want in snake_case (as `Customer.customerName` does).
- **One role per user.** Enforced by `UNIQUE(user_id)` on `userroles`. Role changes go through `PATCH /admin/user/{id}/role/{roleName}`.
- **Retention vs deletion.** `refreshsessions` rows are kept after revocation/supersession (reuse detection needs them); `expires_at` bounds the window. Recovery codes are marked `used_at`, never deleted.
- **`using_mfa` vs `using_totp`.** Two independent second factors: `using_mfa` = SMS code path (stubbed in dev); `using_totp` = authenticator app (fully implemented). `using_totp` is denormalized from `totpcredentials` for fast row mapping.
- **Schema history.** A Flyway migration set (`V1..V6`) previously owned the schema; it was removed after repeated baseline desyncs blocked startup. The cumulative result of those migrations is now baked into `schema.sql`. Do not reintroduce a migration tool without revisiting this guide.
- **Demo data.** On the `dev` profile, `DemoDataSeeder` inserts one user per role (password `TesseraDemo@1`) and a few sample audit events — idempotent, and never runs under `prod`.

---

## 14. TOTP recovery codes (detail)

Expands [§9](#9-authenticator-totp-mfa-tables) (`totprecoverycodes`) with the lifecycle the table only hints at. Recovery codes are the **break-glass** fallback for authenticator-app MFA: if the user loses their phone, one of these single-use codes satisfies a login challenge in place of a live TOTP code.

> **Key source files:** `service/serviceimpl/TotpServiceImpl.java` · `utils/TotpUtils.java` · `query/TotpQuery.java` · `controller/TotpController.java`
> **Code wins:** every number below is taken from the source cited beside it. If this section and the code ever disagree, **the code wins** and this section should be fixed.

| Property | Value | Source |
|----------|-------|--------|
| Count per batch | **10** codes | `RECOVERY_CODE_COUNT = 10` — `TotpServiceImpl.java:54` |
| Format | `XXXXX-XXXXX` (two groups of 5, single hyphen) | `TotpUtils.generateRecoveryCode()` — `TotpUtils.java:176-183` |
| Alphabet | RFC 4648 Base32 `A–Z2–7` (no ambiguous `0/1/8/9`) | `BASE32_ALPHABET` — `TotpUtils.java:50` |
| Entropy | 10 chars × 5 bits = **~50 bits** per code | docstring — `TotpUtils.java:170-174` |
| At-rest form | **SHA-256 hex** in `code_hash CHAR(64)` (lowercase) | `TotpUtils.sha256Hex()` — `TotpUtils.java:192-199`; column — `schema.sql:283` |
| Single-use | atomic burn — `UPDATE … SET used_at = NOW() … WHERE used_at IS NULL` | `CONSUME_RECOVERY_CODE_QUERY` — `TotpQuery.java:78-80` |

### Why SHA-256 and not BCrypt

Recovery codes are machine-generated with ~50 bits of entropy, so they are **not** brute-forceable the way a low-entropy human password is. Fast SHA-256 is therefore the right at-rest hash; BCrypt's deliberate slowness would buy nothing here and would slow the per-login verification path (`TotpUtils.java:170-174`). The same reasoning is why passwords (low entropy) still use BCrypt — see [security.md](security.md).

### Normalization (typed-vs-stored)

Users see the hyphenated, upper-case form but type it inconsistently. Both **issuance** and **verification** run the identical normalizer — strip whitespace and the display hyphen, upper-case — *before* hashing, so the digests line up regardless of how the user enters it:

```java
// TotpServiceImpl.java:222-224
private static String normalizeRecoveryCode(String code) {
    return code.replaceAll("[\\s-]", "").toUpperCase();
}
```

Applied at issuance (`TotpServiceImpl.java:200`) and at consumption (`TotpServiceImpl.java:213`).

### Single-use semantics

`consumeRecoveryCode()` (`TotpServiceImpl.java:210-215`) issues one `UPDATE` whose **affected-row count is the verdict**: `1` = a matching, still-unused code was just burned; `0` = unknown hash or already used. Because the `used_at IS NULL` predicate and the `SET used_at = NOW()` happen in a single statement, two concurrent attempts can never double-spend the same code. A consumed row is **never deleted and never reset to NULL** — `used_at` is the permanent audit trail of which code was spent (mirrors the retention rule in [§13](#13-conventions-gotchas--history)).

### Issuance, plaintext, and regeneration

| Step | What happens | Source |
|------|--------------|--------|
| Issued | Only inside `confirmEnrollment()` — after the user echoes a valid code for the pending secret | `TotpServiceImpl.java:106` → `issueRecoveryCodes()` `:193-203` |
| Replace-on-issue | `issueRecoveryCodes()` first `DELETE`s every existing code for the user, then inserts 10 fresh hashes | `DELETE_RECOVERY_CODES_BY_USER_ID_QUERY` — `TotpQuery.java:63-64` |
| Plaintext exposure | Returned to the SPA **exactly once** in the enable response; only the hash is persisted | `TotpController.java:109-116` ("they will not be shown again") |
| Remaining count | `countUnusedRecoveryCodes()` surfaces the unused tally to the Account Security Center so a user can see they are running low | `TotpServiceImpl.java:184-187`; status endpoint `TotpController.java:161` |
| Cleared on disable | `disableTotp()` removes the credential **and** all recovery codes in one transaction | `TotpServiceImpl.java:128` |

> **⚠️ Gotcha — there is no standalone "regenerate codes" endpoint.** A fresh batch is minted **only** by a (re)enrollment confirmation. `TotpController` exposes setup / enable / disable / status / verify — but no "regenerate". To replace a depleted set today, the user must **disable and re-enroll** TOTP (which deletes the old codes on disable, then issues a new 10 on the next confirm). A dedicated "regenerate recovery codes" action that reissues without a full re-enroll is a sensible future addition; the underlying `issueRecoveryCodes()` already does the delete-then-insert it would need.

---

## 15. Audit-event trigger reference

[§5](#5-audit-tables) covers the audit *tables*; [§12](#12-reference-data) lists the 15 valid `type` strings. This section maps **each event type → when it fires → what context is recorded**, so you can read a `userevents` row back to the code path that wrote it.

> **One pipe for every event.** Controllers (and one service) publish a `NewUserEvent(email, EventType)` via `ApplicationEventPublisher`; `NewUserEventListener.onNewUserEvent()` (`listener/NewUserEventListener.java:42-46`) is the single sink. It enriches every event uniformly from the **live HTTP request** — there is no per-call-site context plumbing.

### Context captured on every row

| `userevents` column | Source at write time | Notes |
|---------------------|----------------------|-------|
| user (`user_id`) | resolved from `NewUserEvent.email` by `EventService.addUserEvent` | events are **keyed by email**, not id (`NewUserEvent.java:33`) |
| `device` | `RequestUtils.getDevice(request)` — parsed `User-Agent` | injected `HttpServletRequest` (`NewUserEventListener.java:31,45`) |
| `ip_address` | `RequestUtils.getIpAddress(request)` — originating IP | same request |
| `created_at` | DB default `CURRENT_TIMESTAMP` | see [§5](#5-audit-tables) |

### Trigger map (all 15 types)

| Event type | Fires when | Published from (`file:line`) |
|------------|-----------|------------------------------|
| `LOGIN_ATTEMPT` | Start of a login attempt, before success/failure is known | `UserController.java:673` |
| `LOGIN_ATTEMPT_SUCCESS` | Password auth succeeds and lock/enable checks pass; also after a TOTP challenge completes login | `UserController.java:159`, `:678` · `TotpController.java:190` |
| `LOGIN_ATTEMPT_FAILURE` | Bad credentials, or account locked/disabled (feeds the 5-in-15-min lockout) | `UserController.java:170`, `:726` |
| `PROFILE_UPDATE` | User saves name/email/bio/profile fields | `UserController.java:504` |
| `PROFILE_PICTURE_UPDATE` | User uploads a new avatar | `UserController.java:334` |
| `ROLE_UPDATE` | An **admin** reassigns a target user's role (`PATCH /admin/user/{id}/role/{roleName}`) | `AdminUserController.java:219` |
| `ACCOUNT_SETTINGS_UPDATE` | `enabled`/`non_locked` toggled — self-service or admin-on-other-user | `UserController.java:268` · `AdminUserController.java:253` |
| `PASSWORD_UPDATE` | User changes their password | `UserController.java:224` |
| `MFA_UPDATE` | SMS 2FA (`using_mfa`) toggled | `UserController.java:296` |
| `FEDERATED_LOGIN` | Federated sign-in completes and the success handler mints app JWTs | `OAuth2LoginSuccessHandler.java:130` |
| `TOTP_ENROLLED` | Authenticator enrollment is confirmed (secret confirmed + recovery codes issued) | `TotpController.java:110` |
| `TOTP_DISABLED` | Authenticator removed after proving possession | `TotpController.java:135` |
| `RECOVERY_CODE_USED` | A single-use recovery code (not a live TOTP code) satisfies the login challenge | `TotpController.java:188` |
| `SESSION_REVOKED` | User revokes one session, or "log out everywhere" | `SessionController.java:89` (one), `:113` (others/all) |
| `TOKEN_REUSE_DETECTED` | A rotated/revoked refresh token is replayed; the whole session family is revoked | `SessionServiceImpl.java:188` (`handleReuse`) |

> **Gotcha — context follows the request in flight, not the account owner.** Because the listener reads the *current* `HttpServletRequest`, the `device`/`ip_address` on `TOKEN_REUSE_DETECTED` reflect **whoever presented the replayed token** (the reuse handler runs inside that refresh request, `SessionServiceImpl.java:183-195`), which is exactly what you want for forensics. Auditing there is wrapped in try/catch so a logging failure never blocks the security response (`:190-194`).

> **History:** adding a new event type means updating **both** the `CK_Events_Type` `CHECK` constraint and the seed `INSERT` in `schema.sql`, then adding the enum constant — see [§16.3](#163-adding-an-event-type) and [§12](#12-reference-data).

---

## 16. Schema evolution & migration

How to change the schema now that **Flyway is gone** (removed because its baseline bookkeeping kept desyncing from the live DB and blocking startup — `schema.sql:4-8`). There is no migration tool and no auto-versioning; the schema has **two owners**, and which one you touch depends on the table.

| Owner | Tables | How DDL is applied | Config |
|-------|--------|--------------------|--------|
| **`schema.sql`** (hand-applied) | all identity/auth tables ([§4](#4-identity--access-tables)–[§10](#10-refresh-session-tables)) | idempotent script, run by hand | `spring.sql.init.mode: never` — `application.yml:52` |
| **Hibernate** | `customer`, `invoice`, `services`, `invoiceserviceitems` | `ddl-auto: update` (dev) / `validate` (prod) | `application.yml:31` · `application-prod.yml:31` |

`schema.sql` is **idempotent and non-destructive**: every statement is `CREATE TABLE IF NOT EXISTS` or `INSERT … ON DUPLICATE KEY UPDATE`, FKs are inlined (not separate `ALTER`s) to stay re-runnable, and there are **deliberately no `DROP`s** (`schema.sql:10-12,173`).

### 16.1 Adding a column or table to an identity/auth table

1. **Edit `schema.sql`.** For a **new table**, add a `CREATE TABLE IF NOT EXISTS …` block (inline its FKs, give constraints stable names like `UQ_…`/`IX_…` as the existing blocks do). For a **new column**, add it to the table's existing `CREATE` block so a *fresh* database is always correct.
2. **Apply to existing databases by hand.** ⚠️ `CREATE TABLE IF NOT EXISTS` will **not** alter a table that already exists, and MySQL 8 has **no `ADD COLUMN IF NOT EXISTS`**. So on an already-initialised DB you must run the delta once yourself:
   ```sql
   ALTER TABLE users ADD COLUMN new_flag BOOLEAN DEFAULT FALSE;
   ```
   Keep the column in the `CREATE` block (step 1) **and** run the one-off `ALTER` on each live DB — fresh installs get it from the block, existing installs from the `ALTER`.
3. **Wire the read/write path.** Add a named-param SQL constant in the matching `*Query` class, update the `*RowMapper`, and surface it through the repo/service (the [§1](#1-two-persistence-mechanisms) JDBC pattern). For a brand-new aggregate, add the four cooperating pieces (`Query` + `RowMapper` + `Repo` + `RepoImpl`).
4. **Re-running `schema.sql` is always safe** — idempotent, no `DROP`s, so it never clobbers data.

### 16.2 Adding or altering a JPA entity (and the drift guard)

The business-domain tables are Hibernate-managed, so in **dev** (`ddl-auto: update`) adding a field to `Customer`/`Invoice`/`Services`/`InvoiceLineItem` creates the column automatically on next boot. But **prod runs `ddl-auto: validate`** (`application-prod.yml:31`): Hibernate refuses to start if a mapped column is missing from the hand-applied schema. So a JPA change is **not done** until `schema.sql` carries the generated DDL too.

`JpaSchemaSyncTest` (`src/test/java/.../tooling/JpaSchemaSyncTest.java`) is the build-time guard and the reproducible DDL source:

1. It drives Hibernate's **offline** schema export (no DB connection) with the MySQL dialect and `globally_quoted_identifiers=true` pinned, writing `target/generated-jpa-schema.sql`.
2. It asserts `schema.sql` contains **every** backtick-quoted table/column Hibernate maps — so a new entity field without a matching `schema.sql` update **fails the build here**, not at the next prod deploy.

Workflow after changing an entity: run the test, open `target/generated-jpa-schema.sql`, copy the new `CREATE TABLE`/column into `schema.sql` as `CREATE TABLE IF NOT EXISTS` with FKs inlined and stable names (the column names are quoted camelCase because of `globally_quoted_identifiers` — see [§13](#13-conventions-gotchas--history)), then re-run the test until green.

### 16.3 Adding an event type

A new audit event touches **three** places (the `CHECK` constraint will reject an unknown `type` otherwise):

1. `schema.sql` — extend the `CK_Events_Type` `CHECK` list **and** add the seed `INSERT … ON DUPLICATE KEY UPDATE` row.
2. `enumeration/EventType.java` — add the constant with its user-facing description.
3. Publish it from the relevant code path (`new NewUserEvent(email, NEW_TYPE)`) — see the trigger map in [§15](#15-audit-event-trigger-reference).

### 16.4 Rollback

There is **no down-migration mechanism** — that is the deliberate trade-off of dropping Flyway. Roll back the same way you roll forward: **by hand, code-first.**

| To undo | Do this | Note |
|---------|---------|------|
| A bad column add | Write a compensating `ALTER TABLE … DROP COLUMN` and run it manually | Not added to `schema.sql` (it has no `DROP`s); keep it in a one-off ops note |
| A bad table add | `DROP TABLE` manually after confirming it is unused | Same — never goes in `schema.sql` |
| A JPA change | Revert the entity + the `schema.sql` DDL together; in prod, `validate` then re-checks them in lockstep | `update` never drops columns, so a removed field leaves a harmless orphan column (cf. the `service`/`services` leftovers, [§11](#11-business-domain-tables-jpa)) |
| A data seed | Re-run `schema.sql` (idempotent) after editing the seed `INSERT` | `ON DUPLICATE KEY UPDATE` reconciles existing rows |

> **Note — back up first.** Because rollback is manual SQL against a live database, take a dump before applying any destructive `ALTER`/`DROP`, and (per project rule) confirm any destructive DB operation with a human before running it.
