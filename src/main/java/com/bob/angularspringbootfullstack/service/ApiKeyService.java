package com.bob.angularspringbootfullstack.service;

import com.bob.angularspringbootfullstack.dto.ApiKeyDTO;
import com.bob.angularspringbootfullstack.dto.UserDTO;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Issues, resolves, revokes, and lists API keys (FUTURE-ENHANCEMENTS.md §3.1, P2-3 Option A).
 * <p>
 * The service layer, not {@code ApiKeyRepo}, owns the raw-key generation and hashing — those are
 * business rules (what the key looks like, how it is protected at rest), not data-access concerns,
 * per this project's convention (see {@code documentation` backend blueprint).
 */
public interface ApiKeyService {

    /**
     * Mints a new key for a service account and returns the raw key exactly once — it is never
     * retrievable again, only {@link ApiKeyDTO#getKeyPrefix()} is, for display.
     *
     * @param serviceAccountUserId the service account's {@code users.id}
     * @param name                 a caller-chosen label (e.g. "CI pipeline")
     * @param expiresAt            optional expiry; {@code null} means the key never expires
     * @param issuedByUserId       the admin performing the issuance, for {@code apikeys.created_by}
     * @return the raw key, in {@code tsk_...} form
     */
    String issue(Long serviceAccountUserId, String name, LocalDateTime expiresAt, Long issuedByUserId);

    /**
     * Resolves a raw key presented on a request to the service-account user it authenticates as.
     * Used exclusively by {@code ApiKeyAuthFilter}. Also stamps {@code last_used_at} on a hit.
     *
     * @param rawKey the raw key from the {@code X-API-Key} header
     * @return the authenticated {@link UserDTO}, or empty if the key is unknown, revoked, or expired
     */
    Optional<UserDTO> resolve(String rawKey);

    /**
     * Resolves one key by id, regardless of its revoked/expired state. Used by
     * {@code AdminServiceAccountController} to verify a {@code keyId} path segment actually
     * belongs to the {@code serviceAccountId} path segment it is nested under before acting on it,
     * and to look up the owning service account's email for the revoke audit event.
     *
     * @param keyId the key's id
     * @return the matching key, or empty if no key has that id
     */
    Optional<ApiKeyDTO> findById(Long keyId);

    /**
     * Revokes one key. Idempotent.
     *
     * @param keyId the key's id
     */
    void revoke(Long keyId);

    /**
     * Lists every key — active, revoked, expired — belonging to one service account, newest first.
     * Never exposes the hash or a raw key.
     *
     * @param serviceAccountUserId the service account's {@code users.id}
     * @return the account's key history
     */
    List<ApiKeyDTO> listForUser(Long serviceAccountUserId);
}
