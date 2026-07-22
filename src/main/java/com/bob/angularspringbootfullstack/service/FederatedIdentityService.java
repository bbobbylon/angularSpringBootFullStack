package com.bob.angularspringbootfullstack.service;

import com.bob.angularspringbootfullstack.dto.UserDTO;

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
}
