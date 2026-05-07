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

import static com.bob.angularspringbootfullstack.utils.ExceptionUtils.processError;
import static java.util.Arrays.asList;
import static java.util.Optional.ofNullable;
import static org.apache.commons.lang3.StringUtils.EMPTY;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;

/**
 * Per-request JWT authentication filter.
 * <p>
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
     * Key for storing JWT token in the request values map, no longer needed since we are now getting the user ID directly from the token instead of the email, but retained for reference in case we need to revert back to the previous implementation.
     */
    // protected static final String TOKEN_KEY = "token";
    /**
     * Key previously used for storing the JWT subject (user email) in the request values map.
     * Retained for reference; the filter now uses {@link #getUserID(HttpServletRequest)} directly.
     */
    // protected static final String EMAIL_KEY = "email";
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
     * <p>
     * Reads the user ID via {@link #getUserID(HttpServletRequest)} and the raw token,
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
            // no longer needed since we retired the getValuesRequest Map<String, String> values = getRequestValues(request);
            String token = getToken(request);
            Long userID = getUserID(request);
            if (tokenProvider.isTokenValid(userID, token)) {
                List<GrantedAuthority> authorities = tokenProvider.getAuthorities(token);
                if (authorities == null || authorities.isEmpty()) {
                    // do not authenticate with a token that lacks authorities; leave security context cleared
                    SecurityContextHolder.clearContext();
                } else {
                    Authentication authentication = tokenProvider.getAuthentication(userID, authorities, request);
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

    // getRequestValues is no longer used, since we are getting the user ID instead of the user email.
    /**
     * Extracts the email and token from the request for downstream processing.
     *
     * @param request HTTP request
     * @return Map with keys EMAIL_KEY and TOKEN_KEY
     */
/*    private Map<String, String> getRequestValues(HttpServletRequest request) {
        return of(EMAIL_KEY, tokenProvider.getSubject(getToken(request), request), TOKEN_KEY, getToken(request));
    }*/

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

    /**
     * Extracts and returns the numeric user ID from the JWT subject claim.
     *
     * @param request the current HTTP request
     * @return the user's numeric ID parsed from the access token subject
     */
    private Long getUserID(HttpServletRequest request) {
        return tokenProvider.getSubject(getToken(request), request);
    }
}
