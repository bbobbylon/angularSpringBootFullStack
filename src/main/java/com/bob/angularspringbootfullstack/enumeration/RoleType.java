package com.bob.angularspringbootfullstack.enumeration;

/**
 * RoleType defines the available user roles in the system.
 * 
 * Each enum value represents a role that can be assigned to users
 * for role-based access control (RBAC). These roles are used by Spring Security
 * to enforce authorization policies.
 *
 * Role descriptions:
 * - ROLE_USER: Standard user with basic permissions
 * - ROLE_ADMIN: Administrator with full system access
 * - ROLE_HELP_DESK_ADMIN: Help desk specialist with support-focused permissions
 * - ROLE_GUEST: Guest user with minimal/read-only permissions
 * - ROLE_MODERATOR: Content moderator with moderation permissions
 * - ROLE_ORGANIZATION_ADMIN: Administrator for organization-level resources
 * - ROLE_APPLICATION_ADMIN: Application-level administrator
 */
public enum RoleType {
    /** Standard user with basic permissions */
    ROLE_USER,
    /** Administrator with full system access */
    ROLE_ADMIN,
    /** Help desk specialist role */
    ROLE_HELP_DESK_ADMIN,
    /** Guest user with limited permissions */
    ROLE_GUEST,
    /** Content moderator role */
    ROLE_MODERATOR,
    /** Organization administrator role */
    ROLE_ORGANIZATION_ADMIN,
    /** Application administrator role */
    ROLE_APPLICATION_ADMIN
}
