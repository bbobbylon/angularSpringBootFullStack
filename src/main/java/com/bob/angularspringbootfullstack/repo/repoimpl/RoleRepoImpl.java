package com.bob.angularspringbootfullstack.repo.repoimpl;

import com.bob.angularspringbootfullstack.exception.ApiException;
import com.bob.angularspringbootfullstack.model.Role;
import com.bob.angularspringbootfullstack.repo.RoleRepo;
import com.bob.angularspringbootfullstack.rowmapper.RoleRowMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import static com.bob.angularspringbootfullstack.enumeration.RoleType.ROLE_USER;
import static com.bob.angularspringbootfullstack.query.RoleQuery.*;
import static java.util.Map.of;
import static java.util.Objects.requireNonNull;

/**
 * JDBC-based {@link com.bob.angularspringbootfullstack.repo.RoleRepo} implementation.
 *
 * <p>In this project roles hold the permission string used to construct authorities.
 *
 * <p>-----------------------------------------------------------------------
 * TODO(org-roles): Implement organization-scoped role system (real-world RBAC)
 * -----------------------------------------------------------------------
 *
 * <p><b>Schema additions required:</b>
 * <pre>
 *   organizations
 *     id          BIGINT PK AUTO_INCREMENT
 *     name        VARCHAR NOT NULL
 *     slug        VARCHAR UNIQUE NOT NULL   -- URL-safe identifier
 *     created_at  TIMESTAMP DEFAULT NOW()
 *
 *   user_organizations                       -- many-to-many junction
 *     user_id     BIGINT FK -> users.id
 *     org_id      BIGINT FK -> organizations.id
 *     org_role    VARCHAR NOT NULL           -- e.g. ORG_ADMIN, ORG_MEMBER, ORG_VIEWER
 *     status      VARCHAR DEFAULT 'ACTIVE'   -- ACTIVE | SUSPENDED | PENDING_INVITE
 *     joined_at   TIMESTAMP DEFAULT NOW()
 *     PRIMARY KEY (user_id, org_id)
 * </pre>
 *
 * <p><b>Role precedence (layered — org roles stack on top of global roles):</b>
 * <ol>
 *   <li>GLOBAL SUPER_ADMIN — bypasses all org checks; full access everywhere.</li>
 *   <li>GLOBAL ADMIN — full access within any org they explicitly belong to.</li>
 *   <li>ORG_ADMIN — admin rights scoped to orgs where this role is assigned.</li>
 *   <li>ORG_MEMBER — standard member rights within their orgs.</li>
 *   <li>ORG_VIEWER — read-only within their orgs.</li>
 *   <li>GLOBAL ROLE_USER — baseline; no org-level elevation.</li>
 * </ol>
 *
 * <p><b>Permission resolution algorithm:</b>
 * <ol>
 *   <li>Load the user's global role from the existing {@code user_roles} table.</li>
 *   <li>If SUPER_ADMIN → grant all; short-circuit.</li>
 *   <li>Load all rows for this user from {@code user_organizations}.</li>
 *   <li>Merge global permissions UNION org-scoped permissions for the active org context.</li>
 *   <li>Inject the resolved authority set into the JWT "authorities" claim at login.</li>
 * </ol>
 *
 * <p><b>Cross-org admin update check (for PATCH /user/admin/update/{userId}):</b>
 * <pre>
 *   SELECT COUNT(*) FROM user_organizations a
 *   JOIN user_organizations b ON a.org_id = b.org_id
 *   WHERE a.user_id = :adminId AND b.user_id = :targetUserId
 *   AND a.org_role IN ('ORG_ADMIN') AND a.status = 'ACTIVE'
 * </pre>
 * If count == 0 AND admin is not SUPER_ADMIN → throw 403 Forbidden.
 *
 * <p><b>New repo methods needed:</b>
 * <ul>
 *   <li>{@code addUserToOrg(Long userId, Long orgId, String orgRole)}</li>
 *   <li>{@code getOrgRolesForUser(Long userId)} → List of (orgId, orgRole)</li>
 *   <li>{@code getUsersInOrg(Long orgId)} → for the admin Users list page</li>
 *   <li>{@code adminSharesOrgWithUser(Long adminId, Long targetUserId)} → boolean guard</li>
 *   <li>{@code updateUserOrgRole(Long userId, Long orgId, String newRole)}</li>
 *   <li>{@code removeUserFromOrg(Long userId, Long orgId)}</li>
 * </ul>
 * -----------------------------------------------------------------------
 */
@Repository
@RequiredArgsConstructor
@Slf4j
public class RoleRepoImpl implements RoleRepo<Role> {
    private final NamedParameterJdbcTemplate jdbcTemplate;

    /**
     * Not yet implemented; returns null. Roles are seeded directly in the
     * database for now.
     *
     * @param data the role to create
     * @return null
     */
    @Override
    public Role create(Role data) {
        return null;
    }

    /**
     * Not yet implemented; returns null.
     *
     * @param page     0-indexed page number
     * @param pageSize page size
     * @return null
     */
    @Override
    public java.util.Collection<Role> list(int page, int pageSize) {
        return null;
    }

    /**
     * Not yet implemented; returns null. Use {@link #getRoleByUserId(Long)}
     * for the only role lookup the application currently performs.
     *
     * @param id the role id
     * @return null
     */
    @Override
    public Role get(Long id) {
        return null;
    }

    /**
     * Not yet implemented; returns null.
     *
     * @param id   the id of the role to update
     * @param data the new role data
     * @return null
     */
    @Override
    public Role update(Long id, Role data) {
        return null;
    }

    /**
     * Not yet implemented; no-op.
     *
     * @param id the id of the role to delete
     */
    @Override
    public void delete(Long id) {

    }

    /**
     * Assigns a role to a user by role name.
     * <p>
     * This method:
     * 1. Queries the database to find the role by its name
     * 2. Retrieves the role ID from the result
     * 3. Inserts a record into the user_roles junction table linking the user and role
     *
     * @param userId   the ID of the user to assign the role to
     * @param roleName the name of the role (e.g., "ROLE_USER", "ROLE_ADMIN")
     * @throws ApiException if the role name is not found or any database operation fails
     */
    @Override
    public void addRoleToUser(Long userId, String roleName) {
        log.info("Adding role {} to user with ID {}", roleName, userId);
        try {
            Role role = jdbcTemplate.queryForObject(SELECT_ROLE_BY_NAME_QUERY, of("name", roleName), new RoleRowMapper());
            jdbcTemplate.update(INSERT_ROLE_TO_USER_QUERY, of("userId", userId, "roleId", requireNonNull(role).getId()));

        } catch (EmptyResultDataAccessException e) {
            throw new ApiException("Can't find role via name to add to the user" + ROLE_USER.name());
        } catch (Exception e) {
            log.error(e.getMessage());
            throw new ApiException("WE DON'T KNOW WHAT KIND, BUT SOME KIND OF ERROR HAS OCCURRED. SORRY!");
        }
    }

    /**
     * Retrieves the role assigned to a user by their user ID.
     * Queries the database using a join between users, user_roles, and roles tables
     * to fetch the role information for a specific user.
     *
     * @param userId the ID of the user whose role should be retrieved
     * @return the Role object containing id, name, and permissions
     * @throws ApiException if the user has no role assigned or any database operation fails
     */
    @Override
    public Role getRoleByUserId(Long userId) {
        log.info("Fetching role to user with ID {}", userId);
        try {
            return jdbcTemplate.queryForObject(SELECT_ROLE_BY_ID_QUERY, of("id", userId), new RoleRowMapper());

        } catch (EmptyResultDataAccessException e) {
            throw new ApiException("Can't find role via name " + ROLE_USER.name());
        } catch (Exception e) {
            log.error(e.getMessage());
            throw new ApiException("WE DON'T KNOW WHAT KIND, BUT SOME KIND OF ERROR HAS OCCURRED. SORRY!");
        }
    }

    /**
     * Not yet implemented; returns null. Lookups go through
     * {@link #getRoleByUserId(Long)} after a separate user lookup.
     *
     * @param email the user's email
     * @return null
     */
    @Override
    public Role getRoleByUserEmail(String email) {
        return null;
    }

    /**
     * Not yet implemented; no-op. Role reassignment is not exposed yet.
     *
     * @param userId   the user whose role should change
     * @param roleName the new role name
     */
    @Override
    public void updateUserRole(Long userId, String roleName) {

    }
}
