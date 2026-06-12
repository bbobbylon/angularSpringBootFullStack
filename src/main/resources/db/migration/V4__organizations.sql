-- V4 — Organizations and membership (SRS §4.6 FR-ORG, DB-4/DB-5).
--
-- organizations: the scoping unit for ROLE_ORGANIZATION_ADMIN. status allows an org to
-- be retired (INACTIVE) without deleting history.
--
-- userorganizations: many-to-many membership with an active flag — the org-scope check
-- (FR-ORG-2) honors only ACTIVE memberships on BOTH sides, so deactivating a membership
-- immediately removes the user from every org admin's reach without destroying the row.
--
-- Seed data (OTH-1): a default 'Tessera' organization containing every existing user, so
-- an organization administrator demo works immediately; plus an empty 'Acme Partners'
-- org to demonstrate the scope boundary (a Tessera org admin must get HTTP 403 when
-- targeting any future Acme-only user). New self-registered users are NOT auto-enrolled;
-- enrollment is an administrative act (a future admin surface).

CREATE TABLE IF NOT EXISTS organizations
(
    id         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
    name       VARCHAR(100)    NOT NULL,
    status     VARCHAR(20)     NOT NULL DEFAULT 'ACTIVE',
    created_at DATETIME                 DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT UQ_Organizations_Name UNIQUE (name),
    CONSTRAINT CK_Organizations_Status CHECK (status IN ('ACTIVE', 'INACTIVE'))
);

CREATE TABLE IF NOT EXISTS userorganizations
(
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
    user_id         BIGINT UNSIGNED NOT NULL,
    organization_id BIGINT UNSIGNED NOT NULL,
    active          BOOLEAN         NOT NULL DEFAULT TRUE,
    created_at      DATETIME                 DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE ON UPDATE CASCADE,
    FOREIGN KEY (organization_id) REFERENCES organizations (id) ON DELETE CASCADE ON UPDATE CASCADE,
    CONSTRAINT UQ_UserOrganizations_User_Org UNIQUE (user_id, organization_id)
);

INSERT INTO organizations (name, status)
VALUES ('Tessera', 'ACTIVE'),
       ('Acme Partners', 'ACTIVE');

INSERT INTO userorganizations (user_id, organization_id, active)
SELECT u.id, o.id, TRUE
FROM users u
         CROSS JOIN organizations o
WHERE o.name = 'Tessera';
