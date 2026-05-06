package com.bob.angularspringbootfullstack.utils;

import com.bob.angularspringbootfullstack.dto.UserDTO;
import com.bob.angularspringbootfullstack.model.UserPrincipal;
import org.springframework.security.core.Authentication;

/**
 * Extracts the {@link UserDTO} for the current user from a Spring Security
 * {@link Authentication}.
 *
 * <p><b>Why two methods?</b> Spring Security's {@code Authentication.getPrincipal()}
 * returns whatever object the code that built the {@code Authentication} chose to put
 * there. In this project there are <i>two</i> places where an {@code Authentication}
 * gets built, and they put different object types in the principal slot. Each method
 * below corresponds to one of those auth paths.
 *
 * <p><b>Path 1 — login (used by {@code POST /user/login}).</b>
 * {@code authenticationManager.authenticate(...)} runs the configured
 * {@code UserDetailsService}, which returns our {@link UserPrincipal} (a
 * {@code UserDetails} wrapper around a {@link UserDTO} plus the user's role). So at
 * the point the controller has the {@code Authentication}, calling
 * {@code getPrincipal()} returns a {@code UserPrincipal} — to reach the {@code UserDTO}
 * inside, you must cast and unwrap. That's what {@link #getLoggedInUser(Authentication)}
 * does.
 *
 * <p><b>Path 2 — token-authenticated requests (every other secured endpoint, e.g.
 * {@code GET /user/profile}).</b> {@code CustomAuthFilter} validates the bearer token
 * and then calls {@code TokenProvider.getAuthentication(email, authorities, request)},
 * which constructs a {@code UsernamePasswordAuthenticationToken} whose principal is
 * the {@link UserDTO} <i>directly</i> (loaded by email from the database). No
 * {@code UserPrincipal} wrapping happens on this path, because there is no
 * {@code UserDetailsService} call — the filter built the {@code Authentication} by
 * hand. Reaching the {@code UserDTO} is therefore a single cast, which is what
 * {@link #getAuthenticatedUser(Authentication)} does.
 *
 * <p><b>Why this used to be a single method, and what broke.</b> Earlier, the
 * controller called {@code authentication.getName()} on the {@code /profile} endpoint
 * to get the user's email. {@code Authentication.getName()} delegates to
 * {@code Principal.getName()}, but our {@code UserDTO} is not a {@code Principal} and
 * has no {@code getName()} override, so Spring fell back to {@code Object.toString()}.
 * That returned the entire Lombok-generated DTO toString (e.g.
 * {@code "UserDTO(id=1, firstName=Bob, email=..., ...)"}), which then failed the
 * email lookup with "no user found with email: UserDTO(id=1, ...)". Splitting into
 * two explicit, type-safe extractors prevents that whole class of mistake — each
 * call site declares which auth path it expects, and the cast fails fast if the
 * principal is the wrong type.
 *
 * <p><b>Calling the wrong method is a {@link ClassCastException}, not a silent
 * bug.</b> If you call {@link #getAuthenticatedUser(Authentication)} on a login-flow
 * {@code Authentication}, the cast {@code (UserDTO) principal} fails because the
 * principal is actually a {@code UserPrincipal}; conversely for
 * {@link #getLoggedInUser(Authentication)} on a request-flow {@code Authentication}.
 * That immediate failure is preferable to the toString bug it replaced.
 */
public class UserUtils {
    /**
     * Returns the principal cast directly to a {@link UserDTO}.
     *
     * <p>Use this on token-authenticated endpoints (everything served behind
     * {@code CustomAuthFilter}, e.g. {@code GET /user/profile}). On those requests
     * the filter has already validated the bearer token and called
     * {@code TokenProvider.getAuthentication(...)}, which stored the {@code UserDTO}
     * loaded from the database as the principal. No unwrapping is required — a
     * single cast yields the DTO, from which the controller can read fields like
     * {@code email} or {@code id} and continue processing.
     *
     * <p>This method exists specifically to replace the older
     * {@code authentication.getName()} approach, which silently fell through to
     * {@code UserDTO#toString()} and broke the downstream email lookup.
     *
     * @param authentication the current {@code Authentication} pulled from the
     *                       SecurityContext; must have a {@link UserDTO} principal
     *                       (i.e. produced by {@code CustomAuthFilter}, not by
     *                       {@code AuthenticationManager})
     * @return the {@link UserDTO} stored as the principal
     * @throws ClassCastException if the principal is not a {@code UserDTO}, which
     *                            means the caller is on the wrong auth path —
     *                            use {@link #getLoggedInUser(Authentication)}
     *                            instead
     */
    public static UserDTO getAuthenticatedUser(Authentication authentication) {
        return ((UserDTO) authentication.getPrincipal());
    }

    /**
     * Unwraps the {@link UserPrincipal} principal and returns the {@link UserDTO}
     * it carries.
     *
     * <p>Use this immediately after
     * {@code AuthenticationManager.authenticate(...)} in the {@code /user/login}
     * flow. {@code AuthenticationManager} runs our {@code UserDetailsService},
     * which returns a {@link UserPrincipal} (a {@code UserDetails} wrapper holding
     * a {@code UserDTO} plus the user's {@code Role}). The wrapper is what Spring
     * Security needs for password checks and authority handling, but the controller
     * only needs the inner {@code UserDTO} to decide whether to send a 2FA code or
     * issue tokens — this method extracts it in one step.
     *
     * <p>This method is only correct on a freshly-authenticated login
     * {@code Authentication}. Authenticated requests served via
     * {@code CustomAuthFilter} use a different principal type
     * ({@code UserDTO} directly) and require
     * {@link #getAuthenticatedUser(Authentication)}.
     *
     * @param authentication the {@code Authentication} returned by
     *                       {@code AuthenticationManager.authenticate(...)};
     *                       its principal must be a {@link UserPrincipal}
     * @return the {@link UserDTO} carried inside the {@code UserPrincipal}
     * @throws ClassCastException if the principal is not a {@code UserPrincipal},
     *                            which means the caller is on the wrong auth path —
     *                            use {@link #getAuthenticatedUser(Authentication)}
     *                            instead
     */
    public static UserDTO getLoggedInUser(Authentication authentication) {
        return ((UserPrincipal) authentication.getPrincipal()).getUser();
    }
}
