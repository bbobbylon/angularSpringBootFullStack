package com.bob.angularspringbootfullstack.repo;

import java.util.List;

/**
 * Data access contract for the favorites / pinned-destinations bar (FUTURE-ENHANCEMENTS.md §3.3).
 * <p>
 * Deliberately not generic over a model type, unlike {@link RoleRepo}: {@code userfavorites} has
 * no independent identity worth modeling beyond the {@code (userId, destinationId)} pair itself,
 * so this contract works directly in those primitives rather than introducing a {@code Favorite}
 * entity purely to satisfy a convention that does not pay for itself here.
 */
public interface FavoriteRepo {

    /**
     * Lists the destination ids the given user has pinned, oldest first.
     *
     * @param userId the user's id
     * @return the pinned destination ids, possibly empty
     */
    List<String> listByUserId(Long userId);

    /**
     * Counts how many destinations the given user has pinned.
     *
     * @param userId the user's id
     * @return the pin count
     */
    int countByUserId(Long userId);

    /**
     * Pins a destination for a user. Idempotent — pinning an already-pinned destination is a
     * no-op.
     *
     * @param userId        the user's id
     * @param destinationId the destination id to pin
     */
    void add(Long userId, String destinationId);

    /**
     * Unpins a destination for a user. Idempotent — unpinning a destination that was never pinned
     * is a no-op.
     *
     * @param userId        the user's id
     * @param destinationId the destination id to unpin
     */
    void remove(Long userId, String destinationId);
}
