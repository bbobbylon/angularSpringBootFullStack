-- V6 — Server-side refresh sessions: rotation, reuse detection, device management
-- (plan.md M5, SRS FR-JWT-5's "rotation with reuse detection"). This is the STATEFUL half
-- of the hybrid model: access tokens stay stateless (verified with no DB hit, NFR-PERF-2),
-- while refresh tokens become server-tracked so sessions are listable and revocable.
--
-- Vocabulary:
--   * family — one logical session (one browser/device login). Stable across rotations;
--     it is the id the UI shows and the unit of revocation.
--   * jti    — one concrete refresh token within a family. Each refresh mints a new jti
--     and marks the old row superseded; exactly one non-superseded row exists per live family.
--
-- Reuse detection: presenting a refresh token whose row is superseded (it was already
-- rotated) or revoked is the signature of token theft — either the attacker or the real
-- user is replaying an old token. The whole family is revoked, forcing a fresh first-factor
-- login, and a TOKEN_REUSE_DETECTED audit event records it.
--
-- Rows are retained after supersession/revocation (not deleted) BECAUSE reuse detection
-- depends on recognizing old tokens; expiry (expires_at) bounds the retention window.

CREATE TABLE IF NOT EXISTS refreshsessions
(
    id           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
    user_id      BIGINT UNSIGNED NOT NULL,
    family       CHAR(36)        NOT NULL,
    jti          CHAR(36)        NOT NULL,
    device       VARCHAR(100) DEFAULT NULL,
    ip_address   VARCHAR(100) DEFAULT NULL,
    created_at   DATETIME     DEFAULT CURRENT_TIMESTAMP,
    last_used_at DATETIME     DEFAULT CURRENT_TIMESTAMP,
    expires_at   DATETIME        NOT NULL,
    revoked      BOOLEAN      DEFAULT FALSE,
    superseded   BOOLEAN      DEFAULT FALSE,
    FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE ON UPDATE CASCADE,
    CONSTRAINT UQ_RefreshSessions_Jti UNIQUE (jti)
);

CREATE INDEX IX_RefreshSessions_User_Id ON refreshsessions (user_id);
CREATE INDEX IX_RefreshSessions_Family ON refreshsessions (family);

ALTER TABLE events
    DROP CHECK CK_Events_Type;

ALTER TABLE events
    ADD CONSTRAINT CK_Events_Type CHECK (type IN
                                         ('LOGIN_ATTEMPT', 'LOGIN_ATTEMPT_FAILURE', 'LOGIN_ATTEMPT_SUCCESS',
                                          'PROFILE_UPDATE', 'PROFILE_PICTURE_UPDATE', 'ROLE_UPDATE',
                                          'ACCOUNT_SETTINGS_UPDATE', 'PASSWORD_UPDATE', 'MFA_UPDATE',
                                          'FEDERATED_LOGIN',
                                          'TOTP_ENROLLED', 'TOTP_DISABLED', 'RECOVERY_CODE_USED',
                                          'SESSION_REVOKED', 'TOKEN_REUSE_DETECTED'));

INSERT INTO events (type, description)
VALUES ('SESSION_REVOKED', 'You revoked an active session on your account :)'),
       ('TOKEN_REUSE_DETECTED', 'A previously used refresh token was replayed; the affected session family was revoked for your security :|');
