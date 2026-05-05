package com.bob.angularspringbootfullstack.filter;

import com.bob.angularspringbootfullstack.tokenprovider.TokenProvider;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static com.bob.angularspringbootfullstack.utils.ExceptionUtils.processError;
import static java.util.Arrays.asList;
import static java.util.Map.of;
import static java.util.Optional.ofNullable;
import static org.apache.commons.lang3.StringUtils.EMPTY;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;

/**
 * Per-request JWT authentication filter.
 *
 * Skips public routes and OPTIONS preflights, otherwise pulls the bearer token
 * off the Authorization header and asks TokenProvider to validate it. When the
 * token has authorities (an access token) the filter installs an Authentication
 * in the SecurityContext; when it has none (a refresh token, only valid at
 * /user/refresh/token) the context is cleared so it can't be used to satisfy
 * authority checks. The filter chain always continues so SecurityConfig's
 * authorization rules and entry point can produce the right response.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class CustomAuthFilter extends OncePerRequestFilter {
    /**
     * Key for storing JWT token in the request values map.
     */
    protected static final String TOKEN_KEY = "token";
    /**
     * Key for storing user email in the request values map.
     */
    protected static final String EMAIL_KEY = "email";
    private static final String HTTP_METHOD_OPTIONS = "OPTIONS";
    private static final String TOKEN_PREFIX = "Bearer ";
    /**
     * Public endpoints that do not require authentication.
     */
    private static final String[] PUBLIC_ROUTES = {"/user/login", "/user/verify/code", "/user/register", "/actuator", "/user/refresh/token"};
    private final TokenProvider tokenProvider;

    /**
     * Determines whether this filter should run for the current request.
     *
     * <p>The filter is skipped when:
     * <ul>
     *   <li>There is no {@code Authorization: Bearer ...} header</li>
     *   <li>The request is an {@code OPTIONS} preflight</li>
     *   <li>The request URI is one of {@link #PUBLIC_ROUTES}</li>
     * </ul>
     *
     * @param request current HTTP request
     * @return {@code true} to skip filtering; {@code false} to run {@link #doFilterInternal}
     */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) throws ServletException {

        return request.getHeader(AUTHORIZATION) == null || !request.getHeader(AUTHORIZATION).startsWith(TOKEN_PREFIX) || request.getMethod().equalsIgnoreCase(HTTP_METHOD_OPTIONS) || asList(PUBLIC_ROUTES).contains(request.getRequestURI());
    }

    /**
     * Validates the bearer token and, when it carries authorities, installs an
     * Authentication in the SecurityContext for the rest of the chain.
     *
     * Reads email and token via {@link #getRequestValues(HttpServletRequest)},
     * checks validity with TokenProvider, and pulls authorities from the
     * token. An empty authorities list means a refresh token was sent to a
     * non-refresh route, so the SecurityContext is cleared instead of
     * authenticated; the SecurityConfig rules then return 401. Any exception
     * during processing is funneled through ExceptionUtils#processError so
     * the client gets a JSON error matching HttpResponse. The filter chain is
     * always continued so downstream handlers can run.
     *
     * @param request     the current HTTP request
     * @param response    the current HTTP response
     * @param filterChain the rest of the filter chain
     * @throws ServletException if a downstream filter throws
     * @throws IOException      if writing to the response fails
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain) throws ServletException, IOException {
        try {
            Map<String, String> values = getRequestValues(request);
            String token = getToken(request);
            if (tokenProvider.isTokenValid(values.get(EMAIL_KEY), token)) {
                List<GrantedAuthority> authorities = tokenProvider.getAuthorities(values.get(TOKEN_KEY));
                if (authorities == null || authorities.isEmpty()) {
                    // do not authenticate with a token that lacks authorities; leave security context cleared
                    SecurityContextHolder.clearContext();
                } else {
                    Authentication authentication = tokenProvider.getAuthentication(values.get(EMAIL_KEY), authorities, request);
                    SecurityContextHolder.getContext().setAuthentication(authentication);
                }
            } else {
                //clear the context of the thread if the token is invalid since the user would be not authenticated
                SecurityContextHolder.clearContext();
            }
            // this is to keep the filter chain going, as we can't just stop the filter chain if the token is invalid. There may be other filters in the request that need to be executed.
            filterChain.doFilter(request, response);
        } catch (Exception e) {
            log.error(e.getMessage());
            processError(request, response, e);
        }
    }

    /**
     * Extracts the email and token from the request for downstream processing.
     *
     * @param request HTTP request
     * @return Map with keys EMAIL_KEY and TOKEN_KEY
     */
    Map<String, String> getRequestValues(HttpServletRequest request) {
        return of(EMAIL_KEY, tokenProvider.getSubject(getToken(request), request), TOKEN_KEY, getToken(request));
    }

    /**
     * Retrieves the JWT token from the Authorization header.
     *
     * @param request HTTP request
     * @return JWT token string (without Bearer prefix)
     */
    private String getToken(HttpServletRequest request) {
        return ofNullable(request.getHeader(AUTHORIZATION))
                .filter(header -> header.startsWith(TOKEN_PREFIX))
                .map(token -> token.replace(TOKEN_PREFIX, EMPTY)).get();
    }
}
