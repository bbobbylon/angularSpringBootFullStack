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
 *      *   <li>Check if filter should run (skip for public routes, without Authorization header, OPTIONS)</li>
 *      *   <li>Extract email and token from request</li>
 *      *   <li>Validate token (signature, expiration, issuer, audience)</li>
 *      *   <li>Extract authorities/permissions from token</li>
 *      *   <li><b>NEW:</b> Check if token has authorities (access token) or is missing them (refresh token)</li>
 *      *   <li>If has authorities → set Authentication in SecurityContext (request is now authenticated)</li>
 *      *   <li>If no authorities → clear SecurityContext (request is unauthenticated, avoid refresh token bypass)</li>
 *      *   <li>Continue filter chain (even if auth failed)</li>
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
     * Processes JWT authentication for each HTTP request with intelligent token type detection.
     * <p>
     * <b>Purpose:</b><br/>
     * This is the core authentication filter that intercepts every HTTP request and decides whether
     * to authenticate it based on the JWT token in the Authorization header. It extracts the token,
     * validates it, and if valid, sets an Authentication in Spring Security's SecurityContext so
     * downstream authorization rules can check permissions.
     * * Extracts and validates a JWT from the {@code Authorization} header and (when appropriate)
     * * installs an {@link Authentication} into {@link SecurityContextHolder}.
     * *
     * * <p>Algorithm:
     * <p>
     * <b>High-Level Flow:</b>
     * <ol>
     *   <li>Read email + token via {@link #getRequestValues(HttpServletRequest)}.</li>
     *   <li>Validate token via {@link TokenProvider#isTokenValid(String, String)}.</li>
     *   <li>Extract authorities via {@link TokenProvider#getAuthorities(String)}.</li>
     *   <li>If authorities are empty, treat the token as a refresh token and do <b>not</b> authenticate.</li>
     *   <li>Otherwise, create an {@link Authentication} via
     *       {@link TokenProvider#getAuthentication(String, List, HttpServletRequest)}.</li>
     * </ol>
     * <p>
     * <b>The Authorities Check (NEW KEY LOGIC):</b>
     * <p>
     * This filter now has a critical responsibility to distinguish between two token types:
     * <pre>
     * ACCESS TOKEN (with authorities):
     *   - Example authorities: ["READ:USER", "UPDATE:USER", "DELETE:USER"]
     *   - Contains permissions the user has
     *   - Should authenticate the request (set Authentication in SecurityContext)
     *   - Valid for 30 minutes
     *   - Used for actual API requests to protected endpoints
     *
     * REFRESH TOKEN (NO authorities):
     *   - No "authorities" claim in the token
     *   - Purpose: request a new access token from /user/refresh/token endpoint
     *   - Should NOT authenticate the request for other endpoints
     *   - Valid for 5 days
     *   - If used incorrectly on other endpoints, this filter will refuse to authenticate
     * </pre>
     * <p>
     * <b>Why this check is critical:</b>
     * <ul>
     *   <li>Security: Prevents refresh tokens from being misused to access protected endpoints</li>
     *   <li>Intended behavior: Refresh tokens can only call /user/refresh/token (whitelisted)</li>
     *   <li>If refresh token sent to /user/profile (protected): filter won't set Authentication → 401</li>
     *   <li>If access token sent to /user/profile (protected): filter sets Authentication → request proceeds</li>
     * </ul>
     * <p>
     * <b>Example Scenarios:</b>
     * <p>
     * <b>Scenario 1: Client sends valid ACCESS token to /user/profile</b>
     * <pre>
     * 1. Request: GET /user/profile -H "Authorization: Bearer eyJhbGci...access_token..."
     * 2. Filter extracts token and email from header
     * 3. Token is valid (signature, expiration, issuer match)
     * 4. Extract authorities: ["READ:USER", "UPDATE:USER"]
     * 5. Authorities are NOT empty
     * 6. Create Authentication object with email + authorities
     * 7. Set Authentication in SecurityContextHolder
     * 8. Continue filter chain → controller receives authenticated request
     * 9. Controller returns user profile
     * Result: ✅ 200 OK with user data
     * </pre>
     * <p>
     * <b>Scenario 2: Client sends valid REFRESH token to /user/profile</b>
     * <pre>
     * 1. Request: GET /user/profile -H "Authorization: Bearer eyJhbGci...refresh_token..."
     * 2. Filter extracts token and email from header
     * 3. Token is valid (signature, expiration, issuer match)
     * 4. Extract authorities: [] (empty array; refresh tokens don't have authorities)
     * 5. Authorities ARE empty
     * 6. Clear SecurityContextHolder (do NOT set Authentication)
     * 7. Continue filter chain → controller sees no Authentication
     * 8. SecurityConfig rule .anyRequest().authenticated() kicks in
     * 9. CustomAuthenticationEntryPoint returns 401 Unauthorized
     * Result: ❌ 401 Unauthorized (user must use access token or refresh to get one)
     * </pre>
     * <p>
     * <b>Scenario 3: Client sends valid REFRESH token to /user/refresh/token</b>
     * <pre>
     * 1. Request: GET /user/refresh/token -H "Authorization: Bearer eyJhbGci...refresh_token..."
     * 2. CustomAuthFilter.shouldNotFilter() checks if route is public
     * 3. "/user/refresh/token" is in PUBLIC_ROUTES → filter is SKIPPED (shouldNotFilter returns true)
     * 4. Request goes directly to UserController.sendNewRefreshToken()
     * 5. Controller calls tokenProvider.getSubject(refreshToken, request)
     * 6. Token is valid (signature, expiration, no authorities required)
     * 7. Returns email from refresh token
     * 8. Controller creates new access token
     * 9. Returns access token to client
     * Result: ✅ 200 OK with new access token (refresh successful)
     * </pre>
     * <p>
     * <b>Scenario 4: Client sends MALFORMED token to any endpoint</b>
     * <pre>
     * 1. Request: GET /user/profile -H "Authorization: Bearer corrupted.data.here"
     * 2. Filter extracts token and email from header
     * 3. tokenProvider.isTokenValid() calls tokenProvider.getSubject() to extract email
     * 4. JWTDecodeException thrown (cannot decode as valid Base64)
     * 5. Caught by TokenProvider.getSubject() and mapped to BadCredentialsException
     * 6. BadCredentialsException propagates back to filter's catch block
     * 7. ExceptionUtils.processError() called
     * 8. BadCredentialsException maps to BAD_REQUEST (400)
     * 9. Returns clean JSON: {"reason": "Could not decode the token..."}
     * Result: ❌ 400 Bad Request (clear error message sent to client)
     * </pre>
     * <p>
     * <b>SecurityContext Behavior:</b>
     * <ul>
     *   <li><b>If Authentication is set:</b> Authorization rules can check authorities via getAuthorities()</li>
     *   <li><b>If Authentication is NOT set:</b> Request is treated as unauthenticated; public endpoints allowed, protected endpoints return 401</li>
     *   <li><b>clearContext():</b> Used when token is invalid OR when token has no authorities (refresh token on non-refresh endpoint)</li>
     * </ul>
     * <p>
     * <b>Why the Filter Always Continues (filterChain.doFilter):</b>
     * <ul>
     *   <li>Even if authentication fails, we must continue the filter chain</li>
     *   <li>Other filters/controllers may handle unauthenticated requests (e.g., public endpoints)</li>
     *   <li>SecurityConfig rules will enforce whether unauthenticated requests are allowed</li>
     *   <li>If not allowed, CustomAuthenticationEntryPoint will return 401</li>
     * </ul>
     * <p>
     * <b>Integration with SecurityFilterChain:</b>
     * <ul>
     *   <li>This filter runs BEFORE UsernamePasswordAuthenticationFilter</li>
     *   <li>It sets Authentication in SecurityContextHolder.getContext()</li>
     *   <li>Downstream authorization rules in SecurityConfig.securityFilterChain() use this Authentication</li>
     * </ul>
     * <p>
     * <b>Error Handling:</b>
     * <ul>
     *   <li>Any exception during processing is caught and passed to ExceptionUtils.processError()</li>
     *   <li>ExceptionUtils maps exceptions to HttpStatus and serializes error JSON response</li>
     *   <li>Known exceptions (TokenExpiredException, BadCredentialsException, ApiException) → 400/401</li>
     *   <li>Unknown exceptions → 500 Internal Server Error</li>
     * </ul>
     *
     * @param request     the HTTP request being processed
     * @param response    the HTTP response being built
     * @param filterChain the filter chain to continue processing
     * @throws ServletException if request/response processing fails
     * @throws IOException      if I/O error occurs during processing
     * @see CustomAuthFilter#shouldNotFilter(HttpServletRequest) for public route logic
     * @see TokenProvider#isTokenValid(String, String) for token validation
     * @see TokenProvider#getAuthorities(String) for authority extraction
     * @see TokenProvider#getSubject(String, HttpServletRequest) for email extraction
     * @see TokenProvider#getAuthentication(String, List, HttpServletRequest) for Authentication creation
     * @see ExceptionUtils#processError(HttpServletRequest, HttpServletResponse, Exception) for error handling
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
