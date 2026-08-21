package com.bob.angularspringbootfullstack.controller;

import com.bob.angularspringbootfullstack.dto.UserDTO;
import com.bob.angularspringbootfullstack.exception.GlobalExceptionHandler;
import com.bob.angularspringbootfullstack.service.FavoriteService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Behavioural guard for {@link FavoriteController}. Uses {@link MockMvcBuilders#standaloneSetup}
 * with a Mockito mock and the real {@link GlobalExceptionHandler}, matching {@code
 * RoleControllerTest}'s convention — the {@code Authentication} is injected via {@code
 * principal(...)}, resolved as a raw {@link java.security.Principal} controller parameter without
 * needing the real security filter chain.
 *
 * <p>Deliberately does not re-test the {@code /user/favorites/**} {@code authenticated()}
 * {@code SecurityConfig} matcher itself — that belongs to {@code
 * SecurityFilterChainIntegrationTest}, which drives real HTTP through the genuine filter chain.
 * This suite only proves each endpoint scopes its work to the caller's own id and delegates
 * correctly to {@link FavoriteService}.
 */
class FavoriteControllerTest {

    private FavoriteService favoriteService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        favoriteService = mock(FavoriteService.class);
        FavoriteController controller = new FavoriteController(favoriteService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private static Authentication userAuth(long id) {
        UserDTO user = new UserDTO();
        user.setId(id);
        user.setEmail("user@example.com");
        user.setRoleName("ROLE_USER");
        return new UsernamePasswordAuthenticationToken(
                user, null, AuthorityUtils.createAuthorityList("READ:USER"));
    }

    @Test
    @DisplayName("listFavorites returns the caller's own favorites")
    void listFavoritesReturnsCallersFavorites() throws Exception {
        when(favoriteService.listFavorites(7L)).thenReturn(List.of("customers", "billing"));

        mockMvc.perform(get("/user/favorites").principal(userAuth(7L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.favorites[0]").value("customers"))
                .andExpect(jsonPath("$.data.favorites[1]").value("billing"));

        verify(favoriteService).listFavorites(7L);
    }

    @Test
    @DisplayName("addFavorite pins the destination for the caller's own id")
    void addFavoritePinsForCaller() throws Exception {
        when(favoriteService.addFavorite(eq(7L), anyString())).thenReturn(List.of("customers"));

        mockMvc.perform(post("/user/favorites/{destinationId}", "customers").principal(userAuth(7L)))
                .andExpect(status().isOk());

        verify(favoriteService).addFavorite(7L, "customers");
    }

    @Test
    @DisplayName("addFavorite surfaces the cap-exceeded ApiException as a client error, not a 500")
    void addFavoriteSurfacesCapExceeded() throws Exception {
        when(favoriteService.addFavorite(eq(7L), anyString()))
                .thenThrow(new com.bob.angularspringbootfullstack.exception.ApiException(
                        "You can only pin up to 8 destinations — unpin one first."));

        mockMvc.perform(post("/user/favorites/{destinationId}", "security").principal(userAuth(7L)))
                .andExpect(status().is4xxClientError());
    }

    @Test
    @DisplayName("removeFavorite unpins the destination for the caller's own id")
    void removeFavoriteUnpinsForCaller() throws Exception {
        when(favoriteService.removeFavorite(eq(7L), anyString())).thenReturn(List.of());

        mockMvc.perform(delete("/user/favorites/{destinationId}", "customers").principal(userAuth(7L)))
                .andExpect(status().isOk());

        verify(favoriteService).removeFavorite(7L, "customers");
    }
}
