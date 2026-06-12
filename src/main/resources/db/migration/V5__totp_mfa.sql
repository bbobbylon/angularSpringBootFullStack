-- V5 — Authenticator-app (TOTP) multi-factor authentication (SRS §4.5 FR-MFA-4, plan.md M4).
--
-- 1. users.using_totp: denormalized flag mirroring the using_mfa pattern so the row mappers
--    and DTOs expose "has a confirmed authenticator" without a join on every user load.
--    The authoritative state remains the confirmed totpcredentials row; the two are kept
--    in lockstep by TotpServiceImpl inside one transaction.
--
-- 2. totpcredentials: one Base32-encoded RFC 6238 shared secret per user. A row is created
--    UNCONFIRMED at enrollment start and only flipped to confirmed once the user proves
--    possession of the authenticator by submitting a valid code — an unconfirmed secret can
--    never satisfy a login challenge.
--
-- 3. totprecoverycodes: single-use fallback codes stored as SHA-256 hex digests (the codes
--    are machine-generated with ~40 bits of entropy, so a fast hash is appropriate — BCrypt
--    exists to slow down guessing of LOW-entropy human passwords). used_at marks consumption;
--    rows are never updated back to NULL.
--
-- 4. mfachallenges: the server-side proof that the FIRST factor succeeded. Unlike the SMS
--    flow — where the code's very existence proves the password step happened because the
--    server only mints it after authentication — a TOTP code always exists on the user's
--    phone. Without this table, a public "verify TOTP" endpoint would let anyone holding
--    the authenticator (or a phished code) skip the password entirely. The login and
--    federated handlers insert a short-lived challenge row after first-factor success, and
--    the verify endpoint refuses any code not accompanied by a live challenge.
--
-- 5. events catalog: TOTP lifecycle + recovery-code usage become auditable (FR-AUDIT-1).
--    The CHECK constraint is rebuilt under its stable explicit name (established in V3).

ALTER TABLE users
    ADD COLUMN using_totp BOOLEAN DEFAULT FALSE;

CREATE TABLE IF NOT EXISTS totpcredentials
(
    id           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
    user_id      BIGINT UNSIGNED NOT NULL,
    secret       VARCHAR(64)     NOT NULL,
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
    code_hash  CHAR(64)        NOT NULL,
    used_at    DATETIME DEFAULT NULL,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE ON UPDATE CASCADE
);

CREATE TABLE IF NOT EXISTS mfachallenges
(
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
    user_id         BIGINT UNSIGNED NOT NULL,
    challenge       CHAR(36)        NOT NULL,
    expiration_date DATETIME        NOT NULL,
    created_at      DATETIME DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE ON UPDATE CASCADE,
    CONSTRAINT UQ_MfaChallenges_User_Id UNIQUE (user_id),
    CONSTRAINT UQ_MfaChallenges_Challenge UNIQUE (challenge)
);

ALTER TABLE events
    DROP CHECK CK_Events_Type;

ALTER TABLE events
    ADD CONSTRAINT CK_Events_Type CHECK (type IN
                                         ('LOGIN_ATTEMPT', 'LOGIN_ATTEMPT_FAILURE', 'LOGIN_ATTEMPT_SUCCESS',
                                          'PROFILE_UPDATE', 'PROFILE_PICTURE_UPDATE', 'ROLE_UPDATE',
                                          'ACCOUNT_SETTINGS_UPDATE', 'PASSWORD_UPDATE', 'MFA_UPDATE',
                                          'FEDERATED_LOGIN',
                                          'TOTP_ENROLLED', 'TOTP_DISABLED', 'RECOVERY_CODE_USED'));

INSERT INTO events (type, description)
VALUES ('TOTP_ENROLLED', 'You enrolled an authenticator app for multi-factor authentication :)'),
       ('TOTP_DISABLED', 'You removed your authenticator app from multi-factor authentication :)'),
       ('RECOVERY_CODE_USED', 'You signed in using a single-use recovery code :)');
