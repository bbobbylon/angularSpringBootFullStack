package com.bob.angularspringbootfullstack.query;

/**
 * SQL constants for organization-scoped authorization (SRS §4.6 FR-ORG, DB-4/DB-5),
 * consumed by {@code OrganizationRepoImpl} through {@code NamedParameterJdbcTemplate},
 * following the same centralized-query convention as {@link UserQuery}.
 *
 * <p>All three queries share one scoping rule: two users are "in scope" of each other
 * when they hold ACTIVE memberships ({@code userorganizations.active = TRUE} on both
 * sides) in at least one common organization. The organization's own status is not
 * consulted here — deactivating memberships is the operational lever; retiring an org
 * flips its rows inactive.
 */
public class OrganizationQuery {

    /**
     * Counts the common active organizations between two users; a result > 0 means the
     * target is within the administrator's organization scope (FR-ORG-2).
     * Parameters: adminId, targetId.
     */
    public static final String COUNT_SHARED_ACTIVE_ORGANIZATIONS_QUERY =
            "SELECT COUNT(*) FROM userorganizations a " +
            "JOIN userorganizations b ON a.organization_id = b.organization_id " +
            "WHERE a.user_id = :adminId AND b.user_id = :targetId AND a.active = TRUE AND b.active = TRUE";

    /**
     * Lists the organization ids a user actively belongs to — the tenant filter for org-scoped
     * <em>reporting</em> (FR-ORG-2).
     *
     * <p>The other queries in this class answer "are these two users in scope of each other?",
     * which suits per-user administrative actions. Aggregates need the complementary shape: the
     * set of organizations to restrict rows to, so the filter can be pushed into the SQL that does
     * the counting rather than applied after the fact. Summing system-wide totals and then trying
     * to subtract what the caller may not see is not possible — a total carries no attribution —
     * so the scope has to reach the {@code WHERE} clause.
     *
     * <p>Returns an empty set for a user with no active memberships, which callers must treat as
     * "sees nothing" rather than "sees everything". Parameter: userId.
     */
    public static final String SELECT_ACTIVE_ORGANIZATION_IDS_BY_USER_QUERY =
            "SELECT organization_id FROM userorganizations WHERE user_id = :userId AND active = TRUE";

    /**
     * The org-scoped variant of {@link UserQuery#SELECT_USERS_PAGED_QUERY}: pages through
     * only the users who share an active organization with the administrator, with the
     * same free-text LIKE filter and newest-first ordering, so the admin directory looks
     * identical to an org admin — just smaller (FR-ADMIN-1 within FR-ORG-2 scope).
     * The administrator themselves naturally appears (they trivially share their own
     * orgs), which is correct: they may view, but the controller refuses self-mutation.
     * Parameters: adminId, searchTerm (pre-wrapped in %), pageSize, offset.
     */
    public static final String SELECT_USERS_SHARING_ORGANIZATIONS_PAGED_QUERY =
            "SELECT DISTINCT u.* FROM users u " +
            "JOIN userorganizations b ON b.user_id = u.id AND b.active = TRUE " +
            "JOIN userorganizations a ON a.organization_id = b.organization_id AND a.user_id = :adminId AND a.active = TRUE " +
            "WHERE (u.first_name LIKE :searchTerm OR u.last_name LIKE :searchTerm OR u.email LIKE :searchTerm) " +
            "ORDER BY %s LIMIT :pageSize OFFSET :offset";

    /**
     * Counts the rows {@link #SELECT_USERS_SHARING_ORGANIZATIONS_PAGED_QUERY} would
     * match, for total-pages metadata. Must stay filter-compatible with it.
     * Parameters: adminId, searchTerm (pre-wrapped in %).
     */
    public static final String COUNT_USERS_SHARING_ORGANIZATIONS_QUERY =
            "SELECT COUNT(DISTINCT u.id) FROM users u " +
            "JOIN userorganizations b ON b.user_id = u.id AND b.active = TRUE " +
            "JOIN userorganizations a ON a.organization_id = b.organization_id AND a.user_id = :adminId AND a.active = TRUE " +
            "WHERE (u.first_name LIKE :searchTerm OR u.last_name LIKE :searchTerm OR u.email LIKE :searchTerm)";

    /**
     * Lists every active organization's id and name — the iteration set the scheduled report
     * digest walks to send each organization its own org-scoped digest
     * (POST-SUBMISSION-UPGRADES.md "Scheduled/on-demand report emails"). No parameters.
     */
    public static final String SELECT_ACTIVE_ORGANIZATIONS_QUERY =
            "SELECT id, name FROM organizations WHERE status = 'ACTIVE' ORDER BY name";

    /**
     * Selects the email addresses of every {@code ROLE_ORGANIZATION_ADMIN} user holding an
     * ACTIVE membership in one organization — the recipient list for that organization's report
     * digest. Mirrors {@link UserQuery#SELECT_SYSTEM_ADMIN_EMAILS_QUERY}'s join shape, plus the
     * membership join every other query in this class uses. Parameter: organizationId.
     */
    public static final String SELECT_ORGANIZATION_ADMIN_EMAILS_QUERY =
            "SELECT DISTINCT u.email FROM users u " +
            "JOIN userroles ur ON ur.user_id = u.id " +
            "JOIN roles r ON r.id = ur.role_id " +
            "JOIN userorganizations uo ON uo.user_id = u.id AND uo.active = TRUE " +
            "WHERE r.name = 'ROLE_ORGANIZATION_ADMIN' AND uo.organization_id = :organizationId";
}
