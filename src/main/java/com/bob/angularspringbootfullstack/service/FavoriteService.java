package com.bob.angularspringbootfullstack.service;

import java.util.List;

/**
 * Business logic for the favorites / pinned-destinations bar (FUTURE-ENHANCEMENTS.md §3.3).
 *
 * @see com.bob.angularspringbootfullstack.controller.FavoriteController
 */
public interface FavoriteService {

    /**
     * Lists the destination ids the given user has pinned.
     *
     * @param userId the user's id
     * @return the pinned destination ids, oldest first
     */
    List<String> listFavorites(Long userId);

    /**
     * Pins a destination for a user, refusing beyond the per-user pin cap.
     *
     * @param userId        the user's id
     * @param destinationId the destination id to pin
     * @return the user's favorites after the change
     */
    List<String> addFavorite(Long userId, String destinationId);

    /**
     * Unpins a destination for a user.
     *
     * @param userId        the user's id
     * @param destinationId the destination id to unpin
     * @return the user's favorites after the change
     */
    List<String> removeFavorite(Long userId, String destinationId);
}
