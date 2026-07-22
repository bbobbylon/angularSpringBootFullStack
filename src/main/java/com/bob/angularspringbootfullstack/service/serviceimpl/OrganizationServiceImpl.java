package com.bob.angularspringbootfullstack.service.serviceimpl;

import com.bob.angularspringbootfullstack.dto.UserDTO;
import com.bob.angularspringbootfullstack.exception.ApiException;
import com.bob.angularspringbootfullstack.model.Role;
import com.bob.angularspringbootfullstack.model.User;
import com.bob.angularspringbootfullstack.repo.RoleRepo;
import com.bob.angularspringbootfullstack.rowmapper.UserRowMapper;
import com.bob.angularspringbootfullstack.service.OrganizationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.Map;

import static com.bob.angularspringbootfullstack.dtomapper.UserDTOMapper.fromUser;
import static com.bob.angularspringbootfullstack.query.OrganizationQuery.COUNT_SHARED_ACTIVE_ORGANIZATIONS_QUERY;
import static com.bob.angularspringbootfullstack.query.OrganizationQuery.COUNT_USERS_SHARING_ORGANIZATIONS_QUERY;
import static com.bob.angularspringbootfullstack.query.OrganizationQuery.SELECT_USERS_SHARING_ORGANIZATIONS_PAGED_QUERY;
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
    public Collection<UserDTO> searchUsersSharingOrganizations(Long adminId, String searchTerm, int page, int pageSize) {
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(pageSize, 1), 100);
        try {
            Collection<User> users = jdbcTemplate.query(SELECT_USERS_SHARING_ORGANIZATIONS_PAGED_QUERY,
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
