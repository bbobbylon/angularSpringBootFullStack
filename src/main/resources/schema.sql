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

CREATE SCHEMA IF NOT EXISTS db2;
USE db2;

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
    CONSTRAINT UQ_Events_Type UNIQUE (type),
    CONSTRAINT CK_Events_Type CHECK (type IN
        ('LOGIN_ATTEMPT', 'LOGIN_ATTEMPT_FAILURE', 'LOGIN_ATTEMPT_SUCCESS',
         'PROFILE_UPDATE', 'PROFILE_PICTURE_UPDATE', 'ROLE_UPDATE',
         'ACCOUNT_SETTINGS_UPDATE', 'PASSWORD_UPDATE', 'MFA_UPDATE',
         'FEDERATED_LOGIN',
         'TOTP_ENROLLED', 'TOTP_DISABLED', 'RECOVERY_CODE_USED',
         'SESSION_REVOKED', 'TOKEN_REUSE_DETECTED'))
);

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
       ('TOKEN_REUSE_DETECTED', 'A previously used refresh token was replayed; the affected session family was revoked for your security :|') AS new
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
    `createdAt`     datetime(6),
    `id`            bigint       NOT NULL AUTO_INCREMENT,
    `address`       varchar(255),
    `customer_name` varchar(255) NOT NULL,
    `email`         varchar(255) NOT NULL,
    `imageUrl`      varchar(255),
    `phoneNumber`   varchar(255),
    `status`        varchar(255) NOT NULL,
    `type`          varchar(255) NOT NULL,
    PRIMARY KEY (`id`)
) engine = InnoDB;

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

CREATE TABLE IF NOT EXISTS `Services`
(
    `price`       float(53),
    `id`          bigint NOT NULL AUTO_INCREMENT,
    `description` varchar(255),
    `name`        varchar(255),
    PRIMARY KEY (`id`)
) engine = InnoDB;

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
    CONSTRAINT UQ_Organizations_Name UNIQUE (name),
    CONSTRAINT CK_Organizations_Status CHECK (status IN ('ACTIVE', 'INACTIVE'))
);

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
