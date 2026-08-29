package com.bob.angularspringbootfullstack.repo.repoimpl;

import com.bob.angularspringbootfullstack.exception.ApiException;
import com.bob.angularspringbootfullstack.model.Role;
import com.bob.angularspringbootfullstack.repo.RoleRepo;
import com.bob.angularspringbootfullstack.rowmapper.RoleRowMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDateTime;

import static com.bob.angularspringbootfullstack.enumeration.RoleType.ROLE_USER;
import static com.bob.angularspringbootfullstack.query.RoleQuery.*;
import static com.bob.angularspringbootfullstack.query.UserQuery.TOUCH_USER_ROLES_CHANGED_AT_QUERY;
import static java.util.Map.of;
import static java.util.Objects.requireNonNull;

/**
 * JDBC-based {@link com.bob.angularspringbootfullstack.repo.RoleRepo} implementation.
 *
 * <p>In this project roles hold the permission string used to construct authorities.
 *
 * <p>-----------------------------------------------------------------------
 * <b>Organization-scoped roles — implemented 2026-08-26 (was {@code TODO(org-roles)})</b>
 * -----------------------------------------------------------------------
 *
 * <p>The capacity a user holds <em>within one organization</em> now lives on the membership row
 * as {@code userorganizations.org_role}, mirrored in code by
 * {@link com.bob.angularspringbootfullstack.enumeration.OrgRole} (ORG_VIEWER &lt; ORG_MEMBER &lt;
 * ORG_ADMIN). This class is unchanged by that work and stays what it was: the repository for the
 * <b>global</b> role catalogue, one role per user, which is what supplies the authority strings
 * Spring Security matches on.
 *
 * <p><b>How the two compose</b> — the split this TODO existed to introduce:
 * <ol>
 *   <li>The global {@link com.bob.angularspringbootfullstack.enumeration.RoleType} gates the
 *       endpoint (via authority strings) and decides <em>whether</em> org scoping applies at all —
 *       {@code ROLE_ADMIN} and {@code ROLE_APPLICATION_ADMIN} remain unscoped platform
 *       operators.</li>
 *   <li>For every scoped tier, {@code OrgRole} decides <em>which</em> organizations they may act
 *       on. Administering organization A now confers nothing in organization B.</li>
 * </ol>
 *
 * <p>Before this, "organization admin" was the global {@code ROLE_ORGANIZATION_ADMIN} tier and its
 * reach was every organization the holder belonged to, so the distinction genuine multi-tenancy
 * rests on could not be expressed. It also made invite redemption a cross-tenant escalation: an
 * invite raised the redeemer's single global role, so one organization's link elevated them
 * everywhere they held a membership. Redemption now writes an org role onto the membership row
 * instead — see {@code OrganizationServiceImpl#redeemInvite} and the {@code NOTE(org-roles)} beside
 * it.
 *
 * <p>The membership-side operations the original note sketched live on
 * {@link com.bob.angularspringbootfullstack.service.OrganizationService}, not here — that class
 * already owned membership and its own SQL, so adding a second home for it would have split one
 * concern across two components. The mapping, for anyone following the old note:
 * <ul>
 *   <li>{@code addUserToOrg} → {@code addMember(orgId, userId, OrgRole)}</li>
 *   <li>{@code getOrgRolesForUser} → {@code findOrgRoles(userId)}</li>
 *   <li>{@code getUsersInOrg} → {@code listActiveMembers(orgId)} (already existed)</li>
 *   <li>{@code adminSharesOrgWithUser} → {@code isWithinOrganizationScope} (already existed);
 *       the per-organization question is {@code isOrgAdminOf}</li>
 *   <li>{@code updateUserOrgRole} → {@code setMemberOrgRole(orgId, userId, OrgRole)}</li>
 *   <li>{@code removeUserFromOrg} → {@code removeMember(orgId, userId)} (already existed)</li>
 * </ul>
 *
 * <p><b>Deliberately not done</b>, and tracked in FUTURE-ENHANCEMENTS.md §3.2 rather than left as
 * a comment here:
 * <ul>
 *   <li><b>Org roles are not in the JWT.</b> The original note's step 5 proposed injecting a
 *       resolved authority set into the token's {@code authorities} claim at login. They are
 *       resolved per request from the database instead, because a token minted before a membership
 *       change would otherwise carry stale authority for its full 30-minute TTL — the same
 *       staleness already recorded as the open "access tokens have no revocation path" item.</li>
 *   <li><b>Per-organization role <em>definitions</em></b> (each organization with its own role
 *       catalogue and permission strings) remain unbuilt. This work makes an existing, fixed set
 *       of capacities per-organization; it does not make the catalogue itself per-tenant.</li>
 *   <li><b>No frontend.</b> The {@code PATCH /admin/organization/&#123;id&#125;/members/&#123;userId&#125;/role}
 *       endpoint and the {@code orgRole} parameter on add-member exist and are tested, but the
 *       Organizations page's Members tab does not yet expose them.</li>
 * </ul>
 * -----------------------------------------------------------------------
 */
@Repository
@RequiredArgsConstructor
@Slf4j
public class RoleRepoImpl implements RoleRepo<Role> {
    private final NamedParameterJdbcTemplate jdbcTemplate;

    /**
     * Inserts a new role catalog row (Role CRUD — create).
     * <p>
     * The generated id is recovered through a {@link GeneratedKeyHolder}, the same pattern
     * every other {@code *RepoImpl#create} in this codebase uses. {@code roles.name} carries a
     * unique constraint, so a name collision surfaces as {@link DuplicateKeyException} and is
     * translated into a client-facing {@link ApiException} rather than a raw SQL error.
     *
     * @param data the role to create; {@code name} and {@code permission} are read, {@code id}
     *             is ignored and overwritten with the generated key
     * @return {@code data}, mutated in place with its generated id
     * @throws ApiException if the name is already taken, or any other database error occurs
     */
    @Override
    public Role create(Role data) {
        log.info("Creating role '{}'", data.getName());
        try {
            MapSqlParameterSource params = new MapSqlParameterSource()
                    .addValue("name", data.getName())
                    .addValue("permission", data.getPermission());
            KeyHolder keyHolder = new GeneratedKeyHolder();
            jdbcTemplate.update(INSERT_ROLE_QUERY, params, keyHolder);
            data.setId(requireNonNull(keyHolder.getKey()).longValue());
            return data;
        } catch (DuplicateKeyException e) {
            throw new ApiException("A role named '" + data.getName() + "' already exists.");
        } catch (Exception e) {
            log.error(e.getMessage());
            throw new ApiException("WE DON'T KNOW WHAT KIND, BUT SOME KIND OF ERROR HAS OCCURRED. SORRY!");
        }
    }

    /**
     * Returns all roles from the database, ordered by ID.
     * <p>
     * Delegates to {@link com.bob.angularspringbootfullstack.query.RoleQuery#SELECT_ALL_ROLES_QUERY}
     * and maps each row with {@link com.bob.angularspringbootfullstack.rowmapper.RoleRowMapper}.
     *
     * @return a collection of all {@link Role} entities
     * @throws ApiException if any database error occurs
     */
    @Override
    public java.util.Collection<Role> list() {
        log.info("Fetching all roles from the database");
        try {
            return jdbcTemplate.query(SELECT_ALL_ROLES_QUERY, new RoleRowMapper());
        } catch (Exception e) {
            log.error(e.getMessage());
            throw new ApiException("WE DON'T KNOW WHAT KIND, BUT SOME KIND OF ERROR HAS OCCURRED. SORRY!");
        }
    }

    /**
     * Fetches a single role catalog row by its own id (Role CRUD). For "what role does this
     * user hold", use {@link #getRoleByUserId(Long)} instead — that is a different query
     * joining through {@code userroles}, not this one.
     *
     * @param id the role id
     * @return the role
     * @throws ApiException if no role has that id, or any other database error occurs
     */
    @Override
    public Role get(Long id) {
        try {
            return jdbcTemplate.queryForObject(SELECT_ROLE_QUERY, of("id", id), new RoleRowMapper());
        } catch (EmptyResultDataAccessException e) {
            throw new ApiException("Role not found.");
        } catch (Exception e) {
            log.error(e.getMessage());
            throw new ApiException("WE DON'T KNOW WHAT KIND, BUT SOME KIND OF ERROR HAS OCCURRED. SORRY!");
        }
    }

    /**
     * Updates a role's permission string (Role CRUD — edit). The name is deliberately not
     * touched — see {@link RoleQuery#UPDATE_ROLE_PERMISSION_QUERY}'s Javadoc for why renaming
     * would strand the {@link com.bob.angularspringbootfullstack.enumeration.RoleType} tier
     * ladder.
     *
     * @param id   the id of the role to update
     * @param data the new role data; only {@link Role#getPermission()} is applied
     * @return the role, freshly re-read from the database after the update
     * @throws ApiException if no role has that id, or any other database error occurs
     */
    @Override
    public Role update(Long id, Role data) {
        log.info("Updating permission for role id {}", id);
        try {
            int rows = jdbcTemplate.update(UPDATE_ROLE_PERMISSION_QUERY, of("id", id, "permission", data.getPermission()));
            if (rows == 0) {
                throw new ApiException("Role not found.");
            }
            return get(id);
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            log.error(e.getMessage());
            throw new ApiException("WE DON'T KNOW WHAT KIND, BUT SOME KIND OF ERROR HAS OCCURRED. SORRY!");
        }
    }

    /**
     * Deletes a role from the catalog (Role CRUD — delete).
     * <p>
     * {@code userroles.role_id} carries {@code ON DELETE RESTRICT}
     * ({@code schema.sql}), so the database itself refuses to delete a role any user currently
     * holds; that surfaces here as {@link DataIntegrityViolationException} and is translated
     * into a client-facing {@link ApiException} rather than a raw SQL error reaching the
     * frontend. Whether a role is one of the seven built-in {@link
     * com.bob.angularspringbootfullstack.enumeration.RoleType} constants is a business rule, not
     * a data-access concern, so that guard lives in {@code RoleServiceImpl#deleteRole}, one layer
     * up — this method deletes whatever id it is given.
     *
     * @param id the id of the role to delete
     * @throws ApiException if no role has that id, or if any user currently holds it
     */
    @Override
    public void delete(Long id) {
        log.info("Deleting role id {}", id);
        try {
            int rows = jdbcTemplate.update(DELETE_ROLE_QUERY, of("id", id));
            if (rows == 0) {
                throw new ApiException("Role not found.");
            }
        } catch (DataIntegrityViolationException e) {
            throw new ApiException("This role is still assigned to at least one user and cannot be deleted.");
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            log.error(e.getMessage());
            throw new ApiException("WE DON'T KNOW WHAT KIND, BUT SOME KIND OF ERROR HAS OCCURRED. SORRY!");
        }
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
     * Queries the database using a join between users, user_roles, and role tables
     * to fetch the role information for a specific user.
     * <p>
     * Enforces time-boxed role assignment (POST-SUBMISSION-UPGRADES.md) <b>live, on every
     * call</b>: if {@code userroles.expires_at} for this user is in the past, the assignment is
     * auto-reverted to {@code ROLE_USER} (expiry cleared) before returning — there is no
     * separate scheduled sweep job. This is the single choke point every role lookup in the
     * application goes through (login, token refresh, profile fetch, OAuth2/passkey/TOTP
     * completion all call this, directly or via {@code UserRepoImpl#loadUserByUsername}), so
     * enforcing it here covers all of them at once, the same way {@code PUBLIC_URLS}/{@code
     * PUBLIC_ROUTES} are each a single list rather than duplicated per call site.
     *
     * @param userId the ID of the user whose role should be retrieved
     * @return the Role object containing id, name, permissions, and (if time-boxed) expiresAt
     * @throws ApiException if the user has no role assigned, or any database operation fails
     */
    @Override
    public Role getRoleByUserId(Long userId) {
        // ── TEMP DIAGNOSTIC (Users vs users casing) ──────────────────────────────────
        // This is the ONLY query in the app that references a capitalized table (`JOIN Users`).
        // On case-INSENSITIVE MySQL (native Windows, lower_case_table_names=1) it resolves to
        // `users` and works. On case-SENSITIVE MySQL (Docker/Aiven, lower_case_table_names=0) it
        // needs a real `Users` table/view or it throws BadSqlGrammarException. These logs make the
        // outcome unmistakable in the backend console. The happy-path lines are DEBUG so they don't
        // spam INFO logs on every login (role is looked up several times per sign-in); the failure
        // paths below stay WARN/ERROR. Enable DEBUG on this class to trace casing if it ever recurs.
        log.debug("[ROLE-CASING] getRoleByUserId(userId={}) — executing: {}", userId, SELECT_ROLE_BY_ID_QUERY);
        try {
            Role role = jdbcTemplate.queryForObject(SELECT_ROLE_BY_ID_QUERY, of("id", userId), (rs, rowNum) -> {
                Role mapped = new RoleRowMapper().mapRow(rs, rowNum);
                Timestamp expiresAt = rs.getTimestamp("expires_at");
                if (expiresAt != null) {
                    mapped.setExpiresAt(expiresAt.toLocalDateTime());
                }
                return mapped;
            });
            log.debug("[ROLE-CASING] SUCCESS — 'JOIN Users' RESOLVED on this database. userId={} -> role='{}' (id={}).",
                    userId, role != null ? role.getName() : null, role != null ? role.getId() : null);

            if (role != null && role.getExpiresAt() != null && !role.getExpiresAt().isAfter(LocalDateTime.now())) {
                log.info("Time-boxed role assignment for userId={} (role={}) expired at {} — reverting to {}.",
                        userId, role.getName(), role.getExpiresAt(), ROLE_USER.name());
                updateUserRole(userId, ROLE_USER.name(), null);
                return getRoleByUserId(userId);
            }
            return role;

        } catch (EmptyResultDataAccessException e) {
            // The query PARSED fine (casing is OK on this DB) — the user simply has no role row.
            log.warn("[ROLE-CASING] QUERY OK, NO ROWS — casing is fine on this DB, but userId={} has no role assigned.", userId);
            throw new ApiException("Can't find role via name " + ROLE_USER.name());
        } catch (BadSqlGrammarException e) {
            // THIS is the casing failure: the DB is case-sensitive and has no `Users` object.
            log.error("[ROLE-CASING] *** BAD SQL GRAMMAR — this is the 'Users' vs 'users' casing bug. *** " +
                    "This database is case-SENSITIVE and has no table/view named 'Users'. " +
                    "Fix: lowercase the query to 'JOIN users', or add a `Users` view. userId={}", userId, e);
            throw new ApiException("WE DON'T KNOW WHAT KIND, BUT SOME KIND OF ERROR HAS OCCURRED. SORRY!");
        } catch (Exception e) {
            log.error("[ROLE-CASING] UNEXPECTED (not a casing issue) for userId={}: {}", userId, e.getMessage(), e);
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
     * Reassigns the given user to a new role by name, optionally time-boxing it.
     * <p>
     * Looks up the target role by name using
     * {@link com.bob.angularspringbootfullstack.query.RoleQuery#SELECT_ROLE_BY_NAME_QUERY},
     * then updates the {@code userroles} junction table entry for the user with
     * {@link com.bob.angularspringbootfullstack.query.RoleQuery#UPDATE_USER_ROLE_QUERY}, and
     * finally stamps {@code users.roles_changed_at = NOW()} via
     * {@link com.bob.angularspringbootfullstack.query.UserQuery#TOUCH_USER_ROLES_CHANGED_AT_QUERY}
     * — mirroring how a password change stamps {@code password_changed_at} — so
     * {@code TokenProvider#isTokenValid} rejects every access token minted before this change on
     * its very next use. This runs for EVERY caller of this method, including the auto-revert to
     * {@code ROLE_USER} that {@code getRoleByUserId} triggers when a time-boxed assignment
     * expires: a token still carrying the expired elevated authorities must not remain valid
     * merely because nobody happened to call the admin endpoint.
     * <p>
     * Uses a {@link MapSqlParameterSource} rather than this class's usual {@code Map.of(...)}
     * static import: {@code expiresAt} is legitimately {@code null} for the common,
     * unlimited-assignment case, and {@code Map.of} throws {@link NullPointerException} on a
     * null value.
     *
     * @param userId    the ID of the user whose role should change
     * @param roleName  the name of the new role (e.g. "ROLE_ADMIN")
     * @param expiresAt when the assignment should expire, or {@code null} for unlimited
     * @throws ApiException if the role name is not found, or if any database error occurs
     */
    @Override
    public void updateUserRole(Long userId, String roleName, LocalDateTime expiresAt) {
        log.info("Updating role for user id {} to {} (expiresAt={})", userId, roleName, expiresAt);
        try {
            Role role = jdbcTemplate.queryForObject(SELECT_ROLE_BY_NAME_QUERY, of("name", roleName), new RoleRowMapper());
            MapSqlParameterSource params = new MapSqlParameterSource()
                    .addValue("userId", userId)
                    .addValue("roleId", requireNonNull(role).getId())
                    .addValue("expiresAt", expiresAt);
            jdbcTemplate.update(UPDATE_USER_ROLE_QUERY, params);
            jdbcTemplate.update(TOUCH_USER_ROLES_CHANGED_AT_QUERY, of("userId", userId));
        } catch (EmptyResultDataAccessException e) {
            throw new ApiException("Can't find role via name " + roleName);
        } catch (Exception e) {
            log.error(e.getMessage());
            throw new ApiException("WE DON'T KNOW WHAT KIND, BUT SOME KIND OF ERROR HAS OCCURRED. SORRY!");
        }
    }
}
