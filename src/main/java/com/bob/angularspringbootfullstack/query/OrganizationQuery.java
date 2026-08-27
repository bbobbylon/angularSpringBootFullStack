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

    // ── Organization CRUD + membership management (2026-08-21, FUTURE-ENHANCEMENTS.md §3.2) ──

    /**
     * Inserts a new organization, always starting {@code ACTIVE} — there is no "create inactive"
     * case, since nothing can be a member of an org before it exists. {@code organizations.name}
     * carries a unique constraint, so a collision surfaces as {@code DuplicateKeyException}.
     * Parameter: name.
     */
    public static final String INSERT_ORGANIZATION_QUERY =
            "INSERT INTO organizations (name, status) VALUES (:name, 'ACTIVE')";

    /**
     * Lists every organization regardless of status, for the unscoped-tier catalog view
     * (Organization CRUD). No parameters.
     */
    public static final String SELECT_ALL_ORGANIZATIONS_QUERY =
            "SELECT * FROM organizations ORDER BY id";

    /**
     * Lists only the organizations whose id is in the given set, for an org-scoped caller's
     * (own-organization-only) catalog view. Parameter: ids (a {@link java.util.Collection} of
     * organization ids; an empty collection is the caller's responsibility to short-circuit
     * before calling, matching every other "empty scope" convention in this codebase).
     */
    public static final String SELECT_ORGANIZATIONS_BY_IDS_QUERY =
            "SELECT * FROM organizations WHERE id IN (:ids) ORDER BY id";

    /**
     * Fetches a single organization by its own id (Organization CRUD). Parameter: id.
     */
    public static final String SELECT_ORGANIZATION_BY_ID_QUERY =
            "SELECT * FROM organizations WHERE id = :id";

    /**
     * Renames an organization (Organization CRUD — edit). {@code status} is untouched; see
     * {@link #UPDATE_ORGANIZATION_STATUS_QUERY} for that. Parameters: name, id.
     */
    public static final String UPDATE_ORGANIZATION_NAME_QUERY =
            "UPDATE organizations SET name = :name WHERE id = :id";

    /**
     * Activates or deactivates an organization (Organization CRUD — the retirement lever; see
     * {@link com.bob.angularspringbootfullstack.model.Organization}'s Javadoc for why this,
     * not a hard delete, is how an organization is retired). Parameters: status, id.
     */
    public static final String UPDATE_ORGANIZATION_STATUS_QUERY =
            "UPDATE organizations SET status = :status WHERE id = :id";

    /**
     * Whether the given user holds an ACTIVE membership in the given organization — the
     * predicate {@code OrganizationController} uses to let a {@code ROLE_ORGANIZATION_ADMIN}
     * manage membership of their own organization (but no other) without needing an unscoped
     * tier. Unlike {@link #COUNT_SHARED_ACTIVE_ORGANIZATIONS_QUERY}, which asks "do these two
     * users share an org", this asks "does this one user belong to this one org" — the question
     * that matters when the target of the action is the organization itself, not another user.
     * Parameters: userId, organizationId.
     */
    public static final String COUNT_ACTIVE_MEMBERSHIP_QUERY =
            "SELECT COUNT(*) FROM userorganizations " +
            "WHERE user_id = :userId AND organization_id = :organizationId AND active = TRUE";

    /**
     * Adds a user to an organization for the first time. {@code UQ_UserOrganizations_User_Org}
     * means a user previously removed and re-added to the <em>same</em> organization already has
     * a row (just an inactive one), so this throws {@code DuplicateKeyException} on that case —
     * {@code OrganizationServiceImpl#addMember} catches it and falls back to
     * {@link #REACTIVATE_MEMBERSHIP_QUERY} rather than this being a single
     * {@code INSERT ... ON DUPLICATE KEY UPDATE}: that idiom's {@code UPDATE <column>} clause is
     * indistinguishable, to {@code SqlTableCaseConsistencyTest}'s table-reference scan, from an
     * {@code UPDATE <table>} statement naming a table this schema doesn't have. Parameters:
     * userId, organizationId.
     */
    public static final String INSERT_MEMBERSHIP_QUERY =
            "INSERT INTO userorganizations (user_id, organization_id, org_role, active) " +
            "VALUES (:userId, :organizationId, :orgRole, TRUE)";

    /**
     * Reactivates a membership row {@link #INSERT_MEMBERSHIP_QUERY} could not insert because one
     * already existed — the fallback for a user who was previously removed
     * ({@link #DEACTIVATE_MEMBERSHIP_QUERY}) and is now being re-added to the same organization.
     * Parameters: userId, organizationId.
     */
    public static final String REACTIVATE_MEMBERSHIP_QUERY =
            "UPDATE userorganizations SET active = TRUE, org_role = :orgRole " +
            "WHERE user_id = :userId AND organization_id = :organizationId";

    /**
     * Removes a user's membership in an organization by deactivating the row rather than
     * deleting it — same soft-removal shape as every other membership/status lever in this
     * codebase, and it preserves the row {@link #UPSERT_ACTIVE_MEMBERSHIP_QUERY} reactivates on
     * re-add instead of re-inserting. Parameters: userId, organizationId.
     */
    public static final String DEACTIVATE_MEMBERSHIP_QUERY =
            "UPDATE userorganizations SET active = FALSE " +
            "WHERE user_id = :userId AND organization_id = :organizationId";

    /**
     * Lists every user holding an ACTIVE membership in one organization — the read side
     * {@code addMember}/{@code removeMember} lacked until now: without it, an admin picking a
     * member to remove would have nothing to choose from. Ordered by name rather than join date
     * since the caller is scanning for a person, not auditing history. Parameter: organizationId.
     */
    public static final String SELECT_ACTIVE_MEMBERS_QUERY =
            "SELECT u.* FROM users u " +
            "JOIN userorganizations uo ON uo.user_id = u.id " +
            "WHERE uo.organization_id = :organizationId AND uo.active = TRUE " +
            "ORDER BY u.first_name, u.last_name";

    /**
     * Counts the ACTIVE members of one organization — the {@code memberCount} tile on the
     * dashboard-style Organizations page. Parameter: organizationId.
     */
    public static final String COUNT_ACTIVE_MEMBERS_QUERY =
            "SELECT COUNT(*) FROM userorganizations WHERE organization_id = :organizationId AND active = TRUE";

    // ── Per-organization roles (2026-08-26, TODO(org-roles)) ────────────────────────────────

    /**
     * The caller's capacity within one organization — the single row
     * {@code OrganizationServiceImpl#findOrgRole} reads to answer "may this member administer
     * <em>this</em> organization?".
     * <p>
     * Restricted to ACTIVE memberships deliberately: a deactivated row keeps its {@code org_role}
     * so that re-adding a former administrator does not silently lose what they were, but an
     * inactive membership must never satisfy an authorization check. Returning no row for it is
     * what makes the service's {@code Optional.empty()} mean "not a member here", which every
     * caller treats as denial.
     * Parameters: userId, organizationId.
     */
    public static final String SELECT_ORG_ROLE_QUERY =
            "SELECT org_role FROM userorganizations " +
            "WHERE user_id = :userId AND organization_id = :organizationId AND active = TRUE";

    /**
     * Every organization the user actively belongs to, paired with the capacity they hold in it —
     * the {@code getOrgRolesForUser} projection the {@code TODO(org-roles)} design called for.
     * <p>
     * Complements {@link #SELECT_ACTIVE_ORGANIZATION_IDS_BY_USER_QUERY}, which answers the narrower
     * "which organizations" question that data scoping needs: this one is for surfaces that must
     * also render or reason about the capacity, and it exists so those callers do not issue one
     * {@link #SELECT_ORG_ROLE_QUERY} per organization.
     * Parameter: userId.
     */
    public static final String SELECT_ORG_ROLES_BY_USER_QUERY =
            "SELECT organization_id, org_role FROM userorganizations " +
            "WHERE user_id = :userId AND active = TRUE " +
            "ORDER BY organization_id";

    /**
     * Reassigns one member's capacity within one organization. Scoped to an ACTIVE membership so a
     * caller cannot promote somebody who was removed from the organization back into relevance
     * without going through {@code addMember} — which is the path that re-activates the row and
     * records an audit event.
     * Parameters: orgRole, userId, organizationId.
     */
    public static final String UPDATE_ORG_ROLE_QUERY =
            "UPDATE userorganizations SET org_role = :orgRole " +
            "WHERE user_id = :userId AND organization_id = :organizationId AND active = TRUE";

    /**
     * Lists one organization's active members together with the capacity each holds — the
     * {@code SELECT_ACTIVE_MEMBERS_QUERY} roster, extended with the column the Members tab needs to
     * render a per-member role selector. Kept as a separate constant rather than widening that one:
     * its {@code SELECT u.*} feeds a {@code UserRowMapper}, and appending a column that is not a
     * {@code users} column would break that mapper's contract.
     * Parameter: organizationId.
     */
    public static final String SELECT_ACTIVE_MEMBERS_WITH_ROLE_QUERY =
            "SELECT u.id AS user_id, uo.org_role AS org_role FROM users u " +
            "JOIN userorganizations uo ON uo.user_id = u.id " +
            "WHERE uo.organization_id = :organizationId AND uo.active = TRUE " +
            "ORDER BY u.first_name, u.last_name";

    /**
     * Counts the ACTIVE {@code ORG_ADMIN} members of an organization <em>other than</em> one given
     * user — the last-administrator guard.
     * <p>
     * Demoting or removing the only administrator of an organization would leave it with no member
     * able to manage its membership, recoverable solely by an unscoped platform tier. That is a
     * support ticket waiting to happen, so both paths consult this first and refuse when it returns
     * zero.
     * Parameters: organizationId, userId (the member being demoted or removed, excluded from the count).
     */
    public static final String COUNT_OTHER_ACTIVE_ORG_ADMINS_QUERY =
            "SELECT COUNT(*) FROM userorganizations " +
            "WHERE organization_id = :organizationId AND active = TRUE " +
            "AND org_role = 'ORG_ADMIN' AND user_id <> :userId";

    // ── Organization profile/settings (2026-08-22, dashboard revamp) ─────────────────────────

    /**
     * Updates an organization's profile fields (Organization CRUD — profile/settings). All three
     * are nullable, so binding must go through a null-tolerant {@code MapSqlParameterSource}
     * rather than {@code Map.of} (which rejects null values). Parameters: description,
     * contactEmail, website, id.
     */
    public static final String UPDATE_ORGANIZATION_PROFILE_QUERY =
            "UPDATE organizations SET description = :description, contact_email = :contactEmail, website = :website WHERE id = :id";

    // ── Organization-level audit trail (2026-08-22) ───────────────────────────────────────────

    /**
     * Inserts an organization audit entry. Mirrors {@code EventQuery#INSERT_EVENT_WITH_DETAIL_BY_EMAIL_QUERY}'s
     * correlated-subquery shape for resolving {@code event_id} from a type name. {@code actorUserId}
     * and {@code detail} are both nullable, so binding must go through a null-tolerant
     * {@code MapSqlParameterSource}. Parameters: organizationId, actorUserId, type, detail.
     */
    public static final String INSERT_ORGANIZATION_EVENT_QUERY =
            "INSERT INTO organizationevents (organization_id, actor_user_id, event_id, detail) " +
            "VALUES (:organizationId, :actorUserId, (SELECT id FROM events WHERE type = :type), :detail)";

    /**
     * Fetches one page of an organization's audit trail, newest first, resolving the acting
     * administrator's email (nullable — the account may since have been deleted) and the event's
     * human-readable type/description from the shared {@code events} catalog. Parameters:
     * organizationId, size, offset.
     */
    public static final String SELECT_ORGANIZATION_EVENTS_PAGINATED_QUERY =
            "SELECT oev.id, ev.type, ev.description, u.email AS actor_email, oev.detail, oev.created_at " +
            "FROM organizationevents oev " +
            "JOIN events ev ON ev.id = oev.event_id " +
            "LEFT JOIN users u ON u.id = oev.actor_user_id " +
            "WHERE oev.organization_id = :organizationId " +
            "ORDER BY oev.created_at DESC LIMIT :size OFFSET :offset";

    /**
     * Counts an organization's audit entries, for total-pages metadata. Parameter: organizationId.
     */
    public static final String COUNT_ORGANIZATION_EVENTS_QUERY =
            "SELECT COUNT(*) FROM organizationevents WHERE organization_id = :organizationId";

    // ── Organization invites (2026-08-22, self-service member onboarding) ───────────────────

    /**
     * Creates a pending invite. {@code organizationinvites.code} carries a unique constraint, so a
     * (practically impossible) UUID collision surfaces as {@code DuplicateKeyException}. Parameters:
     * organizationId, invitedByUserId, code, roleName, expirationDate.
     */
    public static final String INSERT_ORGANIZATION_INVITE_QUERY =
            "INSERT INTO organizationinvites (organization_id, invited_by_user_id, code, role_name, expiration_date) " +
            "VALUES (:organizationId, :invitedByUserId, :code, :roleName, :expirationDate)";

    /**
     * Resolves an invite by its code, joined to the inviting administrator's email. Does not filter
     * on expiry — {@code OrganizationServiceImpl#redeemInvite} checks {@code expirationDate}
     * itself so an expired invite can be told apart from one that never existed and deleted either
     * way. Parameter: code.
     */
    public static final String SELECT_INVITE_BY_CODE_QUERY =
            "SELECT oi.*, u.email AS invited_by_email FROM organizationinvites oi " +
            "JOIN users u ON u.id = oi.invited_by_user_id WHERE oi.code = :code";

    /**
     * Lists every outstanding (not-yet-expired) invite for one organization, newest first, for the
     * Organizations page's Invites panel. Parameter: organizationId.
     */
    public static final String SELECT_ACTIVE_INVITES_QUERY =
            "SELECT oi.*, u.email AS invited_by_email FROM organizationinvites oi " +
            "JOIN users u ON u.id = oi.invited_by_user_id " +
            "WHERE oi.organization_id = :organizationId AND oi.expiration_date > NOW() " +
            "ORDER BY oi.created_at DESC";

    /**
     * Deletes an invite by its primary key — used both for an explicit revoke and, via
     * {@link #DELETE_INVITE_BY_CODE_QUERY}, for consuming it on redemption. Single-use is enforced
     * by deletion rather than a {@code used} flag, matching {@code resetpasswordverifications}'
     * convention. Parameters: id, organizationId (scopes a revoke to the caller's own organization
     * rather than trusting the path id alone).
     */
    public static final String DELETE_INVITE_BY_ID_QUERY =
            "DELETE FROM organizationinvites WHERE id = :id AND organization_id = :organizationId";

    /**
     * Deletes an invite by its code — the redemption path, where the caller does not yet know (or
     * need) the row's primary key. Parameter: code.
     */
    public static final String DELETE_INVITE_BY_CODE_QUERY =
            "DELETE FROM organizationinvites WHERE code = :code";
}
