package com.bob.angularspringbootfullstack.service.serviceimpl;

import com.bob.angularspringbootfullstack.exception.ApiException;
import com.bob.angularspringbootfullstack.repo.RoleRepo;
import com.bob.angularspringbootfullstack.repo.UserRepo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.util.Map;

import static com.bob.angularspringbootfullstack.query.OAuthQuery.COUNT_PASSWORD_BY_USER_ID_QUERY;
import static com.bob.angularspringbootfullstack.query.OAuthQuery.COUNT_PROVIDER_LINKS_BY_USER_ID_QUERY;
import static com.bob.angularspringbootfullstack.query.OAuthQuery.DELETE_PROVIDER_LINK_QUERY;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The account-lockout guard on provider unlinking (ROADMAP §1.4).
 *
 * <h3>Why this needs a test rather than a code review</h3>
 * The failure mode is silent and unrecoverable from the user's side. Disconnecting the last
 * provider on a federated-only account leaves it with no credential of any kind — no password to
 * fall back on, no second provider — so the user is locked out of an account they still own, by a
 * button the interface offered them. There is no self-service path back; it takes an administrator.
 *
 * <p>The interesting thing about the guard is that it is a <em>conjunction</em>, and both halves
 * are easy to get wrong in opposite directions. Checking only the link count would refuse a
 * perfectly safe unlink for anyone who set a password (annoying, and it pushes people to leave
 * providers connected that they wanted removed). Checking only the password would allow the
 * lockout for a federated-only user with two providers down to one. Each case below pins one
 * corner of that truth table.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class FederatedIdentityUnlinkTest {

    private static final long USER_ID = 8L;
    private static final String PROVIDER = "github";

    @Mock
    private NamedParameterJdbcTemplate jdbcTemplate;
    @Mock
    private UserRepo<com.bob.angularspringbootfullstack.model.User> userRepo;
    @Mock
    private RoleRepo<com.bob.angularspringbootfullstack.model.Role> roleRepo;

    @InjectMocks
    private FederatedIdentityServiceImpl federatedIdentityService;

    /**
     * Shapes the account: whether it has a usable password, and how many providers are linked.
     *
     * @param hasPassword whether {@code users.password} is set
     * @param linkCount   how many rows the account has in {@code oauthproviderlinks}
     */
    private void account(boolean hasPassword, long linkCount) {
        when(jdbcTemplate.queryForObject(eq(COUNT_PASSWORD_BY_USER_ID_QUERY), anyMap(), eq(Long.class)))
                .thenReturn(hasPassword ? 1L : 0L);
        when(jdbcTemplate.queryForObject(eq(COUNT_PROVIDER_LINKS_BY_USER_ID_QUERY), anyMap(), eq(Long.class)))
                .thenReturn(linkCount);
        when(jdbcTemplate.update(eq(DELETE_PROVIDER_LINK_QUERY), anyMap())).thenReturn(1);
    }

    @Test
    @DisplayName("refuses to remove the last sign-in method of a federated-only account")
    void lastSignInMethodIsProtected() {
        account(false, 1);

        assertThrows(ApiException.class, () -> federatedIdentityService.unlinkProvider(USER_ID, PROVIDER));

        // Nothing is deleted. The refusal has to happen before the write, not be compensated after
        // it — a "delete then restore" would leave a window where the account is unreachable.
        verify(jdbcTemplate, never()).update(eq(DELETE_PROVIDER_LINK_QUERY), anyMap());
    }

    @Test
    @DisplayName("allows unlinking when the account also has a password")
    void passwordMakesUnlinkingSafe() {
        account(true, 1);

        assertDoesNotThrow(() -> federatedIdentityService.unlinkProvider(USER_ID, PROVIDER));

        verify(jdbcTemplate).update(eq(DELETE_PROVIDER_LINK_QUERY), anyMap());
        // The link count is not even consulted: a password is sufficient on its own, and asking a
        // question whose answer cannot change the outcome is a query wasted on every call.
        verify(jdbcTemplate, never()).queryForObject(eq(COUNT_PROVIDER_LINKS_BY_USER_ID_QUERY), anyMap(), eq(Long.class));
    }

    @Test
    @DisplayName("allows unlinking when a second provider remains")
    void secondProviderMakesUnlinkingSafe() {
        // Passwordless, but two providers linked — removing one still leaves a way in.
        account(false, 2);

        assertDoesNotThrow(() -> federatedIdentityService.unlinkProvider(USER_ID, PROVIDER));

        verify(jdbcTemplate).update(eq(DELETE_PROVIDER_LINK_QUERY), anyMap());
    }

    @Test
    @DisplayName("unlinking a provider that is not linked succeeds as a no-op")
    void unlinkingAnAbsentProviderIsANoOp() {
        account(true, 1);
        when(jdbcTemplate.update(eq(DELETE_PROVIDER_LINK_QUERY), anyMap())).thenReturn(0);

        // The caller asked for a state that already holds. Reporting an error would make the UI
        // show a failure for an outcome the user actually got.
        assertDoesNotThrow(() -> federatedIdentityService.unlinkProvider(USER_ID, PROVIDER));
    }

    @Test
    @DisplayName("the delete is scoped by user id, so it cannot reach another account's link")
    void deleteIsScopedToTheCallersOwnAccount() {
        account(true, 1);

        federatedIdentityService.unlinkProvider(USER_ID, PROVIDER);

        // Binding the owner into the predicate is what makes cross-account unlinking
        // unrepresentable rather than merely refused — there is no id the caller could supply
        // that would reach a row they do not own.
        verify(jdbcTemplate).update(DELETE_PROVIDER_LINK_QUERY, Map.of("userId", USER_ID, "provider", PROVIDER));
    }
}
