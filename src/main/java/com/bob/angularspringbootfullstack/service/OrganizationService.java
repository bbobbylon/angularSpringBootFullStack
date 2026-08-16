package com.bob.angularspringbootfullstack.service;

import com.bob.angularspringbootfullstack.dto.UserDTO;
import com.bob.angularspringbootfullstack.model.OrganizationSummary;

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
     * Returns the ids of every organization the given user actively belongs to.
     *
     * <p>This is the tenant filter for org-scoped <em>aggregates</em> (FR-ORG-2), the complement to
     * {@link #isWithinOrganizationScope}: that method answers a yes/no question about one target,
     * which fits per-user administrative actions, while reporting needs the set of organizations up
     * front so the restriction can be pushed into the aggregating SQL. A total cannot be filtered
     * after it has been summed — the attribution is gone by then — so the scope has to be known
     * before the query runs.
     *
     * <p>An empty result means the caller belongs to no active organization and must therefore see
     * <em>nothing</em>. Callers must not fall back to unscoped data on empty; that would invert the
     * control and hand a membership-less admin the system-wide view.
     *
     * @param userId the administrator whose memberships bound the query
     * @return the active organization ids, possibly empty, never {@code null}
     */
    Collection<Long> findActiveOrganizationIds(Long userId);

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
     * @param orderBy    a validated, {@code u.}-qualified {@code "column ASC|DESC"} SQL fragment
     *                   (see {@code SortUtils#resolveSqlOrderBy}), e.g. {@code "u.created_at DESC, u.id DESC"} —
     *                   qualified because the query joins {@code userorganizations}, so an unqualified
     *                   column name could collide with one on the joined table
     * @return the in-scope users on the requested page, in the requested order
     */
    Collection<UserDTO> searchUsersSharingOrganizations(Long adminId, String searchTerm, int page, int pageSize, String orderBy);

    /**
     * Counts the users {@link #searchUsersSharingOrganizations} would match, for
     * total-pages metadata.
     *
     * @param adminId    the acting administrator's user id
     * @param searchTerm free-text filter; blank or null counts everyone in scope
     * @return the total number of in-scope matching users
     */
    long countUsersSharingOrganizations(Long adminId, String searchTerm);

    /**
     * Lists every active organization's id and name — the iteration set the scheduled report
     * digest walks to send each organization its own org-scoped digest
     * (POST-SUBMISSION-UPGRADES.md "Scheduled/on-demand report emails").
     *
     * @return every active organization, ordered by name
     */
    Collection<OrganizationSummary> findActiveOrganizations();

    /**
     * The email addresses of every {@code ROLE_ORGANIZATION_ADMIN} holding an active membership
     * in the given organization — the recipient list for that organization's report digest.
     *
     * @param organizationId the organization whose admins should receive the digest
     * @return the in-scope organization admins' emails, possibly empty, never {@code null}
     */
    Collection<String> findOrganizationAdminEmails(Long organizationId);
}
