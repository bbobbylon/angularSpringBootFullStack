package com.bob.angularspringbootfullstack.service.serviceimpl;

import com.bob.angularspringbootfullstack.dto.UserDTO;
import com.bob.angularspringbootfullstack.exception.ApiException;
import com.bob.angularspringbootfullstack.model.Role;
import com.bob.angularspringbootfullstack.model.User;
import com.bob.angularspringbootfullstack.repo.RoleRepo;
import com.bob.angularspringbootfullstack.repo.UserRepo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.util.List;
import java.util.Map;

import static com.bob.angularspringbootfullstack.query.UserQuery.INSERT_SERVICE_ACCOUNT_QUERY;
import static com.bob.angularspringbootfullstack.query.UserQuery.SELECT_USERS_BY_ORIGIN_QUERY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ServiceAccountServiceImpl} (FUTURE-ENHANCEMENTS.md §3.1, P2-3 Option A).
 * {@link NamedParameterJdbcTemplate}, {@link UserRepo}, and {@link RoleRepo} are mocked, so no
 * database is involved.
 */
@ExtendWith(MockitoExtension.class)
class ServiceAccountServiceImplTest {

    private static final long ADMIN_ID = 1L;
    private static final long NEW_ACCOUNT_ID = 99L;

    @Mock
    private NamedParameterJdbcTemplate jdbcTemplate;
    @Mock
    private UserRepo<User> userRepo;
    @Mock
    private RoleRepo<Role> roleRepo;
    @Mock
    private BCryptPasswordEncoder passwordEncoder;
    @Mock
    private ApplicationEventPublisher eventPublisher;

    @InjectMocks
    private ServiceAccountServiceImpl serviceAccountService;

    private void stubSuccessfulInsert() {
        doAnswer(invocation -> {
            KeyHolder keyHolder = invocation.getArgument(2);
            keyHolder.getKeyList().add(Map.of("id", NEW_ACCOUNT_ID));
            return 1;
        }).when(jdbcTemplate).update(eq(INSERT_SERVICE_ACCOUNT_QUERY), any(SqlParameterSource.class), any(KeyHolder.class));
    }

    @Test
    @DisplayName("create() is refused when the assigned role outranks the creating admin's own")
    void createRejectsRoleAboveCallersOwnTier() {
        when(roleRepo.getRoleByUserId(ADMIN_ID)).thenReturn(Role.builder().name("ROLE_MODERATOR").build());

        assertThrows(ApiException.class,
                () -> serviceAccountService.create("CI pipeline", "ROLE_APPLICATION_ADMIN", ADMIN_ID));

        verify(jdbcTemplate, never()).update(eq(INSERT_SERVICE_ACCOUNT_QUERY), any(SqlParameterSource.class), any(KeyHolder.class));
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("create() allows granting a role equal to the creating admin's own tier")
    void createAllowsEqualTierRole() {
        when(roleRepo.getRoleByUserId(ADMIN_ID)).thenReturn(Role.builder().name("ROLE_ADMIN").build());
        stubSuccessfulInsert();
        when(passwordEncoder.encode(any())).thenReturn("bcrypt-hash");
        User created = User.builder().id(NEW_ACCOUNT_ID).firstName("CI").lastName("pipeline")
                .email("ci-pipeline-abcd1234@service.tessera.internal").enabled(true).build();
        when(userRepo.get(NEW_ACCOUNT_ID)).thenReturn(created);
        when(roleRepo.getRoleByUserId(NEW_ACCOUNT_ID)).thenReturn(Role.builder().name("ROLE_ADMIN").permission("READ:USER").build());

        UserDTO result = serviceAccountService.create("CI pipeline", "ROLE_ADMIN", ADMIN_ID);

        assertEquals(NEW_ACCOUNT_ID, result.getId());
        verify(roleRepo).addRoleToUser(NEW_ACCOUNT_ID, "ROLE_ADMIN");
        verify(eventPublisher).publishEvent(any());
    }

    @Test
    @DisplayName("create() stores an encoded, unguessable password — never null, never plaintext")
    void createStoresEncodedRandomPassword() {
        when(roleRepo.getRoleByUserId(ADMIN_ID)).thenReturn(Role.builder().name("ROLE_ADMIN").build());
        stubSuccessfulInsert();
        when(passwordEncoder.encode(any())).thenReturn("bcrypt-hash");
        when(userRepo.get(NEW_ACCOUNT_ID)).thenReturn(User.builder().id(NEW_ACCOUNT_ID)
                .email("ci-pipeline-abcd1234@service.tessera.internal").build());
        when(roleRepo.getRoleByUserId(NEW_ACCOUNT_ID)).thenReturn(Role.builder().build());

        serviceAccountService.create("CI pipeline", "ROLE_ADMIN", ADMIN_ID);

        ArgumentCaptor<SqlParameterSource> captor = ArgumentCaptor.forClass(SqlParameterSource.class);
        verify(jdbcTemplate).update(eq(INSERT_SERVICE_ACCOUNT_QUERY), captor.capture(), any(KeyHolder.class));
        MapSqlParameterSource params = (MapSqlParameterSource) captor.getValue();
        assertEquals("bcrypt-hash", params.getValue("password"));
        assertEquals("SERVICE_ACCOUNT", params.getValue("origin"));
    }

    @Test
    @DisplayName("create() surfaces a synthetic-email collision as a friendly ApiException")
    void createTranslatesDuplicateKey() {
        when(roleRepo.getRoleByUserId(ADMIN_ID)).thenReturn(Role.builder().name("ROLE_ADMIN").build());
        doThrow(new DuplicateKeyException("dup"))
                .when(jdbcTemplate).update(eq(INSERT_SERVICE_ACCOUNT_QUERY), any(SqlParameterSource.class), any(KeyHolder.class));
        when(passwordEncoder.encode(any())).thenReturn("bcrypt-hash");

        assertThrows(ApiException.class, () -> serviceAccountService.create("CI pipeline", "ROLE_ADMIN", ADMIN_ID));
    }

    @Test
    @DisplayName("list() returns every SERVICE_ACCOUNT-origin user, mapped with role/permissions")
    void listMapsEveryServiceAccount() {
        User svc = User.builder().id(NEW_ACCOUNT_ID).firstName("CI").lastName("pipeline").build();
        //noinspection unchecked
        when(jdbcTemplate.query(eq(SELECT_USERS_BY_ORIGIN_QUERY), anyMap(), any(RowMapper.class)))
                .thenReturn(List.of(svc));
        when(roleRepo.getRoleByUserId(NEW_ACCOUNT_ID)).thenReturn(Role.builder().name("ROLE_USER").permission("READ:USER").build());

        List<UserDTO> accounts = serviceAccountService.list();

        assertEquals(1, accounts.size());
        assertEquals(NEW_ACCOUNT_ID, accounts.getFirst().getId());
        assertEquals("ROLE_USER", accounts.getFirst().getRoleName());
    }

    @Test
    @DisplayName("deactivate() disables the account without locking it")
    void deactivateDisablesAccount() {
        serviceAccountService.deactivate(NEW_ACCOUNT_ID);

        verify(userRepo).updateAccountSettings(NEW_ACCOUNT_ID, false, true);
    }
}
