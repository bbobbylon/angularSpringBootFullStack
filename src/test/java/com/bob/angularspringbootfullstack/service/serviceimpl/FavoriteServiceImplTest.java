package com.bob.angularspringbootfullstack.service.serviceimpl;

import com.bob.angularspringbootfullstack.exception.ApiException;
import com.bob.angularspringbootfullstack.repo.FavoriteRepo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Behavioural guard for the one business rule {@link FavoriteServiceImpl} owns that {@code
 * FavoriteRepoImpl} deliberately does not: the per-user pin-count cap, and that re-pinning an
 * already-pinned destination never counts against it (idempotent add).
 */
@ExtendWith(MockitoExtension.class)
class FavoriteServiceImplTest {

    @Mock
    private FavoriteRepo favoriteRepo;

    @InjectMocks
    private FavoriteServiceImpl favoriteService;

    @Test
    @DisplayName("listFavorites delegates straight to the repo")
    void listFavoritesDelegates() {
        when(favoriteRepo.listByUserId(1L)).thenReturn(List.of("customers", "billing"));

        assertThat(favoriteService.listFavorites(1L)).containsExactly("customers", "billing");
    }

    @Test
    @DisplayName("addFavorite rejects a blank destination id before the repo is touched")
    void addFavoriteRejectsBlankId() {
        assertThatThrownBy(() -> favoriteService.addFavorite(1L, " "))
                .isInstanceOf(ApiException.class);

        verify(favoriteRepo, never()).add(1L, " ");
    }

    @Test
    @DisplayName("addFavorite pins a new destination under the cap")
    void addFavoriteUnderCapPins() {
        when(favoriteRepo.countByUserId(1L)).thenReturn(3);
        when(favoriteRepo.listByUserId(1L)).thenReturn(List.of("customers", "billing", "analytics", "security"));

        List<String> result = favoriteService.addFavorite(1L, "security");

        verify(favoriteRepo).add(1L, "security");
        assertThat(result).contains("security");
    }

    @Test
    @DisplayName("addFavorite refuses a new pin once the cap is reached")
    void addFavoriteAtCapRefusesNewPin() {
        List<String> eight = List.of("a", "b", "c", "d", "e", "f", "g", "h");
        when(favoriteRepo.countByUserId(1L)).thenReturn(8);
        when(favoriteRepo.listByUserId(1L)).thenReturn(eight);

        assertThatThrownBy(() -> favoriteService.addFavorite(1L, "i"))
                .isInstanceOf(ApiException.class);

        verify(favoriteRepo, never()).add(1L, "i");
    }

    @Test
    @DisplayName("re-pinning an already-pinned destination is allowed even at the cap")
    void addFavoriteAtCapAllowsRepinningExisting() {
        List<String> eight = List.of("a", "b", "c", "d", "e", "f", "g", "h");
        when(favoriteRepo.countByUserId(1L)).thenReturn(8);
        when(favoriteRepo.listByUserId(1L)).thenReturn(eight);

        favoriteService.addFavorite(1L, "a");

        verify(favoriteRepo).add(1L, "a");
    }

    @Test
    @DisplayName("removeFavorite delegates to the repo and returns the refreshed list")
    void removeFavoriteDelegates() {
        when(favoriteRepo.listByUserId(1L)).thenReturn(List.of("billing"));

        List<String> result = favoriteService.removeFavorite(1L, "customers");

        verify(favoriteRepo).remove(1L, "customers");
        assertThat(result).containsExactly("billing");
    }
}
