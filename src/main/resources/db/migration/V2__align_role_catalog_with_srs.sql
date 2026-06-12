-- V2 — Align the role catalog with SRS §2.3 (seven roles) and FR-RBAC-1.
--
-- The pre-SRS catalog had four roles (ROLE_USER, ROLE_MANAGER, ROLE_ADMIN,
-- ROLE_HELP_DESK_ADMIN) and granted no one the UPDATE:ROLE authority — even though
-- SecurityConfig already gated role reassignment on it. This migration:
--
--   1. Renames ROLE_MANAGER -> ROLE_MODERATOR in place, preserving every existing
--      userroles assignment (the FK references roles.id, which does not change).
--   2. Rewrites permission strings to the SRS grants. Notably ROLE_HELP_DESK_ADMIN
--      is DOWNGRADED from the old top-tier grant (it had DELETE:USER/DELETE:CUSTOMER)
--      to support-focused reads/updates, per the SRS role table.
--   3. Adds the three missing roles: ROLE_GUEST, ROLE_ORGANIZATION_ADMIN,
--      ROLE_APPLICATION_ADMIN. The insert is idempotent (ON DUPLICATE KEY against
--      UQ_Roles_Name) so environments that were patched by hand converge too.
--
-- Authority semantics (consumed by SecurityConfig + @PreAuthorize):
--   UPDATE:ROLE  -> may reassign other users' roles (admin tiers only)
--   UPDATE:USER  -> may update other users' accounts/state (staff tiers)
--   DELETE:*     -> destructive operations (top admin tiers only)

UPDATE roles SET name = 'ROLE_MODERATOR' WHERE name = 'ROLE_MANAGER';

UPDATE roles SET permission = 'READ:USER, READ:CUSTOMER'
WHERE name = 'ROLE_USER';

UPDATE roles SET permission = 'READ:USER, READ:CUSTOMER, UPDATE:CUSTOMER'
WHERE name = 'ROLE_MODERATOR';

UPDATE roles SET permission = 'READ:USER, READ:CUSTOMER, UPDATE:USER'
WHERE name = 'ROLE_HELP_DESK_ADMIN';

UPDATE roles SET permission = 'READ:USER, READ:CUSTOMER, CREATE:USER, CREATE:CUSTOMER, UPDATE:USER, UPDATE:CUSTOMER, UPDATE:ROLE, DELETE:USER'
WHERE name = 'ROLE_ADMIN';

INSERT INTO roles (name, permission)
VALUES ('ROLE_GUEST', 'READ:USER'),
       ('ROLE_ORGANIZATION_ADMIN', 'READ:USER, READ:CUSTOMER, UPDATE:USER, UPDATE:ROLE'),
       ('ROLE_APPLICATION_ADMIN',
        'READ:USER, READ:CUSTOMER, CREATE:USER, CREATE:CUSTOMER, UPDATE:USER, UPDATE:CUSTOMER, UPDATE:ROLE, DELETE:USER, DELETE:CUSTOMER')
AS new
ON DUPLICATE KEY UPDATE permission = new.permission;
