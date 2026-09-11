package com.bob.angularspringbootfullstack.service;

import com.bob.angularspringbootfullstack.dto.UserDTO;

import java.util.List;

/**
 * Creates, lists, and deactivates service accounts — non-human {@code users} rows
 * (FUTURE-ENHANCEMENTS.md §3.1, P2-3 Option A) that authenticate via API key
 * ({@code ApiKeyService}, {@code ApiKeyAuthFilter}) instead of a password.
 */
public interface ServiceAccountService {

    /**
     * Creates a new service account with a synthetic, unguessable email and password, and assigns
     * it the given role.
     *
     * <p>Enforces the same tier ceiling human role reassignment already enforces
     * ({@link com.bob.angularspringbootfullstack.enumeration.RoleType#canAssign}): the creating
     * admin cannot grant a service account a role above their own.
     *
     * @param name            a caller-chosen display name (e.g. "CI pipeline")
     * @param roleName        the role to assign, e.g. {@code ROLE_MODERATOR}
     * @param createdByUserId the admin creating the account, whose role bounds {@code roleName}
     * @return the newly created service account
     */
    UserDTO create(String name, String roleName, Long createdByUserId);

    /**
     * Lists every service account, newest first.
     *
     * @return every {@code users} row with {@code origin = Constants.SERVICE_ACCOUNT_ORIGIN}
     */
    List<UserDTO> list();

    /**
     * Disables a service account — {@code enabled = false} — so it can no longer authenticate,
     * whether or not it still holds unrevoked API keys (see {@code ApiKeyServiceImpl#resolve}).
     *
     * @param id the service account's {@code users.id}
     */
    void deactivate(Long id);
}
