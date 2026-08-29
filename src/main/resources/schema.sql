-- =====================================================================================
-- TesseraApp — full application schema (single source of truth).
--
-- Replaces the former Flyway migration set (db/migration/V1..V6), which was removed because
-- Flyway's baseline bookkeeping repeatedly desynced from the live database and blocked
-- startup. This file is now the canonical, idempotent definition of every table the
-- application manages directly via JdbcTemplate: the user-management/auth core plus the
-- identity & security features (federation, organizations, TOTP MFA, refresh sessions).
--
-- IDEMPOTENT & NON-DESTRUCTIVE: every statement uses CREATE TABLE IF NOT EXISTS and
-- INSERT ... ON DUPLICATE KEY UPDATE, so running it repeatedly never drops data and never
-- errors on an already-initialised database. There are deliberately NO DROP statements.
--
-- WHEN IT RUNS (see spring.sql.init.mode in application.yml):
--   * mode: never  (default) — does NOT run on startup; run it by hand once to initialise a
--                   brand-new database:  mysql -u root -p db2 < src/main/resources/schema.sql
--   * mode: always — Spring Boot applies it on every startup (safe, because it is idempotent).
--
-- NOTE: the Customer / Invoice / Services / invoiceserviceitems tables ARE defined here (see the
-- "JPA-managed domain" section below). They back JPA @Entity classes, so their DDL was produced by
-- Hibernate's own schema export — using the app's dialect and globally_quoted_identifiers=true (see
-- the test com.bob...tooling.JpaSchemaSyncTest) — so it matches EXACTLY what
-- spring.jpa.hibernate.ddl-auto: validate expects in production (quoted camelCase identifiers and
-- all). Do NOT hand-edit those column names or types: a mismatch fails the prod boot.
-- =====================================================================================

-- PORTABLE BY DESIGN: no CREATE SCHEMA / USE here on purpose. Every statement below targets
-- whichever database is ACTIVE on the connection that runs this script, so the SAME file
-- initialises the local `db2` OR a cloud database such as Aiven `db3` — no hardcoded name to
-- accidentally build tables in the wrong schema. Select the target before running:
--   * CLI:  mysql -u <user> -p db2 < schema.sql      (the db-name argument = the active schema)
--   * GUI:  make the database the active/default schema, then execute.
-- A brand-new database must be created once, up front:  CREATE DATABASE db2;   (or db3, …).

SET NAMES 'UTF8MB4';

-- ── Core: users ───────────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS users
(
    id                  BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
    first_name          VARCHAR(50)  NOT NULL,
    last_name           VARCHAR(50)  NOT NULL,
    email               VARCHAR(100) NOT NULL,
    password            VARCHAR(255) DEFAULT NULL,
    address             VARCHAR(255) DEFAULT NULL,
    phone               VARCHAR(30)  DEFAULT NULL,
    title               VARCHAR(50)  DEFAULT NULL,
    bio                 VARCHAR(255) DEFAULT NULL,
    enabled             BOOLEAN      DEFAULT FALSE,
    non_locked          BOOLEAN      DEFAULT TRUE,
    using_mfa           BOOLEAN      DEFAULT FALSE,
    using_totp          BOOLEAN      DEFAULT FALSE,
    created_at          DATETIME     DEFAULT CURRENT_TIMESTAMP,
    password_changed_at DATETIME     DEFAULT NULL,
    image_url           VARCHAR(255) DEFAULT 'https://cdn-icons-png.flaticon.com/512/149/149071.png',
    CONSTRAINT UQ_Users_Email UNIQUE (email)
);

-- ── Core: roles (seven-role catalog, SRS §2.3) ─────────────────────────────────────────
CREATE TABLE IF NOT EXISTS roles
(
    id         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
    name       VARCHAR(50)  NOT NULL,
    permission VARCHAR(255) NOT NULL,
    CONSTRAINT UQ_Roles_Name UNIQUE (name)
);

-- Role ids are PINNED, not auto-assigned.
--
-- Without explicit ids this seed drifts: `INSERT ... ON DUPLICATE KEY UPDATE` consumes an
-- AUTO_INCREMENT value for every row it touches, including the rows it merely *updates*. Because
-- this file is idempotent and meant to be re-run, each run burned another 7 ids — which is why a
-- database seeded five times shows roles numbered in the 30s rather than 1–7.
--
-- That is not merely untidy. `userroles.role_id` is a real foreign key, so the numbers are load
-- bearing *within* a database, and two databases seeded a different number of times disagree about
-- which id means which role. Today nothing breaks, because every assignment path resolves the role
-- by NAME first (`SELECT_ROLE_BY_NAME_QUERY`) and only then stores the id it found. But it means a
-- dump restored from one environment into another, or a row compared across `db2` and `db3`, would
-- silently attach people to the wrong role.
--
-- Pinning the ids makes the mapping identical in every environment and stops the drift, because an
-- explicit id below the current counter allocates nothing.
--
-- NOTE for existing databases: this does NOT renumber roles that are already there. The unique key
-- is `name`, so on a seeded database each row still matches on name and only its permission column
-- is updated — the drifted id is left exactly as it is, and the foreign keys pointing at it stay
-- valid. Fresh databases (and CI) get 1–7. Renumbering an existing database would mean updating
-- `roles.id` and every `userroles.role_id` together, which is a deliberate migration and not
-- something an idempotent seed should do behind your back.
-- 2026-08-21: UPDATE:ORGANIZATION added to the same three tiers already holding UPDATE:ROLE
-- (organization admin, admin, application admin) — see OrganizationQuery/OrganizationController
-- for the Organization CRUD + membership-management feature it gates. Deliberately mirrors the
-- UPDATE:ROLE distribution rather than inventing a new tier list: those are exactly the tiers
-- that need some level of organization access (self-service org CRUD for the top two, own-org
-- membership management for ROLE_ORGANIZATION_ADMIN); help desk and below get nothing new.
INSERT INTO roles (id, name, permission)
VALUES (1, 'ROLE_GUEST', 'READ:USER'),
       (2, 'ROLE_USER', 'READ:USER, READ:CUSTOMER'),
       (3, 'ROLE_MODERATOR', 'READ:USER, READ:CUSTOMER, UPDATE:CUSTOMER'),
       (4, 'ROLE_HELP_DESK_ADMIN', 'READ:USER, READ:CUSTOMER, UPDATE:USER'),
       (5, 'ROLE_ORGANIZATION_ADMIN', 'READ:USER, READ:CUSTOMER, UPDATE:USER, UPDATE:ROLE, UPDATE:ORGANIZATION'),
       (6, 'ROLE_ADMIN',
        'READ:USER, READ:CUSTOMER, CREATE:USER, CREATE:CUSTOMER, UPDATE:USER, UPDATE:CUSTOMER, UPDATE:ROLE, UPDATE:ORGANIZATION, DELETE:USER'),
       (7, 'ROLE_APPLICATION_ADMIN',
        'READ:USER, READ:CUSTOMER, CREATE:USER, CREATE:CUSTOMER, UPDATE:USER, UPDATE:CUSTOMER, UPDATE:ROLE, UPDATE:ORGANIZATION, DELETE:USER, DELETE:CUSTOMER') AS new
ON DUPLICATE KEY UPDATE permission = new.permission;

CREATE TABLE IF NOT EXISTS userroles
(
    id      BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT UNSIGNED NOT NULL,
    role_id BIGINT UNSIGNED NOT NULL,
    FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE ON UPDATE CASCADE,
    FOREIGN KEY (role_id) REFERENCES roles (id) ON DELETE RESTRICT ON UPDATE CASCADE,
    CONSTRAINT UQ_UserRoles_User_Id UNIQUE (user_id)
);

-- Idempotent add of userroles.expires_at (POST-SUBMISSION-UPGRADES.md, time-boxed role
-- assignment). NULL means the assignment never expires (the default for every existing row
-- and for ordinary role reassignments); a non-NULL timestamp is enforced live by
-- RoleRepoImpl#getRoleByUserId on every role lookup — the moment it is in the past, that
-- method auto-reverts the user to ROLE_USER and clears this column, rather than a scheduled
-- sweep job checking on a timer. Same information_schema guard as every other idempotent
-- ALTER in this file, since MySQL has no ADD COLUMN IF NOT EXISTS.
SET @add_userroles_expires_at := (
    SELECT IF(COUNT(*) = 0,
        'ALTER TABLE userroles ADD COLUMN expires_at TIMESTAMP NULL DEFAULT NULL AFTER role_id',
        'DO 0')
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'userroles' AND COLUMN_NAME = 'expires_at');
PREPARE add_userroles_expires_at_stmt FROM @add_userroles_expires_at;
EXECUTE add_userroles_expires_at_stmt;
DEALLOCATE PREPARE add_userroles_expires_at_stmt;

-- Favorites / pinned destinations bar (FUTURE-ENHANCEMENTS.md §3.3). destination_id is one of the
-- command palette's navigable destination ids (e.g. 'customers', 'billing') — an id the frontend
-- registry owns and validates; the backend deliberately treats it as an opaque string rather than
-- re-encoding the palette's route list here, which would be the exact two-source-of-truth problem
-- this feature was designed to avoid. Composite PK doubles as the "already pinned" uniqueness
-- guard, so INSERT IGNORE is naturally idempotent with no separate existence check.
CREATE TABLE IF NOT EXISTS userfavorites
(
    user_id        BIGINT UNSIGNED NOT NULL,
    destination_id VARCHAR(64)     NOT NULL,
    created_at     TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (user_id, destination_id),
    FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE ON UPDATE CASCADE
);

-- ── Audit: events catalog + per-user log ───────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS events
(
    id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
    type        VARCHAR(50)  NOT NULL,
    description VARCHAR(255) NOT NULL,
    CONSTRAINT UQ_Events_Type UNIQUE (type)
);

-- events.type is guarded by a CHECK, but the valid-type set GROWS over time (TOTP, sessions,
-- federation, token-reuse …). A CHECK baked into CREATE TABLE can't migrate: on a database
-- created before a new type shipped, CREATE TABLE IF NOT EXISTS is a no-op, the OLD CHECK
-- survives, and the new type is rejected on INSERT (MySQL error 3819 — the exact failure seen
-- applying this against a pre-existing Aiven db3). So the CHECK is (re)applied idempotently here:
-- drop whatever CHECK currently guards events.type — its name drifts across databases
-- ('CK_Events_Type' on fresh installs, auto-named 'events_chk_1' on older ones) — then add the
-- current definition. Existing rows are always a subset of the new set, so revalidation passes.
SET @events_chk := (SELECT CONSTRAINT_NAME FROM information_schema.TABLE_CONSTRAINTS
                    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'events'
                      AND CONSTRAINT_TYPE = 'CHECK' LIMIT 1);
SET @drop_events_chk := IF(@events_chk IS NULL, 'DO 0',
                           CONCAT('ALTER TABLE events DROP CHECK `', @events_chk, '`'));
PREPARE drop_events_chk_stmt FROM @drop_events_chk;
EXECUTE drop_events_chk_stmt;
DEALLOCATE PREPARE drop_events_chk_stmt;

-- 2026-08-22: ten ORG_* types added for the organization-level audit trail
-- (organizationevents, below) — mirrors the per-user audit trail exactly, sharing this same
-- catalog rather than a second CHECK-guarded type table.
ALTER TABLE events ADD CONSTRAINT CK_Events_Type CHECK (type IN
    ('LOGIN_ATTEMPT', 'LOGIN_ATTEMPT_FAILURE', 'LOGIN_ATTEMPT_SUCCESS',
     'PROFILE_UPDATE', 'PROFILE_PICTURE_UPDATE', 'ROLE_UPDATE',
     'ACCOUNT_SETTINGS_UPDATE', 'PASSWORD_UPDATE', 'MFA_UPDATE',
     'FEDERATED_LOGIN',
     'TOTP_ENROLLED', 'TOTP_DISABLED', 'RECOVERY_CODE_USED',
     'SESSION_REVOKED', 'TOKEN_REUSE_DETECTED',
     'SUSPICIOUS_LOGIN', 'PROVIDER_LINKED', 'PROVIDER_UNLINKED',
     'PASSKEY_REGISTERED', 'PASSKEY_REMOVED', 'PASSKEY_LOGIN',
     'MFA_RESET', 'RECOVERY_CODES_REGENERATED',
     'ORG_CREATED', 'ORG_RENAMED', 'ORG_STATUS_CHANGED', 'ORG_PROFILE_UPDATED',
     'ORG_MEMBER_ADDED', 'ORG_MEMBER_REMOVED', 'ORG_MEMBER_ROLE_CHANGED',
     'ORG_INVITE_CREATED', 'ORG_INVITE_REDEEMED', 'ORG_INVITE_REVOKED',
     'ORG_SETTINGS_UPDATED', 'ORG_TENANT_UUID_SET', 'ORG_CUSTOMERS_ASSIGNED',
     'ORG_SSO_CONFIGURED', 'ORG_SSO_REMOVED', 'ORG_SSO_DOMAIN_ADDED', 'ORG_SSO_DOMAIN_REMOVED'));

INSERT INTO events (type, description)
VALUES ('LOGIN_ATTEMPT', 'You tried to log-in :)'),
       ('LOGIN_ATTEMPT_SUCCESS', 'You attempted to log-in and you succeeded :)'),
       ('LOGIN_ATTEMPT_FAILURE', 'You tried to log-in, but you failed to do so :('),
       ('PROFILE_UPDATE', 'You have updated your profile information :)'),
       ('PROFILE_PICTURE_UPDATE', 'You have updated your profile picture :)'),
       ('ROLE_UPDATE', 'You have updated your role and permissions :)'),
       ('ACCOUNT_SETTINGS_UPDATE', 'You have updated your account settings :)'),
       ('PASSWORD_UPDATE', 'You have updated your password successfully :)'),
       ('MFA_UPDATE', 'You have updated your multi-factor authentication settings :)'),
       ('FEDERATED_LOGIN', 'You logged in with a federated identity provider :)'),
       ('TOTP_ENROLLED', 'You enrolled an authenticator app for multi-factor authentication :)'),
       ('TOTP_DISABLED', 'You removed your authenticator app from multi-factor authentication :)'),
       ('RECOVERY_CODE_USED', 'You signed in using a single-use recovery code :)'),
       ('SESSION_REVOKED', 'You revoked an active session on your account :)'),
       ('TOKEN_REUSE_DETECTED', 'A previously used refresh token was replayed; the affected session family was revoked for your security :|'),
       ('SUSPICIOUS_LOGIN', 'We noticed a sign-in that didn''t match your usual device or location, so we asked for extra verification :|'),
       ('PROVIDER_LINKED', 'You connected an identity provider to your account :)'),
       ('PROVIDER_UNLINKED', 'You disconnected an identity provider from your account :|'),
       ('PASSKEY_REGISTERED', 'You registered a new passkey for signing in :)'),
       ('PASSKEY_REMOVED', 'You removed a passkey from your account :|'),
       ('PASSKEY_LOGIN', 'You signed in with a passkey :)'),
       ('MFA_RESET', 'An administrator reset your authenticator MFA :)'),
       ('RECOVERY_CODES_REGENERATED', 'You regenerated your recovery codes :)'),
       ('ORG_CREATED', 'A new organization was created :)'),
       ('ORG_RENAMED', 'The organization was renamed :)'),
       ('ORG_STATUS_CHANGED', 'The organization''s status was changed :|'),
       ('ORG_PROFILE_UPDATED', 'The organization''s profile was updated :)'),
       ('ORG_MEMBER_ADDED', 'A member was added to the organization :)'),
       ('ORG_MEMBER_REMOVED', 'A member was removed from the organization :|'),
       ('ORG_MEMBER_ROLE_CHANGED', 'A member''s role within the organization was changed :)'),
       ('ORG_INVITE_CREATED', 'An invite link was created for the organization :)'),
       ('ORG_INVITE_REDEEMED', 'An invite link was redeemed to join the organization :)'),
       ('ORG_INVITE_REVOKED', 'An invite link for the organization was revoked :|'),
       ('ORG_SETTINGS_UPDATED', 'The organization''s settings were updated :)'),
       ('ORG_TENANT_UUID_SET', 'The organization''s tenant UUID was set :)'),
       ('ORG_CUSTOMERS_ASSIGNED', 'Customers were attached to the organization :)'),
       ('ORG_SSO_CONFIGURED', 'The organization''s single sign-on provider was configured :)'),
       ('ORG_SSO_REMOVED', 'The organization''s single sign-on provider was removed :|'),
       ('ORG_SSO_DOMAIN_ADDED', 'An email domain was added to the organization''s single sign-on routing :)'),
       ('ORG_SSO_DOMAIN_REMOVED', 'An email domain was removed from the organization''s single sign-on routing :|') AS new
ON DUPLICATE KEY UPDATE description = new.description;

CREATE TABLE IF NOT EXISTS userevents
(
    id         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
    user_id    BIGINT UNSIGNED NOT NULL,
    event_id   BIGINT UNSIGNED NOT NULL,
    device     VARCHAR(100) DEFAULT NULL,
    ip_address VARCHAR(100) DEFAULT NULL,
    -- Optional free-form context for an audit row (FR-FED-5): e.g. the federated provider
    -- name ('google' | 'github' | 'microsoft') on a FEDERATED_LOGIN event. NULL for events
    -- that need no extra detail.
    detail     VARCHAR(255) DEFAULT NULL,
    created_at DATETIME     DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE ON UPDATE CASCADE,
    FOREIGN KEY (event_id) REFERENCES events (id) ON DELETE RESTRICT ON UPDATE CASCADE,
    -- Performance indexes for login flow (brute-force check, audit queries)
    INDEX idx_userevents_user_created (user_id, created_at),
    INDEX idx_userevents_created (created_at)
);

-- Idempotent add of userevents.detail for databases created before FR-FED-5 shipped. MySQL has
-- no `ADD COLUMN IF NOT EXISTS`, so guard on information_schema and run the ALTER via a prepared
-- statement only when the column is absent — safe to re-run, and destructive to nothing.
SET @add_userevents_detail := (
    SELECT IF(COUNT(*) = 0,
        'ALTER TABLE userevents ADD COLUMN detail VARCHAR(255) DEFAULT NULL AFTER ip_address',
        'DO 0')
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'userevents' AND COLUMN_NAME = 'detail');
PREPARE add_userevents_detail_stmt FROM @add_userevents_detail;
EXECUTE add_userevents_detail_stmt;
DEALLOCATE PREPARE add_userevents_detail_stmt;

-- Idempotent add of users.using_passkey for databases created before passkey (WebAuthn) support
-- shipped. Mirrors using_totp: a denormalized flag so UserDTO/UserInterface can report "does this
-- account have a passkey" without a join, kept in sync by PasskeyServiceImpl whenever a credential
-- is added or the user's last one is removed. Same information_schema guard as every other
-- idempotent ALTER in this file, since MySQL has no ADD COLUMN IF NOT EXISTS.
SET @add_users_using_passkey := (
    SELECT IF(COUNT(*) = 0,
        'ALTER TABLE users ADD COLUMN using_passkey BOOLEAN DEFAULT FALSE AFTER using_totp',
        'DO 0')
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'users' AND COLUMN_NAME = 'using_passkey');
PREPARE add_users_using_passkey_stmt FROM @add_users_using_passkey;
EXECUTE add_users_using_passkey_stmt;
DEALLOCATE PREPARE add_users_using_passkey_stmt;

-- Idempotent add of users.origin (P2-1, user type classification): an immutable fact stamped ONLY
-- at account creation, never touched again — how the account was BORN, not what identities it
-- currently has linked. NULL for password registration (INTERNAL/EXTERNAL is then derived on read
-- from the email domain, see UserTypeResolver); 'FEDERATED_<PROVIDER>' for an account created by
-- FederatedIdentityServiceImpl#insertFederatedUser on first contact. A password account that later
-- LINKS a federated identity via the Security Center does NOT change origin — step 2 of
-- findOrCreateFederatedUser (link-to-existing) deliberately never writes this column.
SET @add_users_origin := (
    SELECT IF(COUNT(*) = 0,
        'ALTER TABLE users ADD COLUMN origin VARCHAR(30) DEFAULT NULL AFTER using_passkey',
        'DO 0')
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'users' AND COLUMN_NAME = 'origin');
PREPARE add_users_origin_stmt FROM @add_users_origin;
EXECUTE add_users_origin_stmt;
DEALLOCATE PREPARE add_users_origin_stmt;

-- Widen users.image_url for federated avatar URLs.
--
-- The column was VARCHAR(255). Identity providers hand back longer URLs than that — a Google
-- avatar (`https://lh3.googleusercontent.com/a/<long-opaque-token>=s96-c`) can exceed it — and
-- MySQL outside strict mode SILENTLY TRUNCATES on insert rather than failing. The row is written,
-- the login succeeds, and the only symptom is a broken image in the browser: the URL was chopped
-- mid-token and resolves to nothing. Nothing server-side ever reports it.
--
-- Guarded on the current length so re-running is a no-op, and widening never rejects existing data.
SET @widen_image_url := (
    SELECT IF(COUNT(*) = 1,
        'ALTER TABLE users MODIFY COLUMN image_url VARCHAR(512) DEFAULT ''https://cdn-icons-png.flaticon.com/512/149/149071.png''',
        'DO 0')
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'users'
      AND COLUMN_NAME = 'image_url' AND CHARACTER_MAXIMUM_LENGTH < 512);
PREPARE widen_image_url_stmt FROM @widen_image_url;
EXECUTE widen_image_url_stmt;
DEALLOCATE PREPARE widen_image_url_stmt;

-- Idempotent add of users.roles_changed_at (FUTURE-ENHANCEMENTS §3.1, role-change JWT staleness).
-- Mirrors password_changed_at exactly: RoleRepoImpl#updateUserRole stamps NOW() here on every
-- role change (admin-initiated or the auto-revert-to-ROLE_USER path when a time-boxed assignment
-- expires), and TokenProvider#isTokenValid rejects any access token whose issuedAt is not after
-- this value — so a demotion (or an expired elevated assignment) takes effect on the very next
-- request instead of waiting out the access token's 30-minute TTL. NULL for a user whose role has
-- never changed since account creation, in which case no invalidation check is performed, same as
-- password_changed_at's NULL case.
SET @add_users_roles_changed_at := (
    SELECT IF(COUNT(*) = 0,
        'ALTER TABLE users ADD COLUMN roles_changed_at DATETIME DEFAULT NULL AFTER password_changed_at',
        'DO 0')
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'users' AND COLUMN_NAME = 'roles_changed_at');
PREPARE add_users_roles_changed_at_stmt FROM @add_users_roles_changed_at;
EXECUTE add_users_roles_changed_at_stmt;
DEALLOCATE PREPARE add_users_roles_changed_at_stmt;

-- ── Verification flows ─────────────────────────────────────────────────────────────────
-- `url` stores a bare UUID verification key (NOT a full URL); the app builds the clickable
-- email link from it.
CREATE TABLE IF NOT EXISTS accountverifications
(
    id                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
    user_id           BIGINT UNSIGNED NOT NULL,
    verification_key  VARCHAR(255) NOT NULL,
    FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE ON UPDATE CASCADE,
    CONSTRAINT UQ_AccountVerifications_User_Id UNIQUE (user_id),
    CONSTRAINT UQ_AccountVerifications_Url UNIQUE (verification_key)
);

-- Idempotent rename of accountverifications.url -> verification_key (UserQuery.java's former TODO).
-- The column has only ever stored a bare UUID key, never a full URL — see UserQuery's Javadoc.
-- MySQL has no `RENAME COLUMN IF EXISTS`, so guard on information_schema the same way every other
-- conditional ALTER in this file does; a rerun after the column is already renamed is a no-op.
SET @rename_accountverifications_key := (
    SELECT IF(COUNT(*) = 1,
        'ALTER TABLE accountverifications RENAME COLUMN url TO verification_key',
        'DO 0')
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'accountverifications' AND COLUMN_NAME = 'url');
PREPARE rename_accountverifications_key_stmt FROM @rename_accountverifications_key;
EXECUTE rename_accountverifications_key_stmt;
DEALLOCATE PREPARE rename_accountverifications_key_stmt;

CREATE TABLE IF NOT EXISTS resetpasswordverifications
(
    id                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
    user_id           BIGINT UNSIGNED NOT NULL,
    verification_key  VARCHAR(255) NOT NULL,
    expiration_date   DATETIME     NOT NULL,
    FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE ON UPDATE CASCADE,
    CONSTRAINT UQ_ResetPasswordVerifications_User_Id UNIQUE (user_id),
    CONSTRAINT UQ_ResetPasswordVerifications_Url UNIQUE (verification_key)
);

-- Idempotent rename of resetpasswordverifications.url -> verification_key. Same guard shape as
-- accountverifications above; see that block's comment for why.
SET @rename_resetpasswordverifications_key := (
    SELECT IF(COUNT(*) = 1,
        'ALTER TABLE resetpasswordverifications RENAME COLUMN url TO verification_key',
        'DO 0')
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'resetpasswordverifications' AND COLUMN_NAME = 'url');
PREPARE rename_resetpasswordverifications_key_stmt FROM @rename_resetpasswordverifications_key;
EXECUTE rename_resetpasswordverifications_key_stmt;
DEALLOCATE PREPARE rename_resetpasswordverifications_key_stmt;

CREATE TABLE IF NOT EXISTS twofactorverifications
(
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
    user_id         BIGINT UNSIGNED NOT NULL,
    code            VARCHAR(10) NOT NULL,
    expiration_date DATETIME    NOT NULL,
    FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE ON UPDATE CASCADE,
    CONSTRAINT UQ_TwoFactorVerifications_User_Id UNIQUE (user_id),
    CONSTRAINT UQ_TwoFactorVerifications_Code UNIQUE (code)
);

-- ── JPA-managed domain: customers, invoices, services ──────────────────────────────────
-- These four tables back the JPA @Entity classes (model/Customer, Invoice, Services, and the
-- Invoice @ElementCollection of InvoiceLineItem → invoiceserviceitems). Previously they were left
-- to Hibernate's ddl-auto:update; defining them here lets production run ddl-auto:validate
-- (fail-fast, never auto-alter). The DDL is a VERBATIM transcription of Hibernate's own schema
-- export (test JpaSchemaSyncTest, dialect=MySQL, globally_quoted_identifiers=true), so
-- the quoted camelCase identifiers and column types match exactly what `validate` checks against.
-- Foreign keys are inlined (not ALTER TABLE) so the script stays idempotent under IF NOT EXISTS,
-- which also fixes the create order: Customer → Invoice → invoiceserviceitems.
CREATE TABLE IF NOT EXISTS `Customer`
(
    `createdAt`       datetime(6),
    `id`              bigint       NOT NULL AUTO_INCREMENT,
    `address`         varchar(255),
    `customer_name`   varchar(255) NOT NULL,
    `email`           varchar(255) NOT NULL,
    `imageUrl`        varchar(255),
    `organization_id` bigint,
    `phoneNumber`     varchar(255),
    `status`          varchar(255) NOT NULL,
    `type`            varchar(255) NOT NULL,
    PRIMARY KEY (`id`)
) engine = InnoDB;

-- Idempotent add of Customer.organization_id for databases created before org-scoped reporting
-- (FR-ORG-2) shipped. Same guard pattern as userevents.detail above: MySQL has no
-- `ADD COLUMN IF NOT EXISTS`, so check information_schema and run the ALTER through a prepared
-- statement only when the column is absent.
--
-- Deliberately NOT a foreign key. `organizations` is owned by the JDBC half of the schema while
-- `Customer` is generated from the JPA entity and policed by Hibernate's ddl-auto: validate; a
-- constraint spanning the two would be invisible to the entity mapping and would make the
-- JpaSchemaSyncTest drift guard meaningless. The relationship is enforced in the service layer,
-- which is also where the "which organization may this caller see?" decision already lives.
SET @add_customer_org := (
    SELECT IF(COUNT(*) = 0,
        'ALTER TABLE `Customer` ADD COLUMN `organization_id` bigint DEFAULT NULL AFTER `imageUrl`',
        'DO 0')
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'Customer' AND COLUMN_NAME = 'organization_id');
PREPARE add_customer_org_stmt FROM @add_customer_org;
EXECUTE add_customer_org_stmt;
DEALLOCATE PREPARE add_customer_org_stmt;

CREATE TABLE IF NOT EXISTS `Invoice`
(
    `amount`        float(53),
    `totalAmount`   float(53)    NOT NULL,
    `customer`      bigint       NOT NULL,
    `customerId`    bigint,
    `id`            bigint       NOT NULL AUTO_INCREMENT,
    `invoiceDate`   datetime(6),
    `invoiceNumber` varchar(255),
    `status`        varchar(255) NOT NULL,
    PRIMARY KEY (`id`),
    CONSTRAINT `FK_Invoice_Customer` FOREIGN KEY (`customer`) REFERENCES `Customer` (`id`)
) engine = InnoDB;

-- Idempotent relaxation of Invoice.customer for draft invoices. POST /invoice/create has always
-- described itself as creating a standalone invoice to be linked to a customer later, but the
-- column was NOT NULL, so that path could never actually be used. Guarded on information_schema
-- because MySQL has no `MODIFY COLUMN IF`; re-running is a no-op and nothing is destroyed (going
-- NOT NULL -> NULL never rejects existing rows).
SET @relax_invoice_customer := (
    SELECT IF(COUNT(*) = 1,
        'ALTER TABLE `Invoice` MODIFY COLUMN `customer` bigint NULL',
        'DO 0')
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'Invoice'
      AND COLUMN_NAME = 'customer' AND IS_NULLABLE = 'NO');
PREPARE relax_invoice_customer_stmt FROM @relax_invoice_customer;
EXECUTE relax_invoice_customer_stmt;
DEALLOCATE PREPARE relax_invoice_customer_stmt;

CREATE TABLE IF NOT EXISTS `Services`
(
    `price`            float(53),
    `id`               bigint  NOT NULL AUTO_INCREMENT,
    `description`      varchar(255),
    `name`             varchar(255),
    `active`           boolean NOT NULL DEFAULT TRUE,
    `organization_id`  bigint,
    PRIMARY KEY (`id`)
) engine = InnoDB;

-- Idempotent add of Services.active for catalogs created before service retirement shipped.
-- DEFAULT TRUE means every pre-existing service reads back as offered, which is the only correct
-- interpretation of a catalog that had no notion of being retired.
SET @add_services_active := (
    SELECT IF(COUNT(*) = 0,
        'ALTER TABLE `Services` ADD COLUMN `active` boolean NOT NULL DEFAULT TRUE',
        'DO 0')
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'Services' AND COLUMN_NAME = 'active');
PREPARE add_services_active_stmt FROM @add_services_active;
EXECUTE add_services_active_stmt;
DEALLOCATE PREPARE add_services_active_stmt;

-- Idempotent add of Services.organization_id for catalogs created before per-organization service
-- catalogs shipped. NULL default means every pre-existing service reads back as a globally shared
-- entry, exactly the behavior the catalog already had — same guard pattern, and same "no foreign
-- key" reasoning, as Customer.organization_id above.
SET @add_services_org := (
    SELECT IF(COUNT(*) = 0,
        'ALTER TABLE `Services` ADD COLUMN `organization_id` bigint DEFAULT NULL AFTER `active`',
        'DO 0')
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'Services' AND COLUMN_NAME = 'organization_id');
PREPARE add_services_org_stmt FROM @add_services_org;
EXECUTE add_services_org_stmt;
DEALLOCATE PREPARE add_services_org_stmt;

-- Seed the services catalog.
--
-- The table was created empty and nothing ever populated it, so `/services` and the service
-- picker on a new invoice both rendered "no services" on any database built from this file. The
-- catalog is reference data the application reads but never generates, so it belongs here beside
-- the roles and event-type seeds rather than in DemoDataSeeder (which exists for sample
-- customers/invoices, i.e. data a real deployment would delete).
--
-- Ids are pinned for the same reason the role ids are: `INSERT ... ON DUPLICATE KEY UPDATE` on an
-- auto-increment column burns a value per row on every re-run, and `Services` has no unique key on
-- `name` to dedupe against. Keying on the primary key makes re-running this file update the rows in
-- place instead of appending a second copy of the catalogue each time.
--
-- Prices are the standard rate; an invoice copies name and price into its own line item when it is
-- raised, so editing one of these later never restates an invoice already issued.
INSERT INTO `Services` (`id`, `name`, `description`, `price`, `active`)
VALUES (1, 'Identity & Access Review', 'Audit of roles, permissions, and account lifecycle against least-privilege policy.', 2400.00, TRUE),
       (2, 'Single Sign-On Integration', 'Connect an existing identity provider (Google, Microsoft Entra, Okta) via OAuth2/OIDC.', 3600.00, TRUE),
       (3, 'Multi-Factor Rollout', 'Enrolment campaign and support runbook for authenticator-app MFA across an organisation.', 1800.00, TRUE),
       (4, 'Security Posture Assessment', 'Review of authentication, session handling, and audit coverage with a prioritised findings report.', 4200.00, TRUE),
       (5, 'Cloud Migration', 'Containerise and migrate an existing deployment to managed cloud infrastructure.', 7500.00, TRUE),
       (6, 'Database Administration', 'Schema review, index tuning, backup verification, and restore rehearsal.', 1500.00, TRUE),
       (7, 'Web Application Development', 'Custom feature development against the existing Angular and Spring Boot stack.', 5200.00, TRUE),
       (8, 'API Integration', 'Design and build an integration between this platform and a third-party system.', 2800.00, TRUE),
       (9, 'Compliance Reporting', 'Evidence pack assembled from the audit log for an external assessor.', 1950.00, TRUE),
       (10, 'Onboarding & Training', 'Administrator and end-user training, delivered remotely with recorded sessions.', 950.00, TRUE),
       (11, 'Priority Support Retainer', 'Named contact with a four-hour response target during business hours.', 1200.00, TRUE),
       (12, 'Data Import & Cleansing', 'Bulk import of customer records with de-duplication and validation reporting.', 1100.00, TRUE) AS new
ON DUPLICATE KEY UPDATE `name`        = new.`name`,
                        `description` = new.`description`,
                        `price`       = new.`price`;
-- `active` is deliberately NOT overwritten: a service an administrator has retired must stay
-- retired across a re-run of this file, or the seed would silently put it back on sale.

-- @ElementCollection table for Invoice.services (List<InvoiceLineItem>); composite PK
-- (item_order, invoice_id), NO surrogate id — exactly as Hibernate maps an @OrderColumn collection.
CREATE TABLE IF NOT EXISTS `invoiceserviceitems`
(
    `item_order` integer NOT NULL,
    `price`      float(53),
    `invoice_id` bigint  NOT NULL,
    `name`       varchar(255),
    PRIMARY KEY (`item_order`, `invoice_id`),
    CONSTRAINT `FK_InvoiceServiceItems_Invoice` FOREIGN KEY (`invoice_id`) REFERENCES `Invoice` (`id`)
) engine = InnoDB;

-- ── Federated identity (OAuth2 / OIDC) ─────────────────────────────────────────────────
-- Stores ONLY the provider name + the provider's stable subject id (never a third-party
-- credential). UNIQUE(provider, provider_subject) makes find-or-create idempotent.
CREATE TABLE IF NOT EXISTS oauthproviderlinks
(
    id               BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
    user_id          BIGINT UNSIGNED NOT NULL,
    provider         VARCHAR(30)  NOT NULL,
    provider_subject VARCHAR(255) NOT NULL,
    created_at       DATETIME DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE ON UPDATE CASCADE,
    CONSTRAINT UQ_OAuthProviderLinks_Provider_Subject UNIQUE (provider, provider_subject)
);

-- One-time backfill (2026-08-08): accounts created BEFORE users.origin existed are stuck at NULL,
-- which the user-type badge (P2-1) reads as a password account — even when the account was
-- actually born from a federated sign-in. A password-less account (password IS NULL, exactly how
-- FederatedIdentityServiceImpl#insertFederatedUser creates one) with a linked identity was created
-- BY federation, so backfill origin from the EARLIEST link on record (the provider that actually
-- created the account, not one linked later). Guarded on origin IS NULL, so this can only ever set
-- a value once per row — safe to re-run on every boot.
UPDATE users u
    JOIN (
        SELECT opl.user_id, opl.provider
        FROM oauthproviderlinks opl
                 INNER JOIN (SELECT user_id, MIN(created_at) AS first_linked_at
                             FROM oauthproviderlinks
                             GROUP BY user_id) earliest
                            ON earliest.user_id = opl.user_id AND earliest.first_linked_at = opl.created_at
    ) first_link ON first_link.user_id = u.id
SET u.origin = CONCAT('FEDERATED_', UPPER(first_link.provider))
WHERE u.origin IS NULL
  AND u.password IS NULL;

-- ── Organizations + membership (org-scoped admin) ──────────────────────────────────────
CREATE TABLE IF NOT EXISTS organizations
(
    id         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
    name       VARCHAR(100) NOT NULL,
    status     VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    created_at DATETIME     DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT UQ_Organizations_Name UNIQUE (name)
);

-- Same idempotent CHECK-rebuild pattern as events.type (see that block for the full rationale):
-- re-apply the status CHECK so a pre-existing organizations table can't reject a newly added
-- status value on INSERT. Cheap insurance — the status set is stable today ('ACTIVE','INACTIVE').
SET @orgs_chk := (SELECT CONSTRAINT_NAME FROM information_schema.TABLE_CONSTRAINTS
                  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'organizations'
                    AND CONSTRAINT_TYPE = 'CHECK' LIMIT 1);
SET @drop_orgs_chk := IF(@orgs_chk IS NULL, 'DO 0',
                         CONCAT('ALTER TABLE organizations DROP CHECK `', @orgs_chk, '`'));
PREPARE drop_orgs_chk_stmt FROM @drop_orgs_chk;
EXECUTE drop_orgs_chk_stmt;
DEALLOCATE PREPARE drop_orgs_chk_stmt;

ALTER TABLE organizations ADD CONSTRAINT CK_Organizations_Status CHECK (status IN ('ACTIVE', 'INACTIVE'));

INSERT INTO organizations (name, status)
VALUES ('Tessera', 'ACTIVE'),
       ('Acme Partners', 'ACTIVE') AS new
ON DUPLICATE KEY UPDATE status = new.status;

-- ── Organization setup: tenant UUID, MFA policy, feature flags (2026-08-28) ─────────────
--
-- tenant_uuid is an external tenant identifier (distinct from the internal auto-increment `id`),
-- admin-supplied and settable exactly once — enforced in OrganizationServiceImpl#setTenantUuid by
-- refusing the write when the column is already non-null, not by anything the database itself can
-- express. mfa_allowed_methods is a CSV of OrgMfaMethod names (NULL/empty = this organization has
-- not configured a policy, which OrganizationServiceImpl#isMfaMethodAllowed treats as "no
-- restriction from this org", not as "none allowed" — an org that never touches the setting must not
-- suddenly block its members' existing MFA enrollment). feature_flags is a CSV of free-form labels;
-- nothing in the application reads them yet (see FUTURE-ENHANCEMENTS.md).
SET @add_orgs_tenant_uuid := (
    SELECT COUNT(*) = 0 FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'organizations' AND COLUMN_NAME = 'tenant_uuid');
SET @add_orgs_tenant_uuid_sql := IF(@add_orgs_tenant_uuid,
    'ALTER TABLE organizations ADD COLUMN tenant_uuid CHAR(36) DEFAULT NULL AFTER status',
    'DO 0');
PREPARE add_orgs_tenant_uuid_stmt FROM @add_orgs_tenant_uuid_sql;
EXECUTE add_orgs_tenant_uuid_stmt;
DEALLOCATE PREPARE add_orgs_tenant_uuid_stmt;

-- Uniqueness on tenant_uuid is enforced separately from the ADD COLUMN above (a UNIQUE column-level
-- constraint can't be appended with ADD COLUMN once the column already exists on a re-run), same
-- reason the CHECK constraints in this file are rebuilt in their own guarded step.
SET @orgs_tenant_uuid_uq := (SELECT CONSTRAINT_NAME FROM information_schema.TABLE_CONSTRAINTS
                             WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'organizations'
                               AND CONSTRAINT_NAME = 'UQ_Organizations_TenantUuid' LIMIT 1);
SET @add_orgs_tenant_uuid_uq := IF(@orgs_tenant_uuid_uq IS NULL,
    'ALTER TABLE organizations ADD CONSTRAINT UQ_Organizations_TenantUuid UNIQUE (tenant_uuid)',
    'DO 0');
PREPARE add_orgs_tenant_uuid_uq_stmt FROM @add_orgs_tenant_uuid_uq;
EXECUTE add_orgs_tenant_uuid_uq_stmt;
DEALLOCATE PREPARE add_orgs_tenant_uuid_uq_stmt;

SET @add_orgs_mfa_methods := (
    SELECT COUNT(*) = 0 FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'organizations' AND COLUMN_NAME = 'mfa_allowed_methods');
SET @add_orgs_mfa_methods_sql := IF(@add_orgs_mfa_methods,
    'ALTER TABLE organizations ADD COLUMN mfa_allowed_methods VARCHAR(100) DEFAULT NULL AFTER tenant_uuid',
    'DO 0');
PREPARE add_orgs_mfa_methods_stmt FROM @add_orgs_mfa_methods_sql;
EXECUTE add_orgs_mfa_methods_stmt;
DEALLOCATE PREPARE add_orgs_mfa_methods_stmt;

SET @add_orgs_feature_flags := (
    SELECT COUNT(*) = 0 FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'organizations' AND COLUMN_NAME = 'feature_flags');
SET @add_orgs_feature_flags_sql := IF(@add_orgs_feature_flags,
    'ALTER TABLE organizations ADD COLUMN feature_flags VARCHAR(255) DEFAULT NULL AFTER mfa_allowed_methods',
    'DO 0');
PREPARE add_orgs_feature_flags_stmt FROM @add_orgs_feature_flags_sql;
EXECUTE add_orgs_feature_flags_stmt;
DEALLOCATE PREPARE add_orgs_feature_flags_stmt;

CREATE TABLE IF NOT EXISTS userorganizations
(
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
    user_id         BIGINT UNSIGNED NOT NULL,
    organization_id BIGINT UNSIGNED NOT NULL,
    active          BOOLEAN  NOT NULL DEFAULT TRUE,
    created_at      DATETIME DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE ON UPDATE CASCADE,
    FOREIGN KEY (organization_id) REFERENCES organizations (id) ON DELETE CASCADE ON UPDATE CASCADE,
    CONSTRAINT UQ_UserOrganizations_User_Org UNIQUE (user_id, organization_id)
);

-- ── Per-organization role on the membership row (2026-08-26, TODO(org-roles)) ───────────
--
-- Until now a membership row recorded only THAT a user belongs to an organization, never in what
-- capacity. "Organization admin" was a property of the user's single GLOBAL role
-- (`ROLE_ORGANIZATION_ADMIN` in `userroles`, which is UNIQUE per user), and its scope was "every
-- organization I actively belong to". There was no way to express admin of one organization and
-- ordinary member of another — the thing a genuine multi-tenant deployment needs — and the invite
-- flow made that concrete: redeeming an invite granted a GLOBAL role, so an invite issued by one
-- organization elevated the redeemer everywhere they held a membership.
--
-- `org_role` moves that capacity onto the membership row, where it is per-organization by
-- construction. ORG_VIEWER < ORG_MEMBER < ORG_ADMIN; `OrgRole` is the compile-time mirror, and it
-- is deliberately NOT read from this table for its ordering, for the same reason `RoleType` pins
-- its tiers in code rather than reading `roles.id` — an authorization decision must not depend on
-- what a seed script happened to write.
--
-- The global tiers keep their existing meaning and are unchanged: ROLE_ADMIN and
-- ROLE_APPLICATION_ADMIN remain unscoped platform operators who bypass org checks entirely.
SET @add_uo_org_role := (
    SELECT COUNT(*) = 0
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'userorganizations' AND COLUMN_NAME = 'org_role');

SET @add_uo_org_role_sql := IF(@add_uo_org_role,
    'ALTER TABLE userorganizations ADD COLUMN org_role VARCHAR(20) NOT NULL DEFAULT ''ORG_MEMBER'' AFTER organization_id',
    'DO 0');
PREPARE add_uo_org_role_stmt FROM @add_uo_org_role_sql;
EXECUTE add_uo_org_role_stmt;
DEALLOCATE PREPARE add_uo_org_role_stmt;

-- Backfill, so existing deployments behave EXACTLY as they did before this column existed: whoever
-- could administer an organization yesterday (a global tier at or above ROLE_ORGANIZATION_ADMIN,
-- holding an active membership) becomes ORG_ADMIN of the organizations they belong to. Everyone
-- else takes the column default, ORG_MEMBER.
--
-- Guarded on @add_uo_org_role — the flag captured BEFORE the ALTER above, not re-read after it —
-- so this runs exactly once, on the run that introduces the column. Re-reading the column's
-- existence here would make the backfill run on every subsequent `schema.sql` execution and stomp
-- every later demotion an administrator had made through the UI, the same "never overwrite a live
-- edit" property `securitysettings`' INSERT IGNORE seed protects.
--
-- Wrapped in SQL_SAFE_UPDATES=0: the WHERE below (uo.active / r.name) is deliberate and reviewed,
-- but neither column is a key on userorganizations, so a client with safe-update mode on (e.g.
-- MySQL Workbench's default) rejects the UPDATE with Error 1175. Captured and restored rather than
-- hardcoded, so this doesn't change the setting for anything else in the session.
SET @orig_safe_updates := @@SQL_SAFE_UPDATES;
SET SQL_SAFE_UPDATES = 0;
SET @backfill_uo_org_role_sql := IF(@add_uo_org_role,
    'UPDATE userorganizations uo
         JOIN userroles ur ON ur.user_id = uo.user_id
         JOIN roles r ON r.id = ur.role_id
     SET uo.org_role = ''ORG_ADMIN''
     WHERE uo.active = TRUE
       AND r.name IN (''ROLE_ORGANIZATION_ADMIN'', ''ROLE_ADMIN'', ''ROLE_APPLICATION_ADMIN'')',
    'DO 0');
PREPARE backfill_uo_org_role_stmt FROM @backfill_uo_org_role_sql;
EXECUTE backfill_uo_org_role_stmt;
DEALLOCATE PREPARE backfill_uo_org_role_stmt;
SET SQL_SAFE_UPDATES = @orig_safe_updates;

-- Same idempotent CHECK-rebuild pattern as events.type and organizations.status (see the events
-- block for the full rationale): drop whatever CHECK this table currently carries and re-apply the
-- current one, so a database created before a new org role shipped cannot reject it on INSERT.
SET @uo_chk := (SELECT CONSTRAINT_NAME FROM information_schema.TABLE_CONSTRAINTS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'userorganizations'
                  AND CONSTRAINT_TYPE = 'CHECK' LIMIT 1);
SET @drop_uo_chk := IF(@uo_chk IS NULL, 'DO 0',
                       CONCAT('ALTER TABLE userorganizations DROP CHECK `', @uo_chk, '`'));
PREPARE drop_uo_chk_stmt FROM @drop_uo_chk;
EXECUTE drop_uo_chk_stmt;
DEALLOCATE PREPARE drop_uo_chk_stmt;

ALTER TABLE userorganizations
    ADD CONSTRAINT CK_UserOrganizations_OrgRole CHECK (org_role IN ('ORG_ADMIN', 'ORG_MEMBER', 'ORG_VIEWER'));

-- Idempotent add of organizations.description/contact_email/website (2026-08-22, self-service
-- organization profile/settings). All three ship together, so checking for the absence of one
-- (description) guards the whole ALTER — same information_schema pattern as every other
-- idempotent column add in this file, since MySQL has no ADD COLUMN IF NOT EXISTS. All three are
-- nullable: an organization created before this shipped, or one that simply has no website yet,
-- is not forced to backfill a value.
SET @add_organizations_profile := (
    SELECT IF(COUNT(*) = 0,
        'ALTER TABLE organizations ADD COLUMN description VARCHAR(500) DEFAULT NULL AFTER status, ADD COLUMN contact_email VARCHAR(255) DEFAULT NULL AFTER description, ADD COLUMN website VARCHAR(255) DEFAULT NULL AFTER contact_email',
        'DO 0')
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'organizations' AND COLUMN_NAME = 'description');
PREPARE add_organizations_profile_stmt FROM @add_organizations_profile;
EXECUTE add_organizations_profile_stmt;
DEALLOCATE PREPARE add_organizations_profile_stmt;

-- ── Backfill: customer ownership (must follow the organizations tables above) ───────────
--
-- Adopts every unowned customer into the lowest-numbered ACTIVE organization.
--
-- ORDERING MATTERS, which is why this sits here rather than beside the `Customer` ALTER that adds
-- the column. It reads `organizations`, so it cannot run before that table exists. Placed up there
-- it read naturally and was wrong on a FRESH database: `organizations` is created further down, so
-- the backfill died with error 1146 (table doesn't exist) and — because both the mysql CLI and
-- Workbench halt on error — every statement after it silently never ran. Existing databases hid the
-- bug entirely, since they already had the table from an earlier run.
--
-- Existing rows predate the concept of customer ownership, so there is no recorded answer to
-- "whose customer is this?" — the honest choices are to guess once, here, or to leave them NULL
-- and invisible to every scoped administrator. Adopting them preserves the behaviour those rows
-- were created under (visible to the operators who have been managing them) instead of silently
-- emptying the dashboards of an established deployment.
--
-- Scoped to NULL rows only, so it is safe to re-run and never reassigns a customer that has since
-- been given a real owner. If more than one organization is in play, review the result and
-- reassign afterwards; this statement will not touch those rows again.
--
-- The `id` > 0 predicate is there for MySQL Workbench, not for correctness. Workbench connects
-- with safe-update mode on by default, which rejects any UPDATE whose WHERE clause does not
-- reference a KEY column (Error 1175) — and `organization_id` is not indexed. Since `id` is the
-- PRIMARY KEY and auto-increment values start at 1, the condition matches every row and changes
-- nothing about what this statement does; it just lets the file run as-is in Workbench instead of
-- requiring each person to disable a safety setting before applying the schema. A migration script
-- that only works after you have turned off a safety feature is a migration script that eventually
-- gets run with that feature off against the wrong database.
UPDATE `Customer`
SET `organization_id` = (SELECT MIN(id) FROM organizations WHERE status = 'ACTIVE')
WHERE `id` > 0
  AND `organization_id` IS NULL
  AND EXISTS (SELECT 1 FROM organizations WHERE status = 'ACTIVE');

-- ── Organization-level audit trail (2026-08-22) ────────────────────────────────────────
--
-- Mirrors userevents exactly, but keyed to an organization instead of to a single user, and
-- shares the same `events` catalog (see the ORG_* additions to CK_Events_Type above) rather than
-- a second, independently-maintained type table. actor_user_id is nullable and ON DELETE SET
-- NULL rather than CASCADE: a deleted account should not erase the historical fact that an event
-- happened on this organization, only the identity of who did it.
CREATE TABLE IF NOT EXISTS organizationevents
(
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
    organization_id BIGINT UNSIGNED NOT NULL,
    actor_user_id   BIGINT UNSIGNED DEFAULT NULL,
    event_id        BIGINT UNSIGNED NOT NULL,
    detail          VARCHAR(255) DEFAULT NULL,
    created_at      DATETIME     DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (organization_id) REFERENCES organizations (id) ON DELETE CASCADE ON UPDATE CASCADE,
    FOREIGN KEY (actor_user_id) REFERENCES users (id) ON DELETE SET NULL ON UPDATE CASCADE,
    FOREIGN KEY (event_id) REFERENCES events (id) ON DELETE RESTRICT ON UPDATE CASCADE,
    INDEX idx_organizationevents_org_created (organization_id, created_at)
);

-- ── Organization invites (2026-08-22, self-service member onboarding) ─────────────────
--
-- DB-backed, single-use, expiring token — the same convention resetpasswordverifications already
-- uses (code + expiration_date, row deleted on redemption), NOT the in-memory
-- ProviderLinkTicketService pattern, which is documented as a per-instance scaling limitation
-- (FUTURE-ENHANCEMENTS.md §2.4) that new features should not repeat. role_name is the role the
-- invite grants on redemption, bounded at creation time by RoleType#canAssign against the
-- inviting administrator's own tier (see OrganizationController), so an invite can never be used
-- to mint a role more privileged than its creator could otherwise assign.
CREATE TABLE IF NOT EXISTS organizationinvites
(
    id                 BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
    organization_id    BIGINT UNSIGNED NOT NULL,
    invited_by_user_id BIGINT UNSIGNED NOT NULL,
    code               VARCHAR(64)  NOT NULL,
    role_name          VARCHAR(50)  NOT NULL DEFAULT 'ROLE_USER',
    expiration_date    DATETIME     NOT NULL,
    created_at         DATETIME     DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (organization_id) REFERENCES organizations (id) ON DELETE CASCADE ON UPDATE CASCADE,
    FOREIGN KEY (invited_by_user_id) REFERENCES users (id) ON DELETE CASCADE ON UPDATE CASCADE,
    CONSTRAINT UQ_OrganizationInvites_Code UNIQUE (code)
);

-- ── Per-organization external IdP (enterprise SSO) (2026-08-29) ────────────────────────
--
-- One IdP config per organization for this MVP (UQ_OrgIdP_Organization) — an org replaces its row
-- to switch providers rather than layering several. oidc_client_secret_ciphertext holds the
-- AES-256-GCM ciphertext produced by EncryptionUtil (IV + ciphertext + auth tag, base64-encoded);
-- the plaintext secret is never persisted. saml_metadata_uri is reserved for the SAML follow-up
-- (FUTURE-ENHANCEMENTS.md §3.1 Stage 3) and unused by Stage 1/2.
CREATE TABLE IF NOT EXISTS organizationidentityproviders
(
    id                            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
    organization_id               BIGINT UNSIGNED NOT NULL,
    protocol                      VARCHAR(10)  NOT NULL,
    display_name                  VARCHAR(100) NOT NULL,
    status                        VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    oidc_issuer_uri               VARCHAR(500) DEFAULT NULL,
    oidc_client_id                VARCHAR(255) DEFAULT NULL,
    oidc_client_secret_ciphertext VARCHAR(1000) DEFAULT NULL,
    saml_metadata_uri             VARCHAR(500) DEFAULT NULL,
    created_at                    DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at                    DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT UQ_OrgIdP_Organization UNIQUE (organization_id),
    FOREIGN KEY (organization_id) REFERENCES organizations (id) ON DELETE CASCADE ON UPDATE CASCADE
);

-- Same idempotent CHECK-rebuild pattern as organizations.status.
SET @orgidp_protocol_chk := (SELECT CONSTRAINT_NAME FROM information_schema.TABLE_CONSTRAINTS
                             WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'organizationidentityproviders'
                               AND CONSTRAINT_TYPE = 'CHECK' AND CONSTRAINT_NAME LIKE '%Protocol%' LIMIT 1);
SET @drop_orgidp_protocol_chk := IF(@orgidp_protocol_chk IS NULL, 'DO 0',
                                    CONCAT('ALTER TABLE organizationidentityproviders DROP CHECK `', @orgidp_protocol_chk, '`'));
PREPARE drop_orgidp_protocol_chk_stmt FROM @drop_orgidp_protocol_chk;
EXECUTE drop_orgidp_protocol_chk_stmt;
DEALLOCATE PREPARE drop_orgidp_protocol_chk_stmt;

ALTER TABLE organizationidentityproviders ADD CONSTRAINT CK_OrgIdP_Protocol CHECK (protocol IN ('OIDC', 'SAML'));

SET @orgidp_status_chk := (SELECT CONSTRAINT_NAME FROM information_schema.TABLE_CONSTRAINTS
                           WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'organizationidentityproviders'
                             AND CONSTRAINT_TYPE = 'CHECK' AND CONSTRAINT_NAME LIKE '%Status%' LIMIT 1);
SET @drop_orgidp_status_chk := IF(@orgidp_status_chk IS NULL, 'DO 0',
                                  CONCAT('ALTER TABLE organizationidentityproviders DROP CHECK `', @orgidp_status_chk, '`'));
PREPARE drop_orgidp_status_chk_stmt FROM @drop_orgidp_status_chk;
EXECUTE drop_orgidp_status_chk_stmt;
DEALLOCATE PREPARE drop_orgidp_status_chk_stmt;

ALTER TABLE organizationidentityproviders ADD CONSTRAINT CK_OrgIdP_Status CHECK (status IN ('ACTIVE', 'INACTIVE'));

-- Email-domain → organization routing for the login page's SSO discovery lookup
-- (GET /oauth2/org-sso-lookup). UQ_OrgSsoDomains_Domain is the key safety property: a domain can
-- never be claimed by more than one organization, so the lookup is always unambiguous.
CREATE TABLE IF NOT EXISTS organizationssodomains
(
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
    organization_id BIGINT UNSIGNED NOT NULL,
    domain          VARCHAR(255) NOT NULL,
    created_at      DATETIME DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT UQ_OrgSsoDomains_Domain UNIQUE (domain),
    FOREIGN KEY (organization_id) REFERENCES organizations (id) ON DELETE CASCADE ON UPDATE CASCADE
);

-- ── Authenticator-app (TOTP) multi-factor authentication ───────────────────────────────
CREATE TABLE IF NOT EXISTS totpcredentials
(
    id           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
    user_id      BIGINT UNSIGNED NOT NULL,
    secret       VARCHAR(64) NOT NULL,
    confirmed    BOOLEAN  DEFAULT FALSE,
    created_at   DATETIME DEFAULT CURRENT_TIMESTAMP,
    confirmed_at DATETIME DEFAULT NULL,
    FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE ON UPDATE CASCADE,
    CONSTRAINT UQ_TotpCredentials_User_Id UNIQUE (user_id)
);

CREATE TABLE IF NOT EXISTS totprecoverycodes
(
    id         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
    user_id    BIGINT UNSIGNED NOT NULL,
    code_hash  CHAR(64) NOT NULL,
    used_at    DATETIME DEFAULT NULL,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE ON UPDATE CASCADE
);

CREATE TABLE IF NOT EXISTS mfachallenges
(
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
    user_id         BIGINT UNSIGNED NOT NULL,
    challenge       CHAR(36) NOT NULL,
    expiration_date DATETIME NOT NULL,
    created_at      DATETIME DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE ON UPDATE CASCADE,
    CONSTRAINT UQ_MfaChallenges_User_Id UNIQUE (user_id),
    CONSTRAINT UQ_MfaChallenges_Challenge UNIQUE (challenge)
);

-- ── Passkeys (WebAuthn) ─────────────────────────────────────────────────────────────────
-- Unlike totpcredentials (one row per user), a user may register multiple passkeys — one per
-- device/authenticator — so there is no unique-per-user constraint; credential_id is the
-- globally-unique key instead. No BLOB columns: attestation_object is the CBOR-encoded WebAuthn
-- attestation object (which embeds the credential's public key), stored standard-base64 as TEXT —
-- matching this schema's existing preference for text-encoded secrets (totpcredentials.secret)
-- over raw binary, and re-parsed by webauthn4j's own ObjectConverter at authentication time rather
-- than this app hand-decomposing the public key itself. sign_count backs WebAuthn's clone-detection
-- check (PasskeyServiceImpl refuses an assertion whose counter did not increase, since a genuine
-- authenticator must never replay a value).
CREATE TABLE IF NOT EXISTS passkeycredentials
(
    id                  BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
    user_id             BIGINT UNSIGNED NOT NULL,
    credential_id       VARCHAR(255) NOT NULL,
    attestation_object  TEXT         NOT NULL,
    sign_count          BIGINT UNSIGNED NOT NULL DEFAULT 0,
    aaguid           VARCHAR(36)  DEFAULT NULL,
    -- Comma-joined authenticator transports ('internal', 'hybrid', 'usb', 'nfc', 'ble') reported
    -- at registration; lets the frontend show a platform-vs-phone-vs-security-key icon without a
    -- full AAGUID -> device-name database.
    transports       VARCHAR(100) DEFAULT NULL,
    -- User-supplied nickname at registration (e.g. "MacBook Touch ID", "YubiKey"), the same
    -- ask-for-one-bit-of-human-context UX this app already uses elsewhere.
    device_name      VARCHAR(100) DEFAULT NULL,
    created_at       DATETIME     DEFAULT CURRENT_TIMESTAMP,
    last_used_at     DATETIME     DEFAULT NULL,
    FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE ON UPDATE CASCADE,
    CONSTRAINT UQ_PasskeyCredentials_Credential_Id UNIQUE (credential_id),
    INDEX IX_PasskeyCredentials_User_Id (user_id)
);

-- ── Security settings (admin-tunable overrides for env-driven defaults) ────────────────
--
-- A single pinned row (id = 1), the same "one row, no key to look up" shape a table like this
-- always ends up wanting. Every column is NULLable and NULL means "no override — use the
-- application.yml / env default", which LoginRiskServiceImpl still owns. That split matters: this
-- table only ever widens what an admin CAN change without a redeploy, it does not replace the env
-- defaults or require every environment to populate it.
--
-- INSERT IGNORE, not the ON DUPLICATE KEY UPDATE the `roles` seed above uses. `roles` is reset to
-- the literal values this file specifies on every boot because an operator never edits it by hand.
-- This table is the opposite — an admin is expected to change it at runtime through the settings
-- panel, and a schema.sql that runs on every boot (`spring.sql.init.mode: always`) must not stomp
-- that edit back to NULL the next time the app restarts. IGNORE inserts the row only if it is
-- missing and otherwise leaves whatever is there untouched.
CREATE TABLE IF NOT EXISTS securitysettings
(
    id                     BIGINT UNSIGNED NOT NULL PRIMARY KEY,
    anomaly_enabled        BOOLEAN  DEFAULT NULL,
    anomaly_history_limit  INT      DEFAULT NULL,
    updated_at             DATETIME DEFAULT NULL,
    updated_by             BIGINT UNSIGNED DEFAULT NULL,
    FOREIGN KEY (updated_by) REFERENCES users (id) ON DELETE SET NULL ON UPDATE CASCADE
);

INSERT IGNORE INTO securitysettings (id, anomaly_enabled, anomaly_history_limit, updated_at, updated_by)
VALUES (1, NULL, NULL, NULL, NULL);

-- ── Server-side refresh sessions (rotation + reuse detection) ───────────────────────────
CREATE TABLE IF NOT EXISTS refreshsessions
(
    id           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
    user_id      BIGINT UNSIGNED NOT NULL,
    family       CHAR(36) NOT NULL,
    jti          CHAR(36) NOT NULL,
    device       VARCHAR(100) DEFAULT NULL,
    ip_address   VARCHAR(100) DEFAULT NULL,
    created_at   DATETIME     DEFAULT CURRENT_TIMESTAMP,
    last_used_at DATETIME     DEFAULT CURRENT_TIMESTAMP,
    expires_at   DATETIME     NOT NULL,
    revoked      BOOLEAN      DEFAULT FALSE,
    superseded   BOOLEAN      DEFAULT FALSE,
    FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE ON UPDATE CASCADE,
    CONSTRAINT UQ_RefreshSessions_Jti UNIQUE (jti),
    INDEX IX_RefreshSessions_User_Id (user_id),
    INDEX IX_RefreshSessions_Family (family)
);

-- ── Federated account-link tickets (single-instance -> DB-backed, FUTURE-ENHANCEMENTS §2.4) ──
-- Backs ProviderLinkTicketService. Was an in-memory ConcurrentHashMap; moved here so a ticket
-- minted on one app instance can be redeemed on another behind a load balancer. Same "opaque,
-- single-use, five-minute TTL, grants nothing on its own" shape the class javadoc describes —
-- only the storage changed, not the semantics. ticket is the UUID itself (no separate id column,
-- same one-column-is-the-key shape as webauthnchallenges below).
CREATE TABLE IF NOT EXISTS providerlinktickets
(
    ticket     CHAR(36)     NOT NULL PRIMARY KEY,
    user_id    BIGINT UNSIGNED NOT NULL,
    provider   VARCHAR(50)  NOT NULL,
    expires_at DATETIME     NOT NULL,
    FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE ON UPDATE CASCADE
);

-- ── WebAuthn ceremony challenges (single-instance -> DB-backed, FUTURE-ENHANCEMENTS §2.4) ──
-- Backs WebAuthnChallengeStore. Was an in-memory ConcurrentHashMap; moved here for the same
-- cross-instance reason as providerlinktickets above. The base64url-encoded challenge bytes ARE
-- the primary key (see WebAuthnChallengeStore's class javadoc for why: WebAuthn's own correlation
-- mechanism is the challenge value, so there is no separate id to invent). user_id is NULL for an
-- AUTHENTICATE challenge — the server does not know who is signing in until the assertion names a
-- credential id — so the FK is nullable, the same shape securitysettings.updated_by already uses.
CREATE TABLE IF NOT EXISTS webauthnchallenges
(
    challenge  VARCHAR(64)  NOT NULL PRIMARY KEY,
    purpose    VARCHAR(20)  NOT NULL,
    user_id    BIGINT UNSIGNED DEFAULT NULL,
    expires_at DATETIME     NOT NULL,
    FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE ON UPDATE CASCADE
);
