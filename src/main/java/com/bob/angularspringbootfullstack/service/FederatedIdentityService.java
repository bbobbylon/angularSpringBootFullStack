package com.bob.angularspringbootfullstack.service;

import com.bob.angularspringbootfullstack.dto.UserDTO;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Business contract for resolving a federated identity (SRS §4.3) to a local user.
 *
 * <p>This service implements the "find-or-create" step of the hybrid model: after Spring
 * Security's OAuth2 client completes the Authorization Code flow and hands the verified
 * provider identity to {@code OAuth2LoginSuccessHandler}, this service maps that identity
 * onto the local user store so the token-exchange point can issue application JWTs
 * (FR-FED-4). From that moment the federated session is indistinguishable from an
 * in-house one — RBAC, MFA policy, and audit logging all operate on the local user.
 */
public interface FederatedIdentityService {

    /**
     * Resolves the given federated identity to a local user, creating one on first login.
     *
     * <p>Resolution order (FR-FED-3):
     * <ol>
     *   <li>An existing {@code oauthproviderlinks} row for (provider, subject) wins —
     *       the stable subject identifier, not the email, is the durable key.</li>
     *   <li>Otherwise, an existing local account with the provider-asserted email is
     *       linked to this identity (account convergence at the token-exchange point).</li>
     *   <li>Otherwise, a new enabled, passwordless account is created with the default
     *       {@code ROLE_USER} role and linked.</li>
     * </ol>
     *
     * @param provider  the registration id (e.g. {@code google}, {@code github}, {@code microsoft})
     * @param subject   the provider's stable subject identifier for this user
     * @param email     the provider-asserted email address
     * @param firstName best-effort first name extracted from provider attributes
     * @param lastName  best-effort last name extracted from provider attributes
     * @param imageUrl  the provider avatar URL, or null to keep the default avatar
     * @return the resolved local user with role and permissions populated
     */
    UserDTO findOrCreateFederatedUser(String provider, String subject, String email,
                                      String firstName, String lastName, String imageUrl);

    /**
     * Lists the identity providers currently linked to an account (ROADMAP §1.4).
     *
     * <p>Backs the Security Center's connected-accounts panel. Deliberately returns no provider
     * subject — see {@code OAuthQuery.SELECT_PROVIDER_LINKS_BY_USER_ID_QUERY} for why that
     * identifier stays in the database.
     *
     * @param userId the account whose links to list
     * @return the linked providers, oldest first; empty for an account that has never federated
     */
    List<ProviderLink> listLinks(Long userId);

    /**
     * Disconnects one identity provider from an account.
     *
     * <h3>The guard that matters</h3>
     * Refuses when the link being removed is the account's <em>last remaining way to sign in</em>
     * — that is, when it is the only linked provider and the account has no password. Allowing it
     * would leave the user locked out of their own account with no self-service path back, which
     * turns a settings toggle into an irreversible account loss. The check is performed here
     * rather than in the UI because it is a correctness property of the account, not a hint.
     *
     * <p>Idempotent in the safe direction: unlinking a provider that is not linked is a no-op that
     * reports no change, rather than an error. There is nothing for the caller to fix, and the
     * end state they asked for is the one they already have.
     *
     * @param userId   the account to modify — always the authenticated caller's own id, never a
     *                 value taken from the request body
     * @param provider the registration id to disconnect
     * @throws com.bob.angularspringbootfullstack.exception.ApiException when this is the account's
     *         last sign-in method
     */
    void unlinkProvider(Long userId, String provider);

    /**
     * One connected identity provider, as shown in the Security Center.
     *
     * @param provider  the registration id ({@code google} / {@code github} / {@code microsoft})
     * @param linkedAt  when the connection was established
     */
    record ProviderLink(String provider, LocalDateTime linkedAt) {
    }
}
