package com.bob.angularspringbootfullstack.repo.repoimpl;

import com.bob.angularspringbootfullstack.exception.ApiException;
import com.bob.angularspringbootfullstack.model.Role;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.time.LocalDateTime;
import java.util.Map;

import static com.bob.angularspringbootfullstack.query.RoleQuery.SELECT_ROLE_BY_NAME_QUERY;
import static com.bob.angularspringbootfullstack.query.RoleQuery.UPDATE_USER_ROLE_QUERY;
import static com.bob.angularspringbootfullstack.query.UserQuery.TOUCH_USER_ROLES_CHANGED_AT_QUERY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link RoleRepoImpl#updateUserRole}, focused on the {@code roles_changed_at}
 * stamping added for FUTURE-ENHANCEMENTS §3.1 (role-change JWT staleness). Every successful call
 * must write BOTH the junction-table role assignment ({@code UPDATE_USER_ROLE_QUERY}) and the
 * invalidation stamp ({@code TOUCH_USER_ROLES_CHANGED_AT_QUERY}) — this is what lets a demoted
 * user's still-live access token get rejected on its very next use instead of riding out its TTL,
 * and what closes the same gap for {@code getRoleByUserId}'s silent auto-revert-on-expiry path,
 * which calls this same method internally.
 */
@ExtendWith(MockitoExtension.class)
class RoleRepoImplTest {

    private static final Long USER_ID = 42L;
    private static final Long ROLE_ID = 3L;

    @Mock
    private NamedParameterJdbcTemplate jdbcTemplate;

    private RoleRepoImpl roleRepo;

    @BeforeEach
    void setUp() {
        roleRepo = new RoleRepoImpl(jdbcTemplate);
    }

    private void stubRoleLookup(String roleName) {
        Role role = Role.builder().id(ROLE_ID).name(roleName).permission("READ:USER").build();
        when(jdbcTemplate.queryForObject(eq(SELECT_ROLE_BY_NAME_QUERY), eq(Map.of("name", roleName)), any(RowMapper.class)))
                .thenReturn(role);
    }

    @Test
    @DisplayName("updateUserRole writes the role assignment AND stamps roles_changed_at")
    void updateUserRoleStampsRolesChangedAt() {
        stubRoleLookup("ROLE_ADMIN");

        roleRepo.updateUserRole(USER_ID, "ROLE_ADMIN", null);

        ArgumentCaptor<MapSqlParameterSource> paramsCaptor = ArgumentCaptor.forClass(MapSqlParameterSource.class);
        verify(jdbcTemplate).update(eq(UPDATE_USER_ROLE_QUERY), paramsCaptor.capture());
        MapSqlParameterSource params = paramsCaptor.getValue();
        assertEquals(USER_ID, params.getValue("userId"));
        assertEquals(ROLE_ID, params.getValue("roleId"));
        assertNull(params.getValue("expiresAt"));

        verify(jdbcTemplate).update(eq(TOUCH_USER_ROLES_CHANGED_AT_QUERY), eq(Map.of("userId", USER_ID)));
    }

    @Test
    @DisplayName("a time-boxed assignment carries its expiresAt through to the junction-table update")
    void updateUserRolePassesThroughExpiresAt() {
        stubRoleLookup("ROLE_ADMIN");
        LocalDateTime expiresAt = LocalDateTime.now().plusDays(7);

        roleRepo.updateUserRole(USER_ID, "ROLE_ADMIN", expiresAt);

        ArgumentCaptor<MapSqlParameterSource> paramsCaptor = ArgumentCaptor.forClass(MapSqlParameterSource.class);
        verify(jdbcTemplate).update(eq(UPDATE_USER_ROLE_QUERY), paramsCaptor.capture());
        assertEquals(expiresAt, paramsCaptor.getValue().getValue("expiresAt"));

        // The stamp fires regardless of whether the assignment is time-boxed — the token must go
        // stale the moment the ROLE changes, independent of when the new assignment itself expires.
        verify(jdbcTemplate).update(eq(TOUCH_USER_ROLES_CHANGED_AT_QUERY), eq(Map.of("userId", USER_ID)));
    }

    @Test
    @DisplayName("an unknown role name fails before touching either UPDATE query")
    void updateUserRoleWithUnknownRoleNameDoesNotStamp() {
        when(jdbcTemplate.queryForObject(eq(SELECT_ROLE_BY_NAME_QUERY), eq(Map.of("name", "ROLE_NOPE")), any(RowMapper.class)))
                .thenThrow(new EmptyResultDataAccessException(1));

        assertThrows(ApiException.class, () -> roleRepo.updateUserRole(USER_ID, "ROLE_NOPE", null));

        verify(jdbcTemplate, never()).update(eq(UPDATE_USER_ROLE_QUERY), any(MapSqlParameterSource.class));
        verify(jdbcTemplate, never()).update(eq(TOUCH_USER_ROLES_CHANGED_AT_QUERY), anyMap());
    }
}
