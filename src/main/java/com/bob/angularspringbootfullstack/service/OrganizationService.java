package com.bob.angularspringbootfullstack.service;

import com.bob.angularspringbootfullstack.dto.UserDTO;

import java.util.Collection;

/**
 * Business contract for organization-scoped authorization (SRS §4.6 FR-ORG-1..3).
 *
 * <p>Organizations bound what {@code ROLE_ORGANIZATION_ADMIN} can see and touch: such an
 * administrator may act only on users who share at least one ACTIVE organization
 * membership with them. {@code AdminUserController} consults this service before every
 * administrative read or mutation when the caller is org-scoped;
 * {@code ROLE_APPLICATION_ADMIN} and {@code ROLE_ADMIN} bypass it entirely (FR-ORG-3
 * names only the application admin, and the SRS scopes only the organization admin —
 * the global admin tier is unscoped by definition).
 */
public interface OrganizationService {

    /**
     * Whether the target user shares at least one active organization with the
     * administrator — the FR-ORG-2 scope predicate. A {@code false} answer must result
     * in HTTP 403 for any administrative action on the target.
     *
     * @param adminId  the acting administrator's user id
     * @param targetId the user id the administrator wants to act on
     * @return true when both users hold active memberships in a common organization
     */
    boolean isWithinOrganizationScope(Long adminId, Long targetId);

    /**
     * The organization-scoped user directory: pages through only the users sharing an
     * active organization with the administrator, with the same free-text filter and
     * DTO enrichment as the unscoped {@link UserService#searchUsers} so the admin
     * dashboard renders identically for both admin tiers.
     *
     * @param adminId    the acting administrator's user id
     * @param searchTerm free-text filter; blank or null lists everyone in scope
     * @param page       0-indexed page number
     * @param pageSize   rows per page
     * @return the in-scope users on the requested page, newest accounts first
     */
    Collection<UserDTO> searchUsersSharingOrganizations(Long adminId, String searchTerm, int page, int pageSize);

    /**
     * Counts the users {@link #searchUsersSharingOrganizations} would match, for
     * total-pages metadata.
     *
     * @param adminId    the acting administrator's user id
     * @param searchTerm free-text filter; blank or null counts everyone in scope
     * @return the total number of in-scope matching users
     */
    long countUsersSharingOrganizations(Long adminId, String searchTerm);
}
