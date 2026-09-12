package com.bob.angularspringbootfullstack.repo;

import com.bob.angularspringbootfullstack.model.OAuthClient;

import java.util.List;
import java.util.Optional;

/**
 * Data access contract for the {@code oauthclients} table (FUTURE-ENHANCEMENTS.md §3.1, P2-3
 * Option B).
 */
public interface OAuthClientRepo {

    /**
     * Inserts a new client-credentials row.
     *
     * @param data the client to create; {@code id} is ignored and overwritten with the generated id
     * @return {@code data}, mutated in place with its generated id
     */
    OAuthClient create(OAuthClient data);

    /**
     * Resolves a client_id to its row, regardless of revoked state.
     *
     * @param clientId the public client_id presented at the token endpoint
     * @return the matching client, or empty if no client has that id
     */
    Optional<OAuthClient> findByClientId(String clientId);

    /**
     * Resolves one client by id, regardless of its revoked state.
     *
     * @param id the client's id
     * @return the matching client, or empty if no client has that id
     */
    Optional<OAuthClient> findById(Long id);

    /**
     * Lists every client-credentials pair — active and revoked — belonging to one service
     * account, newest first.
     *
     * @param userId the service account's {@code users.id}
     * @return the account's full client history
     */
    List<OAuthClient> findByUserId(Long userId);

    /**
     * Marks a client revoked. Idempotent.
     *
     * @param id the client's id
     */
    void revoke(Long id);

    /**
     * Stamps {@code last_used_at = NOW()} on a client. Best-effort; failures are logged, not
     * thrown.
     *
     * @param id the client's id
     */
    void touchLastUsed(Long id);
}
