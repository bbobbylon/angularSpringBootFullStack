package com.bob.angularspringbootfullstack.service.serviceimpl;

import com.bob.angularspringbootfullstack.exception.ApiException;
import com.bob.angularspringbootfullstack.repo.FavoriteRepo;
import com.bob.angularspringbootfullstack.service.FavoriteService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Default {@link FavoriteService} implementation.
 * <p>
 * Owns the one business rule this domain has — the per-user pin-count cap — deliberately kept out
 * of {@link com.bob.angularspringbootfullstack.repo.repoimpl.FavoriteRepoImpl}, which is pure data
 * access, per this codebase's convention of keeping business logic in the service layer.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class FavoriteServiceImpl implements FavoriteService {

    /**
     * Maximum destinations a single user may pin. A personal-convenience cap, not a data-integrity
     * boundary — chosen so the bar stays a quick-access strip rather than a second navbar.
     */
    private static final int MAX_FAVORITES = 8;

    private final FavoriteRepo favoriteRepo;

    @Override
    public List<String> listFavorites(Long userId) {
        return favoriteRepo.listByUserId(userId);
    }

    @Override
    public List<String> addFavorite(Long userId, String destinationId) {
        if (destinationId == null || destinationId.isBlank()) {
            throw new ApiException("A destination id is required.");
        }
        // Idempotent add: re-pinning an existing favorite must not count against the cap, so the
        // count check only matters on a genuinely new pin. INSERT IGNORE downstream makes this
        // race-tolerant enough for a personal-preference feature — a double-click racing two
        // requests can at worst land exactly at the cap, never meaningfully over it.
        if (favoriteRepo.countByUserId(userId) >= MAX_FAVORITES && !favoriteRepo.listByUserId(userId).contains(destinationId)) {
            log.info("User id {} hit the {}-favorite cap pinning '{}'", userId, MAX_FAVORITES, destinationId);
            throw new ApiException("You can only pin up to " + MAX_FAVORITES + " destinations — unpin one first.");
        }
        favoriteRepo.add(userId, destinationId);
        return favoriteRepo.listByUserId(userId);
    }

    @Override
    public List<String> removeFavorite(Long userId, String destinationId) {
        favoriteRepo.remove(userId, destinationId);
        return favoriteRepo.listByUserId(userId);
    }
}
