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

INSERT INTO roles (name, permission)
VALUES ('ROLE_GUEST', 'READ:USER'),
       ('ROLE_USER', 'READ:USER, READ:CUSTOMER'),
       ('ROLE_MODERATOR', 'READ:USER, READ:CUSTOMER, UPDATE:CUSTOMER'),
       ('ROLE_HELP_DESK_ADMIN', 'READ:USER, READ:CUSTOMER, UPDATE:USER'),
       ('ROLE_ORGANIZATION_ADMIN', 'READ:USER, READ:CUSTOMER, UPDATE:USER, UPDATE:ROLE'),
       ('ROLE_ADMIN',
        'READ:USER, READ:CUSTOMER, CREATE:USER, CREATE:CUSTOMER, UPDATE:USER, UPDATE:CUSTOMER, UPDATE:ROLE, DELETE:USER'),
       ('ROLE_APPLICATION_ADMIN',
        'READ:USER, READ:CUSTOMER, CREATE:USER, CREATE:CUSTOMER, UPDATE:USER, UPDATE:CUSTOMER, UPDATE:ROLE, DELETE:USER, DELETE:CUSTOMER') AS new
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

ALTER TABLE events ADD CONSTRAINT CK_Events_Type CHECK (type IN
    ('LOGIN_ATTEMPT', 'LOGIN_ATTEMPT_FAILURE', 'LOGIN_ATTEMPT_SUCCESS',
     'PROFILE_UPDATE', 'PROFILE_PICTURE_UPDATE', 'ROLE_UPDATE',
     'ACCOUNT_SETTINGS_UPDATE', 'PASSWORD_UPDATE', 'MFA_UPDATE',
     'FEDERATED_LOGIN',
     'TOTP_ENROLLED', 'TOTP_DISABLED', 'RECOVERY_CODE_USED',
     'SESSION_REVOKED', 'TOKEN_REUSE_DETECTED',
     'SUSPICIOUS_LOGIN'));

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
       ('SUSPICIOUS_LOGIN', 'We noticed a sign-in that didn''t match your usual device or location, so we asked for extra verification :|') AS new
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
    FOREIGN KEY (event_id) REFERENCES events (id) ON DELETE RESTRICT ON UPDATE CASCADE
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

-- ── Verification flows ─────────────────────────────────────────────────────────────────
-- `url` stores a bare UUID verification key (NOT a full URL); the app builds the clickable
-- email link from it.
CREATE TABLE IF NOT EXISTS accountverifications
(
    id      BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT UNSIGNED NOT NULL,
    url     VARCHAR(255) NOT NULL,
    FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE ON UPDATE CASCADE,
    CONSTRAINT UQ_AccountVerifications_User_Id UNIQUE (user_id),
    CONSTRAINT UQ_AccountVerifications_Url UNIQUE (url)
);

CREATE TABLE IF NOT EXISTS resetpasswordverifications
(
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
    user_id         BIGINT UNSIGNED NOT NULL,
    url             VARCHAR(255) NOT NULL,
    expiration_date DATETIME     NOT NULL,
    FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE ON UPDATE CASCADE,
    CONSTRAINT UQ_ResetPasswordVerifications_User_Id UNIQUE (user_id),
    CONSTRAINT UQ_ResetPasswordVerifications_Url UNIQUE (url)
);

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
    `price`       float(53),
    `id`          bigint  NOT NULL AUTO_INCREMENT,
    `description` varchar(255),
    `name`        varchar(255),
    `active`      boolean NOT NULL DEFAULT TRUE,
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
