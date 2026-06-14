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
