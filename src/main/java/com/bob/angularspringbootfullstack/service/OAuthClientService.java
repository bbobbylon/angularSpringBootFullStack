package com.bob.angularspringbootfullstack.service;

import com.bob.angularspringbootfullstack.dto.OAuthClientCredentials;
import com.bob.angularspringbootfullstack.dto.OAuthClientDTO;

import java.util.List;
import java.util.Optional;

/**
 * Registers, authenticates, revokes, and lists OAuth2 client-credentials pairs
 * (FUTURE-ENHANCEMENTS.md §3.1, P2-3 Option B — RFC 6749 §4.4).
 * <p>
 * The service layer, not {@code OAuthClientRepo}, owns raw credential generation and hashing —
 * same convention as {@link ApiKeyService}.
 */
public interface OAuthClientService {

    /**
     * Registers a new client-credentials pair for a service account and returns both halves
     * exactly once — the secret is never retrievable again, only
     * {@link OAuthClientDTO#getClientId()} is, for display and for the caller to present at
     * {@code POST /oauth/token}.
     *
     * @param serviceAccountUserId the service account's {@code users.id}
     * @param name                 a caller-chosen label (e.g. "CI pipeline")
     * @param issuedByUserId       the admin performing the registration, for
     *                             {@code oauthclients.created_by}
     * @return the raw {@code client_id}/{@code client_secret} pair
     */
    OAuthClientCredentials register(Long serviceAccountUserId, String name, Long issuedByUserId);

    /**
     * Verifies a presented {@code client_id}/{@code client_secret} pair (RFC 6749 §4.4) and, on
     * success, mints a signed access token carrying the owning service account's authorities.
     * Used exclusively by {@code OAuthTokenController}. Stamps {@code last_used_at} on a hit.
     * <p>
     * Fails uniformly — an unknown client_id, a wrong secret, a revoked client, and a deactivated
     * service account are all indistinguishable to the caller — same anti-enumeration posture as
     * {@link ApiKeyService#resolve} and every other authentication path in this codebase.
     *
     * @param clientId     the presented client_id
     * @param clientSecret the presented client_secret
     * @return a signed JWT access token, or empty if authentication fails for any reason
     */
    Optional<String> authenticate(String clientId, String clientSecret);

    /**
     * Resolves one client by id, regardless of its revoked state. Used by
     * {@code AdminServiceAccountController} to verify a {@code clientRowId} path segment actually
     * belongs to the {@code serviceAccountId} path segment it is nested under before acting on it.
     *
     * @param id the client's id
     * @return the matching client, or empty if no client has that id
     */
    Optional<OAuthClientDTO> findById(Long id);

    /**
     * Revokes one client-credentials pair. Idempotent. Any access token already minted from it
     * keeps working until its own TTL expires — see {@code EventType#OAUTH_CLIENT_REVOKED}'s
     * Javadoc for why this is an accepted, deliberate tradeoff rather than a gap.
     *
     * @param id the client's id
     */
    void revoke(Long id);

    /**
     * Lists every client-credentials pair — active and revoked — belonging to one service
     * account, newest first. Never exposes the secret hash or a raw secret.
     *
     * @param serviceAccountUserId the service account's {@code users.id}
     * @return the account's client history
     */
    List<OAuthClientDTO> listForUser(Long serviceAccountUserId);
}
