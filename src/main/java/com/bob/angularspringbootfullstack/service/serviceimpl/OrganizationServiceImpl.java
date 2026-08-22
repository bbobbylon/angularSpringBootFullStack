package com.bob.angularspringbootfullstack.service.serviceimpl;

import com.bob.angularspringbootfullstack.dto.UserDTO;
import com.bob.angularspringbootfullstack.exception.ApiException;
import com.bob.angularspringbootfullstack.model.Organization;
import com.bob.angularspringbootfullstack.model.OrganizationSummary;
import com.bob.angularspringbootfullstack.model.Role;
import com.bob.angularspringbootfullstack.model.User;
import com.bob.angularspringbootfullstack.repo.RoleRepo;
import com.bob.angularspringbootfullstack.rowmapper.OrganizationRowMapper;
import com.bob.angularspringbootfullstack.rowmapper.UserRowMapper;
import com.bob.angularspringbootfullstack.service.OrganizationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.bob.angularspringbootfullstack.dtomapper.UserDTOMapper.fromUser;
import static com.bob.angularspringbootfullstack.query.OrganizationQuery.COUNT_ACTIVE_MEMBERSHIP_QUERY;
import static com.bob.angularspringbootfullstack.query.OrganizationQuery.COUNT_SHARED_ACTIVE_ORGANIZATIONS_QUERY;
import static com.bob.angularspringbootfullstack.query.OrganizationQuery.COUNT_USERS_SHARING_ORGANIZATIONS_QUERY;
import static com.bob.angularspringbootfullstack.query.OrganizationQuery.DEACTIVATE_MEMBERSHIP_QUERY;
import static com.bob.angularspringbootfullstack.query.OrganizationQuery.INSERT_MEMBERSHIP_QUERY;
import static com.bob.angularspringbootfullstack.query.OrganizationQuery.INSERT_ORGANIZATION_QUERY;
import static com.bob.angularspringbootfullstack.query.OrganizationQuery.REACTIVATE_MEMBERSHIP_QUERY;
import static com.bob.angularspringbootfullstack.query.OrganizationQuery.SELECT_ACTIVE_MEMBERS_QUERY;
import static com.bob.angularspringbootfullstack.query.OrganizationQuery.SELECT_ACTIVE_ORGANIZATIONS_QUERY;
import static com.bob.angularspringbootfullstack.query.OrganizationQuery.SELECT_ACTIVE_ORGANIZATION_IDS_BY_USER_QUERY;
import static com.bob.angularspringbootfullstack.query.OrganizationQuery.SELECT_ALL_ORGANIZATIONS_QUERY;
import static com.bob.angularspringbootfullstack.query.OrganizationQuery.SELECT_ORGANIZATIONS_BY_IDS_QUERY;
import static com.bob.angularspringbootfullstack.query.OrganizationQuery.SELECT_ORGANIZATION_ADMIN_EMAILS_QUERY;
import static com.bob.angularspringbootfullstack.query.OrganizationQuery.SELECT_ORGANIZATION_BY_ID_QUERY;
import static com.bob.angularspringbootfullstack.query.OrganizationQuery.SELECT_USERS_SHARING_ORGANIZATIONS_PAGED_QUERY;
import static com.bob.angularspringbootfullstack.query.OrganizationQuery.UPDATE_ORGANIZATION_NAME_QUERY;
import static com.bob.angularspringbootfullstack.query.OrganizationQuery.UPDATE_ORGANIZATION_STATUS_QUERY;
import static java.util.Objects.requireNonNull;
import static org.apache.commons.lang3.StringUtils.isBlank;

/**
 * JDBC-backed implementation of the organization-scope checks (SRS FR-ORG-1..3).
 *
 * <p>Talks to the {@code userorganizations} membership table (Flyway V4) directly via
 * {@code NamedParameterJdbcTemplate}, following the same service-owns-its-SQL shape as
 * {@link FederatedIdentityServiceImpl}. Page clamping and LIKE-pattern normalization
 * deliberately mirror {@code UserRepoImpl#searchUsers} so the scoped and unscoped
 * directory behave identically from the admin dashboard's point of view.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OrganizationServiceImpl implements OrganizationService {

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final RoleRepo<Role> roleRepo;

    /**
     * Evaluates the FR-ORG-2 scope predicate with a single COUNT over the membership
     * self-join — see {@link OrganizationService#isWithinOrganizationScope}.
     */
    @Override
    public boolean isWithinOrganizationScope(Long adminId, Long targetId) {
        try {
            Long shared = jdbcTemplate.queryForObject(COUNT_SHARED_ACTIVE_ORGANIZATIONS_QUERY,
                    Map.of("adminId", adminId, "targetId", targetId), Long.class);
            return shared != null && shared > 0;
        } catch (Exception exception) {
            log.error("Organization scope check failed for admin {} -> target {}: {}", adminId, targetId, exception.getMessage(), exception);
            // Fail closed: an error in the scope check must deny, never grant, access.
            return false;
        }
    }

    /**
     * Pages the org-scoped directory and enriches each row with its role, mirroring
     * {@code UserServiceImpl#searchUsers} — see
     * {@link OrganizationService#searchUsersSharingOrganizations}.
     */
    @Override
    public Collection<UserDTO> searchUsersSharingOrganizations(Long adminId, String searchTerm, int page, int pageSize, String orderBy) {
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(pageSize, 1), 100);
        try {
            Collection<User> users = jdbcTemplate.query(String.format(SELECT_USERS_SHARING_ORGANIZATIONS_PAGED_QUERY, orderBy),
                    Map.of("adminId", adminId,
                            "searchTerm", toLikePattern(searchTerm),
                            "pageSize", safeSize,
                            "offset", safePage * safeSize),
                    new UserRowMapper());
            return users.stream().map(this::mapToUserDTO).toList();
        } catch (Exception exception) {
            log.error("Error listing org-scoped users for admin {} (page {}, size {}, term '{}'): {}",
                    adminId, safePage, safeSize, searchTerm, exception.getMessage(), exception);
            throw new ApiException("An error occurred while retrieving the user directory. Please try again.");
        }
    }

    /**
     * Counts the org-scoped directory for total-pages metadata — see
     * {@link OrganizationService#countUsersSharingOrganizations}.
     */
    @Override
    public long countUsersSharingOrganizations(Long adminId, String searchTerm) {
        try {
            Long count = jdbcTemplate.queryForObject(COUNT_USERS_SHARING_ORGANIZATIONS_QUERY,
                    Map.of("adminId", adminId, "searchTerm", toLikePattern(searchTerm)), Long.class);
            return count == null ? 0 : count;
        } catch (Exception exception) {
            log.error("Error counting org-scoped users for admin {} (term '{}'): {}", adminId, searchTerm, exception.getMessage(), exception);
            throw new ApiException("An error occurred while retrieving the user directory. Please try again.");
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>Unlike the directory queries in this class, a failure here is <b>not</b> swallowed into an
     * empty result. An empty set means "this administrator may see nothing", which is a legitimate
     * verdict; returning it after a database error would make an infrastructure fault look like a
     * deliberate authorization decision, and the caller would render an empty dashboard as though
     * that were the truth. Failing loudly keeps "you have no access" distinguishable from
     * "we could not determine your access".
     */
    @Override
    public Collection<Long> findActiveOrganizationIds(Long userId) {
        try {
            return jdbcTemplate.queryForList(SELECT_ACTIVE_ORGANIZATION_IDS_BY_USER_QUERY,
                    Map.of("userId", userId), Long.class);
        } catch (Exception exception) {
            log.error("Error resolving active organization ids for user {}: {}", userId, exception.getMessage(), exception);
            throw new ApiException("An error occurred while resolving your organization access. Please try again.");
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Collection<OrganizationSummary> findActiveOrganizations() {
        try {
            return jdbcTemplate.query(SELECT_ACTIVE_ORGANIZATIONS_QUERY, Map.of(),
                    (rs, rowNum) -> new OrganizationSummary(rs.getLong("id"), rs.getString("name")));
        } catch (Exception exception) {
            log.error("Error listing active organizations: {}", exception.getMessage(), exception);
            throw new ApiException("An error occurred while resolving report digest recipients. Please try again.");
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Collection<String> findOrganizationAdminEmails(Long organizationId) {
        try {
            return jdbcTemplate.queryForList(SELECT_ORGANIZATION_ADMIN_EMAILS_QUERY,
                    Map.of("organizationId", organizationId), String.class);
        } catch (Exception exception) {
            log.error("Error resolving organization admin emails for organization {}: {}", organizationId, exception.getMessage(), exception);
            throw new ApiException("An error occurred while resolving report digest recipients. Please try again.");
        }
    }

    // ── Organization CRUD + membership management (2026-08-21, FUTURE-ENHANCEMENTS.md §3.2) ──

    /**
     * {@inheritDoc}
     */
    @Override
    public Organization createOrganization(String name) {
        String trimmed = requireNonBlankName(name);
        log.info("Creating organization '{}'", trimmed);
        try {
            MapSqlParameterSource params = new MapSqlParameterSource().addValue("name", trimmed);
            KeyHolder keyHolder = new GeneratedKeyHolder();
            jdbcTemplate.update(INSERT_ORGANIZATION_QUERY, params, keyHolder);
            return getOrganization(requireNonNull(keyHolder.getKey()).longValue());
        } catch (DuplicateKeyException e) {
            throw new ApiException("An organization named '" + trimmed + "' already exists.");
        } catch (Exception e) {
            log.error(e.getMessage());
            throw new ApiException("An error occurred while creating the organization. Please try again.");
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>{@code organizationIds == null} is the unscoped signal every other org-aware method in
     * this codebase uses; an empty (non-null) collection correctly short-circuits to an empty
     * result rather than running {@code WHERE id IN ()}, which is invalid SQL.
     */
    @Override
    public Collection<Organization> listOrganizations(Collection<Long> organizationIds) {
        if (organizationIds != null && organizationIds.isEmpty()) {
            return List.of();
        }
        try {
            if (organizationIds == null) {
                return jdbcTemplate.query(SELECT_ALL_ORGANIZATIONS_QUERY, new OrganizationRowMapper());
            }
            return jdbcTemplate.query(SELECT_ORGANIZATIONS_BY_IDS_QUERY, Map.of("ids", organizationIds), new OrganizationRowMapper());
        } catch (Exception exception) {
            log.error("Error listing organizations (scope={}): {}", organizationIds, exception.getMessage(), exception);
            throw new ApiException("An error occurred while retrieving organizations. Please try again.");
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Organization renameOrganization(Long id, String name) {
        String trimmed = requireNonBlankName(name);
        log.info("Renaming organization id {} to '{}'", id, trimmed);
        try {
            int rows = jdbcTemplate.update(UPDATE_ORGANIZATION_NAME_QUERY, Map.of("name", trimmed, "id", id));
            if (rows == 0) {
                throw new ApiException("Organization not found.");
            }
            return getOrganization(id);
        } catch (DuplicateKeyException e) {
            throw new ApiException("An organization named '" + trimmed + "' already exists.");
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            log.error(e.getMessage());
            throw new ApiException("An error occurred while renaming the organization. Please try again.");
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Organization setOrganizationStatus(Long id, String status) {
        String normalized = status == null ? "" : status.trim().toUpperCase();
        if (!VALID_STATUSES.contains(normalized)) {
            throw new ApiException("Status must be one of " + VALID_STATUSES + ".");
        }
        log.info("Setting organization id {} status to '{}'", id, normalized);
        try {
            int rows = jdbcTemplate.update(UPDATE_ORGANIZATION_STATUS_QUERY, Map.of("status", normalized, "id", id));
            if (rows == 0) {
                throw new ApiException("Organization not found.");
            }
            return getOrganization(id);
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            log.error(e.getMessage());
            throw new ApiException("An error occurred while updating the organization. Please try again.");
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isActiveMemberOfOrganization(Long userId, Long organizationId) {
        try {
            Long count = jdbcTemplate.queryForObject(COUNT_ACTIVE_MEMBERSHIP_QUERY,
                    Map.of("userId", userId, "organizationId", organizationId), Long.class);
            return count != null && count > 0;
        } catch (Exception exception) {
            log.error("Membership check failed for user {} in organization {}: {}", userId, organizationId, exception.getMessage(), exception);
            // Fail closed, same direction as isWithinOrganizationScope: an error here must deny,
            // never grant, a membership-mutation request.
            return false;
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>Tries a plain insert first, and only falls back to
     * {@link com.bob.angularspringbootfullstack.query.OrganizationQuery#REACTIVATE_MEMBERSHIP_QUERY}
     * on {@link DuplicateKeyException} — see
     * {@link com.bob.angularspringbootfullstack.query.OrganizationQuery#INSERT_MEMBERSHIP_QUERY}'s
     * Javadoc for why this is two statements rather than one
     * {@code INSERT ... ON DUPLICATE KEY UPDATE}. {@link DuplicateKeyException} must be caught
     * before the broader {@link DataIntegrityViolationException} it extends, or the collision
     * case would never reach the reactivation branch.
     */
    @Override
    public void addMember(Long organizationId, Long userId) {
        log.info("Adding user {} to organization {}", userId, organizationId);
        Map<String, Long> params = Map.of("userId", userId, "organizationId", organizationId);
        try {
            jdbcTemplate.update(INSERT_MEMBERSHIP_QUERY, params);
        } catch (DuplicateKeyException e) {
            jdbcTemplate.update(REACTIVATE_MEMBERSHIP_QUERY, params);
        } catch (DataIntegrityViolationException e) {
            throw new ApiException("That user or organization does not exist.");
        } catch (Exception e) {
            log.error(e.getMessage());
            throw new ApiException("An error occurred while adding the member. Please try again.");
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void removeMember(Long organizationId, Long userId) {
        log.info("Removing user {} from organization {}", userId, organizationId);
        try {
            jdbcTemplate.update(DEACTIVATE_MEMBERSHIP_QUERY, Map.of("userId", userId, "organizationId", organizationId));
        } catch (Exception e) {
            log.error(e.getMessage());
            throw new ApiException("An error occurred while removing the member. Please try again.");
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Collection<UserDTO> listActiveMembers(Long organizationId) {
        try {
            Collection<User> members = jdbcTemplate.query(SELECT_ACTIVE_MEMBERS_QUERY,
                    Map.of("organizationId", organizationId), new UserRowMapper());
            return members.stream().map(this::mapToUserDTO).toList();
        } catch (Exception exception) {
            log.error("Error listing active members for organization {}: {}", organizationId, exception.getMessage(), exception);
            throw new ApiException("An error occurred while retrieving the organization's members. Please try again.");
        }
    }

    private static final Set<String> VALID_STATUSES = Set.of("ACTIVE", "INACTIVE");

    private static String requireNonBlankName(String name) {
        if (isBlank(name)) {
            throw new ApiException("Organization name is required.");
        }
        return name.trim();
    }

    private Organization getOrganization(Long id) {
        try {
            return jdbcTemplate.queryForObject(SELECT_ORGANIZATION_BY_ID_QUERY, Map.of("id", id), new OrganizationRowMapper());
        } catch (EmptyResultDataAccessException e) {
            throw new ApiException("Organization not found.");
        }
    }

    /**
     * Normalizes the directory search term exactly like {@code UserRepoImpl#toLikePattern}:
     * trimmed, wrapped in {@code %} wildcards, blank collapsing to match-everything.
     */
    private static String toLikePattern(String searchTerm) {
        return "%" + (isBlank(searchTerm) ? "" : searchTerm.trim()) + "%";
    }

    /**
     * Flattens the user's role onto the DTO, the same enrichment every other directory
     * path applies, so org-scoped rows are indistinguishable in shape from unscoped ones.
     */
    private UserDTO mapToUserDTO(User user) {
        return fromUser(user, roleRepo.getRoleByUserId(user.getId()));
    }
}
