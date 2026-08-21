package com.bob.angularspringbootfullstack.controller;

import com.bob.angularspringbootfullstack.dto.UserDTO;
import com.bob.angularspringbootfullstack.model.HttpResponse;
import com.bob.angularspringbootfullstack.service.FavoriteService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static com.bob.angularspringbootfullstack.utils.UserUtils.getAuthenticatedUser;
import static java.time.LocalTime.now;
import static java.util.Map.of;
import static org.springframework.http.HttpStatus.OK;

/**
 * REST endpoints for the favorites / pinned-destinations bar (FUTURE-ENHANCEMENTS.md §3.3,
 * POST-SUBMISSION-UPGRADES.md).
 * <p>
 * A personal-preference resource, not an administrative one: every method here operates only on
 * the calling user's own row set, scoped by the id read off {@code Authentication} — there is no
 * authority narrower than "signed in" to require, mirroring {@link TotpController}'s
 * {@code /user/totp/**} and {@code /user/sessions/**}'s posture. {@code SecurityConfig} matches
 * {@code /user/favorites/**} with an explicit {@code authenticated()} rule ahead of the broad
 * {@code POST}/{@code DELETE} catch-alls, which otherwise demand {@code UPDATE:USER} /
 * {@code UPDATE:CUSTOMER} — authorities a plain {@code ROLE_USER} account does not hold and has no
 * reason to need just to pin its own navigation shortcuts.
 * <p>
 * {@code destinationId} is treated as an opaque string end to end (see {@code schema.sql}'s
 * comment on {@code userfavorites} and {@link FavoriteService} for why it is never validated
 * against the frontend's command-palette registry here).
 */
@RestController
@RequestMapping(path = "/user/favorites")
@RequiredArgsConstructor
@Slf4j
public class FavoriteController {

    private final FavoriteService favoriteService;

    /**
     * Lists the caller's pinned destinations.
     *
     * @param authentication the caller's authentication
     * @return 200 OK with the pinned destination ids
     */
    @GetMapping
    public ResponseEntity<HttpResponse> listFavorites(Authentication authentication) {
        UserDTO user = getAuthenticatedUser(authentication);
        return ResponseEntity.ok(
                HttpResponse.builder()
                        .timeStamp(now().toString())
                        .data(of("favorites", favoriteService.listFavorites(user.getId())))
                        .message("Favorites retrieved successfully.")
                        .status(OK)
                        .statusCode(OK.value())
                        .build());
    }

    /**
     * Pins a destination for the caller. Idempotent, and refused beyond the per-user pin cap
     * ({@link com.bob.angularspringbootfullstack.service.serviceimpl.FavoriteServiceImpl}).
     *
     * @param authentication the caller's authentication
     * @param destinationId  the destination id to pin
     * @return 200 OK with the caller's favorites after the change
     */
    @PostMapping("/{destinationId}")
    public ResponseEntity<HttpResponse> addFavorite(Authentication authentication, @PathVariable String destinationId) {
        UserDTO user = getAuthenticatedUser(authentication);
        return ResponseEntity.ok(
                HttpResponse.builder()
                        .timeStamp(now().toString())
                        .data(of("favorites", favoriteService.addFavorite(user.getId(), destinationId)))
                        .message("Pinned.")
                        .status(OK)
                        .statusCode(OK.value())
                        .build());
    }

    /**
     * Unpins a destination for the caller. Idempotent — unpinning a destination that was never
     * pinned still returns 200 with the caller's (unchanged) favorites.
     *
     * @param authentication the caller's authentication
     * @param destinationId  the destination id to unpin
     * @return 200 OK with the caller's favorites after the change
     */
    @DeleteMapping("/{destinationId}")
    public ResponseEntity<HttpResponse> removeFavorite(Authentication authentication, @PathVariable String destinationId) {
        UserDTO user = getAuthenticatedUser(authentication);
        return ResponseEntity.ok(
                HttpResponse.builder()
                        .timeStamp(now().toString())
                        .data(of("favorites", favoriteService.removeFavorite(user.getId(), destinationId)))
                        .message("Unpinned.")
                        .status(OK)
                        .statusCode(OK.value())
                        .build());
    }
}
