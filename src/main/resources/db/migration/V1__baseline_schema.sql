-- ─────────────────────────────────────────────────────────────────────────────
-- V1 — Canonical baseline schema for the SecureCapita application.
--
-- This is the single source of truth for the database structure. It is applied
-- automatically by Flyway on application startup:
--   * Fresh DB:    Flyway runs V1 from scratch, then any later V2/V3/... files.
--   * Existing DB: With `spring.flyway.baseline-on-migrate=true` set, Flyway
--                  records V1 as the baseline without re-running it, so
--                  populated production / native-MySQL databases are untouched.
--
-- Hibernate runs in `ddl-auto: validate` mode and will fail-fast at startup
-- if any @Entity ↔ table mismatch is detected. To evolve the schema, add a
-- new V<N+1>__<name>.sql file — DO NOT edit V1 once it has been applied
-- anywhere, because Flyway tracks its checksum.
--
-- Column-naming convention: snake_case throughout, matching the rest of the
-- schema (users / roles / events). Spring Boot's default physical naming
-- strategy (SpringPhysicalNamingStrategy) converts camelCase Java fields
-- (createdAt, imageUrl, customerId, …) to snake_case automatically — no
-- @Column overrides required on the entities. Earlier versions of these
-- tables used camelCase column names as a side effect of
-- `hibernate.globally_quoted_identifiers: true`; that flag is gone now and
-- the names have been normalised. See documentation/DATA_IMPORT.md for the
-- ALTER TABLE steps to align a legacy native-MySQL DB with this schema
-- before importing its data.
-- ─────────────────────────────────────────────────────────────────────────────

SET NAMES 'UTF8MB4';

-- ── Users & auth ─────────────────────────────────────────────────────────────

CREATE TABLE users
(
    id         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
    first_name VARCHAR(50)     NOT NULL,
    last_name  VARCHAR(50)     NOT NULL,
    email      VARCHAR(100)    NOT NULL,
    password   VARCHAR(255) DEFAULT NULL,
    address    VARCHAR(255) DEFAULT NULL,
    phone      VARCHAR(30)  DEFAULT NULL,
    title      VARCHAR(50)  DEFAULT NULL,
    bio        VARCHAR(255) DEFAULT NULL,
    enabled    BOOLEAN      DEFAULT FALSE,
    non_locked BOOLEAN      DEFAULT TRUE,
    using_mfa  BOOLEAN      DEFAULT FALSE,
    created_at          DATETIME     DEFAULT CURRENT_TIMESTAMP,
    password_changed_at DATETIME     DEFAULT NULL,
    image_url           VARCHAR(255) DEFAULT 'https://cdn-icons-png.flaticon.com/512/149/149071.png',
    CONSTRAINT UQ_Users_Email UNIQUE (email)
);

CREATE TABLE roles
(
    id         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
    name       VARCHAR(50)     NOT NULL,
    permission VARCHAR(255)    NOT NULL,
    CONSTRAINT UQ_Roles_Name UNIQUE (name)
);

-- Catalog/reference data: roles are required for the security layer to function,
-- so they ship inside V1 (NOT user data — these rows exist in every environment).
INSERT INTO roles (name, permission)
VALUES ('ROLE_USER', 'READ:USER, READ:CUSTOMER'),
       ('ROLE_MANAGER', 'READ:USER, READ:CUSTOMER, UPDATE:USER, UPDATE:CUSTOMER'),
       ('ROLE_ADMIN', 'READ:USER, READ:CUSTOMER, CREATE:USER, CREATE:CUSTOMER, UPDATE:USER, UPDATE:CUSTOMER'),
       ('ROLE_HELP_DESK_ADMIN',
        'READ:USER, READ:CUSTOMER, CREATE:USER, CREATE:CUSTOMER, UPDATE:USER, UPDATE:CUSTOMER, DELETE:USER, DELETE:CUSTOMER');

CREATE TABLE userroles
(
    id      BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT UNSIGNED NOT NULL,
    role_id BIGINT UNSIGNED NOT NULL,
    FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE ON UPDATE CASCADE,
    FOREIGN KEY (role_id) REFERENCES roles (id) ON DELETE RESTRICT ON UPDATE CASCADE,
    CONSTRAINT UQ_UserRoles_User_Id UNIQUE (user_id)
);

-- ── Audit / event log ────────────────────────────────────────────────────────

CREATE TABLE events
(
    id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
    type        VARCHAR(50)     NOT NULL CHECK (type IN
                                                ('LOGIN_ATTEMPT', 'LOGIN_ATTEMPT_FAILURE', 'LOGIN_ATTEMPT_SUCCESS',
                                                 'PROFILE_UPDATE', 'PROFILE_PICTURE_UPDATE', 'ROLE_UPDATE',
                                                 'ACCOUNT_SETTINGS_UPDATE', 'PASSWORD_UPDATE', 'MFA_UPDATE')),
    description VARCHAR(255)    NOT NULL,
    CONSTRAINT UQ_Events_Type UNIQUE (type)
);

-- Reference data: event-type catalog ships with the schema for the same reason as roles.
INSERT INTO events (type, description)
VALUES ('LOGIN_ATTEMPT', 'You tried to log-in :)'),
       ('LOGIN_ATTEMPT_SUCCESS', 'You attempted to log-in and you succeeded :)'),
       ('LOGIN_ATTEMPT_FAILURE', 'You tried to log-in, but you failed to do so :('),
       ('PROFILE_UPDATE', 'You have updated your profile information :)'),
       ('PROFILE_PICTURE_UPDATE', 'You have updated your profile picture :)'),
       ('ROLE_UPDATE', 'You have updated your role and permissions :)'),
       ('ACCOUNT_SETTINGS_UPDATE', 'You have updated your account settings :)'),
       ('PASSWORD_UPDATE', 'You have updated your password successfully :)'),
       ('MFA_UPDATE', 'You have updated your multi-factor authentication settings :)');

CREATE TABLE userevents
(
    id         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
    user_id    BIGINT UNSIGNED NOT NULL,
    event_id   BIGINT UNSIGNED NOT NULL,
    device     VARCHAR(100) DEFAULT NULL,
    ip_address VARCHAR(100) DEFAULT NULL,
    created_at DATETIME     DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE ON UPDATE CASCADE,
    FOREIGN KEY (event_id) REFERENCES events (id) ON DELETE RESTRICT ON UPDATE CASCADE
);

-- ── Verification tokens ──────────────────────────────────────────────────────

CREATE TABLE accountverifications
(
    id      BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT UNSIGNED NOT NULL,
    url     VARCHAR(255)    NOT NULL,
    FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE ON UPDATE CASCADE,
    CONSTRAINT UQ_AccountVerifications_User_Id UNIQUE (user_id),
    CONSTRAINT UQ_AccountVerifications_Url UNIQUE (url)
);

-- Note: name was 'ResetPasswordVerifications' in the legacy schema; normalised to
-- all-lowercase here for cross-platform safety (Linux MySQL is case-sensitive,
-- Windows is not — having identifiers that only differ by case is a portability hazard).
CREATE TABLE resetpasswordverifications
(
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
    user_id         BIGINT UNSIGNED NOT NULL,
    url             VARCHAR(255)    NOT NULL,
    expiration_date DATETIME        NOT NULL,
    FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE ON UPDATE CASCADE,
    CONSTRAINT UQ_ResetPasswordVerifications_User_Id UNIQUE (user_id),
    CONSTRAINT UQ_ResetPasswordVerifications_Url UNIQUE (url)
);

CREATE TABLE twofactorverifications
(
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
    user_id         BIGINT UNSIGNED NOT NULL,
    code            VARCHAR(10)     NOT NULL,
    expiration_date DATETIME        NOT NULL,
    FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE ON UPDATE CASCADE,
    CONSTRAINT UQ_TwoFactorVerifications_User_Id UNIQUE (user_id),
    CONSTRAINT UQ_TwoFactorVerifications_Code UNIQUE (code)
);

-- ── JPA-managed business tables ──────────────────────────────────────────────
-- These are the tables that Hibernate USED to auto-generate via ddl-auto: update.
-- Locking them down in this migration means Hibernate now only validates them.
-- Column types/widths match what Hibernate produced, so existing native-MySQL
-- dumps can be imported with --no-create-info into this schema without changes.

CREATE TABLE customer
(
    id            BIGINT          NOT NULL AUTO_INCREMENT PRIMARY KEY,
    customer_name VARCHAR(255) DEFAULT NULL,
    type          VARCHAR(255) DEFAULT NULL,
    email         VARCHAR(255) DEFAULT NULL,
    phone_number  VARCHAR(255) DEFAULT NULL,
    address       VARCHAR(255) DEFAULT NULL,
    status        VARCHAR(255) DEFAULT NULL,
    image_url     VARCHAR(255) DEFAULT NULL,
    created_at    DATETIME(6)  DEFAULT NULL
);

CREATE TABLE invoice
(
    id             BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    invoice_number VARCHAR(255) DEFAULT NULL,
    amount         DOUBLE       DEFAULT NULL,
    status         VARCHAR(255) DEFAULT NULL,
    customer_id    BIGINT       DEFAULT NULL,
    invoice_date   DATETIME(6)  DEFAULT NULL,
    total_amount   DOUBLE       DEFAULT NULL,
    -- `customer` here is the JPA-managed FK column produced by @JoinColumn(name="customer")
    -- on Invoice.customer. It's deliberately distinct from the denormalised `customer_id`
    -- column above, which exists for direct SQL queries that don't go through JPA.
    customer       BIGINT       NOT NULL,
    FOREIGN KEY (customer) REFERENCES customer (id)
);

CREATE TABLE services
(
    id          BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    name        VARCHAR(255) DEFAULT NULL,
    description VARCHAR(255) DEFAULT NULL,
    price       DOUBLE       DEFAULT NULL
);

-- Owned by Invoice via @ElementCollection — no PK, deleted with parent invoice.
CREATE TABLE invoiceserviceitems
(
    invoice_id BIGINT       NOT NULL,
    name       VARCHAR(255) DEFAULT NULL,
    price      DOUBLE       DEFAULT NULL,
    FOREIGN KEY (invoice_id) REFERENCES invoice (id)
);
