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
 * Custom authentication filter for JWT-based security.
 * <p>
 * Intercepts incoming HTTP requests and processes JWT tokens for authentication.
 * <ul>
 *   <li>Skips filtering for public routes (login, register, 2FA verification, actuator) and OPTIONS requests.</li>
 *   <li>Extracts and validates JWT from the Authorization header.</li>
 *   <li>If valid, sets the authentication in the Spring Security context using TokenProvider.</li>
 *   <li>If invalid or absent, clears the security context.</li>
 *   <li>Ensures the filter chain always continues, regardless of authentication outcome.</li>
 * </ul>
 * <b>Interactions:</b>
 * <ul>
 *   <li><b>TokenProvider:</b> Used for token validation, extracting authorities, and building Authentication objects.</li>
 *   <li><b>Spring Security:</b> Sets or clears the SecurityContextHolder for downstream filters/controllers.</li>
 * </ul>
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
     * Determines if the filter should be skipped for the current request.
     * Skips if the Authorization header is missing/invalid, method is OPTIONS, or URI is public.
     */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) throws ServletException {

        return request.getHeader(AUTHORIZATION) == null || !request.getHeader(AUTHORIZATION).startsWith(TOKEN_PREFIX) || request.getMethod().equalsIgnoreCase(HTTP_METHOD_OPTIONS) || asList(PUBLIC_ROUTES).contains(request.getRequestURI());
    }

    /**
     * Processes the JWT authentication for each request.
     * <ul>
     *   <li>Extracts token and email from the request.</li>
     *   <li>Validates the token using TokenProvider.</li>
     *   <li>If valid, sets Authentication in SecurityContextHolder.</li>
     *   <li>If invalid, clears the security context.</li>
     *   <li>Always continues the filter chain.</li>
     * </ul>
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain) throws ServletException, IOException {
        try {
            Map<String, String> values = getRequestValues(request);
            String token = getToken(request);
            if (tokenProvider.isTokenValid(values.get(EMAIL_KEY), token)) {
                List<GrantedAuthority> authorities = tokenProvider.getAuthorities(values.get(TOKEN_KEY));
                Authentication authentication = tokenProvider.getAuthentication(values.get(EMAIL_KEY), authorities, request);
                SecurityContextHolder.getContext().setAuthentication(authentication);
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
