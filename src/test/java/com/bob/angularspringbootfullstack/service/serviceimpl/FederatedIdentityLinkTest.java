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

import java.util.List;
import java.util.Optional;

import static com.bob.angularspringbootfullstack.query.OAuthQuery.INSERT_PROVIDER_LINK_QUERY;
import static com.bob.angularspringbootfullstack.query.OAuthQuery.SELECT_USER_ID_BY_PROVIDER_SUBJECT_QUERY;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Account linking — attaching a verified provider identity to an already signed-in account
 * (ROADMAP §1.4).
 *
 * <h3>The property this suite exists for</h3>
 * Linking must refuse an identity that <b>already belongs to another account</b>. Without that
 * check, "Connect a provider" is an account-takeover primitive: sign in as a low-privilege user,
 * connect a provider identity belonging to an administrator, and their sign-in method is now
 * attached to your account. The provider-verified email offers no protection, because links are
 * keyed on the provider's stable subject rather than on email — so the usual "the email was
 * verified" reasoning does not apply here.
 *
 * <p>The other cases pin the shape around it: linking an unclaimed identity writes exactly one row,
 * re-linking one this account already holds is a no-op rather than a duplicate or an error, and the
 * refusal message names no account.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class FederatedIdentityLinkTest {

    private static final long ME = 8L;
    private static final long SOMEONE_ELSE = 9L;
    private static final String PROVIDER = "google";
    private static final String SUBJECT = "google-subject-123";

    @Mock
    private NamedParameterJdbcTemplate jdbcTemplate;
    @Mock
    private UserRepo<com.bob.angularspringbootfullstack.model.User> userRepo;
    @Mock
    private RoleRepo<com.bob.angularspringbootfullstack.model.Role> roleRepo;

    @InjectMocks
    private FederatedIdentityServiceImpl federatedIdentityService;

    /** Sets who, if anyone, currently owns the (provider, subject) identity. */
    private void identityOwnedBy(Long ownerId) {
        when(jdbcTemplate.queryForList(eq(SELECT_USER_ID_BY_PROVIDER_SUBJECT_QUERY), anyMap(), eq(Long.class)))
                .thenReturn(ownerId == null ? List.of() : List.of(ownerId));
    }

    @Test
    @DisplayName("an unclaimed identity is attached to the requesting account")
    void unclaimedIdentityIsLinked() {
        identityOwnedBy(null);

        assertTrue(federatedIdentityService.linkProviderToUser(ME, PROVIDER, SUBJECT));

        verify(jdbcTemplate).update(eq(INSERT_PROVIDER_LINK_QUERY), anyMap());
    }

    @Test
    @DisplayName("an identity belonging to ANOTHER account is refused and nothing is written")
    void identityOwnedByAnotherAccountIsRefused() {
        identityOwnedBy(SOMEONE_ELSE);

        ApiException thrown = assertThrows(ApiException.class,
                () -> federatedIdentityService.linkProviderToUser(ME, PROVIDER, SUBJECT));

        // No row is written — the refusal happens before the insert, not compensated after it.
        verify(jdbcTemplate, never()).update(eq(INSERT_PROVIDER_LINK_QUERY), anyMap());

        // And the message names no account. Saying *which* user holds the identity would turn this
        // endpoint into a probe for "does an account exist with this provider identity?", which is
        // the same enumeration channel the login path is careful to close.
        String message = thrown.getMessage();
        assertFalse(message.contains(String.valueOf(SOMEONE_ELSE)), "leaked the owning user id: " + message);
        assertFalse(message.toLowerCase().contains("@"), "leaked an email address: " + message);
    }

    @Test
    @DisplayName("re-linking an identity this account already holds is a no-op, not a duplicate")
    void relinkingOwnIdentityIsIdempotent() {
        identityOwnedBy(ME);

        // Reports "nothing changed" rather than throwing: the caller asked for a state that already
        // holds, and there is nothing for them to act on.
        assertFalse(federatedIdentityService.linkProviderToUser(ME, PROVIDER, SUBJECT));

        verify(jdbcTemplate, never()).update(eq(INSERT_PROVIDER_LINK_QUERY), anyMap());
    }

    @Test
    @DisplayName("a link ticket is single-use and bound to the provider it was minted for")
    void linkTicketIsSingleUseAndProviderBound() {
        ProviderLinkTicketService tickets = new ProviderLinkTicketService();
        String ticket = tickets.mint(ME, PROVIDER);

        // Wrong provider: a ticket started for one provider must not complete a link for another,
        // or a user who began a GitHub link could be steered into attaching a Google identity.
        assertTrue(tickets.redeem(ticket, "github").isEmpty());

        Optional<Long> redeemed = tickets.redeem(ticket, PROVIDER);
        assertTrue(redeemed.isPresent());
        assertTrue(redeemed.get() == ME);

        // Consumed: replaying the link URL does nothing the second time.
        assertTrue(tickets.redeem(ticket, PROVIDER).isEmpty());
    }

    @Test
    @DisplayName("an unknown or blank ticket redeems to nothing")
    void unknownTicketIsRejected() {
        ProviderLinkTicketService tickets = new ProviderLinkTicketService();

        assertTrue(tickets.redeem(null, PROVIDER).isEmpty());
        assertTrue(tickets.redeem("", PROVIDER).isEmpty());
        assertTrue(tickets.redeem("not-a-real-ticket", PROVIDER).isEmpty());
    }
}
