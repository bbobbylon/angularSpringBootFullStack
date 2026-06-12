-- V3 — Federated identity support (SRS §4.3 FR-FED, DB-6).
--
-- 1. oauthproviderlinks: links a local user to an external identity. Per FR-FED-6 the
--    system stores ONLY the provider name and the provider's stable subject identifier —
--    never a third-party password or long-lived provider credential. The composite
--    UNIQUE(provider, provider_subject) is what makes "find-or-create" idempotent: the
--    same federated identity always resolves to the same local user.
--
-- 2. events catalog: adds the FEDERATED_LOGIN event type (FR-FED-5) so federated sign-ins
--    are auditable alongside in-house logins. The events.type column carries an inline
--    CHECK constraint from the original schema; MySQL auto-named it events_chk_1 (both the
--    baseline V1 and the legacy schema.sql created it identically, so the name is stable
--    across environments). It is rebuilt here under an explicit name so future additions
--    can reference CK_Events_Type instead of guessing.

CREATE TABLE IF NOT EXISTS oauthproviderlinks
(
    id               BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
    user_id          BIGINT UNSIGNED NOT NULL,
    provider         VARCHAR(30)     NOT NULL,
    provider_subject VARCHAR(255)    NOT NULL,
    created_at       DATETIME DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE ON UPDATE CASCADE,
    CONSTRAINT UQ_OAuthProviderLinks_Provider_Subject UNIQUE (provider, provider_subject)
);

ALTER TABLE events
    DROP CHECK events_chk_1;

ALTER TABLE events
    ADD CONSTRAINT CK_Events_Type CHECK (type IN
                                         ('LOGIN_ATTEMPT', 'LOGIN_ATTEMPT_FAILURE', 'LOGIN_ATTEMPT_SUCCESS',
                                          'PROFILE_UPDATE', 'PROFILE_PICTURE_UPDATE', 'ROLE_UPDATE',
                                          'ACCOUNT_SETTINGS_UPDATE', 'PASSWORD_UPDATE', 'MFA_UPDATE',
                                          'FEDERATED_LOGIN'));

INSERT INTO events (type, description)
VALUES ('FEDERATED_LOGIN', 'You logged in with a federated identity provider :)');
