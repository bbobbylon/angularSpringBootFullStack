-- V1 — Baseline: the pre-Flyway user-management schema (SRS DB-14 adoption point).
--
-- HOW THIS RUNS:
--   * Fresh/empty database  -> Flyway executes this file, creating the same tables that
--     schema.sql used to create manually, then applies V2+.
--   * Existing database (local, Docker, Aiven) -> spring.flyway.baseline-on-migrate marks
--     version 1 as already applied WITHOUT running this file, then applies V2+ on top of
--     the live schema. Nothing here is re-executed against existing data.
--
-- Scope note: only the user-management tables are baselined. The customer/invoice tables
-- are still created by Hibernate (ddl-auto: update) from the JPA entities, exactly as
-- before Flyway was introduced. Folding them into a migration is a follow-up.
--
-- Content is a faithful copy of resources/schema.sql at adoption time (no DROPs, and
-- IF NOT EXISTS guards so a partially initialized database does not fail the migration).

CREATE TABLE IF NOT EXISTS users
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

CREATE TABLE IF NOT EXISTS roles
(
    id         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
    name       VARCHAR(50)     NOT NULL,
    permission VARCHAR(255)    NOT NULL,
    CONSTRAINT UQ_Roles_Name UNIQUE (name)
);

-- Original (pre-SRS) role catalog. V2 reshapes this into the seven SRS roles;
-- it is seeded here unchanged so the migration history tells the true story.
INSERT INTO roles (name, permission)
VALUES ('ROLE_USER', 'READ:USER, READ:CUSTOMER'),
       ('ROLE_MANAGER', 'READ:USER, READ:CUSTOMER, UPDATE:USER, UPDATE:CUSTOMER'),
       ('ROLE_ADMIN', 'READ:USER, READ:CUSTOMER, CREATE:USER, CREATE:CUSTOMER, UPDATE:USER, UPDATE:CUSTOMER'),
       ('ROLE_HELP_DESK_ADMIN',
        'READ:USER, READ:CUSTOMER, CREATE:USER, CREATE:CUSTOMER, UPDATE:USER, UPDATE:CUSTOMER, DELETE:USER, DELETE:CUSTOMER');

CREATE TABLE IF NOT EXISTS userroles
(
    id      BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT UNSIGNED NOT NULL,
    role_id BIGINT UNSIGNED NOT NULL,
    FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE ON UPDATE CASCADE,
    FOREIGN KEY (role_id) REFERENCES roles (id) ON DELETE RESTRICT ON UPDATE CASCADE,
    CONSTRAINT UQ_UserRoles_User_Id UNIQUE (user_id)
);

CREATE TABLE IF NOT EXISTS events
(
    id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
    type        VARCHAR(50)     NOT NULL CHECK (type IN
                                                ('LOGIN_ATTEMPT', 'LOGIN_ATTEMPT_FAILURE', 'LOGIN_ATTEMPT_SUCCESS',
                                                 'PROFILE_UPDATE', 'PROFILE_PICTURE_UPDATE', 'ROLE_UPDATE',
                                                 'ACCOUNT_SETTINGS_UPDATE', 'PASSWORD_UPDATE', 'MFA_UPDATE')),
    description VARCHAR(255)    NOT NULL,
    CONSTRAINT UQ_Events_Type UNIQUE (type)
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
       ('MFA_UPDATE', 'You have updated your multi-factor authentication settings :)');

CREATE TABLE IF NOT EXISTS userevents
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

CREATE TABLE IF NOT EXISTS accountverifications
(
    id      BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT UNSIGNED NOT NULL,
    -- `url` stores a bare UUID verification key (NOT a full URL); see schema.sql notes.
    url     VARCHAR(255)    NOT NULL,
    FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE ON UPDATE CASCADE,
    CONSTRAINT UQ_AccountVerifications_User_Id UNIQUE (user_id),
    CONSTRAINT UQ_AccountVerifications_Url UNIQUE (url)
);

CREATE TABLE IF NOT EXISTS resetpasswordverifications
(
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
    user_id         BIGINT UNSIGNED NOT NULL,
    -- `url` stores a bare UUID verification key (NOT a full URL); see schema.sql notes.
    url             VARCHAR(255)    NOT NULL,
    expiration_date DATETIME        NOT NULL,
    FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE ON UPDATE CASCADE,
    CONSTRAINT UQ_ResetPasswordVerifications_User_Id UNIQUE (user_id),
    CONSTRAINT UQ_ResetPasswordVerifications_Url UNIQUE (url)
);

CREATE TABLE IF NOT EXISTS invoiceserviceitems
(
    id         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
    invoice_id BIGINT UNSIGNED NOT NULL,
    item_order INT             NOT NULL DEFAULT 0,
    name       VARCHAR(255)             DEFAULT NULL,
    price      DECIMAL(38, 2)           DEFAULT NULL
);

CREATE TABLE IF NOT EXISTS twofactorverifications
(
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
    user_id         BIGINT UNSIGNED NOT NULL,
    code            VARCHAR(10)     NOT NULL,
    expiration_date DATETIME        NOT NULL,
    FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE ON UPDATE CASCADE,
    CONSTRAINT UQ_TwoFactorVerifications_User_Id UNIQUE (user_id),
    CONSTRAINT UQ_TwoFactorVerifications_Code UNIQUE (code)
);
