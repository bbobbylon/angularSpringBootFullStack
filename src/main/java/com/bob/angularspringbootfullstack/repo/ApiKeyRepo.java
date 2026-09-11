package com.bob.angularspringbootfullstack.repo;

import com.bob.angularspringbootfullstack.model.ApiKey;

import java.util.List;
import java.util.Optional;

/**
 * Data access contract for the {@code apikeys} table (FUTURE-ENHANCEMENTS.md §3.1, P2-3 Option A).
 */
public interface ApiKeyRepo {

    /**
     * Inserts a new key row.
     *
     * @param data the key to create; {@code id} is ignored and overwritten with the generated key
     * @return {@code data}, mutated in place with its generated id
     */
    ApiKey create(ApiKey data);

    /**
     * Resolves an active (not revoked, not expired) key by its hash.
     *
     * @param keyHash the SHA-256 hex digest of the raw key
     * @return the matching key, or empty if no active key has that hash
     */
    Optional<ApiKey> findActiveByHash(String keyHash);

    /**
     * Resolves one key by id, regardless of its revoked/expired state.
     *
     * @param id the key's id
     * @return the matching key, or empty if no key has that id
     */
    Optional<ApiKey> findById(Long id);

    /**
     * Lists every key — active, revoked, and expired — belonging to one service account, newest
     * first.
     *
     * @param userId the service account's {@code users.id}
     * @return the account's full key history
     */
    List<ApiKey> findByUserId(Long userId);

    /**
     * Marks a key revoked. Idempotent.
     *
     * @param id the key's id
     */
    void revoke(Long id);

    /**
     * Stamps {@code last_used_at = NOW()} on a key. Best-effort; failures are logged, not thrown.
     *
     * @param id the key's id
     */
    void touchLastUsed(Long id);
}
