package com.bob.angularspringbootfullstack.filter;

import com.bob.angularspringbootfullstack.dto.UserDTO;
import com.bob.angularspringbootfullstack.service.ApiKeyService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static com.bob.angularspringbootfullstack.constants.Constants.API_KEY_HEADER;
import static com.bob.angularspringbootfullstack.constants.Constants.HTTP_METHOD_OPTIONS;
import static com.bob.angularspringbootfullstack.constants.Constants.TOKEN_PREFIX;
import static com.bob.angularspringbootfullstack.utils.ExceptionUtils.processError;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static java.util.stream.Collectors.toList;

/**
 * Per-request API-key authentication filter (FUTURE-ENHANCEMENTS.md §3.1, P2-3 Option A).
 * <p>
 * Registered {@code .addFilterBefore(apiKeyAuthFilter, CustomAuthFilter.class)}. Acts <b>only</b>
 * when an {@code X-API-Key} header is present <b>and</b> no {@code Authorization: Bearer} header
 * is present — so exactly one of this filter or {@link CustomAuthFilter} ever authenticates a
 * given request, with no precedence ambiguity to reason about. In the common case a request
 * carrying only an API key has no {@code Authorization} header at all, so
 * {@code CustomAuthFilter#shouldNotFilter} already skips it independently of this rule.
 * <p>
 * On a valid key, builds the exact same {@code Authentication} shape
 * {@code TokenProvider#getAuthentication} builds for a JWT — a
 * {@link UsernamePasswordAuthenticationToken} carrying the resolved {@link UserDTO} as principal
 * and one {@link SimpleGrantedAuthority} per entry in its comma-separated {@code permissions}
 * string — so every {@code @AuthenticationPrincipal UserDTO} handler and
 * {@code hasAnyAuthority(...)} rule downstream works completely unchanged, whether the caller
 * authenticated via JWT or API key.
 * <p>
 * An unknown, revoked, or expired key is treated exactly like an invalid JWT in
 * {@link CustomAuthFilter}: the security context is left clear and the filter chain continues, so
 * {@code SecurityConfig}'s authorization rules and 401 entry point produce the response — this
 * filter never itself rejects a request.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ApiKeyAuthFilter extends OncePerRequestFilter {

    private final ApiKeyService apiKeyService;

    /**
     * Skips this filter for every request except one carrying {@code X-API-Key} and no
     * {@code Authorization: Bearer} header.
     *
     * @param request current HTTP request
     * @return {@code true} to skip filtering; {@code false} to run {@link #doFilterInternal}
     */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String apiKey = request.getHeader(API_KEY_HEADER);
        String authorization = request.getHeader(AUTHORIZATION);
        return apiKey == null || apiKey.isBlank()
                || (authorization != null && authorization.startsWith(TOKEN_PREFIX))
                || request.getMethod().equalsIgnoreCase(HTTP_METHOD_OPTIONS);
    }

    /**
     * Resolves the {@code X-API-Key} header and, on a hit, installs an Authentication in the
     * SecurityContext for the rest of the chain. Any exception is funneled through
     * {@code ExceptionUtils#processError} exactly like {@link CustomAuthFilter} does, and the
     * filter chain always continues so downstream handlers can run.
     */
    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response, @NonNull FilterChain filterChain) {
        try {
            Optional<UserDTO> resolved = apiKeyService.resolve(request.getHeader(API_KEY_HEADER));
            if (resolved.isPresent()) {
                UserDTO userDTO = resolved.get();
                List<GrantedAuthority> authorities = authoritiesOf(userDTO);
                Authentication authentication = new UsernamePasswordAuthenticationToken(userDTO, null, authorities);
                ((UsernamePasswordAuthenticationToken) authentication).setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                SecurityContextHolder.getContext().setAuthentication(authentication);
            } else {
                SecurityContextHolder.clearContext();
            }
            filterChain.doFilter(request, response);
        } catch (Exception e) {
            log.error(e.getMessage());
            processError(request, response, e);
        }
    }

    /**
     * Splits the resolved user's comma-separated {@code permissions} string into one
     * {@link SimpleGrantedAuthority} per entry, trimming whitespace — identical derivation to
     * {@code UserPrincipal#getAuthorities}, applied directly to the DTO since
     * {@code ApiKeyService#resolve} already flattened the role's permission string onto it (no
     * second database round trip to re-fetch the role).
     */
    private List<GrantedAuthority> authoritiesOf(UserDTO userDTO) {
        String permissions = userDTO.getPermissions();
        if (permissions == null || permissions.isBlank()) {
            return List.of();
        }
        return Arrays.stream(permissions.split(","))
                .map(p -> new SimpleGrantedAuthority(p.trim()))
                .collect(toList());
    }
}
